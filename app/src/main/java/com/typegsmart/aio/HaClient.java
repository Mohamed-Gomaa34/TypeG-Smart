package com.typegsmart.aio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * عميل Home Assistant عبر REST API.
 * بيكلّم HA مش المشترك مباشرة؛ بيجيب السويتشات ويتحكم فيها ويعرضها
 * كجهاز اسمه "Home Assistant" جنب المشتركات العادية.
 *
 * الإعداد: haUrl (مثل http://192.168.1.50:8123) + haToken (Long-Lived Access Token).
 */
public class HaClient {
    public static final String MAC = "HA";
    private final Prefs prefs;
    // channel -> entity_id (يتعبّى وقت بناء الحالة عشان التوجيه في التحكم)
    private final Map<Integer, String> channelEntity = new java.util.concurrent.ConcurrentHashMap<>();

    // كاش غير متزامن عشان الواجهة متستناش الشبكة
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile JSONObject cached;
    private volatile long lastFetch = 0;
    private volatile boolean fetching = false;
    private static final long FRESH_MS = 4000;

    public HaClient(Prefs p) { this.prefs = p; }

    public boolean configured() {
        return !prefs.getStr("haUrl", "").isEmpty() && !prefs.getStr("haToken", "").isEmpty();
    }

    private String base() {
        String u = prefs.getStr("haUrl", "").trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    // ===== HTTP =====
    private String http(String method, String path, String body) throws Exception {
        URL url = new URL(base() + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(7000);
        c.setRequestProperty("Authorization", "Bearer " + prefs.getStr("haToken", ""));
        c.setRequestProperty("Content-Type", "application/json");
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        java.io.InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line; while ((line = r.readLine()) != null) sb.append(line);
            r.close();
        }
        if (code < 200 || code >= 300) throw new Exception("HA_HTTP_" + code);
        return sb.toString();
    }

    /** فحص الاتصال بسرعة. */
    public boolean ping() {
        try { http("GET", "/api/", null); return true; } catch (Exception e) { return false; }
    }

    /** يرجّع نسخة مخزّنة فورًا، ويحدّثها في الخلفية لو قديمة. لا يحجب الواجهة. */
    public JSONObject device() {
        long now = System.currentTimeMillis();
        if (!fetching && (cached == null || now - lastFetch > FRESH_MS)) {
            fetching = true;
            io.execute(() -> {
                try { cached = fetchDevice(); lastFetch = System.currentTimeMillis(); }
                finally { fetching = false; }
            });
        }
        if (cached != null) return cached;
        // أول مرة: جهاز مبدئي لحد ما الجلب يخلص
        JSONObject d = new JSONObject();
        try { d.put("mac", MAC); d.put("id", MAC); d.put("name", "Home Assistant");
              d.put("online", false); d.put("outlets", new JSONArray()); } catch (Exception ignore) {}
        return d;
    }

    /** الجلب الفعلي من HA (يُشغَّل في الخلفية). */
    private JSONObject fetchDevice() {
        JSONObject d = new JSONObject();
        channelEntity.clear();
        try {
            d.put("mac", MAC); d.put("id", MAC); d.put("name", "Home Assistant");
            JSONArray outs = new JSONArray();
            String resp = http("GET", "/api/states", null);
            JSONArray states = new JSONArray(resp);
            int ch = 1;
            for (int i = 0; i < states.length(); i++) {
                JSONObject s = states.optJSONObject(i);
                if (s == null) continue;
                String eid = s.optString("entity_id", "");
                if (!eid.startsWith("switch.")) continue;
                JSONObject attr = s.optJSONObject("attributes");
                String name = attr != null ? attr.optString("friendly_name", eid) : eid;
                String st = s.optString("state", "off");
                JSONObject o = new JSONObject();
                o.put("channel", ch); o.put("name", name); o.put("room", "");
                o.put("type", "other"); o.put("state", "on".equals(st) ? "on" : "off");
                o.put("watts", lookupPower(states, eid)); o.put("wh", 0.0); o.put("cost", 0.0);
                o.put("enabled", true); o.put("pending", false); o.put("entityId", eid);
                outs.put(o);
                channelEntity.put(ch, eid);
                ch++;
            }
            d.put("online", true);
            d.put("outlets", outs);
        } catch (Exception e) {
            try { d.put("online", false); d.put("outlets", new JSONArray());
                  d.put("error", e.getMessage() == null ? "HA_ERROR" : e.getMessage()); } catch (Exception ignore) {}
        }
        return d;
    }

    /** محاولة إيجاد قراءة واط مرتبطة بالسويتش (sensor.<name>_power). */
    private double lookupPower(JSONArray states, String switchId) {
        String stem = switchId.substring("switch.".length());
        for (int i = 0; i < states.length(); i++) {
            JSONObject s = states.optJSONObject(i);
            if (s == null) continue;
            String eid = s.optString("entity_id", "");
            if (!eid.startsWith("sensor.")) continue;
            if (eid.contains(stem) && eid.contains("power")) {
                try { return Double.parseDouble(s.optString("state", "0")); } catch (Exception ignore) {}
            }
        }
        return 0.0;
    }

    /** تشغيل/فصل سويتش بالقناة (يُستخدم من التحكم). */
    public boolean setByChannel(int ch, boolean on) {
        String eid = channelEntity.get(ch);
        if (eid == null) return false;
        return setSwitch(eid, on);
    }

    public boolean setSwitch(String entityId, boolean on) {
        try {
            String svc = on ? "turn_on" : "turn_off";
            String body = new JSONObject().put("entity_id", entityId).toString();
            http("POST", "/api/services/switch/" + svc, body);
            return true;
        } catch (Exception e) { return false; }
    }

    public void save(String url, String token) {
        prefs.setStr("haUrl", url == null ? "" : url.trim());
        prefs.setStr("haToken", token == null ? "" : token.trim());
    }
}
