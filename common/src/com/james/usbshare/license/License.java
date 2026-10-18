package com.james.usbshare.license;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Offline license keys for PROMAX USB Share.
 *
 * A key is a 13-byte payload signed with ECDSA P-256 by the owner's private key (kept only in
 * the key generator). Apps carry just the public key, so keys cannot be forged from the app.
 *
 * Payload: [0] version=1, [1..8] first 8 bytes of SHA-256 of the device id, [9..10] expiry day
 * (days since 2026-01-01, 0 = lifetime), [11..12] issue day. Signature: raw r||s (64 bytes).
 * Text form: "JUS1-" + Base32 of payload+signature, in groups of 5.
 */
public final class License {
    private License() {}

    static final String PUBLIC_KEY_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFb5E6RdUMGR3Q8UUwlRD1EIR0rM7mBccSo7qRAc56im18zMGHl1BJ3m3q3FtmZfDOIs5n//DOhzYyRvvk/4EWA==";

    public static final int TRIAL_DAYS = 7;
    /** Developer contact (LINE) for buying keys. */
    public static final String CONTACT_URL = "https://line.me/ti/p/qhW3FsG5sq";
    /** Day 0 = 2026-01-01 (UTC). */
    public static final long EPOCH_MS = 1767225600000L;
    static final String PREFIX = "JUS1";
    private static final String B32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no I, O, 0, 1

    // ---------------------------------------------------------------- device id

    /** 8-byte fingerprint of a platform machine id (Android ID, Windows MachineGuid...). */
    public static byte[] deviceHash(String rawMachineId) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(("JUSB-device:" + rawMachineId).getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[8];
            System.arraycopy(d, 0, out, 0, 8);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Human-readable device id, e.g. "K7Q2M-9XAPR-4TD". The customer sends this to the seller. */
    public static String formatDeviceId(byte[] hash8) {
        return group(base32(hash8), 5);
    }

    public static byte[] parseDeviceId(String text) {
        byte[] b = unbase32(clean(text));
        if (b == null || b.length < 8) return null;
        byte[] out = new byte[8];
        System.arraycopy(b, 0, out, 0, 8);
        return out;
    }

    // ---------------------------------------------------------------- key parsing

    public static final class Key {
        public byte[] deviceHash;     // first device (v2: the PC)
        public byte[] deviceHash2;    // v2 only: the phone (null if none)
        /** True if this key was issued for the given device. */
        public boolean matches(byte[] dev) {
            return java.util.Arrays.equals(deviceHash, dev) || (deviceHash2 != null && java.util.Arrays.equals(deviceHash2, dev));
        }
        public int expiryDay;   // 0 = lifetime
        public int issueDay;
        public boolean lifetime() { return expiryDay == 0; }
        public long expiryMs() { return EPOCH_MS + (long) expiryDay * 86400000L + 86400000L - 1; }
    }

    static byte[] payload(byte[] deviceHash, int expiryDay, int issueDay) {
        byte[] p = new byte[13];
        p[0] = 1;
        System.arraycopy(deviceHash, 0, p, 1, 8);
        p[9] = (byte) (expiryDay >>> 8);
        p[10] = (byte) expiryDay;
        p[11] = (byte) (issueDay >>> 8);
        p[12] = (byte) issueDay;
        return p;
    }

    /**
     * v2 payload (one key for 1 PC + 1 phone): [0]=2, [1..8] PC hash, [9..16] phone hash
     * (all zero = none), [17..18] expiry day, [19..20] issue day.
     */
    static byte[] payload2(byte[] pc, byte[] phone, int expiryDay, int issueDay) {
        byte[] p = new byte[21];
        p[0] = 2;
        if (pc != null) System.arraycopy(pc, 0, p, 1, 8);
        if (phone != null) System.arraycopy(phone, 0, p, 9, 8);
        p[17] = (byte) (expiryDay >>> 8);
        p[18] = (byte) expiryDay;
        p[19] = (byte) (issueDay >>> 8);
        p[20] = (byte) issueDay;
        return p;
    }

    public static byte[] buildPayload2(byte[] pc, byte[] phone, int expiryDay, int issueDay) {
        return payload2(pc, phone, expiryDay, issueDay);
    }

    public static String encodeKey(byte[] payload, byte[] rawSig64) {
        byte[] all = new byte[payload.length + 64];
        System.arraycopy(payload, 0, all, 0, payload.length);
        System.arraycopy(rawSig64, 0, all, payload.length, 64);
        return PREFIX + "-" + group(base32(all), 5);
    }

    /** Verifies the signature; returns null for anything that is not a genuine key. */
    public static Key parseKey(String text) {
        try {
            String c = clean(text);
            if (c.startsWith(PREFIX)) c = c.substring(PREFIX.length());
            byte[] all = unbase32(c);
            if (all == null || all.length < 77) return null;
            int plen = all[0] == 1 ? 13 : all[0] == 2 ? 21 : -1;
            if (plen < 0 || all.length < plen + 64) return null;
            byte[] p = new byte[plen];
            byte[] sig = new byte[64];
            System.arraycopy(all, 0, p, 0, plen);
            System.arraycopy(all, plen, sig, 0, 64);
            PublicKey pub = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_B64)));
            Signature v = Signature.getInstance("SHA256withECDSA");
            v.initVerify(pub);
            v.update(p);
            if (!v.verify(rawToDer(sig))) return null;
            Key k = new Key();
            k.deviceHash = java.util.Arrays.copyOfRange(p, 1, 9);
            if (plen == 13) {
                k.expiryDay = ((p[9] & 0xff) << 8) | (p[10] & 0xff);
                k.issueDay = ((p[11] & 0xff) << 8) | (p[12] & 0xff);
            } else {
                byte[] d2 = java.util.Arrays.copyOfRange(p, 9, 17);
                boolean none = true;
                for (byte b : d2) if (b != 0) none = false;
                k.deviceHash2 = none ? null : d2;
                k.expiryDay = ((p[17] & 0xff) << 8) | (p[18] & 0xff);
                k.issueDay = ((p[19] & 0xff) << 8) | (p[20] & 0xff);
            }
            return k;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- state

