package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.PassedArmsHost
import com.optionslab.app.data.WatchlistHost
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.OovArm
import com.optionslab.engine.orb.PassedArms
import com.optionslab.engine.orb.Solo2
import com.optionslab.engine.orb.Watchlist
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 15 Oct (Boss: "Both"; compact rows, Boss's UI rule): each passed-research arm's row shows only its name, a short status line
 * and its key numbers, with its paper-only switch (one tap off); a tap on the NAME opens the explanation - the "Passed research
 * ... paper only" label, research id and result, settings, the forward-test bar - and OK closes it. The Watchlist's card: the
 * same, its dialog saying "shadow, not traded; information only, not a trade signal" and C4 NON-DEFAULT.
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class PassedArmRowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val t0 = LocalDateTime.of(2026, 10, 20, 9, 20, 20)

    private fun show(v: PassedArmsHost.View, w: WatchlistHost.View = WatchlistHost.View(), onArm: (String, Boolean) -> Unit = { _, _ -> }, onWatch: (Boolean) -> Unit = {}) =
        compose.setContent {
            IraAlgoTheme("light") {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PassedArmsHost.SOURCES.forEach { s -> PassedArmRowContent(s, v) { onArm(s, it) } }
                    WatchlistCardContent(w, onWatch)
                }
            }
        }

    @Test fun eachRowIsCompactAndOneTapSwitchesItOff() {
        val asked = ArrayList<Pair<String, Boolean>>()
        val v = PassedArmsHost.View(armed = mapOf(DonchianArm.SOURCE to true, OovArm.SOURCE to false, Solo2.SOURCE to true))
        show(v, onArm = { s, on -> asked += s to on })
        compose.waitForIdle()
        compose.onNodeWithText(DonchianArm.LABEL).assertExists()
        compose.onNodeWithText(OovArm.LABEL).assertExists()
        compose.onNodeWithText(Solo2.LABEL).assertExists()
        // No explanation on the row itself: the rules, the record and the label are in the name's dialog.
        compose.onNodeWithText(keepNumbersWhole(DonchianArm.RULES), substring = true).assertDoesNotExist()
        compose.onNodeWithText(keepNumbersWhole(PassedArms.PASSED)).assertDoesNotExist()
        // ONE status line on the row; the numbers are in each name's own dialog (only that arm's).
        compose.onNodeWithText(keepNumbersWhole("0 closed, +₹0 · FINNIFTY +₹0 · forward test 0/40."), substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("About ${DonchianArm.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("0 closed, +₹0 · FINNIFTY +₹0 · forward test 0/40.")).assertExists()
        compose.onNodeWithText(keepNumbersWhole("0 closed, +₹0 · MIDCPNIFTY +₹0 · forward test 0/60."), substring = true).assertDoesNotExist()
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("About ${Solo2.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("0 closed, +₹0 · MIDCPNIFTY +₹0 · forward test 0/60.")).assertExists()
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Paper only: switch ${DonchianArm.LABEL}").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Paper only: switch ${OovArm.LABEL}").assertIsOff().performClick()
        compose.waitForIdle()
        assertEquals(listOf(DonchianArm.SOURCE to false, OovArm.SOURCE to true), asked)
    }

    @Test fun tappingTheNameOpensTheExplanationAndOkClosesIt() {
        show(PassedArmsHost.View(armed = mapOf(OovArm.SOURCE to true)))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("About ${OovArm.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole(PassedArms.PASSED)).assertExists()
        compose.onNodeWithText(keepNumbersWhole("Research: ${OovArm.RESEARCH_ID}"), substring = true).assertExists()
        compose.onNodeWithText("Stop added for CLAUDE.md", substring = true).assertExists()
        compose.onNodeWithText("switches itself off if the net", substring = true).assertExists()
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole(PassedArms.PASSED)).assertDoesNotExist()
    }

    @Test fun todaysTradesAndTheSelfSwitchOffShowShort() {
        val held = PassedArmsHost.Pos(DonchianArm.SOURCE, "NIFTY", "NIFTY20OCT2625050CE", "CE", 65, 100.0, t0, "p1")
        val sold = PassedArmsHost.Pos(DonchianArm.SOURCE, "FINNIFTY", "FINNIFTY20OCT2624000PE", "PE", 65, 50.0, t0, "p2", exit = 60.0,
            exitTime = t0.plusMinutes(10), why = "target", charges = 50.0)
        val v = PassedArmsHost.View(armed = mapOf(DonchianArm.SOURCE to true), open = listOf(held), today = listOf(held, sold), all = listOf(sold),
            paused = mapOf(Solo2.SOURCE to "a drawdown of −₹26,000"))
        show(v)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("On · paper · holding 1")).assertExists()
        compose.onNodeWithContentDescription("About ${DonchianArm.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("1 closed, +₹600 · FINNIFTY +₹600 · forward test 1/40.")).assertExists()
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("Off · switched itself off (forward test)")).assertExists()
    }

    @Test fun theWatchlistCardIsCompactAndItsDialogSaysShadowAndNonDefault() {
        val asked = ArrayList<Boolean>()
        val w = WatchlistHost.View(on = true, cards = Watchlist.Rule.entries.map { Watchlist.score(it, emptyList(), emptyList()) })
        show(PassedArmsHost.View(), w, onWatch = { asked += it })
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("On · shadow, not traded · 0 open")).assertExists()
        compose.onNodeWithText(keepNumbersWhole("R47 C4 BANKNIFTY high PCR (non-default): 0, +₹0")).assertDoesNotExist()
        compose.onNodeWithText(Watchlist.SELECTION).assertDoesNotExist()
        compose.onNodeWithContentDescription("About Watchlist (shadow)").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("R47 C4 BANKNIFTY high PCR (non-default): 0, +₹0")).assertExists()
        compose.onNodeWithText("Shadow, not traded; information only, not a trade signal.").assertExists()
        compose.onNodeWithText(keepNumbersWhole(Watchlist.SELECTION)).assertExists()
        compose.onNodeWithText("NON-DEFAULT (option-chain signal)", substring = true).assertExists()
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole(Watchlist.SELECTION)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Shadow only: switch the Watchlist").assertIsOn().performClick()
        compose.waitForIdle()
        assertEquals(listOf(false), asked)
    }

    @Test fun aRecordInWords() {
        val s = Watchlist.Signal(Watchlist.Rule.R4_8, "NIFTY", "r4_NIFTY", 1, LocalDate.of(2026, 10, 20).atTime(12, 0), 25_050, "x")
        val p = Watchlist.Priced(s.entry, 100.0, 100.2, s.entry.plusMinutes(30), 110.0, 109.8, "time", 65, 40.0)
        val r = WatchlistHost.Rec(s, LocalDate.of(2026, 10, 27), "NIFTY-X", "NSE_FO|X", 65, WatchlistHost.DONE, p)
        assertEquals("R52 R4-8 Gann swing break · 2026-10-20 12:00 · NIFTY 2026-10-27 25050 CE · in 100.20 @ 12:00, out 109.80 @ 12:30 (time) · would-be +₹584",
            watchRecordLine(r))
        assertEquals("R52 R4-8 Gann swing break · 2026-10-20 12:00 · NIFTY ? 25050 CE · open (shadow)", watchRecordLine(WatchlistHost.Rec(s, null, null, null, null)))
        assertEquals(true, watchRecordLine(r.copy(state = WatchlistHost.NOFILL, p = null)).endsWith("would not have filled"))
    }
}
