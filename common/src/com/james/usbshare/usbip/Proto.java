package com.james.usbshare.usbip;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * USB/IP protocol v1.1.1 wire format (Linux Documentation/usb/usbip_protocol.rst).
 * All header fields are big-endian. The 8-byte SETUP packet is raw (little-endian fields).
 */
public final class Proto {
    private Proto() {}

    public static final int PORT = 3240;
    public static final int VERSION = 0x0111;

    public static final int OP_REQ_DEVLIST = 0x8005;
    public static final int OP_REP_DEVLIST = 0x0005;
    public static final int OP_REQ_IMPORT = 0x8003;
    public static final int OP_REP_IMPORT = 0x0003;

    public static final int CMD_SUBMIT = 1;
    public static final int CMD_UNLINK = 2;
    public static final int RET_SUBMIT = 3;
    public static final int RET_UNLINK = 4;

    public static final int DIR_OUT = 0;
    public static final int DIR_IN = 1;

    public static final int HEADER_LEN = 48;
    public static final int DEVICE_LEN = 312;

    // Linux enum usb_device_speed
    public static final int SPEED_LOW = 1;
    public static final int SPEED_FULL = 2;
    public static final int SPEED_HIGH = 3;
    public static final int SPEED_SUPER = 5;

    // transfer_flags
    public static final int URB_SHORT_NOT_OK = 0x0001;
    public static final int URB_ZERO_PACKET = 0x0040;

    // negative errno values used in RET_SUBMIT / RET_UNLINK
    public static final int EPIPE = -32;
    public static final int EINVAL = -22;
    public static final int ENODEV = -19;
    public static final int EPROTO = -71;
    public static final int ECONNRESET = -104;
    public static final int ETIMEDOUT = -110;
    public static final int ESHUTDOWN = -108;

    /** Exported device description (usbip_usb_device + interfaces). */
    public static final class DeviceInfo {
        public String path = "";
        public String busid = "";
        public int busnum;
        public int devnum;
        public int speed = SPEED_FULL;
        public int idVendor;
        public int idProduct;
        public int bcdDevice;
        public int bDeviceClass;
        public int bDeviceSubClass;
        public int bDeviceProtocol;
        public int bConfigurationValue = 1;
        public int bNumConfigurations = 1;
        /** Each entry: {class, subclass, protocol}; one per interface (first alt setting). */
        public List<int[]> interfaces = new ArrayList<>();
        /** Optional human-readable strings (not part of the wire format). */
        public String manufacturer = "";
        public String product = "";

        public int devid() { return (busnum << 16) | (devnum & 0xffff); }

        @Override public String toString() {
            return String.format("%s %04x:%04x %s", busid, idVendor, idProduct, product);
        }
    }

    // ---------- encoding helpers ----------

    static void putFixedString(ByteBuffer b, String s, int len) {
        byte[] raw = s.getBytes(StandardCharsets.US_ASCII);
        int n = Math.min(raw.length, len - 1);
        b.put(raw, 0, n);
        for (int i = n; i < len; i++) b.put((byte) 0);
    }

    static String getFixedString(ByteBuffer b, int len) {
        byte[] raw = new byte[len];
        b.get(raw);
        int n = 0;
        while (n < len && raw[n] != 0) n++;
        return new String(raw, 0, n, StandardCharsets.US_ASCII);
    }

    public static void putDevice(ByteBuffer b, DeviceInfo d) {
        putFixedString(b, d.path, 256);
        putFixedString(b, d.busid, 32);
        b.putInt(d.busnum);
        b.putInt(d.devnum);
        b.putInt(d.speed);
        b.putShort((short) d.idVendor);
        b.putShort((short) d.idProduct);
        b.putShort((short) d.bcdDevice);
        b.put((byte) d.bDeviceClass);
        b.put((byte) d.bDeviceSubClass);
        b.put((byte) d.bDeviceProtocol);
        b.put((byte) d.bConfigurationValue);
        b.put((byte) d.bNumConfigurations);
        b.put((byte) d.interfaces.size());
    }

    public static DeviceInfo getDevice(ByteBuffer b) {
        DeviceInfo d = new DeviceInfo();
        d.path = getFixedString(b, 256);
        d.busid = getFixedString(b, 32);
        d.busnum = b.getInt();
        d.devnum = b.getInt();
        d.speed = b.getInt();
        d.idVendor = b.getShort() & 0xffff;
        d.idProduct = b.getShort() & 0xffff;
        d.bcdDevice = b.getShort() & 0xffff;
        d.bDeviceClass = b.get() & 0xff;
        d.bDeviceSubClass = b.get() & 0xff;
        d.bDeviceProtocol = b.get() & 0xff;
        d.bConfigurationValue = b.get() & 0xff;
        d.bNumConfigurations = b.get() & 0xff;
        int nIf = b.get() & 0xff;
        // caller reads interfaces (DEVLIST only); remember the count via placeholders
        for (int i = 0; i < nIf; i++) d.interfaces.add(null);
        return d;
    }