    public enum Kind { LICENSED, TRIAL, TRIAL_OVER, EXPIRED, WRONG_DEVICE, INVALID, CLOCK, TAMPERED }

    public static final class Status {
        public Kind kind;
        public int daysLeft;      // trial or expiring license
        public Key key;
        public boolean allowed() { return kind == Kind.LICENSED || kind == Kind.TRIAL; }
    }

    /**
     * @param keyText    saved key or null
     * @param device     this device's 8-byte hash
     * @param firstRunMs first time the app ran on this device
     * @param maxSeenMs  latest clock time ever seen (to catch clocks turned back)
     */
    public static Status evaluate(String keyText, byte[] device, long firstRunMs, long maxSeenMs, long nowMs) {
        Status s = new Status();
        if (nowMs < maxSeenMs - 2L * 86400000L) { s.kind = Kind.CLOCK; return s; }
        if (keyText != null && !keyText.trim().isEmpty()) {
            Key k = parseKey(keyText);
            if (k == null) { s.kind = Kind.INVALID; }
            else if (!k.matches(device)) { s.kind = Kind.WRONG_DEVICE; s.key = k; }
            else if (!k.lifetime() && nowMs > k.expiryMs()) { s.kind = Kind.EXPIRED; s.key = k; }
            else {
                s.kind = Kind.LICENSED;
                s.key = k;
                s.daysLeft = k.lifetime() ? -1 : (int) Math.max(0, (k.expiryMs() - nowMs) / 86400000L);
                return s;
            }
            // a bad key falls back to the trial if it is still running
        }
        long used = Math.max(0, nowMs - firstRunMs) / 86400000L;
        int left = (int) (TRIAL_DAYS - used);
        if (left > 0) {
            Kind bad = s.kind;
            s.kind = Kind.TRIAL;
            s.daysLeft = left;
            if (bad != null) s.key = null;
            return s;
        }
        if (s.kind == null) s.kind = Kind.TRIAL_OVER;
        return s;
    }

