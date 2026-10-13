package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.AutoChange
import com.optionslab.ira.ChangeTimeline
import com.optionslab.ira.LiquidityFreeze
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Boss's own Rs 500 give-back trail switch on the Liquidity PAPER books (13 Oct review): it is kept (a restart reads it back),
 * said in the diagnostics, and logged once per real change in the Autopilot change timeline as Boss's own - shown to Boss,
 * undone in one tap (the switch flipped back, never logged again), and never counted as Autopilot's change.
 */
class LiquidityTrailSwitchTest : RobolectricTest() {
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

    private fun trails() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.LIQ_TRAIL }

    @Test fun theSwitchIsKeptAndSaidInTheDiagnostics() {
        assertFalse("off by default", LiquidityTest.trailOn())
        assertEquals(LiquidityFreeze.trailSaid(true), LiquidityTest.setTrail(true))
        assertTrue(LiquidityTest.trailOn())
        waitFor("the state file") { LiquidityTest.stateWrittenForTest()?.contains("trail=true") == true }
        waitFor("the diary line") { Diag.lines().any { it.contains("₹500 give-back trail ON for new Liquidity PAPER trades") && it.contains("(by you)") } }
        // As after a restart: read back from its file.
        LiquidityTest.forgetMemoryForTest()
        assertFalse(LiquidityTest.trailOn())
        LiquidityTest.ensureLoaded()
        assertTrue(LiquidityTest.trailOn())
        // Off again: kept too.
        assertEquals(LiquidityFreeze.trailSaid(false), LiquidityTest.setTrail(false))
        waitFor("the state file") { LiquidityTest.stateWrittenForTest()?.contains("trail=false") == true }
        // Both flips reached the timeline before the test ends (none can arrive late in the next test).
        if (Autopilot.enabled()) waitFor("both flips in the timeline") { trails().size == 2 }
    }

    @Test fun eachRealChangeIsLoggedOnceAsBosssAndItsUndoFlipsItBackWithoutLoggingAgain() {
        org.junit.Assume.assumeTrue("the change timeline is in IraAlgo with Jarvis only", Autopilot.enabled())
        LiquidityTest.setTrail(true)
        waitFor("the switch in the timeline") { trails().size == 1 }
        val on = trails().single()
        assertEquals(AutoChange.ON, on.change.to); assertEquals(AutoChange.SWITCHED_OFF, on.change.from)
        assertEquals(AutoChange.Status.KEPT, on.status); assertEquals("your choice", on.by)
        assertEquals(AutoChange.Scope.PAPER, on.change.scope)
        // The same value again is no change: not logged twice (the next real change shows the order kept).
        LiquidityTest.setTrail(true)
        LiquidityTest.setTrail(false)
        waitFor("the switch off in the timeline") { trails().size == 2 }
        assertEquals(listOf(AutoChange.ON, AutoChange.SWITCHED_OFF), trails().map { it.change.to })
        // Shown to Boss as his own; only the newest flip undoes; never counted as Autopilot's change.
        val day = on.day
        val items = ChangeTimeline.fromAutopilot(Autopilot.book.value, day).filter { it.key in trails().map { e -> e.id } }
        assertEquals(2, items.size)
        assertTrue(items.all { it.own && it.agent == "You (logged by Autopilot)" })
        assertEquals(ChangeTimeline.UndoState.NO_UNDO, items.single { it.key == on.id }.undo)
        assertEquals(ChangeTimeline.UndoState.UNDOABLE, items.single { it.key == trails().last().id }.undo)
        assertEquals(0, AutoChange.summary(Autopilot.book.value, day).today)
        assertEquals(null, AutoChange.lastUndoable(Autopilot.book.value))
        // Undo the switch-off: the trail is on again, the entry undone, and nothing new is logged (no loop: the Undo never
        // calls the log, and every earlier flip was already waited for above).
        val off = trails().last()
        val said = Autopilot.undo(off.id)
        assertTrue(said, said.startsWith("Undone"))
        assertTrue(LiquidityTest.trailOn())
        Thread.sleep(300)
        assertEquals(2, trails().size)
        assertEquals(AutoChange.Status.UNDONE, trails().last().status)
        // The older flip stays as it was: its Undo is refused and says why.
        assertEquals(AutoChange.SUPERSEDED_UNDO, Autopilot.undo(on.id))
        assertTrue(LiquidityTest.trailOn())
        assertTrue(Autopilot.undo(off.id).startsWith("That change can't be undone now"))
        // Switched off again by Boss after the undo: a real change, logged again.
        LiquidityTest.setTrail(false)
        waitFor("the new switch in the timeline") { trails().size == 3 }
    }

    /**
     * 13 Oct review: on, off, on; an Undo of the FIRST flip must not leave the switch off while the newest entry says on (the next
     * "on" from the card would then go unlogged). Only the newest flip undoes, and every real flip is logged.
     */
    @Test fun anOlderFlipIsNeverUndoneSoTheTimelineAlwaysMatchesTheSwitch() {
        org.junit.Assume.assumeTrue("the change timeline is in IraAlgo with Jarvis only", Autopilot.enabled())
        LiquidityTest.setTrail(true); LiquidityTest.setTrail(false); LiquidityTest.setTrail(true)
        waitFor("three flips in the timeline") { trails().size == 3 }
        val (e1, e2, e3) = trails()
        assertEquals(listOf(AutoChange.ON, AutoChange.SWITCHED_OFF, AutoChange.ON), listOf(e1, e2, e3).map { it.change.to })
        assertEquals(AutoChange.SUPERSEDED_UNDO, Autopilot.undo(e1.id))
        assertEquals(AutoChange.SUPERSEDED_UNDO, Autopilot.undo(e2.id))
        assertTrue("the switch is as the newest entry says", LiquidityTest.trailOn())
        // The newest undoes: off, and the next "on" from the card is a real change, logged.
        assertTrue(Autopilot.undo(e3.id).startsWith("Undone"))
        assertFalse(LiquidityTest.trailOn())
        LiquidityTest.setTrail(true)
        waitFor("the next on in the timeline") { trails().size == 4 }
        assertEquals(AutoChange.ON, trails().last().change.to)
        assertEquals(AutoChange.Status.KEPT, trails().last().status)
    }
}
