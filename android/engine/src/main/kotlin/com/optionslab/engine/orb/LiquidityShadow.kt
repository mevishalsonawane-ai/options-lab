package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs

/**
 * Liquidity 15+5's two candidate rules, pre-registered on 06 Oct 2026 and tracked in the shadow of its paper trades - they
 * never change what it trades (research of 06 Oct on six years of real data: neither was validated, both lost in the
 * clean windows; adopt one only if net a trade improves over at least [MIN_TRADES] paper trades):
 *
 *   (a) skip a signal when the next liquidity level ahead is closer than one index-stop unit (BANKNIFTY 30, FINNIFTY 15
 *       points: [LiquidityRules.indexStopPoints]) - recorded on each signal as [nearLevel];
 *   (b) drop the FINNIFTY 30-minute book ([finThirty]);
 *   (c) skip in high volatility ([VolFilter], Boss's choice later on 06 Oct, from the big-candle study) - recorded on each
 *       signal as [Trade.volSkip]; judged on the trades entered since it was recorded, at [MIN_TRADES] of them;
 *   (d) exit everything at 14:30 ([EXIT_ALL_AT], from the losing-trades study) - the same trade, sold at 14:30 when still
 *       held then ([Trade.exit1430]: its net after charges, a MARKET sell of the option's price at the first look from 14:30);
 *   (e) 2 strikes in the money ([itm2Strike], from the same study) - the same signal, entry and exit moments, priced on the
 *       option one strike deeper than the trade's ([Trade.itm2]: its net after charges, MARKET fills on its price at the
 *       trade's entry and at its exit). (d) and (e) are judged like (c), on the trades they priced, at [MIN_TRADES];
 *   (f) only a strong close with premium momentum ([strongMomentum], Boss's approval later on 06 Oct, from the "meta"
 *       study) - recorded on each signal as [Trade.strong]: the signal bar closed beyond the swept level by more than
 *       [STRONG_CLOSE] of it (10.4 bp, in the trade's direction) AND over the 5 minutes before the entry the bought option's
 *       premium rose while the opposite right at the same strike fell (data before the entry only). The rule was found
 *       after the study had looked at its TEST period, so it is NOT validated: this is a pre-registered forward test,
 *       judged like (c) on the trades it flagged, at [MIN_TRADES] of them.
 *
 * Counted from [SINCE] (the day Boss switched it back on, paper only), PER LOT: each trade's rupees (and each candidate's)
 * divided by the lots it bought ([Trade.lots], [LiquidityLots]), so changing Liquidity's size never moves the comparison.
 * Pure: no clock, no storage.
 */
object LiquidityShadow {
    val SINCE: LocalDate = LocalDate.of(2026, 10, 6)
    const val MIN_TRADES = 40
    /** Candidate (f)'s close beyond the swept level: more than 10.4 bp of it, in the trade's direction. */
    const val STRONG_CLOSE = 0.00104
    /** Candidate (f)'s premium look-back, in minutes before the entry. */
    const val MOMENTUM_MINUTES = 5L

    /** Candidate (f)'s first half: the signal bar's [close] beyond the swept [level] by more than [STRONG_CLOSE] of it, going [side]. */
    fun strongClose(side: Int, close: Double, level: Double): Boolean = level > 0 && side * (close - level) / level > STRONG_CLOSE

    /**
     * Candidate (f)'s second half, on 1-minute candles that closed by [at] (the entry): the bought option's [leg] last close
     * above its close 5 minutes before, and the opposite right's [opp] below its own; null when either has no candle then.
     */
    fun premiumMomentum(leg: List<Bar>, opp: List<Bar>, at: LocalDateTime): Boolean? {
        fun change(x: List<Bar>): Double? {
            val done = x.filter { !it.start.plusMinutes(1).isAfter(at) && it.start.toLocalDate() == at.toLocalDate() }.sortedBy { it.start }
            val last = done.lastOrNull() ?: return null
            val base = done.lastOrNull { !it.start.isAfter(last.start.minusMinutes(MOMENTUM_MINUTES)) } ?: return null
            return last.close - base.close
        }
        val l = change(leg) ?: return null
        val o = change(opp) ?: return null
        return l > 0 && o < 0
    }

    /** Candidate (f)'s flag: a strong close and premium momentum; null when the momentum could not be read (not counted). */
    fun strongMomentum(side: Int, close: Double, level: Double, leg: List<Bar>, opp: List<Bar>, at: LocalDateTime): Boolean? {
        val m = premiumMomentum(leg, opp, at) ?: return null
        return strongClose(side, close, level) && m
    }

    /** Candidate (d)'s time: everything still held is sold at the first look from 14:30. */
    val EXIT_ALL_AT: LocalTime = LocalTime.of(14, 30)

    /** Candidate (e)'s strike: one step deeper in the money than the trade's [strike] (1 ITM -> 2 ITM) on [underlying]. */
    fun itm2Strike(side: Int, strike: Int, underlying: String): Int = strike - side * LiquidityRules.strikeStep(underlying)

    /** Whether candidate (d) sells a trade still held at [now]. */
    fun exitAllDue(now: LocalDateTime): Boolean = !now.toLocalTime().isBefore(EXIT_ALL_AT)

