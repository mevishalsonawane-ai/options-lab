package com.optionslab.ira.neuro

import com.optionslab.ira.dhan.Files
import com.optionslab.ira.dhan.Plan
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The market events the graph learns about, each a fixed, written-down rule on the stored candles (the thresholds are in
 * [Rules]; nothing is tuned to the data). Daily events come from an index's daily candles, option events from its stored
 * expired options (the near series, ATM-10..ATM+10, one-minute), so they exist only on days with options kept.
 */
enum class Ev(val label: String, val options: Boolean, val atOpen: Boolean = false, val vix: Boolean = false) {
    GAP_UP("a gap up of ${Rules.GAP_PCT}% or more", false, atOpen = true),
    GAP_DOWN("a gap down of ${Rules.GAP_PCT}% or more", false, atOpen = true),
    BIG_UP("an up day of ${Rules.BIG_MOVE_PCT}% or more", false),
    BIG_DOWN("a down day of ${Rules.BIG_MOVE_PCT}% or more", false),
    BIG_RANGE("a big-range day (${Rules.BIG_RANGE_X}x the 20-session average range)", false),
    VIX_SPIKE("India VIX up ${Rules.VIX_MOVE_PCT}% or more on the day", false, vix = true),
    VIX_DROP("India VIX down ${Rules.VIX_MOVE_PCT}% or more on the day", false, vix = true),
    ZERO_TO_HERO("a zero-to-hero option (${Rules.ZTH_X}x from its intraday low)", true),
    HERO_TO_ZERO("a hero-to-zero option (lost 90% from its intraday high)", true),
    OI_BUILDUP("an open-interest build-up (near strikes +${Rules.OI_PCT}% in the day)", true),
    OI_UNWIND("an open-interest unwind (near strikes -${Rules.OI_PCT}% in the day)", true),
    IV_SPIKE("an IV spike (at-the-money IV +${Rules.IV_SPIKE_PCT}% on the day before)", true),
    STRADDLE_EXPANSION("a late straddle expansion (ATM straddle ${(Rules.STRADDLE_EXP * 100).toInt()}% off its low since 12:00, 13:30-15:00)", true);

    val bit: Int get() = 1 shl ordinal

    /** The short name said aloud ("gap up", "zero-to-hero"). */
    val short: String get() = when (this) {
        GAP_UP -> "a gap up"; GAP_DOWN -> "a gap down"; BIG_UP -> "a big up day"; BIG_DOWN -> "a big down day"
        BIG_RANGE -> "a big-range day"; VIX_SPIKE -> "a VIX spike"; VIX_DROP -> "a VIX drop"; ZERO_TO_HERO -> "a zero-to-hero move"
        HERO_TO_ZERO -> "a hero-to-zero move"; OI_BUILDUP -> "an OI build-up"; OI_UNWIND -> "an OI unwind"; IV_SPIKE -> "an IV spike"
        STRADDLE_EXPANSION -> "a late straddle expansion"
    }
}

/** Trend regimes of an index, read from the 20 sessions BEFORE the day (never the day itself); HIGH_VOL is a flag beside them. */
enum class Regime(val label: String) {
    TREND_UP("in an uptrend"), TREND_DOWN("in a downtrend"), RANGE("in a range"), HIGH_VOL("in high volatility")
}

