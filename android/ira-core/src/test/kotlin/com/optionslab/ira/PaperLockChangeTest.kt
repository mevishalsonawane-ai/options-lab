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
}
