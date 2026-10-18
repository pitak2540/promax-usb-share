package com.james.usbshare.desktop;

import com.james.usbshare.usbip.Proto;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Finds phones across networks through Tailscale: lists the tailnet peers with
 * "tailscale status" and checks which of them answer USB/IP on port 3240.
 */
public final class Tailscale {
    private Tailscale() {}

    public static final String DOWNLOAD_URL = "https://tailscale.com/download";

    public static final class Peer {
        public String ip, name, os;
        public boolean online;
        @Override public String toString() { return name + " " + ip + " " + os + (online ? "" : " offline"); }
    }

    public static String exe() {
        List<String> c = new ArrayList<>();
        for (String env : new String[]{"ProgramW6432", "ProgramFiles", "ProgramFiles(x86)"}) {
            String pf = System.getenv(env);
            if (pf != null) c.add(pf + "\\Tailscale\\tailscale.exe");
        }
        c.add("C:\\Program Files\\Tailscale\\tailscale.exe");
        for (String p : c) if (new File(p).isFile()) return p;
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File f = new File(dir, "tailscale.exe");
                if (f.isFile()) return f.getAbsolutePath();
                f = new File(dir, "tailscale");
                if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
            }
        }
        return null;
    }

    /** Returns peers (not this PC). Empty when Tailscale is missing or logged out. */
    public static List<Peer> peers() {
        String exe = exe();
        if (exe == null) return new ArrayList<>();
        try {
            Process p = new ProcessBuilder(exe, "status").redirectErrorStream(true).start();
            byte[] out = readAll(p.getInputStream());
            p.waitFor(15, TimeUnit.SECONDS);
            return parseStatus(new String(out, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Parses plain "tailscale status" output, e.g.
     * 100.101.1.2   my-pc        james@   windows  -
     * 100.64.7.9    samsung-a56  james@   android  active; direct 1.2.3.4:41641
     * The first line is this machine and is skipped.
     */
    static List<Peer> parseStatus(String text) {
        List<Peer> out = new ArrayList<>();
        boolean first = true;
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] f = t.split("\\s+");
            if (f.length < 4 || !f[0].matches("100\\.\\d+\\.\\d+\\.\\d+")) continue;
            if (first) { first = false; continue; }
            Peer p = new Peer();
            p.ip = f[0];
            p.name = f[1];
            p.os = f[3];
            p.online = !t.contains("offline");
            out.add(p);
        }
        return out;
    }

    /** True when the host answers a USB/IP device-list request. */
    public static boolean answersUsbIp(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs * 2);
            s.getOutputStream().write(Proto.encodeOpHeader(Proto.OP_REQ_DEVLIST, 0));
            Proto.readDevList(new DataInputStream(s.getInputStream()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isTailscaleIp(String ip) {
        try {
            String[] p = ip.split("\\.");
            return p.length == 4 && Integer.parseInt(p[0]) == 100 && (Integer.parseInt(p[1]) & 0xc0) == 64;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
