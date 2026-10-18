package com.james.usbshare.relay;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Cross-network mode through the PROMAX USB Share relay (Cloudflare Worker).
 * Phone side: {@link Phone} keeps a control WebSocket open and bridges each requested
 * connection to the local USB/IP server. PC side: {@link Forwarder} listens on 127.0.0.1
 * and bridges each local TCP connection (from usbip.exe or the GUI) to the phone.
 */
public final class Relay {
    private Relay() {}

    /** Default relay; can be changed in both apps. */
    public static final String DEFAULT_URL = "https://james-usb-relay.pitak2540.workers.dev";

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RNG = new SecureRandom();

    public static String newCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    public static String normalizeCode(String c) {
        if (c == null) return "";
        return c.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    static String wsBase(String relayUrl) {
        String u = relayUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.startsWith("https://")) return "wss://" + u.substring(8);
        if (u.startsWith("http://")) return "ws://" + u.substring(7);
        if (u.startsWith("ws://") || u.startsWith("wss://")) return u;
        return "wss://" + u;
    }

    static String httpBase(String relayUrl) {
        String w = wsBase(relayUrl);
        if (w.startsWith("wss://")) return "https://" + w.substring(6);
        return "http://" + w.substring(5);
    }

    /** "&lic=...&dev=..." — lets the relay check the license on the server side too. */
    static String credParams(String lic, byte[] dev) {
        StringBuilder sb = new StringBuilder();
        try {
            if (dev != null) {
                sb.append("&dev=");
                for (byte b : dev) sb.append(String.format("%02x", b & 0xff));
            }
            if (lic != null && !lic.trim().isEmpty()) sb.append("&lic=").append(URLEncoder.encode(lic.trim(), "UTF-8"));
        } catch (Exception ignored) {}
        return sb.toString();
    }

