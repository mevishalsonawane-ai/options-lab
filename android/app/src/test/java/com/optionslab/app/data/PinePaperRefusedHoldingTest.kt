package com.optionslab.app.data

import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.app.testing.TradeFixtures
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
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
import java.time.ZonedDateTime

/**
 * 14 Oct (Boss: the signal is read on the index, then the option is bought): a Pine script holding a PAPER option when
 * the load refuses its chart (an option's own) is switched off, but what it holds is managed to its exit - its stop,
 * target and profit lock watched from the first look - and never sold at market by the switch-off.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PinePaperRefusedHoldingTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var now: ZonedDateTime
    private var closes: List<Double> = emptyList()
    private var id = 0L
    private val ceKey = "NSE_FO|PINECE"
    private val peKey = "NSE_FO|PINEPE"

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
        upstox = FakeUpstox()
        TradeFixtures.paperSettings()
        AutomationSupport.clearAlerts()
        AutomationSupport.freshPine(context)
        at(AutomationSupport.tradingDayAt(11, 2))
        PineAuto.testBars = { _, _ -> AutomationSupport.bars(now, closes) }
        val expiry = now.toLocalDate().plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.CE, 30, ceKey, "BANKNIFTY-PINE-52000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 52_000.0, Right.PE, 30, peKey, "BANKNIFTY-PINE-52000PE")))
        upstox.price(ceKey, 300.0)
        upstox.price(peKey, 280.0)
        id = PineScripts.put(PineScripts.Item(0, "Level", code)).id
        PineScripts.setAuto(id, PineScripts.Auto(symbol = "BANKNIFTY", interval = "5m", lots = 1, buy = "Buy", sell = "Sell", shortWith = "exit"))
    }

    @After fun down() {
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
    private fun held() = PineAuto.held.value[id]

    /** The saved script now names an option's chart, read again as after an update and a restart. */
    private fun refuseOnReload(symbol: String) {
        val f = java.io.File(context.filesDir, "pine.vault")
        val saved = org.json.JSONArray(String(com.optionslab.app.security.Vault.readFileSteady(f)!!, Charsets.UTF_8))
        saved.getJSONObject(0).getJSONObject("auto").put("symbol", symbol)
        PineScripts.wipe()
        com.optionslab.app.security.Vault.writeFile(f, saved.toString().toByteArray(Charsets.UTF_8))
        PineScripts::class.java.getDeclaredField("loaded").apply { isAccessible = true }.set(null, false)
        runBlocking { PineAuto.load() }
    }

    @Test fun aPaperHoldingOfARefusedScriptIsWatchedToItsOwnTargetNeverDumped() {
        assertEquals("ok", runBlocking { PineAuto.arm(id, true) })
        pass(51_900.0)
        pass(52_010.0)                                             // the index's signal turns BUY: the ATM call is bought
        val h = held() ?: throw AssertionError("not bought on paper: " + log())
        assertFalse("paper", h.live)
        refuseOnReload(h.symbol)
        assertFalse("refused: off", PineScripts.get(id)!!.auto.on)
        assertNotNull("still held", held())
        assertTrue(log(), log().contains("Its open option is managed to its exit (its stop, target and profit lock); nothing more is bought."))
        runBlocking { PineAuto.watchOnly() }
        assertNotNull("nothing due at its buy price", held())
        pass(51_900.0)                                             // the index's signal turning would have sold it before
        pass(52_010.0)
        assertNotNull(held())
        assertFalse(log(), log().contains("no longer auto-trading"))
        assertFalse(log(), log().contains("signal changed"))
        // Its own target (60 over the buy price) sells it, from the position watch.
        upstox.price(ceKey, h.entry + 61.0)
        runBlocking { PineAuto.watchOnly() }
        assertNull(held())
        assertTrue(log(), log().contains(": target"))
    }

    @Test fun aRefusedScriptWithNoHoldingIsSimplyOff() {
        assertEquals("ok", runBlocking { PineAuto.arm(id, true) })
        refuseOnReload("BANKNIFTY26OCT52000CE")
        assertFalse(PineScripts.get(id)!!.auto.on)
        assertNull(held())
        assertFalse(log(), log().contains("managed to its exit"))
        pass(51_900.0)
        pass(52_010.0)
        assertNull("nothing bought", held())
    }
}
