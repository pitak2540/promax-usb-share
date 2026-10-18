package com.james.usbshare.desktop;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.swing.*;
public class TabsDemo {
    static JTabbedPane find(Container c) {
        for (Component k : c.getComponents()) {
            if (k instanceof JTabbedPane) return (JTabbedPane) k;
            if (k instanceof Container) { JTabbedPane t = find((Container) k); if (t != null) return t; }
        }
        return null;
    }
    public static void main(String[] a) throws Exception {
        App.main(new String[0]);
        Thread.sleep(3000);
        Robot r = new Robot();
        Rectangle scr = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        javax.imageio.ImageIO.write(r.createScreenCapture(scr), "png", new File(a[0] + "1.png"));
        SwingUtilities.invokeAndWait(() -> { for (Frame f : Frame.getFrames()) { JTabbedPane t = find(((JFrame) f).getContentPane()); if (t != null) t.setSelectedIndex(1); } });
        Thread.sleep(1500);
        javax.imageio.ImageIO.write(r.createScreenCapture(scr), "png", new File(a[0] + "2.png"));
        System.exit(0);
    }
}
