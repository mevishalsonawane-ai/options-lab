package com.optionslab.app.security

import android.app.Activity
import android.view.WindowManager
import androidx.compose.ui.window.SecureFlagPolicy

/**
 * Screenshots and screen recording. Blocked (FLAG_SECURE on the app and every
 * dialog) unless the owner allows them in More -> Security (with the PIN).
 */
object Capture {
    // A new key: the testing phase's "allowed" is not carried into live use; everyone starts blocked.
    private const val KEY = "sec.capture.live"
    private const val DEFAULT_ALLOWED = false

    val allowed: Boolean get() = runCatching { SecurePrefs.getBoolean(KEY, DEFAULT_ALLOWED) }.getOrDefault(false)

    /** For every Compose dialog: follows the switch. */
    val policy: SecureFlagPolicy get() = if (allowed) SecureFlagPolicy.SecureOff else SecureFlagPolicy.SecureOn

    fun apply(activity: Activity) {
        // Window flags belong to the main thread; a caller resuming after an off-thread PIN check may not be on it.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { activity.runOnUiThread { apply(activity) }; return }
        if (allowed) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(allowed)
    }

    fun set(activity: Activity?, on: Boolean) {
        SecurePrefs.put(KEY, on)
        activity?.let { apply(it) }
    }
}
