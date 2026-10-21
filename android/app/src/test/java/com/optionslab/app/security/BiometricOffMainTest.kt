package com.optionslab.app.security

import android.os.Looper
import com.optionslab.app.testing.FakeAndroidKeyStore
import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * 16 Oct (Boss's diagnostics: "the screen froze 1.5 s in Cipher.createCipher" at the fingerprint unlock): the fingerprint's
 * Keystore work runs on its own background thread, and its answer comes back on the screen's thread.
 */
class BiometricOffMainTest : RobolectricTest() {
    @Test fun theFingerprintKeyIsMadeOffTheScreensThreadAndAnsweredOnIt() {
        var done: Throwable? = IllegalStateException("not answered")
        var answeredOn: Thread? = null
        val main = Looper.getMainLooper().thread
        BiometricGate.enrolOffMain { e -> done = e; answeredOn = Thread.currentThread() }
        // Not made on the calling (screen's) thread: the answer waits for the background thread, then the screen's queue.
        val end = System.currentTimeMillis() + 5_000
        while (answeredOn == null && System.currentTimeMillis() < end) { Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle() }
        assertNull(done)
        assertEquals(main, answeredOn)
        assertTrue("the match-gated key exists", "ol.vault.bio.v1" in FakeAndroidKeyStore.aliases())
        // A broken Keystore: the failure comes back, on the screen's thread, never thrown there.
        FakeAndroidKeyStore.failing += "ol.vault.bio.v1"
        answeredOn = null
        BiometricGate.enrolOffMain { e -> done = e; answeredOn = Thread.currentThread() }
        val end2 = System.currentTimeMillis() + 5_000
        while (answeredOn == null && System.currentTimeMillis() < end2) { Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle() }
        FakeAndroidKeyStore.failing.clear()
        assertNotEquals(null, done)
        assertEquals(main, answeredOn)
    }
}
