package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs

/**
 * "Why is my P&L different from what I expected?" (reasoning round 24, 2026-10-05): today's paper P&L taken apart into
 * the facts that make it - what was booked (realised, from closed legs), what is still open (unrealised, moving with the
 * price), the charges of today's trade legs, and the slippage of the orders that had an intended level (a limit's price,
 * a stop's trigger) against where they filled. A market order keeps no intended level, so its slippage is never guessed:
 * it is counted and said as unknown. Slippage is already inside the realised and unrealised figures (it is the fill
 * price), so it is said beside them, never added again. The biggest contributor (of realised, unrealised and the charges)
 * is named, with the leg that moved the most. Facts and arithmetic only - never a verdict, advice or a guess at what Boss
 * expected. Boss's account, so never on a locked phone ([LOCKED]); not in IraGoldAlgo. Pure: the app reads the paper book.
 */
object PnlGap {
    /** One position's part of today: booked (closed today) and still open, before charges. */
    data class Leg(val symbol: String, val realised: Double, val unrealised: Double) {
        val total: Double get() = realised + unrealised
    }

    /**
     * One filled order today: its side, its price type ("MARKET", "LIMIT", "SL", "SL-M"), its intended level (a limit's
     * price, a stop's trigger; null or 0 for none) and its average fill, for [qty] units.
     */
    data class Fill(val symbol: String, val buy: Boolean, val type: String, val intended: Double?, val filled: Double, val qty: Int)

    /** Today's paper book: each leg, the charges of today's trade legs, how many legs traded, and the filled orders. */
    data class Day(val legs: List<Leg>, val charges: Double, val tradeLegs: Int, val fills: List<Fill>)

    const val LOCKED = "Your P&L needs the phone unlocked, Boss."

    private fun norm(text: String) = " " + spacedWords(text.lowercase().replace("p&l", "pnl").replace("p & l", "pnl").replace("'", "").replace("’", "")) + " "

    private const val PNL = "(pnl|p l|p n l|profit and loss|profit loss|profit|result|results|numbers)"
    private const val MINE = "(my|mine|todays|today s|aaj ka|aaj ke|mera|meri|the days|the)"
    private const val EXPECTED = "(what i (expected|thought|was expecting|had expected|calculated|worked out)|i (expected|thought)|expected|my expectation|what i had in mind)"
    private val ASKED = rx(
        " why (is|was|does|did) $MINE( todays| today s)? $PNL (look |come out |come |end up )?(so )?(different|off|lower|higher|less|more|worse|better|smaller|bigger)( than| from| to)? $EXPECTED" +
        "| why (is|was) $MINE( todays| today s)? $PNL not (what|as) (i )?(expected|thought)" +
        "| why (does|doesnt|dont|did|didnt) $MINE( todays| today s)? $PNL (not )?(match|add up|line up|make sense)" +
        "| $MINE( todays| today s)? $PNL (doesnt|does not|dont|didnt) (match|add up|line up|make sense)" +
        "| $MINE( todays| today s)? $PNL (is|was) (different|off|lower|higher|less|more) (than|from) $EXPECTED" +
        "| (break down|breakdown of|break up|split|explain) $MINE( todays| today s)? $PNL" +
        "| $MINE( todays| today s)? $PNL (breakdown|break up|split)" +
        "| how much of $MINE( todays| today s)? $PNL is (realised|realized|booked|unrealised|unrealized|open|charges|slippage)" +
        "| (realised|realized) (vs|versus|and|or) (unrealised|unrealized)( pnl| p l)?( today)?" +
        "| where did $MINE( todays| today s)? $PNL (come from|go)" +
        "| (mera|meri|aaj ka|mere) $PNL (expected|socha|soche) se (alag|kam|zyada|jyada) (kyun|kyu|kaise) (hai|tha|hua)" +
        "| (mera|meri|aaj ka) $PNL (itna )?(alag|kam|zyada|jyada) kyun (hai|tha|hua)" +
        "| (mera|meri|aaj ka|mere) $PNL ka (breakdown|hisaab|hisab) (do|batao|bata do|samjhao|dikhao)" +
        "| $PNL (expected|socha) se (alag|kam|zyada) kyun ")
    /** Another span or someone else's book: never today's paper P&L. */
    private val NOT_TODAY = rx(" (yesterday|kal|week|weekly|hafte|hafta|month|monthly|mahina|mahine|year|yearly|zerodha|live|bot|bots|arm|arms|strategy|strategies|orb|nifty|banknifty|gold) ")

