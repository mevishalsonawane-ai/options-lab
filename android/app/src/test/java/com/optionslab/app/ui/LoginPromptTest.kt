package com.optionslab.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.optionslab.app.testing.Background
import com.optionslab.app.testing.BrokerArea
import com.optionslab.app.testing.RobolectricTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A linked account whose Zerodha session has expired (every morning) is asked to log in again, by itself. */
class LoginPromptTest : RobolectricTest() {
    private val store = androidx.lifecycle.ViewModelStore()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun on() { LoginPrompt.enabled = true }
    @After fun off() { LoginPrompt.enabled = false; store.clear() }

    private fun settle(cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < until) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(20)
        }
    }

    @Test fun anExpiredSessionOpensTheLoginPrompt() {
        Background.linkZerodha()
        val m = BrokerArea.model(app, store)
        m.promptLoginIfExpired()
        settle { m.askLoginPin.value }
        assertTrue("the PIN / fingerprint step before Zerodha's login page opens by itself", m.askLoginPin.value)
    }

    @Test fun cancelledItWaitsButZerodhaEndingTheSessionAsksAgain() {
        Background.linkZerodha()
        val m = BrokerArea.model(app, store)
        m.promptLoginIfExpired(); settle { m.askLoginPin.value }
        m.askLoginPin.value = false                                  // Cancel
        m.promptLoginIfExpired(); Thread.sleep(300); settle { false }
        assertFalse("no nagging right after Cancel", m.askLoginPin.value)
        m.promptLoginIfExpired(force = true); settle { m.askLoginPin.value }
        assertTrue(m.askLoginPin.value)
    }

    @Test fun notLinkedNothingIsAsked() {
        val m = BrokerArea.model(app, store)
        m.promptLoginIfExpired(); Thread.sleep(300); settle { false }
        assertFalse(m.askLoginPin.value)
    }
}
