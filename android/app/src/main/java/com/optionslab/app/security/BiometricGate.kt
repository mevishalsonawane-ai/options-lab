package com.optionslab.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Fingerprint unlock (face unlock is not used: the owner's choice).
 *
 * STRONG biometrics (every fingerprint sensor, and Class 3 face such as
 * Pixel's) unlock a Keystore key that is usable ONLY after a successful match
 * and is invalidated the moment a new fingerprint or face is enrolled - so
 * someone who adds their own finger to the phone cannot use it here; the app
 * falls back to the PIN until the owner re-enables biometrics.
 *
 * Face unlock on most phones is Class 2 ("weak"), which Android does not
 * allow to guard a key. It is offered only when the owner opts in, and it is
 * then a convenience over the PIN, not a cryptographic gate.
 */
object BiometricGate {
    private const val BIO_KEY = "ol.vault.bio.v1"

    enum class Kind { STRONG, WEAK, NONE }

    fun available(activity: FragmentActivity): Kind {
        val m = BiometricManager.from(activity)
        return when {
            m.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS -> Kind.STRONG
            // Face unlock is not offered (the owner's choice, 2026-09-26): fingerprint only.
            else -> Kind.NONE
        }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** Creates the match-gated key. Called when the owner enables biometrics. */
    fun enrol() {
        runCatching { keyStore().deleteEntry(BIO_KEY) }
        val b = KeyGenParameterSpec.Builder(BIO_KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(b.build()); generateKey() }
    }

    fun forget() { runCatching { keyStore().deleteEntry(BIO_KEY) } }

    /** The phone has a sensor but no fingerprint added yet. */
    fun notEnrolled(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED

    /** A cipher bound to the gated key, or null if enrolment changed since. */
    private fun gatedCipher(): Cipher? = try {
        // No key yet (switched on before fingerprint-only, or never made): make it now.
        if (keyStore().getKey(BIO_KEY, null) == null) enrol()
        val key = keyStore().getKey(BIO_KEY, null) as? SecretKey ?: return null
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    } catch (_: KeyPermanentlyInvalidatedException) {
        forget(); null
    } catch (_: Exception) {
        null
    }

    // ---- a secret only the fingerprint opens (the Zerodha API secret) -----------------------

    private const val SECRET_KEY = "ol.vault.secret.bio.v1"

    private fun makeSecretKey() {
        val b = KeyGenParameterSpec.Builder(SECRET_KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(b.build()); generateKey() }
    }

    fun forgetSecretKey() { runCatching { keyStore().deleteEntry(SECRET_KEY) } }

    private fun secretCipher(iv: ByteArray?): Cipher? = try {
        if (iv == null && keyStore().getKey(SECRET_KEY, null) == null) makeSecretKey()
        val key = keyStore().getKey(SECRET_KEY, null) as? SecretKey ?: return null
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            if (iv == null) init(Cipher.ENCRYPT_MODE, key) else init(Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        }
    } catch (_: KeyPermanentlyInvalidatedException) {
        forgetSecretKey(); null
    } catch (_: Exception) {
        null
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun prompt(activity: FragmentActivity, cipher: Cipher, title: String, subtitle: String, onDone: (Cipher?, String?) -> Unit) {
        val p = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onDone(result.cryptoObject?.cipher, null)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onDone(null,
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_USER_CANCELED) null else errString.toString())
        })
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle).setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BIOMETRIC_STRONG).setConfirmationRequired(false).build()
        p.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    /** Seal [plain] under the fingerprint-only key; [onDone] gets the blob, or null and why (null why = cancelled). */
    fun sealWithFingerprint(activity: FragmentActivity, plain: String, onDone: (String?, String?) -> Unit) {
        if (available(activity) != Kind.STRONG) { onDone(null, "This phone has no fingerprint Android trusts to guard a key"); return }
        val c = secretCipher(null) ?: run { onDone(null, "The fingerprint key could not be made"); return }
        prompt(activity, c, "Seal the API secret", "Your fingerprint will open it at each Zerodha login") { ok, why ->
            val blob = ok?.let { runCatching { "b1:${hex(it.iv)}:${hex(it.doFinal(plain.toByteArray(Charsets.UTF_8)))}" }.getOrNull() }
            onDone(blob, if (blob == null && ok != null) "The secret could not be sealed" else why)
        }
    }

    /** Open a blob from [sealWithFingerprint]. Null secret with null why = cancelled; "invalid" = a finger was added since. */
    fun openWithFingerprint(activity: FragmentActivity, blob: String, onDone: (String?, String?) -> Unit) {
        val parts = blob.split(":")
        if (parts.size != 3 || parts[0] != "b1") { onDone(null, "invalid"); return }
        val c = secretCipher(unhex(parts[1])) ?: run { onDone(null, "invalid"); return }
        prompt(activity, c, "Log in to Zerodha", "Your fingerprint opens the API secret for this login") { ok, why ->
            val plain = ok?.let { runCatching { String(it.doFinal(unhex(parts[2])), Charsets.UTF_8) }.getOrNull() }
            onDone(plain, if (plain == null && ok != null) "invalid" else why)
        }
    }

    sealed interface Outcome {
        data object Success : Outcome
        data object UsePin : Outcome
        data class Invalidated(val why: String) : Outcome
        data class Failed(val why: String) : Outcome
    }

    fun authenticate(activity: FragmentActivity, allowWeakFace: Boolean, onDone: (Outcome) -> Unit) {
        val kind = available(activity)
        // With face unlock accepted, any enrolled fingerprint or face works (no key gate, as weak
        // biometrics cannot guard a key). Otherwise only strong biometrics, bound to the Keystore key.
        // Fingerprint only, always through the hardware-bound key; [allowWeakFace] is kept for callers but ignored.
        val useStrong = kind == Kind.STRONG
        if (!useStrong) { onDone(Outcome.UsePin); return }

        val cipher = if (useStrong) gatedCipher() else null
        if (useStrong && cipher == null) {
            onDone(Outcome.Invalidated("A fingerprint was added or removed on this phone. Unlock with your PIN, then re-enable biometrics."))
            return
        }

        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (useStrong) {
                    // Prove the match actually released the key, not just that a callback fired.
                    val ok = runCatching { result.cryptoObject?.cipher?.doFinal(ByteArray(16)) != null }.getOrDefault(false)
                    onDone(if (ok) Outcome.Success else Outcome.Failed("biometric key did not unlock"))
                } else onDone(Outcome.Success)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onDone(
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_USER_CANCELED) Outcome.UsePin
                    else Outcome.Failed(errString.toString())
                )
            }
        })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock IraAlgo")
            .setSubtitle("Fingerprint")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(if (useStrong) BIOMETRIC_STRONG else BIOMETRIC_WEAK)
            .setConfirmationRequired(false)
            .build()

        if (useStrong) prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher!!)) else prompt.authenticate(info)
    }
}
