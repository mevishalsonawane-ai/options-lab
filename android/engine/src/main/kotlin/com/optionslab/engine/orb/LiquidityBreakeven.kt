package com.optionslab.engine.orb

/**
 * Boss's breakeven rule for Liquidity (22 Oct 2026: "Keep it as is. Just ... if the 10% is received, make the stoploss to the
 * start point"): once the option has traded [TRIGGER] (+10%) above the price paid, its stop rises to the start point - the
 * price paid plus the round trip's charges ([ProfitLock.roundTripPerUnit]), so a sale there is a real breakeven, not a small
 * loss after charges (as the app's other breakeven rungs, Boss's 06 Oct fix). Nothing else of the arm changes.
 *
 * Research R100 amendment A4 (research/HUNT_R100_LIQUIDITY_GIVEBACK.md, 22 Oct) did NOT pass its gates: on 1,238 past trades
 * (20 Nov 2024 - 13 Oct 2026, 1 lot, after charges) it made Rs 2,45,083 at a 38% win rate against Rs 2,68,404 at 42% on the
 * plain rules. So it is only Boss's own opt-in PAPER switch, off by default, labelled ([NOTE]) - as the Rs 500 trail
 * ([LiquidityTrail]). It never touches a live trade.
 *
 * Like the trail it MOVES THE RESTING STOP, up only, so it works with the app closed; the best price is every stream tick or
 * the 1-minute candles' highs ([ProfitLock.nextPeak]); a new best counts from the next look. Pure: no clock, no network, no
 * orders.
 */
object LiquidityBreakeven {
    /** Boss's +10%: the share of the price paid the option must gain before the stop moves to the start point. */
    const val TRIGGER = 0.10
    /** The exit's reason when the app sells at breakeven or the raised stop fills. */
    const val WHY = "breakeven_lock"
    /** A paper trade's label under it, kept apart from the plain-rules record. */
    const val LABEL = "with breakeven at +10%"
    /** What the switch says next to it. */
    const val NOTE = "did not pass research, your choice"
    private const val EPS = 1e-9

    /**
     * The breakeven stop for a buy at [entry] whose best price so far is [peak] ([trigger]: the gain that arms it, null: no rule;
     * [costPerUnit]: [ProfitLock.roundTripPerUnit]), on the [tick] rounded up, or null: no rule, a missing input, the best not yet
     * [trigger] above the entry, or a level not below the best price (it would sell at once on no profit).
     */
    fun level(entry: Double, peak: Double?, costPerUnit: Double, trigger: Double?, tick: Double = OrbRules.TICK): Double? {
        if (trigger == null || !trigger.isFinite() || trigger <= 0) return null
        if (!(entry > 0) || peak == null || !peak.isFinite() || peak < entry * (1 + trigger) - EPS) return null
        val be = ProfitLock.onTick(entry + (if (costPerUnit.isFinite()) costPerUnit.coerceAtLeast(0.0) else 0.0), tick)
        return be.takeIf { it < peak - EPS }
    }

    /** True when [ltp] is at or below the level the best price BEFORE it ([peakBefore]) earned: a new best counts from the next look. */
    fun exits(entry: Double, peakBefore: Double?, ltp: Double, costPerUnit: Double, trigger: Double?): Boolean =
        level(entry, peakBefore, costPerUnit, trigger)?.let { ltp <= it + EPS } == true

    /**
     * The trigger the resting sell stop moves UP to, as [ProfitLock.raise]: null when it must not move - no level yet, not above
     * the stop resting now ([current]; a stop is never moved down), or not below the price now ([ltp]; the app's own check sells
     * that instead).
     */
    fun raise(entry: Double, peak: Double?, costPerUnit: Double, trigger: Double?, current: Double?, ltp: Double,
              tick: Double = OrbRules.TICK): Double? {
        val l = level(entry, peak, costPerUnit, trigger, tick) ?: return null
        if (current != null && l <= current + EPS) return null
        return l.takeIf { it < ltp - EPS }
    }

    /**
     * The trigger a NEW entry carries: [TRIGGER] only on PAPER ([live] false), on a Liquidity book ([arm]), with Boss's switch on
     * ([switchOn]); null otherwise. A live entry never carries it, whatever the switch says.
     */
    fun forEntry(arm: Arm, live: Boolean, switchOn: Boolean): Double? = if (switchOn && !live && arm.liquidity) TRIGGER else null

    /**
     * A paper Liquidity trade's rules, for its record and notices: [LiquidityTrail.PLAIN] on plain rules, else the rules Boss's
     * switches gave it - [LiquidityTrail.TRAILED] ([trail]: its give-back), [LABEL] ([breakeven]: its trigger), or both.
     */
    fun label(trail: Double?, breakeven: Double?): String = when {
        trail != null && breakeven != null -> "${LiquidityTrail.TRAILED} and breakeven at +10%"
        trail != null -> LiquidityTrail.TRAILED
        breakeven != null -> LABEL
        else -> LiquidityTrail.PLAIN
    }

    /** Is a paper Liquidity trade on Boss's own rules (a trail or this breakeven), so kept out of the plain-rules record? */
    fun ownRules(trail: Double?, breakeven: Double?): Boolean = trail != null || breakeven != null
}
