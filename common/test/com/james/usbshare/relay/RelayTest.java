package com.james.usbshare.relay;

import com.james.usbshare.usbip.Proto;
import com.james.usbshare.usbip.ServerTest;
import com.james.usbshare.usbip.UsbIpServer;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/** End-to-end: fake device -> USB/IP server -> Relay.Phone -> relay -> Relay.Forwarder -> client. */
public class RelayTest {
    static int failures;
    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] a) throws Exception {
        String relay = a.length > 0 ? a[0] : "http://127.0.0.1:8787";
        String code = Relay.newCode();

        Relay.Status st0 = Relay.status(relay, code);
        check(st0.reachable && !st0.online, "status before phone: reachable, offline");

        // PC forwarder while phone is offline -> connection should be refused/closed quickly
        byte[] pcDev = com.james.usbshare.license.License.deviceHash("test-pc-" + code);
        byte[] phoneDev = com.james.usbshare.license.License.deviceHash("test-phone-" + code);
        Relay.Forwarder fwd = new Relay.Forwarder(relay, code, m -> System.out.println("  [pc] " + m)).credentials(null, pcDev);
        fwd.start(3250);
        int port = fwd.port();
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(Proto.encodeOpHeader(Proto.OP_REQ_DEVLIST, 0));
            int r = s.getInputStream().read();
            check(r == -1, "offline phone -> local connection closed");
        }

        ServerTest.FakeBackend fb = new ServerTest.FakeBackend();
        UsbIpServer srv = new UsbIpServer(fb, 0);
        srv.start();
        // a forged key must be refused by the relay itself
        Relay.Phone forged = new Relay.Phone(relay, code, "Forged", srv.getPort(), m -> System.out.println("  [forged] " + m))
                .credentials("JUS1-AFBV4-Y5WMZ-HQWHJ-AAAAU-BABYY-QF5NU-YFF5M-NX452-DY5GH-7WK97-XUXGM-L826H-47W77-ZRSVW-WKWX9-6XAJR-SMTNJ-Y62BJ-PCFFZ-USG64-E5NE5-UKAHY-3BJ4C-RF55P-8XLT", phoneDev);
        forged.start();
        for (int i = 0; i < 50 && forged.lastError().isEmpty(); i++) Thread.sleep(100);
        check(!forged.isConnected() && !forged.lastError().isEmpty(), "relay refuses a forged key: " + forged.lastError());
        forged.stop();
        Relay.Phone phone = new Relay.Phone(relay, code, "Test Phone ทดสอบ", srv.getPort(), m -> System.out.println("  [phone] " + m)).credentials(null, phoneDev);
        phone.start();
        for (int i = 0; i < 50 && !phone.isConnected(); i++) Thread.sleep(100);
        check(phone.isConnected(), "phone control connected");
        Relay.Status st = Relay.status(relay, code);
        check(st.online && st.name.startsWith("Test Phone"), "status online with name: " + st.name);

        long t0 = System.nanoTime();
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10000);
            s.getOutputStream().write(Proto.encodeOpHeader(Proto.OP_REQ_DEVLIST, 0));
            List<Proto.DeviceInfo> list = Proto.readDevList(new DataInputStream(s.getInputStream()));
            check(list.size() == 1 && list.get(0).idProduct == 0xcc4c, "DEVLIST through relay");
        }
        System.out.println("  devlist round trip ms: " + (System.nanoTime() - t0) / 1_000_000);

        // network change on the phone: drop the control link, it must come back and serve again
        phone.reconnect();
        Thread.sleep(300);
        for (int i = 0; i < 80 && !phone.isConnected(); i++) Thread.sleep(100);
        Thread.sleep(500);
        try (Socket s2 = new Socket("127.0.0.1", port)) {
            s2.setSoTimeout(10000);
            s2.getOutputStream().write(Proto.encodeOpHeader(Proto.OP_REQ_DEVLIST, 0));
            check(Proto.readDevList(new DataInputStream(s2.getInputStream())).size() == 1, "DEVLIST after phone reconnect");
        }

        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(10000);
        OutputStream out = s.getOutputStream();
        DataInputStream in = new DataInputStream(s.getInputStream());
        out.write(Proto.encodeImportRequest("1-7"));
        byte[] rep = new byte[320];
        in.readFully(rep);
        check(ByteBuffer.wrap(rep).getInt(4) == 0, "IMPORT through relay");

        int devid = 0x10007;
        byte[] setup = {(byte) 0x80, 6, 0, 1, 0, 0, 18, 0};
        out.write(Proto.encodeCmdSubmit(1, devid, Proto.DIR_IN, 0, 0x200, 18, setup, null));
        byte[] h = new byte[48];
        in.readFully(h);
        ByteBuffer hb = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN);
        int actual = hb.getInt(0x18);
        byte[] desc = new byte[actual];
        in.readFully(desc);
        check(hb.getInt(0) == Proto.RET_SUBMIT && actual == 18 && desc[0] == 18, "control transfer through relay");

        // the phone's control link reconnects while a device is in use: the session must survive
        phone.reconnect();
        for (int i = 0; i < 30; i++) { Thread.sleep(100); }
        for (int i = 0; i < 80 && !phone.isConnected(); i++) Thread.sleep(100);
        Thread.sleep(800);
        out.write(Proto.encodeCmdSubmit(9, devid, Proto.DIR_IN, 0, 0x200, 18, setup, null));
        in.readFully(h);
        hb = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN);
        in.readFully(new byte[hb.getInt(0x18)]);
        check(hb.getInt(4) == 9 && hb.getInt(0x18) == 18, "device session survives phone control reconnect");

        // bulk loopback with a 40 KB payload (crosses frame sizes)
        byte[] big = new byte[40000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 7);
        out.write(Proto.encodeCmdSubmit(2, devid, Proto.DIR_OUT, 1, 0, big.length, null, big));
        in.readFully(h);
        out.write(Proto.encodeCmdSubmit(3, devid, Proto.DIR_IN, 1, 0x200, 65536, null, null));
        in.readFully(h);
        hb = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN);
        byte[] got = new byte[hb.getInt(0x18)];
        in.readFully(got);
        check(Arrays.equals(got, big), "40 KB bulk loopback through relay");

        // latency of small URBs
        long t1 = System.nanoTime();
        int N = 50;
        for (int i = 0; i < N; i++) {
            out.write(Proto.encodeCmdSubmit(10 + i, devid, Proto.DIR_IN, 0, 0x200, 18, setup, null));
            in.readFully(h);
            in.readFully(new byte[ByteBuffer.wrap(h).getInt(0x18)]);
        }
        System.out.println("  avg control URB round trip ms: " + (System.nanoTime() - t1) / 1_000_000.0 / N);

        s.close();
        Thread.sleep(500);
        check(srv.attachedBusids().isEmpty(), "phone side session released after PC disconnect");

        phone.stop();
        Thread.sleep(800);
        check(!Relay.status(relay, code).online, "status offline after phone stops");
        fwd.stop();
        srv.stop();
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
