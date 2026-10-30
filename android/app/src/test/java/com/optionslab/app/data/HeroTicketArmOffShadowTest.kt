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
import com.optionslab.engine.orb.HeroTicketRules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * the Hero ₹1,000 ticket switched OFF still decides its 12:30 tickets on NIFTY's expiry day and hands each to the switched-off
 * shadow ([OffShadow]) with Boss's exits (the 25% trail, a 2x / 3x target, 15:05) - never a paper order, never its live path.
 * Switched ON, the paper path is unchanged.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class HeroTicketArmOffShadowTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")

    private fun ce(k: Int) = "NSE_FO|HT${k}CE"
    private fun pe(k: Int) = "NSE_FO|HT${k}PE"

    /** As [HeroTicketArmTest]: the 25,150 CE (Rs 12) and 24,900 PE (Rs 11) are the 12:30 tickets (lot 65). */
    private val callPx = mapOf(25_050 to 28.0, 25_100 to 16.0, 25_150 to 12.0, 25_200 to 9.0, 25_250 to 6.5, 25_300 to 4.0, 25_350 to 3.0, 25_400 to 2.0)
    private val putPx = mapOf(25_000 to 31.0, 24_950 to 18.0, 24_900 to 11.0, 24_850 to 8.0, 24_800 to 4.0, 24_750 to 3.0, 24_700 to 2.0)

    private fun at(t: LocalTime, seconds: Long = 20) {
        Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(seconds).atZone(IST).toInstant(), IST)
    }

    private fun tick(t: LocalTime, seconds: Long = 20) { at(t, seconds); runBlocking { HeroTicketArm.tick() } }

    private fun minutes(key: String, until: LocalTime, px: (LocalTime) -> Double) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) { val c = px(t); out += FakeUpstox.Candle(t, c, c, c, c); t = t.plusMinutes(1) }
        upstox.minutes[key] = out
    }

    private fun market(until: LocalTime) {
        minutes(niftyKey, until) { 25_010.0 }
        for ((k, p) in callPx) minutes(ce(k), until) { p }
        for ((k, p) in putPx) minutes(pe(k), until) { p }
    }

    /** The shadow's price read: each ticket at its 12:30 price (the ask at the last price, a 0.10 book). */
    private fun prices(call: Double = 12.0, put: Double = 11.0) {
        OffShadow.testPrice = { sym -> if (sym.contains("25150")) Triple(call, call - 0.1, call) else if (sym.contains("24900")) Triple(put, put - 0.1, put) else null }
    }

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
        day = AutomationSupport.tradingDayAt(12, 30).toLocalDate()
        at(LocalTime.of(9, 20))
        val list = ArrayList<Upstox.Contract>()
        for (k in callPx.keys) list += Upstox.Contract("NIFTY", day, k.toDouble(), Right.CE, 65, ce(k), "NIFTY-HT-${k}CE")
        for (k in putPx.keys) list += Upstox.Contract("NIFTY", day, k.toDouble(), Right.PE, 65, pe(k), "NIFTY-HT-${k}PE")
        list += Upstox.Contract("NIFTY", day.plusDays(7), 25_000.0, Right.CE, 65, "NSE_FO|HTNEXTCE", "NIFTY-HT-NEXT")
        AutomationSupport.contracts(context, list)
        Market.saveBfo(listOf(Upstox.Contract("SENSEX", day.plusDays(2), 72_500.0, Right.CE, 20, "BSE_FO|HTS72500CE", "SENSEX-HT-72500CE")))
        HeroTicketArm.testNoAutoOn = false
        OffShadow.init(context)
        OffShadow.wipe()
        OffShadow.testSession = true
        prices()
        kite.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        HeroTicketArm.testNoAutoOn = true
        ArmLive.testHeard = null
        OffShadow.wipe()
        if (Autopilot.enabled()) Autopilot.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun view() = runBlocking { HeroTicketArm.refresh() }

    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    @Test fun offBothTicketsAreWouldBeTradesInTheShadowAndNothingIsBought() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        assertTrue(view().armed)
        runBlocking { HeroTicketArm.setTrailStart(HeroTicketRules.TrailStart.AT_FILL) }
        runBlocking { HeroTicketArm.setArmed(false) }
        assertTrue("off, its 12:25-12:33 window still wants a pass", HeroTicketArm.wantsPass(12 * 60 + 30))
        market(LocalTime.of(12, 30))
        val orders = Paper.state.orders.size
        val trades = Paper.state.trades.size
        tick(LocalTime.of(12, 30))
        assertEquals("no paper order", orders, Paper.state.orders.size)
        assertEquals(trades, Paper.state.trades.size)
        assertTrue(Paper.state.positions.none { it.symbol.contains("25150") || it.symbol.contains("24900") })
        assertTrue(HeroTicketArm.view.value.open.isEmpty())
        assertTrue(heard.toString(), heard.none { it.startsWith("entered|") })
        // The two would-be tickets under its key: 1 lot each, the 25% trail from the fill, no target (trail only), out at 15:05.
        val w = OffShadow.view.value.openOf(HeroTicketRules.SOURCE).sortedBy { it.view }
        assertEquals(w.toString(), 2, w.size)
        assertTrue(w[0].symbol, w[0].symbol.contains("24900")); assertEquals(-1, w[0].view)
        assertTrue(w[1].symbol, w[1].symbol.contains("25150")); assertEquals(1, w[1].view)
        for (p in w) {
            assertEquals(65, p.qty); assertEquals(1, p.side)
            assertEquals(HeroTicketRules.TRAIL, p.exits.trailPct!!, 1e-12)
            assertNull(p.exits.target); assertNull(p.exits.lockAfterRs); assertNull(p.exits.stop)
            assertEquals(HeroTicketRules.EXIT_AT, p.exits.exitAt)
        }
        assertTrue(HeroTicketArm.view.value.status["NIFTY"].orEmpty().contains("switched off - "))
        assertTrue(HeroTicketArm.diagLine(), HeroTicketArm.diagLine().contains(com.optionslab.engine.orb.OffShadow.OFF_WORDS))
        // Decided: a later pass in the window asks nothing more.
        market(LocalTime.of(12, 32)); tick(LocalTime.of(12, 32))
        assertEquals(2, OffShadow.view.value.openOf(HeroTicketRules.SOURCE).size)
        assertEquals(orders, Paper.state.orders.size)
        // The shadow closes them itself on the trail (both fell more than 25% under their best).
        at(LocalTime.of(12, 50))
        prices(call = 8.0, put = 7.0)
        OffShadow.testExtreme = { sym -> if (sym.contains("25150")) 8.0 else 7.0 }
        // (30 Oct review: a look judges its low against the stop as it stood before that look; the next look sees the trail.)
        runBlocking { OffShadow.watch() }
        runBlocking { OffShadow.watch() }
        assertTrue(OffShadow.view.value.openOf(HeroTicketRules.SOURCE).isEmpty())
        assertEquals(2, OffShadow.view.value.closedOf(HeroTicketRules.SOURCE).size)
        assertTrue(HeroTicketArm.view.value.open.isEmpty())
        assertEquals(orders, Paper.state.orders.size)
        assertNothingAtZerodha()
    }

    @Test fun onThePaperPathIsUnchangedAndTheShadowGetsNothing() {
        assertTrue(view().armed)
        market(LocalTime.of(12, 30)); tick(LocalTime.of(12, 30))
        assertEquals(2, HeroTicketArm.view.value.open.size)
        for (p in HeroTicketArm.view.value.open) assertTrue(Paper.state.positions.any { it.symbol == p.symbol && it.quantity == p.qty })
        assertTrue(OffShadow.view.value.openOf(HeroTicketRules.SOURCE).isEmpty())
        assertTrue(HeroTicketArm.diagLine().contains("on (paper only)"))
        assertFalse(HeroTicketArm.diagLine().contains(com.optionslab.engine.orb.OffShadow.OFF_WORDS))
        assertNothingAtZerodha()
    }

    /** The exits' mapping: the trail from the fill as a 25% trail; a trail after 2x / 3x as the nearest lock; Boss's target. */
    @Test fun bossExitsAreMappedToTheShadowsExits() {
        val fill = HeroTicketArm.shadowExits(12.0, 65, HeroTicketRules.Target.TRAIL_ONLY, HeroTicketRules.TrailStart.AT_FILL)
        assertEquals(0.25, fill.trailPct!!, 1e-12); assertNull(fill.lockAfterRs); assertNull(fill.target)
        assertEquals(HeroTicketRules.EXIT_AT, fill.exitAt)
        val x2 = HeroTicketArm.shadowExits(12.0, 65, HeroTicketRules.Target.X3, HeroTicketRules.TrailStart.AFTER_2X)
        assertNull("no stop below 2x", x2.trailPct)
        assertEquals(12.0 * 65, x2.lockAfterRs!!, 1e-9)
        // At 2x the trail sells at 1.5x the fill: half the best net - the lock keeps that share.
        assertEquals(0.5, x2.lockKeep, 1e-12)
        assertEquals(HeroTicketRules.targetLevel(12.0, HeroTicketRules.Target.X3)!!, x2.target!!, 1e-9)
        val x3 = HeroTicketArm.shadowExits(10.0, 65, HeroTicketRules.Target.TRAIL_ONLY, HeroTicketRules.TrailStart.AFTER_3X)
        assertEquals(2 * 10.0 * 65, x3.lockAfterRs!!, 1e-9)
        assertEquals(0.625, x3.lockKeep, 1e-12)
        assertNull(x3.target)
    }

    @Test fun offOutsideItsExpiryDayAsksNothing() {
        AutomationSupport.contracts(context, listOf(Upstox.Contract("NIFTY", day.plusDays(1), 25_150.0, Right.CE, 65, ce(25_150), "NIFTY-HT-X")))
        runBlocking { HeroTicketArm.setArmed(false) }
        market(LocalTime.of(12, 30)); tick(LocalTime.of(12, 30))
        assertTrue(OffShadow.view.value.openOf(HeroTicketRules.SOURCE).isEmpty())
        assertEquals("not its expiry day", HeroTicketArm.view.value.status["NIFTY"])
        assertNothingAtZerodha()
    }
}
