package com.typegsmart.aio;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * جسر الواجهة ↔ الجافا. الواجهة بتنادي window.TypeG.call(action, json).
 * بدون أي تفعيل — license.active = true دايمًا للتوافق مع الواجهة.
 */
public class TypeGBridge {
    private final Context ctx;
    private final Prefs prefs;
    private final HaClient ha;

    public TypeGBridge(Context c) {
        this.ctx = c.getApplicationContext();
        this.prefs = new Prefs(ctx);
        this.ha = new HaClient(prefs);
    }

    private ControlEngine eng() { return ControlService.engine(ctx); }

    @JavascriptInterface
    public String call(String action, String payload) {
        try {
            JSONObject b = (payload == null || payload.isEmpty()) ? new JSONObject() : new JSONObject(payload);
            return dispatch(action, b).toString();
        } catch (Exception e) {
            return err(e.getMessage() == null ? "SERVICE_ERROR" : e.getMessage());
        }
    }

    private JSONObject dispatch(String a, JSONObject b) throws Exception {
        switch (a) {
            case "state":        return state();
            case "start":        ControlService.startSelf(ctx); eng().start(); return ok();
            case "enable":       eng().start(); return ok();

            case "control": {
                boolean on = "on".equals(b.optString("state"));
                String mac = b.optString("mac");
                if (HaClient.MAC.equals(mac)) {   // توجيه لـ Home Assistant
                    return ha.setByChannel(b.optInt("channel"), on) ? ok() : fail("DEVICE_DISCONNECTED");
                }
                int ch = b.optInt("channel");
                if (!on && eng().isProtected(mac, ch)) return fail("PROTECTED");
                boolean okc = eng().setOutlet(mac, ch, on);
                return okc ? ok() : fail("DEVICE_DISCONNECTED");
            }
            case "groupControl":  eng().groupControl(b.optString("mac"), "on".equals(b.optString("state"))); return ok();

            // ===== Home Assistant =====
            case "haSave":        ha.save(b.optString("url"), b.optString("token")); return ok();
            case "haStatus": {
                JSONObject r = ok();
                r.put("configured", ha.configured());
                r.put("url", prefs.getStr("haUrl", ""));
                if (ha.configured()) r.put("connected", ha.ping());
                return r;
            }
            case "globalControl":
            case "allOff":        eng().allOff(); return ok();

            case "timer": {
                String mac = b.optString("mac");
                int ch = b.optInt("channel");
                int seconds = b.optInt("seconds", 0);
                if (seconds <= 0) { eng().cancelTimer(mac, ch); return ok(); }
                boolean turnOn = "on".equals(b.optString("state", "off"));
                return eng().scheduleTimer(mac, ch, seconds, turnOn) ? ok() : fail("DEVICE_DISCONNECTED");
            }

            case "wake":
            case "wakeNow": {
                JSONObject w = prefs.getObj("wakeConfig");
                Wol.send(w.optString("mac", b.optString("mac")), w.optString("broadcast", null), w.optInt("port", 9));
                return ok();
            }
            case "wakeSave":      prefs.setObj("wakeConfig", b); return ok();

            case "history": {
                String mac = b.optString("mac");
                long since = System.currentTimeMillis() - 24L * 3600 * 1000;
                JSONObject r = ok(); r.put("points", eng().energy().history(mac, since)); return r;
            }
            case "csv": {
                String mac = b.optString("mac");
                long since = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
                JSONObject r = ok(); r.put("csv", eng().energy().csv(mac, since)); return r;
            }

            case "settings":      return saveSettings(b);
            case "saveHome":      prefs.setObj("home", b); return ok();
            case "provision":     prefs.setObj("home", b); ControlService.startSelf(ctx); return ok();
            case "saveMeta":
            case "edit":          return saveMeta(b);
            case "saveTariff":    prefs.setObj("tariff", b); return ok();

            // أوامر تُخزَّن كما هي (أتمتة/مشاهد) ويقرأها state
            case "ruleAdd": case "saveRule": return pushInto("rules", b);
            case "sceneSave": case "saveScene": return pushInto("scenes", b);

            case "wifiSettings":  ctx.startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return ok();
            case "guide":         return ok();   // الدليل يُفتح من الواجهة
            default:              return ok();   // باقي الأوامر: لا تكسر الواجهة
        }
    }

