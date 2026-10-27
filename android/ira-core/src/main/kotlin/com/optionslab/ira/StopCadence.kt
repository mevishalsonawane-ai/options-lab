package com.optionslab.ira

import java.time.LocalDate
import kotlinx.coroutines.isActive

/**
 * 20 Oct, enhancement round 1 (idea 6): the real gap between two looks at the stops while anything is held.
 *
 * The watch promises a look every 15 s while holding. Before, each wait began only after the last check FINISHED, so a check
 * that took 3 s made the gap 18 s, and nothing measured it. Now:
 *  - [windowEnd]: the next look is due [WINDOW_MS] after the last one STARTED (at least [MIN_GAP_MS] from now, so a slow check
 *    never spins the processor). This only ever brings a look sooner.
 *  - [Drift]: each look's start is noted; a gap over [DRIFT_MS] between two looks that both had something held is a drift.
 *    It is counted for the day and logged; one at or over [ALERT_MS] is also shown to Boss.
 * Words and timing only: nothing here skips, places or changes an order. Pure (the caller passes the clock).
 */
object StopCadence {
    const val WINDOW_MS = 15_000L
    const val DRIFT_MS = 16_000L
    const val ALERT_MS = 30_000L
    /** Review: a slow check (a slow broker) is followed after a short rest, never back to back (which would load the broker more). */
    const val MIN_GAP_MS = 5_000L

    /**
     * When the next look is due: [windowMs] after the last look started ([lastStartMs]; 0 or in the future: unknown, so a full
     * window from now), never sooner than [MIN_GAP_MS] from [nowMs] (never later than the old way: 15 s after the last ended).
     */
    // 22 Oct: [minGapMs] - the stop lane ([Ticker]) rests [LANE_REST_MS] after a slow look, not 5 s (its looks are bounded and
    // never wait on the full pass, so a 5 s rest would only stretch the gap).
    fun windowEnd(lastStartMs: Long, nowMs: Long, windowMs: Long = WINDOW_MS, minGapMs: Long = MIN_GAP_MS): Long =
        if (lastStartMs <= 0L || lastStartMs > nowMs) nowMs + windowMs else minOf(nowMs + windowMs, maxOf(nowMs + minGapMs, lastStartMs + windowMs))

    /** 22 Oct: how often the stop lane re-reads "is anything held?" (memory only) and its stop flag. */
    const val LANE_POLL_MS = 1_000L
    /** 22 Oct: the stop lane's rest after a look that ran past its window (a slow broker is still never hit back to back). */
    const val LANE_REST_MS = 1_000L
    /** 22 Oct: how long past its 15 s the stop lane waits for another look that runs on its own clock (MCX's pass) to start. */
    const val OUTSIDE_GRACE_MS = 1_000L

