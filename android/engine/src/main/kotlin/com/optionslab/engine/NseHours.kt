package com.optionslab.engine

import java.time.LocalDate
import java.time.LocalTime

/**
 * NSE's session hours, in IST minutes of the day, with the two closes kept apart (Oct 2026):
 *
 * - The cash market and the index values close at 15:30 ([INDEX_CLOSE]): the index's 1-minute candles end with the 15:29
 *   bar, the closing auction runs for cash, and an index option still settles on the index's close.
 * - F&O (index futures and options) trade to 15:40 from 3 Aug 2026 ([FO_EXTENDED_FROM]); to 15:30 before it. BANKNIFTY
 *   futures' minute bars end 15:29 up to 31 Jul 2026 and 15:39 from 3 Aug 2026. Orders, positions, option and future
 *   prices, the market watch and square-offs follow [foClose] of the day concerned, so a replay of an older day keeps its
 *   own hours.
 *
 * A strategy's own entry and exit times are not these: they stay as each strategy was tested.
 */
object NseHours {
    const val OPEN = 9 * 60 + 15
    /** Cash market and index close: the last index candle is 15:29's. */
    const val INDEX_CLOSE = 15 * 60 + 30
    /** F&O's close before [FO_EXTENDED_FROM]. */
    const val FO_CLOSE_BEFORE = 15 * 60 + 30
    /** F&O's close from [FO_EXTENDED_FROM]. */
    const val FO_CLOSE_EXTENDED = 15 * 60 + 40
    /** The first session F&O traded to 15:40. */
    val FO_EXTENDED_FROM: LocalDate = LocalDate.of(2026, 8, 3)
    /** F&O's closing price: the volume-weighted average of the last this many minutes before [foClose]. */
    const val FO_CLOSING_WINDOW_MIN = 30

    /** F&O's close on [day], minutes of the day. */
    fun foClose(day: LocalDate): Int = if (day.isBefore(FO_EXTENDED_FROM)) FO_CLOSE_BEFORE else FO_CLOSE_EXTENDED

    fun foCloseTime(day: LocalDate): LocalTime = time(foClose(day))

    /** "15:40" (or "15:30" before 3 Aug 2026). */
    fun foCloseText(day: LocalDate): String = minuteText(foClose(day))

    /** The start of F&O's closing-price window on [day] (15:10 from 3 Aug 2026, 15:00 before). */
    fun foClosingWindowFrom(day: LocalDate): Int = foClose(day) - FO_CLOSING_WINDOW_MIN

    /** F&O trades on [day] at [minute] (the trading-day calendar is the caller's). */
    fun foOpen(day: LocalDate, minute: Int): Boolean = minute in OPEN until foClose(day)

    /** The app's expiry-day square-off: options expiring that day are closed from 15:05 (the app's setting, on by default). */
    const val EXPIRY_SQUARE_OFF = 15 * 60 + 5

    /**
     * The minute of [day] an intraday position is out by at the latest, as the trade manager counts down to it: the arm's
     * own exit time [ownMinute], never after the app's expiry square-off ([EXPIRY_SQUARE_OFF]) when its option expires that
     * day ([expiresToday]), nor after F&O's close ([foClose]).
     */
    fun squareOffMinute(day: LocalDate, ownMinute: Int, expiresToday: Boolean): Int =
        minOf(ownMinute, foClose(day), if (expiresToday) EXPIRY_SQUARE_OFF else Int.MAX_VALUE)

    /** The index (cash) session is on at [minute]. */
    fun indexOpen(minute: Int): Boolean = minute in OPEN until INDEX_CLOSE

    /** A special session: its own short window instead of 09:15 to the close (Diwali's Muhurat hour). */
    data class Special(val open: LocalTime, val close: LocalTime, val name: String) {
        val text: String get() = "$name, ${open.toString().take(5)}-${close.toString().take(5)} IST"
    }

    /**
     * 25 Oct 2026 (the arms audit's last item): NSE's special sessions. Sunday 8 Nov 2026 is Diwali's Muhurat trading, an
     * evening hour on a Sunday (MCX's own table, [com.optionslab.engine.mcx.McxSession.BUILT_IN], has the same 18:00-19:00 from
     * MCX's circular; NSE's circular gives the exact minutes nearer the day, so this window is the expected one). No arm trades it:
     * every arm's clock is the 09:15-15:30 session, so the day is NOT a regular trading day, even if it is added by hand as a
     * special session ([regular]); the screens name it.
     */
    val SPECIAL_SESSIONS: Map<LocalDate, Special> = mapOf(
        LocalDate.of(2026, 11, 8) to Special(LocalTime.of(18, 0), LocalTime.of(19, 0), "Diwali Muhurat trading"),
    )

    /** [day]'s special session, or null for an ordinary day. */
    fun special(day: LocalDate): Special? = SPECIAL_SESSIONS[day]

    /** Whether [day] may run the regular 09:15 session's clock: not a special session (the trading-day calendar is the caller's). */
    fun regular(day: LocalDate): Boolean = day !in SPECIAL_SESSIONS

    /** The special sessions on or after [from], oldest first. */
    fun specialsFrom(from: LocalDate): List<Pair<LocalDate, Special>> =
        SPECIAL_SESSIONS.entries.filter { !it.key.isBefore(from) }.sortedBy { it.key }.map { it.key to it.value }

    private fun time(m: Int): LocalTime = LocalTime.of(m / 60, m % 60)
}
