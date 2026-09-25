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
 * Fingerprint and face unlock.
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
            m.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS -> Kind.WEAK
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

    /** A cipher bound to the gated key, or null if enrolment changed since. */
    private fun gatedCipher(): Cipher? = try {
        val key = keyStore().getKey(BIO_KEY, null) as? SecretKey ?: return null
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    } catch (_: KeyPermanentlyInvalidatedException) {
        forget(); null
    } catch (_: Exception) {
        null
    }

    sealed interface Outcome {
        data object Success : Outcome
        data object UsePin : Outcome
        data class Invalidated(val why: String) : Outcome
        data class Failed(val why: String) : Outcome
    }

    fun authenticate(activity: FragmentActivity, allowWeakFace: Boolean, onDone: (Outcome) -> Unit) {
        val kind = available(activity)
        val useStrong = kind == Kind.STRONG
        if (kind == Kind.NONE || (!useStrong && !allowWeakFace)) { onDone(Outcome.UsePin); return }

        val cipher = if (useStrong) gatedCipher() else null
        if (useStrong && cipher == null) {
            onDone(Outcome.Invalidated("A fingerprint or face was added or removed on this phone. Unlock with your PIN, then re-enable biometrics."))
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
            .setTitle("Unseal IraAlgo")
            .setSubtitle(if (useStrong) "Fingerprint or face" else "Face unlock")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(if (useStrong) BIOMETRIC_STRONG else BIOMETRIC_WEAK)
            .setConfirmationRequired(false)
            .build()

        if (useStrong) prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher!!)) else prompt.authenticate(info)
    }
}
