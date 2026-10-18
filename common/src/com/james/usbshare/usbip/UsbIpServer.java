package com.james.usbshare.usbip;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal, platform-neutral USB/IP v1.1.1 server. */
public class UsbIpServer {

    public interface Listener {
        void onSessionsChanged(List<String> attachedBusids);
    }

    private final Backend backend;
    private final int port;
    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile Listener listener;
    public final AtomicLong urbCount = new AtomicLong();

    public UsbIpServer(Backend backend, int port) {
        this.backend = backend;
        this.port = port;
    }

    public void setListener(Listener l) { this.listener = l; }

    public int getPort() { return serverSocket != null ? serverSocket.getLocalPort() : port; }

    public synchronized void start() throws IOException {
        if (running) return;
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        serverSocket = ss;
        running = true;
        Thread t = new Thread(this::acceptLoop, "usbip-accept");
        t.setDaemon(true);
        t.start();
        backend.log("เซิร์ฟเวอร์ USB/IP เริ่มทำงานที่พอร์ต " + ss.getLocalPort());
    }

    public synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        for (Session s : new ArrayList<>(sessions.values())) s.shutdown();
        sessions.clear();
        fireSessions();
    }

    public boolean isRunning() { return running; }

    /** Drops the client attached to this busid (e.g. after the device was unplugged). */
    public void kick(String busid) {
        Session s = sessions.get(busid);
        if (s != null) s.shutdown();
    }

    public List<String> attachedBusids() { return new ArrayList<>(sessions.keySet()); }

    private void fireSessions() {
        Listener l = listener;
        if (l != null) l.onSessionsChanged(attachedBusids());
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                try { s.setReceiveBufferSize(256 * 1024); s.setSendBufferSize(256 * 1024); } catch (SocketException ignored) {}
                Thread t = new Thread(() -> handle(s), "usbip-conn-" + s.getInetAddress().getHostAddress());
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) backend.log("accept error: " + e.getMessage());
            }
        }
    }

    private void handle(Socket sock) {
        String peer = sock.getInetAddress().getHostAddress();
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream(), 64 * 1024));
            OutputStream out = sock.getOutputStream();
            byte[] op = new byte[8];
            in.readFully(op);
            ByteBuffer b = ByteBuffer.wrap(op).order(ByteOrder.BIG_ENDIAN);
            int version = b.getShort() & 0xffff;
            int code = b.getShort() & 0xffff;
            if (code == Proto.OP_REQ_DEVLIST) {
                List<Proto.DeviceInfo> devs = backend.listDevices();
                List<Proto.DeviceInfo> free = new ArrayList<>();
                for (Proto.DeviceInfo d : devs) if (!sessions.containsKey(d.busid)) free.add(d);
                out.write(Proto.encodeDevList(free));
                out.flush();
                backend.log(peer + " ขอรายการอุปกรณ์ (" + free.size() + " ชิ้น)");
                sock.close();
            } else if (code == Proto.OP_REQ_IMPORT) {
                byte[] busRaw = new byte[32];
                in.readFully(busRaw);
                ByteBuffer bb = ByteBuffer.wrap(busRaw);
                String busid = Proto.getFixedString(bb, 32);
                if (version != Proto.VERSION) backend.log("คำเตือน: client ใช้โปรโตคอลเวอร์ชัน " + Integer.toHexString(version));
                if (sessions.containsKey(busid)) {
                    backend.log(peer + " ขอใช้ " + busid + " แต่มีเครื่องอื่นใช้อยู่");
                    out.write(Proto.encodeImportReply(null));
                    sock.close();
                    return;
                }
                Backend.Attached dev;
                try {
                    dev = backend.attach(busid);
                } catch (Exception e) {
                    backend.log(peer + " เชื่อม " + busid + " ไม่สำเร็จ: " + e.getMessage());
                    out.write(Proto.encodeImportReply(null));
                    sock.close();
                    return;
                }
                Session session = new Session(busid, sock, in, out, dev, peer);
                if (sessions.putIfAbsent(busid, session) != null) {
                    dev.close();
                    out.write(Proto.encodeImportReply(null));
                    sock.close();
                    return;
                }
                out.write(Proto.encodeImportReply(dev.info()));
                out.flush();
                backend.log(peer + " เชื่อมต่อ " + dev.info());
                fireSessions();
                try {
                    session.run();
                } finally {
                    sessions.remove(busid, session);
                    session.shutdown();
                    backend.log(peer + " ยกเลิกการเชื่อมต่อ " + busid);
                    fireSessions();
                }
            } else {
                backend.log(peer + " ส่งคำสั่งไม่รู้จัก 0x" + Integer.toHexString(code));
                sock.close();
            }
        } catch (IOException e) {
            if (running) backend.log(peer + ": " + e.getMessage());
            try { sock.close(); } catch (IOException ignored) {}
        }
    }

    /** One imported device bound to one TCP connection. */
    private final class Session {
        final String busid;
        final Socket sock;
        final DataInputStream in;
        final OutputStream out;
        final Backend.Attached dev;
        final String peer;
        final Map<Integer, Proto.Cmd> pending = new ConcurrentHashMap<>();
        volatile boolean closed;

        Session(String busid, Socket sock, DataInputStream in, OutputStream out, Backend.Attached dev, String peer) {
            this.busid = busid; this.sock = sock; this.in = in; this.out = out; this.dev = dev; this.peer = peer;
        }

        void send(byte[] msg) {
            if (closed) return;
            synchronized (out) {
                try {
                    out.write(msg);
                    out.flush();
                } catch (IOException e) {
                    shutdown();
                }
            }
        }

        void run() throws IOException {
            while (!closed) {
                Proto.Cmd c;
                try {
                    c = Proto.readCmd(in);
                } catch (IOException e) {
                    if (closed) return;
                    throw e;
                }
                if (c == null) return;
                if (c.command == Proto.CMD_SUBMIT) {
                    urbCount.incrementAndGet();
                    final int seq = c.seqnum;
                    final boolean iso = c.isIso();
                    if (dev.isGone()) {
                        send(Proto.encodeRetSubmit(seq, Proto.ENODEV, null, 0, iso));
                        continue;
                    }
                    pending.put(seq, c);
                    dev.submit(c, (status, inData, actual) -> {
                        Proto.Cmd mine = pending.remove(seq);
                        if (mine == null) return; // unlinked already
                        int len = Math.max(0, actual);
                        byte[] payload = (mine.direction == Proto.DIR_IN) ? inData : null;
                        if (payload != null && len > payload.length) len = payload.length;
                        if (payload != null && len > mine.transferBufferLength) len = mine.transferBufferLength;
                        send(Proto.encodeRetSubmit(seq, status, payload, len, iso));
                    });
                } else if (c.command == Proto.CMD_UNLINK) {
                    Proto.Cmd target = pending.remove(c.unlinkSeqnum);
                    if (target != null) {
                        dev.cancel(c.unlinkSeqnum);
                        send(Proto.encodeRetUnlink(c.seqnum, Proto.ECONNRESET));
                    } else {
                        send(Proto.encodeRetUnlink(c.seqnum, 0));
                    }
                }
            }
        }

        void shutdown() {
            if (closed) return;
            closed = true;
            try { sock.close(); } catch (IOException ignored) {}
            for (Integer seq : new ArrayList<>(pending.keySet())) dev.cancel(seq);
            pending.clear();
            dev.close();
        }
    }
}
