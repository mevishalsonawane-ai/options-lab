package com.optionslab.engine.risk

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * The paper account's running result since a start date (Boss's 08 Oct wish), for Home: "Paper since 1 Oct: +Rs 5,490 net
 * over 5 days (avg Rs 1,098/day)". Read from the account's own day figures (after charges); a day counts when it has one.
 * Words only: nothing here trades or limits anything. Pure.
 */
object PaperSince {
    /** The start Home counts from until Boss picks another (Settings → Bot settings). */
    val DEFAULT_START: LocalDate = LocalDate.of(2026, 10, 1)

    data class Summary(val start: LocalDate, val net: Double, val days: Int) {
        /** The average a day (0 with no day). */
        val perDay: Double get() = if (days > 0) net / days else 0.0
    }

    /** A saved start date read back ("2026-10-01"); [DEFAULT_START] for none or anything broken. */
    fun startOf(saved: String?): LocalDate = saved?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() } ?: DEFAULT_START

    /** The days on or after [start] (date to the day's P&L after charges; non-finite figures left out). */
    fun summary(days: Map<LocalDate, Double>, start: LocalDate): Summary {
        val kept = days.filter { (d, v) -> !d.isBefore(start) && v.isFinite() }
        return Summary(start, kept.values.sum(), kept.size)
    }

    /** "Paper since 1 Oct: +Rs 5,490 net over 5 days (avg Rs 1,098/day)"; with no day yet, "Paper since 1 Oct: no trading day yet". */
    fun line(s: Summary): String {
        val since = "Paper since ${s.start.dayOfMonth} ${s.start.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)}"
        if (s.days == 0) return "$since: no trading day yet"
        return "$since: ${signed(s.net)} net over ${s.days} day${if (s.days == 1) "" else "s"} (avg ${signed(s.perDay, plus = false)}/day)"
    }

    /** From this day every paper fill pays the bid/ask spread (Honest paper, [com.optionslab.engine.sandbox.PaperSpread]). */
    val SPREAD_FROM: LocalDate = LocalDate.of(2026, 10, 8)

    /**
     * What Home adds under the line: "includes the bid/ask spread", and from when if the count starts before
     * [SPREAD_FROM] (the earlier days were booked without it).
     */
    fun spreadNote(start: LocalDate): String =
        if (start.isBefore(SPREAD_FROM)) "Paper fills include the bid/ask spread from 8 Oct" else "Paper fills include the bid/ask spread"

    private fun signed(x: Double, plus: Boolean = true): String {
        val r = Math.round(x).toDouble()
        val sign = if (r < 0) "-" else if (plus && r > 0) "+" else ""
        return sign + "Rs " + "%,.0f".format(Locale.ENGLISH, abs(r))
    }
}
