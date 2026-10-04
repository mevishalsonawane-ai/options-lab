package com.optionslab.app.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.optionslab.ira.BatterySaver

/**
 * Battery saver (Jarvis, the owner's wish 2026-10-02): when the battery is low and not charging, Jarvis's own
 * background work (its listening keeper, the live position cards) refreshes less often. Stops, targets, exits and the
 * minute watch never slow down.
 */
object Battery {
    /** (percent, charging), from the sticky battery broadcast (no receiver is kept). */
    fun state(context: Context): Pair<Int?, Boolean> = runCatching {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return@runCatching null to false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        (if (level >= 0 && scale > 0) level * 100 / scale else null) to charging
    }.getOrDefault(null to false)

    /** [normalMs], or longer while the battery is low and not charging (Jarvis only). */
    fun gap(context: Context?, normalMs: Long): Long {
        if (context == null || !com.optionslab.app.BuildConfig.JARVIS) return normalMs
        val (p, c) = state(context)
        return BatterySaver.gap(normalMs, p, c)
    }

    fun saving(context: Context?): Boolean = context != null && com.optionslab.app.BuildConfig.JARVIS && state(context).let { BatterySaver.saving(it.first, it.second) }
}
