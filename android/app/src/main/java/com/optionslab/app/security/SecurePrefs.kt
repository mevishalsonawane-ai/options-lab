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

    fun init(context: Context) {
        file = File(context.noBackupFilesDir, "prefs.vault")
    }

    @Synchronized
    private fun map(): JSONObject {
        cache?.let { return it }
        val loaded = try {
            Vault.readFile(file)?.let { JSONObject(String(it, Charsets.UTF_8)) } ?: JSONObject()
        } catch (_: Exception) {
            // Unreadable means tampered or the key was destroyed; start clean
            // rather than trusting any part of it.
            JSONObject()
        }
        cache = loaded
        return loaded
    }

    @Synchronized
    private fun save() { Vault.writeFile(file, map().toString().toByteArray(Charsets.UTF_8)) }

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

    @Synchronized fun wipe() {
        cache = JSONObject()
        file.delete()
    }
}
