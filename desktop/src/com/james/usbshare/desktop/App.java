package com.james.usbshare.desktop;

import com.james.usbshare.relay.Relay;
import com.james.usbshare.usbip.Beacon;
import com.james.usbshare.usbip.Proto;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.DataInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;

/** Windows client GUI: finds phones, lists their USB devices and attaches them through usbip-win2. */
public class App {

    static final Color BG = new Color(0x0B0D10), CARD = new Color(0x161A21), BLUE = new Color(0x1FA2FF),
            RED = new Color(0xE53935), SILVER = new Color(0xC3C9D0), DIM = new Color(0x7D8590),
            GREEN = new Color(0x2ECC71), LINE = new Color(0x2A313B);

    static Font FONT, FONT_B, FONT_TITLE;

    /** A phone found by beacon or typed in by hand. */
    static final class Phone {
        String host;
        int port = Proto.PORT;
        String name = "";
        long lastSeen;
        boolean manual;
        boolean viaTailscale;
        boolean viaRelay;
        boolean relayOnline = true;
        String relayCode;
        Beacon beacon;
        @Override public String toString() {
            if (viaRelay)
                return (name.isEmpty() ? "มือถือ" : name) + "  [รหัส " + relayCode + "]" + (relayOnline ? "" : "  (ออฟไลน์)");
            boolean alive = manual || viaTailscale || System.currentTimeMillis() - lastSeen < 15000;
            String tag = viaTailscale ? "  [Tailscale]" : "";
            return (name.isEmpty() ? host : name + "  (" + host + ")") + tag + (alive ? "" : "  (ไม่เห็นสัญญาณ)");
        }
    }

