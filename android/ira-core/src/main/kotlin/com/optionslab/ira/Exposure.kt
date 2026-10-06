package com.optionslab.ira

import com.optionslab.engine.options.OptionMath
import com.optionslab.engine.options.OptionType
import java.util.Locale
import kotlin.math.abs

/**
 * Boss's own open positions, asked about (the owner's wish, 2026-10-05): "what happens to my P&L if Nifty moves 100
 * points" and "which of my positions is losing most". Read from what the app holds; the move is a rough sum from each
 * position's delta and gamma now (IV and time held still), never a forecast. Questions only: nothing here places,
 * changes or closes anything. Pure.
 *
 * Reasoning, round 32 (2026-10-06): each open option's price at the move is estimated too - repriced at its own
 * implied volatility with the engine's [OptionMath] where the app worked one out ([Leg.model]), else from its delta and
 * gamma (held at the bottom of the curve, never below zero) - and set against the position's own stop and target: which
 * would be hit at that move, and what it would come to from here if it filled there. Before charges, an estimate,
 * never advice. Boss's Hinglish asks it too ("agar Nifty 50 point gire to mera kya hoga").
 */
object Exposure {
    /**
     * One open position. [underlying]: "NIFTY", "BANKNIFTY"... when the app knows it; [delta] and [gamma]: per unit of
     * the underlying (an option's from its price now; the index itself 1 and 0), null when not known.
     */
    data class Leg(val where: String, val symbol: String, val qty: Int, val avg: Double, val ltp: Double,
                   val underlying: String? = null, val delta: Double? = null, val gamma: Double? = null,
                   /** The position's own stop trigger and target (premium, as the app keeps them), null: none. */
                   val stop: Double? = null, val target: Double? = null,
                   /** An option's pricing inputs when the app worked out its IV, to reprice it at a move. */
                   val model: Model? = null) {
        val pnl get() = (ltp - avg) * qty
    }

    /**
     * An option as priced now: [forward] the underlying's price its IV was solved at, [sigma] that IV (a decimal, 0.145
     * = 14.5%), [years] to expiry. Rate 0, as the app's Greeks use.
     */
    data class Model(val call: Boolean, val strike: Double, val years: Double, val sigma: Double, val forward: Double)

    /** A move asked: [points] or [pct] of [market]; [sign] +1 up, -1 down, 0 either way. */
    data class Shock(val market: Market, val points: Double?, val pct: Double?, val sign: Int)

    private fun norm(text: String) = " " + text.lowercase().replace("p&l", "p l").replace("%", " percent ")
        .replace(rx("[^a-z0-9. ]"), " ").replace(rx("(?<![0-9])\\.|\\.(?![0-9])"), " ").replace(rx("\\s+"), " ").trim() + " "

    private val OWNER = Regex(" (my|mine|our|i|me|we|us) ")
    private val IF = Regex(" (if|suppose|say|what happens|what would happen|in case) ")
    private val SIZE = Regex(" (\\d+(?:\\.\\d+)?) ?(points?|pts?|percent|per cent) ")
    private val DOWN = Regex(" (falls?|fell|drops?|dropped|goes down|go down|comes down|crash(es)?|tanks?|slides?|sinks?|declines?|is down|down) ")
    private val UP = Regex(" (rises?|rose|goes up|go up|jumps?|rall(y|ies)|climbs?|gains?|surges?|is up|up) ")
    private val MOVE = Regex(" (moves?|moved|swings?|changes?) ")