    /** Does [text] ask why today's P&L is not what Boss expected (or to take it apart)? */
    fun asked(text: String): Boolean = askedKept.of(text) { askedFresh(text) }

    private val askedKept = Kept<Boolean>(64)

    private fun askedFresh(text: String): Boolean {
        val t = norm(text)
        return ASKED.containsMatchIn(t) && !NOT_TODAY.containsMatchIn(t)
    }

    /** [f]'s slippage in rupees against its intended level (positive: it cost; negative: it filled better), or null when it had none. */
    fun slippage(f: Fill): Double? {
        val lvl = f.intended?.takeIf { it > 0 } ?: return null
        if (f.type.uppercase(Locale.ENGLISH) == "MARKET" || f.filled <= 0 || f.qty == 0) return null
        val per = if (f.buy) f.filled - lvl else lvl - f.filled
        return per * abs(f.qty)
    }

    private fun rs(x: Double): String = (if (x < 0) "-" else "") + "Rs " + String.format(Locale.ENGLISH, "%,.0f", abs(x))
    private fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"

    /** The answer: the day's P&L, its parts, the slippage where known, and the biggest contributor named. */
    fun answer(d: Day): String {
        val realised = d.legs.sumOf { it.realised }
        val open = d.legs.sumOf { it.unrealised }
        val charges = d.charges
        val net = realised + open - charges
        if (d.legs.isEmpty() && d.tradeLegs == 0 && d.fills.isEmpty())
            return "Boss, there are no paper positions or trades today, so today's paper P&L is Rs 0 - nothing to take apart."
        val out = StringBuilder()
        // Said before charges, as every P&L is shown (Boss, 5 Oct), and after them too: the charges are one of the parts.
        out.append("Boss, today's paper P&L is ${rs(realised + open)} before charges, ${rs(net)} after charges: ")
        out.append("booked (realised) ${rs(realised)}, still open (unrealised) ${rs(open)} - that part moves with the price until it is closed - ")
        out.append("and charges of ${rs(-charges)} on ${plural(d.tradeLegs, "trade leg")}.")
        // Slippage against the intended levels, where there was one.
        val known = d.fills.mapNotNull { f -> slippage(f)?.let { f to it } }
        val unknown = d.fills.count { slippage(it) == null }
        if (known.isNotEmpty()) {
            val slip = known.sumOf { it.second }
            val stops = known.filter { it.first.type.uppercase(Locale.ENGLISH).startsWith("SL") }
            out.append(" Slippage against your intended levels: ")
            out.append(if (slip > 0) "${rs(slip)} worse" else if (slip < 0) "${rs(-slip)} better" else "none")
            out.append(" on ${plural(known.size, "order")} with a limit or a trigger")
            if (stops.isNotEmpty()) out.append(" (${plural(stops.size, "stop")}: ${rs(stops.sumOf { it.second })} past the trigger)")
            out.append(" - already inside the figures above, not extra.")
        }
        if (unknown > 0) out.append(" ${plural(unknown, "market order")} had no intended level kept, so ${if (unknown == 1) "its" else "their"} slippage isn't known.")
        // The biggest contributor among the parts, and the leg that moved most.
        val parts = listOf("the open (unrealised) part" to open, "the booked (realised) part" to realised, "the charges" to -charges)
        val big = parts.filter { it.second != 0.0 }.maxByOrNull { abs(it.second) }
        if (big != null) out.append(" The biggest part is ${big.first}, ${rs(big.second)}")
        val leg = d.legs.filter { it.total != 0.0 }.maxByOrNull { abs(it.total) }
        if (big != null && leg != null) out.append("; the leg that moved most is ${leg.symbol}, ${rs(leg.total)} before charges.")
        else if (big != null) out.append(".")
        else if (leg != null) out.append(" The leg that moved most is ${leg.symbol}, ${rs(leg.total)} before charges.")
        if (charges > 0 && abs(realised + open) > 0 && charges >= abs(realised + open))
            out.append(" The charges were as large as the whole move before them.")
        out.append(" Facts only, from the paper book.")
        return out.toString()
    }
}
