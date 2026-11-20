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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 20 Nov 2026 (audit B5, item 1): Bot settings' "R51 / R72 stale history" card ([StaleHistoryCardFlow]): off (shadow) by default with
 * today's would-have-skipped counted; on and off each ask the PIN first (on lets a better history replace the one in use; off adds live
 * entries back) and change only once it is given; while the book is being read, no switch is shown.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class StaleHistoryCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    private fun show(on: Boolean, today: Int, onOn: () -> Unit, offConfirmed: () -> Unit, loaded: Boolean = true) = compose.setContent {
        IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            StaleHistoryCardFlow(on, today, null, onOnConfirmed = onOn, onOffConfirmed = offConfirmed, loaded = loaded) { want, ok, cancel ->
                Text(if (want) "PIN asked to switch on" else "PIN asked to switch off")
                Text("PIN asked")
                androidx.compose.material3.TextButton(onClick = ok) { Text("PIN right") }
                androidx.compose.material3.TextButton(onClick = cancel) { Text("PIN cancel") }
            }
        } }
    }

    @Test fun offInShadowCountsTodayAndOnAsksThePinFirst() {
        var ons = 0; var offs = 0
        show(on = false, today = 3, onOn = { ons++ }, offConfirmed = { offs++ })
        compose.waitForIdle()
        assertTrue(shows("Off (shadow): taken as before; each is logged"))
        assertTrue(shows("Today: 3 would have skipped", sub = true))
        compose.onNodeWithText(STALE_HISTORY_SWITCH).performClick(); compose.waitForIdle()
        assertTrue("on can change live breaks: the PIN first", shows("PIN asked to switch on"))
        assertEquals("not on before the PIN", 0, ons)
        compose.onNodeWithText("PIN cancel").performClick(); compose.waitForIdle()
        assertEquals(0, ons)
        compose.onNodeWithText(STALE_HISTORY_SWITCH).performClick(); compose.waitForIdle()
        compose.onNodeWithText("PIN right").performClick(); compose.waitForIdle()
        assertEquals(1, ons); assertEquals(0, offs)
    }

    @Test fun onToOffAsksThePinFirst() {
        var ons = 0; var offs = 0
        show(on = true, today = 0, onOn = { ons++ }, offConfirmed = { offs++ })
        compose.waitForIdle()
        assertTrue(shows("On: such a break waits while the history is read again, else it is let go (paper and live)"))
        compose.onNodeWithText(STALE_HISTORY_SWITCH).performClick(); compose.waitForIdle()
        assertTrue("the PIN first", shows("PIN asked to switch off"))
        assertEquals("not off before the PIN", 0, offs)
        compose.onNodeWithText("PIN cancel").performClick(); compose.waitForIdle()
        assertEquals(0, offs)
        compose.onNodeWithText(STALE_HISTORY_SWITCH).performClick(); compose.waitForIdle()
        compose.onNodeWithText("PIN right").performClick(); compose.waitForIdle()
        assertEquals(1, offs); assertEquals(0, ons)
    }

    @Test fun noSwitchWhileTheBookIsRead() {
        show(on = false, today = 0, onOn = {}, offConfirmed = {}, loaded = false)
        compose.waitForIdle()
        assertTrue(shows("Reading the switch..."))
        assertFalse(shows(STALE_HISTORY_SWITCH))
    }
}
