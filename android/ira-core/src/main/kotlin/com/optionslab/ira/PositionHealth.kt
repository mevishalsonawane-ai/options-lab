package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * A health check on Boss's open positions (usefulness round 9, 2026-10-05): "check my positions", "position health",
 * "kya meri positions theek hain" - and, unasked, at 14:45 on a day a position of his expires. For each open position
 * (paper and Zerodha): the time to its expiry, its time decay a day and a trading hour with the hours left today, how
 * far spot is from the strike in points and in average day's ranges, in or out of the money, the bid-ask spread now
 * against the first one noted, any stop or target and how far it is - and a flag on what expires today in the money,
 * with how such a contract settles (index options in cash; stock options by delivery). Facts only, never advice: it
 * ends "your call, Boss". Nothing here places, changes or closes anything. Pure.
 */
object PositionHealth {
    /**
     * One open position. [right] "CE" / "PE" for an option (null otherwise); [spot] the underlying's price now;
     * [avgRange] its average whole-day range (high to low) over [rangeDays] earlier sessions; [theta] per unit per
     * calendar day in the option's price (negative: the option loses value by the day); [bid] / [ask] the best quote
     * now; [firstSpread] the spread first noted for it, [firstSpreadWhen] when ("10:12 today", "3 Oct"); [stop] /
     * [target] set in the app.
     */
    data class Pos(
        val where: String, val symbol: String, val qty: Int, val avg: Double, val ltp: Double,
        val underlying: String? = null, val strike: Double? = null, val right: String? = null, val expiry: LocalDate? = null,
        val spot: Double? = null, val avgRange: Double? = null, val rangeDays: Int = 0,
        val theta: Double? = null,
        val bid: Double? = null, val ask: Double? = null,
        val firstSpread: Double? = null, val firstSpreadWhen: String? = null,
        val stop: Double? = null, val target: Double? = null,
    ) {
        val option: Boolean get() = (right == "CE" || right == "PE") && strike != null
        /** Points in the money (+) or out of it (-), or null when not an option or no spot. */
        val itmBy: Double? get() {
            val s = spot ?: return null; val k = strike ?: return null
            return when (right) { "CE" -> s - k; "PE" -> k - s; else -> null }
        }
        val spread: Double? get() = if (bid != null && ask != null && bid > 0 && ask >= bid) ask - bid else null
    }

    /** When the unasked check is said on an expiry day: 14:45 (minutes from midnight), for ten minutes. */
    const val AT = 14 * 60 + 45
    fun due(minute: Int): Boolean = minute in AT until AT + 10

    /** The session's close, when an option expires; the session's length in hours (time decay per trading hour). */
    val CLOSE: LocalTime = LocalTime.of(15, 30)
    private const val SESSION_HOURS = 6.25

    /** Index options, cash-settled; every other underlying's options (stocks) are settled by delivery. */
    val INDICES = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTYNXT50", "SENSEX", "BANKEX", "SENSEX50")

    private fun norm(text: String) = Spaced.words(text)
        // The recognizer's and the keyboard's slips (routing audit, round 7): "chek my postions" is this check.
        .replace(rx(" (postions?|positons?|possitions?|posistions?|positiions?|pozitions?|pisitions?)(?= )")) { " position" + if (it.value.endsWith("s")) "s" else "" }
        .replace(rx(" (chek|chk|chck|chec|cheak|chekc)(?= )"), " check")

    private val OWNER = Regex(" (my|mine|our|meri|mere|mera|hamari|hamare|apni|apne) ")
    private val ASK = Regex(" (positions? (health|health check|healthcheck|check up|checkup)|health (check )?(of|on|for) (all )?(my|our) (open )?positions?|" +
        "how healthy are (all )?(my|our) (open )?positions?|check (on )?(all )?(my|our|meri|mere) (open )?positions?|" +
        "(are|is) (all )?(my|our) (open )?positions? (ok|okay|fine|alright|all right|healthy|in good shape|in shape|safe)|" +
        // "Is my put healthy", "is my 24500 call okay" (round 10): one holding named, the same check.
        "(are|is) (my|our) ([a-z0-9 ]{0,20})?(put|puts|call|calls|straddle|strangle|spread|trade|trades) (ok|okay|fine|alright|all right|healthy|in good shape|safe)|" +
        "(are|is) (any of )?(all )?(my|our) (open )?positions? (in danger|at risk|in trouble)|(which|any) of (my|our) (open )?positions? (is |are )?(in danger|at risk|in trouble)|" +
        "positions? (theek|thik|theekh|theek|sahi|ok|okay|fine|thik thak|theek thaak) (hai|hain|he|h|hei|hein|na)|positions? (ka|ki) (haal|halat|haalat)|" +
        "positions? haal) ")
    /** "How is my put (doing)?", "how is my 24500 call": one holding asked after, as a whole question (round 10). */
    private val HOW = Regex("^ how (is|are|s) (my|our) ([0-9]{3,6} )?(put|puts|call|calls|straddle|strangle|iron condor|condor)( doing| looking| holding up)?( now| today)? $")
    /** Not this check: a P&L, a move, a rank, an order or a change. */
    private val NOT = Regex(" (p l|pnl|profit|loss|if|close|exit|square|sell|buy|add|cancel|set|place|move|worst|best|rank) ")

