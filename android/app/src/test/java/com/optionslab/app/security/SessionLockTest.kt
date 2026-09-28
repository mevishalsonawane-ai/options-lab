package com.optionslab.app.security

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.optionslab.app.testing.RobolectricTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** The session seal: locked on start, open after an unlock, sealed again after the chosen idle time. */
class SessionLockTest : RobolectricTest() {
    /** The app's process, as ProcessLifecycleOwner would pass it (SessionLock only reads the clock). */
    private val owner = object : LifecycleOwner {
        override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    private fun pass(seconds: Long) = ShadowSystemClock.advanceBy(Duration.ofSeconds(seconds))

    @Before fun sealed() = SessionLock.lock()
    @After fun reseal() = SessionLock.lock()

    @Test fun lockAndUnlock() {
        assertTrue(SessionLock.locked.value)
        SessionLock.unlock()
        assertFalse(SessionLock.locked.value)
        SessionLock.lock()
        assertTrue(SessionLock.locked.value)
    }

    @Test fun sealsAfterTheDefaultFiveIdleMinutes() {
        assertTrue(SessionLock.DEFAULT_IDLE == 300)
        SessionLock.unlock()
        pass(299); SessionLock.checkIdle()
        assertFalse(SessionLock.locked.value)
        pass(1); SessionLock.checkIdle()
        assertTrue(SessionLock.locked.value)
    }

    @Test fun aTouchKeepsTheSessionOpen() {
        SessionLock.unlock()
        repeat(5) { pass(200); SessionLock.touch(); SessionLock.checkIdle() }
        assertFalse("1000 s with a touch every 200 s", SessionLock.locked.value)
        pass(300); SessionLock.checkIdle()
        assertTrue(SessionLock.locked.value)
    }

    @Test fun theIdleLimitComesFromSettingsWithinBounds() {
        SecurePrefs.put(SessionLock.K_IDLE, 60)
        SessionLock.unlock(); pass(59); SessionLock.checkIdle()
        assertFalse(SessionLock.locked.value)
        pass(1); SessionLock.checkIdle()
        assertTrue(SessionLock.locked.value)
        // Never shorter than 30 s ...
        SecurePrefs.put(SessionLock.K_IDLE, 1)
        SessionLock.unlock(); pass(29); SessionLock.checkIdle()
        assertFalse(SessionLock.locked.value)
        pass(1); SessionLock.checkIdle()
        assertTrue(SessionLock.locked.value)
        // ... and never longer than 15 minutes, whatever is stored.
        SecurePrefs.put(SessionLock.K_IDLE, 999_999)
        SessionLock.unlock(); pass(899); SessionLock.checkIdle()
        assertFalse(SessionLock.locked.value)
        pass(1); SessionLock.checkIdle()
        assertTrue(SessionLock.locked.value)
    }

    @Test fun checkingWhileSealedChangesNothing() {
        pass(10_000); SessionLock.checkIdle()
        assertTrue(SessionLock.locked.value)
    }

    @Test fun comingBackFromTheBackgroundCountsTheTimeAway() {
        SessionLock.unlock()
        pass(301)
        SessionLock.onStart(owner)
        assertTrue(SessionLock.locked.value)
        SessionLock.unlock()
        pass(10)
        SessionLock.onStart(owner)
        assertFalse(SessionLock.locked.value)
    }
}
