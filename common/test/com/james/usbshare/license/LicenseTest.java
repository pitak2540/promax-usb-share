package com.james.usbshare.license;

import com.james.usbshare.keygen.Signer;
import java.nio.file.Path;

public class LicenseTest {
    static int fail;
    static void check(boolean ok, String w) { System.out.println((ok ? "PASS " : "FAIL ") + w); if (!ok) fail++; }
    public static void main(String[] a) throws Exception {
        Signer signer = new Signer(Path.of(a[0]));
        byte[] dev = License.deviceHash("android-id-123456");
        String id = License.formatDeviceId(dev);
        System.out.println("device id: " + id);
        check(java.util.Arrays.equals(License.parseDeviceId(id), dev), "device id round trip");
        check(java.util.Arrays.equals(License.parseDeviceId(id.toLowerCase().replace("-", " ")), dev), "device id typed loosely");
        long now = System.currentTimeMillis();
        String life = signer.issue(dev, 0);
        System.out.println("lifetime key: " + life + "  (" + life.length() + " chars)");
        License.Status st = License.evaluate(life, dev, now - 30L * 86400000L, now, now);
        check(st.kind == License.Kind.LICENSED && st.daysLeft == -1, "lifetime key accepted after trial");
        check(License.evaluate(life, License.deviceHash("other"), now - 30L * 86400000L, now, now).kind == License.Kind.WRONG_DEVICE, "key on another device rejected");
        int in30 = License.dayOf(now) + 30;
        String k30 = signer.issue(dev, in30);
        License.Status s30 = License.evaluate(k30, dev, now - 30L * 86400000L, now, now);
        check(s30.kind == License.Kind.LICENSED && s30.daysLeft >= 29 && s30.daysLeft <= 30, "30-day key: " + License.describe(s30));
        check(License.evaluate(k30, dev, now - 90L * 86400000L, now + 40L * 86400000L, now + 40L * 86400000L).kind == License.Kind.EXPIRED, "30-day key expires");
        char[] c = life.toCharArray(); int pos = 20; c[pos] = c[pos] == 'A' ? 'B' : 'A';
        check(License.evaluate(new String(c), dev, now - 30L * 86400000L, now, now).kind == License.Kind.INVALID, "tampered key rejected");
        check(License.evaluate(null, dev, now - 2L * 86400000L, now, now).kind == License.Kind.TRIAL, "trial day 3 allowed: " + License.describe(License.evaluate(null, dev, now - 2L * 86400000L, now, now)));
        check(License.evaluate(null, dev, now - 8L * 86400000L, now, now).kind == License.Kind.TRIAL_OVER, "trial over after 7 days");
        check(License.evaluate(null, dev, now - 8L * 86400000L, now + 5L * 86400000L, now).kind == License.Kind.CLOCK, "clock turned back detected");
        check(License.evaluate("JUS1-AAAAA", dev, now, now, now).kind == License.Kind.TRIAL, "garbage key during trial keeps trial");
        // v2: one key for 1 PC + 1 phone
        byte[] pc = License.deviceHash("windows:guid-1"), ph = License.deviceHash("android:aid-1");
        String both = signer.issue(pc, ph, 0);
        System.out.println("pc+phone key: " + both + " (" + both.length() + " chars)");
        check(License.evaluate(both, pc, now - 30L * 86400000L, now, now).kind == License.Kind.LICENSED, "pc+phone key works on the PC");
        check(License.evaluate(both, ph, now - 30L * 86400000L, now, now).kind == License.Kind.LICENSED, "pc+phone key works on the phone");
        check(License.evaluate(both, License.deviceHash("android:aid-2"), now - 30L * 86400000L, now, now).kind == License.Kind.WRONG_DEVICE, "pc+phone key refused on a 2nd phone");
        String pcOnly = signer.issue(pc, null, License.dayOf(now) + 10);
        check(License.parseKey(pcOnly).deviceHash2 == null && License.evaluate(pcOnly, pc, now, now, now).kind == License.Kind.LICENSED, "pc-only v2 key");
        System.out.println(fail == 0 ? "ALL PASSED" : fail + " FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }
}
