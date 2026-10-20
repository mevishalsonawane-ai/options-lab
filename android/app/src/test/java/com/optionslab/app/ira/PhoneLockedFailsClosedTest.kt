package com.optionslab.app.ira

import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 20 Oct, review: Jarvis's locked-phone check ([IraHub.phoneLocked]) answered "unlocked" when it could not tell (no context
 * yet, or the keyguard could not be asked), which would open every account answer behind it. It now fails closed.
 */
class PhoneLockedFailsClosedTest : RobolectricTest() {
    @Test fun unknownCountsAsLocked() {
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        val was = IraHub.swapAppForTest(null)
        try {
            assertTrue("no context: treated as locked", IraHub.phoneLocked())
        } finally { IraHub.swapAppForTest(was) }
    }

    @Test fun theKeyguardIsReadWhenItCanBe() {
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        val km = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java))
        assertFalse("unlocked", IraHub.phoneLocked())
        km.setKeyguardLocked(true)
        try { assertTrue("locked", IraHub.phoneLocked()) } finally { km.setKeyguardLocked(false) }
        assertFalse(IraHub.phoneLocked())
    }
}
