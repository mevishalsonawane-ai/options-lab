package com.optionslab.app.data

import com.optionslab.app.security.PinLock
import com.optionslab.app.testing.AutomationSupport
import com.optionslab.app.testing.RobolectricTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 19 Nov 2026 (audit batch 2: live risk rules' PIN): the live risk rules - the account stop rule, the same-direction cap's live
 * refusals, the weekly loss stop / 2% risk cap / opposite bets on live, and the portfolio guard - could be switched off (or put back
 * in shadow) with no PIN. Now off takes the PIN wherever the guard's own rule asks it ([com.optionslab.app.security.GuardPin]: a PIN
 * set, and Live or Zerodha set up); on never asks; with no PIN set there is nothing to ask.
 */
class LiveRiskRulesPinTest : RobolectricTest() {
    @Before fun up() {
        AccountStopHost.resetForTest()
        ExposureCapHost.resetForTest(paperOn = false)
        BossRiskHost.resetForTest(paperOn = false)
        PortfolioHost.resetForTest()
    }

    @After fun down() {
        AutomationSupport.liveSettings(live = false)
        AccountStopHost.resetForTest()
        ExposureCapHost.resetForTest(paperOn = false)
        BossRiskHost.resetForTest(paperOn = false)
        PortfolioHost.resetForTest()
    }

    private fun liveWithPin() { PinLock.setPin("482915".toCharArray()); AutomationSupport.liveSettings() }

    @Test fun inLiveWithAPinTheAccountStopRuleGoesBackToShadowOnlyWithThePin() {
        liveWithPin()
        assertTrue("on never asks", AccountStopHost.setOn(true).startsWith("Account stop rule on"))
        assertTrue(AccountStopHost.view.value.on)
        assertEquals(AccountStopHost.PIN_OFF, AccountStopHost.setOn(false))
        assertTrue("nothing changed without the PIN", AccountStopHost.view.value.on)
        assertTrue(AccountStopHost.setOn(false, pinConfirmed = true).contains("in shadow"))
        assertFalse(AccountStopHost.view.value.on)
        // Already in shadow: switching it off again changes nothing and never asks.
        assertTrue(AccountStopHost.setOn(false).contains("in shadow"))
    }

    @Test fun theSameDirectionCapsLiveSwitchGoesBackToShadowOnlyWithThePin() {
        liveWithPin()
        assertTrue(ExposureCapHost.setLiveOn(true).startsWith("Live: the bots' entries past the cap are refused"))
        assertEquals(ExposureCapHost.PIN_OFF, ExposureCapHost.setLiveOn(false))
        assertTrue(ExposureCapHost.view.value.liveOn)
        assertTrue(ExposureCapHost.setLiveOn(false, pinConfirmed = true).contains("in shadow"))
        assertFalse(ExposureCapHost.view.value.liveOn)
    }

    @Test fun eachLiveRiskRuleGoesBackToShadowOnlyWithThePin() {
        liveWithPin()
        for (r in listOf(BossRiskHost.Rule.WEEKLY, BossRiskHost.Rule.RISK, BossRiskHost.Rule.OPPOSITE)) {
            assertTrue(r.name, BossRiskHost.setLive(r, true).contains("on live"))
            assertEquals(r.name, BossRiskHost.PIN_LIVE_OFF, BossRiskHost.setLive(r, false))
            assertTrue(r.name, BossRiskHost.view.value.live[r] == true)
            assertTrue(r.name, BossRiskHost.setLive(r, false, pinConfirmed = true).contains("in shadow"))
            assertFalse(r.name, BossRiskHost.view.value.live[r] == true)
        }
        // A rule with no live switch still says so first (no PIN asked for nothing).
        assertEquals("That rule has no live switch.", BossRiskHost.setLive(BossRiskHost.Rule.EXPIRY, false))
    }

    @Test fun thePortfolioGuardSwitchesOffOnlyWithThePin() {
        liveWithPin()
        assertEquals(PortfolioHost.PIN_OFF, PortfolioHost.setOn(false))
        assertTrue("on by default, kept", PortfolioHost.view.value.on)
        assertEquals("Portfolio guard off: correlated arms may all open on one day.", PortfolioHost.setOn(false, pinConfirmed = true))
        assertFalse(PortfolioHost.view.value.on)
        assertEquals("on never asks", "Portfolio guard on.", PortfolioHost.setOn(true))
    }

    @Test fun onPaperWithZerodhaSetUpOffStillAsksThePin() {
        com.optionslab.app.testing.BrokerArea.saveKeys("482915")   // the PIN and the Zerodha keys; the app stays in Paper
        AccountStopHost.setOn(true)
        assertEquals(AccountStopHost.PIN_OFF, AccountStopHost.setOn(false))
        assertEquals(PortfolioHost.PIN_OFF, PortfolioHost.setOn(false))
    }

    @Test fun withNoPinSetThereIsNothingToAskAndOffGoesAtOnce() {
        AutomationSupport.liveSettings()
        AccountStopHost.setOn(true)
        assertTrue(AccountStopHost.setOn(false).contains("in shadow"))
        ExposureCapHost.setLiveOn(true)
        assertTrue(ExposureCapHost.setLiveOn(false).contains("in shadow"))
        BossRiskHost.setLive(BossRiskHost.Rule.WEEKLY, true)
        assertTrue(BossRiskHost.setLive(BossRiskHost.Rule.WEEKLY, false).contains("in shadow"))
        assertEquals("Portfolio guard off: correlated arms may all open on one day.", PortfolioHost.setOn(false))
    }
}
