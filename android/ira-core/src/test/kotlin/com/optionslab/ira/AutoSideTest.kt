package com.optionslab.ira

import com.optionslab.engine.orb.ArmPriority.Rank
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

    // Boss's 07 Oct decision (research/HUNT_H21.md): Liquidity 15+5 has priority over ORB, ORB Fresh, ORB Sweep and Range Fade.
    private val orbHeld = AutoSide.Held.option("ORB", "BANKNIFTY26OCT52000CE", "BANKNIFTY", "CE", long = true, rank = Rank.OLD_ARM)
    private val fadeHeld = AutoSide.Held.option("Range Fade", "BANKNIFTY26OCT52000PE", "BANKNIFTY", "PE", long = true, rank = Rank.OLD_ARM)
    private val liqHeld = AutoSide.Held.option("Liquidity 15m", "BANKNIFTY26OCT51900CE", "BANKNIFTY", "CE", long = true, rank = Rank.LIQUIDITY)

    @Test fun aLiquidityEntryWhileAnOrbArmHoldsBankNiftyIsTaken() {
        assertNull(AutoSide.check("BANKNIFTY", 1, listOf(orbHeld), Rank.LIQUIDITY), "the same way: taken beside ORB")
        assertNull(AutoSide.check("BANKNIFTY", 1, listOf(fadeHeld), Rank.LIQUIDITY), "the other way: taken beside Range Fade")
        assertNull(AutoSide.check("BANKNIFTY", -1, listOf(orbHeld, fadeHeld), Rank.LIQUIDITY, live = true), "a live entry beside paper arms")
        assertEquals("Liquidity 15m is not held back by ORB's BANKNIFTY26OCT52000CE, Range Fade's BANKNIFTY26OCT52000PE: " +
            "Liquidity has priority over ORB arms.", AutoSide.priorityNote("Liquidity 15m", "BANKNIFTY", 1, listOf(orbHeld, fadeHeld), Rank.LIQUIDITY))
        assertNull(AutoSide.priorityNote("Liquidity 15m", "BANKNIFTY", 1, emptyList(), Rank.LIQUIDITY), "nothing set aside: no word")
        assertNull(AutoSide.priorityNote("Liquidity 15m", "BANKNIFTY", 0, listOf(orbHeld), Rank.LIQUIDITY))
        // Everything else still counts against Liquidity: another Liquidity book, Pine, Solo, Strategies, the Hero arm.
        assertEquals("same_side_already_held: Liquidity 15m holds BANKNIFTY26OCT51900CE",
            AutoSide.check("BANKNIFTY", 1, listOf(liqHeld), Rank.LIQUIDITY))
        assertEquals("opposite_position_open: Pine #2 holds BANKNIFTY26OCT52000PE",
            AutoSide.check("BANKNIFTY", 1, listOf(orbHeld, AutoSide.Held.option("Pine #2", "BANKNIFTY26OCT52000PE", "BANKNIFTY", "PE", true)), Rank.LIQUIDITY))
        assertNull(AutoSide.priorityNote("Liquidity 15m", "BANKNIFTY", 1, listOf(orbHeld, liqHeld), Rank.LIQUIDITY), "refused anyway: no word")
    }

    @Test fun anOrbEntryWhileLiquidityHoldsBankNiftyIsRefusedAndSaysWhy() {
        val same = AutoSide.check("BANKNIFTY", 1, listOf(liqHeld), Rank.OLD_ARM)!!
        assertEquals("same_side_already_held: Liquidity 15m holds BANKNIFTY26OCT51900CE; Liquidity has priority over ORB arms", same)
        assertTrue(AutoSide.refused(same))
        assertEquals("Not entered: Liquidity 15m holds BANKNIFTY26OCT51900CE the same way on this index (one automatic position an " +
            "index a side). Liquidity has priority over ORB arms.", AutoSide.describe(same))
        val other = AutoSide.check("BANKNIFTY", -1, listOf(liqHeld), Rank.OLD_ARM)!!
        assertEquals("Not entered: Liquidity 15m holds BANKNIFTY26OCT51900CE the other way on this index (no automatic trade " +
            "against another). Liquidity has priority over ORB arms.", AutoSide.describe(other))
        // The old arms keep their own guard among themselves, without the priority words.
        assertEquals("same_side_already_held: ORB holds BANKNIFTY26OCT52000CE", AutoSide.check("BANKNIFTY", 1, listOf(orbHeld), Rank.OLD_ARM))
        assertEquals("entered" + AutoSide.PRIORITY, AutoSide.describe("entered" + AutoSide.PRIORITY), "anything else as it is")
    }

    @Test fun liveGatesAreUnchangedTwoLivePositionsStillRefuse() {
        val liveOrb = orbHeld.copy(live = true)
        assertEquals("same_side_already_held: ORB holds BANKNIFTY26OCT52000CE",
            AutoSide.check("BANKNIFTY", 1, listOf(liveOrb), Rank.LIQUIDITY, live = true), "never two live automatic positions on one index")
        assertNull(AutoSide.check("BANKNIFTY", 1, listOf(liveOrb), Rank.LIQUIDITY, live = false), "a paper Liquidity entry beside it")
        assertNull(AutoSide.priorityNote("Liquidity 15m", "BANKNIFTY", 1, listOf(liveOrb), Rank.LIQUIDITY, live = true))
        // Every other trader is unchanged: the default rank counts everything, as before 07 Oct.
        assertEquals("same_side_already_held: ORB holds BANKNIFTY26OCT52000CE", AutoSide.check("BANKNIFTY", 1, listOf(orbHeld)))
        assertTrue(AutoSide.check("BANKNIFTY", -1, listOf(orbHeld))!!.startsWith(AutoSide.OPPOSITE))
        assertEquals(Rank.OTHER, sweepPut.rank); assertFalse(sweepPut.live)
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
