package com.optionslab.engine.gold

import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.LiquidityRules
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * IraGoldAlgo's one strategy: the liquidity rule on XAUUSD 1-hour candles, buys only, London + New York session
 * (research/liquidity_gold_1h.py, "London + New York, 1h alone, buys only": 88 trades in three years, 59% won,
 * +USD 21,393 a standard lot, t 1.62 - a candidate, not a proven edge). Paper only: the app never sends an order;
 * it notifies the owner, who may act in their own broker app.
 *
 *   chart     1-hour candles of the London + New York session only, 07:00-21:00 UTC, Monday to Friday, continuous
 *             across days (the hours outside the session are not on the chart, as in the research)
 *   levels    LiquidityRules' swing zones (lookback 20) and pools (2 contacts, 5 apart, 10 confirmation)
 *   entry     a completed candle takes a POOL overlapping an active SWING zone above it: BUY at the next candle's open,
 *             entries 08:00-19:00 UTC; a break downwards is ignored (no short sales)
 *   exits     the first of: the price touches the next liquidity level above (target); a candle closes back below the
 *             broken level (failed break); a new level forms above (new liquidity); 20:40 UTC (cut-off)
 *   costs     buys at mid + half the spread, sells at mid - half the spread (a 0.30 spread), $7 a lot round trip
 *
 * All times are UTC. Pure: no clock, no network, no orders.
 */
object GoldLiquidity {
    const val SYMBOL = "XAUUSD"
    const val MINUTES = 60
    val SESSION_START: LocalTime = LocalTime.of(7, 0)
    val SESSION_END: LocalTime = LocalTime.of(21, 0)
    val FIRST_ENTRY: LocalTime = LocalTime.of(8, 0)
    val LAST_ENTRY: LocalTime = LocalTime.of(19, 0)
    val CUT_OFF: LocalTime = LocalTime.of(20, 40)
    const val SPREAD = 0.30
    const val COMMISSION_PER_LOT = 7.0
    const val OZ_PER_LOT = 100.0

    fun weekday(t: LocalDateTime) = t.dayOfWeek != DayOfWeek.SATURDAY && t.dayOfWeek != DayOfWeek.SUNDAY

    /** Inside the session's hours on a weekday (UTC). */
    fun inSession(t: LocalDateTime): Boolean = weekday(t) && !t.toLocalTime().isBefore(SESSION_START) && t.toLocalTime().isBefore(SESSION_END)

    /** Bars of any finer size (UTC starts) folded into the session's 1-hour candles, oldest first. */
    fun hourly(bars: List<Bar>): List<Bar> =
        bars.filter { inSession(it.start) }.sortedBy { it.start }.groupBy { it.start.truncatedTo(ChronoUnit.HOURS) }
            .map { (h, g) -> Bar(h, g.first().open, g.maxOf { it.high }, g.minOf { it.low }, g.last().close) }

    /** Candles that have closed by [now]. */
    fun completed(hours: List<Bar>, now: LocalDateTime): List<Bar> = hours.filter { !it.start.plusMinutes(MINUTES.toLong()).isAfter(now) }

    /** May a buy start at [entry] (the next candle's open)? */
    fun mayEnterAt(entry: LocalDateTime): Boolean =
        weekday(entry) && !entry.toLocalTime().isBefore(FIRST_ENTRY) && !entry.toLocalTime().isAfter(LAST_ENTRY)

    /** The buy on the last completed candle, or null (no signal, or a break downwards: no short sales). */
    fun signal(completed: List<Bar>): LiquidityRules.Signal? =
        if (completed.size < 2 * LiquidityRules.SWING_LOOKBACK + 2) null
        else LiquidityRules.signal(completed, LiquidityRules.zones(completed))?.takeIf { it.side > 0 }

    fun buyPrice(mid: Double) = mid + SPREAD / 2
    fun sellPrice(mid: Double) = mid - SPREAD / 2

    /**
     * Why an open buy should be sold now, or null. [completed] are the session's completed candles, [since] the price
     * bars (mid) since the entry, [entryTime] / [now] UTC. A buy still open on a later day (the phone was off) goes too.
     */
    fun exitReason(level: Double, target: Double?, signalBar: LocalDateTime, entryTime: LocalDateTime, completed: List<Bar>,
                   since: List<Bar>, now: LocalDateTime): String? {
        if (now.toLocalDate() != entryTime.toLocalDate() || !now.toLocalTime().isBefore(CUT_OFF)) return "cut_off"
        return LiquidityRules.exitReason(1, level, target, signalBar, completed, LiquidityRules.zones(completed), since)
    }

    /** The price a buy is sold at for [why]: the target itself (less half the spread) when it was touched, else the bid. */
    fun exitPrice(why: String, target: Double?, mid: Double): Double =
        if (why == "next_liquidity" && target != null) sellPrice(target) else sellPrice(mid)

    /** USD for [lots] standard lots (100 oz each) bought at [entry] and sold at [exit], after the commission. */
    fun pnl(entry: Double, exit: Double, lots: Double): Double = (exit - entry) * OZ_PER_LOT * lots - COMMISSION_PER_LOT * lots
}
