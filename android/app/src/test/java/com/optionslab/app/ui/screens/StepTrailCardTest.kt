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
 * 15 Nov 2026 (Boss's exception, third version; 16 Nov, fourth version: the step lock and breakeven at +₹1,000): Bot settings' "Step lock and breakeven" card ([StepTrailCardFlow]): labelled "failed gates, Boss's
 * exception" with its honest record; on, switching it off asks the PIN first (where a PIN is asked) and goes off only once it is given;
 * off, on again is one tap; before the switch is read it offers none. Also the arms' rows' line ([stepTrailLine]).
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class StepTrailCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    private fun show(view: StepTrailExits.View, pinAsked: Boolean, on: () -> Unit, offConfirmed: () -> Unit,
                     lockOn: () -> Unit = {}, lockOff: () -> Unit = {}) = compose.setContent {
        IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            StepTrailCardFlow(view, null, pinAsked = { pinAsked }, onOn = on, onOffConfirmed = offConfirmed, onLockOn = lockOn,
                onLockOffConfirmed = lockOff) { ok, cancel ->
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
        assertTrue(shows("Step lock and breakeven"))
        assertTrue(shows(StepTrail.SWITCH))
        assertTrue(StepTrail.SWITCH.contains("failed gates, Boss's exception"))
        assertTrue(shows("On: new trades of the three arms get the breakeven at +10%"))
        // 16 Nov: the fourth version's own switch under it, labelled.
        assertTrue(shows(StepTrail.SWITCH_LOCK)); assertTrue(StepTrail.SWITCH_LOCK.contains("failed gates, Boss's exception"))
        assertTrue(shows("On: new R51 / R72 trades get the step lock, Liquidity the breakeven at +₹1,000 (V5)"))
        assertTrue(shows("Failed gates, Boss's exception", sub = true))
        assertTrue(shows("R179", sub = true))
        // 16 Nov: the fourth version's rules and R186's record.
        assertTrue(shows("the stop locks +₹500 a lot, then stays ₹500 a lot under each further ₹500 step", sub = true))
        assertTrue(shows("breakeven at +10% or +₹1,000 a lot, whichever comes first", sub = true))
        assertTrue(shows("R186", sub = true))
        assertTrue(shows("₹98,239", sub = true))
        assertTrue(shows("open trades from before keep the breakeven at +10% alone", sub = true))
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

    /** 16 Nov: the fourth version's switch - off (back to V4 alone) asks the PIN first; on again is one tap; never the rule's own off. */
    @Test fun theStepLocksOffAsksThePinAndGoesBackToV4() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        var offs = 0; var lockOffs = 0; var lockOns = 0
        show(StepTrailExits.View(on = true, loaded = true, lock = true), pinAsked = true, { }, { offs++ }, { lockOns++ }, { lockOffs++ })
        compose.waitForIdle()
        compose.onNodeWithText(StepTrail.SWITCH_LOCK).performClick()
        compose.waitForIdle()
        assertTrue(shows("PIN asked"))
        assertEquals("not off before the PIN", 0, lockOffs)
        compose.onNodeWithText("PIN right").performClick()
        compose.waitForIdle()
        assertEquals(1, lockOffs); assertEquals("the rule itself untouched", 0, offs); assertEquals(0, lockOns)
    }

    @Test fun theStepLockOnAgainIsOneTap() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        var lockOffs = 0; var lockOns = 0
        show(StepTrailExits.View(on = true, loaded = true, lock = false), pinAsked = true, { }, { }, { lockOns++ }, { lockOffs++ })
        compose.waitForIdle()
        assertTrue(shows("Off: new trades get the breakeven at +10% alone (V4)"))
        compose.onNodeWithText(StepTrail.SWITCH_LOCK).performClick()
        compose.waitForIdle()
        assertFalse(shows("PIN asked")); assertEquals(1, lockOns); assertEquals(0, lockOffs)
    }

    @Test fun withTheRuleOffTheStepLockSwitchIsNotShown() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        show(StepTrailExits.View(on = false, loaded = true), pinAsked = true, { }, { })
        compose.waitForIdle()
        assertFalse(shows(StepTrail.SWITCH_LOCK))
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
            assertTrue(line, line.contains("failed gates, Boss's exception") && line.contains("R179") && line.contains("R186"))
            assertNull(stepTrailLine(src, false))
        }
        // 16 Nov: each arm's own lock named.
        assertTrue(stepTrailLine(com.optionslab.engine.orb.DonchianArm.SOURCE, true)!!.startsWith(StepTrail.LABEL_L500))
        assertTrue(stepTrailLine(com.optionslab.engine.orb.BreadthDonchianArm.SOURCE, true)!!.startsWith(StepTrail.LABEL_L500))
        assertTrue(stepTrailLine("liquidity", true)!!.startsWith(StepTrail.LABEL_BE1000))
        for (other in listOf(com.optionslab.engine.orb.OovArm.SOURCE, com.optionslab.engine.orb.Solo2.SOURCE, "orb")) assertNull(stepTrailLine(other, true))
    }
}
