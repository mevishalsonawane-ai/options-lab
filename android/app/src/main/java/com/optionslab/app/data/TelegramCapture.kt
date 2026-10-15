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

    /** The JSON interface as the app uses it (the real one is TDLib's; the tests pass a stand-in). */
    interface Transport {
        fun send(json: String)
        /** The next answer or update, or null after [timeoutSec]. One thread only (TDLib's rule). */
        fun receive(timeoutSec: Double): String?
    }

    /** TEST SEAM: a stand-in for TDLib (null in the app: the real library when present). */
    @Volatile internal var testTransport: (() -> Transport)? = null

    data class View(
        val available: Boolean = false, val auth: TdSession.Auth = TdSession.Auth.OFF, val hint: String? = null, val keys: Boolean = false,
        val loggedIn: Boolean = false, val capture: Boolean = false, val from: LocalTime = FROM, val to: LocalTime = TO, val running: Boolean = false,
        val channels: List<TdSession.Channel> = emptyList(), val fetching: String? = null, val said: String? = null,
    ) {
        /** The row's one status line. */
        val line: String get() = when {
            !available -> "Not in this build"
            !loggedIn && auth != TdSession.Auth.READY -> if (keys) "Not logged in" else "Not set up: enter your api_id and api_hash"
            !capture -> "Logged in (read-only) · capture off"
            else -> "Logged in (read-only) · capture $from-$to" + if (running) " · reading now" else ""
        } + (fetching?.let { " · $it" } ?: "")
    }

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) { app = context.applicationContext }

    // ---- the library -----------------------------------------------------------------------------------------------------

    @Volatile private var loaded: Boolean? = null

    /** TDLib is in this build and loads (once). Never on the main thread (it loads a native library). */
    fun available(): Boolean {
        if (com.optionslab.app.BuildConfig.GOLD) return false
        if (testTransport != null) return true
        loaded?.let { return it }
        val ok = runCatching { System.loadLibrary("tdjsonjava"); true }.getOrElse { false }
        loaded = ok
        return ok
    }

    private class Native : Transport {
        private val id = JsonClient.createClientId()
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

    /** Reads the settings into [view] (the card calls it once, off the main thread). */
    fun refresh(): View {
        val keys = SecurePrefs.getInt(K_API_ID, 0) > 0 && SecurePrefs.getString(K_API_HASH) != null
        upd { it.copy(available = available(), keys = keys, loggedIn = SecurePrefs.getBoolean(K_LOGGED_IN, false),
            capture = SecurePrefs.getBoolean(K_CAPTURE, false), from = time(K_FROM, FROM), to = time(K_TO, TO)) }
        return _view.value
    }

    /** Boss's own api_id (digits) and api_hash (32 hex characters) from my.telegram.org, kept encrypted on this phone only. */
    fun saveKeys(apiId: String, apiHash: String): String {
        val id = apiId.trim().toIntOrNull()
        val hash = apiHash.trim().lowercase()
        if (id == null || id <= 0) return "The api_id is a number (my.telegram.org → API development tools)."
        if (!Regex("[0-9a-f]{32}").matches(hash)) return "The api_hash is 32 letters and digits (0-9, a-f)."
        SecurePrefs.putAll(mapOf(K_API_ID to id, K_API_HASH to hash))
        upd { it.copy(keys = true) }
        return "Saved on this phone only (encrypted). Next: your phone number."
    }

    fun setCapture(on: Boolean): String {
        SecurePrefs.put(K_CAPTURE, on)
        upd { it.copy(capture = on) }
        if (!on) close()
        return if (on) "Capture on: the selected channels are read ${_view.value.from}-${_view.value.to} while the market is open." else "Capture off: Telegram is not read."
    }

    fun setWindow(from: LocalTime, to: LocalTime): String {
        if (!from.isBefore(to)) return "The start must be before the end."
        SecurePrefs.putAll(mapOf(K_FROM to from.toString(), K_TO to to.toString()))
        upd { it.copy(from = from, to = to) }
        return "Capture window $from-$to."
    }

    // ---- running TDLib -----------------------------------------------------------------------------------------------------

    /** TDLib started (if not running) for a job Boss started, kept open [JOB_MS] after it. False: not in this build. */
    private fun openForJob(): Boolean { jobUntil = System.currentTimeMillis() + JOB_MS; return open() }

    @Synchronized private fun open(): Boolean {
        if (running) return true
        // The last session's thread still closing: TDLib's receive must never run on two threads - the next round tries again.
        if (loop?.isAlive == true) return false
        if (!available()) return false
        val t = testTransport?.invoke() ?: runCatching { Native() }.getOrNull() ?: return false
        val s = TdSession { json -> t.send(json) }
        s.selected = SignalTesterHost.telegramSelected().keys
        transport = t; session = s; running = true
        s.start(params())
        loop = Thread({ loop(t, s) }, "telegram-reader").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        upd { it.copy(running = true, auth = s.auth) }
        return true
    }

    /** TDLib's answers and updates, read on this thread only, until it is closed. */
    private fun loop(t: Transport, s: TdSession) {
        var closingSince = 0L
        while (true) {
            val json = runCatching { t.receive(1.0) }.getOrNull()
            if (json != null) runCatching { handle(s, s.onUpdate(json)) }
            val alive = running && session === s
            if (alive && capturing && s.auth == TdSession.Auth.READY && System.currentTimeMillis() - lastPoll >= POLL_MS) {
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
        if (last) upd { it.copy(running = false, auth = if (it.auth == TdSession.Auth.READY) TdSession.Auth.CLOSED else it.auth, channels = emptyList()) }
    }

    private fun handle(s: TdSession, events: List<TdSession.Event>) {
        for (e in events) when (e) {
            is TdSession.Event.AuthChanged -> {
                upd { it.copy(auth = e.auth, hint = s.hint) }
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
                // TDLib's words only (never a message's text): a wrong code, a flood wait.
                upd { it.copy(said = "Telegram: ${e.message}") }
            }
        }
    }

    @Volatile private var pausedUntil = 0L

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
    }

    /** Memory pressure: closed before anything that watches positions; the next round starts it again after the pressure. */
    fun releaseForMemory() { jobUntil = 0L; close() }

    // ---- Boss's taps (off the main thread) ----------------------------------------------------------------------------------

    fun startLogin(): String {
        if (!available()) return "Telegram login is not in this build."
        if (params() == null) return "Enter your api_id and api_hash first."
        return if (openForJob()) "Starting Telegram…" else "Telegram could not start."
    }

    fun phone(p: String): String = session?.let { openForJob(); it.phone(p); "Sent. Telegram sends you a login code." } ?: "Start the login first."
    fun code(c: String): String = session?.let { openForJob(); it.code(c); "Checking the code…" } ?: "Start the login first."
    fun password(p: String): String = session?.let { openForJob(); it.password(p); "Checking the password…" } ?: "Start the login first."

    /** The channel list again (titles only). */
    fun loadChannels(): String {
        if (!SecurePrefs.getBoolean(K_LOGGED_IN, false)) return "Log in first."
        if (!openForJob()) return "Telegram could not start."
        val s = session ?: return "Telegram could not start."
        if (s.auth == TdSession.Auth.READY) { s.loadChats(); scope.launch { delay(3_000); upd { it.copy(channels = s.channels()) } } }
        return "Reading your channel list (titles only)…"
    }

    /** Boss's selection (ids and titles): only these are read; the reader is told at once. */
    fun select(chosen: Map<Long, String>): String {
        val r = SignalTesterHost.telegramSelect(chosen)
        session?.selected = chosen.keys
        return r
    }

    /** "Check this chat", on Boss's tap only: that one channel's last 100 posts counted, nothing of them kept. */
    fun check(id: Long): String {
        if (!openForJob()) return "Telegram could not start."
        return runCatching { session?.history(id, check = true); "Checking…" }.getOrElse { "That chat cannot be checked." }
    }

    /** A selected channel's history from 20 Nov 2024, paged and paced, for the back-test. */
    fun fetchHistory(id: Long): String {
        if (!openForJob()) return "Telegram could not start."
        return runCatching { session?.history(id); upd { it.copy(fetching = "history…") }; "Reading its history back to ${TdSession.HISTORY_FROM}, a page at a time." }
            .getOrElse { "Select the channel first." }
    }

    /**
     * Log out of Telegram: the session logged out at Telegram, then wiped on this phone - TDLib's database and files, their key,
     * the api keys and the login flag. What the tester recorded stays (its scorecards).
     */
    suspend fun logOut(): String {
        val s = session
        if (s != null && s.auth == TdSession.Auth.READY) {
            runCatching { s.logOut() }
            var waited = 0
            while (session === s && s.auth != TdSession.Auth.CLOSED && waited < 15_000) { delay(250); waited += 250 }
        }
        close()
        var waited = 0
        while (loop != null && waited < 16_000) { delay(250); waited += 250 }
        app?.let { c -> runCatching { File(c.noBackupFilesDir, "tdlib").deleteRecursively() } }
        SecurePrefs.putAll(listOf(K_API_ID, K_API_HASH, K_DB_KEY, K_LOGGED_IN).associateWith { null })
        upd { View(available = it.available, capture = it.capture, from = it.from, to = it.to) }
        return "Logged out of Telegram; its session, keys and database are wiped from this phone."
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
        val want = wanted(now, v, runCatching { MemoryTrim.underPressure() }.getOrDefault(false), trading)
        val job = System.currentTimeMillis() < jobUntil
        if (want && !running) open()
        if (!want && !job && running) close()
        capturing = want
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
        loaded = null; testTransport = null; _view.value = View()
    }
}
