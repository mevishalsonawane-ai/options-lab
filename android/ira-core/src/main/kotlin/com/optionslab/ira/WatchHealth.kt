package com.optionslab.ira

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The order watch's health, explained (2026-10-05). Boss, Pixel 9, Paper mode, 09:35: "Order watch stopped: No check
 * since 09:31" while paper positions were open, with the relay server down (every connect timing out at port 22) and the
 * live price stream dropping. The watch's beat was stamped only between passes, and one pass ran ~35 steps in a row -
 * the relay's 15 s SSH connect among them, every minute, Paper mode or not - so a pass that waited on the network for
 * more than three minutes looked exactly like a dead watch, and nobody could tell which it was or why.
 *
 * Pure: the app hands in its clocks and what the watch was doing, and gets back
 *  - the watch's [State]: [State.OK], [State.BUSY] (the service is alive - its pulse is fresh - but a check is waiting on
 *    something) or [State.DEAD] (no pulse: the service or the app's process ended),
 *  - the notification's title and text ([title], [notice]), which say plainly "set IraAlgo's battery to Unrestricted"
 *    when Android is battery-optimizing the app,
 *  - the diagnostics diary's lines ([stall], [failure], [restart], [ended]) - class names and the app's own step names
 *    only, never an exception's message, a URL or a key ([cleanClass]),
 *  - the relay warm-up's back-off ([warmGapMs]) and the diagnostics' "Order watch:" line ([statusLine]),
 *  - the morning check's battery line ([BATTERY_FIX], [isBatteryFix]).
 */
object WatchHealth {
    /** The diagnostics diary's area for the watch's lines. */
    const val AREA = "watch"

    /** No beat for this long in market hours: the watch is not checking. */
    const val STALE_MS = 3 * 60_000L

    /** The service's own pulse (independent of any network call) is stamped this often... */
    const val PULSE_MS = 30_000L

    /** ...so a pulse older than this means the service (or the app's process) is gone. */
    const val ALIVE_MS = 3 * PULSE_MS

    /** The relay warm-up after a failed connect: 2, 4, 8 then 10 minutes between tries (the server is down). */
    const val WARM_FIRST_MS = 2 * 60_000L
    const val WARM_MAX_MS = 10 * 60_000L

    enum class State { OK, BUSY, DEAD }

    /** The watch's state at [now], from its last beat (a finished check) and its last pulse (the service alive). */
    fun state(now: Long, lastBeat: Long, alivePulse: Long): State = when {
        lastBeat > 0 && now - lastBeat in 0..STALE_MS -> State.OK
        alivePulse > 0 && now - alivePulse in 0..ALIVE_MS -> State.BUSY
        else -> State.DEAD
    }

    fun title(s: State): String = if (s == State.BUSY) "Order watch is stuck" else "Order watch stopped"

    private const val NOT_WATCHED = "Stops, targets and strategy exits are not being watched."

    /** The plain fix when Android battery-optimizes the app (the notification and the morning check). */
    const val BATTERY_ASK = "Boss, Android is limiting IraAlgo in the background: set IraAlgo's battery to Unrestricted (tap to open the setting)."

    /**
     * The notification's text. [since]: the last check's time today ("09:31"), or null when there was none today.
     * [busyStep]/[busySec]: what a [State.BUSY] watch has been waiting on, and for how long.
     */
    fun notice(s: State, since: String?, busyStep: String?, busySec: Long?, batteryRestricted: Boolean): String {
        val head = if (since != null) "No check since $since. " else "The watch has not run today. "
        val body = if (s == State.BUSY)
            "The watch is running but has waited ${busySec?.let { mins(it) } ?: "minutes"} on ${busyStep ?: "a network call"}; " +
                "stops, targets and strategy exits are not being checked until it answers."
        else NOT_WATCHED
        val tail = if (batteryRestricted) BATTERY_ASK
            else if (s == State.BUSY) "Tap to open IraAlgo." else "Tap to open IraAlgo and restart it."
        return "$head$body $tail"
    }

    private fun mins(sec: Long): String = if (sec < 90) "$sec s" else "${sec / 60} min"

    /** The diary's line for a stall (once per stall, as the notification). */
    fun stall(s: State, since: String?, busyStep: String?, busySec: Long?, batteryRestricted: Boolean): String =
        (if (s == State.BUSY) "STUCK: alive but no finished check" else "STOPPED: no pulse from the watch service") +
            " · last check ${since ?: "none today"}" +
            (if (s == State.BUSY) " · waiting on ${busyStep ?: "unknown"}${busySec?.let { " for ${it}s" }.orEmpty()}" else "") +
            " · battery ${if (batteryRestricted) "OPTIMIZED (not Unrestricted)" else "unrestricted"}"

    private val CLASS_OK = Regex("[^A-Za-z0-9_.$]")

    /** An exception's class name only (never its message, which can carry a URL or a token), at most 80 characters. */
    fun cleanClass(name: String?): String = name.orEmpty().substringAfterLast('.').replace(CLASS_OK, "").take(80).ifEmpty { "unknown" }

    /** The diary's line for a pass that threw: where (the app's own step name) and the class only. */
    fun failure(step: String?, errorClass: String?): String =
        "a check failed${step?.let { " in $it" }.orEmpty()}: ${cleanClass(errorClass)} (the watch goes on)"

    /** Why the watch ended, for the diary. */
    enum class End { MARKET_CLOSED, STOPPED_BY_BOSS, ANDROID_TIME_LIMIT, FOREGROUND_REFUSED, SERVICE_DESTROYED, CRASHED }

    fun ended(why: End, detail: String? = null): String = "ended: " + when (why) {
        End.MARKET_CLOSED -> "the market closed (normal)"
        End.STOPPED_BY_BOSS -> "stopped from the notification or the app"
        End.ANDROID_TIME_LIMIT -> "Android's time limit for background work${detail?.let { " ($it)" }.orEmpty()}"
        End.FOREGROUND_REFUSED -> "Android refused to keep it in the foreground"
        End.SERVICE_DESTROYED -> "Android destroyed the watch service while it ran"
        End.CRASHED -> "it threw ${cleanClass(detail)}"
    }

    /**
     * The diary's line when the watch starts again: [runDay] is the day a watch was last running and did not end
     * cleanly (null: it did end cleanly). Null when there is nothing to tell.
     */
    fun restart(runDay: String?, today: String, crashSaved: Boolean, byAndroid: Boolean): String? {
        if (runDay != today) return if (byAndroid) "restarted by Android" else null
        return "restarted: the app's process had ended while the watch ran (" +
            (if (crashSaved) "the app crashed - a crash report was saved" else "Android closed the app, or it crashed") + ")" +
            (if (byAndroid) " · restarted by Android" else "")
    }

    /** The wait before the next relay warm-up after [fails] failed connects in a row (0: warm now). */
    fun warmGapMs(fails: Int): Long = if (fails <= 0) 0L else minOf(WARM_MAX_MS, WARM_FIRST_MS shl minOf(fails - 1, 4))

    private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** The diagnostics' line: last finished check, the service's pulse, what it waits on, the battery setting. */
    fun statusLine(now: Long, lastBeat: Long, alivePulse: Long, busyStep: String?, busySince: Long, batteryRestricted: Boolean?, zone: ZoneId): String {
        fun at(ms: Long) = if (ms <= 0) "never" else HMS.withZone(zone).format(Instant.ofEpochMilli(ms))
        val alive = alivePulse > 0 && now - alivePulse in 0..ALIVE_MS
        return "Order watch: last check ${at(lastBeat)} · service ${if (alive) "running" else "not running"}" +
            (if (alive && busyStep != null && busySince > 0) " · now on $busyStep for ${(now - busySince) / 1000}s" else "") +
            " · battery " + when (batteryRestricted) { true -> "OPTIMIZED (set IraAlgo's battery to Unrestricted)"; false -> "unrestricted"; null -> "unknown" }
    }

    /** The morning check's failing line when the app is battery-optimized. */
    const val BATTERY_FIX = "Battery: IraAlgo is battery-optimized, so Android can stop the order watch: set IraAlgo's battery to Unrestricted"
    const val BATTERY_OK = "Battery: IraAlgo is Unrestricted"

    fun isBatteryFix(line: String): Boolean = line.removePrefix("✗ ").startsWith("Battery: IraAlgo is battery-optimized")
}
