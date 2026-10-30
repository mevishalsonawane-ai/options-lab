package com.optionslab.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.ui.AppModel
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.DayStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 30 Oct (Boss: "Make stop bot different for index and comodities", then "Or remove commodities from stop bot button"): the MCX
 * card's own, smaller "Stop commodities" control - its state and reason, the stop with or without the sale, "Start again", and a
 * loss stop that is never started from here.
 */
@RunWith(AndroidJUnit4::class)
class McxStopBarTest {
    @get:Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val watchdog = com.optionslab.app.testing.AutomationWatchdog()
    @get:Rule val compose = createComposeRule()
    private val calls = ArrayList<String>()

    private fun bar(st: AppModel.McxStopState) = compose.setContent {
        IraAlgoTheme("light") { McxStopBar(st, onStop = { sell -> calls += "stop sell=$sell" }, onStart = { calls += "start" }) }
    }

    private fun tap(text: String) { compose.onAllNodesWithText(text).onFirst().areaCClick(); compose.waitForIdle() }
    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun runningItOffersStopCommoditiesAndSellsByDefault() {
        bar(AppModel.McxStopState())
        assertTrue(shown("Commodity bots: running"))
        assertTrue(shown(STOP_MCX))
        assertFalse(shown(START_MCX))
        tap(STOP_MCX)
        assertTrue(shown("Stop the commodity bots for today?"))
        assertTrue(shown("The index bots keep trading"))
        tap("Cancel")
        assertTrue("Cancel does nothing", calls.isEmpty())
        tap(STOP_MCX)
        // The dialog's own button (the bar's is the first).
        compose.onAllNodesWithText(STOP_MCX)[1].areaCClick(); compose.waitForIdle()
        assertEquals(listOf("stop sell=true"), calls)
    }

    @Test fun theSaleCanBeLeftOutSoHeldPositionsKeepTheirOwnExits() {
        bar(AppModel.McxStopState())
        tap(STOP_MCX)
        tap("Also sell what the MCX arms hold now (through their own exits)")
        compose.onAllNodesWithText(STOP_MCX)[1].areaCClick(); compose.waitForIdle()
        assertEquals(listOf("stop sell=false"), calls)
    }

    @Test fun stoppedItSaysWhoAndWhenAndStartsAgain() {
        bar(AppModel.McxStopState(kept = DayStop.Kept(DayStop.Why.BOSS, "10:42"), sells = true))
        assertTrue(shown("Commodity bots: stopped by you 10:42"))
        assertFalse(shown(STOP_MCX))
        tap(START_MCX)
        assertTrue(shown("Start the commodity bots again?"))
        tap("Start")
        assertEquals(listOf("start"), calls)
    }

    @Test fun aLossStopIsSaidAndNeverStartedFromHere() {
        bar(AppModel.McxStopState(kept = DayStop.Kept(DayStop.Why.BOSS, "10:42"), loss = true))
        assertTrue(shown("Commodity bots: stopped by the daily loss limit"))
        tap(START_MCX)
        assertTrue(shown("Stopped by the daily loss limit"))
        tap("OK")
        assertTrue("nothing started", calls.isEmpty())
    }

    @Test fun theStateIsStoppedOnEitherReason() {
        assertFalse(AppModel.McxStopState().stopped)
        assertTrue(AppModel.McxStopState(kept = DayStop.Kept(DayStop.Why.TILE)).stopped)
        assertTrue(AppModel.McxStopState(loss = true).stopped)
    }
}
