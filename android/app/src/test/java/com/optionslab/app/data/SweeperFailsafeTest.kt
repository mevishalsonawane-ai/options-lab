package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.Alerts
import com.optionslab.engine.IST
import com.optionslab.engine.risk.PriceWatch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * The no-price failsafe ("Positions not protected: no prices since HH:MM", [Sweeper]) against the fake Kite, 15 Oct: Boss
 * was told it before the 09:15 open. A position is judged only while its own market trades (NSE from 09:15 on a trading
 * day, MCX from 09:00 in its own hours); a book not read is no answer either way. Once the market is open, a position
 * really without prices is still said - and the sweep of a stop the price went through runs whatever the hour (open
 * positions come first: only the warning's timing changed).
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class SweeperFailsafeTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val nfo = "BANKNIFTY26OCT52000CE"
    private val gold = "GOLDM26NOVFUT"
    private val title = "Positions not protected"
    /** The first day on or after today that NSE trades and MCX trades from 09:00 (an ordinary day for both). */
    private lateinit var day: LocalDate

    @Before fun up() {
        // IraGoldAlgo posts buys and sells only: the warning is never shown there.
        assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        Market.testClock = null
        var d = Market.today()
        while (!(Market.isTradingDay(d) && McxMarket.calendar().window(d)?.open == LocalTime.of(9, 0))) d = d.plusDays(1)
        day = d
        kite = FakeKite()
        kite.login()
        AutomationSupport.clearAlerts()
        Alerts.forgetPosted()
        Sweeper.resetForTest()
    }

    @After fun down() {
        Market.testClock = null
        Sweeper.resetForTest()
        if (::kite.isInitialized) {
            KiteStream.stop()
            kite.close()
        }
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** One sweep at [h]:[m]:[s] on [day] (the app's clock). */
    private fun pass(h: Int, m: Int, s: Int = 0) {
        Market.testClock = Clock.fixed(day.atTime(h, m, s).atZone(IST).toInstant(), IST)
        runBlocking { Sweeper.run(context) }
    }

    private fun warned(): List<String> = Alerts.posted.filter { it.title == title }.map { it.text }

    /** The warning as Boss sees it, for a silence since [hhmm]. */
    private fun said(hhmm: String) = "Positions not protected: no prices since $hhmm. Check them in Trade."

    @Test fun beforeTheOpenAnOvernightPositionWithNoPriceIsNoAlarmFrom0915ItIs() {
        kite.position(nfo, 30, 200.0)                         // no quote: no price at all
        listOf(9 to 0, 9 to 5, 9 to 10, 9 to 14).forEach { (h, m) -> pass(h, m) }
        assertEquals("nothing said before 09:15", emptyList<String>(), warned())
        pass(9, 15)
        pass(9, 17)
        assertEquals("two minutes exactly: not yet", emptyList<String>(), warned())
        pass(9, 17, 1)
        assertEquals(listOf(said("09:15")), warned())
        pass(9, 18)
        assertEquals("said once for one silence", 1, warned().size)
    }

    @Test fun openWithPricesIsNeverAnAlarm() {
        kite.position(nfo, 30, 200.0)
        kite.quote("NFO:$nfo", 200.0, 199.95, 200.05)
        listOf(9 to 30, 9 to 33, 9 to 36, 9 to 40).forEach { (h, m) -> pass(h, m) }
        assertTrue(warned().isEmpty())
    }

    @Test fun anOrderBookNotReadIsNoFalseAlarmButARealSilenceIsStillSaid() {
        kite.position(nfo, 30, 200.0)
        kite.quote("NFO:$nfo", 200.0, 199.95, 200.05)
        kite.down += "/orders"
        // It used to count an unread order book as "no prices": said after two minutes although every price came.
        pass(10, 0); pass(10, 3); pass(10, 6)
        assertTrue(warned().isEmpty())
        // The prices really stop: said, from the last one.
        kite.quotes.remove("NFO:$nfo")
        pass(10, 7)
        pass(10, 8, 1)
        assertEquals(listOf(said("10:06")), warned())
    }

    @Test fun aPositionBookNotReadOnceIsNoAlarmButOneThatStaysUnreadIsSaid() {
        kite.position(nfo, 30, 200.0)
        kite.quote("NFO:$nfo", 200.0, 199.95, 200.05)
        pass(11, 0)
        kite.down += "/portfolio/positions"
        // Judged on the positions Zerodha last showed, with no price: within two minutes of the last price, nothing said.
        pass(11, 1); pass(11, 2)
        assertTrue(warned().isEmpty())
        // Still unread: the position has had no price for over two minutes - said, from the last price.
        pass(11, 2, 1)
        assertEquals(listOf(said("11:00")), warned())
        // Read again and priced: quiet.
        kite.down.clear()
        pass(11, 4); pass(11, 7)
        assertEquals(1, warned().size)
    }

    @Test fun aBookNeverReadSinceTheStartIsNoAlarm() {
        kite.position(nfo, 30, 200.0)
        kite.down += "/portfolio/positions"
        pass(12, 0); pass(12, 3); pass(12, 6)
        assertTrue("nothing known yet is nothing to judge", warned().isEmpty())
        // The first read: from then on it counts.
        kite.down.clear()
        pass(12, 7)
        pass(12, 9, 1)
        assertEquals(listOf(said("12:07")), warned())
    }

    @Test fun onAnNseHolidayNothingIsSaid() {
        Holidays.add(day)
        try {
            assertFalse(Market.isTradingDay(day))
            kite.position(nfo, 30, 200.0)
            listOf(9 to 20, 9 to 25, 11 to 0, 11 to 5, 14 to 0).forEach { (h, m) -> pass(h, m) }
            assertTrue(warned().isEmpty())
        } finally { Holidays.remove(day) }
    }

    @Test fun anMcxPositionIsJudgedFromMcxsOpenAt0900() {
        kite.position(gold, 1, 100.0, exchange = "MCX")
        pass(8, 55); pass(8, 58); pass(8, 59, 59)
        assertTrue("MCX is not open at 08:55", warned().isEmpty())
        pass(9, 5)
        pass(9, 7, 1)
        assertEquals(listOf(said("09:05")), warned())
    }

    @Test fun anMcxPositionPricedAt0905IsNoAlarm() {
        kite.position(gold, 1, 100.0, exchange = "MCX")
        kite.quote("MCX:$gold", 100.0, 99.0, 101.0)
        // And an NSE position without a price beside it: not judged before 09:15.
        kite.position(nfo, 30, 200.0)
        pass(9, 5); pass(9, 8); pass(9, 12)
        assertTrue(warned().isEmpty())
    }

    /** A live position still held after the 15:05 expiry square-off time: still judged until F&O's close. */
    @Test fun heldAfter1505ItIsStillJudgedUntilTheClose() {
        kite.position(nfo, 30, 200.0)
        pass(15, 6)
        pass(15, 8, 1)
        assertEquals(listOf(said("15:06")), warned())
        // After F&O's 15:40 close: nothing more.
        pass(15, 41); pass(15, 45); pass(16, 0)
        assertEquals(1, warned().size)
    }

    /** OPEN POSITIONS COME FIRST: before the NSE open the warning is quiet, but a stop the price went through is still swept. */
    @Test fun aStopThroughItsPriceIsStillSweptBeforeTheOpen() {
        kite.position(nfo, 30, 200.0)
        kite.quote("NFO:$nfo", 200.0, 199.95, 200.05)
        kite.orders["1"] = FakeKite.Order("1", nfo, "NFO", "SELL", 30, "NRML", "SL-M", 0.0, 210.0, "irapine", "TRIGGER PENDING")
        pass(9, 5)
        assertTrue("the stop was turned into a sell", kite.requests.any { it.method == "PUT" && it.path == "/orders/regular/1" })
        assertEquals("LIMIT", kite.order("1").type)
        assertTrue(warned().isEmpty())
    }

    /** The paper book's positions as [Paper.sweepMissedStops] hands them over: keyed as its prices are, judged on the app's calendars. */
    @Test fun paperPositionsAreJudgedOnTheAppsCalendars() {
        val contracts = mapOf(
            nfo to Paper.Contract(nfo, "BANKNIFTY", day.plusDays(7), 52_000.0, com.optionslab.engine.Right.CE, 30, "NSE_FO|1"),
            gold to Paper.Contract(gold, "GOLDM", day.plusDays(30), 0.0, com.optionslab.engine.Right.IX, 10, "MCX_FO|1", exchange = "MCX"))
        val held = Paper.failsafeHeld(listOf(nfo, gold, "FORGOTTEN"), contracts)
        assertEquals(listOf(
            PriceWatch.Held(com.optionslab.engine.sandbox.Sandbox.key(nfo, "NFO"), "NFO", day.plusDays(7)),
            PriceWatch.Held(com.optionslab.engine.sandbox.Sandbox.key(gold, "MCX"), "MCX", day.plusDays(30)),
            // A contract not remembered: NFO, as the book prices it, with no expiry.
            PriceWatch.Held(com.optionslab.engine.sandbox.Sandbox.key("FORGOTTEN", "NFO"), "NFO", null)), held)
        assertEquals("MCX", Paper.exchangeIn(contracts, gold))
        assertEquals("NFO", Paper.exchangeIn(contracts, "FORGOTTEN"))
        val (nse, mcx) = held
        assertEquals(false to false, Sweeper.judge(listOf(nse), emptySet(), day.atTime(9, 10)))
        assertEquals(true to false, Sweeper.judge(listOf(nse), emptySet(), day.atTime(9, 15)))
        assertEquals(true to true, Sweeper.judge(listOf(nse, mcx), setOf(mcx.key), day.atTime(9, 5)))
        assertEquals(false to false, Sweeper.judge(listOf(mcx), emptySet(), day.atTime(8, 55)))
        // Expired yesterday (settled, never priced again): not judged.
        assertEquals(false to false, Sweeper.judge(listOf(nse.copy(expiry = day.minusDays(1))), emptySet(), day.atTime(11, 0)))
        // On its expiry day: judged to the close.
        assertEquals(true to false, Sweeper.judge(listOf(nse.copy(expiry = day)), emptySet(), day.atTime(15, 30)))
        assertEquals(false to false, Sweeper.judge(listOf(nse.copy(expiry = day)), emptySet(), day.atTime(15, 40)))
        Holidays.add(day)
        try { assertEquals(false to false, Sweeper.judge(listOf(nse), emptySet(), day.atTime(11, 0))) } finally { Holidays.remove(day) }
        // An empty paper book: nothing held, so nothing to say.
        assertTrue(runBlocking { Paper.sweepMissedStops() }.held.isEmpty())
    }
}
