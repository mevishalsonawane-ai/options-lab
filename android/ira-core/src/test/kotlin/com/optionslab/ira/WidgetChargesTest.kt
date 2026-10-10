package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Usefulness, round 35: the widgets' small charges line says Zerodha's exact contract-note figure once the app has it for
 * every order of the day ("Charges ₹X"), else the estimate ("Charges ≈ ₹X (estimate)"); the P&L calendar keeps which.
 */
class WidgetChargesTest {
    private val est = ExactCharges.Shown(180.0, estimate = true)
    private val exact = ExactCharges.Shown(163.4, estimate = false)

    @Test fun theExactFigureWinsOverTheEstimate() {
        assertEquals(exact, ExactCharges.widgetNext(est, 163.4, 180.0, checked = true))
        assertEquals(est, ExactCharges.widgetNext(exact, null, 180.0, checked = true))
        assertEquals(ExactCharges.Shown(200.0, true), ExactCharges.widgetNext(null, null, 200.0, checked = false))
    }

    @Test fun withNothingNewTheKeptFigureStays() {
        assertEquals(est, ExactCharges.widgetNext(est, null, null, checked = true))
        assertEquals(exact, ExactCharges.widgetNext(exact, null, null, checked = false), "a price tick does not check: the exact figure stays")
        assertNull(ExactCharges.widgetNext(null, null, null, checked = true))
        assertNull(ExactCharges.widgetNext(null, Double.NaN, Double.POSITIVE_INFINITY, checked = true))
    }

    @Test fun aKeptExactFigureThatNoLongerCoversTheDayIsNeverCalledExact() {
        assertEquals(ExactCharges.Shown(163.4, true), ExactCharges.widgetNext(exact, null, null, checked = true))
        assertEquals("Charges ≈ ₹163 (estimate)", ExactCharges.widgetNext(exact, null, null, checked = true)!!.let { PnlCharges.line(it.value, it.estimate) })
        assertEquals("Charges ₹163", PnlCharges.line(exact.value, exact.estimate))
    }

    @Test fun theWidgetKeepsTodaysFigureAndWhetherItIsExact() {
        val raw = ExactCharges.encodeDay("2026-10-05", exact)
        assertEquals("2026-10-05|163.40|x", raw)
        assertEquals(exact, ExactCharges.decodeDay(raw, "2026-10-05"))
        assertEquals(est, ExactCharges.decodeDay(ExactCharges.encodeDay("2026-10-05", est), "2026-10-05"))
        assertNull(ExactCharges.decodeDay(raw, "2026-10-06"), "another day's is never shown")
    }

    @Test fun anOlderBuildsRecordWasAnEstimate() {
        assertEquals(est, ExactCharges.decodeDay("2026-10-05|180.00", "2026-10-05"))
        assertNull(ExactCharges.decodeDay(null, "2026-10-05"))
        assertNull(ExactCharges.decodeDay("2026-10-05|abc|x", "2026-10-05"))
        assertNull(ExactCharges.decodeDay("2026-10-05", "2026-10-05"))
        assertNull(ExactCharges.decodeDay("2026-10-05|1|x|y", "2026-10-05"))
    }

    @Test fun theCalendarKeepsWhetherTheDaysChargesAreExact() {
        assertEquals(DayFigure.Kept(400.0, 2, 163.4, exact = true), DayFigure.next(DayFigure.Kept(400.0, 2, 180.0), 400.0, 2, 163.4, exact = true))
        assertNull(DayFigure.next(DayFigure.Kept(400.0, 2, 163.4, exact = true), 400.0, -1, -1.0), "a reading with no charges keeps the exact figure")
        assertEquals(DayFigure.Kept(380.0, 2, 163.4, exact = true), DayFigure.next(DayFigure.Kept(400.0, 2, 163.4, exact = true), 380.0, -1, -1.0))
        assertEquals(DayFigure.Kept(400.0, 3, 200.0), DayFigure.next(DayFigure.Kept(400.0, 2, 163.4, exact = true), 400.0, 3, 200.0),
            "an estimate after a new fill replaces it")
        assertEquals(DayFigure.Kept(400.0, 2, 0.0), DayFigure.next(null, 400.0, 2, 0.0, exact = true), "no charges are never exact")
    }
}
