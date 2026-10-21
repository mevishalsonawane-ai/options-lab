package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 21 Oct: commodities' own paper limits in the Autopilot change timeline ([AutoChange.segmentSplit]): Boss's, one tap to undo. */
class SegmentSplitChangeTest {
    private val day = LocalDate.of(2026, 10, 21)

    @Test fun itIsBosssPaperChangeAndItsUndoPutsTheSharedLockBackWithoutAPin() {
        val c = AutoChange.segmentSplit()
        assertFalse(AutoChange.never(c), "no safety word in its target or values, so its Undo is never held")
        assertEquals(AutoChange.Scope.PAPER, c.scope)
        assertEquals(AutoChange.Risk.RAISE, AutoChange.riskOf(c))
        assertTrue(AutoChange.bossOwn(c.kind))
        assertTrue(c.reason.contains("21 Oct") && c.reason.contains("Paper only, never live"))
        val (book, e) = AutoChange.record(AutoChange.Book(), c, AutoChange.Status.KEPT, 1L, day, by = "your choice")
        assertNull(AutoChange.lastUndoable(book), "never undone by \"undo the last change\": only its own tap")
        val u = assertNotNull(AutoChange.undo(book, e.id))
        assertEquals(AutoChange.SWITCHED_OFF, u.inverse.to)
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(u.inverse))
        assertFalse(u.needsPin)
        // Switched on again later in Bot settings: only the newest entry undoes.
        val (b2, _) = AutoChange.record(book, c, AutoChange.Status.KEPT, 2L, day, by = "your choice")
        assertTrue(AutoChange.superseded(b2, e))
    }
}
