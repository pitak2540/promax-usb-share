package com.james.usbshare.relay;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Minimal RFC 6455 WebSocket client (ws:// and wss://) with no dependencies,
 * so the same code runs on Android and desktop Java.
 */
public final class MiniWebSocket {

    public interface Listener {
        void onText(String text);
        void onBinary(byte[] data, int len);
        void onClose(int code, String reason);
    }

    private static final SecureRandom RNG = new SecureRandom();

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean closed;

    private MiniWebSocket(Socket socket, DataInputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    /** Thrown when the server answers the upgrade with a normal HTTP status (e.g. 503 phone offline). */
    public static final class HttpStatusException extends IOException {
        public final int status;
        public final String body;
        HttpStatusException(int status, String body) {
            super("HTTP " + status + (body.isEmpty() ? "" : ": " + body.trim()));
            this.status = status;
            this.body = body;
        }
    }

    public static MiniWebSocket connect(String url, int timeoutMs) throws IOException {
        URI u = URI.create(url);
        boolean tls = "wss".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme());
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");

        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), timeoutMs);
        raw.setTcpNoDelay(true);
        raw.setKeepAlive(true);
        Socket s = raw;
        if (tls) {
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
            SSLParameters p = ssl.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(p);
            ssl.startHandshake();
            s = ssl;
        }
        s.setSoTimeout(timeoutMs);
        OutputStream out = s.getOutputStream();
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 65536));

        byte[] keyRaw = new byte[16];
        RNG.nextBytes(keyRaw);
        String key = Base64.getEncoder().encodeToString(keyRaw);
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + (u.getPort() > 0 ? ":" + port : "") + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "User-Agent: PromaxUsbShare/1.0\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String status = readLine(in);
        int code = 0;
        String[] parts = status.split(" ");
        if (parts.length >= 2) try { code = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
        String accept = null;
        int contentLength = 0;
        boolean chunked = false;
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String k = line.substring(0, c).trim().toLowerCase();
            String v = line.substring(c + 1).trim();
            if (k.equals("sec-websocket-accept")) accept = v;
            if (k.equals("content-length")) try { contentLength = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
            if (k.equals("transfer-encoding") && v.toLowerCase().contains("chunked")) chunked = true;
        }
        if (code != 101) {
            String body = "";
            if (contentLength > 0 && contentLength < 4096) {
                byte[] b = new byte[contentLength];
                try { in.readFully(b); body = new String(b, StandardCharsets.UTF_8); } catch (IOException ignored) {}
            } else if (chunked) {
                try {
                    s.setSoTimeout(3000);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    while (bo.size() < 4096) {
                        String sz = readLine(in).trim();
                        int n = Integer.parseInt(sz.isEmpty() ? "0" : sz.split(";")[0], 16);
                        if (n <= 0) break;
                        byte[] b = new byte[n];
                        in.readFully(b);
                        bo.write(b);
                        readLine(in);
                    }
                    body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
            }
            s.close();
            throw new HttpStatusException(code, body);
        }
        String expect;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            expect = Base64.getEncoder().encodeToString(sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IOException(e);
        }
        if (!expect.equals(accept)) {
            s.close();
            throw new IOException("bad Sec-WebSocket-Accept");
        }
        s.setSoTimeout(0);
        return new MiniWebSocket(s, in, out);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("header too long");
        }
        if (c == -1 && b.size() == 0) throw new IOException("connection closed during handshake");
        return b.toString("ISO-8859-1");
    }

    public void setReadTimeout(int ms) throws IOException { socket.setSoTimeout(ms); }

    public boolean isClosed() { return closed; }

    public void sendText(String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        send(0x1, b, 0, b.length);
    }

    public void sendBinary(byte[] data, int off, int len) throws IOException {
        send(0x2, data, off, len);
    }

    private void send(int opcode, byte[] data, int off, int len) throws IOException {
        byte[] mask = new byte[4];
        RNG.nextBytes(mask);
        int hdrLen = 2 + (len < 126 ? 0 : len < 65536 ? 2 : 8) + 4;
        byte[] frame = new byte[hdrLen + len];
        frame[0] = (byte) (0x80 | opcode);
        int p;
        if (len < 126) {
            frame[1] = (byte) (0x80 | len);
            p = 2;
        } else if (len < 65536) {
            frame[1] = (byte) (0x80 | 126);
            frame[2] = (byte) (len >>> 8);
            frame[3] = (byte) len;
            p = 4;
        } else {
            frame[1] = (byte) (0x80 | 127);
            long L = len;
            for (int i = 0; i < 8; i++) frame[2 + i] = (byte) (L >>> (56 - 8 * i));
            p = 10;
        }
        System.arraycopy(mask, 0, frame, p, 4);
        p += 4;
        for (int i = 0; i < len; i++) frame[p + i] = (byte) (data[off + i] ^ mask[i & 3]);
        synchronized (writeLock) {
            if (closed && opcode != 0x8) throw new IOException("websocket closed");
            out.write(frame);
            out.flush();
        }
    }

    /**
     * Reads frames until the connection closes, calling the listener. Blocks the calling thread.
     * Always ends with exactly one onClose call.
     */
    public void readLoop(Listener l) {
        int closeCode = 1006;
        String closeReason = "connection lost";
        ByteArrayOutputStream frag = null;
        int fragOpcode = 0;
        try {
            while (true) {
                int b0 = in.readUnsignedByte();
                int b1 = in.readUnsignedByte();
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0f;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7f;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("frame too large");
                byte[] mask = null;
                if (masked) { mask = new byte[4]; in.readFully(mask); }
                byte[] payload = new byte[(int) len];
                in.readFully(payload);
                if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

                if (opcode == 0x8) { // close
                    if (payload.length >= 2) {
                        closeCode = ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
                        closeReason = new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
                    } else {
                        closeCode = 1005;
                        closeReason = "";
                    }
                    try { send(0x8, payload, 0, Math.min(payload.length, 2)); } catch (IOException ignored) {}
                    break;
                } else if (opcode == 0x9) { // ping
                    try { send(0xA, payload, 0, payload.length); } catch (IOException ignored) {}
                } else if (opcode == 0xA) { // pong
                    // ignore
                } else if (opcode == 0x0) { // continuation
                    if (frag == null) throw new IOException("unexpected continuation");
                    frag.write(payload);
                    if (fin) {
                        byte[] all = frag.toByteArray();
                        frag = null;
                        deliver(l, fragOpcode, all);
                    }
                } else if (opcode == 0x1 || opcode == 0x2) {
                    if (fin) deliver(l, opcode, payload);
                    else { frag = new ByteArrayOutputStream(); frag.write(payload); fragOpcode = opcode; }
                }
            }
        } catch (IOException e) {
            if (closed) { closeCode = 1000; closeReason = "closed"; }
        } finally {
            closed = true;
            try { socket.close(); } catch (IOException ignored) {}
            l.onClose(closeCode, closeReason);
        }
    }

    private static void deliver(Listener l, int opcode, byte[] data) {
        if (opcode == 0x1) l.onText(new String(data, StandardCharsets.UTF_8));
        else l.onBinary(data, data.length);
    }

    public void close() {
        if (closed) return;
        try {
            byte[] p = {(byte) 0x03, (byte) 0xE8}; // 1000
            send(0x8, p, 0, 2);
        } catch (IOException ignored) {}
        closed = true;
        try { socket.shutdownOutput(); } catch (Exception ignored) {}
        try { socket.close(); } catch (IOException ignored) {}
    }
}
