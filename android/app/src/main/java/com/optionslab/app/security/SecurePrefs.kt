package com.optionslab.app.security

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Settings held in one vault file. Small, read once, cached in memory; every
 * write re-encrypts the whole map, which at this size costs nothing.
 */
object SecurePrefs {
    private lateinit var file: File
    private var cache: JSONObject? = null

    /**
     * The settings file exists but could not be decrypted. The app then fails
     * CLOSED: nothing is written over it (the PIN verifier and failure count
     * live here), and the lock screen offers only "try again" or "erase".
     */
    @Volatile var unreadable: Boolean = false
        private set

    fun init(context: Context) {
        file = File(context.noBackupFilesDir, "prefs.vault")
    }

    @Synchronized
    private fun map(): JSONObject {
        cache?.let { return it }
        val loaded = try {
            Vault.readFileSteady(file)?.let { JSONObject(String(it, Charsets.UTF_8)) } ?: JSONObject()
        } catch (_: Exception) {
            // Tampered, or the key is gone. Trust none of it - and do not replace it
            // with an empty map, which would erase the PIN and let anyone set a new one.
            unreadable = true
            JSONObject()
        }
        cache = loaded
        return loaded
    }

    /** Drop the cache and read the file again (the "try again" on the unreadable-vault screen). */
    @Synchronized fun reload(): Boolean { cache = null; unreadable = false; map(); return !unreadable }

    @Synchronized
    private fun save() {
        map()
        if (unreadable) return   // never write over a vault we could not read
        Vault.writeFile(file, map().toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized fun getString(k: String, d: String? = null): String? = map().optString(k, "").ifEmpty { d }
    @Synchronized fun getBoolean(k: String, d: Boolean): Boolean = if (map().has(k)) map().optBoolean(k, d) else d
    @Synchronized fun getInt(k: String, d: Int): Int = if (map().has(k)) map().optInt(k, d) else d
    @Synchronized fun getLong(k: String, d: Long): Long = if (map().has(k)) map().optLong(k, d) else d
    @Synchronized fun getDouble(k: String, d: Double): Double = if (map().has(k)) map().optDouble(k, d) else d

    @Synchronized fun put(k: String, v: Any?) {
        if (v == null) map().remove(k) else map().put(k, v)
        save()
    }

    @Synchronized fun putAll(values: Map<String, Any?>) {
        val m = map()
        for ((k, v) in values) if (v == null) m.remove(k) else m.put(k, v)
        save()
    }

    /** Every key and value (the backup); callers filter out what must never leave the vault. */
    @Synchronized fun snapshot(): Map<String, Any?> = map().let { m -> m.keys().asSequence().associateWith { m.opt(it) } }

    @Synchronized fun wipe() {
        cache = JSONObject()
        unreadable = false
        file.delete()
    }
}
