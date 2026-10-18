package com.james.usbshare.android;

import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbRequest;

import com.james.usbshare.usbip.Backend;
import com.james.usbshare.usbip.Proto;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * One opened USB device. Bulk/interrupt URBs are queued asynchronously with UsbRequest and
 * reaped by a single thread (requestWait), so many URBs can be in flight and CMD_UNLINK can
 * really cancel them. Control transfers run on their own serial thread.
 */
final class AndroidAttached implements Backend.Attached {

    interface Logger { void log(String s); }

    private static final int CONTROL_TIMEOUT_MS = 5000;

    private final UsbDevice device;
    private final UsbDeviceConnection conn;
    private final Proto.DeviceInfo info;
    private final Logger log;
    private final Runnable onClosed;

    private UsbConfiguration config;
    /** Currently claimed interfaces by interface number (current alternate setting). */
    private final Map<Integer, UsbInterface> claimed = new ConcurrentHashMap<>();
    /** Endpoint address (with 0x80 for IN) -> endpoint of the current alt setting. */
    private final Map<Integer, UsbEndpoint> endpoints = new ConcurrentHashMap<>();
    private final Map<Integer, UsbRequest> inflight = new ConcurrentHashMap<>();

    private final ExecutorService control = Executors.newSingleThreadExecutor(r -> new Thread(r, "usb-ctrl"));
    private final Thread reaper;
    private volatile boolean closed;
    private volatile boolean gone;

    private static final class Urb {
        final int seq;
        final boolean in;
        final ByteBuffer buf;
        final Backend.Completion done;
        boolean zlpPending;
        int actual;
        Urb(int seq, boolean in, ByteBuffer buf, Backend.Completion done) {
            this.seq = seq; this.in = in; this.buf = buf; this.done = done;
        }
    }

    AndroidAttached(UsbDevice device, UsbDeviceConnection conn, Proto.DeviceInfo info, Logger log, Runnable onClosed) {
        this.device = device;
        this.conn = conn;
        this.info = info;
        this.log = log;
        this.onClosed = onClosed;
        this.config = device.getConfigurationCount() > 0 ? device.getConfiguration(0) : null;
        claimAll();
        reaper = new Thread(this::reapLoop, "usb-reaper-" + info.busid);
        reaper.setDaemon(true);
        reaper.start();
    }

    @Override public Proto.DeviceInfo info() { return info; }
    @Override public boolean isGone() { return gone || closed; }

    void markGone() {
        gone = true;
        for (UsbRequest r : inflight.values()) {
            try { r.cancel(); } catch (Exception ignored) {}
        }
    }

    // ---------- interfaces ----------

    private List<UsbInterface> interfacesOf(UsbConfiguration c) {
        List<UsbInterface> l = new ArrayList<>();
        if (c != null) {
            for (int i = 0; i < c.getInterfaceCount(); i++) l.add(c.getInterface(i));
        } else {
            for (int i = 0; i < device.getInterfaceCount(); i++) l.add(device.getInterface(i));
        }
        return l;
    }

    private void claimAll() {
        for (UsbInterface itf : interfacesOf(config)) {
            if (itf.getAlternateSetting() != 0 || claimed.containsKey(itf.getId())) continue;
            if (!conn.claimInterface(itf, true)) {
                log.log("claim interface " + itf.getId() + " ไม่สำเร็จ");
                continue;
            }
            claimed.put(itf.getId(), itf);
            addEndpoints(itf);
        }
    }

    private void releaseAll() {
        for (UsbInterface itf : claimed.values()) {
            try { conn.releaseInterface(itf); } catch (Exception ignored) {}
        }
        claimed.clear();
        endpoints.clear();
    }

    private void addEndpoints(UsbInterface itf) {
        for (int e = 0; e < itf.getEndpointCount(); e++) {
            UsbEndpoint ep = itf.getEndpoint(e);
            endpoints.put(ep.getAddress(), ep);
        }
    }

    private void removeEndpoints(UsbInterface itf) {
        for (int e = 0; e < itf.getEndpointCount(); e++) endpoints.remove(itf.getEndpoint(e).getAddress());
    }

    // ---------- submit ----------

