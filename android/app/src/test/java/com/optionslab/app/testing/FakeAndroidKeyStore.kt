package com.optionslab.app.testing

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * TEST ONLY - lives in src/test, so it can never be part of an APK.
 *
 * Robolectric has no Android Keystore, and the app's [com.optionslab.app.security.Vault]
 * (AES-GCM data key) and PinPepper (HMAC key) ask the "AndroidKeyStore" JCA provider for
 * their keys. This registers a provider under that name that keeps software keys in memory,
 * so the production code runs unchanged - same calls, same aliases, same cipher - and no
 * seam or switch had to be added to the app.
 *
 * Supports exactly what the app uses: KeyStore "AndroidKeyStore" (getKey / deleteEntry /
 * containsAlias), and KeyGenerator AES and HmacSHA256 initialised with a KeyGenParameterSpec.
 * Keys are real random keys, so authentication failures behave as on a phone.
 */
class FakeAndroidKeyStore : Provider(NAME, 1.0, "In-memory stand-in for the Android Keystore (tests only)") {
    init {
        put("KeyStore.$NAME", Store::class.java.name)
        put("KeyGenerator.AES", AesGenerator::class.java.name)
        put("KeyGenerator.HmacSHA256", HmacGenerator::class.java.name)
    }

    companion object {
        const val NAME = "AndroidKeyStore"
        internal val keys = ConcurrentHashMap<String, SecretKey>()

        /** Aliases whose key the "Keystore" refuses to read or create (a flaky or broken Keystore). */
        val failing: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Idempotent. Registered last, so it is only ever used when asked for by name. */
        @Synchronized fun install() {
            // One provider per JVM, but Robolectric loads this class once per sandbox (SDK, graphics mode,
            // Conscrypt mode...): a provider from another sandbox would not recognise this sandbox's
            // KeyGenParameterSpec, so it is replaced by this sandbox's own.
            val cur = Security.getProvider(NAME)
            if (cur != null && cur.javaClass === FakeAndroidKeyStore::class.java) return
            if (cur != null) Security.removeProvider(NAME)
            Security.addProvider(FakeAndroidKeyStore())
        }

        /** Forget every key (a fresh phone). */
        fun reset() { keys.clear(); failing.clear() }

        /** The aliases currently held (for tests that check a key was created or destroyed). */
        fun aliases(): Set<String> = keys.keys.toSet()
    }

    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? {
            if (alias in failing) throw java.security.UnrecoverableKeyException("simulated Keystore fault")
            return keys[alias]
        }
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = if (keys.containsKey(alias)) Date() else null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key as? SecretKey ?: throw java.security.KeyStoreException("secret keys only")
        }
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?): Unit =
            throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String, cert: Certificate): Unit = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)
        override fun engineSize(): Int = keys.size
        override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    abstract class Generator(private val algorithm: String) : KeyGeneratorSpi() {
        private var alias: String? = null
        private var bits = 256
        private var random = SecureRandom()

        override fun engineInit(random: SecureRandom?): Unit = throw IllegalStateException("a KeyGenParameterSpec is required")
        override fun engineInit(keysize: Int, random: SecureRandom?): Unit = throw IllegalStateException("a KeyGenParameterSpec is required")
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            val spec = params as? KeyGenParameterSpec ?: throw java.security.InvalidAlgorithmParameterException("KeyGenParameterSpec required")
            alias = spec.keystoreAlias
            if (spec.keySize > 0) bits = spec.keySize
            random?.let { this.random = it }
        }

        override fun engineGenerateKey(): SecretKey {
            val a = alias ?: throw IllegalStateException("not initialised")
            if (a in failing) throw java.security.ProviderException("simulated Keystore fault")
            val key = SecretKeySpec(ByteArray(bits / 8).also(random::nextBytes), algorithm)
            keys[a] = key
            return key
        }
    }

    class AesGenerator : Generator("AES")
    class HmacGenerator : Generator("HmacSHA256")
}
