package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.KiteTicks
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.ira.FastLane
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

/**
 * Local candles (round 2): (ii) a bar-close decision is taken at the first stream tick after the boundary, on the minute
 * built from the stream, while the candle feed has not published that minute yet; (v) the arm is paper-only, and with the
 * app in Live, logged in and real orders on, not one order reaches Zerodha. The VIX divergence arm's 10:30 check (it waits
 * for the 10:29 minute rather than read the one before) stands for every minute arm.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class LocalCandleEntryTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")
    private val bankKey = Upstox.INDEX_KEYS.getValue("BANKNIFTY")
    private val vixKey = Upstox.INDEX_KEYS.getValue("INDIAVIX")
    private val peKey = "NSE_FO|VXDN25100PE"
    private val nifty = 256_265L
    private val vix = 264_969L

    private fun at(t: LocalTime, seconds: Long = 0) {
        Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(seconds).atZone(IST).toInstant(), IST)
    }

    private fun minutes(key: String, until: LocalTime, open: Double, px: (LocalTime) -> Double) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) {
            val c = px(t)
            out += FakeUpstox.Candle(t, if (t == LocalTime.of(9, 15)) open else c, maxOf(c, open), c * 0.99, c)
            t = t.plusMinutes(1)
        }
        upstox.minutes[key] = out
    }

    /** The feed to [until] (exclusive): NIFTY +0.25% and India VIX +2.5% from 10:00, BANKNIFTY flat; the put to 10:29 too. */
    private fun feed(until: LocalTime) {
        minutes(niftyKey, until, 25_000.0) { t -> if (!t.isBefore(LocalTime.of(10, 0))) 25_062.5 else 25_010.0 }
        minutes(bankKey, until, 56_000.0) { 56_000.0 }
        minutes(vixKey, until, 12.0) { t -> if (!t.isBefore(LocalTime.of(10, 0))) 12.30 else 12.0 }
        minutes(peKey, LocalTime.of(10, 30), 120.0) { 120.0 }
    }

    /** NIFTY and India VIX on the stream, stamped [t] (IST, today) by the exchange. */
    private fun stream(t: LocalTime, seconds: Long) {
        val sec = day.atTime(t).plusSeconds(seconds).atZone(IST).toEpochSecond()
        KiteStream.feedForTest(listOf(KiteTicks.Tick(nifty, 25_062.5, exchangeTime = sec), KiteTicks.Tick(vix, 12.30, exchangeTime = sec)))
    }

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        day = AutomationSupport.tradingDayAt(10, 30).toLocalDate()
        at(LocalTime.of(10, 30))
        val expiry = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("NIFTY", expiry, 25_100.0, Right.PE, 65, peKey, "NIFTY-VXD-25100PE"),
            Upstox.Contract("NIFTY", expiry, 25_000.0, Right.CE, 65, "NSE_FO|VXDN25000CE", "NIFTY-VXD-25000CE")))
        kite.requests.clear()
    }

    @After fun down() {
        FastPath.resetForTest()
        KiteStream.stop()
        Market.testClock = null
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 5_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(5) }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun theFeedAloneMakesTheCheckWaitForTheMinute() {
        assertTrue(runBlocking { VixDivArm.setArmed(true) }.contains("paper only"))
        feed(LocalTime.of(10, 29))                                         // the feed is a few seconds behind: no 10:29 yet
        runBlocking { VixDivArm.tick() }
        assertTrue(VixDivArm.view.value.open.isEmpty())
        assertEquals("waiting for the 10:29 minute", VixDivArm.view.value.status["NIFTY"])
    }

    @Test fun aBarCloseDecisionIsTakenAtTheFirstTickAfterTheBoundaryOnTheLocalCandleAndStaysOnPaper() {
        assertTrue(runBlocking { VixDivArm.setArmed(true) }.contains("paper only"))
        feed(LocalTime.of(10, 29))
        // The stream since 10:27:50 (its first minute is partial; 10:28 and 10:29 are whole), the stream's run unbroken.
        for (s in 0L..120L step 10) stream(LocalTime.of(10, 27, 50), s)
        FastPath.start(context)
        stream(LocalTime.of(10, 29, 55), 0)                               // the consumer sees the instruments in 10:29
        Thread.sleep(200)
        assertTrue(VixDivArm.view.value.open.isEmpty())
        // 10:30:00 by the exchange: the first tick after the boundary closes 10:29.
        val t0 = System.currentTimeMillis()
        stream(LocalTime.of(10, 30), 0)
        awaitTrue("the entry") { VixDivArm.view.value.open.isNotEmpty() }
        val ms = System.currentTimeMillis() - t0
        println("LATENCY first tick after the boundary -> decision on the local candle: $ms ms")
        assertTrue("decided at the tick ($ms ms), not when the feed's minute lane runs", ms < FastLane.MINUTE_AFTER_MS)
        val pos = VixDivArm.view.value.open.single()
        assertEquals("NIFTY", pos.index); assertEquals("PE", pos.right)
        assertTrue("held in the paper book", Paper.state.positions.any { it.symbol == pos.symbol && it.quantity == 65 })
        // The feed never had 10:29: the decision's minute came from the stream.
        assertTrue(upstox.minutes.getValue(niftyKey).none { it.at == LocalTime.of(10, 29) })
        // (v) paper only: Live, logged in, real orders on - and not one order, change or cancel at Zerodha.
        assertTrue(AppSettings.load().let { it.live && it.allowRealOrders })
        Thread.sleep(500)
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }
}
