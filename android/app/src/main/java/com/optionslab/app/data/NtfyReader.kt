package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.signals.NtfyParser
import com.optionslab.engine.signals.NtfyRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * ntfy alerts' reader (25 Oct 2026): Boss's ntfy topic read in the background, handed to [NtfyArm.receive] (which marks each
 * message id read, in its encrypted book, before anything is decided on it).
 *
 * Settings (Settings → Automation → ntfy alerts): the topic, typed once and kept in the encrypted settings ([K_TOPIC]; shown masked,
 * never in a log, the diagnostics, a backup or an upload - anyone who knows it can post), the server (https only, ntfy.sh by
 * default, or a self-hosted one), an optional access token (sent as "Authorization: Bearer"), and an optional prefix a message must
 * start with. The On switch is the arm's ([NtfyArm.setOn], off by default).
 *
 * How it reads: its own coroutine on the IO pool - never the main thread, never the order or tick path - started and stopped by
 * [ensure]. 26 Oct (Boss: "I think ntfy messages are getting read late"): [ensure] is called by the reader's own supervisor
 * ([supervise], every few seconds while ntfy alerts is on with a topic set; started with the app) - it was called only by the
 * words lane, from 09:15 and once a minute or two, so the reader started late and a stopped one waited for the next round. With
 * ntfy armed the market watch also starts at 09:00 ([armedHint], [Jobs]), the reader's hours' open, so the phone is awake for it. First a catch-up poll
 * (".../json?poll=1&since=<last id>", or the last few minutes on a first start) so nothing sent during a restart or a gap is
 * missed; then the stream (".../json?since=<last id>"), held open (ntfy sends a keepalive every 45 s; 90 s of silence is a dead
 * line). On any failure: the next try after 1 s, 2 s, 4 s ... at most 15 s (26 Oct; 60 s when the server says 429), and each new connection starts with the catch-up poll
 * again. Overlaps are harmless: an id already read is skipped. It runs while ntfy alerts is on and a topic is set, on trading days
 * from 09:00 to the F&O close: each reconnect and a 30-second timer check the hours and stop it outside them (25 Oct review); a
 * message read outside them is recorded "outside market hours, not traded" and never waits to be decided. Under
 * memory pressure it is closed before anything that watches positions ([releaseForMemory]); the supervisor starts it again a few
 * seconds later (its memory is a connection and a line buffer) and the catch-up poll reads what came meanwhile.
 */
object NtfyReader {
    const val K_TOPIC = "ntfy.topic"
    const val K_SERVER = "ntfy.server"
    const val K_TOKEN = "ntfy.token"
    const val K_PREFIX = "ntfy.prefix"
    /** A stream silent this long (no keepalive) is a dead line. */
    const val SILENT_S = 90L

    data class Status(val running: Boolean = false, val connected: Boolean = false, val said: String = "not started",
                      val lastMessageAt: Long = 0L, val failures: Int = 0)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var call: Call? = null

    /** TEST SEAMS (JVM tests only): the server's address (a fake server on localhost) and the clock's "in hours" answer. */
    @Volatile internal var testServer: String? = null
    @Volatile internal var testInHours: Boolean? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(SILENT_S, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    }

    // ---- settings ----------------------------------------------------------------------------------------------------------

    data class Config(val topic: String?, val server: String, val token: String?, val prefix: String)

    /** The settings, read off the main thread (the encrypted settings). */
    fun config(): Config = Config(SecurePrefs.getString(K_TOPIC)?.takeIf { it.isNotBlank() },
        testServer ?: SecurePrefs.getString(K_SERVER)?.let { NtfyRules.server(it) } ?: NtfyRules.DEFAULT_SERVER,
        SecurePrefs.getString(K_TOKEN)?.takeIf { it.isNotBlank() }, SecurePrefs.getString(K_PREFIX).orEmpty())

