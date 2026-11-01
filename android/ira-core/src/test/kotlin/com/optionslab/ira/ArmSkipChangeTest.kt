package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 1 Nov 2026 (Boss: "Make the change the arms", after R142 / R143 / R144a): R140's wide-day skip on R72 Breadth Donchian and R142's
 * 09:25 no-progress exit on R52 Open outside value ([AutoChange.passedWideSkip], [AutoChange.passedNoProgress]). Each is Boss's
 * own PAPER change: it only skips an entry or sells earlier (risk down), no PIN either way, shown in the timeline and undone by
 * its own tap, never by "undo everything"; only its newest flip undoes; the words say live stays in shadow.
 */
class ArmSkipChangeTest {
    private val day = LocalDate.of(2026, 11, 1)
    private val both = listOf(AutoChange::passedWideSkip to AutoChange.WIDE_TARGET, AutoChange::passedNoProgress to AutoChange.NO_PROGRESS_TARGET)

    @Test fun switchingEachOnIsABossOwnPaperChangeThatLowersRisk() {
        for ((make, target) in both) {
            val on = make(true)
            assertEquals(AutoChange.Kind.PAPER_FILTER, on.kind)
            assertEquals(target, on.target)
            assertEquals(AutoChange.SWITCHED_OFF, on.from); assertEquals(AutoChange.ON, on.to)
            assertEquals(AutoChange.Scope.PAPER, on.scope)
            assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(on), target)
            assertFalse(AutoChange.never(on)); assertFalse(AutoChange.undoNeedsPin(on), "paper only: no PIN")
            assertFalse(AutoChange.undoLoosensLimit(on), "not one of the 25 Oct limits")
            val back = AutoChange.inverse(on, "undone by you")
            assertEquals(AutoChange.SWITCHED_OFF, back.to); assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(back))
            assertTrue(on.reason.contains("live in shadow"), on.reason)
            assertTrue(on.reason.contains("Make the change the arms"), on.reason)
            val off = make(false)
            assertEquals(AutoChange.ON, off.from); assertEquals(AutoChange.SWITCHED_OFF, off.to); assertFalse(AutoChange.undoNeedsPin(off))
        }
        val wide = AutoChange.passedWideSkip(true).reason
        for (w in listOf("R140 FB-R72-A10", "1.32x", "six periods", "-₹3.15 lakh", "-₹3.89 lakh", "p < 0.001")) assertTrue(wide.contains(w), "$w: $wide")
        val np5 = AutoChange.passedNoProgress(true).reason
        for (w in listOf("R142 NP5-52", "09:25", "5%", "-₹1.87 lakh", "-₹2.42 lakh", "lowers the win rate")) assertTrue(np5.contains(w), "$w: $np5")
    }

    @Test fun theTargetsAreTheirOwn() {
        val others = listOf(AutoChange.ST_TARGET, AutoChange.LOCK_TARGET, AutoChange.L25_TARGET, AutoChange.SAME_DIR_TARGET, AutoChange.NO_TWIN_TARGET,
            AutoChange.BOOK_PRICE_TARGET) + AutoChange.BOSS_PAPER_RULES
        assertTrue(AutoChange.WIDE_TARGET != AutoChange.NO_PROGRESS_TARGET)
        for (t in listOf(AutoChange.WIDE_TARGET, AutoChange.NO_PROGRESS_TARGET)) {
            assertFalse(t in others, t)
            assertFalse("liquidity" in t, "the Liquidity freeze never holds its Undo")
        }
    }

    @Test fun eachShowsInTheTimelineAndUndoesByItsOwnTapOnly() {
        for ((make, _) in both) {
            val (b, e) = AutoChange.record(AutoChange.Book(), make(true), AutoChange.Status.KEPT, 1_000L, day, by = "your ask")
            val undo = AutoChange.undo(b, e.id)!!
            assertEquals(AutoChange.SWITCHED_OFF, undo.inverse.to); assertFalse(undo.needsPin)
            assertNull(AutoChange.lastUndoable(b), "Boss's own: never \"undo the last change\"")
            val item = ChangeTimeline.fromAutopilot(b, day).single { it.key == e.id }
            assertEquals(ChangeTimeline.UndoState.UNDOABLE, item.undo)
            assertTrue(item.own)
            val plan = ChangeTimeline.undoPlan(listOf(item))
            assertTrue(plan.now.isEmpty() && plan.needPin.isEmpty())
        }
    }

    @Test fun onlyTheNewestFlipUndoesAndItSurvivesTheEncoding() {
        for ((make, target) in both) {
            var b = AutoChange.Book()
            val ids = ArrayList<String>()
            for ((i, on) in listOf(true, false, true).withIndex()) {
                val (nb, e) = AutoChange.record(b, make(on), AutoChange.Status.KEPT, 1_000L + i, day, by = "your ask")
                b = nb; ids += e.id
            }
            assertNull(AutoChange.undo(b, ids[0])); assertNull(AutoChange.undo(b, ids[1]))
            assertEquals(AutoChange.SWITCHED_OFF, AutoChange.undo(b, ids[2])!!.inverse.to)
            val back = AutoChange.decode(AutoChange.encode(b))
            assertEquals(target, AutoChange.entry(back, ids[2])!!.change.target)
        }
    }
}
