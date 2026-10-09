package com.typegsmart.aio;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** Wake-on-LAN: magic packet عبر UDP broadcast (نفس أسلوب النسخة الأصلية). */
public final class Wol {
    private Wol() {}

    public static void send(String mac, String broadcast, int port) throws Exception {
        byte[] m = parseMac(mac);
        byte[] pkt = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xFF;
        for (int i = 6; i < pkt.length; i += 6) System.arraycopy(m, 0, pkt, i, 6);
        InetAddress addr = InetAddress.getByName(broadcast == null ? "255.255.255.255" : broadcast);
        DatagramSocket s = new DatagramSocket();
        try {
            s.setBroadcast(true);
            s.send(new DatagramPacket(pkt, pkt.length, addr, port <= 0 ? 9 : port));
        } finally { s.close(); }
    }

    private static byte[] parseMac(String mac) {
        String h = mac.replaceAll("[^0-9a-fA-F]", "");
        byte[] b = new byte[6];
        for (int i = 0; i < 6; i++) b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
