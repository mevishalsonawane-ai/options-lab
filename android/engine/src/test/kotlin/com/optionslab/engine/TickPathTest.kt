package com.optionslab.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TickPathTest {
    @Test fun theHighSinceALookIsEveryTickAfterIt() {
        val p = TickPath()
        assertNull(p.highSince(0))
        p.record(300.0, 10_000)
        p.record(315.0, 10_400)       // a spike inside one second
        p.record(306.0, 11_200)
        p.record(304.0, 14_000)
        assertEquals(315.0, p.highSince(9_000))
        assertEquals(306.0, p.highSince(10_500), "the bucket holding the look itself is the look's own")
        assertNull(p.highSince(14_000))
        // Junk is ignored.
        p.record(Double.NaN, 15_000); p.record(-1.0, 15_000); p.record(0.0, 15_000)
        assertEquals(3, p.size())
    }

    @Test fun aRestingSellStopFillsAtItsTriggerOrTheFirstPriceUnderIt() {
        val p = TickPath()
        p.record(320.0, 1_000)
        p.record(311.0, 2_000)
        p.record(309.0, 2_500)        // touched 310 inside a second that opened above it
        p.record(330.0, 5_000)
        assertEquals(310.0, p.sellStopFill(0, 310.0))
        assertNull(p.sellStopFill(3_000, 310.0), "after the dip: not reached")
        p.record(305.0, 6_000)        // a second that opened under the stop: filled at that price
        assertEquals(305.0, p.sellStopFill(5_500, 310.0))
    }

    @Test fun aWholeMinuteIsBuiltFromItsTicks() {
        val p = TickPath()
        assertNull(p.minute(0), "nothing kept")
        p.record(100.0, 59_000)                         // the minute before
        p.record(101.0, 60_000); p.record(104.0, 75_500); p.record(99.5, 90_000); p.record(102.0, 90_400); p.record(103.0, 119_999)
        p.record(110.0, 120_000)                        // the next minute
        val m = p.minute(60_000)!!
        assertEquals(listOf(101.0, 104.0, 99.5, 103.0), m.toList())
        assertNull(p.minute(180_000), "no tick in it")
        // The ticks kept must reach back to the minute's start, or its open is not known.
        val late = TickPath(); late.record(101.0, 75_000)
        assertNull(late.minute(60_000))
    }

    @Test fun onlyTheLastFewMinutesAreKeptAndLateTicksJoinTheLastBucket() {
        val p = TickPath(keepMs = 10_000, bucketMs = 1_000)
        for (s in 0 until 30) p.record(100.0 + s, s * 1_000L)
        assertEquals(11, p.size())
        assertEquals(129.0, p.highSince(0))
        p.record(500.0, 1_000)        // stamped in the past (a clock step): counted in the newest bucket
        assertEquals(11, p.size())
        assertEquals(500.0, p.highSince(25_000))
    }
}
