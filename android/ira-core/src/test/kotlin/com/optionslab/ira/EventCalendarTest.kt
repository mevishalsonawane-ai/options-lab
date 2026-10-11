package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The event-day calendar and the learning filter (TODO S11). */
class EventCalendarTest {
    @AfterTest fun reset() = LearningFilter.resetForTest()

    private fun d(s: String) = LocalDate.parse(s)
    private val none: (LocalDate) -> Boolean = { false }

    @Test fun theBundledDatesHaveASourceAndTheUnpublishedOnesAreTentative() {
        assertTrue(EventCalendar.BUNDLED.all { it.source.isNotBlank() })
        assertTrue(EventCalendar.BUNDLED.any { it.kind == EventCalendar.Kind.BUDGET && it.day == d("2026-02-01") && !it.tentative })
        assertTrue(EventCalendar.BUNDLED.any { it.kind == EventCalendar.Kind.RBI && it.tentative })
        assertTrue(EventCalendar.BUNDLED.all { it.day.year in 2026..2027 })
        assertTrue(EventCalendar.line(EventCalendar.BUNDLED.first { it.tentative }).endsWith("(tentative)"))
        assertEquals("Sun 1 Feb 2026 · Union Budget", EventCalendar.line(EventCalendar.BUNDLED.first { it.day == d("2026-02-01") }))
    }

    @Test fun theRebalancesFallOnTheLastTradingDayOfTheirMonths() {
        val r = EventCalendar.rules(d("2026-01-01"), d("2026-12-31"), none).filter { it.kind == EventCalendar.Kind.REBALANCE }
        assertEquals(6, r.size)
        assertTrue(r.any { it.day == d("2026-03-31") && it.name.startsWith("NSE") })
        // 30 May 2026 is a Saturday: Friday the 29th.
        assertTrue(r.any { it.day == d("2026-05-29") && it.name.startsWith("MSCI") })
        // A holiday on the last weekday moves it one trading day back.
        val h = EventCalendar.rules(d("2026-09-01"), d("2026-09-30"), { it == d("2026-09-30") }).single { it.kind == EventCalendar.Kind.REBALANCE }
        assertEquals(d("2026-09-29"), h.day)
    }

    @Test fun aHolidayOnAnExpiryDayMarksTheTradingDayBefore() {
        // Tuesday 20 Oct 2026 a holiday: Nifty's expiry moves to Monday the 19th; Thursday 22nd a holiday too: Sensex to Wed 21st.
        val hol = setOf(d("2026-10-20"), d("2026-10-22"))
        val s = EventCalendar.rules(d("2026-10-15"), d("2026-10-25"), { it in hol }).filter { it.kind == EventCalendar.Kind.EXPIRY_SHIFT }
        assertEquals(listOf(d("2026-10-19"), d("2026-10-21")), s.map { it.day }.sorted())
        assertTrue(s.first { it.day == d("2026-10-19") }.name.startsWith("NIFTY expiry moved"))
        // A Monday holiday before the Tuesday too: back to Friday.
        val s2 = EventCalendar.rules(d("2026-10-15"), d("2026-10-25"), { it == d("2026-10-20") || it == d("2026-10-19") })
            .single { it.kind == EventCalendar.Kind.EXPIRY_SHIFT }
        assertEquals(d("2026-10-16"), s2.day)
    }

    @Test fun bossAddsAndRemovesDays() {
        val owner = EventCalendar.fromEvents(listOf(Events.Event(d("2026-11-14"), "State election results", owner = true),
            Events.Event(d("2026-11-20"), "Built in", owner = false)))
        assertEquals(1, owner.size)
        val c = EventCalendar.calendar(d("2026-01-01"), d("2026-12-31"), none, owner, removed = setOf(d("2026-12-04")))
        assertTrue(c.isEventDay(d("2026-11-14")))
        assertEquals(EventCalendar.Kind.OWNER, c.on(d("2026-11-14")).single().kind)
        assertFalse(c.isEventDay(d("2026-12-04")))
        assertTrue(c.isEventDay(d("2026-10-01")))
        assertFalse(c.isEventDay(d("2026-10-12")))
        assertEquals("Event day: learning ignores today's results (RBI policy).", EventCalendar.note(c, d("2026-10-01")))
        assertNull(EventCalendar.note(c, d("2026-10-12")))
        assertTrue(c.between(d("2026-11-01"), d("2026-11-30")).any { it.name == "State election results" })
    }

    @Test fun learningLeavesEventDaysOutOfTheEvidenceButKeepsThemApart() {
        val c = EventCalendar.calendar(d("2026-01-01"), d("2026-12-31"), none)
        LearningFilter.use(c)
        assertFalse(LearningFilter.include(d("2026-02-01")))
        assertTrue(LearningFilter.include(d("2026-02-02")))
        assertEquals(LearningFilter.Bucket.EVENT_DAY, LearningFilter.bucket(d("2026-02-01")))
        assertEquals(LearningFilter.Bucket.EVIDENCE, LearningFilter.bucket(d("2026-02-02")))
        val trades = listOf(d("2026-02-01") to -500.0, d("2026-02-02") to 200.0, d("2026-03-31") to 900.0, d("2026-04-01") to 50.0)
        val s = LearningFilter.split(trades) { it.first }
        assertEquals(listOf(200.0, 50.0), s.evidence.map { it.second })
        assertEquals(listOf(-500.0, 900.0), s.eventDays.map { it.second })
        LearningFilter.excludeEventDays = false
        assertTrue(LearningFilter.include(d("2026-02-01")))
        assertEquals(4, LearningFilter.split(trades) { it.first }.evidence.size)
        LearningFilter.resetForTest()
        // The default (no holidays known yet) still knows the bundled days.
        assertFalse(LearningFilter.include(d("2026-02-01")))
        assertTrue(LearningFilter.calendar.isEventDay(d("2027-02-01")))
    }
}
