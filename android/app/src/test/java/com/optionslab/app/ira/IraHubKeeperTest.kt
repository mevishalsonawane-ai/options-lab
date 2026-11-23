package com.optionslab.app.ira

import com.optionslab.app.testing.RobolectricTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 23 Nov 2026 (B6 follow-ups, round 2): Jarvis's conversation keeper ([IraHub]) saves each change 300 ms after it. With its scope's
 * handler ([com.optionslab.app.data.ScopeGuard]) a save that threw no longer ended the process - but it ended the keeper, and nothing more
 * was saved for the rest of the process. Now a failed save is tried again (2 s apart), and the keeper goes on.
 */
class IraHubKeeperTest : RobolectricTest() {
    @After fun down() { IraHub.testBeforeSave = null }

    private fun awaitTrue(what: String, timeoutMs: Long = 5_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(10) }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun aSaveThatFailsNeverEndsTheKeeper() {
        val file = java.io.File(context.noBackupFilesDir, "ira-state.vault")
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        IraHub.testBeforeSave = { if (failed.compareAndSet(false, true)) throw IllegalStateException("the disk is full") }
        file.delete()
        IraHub.note("Boss, a first note.")
        awaitTrue("the first save to fail") { failed.get() }
        assertTrue("not saved yet", !file.exists())
        awaitTrue("tried again and saved, with no new change", 6_000) { file.exists() }
        file.delete()
        IraHub.note("Boss, a second note.")
        awaitTrue("the next change saved by the same keeper") { file.exists() }
        assertTrue(file.length() > 0)
    }
}
