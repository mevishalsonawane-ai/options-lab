package com.optionslab.app.security

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the UI is sealed. Starts sealed on every cold start. After an
 * unlock the session stays open until it has been idle for the chosen time
 * (default 5 minutes): no touch in the app, or time spent in other apps,
 * both count. So stepping out to copy a key from the browser and coming
 * straight back does not ask for the PIN again.
 */
object SessionLock : DefaultLifecycleObserver {
    const val K_IDLE = "lock.idleSeconds"
    const val DEFAULT_IDLE = 300

    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked
    @Volatile private var lastActive = SystemClock.elapsedRealtime()

    private fun idleLimit(): Int = SecurePrefs.getInt(K_IDLE, DEFAULT_IDLE).coerceAtLeast(30)

    /** Any touch in the app keeps the session alive. */
    fun touch() { lastActive = SystemClock.elapsedRealtime() }

    fun unlock() { touch(); _locked.value = false }
    fun lock() { _locked.value = true }

    /** Seal if the session has been idle past the limit. */
    fun checkIdle() {
        if (!_locked.value && (SystemClock.elapsedRealtime() - lastActive) / 1000 >= idleLimit()) lock()
    }

    // Time in the background counts as idle: lastActive is simply not refreshed.
    override fun onStart(owner: LifecycleOwner) = checkIdle()
}
