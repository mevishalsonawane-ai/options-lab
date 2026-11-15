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
import com.optionslab.app.data.FundsCheck
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 15 Nov 2026 (Boss: "Yes" to the funds check in shadow): Bot settings' funds check card ([FundsCheckCardFlow]): off (shadow) by
 * default with today's would-skips counted; on is one tap with no PIN (it lowers risk); off asks the PIN first and goes off only once
 * it is given; before the switch is read it offers none.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class FundsCheckCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    private fun show(view: FundsCheck.View, on: () -> Unit, offConfirmed: () -> Unit) = compose.setContent {
        IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            FundsCheckCardFlow(view, null, onOn = on, onOffConfirmed = offConfirmed) { ok, cancel ->
                Text("PIN asked")
                androidx.compose.material3.TextButton(onClick = ok) { Text("PIN right") }
                androidx.compose.material3.TextButton(onClick = cancel) { Text("PIN cancel") }
            }
        } }
    }

    @Test fun inShadowItCountsTheWouldSkipsAndOnIsOneTapWithNoPin() {
        var ons = 0; var offs = 0
        show(FundsCheck.View(on = false, loaded = true, wouldSkipToday = 3), { ons++ }, { offs++ })
        compose.waitForIdle()
        assertTrue(shows(FundsCheck.LABEL))
        assertTrue(shows("Off (shadow): such entries go as before; each is logged"))
        assertTrue(shows("Today: 3 it would have skipped", sub = true))
        compose.onNodeWithText(FUNDS_CHECK_SWITCH).performClick()
        compose.waitForIdle()
        assertFalse("no PIN to switch it on", shows("PIN asked"))
        assertEquals(1, ons); assertEquals(0, offs)
    }

    @Test fun offAsksThePinFirst() {
        var ons = 0; var offs = 0
        show(FundsCheck.View(on = true, loaded = true, skippedToday = 2), { ons++ }, { offs++ })
        compose.waitForIdle()
        assertTrue(shows("On: such an entry is not sent; it is kept as a would-be entry"))
        assertTrue(shows("2 not sent", sub = true))
        compose.onNodeWithText(FUNDS_CHECK_SWITCH).performClick()
        compose.waitForIdle()
        assertTrue(shows("PIN asked"))
        assertEquals("not off before the PIN", 0, offs)
        compose.onNodeWithText("PIN cancel").performClick()
        compose.waitForIdle()
        assertEquals("cancelled: still on", 0, offs)
        compose.onNodeWithText(FUNDS_CHECK_SWITCH).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("PIN right").performClick()
        compose.waitForIdle()
        assertEquals(1, offs); assertEquals(0, ons)
    }

    @Test fun beforeTheSwitchIsReadItOffersNone() {
        var taps = 0
        show(FundsCheck.View(), { taps++ }, { taps++ })
        compose.waitForIdle()
        assertTrue(shows("Reading the switch..."))
        assertFalse(shows(FUNDS_CHECK_SWITCH))
        assertEquals(0, taps)
    }
}
