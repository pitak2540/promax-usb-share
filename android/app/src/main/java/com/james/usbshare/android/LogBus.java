package com.james.usbshare.android;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Small in-memory log shown on the main screen. */
public final class LogBus {
    private LogBus() {}

    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final int MAX = 300;
    private static volatile long version;

    public static void add(String msg) {
        Log.i("UsbShare", msg);
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (lines) {
            lines.addFirst(t + "  " + msg);
            while (lines.size() > MAX) lines.removeLast();
            version++;
        }
    }

    public static long version() { return version; }

    public static String text() {
        StringBuilder sb = new StringBuilder();
        synchronized (lines) {
            for (String l : lines) sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
