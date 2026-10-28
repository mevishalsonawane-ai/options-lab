package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.ira.IraHub
import com.optionslab.app.security.PinLock
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.AutoChange
import com.optionslab.ira.IdeaWinRate
import com.optionslab.ira.Market
import com.optionslab.ira.PatternKind
import com.optionslab.ira.StrategyLab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * 28 Oct (Boss: "Dont create a pine script or stratergies automatically, until 65% + winrate is found"): the app's copy of the
 * win-rate rule - on at 65% by default, kept across a restart; the number changes only with the PIN; logged once in the Autopilot
 * change timeline, its Undo (the PIN where one is set) restoring the old behaviour; each held idea a note once, nothing to undo;
 * Jarvis's own Pine scripts held (their backtest is in-sample).
 */
class IdeaRuleHostTest : RobolectricTest() {
    @Before fun up() {
        IdeaRuleHost.setForTest()
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
    }

    @After fun down() {
        IdeaRuleHost.setForTest()
        if (Autopilot.enabled()) Autopilot.resetForTest()
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end && !ok()) Thread.sleep(20)
        assertTrue("waited for $what", ok())
    }

    private fun rules() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.IDEA_RULE }
    private fun notes() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.IDEA_HELD }

    @Test fun theRuleIsOnAt65ByDefaultAndKeptAcrossARestart() {
        IdeaRuleHost.load()
        assertEquals(IdeaWinRate.Rule.DEFAULT, IdeaRuleHost.rule())
        assertTrue(IdeaRuleHost.setMinPct(70.0, pinConfirmed = true).contains("70%"))
        IdeaRuleHost.setForTest()
        IdeaRuleHost.load()
        assertEquals("read back from the settings", 70.0, IdeaRuleHost.rule().minPct, 0.0)
        // A bad number in the settings falls back to 65%.
        SecurePrefs.put("g.ideaRule.minPct", 5.0)
        IdeaRuleHost.load()
        assertEquals(65.0, IdeaRuleHost.rule().minPct, 0.0)
    }

    @Test fun theMinimumChangesOnlyWithThePinUpOrDown() {
        PinLock.setPin("482915".toCharArray())
        assertEquals(IdeaWinRate.PIN_LINE, IdeaRuleHost.setMinPct(70.0, pinConfirmed = false))
        assertEquals(IdeaWinRate.PIN_LINE, IdeaRuleHost.setMinPct(55.0, pinConfirmed = false))
        assertEquals(65.0, IdeaRuleHost.rule().minPct, 0.0)
        assertTrue(IdeaRuleHost.setMinPct(55.0, pinConfirmed = true).contains("55%"))
        assertEquals(55.0, IdeaRuleHost.rule().minPct, 0.0)
        // Outside 50%..90%: refused even with the PIN.
        assertTrue(IdeaRuleHost.setMinPct(20.0, pinConfirmed = true).startsWith("The minimum win rate must be between"))
        assertEquals(55.0, IdeaRuleHost.rule().minPct, 0.0)
    }

    @Test fun theRuleIsLoggedOnceAndItsUndoNeedsThePinAndRestoresTheOldBehaviour() {
        Assume.assumeTrue("the change timeline is in IraAlgo with Jarvis only", Autopilot.enabled())
        PinLock.setPin("482915".toCharArray())
        Autopilot.logIdeaRuleOn(65.0); Autopilot.logIdeaRuleOn(65.0)
        waitFor("the update's entry") { rules().size == 1 }
        Thread.sleep(300)
        assertEquals("logged once", 1, rules().size)
        val e = rules().single()
        assertEquals(AutoChange.ON, e.change.to); assertEquals("your choice", e.by)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(e.change))
        // Taking the rule away loosens Boss's own rule: the PIN.
        assertTrue(Autopilot.undoNeedsPin(e.id))
        assertEquals(Autopilot.PIN_UNDO, Autopilot.undo(e.id))
        assertTrue("still on", IdeaRuleHost.rule().on)
        val said = Autopilot.undo(e.id, pinConfirmed = true)
        assertTrue(said, said.startsWith("Undone"))
        assertFalse("the old behaviour: no win-rate check", IdeaRuleHost.rule().on)
        assertNull(IdeaWinRate.held(IdeaWinRate.Creator.FORGE, IdeaWinRate.fromNets(List(10) { -1.0 }), IdeaRuleHost.rule()))
        Thread.sleep(300)
        assertEquals("the undo is not logged again", 1, rules().size)
        assertEquals(AutoChange.Status.UNDONE, rules().single().status)
        // Switched back on from Settings: logged again, so it can be undone again.
        IdeaRuleHost.setOn(true)
        waitFor("the switch back on") { rules().count { it.status == AutoChange.Status.KEPT } == 1 }
        assertTrue(IdeaRuleHost.rule().on)
    }

    @Test fun aHeldIdeaIsANoteOncePerIdeaWithNothingToUndo() {
        Assume.assumeTrue("the change timeline is in IraAlgo with Jarvis only", Autopilot.enabled())
        val why = "held: win rate 64.9% (57) below your 65% rule"
        IdeaRuleHost.held(IdeaWinRate.Creator.FORGE, "F12", "F12 VWAP reclaim Banknifty 5m", why)
        IdeaRuleHost.held(IdeaWinRate.Creator.FORGE, "F12", "F12 VWAP reclaim Banknifty 5m", why)
        IdeaRuleHost.held(IdeaWinRate.Creator.SHADOW, "orb_fresh_v2", "ORB Fresh v2", why)
        waitFor("two notes") { notes().size == 2 }
        Thread.sleep(300)
        assertEquals("once per idea", 2, notes().size)
        for (n in notes()) {
            assertEquals(AutoChange.Status.SKIPPED, n.status); assertEquals(Autopilot.HELD_BY, n.by); assertEquals(why, n.note)
            assertFalse(n.applied)
            assertTrue(Autopilot.undo(n.id).startsWith("That change can't be undone"))
        }
        assertEquals("no trial counted: the evidence bar is not raised", 0, Autopilot.book.value.trials)
    }

    @Test fun jarvisOwnScriptsAreHeldAndBossesOwnApprovalIsNotAsked() {
        val r = StrategyLab.Result("Jarvis: Hammer NIFTY 15m", PatternKind.HAMMER, Market.NIFTY, 15, "//", LocalDate.of(2024, 10, 1),
            LocalDate.of(2026, 10, 27), 500, 100, 80.0, 900.0, 9.0, 300.0, 450.0, 450.0, 5_000.0, 100, recommended = true, verdict = "")
        val why = IraHub.heldPine(r, IdeaWinRate.Rule.DEFAULT)
        assertNotNull(why); assertTrue(why!!, why.startsWith("held: tested in-sample only"))
        assertNull("the rule undone: the old two-year test alone", IraHub.heldPine(r, IdeaWinRate.Rule.OFF))
        // The rule in force is the host's.
        IdeaRuleHost.setForTest(on = false)
        assertNull(IraHub.heldPine(r))
    }
}
