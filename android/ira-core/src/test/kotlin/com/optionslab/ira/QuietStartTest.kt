package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 19 Nov 2026 (audit batch 2: the 08:15 / 08:30 cached-process kills): when an alarm's cold start may stay light. */
class QuietStartTest {
    private fun m(h: Int, min: Int) = h * 60 + min
    private val nse = QuietStart.Window(m(9, 0), m(15, 40))
    private val mcx = QuietStart.Window(m(9, 0), m(23, 30))
    private val mcxWinter = QuietStart.Window(m(9, 0), m(23, 55))

    @Test fun thePreOpenAlarmWithNothingHeldIsQuiet() {
        assertTrue(QuietStart.quiet(m(8, 45), nse, mcx, mcx, QuietStart.FLAT), "08:45: 15 minutes before MCX and NSE's watch")
        assertTrue(QuietStart.quiet(m(3, 0), nse, mcx, mcx, QuietStart.FLAT), "the small hours")
        assertTrue(QuietStart.quiet(m(8, 49), nse, mcx, mcx, QuietStart.FLAT))
    }

    @Test fun withinTenMinutesOfAnOpenOrInsideASessionIsNeverQuiet() {
        assertFalse(QuietStart.quiet(m(8, 50), nse, mcx, mcx, QuietStart.FLAT), "MCX opens in 10 minutes")
        assertFalse(QuietStart.quiet(m(8, 55), nse, null, null, QuietStart.FLAT), "NSE's watch starts in 5 minutes")
        assertFalse(QuietStart.quiet(m(12, 0), nse, null, null, QuietStart.FLAT), "NSE open")
        assertFalse(QuietStart.quiet(m(16, 10), nse, null, null, QuietStart.FLAT), "the evening look after F&O's close")
        assertTrue(QuietStart.quiet(m(16, 16), nse, null, null, QuietStart.FLAT), "35 minutes past the close, no MCX")
        assertFalse(QuietStart.quiet(m(20, 0), nse, mcx, mcx, QuietStart.FLAT), "MCX's evening")
        assertFalse(QuietStart.quiet(m(0, 20), null, null, mcxWinter, QuietStart.FLAT), "past midnight: yesterday's MCX evening look")
        assertTrue(QuietStart.quiet(m(0, 31), null, null, mcxWinter, QuietStart.FLAT))
        assertTrue(QuietStart.quiet(m(0, 6), null, null, mcx, QuietStart.FLAT), "a 23:30 close's look ends at 00:05")
        assertFalse(QuietStart.quiet(m(0, 4), null, null, mcx, QuietStart.FLAT))
    }

    @Test fun anythingHeldOrUnknownIsTheFullStart() {
        assertFalse(QuietStart.quiet(m(3, 0), nse, mcx, mcx, QuietStart.HELD), "held at the last mark")
        assertFalse(QuietStart.quiet(m(3, 0), nse, mcx, mcx, null), "no mark yet")
        assertFalse(QuietStart.quiet(m(3, 0), nse, mcx, mcx, "garbage"))
        assertFalse(QuietStart.quiet(m(3, 0), nse, mcx, mcx, QuietStart.FLAT, unknown = true), "a calendar that could not be read")
    }

    @Test fun aClosedDayIsQuietAllDayWithNothingHeld() {
        assertTrue(QuietStart.quiet(m(11, 0), null, null, null, QuietStart.FLAT), "a Sunday")
        assertFalse(QuietStart.quiet(m(18, 0), null, QuietStart.Window(m(17, 0), m(23, 30)), null, QuietStart.FLAT), "an NSE holiday with an MCX evening")
    }
}
