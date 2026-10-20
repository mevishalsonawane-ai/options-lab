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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @After fun down() { CandleAgentHost.resetForTest() }

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
}
