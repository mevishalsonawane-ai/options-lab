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
        // Boss's 06 Oct rule: the backup's script had no stop or target: restored with 30 / 60 and the lock on.
        assertEquals(30.0, pine.getJSONObject(0).getJSONObject("auto").getDouble("stopPts"), 0.0)
        assertEquals(60.0, pine.getJSONObject(0).getJSONObject("auto").getDouble("targetPts"), 0.0)
        assertTrue(pine.getJSONObject(0).getJSONObject("auto").getBoolean("profitLock"))
    }

    @Test fun aRestoreNeverBringsBackAPineScriptWithoutItsStopTargetOrLock() {
        val text = JSONArray()
            .put(JSONObject().put("id", 1).put("name", "Bare").put("code", "x")
                .put("auto", JSONObject().put("on", true).put("stopPts", 0.0).put("targetPts", 0.0).put("profitLock", false).put("profitLockChosen", true)))
            .put(JSONObject().put("id", 2).put("name", "Narrow").put("code", "x")
                .put("auto", JSONObject().put("stopPts", 12.0).put("targetPts", 90.0)))
            .put(JSONObject().put("id", 3).put("name", "No auto").put("code", "x"))
            .toString()
        val out = JSONArray(Backup.pineRestored(text))
        val bare = out.getJSONObject(0).getJSONObject("auto")
        assertFalse(bare.getBoolean("on"))
        assertEquals(30.0, bare.getDouble("stopPts"), 0.0); assertEquals(60.0, bare.getDouble("targetPts"), 0.0); assertTrue(bare.getBoolean("profitLock"))
        val narrow = out.getJSONObject(1).getJSONObject("auto")
        assertEquals("a nonzero stop is kept", 12.0, narrow.getDouble("stopPts"), 0.0); assertEquals(90.0, narrow.getDouble("targetPts"), 0.0)
        assertEquals(30.0, out.getJSONObject(2).getJSONObject("auto").getDouble("stopPts"), 0.0)
        // And read back as the app reads it.
        val items = PineScripts.parse(out.toString())
        assertTrue(items.all { it.auto.stopPts > 0 && it.auto.targetPts > 0 && it.auto.profitLock && !it.auto.on })
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

    /** Liquidity 15+5's size: a restore never raises it above this phone's; the backup's higher size waits for Boss. */
    @Test fun aRestoreNeverRaisesLiquiditysLots() {
        fun restoreWith(here: Int?, backup: Int?): JSONObject {
            orbFile().delete()
            if (here != null) Vault.writeFile(orbFile(), JSONObject().put("liqLots", here).toString().toByteArray())
            val orb = JSONObject().put("armed", JSONObject().put("liquidity5", true)).apply { backup?.let { put("liqLots", it) } }
            Backup.restore(context, Backup.open(craft("IRABK3", 600_000, "Test-Passphrase-42!", JSONObject(),
                JSONObject().put("f/orb.vault", b64(orb.toString()))), pass.copyOf()))
            return JSONObject(String(Vault.readFile(orbFile())!!))
        }
        restoreWith(here = 1, backup = 3).let { o ->
            assertEquals("kept at this phone's size", 1, o.getInt("liqLots"))
            assertEquals("the backup's 3 waits for Boss", 3, o.getInt("liqLotsAsk"))
            assertEquals(0, o.getJSONObject("armed").length())
        }
        restoreWith(here = 3, backup = 1).let { o -> assertEquals("lowering restores as it was", 1, o.getInt("liqLots")); assertFalse(o.has("liqLotsAsk")) }
        // A new phone (no book here) holds at Boss's default of 2.
        restoreWith(here = null, backup = 3).let { o -> assertEquals(2, o.getInt("liqLots")); assertEquals(3, o.getInt("liqLotsAsk")) }
        // A backup from before the setting: 2, never above this phone's 1.
        restoreWith(here = 1, backup = null).let { o -> assertEquals(1, o.getInt("liqLots")); assertEquals(2, o.getInt("liqLotsAsk")) }
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

    /** Jarvis's weekly reviews moved to their own file: a backup carries it (sealed) and a restore puts it back. */
    @Test fun theWeeklyReviewsFileIsCarried() = runBlocking {
        val f = File(context.noBackupFilesDir, "weekly-reviews.vault")
        Vault.writeFile(f, "the reviews".toByteArray())
        val bytes = Backup.create(context, pass.copyOf())
        f.delete()
        Backup.restore(context, Backup.open(bytes, pass.copyOf()))
        assertEquals("the reviews", String(Vault.readFile(f)!!))
    }

    /**
     * An older backup (from before the reviews had their own file) has them under the old preferences key and no file:
     * restored onto a phone that has the file, the phone's reviews stay and the backup's are merged in (IraWeekly.migrate).
     */
    @Test fun anOlderBackupsReviewsAreMergedWithThePhonesNotDropped() {
        fun review(monday: java.time.LocalDate, summary: String = "A quiet week.") = com.optionslab.ira.WeeklyReview.Review(monday,
            monday.plusDays(4), monday.plusDays(4).atTime(15, 45), summary, "A quiet week, Boss.", "Watch Thursday's expiry.", emptyList())
        val mine = listOf(review(java.time.LocalDate.of(2026, 10, 5)), review(java.time.LocalDate.of(2026, 9, 28)))
        val f = com.optionslab.app.ira.IraWeekly.file()!!
        Vault.writeFile(f, com.optionslab.ira.WeeklyReview.encodeAll(mine).toByteArray(Charsets.UTF_8))
        val old = listOf(review(java.time.LocalDate.of(2026, 9, 28), "From the backup."), review(java.time.LocalDate.of(2026, 9, 21)))
        val c = Backup.open(craft("IRABK3", 600_000, "Test-Passphrase-42!",
            JSONObject().put(com.optionslab.app.ira.IraWeekly.KEY, com.optionslab.ira.WeeklyReview.encodeAll(old))), pass.copyOf())
        Backup.restore(context, c)
        assertTrue("the phone's reviews file is kept", f.exists())
        val kept = com.optionslab.app.ira.IraWeekly.kept()
        assertEquals(listOf(java.time.LocalDate.of(2026, 10, 5), java.time.LocalDate.of(2026, 9, 28), java.time.LocalDate.of(2026, 9, 21)),
            kept.map { it.monday })
        assertEquals("A quiet week.", kept[1].summary)
        assertNull(SecurePrefs.getString(com.optionslab.app.ira.IraWeekly.KEY))
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
