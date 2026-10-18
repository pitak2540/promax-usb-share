package com.james.usbshare.desktop;

import com.james.usbshare.license.License;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

/** License check for the Windows program (key bound to this PC's MachineGuid). */
public final class LicenseGuard {
    private final App app;
    private final Preferences prefs;
    private byte[] device;

    public LicenseGuard(App app) {
        this.app = app;
        this.prefs = app.prefs;
    }

    static String machineGuid() {
        try {
            Process p = new ProcessBuilder("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")
                    .redirectErrorStream(true).start();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            p.waitFor(10, TimeUnit.SECONDS);
            for (String line : bo.toString(StandardCharsets.UTF_8.name()).split("\\r?\\n")) {
                String t = line.trim();
                if (t.startsWith("MachineGuid")) {
                    String[] f = t.split("\\s+");
                    return f[f.length - 1];
                }
            }
        } catch (Exception ignored) {}
        String host = System.getenv("COMPUTERNAME");
        return "host:" + (host == null ? "unknown" : host);
    }

    synchronized byte[] device() {
        if (device == null) device = License.deviceHash("windows:" + machineGuid());
        return device;
    }

    public String deviceId() { return License.formatDeviceId(device()); }

    public License.Status status() {
        long now = System.currentTimeMillis();
        long first = prefs.getLong("lic.firstRun", 0);
        long maxSeen = prefs.getLong("lic.maxSeen", 0);
        if (first == 0) { first = now; prefs.putLong("lic.firstRun", now); }
        if (now > maxSeen) prefs.putLong("lic.maxSeen", now);
        return License.evaluate(prefs.get("lic.key", null), device(), first, maxSeen, now);
    }

    /** True if the feature may be used; otherwise explains and opens the registration dialog. */
    public boolean require() {
        License.Status st = status();
        if (st.allowed()) return true;
        app.log(License.describe(st));
        showDialog();
        return status().allowed();
    }

    /** Copies the device id (with a short message) and opens the developer's LINE. */
    public void contactDeveloper() {
        String msg = "ขอซื้อคีย์ PROMAX USB Share (คอม)\nรหัสเครื่อง: " + deviceId();
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(msg), null);
        app.log("คัดลอกรหัสเครื่องแล้ว (" + deviceId() + ") — วางในแชท LINE ได้เลย");
        app.openUrl(License.CONTACT_URL);
    }

    public void showDialog() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(new EmptyBorder(6, 6, 6, 6));
        License.Status st = status();
        JLabel s = new JLabel(License.describe(st));
        s.setFont(App.FONT_B);
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(s);
        top.add(new JLabel("1 คีย์ใช้ได้กับคอม 1 เครื่อง + มือถือ 1 เครื่อง — ส่งรหัสเครื่องทั้งสองให้ผู้ขาย"));
        top.add(new JLabel("รหัสเครื่องคอมนี้:"));
        JPanel idRow = new JPanel(new BorderLayout(6, 0));
        JTextField id = new JTextField(deviceId());
        id.setEditable(false);
        id.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
        JButton copy = new JButton("คัดลอก");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(deviceId()), null));
        idRow.add(id, BorderLayout.CENTER);
        idRow.add(copy, BorderLayout.EAST);
        top.add(idRow);
        JPanel buyRow = new JPanel(new BorderLayout(10, 0));
        JButton buy = new JButton("ซื้อคีย์ / ติดต่อผู้พัฒนา (LINE)");
        buy.setBackground(new Color(0x06C755));
        buy.setForeground(Color.WHITE);
        buy.addActionListener(e -> contactDeveloper());
        JPanel buyWrap = new JPanel(new GridBagLayout()); // keeps the button at its natural height, centred next to the QR
        buyWrap.add(buy);
        buyRow.add(buyWrap, BorderLayout.CENTER);
        try (java.io.InputStream qi = App.class.getResourceAsStream("/line-qr.png")) {
            if (qi != null) {
                JLabel qr = new JLabel(new ImageIcon(javax.imageio.ImageIO.read(qi).getScaledInstance(110, 110, Image.SCALE_SMOOTH)));
                qr.setToolTipText("สแกนด้วยมือถือเพื่อแอดไลน์ผู้พัฒนา");
                buyRow.add(qr, BorderLayout.EAST);
            }
        } catch (Exception ignored) {}
        top.add(buyRow);
        top.add(new JLabel("คีย์ลงทะเบียน:"));
        for (Component c : top.getComponents()) {
            if (c instanceof JComponent) ((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        p.add(top, BorderLayout.NORTH);
        JTextArea key = new JTextArea(4, 48);
        key.setLineWrap(true);
        key.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        String saved = prefs.get("lic.key", "");
        key.setText(saved);
        p.add(new JScrollPane(key), BorderLayout.CENTER);
        int r = JOptionPane.showConfirmDialog(app.frame, p, "ลงทะเบียน PROMAX USB Share", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        String k = key.getText().trim();
        if (k.isEmpty() || k.equals(saved)) return;
        License.Key parsed = License.parseKey(k);
        if (parsed == null) {
            JOptionPane.showMessageDialog(app.frame, "คีย์ไม่ถูกต้อง — ตรวจว่าคัดลอกมาครบทั้งหมด");
            return;
        }
        if (!parsed.matches(device())) {
            JOptionPane.showMessageDialog(app.frame, "คีย์นี้ออกให้เครื่องอื่น — ส่งรหัสเครื่องนี้ (" + deviceId() + ") ให้ผู้ขาย");
            return;
        }
        prefs.put("lic.key", k);
        for (com.james.usbshare.relay.Relay.Forwarder f : app.forwarders.values()) f.credentials(k, device());
        String msg = License.describe(status());
        app.log(msg);
        app.updateLicenseLabel();
        JOptionPane.showMessageDialog(app.frame, msg);
    }
}
