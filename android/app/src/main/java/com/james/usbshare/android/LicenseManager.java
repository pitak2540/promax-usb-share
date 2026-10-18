package com.james.usbshare.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import com.james.usbshare.license.License;

/** Stores the license key and trial start on the phone and evaluates them. */
public final class LicenseManager {
    private LicenseManager() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("license", Context.MODE_PRIVATE);
    }

    public static byte[] deviceHash(Context c) {
        String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        return License.deviceHash("android:" + (id == null ? "unknown" : id));
    }

    public static String deviceId(Context c) { return License.formatDeviceId(deviceHash(c)); }

    /** SHA-256 of the certificate this APK must be signed with (a repackaged APK has another one). */
    private static final String CERT_SHA256 = "952beb7adab0404f8ed1841db84fcf8189e78d62fc4a6fd978dd4cccf61d7dda";

    static boolean genuine(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(),
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            android.content.pm.Signature[] sigs = pi.signingInfo.hasMultipleSigners()
                    ? pi.signingInfo.getApkContentsSigners() : pi.signingInfo.getSigningCertificateHistory();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (android.content.pm.Signature s : sigs) {
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest(s.toByteArray())) sb.append(String.format("%02x", b & 0xff));
                if (sb.toString().equals(CERT_SHA256)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static String savedKey(Context c) { return prefs(c).getString("key", null); }

    public static License.Status status(Context c) {
        if (!genuine(c)) {
            License.Status t = new License.Status();
            t.kind = License.Kind.TAMPERED;
            return t;
        }
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        long first = p.getLong("firstRun", 0);
        long maxSeen = p.getLong("maxSeen", 0);
        SharedPreferences.Editor e = p.edit();
        if (first == 0) { first = now; e.putLong("firstRun", now); }
        if (now > maxSeen) e.putLong("maxSeen", now);
        e.apply();
        return License.evaluate(p.getString("key", null), deviceHash(c), first, maxSeen, now);
    }

    /** Saves the key if it is genuine and made for this phone; returns the resulting status. */
    public static License.Status saveKey(Context c, String key) {
        License.Key k = License.parseKey(key);
        if (k != null && k.matches(deviceHash(c))) {
            prefs(c).edit().putString("key", key.trim()).apply();
            return status(c);
        }
        License.Status s = new License.Status();
        s.kind = k == null ? License.Kind.INVALID : License.Kind.WRONG_DEVICE;
        return s;
    }
}
