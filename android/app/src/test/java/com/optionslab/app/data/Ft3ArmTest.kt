package com.optionslab.app.data

import com.optionslab.app.ira.Autopilot
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
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
import com.optionslab.ira.AutoChange
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * "FT3 near-ATM morning" (21 Oct, Boss's choice; the R98 forward test that did not pass research): with the app in Live, logged
 * in to Zerodha, real orders allowed and "AI trades go live" on, NIFTY's own expiry day buys - ON PAPER, not one order at Zerodha
 * from this arm - Rs 5,000 of whole lots of the first 5-minute mark's qualifying option, and sells it at 15:05. Its live path is
 * told of the buy and the close (the standard one, behind the PIN and Shadow). A signal the watch was not in time for is that
 * day's ticket gone, never a later mark's. It is switched on once by the update (logged once as Boss's change, undone in one
 * tap), keeps its trades in its own book, is restored first after a restart, and the kill switch refuses it.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class Ft3ArmTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")
    private val sensexKey = "BSE_INDEX|SENSEX"
    private val bookFile get() = File(context.filesDir, "ft3.vault")

    private fun ce(k: Int) = "NSE_FO|F3${k}CE"
    private fun pe(k: Int) = "NSE_FO|F3${k}PE"

    /**
     * NIFTY at 25,010 all day, its ATM 25,000 call 50 and put 40 (straddle 90: the band is 139 points). The 25,100 call (1.0
     * straddles) is Rs 12 until the 11:20 bar and Rs 8 from it (in the Rs 6-10 band, its IV well under the ATM's); every other
     * strike is out of the price band all day.
     */
    private val callPx = mapOf(25_000 to 50.0, 25_050 to 30.0, 25_100 to 12.0, 25_150 to 3.0)
    private val putPx = mapOf(25_000 to 40.0, 24_950 to 20.0, 24_900 to 3.0)
    private val signalBar = LocalTime.of(11, 20)

    private fun at(t: LocalTime, seconds: Long = 20) {
        Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(seconds).atZone(IST).toInstant(), IST)
    }

    private fun tick(t: LocalTime, seconds: Long = 20) { at(t, seconds); runBlocking { Ft3Arm.tick() } }

    /** 1-minute candles of [key] from 09:15 to [until] (exclusive), each closing at [px] (by the minute it STARTS). */
    private fun minutes(key: String, until: LocalTime, px: (LocalTime) -> Double) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) { val c = px(t); out += FakeUpstox.Candle(t, c, c, c, c); t = t.plusMinutes(1) }
        upstox.minutes[key] = out
    }

    /**
     * The market to [until]; the bought 25,100 call follows [held] after the signal bar. [indexUntil] / [callUntil]: a feed
     * that lags (its candles end there instead).
     */
    private fun market(until: LocalTime, signalAt: LocalTime = signalBar, held: (LocalTime) -> Double = { 8.0 },
                       indexUntil: LocalTime = until, callUntil: LocalTime = until) {
        minutes(niftyKey, indexUntil) { 25_010.0 }
        for ((k, p) in callPx) minutes(ce(k), if (k == 25_100) callUntil else until) { t ->
            if (k != 25_100 || t.isBefore(signalAt)) p else if (t == signalAt) 8.0 else held(t)
        }
        for ((k, p) in putPx) minutes(pe(k), until) { p }
    }

    @Before fun up() {
        upstox = FakeUpstox()                                // (clears Market.testClock: set it after)
        kite = FakeKite()
        // Everything that could send an order to Zerodha is on: Live, real orders allowed, logged in, AI trades live.
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        if (Autopilot.enabled()) { Autopilot.resetForTest(); Autopilot.init(context); Autopilot.testLive = false }
        day = AutomationSupport.tradingDayAt(11, 0).toLocalDate()
        at(LocalTime.of(9, 20))
        // Today is NIFTY's own expiry (next week's is listed too); the other NSE indices and SENSEX expire later.
        val list = ArrayList<Upstox.Contract>()
        for (k in callPx.keys) list += Upstox.Contract("NIFTY", day, k.toDouble(), Right.CE, 65, ce(k), "NIFTY-F3-${k}CE")
        for (k in putPx.keys) list += Upstox.Contract("NIFTY", day, k.toDouble(), Right.PE, 65, pe(k), "NIFTY-F3-${k}PE")
        list += Upstox.Contract("NIFTY", day.plusDays(7), 25_000.0, Right.CE, 65, "NSE_FO|F3NEXTCE", "NIFTY-F3-NEXT")
        for (u in listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"))
            list += Upstox.Contract(u, day.plusDays(3), 50_000.0, Right.CE, 30, "NSE_FO|F3${u}CE", "$u-F3-CE")
        AutomationSupport.contracts(context, list)
        Market.saveBfo(listOf(Upstox.Contract("SENSEX", day.plusDays(2), 82_000.0, Right.CE, 20, "BSE_FO|F3S82000CE", "SENSEX-F3-82000CE")))
        Ft3Arm.testNoAutoOn = false
        kite.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        Ft3Arm.testNoAutoOn = true
        ArmLive.testHeard = null
        if (Autopilot.enabled()) Autopilot.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun view() = runBlocking { Ft3Arm.refresh() }

    /** Not one order, modify or cancel was sent to Zerodha. */
    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    // ---- on after the update, once; logged once; undone in one tap -----------------------------------------------------------

    @Test fun theUpdateSwitchesItOnOnceOnPaperAndNeverAgain() {
        val v = view()
        assertTrue("on after the update", v.armed)
        assertTrue(v.log.toString(), v.log.single { "switched on once by the 21 Oct update" in it }.contains(Ft3Rules.BOSS_CHOICE))
        assertTrue(Ft3Arm.diagLine().contains("on (paper)") && Ft3Arm.diagLine().contains(Ft3Rules.BOSS_CHOICE))
        assertEquals("${Ft3Rules.LABEL} off.", runBlocking { Ft3Arm.setArmed(false) })
        assertTrue(runBlocking { Ft3Arm.setArmed(false) }.contains("already off"))
        AutomationSupport.reloadFromDisk(Ft3Arm)
        assertFalse(view().armed)
        assertEquals(1, view().log.count { "switched on once" in it })
        // A book that cannot be read is never switched on by itself.
        Ft3Arm.wipe(); bookFile.writeText("not a vault"); AutomationSupport.reloadFromDisk(Ft3Arm)
        assertFalse(view().armed)
    }

    @Test fun itIsInTheChangeLogOnceAsBossOwnAndOneTapSwitchesItOff() {
        org.junit.Assume.assumeTrue("the change log is in IraAlgo with Jarvis only", Autopilot.enabled())
        view()
        fun entries() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.PAPER_ARM_ON && it.change.target == AutoChange.FT3_TARGET }
        val deadline = System.currentTimeMillis() + 10_000
        while (entries().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val e = entries().single()
        assertEquals(AutoChange.Status.KEPT, e.status)
        assertEquals("your choice", e.by)
        assertFalse("no PIN: on paper", Autopilot.undoNeedsPin(e.id))
        AutomationSupport.reloadFromDisk(Ft3Arm); view(); Thread.sleep(200)
        assertEquals(1, entries().size)
        val said = Autopilot.undo(e.id)
        assertTrue(said, said.startsWith("Undone"))
        assertFalse(view().armed)
    }

    /** The change log's Undo, through Autopilot's own setter (in every build). */
    @Test fun theUndosSetterSwitchesItOffAndOnlyIt() {
        assertTrue(view().armed)
        val on = AutoChange.ft3On()
        val off = Autopilot.apply(AutoChange.inverse(on, "undone by you"), false)
        assertEquals("${Ft3Rules.LABEL} off.", off)
        assertFalse(view().armed)
        assertTrue(Autopilot.apply(on, false)!!.contains("on paper"))
        assertTrue(view().armed)
        // The Hero ticket's switch is untouched by FT3's Undo.
        assertEquals(AutoChange.TICKET_TARGET, AutoChange.heroTicketOn().target)
    }

    // ---- a day of trading: on paper, its own record ------------------------------------------------------------------------

    @Test fun theFirstQualifyingMarkBuysRs5000OfWholeLotsOnPaperAndItSellsAt1505() {
        // The rule's own pick on this chain at 11:20 is the 25,100 call (a check of the test's numbers).
        val chain = Ft3Rules.Chain("NIFTY", 25_010.0, 50.0, 40.0, listOf(Ft3Rules.Leg(25_100.0, true, 8.0, true), Ft3Rules.Leg(25_050.0, true, 30.0, true)))
        assertEquals(25_100.0, Ft3Rules.pick(signalBar, chain)!!.strike, 0.0)
        assertNull(Ft3Rules.pick(signalBar, chain.copy(legs = listOf(Ft3Rules.Leg(25_100.0, true, 12.0, true)))))
        assertTrue(view().armed)
        // Before 11:01: waiting; the other indices are not on their expiry day.
        market(LocalTime.of(10, 59)); tick(LocalTime.of(10, 59))
        assertEquals("its expiry day: looks at each 5-minute mark 11:00-12:55", Ft3Arm.view.value.status["NIFTY"])
        for (u in listOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")) assertEquals(u, "not its expiry day", Ft3Arm.view.value.status[u])
        // 11:01: the 11:00 mark gave nothing.
        market(LocalTime.of(11, 1)); tick(LocalTime.of(11, 1))
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertEquals("looking: nothing met the rule up to 11:00", Ft3Arm.view.value.status["NIFTY"])
        // 11:21: the 11:05, 11:10 and 11:15 marks gave nothing; the 11:20 mark's 25,100 call is bought on paper.
        market(LocalTime.of(11, 21)); tick(LocalTime.of(11, 21))
        val p = Ft3Arm.view.value.open.single()
        assertTrue(p.symbol, p.symbol.contains("25100"))
        assertEquals("CE", p.right); assertEquals("11:20", p.mark)
        assertEquals(8.0, p.signalPx, 1e-9); assertEquals(1.0, p.distEm, 1e-6); assertTrue(p.ivRatio <= Ft3Rules.IV_REL_MAX)
        assertEquals(0, p.qty % 65)
        assertTrue("Rs 5,000 of whole lots at most: ${p.qty} @ ${p.entry}", p.entry * p.qty <= 5_000.0 + 1e-9)
        assertTrue("as many lots as fit", (p.entry * (p.qty + 65)) > 5_000.0)
        assertTrue(Paper.state.positions.any { it.symbol == p.symbol && it.quantity == p.qty })
        assertTrue(runBlocking { Strategies.owners() }.values.any { it == "${Ft3Rules.OWNER} · entry" })
        assertTrue(Ft3Arm.view.value.status["NIFTY"].orEmpty(), Ft3Arm.view.value.status["NIFTY"].orEmpty().startsWith("bought"))
        assertNothingAtZerodha()
        // Later marks buy nothing more (one ticket an index a day), whatever the chain does.
        market(LocalTime.of(11, 31)); tick(LocalTime.of(11, 31))
        assertEquals(1, Ft3Arm.view.value.open.size + Ft3Arm.view.value.closed.size)
        // No stop on paper: a 70% fall is held.
        market(LocalTime.of(13, 0), held = { 2.4 }); tick(LocalTime.of(13, 0))
        assertEquals(1, Ft3Arm.view.value.open.size)
        // 15:05 sells it at market (or the expiry square-off got there first).
        market(LocalTime.of(15, 5), held = { 2.4 })
        tick(LocalTime.of(15, 4), seconds = 30)
        assertEquals("held to 15:04", 1, Ft3Arm.view.value.open.size)
        tick(LocalTime.of(15, 5), seconds = 2)
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        val sold = Ft3Arm.view.value.closed.single()
        assertEquals("its own 15:05 sale", Ft3Rules.EXIT_WHY, sold.why)
        assertEquals(2.4, sold.exit!!, 0.11)
        assertTrue("${sold.net}", sold.net!! < 0)
        // The forward test's signal record: one bought index-day.
        val sig = view().signals.single()
        assertEquals("bought", sig.outcome); assertEquals("NIFTY", sig.index); assertEquals("11:20", sig.mark); assertEquals(25_100.0, sig.strike!!, 0.0)
        assertEquals(0, Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity })
        assertEquals(ExpirySquareOff.AT_MINUTE, Ft3Rules.EXIT_AT.hour * 60 + Ft3Rules.EXIT_AT.minute)
        // Its own record, read back from its own encrypted file.
        AutomationSupport.reloadFromDisk(Ft3Arm)
        assertEquals(1, view().nets.size)
        assertTrue(HeroTicketArm.view.value.closed.none { it.symbol == p.symbol })
        assertNothingAtZerodha()
    }

    /** Its paper ticket reaches the live path ([ArmLive]) - the buy under its own LiveMirror key with the paper quantity, and the close. */
    @Test fun itsPaperTicketReachesTheLivePathEntryAndClose() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        assertTrue(view().armed)
        market(LocalTime.of(11, 21)); tick(LocalTime.of(11, 21))
        val p = Ft3Arm.view.value.open.single()
        val ref = "ft3:${p.index}:${p.orderId ?: p.entryTime}"
        val entered = heard.toList().filter { it.startsWith("entered|") }
        assertEquals(heard.toString(), listOf("entered|${Ft3Rules.SOURCE}|$ref|${p.qty}|null"), entered)
        assertTrue(ArmLive.legsForTest(com.optionslab.engine.orb.LiveMirror.FT3).orEmpty().contains(ArmLive.PaperLeg(ref, Ft3Rules.SOURCE, p.qty)))
        // No stop level is ever told (the rule has none; the mirror rests only its disaster stop).
        assertTrue(heard.toString(), heard.none { it.startsWith("level|$ref") })
        market(LocalTime.of(15, 5)); tick(LocalTime.of(15, 5), seconds = 2)
        val why = Ft3Arm.view.value.closed.single().why
        assertTrue(heard.toString(), heard.toList().contains("closed|$ref|$why"))
        assertTrue(ArmLive.legsForTest(com.optionslab.engine.orb.LiveMirror.FT3).orEmpty().contains(ArmLive.PaperLeg(ref, Ft3Rules.SOURCE, 0)))
        assertNothingAtZerodha()
    }

    @Test fun aSignalTheWatchWasNotInTimeForIsThatDaysTicketGone() {
        // The phone was asleep: the first pass is at 11:30, after the 11:20 mark's window (11:21-11:23).
        market(LocalTime.of(11, 30))
        val orders = Paper.state.orders.size
        tick(LocalTime.of(11, 30))
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertEquals(orders, Paper.state.orders.size)
        val s = Ft3Arm.view.value.status["NIFTY"].orEmpty()
        assertTrue(s, s.startsWith("no ticket: the 11:20 signal (25100 CE @ 8.00) came while the watch was not in time"))
        assertEquals("missed", view().signals.single().outcome)
        // Never a later mark's ticket instead.
        market(LocalTime.of(11, 41)); tick(LocalTime.of(11, 41))
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertEquals(orders, Paper.state.orders.size)
        assertNothingAtZerodha()
    }

    @Test fun theOrderWindowIsTheThreeMinutesAfterTheMarksBar() {
        // 11:23 is the last minute of the 11:20 mark's window: still bought.
        market(LocalTime.of(11, 23)); tick(LocalTime.of(11, 23), seconds = 40)
        assertEquals(1, Ft3Arm.view.value.open.size)
    }

    @Test fun at1124TheWindowHasGone() {
        market(LocalTime.of(11, 24)); tick(LocalTime.of(11, 24), seconds = 0)
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertTrue(Ft3Arm.view.value.status["NIFTY"].orEmpty().contains("came while the watch was not in time"))
    }

    /** Review: a mark is never judged before the feed has its own minute (the candle feed lags by up to 2 minutes). */
    @Test fun aLaggingFeedIsWaitedForNeverJudgedEarly() {
        // 11:21:20: the index's 11:20 bar is not in the feed yet: the mark waits (it is not written off as "no pick").
        market(LocalTime.of(11, 21), indexUntil = LocalTime.of(11, 20)); tick(LocalTime.of(11, 21))
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertEquals("waiting for the 11:20 minute's prices from the feed", Ft3Arm.view.value.status["NIFTY"])
        // The call's own feed lags too (its 11:20 bar missing): still waiting at 11:21:40.
        market(LocalTime.of(11, 21), callUntil = LocalTime.of(11, 20)); tick(LocalTime.of(11, 21), seconds = 40)
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertEquals("waiting for the 11:20 minute's prices from the feed", Ft3Arm.view.value.status["NIFTY"])
        // 11:22: both feeds caught up - the 11:20 mark is judged and bought inside its window.
        market(LocalTime.of(11, 22)); tick(LocalTime.of(11, 22))
        assertEquals("11:20", Ft3Arm.view.value.open.single().mark)
        assertNothingAtZerodha()
    }

    /** An unfilled LIMIT is cancelled and tried again in the window; the pick survives a restart. */
    @Test fun anUnfilledLimitIsTriedAgainAndThePickSurvivesARestart() {
        // The call is Rs 12 again from 11:21 (its LIMIT is capped at 8 x 1.2 + 0.10 = 9.70): not filled at 11:22.
        val orders = Paper.state.orders.size
        market(LocalTime.of(11, 22), held = { t -> if (t.isBefore(LocalTime.of(11, 22))) 12.0 else 8.0 }); tick(LocalTime.of(11, 22))
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        val st = Ft3Arm.view.value.status["NIFTY"].orEmpty()
        assertTrue(st, st.startsWith("limit 9.70 for") && st.contains("tried again until 11:24"))
        assertTrue("the unfilled order was cancelled", Paper.state.orders.drop(orders).all { it.status != "open" })
        // A restart: the day's pick is kept in the book.
        Ft3Arm.wipe(); AutomationSupport.reloadFromDisk(Ft3Arm); view()
        // 11:23: back at Rs 8 - bought from the kept pick.
        market(LocalTime.of(11, 23), held = { t -> if (t.isBefore(LocalTime.of(11, 22))) 12.0 else 8.0 }); tick(LocalTime.of(11, 23))
        val p = Ft3Arm.view.value.open.single()
        assertEquals("11:20", p.mark); assertEquals(8.0, p.signalPx, 0.0)
        assertNothingAtZerodha()
    }

    @Test fun aSignalNeverFilledInTheWindowIsCountedUnfilled() {
        market(LocalTime.of(11, 24), held = { 12.0 }); tick(LocalTime.of(11, 22)); tick(LocalTime.of(11, 23))
        tick(LocalTime.of(11, 24))
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertTrue(Ft3Arm.view.value.status["NIFTY"].orEmpty().contains("was not bought by 11:24"))
        assertEquals("unfilled", view().signals.single().outcome)
    }

    @Test fun theStopForTodayRefusesTheTicketAndSellsWhatIsHeld() {
        market(LocalTime.of(11, 21)); tick(LocalTime.of(11, 21))
        assertEquals(1, Ft3Arm.view.value.open.size)
        runBlocking { Strategies.stopForToday(stopRunning = false, compromised = false) }
        market(LocalTime.of(11, 40)); tick(LocalTime.of(11, 40))
        assertEquals("operator_stop", Ft3Arm.view.value.closed.single().why)
        assertNothingAtZerodha()
    }

    @Test fun noMarkMetTheRuleMeansNoTicketTodayAndTheDayIsDecidedAt1259() {
        market(LocalTime.of(12, 59), signalAt = LocalTime.of(23, 0))
        tick(LocalTime.of(12, 30))
        assertEquals("looking: nothing met the rule up to 12:25", Ft3Arm.view.value.status["NIFTY"])
        assertTrue(Ft3Arm.wantsPass(12 * 60 + 30))
        tick(LocalTime.of(12, 59))
        assertEquals("no ticket today: no option met the rule at any mark 11:00-12:55", Ft3Arm.view.value.status["NIFTY"])
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertFalse("every index decided: no pass wanted", Ft3Arm.wantsPass(12 * 60 + 59))
        assertNothingAtZerodha()
    }

    @Test fun aChainThatCannotBeReadIsJudgedAgainOnTheNextPass() {
        market(LocalTime.of(11, 21))
        upstox.down = true
        tick(LocalTime.of(11, 21))
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertEquals("the chain could not be read just now; trying again", Ft3Arm.view.value.status["NIFTY"])
        upstox.down = false
        tick(LocalTime.of(11, 22))
        assertEquals("bought within the 11:20 mark's window", 1, Ft3Arm.view.value.open.size)
        assertNothingAtZerodha()
    }

    @Test fun theMinuteLaneOnlyManagesWhatIsHeldAndTheWatchWakesItOnlyWhenNeeded() {
        assertTrue(view().armed)
        assertFalse(Ft3Arm.wantsPass(10 * 60))
        assertTrue(Ft3Arm.wantsPass(10 * 60 + 55)); assertTrue(Ft3Arm.wantsPass(12 * 60 + 59))
        assertTrue("slack for the feed lag", Ft3Arm.wantsPass(13 * 60 + 5)); assertFalse(Ft3Arm.wantsPass(13 * 60 + 6))
        assertFalse(Ft3Arm.wantsPass(10 * 60 + 54))
        // The minute lane never buys.
        market(LocalTime.of(11, 21)); at(LocalTime.of(11, 21)); runBlocking { Ft3Arm.manageOnly() }
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        tick(LocalTime.of(11, 21))
        assertEquals(1, Ft3Arm.view.value.open.size)
        assertTrue(Ft3Arm.wantsPass(14 * 60))
        // The minute lane sells it at 15:05.
        market(LocalTime.of(15, 5)); at(LocalTime.of(15, 5), seconds = 2); runBlocking { Ft3Arm.manageOnly() }
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        // Off with nothing held: never wanted.
        runBlocking { Ft3Arm.setArmed(false) }
        assertFalse(Ft3Arm.wantsPass(11 * 60 + 30))
        assertNothingAtZerodha()
    }

    // ---- open positions first: a restart in the middle of a trade -----------------------------------------------------------

    @Test fun aRestartMidTradeRestoresTheTicketFirstAndKeepsWatchingIt() {
        market(LocalTime.of(11, 21)); tick(LocalTime.of(11, 21))
        assertEquals(1, Ft3Arm.view.value.open.size)
        Ft3Arm.wipe()
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertTrue("something open: the watch is started first", runBlocking { PositionsFirst.restore() })
        assertEquals(1, Ft3Arm.view.value.open.size)
        assertTrue("its minute lane keeps running", Ft3Arm.holding())
        // Switched off, what it holds still sells at 15:05.
        runBlocking { Ft3Arm.setArmed(false) }
        market(LocalTime.of(15, 5)); tick(LocalTime.of(15, 5), seconds = 2)
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertNothingAtZerodha()
    }

    // ---- refusals and other days --------------------------------------------------------------------------------------------

    @Test fun theKillSwitchRefusesTheDaysTicket() {
        TradeFixtures.killSwitch(true)
        market(LocalTime.of(11, 21))
        val orders = Paper.state.orders.size
        tick(LocalTime.of(11, 21))
        assertEquals(orders, Paper.state.orders.size)
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertTrue(Ft3Arm.view.value.status["NIFTY"].orEmpty(), Ft3Arm.view.value.status["NIFTY"].orEmpty().contains("kill switch"))
        assertNothingAtZerodha()
    }

    @Test fun offItBuysNothing() {
        runBlocking { Ft3Arm.setArmed(false) }
        market(LocalTime.of(11, 21))
        val orders = Paper.state.orders.size
        tick(LocalTime.of(11, 21))
        assertEquals(orders, Paper.state.orders.size)
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun aNonExpiryDayIsNeverATicketDay() {
        AutomationSupport.contracts(context, listOf(Upstox.Contract("NIFTY", day.plusDays(1), 25_100.0, Right.CE, 65, ce(25_100), "NIFTY-F3-X")))
        assertTrue(view().armed)
        market(LocalTime.of(11, 21)); tick(LocalTime.of(11, 21))
        assertTrue(Ft3Arm.view.value.open.isEmpty())
        assertEquals("not its expiry day", Ft3Arm.view.value.status["NIFTY"])
        assertNothingAtZerodha()
    }

    @Test fun onSensexsExpiryDayItBuysThroughTheBseList() {
        // Today is SENSEX's own expiry (BSE's list); NIFTY's is next week.
        AutomationSupport.contracts(context, listOf(Upstox.Contract("NIFTY", day.plusDays(7), 25_000.0, Right.CE, 65, "NSE_FO|F3NEXTCE", "NIFTY-F3-NEXT")))
        val sc = mapOf(82_000 to 160.0, 82_100 to 9.0, 82_200 to 2.0); val sp = mapOf(82_000 to 150.0, 81_900 to 30.0, 81_800 to 3.0)
        val bfo = sc.map { (k, _) -> Upstox.Contract("SENSEX", day, k.toDouble(), Right.CE, 20, "BSE_FO|F3S${k}CE", "SENSEX-F3-${k}CE") } +
            sp.map { (k, _) -> Upstox.Contract("SENSEX", day, k.toDouble(), Right.PE, 20, "BSE_FO|F3S${k}PE", "SENSEX-F3-${k}PE") }
        Market.saveBfo(bfo)
        // The check of the test's numbers: the rule takes the 82,100 call at the 11:00 mark.
        val chain = Ft3Rules.Chain("SENSEX", 82_010.0, 160.0, 150.0, listOf(Ft3Rules.Leg(82_100.0, true, 9.0, true)))
        assertNotNull(Ft3Rules.pick(LocalTime.of(11, 0), chain))
        minutes(sensexKey, LocalTime.of(11, 1)) { 82_010.0 }
        for ((k, p) in sc) minutes("BSE_FO|F3S${k}CE", LocalTime.of(11, 1)) { p }
        for ((k, p) in sp) minutes("BSE_FO|F3S${k}PE", LocalTime.of(11, 1)) { p }
        tick(LocalTime.of(11, 1))
        val p = Ft3Arm.view.value.open.single()
        assertEquals("SENSEX", p.index); assertTrue(p.symbol, p.symbol.contains("82100"))
        assertEquals(0, p.qty % 20); assertTrue(p.entry * p.qty <= 5_000.0 + 1e-9)
        assertNothingAtZerodha()
    }

    @Test fun eachIndexReadsItsOwnFeed() {
        assertEquals("NSE_INDEX|Nifty 50", Ft3Arm.indexKey("NIFTY"))
        assertEquals("NSE_INDEX|Nifty Bank", Ft3Arm.indexKey("BANKNIFTY"))
        assertEquals("NSE_INDEX|Nifty Fin Service", Ft3Arm.indexKey("FINNIFTY"))
        assertEquals("NSE_INDEX|NIFTY MID SELECT", Ft3Arm.indexKey("MIDCPNIFTY"))
        assertEquals("BSE_INDEX|SENSEX", Ft3Arm.indexKey("SENSEX"))
        // The forward fill as R98's: the last close within the window, never one older.
        fun bar(c: Double) = Upstox.Bar(0L, c, c, c, c, 1, 0)
        val bars = mapOf(LocalTime.of(11, 0) to bar(10.0), LocalTime.of(11, 2) to bar(12.0))
        assertEquals(12.0, Ft3Arm.lastClose(bars, LocalTime.of(11, 4), 3)!!, 0.0)
        assertEquals(10.0, Ft3Arm.lastClose(bars, LocalTime.of(11, 1), 3)!!, 0.0)
        assertNull(Ft3Arm.lastClose(bars, LocalTime.of(11, 6), 3))
        assertNull(Ft3Arm.lastClose(bars, LocalTime.of(10, 59), 3))
    }

    @Test fun theBookIsItsOwnEncryptedFileNotInTheBackup() {
        view()
        assertTrue(bookFile.exists())
        val o = JSONObject(String(Vault.readFileSteady(bookFile)!!, Charsets.UTF_8))
        assertTrue(o.getBoolean("armed"))
        assertTrue(o.getJSONArray("migrated").toString().contains(Ft3Arm.AUTO_ON))
        assertFalse("not plain text on disk", bookFile.readText(Charsets.ISO_8859_1).contains("\"armed\""))
        // The uploaded day file never holds its rupee record.
        assertTrue(Ft3Arm.diagLine(hideNet = true).contains(PassedArmsHost.NET_HIDDEN))
    }
}
