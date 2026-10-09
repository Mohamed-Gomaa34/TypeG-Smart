package com.typegsmart.aio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

/** قاعدة بيانات الطاقة — نفس المخطط الأصلي (energy.db). */
public class EnergyStore extends SQLiteOpenHelper {
    public EnergyStore(Context c) { super(c, "energy.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS samples(ts INTEGER,mac TEXT,ch INTEGER,w REAL,wh REAL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS sample_index ON samples(mac,ts)");
        db.execSQL("CREATE TABLE IF NOT EXISTS daily_cost(day TEXT,mac TEXT,wh REAL NOT NULL,cost REAL NOT NULL,PRIMARY KEY(day,mac))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {}

    /** تسجيل عينة استهلاك لحظية (watts) + تراكم watt-hours. */
    public void addSample(String mac, int ch, double w, double wh) {
        ContentValues v = new ContentValues();
        v.put("ts", System.currentTimeMillis());
        v.put("mac", mac); v.put("ch", ch); v.put("w", w); v.put("wh", wh);
        getWritableDatabase().insert("samples", null, v);
    }

    public void addCost(String day, String mac, double wh, double cost) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT OR IGNORE INTO daily_cost(day,mac,wh,cost) VALUES(?,?,0,0)", new Object[]{day, mac});
        db.execSQL("UPDATE daily_cost SET wh=wh+?,cost=cost+? WHERE day=? AND mac=?", new Object[]{wh, cost, day, mac});
    }

    public double costSince(String mac, String day) {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT SUM(cost) FROM daily_cost WHERE mac=? AND day>=?", new String[]{mac, day});
        try { return c.moveToFirst() ? c.getDouble(0) : 0; } finally { c.close(); }
    }

    /** تاريخ العينات لرسم الاستهلاك. */
    public JSONArray history(String mac, long sinceTs) {
        JSONArray out = new JSONArray();
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT ts,ch,w,wh FROM samples WHERE mac=? AND ts>=? ORDER BY ts",
            new String[]{mac, String.valueOf(sinceTs)});
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("ts", c.getLong(0)); o.put("ch", c.getInt(1));
                o.put("w", c.getDouble(2)); o.put("wh", c.getDouble(3));
                out.put(o);
            }
        } catch (Exception ignore) {} finally { c.close(); }
        return out;
    }

    /** تصدير CSV للعينات. */
    public String csv(String mac, long sinceTs) {
        StringBuilder sb = new StringBuilder("time,outlet,power_W,calculated_Wh\n");
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT ts,ch,w,wh FROM samples WHERE mac=? AND ts>=? ORDER BY ts",
            new String[]{mac, String.valueOf(sinceTs)});
        try {
            while (c.moveToNext())
                sb.append(c.getLong(0)).append(',').append(c.getInt(1)).append(',')
                  .append(c.getDouble(2)).append(',').append(c.getDouble(3)).append('\n');
        } finally { c.close(); }
        return sb.toString();
    }
}
