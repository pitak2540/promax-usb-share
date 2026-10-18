package com.james.usbshare.keygen;
import java.awt.*; import java.io.File; import javax.swing.*;
public class KeygenDemo {
    public static void main(String[] a) throws Exception {
        KeygenApp app = new KeygenApp();
        app.prefs.put("keyFile", a[0]);
        SwingUtilities.invokeAndWait(app::show);
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> { app.customer.setText("อู่ทดสอบ"); app.device.setText("EDZNH-UZTT6-26U"); app.phoneDevice.setText("JN8XY-7C735-XB4"); app.duration.setSelectedIndex(4); app.issue(); });
        Thread.sleep(1000);
        Robot r = new Robot();
        javax.imageio.ImageIO.write(r.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())), "png", new File(a[1]));
        System.exit(0);
    }
}
