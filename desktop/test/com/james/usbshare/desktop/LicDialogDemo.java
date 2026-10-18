package com.james.usbshare.desktop;
import java.awt.*; import java.io.File; import javax.swing.*;
public class LicDialogDemo {
    public static void main(String[] a) throws Exception {
        App app = new App();
        SwingUtilities.invokeAndWait(app::show);
        Thread.sleep(1500);
        new Thread(() -> { try { Thread.sleep(2500); Robot r = new Robot();
            javax.imageio.ImageIO.write(r.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())), "png", new File(a[0]));
        } catch (Exception e) { e.printStackTrace(); } System.exit(0); }).start();
        SwingUtilities.invokeLater(() -> app.license.showDialog());
    }
}
