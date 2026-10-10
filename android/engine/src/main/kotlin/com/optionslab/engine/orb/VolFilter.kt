package com.optionslab.engine.orb

import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Liquidity 15+5's candidate (c), the volatility risk filter (Boss's choice, 06 Oct 2026; the big-candle study on five years
 * of real 1-minute data: big 5-minute candles cluster right after a big candle, when the last half hour's realised volatility
 * is in its top 20% for the time of day, and at 15:00-15:05; their direction is a coin flip). It would skip a signal when
 * ANY of:
 *
 *   - the last completed 5-minute bar was a big candle: its body at least [BIG_MULTIPLE] times the typical (median) 5-minute
 *     body of the earlier sessions;
 *   - the realised volatility of the last [RV_MINUTES] minutes is above the [PERCENTILE] of the same time of day over the
 *     earlier sessions;
 *   - the entry falls at 15:00-15:05 (Liquidity's entries end at 14:00, so this one never fires there; kept for completeness).
 *
 * Tracked in the shadow of Liquidity's paper trades only: it never changes what it trades. Uses only the 1-minute bars that
 * closed by the signal bar's close (no look-ahead), and the last [SESSIONS] earlier sessions among them (whatever the arm
 * has loaded; at least [MIN_SESSIONS]). Without them the answer is unknown, never a skip. Pure: no clock, no storage.
 */
object VolFilter {
    const val BIG_MULTIPLE = 3.0
    const val RV_MINUTES = 30L
    const val PERCENTILE = 0.80
    const val SESSIONS = 20
    const val MIN_SESSIONS = 5
    /** Fewer 1-minute returns than this in a window: its volatility is not measured. */
    const val MIN_RETURNS = 5
    val LATE_FROM: LocalTime = LocalTime.of(15, 0)
    val LATE_UNTIL: LocalTime = LocalTime.of(15, 5)
    private val OPEN = LocalTime.of(9, 15)

    /** [wouldSkip]: true, false, or null (unknown: too little history); [reason]: why, in a line. */
    data class Verdict(val wouldSkip: Boolean?, val reason: String)

    /**
     * The verdict on a signal whose bar closes at [signalClose] (the entry moment), from the index's 1-minute bars [ones]
     * (today's and earlier sessions', any order). Bars that had not closed by [signalClose] are ignored.
     */
    fun judge(ones: List<Bar>, signalClose: LocalDateTime): Verdict {
        val day = signalClose.toLocalDate()
        val seen = ones.filter { !it.start.plusMinutes(1).isAfter(signalClose) && !it.start.toLocalTime().isBefore(OPEN) }
            .distinctBy { it.start }.sortedBy { it.start }
        val byDay = seen.groupBy { it.start.toLocalDate() }
        val today = byDay[day].orEmpty()
        val earlier = byDay.keys.filter { it.isBefore(day) }.sorted().takeLast(SESSIONS)
        val reasons = ArrayList<String>()

        val late = !signalClose.toLocalTime().isBefore(LATE_FROM) && !signalClose.toLocalTime().isAfter(LATE_UNTIL)
        if (late) reasons += "15:00-15:05"

        val typical = if (earlier.size < MIN_SESSIONS) null
            else median(earlier.flatMap { d -> LiquidityRules.fold(byDay.getValue(d), 5).map { body(it) } }).takeIf { it > 0 }
        val lastFive = LiquidityRules.completed(LiquidityRules.fold(today, 5), 5, signalClose).lastOrNull()
        val big = if (typical == null || lastFive == null) null else body(lastFive) >= BIG_MULTIPLE * typical
        if (big == true) reasons += "big candle: the ${hhmm(lastFive!!.start)} 5-min bar moved ${whole(body(lastFive))} points, " +
            "${String.format(Locale.ENGLISH, "%.1f", body(lastFive) / typical!!)}× the usual ${whole(typical)}"

        val now = rv(today, signalClose)
        val past = earlier.mapNotNull { d -> rv(byDay.getValue(d), d.atTime(signalClose.toLocalTime())) }
        val high = if (now == null || past.size < MIN_SESSIONS) null else now > percentile(past, PERCENTILE)
        if (high == true) reasons += "high volatility: the last 30 minutes in the top 20% for the time of day"

        return when {
            reasons.isNotEmpty() -> Verdict(true, reasons.joinToString("; "))
            big == null || high == null -> Verdict(null, "unknown: fewer than $MIN_SESSIONS earlier sessions or too few bars today")
            else -> Verdict(false, "calm")
        }
    }

    /** A 5-minute bar's body in points. */
    fun body(b: Bar): Double = abs(b.close - b.open)

    /**
     * Realised volatility of the [RV_MINUTES] minutes up to [end] on one day's 1-minute bars [day] (sorted): the root of the
     * summed squared log returns between successive closes. Null with fewer than [MIN_RETURNS] returns.
     */
    fun rv(day: List<Bar>, end: LocalDateTime): Double? {
        val closes = day.filter { it.start.isBefore(end) && !it.start.isBefore(end.minusMinutes(RV_MINUTES + 1)) }.map { it.close }
        if (closes.size - 1 < MIN_RETURNS) return null
        return sqrt(closes.zipWithNext { a, b -> ln(b / a).let { it * it } }.sum())
    }

    /** The [p] quantile of [xs] (not empty), linearly interpolated between the sorted values. */
    fun percentile(xs: List<Double>, p: Double): Double {
        val s = xs.sorted()
        val h = (s.size - 1) * p
        val lo = h.toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (h - lo) * (s[hi] - s[lo])
    }

    /** The median of [xs] (zero when empty). */
    fun median(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else percentile(xs, 0.5)

    private fun whole(x: Double) = String.format(Locale.ENGLISH, "%,.0f", x)
    private fun hhmm(t: LocalDateTime) = String.format(Locale.ENGLISH, "%02d:%02d", t.hour, t.minute)
}
