package com.optionslab.engine.orb

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R150 B's W20 (a shadow log on R51): the wall's edges, the touches, the put side, and an unknown price. */
class W20RuleTest {
    private val day = LocalDate.of(2026, 11, 6)

    @Test fun aLevelWithin025PercentAheadIsAWall() {
        val lv = listOf(100.25 to 3, 99.75 to 3)
        assertTrue(W20Rule.wallAhead(lv, 100.0, 1), "exactly 0.25% above a call")
        assertTrue(W20Rule.wallAhead(lv, 100.0, -1), "exactly 0.25% below a put")
        assertFalse(W20Rule.wallAhead(listOf(100.26 to 3), 100.0, 1), "further than 0.25%")
        assertFalse(W20Rule.wallAhead(listOf(100.1 to 2), 100.0, 1), "two touches are not a level")
        assertFalse(W20Rule.wallAhead(listOf(100.0 to 5), 100.0, 1), "at the price is not ahead")
        assertFalse(W20Rule.wallAhead(listOf(100.1 to 3), 100.0, -1), "behind a put")
        assertFalse(W20Rule.wallAhead(emptyList(), 100.0, 1))
    }

    @Test fun noPriceBeforeTheEntryIsUnknownAndNoHistoryHasNoWall() {
        val n = PassedArms.Session.N
        val empty = PassedArms.DayBars(day, DoubleArray(n) { Double.NaN }, DoubleArray(n) { Double.NaN }, DoubleArray(n) { Double.NaN }, DoubleArray(n) { Double.NaN })
        assertNull(W20Rule.skips(emptyList(), empty, 10, 1))
        val c = DoubleArray(n) { if (it < 10) 100.0 + it else Double.NaN }
        val d = PassedArms.DayBars(day, c.copyOf(), c.map { it + 1 }.toDoubleArray(), c.map { it - 1 }.toDoubleArray(), c)
        assertEquals(false, W20Rule.skips(emptyList(), d, 10, 1))
        assertTrue(R72Picker.levels(emptyList()).isEmpty())
    }
}
