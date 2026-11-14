package com.optionslab.engine.orb

import kotlin.math.ceil

/**
 * 14 Nov 2026 (Boss: "Okay, now just make a change in liquidity, r51, r72. Make it 2k profit and 1k loss fix, dont break any
 * thing"): a fixed rupee target and stop on three arms' trades - Liquidity 15+5 (every book and index), R51 Donchian 20 and R72
 * Breadth Donchian - on paper and live. A trade is sold once its open P&L reaches +₹2,000 ([TARGET_RS]) or -₹1,000 ([STOP_RS]) for
 * the whole position (all its lots together), measured as Kite shows it: (option price - entry fill) x quantity, before charges.
 *
 * Every exit the arm already has stays (its -15% premium stop, ladders and locks, L25, time stops, index exits, square-offs,
 * R151's pickers): whichever comes first wins. The ₹1,000 stop only applies when it is TIGHTER than the arm's own stop
 * ([tighterStop]: it never loosens a stop) and the ₹2,000 target is an earlier exit, so both only lower risk (CLAUDE.md's LIVE rule:
 * "automatic changes may only LOWER risk ... earlier exit").
 *
 * FAILED GATES, Boss's exception (CLAUDE.md, the fifth): R152 (research/hunt/r152_donchian_2k) found a +₹2k / -₹2k pair cut R72 from
 * ₹1.71 lakh to -₹40k and R51 from ₹1.35 lakh to -₹91k at 1 lot (Apr - Oct 2026), and R105C / R106 / R153 found small fixed targets and
 * stops hurt these arms. Labelled [STATUS] wherever it is shown, logged in the Autopilot change timeline, one tap off.
 *
 * Signal source (CLAUDE.md): the arms' entry signals stay on the index; only the exit is on the option's premium (allowed: "stops,
 * targets, trails and locks may be set on the option premium"). Pure: no I/O, no clock.
 */
object FixedRupee {
    /** The profit the whole position is sold at, rupees before charges. */
    const val TARGET_RS = 2_000.0
    /** The loss the whole position is sold at, rupees before charges. */
    const val STOP_RS = 1_000.0

    const val STATUS = "failed gates, Boss's exception"
    /** How the switch and the arms' rows name it. */
    const val LABEL = "₹2k target / ₹1k stop (failed gates, Boss's exception)"
    /**
     * The diagnostics' name for it: no rupee sign and no grouped figure (the uploaded diagnostics hide every rupee figure, and their
     * tests refuse "₹" / "Rs" and "2,000"-style numbers in them).
     */
    const val DIAG = "fixed 2k target / 1k stop (failed gates, Boss's exception)"
    /** Bot settings' switch. */
    const val SWITCH = "Fixed ₹2k target / ₹1k stop (Liquidity, R51, R72) — failed gates, Boss's exception"
    /** An exit by the target, as the trade record and the diagnostics say it. */
    const val WHY_TARGET = "₹2k target"
    /** An exit by the stop, as the trade record and the diagnostics say it. */
    const val WHY_STOP = "₹1k stop"
    /** The rule in one line. */
    const val RULE = "each trade of Liquidity 15+5, R51 Donchian 20 and R72 Breadth Donchian is sold at +₹2,000 or -₹1,000 open P&L for " +
        "the whole position ((price - fill) x quantity, before charges), paper and live; the arm's own exits stay, the first one wins; the " +
        "₹1,000 stop only where it is tighter than the arm's own stop"
    /** R152's record, with its sample. */
    const val RECORD = "R152 (Apr - Oct 2026, 1 lot): a +₹2k / -₹2k pair cut R72 from ₹1.71 lakh to -₹40k and R51 from ₹1.35 lakh to " +
        "-₹91k; R105C, R106 and R153 found small fixed targets and stops hurt these arms"

    private const val EPS = 1e-9

    /** The arm sources it covers: R51, R72 and every Liquidity 15+5 book (its one switch's source too). Nothing else (MCX never). */
    val SOURCES: Set<String> = setOf(DonchianArm.SOURCE, BreadthDonchianArm.SOURCE, LiquidityRules.ARM.source) + LiquidityRules.BOOKS.map { it.source }

    /** Whether the arm [source] is one of the three. */
    fun covers(source: String): Boolean = source in SOURCES

    /**
     * The premium a long position of [qty] units filled at [entry] is stopped at: entry - ₹1,000 / qty, rounded UP to the [tick]
     * (the safe side: the loss at the level is ₹1,000 or a little less). Null when nothing can be told (no fill price, no quantity,
     * no tick) or the level would be one tick or less (no premium to rest a stop at).
     */
    fun stopLevel(entry: Double, qty: Int, tick: Double = TICK): Double? {
        if (!usable(entry, qty, tick)) return null
        val lv = up(entry - STOP_RS / qty, tick)
        return lv.takeIf { it > tick + EPS }
    }

    /**
     * The premium a long position of [qty] units filled at [entry] is sold at for the target: entry + ₹2,000 / qty, rounded UP to the
     * [tick] (the profit at the level is ₹2,000 or a little more). Null when nothing can be told.
     */
    fun targetLevel(entry: Double, qty: Int, tick: Double = TICK): Double? {
        if (!usable(entry, qty, tick)) return null
        return up(entry + TARGET_RS / qty, tick)
    }

    /** The NSE options' tick. */
    const val TICK = 0.05

    /** The stop that applies: the higher (tighter, for a long) of the arm's [own] and the [fixed] ₹1,000 one; never a looser one. */
    fun tighterStop(own: Double?, fixed: Double?): Double? = when {
        fixed == null -> own
        own == null -> fixed
        else -> maxOf(own, fixed)
    }

    /** Whether the stop that applies is the ₹1,000 one: there is one and it is strictly tighter than the arm's [own] (none counts as looser). */
    fun fixedIsTighter(own: Double?, fixed: Double?): Boolean = fixed != null && (own == null || fixed > own + EPS)

    /** Whether the price now [ltp] (null or not a price: never) has reached the [target] (null: none). */
    fun targetHit(ltp: Double?, target: Double?): Boolean = target != null && ltp != null && ltp.isFinite() && ltp > 0 && ltp >= target - EPS

    /** Whether the price now [ltp] (null or not a price: never) is at or under the [stop] (null: none). */
    fun stopHit(ltp: Double?, stop: Double?): Boolean = stop != null && ltp != null && ltp.isFinite() && ltp > 0 && ltp <= stop + EPS

    /**
     * Whether a resting stop at [trigger] is the ₹1,000 stop of a position of [qty] filled at [entry] (within half a [tick]): its exit is
     * then booked as [WHY_STOP]. False with no trigger or no level.
     */
    fun isFixedStop(trigger: Double?, entry: Double, qty: Int, tick: Double = TICK): Boolean {
        val lv = stopLevel(entry, qty, tick) ?: return false
        return trigger != null && kotlin.math.abs(trigger - lv) < tick / 2
    }

    private fun usable(entry: Double, qty: Int, tick: Double): Boolean = entry.isFinite() && entry > 0 && qty > 0 && tick.isFinite() && tick > 0

    /** [x] up to the next [tick] (a value already on a tick stays), kept to the tick's decimals. */
    private fun up(x: Double, tick: Double): Double {
        val n = ceil(x / tick - 1e-6)
        return java.math.BigDecimal.valueOf(tick).multiply(java.math.BigDecimal.valueOf(n.toLong())).toDouble()
    }
}
