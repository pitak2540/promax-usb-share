package com.james.usbshare.keygen;

import com.james.usbshare.license.License;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.prefs.Preferences;

/** Owner-only tool: issues license keys with the private key file. */
public class KeygenApp {
    static final Color BG = new Color(0x0B0D10), CARD = new Color(0x161A21), BLUE = new Color(0x1FA2FF),
            RED = new Color(0xE53935), SILVER = new Color(0xC3C9D0), GREEN = new Color(0x2ECC71), LINE = new Color(0x2A313B);

    final Preferences prefs = Preferences.userNodeForPackage(KeygenApp.class);
    Signer signer;
    JFrame frame;
    final JLabel keyStatus = new JLabel();
    final JTextField customer = new JTextField();
    final JTextField device = new JTextField();       // PC
    final JTextField phoneDevice = new JTextField();  // phone
    String lastIds = "";
    final JComboBox<String> duration = new JComboBox<>(new String[]{"ถาวร (ไม่หมดอายุ)", "30 วัน", "90 วัน", "180 วัน", "1 ปี", "กำหนดวันหมดอายุเอง"});
    final JTextField customDate = new JTextField(LocalDate.now().plusYears(1).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
    final JTextArea output = new JTextArea(4, 60);
    final DefaultTableModel history = new DefaultTableModel(new String[]{"วันที่ออก", "ลูกค้า", "รหัสเครื่อง", "อายุ", "หมดอายุ"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    String lastKey = "";

    public static void main(String[] a) {
        SwingUtilities.invokeLater(() -> new KeygenApp().show());
    }

    /** Every Swing default font (dialogs, buttons, labels, text fields...) -> a font that has Thai glyphs. */
    static void applyThaiFont(Font base) {
        // UIManager.getDefaults().keys() misses look-and-feel keys, so walk the L&F table and a known list too
        java.util.Set<Object> list = new java.util.HashSet<>(UIManager.getLookAndFeelDefaults().keySet());
        for (String n : new String[]{"Button", "ToggleButton", "RadioButton", "CheckBox", "ComboBox", "Label", "List",
                "MenuBar", "MenuItem", "Menu", "PopupMenu", "OptionPane", "Panel", "ProgressBar", "ScrollPane", "Viewport",
                "TabbedPane", "Table", "TableHeader", "TextField", "PasswordField", "TextArea", "TextPane", "EditorPane",
                "TitledBorder", "ToolTip", "Tree", "Spinner", "FormattedTextField", "FileChooser"})
            list.add(n + ".font");
        list.add("OptionPane.messageFont");
        list.add("OptionPane.buttonFont");
        list.add("FileChooser.listFont");
        for (Object k : list) {
            Object v = UIManager.get(k);
            if (v instanceof Font || (k instanceof String && ((String) k).endsWith("ont"))) {
                Font old = v instanceof Font ? (Font) v : base;
                UIManager.put(k, new javax.swing.plaf.FontUIResource(base.getFamily(), old.getStyle(), Math.max(13, old.getSize())));
            }
        }
    }

    static Font font(int style, int size) {
        for (String n : new String[]{"Leelawadee UI", "Segoe UI", "Tahoma", "Loma"})
            if (java.util.Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()).contains(n))
                return new Font(n, style, size);
        return new Font(Font.DIALOG, style, size);
    }

    Path historyFile() {
        Path dir = Path.of(System.getProperty("user.home"), "Documents", com.james.usbshare.Brand.LICENSE_FOLDER);
        try { Files.createDirectories(dir); } catch (Exception ignored) {}
        return dir.resolve("licenses.csv");
    }

    JLabel label(String s) {
        JLabel l = new JLabel(s);
        l.setFont(font(Font.PLAIN, 14));
        l.setForeground(SILVER);
        return l;
    }

    JButton button(String s, Color c) {
        JButton b = new JButton(s);
        b.setFont(font(Font.BOLD, 14));
        b.setForeground(Color.WHITE);
        b.setBackground(c);
        b.setFocusPainted(false);
        b.setOpaque(true);
        b.setBorder(new EmptyBorder(8, 16, 8, 16));
        return b;
    }

    void show() {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) {}
        Font f = font(Font.PLAIN, 14);
        applyThaiFont(f);
        frame = new JFrame("ออกคีย์ " + com.james.usbshare.Brand.NAME);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(14, 14, 14, 14));
        frame.setContentPane(root);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(CARD);
        form.setBorder(BorderFactory.createCompoundBorder(new LineBorder(LINE), new EmptyBorder(12, 14, 12, 14)));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 5, 5, 5);
        g.fill = GridBagConstraints.HORIZONTAL;
        int y = 0;

