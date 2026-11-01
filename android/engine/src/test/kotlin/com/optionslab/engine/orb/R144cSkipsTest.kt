package com.optionslab.engine.orb

import com.optionslab.engine.orb.R144cSkips.Mode
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Research R144c's skips (1 Nov, Boss's exception): paper acts, live stays in shadow; unknown data never skips. */
class R144cSkipsTest {
    @Test fun modeLiveStaysInShadow() {
        assertEquals(Mode.OFF, R144cSkips.mode(paperOn = false, goesLive = false))
        assertEquals(Mode.OFF, R144cSkips.mode(paperOn = false, goesLive = true))
        assertEquals(Mode.PAPER, R144cSkips.mode(paperOn = true, goesLive = false))
        assertEquals(Mode.LIVE_SHADOW, R144cSkips.mode(paperOn = true, goesLive = true))
    }

    @Test fun monthlyOnlyIndices() {
        assertTrue(R144cSkips.ft3MonthlyOnly("FINNIFTY"))
        assertTrue(R144cSkips.ft3MonthlyOnly("midcpnifty"))
        assertTrue(R144cSkips.ft3MonthlyOnly("BANKNIFTY"))
        assertFalse(R144cSkips.ft3MonthlyOnly("NIFTY"))
        assertFalse(R144cSkips.ft3MonthlyOnly("SENSEX"))
    }

    @Test fun longUnwindingNeedsBothFalling() {
        assertEquals(true, R144cSkips.longUnwinding(10.0, 8.0, 2e5, 1.5e5))
        assertEquals(false, R144cSkips.longUnwinding(10.0, 8.0, 1.5e5, 2e5))      // short build-up
        assertEquals(false, R144cSkips.longUnwinding(8.0, 10.0, 2e5, 1.5e5))      // short covering
        assertEquals(false, R144cSkips.longUnwinding(10.0, 10.0, 2e5, 2e5))       // flat counts as not falling (as the research)
        assertNull(R144cSkips.longUnwinding(null, 8.0, 2e5, 1.5e5))
        assertNull(R144cSkips.longUnwinding(10.0, 8.0, 0.0, 1.5e5))               // no OI from the feed: unknown
    }

    @Test fun ft3LookBackTakesTheNewestBar14To18MinutesBefore() {
        val at = LocalTime.of(11, 1)
        val bars = mapOf(at to (8.0 to 150_000L), at.minusMinutes(13) to (5.0 to 100_000L),     // too recent: ignored
            at.minusMinutes(15) to (10.0 to 200_000L), at.minusMinutes(17) to (6.0 to 100_000L))
        assertEquals(true, R144cSkips.ft3LongUnwinding(bars, at))                                // vs 14-18 min: the 15-min bar
        assertNull(R144cSkips.ft3LongUnwinding(mapOf(at to (8.0 to 150_000L), at.minusMinutes(19) to (10.0 to 200_000L)), at))
        assertNull(R144cSkips.ft3LongUnwinding(mapOf(at.minusMinutes(15) to (10.0 to 200_000L)), at))
        assertNull(R144cSkips.ft3LongUnwinding(mapOf(at to (8.0 to 0L), at.minusMinutes(15) to (10.0 to 200_000L)), at))
    }

    @Test fun nightVixCut() {
        assertEquals(true, R144cSkips.nightVixDown(14.5, 15.0))       // -3.3%
        assertEquals(false, R144cSkips.nightVixDown(14.6, 15.0))      // -2.7%
        assertEquals(false, R144cSkips.nightVixDown(16.0, 15.0))
        assertNull(R144cSkips.nightVixDown(null, 15.0))
        assertNull(R144cSkips.nightVixDown(14.0, 0.0))
    }

    @Test fun nightVixMustBeFresh() {
        assertTrue(R144cSkips.nightVixFresh(15 * 60 + 19))
        assertTrue(R144cSkips.nightVixFresh(15 * 60 + 14))           // 5 minutes behind: the feed's lag, still 15:19's
        assertFalse(R144cSkips.nightVixFresh(15 * 60 + 13))          // older: a stalled feed, not known
        assertFalse(R144cSkips.nightVixFresh(13 * 60))
        assertFalse(R144cSkips.nightVixFresh(15 * 60 + 20))          // after the decision minute
    }

    @Test fun wordsSayWhatHappened() {
        assertTrue(R144cSkips.words(Mode.PAPER, "x").contains(R144cSkips.LABEL))
        assertTrue(R144cSkips.words(Mode.LIVE_SHADOW, "x").contains("live stays in shadow"))
        assertEquals("", R144cSkips.words(Mode.OFF, "x"))
    }
}
