package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs

/**
 * Boss's own open positions, asked about (the owner's wish, 2026-10-05): "what happens to my P&L if Nifty moves 100
 * points" and "which of my positions is losing most". Read from what the app holds; the move is a rough sum from each
 * position's delta and gamma now (IV and time held still), never a forecast. Questions only: nothing here places,
 * changes or closes anything. Pure.
 */
object Exposure {
    /**
     * One open position. [underlying]: "NIFTY", "BANKNIFTY"... when the app knows it; [delta] and [gamma]: per unit of
     * the underlying (an option's from its price now; the index itself 1 and 0), null when not known.
     */
    data class Leg(val where: String, val symbol: String, val qty: Int, val avg: Double, val ltp: Double,
                   val underlying: String? = null, val delta: Double? = null, val gamma: Double? = null) {
        val pnl get() = (ltp - avg) * qty
    }

    /** A move asked: [points] or [pct] of [market]; [sign] +1 up, -1 down, 0 either way. */
    data class Shock(val market: Market, val points: Double?, val pct: Double?, val sign: Int)

    private fun norm(text: String) = " " + text.lowercase().replace("p&l", "p l").replace("%", " percent ")
        .replace(Regex("[^a-z0-9. ]"), " ").replace(Regex("(?<![0-9])\\.|\\.(?![0-9])"), " ").replace(Regex("\\s+"), " ").trim() + " "

    private val OWNER = Regex(" (my|mine|our|i|me|we|us) ")
    private val IF = Regex(" (if|suppose|say|what happens|what would happen|in case) ")
    private val SIZE = Regex(" (\\d+(?:\\.\\d+)?) ?(points?|pts?|percent|per cent) ")
    private val DOWN = Regex(" (falls?|fell|drops?|dropped|goes down|go down|comes down|crash(es)?|tanks?|slides?|sinks?|declines?|is down|down) ")
    private val UP = Regex(" (rises?|rose|goes up|go up|jumps?|rall(y|ies)|climbs?|gains?|surges?|is up|up) ")
    private val MOVE = Regex(" (moves?|moved|swings?|changes?) ")

    /** "What happens to my P&L if Nifty moves 100 points", "if BankNifty falls 1% what do I lose": the move, or null. */
    fun moveAsked(text: String): Shock? {
        val t = norm(text)
        if (!IF.containsMatchIn(t) || !OWNER.containsMatchIn(t)) return null
        // "Should I buy puts if Nifty falls", "if I buy the 24500 CE and Nifty moves": advice or a new trade, not the positions.
        if (Regex("\\b(should|shall|recommend|suggest)\\b|\\b(buy|sell)\\b.*\\b(ce|pe|call|put|calls|puts)\\b").containsMatchIn(t)) return null
        val size = SIZE.find(t) ?: return null
        val n = size.groupValues[1].toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        val pct = size.groupValues[2].startsWith("per")
        if (pct && n > 20 || !pct && n > 5000) return null
        val down = DOWN.containsMatchIn(t); val up = UP.containsMatchIn(t)
        if (!down && !up && !MOVE.containsMatchIn(t)) return null
        val m = Market.mentioned(text).firstOrNull { it != Market.VIX && it != Market.GOLD } ?: Market.NIFTY
        val sign = if (down && !up) -1 else if (up && !down) 1 else 0
        return Shock(m, if (pct) null else n, if (pct) n else null, sign)
    }