    /** A bought option's net after charges: [qty] bought at [entry], sold at [exit] (the paper fills, the real charges). */
    fun net(entry: Double, exit: Double, qty: Int): Double = (exit - entry) * qty - ShadowRules.charges(entry, exit, qty)

    /**
     * Candidate (d)'s net for a trade that made [actual] and was sold at [exitTime]: the same when it was sold before 14:31
     * (out by then anyway); else bought at [entry] and sold at [at1430] ([ShadowRules.exitFill] of the price seen from
     * 14:30), or null when that price was never seen (not counted).
     */
    fun exitAllNet(actual: Double, exitTime: LocalDateTime, entry: Double, qty: Int, at1430: Double?): Double? = when {
        exitTime.toLocalTime().isBefore(EXIT_ALL_AT.plusMinutes(1)) -> actual
        at1430 != null -> net(entry, at1430, qty)
        else -> null
    }

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
    data class Trade(val day: LocalDate, val net: Double, val near: Boolean?, val book: String,
                     /** Candidate (c)'s flag as recorded at the signal; null: entered before (c) was recorded (not counted for it). */
                     val volSkip: Boolean? = null,
                     /** Candidate (d)'s net ([exitAllNet]); null: not priced (not counted for it). */
                     val exit1430: Double? = null,
                     /** Candidate (e)'s net on the 2-ITM option; null: not priced (not counted for it). */
                     val itm2: Double? = null,
                     /** Candidate (f)'s flag as recorded at the signal; null: not recorded (not counted for it). */
                     val strong: Boolean? = null,
                     /**
                      * The lots the trade bought ([LiquidityLots]; 1 before the setting existed): [net], [exit1430] and [itm2] are
                      * that many lots' rupees, counted per lot ([perLot]).
                      */
                     val lots: Double = 1.0) {
        /** This trade per lot: its rupees and each candidate's divided by [lots]. */
        fun perLot(): Trade = if (lots == 1.0) this else copy(net = LiquidityLots.perLot(net, lots),
            exit1430 = exit1430?.let { LiquidityLots.perLot(it, lots) }, itm2 = itm2?.let { LiquidityLots.perLot(it, lots) }, lots = 1.0)
    }

    /** A set of trades: how many, and their net. */
    data class Cut(val trades: Int, val net: Double) {
        /** Net a trade, or null with no trades. */
        val perTrade: Double? get() = if (trades == 0) null else net / trades
    }

    /**
     * Every trade since [SINCE] ([all]), and what each candidate would have kept. [untracked]: trades with no (a) flag.
     * Candidate (c): [volAll], the trades with its flag recorded, and [withoutVol], those of them it would have kept.
     */
    data class Summary(val all: Cut, val withoutNear: Cut, val withoutFin30: Cut, val untracked: Int,
                       val volAll: Cut = Cut(0, 0.0), val withoutVol: Cut = Cut(0, 0.0),
                       /** Candidate (d): the trades it priced as they were ([exitAll]) and sold by 14:30 ([at1430]). */
                       val exitAll: Cut = Cut(0, 0.0), val at1430: Cut = Cut(0, 0.0),
                       /** Candidate (e): the trades it priced as they were ([itm1]) and on the 2-ITM option ([itm2]). */
                       val itm1: Cut = Cut(0, 0.0), val itm2: Cut = Cut(0, 0.0),
                       /** Candidate (f): the trades with its flag ([strongAll]) and those it would have kept ([withStrong]). */
                       val strongAll: Cut = Cut(0, 0.0), val withStrong: Cut = Cut(0, 0.0)) {
        val enough: Boolean get() = all.trades >= MIN_TRADES
        /** Candidate (c) has [MIN_TRADES] trades of its own. */
        val volEnough: Boolean get() = volAll.trades >= MIN_TRADES
    }

    fun summarize(trades: List<Trade>): Summary {
        // Per lot: a 2- or 3-lot trade counts as one lot's rupees, so the size Boss trades never moves the comparison.
        val since = trades.filter { !it.day.isBefore(SINCE) }.map { it.perLot() }
        fun cut(x: List<Trade>) = Cut(x.size, x.sumOf { it.net })
        val vol = since.filter { it.volSkip != null }
        val d = since.filter { it.exit1430 != null }
        val e = since.filter { it.itm2 != null }
        return Summary(cut(since), cut(since.filter { it.near != true }), cut(since.filter { !finThirty(it.book) }), since.count { it.near == null },
            cut(vol), cut(vol.filter { it.volSkip == false }), cut(d), Cut(d.size, d.sumOf { it.exit1430!! }), cut(e), Cut(e.size, e.sumOf { it.itm2!! }),
            cut(since.filter { it.strong != null }), cut(since.filter { it.strong == true }))
    }

    /** (d) / (e) on the row: "tracked from its first trade", or its trades and net a trade against the same trades as made. */
    private fun alt(asMade: Cut, alt: Cut) = if (alt.trades == 0) "tracked from its first trade" else "${alt.trades}, ${per(alt)} against ${per(asMade)}"

