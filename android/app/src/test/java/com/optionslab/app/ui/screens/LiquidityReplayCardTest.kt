package com.optionslab.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.OrbArms
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.IST
import com.optionslab.engine.orb.Bar
import com.optionslab.ira.LiquidityReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** A closed Liquidity 15+5 paper trade's replay: the card (chart, words, lesson) and the detail's tap that opens it. */
@RunWith(AndroidJUnit4::class)
class LiquidityReplayCardTest {
    @get:Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    @get:Rule val compose = createComposeRule()

    private val today: LocalDate = LocalDate.now(IST)
    private val bar = today.atTime(10, 40)
    private val closed = OrbArms.Position("liquidity5", "BANKNIFTY-TEST-52000CE", "CE", 30, 210.0, bar.plusMinutes(6), bar, "E1", "S1", 178.5,
        exit = 250.0, exitTime = bar.plusMinutes(40), why = "next_liquidity", charges = 42.0, level = 52_010.0, target = 52_060.0,
        peak = 262.0, lot = 30, low = 204.0)

    private val index: List<Bar> = (0 until 80).map { i ->
        val c = 52_000.0 + i * 0.75
        Bar(today.atTime(10, 20).plusMinutes(i.toLong()), c - 1, c + 2, c - 2, c)
    }
    private val option: List<Bar> = (0 until 60).map { i ->
        val c = 205.0 + i
        Bar(today.atTime(10, 35).plusMinutes(i.toLong()), c, c + 1, c - 1, c)
    }

    private fun replayOf(p: OrbArms.Position, withBars: Boolean = true): LiquidityReplay.Replay =
        LiquidityReplay.of(com.optionslab.app.ira.IraBots.tradeOf(p), 52_000.0, if (withBars) index else emptyList(), if (withBars) option else emptyList())!!

    private fun shown(text: String, sub: Boolean = false) = compose.onAllNodesWithText(text, substring = sub).fetchSemanticsNodes().isNotEmpty()
    private fun tap(text: String) { compose.onAllNodesWithText(text, substring = true).onFirst().areaCClick(); compose.waitForIdle() }

    @Test fun theCardShowsTheChartTheTradeInWordsAndItsLesson() {
        val r = replayOf(closed)
        var closedIt = false
        compose.setContent { IraAlgoTheme("light") { LiquidityReplayCard(r, null) { closedIt = true } } }
        compose.waitForIdle()
        assertTrue(shown(keepNumbersWhole("Replay · BANKNIFTY 5-min CE")))
        assertTrue(compose.onAllNodesWithContentDescription("Trade replay chart", substring = true).fetchSemanticsNodes().isNotEmpty())
        // Every line, its figures kept whole: the result per lot and in all, the hold, the reason, best and worst, the levels.
        r.lines.forEach { assertTrue(it, shown(keepNumbersWhole(it))) }
        assertTrue(r.lines.contains("Net after charges +₹1,158 · +₹1,158 a lot · charges ₹42"))
        assertTrue(r.lines.contains("Entry 210.00 at 10:46 → exit 250.00 at 11:20 · held 34 min"))
        assertTrue(r.lines.contains("Exit reason: Target: the index reached the next level, 52,060"))
        assertTrue(r.lines.any { it.startsWith("Best premium while held: 262.00 (") && it.contains(" · worst: 204.00 (") })
        // The research lesson (BotTrades.lesson, as Jarvis says it).
        val lesson = com.optionslab.ira.BotTrades.lesson(com.optionslab.app.ira.IraBots.tradeOf(closed))!!.text
        assertTrue(shown(keepNumbersWhole("Against the research: $lesson")))
        assertTrue(shown(keepNumbersWhole(legend(r))))
        assertTrue(shown("A replay of a closed paper trade: it reads the candles and changes nothing."))
        tap("Close")
        assertTrue(closedIt)
    }

    @Test fun whatIsNotRecordedIsSaid() {
        val r = replayOf(closed.copy(peak = null, low = null), withBars = false)
        compose.setContent { IraAlgoTheme("light") { LiquidityReplayCard(r, null) {} } }
        compose.waitForIdle()
        assertTrue(shown(keepNumbersWhole("Best premium while held: not recorded · worst: not recorded")))
        assertTrue(shown(keepNumbersWhole("Premium path: not on the phone (the fill and the sale only)")))
        assertTrue(shown(keepNumbersWhole(r.lines.first { it.startsWith("No index candles") })))
        assertFalse(legend(r).contains("best"))
    }

    @Test fun aTapOnAClosedPaperTradeInTheDetailOpensItsReplay() {
        val asked = java.util.concurrent.CopyOnWriteArrayList<OrbArms.Position>()
        val view = StrategyFakes.orbView(armed = true)
        compose.setContent {
            IraAlgoTheme("light") {
                Column { OrbRowsContent(view, false, RecordingStrategyActions(), StrategyFakes.reauthWhy, replay = { p -> asked += p; replayOf(p) }) }
            }
        }
        compose.waitForIdle()
        tap("ARMED · PAPER · AUTO")
        assertTrue(shown("Arms · today"))
        assertTrue(shown("Tap a closed trade to replay it."))
        tap("paper BUY CE ×30 @ 210.00 → 250.00")
        compose.waitUntil(15_000) { shown(keepNumbersWhole("Replay · BANKNIFTY 5-min CE")) }
        assertEquals(1, asked.size)
        assertEquals("next_liquidity", asked.single().why)
        assertTrue(shown(keepNumbersWhole("Net after charges +₹1,158 · +₹1,158 a lot · charges ₹42")))
    }

    @Test fun aLiveTradeHasNoReplay() {
        compose.setContent {
            IraAlgoTheme("light") {
                Column { OrbRowsContent(StrategyFakes.orbView(armed = true, live = true), true, RecordingStrategyActions(), StrategyFakes.reauthWhy, replay = { null }) }
            }
        }
        compose.waitForIdle()
        tap("ARMED · LIVE")
        assertTrue(shown("Arms · today"))
        assertFalse(shown("Tap a closed trade to replay it."))
    }

    @Test fun aReadThatFindsNothingSaysSo() {
        compose.setContent { IraAlgoTheme("light") { LiquidityReplayDialog(closed, { null }) {} } }
        compose.waitUntil(5_000) { shown("No replay for this trade: its candles could not be read.") }
        assertTrue(shown("Replay"))
    }
}
