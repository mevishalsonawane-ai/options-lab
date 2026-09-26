package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup and restore. Every secret here is a made-up test value. Old formats (v1 PIN-sealed,
 * v2 passphrase at 310k rounds) are built independently from the documented layout
 * (MAGIC | salt 16 | iv 12 | AES-GCM(body, aad = MAGIC)), so the reader is checked against the spec.
 */
class BackupTest : RobolectricTest() {
    private val pass = "Test-Passphrase-42!".toCharArray()

    private fun orbFile() = File(context.filesDir, "orb.vault")
    private fun pineFile() = File(context.filesDir, "pine.vault")

    private fun seedPhone() {
        SecurePrefs.putAll(mapOf(
            "s.capital" to 321_000.0, "ui.theme" to "dark",
            // Private: must never leave the phone.
            "kite.apiKey" to "test-key-not-real", "pin.hash" to "00ff", "k.mode" to "live", "g.kill" to false, "sec.wipe" to true,
        ))
        Vault.writeFile(orbFile(), JSONObject().put("armed", JSONObject().put("arm1", true)).put("auto", JSONObject().put("arm1", true))
            .toString().toByteArray())
        Vault.writeFile(pineFile(), JSONArray().put(JSONObject().put("name", "t").put("auto", JSONObject().put("on", true)))
            .toString().toByteArray())
    }

