package com.typegsmart.aio;

import android.content.Context;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * جسر الواجهة ↔ الجافا. الواجهة بتنادي window.TypeG.call(action, json).
 * التطبيق يشتغل مباشرة بدون أي بوابة تفعيل.
 */
public class TypeGBridge {
    private final Context ctx;
    private final Prefs prefs;
    private final HaClient ha;
    private final Provisioner prov;

    public TypeGBridge(Context c) {
        this.ctx = c.getApplicationContext();
        this.prefs = new Prefs(ctx);
        this.ha = new HaClient(prefs);
        this.prov = new Provisioner(ctx);
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
            case "allOff":        eng().allOff(); return ok();

            case "timer": {
                String mac = b.optString("mac");
                int ch = b.optInt("channel");
                int seconds = b.optInt("seconds", 0);
                if (seconds <= 0) { eng().cancelTimer(mac, ch); return ok(); }
                boolean turnOn = "on".equals(b.optString("state", "off"));
                return eng().scheduleTimer(mac, ch, seconds, turnOn) ? ok() : fail("DEVICE_DISCONNECTED");
            }

            // Wake-on-LAN
            case "wakeNow": {
                JSONObject w = prefs.getObj("wakeConfig");
                Wol.send(w.optString("mac", b.optString("mac")), w.optString("broadcast", null), w.optInt("port", 9));
                return ok();
            }
            case "wakeSave":      prefs.setObj("wakeConfig", b); return ok();

            // Home Assistant
            case "haSave":        ha.save(b.optString("url"), b.optString("token")); return ok();
            case "haStatus": {
                JSONObject r = ok();
                r.put("configured", ha.configured());
                r.put("url", prefs.getStr("haUrl", ""));
                if (ha.configured()) r.put("connected", ha.ping());
                return r;
            }

            // الطاقة
            case "history": {
                long since = System.currentTimeMillis() - 24L * 3600 * 1000;
                JSONObject r = ok(); r.put("points", eng().energy().history(b.optString("mac"), since)); return r;
            }
            case "csv": {
                long since = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
                JSONObject r = ok(); r.put("csv", eng().energy().csv(b.optString("mac"), since)); return r;
            }

            // الإعدادات والبيانات
            case "settings":      return saveSettings(b);
            case "saveMeta":      return saveMeta(b);
            case "saveTariff":    prefs.setObj("tariff", b); return ok();

            // ===== إعداد المشترك تلقائيًا =====
            case "prepPerms":     MainActivity.requestWifiPerms(); return ok();
            case "scanStrips": {
                JSONObject r = ok(); r.put("networks", new JSONArray(prov.scanStrips())); return r;
            }
            case "apPassword": {
                JSONObject r = ok(); r.put("password", Provisioner.apPassword(b.optString("ssid"))); return r;
            }
            case "homeIp": {
                JSONObject r = ok(); r.put("ip", prov.currentWifiIp()); return r;
            }
            // تشخيص: عنوان الموبايل + حالة السيرفر + آخر الأحداث (اتصالات واردة من المشترك)
            case "diag": {
                JSONObject r = ok();
                r.put("ip", prov.currentWifiIp());
                r.put("running", eng().isRunning());
                r.put("port", eng().serverPort());
                r.put("events", eng().recentEvents());
                return r;
            }
            // دفتر شبكات البيت (اسم ← باسوورد) محليًا
            case "wifiSave":      prefs.setStr("wifi_" + b.optString("ssid"), b.optString("password")); return ok();
            case "wifiGet": {
                JSONObject r = ok(); r.put("password", prefs.getStr("wifi_" + b.optString("ssid"), "")); return r;
            }
            case "provision": {
                final String apSsid = b.optString("apSsid");
                final String apPass = b.optString("apPass", Provisioner.apPassword(apSsid));
                final String homeSsid = b.optString("homeSsid");
                final String homePass = b.optString("homePass");
                final String serverIp = b.optString("serverIp", prov.currentWifiIp());
                // احفظ باسوورد شبكة البيت للمرة الجاية
                if (!homeSsid.isEmpty()) prefs.setStr("wifi_" + homeSsid, homePass);
                prov.provision(apSsid, apPass, homeSsid, homePass, serverIp, new Provisioner.Callback() {
                    public void progress(String m) { MainActivity.pushJs("window.onProvision&&window.onProvision('progress'," + JSONObject.quote(m) + ")"); }
                    public void done(boolean okr, String m) {
                        try { eng().logEvent(okr ? "PROVISION_OK" : "PROVISION_FAIL", serverIp + " / " + m); } catch (Exception ignore) {}
                        MainActivity.pushJs("window.onProvision&&window.onProvision(" + (okr?"'ok'":"'fail'") + "," + JSONObject.quote(m) + ")");
                    }
                });
                JSONObject r = ok(); r.put("started", true); return r;
            }

            default:              return ok();   // أمر غير معروف: بدون تأثير
        }
    }

    // ===== بناء الحالة S (المطلوبة للواجهة فقط) =====
    private JSONObject state() throws Exception {
        JSONObject s = new JSONObject();
        s.put("running", eng().isRunning());
        s.put("lang", prefs.getStr("lang", "ar"));
        s.put("tariff", prefs.getObj("tariff"));
        s.put("wakeConfig", prefs.getObj("wakeConfig"));
        JSONArray devices = eng().devicesJson();
        if (ha.configured()) devices.put(ha.device());   // دمج جهاز Home Assistant
        s.put("devices", devices);
        s.put("haConfigured", ha.configured());
        s.put("appVersion", BuildConfig.VERSION_NAME);
        return s;
    }

    private JSONObject saveSettings(JSONObject b) {
        if (b.has("lang"))        prefs.setStr("lang", b.optString("lang"));
        if (b.has("themeMode"))   prefs.setStr("themeMode", b.optString("themeMode"));
        if (b.has("pollSeconds")) prefs.setInt("pollSeconds", b.optInt("pollSeconds"));
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
