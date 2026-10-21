package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.AutoChange
import com.optionslab.ira.LiquidityFreeze
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 20 Oct, enhancement round 1 (idea 3): Liquidity's no-twin switch on the PAPER books - on by this update, kept across a restart,
 * said in the diagnostics, every real flip logged in the Autopilot change timeline as Boss's and undone there in one tap.
 */
class LiquidityNoTwinSwitchTest : RobolectricTest() {
    @Before fun up() {
        LiquidityTest.setStateForTest(LiquidityFreeze.State())
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
    }

    @After fun down() {
        LiquidityTest.resetForTest()
        if (Autopilot.enabled()) Autopilot.resetForTest()
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && !ok()) Thread.sleep(20)
        assertTrue("waited for $what", ok())
    }

    private fun flips() = Autopilot.book.value.entries.filter { it.change.target == AutoChange.NO_TWIN_TARGET }

    @Test fun onByDefaultKeptAcrossARestartAndSaid() {
        assertTrue("on by this update", LiquidityTest.noTwinOn())
        val said = LiquidityTest.setNoTwin(false)
        assertTrue(said, said.startsWith("Liquidity no-twin rule off"))
        assertFalse(LiquidityTest.noTwinOn())
        waitFor("the state file") { LiquidityTest.stateWrittenForTest()?.contains("noTwin=false") == true }
        waitFor("the diary line") { Diag.lines().any { it.contains("Liquidity no-twin rule off") && it.contains("(by you)") } }
        LiquidityTest.forgetMemoryForTest()
        LiquidityTest.ensureLoaded()
        assertFalse("read back from its file", LiquidityTest.noTwinOn())
        assertTrue(LiquidityTest.twinLine(), LiquidityTest.twinLine().startsWith("Liquidity no-twin rule (off; live in shadow)"))
        if (Autopilot.enabled()) waitFor("the flip in the timeline") { flips().size == 1 }
    }

    @Test fun aFlipIsLoggedAsBosssAndItsUndoFlipsItBack() {
        org.junit.Assume.assumeTrue("the change timeline is in IraAlgo with Jarvis only", Autopilot.enabled())
        LiquidityTest.setNoTwin(false)
        waitFor("the flip in the timeline") { flips().size == 1 }
        val off = flips().single()
        assertEquals(AutoChange.SWITCHED_OFF, off.change.to); assertEquals(AutoChange.Scope.PAPER, off.change.scope)
        assertEquals("your choice", off.by)
        val said = Autopilot.undo(off.id)
        assertTrue(said, said.startsWith("Undone"))
        assertTrue("back on", LiquidityTest.noTwinOn())
        Thread.sleep(300)
        assertEquals("the undo is not logged again", 1, flips().size)
    }

    /** The update's own switch-on: logged once (kept), so "Today's changes" shows it; never twice. */
    @Test fun theUpdatesSwitchOnIsLoggedOnce() {
        org.junit.Assume.assumeTrue("the change timeline is in IraAlgo with Jarvis only", Autopilot.enabled())
        Autopilot.logLiqNoTwinOn(); Autopilot.logLiqNoTwinOn()
        waitFor("the update's entry") { flips().size == 1 }
        Thread.sleep(300)
        assertEquals(1, flips().size)
        assertEquals(AutoChange.ON, flips().single().change.to)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(flips().single().change))
    }
}
