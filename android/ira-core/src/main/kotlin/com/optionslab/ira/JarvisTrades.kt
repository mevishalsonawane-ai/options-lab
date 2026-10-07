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
 * as the Liquidity arm picks it, played on REAL option minute prices with a fixed 30-point stop, a +60 target (1 : 2 on
 * every index; Boss, 06 Oct) and the profit lock on that 60, out by 15:15 ([OptionSim]); never an option at
 * [MIN_PREMIUM] or less (a 30-point stop would be most of it); no new suggestion after 13:00 on that index's expiry
 * day; trades stay on paper until their own paper record is good ([proven]); and each evening's scorecard of every suggestion - taken or
 * not - on what it would have made. Pure.
 */
object JarvisTrades {
    /** Jarvis's own stop: a fixed 30 premium points below the price paid (not the Liquidity arm's 15%). */
    const val STOP_POINTS = 30.0
    /** Jarvis's own target: +60 premium points (1 : 2 on the stop); the profit-lock ladder is measured on it. */
    const val TARGET_POINTS = 60.0
    /** An option at this premium or less is neither suggested nor bought: a 30-point stop would be most of it. */
    const val MIN_PREMIUM = 35.0
    /** Points taken off every simulated trade for charges and slippage (one lot, both ways). */
    const val COST_POINTS = 1.0
    const val EXIT_MINUTE = 15 * 60 + 15
    /** No new suggestion on an index's expiry day from this minute (decay and jumps are fastest). */
    const val EXPIRY_CUTOFF = 13 * 60
    /** Paper trades needed, and a positive net, before Jarvis's trades may go live. */
    const val PROVEN_TRADES = 20

    /**
     * The resting stop for a buy at [entry]: [STOP_POINTS] below, rounded down to the 0.05 tick; null when that leaves
     * no room (0.05 or less, or not below the entry).
     */
    fun stopFor(entry: Double): Double? {
        if (!(entry > 0)) return null
        val t = kotlin.math.floor((entry - STOP_POINTS) / OrbRules.TICK + 1e-9) * OrbRules.TICK
        val r = kotlin.math.round(t * 100) / 100.0
        return r.takeIf { it > OrbRules.TICK + 1e-9 && it < entry }
    }

    /** The target for a buy at [entry]: [TARGET_POINTS] above. */
    fun targetFor(entry: Double): Double = entry + TARGET_POINTS

    /** The profit-lock stop on the 60 target ([ProfitLock.level]; [costPerUnit]: the first rung is breakeven after charges). */
    fun lockFor(entry: Double, peak: Double, costPerUnit: Double = 0.0): Double? = ProfitLock.level(entry, TARGET_POINTS, peak, costPerUnit)

    /** An option at [premium] that is too cheap for the 30-point stop: why it is not bought, else null. */
    fun premiumBlock(premium: Double): String? =
        if (premium.isFinite() && premium > 0 && premium <= MIN_PREMIUM)
            "The option costs %.2f, %.0f or less: my 30-point stop would be most of it, so I do not buy it.".format(Locale.ENGLISH, premium, MIN_PREMIUM)
        else null

    /**
     * The rules of Jarvis's own trades in words ("what is the stop loss for news trades"): the 30-point stop, the +60
     * target, the profit lock's rungs on it, the 15:15 exit and the cheapest premium bought.
     */
    fun rules(): String {
        fun n(x: Double) = "%.0f".format(Locale.ENGLISH, x)
        val (a, b, c) = ProfitLock.LADDER.map { it.first * TARGET_POINTS to it.second * TARGET_POINTS }.let { Triple(it[0], it[1], it[2]) }
        return "My own trades (news trades, pattern ideas, and Solo's setups when offered to you) buy 1 lot of the at-the-money option, nearest expiry, " +
            "with a fixed ${n(STOP_POINTS)}-point stop below the price paid and a +${n(TARGET_POINTS)} target - 1 : 2 on every index. " +
            "The profit lock then moves the stop up: at +${n(a.first)} to breakeven after charges, at +${n(b.first)} to +${n(b.second)}, " +
            "at +${n(c.first)} to +${n(c.second)}. Out by 15:15 at the latest, and never an option at ${n(MIN_PREMIUM)} or less " +
            "(the stop would be most of it). A trade already open keeps the stop it was placed with."
    }

