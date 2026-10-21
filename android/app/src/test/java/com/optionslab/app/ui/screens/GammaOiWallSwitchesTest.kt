package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ira.Automations
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings → Jarvis → Market alerts (14 Oct, Boss approved): the morning gamma line and the OI-wall check (shadow) each
 * have their own switch, on by default, described in plain words as information only / a record only; a tap turns it off.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class GammaOiWallSwitchesTest {
    // 21 Oct, enhancement round 4: the market clock at noon IST on a trading day unless the test sets its own.
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun down() {
        Automations.set(Automations.Auto.GAMMA, true)
        Automations.set(Automations.Auto.OIWALL, true)
    }

    private fun shows(t: String) = compose.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun bothSwitchesAreOnByDefaultAndPlainlyDescribed() {
        compose.setContent {
            IraAlgoTheme("light") {
                Column {
                    AutomationSubRow(Automations.Auto.GAMMA, groupOn = true)
                    AutomationSubRow(Automations.Auto.OIWALL, groupOn = true)
                }
            }
        }
        compose.waitForIdle()
        assertTrue(shows("Morning gamma line"))
        assertTrue(shows("OI-wall check (shadow)"))
        // COMPACT (15 Oct): each setting's explanation only in its own dialog (a tap on its name; OK closes it).
        assertFalse(shows("says nothing about direction"))
        compose.onNodeWithContentDescription("About Morning gamma line").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); compose.waitForIdle()
        assertTrue(shows("says nothing about direction; information only, never a trade signal"))
        assertFalse("only that setting's text", shows("Never an order, paper or live"))
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); compose.waitForIdle()
        compose.onNodeWithContentDescription("About OI-wall check (shadow)").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); compose.waitForIdle()
        assertTrue(shows("Never an order, paper or live"))
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); compose.waitForIdle()
        val switches = compose.onAllNodes(isToggleable())
        assertEquals(2, switches.fetchSemanticsNodes().size)
        switches[0].assertIsOn()
        switches[1].assertIsOn()
        // A tap on the gamma line's switch: off, saved as its own switch; the OI-wall one is untouched.
        switches[0].performClick()
        compose.waitForIdle()
        switches[0].assertIsOff()
        assertFalse(Automations.ownSwitch(Automations.Auto.GAMMA))
        assertTrue(Automations.ownSwitch(Automations.Auto.OIWALL))
    }

    @Test fun withMarketAlertsOffTheRowSaysItStaysQuiet() {
        compose.setContent { IraAlgoTheme("light") { AutomationSubRow(Automations.Auto.OIWALL, groupOn = false) } }
        compose.waitForIdle()
        assertTrue(shows("(Market alerts is off: this stays quiet.)"))
        compose.onNodeWithContentDescription("About OI-wall check (shadow)").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); compose.waitForIdle()
        assertTrue(shows("the record goes on and Jarvis stays quiet"))
    }
}
