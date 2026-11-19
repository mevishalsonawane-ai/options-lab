package com.optionslab.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.data.AccountStopHost
import com.optionslab.app.data.BossRiskHost
import com.optionslab.app.data.ExposureCapHost
import com.optionslab.app.data.PortfolioHost
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 19 Nov 2026 (audit batch 2: live risk rules' PIN): Bot settings' live risk rules - the portfolio guard, the same-direction cap's
 * live switch, the account stop rule and the live weekly loss stop / 2% risk cap / opposite bets - ask the PIN before going off (or
 * back to shadow) when the guard's rule asks it; Cancel keeps them on; on is one tap; with no PIN to ask, off goes at once.
 */
@org.robolectric.annotation.Config(qualifiers = "w411dp-h3200dp")
@RunWith(AndroidJUnit4::class)
class LiveRiskPinCardsTest {
    @get:Rule val marketClock = com.optionslab.app.testing.MarketClockRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun up() { reset() }
    @After fun down() { reset() }

    private fun reset() {
        PortfolioHost.resetForTest(); ExposureCapHost.resetForTest(paperOn = false); AccountStopHost.resetForTest(); BossRiskHost.resetForTest(paperOn = false)
    }

    private fun shows(t: String, sub: Boolean = false) = compose.onAllNodesWithText(t, substring = sub).fetchSemanticsNodes().isNotEmpty()

    /** The test's PIN step: says it was asked, and offers the right PIN or Cancel. */
    private val fakePin: PinStep = { ok, cancel ->
        Text("PIN asked")
        androidx.compose.material3.TextButton(onClick = ok) { Text("PIN right") }
        androidx.compose.material3.TextButton(onClick = cancel) { Text("PIN cancel") }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { IraAlgoTheme("light") { Column(Modifier.verticalScroll(rememberScrollState())) { content() } } }
        compose.waitForIdle()
    }

    private fun tap(t: String) { compose.onNodeWithText(t).performScrollTo().performClick(); compose.waitForIdle() }

    /** The card's own first read (the settings and the trades) landed, so it can never read the switch back over a tap. */
    private fun portfolioRead() = compose.waitUntil(10_000) { PortfolioHost.view.value.line.startsWith("Portfolio: too few") }

    @Test fun thePortfolioGuardAsksThePinToGoOffAndCancelKeepsItOn() {
        PortfolioHost.testOwners = { emptyMap() }
        show { PortfolioSettingCard(pinAsks = { true }, pin = fakePin) }
        portfolioRead()
        assertTrue(PortfolioHost.view.value.on)
        tap("Block correlated entries over the cap")
        assertTrue(shows("PIN asked"))
        tap("PIN cancel")
        assertFalse(shows("PIN asked"))
        assertTrue("cancelled: still on", PortfolioHost.view.value.on)
        tap("Block correlated entries over the cap")
        tap("PIN right")
        compose.waitUntil(5_000) { !PortfolioHost.view.value.on }
        // On again: one tap, no PIN.
        tap("Block correlated entries over the cap")
        compose.waitUntil(5_000) { PortfolioHost.view.value.on }
        assertFalse(shows("PIN asked"))
    }

    @Test fun withNoPinToAskThePortfolioGuardGoesOffAtOnce() {
        PortfolioHost.testOwners = { emptyMap() }
        show { PortfolioSettingCard(pinAsks = { false }, pin = fakePin) }
        portfolioRead()
        tap("Block correlated entries over the cap")
        compose.waitUntil(5_000) { !PortfolioHost.view.value.on }
        assertFalse(shows("PIN asked"))
    }

    @Test fun theSameDirectionCapsLiveSwitchAsksThePinOnlyToGoBackToShadow() {
        show { SameDirectionCapCard(pinAsks = { true }, pin = fakePin) }
        tap("Also refuse the bots' live entries")
        compose.waitUntil(5_000) { ExposureCapHost.view.value.liveOn }
        assertFalse("on never asks", shows("PIN asked"))
        tap("Also refuse the bots' live entries")
        assertTrue(shows("PIN asked"))
        assertTrue("not off before the PIN", ExposureCapHost.view.value.liveOn)
        tap("PIN right")
        compose.waitUntil(5_000) { !ExposureCapHost.view.value.liveOn }
    }

    @Test fun theAccountStopRuleAsksThePinOnlyToGoBackToShadow() {
        val switched = ArrayList<Pair<Boolean, Boolean>>()
        var on by androidx.compose.runtime.mutableStateOf(false)
        show {
            AccountStopSwitchFlow(AccountStopHost.View(on = on, line = "", reached = false), null, pinAsks = { true }, pin = fakePin,
                onSwitch = { o, p -> switched += o to p }, onRestart = {})
        }
        tap("Refuse the bots' live entries once reached")
        assertFalse("on never asks", shows("PIN asked"))
        assertEquals(listOf(true to false), switched)
        switched.clear()
        on = true
        compose.waitForIdle()
        tap("Refuse the bots' live entries once reached")
        assertTrue(shows("PIN asked"))
        assertTrue("nothing before the PIN", switched.isEmpty())
        tap("PIN cancel")
        assertTrue(switched.isEmpty())
        tap("Refuse the bots' live entries once reached")
        tap("PIN right")
        assertEquals(listOf(false to true), switched)
    }

    @Test fun eachLiveRiskRuleAsksThePinOnlyToGoBackToShadow() {
        val asked = ArrayList<String>()
        show {
            BossRiskCardFlow(loosenAsks = { true }) { why, ok, cancel ->
                asked += why
                fakePin(ok, cancel)
            }
        }
        val title = bossLiveTitle(BossRiskHost.Rule.WEEKLY)
        tap(title)
        compose.waitUntil(5_000) { BossRiskHost.view.value.live[BossRiskHost.Rule.WEEKLY] == true }
        assertFalse("on never asks", shows("PIN asked"))
        tap(title)
        assertTrue(shows("PIN asked"))
        assertTrue(asked.contains(LIVE_RULE_PIN))
        assertTrue("not in shadow before the PIN", BossRiskHost.view.value.live[BossRiskHost.Rule.WEEKLY] == true)
        tap("PIN right")
        compose.waitUntil(5_000) { BossRiskHost.view.value.live[BossRiskHost.Rule.WEEKLY] != true }
    }
}
