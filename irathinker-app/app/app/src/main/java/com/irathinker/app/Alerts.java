package com.irathinker.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/** Background alert cadence: a self-rescheduling JobScheduler chain.
 *  Every 5 minutes during the market window; otherwise it sleeps until
 *  the next trading morning (09:05 IST). */
public final class Alerts {

    public static final int JOB_ID = 42;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime WINDOW_START = LocalTime.of(9, 5);
    private static final LocalTime WINDOW_END = LocalTime.of(15, 35);

    public static void scheduleNext(Context ctx) {
        Prefs prefs = new Prefs(ctx);
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!prefs.alertsOn()) { js.cancel(JOB_ID); return; }
        long delayMs = computeDelayMs();
        JobInfo info = new JobInfo.Builder(JOB_ID,
                new ComponentName(ctx, AlertJobService.class))
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs + 90000)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        js.schedule(info);
    }

    public static void cancel(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID);
    }

    private static long computeDelayMs() {
        LocalDateTime now = LocalDateTime.now(IST);
        boolean weekday = now.getDayOfWeek().getValue() <= 5;
        LocalTime t = now.toLocalTime();
        if (weekday && !t.isBefore(WINDOW_START) && !t.isAfter(WINDOW_END)) {
            return 60 * 1000L; // in the window: next check in 1 minute
        }
        // next weekday 09:05
        LocalDateTime next = now.toLocalDate().atTime(WINDOW_START);
        if (!weekday || !t.isBefore(WINDOW_START)) {
            do { next = next.plusDays(1); } while (next.getDayOfWeek().getValue() > 5);
        }
        return Math.max(60000L, java.time.Duration.between(now, next).toMillis());
    }
}