/** The fixed thresholds of the events and regimes. */
object Rules {
    const val GAP_PCT = 0.5
    const val BIG_MOVE_PCT = 1.0
    const val BIG_RANGE_X = 1.5
    const val VIX_MOVE_PCT = 10.0
    /** Zero-to-hero: an option reaching [ZTH_X] times its running intraday low (the low counted as at least [ZTH_FLOOR] rupee). */
    const val ZTH_X = 25.0
    const val ZTH_FLOOR = 1.0
    /** Hero-to-zero: an option that traded at [HTZ_FROM] or more and later in the day at [HTZ_LEFT] of its high or less. */
    const val HTZ_FROM = 20.0
    const val HTZ_LEFT = 0.10
    const val OI_PCT = 25.0
    const val IV_SPIKE_PCT = 20.0
    /** As the Hero arm's study: the straddle this far above its low since 12:00, at a minute from 13:30 to 15:00. */
    const val STRADDLE_EXP = 0.15
    const val TREND_PCT = 3.0
    const val TREND_SESSIONS = 20
    const val HIGH_VIX = 18.0
    /** Without VIX: 10-session realised volatility (annualised, %) at or above this is high volatility. */
    const val HIGH_RV = 20.0
    /** The time-of-day buckets (IST minute of the day, start inclusive), 9:15 to 15:30. */
    val TOD: List<Pair<Int, Int>> = listOf(555 to 600, 600 to 690, 690 to 780, 780 to 870, 870 to 931)
    fun todLabel(b: Int): String = TOD[b].let { (a, z) -> "${hm(a)}-${hm(z - if (z == 931) 1 else 0)}" }
    private fun hm(m: Int) = "%d:%02d".format(m / 60, m % 60)
    fun tod(minute: Int): Int = TOD.indexOfFirst { minute >= it.first && minute < it.second }
    /**
     * Beside the [Ev] bits in a [DayRow.mask]: the session itself moved [BIG_MOVE_PCT] or more from its OPEN (close / open).
     * The same-session (lag 0) links after a gap are judged on these, never on BIG_UP / BIG_DOWN (close against the previous
     * close), which already contain the gap they would be "predicted" by.
     */
    const val OC_UP = 1 shl 24
    const val OC_DOWN = 1 shl 25

    /** The expiry-day mark read from the data (as [com.optionslab.ira.dhan.ExpiredOptions.EXPIRY_TIME_VALUE]). */
    const val EXPIRY_TIME_VALUE = 0.0012

    /** The daily events of a session with open/high/low/close [o]/[h]/[l]/[c] after a close of [prev]; [avgRangePct] the prior 20 sessions' (NaN: unknown). */
    fun daily(prev: Double, o: Double, h: Double, l: Double, c: Double, avgRangePct: Double, vix: Boolean): Int {
        if (prev <= 0) return 0
        val move = (c / prev - 1) * 100
        if (vix) return (if (move >= VIX_MOVE_PCT) Ev.VIX_SPIKE.bit else 0) or (if (move <= -VIX_MOVE_PCT) Ev.VIX_DROP.bit else 0)
        var m = 0
        val gap = (o / prev - 1) * 100
        if (gap >= GAP_PCT) m = m or Ev.GAP_UP.bit
        if (gap <= -GAP_PCT) m = m or Ev.GAP_DOWN.bit
        if (move >= BIG_MOVE_PCT) m = m or Ev.BIG_UP.bit
        if (move <= -BIG_MOVE_PCT) m = m or Ev.BIG_DOWN.bit
        if (o > 0) {
            val session = (c / o - 1) * 100
            if (session >= BIG_MOVE_PCT) m = m or OC_UP
            if (session <= -BIG_MOVE_PCT) m = m or OC_DOWN
        }
        val range = (h - l) / prev * 100
        if (!avgRangePct.isNaN() && avgRangePct > 0 && range >= BIG_RANGE_X * avgRangePct) m = m or Ev.BIG_RANGE.bit
        return m
    }

    /** The trend regime from the closes of the sessions BEFORE the day, oldest first (at least 21); null when too few. */
    fun trend(prior: List<Double>): Regime? {
        if (prior.size < TREND_SESSIONS + 1) return null
        val last = prior.last()
        val back = prior[prior.size - 1 - TREND_SESSIONS]
        if (back <= 0) return null
        val sma = prior.takeLast(TREND_SESSIONS).average()
        val ret = (last / back - 1) * 100
        return when {
            ret >= TREND_PCT && last > sma -> Regime.TREND_UP
            ret <= -TREND_PCT && last < sma -> Regime.TREND_DOWN
            else -> Regime.RANGE
        }
    }

    /** High volatility: India VIX's last close before the day at [HIGH_VIX] or more; without it, the realised volatility of [priorCloses]. */
    fun highVol(vixBefore: Double?, priorCloses: List<Double>): Boolean {
        if (vixBefore != null && vixBefore > 0) return vixBefore >= HIGH_VIX
        val c = priorCloses.takeLast(11)
        if (c.size < 11) return false
        val r = c.zipWithNext { a, b -> if (a > 0) b / a - 1 else 0.0 }
        val m = r.average()
        val sd = sqrt(r.sumOf { (it - m) * (it - m) } / (r.size - 1))
        return sd * sqrt(250.0) * 100 >= HIGH_RV
    }

