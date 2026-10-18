package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
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

    @After fun down() = Holidays.resetForTest()

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

    @Test fun withoutNsesListTheBuiltInHolidaysCloseTheMarket() {
        val gandhi = LocalDate.of(2026, 10, 2)
        assertTrue("nothing fetched: the built-in list", Holidays.isHoliday(gandhi))
        assertFalse(Market.isTradingDay(gandhi))
        assertEquals(gandhi to "Mahatma Gandhi Jayanti", Holidays.book().upcoming(LocalDate.of(2026, 10, 1)).first())
        // The owner can still overrule it.
        Holidays.remove(gandhi)
        assertTrue(Market.isTradingDay(gandhi))
        Holidays.add(gandhi)
        // A fetched NSE list for the year replaces the built-in one.
        writeNse(LocalDate.of(2026, 9, 20), mapOf(LocalDate.of(2026, 10, 20) to "Dussehra"))
        assertFalse(Holidays.isHoliday(gandhi))
        assertTrue(Holidays.isHoliday(LocalDate.of(2026, 10, 20)))
    }

    @Test fun upstoxsPublicListGivesTheDaysTheDerivativesAreShut() {
        // Upstox's reply on 2026-10-02 (CI probe), shortened: a trading holiday, a Sunday budget session, a settlement holiday.
        val body = """{"status":"success","data":[
            {"date":"2026-01-15","description":"Municipal Corporation Election","holiday_type":"TRADING_HOLIDAY","closed_exchanges":["NSE","NFO","CDS","BSE","BFO","BCD"],"open_exchanges":[]},
            {"date":"2026-02-01","description":"Budget Day Session","holiday_type":"SPECIAL_TIMING","closed_exchanges":["CDS","BCD"],"open_exchanges":[{"exchange":"NFO","start_time":1769917500000,"end_time":1769940000000}]},
            {"date":"2026-02-19","description":"Chhatrapati Shivaji Maharaj Jayanti","holiday_type":"SETTLEMENT_HOLIDAY","closed_exchanges":[],"open_exchanges":[]},
            {"date":"2026-10-02","description":"Gandhi Jayanti","holiday_type":"TRADING_HOLIDAY","closed_exchanges":["NSE","NFO","CDS","BSE","BFO","BCD","MCX","NSCOM"],"open_exchanges":[]}]}"""
        assertEquals(mapOf(LocalDate.of(2026, 1, 15) to "Municipal Corporation Election", LocalDate.of(2026, 10, 2) to "Gandhi Jayanti"),
            Holidays.parseUpstox(body))
    }

    @Test fun aBackgroundRefreshUpdatesTheCalendarWhenItLands() {
        writeNse(LocalDate.of(2026, 9, 1), mapOf(LocalDate.of(2026, 10, 20) to "Dussehra"))
        val release = java.util.concurrent.CountDownLatch(1)
        Holidays.testList = { release.await(); mapOf(monday to "Test holiday") }
        val job = Holidays.refreshInBackground()
        assertSame("one refresh at a time", job, Holidays.refreshInBackground())
        assertFalse("the cached list until it lands", Holidays.isHoliday(monday))
        assertTrue(Holidays.isHoliday(LocalDate.of(2026, 10, 20)))
        release.countDown()
        kotlinx.coroutines.runBlocking { job.join() }
        assertTrue(Holidays.isHoliday(monday))
        assertFalse("NSE's new list replaces the old", Holidays.isHoliday(LocalDate.of(2026, 10, 20)))
        restart()
        assertTrue("kept on disk", Holidays.isHoliday(monday))
    }

    @Test fun aHangingRefreshEndsAtItsLimitAndLeavesTheCachedList() {
        writeNse(LocalDate.of(2026, 9, 1), mapOf(LocalDate.of(2026, 10, 20) to "Dussehra"))
        val interrupted = java.util.concurrent.CountDownLatch(1)
        Holidays.testList = {
            try { Thread.sleep(600_000) } catch (e: InterruptedException) { interrupted.countDown(); throw e }
            emptyMap()
        }
        val t0 = System.nanoTime()
        val job = Holidays.refreshInBackground(timeoutMs = 300)
        kotlinx.coroutines.runBlocking { job.join() }
        assertTrue("ended at its limit", (System.nanoTime() - t0) / 1e9 < 10)
        assertTrue("the hanging read is stopped", interrupted.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(LocalDate.of(2026, 9, 1), Holidays.book().fetched)
        assertTrue(Holidays.isHoliday(LocalDate.of(2026, 10, 20)))
        // The next refresh is a new one, and a failing site leaves the list as it was.
        Holidays.testList = { throw java.io.IOException("simulated: NSE refused") }
        val next = Holidays.refreshInBackground()
        assertNotSame(job, next)
        kotlinx.coroutines.runBlocking { next.join() }
        assertEquals(LocalDate.of(2026, 9, 1), Holidays.book().fetched)
    }

    @Test fun cancellingTheRefreshStopsItsRead() {
        val interrupted = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.CountDownLatch(1)
        Holidays.testList = {
            started.countDown()
            try { Thread.sleep(600_000) } catch (e: InterruptedException) { interrupted.countDown(); throw e }
            emptyMap()
        }
        val job = Holidays.refreshInBackground()
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel()
        assertTrue("the read is stopped with it", interrupted.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertNull(Holidays.book().fetched)
    }

    @Test fun holidaysAddedByHandCloseTheMarketAndCanBeUndone() {
        Holidays.add(monday)
        assertTrue(Holidays.isHoliday(monday))
        assertFalse(Market.isTradingDay(monday))
        assertEquals(monday to "added by you", Holidays.book().upcoming(monday.minusDays(1)).first())
        assertTrue("past days are not upcoming", Holidays.book().upcoming(monday.plusDays(1)).none { it.first == monday })
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
