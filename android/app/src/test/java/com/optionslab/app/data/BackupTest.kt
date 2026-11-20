package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // 19 Nov (review): a restore's books never run Boss's one-time automatic-stops choice (it would switch his stops off with no PIN).
        assertTrue(SecurePrefs.getBoolean(com.optionslab.ira.ArmAutoStops.MIGRATION_KEY, false))
        // 20 Nov 2026: and the card is told why the choice was not applied here (it had not run on this "new phone").
        assertEquals(com.optionslab.ira.ArmAutoStops.NotApplied.RESTORE.key, SecurePrefs.getString(com.optionslab.ira.ArmAutoStops.NOT_APPLIED_KEY))
        assertEquals(com.optionslab.ira.ArmAutoStops.NotApplied.RESTORE, ArmAutoStopsHost.view.value.notApplied)
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

    /** 20 Nov 2026: a restore on a phone where Boss's automatic-stops choice had run says nothing (his switches stand). */
    @Test fun aRestoreWhereTheChoiceHadRunLeavesNoNotice() = runBlocking {
        seedPhone()
        val bytes = Backup.create(context, pass.copyOf())
        SecurePrefs.putAll(mapOf(com.optionslab.ira.ArmAutoStops.MIGRATION_KEY to true, com.optionslab.ira.ArmAutoStops.CUTOFF_MIGRATION_KEY to true))
        ArmAutoStopsHost.resetForTest()
        Backup.restore(context, Backup.open(bytes, pass.copyOf()))
        assertNull(SecurePrefs.getString(com.optionslab.ira.ArmAutoStops.NOT_APPLIED_KEY))
        assertNull(ArmAutoStopsHost.view.value.notApplied)
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
        // A new phone (no book here) holds at the default of 1 lot (research X1, 8 Oct).
        restoreWith(here = null, backup = 3).let { o -> assertEquals(1, o.getInt("liqLots")); assertEquals(3, o.getInt("liqLotsAsk")) }
        // A backup from before the setting reads as the default, 1: nothing waits.
        restoreWith(here = 1, backup = null).let { o -> assertEquals(1, o.getInt("liqLots")); assertFalse(o.has("liqLotsAsk")) }
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
        // The restart that follows a restore (20 Oct: until then only the restore writes its files): the merge runs after it.
        Vault.thaw()
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

    /** 20 Nov 2026 (audit, B5 item 2): with a live position open a restore is refused, and nothing on the phone is touched. */
    @Test fun aRestoreIsRefusedWhileALivePositionIsOpenAndNothingIsWritten() = runBlocking {
        seedPhone()
        val bytes = Backup.create(context, pass.copyOf())
        // The phone moved on after the backup: its ORB book now differs from the backup's.
        Vault.writeFile(orbFile(), JSONObject().put("armed", JSONObject().put("arm2", true)).put("liqLots", 2).toString().toByteArray())
        val orbBefore = String(Vault.readFile(orbFile())!!)
        try {
            Backup.testLiveHeld = { listOf(Backup.HELD_ORB) }
            val e = try { Backup.restore(context, Backup.open(bytes, pass.copyOf())); null } catch (x: Backup.LiveHeld) { x }
            assertNotNull("refused", e)
            assertTrue(e!!.message!!, e.message!!.startsWith("Not restored: Liquidity 15+5 or an ORB arm holds a live position. "))
            assertTrue(e.message!!.contains("Nothing on this phone was changed"))
            assertEquals("the phone's book untouched", orbBefore, String(Vault.readFile(orbFile())!!))
            assertFalse("not even the disarm flag", SecurePrefs.getBoolean(Backup.DISARM, false))
            // Closed: the same restore goes through.
            Backup.testLiveHeld = { emptyList() }
            Backup.restore(context, Backup.open(bytes, pass.copyOf()))
            assertTrue(SecurePrefs.getBoolean(Backup.DISARM, false))
        } finally { Backup.testLiveHeld = null }
    }

    @Test fun theRefusalNamesEveryBookHoldingALivePosition() {
        assertEquals("Not restored: A; B. A restore replaces the books that record them. Nothing on this phone was changed. " +
            "Restore once nothing is open (after the market's close is safest).", Backup.liveHeldWords(listOf("A", "B")))
        // A fresh phone holds nothing live: nothing refuses.
        assertEquals(emptyList<String>(), Backup.liveHeld())
        // Review: the app's start has begun but not yet read the position books - the hints cannot tell yet: refused.
        assertEquals(listOf(Backup.HELD_START), Backup.liveHeld(started = true, startRead = false))
        assertEquals(emptyList<String>(), Backup.liveHeld(started = true, startRead = true))
    }

    /** 20 Nov 2026 (audit, B5 item 3): a restore never lowers the account stop's drawdown or month figure. */
    @Test fun aRestoreKeepsTheAccountStopsWorseFigure() = runBlocking {
        val today = Market.today()
        val early = today.minusDays(3)
        // The backup was made when only one losing day was recorded.
        SecurePrefs.putAll(mapOf(Backup.ACCOUNT_STOP_DAYS to com.optionslab.ira.AccountStop.encode(
            listOf(com.optionslab.ira.AccountStop.Day(early, -2_000.0)))))
        val bytes = Backup.create(context, pass.copyOf())
        // Since then this phone recorded a bigger loss.
        val phone = listOf(com.optionslab.ira.AccountStop.Day(early, -2_000.0), com.optionslab.ira.AccountStop.Day(today.minusDays(1), -9_000.0))
        SecurePrefs.putAll(mapOf(Backup.ACCOUNT_STOP_DAYS to com.optionslab.ira.AccountStop.encode(phone)))
        Backup.restore(context, Backup.open(bytes, pass.copyOf()))
        assertEquals("the phone's days kept: the drawdown is not lowered", phone,
            com.optionslab.ira.AccountStop.decode(SecurePrefs.getString(Backup.ACCOUNT_STOP_DAYS)))
        // A new phone with no days takes the backup's.
        SecurePrefs.putAll(mapOf(Backup.ACCOUNT_STOP_DAYS to null))
        Backup.restore(context, Backup.open(bytes, pass.copyOf()))
        assertEquals(listOf(com.optionslab.ira.AccountStop.Day(early, -2_000.0)),
            com.optionslab.ira.AccountStop.decode(SecurePrefs.getString(Backup.ACCOUNT_STOP_DAYS)))
    }

    /** 20 Nov 2026 (B5 item 3, review P4): this phone's account-stop days unreadable - refused, nothing written (not even the disarm flag). */
    @Test fun aRestoreIsRefusedWhenThePhonesAccountStopDaysCannotBeRead() = runBlocking {
        seedPhone()
        val phone = listOf(com.optionslab.ira.AccountStop.Day(Market.today().minusDays(1), -9_000.0))
        SecurePrefs.putAll(mapOf(Backup.ACCOUNT_STOP_DAYS to com.optionslab.ira.AccountStop.encode(phone)))
        val bytes = Backup.create(context, pass.copyOf())
        val orbBefore = String(Vault.readFile(orbFile())!!)
        try {
            Backup.testAccountStopRead = { throw java.io.IOException("test: the vault read failed") }
            val e = try { Backup.restore(context, Backup.open(bytes, pass.copyOf())); null } catch (x: Backup.AccountStopUnread) { x }
            assertNotNull("refused", e)
            assertEquals(Backup.ACCOUNT_STOP_UNREAD, e!!.message)
            assertTrue(e is Backup.Refused)
            assertFalse("not even the disarm flag", SecurePrefs.getBoolean(Backup.DISARM, false))
            assertEquals("the phone's book untouched", orbBefore, String(Vault.readFile(orbFile())!!))
            assertEquals("the days untouched", phone, com.optionslab.ira.AccountStop.decode(SecurePrefs.getString(Backup.ACCOUNT_STOP_DAYS)))
        } finally { Backup.testAccountStopRead = null }
    }

    /** Review P4: a restore with nothing to keep (both empty) never writes over the phone's value. */
    @Test fun nothingToKeepWritesNothingOverTheDays() = runBlocking {
        SecurePrefs.putAll(mapOf(Backup.ACCOUNT_STOP_DAYS to ""))
        val bytes = Backup.create(context, pass.copyOf())
        val raw = "garbage the decoder skips"
        SecurePrefs.putAll(mapOf(Backup.ACCOUNT_STOP_DAYS to raw))
        Backup.restore(context, Backup.open(bytes, pass.copyOf()))
        assertEquals(raw, SecurePrefs.getString(Backup.ACCOUNT_STOP_DAYS))
    }

    /**
     * Review C1 (real wiring, no seam): a fresh phone holds nothing; the arms' live book being read or recovered is said as exactly that
     * (driven through ArmLive's own unreadable book in ArmLiveTest); the words for it name the Zerodha log-in.
     */
    @Test fun theRealHintsOnAFreshPhoneHoldNothingAndTheRecoveringBookHasItsOwnWords() {
        assertEquals(null, Backup.testLiveHeld)
        assertEquals(emptyList<String>(), Backup.liveHeld())
        assertTrue(Backup.HELD_ARMS_BOOK.contains("being read or recovered") && Backup.HELD_ARMS_BOOK.contains("log in to Zerodha"))
        assertFalse(Backup.HELD_ARMS_BOOK.contains("are open"))
    }
}
