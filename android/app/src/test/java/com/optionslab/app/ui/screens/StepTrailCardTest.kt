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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.StepTrailExits
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.StepTrail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 15 Nov 2026 (Boss's exception, third version): Bot settings' "Breakeven at +10%" card ([StepTrailCardFlow]): labelled "failed gates, Boss's
 * exception" with its honest record; on, switching it off asks the PIN first (where a PIN is asked) and goes off only once it is given;
 * off, on again is one tap; before the switch is read it offers none. Also the arms' rows' line ([stepTrailLine]).
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class StepTrailCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    private fun show(view: StepTrailExits.View, pinAsked: Boolean, on: () -> Unit, offConfirmed: () -> Unit) = compose.setContent {
        IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            StepTrailCardFlow(view, null, pinAsked = { pinAsked }, onOn = on, onOffConfirmed = offConfirmed) { ok, cancel ->
                Text("PIN asked")
                androidx.compose.material3.TextButton(onClick = ok) { Text("PIN right") }
                androidx.compose.material3.TextButton(onClick = cancel) { Text("PIN cancel") }
            }
        } }
    }

    @Test fun onItIsLabelledAndOffAsksThePinFirst() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        var ons = 0; var offs = 0
        show(StepTrailExits.View(on = true, loaded = true), pinAsked = true, { ons++ }, { offs++ })
        compose.waitForIdle()
        assertTrue(shows("Breakeven at +10%"))
        assertTrue(shows(StepTrail.SWITCH))
        assertTrue(shows("On: new trades of the three arms get the breakeven at +10%"))
        assertTrue(shows("Failed gates, Boss's exception", sub = true))
        assertTrue(shows("R179", sub = true))
        assertTrue(shows("keeps its arm's own stop", sub = true))
        assertTrue(shows("10% above the fill, the stop moves to breakeven", sub = true))
        compose.onNodeWithText(StepTrail.SWITCH).performClick()
        compose.waitForIdle()
        assertTrue(shows("PIN asked"))
        assertEquals("not off before the PIN", 0, offs)
        compose.onNodeWithText("PIN cancel").performClick()
        compose.waitForIdle()
        assertEquals("cancelled: still on", 0, offs)
        compose.onNodeWithText(StepTrail.SWITCH).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("PIN right").performClick()
        compose.waitForIdle()
        assertEquals(1, offs); assertEquals(0, ons)
    }

    @Test fun withNoPinToAskOffIsOneTap() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        var offs = 0
        show(StepTrailExits.View(on = true, loaded = true), pinAsked = false, { }, { offs++ })
        compose.waitForIdle()
        compose.onNodeWithText(StepTrail.SWITCH).performClick()
        compose.waitForIdle()
        assertFalse(shows("PIN asked"))
        assertEquals(1, offs)
    }

    @Test fun offOnAgainIsOneTapWithNoPin() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        var ons = 0; var offs = 0
        show(StepTrailExits.View(on = false, loaded = true), pinAsked = true, { ons++ }, { offs++ })
        compose.waitForIdle()
        assertTrue(shows("Off: new trades use the arms' own exits"))
        compose.onNodeWithText(StepTrail.SWITCH).performClick()
        compose.waitForIdle()
        assertFalse(shows("PIN asked"))
        assertEquals(1, ons); assertEquals(0, offs)
    }

    @Test fun beforeTheSwitchIsReadItOffersNone() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        var taps = 0
        show(StepTrailExits.View(), pinAsked = true, { taps++ }, { taps++ })
        compose.waitForIdle()
        assertTrue(shows("Reading the switch..."))
        assertFalse(shows(StepTrail.SWITCH))
        assertEquals(0, taps)
    }

    @Test fun theArmsRowsSayItWhenOnAndOnlyForTheThree() {
        for (src in listOf(com.optionslab.engine.orb.DonchianArm.SOURCE, com.optionslab.engine.orb.BreadthDonchianArm.SOURCE, "liquidity")) {
            val line = stepTrailLine(src, true)!!
            assertTrue(line, line.contains("failed gates, Boss's exception") && line.contains("R179"))
            assertNull(stepTrailLine(src, false))
        }
        for (other in listOf(com.optionslab.engine.orb.OovArm.SOURCE, com.optionslab.engine.orb.Solo2.SOURCE, "orb")) assertNull(stepTrailLine(other, true))
    }
}
