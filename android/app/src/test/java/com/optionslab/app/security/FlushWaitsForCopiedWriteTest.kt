package com.optionslab.app.security

import com.optionslab.app.testing.RobolectricTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 15 Nov, CI (GuardTest's kill switch): a background write that has copied the settings out but not yet put them on disk
 * must be waited for by [SecurePrefs.flush] - before, flush could answer "not written" in that moment although the write
 * finished a moment later.
 */
class FlushWaitsForCopiedWriteTest : RobolectricTest() {
    @After fun down() { SecurePrefs.testAfterCopyOut = null }

    @Test fun flushWaitsForAWriteAlreadyCopiedOutAndSaysWritten() {
        val copied = CountDownLatch(1)
        val release = CountDownLatch(1)
        SecurePrefs.testAfterCopyOut = { copied.countDown(); release.await(10, TimeUnit.SECONDS) }
        SecurePrefs.putAllSoon(mapOf("ui.theme" to "dark"))
        assertTrue("the background writer copied the map out", copied.await(10, TimeUnit.SECONDS))
        SecurePrefs.testAfterCopyOut = null
        val answer = AtomicReference<Boolean?>(null)
        val flusher = Thread { answer.set(SecurePrefs.flush()) }.apply { start() }
        flusher.join(300)
        assertNull("flush is still waiting for the copied-out write", answer.get())
        release.countDown()
        flusher.join(10_000)
        assertEquals("written once the write lands", true, answer.get())
        assertTrue(SecurePrefs.reload())
        assertEquals("dark", SecurePrefs.getString("ui.theme"))
    }

    @Test fun flushWithNothingInFlightAnswersAtOnce() {
        assertTrue(SecurePrefs.flush())
        val t0 = System.currentTimeMillis()
        assertTrue(SecurePrefs.flush())
        assertTrue("no wait when everything is written", System.currentTimeMillis() - t0 < 2_000)
    }
}
