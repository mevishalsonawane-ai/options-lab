package com.optionslab.app.data

import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.work.WatchService
import com.optionslab.engine.Right
import com.optionslab.engine.mcx.McxContract
import com.optionslab.engine.mcx.McxEveRules
import com.optionslab.engine.sandbox.Quote
import com.optionslab.ira.McxRest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.LocalDate
import java.time.LocalTime

/**
 * Battery, 14 Oct (Boss: "my phone's battery drains in 3-4 hours"): with an MCX position open - paper, and separately a
 * live one - the evening watch keeps its 15 s pass and the price stream; with none, outside the arms' entry minutes, it
 * runs once a minute, Zerodha's positions are not read each pass, the stream may stop, the brain and the order flow leave
 * MCX alone; the recorders and the market context rest outside market hours.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class BatteryRestTest : RobolectricTest() {
    private fun at(day: String, time: String) {
        Market.testClock = java.time.Clock.fixed(LocalDate.parse(day).atTime(LocalTime.parse(time)).atZone(com.optionslab.engine.IST).toInstant(),
            com.optionslab.engine.IST)
    }

    @After fun down() {
        Market.testClock = null
        KiteStream.resetForTest()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    // A Thursday: NSE shut after 15:40, MCX trading to 23:30. 22:30 is outside every MCX arm's entry minutes.
    private val day = "2026-10-08"
    private val option = McxContract(148600327L, 580470L, "CRUDEOIL26OCT8550CE", "CRUDEOIL", LocalDate.of(2026, 10, 15), 8550.0, Right.CE, 0.1, 100)

    private fun armEvening() = runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, true) }

    /** The books read (as the watch's pass has done by then), so the memory-only checks know them. */
    private fun booksRead() { Paper.state; McxMarket.liveExposure(); McxPaperArms.armedNow() }

    @Test fun anOpenMcxPaperPositionKeepsThe15SecondPassAndTheStreamAllEvening() = runBlocking {
        at(day, "22:30")
        armEvening()
        val r = Paper.place(McxMarket.paperContract(option), "BUY", 1, "MARKET", "NRML", null, null, Quote(242.0))
        assertTrue(r.message, r.ok)
        booksRead()
        assertTrue(McxMarket.watchDue())
        assertTrue(McxMarket.heldNow())
        assertTrue("the MCX price reads keep the stream", McxMarket.heldQuick())
        assertEquals(McxRest.HELD_MS, McxMarket.watchCadenceMs())
        // Late in the session too, and a working order alone counts as held.
        at(day, "23:25")
        assertEquals(McxRest.HELD_MS, McxMarket.watchCadenceMs())
        // The brain's refresh keeps counting MCX while it is held.
        assertNotNull(SmartWorkers.refreshDelay(System.currentTimeMillis()))
    }

    @Test fun aLiveMcxPositionKeepsThe15SecondPassZerodhasPositionsAndTheStream() {
        at(day, "22:30")
        booksRead()
        McxMarket.markLive()
        assertTrue(McxMarket.liveExposure())
        assertTrue(McxMarket.heldQuick())
        assertEquals(McxRest.HELD_MS, McxMarket.watchCadenceMs())
        assertTrue("Zerodha's positions every pass", McxRest.bookDue(true, McxMarket.liveExposure(), 15_000L))
        assertTrue("the watch runs for it", McxMarket.watchDue())
        // Zerodha shows it closed: back to the minute's pace.
        McxMarket.noteLive(emptyList())
        assertFalse(McxMarket.liveExposure())
        assertFalse(McxMarket.heldQuick())
        assertEquals(McxRest.IDLE_MS, McxMarket.watchCadenceMs())
        assertFalse(McxRest.bookDue(true, McxMarket.liveExposure(), 60_000L))
    }

    @Test fun nothingHeldIsOnceAMinuteOutsideTheArmsMinutesAnd15SecondsInside() {
        at(day, "22:30")
        armEvening()
        booksRead()
        assertTrue("an armed arm still keeps the watch up", McxMarket.watchDue())
        assertFalse(McxMarket.heldNow())
        assertFalse(McxMarket.heldQuick())
        assertEquals(McxRest.IDLE_MS, McxMarket.watchCadenceMs())
        assertNull("the brain sleeps: nothing held on MCX", SmartWorkers.refreshDelay(System.currentTimeMillis()))
        // Inside the evening break's minutes the 15 s pass is back, as before.
        at(day, "20:00")
        assertEquals(McxRest.HELD_MS, McxMarket.watchCadenceMs())
        // No arm at all and nothing held: no MCX watch.
        runBlocking { McxPaperArms.setArmed(McxEveRules.SOURCE, false) }
        assertFalse(McxMarket.watchDue())
    }

    @Test fun beforeTheBooksAreReadTheQuickCheckKeepsTheStream() {
        at(day, "22:30")
        // Nothing read in this process yet: unknown counts as held (the stream kept, as before).
        assertTrue(McxMarket.heldQuick())
    }

    @Test fun mcxPriceReadsKeepTheStreamOnlyWhileHeld() = runBlocking {
        val kite = FakeKite()
        try {
            kite.keepAlive = true
            kite.login()
            at(day, "22:30")
            booksRead()
            kite.quote("NSE:NIFTY 50", 25_000.0)
            Broker.quotes(listOf("NSE:NIFTY 50"), keepStream = McxRest.keepsStream(McxMarket.heldQuick()))
            assertFalse("nothing held: the read does not keep the stream awake", 256265L in KiteStream.touchedForTest())
            McxMarket.markLive()
            Broker.quotes(listOf("NSE:NIFTY 50"), keepStream = McxRest.keepsStream(McxMarket.heldQuick()))
            assertTrue("held: it does, as before", 256265L in KiteStream.touchedForTest())
        } finally {
            kite.close()
        }
    }

    @Test fun theOrderFlowFollowsMcxFuturesOnlyWhileHeldOrInsideAnArmsMinutes() = runBlocking {
        armEvening()
        at(day, "22:30")
        booksRead()
        assertEquals(emptyList<String>(), OrderFlowLive.mcxNames())
        at(day, "20:00")
        assertEquals(listOf(McxEveRules.UNDERLYING), OrderFlowLive.mcxNames())
        at(day, "22:30")
        val r = Paper.place(McxMarket.paperContract(option), "BUY", 1, "MARKET", "NRML", null, null, Quote(242.0))
        assertTrue(r.message, r.ok)
        assertEquals("held: all evening, as before", listOf(McxEveRules.UNDERLYING), OrderFlowLive.mcxNames())
        // MCX shut: none.
        at(day, "23:45")
        assertEquals(emptyList<String>(), OrderFlowLive.mcxNames())
    }

    @Test fun theMcxWaitIs60SecondsIdleAndCutShortTheMomentSomethingIsHeld() = runTest {
        val clock = { testScheduler.currentTime }
        // Nothing held: one minute.
        var t0 = clock()
        assertEquals(60_000L, WatchService.mcxWait(cadence = { 60_000L }, due = { true }, now = clock))
        assertEquals(60_000L, clock() - t0)
        assertEquals(60, WatchService.stepSec)
        // Held: 15 s, as before.
        t0 = clock()
        WatchService.mcxWait(cadence = { 15_000L }, due = { true }, now = clock)
        assertEquals(15_000L, clock() - t0)
        assertEquals(15, WatchService.stepSec)
        // A position opens 20 s into the minute's wait: the next full pass comes at 30 s, not 60 s.
        t0 = clock()
        val start = t0
        WatchService.mcxWait(cadence = { if (clock() - start >= 20_000L) 15_000L else 60_000L }, due = { true }, now = clock)
        assertEquals(30_000L, clock() - t0)
        // MCX's watch no longer due (the close): the wait ends at the first look.
        t0 = clock()
        WatchService.mcxWait(cadence = { 60_000L }, due = { false }, now = clock)
        assertEquals(15_000L, clock() - t0)
        WatchService.stepSec = 0
    }

    @Test fun theDiagnosticsCarryTheBatteryTodayLine() = runBlocking {
        com.optionslab.app.work.BatteryMeter.meter.count(com.optionslab.ira.LoopMeter.MCX_SLOW)
        val r = Diag.report()
        val line = r.lines().firstOrNull { it.startsWith("Battery today: ") }
        assertNotNull(r, line)
        assertTrue(line!!, line.contains("watch after 15:40") && line.contains("tick recorder") && line.contains("stream on"))
    }

    @Test fun theRecordersAndTheContextRestOutsideMarketHours() {
        // Inside the session: no rest.
        at("2026-10-14", "10:00")
        assertEquals(0L, Market.untilOpenMs())
        assertEquals(0L, TickZoneRecorder.untilOpenMs())
        assertEquals(60_000L, MarketContext.nextRefreshMs(Market.now().toLocalDateTime()))
        // The evening: asleep (one wait, at most three hours), the context's next refresh at 18:00.
        at("2026-10-14", "16:00")
        assertTrue(TickZoneRecorder.untilOpenMs() > 0)
        assertEquals(2 * 3_600_000L, MarketContext.nextRefreshMs(Market.now().toLocalDateTime()))
        // A Saturday, and a weekday holiday: no session, asleep.
        at("2026-10-17", "11:00")
        assertTrue(Market.untilOpenMs() > 0)
        Holidays.add(LocalDate.of(2026, 10, 15))
        at("2026-10-15", "11:00")
        assertTrue("a holiday", Market.untilOpenMs() > 0)
        assertTrue(MarketContext.nextRefreshMs(Market.now().toLocalDateTime()) > 60_000L)
        Holidays.remove(LocalDate.of(2026, 10, 15))
        // A minute before the open the loops are awake already.
        at("2026-10-14", "09:14")
        assertEquals(0L, TickZoneRecorder.untilOpenMs())
    }
}
