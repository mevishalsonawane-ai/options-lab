package com.optionslab.engine.trend

import com.optionslab.engine.orb.Bar
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * R63's four trend-turn rules (16 Oct 2026, Boss: "check the current trend, and if the trend changes, sell the orders";
 * research/hunt/r63/PREREG.md, code research/hunt/r63/r63rules.py, ported line for line and held to it by golden vectors).
 * Read from the UNDERLYING only (the index, the stock, the MCX future): never from an option's own price.
 *
 * The underlying's 1-minute bars are laid on its session grid (row r = the minute starting at [Grid.open] + r). A k-minute
 * bar b covers rows kb .. kb+k-1, is complete when row kb+k-1 ends, and a decision on it acts at the open of row kb+k
 * ([KBar.act]). A minute counts only when its high, low and close are known (its open falls back to its close); a bar with
 * no minute does not exist. The indicators run on the bars of every session given, oldest first.
 *
 *   T1  a 15-minute close crosses EMA20 of the 15-minute closes against the trade
 *   T2  15-minute Supertrend(10, 3) flips against the trade
 *   T3  two 5-minute closes in a row of the same session beyond the session VWAP (the index has no volume: the session's
 *       equal-weight mean of the minutes' (H + L + C) / 3, a TWAP) against the trade; both bars closed while it was open
 *   T4  a 5-minute close beyond the last 10 completed 5-minute bars' extreme against the trade
 *
 * "Against" a call (long the underlying) = turned down; against a put (short) = turned up. A rule may sell only on a bar that
 * completes at least [MIN_OPEN_MINUTES] after the fill. Pure: no clock, no network, no storage, no orders.
 */
object TrendTurn {
    enum class Rule(val key: String, val minutes: Int, val label: String) {
        T1("T1", 15, "15-min close crossed EMA20"),
        T2("T2", 15, "15-min Supertrend(10, 3) flipped"),
        T3("T3", 5, "two 5-min closes beyond the day's VWAP"),
        T4("T4", 5, "5-min close broke the last 10 bars' range");

        companion object {
            fun of(key: String?): Rule? = entries.firstOrNull { it.key == key }
        }
    }

    const val MIN_OPEN_MINUTES = 5L
    const val EMA_N = 20
    const val ST_N = 10
    const val ST_MULT = 3.0
    const val DON_N = 10

    /** A session grid: its first minute and its number of rows (NSE / BSE: 09:15, 375 rows to 15:29). */
    data class Grid(val open: LocalTime, val rows: Int) {
        fun time(row: Int): LocalTime = open.plusMinutes(row.toLong())
        fun row(t: LocalTime): Int = (t.toSecondOfDay() - open.toSecondOfDay()) / 60

        companion object {
            val NSE = Grid(LocalTime.of(9, 15), 375)
            /** MCX: 09:00 to 23:55 (US winter time; a shorter session simply has no later minutes). */
            val MCX = Grid(LocalTime.of(9, 0), 895)
        }
    }

    /** One k-minute bar: [act] = the open of the minute after it (where a decision on it sells). */
    data class KBar(val date: LocalDate, val b: Int, val act: LocalDateTime, val open: Double, val high: Double, val low: Double,
                    val close: Double, val twap: Double)

    /** The k-minute bars of [minutes] (bar start times; any order; a minute seen twice keeps its first bar), oldest first. */
    fun bars(minutes: List<Bar>, k: Int, grid: Grid = Grid.NSE): List<KBar> {
        val out = ArrayList<KBar>()
        val nb = grid.rows / k + if (grid.rows % k != 0) 1 else 0
        for ((date, day) in minutes.groupBy { it.start.toLocalDate() }.toSortedMap()) {
            val o = DoubleArray(grid.rows) { Double.NaN }; val h = o.copyOf(); val l = o.copyOf(); val c = o.copyOf()
            val seen = BooleanArray(grid.rows)
            for (m in day) {
                val r = grid.row(m.start.toLocalTime())
                if (r !in 0 until grid.rows || seen[r] || m.start.second != 0) continue
                seen[r] = true
                o[r] = m.open; h[r] = m.high; l[r] = m.low; c[r] = m.close
            }
            val ok = BooleanArray(grid.rows) { good(h[it]) && good(l[it]) && good(c[it]) }
            val cs = DoubleArray(grid.rows); val cn = IntArray(grid.rows)
            var s = 0.0; var n = 0
            for (r in 0 until grid.rows) {
                if (ok[r]) { s += (h[r] + l[r] + c[r]) / 3.0; n++ }
                cs[r] = s; cn[r] = n
            }
            for (b in 0 until nb) {
                val r0 = k * b
                val r1 = minOf(k * b + k, grid.rows)
                val rows = (r0 until r1).filter { ok[it] }
                if (rows.isEmpty()) continue
                val f = rows.first()
                out += KBar(date, b, date.atTime(grid.time(r1)), if (good(o[f])) o[f] else c[f], rows.maxOf { h[it] }, rows.minOf { l[it] },
                    c[rows.last()], cs[r1 - 1] / cn[r1 - 1])
            }
        }
        return out
    }

    private fun good(x: Double) = x.isFinite() && x > 0

    /** EMA seeded with the first value, alpha 2 / (n + 1). */
    fun ema(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size)
        val a = 2.0 / (n + 1)
        for (i in x.indices) out[i] = if (i == 0) x[0] else a * x[i] + (1 - a) * out[i - 1]
        return out
    }

    /** Supertrend direction per bar: +1 up, -1 down, 0 before the ATR exists (Wilder's RMA seeded with the first n's mean). */
    fun supertrendDir(h: DoubleArray, l: DoubleArray, c: DoubleArray, n: Int = ST_N, mult: Double = ST_MULT): IntArray {
        val m = c.size
        val d = IntArray(m)
        if (m == 0) return d
        val tr = DoubleArray(m)
        tr[0] = h[0] - l[0]
        for (i in 1 until m) tr[i] = maxOf(h[i] - l[i], kotlin.math.abs(h[i] - c[i - 1]), kotlin.math.abs(l[i] - c[i - 1]))
        val atr = DoubleArray(m) { Double.NaN }
        if (m >= n) {
            atr[n - 1] = tr.take(n).average()
            for (i in n until m) atr[i] = (atr[i - 1] * (n - 1) + tr[i]) / n
        }
        var up = Double.NaN; var dn = Double.NaN; var cur = 0
        for (i in 0 until m) {
            if (!atr[i].isFinite()) continue
            val mid = (h[i] + l[i]) / 2.0
            val bu = mid - mult * atr[i]; val bd = mid + mult * atr[i]
            if (cur == 0) {
                up = bu; dn = bd; cur = if (c[i] >= mid) 1 else -1
            } else {
                up = if (c[i - 1] > up) maxOf(bu, up) else bu
                dn = if (c[i - 1] < dn) minOf(bd, dn) else bd
                if (cur == -1 && c[i] > dn) cur = 1 else if (cur == 1 && c[i] < up) cur = -1
            }
            d[i] = cur
        }
        return d
    }

    /** Per bar of [bars] (from [bars] with the rule's minutes): (turned against a call, turned against a put). */
    fun flags(rule: Rule, bars: List<KBar>): Pair<BooleanArray, BooleanArray> {
        val m = bars.size
        val ac = BooleanArray(m); val ap = BooleanArray(m)
        if (m < 2) return ac to ap
        val c = DoubleArray(m) { bars[it].close }
        when (rule) {
            Rule.T1 -> {
                val e = ema(c, EMA_N)
                for (i in 1 until m) {
                    val was = c[i - 1] >= e[i - 1]
                    ac[i] = was && c[i] < e[i]
                    ap[i] = !was && c[i] > e[i]
                }
            }
            Rule.T2 -> {
                val d = supertrendDir(DoubleArray(m) { bars[it].high }, DoubleArray(m) { bars[it].low }, c)
                for (i in 1 until m) { ac[i] = d[i - 1] == 1 && d[i] == -1; ap[i] = d[i - 1] == -1 && d[i] == 1 }
            }
            Rule.T3 -> for (i in 1 until m) {
                if (bars[i].date != bars[i - 1].date) continue
                ac[i] = c[i] < bars[i].twap && c[i - 1] < bars[i - 1].twap
                ap[i] = c[i] > bars[i].twap && c[i - 1] > bars[i - 1].twap
            }
            Rule.T4 -> for (i in DON_N until m) {
                val w = bars.subList(i - DON_N, i)
                ac[i] = c[i] < w.minOf { it.low }
                ap[i] = c[i] > w.maxOf { it.high }
            }
        }
        return ac to ap
    }

    /** One turn: where it sells ([act]) and, for T3, where the pair's first bar acted ([firstAct]). */
    data class Turn(val act: LocalDateTime, val firstAct: LocalDateTime?)

    /** Every turn of [rule] in [minutes], against a call ([call] true) or a put. */
    fun turns(rule: Rule, minutes: List<Bar>, call: Boolean, grid: Grid = Grid.NSE): List<Turn> {
        val bs = bars(minutes, rule.minutes, grid)
        val (ac, ap) = flags(rule, bs)
        val f = if (call) ac else ap
        return bs.indices.filter { f[it] }.map { i -> Turn(bs[i].act, if (rule == Rule.T3) bs[i - 1].act else null) }
    }

    /** The first turn that sells a position filled at [fill]: completed while open, >= [MIN_OPEN_MINUTES] after the fill. */
    fun exitTurn(rule: Rule, turns: List<Turn>, fill: LocalDateTime): Turn? = turns.firstOrNull { t ->
        !t.act.isBefore(fill.plusMinutes(MIN_OPEN_MINUTES)) && (rule != Rule.T3 || (t.firstAct != null && t.firstAct.isAfter(fill)))
    }

    /** How many bars a rule needs before it can say anything (EMA: 2; Supertrend: its ATR; Donchian: 10 before). */
    fun barsNeeded(rule: Rule): Int = when (rule) { Rule.T1 -> 2; Rule.T2 -> ST_N + 1; Rule.T3 -> 2; Rule.T4 -> DON_N + 1 }
}