    /**
     * 22 Oct (the 21 Oct diagnostics: "the stops were looked at N s or more apart while a position was held", twice, with the
     * phone hot and at 15% battery): the stop lane's own clock. The 15-second stop check ran INSIDE the watch loop, after the
     * full pass (p95 5.5 s, max 20.2 s) and after each bar-close entry check (which waited up to 45 s for the arms), so either
     * pushed the look past 15 s. Now the look runs on its own coroutine, on this clock, beside them and never after them:
     *  - while [run]'s `held` says something is held, a look starts every [windowMs], timed from the last look's START;
     *  - a look that itself ran past the window is followed [restMs] after it ends (never back to back);
     *  - nothing held: no look at all, `held` re-read every [pollMs] (a new position gets its first look within [pollMs]); the
     *    first time nothing is held after a held look, `idle` runs once (the drift guard starts its count again);
     *  - a wall clock moved back is never waited out: a due time more than one window ahead is due now.
     * No battery, saver, heat or memory input: nothing here can stretch the wait. Pure (the caller passes the clock and the sleep).
     */
    class Ticker(
        private val now: () -> Long,
        private val sleep: suspend (Long) -> Unit,
        private val windowMs: Long = WINDOW_MS,
        private val pollMs: Long = LANE_POLL_MS,
        private val restMs: Long = LANE_REST_MS,
        /**
         * 25 Oct (Boss's decision on the app audit, H4): whether the held positions' prices are quiet now (the stream has no fresh
         * tick for them), read before every wait (memory only). While quiet, the window is [quietWindowMs] (5 s), not [windowMs]:
         * it only ever adds looks, never removes one. Off (false) by default: the old 15 s clock exactly.
         */
        private val quiet: () -> Boolean = { false },
        private val quietWindowMs: Long = QUIET_WINDOW_MS,
        /** Each look's start and whether it was a quiet one (the quiet-gap measure, [QuietGaps]). */
        private val started: (Long, Boolean) -> Unit = { _, _ -> },
    ) {
        /** The window now: [quietWindowMs] while [quiet] says so (a failure to read it counts as quiet: more looks, never fewer). */
        private fun window(): Long = if (runCatching(quiet).getOrDefault(true)) minOf(quietWindowMs, windowMs) else windowMs

        /**
         * Runs until [stop] says so (read between looks: a look under way is finished, never cut). [look] gets its start time; a
         * failure in it (not a cancellation) goes to [failed] and the lane goes on. [outside]: when another look at the same stops
         * last STARTED (0: none counted; MCX's own pass, which runs these checks itself): a newer one than the lane's moves the
         * lane's window to it plus [OUTSIDE_GRACE_MS] (that pass comes back on its own 15 s; the second lets it start first), so the
         * lane looks only when that other look is late - never twice in one window - and the gap stays within [DRIFT_MS].
         */
        suspend fun run(stop: () -> Boolean, held: () -> Boolean, look: suspend (Long) -> Unit, idle: () -> Unit = {},
                        failed: (Throwable) -> Unit = {}, outside: () -> Long = { 0L }) {
            var due = 0L          // 0: the first held look is due at once
            var lastStart = 0L
            var lastEnd = 0L
            var wasHeld = false
            while (!stop()) {
                if (!runCatching(held).getOrDefault(true)) {
                    if (wasHeld) { wasHeld = false; runCatching(idle) }
                    due = 0L
                    lastStart = 0L
                    lastEnd = 0L
                    sleep(pollMs)
                    continue
                }
                wasHeld = true
                val t = now()
                // 25 Oct: the window read again before every wait, so a stream that goes quiet brings the next look forward at
                // once (5 s after the last look started, or [restMs] after a slow one ended), never later than before.
                val w = window()
                if (due != 0L && lastStart > 0L && lastEnd > 0L) due = minOf(due, windowEnd(lastStart, lastEnd, w, restMs))
                val other = runCatching(outside).getOrDefault(0L)
                if (other > lastStart && other <= t) { lastStart = other; due = other + w + OUTSIDE_GRACE_MS; lastEnd = 0L }
                if (due != 0L && t < due && due - t <= w + OUTSIDE_GRACE_MS) { sleep(minOf(due - t, pollMs)); continue }
                lastStart = t
                runCatching { started(t, w < windowMs) }
                try {
                    look(t)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    // Only the lane's own cancellation ends it: one escaped from inside a look (a timeout) is a failure like any.
                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw e
                    runCatching { failed(e) }
                } catch (e: Throwable) {
                    runCatching { failed(e) }
                }
                lastEnd = now()
                due = windowEnd(t, lastEnd, window(), restMs)
            }
        }
    }

    /** 25 Oct (H4): the stop lane's window while the held positions' prices are quiet (no fresh stream tick). */
    const val QUIET_WINDOW_MS = 5_000L
    /** A quiet-time gap over this between two looks is counted (the promise is about 5 s; a second's grace for the look itself). */
    const val QUIET_DRIFT_MS = 6_000L

    /**
     * 25 Oct (Boss's decision, H4: "measure that stop checks never run more than about 5 s apart while a position is held and the
     * stream is quiet"): the gaps between looks that both started on the quiet clock, the same day. Thread-safe; memory only.
     */
    class QuietGaps {
        private var day: LocalDate? = null
        private var lastMs = 0L
        private var lastQuiet = false
        private var looks = 0
        private var over = 0
        private var worst = 0L

        /** A look started at [startMs] on [today]; [quiet]: on the quiet clock. Returns the gap when it is over [QUIET_DRIFT_MS], else null. */
        @Synchronized fun look(startMs: Long, quiet: Boolean, today: LocalDate): Long? {
            if (day != today) { day = today; lastMs = 0L; lastQuiet = false; looks = 0; over = 0; worst = 0L }
            val prev = lastMs
            val both = quiet && lastQuiet && prev > 0L && startMs >= prev
            lastMs = startMs; lastQuiet = quiet
            if (!both) return null
            looks++
            val gap = startMs - prev
            if (gap > worst) worst = gap
            if (gap <= QUIET_DRIFT_MS) return null
            over++
            return gap
        }

        /** Nothing held any more: the next quiet look starts a new count (a gap with nothing held is no gap). */
        @Synchronized fun idle() { lastMs = 0L; lastQuiet = false }

        /** (gaps measured, gaps over 6 s, the worst gap in ms) today. */
        @Synchronized fun today(today: LocalDate): Triple<Int, Int, Long> = if (day == today) Triple(looks, over, worst) else Triple(0, 0, 0L)

