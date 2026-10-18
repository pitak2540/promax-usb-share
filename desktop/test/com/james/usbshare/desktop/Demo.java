package com.james.usbshare.desktop;

import com.james.usbshare.usbip.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.*;

public class Demo {
    public static void main(String[] a) throws Exception {
        ServerTest.FakeBackend fb = new ServerTest.FakeBackend();
        UsbIpServer srv = new UsbIpServer(fb, 3240);
        srv.start();
        App.main(new String[0]);
        Beacon b = new Beacon();
        b.name = "Samsung SM-A546E";
        for (Proto.DeviceInfo d : fb.listDevices()) {
            Beacon.Dev bd = new Beacon.Dev(); bd.busid = d.busid; bd.vid = d.idVendor; bd.pid = d.idProduct; bd.product = "Tactrix OpenPort 2.0"; b.devices.add(bd);
        }
        Beacon.Dev x = new Beacon.Dev(); x.busid = "1-3"; x.vid = 0x1a86; x.pid = 0x7523; x.product = "USB2.0-Serial (CH340)"; x.inUse = true; b.devices.add(x);
        byte[] data = b.encode();
        try (DatagramSocket ds = new DatagramSocket()) {
            for (int i = 0; i < 3; i++) { ds.send(new DatagramPacket(data, data.length, InetAddress.getByName("127.0.0.1"), Beacon.PORT)); Thread.sleep(800); }
        }
        Thread.sleep(2500);
        Rectangle r = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage img = new Robot().createScreenCapture(r);
        javax.imageio.ImageIO.write(img, "png", new File(a.length > 0 ? a[0] : "shot.png"));
        System.exit(0);
    }
}
