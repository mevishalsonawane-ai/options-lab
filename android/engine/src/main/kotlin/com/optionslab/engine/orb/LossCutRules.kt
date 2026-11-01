package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 1 Nov 2026 (Boss: "Okay go ahead, but dont break any thing"): the two paper loss-cut rules that held up in research
 * (research/HUNT_R143_UNSEEN_TWO_YEARS.md). Pure: what to do is decided here; the switches, the paper-only guard and the
 * timeline live in the app. Both only ever skip a trade or exit earlier (they lower risk); unknown inputs never act.
 */
object LossCutRules {

    /**
     * R140 FB-R72-A10: R72 Breadth Donchian skips an index's breaks on a day after a very wide day - yesterday's high-low range
     * at least [WIDE_CUT] x the average high-low range of the 20 sessions before today (yesterday included). The cut is the top
     * fifth of R72's design trades (R140 Amendment A), frozen; R143 confirmed it on Dec 2022 - Nov 2024, never seen before.
     */
    const val WIDE_CUT = 1.3206
    const val WIDE_SESSIONS = 20
    /** At least this many of the 20 sessions must be there to judge (as the research's average). */
    const val WIDE_MIN_SESSIONS = 10
    const val WIDE_WORDS = "R72 skips an index's breaks on a day after a very wide day (yesterday's range at least 1.32x its " +
        "20-day average; R140 / R143, paper)"

    /**
     * Yesterday's range over the average range of the [WIDE_SESSIONS] sessions before [today] (daily bars dated before [today]
     * only, oldest first or not). Null when it cannot be judged (fewer than [WIDE_MIN_SESSIONS] sessions, or no range).
     */
    fun wideRatio(daily: List<Bar>, today: LocalDate): Double? {
        val before = daily.filter { it.start.toLocalDate().isBefore(today) && it.high >= it.low }.sortedBy { it.start }
        if (before.size < WIDE_MIN_SESSIONS) return null
        val last = before.takeLast(WIDE_SESSIONS)
        val avg = last.map { it.high - it.low }.average()
        if (!(avg > 0.0)) return null
        val y = before.last()
        return (y.high - y.low) / avg
    }

    /** True: skip today's breaks; false: take them; null: cannot judge (they are taken, as before). */
    fun wideDay(daily: List<Bar>, today: LocalDate): Boolean? = wideRatio(daily, today)?.let { it >= WIDE_CUT }

    /**
     * R142 NP5-52: R52 Open outside value sells at its entry minute + [NP_MINUTES] when the option has not traded at least
     * [NP_RISE] above its entry price in those minutes. R143 confirmed it on Dec 2022 - Nov 2024 (losing days 28% smaller).
     */
    const val NP_MINUTES = 5L
    const val NP_RISE = 0.05
    /** The rule is looked at only this long after it is due (a late first look, after a restart, does not act). */
    const val NP_LATE_MINUTES = 5L
    const val NP_WHY = "no_progress"
    const val NP_WORDS = "R52 sells at its entry + 5 minutes if the option has not risen 5% by then (R142 / R143, paper)"

    enum class Verdict { NOT_YET, EXIT, HOLD, TOO_LATE, UNKNOWN }

    /**
     * The verdict now for a trade entered at [entry] in minute [entryMinute], from the option's 1-minute [bars] (each labelled by
     * its START): the highest high of the minutes [entryMinute, entryMinute + 5) against [entry] x (1 + 5%).
     * NOT_YET before entry + 5; TOO_LATE after entry + 10 (never acts late); UNKNOWN without the window's last minute (entry + 4).
     */
    fun noProgress(entry: Double, entryMinute: LocalDateTime, bars: List<Bar>, now: LocalDateTime): Verdict {
        val due = entryMinute.plusMinutes(NP_MINUTES)
        if (now.isBefore(due)) return Verdict.NOT_YET
        if (now.isAfter(due.plusMinutes(NP_LATE_MINUTES))) return Verdict.TOO_LATE
        if (!(entry > 0.0)) return Verdict.UNKNOWN
        val inWindow = bars.filter { !it.start.isBefore(entryMinute) && it.start.isBefore(due) }
        // The window's last minute must be in (a late feed's missing minute could hold the rise): else wait, never guess.
        if (inWindow.none { it.start == due.minusMinutes(1) }) return Verdict.UNKNOWN
        val peak = inWindow.maxOf { it.high }
        return if (peak >= entry * (1 + NP_RISE)) Verdict.HOLD else Verdict.EXIT
    }
}
