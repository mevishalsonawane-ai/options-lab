package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PnlStartTest {
    private val mon16 = LocalDate.of(2026, 11, 16)
    private val tue17 = LocalDate.of(2026, 11, 17)
    // Weekdays, less one made-up holiday (Wed 18 Nov) for the tests.
    private val holiday = LocalDate.of(2026, 11, 18)
    private val trading: (LocalDate) -> Boolean = { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY && it != holiday }

    private data class Day(val pnl: Double, val charges: Double)

    private val days = mapOf(
        LocalDate.of(2026, 11, 13) to Day(-4_000.0, 120.0),
        mon16 to Day(1_500.0, 80.0),
        tue17 to Day(2_000.0, 90.0),
        LocalDate.of(2026, 11, 19) to Day(-500.0, 40.0),
    )

    @Test fun theStartReadsBackAndBlankOrBrokenShowsEverything() {
        assertEquals(tue17, PnlStart.startOf("2026-11-17"))
        assertEquals(tue17, PnlStart.startOf(" 2026-11-17 "))
        assertNull(PnlStart.startOf(null))
        assertNull(PnlStart.startOf(""))
        assertNull(PnlStart.startOf("   "))
        assertNull(PnlStart.startOf("tomorrow"))
        assertNull(PnlStart.startOf("2026-13-40"))
    }

    @Test fun tomorrowIsTheNextTradingDay() {
        // Today, Mon 16 Nov 2026 (evening): Tue 17 Nov.
        assertEquals(tue17, PnlStart.nextTradingDay(mon16, trading))
        // The day before a holiday skips it.
        assertEquals(LocalDate.of(2026, 11, 19), PnlStart.nextTradingDay(tue17, trading))
        // Friday and the weekend give the Monday.
        val fri = LocalDate.of(2026, 11, 20); val mon23 = LocalDate.of(2026, 11, 23)
        assertEquals(mon23, PnlStart.nextTradingDay(fri, trading))
        assertEquals(mon23, PnlStart.nextTradingDay(fri.plusDays(1), trading))
        assertEquals(mon23, PnlStart.nextTradingDay(fri.plusDays(2), trading))
    }

    @Test fun aStartOnAWeekendOrHolidayMovesToTheNextTradingDay() {
        assertEquals(LocalDate.of(2026, 11, 19), PnlStart.from(true, "2026-11-18", trading))
        assertEquals(LocalDate.of(2026, 11, 23), PnlStart.from(true, "2026-11-21", trading))
        assertEquals(LocalDate.of(2026, 11, 23), PnlStart.from(true, "2026-11-22", trading))
        assertEquals(tue17, PnlStart.from(true, "2026-11-17", trading))
    }

    @Test fun aBrokenCalendarNeverHangsOrThrows() {
        assertEquals(tue17, PnlStart.onOrAfter(tue17) { false })
        assertEquals(tue17, PnlStart.onOrAfter(tue17) { error("calendar unreadable") })
        assertEquals(tue17, PnlStart.nextTradingDay(mon16) { false })
    }

    @Test fun paperAndNoStartShowEveryDay() {
        assertNull(PnlStart.from(false, "2026-11-17", trading))
        assertNull(PnlStart.from(true, null, trading))
        assertNull(PnlStart.from(true, "", trading))
        assertSame(days, PnlStart.shown(days, null))
        assertTrue(PnlStart.shows(LocalDate.of(2000, 1, 1), null))
    }

    @Test fun daysBeforeTheStartAreHiddenOnAndAfterShown() {
        val from = PnlStart.from(true, "2026-11-17", trading)
        assertFalse(PnlStart.shows(LocalDate.of(2026, 11, 13), from))
        // Today (16 Nov), before the start, is hidden too.
        assertFalse(PnlStart.shows(mon16, from))
        assertTrue(PnlStart.shows(tue17, from))
        assertTrue(PnlStart.shows(LocalDate.of(2026, 11, 19), from))
        val shown = PnlStart.shown(days, from)
        assertEquals(setOf(tue17, LocalDate.of(2026, 11, 19)), shown.keys)
    }

    @Test fun totalsCountOnlyTheShownDaysAndTheDataIsUntouched() {
        val before = days.toMap()
        val shown = PnlStart.shown(days, tue17)
        assertEquals(1_500.0, shown.values.sumOf { it.pnl })
        assertEquals(130.0, shown.values.sumOf { it.charges })
        assertEquals(2, shown.size)
        // The source map (the risk rules' data) is the same after filtering.
        assertEquals(before, days)
        assertEquals(-1_000.0, days.values.sumOf { it.pnl })
    }

    @Test fun anEmptyBookStaysEmpty() {
        assertEquals(emptyMap(), PnlStart.shown(emptyMap<LocalDate, Day>(), tue17))
    }

    @Test fun journalNotesBeforeTheStartOrOnAHiddenTripAreHidden() {
        // 17 Nov 2026 00:00 IST = 16 Nov 18:30 UTC.
        val since = PnlStart.sinceMillis(tue17)
        assertEquals(java.time.Instant.parse("2026-11-16T18:30:00Z").toEpochMilli(), since)
        assertEquals(Long.MIN_VALUE, PnlStart.sinceMillis(null))
        assertTrue(PnlStart.showsNote("kite:1", since, since, emptySet()))
        assertFalse(PnlStart.showsNote("kite:1", since - 1, since, emptySet()))
        // Written after the start, but on a fill of a trip hidden by it.
        assertFalse(PnlStart.showsNote("kite:9", since + 60_000, since, setOf("kite:9")))
        // No start: every note.
        assertTrue(PnlStart.showsNote("kite:1", 0L, PnlStart.sinceMillis(null), emptySet()))
    }

    @Test fun theWords() {
        assertEquals("Showing live P&L from Tue 17 Nov 2026.", PnlStart.line(tue17))
        assertEquals("Earlier live days are hidden here, not deleted; risk rules still count them.", PnlStart.NOTE)
    }
}
