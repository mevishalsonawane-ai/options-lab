package com.optionslab.app.work

import android.content.Context
import com.optionslab.ira.BatteryUse

/**
 * Battery, round 1: what of the app runs in the background now ([BatteryUse.Snapshot]) - for the diagnostics' "Battery:"
 * line and Jarvis's "why is the app using battery?". Reads the app's own state only; nothing is switched.
 */
object BatteryNow {
    fun snapshot(context: Context?): BatteryUse.Snapshot {
        val jarvis = com.optionslab.app.BuildConfig.JARVIS
        val (percent, charging) = context?.let { Battery.state(it) } ?: (null to false)
        val step = WatchService.stepSec
        val cost = if (jarvis) runCatching { com.optionslab.app.ira.JarvisVoice.listenCost() }.getOrNull() else null
        return BatteryUse.Snapshot(
            listenMinutes = cost?.first,
            turnsLastHour = cost?.second,
            listening = jarvis && runCatching { com.optionslab.app.ira.JarvisVoice.listeningNow() }.getOrDefault(false),
            listenSaver = jarvis && runCatching { com.optionslab.app.ira.JarvisVoice.listenSaver }.getOrDefault(false),
            resting = jarvis && runCatching { com.optionslab.app.ira.JarvisVoice.restingNow() }.getOrDefault(false),
            watch = step > 0 || Heartbeat.alivePulse > 0,
            watchStepSec = step.takeIf { it > 0 },
            wordsQuiet = runCatching { Tasks.wordsQuietNow() }.getOrNull(),
            newsAskedToday = jarvis && runCatching { com.optionslab.app.ira.IraHub.newsAskedToday() }.getOrDefault(false),
            stream = runCatching { com.optionslab.app.data.KiteStream.status.value.name }.getOrDefault("OFF"),
            streamTokens = runCatching { com.optionslab.app.data.KiteStream.following() }.getOrDefault(0),
            modelLoaded = jarvis && runCatching { com.optionslab.app.ira.IraModel.state.value.loaded }.getOrDefault(false),
            marketOpen = runCatching { com.optionslab.app.data.Market.isOpen() }.getOrDefault(false),
            batteryPercent = percent,
            charging = charging,
        )
    }

    /** The diagnostics' line. */
    fun line(context: Context?): String = runCatching { BatteryUse.line(snapshot(context)) }.getOrElse { "Battery: could not read" }
}
