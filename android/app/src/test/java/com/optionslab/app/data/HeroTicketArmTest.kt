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
import com.optionslab.engine.orb.HeroTicketRules
import com.optionslab.ira.AutoChange
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
 * "Hero ₹1,000 ticket" (15 Oct, Boss's choice; it did not pass research R50) is PAPER ONLY: with the app in Live, logged in to
 * Zerodha, real orders allowed and "AI trades go live" on, NIFTY's own expiry day buys at 12:30 one lot of the call and one of
 * the put whose lot costs at most Rs 1,000 - on paper, not one order at Zerodha - and sells them on Boss's 25% trail, a 2x / 3x
 * target Boss picked, or 15:05. It is switched on once by the update (logged once as Boss's change, undone in one tap), keeps
 * its trades in its own book, is restored first after a restart, and the kill switch, the bot's stop and a stale price refuse it.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class HeroTicketArmTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val niftyKey = Upstox.INDEX_KEYS.getValue("NIFTY")
    private val sensexKey = "BSE_INDEX|SENSEX"
    private val bookFile get() = File(context.filesDir, "hero_ticket.vault")

    private fun ce(k: Int) = "NSE_FO|HT${k}CE"
    private fun pe(k: Int) = "NSE_FO|HT${k}PE"

    /** NIFTY's calls 25,050-25,400 and puts 25,000-24,700 at 12:30 (lot 65): the 25,150 CE (Rs 12) and 24,900 PE (Rs 11) fit Rs 1,000. */
    private val callPx = mapOf(25_050 to 28.0, 25_100 to 16.0, 25_150 to 12.0, 25_200 to 9.0, 25_250 to 6.5, 25_300 to 4.0, 25_350 to 3.0, 25_400 to 2.0)
    private val putPx = mapOf(25_000 to 31.0, 24_950 to 18.0, 24_900 to 11.0, 24_850 to 8.0, 24_800 to 4.0, 24_750 to 3.0, 24_700 to 2.0)

    private fun at(t: LocalTime, seconds: Long = 20) {
        Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(seconds).atZone(IST).toInstant(), IST)
    }

    private fun tick(t: LocalTime, seconds: Long = 20) { at(t, seconds); runBlocking { HeroTicketArm.tick() } }

    /** 1-minute candles of [key] from 09:15 to [until] (exclusive), each closing at [px] (by the minute it STARTS). */
    private fun minutes(key: String, until: LocalTime, px: (LocalTime) -> Double) {
        val out = ArrayList<FakeUpstox.Candle>()
        var t = LocalTime.of(9, 15)
        while (t.isBefore(until)) { val c = px(t); out += FakeUpstox.Candle(t, c, c, c, c); t = t.plusMinutes(1) }
        upstox.minutes[key] = out
    }

    /**
     * The market to [until]: NIFTY flat at 25,010, every option at its 12:30 price until 12:30; after it the bought call follows
     * [call] and the bought put [put] (by the minute each bar starts).
     */
    private fun market(until: LocalTime, call: (LocalTime) -> Double = { 12.0 }, put: (LocalTime) -> Double = { 11.0 }, index: Boolean = true) {
        if (index) minutes(niftyKey, until) { 25_010.0 } else upstox.minutes[niftyKey] = emptyList()
        val split = LocalTime.of(12, 30)
        for ((k, p) in callPx) minutes(ce(k), until) { t -> if (k == 25_150 && !t.isBefore(split)) call(t) else p }
        for ((k, p) in putPx) minutes(pe(k), until) { t -> if (k == 24_900 && !t.isBefore(split)) put(t) else p }
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
        day = AutomationSupport.tradingDayAt(12, 30).toLocalDate()
        at(LocalTime.of(9, 20))
        // Today is NIFTY's own expiry (and next week's is listed); SENSEX expires in two days (BSE's own list).
        val list = ArrayList<Upstox.Contract>()
        for (k in callPx.keys) list += Upstox.Contract("NIFTY", day, k.toDouble(), Right.CE, 65, ce(k), "NIFTY-HT-${k}CE")
        for (k in putPx.keys) list += Upstox.Contract("NIFTY", day, k.toDouble(), Right.PE, 65, pe(k), "NIFTY-HT-${k}PE")
        list += Upstox.Contract("NIFTY", day.plusDays(7), 25_000.0, Right.CE, 65, "NSE_FO|HTNEXTCE", "NIFTY-HT-NEXT")
        AutomationSupport.contracts(context, list)
        Market.saveBfo(listOf(Upstox.Contract("SENSEX", day.plusDays(2), 72_500.0, Right.CE, 20, "BSE_FO|HTS72500CE", "SENSEX-HT-72500CE")))
        HeroTicketArm.testNoAutoOn = false
        kite.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        HeroTicketArm.testNoAutoOn = true
        if (Autopilot.enabled()) Autopilot.resetForTest()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun view() = runBlocking { HeroTicketArm.refresh() }

    /** Not one order, modify or cancel was sent to Zerodha. */
    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    // ---- on after the update, once; logged once; undone in one tap -----------------------------------------------------------

    @Test fun theUpdateSwitchesItOnOnceOnPaperAndNeverAgain() {
        val v = view()
        assertTrue("on after the update", v.armed)
        assertEquals(HeroTicketRules.Target.TRAIL_ONLY, v.target)
        assertTrue(v.log.toString(), v.log.single { "switched on once by the 15 Oct update" in it }.contains(HeroTicketRules.BOSS_CHOICE))
        assertTrue(HeroTicketArm.diagLine().contains("on (paper only)") && HeroTicketArm.diagLine().contains(HeroTicketRules.BOSS_CHOICE))
        // Boss switches it off; a restart never switches it on again.
        assertEquals("${HeroTicketRules.LABEL} off.", runBlocking { HeroTicketArm.setArmed(false) })
        AutomationSupport.reloadFromDisk(HeroTicketArm)
        assertFalse(view().armed)
        assertEquals(1, view().log.count { "switched on once" in it })
        // A book that cannot be read is never switched on by itself.
        HeroTicketArm.wipe(); bookFile.writeText("not a vault"); AutomationSupport.reloadFromDisk(HeroTicketArm)
        assertFalse(view().armed)
    }

    @Test fun itIsInTheChangeLogOnceAsBossOwnAndOneTapSwitchesItOff() {
        org.junit.Assume.assumeTrue("the change log is in IraAlgo with Jarvis only", Autopilot.enabled())
        view()
        fun entries() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.PAPER_ARM_ON }
        val deadline = System.currentTimeMillis() + 10_000
        while (entries().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val e = entries().single()
        assertEquals(AutoChange.TICKET_TARGET, e.change.target)
        assertEquals(AutoChange.Status.KEPT, e.status)
        assertEquals("your choice", e.by)
        assertFalse("no PIN: paper only", Autopilot.undoNeedsPin(e.id))
        // A restart does not log it twice.
        AutomationSupport.reloadFromDisk(HeroTicketArm); view(); Thread.sleep(200)
        assertEquals(1, entries().size)
        val said = Autopilot.undo(e.id)
        assertTrue(said, said.startsWith("Undone"))
        assertFalse(view().armed)
        assertEquals(AutoChange.Status.UNDONE, entries().single().status)
    }

    /** The change log's Undo, through Autopilot's own setter (in every build: CI runs the app tests without Jarvis). */
    @Test fun theUndosSetterSwitchesItOffAndOnlyIt() {
        assertTrue(view().armed)
        val on = AutoChange.heroTicketOn()
        // Never another target; the change itself (on) applies only as an undo of an undo.
        assertNull(Autopilot.apply(on.copy(target = "paperarm:other"), false))
        val off = Autopilot.apply(AutoChange.inverse(on, "undone by you"), false)
        assertEquals("${HeroTicketRules.LABEL} off.", off)
        assertFalse(view().armed)
        // 22 Oct: the diary says which door switched it (the Undo here; Boss's row below).
        assertTrue(view().log.toString(), view().log.last().endsWith("switched off - by the Undo in Autopilot's change log"))
        assertTrue(Autopilot.apply(on, false)!!.contains("paper only"))
        assertTrue(view().armed)
        assertTrue(view().log.last(), view().log.last().endsWith("- by the Undo in Autopilot's change log"))
        runBlocking { HeroTicketArm.setArmed(false) }
        assertTrue(view().log.last(), view().log.last().endsWith("switched off - by you, from its row on Home"))
    }

    // ---- a day of trading: paper only, its own record --------------------------------------------------------------------

    @Test fun onNiftysExpiryDayItBuysBothTicketsAt1230OnPaperOnlyAndTheTrailSellsThem() {
        assertTrue(view().armed)
        // R50's trail from the fill (the default is after 2x, a placeholder until Boss chooses: tested below).
        assertTrue(runBlocking { HeroTicketArm.setTrailStart(HeroTicketRules.TrailStart.AT_FILL) }.contains("from the fill"))
        // Before 12:30: waiting; SENSEX is not on its expiry day.
        market(LocalTime.of(11, 0)); tick(LocalTime.of(11, 0))
        assertTrue(HeroTicketArm.view.value.open.isEmpty())
        assertEquals("its expiry day: buys at 12:30", HeroTicketArm.view.value.status["NIFTY"])
        assertEquals("not its expiry day", HeroTicketArm.view.value.status["SENSEX"])
        // 12:30: the 25,150 CE (Rs 12 x 65 = Rs 780) and the 24,900 PE (Rs 11 x 65 = Rs 715), 1 lot each, on paper.
        market(LocalTime.of(12, 30)); tick(LocalTime.of(12, 30))
        val open = HeroTicketArm.view.value.open
        assertEquals(2, open.size)
        val call = open.single { it.right == "CE" }; val put = open.single { it.right == "PE" }
        assertTrue(call.symbol, call.symbol.contains("25150")); assertTrue(put.symbol, put.symbol.contains("24900"))
        assertEquals(65, call.qty); assertEquals(65, put.qty)
        assertTrue("never more than Rs 1,000 a ticket", call.entry * call.qty <= 1_000.0 && put.entry * put.qty <= 1_000.0)
        assertTrue("paid the spread", call.entry >= 12.0)
        assertEquals("trail", call.target)
        assertTrue(Paper.state.positions.any { it.symbol == call.symbol && it.quantity == 65 })
        assertTrue(runBlocking { Strategies.owners() }.values.any { it == "${HeroTicketRules.OWNER} · entry" })
        assertNothingAtZerodha()
        // Later passes buy nothing more (one ticket a side a day).
        market(LocalTime.of(12, 32), call = { 13.0 }, put = { 10.5 }); tick(LocalTime.of(12, 32))
        assertEquals(2, HeroTicketArm.view.value.open.size)
        // The call runs to 30 by 13:00; the put falls to 8.0 (more than 25% under its fill): the trail sells the put.
        market(LocalTime.of(13, 0), call = { t -> if (t.isBefore(LocalTime.of(12, 50))) 20.0 else 30.0 }, put = { t -> if (t.isBefore(LocalTime.of(12, 40))) 10.0 else 8.0 })
        tick(LocalTime.of(13, 0))
        val soldPut = HeroTicketArm.view.value.closed.single()
        assertEquals("PE", soldPut.right); assertEquals("ticket_trail", soldPut.why)
        assertEquals(1, HeroTicketArm.view.value.open.size)
        // The call falls to 22 (under 0.75 x 30 = 22.5): the trail sells it, well in profit.
        market(LocalTime.of(13, 20), call = { t -> if (t.isBefore(LocalTime.of(13, 0))) 30.0 else 22.0 }, put = { 8.0 })
        tick(LocalTime.of(13, 20))
        assertTrue(HeroTicketArm.view.value.open.isEmpty())
        val soldCall = HeroTicketArm.view.value.closed.single { it.right == "CE" }
        assertEquals("ticket_trail", soldCall.why)
        assertTrue("${soldCall.net}", soldCall.net!! > 0)
        assertEquals(2, HeroTicketArm.view.value.nets.size)
        assertEquals(0, Paper.state.positions.filter { it.symbol == call.symbol }.sumOf { it.quantity })
        // Its own record, read back from its own encrypted file - apart from every other arm's book.
        AutomationSupport.reloadFromDisk(HeroTicketArm)
        assertEquals(2, view().nets.size)
        assertTrue(VixDivArm.view.value.closed.none { it.symbol == call.symbol })
        assertNothingAtZerodha()
    }

    /**
     * 18 Oct (review D8): its paper tickets reach the live path ([ArmLive]) - each buy under its own LiveMirror key with the
     * paper quantity, the trail's level as it moves, and the close, all by the same reference; its book shows the sold leg at 0.
     */
    @Test fun itsPaperTicketsReachTheLivePathEntryTrailAndClose() {
        val heard = java.util.Collections.synchronizedList(ArrayList<String>())
        ArmLive.testHeard = heard
        assertTrue(view().armed)
        assertTrue(runBlocking { HeroTicketArm.setTrailStart(HeroTicketRules.TrailStart.AT_FILL) }.contains("from the fill"))
        // 12:30: both tickets bought on paper (65 each); each is told to the live path under the ticket's own key.
        market(LocalTime.of(12, 30)); tick(LocalTime.of(12, 30))
        val put = HeroTicketArm.view.value.open.single { it.right == "PE" }
        assertEquals(65, put.qty)
        val ref = "hero_ticket:${put.index}:${put.orderId ?: put.entryTime}"
        val entered = heard.toList().filter { it.startsWith("entered|") }
        assertEquals(heard.toString(), 2, entered.size)
        assertTrue(heard.toString(), entered.all { it.split("|")[1] == HeroTicketRules.SOURCE })
        assertTrue(heard.toString(), entered.any { it.startsWith("entered|${HeroTicketRules.SOURCE}|$ref|${put.qty}|") })
        assertTrue(ArmLive.legsForTest(com.optionslab.engine.orb.LiveMirror.HERO_TICKET).orEmpty()
            .contains(ArmLive.PaperLeg(ref, HeroTicketRules.SOURCE, 65)))
        // The put falls to 8.0 (more than 25% under its fill): the trail's level was told, then the trail sells it on paper.
        market(LocalTime.of(13, 0), call = { 12.0 }, put = { t -> if (t.isBefore(LocalTime.of(12, 40))) 10.0 else 8.0 })
        tick(LocalTime.of(13, 0))
        assertEquals("ticket_trail", HeroTicketArm.view.value.closed.single { it.right == "PE" }.why)
        assertTrue(heard.toString(), heard.toList().any { it.startsWith("level|$ref|") })
        assertTrue(heard.toString(), heard.toList().contains("closed|$ref|ticket_trail"))
        assertTrue(heard.toString(), heard.indexOfFirst { it.startsWith("entered|${HeroTicketRules.SOURCE}|$ref|") } < heard.indexOf("closed|$ref|ticket_trail"))
        assertTrue(ArmLive.legsForTest(com.optionslab.engine.orb.LiveMirror.HERO_TICKET).orEmpty()
            .contains(ArmLive.PaperLeg(ref, HeroTicketRules.SOURCE, 0)))
        assertNothingAtZerodha()
    }

    @Test fun aTwoTimesTargetBossPickedSellsAtTheTargetAndAGapThroughIsBookedThere() {
        assertTrue(runBlocking { HeroTicketArm.setTarget(HeroTicketRules.Target.X2) }.contains("2× target"))
        // The setting is kept (a restart reads it back).
        AutomationSupport.reloadFromDisk(HeroTicketArm)
        assertEquals(HeroTicketRules.Target.X2, view().target)
        market(LocalTime.of(12, 30)); tick(LocalTime.of(12, 30))
        val call = HeroTicketArm.view.value.open.single { it.right == "CE" }
        assertEquals("2x", call.target)
        val level = HeroTicketRules.targetLevel(call.entry, HeroTicketRules.Target.X2)!!
        // The target rests in the paper book as a LIMIT SELL at 2x (any tick through it fills it there).
        val resting = Paper.state.orders.single { it.orderId == call.targetOrderId }
        assertEquals("LIMIT", resting.priceType); assertEquals("SELL", resting.action); assertEquals(level, resting.price!!.toDouble(), 1e-9)
        assertEquals("open", resting.status)
        // The call leaps to 3.5x in one minute (a gap through 2x): the paper book fills the target at 2x, never the gap's better
        // price, and the arm books it so.
        market(LocalTime.of(12, 40), call = { t -> if (t.isBefore(LocalTime.of(12, 38))) 13.0 else call.entry * 3.5 }, put = { 11.0 })
        at(LocalTime.of(12, 40)); runBlocking { Paper.tick() }
        tick(LocalTime.of(12, 40))
        val sold = HeroTicketArm.view.value.closed.single { it.right == "CE" }
        assertEquals("ticket_target", sold.why)
        assertEquals(level, sold.exit!!, 1e-9)
        assertEquals(0, Paper.state.positions.filter { it.symbol == call.symbol }.sumOf { it.quantity })
        // The put (no move) is still held.
        assertEquals("PE", HeroTicketArm.view.value.open.single().right)
        assertNothingAtZerodha()
    }

    /**
     * 16 Oct (research R63): T3 passed for this ticket, so when NIFTY closes twice below the day's average after 12:40 the Trend
     * watch asks for the CALL leg to be sold, and the arm sells it on paper through its own exit ("trend_turn") - the put, with
     * the trend, is kept; nothing reaches Zerodha; the sale is in Autopilot's change log, and its Undo switches the exit off.
     */
    @Test fun theTrendWatchSellsTheLegTheIndexTurnedAgainstThroughTheArmsOwnExit() {
        market(LocalTime.of(12, 30)); tick(LocalTime.of(12, 30))
        val call = HeroTicketArm.view.value.open.single { it.right == "CE" }
        val put = HeroTicketArm.view.value.open.single { it.right == "PE" }
        TrendWatchHost.resetForTest(); TrendWatchHost.init(context)
        // NIFTY flat at 25,010 from 09:15, then down 2 points a minute from 12:40 (finished minutes only).
        TrendWatchHost.testMinutes = { _, n -> (0 until 375).map { r -> day.atTime(9, 15).plusMinutes(r.toLong()) }.filter { !it.plusMinutes(1).isAfter(n) }
            .map { t -> val c = if (t.toLocalTime().isBefore(LocalTime.of(12, 40))) 25_010.0 else 25_010.0 - 2.0 * (java.time.Duration.between(day.atTime(12, 40), t).toMinutes() + 1)
                com.optionslab.engine.orb.Bar(t, c, c + 1, c - 1, c) } }
        market(LocalTime.of(12, 52))
        at(LocalTime.of(12, 46)); runBlocking { TrendWatchHost.round(day.atTime(12, 46, 20)) }
        assertFalse("not yet: one close below", TrendWatchHost.sellRequested(call.symbol))
        at(LocalTime.of(12, 51)); runBlocking { TrendWatchHost.round(day.atTime(12, 51, 20)) }
        assertTrue(TrendWatchHost.sellRequested(call.symbol))
        assertFalse("the put is with the trend", TrendWatchHost.sellRequested(put.symbol))
        assertTrue(TrendWatchHost.view.value.recent.single().line.contains("selling on paper at about"))
        // The arm's own minute lane sells it.
        at(LocalTime.of(12, 51)); runBlocking { HeroTicketArm.manageOnly() }
        val sold = HeroTicketArm.view.value.closed.single { it.right == "CE" }
        assertEquals(HeroTicketArm.TREND_WHY, sold.why)
        assertEquals(put.symbol, HeroTicketArm.view.value.open.single().symbol)
        assertEquals(0, Paper.state.positions.filter { it.symbol == call.symbol }.sumOf { it.quantity })
        assertFalse(TrendWatchHost.sellRequested(call.symbol))
        assertNothingAtZerodha()
        if (Autopilot.enabled()) {
            val end = System.currentTimeMillis() + 10_000
            fun entries() = Autopilot.book.value.entries.filter { it.change.kind == AutoChange.Kind.TREND_EXIT }
            while (entries().none { it.change.to == AutoChange.SOLD_ON_TURN } && System.currentTimeMillis() < end) Thread.sleep(50)
            val e = entries().last { it.change.to == AutoChange.SOLD_ON_TURN }
            assertTrue(e.change.reason, e.change.reason.contains(call.symbol))
            assertTrue(entries().any { it.change.to == AutoChange.ON })     // the one-time switch-on under the PAPER rule
            // One tap: the Hero trend exit off; the leg stays sold.
            assertTrue(Autopilot.undo(e.id).contains("The leg sold stays sold"))
            assertFalse(TrendWatchHost.switches().acts(com.optionslab.engine.trend.TrendWatch.Source.HERO_TICKET))
        }
        // 22 Oct ("switched off" at 13:02 right after the Trend watch sold both tickets): neither the sale nor its Undo ever switches
        // the arm itself off; the later passes leave it on.
        at(LocalTime.of(12, 53)); runBlocking { HeroTicketArm.manageOnly(); HeroTicketArm.tick() }
        assertTrue("the arm stays on", HeroTicketArm.view.value.armed)
        assertTrue(HeroTicketArm.view.value.log.toString(), HeroTicketArm.view.value.log.none { "switched off" in it })
        TrendWatchHost.resetForTest()
    }

    @Test fun theTrailSellsBeforeATargetThatWasNeverReachedAndTheRestingTargetIsTakenOut() {
        runBlocking { HeroTicketArm.setTarget(HeroTicketRules.Target.X3) }
        runBlocking { HeroTicketArm.setTrailStart(HeroTicketRules.TrailStart.AT_FILL) }
        market(LocalTime.of(12, 30)); tick(LocalTime.of(12, 30))
        val put = HeroTicketArm.view.value.open.single { it.right == "PE" }
        val id = put.targetOrderId!!
        // Up to 1.8x, then 25% off its best: the trail sells it; the 3x target never filled and is cancelled, nothing sold twice.
        market(LocalTime.of(13, 0), put = { t -> if (t.isBefore(LocalTime.of(12, 45))) put.entry * 1.8 else put.entry * 1.3 })
        tick(LocalTime.of(13, 0))
        val sold = HeroTicketArm.view.value.closed.single { it.right == "PE" }
        assertEquals("ticket_trail", sold.why)
        assertTrue("${sold.net}", sold.net!! > 0)
        assertTrue(Paper.state.orders.single { it.orderId == id }.status != "open")
        assertEquals(0, Paper.state.positions.filter { it.symbol == put.symbol }.sumOf { it.quantity })
        assertNothingAtZerodha()
    }

    @Test fun theMinuteLaneOnlyManagesWhatIsHeldAndTheWatchWakesItOnlyWhenNeeded() {
        assertTrue(view().armed)
        runBlocking { HeroTicketArm.setTrailStart(HeroTicketRules.TrailStart.AT_FILL) }
        // Switched on, nothing held: no pass wanted outside 12:25-12:33 (no wake, no minute lane, no safety-net round).
        assertFalse(HeroTicketArm.wantsPass(10 * 60))
        assertTrue(HeroTicketArm.wantsPass(12 * 60 + 25)); assertTrue(HeroTicketArm.wantsPass(12 * 60 + 33))
        assertFalse(HeroTicketArm.wantsPass(12 * 60 + 34)); assertFalse(HeroTicketArm.wantsPass(12 * 60 + 24))
        // The minute lane never buys: at 12:30 with nothing held it does nothing.
        market(LocalTime.of(12, 30)); at(LocalTime.of(12, 30)); runBlocking { HeroTicketArm.manageOnly() }
        assertTrue(HeroTicketArm.view.value.open.isEmpty())
        tick(LocalTime.of(12, 30))
        assertEquals(2, HeroTicketArm.view.value.open.size)
        // Held: wanted at every minute, and the minute lane sells on the trail.
        assertTrue(HeroTicketArm.wantsPass(14 * 60))
        market(LocalTime.of(12, 50), put = { t -> if (t.isBefore(LocalTime.of(12, 45))) 11.0 else 7.0 })
        at(LocalTime.of(12, 50)); runBlocking { HeroTicketArm.manageOnly() }
        assertEquals("ticket_trail", HeroTicketArm.view.value.closed.single().why)
        // Switched off with nothing held: never wanted.
        runBlocking { HeroTicketArm.setArmed(false) }
        market(LocalTime.of(13, 10), call = { t -> if (t.isBefore(LocalTime.of(13, 0))) 12.0 else 8.0 }, put = { 7.0 })
        at(LocalTime.of(13, 10)); runBlocking { HeroTicketArm.manageOnly() }
        assertTrue(HeroTicketArm.view.value.open.isEmpty())
        assertFalse(HeroTicketArm.wantsPass(12 * 60 + 30))
        assertNothingAtZerodha()
    }

    @Test fun afterTwoTimesTheTrailHasNoStopBelowAndArmsOnlyOnceTheTicketHasDoubled() {
        // The default (a placeholder until Boss chooses, R55): the trail starts after 2x. Each ticket keeps its own start.
        assertEquals(HeroTicketRules.TrailStart.AFTER_2X, view().trailStart)
        market(LocalTime.of(12, 30)); tick(LocalTime.of(12, 30))
        val put = HeroTicketArm.view.value.open.single { it.right == "PE" }
        assertEquals("2x", put.trailStart)
        // The put falls 60%: no stop below 2x - held (the most it can lose is the ticket).
        market(LocalTime.of(13, 0), put = { put.entry * 0.4 }); tick(LocalTime.of(13, 0))
        assertEquals(2, HeroTicketArm.view.value.open.size)
        // The call runs to 2.5x (armed), then falls 25% off its best: sold on the trail, in profit.
        val call = HeroTicketArm.view.value.open.single { it.right == "CE" }
        market(LocalTime.of(13, 30), call = { t -> if (t.isBefore(LocalTime.of(13, 10))) call.entry * 2.5 else call.entry * 1.8 }, put = { put.entry * 0.4 })
        tick(LocalTime.of(13, 30))
        val sold = HeroTicketArm.view.value.closed.single()
        assertEquals("CE", sold.right); assertEquals("ticket_trail", sold.why); assertTrue("${sold.net}", sold.net!! > 0)
        // A setting change later moves only new tickets: the held put keeps after 2x.
        runBlocking { HeroTicketArm.setTrailStart(HeroTicketRules.TrailStart.AT_FILL) }
        AutomationSupport.reloadFromDisk(HeroTicketArm)
        assertEquals(HeroTicketRules.TrailStart.AT_FILL, view().trailStart)
        assertEquals("2x", HeroTicketArm.view.value.open.single().trailStart)
        market(LocalTime.of(14, 0), put = { put.entry * 0.4 }); tick(LocalTime.of(14, 0))
        assertEquals(1, HeroTicketArm.view.value.open.size)
        // 15:05 squares it off.
        market(LocalTime.of(15, 5), put = { put.entry * 0.4 }); tick(LocalTime.of(15, 5), seconds = 2)
        assertEquals("ticket_1505", HeroTicketArm.view.value.closed.single { it.right == "PE" }.why)
        assertNothingAtZerodha()
    }

    @Test fun afterThreeTimesWaitsForThreeTimes() {
        runBlocking { HeroTicketArm.setTrailStart(HeroTicketRules.TrailStart.AFTER_3X) }
        market(LocalTime.of(12, 30)); tick(LocalTime.of(12, 30))
        val call = HeroTicketArm.view.value.open.single { it.right == "CE" }
        assertEquals("3x", call.trailStart)
        // 2.5x then a 30% fall: not armed under 3x, held.
        market(LocalTime.of(13, 0), call = { t -> if (t.isBefore(LocalTime.of(12, 45))) call.entry * 2.5 else call.entry * 1.7 })
        tick(LocalTime.of(13, 0))
        assertEquals(2, HeroTicketArm.view.value.open.size)
        // 3.2x, then 25% off it: sold.
        market(LocalTime.of(13, 30), call = { t -> if (t.isBefore(LocalTime.of(13, 10))) call.entry * 3.2 else call.entry * 2.3 })
        tick(LocalTime.of(13, 30))
        assertEquals("ticket_trail", HeroTicketArm.view.value.closed.single().why)
        assertNothingAtZerodha()
    }

    @Test fun fifteenOhFiveSquaresOffWhatTheTrailKept() {
        market(LocalTime.of(12, 30)); tick(LocalTime.of(12, 30))
        assertEquals(2, HeroTicketArm.view.value.open.size)
        market(LocalTime.of(15, 5), call = { 12.5 }, put = { 11.2 })
        tick(LocalTime.of(15, 4), seconds = 30)
        assertEquals("held to 15:04", 2, HeroTicketArm.view.value.open.size)
        tick(LocalTime.of(15, 5), seconds = 2)
        assertTrue(HeroTicketArm.view.value.open.isEmpty())
        assertTrue(HeroTicketArm.view.value.closed.all { it.why == "ticket_1505" || it.why == "closed_elsewhere" })
        // 15:05 is the app's expiry square-off minute.
        assertEquals(ExpirySquareOff.AT_MINUTE, HeroTicketRules.EXIT_AT.hour * 60 + HeroTicketRules.EXIT_AT.minute)
        assertNothingAtZerodha()
    }

    // ---- open positions first: a restart in the middle of a trade -----------------------------------------------------------

    @Test fun aRestartMidTradeRestoresTheTicketsFirstAndKeepsWatchingThem() {
        runBlocking { HeroTicketArm.setTrailStart(HeroTicketRules.TrailStart.AT_FILL) }
        market(LocalTime.of(12, 30)); tick(LocalTime.of(12, 30))
        assertEquals(2, HeroTicketArm.view.value.open.size)
        // The process is killed: nothing in memory. The start-up's first step reads this book and finds the open tickets.
        HeroTicketArm.wipe()
        assertTrue(HeroTicketArm.view.value.open.isEmpty())
        assertTrue("something open: the watch is started first", runBlocking { PositionsFirst.restore() })
        assertEquals(2, HeroTicketArm.view.value.open.size)
        assertTrue("its minute lane keeps running", HeroTicketArm.holding())
        // And it is watched as before: the put's trail sells it on the next pass after the restart.
        market(LocalTime.of(12, 50), put = { t -> if (t.isBefore(LocalTime.of(12, 45))) 11.0 else 7.5 })
        tick(LocalTime.of(12, 50))
        assertEquals("ticket_trail", HeroTicketArm.view.value.closed.single().why)
        // Switched off, what it holds still closes by its own exits.
        runBlocking { HeroTicketArm.setArmed(false) }
        market(LocalTime.of(13, 10), call = { t -> if (t.isBefore(LocalTime.of(13, 0))) 12.0 else 8.0 }, put = { 7.5 })
        tick(LocalTime.of(13, 10))
        assertTrue(HeroTicketArm.view.value.open.isEmpty())
        assertNothingAtZerodha()
    }

    // ---- refusals -----------------------------------------------------------------------------------------------------------

    @Test fun theKillSwitchRefusesTheDaysTickets() {
        TradeFixtures.killSwitch(true)
        market(LocalTime.of(12, 30))
        val orders = Paper.state.orders.size
        tick(LocalTime.of(12, 30))
        assertEquals(orders, Paper.state.orders.size)
        assertTrue(HeroTicketArm.view.value.open.isEmpty())
        assertTrue(HeroTicketArm.view.value.status["NIFTY"].orEmpty(), HeroTicketArm.view.value.status["NIFTY"].orEmpty().contains("kill switch"))
        assertNothingAtZerodha()
    }

    @Test fun noFresh1230IndexPriceMeansNoTicket() {
        market(LocalTime.of(12, 30), index = false); tick(LocalTime.of(12, 30))
        assertTrue(HeroTicketArm.view.value.open.isEmpty())
        assertTrue(HeroTicketArm.view.value.status["NIFTY"].orEmpty().startsWith("waiting for a fresh"))
        // Still none by 12:33: the day's chance has gone (never a later buy).
        market(LocalTime.of(12, 33), index = false); tick(LocalTime.of(12, 33))
        assertTrue(HeroTicketArm.view.value.status["NIFTY"].orEmpty(), HeroTicketArm.view.value.status["NIFTY"].orEmpty().contains("no fresh 12:30 index price"))
        market(LocalTime.of(12, 40)); tick(LocalTime.of(12, 40))
        assertTrue(HeroTicketArm.view.value.open.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun offItBuysNothing() {
        runBlocking { HeroTicketArm.setArmed(false) }
        market(LocalTime.of(12, 30))
        val orders = Paper.state.orders.size
        tick(LocalTime.of(12, 30))
        assertEquals(orders, Paper.state.orders.size)
        assertTrue(HeroTicketArm.view.value.open.isEmpty())
        assertNothingAtZerodha()
    }

    @Test fun aNonExpiryDayIsNeverATicketDay() {
        // Today is not NIFTY's expiry (tomorrow is): nothing, whatever the prices.
        AutomationSupport.contracts(context, listOf(Upstox.Contract("NIFTY", day.plusDays(1), 25_150.0, Right.CE, 65, ce(25_150), "NIFTY-HT-X")))
        assertTrue(view().armed)
        market(LocalTime.of(12, 30)); tick(LocalTime.of(12, 30))
        assertTrue(HeroTicketArm.view.value.open.isEmpty())
        assertEquals("not its expiry day", HeroTicketArm.view.value.status["NIFTY"])
        assertNothingAtZerodha()
    }

    @Test fun theBookIsItsOwnEncryptedFileNotInTheBackup() {
        view()
        assertTrue(bookFile.exists())
        val o = JSONObject(String(Vault.readFileSteady(bookFile)!!, Charsets.UTF_8))
        assertTrue(o.getBoolean("armed"))
        assertTrue(o.getJSONArray("migrated").toString().contains(HeroTicketArm.AUTO_ON))
        assertFalse("not plain text on disk", bookFile.readText(Charsets.ISO_8859_1).contains("\"armed\""))
    }
}