    private val RULES_WHO = Regex("\\b(news|pattern|jarvis'?s?|your|your own|ai) (trades?|ideas?|setups?|suggestions?)\\b")
    private val RULES_WHAT = Regex("\\b(stop ?loss|stop|sl|target|rules?|exit|profit lock|risk)\\b")
    private val RULES_ACT = Regex("^(jarvis |hey jarvis |ok jarvis )?(set|move|change|trail|close|cancel|remove|raise|lower|place|put|modify|update|make|exit|square)\\b")

    /** A question about the rules of Jarvis's own trades ("what is the stop loss for news trades", "news trade ka target kya hai"). */
    fun rulesAsked(said: String): Boolean {
        val t = said.lowercase().replace("’", "'").replace(rx("[^a-z0-9' ]"), " ").replace(rx("\\s+"), " ").trim()
        return RULES_WHO.containsMatchIn(t) && RULES_WHAT.containsMatchIn(t) && !RULES_ACT.containsMatchIn(t) &&
            !rx("\\b(my|mine|mera|meri|mere)\\b").containsMatchIn(t) &&
            // About how a trade went ("why did the news trade hit its stop"), not the rules.
            !rx("\\b(why|did|hit|hits|lost|lose|losing|doing|closed|today|yesterday|record|how many)\\b").containsMatchIn(t)
    }

    fun strikeStep(underlying: String): Int = if (underlying == "NIFTY") 50 else LiquidityRules.strikeStep(underlying)

    /** On expiry day after 13:00: a reason not to suggest, else null. */
    fun expiryBlock(expiryToday: Boolean, now: LocalDateTime): String? =
        if (expiryToday && now.hour * 60 + now.minute >= EXPIRY_CUTOFF) "It is expiry day after 13:00: no new suggestions (option prices decay and jump fastest now)." else null

    object OptionSim {
        /**
         * The trade Jarvis would place at [at] (IST) on [s]'s day: the ATM call (put) on [step] around [spot], the next
         * expiry after the day (else that day's), bought at the first minute's close at or after [at]; out at the stop
         * 30 points below, the +60 target, the profit-lock stop on that 60 (its first rung breakeven after [COST_POINTS]),
         * or 15:15. Points per unit after [COST_POINTS]; null when the contract has no prices, or its price is
         * [MIN_PREMIUM] or less (Jarvis would not buy it).
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
            if (entry <= MIN_PREMIUM) return null
            val stop = stopFor(entry) ?: return null
            val target = targetFor(entry)
            var peak = entry
            i++
            while (i < o.size) {
                val m = o.minutes[i]
                val lo = o.low?.get(i) ?: o.close[i]; val hi = o.high?.get(i) ?: o.close[i]
                val lock = lockFor(entry, peak, COST_POINTS)
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
                          /** "approved", "rejected", "lapsed", "waiting" or [SELF]. */ val answer: String,
                          /** What the trade made or would have made (points per unit, real option prices), once known. */ val points: Double? = null,
                          val lot: Int? = null,
                          /** The conditions it came in, for [SelfCalibration] (null: not known then). */
                          val regime: Regime.Kind? = null, val ivRank: Double? = null,
                          /** The confidence it was put with, 1 to 5 ([Confidence]; null: not known then), for [HonestStars]. */
                          val stars: Int? = null)

    /** A paper trade Jarvis took on its own idea (ACT_PAPER): not Boss's answer, so it never counts as one. */
    const val SELF = "taken by me on paper"

    /** The day's scorecard: each suggestion, what was answered, and what it made or would have. */
    fun scorecard(day: LocalDate, all: List<Suggestion>): List<String> {
        val today = all.filter { it.at.toLocalDate() == day }
        if (today.isEmpty()) return listOf("No trades suggested today.")
        val lines = today.map { s ->
            val what = "${"%02d:%02d".format(s.at.hour, s.at.minute)} ${s.market.label} ${if (s.call) "call" else "put"} (${s.source.substringBefore(':')})"
            val res = s.points?.let { p -> val r = s.lot?.let { " (${AppFacts.rs(p * it)} a lot)" } ?: ""
                (if (s.answer == "approved" || s.answer == SELF) "made " else "would have made ") + "%+.1f".format(Locale.ENGLISH, p) + " points$r" } ?: "no option prices to judge it"
            "$what: ${s.answer}, $res."
        }
        val judged = today.filter { it.points != null && it.answer != SELF }
        val right = judged.count { (it.points!! > 0) == (it.answer == "approved") }
        return listOf("Today I suggested ${today.size} trade${if (today.size > 1) "s" else ""}; " +
            (if (judged.isEmpty()) "none could be judged yet." else "your answer was the better choice on $right of ${judged.size}.")) + lines
    }
}
