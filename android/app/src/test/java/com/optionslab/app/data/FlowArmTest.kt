package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
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
import com.optionslab.engine.orb.LiveMirror
import com.optionslab.engine.signals.FlowRules
import com.optionslab.ira.AutoChange
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
 * IraThinker's flow ([FlowArm], 28 Oct): with the app in Live, logged in, real orders allowed and "AI trades go live" on, a plan that
 * passes the flow on Zerodha's own stream candles is bought on PAPER only (its own Trade live switch starts off), a PUT on a
 * breakdown, its premium stop resting in the paper book; no alert and no notification; nothing read from Upstox, Yahoo or the
 * web; every guard refuses (the kill switch, the bot's stop, the expiry day, the stream off); a gap in the minutes is never decided
 * on; a restart restores the trade first and its stops keep running; its switch is on once by the update, logged, undone in one
 * tap; and not one order reaches Zerodha.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class FlowArmTest : RobolectricTest() {
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

    /** One minute of [token] on the stream: open, high, low and close ticks inside it (exchange stamps), arriving now. */
    private fun minute(token: Long, t: LocalTime, o: Double, h: Double, l: Double, c: Double, prev: Double) {
        val s0 = sec(day, t.hour, t.minute)
        KiteStream.feedForTest(listOf(1L to o, 15L to h, 30L to l, 58L to c).map { (ds, px) ->
            KiteTicks.Tick(token, px, close = prev, exchangeTime = s0 + ds) })
    }

    /**
     * NIFTY's day on the stream from 09:15 to [until] (exclusive): the range 25,000-25,100 (midpoint 25,050), a 09:30 bar just
     * under the midpoint, then a 5-minute bar closing at 24,980 from 09:35 (a breakdown) - [skip] a minute missed. India VIX: its
     * range 13.0-13.2, broken up by the 09:30 bar.
     */
    private fun stream(until: LocalTime, skip: LocalTime? = null) {
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) {
            if (t != skip) {
                val m = t.hour * 60 + t.minute
                when {
                    m < 9 * 60 + 20 -> minute(nifty, t, 25_050.0, 25_100.0, 25_040.0, 25_060.0, 25_100.0)
                    m < 9 * 60 + 25 -> minute(nifty, t, 25_060.0, 25_070.0, 25_000.0, 25_040.0, 25_100.0)
                    m < 9 * 60 + 30 -> minute(nifty, t, 25_040.0, 25_055.0, 25_030.0, 25_050.0, 25_100.0)
                    m < 9 * 60 + 35 -> minute(nifty, t, 25_050.0, 25_055.0, 25_040.0, 25_045.0, 25_100.0)
                    else -> minute(nifty, t, 25_045.0, 25_047.0, 24_975.0, 24_980.0, 25_100.0)
                }
                if (m < 9 * 60 + 30) minute(vix, t, 13.1, 13.2, 13.0, 13.1, 12.9) else minute(vix, t, 13.2, 13.5, 13.15, 13.4, 12.9)
            }
            t = t.plusMinutes(1)
        }
    }

    /** Five past NIFTY sessions that broke down at 09:35 and fell to the target: the Time Machine's history. */
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

    /** The option's price on the stream now. */
    private fun option(token: Long, px: Double) = KiteStream.feedForTest(listOf(KiteTicks.Tick(token, px, bid = px - 0.1, ask = px + 0.1)))

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        // Everything that could send an order to Zerodha is on: Live, real orders allowed, logged in, AI trades live.
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
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
        kite.requests.clear(); upstox.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        FlowArm.testNoAutoOn = true
        KiteStream.liveForTest(false)
        KiteStream.resetForTest()
        if (Autopilot.enabled()) Autopilot.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun view() = runBlocking { FlowArm.refresh() }
    private fun tick(t: LocalTime, s: Long = 0) { at(t, s); runBlocking { FlowArm.tick() } }

    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    /** A breakdown day up to 09:41, with history and the option's price: the flow's plan passes. */
    private fun breakdownAt0941() {
        history()
        stream(LocalTime.of(9, 41))
        option(peToken, 100.0); option(ceToken, 90.0)
        tick(LocalTime.of(9, 41), 2)
    }

    // ---- on after the update, once; logged; undone in one tap ---------------------------------------------------------

    @Test fun theUpdateSwitchesItOnOnceOnPaperAndNeverAgain() {
        val v = view()
        assertTrue("on after the update (Boss's ask)", v.armed)
        assertTrue(v.log.toString(), v.log.single { "switched on once by the 28 Oct update" in it }.contains(FlowRules.NOT_PROVEN))
        assertTrue(FlowArm.diagLine().contains("on (paper)") && FlowArm.diagLine().contains(FlowRules.NOT_PROVEN))
        assertEquals("${FlowRules.LABEL} off. Nothing new is bought.", runBlocking { FlowArm.setArmed(false) })
        AutomationSupport.reloadFromDisk(FlowArm)
        assertFalse("Boss's off is kept", view().armed)
        assertEquals(1, view().log.count { "switched on once" in it })
        // Its own Trade live switch starts off: only Boss turns it on, with the PIN.
        assertFalse(ArmLive.view.value.on(FlowRules.SOURCE))
        assertEquals(LiveMirror.FLOW, LiveMirror.spec(FlowRules.SOURCE)!!.family)
    }

    @Test fun itIsInTheChangeLogAsBossOwnAndOneTapSwitchesItOff() {
        org.junit.Assume.assumeTrue("the change log is in IraAlgo with Jarvis only", Autopilot.enabled())
        view()
        fun entries() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.PAPER_ARM_ON && it.change.target == AutoChange.FLOW_TARGET }
        val deadline = System.currentTimeMillis() + 10_000
        while (entries().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val e = entries().single()
        assertEquals(AutoChange.Status.KEPT, e.status); assertEquals("your choice", e.by)
        assertTrue(e.change.reason.contains("not proven"))
        assertFalse("no PIN: on paper", Autopilot.undoNeedsPin(e.id))
        val said = Autopilot.undo(e.id)
        assertTrue(said, said.startsWith("Undone"))
        assertFalse(view().armed)
    }

    @Test fun theUndosSetterSwitchesItOffAndBackOn() {
        assertTrue(view().armed)
        val on = AutoChange.flowOn()
        assertEquals("${FlowRules.LABEL} off. Nothing new is bought.", Autopilot.apply(AutoChange.inverse(on, "undone by you"), false))
        assertFalse(view().armed)
        assertTrue(Autopilot.apply(on, false)!!.contains("on paper"))
        assertTrue(view().armed)
    }

    // ---- the flow's plan becomes a paper order -------------------------------------------------------------------------

    @Test fun aPlanThatPassesTheFlowIsBoughtOnPaperAsAPutWithItsStopResting() {
        assertTrue(view().armed)
        assertTrue("on, in its hours: the watch wants its pass", FlowArm.wantsPass(9 * 60 + 41))
        breakdownAt0941()
        val v = view()
        val p = v.open.single()
        assertEquals("NIFTY", p.index); assertEquals("PE", p.right); assertTrue(p.symbol, p.symbol.endsWith("25000PE"))
        assertEquals(65, p.qty)
        assertEquals(25_050.0, p.idxStop, 1e-9); assertEquals(24_840.0, p.idxTarget, 1e-9)
        assertEquals(FlowRules.premiumStop(p.entry), p.stop, 1e-9)
        val stop = Paper.state.orders.single { it.orderId == p.stopOrderId }
        assertEquals("SL-M", stop.priceType); assertEquals("SELL", stop.action); assertEquals(p.stop, stop.triggerPrice!!.toDouble(), 1e-9)
        val c = v.candidates.single { it.index == "NIFTY" }
        assertTrue(c.taken); assertTrue(c.final); assertTrue(c.why, c.why.startsWith("taken"))
        assertEquals(1, v.passed(FlowRules.Stage.RISK_GATE)); assertEquals(1, v.takenToday)
        assertTrue(v.log.any { it.contains("bought") && it.contains(FlowRules.NOT_PROVEN) })
        // Its ticket says what it is.
        assertTrue(runBlocking { Strategies.owners() }["paper:${p.orderId}"].orEmpty().contains(FlowRules.NOT_PROVEN))
        // The other indices had no stream minutes: never a decision.
        assertTrue(v.candidates.filter { it.index != "NIFTY" }.none { it.taken })
        // A second pass never buys it twice.
        tick(LocalTime.of(9, 42))
        assertEquals(1, view().open.size)
        // Paper only: nothing at Zerodha although Live, real orders and AI trades are on; no alert, no notification.
        assertNothingAtZerodha()
        assertTrue(com.optionslab.app.testing.Background.notifications(context).allNotifications.isEmpty())
        // Zerodha's live data only: not one read of Upstox's (or any web) prices.
        assertEquals("no Upstox read", emptyList<String>(), upstox.requests.toList())
        // Index options only: every paper order is on one of the flow's indices.
        assertTrue(Paper.state.orders.all { o -> FlowRules.INDICES.any { o.symbol.startsWith(it) } })
    }

    @Test fun indexOnlyNeverAStockMcxOrCrypto() {
        assertEquals(listOf("NIFTY", "BANKNIFTY", "FINNIFTY"), FlowRules.INDICES)
        for (u in listOf("RELIANCE", "CRUDEOIL", "GOLDM", "NATURALGAS", "BTCUSD", "SENSEX"))
            assertFalse(u, FlowRules.tradable(u))
        assertEquals("not an index the flow trades",
            FlowRules.entryRefusal("CRUDEOIL", true, LocalTime.of(10, 0), false, true, false, false, null, true, 0, false))
    }

    // ---- every guard refuses -------------------------------------------------------------------------------------------

    @Test fun theKillSwitchRefusesAndSaysSo() {
        AppSettings.save(AppSettings.load().copy(guardKill = true))
        breakdownAt0941()
        val v = view()
        assertTrue(v.open.isEmpty())
        val c = v.candidates.single { it.index == "NIFTY" }
        assertFalse(c.taken); assertTrue(c.why, c.why.contains("kill switch"))
        assertTrue(Paper.state.orders.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun theBotsStopForTodayRefuses() {
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        breakdownAt0941()
        assertTrue(view().open.isEmpty())
        assertTrue(view().candidates.single { it.index == "NIFTY" }.why.contains("stopped for today"))
    }

    @Test fun theIndexsOwnExpiryDayIsSkipped() {
        AutomationSupport.contracts(context, listOf(Upstox.Contract("NIFTY", day, 25_000.0, Right.PE, 65, pe, "NIFTY-FL-25000PE")))
        breakdownAt0941()
        assertTrue(view().open.isEmpty())
        assertTrue(view().candidates.single { it.index == "NIFTY" }.why.contains("expiry day"))
    }

    @Test fun theStreamOffRefusesAndAGapIsNeverDecidedOn() {
        history()
        stream(LocalTime.of(9, 41), skip = LocalTime.of(9, 22))
        option(peToken, 100.0)
        tick(LocalTime.of(9, 41), 2)
        val c = view().candidates.single { it.index == "NIFTY" }
        assertTrue(c.why, c.why.contains("gap")); assertTrue(c.final)
        assertTrue(view().open.isEmpty())
        // An index with no stream minutes at all (logged out, the stream off): nothing decided, said so.
        assertTrue(view().candidates.single { it.index == "BANKNIFTY" }.why.contains("no Zerodha minutes"))
        assertTrue(Paper.state.orders.isEmpty())
    }

    @Test fun aMinuteSeenOnlyInPartIsAGap() {
        history()
        // The stream (re)started at 09:15:40: its 09:15 minute lost its first 40 seconds - never a range from part of a minute.
        val s0 = sec(day, 9, 15)
        KiteStream.feedForTest(listOf(40L to 25_060.0, 58L to 25_060.0).map { (ds, px) -> KiteTicks.Tick(nifty, px, close = 25_100.0, exchangeTime = s0 + ds) })
        stream(LocalTime.of(9, 41), skip = LocalTime.of(9, 15))
        option(peToken, 100.0)
        tick(LocalTime.of(9, 41), 2)
        val c = view().candidates.single { it.index == "NIFTY" }
        assertTrue(c.why, c.why.contains("gap in Zerodha's minutes at 09:15"))
        assertTrue(view().open.isEmpty())
    }

    @Test fun marketClosedAndOffNeverWantAPass() {
        assertFalse("before 09:14", FlowArm.wantsPass(9 * 60))
        assertFalse("after the stream's hours", FlowArm.wantsPass(15 * 60 + 45))
        runBlocking { FlowArm.setArmed(false) }
        assertFalse("off with nothing held", FlowArm.wantsPass(10 * 60))
        // A holiday: no pass decides anything.
        var hol = day.plusDays(1)
        while (Market.isTradingDay(hol)) hol = hol.plusDays(1)
        runBlocking { FlowArm.setArmed(true) }
        Market.testClock = Clock.fixed(hol.atTime(10, 0).atZone(IST).toInstant(), IST)
        runBlocking { FlowArm.tick() }
        assertTrue(view().candidates.isEmpty())
    }

    // ---- open positions first: exits at full cadence, a restart restores them ---------------------------------------------

    @Test fun theRestingStopFillsAndIsTheExit() {
        breakdownAt0941()
        val p = view().open.single()
        option(peToken, p.stop - 1)
        at(LocalTime.of(9, 43))
        runBlocking { Paper.tick() }
        runBlocking { FlowArm.manageOnly() }
        val done = view().closed.single()
        assertEquals("premium stop", done.why)
        assertNothingAtZerodha()
    }

    @Test fun theIndexStopSellsAtFullCadence() {
        breakdownAt0941()
        assertTrue(FlowArm.holding()); assertTrue(FlowArm.wantsPass(12 * 60))
        // NIFTY back over the range's midpoint (25,050): IraThinker's index stop, in the 15-second lane.
        KiteStream.feedForTest(listOf(KiteTicks.Tick(nifty, 25_060.0, close = 25_100.0, exchangeTime = sec(day, 9, 43, 5))))
        option(peToken, 95.0)
        at(LocalTime.of(9, 43), 6)
        runBlocking { FlowArm.manageOnly() }
        val done = view().closed.single()
        assertEquals("index stop", done.why)
        assertTrue("its resting stop is taken out", Paper.state.orders.none { it.priceType == "SL-M" && it.status == "trigger pending" })
        assertNothingAtZerodha()
    }

    @Test fun aRestartMidTradeRestoresItFirstAndKeepsWatchingIt() {
        breakdownAt0941()
        assertEquals(1, FlowArm.view.value.open.size)
        FlowArm.wipe()
        assertTrue(FlowArm.view.value.open.isEmpty())
        assertTrue("something open: the watch is started first", runBlocking { PositionsFirst.restore() })
        assertEquals(1, FlowArm.view.value.open.size)
        assertTrue("its minute lane and 15-second check keep running", FlowArm.holding())
        assertTrue(HeldNow.any())
        // Switched off, what it holds still closes at the 15:15 square-off.
        runBlocking { FlowArm.setArmed(false) }
        option(peToken, 101.0)
        at(LocalTime.of(15, 15), 2)
        runBlocking { FlowArm.manageOnly() }
        assertEquals("square-off 15:15", view().closed.single().why)
        assertNothingAtZerodha()
    }

    @Test fun anInterruptedOrderIsNeverPlacedAgain() {
        breakdownAt0941()
        assertNull(view().candidates.firstOrNull { it.why.contains("interrupted") })
        assertEquals(1, Paper.state.orders.count { it.action == "BUY" })
    }

    @Test fun theDaysRecordIsKeptAfterTheClose() {
        stream(LocalTime.of(10, 30))
        at(LocalTime.of(15, 30), 5)
        runBlocking { FlowArm.tick() }
        assertTrue(view().log.toString(), view().log.any { it.contains("recorded for the Time Machine") })
        assertEquals(1, view().sessions)
        assertFalse("recorded: no more passes after the close", FlowArm.wantsPass(15 * 60 + 31))
        // Once a day.
        runBlocking { FlowArm.tick() }
        assertEquals(1, view().log.count { it.contains("recorded for the Time Machine") })
    }

    @Test fun jarvisSaysItIsNotProvenAndNoRupeesWhenLocked() {
        breakdownAt0941()
        val a = runBlocking { FlowArm.talk() }
        assertTrue(a, a.contains(FlowRules.NOT_PROVEN) && a.contains("NIFTY taken") && a.contains("holding"))
        assertFalse(runBlocking { FlowArm.talk(locked = true) }.contains("₹"))
    }

    @Test fun eraseEverythingClearsItsBookAndRecord() {
        breakdownAt0941()
        assertTrue(FlowArm.holdsForErase())
        assertTrue(FlowArm.filesForErase().any { it.exists() })
        FlowArm.wipeForErase()
        assertFalse(FlowArm.holdsForErase())
        assertTrue(FlowArm.filesForErase().none { it.exists() })
    }
}
