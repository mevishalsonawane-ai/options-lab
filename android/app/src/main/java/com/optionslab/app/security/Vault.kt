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

    /** Atomic: an interrupted write can never leave a half-written vault file. */
    fun writeFile(file: File, plain: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(encrypt(plain))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun readFile(file: File): ByteArray? = if (file.exists()) decrypt(file.readBytes()) else null

    /** Destroys the key: every vault file becomes unreadable at once. */
    fun destroy() {
        runCatching { keyStore().deleteEntry(DATA_KEY) }
    }
}
