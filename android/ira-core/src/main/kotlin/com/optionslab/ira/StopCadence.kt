package com.optionslab.ira

import java.time.LocalDate

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
    fun windowEnd(lastStartMs: Long, nowMs: Long, windowMs: Long = WINDOW_MS): Long =
        if (lastStartMs <= 0L || lastStartMs > nowMs) nowMs + windowMs else minOf(nowMs + windowMs, maxOf(nowMs + MIN_GAP_MS, lastStartMs + windowMs))

    /** One drift: the gap and when the late look started. */
    data class Gap(val gapMs: Long, val atMs: Long) {
        val alert: Boolean get() = gapMs >= ALERT_MS
    }

    /** The day's drifts, in memory (a restart starts again at none). Thread-safe. */
    class Drift {
        private var lastMs = 0L
        private var lastHeld = false
        private var day: LocalDate? = null
        private var count = 0
        private var looks = 0
        private var worst = 0L

        /**
         * A look at the stops started at [startMs] on [today] with something [held]: the drift when the gap since the last look
         * (also held, the same day) is over [DRIFT_MS], else null. A look with nothing held starts the count again.
         */
        @Synchronized fun look(startMs: Long, held: Boolean, today: LocalDate): Gap? {
            if (day != today) { day = today; count = 0; looks = 0; worst = 0L; lastMs = 0L; lastHeld = false }
            val prev = lastMs
            val both = held && lastHeld && prev > 0L && startMs >= prev
            lastMs = startMs; lastHeld = held
            if (!both) return null
            looks++
            val gap = startMs - prev
            if (gap <= DRIFT_MS) return null
            count++
            if (gap > worst) worst = gap
            return Gap(gap, startMs)
        }

        /** The day's count, the looks measured and the worst gap. */
        @Synchronized fun today(today: LocalDate): Triple<Int, Int, Long> = if (day == today) Triple(count, looks, worst) else Triple(0, 0, 0L)

        /** The diagnostics' line. */
        fun line(today: LocalDate): String {
            val (n, of, w) = today(today)
            return if (of == 0) "Stop-check gaps: none measured today (nothing held while the watch ran)."
            else if (n == 0) "Stop-check gaps: all $of gaps while holding were 16 s or less today."
            else "Stop-check gaps: $n of $of gaps while holding were over 16 s today (worst ${w / 1000} s)."
        }
    }

    /** What the app shows for a gap of 30 s or more: the same words each time, so one banner stands for them all. */
    const val ALERT_WORDS = "The stops were looked at 30 s or more apart while a position was held (the promise is 15 s). " +
        "Something held the watch back: keep the app open, or check the battery settings."

    /** What the log says for one drift. */
    fun words(g: Gap): String = "The stops were looked at ${g.gapMs / 1000} s apart while a position was held (the promise is 15 s)." +
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
     * minute after midnight, yesterday's). Due from the minute after the close for [MINUTES].
     */
    fun dueAfter(minute: Int, close: Int, sessionDay: Boolean, pending: Boolean): Boolean {
        if (!sessionDay || !pending || minute !in 0 until 24 * 60 || close !in 0 until 24 * 60) return false
        val since = if (minute > close) minute - close else minute + 24 * 60 - close
        return since in 1..MINUTES
    }
}
