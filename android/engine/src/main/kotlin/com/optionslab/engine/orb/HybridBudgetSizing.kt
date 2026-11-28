package com.optionslab.engine.orb

import kotlin.math.floor

/**
 * Option 8 verified execution & sizing policy for the PR #19 + Feynman-Kac Hybrid portfolio
 * (Post-Nov 2024 SEBI regime, Nov 2024 - Oct 2026, real 1m NSE/BSE option prices):
 *   - Hybrid Exit: R72 Breadth Donchian uses X4 (30m hold); Liquidity UP-3 and NIFTY 0DTE D6 use X1.
 *   - Fixed Budget Sizing ([lotsFor]): floor(₹40,000 / oneLotCost) clamped to [1, 2] lots within hard ₹56,000 ceiling.
 *   - Auto-Compounding Sizing ([compoundingLotsFor]): reinvests realized profits and automatically scales lots up as
 *     [currentEquityRs] grows (and scales back down during drawdowns), never spending more than available cash equity.
 * Pure: no clock, no network, no storage, no orders.
 */
object HybridBudgetSizing {
    /** Fixed-capital target per-trade budget (₹40,000) for smart 1-2 lot utilization. */
    const val BUDGET_RS = 40_000.0

    /** Hard single-position capital ceiling (₹56,000) under fixed-capital mode. */
    const val HARD_CAP_RS = 56_000.0

    /** Maximum lots per single position under fixed-capital Option 8. */
    const val MAX_LOTS = 2

    /** Default fraction of live equity deployed per trade in compounding mode (~71.4% = ₹40k / ₹56k). */
    const val COMPOUND_DEPLOY_FRACTION = 40_000.0 / 56_000.0

    /** Default liquidity cap on auto-compounded lots per order (configurable up to 10-15 lots). */
    const val COMPOUND_MAX_LOTS = 2

    /** Exit regime selected by [exitModeFor]. */
    enum class ExitMode {
        /** Fast X1 exit (used by Liquidity UP-3 and NIFTY 0DTE D6). */
        X1_FAST,
        /** 30-minute trend hold X4 exit (used by R72 Breadth Donchian). */
        X4_HOLD_30M,
    }

    /**
     * Selects the verified Option 8 hybrid exit mode for [armSource]:
     * R72 Breadth Donchian -> [ExitMode.X4_HOLD_30M]; all other arms (Liquidity UP-3, NIFTY D6) -> [ExitMode.X1_FAST].
     * Pure.
     */
    fun exitModeFor(armSource: String): ExitMode =
        if (armSource == BreadthDonchianArm.SOURCE) ExitMode.X4_HOLD_30M else ExitMode.X1_FAST

    /**
     * Fixed-capital Option 8 lot count in `0..maxLots` for an option priced at [entryPremium] with contract [lotSize]:
     *   - Returns `0` when inputs are non-positive/non-finite or when even 1 lot exceeds [hardCapRs].
     *   - Otherwise returns `floor(min(budgetRs, hardCapRs) / (entryPremium * lotSize))` clamped to `[1, maxLots]`.
     * Pure.
     */
    fun lotsFor(
        entryPremium: Double,
        lotSize: Int,
        budgetRs: Double = BUDGET_RS,
        maxLots: Int = MAX_LOTS,
        hardCapRs: Double = HARD_CAP_RS,
    ): Int {
        if (!entryPremium.isFinite() || entryPremium <= 0.0 || lotSize <= 0) return 0
        if (!budgetRs.isFinite() || budgetRs <= 0.0 || !hardCapRs.isFinite() || hardCapRs <= 0.0 || maxLots < 1) return 0
        val oneLotCost = entryPremium * lotSize
        if (!oneLotCost.isFinite() || oneLotCost > hardCapRs) return 0
        val effectiveBudget = minOf(budgetRs, hardCapRs)
        val rawLots = floor(effectiveBudget / oneLotCost).toInt()
        return rawLots.coerceIn(1, maxLots)
    }

    /** Convenience helper returning fixed-capital contract quantity (`lotsFor(...) * lotSize`). Pure. */
    fun quantityFor(
        entryPremium: Double,
        lotSize: Int,
        budgetRs: Double = BUDGET_RS,
        maxLots: Int = MAX_LOTS,
        hardCapRs: Double = HARD_CAP_RS,
    ): Int = lotsFor(entryPremium, lotSize, budgetRs, maxLots, hardCapRs) * lotSize

    /**
     * Auto-compounding Option 8 lot count using live account equity ([currentEquityRs] = initial capital + realized PnL):
     *   - Returns `0` when inputs are invalid, [deployFraction] is outside `(0.0, 1.0]`, or 1 lot exceeds [currentEquityRs].
     *   - Deploys up to `currentEquityRs * deployFraction` (never exceeding `currentEquityRs`), automatically scaling lots
     *     up as profits accumulate and scaling back down during drawdowns, capped at [maxLots].
     * Pure.
     */
    fun compoundingLotsFor(
        currentEquityRs: Double,
        entryPremium: Double,
        lotSize: Int,
        deployFraction: Double = COMPOUND_DEPLOY_FRACTION,
        maxLots: Int = COMPOUND_MAX_LOTS,
    ): Int {
        if (!currentEquityRs.isFinite() || currentEquityRs <= 0.0) return 0
        if (!entryPremium.isFinite() || entryPremium <= 0.0 || lotSize <= 0) return 0
        if (!deployFraction.isFinite() || deployFraction <= 0.0 || deployFraction > 1.0 || maxLots < 1) return 0
        val oneLotCost = entryPremium * lotSize
        if (!oneLotCost.isFinite() || oneLotCost > currentEquityRs) return 0
        val budget = currentEquityRs * deployFraction
        val rawLots = floor(budget / oneLotCost).toInt().coerceIn(1, maxLots)
        val affordableLots = floor(currentEquityRs / oneLotCost).toInt()
        return minOf(rawLots, affordableLots)
    }

    /** Convenience helper returning auto-compounded contract quantity (`compoundingLotsFor(...) * lotSize`). Pure. */
    fun compoundingQuantityFor(
        currentEquityRs: Double,
        entryPremium: Double,
        lotSize: Int,
        deployFraction: Double = COMPOUND_DEPLOY_FRACTION,
        maxLots: Int = COMPOUND_MAX_LOTS,
    ): Int = compoundingLotsFor(currentEquityRs, entryPremium, lotSize, deployFraction, maxLots) * lotSize
}