    private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + String.format(Locale.ENGLISH, "%,.0f", abs(x))
    private fun per(c: Cut) = c.perTrade?.let { rs(it) + " a trade" } ?: "no trades"

    /** The Liquidity row's line, per lot: the trades so far, net, net a trade, and net a trade without each candidate's trades. */
    fun line(s: Summary): String =
        "Since 06 Oct, per lot: ${s.all.trades} paper trade${if (s.all.trades == 1) "" else "s"}, ${rs(s.all.net)} net (${per(s.all)}) · " +
            "(a) skip a level within one index stop: ${s.withoutNear.trades}, ${per(s.withoutNear)} · " +
            "(b) no FINNIFTY 30m: ${s.withoutFin30.trades}, ${per(s.withoutFin30)} · " +
            "(c) skip in high volatility: " + (if (s.volAll.trades == 0) "tracked from its first trade"
                else "${s.withoutVol.trades} of ${s.volAll.trades}, ${per(s.withoutVol)} against ${per(s.volAll)}") +
            " · (d) all out by 14:30: ${alt(s.exitAll, s.at1430)} · (e) 2 strikes in the money: ${alt(s.itm1, s.itm2)}" +
            " · (f) only strong close + momentum: " + (if (s.strongAll.trades == 0) "tracked from its first trade"
                else "${s.withStrong.trades} of ${s.strongAll.trades}, ${per(s.withStrong)} vs ${per(s.strongAll)}") +
            (if (s.untracked > 0) " · ${s.untracked} from before (a) was recorded" else "")

    /** Whether candidate [c] helped against [all]: it kept at least one trade, dropped one, and made more a trade. */
    fun helped(all: Cut, c: Cut): Boolean {
        val a = all.perTrade ?: return false
        val p = c.perTrade ?: return false
        return c.trades < all.trades && p > a
    }

    /**
     * What Jarvis says of the candidates: below [MIN_TRADES] trades since 06 Oct, how many so far and that they are
     * judged at [MIN_TRADES]; from then on, which of (a) and (b) helped (net a trade with and without it), and (c) once it
     * has [MIN_TRADES] trades of its own (until then, how many it has).
     */
    fun verdict(s: Summary): String {
        if (!s.enough) return "Liquidity 15+5 has ${s.all.trades} of $MIN_TRADES paper trades since 06 Oct; its candidate rules " +
            "are judged at $MIN_TRADES. So far: ${line(s)}."
        val a = helped(s.all, s.withoutNear)
        val b = helped(s.all, s.withoutFin30)
        val which = when {
            a && b -> "Both helped"
            a -> "Candidate (a) helped, (b) did not"
            b -> "Candidate (b) helped, (a) did not"
            else -> "Neither helped"
        }
        val c = if (s.volEnough) "(c) skipping signals in high volatility ${if (helped(s.volAll, s.withoutVol)) "helped" else "did not help"}: " +
                "${per(s.withoutVol)} over ${s.withoutVol.trades} against ${per(s.volAll)} over ${s.volAll.trades} since it was added. "
            else "(c) skipping signals in high volatility has ${s.volAll.trades} of $MIN_TRADES trades since it was added; it is judged at $MIN_TRADES. "
        return "$which, Boss. Per lot, Liquidity 15+5 made ${per(s.all)} over ${s.all.trades} paper trades since 06 Oct; " +
            "(a) skipping signals with the next level within one index stop: ${per(s.withoutNear)} over ${s.withoutNear.trades}; " +
            "(b) without FINNIFTY 30m: ${per(s.withoutFin30)} over ${s.withoutFin30.trades}. $c" +
            same("(d) selling everything at 14:30", s.exitAll, s.at1430) + same("(e) buying 2 strikes in the money", s.itm1, s.itm2) +
            (if (s.strongAll.trades < MIN_TRADES) "(f) only a strong close with premium momentum (a forward test) has ${s.strongAll.trades} of " +
                "$MIN_TRADES trades since it was added; it is judged at $MIN_TRADES. "
            else "(f) only a strong close with premium momentum (a forward test) ${if (helped(s.strongAll, s.withStrong)) "helped" else "did not help"}: " +
                "${per(s.withStrong)} over ${s.withStrong.trades} against ${per(s.strongAll)} over ${s.strongAll.trades} since it was added. ") +
            "Nothing changes by itself: adopting one is yours."
    }

    /** Whether the same trades made more a trade under a candidate ([alt]) than as made ([asMade]). */
    fun better(asMade: Cut, alt: Cut): Boolean {
        val a = asMade.perTrade ?: return false
        val c = alt.perTrade ?: return false
        return c > a
    }

    /** Jarvis on (d) or (e): judged at [MIN_TRADES] priced trades of its own; until then, how many it has. */
    private fun same(what: String, asMade: Cut, alt: Cut): String =
        if (alt.trades < MIN_TRADES) "$what has ${alt.trades} of $MIN_TRADES trades priced; it is judged at $MIN_TRADES. "
        else "$what ${if (better(asMade, alt)) "helped" else "did not help"}: ${per(alt)} against ${per(asMade)} over the same ${alt.trades} trades. "
}
