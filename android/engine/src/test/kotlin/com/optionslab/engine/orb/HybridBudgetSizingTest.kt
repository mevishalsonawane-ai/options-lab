package com.optionslab.engine.orb

import org.junit.Assert.assertEquals
import org.junit.Test

class HybridBudgetSizingTest {

    @Test
    fun exitModeRoutesR72ToX4AndOtherArmsToX1() {
        assertEquals(HybridBudgetSizing.ExitMode.X4_HOLD_30M, HybridBudgetSizing.exitModeFor(BreadthDonchianArm.SOURCE))
        assertEquals(HybridBudgetSizing.ExitMode.X1_FAST, HybridBudgetSizing.exitModeFor(LiquidityRules.ARM15.source))
        assertEquals(HybridBudgetSizing.ExitMode.X1_FAST, HybridBudgetSizing.exitModeFor(LiquidityRules.MID15.source))
        assertEquals(HybridBudgetSizing.ExitMode.X1_FAST, HybridBudgetSizing.exitModeFor(NiftyExpiryD6Arm.SOURCE))
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
        // Start ₹56k, NIFTY 75 @ ₹200 = ₹15k/lot -> budget ₹40k -> 2 lots
        assertEquals(2, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 56_000.0, entryPremium = 200.0, lotSize = 75))
        assertEquals(150, HybridBudgetSizing.compoundingQuantityFor(currentEquityRs = 56_000.0, entryPremium = 200.0, lotSize = 75))

        // Profits grow equity to ₹1,12,000 -> capped at default 2 lots (and scales to 5 lots when maxLots = 5)
        assertEquals(2, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 112_000.0, entryPremium = 200.0, lotSize = 75))
        assertEquals(5, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 112_000.0, entryPremium = 200.0, lotSize = 75, maxLots = 5))

        // Higher maxLots=10 at ₹2,10,000 equity -> budget ₹1,50,000 -> 10 lots
        assertEquals(10, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 210_000.0, entryPremium = 200.0, lotSize = 75, maxLots = 10))

        // Drawdown to ₹30,000 equity -> budget ₹21.4k -> automatically scales back down to 1 lot
        assertEquals(1, HybridBudgetSizing.compoundingLotsFor(currentEquityRs = 30_000.0, entryPremium = 200.0, lotSize = 75))

        // Equity below 1-lot cost -> 0 lots (never over-leverages)
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
