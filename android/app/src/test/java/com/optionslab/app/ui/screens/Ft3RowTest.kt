package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.Ft3Arm
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.Ft3Rules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * 21 Oct (Boss's choice): "FT3 near-ATM morning" on Home's Strategies card - labelled as not passing research (R98e), its rules,
 * signal source, replayed record, how it is judged and how it goes live (the standard way), today's ticket with the running P&L,
 * and its switch, one tap off.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class Ft3RowTest {
    // 22 Oct (merge with enhancement round 4): the market clock at noon IST on a trading day unless the test sets its own.
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val t0 = LocalDateTime.of(2026, 10, 27, 11, 21, 20)
    private val off = Ft3Arm.View(false, emptyMap(), emptyList(), emptyList(), emptyList(), emptyList())

    private fun home(v: Ft3Arm.View, onArm: (Boolean) -> Unit = {}) = compose.setContent {
        IraAlgoTheme("light") {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                StrategyArmContent(false, false, emptyList(), emptyMap(), emptyMap(), false, RecordingStrategyActions(), {},
                    orbRows = { Text("ORB rows") }, reauth = StrategyFakes.reauth,
                    nightRow = { Text("Night row"); Ft3RowContent(v, onArm) })
            }
        }
    }

    @Test fun itIsOnHomeStrategiesLabelledBossChoiceThatDidNotPassResearch() {
        val asked = ArrayList<Boolean>()
        home(off.copy(armed = true, status = mapOf("NIFTY" to "looking: nothing met the rule up to 11:00")), onArm = { asked += it })
        compose.waitForIdle()
        compose.onNodeWithText(Ft3Rules.LABEL).assertExists()
        // COMPACT: the row carries no explanation; the name's dialog has the label first, then the rules, the signal source,
        // the replayed record, how it is judged and its live path.
        compose.onNodeWithText(keepNumbersWhole(Ft3Rules.RULES + ".")).assertDoesNotExist()
        compose.onNodeWithContentDescription("About ${Ft3Rules.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        fun top(s: String) = compose.onNodeWithText(keepNumbersWhole(s)).fetchSemanticsNode().boundsInRoot.top
        val label = top(Ft3Rules.RECORD + ".")
        val rules = top(Ft3Rules.RULES + ".")
        val measured = top(Ft3Rules.MEASURED + ".")
        assertTrue(label < rules && rules < measured)
        compose.onNodeWithText(keepNumbersWhole(Ft3Rules.SIGNAL_SOURCE + ".")).assertExists()
        compose.onNodeWithText(keepNumbersWhole(Ft3Rules.JUDGED + ".")).assertExists()
        compose.onNodeWithText(keepNumbersWhole(FT3_LIVE_WORDS)).assertExists()
        compose.onNodeWithText(keepNumbersWhole("NIFTY: looking: nothing met the rule up to 11:00")).assertExists()
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole(Ft3Rules.RULES + ".")).assertDoesNotExist()
        // Under the NSE arms (after Night).
        assertTrue(compose.onNodeWithText("Night row").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText(Ft3Rules.LABEL).fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText("ON · PAPER · LIVE THE STANDARD WAY").assertExists()
        compose.onNodeWithText(keepNumbersWhole("Today: no ticket · forward test: 0 closed, 0 won, +₹0 after charges")).assertExists()
        // One tap switches it off.
        compose.onNodeWithContentDescription("On paper: switch ${Ft3Rules.LABEL}").assertIsOn().performClick()
        compose.waitForIdle()
        assertEquals(listOf(false), asked)
    }

    @Test fun aHeldTicketShowsItsBadgeAndTheRunningPnl() {
        val held = Ft3Arm.Pos("NIFTY", "NIFTY27OCT2625100CE", "CE", 585, 8.05, t0, "p1", "11:20", 25_100.0, 8.0, 1.0, 0.92)
        val sold = held.copy(symbol = "S", exit = 4.0, exitTime = t0.withHour(15).withMinute(5), why = Ft3Rules.EXIT_WHY, charges = 60.0)
        val v = off.copy(armed = true, open = listOf(held), today = listOf(held, sold), nets = listOf(1_000.0, -2_429.25),
            marks = mapOf(held.symbol to 9.05))
        home(v)
        compose.waitForIdle()
        compose.onNodeWithText("HOLDING 1 · PAPER").assertExists()
        // Held at +Rs 585 before charges, the sold one -Rs 2,429 after them.
        compose.onNodeWithText(keepNumbersWhole("Today: 2 tickets, −₹1,844 · forward test: 2 closed, 1 won, −₹1,429 after charges")).assertExists()
        compose.onNodeWithContentDescription("About ${Ft3Rules.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("NIFTY27OCT2625100CE · 585 @ 8.05 · now 9.05 · 11:20 mark · paper")).assertExists()
        compose.onNodeWithText(keepNumbersWhole("S · 585 @ 8.05 → 4.00 (ft3_1505) · −₹2,429")).assertExists()
    }

    @Test fun offSaysOffAndOneTapSwitchesItOn() {
        val asked = ArrayList<Boolean>()
        home(off, onArm = { asked += it })
        compose.waitForIdle()
        compose.onNodeWithText("OFF · SHADOW").assertExists()
        compose.onNodeWithContentDescription("On paper: switch ${Ft3Rules.LABEL}").assertIsOff().performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), asked)
        assertTrue(ft3Explanation(off).first().startsWith("Boss's choice - did not pass research"))
    }
}
