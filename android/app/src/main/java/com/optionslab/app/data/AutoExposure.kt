package com.optionslab.app.data

import com.optionslab.ira.AutoSide

/**
 * Every automatically opened position open now, across the automatic traders ([AutoSide], Boss's 06 Oct rule: no
 * automatic trade against another on one index, at most one automatic position an index a side). Each trader keeps a
 * lock-free copy of its open positions as of its last load or save, and is read here without taking its lock (the
 * traders call this while holding their own: taking another's could deadlock). A trader asking passes its own open
 * positions fresh from its book (they may be newer than its copy) and is left out of the copies here ([except]).
 * Reads only: nothing here places, changes or closes anything.
 */
object AutoExposure {
    enum class Source { ORB, PINE, STRATEGIES, SOLO, JARVIS }

    /** The open automatic positions of every trader but [except], each as its last load or save left them. */
    fun others(except: Source): List<AutoSide.Held> {
        val out = ArrayList<AutoSide.Held>()
        if (except != Source.ORB) out += runCatching { OrbArms.exposureHint }.getOrDefault(emptyList())
        // A shadow re-armed on paper on Boss's yes trades as an arm: its paper positions count with the arms' (virtual ones never do).
        if (except != Source.ORB) out += runCatching { ShadowArms.exposureHint }.getOrDefault(emptyList())
        if (except != Source.PINE) out += runCatching { PineAuto.exposure() }.getOrDefault(emptyList())
        if (except != Source.STRATEGIES) out += runCatching { Strategies.exposureHint }.getOrDefault(emptyList())
        if (except != Source.SOLO) out += runCatching { com.optionslab.app.ira.IraSolo.exposure() }.getOrDefault(emptyList())
        if (except != Source.JARVIS) out += runCatching { com.optionslab.app.ira.IraNewsTrades.exposure() }.getOrDefault(emptyList())
        return out
    }

    /**
     * Whether an automatic entry by [source] on [underlying] leaning [direction] may go ([AutoSide.check]): null when it
     * may, else "opposite_position_open: <who> holds <symbol>" or "same_side_already_held: ...". [own]: the asker's own
     * open positions, fresh from its book. [rank] and [live]: the asker's place in Liquidity's priority over the ORB arms
     * (Boss's 07 Oct decision, [com.optionslab.engine.orb.ArmPriority]) and whether the entry goes to the broker; every
     * other trader leaves them as they are.
     *
     * The account day lock first (08 Oct, [DayLockGuard]): once [account]'s day P&L reached the Bot settings amount, no new
     * automatic entry goes in it today - "Day lock (paper): reached +Rs 8,000 at 14:11, no new entries today (...)".
     * [account]: the account the entry goes to (true Zerodha, false paper; null when the asker cannot tell yet: either
     * account's lock refuses it); by default the one [live] names.
     */
    fun check(source: Source, underlying: String, direction: Int, own: List<AutoSide.Held>,
              rank: com.optionslab.engine.orb.ArmPriority.Rank = com.optionslab.engine.orb.ArmPriority.Rank.OTHER,
              live: Boolean = false, account: Boolean? = live): String? =
        DayLockGuard.refusal(account) ?: AutoSide.check(underlying, direction, own + others(source), rank, live)

    /** The activity log's word when a Liquidity entry goes beside an ORB arm's position ([AutoSide.priorityNote]); null when none. */
    fun priorityNote(source: Source, who: String, underlying: String, direction: Int, own: List<AutoSide.Held>,
                     rank: com.optionslab.engine.orb.ArmPriority.Rank, live: Boolean = false): String? =
        AutoSide.priorityNote(who, underlying, direction, own + others(source), rank, live)

    /** All of them (Boss's own order review warns against these: [AutoSide.warn]). */
    fun all(): List<AutoSide.Held> = others(Source.ORB) + runCatching { OrbArms.exposureHint }.getOrDefault(emptyList()) +
        runCatching { ShadowArms.exposureHint }.getOrDefault(emptyList())

    /**
     * Boss's order (never refused): a word for its review when an automatic position on the same index leans the other way,
     * for each opening option leg ([symbol], [buy]); null when none does.
     */
    fun warnFor(legs: List<Pair<String, Boolean>>): String? {
        val held = all()
        if (held.isEmpty()) return null
        return legs.mapNotNull { (symbol, buy) ->
            val u = AutoSide.underlyingOf(symbol) ?: return@mapNotNull null
            val r = AutoSide.rightOf(symbol) ?: return@mapNotNull null
            AutoSide.warn(u, AutoSide.direction(r, buy), AutoSide.orderWords(r, buy), held)
        }.distinct().takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}
