package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boss's 15 Oct arms cleanup as the Autopilot log keeps it ([AutoChange.Kind.ARM_HIDE], [AutoChange.Kind.LIQ_INDEX_ON]): each
 * switch-off and each restore in the change timeline as Boss's, undone only by its own tap, never Autopilot's own change, and
 * never needing the PIN (a restore is paper only).
 */
class ArmHideChangeTest {
    private val day = LocalDate.of(2026, 10, 15)

    @Test fun aSwitchOffLowersRiskAndItsUndoRestoresOnPaper() {
        val c = AutoChange.armHidden("night_r3", "Night (R3)", wasOn = true)
        assertEquals("hide:night_r3", c.target)
        assertEquals(AutoChange.ON, c.from); assertEquals(AutoChange.HIDDEN, c.to)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(c))
        assertEquals(AutoChange.Scope.PAPER, c.scope)
        assertFalse(AutoChange.never(c))
        assertFalse(AutoChange.undoNeedsPin(c), "a restore is paper only: no PIN")
        val back = AutoChange.inverse(c, "undone by you")
        assertEquals(AutoChange.ON, back.to)
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(back))
        assertEquals(AutoChange.SWITCHED_OFF, AutoChange.armHidden("orb", "ORB", wasOn = false).from)
        assertTrue(c.reason.startsWith("your cleanup (15 Oct): Night (R3) switched off and hidden once it held nothing"))
        assertFalse(AutoChange.armHidden("orb", "ORB", wasOn = false).reason.contains("once it held nothing"))
        assertEquals("Night (R3) (hidden arms)", c.what)
        // No hidden arm's target trips the safety denylist (Pine and Jarvis included).
        for (k in listOf("orb", "orb_fresh", "orb_sweep", "range_fade", "hero", "night_r3", "vix_div", "forge_paper", "pine_auto",
            "shadow_paper", "scheduled_baskets", "jarvis_trades")) {
            assertFalse(AutoChange.never(AutoChange.armHidden(k, k, true)), k)
            assertFalse(AutoChange.never(AutoChange.armRestored(k, k)), k)
        }
    }

    @Test fun aRestoreIsLoggedAndItsUndoHidesAgain() {
        val r = AutoChange.armRestored("pine_auto", "Pine auto-trade scripts")
        assertEquals(AutoChange.HIDDEN, r.from); assertEquals(AutoChange.RESTORED, r.to)
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(r))
        assertFalse(AutoChange.undoNeedsPin(r))
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(AutoChange.inverse(r, "undone")))
        assertTrue(r.reason.contains("PAPER only"))
    }

    @Test fun liquidityIndicesComeOnOnPaperAndBankNiftyIsNeverOne() {
        val c = AutoChange.liqIndexOn("FINNIFTY", parked = true)
        assertEquals("liqindex:FINNIFTY", c.target)
        assertEquals(AutoChange.PARKED, c.from); assertEquals(AutoChange.ON, c.to)
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(c))
        assertEquals(AutoChange.Scope.PAPER, c.scope)
        assertFalse(AutoChange.never(c))
        assertFalse(AutoChange.undoNeedsPin(c))
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(AutoChange.inverse(c, "undone")))
        assertEquals(AutoChange.SWITCHED_OFF, AutoChange.liqIndexOn("MIDCPNIFTY", parked = false).from)
        assertTrue(c.reason.contains("Boss's choice") && c.reason.contains("BANKNIFTY is not touched"))
        // Not aimed at "liquidity" by name, so the BANKNIFTY paper-test freeze never holds its Undo.
        assertFalse(LiquidityFreeze.touches(c.target))
    }

    @Test fun theyAreBosssInTheTimelineAndNeverAutopilotsOwn() {
        var b = AutoChange.Book()
        val (b1, hide) = AutoChange.record(b, AutoChange.armHidden("orb", "ORB", true), AutoChange.Status.KEPT, 1_000L, day, by = "your choice")
        b = b1
        val (b2, liq) = AutoChange.record(b, AutoChange.liqIndexOn("FINNIFTY", true), AutoChange.Status.KEPT, 2_000L, day, by = "your choice")
        b = b2
        assertTrue(AutoChange.bossOwn(AutoChange.Kind.ARM_HIDE) && AutoChange.bossOwn(AutoChange.Kind.LIQ_INDEX_ON))
        val items = ChangeTimeline.fromAutopilot(b, day)
        val h = items.single { it.key == hide.id }
        assertTrue(h.own); assertEquals("You (logged by Autopilot)", h.agent)
        assertEquals("ORB (hidden arms): on → hidden", h.what)
        assertEquals(ChangeTimeline.UndoState.UNDOABLE, h.undo)
        assertEquals("Liquidity 15+5 FINNIFTY (paper): parked → on", items.single { it.key == liq.id }.what)
        // "Undo the last change" and today's count leave them alone; each one's own Undo works.
        assertNull(AutoChange.lastUndoable(b))
        assertNull(AutoChange.notice(AutoChange.summary(b, day)))
        assertEquals(AutoChange.ON, AutoChange.undo(b, hide.id)!!.inverse.to)
        assertEquals(AutoChange.PARKED, AutoChange.undo(b, liq.id)!!.inverse.to)
    }

    @Test fun onlyTheNewestEntryOfAnArmUndoes() {
        var b = AutoChange.Book()
        val (b1, hide) = AutoChange.record(b, AutoChange.armHidden("pine_auto", "Pine", true), AutoChange.Status.KEPT, 1_000L, day, by = "your choice")
        val (b2, restore) = AutoChange.record(b1, AutoChange.armRestored("pine_auto", "Pine"), AutoChange.Status.KEPT, 2_000L, day, by = "your choice")
        b = b2
        assertTrue(AutoChange.superseded(b, AutoChange.entry(b, hide.id)!!))
        assertNull(AutoChange.undo(b, hide.id), "the older switch-off no longer undoes")
        assertFalse(AutoChange.superseded(b, AutoChange.entry(b, restore.id)!!))
        assertEquals(AutoChange.HIDDEN, AutoChange.undo(b, restore.id)!!.inverse.to)
        // Another arm's entries are its own.
        val (b3, other) = AutoChange.record(b, AutoChange.armHidden("orb", "ORB", false), AutoChange.Status.KEPT, 3_000L, day, by = "your choice")
        assertFalse(AutoChange.superseded(b3, AutoChange.entry(b3, restore.id)!!))
        assertFalse(AutoChange.superseded(b3, AutoChange.entry(b3, other.id)!!))
    }
}
