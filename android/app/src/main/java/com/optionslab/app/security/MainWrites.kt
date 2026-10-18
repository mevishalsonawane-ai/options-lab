package com.optionslab.app.security

import java.util.concurrent.atomic.AtomicLong

/**
 * Vault writes made on the screen's thread (17 Oct; Boss's diagnostics: "The screen froze 5.8 s in BinderProxy.transactNative
 * in Vault.encrypt ... a call into Android's system made on the screen's thread", with no caller named). A vault write is a
 * Keystore encryption (StrongBox on a Pixel: one operation at a time, seconds when it is busy) and two disk syncs, and must
 * never run on the screen's thread. [SecurePrefs] now moves such writes to its background writer by itself; this guard
 * catches whatever still writes there and keeps who asked - the settings keys or the file, and the calling code - for the
 * diagnostics ("Vault writes on the screen's thread") and the freeze's own problem text. It never throws and never stops
 * the write (the app goes on exactly as before); it only notes. Names only: keys, file names and class.method, never a value.
 */
object MainWrites {
    /** One write seen on the screen's thread: what was written ("SecurePrefs.put jarvis.auto.guard"), by whom, and when. */
    data class Seen(val what: String, val caller: String, val atMs: Long)

    private val count = AtomicLong()
    @Volatile var last: Seen? = null
        private set
    /** Diary lines today (a few at most): the first time each caller is seen. */
    private val told = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var toldDay: String? = null

    /** Set while a guarded write runs on this thread, so the vault layers below it do not note it again. */
    private val inside = ThreadLocal<Boolean>()

    /** TEST ONLY: the thread taken for the screen's (null: Android's main looper's). */
    @Volatile internal var testMainThread: Thread? = null

    /**
     * Robolectric runs every test on its "main" thread: there the tests' own writes are neither moved nor noted (they read
     * their files straight back, and a diary line per write would crowd the diaries they check) unless a test sets [testMainThread].
     */
    private val robolectric = runCatching { android.os.Build.FINGERPRINT == "robolectric" }.getOrDefault(false)

    /** The calling thread is the screen's (main) thread. False off Android (the plain unit tests) or when it cannot be told. */
    fun onMain(): Boolean = testMainThread?.let { it === Thread.currentThread() } ?: (!robolectric && runCatching {
        val m = android.os.Looper.getMainLooper()
        m != null && m.thread === Thread.currentThread()
    }.getOrDefault(false))

    /**
     * [block] (a vault write); first, when on the screen's thread, what it writes ([what], built only then) and the calling
     * code are noted. Nested guarded calls note only the outermost (the most telling: settings keys rather than "prefs.vault").
     */
    inline fun <T> guard(what: () -> String, block: () -> T): T {
        if (!onMain() || entered()) return block()
        note(what())
        enter(true)
        try { return block() } finally { enter(false) }
    }

    @PublishedApi internal fun entered(): Boolean = inside.get() == true
    @PublishedApi internal fun enter(on: Boolean) { if (on) inside.set(true) else inside.remove() }

    /** Noted now, with the calling code from this thread's stack. */
    @PublishedApi internal fun note(what: String) {
        val st = runCatching { Thread.currentThread().stackTrace }.getOrDefault(emptyArray())
        record(what, st, System.currentTimeMillis())
    }

    /** [what] written on the screen's thread, [st] the stack at the write: its own frames and the stack-reading ones dropped. */
    internal fun record(what: String, st: Array<StackTraceElement>, atMs: Long) {
        val start = st.indexOfFirst { e -> !e.className.startsWith("dalvik.system.VMStack") && !e.className.startsWith("java.lang.Thread") &&
            !e.className.startsWith(MainWrites::class.java.name) }
        val caller = if (start < 0) "?" else com.optionslab.app.data.Speed.where(st.copyOfRange(start, st.size))
        val seen = Seen(what.take(120), caller, atMs)
        last = seen
        count.incrementAndGet()
        // The diary: once a day per caller (the first few), never the value.
        val day = runCatching { com.optionslab.app.data.Market.today().toString() }.getOrDefault("")
        if (toldDay != day) { told.clear(); toldDay = day }
        if (told.size < DIARY_MAX && told.add(caller)) runCatching {
            com.optionslab.app.data.Diag.record("speed", "a vault write on the screen's thread: ${seen.what} from $caller")
        }
    }

    private const val DIARY_MAX = 8

    /** How many vault writes this process made on the screen's thread. */
    fun count(): Long = count.get()

    /**
     * The calling code of the last write on the screen's thread when it was noted at or after [sinceMs] (a freeze that began
     * then): "SecurePrefs.put jarvis.auto.guard by AutomationsCard.invoke"; null when none was.
     */
    fun since(sinceMs: Long): String? = last?.takeIf { it.atMs >= sinceMs }?.let { "${it.what} by ${it.caller}" }

    /** The diagnostics' line. */
    fun line(): String {
        val n = count.get()
        val l = last
        return "Vault writes on the screen's thread: " + if (n == 0L || l == null) "none" else "$n (last: ${l.what} from ${l.caller})"
    }

    /** TEST ONLY: as a fresh process. */
    internal fun resetForTest() { count.set(0L); last = null; told.clear(); toldDay = null; testMainThread = null; inside.remove() }
}
