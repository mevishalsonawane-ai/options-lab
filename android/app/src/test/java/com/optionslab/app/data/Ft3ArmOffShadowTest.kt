package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
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
import com.optionslab.engine.orb.Ft3Rules
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
 * FT3 switched OFF keeps judging its marks on its expiry days and hands the would-be ticket to the switched-off shadow
 * ([OffShadow]) - the same LIMIT and lots, held to 15:05 with no stop - and never places a paper order, never tells its live path,
 * never writes the paper forward test's signal record. Switched ON, the paper path is unchanged.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class Ft3ArmOffShadowTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")

    private fun ce(k: Int) = "NSE_FO|F3${k}CE"
    private fun pe(k: Int) = "NSE_FO|F3${k}PE"

    /** As [Ft3ArmTest]: the 25,100 call is Rs 8 from the 11:20 bar (the rule's pick there), everything else out of the band. */
    private val callPx = mapOf(25_000 to 50.0, 25_050 to 30.0, 25_100 to 12.0, 25_150 to 3.0)
    private val putPx = mapOf(25_000 to 40.0, 24_950 to 20.0, 24_900 to 3.0)
    private val signalBar = LocalTime.of(11, 20)

    private fun at(t: LocalTime, seconds: Long = 20) {
        Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(seconds).atZone(IST).toInstant(), IST)
    }

    private fun tick(t: LocalTime, seconds: Long = 20) { at(t, seconds); runBlocking { Ft3Arm.tick() } }

    private fun minutes(key: String, until: LocalTime, px: (LocalTime) -> Double) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) { val c = px(t); out += FakeUpstox.Candle(t, c, c, c, c); t = t.plusMinutes(1) }
        upstox.minutes[key] = out
    }

    private fun market(until: LocalTime) {
        minutes(niftyKey, until) { 25_010.0 }
        for ((k, p) in callPx) minutes(ce(k), until) { t -> if (k != 25_100 || t.isBefore(signalBar)) p else 8.0 }
        for ((k, p) in putPx) minutes(pe(k), until) { p }
    }

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
        day = AutomationSupport.tradingDayAt(11, 0).toLocalDate()
        at(LocalTime.of(9, 20))
        val list = ArrayList<Upstox.Contract>()
        for (k in callPx.keys) list += Upstox.Contract("NIFTY", day, k.toDouble(), Right.CE, 65, ce(k), "NIFTY-F3-${k}CE")
        for (k in putPx.keys) list += Upstox.Contract("NIFTY", day, k.toDouble(), Right.PE, 65, pe(k), "NIFTY-F3-${k}PE")
        list += Upstox.Contract("NIFTY", day.plusDays(7), 25_000.0, Right.CE, 65, "NSE_FO|F3NEXTCE", "NIFTY-F3-NEXT")
        for (u in listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"))
            list += Upstox.Contract(u, day.plusDays(3), 50_000.0, Right.CE, 30, "NSE_FO|F3${u}CE", "$u-F3-CE")
        AutomationSupport.contracts(context, list)
        Market.saveBfo(listOf(Upstox.Contract("SENSEX", day.plusDays(2), 82_000.0, Right.CE, 20, "BSE_FO|F3S82000CE", "SENSEX-F3-82000CE")))
        Ft3Arm.testNoAutoOn = false
        // The switched-off shadow: a fresh book, its price read from the test (the ask at the rule's Rs 8), the market open.
        OffShadow.init(context)
        OffShadow.wipe()
        OffShadow.testSession = true
        OffShadow.testPrice = { _ -> Triple(8.0, 7.9, 8.0) }
        kite.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        Ft3Arm.testNoAutoOn = true
        ArmLive.testHeard = null
        OffShadow.wipe()
        if (Autopilot.enabled()) Autopilot.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun view() = runBlocking { Ft3Arm.refresh() }

    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    @Test fun offTheDueTicketIsAWouldBeTradeInTheShadowAndNothingIsBought() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        assertTrue(view().armed)
        runBlocking { Ft3Arm.setArmed(false) }
        assertTrue("off, its marks' window still wants a pass", Ft3Arm.wantsPass(11 * 60 + 21))
        market(LocalTime.of(11, 21))
        val orders = Paper.state.orders.size
        val trades = Paper.state.trades.size
        tick(LocalTime.of(11, 21))
        // No paper order, no paper position, no ticket in its book, nothing told to its live path.
        assertEquals(orders, Paper.state.orders.size)
        assertEquals(trades, Paper.state.trades.size)
        assertTrue(Paper.state.positions.none { it.symbol.contains("25100") })
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertTrue(heard.toString(), heard.none { it.startsWith("entered|") })
        // The would-be ticket, kept by the shadow under FT3's key: the rule's 25,100 call, whole lots within Rs 5,000, out at 15:05, no stop.
        val w = OffShadow.view.value.openOf(Ft3Rules.SOURCE).single()
        assertTrue(w.symbol, w.symbol.contains("25100"))
        assertEquals(1, w.side); assertEquals(1, w.view)
        assertEquals(0, w.qty % 65)
        assertEquals(w.lots * 65, w.qty)
        assertTrue("whole lots within Rs 5,000: ${w.qty} @ ${w.entry}", w.lots >= 1 && w.qty * 8.0 <= 5_000.0 + 1e-9)
        assertEquals(Ft3Rules.EXIT_AT, w.exits.exitAt)
        assertNull(w.exits.stop); assertNull(w.exits.trailPct); assertNull(w.exits.target); assertNull(w.exits.lockAfterRs)
        assertEquals(8.0, w.entry, 1e-9)
        // Its row and diagnostics say it runs in shadow; the day is decided (one would-be ticket an index a day).
        val st = Ft3Arm.view.value.status["NIFTY"].orEmpty()
        assertTrue(st, st.startsWith("switched off - ") && st.contains("would buy"))
        assertTrue(Ft3Arm.diagLine(), Ft3Arm.diagLine().contains(com.optionslab.engine.orb.OffShadow.OFF_WORDS))
        assertTrue(Ft3Arm.diagLine(hideNet = true), !Ft3Arm.diagLine(hideNet = true).contains("Rs "))
        market(LocalTime.of(11, 31)); tick(LocalTime.of(11, 31))
        assertEquals(1, OffShadow.view.value.openOf(Ft3Rules.SOURCE).size)
        assertEquals(orders, Paper.state.orders.size)
        // The paper forward test's signal record is not written while off (the shadow is that record).
        assertTrue(view().signals.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun offALimitTheShadowWouldNotHaveFilledIsAskedAgainInTheWindow() {
        runBlocking { Ft3Arm.setArmed(false) }
        // The ask is Rs 9 at 11:21: above the LIMIT (ask + a tick from the paper quote's Rs 8, at most 8 x 1.2 + 0.10): not kept.
        OffShadow.testPrice = { _ -> Triple(9.0, 8.9, 9.0) }
        market(LocalTime.of(11, 21)); tick(LocalTime.of(11, 21))
        assertTrue(OffShadow.view.value.openOf(Ft3Rules.SOURCE).isEmpty())
        val st = Ft3Arm.view.value.status["NIFTY"].orEmpty()
        assertTrue(st, st.contains("would not have filled") && st.contains("asked again until 11:24"))
        // 11:22: back at Rs 8 - the same mark's would-be ticket is kept.
        OffShadow.testPrice = { _ -> Triple(8.0, 7.9, 8.0) }
        market(LocalTime.of(11, 22)); tick(LocalTime.of(11, 22))
        assertEquals(1, OffShadow.view.value.openOf(Ft3Rules.SOURCE).size)
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun offTheKillSwitchStillRefusesTheWouldBeTicket() {
        runBlocking { Ft3Arm.setArmed(false) }
        TradeFixtures.killSwitch(true)
        market(LocalTime.of(11, 21)); tick(LocalTime.of(11, 21))
        assertTrue(OffShadow.view.value.openOf(Ft3Rules.SOURCE).isEmpty())
        assertTrue(Ft3Arm.view.value.status["NIFTY"].orEmpty().contains("kill switch"))
        assertNothingAtZerodha()
    }

    @Test fun onThePaperPathIsUnchangedAndTheShadowGetsNothing() {
        assertTrue(view().armed)
        market(LocalTime.of(11, 21)); tick(LocalTime.of(11, 21))
        val p = Ft3Arm.view.value.open.single()
        assertTrue(Paper.state.positions.any { it.symbol == p.symbol && it.quantity == p.qty })
        assertTrue(OffShadow.view.value.openOf(Ft3Rules.SOURCE).isEmpty())
        assertTrue(Ft3Arm.diagLine().contains("on (paper)"))
        assertFalse(Ft3Arm.diagLine().contains(com.optionslab.engine.orb.OffShadow.OFF_WORDS))
        assertEquals("bought", view().signals.single().outcome)
        assertNothingAtZerodha()
    }

    /** Off while it holds a paper ticket: the ticket still sells at 15:05 on paper; a would-be ticket is never asked for it. */
    @Test fun offWhileHoldingTheTicketStillSellsAt1505OnPaper() {
        market(LocalTime.of(11, 21)); tick(LocalTime.of(11, 21))
        assertEquals(1, Ft3Arm.view.value.open.size)
        runBlocking { Ft3Arm.setArmed(false) }
        market(LocalTime.of(15, 5)); tick(LocalTime.of(15, 5), seconds = 2)
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertEquals(Ft3Rules.EXIT_WHY, Ft3Arm.view.value.closed.single().why)
        assertTrue(OffShadow.view.value.openOf(Ft3Rules.SOURCE).isEmpty())
        assertNothingAtZerodha()
    }
}