        /** The diagnostics' line. */
        fun line(today: LocalDate): String {
            val (n, o, w) = today(today)
            return if (n == 0) "Quiet-stream stop checks: none measured today (the stream kept every held price fresh, or nothing was held)."
            else if (o == 0) "Quiet-stream stop checks: all $n gaps while holding were 6 s or less today (worst ${"%.1f".format(java.util.Locale.ENGLISH, w / 1000.0)} s)."
            else "Quiet-stream stop checks: $o of $n gaps while holding were over 6 s today (worst ${w / 1000} s)."
        }
    }

    /**
     * One drift: the gap and when the late look started; 27 Oct: how long the phone slept in it ([sleptMs]) and how long the
     * look before it took ([prevTookMs]), each -1 when unknown.
     */
    data class Gap(val gapMs: Long, val atMs: Long, val sleptMs: Long = -1L, val prevTookMs: Long = -1L) {
        val alert: Boolean get() = gapMs >= ALERT_MS

        /**
         * The likely cause: the phone slept 5 s or more in it; else the look before was long enough by itself to push the next
         * past 16 s (the next waits [MIN_GAP_MS] at least after it, so over 11 s); else the lane started late.
         */
        val cause: Cause get() = when {
            sleptMs >= SLEEP_MS -> Cause.ASLEEP
            prevTookMs >= 0L && prevTookMs + MIN_GAP_MS > DRIFT_MS -> Cause.SLOW_LOOK
            sleptMs >= 0L || prevTookMs >= 0L -> Cause.LATE
            else -> Cause.UNKNOWN
        }

        /** The cause in words, with its seconds. */
        fun why(): String = when (cause) {
            Cause.ASLEEP -> "phone asleep ${sleptMs / 1000} s"
            Cause.SLOW_LOOK -> "the look before took ${prevTookMs / 1000} s"
            Cause.LATE -> "late start, phone awake" + if (prevTookMs >= 0L) ", last look ${prevTookMs / 1000} s" else ""
            Cause.UNKNOWN -> "cause unknown"
        }
    }

    /** The day's drifts, in memory (a restart starts again at none). Thread-safe. */
    class Drift {
        private var lastMs = 0L
        private var lastHeld = false
        private var lastUp = -1L
        private var lastEnd = 0L
        private var day: LocalDate? = null
        private var count = 0
        private var looks = 0
        private var worst = 0L
        private val gaps = ArrayList<Gap>()

        /**
         * A look at the stops started at [startMs] on [today] with something [held]: the drift when the gap since the last look
         * (also held, the same day) is over [DRIFT_MS], else null. A look with nothing held starts the count again.
         * 27 Oct (the 23-27 Oct diagnostics: 27 gaps, no cause known): [upMs] is the phone's awake clock at the start
         * (SystemClock.uptimeMillis, which stops while the phone sleeps; -1 when unknown), so a gap names how long the phone
         * slept in it; [ended] gives how long the last look took.
         */
        @Synchronized fun look(startMs: Long, held: Boolean, today: LocalDate, upMs: Long = -1L): Gap? {
            if (day != today) { day = today; count = 0; looks = 0; worst = 0L; lastMs = 0L; lastHeld = false; lastUp = -1L; lastEnd = 0L; gaps.clear() }
            val prev = lastMs
            val prevUp = lastUp
            val prevEnd = lastEnd
            val both = held && lastHeld && prev > 0L && startMs >= prev
            lastMs = startMs; lastHeld = held; lastUp = upMs
            if (!both) return null
            looks++
            val gap = startMs - prev
            if (gap <= DRIFT_MS) return null
            count++
            if (gap > worst) worst = gap
            val slept = if (upMs >= 0L && prevUp >= 0L && upMs >= prevUp) (gap - (upMs - prevUp)).coerceAtLeast(0L) else -1L
            val took = if (prevEnd >= prev && prevEnd <= startMs) prevEnd - prev else -1L
            val g = Gap(gap, startMs, slept, took)
            gaps += g
            if (gaps.size > KEEP) gaps.removeAt(0)
            return g
        }

        /**
         * The look that started at [startMs] ended at [endMs] (how long it took is read at the next gap). Only the latest look's
         * end is kept: a slower look that overlapped it (the MCX pass beside the stop lane) is not booked as its end.
         */
        @Synchronized fun ended(startMs: Long, endMs: Long) { if (startMs == lastMs && endMs >= startMs) lastEnd = endMs }

