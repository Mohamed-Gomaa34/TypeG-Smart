package com.typegsmart.aio;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * إعداد المشترك تلقائيًا عبر هوت-سبوت الإعداد بتاعه.
 * - اسم الشبكة: TONLY_TAP_XXXXXXX
 * - الباسوورد (افتراضي): LGU_ + نفس الـ7 حروف اللي بعد TONLY_TAP_
 * - خدمة الإعداد: 192.168.1.1:30300، كل أمر في اتصال لوحده بنهاية CRLF:
 *     up:ip:<serverIp>            -> up:ip:ip_ok
 *     up:connect:<ssid>:<pass>    -> up:connect:connect_ok
 * البروتوكول متأكد من مشاريع مجتمعية مفتوحة لنفس الجهاز.
 */
public class Provisioner {
    public static final String AP_PREFIX = "TONLY_TAP_";
    public static final String SETUP_HOST = "192.168.1.1";
    public static final int SETUP_PORT = 30300;

    public interface Callback { void progress(String msg); void done(boolean ok, String msg); }

    private final Context ctx;
    public Provisioner(Context c) { this.ctx = c.getApplicationContext(); }

    /** يحسب باسوورد هوت-سبوت المشترك من اسمه. يرجّع "" لو الاسم مش بالصيغة المتوقعة. */
    public static String apPassword(String ssid) {
        if (ssid == null) return "";
        String s = ssid.trim();
        if (!s.startsWith(AP_PREFIX)) return "";
        return "LGU_" + s.substring(AP_PREFIX.length());
    }

    /** مسح الشبكات القريبة وإرجاع شبكات المشترك (TONLY_TAP_*). قد يحتاج إذن الموقع/الأجهزة القريبة. */
    @SuppressWarnings("deprecation")
    public List<String> scanStrips() {
        Set<String> found = new LinkedHashSet<>();
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return new ArrayList<>(found);
            try { wm.startScan(); } catch (Exception ignore) {}
            List<ScanResult> results = wm.getScanResults();
            if (results != null) for (ScanResult r : results) {
                String ssid = r.SSID;
                if (ssid != null && ssid.startsWith(AP_PREFIX)) found.add(ssid);
            }
        } catch (Exception ignore) {}
        return new ArrayList<>(found);
    }

    /** IP الموبايل الحالي على الواي فاي (بيتحفظ عشان المشترك يتصل بيه بعد الإعداد). */
    @SuppressWarnings("deprecation")
    public String currentWifiIp() {
        // الأفضل: IPv4 الحقيقي للشبكة النشطة (شبكة البيت) — أدق من WifiManager القديم
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ? cm.getActiveNetwork() : null;
            if (n != null) {
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null) for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isAnyLocalAddress())
                        return a.getHostAddress();
                }
            }
        } catch (Exception ignore) {}
        // احتياطي: الطريقة القديمة
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip == 0) return "";
            return String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
        } catch (Exception e) { return ""; }
    }

    /** الواي فاي والباسوورد ميحتوش ":" ولا أسطر جديدة (محددات البروتوكول). */
    public static boolean credsValid(String ssid, String pass) {
        for (String v : new String[]{ssid, pass}) {
            if (v == null) return false;
            if (v.contains(":") || v.contains("\r") || v.contains("\n")) return false;
        }
        return true;
    }

    /**
     * يتصل بهوت-سبوت المشترك ويبعت الإعداد.
     * apSsid/apPass: شبكة المشترك. homeSsid/homePass: شبكة البيت. serverIp: IP الموبايل على شبكة البيت.
     */
    public void provision(String apSsid, String apPass, String homeSsid, String homePass,
                          String serverIp, Callback cb) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { cb.done(false, "ANDROID_TOO_OLD"); return; }
        if (!credsValid(homeSsid, homePass)) { cb.done(false, "BAD_WIFI_CREDS"); return; }

        final ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                .setSsid(apSsid).setWpa2Passphrase(apPass).build();
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec).build();

        cb.progress("CONNECTING_AP");
        final ConnectivityManager.NetworkCallback[] holder = new ConnectivityManager.NetworkCallback[1];
        ConnectivityManager.NetworkCallback nc = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                new Thread(() -> {
                    boolean ok = false; String msg;
                    try {
                        cb.progress("SENDING_IP");
                        String r1 = send(network, "up:ip:" + serverIp);
                        if (r1 == null || !r1.contains("ip_ok")) { msg = "IP_REJECTED"; }
                        else {
                            cb.progress("SENDING_WIFI");
                            String r2 = send(network, "up:connect:" + homeSsid + ":" + homePass);
                            if (r2 != null && r2.contains("connect_ok")) { ok = true; msg = "OK"; }
                            else msg = "CONNECT_REJECTED";
                        }
                    } catch (Exception e) {
                        msg = "AP_IO_ERROR";
                    }
                    try { cm.unregisterNetworkCallback(holder[0]); } catch (Exception ignore) {}
                    cb.done(ok, msg);
                }, "provision").start();
            }
            @Override public void onUnavailable() {
                try { cm.unregisterNetworkCallback(holder[0]); } catch (Exception ignore) {}
                cb.done(false, "AP_UNAVAILABLE");
            }
        };
        holder[0] = nc;
        cm.requestNetwork(req, nc, 30000);   // المستخدم بيوافق على الاتصال بالشبكة مرة واحدة
    }

    /** أمر إعداد واحد عبر الشبكة المحددة (socket مربوط بشبكة المشترك). */
    private String send(Network network, String cmd) throws Exception {
        Socket s = network.getSocketFactory().createSocket();
        try {
            s.connect(new InetSocketAddress(SETUP_HOST, SETUP_PORT), 6000);
            s.setSoTimeout(6000);
            OutputStream os = s.getOutputStream();
            os.write((cmd + "\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            return in.readLine();
        } finally { try { s.close(); } catch (Exception ignore) {} }
    }
}
