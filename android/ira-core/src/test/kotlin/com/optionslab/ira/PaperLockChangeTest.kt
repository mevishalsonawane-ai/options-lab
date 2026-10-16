package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R64's 30-60-100 lock on the three passed PAPER arms (16 Oct, Boss's choice: "All three"; [AutoChange.Kind.PAPER_LOCK]): logged as Boss's
 * own change, paper only (no PIN either way), shown in the timeline and undone by its own tap, never by "undo everything"; only
 * its newest flip undoes.
 */
class PaperLockChangeTest {
    private val day = LocalDate.of(2026, 10, 16)

    @Test fun switchingItOnIsABossOwnPaperChangeWhoseUndoNeedsNoPin() {
        val on = AutoChange.passedLock(true)
        assertEquals(AutoChange.Kind.PAPER_LOCK, on.kind)
        assertEquals(AutoChange.LOCK_TARGET, on.target)
        assertEquals(AutoChange.SWITCHED_OFF, on.from); assertEquals(AutoChange.ON, on.to)
        assertEquals(AutoChange.Scope.PAPER, on.scope)
        // It drops the fixed target, so it is counted as a raise (on paper); off puts the research exits back.
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(on))
        assertFalse(AutoChange.never(on))
        assertFalse(AutoChange.undoNeedsPin(on), "paper only: no PIN")
        val back = AutoChange.inverse(on, "undone by you")
        assertEquals(AutoChange.SWITCHED_OFF, back.to)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(back))
        assertTrue(AutoChange.bossOwn(AutoChange.Kind.PAPER_LOCK))
        for (w in listOf("R64", "+30%", "+60%", "+100%", "breakeven", "paper only", "never live")) assertTrue(on.reason.contains(w), "$w: ${on.reason}")
        val off = AutoChange.passedLock(false)
        assertEquals(AutoChange.ON, off.from); assertEquals(AutoChange.SWITCHED_OFF, off.to)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(off))
        assertFalse(AutoChange.undoNeedsPin(off))
    }

    @Test fun itShowsInTheTimelineUndoesByItsOwnTapAndNeverByUndoEverything() {
        val (b, e) = AutoChange.record(AutoChange.Book(), AutoChange.passedLock(true), AutoChange.Status.KEPT, 1_000L, day, by = "your choice")
        val undo = AutoChange.undo(b, e.id)!!
        assertEquals(AutoChange.SWITCHED_OFF, undo.inverse.to); assertFalse(undo.needsPin)
        assertNull(AutoChange.lastUndoable(b), "Boss's own: never \"undo the last change\"")
        val item = ChangeTimeline.fromAutopilot(b, day).single { it.key == e.id }
        assertEquals(ChangeTimeline.UndoState.UNDOABLE, item.undo)
        assertTrue(item.own)
        val plan = ChangeTimeline.undoPlan(listOf(item))
        assertTrue(plan.now.isEmpty() && plan.needPin.isEmpty(), "\"undo everything today\" leaves Boss's own change")
    }

    @Test fun onlyTheNewestFlipUndoes() {
        var b = AutoChange.Book()
        val ids = ArrayList<String>()
        for ((i, on) in listOf(true, false, true).withIndex()) {
            val (nb, e) = AutoChange.record(b, AutoChange.passedLock(on), AutoChange.Status.KEPT, 1_000L + i, day, by = "your choice")
            b = nb; ids += e.id
        }
        assertNull(AutoChange.undo(b, ids[0])); assertNull(AutoChange.undo(b, ids[1]))
        assertTrue(AutoChange.superseded(b, AutoChange.entry(b, ids[0])!!))
        assertFalse(AutoChange.superseded(b, AutoChange.entry(b, ids[2])!!))
        assertEquals(AutoChange.SWITCHED_OFF, AutoChange.undo(b, ids[2])!!.inverse.to)
    }

    @Test fun itSurvivesTheLogsEncoding() {
        val (b, e) = AutoChange.record(AutoChange.Book(), AutoChange.passedLock(true), AutoChange.Status.KEPT, 1_000L, day, by = "your choice")
        val back = AutoChange.decode(AutoChange.encode(b))
        assertEquals(AutoChange.Kind.PAPER_LOCK, AutoChange.entry(back, e.id)!!.change.kind)
        assertEquals(AutoChange.LOCK_TARGET, AutoChange.entry(back, e.id)!!.change.target)
    }

    // ---- R66's L25: +5% once +25% (16 Oct, Boss's choice: "Select the best") -----------------------------------------------

    @Test fun l25IsItsOwnVersionedBossChoiceThatLowersRiskOnPaper() {
        val on = AutoChange.passedL25(true)
        assertEquals(AutoChange.Kind.PAPER_LOCK, on.kind)
        assertEquals(AutoChange.L25_TARGET, on.target)
        assertTrue(AutoChange.L25_TARGET != AutoChange.LOCK_TARGET)
        assertEquals(AutoChange.SWITCHED_OFF, on.from); assertEquals(AutoChange.ON, on.to)
        assertEquals(AutoChange.Scope.PAPER, on.scope)
        // It only raises a stop: on lowers risk, its undo takes that away.
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(on))
        val back = AutoChange.inverse(on, "undone by you")
        assertEquals(AutoChange.SWITCHED_OFF, back.to); assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(back))
        assertFalse(AutoChange.undoNeedsPin(on), "paper only: no PIN"); assertFalse(AutoChange.never(on))
        for (w in listOf("R66", "+5%", "+25%", "not proven", "2,202 trades", "+₹5,396", "+₹8,042", "open trades keep", "paper only", "never live"))
            assertTrue(on.reason.contains(w), "$w: ${on.reason}")
        assertTrue(AutoChange.passedL25(false).reason.contains("plain 30-60-100 lock"))
        // The A4 lock's own risk is unchanged.
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(AutoChange.passedLock(true)))
    }

    @Test fun l25UndoesByItsOwnTapAndItsFlipsSupersedeOnlyEachOther() {
        var b = AutoChange.Book()
        val (b1, a4) = AutoChange.record(b, AutoChange.passedLock(true), AutoChange.Status.KEPT, 1_000L, day, by = "your choice"); b = b1
        val ids = ArrayList<String>()
        for ((i, on) in listOf(true, false, true).withIndex()) {
            val (nb, e) = AutoChange.record(b, AutoChange.passedL25(on), AutoChange.Status.KEPT, 2_000L + i, day, by = "your choice")
            b = nb; ids += e.id
        }
        // The L25 flips never supersede the A4 entry (another target): both stay undoable, each by its own tap.
        assertFalse(AutoChange.superseded(b, AutoChange.entry(b, a4.id)!!))
        assertEquals(AutoChange.SWITCHED_OFF, AutoChange.undo(b, a4.id)!!.inverse.to)
        assertNull(AutoChange.undo(b, ids[0])); assertNull(AutoChange.undo(b, ids[1]))
        val undo = AutoChange.undo(b, ids[2])!!
        assertEquals(AutoChange.L25_TARGET, undo.inverse.target); assertEquals(AutoChange.SWITCHED_OFF, undo.inverse.to); assertFalse(undo.needsPin)
        assertNull(AutoChange.lastUndoable(b), "Boss's own: never \"undo the last change\"")
        val item = ChangeTimeline.fromAutopilot(b, day).single { it.key == ids[2] }
        assertEquals(ChangeTimeline.UndoState.UNDOABLE, item.undo); assertTrue(item.own)
        val back = AutoChange.decode(AutoChange.encode(b))
        assertEquals(AutoChange.L25_TARGET, AutoChange.entry(back, ids[2])!!.change.target)
    }
}
