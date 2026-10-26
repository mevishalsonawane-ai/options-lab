package com.optionslab.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated encryption for everything personal this app stores: the paper
 * ledger, price alarms, settings and the PIN verifier.
 *
 * The key is AES-256-GCM, generated inside the Android Keystore (StrongBox
 * where the phone has one) and never leaves it: this process can ask the
 * Keystore to encrypt or decrypt, but cannot read the key. Copying the app's
 * files to another device therefore yields ciphertext nobody can open, and a
 * flipped bit is detected by the GCM tag rather than read as data.
 */
object Vault {
    private const val STORE = "AndroidKeyStore"
    private const val DATA_KEY = "ol.vault.data.v1"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    class Tampered : SecurityException("vault data failed authentication")

    private fun keyStore(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    @Synchronized
    private fun dataKey(): SecretKey {
        val ks = keyStore()
        // 26 Oct: during an erase no key is made (and none is used: the old one is gone). A save racing the erase used to
        // make a NEW key here after the erase destroyed the old one, so data saved in that moment was sealed again.
        if (erasing.get() > 0) throw Erasing()
        (ks.getKey(DATA_KEY, null) as? SecretKey)?.let { return it }
        // 20 Oct, review: a key that exists but did not come back (a Keystore hiccup on some phones) is never replaced: a new
        // key under the same name would make every vault file unreadable for good. Only a key truly absent is made.
        if (ks.containsAlias(DATA_KEY)) throw java.security.KeyStoreException("the vault key is present but was not returned; try again")
        return generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(DATA_KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(true)
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).run { init(builder.build()); generateKey() }
        } catch (e: Exception) {
            // No StrongBox chip (or a flaky one): fall back to the TEE-backed
            // Keystore, which still never exposes the key to this process.
            if (strongBox) generate(strongBox = false) else throw e
        }
    }

    // 17 Oct: a write on the screen's thread is noted with its caller ([MainWrites]; never stopped, never thrown).
    fun encrypt(plain: ByteArray): ByteArray = MainWrites.guard({ "Vault.encrypt" }) { encryptNow(plain) }

