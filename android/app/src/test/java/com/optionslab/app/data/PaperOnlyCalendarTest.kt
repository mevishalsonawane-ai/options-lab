package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.ira.PaperOnlyPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.LocalDate

/**
 * 25 Oct (the Sunday CI run): the paper-only period on the phone, read against the market's calendar day by day. On a Sunday
 * after a Saturday end the order door is open again and the Live switch asks nothing; a period is on through its end date,
 * weekend or holiday, and over the next morning. The clock is set to the day in question, never read from the wall.
 */
class PaperOnlyCalendarTest : RobolectricTest() {
    private val sat = LocalDate.of(2026, 10, 24)
    private val sun = LocalDate.of(2026, 10, 25)
    private val mon = LocalDate.of(2026, 10, 26)

    /** The market clock at 11:00 IST on [d] (fixed). */
    private fun on(d: LocalDate) { Market.testClock = Clock.fixed(d.atTime(11, 0).atZone(IST).toInstant(), IST) }

    /** A period to [until] saved in the vault and read as at a start. */
    private fun saved(until: LocalDate) {
        SecurePrefs.put(PaperOnly.KEY, PaperOnlyPeriod.encode(PaperOnlyPeriod.Period(until, "10 days on paper", until.minusDays(10))))
        PaperOnly.unreadForTest(); PaperOnly.ensureLoaded()
    }

    @Test fun aPeriodThatEndedOnSaturdayRefusesNothingOnTheSunday() {
        saved(sat)
        on(sat)
        assertEquals(sat, Market.today())
        val why = PaperOnly.entryRefusal()
        assertNotNull("on its last day a new live entry is still refused", why)
        assertTrue(why!!, why.startsWith("You set a paper-only period until Sat 24 Oct"))
        assertTrue(PaperOnly.isRefusal(why))
        assertNotNull(PaperOnly.liveAskNow())
        on(sun)
        assertEquals(sun, Market.today())
        assertNull("over the day after its end, a Sunday", PaperOnly.entryRefusal())
        assertNull(PaperOnly.liveAskNow())
        assertNull(PaperOnly.activeNow())
        on(mon)
        assertNull(PaperOnly.entryRefusal())
    }

    @Test fun aPeriodThatEndsOnSundayRefusesEntriesThatSundayAndNotOnMonday() {
        saved(sun)
        on(sun)
        assertNotNull(PaperOnly.entryRefusal())
        assertEquals("Ending the paper-only period needs your PIN.", PaperOnly.end(pinConfirmed = false))
        assertNotNull("still set", PaperOnly.period.value)
        on(mon)
        assertNull(PaperOnly.entryRefusal())
        assertEquals("The paper-only period is over. The app stays in its mode until you switch it.", PaperOnly.end(pinConfirmed = false))
    }

    @Test fun aPeriodThatEndsOnAMarketHolidayIsOverTheNextMorning() {
        // Tue 10 Nov 2026 is Diwali Balipratipada on NSE's built-in list.
        val holiday = LocalDate.of(2026, 11, 10)
        assertTrue(Holidays.BUILT_IN.containsKey(holiday))
        saved(holiday)
        on(holiday)
        assertNotNull(PaperOnly.entryRefusal())
        on(holiday.plusDays(1))
        assertNull(PaperOnly.entryRefusal())
    }

    @Test fun onASundayAPeriodCanBeSetToEndOnTheSaturdayAfterWithoutThePin() {
        on(sun)
        val said = PaperOnly.set(sun.plusDays(6), "a week on paper", pinConfirmed = false)
        assertTrue(said, said.startsWith("Paper only until Sat 31 Oct (7 days left): a week on paper."))
        assertNotNull(PaperOnly.entryRefusal())
        // The end date that has passed (yesterday, the Saturday) is refused as on any day.
        assertEquals("The end date has already passed.", PaperOnly.set(sat, "x", pinConfirmed = false))
    }
}
