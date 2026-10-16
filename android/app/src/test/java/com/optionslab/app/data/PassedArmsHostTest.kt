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
        assertTrue(PassedArmsHost.SOURCES.all { v.on(it) })
        assertEquals(3, v.log.count { "switched on once by the 15 Oct update" in it && PassedArms.PASSED in it })
        assertTrue(PassedArmsHost.diagLine().contains("on (paper only)") && PassedArmsHost.diagLine().contains(PassedArms.PASSED))
        assertEquals("${DonchianArm.LABEL} off.", runBlocking { PassedArmsHost.setArmed(DonchianArm.SOURCE, false) })
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertFalse(view().on(DonchianArm.SOURCE))
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
        assertTrue(v.on(DonchianArm.SOURCE))
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
        assertTrue(view().on(DonchianArm.SOURCE))
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

    @Test fun fourIndicesBreakingTogetherBuyBreadthsOwnTradeBesideDonchiansOnTheSameOptionAndEachSellsItsOwnLot() {
        bothDonchians()
        runBlocking { PassedArmsHost.setLock(true); PassedArmsHost.setL25(true) }
        breadthMarket(up = setOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), until = LocalTime.of(9, 20))
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        val open = PassedArmsHost.view.value.open
        assertEquals(open.toString(), 2, open.size)
        val br = open.single { it.arm == BreadthDonchianArm.SOURCE }
        val dn = open.single { it.arm == DonchianArm.SOURCE }
        // The same 1-ITM call, each its own lot; breadth's with Donchian's exits plus the lock and L25 (the book's switches).
        assertEquals(dn.symbol, br.symbol); assertTrue(br.symbol, br.symbol.contains("25050")); assertEquals("CE", br.right); assertEquals(65, br.qty)
        assertTrue(br.lock && br.l25)
        assertNull(br.breadth); assertEquals("Donchian's break labelled as confirmed", true, dn.breadth)
        assertEquals(130, Paper.state.positions.filter { it.symbol == br.symbol }.sumOf { it.quantity })
        val x = PassedArmsHost.exitsOf(br, null)
        assertEquals(br.entry * 0.85, x.stop!!, 1e-9); assertNull(x.target); assertEquals("+5% locked once +25%", x.note)
        assertTrue(PassedArmsHost.diagLine().contains("${BreadthDonchianArm.LABEL}: on (paper only) · open 1"))
        assertNothingAtZerodha()
        // The bar is decided once: the next pass buys nothing more.
        market(LocalTime.of(9, 21)); breadthMarket(setOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), LocalTime.of(9, 21)); tick(LocalTime.of(9, 21))
        assertEquals(2, PassedArmsHost.view.value.open.size)
        // 09:24 trades at 84 (under -15%): each arm's stop sells its own lot.
        market(LocalTime.of(9, 25), call = { t -> if (t.isBefore(LocalTime.of(9, 24))) 100.0 else 84.0 })
        at(LocalTime.of(9, 25)); runBlocking { PassedArmsHost.manageOnly() }
        val closed = view().closed
        assertEquals(setOf(DonchianArm.SOURCE, BreadthDonchianArm.SOURCE), closed.map { it.arm }.toSet())
        assertTrue(closed.all { it.why == "stop" && it.qty == 65 })
        assertEquals(0, Paper.state.positions.filter { it.symbol == br.symbol }.sumOf { it.quantity })
        // Its own record, from its own rows; the comparison for Boss.
        AutomationSupport.reloadFromDisk(PassedArmsHost)
        assertEquals(1, view().closedOf(BreadthDonchianArm.SOURCE).size)
        assertTrue(view().forwardLine(BreadthDonchianArm.SOURCE).startsWith("Forward test: 1 of 40"))
        val cmp = view().comparison()
        assertTrue(cmp, cmp.contains("R72 Breadth Donchian 1 trade, −₹") && cmp.contains("R51 Donchian 20 1 trade, −₹") &&
            cmp.contains("Donchian's trades breadth did not confirm: 0 trades, +₹0"))
        assertNothingAtZerodha()
    }

    /** Both arms' lots of the same option sold elsewhere in one sale (a square-off): each closes, each with only its share of its charges. */
    @Test fun aSharedOptionSoldElsewhereInOneSaleClosesBothWithTheirShareOfItsCharges() {
        bothDonchians()
        breadthMarket(up = setOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), until = LocalTime.of(9, 20))
        market(LocalTime.of(9, 20)); tick(LocalTime.of(9, 20))
        val open = PassedArmsHost.view.value.open
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
}
