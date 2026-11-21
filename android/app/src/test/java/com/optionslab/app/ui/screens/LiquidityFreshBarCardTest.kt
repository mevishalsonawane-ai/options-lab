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
import com.optionslab.app.data.LiquidityFreshBar
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 20 Nov 2026 (audit: "Liquidity has no fresh-bar check"): Bot settings' fresh-bar check card ([LiquidityFreshBarCardFlow]): off (shadow)
 * by default with today's would-skips counted; on is one tap with no PIN (it lowers risk); off asks the PIN first and goes off only once it
 * is given; before the switch is read it offers none.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class LiquidityFreshBarCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    private fun show(view: LiquidityFreshBar.View, on: () -> Unit, offConfirmed: () -> Unit, listOn: () -> Unit = {}, listOff: () -> Unit = {}) = compose.setContent {
        IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            LiquidityFreshBarCardFlow(view, null, onOn = on, onOffConfirmed = offConfirmed, onListOnConfirmed = listOn, onListOff = listOff) { why, ok, cancel ->
                Text("PIN asked")
                Text(why)
                androidx.compose.material3.TextButton(onClick = ok) { Text("PIN right") }
                androidx.compose.material3.TextButton(onClick = cancel) { Text("PIN cancel") }
            }
        } }
    }

    @Test fun inShadowItCountsTheWouldSkipsAndOnIsOneTapWithNoPin() {
        var ons = 0; var offs = 0
        show(LiquidityFreshBar.View(on = false, loaded = true, wouldSkipToday = 2), { ons++ }, { offs++ })
        compose.waitForIdle()
        assertTrue(shows(LiquidityFreshBar.LABEL))
        assertTrue(shows("Off (shadow): such entries go as before; each is logged"))
        assertTrue(shows("Today: 2 it would have skipped", sub = true))
        compose.onNodeWithText(FRESH_BAR_SWITCH).performClick()
        compose.waitForIdle()
        assertFalse("no PIN to switch it on", shows("PIN asked"))
        assertEquals(1, ons); assertEquals(0, offs)
    }

    @Test fun offAsksThePinFirst() {
        var ons = 0; var offs = 0
        show(LiquidityFreshBar.View(on = true, loaded = true, skippedToday = 1), { ons++ }, { offs++ })
        compose.waitForIdle()
        assertTrue(shows("On: such a live entry is not made; the bar is decided again if fresh candles land in time"))
        assertTrue(shows("1 skipped", sub = true))
        compose.onNodeWithText(FRESH_BAR_SWITCH).performClick()
        compose.waitForIdle()
        assertTrue(shows("PIN asked"))
        assertEquals("not off before the PIN", 0, offs)
        compose.onNodeWithText("PIN cancel").performClick()
        compose.waitForIdle()
        assertEquals("cancelled: still on", 0, offs)
        compose.onNodeWithText(FRESH_BAR_SWITCH).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("PIN right").performClick()
        compose.waitForIdle()
        assertEquals(1, offs); assertEquals(0, ons)
    }

    /** 20 Nov 2026 (coordinator): the list wait is off by default; on adds live entries, so it asks the PIN first. */
    @Test fun theListWaitOnAsksThePinFirst() {
        var ons = 0; var offs = 0
        show(LiquidityFreshBar.View(loaded = true, listWouldWaitToday = 1), {}, {}, { ons++ }, { offs++ })
        compose.waitForIdle()
        assertTrue(shows("Off: such a signal is lost as before (no contract); each is logged"))
        assertTrue(shows("1 it would have waited for the list", sub = true))
        compose.onNodeWithText(LIST_WAIT_SWITCH).performClick()
        compose.waitForIdle()
        assertTrue(shows(LIST_WAIT_PIN))
        assertEquals("not on before the PIN", 0, ons)
        compose.onNodeWithText("PIN right").performClick()
        compose.waitForIdle()
        assertEquals(1, ons); assertEquals(0, offs)
    }

    @Test fun theListWaitOffIsOneTapWithNoPin() {
        var ons = 0; var offs = 0
        show(LiquidityFreshBar.View(loaded = true, listOn = true), {}, {}, { ons++ }, { offs++ })
        compose.waitForIdle()
        compose.onNodeWithText(LIST_WAIT_SWITCH).performClick()
        compose.waitForIdle()
        assertFalse("no PIN to switch it off", shows("PIN asked"))
        assertEquals(1, offs); assertEquals(0, ons)
    }

    @Test fun beforeTheSwitchIsReadItOffersNone() {
        var taps = 0
        show(LiquidityFreshBar.View(), { taps++ }, { taps++ })
        compose.waitForIdle()
        assertTrue(shows("Reading the switch..."))
        assertFalse(shows(FRESH_BAR_SWITCH))
        assertEquals(0, taps)
    }
}
