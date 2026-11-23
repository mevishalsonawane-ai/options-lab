package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.ManualCover
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Boss, 23 Nov 2026 ("a button, where i could stop/start the functionality", as a Home screen card): Home's "Stop & target on my Kite
 * buys" ([ManualCoverHomeCardFlow]): the switch (off one tap, on with the PIN), the stop and target points (in Live a wider stop takes
 * the PIN; a narrower one and the target are one tap), what it covers, and the last position it protected.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class ManualCoverHomeCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val marketClock = com.optionslab.app.testing.MarketClockRule()

    private val toggle = "Protect my own buys"

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    private val offs = ArrayList<Unit>()
    private val ons = ArrayList<Unit>()
    private val saves = ArrayList<Triple<Double, Double, Boolean>>()
    private var said by mutableStateOf<String?>(null)

    /** The card with a stand-in PIN step: "PIN asked: <why>" and its two buttons. */
    private fun show(view: ManualCover.View, live: Boolean = true) = compose.setContent {
        IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            ManualCoverHomeCardFlow(view, live, said, onOff = { offs += Unit }, onOnConfirmed = { ons += Unit },
                onSave = { s, t, pin -> saves += Triple(s, t, pin) }, onSaid = { said = it }) { why, ok, cancel ->
                Text("PIN asked: $why")
                androidx.compose.material3.TextButton(onClick = ok) { Text("PIN right") }
                androidx.compose.material3.TextButton(onClick = cancel) { Text("PIN cancel") }
            }
        } }
    }

    private val loaded = ManualCover.View(on = true, loaded = true, last = "Protected: NIFTY26N0925150CE (130), stop 115.35 · target 135.35 (watched by the app) (10:32:15)")

    private fun type(tag: String, text: String) { compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(text); compose.waitForIdle() }
    private fun save() { compose.onNodeWithTag("manualcover-home-save").performScrollTo().performClick(); compose.waitForIdle() }

    @Test fun itSaysWhatItDoesItsScopeAndTheLastPositionAndOffIsOneTap() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        show(loaded)
        compose.waitForIdle()
        assertTrue(shows("Stop & target on my Kite buys"))
        assertTrue(shows("gets a stop 10 points under your average price and a target sell 10 points over it", sub = true))
        assertTrue(shows("as soon as Zerodha reports your Kite buy filled", sub = true))
        assertTrue(shows("Only NSE and BSE options you bought: never shorts, futures, stock or MCX."))
        assertTrue(shows("the target is watched by the app", sub = true))
        assertTrue(shows("the app takes its own out (never two exits)", sub = true))
        assertTrue(shows("Protected: NIFTY26N0925150CE (130), stop 115.35", sub = true))
        assertTrue(shows("On: placed automatically in market hours (09:15-15:24)"))
        compose.onNodeWithText(toggle).performClick()
        compose.waitForIdle()
        assertEquals(1, offs.size)
        assertFalse("no PIN to switch off", shows("PIN asked", sub = true))
        assertTrue(ons.isEmpty())
    }

    @Test fun switchingOnAsksThePinFirst() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        show(ManualCover.View(on = false, loaded = true))
        compose.waitForIdle()
        assertTrue(shows("Off: positions you buy yourself are left as they are"))
        assertTrue(shows("Nothing protected or left alone yet."))
        compose.onNodeWithText(toggle).performClick()
        compose.waitForIdle()
        assertTrue(shows("PIN asked: $MANUAL_COVER_PIN_ON"))
        assertTrue("not on before the PIN", ons.isEmpty())
        compose.onNodeWithText("PIN cancel").performClick()
        compose.waitForIdle()
        assertTrue("cancelled: still off", ons.isEmpty())
        compose.onNodeWithText(toggle).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("PIN right").performClick()
        compose.waitForIdle()
        assertEquals(1, ons.size)
        assertTrue(offs.isEmpty())
    }

    /** Before the saved switch is read the card never says "On" and offers no switch (as Bot settings' card). */
    @Test fun beforeTheSwitchIsReadItSaysSo() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        show(ManualCover.View())
        compose.waitForIdle()
        assertTrue(shows("Reading the switch..."))
        assertFalse(shows(toggle))
    }

    @Test fun inLiveANarrowerStopAndAnyTargetAreOneTapAndAWiderStopTakesThePin() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        show(loaded)
        compose.waitForIdle()
        // Narrower stop, wider target: saved at once, no PIN.
        type("manualcover-home-stop", "5")
        type("manualcover-home-target", "25")
        save()
        assertFalse(shows("PIN asked", sub = true))
        assertEquals(listOf(Triple(5.0, 25.0, false)), saves)
        // A wider stop: the PIN first, with the reason; cancelled, nothing saved.
        type("manualcover-home-stop", "15")
        save()
        assertTrue(shows("PIN asked: ${manualCoverPinWiden(10.0, 15.0)}"))
        compose.onNodeWithText("PIN cancel").performClick()
        compose.waitForIdle()
        assertEquals(1, saves.size)
        save()
        compose.onNodeWithText("PIN right").performClick()
        compose.waitForIdle()
        assertEquals(Triple(15.0, 25.0, true), saves.last())
        // Not a number: said, nothing saved.
        type("manualcover-home-stop", ".")
        save()
        assertEquals(2, saves.size)
        assertTrue(shows("Points: a number from 1 to 500."))
    }

    @Test fun inPaperAWiderStopIsOneTap() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        show(loaded, live = false)
        compose.waitForIdle()
        type("manualcover-home-stop", "40")
        save()
        assertFalse(shows("PIN asked", sub = true))
        assertEquals(listOf(Triple(40.0, 10.0, false)), saves)
    }
}