    /** A backup file built by hand, in any of the three formats. */
    private fun craft(magic: String, rounds: Int, secret: String, prefs: JSONObject, files: JSONObject = JSONObject()): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(secret.toCharArray(), salt, rounds, 256)).encoded
        val body = JSONObject().put("v", 1).put("at", 1_700_000_000_000L).put("prefs", prefs).put("files", files).toString().toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(raw, "AES"), GCMParameterSpec(128, iv)); updateAAD(magic.toByteArray())
        }
        return magic.toByteArray() + salt + iv + c.doFinal(body)
    }

    private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

    @Test fun passphraseStrength() {
        assertEquals(0, Backup.strength("Short1!".toCharArray()))
        assertEquals(1, Backup.strength("aaaaaaaaaaaa".toCharArray()))
        assertEquals(2, Backup.strength("Abcdefgh12".toCharArray()))
        assertEquals(3, Backup.strength("correct horse battery staple".toCharArray()))
    }

    @Test fun weakPassphrasesAreRefused() = runBlocking {
        for (bad in listOf("Ab1!", "aaaaaaaaaaaa")) {
            try { Backup.create(context, bad.toCharArray()); fail("$bad accepted") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun createThenOpenCarriesDataButNoPrivateKeys() = runBlocking {
        seedPhone()
        val bytes = Backup.create(context, pass.copyOf())
        assertEquals("IRABK3", String(bytes.copyOfRange(0, 6)))
        assertEquals(2, Backup.version(bytes))
        assertFalse("private value in the clear", String(bytes, Charsets.ISO_8859_1).contains("test-key-not-real"))
        val c = Backup.open(bytes, pass.copyOf())
        assertFalse(c.pinSealed)
        assertEquals(2, c.files)
        val prefs = c.json.getJSONObject("prefs")
        assertEquals(321_000.0, prefs.getDouble("s.capital"), 0.0)
        for (k in listOf("kite.apiKey", "pin.hash", "k.mode", "g.kill", "sec.wipe")) assertFalse("$k was backed up", prefs.has(k))
    }

    @Test fun wrongPassphraseTamperingAndNonBackupsAreRefused() = runBlocking {
        val bytes = Backup.create(context, pass.copyOf())
        try { Backup.open(bytes, "Other-Passphrase-42!".toCharArray()); fail() } catch (_: Backup.WrongPin) {}
        val bad = bytes.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        try { Backup.open(bad, pass.copyOf()); fail() } catch (_: Backup.WrongPin) {}
        try { Backup.version("not a backup at all, just some bytes".toByteArray()); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun restoreKeepsThisPhonesPrivateSettingsAndDisarmsEverything() = runBlocking {
        seedPhone()
        val bytes = Backup.create(context, pass.copyOf())
        // "New phone": different private settings, no data.
        SecurePrefs.wipe(); orbFile().delete(); pineFile().delete()
        SecurePrefs.putAll(mapOf("k.mode" to "sandbox", "g.kill" to true, "kite.apiKey" to "other-test-key"))
        val r = Backup.restore(context, Backup.open(bytes, pass.copyOf()))
        assertFalse(r.changePin)
        assertEquals(321_000.0, SecurePrefs.getDouble("s.capital", 0.0), 0.0)
        assertEquals("dark", SecurePrefs.getString("ui.theme"))
        assertEquals("sandbox", SecurePrefs.getString("k.mode"))
        assertTrue(SecurePrefs.getBoolean("g.kill", false))
        assertEquals("other-test-key", SecurePrefs.getString("kite.apiKey"))
        assertNull(SecurePrefs.getString("pin.hash"))
        assertTrue("restored state must start disarmed", SecurePrefs.getBoolean(Backup.DISARM, false))
        val orb = JSONObject(String(Vault.readFile(orbFile())!!))
        assertEquals(0, orb.getJSONObject("armed").length())
        assertEquals(0, orb.getJSONObject("auto").length())
        val pine = JSONArray(String(Vault.readFile(pineFile())!!))
        assertFalse(pine.getJSONObject(0).getJSONObject("auto").getBoolean("on"))
    }

    @Test fun aCraftedFileCannotSwitchOnLiveTradingOrLoosenTheGuard() {
        SecurePrefs.putAll(mapOf("k.mode" to "sandbox", "g.kill" to true, "g.loss" to 1_000.0))
        val evil = JSONObject().put("k.mode", "live").put("k.allow", true).put("g.kill", false).put("g.loss", 1e9)
            .put("sec.refuse", false).put("lock.idleSeconds", 999_999).put("kite.apiKey", "test-injected")
            .put("pin.hash", "00").put("relay.host", "203.0.113.9").put("s.capital", 5.0)
        val c = Backup.open(craft("IRABK3", 600_000, "Test-Passphrase-42!", evil), pass.copyOf())
        Backup.restore(context, c)
        assertEquals("sandbox", SecurePrefs.getString("k.mode"))
        assertFalse(SecurePrefs.getBoolean("k.allow", false))
        assertTrue(SecurePrefs.getBoolean("g.kill", false))
        assertEquals(1_000.0, SecurePrefs.getDouble("g.loss", 0.0), 0.0)
        for (k in listOf("sec.refuse", "lock.idleSeconds", "kite.apiKey", "pin.hash", "relay.host")) assertNull(k, SecurePrefs.snapshot()[k])
        assertEquals(5.0, SecurePrefs.getDouble("s.capital", 0.0), 0.0)
    }

    @Test fun oldPassphraseFilesStillOpen() {
        val bytes = craft("IRABK2", 310_000, "Test-Passphrase-42!", JSONObject().put("s.capital", 7.0),
            JSONObject().put("n/ledger.vault", b64("[]")))
        assertEquals(2, Backup.version(bytes))
        val c = Backup.open(bytes, pass.copyOf())
        assertFalse(c.pinSealed)
        assertEquals(1, c.files)
        assertFalse(Backup.restore(context, c).changePin)
        assertEquals("[]", String(Vault.readFile(File(context.noBackupFilesDir, "ledger.vault"))!!))
    }

    @Test fun oldPinSealedFilesOpenAndAskForANewPin() {
        val bytes = craft("IRABK1", 200_000, "246813", JSONObject().put("ui.theme", "light"))
        assertEquals(1, Backup.version(bytes))
        try { Backup.open(bytes, "135790".toCharArray()); fail() } catch (e: Backup.WrongPin) { assertTrue(e.message!!.contains("PIN")) }
        val c = Backup.open(bytes, "246813".toCharArray())
        assertTrue(c.pinSealed)
        assertTrue(Backup.restore(context, c).changePin)
        assertEquals("light", SecurePrefs.getString("ui.theme"))
    }

    @Test fun filesMissingFromTheBackupAreRemovedHere() {
        seedPhone()
        val c = Backup.open(craft("IRABK3", 600_000, "Test-Passphrase-42!", JSONObject()), pass.copyOf())
        Backup.restore(context, c)
        assertFalse(orbFile().exists())
        assertFalse(pineFile().exists())
    }

    @Test fun nothingIsRestoredOverAnUnreadableVault() {
        SecurePrefs.put("x", 1)
        Vault.destroy()
        assertFalse(SecurePrefs.reload())
        val c = Backup.open(craft("IRABK3", 600_000, "Test-Passphrase-42!", JSONObject().put("s.capital", 1.0),
            JSONObject().put("f/orb.vault", b64("{}"))), pass.copyOf())
        try { Backup.restore(context, c); fail() } catch (_: IllegalStateException) {}
        assertFalse(orbFile().exists())
    }
}
