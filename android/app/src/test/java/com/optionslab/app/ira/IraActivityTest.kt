package com.optionslab.app.ira

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.RobolectricTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jarvis's activity log (20 Oct, the 17 Oct 01:09 ANR): its writes were made with the Keystore under the log's lock, and
 * Jarvis's speech callbacks on the screen's thread add to the log. Now an add never waits on the disk.
 */
class IraActivityTest : RobolectricTest() {
    @After fun down() { Vault.testWriteDelayMs = 0L }

    @Test fun anAddNeverWaitsOnASlowVaultWrite() {
        // Jarvis's log exists only in a build with Jarvis (CI also runs the app's tests without him); the store-level
        // proof is SecureBlobsTest.aSlowWriteNeverHoldsAPutOfTheSameKey.
        org.junit.Assume.assumeTrue(com.optionslab.app.BuildConfig.JARVIS)
        Vault.testWriteDelayMs = 1_500L
        val t0 = System.nanoTime()
        val other = Thread { IraActivity.add("Answered a question (test).") }.apply { start() }
        other.join(5_000)
        IraActivity.add("Heard Boss (test).")
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("two adds took $ms ms with a 1.5 s vault write", ms < 1_000)
        val said = IraActivity.entries().map { it.what }
        assertTrue(said.containsAll(listOf("Answered a question (test).", "Heard Boss (test).")))
        // Written by the background writer in the end: on disk after a flush.
        Vault.testWriteDelayMs = 0L
        assertTrue(SecurePrefs.flush())
        com.optionslab.app.security.SecureBlobs.reset()
        assertEquals(said, IraActivity.entries().map { it.what })
    }
}