    public static byte[] encodeOpHeader(int code, int status) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        b.putShort((short) VERSION);
        b.putShort((short) code);
        b.putInt(status);
        return b.array();
    }

    public static byte[] encodeDevList(List<DeviceInfo> devs) {
        int size = 12;
        for (DeviceInfo d : devs) size += DEVICE_LEN + 4 * d.interfaces.size();
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        b.putShort((short) VERSION);
        b.putShort((short) OP_REP_DEVLIST);
        b.putInt(0);
        b.putInt(devs.size());
        for (DeviceInfo d : devs) {
            putDevice(b, d);
            for (int[] itf : d.interfaces) {
                b.put((byte) itf[0]);
                b.put((byte) itf[1]);
                b.put((byte) itf[2]);
                b.put((byte) 0);
            }
        }
        return b.array();
    }

    public static byte[] encodeImportReply(DeviceInfo d) {
        if (d == null) return encodeOpHeader(OP_REP_IMPORT, 1);
        ByteBuffer b = ByteBuffer.allocate(8 + DEVICE_LEN).order(ByteOrder.BIG_ENDIAN);
        b.putShort((short) VERSION);
        b.putShort((short) OP_REP_IMPORT);
        b.putInt(0);
        putDevice(b, d);
        return b.array();
    }

    public static byte[] encodeImportRequest(String busid) {
        ByteBuffer b = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN);
        b.putShort((short) VERSION);
        b.putShort((short) OP_REQ_IMPORT);
        b.putInt(0);
        putFixedString(b, busid, 32);
        return b.array();
    }

    /** Reads an OP_REP_DEVLIST body after the client has sent OP_REQ_DEVLIST. */
    public static List<DeviceInfo> readDevList(DataInputStream in) throws IOException {
        byte[] hdr = new byte[12];
        in.readFully(hdr);
        ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN);
        h.getShort();
        int code = h.getShort() & 0xffff;
        int status = h.getInt();
        int n = h.getInt();
        if (code != OP_REP_DEVLIST || status != 0) throw new IOException("bad DEVLIST reply code=" + code + " status=" + status);
        List<DeviceInfo> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byte[] raw = new byte[DEVICE_LEN];
            in.readFully(raw);
            DeviceInfo d = getDevice(ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN));
            int nIf = d.interfaces.size();
            d.interfaces.clear();
            for (int k = 0; k < nIf; k++) {
                int c = in.readUnsignedByte(), s = in.readUnsignedByte(), p = in.readUnsignedByte();
                in.readUnsignedByte();
                d.interfaces.add(new int[]{c, s, p});
            }
            out.add(d);
        }
        return out;
    }

    // ---------- URB phase ----------

    /** Parsed USBIP_CMD_SUBMIT / CMD_UNLINK. */
    public static final class Cmd {
        public int command;
        public int seqnum;
        public int devid;
        public int direction;
        public int ep;
        // SUBMIT
        public int transferFlags;
        public int transferBufferLength;
        public int startFrame;
        public int numberOfPackets;
        public int interval;
        public final byte[] setup = new byte[8];
        public byte[] data; // OUT payload (may be empty)
        // UNLINK
        public int unlinkSeqnum;

        public boolean isIso() { return numberOfPackets != -1 && numberOfPackets != 0; }
        public int bmRequestType() { return setup[0] & 0xff; }
        public int bRequest() { return setup[1] & 0xff; }
        public int wValue() { return (setup[2] & 0xff) | ((setup[3] & 0xff) << 8); }
        public int wIndex() { return (setup[4] & 0xff) | ((setup[5] & 0xff) << 8); }
        public int wLength() { return (setup[6] & 0xff) | ((setup[7] & 0xff) << 8); }
    }

    /** Reads one URB-phase command. Returns null on clean EOF. */
    public static Cmd readCmd(DataInputStream in) throws IOException {
        byte[] hdr = new byte[HEADER_LEN];
        try {
            in.readFully(hdr, 0, 4);
        } catch (java.io.EOFException e) {
            return null;
        }
        in.readFully(hdr, 4, HEADER_LEN - 4);
        ByteBuffer b = ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN);
        Cmd c = new Cmd();
        c.command = b.getInt();
        c.seqnum = b.getInt();
        c.devid = b.getInt();
        c.direction = b.getInt();
        c.ep = b.getInt();
        if (c.command == CMD_SUBMIT) {
            c.transferFlags = b.getInt();
            c.transferBufferLength = b.getInt();
            c.startFrame = b.getInt();
            c.numberOfPackets = b.getInt();
            c.interval = b.getInt();
            b.get(c.setup);
            if (c.transferBufferLength < 0 || c.transferBufferLength > 64 * 1024 * 1024)
                throw new IOException("bad transfer_buffer_length " + c.transferBufferLength);
            if (c.direction == DIR_OUT && c.transferBufferLength > 0) {
                c.data = new byte[c.transferBufferLength];
                in.readFully(c.data);
            } else {
                c.data = new byte[0];
            }
            if (c.isIso() && c.numberOfPackets > 0 && c.numberOfPackets < 4096) {
                in.readFully(new byte[16 * c.numberOfPackets]);
            }
        } else if (c.command == CMD_UNLINK) {
            c.unlinkSeqnum = b.getInt();
        } else {
            throw new IOException("unknown USB/IP command " + c.command);
        }
        return c;
    }

    public static byte[] encodeRetSubmit(int seqnum, int status, byte[] inData, int actualLength, boolean iso) {
        int payload = (inData != null) ? actualLength : 0;
        ByteBuffer b = ByteBuffer.allocate(HEADER_LEN + payload).order(ByteOrder.BIG_ENDIAN);
        b.putInt(RET_SUBMIT);
        b.putInt(seqnum);
        b.putInt(0);
        b.putInt(0);
        b.putInt(0);
        b.putInt(status);
        b.putInt(actualLength);
        b.putInt(0);
        b.putInt(iso ? 0 : -1);
        b.putInt(0);
        b.putLong(0);
        if (payload > 0) b.put(inData, 0, payload);
        return b.array();
    }

    public static byte[] encodeRetUnlink(int seqnum, int status) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_LEN).order(ByteOrder.BIG_ENDIAN);
        b.putInt(RET_UNLINK);
        b.putInt(seqnum);
        b.putInt(0);
        b.putInt(0);
        b.putInt(0);
        b.putInt(status);
        return b.array();
    }

    public static byte[] encodeCmdSubmit(int seqnum, int devid, int dir, int ep, int flags, int len, byte[] setup, byte[] outData) {
        int payload = (dir == DIR_OUT && outData != null) ? outData.length : 0;
        ByteBuffer b = ByteBuffer.allocate(HEADER_LEN + payload).order(ByteOrder.BIG_ENDIAN);
        b.putInt(CMD_SUBMIT);
        b.putInt(seqnum);
        b.putInt(devid);
        b.putInt(dir);
        b.putInt(ep);
        b.putInt(flags);
        b.putInt(len);
        b.putInt(0);
        b.putInt(-1);
        b.putInt(0);
        b.put(setup != null ? setup : new byte[8]);
        if (payload > 0) b.put(outData);
        return b.array();
    }

    public static byte[] encodeCmdUnlink(int seqnum, int devid, int target) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_LEN).order(ByteOrder.BIG_ENDIAN);
        b.putInt(CMD_UNLINK);
        b.putInt(seqnum);
        b.putInt(devid);
        b.putInt(0);
        b.putInt(0);
        b.putInt(target);
        return b.array();
    }

    /** Friendly names for common devices (DEVLIST carries no strings). */
    public static String knownName(int vid, int pid) {
        if (vid == 0x0403 && (pid == 0xcc4c || pid == 0xcc4d)) return "Tactrix OpenPort 2.0";
        if (vid == 0x20a2 && pid == 0x0001) return "Scanmatik SM2 Pro";
        if (vid == 0x0403) return "FTDI USB Serial";
        if (vid == 0x1a86 && pid == 0x5512) return "CH341A Programmer";
        if (vid == 0x1a86) return "WCH CH34x Serial";
        if (vid == 0x067b) return "Prolific PL2303";
        if (vid == 0x10c4) return "Silicon Labs CP210x";
        return String.format("USB %04x:%04x", vid, pid);
    }

    /** J2534 pass-thru cables that are also used to reflash ECUs (warn: no flashing over the network). */
    public static boolean isEcuFlashInterface(int vid, int pid) {
        return (vid == 0x0403 && (pid == 0xcc4c || pid == 0xcc4d)) || (vid == 0x20a2 && pid == 0x0001);
    }

    public static String speedName(int s) {
        switch (s) {
            case SPEED_LOW: return "Low (1.5M)";
            case SPEED_FULL: return "Full (12M)";
            case SPEED_HIGH: return "High (480M)";
            case SPEED_SUPER: return "Super (5G)";
            default: return "?";
        }
    }
}
