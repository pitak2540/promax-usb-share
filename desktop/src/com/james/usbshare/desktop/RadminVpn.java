package com.james.usbshare.desktop;

import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Radmin VPN support. Radmin VPN builds a virtual LAN: every member PC gets an address in
 * 26.0.0.0/8 and the virtual adapter carries broadcasts, so the normal LAN auto-discovery
 * (the UDP beacon on {@link com.james.usbshare.usbip.Beacon#PORT}) already reaches peers on it.
 *
 * This helper only adds the convenience pieces: find this PC's Radmin address so it can be shown
 * and typed on the other machine, and recognise a 26.x address so such a connection is tagged and
 * treated as cross-network (higher latency than a real LAN). Radmin VPN is Windows-only, so this is
 * a computer-to-computer path; phones use Wi-Fi, the pairing code (relay) or Tailscale instead.
 */
public final class RadminVpn {
    private RadminVpn() {}

    public static final String DOWNLOAD_URL = "https://www.radmin-vpn.com/";

    /** Radmin VPN hands out addresses in 26.0.0.0/8. */
    public static boolean isRadminIp(String ip) {
        if (ip == null) return false;
        try {
            String[] p = ip.split("\\.");
            return p.length == 4 && Integer.parseInt(p[0]) == 26;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * This PC's Radmin VPN address, or null when Radmin VPN is not installed / not connected.
     * Prefers an adapter whose name mentions Radmin; otherwise any up adapter holding a 26.x address.
     */
    public static String localIp() {
        String byName = null, byRange = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String tag = ((ni.getDisplayName() == null ? "" : ni.getDisplayName()) + " " + ni.getName()).toLowerCase();
                boolean named = tag.contains("radmin");
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof Inet4Address)) continue;
                    String ip = ia.getAddress().getHostAddress();
                    if (named && byName == null) byName = ip;
                    if (isRadminIp(ip) && byRange == null) byRange = ip;
                }
            }
        } catch (Exception ignored) {}
        return byName != null ? byName : byRange;
    }

    /** True when a Radmin VPN adapter with an address is present on this PC. */
    public static boolean installed() {
        return localIp() != null;
    }
}
