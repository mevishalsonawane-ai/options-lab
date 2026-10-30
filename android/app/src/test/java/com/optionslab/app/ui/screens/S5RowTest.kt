package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.S5Arm
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.S5Trail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 28 Oct (Boss's ask): S5 Trail on Home's Strategies card - labelled "failed research gates" on the row and first in its name's
 * dialog, its rules, signal source and record there, today's decisions without rupee figures, and its switch, one tap off.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class S5RowTest {
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val d = LocalDate.of(2026, 10, 29)
    private val looks = listOf(
        S5Arm.Look(d, "NIFTY", 4, LocalDateTime.of(2026, 10, 29, 9, 20, 3), 512.0, 15, "taken: NIFTY26NOV25000PE 65 @ 110.4 · paper", true),
        S5Arm.Look(d, "SENSEX", 4, LocalDateTime.of(2026, 10, 29, 9, 20, 3), 120.0, null, "best expected net +₹120 is under ₹300", false))

    private fun row(v: S5Arm.View, onArm: (Boolean) -> Unit = {}) = compose.setContent {
        IraAlgoTheme("light") { Column { S5RowContent(v, onArm) } }
    }

    @Test fun itIsLabelledFailedGatesAndOneTapSwitchesItOff() {
        val asked = ArrayList<Boolean>()
        row(S5Arm.View(armed = true, looks = looks), onArm = { asked += it })
        compose.waitForIdle()
        compose.onNodeWithText(S5Trail.LABEL).assertExists()
        compose.onNodeWithText("ON · PAPER + ITS LIVE SWITCH · FAILED GATES, BOSS'S EXCEPTION").assertExists()
        compose.onNodeWithText("Today: 0 taken, 1 not · 0 closed so far, 0 won").assertExists()
        compose.onNodeWithContentDescription("About ${S5Trail.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        fun top(s: String) = compose.onNodeWithText(keepNumbersWhole(s)).fetchSemanticsNode().boundsInRoot.top
        assertTrue("the label first", top(S5Trail.NOT_PROVEN + ".") < top(S5Trail.RULES + "."))
        compose.onNodeWithText(keepNumbersWhole(S5Trail.SIGNAL + ".")).assertExists()
        compose.onNodeWithText(keepNumbersWhole(S5Trail.RECORD + ".")).assertExists()
        compose.onNodeWithText(keepNumbersWhole("SENSEX 09:20: best expected net ₹… is under ₹…")).assertExists()
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("On paper: switch ${S5Trail.LABEL}").assertIsOn().performClick()
        compose.waitForIdle()
        assertEquals(listOf(false), asked)
    }

    @Test fun offAndHoldingAreSaid() {
        row(S5Arm.View(armed = false))
        compose.waitForIdle()
        compose.onNodeWithText("OFF · SHADOW · FAILED GATES, BOSS'S EXCEPTION").assertExists()
    }

    @Test fun reasonsLoseTheirRupeeFigures() {
        assertEquals("picked; not taken: a NIFTY trade is still open", noRupees("picked (expected net +₹500); not taken: a NIFTY trade is still open"))
        assertEquals("best expected net ₹… is under ₹…", noRupees("best expected net +₹1,120 is under ₹300"))
        assertFalse(noRupees("best expected net −₹40 is under ₹300").contains(Regex("\\d")))
        assertEquals("no NIFTY minute bar for 09:24 yet", noRupees("no NIFTY minute bar for 09:24 yet"))
        val lines = s5Explanation(S5Arm.View(armed = true, looks = looks, model = "IOException"))
        assertEquals(S5Trail.NOT_PROVEN + ".", lines.first())
        assertTrue(lines.any { it.contains("could not be read") })
        assertTrue(lines.filter { it.startsWith("NIFTY") || it.startsWith("SENSEX") }.none { it.contains(Regex("₹\\d")) })
    }
}