        /** The day's count, the looks measured and the worst gap. */
        @Synchronized fun today(today: LocalDate): Triple<Int, Int, Long> = if (day == today) Triple(count, looks, worst) else Triple(0, 0, 0L)

        /** The day's last [KEEP] gaps over 16 s, oldest first. */
        @Synchronized fun gaps(today: LocalDate): List<Gap> = if (day == today) gaps.toList() else emptyList()

        /** The diagnostics' line. */
        fun line(today: LocalDate): String {
            val (n, of, w) = today(today)
            return if (of == 0) "Stop-check gaps: none measured today (nothing held while the watch ran)."
            else if (n == 0) "Stop-check gaps: all $of gaps while holding were 16 s or less today."
            else "Stop-check gaps: $n of $of gaps while holding were over 16 s today (worst ${w / 1000} s)."
        }

        /**
         * 27 Oct: the gaps' causes for the diagnostics, "Stop-check gap causes: phone asleep 3, slow look 1. Worst: 10:14:03 41 s
         * (phone asleep 35 s); ..." (the [WORST] longest, by time; under the file's 300-character cut); null when there was no gap. Times in [zone].
         */
        @Synchronized fun detail(today: LocalDate, zone: java.time.ZoneId): String? {
            val g = gaps(today)
            if (g.isEmpty()) return null
            val by = g.groupingBy { it.cause }.eachCount()
            val causes = Cause.values().filter { (by[it] ?: 0) > 0 }.joinToString(", ") { "${it.words} ${by[it]}" }
            val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            val worst = g.sortedByDescending { it.gapMs }.take(WORST).sortedBy { it.atMs }.joinToString("; ") {
                "${java.time.Instant.ofEpochMilli(it.atMs).atZone(zone).format(fmt)} ${it.gapMs / 1000} s (${it.why()})"
            }
            val of = if (g.size < today(today).first) " (the last ${g.size})" else ""
            return "Stop-check gap causes$of: $causes. Worst: $worst."
        }
    }

    /** Why a look came late (27 Oct). */
    enum class Cause(val words: String) {
        ASLEEP("phone asleep"), SLOW_LOOK("slow look"), LATE("late start"), UNKNOWN("unknown")
    }

    /** A gap counts as the phone's sleep when the phone slept this long in it. */
    const val SLEEP_MS = 5_000L
    /** Gaps kept for the day's causes (memory only). */
    const val KEEP = 60
    /** Gaps named in the diagnostics' detail. */
    const val WORST = 3

    /** What the app shows for a gap of 30 s or more: the same words each time, so one banner stands for them all. */
    const val ALERT_WORDS = "The stops were looked at 30 s or more apart while a position was held (the promise is 15 s). " +
        "Something held the watch back: keep the app open, or check the battery settings."

    /** What the log says for one drift. */
    fun words(g: Gap): String = "The stops were looked at ${g.gapMs / 1000} s apart while a position was held (the promise is 15 s)." +
        (if (g.cause != Cause.UNKNOWN) " Cause: ${g.why()}." else "") +
        if (g.alert) " Something held the watch back: keep the app open, or check the battery settings." else ""
}

/**
 * 20 Oct, enhancement round 1 (idea 2): the watch's evening look. Zerodha ends a carried position's DAY stop and target after
 * F&O's close, and the watch used to end at the close, so nothing then rested them overnight as a GTT. While a carried live
 * protection still waits for its overnight GTT ([pending]), the watch keeps one small look (stops and targets only) for up to
 * [MINUTES] past the close. Pure.
 */
object EveningLook {
    const val MINUTES = 30
    const val EVERY_MS = 30_000L

    /** Whether the evening look is due at [minute] of a [tradingDay] with F&O closing at [foClose]. */
    fun due(minute: Int, foClose: Int, tradingDay: Boolean, pending: Boolean): Boolean =
        tradingDay && pending && minute > foClose && minute <= foClose + MINUTES

    /**
     * 20 Oct, enhancement round 2 (idea 3): the same look after MCX's close ([close], 23:30, or 23:55 in US winter time), which
     * can run past midnight: [minute] is the minute of the day now; [sessionDay] whether the session that closed traded (for a
     * minute after midnight, yesterday's). Due from the close minute itself (review: MCX is closed from that minute, and the watch
     * would end in it) for [MINUTES] more.
     */
    fun dueAfter(minute: Int, close: Int, sessionDay: Boolean, pending: Boolean): Boolean {
        if (!sessionDay || !pending || minute !in 0 until 24 * 60 || close !in 0 until 24 * 60) return false
        val since = if (minute >= close) minute - close else minute + 24 * 60 - close
        return since in 0..MINUTES
    }
}
