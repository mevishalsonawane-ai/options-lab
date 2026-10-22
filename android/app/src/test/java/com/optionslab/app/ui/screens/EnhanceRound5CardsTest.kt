package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.DayOco
import com.optionslab.app.data.PayoutWatch
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 21 Oct, enhancement round 5: the "Stop and target as one GTT" card shows today's and the last week's counts (idea 1); the
 * "Payouts and the drawdown" card says a payout counts once the next opening balance shows it left, with the check's line (idea 5).
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class EnhanceRound5CardsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val marketClock = com.optionslab.app.testing.MarketClockRule()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    @Test fun theDayOcoCardShowsTheCounts() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val line = "Stop and target as one GTT in the day: on; today placed 1, trailed 2; last 7 days placed 3, trailed 2, fired 1 (3 days with any)"
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            DayOcoCardContent(DayOco.View(on = true, line = line), null) {}
        } } }
        compose.waitForIdle()
        assertTrue(shows(line))
        assertTrue(shows("On: one GTT, all day and overnight"))
    }

    @Test fun theDayOcoCardWithNoLineShowsNoEmptyNote() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            DayOcoCardContent(DayOco.View(on = false), null) {}
        } } }
        compose.waitForIdle()
        assertFalse(shows("last 7 days", sub = true))
        assertTrue(shows("Off: two orders in the day (as before)"))
    }

    @Test fun thePayoutCardSaysTheNextOpeningBalanceCheckAndOffersNothingWhileItWaits() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val line = "Payout-aware drawdown: on; payouts kept 1 (left, by the next opening balance: confirmed 0, not seen 1, not read yet 0)"
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            PayoutDrawdownCardContent(PayoutWatch.View(on = true, line = line, offer = null), null, onSwitch = {}, onLower = {})
        } } }
        compose.waitForIdle()
        assertTrue(shows("once the next day's opening balance shows the money left", sub = true))
        assertTrue(shows("confirmed 0, not seen 1", sub = true))
        assertFalse(shows("Lower the peak by", sub = true))
    }
}
