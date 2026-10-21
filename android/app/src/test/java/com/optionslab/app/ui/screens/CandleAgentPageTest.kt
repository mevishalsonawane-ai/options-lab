package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.CandleAgentHost
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.CandleAgent
import com.optionslab.ira.CandleBook
import com.optionslab.ira.CandlePage
import com.optionslab.ira.CandleRead
import com.optionslab.ira.Findings
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** The Candle agent's page (20 Oct 2026): its status, latest candles (tap one: its explanation), table, lessons and talk. */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
class CandleAgentPageTest {
    // 21 Oct, enhancement round 4: the market clock at noon IST on a trading day unless the test sets its own.
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun down() { CandleAgentHost.resetForTest(); com.optionslab.app.data.Market.testClock = null }

    private val day = LocalDate.of(2026, 10, 19)

    /** The page is a lazy list: a card below the screen is composed once the list scrolls to it. */
    private fun scrollTo(tag: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    private fun agent(): CandleAgent {
        val seed = CandleBook.seed(compose.activity.assets.open("knowledge/candle_seed.json").bufferedReader().use { it.readText() })
        val s = CandleBook.Stats()
        s.cells["NIFTY|15|big_lower_wick|all"] = CandleBook.Cell(n5 = 200, up5 = 102)
        s.cells["NIFTY|15|*G|all"] = CandleBook.Cell(n5 = 2_000, up5 = 1_000)
        s.cells["NIFTY|15|*R|all"] = CandleBook.Cell(n5 = 2_000, up5 = 1_000)
        val a = CandleAgent(seed, s)
        val r = java.util.Random(3)
        val bars = ArrayList<CandleRead.Bar>()
        var px = 24_000.0
        var t = day.atTime(9, 15)
        while (t.isBefore(day.atTime(14, 0))) {
            val o = px; val c = o + r.nextGaussian() * 4
            bars += CandleRead.Bar(t, o, maxOf(o, c) + 2, minOf(o, c) - 2, c); px = c; t = t.plusMinutes(5)
        }
        bars += CandleRead.Bar(day.atTime(14, 0), px, px + 12, px - 30, px + 5)
        val now = day.atTime(14, 6)
        val ms = now.atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        val brain = Findings.Finding("the market brain", Findings.Kind.OTHER, Findings.ALL, atMs = ms - 60_000, words = "busy day", seq = 1)
        a.study(listOf(CandleAgent.Series("NIFTY", 5, bars)), listOf(brain), ms, now, true)
        return a
    }

    @Test fun thePageShowsTheReadsTheTableTheLessonsAndTheTalk() {
        CandleAgentHost.resetForTest(agent())
        compose.setContent { IraAlgoTheme("light") { CandleAgentPage() } }
        compose.waitForIdle()
        compose.onNodeWithText(CANDLE_AGENT_TITLE).assertExists()
        compose.onAllNodesWithText("reading candles", substring = true).onFirst().assertExists()
        // The newest notable candle: tap its name for the whole explanation.
        compose.onAllNodesWithText("NIFTY 5m 19 Oct 14:00-14:05", substring = true).onFirst().performClick()
        compose.onAllNodesWithText("Green because", substring = true).onFirst().assertExists()
        compose.onAllNodesWithText("A read, not a signal", substring = true).onFirst().assertExists()
        compose.onNodeWithText("OK").performClick()
        scrollTo("candle-table")
        compose.onAllNodesWithText("big tail (long lower wick)", substring = true).onFirst().assertExists()
        compose.onAllNodesWithText("agrees with the research", substring = true).onFirst().assertExists()
        scrollTo("candle-lessons")
        compose.onAllNodesWithText("Big tail (long lower wick) on NIFTY 15m: agrees with the research (200 counted)", substring = true).onFirst().assertExists()
        scrollTo("candle-talk")
        compose.onAllNodesWithText("heard the market brain: busy day", substring = true).onFirst().assertExists()
        compose.onAllNodesWithText("shared the findings bus", substring = true).onFirst().assertExists()
        compose.onAllNodesWithText("never count as a vote", substring = true).onFirst().assertExists()
    }

    @Test fun beforeItsFirstBatchAndAfterBeingLetGoItSaysSo() {
        compose.setContent { IraAlgoTheme("light") { CandleAgentContent(CandleAgentHost.View()) } }
        compose.onAllNodesWithText("Waiting for its first batch", substring = true).onFirst().assertExists()
        compose.onNodeWithText("No notable candle read yet.").assertExists()
        scrollTo("candle-talk")
        compose.onNodeWithText("Nothing shared or heard yet.").assertExists()
    }

    /**
     * 20 Oct, review: an open explanation was remembered by its row's place, so a batch adding a newer candle on top showed the
     * next candle's words in the open dialog. Each row is now keyed by its candle: the dialog stays on the one tapped.
     */
    @Test fun anOpenExplanationStaysOnItsCandleWhenANewerOneArrives() {
        val all = agent().latest()
        org.junit.Assume.assumeTrue("needs three notable candles", all.size >= 3)
        val view = androidx.compose.runtime.mutableStateOf(CandleAgentHost.View(latest = all.drop(1)))
        compose.setContent { IraAlgoTheme("light") { CandleAgentContent(view.value) } }
        compose.waitForIdle()
        val tapped = all[2]                                            // the second row before the new candle comes
        compose.onAllNodesWithText(tapped.title).onFirst().performClick()
        compose.onAllNodesWithText(keepNumbersWhole(tapped.lines[0])).onFirst().assertExists()
        // A batch adds a newer candle at the top.
        view.value = CandleAgentHost.View(latest = all)
        compose.waitForIdle()
        compose.onAllNodesWithText(keepNumbersWhole(tapped.lines[0])).onFirst().assertExists()
        compose.onAllNodesWithText(keepNumbersWhole(all[1].lines[0])).assertCountEquals(0)
    }

    @Test fun beforeItsFirstBatchTheStatusPromisesNothingItWillNotDo() {
        compose.setContent { IraAlgoTheme("light") { CandleAgentContent(CandleAgentHost.View()) } }
        compose.onAllNodesWithText("hourly otherwise", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("starts with the next session", substring = true).onFirst().assertExists()
    }

    @Test fun afterAMemoryTrimThePageSaysPositionsComeFirst() {
        compose.setContent { IraAlgoTheme("light") { CandleAgentContent(CandleAgentHost.View(released = true)) } }
        compose.onAllNodesWithText("open positions come first", substring = true).onFirst().assertExists()
    }

    // ---- 20 Oct, enhancement round 2: the "as of" line, the instrument chips, 1-minute candles never lessons, What's New's tap --

    private fun ms(t: java.time.LocalDateTime) = t.atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

    @Test fun theAsOfLineSaysPlainlyWhenNothingWasReadYet() {
        compose.setContent { IraAlgoTheme("light") { CandleAgentContent(CandleAgentHost.View(), nowMs = ms(day.atTime(10, 0)), open = true) } }
        compose.onNodeWithText("As of: nothing read yet (its first batch runs while NSE or MCX is open).").assertExists()
    }

    @Test fun theAsOfLineShowsTheLastBatchAndCallsALateOneStale() {
        val a = agent()                                                   // its batch ran at 14:06
        val view = androidx.compose.runtime.mutableStateOf(CandleAgentHost.View(status = a.status(), latest = a.latest()))
        val now = androidx.compose.runtime.mutableStateOf(ms(day.atTime(14, 9)))
        val open = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { IraAlgoTheme("light") { CandleAgentContent(view.value, nowMs = now.value, open = open.value) } }
        compose.onNodeWithText("As of 14:06.").assertExists()
        now.value = ms(day.atTime(14, 40))                               // no batch for 34 minutes in the session
        compose.waitForIdle()
        compose.onAllNodesWithText("As of 14:06 (34 min ago: late", substring = true).onFirst().assertExists()
        open.value = false                                                // after the close: the last batch's, never "late"
        compose.waitForIdle()
        compose.onNodeWithText("As of 14:06 (markets closed: the last batch's reads).").assertExists()
        compose.onAllNodesWithText("overdue", substring = true).assertCountEquals(0)
    }

    @Test fun thePageReadsTheClockForItsAsOfLine() {
        val a = agent()
        CandleAgentHost.resetForTest(a)
        com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(day.atTime(14, 9).atZone(ZoneId.of("Asia/Kolkata")).toInstant(), ZoneId.of("Asia/Kolkata"))
        compose.setContent { IraAlgoTheme("light") { CandleAgentPage() } }
        compose.waitForIdle()
        compose.onAllNodesWithText("As of 14:06", substring = true).onFirst().assertExists()
    }

    @Test fun theChipsFilterTheCandlesTheLessonsAndTheNotes() {
        val a = agent()
        val v = CandleAgentHost.View(status = a.status(), latest = a.latest(),
            lessons = listOf(CandlePage.Line("NIFTY", "the nifty lesson"), CandlePage.Line("BANKNIFTY", "the banknifty lesson")),
            notes = listOf(CandlePage.Line("BANKNIFTY", "the banknifty note")))
        compose.setContent { IraAlgoTheme("light") { CandleAgentContent(v, nowMs = ms(day.atTime(14, 9)), open = true) } }
        compose.waitForIdle()
        // All, then the instruments the page shows, in the app's order; SENSEX shows nothing here, so it has no chip.
        compose.onNodeWithText(CandlePage.ALL).assertExists()
        compose.onNodeWithText("NIFTY").assertExists()
        compose.onNodeWithText("SENSEX").assertDoesNotExist()
        compose.onNodeWithText("BANKNIFTY").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No notable candle read on BANKNIFTY yet.").assertExists()
        compose.onAllNodesWithText("NIFTY 5m 19 Oct", substring = true).assertCountEquals(0)
        scrollTo("candle-lessons")
        compose.onNodeWithText("the banknifty lesson").assertExists()
        compose.onNodeWithText("the banknifty note").assertExists()
        compose.onAllNodesWithText("the nifty lesson").assertCountEquals(0)
        compose.onAllNodesWithText("never counted as a lesson", substring = true).onFirst().assertExists()
        // Back to All: everything again.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("candle-chips"))
        compose.onNodeWithText(CandlePage.ALL).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("NIFTY 5m 19 Oct", substring = true).onFirst().assertExists()
        scrollTo("candle-lessons")
        compose.onNodeWithText("the nifty lesson").assertExists()
    }

    @Test fun aPickedInstrumentThatLeavesThePageFallsBackToAll() {
        val a = agent()
        val view = androidx.compose.runtime.mutableStateOf(CandleAgentHost.View(status = a.status(), latest = a.latest(),
            lessons = listOf(CandlePage.Line("SENSEX", "the sensex lesson"))))
        compose.setContent { IraAlgoTheme("light") { CandleAgentContent(view.value, nowMs = ms(day.atTime(14, 9)), open = true) } }
        compose.onNodeWithText("SENSEX").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No notable candle read on SENSEX yet.").assertExists()
        // A memory trim lets the agent go: nothing about SENSEX is left, so the page lists all again (no dead filter).
        view.value = CandleAgentHost.View(status = a.status(), latest = a.latest())
        compose.waitForIdle()
        compose.onNodeWithText("SENSEX").assertDoesNotExist()
        compose.onAllNodesWithText("NIFTY 5m 19 Oct", substring = true).onFirst().assertExists()
    }

    @Test fun withNothingToFilterThereAreNoChips() {
        compose.setContent { IraAlgoTheme("light") { CandleAgentContent(CandleAgentHost.View(), nowMs = ms(day.atTime(10, 0)), open = true) } }
        compose.onNodeWithText(CandlePage.ALL).assertDoesNotExist()
    }

    @Test fun theCandleAgentsWhatsNewChangeOpensResearchCandles() {
        val e = com.optionslab.ira.WhatsNew.ENTRIES.first { it.id == "2026-10-20-candle-agent" }
        val went = ArrayList<String>()
        compose.setContent { IraAlgoTheme("light") { WhatsNewCardContent(listOf(e), onGotIt = {}, onGo = { went += it }) } }
        compose.onNodeWithText("${e.title} ›").performClick()
        org.junit.Assert.assertEquals(listOf("candles"), went)
        org.junit.Assert.assertEquals(com.optionslab.app.ui.NavState(tab = com.optionslab.app.ui.Tab.LAB, labPage = "candles"),
            com.optionslab.app.ui.NavState().home(went.single()))
    }
}
