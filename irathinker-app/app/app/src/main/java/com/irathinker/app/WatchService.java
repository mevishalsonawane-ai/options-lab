package com.irathinker.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.time.LocalTime;
import java.time.ZoneId;

/** The watchdog: a foreground service that keeps IraThinker alerting
 *  from 08:30 to midnight. Started when the app opens and on boot.
 *  A persistent (quiet) notification is Android's price for a service
 *  the system will not kill; the JobScheduler chain remains as backup.
 *
 *  v52: Binance gold trades 24/7, so the watchdog no longer sleeps at
 *  midnight. Inside 08:30-24:00 it runs the full engine as before;
 *  outside that window it wakes every 5 minutes for a gold-only pass
 *  (gold's scan refreshes; the user's gold price alerts may fire) and
 *  nothing else scans. The JobScheduler chain still only drives the
 *  full engine - overnight gold is this service's job alone. */
public class WatchService extends Service {

    private static final String CHANNEL = "irathinker_watch";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(8, 30);
    /** Relaxed overnight tick for the gold-only pass. */
    private static final long GOLD_TICK_MS = 5 * 60_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean stopped = false;

    public static boolean inWindow() {
        LocalTime now = LocalTime.now(IST);
        // window: 08:30 -> 24:00
        return !now.isBefore(START);
    }

    public static void start(Context ctx) {
        if (!new Prefs(ctx).watchdogOn()) return;
        // v52: no inWindow() gate here any more - the service itself
        // splits its ticks into full-engine (in window) and gold-only
        // (overnight), so a boot or app open at 03:00 still starts the
        // gold watch.
        Intent i = new Intent(ctx, WatchService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) {
            // background-start restrictions: the job chain remains as fallback
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            if (inWindow()) {
                new Thread(() -> {
                    try { AlertEngine.runChecks(WatchService.this); } catch (Throwable ignored) { }
                }).start();
                handler.postDelayed(this, nextDelayMs());
            } else {
                // Overnight: gold only, at a relaxed tick. The pass
                // refreshes XAU/USD and checks the user's gold price
                // alerts; no other symbol is fetched or scanned.
                new Thread(() -> {
                    try { AlertEngine.runGoldNightChecks(WatchService.this); } catch (Throwable ignored) { }
                }).start();
                handler.postDelayed(this, GOLD_TICK_MS);
            }
        }
    };

    /** Faster checks in the busy opening window (v41): every 30s
     *  from 09:25 to 11:35 IST, every 60s otherwise - alerts land up
     *  to half a minute sooner when the day's breaks cluster. */
    private static long nextDelayMs() {
        java.time.LocalTime t = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        boolean peak = !t.isBefore(java.time.LocalTime.of(9, 25))
                && t.isBefore(java.time.LocalTime.of(11, 35));
        return peak ? 30000 : 60000;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    "Market watchdog", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("IraThinker is watching the market (08:30-24:00) and gold overnight");
            nm.createNotificationChannel(ch);
        }
        startForeground(1, buildNotification("Watching · alerts 08:30–24:00 · gold overnight"));
        handler.post(tick);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("IraThinker")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
