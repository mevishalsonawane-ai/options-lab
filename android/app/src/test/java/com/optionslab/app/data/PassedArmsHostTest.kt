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
        assertEquals(3, view().log.count { "switched on once" in it })
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
        orphanBuy(com.optionslab.engine.orb.NightRules.LABEL)
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
}
