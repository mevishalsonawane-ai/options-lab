package com.optionslab.ira

import com.optionslab.engine.Right
import com.optionslab.engine.Session
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.ProfitLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * The rules every trade Jarvis suggests follows, and how they are judged (the owner's wishes, 2026-10-02): the option
 * as the Liquidity arm picks it, played on REAL option minute prices with the arm's 15% stop, a +40 target and the
 * profit lock, out by 15:15 ([OptionSim]); no new suggestion after 13:00 on that index's expiry day; trades stay on
 * paper until their own paper record is good ([proven]); and each evening's scorecard of every suggestion - taken or
 * not - on what it would have made. Pure.
 */
object JarvisTrades {
    const val TARGET_POINTS = OrbRules.TARGET_POINTS
    /** Points taken off every simulated trade for charges and slippage (one lot, both ways). */
    const val COST_POINTS = 1.0
    const val EXIT_MINUTE = 15 * 60 + 15
    /** No new suggestion on an index's expiry day from this minute (decay and jumps are fastest). */
    const val EXPIRY_CUTOFF = 13 * 60
    /** Paper trades needed, and a positive net, before Jarvis's trades may go live. */
    const val PROVEN_TRADES = 20

    fun strikeStep(underlying: String): Int = if (underlying == "NIFTY") 50 else LiquidityRules.strikeStep(underlying)

    /** On expiry day after 13:00: a reason not to suggest, else null. */
    fun expiryBlock(expiryToday: Boolean, now: LocalDateTime): String? =
        if (expiryToday && now.hour * 60 + now.minute >= EXPIRY_CUTOFF) "It is expiry day after 13:00: no new suggestions (option prices decay and jump fastest now)." else null

    object OptionSim {
        /**
         * The trade Jarvis would place at [at] (IST) on [s]'s day: the ATM call (put) on [step] around [spot], the next
         * expiry after the day (else that day's), bought at the first minute's close at or after [at]; out at the stop
         * 15% below, the +40 target, the profit-lock stop, or 15:15. Points per unit after [COST_POINTS]; null when the
         * contract has no prices.
         */
        fun trade(s: Session, at: LocalDateTime, call: Boolean, spot: Double, step: Int): Double? {
            val strike = Math.round(spot / step) * step.toDouble()
            val right = if (call) Right.CE else Right.PE
            val expiries = s.options.mapNotNull { it.expiry }.distinct().sorted()
            val expiry = expiries.firstOrNull { it.isAfter(s.day) } ?: expiries.firstOrNull { !it.isBefore(s.day) } ?: return null
            val o = s.options.firstOrNull { it.expiry == expiry && it.right == right && kotlin.math.abs(it.strike - strike) < 0.01 } ?: return null
            val start = at.hour * 60 + at.minute
            var i = (0 until o.size).firstOrNull { o.minutes[it] >= start } ?: return null
            if (o.minutes[i] > start + 5 || o.minutes[i] >= EXIT_MINUTE) return null
            val entry = o.close[i]
            if (entry <= 0) return null
            val stop = LiquidityRules.stopTrigger(entry) ?: entry * 0.85
            val target = entry + TARGET_POINTS
            var peak = entry
            i++
            while (i < o.size) {
                val m = o.minutes[i]
                val lo = o.low?.get(i) ?: o.close[i]; val hi = o.high?.get(i) ?: o.close[i]
                val lock = ProfitLock.level(entry, TARGET_POINTS, peak)
                val floor = maxOf(stop, lock ?: Double.NEGATIVE_INFINITY)
                // Out at 15:15: the last price before it (the 15:14 minute's close).
                if (m >= EXIT_MINUTE) return o.close[i - 1] - entry - COST_POINTS
                // A stop fills at the stop, or worse when the minute opened below it (a jump).
                if (lo <= floor) return minOf(floor, o.open?.get(i) ?: floor) - entry - COST_POINTS
                if (hi >= target) return target - entry - COST_POINTS
                peak = maxOf(peak, hi)
                i++
            }
            return o.close[o.size - 1] - entry - COST_POINTS
        }
    }

    /** One closed Jarvis trade on paper, for [proven]. */
    data class Closed(val day: LocalDate, val rupees: Double, val live: Boolean)

    /** Has Jarvis's own paper record earned live trading? (null: yes; else why not, in words). */
    fun proven(closed: List<Closed>): String? {
        val paper = closed.filter { !it.live }
        val net = paper.sumOf { it.rupees }
        return when {
            paper.size < PROVEN_TRADES -> "My trades stay on paper until $PROVEN_TRADES have closed there: ${paper.size} so far" +
                (if (paper.isNotEmpty()) ", net ${AppFacts.rs(net)}." else ".")
            net <= 0 -> "My paper trades are not making money yet (${paper.size} trades, net ${AppFacts.rs(net)}): they stay on paper."
            else -> null
        }
    }

    /** One suggestion, for the evening scorecard. */
    data class Suggestion(val at: LocalDateTime, val market: Market, val call: Boolean, val spot: Double, val source: String,
                          /** "approved", "rejected", "lapsed" or "waiting". */ val answer: String,
                          /** What the trade made or would have made (points per unit, real option prices), once known. */ val points: Double? = null,
                          val lot: Int? = null)

    /** The day's scorecard: each suggestion, what was answered, and what it made or would have. */
    fun scorecard(day: LocalDate, all: List<Suggestion>): List<String> {
        val today = all.filter { it.at.toLocalDate() == day }
        if (today.isEmpty()) return listOf("No trades suggested today.")
        val lines = today.map { s ->
            val what = "${"%02d:%02d".format(s.at.hour, s.at.minute)} ${s.market.label} ${if (s.call) "call" else "put"} (${s.source.substringBefore(':')})"
            val res = s.points?.let { p -> val r = s.lot?.let { " (${AppFacts.rs(p * it)} a lot)" } ?: ""
                (if (s.answer == "approved") "made " else "would have made ") + "%+.1f".format(Locale.ENGLISH, p) + " points$r" } ?: "no option prices to judge it"
            "$what: ${s.answer}, $res."
        }
        val judged = today.filter { it.points != null }
        val right = judged.count { (it.points!! > 0) == (it.answer == "approved") }
        return listOf("Today I suggested ${today.size} trade${if (today.size > 1) "s" else ""}; " +
            (if (judged.isEmpty()) "none could be judged yet." else "your answer was the better choice on $right of ${judged.size}.")) + lines
    }
}
