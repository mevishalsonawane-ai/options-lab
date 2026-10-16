package com.optionslab.ira

import java.time.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A position's open-trade change ([OpenChange]) and its arm's exits ([ArmExits]): 16 Oct, Boss's "+48.57%" that was +18.4%. */
class OpenChangeTest {
    @BeforeTest @AfterTest fun clean() = ArmExits.clear()

    @Test fun theOpenTradeAloneOnItsAverage() {
        // Boss's NIFTY19OCT2623050CE: LONG 65 @ 122.20, LTP 144.65. The day's P&L (+3,858, an earlier closed trade's +2,399
        // with it) over the open cost read +48.57%; the open trade is +18.37%.
        assertEquals(18.371522094926, OpenChange.pct(65, 122.20, 144.65)!!, 1e-9)
        assertEquals("+18.37%", OpenChange.line(65, 122.20, 144.65))
        assertEquals("-10.00%", OpenChange.line(75, 100.0, 90.0))
        assertEquals("+0.00%", OpenChange.line(75, 100.0, 100.0))
    }

    @Test fun aShortIsMirrored() {
        assertEquals("+12.50%", OpenChange.line(-75, 80.0, 70.0))
        assertEquals("-12.50%", OpenChange.line(-75, 80.0, 90.0))
    }

    @Test fun closedZeroAverageOrNoPriceHaveNone() {
        assertNull(OpenChange.pct(0, 122.20, 144.65), "closed")
        assertNull(OpenChange.pct(65, 0.0, 144.65), "zero average")
        assertNull(OpenChange.pct(65, -1.0, 144.65), "negative average")
        assertNull(OpenChange.pct(65, 122.20, 0.0), "no price yet")
        assertNull(OpenChange.pct(65, Double.NaN, 144.65))
        assertNull(OpenChange.pct(65, 122.20, Double.POSITIVE_INFINITY))
        assertNull(OpenChange.line(0, 100.0, 120.0))
    }

    private fun buy(q: Int, p: Double) = OpenChange.Fill(true, q, p)
    private fun sell(q: Int, p: Double) = OpenChange.Fill(false, q, p)

    @Test fun theOpenAverageStartsAgainAfterAClosedTrade() {
        // Boss's day: bought 65 @ 85.30, sold 65 @ 122.20 (+2,399), bought 65 again @ 122.20 - the open trade's average is 122.20,
        // never the day's buys together (103.75).
        assertEquals(122.20, OpenChange.openAverage(listOf(buy(65, 85.30), sell(65, 122.20), buy(65, 122.20)), 65)!!, 1e-9)
        // Adding to it averages in; a part sold keeps the average of what is left.
        assertEquals(110.0, OpenChange.openAverage(listOf(buy(65, 100.0), buy(65, 120.0)), 130)!!, 1e-9)
        assertEquals(110.0, OpenChange.openAverage(listOf(buy(65, 100.0), buy(65, 120.0), sell(65, 130.0)), 65)!!, 1e-9)
    }

    @Test fun aShortAndAFillThroughFlat() {
        assertEquals(80.0, OpenChange.openAverage(listOf(sell(75, 80.0)), -75)!!, 1e-9)
        assertEquals(85.0, OpenChange.openAverage(listOf(sell(75, 80.0), sell(75, 90.0)), -150)!!, 1e-9)
        // Long 65, then a sale of 130: 65 close the long and 65 open a short at that sale's price.
        assertEquals(95.0, OpenChange.openAverage(listOf(buy(65, 100.0), sell(130, 95.0)), -65)!!, 1e-9)
    }

    @Test fun anythingUncertainKeepsTheBrokersAverage() {
        assertNull(OpenChange.openAverage(listOf(buy(65, 100.0)), 65, overnight = 65), "carried in from an earlier day")
        assertNull(OpenChange.openAverage(listOf(buy(65, 100.0)), 130), "fills not all read yet")
        assertNull(OpenChange.openAverage(emptyList(), 65), "no fills read")
        assertNull(OpenChange.openAverage(listOf(buy(65, 100.0), sell(65, 110.0)), 0), "closed")
        assertNull(OpenChange.openAverage(listOf(buy(0, 100.0)), 0))
        assertNull(OpenChange.openAverage(listOf(buy(65, 0.0)), 65), "a fill without a price")
    }

    @Test fun theExitsLine() {
        // R51 Donchian on a 122.20 fill: -15% stop, +30% target, out 30 minutes after a 10:15 entry.
        assertEquals("stop 103.87 · target 158.86 · time 10:45", OpenChange.exits(122.20 * 0.85, 122.20 * 1.3, LocalTime.of(10, 45)))
        assertEquals("time 14:30 · stop on the index", OpenChange.exits(null, null, LocalTime.of(14, 30), "stop on the index"))
        assertEquals("stop 50.00", OpenChange.exits(50.0, 0.0, null, " "))
        assertNull(OpenChange.exits(null, Double.NaN, null))
        assertNull(OpenChange.exits(-1.0, null, null, ""))
    }

    @Test fun eachArmReplacesItsOwnListAndOnlyItsAccountMatches() {
        val sym = "NIFTY19OCT2623050CE"
        assertNull(ArmExits.line(false, sym), "no arm holds it")
        ArmExits.set("passed", listOf(ArmExits.Entry("passed", "R51 Donchian 20", false, sym, 103.87, 158.86, LocalTime.of(10, 45))))
        assertEquals("stop 103.87 · target 158.86 · time 10:45", ArmExits.line(false, sym))
        assertNull(ArmExits.line(true, sym), "the paper arm's trade is not the Zerodha position's")
        assertNull(ArmExits.line(false, "OTHER"))
        // Two arms on the same symbol: each named.
        ArmExits.set("orb", listOf(ArmExits.Entry("orb", "ORB", false, sym, 92.2, null, LocalTime.of(15, 10))))
        assertEquals("ORB: stop 92.20 · time 15:10; R51 Donchian 20: stop 103.87 · target 158.86 · time 10:45", ArmExits.line(false, sym))
        assertEquals(2, ArmExits.of(false, sym).size)
        // The arm's next publish without it (closed): it drops out.
        ArmExits.set("passed", emptyList())
        assertEquals("stop 92.20 · time 15:10", ArmExits.line(false, sym))
        ArmExits.set("orb", emptyList())
        assertNull(ArmExits.line(false, sym))
    }

    @Test fun anEntryWithNothingKnownIsLeftOut() {
        ArmExits.set("night", listOf(ArmExits.Entry("night", "Night", false, "X", null, null, null)))
        assertNull(ArmExits.line(false, "X"))
        ArmExits.set("jarvis", listOf(ArmExits.Entry("jarvis", "Jarvis", true, "X", 70.0, 160.0, null)))
        assertEquals("stop 70.00 · target 160.00", ArmExits.line(true, "X"))
        assertNull(ArmExits.line(false, "X"))
    }
}
