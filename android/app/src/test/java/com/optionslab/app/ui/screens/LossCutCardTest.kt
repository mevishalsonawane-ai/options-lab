package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.LossCutHost
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1 Nov, Settings → Bot settings → Loss cuts (paper): both switches show OFF; a tap switches each on (paper only), and again off.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class LossCutCardTest {
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun down() { LossCutHost.setForTest() }

    @Test fun bothSwitchesShowOffAndATapSwitchesEachOnAndOff() {
        LossCutHost.setForTest()
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) { LossCutCard() } } }
        compose.waitForIdle()
        compose.onNodeWithText("Loss cuts (paper)", ignoreCase = true).assertExists()
        compose.onNodeWithText("Off: R72 takes every break").assertExists()
        compose.onNodeWithText("Off: R52 keeps its own exits only").assertExists()
        compose.onNodeWithText(LOSS_CUT_WIDE).performClick()
        compose.waitUntil(5_000) { LossCutHost.wideDayOn() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("On: R72 skips", substring = true) > 0 }
        assertFalse("the other stays off", LossCutHost.noProgressOn())
        compose.onNodeWithText(LOSS_CUT_NP).performClick()
        compose.waitUntil(5_000) { LossCutHost.noProgressOn() }
        compose.onNodeWithText(LOSS_CUT_NP).performClick()
        compose.waitUntil(5_000) { !LossCutHost.noProgressOn() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("Off: R52 keeps its own exits only") > 0 }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String, substring: Boolean = false): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = substring)).fetchSemanticsNodes().size
}
