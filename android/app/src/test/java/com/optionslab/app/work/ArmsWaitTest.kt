package com.optionslab.app.work

import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 18 Oct, review: while anything is held, a full pass waits 10 s (not 45) on each arms group, so the stops and targets
 * after it and the 15-second stop check after the pass are not held up behind a slow arm.
 */
class ArmsWaitTest : RobolectricTest() {
    @Test fun heldWaitsTenSecondsElseFortyFive() {
        assertEquals(10_000L, WatchWorkers.armsWaitMs(true))
        assertEquals(WatchWorkers.ARMS_WAIT_MS, WatchWorkers.armsWaitMs(false))
        assertTrue("shorter than the stop check's own wait", WatchWorkers.ARMS_WAIT_HELD_MS <= WatchWorkers.EXITS_WAIT_MS)
    }

    @Test fun aFreshProcessCountsAsHeld() {
        // Nothing loaded yet (the protections not read): unknown counts as held, so the shorter wait applies.
        if (com.optionslab.app.data.Protections.activeHint == null) assertTrue(WatchWorkers.heldQuick())
    }
}
