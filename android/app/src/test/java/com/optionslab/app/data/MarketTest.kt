package com.optionslab.app.data

import com.optionslab.app.testing.Background
import com.optionslab.app.testing.Background.WED
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.UpstoxStub
import com.optionslab.app.testing.UpstoxStub.Companion.isHistory
import com.optionslab.app.testing.UpstoxStub.Companion.isIntraday
import com.optionslab.app.testing.UpstoxStub.Companion.status
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalTime

/**
 * The market calendar and the index quote the watch and the masthead read (Upstox feed, Paper
 * mode): a last-session price is shown only while the market is shut, never passed off as live.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class MarketTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.BackgroundWatchdog()
    private lateinit var upstox: UpstoxStub
    private val tue = WED.minusDays(1)
    private val nifty = Upstox.INDEX_KEYS.getValue("NIFTY")

    @Before fun up() {
        upstox = UpstoxStub()
        Background.calendar(WED)
        Background.clearChartCache()
    }

    @After fun down() {
        upstox.close(); Background.reset()
        assertEquals("a test reached the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    @Test fun tradingDaysFollowTheCalendarAndSpecialSessions() {
        val sat = WED.plusDays(3)
        val diwali = WED.plusDays(6)          // a Tuesday made a holiday
        val budget = WED.plusDays(10)         // a Saturday NSE opens
        Background.calendar(WED, holidays = setOf(diwali), extra = setOf(budget))
        assertTrue(Market.isTradingDay(WED))
        assertFalse(Market.isTradingDay(sat))
        assertFalse(Market.isWeekday(sat))
        assertFalse(Market.isTradingDay(diwali))
        assertTrue("a special session trades although it is a weekend", Market.isTradingDay(budget))
        Holidays.addSession(diwali)
        assertTrue("a session NSE called wins over the holiday list", Market.isTradingDay(diwali))
        Holidays.removeSession(diwali)
        assertFalse(Market.isTradingDay(diwali))
    }

    @Test fun openOnlyInSessionHoursOfATradingDay() {
        Background.at(WED, 9, 14); assertFalse(Market.isOpen())
        Background.at(WED, 9, 15); assertTrue(Market.isOpen())
        Background.at(WED, 15, 29); assertTrue(Market.isOpen())
        Background.at(WED, 15, 30); assertFalse(Market.isOpen())
        Background.at(WED.plusDays(3), 11, 0); assertFalse("Saturday", Market.isOpen())
        Background.calendar(WED, holidays = setOf(WED))
        Background.at(WED, 11, 0); assertFalse("holiday", Market.isOpen())
        assertEquals(11 * 60, Market.minuteNow())
        assertEquals(WED, Market.today())
    }

    /** F&O trades to 15:40 from 3 Aug 2026 (15:30 before); the index's session still ends 15:30 on both sides. */
    @Test fun foHoursMoveTo1540From3Aug2026AndTheIndexStaysAt1530() {
        val july = java.time.LocalDate.of(2026, 7, 29)     // a Wednesday before the change
        val aug = java.time.LocalDate.of(2026, 8, 5)       // a Wednesday after it
        Background.calendar(WED)
        Market.testOrdersAnyTime = false
        try { foHours(july, aug) } finally { Market.testOrdersAnyTime = true }
    }

    private fun foHours(july: java.time.LocalDate, aug: java.time.LocalDate) {
        assertEquals(15 * 60 + 30, Market.foClose(july))
        assertEquals(15 * 60 + 40, Market.foClose(aug))
        assertEquals(15 * 60 + 30, Market.INDEX_CLOSE)
        Background.at(july, 15, 29); assertTrue(Market.isOpen()); assertTrue(Market.isIndexOpen())
        Background.at(july, 15, 30); assertFalse(Market.isOpen()); assertFalse(Market.isIndexOpen())
        assertEquals("Market is closed now: orders are taken 09:15-15:30 on trading days.", Market.CLOSED_FOR_ORDERS)
        Background.at(july, 15, 35); assertFalse("15:35 before 3 Aug 2026: F&O was shut", Market.acceptsOrders())
        Background.at(aug, 15, 30); assertTrue("F&O still trades", Market.isOpen()); assertFalse("the index has closed", Market.isIndexOpen())
        Background.at(aug, 15, 35); assertTrue(Market.isOpen()); assertTrue(Market.acceptsOrders())
        Background.at(aug, 15, 39); assertTrue(Market.isOpen())
        Background.at(aug, 15, 40); assertFalse(Market.isOpen()); assertFalse(Market.acceptsOrders())
        assertEquals("Market is closed now: orders are taken 09:15-15:40 on trading days.", Market.CLOSED_FOR_ORDERS)
        // The pure check a replay of either day reads.
        assertTrue(Market.isOpenAt(aug, 15 * 60 + 35)); assertFalse(Market.isOpenAt(july, 15 * 60 + 35))
        assertFalse("a weekend", Market.isOpenAt(aug.plusDays(3), 11 * 60))
    }

    @Test fun theClockSeamIsDebugOnlyAndDefaultsToTheSystemClock() {
        assertNull(Market.testClock)
        val before = java.time.ZonedDateTime.now(com.optionslab.engine.IST).minusSeconds(5)
        assertTrue(Market.now().isAfter(before))
        assertEquals(com.optionslab.engine.IST, Market.now().zone)
    }

    private fun serve(today: List<Upstox.Bar>?, past: List<Upstox.Bar>) {
        upstox.reply = { p ->
            when {
                isIntraday(p) -> if (today == null) status(500) else UpstoxStub.candles(today)
                isHistory(p) -> UpstoxStub.candles(past)
                else -> status(404)
            }
        }
    }

    @Test fun inSessionTodaysBarsAreTheQuote() = runBlocking {
        Background.at(WED, 11, 0)
        serve(UpstoxStub.minutes(WED, LocalTime.of(9, 15), 30, 24_800.0), UpstoxStub.minutes(tue, LocalTime.of(15, 0), 30, 24_000.0))
        val q = Market.quote("NIFTY", quick = true)!!
        assertFalse(q.lastSession)
        assertEquals(24_800.0, q.open, 0.0)
        assertEquals(24_800.0 + 29 * 0.5 + 0.25, q.last, 1e-9)
        assertEquals(9 * 60 + 15 + 29, q.minute)
        assertEquals(30, q.spark.size)
        assertEquals("today's bars were enough", 0, upstox.count(::isHistory))
    }

    @Test fun inSessionAFailedReadIsAnErrorNeverYesterdaysClose() = runBlocking {
        Background.at(WED, 11, 0)
        serve(null, UpstoxStub.minutes(tue, LocalTime.of(15, 0), 30, 24_000.0))
        try { Market.quote("NIFTY", quick = true); fail("the last close was passed off as live") } catch (e: Net.HttpFailure) { assertEquals(500, e.code) }
        assertEquals("quick: two tries, no history fallback", 2, upstox.count(::isIntraday))
        assertEquals(0, upstox.count(::isHistory))
    }

    @Test fun inSessionAnEmptyReadAfterTheFirstMinutesIsNoQuote() = runBlocking {
        Background.at(WED, 11, 0)
        serve(emptyList(), UpstoxStub.minutes(tue, LocalTime.of(15, 0), 30, 24_000.0))
        assertNull(Market.quote("NIFTY"))
        assertEquals(0, upstox.count(::isHistory))
    }

    @Test fun beforeTheFirstCandleTheLastSessionShowsMarkedAsSuch() = runBlocking {
        Background.at(WED, 9, 16)
        serve(emptyList(), UpstoxStub.minutes(tue, LocalTime.of(15, 0), 30, 24_000.0))
        val q = Market.quote("NIFTY", quick = true)!!
        assertTrue(q.lastSession)
        assertEquals(24_000.0 + 29 * 0.5 + 0.25, q.last, 1e-9)
    }

    @Test fun afterTheCloseAFailedReadFallsBackToTheLastSession() = runBlocking {
        Background.at(WED.plusDays(3), 12, 0)   // Saturday
        val fri = WED.plusDays(2)
        serve(null, UpstoxStub.minutes(WED.plusDays(1), LocalTime.of(15, 0), 10, 24_100.0) + UpstoxStub.minutes(fri, LocalTime.of(15, 0), 30, 24_200.0))
        val q = Market.quote("NIFTY")!!
        assertTrue(q.lastSession)
        assertEquals("Friday's session only", 30, q.spark.size)
        assertEquals(24_200.0, q.open, 0.0)
    }

    @Test fun nothingAnywhereIsNoQuote() = runBlocking {
        Background.at(WED, 18, 0)
        serve(emptyList(), emptyList())
        assertNull(Market.quote("BANKNIFTY"))
    }

    @Test fun contractsAreReadFromTodaysListAndTheExpiryCalendarFromIt() {
        Background.at(WED, 10, 0)
        val next = WED.plusDays(6)
        Background.contracts(context, WED, listOf(
            Upstox.Contract("NIFTY", WED, 24_500.0, Right.PE, 75, "NSE_FO|1", "NIFTY25O1524500PE"),
            Upstox.Contract("NIFTY", next, 24_500.0, Right.PE, 75, "NSE_FO|2", "NIFTY25O2124500PE"),
            Upstox.Contract("NIFTY", WED.minusDays(7), 24_500.0, Right.PE, 75, "NSE_FO|3", "OLD"),
            Upstox.Contract("BANKNIFTY", next, 55_000.0, Right.CE, 35, "NSE_FO|4", "BN"),
        ))
        assertEquals(4, Market.contracts().size)
        assertEquals("nothing downloaded: today's list is on the phone", 0, upstox.paths.size)
        assertEquals(listOf(WED, next), Market.upcomingExpiries("NIFTY"))
        assertTrue(Market.isExpiryDay("NIFTY"))
        assertFalse(Market.isExpiryDay("BANKNIFTY"))
        assertTrue(Market.isExpiryDay("BANKNIFTY", next))
    }

    @Test fun anOldContractListIsReplacedByTodaysMaster() {
        Background.at(WED, 10, 0)
        Background.contracts(context, tue, listOf(Upstox.Contract("NIFTY", WED, 24_500.0, Right.PE, 75, "NSE_FO|1", "X")))
        upstox.reply = { status(503) }
        try { Market.contracts(); fail() } catch (e: Net.HttpFailure) { assertEquals(503, e.code) }
        assertEquals("the saved list stays readable", tue, Market.cachedContracts()!!.first)
    }
}
