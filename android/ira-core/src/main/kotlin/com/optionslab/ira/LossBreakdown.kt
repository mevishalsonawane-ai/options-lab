package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs

/**
 * 25 Oct (app audit; Boss's diagnostics, 23 Oct 09:49): the NSE paper daily loss stop said only "Today's Paper NSE P&L reached the
 * limit" and stopped every NSE paper arm. That morning R52 had bought a NIFTY call and Solo 2.0 a MIDCPNIFTY put - one bet up and
 * one down on two related indices, so they partly cancelled each other - and both lost. Nothing said so: the opposite-side check
 * looks at one index only. This says, in the stop's message, which positions made the day (worst first) and whether the day held
 * opposite bets on related indices. Words only: nothing is refused, sized or sold by it. Pure.
 *
 * Signal on the underlying: a bought call is a bet the index goes up, a bought put that it goes down (the arms only buy options).
 */
object LossBreakdown {
    /** The index options counted as one market (they move together; a call on one and a put on another are opposite bets). */
    private val INDICES = listOf("MIDCPNIFTY", "FINNIFTY", "BANKNIFTY", "NIFTY", "BANKEX", "SENSEX")

    /** The index an NSE/BSE index option [symbol] is on ("NIFTY27OCT2622700CE" -> "NIFTY"), or null for anything else. */
    fun indexOf(symbol: String): String? {
        val s = symbol.uppercase(Locale.ENGLISH)
        if (!(s.endsWith("CE") || s.endsWith("PE"))) return null
        return INDICES.firstOrNull { s.startsWith(it) && s.length > it.length && s[it.length].isDigit() }
    }

    /** +1 a call (a bet up), -1 a put (a bet down), 0 not an index option. */
    fun betOf(symbol: String): Int = if (indexOf(symbol) == null) 0 else if (symbol.uppercase(Locale.ENGLISH).endsWith("CE")) 1 else -1

    /** True when the day's index options hold both a call and a put (on any of the related indices). */
    fun opposite(symbols: List<String>): Boolean {
        val bets = symbols.map { betOf(it) }.filter { it != 0 }.toSet()
        return 1 in bets && -1 in bets
    }

    private fun rs(x: Double): String = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))

    private fun name(symbol: String): String {
        val i = indexOf(symbol) ?: return symbol
        return "$i ${if (betOf(symbol) > 0) "call" else "put"}"
    }

    /**
     * The words added to the loss stop's message, from the day's position rows ([rows]: symbol and its P&L today), or null when
     * there is none: "By position: NIFTY call -Rs 3,195, MIDCPNIFTY put -Rs 3,000. These were opposite bets ..." At most [max]
     * positions, the worst first; rows of 0 left out.
     */
    fun words(rows: List<Pair<String, Double>>, max: Int = 4): String? {
        val r = rows.filter { it.second.isFinite() && abs(it.second) >= 0.5 }
        if (r.isEmpty()) return null
        val worst = r.sortedBy { it.second }
        val shown = worst.take(max).joinToString(", ") { "${name(it.first)} ${rs(it.second)}" } +
            if (worst.size > max) " and ${worst.size - max} more" else ""
        val opp = if (opposite(r.map { it.first }))
            " These were opposite bets: a call (the market up) and a put (the market down) on related indices. " +
                "They partly cancel each other, so the day can lose on both sides; each arm decides its side on its own."
        else ""
        return "By position: $shown.$opp"
    }
}
