package com.optionslab.app.security

import android.os.SystemClock
import com.optionslab.app.testing.FakeAndroidKeyStore
import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** PIN verifier, lockout and wipe, and the PIN-sealed secret box. Test PINs only. */
class PinLockTest : RobolectricTest() {
    private val pin = "246813"
    private fun verify(p: String, wipe: Boolean = false) = PinLock.verify(p.toCharArray(), wipe)
    private fun wait(seconds: Long) = ShadowSystemClock.advanceBy(Duration.ofSeconds(seconds))

    @Test fun setAndVerify() {
        assertFalse(PinLock.isSet)
        PinLock.setPin(pin.toCharArray())
        assertTrue(PinLock.isSet)
        assertEquals(6, PinLock.length())
        assertEquals(PinLock.Result.Ok, verify(pin))
        assertEquals(0, PinLock.failures())
        // The PIN itself is stored nowhere.
        assertFalse(SecurePrefs.snapshot().values.any { it.toString().contains(pin) })
    }

    @Test fun weakOrShortPinsAreRefused() {
        for (bad in listOf("12345", "1234567", "111111")) {
            try { PinLock.setPin(bad.toCharArray()); throw AssertionError("$bad accepted") } catch (_: IllegalArgumentException) {}
        }
        assertFalse(PinLock.isSet)
    }

    @Test fun wrongPinsCountDownThenLockOutWithEscalation() {
        PinLock.setPin(pin.toCharArray())
        for (left in 4 downTo 1) assertEquals(PinLock.Result.Wrong(left), verify("000000"))
        assertEquals(PinLock.Result.LockedOut(30), verify("000000"))
        // While locked out even the right PIN is refused, and the attempt is not counted.
        assertTrue(verify(pin) is PinLock.Result.LockedOut)
        assertEquals(5, PinLock.failures())
        wait(31)
        assertEquals(0, PinLock.lockoutSecondsLeft())
        assertEquals(PinLock.Result.LockedOut(60), verify("000000"))
        wait(61)
        assertEquals(PinLock.Result.Ok, verify(pin))
        assertEquals(0, PinLock.failures())
        assertEquals(0, PinLock.lockoutSecondsLeft())
    }

    @Test fun anEmptyPinCostsNoAttempt() {
        PinLock.setPin(pin.toCharArray())
        assertEquals(PinLock.Result.Wrong(5), verify(""))
        assertEquals(0, PinLock.failures())
    }

    @Test fun tenFailuresWipeWhenTheOwnerChoseSo() {
        PinLock.setPin(pin.toCharArray())
        var last: PinLock.Result? = null
        repeat(PinLock.WIPE_AFTER) {
            PinLock.lockoutSecondsLeft().takeIf { it > 0 }?.let { s -> wait(s + 1) }
            last = verify("000000", wipe = true)
        }
        assertEquals(PinLock.Result.Wiped, last)
    }

    @Test fun withoutWipeTheLockoutIsCappedAtAnHour() {
        PinLock.setPin(pin.toCharArray())
        var last: PinLock.Result? = null
        repeat(14) {
            PinLock.lockoutSecondsLeft().takeIf { it > 0 }?.let { s -> wait(s + 1) }
            last = verify("000000", wipe = false)
        }
        assertEquals(PinLock.Result.LockedOut(3600), last)
    }

    @Test fun aKeystoreFaultIsNotAWrongPin() {
        PinLock.setPin(pin.toCharArray())
        FakeAndroidKeyStore.failing += "ol.pin.pepper.v1"
        assertEquals(PinLock.Result.LockedOut(5), verify(pin))
        assertEquals("a Keystore fault must not count towards the wipe", 0, PinLock.failures())
        FakeAndroidKeyStore.failing.clear()
        assertEquals(PinLock.Result.Ok, verify(pin))
    }

    @Test fun lockoutSurvivesARebootInFull() {
        PinLock.setPin(pin.toCharArray())
        repeat(5) { verify("000000") }
        assertEquals(30, PinLock.lockoutSecondsLeft())
        // A reboot resets the monotonic clock: the stored start is now in its future.
        SecurePrefs.put("pin.lockAtElapsed", SystemClock.elapsedRealtime() + 10_000)
        assertEquals(30, PinLock.lockoutSecondsLeft())
    }

    @Test fun secretBoxOpensOnlyWithItsPin() {
        val sealed = SecretBox.seal("test-api-secret-not-real", pin.toCharArray())
        assertTrue(SecretBox.isCurrent(sealed))
        assertEquals("test-api-secret-not-real", SecretBox.open(sealed, pin.toCharArray()))
        assertNull(SecretBox.open(sealed, "135790".toCharArray()))
        assertNull(SecretBox.open("garbage", pin.toCharArray()))
        assertNull(SecretBox.upgrade(sealed, pin.toCharArray(), "x"))
    }

    @Test fun secretBoxFallsBackToV1AndUpgradesLater() {
        FakeAndroidKeyStore.failing += "ol.pin.pepper.v1"
        val v1 = SecretBox.seal("test-api-secret-not-real", pin.toCharArray())
        assertTrue(v1.startsWith("v1:"))
        assertEquals("test-api-secret-not-real", SecretBox.open(v1, pin.toCharArray()))
        assertNull("no upgrade while the Keystore refuses", SecretBox.upgrade(v1, pin.toCharArray(), "test-api-secret-not-real"))
        FakeAndroidKeyStore.failing.clear()
        val v2 = SecretBox.upgrade(v1, pin.toCharArray(), "test-api-secret-not-real")
        assertNotNull(v2)
        assertTrue(SecretBox.isCurrent(v2!!))
        assertEquals("test-api-secret-not-real", SecretBox.open(v2, pin.toCharArray()))
    }

    @Test fun aV2SecretIsUselessWithoutThisPhonesPepper() {
        val sealed = SecretBox.seal("test-api-secret-not-real", pin.toCharArray())
        PinPepper.destroy()
        assertNull(SecretBox.open(sealed, pin.toCharArray()))
    }
}
