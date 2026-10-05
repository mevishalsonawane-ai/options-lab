package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DayKeptTest {
    private val kept = DayKept<Double?>()
    private var made = 0
    private fun figure(v: Double?): () -> Double? = { made++; v }

    @Test fun theSameDayAndBookGiveTheKeptFigure() {
        assertEquals(42.5, kept.of("2026-10-05", 3, figure(42.5)))
        // Every later watch pass with no new fill: not made again.
        repeat(5) { assertEquals(42.5, kept.of("2026-10-05", 3, figure(99.0))) }
        assertEquals(1, made)
    }

    @Test fun aNewFillOrANewDayMakesItAfresh() {
        kept.of("2026-10-05", 3, figure(42.5))
        assertEquals(60.0, kept.of("2026-10-05", 4, figure(60.0)))      // a fill recorded
        assertEquals(10.0, kept.of("2026-10-06", 4, figure(10.0)))      // the next session, same book
        assertEquals(42.5, kept.of("2026-10-05", 3, figure(42.5)))      // back again: made again, never mixed up
        assertEquals(4, made)
    }

    @Test fun noTradesIsKeptToo() {
        assertNull(kept.of("2026-10-05", 1, figure(null)))
        assertNull(kept.of("2026-10-05", 1, figure(5.0)))
        assertEquals(1, made)
    }

    @Test fun aFailureKeepsNothing() {
        kept.of("2026-10-05", 1, figure(7.0))
        assertFailsWith<IllegalStateException> { kept.of("2026-10-05", 2) { error("vault unreadable") } }
        assertEquals(8.0, kept.of("2026-10-05", 2, figure(8.0)))
        assertEquals(7.0, kept.of("2026-10-05", 1, figure(7.0)))
        assertEquals(3, made)
    }

    @Test fun forgetMakesItAfresh() {
        kept.of("2026-10-05", 1, figure(7.0))
        kept.forget()
        assertEquals(9.0, kept.of("2026-10-05", 1, figure(9.0)))
        assertEquals(2, made)
    }
}
