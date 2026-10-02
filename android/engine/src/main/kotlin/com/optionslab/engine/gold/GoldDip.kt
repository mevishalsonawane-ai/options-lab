package com.optionslab.engine.gold

import com.optionslab.engine.orb.Bar
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * IraGoldAlgo's third arm, "Dip 1h+30m" (paper, the owner's choice 2026-10-02; research/GOLD_30M_1H_PATTERNS.md and
 * GOLD_MTF_LOCK.md): buy a short dip inside an hourly up-move.
 *
 *   signal  the last two COMPLETED 1-hour candles green, the 30-minute candle before the last red, the last one green
 *           (a dip that has turned up); not on the last 30-minute candle before the daily break (20:30 UTC)
 *   entry   BUY at the price then; buys only, one trade at a time
 *   exits   the first of: the profit lock - once the bid has been 1 ATR above the entry (the 30-minute ATR(14) of the
 *           signal candle), sell if it falls 2 ATRs from its highest point since the entry; 8 hours after the entry;
 *           20:55 UTC, before the daily break (and so before the weekend)
 * Backtest, three years a standard lot after costs: +$131.0k (+11.8k, +44.5k, +74.6k), 961 trades, 48% won, t 2.09,
 * deepest drawdown -$42.3k. Weaker than the Trend 4h arm; a paper candidate.
 *
 * Pure: no clock, no network, no orders. Times UTC; prices mid unless said.
 */
object GoldDip {
    const val MINUTES = 30L
    const val ATR_LENGTH = 14
    const val LOCK_START = 1.0
    const val LOCK_GIVEBACK = 2.0
    const val MAX_HOURS = 8L
    /** Out by here each day (the candle before the 21:00 break closes at 21:00; the pass runs every minute). */
    val CUT: LocalTime = LocalTime.of(20, 55)
    /** The last 30-minute candle before the daily break: no signal on it (the trade would end at once). */
    val LAST_BEFORE_BREAK: LocalTime = LocalTime.of(20, 30)

    /** The 30-minute candle a time belongs to. */
    fun slot(t: LocalDateTime): LocalDateTime = t.truncatedTo(ChronoUnit.HOURS).plusMinutes(if (t.minute >= 30) 30 else 0)

    /** Bars of any finer size (UTC, trading hours only) folded into 30-minute candles, oldest first. */
    fun thirty(bars: List<Bar>): List<Bar> =
        bars.filter { GoldLiquidity.inSession(it.start) }.sortedBy { it.start }.groupBy { slot(it.start) }
            .map { (s, g) -> Bar(s, g.first().open, g.maxOf { it.high }, g.minOf { it.low }, g.last().close) }

    /** The 30-minute candles closed by [now]. */
    fun completed(bars: List<Bar>, now: LocalDateTime): List<Bar> = bars.filter { !it.start.plusMinutes(MINUTES).isAfter(now) }

    /** The signal on the last of [thirty] (completed 30-minute candles) given [hours] (completed 1-hour candles), or null. */
    fun signal(thirty: List<Bar>, hours: List<Bar>): Signal? {
        if (thirty.size < ATR_LENGTH + 2 || hours.size < 2) return null
        val last = thirty.last()
        val prev = thirty[thirty.size - 2]
        if (last.start.toLocalTime() == LAST_BEFORE_BREAK) return null
        val closeAt = last.start.plusMinutes(MINUTES)
        val done = hours.filter { !it.start.plusHours(1).isAfter(closeAt) }
        if (done.size < 2) return null
        val h1 = done[done.size - 2]; val h2 = done.last()
        val green = { b: Bar -> b.close > b.open }
        val red = { b: Bar -> b.close < b.open }
        if (!(green(h1) && green(h2) && red(prev) && green(last))) return null
        return Signal(last.start, GoldTrend.atr(thirty, ATR_LENGTH).last())
    }

    data class Signal(val bar: LocalDateTime, val atr: Double)

    /** The lock's stop (a bid price) for a buy at [entry] (ask) whose highest bid since is [peak], or null before it starts. */
    fun lockStop(entry: Double, peak: Double, atr: Double): Double? =
        if (atr > 0 && peak - entry >= LOCK_START * atr) peak - LOCK_GIVEBACK * atr else null

    /** The first 20:55 UTC at or after [entryTime]: the trade is out before that day's break (and so the weekend). */
    fun cutAfter(entryTime: LocalDateTime): LocalDateTime =
        entryTime.toLocalDate().atTime(CUT).let { if (entryTime.isBefore(it)) it else it.plusDays(1) }

    /** "dip_time" 8 hours after the entry, "dip_break" from the cut before the daily break, else null. */
    fun timeExit(entryTime: LocalDateTime, now: LocalDateTime): String? = when {
        !now.isBefore(cutAfter(entryTime)) -> "dip_break"
        !now.isBefore(entryTime.plusHours(MAX_HOURS)) -> "dip_time"
        else -> null
    }
}
