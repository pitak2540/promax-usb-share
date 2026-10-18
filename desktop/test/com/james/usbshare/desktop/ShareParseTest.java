package com.james.usbshare.desktop;
public class ShareParseTest {
    public static void main(String[] a) {
        String out = "Connected:\nBUSID  VID:PID    DEVICE                                                        STATE\n"
            + "1-4    0403:cc4d  OpenPort 2.0 J2534 ISO/CAN/VPW/PWM, USB Serial Converter       Not shared\n"
            + "2-1    1a86:7523  USB-SERIAL CH340 (COM3)                                       Shared\n"
            + "3-2    20a2:0001  Scanmatik 2 PRO                                               Attached\n"
            + "4-1    046d:c52b  USB Input Device, Logitech USB Input Device                   Shared (forced)\n\n"
            + "Persisted:\nGUID                                  DEVICE\n";
        java.util.List<SharePanel.LocalDev> l = SharePanel.parseList(out);
        for (SharePanel.LocalDev d : l) System.out.println(d.busid + " | " + d.vidpid + " | " + d.name + " | " + d.state + " | shared=" + d.shared());
        if (l.size() != 4 || l.get(0).shared() || !l.get(1).shared() || !l.get(2).shared()) { System.out.println("FAIL"); System.exit(1); }
        System.out.println("PASS");
    }
}
