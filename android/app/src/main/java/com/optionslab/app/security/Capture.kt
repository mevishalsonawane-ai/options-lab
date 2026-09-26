package com.optionslab.app.security

import android.app.Activity
import android.view.WindowManager
import androidx.compose.ui.window.SecureFlagPolicy

/**
 * Screenshots and screen recording. Blocked (FLAG_SECURE on the app and every
 * dialog) unless the owner allows them in More -> Security. Allowed for now,
 * while the app is being tested and screens are shared; TODO.md has turning it
 * off before going live.
 */
object Capture {
    private const val KEY = "sec.capture"
    private const val DEFAULT_ALLOWED = true

    val allowed: Boolean get() = runCatching { SecurePrefs.getBoolean(KEY, DEFAULT_ALLOWED) }.getOrDefault(false)

    /** For every Compose dialog: follows the switch. */
    val policy: SecureFlagPolicy get() = if (allowed) SecureFlagPolicy.SecureOff else SecureFlagPolicy.SecureOn

    fun apply(activity: Activity) {
        if (allowed) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(allowed)
    }

    fun set(activity: Activity?, on: Boolean) {
        SecurePrefs.put(KEY, on)
        activity?.let { apply(it) }
    }
}
