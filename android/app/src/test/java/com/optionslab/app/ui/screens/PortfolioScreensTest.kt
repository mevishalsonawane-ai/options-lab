package com.optionslab.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.Market
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.portfolio.DailyBar
import com.optionslab.engine.portfolio.Holding
import com.optionslab.engine.portfolio.PortfolioAnalyzer
import com.optionslab.engine.portfolio.PortfolioBacktest
import com.optionslab.engine.portfolio.PortfolioRequest
import com.optionslab.engine.portfolio.SipBacktest
import com.optionslab.engine.portfolio.SipRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import kotlin.math.sin

/** Made-up daily prices (a gentle trend with a wobble) and the engine's real results on them: no network, no AppModel. */
internal object PortfolioFixtures {
    val end: LocalDate = LocalDate.of(2025, 6, 30)
    val start: LocalDate = end.minusYears(1)
    private val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.filter { it.dayOfWeek.value <= 5 }.toList()
    private fun series(base: Double, drift: Double, amp: Double) =
        days.mapIndexed { i, d -> val c = base * (1 + drift * i) * (1 + amp * sin(i / 7.0)); DailyBar(d, c, c, c, c) }
    val bars = mapOf("NIFTYBEES" to series(250.0, 0.0006, 0.03), "GOLDBEES" to series(60.0, 0.0004, 0.02), "NIFTY" to series(24_000.0, 0.0005, 0.025))
    val sources = setOf("Upstox")

    val portfolio by lazy {
        AppModel.PortfolioView(PortfolioBacktest.run(PortfolioRequest(listOf(Holding("NIFTYBEES", "NSE", 60.0), Holding("GOLDBEES", "NSE", 40.0)),
            start, end, benchmark = "NIFTY", rebalance = "monthly", initialCapital = 100_000.0), bars), sources)
    }
    val sip by lazy {
        AppModel.SipView(SipBacktest.run(SipRequest("NIFTYBEES", "NSE", start, end, 10_000.0, "monthly", 5, 10.0, benchmark = "NIFTY"), bars), sources)
    }
    val analyzer by lazy {
        val rows = listOf(
            mapOf<String, Any?>("symbol" to "NIFTYBEES", "exchange" to "NSE", "quantity" to 100.0, "average_price" to 240.0, "last_price" to 260.0, "pnl" to 2000.0, "product" to "CNC"),
            mapOf<String, Any?>("symbol" to "GOLDBEES", "exchange" to "NSE", "quantity" to 200.0, "average_price" to 62.0, "last_price" to 61.0, "pnl" to -200.0, "product" to "CNC"))
        AppModel.AnalyzerView(PortfolioAnalyzer.analyze(rows, bars, end), sources)
    }
}

/**
 * The Portfolio and SIP labs ([PortfolioLabContent], [SipLabContent]) with made-up results: every control
 * is operated and what the run button would ask the model for is recorded; loading, failure and results render.
 */
@RunWith(AndroidJUnit4::class)
class PortfolioScreensTest {
    @get:Rule val compose = createComposeRule()

    data class PortfolioRun(val holdings: List<Holding>, val start: LocalDate, val end: LocalDate, val bench: String?, val rebalance: String, val capital: Double)
    data class SipRun(val symbol: String, val exchange: String, val start: LocalDate, val end: LocalDate, val amount: Double, val freq: String,
                      val day: Int, val stepUp: Double, val bench: String?)

    private val runs = ArrayList<PortfolioRun>()
    private val sips = ArrayList<SipRun>()
    private var analyses = 0
    private var res by mutableStateOf<Load<AppModel.PortfolioView>>(Load.Idle)
    private var an by mutableStateOf<Load<AppModel.AnalyzerView>>(Load.Idle)
    private var sipRes by mutableStateOf<Load<AppModel.SipView>>(Load.Idle)

    private fun portfolio(live: Boolean = false) = compose.setContent {
        IraAlgoTheme("light") {
            PortfolioLabContent(live, res, an, onAnalyze = { analyses++ }, onRun = { h, s, e, b, r, c -> runs += PortfolioRun(h, s, e, b, r, c) })
        }
    }

    private fun sipLab() = compose.setContent {
        IraAlgoTheme("light") {
            SipLabContent(sipRes) { sym, ex, s, e, a, f, d, up, b -> sips += SipRun(sym, ex, s, e, a, f, d, up, b) }
        }
    }

