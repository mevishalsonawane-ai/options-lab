package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.HeroTicketArm
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.HeroTicketRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * 15 Oct (Boss's choice): "Hero ₹1,000 ticket" on Home's Strategies card - Boss's label that it did not pass research (R50),
 * its own measured record, its rules and signal source, today's tickets with the running P&L, the exit (trail only / 2× / 3×)
 * and its paper-only switch, one tap off.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class HeroTicketRowTest {
    // 21 Oct, enhancement round 4: the market clock at noon IST on a trading day unless the test sets its own.
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val t0 = LocalDateTime.of(2026, 10, 20, 12, 30, 20)
    private val off = HeroTicketArm.View(false, HeroTicketRules.Target.TRAIL_ONLY, emptyMap(), emptyList(), emptyList(), emptyList(), emptyList())

    private fun home(v: HeroTicketArm.View, onArm: (Boolean) -> Unit = {}, onTarget: (HeroTicketRules.Target) -> Unit = {}) = compose.setContent {
        IraAlgoTheme("light") {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                StrategyArmContent(false, false, emptyList(), emptyMap(), emptyMap(), false, RecordingStrategyActions(), {},
                    orbRows = { Text("ORB rows") }, reauth = StrategyFakes.reauth,
                    nightRow = { Text("Night row"); HeroTicketRowContent(v, onArm, onTarget) })
            }
        }
    }

    @Test fun itIsOnHomeStrategiesLabelledBossChoiceThatDidNotPassResearch() {
        val asked = ArrayList<Boolean>()
        home(off.copy(armed = true), onArm = { asked += it })
        compose.waitForIdle()
        compose.onNodeWithText(HeroTicketRules.LABEL).assertExists()
        // COMPACT (15 Oct): the row carries no explanation; the name's dialog has the label Boss asked for, word for word, above
        // its record and rules, the signal source and its own record's note - and OK closes it.
        compose.onNodeWithText(keepNumbersWhole(HeroTicketRules.RULES)).assertDoesNotExist()
        compose.onNodeWithContentDescription("About ${HeroTicketRules.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        val label = compose.onNodeWithText(keepNumbersWhole(HeroTicketRules.RECORD + ".")).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Boss's choice - did not pass research (R50): about -Rs 525 per Rs 1,000 on average; jackpots are rare" == HeroTicketRules.RECORD)
        val measured = compose.onNodeWithText(keepNumbersWhole(HeroTicketRules.MEASURED)).fetchSemanticsNode().boundsInRoot.top
        val rules = compose.onNodeWithText(keepNumbersWhole(HeroTicketRules.RULES)).fetchSemanticsNode().boundsInRoot.top
        assertTrue(label < measured && measured < rules)
        compose.onNodeWithText(keepNumbersWhole(HeroTicketRules.SIGNAL_SOURCE)).assertExists()
        compose.onNodeWithText(keepNumbersWhole(HeroTicketRules.SEPARATE + ".")).assertExists()
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole(HeroTicketRules.RULES)).assertDoesNotExist()
        // Under the NSE arms (after Night).
        assertTrue(compose.onNodeWithText("Night row").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText(HeroTicketRules.LABEL).fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText("ON · PAPER + ITS LIVE SWITCH").assertExists()
        compose.onNodeWithText(keepNumbersWhole("Today: no ticket · its own record: 0 closed, +₹0 after charges")).assertExists()
        // One tap switches it off (paper only).
        compose.onNodeWithContentDescription("Paper only: switch ${HeroTicketRules.LABEL}").assertIsOn().performClick()
        compose.waitForIdle()
        assertEquals(listOf(false), asked)
    }

    @Test fun offItSaysOffAndATapAsksForOn() {
        val asked = ArrayList<Boolean>()
        home(off, onArm = { asked += it })
        compose.waitForIdle()
        compose.onNodeWithText("OFF · SHADOW").assertExists()
        compose.onNodeWithContentDescription("Paper only: switch ${HeroTicketRules.LABEL}").assertIsOff().performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), asked)
    }

    @Test fun todaysTicketsAndTheRunningPnl() {
        val held = HeroTicketArm.Pos("NIFTY", "NIFTY20OCT2625150CE", "CE", 65, 12.0, t0, "p1")
        val sold = HeroTicketArm.Pos("NIFTY", "NIFTY20OCT2624900PE", "PE", 65, 11.0, t0, "p2", exit = 8.0, exitTime = t0.plusMinutes(12),
            why = "ticket_trail", charges = 40.0)
        val v = off.copy(armed = true, open = listOf(held), closed = listOf(sold), today = listOf(held, sold), nets = listOf(sold.net!!),
            marks = mapOf(held.symbol to 20.0), status = mapOf("NIFTY" to "PE bought", "SENSEX" to "not its expiry day"))
        // Held at 20 (+Rs 520 before charges) and sold at 8 (-Rs 235 after charges): +Rs 285 today.
        assertEquals(285.0, v.todayPnl, 1e-9)
        home(v)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("Today: 2 tickets, +₹285 · its own record: 1 closed, −₹235 after charges")).assertExists()
        // The tickets and each index's last decision are in the name's dialog (ONE status line on the row).
        compose.onNodeWithText(keepNumbersWhole("NIFTY20OCT2625150CE · 65 @ 12.00 · now 20.00 · paper")).assertDoesNotExist()
        compose.onNodeWithContentDescription("About ${HeroTicketRules.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("NIFTY20OCT2625150CE · 65 @ 12.00 · now 20.00 · paper")).assertExists()
        compose.onNodeWithText(keepNumbersWhole("NIFTY20OCT2624900PE · 65 @ 11.00 → 8.00 (ticket_trail) · −₹235")).assertExists()
        compose.onNodeWithText(keepNumbersWhole("SENSEX: not its expiry day")).assertExists()
    }

    @Test fun theExitIsTrailOnlyByDefaultAndATapPicks2xOr3x() {
        val picked = ArrayList<HeroTicketRules.Target>()
        home(off.copy(armed = true), onTarget = { picked += it })
        compose.waitForIdle()
        // The exit choice is a control in the name's dialog (not on the row).
        compose.onNodeWithContentDescription("${HeroTicketRules.LABEL} exit: trail only").assertDoesNotExist()
        compose.onNodeWithContentDescription("About ${HeroTicketRules.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("${HeroTicketRules.LABEL} exit: trail only").assertIsSelected()
        compose.onNodeWithContentDescription("${HeroTicketRules.LABEL} exit: 2× target").assertIsNotSelected().performClick()
        compose.onNodeWithContentDescription("${HeroTicketRules.LABEL} exit: 3× target").performClick()
        // Tapping the one already chosen asks nothing.
        compose.onNodeWithContentDescription("${HeroTicketRules.LABEL} exit: trail only").performClick()
        compose.waitForIdle()
        assertEquals(listOf(HeroTicketRules.Target.X2, HeroTicketRules.Target.X3), picked)
    }

    @Test fun theTrailStartsAfter2xByDefaultPendingBossAndATapPicksAnother() {
        val picked = ArrayList<HeroTicketRules.TrailStart>()
        compose.setContent {
            IraAlgoTheme("light") {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HeroTicketRowContent(off.copy(armed = true), onArm = {}, onTarget = {}, onStart = { picked += it })
                }
            }
        }
        compose.waitForIdle()
        // The trail-start choice and its note are in the name's dialog (compact row).
        compose.onNodeWithText(keepNumbersWhole(HeroTicketRules.TRAIL_START_NOTE + ".")).assertDoesNotExist()
        compose.onNodeWithContentDescription("About ${HeroTicketRules.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        // 25 Oct: the default is the trail from the fill (R55's recommended cell).
        compose.onNodeWithContentDescription("${HeroTicketRules.LABEL} trail start: trail from the fill (R50)").assertIsSelected()
        compose.onNodeWithText(keepNumbersWhole(HeroTicketRules.TRAIL_START_NOTE + ".")).assertExists()
        compose.onNodeWithContentDescription("${HeroTicketRules.LABEL} trail start: trail after 2×").assertIsNotSelected().performClick()
        compose.onNodeWithContentDescription("${HeroTicketRules.LABEL} trail start: trail after 3×").performClick()
        compose.onNodeWithContentDescription("${HeroTicketRules.LABEL} trail start: trail from the fill (R50)").performClick()
        compose.waitForIdle()
        assertEquals(listOf(HeroTicketRules.TrailStart.AFTER_2X, HeroTicketRules.TrailStart.AFTER_3X), picked)
    }

    @Test fun aChosenTargetShowsAsSelected() {
        home(off.copy(armed = true, target = HeroTicketRules.Target.X3))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("About ${HeroTicketRules.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("${HeroTicketRules.LABEL} exit: 3× target").assertIsSelected()
        compose.onNodeWithContentDescription("${HeroTicketRules.LABEL} exit: trail only").assertIsNotSelected()
    }
}
