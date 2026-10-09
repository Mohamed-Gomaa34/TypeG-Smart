package com.typegsmart.aio;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * بروتوكول المشترك كما هو في النسخة الأصلية (v1.2.1) — بدون تغيير.
 * المحرك TCP server على البورت 10086، والمشترك هو اللي بيتصل بالموبايل.
 */
public final class Protocol {
    private Protocol() {}

    public static final int PORT = 10086;
    public static final String EOL = "\r\n";

    // أوامر صادرة للمشترك
    public static String connect(String arg)      { return "up:connect:" + arg; }
    public static String getInfoAll()             { return "up:getinfo:all"; }
    public static String getInfo(int ch)          { return "up:getinfo:" + ch; }
    public static String onoff(int ch, boolean on){ return "up:onoff:" + ch + ":" + (on ? "on" : "off"); }
    public static String ip(String arg)           { return "up:ip:" + arg; }
    public static String reboot()                 { return "up:reboot:0" + EOL; }

    // رسائل واردة من المشترك
    public static final Pattern ONOFF =
        Pattern.compile("^up:(?:event:)?onoff:([1-4]):(on|off)$");
    public static final Pattern BOOTINFO =
        Pattern.compile("^up:bootinfo:([^;]{1,32});([0-9a-fA-F]{12});([0-9a-fA-F]{12});([^;]{1,64});connect$");
    public static final Pattern TAP =
        Pattern.compile("(?i)TONLY_TAP_[0-9A-F]{6,12}");

    public static Matcher onoff(String line)   { return ONOFF.matcher(line); }
    public static Matcher bootinfo(String line){ return BOOTINFO.matcher(line); }
    public static Matcher tap(String line)     { return TAP.matcher(line); }

    public static final String INFO_PREFIX = "up:getinfo:";

    /** قراءة مخرج واحد من رد getinfo. */
    public static final class Reading {
        public final int channel; public final boolean on; public final double watts;
        public Reading(int ch, boolean on, double w){ channel=ch; this.on=on; watts=w; }
    }

    /**
     * تحليل رد المشترك: up:getinfo:<ch>:<f0;on|off;..;watts*1000;..>:<ch>:<...>...
     * القص 11 حرف، تقسيم ":" = 8 أجزاء (4 مخارج)، بيانات كل مخرج ";" = 12 حقل،
     * الحقل[1]=الحالة، الحقل[5]=رقم/1000=الواط. يرجّع null لو الصيغة مش مطابقة.
     */
    public static java.util.List<Reading> parseInfo(String line) {
        if (line == null || !line.startsWith(INFO_PREFIX)) return null;
        String[] parts = line.substring(11).split(":", -1);
        if (parts.length != 8) return null;
        java.util.List<Reading> out = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i += 2) {
            if (!parts[i].matches("[1-4]")) return null;
            int ch = Integer.parseInt(parts[i]);
            String[] f = parts[i + 1].split(";", -1);
            if (f.length != 12) return null;
            if (!f[1].matches("on|off")) return null;
            boolean on = "on".equals(f[1]);
            double w = 0;
            if (f[5].matches("[0-9]{1,9}")) w = Long.parseLong(f[5]) / 1000.0;
            out.add(new Reading(ch, on, w));
        }
        return out;
    }
}
