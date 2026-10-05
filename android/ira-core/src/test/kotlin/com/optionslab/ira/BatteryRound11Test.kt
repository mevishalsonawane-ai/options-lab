package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountWarmPaceTest {
    @Test fun notQuietReadsEveryPass() {
        assertTrue(AccountWarmPace.due(0L, quiet = false))
        assertTrue(AccountWarmPace.due(30_000L, quiet = false))
        assertTrue(AccountWarmPace.due(null, quiet = false))
    }

    @Test fun quietReadsAboutEveryTwoMinutes() {
        assertFalse(AccountWarmPace.due(30_000L, quiet = true))
        assertFalse(AccountWarmPace.due(90_000L, quiet = true))
        assertTrue(AccountWarmPace.due(AccountWarmPace.QUIET_MS, quiet = true))
        assertTrue(AccountWarmPace.due(600_000L, quiet = true))
    }

    @Test fun aChangeOrAClockOddityReads() {
        assertTrue(AccountWarmPace.due(null, quiet = true), "cleared by an order or a command: read at once")
        assertTrue(AccountWarmPace.due(-1L, quiet = true))
    }

    @Test fun aThirtySecondLoopReadsOnItsFourthPass() {
        // A pass every 30 s, each timer a little early (29.5 s): the fourth pass after a read is still due.
        var since = 0L
        var reads = 0
        repeat(16) {
            since += 29_500L
            if (AccountWarmPace.due(since, quiet = true)) { reads++; since = 0L }
        }
        assertEquals(4, reads, "16 passes (8 minutes) read 4 times, not 16")
        assertTrue(AccountWarmPace.QUIET_MS <= 120_000L)
    }
}
