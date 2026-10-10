package com.typegsmart.aio;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

/**
 * محرك التحكم المحلي: ServerSocket على 10086 يستقبل اتصال المشتركات،
 * يحافظ على حالتها، ويوزّع الأوامر — بنفس بروتوكول النسخة الأصلية.
 */
public class ControlEngine implements StripConnection.Listener {
    private final Context ctx;
    private final Prefs prefs;
    private final EnergyStore energy;
    private final Discovery discovery;

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
    private volatile ServerSocket server;
    private volatile boolean running = false;

    // mac -> اتصال حي، و mac -> حالة الجهاز (JSON)
    private final Map<String, StripConnection> conns = new ConcurrentHashMap<>();
    private final Map<String, JSONObject> devices = new ConcurrentHashMap<>();
    // توقيت آخر عينة لكل مخرج (mac|ch) لحساب الطاقة
    private final Map<String, Long> lastSampleMs = new ConcurrentHashMap<>();
    // وقت اتصال كل مشترك — نتجاهل القراءات أول SETTLE_MS (الجهاز بيرجّع أصفار)
    private final Map<String, Long> connectedAt = new ConcurrentHashMap<>();
    private static final long SETTLE_MS = 10000;
    // المؤقتات الجارية (mac|ch) -> مهمة مجدولة
    private final Map<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);

    public ControlEngine(Context c) {
        this.ctx = c.getApplicationContext();
        this.prefs = new Prefs(ctx);
        this.energy = new EnergyStore(ctx);
        this.discovery = new Discovery(ctx);
        loadDevices();
    }

    public boolean isRunning() { return running; }

    // ===== دورة حياة السيرفر =====
    public synchronized void start() {
        if (running) return;
        running = true;
        pool.execute(() -> {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(Protocol.PORT));
                server = s;
                discovery.start();
                startPolling();
                while (running) {
                    try {
                        Socket client = s.accept();
                        StripConnection conn = new StripConnection(client, this);
                        pool.execute(conn);
                    } catch (Exception e) {
                        if (running) logEvent("CONNECT_ERROR", String.valueOf(e.getMessage()));
                    }
                }
            } catch (Exception e) {
                logEvent("ENGINE_ERROR", String.valueOf(e.getMessage()));
            } finally { closeServer(); }
        });
    }

    public synchronized void stop() {
        running = false;
        discovery.stop();
        if (poller != null) { poller.cancel(false); poller = null; }
        for (ScheduledFuture<?> f : timers.values()) f.cancel(false);
        timers.clear();
        for (StripConnection c : conns.values()) c.close();
        conns.clear();
        closeServer();
    }

    private void closeServer() {
        try { if (server != null) server.close(); } catch (Exception ignore) {}
        server = null;
    }

    // ===== استقبال رسائل المشترك =====
    @Override public void onLine(StripConnection conn, String line) {
        Matcher boot = Protocol.bootinfo(line);
        if (boot.matches()) {
            String name = boot.group(1), mac = boot.group(2).toUpperCase();
            String label = boot.group(4);
            conn.mac = mac; conn.label = label;
            conns.put(mac, conn);
            connectedAt.put(mac, System.currentTimeMillis());
            JSONObject d = devices.get(mac);
            if (d == null) d = newDevice(mac, name);
            try { d.put("online", true); d.put("name", d.optString("name", name)); } catch (Exception ignore) {}
            devices.put(mac, d);
            saveDevices();
            conn.send(Protocol.getInfoAll());   // استعلم عن كل المخارج بعد الاتصال
            return;
        }
        Matcher on = Protocol.onoff(line);
        if (on.matches()) {
            int fw = Integer.parseInt(on.group(1));
            boolean state = "on".equals(on.group(2));
            if (conn.mac != null) applyState(conn.mac, Protocol.physicalChannel(fw), state); // فريموير → فعلي
            return;
        }
        // رد المعلومات/القراءات: up:getinfo:<ch>:<..>:...  (الواط + الحالة لكل مخرج)
        List<Protocol.Reading> rs = Protocol.parseInfo(line);
        if (rs != null && conn.mac != null) {
            ingestReadings(conn.mac, rs);
        }
    }

    /** دمج قراءات المشترك: تحديث الحالة والواط + تسجيل الطاقة والتكلفة. */
    private void ingestReadings(String mac, List<Protocol.Reading> rs) {
        JSONObject d = devices.get(mac);
        if (d == null) return;
        JSONArray outs = d.optJSONArray("outlets");
        if (outs == null) return;
        long now = System.currentTimeMillis();
        double tariff = prefs.getObj("tariff").optDouble("rate", 0); // جنيه لكل kWh
        String day = DAY.format(new Date(now));
        // تجاهل أول SETTLE_MS بعد الاتصال (الجهاز بيرجّع أصفار)
        Long conn = connectedAt.get(mac);
        boolean settled = conn == null || (now - conn) > SETTLE_MS;

        for (Protocol.Reading r : rs) {
            int phys = Protocol.physicalChannel(r.channel);   // فريموير → فعلي
            for (int i = 0; i < outs.length(); i++) {
                JSONObject o = outs.optJSONObject(i);
                if (o == null || o.optInt("channel") != phys) continue;
                try {
                    o.put("state", r.on ? "on" : "off");
                    o.put("watts", r.watts);
                    o.put("pending", false);
                    // الحقول الخام الـ12 — عشان نحدّد الفولت/الواط بدقة من جهاز فعلي بدل التخمين
                    if (r.fields != null) {
                        JSONArray raw = new JSONArray();
                        for (String f : r.fields) raw.put(f);
                        o.put("raw", raw);
                    }
                } catch (Exception ignore) {}

                if (!settled) continue;   // متسجّلش طاقة في فترة الاستقرار

                // تكامل الطاقة: فقط لو الفرق الزمني أقل من 30 ثانية (زي الأصل)
                String key = mac + "|" + phys;
                Long prev = lastSampleMs.get(key);
                if (prev != null) {
                    long dt = now - prev;
                    if (dt > 0 && dt <= 30000) {
                        double whDelta = r.watts * (dt / 3600000.0);       // Wh
                        double costDelta = (whDelta / 1000.0) * tariff;    // جنيه
                        try {
                            o.put("wh", o.optDouble("wh", 0) + whDelta);
                            o.put("cost", o.optDouble("cost", 0) + costDelta);
                        } catch (Exception ignore) {}
                        energy.addSample(mac, phys, r.watts, whDelta);
                        energy.addCost(day, mac, whDelta, costDelta);
                    }
                }
                lastSampleMs.put(key, now);
            }
        }
        saveDevices();
    }

    // ===== المؤقتات =====
    /** جدولة تشغيل/فصل مخرج بعد عدد ثوانٍ. */
    public boolean scheduleTimer(String mac, int ch, int seconds, boolean turnOn) {
        if (!conns.containsKey(mac)) return false;
        String key = mac + "|" + ch;
        ScheduledFuture<?> old = timers.remove(key);
        if (old != null) old.cancel(false);
        long fireAt = System.currentTimeMillis() + seconds * 1000L;
        setTimerMeta(mac, ch, fireAt, seconds, turnOn);
        ScheduledFuture<?> f = sched.schedule(() -> {
            setOutlet(mac, ch, turnOn);
            timers.remove(key);
            clearTimerMeta(mac, ch);
            logEvent("TIMER_FIRED", key);
        }, seconds, TimeUnit.SECONDS);
        timers.put(key, f);
        return true;
    }

    public void cancelTimer(String mac, int ch) {
        String key = mac + "|" + ch;
        ScheduledFuture<?> f = timers.remove(key);
        if (f != null) f.cancel(false);
        clearTimerMeta(mac, ch);
        logEvent("TIMER_CANCELLED", key);
    }

    private void setTimerMeta(String mac, int ch, long at, int seconds, boolean on) {
        eachOutlet(mac, ch, o -> {
            try {
                JSONObject t = new JSONObject();
                t.put("at", at); t.put("seconds", seconds); t.put("state", on ? "on" : "off");
                o.put("timer", t);
            } catch (Exception ignore) {}
        });
        saveDevices();
    }
    private void clearTimerMeta(String mac, int ch) {
        eachOutlet(mac, ch, o -> o.remove("timer"));
        saveDevices();
    }
    /** تعديل بيانات مخرج (اسم/غرفة/نوع/محمي) في الحالة الحيّة. */
    public void updateOutletMeta(String mac, int ch, JSONObject fields) {
        eachOutlet(mac, ch, o -> {
            try {
                if (fields.has("name")) o.put("name", fields.optString("name"));
                if (fields.has("room")) o.put("room", fields.optString("room"));
                if (fields.has("type")) o.put("type", fields.optString("type"));
                if (fields.has("protected")) o.put("protected", fields.optBoolean("protected"));
            } catch (Exception ignore) {}
        });
        saveDevices();
    }

    public void setStripName(String mac, String name) {
        JSONObject d = devices.get(mac);
        if (d != null) { try { d.put("name", name); } catch (Exception ignore) {} saveDevices(); }
    }

    private interface OutletFn { void apply(JSONObject o); }
    private void eachOutlet(String mac, int ch, OutletFn fn) {
        JSONObject d = devices.get(mac);
        if (d == null) return;
        JSONArray outs = d.optJSONArray("outlets");
        if (outs == null) return;
        for (int i = 0; i < outs.length(); i++) {
            JSONObject o = outs.optJSONObject(i);
            if (o != null && o.optInt("channel") == ch) fn.apply(o);
        }
    }

    @Override public void onClose(StripConnection conn) {
        if (conn.mac != null) {
            conns.remove(conn.mac, conn);
            JSONObject d = devices.get(conn.mac);
            if (d != null) { try { d.put("online", false); } catch (Exception ignore) {} saveDevices(); }
        }
    }

    // ===== أوامر صادرة =====
    /** ch = المخرج الفعلي (1..4). */
    public boolean setOutlet(String mac, int ch, boolean on) {
        if (!on && isProtected(mac, ch)) return false;   // مخرج محمي لا يُفصَل
        StripConnection c = conns.get(mac);
        if (c == null || !c.isOpen()) return false;
        setPending(mac, ch, true);
        c.send(Protocol.onoff(Protocol.fwChannel(ch), on));   // فعلي → فريموير
        return true;
    }

    public boolean isProtected(String mac, int ch) {
        JSONObject d = devices.get(mac);
        if (d == null) return false;
        JSONArray outs = d.optJSONArray("outlets");
        if (outs == null) return false;
        for (int i = 0; i < outs.length(); i++) {
            JSONObject o = outs.optJSONObject(i);
            if (o != null && o.optInt("channel") == ch) return o.optBoolean("protected", false);
        }
        return false;
    }

    public void refresh(String mac) {
        StripConnection c = conns.get(mac);
        if (c != null && c.isOpen()) c.send(Protocol.getInfoAll());
    }

    private ScheduledFuture<?> poller;
    /** استطلاع دوري لكل المشتركات المتصلة لتحديث القراءات. */
    private void startPolling() {
        if (poller != null) poller.cancel(false);
        int sec = Math.max(2, prefs.getInt("pollSeconds", 3));
        poller = sched.scheduleAtFixedRate(() -> {
            for (StripConnection c : conns.values())
                if (c.isOpen()) c.send(Protocol.getInfoAll());
        }, sec, sec, TimeUnit.SECONDS);
    }

    public void allOff() {
        for (JSONObject d : devices.values()) {
            String mac = d.optString("mac");
            for (int ch = 1; ch <= 4; ch++) setOutlet(mac, ch, false);
        }
    }

    public void groupControl(String mac, boolean on) {
        for (int ch = 1; ch <= 4; ch++) setOutlet(mac, ch, on);
    }

    // ===== حالة الأجهزة =====
    private void applyState(String mac, int ch, boolean on) {
        JSONObject d = devices.get(mac);
        if (d == null) return;
        JSONArray outs = d.optJSONArray("outlets");
        if (outs == null) return;
        for (int i = 0; i < outs.length(); i++) {
            JSONObject o = outs.optJSONObject(i);
            if (o != null && o.optInt("channel") == ch) {
                try { o.put("state", on ? "on" : "off"); o.put("pending", false); } catch (Exception ignore) {}
            }
        }
        saveDevices();
    }

    private void setPending(String mac, int ch, boolean p) {
        JSONObject d = devices.get(mac);
        if (d == null) return;
        JSONArray outs = d.optJSONArray("outlets");
        if (outs == null) return;
        for (int i = 0; i < outs.length(); i++) {
            JSONObject o = outs.optJSONObject(i);
            if (o != null && o.optInt("channel") == ch) try { o.put("pending", p); } catch (Exception ignore) {}
        }
    }

    private JSONObject newDevice(String mac, String name) {
        JSONObject d = new JSONObject();
        try {
            d.put("mac", mac); d.put("id", mac); d.put("name", name == null ? "TypeG" : name);
            d.put("online", true);
            JSONArray outs = new JSONArray();
            for (int ch = 1; ch <= 4; ch++) {
                JSONObject o = new JSONObject();
                o.put("channel", ch); o.put("name", "Outlet " + ch); o.put("room", "");
                o.put("type", "other"); o.put("state", "off"); o.put("watts", 0.0);
                o.put("wh", 0.0); o.put("cost", 0.0); o.put("enabled", true); o.put("pending", false);
                outs.put(o);
            }
            d.put("outlets", outs);
            d.put("rules", new JSONArray());
        } catch (Exception ignore) {}
        return d;
    }

    // ===== تخزين =====
    public JSONArray devicesJson() {
        JSONArray a = new JSONArray();
        for (JSONObject d : devices.values()) a.put(d);
        return a;
    }
    private void loadDevices() {
        JSONArray a = prefs.getArr("devices");
        for (int i = 0; i < a.length(); i++) {
            JSONObject d = a.optJSONObject(i);
            if (d != null) { try { d.put("online", false); } catch (Exception ignore) {} devices.put(d.optString("mac"), d); }
        }
    }
    private void saveDevices() { prefs.setArr("devices", devicesJson()); }

    public EnergyStore energy() { return energy; }

    public void logEvent(String code, String detail) {
        JSONArray ev = prefs.getArr("events");
        try {
            JSONObject e = new JSONObject();
            e.put("ts", System.currentTimeMillis()); e.put("code", code); e.put("detail", detail);
            ev.put(e);
            while (ev.length() > 100) ev.remove(0);
            prefs.setArr("events", ev);
        } catch (Exception ignore) {}
    }
}
