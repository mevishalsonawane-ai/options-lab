package com.optionslab.app.ui.screens

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.Market
import com.optionslab.app.data.TimeMachineHost
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.risk.TimeMachine
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The time machine's line under a ticket (22 Oct, [TimeMachineLine]): shown for the trade's order id once checked, nothing otherwise. */
@RunWith(AndroidJUnit4::class)
class TimeMachineLineTest {
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createComposeRule()
    private lateinit var storeBars: TimeMachineHost.Bars

    @Before fun up() {
        TimeMachineHost.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        TimeMachineHost.resetForTest(); storeBars = TimeMachineHost.bars
        val today = Market.today()
        val past = ArrayList<TimeMachine.Session>()
        var d = today.minusDays(1)
        while (past.size < 20) {
            if (d.dayOfWeek.value <= 5) past += TimeMachine.Session(d, (0 until 75).map { i -> val c = 50_000.0 + 20.0 * i; Bar(d.atTime(9, 15).plusMinutes(5L * i), c, c + 10, c - 10, c) })
            d = d.minusDays(1)
        }
        TimeMachineHost.bars = TimeMachineHost.Bars { _, _, _ -> past to "20 sessions" }
        TimeMachineHost.check(TimeMachineHost.Entry("orb", "ORB", "orb:orb:P77", "BANKNIFTY", 1, today.atTime(10, 35), 40.0, 40.0, premium = true, exitAt = OrbRules.SQUARE_OFF))
    }

    @After fun down() { TimeMachineHost.resetForTest(); TimeMachineHost.bars = storeBars }

    @Test fun theLineShowsForTheCheckedOrderAndNotForOthers() {
        compose.setContent { IraAlgoTheme("light") { androidx.compose.foundation.layout.Column { TimeMachineLine("P77"); TimeMachineLine("P78"); TimeMachineLine(null) } } }
        compose.onNodeWithTag("time-machine-line").assertTextContains("Time machine (edge): target first 100%", substring = true)
        assertTrue(compose.onAllNodesWithTag("time-machine-line").fetchSemanticsNodes().size == 1)
    }
}
