package com.optionslab.engine.orb

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Liquidity 15+5: the owner's liquidity-break idea, run on two charts side by side (15-minute and 5-minute, one
 * position each). Like the ORB it follows the app's Paper / Live switch (Live is armed with the PIN). Levels as in research/liquidity_break.py and indicator/liquidity.py, written from
 * the published descriptions of LuxAlgo's Liquidity Swings (pivot lookback 20, full range) and Liquidity Pools
 * (2 contacts, 5 bars apart, 10 confirmation bars):
 *
 *   swing     a pivot high / low with 20 bars each side; its zone is the pivot candle's full range
 *   pool      a candle's wick area rejected twice, the contacts at least 5 bars apart, then not closed through for
 *             10 bars; a close through it before that discards it
 *   taken     a close beyond a level's outer edge (above a high's top, below a low's bottom)
 *   entry     the last completed bar takes a POOL that overlaps a still-active SWING zone of the same side (both
 *             tools agree): above -> BUY the ATM CE, below -> BUY the ATM PE, at the next bar's open, 09:20-14:30
 *   exits     the first of: the option falls 15% below the price paid (a resting stop, the owner's 2026-10-01 choice);
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
    /** The two books behind the one switch. */
    val BOOKS = listOf(ARM15, ARM5)

    const val SWING_LOOKBACK = 20
    const val CONTACTS = 2
    const val GAP = 5
    const val CONFIRM = 10
    const val MAX_AGE = 300
    val SESSION_OPEN: LocalTime = LocalTime.of(9, 15)
    val FIRST_ENTRY: LocalTime = LocalTime.of(9, 20)
    val LAST_ENTRY: LocalTime = LocalTime.of(14, 30)

    /** The owner's stop: 15% of the premium paid (a resting SL-M sell at 85% of the fill). */
    const val PREMIUM_STOP = 0.15

    /** The stop's trigger for a [fill]: 15% below, rounded down to the 0.05 tick; null for a price with no room. */
    fun stopTrigger(fill: Double): Double? {
        val t = kotlin.math.floor(fill * (1 - PREMIUM_STOP) / OrbRules.TICK + 1e-9) * OrbRules.TICK
        val r = kotlin.math.round(t * 100) / 100.0
        return r.takeIf { it >= OrbRules.TICK && it < fill }
    }

    fun minutesOf(arm: Arm): Int = if (arm.source == ARM15.source) 15 else 5

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

    fun mayEnterAt(entry: LocalDateTime): Boolean { val t = entry.toLocalTime(); return !t.isBefore(FIRST_ENTRY) && !t.isAfter(LAST_ENTRY) }
}
