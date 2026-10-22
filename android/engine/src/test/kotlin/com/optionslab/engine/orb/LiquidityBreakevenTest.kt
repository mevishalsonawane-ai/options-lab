package com.optionslab.engine.orb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boss's breakeven at +10% for Liquidity (22 Oct): armed only at +10%, the stop at the price paid plus the round trip's charges
 * (on the tick, rounded up), never moved down, never at or above the price now, from the next look on; paper Liquidity entries
 * only; and the labels that keep its trades out of the plain-rules record.
 */
class LiquidityBreakevenTest {
    private val t = LiquidityBreakeven.TRIGGER

    @Test fun notArmedUnderTenPercent() {
        assertNull(LiquidityBreakeven.level(172.95, 190.0, 0.8, t))             // +9.9%
        assertNull(LiquidityBreakeven.level(172.95, null, 0.8, t))
        assertNull(LiquidityBreakeven.level(172.95, 210.0, 0.8, null))          // no rule on this trade
        assertNull(LiquidityBreakeven.level(0.0, 210.0, 0.8, t))
        assertNull(LiquidityBreakeven.level(172.95, Double.NaN, 0.8, t))
    }

    @Test fun armedAtTenPercentTheStopIsThePricePaidPlusCharges() {
        // 22 Oct's MIDCPNIFTY put: bought 172.95, best about 210 (+21.7%): the stop moves to 172.95 + 0.80 = 173.75.
        assertEquals(173.75, LiquidityBreakeven.level(172.95, 210.0, 0.8, t)!!, 1e-9)
        assertEquals(173.75, LiquidityBreakeven.level(172.95, 172.95 * 1.10, 0.8, t)!!, 1e-9)   // exactly +10% arms
        assertEquals(173.80, LiquidityBreakeven.level(172.95, 210.0, 0.83, t)!!, 1e-9)          // rounded UP to the tick
        // Broken charges count as none (never a stop under the price paid).
        assertEquals(172.95, LiquidityBreakeven.level(172.95, 210.0, Double.NaN, t)!!, 1e-9)
        assertEquals(172.95, LiquidityBreakeven.level(172.95, 210.0, -3.0, t)!!, 1e-9)
    }

    @Test fun aNewBestCountsFromTheNextLook() {
        // The look that first sees +10% does not sell on its own price: the exit reads the best from BEFORE it.
        assertFalse(LiquidityBreakeven.exits(100.0, 105.0, 100.5, 0.6, t))
        assertTrue(LiquidityBreakeven.exits(100.0, 111.0, 100.6, 0.6, t))
        assertTrue(LiquidityBreakeven.exits(100.0, 111.0, 95.0, 0.6, t))         // a gap under it still sells
        assertFalse(LiquidityBreakeven.exits(100.0, 111.0, 101.0, 0.6, t))
    }

    @Test fun theRestingStopMovesUpOnlyAndStaysUnderThePrice() {
        assertEquals(100.6, LiquidityBreakeven.raise(100.0, 111.0, 0.6, t, 85.0, 105.0)!!, 1e-9)
        assertNull(LiquidityBreakeven.raise(100.0, 111.0, 0.6, t, 100.6, 105.0))     // already there
        assertNull(LiquidityBreakeven.raise(100.0, 111.0, 0.6, t, 102.0, 105.0))     // never down (a trail rests higher)
        assertNull(LiquidityBreakeven.raise(100.0, 111.0, 0.6, t, 85.0, 100.6))      // not under the price: the app sells it
        assertNull(LiquidityBreakeven.raise(100.0, 108.0, 0.6, t, 85.0, 105.0))      // not armed
    }

    @Test fun onlyPaperLiquidityEntriesWithTheSwitchOnCarryIt() {
        assertEquals(t, LiquidityBreakeven.forEntry(LiquidityRules.MID5, live = false, switchOn = true))
        assertNull(LiquidityBreakeven.forEntry(LiquidityRules.MID5, live = true, switchOn = true))    // never live
        assertNull(LiquidityBreakeven.forEntry(LiquidityRules.MID5, live = false, switchOn = false))
        assertNull(LiquidityBreakeven.forEntry(OrbRules.ORB, live = false, switchOn = true))          // Liquidity only
    }

    @Test fun labelsKeepItsTradesApart() {
        assertEquals(LiquidityTrail.PLAIN, LiquidityBreakeven.label(null, null))
        assertEquals(LiquidityTrail.TRAILED, LiquidityBreakeven.label(500.0, null))
        assertEquals("with breakeven at +10%", LiquidityBreakeven.label(null, t))
        assertEquals("with ₹500 trail and breakeven at +10%", LiquidityBreakeven.label(500.0, t))
        assertFalse(LiquidityBreakeven.ownRules(null, null))
        assertTrue(LiquidityBreakeven.ownRules(null, t))
        assertTrue(LiquidityBreakeven.ownRules(500.0, null))
    }
}
