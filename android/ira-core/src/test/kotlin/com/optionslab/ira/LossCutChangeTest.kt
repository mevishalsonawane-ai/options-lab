package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 1 Nov (Boss: "Okay go ahead, but dont break any thing"): R72's wide-day skip and R52's no-progress exit as paper switches
 * ([AutoChange.wideDaySkip], [AutoChange.noProgressExit]): Boss's own change, paper only (no PIN either way), on lowers risk,
 * shown in the timeline, undone by its own tap and never by "undo everything"; only its newest flip undoes; own targets.
 */
class LossCutChangeTest {
    private val day = LocalDate.of(2026, 11, 2)

    @Test fun bothAreBossOwnPaperChangesThatLowerRiskWhenOn() {
        for ((build, target) in listOf<Pair<(Boolean) -> AutoChange.Change, String>>(
            AutoChange::wideDaySkip to AutoChange.WIDE_DAY_TARGET, AutoChange::noProgressExit to AutoChange.NO_PROGRESS_TARGET)) {
            val on = build(true)
            assertEquals(AutoChange.Kind.PAPER_FILTER, on.kind)
            assertEquals(target, on.target)
            assertEquals(AutoChange.SWITCHED_OFF, on.from); assertEquals(AutoChange.ON, on.to)
            assertEquals(AutoChange.Scope.PAPER, on.scope)
            assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(on))
            assertFalse(AutoChange.undoNeedsPin(on)); assertFalse(AutoChange.never(on))
            assertTrue(on.reason.contains("paper only"), on.reason)
            val off = build(false)
            assertEquals(AutoChange.ON, off.from); assertEquals(AutoChange.SWITCHED_OFF, off.to)
            assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(off))
            assertFalse(AutoChange.undoNeedsPin(off))
        }
        assertTrue(AutoChange.wideDaySkip(true).reason.contains("1.32x"))
        assertTrue(AutoChange.noProgressExit(true).reason.contains("5 minutes"))
        val targets = setOf(AutoChange.WIDE_DAY_TARGET, AutoChange.NO_PROGRESS_TARGET, AutoChange.ST_TARGET, AutoChange.BOOK_PRICE_TARGET,
            AutoChange.LOCK_TARGET, AutoChange.L25_TARGET)
        assertEquals(6, targets.size, "each has its own target")
    }

    @Test fun shownInTheTimelineUndoneByItsOwnTapNeverByUndoEverything() {
        for (c in listOf(AutoChange.wideDaySkip(true), AutoChange.noProgressExit(true))) {
            val (b, e) = AutoChange.record(AutoChange.Book(), c, AutoChange.Status.KEPT, 1_000L, day, by = "your choice")
            val undo = AutoChange.undo(b, e.id)!!
            assertEquals(AutoChange.SWITCHED_OFF, undo.inverse.to); assertFalse(undo.needsPin)
            assertNull(AutoChange.lastUndoable(b))
            val item = ChangeTimeline.fromAutopilot(b, day).single { it.key == e.id }
            assertEquals(ChangeTimeline.UndoState.UNDOABLE, item.undo)
            assertTrue(ChangeTimeline.undoPlan(listOf(item)).now.isEmpty())
        }
    }

    @Test fun onlyTheNewestFlipUndoesAndItSurvivesTheEncoding() {
        var b = AutoChange.Book()
        val ids = ArrayList<String>()
        for ((i, on) in listOf(true, false, true).withIndex()) {
            val (nb, e) = AutoChange.record(b, AutoChange.noProgressExit(on), AutoChange.Status.KEPT, 1_000L + i, day, by = "your choice")
            b = nb; ids += e.id
        }
        assertNull(AutoChange.undo(b, ids[0])); assertNull(AutoChange.undo(b, ids[1]))
        assertEquals(AutoChange.SWITCHED_OFF, AutoChange.undo(b, ids[2])!!.inverse.to)
        val back = AutoChange.decode(AutoChange.encode(b))
        assertEquals(AutoChange.NO_PROGRESS_TARGET, AutoChange.entry(back, ids[2])!!.change.target)
    }
}
