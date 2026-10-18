package com.james.usbshare.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.james.usbshare.relay.Relay;
import com.james.usbshare.usbip.Beacon;
import com.james.usbshare.usbip.Proto;
import com.james.usbshare.usbip.UsbIpServer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Foreground service that runs the USB/IP server and the discovery beacon. */
public class UsbShareService extends Service {

    private static final String CHANNEL = "usbshare";
    private static final int NOTIF_ID = 1;

    private static volatile UsbShareService instance;

    private AndroidUsbBackend backend;
    private UsbIpServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private volatile boolean beaconRunning;
    private Thread beaconThread;
    private volatile Relay.Phone relay;

    // ---------- relay settings (SharedPreferences) ----------
    public static final String PREFS = "relay";

    public static String relayCode(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        String code = sp.getString("code", null);
        if (code == null) {
            code = Relay.newCode();
            sp.edit().putString("code", code).apply();
        }
        return code;
    }

    public static String relayUrl(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getString("url", Relay.DEFAULT_URL);
    }

    public static boolean relayEnabled(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("enabled", true);
    }

    public Relay.Phone relay() { return relay; }

    /** Applies the saved relay settings (call after changing them). */
    private android.net.ConnectivityManager.NetworkCallback netCallback;
    private volatile long lastNetChange;

    /** Wi-Fi <-> mobile data switch: the old relay link is dead, reconnect right away. */
    private void onNetworkChanged() {
        long now = System.currentTimeMillis();
        if (now - lastNetChange < 2000) return;
        lastNetChange = now;
        Relay.Phone r = relay;
        if (r != null) {
            LogBus.add("เครือข่ายเปลี่ยน — ต่อ Relay ใหม่");
            r.reconnect();
        }
        updateNotification();
    }

    public synchronized void applyRelay() {
        if (relay != null) { relay.stop(); relay = null; }
        if (relayEnabled(this) && server != null && server.isRunning()) {
            relay = new Relay.Phone(relayUrl(this), relayCode(this), Build.MANUFACTURER + " " + Build.MODEL, Proto.PORT, LogBus::add)
                    .credentials(LicenseManager.savedKey(this), LicenseManager.deviceHash(this));
            relay.start();
        }
    }

    public static UsbShareService get() { return instance; }
    public UsbIpServer server() { return server; }
    public AndroidUsbBackend backend() { return backend; }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (d == null) return;
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) {
                String busid = AndroidUsbBackend.busidOf(d);
                LogBus.add("ถอดอุปกรณ์ " + busid + " " + AndroidUsbBackend.nameOf(d));
                backend.onDetached(d);
                if (server != null) server.kick(busid);
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) {
                LogBus.add("เสียบอุปกรณ์ " + AndroidUsbBackend.busidOf(d) + " " + AndroidUsbBackend.nameOf(d));
            }
            updateNotification();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        backend = new AndroidUsbBackend(this);
        com.james.usbshare.license.License.Status lic = LicenseManager.status(this);
        if (!lic.allowed()) {
            // second line of defence: the service itself refuses to run without a license
            LogBus.add(com.james.usbshare.license.License.describe(lic));
            createChannel();
            Notification n0 = buildNotification(com.james.usbshare.license.License.describe(lic));
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n0, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIF_ID, n0);
            stopSelf();
            return;
        }
        createChannel();
        Notification n = buildNotification("กำลังเริ่ม...");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, n);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UsbShare:server");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        wifiLock = wm.createWifiLock(mode, "UsbShare:wifi");
        wifiLock.acquire();

        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        netCallback = new android.net.ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(android.net.Network n) { onNetworkChanged(); }
            @Override public void onLost(android.net.Network n) { onNetworkChanged(); }
        };
        try { cm.registerDefaultNetworkCallback(netCallback); } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(usbReceiver, f);

        server = new UsbIpServer(backend, Proto.PORT);
        server.setListener(ids -> updateNotification());
        try {
            server.start();
        } catch (Exception e) {
            LogBus.add("เปิดพอร์ต " + Proto.PORT + " ไม่ได้: " + e.getMessage());
        }
        startBeacon();
        applyRelay();
        updateNotification();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        beaconRunning = false;
        if (relay != null) relay.stop();
        if (beaconThread != null) beaconThread.interrupt();
        if (server != null) server.stop();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        try { ((android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        instance = null;
        LogBus.add("หยุดการแชร์แล้ว");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ---------- notification ----------

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "USB Share", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_usb)
                .setContentTitle("PROMAX USB Share")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    void updateNotification() {
        if (server == null) return;
        List<String> ids = server.attachedBusids();
        String addr = String.join(", ", localIps());
        String text = ids.isEmpty()
                ? "รอการเชื่อมต่อที่ " + (addr.isEmpty() ? "(ไม่มี Wi-Fi)" : addr) + ":" + Proto.PORT
                : "PC กำลังใช้: " + String.join(", ", ids);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotification(text));
    }

    // ---------- network ----------

    public static List<String> localIps() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && (a.isSiteLocalAddress() || isTailscale(a))) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Tailscale gives each device an address in 100.64.0.0/10. */
    public static boolean isTailscale(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
    }

    public static String tailscaleIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) continue;
                // Tailscale runs as a VPN (tun interface); mobile carriers also use 100.64/10, so check the interface
                String n = ni.getName() == null ? "" : ni.getName().toLowerCase();
                if (!n.startsWith("tun") && !n.contains("tailscale")) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address && isTailscale(ia.getAddress())) return ia.getAddress().getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null && ia.getAddress() instanceof Inet4Address) out.add(ia.getBroadcast());
                }
            }
            out.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {}
        return out;
    }

    private void startBeacon() {
        beaconRunning = true;
        beaconThread = new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                while (beaconRunning) {
                    Beacon b = new Beacon();
                    b.name = Build.MANUFACTURER + " " + Build.MODEL;
                    b.port = Proto.PORT;
                    List<String> busy = server.attachedBusids();
                    for (Proto.DeviceInfo d : backend.listDevices()) {
                        Beacon.Dev bd = new Beacon.Dev();
                        bd.busid = d.busid;
                        bd.vid = d.idVendor;
                        bd.pid = d.idProduct;
                        bd.inUse = busy.contains(d.busid);
                        bd.manufacturer = d.manufacturer;
                        bd.product = d.product;
                        b.devices.add(bd);
                    }
                    byte[] data = b.encode();
                    for (InetAddress a : broadcastAddresses()) {
                        try { ds.send(new DatagramPacket(data, data.length, a, Beacon.PORT)); } catch (Exception ignored) {}
                    }
                    Thread.sleep(2000);
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                LogBus.add("beacon error: " + e.getMessage());
            }
        }, "beacon");
        beaconThread.setDaemon(true);
        beaconThread.start();
    }
}
