package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.CisdArm
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.CisdNet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 29 Oct (Boss's ask): CISD + network on Home's Strategies card - named "CISD + network (failed gates, Boss's exception)", labelled
 * on its status line and first in its name's dialog, its rules, signal source and record there, today's setups without rupee
 * figures, and its switch, one tap off.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class CisdRowTest {
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val d = LocalDate.of(2026, 10, 29)
    private val looks = listOf(
        CisdArm.Look(d, "NIFTY", 70, LocalDateTime.of(2026, 10, 29, 10, 25, 3), false, 45.0, "taken: NIFTY26NOV25000PE 65 @ 110.4 · paper", true),
        CisdArm.Look(d, "SENSEX", 70, LocalDateTime.of(2026, 10, 29, 10, 25, 3), true, -12.0,
            "refused by the network: its EV for this side is -12, not above 0 (₹-12)", false))

    private fun row(v: CisdArm.View, onArm: (Boolean) -> Unit = {}) = compose.setContent {
        IraAlgoTheme("light") { Column { CisdRowContent(v, onArm) } }
    }

    @Test fun itIsLabelledAndOneTapSwitchesItOff() {
        val asked = ArrayList<Boolean>()
        row(CisdArm.View(armed = true, looks = looks), onArm = { asked += it })
        compose.waitForIdle()
        compose.onNodeWithText(CisdNet.SHOWN).assertExists()
        assertEquals("CISD + network (failed gates, Boss's exception)", CisdNet.SHOWN)
        compose.onNodeWithText("ON · PAPER · FAILED GATES, BOSS'S EXCEPTION").assertExists()
        compose.onNodeWithText("Today: 0 taken, 1 setups not · 0 closed so far, 0 won").assertExists()
        compose.onNodeWithContentDescription("About ${CisdNet.SHOWN}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        fun top(s: String) = compose.onNodeWithText(keepNumbersWhole(s)).fetchSemanticsNode().boundsInRoot.top
        assertTrue("the label first", top(CisdNet.NOT_PROVEN + ".") < top(CisdNet.RULES + "."))
        compose.onNodeWithText(keepNumbersWhole(CisdNet.SIGNAL + ".")).assertExists()
        compose.onNodeWithText(keepNumbersWhole(CisdNet.RECORD + ".")).assertExists()
        compose.onNodeWithText(keepNumbersWhole("NIFTY 10:25 put: taken")).assertExists()
        compose.onNodeWithText(keepNumbersWhole("SENSEX 10:25 call: refused by the network: its EV for this side is -12, not above 0")).assertExists()
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("On paper: switch ${CisdNet.SHOWN}").assertIsOn().performClick()
        compose.waitForIdle()
        assertEquals(listOf(false), asked)
    }

    @Test fun offAndHoldingAreSaid() {
        val held = CisdArm.Pos("NIFTY", "NIFTY26NOV25000PE", "PE", 1, 65, 110.4, LocalDateTime.of(2026, 10, 29, 10, 25, 3), "P1", 25_110.0, 24_950.0,
            25_010.0, 70, 45.0)
        row(CisdArm.View(armed = false, open = listOf(held), today = listOf(held)))
        compose.waitForIdle()
        compose.onNodeWithText("OFF · FAILED GATES, BOSS'S EXCEPTION").assertExists()
        compose.onNodeWithText("HOLDING 1 · PAPER").assertExists()
        compose.onNodeWithContentDescription("On paper: switch ${CisdNet.SHOWN}").assertIsOff()
    }

    @Test fun theDialogSaysTheNetworkCouldNotBeRead() {
        val lines = cisdExplanation(CisdArm.View(armed = true, looks = looks, model = "IOException"))
        assertEquals(CisdNet.NOT_PROVEN + ".", lines.first())
        assertTrue(lines.any { it.contains("could not be read") })
        assertTrue("the setups carry no rupee figure", lines.filter { it.startsWith("NIFTY") || it.startsWith("SENSEX") }.none { it.contains(Regex("₹-?\\d")) })
    }
}
