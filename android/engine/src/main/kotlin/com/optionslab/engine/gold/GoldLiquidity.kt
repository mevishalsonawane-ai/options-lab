package com.optionslab.engine.gold

import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.LiquidityRules
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * IraGoldAlgo's one strategy: the liquidity rule on XAUUSD 1-hour candles, buys only, 24x5 (the owner's choice,
 * research/GOLD_24X5.md: three years +USD 96.8k a standard lot, +93.9k after an assumed $40-a-night swap, t 2.10,
 * positive each year but most of it in the last; London + New York only was +21.4k, t 1.62). Paper only: the app never
 * sends an order; it notifies the owner, who may act in their own broker app.
 *
 *   chart     every 1-hour candle Monday to Friday (UTC), continuous across days; gold's daily break (21:00-22:00
 *             UTC) and the weekend have no candles
 *   levels    LiquidityRules' swing zones (lookback 20) and pools (2 contacts, 5 apart, 15 confirmation - 10 on the
 *             indices; research/GOLD_1H_PLUS.md)
 *   entry     a completed candle takes a POOL overlapping an active SWING zone above it: BUY at the next candle's open,
 *             any hour but 00:00 and the 21:00 break, not after 19:00 on Friday; a break downwards is ignored
 *   exits     the first of: the price touches the second liquidity level above (target; the first if there is only
 *             one - research/GOLD_1H_PLUS.md: with confirmation 15, three years +$119.5k a lot, t 3.09, drawdown -7.3k,
 *             better than the old rule in the two fitting years and the held-out one); a candle closes back below the
 *             broken level (failed break); a new level forms above (new liquidity); Friday 20:40 UTC, before the
 *             weekend (cut-off). A buy is held overnight until one of those.
 *   costs     buys at mid + half the spread, sells at mid - half the spread (a 0.30 spread), $7 a lot round trip
 *
 * All times are UTC. Pure: no clock, no network, no orders.
 */
object GoldLiquidity {
    const val SYMBOL = "XAUUSD"
    const val MINUTES = 60
    /** Gold's daily break, UTC: no candles. */
    val BREAK_START: LocalTime = LocalTime.of(21, 0)
    val BREAK_END: LocalTime = LocalTime.of(22, 0)
    /** The last entry on Friday, and Friday's cut-off before the weekend. */
    val FRIDAY_LAST_ENTRY: LocalTime = LocalTime.of(19, 0)
    val CUT_OFF: LocalTime = LocalTime.of(20, 40)
    const val SPREAD = 0.30
    const val COMMISSION_PER_LOT = 7.0
    const val OZ_PER_LOT = 100.0

    fun weekday(t: LocalDateTime) = t.dayOfWeek != DayOfWeek.SATURDAY && t.dayOfWeek != DayOfWeek.SUNDAY

    /** Gold is trading (UTC): Monday to Friday, outside the 21:00-22:00 break, and Friday only until 21:00. */
    fun inSession(t: LocalDateTime): Boolean {
        if (!weekday(t)) return false
        val tm = t.toLocalTime()
        if (!tm.isBefore(BREAK_START) && tm.isBefore(BREAK_END)) return false
        return !(t.dayOfWeek == DayOfWeek.FRIDAY && !tm.isBefore(BREAK_START))
    }

    /** Bars of any finer size (UTC starts) folded into 1-hour candles of the trading hours, oldest first. */
    fun hourly(bars: List<Bar>): List<Bar> =
        bars.filter { inSession(it.start) }.sortedBy { it.start }.groupBy { it.start.truncatedTo(ChronoUnit.HOURS) }
            .map { (h, g) -> Bar(h, g.first().open, g.maxOf { it.high }, g.minOf { it.low }, g.last().close) }

    /** Candles that have closed by [now]. */
    fun completed(hours: List<Bar>, now: LocalDateTime): List<Bar> = hours.filter { !it.start.plusMinutes(MINUTES.toLong()).isAfter(now) }

    /** May a buy start at [entry] (the next candle's open)? Any trading hour but 00:00, not after 19:00 on Friday. */
    fun mayEnterAt(entry: LocalDateTime): Boolean =
        inSession(entry) && entry.hour != 0 &&
            !(entry.dayOfWeek == DayOfWeek.FRIDAY && entry.toLocalTime().isAfter(FRIDAY_LAST_ENTRY))

    /** The Friday 20:40 UTC by which a buy entered at [entry] is sold (before the weekend). */
    fun weekendCut(entry: LocalDateTime): LocalDateTime =
        entry.toLocalDate().with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)).atTime(CUT_OFF)

    /** Pools need 15 bars of confirmation on gold's 1-hour chart (10 on the indices). */
    const val POOL_CONFIRM = 15

    /** Gold's levels: the indices' swing zones, pools confirmed over [POOL_CONFIRM] candles. */
    fun zones(bars: List<Bar>): List<LiquidityRules.Zone> =
        LiquidityRules.swingZones(bars) + LiquidityRules.poolZones(bars, confirm = POOL_CONFIRM)

    /**
     * The buy on the last completed candle, or null (no signal, or a break downwards: no short sales). Its target is the
     * second liquidity level above the close (the first when there is only one).
     */
    fun signal(completed: List<Bar>): LiquidityRules.Signal? {
        if (completed.size < 2 * LiquidityRules.SWING_LOOKBACK + 2) return null
        val zones = zones(completed)
        val s = LiquidityRules.signal(completed, zones)?.takeIf { it.side > 0 } ?: return null
        val i = completed.lastIndex
        val close = completed[i].close
        val ahead = zones.filter { q -> q.side > 0 && q.known <= i && (q.broken < 0 || q.broken > i) && q.edge > close }.map { it.edge }.sorted()
        return s.copy(target = ahead.getOrNull(1) ?: ahead.firstOrNull())
    }

    fun buyPrice(mid: Double) = mid + SPREAD / 2
    fun sellPrice(mid: Double) = mid - SPREAD / 2

    /**
     * Why an open buy should be sold now, or null. [completed] are the completed candles, [since] the price bars (mid)
     * since the entry, [entryTime] / [now] UTC. Held overnight; sold by Friday 20:40 UTC (or at once if the phone was off
     * past it).
     */
    fun exitReason(level: Double, target: Double?, signalBar: LocalDateTime, entryTime: LocalDateTime, completed: List<Bar>,
                   since: List<Bar>, now: LocalDateTime): String? {
        if (!now.isBefore(weekendCut(entryTime))) return "cut_off"
        return LiquidityRules.exitReason(1, level, target, signalBar, completed, zones(completed), since)
    }

    /** The price a buy is sold at for [why]: the target itself (less half the spread) when it was touched, else the bid. */
    fun exitPrice(why: String, target: Double?, mid: Double): Double =
        if (why == "next_liquidity" && target != null) sellPrice(target) else sellPrice(mid)

    /** USD for [lots] standard lots (100 oz each) bought at [entry] and sold at [exit], after the commission. */
    fun pnl(entry: Double, exit: Double, lots: Double): Double = (exit - entry) * OZ_PER_LOT * lots - COMMISSION_PER_LOT * lots
}
