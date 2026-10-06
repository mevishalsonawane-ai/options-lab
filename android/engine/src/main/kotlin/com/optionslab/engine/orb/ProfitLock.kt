package com.optionslab.engine.orb

/**
 * The owner's profit-lock ladder (2026-10-01) for the arms with a fixed premium target (ORB, ORB Fresh, ORB Sweep,
 * Range Fade): once the option has traded 25% of the way to its target the stop moves to the price paid, at 50% to
 * price paid + 25% of the target, at 75% to price paid + 50%. A trade that got most of the way and turned back no
 * longer runs to the full -40 stop. Backtest (research/PROFIT_LOCK.md, two BANKNIFTY years): ORB -218k / -194k ->
 * -77k / -70k, ORB Fresh -69k / -57k -> -17k / -6k; Sweep and Range Fade about unchanged.
 * Liquidity 15+5 has no fixed target (it sells at the next liquidity level), so it is not laddered.
 * Pure: no clock, no network, no orders.
 */
object ProfitLock {
    /** (share of the target reached, share of the target locked in). */
    val LADDER: List<Pair<Double, Double>> = listOf(0.25 to 0.0, 0.50 to 0.25, 0.75 to 0.50)
    private const val EPS = 1e-9

    /** The arm's premium target in points, or null for an arm without one. */
    fun targetOf(arm: Arm): Double? = when {
        arm.liquidity -> null
        arm.hero -> null              // the Hero arm has no target: it holds to its exit time
        arm.sweep -> SweepRules.TARGET_POINTS
        else -> OrbRules.TARGET_POINTS
    }

    /** The locked stop for a buy at [entry] whose best price so far is [peak], or null below the first rung. */
    fun level(entry: Double, target: Double, peak: Double): Double? =
        LADDER.lastOrNull { peak >= entry + it.first * target - EPS }?.let { entry + it.second * target }

    /**
     * True when [ltp] is at or below the lock earned by the best price seen BEFORE it ([peakBefore]): a rung counts
     * from the next look on, as the backtest applies it from the next minute.
     */
    fun exits(entry: Double, target: Double, peakBefore: Double, ltp: Double): Boolean =
        level(entry, target, peakBefore)?.let { ltp <= it + EPS } == true
}
