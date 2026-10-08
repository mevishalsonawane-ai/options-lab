package com.optionslab.engine.risk

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Home's "Paper since 1 Oct" line (Boss's 08 Oct wish): the net after charges, the days counted and the average a day. */
class PaperSinceTest {
    private fun d(day: Int, month: Int = 10) = LocalDate.of(2026, month, day)

    @Test fun theDaysSinceTheStartAreAddedUp() {
        val days = mapOf(d(30, 9) to 9_999.0, d(1) to 2_000.0, d(3) to -1_200.0, d(6) to 1_500.0, d(7) to -582.0, d(8) to 3_772.0,
            d(9) to Double.NaN)
        val s = PaperSince.summary(days, PaperSince.DEFAULT_START)
        assertEquals(5_490.0, s.net, 1e-9)
        assertEquals(5, s.days)
        assertEquals(1_098.0, s.perDay, 1e-9)
        assertEquals("Paper since 1 Oct: +Rs 5,490 net over 5 days (avg Rs 1,098/day)", PaperSince.line(s))
    }

    @Test fun lossesOneDayAndNoDayAreSaidPlainly() {
        assertEquals("Paper since 6 Oct: -Rs 582 net over 1 day (avg -Rs 582/day)",
            PaperSince.line(PaperSince.summary(mapOf(d(7) to -582.0), d(6))))
        val none = PaperSince.summary(emptyMap(), d(6))
        assertEquals(0.0, none.perDay)
        assertEquals("Paper since 6 Oct: no trading day yet", PaperSince.line(none))
        assertEquals("Paper since 6 Oct: Rs 0 net over 1 day (avg Rs 0/day)", PaperSince.line(PaperSince.summary(mapOf(d(6) to 0.2), d(6))))
    }

    @Test fun aSavedStartIsReadBackOrTheFirstOfOctober() {
        assertEquals(d(5), PaperSince.startOf("2026-10-05"))
        assertEquals(d(5), PaperSince.startOf(" 2026-10-05 "))
        assertEquals(d(1), PaperSince.startOf(null))
        assertEquals(d(1), PaperSince.startOf("not a date"))
    }
}
