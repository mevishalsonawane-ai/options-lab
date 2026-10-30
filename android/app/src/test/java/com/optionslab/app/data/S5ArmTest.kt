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
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.LiveMirror
import com.optionslab.engine.orb.PassedArms
import com.optionslab.engine.orb.S5Trail
import com.optionslab.ira.AutoChange
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
 * S5 Trail ([S5Arm], 28 Oct): with the app in Live, logged in, real orders allowed and "AI trades go live" on, a pick at a morning
 * decision is bought on PAPER only (it has no live path at all), 1 lot of the ATM option, its stop resting in the paper book; the
 * profit lock and the square-off sell it; every guard refuses; a pick under ₹300 or a missing read is never bought; the real network
 * runs on the inputs; a restart restores the trade first; its switch is on once by the update, logged, undone in one tap; it is
 * labelled "failed research gates" everywhere; and not one order reaches Zerodha.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class S5ArmTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private lateinit var expiry: LocalDate
    private val peToken = 32_000_001L
    private val ceToken = 32_000_002L
    private val pe = "NSE_FO|S525000PE"
    private val ce = "NSE_FO|S525000CE"

    private fun at(t: LocalTime, s: Long = 0) { Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(s).atZone(IST).toInstant(), IST) }

    private val start = mapOf("NIFTY" to 25_000.0, "BANKNIFTY" to 56_000.0, "FINNIFTY" to 26_500.0, "MIDCPNIFTY" to 13_000.0, "SENSEX" to 82_000.0,
        PassedArmsHost.VIX to 14.0)

    /** [u]'s minutes on [d] from 09:15 for [n] minutes, a gentle drift (NIFTY at 09:19: 25,020, its ATM 25,000). */
    private fun minutes(u: String, d: LocalDate, n: Int): List<Bar> {
        val px = start.getValue(u)
        return (0 until n).map { i ->
            val c = px * (1 + 0.0002 * i)
            Bar(d.atTime(9, 15).plusMinutes(i.toLong()), c - 1, c + 2, c - 2, c)
        }
    }

    private fun prior(n: Int): List<LocalDate> = generateSequence(day.minusDays(1)) { it.minusDays(1) }.filter { Market.isTradingDay(it) }.take(n).toList().reversed()

    /** The reads: today's minutes to 09:19 (or [todayMinutes]), three earlier whole sessions and 25 daily candles of every index. */
    private fun market(todayMinutes: Int = 5, withHistory: Boolean = true) {
        S5Arm.testBars = { u -> minutes(u, day, todayMinutes) }
        S5Arm.testHist = { u ->
            if (!withHistory) null else {
                val days = prior(25)
                PassedArmsHost.Hist(day, days.takeLast(3).map { d -> PassedArms.DayBars.of(d, minutes(u, d, 375)) },
                    days.map { d -> start.getValue(u).let { px -> Bar(d.atTime(9, 15), px, px * 1.01, px * 0.99, px) } })
            }
        }
        // Every index's options list (NIFTY's is also the phone's saved list, [up]): its ATM +- 8 strikes, both sides.
        S5Arm.testListed = { u ->
            val st = S5Trail.step(u).toDouble()
            val atm = S5Trail.atm(start.getValue(u), S5Trail.step(u)).toDouble()
            (-8..8).flatMap { i -> val k = atm + st * i; listOf(Right.PE, Right.CE).map { r ->
                Upstox.Contract(u, expiry, k, r, if (u == "NIFTY") 65 else 30,
                    if (u == "NIFTY" && k == 25_000.0) (if (r == Right.PE) pe else ce) else "TEST|$u${k.toInt()}${r.name}", "$u-S5-${k.toInt()}${r.name}") } }
        }
        S5Arm.testChain = { c ->
            val base = if (c.right == Right.CE) 120.0 else 110.0
            (0 until todayMinutes).map { i -> Upstox.Bar(day.atTime(9, 15 + i).atZone(IST).toEpochSecond(), base, base + 1, base - 1, base + i * 0.1, 1000L + i, 50_000L + 10 * i) }
        }
    }

    /** The network's EV forced so the put cell (2000, 2000, 60) wins at [ev] for NIFTY; everything else under ₹300. */
    private fun forcePut(ev: Double = 500.0) {
        S5Arm.testEv = { u, _ -> DoubleArray(S5Trail.NJ) { 100.0 }.also { if (u == "NIFTY") it[15] = ev } }
    }

    private fun option(token: Long, px: Double) = KiteStream.feedForTest(listOf(KiteTicks.Tick(token, px, bid = px - 0.1, ask = px + 0.1)))

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
        day = AutomationSupport.tradingDayAt(9, 20).toLocalDate()
        expiry = day.plusDays(6)
        while (!Market.isTradingDay(expiry)) expiry = expiry.plusDays(1)
        at(LocalTime.of(9, 20))
        val strikes = (-8..8).map { 25_000.0 + 50 * it }
        AutomationSupport.contracts(context, strikes.flatMap { k -> listOf(
            Upstox.Contract("NIFTY", expiry, k, Right.PE, 65, if (k == 25_000.0) pe else "NSE_FO|S5${k.toInt()}PE", "NIFTY-S5-${k.toInt()}PE"),
            Upstox.Contract("NIFTY", expiry, k, Right.CE, 65, if (k == 25_000.0) ce else "NSE_FO|S5${k.toInt()}CE", "NIFTY-S5-${k.toInt()}CE")) })
        kite.instruments += FakeKite.Ins(peToken, "NIFTYS525000PE", "NIFTY", expiry, 25_000.0, "PE", 65)
        kite.instruments += FakeKite.Ins(ceToken, "NIFTYS525000CE", "NIFTY", expiry, 25_000.0, "CE", 65)
        java.io.File(context.filesDir, "kite_instruments.json").delete()
        runBlocking { Broker.instruments() }
        KiteStream.resetForTest()
        KiteStream.liveForTest(true)
        S5Arm.testNoAutoOn = false
        kite.requests.clear(); upstox.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        S5Arm.testNoAutoOn = true
        S5Arm.wipe()
        KiteStream.liveForTest(false)
        KiteStream.resetForTest()
        if (Autopilot.enabled()) Autopilot.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun view() = runBlocking { S5Arm.refresh() }
    private fun tick(t: LocalTime, s: Long = 0) { at(t, s); runBlocking { S5Arm.tick() } }

    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    /** A forced NIFTY put pick at 09:20, its price on the stream: bought. */
    private fun boughtAt0920(): S5Arm.Pos {
        market(); forcePut()
        option(peToken, 110.4); option(ceToken, 120.4)
        tick(LocalTime.of(9, 20), 3)
        return view().open.single()
    }

    // ---- 30 Oct: live through the mirror when on; shadow when off ------------------------------------------------------

    /** On: its paper fill is offered to its live path ([ArmLive.entered]) with its ref and its resting stop, 1 lot as on paper. */
    @Test fun onItsPaperFillIsOfferedToItsLivePathWithItsStop() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        try {
            val p = boughtAt0920()
            val e = heard.firstOrNull { it.startsWith("entered|${S5Trail.SOURCE}|${p.ref}|") } ?: error("not offered: $heard")
            assertTrue(e, e.endsWith("|${p.qty}|${p.stop}"))
            assertEquals(65, p.qty)
            // Its legs for the mirror: the open trade, with what it holds.
            assertEquals(listOf(ArmLive.PaperLeg(p.ref, S5Trail.SOURCE, 65)), ArmLive.legsForTest(LiveMirror.S5))
        } finally { ArmLive.testHeard = null }
    }

    /**
     * 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record only"): off, the same pick is a would-be
     * trade in the switched-off shadow - its stop, its 60% lock after +Rs 1,000 and its square-off - and nothing is ordered:
     * no paper order, nothing offered to the live path, nothing at Zerodha.
     */
    @Test fun offThePickIsAWouldBeTradeInTheShadowAndNothingIsOrdered() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        OffShadow.testSession = true
        OffShadow.testPrice = { Triple(110.4, 110.3, 110.5) }
        try {
            runBlocking { S5Arm.setArmed(false) }
            market(); forcePut()
            option(peToken, 110.4); option(ceToken, 120.4)
            tick(LocalTime.of(9, 20), 3)
            assertTrue("no paper order", Paper.state.orders.isEmpty())
            assertTrue("no paper trade", view().open.isEmpty())
            assertTrue("nothing offered live: $heard", heard.none { it.startsWith("entered|") })
            assertNothingAtZerodha()
            val w = OffShadow.view.value.openOf(S5Trail.SOURCE).singleOrNull() ?: error("no would-be trade: ${OffShadow.view.value} ${view().looks}")
            assertEquals("NIFTY", w.underlying); assertEquals(-1, w.view); assertEquals(1, w.side); assertEquals(65, w.qty)
            assertNotNull("its stop", w.exits.stop)
            assertEquals(S5Trail.LOCK_FROM, w.exits.lockAfterRs!!, 1e-9); assertEquals(S5Trail.LOCK_KEEP, w.exits.lockKeep, 1e-9)
            assertTrue(view().looks.single { it.index == "NIFTY" }.why.contains("switched off"))
            // The same signal again is never a second would-be trade, and a second NIFTY pick waits for the first to close.
            tick(LocalTime.of(9, 21), 10)
            assertEquals(1, OffShadow.view.value.openOf(S5Trail.SOURCE).size)
        } finally { ArmLive.testHeard = null; OffShadow.wipe() }
    }

    // ---- on after the update, once; logged; undone in one tap; never live -------------------------------------------

    @Test fun theUpdateSwitchesItOnOnceOnPaperAndNeverAgain() {
        val v = view()
        assertTrue("on after the update (Boss's ask)", v.armed)
        assertTrue(v.log.toString(), v.log.single { "switched on once by the 28 Oct update" in it }.contains(S5Trail.NOT_PROVEN))
        assertTrue(S5Arm.diagLine().contains("on (paper)") && S5Arm.diagLine().contains(S5Trail.NOT_PROVEN))
        assertEquals("${S5Trail.LABEL} off. Nothing new is bought.", runBlocking { S5Arm.setArmed(false) })
        assertEquals("${S5Trail.LABEL} is already off (paper).", runBlocking { S5Arm.setArmed(false) })
        AutomationSupport.reloadFromDisk(S5Arm)
        assertFalse("Boss's off is kept", view().armed)
        assertEquals(1, view().log.count { "switched on once" in it })
        // 30 Oct (Boss: "Make them live s5 and csid startergies live too"): it has a live mirror now, through the live gates only;
        // switched off, it buys nothing anywhere (its live copy needs a paper fill).
        assertNotNull(LiveMirror.spec(S5Trail.SOURCE))
        assertEquals(LiveMirror.S5, LiveMirror.spec(S5Trail.SOURCE)!!.family)
    }

    @Test fun itIsInTheChangeLogAsBossOwnAndOneTapSwitchesItOff() {
        org.junit.Assume.assumeTrue("the change log is in IraAlgo with Jarvis only", Autopilot.enabled())
        view()
        fun entries() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.PAPER_ARM_ON && it.change.target == AutoChange.S5_TARGET }
        val deadline = System.currentTimeMillis() + 10_000
        while (entries().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val e = entries().single()
        assertEquals(AutoChange.Status.KEPT, e.status); assertEquals("your choice", e.by)
        assertTrue(e.change.reason.contains("FAILED the research gates"))
        assertFalse("no PIN: on paper", Autopilot.undoNeedsPin(e.id))
        val said = Autopilot.undo(e.id)
        assertTrue(said, said.startsWith("Undone"))
        assertFalse(view().armed)
    }

    @Test fun theUndosSetterSwitchesItOffAndBackOn() {
        assertTrue(view().armed)
        val on = AutoChange.s5On()
        assertEquals("${S5Trail.LABEL} off. Nothing new is bought.", Autopilot.apply(AutoChange.inverse(on, "undone by you"), false))
        assertFalse(view().armed)
        assertTrue(Autopilot.apply(on, false)!!.contains("on paper"))
        assertTrue(view().armed)
    }

    // ---- a pick becomes a paper order ------------------------------------------------------------------------------------

    @Test fun aPickIsBoughtOnPaperWithItsStopResting() {
        assertTrue(view().armed)
        assertTrue("its decision minutes want a pass", S5Arm.wantsPass(9 * 60 + 20))
        assertTrue(S5Arm.decisionDue(9 * 60 + 25))
        val p = boughtAt0920()
        assertEquals("NIFTY", p.index); assertEquals("PE", p.right); assertTrue(p.symbol, p.symbol.endsWith("25000PE"))
        assertEquals(65, p.qty); assertEquals(2000.0, p.stopRs, 0.0); assertEquals(15, p.cell); assertEquals(500.0, p.ev, 1e-9)
        // The stop's FILL (10 bps and 2 ticks under its trigger, research's stop fill) nets about −₹2,000 after both legs' charges;
        // the trigger itself sits a little above it.
        val fill = p.stop!! * (1 - S5Trail.STOP_BPS / 1e4) - 2 * 0.05
        val stopNet = S5Trail.netAt(p.entry, fill, p.qty, S5Arm.chargeFor(p.qty))
        assertTrue("$stopNet", stopNet in -2000.0 - 1e-6..-2000.0 + 0.05 * 65 + 1e-6)
        assertTrue(S5Trail.netAt(p.entry, p.stop!!, p.qty, S5Arm.chargeFor(p.qty)) > stopNet)
        val stop = Paper.state.orders.single { it.orderId == p.stopOrderId }
        assertEquals("SL-M", stop.priceType); assertEquals("SELL", stop.action); assertEquals(p.stop!!, stop.triggerPrice!!.toDouble(), 1e-9)
        val v = view()
        val nifty = v.looks.single { it.index == "NIFTY" && it.row == 4 }
        assertTrue(nifty.taken); assertTrue(nifty.why, nifty.why.startsWith("taken"))
        // The other indices' best EV was ₹100: decided, not taken, said why.
        assertTrue(v.looks.filter { it.index != "NIFTY" }.all { !it.taken && it.why.contains("under ₹300") })
        assertTrue(v.log.any { it.contains("bought") && it.contains(S5Trail.NOT_PROVEN) })
        assertTrue(runBlocking { Strategies.owners() }["paper:${p.orderId}"].orEmpty().contains(S5Trail.NOT_PROVEN))
        // A second pass at 09:21 and the 09:25 decision never buy NIFTY again while it is open.
        tick(LocalTime.of(9, 21))
        market(todayMinutes = 10)
        tick(LocalTime.of(9, 25), 2)
        assertEquals(1, view().open.size)
        assertTrue(view().looks.single { it.index == "NIFTY" && it.row == 9 }.why.contains("still open"))
        assertNothingAtZerodha()
        val said = runBlocking { S5Arm.talk() }
        assertTrue(said, said.contains(S5Trail.NOT_PROVEN) && said.contains("holding"))
        assertFalse("no rupees on a locked phone", runBlocking { S5Arm.talk(locked = true) }.contains("₹"))
    }

    @Test fun theRealNetworkDecidesOnTheInputs() {
        market()
        option(peToken, 110.4); option(ceToken, 120.4)
        tick(LocalTime.of(9, 20), 3)
        val v = view()
        assertEquals(v.looks.toString(), S5Trail.UNDERLYINGS.toSet(), v.looks.filter { it.row == 4 }.map { it.index }.toSet())
        assertNull(v.model)
        // Each decision carries the network's best EV (a number, not a failure).
        assertTrue(v.looks.toString(), v.looks.all { it.ev != null && it.ev!!.isFinite() })
        assertNothingAtZerodha()
    }

    @Test fun anEvUnder300IsNeverBought() {
        market(); forcePut(299.99)
        option(peToken, 110.4)
        tick(LocalTime.of(9, 20), 3)
        assertTrue(view().open.isEmpty())
        assertTrue(view().looks.single { it.index == "NIFTY" }.why.contains("under ₹300"))
        assertTrue(Paper.state.orders.isEmpty())
    }

    @Test fun aMissingReadWaitsThenIsSaid() {
        market(withHistory = false); forcePut()
        tick(LocalTime.of(9, 20), 3)
        assertTrue("tried again in the window", view().looks.isEmpty())
        tick(LocalTime.of(9, 21), 30)
        val l = view().looks.single { it.index == "NIFTY" }
        assertFalse(l.taken); assertTrue(l.why, l.why.contains("earlier sessions"))
        // No 09:24 minute yet at 09:25: waits, then says so at the window's end.
        market(todayMinutes = 9)
        tick(LocalTime.of(9, 26), 30)
        assertTrue(view().looks.single { it.index == "NIFTY" && it.row == 9 }.why.contains("no NIFTY minute bar"))
        assertTrue(Paper.state.orders.isEmpty())
    }

    // ---- every guard refuses -------------------------------------------------------------------------------------------

    @Test fun theKillSwitchRefusesAndSaysSo() {
        AppSettings.save(AppSettings.load().copy(guardKill = true))
        market(); forcePut(); option(peToken, 110.4)
        tick(LocalTime.of(9, 20), 3)
        assertTrue(view().open.isEmpty())
        val l = view().looks.single { it.index == "NIFTY" }
        assertFalse(l.taken); assertTrue(l.why, l.why.contains("kill switch"))
        assertTrue(Paper.state.orders.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun theBotsStopForTodayRefuses() {
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        market(); forcePut(); option(peToken, 110.4)
        tick(LocalTime.of(9, 20), 3)
        assertTrue(view().open.isEmpty())
        assertTrue(view().looks.single { it.index == "NIFTY" }.why.contains("stopped for today"))
    }

    /** 30 Oct (Boss: "Make stop bot different for index and comodities"): the commodity stop never stops this index arm. */
    @Test fun theCommodityStopNeverStopsThisIndexArm() {
        runBlocking { Strategies.stopCommodities(sell = true) }
        val p = boughtAt0920()
        assertEquals("NIFTY", p.index)
        assertNothingAtZerodha()
    }

    /** 30 Oct, OPEN POSITIONS FIRST: the index side stopped while it holds - still watched at full cadence and closed by its own exit. */
    @Test fun aHeldTradeIsStillWatchedAndClosedByItsOwnExitAfterTheIndexStop() {
        val p = boughtAt0920()
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        assertTrue(S5Arm.holding()); assertTrue("its watch still wants every pass", S5Arm.wantsPass(12 * 60))
        assertEquals("its stop still rests", "trigger pending", Paper.state.orders.single { it.orderId == p.stopOrderId }.status)
        option(peToken, p.entry + 42.0); at(LocalTime.of(10, 0), 5); runBlocking { S5Arm.manageOnly() }
        option(peToken, p.entry + 20.0); at(LocalTime.of(10, 2), 5); runBlocking { S5Arm.manageOnly() }
        assertTrue(view().closed.single().why!!.startsWith("profit lock"))
        assertNothingAtZerodha()
    }

    @Test fun offOrOutsideItsMinutesDecidesNothing() {
        assertFalse("before 08:45", S5Arm.wantsPass(8 * 60))
        assertFalse("after 09:32", S5Arm.wantsPass(10 * 60))
        assertNull(S5Arm.dueRow(9 * 60 + 19)); assertEquals(4, S5Arm.dueRow(9 * 60 + 21)); assertNull(S5Arm.dueRow(9 * 60 + 22))
        assertEquals(14, S5Arm.dueRow(9 * 60 + 30))
        market(); forcePut(); option(peToken, 110.4)
        tick(LocalTime.of(9, 23))
        assertTrue("no decision between the bar closes", view().looks.isEmpty())
        runBlocking { S5Arm.setArmed(false) }
        // 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow"): off, it still decides at its bar closes -
        // nothing is ever ordered (its picks go to the switched-off shadow: S5's shadow test).
        assertTrue(S5Arm.decisionDue(9 * 60 + 20))
        assertTrue(Paper.state.orders.isEmpty())
    }

    // ---- open positions first: exits at full cadence, a restart restores them ---------------------------------------------

    @Test fun theRestingStopFillsAndIsTheExit() {
        val p = boughtAt0920()
        option(peToken, p.stop!! - 1)
        at(LocalTime.of(9, 43))
        runBlocking { Paper.tick() }
        runBlocking { S5Arm.manageOnly() }
        assertEquals("stop", view().closed.single().why)
        assertNothingAtZerodha()
    }

    @Test fun theProfitLockKeeps60PercentOfTheBest() {
        val p = boughtAt0920()
        assertTrue(S5Arm.holding()); assertTrue(S5Arm.wantsPass(12 * 60))
        // Up about ₹2,600 net at 10:00's mark, then back: out once the net falls to 60% of its best.
        option(peToken, p.entry + 42.0); at(LocalTime.of(10, 0), 5); runBlocking { S5Arm.manageOnly() }
        val held = view().open.single()
        assertTrue("${held.peak}", held.peak!! > 2500)
        assertNotNull(held.lockFloor)
        // The same minute is marked once: a dip inside it does not sell.
        option(peToken, p.entry + 10.0); at(LocalTime.of(10, 0), 40); runBlocking { S5Arm.manageOnly() }
        assertTrue(view().open.isNotEmpty())
        option(peToken, p.entry + 30.0); at(LocalTime.of(10, 1), 5); runBlocking { S5Arm.manageOnly() }
        assertTrue("still above 60% of the best", view().open.isNotEmpty())
        option(peToken, p.entry + 20.0); at(LocalTime.of(10, 2), 5); runBlocking { S5Arm.manageOnly() }
        val done = view().closed.single()
        assertTrue(done.why, done.why!!.startsWith("profit lock"))
        assertTrue("${done.net}", done.net!! > 0)
        assertTrue("its resting stop is taken out", Paper.state.orders.none { it.priceType == "SL-M" && it.status == "trigger pending" })
        assertNothingAtZerodha()
    }

    @Test fun theSquareOffSellsAt1515() {
        boughtAt0920()
        option(peToken, 105.0)
        at(LocalTime.of(15, 14), 50); runBlocking { S5Arm.manageOnly() }
        assertTrue(view().open.isNotEmpty())
        at(LocalTime.of(15, 15), 1); runBlocking { S5Arm.manageOnly() }
        val done = view().closed.singleOrNull()
        assertNotNull("sold by 15:15", done)
        assertTrue(done!!.why, done.why!!.contains("square-off") || done.why == "closed_elsewhere")
    }

    @Test fun aRestartRestoresTheTradeFirstAndItsExitsKeepRunning() {
        val p = boughtAt0920()
        AutomationSupport.reloadFromDisk(S5Arm)
        assertEquals(p.symbol, runBlocking { S5Arm.refresh() }.open.single().symbol)
        assertTrue(S5Arm.holding())
        option(peToken, p.stop!! - 1)
        at(LocalTime.of(9, 50))
        runBlocking { Paper.tick() }
        runBlocking { S5Arm.manageOnly() }
        assertEquals("stop", view().closed.single().why)
    }

    @Test fun theEraseTakesItsBook() {
        boughtAt0920()
        assertTrue(S5Arm.holdsForErase())
        assertTrue(S5Arm.filesForErase().any { it.exists() })
        S5Arm.wipeForErase()
        assertTrue(S5Arm.filesForErase().none { it.exists() })
        assertFalse(S5Arm.holdsForErase())
    }

    @Test fun pastStatsPreferTheMinuteSessions() {
        val d = prior(3)
        val minute = d.takeLast(2).map { PassedArms.DayBars.of(it, minutes("NIFTY", it, 375)) }
        val daily = d.map { Bar(it.atTime(9, 15), 25_000.0, 25_500.0, 24_500.0, 25_000.0) } + Bar(day.atTime(9, 15), 1.0, 2.0, 0.5, 1.0)
        val past = S5Arm.pastOf(minute, daily, day)
        assertEquals(3, past.size)
        assertEquals(4.0, past.first().range, 1e-9)                       // the daily candle: (25,500 − 24,500) / 25,000
        assertEquals(S5Trail.Past.of(minute.last()), past.last())        // the minute session where read
        assertTrue(past.none { it.date == day })
    }
}