    @Override public void submit(Proto.Cmd c, Backend.Completion done) {
        if (closed || gone) { done.done(Proto.ENODEV, null, 0); return; }
        if (c.ep == 0) {
            control.execute(() -> doControl(c, done));
            return;
        }
        boolean in = c.direction == Proto.DIR_IN;
        UsbEndpoint ep = endpoints.get(c.ep | (in ? 0x80 : 0));
        if (ep == null) {
            done.done(Proto.EPIPE, null, 0);
            return;
        }
        if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC || c.isIso()) {
            done.done(Proto.EINVAL, null, 0); // Android API cannot do isochronous transfers
            return;
        }
        int len = c.transferBufferLength;
        ByteBuffer buf;
        if (len == 0) buf = null;
        else if (in) buf = ByteBuffer.allocate(len);
        else buf = ByteBuffer.wrap(c.data, 0, c.data.length);
        Urb u = new Urb(c.seqnum, in, buf, done);
        if (!in && len > 0 && (c.transferFlags & Proto.URB_ZERO_PACKET) != 0
                && ep.getMaxPacketSize() > 0 && len % ep.getMaxPacketSize() == 0) {
            u.zlpPending = true;
        }
        if (!queue(ep, u, buf)) done.done(Proto.EPROTO, null, 0);
    }

    private boolean queue(UsbEndpoint ep, Urb u, ByteBuffer buf) {
        UsbRequest r = new UsbRequest();
        try {
            if (!r.initialize(conn, ep)) { r.close(); return false; }
            r.setClientData(u);
            inflight.put(u.seq, r);
            if (!r.queue(buf)) {
                inflight.remove(u.seq);
                r.close();
                return false;
            }
            return true;
        } catch (Exception e) {
            inflight.remove(u.seq);
            try { r.close(); } catch (Exception ignored) {}
            log.log("queue error: " + e.getMessage());
            return false;
        }
    }

    private void reapLoop() {
        int nullStreak = 0;
        while (!closed) {
            UsbRequest r;
            try {
                r = conn.requestWait(500);
            } catch (TimeoutException e) {
                continue;
            } catch (Exception e) {
                if (closed) break;
                r = null;
            }
            if (r == null) {
                // error from the kernel (device unplugged, or fd closed)
                if (++nullStreak > 20) {
                    gone = true;
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
                continue;
            }
            nullStreak = 0;
            Object cd = r.getClientData();
            if (!(cd instanceof Urb)) { r.close(); continue; }
            Urb u = (Urb) cd;
            UsbEndpoint ep = r.getEndpoint();
            int moved = (u.buf != null) ? u.buf.position() : 0;
            inflight.remove(u.seq, r);
            r.close();
            if (u.zlpPending) {
                u.zlpPending = false;
                u.actual = moved;
                Urb z = new Urb(u.seq, false, null, u.done);
                z.actual = moved;
                if (queue(ep, z, null)) continue;
                u.done.done(0, null, moved);
                continue;
            }
            int actual = (u.buf == null && !u.in) ? u.actual : moved;
            u.done.done(0, u.in ? (u.buf != null ? u.buf.array() : new byte[0]) : null, actual);
        }
    }

    @Override public void cancel(int seqnum) {
        UsbRequest r = inflight.get(seqnum);
        if (r != null) {
            try { r.cancel(); } catch (Exception ignored) {}
        }
    }

    // ---------- control ----------

    private void doControl(Proto.Cmd c, Backend.Completion done) {
        if (closed || gone) { done.done(Proto.ENODEV, null, 0); return; }
        int rt = c.bmRequestType(), req = c.bRequest(), wValue = c.wValue(), wIndex = c.wIndex();
        boolean in = (rt & 0x80) != 0;
        try {
            if (rt == 0x00 && req == 0x09) { // SET_CONFIGURATION
                done.done(setConfiguration(wValue) ? 0 : Proto.EPIPE, null, 0);
                return;
            }
            if (rt == 0x01 && req == 0x0B) { // SET_INTERFACE
                done.done(setInterface(wIndex, wValue) ? 0 : Proto.EPIPE, null, 0);
                return;
            }
            int len = in ? c.transferBufferLength : c.data.length;
            byte[] buf = len > 0 ? (in ? new byte[len] : c.data) : null;
            int n = conn.controlTransfer(rt, req, wValue, wIndex, buf, len, CONTROL_TIMEOUT_MS);
            if (n < 0) {
                done.done(Proto.EPIPE, null, 0);
            } else {
                done.done(0, in ? (buf != null ? buf : new byte[0]) : null, n);
            }
        } catch (Exception e) {
            log.log("control error: " + e.getMessage());
            done.done(Proto.EPROTO, null, 0);
        }
    }

    private boolean setConfiguration(int value) {
        if (value == 0) return true; // un-configure: ignore
        if (config != null && config.getId() == value) return true; // already active (the usual case)
        for (int i = 0; i < device.getConfigurationCount(); i++) {
            UsbConfiguration c = device.getConfiguration(i);
            if (c.getId() == value) {
                releaseAll();
                boolean ok = conn.setConfiguration(c);
                if (ok) config = c;
                claimAll();
                log.log("SET_CONFIGURATION " + value + (ok ? " สำเร็จ" : " ล้มเหลว"));
                return ok;
            }
        }
        return false;
    }

    private boolean setInterface(int ifnum, int alt) {
        UsbInterface target = null;
        int alts = 0;
        for (UsbInterface itf : interfacesOf(config)) {
            if (itf.getId() != ifnum) continue;
            alts++;
            if (itf.getAlternateSetting() == alt) target = itf;
        }
        if (target == null) return false;
        UsbInterface cur = claimed.get(ifnum);
        if (alts == 1 && alt == 0) return true; // nothing to switch; many devices stall SET_INTERFACE here
        boolean ok = conn.setInterface(target);
        if (ok) {
            if (cur != null) removeEndpoints(cur);
            claimed.put(ifnum, target);
            addEndpoints(target);
        }
        return ok;
    }

    // ---------- close ----------

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (UsbRequest r : inflight.values()) {
            try { r.cancel(); } catch (Exception ignored) {}
        }
        control.shutdownNow();
        try { reaper.join(1500); } catch (InterruptedException ignored) {}
        inflight.clear();
        releaseAll();
        try { conn.close(); } catch (Exception ignored) {}
        onClosed.run();
    }
}
