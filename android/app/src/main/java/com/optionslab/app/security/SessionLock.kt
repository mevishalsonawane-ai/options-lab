package com.optionslab.app.security

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the UI is sealed. Starts sealed on every cold start, and re-seals
 * when the app has been in the background longer than the chosen grace
 * period (default: immediately). Observed on the process lifecycle, so
 * switching apps, the screen turning off and the recents screen all count.
 */
object SessionLock : DefaultLifecycleObserver {
    const val K_GRACE = "lock.graceSeconds"

    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked
    private var backgroundedAt = 0L

    fun unlock() { _locked.value = false }
    fun lock() { _locked.value = true }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = SystemClock.elapsedRealtime()
        if (SecurePrefs.getInt(K_GRACE, 0) == 0) lock()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (backgroundedAt == 0L) return
        val away = (SystemClock.elapsedRealtime() - backgroundedAt) / 1000
        if (away >= SecurePrefs.getInt(K_GRACE, 0)) lock()
    }
}
