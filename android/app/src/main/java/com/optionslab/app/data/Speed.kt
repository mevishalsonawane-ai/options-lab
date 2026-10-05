package com.optionslab.app.data

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicLong

/**
 * Speed (Boss, 5 Oct: "my application has become too slow"): how long the screen's thread was stuck today, and on what,
 * for the diagnostics' "Speed:" line - so the next report names the cause.
 *
 * While the app is on screen ([start] / [stop] from the activity), a small background thread posts an empty task to the
 * main thread once a second and times how long it waits to run. A wait of [STALL_MS] or more is a stall (the screen
 * could not draw meanwhile); once a wait passes [SAMPLE_MS] the main thread's stack is looked at once, and the first frames
 * (class and method names of the code running - never data, never a message) are kept as "what was running".
 * Stalls of a second or more also go to the diagnostics diary (a few a day at most).
 */
object Speed {
    private const val STALL_MS = 200L
    private const val LONG_MS = 1_000L
    private const val SAMPLE_MS = 250L
    private const val EVERY_MS = 1_000L
    /** At most this many diary lines a day (the stalls of a second or more). */
    private const val DIARY_MAX = 12

    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** Bumped by [start] and [stop]: an older watcher thread sees it changed and ends. */
    @Volatile private var run = 0
    @Volatile private var on = false

    // Today's tally (guarded by this object's lock).
    private var day: LocalDate? = null
    private var stalls = 0
    private var longStalls = 0
    private var longestMs = 0L
    private var longestAt: LocalTime? = null
    private var longestWhat: String? = null
    private var diaryToday = 0

    /** How long the app's start (Application.onCreate) took, and its slowest step, for the same line. */
    @Volatile var startMs: Long = 0L
    @Volatile var startSlowest: String? = null

    /** The app came on screen. */
    @Synchronized fun start() {
        if (on) return
        on = true
        val me = ++run
        runCatching { Thread({ watch(me) }, "speed-watch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start() }
    }

    /** The app left the screen: the watcher ends (nothing runs in the background for this). */
    @Synchronized fun stop() { on = false; run++ }

    private fun watch(me: Int) {
        while (run == me) {
            val posted = SystemClock.uptimeMillis()
            val ran = AtomicLong(0L)
            if (!main.post { ran.set(SystemClock.uptimeMillis()) }) return
            var what: String? = null
            while (run == me && ran.get() == 0L) {
                try { Thread.sleep(50) } catch (_: InterruptedException) { return }
                val waited = SystemClock.uptimeMillis() - posted
                if (what == null && waited >= SAMPLE_MS) what = runCatching { runningOnMain() }.getOrNull() ?: "?"
                if (waited > 120_000) break
            }
            val end = ran.get().takeIf { it != 0L } ?: SystemClock.uptimeMillis()
            val lag = end - posted
            if (lag >= STALL_MS) note(lag, what)
            try { Thread.sleep(EVERY_MS) } catch (_: InterruptedException) { return }
        }
    }

    /** The main thread's first frames: the code running (class.method), the app's own frame named if it is deeper. */
    private fun runningOnMain(): String {
        val st = Looper.getMainLooper().thread.stackTrace
        if (st.isEmpty()) return "?"
        fun f(e: StackTraceElement) = "${e.className.substringAfterLast('.')}.${e.methodName}"
        val top = f(st[0])
        val ours = st.firstOrNull { it.className.startsWith("com.optionslab.") }
        return if (ours == null || ours === st[0]) top else "$top in ${f(ours)}"
    }

    private fun note(lag: Long, what: String?) {
        val now = LocalTime.now(com.optionslab.engine.IST).withNano(0)
        val diary = synchronized(this) {
            val today = LocalDate.now(com.optionslab.engine.IST)
            if (day != today) { day = today; stalls = 0; longStalls = 0; longestMs = 0; longestAt = null; longestWhat = null; diaryToday = 0 }
            stalls++
            if (lag >= LONG_MS) longStalls++
            if (lag > longestMs) { longestMs = lag; longestAt = now; longestWhat = what }
            (lag >= LONG_MS && diaryToday < DIARY_MAX).also { if (it) diaryToday++ }
        }
        if (diary) runCatching { Diag.record("speed", "the screen was stuck " + "%.1f".format(java.util.Locale.ENGLISH, lag / 1000.0) + " s in " + (what ?: "?")) }
    }

    /** The diagnostics' "Speed:" line. */
    fun line(): String = synchronized(this) {
        val start = if (startMs > 0) " · app start ${startMs} ms" + (startSlowest?.let { " (slowest: $it)" } ?: "") else ""
        val today = LocalDate.now(com.optionslab.engine.IST)
        if (day != today || stalls == 0) "Speed: no screen stalls today (checked while the app is open: ${if (on) "now" else "not now"})$start"
        else {
            val longest = "%.1f".format(java.util.Locale.ENGLISH, longestMs / 1000.0)
            "Speed: $stalls screen stalls of 0.2 s or more today ($longStalls of 1 s or more) · longest $longest s at $longestAt in ${longestWhat ?: "?"}$start"
        }
    }
}
