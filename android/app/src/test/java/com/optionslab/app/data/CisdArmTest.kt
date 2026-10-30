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
import com.optionslab.engine.orb.CisdNet
import com.optionslab.engine.orb.LiveMirror
import com.optionslab.engine.orb.PassedArms
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
 * CISD + network ([CisdArm], 29 Oct): with the app in Live, logged in, real orders allowed and "AI trades go live" on, a CISD setup on
 * NIFTY's 5-minute bars that the network expects to pay is bought on PAPER only (no live path at all), 1 lot of the ATM option; a
 * refusing network buys nothing and keeps the index busy as the research counted it; the index stop, the 60% lock after +₹1,000
 * (resting in the paper book) and the square-off sell it; every guard refuses; missing data waits and is said; R131's expiry rule;
 * a restart restores the trade first and its exits keep running at full cadence; its switch is on once by the update, logged, undone
 * in one tap; it is labelled "failed gates, Boss's exception" everywhere; and not one order reaches Zerodha.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class CisdArmTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private lateinit var expiry: LocalDate
    private val peToken = 33_000_001L
    private val ceToken = 33_000_002L
    private val niftyToken = 256265L
    private val pe = "NSE_FO|C525000PE"
    private val ce = "NSE_FO|C525000CE"

    private fun at(t: LocalTime, s: Long = 0) { Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(s).atZone(IST).toInstant(), IST) }

    private val start = mapOf("NIFTY" to 25_000.0, "BANKNIFTY" to 56_000.0, "FINNIFTY" to 26_500.0, "MIDCPNIFTY" to 13_000.0, "SENSEX" to 82_000.0,
        PassedArmsHost.VIX to 14.0)

    private fun flat(p: Double) = doubleArrayOf(p, p + 5, p - 5, p)

    /** 5-minute bars (open, high, low, close) as minutes on [d] from 09:15. */
    private fun minutes(d: LocalDate, bars: List<DoubleArray>): List<Bar> = bars.flatMapIndexed { b, x ->
        val mid = (x[0] + x[3]) / 2
        (0 until 5).map { k -> Bar(d.atTime(9, 15).plusMinutes((b * 5 + k).toLong()), if (k == 0) x[0] else mid, x[1], x[2], if (k == 4) x[3] else mid) }
    }

    /** NIFTY today: flat at 25,000, a 2-bar drop of 45 (2 ATR = 20), a green bar, then 10:20's bar closing at 25,005 (above 25,000). */
    private fun niftyToday(extra: List<DoubleArray> = emptyList()): List<DoubleArray> = (0 until 10).map { flat(25_000.0) } + listOf(
        doubleArrayOf(25_000.0, 25_001.0, 24_975.0, 24_980.0), doubleArrayOf(24_980.0, 24_981.0, 24_955.0, 24_960.0),
        doubleArrayOf(24_960.0, 24_992.0, 24_958.0, 24_990.0), doubleArrayOf(24_990.0, 25_008.0, 24_988.0, 25_005.0)) + extra

    /** After 10:25: flat at 25,005 to 10:55, a second drop of 45 (its low [low2]) and 11:05's bar closing at 25,010: a second setup. */
    private fun second(low2: Double = 24_960.0): List<DoubleArray> = (14 until 20).map { flat(25_005.0) } + listOf(
        doubleArrayOf(25_005.0, 25_006.0, 24_985.0, 24_985.0), doubleArrayOf(24_985.0, 24_986.0, low2, 24_965.0),
        doubleArrayOf(24_965.0, 25_012.0, 24_964.0, 25_010.0))

    private fun prior(n: Int): List<LocalDate> = generateSequence(day.minusDays(1)) { it.minusDays(1) }.filter { Market.isTradingDay(it) }.take(n).toList().reversed()

    /** The reads: today's minutes, six earlier sessions (yesterday with a swing high 50 above, the target) and 25 daily candles. */
    private fun market(nifty: List<DoubleArray> = niftyToday(), through: Int = 70, withHistory: Boolean = true, niftyBars: Boolean = true) {
        CisdArm.testBars = { u ->
            if (u == "NIFTY") (if (niftyBars) minutes(day, nifty).filter { PassedArms.Session.row(it.start.toLocalTime()) < through } else null)
            else start.getValue(u).let { px -> minutes(day, (0 until through / 5).map { flat(px) }) }
        }
        CisdArm.testHist = { u ->
            if (!withHistory) null else {
                val days = prior(25)
                val px = start.getValue(u)
                PassedArmsHost.Hist(day, days.takeLast(6).mapIndexed { i, d ->
                    PassedArms.DayBars.of(d, minutes(d, (0 until 75).map { b -> if (i == 5 && b == 40) doubleArrayOf(px, px + 50, px - 5, px) else flat(px) }))
                }, days.map { d -> Bar(d.atTime(9, 15), px, px * 1.01, px * 0.99, px) })
            }
        }
        CisdArm.testListed = { u ->
            val st = PassedArms.step(u).toDouble()
            val atm = CisdNet.strike(u, start.getValue(u)).toDouble()
            (-8..8).flatMap { i -> val k = atm + st * i; listOf(Right.PE, Right.CE).map { r ->
                Upstox.Contract(u, expiry, k, r, if (u == "NIFTY") 65 else 30,
                    if (u == "NIFTY" && k == 25_000.0) (if (r == Right.PE) pe else ce) else "TEST|$u${k.toInt()}${r.name}", "$u-C5-${k.toInt()}${r.name}") } }
        }
        CisdArm.testChain = { c ->
            val base = if (c.right == Right.CE) 120.0 else 110.0
            (0 until 375).map { i -> Upstox.Bar(day.atTime(9, 15).plusMinutes(i.toLong()).atZone(IST).toEpochSecond(), base, base + 1, base - 1, base, 1000L, 50_000L) }
        }
    }

    /** The network's EV forced to [ev] on every cell (so the side's mean EV is [ev]). */
    private fun forceEv(ev: Double) { CisdArm.testEv = { _, _ -> DoubleArray(16) { ev } } }

    private fun option(token: Long, px: Double) = KiteStream.feedForTest(listOf(KiteTicks.Tick(token, px, bid = px - 0.1, ask = px + 0.1)))
    private fun index(px: Double) = KiteStream.feedForTest(listOf(KiteTicks.Tick(niftyToken, px)))

    @Before fun up() {
        upstox = FakeUpstox()
        kite = FakeKite()
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
        day = AutomationSupport.tradingDayAt(10, 25).toLocalDate()
        expiry = day.plusDays(6)
        while (!Market.isTradingDay(expiry)) expiry = expiry.plusDays(1)
        at(LocalTime.of(10, 25))
        val strikes = (-8..8).map { 25_000.0 + 50 * it }
        AutomationSupport.contracts(context, strikes.flatMap { k -> listOf(
            Upstox.Contract("NIFTY", expiry, k, Right.PE, 65, if (k == 25_000.0) pe else "NSE_FO|C5${k.toInt()}PE", "NIFTY-C5-${k.toInt()}PE"),
            Upstox.Contract("NIFTY", expiry, k, Right.CE, 65, if (k == 25_000.0) ce else "NSE_FO|C5${k.toInt()}CE", "NIFTY-C5-${k.toInt()}CE")) })
        kite.instruments += FakeKite.Ins(peToken, "NIFTYC525000PE", "NIFTY", expiry, 25_000.0, "PE", 65)
        kite.instruments += FakeKite.Ins(ceToken, "NIFTYC525000CE", "NIFTY", expiry, 25_000.0, "CE", 65)
        java.io.File(context.filesDir, "kite_instruments.json").delete()
        runBlocking { Broker.instruments() }
        KiteStream.resetForTest()
        KiteStream.liveForTest(true)
        // Built switched off (no Dhan IV on the phone): these tests switch it on as Boss would, unlogged.
        runBlocking { CisdArm.setArmed(true, log = false) }
        kite.requests.clear(); upstox.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        CisdArm.wipeForErase()
        KiteStream.liveForTest(false)
        KiteStream.resetForTest()
        if (Autopilot.enabled()) Autopilot.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun view() = runBlocking { CisdArm.refresh() }
    private fun tick(t: LocalTime, s: Long = 0) { at(t, s); runBlocking { CisdArm.tick() } }
    private fun manage(t: LocalTime, s: Long = 0) { at(t, s); runBlocking { CisdArm.manageOnly() } }

    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    /** NIFTY's bullish CISD at 10:25 with a paying network, its option on the stream: bought. */
    private fun boughtAt1025(): CisdArm.Pos {
        market(); forceEv(40.0)
        index(25_006.0); option(ceToken, 120.4); option(peToken, 110.4)
        tick(LocalTime.of(10, 25), 3)
        return view().open.single()
    }

    // ---- 30 Oct: live through the mirror when on; shadow when off ------------------------------------------------------

    /** On: its paper fill is offered to its live path ([ArmLive.entered]) with its ref; its lock's level follows ([ArmLive.level]). */
    @Test fun onItsPaperFillIsOfferedToItsLivePath() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        try {
            val p = boughtAt1025()
            assertTrue(heard.toString(), heard.any { it.startsWith("entered|${CisdNet.SOURCE}|${p.key}|${p.qty}|") })
            assertEquals(listOf(ArmLive.PaperLeg(p.key, CisdNet.SOURCE, 65)), ArmLive.legsForTest(LiveMirror.CISD))
        } finally { ArmLive.testHeard = null }
    }

    /**
     * 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record only"): off, the kept setup is a would-be
     * trade in the switched-off shadow with its index stop and its 60% lock after +Rs 1,000 - nothing ordered, nothing offered live.
     */
    @Test fun offTheSetupIsAWouldBeTradeWithItsIndexStopAndNothingIsOrdered() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        OffShadow.testSession = true
        OffShadow.testPrice = { Triple(120.4, 120.3, 120.5) }
        try {
            runBlocking { CisdArm.setArmed(false) }
            market(); forceEv(40.0)
            index(25_006.0); option(ceToken, 120.4); option(peToken, 110.4)
            tick(LocalTime.of(10, 25), 3)
            assertTrue("no paper order", Paper.state.orders.isEmpty())
            assertTrue(view().open.isEmpty())
            assertTrue("nothing offered live: $heard", heard.none { it.startsWith("entered|") })
            assertNothingAtZerodha()
            val w = OffShadow.view.value.openOf(CisdNet.SOURCE).singleOrNull() ?: error("no would-be trade: ${OffShadow.view.value} ${view().looks}")
            assertEquals("NIFTY", w.underlying); assertEquals(1, w.view)
            assertEquals(24_955.0 - 0.0002 * 24_955.0, w.exits.indexStop!!, 1e-6)
            assertEquals(CisdNet.LOCK_AFTER, w.exits.lockAfterRs!!, 1e-9)
            assertTrue(view().looks.single().why.contains("switched off"))
        } finally { ArmLive.testHeard = null; OffShadow.wipe() }
    }

    // ---- on after the update, once; logged; undone in one tap; never live -------------------------------------------

    @Test fun itIsBuiltOffSaysWhyAndIsNeverLive() {
        CisdArm.wipeForErase()
        val v = view()
        assertFalse("built switched off: the phone has no Dhan IV", v.armed)
        assertTrue(v.log.toString(), v.log.single { "built switched off" in it }.contains(CisdNet.IV_GAP))
        // 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow"): off, it still decides - nothing is bought.
        assertTrue(CisdArm.decisionDue(10 * 60 + 25))
        OffShadow.testSession = true; OffShadow.testPrice = { Triple(120.4, 120.3, 120.5) }
        market(); forceEv(40.0); index(25_006.0); option(ceToken, 120.4)
        tick(LocalTime.of(10, 25), 3)
        assertTrue("off: nothing bought", Paper.state.orders.isEmpty() && view().open.isEmpty())
        OffShadow.wipe()
        assertTrue(runBlocking { CisdArm.setArmed(true) }.startsWith("${CisdNet.SHOWN} on, on paper."))
        assertEquals("${CisdNet.SHOWN} is already on (paper).", runBlocking { CisdArm.setArmed(true) })
        AutomationSupport.reloadFromDisk(CisdArm)
        assertTrue("Boss's on is kept", view().armed)
        assertEquals("the note is written once", 1, view().log.count { "built switched off" in it })
        assertTrue(CisdArm.diagLine().contains("on (paper)") && CisdArm.diagLine().startsWith(CisdNet.SHOWN))
        assertEquals("${CisdNet.SHOWN} off. Nothing new is bought.", runBlocking { CisdArm.setArmed(false) })
        // 30 Oct (Boss: "Make them live s5 and csid startergies live too"): live through its mirror (ArmLive), only through the
        // live gates, with a labelled safety stop while only its index stop guards it. (The engine arm's paperOnly only keeps it off
        // the ORB family's own live path.)
        assertEquals(LiveMirror.CISD, LiveMirror.spec(CisdNet.SOURCE)!!.family)
        assertTrue(LiveMirror.spec(CisdNet.SOURCE)!!.safety)
    }

    @Test fun bossSwitchingItOnIsInTheChangeTimelineAndOneTapUndoesIt() {
        org.junit.Assume.assumeTrue("the change log is in IraAlgo with Jarvis only", Autopilot.enabled())
        CisdArm.wipeForErase()
        assertFalse(view().armed)
        runBlocking { CisdArm.setArmed(true) }
        fun entries() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.PAPER_ARM_ON && it.change.target == AutoChange.CISD_TARGET }
        val deadline = System.currentTimeMillis() + 10_000
        while (entries().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val e = entries().single()
        assertEquals(AutoChange.ON, e.change.to)
        assertTrue(e.change.reason, e.change.reason.contains("FAILED the research gates") && e.change.reason.contains("failed gates, Boss's exception") &&
            e.change.reason.contains("not Dhan's"))
        assertFalse("no PIN: on paper", Autopilot.undoNeedsPin(e.id))
        val said = Autopilot.undo(e.id)
        assertTrue(said, said.startsWith("Undone"))
        assertFalse(view().armed)
    }

    @Test fun theUndosSetterSwitchesItOffAndBackOn() {
        assertTrue(view().armed)
        val on = AutoChange.cisdOn()
        assertEquals("${CisdNet.SHOWN} off. Nothing new is bought.", Autopilot.apply(AutoChange.inverse(on, "undone by you"), false))
        assertFalse(view().armed)
        assertTrue(Autopilot.apply(on, false)!!.contains("on paper"))
        assertTrue(view().armed)
    }

    // ---- the setup and the network's filter -------------------------------------------------------------------------------

    @Test fun aKeptSetupIsBoughtOnPaperAndLabelled() {
        assertTrue(view().armed)
        assertTrue("its bar closes want a pass", CisdArm.wantsPass(10 * 60 + 25))
        assertTrue(CisdArm.decisionDue(10 * 60 + 25)); assertTrue(CisdArm.decisionDue(10 * 60 + 26)); assertFalse(CisdArm.decisionDue(10 * 60 + 27))
        val p = boughtAt1025()
        assertEquals("NIFTY", p.index); assertEquals("CE", p.right); assertTrue(p.symbol, p.symbol.endsWith("25000CE"))
        assertEquals(65, p.qty); assertEquals(70, p.r0); assertEquals(40.0, p.ev, 1e-9)
        assertEquals(24_955.0 - 0.0002 * 24_955.0, p.idxStop, 1e-6)
        assertEquals(25_050.0, p.target!!, 1e-9)
        val v = view()
        val l = v.looks.single()
        assertTrue(l.taken); assertTrue(l.call); assertEquals("10:25", l.time)
        assertTrue("no other index set up", v.looks.none { it.index != "NIFTY" })
        assertTrue(v.log.any { it.contains("bullish CISD - bought") && it.contains(CisdNet.NOT_PROVEN) })
        assertTrue(runBlocking { Strategies.owners() }["paper:${p.orderId}"].orEmpty().contains(CisdNet.SHOWN))
        // The forward record in the diagnostics: key, entry, network EV, index stop - no rupees in the record line.
        val rec = CisdArm.recordLine(p)
        assertTrue(rec, rec.startsWith("cisdnet ") && rec.contains("NIFTY CE in 10:25") && rec.contains("network EV +40") && !rec.contains("₹"))
        assertTrue(CisdArm.diagLine(hideNet = true).contains(PassedArmsHost.NET_HIDDEN))
        assertNothingAtZerodha()
        val said = runBlocking { CisdArm.talk() }
        assertTrue(said, said.contains(CisdNet.SHOWN) && said.contains("holding"))
        assertFalse("no rupees on a locked phone", runBlocking { CisdArm.talk(locked = true) }.contains("₹"))
        // Held: the positions-first sources know it.
        assertTrue(HeldNow.sources().cisd)
        assertTrue(com.optionslab.ira.Held.line(HeldNow.sources()).contains("CISD + network"))
    }

    @Test fun aRefusingNetworkBuysNothingAndKeepsTheIndexBusy() {
        market(nifty = niftyToday(second()), through = 70); forceEv(-5.0)
        index(25_006.0); option(ceToken, 120.4)
        tick(LocalTime.of(10, 25), 3)
        val l = view().looks.single()
        assertFalse(l.taken); assertTrue(l.why, l.why.contains("refused by the network") && l.why.contains("-5"))
        assertTrue(Paper.state.orders.isEmpty())
        // 11:10: a second setup while the refused one's trade is still running in the research's count (no stop, no lock): skipped.
        market(nifty = niftyToday(second()), through = 115); forceEv(40.0)
        index(25_011.0)
        tick(LocalTime.of(11, 10), 3)
        val l2 = view().looks.single { it.r0 == 115 }
        assertFalse(l2.taken); assertTrue(l2.why, l2.why.contains("still running in the research's count"))
        assertTrue(Paper.state.orders.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun aRefusedSetupStoppedOutFreesTheIndex() {
        // The second drop reaches 24,945, under the first setup's index stop (about 24,950): its research trade closed then.
        market(nifty = niftyToday(second(24_945.0)), through = 70); forceEv(-5.0)
        index(25_006.0)
        tick(LocalTime.of(10, 25), 3)
        market(nifty = niftyToday(second(24_945.0)), through = 115); forceEv(40.0)
        index(25_011.0); option(ceToken, 120.4)
        tick(LocalTime.of(11, 10), 3)
        val p = view().open.single()
        assertEquals(115, p.r0); assertEquals(24_945.0 - 0.0002 * 24_945.0, p.idxStop, 1e-6)
    }

    @Test fun theRealNetworkRunsOnTheInputsWhenASetupIsPresent() {
        market()
        index(25_006.0); option(ceToken, 120.4)
        tick(LocalTime.of(10, 25), 3)
        val l = view().looks.single()
        assertNull(view().model)
        assertNotNull("the network's side EV is a number", l.ev); assertTrue(l.ev!!.isFinite())
        assertEquals(l.taken, l.ev!! > 0)
        assertNothingAtZerodha()
    }

    @Test fun noSetupNoNetworkAndNothingLogged() {
        market(nifty = (0 until 14).map { flat(25_000.0) })
        CisdArm.testEv = { _, _ -> throw AssertionError("the network runs only for a setup") }
        tick(LocalTime.of(10, 25), 3)
        assertTrue(view().looks.isEmpty())
        assertTrue(Paper.state.orders.isEmpty())
    }

    @Test fun missingReadsWaitThenAreSaid() {
        market(withHistory = false); forceEv(40.0)
        tick(LocalTime.of(10, 25), 3)
        assertTrue("tried again in the window", view().looks.isEmpty())
        tick(LocalTime.of(10, 26), 30)
        assertTrue(view().looks.isEmpty())
        assertTrue(view().log.any { it.contains("NIFTY 10:25: could not look") && it.contains("earlier sessions") })
        // No NIFTY minutes at all (the index's data missing): said, nothing bought.
        market(niftyBars = false); forceEv(40.0)
        tick(LocalTime.of(10, 31), 30)
        assertTrue(view().log.any { it.contains("NIFTY 10:30: could not look") && it.contains("no NIFTY minute bar") })
        assertTrue(Paper.state.orders.isEmpty())
    }

    @Test fun aStalePriceIsNeverBought() {
        market(); forceEv(40.0)
        index(25_006.0)                                  // no option tick at all
        tick(LocalTime.of(10, 25), 3)
        val l = view().looks.single()
        assertFalse(l.taken); assertTrue(l.why, l.why.contains("no fresh price"))
        assertTrue(Paper.state.orders.isEmpty())
    }

    @Test fun bothSeriesExpiringTodayIsNoTrade() {
        market(); forceEv(40.0)
        CisdArm.testListed = { u -> listOf(Upstox.Contract(u, day, CisdNet.strike(u, start.getValue(u)).toDouble(), Right.CE, 65, ce, "X")) }
        index(25_006.0); option(ceToken, 120.4)
        tick(LocalTime.of(10, 25), 3)
        assertTrue(view().looks.single().why.contains("both today (R131: no trade)"))
        assertTrue(Paper.state.orders.isEmpty())
    }

    // ---- every guard refuses; market closed decides nothing ----------------------------------------------------------------

    @Test fun theKillSwitchRefusesAndSaysSo() {
        AppSettings.save(AppSettings.load().copy(guardKill = true))
        market(); forceEv(40.0); index(25_006.0); option(ceToken, 120.4)
        tick(LocalTime.of(10, 25), 3)
        val l = view().looks.single()
        assertFalse(l.taken); assertTrue(l.why, l.why.contains("kill switch"))
        assertTrue(Paper.state.orders.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun theBotsStopForTodayRefuses() {
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        market(); forceEv(40.0); index(25_006.0); option(ceToken, 120.4)
        tick(LocalTime.of(10, 25), 3)
        assertTrue(view().looks.single().why.contains("stopped for today"))
        assertTrue(Paper.state.orders.isEmpty())
    }

    @Test fun offOrOutsideItsMinutesOrAClosedMarketDecidesNothing() {
        assertFalse("before 08:45", CisdArm.wantsPass(8 * 60)); assertFalse("after 15:02", CisdArm.wantsPass(15 * 60 + 2))
        assertNull(CisdArm.dueR0(9 * 60 + 19)); assertEquals(5, CisdArm.dueR0(9 * 60 + 20)); assertEquals(5, CisdArm.dueR0(9 * 60 + 21))
        assertNull(CisdArm.dueR0(9 * 60 + 22)); assertEquals(345, CisdArm.dueR0(15 * 60)); assertNull(CisdArm.dueR0(15 * 60 + 5))
        market(); forceEv(40.0); index(25_006.0); option(ceToken, 120.4)
        tick(LocalTime.of(10, 23))
        assertTrue("no look between the bar closes", view().looks.isEmpty())
        runBlocking { CisdArm.setArmed(false) }
        // 30 Oct: off, it still decides at its bar closes (shadow) - never a paper order.
        assertTrue(CisdArm.decisionDue(10 * 60 + 25))
        // A market holiday (the next non-trading day): nothing decided even when on.
        runBlocking { CisdArm.setArmed(true) }
        var off = day.plusDays(1); while (Market.isTradingDay(off)) off = off.plusDays(1)
        Market.testClock = Clock.fixed(off.atTime(10, 25, 3).atZone(IST).toInstant(), IST)
        assertFalse(CisdArm.wantsPass(10 * 60 + 25))
        runBlocking { CisdArm.tick() }
        assertTrue(view().looks.isEmpty())
        assertTrue(Paper.state.orders.isEmpty())
    }

    // ---- open positions first: exits at full cadence, a restart restores them ---------------------------------------------

    @Test fun theIndexStopSellsOnAnyTickSinceTheLastLook() {
        val p = boughtAt1025()
        manage(LocalTime.of(10, 25, 20))
        assertTrue(view().open.isNotEmpty())
        index(p.idxStop - 1)                             // one tick through the stop ...
        index(25_000.0)                                  // ... and straight back: still caught
        manage(LocalTime.of(10, 25, 35))
        val done = view().closed.single()
        assertTrue(done.why, done.why!!.startsWith("index stop"))
        assertNotNull(done.points); assertNotNull(done.pct)
        assertTrue(CisdArm.recordLine(done).contains("pts ("))
        assertNothingAtZerodha()
    }

    @Test fun theLockKeeps60PercentOfTheGainOnceUp1000AndRestsInThePaperBook() {
        val p = boughtAt1025()
        assertTrue(CisdArm.holding()); assertTrue(CisdArm.wantsPass(12 * 60))
        // 10:30: up to 140 (₹1,280 on 65): no lock yet - the level counts from the next minute.
        option(ceToken, 140.0); manage(LocalTime.of(10, 30), 5)
        var held = view().open.single()
        assertNull(held.lockLevel)
        // 10:31: the best of earlier minutes is 140: the level is the entry + 60% of the gain, resting as a SELL SL-M.
        option(ceToken, 135.0); manage(LocalTime.of(10, 31), 5)
        held = view().open.single()
        val level = p.entry + 0.6 * (140.0 - p.entry)
        assertEquals(level, held.lockLevel!!, 1e-9)
        val sl = Paper.state.orders.single { it.orderId == held.lockOrderId }
        assertEquals("SL-M", sl.priceType); assertEquals("SELL", sl.action)
        assertEquals(CisdArm.lockTrigger(level), sl.triggerPrice!!.toDouble(), 1e-9)
        assertTrue(CisdArm.lockTrigger(level) <= level && level - CisdArm.lockTrigger(level) < 0.05)
        // Under the level: sold by the lock.
        option(ceToken, level - 1); manage(LocalTime.of(10, 31), 30)
        val done = view().closed.single()
        assertTrue(done.why, done.why!!.contains("profit lock"))
        assertTrue("${done.net}", done.net!! > 0)
        assertTrue("no lock left resting", Paper.state.orders.none { it.priceType == "SL-M" && it.status == "trigger pending" })
        assertNothingAtZerodha()
    }

    @Test fun underTheLockThresholdNoLockRests() {
        val p = boughtAt1025()
        option(ceToken, p.entry + 15.0); manage(LocalTime.of(10, 30), 5)       // up ₹975 on 65: not ₹1,000
        option(ceToken, p.entry + 2.0); manage(LocalTime.of(10, 31), 5)
        val held = view().open.single()
        assertNull(held.lockLevel); assertNull(held.lockOrderId)
        assertTrue(Paper.state.orders.none { it.priceType == "SL-M" })
    }

    @Test fun theSquareOffSellsAt1515() {
        boughtAt1025()
        option(ceToken, 118.0)
        manage(LocalTime.of(15, 14), 50)
        assertTrue(view().open.isNotEmpty())
        manage(LocalTime.of(15, 15), 1)
        val done = view().closed.singleOrNull()
        assertNotNull("sold by 15:15", done)
        assertTrue(done!!.why, done.why!!.contains("square-off") || done.why == "closed_elsewhere")
    }

    @Test fun aRestartRestoresTheTradeFirstAndItsExitsKeepRunning() {
        val p = boughtAt1025()
        AutomationSupport.reloadFromDisk(CisdArm)
        assertEquals(p.symbol, runBlocking { CisdArm.refresh() }.open.single().symbol)
        assertTrue(CisdArm.holding())
        assertTrue("the 15-second stop lane and the minute lane run it", CisdArm.wantsPass(14 * 60))
        index(p.idxStop - 2)
        manage(LocalTime.of(10, 40))
        assertTrue(view().closed.single().why!!.startsWith("index stop"))
    }

    @Test fun aRestartNeverOrdersTheSameSetupAgain() {
        market(); forceEv(40.0); index(25_006.0); option(ceToken, 120.4)
        tick(LocalTime.of(10, 25), 3)
        AutomationSupport.reloadFromDisk(CisdArm)
        tick(LocalTime.of(10, 26), 3)
        assertEquals(1, Paper.state.orders.count { it.action == "BUY" })
    }

    @Test fun theEraseTakesItsBook() {
        boughtAt1025()
        assertTrue(CisdArm.holdsForErase())
        assertTrue(CisdArm.filesForErase().any { it.exists() })
        CisdArm.wipeForErase()
        assertTrue(CisdArm.filesForErase().none { it.exists() })
        assertFalse(CisdArm.holdsForErase())
    }
}
