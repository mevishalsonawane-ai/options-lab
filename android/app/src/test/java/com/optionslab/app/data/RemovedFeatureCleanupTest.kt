package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 25 Oct: what the removed Telegram feature left on the phone (its login's folder, the Signal tester's log, its encrypted settings)
 * is deleted once on the first start after the update, logged by name only; other files and settings are left alone.
 */
class RemovedFeatureCleanupTest : RobolectricTest() {
    @Test fun theTelegramDataIsDeletedOnceAndNothingElse() {
        val dir = context.noBackupFilesDir
        File(dir, "tdlib/db").mkdirs(); File(dir, "tdlib/db/td.binlog").writeText("x"); File(dir, "tdlib/files/a").apply { parentFile!!.mkdirs(); writeText("y") }
        File(dir, "signals.vault").writeText("z")
        val other = File(dir, "ntfy_arm.vault").apply { writeText("keep") }
        SecurePrefs.putAll(mapOf("telegram.api_id" to 12345, "telegram.api_hash" to "h", "telegram.db_key" to "k", "telegram.logged_in" to true,
            "ntfy.topic" to "keep-this-topic-please"))
        assertTrue(RemovedFeatureCleanup.run(dir))
        assertFalse(File(dir, "tdlib").exists()); assertFalse(File(dir, "signals.vault").exists())
        assertTrue(other.exists())
        assertEquals(emptyList<String>(), SecurePrefs.keys("telegram."))
        assertEquals("keep-this-topic-please", SecurePrefs.getString("ntfy.topic"))
        assertTrue(SecurePrefs.getBoolean(RemovedFeatureCleanup.K_DONE, false))
        val said = Diag.lines().filter { "removed Telegram data" in it }
        assertTrue(said.toString(), said.any { "tdlib deleted" in it } && said.any { "4 encrypted setting(s) deleted" in it })
        assertFalse(said.toString(), said.any { "12345" in it || "keep-this-topic" in it })
        // Once only: a later start does nothing.
        SecurePrefs.put("telegram.other", "x")
        assertTrue(RemovedFeatureCleanup.run(dir))
        assertEquals("x", SecurePrefs.getString("telegram.other"))
    }

    @Test fun nothingToCleanIsDoneAtOnce() {
        assertTrue(RemovedFeatureCleanup.run(context.noBackupFilesDir))
        assertTrue(SecurePrefs.getBoolean(RemovedFeatureCleanup.K_DONE, false))
    }
}