    /** "Check my positions", "position health", "are my positions okay", "kya meri positions theek hain". */
    fun asked(text: String): Boolean = listOf(text, Ask.reading(text)).any { s ->
        val t = norm(s.replace("p&l", "p l", ignoreCase = true))
        (ASK.containsMatchIn(t) || HOW.containsMatchIn(t)) && (OWNER.containsMatchIn(t) || rx(" positions? (health|health check|healthcheck|check up|checkup) ").containsMatchIn(t)) &&
            !NOT.containsMatchIn(t)
    }

    private fun n(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun p2(x: Double) = "%,.2f".format(Locale.ENGLISH, x)
    private fun hm(minutes: Long) = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** Minutes of trading left today at [now] (0 after the close). */
    fun minutesLeft(now: LocalDateTime): Long = maxOf(0L, ChronoUnit.MINUTES.between(now.toLocalTime(), CLOSE))

    /** "expires today at 15:30, 45m from now", "expires Thu 9 Oct, in 4 days". */
    fun toExpiry(expiry: LocalDate, now: LocalDateTime): String {
        val today = now.toLocalDate()
        val days = ChronoUnit.DAYS.between(today, expiry)
        return when {
            days < 0 || days == 0L && !now.toLocalTime().isBefore(CLOSE) -> "expired ${if (days == 0L) "today" else expiry.format(DAY)}"
            days == 0L -> "expires today at 15:30, ${hm(minutesLeft(now))} from now"
            days == 1L -> "expires tomorrow (${expiry.format(DAY)})"
            else -> "expires ${expiry.format(DAY)}, in $days days"
        }
    }

    /** The settlement of an option on [underlying] held to expiry, as a fact. */
    fun settlement(underlying: String?): String = when {
        underlying == null -> "I don't know its underlying, so I can't say how it settles."
        underlying.uppercase() in INDICES -> "Index options are cash-settled: no shares change hands; one left open in the money is settled in cash at the exchange's final settlement price."
        else -> "Stock options are physically settled: one left open in the money at expiry is settled by delivery of the shares - taken or given - with the money or margin that needs."
    }

    private fun one(p: Pos, now: LocalDateTime): String {
        val side = if (p.qty > 0) "long" else "short"
        val parts = ArrayList<String>()
        parts += "${p.where} ${p.symbol}, ${abs(p.qty)} $side at ${p2(p.avg)}, now ${p2(p.ltp)}"
        p.expiry?.let { parts += toExpiry(it, now) }
        val left = minutesLeft(now)
        p.theta?.takeIf { p.option }?.let { th ->
            val day = th * p.qty
            parts += "time decay about ${AppFacts.rs(day)} a day on the position, about ${AppFacts.rs(day / SESSION_HOURS)} a trading hour" +
                if (left > 0) " (${hm(left)} of trading left today)" else " (the session is over for today)"
        }
        val itm = p.itmBy
        if (p.option && itm != null) {
            val dir = if (p.spot!! >= p.strike!!) "above" else "below"
            val ranges = p.avgRange?.takeIf { it > 0 }?.let { r ->
                " - %.2f of an average day's range (%s points over the last %d sessions)".format(Locale.ENGLISH, abs(p.spot - p.strike) / r, n(r), p.rangeDays) } ?: ""
            parts += if (itm == 0.0) "spot ${p2(p.spot)} is at the ${n(p.strike)} strike: at the money"
                else "spot ${p2(p.spot)} is ${n(abs(p.spot - p.strike))} points $dir the ${n(p.strike)} strike$ranges: " +
                    (if (itm > 0) "in the money" else "out of the money")
        } else if (p.option) parts += "${p.underlying ?: "the underlying"}'s price is not known just now, so not how far it is from the strike"
        val sp = p.spread
        parts += if (sp == null) "no bid-ask quote just now" else {
            val mid = (p.bid!! + p.ask!!) / 2
            "bid ${p2(p.bid)}, ask ${p2(p.ask)}: spread ${p2(sp)}" + (if (mid > 0) " (%.1f%% of the price)".format(Locale.ENGLISH, sp / mid * 100) else "") +
                when {
                    p.firstSpread == null -> ", none noted earlier to compare"
                    abs(p.firstSpread - sp) < 0.005 -> ", the same as when first noted (${p.firstSpreadWhen ?: "earlier"})"
                    else -> ", against ${p2(p.firstSpread)} when first noted (${p.firstSpreadWhen ?: "earlier"}): " + if (sp > p.firstSpread) "wider" else "narrower"
                }
        }
        // A stop or target: how far from the price now (a long's stop under it, a short's above it).
        val prot = listOfNotNull(
            p.stop?.let { s -> "stop ${p2(s)}, ${p2(abs(p.ltp - s))} ${if (s < p.ltp) "under" else "above"} the price" + pct(abs(p.ltp - s), p.ltp) },
            p.target?.let { t -> "target ${p2(t)}, ${p2(abs(t - p.ltp))} ${if (t > p.ltp) "above" else "under"} the price" + pct(abs(t - p.ltp), p.ltp) },
        )
        parts += if (prot.isEmpty()) "no stop or target set in the app" else prot.joinToString(", ")
        return parts.joinToString("; ") + "."
    }

    private fun pct(d: Double, of: Double) = if (of > 0) " (%.0f%%)".format(Locale.ENGLISH, d / of * 100) else ""

    /** Expires on [today] (before the close) and in the money: the flag. */
    fun expiringItm(ps: List<Pos>, today: LocalDate): List<Pos> = ps.filter { it.option && it.expiry == today && (it.itmBy ?: 0.0) > 0 }

    /** The health check, one line a position, the flags, and "your call, Boss". [now]: IST. */
    fun lines(ps: List<Pos>, now: LocalDateTime): List<String> {
        if (ps.isEmpty()) return listOf("You have no open positions, Boss: nothing to check.")
        val today = now.toLocalDate()
        val out = ArrayList<String>()
        val expToday = ps.filter { it.expiry == today }
        out += "Health check on your ${ps.size} open position${if (ps.size > 1) "s" else ""}, Boss, at ${now.toLocalTime().withSecond(0).withNano(0)}" +
            (if (expToday.isNotEmpty()) ": ${expToday.size} expire${if (expToday.size == 1) "s" else ""} today." else ".")
        // Expiring today first, then the nearest expiry.
        ps.sortedWith(compareBy<Pos> { it.expiry ?: LocalDate.MAX }.thenBy { it.symbol }).forEach { out += one(it, now) }
        val flagged = expiringItm(ps, today)
        if (flagged.isNotEmpty() && now.toLocalTime().isBefore(CLOSE)) {
            out += "Expiring today in the money: " + flagged.joinToString(", ") { "${it.where} ${it.symbol} (${n(it.itmBy!!)} points in)" } + "."
            flagged.map { settlement(it.underlying) }.distinct().forEach { out += it }
        }
        val unknown = ps.count { it.option && it.expiry == null }
        if (unknown > 0) out += "$unknown position${if (unknown > 1) "s" else ""} I couldn't match to a contract, so no expiry or strike for ${if (unknown > 1) "them" else "it"}."
        out += "Facts from the app and the quotes now, not advice - your call, Boss."
        return out
    }

    /**
     * Said unasked at 14:45 on a day a position of Boss's expires: counts only, never an amount or a symbol (a locked
     * phone may be heard). Null when nothing he holds expires today.
     */
    fun spoken(ps: List<Pos>, today: LocalDate): String? {
        val exp = ps.filter { it.expiry == today }
        if (exp.isEmpty()) return null
        val itm = expiringItm(exp, today)
        val out = StringBuilder("Boss, a 14:45 check: ")
        out.append(if (exp.size == 1) "one of your positions expires today" else "${words(exp.size)} of your positions expire today")
        out.append(when (itm.size) {
            0 -> ", none of them in the money just now."
            exp.size -> if (exp.size == 1) ", and it is in the money." else ", all of them in the money."
            else -> ", ${words(itm.size)} of them in the money."
        })
        if (itm.isNotEmpty()) {
            val index = itm.count { it.underlying?.uppercase() in INDICES }
            val stock = itm.count { it.underlying != null && it.underlying.uppercase() !in INDICES }
            if (index > 0) out.append(" Index options are cash-settled.")
            if (stock > 0) out.append(" ${if (stock == 1) "One is a stock option" else "${words(stock).replaceFirstChar { it.uppercase() }} are stock options"}: those settle by delivery of the shares.")
        }
        val bare = exp.count { it.stop == null && it.target == null }
        if (bare > 0) out.append(" ${if (bare == exp.size) (if (bare == 1) "It has" else "They have") else "${words(bare).replaceFirstChar { it.uppercase() }} of them ${if (bare == 1) "has" else "have"}"} no stop or target set.")
        out.append(" The details are in the chat - your call, Boss.")
        return out.toString()
    }

    private fun words(k: Int) = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten").getOrElse(k) { "$k" }

    /** The average whole-session range (high to low) of up to [days] sessions before [today] in [bars], with how many; null with fewer than 3. */
    fun avgRange(bars: List<Candle>, today: LocalDate, days: Int = 10): Pair<Double, Int>? {
        val past = bars.filter { it.t.toLocalDate() < today }.groupBy { it.t.toLocalDate() }
            .filterValues { it.size >= 300 }.toSortedMap().values.toList().takeLast(days)
        if (past.size < 3) return null
        return past.map { s -> s.maxOf { it.h } - s.minOf { it.l } }.average() to past.size
    }
}
