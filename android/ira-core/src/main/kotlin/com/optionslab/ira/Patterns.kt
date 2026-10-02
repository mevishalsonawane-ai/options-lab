package com.optionslab.ira

import kotlin.math.max
import kotlin.math.min

/** The patterns Ira recognises, and which way each one leans (+1 up, -1 down, 0 either way). */
enum class PatternKind(val label: String, val bias: Int) {
    BULLISH_ENGULFING("bullish engulfing", 1),
    BEARISH_ENGULFING("bearish engulfing", -1),
    HAMMER("hammer", 1),
    SHOOTING_STAR("shooting star", -1),
    DOJI("doji", 0),
    INSIDE_BAR("inside bar", 0),
    THREE_WHITE_SOLDIERS("three green candles in a row", 1),
    THREE_BLACK_CROWS("three red candles in a row", -1),
    BREAKOUT_UP("breakout above the last 20 candles' high", 1),
    BREAKOUT_DOWN("breakdown below the last 20 candles' low", -1),
    DOUBLE_TOP("double top", -1),
    DOUBLE_BOTTOM("double bottom", 1),
}

/** A pattern found on the candle at [at] of a [minutes]-minute chart. */
data class Pattern(val kind: PatternKind, val minutes: Int, val at: java.time.LocalDateTime, val price: Double)

/** Pattern recognition on completed candles. Pure. */
object Patterns {
    const val LOOKBACK = 20

    /** Every pattern that completes on candle [i] of [c]. */
    fun at(c: List<Candle>, i: Int): List<PatternKind> {
        if (i < 1 || i >= c.size) return emptyList()
        val out = ArrayList<PatternKind>()
        val x = c[i]; val p = c[i - 1]
        val avgBody = c.subList(max(0, i - LOOKBACK), i).map { it.body }.average().takeIf { !it.isNaN() } ?: x.body
        if (x.range > 0) {
            val upper = x.h - max(x.o, x.c); val lower = min(x.o, x.c) - x.l
            if (p.red && x.green && x.c >= p.o && x.o <= p.c && x.body > p.body) out += PatternKind.BULLISH_ENGULFING
            if (p.green && x.red && x.c <= p.c.coerceAtMost(p.o) && x.o >= p.c && x.body > p.body) out += PatternKind.BEARISH_ENGULFING
            if (x.body <= 0.1 * x.range && x.range >= 0.5 * avgBody) out += PatternKind.DOJI
            else {
                if (lower >= 2 * x.body && upper <= 0.5 * x.body.coerceAtLeast(x.range * 0.1) && x.body > 0) out += PatternKind.HAMMER
                if (upper >= 2 * x.body && lower <= 0.5 * x.body.coerceAtLeast(x.range * 0.1) && x.body > 0) out += PatternKind.SHOOTING_STAR
            }
        }
        if (x.h < p.h && x.l > p.l) out += PatternKind.INSIDE_BAR
        if (i >= 2) {
            val a = c[i - 2]
            if (a.green && p.green && x.green && p.c > a.c && x.c > p.c && listOf(a, p, x).all { it.body >= 0.6 * avgBody }) out += PatternKind.THREE_WHITE_SOLDIERS
            if (a.red && p.red && x.red && p.c < a.c && x.c < p.c && listOf(a, p, x).all { it.body >= 0.6 * avgBody }) out += PatternKind.THREE_BLACK_CROWS
        }
        if (i >= LOOKBACK) {
            val prior = c.subList(i - LOOKBACK, i)
            if (x.c > prior.maxOf { it.h }) out += PatternKind.BREAKOUT_UP
            if (x.c < prior.minOf { it.l }) out += PatternKind.BREAKOUT_DOWN
            double(c, i)?.let { out += it }
        }
        return out
    }

    /**
     * A double top (bottom) confirmed on candle [i]: two swing highs (lows) within 0.15% of each other in the last
     * [LOOKBACK] candles, at least 4 candles apart, and candle [i] closing below (above) the low (high) between them.
     */
    private fun double(c: List<Candle>, i: Int): PatternKind? {
        val from = i - LOOKBACK
        val win = c.subList(from, i)
        val (hi, lo) = Candles.swings(win, 2)
        for (a in hi.indices) for (b in a + 1 until hi.size) {
            val h1 = win[hi[a]].h; val h2 = win[hi[b]].h
            if (hi[b] - hi[a] < 4 || kotlin.math.abs(h1 - h2) > 0.0015 * h1) continue
            val trough = win.subList(hi[a], hi[b] + 1).minOf { it.l }
            if (c[i].c < trough && c[i - 1].c >= trough) return PatternKind.DOUBLE_TOP
        }
        for (a in lo.indices) for (b in a + 1 until lo.size) {
            val l1 = win[lo[a]].l; val l2 = win[lo[b]].l
            if (lo[b] - lo[a] < 4 || kotlin.math.abs(l1 - l2) > 0.0015 * l1) continue
            val peak = win.subList(lo[a], lo[b] + 1).maxOf { it.h }
            if (c[i].c > peak && c[i - 1].c <= peak) return PatternKind.DOUBLE_BOTTOM
        }
        return null
    }

    /** All patterns on the candles of [c] from index [from] on. */
    fun scan(c: List<Candle>, minutes: Int, from: Int = 1): List<Pattern> =
        (max(1, from) until c.size).flatMap { i -> at(c, i).map { Pattern(it, minutes, c[i].t, c[i].c) } }
}
