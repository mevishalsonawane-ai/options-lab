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
        (ks.getKey(DATA_KEY, null) as? SecretKey)?.let { return it }
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

    fun encrypt(plain: ByteArray): ByteArray {
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
    fun writeFile(file: File, plain: ByteArray) {
        file.parentFile?.mkdirs()
        val blob = encrypt(plain)   // before the file is opened: a Keystore failure leaves nothing half-written
        val tmp = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(tmp).use { it.write(blob); it.fd.sync() }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        syncDir(file.parentFile)
        fileWrites.merge(file.path, 1L) { a, b -> a + b }
    }

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
            writeFile(file, plain)
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
            try { return readFile(file) } catch (e: Exception) { last = e; if (i < attempts - 1) Thread.sleep(150L * (i + 1)) }
        }
        throw last!!
    }

    /** Move an unreadable vault file aside (never overwrite it) and say where it went. */
    fun setAside(file: File): File {
        val aside = File(file.parentFile, "${file.name}.unreadable.${System.currentTimeMillis()}")
        file.renameTo(aside)
        return aside
    }

    /** Destroys the key: every vault file becomes unreadable at once. */
    fun destroy() {
        keyEpoch++
        runCatching { keyStore().deleteEntry(DATA_KEY) }
    }
}
