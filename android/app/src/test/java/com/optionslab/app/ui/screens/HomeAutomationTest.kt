package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.Paper
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.ui.Load
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.sandbox.PositionBook
import com.optionslab.ira.AutomationSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every line of the Automation card, the freeze warning too. */
internal object AutomationFixtures {
    val lines = AutomationSummary.Lines(
        manager = "Trade manager: 2 trades managed today (1 open) · ORB Fresh acting on paper",
        autopilot = "Autopilot: 1 change today, 0 reverted",
        lab = "Lab: 3 variants · 1 idea on paper",
        orders = "Order review · Today: 7 orders · 2 flaws (est. ₹340) · 1 improvement testing",
        orderFlow = "Order flow: shadow only, no entry skipped",
        brain = "Brain: busy day · 1 strategy paused on paper",
        frozen = "Learning frozen (paper): nothing is applied until you unfreeze it.",
        flatBy = "Flat-by: index options 15:25 · stock and fund 15:20 · MCX 23:20 (10 min before its close) · expiry day 15:05 first.",
    )

    /** A section's stand-in: its title and a few lines and buttons, as long as the real ones. */
    @Composable fun Section(s: AutomationSummary.Section) {
        Column {
            Text(s.title)
            Text("section body ${s.key}")
            repeat(6) { Text("Line $it of the ${s.title} section, as long as a real record line reads on a phone.") }
            com.optionslab.app.ui.components.BrassButton("${s.title} details", Modifier.padding(top = 6.dp)) {}
        }
    }
}

/**
 * Home after 11 Oct (Boss: trading first, less clutter): the cards' order, the Automation card's lines each opening its section
 * in a full-height sheet, and the speed budget - a price tick recomposes the money and the chart, never the strategies or
 * the Automation card.
 */
@Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class HomeAutomationTest {
    // 21 Oct, enhancement round 4: the market clock at noon IST on a trading day unless the test sets its own.
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order = 100) val dump = com.optionslab.app.testing.DumpOnFailure(compose)
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()

    @After fun noNetwork() { assertEquals("no host may be reached", emptyList<String>(), NetworkGuard.blocked.toList()) }

    private fun set(content: @Composable () -> Unit) = compose.setContent { IraAlgoTheme("light") { content() } }
    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()
    private fun scrollTo(t: String, sub: Boolean = false) {
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToNode(hasText(t, substring = sub))
    }

    // Built once, so a recomposition hands Home the same cards (as the app's remembered lambdas do).
    private val onGo: (String) -> Unit = {}
    private val onRow: (RowTarget) -> Unit = {}
    private val mcx = listOf("mcx" to @Composable { Text("MCX card") })

    @Test fun everySummaryLineOpensItsSection() {
        val shown = ArrayList<AutomationSummary.Section>()
        set { AutomationCardContent(AutomationFixtures.lines) { s -> SideEffect { if (shown.lastOrNull() != s) shown += s }; AutomationFixtures.Section(s) } }
        compose.waitForIdle()
        assertTrue(shows(AUTOMATION_TITLE))
        for (s in AutomationSummary.Section.values()) {
            compose.onNodeWithTag("automation-line-${s.key}").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("automation-sheet-${s.key}").assertIsDisplayed()
            compose.onNodeWithText("section body ${s.key}").assertIsDisplayed()
            // The section's own buttons are there, a tap away (the real sections keep every button and sheet).
            assertTrue(shows("${s.title} details"))
            compose.onNodeWithText("Close").performClick()
            compose.waitForIdle()
            assertTrue("${s.key}'s sheet closes", compose.onAllNodesWithTag("automation-sheet-${s.key}").fetchSemanticsNodes().isEmpty())
        }
        assertEquals(AutomationSummary.Section.values().toList(), shown)
    }

    @Test fun theFreezeWarningOpensAutopilot() {
        set { AutomationCardContent(AutomationFixtures.lines) { s -> AutomationFixtures.Section(s) } }
        compose.waitForIdle()
        compose.onNodeWithTag("automation-frozen").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("automation-sheet-autopilot").assertIsDisplayed()
        compose.onNodeWithText("section body autopilot").assertIsDisplayed()
    }

    @Test fun aBuildWithoutTheLabOrAutopilotShowsOnlyItsLines() {
        set { AutomationCardContent(AutomationSummary.Lines(manager = "Trade manager: x", orderFlow = "Order flow: y", brain = "Brain: z")) {} }
        compose.waitForIdle()
        assertEquals(3, AutomationSummary.Section.values().count { compose.onAllNodesWithTag("automation-line-${it.key}").fetchSemanticsNodes().isNotEmpty() })
        assertTrue(compose.onAllNodesWithTag("automation-line-lab").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("automation-frozen").fetchSemanticsNodes().isEmpty())
    }

    /** The card on the app's own books (memory only, nothing loaded here): a line per section, from the StateFlows' values. */
    @Test fun theFeedCardReadsTheBooksInMemory() {
        assumeFalse("IraGoldAlgo has no Automation card", com.optionslab.app.BuildConfig.GOLD)
        set { AutomationFeedCard(load = {}) { s -> AutomationFixtures.Section(s) } }
        compose.waitForIdle()
        for (prefix in listOf("Trade manager: ", "Order flow: ", "Brain: ")) assertTrue(prefix, shows(prefix, sub = true))
        compose.onNodeWithTag("automation-line-brain").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("section body brain").assertIsDisplayed()
    }

    @Test fun homeIsTradingFirstThenTheAutomationCardLast() {
        set {
            AlmanacContent(false, false, mapOf("BANKNIFTY" to HomeFixtures.quote), null, HomeFixtures.daily, Load.Idle, Load.Done(HomeFixtures.paper),
                onGo, onRow, whatsNew = { Text("what's new card") }, glance = { Text("glance card") }, afterStrategies = mcx,
                automation = { AutomationCardContent(AutomationFixtures.lines) { s -> AutomationFixtures.Section(s) } }) { Text("strategies card") }
        }
        compose.waitForIdle()
        val order = listOf("what's new card", "glance card", "Capital", "Live orders", "strategies card", "MCX card", "BANKNIFTY", AUTOMATION_TITLE)
        val tops = order.map { t ->
            scrollTo(t)
            compose.waitForIdle()
            t to compose.onNodeWithText(t).fetchSemanticsNode().boundsInRoot.top
        }
        // One screen tall enough for all: the order is read from the positions on it.
        scrollTo(order.first())
        compose.waitForIdle()
        val now = order.map { t -> compose.onNodeWithText(t).fetchSemanticsNode().boundsInRoot.top }
        assertEquals("the cards in order: $tops", now.sorted(), now)
        // None of the five sections is a card of its own any more; each is a line of the Automation card.
        for (old in listOf("Trade manager", "Autopilot", "Strategy lab", "Order flow", "Brain")) assertTrue(old, !shows(old))
        for (s in AutomationSummary.Section.values()) assertTrue(s.key, compose.onAllNodes(hasTestTag("automation-line-${s.key}")).fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * The speed budget (speed round 3's rule, for 11 Oct's Home): with every card on screen, a price tick - BANKNIFTY moves and
     * the paper book is repriced - recomposes the chart and the money (once each a tick), and never the Strategies card or the
     * Automation card, whose lines come from their own books.
     */
    @Test fun aPriceTickRecomposesTheMoneyAndTheChartOnly() {
        val counts = HashMap<String, Int>()
        val probe: (String) -> Unit = { counts[it] = (counts[it] ?: 0) + 1 }
        var strategiesRuns = 0
        var automationRuns = 0
        val strategies: @Composable () -> Unit = { SideEffect { strategiesRuns++ }; Text("strategies card") }
        val automation: @Composable () -> Unit = {
            SideEffect { automationRuns++ }
            if (com.optionslab.app.BuildConfig.GOLD) AutomationCardContent(AutomationFixtures.lines) {} else AutomationFeedCard(load = {}) {}
        }
        val glance: @Composable () -> Unit = { Text("glance card") }
        var quotes by mutableStateOf(mapOf("BANKNIFTY" to HomeFixtures.quote))
        var paper by mutableStateOf<Load<Paper.Snapshot>>(Load.Done(HomeFixtures.paper))
        set {
            CompositionLocalProvider(LocalHomeProbe provides probe) {
                AlmanacContent(false, false, quotes, null, HomeFixtures.daily, Load.Idle, paper, onGo, onRow, glance = glance,
                    afterStrategies = mcx, automation = automation, strategies = strategies)
            }
        }
        compose.waitForIdle()
        for (card in listOf("money", "orders", "strategies", "mcx", "chart", "automation")) assertTrue("$card is on screen: $counts", (counts[card] ?: 0) >= 1)
        val before = HashMap(counts)
        val strategiesBefore = strategiesRuns
        val automationBefore = automationRuns
        val ticks = 5
        for (i in 1..ticks) {
            compose.runOnIdle {
                val px = HomeFixtures.quote.last + 10.0 * i
                quotes = mapOf("BANKNIFTY" to HomeFixtures.quote.copy(last = px, spark = HomeFixtures.quote.spark + px, minute = HomeFixtures.quote.minute + i))
                val pos = HomeFixtures.position.copy(ltp = HomeFixtures.position.ltp + i, pnl = HomeFixtures.position.pnl - 75.0 * i)
                paper = Load.Done(HomeFixtures.paper.copy(positions = PositionBook(listOf(pos), pos.pnl, pos.pnl, 0.0, pos.pnl)))
            }
            compose.waitForIdle()
        }
        val per = { card: String -> ((counts[card] ?: 0) - (before[card] ?: 0)).toDouble() / ticks }
        println("HOME SPEED per tick: chart ${per("chart")}, money ${per("money")}, orders ${per("orders")}, strategies ${per("strategies")}, " +
            "mcx ${per("mcx")}, automation ${per("automation")}")
        // The chart and the money follow every tick: at least once, at most twice (the price and the book may land a frame apart).
        assertTrue("chart ${per("chart")} a tick", per("chart") in 1.0..2.0)
        assertTrue("money ${per("money")} a tick", per("money") in 1.0..2.0)
        // Nothing else on Home is recomposed by a tick.
        assertEquals(0.0, per("strategies"), 0.0)
        assertEquals(0.0, per("automation"), 0.0)
        assertEquals(0.0, per("mcx"), 0.0)
        assertEquals("the Strategies card's own content", strategiesBefore, strategiesRuns)
        assertEquals("the Automation card's own content", automationBefore, automationRuns)
    }
}

/** The Automation card, Home with it and a section's sheet on the six research set-ups: no overlap upright, on its side, at font 2.0. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeAutomationLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    @get:Rule val watchdog = com.optionslab.app.testing.ResearchWatchdog()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = com.optionslab.app.testing.ResearchMatrix.six()

        val BUGS = emptyMap<String, String>()
    }

    @After fun noNetwork() { assertEquals(emptyList<String>(), NetworkGuard.blocked.toList()) }

    private val mcx = listOf("mcx" to @Composable { Text("MCX card") })

    @Test fun homeWithTheAutomationCard() {
        checkScreen("home-automation", BUGS) {
            AlmanacContent(false, false, mapOf("BANKNIFTY" to HomeFixtures.quote), null, HomeFixtures.daily, Load.Idle, Load.Done(HomeFixtures.paper), {}, {},
                afterStrategies = mcx, automation = { AutomationCardContent(AutomationFixtures.lines) { s -> AutomationFixtures.Section(s) } }) {}
        }
        // A lazy list: at a large font or on its side the card starts below the fold.
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToNode(hasTestTag("automation-line-brain"))
        compose.waitForIdle()
        // At font 2.0 on a small phone the card is taller than the screen: the lazy list shows its top; the line is there.
        compose.onNodeWithTag("automation-line-brain").assertExists()
        lint("home-automation-scrolled", BUGS)
        if (device.name == "phone-font1.3-light") smokeEveryAction()
    }

    @Test fun theCardAlone() = checkScreen("automation-card", BUGS) {
        // On Home the card sits in the scrolling list; alone it scrolls too (taller than a small phone at font 2.0).
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(14.dp)) {
            AutomationCardContent(AutomationFixtures.lines) { s -> AutomationFixtures.Section(s) }
        }
    }

    @Test fun aSectionsSheet() {
        checkScreen("automation-sheet", BUGS) { AutomationSheet(AutomationSummary.Section.MANAGER, onClose = {}) { AutomationFixtures.Section(AutomationSummary.Section.MANAGER) } }
        compose.onNodeWithText("Close").assertIsDisplayed()
    }
}
