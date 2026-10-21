package com.optionslab.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.MarketContext
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Home's Market context card (13 Oct review): opened on an event day, it says how far the US CPI dates are listed. */
@RunWith(AndroidJUnit4::class)
class MarketContextCardTest {
    // 21 Oct, enhancement round 4: the market clock at noon IST on a trading day unless the test sets its own.
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    @get:Rule val compose = createComposeRule()

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun anOpenedEventCardSaysHowFarTheCpiDatesGo() {
        compose.setContent { IraAlgoTheme("light") { MarketContextContent(MarketContext.Card(event = "Today is an event day: RBI policy.")) } }
        compose.waitForIdle()
        assertTrue(shown("Today is an event day: RBI policy."))
        assertFalse(shown("later releases are not warned yet"))
        compose.onAllNodesWithText("Today is an event day", substring = true).onFirst().areaCClick()
        compose.waitForIdle()
        assertTrue(shown("US CPI dates are listed until"))
        assertTrue(shown("later releases are not warned yet"))
    }
}
