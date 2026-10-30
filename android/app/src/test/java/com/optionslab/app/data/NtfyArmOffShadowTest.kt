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
import com.optionslab.engine.orb.LiveMirror
import com.optionslab.engine.signals.NtfyRules
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
 * ntfy alerts switched OFF runs in shadow (30 Oct 2026, Boss: "If i switch any arm off, it should be always run in shadow and record
 * only, so i can continuously observe it"): a buy message read while off is decided by the pass with every guard, and the buy it
 * would have made becomes a would-be trade in the switched-off shadow ([OffShadow]) with the message's stop, target and the
 * square-off - NO paper order, NO live path, nothing at Zerodha. An exit message closes the would-be trade. The kill switch still
 * refuses it. On, the paper path is unchanged and nothing goes to the shadow.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class NtfyArmOffShadowTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val ce = "NSE_FO|SP25000CE"
    private val pe = "NSE_FO|SP25000PE"
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")

    private fun at(t: LocalTime, s: Long = 0) { Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(s).atZone(IST).toInstant(), IST) }

    /** The options' minutes to [until] at 121; NIFTY flat at 25,010 (ATM 25,000). */
    private fun market(until: LocalTime) {
        val o = ArrayList<FakeUpstox.Candle>(); val i = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) { o += FakeUpstox.Candle(t, 121.0, 121.0, 121.0, 121.0); i += FakeUpstox.Candle(t, 25_010.0, 25_010.0, 25_010.0, 25_010.0); t = t.plusMinutes(1) }
        upstox.minutes[ce] = o; upstox.minutes[pe] = o; upstox.minutes[niftyKey] = i
    }

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        // Everything that could send an order to Zerodha is on: Live, real orders allowed, logged in, AI trades live.
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        day = AutomationSupport.tradingDayAt(9, 20).toLocalDate()
        at(LocalTime.of(9, 20))
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("NIFTY", day.plusDays(6), 25_000.0, Right.CE, 65, ce, "NIFTY-SP-25000CE"),
            Upstox.Contract("NIFTY", day.plusDays(6), 25_000.0, Right.PE, 65, pe, "NIFTY-SP-25000PE")))
        // The switched-off shadow's book, its session and its price read (the option's ask 121.10).
        OffShadow.init(context); OffShadow.wipe()
        OffShadow.testSession = true
        OffShadow.testPrice = { Triple(121.0, 120.9, 121.1) }
        kite.requests.clear()
    }

    @After fun down() {
        OffShadow.wipe()
        Market.testClock = null
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    private fun msg(id: String, text: String, t: LocalTime = LocalTime.of(9, 28), s: Long = 4): Int {
        at(t, s + 1); market(t.plusMinutes(1))
        return runBlocking { NtfyArm.receive(listOf(NtfyRules.Event(id, day.atTime(t).plusSeconds(s).atZone(IST).toEpochSecond(), "message", text, null)),
            SecurePrefs.getString(NtfyReader.K_PREFIX).orEmpty()) }
    }
    private fun tick(t: LocalTime, s: Long = 0) { at(t, s); market(t); runBlocking { NtfyArm.tick() } }
    private fun v() = NtfyArm.view.value
    private fun alert(id: String) = v().alerts.single { it.id == id }
    private fun shadowOpen() = OffShadow.view.value.openOf(LiveMirror.NTFY_ALERTS)

    @Test fun offABuyMessageIsAWouldBeTradeWithItsStopAndTargetAndNothingIsOrdered() {
        assertFalse("off by default", v().on)
        msg("m1", "NIFTY 25000 CE BUY SL 100 TGT 140")
        assertEquals("off: it waits for the pass (decided in shadow)", NtfyArm.PENDING, alert("m1").state)
        assertTrue(NtfyArm.wantsPass())
        tick(LocalTime.of(9, 28), 30)
        // No paper order, no paper position, nothing held by the arm.
        assertTrue(v().open.isEmpty())
        assertTrue(Paper.state.orders.toString(), Paper.state.orders.isEmpty())
        assertTrue(Paper.state.positions.none { it.quantity != 0 })
        assertEquals(NtfyArm.NOT_TRADED, alert("m1").state)
        assertTrue(alert("m1").why, alert("m1").why.startsWith(NtfyArm.OFF_WHY))
        assertFalse(NtfyArm.wantsPass())
        // The would-be trade, with the message's own exits.
        val w = shadowOpen().single()
        assertEquals(LiveMirror.NTFY_ALERTS, w.key)
        assertTrue(w.symbol, w.symbol.endsWith("25000CE")); assertEquals(1, w.view); assertEquals(1, w.side); assertEquals(65, w.qty)
        assertEquals("bought at the ask", 121.1, w.entry, 1e-9)
        assertEquals(100.0, w.exits.stop!!, 1e-9); assertEquals(140.0, w.exits.target!!, 1e-9)
        assertEquals("the square-off (not its expiry day)", NtfyRules.SQ, w.exits.exitAt)
        assertTrue(w.ref, w.ref.endsWith("|${LiveMirror.NTFY_ALERTS}|m1"))
        // A second buy on NIFTY while the would-be trade is open: refused as the real arm would.
        msg("m2", "BUY NIFTY 25000 PE", LocalTime.of(9, 29))
        tick(LocalTime.of(9, 29), 30)
        assertTrue(alert("m2").why, alert("m2").why.contains("already holds a would-be NIFTY trade"))
        assertEquals(1, shadowOpen().size)
        // An exit message closes the would-be trade (nothing sold anywhere).
        msg("x1", "exit nifty", LocalTime.of(9, 31))
        tick(LocalTime.of(9, 31), 30)
        assertEquals(NtfyArm.CLOSED, alert("x1").state)
        assertTrue(shadowOpen().isEmpty())
        assertEquals("exit message", OffShadow.view.value.closedOf(LiveMirror.NTFY_ALERTS).single().why)
        assertTrue(Paper.state.orders.isEmpty())
        // Its line reads as running in shadow.
        assertTrue(NtfyArm.diagLine(), NtfyArm.diagLine().contains(com.optionslab.engine.orb.OffShadow.OFF_WORDS))
        // Never mirrored live: nothing at Zerodha although Live, real orders and AI trades are on.
        assertNothingAtZerodha()
    }

    @Test fun onThePaperPathIsUnchangedAndNothingGoesToTheShadow() {
        runBlocking { NtfyArm.setOn(true, log = false) }
        msg("m1", "NIFTY 25000 CE BUY SL 100 TGT 140")
        tick(LocalTime.of(9, 28), 30)
        val p = v().open.single()
        assertEquals(100.0, p.stop, 1e-9); assertEquals(140.0, p.target)
        assertEquals(NtfyArm.TRADED, alert("m1").state)
        assertTrue(Paper.state.orders.any { it.action == "BUY" && it.symbol == p.symbol })
        assertTrue("on: no would-be trade", shadowOpen().isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun offTheKillSwitchStillRefusesTheWouldBeTrade() {
        AppSettings.save(AppSettings.load().copy(guardKill = true))
        msg("m1", "BUY NIFTY 25000 CE")
        tick(LocalTime.of(9, 28), 30)
        assertEquals(NtfyArm.NOT_TRADED, alert("m1").state)
        assertTrue(alert("m1").why, alert("m1").why.contains("kill switch"))
        assertTrue(shadowOpen().isEmpty())
        assertTrue(Paper.state.orders.isEmpty())
        assertNothingAtZerodha()
    }
}
