package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetOrdersPaceTest {
    @Test fun screenOnReadsEveryPass() {
        assertTrue(WidgetOrdersPace.due(0L, screenOn = true))
        assertTrue(WidgetOrdersPace.due(15_000L, screenOn = true))
        assertTrue(WidgetOrdersPace.due(null, screenOn = true))
    }

    @Test fun screenOffReadsAboutEveryFiveMinutes() {
        assertFalse(WidgetOrdersPace.due(15_000L, screenOn = false))
        assertFalse(WidgetOrdersPace.due(240_000L, screenOn = false))
        assertTrue(WidgetOrdersPace.due(WidgetOrdersPace.SCREEN_OFF_MS, screenOn = false))
        assertTrue(WidgetOrdersPace.due(3_600_000L, screenOn = false))
    }

    @Test fun noReadYetOrAClockOddityReads() {
        assertTrue(WidgetOrdersPace.due(null, screenOn = false))
        assertTrue(WidgetOrdersPace.due(-1L, screenOn = false))
    }

    @Test fun fifteenSecondPassesReadTwentyTimesLess() {
        // A held position: a pass every 15 s, each timer a little early (14.8 s), for an hour with the screen off.
        var since = 0L
        var reads = 0
        repeat(240) {
            since += 14_800L
            if (WidgetOrdersPace.due(since, screenOn = false)) { reads++; since = 0L }
        }
        assertEquals(12, reads, "240 passes (an hour) read 12 times, not 240")
    }

    @Test fun minutePassesReadOnTheFifth() {
        var since = 0L
        var reads = 0
        repeat(60) {
            since += 59_000L
            if (WidgetOrdersPace.due(since, screenOn = false)) { reads++; since = 0L }
        }
        assertEquals(12, reads, "60 one-minute passes read 12 times, not 60")
    }
}
