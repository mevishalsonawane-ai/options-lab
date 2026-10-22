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
 * Boss's own breakeven at +10% on the Liquidity PAPER books (22 Oct: "if the 10% is received, make the stoploss to the start
 * point"; research R100 A4 did not pass, so it is their switch, off by default): it is kept (a restart reads it back), said in
 * the diagnostics, and logged once per real change in the Autopilot change timeline as Boss's own - shown to Boss, undone in
 * one tap (the switch flipped back, never logged again), and never counted as Autopilot's change. It is its own switch: the
 * ₹500 trail's flips and Undo never move it.
 */
class LiquidityBreakevenSwitchAppTest : RobolectricTest() {
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

    private fun flips() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.LIQ_BREAKEVEN }
    private fun trails() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.LIQ_TRAIL }

    @Test fun theSwitchIsOffByDefaultKeptAndSaidInTheDiagnostics() {
        assertFalse("off by default", LiquidityTest.breakevenOn())
        assertEquals(LiquidityFreeze.breakevenSaid(true), LiquidityTest.setBreakeven(true))
        assertTrue(LiquidityTest.breakevenOn())
        assertFalse("the trail is its own switch", LiquidityTest.trailOn())
        waitFor("the state file") { LiquidityTest.stateWrittenForTest()?.contains("breakeven=true") == true }
        waitFor("the diary line") { Diag.lines().any { it.contains(LiquidityFreeze.breakevenSaid(true)) && it.contains("(by you)") } }
        // As after a restart: read back from its file.
        LiquidityTest.forgetMemoryForTest()
        assertFalse(LiquidityTest.breakevenOn())
        LiquidityTest.ensureLoaded()
        assertTrue(LiquidityTest.breakevenOn())
        // Off again: kept too.
        assertEquals(LiquidityFreeze.breakevenSaid(false), LiquidityTest.setBreakeven(false))
        waitFor("the state file") { LiquidityTest.stateWrittenForTest()?.contains("breakeven=false") == true }
        if (Autopilot.enabled()) waitFor("both flips in the timeline") { flips().size == 2 }
    }

    @Test fun eachRealChangeIsLoggedOnceAsBosssAndItsUndoFlipsItBackWithoutLoggingAgain() {
        org.junit.Assume.assumeTrue("the change timeline is in IraAlgo with Jarvis only", Autopilot.enabled())
        LiquidityTest.setBreakeven(true)
        waitFor("the switch in the timeline") { flips().size == 1 }
        val on = flips().single()
        assertEquals(AutoChange.ON, on.change.to); assertEquals(AutoChange.SWITCHED_OFF, on.change.from)
        assertEquals(AutoChange.Status.KEPT, on.status); assertEquals("your choice", on.by)
        assertEquals(AutoChange.Scope.PAPER, on.change.scope)
        assertTrue(on.note, on.note.contains("live trades never use it"))
        // The same value again is no change: not logged twice.
        LiquidityTest.setBreakeven(true)
        LiquidityTest.setBreakeven(false)
        waitFor("the switch off in the timeline") { flips().size == 2 }
        assertEquals(listOf(AutoChange.ON, AutoChange.SWITCHED_OFF), flips().map { it.change.to })
        // Shown to Boss as theirs; only the newest flip undoes; never counted as Autopilot's change.
        val day = on.day
        val items = ChangeTimeline.fromAutopilot(Autopilot.book.value, day).filter { it.key in flips().map { e -> e.id } }
        assertEquals(2, items.size)
        assertTrue(items.all { it.own && it.agent == "You (logged by Autopilot)" })
        assertEquals(ChangeTimeline.UndoState.NO_UNDO, items.single { it.key == on.id }.undo)
        assertEquals(ChangeTimeline.UndoState.UNDOABLE, items.single { it.key == flips().last().id }.undo)
        assertEquals(0, AutoChange.summary(Autopilot.book.value, day).today)
        assertEquals(null, AutoChange.lastUndoable(Autopilot.book.value))
        // Undo the switch-off: the breakeven is on again, the entry undone, and nothing new is logged.
        val off = flips().last()
        val said = Autopilot.undo(off.id)
        assertTrue(said, said.startsWith("Undone"))
        assertTrue(LiquidityTest.breakevenOn())
        assertFalse("the Undo never touches the trail", LiquidityTest.trailOn())
        Thread.sleep(300)
        assertEquals(2, flips().size)
        assertEquals(AutoChange.Status.UNDONE, flips().last().status)
        assertEquals(AutoChange.SUPERSEDED_UNDO, Autopilot.undo(on.id))
        assertTrue(LiquidityTest.breakevenOn())
        // Switched off again by Boss after the undo: a real change, logged again.
        LiquidityTest.setBreakeven(false)
        waitFor("the new switch in the timeline") { flips().size == 3 }
    }

    /** The two switches are apart: the trail's flip and its Undo leave the breakeven as it was, and the other way round. */
    @Test fun theTrailAndTheBreakevenAreSwitchedAndUndoneApart() {
        org.junit.Assume.assumeTrue("the change timeline is in IraAlgo with Jarvis only", Autopilot.enabled())
        LiquidityTest.setBreakeven(true)
        LiquidityTest.setTrail(true)
        waitFor("both in the timeline") { flips().size == 1 && trails().size == 1 }
        assertTrue(LiquidityTest.breakevenOn() && LiquidityTest.trailOn())
        assertTrue(Autopilot.undo(trails().single().id).startsWith("Undone"))
        assertFalse(LiquidityTest.trailOn()); assertTrue("the breakeven stays on", LiquidityTest.breakevenOn())
        assertTrue(Autopilot.undo(flips().single().id).startsWith("Undone"))
        assertFalse(LiquidityTest.breakevenOn()); assertFalse(LiquidityTest.trailOn())
        Thread.sleep(300)
        assertEquals("no Undo logs again", 1, flips().size)
        assertEquals(1, trails().size)
    }
}
