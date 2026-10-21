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
import com.optionslab.app.data.AccountStopHost
import com.optionslab.app.ui.theme.IraAlgoTheme
import com.optionslab.ira.LiquidityFreeze
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 20 Oct, enhancement round 1: the Strategy lab's no-twin row (idea 3) and Bot settings' account stop rule card (idea 4). */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2400dp")
@RunWith(AndroidJUnit4::class)
class EnhanceRound1CardsTest {
    // 21 Oct, enhancement round 4: the market clock at noon IST on a trading day unless the test sets its own.
    @get:org.junit.Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    @Test fun theNoTwinRowSaysWhatItDoesAndSwitchesOffInOneTap() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val set = ArrayList<Boolean>()
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            LiquidityNoTwinRow(LiquidityFreeze.State(), "Liquidity no-twin rule (on for paper; live in shadow): today 1 would-be trade on paper") { set += it }
        } } }
        compose.waitForIdle()
        assertTrue(shows("No-twin rule on (paper)", sub = true))
        assertTrue(shows("Live only logs it", sub = true))
        assertTrue(shows("today 1 would-be trade on paper", sub = true))
        compose.onNodeWithText(NO_TWIN_OFF).performClick()
        compose.waitForIdle()
        assertEquals("one tap off, no PIN (paper only)", listOf(false), set)
    }

    @Test fun theNoTwinRowWhenOffTurnsBackOnAndWaitsForItsCount() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val set = ArrayList<Boolean>()
        compose.setContent { IraAlgoTheme("light") { Column { LiquidityNoTwinRow(LiquidityFreeze.State(noTwin = false), null) { set += it } } } }
        compose.waitForIdle()
        assertTrue(shows("No-twin rule off", sub = true))
        compose.onNodeWithText(NO_TWIN_ON).performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), set)
    }

    @Test fun theAccountStopCardIsInShadowByDefaultWithNoRestart() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        val set = ArrayList<Boolean>()
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            AccountStopCardContent(AccountStopHost.View(on = false, line = "Account stop rule (in shadow: logged only): no Zerodha day recorded yet.", reached = false),
                null, onSwitch = { set += it }, onRestart = {})
        } } }
        compose.waitForIdle()
        assertTrue(shows("Off: live only logs what it would refuse"))
        assertTrue(shows("Your own orders and every exit always go", sub = true))
        assertFalse("nothing to restart", shows(ACCOUNT_STOP_RESTART))
        compose.onNodeWithText("Refuse the bots' live entries once reached").performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), set)
    }

    @Test fun reachedItOffersTheRestartWithThePin() {
        org.junit.Assume.assumeFalse(com.optionslab.app.BuildConfig.GOLD)
        var asked = 0
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) {
            AccountStopCardContent(AccountStopHost.View(on = true, line = "Account stop rule (on): REACHED on 2026-10-21.", reached = true),
                "said back", onSwitch = {}, onRestart = { asked++ })
        } } }
        compose.waitForIdle()
        assertTrue(shows("On: refused once reached"))
        assertTrue(shows("REACHED on 2026-10-21", sub = true))
        assertTrue(shows("said back"))
        compose.onNodeWithText(ACCOUNT_STOP_RESTART).performClick()
        compose.waitForIdle()
        assertEquals(1, asked)
    }
}
