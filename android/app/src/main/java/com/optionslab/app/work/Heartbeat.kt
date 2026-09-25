package com.optionslab.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.optionslab.app.data.Market
import com.optionslab.app.security.SecurePrefs

/**
 * The dead-man alert. The market watch stamps a heartbeat each pass; a
 * separate alarm looks at it every five minutes of market hours. When the
 * watch has gone quiet for more than three minutes (killed by the system, a
 * hung network call, a crash), the check restarts it and tells the owner once
 * per stall: stops, targets and strategy exits are only checked while it runs.
 */
object Heartbeat {
    const val ACTION = "ol.heartbeat"
    private const val KEY = "hb.last"
    private const val ALERTED = "hb.alerted"
    private const val STALE_MS = 3 * 60_000L
    private const val EVERY_MS = 5 * 60_000L
    private const val NOTE_ID = 2014

    /** Called by the watch on every pass, including while it waits for the open. */
    fun beat(context: Context) {
        SecurePrefs.put(KEY, System.currentTimeMillis())
        if (SecurePrefs.getString(ALERTED) != null) {
            SecurePrefs.put(ALERTED, null)
            runCatching { androidx.core.app.NotificationManagerCompat.from(context).cancel(NOTE_ID) }
        }
    }

    fun last(): Long = SecurePrefs.getLong(KEY, 0L)

    /** Checks only make sense from a couple of minutes after the open to the close. */
    private fun inHours(): Boolean = Market.isTradingDay() && Market.minuteNow() in (Market.OPEN + 2) until Market.CLOSE

    fun stale(): Boolean = inHours() && System.currentTimeMillis() - last() > STALE_MS

    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 190, Intent(context, AlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The next check: five minutes from now in market hours, else 09:20 on the next trading day. */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = intent(context)
        am.cancel(pi)
        if (!com.optionslab.app.data.Broker.linked) return
        val now = Market.now()
        val at = if (Market.isTradingDay() && Market.minuteNow() < Market.CLOSE && Market.minuteNow() >= Market.OPEN) {
            System.currentTimeMillis() + EVERY_MS
        } else {
            var d = now.toLocalDate()
            if (Market.minuteNow() >= Market.OPEN) d = d.plusDays(1)
            while (!Market.isTradingDay(d)) d = d.plusDays(1)
            d.atTime(9, 20).atZone(now.zone).toInstant().toEpochMilli()
        }
        try {
            if (Jobs.canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** The alarm fired: re-arm, and if the watch is silent, restart it and say so once. */
    fun check(context: Context) {
        schedule(context)
        if (!com.optionslab.app.data.Broker.linked || !stale()) return
        // An exact alarm may start the foreground service; this usually brings it straight back.
        runCatching { Jobs.ensureWatch(context) }
        val day = Market.today().toString()
        if (SecurePrefs.getString(ALERTED) == day) return
        SecurePrefs.put(ALERTED, day)
        val lastAt = java.time.Instant.ofEpochMilli(last()).atZone(Market.now().zone)
        val since = if (lastAt.toLocalDate() == Market.today()) lastAt.toLocalTime().withSecond(0).withNano(0).toString() else null
        Notifier.post(context, NOTE_ID, Notifier.APPROVAL, "Market watch stopped",
            (if (since != null) "No check since $since. " else "The watch has not run today. ") +
                "Stops, targets and strategy exits are not being watched. Tap to open IraAlgo and restart it.", "almanac")
    }
}
