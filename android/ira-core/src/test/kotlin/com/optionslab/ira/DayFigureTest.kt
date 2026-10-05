package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DayFigureTest {
    @Test fun firstReadingOfTheDayIsKept() {
        assertEquals(589.0 to 2, DayFigure.next(null, 589.0, 2))
        assertEquals(-150.0 to 0, DayFigure.next(null, -150.0, -1))
    }

    @Test fun theSameFigureIsNotWrittenAgain() {
        assertNull(DayFigure.next(589.0 to 2, 589.0, 2))
        assertNull(DayFigure.next(589.0 to 2, 589.004, 2))     // the same to the paisa
        assertNull(DayFigure.next(589.0 to 2, 589.0, -1))      // the count kept
    }

    @Test fun aNewFigureOrCountIsWritten() {
        assertEquals(589.01 to 2, DayFigure.next(589.0 to 2, 589.01, 2))
        assertEquals(589.0 to 3, DayFigure.next(589.0 to 2, 589.0, 3))
        assertEquals(-12.35 to 2, DayFigure.next(589.0 to 2, -12.345678, -1))
    }

    @Test fun paiseAsTheCalendarKeptThem() {
        assertEquals(12.35, DayFigure.paise(12.346))
        assertEquals(-0.01, DayFigure.paise(-0.0104))
    }

    @Test fun curveLatestReadingInAMinuteWins() {
        val pts = listOf(555 to 10.0, 556 to 12.5)
        assertEquals(listOf(555 to 10.0, 556 to 13.0), DayFigure.sample(pts, 556, 13.0, 480))
        assertEquals(listOf(555 to 10.0, 556 to 12.5, 557 to -4.25), DayFigure.sample(pts, 557, -4.249, 480))
    }

    @Test fun curveUnchangedMinuteIsNotWritten() {
        assertNull(DayFigure.sample(listOf(555 to 10.0, 556 to 12.5), 556, 12.5, 480))
        assertNull(DayFigure.sample(listOf(556 to 12.5), 556, 12.4999, 480))
    }

    @Test fun curveKeepsTheNewestAndSorts() {
        assertEquals(listOf(556 to 2.0, 557 to 3.0), DayFigure.sample(listOf(557 to 3.0, 555 to 1.0), 556, 2.0, 2))
        assertEquals(listOf(600 to 1.0), DayFigure.sample(emptyList(), 600, 1.0, 480))
    }
}
