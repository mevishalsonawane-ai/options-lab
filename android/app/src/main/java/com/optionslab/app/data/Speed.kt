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

    /** TEST ONLY: the main thread's queue and the clock as given (a blocked looper simulated); null in the app. */
    @Volatile internal var testPost: ((Runnable) -> Boolean)? = null
    @Volatile internal var testClock: (() -> Long)? = null
    private fun now(): Long = testClock?.invoke() ?: SystemClock.uptimeMillis()
    private fun post(r: Runnable): Boolean = testPost?.invoke(r) ?: main.post(r)

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
            val posted = now()
            val ran = AtomicLong(0L)
            if (!post(Runnable { ran.set(now()) })) return
            var what: String? = null
            while (run == me && ran.get() == 0L) {
                try { Thread.sleep(50) } catch (_: InterruptedException) { return }
                val waited = now() - posted
                if (what == null && waited >= SAMPLE_MS) what = runCatching { runningOnMain() }.getOrNull() ?: "?"
                if (waited > 120_000) break
            }
            val end = ran.get().takeIf { it != 0L } ?: now()
            val lag = end - posted
            if (lag >= STALL_MS) note(lag, what)
            try { Thread.sleep(EVERY_MS) } catch (_: InterruptedException) { return }
        }
    }

    /** The main thread's first frames: the code running (class.method), the app's own frame named if it is deeper. */
    private fun runningOnMain(): String = where(Looper.getMainLooper().thread.stackTrace)

    /**
     * [runningOnMain] from a stack: the top frame, the app's first frame, and (16 Oct) who called it - up to two more of the
     * app's own frames outside its storage and security code ("BinderProxy.transactNative in Vault.encrypt from
     * SecurePrefs.put < AppModel.save"). Boss's diagnostics named only "Vault.encrypt", which every save goes through, so the
     * screen code that wrote on the screen's thread could not be found. Class and method names only.
     */
    internal fun where(st: Array<StackTraceElement>): String {
        if (st.isEmpty()) return "?"
        fun f(e: StackTraceElement) = "${e.className.substringAfterLast('.').substringBefore('$')}.${e.methodName}"
        val top = f(st[0])
        val i = st.indexOfFirst { it.className.startsWith("com.optionslab.") }
        if (i < 0) return top
        val head = if (i == 0) top else "$top in ${f(st[i])}"
        val callers = st.drop(i + 1).filter { it.className.startsWith("com.optionslab.") }.map { f(it) }
            .filter { it != f(st[i]) }.distinct()
        // The storage layers first named as they come, then the first frame of the code that asked for them.
        val store = callers.takeWhile { STORE.any { p -> it.startsWith(p) } }
        val asker = callers.drop(store.size).firstOrNull()
        val chain = (store.takeLast(1) + listOfNotNull(asker)).distinct()
        if (chain.isNotEmpty()) return head + " from " + chain.joinToString(" < ")
        // 17 Oct (Boss's diagnostics: "BinderProxy.transactNative in Vault.encrypt" with no caller, twice): every frame below
        // the app's first one was outside "com.optionslab." - the release build's optimiser folds small callers into
        // lambdas it renames ("o.a"), and a call posted to the screen's thread starts in Android's own code. The caller is
        // still named: the first frame below that is not Android's, Java's or Kotlin's own, else the nearest framework frame.
        val below = st.drop(i + 1)
        if (below.isEmpty()) return head
        val outside = below.firstOrNull { e -> FRAMEWORK.none { e.className.startsWith(it) } }
        return head + if (outside != null) " from " + f(outside) else " from <framework ${f(below.first())}>"
    }

    /** Android's, Java's and Kotlin's own code: never the caller worth naming. */
    private val FRAMEWORK = listOf("android.", "androidx.", "com.android.", "java.", "javax.", "kotlin.", "kotlinx.", "dalvik.",
        "libcore.", "sun.", "jdk.", "org.json.", "com.google.android.")

    /** The storage and security layers every save passes through (named, but the caller beyond them is the useful frame). */
    /** The vault's write paths, as a freeze's place names them. */
    private val WRITES = listOf("Vault.encrypt", "Vault.writeFile", "Vault.write", "SecurePrefs.put", "SecureBlobs.put", "SecureBlobs.saveNow")

    private val STORE = listOf("Vault.", "SecurePrefs.", "SecureBlobs.", "SecretBox.", "Diag.")

    /**
     * 17 Oct: a freeze in the vault's code gets the write the guard caught on the screen's thread meanwhile
     * ([com.optionslab.app.security.MainWrites]: the settings keys or the file, and who asked), so the problem names it even
     * when the stack's own frames could not. [writer] null, or a freeze elsewhere: [what] as it was.
     */
    internal fun withWriter(what: String?, writer: String?): String? {
        if (writer == null) return what
        // Writes only (review, 17 Oct): a freeze in a read (Vault.decrypt) is never given a write it did not make.
        if (what != null && WRITES.none { it in what }) return what
        return (what ?: "?") + " · " + writer
    }

    private fun note(lag: Long, seen: String?) {
        val what = withWriter(seen, runCatching { com.optionslab.app.security.MainWrites.since(System.currentTimeMillis() - lag - 1_000) }.getOrNull())
        val now = LocalTime.now(com.optionslab.engine.IST).withNano(0)
        val diary = synchronized(this) {
            val today = LocalDate.now(com.optionslab.engine.IST)
            if (day != today) { day = today; stalls = 0; longStalls = 0; longestMs = 0; longestAt = null; longestWhat = null; diaryToday = 0 }
            stalls++
            if (lag >= LONG_MS) longStalls++
            if (lag > longestMs) { longestMs = lag; longestAt = now; longestWhat = what }
            (lag >= LONG_MS && diaryToday < DIARY_MAX).also { if (it) diaryToday++ }
        }
        // The app's own health (11 Oct): a stall of 0.7 s or more is kept with where it was stuck, for its tuner and Jarvis.
        if (lag >= com.optionslab.ira.HealthBook.STALL_MS) runCatching { AppHealth.stall(lag, what) }
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
