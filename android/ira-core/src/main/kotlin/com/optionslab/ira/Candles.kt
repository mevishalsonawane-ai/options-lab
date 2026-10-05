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
        // The same history is folded several times a pass (the book, then [Brain.read]): kept by its candles ([BarsKept]).
        if (bars.size in KEEP_FROM..KEEP_UP_TO) return folded.of(bars, FoldKey(minutes, market)) { foldNow(bars, minutes, market) }
        return foldNow(bars, minutes, market)
    }

    private data class FoldKey(val minutes: Int, val market: Market)

    /** Histories of this many candles are kept folded (a day or more, up to about 80 days of 1-minute bars). */
    private const val KEEP_FROM = 300
    private const val KEEP_UP_TO = 30_000
    private val folded = BarsKept<List<Candle>>(16)

    /**
     * [fold] read afresh. Bars in time order (as every history is kept) are folded in one walk: a candle's minutes are
     * then side by side, so each candle is the run of bars with the same start (counted as minutes since 1970, which
     * is equal exactly when the start is). Bars out of order are grouped as they always were ([foldGrouped]); both
     * give the same candles.
     */
    internal fun foldNow(bars: List<Candle>, minutes: Int, market: Market): List<Candle> {
        val anchorMin = market.open?.let { it.hour * 60 + it.minute } ?: 0
        val b = if (bars is RandomAccess) bars else ArrayList(bars)
        val n = b.size
        val slots = IntArray(n)
        val keys = LongArray(n)
        for (i in 0 until n) {
            val t = b[i].t
            val slot = Math.floorDiv(t.hour * 60 + t.minute - anchorMin, minutes) * minutes + anchorMin
            slots[i] = slot
            keys[i] = t.toLocalDate().toEpochDay() * 1440 + slot
            if (i > 0 && keys[i] < keys[i - 1]) return foldGrouped(bars, minutes, anchorMin)
        }
        val out = ArrayList<Candle>()
        var i = 0
        while (i < n) {
            var j = i
            var hi = b[i].h
            var lo = b[i].l
            while (j + 1 < n && keys[j + 1] == keys[i]) { j++; hi = maxOf(hi, b[j].h); lo = minOf(lo, b[j].l) }
            out += Candle(b[i].t.truncatedTo(ChronoUnit.DAYS).plusMinutes(slots[i].toLong()), b[i].o, hi, lo, b[j].c)
            i = j + 1
        }
        return out
    }

    /** [fold] by grouping, for bars in any order. */
    internal fun foldGrouped(bars: List<Candle>, minutes: Int, anchorMin: Int): List<Candle> {
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