    static String randomId() {
        StringBuilder sb = new StringBuilder();
        String a = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 16; i++) sb.append(a.charAt(RNG.nextInt(a.length())));
        return sb.toString();
    }

    /** Result of a status query: online flag and the phone's name. */
    public static final class Status {
        public boolean reachable;
        public boolean online;
        public String name = "";
        public String error = "";
    }

    public static Status status(String relayUrl, String code) {
        Status st = new Status();
        try {
            URL u = new URL(httpBase(relayUrl) + "/c/" + normalizeCode(code) + "/status");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            int rc = c.getResponseCode();
            InputStream in = rc < 400 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (in != null) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            }
            String body = bo.toString("UTF-8");
            st.reachable = rc == 200;
            st.online = body.contains("\"online\":true");
            int i = body.indexOf("\"name\":\"");
            if (i >= 0) {
                int j = body.indexOf('"', i + 8);
                if (j > i) st.name = body.substring(i + 8, j);
            }
            if (rc != 200) st.error = "HTTP " + rc;
        } catch (Exception e) {
            st.error = e.getMessage();
        }
        return st;
    }

    /** Copies a TCP stream into a WebSocket as binary frames. */
    static void pumpTcpToWs(Socket tcp, MiniWebSocket ws) {
        byte[] buf = new byte[64 * 1024];
        try {
            InputStream in = tcp.getInputStream();
            int n;
            while ((n = in.read(buf)) > 0) ws.sendBinary(buf, 0, n);
        } catch (Exception ignored) {
        } finally {
            ws.close();
            try { tcp.close(); } catch (Exception ignored) {}
        }
    }

    // =====================================================================
    // Phone side
    // =====================================================================

    public static final class Phone {
        private final String relayUrl, code, name;
        private volatile String lic;
        private volatile byte[] dev;
        private volatile String lastError = "";

        /** License key (or null during the trial) and this device's 8-byte id. */
        public Phone credentials(String licenseKey, byte[] deviceHash) {
            this.lic = licenseKey;
            this.dev = deviceHash;
            return this;
        }

        public String lastError() { return lastError; }
        private final int localPort;
        private final Consumer<String> log;
        private volatile boolean running;
        private volatile boolean connected;
        private volatile MiniWebSocket ctl;
        private Thread thread;

        public Phone(String relayUrl, String code, String name, int localPort, Consumer<String> log) {
            this.relayUrl = relayUrl;
            this.code = normalizeCode(code);
            this.name = name;
            this.localPort = localPort;
            this.log = log;
        }

        public boolean isConnected() { return connected; }
        public String code() { return code; }

        public synchronized void start() {
            if (running) return;
            running = true;
            thread = new Thread(this::loop, "relay-ctl");
            thread.setDaemon(true);
            thread.start();
        }

        /** Drops the current control link so the loop reconnects at once (call after a network change). */
        public void reconnect() {
            MiniWebSocket w = ctl;
            if (w != null && running) {
                Thread t = new Thread(w::close, "relay-reconnect");
                t.setDaemon(true);
                t.start();
            }
        }

        public synchronized void stop() {
            running = false;
            MiniWebSocket w = ctl;
            if (w != null) {
                Thread t = new Thread(w::close, "relay-close"); // never do network I/O on the caller (Android main) thread
                t.setDaemon(true);
                t.start();
            }
            if (thread != null) thread.interrupt();
        }

        private void loop() {
            int backoff = 1000;
            while (running) {
                String url;
                try {
                    url = wsBase(relayUrl) + "/p/" + code + "/ctl?name=" + URLEncoder.encode(name, "UTF-8") + credParams(lic, dev);
                } catch (Exception e) {
                    return;
                }
                try {
                    MiniWebSocket ws = MiniWebSocket.connect(url, 15000);
                    ctl = ws;
                    // the relay answers our ping every 25 s; 65 s of silence means the link is dead
                    // (e.g. phone switched Wi-Fi <-> mobile data) -> drop it and reconnect
                    ws.setReadTimeout(65000);
                    connected = true;
                    backoff = 1000;
                    log.accept("เชื่อมต่อ Relay แล้ว — รหัสจับคู่ " + code);
                    Thread pinger = new Thread(() -> {
                        while (running && !ws.isClosed()) {
                            try {
                                Thread.sleep(25000);
                                ws.sendText("ping");
                            } catch (Exception e) {
                                ws.close();
                                return;
                            }
                        }
                    }, "relay-ping");
                    pinger.setDaemon(true);
                    pinger.start();
                    ws.readLoop(new MiniWebSocket.Listener() {
                        @Override public void onText(String t) {
                            if (t.startsWith("open ")) {
                                log.accept("คอมขอเชื่อมต่อผ่าน Relay");
                                openData(t.substring(5).trim());
                            }
                        }
                        @Override public void onBinary(byte[] d, int len) {}
                        @Override public void onClose(int c, String r) {
                            if (c == 4000) {
                                // another connection with this code took over: stop instead of fighting over it
                                log.accept("มีการเชื่อมต่อใหม่ด้วยรหัสนี้แทนที่แล้ว");
                                running = false;
                                return;
                            }
                            if (running) log.accept("Relay หลุด (" + c + " " + r + ") กำลังต่อใหม่...");
                        }
                    });
                } catch (MiniWebSocket.HttpStatusException e) {
                    if (e.status == 403) {
                        lastError = e.body.trim();
                        log.accept("Relay ไม่อนุญาต: " + lastError);
                        backoff = 60000; // license problem: no point hammering the server
                    } else if (running) log.accept("ต่อ Relay ไม่ได้: " + e.getMessage());
                } catch (Exception e) {
                    if (running) log.accept("ต่อ Relay ไม่ได้: " + e.getMessage());
                }
                connected = false;
                ctl = null;
                if (!running) break;
                try { Thread.sleep(backoff); } catch (InterruptedException e) { break; }
                backoff = Math.min(backoff * 2, 60000);
            }
            connected = false;
        }

        private void openData(String id) {
            Thread t = new Thread(() -> {
                Socket tcp = null;
                MiniWebSocket ws = null;
                try {
                    tcp = new Socket();
                    tcp.connect(new InetSocketAddress("127.0.0.1", localPort), 5000);
                    tcp.setTcpNoDelay(true);
                    ws = MiniWebSocket.connect(wsBase(relayUrl) + "/p/" + code + "/data?id=" + id, 15000);
                    log.accept("เปิดช่องข้อมูลกับคอมแล้ว");
                    final Socket ftcp = tcp;
                    final MiniWebSocket fws = ws;
                    Thread up = new Thread(() -> pumpTcpToWs(ftcp, fws), "relay-up-" + id);
                    up.setDaemon(true);
                    up.start();
                    final OutputStream out = tcp.getOutputStream();
                    ws.readLoop(new MiniWebSocket.Listener() {
                        @Override public void onText(String s) {}
                        @Override public void onBinary(byte[] d, int len) {
                            try { out.write(d, 0, len); out.flush(); } catch (Exception e) { fws.close(); }
                        }
                        @Override public void onClose(int c, String r) {
                            try { ftcp.close(); } catch (Exception ignored) {}
                        }
                    });
                } catch (Exception e) {
                    log.accept("Relay: เปิดช่องข้อมูลไม่สำเร็จ: " + e.getMessage());
                    if (ws != null) ws.close();
                    try { if (tcp != null) tcp.close(); } catch (Exception ignored) {}
                }
            }, "relay-data-" + id);
            t.setDaemon(true);
            t.start();
        }
    }

    // =====================================================================
    // PC side
    // =====================================================================

    public static final class Forwarder {
        private final String relayUrl, code;
        private volatile String lic;
        private volatile byte[] dev;

        public Forwarder credentials(String licenseKey, byte[] deviceHash) {
            this.lic = licenseKey;
            this.dev = deviceHash;
            return this;
        }
        private final Consumer<String> log;
        private ServerSocket server;
        private volatile boolean running;
        private final AtomicBoolean warnedOffline = new AtomicBoolean();
        private volatile long lastOfflineLog;

        public Forwarder(String relayUrl, String code, Consumer<String> log) {
            this.relayUrl = relayUrl;
            this.code = normalizeCode(code);
            this.log = log;
        }

        public String code() { return code; }
        public int port() { return server == null ? -1 : server.getLocalPort(); }

        /** Listens on 127.0.0.1 at the first free port starting from {@code firstPort}. */
        public void start(int firstPort) throws Exception {
            Exception last = null;
            for (int p = firstPort; p < firstPort + 50; p++) {
                try {
                    ServerSocket ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress("127.0.0.1", p));
                    server = ss;
                    break;
                } catch (Exception e) {
                    last = e;
                }
            }
            if (server == null) throw last;
            running = true;
            Thread t = new Thread(() -> {
                while (running) {
                    try {
                        Socket s = server.accept();
                        s.setTcpNoDelay(true);
                        Thread h = new Thread(() -> bridge(s), "fwd-conn");
                        h.setDaemon(true);
                        h.start();
                    } catch (Exception e) {
                        if (!running) return;
                    }
                }
            }, "fwd-accept-" + code);
            t.setDaemon(true);
            t.start();
        }

        public void stop() {
            running = false;
            try { if (server != null) server.close(); } catch (Exception ignored) {}
        }

        private void bridge(Socket tcp) {
            String id = randomId();
            MiniWebSocket ws;
            try {
                ws = MiniWebSocket.connect(wsBase(relayUrl) + "/c/" + code + "/data?id=" + id + credParams(lic, dev), 15000);
            } catch (MiniWebSocket.HttpStatusException e) {
                if (e.status == 503) {
                    long now = System.currentTimeMillis();
                    if (now - lastOfflineLog > 10000) {
                        lastOfflineLog = now;
                        log.accept("มือถือรหัส " + code + " ยังไม่ออนไลน์ใน Relay (เปิดแอพบนมือถือ และเปิดโหมดข้ามเครือข่าย)");
                    }
                } else {
                    log.accept(e.status == 403 ? "Relay ไม่อนุญาต: " + e.body.trim() : "Relay ตอบกลับ " + e.getMessage());
                }
                try { tcp.close(); } catch (Exception ignored) {}
                return;
            } catch (Exception e) {
                log.accept("ต่อ Relay ไม่ได้: " + e.getMessage());
                try { tcp.close(); } catch (Exception ignored) {}
                return;
            }
            warnedOffline.set(false);
            final CountDownLatch ready = new CountDownLatch(1);
            final OutputStream out;
            try {
                out = tcp.getOutputStream();
            } catch (Exception e) {
                ws.close();
                return;
            }
            final MiniWebSocket fws = ws;
            Thread reader = new Thread(() -> fws.readLoop(new MiniWebSocket.Listener() {
                @Override public void onText(String s) {
                    if (s.equals("ready")) ready.countDown();
                }
                @Override public void onBinary(byte[] d, int len) {
                    try { out.write(d, 0, len); out.flush(); } catch (Exception e) { fws.close(); }
                }
                @Override public void onClose(int c, String r) {
                    ready.countDown();
                    try { tcp.close(); } catch (Exception ignored) {}
                }
            }), "fwd-down");
            reader.setDaemon(true);
            reader.start();
            try {
                if (!ready.await(20, TimeUnit.SECONDS) || ws.isClosed()) {
                    log.accept("มือถือไม่ตอบผ่าน Relay (หมดเวลา)");
                    ws.close();
                    try { tcp.close(); } catch (Exception ignored) {}
                    return;
                }
            } catch (InterruptedException e) {
                ws.close();
                return;
            }
            pumpTcpToWs(tcp, ws);
        }
    }

    /** Small helper used by tests and tools. */
    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }
}
