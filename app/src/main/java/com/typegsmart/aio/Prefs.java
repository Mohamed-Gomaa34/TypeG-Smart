package com.typegsmart.aio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** تخزين الإعدادات وحالة الأجهزة في SharedPreferences. */
public class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) { sp = c.getSharedPreferences("typegsmart", Context.MODE_PRIVATE); }

    public boolean getBool(String k, boolean d) { return sp.getBoolean(k, d); }
    public void    setBool(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }
    public String  getStr(String k, String d)   { return sp.getString(k, d); }
    public void    setStr(String k, String v)    { sp.edit().putString(k, v).apply(); }
    public int     getInt(String k, int d)        { return sp.getInt(k, d); }
    public void    setInt(String k, int v)         { sp.edit().putInt(k, v).apply(); }

    public JSONObject getObj(String k) {
        try { return new JSONObject(sp.getString(k, "{}")); }
        catch (JSONException e) { return new JSONObject(); }
    }
    public void setObj(String k, JSONObject v) { sp.edit().putString(k, v.toString()).apply(); }

    public JSONArray getArr(String k) {
        try { return new JSONArray(sp.getString(k, "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }
    public void setArr(String k, JSONArray v) { sp.edit().putString(k, v.toString()).apply(); }
}