    /** The page is a lazy list: bring [text] into view first. */
    private fun node(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text, substring = substring))
        return compose.onNodeWithText(text, substring = substring)
    }

    private fun click(text: String) { node(text).performClick(); compose.waitForIdle() }
    private fun shown(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun symbolBoxes() = compose.onAllNodes(hasSetTextAction() and hasText("NSE symbol"))
    private fun weightBoxes() = compose.onAllNodes(hasSetTextAction() and hasText("Weight %"))
    private fun SemanticsNodeInteraction.value() = fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    // ---- Portfolio ------------------------------------------------------------------------------

    @Test fun theDefaultBasketRunsWithTheDefaults() {
        portfolio()
        assertTrue(shown("Portfolio"))
        assertEquals(3, symbolBoxes().fetchSemanticsNodes().size)
        click("Run the backtest")
        val r = runs.single()
        assertEquals(listOf(Holding("NIFTYBEES", "NSE", 50.0), Holding("GOLDBEES", "NSE", 30.0), Holding("ITC", "NSE", 20.0)), r.holdings)
        assertEquals(Market.today(), r.end); assertEquals(Market.today().minusYears(5), r.start)
        assertEquals("NIFTY", r.bench); assertEquals("never", r.rebalance); assertEquals(100_000.0, r.capital, 0.0)
    }

    @Test fun everyChoiceReachesTheRun() {
        portfolio()
        click("10 y"); click("quarterly"); click("none"); click(com.optionslab.app.ui.rs(1_000_000.0))
        node("10 y").assertIsSelected(); node("quarterly").assertIsSelected(); node("none").assertIsSelected()
        click("Run the backtest")
        val r = runs.single()
        assertEquals(Market.today().minusYears(10), r.start)
        assertEquals(null, r.bench); assertEquals("quarterly", r.rebalance); assertEquals(1_000_000.0, r.capital, 0.0)
    }

    @Test fun holdingsCanBeEditedAddedAndRemoved() {
        portfolio()
        symbolBoxes()[2].performTextClearance()
        symbolBoxes()[2].performTextInput("m&m-x!y")
        assertEquals("upper case, letters, digits, - and & only", "M&M-XY", symbolBoxes()[2].value())
        weightBoxes()[2].performTextClearance()
        weightBoxes()[2].performTextInput("2a5.5")
        assertEquals("25.5", weightBoxes()[2].value())
        click("+ Add holding")
        assertEquals(4, symbolBoxes().fetchSemanticsNodes().size)
        symbolBoxes()[3].performTextInput("TCS")                        // no weight: counts as 0
        compose.onAllNodesWithText("✕")[0].performClick()               // NIFTYBEES goes
        compose.waitForIdle()
        assertEquals(3, symbolBoxes().fetchSemanticsNodes().size)
        click("Run the backtest")
        assertEquals(listOf(Holding("GOLDBEES", "NSE", 30.0), Holding("M&M-XY", "NSE", 25.5), Holding("TCS", "NSE", 0.0)), runs.single().holdings)
    }

    @Test fun blankRowsAreLeftOutAndTheBasketStopsAtTwenty() {
        portfolio()
        repeat(17) { click("+ Add holding") }
        assertEquals(20, symbolBoxes().fetchSemanticsNodes().size)
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Run the backtest"))
        assertTrue("no more than 20 holdings", !shown("+ Add holding"))
        click("Run the backtest")
        assertEquals(3, runs.single().holdings.size)
    }

    @Test fun loadingFailureAndTheReport() {
        portfolio()
        res = Load.Busy("Backtesting 3 holdings")
        compose.waitForIdle()
        node("Backtesting 3 holdings")
        res = Load.Failed("NIFTYBEES has no price history for that period")
        compose.waitForIdle()
        node("NIFTYBEES has no price history for that period")
        res = Load.Done(PortfolioFixtures.portfolio)
        compose.waitForIdle()
        for (t in listOf("Equity curve", "Metrics", "CAGR", "Holdings", "Health", "Insights", "Costs")) node(t)
        node("Prices: Upstox")
        node(" · dashed: benchmark", substring = true)
        node("Benchmark CAGR")
        node("Monte Carlo (${PortfolioFixtures.portfolio.result.monteCarlo.paths} paths)")
    }

    @Test fun theHoldingsAnalyserIsOfferedInLiveModeOnly() {
        portfolio(live = false)
        assertTrue(!shown("Analyse my holdings"))
    }

    @Test fun analysingTheHoldings() {
        portfolio(live = true)
        click("Analyse my holdings")
        assertEquals(1, analyses)
        an = Load.Busy("Reading your holdings")
        compose.waitForIdle()
        node("Reading your holdings")
        an = Load.Failed("Log in to Zerodha to analyse your holdings.")
        compose.waitForIdle()
        an = Load.Done(PortfolioFixtures.analyzer)
        compose.waitForIdle()
        node("Current value")
        node("2")                                                          // holdings counted
        node("P&L")
        node("Equity curve")                                                // the analysis report follows
    }

    // ---- SIP ------------------------------------------------------------------------------------------

    @Test fun theSipRunsWithTheDefaults() {
        sipLab()
        click("Run the SIP")
        val r = sips.single()
        assertEquals(SipRun("NIFTYBEES", "NSE", Market.today().minusYears(5), Market.today(), 10_000.0, "monthly", 5, 0.0, "NIFTY"), r)
    }

    @Test fun everySipChoiceReachesTheRun() {
        sipLab()
        val sym = compose.onNode(hasSetTextAction() and hasText("NSE symbol"))
        sym.performTextClearance(); sym.performTextInput("hdfc bank!")
        assertEquals("HDFCBANK", sym.value())
        val amt = compose.onNode(hasSetTextAction() and hasText("Installment (Rs)"))
        amt.performTextClearance(); amt.performTextInput("25,000.50")
        assertEquals("digits only", "2500050", amt.value())
        amt.performTextClearance(); amt.performTextInput("123456789")
        assertEquals("at most 8 digits", "12345678", amt.value())
        click("quarterly"); click("20"); click("10%"); click("15 y"); click("none")
        click("Run the SIP")
        assertEquals(SipRun("HDFCBANK", "NSE", Market.today().minusYears(15), Market.today(), 12_345_678.0, "quarterly", 20, 10.0, null), sips.single())
    }

    @Test fun weeklySipsHaveNoDayOfMonth() {
        sipLab()
        assertTrue(shown("DAY OF MONTH"))
        click("weekly")
        assertTrue(!shown("DAY OF MONTH"))
        click("fortnightly")
        assertTrue(!shown("DAY OF MONTH"))
        click("Run the SIP")
        assertEquals("fortnightly", sips.single().freq)
    }

    @Test fun anEmptySymbolOrZeroAmountCannotRun() {
        sipLab()
        val amt = compose.onNode(hasSetTextAction() and hasText("Installment (Rs)"))
        amt.performTextClearance(); amt.performTextInput("0")
        node("Run the SIP").assertIsNotEnabled()
        amt.performTextClearance(); amt.performTextInput("1")
        node("Run the SIP").assertIsEnabled()
        compose.onNode(hasSetTextAction() and hasText("NSE symbol")).performTextClearance()
        node("Run the SIP").assertIsNotEnabled()
        node("Run the SIP").performClick()
        assertTrue(sips.isEmpty())
    }

    @Test fun sipLoadingFailureAndResults() {
        sipLab()
        sipRes = Load.Busy("Reading price history")
        compose.waitForIdle()
        node("Reading price history")
        sipRes = Load.Failed("No prices for NIFTYBEES")
        compose.waitForIdle()
        node("No prices for NIFTYBEES")
        sipRes = Load.Done(PortfolioFixtures.sip)
        compose.waitForIdle()
        for (t in listOf("Outcome", "Invested", "Value vs invested", "Against a lump sum", "Same plan in NIFTY", "Year by year", "Deepest drawdown")) node(t)
        node("Prices: Upstox. Dashed: money put in.")
        node("Installments")
        node("${PortfolioFixtures.sip.result.headline.installments}")
    }
}

/** The Portfolio and SIP labs with results, on every device set-up: screenshots, layout lint and a click smoke. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PortfolioScreensLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = DeviceConfig.matrix()

        /** Real layout bugs found here, skipped with this text until fixed. */
        val BUGS = emptyMap<String, String>()
    }

    @Test fun portfolioReport() {
        checkScreen("portfolio-report", BUGS) {
            PortfolioLabContent(true, Load.Done(PortfolioFixtures.portfolio), Load.Done(PortfolioFixtures.analyzer), onAnalyze = {}, onRun = { _, _, _, _, _, _ -> })
        }
        smokeEveryAction()
    }

    @Test fun sipReport() {
        checkScreen("sip-report", BUGS) { SipLabContent(Load.Done(PortfolioFixtures.sip)) { _, _, _, _, _, _, _, _, _ -> } }
        smokeEveryAction()
    }
}
