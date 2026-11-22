package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.MemoryPlan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 22 Nov 2026 (B6 review: every memory trim - the screen merely hidden too - cleared every live rule trade's option high, open trades'
 * included, and kept their "asked" stamps, so a live step lock or breakeven missed a spike for up to a minute): never for the screen
 * merely hidden (nor the system only starting to run low), and only trades no longer asked for go - each with its stamp.
 */
class RuleHighsTrimTest : RobolectricTest() {
    private val now = 1_800_000_000_000L

    @Before @After fun clean() { RuleHighs.clear() }

    @Test fun onlyTheLevelsThatReleaseCachesTrimAndNeverAnOpenTradesHigh() {
        RuleHighs.putForTest("live:open", 221.0, now - 30_000)                         // asked 30 s ago: open, looked at
        RuleHighs.putForTest("orb:closed", 150.0, now - RuleHighs.ACTIVE_MS - 1)        // not asked for 10 minutes: closed
        for (level in listOf(MemoryPlan.RUNNING_MODERATE, MemoryPlan.RUNNING_LOW, MemoryPlan.UI_HIDDEN))
            assertEquals("level $level trims nothing", 0, RuleHighs.trim(level, now))
        assertEquals(2, RuleHighs.kept())
        // In the background (from Android 14 the only real signal): the closed trade only.
        assertEquals(1, RuleHighs.trim(MemoryPlan.BACKGROUND, now))
        assertEquals(221.0, RuleHighs.peek("live:open")!!, 1e-9)
        assertTrue(RuleHighs.has("live:open"))
        assertFalse("its high went", RuleHighs.has("orb:closed"))
        assertFalse("and its stamp with it (it asks again at once)", RuleHighs.askedFor("orb:closed"))
        assertTrue(RuleHighs.askedFor("live:open"))
        // RUNNING_CRITICAL and the harshest too; the open trade still kept.
        RuleHighs.putForTest("paper:old", 99.0, now - 2 * RuleHighs.ACTIVE_MS)
        assertEquals(1, RuleHighs.trim(MemoryPlan.RUNNING_CRITICAL, now))
        RuleHighs.putForTest("orb:older", 98.0, now - 3 * RuleHighs.ACTIVE_MS)
        assertEquals(1, RuleHighs.trim(MemoryPlan.COMPLETE, now))
        assertEquals(221.0, RuleHighs.peek("live:open")!!, 1e-9)
        assertEquals(1, RuleHighs.kept())
        // A stamp a little "in the future" (the clock set back since it was asked): recent, kept. One a day ahead belongs to a trade no
        // longer asked for (an open trade's next ask stamps it afresh): let go.
        RuleHighs.putForTest("live:ahead", 150.0, now + 60_000)
        RuleHighs.putForTest("orb:far", 140.0, now + 24 * 3_600_000L)
        assertEquals(1, RuleHighs.trim(MemoryPlan.COMPLETE, now))
        assertTrue(RuleHighs.has("live:ahead"))
        assertFalse(RuleHighs.has("orb:far"))
    }
}
