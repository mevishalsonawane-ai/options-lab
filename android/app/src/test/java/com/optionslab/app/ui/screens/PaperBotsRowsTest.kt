package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.VixDivArm
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.ParkedArms
import com.optionslab.engine.orb.VixDivRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 9 Oct (Boss's yes): "VIX divergence (not proven)" on Home's Strategies card - labelled not proven, its research above its
 * rules, its paper test, off by default, its switch paper only - and Liquidity 15+5's parked FINNIFTY, said with its record
 * under the row with a tap that switches it back on.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class PaperBotsRowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val off = VixDivArm.View(false, emptyMap(), emptyList(), emptyList(), emptyList())

    private fun home(v: VixDivArm.View, onArm: (Boolean) -> Unit = {}) = compose.setContent {
        IraAlgoTheme("light") {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                StrategyArmContent(false, false, emptyList(), emptyMap(), emptyMap(), false, RecordingStrategyActions(), {},
                    orbRows = { Text("ORB rows") }, reauth = StrategyFakes.reauth, nightRow = { Text("Night row"); VixDivRowContent(v, onArm) })
            }
        }
    }

    @Test fun vixDivergenceIsOnHomeStrategiesNotProvenAndOffByDefault() {
        val asked = ArrayList<Boolean>()
        // The store's own first view: what Home shows before Boss touches the switch.
        home(VixDivArm.view.value) { asked += it }
        compose.waitForIdle()
        compose.onNodeWithText(VixDivRules.LABEL).assertExists()
        compose.onNodeWithText(VixDivRules.NOT_PROVEN).assertExists()
        // Under the NSE arms (after Night).
        assertTrue(compose.onNodeWithText("Night row").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText(VixDivRules.LABEL).fetchSemanticsNode().boundsInRoot.top)
        val rec = compose.onNodeWithText(keepNumbersWhole(VixDivRules.RECORD)).fetchSemanticsNode().boundsInRoot.top
        val rules = compose.onNodeWithText(keepNumbersWhole(VixDivRules.RULES)).fetchSemanticsNode().boundsInRoot.top
        assertTrue("the research above the rules", rec < rules)
        for (w in listOf("+₹67/day NIFTY", "+₹89/day BANKNIFTY", "+₹70/day (29 trades) NIFTY", "+₹112/day (45 trades) BANKNIFTY", "q 1.0", "~150 paper trades"))
            assertTrue(w, w in VixDivRules.RECORD)
        compose.onNodeWithText("OFF").assertExists()
        compose.onNodeWithText(keepNumbersWhole(paperTestLine(VixDivRules.LABEL, emptyList(), 150))).assertExists()
        assertTrue(paperTestLine(VixDivRules.LABEL, emptyList(), 150).startsWith("${VixDivRules.LABEL} is still on its paper test: 0 of 15 trades"))
        compose.onNodeWithContentDescription("Paper only: switch ${VixDivRules.LABEL}").assertIsOff().performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), asked)
    }

    @Test fun armedItSaysPaperOnlyAndEachIndexsLastDecision() {
        home(off.copy(armed = true, status = mapOf("NIFTY" to "vix_late (10:30 check)", "BANKNIFTY" to "no signal yet (10:30: index +0.01%, VIX +2.50%)")))
        compose.waitForIdle()
        compose.onNodeWithText("ON · PAPER ONLY").assertExists()
        compose.onNodeWithContentDescription("Paper only: switch ${VixDivRules.LABEL}").assertIsOn()
        compose.onNodeWithText(keepNumbersWhole("NIFTY: vix_late (10:30 check)")).assertExists()
        compose.onNodeWithText(keepNumbersWhole("BANKNIFTY: no signal yet (10:30: index +0.01%, VIX +2.50%)")).assertExists()
    }

    @Test fun liquiditysParkedFinniftyIsSaidWithItsRecordAndATapSwitchesItBackOn() {
        val actions = RecordingStrategyActions()
        val view = StrategyFakes.orbView(armed = true).let { v ->
            v.copy(arms = v.arms.map { a -> if (a.arm.liquidity) a.copy(parked = listOf("FINNIFTY")) else a })
        }
        compose.setContent {
            IraAlgoTheme("light") {
                Column(Modifier.verticalScroll(rememberScrollState())) { OrbRowsContent(view, false, actions, StrategyFakes.reauthWhy) }
            }
        }
        compose.waitForIdle()
        assertEquals("−₹8,035 over 8 paper trades", ParkedArms.record("FINNIFTY"))
        compose.onNodeWithText(keepNumbersWhole("FINNIFTY parked on your OK (−₹8,035 over 8 paper trades): its books stay off with the switch.")).assertExists()
        compose.onNodeWithText("Switch FINNIFTY back on").performClick()
        compose.waitForIdle()
        assertTrue(actions.calls.toString(), "orb unpark FINNIFTY" in actions.calls)
    }
}