    /**
     * Boss's Hinglish for his own book at a move (reasoning, round 32): "agar Nifty 50 point gire to mera kya hoga",
     * "Nifty 100 point gira to meri positions ka kya hoga" - read as the English question. An order or advice in Hindi
     * ("to mera call bech do", "to kya karu") is refused below, never read as the move.
     */
    private val SAID = listOf(
        Regex(" (agar|agr|yadi) ") to " if ",
        Regex(" (kya|kia) (hoga|hogi|hoge|hota|hoti)( phir)?( kya)? ") to " what happens ",
        // Understanding round 29: what it comes to, as Hindi asks it - "to mera kitna loss hoga", "to mujhe kitna nuksan hoga",
        // "to mere p&l pe kya asar hoga", "to meri positions ka kya haal hoga", "to kitna jayega mera".
        Regex(" (kitna|kitni|kitne) (loss|nuksan|nuksaan|nuqsan|ghata|profit|fayda|faida|munafa) (hoga|hogi|ho jayega|ho jaega|banega|banegi)( kya)? ") to " what happens ",
        Regex(" (kya|kitna|kitni) (asar|effect|impact|farak|fark) (hoga|padega|padegi)( kya)? ") to " what happens ",
        Regex(" (kya|kaisa|kaisi) haal (hoga|hogi|honge) ") to " what happens ",
        Regex(" (kitna|kitne) (jayega|jaega|jaayega|doobega|dubega|udega) ") to " what happens ",
        Regex(" (mujhe|mujhko|hume|humein|hamein|hamko) ") to " me ",
        Regex(" (mera|meri|mere|hamara|hamari|hamare|apna|apni|apne) ") to " my ",
        Regex(" (gir|gire|gira|giri|girta|girti|tut|toot|toote|tute)( (jaye|jaaye|jae|jata|jaata|gaya|gayi|jati)( hai)?| hai)?(?= )") to " falls",
        Regex(" (chadh|chadhe|chadha|chadhi|chadhta|badh|badhe|badha|badhta|uchhle|uchhal)( (jaye|jaaye|jae|jata|jaata|gaya|gayi|jati)( hai)?| hai)?(?= )") to " rises",
        Regex(" (neeche|niche) ") to " down ", Regex(" (upar|uppar|oopar) ") to " up ",
    )
    /** An order, or advice, said in Hindi: never the positions' what-if. */
    private val HINDI_ACT = Regex(" (bech|becho|bechu|bechun|bechna|bech do|kharid|kharido|kharidu|khareed|khareedo|lagao|lagau|lagana|nikal|nikalo|" +
        "nikaal|nikaalo|katu|kaat|kaato|karu|karun|karoon|karein|karna chahiye|chahiye|band karo|exit karo) ")

    /** An order or exit verb anywhere in the sentence, English or Hinglish ("sell kar do", "square off karo", "close my call"). */
    private val ACT_ANYWHERE = Regex(" ((sell|exit|close|square ?off|squareoff|cut|nikal|nikaal|kaat|hedge|place|cancel|modify)( (kar|karo|kar do|kardo|kar dena|kar lo|karke|karna|kar dijiye|do|dena))?" +
        "|buy|(book|band) (kar|karo|kar do|kardo|kar dena|kar lo|karke|karna|kar dijiye|profit|profits|my|the|it)) ")

    /** "What happens to my P&L if Nifty moves 100 points", "if BankNifty falls 1% what do I lose": the move, or null. */
    fun moveAsked(text: String): Shock? {
        val n0 = norm(text)
        if (HINDI_ACT.containsMatchIn(n0)) return null
        // A conditional instruction in any mix ("agar nifty gire to mera call sell kar do", "if nifty falls 100 points close
        // my call", "... square off karo", "... book kar lo"): never read as the what-if, so Jarvis never answers an exit
        // Boss means with an estimate he may take for an exit set up.
        if (ACT_ANYWHERE.containsMatchIn(n0)) return null
        val t = SAID.fold(n0) { a, (r, w) -> r.replace(a, w) }.replace(rx("\\s+"), " ")
        if (!IF.containsMatchIn(t) || !OWNER.containsMatchIn(t)) return null
        // "Should I buy puts if Nifty falls", "if I buy the 24500 CE and Nifty moves": advice or a new trade, not the positions.
        if (rx("\\b(should|shall|recommend|suggest)\\b|\\b(buy|sell)\\b.*\\b(ce|pe|call|put|calls|puts)\\b").containsMatchIn(t)) return null
        val size = SIZE.find(t) ?: return null
        val n = size.groupValues[1].toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        val pct = size.groupValues[2].startsWith("per")
        if (pct && n > 20 || !pct && n > 5000) return null
        val down = DOWN.containsMatchIn(t); val up = UP.containsMatchIn(t)
        if (!down && !up && !MOVE.containsMatchIn(t)) return null
        val ms = Market.mentioned(text)
        // Gold is IraGoldAlgo's own, never read here as a Nifty move.
        if (Market.GOLD in ms) return null
        val m = ms.firstOrNull { it != Market.VIX } ?: Market.NIFTY
        val sign = if (down && !up) -1 else if (up && !down) 1 else 0
        return Shock(m, if (pct) null else n, if (pct) n else null, sign)
    }

