package com.optionslab.engine.orb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Boss's Rs 500 give-back trail (R19b, failed research: an opt-in paper switch only). */
class LiquidityTrailTest {
    private val mid = 120          // MIDCPNIFTY lot
    private val bn = 30            // BANKNIFTY lot
    private val rs = LiquidityTrail.GIVE_BACK_RS

    @Test fun rupeesPerLotBecomePointsForEachLotSize() {
        assertEquals(500.0 / 120, LiquidityTrail.points(rs, mid)!!, 1e-9)
        assertEquals(500.0 / 30, LiquidityTrail.points(rs, bn)!!, 1e-9)
        assertEquals(500.0 / 35, LiquidityTrail.points(rs, 35)!!, 1e-9)
        assertEquals(500.0 / 15, LiquidityTrail.points(rs, 15)!!, 1e-9)
        // the same rupees a lot whatever the lot: the trigger sits 500 / lot under the best price
        assertEquals(523.35, LiquidityTrail.level(500.0, 540.0, bn, rs)!!, 1e-9)       // 540 - 16.67 = 523.33 -> up to 523.35
        assertEquals(506.70, LiquidityTrail.level(500.0, 540.0, 15, rs)!!, 1e-9)       // 540 - 33.33 = 506.67 -> 506.70
    }

    @Test fun waitsForPlus500ALotBeforeItActs() {
        assertNull(LiquidityTrail.level(195.45, 195.45 + 4.0, mid, rs))                 // +Rs 480 a lot: not yet
        assertNull(LiquidityTrail.level(195.45, 195.45, mid, rs))                       // at the entry
        val at = LiquidityTrail.level(195.45, 195.45 + 500.0 / 120, mid, rs)            // exactly +Rs 500: breakeven
        assertEquals(195.45, at!!, 1e-9)
    }

    @Test fun bossesThirteenOctoberTrade() {
        // MIDCPNIFTY PE, entry 195.45, lot 120, best about +Rs 2,500 (216.28): the trail rests at 212.15, about +Rs 2,000 a lot.
        val peak = 195.45 + 2500.0 / 120
        val l = LiquidityTrail.level(195.45, peak, mid, rs)!!
        assertEquals(212.15, l, 1e-9)
        assertEquals(2004.0, (l - 195.45) * 120, 1.0)
        assertTrue(LiquidityTrail.exits(195.45, peak, 212.0, mid, rs))
        assertFalse(LiquidityTrail.exits(195.45, peak, 213.0, mid, rs))
    }

    @Test fun neverAboveTheBestPriceAndAlwaysOnTheTick() {
        var p = 200.0
        while (p < 260.0) {
            val l = LiquidityTrail.level(195.45, p, mid, rs, 0.05)
            if (l != null) {
                assertTrue(l <= p + 1e-9)
                assertEquals(0.0, Math.round(l / 0.05) - l / 0.05, 1e-6)
            }
            p += 0.37
        }
        // a coarse tick that would round above the peak is held at the peak
        assertEquals(204.17, LiquidityTrail.level(200.0, 204.17, mid, rs, tick = 5.0)!!, 1e-9)
    }

    @Test fun neverLowersTheStop() {
        // the stop already above the trail: no move
        assertNull(LiquidityTrail.raise(200.0, 210.0, mid, rs, current = 207.0, ltp = 209.0))
        // equal: no move
        val l = LiquidityTrail.level(200.0, 210.0, mid, rs)!!
        assertNull(LiquidityTrail.raise(200.0, 210.0, mid, rs, current = l, ltp = 209.0))
        // below: moves up, to the trail
        assertEquals(l, LiquidityTrail.raise(200.0, 210.0, mid, rs, current = 170.0, ltp = 209.0)!!, 1e-9)
        // no resting stop known: moves to the trail
        assertEquals(l, LiquidityTrail.raise(200.0, 210.0, mid, rs, current = null, ltp = 209.0)!!, 1e-9)
        // a rising best only ever raises it
        var cur: Double? = 170.0
        var best = 200.0
        for (price in listOf(205.0, 208.0, 207.0, 212.0, 211.0, 215.0)) {
            best = maxOf(best, price)
            LiquidityTrail.raise(200.0, best, mid, rs, cur, ltp = price + 1.0)?.let { assertTrue(it > cur!!); cur = it }
        }
        assertEquals(LiquidityTrail.level(200.0, 215.0, mid, rs)!!, cur!!, 1e-9)
    }

