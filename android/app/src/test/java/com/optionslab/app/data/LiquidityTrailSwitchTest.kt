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
        // Shown to Boss as his own, undoable in one tap, and never counted as Autopilot's change.
        val day = on.day
        val items = ChangeTimeline.fromAutopilot(Autopilot.book.value, day).filter { it.key in trails().map { e -> e.id } }
        assertEquals(2, items.size)
        assertTrue(items.all { it.own && it.agent == "You (logged by Autopilot)" && it.undo == ChangeTimeline.UndoState.UNDOABLE })
        assertEquals(0, AutoChange.summary(Autopilot.book.value, day).today)
        assertEquals(null, AutoChange.lastUndoable(Autopilot.book.value))
        // Undo the switch-off: the trail is on again, the entry undone, and nothing new is logged (no loop).
        val off = trails().last()
        val said = Autopilot.undo(off.id)
        assertTrue(said, said.startsWith("Undone"))
        assertTrue(LiquidityTest.trailOn())
        Thread.sleep(300)
        assertEquals(2, trails().size)
        assertEquals(AutoChange.Status.UNDONE, trails().last().status)
        // Undo the switch-on: off again, still nothing new logged; and an undone entry cannot be undone twice.
        assertTrue(Autopilot.undo(on.id).startsWith("Undone"))
        assertFalse(LiquidityTest.trailOn())
        assertTrue(Autopilot.undo(on.id).startsWith("That change can't be undone now"))
        Thread.sleep(300)
        assertEquals(2, trails().size)
        // Switched on again by Boss after the undo: a real change, logged again.
        LiquidityTest.setTrail(true)
        waitFor("the new switch in the timeline") { trails().size == 3 }
    }
}
