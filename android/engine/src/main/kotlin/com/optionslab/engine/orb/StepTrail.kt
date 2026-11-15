package com.optionslab.engine.orb

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Boss's exit rule on three arms' trades - Liquidity 15+5 (every book and index), R51 Donchian 20 and R72 Breadth Donchian - on paper
 * and live. (The object keeps its first name, StepTrail, so every caller, the persisted flag and the switch carry over.)
 *
 * 15 Nov 2026, third version (Boss, after the whole R176 - R179 table: "Switch to + picker + breakeven at +10% (V4)"), replacing the
 * ₹500 step trail of the morning (and the ₹1,500 stop a lot + breakeven at +5% that never left its branch):
 *  - no rupee stop at the entry: each arm keeps its own stop (Liquidity and R51 / R72's -15%) - [stopLevel] is always null;
 *  - once the option has traded 10% above the fill, the stop moves to breakeven: the fill plus the round trip's charges
 *    ([ProfitLock.roundTripPerUnit] for one lot), so a sale there nets about ₹0. It only ever moves up. No fixed target.
 *  - R72 / R51's R151 picker (CLAUDE.md's fourth exception) stays as it is: V4 is the picker plus this breakeven.
 * Per unit the distance depends only on the fill and the lot size, so the level is the same whatever the lots. Levels are rounded UP to
 * the tick (the safe side). Every exit the arm already has stays (its -15% stop, ladders and locks, L25, time stops, index exits,
 * square-offs): whichever comes first wins. A stop order fills at the market, so a fast fall can sell under breakeven. It only lowers risk
 * (a higher stop, an earlier exit), so it acts on live through every existing live gate (CLAUDE.md's LIVE rule).
 *
 * FAILED GATES, Boss's exception (CLAUDE.md, the fifth): R179's V4 replayed it on one year ([RECORD]) and it failed the gates (its twin
 * q 1.00; R72's final win rate 53.5%). Labelled [STATUS] wherever it is shown, logged in the Autopilot change timeline, one tap off.
 * Signal source: the arms' entries stay on the index; only the stop is on the premium (CLAUDE.md allows stops and locks on the option
 * premium). Pure: no I/O, no clock.
 */
object StepTrail {
    /** The gain above the fill, percent, at which the stop moves to breakeven. */
    const val BREAKEVEN_PCT = 10.0

    const val STATUS = "failed gates, Boss's exception"
    /** How the arms' rows and the trades name it. */
    const val LABEL = "breakeven at +10 percent (failed gates, Boss's exception)"
    /** Bot settings' switch. */
    const val SWITCH = "Breakeven at +10 percent (Liquidity, R51, R72) — failed gates, Boss's exception"
    /** The diagnostics' name: no rupee sign and no grouped figure (the uploaded diagnostics hide every rupee figure). */
    const val DIAG = "breakeven at 10 percent (failed gates, Boss's exception)"
    /** An exit by the breakeven stop, as the trade record and the diagnostics say it. */
    const val WHY_TRAIL = "breakeven lock"
    /**
     * The entry's rupee stop: this version has none (each arm's own stop applies), so nothing new is booked with it; the morning's first
     * version (₹500 a lot) wrote it, and its older trade records still carry it.
     */
    const val WHY_STOP = "₹500 stop"
    /** The first version's trail word (15 Nov morning): older trade records still carry it. */
    const val OLD_WHY_TRAIL = "step trail"
    /** The rule in one line. */
    const val RULE = "each trade of Liquidity 15+5, R51 Donchian 20 and R72 Breadth Donchian keeps its arm's own stop; once the option is 10% " +
        "above the fill the stop moves to breakeven (the fill plus the charges), never down; R72 / R51 keep the R151 picker; the arm's own " +
        "exits stay and the first one wins; paper and live"
    /** The honest record. */
    const val RECORD = "R179's V4 (the R151 picker plus a breakeven at +10%) on one year (20 Oct 2025 - 19 Oct 2026, 1 lot): R72 won 56.6% and " +
        "made ₹3.25 L (the picker alone 51.9%, ₹3.72 L), R51 55.5% and ₹2.50 L (50.8%, ₹2.93 L), Liquidity 41.3% and ₹1.00 L (its own rules " +
        "39.2%, ₹1.18 L); it failed the gates (twin q 1.00; R72's final win rate 53.5%)"

    private const val EPS = 1e-9

    /** The NSE options' tick. */
    const val TICK = 0.05

    /** The arm sources it covers: R51, R72 and every Liquidity 15+5 book (and its one switch's source). Nothing else (MCX never). */
    val SOURCES: Set<String> = setOf(DonchianArm.SOURCE, BreadthDonchianArm.SOURCE, LiquidityRules.ARM.source) + LiquidityRules.BOOKS.map { it.source }

    /** Whether the arm [source] is one of the three. */
    fun covers(source: String): Boolean = source in SOURCES

    /** The round trip's charges per unit for one lot of [lot] units bought at [entry] (the engine's F&O schedule). */
    fun chargesPerUnit(entry: Double, lot: Int): Double = ProfitLock.roundTripPerUnit(entry, lot).takeIf { it.isFinite() && it >= 0 } ?: 0.0

    /**
     * The rule's own stop at the entry for a long position filled at [entry]: none in this version (each arm keeps its own stop), so always
     * null. Kept so the callers (and [tighterStop]) carry over unchanged: with null, the arm's own stop is the one that applies.
     */
    @Suppress("UNUSED_PARAMETER")
    fun stopLevel(entry: Double, lot: Int, tick: Double = TICK): Double? = null

    /** The price at which the stop moves to breakeven: [BREAKEVEN_PCT] above the fill. */
    fun breakevenTrigger(entry: Double): Double = entry * (1 + BREAKEVEN_PCT / 100)

    /** The breakeven stop: the fill plus the round trip's charges a unit, rounded UP to the [tick] (null: nothing can be told). */
    fun breakevenLevel(entry: Double, lot: Int, tick: Double = TICK): Double? =
        if (!usable(entry, lot, tick)) null else up(entry + chargesPerUnit(entry, lot), tick)

    /**
     * The premium the breakeven holds for a position filled at [entry] whose best price so far is [best]: null until the best has reached
     * [breakevenTrigger] (or when the breakeven would not sit under the best, which would sell at once).
     */
    fun trailLevel(entry: Double, lot: Int, best: Double?, tick: Double = TICK): Double? {
        if (!usable(entry, lot, tick) || best == null || !best.isFinite() || best < breakevenTrigger(entry) - EPS) return null
        return breakevenLevel(entry, lot, tick)?.takeIf { it < best - EPS }
    }

    /** The rule's stop now: the breakeven once earned (null: not yet; the rule sets no entry stop, [stopLevel]). */
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
     * What a stop resting at [trigger] is, for a position filled at [entry]: [WHY_TRAIL] at the breakeven, within half a [tick]; null: not
     * the rule's (the arm's own stop or lock).
     */
    fun whyAt(trigger: Double?, entry: Double, lot: Int, tick: Double = TICK): String? {
        if (trigger == null || !usable(entry, lot, tick)) return null
        breakevenLevel(entry, lot, tick)?.let { if (abs(trigger - it) < tick / 2) return WHY_TRAIL }
        return null
    }

    /** Whether [why] is one of the rule's exit words, this version's or the first's (older records). */
    fun isRuleWhy(why: String?): Boolean = why == WHY_STOP || why == WHY_TRAIL || why == OLD_WHY_TRAIL

    /** Whether [why] is the rule's entry stop (the first version's ₹500, on older records: a stop hit, for the findings). */
    fun isRuleStop(why: String?): Boolean = why == WHY_STOP

    /** An exit reason as a fill card or a line shows it: the rule's own words (this version's or the first's) with [STATUS] after them; any other as it is. */
    fun words(why: String): String = if (isRuleWhy(why)) "$why ($STATUS)" else why

    private fun usable(entry: Double, lot: Int, tick: Double): Boolean = entry.isFinite() && entry > 0 && lot > 0 && tick.isFinite() && tick > 0

    /** [x] up to the next [tick] (a value already on a tick stays), kept to the tick's decimals. */
    private fun up(x: Double, tick: Double): Double {
        val n = ceil(x / tick - 1e-6)
        return java.math.BigDecimal.valueOf(tick).multiply(java.math.BigDecimal.valueOf(n.toLong())).toDouble()
    }
}
