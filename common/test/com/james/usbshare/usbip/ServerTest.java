package com.james.usbshare.usbip;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** End-to-end protocol test: real TCP server + fake loopback device + hand-written client. */
public class ServerTest {

    static int failures = 0;
    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    /** Fake device: ep0 returns a device descriptor, ep1 OUT stores data, ep1 IN returns it (waits if empty). */
    public static class FakeBackend implements Backend {
        Proto.DeviceInfo info = new Proto.DeviceInfo();
        public FakeBackend() {
            info.busid = "1-7"; info.busnum = 1; info.devnum = 7; info.path = "/sys/devices/fake/1-7";
            info.idVendor = 0x0403; info.idProduct = 0xcc4c; info.bcdDevice = 0x0100; info.speed = Proto.SPEED_FULL;
            info.interfaces.add(new int[]{0xff, 0xff, 0xff});
            info.product = "OpenPort 2.0 (fake)";
        }
        public List<Proto.DeviceInfo> listDevices() { return new ArrayList<>(List.of(info)); }
        public void log(String m) { System.out.println("  [log] " + m); }
        public Attached attach(String busid) throws Exception {
            if (!busid.equals(info.busid)) throw new Exception("no such device");
            return new Attached() {
                final ArrayDeque<byte[]> fifo = new ArrayDeque<>();
                final List<Object[]> waitingIn = new ArrayList<>(); // {seq, Completion}
                public Proto.DeviceInfo info() { return info; }
                public synchronized void submit(Proto.Cmd c, Completion done) {
                    if (c.ep == 0) {
                        byte[] desc = {18, 1, 0, 2, 0, 0, 0, 64, 3, 4, 0x4c, (byte) 0xcc, 0, 1, 1, 2, 3, 1};
                        int n = Math.min(c.wLength(), desc.length);
                        done.done(0, desc, n);
                    } else if (c.direction == Proto.DIR_OUT) {
                        if (!waitingIn.isEmpty()) {
                            Object[] w = waitingIn.remove(0);
                            ((Completion) w[1]).done(0, c.data, c.data.length);
                        } else fifo.add(c.data);
                        done.done(0, null, c.data.length);
                    } else {
                        byte[] d = fifo.poll();
                        if (d != null) done.done(0, d, d.length);
                        else waitingIn.add(new Object[]{c.seqnum, done});
                    }
                }
                public synchronized void cancel(int seq) { waitingIn.removeIf(w -> (int) w[0] == seq); }
                public void close() {}
                public boolean isGone() { return false; }
            };
        }
    }

