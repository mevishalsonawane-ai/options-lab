package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.signals.TdSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.JsonClient
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The Signal tester's Telegram login (15 Oct 2026, Boss: "no notification reading - log in to Telegram in the app"): TDLib,
 * Telegram's official client library (built from tdlib/td's own source in CI, android/tools/tdlib/build-tdlib.sh), run through
 * its JSON interface. What is said to TDLib and made of its answers is [TdSession] (read-only by construction: never a send, a
 * join, a leave, an open or a "mark as read"; only the channels Boss selects are ever read).
 *
 * Boss enters his own api_id and api_hash once (my.telegram.org); they and the random key of TDLib's encrypted database are kept
 * in the encrypted settings ("telegram." keys: never in a backup, an upload or a log). The phone number, the login code and the
 * 2-step password go straight to TDLib and are never kept. "Log out of Telegram" logs the session out at Telegram and wipes it:
 * the database, its key and the api keys.
 *
 * When it runs: TDLib's thread ([loop], its own background thread - never the main thread, never the order or tick path) runs
 * only while capture is on and the time is inside the capture window (09:00-15:35 by default, a setting), started and stopped by
 * the Signal tester's round ([ensure]); a login, "Check this chat" or a history fetch Boss taps runs it for that job. Under memory
 * pressure it is closed before anything that watches positions ([releaseForMemory]); the next round starts it again once the
 * pressure has passed. A build without the library (IraGoldAlgo, the tests) has no Telegram login ([available]).
 */
object TelegramCapture {
    const val K_API_ID = "telegram.api_id"
    const val K_API_HASH = "telegram.api_hash"
    const val K_DB_KEY = "telegram.db_key"
    const val K_LOGGED_IN = "telegram.logged_in"
    const val K_CAPTURE = "telegram.capture"
    const val K_FROM = "telegram.from"
    const val K_TO = "telegram.to"
    val FROM: LocalTime = LocalTime.of(9, 0)
    val TO: LocalTime = LocalTime.of(15, 35)
    /** Between two history pages (Telegram's limits; a flood wait from TDLib waits longer). */
    const val PACE_MS = 1_500L
    /**
     * A backstop (Boss, 15 Oct: "every new msg should be read immediately"): each new post arrives at once as TDLib's update and
     * is read on arrival; besides, while capturing, the selected channels' newest posts are looked at this often (Telegram may
     * delay a large channel's pushes to a client that has not opened it; a post already recorded is dropped by its id).
     */
    const val POLL_MS = 15_000L
    /** A job Boss started (login, check, history) keeps TDLib open this long after its last step, outside the window. */
    const val JOB_MS = 10 * 60_000L
    /** Still "starting" this long after the login was tapped: the dialog says what to check, with "Try again" (16 Oct). */
    const val STUCK_MS = 20_000L
    /** A login in progress is kept through a mild memory trim this long from its start at most ([releaseForMemory]). */
    const val LOGIN_KEEP_MS = 5 * 60_000L
    /** The one ABI TDLib is built for (android/tools/tdlib/build-tdlib.sh). */
    const val ABI = "arm64-v8a"

    /** The JSON interface as the app uses it (the real one is TDLib's; the tests pass a stand-in). */
    interface Transport {
        fun send(json: String)
        /** The next answer or update, or null after [timeoutSec]. One thread only (TDLib's rule). */
        fun receive(timeoutSec: Double): String?
        /** TDLib's id of this client (its answers carry it); null: the stand-in's. */
        val clientId: Int? get() = null
    }

    /** TEST SEAM: a stand-in for TDLib (null in the app: the real library when present). */
    @Volatile internal var testTransport: (() -> Transport)? = null

