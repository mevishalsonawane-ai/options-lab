package com.optionslab.engine.gold

import com.optionslab.engine.orb.Bar
import java.time.LocalDateTime

/**
 * IraGoldAlgo's second arm: buy gold while its 4-hour trend points up (research/GOLD_TREND.md, GOLD_TREND_TUNE.md).
 *
 *   chart   XAUUSD 4-hour candles from the trading hours' 1-hour candles, starting 00:00, 04:00, ... 20:00 UTC (the
 *           20:00 candle holds 20:00 and, Monday to Thursday, 22:00-23:59 after the break)
 *   trend   Supertrend(10, 3): ATR(10) (Wilder), bands 3 ATRs either side of the candle's middle, the usual ratchet
 *   entry   a completed 4-hour candle with the trend up: BUY (paper) at the price then; buys only
 *   exits   the first of: a completed 4-hour candle with the trend down (sell); the profit lock - once the bid has been
 *           1 ATR(14) above the entry (the 4-hour ATR at the entry), sell if it falls 4 ATRs from its highest point
 *           since the entry; after a lock exit, no new buy until the trend has turned down and up again
 *   holds   overnight and over the weekend (XM charges swap)
 * Backtest, three years a standard lot after costs and an assumed $40 a night swap: +$321.9k (+59.9k, +76.3k, +185.7k),
 * 52 trades, 63% won, t 2.79, deepest drawdown -$39.2k marked hourly (without the lock +$252.1k, -$49.9k). Gold rose
 * about 140% in those years.
 *
 * Pure: no clock, no network, no orders. Times UTC; prices mid unless said.
 */
object GoldTrend {
    const val CHART_HOURS = 4L
    const val ATR_LENGTH = 10
    const val MULTIPLIER = 3.0
    const val LOCK_ATR_LENGTH = 14
    /** The lock starts once the bid has been this many ATRs above the entry... */
    const val LOCK_START = 1.0
    /** ...and then sells a fall of this many ATRs from the highest bid since the entry. */
    const val LOCK_GIVEBACK = 4.0
    /** Fewer completed 4-hour candles than this: no decision (the ATR and the line need a run-in). */
    const val MIN_BARS = 30

    /** The 4-hour candle a time belongs to. */
    fun slot(t: LocalDateTime): LocalDateTime = t.toLocalDate().atTime((t.hour / CHART_HOURS.toInt()) * CHART_HOURS.toInt(), 0)

    /** The trading hours' 1-hour candles ([GoldLiquidity.hourly]) folded into 4-hour candles, oldest first. */
    fun chart(hours: List<Bar>): List<Bar> =
        hours.sortedBy { it.start }.groupBy { slot(it.start) }
            .map { (s, g) -> Bar(s, g.first().open, g.maxOf { it.high }, g.minOf { it.low }, g.last().close) }

    /** The 4-hour candles that have closed by [now]. */
    fun completed(bars: List<Bar>, now: LocalDateTime): List<Bar> = bars.filter { !it.start.plusHours(CHART_HOURS).isAfter(now) }

    /** Wilder's ATR (an exponential average, 1/n, seeded with the first candle's range). */
    fun atr(bars: List<Bar>, n: Int): DoubleArray {
        val a = DoubleArray(bars.size)
        for (i in bars.indices) {
            val b = bars[i]
            val tr = if (i == 0) b.high - b.low
            else maxOf(b.high - b.low, kotlin.math.abs(b.high - bars[i - 1].close), kotlin.math.abs(b.low - bars[i - 1].close))
            a[i] = if (i == 0) tr else a[i - 1] + (tr - a[i - 1]) / n
        }
        return a
    }

    /** The trend after the last completed candle: up or not, the Supertrend line under it, and the lock's ATR. */
    data class State(val up: Boolean, val line: Double, val atr: Double)

    fun state(completed: List<Bar>): State? {
        if (completed.size < MIN_BARS) return null
        val a = atr(completed, ATR_LENGTH)
        var fu = 0.0; var fl = 0.0; var up = true
        for (i in completed.indices) {
            val b = completed[i]
            val mid = (b.high + b.low) / 2
            val ub = mid + MULTIPLIER * a[i]
            val lb = mid - MULTIPLIER * a[i]
            if (i == 0) { fu = ub; fl = lb; continue }
            val pc = completed[i - 1].close
            val pu = fu; val pl = fl
            fu = if (ub < pu || pc > pu) ub else pu
            fl = if (lb > pl || pc < pl) lb else pl
            up = if (b.close > pu) true else if (b.close < pl) false else up
        }
        return State(up, fl, atr(completed, LOCK_ATR_LENGTH).last())
    }

    /** The lock's stop (a bid price) for a buy at [entry] (ask) whose highest bid since is [peak], or null before it starts. */
    fun lockStop(entry: Double, peak: Double, atr: Double): Double? =
        if (atr > 0 && peak - entry >= LOCK_START * atr) peak - LOCK_GIVEBACK * atr else null
}
