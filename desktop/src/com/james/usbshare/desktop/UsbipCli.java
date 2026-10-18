package com.james.usbshare.desktop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Thin wrapper around usbip-win2's usbip.exe (the signed Windows USB/IP client). */
public final class UsbipCli {

    public static final String DOWNLOAD_URL = "https://github.com/vadimgrn/usbip-win2/releases";

    public static final class Result {
        public final int exit;
        public final String output;
        Result(int exit, String output) { this.exit = exit; this.output = output; }
        public boolean ok() { return exit == 0; }
    }

    public static final class Port {
        public int port;
        public String description = "";
        public String remote = "";
        @Override public String toString() { return "Port " + port + " " + description + " " + remote; }
    }

    private String exe;
    private volatile String lastPortOutput = "";
    private volatile int lastPortExit;

    public String lastPortOutput() { return lastPortOutput; }
    public int lastPortExit() { return lastPortExit; }

    public UsbipCli() { exe = find(); }

    public String exe() { return exe; }
    public boolean available() { return exe != null; }
    public void rescan() { exe = find(); }

    static String find() {
        List<String> candidates = new ArrayList<>();
        for (String env : new String[]{"ProgramW6432", "ProgramFiles", "ProgramFiles(x86)"}) {
            String pf = System.getenv(env);
            if (pf != null) {
                candidates.add(pf + "\\USBip\\usbip.exe");
                candidates.add(pf + "\\usbip-win2\\usbip.exe");
            }
        }
        candidates.add("C:\\Program Files\\USBip\\usbip.exe");
        for (String c : candidates) if (new File(c).isFile()) return c;
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File f = new File(dir, "usbip.exe");
                if (f.isFile()) return f.getAbsolutePath();
            }
        }
        return null;
    }

    public Result run(String... args) {
        if (exe == null) return new Result(-1, "ไม่พบ usbip.exe — กรุณาติดตั้ง usbip-win2 ก่อน");
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        for (String a : args) cmd.add(a);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process p = pb.start();
            byte[] out = readAll(p.getInputStream());
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-2, "usbip.exe ไม่ตอบสนอง (หมดเวลา)");
            }
            return new Result(p.exitValue(), decode(out));
        } catch (Exception e) {
            return new Result(-3, "เรียก usbip.exe ไม่ได้: " + e.getMessage());
        }
    }

    /** Runs usbip.exe elevated through a UAC prompt. Output cannot be captured, only the exit code. */
    public Result runElevated(String... args) {
        if (exe == null) return new Result(-1, "ไม่พบ usbip.exe");
        StringBuilder argList = new StringBuilder();
        for (String a : args) {
            if (argList.length() > 0) argList.append(',');
            argList.append("'").append(a.replace("'", "''")).append("'");
        }
        String ps = "$p = Start-Process -FilePath '" + exe.replace("'", "''") + "' -ArgumentList " + argList
                + " -Verb RunAs -WindowStyle Hidden -Wait -PassThru; exit $p.ExitCode";
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps)
                    .redirectErrorStream(true).start();
            byte[] out = readAll(p.getInputStream());
            p.waitFor(120, TimeUnit.SECONDS);
            return new Result(p.exitValue(), decode(out));
        } catch (Exception e) {
            return new Result(-3, e.getMessage());
        }
    }

    public static boolean looksLikeAccessDenied(Result r) {
        String o = r.output.toLowerCase();
        return o.contains("access is denied") || o.contains("access denied") || o.contains("0x00000005")
                || o.contains("error 5") || o.contains("administrator") || o.contains("elevat");
    }

    public Result attach(String host, int port, String busid, boolean lowLatency) {
        List<String> a = new ArrayList<>();
        if (port != 3240) { a.add("--tcp-port"); a.add(String.valueOf(port)); }
        a.add("attach");
        a.add("-r");
        a.add(host);
        a.add("-b");
        a.add(busid);
        if (lowLatency) a.add("--receive-mode=low-latency");
        Result r = run(a.toArray(new String[0]));
        if (!r.ok() && lowLatency && (r.output.contains("receive-mode") || r.output.toLowerCase().contains("unknown") || r.output.toLowerCase().contains("not expected"))) {
            a.remove(a.size() - 1); // older usbip-win2 without that option
            r = run(a.toArray(new String[0]));
        }
        if (!r.ok() && looksLikeAccessDenied(r)) {
            Result e = runElevated(a.toArray(new String[0]));
            return new Result(e.exit, r.output + "\n→ ลองใหม่แบบ Administrator: " + (e.ok() ? "สำเร็จ" : "ไม่สำเร็จ (" + e.exit + ")"));
        }
        return r;
    }

    public Result detach(int port) {
        Result r = run("detach", "-p", String.valueOf(port));
        if (!r.ok() && looksLikeAccessDenied(r)) {
            Result e = runElevated("detach", "-p", String.valueOf(port));
            return new Result(e.exit, r.output);
        }
        return r;
    }

    public Result detachAll() {
        Result r = run("detach", "--all");
        if (r.ok()) return r;
        // fallback: detach one by one
        StringBuilder sb = new StringBuilder();
        int exit = 0;
        for (Port p : ports()) {
            Result d = detach(p.port);
            sb.append(d.output).append('\n');
            if (!d.ok()) exit = d.exit;
        }
        return new Result(exit, sb.toString());
    }

    private static final Pattern PORT_LINE = Pattern.compile("(?i)^\\s*port\\s+0*(\\d+)\\s*:?(.*)$");
    private static final Pattern REMOTE = Pattern.compile("(?i)usbip://\\S+");

    public List<Port> ports() {
        Result r = run("port");
        lastPortOutput = r.output;
        lastPortExit = r.exit;
        if (!r.ok()) return new ArrayList<>();
        return parsePorts(r.output);
    }

    /** Parses usbip-win2 "usbip port" output ("Port 01: device in use at ..." blocks). */
    static List<Port> parsePorts(String text) {
        List<Port> out = new ArrayList<>();
        Port cur = null;
        for (String line : text.split("\\r?\\n")) {
            Matcher m = PORT_LINE.matcher(line);
            if (m.find()) {
                cur = new Port();
                cur.port = Integer.parseInt(m.group(1));
                out.add(cur);
                continue;
            }
            if (cur == null) continue;
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("====")) continue;
            Matcher rm = REMOTE.matcher(t);
            if (rm.find()) cur.remote = rm.group();
            else if (!t.startsWith("->") && cur.description.isEmpty()) cur.description = t;
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static String decode(byte[] b) {
        String s = new String(b, StandardCharsets.UTF_8);
        if (s.indexOf('\uFFFD') >= 0) {
            try { s = new String(b, Charset.forName(System.getProperty("sun.jnu.encoding", "Cp874"))); } catch (Exception ignored) {}
        }
        return s.trim();
    }
}
