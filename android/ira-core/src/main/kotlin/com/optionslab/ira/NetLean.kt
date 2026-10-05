package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs

/**
 * Which way Boss's book leans right now, and who in it pulls against whom (reasoning, round 17, 2026-10-05): "am I net
 * long or short?", "which way am I leaning?", "what's my net delta on BankNifty?", "do my positions cancel each other
 * out?", "do my bots contradict each other right now?", "main long hoon ya short".
 *
 * [Exposure] answers a move of a size Boss names and [BotTrades] the opposite sides held earlier today; nothing put the
 * open book together by index and by owner. For each index under his open legs (Paper and Zerodha), from each leg's delta
 * now ([Exposure.Leg.delta], worked from the option's price): the net rupees per index point and which way that leans;
 * then the same split by who holds it - each arm (from the arms' own book), his other automations, his own Paper and
 * Zerodha trades - and, when owners lean opposite ways on one index, that they do and how much of the gross offsets. A
 * 1% move each way is given as a clearly-labelled conditional (delta and gamma now, everything else held), never as
 * what will happen.
 *
 * Facts and conditionals only - never a forecast, never advice (whether to hedge, close or keep is Boss's call); nothing
 * here places, changes or closes anything. Boss's account, so never on a locked phone. Pure.
 */
object NetLean {
    /** An open arm trade, as the arms' book keeps it: [source] the arm's id ("orb", "liquidity15"...), [live] at Zerodha. */
    data class ArmLeg(val source: String, val symbol: String, val qty: Int, val live: Boolean)

    /**
     * [legs]: the open legs ([Exposure.Leg], "Paper" or "Zerodha"); [arms]: the arms' open trades; [otherBots]: symbols
     * held by another automation (Pine, a built strategy, Jarvis's own trades) and not an arm; [spots]: each underlying's
     * price now by name ("NIFTY"); [zerodha]: whether Zerodha's legs were read.
     */
    data class Input(val legs: List<Exposure.Leg>, val arms: List<ArmLeg> = emptyList(), val otherBots: Set<String> = emptySet(),
                     val spots: Map<String, Double> = emptyMap(), val zerodha: SinceMorning.Zerodha = SinceMorning.Zerodha.READ,
                     val market: Market? = null)

    /** One owner's share of an index: rupees per index point ([perPoint]) and the legs behind it. */
    data class Share(val owner: String, val bot: Boolean, val perPoint: Double, val legs: Int)

    /** An index's lean: [net] rupees per point, [shares] by owner, [legs] the legs with a delta. */
    data class Lean(val underlying: String, val net: Double, val gross: Double, val shares: List<Share>, val legs: List<Exposure.Leg>)

    /** Below this share of the gross (by owner), the net reads as about flat. */
    const val FLAT_SHARE = 0.15
    /** The conditional move, percent of the index. */
    const val MOVE_PCT = 1.0