    static final java.util.Set<Integer> IN_SEQS = new java.util.HashSet<>();
    static int[] readRet(DataInputStream in, ByteBuffer[] payloadOut, int expectLenIfIn) throws Exception {
        byte[] h = new byte[48];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN);
        int cmd = b.getInt(), seq = b.getInt();
        b.getInt(); b.getInt(); b.getInt();
        int status = b.getInt(), actual = b.getInt();
        if (cmd == Proto.RET_SUBMIT && (expectLenIfIn >= 0 || IN_SEQS.contains(seq))) {
            byte[] p = new byte[actual];
            in.readFully(p);
            payloadOut[0] = ByteBuffer.wrap(p);
        }
        return new int[]{cmd, seq, status, actual};
    }

    public static void main(String[] args) throws Exception {
        FakeBackend fb = new FakeBackend();
        UsbIpServer srv = new UsbIpServer(fb, 0);
        srv.start();
        int port = srv.getPort();

        // DEVLIST
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.getOutputStream().write(Proto.encodeOpHeader(Proto.OP_REQ_DEVLIST, 0));
            List<Proto.DeviceInfo> list = Proto.readDevList(new DataInputStream(s.getInputStream()));
            check(list.size() == 1, "devlist has 1 device");
            Proto.DeviceInfo d = list.get(0);
            check(d.busid.equals("1-7") && d.idVendor == 0x0403 && d.idProduct == 0xcc4c, "devlist ids " + d);
            check(d.interfaces.size() == 1 && d.interfaces.get(0)[0] == 0xff, "devlist interface class");
            check(d.speed == Proto.SPEED_FULL && d.devid() == 0x10007, "speed/devid");
        }

        // byte-level check of the DEVLIST encoding size: 12 + 312 + 4
        check(Proto.encodeDevList(fb.listDevices()).length == 328, "devlist byte length 328");
        check(Proto.encodeImportReply(fb.info).length == 320, "import reply length 320");

        // bad import
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.getOutputStream().write(Proto.encodeImportRequest("9-9"));
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] h = new byte[8];
            in.readFully(h);
            check(ByteBuffer.wrap(h).getInt(4) == 1, "import unknown busid -> status 1");
        }

        // IMPORT + URBs
        Socket s = new Socket("127.0.0.1", port);
        OutputStream out = s.getOutputStream();
        DataInputStream in = new DataInputStream(s.getInputStream());
        out.write(Proto.encodeImportRequest("1-7"));
        byte[] rep = new byte[320];
        in.readFully(rep);
        ByteBuffer rb = ByteBuffer.wrap(rep).order(ByteOrder.BIG_ENDIAN);
        check(rb.getShort(2) == Proto.OP_REP_IMPORT && rb.getInt(4) == 0, "import ok");
        rb.position(8);
        Proto.DeviceInfo imp = Proto.getDevice(rb);
        check(imp.busid.equals("1-7") && imp.idProduct == 0xcc4c, "import device struct");

        // second import while attached must fail; devlist must hide it
        try (Socket s2 = new Socket("127.0.0.1", port)) {
            s2.getOutputStream().write(Proto.encodeImportRequest("1-7"));
            byte[] h = new byte[8];
            new DataInputStream(s2.getInputStream()).readFully(h);
            check(ByteBuffer.wrap(h).getInt(4) == 1, "double import refused");
        }
        try (Socket s3 = new Socket("127.0.0.1", port)) {
            s3.getOutputStream().write(Proto.encodeOpHeader(Proto.OP_REQ_DEVLIST, 0));
            check(Proto.readDevList(new DataInputStream(s3.getInputStream())).isEmpty(), "attached device hidden from devlist");
        }

        int devid = 0x10007;
        ByteBuffer[] p = new ByteBuffer[1];

        // control GET_DESCRIPTOR(DEVICE), wLength 18
        byte[] setup = {(byte) 0x80, 6, 0, 1, 0, 0, 18, 0};
        out.write(Proto.encodeCmdSubmit(1, devid, Proto.DIR_IN, 0, 0x200, 18, setup, null));
        int[] r = readRet(in, p, 18);
        check(r[0] == Proto.RET_SUBMIT && r[1] == 1 && r[2] == 0 && r[3] == 18 && p[0].get(0) == 18 && p[0].get(1) == 1, "control IN get descriptor");

        // pending bulk IN (no data yet), then unlink it
        out.write(Proto.encodeCmdSubmit(2, devid, Proto.DIR_IN, 1, 0x200, 64, null, null));
        out.write(Proto.encodeCmdUnlink(3, devid, 2));
        r = readRet(in, p, -1);
        check(r[0] == Proto.RET_UNLINK && r[1] == 3 && r[2] == Proto.ECONNRESET, "unlink pending IN -> -ECONNRESET");

        // bulk OUT then IN
        byte[] payload = "ATZ\r".getBytes();
        out.write(Proto.encodeCmdSubmit(4, devid, Proto.DIR_OUT, 1, 0, payload.length, null, payload));
        r = readRet(in, p, -1);
        check(r[0] == Proto.RET_SUBMIT && r[1] == 4 && r[2] == 0 && r[3] == 4, "bulk OUT actual=4 (no payload echoed)");
        out.write(Proto.encodeCmdSubmit(5, devid, Proto.DIR_IN, 1, 0x200, 64, null, null));
        r = readRet(in, p, 64);
        byte[] got = new byte[p[0].remaining()];
        p[0].get(got);
        check(r[1] == 5 && Arrays.equals(got, payload), "bulk IN returns data (unlinked URB got no stray RET_SUBMIT)");

        // unlink of an already-completed URB -> status 0
        out.write(Proto.encodeCmdUnlink(6, devid, 5));
        r = readRet(in, p, -1);
        check(r[0] == Proto.RET_UNLINK && r[2] == 0, "unlink completed URB -> 0");

        // pipelined: IN waits, OUT completes it
        out.write(Proto.encodeCmdSubmit(7, devid, Proto.DIR_IN, 1, 0x200, 64, null, null));
        out.write(Proto.encodeCmdSubmit(8, devid, Proto.DIR_OUT, 1, 0, 2, null, new byte[]{1, 2}));
        IN_SEQS.add(7);
        int[] a = readRet(in, p, -1);
        int[] bb2 = readRet(in, p, -1);
        check((a[1] == 7 && bb2[1] == 8) || (a[1] == 8 && bb2[1] == 7), "pipelined replies matched by seqnum");

        s.close();
        Thread.sleep(200);
        check(srv.attachedBusids().isEmpty(), "session released after client disconnect");

        // Beacon round trip
        Beacon bc = new Beacon();
        bc.name = "Pixel ทดสอบ";
        Beacon.Dev d = new Beacon.Dev();
        d.busid = "1-7"; d.vid = 0x403; d.pid = 0xcc4c; d.product = "OpenPort|2.0";
        bc.devices.add(d);
        byte[] enc = bc.encode();
        Beacon back = Beacon.parse(enc, enc.length);
        check(back != null && back.name.equals("Pixel ทดสอบ") && back.devices.size() == 1 && back.devices.get(0).pid == 0xcc4c, "beacon round trip");

        srv.stop();
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
