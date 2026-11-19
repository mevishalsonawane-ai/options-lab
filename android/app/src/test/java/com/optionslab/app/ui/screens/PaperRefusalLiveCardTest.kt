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
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 19 Nov 2026 (audit batch 2, coordinator's decision B2-B): Bot settings' "paper refusals and live" card ([PaperRefusalLiveCardFlow]):
 * off (shadow) by default with today's would-have-gone-live counted; on asks the PIN first (it adds live entries) and goes on only once
 * it is given; off is one tap.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class PaperRefusalLiveCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    private fun show(on: Boolean, today: Int, off: () -> Unit, onConfirmed: () -> Unit) = compose.setContent {
        IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            PaperRefusalLiveCardFlow(on, today, null, onOff = off, onOnConfirmed = onConfirmed) { ok, cancel ->
                Text("PIN asked")
                androidx.compose.material3.TextButton(onClick = ok) { Text("PIN right") }
                androidx.compose.material3.TextButton(onClick = cancel) { Text("PIN cancel") }
            }
        } }
    }

    @Test fun offInShadowCountsTodayAndOnAsksThePinFirst() {
        var offs = 0; var ons = 0
        show(on = false, today = 2, off = { offs++ }, onConfirmed = { ons++ })
        compose.waitForIdle()
        assertTrue(shows("Off (shadow): skipped live; each is logged"))
        assertTrue(shows("Today: 2 would have gone live", sub = true))
        compose.onNodeWithText(REFUSAL_LIVE_SWITCH).performClick()
        compose.waitForIdle()
        assertTrue("the PIN first", shows("PIN asked"))
        assertEquals("not on before the PIN", 0, ons)
        compose.onNodeWithText("PIN cancel").performClick(); compose.waitForIdle()
        assertEquals(0, ons)
        compose.onNodeWithText(REFUSAL_LIVE_SWITCH).performClick(); compose.waitForIdle()
        compose.onNodeWithText("PIN right").performClick(); compose.waitForIdle()
        assertEquals(1, ons); assertEquals(0, offs)
    }

    @Test fun onToOffIsOneTapWithNoPin() {
        var offs = 0; var ons = 0
        show(on = true, today = 0, off = { offs++ }, onConfirmed = { ons++ })
        compose.waitForIdle()
        assertTrue(shows("On: such entries go live (every live gate applies)"))
        compose.onNodeWithText(REFUSAL_LIVE_SWITCH).performClick(); compose.waitForIdle()
        assertTrue(!shows("PIN asked"))
        assertEquals(1, offs); assertEquals(0, ons)
    }
}