        JLabel title = new JLabel("ออกคีย์ลงทะเบียน " + com.james.usbshare.Brand.NAME);
        title.setFont(font(Font.BOLD, 20));
        title.setForeground(Color.WHITE);
        g.gridx = 0; g.gridy = y++; g.gridwidth = 3; form.add(title, g);
        keyStatus.setFont(f);
        JButton pick = button("เลือกไฟล์กุญแจ...", LINE);
        pick.addActionListener(e -> pickKeyFile());
        g.gridy = y; g.gridwidth = 2; form.add(keyStatus, g);
        g.gridx = 2; g.gridwidth = 1; form.add(pick, g);
        y++;

        for (JComponent c : new JComponent[]{customer, device, customDate}) c.setFont(f);
        device.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
        phoneDevice.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
        duration.setFont(f);
        g.gridx = 0; g.gridy = y; form.add(label("ชื่อลูกค้า / หมายเหตุ"), g);
        g.gridx = 1; g.gridwidth = 2; g.weightx = 1; form.add(customer, g); g.gridwidth = 1; g.weightx = 0; y++;
        g.gridx = 0; g.gridy = y; form.add(label("รหัสเครื่องคอม"), g);
        g.gridx = 1; g.gridwidth = 2; form.add(device, g); g.gridwidth = 1; y++;
        g.gridx = 0; g.gridy = y; form.add(label("รหัสเครื่องมือถือ"), g);
        g.gridx = 1; g.gridwidth = 2; form.add(phoneDevice, g); g.gridwidth = 1; y++;
        JLabel hint = label("1 คีย์ใช้ได้ คอม 1 เครื่อง + มือถือ 1 เครื่อง (ใส่ช่องเดียวก็ได้ ถ้าลูกค้ายังไม่มีอีกเครื่อง)");
        hint.setForeground(new Color(0x7D8590));
        g.gridx = 1; g.gridy = y++; g.gridwidth = 2; form.add(hint, g); g.gridwidth = 1;
        g.gridx = 0; g.gridy = y; form.add(label("อายุคีย์"), g);
        g.gridx = 1; form.add(duration, g);
        g.gridx = 2; form.add(customDate, g); y++;
        customDate.setToolTipText("วันสุดท้ายที่ใช้ได้ (วัน/เดือน/ปี ค.ศ.)");
        customDate.setEnabled(false);
        duration.addActionListener(e -> customDate.setEnabled(duration.getSelectedIndex() == 5));

        JButton issue = button("ออกคีย์", BLUE);
        issue.addActionListener(e -> issue());
        g.gridx = 1; g.gridy = y++; form.add(issue, g);

        output.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        output.setLineWrap(true);
        output.setEditable(false);
        g.gridx = 0; g.gridy = y++; g.gridwidth = 3; form.add(new JScrollPane(output), g);
        JPanel copyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        copyRow.setOpaque(false);
        JButton copyKey = button("คัดลอกคีย์", LINE);
        copyKey.addActionListener(e -> copy(lastKey));
        JButton copyMsg = button("คัดลอกข้อความส่งลูกค้า", LINE);
        copyMsg.addActionListener(e -> copy(customerMessage()));
        copyRow.add(copyKey);
        copyRow.add(copyMsg);
        g.gridy = y++; form.add(copyRow, g);
        root.add(form, BorderLayout.NORTH);

        JTable t = new JTable(history);
        t.setFont(f);
        t.setRowHeight(26);
        t.getTableHeader().setFont(font(Font.BOLD, 14));
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(BorderFactory.createTitledBorder(new LineBorder(LINE), "ประวัติการออกคีย์ (" + historyFile() + ")", 0, 0, f, SILVER));
        sp.setBackground(BG);
        root.add(sp, BorderLayout.CENTER);

        loadHistory();
        String saved = prefs.get("keyFile", "");
        if (!saved.isEmpty()) loadKey(new File(saved)); else setKeyStatus(false, "ยังไม่ได้เลือกไฟล์กุญแจ (JAMES-license-private.key)");

        frame.setSize(980, 720);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    void setKeyStatus(boolean ok, String text) {
        keyStatus.setText(text);
        keyStatus.setForeground(ok ? GREEN : RED);
    }

