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
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.BreadthDonchianArm
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.OovArm
import com.optionslab.engine.orb.PassedArms
import com.optionslab.engine.orb.Solo2
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
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * The paper arms that passed research ([PassedArmsHost], 15 Oct, Boss: "Both") are PAPER ONLY: with the app in Live, logged in
 * to Zerodha and real orders allowed, R51 Donchian 20 and R52 Open outside value buy their 09:20 signals on paper - not one
 * order at Zerodha - and their exits (stop, target, time) sell them; the update switches all three on once (logged, undone in
 * one tap); a restart restores what they hold first; the kill switch refuses an entry; an index's expiry day trades nothing;
 * Solo 2.0's model is read from the assets once and scores its candidates (one trade a day at most); a forward bar that fails
 * switches its arm off by itself.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class PassedArmsHostTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")
    private val ce = "NSE_FO|PA25050CE"
    private val pe = "NSE_FO|PA25150PE"
    private val bookFile get() = File(context.filesDir, "passed_arms.vault")

    private fun at(t: LocalTime, seconds: Long = 20) {
        Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(seconds).atZone(IST).toInstant(), IST)
    }

    private fun tick(t: LocalTime, seconds: Long = 20) { at(t, seconds); runBlocking { PassedArmsHost.tick() } }

    private fun minutes(key: String, until: LocalTime, px: (LocalTime) -> Double) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) { val c = px(t); out += FakeUpstox.Candle(t, c, c + 1, c - 1, c); t = t.plusMinutes(1) }
        upstox.minutes[key] = out
    }

    /** NIFTY yesterday: flat at 25,000 all day (high 25,001, low 24,999): its value area is about 24,995 - 25,005. */
    private fun yesterday(): List<Bar> {
        val d = day.minusDays(1)
        return (0 until 375).map { Bar(d.atTime(9, 15).plusMinutes(it.toLong()), 25_000.0, 25_001.0, 24_999.0, 25_000.0) }
    }

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
        day = AutomationSupport.tradingDayAt(9, 20).toLocalDate()
        at(LocalTime.of(9, 0))
        // NIFTY expires in a week (today is not its expiry day): the 25,050 CE and the 25,150 PE are listed, lot 65.
        val exp = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("NIFTY", exp, 25_050.0, Right.CE, 65, ce, "NIFTY-PA-25050CE"),
            Upstox.Contract("NIFTY", exp, 25_150.0, Right.PE, 65, pe, "NIFTY-PA-25150PE"),
            Upstox.Contract("NIFTY", exp.plusDays(7), 25_000.0, Right.CE, 65, "NSE_FO|PANEXT", "NIFTY-PA-NEXT")))
        PassedArmsHost.testHistory = { u -> if (u == "NIFTY") yesterday() else emptyList() }
        PassedArmsHost.testDaily = { emptyList() }
        runBlocking { PassedArmsHost.prefetchNow(day) }
        OrphanWatch.wipe()
        kite.requests.clear()
    }

    @After fun down() {
        com.optionslab.ira.ArmExits.clear()
        Market.testClock = null
        PassedArmsHost.testNoAutoOn = true
        if (Autopilot.enabled()) Autopilot.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun view() = runBlocking { PassedArmsHost.refresh() }
    private fun only(source: String) { for (s in PassedArmsHost.SOURCES) runBlocking { PassedArmsHost.setArmed(s, s == source, log = false) } }

    /** Nothing at Zerodha (18 Oct: these tests leave the arms' "Trade live" unset, which the tests' app counts as off). */
    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    /** NIFTY today: 25,000 at 09:15-09:18, then [open] from 09:19 on; the CE at [call] and the PE at [put] by minute. */
    private fun market(until: LocalTime, open: Double = 25_100.0, call: (LocalTime) -> Double = { 100.0 }, put: (LocalTime) -> Double = { 90.0 }, firstOpen: Double = 25_000.0) {
        minutes(niftyKey, until) { t -> if (t.isBefore(LocalTime.of(9, 19))) firstOpen else open }
        minutes(ce, until, call)
        minutes(pe, until, put)
    }

    // ---- switched on once by the update; logged; one tap off ------------------------------------------------------------------

    @Test fun theUpdateSwitchesAllThreeOnOnceOnPaperAndNeverAgain() {
        PassedArmsHost.testNoAutoOn = false
        PassedArmsHost.wipe(); bookFile.delete()
        val v = view()
        // 19 Oct (Boss: "Yes, keep only breadth donchain"): R51 is then set to record only by the 19 Oct update; the others stay on.
        assertTrue(PassedArmsHost.SOURCES.all { v.on(it) || (it == DonchianArm.SOURCE && v.recording(it)) })
        assertEquals(3, v.log.count { "switched on once by the 15 Oct update" in it && PassedArms.PASSED in it })
        assertTrue(PassedArmsHost.diagLine().contains("on (paper only)") && PassedArmsHost.diagLine().contains(PassedArms.PASSED))
        assertEquals("${DonchianArm.LABEL} off.", runBlocking { PassedArmsHost.setArmed(DonchianArm.SOURCE, false) })
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertFalse(view().on(DonchianArm.SOURCE)); assertFalse(view().recording(DonchianArm.SOURCE))
        assertTrue(view().on(OovArm.SOURCE))
        // Three by the 15 Oct update, R72 Breadth Donchian by its own (16 Oct): four lines, never again.
        assertEquals(4, view().log.count { "switched on once" in it })
        // A book that cannot be read is never switched on by itself.
        PassedArmsHost.wipe(); bookFile.writeText("not a vault"); AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertTrue(PassedArmsHost.SOURCES.none { view().on(it) })
    }

    @Test fun theChangeLogsUndoSwitchesAnArmOffAndOnlyIt() {
        only(DonchianArm.SOURCE)
        val on = AutoChange.passedArmOn(DonchianArm.SOURCE, DonchianArm.LABEL, DonchianArm.RESEARCH_ID)
        assertNull(Autopilot.apply(on.copy(target = "paperarm:other"), false))
        assertEquals("${DonchianArm.LABEL} off.", Autopilot.apply(AutoChange.inverse(on, "undone by you"), false))
        assertFalse(view().on(DonchianArm.SOURCE))
        assertTrue(Autopilot.apply(on, false)!!.contains("paper only"))
        assertTrue(view().on(DonchianArm.SOURCE))
        assertFalse(view().on(OovArm.SOURCE))
    }

    // ---- R51 Donchian 20 ---------------------------------------------------------------------------------------------------

    @Test fun aDonchianBreakAtTheOpenBuysThe1ItmCallOnPaperAndItsStopSellsIt() {
        only(DonchianArm.SOURCE)
        market(LocalTime.of(9, 20))
        tick(LocalTime.of(9, 20))
        val p = PassedArmsHost.view.value.open.single()
        assertEquals(DonchianArm.SOURCE, p.arm); assertEquals("CE", p.right); assertEquals(65, p.qty)
        assertTrue(p.symbol, p.symbol.contains("25050"))
        assertTrue("paid the spread", p.entry >= 100.0)
        assertTrue(Paper.state.positions.any { it.symbol == p.symbol && it.quantity == 65 })
        assertNothingAtZerodha()
        // The bar is decided once: the next pass buys nothing more.
        market(LocalTime.of(9, 21)); tick(LocalTime.of(9, 21))
        assertEquals(1, PassedArmsHost.view.value.open.size)
        // 09:24 trades at 84 (under -15%): the stop sells it in the minute lane.
        market(LocalTime.of(9, 25), call = { t -> if (t.isBefore(LocalTime.of(9, 24))) 100.0 else 84.0 })
        at(LocalTime.of(9, 25)); runBlocking { PassedArmsHost.manageOnly() }
        val sold = PassedArmsHost.view.value.closed.single()
        assertEquals("stop", sold.why)
        assertTrue("${sold.net}", sold.net!! < 0)
        assertEquals(0, Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity })
        // Its own record, read back from its own file.
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertEquals(1, view().closedOf(DonchianArm.SOURCE).size)
        assertTrue(view().forwardLine(DonchianArm.SOURCE).startsWith("Forward test: 1 of 40"))
        assertNothingAtZerodha()
    }

    /**
     * 18 Oct (Boss: every paper strategy present for live too): in Live with AI trades on and R51's "Trade live" on (its default), the
     * same break also buys the same option and lot at Zerodha, its -15% stop resting there as an SL order; the paper stop's exit
     * then sells the live trade too, the Zerodha stop taken out first. The paper record stays the paper book's.
     */
    @Test fun inLiveWithTradeLiveOnTheBreakAlsoTradesAtZerodhaAndThePaperStopSellsItThere() {
        com.optionslab.app.data.ArmLive.testDefaultOff = false
        com.optionslab.app.data.ArmLive.testSync = true
        // Out of shadow with Boss's PIN (every arm starts in shadow).
        runBlocking { com.optionslab.app.data.ArmLive.setShadow(DonchianArm.SOURCE, false, pinConfirmed = true, log = false) }
        val sym = "NIFTY26OCT25050CE"
        kite.instruments += FakeKite.Ins(26_000_001, sym, "NIFTY", day.plusDays(7), 25_050.0, "CE", 65)
        kite.quote("NFO:$sym", 100.0, 99.95, 100.05)
        File(context.filesDir, "kite_instruments.json").delete()
        runBlocking { Broker.instruments() }
        kite.requests.clear()
        only(DonchianArm.SOURCE)
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        val p = PassedArmsHost.view.value.open.single()
        val (buy, stop) = kite.placed
        assertEquals(mapOf("tradingsymbol" to sym, "transaction_type" to "BUY", "order_type" to "MARKET", "quantity" to "65", "tag" to "iraarm"),
            buy.form.filterKeys { it in setOf("tradingsymbol", "transaction_type", "order_type", "quantity", "tag") })
        assertEquals(mapOf("transaction_type" to "SELL", "order_type" to "SL", "trigger_price" to "85.00", "quantity" to "65"),
            stop.form.filterKeys { it in setOf("transaction_type", "order_type", "trigger_price", "quantity") })
        val live = com.optionslab.app.data.ArmLive.view.value.open.single()
        assertEquals(DonchianArm.SOURCE, live.key)
        // 09:24 trades at 84: the paper stop sells it, and its live trade follows (the Zerodha stop out first).
        market(LocalTime.of(9, 25), call = { t -> if (t.isBefore(LocalTime.of(9, 24))) 100.0 else 84.0 })
        kite.quote("NFO:$sym", 86.0, 85.95, 86.05)
        at(LocalTime.of(9, 25)); runBlocking { PassedArmsHost.manageOnly() }
        assertEquals("stop", PassedArmsHost.view.value.closed.single().why)
        runBlocking { com.optionslab.app.data.ArmLive.watch() }
        assertEquals("CANCELLED", kite.order(live.stopOrderId!!).status)
        assertEquals("the live trade closed with its paper trade", "stop", com.optionslab.app.data.ArmLive.view.value.closed.single().why)
        assertEquals("flat at Zerodha, never short", 0, kite.positions["NFO:$sym:MIS"]?.first ?: 0)
        // Its paper record is the paper book's alone; the live one is the live path's.
        assertEquals(1, view().closedOf(DonchianArm.SOURCE).size)
        assertTrue(p.symbol != sym)
    }

    /**
     * 18 Oct (Boss: "add a shadow button"): end to end, in Live with AI trades and Trade live on but R51 in SHADOW (its default), the
     * same break sends nothing to Zerodha: its live path records a shadow trade, which closes with the paper stop.
     */
    @Test fun inShadowTheBreakSendsNothingAndItsShadowTradeFollowsThePaperStop() {
        com.optionslab.app.data.ArmLive.testDefaultOff = false
        com.optionslab.app.data.ArmLive.testSync = true
        val sym = "NIFTY26OCT25050CE"
        kite.instruments += FakeKite.Ins(26_000_001, sym, "NIFTY", day.plusDays(7), 25_050.0, "CE", 65)
        kite.quote("NFO:$sym", 100.0, 99.95, 100.05)
        File(context.filesDir, "kite_instruments.json").delete()
        runBlocking { Broker.instruments() }
        kite.requests.clear()
        only(DonchianArm.SOURCE)
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        assertEquals(1, PassedArmsHost.view.value.open.size)
        assertTrue("nothing sent in shadow", kite.placed.isEmpty())
        val sh = com.optionslab.app.data.ArmLive.view.value.openShadow.single()
        assertEquals(DonchianArm.SOURCE, sh.key); assertEquals(65, sh.qty)
        market(LocalTime.of(9, 25), call = { t -> if (t.isBefore(LocalTime.of(9, 24))) 100.0 else 84.0 })
        kite.quote("NFO:$sym", 84.0, 83.95, 84.05)
        at(LocalTime.of(9, 25)); runBlocking { PassedArmsHost.manageOnly() }
        runBlocking { com.optionslab.app.data.ArmLive.watch() }
        val c = com.optionslab.app.data.ArmLive.view.value.shadowClosed.single()
        assertEquals("stop", c.why)
        assertTrue("still nothing sent", kite.placed.isEmpty())
        assertEquals(1, com.optionslab.app.data.ArmLive.view.value.shadowRecord(DonchianArm.SOURCE).trades)
    }

    /** 16 Oct (Boss: "+50% profit, still not selling"): the position popup's Exits line is the arm's own stop, target and time. */
    @Test fun theOpenTradesExitsAreInThePopupsLineUntilItCloses() {
        only(DonchianArm.SOURCE)
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        val p = PassedArmsHost.view.value.open.single()
        val out = p.entryMinute.plusMinutes(30).toLocalTime()
        assertEquals("stop %.2f · target %.2f · time %02d:%02d".format(java.util.Locale.ENGLISH, p.entry * 0.85, p.entry * 1.3, out.hour, out.minute),
            com.optionslab.ira.ArmExits.line(false, p.symbol))
        assertNull("a paper arm's trade is never the Zerodha position's", com.optionslab.ira.ArmExits.line(true, p.symbol))
        market(LocalTime.of(9, 25), call = { t -> if (t.isBefore(LocalTime.of(9, 24))) 100.0 else 84.0 })
        at(LocalTime.of(9, 25)); runBlocking { PassedArmsHost.manageOnly() }
        assertEquals("stop", PassedArmsHost.view.value.closed.single().why)
        assertNull("closed: no exits", com.optionslab.ira.ArmExits.line(false, p.symbol))
    }

    @Test fun theExitsOfALockedTradeTheSquareOffAndSoloTwosIndexStop() {
        val t0 = day.atTime(14, 50)
        val oov = PassedArmsHost.Pos(OovArm.SOURCE, "NIFTY", "X", "CE", 65, 100.0, t0, null)
        // Before +40%: the -30% stop and the lock to come; the 60 minutes run past the 15:10 square-off, so 15:10.
        val before = PassedArmsHost.exitsOf(oov, null)
        assertEquals(70.0, before.stop!!, 1e-9); assertNull(before.target)
        assertEquals(OovArm.EXITS.squareOff, before.at)
        assertEquals("+10% locked once +40%", before.note)
        // R64's lock (16 Oct): no target, breakeven worded (never "+NaN%").
        val lk = PassedArmsHost.exitsOf(oov.copy(lock = true), null)
        assertNull(lk.target); assertEquals("breakeven locked once +30%", lk.note)
        // The lock earned: the stop is raised to it.
        val locked = PassedArmsHost.exitsOf(oov, "${t0.plusMinutes(5)}|110.0")
        assertEquals(110.0, locked.stop!!, 1e-9); assertEquals("lock held", locked.note)
        // Donchian: no lock steps, no note.
        val d = PassedArmsHost.exitsOf(oov.copy(arm = DonchianArm.SOURCE, entryTime = day.atTime(10, 15, 30)), null)
        assertEquals(LocalTime.of(10, 45), d.at); assertNull(d.note); assertEquals(130.0, d.target!!, 1e-9)
        // Solo 2.0's S1: out on the index, by 14:30.
        val s1 = PassedArmsHost.exitsOf(oov.copy(arm = Solo2.SOURCE, setup = Solo2.Setup.S1.key, idx = 25_000.0, atr = 100.0), null)
        assertNull(s1.stop); assertNull(s1.target); assertEquals(LocalTime.of(14, 30), s1.at)
        assertTrue(s1.note!!, s1.note!!.startsWith("stop on the index at "))
        assertEquals("stop on the index, breakeven lock", PassedArmsHost.exitsOf(oov.copy(arm = Solo2.SOURCE, setup = Solo2.Setup.S1.key), null).note)
    }

    @Test fun theTargetAndTheThirtyMinuteExit() {
        only(DonchianArm.SOURCE)
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        val p = PassedArmsHost.view.value.open.single()
        // Up to 131 at 09:30 (above +30%): sold, booked at the target at most.
        market(LocalTime.of(9, 31), call = { t -> if (t.isBefore(LocalTime.of(9, 30))) 105.0 else 140.0 })
        at(LocalTime.of(9, 31)); runBlocking { PassedArmsHost.manageOnly() }
        val sold = PassedArmsHost.view.value.closed.single()
        assertEquals("target", sold.why)
        assertTrue("${sold.exit}", sold.exit!! <= PassedArms.targetLevel(p.entry, DonchianArm.EXITS)!! + 1e-9)
        assertTrue(sold.net!! > 0)
    }

    // ---- R64's 30-60-100 lock (16 Oct, Boss's choice: "All three") ------------------------------------------------------------------

    @Test fun theUpdateSwitchesTheLockOnOnceAndTheChangeLogsUndoSwitchesItOff() {
        PassedArmsHost.testNoAutoOn = false
        PassedArmsHost.wipe(); bookFile.delete()
        assertTrue(view().lockOn)
        assertEquals(1, view().log.count { "30-60-100 lock turned on by the 16 Oct update" in it })
        // Once only: a restart does not log it again.
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertTrue(view().lockOn)
        assertEquals(1, view().log.count { "30-60-100 lock turned on" in it })
        // The change log's Undo switches it off (paper only, no PIN); a change for another target does nothing.
        val on = AutoChange.passedLock(true)
        assertNull(Autopilot.apply(on.copy(target = "lock:other"), false))
        assertTrue(Autopilot.apply(AutoChange.inverse(on, "undone by you"), false)!!.contains("research exits"))
        assertFalse(view().lockOn)
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertFalse("its Undo stays undone after a restart", view().lockOn)
        assertTrue(Autopilot.apply(on, false)!!.contains("is on"))
        assertTrue(view().lockOn)
        // A book that cannot be read is never switched on by itself.
        PassedArmsHost.wipe(); bookFile.writeText("not a vault"); AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertFalse(view().lockOn)
    }

    @Test fun withTheLockABigMoverIsHeldPastThirtyPercentAndTheLockSellsIt() {
        only(DonchianArm.SOURCE)
        runBlocking { PassedArmsHost.setLock(true) }
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        val p = PassedArmsHost.view.value.open.single()
        assertTrue("it entered with the lock", p.lock)
        // Up to 141 at 09:30 (+40%): the research exits would sell at the +30% target; with the lock it is held.
        market(LocalTime.of(9, 32), call = { t -> if (t.isBefore(LocalTime.of(9, 30))) 105.0 else 140.0 })
        at(LocalTime.of(9, 32)); runBlocking { PassedArmsHost.manageOnly() }
        assertEquals(1, PassedArmsHost.view.value.open.size)
        assertTrue(PassedArmsHost.view.value.closed.isEmpty())
        // A restart keeps the rule it entered with.
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertTrue(view().open.single().lock)
        // 09:33 trades at 95: under breakeven (the fill plus the round-trip charges), locked since +30% - sold by the lock.
        market(LocalTime.of(9, 34), call = { t -> if (t.isBefore(LocalTime.of(9, 30))) 105.0 else if (t.isBefore(LocalTime.of(9, 33))) 140.0 else 95.0 })
        at(LocalTime.of(9, 34)); runBlocking { PassedArmsHost.manageOnly() }
        val sold = PassedArmsHost.view.value.closed.single()
        assertEquals("lock", sold.why)
        // Labelled apart, so the research-exit record stays separable.
        assertTrue(PassedArmsHost.tradeLine(sold, emptyMap()).endsWith("30-60-100 lock"))
        assertTrue(PassedArmsHost.diagLine().contains("1 closed on it"))
        assertEquals(0, Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity })
        assertNothingAtZerodha()
    }

    @Test fun anOpenTradeKeepsTheExitsItEnteredWith() {
        only(DonchianArm.SOURCE)
        runBlocking { PassedArmsHost.setLock(false) }
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        val p = PassedArmsHost.view.value.open.single()
        assertFalse(p.lock)
        // The lock switched on while it is held: it still sells at its +30% target.
        assertEquals("The 30-60-100 lock is on for new paper trades.", runBlocking { PassedArmsHost.setLock(true) })
        assertEquals("The 30-60-100 lock is already on (paper only).", runBlocking { PassedArmsHost.setLock(true) })
        market(LocalTime.of(9, 31), call = { t -> if (t.isBefore(LocalTime.of(9, 30))) 105.0 else 140.0 })
        at(LocalTime.of(9, 31)); runBlocking { PassedArmsHost.manageOnly() }
        assertEquals("target", PassedArmsHost.view.value.closed.single().why)
        // Switched off while a locked trade is held: the note says it keeps its lock.
        runBlocking { PassedArmsHost.setLock(false) }
        assertTrue(view().log.last().contains("30-60-100 lock off"))
    }

    // ---- R66's L25: +5% locked once +25% (16 Oct, Boss's choice: "Select the best") ------------------------------------------

    @Test fun theUpdateSwitchesL25OnOnceAndItsOwnUndoSwitchesItOff() {
        PassedArmsHost.testNoAutoOn = false
        PassedArmsHost.wipe(); bookFile.delete()
        assertTrue(view().l25On); assertTrue(view().lockOn)
        assertEquals(1, view().log.count { "L25 turned on by the 16 Oct update" in it })
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertTrue(view().l25On)
        assertEquals(1, view().log.count { "L25 turned on" in it })
        assertTrue(PassedArmsHost.diagLine().contains("L25 +5% once +25% on for new trades"))
        // Its own Undo (another target than the lock's): the step off, the lock stays on; paper only, no PIN.
        val on = AutoChange.passedL25(true)
        assertTrue(Autopilot.apply(AutoChange.inverse(on, "undone by you"), false)!!.contains("plain 30-60-100 lock"))
        assertFalse(view().l25On); assertTrue(view().lockOn)
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertFalse("its Undo stays undone after a restart", view().l25On)
        assertTrue(PassedArmsHost.diagLine().contains("L25 +5% once +25% off for new trades"))
        assertTrue(Autopilot.apply(on, false)!!.contains("is on"))
        assertTrue(view().l25On)
        // The lock's Undo leaves the step's switch alone (it acts only with the lock: said so).
        assertTrue(Autopilot.apply(AutoChange.inverse(AutoChange.passedLock(true), "undone by you"), false)!!.contains("research exits"))
        assertTrue(view().l25On); assertFalse(view().lockOn)
        assertTrue(PassedArmsHost.diagLine().contains("acts only with the lock, which is off"))
        // A book that cannot be read is never switched on by itself.
        PassedArmsHost.wipe(); bookFile.writeText("not a vault"); AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertFalse(view().l25On)
    }

    /** NIFTY's CE: 100 until 09:30, [peak] 09:30 - 09:32, then [after] (the minute candles' high is the close + 1). */
    private fun ride(until: LocalTime, peak: Double, after: Double) =
        market(until, call = { t -> if (t.isBefore(LocalTime.of(9, 30))) 100.0 else if (t.isBefore(LocalTime.of(9, 33))) peak else after })

    @Test fun withL25ATradeThatWasPlus25AndCameBackSellsAtAboutPlus5EvenAfterARestart() {
        only(DonchianArm.SOURCE)
        runBlocking { PassedArmsHost.setLock(true); PassedArmsHost.setL25(true) }
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        val p = PassedArmsHost.view.value.open.single()
        assertTrue("it entered with the lock and the step", p.lock && p.l25)
        assertTrue(PassedArmsHost.tradeLine(p, emptyMap()).contains("30-60-100 lock + L25"))
        // The popup before +25%: "+5% locked once +25%".
        assertTrue(com.optionslab.ira.ArmExits.line(false, p.symbol)!!, com.optionslab.ira.ArmExits.line(false, p.symbol)!!.contains("+5% locked once +25%"))
        // 09:30 - 09:32 at 126 (high 127: past +25%, under +30%): +5% earned, nothing sold.
        val peak = p.entry * 1.26
        ride(LocalTime.of(9, 33), peak, peak)
        at(LocalTime.of(9, 33)); runBlocking { PassedArmsHost.manageOnly() }
        assertEquals(1, view().open.size)
        assertTrue(com.optionslab.ira.ArmExits.line(false, p.symbol)!!.contains("lock held"))
        // A restart in the middle of the trade: it keeps the ladder it entered with, and the lock it earned.
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertTrue(view().open.single().l25)
        // 09:33 on at +5.5% (its low a rupee under, below +5%): through the +5% lock - sold by the lock at about +5%.
        ride(LocalTime.of(9, 35), peak, p.entry * 1.055)
        at(LocalTime.of(9, 35)); runBlocking { PassedArmsHost.manageOnly() }
        val sold = view().closed.single()
        assertEquals("lock", sold.why)
        assertTrue("${sold.exit} vs ${p.entry}", sold.exit!! in p.entry * 1.02..p.entry * 1.07)
        assertTrue(PassedArmsHost.tradeLine(sold, emptyMap()).endsWith("30-60-100 lock + L25"))
        assertTrue(PassedArmsHost.diagLine().contains("1 closed on it"))
        assertEquals(0, Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity })
        assertNothingAtZerodha()
    }

    @Test fun withoutL25OrForATradeEnteredBeforeItTheSameRideIsHeld() {
        only(DonchianArm.SOURCE)
        runBlocking { PassedArmsHost.setLock(true); PassedArmsHost.setL25(false) }
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        val p = PassedArmsHost.view.value.open.single()
        assertTrue(p.lock); assertFalse(p.l25)
        assertTrue(com.optionslab.ira.ArmExits.line(false, p.symbol)!!.contains("breakeven locked once +30%"))
        // Switched on while it is held: this trade keeps the plain A4 ladder (open trades keep the exits they entered with).
        assertEquals("The +5% once +25% step is on for new paper trades.", runBlocking { PassedArmsHost.setL25(true) })
        assertEquals("The +5% once +25% step is already on (paper only).", runBlocking { PassedArmsHost.setL25(true) })
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertFalse("an older trade reads as the plain A4 ladder", view().open.single().l25)
        val peak = p.entry * 1.26
        ride(LocalTime.of(9, 35), peak, p.entry * 1.055)
        at(LocalTime.of(9, 35)); runBlocking { PassedArmsHost.manageOnly() }
        assertEquals("A4 alone: +26% then +5.5% is still held (its stop is −15%)", 1, view().open.size)
        assertTrue(view().closed.isEmpty())
        runBlocking { PassedArmsHost.setL25(false) }
        assertTrue(view().log.last().contains("L25 off"))
        assertNothingAtZerodha()
    }

    @Test fun l25WordsInThePopupTheBuyNoticeAndJarvis() {
        val t0 = day.atTime(10, 0)
        val oov = PassedArmsHost.Pos(OovArm.SOURCE, "NIFTY", "X", "CE", 65, 100.0, t0, null, lock = true, l25 = true)
        val e = PassedArmsHost.exitsOf(oov, null)
        assertNull(e.target); assertEquals(70.0, e.stop!!, 1e-9); assertEquals("+5% locked once +25%", e.note)
        val held = PassedArmsHost.exitsOf(oov, "${t0.plusMinutes(5)}|105.0")
        assertEquals(105.0, held.stop!!, 1e-9); assertEquals("lock held", held.note)
        // The plain A4 ladder is still worded as before.
        assertEquals("breakeven locked once +30%", PassedArmsHost.exitsOf(oov.copy(l25 = false), null).note)
        // Solo 2.0's S1 keeps its index exits, with the premium ladder beside them.
        val s1 = oov.copy(arm = Solo2.SOURCE, setup = Solo2.Setup.S1.key)
        assertEquals("stop on the index, breakeven lock; on the premium, +5% locked once +25%", PassedArmsHost.exitsOf(s1, null).note)
        assertEquals("stop on the index, breakeven lock; premium lock held", PassedArmsHost.exitsOf(s1, "${t0.plusMinutes(5)}|105.0").note)
        assertEquals("stop on the index, breakeven lock", PassedArmsHost.exitsOf(s1.copy(lock = false, l25 = false), null).note)
        // Jarvis: the exits new trades enter with.
        val f = PassedArmsHost.facts(PassedArmsHost.View(lockOn = true, l25On = true))
        assertTrue(f.exits!!, f.exits!!.contains("+5% locked once +25%"))
        assertTrue(PassedArmsHost.facts(PassedArmsHost.View(lockOn = true)).exits!!.contains("breakeven locked once +30%"))
    }

    @Test fun nothingOnTheIndexsOwnExpiryDayAndTheKillSwitchRefuses() {
        only(DonchianArm.SOURCE)
        // The kill switch: the break is not bought (refused), nothing placed.
        AppSettings.save(AppSettings.load().copy(guardKill = true))
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        assertTrue(PassedArmsHost.view.value.open.isEmpty())
        assertTrue(PassedArmsHost.view.value.status["${DonchianArm.SOURCE}|NIFTY"].orEmpty().contains("kill switch"))
        AppSettings.save(AppSettings.load().copy(guardKill = false))
        // Today is NIFTY's expiry: no trade at all.
        AutomationSupport.contracts(context, listOf(Upstox.Contract("NIFTY", day, 25_050.0, Right.CE, 65, ce, "NIFTY-PA-25050CE")))
        market(LocalTime.of(9, 25)); tick(LocalTime.of(9, 25))
        assertTrue(PassedArmsHost.view.value.open.isEmpty())
        assertEquals("its expiry day: no trades", PassedArmsHost.view.value.status["${DonchianArm.SOURCE}|NIFTY"])
        assertTrue(Paper.state.orders.isEmpty())
    }

    // ---- R52 Open outside value --------------------------------------------------------------------------------------------

    @Test fun openingAboveYesterdaysValueBuysTheCallAt0920AndItsSixtyMinutesSellIt() {
        only(OovArm.SOURCE)
        market(LocalTime.of(9, 20), firstOpen = 25_100.0)
        tick(LocalTime.of(9, 20))
        val p = PassedArmsHost.view.value.open.single()
        assertEquals(OovArm.SOURCE, p.arm); assertEquals("CE", p.right)
        // 60 minutes later (10:20): out on the time stop.
        market(LocalTime.of(10, 21), firstOpen = 25_100.0, call = { 104.0 })
        at(LocalTime.of(10, 21)); runBlocking { PassedArmsHost.manageOnly() }
        assertEquals("time", PassedArmsHost.view.value.closed.single().why)
        // One trade per index a day: nothing more.
        tick(LocalTime.of(10, 25))
        assertTrue(PassedArmsHost.view.value.open.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun openingInsideValueTradesNothingAndAMissedWindowIsSaid() {
        only(OovArm.SOURCE)
        market(LocalTime.of(9, 20), firstOpen = 25_000.0, open = 25_000.0)
        tick(LocalTime.of(9, 20))
        assertTrue(PassedArmsHost.view.value.open.isEmpty())
        assertTrue(PassedArmsHost.view.value.status["${OovArm.SOURCE}|NIFTY"].orEmpty().contains("inside"))
    }

    // ---- restart, positions first --------------------------------------------------------------------------------------------

    @Test fun aRestartRestoresTheOpenTradeFirstAndItsStopStillSells() {
        only(DonchianArm.SOURCE)
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        assertEquals(1, PassedArmsHost.view.value.open.size)
        // The app is killed: the start-up's positions-first step reads the book before anything else and finds it open.
        PassedArmsHost.wipe()
        assertFalse(PassedArmsHost.holding())
        assertTrue(runBlocking { PositionsFirst.restore() })
        assertTrue(PassedArmsHost.holding())
        assertTrue(PassedArmsHost.wantsPass(10 * 60))
        market(LocalTime.of(9, 27), call = { t -> if (t.isBefore(LocalTime.of(9, 25))) 100.0 else 80.0 })
        at(LocalTime.of(9, 27)); runBlocking { PassedArmsHost.manageOnly() }
        assertEquals("stop", PassedArmsHost.view.value.closed.single().why)
        assertFalse(PassedArmsHost.holding())
    }

    // ---- 16 Oct: a second trade on the same option, and the safety net ----------------------------------------------------------

    /**
     * Boss's 16 Oct day on the test option: a 09:20 break bought, sold at its target at 09:30; a second break at 09:50 (NIFTY
     * 25,100 -> 25,105, the same 1-ITM 25,050 call) bought again at about 120. The call after 09:50 is [after]. The re-entry.
     */
    private fun reEntered(until: LocalTime, after: (LocalTime) -> Double): PassedArmsHost.Pos {
        only(DonchianArm.SOURCE)
        fun index(t: LocalTime) = when { t.isBefore(LocalTime.of(9, 19)) -> 25_000.0; t.isBefore(LocalTime.of(9, 45)) -> 25_100.0; else -> 25_105.0 }
        fun call(t: LocalTime) = when { t.isBefore(LocalTime.of(9, 30)) -> 100.0; t.isBefore(LocalTime.of(9, 45)) -> 140.0
            t.isBefore(LocalTime.of(9, 51)) -> 120.0; else -> after(t) }
        fun upto(t: LocalTime) { minutes(niftyKey, t, ::index); minutes(ce, t, ::call); minutes(pe, t) { 90.0 } }
        upto(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        val first = PassedArmsHost.view.value.open.single()
        upto(LocalTime.of(9, 31)); at(LocalTime.of(9, 31)); runBlocking { PassedArmsHost.manageOnly() }
        assertEquals("target", PassedArmsHost.view.value.closed.single().why)
        upto(LocalTime.of(9, 50)); tick(LocalTime.of(9, 50))
        val second = PassedArmsHost.view.value.open.single()
        assertEquals(first.symbol, second.symbol)
        assertTrue("its own fill, not the first trade's: ${second.entry}", second.entry >= 120.0 && second.entry < 125.0)
        assertEquals(day.atTime(9, 50), second.entryMinute)
        // Booked in the book's own file at once (a restart now keeps it).
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertEquals(second.orderId, view().open.single().orderId)
        upto(until)
        return second
    }

    @Test fun aReEntryAfterAClosedTradeOnTheSameOptionSellsAtItsOwnTarget() {
        val p = reEntered(LocalTime.of(10, 1)) { t -> if (t.isBefore(LocalTime.of(9, 58))) 130.0 else 160.0 }
        at(LocalTime.of(10, 1)); runBlocking { PassedArmsHost.manageOnly() }
        val sold = PassedArmsHost.view.value.closed.filter { it.orderId == p.orderId }.single()
        assertEquals("target", sold.why)
        assertTrue("${sold.exit}", sold.exit!! <= PassedArms.targetLevel(p.entry, DonchianArm.EXITS)!! + 1e-9)
        assertEquals(2, PassedArmsHost.view.value.closed.size)
        assertFalse(PassedArmsHost.holding())
        assertEquals(0, Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity })
        assertNothingAtZerodha()
    }

    @Test fun aReEntrysThirtyMinutesAndItsStop() {
        val p = reEntered(LocalTime.of(10, 21)) { 121.0 }
        at(LocalTime.of(10, 19)); runBlocking { PassedArmsHost.manageOnly() }
        assertTrue("not before its 30 minutes", PassedArmsHost.holding())
        at(LocalTime.of(10, 21)); runBlocking { PassedArmsHost.manageOnly() }
        assertEquals("time", PassedArmsHost.view.value.closed.single { it.orderId == p.orderId }.why)
    }

    @Test fun aReEntrysStop() {
        val p = reEntered(LocalTime.of(9, 56)) { 100.0 }
        at(LocalTime.of(9, 56)); runBlocking { PassedArmsHost.manageOnly() }
        assertEquals("stop", PassedArmsHost.view.value.closed.single { it.orderId == p.orderId }.why)
    }

    @Test fun withTheOptionsFeedGoneItsTimeExitStillRuns() {
        val p = reEntered(LocalTime.of(10, 21)) { 121.0 }
        // The option's minutes stop at 09:55 (the feed lost it): no bars after that, the clock still sells it at 10:20.
        upstox.minutes[ce] = upstox.minutes[ce].orEmpty().filter { it.at.isBefore(LocalTime.of(9, 55)) }
        at(LocalTime.of(10, 21)); runBlocking { PassedArmsHost.manageOnly() }
        val v = PassedArmsHost.view.value
        val sold = v.closed.firstOrNull { it.orderId == p.orderId }
        assertTrue(v.status.toString(), sold?.why == "time" || v.status["${DonchianArm.SOURCE}|exit"].orEmpty().startsWith("exit_retry"))
    }

    /** A paper buy of the test call tagged [owner]'s, outside any arm's book (the record lost): the orphan. */
    private fun orphanBuy(owner: String): String {
        val c = Paper.ticketContract("NIFTY", day.plusDays(7), 25_050.0, Right.CE)!!
        val r = runBlocking { Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null) }
        assertTrue(r.message, r.ok)
        runBlocking { Strategies.tagOwner("paper:${r.orderId}", "$owner · entry") }
        return r.orderId!!
    }

    @Test fun anArmsPositionWithNoRecordIsTakenBackWithItsArmsExitsFromItsRealFill() {
        only("none")
        minutes(niftyKey, LocalTime.of(10, 0)) { 25_000.0 }; minutes(ce, LocalTime.of(10, 0)) { 100.0 }
        at(LocalTime.of(10, 0))
        val id = orphanBuy(DonchianArm.LABEL)
        val fill = Paper.state.trades.single { it.orderId == id }
        assertFalse(PassedArmsHost.holding())
        // The first sighting does nothing (an entry may still be being booked); the second, 20 s later, takes it back.
        at(LocalTime.of(10, 1), 0); runBlocking { OrphanWatch.run(context) }
        assertFalse(PassedArmsHost.holding())
        at(LocalTime.of(10, 1), 30); runBlocking { OrphanWatch.run(context) }
        val p = PassedArmsHost.view.value.open.single()
        assertEquals(DonchianArm.SOURCE, p.arm); assertEquals(id, p.orderId); assertEquals(PassedArmsHost.ADOPT_FILL, p.adopted)
        assertEquals(fill.price.toDouble(), p.entry, 1e-9); assertEquals(fill.timestamp, p.entryTime); assertEquals(65, p.qty)
        assertTrue(PassedArmsHost.view.value.log.last().contains("taken back"))
        // Not taken twice.
        at(LocalTime.of(10, 2)); runBlocking { OrphanWatch.run(context) }
        assertEquals(1, PassedArmsHost.view.value.open.size)
        // Its arm's own target sells it.
        minutes(ce, LocalTime.of(10, 6)) { t -> if (t.isBefore(LocalTime.of(10, 5))) 100.0 else 140.0 }
        at(LocalTime.of(10, 6)); runBlocking { PassedArmsHost.manageOnly() }
        assertEquals("target", PassedArmsHost.view.value.closed.single().why)
        assertNothingAtZerodha()
    }

    @Test fun anotherArmsUnwatchedPositionGetsTheNetsStopAndTargetAndAHandOrderIsLeftAlone() {
        only("none")
        minutes(niftyKey, LocalTime.of(10, 0)) { 25_000.0 }; minutes(ce, LocalTime.of(10, 0)) { 100.0 }
        at(LocalTime.of(10, 0))
        orphanBuy(com.optionslab.engine.orb.HeroTicketRules.OWNER)
        at(LocalTime.of(10, 1), 0); runBlocking { OrphanWatch.run(context) }
        at(LocalTime.of(10, 1), 30); runBlocking { OrphanWatch.run(context) }
        val prot = runBlocking { Protections.active() }.single()
        assertTrue(prot.symbol.contains("25050"))
        assertTrue(Paper.state.orders.count { it.action == "SELL" && (it.status == "open" || it.status == "trigger pending") } >= 1)
        assertFalse("never adopted by the passed arms", PassedArmsHost.holding())
        assertNothingAtZerodha()
    }

    @Test fun aHandBuyIsNeverAnOrphan() {
        only("none")
        minutes(niftyKey, LocalTime.of(10, 0)) { 25_000.0 }; minutes(ce, LocalTime.of(10, 0)) { 100.0 }
        at(LocalTime.of(10, 0))
        orphanBuy(Origins.MANUAL)
        assertTrue(runBlocking { OrphanWatch.find() }.isEmpty())
        at(LocalTime.of(10, 1), 30); runBlocking { OrphanWatch.run(context) }
        assertTrue(runBlocking { Protections.active() }.isEmpty())
        assertFalse(PassedArmsHost.holding())
    }

    // ---- the forward bars ----------------------------------------------------------------------------------------------------

    @Test fun aFailedFortyTradeBarSwitchesTheArmOffByItselfOnce() {
        only(DonchianArm.SOURCE)
        val t0 = day.minusDays(3).atTime(10, 0)
        runBlocking { PassedArmsHost.addClosedForTest(List(40) { i ->
            PassedArmsHost.Pos(DonchianArm.SOURCE, "NIFTY", "X$i", "CE", 65, 100.0, t0.plusMinutes(i.toLong()), null, exit = 98.0, exitTime = t0, why = "stop") }) }
        tick(LocalTime.of(9, 0))
        val v = view()
        assertFalse(v.on(DonchianArm.SOURCE))
        assertTrue(v.paused[DonchianArm.SOURCE].orEmpty().contains("after 40 paper trades"))
        // Boss switches it back on: the bar is not judged again.
        runBlocking { PassedArmsHost.setArmed(DonchianArm.SOURCE, true) }
        tick(LocalTime.of(9, 1))
        assertTrue(view().on(DonchianArm.SOURCE))
        assertNull(view().paused[DonchianArm.SOURCE])
    }

    @Test fun soloTwoBreachingItsDrawdownSwitchesItselfOff() {
        only(Solo2.SOURCE)
        val t0 = day.minusDays(3).atTime(12, 0)
        runBlocking { PassedArmsHost.addClosedForTest(listOf(PassedArmsHost.Pos(Solo2.SOURCE, "NIFTY", "Y", "CE", 65, 500.0, t0, null, setup = "S2",
            exit = 50.0, exitTime = t0, why = "stop"))) }
        tick(LocalTime.of(9, 0))
        assertFalse(view().on(Solo2.SOURCE))
        assertTrue(view().paused[Solo2.SOURCE].orEmpty().contains("drawdown"))
    }

    // ---- Solo 2.0 ----------------------------------------------------------------------------------------------------------

    @Test fun soloTwosModelIsReadOnceAndItsCandidateIsScoredOnceAndAtMostOneTradeADay() {
        only(Solo2.SOURCE)
        assertTrue("read at the start-up's refresh", view().modelLoaded)
        market(LocalTime.of(9, 20), firstOpen = 25_100.0)
        tick(LocalTime.of(9, 20))
        val v = PassedArmsHost.view.value
        // S3 (open outside value) and S2 (the break) on NIFTY at 09:20, each scored once.
        assertTrue(v.scores.toString(), v.scores.any { it.startsWith("S3 NIFTY call") } || v.scores.any { it.startsWith("S2 NIFTY call") })
        val bought = v.open.filter { it.arm == Solo2.SOURCE }
        assertTrue(bought.size <= 1)
        if (bought.isNotEmpty()) assertTrue(v.scores.any { "bought" in it })
        // 15 Oct: each decision says why in words (what raised and lowered its score most), from the inputs it scored.
        assertTrue(v.scores.toString(), v.scores.all { " · raised by " in it || " · lowered by " in it })
        tick(LocalTime.of(9, 21))
        assertEquals(v.scores.size, PassedArmsHost.view.value.scores.size)
        assertTrue(PassedArmsHost.view.value.open.count { it.arm == Solo2.SOURCE } <= 1)
        assertNothingAtZerodha()
    }

    @Test fun nothingIsWantedWhenEverythingIsOffAndNothingHeld() {
        only("none")
        assertFalse(PassedArmsHost.wantsPass(10 * 60))
        runBlocking { PassedArmsHost.setArmed(OovArm.SOURCE, true, log = false) }
        assertTrue(PassedArmsHost.wantsPass(10 * 60))
        assertFalse(PassedArmsHost.wantsPass(16 * 60))
        assertTrue(PassedArmsHost.entryMinute(LocalTime.of(9, 20)) && PassedArmsHost.entryMinute(LocalTime.of(12, 3)))
        assertFalse(PassedArmsHost.entryMinute(LocalTime.of(9, 23)) || PassedArmsHost.entryMinute(LocalTime.of(15, 20)))
        assertTrue(runBlocking { PassedArmsHost.setArmed("nope", true) }.contains("not ready"))
    }

    // ---- 19 Oct (Boss: "Resolve the issues"): order speed and the same-direction cap -----------------------------------------------

    /**
     * A 1.5 s vault write (as a Pixel's StrongBox) no longer holds the entry: the snapshot's write, the order's own, the arm's book
     * and the order's label used to run one after another before the next index's entry. The whole pass now takes less than one
     * write, decision to sent is well under a second, and everything is still on disk right after.
     */
    @Test fun aSlowVaultWriteNoLongerHoldsAPassedArmsEntry() {
        only(DonchianArm.SOURCE)
        market(LocalTime.of(9, 20))
        at(LocalTime.of(9, 20))
        OrderTiming.forget(); SecurePrefs.put("speed.day", null)
        Paper.writeInlineForTest = false; PassedArmsHost.writeInlineForTest = false; Strategies.writeInlineForTest = false
        com.optionslab.app.security.Vault.testWriteDelayMs = 1_500L
        val ms: Long
        try {
            val t0 = System.nanoTime()
            runBlocking { kotlinx.coroutines.withContext(OrderTiming.Trigger(System.currentTimeMillis(), null)) { PassedArmsHost.tick() } }
            ms = (System.nanoTime() - t0) / 1_000_000
        } finally {
            com.optionslab.app.security.Vault.testWriteDelayMs = 0L
            assertTrue(Paper.awaitWrites(20_000L) && PassedArmsHost.awaitWrites(20_000L) && Strategies.awaitWrites(20_000L))
            Paper.writeInlineForTest = true; PassedArmsHost.writeInlineForTest = true; Strategies.writeInlineForTest = true
        }
        val p = PassedArmsHost.view.value.open.single()
        assertNull(p.shadow)
        assertTrue("the entry pass took $ms ms with a 1.5 s vault write (three or four writes in a row before)", ms < 1_500L)
        val ds = OrderTiming.paperDecisionToSent()!!
        assertTrue("decision to sent ${ds.first} ms", ds.first < 1_000L)
        // On disk all the same: a restart restores the trade and the paper lot.
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertEquals(p.symbol, view().open.single().symbol)
        Paper.forgetForTest()
        assertEquals(65, Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity })
        assertNothingAtZerodha()
    }

    /** The same-direction cap refuses R51's break: nothing is bought; it is kept as a would-be trade, decided once, run by its own stop. */
    @Test fun aBreakTheSameDirectionCapRefusesIsKeptAsAWouldBeTradeAndNeverBought() {
        ExposureCapHost.resetForTest(paperOn = true); ExposureCapHost.setMax(1)
        only(DonchianArm.SOURCE)
        market(LocalTime.of(9, 20))
        at(LocalTime.of(9, 20))
        // Another arm's automatic call already open (next week's NIFTY call).
        val other = Paper.contractFor("NIFTY", day.plusDays(14), 25_000.0, Right.CE)!!
        assertTrue(runBlocking { Paper.place(other, "BUY", 1, "MARKET", "MIS", null, null, com.optionslab.engine.sandbox.Quote(50.0), auto = "ORB") }.ok)
        tick(LocalTime.of(9, 20))
        val p = PassedArmsHost.view.value.open.single()
        assertEquals(PassedArmsHost.SHADOW_CAP, p.shadow)
        assertNull("never a paper order", p.orderId)
        assertEquals(0, Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity })
        assertEquals(DonchianArm.LABEL, ExposureCapHost.today().single().owner)
        assertTrue(PassedArmsHost.view.value.status.toString(), PassedArmsHost.view.value.status["${DonchianArm.SOURCE}|NIFTY"].orEmpty().contains("same-direction cap"))
        // Decided once: not tried again the next minute.
        market(LocalTime.of(9, 21)); tick(LocalTime.of(9, 21))
        assertEquals(1, PassedArmsHost.view.value.open.size)
        assertEquals(1, ExposureCapHost.today().size)
        // Its own stop closes the would-be trade at the price seen; nothing is sold on the account.
        market(LocalTime.of(9, 25), call = { t -> if (t.isBefore(LocalTime.of(9, 24))) 100.0 else 84.0 })
        at(LocalTime.of(9, 25)); runBlocking { PassedArmsHost.manageOnly() }
        val c = view().closed.single()
        assertEquals("stop", c.why); assertEquals(PassedArmsHost.SHADOW_CAP, c.shadow)
        assertEquals("no paper trade in it", 0, Paper.state.trades.count { it.symbol == p.symbol })
        assertNothingAtZerodha()
    }

    // ---- 19 Oct: R51 record only (Boss: "Yes, keep only breadth donchain") ------------------------------------------------------

    private fun recordOnlyR51() {
        bothDonchians()
        assertEquals("${DonchianArm.LABEL} is record only: would-be trades, nothing bought.",
            runBlocking { PassedArmsHost.setMode(DonchianArm.SOURCE, PassedArmsHost.Mode.RECORD, log = false) })
    }

    private fun buyTitles(label: String) = com.optionslab.app.testing.Background.notifications(context).allNotifications
        .mapNotNull { com.optionslab.app.testing.Background.title(it) }.filter { it.startsWith("$label bought") }

    /**
     * R51 record only, R72 on, four indices breaking together: R51's break is a would-be trade (no paper order, no notice, never in
     * the same-direction cap, here at most 1); R72 on the same bar still buys the same option for real; each closes by its own stop.
     */
    @Test fun aRecordOnlyR51BreakIsAWouldBeTradeAndR72StillBuysTheSameBarForReal() {
        com.optionslab.app.testing.Background.grantNotifications(context)
        ExposureCapHost.resetForTest(paperOn = true); ExposureCapHost.setMax(1)
        recordOnlyR51()
        val v0 = view()
        assertFalse(v0.on(DonchianArm.SOURCE)); assertTrue(v0.recording(DonchianArm.SOURCE))
        assertEquals(PassedArmsHost.Mode.RECORD, v0.mode(DonchianArm.SOURCE)); assertEquals(PassedArmsHost.Mode.ON, v0.mode(BreadthDonchianArm.SOURCE))
        assertEquals("${DonchianArm.LABEL} is already record only (paper only).",
            runBlocking { PassedArmsHost.setMode(DonchianArm.SOURCE, PassedArmsHost.Mode.RECORD, log = false) })
        breadthMarket(up = setOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), until = LocalTime.of(9, 20))
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        val open = PassedArmsHost.view.value.open
        assertEquals(open.toString(), 2, open.size)
        val dn = open.single { it.arm == DonchianArm.SOURCE }
        val br = open.single { it.arm == BreadthDonchianArm.SOURCE }
        assertEquals("R51's is a would-be trade", PassedArmsHost.SHADOW_OFF, dn.shadow)
        assertNull("never a paper order", dn.orderId)
        assertTrue("priced at the ask or the last price", dn.entry >= 100.0)
        assertNull("R72's is a real paper trade", br.shadow)
        assertTrue(br.orderId != null)
        assertEquals(dn.symbol, br.symbol)
        assertEquals("R72's lot only", 65, Paper.state.positions.filter { it.symbol == br.symbol }.sumOf { it.quantity })
        assertEquals(1, Paper.state.orders.count { it.symbol == br.symbol && it.action == "BUY" })
        assertTrue("the would-be trade is never counted by the same-direction cap", ExposureCapHost.today().isEmpty())
        assertTrue("no buy notice for R51", buyTitles(DonchianArm.LABEL).isEmpty())
        assertEquals("R72's buy notice", 1, buyTitles(BreadthDonchianArm.LABEL).size)
        assertTrue(PassedArmsHost.view.value.status.toString(),
            PassedArmsHost.view.value.status["${DonchianArm.SOURCE}|NIFTY"].orEmpty().let { it.startsWith("would-be ") && "switched to record only" in it })
        assertTrue(PassedArmsHost.tradeLine(dn, emptyMap()).contains("would-be: switched to record only"))
        assertEquals("the position popup lists R72's only", listOf(BreadthDonchianArm.LABEL), com.optionslab.ira.ArmExits.of(false, br.symbol).map { it.label })
        // Decided once: the next pass adds nothing.
        market(LocalTime.of(9, 21)); breadthMarket(setOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), LocalTime.of(9, 21)); tick(LocalTime.of(9, 21))
        assertEquals(2, PassedArmsHost.view.value.open.size)
        // 09:24 trades at 84 (under -15%): R72's stop sells its lot; R51's would-be closes by its own stop at the price seen.
        market(LocalTime.of(9, 25), call = { t -> if (t.isBefore(LocalTime.of(9, 24))) 100.0 else 84.0 })
        at(LocalTime.of(9, 25)); runBlocking { PassedArmsHost.manageOnly() }
        val closed = view().closed
        val wb = closed.single { it.arm == DonchianArm.SOURCE }
        assertEquals("stop", wb.why); assertEquals(PassedArmsHost.SHADOW_OFF, wb.shadow); assertEquals(84.0, wb.exit!!, 1e-9)
        assertTrue("estimated charges of a real round trip", wb.charges > 0)
        assertEquals("stop", closed.single { it.arm == BreadthDonchianArm.SOURCE }.why)
        assertEquals(0, Paper.state.positions.filter { it.symbol == br.symbol }.sumOf { it.quantity })
        assertEquals("only R72's lot was ever sold", 1, Paper.state.orders.count { it.symbol == br.symbol && it.action == "SELL" })
        // Counted apart in its record, the diagnostics and Jarvis; read back from its own file.
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        val v = view()
        assertTrue(v.recording(DonchianArm.SOURCE))
        assertEquals(PassedArmsHost.SHADOW_OFF, v.wouldBeOf(DonchianArm.SOURCE).single().shadow)
        assertTrue(v.wouldBeOf(BreadthDonchianArm.SOURCE).isEmpty())
        val d = PassedArmsHost.diagLine()
        assertTrue(d, d.contains("${DonchianArm.LABEL}: record only (would-be trades, nothing bought) · open 0 · 1 closed -"))
        assertTrue(d, d.contains("of them 1 would-be -"))
        val said = PassedArmsHost.facts(v).arms.single { it.source == DonchianArm.SOURCE }
        assertTrue(said.recordOnly && said.on == false); assertEquals(1, said.wouldBe)
        assertTrue(com.optionslab.ira.PassedArmsTalk.armWords(said).startsWith("${DonchianArm.LABEL}: record only (would-be trades, nothing bought)."))
        assertNothingAtZerodha()
    }

    /** Record only is never mirrored live, even with R51's own Trade live on and out of shadow (Live, AI trades on). */
    @Test fun aRecordOnlyBreakIsNeverMirroredLiveEvenWithItsTradeLiveOn() {
        com.optionslab.app.data.ArmLive.testDefaultOff = false
        com.optionslab.app.data.ArmLive.testSync = true
        runBlocking { com.optionslab.app.data.ArmLive.setShadow(DonchianArm.SOURCE, false, pinConfirmed = true, log = false) }
        val sym = "NIFTY26OCT25050CE"
        kite.instruments += FakeKite.Ins(26_000_001, sym, "NIFTY", day.plusDays(7), 25_050.0, "CE", 65)
        kite.quote("NFO:$sym", 100.0, 99.95, 100.05)
        File(context.filesDir, "kite_instruments.json").delete()
        runBlocking { Broker.instruments() }
        kite.requests.clear()
        only(DonchianArm.SOURCE)
        runBlocking { PassedArmsHost.setMode(DonchianArm.SOURCE, PassedArmsHost.Mode.RECORD, log = false) }
        assertTrue("its pass still runs while record only", PassedArmsHost.wantsPass(9 * 60 + 20))
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        assertEquals(PassedArmsHost.SHADOW_OFF, PassedArmsHost.view.value.open.single().shadow)
        runBlocking { com.optionslab.app.data.ArmLive.watch() }
        assertTrue("nothing sent to Zerodha", kite.placed.isEmpty())
        val lv = com.optionslab.app.data.ArmLive.view.value
        assertTrue(lv.open.none { it.key == DonchianArm.SOURCE } && lv.openShadow.none { it.key == DonchianArm.SOURCE })
        market(LocalTime.of(9, 25), call = { t -> if (t.isBefore(LocalTime.of(9, 24))) 100.0 else 84.0 })
        at(LocalTime.of(9, 25)); runBlocking { PassedArmsHost.manageOnly() }
        runBlocking { com.optionslab.app.data.ArmLive.watch() }
        assertEquals("stop", view().closed.single().why)
        assertTrue(kite.placed.isEmpty())
        assertTrue(com.optionslab.app.data.ArmLive.view.value.shadowClosed.none { it.key == DonchianArm.SOURCE })
        assertNothingAtZerodha()
    }

    /**
     * The 19 Oct update sets R51 to record only once (Boss's choice), R72 and the others untouched; a restart never does it again;
     * an older book (no record-only field) reads as before; the change log's entry is Boss's ask and one tap puts R51 back on.
     */
    @Test fun the19OctUpdateSetsR51ToRecordOnlyOnceAndOneTapPutsItBackOn() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val settings = AppSettings.load()
        PassedArmsHost.testNoAutoOn = false
        PassedArmsHost.wipe(); bookFile.delete()
        val v = view()
        assertTrue(v.recording(DonchianArm.SOURCE)); assertFalse(v.on(DonchianArm.SOURCE))
        for (s in listOf(BreadthDonchianArm.SOURCE, OovArm.SOURCE, Solo2.SOURCE)) assertTrue(s, v.on(s))
        val line = v.log.single { "set to record only by the 19 Oct update" in it }
        assertTrue(line, "Yes, keep only breadth donchain" in line && "${BreadthDonchianArm.LABEL} stays on" in line)
        assertEquals("no setting, guard or day lock touched", settings, AppSettings.load())
        // Once: a restart never sets it again; Boss puts it on, and it stays on.
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertTrue(view().recording(DonchianArm.SOURCE))
        assertTrue(runBlocking { PassedArmsHost.setArmed(DonchianArm.SOURCE, true, log = false) }.contains("paper only"))
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertTrue(view().on(DonchianArm.SOURCE)); assertFalse(view().recording(DonchianArm.SOURCE))
        assertEquals(1, view().log.count { "set to record only by the 19 Oct update" in it })
        // The change log's Undo through Autopilot's own setter: record only -> on -> record only (paper only, no PIN).
        val rec = AutoChange.passedArmRecordOnly(DonchianArm.SOURCE, DonchianArm.LABEL, AutoChange.ON, AutoChange.CHOICE_KEEP_BREADTH)
        assertTrue(Autopilot.apply(rec, false)!!.contains("record only"))
        assertTrue(view().recording(DonchianArm.SOURCE))
        assertTrue(Autopilot.apply(AutoChange.inverse(rec, "undone by you"), false)!!.contains("paper only"))
        assertTrue(view().on(DonchianArm.SOURCE))
        assertNull(Autopilot.apply(rec.copy(to = "sideways"), false))
        // The update's own entry: Boss's ask, kept, once; its Undo puts R51 back on.
        org.junit.Assume.assumeTrue("the change log is in IraAlgo with Jarvis only", Autopilot.enabled())
        PassedArmsHost.wipe(); bookFile.delete(); view()
        fun entries() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.PAPER_ARM_ON &&
            it.change.target == "paperarm:${DonchianArm.SOURCE}" && it.change.to == AutoChange.RECORD_ONLY }
        val deadline = System.currentTimeMillis() + 10_000
        while (entries().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val e = entries().single()
        assertEquals(AutoChange.Status.KEPT, e.status)
        assertEquals("your choice", e.by)
        assertTrue(e.change.reason, e.change.reason.contains("Yes, keep only breadth donchain"))
        assertTrue(e.note, e.note.contains("${BreadthDonchianArm.LABEL} stays on"))
        assertFalse("no PIN: paper only", Autopilot.undoNeedsPin(e.id))
        AutomationSupport.reloadFromDisk(PassedArmsHost); view(); Thread.sleep(200)
        assertEquals("logged once", 1, entries().size)
        assertTrue(Autopilot.undo(e.id).startsWith("Undone"))
        assertTrue(view().on(DonchianArm.SOURCE)); assertFalse(view().recording(DonchianArm.SOURCE))
        assertTrue("R72 untouched", view().on(BreadthDonchianArm.SOURCE))
    }

    /** A book from before 19 Oct (no record-only field): read as it was; then the update sets R51 to record only, once. */
    @Test fun anOlderBookIsReadAsBeforeAndRecordOnlyNeverSitsBesideOn() {
        val done = listOf(PassedArmsHost.AUTO_ON, PassedArmsHost.LOCK_ON, PassedArmsHost.L25_ON, PassedArmsHost.BREADTH_ON, PassedArmsHost.ST_ON)
        fun old(extra: org.json.JSONObject.() -> Unit = {}) {
            PassedArmsHost.wipe()
            com.optionslab.app.security.Vault.writeFile(bookFile, org.json.JSONObject()
                .put("armed", org.json.JSONObject().put(DonchianArm.SOURCE, true).put(BreadthDonchianArm.SOURCE, true).put(OovArm.SOURCE, false))
                .put("migrated", org.json.JSONArray(done)).put("lock306100", true).apply(extra).toString().toByteArray())
            AutomationSupport.reloadFromDisk(PassedArmsHost)
        }
        // With the tests' seam (the update does not act): R51 on, nothing record only.
        old()
        view().let { v -> assertTrue(v.on(DonchianArm.SOURCE)); assertTrue(v.recordOnly.isEmpty()); assertTrue(v.on(BreadthDonchianArm.SOURCE)); assertTrue(v.lockOn) }
        // Record only beside on in a book: on wins (the two never both); an unknown arm is dropped.
        old { put("recordOnly", org.json.JSONArray(listOf(DonchianArm.SOURCE, "nope", OovArm.SOURCE))) }
        view().let { v -> assertTrue(v.on(DonchianArm.SOURCE)); assertEquals(setOf(OovArm.SOURCE), v.recordOnly); assertTrue(v.recording(OovArm.SOURCE)) }
        // The update itself (IraAlgo): R51 on in the older book -> record only, once; R72 untouched.
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        PassedArmsHost.testNoAutoOn = false
        old()
        view().let { v -> assertTrue(v.recording(DonchianArm.SOURCE)); assertTrue(v.on(BreadthDonchianArm.SOURCE)) }
        assertTrue(PassedArmsHost.R51_RECORD in String(com.optionslab.app.security.Vault.readFileSteady(bookFile)!!, Charsets.UTF_8))
        // R51 already off in an older book: left off.
        PassedArmsHost.wipe()
        com.optionslab.app.security.Vault.writeFile(bookFile, org.json.JSONObject().put("armed", org.json.JSONObject().put(DonchianArm.SOURCE, false))
            .put("migrated", org.json.JSONArray(done)).toString().toByteArray())
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        view().let { v -> assertFalse(v.on(DonchianArm.SOURCE)); assertFalse(v.recording(DonchianArm.SOURCE)) }
    }

    // ---- R72 Breadth Donchian (16 Oct, Boss: "Okay add it") --------------------------------------------------------------------

    private val indexKeys = mapOf("NIFTY" to niftyKey, "BANKNIFTY" to "NSE_INDEX|Nifty Bank", "FINNIFTY" to "NSE_INDEX|Nifty Fin Service",
        "MIDCPNIFTY" to "NSE_INDEX|NIFTY MID SELECT", "SENSEX" to "BSE_INDEX|SENSEX")
    private val bases = mapOf("NIFTY" to 25_000.0, "BANKNIFTY" to 56_000.0, "FINNIFTY" to 26_500.0, "MIDCPNIFTY" to 13_000.0, "SENSEX" to 82_000.0)

    /**
     * The four other indices (NIFTY is [market]'s, breaking up at 09:19): each flat at its base yesterday (high +1, low -1) and
     * today until 09:19; from 09:19 those in [up] +0.4% (a close above the channel on the 09:15 bar), the rest flat. [off]: no
     * bar today at all (its feed down). Only NIFTY's options are listed, so only NIFTY can be bought.
     */
    private fun breadthMarket(up: Set<String>, until: LocalTime, off: Set<String> = emptySet()) {
        PassedArmsHost.testHistory = { u -> bases[u]?.let { b -> (0 until 375).map { Bar(day.minusDays(1).atTime(9, 15).plusMinutes(it.toLong()), b, b + 1, b - 1, b) } }.orEmpty() }
        runBlocking { PassedArmsHost.prefetchNow(day) }
        for (u in indexKeys.keys - "NIFTY") {
            if (u in off) { upstox.minutes.remove(indexKeys.getValue(u)); continue }
            val b = bases.getValue(u)
            minutes(indexKeys.getValue(u), until) { t -> if (t.isBefore(LocalTime.of(9, 19)) || u !in up) b else b * 1.004 }
        }
    }

    private fun bothDonchians() {
        only(BreadthDonchianArm.SOURCE)
        runBlocking { PassedArmsHost.setArmed(DonchianArm.SOURCE, true, log = false) }
    }

    @Test fun theUpdateSwitchesBreadthDonchianOnOnceBesideDonchianWithItsRiskAndNeverAgain() {
        PassedArmsHost.testNoAutoOn = false
        PassedArmsHost.wipe(); bookFile.delete()
        val v = view()
        // Never in IraGoldAlgo (its passes never run there either); in IraAlgo on at once, on paper, beside Donchian.
        assertEquals(!com.optionslab.app.BuildConfig.GOLD, v.on(BreadthDonchianArm.SOURCE))
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        // 19 Oct (Boss: "Yes, keep only breadth donchain"): R51 beside it is record only now (its breaks kept as would-be trades).
        assertTrue(v.recording(DonchianArm.SOURCE))
        val line = v.log.single { "switched on once by the 16 Oct update" in it }
        for (w in listOf(BreadthDonchianArm.LABEL, PassedArms.PASSED, BreadthDonchianArm.RESEARCH_ID, "Okay add it", "beside ${DonchianArm.LABEL}", BreadthDonchianArm.RISK))
            assertTrue(w, w in line)
        assertEquals(day, v.breadthSince)
        assertTrue(PassedArmsHost.diagLine().contains("${BreadthDonchianArm.LABEL}: on (paper only)"))
        assertTrue(PassedArmsHost.diagLine().contains("Breadth Donchian vs Donchian on paper since"))
        // Boss switches it off: a restart never switches it back on.
        assertEquals("${BreadthDonchianArm.LABEL} off.", runBlocking { PassedArmsHost.setArmed(BreadthDonchianArm.SOURCE, false) })
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertFalse(view().on(BreadthDonchianArm.SOURCE))
        assertTrue(view().recording(DonchianArm.SOURCE))
        assertEquals(1, view().log.count { "switched on once by the 16 Oct update" in it })
        assertEquals("the switch-on day is kept", day, view().breadthSince)
        // A book where the update ran without switching it on (here the tests' own seam) never switches it later; nor an unreadable one.
        PassedArmsHost.wipe(); bookFile.delete(); PassedArmsHost.testNoAutoOn = true; view()
        PassedArmsHost.testNoAutoOn = false; PassedArmsHost.wipe()
        assertFalse("marked done once", view().on(BreadthDonchianArm.SOURCE))
        PassedArmsHost.wipe(); bookFile.writeText("not a vault"); AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertFalse(view().on(BreadthDonchianArm.SOURCE))
    }

    @Test fun breadthDonchiansSwitchOnIsInTheChangeLogOnceAndOneTapSwitchesItOff() {
        // The change log's Undo through Autopilot's own setter (every build).
        bothDonchians()
        val on = AutoChange.passedArmOn(BreadthDonchianArm.SOURCE, BreadthDonchianArm.LABEL, BreadthDonchianArm.RESEARCH_ID, "your choice")
        assertEquals("${BreadthDonchianArm.LABEL} off.", Autopilot.apply(AutoChange.inverse(on, "undone by you"), false))
        assertFalse(view().on(BreadthDonchianArm.SOURCE))
        assertTrue("Donchian is never touched", view().on(DonchianArm.SOURCE))
        assertTrue(Autopilot.apply(on, false)!!.contains("paper only"))
        assertTrue(view().on(BreadthDonchianArm.SOURCE))
        // The update's own entry: Boss's, kept, once; its Undo turns it off (paper only, no PIN).
        org.junit.Assume.assumeTrue("the change log is in IraAlgo with Jarvis only", Autopilot.enabled())
        PassedArmsHost.testNoAutoOn = false
        PassedArmsHost.wipe(); bookFile.delete(); view()
        fun entries() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.PAPER_ARM_ON && it.change.target == "paperarm:${BreadthDonchianArm.SOURCE}" }
        val deadline = System.currentTimeMillis() + 10_000
        while (entries().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val e = entries().single()
        assertEquals(AutoChange.Status.KEPT, e.status)
        assertEquals("your choice", e.by)
        assertTrue(e.change.reason, e.change.reason.contains("Okay add it"))
        assertTrue(e.note, e.note.contains(BreadthDonchianArm.RISK))
        assertFalse("no PIN: paper only", Autopilot.undoNeedsPin(e.id))
        AutomationSupport.reloadFromDisk(PassedArmsHost); view(); Thread.sleep(200)
        assertEquals(1, entries().size)
        assertTrue(Autopilot.undo(e.id).startsWith("Undone"))
        assertFalse(view().on(BreadthDonchianArm.SOURCE))
    }

    /**
     * 19 Oct (Boss: no duplicate Donchian contracts): four indices breaking together - R51 buys the 1-ITM call; R72 does NOT buy the
     * same option again: its trade is a would-be one (nothing on the account), run by its own exits, so its record and the
     * R72-vs-R51 comparison still count it.
     */
    @Test fun fourIndicesBreakingTogetherBuyDonchiansCallOnceAndKeepBreadthsAsAWouldBeTrade() {
        bothDonchians()
        runBlocking { PassedArmsHost.setLock(true); PassedArmsHost.setL25(true) }
        breadthMarket(up = setOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), until = LocalTime.of(9, 20))
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        val open = PassedArmsHost.view.value.open
        assertEquals(open.toString(), 2, open.size)
        val br = open.single { it.arm == BreadthDonchianArm.SOURCE }
        val dn = open.single { it.arm == DonchianArm.SOURCE }
        assertNull("R51's is a real paper trade", dn.shadow)
        assertEquals("R72's is a would-be trade", PassedArmsHost.SHADOW_TWIN, br.shadow)
        assertNull("never a paper order", br.orderId)
        // The same 1-ITM call, one lot on the account only; breadth's with Donchian's exits plus the lock and L25 (the book's switches).
        assertEquals(dn.symbol, br.symbol); assertTrue(br.symbol, br.symbol.contains("25050")); assertEquals("CE", br.right); assertEquals(65, br.qty)
        assertTrue(br.lock && br.l25)
        assertNull(br.breadth); assertEquals("Donchian's break labelled as confirmed", true, dn.breadth)
        assertEquals("one lot, never two", 65, Paper.state.positions.filter { it.symbol == br.symbol }.sumOf { it.quantity })
        assertEquals(1, Paper.state.orders.count { it.symbol == br.symbol && it.action == "BUY" })
        val x = PassedArmsHost.exitsOf(br, null)
        assertEquals(br.entry * 0.85, x.stop!!, 1e-9); assertNull(x.target); assertEquals("+5% locked once +25%", x.note)
        assertTrue(PassedArmsHost.tradeLine(br, emptyMap()).contains("would-be"))
        assertTrue(PassedArmsHost.view.value.status["${BreadthDonchianArm.SOURCE}|NIFTY"].orEmpty().startsWith("would-be "))
        assertNothingAtZerodha()
        // The bar is decided once: the next pass buys nothing more.
        market(LocalTime.of(9, 21)); breadthMarket(setOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), LocalTime.of(9, 21)); tick(LocalTime.of(9, 21))
        assertEquals(2, PassedArmsHost.view.value.open.size)
        assertEquals(65, Paper.state.positions.filter { it.symbol == br.symbol }.sumOf { it.quantity })
        // 09:24 trades at 84 (under -15%): R51's stop sells its lot; R72's would-be trade closes on its own stop at the price seen.
        market(LocalTime.of(9, 25), call = { t -> if (t.isBefore(LocalTime.of(9, 24))) 100.0 else 84.0 })
        at(LocalTime.of(9, 25)); runBlocking { PassedArmsHost.manageOnly() }
        val closed = view().closed
        assertEquals(setOf(DonchianArm.SOURCE, BreadthDonchianArm.SOURCE), closed.map { it.arm }.toSet())
        assertTrue(closed.toString(), closed.all { it.why == "stop" && it.qty == 65 })
        val wb = closed.single { it.arm == BreadthDonchianArm.SOURCE }
        assertEquals(84.0, wb.exit!!, 1e-9)
        assertTrue("estimated charges of a real round trip", wb.charges > 0)
        assertEquals(0, Paper.state.positions.filter { it.symbol == br.symbol }.sumOf { it.quantity })
        // Its own record, from its own rows (read back from its file); the comparison for Boss still counts both.
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertEquals(PassedArmsHost.SHADOW_TWIN, view().closedOf(BreadthDonchianArm.SOURCE).single().shadow)
        assertTrue(view().forwardLine(BreadthDonchianArm.SOURCE).startsWith("Forward test: 1 of 40"))
        val cmp = view().comparison()
        assertTrue(cmp, cmp.contains("R72 Breadth Donchian 1 trade, −₹") && cmp.contains("R51 Donchian 20 1 trade, −₹") &&
            cmp.contains("Donchian's trades breadth did not confirm: 0 trades, +₹0"))
        assertNothingAtZerodha()
    }

    /**
     * 19 Oct review (CLAUDE.md's LIVE rule): while R72's own entry could also go live (Live, AI trades on, its "Trade live" on and
     * out of shadow), the no-twin rule steps aside - keeping R72 off the option would skip its live trade too - so both buy, as
     * before. An arm still in shadow (R51 here) is never live-mirrored, so the rule (and the cap) apply to it.
     */
    @Test fun whileBreadthCanGoLiveItsTwinIsBoughtAsBeforeSoItsLiveTradeIsNeverSkipped() {
        com.optionslab.app.data.ArmLive.testDefaultOff = false
        runBlocking {
            com.optionslab.app.data.ArmLive.setLive(BreadthDonchianArm.SOURCE, true, pinConfirmed = true, log = false)
            com.optionslab.app.data.ArmLive.setShadow(BreadthDonchianArm.SOURCE, false, pinConfirmed = true, log = false)
        }
        runBlocking { com.optionslab.app.data.ArmLive.refresh() }
        com.optionslab.app.data.ArmLive.liveFlags()
        assertTrue(ExposureCapHost.liveMirrorPossible(BreadthDonchianArm.SOURCE))
        assertFalse("R51 is still in shadow", ExposureCapHost.liveMirrorPossible(DonchianArm.SOURCE))
        bothDonchians()
        breadthMarket(up = setOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), until = LocalTime.of(9, 20))
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        val open = PassedArmsHost.view.value.open
        assertEquals(open.toString(), 2, open.size)
        assertTrue("both real paper trades", open.all { it.shadow == null && it.orderId != null })
        assertEquals(130, Paper.state.positions.filter { it.symbol == open.first().symbol }.sumOf { it.quantity })
    }

    /**
     * A book carried over from before 19 Oct: R51's trade (bought by the arm at 09:20) and R72's own lot of the very same option
     * (each with its own paper lot), as the arms bought them then. Today the arms never buy one option twice (the test above).
     */
    private fun legacyShared(): List<PassedArmsHost.Pos> {
        only(DonchianArm.SOURCE)
        runBlocking { PassedArmsHost.setArmed(BreadthDonchianArm.SOURCE, true, log = false) }
        breadthMarket(up = setOf("BANKNIFTY"), until = LocalTime.of(9, 20))
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        val dn = PassedArmsHost.view.value.open.single()
        assertEquals(DonchianArm.SOURCE, dn.arm)
        val c = Paper.contractOf(dn.symbol)!!
        val r = runBlocking { Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null) }
        assertTrue(r.message, r.ok)
        val fill = Paper.state.trades.last { it.orderId == r.orderId }
        runBlocking { PassedArmsHost.addOpenForTest(PassedArmsHost.Pos(BreadthDonchianArm.SOURCE, "NIFTY", dn.symbol, "CE", 65, fill.price.toDouble(),
            dn.entryTime, r.orderId, entryRef = 100.0, charges = fill.charges.toDouble())) }
        val open = PassedArmsHost.view.value.open
        assertEquals(listOf(DonchianArm.SOURCE, BreadthDonchianArm.SOURCE), open.map { it.arm })
        assertEquals(130, Paper.state.positions.filter { it.symbol == dn.symbol }.sumOf { it.quantity })
        return open
    }

    /** Both arms' lots of the same option sold elsewhere in one sale (a square-off): each closes, each with only its share of its charges. */
    @Test fun aSharedOptionSoldElsewhereInOneSaleClosesBothWithTheirShareOfItsCharges() {
        val open = legacyShared()
        assertEquals(2, open.size)
        val c = Paper.contractOf(open.first().symbol)!!
        market(LocalTime.of(9, 22)); at(LocalTime.of(9, 22))
        val r = runBlocking { Paper.place(c, "SELL", 2, "MARKET", "MIS", null, null) }
        assertTrue(r.message, r.ok)
        val sale = Paper.state.trades.last { it.symbol == c.symbol && it.action == "SELL" }
        assertEquals(130, sale.quantity)
        runBlocking { PassedArmsHost.manageOnly() }
        val closed = view().closed
        assertEquals(2, closed.size)
        for (x in closed) {
            assertEquals("closed_elsewhere", x.why)
            val entryCharges = open.single { it.arm == x.arm }.charges
            assertEquals(entryCharges + sale.charges.toDouble() / 2, x.charges, 1e-6)
        }
        assertNothingAtZerodha()
    }

    /** Both arms in the same option; one lot sold elsewhere at [t] (at the call's price then): that sale, as a paper trade. */
    private fun sharedThenSold(t: LocalTime, call: Double = 100.0): Pair<List<PassedArmsHost.Pos>, com.optionslab.engine.sandbox.Trade> {
        val open = PassedArmsHost.view.value.open.takeIf { it.size == 2 } ?: legacyShared()
        assertEquals(2, open.size)
        val c = Paper.contractOf(open.first().symbol)!!
        market(t.plusMinutes(1), call = { call }); at(t)
        val r = runBlocking { Paper.place(c, "SELL", 1, "MARKET", "MIS", null, null) }
        assertTrue(r.message, r.ok)
        val sale = Paper.state.trades.last { it.symbol == c.symbol && it.action == "SELL" }
        assertEquals(65, sale.quantity)
        return open to sale
    }

    /**
     * 16 Oct (R72 review): one lot of the two sold elsewhere is noticed at once and closes the OLDEST trade (entry, then book
     * order) at that sale, with all its charges; the other's own stop later sells the last lot and is never taken for a sale
     * elsewhere - each sale's charges booked once.
     */
    @Test fun oneLotOfASharedOptionSoldElsewhereClosesTheOldestTradeAndTheOthersOwnExitIsItsOwn() {
        val (open, sale) = sharedThenSold(LocalTime.of(9, 22))
        runBlocking { PassedArmsHost.manageOnly() }
        val gone = view().closed.single()
        assertEquals(open.first().arm, gone.arm)
        assertEquals("closed_elsewhere", gone.why)
        assertEquals(sale.price.toDouble(), gone.exit!!, 1e-9)
        assertEquals(open.first().charges + sale.charges.toDouble(), gone.charges, 1e-6)
        assertEquals("${sale.orderId}:65", gone.soldBy)
        assertEquals(open.last().arm, view().open.single().arm)
        // 09:24 trades at 84 (under -15%): the other's own stop sells the last lot.
        market(LocalTime.of(9, 25), call = { t -> if (t.isBefore(LocalTime.of(9, 24))) 100.0 else 84.0 })
        at(LocalTime.of(9, 25)); runBlocking { PassedArmsHost.manageOnly() }
        val closed = view().closed
        assertEquals(2, closed.size)
        val own = closed.single { it.arm == open.last().arm }
        assertEquals("stop", own.why)
        val exit = Paper.state.trades.last { it.symbol == sale.symbol && it.action == "SELL" }
        assertTrue(exit.orderId != sale.orderId)
        assertEquals(open.last().charges + exit.charges.toDouble(), own.charges, 1e-6)
        assertEquals("the first is never re-booked", gone, closed.single { it.arm == open.first().arm })
        assertEquals(0, Paper.state.positions.filter { it.symbol == sale.symbol }.sumOf { it.quantity })
        // Kept in the book: read back, the next look books nothing more.
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        at(LocalTime.of(9, 26)); runBlocking { PassedArmsHost.manageOnly() }
        assertEquals(closed.toSet(), view().closed.toSet())
        assertNothingAtZerodha()
    }

    /** 16 Oct (R72 review): two separate sales elsewhere, one lot each: each trade books its own sale's price and charges. */
    @Test fun twoSalesElsewhereOfASharedOptionEachBookTheirOwnPriceAndCharges() {
        val (open, first) = sharedThenSold(LocalTime.of(9, 22), call = 100.0)
        // Not looked at in between (the app was asleep): the second sale, at another price, before the next look.
        val (_, second) = sharedThenSold(LocalTime.of(9, 23), call = 120.0)
        assertTrue(first.orderId != second.orderId)
        runBlocking { PassedArmsHost.manageOnly() }
        val closed = view().closed
        assertEquals(2, closed.size)
        assertTrue(closed.all { it.why == "closed_elsewhere" })
        val a = closed.single { it.arm == open.first().arm }
        val b = closed.single { it.arm == open.last().arm }
        assertEquals(first.price.toDouble(), a.exit!!, 1e-9); assertEquals(open.first().charges + first.charges.toDouble(), a.charges, 1e-6)
        assertEquals(second.price.toDouble(), b.exit!!, 1e-9); assertEquals(open.last().charges + second.charges.toDouble(), b.charges, 1e-6)
        // Each booked to its own sale (so the first sale is never lost, nor the second counted twice).
        assertEquals("${first.orderId}:65", a.soldBy); assertEquals("${second.orderId}:65", b.soldBy)
        assertNothingAtZerodha()
    }

    /** 16 Oct (R72 review): Boss switching R72 back on from its row is logged with his "Okay add it", never the 15 Oct "Both". */
    @Test fun bossSwitchingBreadthBackOnFromItsRowIsLoggedAsHisOkayAddIt() {
        org.junit.Assume.assumeTrue("the change log is in IraAlgo with Jarvis only", Autopilot.enabled())
        bothDonchians()
        assertEquals("${BreadthDonchianArm.LABEL} off.", runBlocking { PassedArmsHost.setArmed(BreadthDonchianArm.SOURCE, false) })
        assertTrue(runBlocking { PassedArmsHost.setArmed(BreadthDonchianArm.SOURCE, true) }.contains("paper only"))
        fun kept() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.PAPER_ARM_ON &&
            it.change.target == "paperarm:${BreadthDonchianArm.SOURCE}" && it.status == AutoChange.Status.KEPT }
        val deadline = System.currentTimeMillis() + 10_000
        while (kept().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val e = kept().single()
        assertEquals("your choice", e.by)
        assertTrue(e.change.reason, e.change.reason.startsWith("your \"Okay add it\" (16 Oct, after R72): "))
        assertFalse(e.change.reason, "Both" in e.change.reason)
    }

    @Test fun threeIndicesBreakingIsNoBreadthTradeButDonchiansAndAFeedDownCountsAsNotBreaking() {
        bothDonchians()
        // NIFTY, BANKNIFTY and FINNIFTY break: Donchian buys NIFTY's call, breadth nothing.
        breadthMarket(up = setOf("BANKNIFTY", "FINNIFTY"), until = LocalTime.of(9, 20))
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        val p = PassedArmsHost.view.value.open.single()
        assertEquals(DonchianArm.SOURCE, p.arm)
        assertEquals("labelled: breadth did not confirm it", false, p.breadth)
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertEquals("the label is kept in the book", false, view().open.single().breadth)
    }

    @Test fun withSensexsFeedDownTheOtherFourBreakingStillBuy() {
        only(BreadthDonchianArm.SOURCE)
        breadthMarket(up = setOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), until = LocalTime.of(9, 20), off = setOf("SENSEX"))
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        assertEquals(BreadthDonchianArm.SOURCE, PassedArmsHost.view.value.open.single().arm)
        assertNothingAtZerodha()
    }

    @Test fun withSensexsFeedDownThreeBreakingBuyNothing() {
        only(BreadthDonchianArm.SOURCE)
        breadthMarket(up = setOf("BANKNIFTY", "FINNIFTY"), until = LocalTime.of(9, 20), off = setOf("SENSEX"))
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        assertTrue(PassedArmsHost.view.value.open.isEmpty())
        assertTrue(Paper.state.orders.isEmpty())
    }

    @Test fun aBreadthTradeThatWasPlus25AndCameBackSellsAtAboutPlus5() {
        only(BreadthDonchianArm.SOURCE)
        runBlocking { PassedArmsHost.setLock(true); PassedArmsHost.setL25(true) }
        breadthMarket(up = setOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"), until = LocalTime.of(9, 20))
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        val p = PassedArmsHost.view.value.open.single()
        assertEquals(BreadthDonchianArm.SOURCE, p.arm); assertTrue(p.lock && p.l25)
        val peak = p.entry * 1.26
        ride(LocalTime.of(9, 33), peak, peak)
        at(LocalTime.of(9, 33)); runBlocking { PassedArmsHost.manageOnly() }
        assertEquals(1, view().open.size)
        assertTrue(com.optionslab.ira.ArmExits.line(false, p.symbol)!!.contains("lock held"))
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        ride(LocalTime.of(9, 35), peak, p.entry * 1.055)
        at(LocalTime.of(9, 35)); runBlocking { PassedArmsHost.manageOnly() }
        val sold = view().closed.single()
        assertEquals("lock", sold.why)
        assertTrue("${sold.exit} vs ${p.entry}", sold.exit!! in p.entry * 1.02..p.entry * 1.07)
        assertNothingAtZerodha()
    }

    @Test fun aRestartRestoresBreadthsOpenTradeFirstAndItsStopStillSells() {
        only(BreadthDonchianArm.SOURCE)
        breadthMarket(up = setOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), until = LocalTime.of(9, 20))
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        assertEquals(BreadthDonchianArm.SOURCE, PassedArmsHost.view.value.open.single().arm)
        // The app is killed: the start-up's positions-first step reads the book before anything else and finds it open.
        PassedArmsHost.wipe()
        assertFalse(PassedArmsHost.holding())
        assertTrue(runBlocking { PositionsFirst.restore() })
        assertTrue(PassedArmsHost.holding())
        assertTrue("watched at full cadence, whatever the time", PassedArmsHost.wantsPass(16 * 60))
        market(LocalTime.of(9, 27), call = { t -> if (t.isBefore(LocalTime.of(9, 25))) 100.0 else 80.0 })
        at(LocalTime.of(9, 27)); runBlocking { PassedArmsHost.manageOnly() }
        assertEquals("stop", PassedArmsHost.view.value.closed.single().why)
        assertFalse(PassedArmsHost.holding())
    }

    @Test fun breadthsFailedFortyTradeBarSwitchesItOffByItselfAndLeavesDonchianOn() {
        bothDonchians()
        val t0 = day.minusDays(3).atTime(10, 0)
        runBlocking { PassedArmsHost.addClosedForTest(List(40) { i ->
            PassedArmsHost.Pos(BreadthDonchianArm.SOURCE, "NIFTY", "B$i", "CE", 65, 100.0, t0.plusMinutes(i.toLong()), null, exit = 98.0, exitTime = t0, why = "stop") }) }
        tick(LocalTime.of(9, 0))
        val v = view()
        assertFalse(v.on(BreadthDonchianArm.SOURCE))
        assertTrue(v.paused[BreadthDonchianArm.SOURCE].orEmpty().contains("after 40 paper trades"))
        assertTrue(v.log.any { "${BreadthDonchianArm.LABEL} switched itself off on its forward-test bar" in it })
        assertTrue(v.on(DonchianArm.SOURCE))
    }

    // ---- R73 T7: R51 Donchian 20 takes a break only when Supertrend (10, 3) agrees (16 Oct, Boss) ------------------------------

    /** NIFTY yesterday: falling from 30,000 to 25,000 by 13:35, then flat at 25,000: Supertrend (10, 3) is down at the close. */
    private fun fallingYesterday(): List<Bar> {
        val d = day.minusDays(1)
        return (0 until 375).map { i -> val c = if (i < 260) 30_000.0 - 5_000.0 * i / 259 else 25_000.0
            Bar(d.atTime(9, 15).plusMinutes(i.toLong()), c, c + 1, c - 1, c) }
    }

    private val ce24950 = "NSE_FO|PA24950CE"

    /** Today's history read again from [h] (the day's earlier read dropped first). */
    private fun historyNow(h: (String) -> List<Bar>) {
        PassedArmsHost.testHistory = h
        PassedArmsHost.wipe()
        runBlocking { PassedArmsHost.prefetchNow(day) }
    }

    /**
     * After [fallingYesterday]: a 25,010 break at 09:19 (above the last 20 bars' 25,001 high; Supertrend stays down - it would
     * flip only above about 25,031), with the 24,950 CE listed too (the 1-ITM call of 25,010), priced at 100.
     */
    private fun fallingMarket(until: LocalTime) {
        val exp = day.plusDays(7)
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("NIFTY", exp, 25_050.0, Right.CE, 65, ce, "NIFTY-PA-25050CE"),
            Upstox.Contract("NIFTY", exp, 24_950.0, Right.CE, 65, ce24950, "NIFTY-PA-24950CE"),
            Upstox.Contract("NIFTY", exp, 25_150.0, Right.PE, 65, pe, "NIFTY-PA-25150PE"),
            Upstox.Contract("NIFTY", exp.plusDays(7), 25_000.0, Right.CE, 65, "NSE_FO|PANEXT", "NIFTY-PA-NEXT")))
        market(until, open = 25_010.0)
        minutes(ce24950, until) { 100.0 }
    }

    @Test fun theUpdateSwitchesTheSupertrendFilterOnOnceAndItsOwnUndoSwitchesItOff() {
        PassedArmsHost.testNoAutoOn = false
        PassedArmsHost.wipe(); bookFile.delete()
        assertTrue(view().stOn)
        assertEquals(1, view().log.count { "Supertrend filter turned on by the 16 Oct update" in it })
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertTrue(view().stOn)
        assertEquals(1, view().log.count { "Supertrend filter turned on" in it })
        assertTrue(PassedArmsHost.diagLine().contains("Supertrend filter (R73 T7) on for new breaks"))
        // Its own Undo: back to the plain rules (the lock and L25 untouched); paper only, no PIN; it stays undone after a restart.
        val on = AutoChange.passedSupertrend(true)
        assertNull(Autopilot.apply(on.copy(target = "filter:other"), false))
        assertTrue(Autopilot.apply(AutoChange.inverse(on, "undone by you"), false)!!.contains("plain rules"))
        assertFalse(view().stOn); assertTrue(view().lockOn); assertTrue(view().l25On)
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertFalse("its Undo stays undone after a restart", view().stOn)
        assertTrue(PassedArmsHost.diagLine().contains("Supertrend filter (R73 T7) off for new breaks"))
        assertTrue(Autopilot.apply(on, false)!!.contains("Supertrend (10, 3) agrees"))
        assertTrue(view().stOn)
        assertEquals("${DonchianArm.LABEL}'s Supertrend filter is already on (paper only).", runBlocking { PassedArmsHost.setSupertrend(true) })
        // A book that cannot be read is never switched on by itself.
        PassedArmsHost.wipe(); bookFile.writeText("not a vault"); AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertFalse(view().stOn)
    }

    @Test fun withTheFilterOnABreakSupertrendAgreesWithIsBoughtAndLabelled() {
        only(DonchianArm.SOURCE)
        runBlocking { PassedArmsHost.setSupertrend(true) }
        market(LocalTime.of(9, 20))
        tick(LocalTime.of(9, 20))
        val p = PassedArmsHost.view.value.open.single()
        assertEquals("CE", p.right); assertTrue(p.st)
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertTrue("the label is kept on disk", view().open.single().st)
        assertNothingAtZerodha()
    }

    @Test fun withTheFilterOnABreakSupertrendIsAgainstIsSkipped() {
        historyNow { u -> if (u == "NIFTY") fallingYesterday() else emptyList() }
        only(DonchianArm.SOURCE)
        runBlocking { PassedArmsHost.setSupertrend(true) }
        fallingMarket(LocalTime.of(9, 20))
        tick(LocalTime.of(9, 20))
        assertTrue("nothing bought", PassedArmsHost.view.value.open.isEmpty())
        assertTrue(view().log.any { "break skipped: Supertrend (10, 3) is against it (R73 T7)" in it })
        assertTrue(Paper.state.positions.none { it.quantity != 0 })
        // Decided once: the next pass does not buy it either.
        fallingMarket(LocalTime.of(9, 21)); tick(LocalTime.of(9, 21))
        assertTrue(PassedArmsHost.view.value.open.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun withTheFilterOffTheSameBreakIsBoughtOnThePlainRules() {
        historyNow { u -> if (u == "NIFTY") fallingYesterday() else emptyList() }
        only(DonchianArm.SOURCE)
        runBlocking { PassedArmsHost.setSupertrend(false) }
        fallingMarket(LocalTime.of(9, 20))
        tick(LocalTime.of(9, 20))
        val p = PassedArmsHost.view.value.open.single()
        assertEquals("CE", p.right); assertTrue(p.symbol, p.symbol.contains("24950")); assertFalse("a plain-rules trade", p.st)
        assertNothingAtZerodha()
    }

    @Test fun withTheFilterOnABreakWithNoEarlierSessionIsNotJudgedSoNotTaken() {
        historyNow { emptyList() }
        only(DonchianArm.SOURCE)
        runBlocking { PassedArmsHost.setSupertrend(true) }
        // Flat at 25,000 until 10:55, then 25,100: today's 21st bar breaks up (entry 11:00), with no earlier session in.
        val until = LocalTime.of(11, 0)
        minutes(niftyKey, until) { t -> if (t.isBefore(LocalTime.of(10, 59))) 25_000.0 else 25_100.0 }
        minutes(ce, until) { 100.0 }; minutes(pe, until) { 90.0 }
        tick(LocalTime.of(11, 0))
        assertTrue(PassedArmsHost.view.value.open.isEmpty())
        assertTrue(view().log.any { "Supertrend cannot be judged yet" in it })
        // 16 Oct (R73 review): counted where Boss sees it - the row, Jarvis and the diagnostics - and kept over a restart.
        assertEquals(1, view().stUnjudged)
        assertTrue(PassedArmsHost.diagLine(), PassedArmsHost.diagLine().contains("Supertrend filter (R73 T7) on for new breaks, 1 not judged today"))
        assertFalse("said once, not again from its kept line", PassedArmsHost.diagLine().contains("Supertrend not judged"))
        assertTrue(com.optionslab.app.ui.screens.passedStatusLine(DonchianArm.SOURCE, view()).endsWith(" · 1 not judged by Supertrend"))
        val said = runBlocking { PassedArmsHost.talk(com.optionslab.ira.PassedArmsTalk.Topic.DONCHIAN) }
        assertTrue(said, "Today Supertrend could not judge 1 break (no earlier session in), so it was not taken." in said)
        assertTrue(said, DonchianArm.ST_RULE in said)
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertEquals(1, view().stUnjudged)
        assertNothingAtZerodha()
    }

    /** Flat at 25,000 (high 25,001, low 24,999) on [d], 09:15 - 15:29. */
    private fun flat(d: LocalDate, base: Double = 25_000.0): List<Bar> =
        (0 until 375).map { Bar(d.atTime(9, 15).plusMinutes(it.toLong()), base, base + 1, base - 1, base) }

    @Test fun theSupertrendFilterIsR51sOnlyBreadthDonchianStillBuysABreakSupertrendIsAgainst() {
        // 16 Oct (R73 review): the filter is passed to R51's taking only; R72 keeps its own rules (R73: it does not help R72).
        bothDonchians()
        runBlocking { PassedArmsHost.setSupertrend(true) }
        breadthMarket(up = setOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), until = LocalTime.of(9, 20))
        historyNow { u -> if (u == "NIFTY") fallingYesterday() else bases[u]?.let { flat(day.minusDays(1), it) }.orEmpty() }
        fallingMarket(LocalTime.of(9, 20))
        tick(LocalTime.of(9, 20))
        val open = PassedArmsHost.view.value.open
        assertEquals(open.toString(), listOf(BreadthDonchianArm.SOURCE), open.map { it.arm })
        assertTrue(open.single().symbol, open.single().symbol.contains("24950")); assertFalse("R72's trade is never labelled as filtered", open.single().st)
        assertTrue(view().log.any { DonchianArm.LABEL in it && "call break skipped: Supertrend (10, 3) is against it (R73 T7)" in it })
        assertNothingAtZerodha()
    }

    @Test fun supertrendIsSeededFromEveryEarlierSessionInMemoryNotOnlyTheLastTwo() {
        // 16 Oct (R73 review): a fall three sessions back, then two flat sessions. Over the whole series (the research's way)
        // Supertrend is still down at 25,004 (its band sits near 25,006); seeded from the last two flat sessions only it starts
        // up. Donchian's own break (a close above the last 20 bars' 25,001) is the same either way.
        historyNow { u -> if (u != "NIFTY") emptyList() else
            fallingYesterday().map { it.copy(start = it.start.minusDays(2)) } + flat(day.minusDays(2)) + flat(day.minusDays(1)) }
        assertEquals(3, PassedArmsHost.history("NIFTY", day)!!.sessions.size)
        only(DonchianArm.SOURCE)
        runBlocking { PassedArmsHost.setSupertrend(true) }
        fallingMarket(LocalTime.of(9, 20))
        minutes(niftyKey, LocalTime.of(9, 20)) { t -> if (t.isBefore(LocalTime.of(9, 19))) 25_000.0 else 25_004.0 }
        tick(LocalTime.of(9, 20))
        assertTrue(PassedArmsHost.view.value.open.toString(), PassedArmsHost.view.value.open.isEmpty())
        assertTrue(view().log.any { "call break skipped: Supertrend (10, 3) is against it (R73 T7)" in it })
        assertEquals(0, view().stUnjudged)
        assertNothingAtZerodha()
    }
}
