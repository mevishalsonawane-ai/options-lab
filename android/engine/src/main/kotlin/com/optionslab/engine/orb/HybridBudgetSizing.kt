package com.optionslab.engine.orb

import kotlin.math.floor

object HybridBudgetSizing {
    const val BUDGET_RS = 40_000.0
    const val LIQUIDITY_BUDGET_RS = 56_000.0
    const val HARD_CAP_RS = 56_000.0
    const val MAX_LOTS = 2
    const val LIQUIDITY_MAX_LOTS = 3
    const val COMPOUND_DEPLOY_FRACTION = 40_000.0 / 56_000.0
    const val LIQUIDITY_COMPOUND_DEPLOY_FRACTION = 1.0
    const val COMPOUND_MAX_LOTS = 2

    val EX_BN_LIQUIDITY_SOURCES: Set<String> = LiquidityRules.EX_BN_BOOKS.map { it.source }.toSet()

    enum class ExitMode {
        X1_FAST,
        X4_HOLD_30M,
        SPLIT_X1_X4,
    }

    data class OrderPlan(
        val armSource: String,
        val totalLots: Int,
        val x1Lots: Int,
        val x4Lots: Int,
        val lotSize: Int,
        val exitMode: ExitMode,
    ) {
        val totalQuantity: Int get() = totalLots * lotSize
        val x1Quantity: Int get() = x1Lots * lotSize
        val x4Quantity: Int get() = x4Lots * lotSize
        val isSplit: Boolean get() = x1Lots > 0 && x4Lots > 0
    }

    fun isExBnLiquidity(armSource: String): Boolean = armSource in EX_BN_LIQUIDITY_SOURCES

    fun isLiquidityArm(armSource: String): Boolean =
        armSource in EX_BN_LIQUIDITY_SOURCES ||
            armSource == LiquidityRules.ARM.source ||
            armSource == LiquidityRules.ARM15.source ||
            armSource == LiquidityRules.ARM5.source

    fun isAllowedArm(armSource: String): Boolean =
        armSource == BreadthDonchianArm.SOURCE ||
            armSource == NiftyExpiryD6Arm.SOURCE ||
            isExBnLiquidity(armSource)

    fun budgetFor(armSource: String): Double =
        if (isExBnLiquidity(armSource)) LIQUIDITY_BUDGET_RS else BUDGET_RS

    fun maxLotsFor(armSource: String): Int =
        if (isExBnLiquidity(armSource)) LIQUIDITY_MAX_LOTS else MAX_LOTS

    fun exitModeFor(armSource: String, lots: Int = MAX_LOTS): ExitMode =
        if (armSource == BreadthDonchianArm.SOURCE && lots >= 2) ExitMode.SPLIT_X1_X4 else ExitMode.X1_FAST

    fun planFor(
        armSource: String,
        entryPremium: Double,
        lotSize: Int,
        hardCapRs: Double = HARD_CAP_RS,
        allowNonExBnLiquidity: Boolean = false,
    ): OrderPlan {
        if (!allowNonExBnLiquidity && isLiquidityArm(armSource) && !isExBnLiquidity(armSource)) {
            return OrderPlan(armSource, 0, 0, 0, maxOf(lotSize, 0), ExitMode.X1_FAST)
        }
        val lots = lotsFor(entryPremium, lotSize, budgetFor(armSource), maxLotsFor(armSource), hardCapRs)
        return buildPlan(armSource, lots, lotSize)
    }

    fun compoundingPlanFor(
        armSource: String,
        currentEquityRs: Double,
        entryPremium: Double,
        lotSize: Int,
        maxLotsOverride: Int? = null,
        allowNonExBnLiquidity: Boolean = false,
    ): OrderPlan {
        if (!allowNonExBnLiquidity && isLiquidityArm(armSource) && !isExBnLiquidity(armSource)) {
            return OrderPlan(armSource, 0, 0, 0, maxOf(lotSize, 0), ExitMode.X1_FAST)
        }
        val deployFraction =
            if (isExBnLiquidity(armSource)) LIQUIDITY_COMPOUND_DEPLOY_FRACTION else COMPOUND_DEPLOY_FRACTION
        val maxLots = maxLotsOverride ?: maxLotsFor(armSource)
        val lots = compoundingLotsFor(currentEquityRs, entryPremium, lotSize, deployFraction, maxLots)
        return buildPlan(armSource, lots, lotSize)
    }

    private fun buildPlan(armSource: String, lots: Int, lotSize: Int): OrderPlan {
        val safeLotSize = maxOf(lotSize, 0)
        if (lots <= 0) return OrderPlan(armSource, 0, 0, 0, safeLotSize, ExitMode.X1_FAST)
        val mode = exitModeFor(armSource, lots)
        return if (mode == ExitMode.SPLIT_X1_X4) {
            val x1Lots = (lots + 1) / 2
            val x4Lots = lots - x1Lots
            OrderPlan(armSource, lots, x1Lots, x4Lots, safeLotSize, mode)
        } else {
            OrderPlan(armSource, lots, lots, 0, safeLotSize, ExitMode.X1_FAST)
        }
    }

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

    fun quantityFor(
        entryPremium: Double,
        lotSize: Int,
        budgetRs: Double = BUDGET_RS,
        maxLots: Int = MAX_LOTS,
        hardCapRs: Double = HARD_CAP_RS,
    ): Int = lotsFor(entryPremium, lotSize, budgetRs, maxLots, hardCapRs) * lotSize

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

    fun compoundingQuantityFor(
        currentEquityRs: Double,
        entryPremium: Double,
        lotSize: Int,
        deployFraction: Double = COMPOUND_DEPLOY_FRACTION,
        maxLots: Int = COMPOUND_MAX_LOTS,
    ): Int = compoundingLotsFor(currentEquityRs, entryPremium, lotSize, deployFraction, maxLots) * lotSize
}
