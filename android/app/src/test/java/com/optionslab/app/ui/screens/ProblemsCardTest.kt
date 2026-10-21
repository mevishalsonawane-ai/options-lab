package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.ui.components.expandTag
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.AutomationSummary
import com.optionslab.ira.OpenProblems
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Automation card's "Problems found" (12 Oct, Boss: "find out why all my agents were not able to find these issues"): the
 * count by severity and the most serious problem in sight, the others behind "Show all problems"; nothing shown when none is open.
 */
@Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class ProblemsCardTest {
    // 21 Oct, enhancement round 4: the market clock at noon IST on a trading day unless the test sets its own.
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun noNetwork() { assertEquals("no host may be reached", emptyList<String>(), NetworkGuard.blocked.toList()) }

    private fun set(content: @Composable () -> Unit) = compose.setContent { IraAlgoTheme("light") { content() } }
    private fun shows(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    private val at = 1_760_000_000_000L
    private fun board(): OpenProblems.Board {
        var b = OpenProblems.Board()
        b = OpenProblems.report(b, OpenProblems.Problem("orders:chg", "Order review", OpenProblems.Severity.WARN,
            "ORB (paper) · charges ate the day's gross: 9 trades"), at)
        b = OpenProblems.report(b, OpenProblems.Problem("coverage:MCX:watch", "Session coverage", OpenProblems.Severity.SERIOUS,
            "MCX is open with 3 armed, but the market watch is not running"), at + 1)
        return b
    }

    @Test fun theMostSeriousIsInSightTheRestBehindShowAll() {
        val lines = AutomationSummary.withProblems(AutomationSummary.Lines(manager = "Trade manager: x"), board(), at + 2)
        set { AutomationCardContent(lines) {} }
        compose.waitForIdle()
        compose.onNodeWithTag("automation-problems").assertIsDisplayed()
        assertTrue(shows("Problems found: 1 serious, 1 warning"))
        assertTrue(shows("SERIOUS · Session coverage: MCX is open"))
        assertTrue("the warning is folded", !shows("charges ate the day"))
        compose.onNodeWithTag(expandTag("automation-problems")).performClick()
        compose.waitForIdle()
        assertTrue(shows("WARNING · Order review: ORB (paper)"))
    }

    @Test fun nothingOpenShowsNothing() {
        set { AutomationCardContent(AutomationSummary.Lines(manager = "Trade manager: x")) {} }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("automation-problems").fetchSemanticsNodes().isEmpty())
    }
}
