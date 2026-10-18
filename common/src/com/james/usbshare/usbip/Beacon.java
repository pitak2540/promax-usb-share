package com.james.usbshare.usbip;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiny UDP discovery packet (not part of USB/IP). The phone broadcasts it to port 3241
 * every couple of seconds so the PC program can find it without typing an IP.
 *
 * Format (UTF-8 text):
 *   JUSB1
 *   name=&lt;phone name&gt;
 *   port=3240
 *   dev=&lt;busid&gt;|&lt;vid hex&gt;|&lt;pid hex&gt;|&lt;in use 0/1&gt;|&lt;manufacturer&gt;|&lt;product&gt;
 */
public final class Beacon {
    public static final int PORT = 3241;
    public static final String MAGIC = "JUSB1";

    public String name = "";
    public int port = Proto.PORT;
    public String host = ""; // filled in by the receiver
    public final List<Dev> devices = new ArrayList<>();

    public static final class Dev {
        public String busid = "";
        public int vid, pid;
        public boolean inUse;
        public String manufacturer = "", product = "";
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim();
    }

    public byte[] encode() {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n');
        sb.append("name=").append(clean(name)).append('\n');
        sb.append("port=").append(port).append('\n');
        for (Dev d : devices) {
            sb.append("dev=").append(clean(d.busid)).append('|')
              .append(String.format("%04x", d.vid)).append('|')
              .append(String.format("%04x", d.pid)).append('|')
              .append(d.inUse ? '1' : '0').append('|')
              .append(clean(d.manufacturer)).append('|')
              .append(clean(d.product)).append('\n');
        }
        byte[] raw = sb.toString().getBytes(StandardCharsets.UTF_8);
        if (raw.length > 1400) { // keep it in one datagram
            byte[] cut = new byte[1400];
            System.arraycopy(raw, 0, cut, 0, 1400);
            return cut;
        }
        return raw;
    }

    public static Beacon parse(byte[] data, int len) {
        String s = new String(data, 0, len, StandardCharsets.UTF_8);
        String[] lines = s.split("\n");
        if (lines.length == 0 || !lines[0].trim().equals(MAGIC)) return null;
        Beacon b = new Beacon();
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i];
            int eq = l.indexOf('=');
            if (eq < 0) continue;
            String k = l.substring(0, eq), v = l.substring(eq + 1);
            try {
                if (k.equals("name")) b.name = v;
                else if (k.equals("port")) b.port = Integer.parseInt(v.trim());
                else if (k.equals("dev")) {
                    String[] p = v.split("\\|", -1);
                    if (p.length < 6) continue;
                    Dev d = new Dev();
                    d.busid = p[0];
                    d.vid = Integer.parseInt(p[1], 16);
                    d.pid = Integer.parseInt(p[2], 16);
                    d.inUse = p[3].equals("1");
                    d.manufacturer = p[4];
                    d.product = p[5];
                    b.devices.add(d);
                }
            } catch (NumberFormatException ignored) {}
        }
        return b;
    }
}
