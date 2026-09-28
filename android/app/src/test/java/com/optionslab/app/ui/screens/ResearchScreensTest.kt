package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.Broker
import com.optionslab.app.data.Market
import com.optionslab.app.data.Paper
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.ResearchFixtures
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.ui.Account
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.SignalResult
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.Costs
import com.optionslab.engine.Ic
import com.optionslab.engine.Lots
import com.optionslab.engine.Sizing
import com.optionslab.engine.sandbox.FundsView
import com.optionslab.engine.sandbox.HoldingsBook
import com.optionslab.engine.sandbox.OrderBook
import com.optionslab.engine.sandbox.OrderRow
import com.optionslab.engine.sandbox.OrderStatistics
import com.optionslab.engine.sandbox.PositionBook
import com.optionslab.engine.sandbox.PositionRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** Home's fixtures: a paper account with an open short and a resting stop, a Zerodha account, BANKNIFTY prices. */
internal object HomeFixtures {
    val position = PositionRow("NIFTY26O0124800CE", "NFO", "NRML", -75, 120.0, 100.0, 1500.0, 16.7, 1500.0, 0.0, 1500.0, 1.0)
    val stop = OrderRow("P1", "NIFTY26O0124800CE", "NFO", "BUY", 75, 0.0, 180.0, "SL-M", "NRML", "TRIGGER PENDING", 0.0, 0, 75, "", "", "")
    val done = OrderRow("P0", "NIFTY26O0124800CE", "NFO", "SELL", 75, 120.0, 0.0, "LIMIT", "NRML", "COMPLETE", 120.0, 75, 0, "", "", "")
    val paper = Paper.Snapshot(
        FundsView(300_000.0, 0.0, 1_500.0, 0.0, 0.0, 500.0, 100_000.0, 0.0, 2_000.0, "", 0),
        PositionBook(listOf(position), 1_500.0, 1_500.0, 0.0, 1_500.0),
        OrderBook(listOf(done, stop), OrderStatistics(1, 1, 1, 1, 0, 1)),
        emptyList(), HoldingsBook(emptyList(), 0.0, 0.0, 0.0, 0.0), priced = true,
    )
    val emptyPaper = paper.copy(positions = PositionBook(emptyList(), 0.0, 0.0, 0.0, 0.0), orders = OrderBook(emptyList(), OrderStatistics(0, 0, 0, 0, 0, 0)))
    val livePosition = Broker.Position("BANKNIFTY26OCT52000PE", "NRML", -30, 200.0, 180.0, 600.0)
    val liveOrder = Broker.OrderRow("O1", "BANKNIFTY26OCT52000PE", "BUY", 30, 0, 0.0, 0.0, "TRIGGER PENDING", "", "09:30", type = "SL-M", trigger = 260.0, pending = 30)
    val account = Account(Broker.Funds(200_000.0, 50_000.0, 250_000.0), Broker.Positions(listOf(livePosition), emptyList()), listOf(liveOrder), emptyList(), emptyList())

    /** The last closes before today, oldest first, and today's BANKNIFTY quote with its minute spark. */
    val daily: List<Pair<LocalDate, Double>> = (30 downTo 1).map { Market.today().minusDays(it.toLong()) to 51_000.0 + 20 * (30 - it) }
    val quote = Market.Quote("BANKNIFTY", 52_080.0, 51_700.0, 52_150.0, 51_650.0, 11 * 60, List(60) { 51_700.0 + 6.5 * it })
}

/**
 * The research pages (IC table, Signal Lab, Sizing, Costs, Lots, Notes) and Home, driven through plain
 * state and recording callbacks - no AppModel, no network. Numbers come from the bundled record.
 */
