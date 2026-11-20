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
import com.optionslab.app.data.CheaperStrike
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 20 Nov 2026 (Boss's exception: "try cheaper options increment it 1 by 1"): Bot settings' cheaper strike card ([CheaperStrikeCardFlow]):
 * on by default with today's stepped buys and give-ups counted; off is one tap with no PIN (it lowers risk); on asks the PIN first and goes
 * on only once it is given; before the switch is read it offers none.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class CheaperStrikeCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    private fun show(view: CheaperStrike.View, off: () -> Unit, onConfirmed: () -> Unit) = compose.setContent {
        IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            CheaperStrikeCardFlow(view, null, onOff = off, onOnConfirmed = onConfirmed) { ok, cancel ->
                Text("PIN asked")
                androidx.compose.material3.TextButton(onClick = ok) { Text("PIN right") }
                androidx.compose.material3.TextButton(onClick = cancel) { Text("PIN cancel") }
            }
        } }
    }

    @Test fun onItCountsTodayAndOffIsOneTapWithNoPin() {
        var offs = 0; var ons = 0
        show(CheaperStrike.View(on = true, loaded = true, boughtToday = 2, gaveUpToday = 1), { offs++ }, { ons++ })
        compose.waitForIdle()
        assertTrue(shows(CheaperStrike.SHORT))
        assertTrue(shows("On: such a buy steps to a cheaper strike (1 lot)"))
        assertTrue(shows("Today: 2 bought at a cheaper strike, 1 gave up", sub = true))
        assertTrue(shows("Live at once, by your choice", sub = true))
        compose.onNodeWithText(CHEAPER_STRIKE_SWITCH).performClick()
        compose.waitForIdle()
        assertFalse("no PIN to switch it off", shows("PIN asked"))
        assertEquals(1, offs); assertEquals(0, ons)
    }

    @Test fun onAsksThePinFirst() {
        var offs = 0; var ons = 0
        show(CheaperStrike.View(on = false, loaded = true), { offs++ }, { ons++ })
        compose.waitForIdle()
        assertTrue(shows("Off: such buys go (or are skipped by the funds check) as before"))
        compose.onNodeWithText(CHEAPER_STRIKE_SWITCH).performClick()
        compose.waitForIdle()
        assertTrue(shows("PIN asked"))
        assertEquals("not on before the PIN", 0, ons)
        compose.onNodeWithText("PIN cancel").performClick()
        compose.waitForIdle()
        assertEquals("cancelled: still off", 0, ons)
        compose.onNodeWithText(CHEAPER_STRIKE_SWITCH).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("PIN right").performClick()
        compose.waitForIdle()
        assertEquals(1, ons); assertEquals(0, offs)
    }

    @Test fun beforeTheSwitchIsReadItOffersNone() {
        var taps = 0
        show(CheaperStrike.View(), { taps++ }, { taps++ })
        compose.waitForIdle()
        assertTrue(shows("Reading the switch..."))
        assertFalse(shows(CHEAPER_STRIKE_SWITCH))
        assertEquals(0, taps)
    }
}