    /** Boss's topic typed once: checked, kept encrypted. What to say (never the topic itself). */
    fun setTopic(topic: String): String {
        val t = topic.trim()
        if (t.isEmpty()) { SecurePrefs.put(K_TOPIC, null); restart(); return "Topic cleared. Nothing is read." }
        if (!NtfyRules.topicOk(t)) return "A topic is 1 to 64 letters, digits, - or _. Not saved."
        SecurePrefs.put(K_TOPIC, t)
        restart()
        return "Topic saved (****)." + if (NtfyRules.weakTopic(t)) " It is short or plain: anyone who guesses it can post an alert. Use a long random topic, " +
            "or an access token." else ""
    }

    /** The server ("" = ntfy.sh): https only. */
    fun setServer(raw: String): String {
        val s = NtfyRules.server(raw) ?: return "The server must be an https address (e.g. https://ntfy.sh). Not saved."
        SecurePrefs.put(K_SERVER, s.takeIf { it != NtfyRules.DEFAULT_SERVER })
        restart()
        return "Server: $s."
    }

    /** The optional access token ("" clears it), kept encrypted, never shown again. */
    fun setToken(token: String): String {
        val t = token.trim()
        if (t.any { it.isWhitespace() || it.code < 33 }) return "A token has no spaces. Not saved."
        SecurePrefs.put(K_TOKEN, t.ifEmpty { null })
        restart()
        return if (t.isEmpty()) "Access token cleared." else "Access token saved (****)."
    }

    /** The optional prefix every message must start with ("" = none). */
    fun setPrefix(prefix: String): String {
        val p = prefix.trim().take(20)
        SecurePrefs.put(K_PREFIX, p.ifEmpty { null })
        // 25 Oct (review): a new prefix applies at once (each message also reads it fresh, [read]).
        restart()
        return if (p.isEmpty()) "No prefix: every message is read." else "Only messages starting with \"$p\" are read."
    }

    /** What the diagnostics say: the topic masked, never its words; the token only as set or not. */
    fun diagWords(): String = runCatching {
        val c = config()
        "${NtfyRules.masked(c.topic)}, token ${if (c.token != null) "set (****)" else "none"}, ${if (c.server == NtfyRules.DEFAULT_SERVER) "ntfy.sh" else "own server"}, " +
            _status.value.said
    }.getOrDefault("settings not read")

    // ---- running -----------------------------------------------------------------------------------------------------------

    internal fun inHours(): Boolean = testInHours ?: runCatching {
        val n = Market.now().toLocalDateTime()
        NtfyRules.outsideHours(n, Market.isTradingDay(n.toLocalDate()), com.optionslab.engine.NseHours.foClose(n.toLocalDate())) == null
    }.getOrDefault(false)

    /** 25 Oct (review): how often a running reader checks the hours (it stops at the F&O close, not at the next in-session round). */
    @Volatile internal var hoursCheckMs: Long = 30_000L

    /** Start the reader when it should run (on, a topic set, in hours), stop it when not. Cheap; off the main thread. */
    fun ensure() {
        // 26 Oct (review): a settings read that fails is "unknown": nothing is changed (a passing Keystore hiccup never stops the
        // reader nor moves the watch's alarm).
        val armed = runCatching { NtfyArm.view.value.on && config().topic != null }.getOrNull() ?: return
        noteArmed(armed)
        val want = armed && runCatching { inHours() }.getOrDefault(false)
        val running = job?.isActive == true
        if (want && !running) start() else if (!want && running) stop("off")
        // 25 Oct: today's stock options list, fetched in the background while stock signals may trade.
        // 26 Oct (review): at most every 5 minutes (ensure now runs every few seconds; a failing download is not retried each time).
        val nowMs = System.currentTimeMillis()
        if (want && nowMs - stocksAskedAt >= STOCKS_EVERY_MS && runCatching { NtfyArm.view.value.settings.stocks }.getOrDefault(false)) {
            stocksAskedAt = nowMs
            runCatching { StockOptions.refreshInBackground() }
        }
    }

