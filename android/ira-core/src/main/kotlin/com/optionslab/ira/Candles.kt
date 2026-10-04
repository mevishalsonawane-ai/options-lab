package com.optionslab.ira

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max

/** Candle maths Ira uses everywhere: folding, ATR, the trend tracker, swing points. Pure. */
object Candles {
    /**
     * 1-minute bars folded into [minutes]-minute candles. Indian indices count from 09:15 (so 15-minute candles start
     * 09:15, 09:30 ...); gold from the hour. A candle is listed once any of its minutes exists.
     */
    fun fold(bars: List<Candle>, minutes: Int, market: Market): List<Candle> {
        if (minutes <= 1) return bars
        val anchorMin = market.open?.let { it.hour * 60 + it.minute } ?: 0
        return bars.groupBy { b ->
            val m = b.t.hour * 60 + b.t.minute - anchorMin
            val slot = Math.floorDiv(m, minutes) * minutes + anchorMin
            b.t.truncatedTo(ChronoUnit.DAYS).plusMinutes(slot.toLong())
        }.toSortedMap().map { (t, g) -> Candle(t, g.first().o, g.maxOf { it.h }, g.minOf { it.l }, g.last().c) }
    }

    /** Candles that have closed by [now]. */
    fun closed(candles: List<Candle>, minutes: Int, now: LocalDateTime): List<Candle> =
        candles.filter { !it.t.plusMinutes(minutes.toLong()).isAfter(now) }

    fun trueRange(c: List<Candle>, i: Int): Double =
        if (i == 0) c[0].range else max(c[i].range, max(abs(c[i].h - c[i - 1].c), abs(c[i].l - c[i - 1].c)))

    /** Wilder's ATR per candle (seeded with the first range). */
    fun atr(c: List<Candle>, n: Int = 14): DoubleArray {
        val a = DoubleArray(c.size)
        for (i in c.indices) a[i] = if (i == 0) c[0].range else a[i - 1] + (trueRange(c, i) - a[i - 1]) / n
        return a
    }

    /**
     * The trend tracker (a Supertrend, as the TAS strategy draws it): +1 up, -1 down per candle, and its line - under
     * the price while up, above it while down. [n] the ATR length, [mult] the band width in ATRs.
     */
    fun tracker(c: List<Candle>, n: Int = 10, mult: Double = 3.0): Pair<IntArray, DoubleArray> {
        val a = atr(c, n)
        val dir = IntArray(c.size) { 1 }
        val line = DoubleArray(c.size)
        var fu = 0.0; var fl = 0.0; var up = true
        for (i in c.indices) {
            val mid = (c[i].h + c[i].l) / 2
            val ub = mid + mult * a[i]; val lb = mid - mult * a[i]
            if (i == 0) { fu = ub; fl = lb } else {
                val pc = c[i - 1].c; val pu = fu; val pl = fl
                fu = if (ub < pu || pc > pu) ub else pu
                fl = if (lb > pl || pc < pl) lb else pl
                up = if (c[i].c > pu) true else if (c[i].c < pl) false else up
            }
            dir[i] = if (up) 1 else -1
            line[i] = if (up) fl else fu
        }
        return dir to line
    }

    /** Swing highs and lows: a candle higher (lower) than the [k] candles each side. Indices into [c]. */
    fun swings(c: List<Candle>, k: Int = 3): Pair<List<Int>, List<Int>> {
        val hi = ArrayList<Int>(); val lo = ArrayList<Int>()
        for (i in k until c.size - k) {
            if ((i - k..i + k).all { it == i || c[it].h < c[i].h }) hi += i
            if ((i - k..i + k).all { it == i || c[it].l > c[i].l }) lo += i
        }
        return hi to lo
    }

    /** Where [x] sits among [values], 0..1 (the share at or below it). */
    fun percentile(values: List<Double>, x: Double): Double =
        if (values.isEmpty()) 0.5 else values.count { it <= x }.toDouble() / values.size
}