    const val NOTE = "From each leg's delta now, Boss - a rough sum that drifts as prices, time and implied volatility move. Facts, not a forecast and not advice; what to do with it, if anything, is your call."
    const val LOCKED = "Your positions stay out of it on a locked phone, Boss - unlock it for that."

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("’", "'").replace("'", "")
        .replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    private const val MY = "(my|our|mine|the|todays|today s)"
    private const val BOOK = "(positions?|book|portfolio|trades|open trades|open positions|legs|exposure|holdings?)"
    private const val BOT = "(bots?|algos?|arms?|strategies|paper bots?)"
    private const val NOW = "(right now|now|currently|at the moment|at present|as of now|open|still open)"
    private val ASK = listOf(
        // "Am I net long or short?", "am I long or short right now?", "am I net short on BankNifty?"
        " am i (net )?(long|short)( or (net )?(long|short))? ",
        " am i (net )?(long|short)( on| in)? ",
        " (is|are) $MY $BOOK (net )?(long|short)( or (net )?(long|short))? ",
        // "Which way am I leaning?", "which side am I on right now?", "which way is my book tilted?"
        " (which|what) (way|side|direction) (am i|is $MY $BOOK|are $MY $BOOK) (leaning|tilted|tilting|positioned|biased|pointed|facing|on) ",
        " (which|what) (way|side|direction) (do|does) $MY $BOOK (lean|tilt|point) ",
        " (which|what) side am i on ",
        " how (am i|is $MY $BOOK|are $MY $BOOK) (leaning|tilted|positioned|biased) ",
        // "What's my net delta?", "my net exposure on Nifty", "what is my net position"
        " (what is|whats|what s|show|tell me|give me)? ?$MY (net|overall|total) (delta|exposure|position|direction|bias|lean) ",
        " (net|overall|total) (delta|directional exposure|direction) (of|on|in|across) $MY ",
        // "Do my positions cancel each other out?", "are my positions offsetting?", "are my trades hedged against each other?"
        " (do|does) $MY $BOOK (cancel|offset|net) (each other|one another|out)",
        " (are|is) $MY $BOOK (offsetting|cancelling|canceling|hedged|hedging|against each other|fighting each other|working against each other)",
        " (do|does) any of $MY $BOOK (cancel|offset) ",
        // "Do my bots contradict each other right now?", "are my bots on opposite sides now?" - right now only: what they did
        // earlier today stays BotTrades'.
        " (do|does|are|is) $MY $BOT (contradict|contradicting|fight|fighting|against|on opposite sides|opposite|offset|offsetting|cancel|cancelling)( each other| one another)? $NOW ",
        " $NOW (do|does|are|is) $MY $BOT (contradict|contradicting|fight|fighting|on opposite sides|opposite|offset|offsetting|cancel|cancelling) ",
        " (are|is) $MY $BOT and $MY (own |manual |zerodha |paper )?(trades|positions|book) (on )?(opposite|different) (sides|ways) ",
        " (do|does) $MY $BOT (lean|point|face) (the )?(opposite|different) ways? $NOW ",
        // Hinglish: "main long hoon ya short", "mera net position kis taraf hai", "mere bots ek dusre ke against hain kya"
        " (main|mai|me|hum) (net )?(long|short) (hoon|hu|hun|hain|he)( ya (long|short))? ",
        " (mera|meri|mere|hamara|hamari) (net )?(position|positions|book|exposure|delta|trades) (kis|kaun si|kaunsi) (taraf|side|direction) ",
        " (mere|hamare) (bots?|algos?) (ek dusre|ek doosre|aapas) (ke|me|mein) (against|ulta|ulte|khilaf) ",
    ).map { rx(it) }
    /** Not this: a what-if (Exposure's and Scenarios'), advice, an order, another day, a definition. */
    private val NOT = rx(" (if|suppose|should|shall|recommend|suggest|advise|advice|buy|sell|close|exit|square|hedge it|go long|go short|tomorrow|yesterday|kal|last week|what does|meaning|mean by|define|explain net delta) ")

    /** "Am I net long or short?", "which way am I leaning?", "what's my net delta?", "do my bots contradict each other right now?". */
    fun asked(text: String): Boolean {
        for (s in listOf(text, Ask.reading(text))) {
            val t = norm(s)
            if (NOT.containsMatchIn(t)) continue
            if (ASK.any { it.containsMatchIn(t) }) return true
        }
        return false
    }

    /** The index asked about ("am I net long on BankNifty?"), or null for all. */
    fun market(text: String): Market? = Market.mentioned(text).firstOrNull { it != Market.VIX && it != Market.GOLD }

    // ---- the reading ----------------------------------------------------------------------------------------------

    private const val YOURS_PAPER = "your own Paper trades"
    private const val YOURS_ZERODHA = "your own Zerodha trades"
    private const val OTHER = "your other automations"

    /** Each leg split by owner: the arms' quantity first (same symbol, account and side), the rest the other bots' or Boss's. */
    internal fun owned(i: Input): List<Triple<String, Boolean, Exposure.Leg>> {
        val out = ArrayList<Triple<String, Boolean, Exposure.Leg>>()
        val arms = i.arms.toMutableList()
        for (l in i.legs) {
            if (l.qty == 0) continue
            var left = l.qty
            val where = l.where.equals("Zerodha", true)
            val mine = arms.filter { it.symbol.equals(l.symbol, true) && it.live == where && it.qty != 0 && (it.qty > 0) == (l.qty > 0) }
            for (a in mine) {
                if (left == 0) break
                val take = if (abs(a.qty) >= abs(left)) left else a.qty
                out += Triple(BotTrades.recordName(a.source), true, l.copy(qty = take))
                left -= take
                arms.remove(a)
            }
            if (left != 0) {
                val owner = when {
                    l.symbol in i.otherBots -> OTHER
                    where -> YOURS_ZERODHA
                    else -> YOURS_PAPER
                }
                out += Triple(owner, owner == OTHER, l.copy(qty = left))
            }
        }
        return out
    }

    /** Each index's lean, the biggest first; legs without an underlying or a delta are left out (see [unread]). */
    fun leans(i: Input): List<Lean> {
        val parts = owned(i).filter { it.third.delta != null && it.third.underlying != null }
        return parts.groupBy { it.third.underlying!!.uppercase(Locale.ENGLISH) }.map { (u, ps) ->
            val shares = ps.groupBy { it.first }.map { (o, xs) ->
                Share(o, xs.first().second, xs.sumOf { it.third.qty * it.third.delta!! }, xs.size)
            }.sortedByDescending { abs(it.perPoint) }
            Lean(u, shares.sumOf { it.perPoint }, shares.sumOf { abs(it.perPoint) }, shares, ps.map { it.third })
        }.filter { i.market == null || it.underlying == i.market.name }.sortedByDescending { abs(it.net) }
    }