    private fun restart() { if (job?.isActive == true) stop("settings changed"); ensure() }

    /** 26 Oct (review): when [ensure] last asked for today's stock options list (memory). */
    @Volatile private var stocksAskedAt = 0L
    private const val STOCKS_EVERY_MS = 5 * 60_000L

    // ---- supervision (26 Oct) ----------------------------------------------------------------------------------------------

    /**
     * ntfy alerts is on with a topic set (whatever the hour), as the last [ensure] found it. Memory only: the watch's start time
     * ([Jobs]) and its rest rules read it (armed: the watch starts at 09:00 and counts it as armed).
     */
    @Volatile var armedHint = false
        private set

    /** In the reader's hours with ntfy armed: counts as armed for any quiet or rest rule. Memory only. */
    fun armedNow(): Boolean = armedHint && runCatching { inHours() }.getOrDefault(false)

    @Volatile private var appContext: android.content.Context? = null
    /** The NightMarks key that keeps [armedHint] across a restart (a flag only; nothing about the topic). */
    internal const val EARLY_MARK = "ntfy.early"

    /** The app's start: its context kept (for the watch's alarm) and the supervisor started. No I/O on the caller's thread. */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        supervise()
    }

    /**
     * Armed for the morning (ntfy on, a topic set) as last known: memory, else the plain mark kept across a restart (off the main
     * thread only). For [Jobs]: the market watch starts at 09:00, the reader's hours' open, instead of 09:14.
     */
    fun earlyWatch(context: android.content.Context?): Boolean =
        armedHint || runCatching { com.optionslab.app.work.NightMarks.get(context, EARLY_MARK) == "1" }.getOrDefault(false)

    /** [armed] remembered; on a change the mark is written and the watch's start alarm moved, in the background. */
    private fun noteArmed(armed: Boolean) {
        if (armedHint == armed && armedKnown) return
        // 26 Oct (review): the first answer in a process re-arms the watch's alarm too when armed - a boot's arming on the main
        // thread could not read the mark and left it at 09:14.
        val changed = (armedKnown && armedHint != armed) || (!armedKnown && armed)
        armedHint = armed; armedKnown = true
        val c = appContext ?: return
        scope.launch {
            val was = runCatching { com.optionslab.app.work.NightMarks.get(c, EARLY_MARK) == "1" }.getOrDefault(false)
            if (was != armed || changed) {
                runCatching { com.optionslab.app.work.NightMarks.put(c, EARLY_MARK, if (armed) "1" else null) }
                runCatching { com.optionslab.app.work.Jobs.schedule(c, com.optionslab.app.work.Jobs.Kind.LIVE) }
            }
            // Armed inside the reader's hours (switched on at 09:05, or the app started then): the watch starts now, not at 09:14.
            if (armed && runCatching { inHours() }.getOrDefault(false)) runCatching { com.optionslab.app.work.Jobs.ensureWatch(c) }
        }
    }
    @Volatile private var armedKnown = false

    /** How often the supervisor looks while ntfy alerts is armed (a stopped reader is started again within this). */
    @Volatile internal var superviseMs: Long = 3_000L
    /** How often it looks while not armed (a switch on the page calls [ensure] itself, at once). */
    @Volatile internal var idleLookMs: Long = 30_000L
    @Volatile private var supervisor: Job? = null
    /** Looks the supervisor made (memory; the tests). */
    @Volatile internal var looks = 0

    /**
     * The reader's own supervisor (26 Oct): every [superviseMs] while armed, [ensure] - the reader started at its hours' open
     * (09:00), started again within seconds after a stop (memory pressure, a failure that ended it), stopped at the F&O close.
     * After a restart the open positions are restored and watched first ([PositionsFirst]); then the book is read. Its own
     * coroutine on the IO pool; never the main thread, the order or the tick path. Idempotent.
     */
    fun supervise() {
        if (com.optionslab.app.BuildConfig.GOLD) return
        synchronized(this) {
            if (supervisor?.isActive == true) return
            supervisor = scope.launch {
                runCatching { PositionsFirst.awaitPositions() }
                if (!NtfyArm.loaded()) runCatching { NtfyArm.refresh() }
                while (isActive) {
                    looks++
                    runCatching { ensure() }
                    delay(nextLookMs())
                }
            }
        }
    }

    /** The supervisor's next look: [superviseMs] while armed in hours; else [idleLookMs], or sooner to be on time for 09:00. */
    private fun nextLookMs(): Long {
        if (!armedHint) return idleLookMs
        if (runCatching { inHours() }.getOrDefault(false)) return superviseMs
        val toOpen = runCatching {
            val n = Market.now()
            val open = n.toLocalDate().atTime(NtfyRules.READ_FROM).atZone(n.zone)
            if (n.isBefore(open)) java.time.Duration.between(n, open).toMillis() + 200 else null
        }.getOrNull()
        return if (toOpen != null) toOpen.coerceIn(superviseMs, idleLookMs) else idleLookMs
    }

    /** For tests: the supervisor stopped. */
    internal fun stopSupervisor() { synchronized(this) { supervisor?.cancel(); supervisor = null } }

    @Synchronized private fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // 25 Oct (review): the stream is held open for hours; outside them (after the F&O close) it is stopped by this timer,
            // not left open all night waiting for the next in-session round's [ensure].
            val hours = launch { while (isActive) { delay(hoursCheckMs); if (!inHours()) { stop("outside market hours"); break } } }
            try { loop() } finally { hours.cancel() }
        }
    }

    @Synchronized fun stop(why: String) {
        job?.cancel(); job = null
        runCatching { call?.cancel() }; call = null
        _status.value = _status.value.copy(running = false, connected = false, said = "stopped ($why)")
    }

    /** Memory pressure: the reader closes (the supervisor starts it again within seconds; it catches up by its poll). True when it was running. */
    fun releaseForMemory(): Boolean {
        if (job?.isActive != true) return false
        stop("memory pressure")
        return true
    }

    private suspend fun loop() {
        val me = kotlinx.coroutines.currentCoroutineContext()[Job]
        var attempt = 0
        _status.value = Status(running = true, said = "starting")
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            // 25 Oct (review): every (re)connect checks the hours first - never a new connection after the F&O close.
            if (!inHours()) { _status.value = _status.value.copy(said = "stopped (outside market hours)"); break }
            val c = config()
            val topic = c.topic ?: break
            val ok = runCatching {
                // The catch-up poll first (after a restart or a gap), then the stream from the last id read.
                val v = if (NtfyArm.view.value.last != null) NtfyArm.view.value else NtfyArm.refresh()
                // After the last id read while it is recent; else the last few minutes (an old id may have left the server's cache).
                val lastAt = v.alerts.firstOrNull { it.id == v.last }?.readAt?.atZone(com.optionslab.engine.IST)?.toEpochSecond()
                val since = NtfyRules.since(v.last, lastAt, nowEpoch(), v.settings.staleMin)
                read(NtfyRules.pollUrl(c.server, topic, since), c)
                val after = NtfyArm.view.value.last ?: since
                _status.value = _status.value.copy(connected = true, said = "connected", failures = 0)
                attempt = 0
                read(NtfyRules.streamUrl(c.server, topic, after), c)
            }
            if (!kotlinx.coroutines.currentCoroutineContext().isActive || ok.exceptionOrNull() is kotlinx.coroutines.CancellationException) break
            val why = ok.exceptionOrNull()?.let { e -> (e as? HttpError)?.let { "server said ${it.code}" } ?: e.javaClass.simpleName } ?: "the line closed"
            val wait = NtfyRules.backoffMs(attempt++, (ok.exceptionOrNull() as? HttpError)?.code)
            _status.value = _status.value.copy(connected = false, said = "reconnecting in ${wait / 1000} s ($why)", failures = _status.value.failures + 1)
            runCatching { Diag.record("ntfy", "reader: $why; again in ${wait / 1000} s") }
            delay(wait)
        }
        // 26 Oct (review): only this run's own status (a reader started again meanwhile keeps its "running").
        if (job == null || job === me) _status.value = _status.value.copy(running = false, connected = false)
    }

    private fun nowEpoch(): Long = runCatching { Market.now().toEpochSecond() }.getOrElse { System.currentTimeMillis() / 1000 }

    private class HttpError(val code: Int) : Exception("http $code")

    private fun request(url: String, c: Config): Request = Request.Builder().url(url).header("Accept", "application/x-ndjson")
        .apply { c.token?.let { header("Authorization", "Bearer $it") } }.build()

    /** One poll or stream: each line read as it comes, its messages handed to the arm in order. */
    private suspend fun read(url: String, c: Config) = withContext(Dispatchers.IO) {
        val cl = client.newCall(request(url, c))
        call = cl
        cl.execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code)
            val src = r.body?.source() ?: return@use
            while (true) {
                val line = src.readUtf8Line() ?: break
                val e = NtfyRules.event(line) ?: continue
                if (!e.isMessage) continue
                _status.value = _status.value.copy(lastMessageAt = System.currentTimeMillis())
                // 25 Oct (review): the prefix read fresh for each message - a new one applies without a reconnect.
                NtfyArm.receive(listOf(e), runCatching { SecurePrefs.getString(K_PREFIX).orEmpty() }.getOrDefault(c.prefix))
            }
        }
    }

    /**
     * "Test" on the page: one fetch of the last hour, NOTHING marked read and nothing traded - what the topic sent and how each
     * message would be read. What to say (never the topic itself).
     */
    suspend fun test(): String = withContext(Dispatchers.IO) {
        val c = runCatching { config() }.getOrElse { return@withContext "The settings could not be read." }
        val topic = c.topic ?: return@withContext "Type your topic first."
        runCatching {
            client.newCall(request(NtfyRules.pollUrl(c.server, topic, "1h"), c)).execute().use { r ->
                if (r.code == 401 || r.code == 403) return@use "The server refused (${r.code}): check the access token."
                if (!r.isSuccessful) return@use "The server answered ${r.code}."
                val events = r.body?.string().orEmpty().lineSequence().mapNotNull { NtfyRules.event(it) }.filter { it.isMessage }.toList()
                if (events.isEmpty()) return@use "Connected. No messages in the last hour. Nothing was traded."
                val today = runCatching { Market.today() }.getOrElse { java.time.LocalDate.now() }
                val last = events.takeLast(3).reversed().joinToString("\n") { e ->
                    val p = NtfyParser.read(e.title?.let { NtfyRules.afterPrefix(it, c.prefix) ?: it }, e.message?.let { NtfyRules.afterPrefix(it, c.prefix) ?: it }, today)
                    "%02d:%02d ".format(e.at().hour, e.at().minute) + when (p) {
                        is NtfyParser.Parsed.Buy -> "would buy: ${p.line()}"
                        is NtfyParser.Parsed.Exit -> "would close: ${p.line()}"
                        is NtfyParser.Parsed.Refused -> "not a trade: ${p.reason}"
                    }
                }
                "Connected. ${events.size} message(s) in the last hour. The newest:\n$last\nNothing was traded or marked read."
            }
        }.getOrElse { "Could not reach the server (${it.javaClass.simpleName})." }
    }

    /** For tests: stopped and forgotten. */
    internal fun wipe() {
        stopSupervisor(); stop("test"); _status.value = Status(); testServer = null; testInHours = null
        armedHint = false; armedKnown = false; looks = 0; superviseMs = 3_000L; idleLookMs = 30_000L; stocksAskedAt = 0L
    }
}
