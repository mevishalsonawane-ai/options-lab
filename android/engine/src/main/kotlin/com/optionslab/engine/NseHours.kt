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

    /** The index (cash) session is on at [minute]. */
    fun indexOpen(minute: Int): Boolean = minute in OPEN until INDEX_CLOSE

    private fun time(m: Int): LocalTime = LocalTime.of(m / 60, m % 60)
}
