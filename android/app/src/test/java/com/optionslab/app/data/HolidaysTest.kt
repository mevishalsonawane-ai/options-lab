package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/** NSE holidays (NSE's list, the owner's corrections, extra weekend sessions) and what they do to the trading calendar. */
class HolidaysTest : RobolectricTest() {
    private fun file() = File(context.filesDir, "holidays.json")

    /** The book kept in memory outlives a test (TestApp does not clear it): drop it, as a cold start does. */
    private fun restart() = Holidays::class.java.getDeclaredField("cache").apply { isAccessible = true }.set(null, null)

    @Before fun fresh() = restart()

    private val monday = LocalDate.of(2026, 10, 5)
    private val saturday = LocalDate.of(2026, 10, 3)

    @Test fun aFreshPhoneKnowsNoHolidays() {
        val b = Holidays.book()
        assertNull(b.fetched)
        assertTrue(b.nse.isEmpty() && b.added.isEmpty() && b.removed.isEmpty() && b.extra.isEmpty())
        assertFalse(Holidays.isHoliday(monday))
        assertTrue(Market.isTradingDay(monday))
        assertFalse("weekends are shut", Market.isTradingDay(saturday))
        assertTrue(Holidays.stale(monday))
    }

    @Test fun holidaysAddedByHandCloseTheMarketAndCanBeUndone() {
        Holidays.add(monday)
        assertTrue(Holidays.isHoliday(monday))
        assertFalse(Market.isTradingDay(monday))
        assertEquals(listOf(monday to "added by you"), Holidays.book().upcoming(monday.minusDays(1)))
        assertTrue("past days are not upcoming", Holidays.book().upcoming(monday.plusDays(1)).isEmpty())
        restart()
        assertTrue("kept on disk", Holidays.isHoliday(monday))
        Holidays.remove(monday)
        assertFalse(Holidays.isHoliday(monday))
        assertTrue(Market.isTradingDay(monday))
    }

    private fun writeNse(fetched: LocalDate, days: Map<LocalDate, String>) {
        file().writeText(JSONObject().put("fetched", fetched.toString())
            .put("nse", JSONObject().apply { days.forEach { (d, n) -> put(d.toString(), n) } })
            .put("added", JSONArray()).put("removed", JSONArray()).put("extra", JSONArray()).toString())
        restart()
    }

    @Test fun nseDatesAreReadAndTheOwnerCanOverruleThem() {
        val gandhi = LocalDate.of(2026, 10, 2)
        writeNse(LocalDate.of(2026, 9, 20), mapOf(gandhi to "Mahatma Gandhi Jayanti", monday to "Test holiday"))
        assertTrue(Holidays.isHoliday(gandhi))
        assertEquals(listOf(gandhi to "Mahatma Gandhi Jayanti", monday to "Test holiday"), Holidays.book().upcoming(LocalDate.of(2026, 9, 26)))
        // NSE is wrong about one: removing it opens the market that day, and survives a restart.
        Holidays.remove(monday)
        restart()
        assertFalse(Holidays.isHoliday(monday))
        assertTrue(Market.isTradingDay(monday))
        assertEquals(listOf(gandhi to "Mahatma Gandhi Jayanti"), Holidays.book().upcoming(LocalDate.of(2026, 9, 26)))
        // Adding it back clears the removal.
        Holidays.add(monday)
        assertTrue(Holidays.isHoliday(monday))
        assertEquals("Test holiday", Holidays.book().upcoming(monday).first().second)
    }

    @Test fun anExtraWeekendSessionIsATradingDay() {
        assertEquals(DayOfWeek.SATURDAY, saturday.dayOfWeek)
        Holidays.addSession(saturday)
        assertTrue(Holidays.isExtraSession(saturday))
        assertTrue(Market.isTradingDay(saturday))
        restart()
        assertTrue("kept on disk", Market.isTradingDay(saturday))
        Holidays.removeSession(saturday)
        assertFalse(Holidays.isExtraSession(saturday))
        assertFalse(Market.isTradingDay(saturday))
    }

    @Test fun anExtraSessionOutranksAHoliday() {
        Holidays.add(saturday)
        Holidays.addSession(saturday)
        assertTrue(Market.isTradingDay(saturday))
    }

    @Test fun theListIsStaleAfterAWeekOrANewYear() {
        writeNse(LocalDate.of(2026, 9, 20), emptyMap())
        assertFalse(Holidays.stale(LocalDate.of(2026, 9, 26)))
        assertFalse(Holidays.stale(LocalDate.of(2026, 9, 27)))
        assertTrue(Holidays.stale(LocalDate.of(2026, 9, 28)))
        writeNse(LocalDate.of(2026, 12, 30), emptyMap())
        assertTrue("a new year needs the new list", Holidays.stale(LocalDate.of(2027, 1, 2)))
    }

    @Test fun aDamagedFileReadsAsNoList() {
        file().writeText("{ not json")
        restart()
        assertNull(Holidays.book().fetched)
        assertFalse(Holidays.isHoliday(monday))
        // And the next change writes a good file over it.
        Holidays.add(monday)
        restart()
        assertTrue(Holidays.isHoliday(monday))
    }
}
