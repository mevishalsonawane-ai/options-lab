package com.optionslab.engine.gold

import com.optionslab.engine.orb.Bar
import java.time.LocalDateTime

/**
 * IraGoldAlgo's fourth arm, "TAS 1h" (paper, the owner's choice 2026-10-02; research/GOLD_TAS.md): the "Trend Analysis
 * Strategy" (Pine v6) on 1-hour candles, buys only, its tracker ATR period 19.
 *
 *   tracker  ATR = WMA(true range, 19); up = high + 2.2 ATR, down = low - 2.2 ATR. While up the line is max(line, down)
 *            and a close under it turns the trend down (the line jumps to up); the mirror while down
 *   score    six votes of +1 / -1: the tracker's direction, close vs the equilibrium (HMA(9) of the 55-candle regression
 *            line), the equilibrium's slope, RSI(14) vs 50, RSI vs its 14-candle average, +DI vs -DI (14); as % of 6
 *   entry    on a completed hour: the tracker turned up at most 10 candles ago (the turn's own candle counts 0) and the
 *            score is at least +50%; one buy per up-turn. BUY at the price then
 *   stop     the tracker line at the buy; R = entry - stop
 *   targets  1.5 R (33%), 2.5 R (33%), 3.5 R (the rest); once the first is reached the stop moves to the entry
 *   exits    also a completed hour with the tracker turned down (sell the rest)
 * Backtest, three years a standard lot after costs: +$165.5k (+47.6k, +52.2k, +65.7k), 222 trades, 45% won, t 2.21,
 * deepest drawdown -$38.1k.
 *
 * Pure: no clock, no network, no orders. Times UTC; prices mid.
 */
object GoldTas {
    const val ATR_LENGTH = 19
    const val MULTIPLIER = 2.2
    const val EQ_LENGTH = 55
    const val EQ_SMOOTH = 9
    const val RSI_LENGTH = 14
    const val RSI_AVERAGE = 14
    const val DMI_LENGTH = 14
    const val MIN_SCORE = 50.0
    /** A buy may come this many candles after the turn, if the score blocked it at first. */
    const val LATE_BARS = 10
    /** The targets in R and the share of the buy each sells (the last sells what is left). */
    val TARGETS: List<Pair<Double, Double>> = listOf(1.5 to 0.33, 2.5 to 0.33, 3.5 to 0.34)
    /** Fewer completed 1-hour candles than this: no decision (the equilibrium alone needs 66). */
    const val MIN_BARS = 100

    /**
     * The read after the last completed candle [bar]: the trend [up] or not, the tracker [line], the [score] in % (null
     * while the averages run in), [turn] the candle where the trend last turned up (null if not up, or up since the
     * start of the history) and [sinceTurn] candles since it.
     */
    data class Read(val bar: LocalDateTime, val up: Boolean, val line: Double, val score: Double?, val turn: LocalDateTime?, val sinceTurn: Int)

    /** May a buy be taken on this read (one per up-turn: the caller remembers the [Read.turn] it bought)? */
    fun buys(r: Read): Boolean = r.up && r.turn != null && r.sinceTurn <= LATE_BARS && (r.score ?: -1.0) >= MIN_SCORE

    /** The targets' prices for a buy at [entry] with the stop at [stop]. */
    fun targets(entry: Double, stop: Double): List<Double> = TARGETS.map { entry + it.first * (entry - stop) }

    fun read(hours: List<Bar>): Read? {
        if (hours.size < MIN_BARS) return null
        val n = hours.size
        val (dir, line) = tracker(hours)
        val score = score(hours, dir)
        val last = n - 1
        if (!line[last].isFinite()) return null
        val up = dir[last] == 1
        var turn: Int? = null
        if (up) {
            var i = last
            while (i > 0 && dir[i - 1] == 1) i--
            if (i > 0 && line[i - 1].isFinite()) turn = i
        }
        return Read(hours[last].start, up, line[last], score[last].takeIf { it.isFinite() }, turn?.let { hours[it].start }, turn?.let { last - it } ?: 0)
    }

