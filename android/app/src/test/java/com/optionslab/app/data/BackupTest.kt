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

    // ---- 22 Nov 2026 (B6 review): a restore never loosens a live guard, never switches a live copy on, never lifts a reached stop ----------

    private val today get() = Market.today()

    /** Item 1: through create / open / restore - the peak never lowered, a park never lifted, the brain only tightened, the private ones kept. */
    @Test fun aRestoreNeverLoosensALiveGuardNorTakesTheLiveCopiesSwitchesOrTheFlowModes() = runBlocking {
        val pauseOff = "news=OFF;traps=SHADOW;feed=SHADOW;bigmove=SHADOW"
        SecurePrefs.putAll(mapOf(Backup.LIVE_PEAK to 90_000.0, SmartWorkers.K_PARKED to "liquidity", SmartWorkers.K_PAUSED to "",
            SmartWorkers.K_PAUSE to pauseOff, SmartWorkers.K_REACT to "*:data=OFF",
            ArmLive.K_SWITCHES to """{"r51":true}""", ArmLive.K_SHADOW to """{"r51":false}""", "flow.modes" to "orb=SHADOW@55",
            "brain.reviewedOn" to today.toString()))
        val bytes = Backup.create(context, pass.copyOf())
        val carried = Backup.open(bytes, pass.copyOf()).json.getJSONObject("prefs")
        for (k in listOf(ArmLive.K_SWITCHES, ArmLive.K_SHADOW, "flow.modes", "brain.reviewedOn")) assertFalse("$k was backed up", carried.has(k))
        assertTrue(carried.has(Backup.LIVE_PEAK) && carried.has(SmartWorkers.K_PARKED))
        // The phone since: a higher peak; Liquidity's park lifted (Boss, with his PIN) and Hero parked; Solo paused; news windows and the
        // data reaction back on; R51's live copy switched off and in shadow; the order flow on CONFIRM.
        SecurePrefs.putAll(mapOf(Backup.LIVE_PEAK to 150_000.0, SmartWorkers.K_PARKED to "hero", SmartWorkers.K_PAUSED to "solo",
            SmartWorkers.K_PAUSE to "news=ON;traps=SHADOW;feed=SHADOW;bigmove=SHADOW", SmartWorkers.K_REACT to "*:data=ACT",
            ArmLive.K_SWITCHES to """{"r51":false}""", ArmLive.K_SHADOW to """{"r51":true}""", "flow.modes" to "orb=CONFIRM@55",
            "brain.reviewedOn" to null))
        Backup.restore(context, Backup.open(bytes, pass.copyOf()))
        assertEquals("never lowered", 150_000.0, SecurePrefs.getDouble(Backup.LIVE_PEAK, 0.0), 0.0)
        assertEquals("only ever added", "hero,liquidity", SecurePrefs.getString(SmartWorkers.K_PARKED))
        assertEquals("solo", SecurePrefs.getString(SmartWorkers.K_PAUSED))
        assertEquals(com.optionslab.ira.MarketBrain.Mode.ON,
            com.optionslab.ira.MarketBrain.decode(SecurePrefs.getString(SmartWorkers.K_PAUSE)).mode(com.optionslab.ira.MarketBrain.Cause.NEWS))
        assertEquals(com.optionslab.ira.Findings.Mode.ACT,
            com.optionslab.ira.Findings.decode(SecurePrefs.getString(SmartWorkers.K_REACT)).mode("orb", com.optionslab.ira.Findings.Reaction.DATA_UNRELIABLE))
        assertEquals("""{"r51":false}""", SecurePrefs.getString(ArmLive.K_SWITCHES))
        assertEquals("""{"r51":true}""", SecurePrefs.getString(ArmLive.K_SHADOW))
        assertEquals("orb=CONFIRM@55", SecurePrefs.getString("flow.modes"))
        assertNull("this phone's review still runs today", SecurePrefs.getString("brain.reviewedOn"))
        // A phone whose peak is lower (or has none) takes the backup's: never lower than either.
        SecurePrefs.putAll(mapOf(Backup.LIVE_PEAK to 50_000.0))
        Backup.restore(context, Backup.open(bytes, pass.copyOf()))
        assertEquals(90_000.0, SecurePrefs.getDouble(Backup.LIVE_PEAK, 0.0), 0.0)
        SecurePrefs.putAll(mapOf(Backup.LIVE_PEAK to null))
        Backup.restore(context, Backup.open(bytes, pass.copyOf()))
        assertEquals(90_000.0, SecurePrefs.getDouble(Backup.LIVE_PEAK, 0.0), 0.0)
    }

    /** Item 1: a file made before these keys were private (or crafted) still never brings them back. */
    @Test fun anOlderOrCraftedFileCannotSwitchALiveCopyOnOrSkipTodaysReview() {
        SecurePrefs.putAll(mapOf(ArmLive.K_SWITCHES to """{"r72":false}""", "flow.modes" to "orb=CONFIRM@55", Backup.LIVE_PEAK to 120_000.0))
        val evil = JSONObject().put(ArmLive.K_SWITCHES, """{"r72":true}""").put(ArmLive.K_SHADOW, """{"r72":false}""")
            .put("flow.modes", "orb=OFF@55").put("brain.reviewedOn", today.toString()).put(Backup.LIVE_PEAK, 1.0)
            .put(SmartWorkers.K_PARKED, "").put("timeline.once.stepTrail", true)
        Backup.restore(context, Backup.open(craft("IRABK3", 600_000, "Test-Passphrase-42!", evil), pass.copyOf()))
        assertEquals("""{"r72":false}""", SecurePrefs.getString(ArmLive.K_SWITCHES))
        assertNull(SecurePrefs.getString(ArmLive.K_SHADOW))
        assertEquals("orb=CONFIRM@55", SecurePrefs.getString("flow.modes"))
        assertNull(SecurePrefs.getString("brain.reviewedOn"))
        assertFalse(SecurePrefs.getBoolean("timeline.once.stepTrail", false))
        assertEquals(120_000.0, SecurePrefs.getDouble(Backup.LIVE_PEAK, 0.0), 0.0)
    }

    /** A closed live trade of the arms' book, as OrbArms writes its row. */
    private fun liveRow(arm: String, day: java.time.LocalDate, entry: Double, exit: Double, qty: Int, id: String = "", h: Int = 10) = JSONObject()
        .put("arm", arm).put("symbol", "BANKNIFTY26NOV52000CE").put("right", "CE").put("qty", qty).put("entry", entry)
        .put("entryTime", day.atTime(h, 0).toString()).put("signalBar", day.atTime(h - 1, 55).toString()).put("entryOrderId", id)
        .put("exit", exit).put("exitTime", day.atTime(h + 1, 0).toString()).put("why", "stop").put("charges", 0.0).put("live", true)

    private fun book(vararg rows: JSONObject) = JSONObject().put("armed", JSONObject()).put("auto", JSONObject()).put("liveOk", JSONObject())
        .put("positions", JSONArray().apply { rows.forEach { put(it) } })

    /**
     * Item 2: a stop already reached stays reached after restoring an older backup - Liquidity's live stop rule and the arms' live-copies
     * account stop are worked out from the arms' book's closed live trades, and the restore keeps this phone's.
     */
    @Test fun aRestoreAfterTheLiveStopIsReachedKeepsItReached() = runBlocking {
        LiquidityTest.setStateForTest(com.optionslab.ira.LiquidityFreeze.State())
        ArmAutoStopsHost.resetForTest()
        try {
            // The backup: made before the loss (one small paper trade).
            com.optionslab.app.testing.AutomationSupport.orbState(context, book(liveRow("liquidity15", today.minusDays(3), 100.0, 110.0, 30).put("live", false)))
            val bytes = Backup.create(context, pass.copyOf())
            // Since then a live loss of Rs 41,000: both stops reached on this phone.
            val loss = liveRow("liquidity15", today.minusDays(1), 500.0, 90.0, 100, id = "251121000123456")
            com.optionslab.app.testing.AutomationSupport.orbState(context, book(liveRow("liquidity15", today.minusDays(3), 100.0, 110.0, 30).put("live", false), loss))
            OrbArms.view()
            assertTrue("reached before the restore", OrbArms.liquidityStopHint.stopped)
            assertNotNull(ArmLive.accountStopNow().second)
            Backup.restore(context, Backup.open(bytes, pass.copyOf()))
            // The restart that follows: the restored book read afresh.
            Vault.thaw(); OrbArms.forgetMemoryForTest()
            OrbArms.view()
            assertTrue("Liquidity's live stop rule still reached", OrbArms.liquidityStopHint.stopped)
            val (words, refusal) = ArmLive.accountStopNow()
            assertNotNull("the arms' live-copies account stop still reached: $words", refusal)
            assertTrue(words, words.contains("REACHED"))
            val rows = JSONObject(String(Vault.readFile(orbFile())!!)).getJSONArray("positions")
            assertEquals("the backup's row and this phone's live trade", 2, rows.length())
            assertEquals("251121000123456", rows.getJSONObject(1).getString("entryOrderId"))
            assertEquals(0, JSONObject(String(Vault.readFile(orbFile())!!)).getJSONObject("armed").length())
        } finally { LiquidityTest.resetForTest(); OrbArms.forgetMemoryForTest() }
    }

    /** Item 2: a backup with no arms' book still keeps this phone's closed live trades (its book disarmed, its parkings as they are). */
    @Test fun aBackupWithNoArmsBookKeepsThePhonesClosedLiveTrades() {
        val loss = liveRow("liquidity15", today.minusDays(1), 500.0, 90.0, 100, id = "251121000123456")
        Vault.writeFile(orbFile(), book(liveRow("liquidity15", today.minusDays(2), 100.0, 110.0, 30).put("live", false), loss,
            liveRow("liquidity5", today, 100.0, 120.0, 30, h = 13).apply { remove("exit"); put("exitTime", "") })
            .put("armed", JSONObject().put("liquidity", true)).put("liqParked", JSONArray().put("FINNIFTY")).put("liqLots", 2)
            .toString().toByteArray())
        Backup.restore(context, Backup.open(craft("IRABK3", 600_000, "Test-Passphrase-42!", JSONObject()), pass.copyOf()))
        val o = JSONObject(String(Vault.readFile(orbFile())!!))
        val rows = o.getJSONArray("positions")
        assertEquals("only the closed live trade", 1, rows.length())
        assertEquals("251121000123456", rows.getJSONObject(0).getString("entryOrderId"))
        assertEquals(0, o.getJSONObject("armed").length())
        assertEquals("FINNIFTY", o.getJSONArray("liqParked").getString(0))
        assertEquals(2, o.getInt("liqLots"))
        // No live trade here and none in the backup: the book goes, as before. (The restart that follows a restore: its files thawed.)
        Vault.thaw()
        Vault.writeFile(orbFile(), book(liveRow("orb", today, 100.0, 110.0, 30).put("live", false)).toString().toByteArray())
        Backup.restore(context, Backup.open(craft("IRABK3", 600_000, "Test-Passphrase-42!", JSONObject()), pass.copyOf()))
        assertFalse(orbFile().exists())
    }

    /** Item 2: this phone's arms' book there but unreadable - refused, nothing written (not even the disarm flag). */
    @Test fun anUnreadableArmsBookRefusesTheRestore() {
        orbFile().writeBytes(ByteArray(64) { 7 })
        val before = orbFile().readBytes()
        val e = try { Backup.restore(context, Backup.open(craft("IRABK3", 600_000, "Test-Passphrase-42!", JSONObject().put("s.capital", 1.0),
            JSONObject().put("f/orb.vault", b64("{}"))), pass.copyOf())); null } catch (x: Backup.OrbBookUnread) { x }
        assertNotNull("refused", e)
        assertEquals(Backup.ORB_UNREAD, e!!.message)
        assertTrue(e is Backup.Refused)
        assertFalse(SecurePrefs.getBoolean(Backup.DISARM, false))
        assertTrue("the book untouched", before.contentEquals(orbFile().readBytes()))
        assertEquals(0.0, SecurePrefs.getDouble("s.capital", 0.0), 0.0)
    }

    /** Item 2: the merge itself - this phone's row wins a trade in both (by order id, or arm, option and entry time), the rest in entry order. */
    @Test fun thePhonesClosedLiveTradesAreMergedInEntryOrder() {
        val d = java.time.LocalDate.of(2026, 11, 18)
        val theirs = book(liveRow("orb", d, 100.0, 120.0, 30, h = 9).put("live", false),
            liveRow("liquidity15", d, 100.0, 50.0, 30, id = "A1", h = 11).apply { remove("exit"); put("exitTime", "") },   // open in the backup
            liveRow("liquidity5", d, 100.0, 50.0, 30, h = 14),
            liveRow("orb", d.plusDays(1), 100.0, 90.0, 30, h = 10))
        val here = listOf(liveRow("liquidity15", d, 100.0, 20.0, 30, id = "A1", h = 11),          // closed here at a loss: wins by its id
            liveRow("liquidity5", d, 100.0, 10.0, 30, h = 14),                                     // same arm, option, entry time: wins
            liveRow("liquidity15", d, 100.0, 40.0, 30, id = "B2", h = 12),                         // missing in the backup: put at 12:00
            liveRow("liquidity15", d.minusDays(5), 100.0, 40.0, 30, id = "C3", h = 10))            // older than every row: first
        val theirsText = theirs.toString()
        val rows = Backup.withClosedLive(theirs, here).getJSONArray("positions")
        assertEquals(6, rows.length())
        assertEquals(listOf("C3", "", "A1", "B2", "", ""), (0 until 6).map { rows.getJSONObject(it).getString("entryOrderId") })
        assertEquals(20.0, rows.getJSONObject(2).getDouble("exit"), 0.0)
        assertEquals(10.0, rows.getJSONObject(4).getDouble("exit"), 0.0)
        assertFalse(rows.getJSONObject(1).getBoolean("live"))
        // Nothing here: the backup's book as it was. Paper and open rows here are never taken.
        assertEquals(4, Backup.withClosedLive(JSONObject(theirsText), emptyList()).getJSONArray("positions").length())
        assertEquals(listOf("A1"), Backup.closedLive(book(here[0], liveRow("orb", d, 1.0, 2.0, 1).put("live", false),
            liveRow("orb", d, 1.0, 2.0, 1, id = "Z").apply { remove("exit") })).map { it.getString("entryOrderId") })
    }

    /** Item 9c: a restore never makes a fresh phone look like one that ran an earlier build (the cheaper strike's one-time entry). */
    @Test fun aRestoredPhoneIsNeverTakenForOneThatRanAnEarlierBuild() = runBlocking {
        assertFalse("a fresh phone", CheaperStrike.ranBefore(context))
        seedPhone()
        assertTrue("an arms' book on a phone never restored: it ran before", CheaperStrike.ranBefore(context))
        val bytes = Backup.create(context, pass.copyOf())
        SecurePrefs.wipe(); orbFile().delete(); pineFile().delete()
        assertFalse(CheaperStrike.ranBefore(context))
        Backup.restore(context, Backup.open(bytes, pass.copyOf()))
        // The restore wrote the 19 Nov choice's mark and the arms' book: neither counts now.
        assertTrue(SecurePrefs.getBoolean(com.optionslab.ira.ArmAutoStops.MIGRATION_KEY, false))
        assertTrue(orbFile().exists())
        assertFalse(CheaperStrike.ranBefore(context))
        assertFalse(CheaperStrike.onceDue(never = true, ranBefore = CheaperStrike.ranBefore(context)))
        // The arms' live copies' book (never in a backup) still says it ran before; the mark itself never travels.
        File(context.noBackupFilesDir, "arm_live.vault").writeBytes(byteArrayOf(1))
        assertTrue(CheaperStrike.ranBefore(context))
        assertFalse(com.optionslab.ira.Upkeep.carried(CheaperStrike.K_RESTORED))
    }

    /** Item 9d: the first choice ran (four off) but not its cutoff step - the card says exactly that, never "not applied". */
    @Test fun aRestoreThatCutTheChoiceShortSaysWhichStopIsStillOn() = runBlocking {
        seedPhone()
        val bytes = Backup.create(context, pass.copyOf())
        val fourOff = com.optionslab.ira.ArmAutoStops.migrate(com.optionslab.ira.ArmAutoStops.State(), 1L, null)!!
            .let { it.copy(on = it.on + (com.optionslab.ira.ArmAutoStops.Rule.JARVIS_CUTOFF to true), cutoffMigrated = false) }
        SecurePrefs.putAll(com.optionslab.ira.ArmAutoStops.encode(fourOff))
        ArmAutoStopsHost.resetForTest(fourOff)
        try {
            Backup.restore(context, Backup.open(bytes, pass.copyOf()))
            val n = ArmAutoStopsHost.view.value.notice()!!
            assertTrue(n, n.startsWith("Your 19 Nov choice (all of these off) was applied on this phone only in part: a backup was restored before its last step"))
            assertTrue(n, n.contains("Jarvis's losing-arm cutoff stays on."))
            assertFalse(n, n.contains("not applied"))
            assertEquals(com.optionslab.ira.ArmAutoStops.NotApplied.CUT_SHORT.key, SecurePrefs.getString(com.optionslab.ira.ArmAutoStops.NOT_APPLIED_KEY))
        } finally { ArmAutoStopsHost.resetForTest() }
    }

    // ---- 22 Nov 2026 (B6 review, second look) ------------------------------------------------------------------------------------------

    private fun stratFile() = File(context.filesDir, "strategies.vault")

    /** A strategies' book as Strategies saves it, with [stops] (its stop fields) on it. */
    private fun stratBook(vararg stops: Pair<String, Any>) = JSONObject().put("defs", JSONArray()).put("runs", JSONObject())
        .put("history", JSONArray()).put("brokerIds", JSONObject()).put("log", JSONArray()).put("nextRunId", 4).put("nextStrategyId", 7)
        .apply { stops.forEach { (k, v) -> put(k, v) } }

    /** This phone's strategies' book [book], on disk and read by Strategies (its memory as the app's would be). */
    private fun phoneStrategies(book: JSONObject) {
        Vault.writeFile(stratFile(), book.toString().toByteArray()); Strategies.forgetCacheForTest()
        runBlocking { Strategies.stoppedKept() }
    }

    /** A crafted backup holding [strategies] (null: no strategies' book in it) and [prefs]. */
    private fun backupWith(strategies: JSONObject?, prefs: JSONObject = JSONObject(), orb: JSONObject? = null) = Backup.open(craft("IRABK3", 600_000,
        "Test-Passphrase-42!", prefs, JSONObject().apply { strategies?.let { put("f/strategies.vault", b64(it.toString())) }
            orb?.let { put("f/orb.vault", b64(it.toString())) } }), pass.copyOf())

    /** The restart after a restore: the restored files read afresh. */
    private fun restarted() { Vault.thaw(); Strategies.forgetCacheForTest() }

    /** Item 1: today's Stop for today on each side, with its reason, time and the MCX sale, is never lifted by an older backup. */
    @Test fun aRestoreNeverLiftsTodaysStopForTodayOnEitherSide() = runBlocking {
        val t = today.toString()
        phoneStrategies(stratBook("stoppedDay" to t, "stoppedWhy" to "boss", "stoppedAt" to "10:15", "stopSides" to 1,
            "mcxStoppedDay" to t, "mcxStoppedWhy" to "tile", "mcxStoppedAt" to "11:00", "mcxStopSells" to false))
        val r = Backup.restore(context, backupWith(stratBook("stoppedDay" to today.minusDays(1).toString(), "stoppedWhy" to "loss", "stopSides" to 1)))
        restarted()
        assertEquals(com.optionslab.ira.DayStop.Kept(com.optionslab.ira.DayStop.Why.BOSS, "10:15"), Strategies.stoppedKept())
        assertEquals(com.optionslab.ira.DayStop.Kept(com.optionslab.ira.DayStop.Why.TILE, "11:00"), Strategies.mcxStoppedKept())
        assertFalse("its sale kept as set", Strategies.mcxStopSells())
        assertTrue(r.notes.toString(), r.notes.any { it.startsWith("Today's Stop for today on the index side is kept as this phone had it (since 10:15)") })
        assertTrue(r.notes.toString(), r.notes.any { it.startsWith("Today's Stop for today on the commodity (MCX) side is kept") })
        // The restored book is the backup's otherwise (its counters).
        assertEquals(7, JSONObject(String(Vault.readFile(stratFile())!!)).getLong("nextStrategyId"))
    }

    /** Item 1: the later stop wins on each side - the backup's when it is today's; and an older build's one stop counts for both sides. */
    @Test fun theLaterStopWinsOnEachSideAndAnOlderBooksOneStopCountsForBoth() = runBlocking {
        val t = today.toString()
        phoneStrategies(stratBook("stoppedDay" to today.minusDays(1).toString(), "stoppedWhy" to "boss", "stopSides" to 1))
        Backup.restore(context, backupWith(stratBook("stoppedDay" to t, "stoppedWhy" to "loss", "stoppedAt" to "09:40", "stopSides" to 1)))
        restarted()
        assertEquals(com.optionslab.ira.DayStop.Kept(com.optionslab.ira.DayStop.Why.LOSS, "09:40"), Strategies.stoppedKept())
        assertNull(Strategies.mcxStoppedKept())
        // An older build's book (no "stopSides"): its one stop is both sides' (a loss stop only the index side's), kept in this build's form.
        phoneStrategies(stratBook())
        val r = Backup.restore(context, backupWith(stratBook("stoppedDay" to t, "stoppedWhy" to "boss", "stoppedAt" to "09:30")))
        restarted()
        assertEquals(com.optionslab.ira.DayStop.Kept(com.optionslab.ira.DayStop.Why.BOSS, "09:30"), Strategies.stoppedKept())
        assertEquals(com.optionslab.ira.DayStop.Kept(com.optionslab.ira.DayStop.Why.BOSS, "09:30"), Strategies.mcxStoppedKept())
        assertEquals(1, JSONObject(String(Vault.readFile(stratFile())!!)).getInt("stopSides"))
        assertTrue("the backup's own stop is no news", r.notes.none { it.contains("Stop for today") })
        // This phone's older-build book stopped today, the backup's not stopped: both sides stay stopped.
        phoneStrategies(stratBook("stoppedDay" to t, "stoppedWhy" to "tile", "stoppedAt" to "12:05"))
        Backup.restore(context, backupWith(stratBook("stopSides" to 1)))
        restarted()
        assertEquals(com.optionslab.ira.DayStop.Kept(com.optionslab.ira.DayStop.Why.TILE, "12:05"), Strategies.stoppedKept())
        assertEquals(com.optionslab.ira.DayStop.Kept(com.optionslab.ira.DayStop.Why.TILE, "12:05"), Strategies.mcxStoppedKept())
        // Both today: this phone's (the newer - a loss stop Boss lifted with his PIN since the backup stays lifted).
        phoneStrategies(stratBook("stoppedDay" to t, "stoppedWhy" to "boss", "stoppedAt" to "12:00", "stopSides" to 1))
        Backup.restore(context, backupWith(stratBook("stoppedDay" to t, "stoppedWhy" to "loss", "stoppedAt" to "09:40", "stopSides" to 1)))
        restarted()
        assertEquals(com.optionslab.ira.DayStop.Kept(com.optionslab.ira.DayStop.Why.BOSS, "12:00"), Strategies.stoppedKept())
    }

    /**
     * A stop made while the restore runs is never saved (the book is frozen for it): it is taken from Strategies' memory, so the restart
     * still has it - on either side.
     */
    @Test fun aStopMadeWhileTheRestoreRunsIsKept() = runBlocking {
        phoneStrategies(stratBook("stopSides" to 1))
        Vault.freezeForRestore(listOf(stratFile()))
        Strategies.stopForToday(stopRunning = false, compromised = false, why = com.optionslab.ira.DayStop.Why.TILE)
        Strategies.stopCommodities(sell = false)
        Vault.thaw(); Strategies.forgetCacheForTest()
        assertNull("its save was dropped", Backup.indexStop(JSONObject(String(Vault.readFile(stratFile())!!))))
        assertNotNull("memory has it", Strategies.stopKeptHint())
        Backup.restore(context, backupWith(stratBook("stopSides" to 1)))
        restarted()
        assertEquals(com.optionslab.ira.DayStop.Why.TILE, Strategies.stoppedKept()?.why)
        assertEquals(com.optionslab.ira.DayStop.Why.BOSS, Strategies.mcxStoppedKept()?.why)
        assertFalse(Strategies.mcxStopSells())
        // The merge itself: memory's stop the newer on the same day; none in memory: the book as read.
        val read = stratBook("stoppedDay" to today.toString(), "stoppedWhy" to "boss", "stoppedAt" to "09:00", "stopSides" to 1)
        assertEquals("10:30", Backup.indexStop(Backup.withStopsInMemory(read, com.optionslab.ira.DayStop.Kept(com.optionslab.ira.DayStop.Why.TILE, "10:30"),
            null, true))?.at)
        assertEquals(7L, Backup.withStopsInMemory(read, com.optionslab.ira.DayStop.Kept(com.optionslab.ira.DayStop.Why.TILE), null, true)!!.getLong("nextStrategyId"))
        assertTrue(Backup.withStopsInMemory(read, null, null, true) === read)
    }

    /** Item 1: a backup with no strategies' book still keeps today's stop (an empty book that holds it); none today: removed, as before. */
    @Test fun aBackupWithNoStrategiesBookStillKeepsTodaysStop() = runBlocking {
        phoneStrategies(stratBook("stoppedDay" to today.toString(), "stoppedWhy" to "boss", "stoppedAt" to "10:00", "stopSides" to 1))
        Backup.restore(context, backupWith(null))
        restarted()
        assertEquals(com.optionslab.ira.DayStop.Kept(com.optionslab.ira.DayStop.Why.BOSS, "10:00"), Strategies.stoppedKept())
        assertTrue(Strategies.all().isEmpty())
        assertEquals("this phone's counters", 4, JSONObject(String(Vault.readFile(stratFile())!!)).getLong("nextRunId"))
        // Stopped only yesterday: nothing to keep, the book goes.
        phoneStrategies(stratBook("stoppedDay" to today.minusDays(1).toString(), "stopSides" to 1))
        Backup.restore(context, backupWith(null))
        assertFalse(stratFile().exists())
    }

    /** Item 1: this phone's strategies' book there but unreadable - refused, nothing written; one simply absent is no refusal. */
    @Test fun anUnreadableStrategiesBookRefusesTheRestore() {
        stratFile().writeBytes(ByteArray(64) { 5 })
        val before = stratFile().readBytes()
        val e = try { Backup.restore(context, backupWith(stratBook(), JSONObject().put("s.capital", 1.0))); null } catch (x: Backup.StrategiesBookUnread) { x }
        assertNotNull("refused", e)
        assertEquals(Backup.STRATEGIES_UNREAD, e!!.message)
        assertTrue(e is Backup.Refused)
        assertFalse(SecurePrefs.getBoolean(Backup.DISARM, false))
        assertTrue("the book untouched", before.contentEquals(stratFile().readBytes()))
        assertEquals(0.0, SecurePrefs.getDouble("s.capital", 0.0), 0.0)
        assertNull("entries go again", Backup.restoringRefusal())
        stratFile().delete()
        Backup.restore(context, backupWith(stratBook(), JSONObject().put("s.capital", 1.0)))
        assertEquals(1.0, SecurePrefs.getDouble("s.capital", 0.0), 0.0)
    }

    /** Item 2: the backup's open live trades that match nothing here are left out (named in the notice); its paper ones stay. */
    @Test fun theBackupsOpenLiveTradesThatMatchNothingHereAreLeftOut() {
        val open = liveRow("liquidity15", today, 100.0, 0.0, 30, id = "260122000123456", h = 11).apply { remove("exit"); put("exitTime", "") }
        val paperOpen = liveRow("orb", today, 100.0, 0.0, 30, h = 12).put("live", false).apply { remove("exit"); put("exitTime", "") }
        val closed = liveRow("liquidity5", today.minusDays(1), 100.0, 80.0, 30, id = "251121000999999")
        val r = Backup.restore(context, backupWith(null, orb = book(open, paperOpen, closed)))
        val rows = JSONObject(String(Vault.readFile(orbFile())!!)).getJSONArray("positions")
        assertEquals(listOf("", "251121000999999"), (0 until rows.length()).map { rows.getJSONObject(it).getString("entryOrderId") }.sorted())
        val note = r.notes.single { it.startsWith("Left out of the backup") }
        assertTrue(note, note.contains("BANKNIFTY26NOV52000CE (liquidity15, entered ${today} 11:00)"))
        assertTrue(note, note.contains("Check Kite"))
        // The same trade closed on this phone: this phone's row stands in for it, nothing left out.
        Vault.thaw()
        Vault.writeFile(orbFile(), book(liveRow("liquidity15", today, 100.0, 70.0, 30, id = "260122000123456", h = 11)).toString().toByteArray())
        val r2 = Backup.restore(context, backupWith(null, orb = book(open)))
        assertTrue(r2.notes.toString(), r2.notes.none { it.startsWith("Left out") })
        val kept = JSONObject(String(Vault.readFile(orbFile())!!)).getJSONArray("positions")
        assertEquals(1, kept.length())
        assertEquals(70.0, kept.getJSONObject(0).getDouble("exit"), 0.0)
        // A paper row is never taken for a live trade of this phone's (the backup's row must be live).
        assertEquals(2, Backup.withClosedLive(book(paperOpen.put("entryOrderId", "X1")), listOf(liveRow("orb", today, 1.0, 2.0, 1, id = "X1")))
            .getJSONArray("positions").length())
    }

    /** Item 7: a peak the restore raised above this phone's own is said (a payout-lowered one is lowered again with the PIN). */
    @Test fun aRaisedLivePeakIsSaid() {
        SecurePrefs.putAll(mapOf(Backup.LIVE_PEAK to 50_000.0))
        val r = Backup.restore(context, backupWith(null, JSONObject().put(Backup.LIVE_PEAK, 90_000.0)))
        assertEquals(90_000.0, SecurePrefs.getDouble(Backup.LIVE_PEAK, 0.0), 0.0)
        assertTrue(r.notes.toString(), r.notes.any { it.startsWith("The live account's drawdown peak was raised to the backup's (Rs 90,000, this phone had Rs 50,000)") })
        SecurePrefs.putAll(mapOf(Backup.LIVE_PEAK to 120_000.0))
        assertTrue(Backup.restore(context, backupWith(null, JSONObject().put(Backup.LIVE_PEAK, 90_000.0))).notes.none { it.contains("peak") })
    }

    /** Item 4: with the self-review's stop off (Boss's 19 Nov choice), R51 / R72 / Liquidity's parks and pauses stay as this phone has them. */
    @Test fun aRestoreKeepsBosssChoiceOnTheThreeArmsParksAndPauses() {
        val review = com.optionslab.ira.ArmAutoStops.Rule.SELF_REVIEW.prefKey
        val theirs = JSONObject().put(SmartWorkers.K_PARKED, "liquidity,solo").put(SmartWorkers.K_PAUSED, "liquidity")
        SecurePrefs.putAll(mapOf(review to false, SmartWorkers.K_PARKED to "hero", SmartWorkers.K_PAUSED to ""))
        Backup.restore(context, backupWith(null, theirs))
        assertEquals("the backup's other park still added", "hero,solo", SecurePrefs.getString(SmartWorkers.K_PARKED))
        assertEquals("", SecurePrefs.getString(SmartWorkers.K_PAUSED).orEmpty())
        // This phone's own park of it is kept (only Boss lifts it).
        SecurePrefs.putAll(mapOf(SmartWorkers.K_PARKED to "liquidity"))
        Backup.restore(context, backupWith(null, JSONObject().put(SmartWorkers.K_PARKED, "")))
        assertEquals("liquidity", SecurePrefs.getString(SmartWorkers.K_PARKED))
        // The review's stop on here: both sets, as before.
        SecurePrefs.putAll(mapOf(review to true, SmartWorkers.K_PARKED to "hero"))
        Backup.restore(context, backupWith(null, theirs))
        assertEquals("hero,liquidity,solo", SecurePrefs.getString(SmartWorkers.K_PARKED))
        // The switch itself is this phone's alone: a backup's never comes back.
        assertFalse(com.optionslab.ira.Upkeep.carried(review))
        SecurePrefs.putAll(mapOf(review to false, SmartWorkers.K_PARKED to "hero"))
        Backup.restore(context, backupWith(null, JSONObject(theirs.toString()).put(review, true)))
        assertEquals("hero,solo", SecurePrefs.getString(SmartWorkers.K_PARKED))
        assertFalse(SecurePrefs.getBoolean(review, true))
    }

    /** Item 3: from the restore's start no entry goes through the door (exits never come there); refused or failed, entries go again. */
    @Test fun noEntryGoesWhileARestoreRunsAndARefusedOneLetsThemGoAgain() {
        val o = com.optionslab.engine.Kite.Order("NIFTY26NOV25000CE", com.optionslab.engine.Kite.Side.BUY, 65, 65, "MIS", "MARKET", null, exchange = "NFO")
        assertNull(Backup.restoringRefusal())
        var atDoor: String? = null
        try {
            Backup.testLiveHeld = { atDoor = Broker.entryRecheck(o); listOf(Backup.HELD_ORB) }
            try { Backup.restore(context, backupWith(null)); fail() } catch (_: Backup.LiveHeld) {}
            assertEquals(Backup.RESTORING, atDoor)
            assertNull("refused: entries go again", Backup.restoringRefusal())
            assertNull(Broker.entryRecheck(o))
            // One that went through holds them until the restart.
            Backup.testLiveHeld = { emptyList() }
            Backup.restore(context, backupWith(null))
            assertEquals(Backup.RESTORING, Broker.entryRecheck(o))
            Backup.restoreEndedForTest()
            // One that failed after the books were frozen for it keeps them held (the app closes after a failure).
            Vault.thaw()
            Vault.testFailWrites = setOf("strategies.vault")
            val late = try { Backup.restore(context, backupWith(stratBook())); null } catch (x: Exception) { x }
            assertNotNull(late)
            assertEquals(Backup.RESTORING, Backup.restoringRefusal())
            Vault.testFailWrites = emptySet(); Vault.thaw(); Backup.restoreEndedForTest()
            // One that fails before anything was written (the settings vault unreadable) lets them go again.
            SecurePrefs.put("x", 1)
            Vault.destroy()
            assertFalse(SecurePrefs.reload())
            val failed = try { Backup.restore(context, backupWith(null)); null } catch (x: Exception) { x }
            assertNotNull(failed)
            assertNull(Backup.restoringRefusal())
        } finally { Backup.testLiveHeld = null; Vault.testFailWrites = emptySet(); Backup.restoreEndedForTest() }
    }
}
