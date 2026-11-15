package com.optionslab.engine.orb

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * 15 Nov 2026 (Boss: "if the profit moves to 1k, make stop loss 500, if the profit moves to 1.5k move stop loss to 1000, keep a 500 rs
 * gap on every 500 rs gain. And when a trade is bought the stop loss should be only 500 rs, make this change to liquidity and donchian
 * r51 and r72"; the amounts are PER LOT): a ₹500 step trail on three arms' trades - Liquidity 15+5 (every book and index), R51 Donchian
 * 20 and R72 Breadth Donchian - on paper and live.
 *
 * With L the position's lots and P&L = (price - fill) x quantity before charges (as Kite shows it):
 *  - at the entry the stop is at -₹500 x L, only where that is tighter than the arm's own stop (it never loosens one);
 *  - once the trade's best open P&L has reached +₹1,000 x L, the stop is at +₹500 x L, and for every further ₹500 x L of best P&L it
 *    rises ₹500 x L: stop = (floor(best / 500L) - 1) x 500L. It only ever moves up. No fixed target.
 * Per unit that is a distance of ₹500 / lot size whatever the lots, so the levels need only the fill, the best price and the lot size.
 * Levels are rounded UP to the tick (the safe side: a little more is protected). Every exit the arm already has stays (its -15% stop,
 * ladders and locks, L25, time stops, index exits, square-offs): whichever comes first wins. It only lowers risk (a tighter stop, an
 * earlier exit), so it acts on live through every existing live gate (CLAUDE.md's LIVE rule).
 *
 * NOT TESTED, Boss's exception (CLAUDE.md, the fifth): no research is behind these levels, and earlier studies point the other way -
 * R152, R167 and R172 found tight rupee stops and early profit locks hurt these arms. Labelled [STATUS] wherever it is shown, logged in
 * the Autopilot change timeline, one tap off. Signal source: the arms' entries stay on the index; only the stop is on the premium
 * (CLAUDE.md allows stops and locks on the option premium). Pure: no I/O, no clock.
 */
object StepTrail {
    /** The stop at the entry, and each step, rupees a lot. */
    const val STEP_RS = 500.0
    /** The best P&L a lot at which the trail starts (its first stop: +[STEP_RS] a lot). */
    const val START_RS = 1_000.0

    const val STATUS = "not tested, Boss's exception"
    /** How the arms' rows and the trades name it. */
    const val LABEL = "₹500 step trail per lot (not tested, Boss's exception)"
    /** Bot settings' switch. */
    const val SWITCH = "₹500 step trail per lot (Liquidity, R51, R72) — not tested, Boss's exception"
    /** The diagnostics' name: no rupee sign and no grouped figure (the uploaded diagnostics hide every rupee figure). */
    const val DIAG = "500 step trail per lot (not tested, Boss's exception)"
    /** An exit by the stop set at the entry, as the trade record and the diagnostics say it. */
    const val WHY_STOP = "₹500 stop"
    /** An exit by a stop the trail raised. */
    const val WHY_TRAIL = "step trail"
    /** The rule in one line. */
    const val RULE = "each trade of Liquidity 15+5, R51 Donchian 20 and R72 Breadth Donchian starts with its stop at -₹500 a lot (only where " +
        "that is tighter than the arm's own stop); once its best open P&L reaches +₹1,000 a lot the stop moves to +₹500 a lot, and up ₹500 a " +
        "lot for every further ₹500 a lot of best P&L, never down; the arm's own exits stay and the first one wins; paper and live"
    /** The honest record. */
    const val RECORD = "no research is behind these levels; R152, R167 and R172 found tight rupee stops and early profit locks hurt these arms"

    private const val EPS = 1e-9

    /** The NSE options' tick. */
    const val TICK = 0.05

    /** The arm sources it covers: R51, R72 and every Liquidity 15+5 book (and its one switch's source). Nothing else (MCX never). */
    val SOURCES: Set<String> = setOf(DonchianArm.SOURCE, BreadthDonchianArm.SOURCE, LiquidityRules.ARM.source) + LiquidityRules.BOOKS.map { it.source }

    /** Whether the arm [source] is one of the three. */
    fun covers(source: String): Boolean = source in SOURCES

    /**
     * The premium a long position filled at [entry] is stopped at on its entry: entry - ₹500 / [lot] (₹500 a lot), rounded UP to the [tick].
     * Null when nothing can be told (no fill, no lot size, no tick) or the level would be one tick or less.
     */
    fun stopLevel(entry: Double, lot: Int, tick: Double = TICK): Double? {
        if (!usable(entry, lot, tick)) return null
        return up(entry - STEP_RS / lot, tick).takeIf { it > tick + EPS }
    }

    /**
     * The stop P&L a lot the trail holds for a best open P&L of [bestPerLot] a lot: null under +₹1,000; else (floor(best / 500) - 1) x 500
     * (+₹500 at +₹1,000, +₹1,000 at +₹1,500, ...).
     */
    fun stepPnl(bestPerLot: Double): Double? {
        if (!bestPerLot.isFinite() || bestPerLot < START_RS - EPS) return null
        return (floor(bestPerLot / STEP_RS + 1e-9) - 1) * STEP_RS
    }

    /** The premium the trail holds for a position filled at [entry] whose best price so far is [best]: null before +₹1,000 a lot. */
    fun trailLevel(entry: Double, lot: Int, best: Double?, tick: Double = TICK): Double? {
        if (!usable(entry, lot, tick) || best == null || !best.isFinite()) return null
        val pnl = stepPnl((best - entry) * lot) ?: return null
        return up(entry + pnl / lot, tick)
    }

    /** The rule's stop now: the higher of the entry's -₹500 a lot and the trail's (null: neither). */
    fun levelOf(entry: Double, lot: Int, best: Double?, tick: Double = TICK): Double? =
        listOfNotNull(stopLevel(entry, lot, tick), trailLevel(entry, lot, best, tick)).maxOrNull()

    /** The stop that applies: the higher (tighter, for a long) of the arm's [own] and the rule's [rule]; never a looser one. */
    fun tighterStop(own: Double?, rule: Double?): Double? = when {
        rule == null -> own
        own == null -> rule
        else -> maxOf(own, rule)
    }

    /** Whether the rule's stop [rule] is strictly tighter than the arm's [own] (none counts as looser). */
    fun isTighter(own: Double?, rule: Double?): Boolean = rule != null && (own == null || rule > own + EPS)

    /** Whether the price now [ltp] (null or not a price: never) is at or under [stop] (null: none). */
    fun stopHit(ltp: Double?, stop: Double?): Boolean = stop != null && ltp != null && ltp.isFinite() && ltp > 0 && ltp <= stop + EPS

    /**
     * What a stop resting at [trigger] is, for a position filled at [entry]: [WHY_STOP] at the entry's -₹500 a lot, [WHY_TRAIL] at one of
     * the trail's steps (+₹500, +₹1,000, ... a lot), within half a [tick]; null: neither (the arm's own stop or lock).
     */
    fun whyAt(trigger: Double?, entry: Double, lot: Int, tick: Double = TICK): String? {
        if (trigger == null || !usable(entry, lot, tick)) return null
        stopLevel(entry, lot, tick)?.let { if (abs(trigger - it) < tick / 2) return WHY_STOP }
        val k = ((trigger - entry) * lot / STEP_RS).roundToLong()
        if (k < 1) return null
        return WHY_TRAIL.takeIf { abs(trigger - up(entry + k * STEP_RS / lot, tick)) < tick / 2 }
    }

    /** An exit reason as a fill card or a line shows it: the rule's own two ([WHY_STOP], [WHY_TRAIL]) with [STATUS] after them; any other as it is. */
    fun words(why: String): String = if (why == WHY_STOP || why == WHY_TRAIL) "$why ($STATUS)" else why

    private fun usable(entry: Double, lot: Int, tick: Double): Boolean = entry.isFinite() && entry > 0 && lot > 0 && tick.isFinite() && tick > 0

    /** [x] up to the next [tick] (a value already on a tick stays), kept to the tick's decimals. */
    private fun up(x: Double, tick: Double): Double {
        val n = ceil(x / tick - 1e-6)
        return java.math.BigDecimal.valueOf(tick).multiply(java.math.BigDecimal.valueOf(n.toLong())).toDouble()
    }
}
