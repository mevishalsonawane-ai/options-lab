package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.TrendWatchHost
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.trend.TrendWatch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The Trend watch's compact row (16 Oct, Boss's UI rule): its name, ONE status line and its switch; a tap on the NAME opens only
 * its own details - what it does, research R63, what is watched and today's turns - with its switches (live positions too,
 * alerts, each source's paper exit: only the Hero ticket's can be on, the rest are disabled as failed research) and OK.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class TrendWatchRowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val day = LocalDate.of(2026, 10, 16)
    private val sw = TrendWatch.Switches()

    private fun show(v: TrendWatchHost.View, on: (Boolean) -> Unit = {}, live: (Boolean) -> Unit = {}, alerts: (Boolean) -> Unit = {},
                     act: (TrendWatch.Source, Boolean) -> Unit = { _, _ -> }) = compose.setContent {
        IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) { TrendWatchRowContent(v, on, live, alerts, act) } }
    }

    private fun click(desc: String) { compose.onNodeWithContentDescription(desc).performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle() }

    @Test fun theRowIsOneNameOneLineAndItsSwitch() {
        val asked = ArrayList<Boolean>()
        val v = TrendWatchHost.View(sw, TrendWatch.status(sw, 3, 1, 0), listOf(TrendWatchHost.Watched(TrendWatch.Source.DONCHIAN, "NIFTY26OCT25000CE",
            false, "T2", "trend holding", "NOTE")))
        show(v, on = { asked += it })
        compose.waitForIdle()
        compose.onNodeWithText(TrendWatch.LABEL).assertExists()
        compose.onNodeWithText(keepNumbersWhole("On · 3 watched, 1 turned today · live off")).assertExists()
        // Nothing of the explanation on the row itself.
        compose.onNodeWithText("tested four turns", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Switch the Trend watch").assertIsOn().performClick()
        compose.waitForIdle()
        assertEquals(listOf(false), asked)
    }

    @Test fun theNameOpensItsDetailsWithItsOwnSwitchesAndOkClosesIt() {
        val acted = ArrayList<Pair<TrendWatch.Source, Boolean>>()
        val live = ArrayList<Boolean>()
        val rec = TrendWatchHost.Rec("k", day, TrendWatch.Source.LIQUIDITY, "BANKNIFTY26OCT52000CE", false, "T2", day.atTime(11, 5), "NOTE",
            "trend turned against Liquidity 15+5 BANKNIFTY26OCT52000CE at 11:05: would sell at 84.20 (T2, shadow: failed research)")
        show(TrendWatchHost.View(sw, TrendWatch.status(sw, 0, 1, 0), emptyList(), listOf(rec)), live = { live += it }, act = { s, on -> acted += s to on })
        compose.waitForIdle()
        click("About ${TrendWatch.LABEL}")
        compose.onNodeWithText("tested four turns", substring = true).assertExists()
        compose.onNodeWithText("Nothing is watched right now.").assertExists()
        compose.onNodeWithText("would sell at", substring = true).assertExists()
        compose.onNodeWithContentDescription("Live positions too (notes and alerts only)").assertIsOff().performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), live)
        // Only the Hero ticket's paper exit can be switched (it passed); every other source is disabled: notes only.
        compose.onNodeWithContentDescription("${TrendWatch.Source.HERO_TICKET.label} (passed research)").performScrollTo().assertIsOn().performClick()
        compose.waitForIdle()
        assertEquals(listOf(TrendWatch.Source.HERO_TICKET to false), acted)
        compose.onNodeWithContentDescription("${TrendWatch.Source.MANUAL.label} (failed research: notes only)").performScrollTo().assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("OK").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("tested four turns", substring = true).assertDoesNotExist()
    }

    @Test fun offSaysSo() {
        val off = sw.copy(on = false)
        show(TrendWatchHost.View(off, TrendWatch.status(off, 0, 0, 0)))
        compose.waitForIdle()
        compose.onNodeWithText("Off: no position is watched").assertExists()
        compose.onNodeWithContentDescription("Switch the Trend watch").assertIsOff()
    }
}
