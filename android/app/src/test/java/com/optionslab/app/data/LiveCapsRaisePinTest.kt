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
 * 22 Nov 2026 (B6 review): raising the same-direction cap while its live switch is on, or the portfolio cap while the guard is on (it
 * blocks Zerodha entries too), loosens a live protection: refused without the PIN wherever the guard's rule asks it, done with it. A
 * lower number, or any number while the live rule is off, never asks.
 */
class LiveCapsRaisePinTest : RobolectricTest() {
    @Before fun up() { ExposureCapHost.resetForTest(paperOn = true); PortfolioHost.resetForTest() }

    @After fun down() {
        AutomationSupport.liveSettings(live = false)
        ExposureCapHost.resetForTest(paperOn = true); PortfolioHost.resetForTest()
    }

    private fun liveWithPin() { PinLock.setPin("482915".toCharArray()); AutomationSupport.liveSettings() }

    @Test fun aHigherSameDirectionCapWhileLiveIsOnTakesThePin() {
        ExposureCapHost.setLiveOn(true)
        liveWithPin()
        assertEquals(ExposureCapHost.PIN_RAISE, ExposureCapHost.setMax(3))
        assertEquals("unchanged", 2, ExposureCapHost.view.value.max)
        assertEquals("unchanged at the door too", 2, ExposureCapHost.paperMax())
        assertTrue(ExposureCapHost.setMax(3, pinConfirmed = true).startsWith("At most 3 "))
        assertEquals(3, ExposureCapHost.view.value.max)
        // Lower, or the same: never asks.
        assertTrue(ExposureCapHost.setMax(1).startsWith("At most 1 "))
        assertTrue(ExposureCapHost.setMax(1).startsWith("At most 1 "))
        assertEquals(1, ExposureCapHost.paperMax())
    }

    @Test fun withLiveInShadowOrNoPinTheSameDirectionCapRisesAtOnce() {
        liveWithPin()
        assertFalse(ExposureCapHost.view.value.liveOn)
        assertTrue("live in shadow: paper only", ExposureCapHost.setMax(5).startsWith("At most 5 "))
        AutomationSupport.liveSettings(live = false)
        ExposureCapHost.setLiveOn(true)
        ExposureCapHost.setMax(1)
        // Paper with no Zerodha keys: the guard's rule asks nothing.
        assertTrue(ExposureCapHost.setMax(4).startsWith("At most 4 "))
    }

    @Test fun aSavedLiveSwitchCountsAsOnEvenBeforeItIsReadIntoMemory() {
        com.optionslab.app.security.SecurePrefs.putAll(mapOf("g.sameDirCap.live" to true))
        liveWithPin()
        assertFalse("not read into memory yet", ExposureCapHost.view.value.liveOn)
        assertEquals(ExposureCapHost.PIN_RAISE, ExposureCapHost.setMax(4))
    }

    @Test fun aHigherPortfolioCapWhileTheGuardIsOnTakesThePin() {
        liveWithPin()
        assertEquals(PortfolioHost.PIN_RAISE, PortfolioHost.setCapPct(3.0))
        assertEquals("unchanged", 2.0, PortfolioHost.guard.config.capPct, 1e-9)
        assertTrue(PortfolioHost.setCapPct(3.0, pinConfirmed = true).contains("3.0% of capital"))
        assertEquals(3.0, PortfolioHost.guard.config.capPct, 1e-9)
        // Lower: at once.
        assertTrue(PortfolioHost.setCapPct(1.0).contains("1.0% of capital"))
        // The guard off: a higher cap never asks.
        PortfolioHost.setOn(false, pinConfirmed = true)
        assertTrue(PortfolioHost.setCapPct(5.0).contains("5.0% of capital"))
    }

    /**
     * A cap saved but not yet handed to the guard (its refresh has not run) counts too: above it is a raise, at or below it (and the one in
     * force) never asks.
     */
    @Test fun aSavedCapNotYetInTheGuardCountsAndALowerOneNeverAsks() {
        com.optionslab.app.security.SecurePrefs.putAll(mapOf("g.portfolioCapPct" to 1.0))
        liveWithPin()
        assertEquals("in force (memory) still the default", 2.0, PortfolioHost.guard.config.capPct, 1e-9)
        assertEquals("above the saved 1%", PortfolioHost.PIN_RAISE, PortfolioHost.setCapPct(1.5))
        assertTrue(PortfolioHost.setCapPct(1.0).contains("1.0% of capital"))
        assertTrue(PortfolioHost.setCapPct(0.5).contains("0.5% of capital"))
    }

    @Test fun onPaperWithNoZerodhaThePortfolioCapRisesAtOnce() {
        PinLock.setPin("482915".toCharArray())
        assertTrue(PortfolioHost.setCapPct(5.0).contains("5.0% of capital"))
        assertEquals(5.0, PortfolioHost.guard.config.capPct, 1e-9)
    }
}