    @Test fun aStopMustRestUnderThePriceNow() {
        // the price is already at or under the trail: the app's own check sells it instead of a stop above the price
        assertNull(LiquidityTrail.raise(200.0, 210.0, mid, rs, current = 170.0, ltp = 205.0))
        assertTrue(LiquidityTrail.exits(200.0, 210.0, 205.0, mid, rs))
    }

    @Test fun aGapThroughTheTrailFillsAtTheOpen() {
        val trig = LiquidityTrail.level(200.0, 210.0, mid, rs)!!                          // 205.85
        // the resting SL-M at the exchange: a bar that opens under the trigger fills at its open, else at the trigger
        assertEquals(203.0, ProfitLock.sellStopFill(trig, open = 203.0, low = 202.0)!!, 1e-9)
        assertEquals(trig, ProfitLock.sellStopFill(trig, open = 208.0, low = 205.0)!!, 1e-9)
        assertNull(ProfitLock.sellStopFill(trig, open = 208.0, low = 206.0))
    }

    @Test fun aMissingPeakOrLotOrGiveBackMeansNoTrail() {
        assertNull(LiquidityTrail.level(200.0, null, mid, rs))
        assertNull(LiquidityTrail.level(200.0, Double.NaN, mid, rs))
        assertNull(LiquidityTrail.level(200.0, 220.0, null, rs))
        assertNull(LiquidityTrail.level(200.0, 220.0, 0, rs))
        assertNull(LiquidityTrail.level(200.0, 220.0, mid, null))
        assertNull(LiquidityTrail.level(200.0, 220.0, mid, -5.0))
        assertNull(LiquidityTrail.level(200.0, 220.0, mid, Double.NaN))
        assertNull(LiquidityTrail.level(0.0, 220.0, mid, rs))
        assertNull(LiquidityTrail.points(rs, null))
        assertFalse(LiquidityTrail.exits(200.0, null, 1.0, mid, rs))
        assertNull(LiquidityTrail.raise(200.0, null, mid, rs, 170.0, 209.0))
        // a missing best keeps the stop where it is
        assertEquals(170.0, LiquidityTrail.restingStop(200.0, 170.0, null, mid, rs)!!, 1e-9)
    }

    @Test fun theRestingStopIsTheHigherOfItsOwnAndTheTrail() {
        val l = LiquidityTrail.level(200.0, 210.0, mid, rs)!!
        assertEquals(l, LiquidityTrail.restingStop(200.0, 170.0, 210.0, mid, rs)!!, 1e-9)
        assertEquals(209.0, LiquidityTrail.restingStop(200.0, 209.0, 210.0, mid, rs)!!, 1e-9)
        assertEquals(l, LiquidityTrail.restingStop(200.0, null, 210.0, mid, rs)!!, 1e-9)
        assertNull(LiquidityTrail.restingStop(200.0, null, 201.0, mid, rs))
    }

    @Test fun onlyPaperLiquidityEntriesWithTheSwitchOnCarryIt() {
        val liq = LiquidityRules.MID5
        assertEquals(rs, LiquidityTrail.forEntry(liq, live = false, switchOn = true))
        assertNull(LiquidityTrail.forEntry(liq, live = true, switchOn = true), "never live")
        assertNull(LiquidityTrail.forEntry(liq, live = false, switchOn = false), "off by default")
        assertNull(LiquidityTrail.forEntry(Arm("orb", "ORB"), live = false, switchOn = true), "Liquidity only")
        assertEquals(LiquidityTrail.TRAILED, LiquidityTrail.label(rs))
        assertEquals(LiquidityTrail.PLAIN, LiquidityTrail.label(null))
    }

    // ---- Boss's rule from 13 Oct, later (R19c): from +Rs 1,000 a lot, Rs 500 below the best; never under +Rs 500 ----

    private val from = LiquidityTrail.ACTIVATE_RS

