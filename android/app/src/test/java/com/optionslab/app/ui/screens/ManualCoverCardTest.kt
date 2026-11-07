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
import com.optionslab.app.data.ManualCover
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 7 Nov: Bot settings' "Stop and target on my manual positions" card ([ManualCoverCardFlow]): on by default and saying what it
 * does; off is one tap with no PIN; switching it on asks for the PIN first and switches on only once it is given.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class ManualCoverCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val marketClock = com.optionslab.app.testing.MarketClockRule()

    private val toggle = "Stop and target on my manual positions"

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    /** The card with a stand-in PIN step: "PIN asked" and its two buttons. */
    private fun show(view: ManualCover.View, off: () -> Unit, onConfirmed: () -> Unit) = compose.setContent {
        IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            ManualCoverCardFlow(view, null, onOff = off, onOnConfirmed = onConfirmed) { ok, cancel ->
                Text("PIN asked")
                androidx.compose.material3.TextButton(onClick = ok) { Text("PIN right") }
                androidx.compose.material3.TextButton(onClick = cancel) { Text("PIN cancel") }
            }
        } }
    }

    @Test fun onItSaysWhatItDoesAndOffIsOneTapWithNoPin() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        var offs = 0; var ons = 0
        show(ManualCover.View(on = true, line = "Stop and target on my manual positions: on; today protected 1, left alone 0, failed 0"),
            { offs++ }, { ons++ })
        compose.waitForIdle()
        assertTrue(shows("On: placed automatically in market hours (09:15-15:24)"))
        assertTrue(shows("gets a stop 20% under its average price and a target sell 40% over it", sub = true))
        assertTrue(shows("today protected 1", sub = true))
        compose.onNodeWithText(toggle).performClick()
        compose.waitForIdle()
        assertEquals(1, offs)
        assertFalse("no PIN to switch off", shows("PIN asked"))
        assertEquals(0, ons)
    }

    @Test fun switchingOnAsksThePinFirst() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        var offs = 0; var ons = 0
        show(ManualCover.View(on = false), { offs++ }, { ons++ })
        compose.waitForIdle()
        assertTrue(shows("Off: positions you buy yourself are left as they are"))
        assertTrue(shows("Switching it back on takes your PIN", sub = true))
        compose.onNodeWithText(toggle).performClick()
        compose.waitForIdle()
        assertTrue(shows("PIN asked"))
        assertEquals("not on before the PIN", 0, ons)
        compose.onNodeWithText("PIN cancel").performClick()
        compose.waitForIdle()
        assertFalse(shows("PIN asked"))
        assertEquals("cancelled: still off", 0, ons)
        compose.onNodeWithText(toggle).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("PIN right").performClick()
        compose.waitForIdle()
        assertEquals(1, ons)
        assertFalse(shows("PIN asked"))
        assertEquals(0, offs)
    }
}
