package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoSideTest {
    private val sweepPut = AutoSide.Held.option("ORB Sweep", "BANKNIFTY26OCT52000PE", "BANKNIFTY", "PE", long = true)
    private val orbCall = AutoSide.Held.option("ORB", "BANKNIFTY26OCT52000CE", "BANKNIFTY", "CE", long = true)

    @Test fun boss06OctTheArmsNeverTradeAgainstEachOther() {
        // 12:30 ORB Sweep bought a BankNifty put; 12:35 ORB wanted a call: refused.
        assertEquals("opposite_position_open: ORB Sweep holds BANKNIFTY26OCT52000PE",
            AutoSide.check("BANKNIFTY", AutoSide.direction("CE", true), listOf(sweepPut)))
        // Had ORB's call been held, Liquidity 15m's call at 12:46 is refused too: one an index a side.
        assertEquals("same_side_already_held: ORB holds BANKNIFTY26OCT52000CE",
            AutoSide.check("BANKNIFTY", 1, listOf(orbCall)))
    }

    @Test fun anotherIndexOrNothingHeldIsFree() {
        assertNull(AutoSide.check("NIFTY", 1, listOf(sweepPut)))
        assertNull(AutoSide.check("BANKNIFTY", 1, emptyList()))
        assertNull(AutoSide.check("banknifty", 0, listOf(sweepPut)), "a neutral entry is never refused")
        assertNull(AutoSide.check("BANKNIFTY", 1, listOf(AutoSide.Held("Straddle", "X", "BANKNIFTY", 0, "a neutral position"))),
            "a neutral holding never refuses one")
    }

    @Test fun theOppositeSideIsSaidFirst() {
        assertTrue(AutoSide.check("BANKNIFTY", -1, listOf(sweepPut, orbCall))!!.startsWith("opposite_position_open: ORB holds"))
    }

    @Test fun shortsLeanByTheirDelta() {
        assertEquals(1, AutoSide.direction("CE", true))
        assertEquals(-1, AutoSide.direction("PE", true))
        assertEquals(1, AutoSide.direction("PE", false), "a sold put leans up")
        assertEquals(-1, AutoSide.direction("CE", false), "a sold call leans down")
        assertEquals(0, AutoSide.direction("FUT", true))
        assertEquals(1, AutoSide.direction("call", true)); assertEquals(-1, AutoSide.direction("put", true))
        val shortPut = AutoSide.Held.option("Strategy A", "NIFTY26OCT24500PE", "NIFTY", "PE", long = false)
        assertEquals("a short put", shortPut.what)
        assertTrue(AutoSide.check("NIFTY", -1, listOf(shortPut))!!.startsWith("opposite_position_open"), "a long put against a short put")
        assertEquals(0, AutoSide.net(listOf(1, -1)))
        assertEquals(1, AutoSide.net(listOf(1, 1, -1)))
        assertEquals(-1, AutoSide.net(listOf(-1)))
    }

    @Test fun theIndexAndSideAreReadFromTheSymbol() {
        assertEquals("BANKNIFTY", AutoSide.underlyingOf("BANKNIFTY26OCT52000CE"))
        assertEquals("BANKNIFTY", AutoSide.underlyingOf("NFO:BANKNIFTY26OCT52000CE"))
        assertEquals("NIFTY", AutoSide.underlyingOf("NIFTY26OCT24500PE"))
        assertEquals("FINNIFTY", AutoSide.underlyingOf("finnifty-liq-23000ce"))
        assertEquals("MIDCPNIFTY", AutoSide.underlyingOf("MIDCPNIFTY26OCT12000CE"))
        assertNull(AutoSide.underlyingOf("RELIANCE"))
        assertEquals("CE", AutoSide.rightOf("BANKNIFTY26OCT52000CE"))
        assertEquals("PE", AutoSide.rightOf("nifty26oct24500pe"))
        assertNull(AutoSide.rightOf("BANKNIFTY26OCTFUT"))
    }

    @Test fun theRefusalsReadInPlainWords() {
        val s = AutoSide.check("BANKNIFTY", 1, listOf(sweepPut))!!
        assertTrue(AutoSide.refused(s))
        assertFalse(AutoSide.refused("entered")); assertFalse(AutoSide.refused(null))
        assertEquals("Not entered: ORB Sweep holds BANKNIFTY26OCT52000PE the other way on this index (no automatic trade against another).",
            AutoSide.describe(s))
        assertTrue(AutoSide.describe(AutoSide.check("BANKNIFTY", 1, listOf(orbCall))!!).contains("one automatic position an index a side"))
        assertEquals("entered", AutoSide.describe("entered"))
    }

    @Test fun bossesOwnOrderIsOnlyWarned() {
        assertEquals("ORB holds a call on BankNifty; this put works against it.",
            AutoSide.warn("BANKNIFTY", -1, AutoSide.orderWords("PE", buy = true), listOf(orbCall)))
        assertNull(AutoSide.warn("BANKNIFTY", 1, AutoSide.orderWords("CE", buy = true), listOf(orbCall)), "the same way: no word")
        assertNull(AutoSide.warn("NIFTY", -1, "this put", listOf(orbCall)))
        assertNull(AutoSide.warn("BANKNIFTY", 0, "this future", listOf(orbCall)))
        assertEquals("this short call", AutoSide.orderWords("CE", buy = false))
        assertEquals("Pine #2 holds a put on XYZ; this call works against it.",
            AutoSide.warn("XYZ", 1, "this call", listOf(AutoSide.Held.option("Pine #2", "XYZ1PE", "XYZ", "PE", true))))
    }
}
