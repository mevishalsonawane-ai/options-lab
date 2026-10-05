package com.optionslab.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.optionslab.app.data.GoldPaper
import com.optionslab.engine.gold.GoldLiquidity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * IraGoldAlgo's clock: a pass of [GoldPaper] 30 seconds after every 5th minute while gold trades (24x5: Monday to
 * Friday UTC, but for the 21:00-22:00 break), so a 1-hour candle is decided half a minute after it closes and an exit is
 * caught within five minutes. Over the break and the weekend the next alarm is the reopening. Each alarm sets the next.
 */
object GoldAlarm {
    private const val ACTION = "ol.gold.pass"

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val at = next(LocalDateTime.now(ZoneOffset.UTC)).toInstant(ZoneOffset.UTC).toEpochMilli()
        val pi = PendingIntent.getBroadcast(context, 7100, Intent(context, GoldReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            if (Jobs.canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** The next pass after [now] (UTC): 30 s past the next 5-minute mark inside the session, else the next session's start. */
    fun next(now: LocalDateTime): LocalDateTime {
        var t = now.withSecond(30).withNano(0)
        t = t.plusMinutes((5 - t.minute % 5).toLong())
        repeat(8 * 24 * 12) {
            if (GoldLiquidity.inSession(t)) return t
            t = t.plusMinutes(5)
        }
        return t
    }
}

class GoldReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { GoldPaper.tick() } finally {
                runCatching { GoldAlarm.schedule(context) }
                // The alarm is the backup: it (re)starts the always-on service whenever it is needed and not running.
                runCatching { GoldService.ensure(context) }
                pending.finish()
            }
        }
    }
}

/**
 * IraGoldAlgo running in the background, always (the owner's ask, 2026-10-02): a foreground service with one quiet
 * ongoing notification that runs the pass every minute while either arm is armed or holds a trade and gold trades.
 * Android keeps a foreground service alive where it delays or drops alarms; the 5-minute alarm stays as the backup
 * that starts it again (after a kill, a reboot, or the weekend). It stops itself when nothing is armed or held, and
 * over the daily break and the weekend; START_STICKY asks Android to restart it if it is killed.
 */
class GoldService : android.app.Service() {
    companion object {
        const val ID = 7200
        private val _running = kotlinx.coroutines.flow.MutableStateFlow(false)
        /** Whether the service is running now (Settings shows it). */
        val running: kotlinx.coroutines.flow.StateFlow<Boolean> = _running

        /** Is there anything for the service to do now? An arm armed or a trade held, while gold trades. */
        fun needed(now: LocalDateTime = GoldPaper.now()): Boolean {
            val liq = GoldPaper.book.value
            val tr = com.optionslab.app.data.GoldTrendPaper.book.value
            val dp = com.optionslab.app.data.GoldDipPaper.book.value
            val ts = com.optionslab.app.data.GoldTasPaper.book.value
            return (liq.armed || liq.position != null || tr.armed || tr.position != null || dp.armed || dp.position != null ||
                ts.armed || ts.position != null) && GoldLiquidity.inSession(now)
        }

        /** Start it when it is needed and not running (from the screen, the alarm or a reboot). Never throws. */
        fun ensure(context: Context) {
            if (!com.optionslab.app.BuildConfig.GOLD || _running.value || !needed()) return
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, GoldService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    private fun text(): Pair<String, String> {
        val b = GoldPaper.book.value
        val tr = com.optionslab.app.data.GoldTrendPaper.book.value
        val dp = com.optionslab.app.data.GoldDipPaper.book.value
        val ts = com.optionslab.app.data.GoldTasPaper.book.value
        val arms = listOfNotNull(if (b.armed) "Liquidity 1h" else null, if (tr.armed) com.optionslab.app.data.GoldTrendPaper.NAME else null,
            if (dp.armed) com.optionslab.app.data.GoldDipPaper.NAME else null, if (ts.armed) com.optionslab.app.data.GoldTasPaper.NAME else null)
        val held = listOfNotNull(b.position?.let { "Liquidity buy" }, tr.position?.let { "Trend buy" }, dp.position?.let { "Dip buy" }, ts.position?.let { "TAS buy" })
        val title = "IraGoldAlgo running" + (b.price?.let { " · gold %,.2f".format(java.util.Locale.ENGLISH, it) } ?: "")
        val line = (if (arms.isEmpty()) "No arm armed" else "Armed: " + arms.joinToString(", ")) +
            (if (held.isEmpty()) "" else " · holding: " + held.joinToString(", ")) + " · paper"
        return title to line
    }

    /**
     * The notice last shown: re-posted when its words change (Battery, round 2 - it was re-posted every minute), and at
     * least every [RESHOW_MS] even unchanged - on Android 14+ a foreground notice can be swiped away, and only a re-post
     * brings it back.
     */
    private var shown: Pair<String, String>? = null
    /** When the notice was last posted (elapsed realtime). */
    private var shownAt = 0L
    private val RESHOW_MS = 15 * 60_000L

    private fun show(): Boolean {
        val (title, line) = text()
        shown = title to line
        shownAt = android.os.SystemClock.elapsedRealtime()
        val n = Notifier.builder(this, Notifier.GOLD_BG, title, line)
            .setOngoing(true).setOnlyAlertOnce(true).setAutoCancel(false).setSilent(true).build()
        val type = if (android.os.Build.VERSION.SDK_INT >= 34) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return try {
            androidx.core.app.ServiceCompat.startForeground(this, ID, n, type); true
        } catch (_: Exception) {
            // Android refused the foreground now: stop cleanly; the 5-minute alarm carries on and tries again.
            runCatching { com.optionslab.app.data.Diag.record("gold", "background service refused by Android; the 5-minute alarm carries on") }
            stopSelf(); false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!show()) return START_NOT_STICKY
        _running.value = true
        if (loop?.isActive == true) return START_STICKY
        loop = scope.launch {
            try {
                while (true) {
                    if (!needed()) break
                    runCatching { GoldPaper.tick() }
                    runCatching { if (text() != shown || android.os.SystemClock.elapsedRealtime() - shownAt >= RESHOW_MS) show() }
                    // 30 s past each minute, as the alarm's pass (the feed's last minute is in by then).
                    val now = LocalDateTime.now(ZoneOffset.UTC)
                    val next = now.withSecond(30).withNano(0).let { if (it.isAfter(now)) it else it.plusMinutes(1) }
                    kotlinx.coroutines.delay(java.time.Duration.between(now, next).toMillis().coerceAtLeast(1_000))
                }
            } finally {
                _running.value = false
                runCatching { GoldAlarm.schedule(this@GoldService) }
                androidx.core.app.ServiceCompat.stopForeground(this@GoldService, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        scope.cancel()
        super.onDestroy()
    }
}
