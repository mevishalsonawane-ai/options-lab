package com.optionslab.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.optionslab.app.data.Diag
import com.optionslab.app.data.Market
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.WatchHealth

/**
 * The dead-man alert. The market watch stamps a heartbeat each pass; a
 * separate alarm looks at it every five minutes of market hours. When the
 * watch has gone quiet for more than three minutes (killed by the system, a
 * hung network call, a crash), the check restarts it and tells the owner once
 * per stall: stops, targets and strategy exits are only checked while it runs.
 *
 * Two clocks (2026-10-05): the beat says a check FINISHED; the pulse ([pulse], every 30 s from the service's own
 * coroutine, no network) says the service is ALIVE. A stale beat with a fresh pulse is a watch stuck on something
 * ([busy] names it), not a dead one - the notice and the diagnostics diary say which, and why.
 */
object Heartbeat {
    const val ACTION = "ol.heartbeat"
    private const val KEY = "hb.last"
    private const val ALERTED = "hb.alerted"
    private const val STALLED = "hb.stalled.day"

    /** The watch went silent at least once today (for the day report). */
    fun stalledToday(): Boolean = SecurePrefs.getString(STALLED) == Market.today().toString()
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

    /** The service's own pulse (this process only: a new process starts at 0, which reads as "not running"). */
    @Volatile var alivePulse: Long = 0L
        private set
    /** What the watch is doing now (the app's own step name, never data) and since when. */
    @Volatile var busy: String? = null
        private set
    @Volatile var busySince: Long = 0L
        private set

    fun pulse() { alivePulse = System.currentTimeMillis() }
    fun pulseStopped() { alivePulse = 0L; busy = null; busySince = 0L }

    fun stepBegin(name: String) { busy = name; busySince = System.currentTimeMillis() }
    fun stepEnd() { busy = null; busySince = 0L }

    /** Is the app battery-optimized (Android may stop the watch)? Null when it cannot be told. */
    fun batteryRestricted(context: Context): Boolean? =
        runCatching { !com.optionslab.app.ui.screens.BatteryCheck.unrestricted(context) }.getOrNull()

    /** The diagnostics' "Order watch:" line. */
    fun statusLine(context: Context?): String = WatchHealth.statusLine(System.currentTimeMillis(), last(), alivePulse, busy, busySince,
        context?.let { batteryRestricted(it) }, Market.now().zone)

    /** One line in the diagnostics diary (words the app chose; [Diag] redacts anything secret-looking anyway). */
    fun diary(text: String) = runCatching { Diag.record(WatchHealth.AREA, text) }

    /** Checks only make sense from a couple of minutes after the open to the close. */
    private fun inHours(): Boolean = Market.isTradingDay() && Market.minuteNow() in (Market.OPEN + 2) until Market.CLOSE

    /** The same condition [Jobs] schedules the market watch on. */
    private fun watched(): Boolean = Jobs.enabled(Jobs.Kind.LIVE, com.optionslab.app.data.AppSettings.load())

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
        // Armed whenever the watch is scheduled - paper bots run in it with no Zerodha account too.
        if (!watched()) return
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
        if (!watched() || !stale()) return
        val now = System.currentTimeMillis()
        val state = WatchHealth.state(now, last(), alivePulse).let { if (it == WatchHealth.State.OK) WatchHealth.State.DEAD else it }
        // An exact alarm may start the foreground service; this usually brings a dead watch straight back (a stuck one
        // is still running: the start is a no-op for it).
        runCatching { Jobs.ensureWatch(context) }
        val day = Market.today().toString()
        if (SecurePrefs.getString(ALERTED) == day) return
        SecurePrefs.putAll(mapOf(ALERTED to day, STALLED to day))
        val lastAt = java.time.Instant.ofEpochMilli(last()).atZone(Market.now().zone)
        val since = if (lastAt.toLocalDate() == Market.today()) lastAt.toLocalTime().withSecond(0).withNano(0).toString() else null
        val step = busy
        val busySec = busySince.takeIf { it > 0 }?.let { (now - it) / 1000 }
        val restricted = batteryRestricted(context) == true
        diary(WatchHealth.stall(state, since, step, busySec, restricted))
        // Battery-optimized or not, the tap opens Settings → Schedule → Permissions (its Battery row); nothing is changed by itself.
        Notifier.post(context, NOTE_ID, Notifier.APPROVAL, WatchHealth.title(state),
            WatchHealth.notice(state, since, step, busySec, restricted), "almanac",
            setting = "schedule.permissions")
        // Once per stall, as the notification: Jarvis says in the chat which open positions and stops are now unwatched,
        // and what keeps the phone from stopping the watch again. Only speaks (round 21). Off the main thread already
        // (the alarm receiver's IO scope); bounded under goAsync's ~10 s window so a hung read never holds the receiver.
        runCatching {
            val at = if (since != null) lastAt.toLocalTime() else null
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(8_000) { com.optionslab.app.ira.IraWatchStopped.tell(context, at) }
            }
        }
    }
}
