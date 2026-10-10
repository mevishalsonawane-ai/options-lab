package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.ProfitLock
import com.optionslab.ira.Auction
import com.optionslab.ira.OrderFlow
import com.optionslab.ira.TradeManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.ZonedDateTime

/**
 * The trade manager on a Pine script's LIVE holding against the fake Kite ([TradeManagerHost], [PineAuto]): turning LIVE to
 * ACT asks for the PIN; in SHADOW (live's default) it records what it would do and changes nothing - the resting stop and
 * the target stay the script's; in ACT, with more room, the target moves out from 360 and the resting stop at Zerodha moves
 * up to the manager's lock - at breakeven plus charges or better - by a modify of the same order, and a price past the old
 * target is held. Readings come from the test ([TradeManagerHost.testSnapshot]); no order is placed by the manager itself.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class TradeManagerPineTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var kite: FakeKite
    private val ce = "BANKNIFTY26OCT52000CE"
    private val pe = "BANKNIFTY26OCT52000PE"
    private lateinit var now: ZonedDateTime
    private var closes: List<Double> = emptyList()
    private var id = 0L

    private val code = """
        //@version=5
        indicator("Level signals", overlay = true)
        level = input.float(52000, "Level")
        buy = close > level
        sell = close < level
        plotshape(buy, "Buy", shape.labelup, location.belowbar, color.green)
        plotshape(sell, "Sell", shape.labeldown, location.abovebar, color.red)
    """.trimIndent()

    @Before fun up() {
        kite = FakeKite()
        kite.login()
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        AutomationSupport.clearAlerts()
        AutomationSupport.freshPine(context)
        now = AutomationSupport.tradingDayAt(11, 2)
        PineAuto.testNow = now
        PineAuto.testBars = { _, _ -> AutomationSupport.bars(now, closes) }
        val expiry = now.toLocalDate().plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.CE, 30, "NSE_FO|1", ce),
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.PE, 30, "NSE_FO|2", pe)))
        kite.instruments += FakeKite.Ins(33_000_001, ce, "BANKNIFTY", expiry, 52_000.0, "CE", 30)
        kite.instruments += FakeKite.Ins(33_000_002, pe, "BANKNIFTY", expiry, 52_000.0, "PE", 30)
        kite.quote("NFO:$ce", 300.0, 299.95, 300.05)
        kite.quote("NFO:$pe", 280.0, 279.95, 280.05)
        id = PineScripts.put(PineScripts.Item(0, "Level", code)).id
        PineScripts.setAuto(id, PineScripts.Auto(symbol = "BANKNIFTY", interval = "5m", lots = 1, buy = "Buy", sell = "Sell", shortWith = "exit"))
        TradeManagerHost.testNoWake = true
        TradeManagerHost.testBars = { emptyList() }
    }

    @After fun down() {
        TradeManagerHost.resetForTest()
        AutomationSupport.realClocks()
        kite.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun pass(last: Double, minutes: Long = 5) {
        now = now.plusMinutes(minutes); PineAuto.testNow = now
        closes = List(9) { 51_900.0 } + last
        runBlocking { PineAuto.tick() }
    }

    private fun log() = PineAuto.log.value.filter { it.script == id }.joinToString("\n") { it.text }
    private fun held() = PineAuto.held.value[id]
    private fun side(s: String) = kite.placed.filter { it.form["transaction_type"] == s && it.form["order_type"] != "SL" }

    private fun bought() {
        assertEquals("ok", runBlocking { PineAuto.arm(id, true, pinConfirmed = true) })
        pass(51_900.0)
        pass(52_010.0)
        assertEquals(300.0, held()!!.entry, 1e-9)
    }

    /** Strong agreeing executed flow, the future accepted above VWAP and value, long build-up: room to extend, at [premium]. */
    private fun room(premium: Double) {
        TradeManagerHost.testSnapshot = { _, at ->
            val read = OrderFlow.Read(name = "BANKNIFTY", atSec = at / 1000, side = OrderFlow.Side.BUYERS, strength = 75, buyers = 75, warm = true,
                mid = 52_080.0, ofi10 = 0.0, ofi60 = 0.0, ofi300 = 0.0, cvd10 = 0.0, cvd60 = 9_000.0, cvd300 = 0.0, depth = 0.0, queue = 0.0,
                ratio = Double.NaN, ce60 = 0.0, pe60 = 0.0, optionNet = Double.NaN, buildUp = OrderFlow.BuildUp.LONG_BUILDUP, oiChange = 15_000L,
                z = emptyMap())
            TradeManager.Snapshot(at, premium, flow = read, futPrice = 52_080.0, vwap = Auction.Vwap(52_000.0, 40.0, null, null),
                valueHigh = 52_050.0, valueLow = 51_950.0)
        }
    }

    /** The manager looks once a second for [secs] seconds from the test's clock. */
    private fun looks(secs: Int) {
        val t0 = now.toInstant().toEpochMilli()
        for (i in 0..secs) TradeManagerHost.evaluate(t0 + i * 1000L)
    }

    private fun mid(): String = PineAuto.managerId(id, held()!!)

    @Test fun turningLiveToActAsksForThePin() {
        assertEquals(TradeManager.Mode.SHADOW, TradeManagerHost.policy.value.mode("pine", TradeManager.Account.LIVE))
        assertEquals(TradeManager.Mode.ACT, TradeManagerHost.policy.value.mode("pine", TradeManager.Account.PAPER))
        val said = TradeManagerHost.setMode("pine", TradeManager.Account.LIVE, TradeManager.Mode.ACT)
        assertEquals(TradeManagerHost.PIN_NEEDED, said)
        assertTrue("unproven - may cut winners early", "may cut winners early" in said)
        assertEquals("nothing changed without the PIN", TradeManager.Mode.SHADOW, TradeManagerHost.policy.value.mode("pine", TradeManager.Account.LIVE))
        val ok = TradeManagerHost.setMode("pine", TradeManager.Account.LIVE, TradeManager.Mode.ACT, pinConfirmed = true)
        assertTrue(ok, "may cut winners early" in ok)
        assertEquals(TradeManager.Mode.ACT, TradeManagerHost.policy.value.mode("pine", TradeManager.Account.LIVE))
        // Every other strategy is SHADOW by default; one whose exits do not take the manager's word never acts.
        assertEquals(TradeManager.Mode.SHADOW, TradeManagerHost.policy.value.mode("liquidity", TradeManager.Account.PAPER))
        assertTrue("records only" in TradeManagerHost.setMode("liquidity", TradeManager.Account.PAPER, TradeManager.Mode.ACT))
        assertEquals(TradeManager.Mode.SHADOW, TradeManagerHost.policy.value.mode("liquidity", TradeManager.Account.PAPER))
        // A paper-only arm may act, switched on by hand (no PIN on paper); never by default.
        assertEquals(TradeManager.Mode.SHADOW, TradeManagerHost.policy.value.mode("night", TradeManager.Account.PAPER))
        assertFalse(TradeManagerHost.setMode("night", TradeManager.Account.PAPER, TradeManager.Mode.ACT) == TradeManagerHost.PIN_NEEDED)
        assertEquals(TradeManager.Mode.ACT, TradeManagerHost.policy.value.mode("night", TradeManager.Account.PAPER))
    }

    @Test fun liveShadowRecordsTheExtensionAndChangesNothing() {
        bought()
        kite.quote("NFO:$ce", 356.0, 355.95, 356.05)
        pass(52_010.0)                                           // the holding is handed to the manager (SHADOW: live's default)
        val m = mid()
        val stopBefore = held()!!.stopAt
        room(356.0)
        looks(40)
        val r = TradeManagerHost.records.value.single { it.trade.tradeId == m }
        assertEquals(TradeManager.Mode.SHADOW, r.mode)
        val ext = r.notes.single { it.kind == TradeManager.EXTEND_NOTE }
        assertFalse("recorded, not acted", ext.acted)
        assertNull("SHADOW hands nothing to the script", TradeManagerHost.levels(m))
        assertNull(TradeManagerHost.exitDue(m))
        pass(52_010.0)
        assertEquals("the resting stop is the script's own", stopBefore, held()!!.stopAt)
        assertFalse(log(), log().contains("trade manager"))
        // Past the original target (360): sold there, as before.
        kite.quote("NFO:$ce", 361.0, 360.95, 361.05)
        pass(52_010.0)
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("at 361.00: target"))
        assertNull(held())
        val done = TradeManagerHost.records.value.single { it.trade.tradeId == m }
        assertEquals("the original rules are what really happened", done.actual, done.original)
    }

    @Test fun liveActExtendsTheTargetOnlyWithTheRestingStopRaisedToBreakevenOrBetter() {
        TradeManagerHost.setMode("pine", TradeManager.Account.LIVE, TradeManager.Mode.ACT, pinConfirmed = true)
        bought()
        val stop = held()!!.stop!!
        kite.quote("NFO:$ce", 356.0, 355.95, 356.05)
        pass(52_010.0)
        val m = mid()
        room(356.0)
        looks(40)
        val r = TradeManagerHost.records.value.single { it.trade.tradeId == m }
        assertEquals(TradeManager.Mode.ACT, r.mode)
        val ext = r.notes.single { it.kind == TradeManager.EXTEND_NOTE }
        assertTrue(ext.acted)
        val (target, lock) = TradeManagerHost.levels(m)!!
        assertNotNull(target); assertNotNull(lock)
        assertTrue("the target moved out from 360: $target", target!! > 360.0 && target <= 360.0 + 30.0)
        val breakeven = 300.0 + ProfitLock.roundTripPerUnit(300.0, 30)
        assertTrue("the lock is at breakeven plus charges or better: $lock", lock!! >= breakeven)
        assertTrue("under the price", lock < 356.0)
        // The next look moves the resting stop at Zerodha up to the lock: a modify of the same order, never a new sell.
        pass(52_010.0)
        assertTrue(log(), held()!!.stopAt!! >= lock - 1e-9)
        assertEquals(held()!!.stopAt!!, kite.order(stop).trigger, 1e-9)
        assertTrue(kite.order(stop).trigger >= breakeven)
        assertEquals("TRIGGER PENDING", kite.order(stop).status)
        // Money at risk now: none (the stop is above the price paid plus charges).
        assertEquals(0.0, TradeManager.moneyAtRisk(TradeManagerHost.records.value.single { it.trade.tradeId == m }.trade, held()!!.stopAt), 1e-9)
        // Past the old target (360) but under the new one: held.
        kite.quote("NFO:$ce", 365.0, 364.95, 365.05)
        pass(52_010.0)
        assertTrue(log(), side("SELL").isEmpty())
        assertNotNull(held())
        // At the new target: sold, said as extended.
        kite.quote("NFO:$ce", target + 1.0, target + 0.95, target + 1.05)
        pass(52_010.0)
        assertEquals(1, side("SELL").size)
        assertTrue(log(), log().contains("target (extended by the trade manager)"))
        assertEquals("nothing more was bought", 1, side("BUY").size)
        val done = TradeManagerHost.records.value.single { it.trade.tradeId == m }
        assertNotNull(done.actual)
        assertEquals(done.actual, done.manager)
    }
}