    // ===== بناء الحالة S =====
    private JSONObject state() throws Exception {
        JSONObject s = new JSONObject();
        s.put("running", eng().isRunning());
        s.put("onboarded", prefs.getBool("onboarded", false));
        s.put("lang", prefs.getStr("lang", "ar"));
        s.put("themeMode", prefs.getStr("themeMode", "system"));
        s.put("palette", prefs.getStr("palette", "mint"));
        s.put("sound", prefs.getBool("sound", true));
        s.put("motion", prefs.getBool("motion", true));
        s.put("haptic", prefs.getBool("haptic", true));
        s.put("soundVolume", prefs.getInt("soundVolume", 1));
        s.put("pollSeconds", prefs.getInt("pollSeconds", 3));
        s.put("usageAlerts", prefs.getBool("usageAlerts", true));
        s.put("dailyLimit", prefs.getInt("dailyLimit", 0));
        JSONObject home = prefs.getObj("home");
        s.put("homeSSID", home.optString("ssid", ""));
        s.put("homeIP", home.optString("ip", ""));
        s.put("tariff", prefs.getObj("tariff"));
        s.put("tariffCatalog", prefs.getArr("tariffCatalog"));
        JSONArray devices = eng().devicesJson();
        if (ha.configured()) devices.put(ha.device());   // دمج جهاز Home Assistant
        s.put("devices", devices);
        s.put("haConfigured", ha.configured());
        s.put("scenes", prefs.getArr("scenes"));
        s.put("events", prefs.getArr("events"));
        s.put("prompts", new JSONArray());
        // التفعيل متشال — نخلي الواجهة تعدّي القفل
        s.put("license", new JSONObject().put("active", true));
        s.put("terms", new JSONObject().put("accepted", true));
        return s;
    }

    private JSONObject saveSettings(JSONObject b) {
        if (b.has("sound"))   prefs.setBool("sound", b.optBoolean("sound"));
        if (b.has("motion"))  prefs.setBool("motion", b.optBoolean("motion"));
        if (b.has("haptic"))  prefs.setBool("haptic", b.optBoolean("haptic"));
        if (b.has("lang"))    prefs.setStr("lang", b.optString("lang"));
        if (b.has("themeMode")) prefs.setStr("themeMode", b.optString("themeMode"));
        if (b.has("pollSeconds")) prefs.setInt("pollSeconds", b.optInt("pollSeconds"));
        if (b.has("dailyLimit"))  prefs.setInt("dailyLimit", b.optInt("dailyLimit"));
        return ok();
    }

    private JSONObject saveMeta(JSONObject b) {
        String mac = b.optString("mac");
        int ch = b.optInt("channel", -1);
        if (HaClient.MAC.equals(mac)) return ok();   // بيانات HA مش بتتخزّن هنا
        if (b.has("stripName")) eng().setStripName(mac, b.optString("stripName"));
        if (ch > 0) eng().updateOutletMeta(mac, ch, b);   // name/room/type/protected
        return ok();
    }

    private JSONObject pushInto(String key, JSONObject item) {
        JSONArray a = prefs.getArr(key);
        a.put(item);
        prefs.setArr(key, a);
        return ok();
    }

    private JSONObject ok() { try { return new JSONObject().put("ok", true); } catch (Exception e) { return new JSONObject(); } }
    private JSONObject fail(String code) {
        try { return new JSONObject().put("error", code == null ? "SERVICE_ERROR" : code); }
        catch (Exception e) { return new JSONObject(); }
    }
    private String err(String code) {
        try { return new JSONObject().put("error", code == null ? "SERVICE_ERROR" : code).toString(); }
        catch (Exception e) { return "{\"error\":\"SERVICE_ERROR\"}"; }
    }
}
