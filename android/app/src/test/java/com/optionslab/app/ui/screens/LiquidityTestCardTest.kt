package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.OrbArms
import com.optionslab.app.ui.components.expandTag
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.LiquidityFreeze
import com.optionslab.ira.LiquidityGoLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Liquidity BANKNIFTY's paper test under its row and its freeze on the Strategy lab card (12 Oct). Plain state; fixed dates. */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class LiquidityTestCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    private val lines = listOf(LiquidityGoLive.Line(LiquidityGoLive.Mark.OK, "At least 6 trades since 13 Oct: 7"),
        LiquidityGoLive.Line(LiquidityGoLive.Mark.NO, "Entry within 10 s of the bar close: median 12.0 s (worst 15.0 s)"),
        LiquidityGoLive.Line(LiquidityGoLive.Mark.UNKNOWN, "First live trade watched: your tick, after you watch it with the app open"))
    private fun view(stopped: Boolean = false, watched: Boolean = false) = OrbArms.LiquidityTestView(
        "Frozen for the paper test until Mon 26 Oct: the strategy lab's variants, Autopilot and the trade manager only record - they do not change its entries or exits.",
        true, "Liquidity 15+5 BANKNIFTY's paper test: too early (7/60, 6/40) - 7 trades since 13 Oct, +Rs 2,400.",
        lines, LiquidityGoLive.head(lines),
        listOf(if (stopped) "Live stop rule reached on 2026-11-02 (drawdown -Rs 40,000 from its best (limit -Rs 40,000)): no new live entries until you re-enable it with your PIN; exits continue."
            else "Live stop rule (paper, information only): would not have stopped - drawdown -Rs 1,200 of -Rs 40,000, this month -Rs 300 of -Rs 13,000."),
        stopped, "Liquidity BANKNIFTY fills since 13 Oct: 2 signals, 2 entries, 1 exit.", listOf("20 Oct 10:20:03 · 5m BANKNIFTY entry · paper · quote fresh"), watched)

    @Test fun theFreezeTheStopRuleTheChecklistAndTheDetails() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        var watched: Boolean? = null
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            LiquidityTestLines(view(), onWatched = { watched = it }, onReenable = {})
        } } }
        compose.waitForIdle()
        // COMPACT (15 Oct): the freeze said short; a tap shows its words in full, OK closes them.
        assertTrue(shows(FROZEN_SHORT))
        assertFalse(shows(keepNumbersWhole("Frozen for the paper test until Mon 26 Oct"), sub = true))
        compose.onNodeWithText(FROZEN_SHORT).performClick()
        compose.waitForIdle()
        assertTrue(shows(keepNumbersWhole("Frozen for the paper test until Mon 26 Oct"), sub = true))
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertFalse(shows(keepNumbersWhole("Frozen for the paper test until Mon 26 Oct"), sub = true))
        // The live stop rule (the account stop) stays on the row.
        assertTrue(shows("would not have stopped", sub = true))
        assertFalse("no re-enable while it runs", shows(REENABLE_LIVE))
        // The checklist is folded: its count in sight, the lines behind the tap.
        assertTrue(shows("Go-live checklist (Liquidity BANKNIFTY): 1 ✓ · 1 ✗ · 1 —", sub = true))
        assertFalse(shows(keepNumbersWhole("At least 6 trades since 13 Oct"), sub = true))
        compose.onNodeWithTag(expandTag("liq-golive")).performClick()
        compose.waitForIdle()
        assertTrue(shows(keepNumbersWhole("✓ At least 6 trades since 13 Oct: 7"), sub = true))
        assertTrue(shows(keepNumbersWhole("✗ Entry within 10 s of the bar close"), sub = true))
        assertTrue(shows(LiquidityGoLive.NOTE, sub = true))
        compose.onNodeWithText(WATCHED_TICK).performClick()
        compose.waitForIdle()
        assertEquals(true, watched)
        // Show details: the paper test against the backtest and the fills.
        assertFalse(shows(keepNumbersWhole("too early (7/60"), sub = true))
        compose.onNodeWithTag(expandTag("liq-details")).performClick()
        compose.waitForIdle()
        assertTrue(shows(keepNumbersWhole("too early (7/60"), sub = true))
        assertTrue(shows(keepNumbersWhole("2 signals, 2 entries"), sub = true))
        assertTrue(shows(keepNumbersWhole("5m BANKNIFTY entry"), sub = true))
    }

    @Test fun reachedTheStopRuleOffersOnlyThePinReEnable() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        var asked = 0
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            LiquidityTestLines(view(stopped = true), onWatched = {}, onReenable = { asked++ })
        } } }
        compose.waitForIdle()
        assertTrue(shows("no new live entries until you re-enable it with your PIN", sub = true))
        compose.onNodeWithText(REENABLE_LIVE).performClick()
        compose.waitForIdle()
        assertEquals("the row asks for the PIN (the caller's prompt), never re-enables by itself", 1, asked)
    }

    @Test fun unfreezingFromTheLabCardAsksFirst() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val set = ArrayList<Boolean>()
        val day = LocalDate.of(2026, 10, 20)
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            LiquidityFreezeRow(LiquidityFreeze.State(), day) { set += it }
        } } }
        compose.waitForIdle()
        assertTrue(shows("Liquidity BANKNIFTY: Frozen for the paper test until Mon 26 Oct", sub = true))
        compose.onNodeWithText(UNFREEZE).performClick()
        compose.waitForIdle()
        assertTrue("asked first", set.isEmpty())
        compose.onNodeWithText("Unfreeze").performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), set)
    }

    @Test fun bossesTrailSwitchIsOffSaysItFailedAndFlipsInOneTap() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val set = ArrayList<Boolean>()
        var state = LiquidityFreeze.State()
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            LiquidityTrailRow(state) { set += it }
        } } }
        compose.waitForIdle()
        assertTrue(shows("failed research, your choice", sub = true))
        assertTrue(shows("off. Liquidity trades on plain rules.", sub = true))
        assertTrue(shows("12 of 13 big winners", sub = true))
        // What it costs the plain-rules record, said before the tap.
        assertTrue(shows(LiquidityFreeze.TRAIL_RECORD))
        compose.onNodeWithTag("lab-liq-trail-record").assertExists()
        compose.onNodeWithText(LiquidityFreeze.TRAIL_ON).performClick()
        compose.waitForIdle()
        assertEquals("one tap on, no PIN (paper only)", listOf(true), set)
    }

    @Test fun bossesTrailSwitchWhenOnSaysPaperOnlyAndGoesBackInOneTap() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val set = ArrayList<Boolean>()
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            LiquidityTrailRow(LiquidityFreeze.State(trail = true)) { set += it }
        } } }
        compose.waitForIdle()
        assertTrue(shows("ON for new Liquidity PAPER trades", sub = true))
        assertTrue(shows("live trades never use it", sub = true))
        assertTrue(shows("so the plain-rules record (used for the 26 Oct decision) does not grow", sub = true))
        compose.onNodeWithText(LiquidityFreeze.TRAIL_OFF).performClick()
        compose.waitForIdle()
        assertEquals(listOf(false), set)
    }

    @Test fun afterTheTenthDayNoButton() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        compose.setContent { IraAlgoTheme("light") { Column { LiquidityFreezeRow(LiquidityFreeze.State(), LocalDate.of(2026, 11, 2)) {} } } }
        compose.waitForIdle()
        assertTrue(shows("ended on Mon 26 Oct", sub = true))
        assertFalse(shows(UNFREEZE)); assertFalse(shows(FREEZE_AGAIN))
    }
}