    data class View(
        val available: Boolean = false, val auth: TdSession.Auth = TdSession.Auth.OFF, val hint: String? = null, val keys: Boolean = false,
        val loggedIn: Boolean = false, val capture: Boolean = false, val from: LocalTime = FROM, val to: LocalTime = TO, val running: Boolean = false,
        val channels: List<TdSession.Channel> = emptyList(), val fetching: String? = null, val said: String? = null,
        /** Why there is no Telegram login on this phone, when the library is wanted but cannot load (16 Oct); null: not in this build. */
        val why: String? = null,
        /** The login has said "starting" for [STUCK_MS]: the dialog says what to check and offers "Try again". */
        val stuck: Boolean = false,
    ) {
        /** Telegram is starting or waiting for one of Boss's login steps. */
        val busy: Boolean get() = running && auth == TdSession.Auth.STARTING

        /** The row's one status line. */
        val line: String get() = when {
            !available -> why ?: "Not in this build"
            !loggedIn && auth != TdSession.Auth.READY -> (if (keys) "Not logged in" else "Not set up: enter your api_id and api_hash") +
                // 16 Oct: where the login is (Boss saw only "Not logged in" while it was starting or waiting for them).
                (if (running && auth in STEPS) " · ${auth.words}" else "")
            !capture -> "Logged in (read-only) · capture off"
            else -> "Logged in (read-only) · capture $from-$to" + if (running) " · reading now" else ""
        } + (fetching?.let { " · $it" } ?: "")
    }

    /** The login's steps in progress (shown on the status line; kept through a background trim, [releaseForMemory]). */
    private val STEPS = setOf(TdSession.Auth.STARTING, TdSession.Auth.NEED_PHONE, TdSession.Auth.NEED_CODE, TdSession.Auth.NEED_PASSWORD)

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view
    private fun upd(f: (View) -> View) { synchronized(this) { _view.value = f(_view.value) } }

