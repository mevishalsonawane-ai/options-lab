package com.optionslab.app.security

import com.optionslab.app.testing.FakeAndroidKeyStore
import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * [Vault.writeFile] replaces a file atomically: the old version or the new one, never half of
 * one, and never an empty file when the Keystore fails part-way.
 */
class VaultAtomicTest : RobolectricTest() {
    @get:org.junit.Rule val watchdog = com.optionslab.app.testing.BackgroundWatchdog()
    private val alias = "ol.vault.data.v1"

    @Test fun aKeystoreFaultLeavesTheOldFileWholeAndNoTemp() {
        val f = File(context.filesDir, "ledger.vault")
        Vault.writeFile(f, "version one".toByteArray())
        val before = f.readBytes()
        FakeAndroidKeyStore.failing += alias
        try { Vault.writeFile(f, "version two".toByteArray()); fail("wrote without a key") } catch (_: Exception) {}
        FakeAndroidKeyStore.failing -= alias
        assertArrayEquals("not touched", before, f.readBytes())
        assertFalse(File(f.parentFile, "ledger.vault.tmp").exists())
        assertEquals("version one", String(Vault.readFile(f)!!))
    }

    @Test fun aTempFileLeftByACrashIsReplacedNotRead() {
        val f = File(context.filesDir, "deep/er/prefs.vault")
        f.parentFile!!.mkdirs()
        File(f.parentFile, "prefs.vault.tmp").writeText("torn")
        Vault.writeFile(f, "fresh".toByteArray())
        assertFalse(File(f.parentFile, "prefs.vault.tmp").exists())
        assertEquals("fresh", String(Vault.readFile(f)!!))
    }

    @Test fun aShorterVersionReplacesTheWholeFile() {
        val f = File(context.filesDir, "a.vault")
        Vault.writeFile(f, ByteArray(10_000) { 7 })
        Vault.writeFile(f, "short".toByteArray())
        assertEquals(12 + 5 + 16, f.length().toInt())   // IV + text + GCM tag: nothing of the old one left
        assertEquals("short", String(Vault.readFile(f)!!))
    }

    @Test fun parentDirectoriesAreCreatedAndSyncingNeverThrows() {
        val f = File(context.filesDir, "x/y/z.vault")
        Vault.writeFile(f, "z".toByteArray())
        assertTrue(f.exists())
        Vault.syncDir(null)
        Vault.syncDir(File(context.filesDir, "does-not-exist"))
        Vault.syncDir(f.parentFile)
    }

    @Test fun aPassingKeystoreHiccupIsNotMistakenForTampering() {
        val f = File(context.filesDir, "b.vault")
        Vault.writeFile(f, "steady".toByteArray())
        FakeAndroidKeyStore.failing += alias
        val healer = Thread { Thread.sleep(100); FakeAndroidKeyStore.failing -= alias }
        healer.start()
        assertEquals("steady", String(Vault.readFileSteady(f)!!))
        healer.join()
        FakeAndroidKeyStore.failing += alias
        try { Vault.readFileSteady(f, attempts = 2); fail("a lasting fault must surface") } catch (_: Exception) {}
        FakeAndroidKeyStore.failing -= alias
        assertEquals("steady", String(Vault.readFile(f)!!))
    }
}
