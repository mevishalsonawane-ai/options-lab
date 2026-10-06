package com.optionslab.engine.orb

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Liquidity 15+5: the owner's liquidity-break idea, run on BANKNIFTY and FINNIFTY, each on two charts side by side
 * (BANKNIFTY 15-minute and 5-minute, FINNIFTY 30-minute and 5-minute, one position per chart). Like the ORB it follows the app's Paper / Live switch (Live is armed with the PIN). Levels as in research/liquidity_break.py and indicator/liquidity.py, written from
 * the published descriptions of LuxAlgo's Liquidity Swings (pivot lookback 20, full range) and Liquidity Pools
 * (2 contacts, 5 bars apart, 10 confirmation bars):
 *
 *   swing     a pivot high / low with 20 bars each side; its zone is the pivot candle's full range
 *   pool      a candle's wick area rejected twice, the contacts at least 5 bars apart, then not closed through for
 *             10 bars; a close through it before that discards it
 *   taken     a close beyond a level's outer edge (above a high's top, below a low's bottom)
 *   entry     the last completed bar takes a POOL that overlaps a still-active SWING zone of the same side (both
 *             tools agree): above -> BUY the CE, below -> BUY the PE, one strike in the money ([entryStrike]), at the
 *             next bar's open, 09:20-14:00; skipped when the next level ahead is too close ([hasRoom])
 *   exits     the first of: the option falls 15% below the price paid (a resting stop, the owner's 2026-10-01 choice);
 *             the index trades 30 points (FINNIFTY 15) back through the broken level (index stop); not +5% after 20 minutes
 *             (time stop);
 *             the index touches the next active liquidity level beyond the entry (target); a completed bar closes back
 *             through the broken level (failed break); a new level forms on the trade's side (new liquidity); 15:10
 *
 * Backtested on two BANKNIFTY years with real option prices (research/LIQUIDITY_MORE.md): the two charts together
 * about 2 trades a day, +Rs 58k and +Rs 32k per lot a year (t about 1.1 each): a candidate, not a proven edge.
 * Pure: no clock, no network, no orders.
 */
object LiquidityRules {
    // Follows the app's Paper / Live switch like the ORB (the owner's go-ahead, 2026-10-01): Live is armed with the PIN.
    val ARM = Arm("liquidity", "Liquidity 15+5", liquidity = true)
    val ARM15 = Arm("liquidity15", "Liquidity 15m", liquidity = true)
    val ARM5 = Arm("liquidity5", "Liquidity 5m", liquidity = true)
    /**
     * FINNIFTY too (the owner's choice, 2026-10-01): the same rules on its own charts and options, but on the 30-minute
     * and 5-minute charts - the one change that held up in both years on FINNIFTY (research/LIQUIDITY_FINNIFTY_PLUS.md:
     * +8.2 / +4.8 index pts a trade against +5.8 / +3.6 with 15 + 5). The owner's choice, 2026-10-01.
     */
    val FIN30 = Arm("liquidity30_fin", "Liquidity 30m FINNIFTY", liquidity = true)
    val FIN5 = Arm("liquidity5_fin", "Liquidity 5m FINNIFTY", liquidity = true)
    /** The books behind the one switch: BANKNIFTY 15-minute and 5-minute, FINNIFTY 30-minute and 5-minute, one position per book. */
    val BOOKS = listOf(ARM15, ARM5, FIN30, FIN5)
    /** Books renamed since a build saved them (FINNIFTY's 15-minute book became its 30-minute book). */
    val RENAMED = mapOf("liquidity15_fin" to FIN30.source)
    val UNDERLYINGS = listOf("BANKNIFTY", "FINNIFTY")
    /** Upstox index keys for the charts the levels are read from. */
    val INDEX_KEYS = mapOf("BANKNIFTY" to "NSE_INDEX|Nifty Bank", "FINNIFTY" to "NSE_INDEX|Nifty Fin Service")

    const val SWING_LOOKBACK = 20
    const val CONTACTS = 2
    const val GAP = 5
    const val CONFIRM = 10
    const val MAX_AGE = 300
    val SESSION_OPEN: LocalTime = LocalTime.of(9, 15)
    val FIRST_ENTRY: LocalTime = LocalTime.of(9, 20)
    /** 14:00 (was 14:30): better in both years tested (research/ENTRY_CUTOFF.md). */
    val LAST_ENTRY: LocalTime = LocalTime.of(14, 0)

    /** The owner's stop: 15% of the premium paid (a resting SL-M sell at 85% of the fill). */
    const val PREMIUM_STOP = 0.15

    /** The stop's trigger for a [fill]: 15% below, rounded down to the 0.05 tick; null for a price with no room. */
    fun stopTrigger(fill: Double): Double? {
        val t = kotlin.math.floor(fill * (1 - PREMIUM_STOP) / OrbRules.TICK + 1e-9) * OrbRules.TICK
        val r = kotlin.math.round(t * 100) / 100.0
        return r.takeIf { it >= OrbRules.TICK && it < fill }
    }

    fun minutesOf(arm: Arm): Int = when {
        arm.source.startsWith("liquidity30") -> 30
        arm.source.startsWith("liquidity15") -> 15
        else -> 5
    }
    fun underlyingOf(arm: Arm): String = if (arm.source.endsWith("_fin")) "FINNIFTY" else "BANKNIFTY"
    /** Strike spacing of the index's options: BANKNIFTY 100, FINNIFTY 50. */
    fun strikeStep(underlying: String): Int = if (underlying == "FINNIFTY") 50 else 100

    class Zone(val kind: String, val side: Int, val top: Double, val bottom: Double, val origin: Int, val known: Int) {
        var broken: Int = -1
        val edge: Double get() = if (side > 0) top else bottom
    }

    data class Signal(val side: Int, val level: Double, val target: Double?)

    /** 1-minute bars (any number of days) folded into [minutes]-minute bars from 09:15 each day, labelled by their start. */
    fun fold(ones: List<Bar>, minutes: Int): List<Bar> =
        ones.sortedBy { it.start }.groupBy { b ->
            val m = b.start.hour * 60 + b.start.minute - (SESSION_OPEN.hour * 60 + SESSION_OPEN.minute)
            b.start.toLocalDate().atTime(SESSION_OPEN).plusMinutes(((if (m < 0) 0 else m) / minutes * minutes).toLong())
        }.map { (start, g) -> Bar(start, g.first().open, g.maxOf { it.high }, g.minOf { it.low }, g.last().close) }

    /** Bars that have closed by [now]. */
    fun completed(bars: List<Bar>, minutes: Int, now: LocalDateTime): List<Bar> =
        bars.filter { !it.start.plusMinutes(minutes.toLong()).isAfter(now) }

    fun swingZones(bars: List<Bar>, length: Int = SWING_LOOKBACK): List<Zone> {
        val out = ArrayList<Zone>()
        for (i in 2 * length until bars.size) {
            val j = i - length
            val win = (j - length)..(j + length)
            val h = bars[j].high
            val l = bars[j].low
            if (win.all { k -> k == j || bars[k].high < h }) out += Zone("swing", 1, h, l, j, i)
            if (win.all { k -> k == j || bars[k].low > l }) out += Zone("swing", -1, h, l, j, i)
        }
        markBreaks(out, bars)
        return out
    }

    fun poolZones(bars: List<Bar>, contacts: Int = CONTACTS, gap: Int = GAP, confirm: Int = CONFIRM, maxAge: Int = MAX_AGE): List<Zone> {
        class Cand(val side: Int, val top: Double, val bottom: Double, val origin: Int, var count: Int, var last: Int)
        val out = ArrayList<Zone>()
        var cand = ArrayList<Cand>()
        for (i in bars.indices) {
            val b = bars[i]
            val keep = ArrayList<Cand>()
            for (z in cand) {
                if (i - z.origin > maxAge) continue
                if ((z.side > 0 && b.close > z.top) || (z.side < 0 && b.close < z.bottom)) continue      // broken before confirmed
                val touched = if (z.side > 0) b.high >= z.bottom && b.close < z.top else b.low <= z.top && b.close > z.bottom
                if (touched && i - z.last >= gap && z.count < contacts) { z.count++; z.last = i }
                if (z.count >= contacts && i - z.last >= confirm) {
                    // As the research version: a pool overlapping one of the last 50 of its side is the same pool (whether
                    // or not that one has since been taken - breaks are marked after the scan).
                    val dup = out.takeLast(50).any { q -> q.side == z.side && q.bottom <= z.top && z.bottom <= q.top }
                    if (!dup) out += Zone("pool", z.side, z.top, z.bottom, z.origin, i)
                    continue
                }
                keep += z
            }
            cand = keep
            val bodyHi = maxOf(b.open, b.close)
            val bodyLo = minOf(b.open, b.close)
            if (b.high > bodyHi) cand += Cand(1, b.high, bodyHi, i, 1, i)
            if (b.low < bodyLo) cand += Cand(-1, bodyLo, b.low, i, 1, i)
        }
        markBreaks(out, bars)
        return out
    }

    /** The bar whose close first takes each zone after it became known. */
    fun markBreaks(zones: List<Zone>, bars: List<Bar>) {
        for (z in zones) {
            z.broken = -1
            for (i in z.known until bars.size) {
                val c = bars[i].close
                if ((z.side > 0 && c > z.top) || (z.side < 0 && c < z.bottom)) { z.broken = i; break }
            }
        }
    }

    fun zones(bars: List<Bar>): List<Zone> = swingZones(bars) + poolZones(bars)

    private fun activeAt(z: Zone, i: Int) = z.known <= i && (z.broken < 0 || z.broken >= i)

    /** The entry on the last bar of [bars], or null: a pool taken there that sits on an active swing zone of its side. */
    fun signal(bars: List<Bar>, zones: List<Zone>): Signal? {
        val i = bars.lastIndex
        if (i < 0) return null
        val swings = zones.filter { it.kind == "swing" }
        val pool = zones.firstOrNull { p -> p.kind == "pool" && p.broken == i &&
            swings.any { s -> s.side == p.side && s.bottom <= p.top && p.bottom <= s.top && activeAt(s, i) } } ?: return null
        val close = bars[i].close
        val ahead = zones.filter { q -> q.side == pool.side && q.known <= i && (q.broken < 0 || q.broken > i) &&
            pool.side * (q.edge - close) > 0 }.map { it.edge }
        val target = if (ahead.isEmpty()) null else if (pool.side > 0) ahead.min() else ahead.max()
        return Signal(pool.side, pool.edge, target)
    }

    /**
     * Why an open position should exit now, or null. [bars] are the chart's completed bars, [zones] their levels,
     * [signalBar] the bar that decided the entry, [minutesSince] the index 1-minute bars since the entry.
     */
    fun exitReason(side: Int, level: Double, target: Double?, signalBar: LocalDateTime, bars: List<Bar>, zones: List<Zone>,
                   minutesSince: List<Bar>): String? {
        if (target != null && minutesSince.any { if (side > 0) it.high >= target else it.low <= target }) return "next_liquidity"
        val after = bars.withIndex().filter { it.value.start.isAfter(signalBar) }
        if (after.any { side * (it.value.close - level) < 0 }) return "failed_break"
        val first = after.firstOrNull()?.index ?: return null
        if (zones.any { it.side == side && it.known >= first }) return "new_liquidity"
        return null
    }

    /**
     * The owner's exits for a turn in direction (2026-10-01; research/LIQUIDITY_REVERSAL.md: +55.6k / +21.6k ->
     * +103.0k / +30.3k over two BANKNIFTY years, worst trade -15.7k -> -9.6k):
     *   index stop  out the minute the index trades [indexStopPoints] back beyond the level the entry broke
     *   time stop   [TIME_STOP_MINUTES] after the entry, out if the option is not [TIME_STOP_GAIN] above the price paid
     */
    const val TIME_STOP_MINUTES = 20L
    const val TIME_STOP_GAIN = 0.05

    /** BANKNIFTY 30 points; FINNIFTY, about half its size, 15. */
    fun indexStopPoints(underlying: String): Double = if (underlying == "FINNIFTY") 15.0 else 30.0

    /** True when a 1-minute bar since the entry traded [points] back through [level] against [side]. */
    fun indexStopHit(side: Int, level: Double, points: Double, minutesSince: List<Bar>): Boolean =
        minutesSince.any { if (side > 0) it.low < level - points else it.high > level + points }

    fun timeStopDue(entryTime: LocalDateTime, now: LocalDateTime): Boolean = !now.isBefore(entryTime.plusMinutes(TIME_STOP_MINUTES))

    /** True when the option at [ltp] is short of the gain the time stop asks for. */
    fun timeStopFails(entry: Double, ltp: Double): Boolean = ltp < entry * (1 + TIME_STOP_GAIN) - 1e-9

    /**
     * Room filter (research liq2, 2026-10-06): skip a break whose next liquidity level ahead (the trade's target) is
     * closer to the deciding bar's close than [MIN_ROOM_STOPS] index-stop units (BANKNIFTY 30 points, FINNIFTY 15).
     * No level ahead counts as room. Picked by a quarterly walk-forward over Aug 2021 - Oct 2026, together with
     * [ITM_STEPS]; the one book state it changes is that a skipped break leaves the book flat for the next one.
     */
    const val MIN_ROOM_STOPS = 1.0

    fun hasRoom(s: Signal, close: Double, underlying: String): Boolean {
        val target = s.target ?: return true
        return s.side * (target - close) >= MIN_ROOM_STOPS * indexStopPoints(underlying)
    }

    /** Strikes in the money the entry buys (research liq2, 2026-10-06): 1, i.e. the CE one step below ATM, the PE one above. */
    const val ITM_STEPS = 1

    /** The strike a break on [side] buys when the deciding bar closed at [close]: [ITM_STEPS] steps in the money from ATM. */
    fun entryStrike(side: Int, close: Double, underlying: String): Int {
        val step = strikeStep(underlying)
        return OrbRules.atmStrike(close, step) - (if (side > 0) 1 else -1) * ITM_STEPS * step
    }

    fun mayEnterAt(entry: LocalDateTime): Boolean { val t = entry.toLocalTime(); return !t.isBefore(FIRST_ENTRY) && !t.isAfter(LAST_ENTRY) }
}
