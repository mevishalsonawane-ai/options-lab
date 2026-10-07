package com.optionslab.ira

import com.optionslab.engine.Right
import com.optionslab.engine.Session
import com.optionslab.engine.options.OptionMath
import com.optionslab.engine.options.OptionType
import java.time.LocalDate
import java.util.Locale

/**
 * Is the option cheap or expensive? (the owner's wish, 2026-10-02): the at-the-money implied volatility of each past
 * session (from the real option prices the phone keeps), and where today's stands among the last year's. Jarvis does
 * not suggest buying options when they are in the dearest tenth of the year - the move has to be bigger just to pay
 * for the option - and says it is careful in the dearest quarter. Pure.
 */
object IvRank {
    /** Read at 10:30, after the opening swings. */
    const val MINUTE = 10 * 60 + 30
    const val BLOCK = 0.90
    const val CAREFUL = 0.75
    const val MIN_DAYS = 60

    /** Annualised at-the-money IV of [s] at [minute] (the call and put averaged), or null without prices. */
    fun atm(s: Session, step: Int, minute: Int = MINUTE): Double? {
        val ix = s.index ?: return null
        val i = ix.lastAtOrBefore(minute).takeIf { it >= 0 } ?: return null
        val spot = ix.close[i]
        val strike = Math.round(spot / step) * step.toDouble()
        val expiry = s.options.mapNotNull { it.expiry }.distinct().sorted().firstOrNull { it.isAfter(s.day) } ?: return null
        val days = java.time.temporal.ChronoUnit.DAYS.between(s.day, expiry).toDouble() + (15.5 * 60 - minute) / (24 * 60)
        val t = days / 365.0
        val ivs = listOf(Right.CE to OptionType.CE, Right.PE to OptionType.PE).mapNotNull { (r, ot) ->
            val o = s.options.firstOrNull { it.expiry == expiry && it.right == r && kotlin.math.abs(it.strike - strike) < 0.01 } ?: return@mapNotNull null
            val k = o.lastAtOrBefore(minute).takeIf { it >= 0 } ?: return@mapNotNull null
            OptionMath.impliedVol(o.close[k], ot, spot, strike, t)?.takeIf { it in 0.03..2.0 }
        }
        return ivs.takeIf { it.isNotEmpty() }?.average()
    }

    /** IV of an option priced [price] now (the call if [call]), [spot] the index, [daysLeft] to expiry. */
    fun now(price: Double, call: Boolean, spot: Double, strike: Double, daysLeft: Double): Double? =
        OptionMath.impliedVol(price, if (call) OptionType.CE else OptionType.PE, spot, strike, daysLeft / 365.0)?.takeIf { it in 0.03..2.0 }

    /** Share of the last year's sessions with a lower IV than [iv] (0 cheapest .. 1 dearest), or null with too few days. */
    fun rank(history: List<Pair<LocalDate, Double>>, iv: Double, today: LocalDate): Double? {
        val year = history.filter { it.first.isAfter(today.minusYears(1)) && it.first.isBefore(today) }.map { it.second }
        if (year.size < MIN_DAYS) return null
        return year.count { it < iv }.toDouble() / year.size
    }

    /** In words; a reason not to buy when it is [BLOCK] or more. */
    fun say(rank: Double, iv: Double): String {
        val pct = "%.0f".format(Locale.ENGLISH, rank * 100)
        val v = "%.1f%%".format(Locale.ENGLISH, iv * 100)
        return when {
            rank >= BLOCK -> "Options are expensive now: implied volatility $v is higher than on $pct% of the last year's days, so buying one needs an unusually big move. Not suggested."
            rank >= CAREFUL -> "Options are on the dear side: implied volatility $v is above $pct% of the last year's days."
            rank <= 0.25 -> "Options are cheap now: implied volatility $v is below ${100 - pct.toInt()}% of the last year's days."
            else -> "Options are normally priced: implied volatility $v is at the ${pct}th percentile of the last year."
        }
    }
}

/**
 * How many lots a Jarvis trade takes for the owner's rupee risk: each lot risks its 15% stop on the premium
 * ([JarvisTrades.STOP_SHARE] x the premium x the lot size); 1 when no risk is set.
 */
object RiskSizing {
    const val STOP_SHARE = JarvisTrades.STOP_SHARE

    /** Rupees one lot at [premium] risks at Jarvis's 15% stop. */
    fun perLot(premium: Double, lotSize: Int): Double = premium * STOP_SHARE * lotSize

    /** Lots within [riskRs]: 1 when no risk is set; 0 when even one lot would risk more than the owner allows. */
    fun lots(riskRs: Double?, premium: Double, lotSize: Int, maxLots: Int): Int {
        if (riskRs == null || riskRs <= 0 || premium <= 0 || lotSize <= 0) return 1
        return kotlin.math.floor(riskRs / perLot(premium, lotSize) + 1e-9).toInt().coerceIn(0, maxOf(1, maxLots))
    }

    fun say(lots: Int, premium: Double, lotSize: Int): String =
        "$lots lot${if (lots > 1) "s" else ""}: the 15%% stop risks about Rs %,.0f.".format(Locale.ENGLISH, lots * perLot(premium, lotSize))

    /** The words when the owner's risk per trade came out at 0 lots for an option at [premium]. */
    fun tooSmall(premium: Double, lotSize: Int): String =
        "Your risk per trade is smaller than one lot's stop risk (15% of the premium x $lotSize, about ${AppFacts.amt(perLot(premium, lotSize))}): not placed."
}

