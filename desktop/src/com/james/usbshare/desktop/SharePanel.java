package com.james.usbshare.desktop;

import com.james.usbshare.relay.Relay;
import com.james.usbshare.usbip.Beacon;
import com.james.usbshare.usbip.Proto;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Share this PC's USB" tab: this PC becomes a USB/IP server through usbipd-win
 * (Microsoft-signed, MIT). Other PCs attach its devices over the LAN (beacon) or from
 * anywhere with the pairing code (relay), exactly like they do with the phone app.
 */
public class SharePanel extends JPanel {

    public static final String USBIPD_URL = "https://github.com/dorssel/usbipd-win/releases";

    static final class LocalDev {
        String busid, vidpid, name, state;
        boolean shared() { return !state.toLowerCase().startsWith("not"); }
    }

    private final App app;
    private final DefaultTableModel model = new DefaultTableModel(new String[]{"อุปกรณ์", "VID:PID", "busid", "สถานะ"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable table = new JTable(model);
    private final List<LocalDev> devs = new ArrayList<>();
    private final JLabel status = new JLabel();
    private final JLabel codeLabel = new JLabel();
    private final JCheckBox allowRemote = new JCheckBox("ให้คอมที่อยู่คนละที่เชื่อมต่อได้ด้วยรหัสจับคู่", true);
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "share"); t.setDaemon(true); return t; });
    private volatile Relay.Phone relay;
    private volatile boolean beaconOn;

    public SharePanel(App app) {
        super(new BorderLayout(12, 12));
        this.app = app;
        setOpaque(false);

        JPanel top = app.card("แชร์ USB ของคอมเครื่องนี้ให้คอมเครื่องอื่น");
        JLabel how = new JLabel("<html>เลือกอุปกรณ์แล้วกด <b>แชร์</b> — คอมอีกเครื่องเปิดโปรแกรมนี้ แล้วเลือกเครื่องนี้ (Wi-Fi/LAN เดียวกันจะเจอเอง)"
                + " หรือกด 'เพิ่มด้วยรหัสจับคู่' แล้วพิมพ์รหัสด้านขวา<br>ระหว่างแชร์ อุปกรณ์จะใช้บนเครื่องนี้ไม่ได้ และต้องเปิดโปรแกรมนี้ค้างไว้ถ้าใช้รหัสจับคู่</html>");
        how.setFont(App.FONT);
        how.setForeground(App.SILVER);
        top.add(how, BorderLayout.CENTER);
        JPanel codeBox = new JPanel(new GridLayout(3, 1));
        codeBox.setOpaque(false);
        JLabel ct = new JLabel("รหัสจับคู่ของเครื่องนี้");
        ct.setFont(App.FONT);
        ct.setForeground(App.SILVER);
        codeLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 26));
        codeLabel.setForeground(App.BLUE);
        allowRemote.setFont(App.FONT);
        allowRemote.setForeground(App.SILVER);
        allowRemote.setOpaque(false);
        allowRemote.addActionListener(e -> applyRelay());
        codeBox.add(ct);
        codeBox.add(codeLabel);
        codeBox.add(allowRemote);
        top.add(codeBox, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        JPanel list = app.card("อุปกรณ์ USB ที่เสียบอยู่ที่คอมเครื่องนี้");
        app.styleTable(table);
        table.getColumnModel().getColumn(0).setPreferredWidth(320);
        list.add(app.scroll(table), BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btns.setOpaque(false);
        JButton share = app.button("แชร์", App.BLUE);
        share.addActionListener(e -> bindSelected(true));
        JButton unshare = app.button("เลิกแชร์", App.RED);
        unshare.addActionListener(e -> bindSelected(false));
        JButton refresh = app.button("รีเฟรช", App.LINE);
        refresh.addActionListener(e -> refresh());
        status.setFont(App.FONT);
        btns.add(share);
        btns.add(unshare);
        btns.add(refresh);
        btns.add(status);
        list.add(btns, BorderLayout.SOUTH);
        add(list, BorderLayout.CENTER);

        codeLabel.setText(code());
        refresh();
        new Timer(5000, e -> refresh()).start();
    }

    // ---------- usbipd-win ----------

    static String usbipdExe() {
        for (String env : new String[]{"ProgramW6432", "ProgramFiles"}) {
            String pf = System.getenv(env);
            if (pf != null) {
                File f = new File(pf + "\\usbipd-win\\usbipd.exe");
                if (f.isFile()) return f.getAbsolutePath();
            }
        }
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(File.pathSeparator)) {
            File f = new File(d, "usbipd.exe");
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }

    static String run(String exe, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        Collections.addAll(cmd, args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        p.waitFor(30, TimeUnit.SECONDS);
        return bo.toString(StandardCharsets.UTF_8.name());
    }

    private static final Pattern ROW = Pattern.compile("^(\\d+-[\\d.]+)\\s+([0-9a-fA-F]{4}:[0-9a-fA-F]{4})\\s+(.*?)\\s{2,}(Not shared|Shared.*|Attached.*)\\s*$");

    static List<LocalDev> parseList(String text) {
        List<LocalDev> out = new ArrayList<>();
        boolean connected = false;
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("Connected")) { connected = true; continue; }
            if (t.startsWith("Persisted")) { connected = false; continue; }
            if (!connected) continue;
            Matcher m = ROW.matcher(t);
            if (m.find()) {
                LocalDev d = new LocalDev();
                d.busid = m.group(1);
                d.vidpid = m.group(2).toLowerCase();
                d.name = m.group(3).trim();
                d.state = m.group(4).trim();
                out.add(d);
            }
        }
        return out;
    }

    void refresh() {
        work.execute(() -> {
            String exe = usbipdExe();
            List<LocalDev> list = new ArrayList<>();
            String err = null;
            if (exe == null) err = "ยังไม่ได้ติดตั้ง usbipd-win (ติดตั้งพร้อมโปรแกรมนี้ หรือดาวน์โหลดจาก " + USBIPD_URL + ")";
            else {
                try { list = parseList(run(exe, "list")); } catch (Exception e) { err = e.getMessage(); }
            }
            final List<LocalDev> fl = list;
            final String fe = err;
            SwingUtilities.invokeLater(() -> fill(fl, fe));
        });
    }

    private void fill(List<LocalDev> list, String err) {
        int sel = table.getSelectedRow();
        devs.clear();
        devs.addAll(list);
        model.setRowCount(0);
        int shared = 0;
        for (LocalDev d : list) {
            String[] vp = d.vidpid.split(":");
            String nice = d.name;
            try {
                String k = Proto.knownName(Integer.parseInt(vp[0], 16), Integer.parseInt(vp[1], 16));
                if (!k.startsWith("USB ")) nice = k + " — " + d.name;
            } catch (Exception ignored) {}
            String st = d.state.startsWith("Not") ? "ไม่ได้แชร์" : d.state.startsWith("Attached") ? "ใช้งานอยู่ (มีคอมเชื่อมแล้ว)" : "ว่าง — แชร์อยู่";
            if (d.shared()) shared++;
            model.addRow(new Object[]{nice, d.vidpid.toUpperCase(), d.busid, st});
        }
        if (sel >= 0 && sel < model.getRowCount()) table.setRowSelectionInterval(sel, sel);
        if (err != null) {
            status.setText(err);
            status.setForeground(App.RED);
        } else {
            status.setText(shared == 0 ? "ยังไม่ได้แชร์อุปกรณ์" : "กำลังแชร์ " + shared + " ชิ้น");
            status.setForeground(shared == 0 ? App.DIM : App.GREEN);
        }
        boolean hosting = shared > 0 && err == null;
        if (hosting && !beaconOn) startBeacon();
        if (!hosting) beaconOn = false;
        if (hosting && relay == null) applyRelay();
        if (!hosting && relay != null) { relay.stop(); relay = null; }
    }

    private void bindSelected(boolean share) {
        if (share && !app.license.require()) return;
        int row = table.getSelectedRow();
        if (row < 0 || row >= devs.size()) {
            JOptionPane.showMessageDialog(app.frame, "เลือกอุปกรณ์ก่อน");
            return;
        }
        String exe = usbipdExe();
        if (exe == null) {
            app.openUrl(USBIPD_URL);
            return;
        }
        LocalDev d = devs.get(row);
        String[] vp = d.vidpid.split(":");
        if (share && Proto.isEcuFlashInterface(Integer.parseInt(vp[0], 16), Integer.parseInt(vp[1], 16))) {
            int a = JOptionPane.showConfirmDialog(app.frame,
                    "สายนี้ใช้ผ่านเครือข่ายได้เฉพาะอ่านโค้ด / ลบโค้ด / ดูค่าสด\nห้ามแฟลช ECU ผ่านเครือข่าย — สัญญาณสะดุดกลางคันอาจทำให้กล่องเสีย\n\nแชร์ต่อไหม?",
                    "คำเตือน", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (a != JOptionPane.YES_OPTION) return;
        }
        work.execute(() -> {
            // bind/unbind need administrator rights: run through a UAC prompt
            String args = share ? "bind --busid " + d.busid : "unbind --busid " + d.busid;
            String ps = "$p = Start-Process -FilePath '" + exe.replace("'", "''") + "' -ArgumentList '" + args
                    + "' -Verb RunAs -WindowStyle Hidden -Wait -PassThru; exit $p.ExitCode";
            int rc;
            try {
                Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps)
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                p.waitFor(120, TimeUnit.SECONDS);
                rc = p.exitValue();
            } catch (Exception e) {
                rc = -1;
            }
            app.log((share ? "แชร์ " : "เลิกแชร์ ") + d.name + " (" + d.busid + "): " + (rc == 0 ? "สำเร็จ" : "ไม่สำเร็จ (" + rc + ")"));
            refresh();
        });
    }

    // ---------- pairing code + LAN beacon ----------

    String code() {
        String c = app.prefs.get("hostCode", null);
        if (c == null) {
            c = Relay.newCode();
            app.prefs.put("hostCode", c);
        }
        return c;
    }

    void applyRelay() {
        if (relay != null) { relay.stop(); relay = null; }
        if (!allowRemote.isSelected()) return;
        boolean anyShared = false;
        for (LocalDev d : devs) if (d.shared()) anyShared = true;
        if (!anyShared) return;
        relay = new Relay.Phone(app.relayUrl(), code(), pcName(), Proto.PORT, app::log)
                .credentials(app.prefs.get("lic.key", null), app.license.device());
        relay.start();
    }

    static String pcName() {
        String n = System.getenv("COMPUTERNAME");
        return "คอม " + (n == null ? "Windows" : n);
    }

    void startBeacon() {
        beaconOn = true;
        Thread t = new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                while (beaconOn) {
                    Beacon b = new Beacon();
                    b.name = pcName();
                    b.port = Proto.PORT;
                    for (LocalDev d : new ArrayList<>(devs)) {
                        if (!d.shared()) continue;
                        Beacon.Dev bd = new Beacon.Dev();
                        String[] vp = d.vidpid.split(":");
                        bd.busid = d.busid;
                        bd.vid = Integer.parseInt(vp[0], 16);
                        bd.pid = Integer.parseInt(vp[1], 16);
                        bd.inUse = d.state.startsWith("Attached");
                        bd.product = d.name;
                        b.devices.add(bd);
                    }
                    byte[] data = b.encode();
                    for (InetAddress a : broadcastAddresses()) {
                        try { ds.send(new DatagramPacket(data, data.length, a, Beacon.PORT)); } catch (Exception ignored) {}
                    }
                    Thread.sleep(2000);
                }
            } catch (Exception ignored) {
            } finally {
                beaconOn = false;
            }
        }, "host-beacon");
        t.setDaemon(true);
        t.start();
    }

    static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses())
                    if (ia.getBroadcast() != null && ia.getAddress() instanceof Inet4Address) out.add(ia.getBroadcast());
            }
            out.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {}
        return out;
    }

    /** True if the address belongs to this PC (so we don't list ourselves as a source). */
    static boolean isLocalAddress(String ip) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InterfaceAddress ia : ni.getInterfaceAddresses())
                    if (ia.getAddress().getHostAddress().equals(ip)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    void shutdown() {
        beaconOn = false;
        if (relay != null) relay.stop();
    }
}