    /**
     * One contract's minutes in a day, oldest first ([lows], [highs]): the first minute it reached [ZTH_X] times its running
     * low (the low floored at [ZTH_FLOOR]) - or -1 - and whether it lost 90% from a high of [HTZ_FROM] or more later on.
     */
    fun contractDay(minutes: IntArray, lows: DoubleArray, highs: DoubleArray, n: Int = minutes.size): Pair<Int, Boolean> {
        var runLow = Double.MAX_VALUE
        var runHigh = 0.0
        var zth = -1
        var htz = false
        for (i in 0 until n) {
            val lo = lows[i]; val hi = highs[i]
            if (lo <= 0 || hi <= 0) continue
            if (runHigh >= HTZ_FROM && lo <= HTZ_LEFT * runHigh) htz = true
            runLow = min(runLow, max(lo, ZTH_FLOOR))
            if (zth < 0 && hi >= ZTH_X * runLow) zth = minutes[i]
            runHigh = max(runHigh, hi)
        }
        return zth to htz
    }
}

/** One day of an index's stored options, reduced to what the events need. */
data class OptDay(val zthMinute: Int, val htz: Boolean, val oiStart: Double, val oiEnd: Double, val ivClose: Double,
                  val lateExpansion: Boolean, val expiryLike: Boolean)

/**
 * Reads one stored window of an index's expired options (every CE/PE file, ATM-10..ATM+10) into one [OptDay] per day.
 * The rolling files keep "n strikes from the money", so a contract moves between files as the index moves: rows are
 * regrouped by (day, strike, side) with one sort of packed primitive keys - one window (25 days) is all that is held.
 */
object OptionDays {
    private const val SLOTS = 375
    private const val OPEN = 555
    private const val IST_OFFSET = 19_800L

    fun epochDay(t: Long): Int = Math.floorDiv(t + IST_OFFSET, 86_400L).toInt()
    fun minuteOfDay(t: Long): Int = (Math.floorMod(t + IST_OFFSET, 86_400L) / 60).toInt()

    private fun parseName(name: String): Pair<Boolean, Int>? {
        val base = name.removeSuffix(".csv.gz")
        if (base.length < 3 || !name.endsWith(".csv.gz")) return null
        val call = when (base.take(2)) { "CE" -> true; "PE" -> false; else -> return null }
        return call to (base.drop(2).toIntOrNull() ?: return null)
    }

    private class Grow {
        var keys = LongArray(1 shl 14); var low = FloatArray(1 shl 14); var high = FloatArray(1 shl 14); var oi = FloatArray(1 shl 14)
        var n = 0
        fun add(k: Long, l: Float, h: Float, o: Float) {
            if (n == keys.size) {
                val m = n * 2
                keys = keys.copyOf(m); low = low.copyOf(m); high = high.copyOf(m); oi = oi.copyOf(m)
            }
            keys[n] = k or n.toLong(); low[n] = l; high[n] = h; oi[n] = o; n++
        }
    }

