package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 25 Oct (app audit): the loss stop's message says which positions made the day and when they were opposite bets. */
class LossBreakdownTest {
    @Test fun theIndexAndTheBetOfASymbol() {
        assertEquals("NIFTY", LossBreakdown.indexOf("NIFTY27OCT2622700CE"))
        assertEquals("MIDCPNIFTY", LossBreakdown.indexOf("MIDCPNIFTY27OCT2613225PE"))
        assertEquals("BANKNIFTY", LossBreakdown.indexOf("BANKNIFTY27OCT2655300PE"))
        assertEquals("FINNIFTY", LossBreakdown.indexOf("FINNIFTY27OCT2624550PE"))
        assertEquals("SENSEX", LossBreakdown.indexOf("SENSEX22OCT2672600PE"))
        assertNull(LossBreakdown.indexOf("SILVERMIC30NOV26FUT"))
        assertNull(LossBreakdown.indexOf("NATURALGAS23OCT26290PE"))
        assertNull(LossBreakdown.indexOf("RELIANCE"))
        assertEquals(1, LossBreakdown.betOf("NIFTY27OCT2622700CE"))
        assertEquals(-1, LossBreakdown.betOf("MIDCPNIFTY27OCT2613225PE"))
        assertEquals(0, LossBreakdown.betOf("SILVERMIC30NOV26FUT"))
    }

    /** 23 Oct: R52's NIFTY call and Solo 2.0's MIDCPNIFTY put both lost; the message names both and says they were opposite bets. */
    @Test fun the23OctStopNamesBothAndSaysTheyWereOppositeBets() {
        val w = LossBreakdown.words(listOf("MIDCPNIFTY27OCT2613225PE" to -3_000.0, "NIFTY27OCT2622700CE" to -3_194.75))!!
        assertTrue(w.startsWith("By position: NIFTY call -Rs 3,195, MIDCPNIFTY put -Rs 3,000."), w)
        assertTrue(w.contains("opposite bets"), w)
    }

    @Test fun sameWayBetsAndEdgeCases() {
        val same = LossBreakdown.words(listOf("NIFTY27OCT2622700CE" to -2_000.0, "BANKNIFTY27OCT2655000CE" to -4_000.0))!!
        assertEquals("By position: BANKNIFTY call -Rs 4,000, NIFTY call -Rs 2,000.", same)
        assertFalse(LossBreakdown.opposite(listOf("NIFTY27OCT2622700CE", "SILVERMIC30NOV26FUT")))
        assertNull(LossBreakdown.words(emptyList()))
        assertNull(LossBreakdown.words(listOf("NIFTY27OCT2622700CE" to 0.0, "X" to Double.NaN)))
        val many = LossBreakdown.words((1..6).map { "NIFTY27OCT26${22000 + it * 50}CE" to -100.0 * it }, max = 4)!!
        assertTrue(many.endsWith("and 2 more."), many)
        assertTrue(many.startsWith("By position: NIFTY call -Rs 600"), many)
        // A winner beside a loser is still listed (worst first).
        assertEquals("By position: NIFTY put -Rs 500, NIFTY call +Rs 200. These were opposite bets: a call (the market up) and a put " +
            "(the market down) on related indices. They partly cancel each other, so the day can lose on both sides; " +
            "each arm decides its side on its own.", LossBreakdown.words(listOf("NIFTY27OCT2622700CE" to 200.0, "NIFTY27OCT2622700PE" to -500.0)))
    }
}
