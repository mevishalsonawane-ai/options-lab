package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 22 Nov 2026 (B6 review): "max lots per order" and "max manual orders per day" could be set by voice but no order check read them. Where
 * either was set on a phone, Boss is told once in the change timeline that it was never enforced and which limits are; nothing is changed.
 */
class DeadOrderLimitsTest {
    private fun note(lots: Int = 2, orders: Int = 4, logged: Set<String> = emptySet(), guardLots: Int = 2, guardOrders: Int = 10) =
        AutoChange.deadOrderLimits(lots, 2, orders, 4, logged, guardLots, guardOrders)

    @Test fun neitherSetSaysNothing() {
        assertNull(note())
        assertNull(note(logged = setOf("MAX_LOTS", "DAILY_LOSS")))
    }

    @Test fun aValueAwayFromItsDefaultOrOneInTheLogIsSaidWithTheLimitsInForce() {
        val c = note(lots = 1)!!
        assertEquals(AutoChange.Kind.SETTING_NOTE, c.kind)
        assertEquals(AutoChange.DEAD_LIMITS_TARGET, c.target)
        assertEquals("max lots per order 1", c.from)
        assertEquals(AutoChange.NEVER_ENFORCED, c.to)
        assertTrue(c.reason.startsWith("Max lots per order 1, set earlier, was never checked by any order."), c.reason)
        assertTrue(c.reason.contains("max lots per instrument 2 (each order too) and max orders per day 10"), c.reason)
        assertTrue(c.reason.contains("Nothing was changed"), c.reason)
        // Set by voice to its default value: the log says so.
        val both = note(logged = setOf("ORDERS_PER_DAY", "LOTS_PER_ORDER"), guardOrders = 0)!!
        assertEquals("max lots per order 2; max manual orders per day 4", both.from)
        assertTrue(both.reason.contains("were never checked") && both.reason.contains("max orders per day off"), both.reason)
        // A note: lowers nothing, raises nothing, and is never "undone" with a PIN.
        assertEquals(AutoChange.Risk.LOWER, AutoChange.riskOf(c))
        assertFalse(AutoChange.undoNeedsPin(c))
    }

    @Test fun inTheTimelineItIsANoteWithNoUndo() {
        val day = LocalDate.of(2026, 11, 22)
        val (book, e) = AutoChange.record(AutoChange.Book(), note(orders = 3)!!, AutoChange.Status.SKIPPED, 1L, day, by = "the 22 Nov update")
        assertFalse(e.applied, "never counted as a change made")
        assertNull(AutoChange.undo(book, e.id))
        assertNull(AutoChange.lastUndoable(book))
        val items = ChangeTimeline.fromAutopilot(book, day)
        assertEquals(1, items.size)
        assertEquals(ChangeTimeline.UndoState.NO_UNDO, items.single().undo)
        assertEquals(0, AutoChange.summary(book, day).today)
        // Kept across a save.
        assertEquals(book.entries, AutoChange.decode(AutoChange.encode(book)).entries)
    }
}
