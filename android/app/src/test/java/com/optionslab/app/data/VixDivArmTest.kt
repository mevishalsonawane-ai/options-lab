package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.VixDivRules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * "VIX divergence (not proven)" (9 Oct, research R1 N13) is PAPER ONLY: with the app in Live, logged in to Zerodha, real
 * orders allowed and "AI trades go live" on, NIFTY up 0.25% while India VIX is up 2.5% at the 10:30 check buys the 1-ITM
 * put on paper, 1 lot, with its -15% stop resting in the paper book; the 20-minute rule sells it; not one order reaches
 * Zerodha. Off by default; first signal of the day only; the kill switch refuses an entry; a late watch lets the day go.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class VixDivArmTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")
    private val bankKey = Upstox.INDEX_KEYS.getValue("BANKNIFTY")
    private val vixKey = Upstox.INDEX_KEYS.getValue("INDIAVIX")
    private val peKey = "NSE_FO|VXDN25100PE"                 // NIFTY 25,100 PE: 1-ITM when the 10:29 close is 25,062.5 (ATM 25,050)

    private fun at(t: LocalTime, seconds: Long = 20) {
        Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(seconds).atZone(IST).toInstant(), IST)
    }

    private fun tick(t: LocalTime) { at(t); runBlocking { VixDivArm.tick() } }

    /** 1-minute candles from 09:15 to [until] (exclusive), each closing at [px]; the 09:15 one opens at [open]. */
    private fun minutes(key: String, until: LocalTime, open: Double, px: (LocalTime) -> Double, low: (LocalTime) -> Double = { px(it) }) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) {
            val c = px(t)
            out += FakeUpstox.Candle(t, if (t == LocalTime.of(9, 15)) open else c, maxOf(c, open), low(t), c)
            t = t.plusMinutes(1)
        }
        upstox.minutes[key] = out
    }

    /** The day to [until]: NIFTY +0.25% and VIX +2.5% from 10:00 ([diverge]), BANKNIFTY flat; the put at [put] (low [putLow]). */
    private fun market(until: LocalTime, diverge: Boolean = true, put: (LocalTime) -> Double = { 120.0 }, putLow: (LocalTime) -> Double = { put(it) * 0.99 }) {
        minutes(niftyKey, until, 25_000.0, { t -> if (diverge && !t.isBefore(LocalTime.of(10, 0))) 25_062.5 else 25_010.0 })
        minutes(bankKey, until, 56_000.0, { 56_000.0 })
        minutes(vixKey, until, 12.0, { t -> if (diverge && !t.isBefore(LocalTime.of(10, 0))) 12.30 else 12.0 })
        minutes(peKey, until, 120.0, put, putLow)
    }

    @Before fun up() {
        upstox = FakeUpstox()                                // (clears Market.testClock: set it after)
        kite = FakeKite()
        // Everything that could send an order to Zerodha is on: Live, real orders allowed, logged in, AI trades live.
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        day = AutomationSupport.tradingDayAt(10, 30).toLocalDate()
        at(LocalTime.of(10, 30))
        val expiry = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("NIFTY", expiry, 25_100.0, Right.PE, 65, peKey, "NIFTY-VXD-25100PE"),
            Upstox.Contract("NIFTY", expiry, 25_000.0, Right.CE, 65, "NSE_FO|VXDN25000CE", "NIFTY-VXD-25000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 56_100.0, Right.PE, 30, "NSE_FO|VXDB56100PE", "BANKNIFTY-VXD-56100PE")))
        kite.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** Not one order, modify or cancel was sent to Zerodha. */
    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    @Test fun itIsOffByDefaultAndDoesNothing() {
        market(LocalTime.of(10, 30))
        val v = runBlocking { VixDivArm.refresh() }
        assertFalse(v.armed)
        val orders = Paper.state.orders.size
        tick(LocalTime.of(10, 30))
        assertEquals(orders, Paper.state.orders.size)
        assertTrue(VixDivArm.diagLine().contains("off"))
        assertNothingAtZerodha()
    }

    @Test fun theDivergenceBuysThePutOnPaperOnlyEvenWithEverythingLiveAndTheTwentyMinuteRuleSellsIt() {
        assertTrue(runBlocking { VixDivArm.setArmed(true) }.contains("paper only"))
        // Before 10:30: only waiting.
        market(LocalTime.of(10, 0))
        tick(LocalTime.of(10, 0))
        assertTrue(VixDivArm.view.value.open.isEmpty())
        assertEquals("waiting for the 10:30 check", VixDivArm.view.value.status["NIFTY"])
        // 10:30: the 10:29 minute closed NIFTY +0.25% from its open and VIX +2.5%: the 1-ITM put (25,100 PE), 1 lot, on paper.
        market(LocalTime.of(10, 30))
        tick(LocalTime.of(10, 30))
        val pos = VixDivArm.view.value.open.single()
        assertEquals("NIFTY", pos.index)
        assertEquals("PE", pos.right)
        assertTrue(pos.symbol, pos.symbol.contains("25100") || pos.symbol.contains("VXD-25100"))
        assertEquals(65, pos.qty)
        assertTrue("paid the spread", pos.entry > 120.0)
        assertTrue("held in the paper book", Paper.state.positions.any { it.symbol == pos.symbol && it.quantity == 65 })
        // Its -15% stop rests in the paper book (fills on a minute's low).
        val stop = Paper.state.orders.single { it.orderId == pos.stopOrderId }
        assertEquals("SL-M", stop.priceType)
        assertEquals(VixDivRules.stop(pos.entry), stop.triggerPrice!!.toDouble(), 1e-9)
        // The decision says the check, the moves and the bid/ask at the signal (the arm log and the diagnostics).
        val said = VixDivArm.view.value.status["NIFTY"].orEmpty()
        assertTrue(said, said.startsWith("bought") && said.contains("10:30 check: NIFTY +0.25% from its open, India VIX +2.50%") && said.contains("bid/ask"))
        assertTrue(VixDivArm.view.value.log.any { "bid/ask" in it })
        // BANKNIFTY flat: no signal yet, still watching.
        assertTrue(VixDivArm.view.value.status["BANKNIFTY"].orEmpty().startsWith("no signal yet"))
        assertNothingAtZerodha()
        // First signal of the day only: a later pass buys nothing more.
        market(LocalTime.of(10, 32))
        tick(LocalTime.of(10, 32))
        assertEquals(1, VixDivArm.view.value.open.size)
        // The 20th minute (10:49) closes under +5% of the fill: sold at the 10:50 pass, on paper.
        market(LocalTime.of(10, 50))
        tick(LocalTime.of(10, 50))
        assertTrue(VixDivArm.view.value.open.isEmpty())
        val done = VixDivArm.view.value.closed.single()
        assertEquals("not_up_5_in_20", done.why)
        assertEquals(0, Paper.state.positions.filter { it.symbol == pos.symbol }.sumOf { it.quantity })
        assertEquals(1, VixDivArm.view.value.nets.size)
        // Restart: the record is read back from its encrypted file.
        AutomationSupport.reloadFromDisk(VixDivArm)
        assertEquals(done, runBlocking { VixDivArm.refresh() }.closed.single())
        assertNothingAtZerodha()
    }

    @Test fun theKillSwitchRefusesTheSignalAndTheDayIsDone() {
        runBlocking { VixDivArm.setArmed(true) }
        TradeFixtures.killSwitch(true)
        market(LocalTime.of(10, 30))
        val orders = Paper.state.orders.size
        tick(LocalTime.of(10, 30))
        assertEquals(orders, Paper.state.orders.size)
        val s = VixDivArm.view.value.status["NIFTY"].orEmpty()
        assertTrue(s, s.startsWith("vix_kill_switch") && s.contains("bid/ask"))
        // Decided for the day: switched off again, a later pass still buys nothing (first signal only).
        TradeFixtures.killSwitch(false)
        market(LocalTime.of(10, 31))
        tick(LocalTime.of(10, 31))
        assertEquals(orders, Paper.state.orders.size)
        assertNothingAtZerodha()
    }

    @Test fun aWatchTooLateForTheCheckLetsTheDayGo() {
        runBlocking { VixDivArm.setArmed(true) }
        market(LocalTime.of(10, 40))
        tick(LocalTime.of(10, 40))
        assertTrue(VixDivArm.view.value.open.isEmpty())
        assertTrue(VixDivArm.view.value.status["NIFTY"].orEmpty().startsWith("vix_late"))
        assertNothingAtZerodha()
    }

    @Test fun noDivergenceAllDayDecidesNothingTradedAfterTheLastCheck() {
        runBlocking { VixDivArm.setArmed(true) }
        market(LocalTime.of(13, 33), diverge = false)
        tick(LocalTime.of(13, 33))
        assertTrue(VixDivArm.view.value.open.isEmpty())
        assertTrue(VixDivArm.view.value.status["NIFTY"].orEmpty().startsWith("vix_no_signal"))
        assertTrue(VixDivArm.view.value.status["BANKNIFTY"].orEmpty().startsWith("vix_no_signal"))
        assertNothingAtZerodha()
    }
}
