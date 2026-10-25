package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 25 Oct 2026 (Boss: "remove telegram full feature"): what the removed Telegram feature left on the phone is deleted ONCE, on the
 * first start after the update - its login's database and files (the "tdlib" folder), the Signal tester's message log, and its
 * encrypted settings (the "telegram." keys: the api keys, the database key, the login and capture switches). Off the main thread,
 * after the open positions are restored and watched ([PositionsFirst]); each step logged in the diagnostics (what, never a value).
 * The removed paper arm's book is NOT deleted here: [NtfyArm] moves its open trades into its own book first, then deletes it.
 * A step that fails is tried again on the next start (the done flag is set only once every step went).
 */
object RemovedFeatureCleanup {
    const val K_DONE = "cleanup.telegram.v1"
    /** The encrypted settings' prefix of the removed login. */
    internal const val PREFIX = "telegram."
    internal val FILES = listOf("tdlib", "signals.vault")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { PositionsFirst.awaitPositions() }
            runCatching { run(app.noBackupFilesDir) }.onFailure { e -> runCatching { Diag.record("cleanup", "removed feature's data: ${e.javaClass.simpleName}; tried again next start") } }
        }
    }

    /** The cleanup in [dir] (the no-backup folder). True when everything is gone (now, or on an earlier start). */
    internal fun run(dir: File): Boolean {
        if (SecurePrefs.getBoolean(K_DONE, false)) return true
        var ok = true
        for (name in FILES) {
            val f = File(dir, name)
            if (!f.exists()) continue
            val gone = if (f.isDirectory) f.deleteRecursively() else f.delete()
            ok = ok && gone
            runCatching { Diag.record("cleanup", "removed Telegram data: $name ${if (gone) "deleted" else "could not be deleted"}") }
        }
        val keys = SecurePrefs.keys(PREFIX)
        if (keys.isNotEmpty()) {
            SecurePrefs.putAll(keys.associateWith { null })
            runCatching { Diag.record("cleanup", "removed Telegram data: ${keys.size} encrypted setting(s) deleted") }
        }
        if (ok) SecurePrefs.put(K_DONE, true)
        return ok
    }
}