    private fun f(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun signed(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + f(abs(x))

    /** The change on [l] (rupees) if its underlying moves [ds] points: delta and half gamma, times the quantity. */
    fun change(l: Leg, ds: Double): Double = l.qty * ((l.delta ?: 0.0) * ds + 0.5 * (l.gamma ?: 0.0) * ds * ds)

    /** [spot]: the market's price now (to turn a percent into points). */
    fun move(s: Shock, legs: List<Leg>, spot: Double?): List<String> {
        if (legs.isEmpty()) return listOf("You have no open positions, Boss, so a ${s.market.label} move changes nothing.")
        val pts = s.points ?: spot?.let { it * s.pct!! / 100 }
            ?: return listOf("I don't have ${s.market.label}'s price just now, Boss, to turn ${fmtPct(s.pct!!)} into points. Ask it in points.")
        val said = if (s.points != null) "${f(pts)} points" else "${fmtPct(s.pct!!)} (about ${f(pts)} points)"
        val on = legs.filter { it.underlying.equals(s.market.name, true) }
        val counted = on.filter { it.delta != null }
        val left = legs - counted.toSet()
        val out = ArrayList<String>()
        if (counted.isEmpty()) {
            out += if (on.isEmpty()) "None of your open positions are on ${s.market.label}, Boss."
                else "I can't work out how your ${s.market.label} positions move just now, Boss: their deltas are not known."
            return out
        }
        val n = "${counted.size} ${s.market.label} position${if (counted.size > 1) "s" else ""}"
        val upBy = counted.sumOf { change(it, pts) }; val downBy = counted.sumOf { change(it, -pts) }
        if (s.sign >= 0) out += "If ${s.market.label} rises $said: about ${signed(upBy)} on your $n."
        if (s.sign <= 0) out += "If ${s.market.label} falls $said: about ${signed(downBy)} on your $n."
        counted.sortedBy { minOf(change(it, pts), change(it, -pts)) }.take(5).forEach { l ->
            out += "${l.where} ${l.symbol} (${l.qty}): " + when (s.sign) {
                1 -> "about ${signed(change(l, pts))}"
                -1 -> "about ${signed(change(l, -pts))}"
                else -> "about ${signed(change(l, pts))} up, ${signed(change(l, -pts))} down"
            } + ", delta %.2f.".format(Locale.ENGLISH, l.delta!!)
        }
        if (left.isNotEmpty()) out += "Not counted: " + left.take(4).joinToString(", ") { "${it.where} ${it.symbol}" } +
            (if (left.size > 4) " and ${left.size - 4} more" else "") + " (another underlying, or no delta known)."
        out += "A rough guide, Boss: from each position's delta and gamma now, with IV and time held still."
        return out
    }

    private fun fmtPct(p: Double) = (if (p == Math.floor(p)) "%.0f" else "%.1f").format(Locale.ENGLISH, p) + "%"

    private val RANK = Regex(" ((losing|lost|down|bleeding|hurting|winning|making|up|gaining) (the )?most|most (in the red|in the green)|" +
        "(worst|best|weakest|strongest|biggest losing|biggest winning) (open )?(position|positions|trade|trades)|biggest (loser|losers|winner|winners)|" +
        "rank (my |the )?(open )?(positions|trades)|(positions|trades) ranked|which (of my |one of my )?(open )?(position|positions|trade|trades) (is|are) (losing|winning|down|up|in the red|in the green)) ")

    /** "Which of my positions is losing most", "my worst position", "rank my positions". */
    fun rankAsked(text: String): Boolean {
        val t = norm(text)
        return RANK.containsMatchIn(t) && Regex(" (position|positions|trade|trades|loser|losers|winner|winners) ").containsMatchIn(t) &&
            OWNER.containsMatchIn(t) && !Regex(" (today s|yesterday|week|month|year|all time|ever|history|closed) ").containsMatchIn(t)
    }

    private fun one(l: Leg): String {
        val cost = abs(l.avg * l.qty)
        val pct = if (cost > 0) " (%+.1f%% on what it cost)".format(Locale.ENGLISH, l.pnl / cost * 100) else ""
        return "${l.where} ${l.symbol}, ${l.qty} at ${AppFacts.px(l.avg)}, now ${AppFacts.px(l.ltp)}: ${AppFacts.rs(l.pnl)}$pct"
    }

    /** Boss's open positions by open P&L, worst first (not numbered: "close position 2" goes by the positions list). */
    fun rank(legs: List<Leg>): List<String> {
        if (legs.isEmpty()) return listOf("You have no open positions, Boss.")
        val sorted = legs.sortedBy { it.pnl }
        val out = ArrayList<String>()
        val worst = sorted.first(); val best = sorted.last()
        out += if (worst.pnl < 0) "Losing most: ${one(worst)}." else "None of your open positions is losing, Boss. Smallest gain: ${one(worst)}."
        if (best !== worst && best.pnl > 0) out += "Doing best: ${one(best)}."
        if (sorted.size > 2) out += "Worst first: " + sorted.take(8).joinToString("; ") { "${it.where} ${it.symbol} ${AppFacts.rs(it.pnl)}" } +
            (if (sorted.size > 8) "; and ${sorted.size - 8} more" else "") + "."
        out += "Open P&L on all ${legs.size} together: ${AppFacts.rs(legs.sumOf { it.pnl })} (before charges)."
        return out
    }
}
