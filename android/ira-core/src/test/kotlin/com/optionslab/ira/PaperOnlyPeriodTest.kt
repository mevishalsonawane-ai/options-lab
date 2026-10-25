package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 25 Oct (the Sunday CI run): the paper-only period's calendar, day by day. A period runs THROUGH its end date and is over
 * the day after, whatever that day is - a Saturday end is over on the Sunday, a Sunday end on the Monday, an end on a market
 * holiday the next morning. The app's test that failed on Sunday 25 Oct had computed its dates from the wall clock while the
 * app read the pinned trading day (a test fault); these fix the rules themselves, so a weekend can never be read as "still on".
 */
class PaperOnlyPeriodTest {
    private val sat = LocalDate.of(2026, 10, 24)
    private val sun = LocalDate.of(2026, 10, 25)
    private val mon = LocalDate.of(2026, 10, 26)
    private val P = PaperOnlyPeriod

    private fun until(d: LocalDate, setOn: LocalDate = d.minusDays(10)) = PaperOnlyPeriod.Period(d, "10 days on paper", setOn)

    @Test fun aPeriodThatEndsOnSaturdayIsOnThroughSaturdayAndOverOnSunday() {
        val p = until(sat)
        assertTrue(P.active(p, sat.minusDays(1)))
        assertTrue(P.active(p, sat), "through its end date")
        assertFalse(P.active(p, sun), "over the next day, a Sunday")
        assertFalse(P.active(p, mon))
        // Over means no PIN to end it, and the status says so.
        assertFalse(P.needsPin(p, null, sun))
        assertEquals("No paper-only period set.", P.status(p, sun))
        assertEquals("Paper only until Sat 24 Oct (1 days left): 10 days on paper", P.status(p, sat))
    }

    @Test fun aPeriodThatEndsOnSundayIsOnThatSundayAndOverOnMonday() {
        val p = until(sun)
        assertTrue(P.active(p, sat))
        assertTrue(P.active(p, sun))
        assertFalse(P.active(p, mon))
        assertTrue(P.needsPin(p, null, sun), "ending it early on the Sunday still needs the PIN")
        assertFalse(P.needsPin(p, null, mon))
    }

    @Test fun aPeriodThatEndsOnAMarketHolidayIsOverTheNextMorning() {
        // Tue 10 Nov 2026 is Diwali Balipratipada (an NSE holiday on a weekday).
        val holiday = LocalDate.of(2026, 11, 10)
        val p = until(holiday)
        assertTrue(P.active(p, holiday.minusDays(1)))
        assertTrue(P.active(p, holiday))
        assertFalse(P.active(p, holiday.plusDays(1)))
    }

    @Test fun aPeriodMayBeSetOrEndAtTheWeekendLikeAnyOtherDay() {
        // Set on a Sunday to end on the Saturday after: fine, 7 days.
        assertNull(P.invalid(sun.plusDays(6), sun))
        // An end before today is refused on a Sunday as on a weekday; today itself is allowed.
        assertEquals("The end date has already passed.", P.invalid(sat, sun))
        assertNull(P.invalid(sun, sun))
        // Moving a running period's end from a weekday to the Saturday after it lowers risk: no PIN.
        val old = until(mon.plusDays(4))
        assertFalse(P.needsPin(old, until(mon.plusDays(5)), sun))
        // Moving it back to the Sunday from the Monday raises risk: the PIN.
        assertTrue(P.needsPin(until(mon), until(sun), sat))
    }

    @Test fun theDaysLeftCountTheWeekendTooAndTheEndDateIsSaidWithItsWeekday() {
        // Fri 23 Oct to Sun 25 Oct: 3 days left on the Friday, 1 on the Sunday.
        val p = until(sun)
        assertEquals("Paper only until Sun 25 Oct (3 days left): 10 days on paper", P.status(p, LocalDate.of(2026, 10, 23)))
        assertEquals("Paper only until Sun 25 Oct (1 days left): 10 days on paper", P.status(p, sun))
        assertEquals("Sat 24 Oct", P.day(sat))
        assertTrue(P.liveAsk(until(sat)).startsWith("You set a paper-only period until Sat 24 Oct: \"10 days on paper\"."))
    }
}
