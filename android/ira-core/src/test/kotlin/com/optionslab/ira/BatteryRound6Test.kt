package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnaskedNewsPaceTest {
    private val now = 50_000_000L

    @Test fun heldOrScreenOnIsEveryRoundAsBefore() {
        // Not quiet (a position held, the screen on, a bot armed): the 5-minute read stays, asked or not.
        assertTrue(WordsPace.newsDueUnasked(quiet = false, askedToday = false, nowMs = now, lastNewsMs = now - 1_000))
        assertTrue(WordsPace.newsDueUnasked(quiet = false, askedToday = true, nowMs = now, lastNewsMs = now - 1_000))
    }

    @Test fun askedTodayKeepsTheQuietPace() {
        assertFalse(WordsPace.newsDueUnasked(quiet = true, askedToday = true, nowMs = now, lastNewsMs = now - 5 * 60_000))
        assertTrue(WordsPace.newsDueUnasked(quiet = true, askedToday = true, nowMs = now, lastNewsMs = now - WordsPace.QUIET_NEWS_MS))
    }

    @Test fun unaskedAndQuietReadsEveryTwentyMinutes() {
        assertFalse(WordsPace.newsDueUnasked(quiet = true, askedToday = false, nowMs = now, lastNewsMs = now - WordsPace.QUIET_NEWS_MS))
        assertTrue(WordsPace.newsDueUnasked(quiet = true, askedToday = false, nowMs = now, lastNewsMs = now - WordsPace.UNASKED_NEWS_MS))
        assertTrue(WordsPace.newsDueUnasked(quiet = true, askedToday = false, nowMs = now, lastNewsMs = null), "never read")
        assertTrue(WordsPace.newsDueUnasked(quiet = true, askedToday = false, nowMs = now, lastNewsMs = now + 5_000), "clock went back")
    }
}

class ChainKeepPaceTest {
    private fun at(h: Int, m: Int, d: Int = 5) = LocalDateTime.of(2026, 10, d, h, m)

    @Test fun harvestOffIsEveryPassAsBefore() {
        assertTrue(ChainKeepPace.due(at(10, 0), at(10, 15), harvestOn = false))
        assertTrue(ChainKeepPace.due(null, at(10, 15), harvestOn = false))
    }

    @Test fun harvestOnIsHourly() {
        assertTrue(ChainKeepPace.due(null, at(9, 30), harvestOn = true), "first read of the day")
        assertFalse(ChainKeepPace.due(at(9, 30), at(9, 45), harvestOn = true))
        assertFalse(ChainKeepPace.due(at(9, 30), at(10, 15), harvestOn = true))
        assertTrue(ChainKeepPace.due(at(9, 30), at(10, 29), harvestOn = true), "a pass that drifted a minute early")
        assertTrue(ChainKeepPace.due(at(15, 0, d = 2), at(9, 30), harvestOn = true), "a new day")
        assertTrue(ChainKeepPace.due(at(11, 0), at(10, 0), harvestOn = true), "clock went back")
    }

    @Test fun oneReadFromQuarterPastThree() {
        assertTrue(ChainKeepPace.due(at(14, 45), at(15, 15), harvestOn = true))
        assertFalse(ChainKeepPace.due(at(15, 15), at(15, 30), harvestOn = true))
    }
}
