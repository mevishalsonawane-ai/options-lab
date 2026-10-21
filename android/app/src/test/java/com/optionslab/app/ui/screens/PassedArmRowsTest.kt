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
import com.optionslab.engine.orb.BreadthDonchianArm
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
    // 21 Oct, enhancement round 4: the market clock at noon IST on a trading day unless the test sets its own.
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val t0 = LocalDateTime.of(2026, 10, 20, 9, 20, 20)

    private fun show(v: PassedArmsHost.View, w: WatchlistHost.View = WatchlistHost.View(), onArm: (String, Boolean) -> Unit = { _, _ -> }, onWatch: (Boolean) -> Unit = {},
                     onRecord: (String) -> Unit = {}) =
        compose.setContent {
            IraAlgoTheme("light") {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PassedArmsHost.SOURCES.forEach { s -> PassedArmRowContent(s, v, onRecordOnly = { onRecord(s) }) { onArm(s, it) } }
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

    /**
     * 19 Oct (Boss: "Yes, keep only breadth donchain"): R51 record only - its row says so, its switch is off, "Record only" is picked; its
     * would-be trades are counted apart in its numbers; any row can pick on, record only or off.
     */
    @Test fun aRecordOnlyRowSaysSoAndOffersOnRecordOnlyAndOff() {
        val armed = ArrayList<Pair<String, Boolean>>()
        val rec = ArrayList<String>()
        val wb = PassedArmsHost.Pos(DonchianArm.SOURCE, "NIFTY", "NIFTY20OCT2625050CE", "CE", 65, 100.0, t0, null, shadow = PassedArmsHost.SHADOW_OFF)
        val done = PassedArmsHost.Pos(DonchianArm.SOURCE, "FINNIFTY", "FINNIFTY20OCT2624000PE", "PE", 65, 50.0, t0, null, exit = 60.0,
            exitTime = t0.plusMinutes(10), why = "target", charges = 50.0, shadow = PassedArmsHost.SHADOW_OFF)
        val v = PassedArmsHost.View(armed = mapOf(DonchianArm.SOURCE to false, BreadthDonchianArm.SOURCE to true), recordOnly = setOf(DonchianArm.SOURCE),
            open = listOf(wb), today = listOf(wb, done), all = listOf(done))
        show(v, onArm = { s, on -> armed += s to on }, onRecord = { rec += it })
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("Record only (would-be trades, nothing bought) · holding 1 (1 would-be)")).assertExists()
        compose.onNodeWithText(keepNumbersWhole("On · paper · no trade today")).assertExists()
        compose.onNodeWithContentDescription("Paper only: switch ${DonchianArm.LABEL}").assertIsOff()
        compose.onNodeWithContentDescription("Record only, nothing bought: ${DonchianArm.LABEL}").assertIsOn()
        compose.onNodeWithContentDescription("Record only, nothing bought: ${BreadthDonchianArm.LABEL}").assertIsOff()
        // Its numbers count the would-be trades apart; its dialog says what record only means.
        compose.onNodeWithContentDescription("About ${DonchianArm.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("1 closed (1 would-be, +₹600), +₹600 · FINNIFTY +₹600 · forward test 1/40.")).assertExists()
        compose.onNodeWithText("Nothing is bought, never live", substring = true).assertExists()
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        // Picking: R72 record only; R51 back on with its switch; R51's ticked "Record only" tapped again: off.
        compose.onNodeWithContentDescription("Record only, nothing bought: ${BreadthDonchianArm.LABEL}").performClick()
        compose.onNodeWithContentDescription("Paper only: switch ${DonchianArm.LABEL}").performClick()
        compose.onNodeWithContentDescription("Record only, nothing bought: ${DonchianArm.LABEL}").performClick()
        compose.waitForIdle()
        assertEquals(listOf(BreadthDonchianArm.SOURCE), rec)
        assertEquals(listOf(DonchianArm.SOURCE to true, DonchianArm.SOURCE to false), armed)
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

    /** 15 Oct (Boss: Solo explains itself): today's take / skip reasons and the context-only concepts are in Solo's dialog, not on its row. */
    @Test fun solosDialogSaysWhyItTookOrSkippedAndWhatIsContextOnly() {
        val skip = "S3 NIFTY call 0.4100: below 0.4871, skipped · raised by India VIX higher than usual, lowered by options dearer than usual (ATM IV)"
        show(PassedArmsHost.View(armed = mapOf(Solo2.SOURCE to true), scores = listOf(skip)))
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("• $skip."), substring = true).assertDoesNotExist()
        compose.onNodeWithText(keepNumbersWhole(Solo2.CONTEXT_NOTE), substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("About ${Solo2.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("• $skip.")).assertExists()
        compose.onNodeWithText(keepNumbersWhole(Solo2.CONTEXT_NOTE + ".")).assertExists()
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("• $skip.")).assertDoesNotExist()
    }

    @Test fun soloWithNoDecisionYetSaysSoAndOtherArmsCarryNone() {
        val v = PassedArmsHost.View()
        val solo = soloDecisions(v)
        assertEquals("Today's decisions: none yet (it decides at each setup's entry minute).", solo.first())
        assertEquals(Solo2.CONTEXT_NOTE + ".", solo.last())
        org.junit.Assert.assertTrue(passedExplanation(Solo2.SOURCE, v).containsAll(solo))
        org.junit.Assert.assertTrue(passedExplanation(DonchianArm.SOURCE, v).none { "Today's decisions" in it || it == Solo2.CONTEXT_NOTE + "." })
    }

    /** 16 Oct (R66 L25, Boss's choice): the name's dialog says the exits new trades enter with; the row stays one line. */
    @Test fun theDialogSaysTheExitsNewTradesEnterWith() {
        show(PassedArmsHost.View(armed = mapOf(DonchianArm.SOURCE to true), lockOn = true, l25On = true))
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("+5% locked once +25%"), substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("About ${DonchianArm.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole(com.optionslab.ira.PassedArmsTalk.exitsWords(true, true) + ".")).assertExists()
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        org.junit.Assert.assertTrue(passedExplanation(OovArm.SOURCE, PassedArmsHost.View(lockOn = true)).any { "breakeven locked once +30%" in it && "+25%" !in it })
        org.junit.Assert.assertTrue(passedExplanation(Solo2.SOURCE, PassedArmsHost.View()).any { "research's own stop, target and time exit" in it })
    }

    /** 16 Oct (R72, Boss: "Okay add it"): its compact row beside Donchian's; its dialog carries its risk line and the comparison. */
    @Test fun breadthDonchiansRowAndItsDialogSayItsRiskAndTheComparison() {
        val t = LocalDateTime.of(2026, 10, 16, 9, 20)
        val br = PassedArmsHost.Pos(BreadthDonchianArm.SOURCE, "NIFTY", "N1", "CE", 65, 100.0, t, "b1", exit = 120.0, exitTime = t.plusMinutes(10), why = "time")
        val dn = PassedArmsHost.Pos(DonchianArm.SOURCE, "NIFTY", "N2", "CE", 65, 100.0, t, "d1", exit = 90.0, exitTime = t.plusMinutes(10), why = "stop", breadth = false)
        val v = PassedArmsHost.View(armed = mapOf(BreadthDonchianArm.SOURCE to true, DonchianArm.SOURCE to true), all = listOf(br, dn),
            breadthSince = LocalDate.of(2026, 10, 16))
        show(v)
        compose.waitForIdle()
        compose.onNodeWithText(BreadthDonchianArm.LABEL).assertExists()
        compose.onNodeWithText(keepNumbersWhole("Risk: ${BreadthDonchianArm.RISK}."), substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("About ${BreadthDonchianArm.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole("Risk: ${BreadthDonchianArm.RISK}.")).assertExists()
        compose.onNodeWithText(keepNumbersWhole("Research: ${BreadthDonchianArm.RESEARCH_ID}"), substring = true).assertExists()
        compose.onNodeWithText(keepNumbersWhole(v.comparison() + ".")).assertExists()
        org.junit.Assert.assertTrue(v.comparison(), v.comparison().contains("since 16 Oct: R72 Breadth Donchian 1 trade, +₹1,300; R51 Donchian 20 1 trade, −₹650; " +
            "Donchian's trades breadth did not confirm: 1 trade, −₹650"))
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Paper only: switch ${BreadthDonchianArm.LABEL}").assertIsOn()
        // Donchian's own dialog carries neither.
        org.junit.Assert.assertTrue(passedExplanation(DonchianArm.SOURCE, v).none { "Risk:" in it || "Breadth Donchian vs Donchian" in it })
    }

    /** 16 Oct (R73 review): R51's row says the breaks Supertrend could not judge; its dialog says the filter and its record when on. */
    @Test fun donchiansRowAndDialogSayTheSupertrendFilterWhenItIsOn() {
        val t = LocalDateTime.of(2026, 10, 16, 9, 20)
        val st = PassedArmsHost.Pos(DonchianArm.SOURCE, "NIFTY", "N1", "CE", 65, 100.0, t, "d1", exit = 120.0, exitTime = t.plusMinutes(10), why = "time", st = true)
        val plain = PassedArmsHost.Pos(DonchianArm.SOURCE, "NIFTY", "N2", "CE", 65, 100.0, t, "d2", exit = 90.0, exitTime = t.plusMinutes(10), why = "stop")
        val br = PassedArmsHost.Pos(BreadthDonchianArm.SOURCE, "NIFTY", "N3", "CE", 65, 100.0, t, "b1", exit = 110.0, exitTime = t.plusMinutes(10), why = "time")
        val v = PassedArmsHost.View(armed = mapOf(DonchianArm.SOURCE to true), all = listOf(st, plain, br), stOn = true, stUnjudged = 2,
            breadthSince = LocalDate.of(2026, 10, 16))
        show(v)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole(passedStatusLine(DonchianArm.SOURCE, v))).assertExists()
        org.junit.Assert.assertTrue(passedStatusLine(DonchianArm.SOURCE, v), passedStatusLine(DonchianArm.SOURCE, v).endsWith(" · 2 not judged by Supertrend"))
        compose.onNodeWithContentDescription("About ${DonchianArm.LABEL}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(keepNumbersWhole(DonchianArm.rules(true) + ".")).assertExists()
        compose.onNodeWithText(keepNumbersWhole(DonchianArm.ST_RECORD + ".")).assertExists()
        compose.onNodeWithText("OK").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        // The comparison sets Breadth against Donchian's filtered and plain-rules records apart, labelled.
        org.junit.Assert.assertTrue(v.comparison(), v.comparison().contains("Donchian's with its Supertrend filter: 1 trade, +₹1,300, on its plain rules: 1 trade, −₹650"))
        // Off: the plain rules, no record of the filter, no count on the row; never on another arm's row.
        val off = v.copy(stOn = false)
        org.junit.Assert.assertTrue(passedExplanation(DonchianArm.SOURCE, off).none { "Supertrend" in it })
        org.junit.Assert.assertTrue(DonchianArm.RULES + "." in passedExplanation(DonchianArm.SOURCE, off))
        org.junit.Assert.assertFalse("Supertrend" in passedStatusLine(DonchianArm.SOURCE, off))
        org.junit.Assert.assertTrue(passedExplanation(BreadthDonchianArm.SOURCE, v).none { "Supertrend (10, 3)" in it })
        org.junit.Assert.assertFalse("Supertrend" in passedStatusLine(BreadthDonchianArm.SOURCE, v))
    }
}