// A tall phone: the functional checks are about behaviour, so every control is on screen to be tapped (layout is the matrix tests' job).
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class ResearchScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order = 100) val dump = com.optionslab.app.testing.DumpOnFailure(compose)
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()

    @Before fun fresh() { clearAlerts() }
    @After fun noNetwork() { assertEquals("no host may be reached", emptyList<String>(), NetworkGuard.blocked.toList()) }

    private fun set(content: @Composable () -> Unit) = compose.setContent { IraAlgoTheme("light") { content() } }
    private fun text(t: String, sub: Boolean = false) = compose.onNodeWithText(t, substring = sub)
    private fun shows(t: String, sub: Boolean = true) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()
    private fun scrollTo(t: String, sub: Boolean = false): SemanticsNodeInteraction {
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToNode(hasText(t, substring = sub))
        return text(t, sub)
    }
    private val sliders get() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
    private fun slide(i: Int, to: Float) = sliders[i].performSemanticsAction(SemanticsActions.SetProgress) { it(to) }

    // ---- the IC table -----------------------------------------------------------------------------------

    @Test fun icMeasuresWithTheChosenUnderlyingRegimeAndSessions() {
        val asked = ArrayList<Triple<String, String, Boolean>>()
        set { IcContent(Load.Idle) { u, r, c -> asked += Triple(u, r, c) } }
        text("The IC Table").assertIsDisplayed()
        text("NIFTY").assertIsSelected()
        text("quoted").assertIsSelected()
        text("Measure").performClick()
        text("BANKNIFTY").performClick()
        text("stress").performClick()
        text("same-day chains only").performClick()
        text("same-day chains only").assertIsSelected()
        text("Measure").performClick()
        text("all").performClick()
        text("roll").performClick()
        text("Measure").performClick()
        assertEquals(listOf(Triple("NIFTY", "quoted", false), Triple("BANKNIFTY", "stress", true), Triple("BANKNIFTY", "roll", false)), asked)
    }

    @Test fun icBusyFailedAndTheTable() {
        var st by mutableStateOf<Load<Ic.IcResult>>(Load.Busy("Session 3", 0.1f))
        var measured = 0
        set { IcContent(st) { _, _, _ -> measured++ } }
        text("Session 3").assertIsDisplayed()
        text("Measure").assertIsNotEnabled()
        st = Load.Failed("nothing to measure")
        compose.waitForIdle()
        text("nothing to measure").assertIsDisplayed()
        text("Measure").assertIsEnabled()
        val r = ResearchFixtures.ic
        st = Load.Done(r)
        compose.waitForIdle()
        scrollTo("${r.underlying}: ${r.sessions.size} sessions").assertIsDisplayed()
        scrollTo("Round-trip break-even").assertIsDisplayed()
        r.rows.take(3).forEach { row -> scrollTo("${row.feature}  h=${row.horizon}").assertIsDisplayed() }
        if (r.cleared.isEmpty()) scrollTo("Nothing clears the 2x cost bar while beating its null band.").assertIsDisplayed()
        else scrollTo("CLEARS 2x COST", sub = true).assertIsDisplayed()
        // A raw IC is never shown without its partial.
        assertTrue(r.rows.isEmpty() || shows("IC / partial", sub = false))
        assertEquals(0, measured)
    }

    // ---- Signal Lab ---------------------------------------------------------------------------------------

    private data class Replay(val u: String, val day: LocalDate, val ind: String, val key: Double, val atr: Int, val len: Int, val lot: Int)

    @Test fun signalReplaysTheChosenSessionWithTheChosenParameters() {
        val days = (1..10).map { LocalDate.of(2026, 8, 10 + it) }
        val runs = ArrayList<Replay>()
        set { SignalContent(Load.Idle, daysFor = { if (it == "NIFTY") days else days.take(2) }) { u, d, i, k, a, l, lot -> runs += Replay(u, d, i, k, a, l, lot) } }
        compose.waitUntil(5_000) { shows("08-20", sub = false) }
        // The last eight sessions are offered, the newest picked.
        assertTrue(!shows("08-12", sub = false))
        text("08-20").assertIsSelected()
        text("Key value a = 2.0   ·   ATR period c = 1").assertIsDisplayed()
        text("Replay the session").performClick()
        text("08-15").performClick()
        slide(0, 3.2f)
        slide(1, 7.6f)
        text("Key value a = 3.0   ·   ATR period c = 7").assertIsDisplayed()
        text("Replay the session").performClick()
        text("LinReg flip").performClick()
        text("Window length = 100 bars").assertIsDisplayed()
        slide(0, 150.4f)
        text("Window length = 150 bars").assertIsDisplayed()
        text("Replay the session").performClick()
        text("BANKNIFTY").performClick()
        compose.waitUntil(5_000) { !shows("08-20", sub = false) }
        text("08-12").assertIsSelected()
        text("Replay the session").performClick()
        assertEquals(listOf(
            Replay("NIFTY", days[9], "utbot", 2.0, 1, 100, 65),
            Replay("NIFTY", days[4], "utbot", 3.0, 7, 100, 65),
            Replay("NIFTY", days[4], "linreg", 3.0, 7, 150, 65),
            Replay("BANKNIFTY", days[1], "linreg", 3.0, 7, 150, 30),
        ), runs)
    }

    @Test fun signalWithNoSessionsCannotReplay() {
        var runs = 0
        set { SignalContent(Load.Idle, daysFor = { emptyList() }) { _, _, _, _, _, _, _ -> runs++ } }
        compose.waitForIdle()
        text("Replay the session").assertIsNotEnabled()
        assertEquals(0, runs)
    }

    @Test fun signalBusyFailedAndTheResult() {
        var st by mutableStateOf<Load<SignalResult>>(Load.Busy("Replaying 2026-08-20"))
        set { SignalContent(st, daysFor = { listOf(LocalDate.of(2026, 8, 20)) }) { _, _, _, _, _, _, _ -> } }
        compose.waitUntil(5_000) { shows("Replaying 2026-08-20", sub = false) }
        st = Load.Failed("no partition for 2026-08-20")
        compose.waitForIdle()
        scrollTo("no partition for 2026-08-20").assertIsDisplayed()
        val r = ResearchFixtures.signal
        st = Load.Done(r)
        compose.waitForIdle()
        scrollTo("${r.underlying} ${r.day}").assertIsDisplayed()
        scrollTo("${r.buys.count { it }} buy · ${r.sells.count { it }} sell").assertIsDisplayed()
        scrollTo("A flat option price must produce a LOSS", sub = true).assertIsDisplayed()
        scrollTo("${r.trades.size}, won ${r.trades.count { it.netPnl > 0 }}").assertIsDisplayed()
        if (r.trades.isEmpty()) scrollTo("No fillable trades.").assertIsDisplayed()
    }

    // ---- Sizing ---------------------------------------------------------------------------------------------

    @Test fun sizingSlidersPlanAndSaveForTheTrial() {
        val used = ArrayList<Pair<Double, Double>>()
        set { SizingContent(ResearchFixtures.settings) { c, s -> used += c to s } }
        text("Capital Rs 400,000").assertIsDisplayed()
        text("Survive a -6% expiry day").assertIsDisplayed()
        slide(0, 1_234_567f)
        slide(1, -13.7f)
        text("Capital Rs 1,230,000").assertIsDisplayed()
        text("Survive a -13% expiry day").assertIsDisplayed()
        val plan = runCatching { Sizing.planPosition(1_230_000.0, -0.13) }
        plan.onSuccess { text("${it.lots} (depth ceiling ${Sizing.MAX_LOTS_ON_DEPTH})").assertIsDisplayed() }
            .onFailure { text(it.message!!).assertIsDisplayed() }
        text("Use for the trial").performClick()
        assertEquals(listOf(1_230_000.0 to -0.13), used)
        scrollTo("The Tail, per lot of ${Sizing.LOT_SIZE}").assertIsDisplayed()
        scrollTo("index -13%").assertIsDisplayed()
    }

    @Test fun sizingThatCannotSurviveSaysWhy() {
        set { SizingContent(ResearchFixtures.settings) { _, _ -> } }
        slide(0, 100_000f)
        slide(1, -20f)
        val plan = runCatching { Sizing.planPosition(100_000.0, -0.20) }
        plan.onSuccess { text("Lots").assertIsDisplayed() }.onFailure { text(it.message!!).assertIsDisplayed() }
    }

    // ---- Costs ------------------------------------------------------------------------------------------------

    @Test fun costsFollowPremiumLotAndRegime() {
        val asked = ArrayList<List<Any>>()
        set {
            CostsContent { p, l, n, r ->
                asked += listOf(p, l, n, r)
                Triple(Costs.sellToSettle(p, l, n, r), Costs.buyToSettle(p, l, n, r), Costs.roundTrip(p, l, n, r))
            }
        }
        text("Premium Rs 4.85").assertIsDisplayed()
        assertEquals(listOf<Any>(4.85, 65, 1, "quoted"), asked.last().let { listOf((it[0] as Double).let { d -> Math.round(d * 100) / 100.0 }, it[1], it[2], it[3]) })
        slide(0, 100.03f)
        text("Premium Rs 100.00").assertIsDisplayed()
        text("75").performClick()
        text("stress").performClick()
        compose.waitForIdle()                  // the figures are recomputed when the page recomposes, not in the click
        val last = asked.last()
        assertEquals(100.0, last[0] as Double, 1e-9); assertEquals(75, last[1]); assertEquals(1, last[2]); assertEquals("stress", last[3])
        text("Rs %.3f".format(0.009 * 100.0)).assertIsDisplayed()
        val rt = Costs.roundTrip(100.0, 75, 1, "stress")
        scrollTo("Full round trip").assertIsDisplayed()
        scrollTo("Rs %.2f (%s of premium)".format(rt.total, com.optionslab.app.ui.pct(rt.fractionOfPremium))).assertIsDisplayed()
        scrollTo("Round-trip break-even at delta 0.5").assertIsDisplayed()
    }

    // ---- Lots and Notes -------------------------------------------------------------------------------------

    @Test fun lotHistoryListsEveryUnderlyingAndToday() {
        set { LotsPage() }
        text("Lot History").assertIsDisplayed()
        for ((u, rows) in Lots.LOT_HISTORY) {
            scrollTo(u).assertIsDisplayed()
            // Several underlyings start on the same day: their "from" lines have the same text.
            val from = "from ${rows.first().first}"
            val same = Lots.LOT_HISTORY.count { it.value.first().first == rows.first().first }
            assertTrue("$u's history starts $from", compose.onAllNodesWithText(from).fetchSemanticsNodes().size in 1..same)
        }
        assertTrue(shows("Today", sub = false))
        scrollTo("On 2025-01-30 every NIFTY contract", sub = true).assertIsDisplayed()
    }

    @Test fun researchNotesListEveryNote() {
        set { NotesPage() }
        text("Research Notes").assertIsDisplayed()
        listOf("The one result", "What this is not", "Everything directional was closed", "Multi-day does not rescue it", "The hedge", "Not deployed", "Invariants")
            .forEach { scrollTo(it).assertIsDisplayed() }
    }

    // ---- Home ------------------------------------------------------------------------------------------------

    private class HomeCalls { val went = ArrayList<String>(); val rows = ArrayList<RowTarget>() }

    private fun home(live: Boolean, loggedIn: Boolean = false, account: Load<Account> = Load.Idle, paper: Load<Paper.Snapshot> = Load.Idle,
                     quotes: Map<String, Market.Quote> = emptyMap(), daily: List<Pair<LocalDate, Double>> = emptyList(), note: String? = null): HomeCalls {
        val c = HomeCalls()
        set { AlmanacContent(live, loggedIn, quotes, note, daily, account, paper, onGo = { c.went += it }, onRow = { c.rows += it }) { Text("strategies card") } }
        return c
    }

    @Test fun homePaperShowsTheMoneyAndTheLiveOrders() {
        val c = home(live = false, paper = Load.Done(HomeFixtures.paper))
        text("Capital").assertIsDisplayed()
        text("25% of capital in use").assertIsDisplayed()
        text("strategies card").assertIsDisplayed()
        scrollTo("Live orders").assertIsDisplayed()
        text("SELL 75 · avg 120.00 · LTP 100.00").assertIsDisplayed()
        text("OPEN").assertIsDisplayed()
        // The resting stop is listed; the finished order is not.
        text("BUY 75 · sl-m · trigger 180.00").assertIsDisplayed()
        text("TRIGGER PENDING").assertIsDisplayed()
        assertTrue(!shows("SELL 75 · limit", sub = true))
        text("SELL 75 · avg 120.00 · LTP 100.00").performClick()
        text("BUY 75 · sl-m · trigger 180.00").performClick()
        assertEquals(listOf(RowTarget.PaperPosition(HomeFixtures.position), RowTarget.PaperOrder(HomeFixtures.stop)), c.rows)
        text("All in Trade ›").performClick()
        assertEquals(listOf("trade"), c.went)
    }

    @Test fun homeEmptyAndFailedPaperAccount() {
        var paper by mutableStateOf<Load<Paper.Snapshot>>(Load.Done(HomeFixtures.emptyPaper))
        set { AlmanacContent(false, false, emptyMap(), null, emptyList(), Load.Idle, paper, {}, {}) {} }
        scrollTo("No open positions or pending orders.").assertIsDisplayed()
        paper = Load.Failed("The paper account could not be read")
        compose.waitForIdle()
        scrollTo("The paper account could not be read").assertIsDisplayed()
        paper = Load.Idle
        compose.waitForIdle()
        scrollTo("Capital")
        assertTrue(shows("—", sub = false))
    }

    @Test fun homeLiveNeedsALoginThenShowsZerodha() {
        var loggedIn by mutableStateOf(false)
        var account by mutableStateOf<Load<Account>>(Load.Idle)
        val rows = ArrayList<RowTarget>()
        set { AlmanacContent(true, loggedIn, emptyMap(), null, emptyList(), account, Load.Done(HomeFixtures.paper), {}, { rows += it }) {} }
        text("Log in to Zerodha for today to see your money and orders.").assertIsDisplayed()
        // Live mode never shows the paper account.
        assertTrue(!shows("SELL 75 · avg 120.00", sub = true))
        loggedIn = true
        account = Load.Failed("TokenException: the session expired")
        compose.waitForIdle()
        text("TokenException: the session expired").assertIsDisplayed()
        account = Load.Done(HomeFixtures.account)
        compose.waitForIdle()
        text("20% of capital in use").assertIsDisplayed()
        scrollTo("SELL 30 · avg 200.00 · LTP 180.00").performClick()
        scrollTo("BUY 30 · sl-m · trigger 260.00").performClick()
        assertEquals(listOf(RowTarget.LivePosition(HomeFixtures.livePosition), RowTarget.LiveOrder(HomeFixtures.liveOrder)), rows)
    }

    @Test fun homeBankNiftyChartRangesAndFullChart() {
        val c = home(live = false, paper = Load.Done(HomeFixtures.paper), quotes = mapOf("BANKNIFTY" to HomeFixtures.quote), daily = HomeFixtures.daily)
        scrollTo("BANKNIFTY").assertIsDisplayed()
        text("52,080.00").assertIsDisplayed()
        // 1D: against the previous close (51,580), not today's open.
        text("+500.00", sub = true).assertIsDisplayed()
        text("1D").assertIsSelected()
        for (r in listOf("1W", "1M", "1Y")) { scrollTo(r).performClick(); text(r).assertIsSelected() }
        // 1Y: from the first close shown.
        text("+1,080.00", sub = true).assertIsDisplayed()
        text("Full chart ›").performClick()
        assertEquals(listOf("chart"), c.went)
    }

    @Test fun homeDayChartWithoutPricesExplainsWhy() {
        home(live = false, note = "Prices are delayed: Upstox did not answer")
        scrollTo("1D").assertIsSelected()
        val expected = when {
            !Market.isTradingDay() -> "Market closed today. Pick 1W, 1M or 1Y for recent days."
            Market.minuteNow() < Market.OPEN -> "The day's chart starts at 09:15."
            else -> "Prices are delayed: Upstox did not answer"
        }
        scrollTo(expected).assertIsDisplayed()
        text("1W").performClick()
        scrollTo("Prices are delayed: Upstox did not answer").assertIsDisplayed()
    }
}

