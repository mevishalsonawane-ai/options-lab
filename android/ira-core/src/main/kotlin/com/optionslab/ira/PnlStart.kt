package com.optionslab.ira

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The live P&L page's start date (Boss, 16 Nov: "Could we make it blank until yesterday, and show only from tomorrow, for live").
 *
 * Boss taps "Start fresh from tomorrow" once on the P&L page (Zerodha view); from then that page shows only the live days on or
 * after the next trading day, and the earlier days look empty, as if never traded. "Show all history again" clears it.
 *
 * Display only: NOTHING is deleted. No risk rule reads it - the account stop rule, the guard and its drawdown peaks, the day lock,
 * the self-reviews, the diagnostics, Jarvis's figures and the order review all keep reading every day, as before. The paper view is
 * never filtered (paper has its own "Reset paper to default"). Pure.
 */
object PnlStart {
    /** The one line under the control while a start date is set. */
    const val NOTE = "Earlier live days are hidden here, not deleted; risk rules still count them."

    /** The confirm dialog's words. */
    const val CONFIRM = "From the next trading day the Zerodha P&L page starts blank. Earlier live days are only hidden: " +
        "nothing is deleted, and the account stop rule, the guard and every other risk rule still count them."

    /** How far ahead a trading day is looked for (NSE never closes this long; a broken calendar falls back to the next day). */
    private const val LOOK_AHEAD = 60

    /** A saved start read back ("2026-11-17"); null for none (blank) or anything broken, which shows every day. */
    fun startOf(saved: String?): LocalDate? =
        saved?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** The first trading day strictly after [day] (16 Nov 2026, a Monday, gives Tue 17 Nov; a Friday gives the Monday). */
    fun nextTradingDay(day: LocalDate, isTradingDay: (LocalDate) -> Boolean): LocalDate =
        onOrAfter(day.plusDays(1), isTradingDay)

    /** The first trading day on or after [day]: a start that falls on a weekend or holiday moves to the next trading day. */
    fun onOrAfter(day: LocalDate, isTradingDay: (LocalDate) -> Boolean): LocalDate {
        var d = day
        repeat(LOOK_AHEAD) {
            if (runCatching { isTradingDay(d) }.getOrDefault(false)) return d
            d = d.plusDays(1)
        }
        return day
    }

    /**
     * The first day the P&L page shows for this account: null (every day) for paper, for no start saved, or a broken one;
     * otherwise the saved start moved to a trading day ([onOrAfter]).
     */
    fun from(live: Boolean, saved: String?, isTradingDay: (LocalDate) -> Boolean): LocalDate? =
        if (!live) null else startOf(saved)?.let { onOrAfter(it, isTradingDay) }

    /** True when [day] is shown: no start, or on or after it. */
    fun shows(day: LocalDate, from: LocalDate?): Boolean = from == null || !day.isBefore(from)

    /** The days shown from [days] ([from] null: all of them, the same map). Nothing is changed in [days]. */
    fun <V> shown(days: Map<LocalDate, V>, from: LocalDate?): Map<LocalDate, V> =
        if (from == null) days else days.filterKeys { shows(it, from) }

    /** The start as epoch milliseconds at its midnight, India time ([Long.MIN_VALUE] with no start: every note). */
    fun sinceMillis(from: LocalDate?): Long = from?.atStartOfDay(IST)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE

    /**
     * A journal note shown with the start: written on or after [since] ([sinceMillis]) and not on a fill of a hidden trip
     * ([hiddenFills], the note's [key] is the fill's id).
     */
    fun showsNote(key: String, at: Long, since: Long, hiddenFills: Set<String>): Boolean = at >= since && key !in hiddenFills

    private val IST: java.time.ZoneId = java.time.ZoneOffset.ofHoursMinutes(5, 30)

    /** "Showing live P&L from Tue 17 Nov 2026." */
    fun line(from: LocalDate): String =
        "Showing live P&L from ${from.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH))}."
}
