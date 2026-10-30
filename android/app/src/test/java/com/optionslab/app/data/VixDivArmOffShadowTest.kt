package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.FakeKite
import com.optionslab.app.testing.FakeUpstox
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.ArmsCleanup
import com.optionslab.engine.orb.VixDivRules
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
import java.time.LocalDate
import java.time.LocalTime

/**
 * 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record only, so i can continuously observe it"):
 * VIX divergence switched OFF (its default) still takes the 10:30 check and hands the 1-ITM put to the switched-off shadow
 * ([OffShadow]) with its own exits (NIFTY's -15% premium stop, the 20-minute rule, 15:10) - never a paper order, never its live
 * path. Hidden by the cleanup it is off, so it runs in shadow too. Switched ON, the paper path is unchanged.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class VixDivArmOffShadowTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")
    private val bankKey = Upstox.INDEX_KEYS.getValue("BANKNIFTY")
    private val vixKey = Upstox.INDEX_KEYS.getValue("INDIAVIX")
    private val peKey = "NSE_FO|VXDN25100PE"                 // NIFTY 25,100 PE: 1-ITM when the 10:29 close is 25,062.5

    private fun at(t: LocalTime, seconds: Long = 20) {
        Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(seconds).atZone(IST).toInstant(), IST)
    }

    private fun tick(t: LocalTime) { at(t); runBlocking { VixDivArm.tick() } }

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

    /** As [VixDivArmTest]: NIFTY +0.25% and VIX +2.5% from 10:00, BANKNIFTY flat; the put at Rs 120 all day. */
    private fun market(until: LocalTime) {
        minutes(niftyKey, until, 25_000.0) { t -> if (!t.isBefore(LocalTime.of(10, 0))) 25_062.5 else 25_010.0 }
        minutes(bankKey, until, 56_000.0) { 56_000.0 }
        minutes(vixKey, until, 12.0) { t -> if (!t.isBefore(LocalTime.of(10, 0))) 12.30 else 12.0 }
        minutes(peKey, until, 120.0) { 120.0 }
    }

    @Before fun up() {
        upstox = FakeUpstox()                                // (clears Market.testClock: set it after)
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
            Upstox.Contract("NIFTY", expiry, 25_000.0, Right.CE, 65, "NSE_FO|VXDN25000CE", "NIFTY-VXD-25000CE"),
            Upstox.Contract("BANKNIFTY", expiry, 56_100.0, Right.PE, 30, "NSE_FO|VXDB56100PE", "BANKNIFTY-VXD-56100PE")))
        HiddenArms.resetForTest(context)
        OffShadow.init(context)
        OffShadow.wipe()
        OffShadow.testSession = true
        OffShadow.testPrice = { Triple(120.0, 119.9, 120.1) }
        kite.requests.clear()
    }

    @After fun down() {
        com.optionslab.ira.ArmExits.clear()
        ArmLive.testHeard = null
        OffShadow.wipe()
        HiddenArms.resetForTest(context)
        Market.testClock = null
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    @Test fun offTheDivergenceIsAWouldBeTradeInTheShadowAndNothingIsBought() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        assertFalse("off by default", runBlocking { VixDivArm.refresh() }.armed)
        // Off, its check minutes still want a pass; between the checks, with nothing held, they do not.
        assertTrue(VixDivArm.wantsPass(10 * 60 + 30))
        assertFalse(VixDivArm.wantsPass(10 * 60 + 40))
        market(LocalTime.of(10, 30))
        val orders = Paper.state.orders.size
        val trades = Paper.state.trades.size
        tick(LocalTime.of(10, 30))
        assertEquals("no paper order", orders, Paper.state.orders.size)
        assertEquals(trades, Paper.state.trades.size)
        assertTrue(Paper.state.positions.none { it.symbol.contains("25100") })
        assertTrue(VixDivArm.view.value.open.isEmpty())
        assertTrue(heard.toString(), heard.none { it.startsWith("entered|") })
        // The would-be put under its key: 1 lot, bearish, NIFTY's -15% premium stop, out by 15:10.
        val w = OffShadow.view.value.openOf(VixDivRules.SOURCE).single()
        assertTrue(w.symbol, w.symbol.contains("25100"))
        assertEquals(-1, w.view); assertEquals(1, w.side); assertEquals(65, w.qty)
        val stop = w.exits.stop
        assertNotNull(stop)
        assertTrue("stop under the price: $stop", stop!! < w.entry && stop > w.entry * 0.8)
        assertEquals(LocalTime.of(15, 10), w.exits.exitAt)
        assertFalse(w.exits.carry)
        val said = VixDivArm.view.value.status["NIFTY"].orEmpty()
        assertTrue(said, said.startsWith("switched off - ") && said.contains("10:30 check"))
        assertTrue(VixDivArm.diagLine(), VixDivArm.diagLine().contains(com.optionslab.engine.orb.OffShadow.OFF_WORDS))
        // Its open would-be trade wants the passes (its 20-minute rule).
        assertTrue(VixDivArm.wantsPass(10 * 60 + 40))
        // Decided for the day: a later pass asks nothing more.
        market(LocalTime.of(10, 32)); tick(LocalTime.of(10, 32))
        assertEquals(1, OffShadow.view.value.openOf(VixDivRules.SOURCE).size)
        // The 20th minute (10:49) closed under +5% of the price: the 20-minute rule closes the would-be trade at 10:50.
        market(LocalTime.of(10, 50)); tick(LocalTime.of(10, 50))
        assertTrue(OffShadow.view.value.openOf(VixDivRules.SOURCE).isEmpty())
        assertEquals("not_up_5_in_20", OffShadow.view.value.closedOf(VixDivRules.SOURCE).single().why)
        assertEquals(orders, Paper.state.orders.size)
        assertTrue(VixDivArm.view.value.closed.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun hiddenItIsOffAndStillRunsInShadow() {
        HiddenArms.hideForTest(ArmsCleanup.VIX_DIV)
        market(LocalTime.of(10, 30))
        val orders = Paper.state.orders.size
        tick(LocalTime.of(10, 30))
        assertEquals(orders, Paper.state.orders.size)
        assertEquals(1, OffShadow.view.value.openOf(VixDivRules.SOURCE).size)
        // Still never switched on while hidden.
        assertFalse(runBlocking { VixDivArm.setArmed(true) }.contains("paper only"))
        assertNothingAtZerodha()
    }

    @Test fun onThePaperPathIsUnchangedAndTheShadowGetsNothing() {
        assertTrue(runBlocking { VixDivArm.setArmed(true) }.contains("paper only"))
        market(LocalTime.of(10, 30))
        tick(LocalTime.of(10, 30))
        val pos = VixDivArm.view.value.open.single()
        assertEquals(65, pos.qty)
        assertTrue(Paper.state.positions.any { it.symbol == pos.symbol && it.quantity == 65 })
        assertTrue(VixDivArm.view.value.status["NIFTY"].orEmpty().startsWith("bought"))
        assertTrue(OffShadow.view.value.openOf(VixDivRules.SOURCE).isEmpty())
        assertNull(OffShadow.view.value.closedOf(VixDivRules.SOURCE).firstOrNull())
        assertNothingAtZerodha()
    }
}
