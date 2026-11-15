package com.optionslab.engine.orb

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Boss's stop rule on three arms' trades - Liquidity 15+5 (every book and index), R51 Donchian 20 and R72 Breadth Donchian - on paper
 * and live. (The object keeps its first name, StepTrail, so every caller, the persisted flag and the switch carry over.)
 *
 * 15 Nov 2026, second version (Boss, after R179: "Make it 1.5k stoploss" and "Instead 10% breakeven, make it 5% breakeven"), replacing the
 * ₹500 step trail of the morning:
 *  - at the entry the stop is where the trade loses ₹1,500 a lot AFTER the round trip's charges ([ProfitLock.roundTripPerUnit] for one
 *    lot), only where that is tighter than the arm's own stop (it never loosens one: a cheap option keeps its -15%);
 *  - once the option has traded 5% above the fill, the stop moves to breakeven: the fill plus the round trip's charges, so a sale there
 *    nets about ₹0. It only ever moves up. No fixed target.
 * Per unit the distances depend only on the fill and the lot size, so the levels are the same whatever the lots (₹1,500 a lot). Levels are
 * rounded UP to the tick (the safe side: a little less is lost, a little more is kept). Every exit the arm already has stays (its -15% stop,
 * ladders and locks, L25, time stops, index exits, square-offs): whichever comes first wins. A stop order fills at the market, so a fast move
 * can lose more than ₹1,500 a lot. It only lowers risk (a tighter stop, an earlier exit), so it acts on live through every existing live
 * gate (CLAUDE.md's LIVE rule).
 *
 * FAILED GATES, Boss's exception (CLAUDE.md, the fifth): R179 Amendment C replayed exactly this rule on one year ([RECORD]): about 55% wins
 * but far less profit than the arms' own rules (and than the +10% breakeven of Amendment B); it failed the gates. Labelled [STATUS] wherever it is
 * shown, logged in the Autopilot change timeline, one tap off. Signal source: the arms' entries stay on the index; only the stop is on the
 * premium (CLAUDE.md allows stops and locks on the option premium). Pure: no I/O, no clock.
 */
object StepTrail {
    /** The most a trade may lose at its entry stop, rupees a lot, after the round trip's charges. */
    const val STOP_RS = 1_500.0
    /** The gain above the fill, percent, at which the stop moves to breakeven. */
    const val BREAKEVEN_PCT = 5.0

    const val STATUS = "failed gates, Boss's exception"
    /** How the arms' rows and the trades name it. */
    const val LABEL = "₹1,500 stop a lot + breakeven at +5% (failed gates, Boss's exception)"
    /** Bot settings' switch. */
    const val SWITCH = "₹1,500 stop a lot + breakeven at +5% (Liquidity, R51, R72) — failed gates, Boss's exception"
    /** The diagnostics' name: no rupee sign and no grouped figure (the uploaded diagnostics hide every rupee figure). */
    const val DIAG = "1500 stop a lot and breakeven at 5 percent (failed gates, Boss's exception)"
    /** An exit by the stop set at the entry, as the trade record and the diagnostics say it. */
    const val WHY_STOP = "₹1,500 stop"
    /** An exit by the breakeven stop. */
    const val WHY_TRAIL = "breakeven lock"
    /** The first version's two exit words (15 Nov morning): older trade records still carry them. */
    const val OLD_WHY_STOP = "₹500 stop"
    const val OLD_WHY_TRAIL = "step trail"
    /** The rule in one line. */
    const val RULE = "each trade of Liquidity 15+5, R51 Donchian 20 and R72 Breadth Donchian starts with its stop where it loses ₹1,500 a lot " +
        "after charges (only where that is tighter than the arm's own stop); once the option is 5% above the fill the stop moves to breakeven " +
        "(the fill plus the charges), never down; the arm's own exits stay and the first one wins; paper and live"
    /** The honest record. */
    const val RECORD = "R179 Amendment C replayed this rule (₹1,500 a lot, breakeven at +5%, with the R151 picker) on one year (20 Oct " +
        "2025 - 19 Oct 2026, 1 lot): R51 won 54.5% and made ₹47k (its own rules 45.1%, ₹2.21 L), R72 55.9% and ₹1.70 L (46.3%, ₹2.98 L), " +
        "Liquidity 43.4% and ₹64k (39.2%, ₹1.18 L); it failed the gates. With the breakeven at +10% (Amendment B) it made more: R51 ₹1.30 L, " +
        "R72 ₹2.29 L"

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
     * The premium a long position filled at [entry] is stopped at on its entry: where one lot of [lot] units loses ₹1,500 after the round
     * trip's charges, entry - 1,500 / lot + charges a unit, rounded UP to the [tick]. Null when nothing can be told (no fill, no lot size,
     * no tick) or the level would be one tick or less.
     */
    fun stopLevel(entry: Double, lot: Int, tick: Double = TICK): Double? {
        if (!usable(entry, lot, tick)) return null
        return up(entry - STOP_RS / lot + chargesPerUnit(entry, lot), tick).takeIf { it > tick + EPS }
    }

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

    /** The rule's stop now: the higher of the entry's ₹1,500 a lot and the breakeven (null: neither). */
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
     * What a stop resting at [trigger] is, for a position filled at [entry]: [WHY_STOP] at the entry's ₹1,500 a lot, [WHY_TRAIL] at the
     * breakeven, within half a [tick]; null: neither (the arm's own stop or lock).
     */
    fun whyAt(trigger: Double?, entry: Double, lot: Int, tick: Double = TICK): String? {
        if (trigger == null || !usable(entry, lot, tick)) return null
        stopLevel(entry, lot, tick)?.let { if (abs(trigger - it) < tick / 2) return WHY_STOP }
        breakevenLevel(entry, lot, tick)?.let { if (abs(trigger - it) < tick / 2) return WHY_TRAIL }
        return null
    }

    /** Whether [why] is one of the rule's exit words, either version's. */
    fun isRuleWhy(why: String?): Boolean = why == WHY_STOP || why == WHY_TRAIL || why == OLD_WHY_STOP || why == OLD_WHY_TRAIL

    /** Whether [why] is the rule's entry stop, either version's (a stop hit, for the findings). */
    fun isRuleStop(why: String?): Boolean = why == WHY_STOP || why == OLD_WHY_STOP

    /** An exit reason as a fill card or a line shows it: the rule's own words (either version) with [STATUS] after them; any other as it is. */
    fun words(why: String): String = if (isRuleWhy(why)) "$why ($STATUS)" else why

    private fun usable(entry: Double, lot: Int, tick: Double): Boolean = entry.isFinite() && entry > 0 && lot > 0 && tick.isFinite() && tick > 0

    /** [x] up to the next [tick] (a value already on a tick stays), kept to the tick's decimals. */
    private fun up(x: Double, tick: Double): Double {
        val n = ceil(x / tick - 1e-6)
        return java.math.BigDecimal.valueOf(tick).multiply(java.math.BigDecimal.valueOf(n.toLong())).toDouble()
    }
}
