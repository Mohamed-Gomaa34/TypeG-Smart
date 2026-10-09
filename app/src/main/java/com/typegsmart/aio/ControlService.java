package com.typegsmart.aio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;

/** Foreground service يشغّل محرك التحكم طول ما البرنامج متفعّل. */
public class ControlService extends Service {
    private static final String CH = "controller";
    private static ControlEngine engine;   // مشترك مع الجسر
    private PowerManager.WakeLock wake;

    public static ControlEngine engine(Context c) {
        if (engine == null) engine = new ControlEngine(c);
        return engine;
    }

    @Override public void onCreate() {
        super.onCreate();
        engine(this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TypeGSmart:Controller");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        if (wake != null && !wake.isHeld()) wake.acquire();
        engine(this).start();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (engine != null) engine.stop();
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CH, "Controller", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        return b.setContentTitle("Type-G Smart")
                .setContentText("المتحكم المحلي يعمل")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();
    }

    public static void startSelf(Context c) {
        Intent i = new Intent(c, ControlService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }
}
