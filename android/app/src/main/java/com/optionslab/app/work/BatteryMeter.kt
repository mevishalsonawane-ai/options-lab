package com.optionslab.app.work

import com.optionslab.ira.LoopMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Battery, 14 Oct: which background loops ran today and for how long ([LoopMeter]) - the evening watch's minutes after
 * 15:40 and its 15 s / 60 s MCX passes, the tick recorder's minutes, the price stream's minutes on, the order-flow pump's,
 * Jarvis's resting minutes. Shown as one "Battery today:" line in the diagnostics and on the Health page, so Boss can see
 * on the phone that the evening work rests. Memory only (a restart counts again); never on the order or tick path: the
 * loops note a start or an end on their own threads, and the stream's state is collected here (no polling).
 */
object BatteryMeter {
    val meter = LoopMeter(dayOf = { Instant.ofEpochMilli(it).atZone(com.optionslab.engine.IST).toLocalDate() })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    /** At the app's start: the stream's minutes on, from its own state (a collector suspends between changes). */
    fun init() {
        if (started) return
        started = true
        scope.launch {
            runCatching {
                com.optionslab.app.data.KiteStream.status.collect { meter.set(LoopMeter.STREAM, it == com.optionslab.app.data.KiteStream.Status.LIVE) }
            }
        }
    }

    /** The diagnostics' and the Health page's line. */
    fun line(): String = runCatching { LoopMeter.line(meter) }.getOrElse { "Battery today: could not read" }
}
