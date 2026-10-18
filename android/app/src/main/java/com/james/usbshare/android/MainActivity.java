package com.james.usbshare.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Switch;
import android.content.SharedPreferences;
import com.james.usbshare.relay.Relay;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.james.usbshare.usbip.Proto;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    static final int BG = 0xFF0B0D10, CARD = 0xFF161A21, BLUE = 0xFF1FA2FF, RED = 0xFFE53935,
            SILVER = 0xFFC3C9D0, DIM = 0xFF7D8590, GREEN = 0xFF2ECC71;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, ipText, logView, warnView;
    private Button toggle;
    private LinearLayout deviceBox;
    private CheckBox keepScreen;
    private TextView relayStatus, relayCodeView;
    private Switch relaySwitch;
    private TextView licStatus;
    private String lastLic = "";
    private long lastLogVersion = -1;
    private String lastDeviceKey = "";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int color, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 0x22FFFFFF));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private Button button(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setBackground(round(color, 0));
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        return b;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(24));
        scroll.addView(root);

        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.promax_logo);
        logo.setAdjustViewBounds(true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(300), -2);
        llp.bottomMargin = dp(10);
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        logo.setLayoutParams(llp);
        root.addView(logo);
        TextView title = text("PROMAX USB Share", 26, Color.WHITE, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        root.addView(title);
        TextView sub = text("แชร์ USB จากมือถือไปคอม Windows  •  Tune by MAXS", 13, SILVER, false);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        root.addView(sub);

        LinearLayout c1 = card();
        status = text("", 17, GREEN, true);
        ipText = text("", 15, Color.WHITE, false);
        ipText.setTextIsSelectable(true);
        toggle = button("เริ่มแชร์", BLUE);
        toggle.setOnClickListener(v -> toggleService());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(10);
        toggle.setLayoutParams(blp);
        keepScreen = new CheckBox(this);
        keepScreen.setText("เปิดหน้าจอค้างไว้ (Wi-Fi หน่วงน้อยสุด แนะนำ)");
        keepScreen.setTextColor(SILVER);
        keepScreen.setChecked(true);
        keepScreen.setOnCheckedChangeListener((b, on) -> applyKeepScreen());
        c1.addView(status);
        c1.addView(ipText);
        c1.addView(toggle);
        c1.addView(keepScreen);
        root.addView(c1);

        LinearLayout cl = card();
        cl.addView(text("ลงทะเบียน", 16, Color.WHITE, true));
        licStatus = text("", 14, GREEN, true);
        cl.addView(licStatus);
        cl.addView(text("1 คีย์ใช้ได้กับคอม 1 เครื่อง + มือถือ 1 เครื่อง — ส่งรหัสเครื่องทั้งสองให้ผู้ขาย แล้วใส่คีย์เดียวกันทั้งสองเครื่อง\nรหัสเครื่องมือถือนี้:", 13, SILVER, false));
        TextView devId = text(LicenseManager.deviceId(this), 22, BLUE, true);
        devId.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        devId.setTextIsSelectable(true);
        cl.addView(devId);
        LinearLayout lrow = new LinearLayout(this);
        lrow.setOrientation(LinearLayout.HORIZONTAL);
        Button copyId = button("คัดลอกรหัสเครื่อง", 0xFF2A313B);
        copyId.setTextSize(13);
        copyId.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("device id", LicenseManager.deviceId(this)));
            android.widget.Toast.makeText(this, "คัดลอกแล้ว", android.widget.Toast.LENGTH_SHORT).show();
        });
        Button enterKey = button("ใส่คีย์", BLUE);
        enterKey.setTextSize(13);
        enterKey.setOnClickListener(v -> askKey());
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1f);
        lp1.rightMargin = dp(8);
        copyId.setLayoutParams(lp1);
        enterKey.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        lrow.addView(copyId);
        lrow.addView(enterKey);
        LinearLayout.LayoutParams lrp = new LinearLayout.LayoutParams(-1, -2);
        lrp.topMargin = dp(8);
        lrow.setLayoutParams(lrp);
        cl.addView(lrow);
        Button buy = button("ติดต่อผู้พัฒนา / ซื้อคีย์ (LINE)", 0xFF06C755);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(8);
        buy.setLayoutParams(bp);
        buy.setOnClickListener(v -> contactDeveloper());
        cl.addView(buy);
        root.addView(cl);

        warnView = text("", 13, Color.WHITE, false);
        warnView.setBackground(round(0x33E53935, RED));
        warnView.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.topMargin = dp(12);
        warnView.setLayoutParams(wlp);
        warnView.setText("⚠ OpenPort / SM2 Pro: ใช้ผ่านเครือข่ายได้เฉพาะอ่านโค้ด/ลบโค้ด/ดูค่า\n"
                + "ห้ามแฟลช/เขียน ECU ผ่านเครือข่าย — สัญญาณสะดุดกลางคันทำให้กล่อง ECU เสียได้ ให้ต่อสาย USB ตรงกับคอมแทน");
        warnView.setVisibility(View.GONE);
        root.addView(warnView);

        LinearLayout c2 = card();
        c2.addView(text("อุปกรณ์ USB ที่เสียบอยู่ (OTG)", 16, Color.WHITE, true));
        deviceBox = new LinearLayout(this);
        deviceBox.setOrientation(LinearLayout.VERTICAL);
        c2.addView(deviceBox);
        root.addView(c2);

        LinearLayout cr = card();
        cr.addView(text("ใช้ข้ามเครือข่าย (มือถือใช้เน็ตมือถือ / คนละ Wi-Fi)", 16, Color.WHITE, true));
        cr.addView(text("ไม่ต้องลงแอพอื่น — พิมพ์รหัสด้านล่างในโปรแกรมบนคอมครั้งเดียว ใช้ได้ทุกที่ที่มีอินเทอร์เน็ต\n(ถ้าลง Tailscale ไว้ทั้งสองเครื่อง ก็ใช้แทนได้ — ดู IP Tailscale ด้านบน)", 13, SILVER, false));
        relaySwitch = new Switch(this);
        relaySwitch.setText("ให้คอมเชื่อมต่อจากที่อื่นได้");
        relaySwitch.setTextColor(Color.WHITE);
        relaySwitch.setTextSize(15);
        relaySwitch.setChecked(UsbShareService.relayEnabled(this));
        relaySwitch.setOnCheckedChangeListener((b, on) -> {
            getSharedPreferences(UsbShareService.PREFS, MODE_PRIVATE).edit().putBoolean("enabled", on).apply();
            UsbShareService svc = UsbShareService.get();
            if (svc != null) svc.applyRelay();
        });
        cr.addView(relaySwitch);
        relayCodeView = text(UsbShareService.relayCode(this), 30, BLUE, true);
        relayCodeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        relayCodeView.setLetterSpacing(0.15f);
        relayCodeView.setTextIsSelectable(true);
        cr.addView(relayCodeView);
        relayStatus = text("", 13, DIM, false);
        cr.addView(relayStatus);
        LinearLayout rrow = new LinearLayout(this);
        rrow.setOrientation(LinearLayout.HORIZONTAL);
        Button newCode = button("เปลี่ยนรหัส", 0xFF2A313B);
        newCode.setTextSize(13);
        newCode.setOnClickListener(v -> {
            getSharedPreferences(UsbShareService.PREFS, MODE_PRIVATE).edit().putString("code", Relay.newCode()).apply();
            relayCodeView.setText(UsbShareService.relayCode(this));
            UsbShareService svc = UsbShareService.get();
            if (svc != null) svc.applyRelay();
            LogBus.add("เปลี่ยนรหัสจับคู่แล้ว — คอมที่ใช้รหัสเก่าจะเชื่อมต่อไม่ได้อีก");
        });
        Button urlBtn = button("ตั้งค่า Relay URL", 0xFF2A313B);
        urlBtn.setTextSize(13);
        urlBtn.setOnClickListener(v -> editRelayUrl());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1f);
        rp.rightMargin = dp(8);
        newCode.setLayoutParams(rp);
        urlBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        rrow.addView(newCode);
        rrow.addView(urlBtn);
        LinearLayout.LayoutParams rrp = new LinearLayout.LayoutParams(-1, -2);
        rrp.topMargin = dp(8);
        rrow.setLayoutParams(rrp);
        cr.addView(rrow);
        root.addView(cr);

        LinearLayout c3 = card();
        c3.addView(text("วิธีใช้บนคอม", 16, Color.WHITE, true));
        c3.addView(text("1) กด \'เริ่มแชร์\' — ถ้าอยู่ Wi-Fi เดียวกัน คอมจะเจอมือถือเอง ถ้าอยู่คนละที่ ให้ใช้รหัสจับคู่ด้านบน\n"
                + "2) เปิดโปรแกรม PROMAX USB Share บน Windows (ต้องติดตั้ง usbip-win2 ก่อน)\n"
                + "3) เลือกมือถือเครื่องนี้ → เลือกอุปกรณ์ → กด 'เชื่อมต่อ'\n"
                + "4) ถ้ามีหน้าต่างขออนุญาต USB เด้งบนมือถือ ให้กด 'ตกลง'", 13, SILVER, false));
        Button battery = button("ปิดการประหยัดแบตให้แอพนี้", 0xFF2A313B);
        LinearLayout.LayoutParams bl2 = new LinearLayout.LayoutParams(-1, -2);
        bl2.topMargin = dp(10);
        battery.setLayoutParams(bl2);
        battery.setOnClickListener(v -> askBatteryExemption());
        c3.addView(battery);
        root.addView(c3);

        LinearLayout c4 = card();
        c4.addView(text("บันทึกการทำงาน", 16, Color.WHITE, true));
        logView = text("", 11, DIM, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        c4.addView(logView);
        root.addView(c4);

        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        // sharing starts only when the user taps "เริ่มแชร์"
        applyKeepScreen();
    }

    @Override protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    private void editRelayUrl() {
        EditText et = new EditText(this);
        et.setText(UsbShareService.relayUrl(this));
        et.setSingleLine(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Relay URL")
                .setMessage("ใช้ค่าเดียวกับในโปรแกรมบนคอม")
                .setView(et)
                .setPositiveButton("บันทึก", (d, w) -> {
                    String u = et.getText().toString().trim();
                    getSharedPreferences(UsbShareService.PREFS, MODE_PRIVATE).edit().putString("url", u.isEmpty() ? Relay.DEFAULT_URL : u).apply();
                    UsbShareService svc = UsbShareService.get();
                    if (svc != null) svc.applyRelay();
                })
                .setNeutralButton("ค่าเริ่มต้น", (d, w) -> {
                    getSharedPreferences(UsbShareService.PREFS, MODE_PRIVATE).edit().remove("url").apply();
                    UsbShareService svc = UsbShareService.get();
                    if (svc != null) svc.applyRelay();
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void applyKeepScreen() {
        if (keepScreen.isChecked()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** Copies this phone's device id, then opens the developer's LINE so the customer can paste it. */
    private void contactDeveloper() {
        String id = LicenseManager.deviceId(this);
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("device id", "ขอซื้อคีย์ PROMAX USB Share (มือถือ)\nรหัสเครื่อง: " + id));
        android.widget.Toast.makeText(this, "คัดลอกรหัสเครื่องแล้ว — วางในแชท LINE ได้เลย", android.widget.Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(com.james.usbshare.license.License.CONTACT_URL)));
        } catch (Exception e) {
            android.widget.Toast.makeText(this, com.james.usbshare.license.License.CONTACT_URL, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void askKey() {
        EditText et = new EditText(this);
        et.setHint("JUS1-XXXXX-XXXXX-...");
        et.setMinLines(3);
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        try {
            CharSequence clip = cm.getPrimaryClip() != null ? cm.getPrimaryClip().getItemAt(0).getText() : null;
            if (clip != null && clip.toString().trim().toUpperCase().startsWith("JUS1")) et.setText(clip);
        } catch (Exception ignored) {}
        new android.app.AlertDialog.Builder(this)
                .setTitle("ใส่คีย์ลงทะเบียน")
                .setMessage("รหัสเครื่อง: " + LicenseManager.deviceId(this))
                .setView(et)
                .setPositiveButton("ยืนยัน", (d, w) -> {
                    com.james.usbshare.license.License.Status st = LicenseManager.saveKey(this, et.getText().toString());
                    String msg = com.james.usbshare.license.License.describe(st);
                    LogBus.add(msg);
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
                    lastLic = "";
                    refresh();
                })
                .setNeutralButton("ซื้อคีย์ (LINE)", (d, w) -> contactDeveloper())
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void startShare() {
        com.james.usbshare.license.License.Status st = LicenseManager.status(this);
        if (!st.allowed()) {
            android.widget.Toast.makeText(this, com.james.usbshare.license.License.describe(st), android.widget.Toast.LENGTH_LONG).show();
            askKey();
            return;
        }
        Intent i = new Intent(this, UsbShareService.class);
        startForegroundService(i);
    }

    private void toggleService() {
        if (UsbShareService.get() != null) stopService(new Intent(this, UsbShareService.class));
        else startShare();
        ui.postDelayed(this::refresh, 400);
    }

    private void askBatteryExemption() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            LogBus.add("แอพนี้ไม่ถูกจำกัดการใช้แบตอยู่แล้ว");
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void refresh() {
        UsbShareService svc = UsbShareService.get();
        boolean running = svc != null && svc.server() != null && svc.server().isRunning();
        List<String> ips = UsbShareService.localIps();
        if (running) {
            status.setText("● กำลังแชร์");
            status.setTextColor(GREEN);
            ipText.setText(netText(ips) + "\nURB ที่รับส่งแล้ว: " + svc.server().urbCount.get());
            toggle.setText("หยุดแชร์");
            toggle.setBackground(round(RED, 0));
        } else {
            status.setText("○ หยุดแชร์อยู่");
            status.setTextColor(DIM);
            ipText.setText(netText(ips));
            toggle.setText("เริ่มแชร์");
            toggle.setBackground(round(BLUE, 0));
        }

        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        List<String> busy = running ? svc.server().attachedBusids() : new ArrayList<>();
        List<UsbDevice> devs = new ArrayList<>(um.getDeviceList().values());
        devs.sort((a, b) -> AndroidUsbBackend.busidOf(a).compareTo(AndroidUsbBackend.busidOf(b)));
        StringBuilder key = new StringBuilder();
        boolean openport = false;
        for (UsbDevice d : devs) {
            key.append(d.getDeviceName()).append(um.hasPermission(d)).append(busy.contains(AndroidUsbBackend.busidOf(d))).append(';');
            if (Proto.isEcuFlashInterface(d.getVendorId(), d.getProductId())) openport = true;
        }
        warnView.setVisibility(openport ? View.VISIBLE : View.GONE);
        if (!key.toString().equals(lastDeviceKey)) {
            lastDeviceKey = key.toString();
            deviceBox.removeAllViews();
            if (devs.isEmpty()) {
                deviceBox.addView(text("ยังไม่มีอุปกรณ์ — เสียบผ่านสาย OTG ได้เลย", 14, DIM, false));
            }
            for (UsbDevice d : devs) deviceBox.addView(deviceRow(um, d, busy.contains(AndroidUsbBackend.busidOf(d))));
        }

        if (!relaySwitch.isChecked()) {
            relayStatus.setText("ปิดอยู่");
            relayStatus.setTextColor(DIM);
        } else if (svc != null && svc.relay() != null && svc.relay().isConnected()) {
            relayStatus.setText("ออนไลน์ — พิมพ์รหัสนี้ในโปรแกรมบนคอม");
            relayStatus.setTextColor(GREEN);
        } else if (svc != null && svc.relay() != null && !svc.relay().lastError().isEmpty()) {
            relayStatus.setText("เซิร์ฟเวอร์ไม่อนุญาต: " + svc.relay().lastError());
            relayStatus.setTextColor(RED);
        } else {
            relayStatus.setText(running ? "กำลังเชื่อมต่อ Relay..." : "เริ่มแชร์ก่อน แล้ว Relay จะเชื่อมต่อเอง");
            relayStatus.setTextColor(SILVER);
        }

        com.james.usbshare.license.License.Status lic = LicenseManager.status(this);
        String lt = com.james.usbshare.license.License.describe(lic);
        if (!lt.equals(lastLic)) {
            lastLic = lt;
            licStatus.setText(lt);
            licStatus.setTextColor(lic.kind == com.james.usbshare.license.License.Kind.LICENSED ? GREEN
                    : lic.allowed() ? 0xFFFFB300 : RED);
        }
        if (running && !lic.allowed()) {
            LogBus.add(lt + " — หยุดแชร์");
            stopService(new Intent(this, UsbShareService.class));
        }

        if (LogBus.version() != lastLogVersion) {
            lastLogVersion = LogBus.version();
            logView.setText(LogBus.text());
        }
    }

    private String netText(List<String> ips) {
        String ts = UsbShareService.tailscaleIp();
        List<String> lan = new ArrayList<>();
        for (String ip : ips) if (!ip.equals(ts)) lan.add(ip);
        String s = lan.isEmpty() ? "ยังไม่ได้ต่อเครือข่าย" : "IP มือถือ: " + String.join(", ", lan) + " (ใช้กับคอมใน Wi-Fi เดียวกัน)";
        s += ts == null ? "\nTailscale: ไม่ได้เปิด (ไม่บังคับ)" : "\nTailscale: " + ts + " (คอมที่ลง Tailscale บัญชีเดียวกันจะเจอเอง)";
        return s;
    }

    private View deviceRow(UsbManager um, UsbDevice d, boolean inUse) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(text(AndroidUsbBackend.nameOf(d), 15, Color.WHITE, true));
        String state = inUse ? "PC กำลังใช้งาน" : (um.hasPermission(d) ? "พร้อมแชร์" : "ยังไม่อนุญาต (จะถามตอน PC เชื่อมต่อ)");
        col.addView(text(String.format("ID %04X:%04X  •  busid %s\n%s", d.getVendorId(), d.getProductId(),
                AndroidUsbBackend.busidOf(d), state), 12, inUse ? BLUE : SILVER, false));
        row.addView(col);
        if (!um.hasPermission(d)) {
            Button b = button("อนุญาต", BLUE);
            b.setTextSize(13);
            b.setOnClickListener(v -> {
                UsbShareService svc = UsbShareService.get();
                if (svc != null) svc.backend().requestPermission(d);
                else new AndroidUsbBackend(this).requestPermission(d);
            });
            row.addView(b);
        }
        return row;
    }
}
