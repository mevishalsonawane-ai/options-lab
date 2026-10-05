package com.optionslab.app.security

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Settings held in one vault file. Small, read once, cached in memory; every
 * write re-encrypts the whole map.
 *
 * Speed (2026-10-05): the encryption and the disk syncs of a write run OUTSIDE the lock the reads take. A write is a
 * Keystore operation (StrongBox on a Pixel, tens to hundreds of ms when the chip is busy) plus two fsyncs; held under
 * the read lock, every settings read on the screen's thread waited behind every background write. Writes still go to
 * disk one at a time and in order ([ioLock]); a write that finds a newer copy of the map already on disk is skipped (that
 * copy carries its values too), so an older map can never land over a newer one.
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
        synchronized(ioLock) { synchronized(this) { generation++; pending = false; writtenSeq = seq } }
        file = File(context.noBackupFilesDir, "prefs.vault")
    }

    /**
     * Held while the vault file is written, and taken BEFORE this object's lock whenever both are held (never the other
     * way round). Reads take only this object's lock, so they never wait on a write's encryption or disk sync.
     */
    private val ioLock = Any()
    /** Bumped (under this object's lock) each time the map is copied out to be written. */
    private var seq = 0L
    /** The newest copy on disk (guarded by [ioLock]). */
    private var writtenSeq = 0L

    // ---- write-behind, for writes that must not hold up an order -----------------------------------

    /** One background writer: saves run in order, each writing the whole (latest) map. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "prefs-writer").apply { isDaemon = true } }
    /** A save is queued and not yet written (guarded by this object's lock). */
    private var pending = false
    /** Bumped by [init] and [wipe]: a save queued before them is dropped, never written over the new state. */
    private var generation = 0

    /**
     * [putAll], with the values readable at once (every get sees them) and the vault write - a Keystore
     * encryption and two disk syncs - done on the background writer instead of the caller's thread.
     * A later [put]/[putAll] writes them too; [flush] waits for them.
     */
    @Synchronized fun putAllSoon(values: Map<String, Any?>) {
        val m = map()
        for ((k, v) in values) if (v == null) m.remove(k) else m.put(k, v)
        if (pending) return
        pending = true
        val gen = generation
        writer.execute {
            val snap = synchronized(this) { if (pending && gen == generation) snapshot(gen) else null } ?: return@execute
            runCatching { write(snap) }
        }
    }

    /** The last background write failed (e.g. a Keystore fault); the values stay in memory and the next save retries. */
    @Volatile private var writeFailed = false

    /**
     * Wait until every [putAllSoon] so far is on disk (never call it holding this object's lock).
     * False when the background write failed: a caller that needs the value durable writes it itself.
     */
    fun flush(): Boolean {
        if (synchronized(this) { pending }) runCatching { writer.submit(Runnable {}).get(30, java.util.concurrent.TimeUnit.SECONDS) }
        // A write another thread copied out and is still putting on disk counts as not yet written: wait for it.
        val want = synchronized(this) { seq }
        val written = synchronized(ioLock) { writtenSeq >= want }
        return written && !writeFailed && synchronized(this) { !pending }
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
    fun reload(): Boolean {
        flush()
        synchronized(this) { cache = null; unreadable = false; map(); return !unreadable }
    }

    /** The map as it is now, copied out to be written; null when nothing may be written (an unreadable vault). */
    private class Snap(val seq: Long, val gen: Int, val bytes: ByteArray)

    @Synchronized
    private fun snapshot(gen: Int = generation): Snap? {
        map()
        if (unreadable) return null   // never write over a vault we could not read
        pending = false               // this write carries every value set so far
        seq++
        return Snap(seq, gen, map().toString().toByteArray(Charsets.UTF_8))
    }

    /** Writes [s] to disk, outside this object's lock (reads go on meanwhile); one write at a time, never an older map over a newer. */
    private fun write(s: Snap) {
        synchronized(ioLock) {
            if (s.seq <= writtenSeq) return                              // a newer copy is on disk already
            if (synchronized(this) { s.gen != generation }) return       // wiped (or started again) since: never written back
            try {
                Vault.writeFile(file, s.bytes)
                writtenSeq = s.seq
                writeFailed = false
            } catch (e: Exception) { writeFailed = true; throw e }
        }
    }


    @Synchronized fun getString(k: String, d: String? = null): String? = map().optString(k, "").ifEmpty { d }
    @Synchronized fun getBoolean(k: String, d: Boolean): Boolean = if (map().has(k)) map().optBoolean(k, d) else d
    @Synchronized fun getInt(k: String, d: Int): Int = if (map().has(k)) map().optInt(k, d) else d
    @Synchronized fun getLong(k: String, d: Long): Long = if (map().has(k)) map().optLong(k, d) else d
    @Synchronized fun getDouble(k: String, d: Double): Double = if (map().has(k)) map().optDouble(k, d) else d

    fun put(k: String, v: Any?) {
        val snap = synchronized(this) {
            if (v == null) map().remove(k) else map().put(k, v)
            snapshot()
        } ?: return
        write(snap)
    }

    fun putAll(values: Map<String, Any?>) {
        val snap = synchronized(this) {
            val m = map()
            for ((k, v) in values) if (v == null) m.remove(k) else m.put(k, v)
            snapshot()
        } ?: return
        write(snap)
    }

    /** Every key and value (the backup); callers filter out what must never leave the vault. */
    @Synchronized fun snapshot(): Map<String, Any?> = map().let { m -> m.keys().asSequence().associateWith { m.opt(it) } }

    /** The keys starting with [prefix] (to prune per-day keys); names only. */
    @Synchronized fun keys(prefix: String): List<String> = map().keys().asSequence().filter { it.startsWith(prefix) }.toList()

    /** Bumped by every [init] and [wipe]: a caller holding vault values in memory drops them when it changes. */
    @Synchronized fun generationNow(): Int = generation

    fun wipe() {
        // After any write in progress (a write finishing after the delete would bring the old vault back).
        synchronized(ioLock) {
            synchronized(this) {
                generation++; pending = false
                cache = JSONObject()
                unreadable = false
                writtenSeq = seq
                file.delete()
            }
        }
    }
}
