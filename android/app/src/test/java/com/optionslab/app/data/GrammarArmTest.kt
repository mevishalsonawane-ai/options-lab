package com.optionslab.app.data

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
import com.optionslab.ira.GrammarPaper
import com.optionslab.ira.ShadowSim
import com.optionslab.ira.StrategyForge
import com.optionslab.ira.VariantLab
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.ConscryptMode
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * The paper arm for approved forge ideas (S2) is PAPER ONLY: with the app in Live, logged in to Zerodha, real orders allowed
 * and "AI trades go live" on, an idea Boss approved "start on paper" is hosted and trades by its own grammar on the paper
 * account - BANKNIFTY's 10:30-10:34 bar closing above the first 30 minutes' range buys the at-the-money call, 1 lot, its
 * 20% stop resting in the paper book - and not one order reaches Zerodha. The kill switch and the account's cut-off refuse
 * an entry; at most three ideas are hosted; Boss's Stop and a record worse than its forward test both stop an idea and sell
 * what it holds.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
class GrammarArmTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    private lateinit var upstox: FakeUpstox
    private lateinit var kite: FakeKite
    private lateinit var day: LocalDate
    private val bankKey = Upstox.INDEX_KEYS.getValue("BANKNIFTY")
    private val ceKey = "NSE_FO|GRAMB50300CE"                  // BANKNIFTY 50,300 CE: at the money when the 10:34 close is 50,300

    private val rules = ShadowSim.Rules(ShadowSim.Entry.ORB_BREAK, barMin = 5, orMin = 30, fromMin = 10 * 60, untilMin = 14 * 60,
        stopPct = 0.2, targetPct = 0.5)

    private fun record(n: Int, stage: StrategyForge.Stage) = StrategyForge.Record(
        StrategyForge.Candidate(n, day.minusDays(40), "BANKNIFTY", rules, StrategyForge.ExitBlock.FIXED), stage,
        StrategyForge.Backtest(60, 100, 30_000.0, 50.0, listOf(1.0, 1.0, 1.0), 0.01, 0.0, 5_000.0, false, listOf("x" to true)),
        List(30) { 400.0 }, 25)

    private fun forge(vararg recs: StrategyForge.Record) = StrategyForge.Book(tried = recs.size, records = recs.toList())

    private fun at(t: LocalTime, seconds: Long = 20) {
        Market.testClock = Clock.fixed(day.atTime(t).plusSeconds(seconds).atZone(IST).toInstant(), IST)
    }

    private fun tick(t: LocalTime) { at(t); market(t); runBlocking { GrammarArm.tick() } }

    /** BANKNIFTY's minutes to [until] (exclusive): near 50,000 to 10:30, then 50,300; the call at [call]. */
    private fun market(until: LocalTime, call: (LocalTime) -> Double = { 200.0 }) {
        fun series(key: String, px: (LocalTime) -> Double) {
            val out = ArrayList<FakeUpstox.Candle>()
            var t = LocalTime.of(9, 15)
            while (t.isBefore(until)) { val c = px(t); out += FakeUpstox.Candle(t, c, c + 2, c - 2, c); t = t.plusMinutes(1) }
            upstox.minutes[key] = out
        }
        series(bankKey) { t -> if (!t.isBefore(LocalTime.of(10, 30))) 50_300.0 else 50_000.0 }
        series(ceKey, call)
    }

    @Before fun up() {
        assumeTrue("The strategy lab is in IraAlgo with Jarvis only", VariantRunner.enabled())
        upstox = FakeUpstox()                                // (clears Market.testClock: set it after)
        kite = FakeKite()
        // Everything that could send an order to Zerodha is on: Live, real orders allowed, logged in, AI trades live.
        kite.login(live = true)
        AutomationSupport.liveSettings()
        AutomationSupport.security(compromised = false)
        SecurePrefs.put("jarvis.trades.paper.v3", false)
        day = AutomationSupport.tradingDayAt(10, 35).toLocalDate()
        at(LocalTime.of(10, 35))
        AutomationSupport.contracts(context, listOf(
            Upstox.Contract("BANKNIFTY", day.plusDays(7), 50_300.0, Right.CE, 30, ceKey, "BANKNIFTY-GRAM-50300CE"),
            Upstox.Contract("BANKNIFTY", day.plusDays(7), 50_300.0, Right.PE, 30, "NSE_FO|GRAMB50300PE", "BANKNIFTY-GRAM-50300PE")))
        VariantRunner.resetForTest()
        GrammarArm.wipe(); java.io.File(context.filesDir, "grammar_arm.vault").delete()
        kite.requests.clear()
    }

    @After fun down() {
        Market.testClock = null
        if (!::kite.isInitialized) return    // skipped (no Jarvis in this build): nothing was set up
        VariantRunner.resetForTest()
        GrammarArm.wipe()
        kite.close(); upstox.close()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** Not one order, modify or cancel was sent to Zerodha. */
    private fun assertNothingAtZerodha() {
        assertEquals("no order at Zerodha", emptyList<FakeKite.Req>(), kite.placed)
        assertEquals("nothing written at Zerodha", emptyList<FakeKite.Req>(), kite.writes)
    }

    @Test fun anApprovedIdeaIsHostedAndTradesOnPaperOnlyEvenWithEverythingLive() {
        // Proposed, not yet approved: nothing is hosted.
        VariantRunner.setForTest(VariantLab.Book(), forge(record(1, StrategyForge.Stage.PROPOSED)))
        tick(LocalTime.of(10, 35))
        assertTrue(GrammarArm.view.value.ideas.isEmpty())
        // Boss's "start on paper" (the existing approve flow): hosted on the next pass, and its grammar fires at 10:35.
        assertTrue(VariantRunner.approvePaper("F1").contains("approved for paper"))
        val orders = Paper.state.orders.size
        tick(LocalTime.of(10, 35))
        val iv = GrammarArm.view.value.active.single()
        assertEquals("F1", iv.idea.id)
        val pos = iv.open!!
        assertEquals("CE", pos.right)
        assertEquals(30, pos.qty)
        assertTrue("paid the spread", pos.entry > 200.0)
        assertTrue("held in the paper book", Paper.state.positions.any { it.symbol == pos.symbol && it.quantity == 30 })
        // Its 20% stop rests in the paper book (it fills on a minute's low).
        val stop = Paper.state.orders.single { it.orderId == pos.stopOrderId }
        assertEquals("SL-M", stop.priceType)
        assertTrue(Paper.state.orders.size == orders + 2)
        // The trade manager follows it in SHADOW (records only).
        assertTrue(TradeManagerHost.records.value.any { it.trade.family == GrammarPaper.FAMILY })
        // The same decision is not taken twice.
        tick(LocalTime.of(10, 36))
        assertEquals(orders + 2, Paper.state.orders.size)
        // Its target (+50%) on a minute's high: sold on paper at the next pass.
        market(LocalTime.of(10, 40)) { t -> if (!t.isBefore(LocalTime.of(10, 38))) 320.0 else 200.0 }
        at(LocalTime.of(10, 40)); runBlocking { GrammarArm.tick() }
        val done = GrammarArm.view.value.ideas.single().nets
        assertEquals(1, done.size)
        assertEquals(0, Paper.state.positions.filter { it.symbol == pos.symbol }.sumOf { it.quantity })
        assertNothingAtZerodha()
    }

    /** The arm's own code never calls the broker: every order is the paper account's. */
    @Test fun theArmNeverCallsTheBroker() {
        val src = java.io.File("src/main/java/com/optionslab/app/data/GrammarArm.kt").takeIf { it.isFile }?.readText() ?: return
        assertFalse(Regex("\\b(Broker|Kite|KiteStream)\\.").containsMatchIn(src))
        assertTrue(src.contains("Paper.place("))
    }

    @Test fun theKillSwitchAndTheAccountsCutoffRefuseTheEntry() {
        VariantRunner.setForTest(VariantLab.Book(), forge(record(1, StrategyForge.Stage.PAPER)))
        TradeFixtures.killSwitch(true)
        val orders = Paper.state.orders.size
        tick(LocalTime.of(10, 35))
        assertEquals(orders, Paper.state.orders.size)
        assertTrue(GrammarArm.view.value.active.single().idea.status.contains("kill_switch"))
        TradeFixtures.killSwitch(false)
        // The account's entry cut-off before the signal (10:30): refused too.
        GrammarArm.wipe(); java.io.File(context.filesDir, "grammar_arm.vault").delete()
        SecurePrefs.put("g.cutoff", 10 * 60 + 30)
        tick(LocalTime.of(10, 35))
        assertEquals(orders, Paper.state.orders.size)
        assertTrue(GrammarArm.view.value.active.single().idea.status.contains("after_cutoff"))
        // F&O closed: no entry at all.
        SecurePrefs.put("g.cutoff", -1)
        tick(LocalTime.of(15, 50))
        assertEquals(orders, Paper.state.orders.size)
        assertNothingAtZerodha()
    }

    @Test fun atMostThreeIdeasAreHostedAndBossCanStopOne() {
        VariantRunner.setForTest(VariantLab.Book(), forge(*Array(4) { record(it + 1, StrategyForge.Stage.PAPER) }))
        runBlocking { GrammarArm.refresh() }
        assertEquals(listOf("F1", "F2", "F3"), GrammarArm.view.value.active.map { it.idea.id })
        assertTrue(runBlocking { GrammarArm.host("F4") }.contains("3 ideas are already on paper"))
        // Stopped from the sheet: no new entries, and the waiting idea takes its place; a stopped one never comes back by itself.
        assertTrue(runBlocking { GrammarArm.stop("F2") }.contains("stopped"))
        runBlocking { GrammarArm.refresh() }
        assertEquals(listOf("F1", "F3", "F4"), GrammarArm.view.value.active.map { it.idea.id })
        assertFalse(GrammarArm.view.value.ideas.single { it.idea.id == "F2" }.idea.active)
        assertTrue(runBlocking { GrammarArm.host("F2") }.contains("not started"))
        assertNothingAtZerodha()
    }

    @Test fun stoppingSellsWhatItHoldsAndAWorseRecordStopsItByItself() {
        VariantRunner.setForTest(VariantLab.Book(), forge(record(1, StrategyForge.Stage.PAPER)))
        tick(LocalTime.of(10, 35))
        val pos = GrammarArm.view.value.active.single().open!!
        at(LocalTime.of(10, 36)); market(LocalTime.of(10, 36))
        assertTrue(runBlocking { GrammarArm.stop("F1") }.contains("stopped"))
        assertEquals(0, Paper.state.positions.filter { it.symbol == pos.symbol }.sumOf { it.quantity })
        val iv = GrammarArm.view.value.ideas.single()
        assertFalse(iv.idea.active)
        assertEquals(1, iv.nets.size)
        assertTrue(GrammarArm.view.value.log.any { "sold (idea_stopped)" in it })
        // A stopped idea buys nothing more.
        val orders = Paper.state.orders.size
        tick(LocalTime.of(10, 40))
        assertEquals(orders, Paper.state.orders.size)
        assertNothingAtZerodha()
    }

    @Test fun aRecordWorseThanItsForwardTestStopsTheIdeaByItself() {
        VariantRunner.setForTest(VariantLab.Book(), forge(record(1, StrategyForge.Stage.PAPER), record(2, StrategyForge.Stage.PAPER)))
        runBlocking { GrammarArm.refresh() }
        // F1 loses steadily where its forward test made Rs 400 a trade; F2 is in line with its own.
        runBlocking { GrammarArm.recordForTest("F1", List(8) { -1_000.0 }, day.minusDays(1)) }
        runBlocking { GrammarArm.recordForTest("F2", listOf(600.0, -200.0, 900.0, 300.0, -100.0, 700.0), day.minusDays(1)) }
        tick(LocalTime.of(10, 20))
        val f1 = GrammarArm.view.value.ideas.single { it.idea.id == "F1" }.idea
        assertFalse(f1.active)
        assertTrue(f1.stopWhy.orEmpty(), f1.stopWhy.orEmpty().startsWith("auto-stopped: worse than its forward test"))
        assertTrue(GrammarArm.view.value.ideas.single { it.idea.id == "F2" }.idea.active)
        assertTrue(GrammarArm.diagLine().contains("1 of ${GrammarPaper.MAX_HOSTED} on paper"))
        assertNothingAtZerodha()
    }
}
