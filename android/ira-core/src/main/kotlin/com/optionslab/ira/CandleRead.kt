package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The Candle agent's eyes (Boss, 20 Oct: "an agent who will only study the candle patterns, how the candle works, each and
 * every candle: if some high candles are up, why it happened, from where it got the support/resistance, the different
 * behaviours of that candle, why it's green, why it's red; then use all the OI, gamma and everything we can").
 *
 * One closed candle read three ways, all from that candle and the ones before it (nothing after it is ever looked at here):
 *  - its anatomy ([Anatomy]): body, upper and lower wick as shares of the range, where it closed in the range, its colour,
 *    its size against ATR14 through the previous candle and its volume against the day's usual candle;
 *  - its shapes ([shapes]): the R87 families with R87's exact thresholds (research/hunt/r87_candle_anatomy/candles.py and
 *    context.py), so the research's numbers can be said beside it; [primary] picks the most specific one;
 *  - its context ([Context]): the trend (R87's EMA50 rule), the day's and the previous day's high and low, the opening
 *    range, VWAP (only where there is volume: an index has none), round numbers, the previous day's value area, the option
 *    chain's OI walls, OI change and PCR, the gamma regime, the time of day and the expiry day; and the nearest support and
 *    resistance with where each level came from ([Level]).
 *
 * Signal source: the underlying's own candle (the index spot, or the MCX future), never an option's chart. It never says
 * buy or sell: a read is knowledge. Pure and cheap: no clock, no I/O; the app hands it candles off the main thread in a batch.
 */
object CandleRead {
    /** One candle; [v] its volume (null: none, as for an index). Times are the market's own clock (IST). */
    data class Bar(val t: LocalDateTime, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double? = null) {
        val range: Double get() = h - l
        val ok: Boolean get() = o.isFinite() && h.isFinite() && l.isFinite() && c.isFinite() && o > 0 && h >= l && h >= max(o, c) && l <= min(o, c)
    }

    /** One strike of the option chain at the time of the candle: OI and its change since the chain before. */
    data class Strike(val strike: Double, val ceOi: Long, val peOi: Long, val ceOiChg: Long = 0, val peOiChg: Long = 0)

    /**
     * What the other agents and the app's stores add around a candle (each may be missing; a missing one is said, never
     * guessed): the chain ([strikes]), the gamma regime ("long" / "short", GammaRegime's sign), an outside VWAP (the future's,
     * when the index has no volume) and whether the day is an expiry day.
     */
    data class Around(
        val strikes: List<Strike> = emptyList(), val gamma: String? = null, val vwap: Double? = null, val expiry: Boolean = false,
        /** Where the VWAP came from ("the future's flow"), said beside it. */
        val vwapFrom: String? = null,
    )

    data class Anatomy(
        val bodyPct: Double, val upperPct: Double, val lowerPct: Double,
        /** Where it closed in its range: 0 at the low, 1 at the high. */
        val closeAt: Double,
        /** +1 green (close above open), -1 red, 0 flat. */
        val colour: Int,
        val range: Double, val body: Double, val upper: Double, val lower: Double,
        /** ATR14 through the previous candle (null: fewer than 15 candles yet). */
        val atr: Double?,
        /** Range / ATR (null without ATR). */
        val size: Double?,
        /** Volume / the median of the day's earlier candles (null: no volume, or too few candles). */
        val volRatio: Double?,
    )

    enum class LevelKind(val words: String) {
        PREV_HIGH("the previous day's high"), PREV_LOW("the previous day's low"), PREV_CLOSE("the previous day's close"),
        DAY_HIGH("today's high so far"), DAY_LOW("today's low so far"), OR_HIGH("the opening range high"), OR_LOW("the opening range low"),
        VWAP("VWAP"), ROUND("a round number"), CALL_WALL("the biggest call OI (a call wall)"), PUT_WALL("the biggest put OI (a put wall)"),
        VAH("the previous day's value-area high"), VAL("the previous day's value-area low"), SWING_HIGH("a recent swing high"),
        SWING_LOW("a recent swing low"),
    }

    /** A price level and where it came from. [distAtr]: its distance from the close in ATR (null without ATR). */
    data class Level(val price: Double, val kind: LevelKind, val distAtr: Double?) {
        fun words(): String = "${px(price)} (${kind.words}${distAtr?.let { ", ${f1(abs(it))} ATR away" } ?: ""})"
    }

    data class Context(
        /** +1 up, -1 down, 0 flat or not known (R87: close above EMA50 and EMA50 rising over 10 candles). */
        val trend: Int,
        /** Close in the last 50 candles' range, 0 bottom, 1 top (null: fewer than 50). */
        val pos50: Double?,
        val dayHighBefore: Double?, val dayLowBefore: Double?,
        val prevHigh: Double?, val prevLow: Double?, val prevClose: Double?,
        val orHigh: Double?, val orLow: Double?,
        val vwap: Double?, val vwapFrom: String?,
        val vah: Double?, val val_: Double?,
        val callWall: Double?, val putWall: Double?, val pcr: Double?,
        /** +1: puts added and calls cut over the last chain read (R87's bull OI shift), -1 the mirror, 0 neither or unknown. */
        val oiShift: Int,
        val gamma: String?,
        /** "open", "morning", "midday", "afternoon", "close" (R87's buckets; MCX adds "evening"). */
        val timeOfDay: String,
        val expiry: Boolean,
        /** ATR against its usual (the median of the 100 before): "high" > 1.3, "low" < 0.8, "normal", or null. */
        val volRegime: String?,
        val support: Level?, val resistance: Level?,
        /** The context words the stats are kept by ([tags]). */
        val tags: List<String>,
    )

    /** One read: the candle, its anatomy, shapes (most specific first), the primary shape and its context. */
    data class Read(
        val inst: String, val tf: Int, val bar: Bar, val index: Int, val anatomy: Anatomy, val shapes: List<String>, val primary: String,
        val context: Context,
    ) {
        /** Worth explaining and sharing: a named shape (not just green / red / tiny / inside), a big candle or a stop run. */
        val notable: Boolean get() = primary !in PLAIN || (anatomy.size ?: 0.0) >= 1.5
        val key: String get() = "$inst|$tf|${bar.t}"
    }

    /** Shapes too ordinary to post or explain on their own. */
    val PLAIN = setOf("green", "red", "flat", "tiny_range", "inside_bar", "spinning_top", "small_both_wicks")

    /** Most specific first: the shape a read is named by. */
    val PRIORITY = listOf(
        "fp_stop_run_up", "fp_stop_run_down", "three_white", "three_black", "morning_star", "evening_star", "bull_engulfing", "bear_engulfing",
        "bull_harami", "bear_harami", "tweezer_bottom", "tweezer_top", "marubozu_green", "marubozu_red", "hammer", "hanging_man", "inverted_hammer",
        "shooting_star", "hammer_shape_flat", "inverted_shape_flat", "dragonfly_doji", "gravestone_doji", "long_legged_doji", "doji",
        "both_big_wicks", "big_lower_wick", "big_upper_wick", "big_body_green", "big_body_red", "huge_range", "spinning_top", "outside_bar",
        "inside_bar", "small_both_wicks", "tiny_range", "green", "red", "flat",
    )

    /** Plain names (R87's, with Boss's words in brackets). */
    val NAMES: Map<String, String> = mapOf(
        "doji" to "doji", "dragonfly_doji" to "dragonfly doji", "gravestone_doji" to "gravestone doji", "long_legged_doji" to "long-legged doji",
        "spinning_top" to "spinning top", "marubozu_green" to "green marubozu (all body)", "marubozu_red" to "red marubozu (all body)",
        "big_body_green" to "big green body", "big_body_red" to "big red body", "hammer" to "hammer", "hanging_man" to "hanging man",
        "hammer_shape_flat" to "hammer shape with no trend", "inverted_hammer" to "inverted hammer", "shooting_star" to "shooting star",
        "inverted_shape_flat" to "inverted hammer shape with no trend", "big_upper_wick" to "big head (long upper wick)",
        "big_lower_wick" to "big tail (long lower wick)", "both_big_wicks" to "big head and big tail", "small_both_wicks" to "small head and tail",
        "tiny_range" to "tiny candle", "huge_range" to "huge candle", "green" to "green candle", "red" to "red candle", "flat" to "flat candle",
        "inside_bar" to "inside bar", "outside_bar" to "outside bar", "bull_engulfing" to "bullish engulfing", "bear_engulfing" to "bearish engulfing",
        "bull_harami" to "bullish harami", "bear_harami" to "bearish harami", "tweezer_bottom" to "tweezer bottom", "tweezer_top" to "tweezer top",
        "three_white" to "three white soldiers", "three_black" to "three black crows", "morning_star" to "morning star", "evening_star" to "evening star",
        "fp_stop_run_up" to "stop run above the day's high", "fp_stop_run_down" to "stop run below the day's low",
    )

    fun name(shape: String): String = NAMES[shape] ?: shape.replace('_', ' ')

    const val ATR_N = 14
    const val EMA_N = 50

    // ---- anatomy ---------------------------------------------------------------------------------------------------------

    /** Wilder ATR14 of [bars] through the PREVIOUS candle (R87): null for the first 14. True range never crosses a session. */
    fun atrPrev(bars: List<Bar>): Array<Double?> {
        val n = bars.size
        val out = arrayOfNulls<Double>(n)
        if (n <= ATR_N) return out
        val tr = DoubleArray(n) { i ->
            val b = bars[i]
            if (i > 0 && bars[i - 1].t.toLocalDate() == b.t.toLocalDate()) max(b.h, bars[i - 1].c) - min(b.l, bars[i - 1].c) else b.range
        }
        var a = (0 until ATR_N).sumOf { tr[it] } / ATR_N
        // atr through candle ATR_N-1 is a; the candle after it reads it.
        out[ATR_N] = a
        for (i in ATR_N until n - 1) { a += (tr[i] - a) / ATR_N; out[i + 1] = a }
        return out
    }

    fun anatomy(b: Bar, atr: Double?, volRatio: Double?): Anatomy {
        val r = b.range
        val body = abs(b.c - b.o)
        val up = b.h - max(b.o, b.c)
        val lo = min(b.o, b.c) - b.l
        val col = if (b.c > b.o) 1 else if (b.c < b.o) -1 else 0
        fun share(x: Double) = if (r > 0) x / r else 0.0
        val size = if (atr != null && atr > 0) r / atr else null
        return Anatomy(share(body), share(up), share(lo), if (r > 0) (b.c - b.l) / r else 0.5, col, r, body, up, lo, atr?.takeIf { it > 0 }, size, volRatio)
    }

    // ---- shapes (R87's thresholds) -------------------------------------------------------------------------------------------

    /**
     * Every R87 family the candle at [i] is in, given its anatomy [a], the trend before it and the day's high / low before it.
     * Two- and three-candle patterns need the earlier candles in the same session. Without ATR only the colour is known.
     */
    fun shapes(bars: List<Bar>, i: Int, a: Anatomy, trend: Int, dayHighBefore: Double?, dayLowBefore: Double?, atrs: Array<Double?>): List<String> {
        val f = LinkedHashSet<String>()
        val b = bars[i]
        when (a.colour) { 1 -> f += "green"; -1 -> f += "red"; else -> f += "flat" }
        val s = a.size ?: return f.toList()
        val atr = a.atr ?: return f.toList()
        val bp = a.bodyPct; val u = a.upperPct; val w = a.lowerPct
        val doji = bp <= 0.10
        if (doji) f += "doji"
        if (doji && w >= 0.60 && u <= 0.15) f += "dragonfly_doji"
        if (doji && u >= 0.60 && w <= 0.15) f += "gravestone_doji"
        if (doji && u >= 0.30 && w >= 0.30 && s >= 1.0) f += "long_legged_doji"
        if (bp > 0.10 && bp <= 0.35 && u >= 0.25 && w >= 0.25) f += "spinning_top"
        if (bp >= 0.85 && s >= 0.8 && a.colour > 0) f += "marubozu_green"
        if (bp >= 0.85 && s >= 0.8 && a.colour < 0) f += "marubozu_red"
        if (a.body / atr >= 1.2 && a.colour > 0) f += "big_body_green"
        if (a.body / atr >= 1.2 && a.colour < 0) f += "big_body_red"
        val ham = w >= 0.60 && bp > 0.10 && bp <= 0.35 && u <= 0.15
        val inv = u >= 0.60 && bp > 0.10 && bp <= 0.35 && w <= 0.15
        if (ham) f += when { trend < 0 -> "hammer"; trend > 0 -> "hanging_man"; else -> "hammer_shape_flat" }
        if (inv) f += when { trend < 0 -> "inverted_hammer"; trend > 0 -> "shooting_star"; else -> "inverted_shape_flat" }
        if (u >= 0.50 && s >= 1.0) f += "big_upper_wick"
        if (w >= 0.50 && s >= 1.0) f += "big_lower_wick"
        if (u >= 0.30 && w >= 0.30 && s >= 1.0) f += "both_big_wicks"
        if (u <= 0.10 && w <= 0.10 && s >= 0.5) f += "small_both_wicks"
        if (s < 0.5) f += "tiny_range"
        if (s >= 2.0) f += "huge_range"
        if (dayHighBefore != null && b.h > dayHighBefore && b.c < dayHighBefore) f += "fp_stop_run_up"
        if (dayLowBefore != null && b.l < dayLowBefore && b.c > dayLowBefore) f += "fp_stop_run_down"
        val day = b.t.toLocalDate()
        val p1 = bars.getOrNull(i - 1)?.takeIf { it.t.toLocalDate() == day }
        if (p1 != null) {
            val col1 = sign(p1.c - p1.o); val b1 = abs(p1.c - p1.o)
            if (b.h <= p1.h && b.l >= p1.l) f += "inside_bar"
            if (b.h > p1.h && b.l < p1.l) f += "outside_bar"
            if (a.colour > 0 && col1 < 0 && b.c >= p1.o && b.o <= p1.c && a.body > b1) f += "bull_engulfing"
            if (a.colour < 0 && col1 > 0 && b.c <= p1.o && b.o >= p1.c && a.body > b1) f += "bear_engulfing"
            if (col1 < 0 && b1 >= 0.6 * atr && a.colour > 0 && b.o >= p1.c && b.c <= p1.o) f += "bull_harami"
            if (col1 > 0 && b1 >= 0.6 * atr && a.colour < 0 && b.o <= p1.c && b.c >= p1.o) f += "bear_harami"
            if (col1 < 0 && a.colour > 0 && abs(b.l - p1.l) <= 0.05 * atr) f += "tweezer_bottom"
            if (col1 > 0 && a.colour < 0 && abs(b.h - p1.h) <= 0.05 * atr) f += "tweezer_top"
            val p2 = bars.getOrNull(i - 2)?.takeIf { it.t.toLocalDate() == day }
            if (p2 != null) {
                val col2 = sign(p2.c - p2.o); val b2 = abs(p2.c - p2.o)
                fun bpOf(x: Bar) = if (x.range > 0) abs(x.c - x.o) / x.range else 0.0
                if (a.colour > 0 && col1 > 0 && col2 > 0 && b.c > p1.c && p1.c > p2.c && bp >= 0.5 && bpOf(p1) >= 0.5 && bpOf(p2) >= 0.5) f += "three_white"
                if (a.colour < 0 && col1 < 0 && col2 < 0 && b.c < p1.c && p1.c < p2.c && bp >= 0.5 && bpOf(p1) >= 0.5 && bpOf(p2) >= 0.5) f += "three_black"
                if (col2 < 0 && b2 >= 0.8 * atr && b1 <= 0.3 * atr && a.colour > 0 && b.c > (p2.o + p2.c) / 2) f += "morning_star"
                if (col2 > 0 && b2 >= 0.8 * atr && b1 <= 0.3 * atr && a.colour < 0 && b.c < (p2.o + p2.c) / 2) f += "evening_star"
            }
        }
        return PRIORITY.filter { it in f }
    }

    fun primary(shapes: List<String>): String = PRIORITY.firstOrNull { it in shapes } ?: "flat"

    private fun sign(x: Double) = if (x > 0) 1 else if (x < 0) -1 else 0

    // ---- context ---------------------------------------------------------------------------------------------------------

    /** EMA of [c] (R87: warm-up of [n] - 1 candles gives null). */
    fun ema(c: List<Double>, n: Int = EMA_N): Array<Double?> {
        val k = 2.0 / (n + 1)
        val out = arrayOfNulls<Double>(c.size)
        var v = Double.NaN
        for (i in c.indices) {
            v = if (v.isNaN()) c[i] else v + k * (c[i] - v)
            if (i >= n - 1) out[i] = v
        }
        return out
    }

    /** R87's trend: +1 close above EMA50 and EMA50 above its value 10 candles ago, -1 the mirror, 0 otherwise or unknown. */
    fun trend(closes: List<Double>, emas: Array<Double?>, i: Int): Int {
        val e = emas.getOrNull(i) ?: return 0
        val e10 = emas.getOrNull(i - 10) ?: return 0
        val c = closes[i]
        return when { c > e && e > e10 -> 1; c < e && e < e10 -> -1; else -> 0 }
    }

    /** The round-number step for [inst] at [price]: 100 for NIFTY, 500 for BANKNIFTY and SENSEX, else about 1% of the price. */
    fun roundStep(inst: String, price: Double): Double = when (inst.uppercase(Locale.ENGLISH)) {
        "NIFTY", "FINNIFTY" -> 100.0
        "BANKNIFTY", "SENSEX", "BANKEX" -> 500.0
        "MIDCPNIFTY" -> 100.0
        else -> {
            // About 1% of the price: 5,600 -> 50, 1,20,000 -> 1,000, 280 -> 5.
            val p = abs(price).coerceAtLeast(1.0)
            val top = Math.pow(10.0, floor(Math.log10(p)))
            top / 100 * (if (p / top >= 5) 5 else 1)
        }
    }

    /** R87's time-of-day buckets (minutes of the day, IST), with the evening for MCX. */
    fun timeOfDay(minute: Int): String = when {
        minute < 10 * 60 -> "open"
        minute < 11 * 60 + 30 -> "morning"
        minute < 13 * 60 + 30 -> "midday"
        minute < 14 * 60 + 30 -> "afternoon"
        minute < 15 * 60 + 40 -> "close"
        else -> "evening"
    }

    /**
     * The previous session's value area (70% of its time at price, around the price it spent longest at), from its candles:
     * a time profile (an index has no volume), [bin] points a row. Null for fewer than 10 candles.
     */
    fun valueArea(prev: List<Bar>, bin: Double): Pair<Double, Double>? {
        if (prev.size < 10 || bin <= 0) return null
        val lo = prev.minOf { it.l }; val hi = prev.maxOf { it.h }
        val rows = ((hi - lo) / bin).toInt() + 1
        if (rows <= 0 || rows > 5_000) return null
        val tpo = IntArray(rows)
        for (b in prev) for (r in ((b.l - lo) / bin).toInt()..((b.h - lo) / bin).toInt().coerceAtMost(rows - 1)) tpo[r]++
        val total = tpo.sum()
        var poc = 0
        for (r in 1 until rows) if (tpo[r] > tpo[poc]) poc = r
        var a = poc; var z = poc; var got = tpo[poc]
        while (got < 0.7 * total && (a > 0 || z < rows - 1)) {
            val down = if (a > 0) tpo[a - 1] else -1
            val up = if (z < rows - 1) tpo[z + 1] else -1
            if (up >= down) { z++; got += tpo[z] } else { a--; got += tpo[a] }
        }
        return (lo + (z + 1) * bin) to (lo + a * bin)
    }

    /** The context of candle [i] of [bars] (the instrument's candles of one timeframe, oldest first, possibly many days). */
    fun context(
        inst: String, tf: Int, bars: List<Bar>, i: Int, atrs: Array<Double?>, emas: Array<Double?>, closes: List<Double>, around: Around,
        sessionOpenMin: Int, vaCache: MutableMap<LocalDate, Pair<Double, Double>?> = HashMap(),
    ): Context {
        val b = bars[i]
        val day = b.t.toLocalDate()
        var first = i
        while (first > 0 && bars[first - 1].t.toLocalDate() == day) first--
        val today = bars.subList(first, i)                      // the session's earlier candles
        var pFirst = first - 1
        val prevDay: LocalDate? = bars.getOrNull(first - 1)?.t?.toLocalDate()
        while (pFirst > 0 && bars[pFirst - 1].t.toLocalDate() == prevDay) pFirst--
        val prev = if (prevDay != null) bars.subList(pFirst.coerceAtLeast(0), first) else emptyList()
        val tr = trend(closes, emas, i)
        val pos50 = if (i >= 49) {
            val w = bars.subList(i - 49, i + 1); val hh = w.maxOf { it.h }; val ll = w.minOf { it.l }
            if (hh > ll) (b.c - ll) / (hh - ll) else null
        } else null
        val dh = today.maxOfOrNull { it.h }; val dl = today.minOfOrNull { it.l }
        val ph = prev.maxOfOrNull { it.h }; val pl = prev.minOfOrNull { it.l }; val pc = prev.lastOrNull()?.c
        val orEnd = b.t.toLocalDate().atStartOfDay().plusMinutes((sessionOpenMin + 15).toLong())
        val orBars = today.filter { it.t.plusMinutes(tf.toLong()) <= orEnd }
        val orReady = !b.t.isBefore(orEnd) && orBars.isNotEmpty()
        val orh = if (orReady) orBars.maxOf { it.h } else null
        val orl = if (orReady) orBars.minOf { it.l } else null
        // VWAP: from the candles' own volume when they have it, else the one the app passes (the future's), else none.
        val withVol = (today + b).filter { (it.v ?: 0.0) > 0 }
        val ownVwap = if (withVol.size == today.size + 1 && withVol.isNotEmpty())
            withVol.sumOf { (it.h + it.l + it.c) / 3 * it.v!! } / withVol.sumOf { it.v!! } else null
        val vwap = ownVwap ?: around.vwap
        val vwapFrom = if (ownVwap != null) "its own volume" else if (around.vwap != null) around.vwapFrom ?: "the app" else null
        val atr = atrs[i]
        val va = if (prevDay == null) null else vaCache.getOrPut(prevDay) { valueArea(prev, roundStep(inst, b.c) / 20) }
        // The chain: the biggest call OI at or above the close (resistance), the biggest put OI at or below (support).
        val calls = around.strikes.filter { it.strike >= b.c && it.ceOi > 0 }.maxByOrNull { it.ceOi }
        val puts = around.strikes.filter { it.strike <= b.c && it.peOi > 0 }.maxByOrNull { it.peOi }
        val ceSum = around.strikes.sumOf { it.ceOi }; val peSum = around.strikes.sumOf { it.peOi }
        val pcr = if (ceSum > 0) peSum.toDouble() / ceSum else null
        val dce = around.strikes.sumOf { it.ceOiChg }; val dpe = around.strikes.sumOf { it.peOiChg }
        val oiShift = when { dpe > 0 && dce < 0 -> 1; dce > 0 && dpe < 0 -> -1; else -> 0 }
        val volReg = atr?.let { a ->
            val past = (maxOf(0, i - 100) until i).mapNotNull { atrs[it] }
            if (past.size < 50) null else {
                val med = past.sorted()[past.size / 2]
                if (med <= 0) null else (a / med).let { r -> if (r > 1.3) "high" else if (r < 0.8) "low" else "normal" }
            }
        }
        // The swing points of the last 50 candles (3 a side), only those already confirmed by candles before this one.
        val swingFrom = maxOf(0, i - 50)
        val swing = bars.subList(swingFrom, i)
        val (sh, sl) = swings(swing, 3)
        val levels = ArrayList<Pair<Double, LevelKind>>()
        ph?.let { levels += it to LevelKind.PREV_HIGH }; pl?.let { levels += it to LevelKind.PREV_LOW }; pc?.let { levels += it to LevelKind.PREV_CLOSE }
        dh?.let { levels += it to LevelKind.DAY_HIGH }; dl?.let { levels += it to LevelKind.DAY_LOW }
        orh?.let { levels += it to LevelKind.OR_HIGH }; orl?.let { levels += it to LevelKind.OR_LOW }
        vwap?.let { levels += it to LevelKind.VWAP }
        va?.let { levels += it.first to LevelKind.VAH; levels += it.second to LevelKind.VAL }
        calls?.let { levels += it.strike to LevelKind.CALL_WALL }; puts?.let { levels += it.strike to LevelKind.PUT_WALL }
        for (k in sh) levels += swing[k].h to LevelKind.SWING_HIGH
        for (k in sl) levels += swing[k].l to LevelKind.SWING_LOW
        val step = roundStep(inst, b.c)
        val below = floor(b.c / step) * step
        levels += below to LevelKind.ROUND
        levels += (below + step) to LevelKind.ROUND
        fun dist(p: Double) = atr?.let { (p - b.c) / it }
        // The nearest level under the close and over it; on a tie the stronger source (the chain, then yesterday, then today).
        val sup = levels.filter { it.first < b.c }.minWithOrNull(compareBy<Pair<Double, LevelKind>>({ b.c - it.first }, { it.second.ordinal }))
            ?.let { Level(it.first, it.second, dist(it.first)) }
        val res = levels.filter { it.first > b.c }.minWithOrNull(compareBy<Pair<Double, LevelKind>>({ it.first - b.c }, { it.second.ordinal }))
            ?.let { Level(it.first, it.second, dist(it.first)) }
        val minute = b.t.hour * 60 + b.t.minute
        val tod = timeOfDay(minute)
        val tags = ArrayList<String>()
        tags += when (tr) { 1 -> "uptrend"; -1 -> "downtrend"; else -> "flat_trend" }
        if (dh != null && b.h > dh) tags += "new_day_high"
        if (dl != null && b.l < dl) tags += "new_day_low"
        if (ph != null && b.h > ph && b.c < ph) tags += "sweep_pdh"
        if (pl != null && b.l < pl && b.c > pl) tags += "sweep_pdl"
        if (atr != null) {
            if (sup != null && (b.c - sup.price) <= 0.3 * atr) tags += "at_support"
            if (res != null && (res.price - b.c) <= 0.3 * atr) tags += "at_resistance"
            if (vwap != null && (b.c - vwap) / atr >= 1.5) tags += "far_above_vwap"
            if (vwap != null && (b.c - vwap) / atr <= -1.5) tags += "far_below_vwap"
        }
        if (minute < sessionOpenMin + 60) tags += "first_hour"
        if (tod == "close") tags += "last_hour"
        if (around.expiry) tags += "expiry_day"
        when (around.gamma) { "short" -> tags += "short_gamma"; "long" -> tags += "long_gamma" }
        when (oiShift) { 1 -> tags += "bull_oi_shift"; -1 -> tags += "bear_oi_shift" }
        when (volReg) { "high" -> tags += "high_vol"; "low" -> tags += "low_vol" }
        return Context(tr, pos50, dh, dl, ph, pl, pc, orh, orl, vwap, vwapFrom, va?.first, va?.second, calls?.strike, puts?.strike, pcr, oiShift,
            around.gamma, tod, around.expiry, volReg, sup, res, tags)
    }

    /** Swing highs and lows of [c] ([k] candles each side, all inside [c]): indices. */
    private fun swings(c: List<Bar>, k: Int): Pair<List<Int>, List<Int>> {
        val hi = ArrayList<Int>(); val lo = ArrayList<Int>()
        for (i in k until c.size - k) {
            var isHi = true; var isLo = true
            for (j in i - k..i + k) { if (j == i) continue; if (c[j].h >= c[i].h) isHi = false; if (c[j].l <= c[i].l) isLo = false }
            if (isHi) hi += i; if (isLo) lo += i
        }
        return hi to lo
    }

    // ---- the read ----------------------------------------------------------------------------------------------------------

    /** Is the candle at [i] a frozen print (no range, and the four before it flat at the same price)? Never read as a candle. */
    fun stale(bars: List<Bar>, i: Int): Boolean {
        val b = bars[i]
        if (b.range > 0) return false
        if (i < 4) return false
        return (i - 4 until i).all { bars[it].range == 0.0 && bars[it].c == b.c }
    }

    /**
     * Every closed candle of [bars] after [after] (all when null), read ([Read]); bad rows (non-finite, high under low) and frozen prints are
     * skipped, never guessed. [around] gives the chain, gamma and expiry per day (null: none known). Pure.
     */
    fun readAll(inst: String, tf: Int, bars: List<Bar>, after: LocalDateTime? = null, sessionOpenMin: Int = 9 * 60 + 15,
                around: (LocalDate) -> Around = { Around() }): List<Read> {
        val clean = bars.filter { it.ok }
        if (clean.isEmpty()) return emptyList()
        val atrs = atrPrev(clean)
        val closes = clean.map { it.c }
        val emas = ema(closes)
        val out = ArrayList<Read>()
        // Only the candles after [after] are read (the ones before were read in an earlier batch); ATR and the trend still
        // come from every candle before them.
        val start = if (after == null) 0 else clean.indexOfFirst { it.t.isAfter(after) }.let { if (it < 0) clean.size else it }
        val aroundOf = HashMap<LocalDate, Around>()
        val vaCache = HashMap<LocalDate, Pair<Double, Double>?>()
        for (i in start until clean.size) {
            if (stale(clean, i)) continue
            val b = clean[i]
            val day = b.t.toLocalDate()
            val ar = aroundOf.getOrPut(day) { around(day) }
            val volRatio = b.v?.takeIf { it > 0 }?.let { v ->
                var f = i; while (f > 0 && clean[f - 1].t.toLocalDate() == day) f--
                val vols = (f until i).mapNotNull { clean[it].v?.takeIf { x -> x > 0 } }.sorted()
                if (vols.size < 5) null else v / vols[vols.size / 2]
            }
            val a = anatomy(b, atrs[i], volRatio)
            val ctx = context(inst, tf, clean, i, atrs, emas, closes, ar, sessionOpenMin, vaCache)
            val sh = shapes(clean, i, a, ctx.trend, ctx.dayHighBefore, ctx.dayLowBefore, atrs)
            out += Read(inst, tf, b, i, a, sh, primary(sh), ctx)
        }
        return out
    }

    /** "20 Oct 10:35-10:40" (a 1-minute candle: "20 Oct 10:35"). */
    fun timeOf(t: LocalDateTime, tf: Int): String {
        val hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val d = "${t.dayOfMonth} ${t.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)}"
        return if (tf <= 1) "$d ${t.format(hm)}" else "$d ${t.format(hm)}-${t.plusMinutes(tf.toLong()).format(hm)}"
    }

    internal fun px(x: Double): String = String.format(Locale.ENGLISH, "%,.2f", x).removeSuffix(".00")
    internal fun f1(x: Double): String = String.format(Locale.ENGLISH, "%.1f", x)
    internal fun pct(x: Double): String = "${(x * 100).toInt()}%"
}
