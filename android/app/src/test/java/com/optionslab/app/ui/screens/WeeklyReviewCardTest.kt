package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.testing.DeviceConfig
import com.optionslab.app.testing.ScreenTest
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.engine.orb.ShadowRules
import com.optionslab.ira.ForwardCheck
import com.optionslab.ira.Market
import com.optionslab.ira.WeeklyReview
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** Two kept weeks: the newest with every part filled (long lines, a CUSUM warning), the one before it plain. */
internal object WeeklyFixtures {
    private val mon: LocalDate = LocalDate.of(2026, 10, 5)
    private fun t(g: WeeklyReview.Group, name: String, d: LocalDate, net: Double) = WeeklyReview.Trade(g, name, d, net, 45.0)
    private fun ft(i: Int, x: Double) = ForwardCheck.Trade(mon.plusDays(1 + i / 10L), x)

    private fun input(monday: LocalDate) = WeeklyReview.Input(
        monday, monday.plusDays(4), monday.plusDays(4).atTime(15, 47),
        trades = listOf(t(WeeklyReview.Group.LIQUIDITY, "BankNifty 15-min", monday, 12_400.0), t(WeeklyReview.Group.LIQUIDITY, "FinNifty 5-min", monday.plusDays(1), -3_250.0),
            t(WeeklyReview.Group.SOLO, "Solo (midday)", monday.plusDays(2), -1_100.0), t(WeeklyReview.Group.PINE, "My EMA cross", monday.plusDays(3), 640.0),
            t(WeeklyReview.Group.MANUAL, "Manual", monday.plusDays(4), -220.0), t(WeeklyReview.Group.LIQUIDITY, "BankNifty 15-min", monday.minusDays(3), -800.0)),
        heroArmed = true,
        forward = listOf(WeeklyReview.Forward("Liquidity 15+5", ForwardCheck.check(ForwardCheck.LIQUIDITY, (0 until 24).map { ft(it, if (it % 2 == 0) 1_400.0 else -1_000.0) })),
            WeeklyReview.Forward("Solo (midday)", ForwardCheck.check(ForwardCheck.SOLO, (0 until 12).map { ft(it, -3_000.0) }))),
        shadows = listOf(WeeklyReview.Shadow("OP10", "ORB", 3, 900.0, ShadowRules.Summary(41, -1_200.0, 4_000.0, 5_200.0)),
            WeeklyReview.Shadow("V43", "ORB / ORB Fresh", 2, -1_500.0, ShadowRules.Summary(63, 6_000.0, 20_000.0, 14_000.0))),
        liquidity = null,
        discipline = WeeklyReview.Discipline(2, 1, 1, 0, 0, 3, emptyList()),
        market = WeeklyReview.MarketInput(mapOf(Market.NIFTY to listOf(monday.minusDays(3) to 25_000.0, monday.plusDays(4) to 25_250.0),
            Market.BANKNIFTY to listOf(monday.minusDays(3) to 55_000.0, monday.plusDays(4) to 54_450.0)), null, null, 4, 5),
        nextWeek = WeeklyReview.nextWeek(monday, { it.dayOfWeek.value <= 5 }, { null }, mapOf(Market.NIFTY to listOf(monday.plusDays(8))), emptyList(), true),
    )

    val newest: WeeklyReview.Review = WeeklyReview.build(input(mon))
    val older: WeeklyReview.Review = WeeklyReview.build(input(mon.minusWeeks(1)).copy(trades = emptyList(), forward = emptyList()))
    val all = listOf(newest, older)
}

@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class WeeklyReviewCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    @Test fun newestFirstTheWholeReviewAndOlderWeeks() {
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) { WeeklyReviewCard(WeeklyFixtures.all) } } }
        compose.waitForIdle()
        assertTrue(shows("Jarvis's weekly review", sub = true))
        assertTrue(shows("Week of 5 Oct"))
        assertTrue(shows("1 of 2"))
        assertTrue(shows("Live vs backtest: Liquidity 15+5 in line", sub = true))
        // The parts open on "The whole review".
        assertTrue(!shows("Money (paper)"))
        compose.onNodeWithText("The whole review").performClick(); compose.waitForIdle()
        listOf("Money (paper)", "Live vs backtest", "Shadows", "Liquidity candidates (a)-(f)", "Discipline", "Market", "Next week", "What I'd watch")
            .forEach { assertTrue(it, shows(it)) }
        assertTrue(shows("CUSUM warning - Solo (midday)", sub = true))
        // The week before.
        compose.onNodeWithText("‹ Older").performClick(); compose.waitForIdle()
        assertTrue(shows("Week of 28 Sep")); assertTrue(shows("2 of 2"))
        assertTrue(shows("no paper trades closed", sub = true))
    }

    @Test fun noReviewYet() {
        compose.setContent { IraAlgoTheme("dark") { WeeklyReviewCard(emptyList()) } }
        compose.waitForIdle()
        assertTrue(shows("No weekly review yet", sub = true))
    }
}

/** The card, shut and open, on six set-ups spanning the device matrix (font 2.0 and landscape among them): a screenshot each and the layout lint. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeeklyReviewCardLayoutTest(device: DeviceConfig) : ScreenTest(device) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = com.optionslab.app.testing.ResearchMatrix.six()
    }

    @Test fun weeklyReviewShut() = checkScreen("weekly-review") {
        Column(Modifier.verticalScroll(rememberScrollState())) { WeeklyReviewCard(WeeklyFixtures.all) }
    }

    @Test fun weeklyReviewOpen() = checkScreen("weekly-review-open") {
        Column(Modifier.verticalScroll(rememberScrollState())) { WeeklyReviewCard(WeeklyFixtures.all, openAtFirst = true) }
    }

    @Test fun weeklyReviewEmpty() = checkScreen("weekly-review-empty") { WeeklyReviewCard(emptyList()) }
}
