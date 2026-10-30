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
import com.optionslab.engine.signals.FlowRules
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
 * IraThinker's flow switched OFF runs in shadow (30 Oct 2026, Boss: "If i switch any arm off, it should be always run in shadow and
 * record only, so i can continuously observe it"): off, it still runs the flow and the plan that passes becomes a would-be trade in
 * the switched-off shadow ([OffShadow]) with its own exits - the 20% premium stop, IraThinker's index stop and 2R index target, flat
 * at 15:15, its LIMIT - and NO paper order, NO live path, nothing at Zerodha. The guards still judge it (the kill switch). On, the
 * paper path is unchanged and nothing goes to the shadow. Switched off while holding, its own exits keep closing what it holds.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class FlowArmOffShadowTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private lateinit var expiry: LocalDate
    private val nifty = 256265L
    private val vix = 264969L
    private val peToken = 31_000_001L
    private val ceToken = 31_000_002L
    private val pe = "NSE_FO|FL25000PE"
    private val ce = "NSE_FO|FL25000CE"

    private fun at(t: LocalTime, s: Long = 0) { Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(s).atZone(IST).toInstant(), IST) }
    private fun sec(d: LocalDate, h: Int, m: Int, s: Int = 0) = d.atTime(h, m, s).atZone(IST).toEpochSecond()

    private fun minute(token: Long, t: LocalTime, o: Double, h: Double, l: Double, c: Double, prev: Double) {
        val s0 = sec(day, t.hour, t.minute)
        KiteStream.feedForTest(listOf(1L to o, 15L to h, 30L to l, 58L to c).map { (ds, px) ->
            KiteTicks.Tick(token, px, close = prev, exchangeTime = s0 + ds) })
    }

    /** NIFTY's breakdown day on the stream (as [FlowArmTest]): range 25,000-25,100, a break to 24,980 from 09:35; VIX breaks up. */
    private fun stream(until: LocalTime) {
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) {
            val m = t.hour * 60 + t.minute
            when {
                m < 9 * 60 + 20 -> minute(nifty, t, 25_050.0, 25_100.0, 25_040.0, 25_060.0, 25_100.0)
                m < 9 * 60 + 25 -> minute(nifty, t, 25_060.0, 25_070.0, 25_000.0, 25_040.0, 25_100.0)
                m < 9 * 60 + 30 -> minute(nifty, t, 25_040.0, 25_055.0, 25_030.0, 25_050.0, 25_100.0)
                m < 9 * 60 + 35 -> minute(nifty, t, 25_050.0, 25_055.0, 25_040.0, 25_045.0, 25_100.0)
                else -> minute(nifty, t, 25_045.0, 25_047.0, 24_975.0, 24_980.0, 25_100.0)
            }
            if (m < 9 * 60 + 30) minute(vix, t, 13.1, 13.2, 13.0, 13.1, 12.9) else minute(vix, t, 13.2, 13.5, 13.15, 13.4, 12.9)
            t = t.plusMinutes(1)
        }
    }

    private fun history() {
        val days = generateSequence(day.minusDays(1)) { it.minusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(5).toList()
        val m = days.associateWith { d ->
            val bars = ArrayList<Upstox.Bar>()
            fun b(h: Int, mm: Int, o: Double, hi: Double, lo: Double, c: Double) { bars += Upstox.Bar(sec(d, h, mm), o, hi, lo, c, 0, 0) }
            b(9, 15, 25_050.0, 25_100.0, 25_040.0, 25_060.0); b(9, 20, 25_060.0, 25_070.0, 25_000.0, 25_040.0); b(9, 25, 25_040.0, 25_055.0, 25_030.0, 25_050.0)
            b(9, 30, 25_050.0, 25_055.0, 25_040.0, 25_045.0)
            var prev = 25_045.0
            for (i in 0 until 70) { val mm = 35 + 5 * i; val c = 24_980.0 - 30 * i; b(9 + mm / 60, mm % 60, prev, maxOf(prev, c) + 2, minOf(prev, c) - 2, c); prev = c }
            FlowRules.encode(bars)
        }
        FlowArm.sessionsForTest(mapOf("NIFTY" to m))
    }

    private fun option(token: Long, px: Double) = KiteStream.feedForTest(listOf(KiteTicks.Tick(token, px, bid = px - 0.1, ask = px + 0.1)))

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        // Everything that could send an order to Zerodha is on: Live, real orders allowed, logged in, AI trades live.
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        day = AutomationSupport.tradingDayAt(9, 41).toLocalDate()
        expiry = day.plusDays(6)
        while (!Market.isTradingDay(expiry)) expiry = expiry.plusDays(1)
        at(LocalTime.of(9, 20))
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("NIFTY", expiry, 25_000.0, Right.PE, 65, pe, "NIFTY-FL-25000PE"),
            Upstox.Contract("NIFTY", expiry, 25_000.0, Right.CE, 65, ce, "NIFTY-FL-25000CE")))
        kite.instruments += FakeKite.Ins(peToken, "NIFTYFL25000PE", "NIFTY", expiry, 25_000.0, "PE", 65)
        kite.instruments += FakeKite.Ins(ceToken, "NIFTYFL25000CE", "NIFTY", expiry, 25_000.0, "CE", 65)
        java.io.File(context.filesDir, "kite_instruments.json").delete()
        runBlocking { Broker.instruments() }
        KiteStream.resetForTest()
        KiteStream.liveForTest(true)
        FlowArm.testNoAutoOn = false
        // The switched-off shadow's book, its session and its price read (the option's ask 100.10).
        OffShadow.init(context); OffShadow.wipe()
        OffShadow.testSession = true
        OffShadow.testPrice = { Triple(100.0, 99.9, 100.1) }
        kite.requests.clear(); upstox.requests.clear()
    }

    @After fun down() {
        OffShadow.wipe()
        Market.testClock = null
        FlowArm.testNoAutoOn = true
        KiteStream.liveForTest(false)
        KiteStream.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun view() = runBlocking { FlowArm.refresh() }
    private fun tick(t: LocalTime, s: Long = 0) { at(t, s); runBlocking { FlowArm.tick() } }
    private fun shadowOpen() = OffShadow.view.value.openOf(FlowRules.SOURCE)

    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    private fun breakdownAt0941() {
        history()
        stream(LocalTime.of(9, 41))
        option(peToken, 100.0); option(ceToken, 90.0)
        tick(LocalTime.of(9, 41), 2)
    }

    @Test fun offThePlanIsAWouldBeTradeInTheShadowAndNothingIsOrdered() {
        assertTrue(view().armed)
        runBlocking { FlowArm.setArmed(false, log = false) }
        assertFalse(view().armed)
        assertTrue("off, in its hours: it still wants its pass (decides in shadow)", FlowArm.wantsPass(9 * 60 + 41))
        breakdownAt0941()
        val v = view()
        // No paper order, no paper position, nothing held by the arm.
        assertTrue(v.open.isEmpty()); assertFalse(FlowArm.holding())
        assertTrue(Paper.state.orders.toString(), Paper.state.orders.isEmpty())
        assertTrue(Paper.state.positions.none { it.quantity != 0 })
        // The would-be trade in the shadow, with the arm's own exits.
        val w = shadowOpen().single()
        assertEquals(FlowRules.SOURCE, w.key); assertEquals(FlowRules.LABEL, w.label)
        assertTrue(w.symbol, w.symbol.endsWith("25000PE")); assertEquals(-1, w.view); assertEquals(1, w.side); assertEquals(65, w.qty)
        assertEquals("bought at the ask", 100.1, w.entry, 1e-9)
        assertEquals(FlowRules.premiumStop(100.0), w.exits.stop!!, 1e-9)
        assertEquals("index stop: the range's midpoint", 25_050.0, w.exits.indexStop!!, 1e-9)
        assertEquals("index target: 2R", 24_840.0, w.exits.indexTarget!!, 1e-9)
        assertEquals(NtfyRules.SQ, w.exits.exitAt)
        assertTrue(w.ref, w.ref.endsWith("|${FlowRules.SOURCE}|NIFTY"))
        // The day's candidate says so, final: never asked twice.
        val c = v.candidates.single { it.index == "NIFTY" }
        assertFalse(c.taken); assertTrue(c.final); assertTrue(c.why, c.why.contains("switched off - runs in shadow"))
        tick(LocalTime.of(9, 42))
        assertEquals(1, shadowOpen().size)
        assertTrue(Paper.state.orders.isEmpty())
        // Its row line reads as running in shadow.
        assertTrue(FlowArm.diagLine(), FlowArm.diagLine().contains(com.optionslab.engine.orb.OffShadow.OFF_WORDS))
        assertTrue(FlowArm.diagLine(), FlowArm.diagLine().contains("1 open"))
        // Never mirrored live: nothing at Zerodha although Live, real orders and AI trades are on.
        assertNothingAtZerodha()
    }

    @Test fun onThePaperPathIsUnchangedAndNothingGoesToTheShadow() {
        assertTrue(view().armed)
        breakdownAt0941()
        val p = view().open.single()
        assertTrue(p.symbol, p.symbol.endsWith("25000PE"))
        assertEquals(FlowRules.premiumStop(p.entry), p.stop, 1e-9)
        assertTrue(Paper.state.orders.any { it.action == "BUY" && it.symbol == p.symbol })
        assertTrue(view().candidates.single { it.index == "NIFTY" }.taken)
        assertTrue("on: no would-be trade", shadowOpen().isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun offTheGuardsStillJudgeTheWouldBeTrade() {
        runBlocking { FlowArm.setArmed(false, log = false) }
        AppSettings.save(AppSettings.load().copy(guardKill = true))
        breakdownAt0941()
        val c = view().candidates.single { it.index == "NIFTY" }
        assertTrue(c.why, c.why.contains("kill switch"))
        assertTrue("the kill switch refuses the would-be trade too", shadowOpen().isEmpty())
        assertTrue(Paper.state.orders.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun switchedOffWhileHoldingItsOwnExitsStillCloseIt() {
        breakdownAt0941()
        assertTrue(FlowArm.holding())
        runBlocking { FlowArm.setArmed(false, log = false) }
        // NIFTY back over the range's midpoint (25,050): IraThinker's index stop, in the 15-second lane, as before.
        KiteStream.feedForTest(listOf(KiteTicks.Tick(nifty, 25_060.0, close = 25_100.0, exchangeTime = sec(day, 9, 43, 5))))
        option(peToken, 95.0)
        at(LocalTime.of(9, 43), 6)
        runBlocking { FlowArm.manageOnly() }
        assertEquals("index stop", view().closed.single().why)
        // Its trade was real (on), so nothing went to the shadow.
        assertTrue(shadowOpen().isEmpty())
        assertNothingAtZerodha()
    }
}
