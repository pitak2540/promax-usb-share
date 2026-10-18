package com.james.usbshare.android;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import com.james.usbshare.usbip.Backend;
import com.james.usbshare.usbip.Proto;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Exposes the phone's USB host devices (OTG) to the USB/IP server. */
public class AndroidUsbBackend implements Backend {

    public static final String ACTION_USB_PERMISSION = "com.james.usbshare.USB_PERMISSION";

    private final Context ctx;
    private final UsbManager usb;
    private final Map<String, AndroidAttached> open = new ConcurrentHashMap<>();

    public AndroidUsbBackend(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
    }

    public UsbManager manager() { return usb; }

    @Override public void log(String msg) { LogBus.add(msg); }

    @Override public List<Proto.DeviceInfo> listDevices() {
        List<Proto.DeviceInfo> out = new ArrayList<>();
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getDeviceClass() == 9) continue; // hubs are not exportable; their children are listed separately
            out.add(describe(d, null));
        }
        out.sort((a, b) -> a.busid.compareTo(b.busid));
        return out;
    }

    public UsbDevice find(String busid) {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (busidOf(d).equals(busid)) return d;
        }
        return null;
    }

    public void requestPermission(UsbDevice d) {
        Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(ctx.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i, flags);
        usb.requestPermission(d, pi);
    }

    @Override public Attached attach(String busid) throws Exception {
        UsbDevice d = find(busid);
        if (d == null) throw new Exception("ไม่พบอุปกรณ์ " + busid);
        if (!usb.hasPermission(d)) {
            log("กรุณากด 'อนุญาต' บนมือถือ เพื่อให้ใช้ " + nameOf(d));
            requestPermission(d);
            long until = System.currentTimeMillis() + 45000;
            while (!usb.hasPermission(d) && System.currentTimeMillis() < until) Thread.sleep(300);
            if (!usb.hasPermission(d)) throw new Exception("ผู้ใช้ไม่ได้อนุญาตการเข้าถึง USB");
        }
        UsbDeviceConnection conn = usb.openDevice(d);
        if (conn == null) throw new Exception("เปิดอุปกรณ์ไม่ได้");
        Proto.DeviceInfo info = describe(d, conn.getRawDescriptors());
        AndroidAttached a = new AndroidAttached(d, conn, info, this::log, () -> open.remove(busid));
        open.put(busid, a);
        return a;
    }

    /** Called when Android reports the device was unplugged. */
    public void onDetached(UsbDevice d) {
        AndroidAttached a = open.remove(busidOf(d));
        if (a != null) a.markGone();
    }

    // ---------- descriptors ----------

    public static String busidOf(UsbDevice d) {
        int[] bd = busDev(d);
        return bd[0] + "-" + bd[1];
    }

    static int[] busDev(UsbDevice d) {
        // "/dev/bus/usb/001/005"
        String[] p = d.getDeviceName().split("/");
        try {
            return new int[]{Integer.parseInt(p[p.length - 2]), Integer.parseInt(p[p.length - 1])};
        } catch (Exception e) {
            int id = d.getDeviceId();
            return new int[]{1 + id / 1000, id % 1000};
        }
    }

    public static String nameOf(UsbDevice d) {
        String n = null;
        try { n = d.getProductName(); } catch (SecurityException ignored) {}
        if (n == null || n.isEmpty()) n = knownName(d.getVendorId(), d.getProductId());
        return n;
    }

    public static String knownName(int vid, int pid) {
        if (vid == 0x0403 && pid == 0xcc4c) return "Tactrix OpenPort 2.0";
        if (vid == 0x0403 && pid == 0xcc4d) return "Tactrix OpenPort 2.0";
        if (vid == 0x20a2 && pid == 0x0001) return "Scanmatik SM2 Pro";
        if (vid == 0x0403) return "FTDI USB Serial";
        if (vid == 0x1a86 && pid == 0x5512) return "CH341A Programmer";
        if (vid == 0x1a86) return "WCH CH34x Serial";
        if (vid == 0x067b) return "Prolific PL2303";
        if (vid == 0x10c4) return "Silicon Labs CP210x";
        return String.format("USB %04x:%04x", vid, pid);
    }

    static Proto.DeviceInfo describe(UsbDevice d, byte[] raw) {
        Proto.DeviceInfo i = new Proto.DeviceInfo();
        int[] bd = busDev(d);
        i.busnum = bd[0];
        i.devnum = bd[1];
        i.busid = bd[0] + "-" + bd[1];
        i.path = "/sys/devices/platform/android-usb/usb" + bd[0] + "/" + i.busid;
        i.idVendor = d.getVendorId();
        i.idProduct = d.getProductId();
        i.bDeviceClass = d.getDeviceClass();
        i.bDeviceSubClass = d.getDeviceSubclass();
        i.bDeviceProtocol = d.getDeviceProtocol();
        i.bNumConfigurations = Math.max(1, d.getConfigurationCount());
        i.bcdDevice = parseVersion(d.getVersion());
        if (raw != null && raw.length >= 18 && raw[1] == 1) {
            i.bcdDevice = (raw[12] & 0xff) | ((raw[13] & 0xff) << 8);
        }
        Set<Integer> seen = new HashSet<>();
        if (d.getConfigurationCount() > 0) {
            UsbConfiguration c = d.getConfiguration(0);
            i.bConfigurationValue = c.getId();
            for (int k = 0; k < c.getInterfaceCount(); k++) {
                UsbInterface itf = c.getInterface(k);
                if (seen.add(itf.getId()))
                    i.interfaces.add(new int[]{itf.getInterfaceClass(), itf.getInterfaceSubclass(), itf.getInterfaceProtocol()});
            }
        } else {
            for (int k = 0; k < d.getInterfaceCount(); k++) {
                UsbInterface itf = d.getInterface(k);
                if (seen.add(itf.getId()))
                    i.interfaces.add(new int[]{itf.getInterfaceClass(), itf.getInterfaceSubclass(), itf.getInterfaceProtocol()});
            }
        }
        i.speed = guessSpeed(d, bd[0], bd[1]);
        try { i.manufacturer = nz(d.getManufacturerName()); } catch (SecurityException ignored) {}
        i.product = nameOf(d);
        return i;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    static int parseVersion(String v) {
        // UsbDevice.getVersion() returns e.g. "2.00" for bcdDevice 0x0200
        try {
            String[] p = v.split("\\.");
            String minor = p.length > 1 ? p[1] : "00";
            while (minor.length() < 2) minor = "0" + minor;
            return Integer.parseInt(p[0] + minor.substring(0, 2), 16);
        } catch (Exception e) {
            return 0x0100;
        }
    }

    static int guessSpeed(UsbDevice d, int bus, int dev) {
        // 1) sysfs (readable on many phones, blocked by SELinux on others)
        try {
            File[] dirs = new File("/sys/bus/usb/devices").listFiles();
            if (dirs != null) {
                for (File f : dirs) {
                    if (readInt(new File(f, "busnum")) == bus && readInt(new File(f, "devnum")) == dev) {
                        String s = readLine(new File(f, "speed"));
                        if (s != null) {
                            s = s.trim();
                            if (s.equals("1.5")) return Proto.SPEED_LOW;
                            if (s.equals("12")) return Proto.SPEED_FULL;
                            if (s.equals("480")) return Proto.SPEED_HIGH;
                            if (s.startsWith("5000") || s.startsWith("10000") || s.startsWith("20000")) return Proto.SPEED_SUPER;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        // 2) heuristic from endpoint max packet sizes
        int maxBulk = 0, maxIntr = 0;
        for (int k = 0; k < d.getInterfaceCount(); k++) {
            UsbInterface itf = d.getInterface(k);
            for (int e = 0; e < itf.getEndpointCount(); e++) {
                UsbEndpoint ep = itf.getEndpoint(e);
                if (ep.getType() == UsbConstantsCompat.XFER_BULK) maxBulk = Math.max(maxBulk, ep.getMaxPacketSize());
                if (ep.getType() == UsbConstantsCompat.XFER_INT) maxIntr = Math.max(maxIntr, ep.getMaxPacketSize());
            }
        }
        if (maxBulk >= 1024) return Proto.SPEED_SUPER;
        if (maxBulk == 512 || maxIntr > 64) return Proto.SPEED_HIGH;
        return Proto.SPEED_FULL;
    }

    private static int readInt(File f) {
        String s = readLine(f);
        try { return s == null ? -1 : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    private static String readLine(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    static final class UsbConstantsCompat {
        static final int XFER_CONTROL = 0, XFER_ISOC = 1, XFER_BULK = 2, XFER_INT = 3;
    }
}
