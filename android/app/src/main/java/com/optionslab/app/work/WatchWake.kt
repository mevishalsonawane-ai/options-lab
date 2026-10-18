package com.optionslab.app.work

import android.content.Context
import android.os.PowerManager

/**
 * The market watch keeps the phone's processor awake while it runs (17 Oct). Boss's diagnostics, 17 Oct 01:07: "MCX is open
 * with 2 armed (NG evening breakout and US-night silver), but the market watch is not running ... the watch was started again
 * and was still not running after 2 minutes". A foreground service keeps the app's process alive, but not the processor: with
 * the screen off and nothing arriving from the network (the evening's MCX arms armed, nothing held, Paper mode - the price
 * stream rests by design), the phone sleeps between the dead-man alarms, and the watch's timed passes, its 30 s pulse and the
 * arms' checks sleep with it. The alarm then woke the phone, found the pulse minutes old, called the watch "not running" and
 * asked it to start - a no-op, the service was there, asleep - and two minutes later found it the same.
 *
 * Held from the watch's first pulse to its end, renewed with each pulse ([PULSE_MS]): each hold lapses by itself after
 * [HOLD_MS], so a watch that dies without its end never keeps the phone awake. Only the watch's run holds it, and the watch
 * runs only while a market needs it (NSE's session, MCX open with something held or an arm armed). Never on the order or tick
 * path; a binder call to Android, made on the watch's own background thread.
 */
object WatchWake {
    private const val TAG = "IraAlgo:watch"
    /** Each hold's own limit; the pulse renews it every 30 s. */
    const val HOLD_MS = 3 * 60_000L

    @Volatile private var lock: PowerManager.WakeLock? = null

    /** Held (again) for [HOLD_MS] from now. Never throws. */
    @Synchronized fun hold(context: Context) {
        runCatching {
            val l = lock ?: context.applicationContext.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)?.apply { setReferenceCounted(false) }?.also { lock = it }
                ?: return
            l.acquire(HOLD_MS)
        }
    }

    /** Let go (the watch's end). Never throws. */
    @Synchronized fun release() { runCatching { lock?.let { if (it.isHeld) it.release() } } }

    /** Held now (the diagnostics, the tests). */
    fun held(): Boolean = runCatching { lock?.isHeld == true }.getOrDefault(false)

    /** TEST ONLY: as a fresh process. */
    @Synchronized internal fun resetForTest() { release(); lock = null }
}