    @Test fun bossesRuleWaitsForPlus1000ALot() {
        // BANKNIFTY lot 30: Rs 1,000 = 33.33 points, Rs 500 = 16.67
        assertNull(LiquidityTrail.level(100.0, 130.0, bn, rs, activateRs = from))                       // +Rs 900: not yet
        assertNull(LiquidityTrail.level(100.0, 133.30, bn, rs, activateRs = from))                      // +Rs 999
        assertEquals(116.70, LiquidityTrail.level(100.0, 133.35, bn, rs, activateRs = from)!!, 1e-9)    // 133.35 - 16.67 -> 116.70
        // R19b's first rule (no activation stored) still starts at +Rs 500: trades entered before keep their rule
        assertEquals(113.35, LiquidityTrail.level(100.0, 130.0, bn, rs)!!, 1e-9)
    }

    @Test fun bossesRuleNeverKeepsLessThanPlus500AndOnlyRises() {
        var last = Double.NEGATIVE_INFINITY
        for (i in 0..400) {
            val peak = 100.0 + 1000.0 / bn + i * 0.1
            val l = LiquidityTrail.level(100.0, peak, bn, rs, activateRs = from)!!
            assertTrue((l - 100.0) * bn >= 500.0 - 1e-6, "keeps at least +Rs 500 a lot")
            assertTrue(l <= peak + 1e-9 && l >= last - 1e-9, "never above the best, never lower")
            assertEquals(0.0, Math.round(l / 0.05) * 0.05 - l, 1e-9)
            last = l
        }
    }

    @Test fun bossesRuleOnTodaysMidcpTradeAndPointsPerLot() {
        // MIDCPNIFTY lot 120: +Rs 1,000 = 8.33 points (activates at 203.78), best 216.3 -> rests at 212.15 (+Rs 2,004 a lot)
        assertNull(LiquidityTrail.level(195.45, 203.75, mid, rs, activateRs = from))
        assertEquals(212.15, LiquidityTrail.level(195.45, 216.3, mid, rs, activateRs = from)!!, 1e-9)
        assertTrue(LiquidityTrail.exits(195.45, 216.3, 212.0, mid, rs, activateRs = from))
        assertFalse(LiquidityTrail.exits(195.45, 216.3, 212.5, mid, rs, activateRs = from))
        assertEquals(1000.0 / 30, LiquidityTrail.points(from, 30)!!, 1e-9)
        assertEquals(1000.0 / 35, LiquidityTrail.points(from, 35)!!, 1e-9)
        assertEquals(1000.0 / 65, LiquidityTrail.points(from, 65)!!, 1e-9)
    }

    @Test fun bossesRuleAsTheRestingStopAndMissingInputs() {
        // up only: a stop already above it is never moved; a level not under the price is the app's own sale
        assertNull(LiquidityTrail.raise(100.0, 130.0, bn, rs, current = 85.0, ltp = 129.0, activateRs = from), "not active")
        assertEquals(123.35, LiquidityTrail.raise(100.0, 140.0, bn, rs, current = 85.0, ltp = 139.0, activateRs = from)!!, 1e-9)
        assertNull(LiquidityTrail.raise(100.0, 140.0, bn, rs, current = 124.0, ltp = 139.0, activateRs = from), "never down")
        assertNull(LiquidityTrail.raise(100.0, 140.0, bn, rs, current = 85.0, ltp = 123.0, activateRs = from), "gapped through it")
        assertEquals(123.35, LiquidityTrail.restingStop(100.0, 85.0, 140.0, bn, rs, activateRs = from)!!, 1e-9)
        assertEquals(85.0, LiquidityTrail.restingStop(100.0, 85.0, 130.0, bn, rs, activateRs = from)!!, 1e-9)
        // a missing peak or lot: no trail; a broken activation falls back to the give-back
        assertNull(LiquidityTrail.level(100.0, null, bn, rs, activateRs = from))
        assertNull(LiquidityTrail.level(100.0, 140.0, null, rs, activateRs = from))
        assertNull(LiquidityTrail.level(100.0, Double.NaN, bn, rs, activateRs = from))
        assertEquals(113.35, LiquidityTrail.level(100.0, 130.0, bn, rs, activateRs = Double.NaN)!!, 1e-9)
    }

    @Test fun aNewPaperEntryCarriesBossesActivation() {
        val liq = LiquidityRules.MID5
        assertEquals(from, LiquidityTrail.forEntryFrom(LiquidityTrail.forEntry(liq, live = false, switchOn = true)))
        assertNull(LiquidityTrail.forEntryFrom(LiquidityTrail.forEntry(liq, live = true, switchOn = true)), "never live")
        assertNull(LiquidityTrail.forEntryFrom(null))
    }
}
