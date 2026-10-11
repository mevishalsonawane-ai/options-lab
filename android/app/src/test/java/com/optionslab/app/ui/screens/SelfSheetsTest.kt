package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.testing.AreaE
import com.optionslab.app.testing.AreaEWatchdog
import com.optionslab.app.testing.frames
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.BossTrades
import com.optionslab.ira.BotReplay
import com.optionslab.ira.ChangeTimeline
import com.optionslab.ira.FreezeRule
import com.optionslab.ira.StudyBook
import com.optionslab.ira.TradeManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The roadmap's sheets and cards from plain state (S10-S21): Today's changes with "Undo everything today" and the PIN
 * line, the "why?" chips after a hand trade, the learning freeze's card, the study list's search, the replay's
 * counterfactual - upright, on its side and at large font (lazy lists scrolled to their items before asserting).
 */
@Config(qualifiers = "w411dp-h891dp")
@RunWith(AndroidJUnit4::class)
class SelfSheetsTest {
    @get:Rule val watchdog = AreaEWatchdog()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val day = LocalDate.of(2026, 10, 12)
    private fun at(h: Int, m: Int) = day.atTime(h, m).atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()

    @Before fun up() { AreaE.resetGlobals() }
    @After fun down() { AreaE.resetGlobals(); RuntimeEnvironment.setFontScale(1f) }

