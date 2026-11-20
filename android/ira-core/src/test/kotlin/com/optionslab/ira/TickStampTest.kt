package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 20 Nov 2026 (audit: "a burst of late ticks is stamped now"): [TickStamp]. */
class TickStampTest {
    private val nfo = 2
    private val bse = 4
    /** The wall clock 3.2 s ahead of the exchange, the monotonic clock 1 000 000 behind the wall clock. */
    private val offset = 3_200L
    private val skew = 1_000_000L

    private fun at(s: TickStamp, seg: Int, wall: Long, exchSec: Long?) = s.at(seg, wall, wall - skew, exchSec)

    @Test fun aSteadyStreamKeepsEveryArrivalTime() {
        val s = TickStamp()
        var wall = 1_763_600_000_000L
        repeat(600) {
            // Ticks every 100 ms, the exchange stamp in whole seconds: the gap wanders between the offset and the offset + 1 s.
            val ex = (wall - offset) / 1000
            assertEquals(wall, at(s, nfo, wall, ex), "tick $it")
            wall += 100
        }
    }

    @Test fun aLateBurstIsStampedBackByItsLatenessNeverOlderThanItIs() {
        val s = TickStamp()
        var wall = 1_763_600_000_000L
        repeat(50) { at(s, nfo, wall, (wall - offset) / 1000); wall += 200 }
        // A burst of packets 8 s late (a stall, then Zerodha sends what it held).
        val ex = (wall - offset - 8_000) / 1000
        val got = at(s, nfo, wall, ex)
        val trueAt = ex * 1000 + offset
        assertTrue(got < wall - 5_000, "stamped back: ${wall - got} ms")
        assertTrue(got >= trueAt, "never older than the exchange says: ${got - trueAt}")
        assertTrue(wall - got > LiveQuote.FRESH_MS, "not fresh enough to decide a stop on")
        // The next packet on time keeps its arrival.
        assertEquals(wall + 100, at(s, nfo, wall + 100, (wall + 100 - offset) / 1000))
    }

    @Test fun twoSecondsLateIsWithinTheMarginAndKeptAsArrived() {
        val s = TickStamp()
        var wall = 1_763_600_000_000L
        repeat(50) { at(s, nfo, wall, (wall - offset) / 1000); wall += 200 }
        assertEquals(wall, at(s, nfo, wall, (wall - offset - 2_000) / 1000))
    }

    @Test fun eachSegmentHasItsOwnFloorAndNoStampMeansArrival() {
        val s = TickStamp()
        var wall = 1_763_600_000_000L
        repeat(20) {
            at(s, nfo, wall, (wall - offset) / 1000)
            // BSE's stamps run 6 s behind NSE's on this phone: steady for BSE, never "late".
            assertEquals(wall, at(s, bse, wall, (wall - offset - 6_000) / 1000))
            wall += 500
        }
        assertEquals(wall, at(s, nfo, wall, null))
        assertEquals(wall, at(s, nfo, wall, 0))
    }

    @Test fun aWallClockStepForgetsWhatWasLearnt() {
        val s = TickStamp()
        var wall = 1_763_600_000_000L
        repeat(20) { at(s, nfo, wall, (wall - offset) / 1000); wall += 500 }
        // The wall clock jumps an hour forward (the monotonic clock does not): every tick would look an hour late without the reset.
        val jumped = wall + 3_600_000L
        assertEquals(jumped, s.at(nfo, jumped, wall - skew, (wall - offset) / 1000))
        assertEquals(jumped + 500, s.at(nfo, jumped + 500, wall + 500 - skew, (wall + 500 - offset) / 1000))
        // And back: the same.
        val back = wall - 3_600_000L
        assertEquals(back, s.at(nfo, back, wall + 1_000 - skew, (wall + 1_000 - offset) / 1000))
    }

    /** Review, 20 Nov 2026: one packet stamped ahead of time never makes the rest of its segment look late. */
    @Test fun oneBadStampAheadOfTimeDoesNotMakeEveryPacketLate() {
        val s = TickStamp()
        var wall = 1_763_600_000_000L
        repeat(120) { at(s, nfo, wall, (wall - offset) / 1000); wall += 500 }             // a minute: six steps of the window
        at(s, nfo, wall, (wall - offset + 10_000) / 1000)                                 // a stamp 10 s ahead
        wall += 500
        repeat(240) {
            assertEquals(wall, at(s, nfo, wall, (wall - offset) / 1000), "on time after the bad stamp: $it")
            wall += 500
        }
        // And a real late burst is still seen.
        assertTrue(wall - at(s, nfo, wall, (wall - offset - 8_000) / 1000) > 3_000)
    }

    @Test fun theFloorIsCarriedAcrossAQuietLongerThanTheWindow() {
        val s = TickStamp()
        var wall = 1_763_600_000_000L
        repeat(20) { at(s, nfo, wall, (wall - offset) / 1000); wall += 500 }
        // Ten minutes with no packet (the stream down), then the first burst is 6 s late.
        wall += 600_000L
        val got = at(s, nfo, wall, (wall - offset - 6_000) / 1000)
        assertTrue(wall - got > 3_000, "the late burst after a long quiet is still seen late: ${wall - got}")
        s.clear()
        assertEquals(wall, at(s, nfo, wall, (wall - offset - 6_000) / 1000), "nothing learnt: its arrival")
    }
}
