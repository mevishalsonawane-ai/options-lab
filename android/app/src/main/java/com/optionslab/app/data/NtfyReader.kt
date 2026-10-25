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
 * [ensure] (the watch's words lane calls it every round; the page calls it after a change). First a catch-up poll
 * (".../json?poll=1&since=<last id>", or the last few minutes on a first start) so nothing sent during a restart or a gap is
 * missed; then the stream (".../json?since=<last id>"), held open (ntfy sends a keepalive every 45 s; 90 s of silence is a dead
 * line). On any failure: the next try after 1 s, 2 s, 4 s ... at most 60 s, and each new connection starts with the catch-up poll
 * again. Overlaps are harmless: an id already read is skipped. It runs while ntfy alerts is on and a topic is set, on trading days
 * from 09:00 to the F&O close (messages outside are stale anyway and are caught up, as stale, by the next start's poll). Under
 * memory pressure it is closed before anything that watches positions ([releaseForMemory]); the next round starts it again.
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
        return if (p.isEmpty()) "No prefix: every message is read." else "Only messages starting with \"$p\" are read."
    }

    /** What the diagnostics say: the topic masked, never its words; the token only as set or not. */
    fun diagWords(): String = runCatching {
        val c = config()
        "${NtfyRules.masked(c.topic)}, token ${if (c.token != null) "set (****)" else "none"}, ${if (c.server == NtfyRules.DEFAULT_SERVER) "ntfy.sh" else "own server"}, " +
            _status.value.said
    }.getOrDefault("settings not read")

    // ---- running -----------------------------------------------------------------------------------------------------------

    private fun inHours(): Boolean = testInHours ?: runCatching {
        val n = Market.now()
        val m = n.hour * 60 + n.minute
        Market.isTradingDay(n.toLocalDate()) && m in (9 * 60) until com.optionslab.engine.NseHours.foClose(n.toLocalDate())
    }.getOrDefault(false)

    /** Start the reader when it should run (on, a topic set, in hours), stop it when not. Cheap; off the main thread. */
    fun ensure() {
        val want = runCatching { NtfyArm.view.value.on && config().topic != null && inHours() }.getOrDefault(false)
        val running = job?.isActive == true
        if (want && !running) start() else if (!want && running) stop("off")
    }

    private fun restart() { if (job?.isActive == true) stop("settings changed"); ensure() }

    @Synchronized private fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    @Synchronized fun stop(why: String) {
        job?.cancel(); job = null
        runCatching { call?.cancel() }; call = null
        _status.value = _status.value.copy(running = false, connected = false, said = "stopped ($why)")
    }

    /** Memory pressure: the reader closes (the next round starts it again and catches up by its poll). True when it was running. */
    fun releaseForMemory(): Boolean {
        if (job?.isActive != true) return false
        stop("memory pressure")
        return true
    }

    private suspend fun loop() {
        var attempt = 0
        _status.value = Status(running = true, said = "starting")
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val c = config()
            val topic = c.topic ?: break
            val ok = runCatching {
                // The catch-up poll first (after a restart or a gap), then the stream from the last id read.
                val since = NtfyRules.since(NtfyArm.view.value.last ?: NtfyArm.refresh().last, nowEpoch(), NtfyArm.view.value.settings.staleMin)
                read(NtfyRules.pollUrl(c.server, topic, since), c)
                val after = NtfyArm.view.value.last ?: since
                _status.value = _status.value.copy(connected = true, said = "connected", failures = 0)
                attempt = 0
                read(NtfyRules.streamUrl(c.server, topic, after), c)
            }
            if (!kotlinx.coroutines.currentCoroutineContext().isActive || ok.exceptionOrNull() is kotlinx.coroutines.CancellationException) break
            val why = ok.exceptionOrNull()?.let { e -> (e as? HttpError)?.let { "server said ${it.code}" } ?: e.javaClass.simpleName } ?: "the line closed"
            val wait = NtfyRules.backoffMs(attempt++)
            _status.value = _status.value.copy(connected = false, said = "reconnecting in ${wait / 1000} s ($why)", failures = _status.value.failures + 1)
            runCatching { Diag.record("ntfy", "reader: $why; again in ${wait / 1000} s") }
            delay(wait)
        }
        _status.value = _status.value.copy(running = false, connected = false)
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
                NtfyArm.receive(listOf(e), c.prefix)
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
    internal fun wipe() { stop("test"); _status.value = Status(); testServer = null; testInHours = null }
}
