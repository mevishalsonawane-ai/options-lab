package com.optionslab.engine.orb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBudgetSizingTest {

    @Test
    fun exitModeRoutesR72TwoLotsToSplitAndOneLotOrOtherArmsToX1() {
        assertEquals(HybridBudgetSizing.ExitMode.SPLIT_X1_X4, HybridBudgetSizing.exitModeFor(BreadthDonchianArm.SOURCE))
        assertEquals(HybridBudgetSizing.ExitMode.SPLIT_X1_X4, HybridBudgetSizing.exitModeFor(BreadthDonchianArm.SOURCE, lots = 2))
        assertEquals(HybridBudgetSizing.ExitMode.X1_FAST, HybridBudgetSizing.exitModeFor(BreadthDonchianArm.SOURCE, lots = 1))
        assertEquals(HybridBudgetSizing.ExitMode.X1_FAST, HybridBudgetSizing.exitModeFor(LiquidityRules.MID15.source, lots = 3))
        assertEquals(HybridBudgetSizing.ExitMode.X1_FAST, HybridBudgetSizing.exitModeFor(LiquidityRules.SENSEX5.source, lots = 3))
        assertEquals(HybridBudgetSizing.ExitMode.X1_FAST, HybridBudgetSizing.exitModeFor(NiftyExpiryD6Arm.SOURCE, lots = 2))
        assertTrue(HybridBudgetSizing.ExitMode.values().contains(HybridBudgetSizing.ExitMode.X4_HOLD_30M))
    }

    @Test
    fun exBnLiquidityBookFilterAllowsAllEightExBnBooksAndBlocksBankNifty() {
        assertEquals(8, HybridBudgetSizing.EX_BN_LIQUIDITY_SOURCES.size)
        for (book in LiquidityRules.EX_BN_BOOKS) {
            assertTrue(HybridBudgetSizing.isExBnLiquidity(book.source))
            assertTrue(HybridBudgetSizing.isLiquidityArm(book.source))
            assertTrue(HybridBudgetSizing.isAllowedArm(book.source))
            assertEquals(56_000.0, HybridBudgetSizing.budgetFor(book.source), 1e-6)
            assertEquals(3, HybridBudgetSizing.maxLotsFor(book.source))
        }
        assertTrue(HybridBudgetSizing.isLiquidityArm(LiquidityRules.ARM.source))
        assertTrue(HybridBudgetSizing.isLiquidityArm(LiquidityRules.ARM15.source))
        assertTrue(HybridBudgetSizing.isLiquidityArm(LiquidityRules.ARM5.source))
        assertFalse(HybridBudgetSizing.isLiquidityArm(BreadthDonchianArm.SOURCE))
        assertFalse(HybridBudgetSizing.isLiquidityArm(NiftyExpiryD6Arm.SOURCE))
        assertFalse(HybridBudgetSizing.isExBnLiquidity(LiquidityRules.ARM15.source))
        assertFalse(HybridBudgetSizing.isExBnLiquidity(LiquidityRules.ARM5.source))
        assertFalse(HybridBudgetSizing.isAllowedArm(LiquidityRules.ARM15.source))
        assertFalse(HybridBudgetSizing.isAllowedArm(LiquidityRules.ARM5.source))
        assertTrue(HybridBudgetSizing.isAllowedArm(BreadthDonchianArm.SOURCE))
        assertTrue(HybridBudgetSizing.isAllowedArm(NiftyExpiryD6Arm.SOURCE))
        assertEquals(40_000.0, HybridBudgetSizing.budgetFor(BreadthDonchianArm.SOURCE), 1e-6)
        assertEquals(2, HybridBudgetSizing.maxLotsFor(BreadthDonchianArm.SOURCE))
    }

    @Test
    fun planForAllocatesIdea4AsymmetricLiquidityAndR72Split() {
        val liqPlan = HybridBudgetSizing.planFor(LiquidityRules.MID15.source, entryPremium = 200.0, lotSize = 75)
        assertEquals(3, liqPlan.totalLots)
        assertEquals(3, liqPlan.x1Lots)
        assertEquals(0, liqPlan.x4Lots)
        assertEquals(225, liqPlan.totalQuantity)
        assertEquals(225, liqPlan.x1Quantity)
        assertEquals(0, liqPlan.x4Quantity)
        assertEquals(HybridBudgetSizing.ExitMode.X1_FAST, liqPlan.exitMode)
        assertFalse(liqPlan.isSplit)

        val bnBlocked = HybridBudgetSizing.planFor(LiquidityRules.ARM5.source, entryPremium = 200.0, lotSize = 30)
        assertEquals(0, bnBlocked.totalLots)
        assertEquals(0, bnBlocked.totalQuantity)

        val bnOverride = HybridBudgetSizing.planFor(
            LiquidityRules.ARM15.source,
            entryPremium = 200.0,
            lotSize = 30,
            allowNonExBnLiquidity = true,
        )
        assertEquals(2, bnOverride.totalLots)
        assertEquals(HybridBudgetSizing.ExitMode.X1_FAST, bnOverride.exitMode)

        val r72Split = HybridBudgetSizing.planFor(BreadthDonchianArm.SOURCE, entryPremium = 200.0, lotSize = 75)
        assertEquals(2, r72Split.totalLots)
        assertEquals(1, r72Split.x1Lots)
        assertEquals(1, r72Split.x4Lots)
        assertEquals(75, r72Split.x1Quantity)
        assertEquals(75, r72Split.x4Quantity)
        assertEquals(HybridBudgetSizing.ExitMode.SPLIT_X1_X4, r72Split.exitMode)
        assertTrue(r72Split.isSplit)

        val r72Single = HybridBudgetSizing.planFor(BreadthDonchianArm.SOURCE, entryPremium = 400.0, lotSize = 75)
        assertEquals(1, r72Single.totalLots)
        assertEquals(1, r72Single.x1Lots)
        assertEquals(0, r72Single.x4Lots)
        assertEquals(HybridBudgetSizing.ExitMode.X1_FAST, r72Single.exitMode)
        assertFalse(r72Single.isSplit)

        val r72Rejected = HybridBudgetSizing.planFor(BreadthDonchianArm.SOURCE, entryPremium = 1000.0, lotSize = 75)
        assertEquals(0, r72Rejected.totalLots)

        val d6Plan = HybridBudgetSizing.planFor(NiftyExpiryD6Arm.SOURCE, entryPremium = 200.0, lotSize = 75)
        assertEquals(2, d6Plan.totalLots)
        assertEquals(2, d6Plan.x1Lots)
        assertEquals(0, d6Plan.x4Lots)
        assertEquals(HybridBudgetSizing.ExitMode.X1_FAST, d6Plan.exitMode)
    }

    @Test
    fun compoundingPlanForScalesAsymmetricallyAndSplitsR72() {
        val liq56 = HybridBudgetSizing.compoundingPlanFor(LiquidityRules.NIFTY15.source, 56_000.0, 200.0, 75)
        assertEquals(3, liq56.totalLots)
        assertEquals(3, liq56.x1Lots)
        assertEquals(0, liq56.x4Lots)

        val r72Odd = HybridBudgetSizing.compoundingPlanFor(BreadthDonchianArm.SOURCE, 112_000.0, 200.0, 75, maxLotsOverride = 3)
        assertEquals(3, r72Odd.totalLots)
        assertEquals(2, r72Odd.x1Lots)
        assertEquals(1, r72Odd.x4Lots)
        assertEquals(HybridBudgetSizing.ExitMode.SPLIT_X1_X4, r72Odd.exitMode)

        val r72At112 = HybridBudgetSizing.compoundingPlanFor(BreadthDonchianArm.SOURCE, 112_000.0, 200.0, 75, maxLotsOverride = 4)
        assertEquals(4, r72At112.totalLots)
        assertEquals(2, r72At112.x1Lots)
        assertEquals(2, r72At112.x4Lots)

        val bnCompBlocked = HybridBudgetSizing.compoundingPlanFor(LiquidityRules.ARM15.source, 112_000.0, 200.0, 30)
        assertEquals(0, bnCompBlocked.totalLots)

        val bnCompOverride = HybridBudgetSizing.compoundingPlanFor(
            LiquidityRules.ARM15.source,
            112_000.0,
            200.0,
            30,
            allowNonExBnLiquidity = true,
        )
        assertEquals(2, bnCompOverride.totalLots)
    }

    @Test
    fun lotsForAllocatesTwoLotsInsideFortyThousandBudget() {
        assertEquals(2, HybridBudgetSizing.lotsFor(entryPremium = 200.0, lotSize = 75))
        assertEquals(150, HybridBudgetSizing.quantityFor(entryPremium = 200.0, lotSize = 75))
        assertEquals(2, HybridBudgetSizing.lotsFor(entryPremium = 100.0, lotSize = 50))
        assertEquals(2, HybridBudgetSizing.lotsFor(entryPremium = 400.0, lotSize = 50))
    }

    @Test
    fun lotsForFallsBackToOneLotBetweenBudgetAndHardCap() {
        assertEquals(1, HybridBudgetSizing.lotsFor(entryPremium = 400.0, lotSize = 75))
        assertEquals(1, HybridBudgetSizing.lotsFor(entryPremium = 640.0, lotSize = 75))
        assertEquals(1, HybridBudgetSizing.lotsFor(entryPremium = 560.0, lotSize = 100))
    }

    @Test
    fun lotsForRejectsAboveHardCapAndInvalidInputs() {
        assertEquals(0, HybridBudgetSizing.lotsFor(entryPremium = 561.0, lotSize = 100))
        assertEquals(0, HybridBudgetSizing.quantityFor(entryPremium = 561.0, lotSize = 100))
        assertEquals(0, HybridBudgetSizing.lotsFor(entryPremium = 0.0, lotSize = 75))
        assertEquals(0, HybridBudgetSizing.lotsFor(entryPremium = -10.0, lotSize = 75))
        assertEquals(0, HybridBudgetSizing.lotsFor(entryPremium = Double.NaN, lotSize = 75))
        assertEquals(0, HybridBudgetSizing.lotsFor(entryPremium = Double.POSITIVE_INFINITY, lotSize = 75))
        assertEquals(0, HybridBudgetSizing.lotsFor(entryPremium = 100.0, lotSize = 0))
        assertEquals(0, HybridBudgetSizing.lotsFor(entryPremium = 100.0, lotSize = -25))
        assertEquals(0, HybridBudgetSizing.lotsFor(entryPremium = 100.0, lotSize = 50, budgetRs = 0.0))
        assertEquals(0, HybridBudgetSizing.lotsFor(entryPremium = 100.0, lotSize = 50, budgetRs = Double.NaN))
        assertEquals(0, HybridBudgetSizing.lotsFor(entryPremium = 100.0, lotSize = 50, hardCapRs = 0.0))
        assertEquals(0, HybridBudgetSizing.lotsFor(entryPremium = 100.0, lotSize = 50, hardCapRs = Double.POSITIVE_INFINITY))
        assertEquals(0, HybridBudgetSizing.lotsFor(entryPremium = 100.0, lotSize = 50, maxLots = 0))
        assertEquals(0, HybridBudgetSizing.lotsFor(entryPremium = Double.MAX_VALUE, lotSize = 2))
    }

    @Test
    fun lotsForRespectsHardCapWhenBudgetExceedsHardCap() {
        assertEquals(2, HybridBudgetSizing.lotsFor(entryPremium = 250.0, lotSize = 100, budgetRs = 80_000.0, maxLots = 4, hardCapRs = 56_000.0))
    }

    @Test
    fun compoundingLotsScalesUpWithProfitsAndDownOnDrawdown() {
        assertEquals(2, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 56_000.0, entryPremium = 200.0, lotSize = 75))
        assertEquals(150, HybridBudgetSizing.compoundingQuantityFor(currentEquityRs = 56_000.0, entryPremium = 200.0, lotSize = 75))
        assertEquals(2, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 112_000.0, entryPremium = 200.0, lotSize = 75))
        assertEquals(5, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 112_000.0, entryPremium = 200.0, lotSize = 75, maxLots = 5))
        assertEquals(10, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 210_000.0, entryPremium = 200.0, lotSize = 75, maxLots = 10))
        assertEquals(1, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 30_000.0, entryPremium = 200.0, lotSize = 75))
        assertEquals(0, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 14_000.0, entryPremium = 200.0, lotSize = 75))
    }

    @Test
    fun compoundingLotsRejectsInvalidInputs() {
        assertEquals(0, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 0.0, entryPremium = 200.0, lotSize = 75))
        assertEquals(0, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = Double.NaN, entryPremium = 200.0, lotSize = 75))
        assertEquals(0, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 56_000.0, entryPremium = 0.0, lotSize = 75))
        assertEquals(0, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 56_000.0, entryPremium = Double.POSITIVE_INFINITY, lotSize = 75))
        assertEquals(0, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 56_000.0, entryPremium = 200.0, lotSize = 0))
        assertEquals(0, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 56_000.0, entryPremium = 200.0, lotSize = 75, deployFraction = 0.0))
        assertEquals(0, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 56_000.0, entryPremium = 200.0, lotSize = 75, deployFraction = 1.5))
        assertEquals(0, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 56_000.0, entryPremium = 200.0, lotSize = 75, deployFraction = Double.NaN))
        assertEquals(0, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 56_000.0, entryPremium = 200.0, lotSize = 75, maxLots = 0))
        assertEquals(0, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 56_000.0, entryPremium = Double.MAX_VALUE, lotSize = 2))
    }
}
