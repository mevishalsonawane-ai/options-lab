package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveReadPaceTest {
    @Test fun aRecentReadIsShared() {
        assertFalse(LiveReadPace.due(0L, quiet = false))
        assertFalse(LiveReadPace.due(20_000L, quiet = false), "the page right after the listening loop's read")
        assertFalse(LiveReadPace.due(49_999L, quiet = false))
        assertTrue(LiveReadPace.due(LiveReadPace.SHARED_MS, quiet = false))
        assertTrue(LiveReadPace.due(60_000L, quiet = false), "one keeper alone still reads every minute")
    }

    @Test fun quietIsEveryTwoMinutes() {
        assertFalse(LiveReadPace.due(60_000L, quiet = true))
        assertFalse(LiveReadPace.due(119_999L, quiet = true))
        assertTrue(LiveReadPace.due(LiveReadPace.QUIET_MS, quiet = true))
        assertTrue(LiveReadPace.due(600_000L, quiet = true))
    }

    @Test fun noReadYetOrAClockOddityReads() {
        assertTrue(LiveReadPace.due(null, quiet = true))
        assertTrue(LiveReadPace.due(null, quiet = false))
        assertTrue(LiveReadPace.due(-5L, quiet = true))
    }

    @Test fun quietNeverOutwaitsTheAnswersFreshness() {
        // Answers re-read in the background past 2 minutes and call 3 minutes fresh; the feed check reads at 2 minutes.
        assertTrue(LiveReadPace.QUIET_MS <= 120_000L)
        assertTrue(LiveReadPace.SHARED_MS < 60_000L)
    }
}
