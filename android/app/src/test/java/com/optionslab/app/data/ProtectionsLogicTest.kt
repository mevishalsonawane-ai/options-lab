package com.optionslab.app.data

import com.optionslab.engine.Kite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

/** Pure JVM: the limit price of a stop exit, and how a protection describes itself. */
class ProtectionsLogicTest {
    @Test fun stopLimitGivesTenPercentRoomPastTheTrigger() {
        assertEquals(90.0, Protections.stopLimit(100.0, Kite.Side.SELL, 0.05), 1e-9)
        assertEquals(110.0, Protections.stopLimit(100.0, Kite.Side.BUY, 0.05), 1e-9)
    }

    @Test fun stopLimitRoundsAwayFromTheFillOntoTheTick() {
        // SELL rounds down (accepts less), BUY rounds up (pays more): a fast market still fills.
        assertEquals(90.9, Protections.stopLimit(101.03, Kite.Side.SELL, 0.05), 1e-9)
        assertEquals(111.15, Protections.stopLimit(101.03, Kite.Side.BUY, 0.05), 1e-9)
    }

    @Test fun cheapOptionsGetAtLeastFiveTicksOfRoom() {
        assertEquals(0.75, Protections.stopLimit(1.0, Kite.Side.SELL, 0.05), 1e-9)
        assertEquals(1.25, Protections.stopLimit(1.0, Kite.Side.BUY, 0.05), 1e-9)
    }

    @Test fun aSellLimitNeverGoesBelowOneTick() {
        assertEquals(0.05, Protections.stopLimit(0.2, Kite.Side.SELL, 0.05), 1e-9)
    }

    @Test fun everyStopLimitIsOnTheTick() {
        for (tick in listOf(0.05, 0.1, 0.0025)) for (i in 1..400) {
            val trigger = i * 0.37
            for (side in Kite.Side.values()) {
                val px = Protections.stopLimit(trigger, side, tick)
                val ticks = px / tick
                assertTrue("$px is not on tick $tick", abs(ticks - ticks.roundToLong()) < 1e-6)
                if (side == Kite.Side.SELL) assertTrue(px <= trigger || px == tick) else assertTrue(px >= trigger)
            }
        }
    }

    @Test fun describeNamesStopTrailAndTarget() {
        val base = Protections.Item(1, live = false, symbol = "NIFTY26OCT24500PE", exchange = "NFO", product = "NRML",
            qty = 75, lotSize = 75, tick = 0.05, stop = 95.0, trail = null, target = 120.0, best = 100.0,
            stopOrderId = null, targetOrderId = null)
        assertEquals("stop 95.00 · target 120.00", base.describe())
        assertEquals("trailing stop 95.00 (5.00 behind)", base.copy(trail = 5.0, target = null).describe())
        assertEquals("SELL", base.exitSide)
        assertEquals("BUY", base.copy(qty = -75).exitSide)
    }
}
