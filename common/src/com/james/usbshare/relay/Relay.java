package com.james.usbshare.relay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
    // Resumable data link (survives a dropped WebSocket — mobile data handover, Wi-Fi blip)
    // =====================================================================

    /** Protocol version of the data link; the relay tells each side the other's version ("peer N"). */
    static final int LINK_VERSION = 2;

    /**
     * One USB/IP TCP connection carried over the relay. Every frame carries a sequence number and is
     * kept until the other side has certainly received it; when the WebSocket drops, the TCP side is
     * kept open, a new WebSocket is opened to the same session and both ends exchange
     * "resume &lt;last received&gt;" so missing frames are replayed. Windows never sees the device go away
     * as long as the link comes back within {@link #WINDOW_MS}. Against a peer that runs the old
     * protocol (v1) the link works exactly like before: raw frames, no resume.
     */
    static final class Link {
        interface Connector { MiniWebSocket connect() throws IOException; }

        static final int WINDOW_MS = 30000;      // how long a dropped link may stay away
        static final int PING_MS = 5000;         // keep-alive on the data link
        static final int READ_TIMEOUT_MS = 16000; // 3 missed pongs = dead link
        static final int BUF_MAX = 4 << 20;      // replay buffer per direction

        private final Socket tcp;
        private final OutputStream tcpOut;
        private final Connector reconnector;
        private final Consumer<String> log;
        private final String who;

        private final Object lock = new Object();
        private final java.util.ArrayDeque<byte[]> sent = new java.util.ArrayDeque<>(); // frames with header, oldest first
        private long sentBytes;
        private long firstSeq = 1;   // seq of sent.peekFirst()
        private long sendSeq;        // last seq handed out
        private long recvSeq;        // last seq written to tcp
        private MiniWebSocket ws;
        private boolean open;        // handshake done on the current ws -> may send
        private boolean readySeen;
        private int peerVersion = LINK_VERSION;
        private volatile boolean finished;
        private volatile boolean legacy; // peer is v1: no seq header, no resume
        private int resumes;

        /** Called while the link is in use so callers (tests, network-change hooks) can drop it. */
        private static final java.util.Set<Link> LIVE = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

        Link(Socket tcp, Connector reconnector, String who, Consumer<String> log) throws IOException {
            this.tcp = tcp;
            this.tcpOut = tcp.getOutputStream();
            this.reconnector = reconnector;
            this.who = who;
            this.log = log;
        }

        /** Simulates a network drop: closes every live data WebSocket; links must resume by themselves. */
        static void dropAll() {
            for (Link l : LIVE) l.kick();
        }

        /** Drops only the links whose name starts with {@code prefix} ("ph-" = phone side, "pc-" = PC side). */
        static void drop(String prefix) {
            for (Link l : LIVE) if (l.who.startsWith(prefix)) l.kick();
        }

        static int totalResumes() {
            int n = 0;
            for (Link l : LIVE) n += l.resumes;
            return n;
        }

        static int liveCount() { return LIVE.size(); }

        /** Drops the current WebSocket (e.g. after a network change) so the link resumes at once. */
        void kick() {
            MiniWebSocket w;
            synchronized (lock) { w = ws; }
            if (w != null) {
                Thread t = new Thread(w::close, "link-kick");
                t.setDaemon(true);
                t.start();
            }
        }

        /**
         * Runs the link on the calling thread until the TCP side closes or the link is lost for good.
         * @param first      the WebSocket already connected to the relay
         * @param readySeen  true if "ready" was already consumed by the caller (PC side)
         * @param peerVersion the peer's protocol version if already known, else 0
         */
        void run(MiniWebSocket first, boolean readySeen, int peerVersion) {
            LIVE.add(this);
            try {
                synchronized (lock) {
                    this.readySeen = readySeen;
                    if (peerVersion > 0) setPeerVersion(peerVersion);
                }
                Thread reader = new Thread(this::tcpToLink, "link-up-" + who);
                reader.setDaemon(true);
                reader.start();
                MiniWebSocket cur = first;
                while (!finished) {
                    serve(cur);
                    if (finished || legacy) break;
                    long lostAt = System.currentTimeMillis();
                    log.accept("สัญญาณหลุด — กำลังต่อกลับเข้าเซสชันเดิม (อุปกรณ์ยังค้างอยู่บนคอม)");
                    cur = null;
                    int delay = 300;
                    while (!finished && System.currentTimeMillis() - lostAt < WINDOW_MS) {
                        try { Thread.sleep(delay); } catch (InterruptedException e) { break; }
                        delay = Math.min(delay * 2, 2000);
                        try {
                            cur = reconnector.connect();
                            break;
                        } catch (MiniWebSocket.HttpStatusException e) {
                            if (e.status == 403) { log.accept("Relay ไม่อนุญาต: " + e.body.trim()); break; }
                            // 503 / 410: the other side is not back yet — keep trying inside the window
                        } catch (Exception ignored) {}
                    }
                    if (cur == null) {
                        log.accept("ต่อกลับไม่ได้ภายใน " + (WINDOW_MS / 1000) + " วินาที — ตัดการเชื่อมต่ออุปกรณ์");
                        break;
                    }
                    synchronized (lock) { this.readySeen = false; }
                    resumes++;
                }
            } finally {
                LIVE.remove(this);
                finish(false);
            }
        }

        int resumes() { return resumes; }

        private void setPeerVersion(int v) {
            peerVersion = v;
            legacy = v < 2;
            if (legacy) open = true; // nothing to negotiate with an old peer
        }

        /** Serves one WebSocket until it closes. */
        private void serve(MiniWebSocket w) {
            synchronized (lock) {
                ws = w;
                open = legacy;
            }
            try { w.setReadTimeout(READ_TIMEOUT_MS); } catch (IOException ignored) {}
            Thread pinger = new Thread(() -> {
                while (!finished && !w.isClosed()) {
                    try {
                        Thread.sleep(PING_MS);
                        if (w.isClosed()) return;
                        w.sendText("ping");
                        boolean askAgain;
                        synchronized (lock) { askAgain = readySeen && !open && !legacy; }
                        if (askAgain) w.sendText("resume " + recvSeq); // peer may not have been there yet
                    } catch (Exception e) {
                        w.close();
                        return;
                    }
                }
            }, "link-ping-" + who);
            pinger.setDaemon(true);
            pinger.start();
            // the PC side consumed "ready" before handing the socket over: start the handshake now
            boolean startNow;
            synchronized (lock) { startNow = readySeen && !legacy; }
            if (startNow) {
                try { w.sendText("resume " + recvSeq); } catch (IOException e) { w.close(); }
            }
            w.readLoop(new MiniWebSocket.Listener() {
                @Override public void onText(String t) {
                    try {
                        if (t.equals("ready") || t.startsWith("ready ")) {
                            boolean send;
                            synchronized (lock) { readySeen = true; send = !legacy; }
                            if (send) w.sendText("resume " + recvSeq);
                        } else if (t.startsWith("peer ")) {
                            int v = 1;
                            try { v = Integer.parseInt(t.substring(5).trim()); } catch (NumberFormatException ignored) {}
                            synchronized (lock) { setPeerVersion(v); }
                        } else if (t.startsWith("resume ")) {
                            long peerRecv = Long.parseLong(t.substring(7).trim());
                            if (!replay(w, peerRecv)) {
                                log.accept("ต่อกลับไม่ได้ — ข้อมูลที่ค้างระหว่างหลุดมากเกินไป");
                                finish(true);
                            }
                        }
                    } catch (IOException e) {
                        w.close();
                    }
                }
                @Override public void onBinary(byte[] d, int len) {
                    try {
                        if (legacy) {
                            tcpOut.write(d, 0, len);
                        } else {
                            if (len < 4) return;
                            long seq = ((long) (d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16) | ((d[2] & 0xff) << 8) | (d[3] & 0xff);
                            synchronized (lock) {
                                if (seq <= recvSeq) return; // replayed frame we already have
                                recvSeq = seq;
                            }
                            tcpOut.write(d, 4, len - 4);
                        }
                        tcpOut.flush();
                    } catch (IOException e) {
                        finish(true); // the local USB/IP side is gone
                    }
                }
                @Override public void onClose(int c, String r) {
                    synchronized (lock) {
                        if (ws == w) { ws = null; open = false; }
                    }
                    // 4001 = a newer link with our id took over, 4002 = the relay says the session is over: no resume
                    if (c == 4001 || c == 4002) finish(false);
                }
            });
        }

        /** Replays every frame the peer has not received yet; returns false when they are no longer buffered. */
        private boolean replay(MiniWebSocket w, long peerRecv) throws IOException {
            synchronized (lock) {
                if (peerRecv + 1 < firstSeq) return false;
                long skip = peerRecv - firstSeq + 1;
                java.util.Iterator<byte[]> it = sent.iterator();
                while (skip-- > 0 && it.hasNext()) it.next();
                while (it.hasNext()) {
                    byte[] f = it.next();
                    w.sendBinary(f, 0, f.length);
                }
                open = true;
            }
            return true;
        }

        private void tcpToLink() {
            byte[] buf = new byte[60 * 1024];
            try {
                InputStream in = tcp.getInputStream();
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (legacy) {
                        MiniWebSocket w;
                        synchronized (lock) { w = ws; }
                        if (w == null) break;
                        w.sendBinary(buf, 0, n);
                        continue;
                    }
                    byte[] f = new byte[4 + n];
                    System.arraycopy(buf, 0, f, 4, n);
                    MiniWebSocket w = null;
                    synchronized (lock) {
                        long seq = ++sendSeq;
                        f[0] = (byte) (seq >>> 24); f[1] = (byte) (seq >>> 16); f[2] = (byte) (seq >>> 8); f[3] = (byte) seq;
                        sent.addLast(f);
                        sentBytes += f.length;
                        while (sentBytes > BUF_MAX && sent.size() > 1) { sentBytes -= sent.pollFirst().length; firstSeq++; }
                        if (open && ws != null) w = ws;
                    }
                    if (w != null) {
                        try { w.sendBinary(f, 0, f.length); } catch (IOException e) { w.close(); } // frame stays buffered; replayed after resume
                    }
                }
            } catch (IOException ignored) {
            }
            finish(true); // local side closed: tell the peer and stop
        }

        private void finish(boolean tellPeer) {
            if (finished) return;
            finished = true;
            MiniWebSocket w;
            synchronized (lock) { w = ws; }
            if (w != null) {
                if (tellPeer) try { w.sendText("bye"); } catch (IOException ignored) {}
                w.close();
            }
            try { tcp.close(); } catch (IOException ignored) {}
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
            Link.dropAll(); // data links resume into the same session
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
                    final String dataUrl = wsBase(relayUrl) + "/p/" + code + "/data?id=" + id + "&v=" + LINK_VERSION;
                    ws = MiniWebSocket.connect(dataUrl, 15000);
                    log.accept("เปิดช่องข้อมูลกับคอมแล้ว");
                    Link link = new Link(tcp, () -> MiniWebSocket.connect(dataUrl + "&resume=1", 15000), "ph-" + id, log);
                    link.run(ws, false, 0);
                    return;
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
                ws = MiniWebSocket.connect(dataUrl(id), 15000);
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
            // wait for "ready" (preceded by "peer N" on a current relay) before any USB/IP byte moves
            final int[] peerV = {0};
            final boolean[] gotReady = {false}, closed = {false};
            try { ws.setReadTimeout(20000); } catch (IOException ignored) {}
            MiniWebSocket.Listener pre = new MiniWebSocket.Listener() {
                @Override public void onText(String t) {
                    if (t.equals("ready") || t.startsWith("ready ")) gotReady[0] = true;
                    else if (t.startsWith("peer ")) { try { peerV[0] = Integer.parseInt(t.substring(5).trim()); } catch (NumberFormatException ignored) {} }
                }
                @Override public void onBinary(byte[] d, int len) {}
                @Override public void onClose(int c, String r) { closed[0] = true; }
            };
            while (!gotReady[0] && !closed[0]) ws.readOne(pre);
            if (closed[0]) {
                log.accept("มือถือไม่ตอบผ่าน Relay (หมดเวลา)");
                try { tcp.close(); } catch (Exception ignored) {}
                return;
            }
            if (peerV[0] == 0) peerV[0] = 1; // no "peer N": old relay or old phone -> plain frames, no resume
            try {
                final String fid = id;
                Link link = new Link(tcp, () -> MiniWebSocket.connect(dataUrl(fid) + "&resume=1", 15000), "pc-" + id, log);
                link.run(ws, true, peerV[0]);
            } catch (IOException e) {
                ws.close();
                try { tcp.close(); } catch (Exception ignored) {}
            }
        }

        private String dataUrl(String id) {
            return wsBase(relayUrl) + "/c/" + code + "/data?id=" + id + "&v=" + LINK_VERSION + credParams(lic, dev);
        }

        /** Drops every live data link so it resumes at once (call after a network change). */
        public static void reconnectLinks() { Link.dropAll(); }
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