    private fun f(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    private fun signed(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + f(abs(x))

    /** An option leg (a strike's CE or PE), whose price is estimated at a move; the index, futures and shares are not. */
    fun option(l: Leg): Boolean = l.model != null || rx("\\d(CE|PE)$").containsMatchIn(l.symbol.uppercase(Locale.ENGLISH))

    /**
     * [l]'s price estimated if its underlying moves [ds] points, IV and time held still: repriced with [OptionMath] at
     * its own IV where [Leg.model] is known (the change from its price now added to the price now), else from delta and
     * half gamma - held at the bottom of the curve (a long option's estimate never turns back up past where its delta
     * would cross zero) and never below zero.
     */
    fun price(l: Leg, ds: Double): Double {
        val m = l.model
        if (m != null && m.forward + ds > 0 && m.strike > 0 && m.years > 0 && m.sigma > 0) {
            val type = if (m.call) OptionType.CE else OptionType.PE
            val now = OptionMath.price(type, m.forward, m.strike, m.years, 0.0, m.sigma)
            val then = OptionMath.price(type, m.forward + ds, m.strike, m.years, 0.0, m.sigma)
            if (now.isFinite() && then.isFinite()) return maxOf(0.0, l.ltp + then - now)
        }
        val d = l.delta ?: 0.0; val g = l.gamma ?: 0.0
        // Past the vertex of delta*ds + gamma*ds^2/2 (ds = -delta/gamma) the parabola climbs again: held at its bottom.
        val x = if (g > 0 && d != 0.0 && (d + g * ds) * d < 0) -d / g else ds
        return maxOf(0.0, l.ltp + d * x + 0.5 * g * x * x)
    }

    /** The change on [l] (rupees) if its underlying moves [ds] points: its estimated price ([price]) less now, times the quantity. */
    fun change(l: Leg, ds: Double): Double = l.qty * (price(l, ds) - l.ltp)

    /**
     * [l]'s own stop and target set against its estimated price at each way asked ([ways]: -1 a fall, +1 a rise, of
     * [pts] points): which would be hit, and what it would come to from here if it filled there (before charges). Null
     * when the leg has neither, or is not an option.
     */
    fun levels(l: Leg, pts: Double, ways: List<Int>, market: Market): String? {
        if (!option(l) || l.qty == 0) return null
        val stop = l.stop?.takeIf { it.isFinite() && it > 0 }
        val target = l.target?.takeIf { it.isFinite() && it > 0 }
        if (stop == null && target == null) return null
        val long = l.qty > 0
        val head = "${l.where} ${l.symbol} (${l.qty}, now ${AppFacts.px(l.ltp)})"
        val parts = ArrayList<String>()
        val stopPast = stop != null && (if (long) l.ltp <= stop else l.ltp >= stop)
        val targetPast = target != null && (if (long) l.ltp >= target else l.ltp <= target)
        if (stop != null && stopPast) parts += "already at or past its ${AppFacts.px(stop)} stop"
        if (target != null && targetPast) parts += "already at or past its ${AppFacts.px(target)} target"
        var stopHit = false
        var targetHit = false
        for (w in ways) {
            val est = price(l, w * pts)
            val way = "if ${market.label} ${if (w < 0) "falls" else "rises"}"
            if (stop != null && !stopPast && (if (long) est <= stop else est >= stop)) {
                stopHit = true
                parts += "its ${AppFacts.px(stop)} stop would be hit $way (about ${AppFacts.px(est)} by this estimate) - " +
                    "${signed((stop - l.ltp) * l.qty)} from here if it filled at the stop"
            }
            if (target != null && !targetPast && (if (long) est >= target else est <= target)) {
                targetHit = true
                parts += "its ${AppFacts.px(target)} target would be reached $way (about ${AppFacts.px(est)}) - " +
                    "${signed((target - l.ltp) * l.qty)} from here if it filled there"
            }
        }
        val untouched = listOfNotNull(stop?.takeIf { !stopPast && !stopHit }?.let { "${AppFacts.px(it)} stop" },
            target?.takeIf { !targetPast && !targetHit }?.let { "${AppFacts.px(it)} target" })
        if (untouched.isNotEmpty()) parts += "its ${untouched.joinToString(" and ")} would not be reached at that move " +
            "(about ${ways.joinToString(" or ") { AppFacts.px(price(l, it * pts)) }})"
        return "$head: ${parts.joinToString("; ")}."
    }

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
        val ways = when (s.sign) { 1 -> listOf(1); -1 -> listOf(-1); else -> listOf(1, -1) }
        counted.sortedBy { minOf(change(it, pts), change(it, -pts)) }.take(5).forEach { l ->
            val est = if (!option(l)) "" else ", its price about ${AppFacts.px(l.ltp)} now, about " + when (s.sign) {
                1 -> AppFacts.px(price(l, pts))
                -1 -> AppFacts.px(price(l, -pts))
                else -> "${AppFacts.px(price(l, pts))} up, ${AppFacts.px(price(l, -pts))} down"
            } + " then"
            out += "${l.where} ${l.symbol} (${l.qty}): " + when (s.sign) {
                1 -> "about ${signed(change(l, pts))}"
                -1 -> "about ${signed(change(l, -pts))}"
                else -> "about ${signed(change(l, pts))} up, ${signed(change(l, -pts))} down"
            } + ", delta %.2f".format(Locale.ENGLISH, l.delta!!) + "$est."
        }
        // Each position's own stop and target at that move (those the app keeps: a protection's, a strategy's stop).
        val at = counted.mapNotNull { levels(it, pts, ways, s.market) }
        out += at.take(5)
        if (at.size > 5) out += "And ${at.size - 5} more with a stop or target set."
        if (at.isNotEmpty()) out += "A stop is a trigger, not a price: a gap or a fast market fills past it."
        if (left.isNotEmpty()) out += "Not counted: " + left.take(4).joinToString(", ") { "${it.where} ${it.symbol}" } +
            (if (left.size > 4) " and ${left.size - 4} more" else "") + " (another underlying, or no delta known)."
        out += "A rough guide, Boss: an estimate from each position's delta and gamma now (an option repriced at its own IV where " +
            "known), with IV and time held still, before charges - not a forecast and not advice. What to do, if anything, is your call."
        return out
    }

    private fun fmtPct(p: Double) = (if (p == Math.floor(p)) "%.0f" else "%.1f").format(Locale.ENGLISH, p) + "%"

    private val RANK = Regex(" ((losing|lost|down|bleeding|hurting|winning|making|up|gaining) (the )?most|most (in the red|in the green)|" +
        "(worst|best|weakest|strongest|biggest losing|biggest winning) (open )?(position|positions|trade|trades)|biggest (loser|losers|winner|winners)|" +
        "rank (my |the )?(open )?(positions|trades)|(positions|trades) ranked|which (of my |one of my )?(open )?(position|positions|trade|trades) (is|are) (losing|winning|down|up|in the red|in the green)) ")

    /**
     * "Which of my positions is losing most", "my worst position", "rank my positions" - and with no "my" when it names a
     * position ("which position is losing the most", "worst position": the open positions are Boss's; routing audit, round
     * 8), but never one to take ("the best position to take", "worst position for Nifty").
     */
    fun rankAsked(text: String): Boolean {
        val t = norm(text)
        return RANK.containsMatchIn(t) && rx(" (position|positions|trade|trades|loser|losers|winner|winners) ").containsMatchIn(t) &&
            (OWNER.containsMatchIn(t) || rx(" (position|positions) ").containsMatchIn(t) &&
                !rx(" (to|for|should|shall|would|take|buy|sell|enter|short|long|market|markets|stocks|shares|sector|index|indices) ").containsMatchIn(t)) &&
            !rx(" (today s|yesterday|week|month|year|all time|ever|history|closed) ").containsMatchIn(t)
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
