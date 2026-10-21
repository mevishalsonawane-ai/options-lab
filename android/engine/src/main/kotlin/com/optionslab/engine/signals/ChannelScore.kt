package com.optionslab.engine.signals

import com.optionslab.engine.orb.Watchlist
import com.optionslab.engine.signals.CallEval.Variant
import java.util.Locale
import kotlin.math.abs

/**
 * Each Telegram channel's honest scorecard in the Signal tester (15 Oct 2026; shadow - never trades): how many messages, how
 * many were calls and how many the reader understood without help (the parse rate), how many triggered, the win rate, average
 * win and loss, the would-be net of each exit variant ([CallEval.Variant]) after the paper slippage and real charges, the
 * deepest drawdown, how often the price had already moved before the call (and by how much on average), and - from
 * [REVIEW_CALLS] triggered calls - a p against random entries on the same day and contract (the Watchlist's twin method,
 * [Watchlist.pVsRandom]) on the "with a stop" variant. At [REVIEW_CALLS] Jarvis says once whether the channel beat random after
 * costs and the reaction delay, or failed ([verdictLine]); nothing is ever promoted or traded. Edited calls (their first text is
 * unknown) are not scored. Pure.
 */
object ChannelScore {
    const val REVIEW_CALLS = Watchlist.REVIEW_TRADES
    /** The variant the p and the verdict are on: every call has a stop there. */
    val MAIN = Variant.STOPPED

    /** One message's part in the card. */
    data class Item(val kind: Kind, val byRules: Boolean = true, val triggered: Boolean? = null, val nets: Map<Variant, Double> = emptyMap(),
                    val movedBefore: Boolean? = null, val preMove: Double? = null, val twins: List<Double> = emptyList(), val edited: Boolean = false,
                    /** A call edited after posting, seen live (its first text kept and scored, flagged). */
                    val editedLater: Boolean = false,
                    /** A follow-up's claim checked against the prices ([CallReader.verdict]); null: not a checked claim. */
                    val verdict: CallReader.Verdict? = null)

    enum class Kind { CALL, FOLLOW_UP, NOT_A_CALL, REVIEW }

    data class Card(val channel: String, val messages: Int, val calls: Int, val followUps: Int, val notCalls: Int, val review: Int, val understood: Int,
                    val evaluated: Int, val triggered: Int, val wins: Int, val avgWin: Double?, val avgLoss: Double?, val net: Map<Variant, Double>,
                    val maxDrawdown: Double, val movedBefore: Int, val preMoveAvg: Double?, val p: Double?,
                    val claimsConfirmed: Int = 0, val claimsNotReached: Int = 0, val claimsAfterStop: Int = 0, val editedLater: Int = 0) {
        /** The channel's target and stop claims checked against the prices. */
        val claims: Int get() = claimsConfirmed + claimsNotReached + claimsAfterStop
        /** Calls and follow-ups understood without Boss's help, of all that looked like calls. */
        val parseRate: Double? get() = (calls + followUps + review).let { if (it == 0) null else understood.toDouble() / it }
        val triggeredRate: Double? get() = if (evaluated == 0) null else triggered.toDouble() / evaluated
        val winRate: Double? get() = if (triggered == 0) null else wins.toDouble() / triggered
        val movedShare: Double? get() = if (evaluated == 0) null else movedBefore.toDouble() / evaluated
        val mainNet: Double get() = net[MAIN] ?: 0.0
        /** Beat random entries after costs and delay: money made and p < 0.05, from 40 triggered calls. */
        val passed: Boolean get() = triggered >= REVIEW_CALLS && mainNet > 0 && p != null && p < 0.05
    }

    /** [channel]'s card from its messages' [items] (oldest first). */
    fun card(channel: String, items: List<Item>): Card {
        val calls = items.filter { it.kind == Kind.CALL }
        val scored = calls.filter { !it.edited && it.triggered != null }
        val trig = scored.filter { it.triggered == true && MAIN in it.nets }
        val mains = trig.map { it.nets.getValue(MAIN) }
        val net = Variant.entries.associateWith { v -> trig.sumOf { it.nets[v] ?: 0.0 } }.filterKeys { v -> trig.any { v in it.nets } }
        var peak = 0.0; var cum = 0.0; var dd = 0.0
        for (x in mains) { cum += x; peak = maxOf(peak, cum); dd = maxOf(dd, peak - cum) }
        val wins = mains.filter { it > 0 }
        val losses = mains.filter { it <= 0 }
        val pre = scored.mapNotNull { it.preMove }
        val p = Watchlist.pVsRandom(mains, trig.map { it.twins })
        return Card(channel, items.size, calls.size, items.count { it.kind == Kind.FOLLOW_UP }, items.count { it.kind == Kind.NOT_A_CALL },
            items.count { it.kind == Kind.REVIEW }, items.count { (it.kind == Kind.CALL || it.kind == Kind.FOLLOW_UP) && it.byRules },
            scored.size, trig.size, wins.size, wins.takeIf { it.isNotEmpty() }?.average(), losses.takeIf { it.isNotEmpty() }?.average(), net, dd,
            scored.count { it.movedBefore == true }, pre.takeIf { it.isNotEmpty() }?.average(), p,
            items.count { it.verdict == CallReader.Verdict.CONFIRMED }, items.count { it.verdict == CallReader.Verdict.NOT_REACHED },
            items.count { it.verdict == CallReader.Verdict.AFTER_STOP }, calls.count { it.editedLater })
    }

