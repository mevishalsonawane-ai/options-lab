package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.mcx.McxContract
import com.optionslab.engine.sandbox.Quote
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate

/**
 * The hard flat-by (22 Oct, [FlatBySquareOff]): an intraday (MIS) position still open at its segment's minute is closed, paper
 * and live, the kill switch notwithstanding; NRML (held by design) and Boss's own live trades are left alone; nothing before
 * the minute, nothing on a holiday; a restart at 15:24 still closes at 15:25; MCX ten minutes before its close.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class FlatBySquareOffTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private lateinit var upstox: com.optionslab.app.testing.FakeUpstox
    /** A Thursday in US summer time (MCX closes 23:30); the market clock fixed at noon on it, so the paper book's own 15:15 square-off never runs. */
    private val today: LocalDate = LocalDate.of(2026, 10, 22)
    private val ce = "BANKNIFTY26OCT52000CE"
    private val stock = "RELIANCE26OCT2800CE"
    private lateinit var paperCe: String
    private lateinit var paperStock: String
    private lateinit var expiry: LocalDate

    @Before fun up() {
        // The fake feed resets the market clock: fixed again at noon on [today] (the flat-by's own clock is [at]).
        upstox = com.optionslab.app.testing.FakeUpstox()
        Market.testClock = java.time.Clock.fixed(today.atTime(12, 0).atZone(com.optionslab.engine.IST).toInstant(), com.optionslab.engine.IST)
        Paper.testSkipFeedChecks = true
        kite = FakeKite()
        kite.login()
        expiry = today.plusDays(7)
        kite.instruments += FakeKite.Ins(31_000_001, ce, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.instruments += FakeKite.Ins(31_000_002, stock, "RELIANCE", expiry, 2_800.0, "CE", 250)
        kite.quote("NFO:$ce", 200.0, 199.95, 200.05)
        kite.quote("NFO:$stock", 50.0, 49.95, 50.05)
        runBlocking { Broker.instruments() }
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.CE, 30, "NSE_FO|FLATCE", "BANKNIFTY-FLAT-52000CE"),
            Upstox.Contract("RELIANCE", expiry, 2_800.0, Right.CE, 250, "NSE_FO|FLATSTK", "RELIANCE-FLAT-2800CE")))
        upstox.price("NSE_FO|FLATCE", 200.0); upstox.price("NSE_FO|FLATSTK", 50.0)
        paperCe = Paper.contractFor("BANKNIFTY", expiry, 52_000.0, Right.CE)!!.symbol
        paperStock = Paper.contractFor("RELIANCE", expiry, 2_800.0, Right.CE)!!.symbol
        FlatBySquareOff.resetForTest()
        FlatBySquareOff.liveOwner = { null }
        FlatBySquareOff.liveBooksReady = { true }
        kite.requests.clear()
        com.optionslab.app.testing.Background.clearAlerts()
    }

    @After fun down() {
        FlatBySquareOff.liveOwner = { sym -> ArmLive.ownerOf(sym) }
        FlatBySquareOff.liveBooksReady = { ArmLive.view.value.let { it.loaded && !it.unreadable } }
        AutomationSupport.realClocks()
        Paper.testSkipFeedChecks = false
        Market.testClock = null
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(h: Int, m: Int, day: LocalDate = today) { FlatBySquareOff.testNow = day.atTime(h, m).atZone(java.time.ZoneId.of("Asia/Kolkata")) }
    private fun pass() = runBlocking { FlatBySquareOff.maybeRun(context, AppSettings.load()) }
    private fun sent() = kite.placed.map { Triple(it.form["tradingsymbol"], it.form["transaction_type"], it.form["quantity"]) }
    private fun paperOpen(): List<Pair<String, String>> = runBlocking { Paper.snapshot().positions.positions.filter { it.quantity != 0 }.map { it.symbol to it.product } }
    private fun paper(symbol: String, product: String, price: Double) = runBlocking {
        val c = Paper.contractOf(symbol) ?: Paper.contractFor(if (symbol == paperCe) "BANKNIFTY" else "RELIANCE", expiry, if (symbol == paperCe) 52_000.0 else 2_800.0, Right.CE)!!
        val r = Paper.place(c, "BUY", 1, "MARKET", product, null, null, Quote(price))
        assertTrue(r.message, r.ok)
    }

    @Test fun aPaperMisIndexPositionIsClosedAt1525NotBefore() {
        paper(paperCe, "MIS", 200.0)
        at(15, 24); pass()
        assertEquals(listOf(paperCe to "MIS"), paperOpen())
        at(15, 25); pass()
        assertTrue(paperOpen().isEmpty())
        assertTrue("nothing live was sent", kite.placed.isEmpty())
        assertTrue(FlatBySquareOff.diagLine().contains("done today"))
    }

    @Test fun nrmlIsHeldByDesignAndLeftAlone() {
        paper(paperCe, "NRML", 200.0)
        at(15, 30); pass()
        assertEquals(listOf(paperCe to "NRML"), paperOpen())
    }

    @Test fun theStockSegmentGoesAt1520AndTheIndexOnlyAt1525() {
        paper(paperCe, "MIS", 200.0)
        paper(paperStock, "MIS", 50.0)
        at(15, 21); pass()
        assertEquals(listOf(paperCe to "MIS"), paperOpen())
        at(15, 26); pass()
        assertTrue(paperOpen().isEmpty())
    }

    @Test fun theKillSwitchNeverStopsIt() {
        SecurePrefs.put("g.kill", true)
        paper(paperCe, "MIS", 200.0)
        FlatBySquareOff.liveOwner = { if (it == ce) "ORB" else null }
        kite.position(ce, 30, 200.0, product = "MIS")
        at(15, 27); pass()
        assertTrue(paperOpen().isEmpty())
        assertEquals(listOf(Triple(ce, "SELL", "30")), sent())
        val o = kite.placed.single().form
        assertEquals("MARKET", o["order_type"]); assertEquals("MIS", o["product"]); assertEquals("iraalgoflat", o["tag"])
    }

    @Test fun aRestartAt1524StillClosesAt1525() {
        paper(paperCe, "MIS", 200.0)
        // The process died at 15:24 and came back: nothing in memory (no done marks), the position restored first.
        FlatBySquareOff.resetForTest()
        at(15, 24); pass()
        assertEquals(listOf(paperCe to "MIS"), paperOpen())
        at(15, 25); pass()
        assertTrue(paperOpen().isEmpty())
    }

    @Test fun nothingOnAHolidayOrWeekendOrAfterTheClose() {
        paper(paperCe, "MIS", 200.0)
        FlatBySquareOff.liveOwner = { "ORB" }
        kite.position(ce, 30, 200.0, product = "MIS")
        // A Saturday, and a weekday holiday.
        var d = today; while (d.dayOfWeek != java.time.DayOfWeek.SATURDAY) d = d.plusDays(1)
        at(15, 30, d); pass()
        var h = today.plusDays(1); while (h.dayOfWeek.value > 5) h = h.plusDays(1)
        Holidays.add(h)
        try { at(15, 30, h); pass() } finally { Holidays.remove(h) }
        // After F&O's close nothing can be sent.
        val close = Market.foClose(today)
        at(close / 60, close % 60); pass()
        assertEquals(listOf(paperCe to "MIS"), paperOpen())
        assertTrue(kite.placed.isEmpty())
    }

    @Test fun liveOnlyAnArmsMisPositionIsClosedNeverBossOwnNorNrml() {
        FlatBySquareOff.liveOwner = { if (it == ce) "R51 Donchian 20" else null }
        kite.position(ce, 30, 200.0, product = "MIS")
        kite.position(stock, 250, 50.0, product = "MIS")          // Boss's own (no arm owns it)
        kite.position("BANKNIFTY26OCT52000PE", 30, 100.0, product = "NRML")
        at(15, 26); pass()
        assertEquals(listOf(Triple(ce, "SELL", "30")), sent())
        // The next pass: the exit is working, nothing is sent twice.
        at(15, 27); pass()
        assertEquals(1, kite.placed.size)
    }

    @Test fun theArmsLiveBookNotReadYetMeansNothingLiveIsClosedAndTheDayIsNotDone() {
        // A restart at 15:24: the first pass at 15:25 runs before the arms' live book is read. An arm's position cannot be told
        // from Boss's own, so nothing live goes and the segment is not marked done; the next pass, with the book in, closes it.
        FlatBySquareOff.liveBooksReady = { false }
        FlatBySquareOff.liveOwner = { "ORB" }
        kite.position(ce, 30, 200.0, product = "MIS")
        at(15, 25); pass()
        assertTrue(kite.placed.isEmpty())
        assertFalse(FlatBySquareOff.diagLine().contains("done today"))
        FlatBySquareOff.liveBooksReady = { true }
        at(15, 26); pass()
        assertEquals(listOf(Triple(ce, "SELL", "30")), sent())
    }

    @Test fun aShortIsBoughtBackAndAWorkingExitIsNeverDoubled() {
        FlatBySquareOff.liveOwner = { "arm" }
        kite.position(ce, -30, 200.0, product = "MIS")
        kite.nextPlace(outcome = FakeKite.Outcome.OPEN)
        at(15, 26); pass()
        assertEquals(listOf(Triple(ce, "BUY", "30")), sent())
        at(15, 28); pass()
        assertEquals("still working: not sent again", 1, kite.placed.size)
    }

    @Test fun mcxGoesTenMinutesBeforeItsCloseMisOnly() {
        val day = today                                   // a Thursday in US summer time: MCX closes 23:30
        val fut = McxContract(148_600_400L, 580_480L, "CRUDEOIL26NOVFUT", "CRUDEOIL", LocalDate.of(2026, 11, 19), 0.0, null, 1.0, 100)
        val c = McxMarket.paperContract(fut)
        upstox.price(c.feedKey, 5_000.0)
        runBlocking {
            assertTrue(Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, Quote(5_000.0)).ok)
            assertTrue(Paper.place(c, "BUY", 1, "MARKET", "NRML", null, null, Quote(5_000.0)).ok)
        }
        at(23, 19, day); pass()
        assertEquals(2, paperOpen().size)
        at(23, 21, day); pass()
        assertEquals(listOf(c.symbol to "NRML"), paperOpen())
    }

    @Test fun theDueSegmentsFollowTheClockAndTheCalendar() {
        assertTrue(FlatBySquareOff.dueNow(today, 15 * 60 + 20).let { com.optionslab.engine.risk.FlatBy.Segment.NSE_STOCK in it && com.optionslab.engine.risk.FlatBy.Segment.NSE_INDEX !in it })
        assertTrue(FlatBySquareOff.dueNow(today, 15 * 60 + 25).containsAll(listOf(com.optionslab.engine.risk.FlatBy.Segment.NSE_STOCK, com.optionslab.engine.risk.FlatBy.Segment.NSE_INDEX)))
        assertFalse(com.optionslab.engine.risk.FlatBy.Segment.MCX in FlatBySquareOff.dueNow(today, 15 * 60 + 25))
        assertTrue(FlatBySquareOff.dueNow(today, 15 * 60 + 19).isEmpty())
    }
}
