package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * GitHub sync's evening backup (TODO G2, 13 Oct): sealed with the key kept from the backup passphrase, it is an ordinary
 * IRABK3 backup - it opens with the passphrase and restores through the same path, everything switched off - and it never
 * carries the GitHub token, the passphrase's keys or the upload record. Every secret here is a made-up test value.
 */
class GitHubBackupTest : RobolectricTest() {
    private val pass = "Test-Passphrase-42!".toCharArray()
    private val salt = ByteArray(16) { it.toByte() }

    @Test fun theEveningBackupOpensWithThePassphraseAndCarriesNoTokenOrKey() = runBlocking {
        SecurePrefs.putAll(mapOf("s.capital" to 321_000.0, "github.token.pin" to "v2:not-a-real-sealed-token",
            "github.token.worker" to "w1:00:00", "github.repo" to "mevishalsonawane-ai/IraAlgo", "github.key.live" to "1000:00:00", "github.key.backup" to "00:00", "github.last" to "1|1|2|backup|"))
        val pine = File(context.filesDir, "pine.vault")
        Vault.writeFile(pine, JSONArray().put(JSONObject().put("name", "t").put("auto", JSONObject().put("on", true))).toString().toByteArray())
        val key = Backup.stretch(pass.copyOf(), salt)
        val bytes = Backup.createSealed(context, key, salt)
        assertEquals("IRABK3", String(bytes.copyOfRange(0, 6)))
        assertEquals(2, Backup.version(bytes))
        val c = Backup.open(bytes, pass.copyOf())
        val prefs = c.json.getJSONObject("prefs")
        assertEquals(321_000.0, prefs.getDouble("s.capital"), 0.0)
        for (k in listOf("github.token.pin", "github.token.worker", "github.repo", "github.key.live", "github.key.backup", "github.last"))
            assertFalse("$k was backed up", prefs.has(k))
        try { Backup.open(bytes, "Other-Passphrase-42!".toCharArray()); fail() } catch (_: Backup.WrongPin) {}
        // Restored as a file would be: the Pine script comes back switched off, and the disarm flag is set first.
        Backup.restore(context, c)
        val back = JSONArray(String(Vault.readFileSteady(pine)!!))
        assertFalse(back.getJSONObject(0).getJSONObject("auto").getBoolean("on"))
        assertTrue(back.getJSONObject(0).getJSONObject("auto").getBoolean("profitLock"))
        assertTrue(SecurePrefs.getBoolean(Backup.DISARM, false))
        // This phone's token is still here, untouched by the restore.
        assertEquals("v2:not-a-real-sealed-token", SecurePrefs.getString("github.token.pin"))
    }

    @Test fun aMissingKeyIsRefused() = runBlocking {
        try { Backup.createSealed(context, ByteArray(0), salt); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun theDiagnosticsLineNamesNoSecret() {
        val fake = "github_pat_" + "TEST" + "q".repeat(60)
        SecurePrefs.putAll(mapOf("github.token.pin" to fake, "github.token.expiry" to "2027-01-10", "github.key.live" to "1000:00:00",
            "github.key.backup" to "00:00", "github.token.login" to "someone"))
        val line = GitHubSync.diagLine()
        assertTrue(line, line.startsWith("GitHub sync: token set, expires 2027-01-10"))
        assertFalse(line, line.contains("github_pat_"))
        assertFalse(line, line.contains("someone"))
    }
}
