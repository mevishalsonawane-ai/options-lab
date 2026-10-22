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
        // 22 Oct: the stop check runs on its own lane now (never after a pass), so its own wait for the arms' exits is what must
        // fit its 15 s window, with room for the sweep and the stops and targets after it.
        assertTrue("inside the stop check's window", WatchWorkers.EXITS_WAIT_MS <= com.optionslab.ira.StopCadence.WINDOW_MS / 2 + 1_000L)
    }

    @Test fun aFreshProcessCountsAsHeld() {
        // Nothing loaded yet (the protections not read): unknown counts as held, so the shorter wait applies.
        if (com.optionslab.app.data.Protections.activeHint == null) assertTrue(WatchWorkers.heldQuick())
    }
}
