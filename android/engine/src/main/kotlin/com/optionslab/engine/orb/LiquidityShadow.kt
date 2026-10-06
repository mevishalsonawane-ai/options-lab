package com.optionslab.engine.orb

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * Liquidity 15+5's two candidate rules, pre-registered on 06 Oct 2026 and tracked in the shadow of its paper trades - they
 * never change what it trades (research of 06 Oct on six years of real data: neither was validated, both lost in the
 * clean windows; adopt one only if net a trade improves over at least [MIN_TRADES] paper trades):
 *
 *   (a) skip a signal when the next liquidity level ahead is closer than one index-stop unit (BANKNIFTY 30, FINNIFTY 15
 *       points: [LiquidityRules.indexStopPoints]) - recorded on each signal as [nearLevel];
 *   (b) drop the FINNIFTY 30-minute book ([finThirty]).
 *
 * Counted from [SINCE] (the day Boss switched it back on, paper only). Pure: no clock, no storage.
 */
object LiquidityShadow {
    val SINCE: LocalDate = LocalDate.of(2026, 10, 6)
    const val MIN_TRADES = 40

    /**
     * Candidate (a) for a signal on [underlying] that closed at [close], going [side] (+1 up, -1 down), with the next
     * liquidity level ahead at [target] (null: none ahead, so it is never skipped).
     */
    fun nearLevel(underlying: String, side: Int, close: Double, target: Double?): Boolean =
        target != null && side * (target - close) < LiquidityRules.indexStopPoints(underlying)

    /** Candidate (b): the trade is the FINNIFTY 30-minute book's. */
    fun finThirty(book: String): Boolean = book == LiquidityRules.FIN30.source

    /**
     * One closed paper trade: the [day] it was entered, its [net] rupees after charges, candidate (a)'s flag as recorded at
     * the signal ([near]; null: entered before the flag was recorded, counted as kept), and its [book].
     */
    data class Trade(val day: LocalDate, val net: Double, val near: Boolean?, val book: String)

    /** A set of trades: how many, and their net. */
    data class Cut(val trades: Int, val net: Double) {
        /** Net a trade, or null with no trades. */
        val perTrade: Double? get() = if (trades == 0) null else net / trades
    }

    /** Every trade since [SINCE] ([all]), and what each candidate would have kept. [untracked]: trades with no (a) flag. */
    data class Summary(val all: Cut, val withoutNear: Cut, val withoutFin30: Cut, val untracked: Int) {
        val enough: Boolean get() = all.trades >= MIN_TRADES
    }

    fun summarize(trades: List<Trade>): Summary {
        val since = trades.filter { !it.day.isBefore(SINCE) }
        fun cut(x: List<Trade>) = Cut(x.size, x.sumOf { it.net })
        return Summary(cut(since), cut(since.filter { it.near != true }), cut(since.filter { !finThirty(it.book) }), since.count { it.near == null })
    }

    private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + String.format(Locale.ENGLISH, "%,.0f", abs(x))
    private fun per(c: Cut) = c.perTrade?.let { rs(it) + " a trade" } ?: "no trades"

    /** The Liquidity row's line: the trades so far, net, net a trade, and net a trade without each candidate's trades. */
    fun line(s: Summary): String =
        "Since 06 Oct: ${s.all.trades} paper trade${if (s.all.trades == 1) "" else "s"}, ${rs(s.all.net)} net (${per(s.all)}) · " +
            "(a) skip a level within one index stop: ${s.withoutNear.trades}, ${per(s.withoutNear)} · " +
            "(b) no FINNIFTY 30m: ${s.withoutFin30.trades}, ${per(s.withoutFin30)}" +
            (if (s.untracked > 0) " · ${s.untracked} from before (a) was recorded" else "")

    /** Whether candidate [c] helped against [all]: it kept at least one trade, dropped one, and made more a trade. */
    fun helped(all: Cut, c: Cut): Boolean {
        val a = all.perTrade ?: return false
        val p = c.perTrade ?: return false
        return c.trades < all.trades && p > a
    }

    /**
     * What Jarvis says of the two candidates: below [MIN_TRADES] trades since 06 Oct, how many so far and that they are
     * judged at [MIN_TRADES]; from then on, which of them helped (net a trade with and without it).
     */
    fun verdict(s: Summary): String {
        if (!s.enough) return "Liquidity 15+5 has ${s.all.trades} of $MIN_TRADES paper trades since 06 Oct; its two candidate rules " +
            "are judged at $MIN_TRADES. So far: ${line(s)}."
        val a = helped(s.all, s.withoutNear)
        val b = helped(s.all, s.withoutFin30)
        val which = when {
            a && b -> "Both helped"
            a -> "Candidate (a) helped, (b) did not"
            b -> "Candidate (b) helped, (a) did not"
            else -> "Neither helped"
        }
        return "$which, Boss. Liquidity 15+5 made ${per(s.all)} over ${s.all.trades} paper trades since 06 Oct; " +
            "(a) skipping signals with the next level within one index stop: ${per(s.withoutNear)} over ${s.withoutNear.trades}; " +
            "(b) without FINNIFTY 30m: ${per(s.withoutFin30)} over ${s.withoutFin30.trades}. Nothing changes by itself: adopting one is yours."
    }
}
