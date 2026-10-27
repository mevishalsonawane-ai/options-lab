package com.optionslab.app.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.optionslab.ira.AnalysisRest
import com.optionslab.ira.BatterySaver
import com.optionslab.ira.PowerCache

/**
 * Battery saver (Jarvis, the owner's wish 2026-10-02): when the battery is low and not charging, Jarvis's own
 * background work (its listening keeper, the live position cards) refreshes less often. Stops, targets, exits and the
 * minute watch never slow down.
 *
 * Battery audit, 27 Oct: the battery, Android's battery saver and the screen are read from Android at most once every
 * [TTL_MS] ([PowerCache]); before, the loops asked Android each time (the live cards every 3 s, the recorders, the workers'
 * waits), one of the "binder traffic" sources Android killed the app for on 25 Oct. A charge level 30 s old changes nothing
 * here: the stops never read it.
 */
object Battery {
    /** How old a reading may be. */
    const val TTL_MS = 30_000L

    @Volatile private var app: Context? = null

    /** One reading of the sticky battery broadcast: percent, charging, temperature (tenths of a degree; null unknown). */
    data class Reading(val percent: Int?, val charging: Boolean, val tempTenths: Int?)

    private val battery = PowerCache(TTL_MS, System::currentTimeMillis) {
        readState(app).also { r ->
            // The "Battery today" line's minutes at 30% or less (memory only).
            runCatching { BatteryMeter.meter.set(com.optionslab.ira.LoopMeter.LOW_BATTERY, AnalysisRest.low(r.percent, r.charging)) }
        }
    }
    private val thermalCache = PowerCache(TTL_MS, System::currentTimeMillis) {
        if (android.os.Build.VERSION.SDK_INT >= 29) app?.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: 0 else 0
    }
    private val saverCache = PowerCache(TTL_MS, System::currentTimeMillis) {
        app?.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
    }
    private val screenCache = PowerCache(TTL_MS, System::currentTimeMillis) {
        app?.getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true
    }

    /** The app's context (a new one - a test's new application - reads Android again at once). */
    private fun bind(context: Context) {
        val c = context.applicationContext ?: context
        if (c === app) return
        app = c
        battery.clear(); saverCache.clear(); screenCache.clear(); thermalCache.clear()
    }

    /** The real reading, from the sticky battery broadcast (no receiver is kept). */
    private fun readState(context: Context?): Reading = runCatching {
        val i = context?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return@runCatching Reading(null, false, null)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        Reading(if (level >= 0 && scale > 0) level * 100 / scale else null, charging, temp)
    }.getOrDefault(Reading(null, false, null))

    /** The whole reading (percent, charging, temperature), at most [TTL_MS] old. */
    fun reading(context: Context): Reading { bind(context); return battery.get() }

    /** (percent, charging), at most [TTL_MS] old. */
    fun state(context: Context): Pair<Int?, Boolean> = reading(context).let { it.percent to it.charging }

    /** Android's thermal status (0 none ... 6 shutdown; 0 before Android 10 or unknown), at most [TTL_MS] old. */
    fun thermal(context: Context?): Int {
        if (context == null) return 0
        bind(context)
        return runCatching { thermalCache.get() }.getOrDefault(0)
    }

    /** Android's battery saver is on (at most [TTL_MS] old). */
    fun powerSave(context: Context?): Boolean {
        if (context == null) return false
        bind(context)
        return runCatching { saverCache.get() }.getOrDefault(false)
    }

    /** The screen is on (at most [TTL_MS] old; unknown: on). */
    fun screenOn(context: Context?): Boolean {
        if (context == null) return true
        bind(context)
        return runCatching { screenCache.get() }.getOrDefault(true)
    }

    /** [normalMs], or longer while the battery is low and not charging (Jarvis only). */
    fun gap(context: Context?, normalMs: Long): Long {
        if (context == null || !com.optionslab.app.BuildConfig.JARVIS) return normalMs
        val (p, c) = state(context)
        return BatterySaver.gap(normalMs, p, c)
    }

    fun saving(context: Context?): Boolean = context != null && com.optionslab.app.BuildConfig.JARVIS && state(context).let { BatterySaver.saving(it.first, it.second) }

    /**
     * Battery audit, 27 Oct: the pace of the knowledge/words-only analysis ([AnalysisRest.pace]) - always FULL while [held].
     * The order watch, the stop lane and the stream for held instruments never call this.
     */
    fun analysisPace(context: Context?, held: Boolean): AnalysisRest.Pace {
        if (held) return AnalysisRest.Pace.FULL
        testPace?.let { return it }
        if (context == null) return AnalysisRest.Pace.FULL
        val (p, c) = state(context)
        return AnalysisRest.pace(held = false, screenOn = screenOn(context), saver = powerSave(context), percent = p, charging = c)
    }

    /** How many real reads of Android were made in this process (battery, saver, screen): the diagnostics' battery line. */
    fun reads(): Int = battery.reads + saverCache.reads + screenCache.reads + thermalCache.reads

    /** TEST ONLY: the pace with nothing held, as the test says (null: the phone's). */
    @Volatile internal var testPace: AnalysisRest.Pace? = null

    /** TEST ONLY: forget the kept readings (a test changes the battery). */
    internal fun resetForTest() { battery.clear(); saverCache.clear(); screenCache.clear(); thermalCache.clear(); testPace = null }
}
