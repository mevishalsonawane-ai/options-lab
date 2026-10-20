package com.optionslab.app.security

import com.optionslab.app.testing.RobolectricTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The settings vault's large values in their own files (Speed, 12 Oct: "app start 2069 ms (slowest: settings 1990 ms)").
 * The start decrypts only the small settings file; the large values move out once, crash-safely, and every caller, the
 * backup and the wipe still see them as settings.
 */
class SecureBlobsTest : RobolectricTest() {
    private val big = "jarvis.solo.trades"
    private val settingsFile get() = File(context.noBackupFilesDir, "prefs.vault")
    private val blobFile get() = File(File(context.noBackupFilesDir, "prefs.blobs"), "$big.vault")

    /** A settings file as an older version wrote it: the large value inside, next to small settings. */
    private fun olderVersionsFile(value: String) {
        Vault.writeFile(settingsFile, JSONObject().put("pin.hash", "00ff").put("small", 3).put(big, value).toString().toByteArray())
        assertTrue(SecurePrefs.reload())
    }

    private fun onDisk(): JSONObject = JSONObject(String(Vault.readFileSteady(settingsFile)!!, Charsets.UTF_8))

    private fun bulk(n: Int) = org.json.JSONArray().apply { repeat(n) { put(JSONObject().put("id", it).put("note", "a paper trade, nothing real $it")) } }.toString()

    @Test fun movedOutOnceVerifiedThenDroppedFromTheSettingsFile() {
        val value = bulk(2000)
        olderVersionsFile(value)
        assertTrue(SecurePrefs.flush())
        assertTrue("its own file written", blobFile.exists())
        assertFalse("still in the settings file", onDisk().has(big))
        assertEquals("00ff", onDisk().getString("pin.hash"))
        assertEquals(value, SecurePrefs.getString(big))
        // Again (a second start): nothing changes, nothing is lost.
        assertTrue(SecurePrefs.reload()); assertTrue(SecurePrefs.flush())
        assertEquals(value, SecurePrefs.getString(big))
        assertEquals(3, SecurePrefs.getInt("small", 0))
    }

    @Test fun theStartDecryptsOnlyTheSmallSettingsFile() {
        SecurePrefs.put("small", 1)
        SecurePrefs.put(big, bulk(5000))
        assertTrue("the settings file stays small", settingsFile.length() < 16_384)
        assertTrue(blobFile.length() > 100_000)
        SecurePrefs.flush()
        val before = SecureBlobs.loadsOnMain.get()
        val t0 = System.nanoTime()
        SecureBlobs.reset()
        assertTrue(SecurePrefs.reload())     // the settings decrypted again, as at a start
        SecurePrefs.warm()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(1, SecurePrefs.getInt("small", 0))
        assertEquals("a large value decrypted on the start's thread", before, SecureBlobs.loadsOnMain.get())
        assertTrue("settings load took $ms ms", ms < 2_000)   // generous: CI machines vary; the point is the file decrypted
    }

    @Test fun aSettingsWriteBeforeTheMoveStillCarriesTheOldValue() {
        val value = bulk(50)
        olderVersionsFile(value)
        // A settings write racing the move: the large value is written back with it until its own file is there.
        SecurePrefs.put("small", 4)
        SecurePrefs.flush()
        // Whatever ran first, the value is in one place or both - never in neither.
        assertTrue(onDisk().has(big) || blobFile.exists())
        assertEquals(value, SecurePrefs.getString(big))
    }

    @Test fun itsOwnFileWinsOverAnOldCopyInTheSettingsFile() {
        SecurePrefs.put(big, "newer")
        // A crash between the file and the settings file's rewrite: the old copy is still there.
        olderVersionsFile("older")
        SecurePrefs.flush()
        assertEquals("newer", SecurePrefs.getString(big))
        assertFalse(onDisk().has(big))
    }

    @Test fun aDeletedValueNeverComesBackFromTheSettingsFile() {
        olderVersionsFile("older")
        SecurePrefs.put(big, null)
        assertNull(SecurePrefs.getString(big))
        // The settings file may still hold the old copy (written back until the move): the tombstone wins.
        SecureBlobs.reset()
        assertNull(SecurePrefs.getString(big))
        assertTrue(SecurePrefs.reload()); SecurePrefs.flush()
        assertNull(SecurePrefs.getString(big))
    }

    @Test fun soonAndLazyWritesLandInTheirOwnFile() {
        SecurePrefs.putAllSoon(mapOf(big to "soon", "small" to 2))
        assertEquals("soon", SecurePrefs.getString(big))
        assertTrue(SecurePrefs.flush())
        SecureBlobs.reset()
        assertEquals("soon", SecurePrefs.getString(big))
        SecurePrefs.putAllLazy(mapOf(big to "lazy"))
        assertTrue(SecurePrefs.flush())
        SecureBlobs.reset()
        assertEquals("lazy", SecurePrefs.getString(big))
        assertFalse(onDisk().has(big))
    }