    @Volatile private var app: Context? = null
    @Volatile private var session: TdSession? = null
    @Volatile private var transport: Transport? = null
    @Volatile private var loop: Thread? = null
    @Volatile private var running = false
    @Volatile private var jobUntil = 0L
    @Volatile private var lastPoll = 0L
    /** When the running session's login was started (the "stuck while starting" message, [STUCK_MS]). */
    @Volatile private var startedAt = 0L
    /** When TDLib last refused a request (the stuck message keeps an error said since the start). */
    @Volatile private var lastErrorAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) { app = context.applicationContext }

    // ---- the library -----------------------------------------------------------------------------------------------------

    @Volatile private var loaded: Boolean? = null

    /** TDLib is in this build and loads (once). Never on the main thread (it loads a native library). */
    fun available(): Boolean {
        if (com.optionslab.app.BuildConfig.GOLD) return false
        if (testTransport != null) return true
        loaded?.let { return it }
        val err = runCatching { System.loadLibrary("tdjsonjava") }.exceptionOrNull()
        loaded = err == null
        if (err != null) {
            val packaged = runCatching { File(app?.applicationInfo?.nativeLibraryDir ?: "", "libtdjsonjava.so").exists() }.getOrDefault(false)
            val abis = runCatching { android.os.Build.SUPPORTED_ABIS.toList() }.getOrDefault(emptyList())
            val why = unavailableWhy(packaged, abis, err.javaClass.simpleName)
            upd { it.copy(why = why) }
        }
        return err == null
    }

    /**
     * Why the library did not load, in plain words (16 Oct, Boss: the login "not working"): TDLib is built for 64-bit ARM phones
     * only ([ABI]); null when this build simply has none (the tests).
     */
    internal fun unavailableWhy(packaged: Boolean, abis: List<String>, error: String): String? = when {
        ABI !in abis -> "Not on this phone: the Telegram login needs a 64-bit ARM phone ($ABI); this one is ${abis.joinToString().ifEmpty { "unknown" }}"
        packaged -> "Telegram's library is in the app but did not load on this phone ($error)"
        else -> null
    }

    private class Native : Transport {
        // TDLib's own log off before the first client exists, so it never writes to the phone's log.
        init {
            JsonClient.execute("{\"@type\":\"setLogStream\",\"log_stream\":{\"@type\":\"logStreamEmpty\"}}")
            JsonClient.execute("{\"@type\":\"setLogVerbosityLevel\",\"new_verbosity_level\":0}")
        }
        private val id = JsonClient.createClientId()
        override val clientId: Int get() = id
        override fun send(json: String) = JsonClient.send(id, json)
        override fun receive(timeoutSec: Double): String? = JsonClient.receive(timeoutSec)
    }

    // ---- settings (the encrypted vault; off the main thread) ----------------------------------------------------------------

    private fun params(): TdSession.Params? {
        val c = app ?: return null
        val id = SecurePrefs.getInt(K_API_ID, 0)
        val hash = SecurePrefs.getString(K_API_HASH) ?: return null
        if (id <= 0) return null
        val key = SecurePrefs.getString(K_DB_KEY) ?: newKey().also { SecurePrefs.put(K_DB_KEY, it) }
        val dir = File(c.noBackupFilesDir, "tdlib")
        return TdSession.Params(id, hash, File(dir, "db").absolutePath, File(dir, "files").absolutePath, key)
    }

    private fun newKey(): String {
        val b = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        return java.util.Base64.getEncoder().encodeToString(b).also { b.fill(0) }
    }

    private fun time(k: String, d: LocalTime) = SecurePrefs.getString(k)?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d

    /** [s] shown in the login dialog (every result of Boss's taps, 16 Oct: they were shown only behind the dialog); returns it. */
    fun say(s: String?): String? { upd { it.copy(said = s) }; return s }
    private fun tell(s: String): String { upd { it.copy(said = s) }; return s }

    /** Reads the settings into [view] (the card calls it once, off the main thread). */
    fun refresh(): View {
        val keys = SecurePrefs.getInt(K_API_ID, 0) > 0 && SecurePrefs.getString(K_API_HASH) != null
        upd { it.copy(available = available(), keys = keys, loggedIn = SecurePrefs.getBoolean(K_LOGGED_IN, false),
            capture = SecurePrefs.getBoolean(K_CAPTURE, false), from = time(K_FROM, FROM), to = time(K_TO, TO)) }
        return _view.value
    }

    /**
     * Boss's own api_id (digits) and api_hash (32 hex characters) from my.telegram.org, kept encrypted on this phone only; once
     * saved, the login starts at once (16 Oct: the next step is the phone number, no second tap).
     */
    fun saveKeys(apiId: String, apiHash: String): String {
        val id = apiId.trim().toIntOrNull()
        val hash = apiHash.trim().lowercase()
        if (id == null || id <= 0) return tell("The api_id is a number (my.telegram.org → API development tools).")
        if (!Regex("[0-9a-f]{32}").matches(hash)) return tell("The api_hash is 32 letters and digits (0-9, a-f).")
        SecurePrefs.putAll(mapOf(K_API_ID to id, K_API_HASH to hash))
        upd { it.copy(keys = true) }
        return startLogin("Saved on this phone only (encrypted). ")
    }

    fun setCapture(on: Boolean): String {
        SecurePrefs.put(K_CAPTURE, on)
        upd { it.copy(capture = on) }
        if (!on) close()
        return tell(if (on) "Capture on: the selected channels are read ${_view.value.from}-${_view.value.to} while the market is open." else "Capture off: Telegram is not read.")
    }

    fun setWindow(from: LocalTime, to: LocalTime): String {
        if (!from.isBefore(to)) return tell("The start must be before the end.")
        SecurePrefs.putAll(mapOf(K_FROM to from.toString(), K_TO to to.toString()))
        upd { it.copy(from = from, to = to) }
        return tell("Capture window $from-$to.")
    }

    // ---- running TDLib -----------------------------------------------------------------------------------------------------

    /** TDLib started (if not running) for a job Boss started, kept open [JOB_MS] after it. False: not in this build. */
    private fun openForJob(): Boolean { jobUntil = System.currentTimeMillis() + JOB_MS; return open() }

    @Synchronized private fun open(): Boolean {
        if (running) return true
        // The last session's thread still closing: TDLib's receive must never run on two threads - the next round tries again.
        if (loop?.isAlive == true) return false
        if (!available()) return false
        failed = null
        val t = testTransport?.invoke() ?: runCatching { Native() }.onFailure { failed = it.javaClass.simpleName }.getOrNull() ?: return false
        val s = TdSession(t.clientId) { json -> t.send(json) }
        s.selected = SignalTesterHost.telegramSelected().keys
        transport = t; session = s; running = true
        startedAt = System.currentTimeMillis()
        upd { it.copy(stuck = false) }
        s.start(params())
        loop = Thread({ loop(t, s) }, "telegram-reader").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        // Opened: the library is here (the view says so even before the card's first refresh).
        upd { it.copy(running = true, auth = s.auth, available = true) }
        return true
    }

    /** TDLib's answers and updates, read on this thread only, until it is closed. */
    private fun loop(t: Transport, s: TdSession) {
        var closingSince = 0L
        while (true) {
            val json = runCatching { t.receive(1.0) }.getOrNull()
            if (json != null) runCatching { handle(s, s.onUpdate(json)) }
            val alive = running && session === s
            val now = System.currentTimeMillis()
            // A job Boss started is over and capture is not wanted now: closed (whether or not the words lane runs).
            if (alive && !capturing && !loggingOut && now > jobUntil) close()
            // 16 Oct: still "starting" well after the tap - the dialog says what to check, once, with "Try again".
            if (alive && s.auth == TdSession.Auth.STARTING && now - startedAt > stuckMs && !_view.value.stuck)
                // TDLib's own error since the start (bad keys, say) stays in front: it is the likelier reason.
                upd { it.copy(stuck = true, said = listOfNotNull(it.said.takeIf { lastErrorAt >= startedAt }, STUCK_WORDS).joinToString(" ")) }
            if (alive && capturing && s.auth == TdSession.Auth.READY && now - lastPoll >= POLL_MS && now >= pausedUntil) {
                lastPoll = System.currentTimeMillis()
                for (id in s.selected) runCatching { s.poll(id) }
            }
            if (!alive) {
                if (closingSince == 0L) closingSince = System.currentTimeMillis()
                // Closed by TDLib, or it did not answer the close within 15 s.
                if (s.auth == TdSession.Auth.CLOSED || System.currentTimeMillis() - closingSince > 15_000) break
            } else if (s.auth == TdSession.Auth.CLOSED) { synchronized(this) { if (session === s) running = false }; break }
        }
        val last = synchronized(this) { if (session === s) { session = null; transport = null; loop = null; true } else false }
        // 16 Oct: a login step left on screen after a slow close would show a "Send" that goes nowhere - closed instead.
        if (last) upd { it.copy(running = false, auth = if (it.auth == TdSession.Auth.READY || it.auth in STEPS) TdSession.Auth.CLOSED else it.auth,
            channels = emptyList(), stuck = false) }
    }

    private fun handle(s: TdSession, events: List<TdSession.Event>) {
        for (e in events) when (e) {
            is TdSession.Event.AuthChanged -> {
                // 16 Oct: each step says what comes next, in the dialog itself.
                val next = when (e.auth) {
                    TdSession.Auth.NEED_PHONE -> "Telegram started. Enter your phone number with the country code (+91…), then Send."
                    TdSession.Auth.NEED_CODE -> "Telegram sent you a login code" + (s.hint?.let { " $it" } ?: "") + ". Enter it, then Send."
                    TdSession.Auth.NEED_PASSWORD -> "Your account has 2-step verification: enter its password, then Send."
                    // Only for a login (a capture round re-opening a logged-in session says nothing).
                    TdSession.Auth.READY -> if (_view.value.loggedIn) null else "Logged in (read-only). Next: Choose channels."
                    TdSession.Auth.NEED_KEYS -> "Enter your api_id and api_hash first."
                    TdSession.Auth.UNSUPPORTED -> "This account needs a step only the Telegram app can do (sign up or e-mail): finish it there, then try again."
                    else -> null
                }
                upd { it.copy(auth = e.auth, hint = s.hint, stuck = if (e.auth == TdSession.Auth.STARTING) it.stuck else false, said = next ?: it.said) }
                if (e.auth == TdSession.Auth.READY) {
                    if (!SecurePrefs.getBoolean(K_LOGGED_IN, false)) SecurePrefs.put(K_LOGGED_IN, true)
                    upd { it.copy(loggedIn = true) }
                    s.loadChats()
                    scope.launch { delay(3_000); upd { it.copy(channels = s.channels()) } }
                }
            }
            is TdSession.Event.NewMessage -> SignalTesterHost.telegram(e.chat.id, e.msg, history = false)
            is TdSession.Event.Edited -> SignalTesterHost.edited(e.chat.id, e.msgId, e.at)
            is TdSession.Event.History -> history(s, e)
            is TdSession.Event.Error -> {
                e.retryAfter?.let { w -> pausedUntil = System.currentTimeMillis() + w * 1_000L }
                // A history page refused (a flood wait): asked again once Telegram allows, a few times at most.
                val x = e.extra
                if (x != null && x.startsWith("h:")) {
                    val id = x.substringAfter(':').substringBefore(':').toLongOrNull()
                    val from = x.substringAfter(':').substringAfter(':', "").toLongOrNull()
                    if (id != null && from != null && retries++ < 5) scope.launch {
                        delay(maxOf(PACE_MS * 4, pausedUntil - System.currentTimeMillis()))
                        if (running && session === s) runCatching { s.history(id, from) }
                    } else { SignalTesterHost.dropHistory(); upd { it.copy(fetching = null) } }
                }
                // TDLib's words only (never a message's text), in plain words: a wrong code, bad keys, a flood wait.
                lastErrorAt = System.currentTimeMillis()
                upd { it.copy(said = TdSession.errorWords(e.message)) }
            }
        }
    }

    @Volatile private var pausedUntil = 0L
    @Volatile private var retries = 0
    @Volatile private var loggingOut = false

    private fun history(s: TdSession, e: TdSession.Event.History) {
        when {
            e.check -> { SignalTesterHost.checkCounts(e.chat.title, e.msgs); upd { it.copy(said = "Checked ${e.chat.title}.") } }
            e.poll -> for (m in e.msgs) SignalTesterHost.telegram(e.chat.id, m, history = false)
            else -> {
                val next = e.next
                SignalTesterHost.telegramHistory(e.chat.id, e.msgs, done = next == null)
                if (next == null) {
                    upd { it.copy(fetching = null, said = "History of ${e.chat.title} read back to ${TdSession.HISTORY_FROM}.") }
                } else {
                    retries = 0
                    upd { it.copy(fetching = "history to ${e.msgs.firstOrNull()?.at?.toLocalDate() ?: "…"}") }
                    jobUntil = System.currentTimeMillis() + JOB_MS
                    // Paced: one page at a time, longer when Telegram asked to wait.
                    scope.launch {
                        delay(maxOf(PACE_MS, pausedUntil - System.currentTimeMillis()))
                        if (running && session === s) runCatching { s.history(e.chat.id, next) }
                    }
                }
            }
        }
    }

    /** Closes TDLib (its thread ends once TDLib says it is closed). */
    @Synchronized fun close() {
        if (!running) return
        running = false
        runCatching { session?.close() }
        // A history fetch cut short: its pages so far are let go.
        SignalTesterHost.dropHistory()
        upd { it.copy(fetching = null) }
    }

    /**
     * Memory pressure: closed before anything that watches positions; the next round starts it again after the pressure. False:
     * kept - a login Boss is in the middle of (16 Oct: they leave the app to read the code in Telegram, and on Android 14 that
     * alone is a "background" trim, which closed the login under them) is kept through a background or a moderate trim, for
     * [LOGIN_KEEP_MS] from its start at most (Android 14+ sends only background trims, so this cap is what bounds it there); real
     * shortage ([MemoryPlan.RUNNING_LOW] and worse while showing, [MemoryPlan.MODERATE] and worse in the background) still closes it.
     */
    fun releaseForMemory(level: Int = com.optionslab.ira.MemoryPlan.COMPLETE): Boolean {
        val mild = level < com.optionslab.ira.MemoryPlan.RUNNING_LOW ||
            level in com.optionslab.ira.MemoryPlan.BACKGROUND until com.optionslab.ira.MemoryPlan.MODERATE
        if (mild && loggingIn()) return false
        // Read before the close: TDLib's "closed" may reach the view before the close returns.
        val atStep = running && _view.value.auth in STEPS
        jobUntil = 0L; close()
        if (atStep) tell("Telegram was closed to free memory: tap \"Log in to Telegram\" to carry on where you were.")
        return true
    }

    /** A login Boss started is in progress: running, at one of its steps, within its job's time. */
    private fun loggingIn(): Boolean {
        val now = System.currentTimeMillis()
        return running && session?.auth in STEPS && now < jobUntil && now - startedAt < LOGIN_KEEP_MS
    }

    // ---- Boss's taps (off the main thread) ----------------------------------------------------------------------------------

    /** What to check when the login stays "starting" ([STUCK_MS]). */
    internal const val STUCK_WORDS = "Telegram has not answered for 20 seconds. Check: the phone is online (Wi-Fi or mobile data; no VPN, " +
        "firewall or data saver blocking IraAlgo), the date and time are set automatically, and the api_id / api_hash are the ones on " +
        "my.telegram.org. Then tap \"Try again\"."

    @Volatile private var failed: String? = null
    /** TEST SEAM: [STUCK_MS], shorter in the tests. */
    @Volatile internal var stuckMs = STUCK_MS

    /**
     * The login started (or, already running, where it is said again). Every outcome is told in the dialog ([say]); "Starting
     * Telegram…" BEFORE TDLib is opened, so TDLib's first answer (the phone step) is never overwritten by it.
     */
    fun startLogin(prefix: String = ""): String {
        if (!available()) return tell(prefix + (_view.value.why?.let { "$it." } ?: "Telegram login is not in this build."))
        if (params() == null) return tell(prefix + "Enter your api_id and api_hash first.")
        val s = session
        // At a step Boss can take (or logged in): said where it is. Any other state (a step only the Telegram app can do, say):
        // closed and started afresh, so a tap after finishing it there tries again.
        if (running && s != null && s.auth in AT_STEP) {
            jobUntil = System.currentTimeMillis() + JOB_MS
            return tell(prefix + "Telegram is " + s.auth.words + ".")
        }
        if (running && s != null && s.auth != TdSession.Auth.STARTING && s.auth != TdSession.Auth.OFF) return restart(prefix)
        val r = tell(prefix + "Starting Telegram…")
        if (openForJob()) return r
        return tell(prefix + when {
            loop?.isAlive == true -> "Telegram is still closing its last session: try again in a few seconds."
            else -> "Telegram could not start" + (failed?.let { " ($it)" } ?: "") + "."
        })
    }

    private val AT_STEP = setOf(TdSession.Auth.NEED_PHONE, TdSession.Auth.NEED_CODE, TdSession.Auth.NEED_PASSWORD, TdSession.Auth.READY)

    /** "Try again" after a stuck start: TDLib closed and started afresh (a second tap meanwhile waits for the first). */
    fun retryLogin(): String = restart("")

    private val restarting = java.util.concurrent.atomic.AtomicBoolean(false)

    /** TDLib closed, its thread let finish (up to 16 s), then the login started again. Blocking: Boss's taps run off the main thread. */
    private fun restart(prefix: String): String {
        if (!restarting.compareAndSet(false, true)) return "Restarting Telegram…"
        try {
            tell(prefix + "Restarting Telegram…")
            jobUntil = 0L
            close()
            val end = System.currentTimeMillis() + 16_000
            while (loop?.isAlive == true && System.currentTimeMillis() < end) Thread.sleep(250)
            return startLogin(prefix)
        } finally { restarting.set(false) }
    }

    // Each step told BEFORE it goes to TDLib, so TDLib's answer (the next step, or its error) is what stays on screen.
    fun phone(p: String): String = session?.let { tell("Sent. Telegram sends you a login code…"); openForJob(); it.phone(p); "Sent." } ?: tell("Tap \"Log in to Telegram\" first.")
    fun code(c: String): String = session?.let { tell("Checking the code…"); openForJob(); it.code(c); "Checking the code…" } ?: tell("Tap \"Log in to Telegram\" first.")
    fun password(p: String): String = session?.let { tell("Checking the password…"); openForJob(); it.password(p); "Checking the password…" } ?: tell("Tap \"Log in to Telegram\" first.")

    /** The channel list again (titles only). */
    fun loadChannels(): String {
        if (!SecurePrefs.getBoolean(K_LOGGED_IN, false)) return tell("Log in first.")
        if (!openForJob()) return tell("Telegram could not start.")
        val s = session ?: return tell("Telegram could not start.")
        tell("Reading your channel list (titles only)…")
        if (s.auth == TdSession.Auth.READY) { s.loadChats(); scope.launch { delay(3_000); upd { it.copy(channels = s.channels()) } } }
        return "Reading your channel list (titles only)…"
    }

    /** Boss's selection (ids and titles): only these are read; the reader is told at once. */
    fun select(chosen: Map<Long, String>): String {
        val r = SignalTesterHost.telegramSelect(chosen)
        session?.selected = chosen.keys
        return tell(r)
    }

    /** "Check this chat", on Boss's tap only: that one channel's last 100 posts counted, nothing of them kept. */
    fun check(id: Long): String {
        if (!openForJob()) return tell("Telegram could not start.")
        tell("Checking…")
        return runCatching { session?.history(id, check = true); "Checking…" }.getOrElse { tell("That chat cannot be checked.") }
    }

    /** A selected channel's history from 20 Nov 2024, paged and paced, for the back-test. */
    fun fetchHistory(id: Long): String {
        if (!openForJob()) return tell("Telegram could not start.")
        val r = tell("Reading its history back to ${TdSession.HISTORY_FROM}, a page at a time.")
        return runCatching { upd { it.copy(fetching = "history…") }; session?.history(id); r }
            .getOrElse { upd { it.copy(fetching = null) }; tell("Select the channel first.") }
    }

    /**
     * Log out of Telegram: the session logged out at Telegram, then wiped on this phone - TDLib's database and files, their key,
     * the api keys and the login flag. What the tester recorded stays (its scorecards).
     */
    suspend fun logOut(): String {
        // No round may start it again meanwhile; the login flag goes first.
        loggingOut = true
        tell("Logging out of Telegram…")
        try {
            SecurePrefs.put(K_LOGGED_IN, false)
            upd { it.copy(loggedIn = false) }
            // Logged out at Telegram itself (so the session leaves Boss's Active Sessions), started for it when not running.
            if (params() != null && (session == null || !running)) {
                var waited = 0
                while (loop?.isAlive == true && waited < 16_000) { delay(250); waited += 250 }
                openForJob()
            }
            var waited = 0
            while (session?.auth.let { it == TdSession.Auth.STARTING || it == TdSession.Auth.OFF } && waited < 20_000) { delay(250); waited += 250 }
            val s = session
            if (s != null && s.auth == TdSession.Auth.READY) {
                runCatching { s.logOut() }
                waited = 0
                while (session === s && s.auth != TdSession.Auth.CLOSED && waited < 15_000) { delay(250); waited += 250 }
            }
            close()
            waited = 0
            while (loop != null && waited < 16_000) { delay(250); waited += 250 }
            app?.let { c -> runCatching { File(c.noBackupFilesDir, "tdlib").deleteRecursively() } }
            SecurePrefs.putAll(listOf(K_API_ID, K_API_HASH, K_DB_KEY, K_LOGGED_IN).associateWith { null })
            val r = "Logged out of Telegram; its session, keys and database are wiped from this phone."
            upd { View(available = it.available, capture = it.capture, from = it.from, to = it.to, why = it.why, said = r) }
            return r
        } finally { loggingOut = false }
    }

    // ---- the round ---------------------------------------------------------------------------------------------------------

    /** Capture should run at [now]: logged in, capture on, inside the window, a trading day, and no memory pressure. */
    internal fun wanted(now: LocalDateTime, v: View, pressure: Boolean, tradingDay: Boolean): Boolean =
        v.available && v.loggedIn && v.capture && tradingDay && !pressure && !now.toLocalTime().isBefore(v.from) && now.toLocalTime().isBefore(v.to)

    /**
     * The Signal tester's round (its own words-lane step, never the order path): TDLib started or closed for the window (the
     * reading itself is TDLib's thread, on each post's arrival).
     */
    fun ensure(now: LocalDateTime) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        val v = if (_view.value.available) _view.value else refresh()
        val trading = runCatching { Market.isTradingDay(now.toLocalDate()) }.getOrDefault(true)
        val want = wanted(now, v, runCatching { MemoryTrim.underPressure() }.getOrDefault(false), trading) && !loggingOut
        val job = System.currentTimeMillis() < jobUntil
        capturing = want
        if (want && !running) open()
        if (!want && !job && running) close()
    }

    /** Inside the capture window now (set by [ensure]): the reader thread then also looks at the newest posts ([POLL_MS]). */
    @Volatile private var capturing = false

    /** The diagnostics line: never a key, a phone number or a channel's name. */
    fun diagLine(): String {
        val v = _view.value
        return "Telegram login (Signal tester, read-only): ${if (v.available) "in this build" else "not in this build"} · " +
            "${if (v.loggedIn) "logged in" else "not logged in"} · capture ${if (v.capture) "on ${v.from}-${v.to}" else "off"} · " +
            "${if (v.running) "running" else "stopped"} · ${SignalTesterHost.telegramSelected().size} channels selected"
    }

    /** TEST ONLY: as a fresh process. */
    internal fun resetForTest() {
        runCatching { close() }
        loop?.join(2_000)
        session = null; transport = null; loop = null; running = false; jobUntil = 0L; lastPoll = 0L; pausedUntil = 0L; capturing = false
        retries = 0; loggingOut = false; startedAt = 0L; lastErrorAt = 0L; failed = null; stuckMs = STUCK_MS
        loaded = null; testTransport = null; _view.value = View()
    }
}
