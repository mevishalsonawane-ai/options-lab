package com.optionslab.app.security

import com.optionslab.app.testing.FakeAndroidKeyStore
import com.optionslab.app.testing.RobolectricTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class VaultTest : RobolectricTest() {
    private val plain = "paper ledger: nothing real in here".toByteArray()

    @Test fun roundTripAndFreshIvEachTime() {
        val a = Vault.encrypt(plain)
        val b = Vault.encrypt(plain)
        assertFalse("same IV twice", a.copyOfRange(0, 12).contentEquals(b.copyOfRange(0, 12)))
        assertArrayEquals(plain, Vault.decrypt(a))
        assertArrayEquals(plain, Vault.decrypt(b))
        assertTrue("the key lives in the (fake) Keystore", "ol.vault.data.v1" in FakeAndroidKeyStore.aliases())
    }

    @Test fun aFlippedBitIsTamperingNotData() {
        val blob = Vault.encrypt(plain)
        for (i in listOf(0, 12, blob.size - 1)) {
            val bad = blob.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            try { Vault.decrypt(bad); fail("byte $i flipped but decrypted") } catch (_: Vault.Tampered) {}
        }
        try { Vault.decrypt(ByteArray(12)); fail("a bare IV decrypted") } catch (_: Vault.Tampered) {}
    }

    @Test fun filesAreSealedAtRestAndAtomic() {
        val f = File(context.filesDir, "sub/x.vault")
        assertNull(Vault.readFile(f))
        Vault.writeFile(f, plain)
        assertFalse("plaintext on disk", String(f.readBytes(), Charsets.ISO_8859_1).contains("paper ledger"))
        assertFalse(File(f.parentFile, "x.vault.tmp").exists())
        assertArrayEquals(plain, Vault.readFileSteady(f))
    }

    @Test fun destroyingTheKeyMakesEveryFileUnreadable() {
        val f = File(context.filesDir, "y.vault")
        Vault.writeFile(f, plain)
        Vault.destroy()
        try { Vault.readFile(f); fail("readable after the key was destroyed") } catch (_: Exception) {}
        val aside = Vault.setAside(f)
        assertFalse(f.exists())
        assertTrue(aside.exists() && aside.name.startsWith("y.vault.unreadable."))
    }

    @Test fun securePrefsPersistAcrossAReload() {
        SecurePrefs.putAll(mapOf("s" to "text", "b" to true, "i" to 7, "l" to (1L shl 40), "d" to 2.5))
        assertTrue(SecurePrefs.reload())
        assertEquals("text", SecurePrefs.getString("s"))
        assertTrue(SecurePrefs.getBoolean("b", false))
        assertEquals(7, SecurePrefs.getInt("i", 0))
        assertEquals(1L shl 40, SecurePrefs.getLong("l", 0))
        assertEquals(2.5, SecurePrefs.getDouble("d", 0.0), 0.0)
        assertEquals("fallback", SecurePrefs.getString("missing", "fallback"))
        SecurePrefs.put("s", null)
        assertNull(SecurePrefs.getString("s"))
        assertEquals(setOf("b", "i", "l", "d"), SecurePrefs.snapshot().keys)
    }

    @Test fun anUnreadableSettingsVaultFailsClosedAndIsNeverOverwritten() {
        SecurePrefs.put("pin.hash", "00ff")
        val file = File(context.noBackupFilesDir, "prefs.vault")
        val before = file.readBytes()
        Vault.destroy()
        assertFalse(SecurePrefs.reload())
        assertTrue(SecurePrefs.unreadable)
        assertTrue("an unreadable vault must still count as a PIN being set", PinLock.isSet)
        SecurePrefs.put("pin.hash", null)
        assertArrayEquals("written over an unreadable vault", before, file.readBytes())
        SecurePrefs.wipe()
        assertFalse(SecurePrefs.unreadable)
        assertFalse(file.exists())
    }
}