    @Test fun backupSnapshotKeysAndWipeCoverThem() {
        SecurePrefs.putAll(mapOf(big to "trades", "small" to 1))
        SecureBlobs.reset()
        assertTrue("listed without decrypting", big in SecurePrefs.keys("jarvis.solo."))
        assertEquals("trades", SecurePrefs.snapshot()[big])
        // An older backup's restore (the value under the settings) lands in its own file.
        SecurePrefs.putAll(mapOf(big to "restored"))
        SecureBlobs.reset()
        assertEquals("restored", SecurePrefs.getString(big))
        assertFalse(onDisk().has(big))
        SecurePrefs.wipe()
        assertNull(SecurePrefs.getString(big))
        assertFalse(blobFile.exists())
    }

    @Test fun anUnreadableSettingsVaultIsNeverWrittenAroundEither() {
        SecurePrefs.put(big, "kept")
        val before = blobFile.readBytes()
        Vault.destroy()
        assertFalse(SecurePrefs.reload())
        runCatching { SecurePrefs.put(big, "over it") }
        assertTrue("written while the settings vault is unreadable", before.contentEquals(blobFile.readBytes()))
        SecurePrefs.wipe()
    }

    @Test fun noLockPinLiveOrGuardSettingEverMovesOut() {
        for (k in SecureBlobs.KEYS) for (p in SecureBlobs.NEVER) assertFalse("$k moved out", k.startsWith(p))
        for (k in SecureBlobs.KEYS) assertTrue("$k is not a plain name", Regex("[A-Za-z0-9._-]+").matches(k))
    }

    @Test fun theStartsCodeNeverReadsALargeValue() {
        // The start (IraAlgoApp.onCreate) and the activity's own steps touch none of the stores whose records moved out:
        // each is read by its own background job, or ahead by SecurePrefs.warm's prefetch thread.
        fun src(path: String) = listOf(File("src/main/java/$path"), File("app/src/main/java/$path")).firstOrNull { it.isFile }?.readText()
        val owners = listOf("IraSolo", "IraNewsTrades", "IraStudy", "IraActivity", "IraThinking", "IraDayJournal", "IraJournal",
            "IraBots", "SecureBlobs")
        for (f in listOf("com/optionslab/app/IraAlgoApp.kt", "com/optionslab/app/MainActivity.kt")) {
            val text = checkNotNull(src(f)) { "$f not found" }
            for (o in owners) assertFalse("$f reads $o at the start", Regex("\\b$o\\.").containsMatchIn(text))
            for (k in SecureBlobs.KEYS) assertFalse("$f reads $k", text.contains("\"$k\""))
        }
        for (k in SecureBlobs.PREFETCH) assertTrue(k in SecureBlobs.KEYS)
    }

    @Test fun theSizesLineNamesKeysNeverValues() {
        SecurePrefs.putAll(mapOf(big to "secret-looking text", "small" to 1))
        val line = SecurePrefs.sizeLine()
        assertTrue(line, line.startsWith("Settings size:"))
        assertFalse(line, line.contains("secret-looking"))
    }

    /**
     * 20 Oct (the 17 Oct 01:09 ANR, "Input dispatching timed out"): a value's lock was held for a whole Keystore write, so the
     * screen's thread putting the same key waited for it. Now a write holds only its own write lock: a put in the meantime
     * returns at once, and the newer value is the one left on disk.
     */
    @Test fun aSlowWriteNeverHoldsAPutOfTheSameKey() {
        SecurePrefs.put(big, bulk(10))
        Vault.testWriteDelayMs = 1_500L
        try {
            val writer = Thread { SecureBlobs.put(big, bulk(20)) }.apply { start() }
            // The write is under way: the writer sleeps inside the vault's write (polled, never a fixed guess).
            val until = System.currentTimeMillis() + 10_000
            while (writer.state != Thread.State.TIMED_WAITING && System.currentTimeMillis() < until) Thread.sleep(5)
            assertEquals(Thread.State.TIMED_WAITING, writer.state)
            val t0 = System.nanoTime()
            assertTrue(SecureBlobs.putSoon(big, bulk(30)))
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("putSoon waited $ms ms behind a write", ms < 500)
            assertEquals(bulk(30), SecureBlobs.get(big))           // readable at once
            writer.join(10_000)
            Vault.testWriteDelayMs = 0L
            // The slow write took the older value: the key is still to be written, and the next write leaves the newest.
            assertTrue(SecureBlobs.anyDirty())
            SecureBlobs.saveNow(big)
            assertFalse(SecureBlobs.anyDirty())
            SecureBlobs.reset()
            assertEquals(bulk(30), SecureBlobs.get(big))
        } finally { Vault.testWriteDelayMs = 0L }
    }
}