    /** The open legs whose lean can't be worked out (no underlying or no delta known). */
    fun unread(i: Input): List<Exposure.Leg> = i.legs.filter { it.qty != 0 && (it.delta == null || it.underlying == null) }
        .filter { i.market == null || it.underlying == null || it.underlying.equals(i.market.name, true) }

    private fun label(u: String) = runCatching { Market.valueOf(u).label }.getOrDefault(u)
    private fun n0(x: Double) = "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + n0(x)
    private fun way(x: Double) = if (x > 0) "long" else "short"
    private fun flat(net: Double, gross: Double) = gross <= 0.0 || abs(net) < FLAT_SHARE * gross

    private fun lean(l: Lean, spot: Double?): String {
        val out = ArrayList<String>()
        val name = label(l.underlying)
        out += if (flat(l.net, l.gross)) "$name: about flat - ${rs(l.net)} for each point it rises (the owners below pull opposite ways)."
            else "$name: net ${way(l.net)} - about ${rs(l.net)} for each point it rises, ${rs(-l.net)} for each point it falls."
        if (spot != null && spot > 0) {
            val pts = spot * MOVE_PCT / 100
            val up = l.legs.sumOf { Exposure.change(it, pts) }; val down = l.legs.sumOf { Exposure.change(it, -pts) }
            out += "If $name moved 1% from ${n0(spot)} (about ${n0(pts)} points), that would be about ${rs(up)} on a rise and ${rs(down)} on a fall, " +
                "on delta and gamma now with everything else held - a rough figure, not what will happen."
        }
        if (l.shares.size > 1) out += "By who holds it: " + l.shares.joinToString("; ") { s ->
            "${s.owner} ${if (abs(s.perPoint) < 0.5) "about flat" else "${way(s.perPoint)} ${rs(s.perPoint)} a point"}"
        } + "."
        val longs = l.shares.filter { it.perPoint >= 0.5 }; val shorts = l.shares.filter { it.perPoint <= -0.5 }
        if (longs.isNotEmpty() && shorts.isNotEmpty()) {
            val offset = l.gross - abs(l.net)
            val pct = if (l.gross > 0) (offset / l.gross * 100).toInt() else 0
            val bots = longs.any { it.bot } && shorts.any { it.bot } && (longs + shorts).count { it.bot } >= 2
            val who = "${longs.joinToString(" and ") { it.owner }} ${if (longs.size == 1) "leans" else "lean"} long while " +
                "${shorts.joinToString(" and ") { it.owner }} ${if (shorts.size == 1) "leans" else "lean"} short"
            out += (if (bots) "Yes - your bots are on opposite sides of $name right now: " else "These pull against each other on $name: ") +
                "$who; about $pct% of the gross ${rs(l.gross).removePrefix("+")} a point offsets. That is a fact about the book, not a judgement on either side."
        } else if (l.shares.size > 1) out += "Nobody in it pulls the other way on $name."
        return out.joinToString(" ")
    }

    /** The answer from Boss's open book (call only on an unlocked phone). */
    fun answer(i: Input): String {
        val out = ArrayList<String>()
        val missing = when (i.zerodha) {
            SinceMorning.Zerodha.READ -> null
            SinceMorning.Zerodha.FAILED -> "Zerodha didn't answer in time just now, so its legs are not in this - only Paper's."
            SinceMorning.Zerodha.LOGGED_OUT -> "You're not logged in to Zerodha, so this is Paper's legs only."
        }
        val open = i.legs.filter { it.qty != 0 }
        if (open.isEmpty()) {
            out += "You hold no open positions right now, Boss, so the book leans neither way."
            missing?.let { out += it }
            return out.joinToString(" ")
        }
        val ls = leans(i)
        val skipped = unread(i)
        if (ls.isEmpty()) {
            out += if (i.market != null && skipped.isEmpty()) "None of your open positions are on ${i.market.label}, Boss."
                else "I can't work out which way your book leans just now, Boss: the deltas of your open legs aren't known (the index price may not be on the phone yet)."
        } else {
            out += "Right now, Boss, by each leg's delta:"
            ls.forEach { out += lean(it, i.spots[it.underlying]) }
            val bots = ls.flatMap { l -> l.shares.filter { it.bot }.map { l.underlying } }.distinct()
            if (ls.size > 1 && bots.size > 1 && ls.none { l -> l.shares.count { it.bot } >= 2 })
                out += "Your bots are on different indices, so they don't face each other directly."
        }
        if (skipped.isNotEmpty()) out += "Left out - no delta known: " + skipped.take(4).joinToString(", ") { "${it.where} ${it.symbol}" } +
            (if (skipped.size > 4) " and ${skipped.size - 4} more" else "") + "."
        missing?.let { out += it }
        if (ls.isNotEmpty()) out += NOTE
        return out.joinToString(" ")
    }
}
