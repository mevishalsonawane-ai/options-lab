package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.OrderReviewHost
import com.optionslab.app.ui.components.expandTag
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Order review section (Home → Automation card → Order review): the line and the warnings in sight, the rest behind "Show details". */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class OrderReviewCardTest {
    // 21 Oct, enhancement round 4: the market clock at noon IST on a trading day unless the test sets its own.
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val view = OrderReviewHost.View(
        line = "Today: 7 orders · 2 flaws (est. ₹340) · 1 improvement testing",
        warnings = listOf("Needs a fix: ORB (live): stop not resting - 3 times on 2 days"),
        flaws = listOf("ORB Fresh (paper) · gave back most of its best gain: reached 130.00 · est. ₹300"),
        summaries = listOf("ORB Fresh (paper): 1 order, 3 trades, 2 won 1 lost, net +₹270 after charges ₹120 · flaws: gave back ×1"),
        lab = listOf("ORB Fresh: V7 · target 60 pts → 50 pts · no compared day yet · shadowing"),
        proposals = listOf("Pine (paper): gave back most of its best gain. A proposal only: the strategy lab cannot test this kind of change for Pine."),
        fixes = listOf("ORB (live): stop not resting. Needs a fix (flagged for you and the developer); nothing was changed."),
        notes = listOf("2 live fills not judged for slippage: fewer than 10 earlier fills of that strategy to compare with."),
    )

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun theLineAndWarningsShowFoldedAndTheRestOpensOnShowDetails() {
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) { OrderReviewContent(view) } } }
        compose.waitForIdle()
        assertTrue(shown(ORDER_REVIEW_TITLE))
        assertTrue(shown("Today: 7 orders · 2 flaws"))
        // Warnings are never folded.
        assertTrue(shown("Needs a fix: ORB (live): stop not resting"))
        assertTrue(shown("never places, changes or cancels an order"))
        // Folded: the flaws, the strategies, the lab and the proposals are not shown yet.
        assertFalse(shown("gave back most of its best gain: reached"))
        assertFalse(shown("V7 · target"))
        compose.onNodeWithTag(expandTag("order-review")).performScrollTo().performClick()
        compose.waitForIdle()
        for (t in listOf("gave back most of its best gain: reached", "2 won 1 lost", "V7 · target 60 pts → 50 pts", "A proposal only",
            "nothing was changed", "not judged for slippage"))
            assertTrue(t, shown(t))
    }
}
