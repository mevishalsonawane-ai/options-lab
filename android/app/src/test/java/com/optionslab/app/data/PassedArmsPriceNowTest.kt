package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.DonchianArm
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.io.File

/**
 * 15 Nov 2026 (Boss: "Yes fix it"; [com.optionslab.ira.PriceNow]): the passed arms judge a held option's stop only on a price it
 * traded at since the entry, or on a sane book's bid. The 5, 11 and 13 Nov FINNIFTY paper trades were sold seconds after their entry
 * on a last price from an earlier day and on the stray bid of an empty book; here R51's NIFTY trade at 200 (its -15% stop: 170)
 * meets both, stays open, and is still stopped by a real trade or a sane book under its stop.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PassedArmsPriceNowTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")
    private val ce = "NSE_FO|FX25050CE"
    private val pe = "NSE_FO|FX25150PE"
    private val sym = "NIFTY26OCT25050CE"

    private fun at(t: LocalTime, seconds: Long = 20) {
        Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(seconds).atZone(IST).toInstant(), IST)
    }

    private fun tick(t: LocalTime, seconds: Long = 20) { at(t, seconds); runBlocking { PassedArmsHost.tick() } }
    private fun manage(t: LocalTime) { at(t); runBlocking { PassedArmsHost.manageOnly() } }

    private fun minutes(key: String, until: LocalTime, px: (LocalTime) -> Double) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) { val c = px(t); out += FakeUpstox.Candle(t, c, c + 1, c - 1, c); t = t.plusMinutes(1) }
        upstox.minutes[key] = out
    }

    private fun yesterday(): List<Bar> {
        val d = day.minusDays(1)
        return (0 until 375).map { Bar(d.atTime(9, 15).plusMinutes(it.toLong()), 25_000.0, 25_001.0, 24_999.0, 25_000.0) }
    }

    /** NIFTY: 25,000 until 09:18, then 25,100 (R51's 09:20 break up); the 25,050 CE at [call] by minute. */
    private fun market(until: LocalTime, call: (LocalTime) -> Double) {
        minutes(niftyKey, until) { t -> if (t.isBefore(LocalTime.of(9, 19))) 25_000.0 else 25_100.0 }
        minutes(ce, until, call)
        minutes(pe, until) { 90.0 }
    }

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
        day = AutomationSupport.tradingDayAt(9, 20).toLocalDate()
        at(LocalTime.of(9, 0))
        val exp = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("NIFTY", exp, 25_050.0, Right.CE, 65, ce, "NIFTY-FX-25050CE"),
            Upstox.Contract("NIFTY", exp, 25_150.0, Right.PE, 65, pe, "NIFTY-FX-25150PE"),
            Upstox.Contract("NIFTY", exp.plusDays(7), 25_000.0, Right.CE, 65, "NSE_FO|FXNEXT", "NIFTY-FX-NEXT")))
        PassedArmsHost.testHistory = { u -> if (u == "NIFTY") yesterday() else emptyList() }
        PassedArmsHost.testDaily = { emptyList() }
        runBlocking { PassedArmsHost.prefetchNow(day) }
        OrphanWatch.wipe()
        kite.requests.clear()
        for (s in PassedArmsHost.SOURCES) runBlocking { PassedArmsHost.setArmed(s, s == DonchianArm.SOURCE, log = false) }
    }

    @After fun down() {
        com.optionslab.ira.ArmExits.clear()
        StepTrailExits.resetForTest(on = false)
        Market.testClock = null
        PassedArmsHost.testNoAutoOn = true
        if (Autopilot.enabled()) Autopilot.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun open() = PassedArmsHost.view.value.open.single { it.shadow == null }
    private fun closed() = PassedArmsHost.view.value.closed.single { it.shadow == null }

    /** R51's 09:20 break bought at about [px] with the switch [on]. */
    private fun bought(px: Double, on: Boolean = true): PassedArmsHost.Pos {
        StepTrailExits.resetForTest(on = on)
        market(LocalTime.of(9, 20)) { px }
        tick(LocalTime.of(9, 20))
        return open()
    }
    private val token = 26_000_001L

    /** Zerodha lists the option (so the stream's ticks reach the paper price layer); nothing goes live. */
    private fun listed() {
        kite.instruments += FakeKite.Ins(token, sym, "NIFTY", day.plusDays(7), 25_050.0, "CE", 65)
        kite.quote("NFO:$sym", 200.0, 199.95, 200.05)
        File(context.filesDir, "kite_instruments.json").delete()
        runBlocking { Broker.instruments() }
        kite.requests.clear()
    }

    private fun sec(t: LocalTime): Long = day.atTime(t).atZone(IST).toEpochSecond()

    private fun fed(last: Double, bid: Double, ask: Double, tradedAt: Long?) =
        KiteStream.feedForTest(listOf(com.optionslab.engine.KiteTicks.Tick(token, last, bid = bid, ask = ask,
            exchangeTime = System.currentTimeMillis() / 1000, bidQty = 65, askQty = 65, lastTradeTime = tradedAt)))

    @Test fun aLastPriceFromAnEarlierDayNeverStopsTheTradeAndARealTradeDoes() {
        listed()
        val p = bought(200.0, on = false)
        assertTrue("its own -15% stop", PassedArmsHost.exitsOf(p, null).stop!! < 171.0)
        // The stream's packet: a real book around 200, but its last price 68.70 is a trade of yesterday.
        fed(68.70, 199.95, 200.05, sec(LocalTime.of(9, 20)) - 86_400)
        market(LocalTime.of(9, 22)) { 200.0 }
        manage(LocalTime.of(9, 22))
        assertEquals("still held: 68.70 never traded since the entry", p.symbol, open().symbol)
        // A trade under the stop after the entry: stopped.
        fed(160.0, 159.95, 160.05, sec(LocalTime.of(9, 22)) + 5)
        manage(LocalTime.of(9, 22))
        assertEquals("stop", closed().why)
        assertTrue("paper only", kite.placed.isEmpty())
    }

    @Test fun aStrayBidInAnEmptyBookNeverStopsTheTradeAndASaneBookUnderTheStopDoes() {
        listed()
        val p = bought(200.0, on = false)
        // No trade after 09:20 (no newer minute), no last price, and a book of a stray bid at 68.70 under an ask of 200.05.
        market(LocalTime.of(9, 21)) { 200.0 }
        fed(0.0, 68.70, 200.05, null)
        manage(LocalTime.of(9, 25))
        assertEquals("still held: a wide book's bid is no price", p.symbol, open().symbol)
        assertTrue("said once in the arm's notes", PassedArmsHost.view.value.log.count { it.contains("no trade since its entry") } == 1)
        fed(0.0, 68.70, 200.05, null)
        manage(LocalTime.of(9, 25))
        assertTrue(PassedArmsHost.view.value.log.count { it.contains("no trade since its entry") } == 1)
        // A sane book under the stop (still no trade): what a seller gets is under 170 - stopped.
        fed(0.0, 165.0, 165.05, null)
        manage(LocalTime.of(9, 25))
        assertEquals("stop", closed().why)
    }

    @Test fun aPacketWithNoTradeTimeIsTakenAsBeforeUnlessItsLastPriceIsFarFromTheBook() {
        listed()
        val p = bought(200.0, on = false)
        market(LocalTime.of(9, 22)) { 200.0 }
        // No trade time, and the last price far outside a sane book: the book is the price now (199.95), not 68.70.
        fed(68.70, 199.95, 200.05, null)
        manage(LocalTime.of(9, 22))
        assertEquals("still held", p.symbol, open().symbol)
        // No trade time, the last price at the book: taken as before.
        fed(160.0, 159.95, 160.05, null)
        manage(LocalTime.of(9, 22))
        assertEquals("stop", closed().why)
    }
}
