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
import com.optionslab.app.data.DayOco
import com.optionslab.app.data.PayoutWatch
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 21 Oct, enhancement round 4: Bot settings' "Stop and target as one GTT" (idea 1) and "Payouts and the drawdown" (idea 3) cards:
 * both off by default and saying so; their switches tap through; the payout card's offer shows only when given.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class EnhanceRound4CardsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val marketClock = com.optionslab.app.testing.MarketClockRule()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    @Test fun theDayOcoCardIsOffByDefaultSaysTheTradeOffAndItsTapAsksToSwitchOn() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val taps = ArrayList<Boolean>()
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            DayOcoCardContent(DayOco.View(on = false), null) { taps += it }
        } } }
        compose.waitForIdle()
        assertTrue(shows("Stop and target as one GTT in the day"))
        assertTrue(shows("Off: two orders in the day (as before)"))
        assertTrue(shows("can miss in a sharp gap", sub = true))
        assertTrue(shows("Switching it on takes your PIN", sub = true))
        compose.onNodeWithText("Stop and target as one GTT in the day").performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), taps)
    }

    @Test fun theDayOcoCardOnSaysWhatItDoes() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            DayOcoCardContent(DayOco.View(on = true), "On: done") {}
        } } }
        compose.waitForIdle()
        assertTrue(shows("On: one GTT, all day and overnight"))
        assertTrue(shows("On: done"))
    }

    @Test fun thePayoutCardIsInShadowByDefaultWithNoOffer() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val taps = ArrayList<Boolean>()
        var lowered = 0
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            PayoutDrawdownCardContent(PayoutWatch.View(on = false, line = "Payout-aware drawdown: shadow (logged only); payouts kept 1"), null,
                onSwitch = { taps += it }, onLower = { lowered++ })
        } } }
        compose.waitForIdle()
        assertTrue(shows("Count payouts in the drawdown"))
        assertTrue(shows("Off: only logs what it would say"))
        assertTrue(shows("It never clears the kill switch", sub = true))
        assertTrue(shows("payouts kept 1", sub = true))
        assertFalse("no offer in shadow", shows("Lower the peak by", sub = true))
        compose.onNodeWithText("Count payouts in the drawdown").performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), taps)
        assertEquals(0, lowered)
    }

    @Test fun thePayoutCardOffersToLowerThePeakOnlyWhenGiven() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        var lowered = 0
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            PayoutDrawdownCardContent(PayoutWatch.View(on = true, line = "Payout-aware drawdown: on", offer = 100_000.0), null,
                onSwitch = {}, onLower = { lowered++ })
        } } }
        compose.waitForIdle()
        assertTrue(shows("On: said beside the refusal, and offered to you"))
        assertTrue(shows(payoutLowerLabel(100_000.0)))
        assertEquals("Lower the peak by Rs 100,000 (PIN)", payoutLowerLabel(100_000.0))
        compose.onNodeWithText(payoutLowerLabel(100_000.0)).performClick()
        compose.waitForIdle()
        assertEquals(1, lowered)
    }
}