    /** The tracker's direction (1 up, -1 down) and line per candle (NaN while its ATR runs in). */
    fun tracker(b: List<Bar>): Pair<IntArray, DoubleArray> {
        val a = wma(trueRange(b), ATR_LENGTH)
        val d = IntArray(b.size) { 1 }
        val line = DoubleArray(b.size) { Double.NaN }
        var t = Double.NaN; var di = 1
        for (i in b.indices) {
            if (!a[i].isFinite()) { d[i] = di; continue }
            val up = b[i].high + a[i] * MULTIPLIER
            val dn = b[i].low - a[i] * MULTIPLIER
            if (!t.isFinite()) t = dn
            else if (di == 1) { t = maxOf(t, dn); if (b[i].close < t) { di = -1; t = up } }
            else { t = minOf(t, up); if (b[i].close > t) { di = 1; t = dn } }
            line[i] = t; d[i] = di
        }
        return d to line
    }

    /** The trend score in % of the six votes per candle (NaN while the averages run in). */
    fun score(b: List<Bar>, dir: IntArray): DoubleArray {
        val n = b.size
        val c = DoubleArray(n) { b[it].close }
        val reg = DoubleArray(n) { Double.NaN }
        val m = EQ_LENGTH
        val xm = (m - 1) / 2.0
        val sxx = (0 until m).sumOf { (it - xm) * (it - xm) }
        for (i in m - 1 until n) {
            var sy = 0.0; var sxy = 0.0
            for (k in 0 until m) { val y = c[i - m + 1 + k]; sy += y; sxy += (k - xm) * y }
            val slope = sxy / sxx
            reg[i] = sy / m + slope * (m - 1 - xm)
        }
        val half = wma(reg, EQ_SMOOTH / 2); val full = wma(reg, EQ_SMOOTH)
        val eq = wma(DoubleArray(n) { 2 * half[it] - full[it] }, Math.round(Math.sqrt(EQ_SMOOTH.toDouble())).toInt())
        val gain = DoubleArray(n) { if (it == 0) Double.NaN else maxOf(c[it] - c[it - 1], 0.0) }
        val loss = DoubleArray(n) { if (it == 0) Double.NaN else maxOf(c[it - 1] - c[it], 0.0) }
        val ag = rma(gain, RSI_LENGTH); val al = rma(loss, RSI_LENGTH)
        val rsi = DoubleArray(n) { 100 - 100 / (1 + ag[it] / al[it]) }
        val rsiAvg = sma(rsi, RSI_AVERAGE)
        val plus = DoubleArray(n); val minus = DoubleArray(n)
        for (i in 1 until n) {
            val u = b[i].high - b[i - 1].high; val dn = b[i - 1].low - b[i].low
            plus[i] = if (u > dn && u > 0) u else 0.0
            minus[i] = if (dn > u && dn > 0) dn else 0.0
        }
        val atr = rma(trueRange(b), DMI_LENGTH)
        val ap = rma(plus, DMI_LENGTH); val am = rma(minus, DMI_LENGTH)
        val v = { cond: Boolean -> if (cond) 1 else -1 }
        return DoubleArray(n) { i ->
            if (!eq[i].isFinite() || !rsiAvg[i].isFinite() || i == 0) Double.NaN
            else (dir[i] + v(c[i] > eq[i]) + v(eq[i] > eq[i - 1]) + v(rsi[i] > 50) + v(rsi[i] > rsiAvg[i]) +
                v(ap[i] / atr[i] > am[i] / atr[i])) / 6.0 * 100
        }
    }

    private fun trueRange(b: List<Bar>) = DoubleArray(b.size) { i ->
        if (i == 0) b[0].high - b[0].low
        else maxOf(b[i].high - b[i].low, kotlin.math.abs(b[i].high - b[i - 1].close), kotlin.math.abs(b[i].low - b[i - 1].close))
    }

    /** Weighted average of the last [n] (weights 1..n, the newest heaviest); NaN unless all [n] are numbers. */
    internal fun wma(x: DoubleArray, n: Int): DoubleArray {
        val w = n * (n + 1) / 2.0
        return DoubleArray(x.size) { i ->
            if (i < n - 1) Double.NaN
            else { var s = 0.0; for (k in 0 until n) s += x[i - n + 1 + k] * (k + 1); if (s.isFinite()) s / w else Double.NaN }
        }
    }

    private fun sma(x: DoubleArray, n: Int) = DoubleArray(x.size) { i ->
        if (i < n - 1) Double.NaN else { var s = 0.0; for (k in i - n + 1..i) s += x[k]; if (s.isFinite()) s / n else Double.NaN }
    }

    /** Wilder's average (1/n), seeded with the first number. */
    private fun rma(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        var a = Double.NaN
        for (i in x.indices) {
            if (!x[i].isFinite()) { out[i] = a; continue }
            a = if (a.isFinite()) a + (x[i] - a) / n else x[i]
            out[i] = a
        }
        return out
    }
}
