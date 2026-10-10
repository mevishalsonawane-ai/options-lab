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

    @Test fun theBreakevenRungCoversTheRoundTripsCharges() {
        // Boss's 06 Oct: Range Fade sold at +0.5 via the lock, a loss after ~Rs 70 of charges. One lot of 30 at 200.
        val c = ProfitLock.roundTripPerUnit(200.0, 30)
        assertTrue(c > 1.5 && c < 3.0, "about 2 points a unit: $c")
        assertNull(ProfitLock.level(200.0, 40.0, 209.95, c), "below the first rung nothing is locked")
        assertEquals(200.0 + c, ProfitLock.level(200.0, 40.0, 210.0, c)!!, 1e-9, "breakeven after charges, not the price paid")
        // The higher rungs are unchanged (they already clear the charges), and none is ever lowered by the charges.
        assertEquals(210.0, ProfitLock.level(200.0, 40.0, 220.0, c)!!, 1e-9)
        assertEquals(220.0, ProfitLock.level(200.0, 40.0, 230.0, c)!!, 1e-9)
        // Sold at the old breakeven (+0.5) is no longer possible: the lock sells at or above entry + charges.
        assertTrue(ProfitLock.exits(200.0, 40.0, 212.0, 200.5, c))
        assertFalse(ProfitLock.exits(200.0, 40.0, 212.0, 200.0 + c + 0.1, c), "still above the after-charges breakeven")
        // The plain ladder (the backtests, the replay) is as before.
        assertEquals(200.0, ProfitLock.level(200.0, 40.0, 210.0))
        // Negative or broken charges count as none.
        assertEquals(200.0, ProfitLock.level(200.0, 40.0, 210.0, -5.0)!!, 1e-9)
        assertEquals(200.0, ProfitLock.level(200.0, 40.0, 210.0, Double.NaN)!!, 1e-9)
    }

    @Test fun chargesAboveTheFirstRungNeverSellAtOnceOrWidenRisk() {
        // Charges bigger than the first rung's gain: that rung does not count (its level would not sit below the best).
        assertNull(ProfitLock.level(200.0, 40.0, 210.0, 12.0))
        // At the second rung the level is the higher of +10 and the charges, still below the best.
        assertEquals(212.0, ProfitLock.level(200.0, 40.0, 220.0, 12.0)!!, 1e-9)
        // A higher best never lowers the lock, and every lock sits at or above entry + charges and below the best.
        for (cost in listOf(0.0, 2.0, 9.0, 15.0)) {
            var last = Double.NEGATIVE_INFINITY
            for (pk in (1..80).map { 200.0 + it * 0.5 }) {
                val l = ProfitLock.level(200.0, 40.0, pk, cost) ?: continue
                assertTrue(l >= 200.0 + cost - 1e-9 && l < pk, "level $l at best $pk, charges $cost")
                assertTrue(l >= last - 1e-9, "never lowered ($last -> $l)")
                last = l
            }
        }
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

class ProfitLockTrailTest {
    private val trail = ProfitLock.Trail()
    private val cost = ProfitLock.roundTripPerUnit(200.0, 30)

    /** As PineAuto walks a holding: the lock from the best price seen BEFORE each look; the index of the sell, or null. */
    private fun walk(entry: Double, ref: Double?, t: ProfitLock.Trail?, path: List<Double>, qty: Int = 30): Int? {
        var peak = entry
        val c = ProfitLock.roundTripPerUnit(entry, qty)
        path.forEachIndexed { i, ltp ->
            if (ProfitLock.lockExits(entry, ref, t, c, peak, ltp)) return i
            peak = maxOf(peak, ltp)
        }
        return null
    }

    @Test fun theRoundTripsChargesComeFromTheFnoScheduleElseAFixedShare() {
        // One lot of 30 at 200: Rs 20 a leg + STT, exchange, stamp, GST: about Rs 61.5, so about 2.05 a unit.
        assertEquals(2.05, cost, 0.05)
        assertEquals(0.6, ProfitLock.roundTripPerUnit(200.0, 0), 1e-9, "no quantity: 0.3% of the buy price")
        assertEquals(0.0, ProfitLock.roundTripPerUnit(0.0, 30))
        assertEquals(0.0, ProfitLock.roundTripPerUnit(Double.NaN, 30))
    }

    @Test fun bossesTenPercentThatFellToTwoIsSoldAtAboutFive() {
        // Bought at 200, no stop, no target: up to 220 (+10%), then back down to 204 (+2%).
        val path = listOf(204.0, 210.0, 215.0, 220.0, 216.0, 212.0, 209.0, 206.0, 204.0)
        assertEquals(210.0, ProfitLock.lockLevel(200.0, null, trail, cost, 220.0)!!, 1e-9, "+10% best: half of it kept, +5%")
        val i = walk(200.0, null, trail, path)!!
        assertEquals(6, i, "sold at the first look at or below 210 (+5%): 209, not 204")
        // The same script without the lock would have held to +2%.
        assertNull(walk(200.0, null, null, path))
    }

    @Test fun fromFivePercentTheStopIsBreakevenAfterCharges() {
        assertNull(ProfitLock.trailLevel(200.0, 209.9, trail, cost), "+4.95%: nothing yet")
        assertEquals(200.0 + cost, ProfitLock.trailLevel(200.0, 210.0, trail, cost)!!, 1e-9)
        assertEquals(200.0 + cost, ProfitLock.trailLevel(200.0, 215.0, trail, cost)!!, 1e-9, "+7.5%")
        assertEquals(208.0, ProfitLock.trailLevel(200.0, 216.0, trail, cost)!!, 1e-9, "+8%: half of 16 kept")
        assertEquals(1, walk(200.0, null, trail, listOf(212.0, 201.0)), "+6% then back to just above the buy price: sold")
        // Charges bigger than the gain at the rung: no breakeven stop above the best price.
        assertNull(ProfitLock.trailLevel(200.0, 210.0, trail, 12.0))
        assertEquals(200.0, ProfitLock.trailLevel(200.0, 210.0, trail, -3.0)!!, 1e-9, "negative charges count as none")
    }

    @Test fun theShareKeptRisesAt20And40Percent() {
        assertEquals(200.0 + 0.5 * 38, ProfitLock.trailLevel(200.0, 238.0, trail, cost)!!, 1e-9)
        assertEquals(200.0 + 0.65 * 40, ProfitLock.trailLevel(200.0, 240.0, trail, cost)!!, 1e-9)
        assertEquals(200.0 + 0.75 * 80, ProfitLock.trailLevel(200.0, 280.0, trail, cost)!!, 1e-9)
    }

    @Test fun noGainNoTrailAndABrokenBuyPriceNever() {
        assertNull(ProfitLock.trailLevel(200.0, 200.0, trail, cost))
        assertNull(ProfitLock.trailLevel(200.0, 190.0, trail, cost))
        assertNull(ProfitLock.trailLevel(0.0, 50.0, trail, cost))
        assertNull(ProfitLock.trailLevel(200.0, Double.NaN, trail, cost))
        assertNull(ProfitLock.trailLevel(200.0, 300.0, ProfitLock.Trail(0.0, emptyList()), cost), "every rung off")
        assertNull(ProfitLock.trailLevel(200.0, 300.0, ProfitLock.Trail(0.0, listOf(ProfitLock.Trail.Step(0.0, 50.0))), cost))
    }

    @Test fun theLockIsTheHigherOfTheTargetLadderAndTheTrail() {
        // Target 100 at 200: best 260 (60% of the way) -> ladder 225; trail +30% -> 65% of 60 = 239.
        assertEquals(239.0, ProfitLock.lockLevel(200.0, 100.0, trail, cost, 260.0)!!, 1e-9)
        // Target 40: best 230 (75%) -> ladder 220; trail +15% -> 50% of 30 = 215.
        assertEquals(220.0, ProfitLock.lockLevel(200.0, 40.0, trail, cost, 230.0)!!, 1e-9)
        assertEquals(220.0, ProfitLock.lockLevel(200.0, 40.0, null, cost, 230.0)!!, 1e-9, "ladder only")
        assertEquals(215.0, ProfitLock.lockLevel(200.0, null, trail, cost, 230.0)!!, 1e-9, "trail only")
        assertNull(ProfitLock.lockLevel(200.0, null, null, cost, 230.0))
        assertNull(ProfitLock.lockLevel(200.0, 400.0, trail, cost, 205.0), "neither has a rung")
        // A far target (+400) alone never protects +10%; with the trail it does.
        val path = listOf(210.0, 220.0, 212.0, 204.0)
        assertNull(walk(200.0, 400.0, null, path))
        assertEquals(3, walk(200.0, 400.0, trail, path))
    }

    @Test fun theLockNeverLowersAStopAndNeverWidensRisk() {
        // Every level is at or above the buy price (so above any premium stop under it) and below the best price seen,
        // and a higher best never lowers it.
        var last = Double.NEGATIVE_INFINITY
        for (pk in (1..200).map { 200.0 + it }) {
            val l = ProfitLock.lockLevel(200.0, 60.0, trail, cost, pk) ?: continue
            assertTrue(l > 200.0 - 1e-9 && l < pk, "level $l at best $pk")
            assertTrue(l >= last - 1e-9, "a higher best never lowers the lock ($last -> $l at $pk)")
            last = l
        }
        assertTrue(last > 200.0)
        assertFalse(ProfitLock.lockExits(200.0, null, trail, cost, 200.0, 150.0), "a loss is the stop's, not the lock's")
        assertFalse(ProfitLock.lockExits(200.0, null, trail, cost, 220.0, 211.0))
        assertTrue(ProfitLock.lockExits(200.0, null, trail, cost, 220.0, 210.0))
        assertNull(walk(200.0, null, trail, listOf(230.0)), "a rung counts from the next look on")
    }

    @Test fun theTrailsNumbersAreCleaned() {
        val t = ProfitLock.Trail(Double.NaN, listOf(ProfitLock.Trail.Step(30.0, 120.0), ProfitLock.Trail.Step(-4.0, 50.0),
            ProfitLock.Trail.Step(10.0, Double.NaN), ProfitLock.Trail.Step(5000.0, -2.0))).clean()
        assertEquals(0.0, t.breakevenPct)
        assertEquals(listOf(ProfitLock.Trail.Step(30.0, 95.0), ProfitLock.Trail.Step(0.0, 50.0), ProfitLock.Trail.Step(10.0, 0.0),
            ProfitLock.Trail.Step(1000.0, 0.0)), t.steps)
        // Out of order the highest level reached still counts: +30% with (30 -> 95%) and (10 -> 0%) keeps 95%.
        assertEquals(200.0 + 0.95 * 60, ProfitLock.trailLevel(200.0, 260.0, t, 2.0)!!, 1e-9)
        assertEquals(trail, trail.clean(), "the defaults are already clean")
        assertEquals(7.5, ProfitLock.Trail(7.5).clean().breakevenPct)
        assertEquals(0.0, ProfitLock.Trail(-1.0).clean().breakevenPct)
        assertEquals(0.0, ProfitLock.Trail(Double.POSITIVE_INFINITY).clean().breakevenPct)
    }

    @Test fun theTrailSaysWhatItDoes() {
        assertEquals("from +5% breakeven after charges; from +8% keeps 50% of the best gain, from +20% 65%, from +40% 75%", trail.describe())
        assertEquals("from +2.5% keeps 40% of the best gain", ProfitLock.Trail(0.0, listOf(ProfitLock.Trail.Step(2.5, 40.0))).describe())
        assertEquals("from +5% breakeven after charges", ProfitLock.Trail(5.0, listOf(ProfitLock.Trail.Step(0.0, 40.0))).describe())
        assertEquals("trail off", ProfitLock.Trail(0.0, emptyList()).describe())
    }
}
