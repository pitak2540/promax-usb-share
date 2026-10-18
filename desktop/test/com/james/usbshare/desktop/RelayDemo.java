package com.james.usbshare.desktop;

import com.james.usbshare.relay.Relay;
import com.james.usbshare.usbip.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.prefs.Preferences;

/** GUI check: phone reachable only through the local relay, selected via its pairing code. */
public class RelayDemo {
    public static void main(String[] a) throws Exception {
        String relay = "http://127.0.0.1:8787";
        String code = "TESTCODE";
        ServerTest.FakeBackend fb = new ServerTest.FakeBackend();
        UsbIpServer srv = new UsbIpServer(fb, 0);
        srv.start();
        Relay.Phone ph = new Relay.Phone(relay, code, "samsung SM-A566B", srv.getPort(), m -> {});
        ph.start();
        Thread.sleep(1500);
        Preferences p = Preferences.userNodeForPackage(App.class);
        p.put("relayUrl", relay);
        p.put("relayCodes", code);
        App.main(new String[0]);
        Thread.sleep(3000);
        // select the relay phone (first in list)
        java.awt.EventQueue.invokeAndWait(() -> {
            for (Frame f : Frame.getFrames()) f.toFront();
        });
        Thread.sleep(500);
        Robot r = new Robot();
        r.mouseMove(200, 182); r.mousePress(16); r.mouseRelease(16);
        Thread.sleep(3000);
        BufferedImage img = r.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
        javax.imageio.ImageIO.write(img, "png", new File(a[0]));
        p.remove("relayCodes"); p.remove("relayUrl");
        System.exit(0);
    }
}
