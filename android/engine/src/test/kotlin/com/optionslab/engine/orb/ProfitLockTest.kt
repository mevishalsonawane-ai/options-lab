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

    @Test fun aPineScriptsReferenceIsItsTargetElseTwiceItsStopElseNone() {
        assertEquals(100.0, ProfitLock.pineReference(100.0, 30.0), "the target when set")
        assertEquals(100.0, ProfitLock.pineReference(100.0, 0.0))
        assertEquals(60.0, ProfitLock.pineReference(0.0, 30.0), "no target: twice the stop")
        assertNull(ProfitLock.pineReference(0.0, 0.0), "neither: no lock")
        assertNull(ProfitLock.pineReference(-5.0, Double.NaN))
    }

    /** Walks [path] (option prices, one a look) as PineAuto does: the lock from the best price seen BEFORE each look. */
    private fun walk(entry: Double, ref: Double, path: List<Double>): Int? {
        var peak = entry
        path.forEachIndexed { i, ltp ->
            if (ProfitLock.exits(entry, ref, peak, ltp)) return i
            peak = maxOf(peak, ltp)
        }
        return null
    }

    @Test fun onASyntheticPathTheLockSellsWhereTheLadderSays() {
        // Bought at 300, target 100: up to 355 (55%: 25 locked), back to 324 -> sold there, not at the stop.
        assertEquals(4, walk(300.0, 100.0, listOf(310.0, 330.0, 355.0, 340.0, 324.0, 290.0)))
        // Only 20% of the way (320), then back below the price paid: no rung, the lock never sells.
        assertNull(walk(300.0, 100.0, listOf(310.0, 320.0, 299.0, 280.0)))
        // A single look that jumps straight to 380 and back to 340 at once: the rung counts from the next look on.
        assertNull(walk(300.0, 100.0, listOf(380.0)))
        assertEquals(1, walk(300.0, 100.0, listOf(380.0, 349.0)), "75% reached: 50 locked, 349 sells")
        // Stop only (30): the reference is 60, so 15 up moves the stop to the price paid.
        assertEquals(2, walk(300.0, ProfitLock.pineReference(0.0, 30.0)!!, listOf(316.0, 305.0, 300.0)))
    }
}
