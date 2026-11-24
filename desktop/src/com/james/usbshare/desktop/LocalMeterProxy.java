package com.james.usbshare.desktop;

import com.james.usbshare.relay.Relay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny localhost splice that lets the PC count real bytes for DIRECT attachments (LAN, Radmin,
 * Tailscale) the same way the relay already does. usbip-win2 is pointed at 127.0.0.1:&lt;localPort&gt;
 * instead of the sharer, and this proxy copies the bytes straight through to the sharer, counting
 * them into {@link Relay.Meter} (up = PC→sharer requests, down = sharer→PC responses).
 *
 * <p>It is a dumb byte copier — no parsing, no framing — so it adds only a sub-millisecond localhost
 * hop and does not touch the USB/IP data. The trade-off is that the data now passes through this app,
 * so the app must stay open while a device is in use (closing it drops the device). One listener per
 * target host is reused across devices; {@link #hostForPort} maps a local port back to the real host.
 */
final class LocalMeterProxy {
    private final Map<String, Target> byTarget = new ConcurrentHashMap<>();
    private final Map<Integer, String> portToHost = new ConcurrentHashMap<>();

    /** Local port to attach to for {@code host:port}; starts a listener on first use. */
    int localPortFor(String host, int port) throws IOException {
        Target t = byTarget.computeIfAbsent(host + ":" + port, k -> new Target(host, port));
        int lp = t.ensureStarted();
        portToHost.put(lp, host);
        return lp;
    }

    /** The real sharer host behind a 127.0.0.1 local port, or null if this port is not ours. */
    String hostForPort(int localPort) { return portToHost.get(localPort); }

    void stopAll() {
        for (Target t : byTarget.values()) t.stop();
        byTarget.clear();
        portToHost.clear();
    }

    private static final class Target {
        private final String host;
        private final int port;
        private volatile ServerSocket server;

        Target(String host, int port) { this.host = host; this.port = port; }

        synchronized int ensureStarted() throws IOException {
            if (server != null && !server.isClosed()) return server.getLocalPort();
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("127.0.0.1", 0)); // any free local port
            server = ss;
            Thread t = new Thread(this::acceptLoop, "meter-proxy-" + host);
            t.setDaemon(true);
            t.start();
            return ss.getLocalPort();
        }

        private void acceptLoop() {
            ServerSocket ss = server;
            while (ss != null && !ss.isClosed()) {
                try {
                    Socket client = ss.accept();
                    client.setTcpNoDelay(true);
                    Thread h = new Thread(() -> bridge(client), "meter-conn-" + host);
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) {
                    return; // listener closed
                }
            }
        }

        private void bridge(Socket client) {
            Socket up = new Socket();
            try {
                up.setTcpNoDelay(true);
                up.connect(new InetSocketAddress(host, port), 15000);
                Thread a = new Thread(() -> pump(client, up, true), "meter-up");   // PC -> sharer (ถาม)
                a.setDaemon(true);
                a.start();
                pump(up, client, false); // sharer -> PC (ตอบ), on this thread
            } catch (IOException e) {
                // connection failed/ended — just tear both sides down below
            } finally {
                close(client);
                close(up);
            }
        }

        private void pump(Socket from, Socket to, boolean up) {
            byte[] buf = new byte[32 * 1024];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                    if (up) Relay.Meter.addUp(n); else Relay.Meter.addDown(n);
                }
            } catch (IOException ignored) {
            } finally {
                try { to.shutdownOutput(); } catch (IOException ignored) {}
            }
        }

        private static void close(Socket s) { try { s.close(); } catch (IOException ignored) {} }

        void stop() {
            try { if (server != null) server.close(); } catch (IOException ignored) {}
            server = null;
        }
    }
}
