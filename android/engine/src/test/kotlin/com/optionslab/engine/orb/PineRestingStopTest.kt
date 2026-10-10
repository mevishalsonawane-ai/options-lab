package com.optionslab.engine.orb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Pine stop and profit lock as one resting stop (08 Oct, research/PROFIT_LOCK_8OCT.md fix 1): up, never down. */
class PineRestingStopTest {
    private val trail = ProfitLock.Trail()

    @Test fun theStopLossRestsUnderTheBuyOnTheTick() {
        assertEquals(270.0, ProfitLock.pineBase(300.0, 30.0))
        assertEquals(307.25, ProfitLock.pineBase(337.22, 30.0), "rounded up: never under the level it guards")
        assertEquals(307.3, ProfitLock.pineBase(337.22, 30.0, 0.1))
        assertNull(ProfitLock.pineBase(300.0, 0.0), "no stop")
        assertNull(ProfitLock.pineBase(300.0, Double.NaN))
        assertNull(ProfitLock.pineBase(Double.NaN, 30.0))
        assertNull(ProfitLock.pineBase(20.0, 30.0), "a level under zero has no stop")
        assertNull(ProfitLock.pineBase(30.04, 30.0), "a level at or under one tick has no stop")
    }

    @Test fun theLockMovesTheRestingStopUpWithTheScriptsOwnRungs() {
        // Stop 30, target 60 (the ladder's +15 -> breakeven, +30 -> +15) and the default trail (5% breakeven, 8% keeps 50%).
        val ref = ProfitLock.pineReference(60.0, 30.0)
        assertNull(ProfitLock.pineRaise(300.0, ref, trail, 0.0, 312.0, 270.0, 311.0), "+4%: no rung yet")
        assertEquals(300.0, ProfitLock.pineRaise(300.0, ref, trail, 0.0, 322.0, 270.0, 310.0), "+7.3%: breakeven")
        assertEquals(315.0, ProfitLock.pineRaise(300.0, ref, trail, 0.0, 330.0, 300.0, 325.0), "+10%: half the gain kept")
        // With the round trip's charges the breakeven is a real one, on the tick.
        assertEquals(301.6, ProfitLock.pineRaise(300.0, ref, trail, 1.59, 322.0, 270.0, 310.0))
        // A script with neither a ladder nor a trail has no lock.
        assertNull(ProfitLock.pineRaise(300.0, null, null, 0.0, 400.0, 270.0, 390.0))
        // The trail alone still locks (no target, no stop).
        assertEquals(315.0, ProfitLock.pineRaise(300.0, null, trail, 0.0, 330.0, null, 325.0))
    }

    @Test fun itIsNeverMovedDownNorToOrAboveThePriceNow() {
        val ref = 60.0
        assertNull(ProfitLock.pineRaise(300.0, ref, trail, 0.0, 330.0, 315.0, 325.0), "already there")
        assertNull(ProfitLock.pineRaise(300.0, ref, trail, 0.0, 322.0, 315.0, 320.0), "a lower lock never lowers it")
        assertNull(ProfitLock.pineRaise(300.0, ref, trail, 0.0, 330.0, 270.0, 315.0), "the price is at the lock: the app sells it")
        assertNull(ProfitLock.pineRaise(300.0, ref, trail, 0.0, 330.0, 270.0, 310.0))
        // Over a walk of best prices and prices the stop only ever rises.
        var stop = ProfitLock.pineBase(300.0, 30.0)!!
        var peak = 300.0
        for ((px, hi) in listOf(305.0 to 309.0, 318.0 to 323.0, 312.0 to 316.0, 335.0 to 341.0, 331.0 to 333.0, 360.0 to 372.0, 340.0 to 341.0)) {
            ProfitLock.pineRaise(300.0, ref, trail, 0.0, peak, stop, px)?.let { assertTrue(it > stop, "$stop -> $it"); stop = it }
            peak = ProfitLock.nextPeak(peak, px, hi)
        }
        // The best was 372 (+24%: the trail keeps 65% of 72 = 346.80; the ladder's +45 rung locks +30 = 330).
        assertEquals(346.8, ProfitLock.pineRaise(300.0, ref, trail, 0.0, peak, stop, 350.0) ?: stop, 1e-9)
    }

    @Test fun theBestFromTheMinuteHighsLocksWhatOneSampledPriceMissed() {
        // 8 Oct #3: FINNIFTY 24800PE bought at 337.22; the app's sample said 354.00, the minute high was 359.05. Both earn only
        // breakeven after charges (the lock's rungs are unchanged), and the stop rests there instead of selling after the fact.
        val cost = 1.59
        val ref = ProfitLock.pineReference(60.0, 30.0)
        val fromSample = ProfitLock.pineRaise(337.22, ref, trail, cost, 354.0, ProfitLock.pineBase(337.22, 30.0), 350.0)
        val fromHigh = ProfitLock.pineRaise(337.22, ref, trail, cost, ProfitLock.nextPeak(337.22, 350.0, 359.05), ProfitLock.pineBase(337.22, 30.0), 350.0)
        assertEquals(338.85, fromSample)
        assertEquals(338.85, fromHigh)
    }

    @Test fun whereTheRestingStopBelongs() {
        val ref = 60.0
        assertEquals(315.0, ProfitLock.pineRestingStop(300.0, 270.0, ref, trail, 0.0, 330.0))
        assertEquals(270.0, ProfitLock.pineRestingStop(300.0, 270.0, ref, trail, 0.0, 305.0), "below a rung: the stop-loss")
        assertNull(ProfitLock.pineRestingStop(300.0, null, ref, trail, 0.0, 305.0))
        assertEquals(315.0, ProfitLock.pineRestingStop(300.0, null, null, trail, 0.0, 330.0))
    }
}
