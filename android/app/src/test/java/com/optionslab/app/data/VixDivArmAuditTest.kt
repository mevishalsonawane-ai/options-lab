package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.ManagerShadow
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
 * 22 Oct (arms audit). The fixture is [VixDivArmTest]'s. An unreadable "stop for today" never sells a held VIX position (an
 * entry still treats unknown as stopped); Boss's own stop sells it as "operator_stop"; a position the square-off closed first
 * is booked closed elsewhere and the book is never left short.
 *
 * (Original fixture note:) "VIX divergence (not proven)" (9 Oct, research R1 N13) is PAPER ONLY: with the app in Live, logged in to Zerodha, real
 * orders allowed and "AI trades go live" on, NIFTY up 0.25% while India VIX is up 2.5% at the 10:30 check buys the 1-ITM
 * put on paper, 1 lot, with its -15% stop resting in the paper book; the 20-minute rule sells it; not one order reaches
 * Zerodha. Off by default; first signal of the day only; the kill switch refuses an entry; a late watch lets the day go.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class VixDivArmAuditTest : RobolectricTest() {
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
        VixDivArm.testStoppedWhy = null
        com.optionslab.ira.ArmExits.clear()
        Market.testClock = null
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** Not one order, modify or cancel was sent to Zerodha. */
    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    /** Bought at 10:30 (the fixture's divergence); the open position. */
    private fun bought(): VixDivArm.Pos {
        assertTrue(runBlocking { VixDivArm.setArmed(true) }.contains("paper only"))
        market(LocalTime.of(10, 30))
        tick(LocalTime.of(10, 30))
        return VixDivArm.view.value.open.single()
    }

    private fun held(sym: String) = Paper.state.positions.filter { it.symbol == sym }.sumOf { it.quantity }

    @Test fun anUnreadableStopForTodayNeverSellsAHeldPosition() {
        val pos = bought()
        VixDivArm.testStoppedWhy = { throw IllegalStateException("the strategies' book could not be read") }
        market(LocalTime.of(10, 35))
        tick(LocalTime.of(10, 35))
        assertEquals("still held", 1, VixDivArm.view.value.open.size)
        assertTrue(VixDivArm.view.value.closed.none { it.why == "operator_stop" })
        assertEquals(pos.qty, held(pos.symbol))
        assertNothingAtZerodha()
    }

    @Test fun bossesOwnStopForTodaySellsWhatIsHeld() {
        val pos = bought()
        VixDivArm.testStoppedWhy = { com.optionslab.ira.DayStop.Why.BOSS }
        market(LocalTime.of(10, 35))
        tick(LocalTime.of(10, 35))
        assertTrue(VixDivArm.view.value.open.isEmpty())
        assertEquals("operator_stop", VixDivArm.view.value.closed.single().why)
        assertEquals("flat", 0, held(pos.symbol))
        assertNothingAtZerodha()
    }

    @Test fun aPositionTheSquareOffClosedFirstIsBookedClosedElsewhereNeverShort() {
        val pos = bought()
        assertTrue(runBlocking { Paper.close(pos.symbol, "MIS") }.ok)
        // Boss's stop is on too: the arm would sell now, but the book is already flat.
        VixDivArm.testStoppedWhy = { com.optionslab.ira.DayStop.Why.BOSS }
        val sells = Paper.state.trades.count { it.symbol == pos.symbol && it.action == "SELL" }
        market(LocalTime.of(10, 35))
        tick(LocalTime.of(10, 35))
        assertEquals("closed_elsewhere", VixDivArm.view.value.closed.single().why)
        assertEquals("no second sale", sells, Paper.state.trades.count { it.symbol == pos.symbol && it.action == "SELL" })
        assertEquals("flat, never short", 0, held(pos.symbol))
        assertTrue("its resting stop is out of the book", Paper.state.orders.none { it.symbol == pos.symbol && it.status == "trigger pending" })
        assertNothingAtZerodha()
    }
}