    private fun set(content: @Composable () -> Unit) { compose.setContent { IraAlgoTheme("light") { content() } }; compose.frames() }
    private fun shows(t: String) = compose.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun scrolledShows(t: String): Boolean {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(t, substring = true)); compose.frames(); return shows(t)
    }
    private fun tapTag(tag: String) { compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); compose.frames(); compose.waitForIdle() }

    private val items = listOf(
        ChangeTimeline.Item("ap:1", ChangeTimeline.Source.AUTOPILOT, at(15, 50), "Autopilot · trade manager specialists", "the OI specialist: counts → muted",
            "it hurt", ChangeTimeline.UndoState.UNDOABLE, "paper", "1"),
        ChangeTimeline.Item("ap:2", ChangeTimeline.Source.AUTOPILOT, at(15, 51), "Autopilot · self-review", "Liquidity's new live entries: live → parked",
            "drifting", ChangeTimeline.UndoState.NEEDS_PIN, "live", "2"),
        ChangeTimeline.Item("health:x", ChangeTimeline.Source.HEALTH, at(11, 0), "Health tuner", "the Brain card's refresh slowed to every 30 s",
            "stalls", ChangeTimeline.UndoState.UNDOABLE, "app speed", "brain.consensus"),
    )

    @Test fun todaysChangesListEveryChangeAndLeaveLiveRaisesForThePin() {
        var undone = 0; var pin = 0
        set { TodayChangesContent(items, null, false, onUndoAll = { undone++ }, onUndoWithPin = { pin++ }) }
        assertTrue(shows("Undo everything today (2)"))
        assertTrue(shows("Needs your PIN (adds live risk): Liquidity's new live entries: live → parked"))
        tapTag("undo-all"); assertEquals(1, undone)
        tapTag("undo-all-pin"); assertEquals(1, pin)
        assertTrue(scrolledShows("the Brain card's refresh slowed to every 30 s"))
        assertTrue(scrolledShows("Undo needs your PIN (it adds live risk)"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("change-ap:1"))
        assertTrue(compose.onAllNodesWithTag("change-ap:1").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun nothingTodaySaysSo() {
        set { TodayChangesContent(emptyList(), null, false, {}, {}) }
        assertTrue(scrolledShows("Nothing changed by itself today."))
    }

    @Config(qualifiers = "w891dp-h411dp-land")
    @Test fun theDialogsTitleAndCloseKeepApartOnItsSideAndAtLargeFont() {
        RuntimeEnvironment.setFontScale(2f)
        set { SelfDialog(TODAY_CHANGES_TITLE, "today-changes", onClose = {}) { TodayChangesContent(items, null, false, {}, {}) } }
        val a: DpRect = compose.onNodeWithTag("today-changes-title").getBoundsInRoot()
        val b: DpRect = compose.onNodeWithTag("today-changes-close").getBoundsInRoot()
        assertTrue("title and Close overlap", a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
        assertTrue(scrolledShows("Liquidity's new live entries: live → parked"))
    }

    @Test fun theWhyCardOffersOneTapChipsForAnEntryAndAnExit() {
        var picked: BossTrades.Why? = null
        set { ManualReasonContent("NIFTY26OCT25000CE", exit = false, onEntry = { w, _ -> picked = w }, onExit = { _, _ -> }, onDismiss = {}) }
        for (w in listOf("breakout", "level", "news", "gut", "following Jarvis", "other")) assertTrue(w, shows(w))
        tapTag("why-GUT")
        assertEquals(BossTrades.Why.GUT, picked)
    }

    @Test fun theWhyCardOffersOneTapChipsForAnExit() {
        var out: BossTrades.ExitWhy? = null
        var gone = false
        set { ManualReasonContent("NIFTY26OCT25000CE", exit = true, onEntry = { _, _ -> }, onExit = { w, _ -> out = w }, onDismiss = { gone = true }) }
        for (w in listOf("target", "stop", "fear", "news", "time", "other")) assertTrue(w, shows(w))
        tapTag("exit-FEAR"); assertEquals(BossTrades.ExitWhy.FEAR, out)
        tapTag("manual-reason-dismiss"); assertTrue(gone)
    }

    @Test fun theWhyCardIsNotModalAndItsCrossSaysDismiss() {
        // Review R5 #7: a dialog after a fill swallowed Boss's next tap. The card is inline: no dialog, and the page under
        // it keeps its taps (a real touch, not a semantics action).
        var under = 0
        set {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(80.dp).testTag("under").clickable { under++ })
                ManualReasonCard(com.optionslab.app.ira.BossTradeLog.Prompt("t1", "NIFTY26OCT25000CE", exit = false, atMs = at(10, 0)))
            }
        }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertTrue(compose.onAllNodesWithTag("manual-reason").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithContentDescription("Dismiss").assertExists()
        compose.onNodeWithTag("under").performTouchInput { click() }
        compose.frames(); compose.waitForIdle()
        assertEquals(1, under)
    }

    @Test fun aNewFillsWhyCardStartsWithAnEmptyNote() {
        // Review G10 F6: trade B's card must not carry trade A's half-typed note (one chip tap would record it against B).
        var prompt by androidx.compose.runtime.mutableStateOf(com.optionslab.app.ira.BossTradeLog.Prompt("t1", "NIFTY26OCT25000CE", exit = false, atMs = at(10, 0)))
        set { ManualReasonCard(prompt) }
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("A broke out")
        compose.frames(); compose.waitForIdle()
        assertTrue(shows("A broke out"))
        prompt = com.optionslab.app.ira.BossTradeLog.Prompt("t2", "NIFTY26OCT25000CE", exit = false, atMs = at(10, 1))
        compose.frames(); compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("manual-reason").fetchSemanticsNodes().isNotEmpty())
        assertTrue("the last trade's note carried over", !shows("A broke out"))
    }

    @Config(qualifiers = "w320dp-h640dp")
    @Test fun theWhyCardSitsAboveTheTabBarAtLargeFontOnASmallPhone() {
        // Review G10 F5: the card is placed above the bottom chrome as laid out (the tab bar with its navigation-bar inset).
        RuntimeEnvironment.setFontScale(2f)
        val chrome = 130.dp
        set {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                    .fillMaxWidth().height(chrome).testTag("tab-bar"))
                ManualReasonCard(com.optionslab.app.ira.BossTradeLog.Prompt("t1", "NIFTY26OCT25000CE", exit = true, atMs = at(10, 0)), bottomChrome = chrome)
            }
        }
        val card = compose.onNodeWithTag("manual-reason").getBoundsInRoot()
        val bar = compose.onNodeWithTag("tab-bar").getBoundsInRoot()
        assertTrue("the card overlaps the tab bar: $card vs $bar", card.bottom <= bar.top)
    }

    @Test fun todayRollsOverAtMidnight() {
        // Review G10 F4: a card left composed overnight reads the new day (the Automation card's counts, Today's lesson).
        val ist = com.optionslab.engine.IST
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(day.atTime(23, 59, 30).atZone(ist).toInstant(), ist)
        try {
            set { androidx.compose.material3.Text("day " + rememberToday()) }
            assertTrue(shows("day $day"))
            com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(day.plusDays(1).atTime(0, 0, 30).atZone(ist).toInstant(), ist)
            compose.mainClock.advanceTimeBy(61_000)
            compose.waitForIdle()
            assertTrue(shows("day ${day.plusDays(1)}"))
        } finally { com.optionslab.app.data.Market.testClock = null }
    }

    @Test fun theFreezeCardSaysWhyAndOffersTheUnfreeze() {
        var unfrozen = false
        val s = FreezeRule.State(true, setOf(FreezeRule.Account.PAPER), day, "paper P&L −₹4,100 since the first automatic change on 8 Oct")
        set { LearningFreezeContent(s, emptyList(), "", null, onLine = {}, onSaveLine = {}, onUnfreeze = { unfrozen = true }) }
        assertTrue(shows("FROZEN: no automatic change is applied"))
        assertTrue(shows("−₹4,100"))
        tapTag("unfreeze"); assertTrue(unfrozen)
    }

    @Test fun anUnfrozenCardSaysSoWithoutAnUnfreeze() {
        set { LearningFreezeContent(FreezeRule.State(), emptyList(), "", null, {}, {}, {}) }
        assertTrue(shows("Not frozen"))
        assertTrue(compose.onAllNodesWithTag("unfreeze").fetchSemanticsNodes().isEmpty())
    }

    @Test fun theStudyListSearches() {
        val pages = StudyBook.parse("@@ vwap | VWAP | Reading the market | vwap\nThe day's average price.\n\nMore.\n@@ risk | Risk | Risk | sizing\nOne lot.\n\nMore.\n")
        var opened: String? = null
        set { StudyList(pages) { opened = it.id } }
        assertTrue(shows("VWAP")); assertTrue(shows("Risk"))
        tapTag("study-risk"); assertEquals("risk", opened)
    }

    @Test fun theReplayShowsTheCounterfactualAndTheTimeline() {
        val t = TradeManager.ManagedTrade("pine:x", "p1", "Pine · EMA", "NIFTY", 1, "NIFTY26OCT25000CE", 100.0, 75, 85.0, 140.0,
            TradeManager.Caps(at(15, 10)), TradeManager.Account.PAPER, at(10, 0))
        val r = TradeManager.Record(t, TradeManager.Mode.ACT, listOf(TradeManager.Note(at(10, 9), TradeManager.EXIT_EARLY, "FLOW", "the flow turned", 110.0, true, lead = "flow")),
            actual = TradeManager.Exit(at(10, 10), 110.0, "EARLY"), original = TradeManager.Exit(at(10, 30), 140.0, "TARGET"), actedAtMs = at(10, 0))
        val bars = (0 until 40).map { TradeManager.Bar(at(9, 55) + it * 60_000L, 100.0 + it, 102.0 + it, 99.0 + it, 101.0 + it) }
        set { BotReplayContent(BotReplay.of(r, bars, "Order flow at the entry agreed with the trade."), null) }
        assertTrue(shows("Why it exited: The trade manager took it out: the flow turned"))
        assertTrue(scrolledShows(keepNumbersWhole("The original rules would have exited at 140")))
        assertTrue(scrolledShows("WHAT THE TRADE MANAGER SAID"))
        assertTrue(scrolledShows(keepNumbersWhole("10:10 exit at 110")))
    }
}
