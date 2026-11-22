package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.StartOrder
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 22 Nov 2026 (B6 review: the app's start queued the Pine scripts' Keystore read ahead of the open positions' books): the scripts'
 * read ahead waits for the start's positions step, as the gold books' and Jarvis's memory do ([PineScripts.warmSoon]).
 */
class PineScriptsStartTest : RobolectricTest() {
    @After fun down() { PositionsFirst.resetForTest(started = false) }

    @Test fun theScriptsReadAheadWaitsForThePositionsStep() {
        PineScripts.init(context, warm = false)
        PineScripts.unloadForTest()
        PositionsFirst.resetForTest(started = true)          // a start under way: the positions step not done yet
        PineScripts.warmSoon()
        Thread.sleep(500)
        assertFalse("not read ahead of the positions' books", PineScripts.loadedForTest())
        PositionsFirst.order.done(StartOrder.Step.POSITIONS, android.os.SystemClock.uptimeMillis())
        val end = System.currentTimeMillis() + 10_000
        while (!PineScripts.loadedForTest() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("read once the positions step is done", PineScripts.loadedForTest())
    }

    @Test fun withNoStartUnderWayItReadsAtOnce() {
        PineScripts.init(context, warm = false)
        PineScripts.unloadForTest()
        PositionsFirst.resetForTest(started = false)
        PineScripts.warmSoon()
        val end = System.currentTimeMillis() + 10_000
        while (!PineScripts.loadedForTest() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(PineScripts.loadedForTest())
    }
}
