package com.optionslab.app.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.security.PinLock
import com.optionslab.app.testing.has
import com.optionslab.app.testing.waitForText
import com.optionslab.app.ui.theme.IraAlgoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * The rest of the lock screen (LockScreenTest has setup and the basic unlock): the fingerprint
 * button, notices, the lockout countdown with the pad disabled, the erase after too many wrong PINs,
 * and a pad that stays shut once unlocked. Uses the real PinLock where the lockout state matters.
 */
@RunWith(AndroidJUnit4::class)
class LockScreenMoreTest {
    @get:Rule val compose = createComposeRule()
    private val pin = "246813"

    private fun type(digits: String) = digits.forEach { compose.onNodeWithText(it.toString()).performClick() }

    private fun show(setup: Boolean = false, bio: String? = null, notice: String? = null, onBio: () -> Unit = {},
                     onPin: (CharArray) -> PinLock.Result = { PinLock.Result.Ok }) = compose.setContent {
        IraAlgoTheme("light") { LockScreen(setup = setup, biometricLabel = bio, onBiometric = onBio, onPin = onPin, onCreate = { null }, notice = notice, calm = true) }
    }

    @Test fun theFingerprintButtonAsksAgain() {
        val asked = AtomicInteger()
        show(bio = "Use fingerprint", onBio = { asked.incrementAndGet() })
        compose.waitForIdle()
        val before = asked.get()
        compose.onNodeWithText("Use fingerprint").performClick()
        assertTrue(asked.get() >= before + 1)
        // The PIN pad still works alongside it.
        compose.onNodeWithText("1").assertIsEnabled()
    }

    @Test fun noFingerprintWhileChoosingAPin() {
        val asked = AtomicInteger()
        show(setup = true, bio = "Use fingerprint", onBio = { asked.incrementAndGet() })
        compose.waitForIdle()
        assertFalse(compose.has("Use fingerprint"))
        assertEquals("never prompted during setup", 0, asked.get())
    }

    @Test fun aNoticeIsShown() {
        show(notice = "No fingerprint is set up on this phone any more; unlock with your PIN.")
        compose.waitForText("No fingerprint is set up on this phone any more; unlock with your PIN.")
    }

    @Test fun whileLockedOutThePadIsShutAndCountsDown() {
        PinLock.setPin(pin.toCharArray())
        repeat(5) { PinLock.verify("000000".toCharArray(), false) }
        assertEquals(30, PinLock.lockoutSecondsLeft())
        show(onPin = { PinLock.verify(it, false) })
        compose.waitForText("Too many attempts. Try again in 30 s.")
        compose.onNodeWithText("1").assertIsNotEnabled()
        compose.onNodeWithText("⌫").assertIsNotEnabled()
        // Time passes: the countdown ends and the pad opens again.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        compose.mainClock.advanceTimeBy(1_100)
        compose.waitUntil(5_000) { compose.mainClock.advanceTimeBy(1_000); !compose.has("Try again in", substring = true) }
        compose.onNodeWithText("1").assertIsEnabled()
        type(pin)
        compose.waitUntil(10_000) { PinLock.failures() == 0 }
    }

    @Test fun theLastWrongPinErasesAndSaysSo() {
        show(onPin = { PinLock.Result.Wiped })
        type("2468"); compose.onNodeWithText("✓").performClick()
        compose.waitForText("Too many attempts: the vault has been erased.")
    }

    @Test fun aWrongPinWithNoFreeAttemptsLeftSaysOnlyThat() {
        show(onPin = { PinLock.Result.Wrong(0) })
        type("2468"); compose.onNodeWithText("✓").performClick()
        compose.waitForText("Not the right PIN.")
    }

    @Test fun onceUnlockedThePadStaysShut() {
        val tried = AtomicInteger()
        show(onPin = { tried.incrementAndGet(); PinLock.Result.Ok })
        type("2468"); compose.onNodeWithText("✓").performClick()
        compose.waitUntil(5_000) { tried.get() == 1 }
        compose.waitForIdle()
        compose.onNodeWithText("1").assertIsNotEnabled()
        assertEquals(1, tried.get())
    }

    @Test fun anEmptyPinIsNeverSubmitted() {
        val tried = AtomicInteger()
        show(onPin = { tried.incrementAndGet(); PinLock.Result.Wrong(4) })
        compose.onNodeWithText("✓").performClick()
        type("1"); compose.onNodeWithText("⌫").performClick()
        compose.onNodeWithText("✓").performClick()
        compose.waitForIdle()
        assertEquals(0, tried.get())
    }

    @Test fun theNoticeCanChangeWhileShown() {
        val notice = mutableStateOf<String?>(null)
        compose.setContent {
            IraAlgoTheme("dark") { LockScreen(setup = false, biometricLabel = null, onBiometric = {}, onPin = { PinLock.Result.Ok }, onCreate = { null }, notice = notice.value, calm = false) }
        }
        compose.waitForIdle()
        assertFalse(compose.has("Fingerprint: too many attempts (test)"))
        notice.value = "Fingerprint: too many attempts (test)"
        compose.waitForText("Fingerprint: too many attempts (test)")
        assertTrue(compose.has("Enter your PIN"))
    }
}