    /** The days of [u]'s [flag] window starting [w], each reduced to an [OptDay]. Rows outside the window are read there. */
    fun read(files: Files, u: String, flag: String, w: LocalDate): Map<Int, OptDay> {
        val dir = files.file("opt/${Plan.safe(u)}/$flag/$w")
        val w0 = w.toEpochDay().toInt()
        val end = w0 + Plan.OPT_WINDOW_DAYS.toInt()
        val g = Grow()
        // The at-the-money pair, minute by minute: call close, put close, call IV, put IV, spot, call strike, put strike.
        val atm = HashMap<Int, Array<FloatArray>>()
        for (f in dir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            val (call, offset) = parseName(f.name) ?: continue
            for (line in files.lines(f)) {
                val c = files.parseOption(line) ?: continue
                val d = epochDay(c.t)
                if (d < w0 || d >= end) continue
                val m = minuteOfDay(c.t)
                if (m < OPEN || m >= OPEN + SLOTS) continue
                val strikeCode = Math.round(c.strike * 20).coerceIn(0L, (1L shl 22) - 1)
                val key = ((d - w0).toLong() shl 58) or (strikeCode shl 36) or ((if (call) 1L else 0L) shl 35) or (m.toLong() shl 24)
                if (g.n < (1 shl 24) - 1) g.add(key, c.low.toFloat(), c.high.toFloat(), c.oi.toFloat())
                if (offset == 0) {
                    val a = atm.getOrPut(d) { Array(7) { FloatArray(SLOTS) { Float.NaN } } }
                    val s = m - OPEN
                    if (call) { a[0][s] = c.close.toFloat(); a[2][s] = c.iv.toFloat(); a[5][s] = c.strike.toFloat() }
                    else { a[1][s] = c.close.toFloat(); a[3][s] = c.iv.toFloat(); a[6][s] = c.strike.toFloat() }
                    if (c.spot > 0) a[4][s] = c.spot.toFloat()
                }
            }
        }
        val keys = g.keys.copyOf(g.n)
        java.util.Arrays.sort(keys)
        val zth = HashMap<Int, Int>(); val htz = HashSet<Int>()
        val oiStart = HashMap<Int, Double>(); val oiEnd = HashMap<Int, Double>()
        val days = HashSet<Int>()
        var i = 0
        val mins = IntArray(SLOTS * 4); val lows = DoubleArray(SLOTS * 4); val highs = DoubleArray(SLOTS * 4)
        while (i < keys.size) {
            val group = keys[i] ushr 35
            var j = i
            var n = 0
            var firstOi = Float.NaN; var lastOi = Float.NaN
            while (j < keys.size && (keys[j] ushr 35) == group) {
                val row = (keys[j] and 0xFFFFFF).toInt()
                if (n < mins.size) {
                    mins[n] = ((keys[j] ushr 24) and 0x7FF).toInt(); lows[n] = g.low[row].toDouble(); highs[n] = g.high[row].toDouble(); n++
                }
                val o = g.oi[row]
                if (o > 0) { if (firstOi.isNaN()) firstOi = o; lastOi = o }
                j++
            }
            val d = w0 + (group ushr 23).toInt()
            days += d
            val (z, h) = Rules.contractDay(mins, lows, highs, n)
            if (z >= 0) zth[d] = zth[d]?.let { min(it, z) } ?: z
            if (h) htz += d
            if (!firstOi.isNaN()) {
                oiStart[d] = (oiStart[d] ?: 0.0) + firstOi
                oiEnd[d] = (oiEnd[d] ?: 0.0) + lastOi
            }
            i = j
        }
        val out = java.util.TreeMap<Int, OptDay>()
        for (d in days.sorted()) {
            val a = atm[d]
            var iv = Double.NaN; var late = false; var expiry = false
            if (a != null) {
                var lastBoth = -1
                var runLow = Double.MAX_VALUE
                for (s in 0 until SLOTS) {
                    val ce = a[0][s]; val pe = a[1][s]
                    if (ce.isNaN() || pe.isNaN() || abs(a[5][s] - a[6][s]) > 1e-3) continue
                    lastBoth = s
                    if (a[2][s] > 0 && a[3][s] > 0) iv = (a[2][s] + a[3][s]) / 2.0
                    val m = s + OPEN
                    val st = (ce + pe).toDouble()
                    if (m >= 720 && st > 0) {
                        if (m >= 810 && m <= 900 && runLow < Double.MAX_VALUE && st >= (1 + Rules.STRADDLE_EXP) * runLow) late = true
                        runLow = min(runLow, st)
                    }
                }
                if (lastBoth >= 0) {
                    val spot = a[4][lastBoth]
                    if (!spot.isNaN() && spot > 0) {
                        val tv = a[0][lastBoth] + a[1][lastBoth] - abs(spot - a[5][lastBoth])
                        expiry = tv / spot < Rules.EXPIRY_TIME_VALUE
                    }
                }
            }
            out[d] = OptDay(zth[d] ?: -1, d in htz, oiStart[d] ?: 0.0, oiEnd[d] ?: 0.0, iv, late, expiry)
        }
        return out
    }
}
