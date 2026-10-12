package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Liquidity 15+5 has priority over the ORB arms (Boss's 07 Oct decision, research/HUNT_H21.md): ORB, ORB Fresh, ORB Sweep and
 * Range Fade keep running on paper, but under the one-index guard (at most one automatic position an index a side, none
 * against another) they took Liquidity's place on BANKNIFTY - 31-68 Liquidity trades in the holdout, worth +Rs 7.7k-18k.
 *
 * The rule, the same in the app's guard ([com.optionslab.ira.AutoSide]) and in the replays ([guard], [ArmsBacktest.guarded]):
 *  - a Liquidity entry is never refused because an ORB-family arm ([Rank.OLD_ARM]) holds a position on the same index: it
 *    may run beside it;
 *  - an ORB-family entry is still refused while Liquidity (or anything else) holds that index: the old arms keep the guard;
 *  - live safety is kept: when both the Liquidity entry and the old arm's holding are live (real money at the broker), the
 *    guard applies as before - never two automatic live positions on one index. Every live gate (PIN, the account guard's
 *    limits, the kill switch) is untouched; this only decides which holdings the one-index rule counts.
 *
 * Every other trader (Hero, Pine, Strategies, Solo, Jarvis) is [Rank.OTHER]. Since 12 Oct (Boss: Liquidity BANKNIFTY, the
 * go-live candidate, gets every signal it makes) Liquidity's BANKNIFTY books also rank above them on BANKNIFTY, and its 15-
 * and 5-minute books no longer block each other (the 7-argument [counts], [yielded]); other indices are as before.
 * Pure: no clock, no network, no orders.
 */
object ArmPriority {
    /** What the activity log and the why-not say. */
    const val WORDS = "Liquidity has priority over ORB arms"

    enum class Rank { OTHER, OLD_ARM, LIQUIDITY }

    /** The ORB family: the arms that give way to Liquidity. */
    val OLD_ARMS: List<Arm> = OrbRules.ARMS + SweepRules.ARM + RangeFadeRules.ARM

    /** An arm's rank: Liquidity (any book), the ORB family, or anything else (Hero). */
    fun rankOf(arm: Arm): Rank = when {
        arm.liquidity -> Rank.LIQUIDITY
        OLD_ARMS.any { it.source == arm.source } -> Rank.OLD_ARM
        else -> Rank.OTHER
    }

    /** The rank of a trader acting for [arms] (a re-armed shadow variant): the ORB family only when every arm is one. */
    fun rankOf(arms: List<Arm>): Rank = when {
        arms.isEmpty() -> Rank.OTHER
        arms.all { rankOf(it) == Rank.LIQUIDITY } -> Rank.LIQUIDITY
        arms.all { rankOf(it) == Rank.OLD_ARM } -> Rank.OLD_ARM
        else -> Rank.OTHER
    }

    /**
     * Whether a holding ([holder], live at the broker when [holderLive]) counts against an entry ([entrant], [entrantLive])
     * on the same index: always, except a Liquidity entry against an ORB-family holding (unless both are live).
     */
    fun counts(holder: Rank, holderLive: Boolean, entrant: Rank, entrantLive: Boolean): Boolean =
        !(entrant == Rank.LIQUIDITY && holder == Rank.OLD_ARM && !(holderLive && entrantLive))

    /** Whether a refusal of an [entrant] by a [holder] is Liquidity's priority at work (said with [WORDS]). */
    fun byPriority(holder: Rank, entrant: Rank): Boolean = holder == Rank.LIQUIDITY && entrant == Rank.OLD_ARM

    // ---- Liquidity BANKNIFTY gets every signal it makes (Boss, 12 Oct; research X1 and the strategy scorecard) ------------

    /** The index whose Liquidity books have priority over every other automatic trader. */
    const val BN = "BANKNIFTY"

    /** Liquidity's BANKNIFTY books (15-minute and 5-minute; the one switch's own source too): each its own claim on the index. */
    val BN_BOOKS: Set<String> = setOf(LiquidityRules.ARM15.source, LiquidityRules.ARM5.source, LiquidityRules.ARM.source)

    /** What a trader refused for Liquidity BANKNIFTY's sake says. */
    const val YIELD_WORDS = "yielded to Liquidity BANKNIFTY"

    /** Whether a trader of [rank] acting for [book] on [underlying] is one of Liquidity's BANKNIFTY books. */
    fun liquidityBn(rank: Rank, book: String?, underlying: String): Boolean =
        rank == Rank.LIQUIDITY && book != null && book in BN_BOOKS && underlying.equals(BN, ignoreCase = true)

    /**
     * [counts] with the books and the index known (12 Oct): Liquidity BANKNIFTY's entry ([entrantBook] one of [BN_BOOKS]) is
     *  - never refused by its OTHER BANKNIFTY book's position: the 15- and 5-minute books each hold their own (research X1:
     *    about +16-18k a year; each book is 1 lot and every account-wide guard still applies);
     *  - never refused by another automatic trader's position (Solo, VIX divergence, Pine, Jarvis's trades, the Strategies,
     *    the forge's ideas, Hero: [Rank.OTHER]; the ORB family; a re-armed shadow) - it goes beside it, and that trader's later entry is the one refused
     *    ([yielded]). When both the holding and the entry are live the old guard holds (never two live automatic positions of
     *    different traders on one index); the app keeps that case from arising by refusing the other traders' live BANKNIFTY
     *    entries while Liquidity BANKNIFTY is armed live - which only ever removes entries.
     * Every other case is [counts] as before.
     */
    fun counts(holder: Rank, holderLive: Boolean, entrant: Rank, entrantLive: Boolean, underlying: String,
               holderBook: String?, entrantBook: String?): Boolean {
        if (liquidityBn(entrant, entrantBook, underlying)) {
            if (liquidityBn(holder, holderBook, underlying)) return holderBook == entrantBook
            // Any other holding (an ORB arm, Solo, Pine, a re-armed shadow, ...) counts only when both are live.
            return holderLive && entrantLive
        }
        return counts(holder, holderLive, entrant, entrantLive)
    }

    /** Whether a refusal of an entry ([entrant], [entrantBook]) by a holding ([holder], [holderBook]) is the other trader yielding to Liquidity BANKNIFTY. */
    fun yielded(holder: Rank, holderBook: String?, entrant: Rank, entrantBook: String?, underlying: String): Boolean =
        liquidityBn(holder, holderBook, underlying) && !liquidityBn(entrant, entrantBook, underlying) && !byPriority(holder, entrant)

    /**
     * One automatic trade in a replay: its [who] (an arm's source or a label), [rank], index, lean ([side]: +1 call, -1 put),
     * entry and exit time ([exit] null: held to the day's end), and whether it was [live].
     */
    data class Leg(val who: String, val rank: Rank, val underlying: String, val side: Int, val entry: LocalDateTime,
                   val exit: LocalDateTime?, val live: Boolean = false)

    /** A leg the guard refused, and the leg holding the index then ([by]). */
    data class Refused(val leg: Leg, val by: Leg) {
        /** In words: "orb refused: liquidity15 holds BANKNIFTY (Liquidity has priority over ORB arms)". */
        val words: String get() = "${leg.who} refused: ${by.who} holds ${by.underlying}" +
            when {
                byPriority(by.rank, leg.rank) -> " ($WORDS)"
                yielded(by.rank, by.who, leg.rank, leg.who, by.underlying) -> " ($YIELD_WORDS)"
                else -> " (one automatic position an index a side)"
            }
    }

    data class Outcome(val taken: List<Leg>, val refused: List<Refused>)

    /**
     * The app's one-index guard with Liquidity's priority, replayed: [legs] in entry order (at one minute, Liquidity first -
     * it has priority), each taken unless a taken leg on the same index, leaning either way, is still open at its entry and
     * [counts] against it. A neutral leg (side 0) is never refused and never refuses.
     */
    fun guard(legs: List<Leg>): Outcome {
        val taken = ArrayList<Leg>()
        val refused = ArrayList<Refused>()
        val order = legs.sortedWith(compareBy<Leg> { it.entry }.thenBy { if (it.rank == Rank.LIQUIDITY) 0 else 1 })
        for (l in order) {
            val by = if (l.side == 0) null else taken.firstOrNull { h ->
                h.side != 0 && h.underlying.equals(l.underlying, ignoreCase = true) && (h.exit == null || h.exit.isAfter(l.entry)) &&
                    counts(h.rank, h.live, l.rank, l.live, l.underlying, h.who, l.who)
            }
            if (by == null) taken += l else refused += Refused(l, by)
        }
        return Outcome(taken, refused)
    }

    /**
     * The evening replay under the same rule: [arm]'s replayed [trades] on [day] (bought at the open of the bar after the
     * signal bar, [barMinutes] long; sold at the exit bar) against the day's [liquidity] trades on the arm's index. Returns,
     * by the trade's place in [trades], the words for each the app would have refused ([Refused.words]).
     */
    fun refusedInReplay(arm: Arm, day: LocalDate, trades: List<ReplayTrade>, liquidity: List<Leg>, barMinutes: Long = 5): Map<Int, String> {
        val legs = trades.map { t ->
            Leg(arm.source, rankOf(arm), OrbRules.UNDERLYING, if (t.right == "CE") 1 else -1,
                day.atTime(LocalTime.parse(t.signalBar)).plusMinutes(barMinutes), day.atTime(LocalTime.parse(t.exitBar)))
        }
        val refused = guard(legs + liquidity).refused
        return legs.withIndex().mapNotNull { (i, l) -> refused.firstOrNull { it.leg === l }?.let { i to it.words } }.toMap()
    }
}