    void pickKeyFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("เลือกไฟล์ JAMES-license-private.key");
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) loadKey(fc.getSelectedFile());
    }

    void loadKey(File f) {
        try {
            signer = new Signer(f.toPath());
            prefs.put("keyFile", f.getAbsolutePath());
            setKeyStatus(true, "พร้อมออกคีย์ — " + f.getName());
        } catch (Exception e) {
            signer = null;
            setKeyStatus(false, "เปิดไฟล์กุญแจไม่ได้: " + e.getMessage());
        }
    }

    void issue() {
        if (signer == null) { JOptionPane.showMessageDialog(frame, "เลือกไฟล์กุญแจก่อน"); return; }
        byte[] dev = null, phone = null;
        String pcText = License.clean(device.getText()), phText = License.clean(phoneDevice.getText());
        if (!pcText.isEmpty()) {
            dev = License.parseDeviceId(pcText);
            if (dev == null || pcText.length() != 13) { JOptionPane.showMessageDialog(frame, "รหัสเครื่องคอมไม่ถูกต้อง — ต้องเป็นแบบ XXXXX-XXXXX-XXX"); return; }
        }
        if (!phText.isEmpty()) {
            phone = License.parseDeviceId(phText);
            if (phone == null || phText.length() != 13) { JOptionPane.showMessageDialog(frame, "รหัสเครื่องมือถือไม่ถูกต้อง — ต้องเป็นแบบ XXXXX-XXXXX-XXX"); return; }
        }
        if (dev == null && phone == null) { JOptionPane.showMessageDialog(frame, "ใส่รหัสเครื่องอย่างน้อย 1 ช่อง"); return; }
        if (dev == null) { dev = phone; phone = null; }
        int idx = duration.getSelectedIndex();
        int expiryDay = 0;
        String durText = (String) duration.getSelectedItem();
        LocalDate today = LocalDate.now();
        LocalDate last = null;
        switch (idx) {
            case 1: last = today.plusDays(30); break;
            case 2: last = today.plusDays(90); break;
            case 3: last = today.plusDays(180); break;
            case 4: last = today.plusYears(1); break;
            case 5:
                try { last = LocalDate.parse(customDate.getText().trim(), DateTimeFormatter.ofPattern("d/M/yyyy")); }
                catch (Exception e) { JOptionPane.showMessageDialog(frame, "วันที่ไม่ถูกต้อง ใช้แบบ 31/12/2027"); return; }
                if (last.isBefore(today)) { JOptionPane.showMessageDialog(frame, "วันหมดอายุต้องไม่ใช่วันที่ผ่านมาแล้ว"); return; }
                durText = "ถึง " + customDate.getText().trim();
                break;
            default: break;
        }
        if (last != null) expiryDay = (int) (last.toEpochDay() - LocalDate.of(2026, 1, 1).toEpochDay());
        try {
            lastKey = signer.issue(dev, phone, expiryDay);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "ออกคีย์ไม่สำเร็จ: " + e.getMessage());
            return;
        }
        // self-check with the same public key the apps use
        License.Key k = License.parseKey(lastKey);
        if (k == null) {
            JOptionPane.showMessageDialog(frame, "ไฟล์กุญแจไม่ตรงกับแอพ — คีย์นี้ใช้ไม่ได้ (เลือกไฟล์กุญแจที่ถูกต้อง)");
            lastKey = "";
            return;
        }
        output.setText(lastKey);
        String expiry = expiryDay == 0 ? "ไม่หมดอายุ" : License.dateOf(expiryDay);
        String now = new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date());
        String devId = License.formatDeviceId(dev) + (phone != null ? " + " + License.formatDeviceId(phone) : "");
        lastIds = devId;
        history.insertRow(0, new Object[]{now, customer.getText().trim(), devId, durText, expiry});
        String line = String.join(",", csv(now), csv(customer.getText().trim()), csv(devId), csv(durText), csv(expiry), csv(lastKey)) + "\r\n";
        try {
            Path h = historyFile();
            if (!Files.exists(h)) Files.write(h, ("﻿วันที่ออก,ลูกค้า,รหัสเครื่อง,อายุ,หมดอายุ,คีย์\r\n").getBytes(StandardCharsets.UTF_8));
            Files.write(h, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "บันทึกประวัติไม่ได้: " + e.getMessage());
        }
        copy(lastKey);
    }

    String customerMessage() {
        if (lastKey.isEmpty()) return "";
        return "คีย์ลงทะเบียน " + com.james.usbshare.Brand.NAME + "\n" + lastKey
                + "\n\nวิธีใช้: เปิดโปรแกรมบนคอม และ/หรือแอพบนมือถือ → ลงทะเบียน → ใส่คีย์เดียวกันนี้ → ยืนยัน"
                + "\n(ใช้ได้กับเครื่องรหัส " + lastIds + " เท่านั้น)";
    }

    static String csv(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }

    void copy(String s) {
        if (s == null || s.isEmpty()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    void loadHistory() {
        try {
            Path h = historyFile();
            if (!Files.exists(h)) return;
            List<String> lines = Files.readAllLines(h, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 1; i--) {
                String[] p = lines.get(i).split("\",\"");
                if (p.length < 5) continue;
                for (int k = 0; k < p.length; k++) p[k] = p[k].replace("\"", "");
                history.addRow(new Object[]{p[0], p[1], p[2], p[3], p[4]});
            }
        } catch (Exception ignored) {}
    }
}