/**
 * The research pages and Home on every device set-up: a screenshot each, [com.optionslab.app.testing.LayoutLint],
 * and a click-everything smoke test.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResearchScreensLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = com.optionslab.app.testing.ResearchMatrix.six()

        /**
         * Real layout bugs found by these tests, skipped with this text until fixed. The triage run found 'Add to
         * Strategies' ellipsized, the replay's 'ATM PE' token cut and the 'Unbounded beyond this move' badge cut at
         * font 2.0 on a small phone: all fixed (buttons take three lines, tokens and badges wrap).
         */
        val BUGS = emptyMap<String, String>()
    }

    @After fun noNetwork() { assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    @Test fun icTable() {
        checkScreen("research-ic", BUGS) { IcContent(Load.Done(ResearchFixtures.ic)) { _, _, _ -> } }
        if (device.name == "phone-font1.3-light") smokeEveryAction()
    }

    @Test fun signalLab() {
        val r = ResearchFixtures.signal
        checkScreen("research-signal", BUGS) { SignalContent(Load.Done(r), { listOf(r.day) }) { _, _, _, _, _, _, _ -> } }
        if (device.name == "phone-font1.3-light") smokeEveryAction()
    }

    @Test fun sizing() {
        checkScreen("research-sizing", BUGS) { SizingContent(ResearchFixtures.settings) { _, _ -> } }
        if (device.name == "phone-font1.3-light") smokeEveryAction()
    }

    @Test fun costs() {
        checkScreen("research-costs", BUGS) {
            CostsContent { p, l, n, r -> Triple(Costs.sellToSettle(p, l, n, r), Costs.buyToSettle(p, l, n, r), Costs.roundTrip(p, l, n, r)) }
        }
        if (device.name == "phone-font1.3-light") smokeEveryAction()
    }

    @Test fun lotHistory() = checkScreen("research-lots", BUGS) { LotsPage() }

    @Test fun notes() = checkScreen("research-notes", BUGS) { NotesPage() }

    @Test fun homePaper() {
        checkScreen("home-paper", BUGS) {
            AlmanacContent(false, false, mapOf("BANKNIFTY" to HomeFixtures.quote), null, HomeFixtures.daily, Load.Idle, Load.Done(HomeFixtures.paper), {}, {}) {}
        }
        if (device.name == "phone-font1.3-light") smokeEveryAction()
    }

    /** Who placed each row: a strategy's short and its resting stop on paper, a hand order on Zerodha. */
    @Test fun homeSources() {
        val trades = listOf(com.optionslab.engine.sandbox.TradeRow("T0", "P0", HomeFixtures.position.symbol, "NFO", "SELL", 75, 120.0, 120.0, 9_000.0,
            "NRML", "", "2026-09-28 10:05:00"))
        val owners = mapOf("paper:P0" to "ORB · entry", "paper:P1" to "ORB · stop")
        checkScreen("home-sources", BUGS) {
            AlmanacContent(false, false, mapOf("BANKNIFTY" to HomeFixtures.quote), null, HomeFixtures.daily, Load.Idle,
                Load.Done(HomeFixtures.paper.copy(trades = trades)), {}, {}, owners = owners) {}
        }
        // Home is a lazy list: at a large font or in landscape the rows start below the fold.
        for (label in listOf("Opened by ORB", "Strategy: ORB · stop")) {
            compose.onAllNodes(hasScrollToIndexAction()).onFirst()
                .performScrollToNode(hasText(label))
            compose.onNodeWithText(label).assertExists()
        }
    }

    @Test fun homeLiveLoggedOut() = checkScreen("home-live-logged-out", BUGS) {
        AlmanacContent(true, false, emptyMap(), "Loading prices…", emptyList(), Load.Idle, Load.Idle, {}, {}) {}
    }
}

/** The page's main state on all 24 set-ups (the other states run on six, in [ResearchScreensLayoutTest]). */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResearchMainLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix()
        val BUGS get() = ResearchScreensLayoutTest.BUGS
    }

    @Test fun homePaper() = checkScreen("home-paper-all", BUGS) {
        AlmanacContent(false, false, mapOf("BANKNIFTY" to HomeFixtures.quote), null, HomeFixtures.daily, Load.Idle, Load.Done(HomeFixtures.paper), {}, {}) {}
    }
}
