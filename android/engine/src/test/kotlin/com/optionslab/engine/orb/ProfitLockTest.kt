package com.optionslab.engine.orb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfitLockTest {
    @Test fun theLadderMovesTheStopAt25And50And75PercentOfTheTarget() {
        // Bought at 200, target +40: +10 -> 200 (breakeven), +20 -> 210, +30 -> 220.
        assertNull(ProfitLock.level(200.0, 40.0, 209.95))
        assertEquals(200.0, ProfitLock.level(200.0, 40.0, 210.0))
        assertEquals(210.0, ProfitLock.level(200.0, 40.0, 220.0))
        assertEquals(220.0, ProfitLock.level(200.0, 40.0, 230.0))
        assertEquals(220.0, ProfitLock.level(200.0, 40.0, 239.0))
        // ORB Sweep's +80 target: the rungs are 20 / 40 / 60 points up.
        assertEquals(240.0, ProfitLock.level(200.0, 80.0, 260.0))
    }

    @Test fun theLockSellsOnlyOnceTheEarlierBestEarnedIt() {
        assertFalse(ProfitLock.exits(200.0, 40.0, 205.0, 199.0), "below the first rung nothing is locked")
        assertTrue(ProfitLock.exits(200.0, 40.0, 212.0, 200.0), "went +12, back to the price paid")
        assertFalse(ProfitLock.exits(200.0, 40.0, 222.0, 211.0), "went +22: locked at 210, still above it")
        assertTrue(ProfitLock.exits(200.0, 40.0, 222.0, 210.0))
        assertTrue(ProfitLock.exits(200.0, 40.0, 231.0, 219.5), "went +31: locked at 220")
    }

    @Test fun everyFixedTargetArmIsLadderedButNotLiquidity() {
        assertEquals(40.0, ProfitLock.targetOf(OrbRules.ORB))
        assertEquals(40.0, ProfitLock.targetOf(OrbRules.ORB_FRESH))
        assertEquals(80.0, ProfitLock.targetOf(SweepRules.ARM))
        assertEquals(40.0, ProfitLock.targetOf(RangeFadeRules.ARM))
        assertNull(ProfitLock.targetOf(LiquidityRules.ARM))
        assertTrue(LiquidityRules.BOOKS.all { ProfitLock.targetOf(it) == null })
    }
}