    final Map<String, Phone> phones = new LinkedHashMap<>();
    final DefaultListModel<Phone> phoneModel = new DefaultListModel<>();
    final JList<Phone> phoneList = new JList<>(phoneModel);
    final DefaultTableModel devModel = new DefaultTableModel(new String[]{"อุปกรณ์", "VID:PID", "busid", "ความเร็ว", "สถานะ"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    final JTable devTable = new JTable(devModel);
    final DefaultTableModel portModel = new DefaultTableModel(new String[]{"พอร์ต", "อุปกรณ์", "มาจาก"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    final JTable portTable = new JTable(portModel);
    final JTextArea logArea = new JTextArea();
    final JLabel usbipStatus = new JLabel();
    final JCheckBox lowLatency = new JCheckBox("โหมดหน่วงต่ำ (แนะนำสำหรับสาย OBD / Serial)", true);
    final List<Proto.DeviceInfo> shownDevices = new ArrayList<>();
    final UsbipCli cli = new UsbipCli();
    final ExecutorService scan = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "scan"); t.setDaemon(true); return t; });
    final ExecutorService work = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "work"); t.setDaemon(true); return t; });
    JFrame frame;
    SharePanel sharePanel;
    LicenseGuard license;
    final JLabel licLabel = new JLabel();

    void updateLicenseLabel() {
        com.james.usbshare.license.License.Status st = license.status();
        licLabel.setText(com.james.usbshare.license.License.describe(st));
        licLabel.setForeground(st.kind == com.james.usbshare.license.License.Kind.LICENSED ? GREEN : st.allowed() ? new Color(0xFFB300) : RED);
    }
    final Preferences prefs = Preferences.userRoot().node("com/promax/usbshare/desktop");
    final Map<String, Relay.Forwarder> forwarders = new LinkedHashMap<>();
    String lastPortRaw = "";

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--clear-history")) {
            // run by the uninstaller: forget phones/PCs and pairing codes, keep license + trial date
            Preferences p = Preferences.userRoot().node("com/promax/usbshare/desktop");
            for (String k : new String[]{"relayCodes", "relayUrl", "hostCode"}) p.remove(k);
            try { p.flush(); } catch (Exception ignored) {}
            System.exit(0);
        }
        if (args.length > 0 && args[0].equals("--selftest-relay")) {
            // used by CI: proves the bundled runtime can open TLS to the relay
            Relay.Status st = Relay.status(Relay.DEFAULT_URL, "SELFTEST1");
            System.out.println("relay reachable=" + st.reachable + " error=" + st.error);
            System.exit(st.reachable ? 0 : 1);
        }
        System.setProperty("awt.useSystemAAFontSettings", "on");
        SwingUtilities.invokeLater(() -> new App().show());
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

    static Font pickFont(int style, int size) {
        String[] names = {"Leelawadee UI", "Segoe UI", "Tahoma", "Loma", "Dialog"};
        java.util.Set<String> have = new java.util.HashSet<>(java.util.Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String n : names) if (have.contains(n)) return new Font(n, style, size);
        return new Font(Font.DIALOG, style, size);
    }

    void log(String s) {
        String t = new SimpleDateFormat("HH:mm:ss").format(new Date());
        SwingUtilities.invokeLater(() -> {
            logArea.append(t + "  " + s + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    JPanel card(String title) {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBackground(CARD);
        p.setBorder(BorderFactory.createCompoundBorder(new LineBorder(LINE, 1, true), new EmptyBorder(12, 14, 12, 14)));
        if (title != null) {
            JLabel l = new JLabel(title);
            l.setFont(FONT_B);
            l.setForeground(Color.WHITE);
            p.add(l, BorderLayout.NORTH);
        }
        return p;
    }

    JButton button(String text, Color bg) {
        JButton b = new JButton(text);
        b.setFont(FONT_B);
        b.setForeground(Color.WHITE);
        b.setBackground(bg);
        b.setFocusPainted(false);
        b.setBorder(new EmptyBorder(8, 16, 8, 16));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setOpaque(true);
        return b;
    }

    void styleTable(JTable t) {
        t.setFont(FONT);
        t.setRowHeight(30);
        t.setBackground(CARD);
        t.setForeground(Color.WHITE);
        t.setGridColor(LINE);
        t.setSelectionBackground(new Color(0x1F3B57));
        t.setSelectionForeground(Color.WHITE);
        t.setShowVerticalLines(false);
        t.setFillsViewportHeight(true);
        t.getTableHeader().setFont(FONT_B);
        t.getTableHeader().setBackground(BG);
        t.getTableHeader().setForeground(SILVER);
        t.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean f, int row, int col) {
                JLabel c = (JLabel) super.getTableCellRendererComponent(tb, v, false, false, row, col);
                c.setFont(FONT_B);
                c.setBackground(BG);
                c.setForeground(SILVER);
                c.setBorder(new EmptyBorder(6, 8, 6, 8));
                return c;
            }
        });
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        DefaultTableCellRenderer r = new DefaultTableCellRenderer();
        r.setBorder(new EmptyBorder(0, 8, 0, 8));
        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean f, int row, int col) {
                JLabel c = (JLabel) super.getTableCellRendererComponent(tb, v, sel, false, row, col);
                c.setBorder(new EmptyBorder(0, 8, 0, 8));
                if (!sel) c.setBackground(CARD);
                String s = String.valueOf(v);
                if (s.startsWith("PC เครื่องอื่น") || s.startsWith("ใช้งานอยู่")) c.setForeground(BLUE);
                else if (s.startsWith("ว่าง")) c.setForeground(GREEN);
                else c.setForeground(Color.WHITE);
                return c;
            }
        });
    }

    JScrollPane scroll(Component c) {
        JScrollPane s = new JScrollPane(c);
        s.setBorder(new LineBorder(LINE));
        s.getViewport().setBackground(CARD);
        return s;
    }

    void show() {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) {}
        FONT = pickFont(Font.PLAIN, 14);
        FONT_B = pickFont(Font.BOLD, 14);
        FONT_TITLE = pickFont(Font.BOLD, 22);
        applyThaiFont(FONT);
        UIManager.put("ScrollBar.background", BG);
        UIManager.put("TabbedPane.contentAreaColor", BG);
        UIManager.put("TabbedPane.selected", new Color(0x1F3B57));
        UIManager.put("TabbedPane.background", CARD);
        UIManager.put("TabbedPane.unselectedBackground", CARD);
        UIManager.put("TabbedPane.foreground", Color.WHITE);
        UIManager.put("TabbedPane.borderHightlightColor", LINE);
        UIManager.put("TabbedPane.darkShadow", LINE);
        UIManager.put("TabbedPane.light", LINE);
        UIManager.put("TabbedPane.highlight", LINE);
        UIManager.put("TabbedPane.shadow", LINE);
        UIManager.put("TabbedPane.focus", BG);
        UIManager.put("TabbedPane.tabAreaBackground", BG);
        UIManager.put("TabbedPane.contentBorderInsets", new Insets(10, 0, 0, 0));
        UIManager.put("ToolTip.font", FONT);
        UIManager.put("OptionPane.messageFont", FONT);
        UIManager.put("OptionPane.buttonFont", FONT);

        frame = new JFrame("PROMAX USB Share — Windows");
        java.awt.image.BufferedImage logoImg = null;
        try (java.io.InputStream li = App.class.getResourceAsStream("/logo.png")) {
            if (li != null) logoImg = javax.imageio.ImageIO.read(li);
        } catch (Exception ignored) {}
        if (logoImg != null) {
            List<Image> icons = new ArrayList<>();
            for (int sz : new int[]{16, 24, 32, 48, 64, 128, 256}) icons.add(logoImg.getScaledInstance(sz, sz, Image.SCALE_SMOOTH));
            frame.setIconImages(icons);
        }
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(14, 14, 14, 14));
        frame.setContentPane(root);

        // ---- header
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel title = new JLabel("PROMAX USB Share");
        title.setFont(FONT_TITLE);
        title.setForeground(Color.WHITE);
        JLabel sub = new JLabel("แชร์ USB จากมือถือหรือคอมอีกเครื่อง มาใช้เหมือนเสียบตรงที่คอมนี้  •  Tune by MAXS");
        sub.setFont(FONT);
        sub.setForeground(SILVER);
        JPanel titles = new JPanel(new GridLayout(2, 1));
        titles.setOpaque(false);
        titles.add(title);
        titles.add(sub);
        JPanel brand = new JPanel(new BorderLayout(12, 0));
        brand.setOpaque(false);
        java.awt.image.BufferedImage wordmark = null;
        try (java.io.InputStream wi = App.class.getResourceAsStream("/wordmark.png")) {
            if (wi != null) wordmark = javax.imageio.ImageIO.read(wi);
        } catch (Exception ignored) {}
        if (wordmark != null) {
            int ww = 230, wh = wordmark.getHeight() * ww / wordmark.getWidth();
            JLabel wl = new JLabel(new ImageIcon(wordmark.getScaledInstance(ww, wh, Image.SCALE_SMOOTH)));
            wl.setBorder(new EmptyBorder(0, 0, 0, 6));
            brand.add(wl, BorderLayout.WEST);
        } else if (logoImg != null) brand.add(new JLabel(new ImageIcon(logoImg.getScaledInstance(84, 84, Image.SCALE_SMOOTH))), BorderLayout.WEST);
        brand.add(titles, BorderLayout.CENTER);
        header.add(brand, BorderLayout.WEST);
        JPanel usbipBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        usbipBox.setOpaque(false);
        usbipStatus.setFont(FONT);
        JButton install = button("ดาวน์โหลด usbip-win2", LINE);
        install.addActionListener(e -> openUrl(UsbipCli.DOWNLOAD_URL));
        usbipBox.add(usbipStatus);
        usbipBox.add(install);
        // usbip-win2 status is not shown in the header any more (the installer sets it up); a missing install is reported in the log
        license = new LicenseGuard(this);
        JPanel licBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        licBox.setOpaque(false);
        licLabel.setFont(FONT);
        JButton reg = button("ลงทะเบียน", LINE);
        reg.addActionListener(e -> license.showDialog());
        licBox.add(licLabel);
        licBox.add(reg);
        JButton buy = button("ซื้อคีย์ / ติดต่อผู้พัฒนา", new Color(0x06C755));
        buy.addActionListener(e -> license.contactDeveloper());
        licBox.add(buy);
        header.add(licBox, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        // ---- phones (left)
        JPanel phonesCard = card("เครื่องที่แชร์ USB (มือถือ / คอม)");
        phoneList.setFont(FONT);
        phoneList.setBackground(CARD);
        phoneList.setForeground(Color.WHITE);
        phoneList.setSelectionBackground(new Color(0x1F3B57));
        phoneList.setSelectionForeground(Color.WHITE);
        phoneList.setFixedCellHeight(34);
        phoneList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) refreshDevices(); });
        phonesCard.add(scroll(phoneList), BorderLayout.CENTER);
        JPanel manual = new JPanel(new BorderLayout(6, 6));
        manual.setOpaque(false);
        JTextField ipField = new JTextField();
        ipField.setFont(FONT);
        ipField.setToolTipText("พิมพ์ IP มือถือ เช่น 192.168.1.23");
        JButton add = button("เพิ่ม IP", LINE);
        add.addActionListener(e -> addManual(ipField.getText().trim()));
        ipField.addActionListener(e -> addManual(ipField.getText().trim()));
        JLabel hint = new JLabel("หรือพิมพ์ IP (Wi-Fi/LAN เดียวกัน):");
        hint.setFont(FONT);
        hint.setForeground(DIM);
        manual.add(hint, BorderLayout.NORTH);
        manual.add(ipField, BorderLayout.CENTER);
        manual.add(add, BorderLayout.EAST);
        JButton rl = button("เพิ่มด้วยรหัสจับคู่ (อยู่คนละที่)", BLUE);
        rl.addActionListener(e -> askRelayCode());
        JButton ts = button("ค้นหาผ่าน Tailscale (ถ้าลงไว้)", new Color(0x24324A));
        ts.addActionListener(e -> scanTailscale(true));
        JPanel crossBtns = new JPanel(new GridLayout(2, 1, 0, 6));
        crossBtns.setOpaque(false);
        crossBtns.add(rl);
        crossBtns.add(ts);
        manual.add(crossBtns, BorderLayout.SOUTH);
        JPopupMenu pm = new JPopupMenu();
        JMenuItem rm = new JMenuItem("ลบออกจากรายการ");
        rm.setFont(FONT);
        rm.addActionListener(e -> removeSelectedPhone());
        JMenuItem ru = new JMenuItem("ตั้งค่าขั้นสูง: Relay URL...");
        ru.setFont(FONT);
        ru.addActionListener(e -> editRelayUrl());
        pm.add(rm);
        pm.add(ru);
        phoneList.setComponentPopupMenu(pm);
        phonesCard.add(manual, BorderLayout.SOUTH);
        phonesCard.setPreferredSize(new Dimension(320, 300));
        JPanel client = new JPanel(new BorderLayout(12, 12));
        client.setOpaque(false);
        client.add(phonesCard, BorderLayout.WEST);

        // ---- devices (center)
        JPanel center = new JPanel(new GridLayout(2, 1, 12, 12));
        center.setOpaque(false);
        JPanel devCard = card("อุปกรณ์ USB บนมือถือที่เลือก");
        styleTable(devTable);
        devTable.getColumnModel().getColumn(0).setPreferredWidth(230);
        devTable.getColumnModel().getColumn(1).setPreferredWidth(90);
        devTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        devTable.getColumnModel().getColumn(3).setPreferredWidth(100);
        devTable.getColumnModel().getColumn(4).setPreferredWidth(220);
        devTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) attachSelected(); }
        });
        devCard.add(scroll(devTable), BorderLayout.CENTER);
        JPanel devButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        devButtons.setOpaque(false);
        JButton attach = button("เชื่อมต่อเข้าคอม", BLUE);
        attach.addActionListener(e -> attachSelected());
        JButton refresh = button("รีเฟรช", LINE);
        refresh.addActionListener(e -> refreshDevices());
        lowLatency.setFont(FONT);
        lowLatency.setForeground(SILVER);
        lowLatency.setOpaque(false);
        devButtons.add(attach);
        devButtons.add(refresh);
        devButtons.add(lowLatency);
        devCard.add(devButtons, BorderLayout.SOUTH);
        center.add(devCard);

        JPanel portCard = card("อุปกรณ์ที่เชื่อมอยู่บนคอมนี้ (ดูใน Device Manager ได้)");
        styleTable(portTable);
        portTable.getColumnModel().getColumn(0).setMaxWidth(70);
        portCard.add(scroll(portTable), BorderLayout.CENTER);
        JPanel portButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        portButtons.setOpaque(false);
        JButton detach = button("ตัดการเชื่อมต่อ", RED);
        detach.addActionListener(e -> detachSelected());
        JButton detachAll = button("ตัดทั้งหมด", LINE);
        detachAll.addActionListener(e -> work.execute(() -> {
            UsbipCli.Result r = cli.detachAll();
            log("ตัดทั้งหมด: " + (r.ok() ? "สำเร็จ" : r.output));
            refreshPorts();
        }));
        portButtons.add(detach);
        portButtons.add(detachAll);
        portCard.add(portButtons, BorderLayout.SOUTH);
        center.add(portCard);
        client.add(center, BorderLayout.CENTER);
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(FONT_B);
        tabs.setBackground(CARD);
        tabs.setForeground(Color.WHITE);
        tabs.addTab("  ใช้ USB จากเครื่องอื่น  ", client);
        sharePanel = new SharePanel(this);
        tabs.addTab("  แชร์ USB ของคอมนี้  ", sharePanel);
        root.add(tabs, BorderLayout.CENTER);

        // ---- log (bottom)
        logArea.setEditable(false);
        logArea.setFont(pickFont(Font.PLAIN, 13));
        logArea.setBackground(CARD);
        logArea.setForeground(DIM);
        logArea.setRows(7);
        logArea.setLineWrap(true);
        JPanel logCard = card(null);
        logCard.add(scroll(logArea), BorderLayout.CENTER);
        root.add(logCard, BorderLayout.SOUTH);

        frame.setSize(1180, 760);
        frame.setMinimumSize(new Dimension(960, 620));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        updateUsbipStatus();
        updateLicenseLabel();
        new Timer(60000, e -> updateLicenseLabel()).start();
        if (!license.status().allowed()) SwingUtilities.invokeLater(() -> license.showDialog());
        startBeaconListener();
        new Timer(3000, e -> { phoneList.repaint(); }).start();
        new Timer(6000, e -> work.execute(this::refreshPorts)).start();
        new Timer(30000, e -> scanTailscale(false)).start();
        scanTailscale(false);
        for (String c : prefs.get("relayCodes", "").split(",")) if (!c.isEmpty()) addRelay(c, false);
        new Timer(30000, e -> refreshRelayStatus()).start();
        work.execute(this::refreshPorts);
        log("พร้อมใช้งาน — เปิดแอพ PROMAX USB Share บนมือถือ แล้วต่อ Wi-Fi วงเดียวกับคอมนี้");
    }

    void updateUsbipStatus() {
        cli.rescan();
        if (cli.available()) {
            usbipStatus.setText("พบ usbip-win2 แล้ว");
            usbipStatus.setForeground(GREEN);
        } else {
            usbipStatus.setText("ยังไม่ได้ติดตั้ง usbip-win2");
            usbipStatus.setForeground(RED);
            log("ยังไม่พบ usbip-win2 — รันตัวติดตั้ง PromaxUsbShare-Setup.exe อีกครั้ง แล้วรีสตาร์ทเครื่อง");
        }
    }

    void openUrl(String url) {
        try { Desktop.getDesktop().browse(new URI(url)); } catch (Exception e) { log("เปิดเว็บไม่ได้: " + url); }
    }

    // ---------- discovery ----------

    void startBeaconListener() {
        Thread t = new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket(null)) {
                ds.setReuseAddress(true);
                ds.bind(new InetSocketAddress(Beacon.PORT));
                byte[] buf = new byte[2048];
                while (true) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    ds.receive(p);
                    Beacon b = Beacon.parse(p.getData(), p.getLength());
                    if (b == null) continue;
                    String host = p.getAddress().getHostAddress();
                    SwingUtilities.invokeLater(() -> onBeacon(host, b));
                }
            } catch (Exception e) {
                log("ฟังการค้นหาอัตโนมัติไม่ได้ (" + e.getMessage() + ") — พิมพ์ IP มือถือเองได้");
            }
        }, "beacon");
        t.setDaemon(true);
        t.start();
    }

    void onBeacon(String host, Beacon b) {
        if (SharePanel.isLocalAddress(host)) return; // our own "share this PC" beacon
        Phone ph = phones.get(host);
        boolean isNew = ph == null;
        if (isNew) {
            ph = new Phone();
            ph.host = host;
            phones.put(host, ph);
            phoneModel.addElement(ph);
            log("พบมือถือ " + b.name + " ที่ " + host);
        }
        String oldKey = ph.beacon == null ? "" : beaconKey(ph.beacon);
        ph.name = b.name;
        ph.port = b.port;
        ph.lastSeen = System.currentTimeMillis();
        ph.beacon = b;
        if (isNew && phoneList.getSelectedIndex() < 0) phoneList.setSelectedValue(ph, true);
        else if (ph == phoneList.getSelectedValue() && !oldKey.equals(beaconKey(b))) refreshDevices();
        phoneList.repaint();
    }

    static String beaconKey(Beacon b) {
        StringBuilder sb = new StringBuilder();
        for (Beacon.Dev d : b.devices) sb.append(d.busid).append(d.inUse).append(';');
        return sb.toString();
    }

    /** Looks for phones on the Tailscale network (works across different networks / mobile data). */
    void scanTailscale(boolean userClicked) {
        scan.execute(() -> {
            if (Tailscale.exe() == null) {
                if (userClicked) {
                    log("ยังไม่ได้ติดตั้ง Tailscale บนคอมนี้ — ติดตั้งจาก " + Tailscale.DOWNLOAD_URL + " แล้วล็อกอินบัญชีเดียวกับในมือถือ");
                    openUrl(Tailscale.DOWNLOAD_URL);
                }
                return;
            }
            List<Tailscale.Peer> peers = Tailscale.peers();
            if (userClicked) log("Tailscale: พบอุปกรณ์ในเครือข่าย " + peers.size() + " เครื่อง กำลังตรวจหาแอพ USB Share...");
            List<Thread> probes = new ArrayList<>();
            final int[] found = {0};
            for (Tailscale.Peer p : peers) {
                if (!p.online) continue;
                Thread t = new Thread(() -> {
                    if (Tailscale.answersUsbIp(p.ip, Proto.PORT, 3000)) {
                        synchronized (found) { found[0]++; }
                        SwingUtilities.invokeLater(() -> onTailscalePhone(p));
                    }
                });
                t.setDaemon(true);
                t.start();
                probes.add(t);
            }
            for (Thread t : probes) { try { t.join(8000); } catch (InterruptedException ignored) {} }
            if (userClicked) {
                if (found[0] == 0) log("Tailscale: ยังไม่เจอมือถือ — เปิด Tailscale และแอพ PROMAX USB Share บนมือถือ (กด 'เริ่มแชร์') แล้วลองใหม่");
                else log("Tailscale: พบมือถือ " + found[0] + " เครื่อง");
            }
        });
    }

    void onTailscalePhone(Tailscale.Peer p) {
        Phone ph = phones.get(p.ip);
        if (ph == null) {
            ph = new Phone();
            ph.host = p.ip;
            phones.put(p.ip, ph);
            phoneModel.addElement(ph);
            log("พบมือถือผ่าน Tailscale: " + p.name + " (" + p.ip + ")");
            if (phoneList.getSelectedIndex() < 0) phoneList.setSelectedValue(ph, true);
        }
        ph.viaTailscale = true;
        if (ph.name.isEmpty()) ph.name = p.name;
        ph.lastSeen = System.currentTimeMillis();
        phoneList.repaint();
    }

    // ---------- relay (pairing code) ----------

    String relayUrl() { return prefs.get("relayUrl", Relay.DEFAULT_URL); }

    void editRelayUrl() {
        String u = (String) JOptionPane.showInputDialog(frame, "Relay URL (ต้องตรงกับในแอพมือถือ)", "ตั้งค่า Relay",
                JOptionPane.PLAIN_MESSAGE, null, null, relayUrl());
        if (u == null) return;
        u = u.trim();
        if (u.isEmpty()) prefs.remove("relayUrl"); else prefs.put("relayUrl", u);
        log("Relay URL: " + relayUrl() + " (มีผลกับรหัสที่เพิ่มใหม่ / เปิดโปรแกรมครั้งถัดไป)");
    }

    void askRelayCode() {
        if (!license.require()) return;
        String c = JOptionPane.showInputDialog(frame, "พิมพ์รหัสจับคู่ 8 ตัว ที่แสดงในแอพมือถือ หรือในแท็บ 'แชร์ USB ของคอมนี้' ของคอมเครื่องที่แชร์", "เพิ่มด้วยรหัสจับคู่", JOptionPane.PLAIN_MESSAGE);
        if (c == null) return;
        c = Relay.normalizeCode(c);
        if (c.length() < 6) {
            JOptionPane.showMessageDialog(frame, "รหัสไม่ถูกต้อง");
            return;
        }
        addRelay(c, true);
    }

    void addRelay(String code, boolean userAdded) {
        String key = "relay:" + code;
        if (phones.containsKey(key)) {
            phoneList.setSelectedValue(phones.get(key), true);
            return;
        }
        scan.execute(() -> {
            Relay.Status st = Relay.status(relayUrl(), code);
            if (!st.reachable && userAdded) {
                log("ติดต่อ Relay ไม่ได้ (" + st.error + ") — ตรวจอินเทอร์เน็ต หรือ Relay URL");
            }
            Relay.Forwarder f = new Relay.Forwarder(relayUrl(), code, this::log)
                    .credentials(prefs.get("lic.key", null), license.device());
            try {
                f.start(3250);
            } catch (Exception e) {
                log("เปิดพอร์ตในเครื่องสำหรับ Relay ไม่ได้: " + e.getMessage());
                return;
            }
            SwingUtilities.invokeLater(() -> {
                forwarders.put(code, f);
                Phone ph = new Phone();
                ph.host = "127.0.0.1";
                ph.port = f.port();
                ph.viaRelay = true;
                ph.relayCode = code;
                ph.relayOnline = st.online;
                ph.name = st.name;
                phones.put(key, ph);
                phoneModel.addElement(ph);
                saveRelayCodes();
                if (userAdded) {
                    log(st.online ? "เชื่อมกับมือถือ " + st.name + " แล้ว (รหัส " + code + ") — เปิดโปรแกรมนี้ค้างไว้ระหว่างใช้งาน"
                                  : "เพิ่มรหัส " + code + " แล้ว แต่มือถือยังออฟไลน์ — เปิดแอพบนมือถือและเปิดโหมดรหัสจับคู่");
                    phoneList.setSelectedValue(ph, true);
                }
            });
        });
    }

    void saveRelayCodes() {
        StringBuilder sb = new StringBuilder();
        for (String c : forwarders.keySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(c);
        }
        prefs.put("relayCodes", sb.toString());
    }

    void refreshRelayStatus() {
        List<Phone> rel = new ArrayList<>();
        for (Phone p : phones.values()) if (p.viaRelay) rel.add(p);
        if (rel.isEmpty()) return;
        scan.execute(() -> {
            for (Phone p : rel) {
                Relay.Status st = Relay.status(relayUrl(), p.relayCode);
                SwingUtilities.invokeLater(() -> {
                    boolean was = p.relayOnline;
                    p.relayOnline = st.online;
                    if (!st.name.isEmpty()) p.name = st.name;
                    if (was != st.online) log("มือถือรหัส " + p.relayCode + (st.online ? " ออนไลน์แล้ว" : " ออฟไลน์"));
                    phoneList.repaint();
                });
            }
        });
    }

    void removeSelectedPhone() {
        Phone ph = phoneList.getSelectedValue();
        if (ph == null) return;
        if (ph.viaRelay) {
            Relay.Forwarder f = forwarders.remove(ph.relayCode);
            if (f != null) f.stop();
            phones.remove("relay:" + ph.relayCode);
            saveRelayCodes();
        } else {
            phones.remove(ph.host);
        }
        phoneModel.removeElement(ph);
        devModel.setRowCount(0);
        shownDevices.clear();
    }

    void addManual(String ip) {
        if (ip.isEmpty()) return;
        String host = ip;
        int port = Proto.PORT;
        int c = ip.lastIndexOf(':');
        if (c > 0) {
            host = ip.substring(0, c);
            try { port = Integer.parseInt(ip.substring(c + 1)); } catch (NumberFormatException ignored) {}
        }
        Phone ph = phones.get(host);
        if (ph == null) {
            ph = new Phone();
            ph.host = host;
            phones.put(host, ph);
            phoneModel.addElement(ph);
        }
        ph.manual = true;
        ph.viaTailscale = Tailscale.isTailscaleIp(host);
        ph.port = port;
        phoneList.setSelectedValue(ph, true);
        refreshDevices();
    }

    // ---------- devices ----------

    void refreshDevices() {
        Phone ph = phoneList.getSelectedValue();
        if (ph == null) return;
        final String host = ph.host;
        final int port = ph.port;
        final Beacon beacon = ph.beacon;
        final Phone target = ph;
        work.execute(() -> {
            List<Proto.DeviceInfo> list = new ArrayList<>();
            String err = null;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 4000);
                s.setSoTimeout(target.viaRelay ? 25000 : 8000);
                s.getOutputStream().write(Proto.encodeOpHeader(Proto.OP_REQ_DEVLIST, 0));
                list = Proto.readDevList(new DataInputStream(s.getInputStream()));
            } catch (Exception e) {
                err = (e instanceof java.io.EOFException || e.getMessage() == null)
                        ? "การเชื่อมต่อถูกตัดก่อนได้รายการอุปกรณ์ (" + e.getClass().getSimpleName() + ")"
                        : e.getMessage();
            }
            final List<Proto.DeviceInfo> fl = list;
            final String fe = err;
            SwingUtilities.invokeLater(() -> fillDevices(target, fl, beacon, fe));
        });
    }

    void fillDevices(Phone target, List<Proto.DeviceInfo> list, Beacon beacon, String err) {
        Phone sel = phoneList.getSelectedValue();
        if (sel != target) return;
        String host = target.viaRelay ? "รหัส " + target.relayCode : target.host;
        devModel.setRowCount(0);
        shownDevices.clear();
        if (err != null) log("ติดต่อมือถือ " + host + " ไม่ได้: " + err + " (เปิดแอพบนมือถือและกด 'เริ่มแชร์' แล้วหรือยัง?)");
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (Proto.DeviceInfo d : list) {
            String name = d.product;
            if (beacon != null) for (Beacon.Dev bd : beacon.devices) if (bd.busid.equals(d.busid) && !bd.product.isEmpty()) name = bd.product;
            if (name == null || name.isEmpty()) name = Proto.knownName(d.idVendor, d.idProduct);
            devModel.addRow(new Object[]{name, String.format("%04X:%04X", d.idVendor, d.idProduct), d.busid,
                    Proto.speedName(d.speed), "ว่าง — พร้อมเชื่อมต่อ"});
            shownDevices.add(d);
            listed.add(d.busid);
        }
        if (beacon != null) {
            for (Beacon.Dev bd : beacon.devices) {
                if (listed.contains(bd.busid)) continue;
                devModel.addRow(new Object[]{bd.product, String.format("%04X:%04X", bd.vid, bd.pid), bd.busid, "",
                        bd.inUse ? "ใช้งานอยู่ (มีคอมเชื่อมแล้ว)" : "ไม่พร้อม"});
                Proto.DeviceInfo d = new Proto.DeviceInfo();
                d.busid = bd.busid; d.idVendor = bd.vid; d.idProduct = bd.pid; d.product = bd.product;
                d.path = null; // marker: not attachable now
                shownDevices.add(d);
            }
        }
        if (devModel.getRowCount() == 0 && err == null) log("มือถือ " + host + " ยังไม่มีอุปกรณ์ USB เสียบอยู่");
    }

    static boolean isOpenPort(Proto.DeviceInfo d) {
        return Proto.isEcuFlashInterface(d.idVendor, d.idProduct);
    }

    void attachSelected() {
        if (!license.require()) return;
        Phone ph = phoneList.getSelectedValue();
        int row = devTable.getSelectedRow();
        if (ph == null || row < 0 || row >= shownDevices.size()) {
            JOptionPane.showMessageDialog(frame, "เลือกมือถือและอุปกรณ์ก่อน");
            return;
        }
        Proto.DeviceInfo d = shownDevices.get(row);
        if (d.path == null) {
            JOptionPane.showMessageDialog(frame, "อุปกรณ์นี้มีคอมเครื่องอื่นเชื่อมอยู่ ต้องตัดการเชื่อมต่อที่เครื่องนั้นก่อน");
            return;
        }
        if (!cli.available()) {
            updateUsbipStatus();
            if (!cli.available()) {
                int a = JOptionPane.showConfirmDialog(frame,
                        "ต้องติดตั้ง usbip-win2 ก่อน (ฟรี, ไดรเวอร์ที่ Microsoft รับรอง)\nเปิดหน้าดาวน์โหลดเลยไหม?",
                        "ยังไม่มี usbip-win2", JOptionPane.YES_NO_OPTION);
                if (a == JOptionPane.YES_OPTION) openUrl(UsbipCli.DOWNLOAD_URL);
                return;
            }
        }
        if (ph.viaRelay)
            log("เชื่อมต่อด้วยรหัสจับคู่ (" + ph.relayCode + ") — ต้องเปิดโปรแกรมนี้ค้างไว้ตลอดการใช้งาน ถ้าปิด อุปกรณ์จะหลุด");
        if (ph.viaTailscale)
            log("เชื่อมต่อข้ามเครือข่ายผ่าน Tailscale — จะหน่วงกว่า Wi-Fi วงเดียวกัน ถ้าโปรแกรมสแกนขึ้น timeout ให้ลองใหม่ หรือย้ายมาใช้ Wi-Fi วงเดียวกัน");
        if (isOpenPort(d)) {
            int a = JOptionPane.showConfirmDialog(frame,
                    Proto.knownName(d.idVendor, d.idProduct) + " ผ่านเครือข่าย\n\n"
                            + "ใช้ได้: อ่าน/ลบโค้ด, ดูค่าสด, ล็อกข้อมูล\n"
                            + "ห้าม: แฟลช/เขียน ECU (ROM write/reflash)\n\n"
                            + "ถ้า Wi-Fi สะดุดกลางการแฟลช กล่อง ECU อาจเสียจนสตาร์ทไม่ติด\n"
                            + "เวลาแฟลชให้เสียบสาย USB ตรงเข้าคอมเท่านั้น\n\nเข้าใจแล้ว เชื่อมต่อต่อไหม?",
                    "คำเตือน OpenPort", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (a != JOptionPane.YES_OPTION) return;
        }
        final String host = ph.host;
        final int port = ph.port;
        final boolean ll = lowLatency.isSelected();
        log("กำลังเชื่อมต่อ " + d.busid + " จาก " + host + " ... (ถ้ามือถือถามสิทธิ์ USB ให้กดตกลง)");
        work.execute(() -> {
            UsbipCli.Result r = cli.attach(host, port, d.busid, ll);
            log((r.ok() ? "เชื่อมต่อสำเร็จ: " : "เชื่อมต่อไม่สำเร็จ: ") + r.output.replace('\n', ' '));
            refreshPorts();
            SwingUtilities.invokeLater(this::refreshDevices);
        });
    }

    void detachSelected() {
        int row = portTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(frame, "เลือกอุปกรณ์ในตารางล่างก่อน");
            return;
        }
        int port = Integer.parseInt(String.valueOf(portModel.getValueAt(row, 0)));
        work.execute(() -> {
            UsbipCli.Result r = cli.detach(port);
            log("ตัดการเชื่อมต่อพอร์ต " + port + ": " + (r.ok() ? "สำเร็จ" : r.output));
            refreshPorts();
            SwingUtilities.invokeLater(this::refreshDevices);
        });
    }

    /** Runs on the work thread. */
    void refreshPorts() {
        if (!cli.available()) return;
        List<UsbipCli.Port> ports = cli.ports();
        String raw = cli.lastPortOutput();
        if (!raw.equals(lastPortRaw)) {
            lastPortRaw = raw;
            if (cli.lastPortExit() != 0 || (ports.isEmpty() && raw.toLowerCase().contains("port")))
                log("usbip port (" + cli.lastPortExit() + "): " + raw.replace('\n', ' '));
        }
        SwingUtilities.invokeLater(() -> {
            int sel = portTable.getSelectedRow();
            portModel.setRowCount(0);
            for (UsbipCli.Port p : ports) portModel.addRow(new Object[]{p.port, p.description, p.remote});
            if (sel >= 0 && sel < portModel.getRowCount()) portTable.setRowSelectionInterval(sel, sel);
        });
    }
}