    private fun encryptNow(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, dataKey())
        val iv = c.iv
        require(iv.size == IV_BYTES)
        return iv + c.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size <= IV_BYTES) throw Tampered()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, dataKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        return try { c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES) } catch (_: javax.crypto.AEADBadTagException) { throw Tampered() }
    }

    /**
     * Atomic and durable: the new file is synced before it is renamed over the old one, and the
     * rename is synced too, so neither a crash nor a power cut leaves a half-written (or empty) vault file.
     */
    fun writeFile(file: File, plain: ByteArray) { writeFileWritten(file, plain) }

    /**
     * [writeFile], saying whether the file was written (26 Oct, review): false when an erase dropped the save, so a caller that
     * remembers what is on disk ([LastWrite], the settings) never takes it for written. A save a restore drops counts as
     * written, as before (the restart reads the restored file).
     */
    internal fun writeFileWritten(file: File, plain: ByteArray): Boolean =
        MainWrites.guard({ "Vault.writeFile ${file.name}" }) { writeFileNow(file, plain) }

    /**
     * TEST ONLY (19 Oct, order speed): every vault file write takes this long more (ms), as a Pixel's StrongBox can - so a test can
     * show a slow write never holds an order. 0 in the app; set only in debug builds.
     */
    /**
     * TEST ONLY (20 Oct): writes of these vault files (by name) fail, as a disk or Keystore fault would. Tests used to block a
     * write by making its fixed "<name>.tmp" a directory; each write now has its own temp file, so that no longer fails it.
     */
    @Volatile internal var testFailWrites: Set<String> = emptySet()
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    @Volatile internal var testWriteDelayMs = 0L
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    private fun writeFileNow(file: File, plain: ByteArray, restoring: Boolean = false): Boolean {
        // 20 Oct, review: a backup being restored owns its files until the app restarts: a live store saving its old book in
        // the seconds before the restart would write it over the restored file. Such a save is dropped (the store's memory
        // is the old data anyway; the restart reads the restored file).
        if (!restoring && file.path in frozen) {
            runCatching { com.optionslab.app.data.Diag.record("vault", "${file.name}: a save during a restore was dropped (the restored file is kept)") }
            return true     // handled, as before 26 Oct: only an erase's drop counts as not written
        }
        // 26 Oct: a save made while everything is being erased is dropped (no new key, no file); see [beginErase].
        val epoch = eraseEpoch
        if (erasing.get() > 0) { droppedInErase.incrementAndGet(); return false }
        testWriteDelayMs.takeIf { it > 0 }?.let { Thread.sleep(it) }
        if (file.name in testFailWrites) throw java.io.IOException("simulated disk fault writing ${file.name}")
        val dir = file.parentFile
        dir?.mkdirs()
        val blob = encrypt(plain)   // before the file is opened: a Keystore failure leaves nothing half-written
        // 20 Oct, review: each write its own temp file. One shared "<name>.tmp" let two writers of a file interleave (a broken
        // GCM tag: "tampered"), and the second's rename then failed and its fallback DELETED the good file.
        val tmp = File.createTempFile(file.name + ".", ".tmp", dir)
        try {
            java.io.FileOutputStream(tmp).use { it.write(blob); it.fd.sync() }
            // A rename replaces the file in one step; if it cannot, the old file is kept as it is and the write fails.
            // 20 Oct, second review: the freeze is asked again here, under the lock the freeze takes - a save that began
            // (and was encrypting) before the restore froze its file never lands over the restored one.
            // 26 Oct: a save that began before an erase and reaches its rename during or after it is dropped too (its file
            // would come back after the erase deleted it). Asked under the same lock [beginErase] takes.
            var erased = false
            val dropped = synchronized(freezeLock) {
                erased = erasing.get() > 0 || epoch != eraseEpoch
                if (erased) false
                else if (!restoring && file.path in frozen) true
                else { if (!tmp.renameTo(file)) throw java.io.IOException("could not replace ${file.name}"); false }
            }
            if (erased) { droppedInErase.incrementAndGet(); return false }
            if (dropped) {
                runCatching { com.optionslab.app.data.Diag.record("vault", "${file.name}: a save during a restore was dropped (the restored file is kept)") }
                return true
            }
        } finally { if (tmp.exists()) tmp.delete() }
        syncDir(dir)
        // The one fixed temp name used before 20 Oct, and this file's own temp files a crash left (over a minute old, so
        // never another writer's in flight): nothing will rename them any more.
        File(dir, file.name + ".tmp").takeIf { it.exists() }?.delete()
        if (swept.add(file.path)) {                                    // once a file a run: a directory listing is not free
            val old = System.currentTimeMillis() - 60_000L
            runCatching {
                dir?.listFiles { f -> f.name.startsWith(file.name + ".") && f.name.endsWith(".tmp") && f.lastModified() < old }?.forEach { it.delete() }
            }
        }
        fileWrites.merge(file.path, 1L) { a, b -> a + b }
        return true
    }

    /** Files a backup restore owns until the restart ([freezeForRestore]); every other write to them is dropped. */
    @Volatile private var frozen: Set<String> = emptySet()

    /**
     * A restore begins (20 Oct, review): from now until the process ends, only [writeRestored] writes [files]. Saves by the live
     * stores (their old books, still in memory) are dropped, so the restored data is what the restart reads.
     */
    fun freezeForRestore(files: Collection<File>) { synchronized(freezeLock) { frozen = frozen + files.map { it.path } } }

    /** Files whose leftover temp files this run already swept. */
    private val swept: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Taken by [freezeForRestore] and by each write's final rename, so a freeze and a rename never cross. */
    private val freezeLock = Any()

    /** The restore's own write of a file [freezeForRestore] froze. */
    fun writeRestored(file: File, plain: ByteArray) { writeFileNow(file, plain, restoring = true) }

    /** A restore that stopped half-way (no restart follows), and tests: the stores write their files again. */
    fun thaw() { synchronized(freezeLock) { frozen = emptySet() } }

    /** How many times this process wrote each vault file (by path): a [LastWrite] knows when someone else wrote it since. */
    private val fileWrites = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** How many times this process wrote [file] (tests: a save that changes nothing costs no encryption). */
    internal fun writeCount(file: File): Long = fileWrites[file.path] ?: 0L
    /** Bumped when the key is destroyed: everything written before must be written again (with the new key). */
    @Volatile private var keyEpoch = 0

    /**
     * What one store last wrote to its vault file, so a save that would write exactly the same bytes to a file nobody touched
     * since is skipped (ANR fix, 9 Oct). A book saved after every look of the watch (every 15 s while anything is held) and
     * unchanged most of those times cost a Keystore encryption and two disk syncs each time, and the Keystore takes one
     * operation at a time - every other vault read or write, the screen's included, queued behind them. Anything that
     * changed is written exactly as before. Kept in memory only: a new process writes its first save.
     */
    class LastWrite {
        private var path: String? = null
        private var digest: ByteArray? = null
        private var stat: Pair<Long, Long>? = null
        private var writes = -1L
        private var epoch = -1

        /** [writeFile], unless [plain] is what this process last wrote to [file] and the file is untouched since. True when written. */
        @Synchronized fun write(file: File, plain: ByteArray): Boolean {
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(plain)
            if (same(file, d)) return false
            path = null; digest = null; stat = null
            // A save an erase dropped is not remembered: the next save of the same bytes is written.
            if (!writeFileWritten(file, plain)) return false
            path = file.path; digest = d; stat = file.length() to file.lastModified()
            writes = fileWrites[file.path] ?: 0L; epoch = keyEpoch
            return true
        }

        private fun same(file: File, d: ByteArray): Boolean = digest?.contentEquals(d) == true && path == file.path &&
            epoch == keyEpoch && writes == (fileWrites[file.path] ?: 0L) && file.exists() && stat == (file.length() to file.lastModified())

        /** The next save writes whatever it holds (a wipe, a reset). */
        @Synchronized fun forget() { path = null; digest = null; stat = null }
    }

    /**
     * Sync a directory, so a rename into it survives a power cut. Best effort: a file system that
     * cannot sync a directory still has the synced file under one of its two names.
     */
    fun syncDir(dir: File?) {
        if (dir == null) return
        runCatching {
            val fd = android.system.Os.open(dir.path, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
        }
    }

    fun readFile(file: File): ByteArray? = if (file.exists()) decrypt(file.readBytes()) else null

    /**
     * [readFile], retried a few times: a Keystore hiccup must not be mistaken
     * for a destroyed or tampered vault. Still throws if it stays unreadable.
     */
    fun readFileSteady(file: File, attempts: Int = 3): ByteArray? {
        var last: Exception? = null
        repeat(attempts) { i ->
            try { return readFile(file) } catch (e: Erasing) { throw e   // not a hiccup: no retry
            } catch (e: Exception) { last = e; if (i < attempts - 1) Thread.sleep(150L * (i + 1)) }
        }
        throw last!!
    }

    /** Move an unreadable vault file aside (never overwrite it) and say where it went. */
    fun setAside(file: File): File {
        // 26 Oct (erase audit): during an erase every read fails ([Erasing]) and the stores that catch it set their file aside.
        // Renamed, it outlived the erase (its wipe deletes the file by its own name). Now the file is deleted instead: it is
        // being erased anyway, and its key is already gone.
        if (isErasing) { runCatching { file.delete() }; return file }
        val aside = File(file.parentFile, "${file.name}.unreadable.${System.currentTimeMillis()}")
        file.renameTo(aside)
        return aside
    }

    /**
     * Destroys the key: every vault file becomes unreadable at once. Under [dataKey]'s lock, so a key being made at this
     * moment is made first and destroyed with the rest (never left behind).
     */
    @Synchronized
    fun destroy() {
        keyEpoch++
        runCatching { keyStore().deleteEntry(DATA_KEY) }
    }

    // ---- erase (26 Oct) -------------------------------------------------------------------------------------------------

    /**
     * 26 Oct (CI on 29ba08f: "the vault key is gone" failed once): erase-everything destroyed the key, but a save on another
     * thread (a settings write, an arm's book, the market recorder's own wipe) could encrypt after that and make a NEW key,
     * sealing what it saved again. Between [beginErase] and [endErase] no key is made and no vault file is written: such saves
     * are dropped (the stores are being wiped anyway), reads fail. The PIN pepper is held the same way ([PinPepper]).
     */
    class Erasing : java.security.KeyStoreException("everything is being erased: nothing is sealed until the erase ends")

    private val erasing = java.util.concurrent.atomic.AtomicInteger(0)
    /** Bumped when an erase begins: a save that started before it never lands after it. Written under [freezeLock]. */
    @Volatile private var eraseEpoch = 0
    /** Saves dropped because an erase was running (the tests). */
    internal val droppedInErase = java.util.concurrent.atomic.AtomicLong()

    /** True while an erase runs. */
    val isErasing: Boolean get() = erasing.get() > 0

    /** The erase begins: from now no key is made and no vault file written; the key is destroyed. Pair with [endErase]. */
    fun beginErase() {
        synchronized(freezeLock) { erasing.incrementAndGet(); eraseEpoch++ }
        runCatching { PinPepper.destroy() }
        destroy()
    }

    /**
     * TEST ONLY: run at the end of an erase, just before the keys are destroyed the last time (a test checks that no key was
     * made during the erase, which the last destroy would otherwise hide). Null in the app.
     */
    @Volatile internal var testBeforeLastDestroy: (() -> Unit)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /** TEST ONLY: no erase running (a test that failed mid-erase never leaves the next one held). */
    internal fun resetEraseForTest() { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; erasing.set(0) }

    /** The erase ends: the keys are destroyed one last time, then saves (with a new key) are allowed again. */
    fun endErase() {
        try { testBeforeLastDestroy?.invoke() } finally {
            synchronized(this) {
                runCatching { PinPepper.destroy() }
                destroy()
                erasing.updateAndGet { if (it > 0) it - 1 else 0 }
            }
        }
    }
}
