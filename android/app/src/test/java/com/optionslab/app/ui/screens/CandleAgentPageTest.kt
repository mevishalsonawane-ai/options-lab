package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        compose.onNodeWithText("reading candles", substring = true).assertExists()
        // The newest notable candle: tap its name for the whole explanation.
        compose.onNodeWithText("NIFTY 5m 19 Oct 14:00-14:05", substring = true).performClick()
        compose.onNodeWithText("Green because", substring = true).assertExists()
        compose.onNodeWithText("A read, not a signal", substring = true).assertExists()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithTag("candle-table").performScrollTo()
        compose.onNodeWithText("big tail (long lower wick)", substring = true).assertExists()
        compose.onNodeWithText("agrees with the research", substring = true).assertExists()
        compose.onNodeWithTag("candle-lessons").performScrollTo()
        compose.onNodeWithText("Big tail (long lower wick) on NIFTY 15m: agrees with the research (200 counted)", substring = true).assertExists()
        compose.onNodeWithTag("candle-talk").performScrollTo()
        compose.onNodeWithText("heard the market brain: busy day", substring = true).assertExists()
        compose.onNodeWithText("shared the findings bus", substring = true).assertExists()
        compose.onNodeWithText("never count as a vote", substring = true).assertExists()
    }

    @Test fun beforeItsFirstBatchAndAfterBeingLetGoItSaysSo() {
        compose.setContent { IraAlgoTheme("light") { CandleAgentContent(CandleAgentHost.View()) } }
        compose.onNodeWithText("Waiting for its first batch", substring = true).assertExists()
        compose.onNodeWithText("No notable candle read yet.").assertExists()
        compose.onNodeWithTag("candle-talk").performScrollTo()
        compose.onNodeWithText("Nothing shared or heard yet.").assertExists()
    }

    @Test fun afterAMemoryTrimThePageSaysPositionsComeFirst() {
        compose.setContent { IraAlgoTheme("light") { CandleAgentContent(CandleAgentHost.View(released = true)) } }
        compose.onNodeWithText("open positions come first", substring = true).assertExists()
    }
}
