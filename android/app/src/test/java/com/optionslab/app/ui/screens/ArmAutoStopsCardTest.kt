package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.ArmAutoStopsHost
import com.optionslab.app.data.PassedArmsHost
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.OovArm
import com.optionslab.ira.ArmAutoStops
import com.optionslab.ira.ArmAutoStops.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule as JRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 19 Nov 2026 (Boss: "i want it to run until i stop them"): Bot settings' four rows ([ArmAutoStopsCardFlow]) - each titled "<rule>
 * applies to R51, R72 and Liquidity"; on is one tap with no PIN; off asks the PIN first and goes off only once it is given; before the
 * switches are read none is offered. And R51's row says "auto-stop off by Boss's choice" while one of its stops is off.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class ArmAutoStopsCardTest {
    @get:JRule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:JRule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    private val allOff = ArmAutoStops.migrate(ArmAutoStops.State(), 1L, null)!!

    private fun show(view: ArmAutoStopsHost.View, on: (Rule) -> Unit, offConfirmed: (Rule) -> Unit) = compose.setContent {
        IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            ArmAutoStopsCardFlow(view, null, onOn = on, onOffConfirmed = offConfirmed) { ok, cancel ->
                Text("PIN asked")
                androidx.compose.material3.TextButton(onClick = ok) { Text("PIN right") }
                androidx.compose.material3.TextButton(onClick = cancel) { Text("PIN cancel") }
            }
        } }
    }

    @Test fun offByBossesChoiceEachRowIsLabelledAndOnIsOneTapWithNoPin() {
        val ons = ArrayList<Rule>(); val offs = ArrayList<Rule>()
        show(ArmAutoStopsHost.View(loaded = true, state = allOff), { ons += it }, { offs += it })
        compose.waitForIdle()
        assertTrue(shows(ARM_AUTOSTOPS_TITLE))
        assertTrue(shows(ArmAutoStops.CHOICE, sub = true))
        for (r in Rule.entries) {
            assertTrue(r.switch, shows(r.switch))
            assertTrue(r.switch.endsWith("applies to R51, R72 and Liquidity"))
        }
        assertTrue(shows("Off - ${ArmAutoStops.ROW}", sub = true))
        assertEquals("five switches", 5, Rule.entries.size)
        assertTrue(shows(Rule.JARVIS_CUTOFF.switch))
        // Review (19 Nov): no path lets Jarvis switch one on, so the card never says so.
        assertFalse(shows("Jarvis may switch one on", sub = true))
        assertTrue(shows("never Jarvis or a voice command", sub = true))
        compose.onNodeWithText(Rule.LIVE_STOP.switch).performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse("no PIN to switch it on", shows("PIN asked"))
        assertEquals(listOf(Rule.LIVE_STOP), ons); assertTrue(offs.isEmpty())
    }

    @Test fun offAsksThePinFirstAndOnlyThatRuleGoesOff() {
        val ons = ArrayList<Rule>(); val offs = ArrayList<Rule>()
        show(ArmAutoStopsHost.View(loaded = true, state = ArmAutoStops.State(migrated = true)), { ons += it }, { offs += it })
        compose.waitForIdle()
        assertTrue(shows("On (as before 19 Nov)", sub = true))
        compose.onNodeWithText(Rule.PAPER_ACCOUNT.switch).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(shows("PIN asked"))
        assertTrue("not off before the PIN", offs.isEmpty())
        compose.onNodeWithText("PIN cancel").performClick()
        compose.waitForIdle()
        assertTrue("cancelled: still on", offs.isEmpty())
        compose.onNodeWithText(Rule.FORWARD_BAR.switch).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("PIN right").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Rule.FORWARD_BAR), offs); assertTrue(ons.isEmpty())
    }

    @Test fun beforeTheSwitchesAreReadItOffersNone() {
        var taps = 0
        show(ArmAutoStopsHost.View(), { taps++ }, { taps++ })
        compose.waitForIdle()
        assertTrue(shows("Reading the switches..."))
        Rule.entries.forEach { assertFalse(shows(it.switch)) }
        assertEquals(0, taps)
    }

    @Test fun r51sRowSaysItsAutoStopIsOffByBossesChoiceAndAnotherArmsDoesNot() {
        val view = ArmAutoStopsHost.View(loaded = true, state = allOff)
        val v = PassedArmsHost.View(armed = mapOf(DonchianArm.SOURCE to true, OovArm.SOURCE to true))
        compose.setContent {
            IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
                PassedArmRowContent(DonchianArm.SOURCE, v, view.rowLine(DonchianArm.SOURCE)) {}
                PassedArmRowContent(OovArm.SOURCE, v, view.rowLine(OovArm.SOURCE)) {}
            } }
        }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("Auto-stop off by Boss's choice (19 Nov)", substring = true).fetchSemanticsNodes().size)
        assertTrue(shows("the forward-test switch-off, the paper account stop no longer stop it", sub = true))
    }

    @Test fun liquiditysRowSaysItsAutoStopsAreOffAndNoOtherOrbRowDoes() {
        val view = ArmAutoStopsHost.View(loaded = true, state = allOff)
        compose.setContent {
            IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
                OrbRowsContent(StrategyFakes.orbView(armed = true, lots = 1), false, RecordingStrategyActions(), StrategyFakes.reauthWhy,
                    autoStop = { view.rowLine(it) })
            } }
        }
        compose.waitForIdle()
        val tagged = compose.onAllNodes(androidx.compose.ui.test.hasTestTag("orb-autostop:${com.optionslab.engine.orb.LiquidityRules.ARM.source}"),
            useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, tagged.size)
        assertEquals("only Liquidity's row", 1, compose.onAllNodesWithText("Auto-stop off by Boss's choice (19 Nov)", substring = true,
            useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue(compose.onAllNodesWithText("the live stop rule, the self-review park and pause, the paper account stop, Jarvis's losing-arm cutoff no longer stop it",
            substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }
}