/**
 * How the indices behaved on past event days (the owner's wish, 2026-10-02): RBI policy days, the Indian session after
 * a US Fed decision, and Budget days, against all other days of the last two years - the day's range and how often it
 * moved 1% or more. Only dates known for certain are listed. Pure.
 */
object EventStudy {
    /** RBI policy announcements (India). */
    val RBI = listOf("2024-02-08", "2024-04-05", "2024-06-07", "2024-08-08", "2024-10-09", "2024-12-06",
        "2025-02-07", "2025-04-09", "2025-06-06", "2025-08-06", "2025-10-01", "2025-12-05").map(LocalDate::parse)
    /** US Fed decisions (announced after Indian hours: the next Indian session reacts). */
    val FOMC = listOf("2024-01-31", "2024-03-20", "2024-05-01", "2024-06-12", "2024-07-31", "2024-09-18", "2024-11-07", "2024-12-18",
        "2025-01-29", "2025-03-19", "2025-05-07", "2025-06-18", "2025-07-30", "2025-09-17", "2025-10-29", "2025-12-10",
        "2026-01-28", "2026-03-18", "2026-04-29", "2026-06-17", "2026-07-29", "2026-09-16").map(LocalDate::parse)
    val BUDGET = listOf("2024-02-01", "2024-07-23", "2025-02-01", "2026-02-01").map(LocalDate::parse)

    enum class Kind(val label: String) { RBI("RBI policy days"), FED("the session after a US Fed decision"), BUDGET("Budget days") }

    data class Line(val market: Market, val kind: Kind, val days: Int, val range: Double, val usualRange: Double, val bigShare: Double, val usualBig: Double) {
        fun text(): String = "${market.label} on ${kind.label} ($days in two years): range %.2f%% against %.2f%% on other days; moved 1%% or more on %.0f%% of them (%.0f%% usually)."
            .format(Locale.ENGLISH, range, usualRange, bigShare * 100, usualBig * 100)
    }

    /** The trading day each event is felt on, among [days]. */
    fun dates(kind: Kind, days: Set<LocalDate>): Set<LocalDate> = when (kind) {
        Kind.RBI -> RBI.filter { it in days }.toSet()
        Kind.BUDGET -> BUDGET.filter { it in days }.toSet()
        Kind.FED -> FOMC.mapNotNull { d -> (1..5L).map { d.plusDays(it) }.firstOrNull { it in days } }.toSet()
    }

    fun run(market: Market, days: List<Study.Day>): List<Line> {
        if (days.size < 60) return emptyList()
        val set = days.map { it.date }.toSet()
        fun range(d: Study.Day) = (d.high - d.low) / d.open * 100
        fun big(d: Study.Day) = d.changePct?.let { kotlin.math.abs(it) >= 1.0 } == true
        return Kind.entries.mapNotNull { k ->
            val on = dates(k, set)
            // RBI dates are listed to the end of 2025: later days are left out rather than counted as "other days".
            val known = if (k == Kind.RBI) days.filter { !it.date.isAfter(RBI.max()) } else days
            val ev = known.filter { it.date in on }; val rest = known.filter { it.date !in on }
            if (ev.size < 2) return@mapNotNull null
            Line(market, k, ev.size, ev.map(::range).average(), rest.map(::range).average(), ev.count(::big).toDouble() / ev.size, rest.count(::big).toDouble() / rest.size)
        }
    }

    /** Which kind an event's name is (from the calendar), or null. */
    fun kindOf(name: String): Kind? = when {
        rx("(?i)\\brbi\\b|monetary policy").containsMatchIn(name) -> Kind.RBI
        rx("(?i)\\bfed\\b|fomc").containsMatchIn(name) -> Kind.FED
        rx("(?i)budget").containsMatchIn(name) -> Kind.BUDGET
        else -> null
    }
}

/**
 * The monthly re-test of the arms (the owner's wish, 2026-10-02): each arm's last two months against its own whole
 * record on the same data. An arm whose recent trades lose while its record made money - or whose recent average
 * fell well below its record's - is "slipping", and the owner is told. Pure.
 */
object ArmHealth {
    data class T(val day: LocalDate, val arm: String, val net: Double)
    data class Check(val arm: String, val trades: Int, val net: Double, val recentTrades: Int, val recentNet: Double, val slipping: Boolean) {
        fun text(): String = "$arm: ${AppFacts.rs(net)} over $trades trades; last two months ${AppFacts.rs(recentNet)} over $recentTrades" +
            if (slipping) " - slipping, worth a look." else "."
    }

    fun check(trades: List<T>, today: LocalDate, recentDays: Long = 61): List<Check> =
        trades.groupBy { it.arm }.map { (arm, ts) ->
            val recent = ts.filter { it.day.isAfter(today.minusDays(recentDays)) }
            val net = ts.sumOf { it.net }; val rn = recent.sumOf { it.net }
            val avg = if (ts.isEmpty()) 0.0 else net / ts.size
            val ravg = if (recent.isEmpty()) 0.0 else rn / recent.size
            val slipping = recent.size >= 5 && ((net > 0 && rn < 0) || (avg > 0 && ravg < avg * 0.25))
            Check(arm, ts.size, net, recent.size, rn, slipping)
        }.sortedBy { it.arm }
}
