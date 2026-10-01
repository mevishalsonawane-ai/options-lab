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
            try { GoldPaper.tick() } finally { runCatching { GoldAlarm.schedule(context) }; pending.finish() }
        }
    }
}