    fun rs(x: Double): String = (if (x < 0) "−₹" else "+₹") + String.format(Locale.ENGLISH, "%,.0f", abs(x))
    private fun pct(x: Double?) = x?.let { String.format(Locale.ENGLISH, "%.0f%%", it * 100) } ?: "–"

    /** The card's one status line. */
    fun line(c: Card): String = "${c.calls} call${if (c.calls == 1) "" else "s"}, ${c.triggered} triggered" +
        (c.winRate?.let { ", ${pct(it)} won" } ?: "") + ", with a stop ${rs(c.mainNet)}" +
        (c.p?.let { ", p ${String.format(Locale.ENGLISH, "%.3f", it)}" } ?: "")

    /** The card in full, line by line (the channel's dialog). */
    fun details(c: Card): List<String> = listOfNotNull(
        "Messages ${c.messages}: ${c.calls} calls, ${c.followUps} follow-ups, ${c.notCalls} not calls, ${c.review} waiting for your review.",
        "Read without help: ${pct(c.parseRate)} of the call-like messages.",
        "Tested ${c.evaluated} calls: ${c.triggered} triggered (${pct(c.triggeredRate)}), ${c.wins} won (${pct(c.winRate)}).",
        "Average win ${c.avgWin?.let { rs(it) } ?: "–"}, average loss ${c.avgLoss?.let { rs(it) } ?: "–"} (with a stop).",
        "Would-be net after slippage and charges, 1 lot: " + Variant.entries.filter { it in c.net }.joinToString(" · ") { "${it.label} ${rs(c.net.getValue(it))}" } + ".",
        "Deepest drawdown ${rs(-c.maxDrawdown)}.",
        // 15 Oct (Boss): what the channel said about its own calls ("T1 done", ticks, "SL hit"), checked against the real prices.
        if (c.claims == 0) "Claims accuracy: no target or stop claims checked yet."
        else "Claims accuracy: of ${c.claims} hits the channel claimed, ${c.claimsConfirmed} confirmed by the real prices, " +
            "${c.claimsNotReached} not reached, ${c.claimsAfterStop} reached only after our stop.",
        if (c.editedLater > 0) "${c.editedLater} call${if (c.editedLater == 1) " was" else "s were"} edited after posting (scored as first posted)." else null,
        "Price already moved before the call: ${pct(c.movedShare)} of tested calls" +
            (c.preMoveAvg?.let { ", the underlying ${String.format(Locale.ENGLISH, "%+.2f%%", it)} on average in the 3 minutes before" } ?: "") + ".",
        if (c.p != null) "Against random entries on the same day and contract: p ${String.format(Locale.ENGLISH, "%.3f", c.p)} (${c.triggered} calls)."
        else "The test against random entries starts at $REVIEW_CALLS triggered calls (${c.triggered} so far).",
    )

    /** Jarvis's one-time word at [REVIEW_CALLS] triggered calls: beat random after costs and delay, or failed. Never a promotion. */
    fun verdictLine(c: Card): String = if (c.passed)
        "Boss, the Signal tester has ${c.triggered} triggered calls from ${c.channel}: with a stop, would-be net ${rs(c.mainNet)} after slippage, charges " +
            "and the reaction delay, better than random entries (p ${String.format(Locale.ENGLISH, "%.3f", c.p ?: 1.0)}). It stays shadow - " +
            "trading any of it is your call, after research and your PIN rules."
    else "Boss, the Signal tester has ${c.triggered} triggered calls from ${c.channel}: with a stop, would-be net ${rs(c.mainNet)}" +
        (c.p?.let { ", p ${String.format(Locale.ENGLISH, "%.3f", it)} against random entries" } ?: "") +
        ". It did not beat random entries after costs and delay."
}
