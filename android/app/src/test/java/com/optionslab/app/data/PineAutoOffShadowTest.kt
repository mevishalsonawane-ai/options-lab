package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.ProfitLock
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
import java.time.Clock
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record only, so i can continuously observe it"):
 * a Pine script saved for auto-trading and switched OFF keeps reading its signal on the index's completed candles; the entry it
 * would have made goes to the switched-off shadow ([OffShadow], key "pine:<id>") with the script's own stop, target, lock and 15:15
 * exit - never a paper order or a held option. Switched ON, the paper path is unchanged and nothing goes to the shadow. A script
 * only ever saved (never switched on) is not an arm and is never shadowed.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PineAutoOffShadowTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var now: ZonedDateTime
    private var closes: List<Double> = emptyList()
    private var id = 0L
    private val ceKey = "NSE_FO|PINEOFFCE"
    private val peKey = "NSE_FO|PINEOFFPE"

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
        upstox = FakeUpstox()                               // (clears Market.testClock: set it after)
        TradeFixtures.paperSettings()
        AutomationSupport.clearAlerts()
        AutomationSupport.freshPine(context)
        at(AutomationSupport.tradingDayAt(11, 2))
        PineAuto.testBars = { _, _ -> AutomationSupport.bars(now, closes) }
        val expiry = now.toLocalDate().plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.CE, 30, ceKey, "BANKNIFTY-PINEOFF-52000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.PE, 30, peKey, "BANKNIFTY-PINEOFF-52000PE")))
        upstox.price(ceKey, 300.0)
        upstox.price(peKey, 280.0)
        OffShadow.init(context)
        OffShadow.wipe()
        OffShadow.testSession = true
        OffShadow.testPrice = { _ -> Triple(300.0, 299.9, 300.1) }
        id = PineScripts.put(PineScripts.Item(0, "Level", code)).id
        PineScripts.setAuto(id, PineScripts.Auto(symbol = "BANKNIFTY", interval = "5m", lots = 1, buy = "Buy", sell = "Sell", shortWith = "exit"))
    }

    @After fun down() {
        OffShadow.wipe()
        AutomationSupport.realClocks()
        Market.testClock = null
        upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun at(z: ZonedDateTime) {
        now = z
        PineAuto.testNow = z
        Market.testClock = Clock.fixed(z.toInstant(), IST)
    }

    private fun pass(last: Double) {
        at(now.plusMinutes(5))
        closes = List(9) { 51_900.0 } + last
        runBlocking { PineAuto.tick() }
    }

    private fun log() = PineAuto.log.value.filter { it.script == id }.joinToString("\n") { it.text }
    private fun key() = PineAuto.shadowKey(id)
    private fun shadowOpen() = OffShadow.view.value.openOf(key())
    private fun shadowClosed() = OffShadow.view.value.closedOf(key())

    @Test fun aScriptSwitchedOffRecordsItsWouldBeEntryInTheShadowAndPlacesNothing() {
        assertEquals("ok", runBlocking { PineAuto.arm(id, true) })
        pass(51_900.0)                                             // watching: SELL (flat, shortWith exit)
        assertEquals("ok", runBlocking { PineAuto.arm(id, false) })
        assertFalse(PineScripts.get(id)!!.auto.on)
        val orders = Paper.state.orders.size
        pass(52_010.0)                                             // the index's signal turns BUY: off, so the shadow only
        assertNull("nothing held", PineAuto.held.value[id])
        assertEquals("no paper order", orders, Paper.state.orders.size)
        assertTrue("no paper position", Paper.state.positions.none { it.symbol.startsWith("BANKNIFTY-PINEOFF") && it.quantity != 0 })
        val w = shadowOpen().single()
        assertEquals(key(), w.key)
        assertEquals("Pine · Level", w.label)
        assertTrue(w.symbol, w.symbol.startsWith("BANKNIFTY") && w.symbol.endsWith("52000CE"))
        assertEquals("a call: bullish on the index", 1, w.view)
        assertEquals(30, w.qty)
        // Its own exits: the stop 30 under and the target 60 over the price read, the lock's first rung, 15:15.
        assertEquals(270.0, w.exits.stop!!, 1e-9)
        assertEquals(360.0, w.exits.target!!, 1e-9)
        assertEquals(LocalTime.of(15, 15), w.exits.exitAt)
        assertNotNull(w.exits.lockAfterRs)
        assertTrue(log(), log().contains("Shadow (switched off): would buy ${w.symbol} 30"))
        assertTrue(log(), log().contains("Shadow (switched off): Signal: BUY"))
        // The same signal again records nothing more.
        pass(52_020.0)
        assertEquals(1, shadowOpen().size)
        assertEquals(orders, Paper.state.orders.size)
        // The signal turns: the would-be trade closes in the shadow by the script's signal exit (still nothing placed).
        pass(51_900.0)
        assertTrue(shadowOpen().isEmpty())
        assertEquals("signal changed", shadowClosed().single().why)
        assertEquals(orders, Paper.state.orders.size)
        assertNull(PineAuto.held.value[id])
    }

    @Test fun switchedOnThePaperPathIsUnchangedAndNothingGoesToTheShadow() {
        assertEquals("ok", runBlocking { PineAuto.arm(id, true) })
        pass(51_900.0)
        val orders = Paper.state.orders.size
        pass(52_010.0)
        val h = PineAuto.held.value[id] ?: throw AssertionError("not bought on paper: " + log())
        assertFalse(h.live)
        assertTrue(h.symbol, h.symbol.startsWith("BANKNIFTY") && h.symbol.endsWith("52000CE"))
        assertTrue("a paper buy (and its resting stop)", Paper.state.orders.size > orders)
        assertTrue(log(), log().contains("Bought 30 ${h.symbol}"))
        assertTrue(shadowOpen().isEmpty()); assertTrue(shadowClosed().isEmpty())
        assertFalse(log(), log().contains("Shadow (switched off)"))
    }

    @Test fun aScriptNeverSwitchedOnIsNotAnArmAndIsNeverShadowed() {
        val orders = Paper.state.orders.size
        pass(51_900.0)
        pass(52_010.0)
        assertTrue(shadowOpen().isEmpty())
        assertEquals("", log())
        assertEquals(orders, Paper.state.orders.size)
    }

    @Test fun anAlertsOnlyScriptSwitchedOffIsNotShadowed() {
        PineScripts.setAuto(id, PineScripts.get(id)!!.auto.copy(mode = "alert"))
        assertEquals("ok", runBlocking { PineAuto.arm(id, true) })
        pass(51_900.0)
        assertEquals("ok", runBlocking { PineAuto.arm(id, false) })
        pass(52_010.0)
        assertTrue(shadowOpen().isEmpty())
    }

    @Test fun theShadowsExitsAreTheScriptsOwn() {
        val a = PineScripts.Auto(stopPts = 30.0, targetPts = 60.0, profitLock = true,
            trail = ProfitLock.Trail(5.0, listOf(ProfitLock.Trail.Step(8.0, 65.0))))
        // At 300: the trail's first step (+8% = 24 points, keeps 65%) comes before the ladder's half-target rung (30 points).
        val x = PineAuto.shadowExits(a, 300.0, 30)
        assertEquals(270.0, x.stop!!, 1e-9)
        assertEquals(360.0, x.target!!, 1e-9)
        assertEquals(24.0 * 30, x.lockAfterRs!!, 1e-6)
        assertEquals(0.65, x.lockKeep, 1e-9)
        assertEquals(LocalTime.of(15, 15), x.exitAt)
        // At 1,000: the ladder (half of the 60-point target, keeps half) comes first.
        val y = PineAuto.shadowExits(a, 1_000.0, 30)
        assertEquals(30.0 * 30, y.lockAfterRs!!, 1e-6)
        assertEquals(0.5, y.lockKeep, 1e-9)
        // Its own 15:15 sell off: no time exit of its own (the shadow's NSE square-off still applies).
        assertNull(PineAuto.shadowExits(a.copy(squareOff = false), 300.0, 30).exitAt)
        // A stop at or under one tick has no level; no target, no target exit.
        assertNull(PineAuto.shadowExits(a.copy(targetPts = 0.0), 20.0, 30).target)
        assertNull(PineAuto.shadowExits(a, 20.0, 30).stop)
    }
}
