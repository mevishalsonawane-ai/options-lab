package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 14 Nov 2026 (Boss: "Make it 2k profit and 1k loss fix"; CLAUDE.md's fifth exception): the fixed ₹2,000 target / ₹1,000 stop on
 * Liquidity 15+5, R51 and R72 in the change timeline ([AutoChange.fixedRupee]) - Boss's own, live scope: on only adds earlier exits
 * (lowers risk); its Undo puts the arms' own wider exits back (adds live risk) and takes the PIN.
 */
class FixedRupeeChangeTest {
    private val day = LocalDate.of(2026, 11, 14)

    @Test fun onLowersRiskAndItsUndoTakesThePin() {
        val on = AutoChange.fixedRupee(true)
        assertEquals(AutoChange.Kind.PAPER_FILTER, on.kind)
        assertEquals(AutoChange.FIXED_RUPEE_TARGET, on.target)
        assertEquals(AutoChange.SWITCHED_OFF, on.from); assertEquals(AutoChange.ON, on.to)
        assertEquals(AutoChange.Scope.LIVE, on.scope)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(on))
        val back = AutoChange.inverse(on, "undone by you")
        assertEquals(AutoChange.SWITCHED_OFF, back.to); assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(back))
        assertTrue(AutoChange.undoNeedsPin(on), "off puts the wider exits back on live: the PIN")
        assertFalse(AutoChange.undoNeedsPin(AutoChange.fixedRupee(false)), "switching it back on only adds earlier exits")
        assertFalse(AutoChange.never(on)); assertFalse(AutoChange.never(AutoChange.fixedRupee(false)))
        assertTrue(AutoChange.bossOwn(on.kind))
        for (w in listOf("Make it 2k profit and 1k loss fix", "+₹2,000", "-₹1,000", "whole position", "paper and live", "first one wins", "tighter",
                "Failed gates, Boss's exception", "R152", "₹1.71 lakh", "-₹40k", "₹1.35 lakh", "-₹91k", "R105C", "R153"))
            assertTrue(on.reason.contains(w), "$w: ${on.reason}")
        assertTrue(on.what.contains("failed gates, Boss's exception"))
        assertTrue(AutoChange.fixedRupee(false).reason.contains("own exits"))
    }

    @Test fun itsTargetIsItsOwnAndItsUndoIsItsOwnTap() {
        val others = listOf(AutoChange.ST_TARGET, AutoChange.LOCK_TARGET, AutoChange.L25_TARGET, AutoChange.SAME_DIR_TARGET, AutoChange.NO_TWIN_TARGET,
            AutoChange.BOOK_PRICE_TARGET, AutoChange.WIDE_TARGET, AutoChange.NO_PROGRESS_TARGET, AutoChange.W20_TARGET, AutoChange.PICK_TARGET) +
            AutoChange.BOSS_PAPER_RULES
        assertFalse(AutoChange.FIXED_RUPEE_TARGET in others)
        assertFalse("liquidity" in AutoChange.FIXED_RUPEE_TARGET)
        val (b, e) = AutoChange.record(AutoChange.Book(), AutoChange.fixedRupee(true), AutoChange.Status.KEPT, 1_000L, day, by = "your ask")
        val undo = AutoChange.undo(b, e.id)!!
        assertEquals(AutoChange.SWITCHED_OFF, undo.inverse.to); assertTrue(undo.needsPin)
        assertNull(AutoChange.lastUndoable(b), "Boss's own: never \"undo the last change\"")
        val item = ChangeTimeline.fromAutopilot(b, day).single { it.key == e.id }
        assertEquals(ChangeTimeline.UndoState.NEEDS_PIN, item.undo)
        assertTrue(item.own)
        // A newer flip of the same switch supersedes the older entry (only the newest undoes).
        val (b2, _) = AutoChange.record(b, AutoChange.fixedRupee(false), AutoChange.Status.KEPT, 2_000L, day, by = "your choice")
        assertTrue(AutoChange.superseded(b2, e))
    }
}
