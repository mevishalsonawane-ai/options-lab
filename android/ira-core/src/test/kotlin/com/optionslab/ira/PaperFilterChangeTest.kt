package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R73 T7's Supertrend filter on R51 Donchian 20's paper breaks (16 Oct, Boss: "Update normal Donchian as you recommended, T7
 * supertrend agree"; [AutoChange.Kind.PAPER_FILTER]): Boss's own change, paper only (no PIN either way), it only skips entries
 * (risk down), shown in the timeline and undone by its own tap, never by "undo everything"; only its newest flip undoes.
 */
class PaperFilterChangeTest {
    private val day = LocalDate.of(2026, 10, 16)

    @Test fun switchingItOnIsABossOwnPaperChangeThatLowersRisk() {
        val on = AutoChange.passedSupertrend(true)
        assertEquals(AutoChange.Kind.PAPER_FILTER, on.kind)
        assertEquals(AutoChange.ST_TARGET, on.target)
        assertEquals(AutoChange.SWITCHED_OFF, on.from); assertEquals(AutoChange.ON, on.to)
        assertEquals(AutoChange.Scope.PAPER, on.scope)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(on), "it only skips breaks")
        assertFalse(AutoChange.never(on)); assertFalse(AutoChange.undoNeedsPin(on), "paper only: no PIN")
        assertTrue(AutoChange.bossOwn(AutoChange.Kind.PAPER_FILTER))
        val back = AutoChange.inverse(on, "undone by you")
        assertEquals(AutoChange.SWITCHED_OFF, back.to); assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(back))
        for (w in listOf("R73 T7", "Supertrend (10, 3)", "R51 Donchian 20", "1,670 trades", "+₹1,34,743", "+₹81,845", "paper only", "never live"))
            assertTrue(on.reason.contains(w), "$w: ${on.reason}")
        val off = AutoChange.passedSupertrend(false)
        assertEquals(AutoChange.ON, off.from); assertEquals(AutoChange.SWITCHED_OFF, off.to)
        assertTrue(off.reason.contains("plain rules")); assertFalse(AutoChange.undoNeedsPin(off))
        // Its own target: never confused with the lock's or L25's.
        assertTrue(AutoChange.ST_TARGET != AutoChange.LOCK_TARGET && AutoChange.ST_TARGET != AutoChange.L25_TARGET)
    }

    @Test fun itShowsInTheTimelineUndoesByItsOwnTapAndNeverByUndoEverything() {
        val (b, e) = AutoChange.record(AutoChange.Book(), AutoChange.passedSupertrend(true), AutoChange.Status.KEPT, 1_000L, day, by = "your choice")
        val undo = AutoChange.undo(b, e.id)!!
        assertEquals(AutoChange.SWITCHED_OFF, undo.inverse.to); assertFalse(undo.needsPin)
        assertNull(AutoChange.lastUndoable(b), "Boss's own: never \"undo the last change\"")
        val item = ChangeTimeline.fromAutopilot(b, day).single { it.key == e.id }
        assertEquals(ChangeTimeline.UndoState.UNDOABLE, item.undo)
        assertTrue(item.own)
        val plan = ChangeTimeline.undoPlan(listOf(item))
        assertTrue(plan.now.isEmpty() && plan.needPin.isEmpty(), "\"undo everything today\" leaves Boss's own change")
    }

    @Test fun onlyTheNewestFlipUndoesAndItSurvivesTheEncoding() {
        var b = AutoChange.Book()
        val ids = ArrayList<String>()
        for ((i, on) in listOf(true, false, true).withIndex()) {
            val (nb, e) = AutoChange.record(b, AutoChange.passedSupertrend(on), AutoChange.Status.KEPT, 1_000L + i, day, by = "your choice")
            b = nb; ids += e.id
        }
        assertNull(AutoChange.undo(b, ids[0])); assertNull(AutoChange.undo(b, ids[1]))
        assertEquals(AutoChange.SWITCHED_OFF, AutoChange.undo(b, ids[2])!!.inverse.to)
        val back = AutoChange.decode(AutoChange.encode(b))
        assertEquals(AutoChange.Kind.PAPER_FILTER, AutoChange.entry(back, ids[2])!!.change.kind)
        assertEquals(AutoChange.ST_TARGET, AutoChange.entry(back, ids[2])!!.change.target)
    }
}
