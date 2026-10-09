package com.typegsmart.aio;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * اكتشاف الموبايل بدون IP ثابت — طريقتان، المشترك يستخدم اللي يقدر عليه:
 *
 * 1) UDP Discovery (مسار افتراضي، مناسب لأي فيرموير):
 *    - المشترك يبعث probe (نص "typeg?discover") broadcast على البورت DISCOVERY_PORT.
 *    - الموبايل يرد unicast بـ JSON: {"typegsmart":1,"ip":"<phoneIp>","port":10086}.
 *    - المشترك ياخد الـ ip ويفتح TCP على 10086 زي ما هو في البروتوكول الحالي.
 *    - كمان الموبايل يعمل announce broadcast كل 5 ثواني لو المشترك بيسمع بس.
 *
 * 2) mDNS/NSD: إعلان خدمة "_typeg._tcp" باسم "TypeGSmart" على 10086.
 */
public class Discovery {
    public static final int DISCOVERY_PORT = 10087;
    public static final String PROBE = "typeg?discover";
    public static final String SERVICE_TYPE = "_typeg._tcp.";
    public static final String SERVICE_NAME = "TypeGSmart";

    private final Context ctx;
    private volatile boolean running = false;
    private DatagramSocket udp;
    private Thread udpThread, announceThread;
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdListener;

    public Discovery(Context c) { this.ctx = c.getApplicationContext(); }

    public synchronized void start() {
        if (running) return;
        running = true;
        startUdp();
        startNsd();
    }

    public synchronized void stop() {
        running = false;
        try { if (udp != null) udp.close(); } catch (Exception ignore) {}
        stopNsd();
    }

    // ===== 1) UDP responder + announcer =====
    private void startUdp() {
        udpThread = new Thread(() -> {
            try {
                udp = new DatagramSocket(null);
                udp.setReuseAddress(true);
                udp.setBroadcast(true);
                udp.bind(new InetSocketAddress(DISCOVERY_PORT));
                byte[] buf = new byte[256];
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    udp.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
                    if (msg.startsWith(PROBE)) reply(p.getAddress(), p.getPort());
                }
            } catch (Exception ignore) {}
        }, "typeg-discovery");
        udpThread.start();

        announceThread = new Thread(() -> {
            try {
                while (running) {
                    broadcastAnnounce();
                    Thread.sleep(5000);
                }
            } catch (Exception ignore) {}
        }, "typeg-announce");
        announceThread.start();
    }

    private void reply(InetAddress to, int port) {
        try {
            byte[] data = payload().getBytes(StandardCharsets.UTF_8);
            udp.send(new DatagramPacket(data, data.length, to, port));
        } catch (Exception ignore) {}
    }

    private void broadcastAnnounce() {
        try {
            byte[] data = payload().getBytes(StandardCharsets.UTF_8);
            udp.send(new DatagramPacket(data, data.length,
                    InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT));
        } catch (Exception ignore) {}
    }

    private String payload() {
        try {
            return new JSONObject()
                .put("typegsmart", 1)
                .put("ip", localIp())
                .put("port", Protocol.PORT)
                .toString();
        } catch (Exception e) { return "{\"typeg\":1,\"port\":" + Protocol.PORT + "}"; }
    }

    private String localIp() {
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        int ip = wm.getConnectionInfo().getIpAddress();
        if (ip == 0) return "";
        return String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
    }

    // ===== 2) mDNS / NSD advertise =====
    private void startNsd() {
        try {
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName(SERVICE_NAME);
            info.setServiceType(SERVICE_TYPE);
            info.setPort(Protocol.PORT);
            nsdListener = new NsdManager.RegistrationListener() {
                public void onRegistrationFailed(NsdServiceInfo s, int e) {}
                public void onUnregistrationFailed(NsdServiceInfo s, int e) {}
                public void onServiceRegistered(NsdServiceInfo s) {}
                public void onServiceUnregistered(NsdServiceInfo s) {}
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception ignore) {}
    }

    private void stopNsd() {
        try { if (nsd != null && nsdListener != null) nsd.unregisterService(nsdListener); }
        catch (Exception ignore) {}
    }
}