    public static String describe(Status s) {
        switch (s.kind) {
            case LICENSED: return s.daysLeft < 0 ? "ลงทะเบียนแล้ว (ถาวร)" : "ลงทะเบียนแล้ว — เหลือ " + s.daysLeft + " วัน (ถึง " + dateOf(s.key.expiryDay) + ")";
            case TRIAL: return "ทดลองใช้ฟรี — เหลือ " + s.daysLeft + " วัน";
            case TRIAL_OVER: return "หมดช่วงทดลองใช้ — กรุณาใส่คีย์";
            case EXPIRED: return "คีย์หมดอายุแล้ว (" + dateOf(s.key.expiryDay) + ") — กรุณาต่ออายุ";
            case WRONG_DEVICE: return "คีย์นี้ออกให้เครื่องอื่น";
            case INVALID: return "คีย์ไม่ถูกต้อง";
            case CLOCK: return "วันเวลาของเครื่องไม่ถูกต้อง — ตั้งเวลาให้ตรงก่อน";
            case TAMPERED: return "แอพนี้ถูกดัดแปลง — กรุณาติดตั้งจากผู้ขายเท่านั้น";
            default: return "";
        }
    }

    public static int dayOf(long ms) { return (int) Math.floorDiv(ms - EPOCH_MS, 86400000L); }

    public static String dateOf(int day) {
        java.time.LocalDate d = java.time.LocalDate.of(2026, 1, 1).plusDays(day);
        return String.format("%02d/%02d/%04d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
    }

    // ---------------------------------------------------------------- helpers

    public static String clean(String s) {
        return s == null ? "" : s.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    static String group(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && i % n == 0) sb.append('-');
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte b : data) {
            buf = (buf << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(B32.charAt((buf >>> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(B32.charAt((buf << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] unbase32(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (char ch : s.toCharArray()) {
            int v = B32.indexOf(ch);
            if (v < 0) return null;
            buf = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buf >>> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    /** Raw 64-byte r||s -> ASN.1 DER for java.security.Signature. */
    static byte[] rawToDer(byte[] raw) {
        byte[] r = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 0, 32)).toByteArray();
        byte[] s = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 32, 64)).toByteArray();
        int len = 2 + r.length + 2 + s.length;
        byte[] der = new byte[2 + len];
        int i = 0;
        der[i++] = 0x30;
        der[i++] = (byte) len;
        der[i++] = 0x02;
        der[i++] = (byte) r.length;
        System.arraycopy(r, 0, der, i, r.length);
        i += r.length;
        der[i++] = 0x02;
        der[i++] = (byte) s.length;
        System.arraycopy(s, 0, der, i, s.length);
        return der;
    }

    /** ASN.1 DER -> raw 64-byte r||s (used by the key generator). */
    public static byte[] derToRaw(byte[] der) {
        int i = 2;
        if ((der[1] & 0x80) != 0) i += der[1] & 0x7f;
        int rl = der[i + 1];
        byte[] r = java.util.Arrays.copyOfRange(der, i + 2, i + 2 + rl);
        i = i + 2 + rl;
        int sl = der[i + 1];
        byte[] s = java.util.Arrays.copyOfRange(der, i + 2, i + 2 + sl);
        byte[] out = new byte[64];
        copyFixed(r, out, 0);
        copyFixed(s, out, 32);
        return out;
    }

    private static void copyFixed(byte[] v, byte[] out, int off) {
        int start = Math.max(0, v.length - 32);
        int len = v.length - start;
        System.arraycopy(v, start, out, off + 32 - len, len);
    }

    /** Builds the payload for the key generator. */
    public static byte[] buildPayload(byte[] deviceHash, int expiryDay, int issueDay) {
        return payload(deviceHash, expiryDay, issueDay);
    }
}
