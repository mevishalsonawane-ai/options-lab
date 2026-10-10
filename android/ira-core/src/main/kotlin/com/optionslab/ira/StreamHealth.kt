package com.optionslab.ira

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Zerodha's live price stream, its drops explained (2026-10-05). Boss, 5 Oct: "Check why live streaming is dropping again
 * and again." His diagnostics showed only "[error] Live price stream dropped; reconnecting" (throttled) - the socket's
 * close code, the error and how long it had been up were never kept, so nobody could tell why.
 *
 * Pure: the app's KiteStream hands in what it saw (close code and reason, the error's class and message, the HTTP code
 * of a refused handshake, how long it was up, ticks, tokens, foreground, network) and gets back
 *  - the cause in plain words ([cause], [plain]), for the banner and the diary,
 *  - the diary line ([diary]), scrubbed of the URL, api_key and access_token ([scrub]) - never a secret,
 *  - the stall watchdog's rule ([stalled]: open in market hours with no frame for [SILENCE_MS]) and the pause rule
 *    ([paused]: the watchdog's own clock jumped, so Android froze the app or the phone slept),
 *  - the wait before the next try ([wait]: a connection that drops within a minute does not reset the backoff),
 *  - the subscription cap ([cap], Kite takes at most [MAX_TOKENS] instruments on one connection) and message chunks,
 *  - the day's tally from the diary ([day]) for the diagnostics' "Live stream:" line ([statusLine]), and
 *  - the spoken answer to "why is live data dropping?" / "stream kyun band ho raha" ([asked], [answer]).
 */
object StreamHealth {
    /** Why a connection ended, as far as what it said goes. */
    enum class Cause { SILENT, PAUSED, PING, NETWORK_CHANGED, NETWORK_LOST, DNS, TIMEOUT, RESET, CLOSED, TOKEN, TOO_MANY, SERVER, TLS, OTHER }

    /** The diary's area for the stream's lines. */
    const val AREA = "stream"

    /** The stall watchdog: an open socket in market hours with no frame (Kite sends a heartbeat every second) for this long is dead. */
    const val SILENCE_MS = 10_000L

    /** The watchdog looks every [STEP_MS]; a gap longer than [PAUSE_MS] between its looks means the app was frozen or the phone slept. */
    const val STEP_MS = 2_000L
    const val PAUSE_MS = 15_000L

    /** A connection up at least this long was healthy: the next try starts again at once. */
    const val STABLE_MS = 60_000L
    /** Round 2 (9 Oct): the first try again after half a second, not a whole one (then doubling, as before). */
    const val FIRST_WAIT_MS = 500L
    const val MAX_WAIT_MS = 30_000L

    /** Kite: at most 3000 instruments on one connection. */
    const val MAX_TOKENS = 3000
    /** Tokens per subscribe / mode message: keeps every message small. */
    const val CHUNK = 500

    /** What one connection's end looked like. [code]/[reason]: a close frame; [error]/[message]: a failure; [http]: a refused handshake. */
    data class Drop(
        val code: Int? = null, val reason: String? = null,
        val error: String? = null, val message: String? = null, val http: Int? = null,
        val upMs: Long? = null, val ticks: Long = 0, val tokens: Int = 0,
        val foreground: Boolean? = null, val networkChanged: Boolean? = null, val watch: Boolean? = null,
        val forced: Cause? = null, val silentSec: Long? = null, val pausedSec: Long? = null,
        val tries: Int = 1,
    )

    // ---- secrets -------------------------------------------------------------------------

    private val KEYED = rx("(?i)\\b(access_token|api_key|api_secret|request_token|enctoken|token|key|secret)\\s*[=:]\\s*[^&\\s'\",;)]*")
    private val URL = rx("(?i)\\b(?:wss?|https?)://\\S+")

    /** [s] with any URL, "access_token=..."/"api_key=..." value and each of [secrets] taken out; one line, at most 160 characters. */
    fun scrub(s: String?, secrets: Collection<String?> = emptyList()): String {
        if (s.isNullOrBlank()) return ""
        var out: String = s
        secrets.filterNotNull().filter { it.length >= 4 }.forEach { out = out.replace(it, "‹hidden›") }
        out = URL.replace(out, "‹url›")
        out = KEYED.replace(out) { "${it.groupValues[1]}=‹hidden›" }
        return out.replace(Regex("\\s+"), " ").trim().take(160)
    }

    // ---- the cause -------------------------------------------------------------------------

    private val HTTP_IN = rx("\\b(?:was|response|http)\\s*'?(\\d{3})\\b", RegexOption.IGNORE_CASE)

    /** The HTTP code of a refused handshake, from the response or the error's words ("Expected HTTP 101 response but was '403 Forbidden'"). */
    fun httpOf(d: Drop): Int? = d.http?.takeIf { it != 101 } ?: d.message?.let { m -> HTTP_IN.findAll(m).map { it.groupValues[1].toInt() }.firstOrNull { it != 101 } }

    /**
     * Why [d] ended. A dead-connection kind of end (no answer, a timeout, a reset, a lost network, silence) right after the
     * app was frozen ([Drop.pausedSec]) is put down to the pause: the connection died while nothing in the app could run.
     */
    fun cause(d: Drop): Cause {
        val base = baseCause(d)
        return if (d.pausedSec != null && base in DEAD) Cause.PAUSED else base
    }

    private val DEAD = setOf(Cause.SILENT, Cause.PING, Cause.TIMEOUT, Cause.RESET, Cause.NETWORK_LOST)

    private fun baseCause(d: Drop): Cause {
        d.forced?.let { return it }
        val http = httpOf(d)
        val words = "${d.reason.orEmpty()} ${d.message.orEmpty()}".lowercase(Locale.ENGLISH)
        val err = d.error.orEmpty()
        when {
            http == 401 || http == 403 -> return Cause.TOKEN
            http == 429 -> return Cause.TOO_MANY
            http != null && http >= 500 -> return Cause.SERVER
        }
        if ("too many" in words || "connection limit" in words || "max connections" in words) return Cause.TOO_MANY
        if ("token" in words && ("invalid" in words || "expired" in words || "incorrect" in words)) return Cause.TOKEN
        if (d.code != null) return if (d.code == 1011 || d.code == 1013) Cause.SERVER else Cause.CLOSED
        return when {
            "ping" in words && "pong" in words -> Cause.PING
            d.networkChanged == true -> Cause.NETWORK_CHANGED
            err == "UnknownHostException" || "unable to resolve" in words -> Cause.DNS
            err.startsWith("SSL") || "certificate" in words || "handshake" in words && "ssl" in words -> Cause.TLS
            "reset" in words -> Cause.RESET
            err == "ConnectException" || err == "NoRouteToHostException" || "unreachable" in words || "connection abort" in words ||
                "enetunreach" in words || "network" in words -> Cause.NETWORK_LOST
            err == "SocketTimeoutException" || "timeout" in words || "timed out" in words -> Cause.TIMEOUT
            err == "EOFException" || "closed" in words || "broken pipe" in words || "end of" in words -> Cause.CLOSED
            http != null -> Cause.SERVER
            else -> Cause.OTHER
        }
    }

    /** The cause in a few plain words (never " · ", so the diary line splits cleanly). */
    fun plain(c: Cause, silentSec: Long? = null): String = when (c) {
        Cause.SILENT -> "no data from Zerodha for ${silentSec ?: (SILENCE_MS / 1000)} s"
        Cause.PAUSED -> "Android paused the app${silentSec?.let { " for $it s" }.orEmpty()}"
        Cause.PING -> "the connection stopped answering"
        Cause.NETWORK_CHANGED -> "the phone's network changed"
        Cause.NETWORK_LOST -> "the phone's internet dropped"
        Cause.DNS -> "no internet"
        Cause.TIMEOUT -> "Zerodha did not answer in time"
        Cause.RESET -> "the connection was reset"
        Cause.CLOSED -> "Zerodha closed the connection"
        Cause.TOKEN -> "Zerodha refused the session"
        Cause.TOO_MANY -> "too many connections on your API key"
        Cause.SERVER -> "Zerodha's stream server had an error"
        Cause.TLS -> "the secure connection check failed"
        Cause.OTHER -> "an unknown error"
    }

    private fun up(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }

    /**
     * The diary's line for one drop: "dropped: <plain> · up 12m 3s · ticks 1234 · tokens 25 · close 1001 going away · http 403 ·
     * foreground · network changed · watch running". Every free text from the socket is [scrub]bed with [secrets].
     */
    fun diary(d: Drop, secrets: Collection<String?> = emptyList()): String {
        val c = cause(d)
        val parts = ArrayList<String>()
        // "dropped" is a live connection lost (the day's tally counts these); "connect failed" one that never opened.
        parts += (if (d.upMs != null) "dropped: " else "connect failed: ") + plain(c, if (c == Cause.PAUSED) d.pausedSec ?: d.silentSec else d.silentSec)
        d.upMs?.let { parts += "up ${up(it)}" }
        if (d.tries > 1) parts += "try ${d.tries}"
        parts += "ticks ${d.ticks}"
        parts += "tokens ${d.tokens}"
        d.code?.let { parts += ("close $it " + scrub(d.reason, secrets)).trim() }
        d.error?.let { parts += ("${scrub(it, secrets)}: " + scrub(d.message, secrets)).trimEnd(' ', ':') }
        httpOf(d)?.let { parts += "http $it" }
        if (c == Cause.PAUSED && d.silentSec != null) parts += "silent ${d.silentSec} s"
        d.foreground?.let { parts += if (it) "foreground" else "background" }
        if (d.networkChanged == true) parts += "network changed"
        d.watch?.let { parts += if (it) "watch running" else "watch not running" }
        return parts.joinToString(" · ")
    }

    // ---- the watchdog and the retries ----------------------------------------------------

    /** The stall rule: [live] (opened) in market hours and no frame since [lastFrameAt] for [SILENCE_MS]. */
    fun stalled(live: Boolean, marketOpen: Boolean, lastFrameAt: Long, now: Long): Boolean =
        live && marketOpen && lastFrameAt > 0 && now - lastFrameAt >= SILENCE_MS

    /** The watchdog's own clock jumped from [lastLook] to [now]: the seconds the app was frozen (or the phone slept), else null. */
    fun paused(lastLook: Long, now: Long): Long? = (now - lastLook).takeIf { lastLook > 0 && it >= PAUSE_MS }?.let { it / 1000 }

    /**
     * The wait before the next try. A connection that stayed up [STABLE_MS] starts again at once; one that dropped sooner
     * (or never opened) doubles the last wait, so a socket refused or closed straight after opening is not hammered.
     * A refused session or too many connections waits the longest.
     */
    fun wait(lastWait: Long, upMs: Long?, cause: Cause): Long = when {
        cause == Cause.TOKEN || cause == Cause.TOO_MANY -> MAX_WAIT_MS
        upMs != null && upMs >= STABLE_MS -> FIRST_WAIT_MS
        lastWait <= 0 -> FIRST_WAIT_MS
        else -> (lastWait * 2).coerceAtMost(MAX_WAIT_MS)
    }

    /** At most [max] of [want], those in [first] (the indices, open positions) kept before the rest. */
    fun cap(want: Set<Long>, first: Collection<Long>, max: Int = MAX_TOKENS): Set<Long> {
        if (want.size <= max) return want
        val out = LinkedHashSet<Long>()
        for (t in first) { if (out.size >= max) break; if (t in want) out += t }
        for (t in want) { if (out.size >= max) break; out += t }
        return out
    }

    fun chunks(tokens: Collection<Long>, size: Int = CHUNK): List<List<Long>> = tokens.toList().chunked(size.coerceAtLeast(1))

    // ---- the day, from the diary -------------------------------------------------------

    data class Day(val drops: Int, val lastAt: LocalDateTime?, val lastWhy: String?, val whys: Map<String, Int>)

    private val LINE = rx("^(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2}) \\[([\\w-]+)\\] (.*)$")

    /** Today's drops in the diary [lines] ("MM-dd HH:mm:ss [area] text", oldest first), as of [now] (IST). */
    fun day(lines: List<String>, now: LocalDateTime): Day {
        var n = 0
        var lastAt: LocalDateTime? = null
        var lastWhy: String? = null
        val whys = LinkedHashMap<String, Int>()
        for (raw in lines) {
            val m = LINE.find(raw.trim()) ?: continue
            val (mo, d, h, mi, s, area, text) = m.destructured
            if (area != AREA || !text.startsWith("dropped: ")) continue
            if (mo.toInt() != now.monthValue || d.toInt() != now.dayOfMonth) continue
            val at = runCatching { LocalDateTime.of(now.year, mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()) }.getOrNull() ?: continue
            if (at.isAfter(now.plusMinutes(5))) continue
            val why = text.removePrefix("dropped: ").substringBefore(" · ").trim()
            n++; lastAt = at; lastWhy = why
            val key = why.replace(Regex("\\d+ s\\b"), "N s").replace(Regex(" for N s$"), "")
            whys[key] = (whys[key] ?: 0) + 1
        }
        return Day(n, lastAt, lastWhy, whys)
    }

    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH)

    /** The diagnostics' line: "Live stream: LIVE · drops today 3 · last drop 09:58:45 (no data from Zerodha for 12 s)". */
    fun statusLine(status: String, lines: List<String>, now: LocalDateTime): String {
        val d = day(lines, now)
        return "Live stream: $status · drops today ${d.drops}" + (d.lastAt?.let { " · last drop ${TIME.format(it)} (${d.lastWhy})" } ?: "")
    }

    // ---- asked by voice ------------------------------------------------------------------

    private fun norm(q: String) = q.lowercase(Locale.ENGLISH).replace('’', '\'').replace(rx("[?!.,]"), " ").replace(rx("\\s+"), " ").trim()

    private const val FEED = "(?:live (?:price |prices |data |market data |feed |ticks? |quotes? |stream |streaming |connection )?|price (?:feed|stream)|" +
        "(?:live |price |kite |zerodha |market data )?(?:stream|streaming|websocket|web socket)|ticks?|tick feed)"
    private const val TROUBLE = "(?:drop|drops|dropping|dropped|disconnect|disconnects|disconnecting|disconnected|cut|cuts|cutting|" +
        "reconnect|reconnects|reconnecting|stop|stops|stopping|stopped|break|breaks|breaking|broke|fail|fails|failing|failed|die|dies|dying|died|" +
        "going off|goes off|keeps? going|freez|freezes|freezing|frozen|stall|stalls|stalling|stalled|lag|lags|lagging)"
    private val ASKED = listOf(
        // "why is live data dropping", "why does the live stream keep dropping", "why is my price feed disconnecting"
        rx("\\b(?:why|what's|whats|what is|how come|kyun|kyon|kyu)\\b.*\\b$FEED\\b.*\\b$TROUBLE"),
        rx("\\b$FEED\\b.*\\b(?:keeps?|again and again|so often|baar baar|bar bar|repeatedly|every few)\\b.*\\b$TROUBLE"),
        rx("\\b$FEED\\b.*\\b$TROUBLE\\w*\\b.*\\b(?:why|again and again|so often|baar baar|bar bar|repeatedly)\\b"),
        // Hinglish: "stream kyun band ho raha", "live data baar baar band ho raha hai", "stream ka kya haal hai"
        rx("\\b$FEED\\b.*\\b(?:kyun|kyon|kyu|kiu)\\b.*\\b(?:band|toot|tut|ruk|kat|drop|disconnect)"),
        rx("\\b$FEED\\b.*\\b(?:baar baar|bar bar)\\b.*\\b(?:band|toot|tut|ruk|kat|drop|disconnect)"),
        // Round 16: "live stream ka kya haal hai", "how many times did the stream drop today"
        rx("\\b(?:live (?:price )?stream|price stream|kite stream|websocket|live data|live feed|price feed)\\b (?:ka|ki) (?:kya )?haal\\b"),
        rx("^(?:jarvis )?how (?:many times|often)\\b.*\\b(?:stream|streaming|websocket|live data|live feed|price feed|live prices)\\b.*\\b$TROUBLE"),
        // Round 14: "why do prices keep freezing" - the prices on screen stuck (never the market falling: no drop words here).
        rx("^(?:jarvis )?(?:why|how come|kyun)\\b.*\\b(?:prices|quotes|ticks|ltp)\\b.*\\b(?:keeps? |keep on )?(?:freez\\w*|frozen|stall\\w*|not updating|not refreshing|stuck on screen)"),
        // "is the live stream working", "how is the live stream", "live stream status"
        rx("^(?:jarvis )?(?:is|how is|how's|hows) (?:the |my )?(?:live (?:price )?stream|price stream|kite stream|zerodha stream|websocket)\\b.*\\b(?:working|ok|okay|fine|up|connected|healthy|doing)?$"),
        rx("^(?:the |my )?(?:live (?:price )?stream|price stream|kite stream|websocket) (?:status|health)$"),
    )
    private val ACTING = rx("^(?:please |jarvis )*(?:turn|switch|connect|reconnect|restart|start|stop|reset|fix|open|show|chart)\\b")

    /** Is [q] a question about the live price stream dropping? */
    fun asked(q: String): Boolean = askedKept.of(q) { askedFresh(q) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Boolean>(64)

    private fun askedFresh(q: String): Boolean {
        val t = norm(q)
        if (ACTING.containsMatchIn(t) && !t.startsWith("why")) return false
        if ("relay" in t || "static ip" in t || "voice" in t || "mic" in t) return false
        return ASKED.any { it.containsMatchIn(t) }
    }

    private val CLOCK = DateTimeFormatter.ofPattern("H:mm", Locale.ENGLISH)

    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    /** What a cause usually means and what Boss can check, by the plain words the diary keeps. */
    private fun meaning(why: String): String = when {
        why.startsWith("Android paused") -> "Android froze the app in the background (battery saving or the phone asleep), so the connection died while nothing ran. " +
            "Keeping the order watch running and IraAlgo out of battery saving keeps it alive"
        why.startsWith("no data from Zerodha") -> "the connection stayed open but nothing came through, so I closed it and opened a new one"
        why.startsWith("the connection stopped answering") -> "the connection went dead without being closed: usually the phone's network or Android holding the app back"
        why.startsWith("the phone's network changed") -> "the phone moved between Wi-Fi and mobile data, which ends every open connection"
        why.startsWith("the phone's internet dropped") || why.startsWith("no internet") -> "the phone's own internet was down"
        why.startsWith("Zerodha refused the session") -> "Zerodha no longer accepts today's login: logging in again fixes it"
        why.startsWith("too many connections") -> "Zerodha allows 3 live connections per API key; something else (another app or script on the same key) is using them"
        why.startsWith("Zerodha closed") || why.startsWith("Zerodha's stream server") -> "the drop was on Zerodha's side"
        else -> "I can't put that one in plainer words"
    }

    /** The spoken answer from the diary [lines] (oldest first) and the stream's [status] now (OFF, CONNECTING, LIVE, RETRYING). */
    fun answer(lines: List<String>, now: LocalDateTime, status: String, watchRunning: Boolean?): String {
        val d = day(lines, now)
        val out = ArrayList<String>()
        val st = when (status) { "LIVE" -> "live now"; "CONNECTING" -> "connecting now"; "RETRYING" -> "reconnecting now"; else -> "off now" }
        if (d.drops == 0) {
            out += "Boss, the live price stream hasn't dropped today; it's $st."
            if (status == "OFF") out += "It runs only with a Zerodha login during market hours."
            return out.joinToString(" ")
        }
        out += "Boss, the live price stream has dropped ${plural(d.drops, "time")} today, the last at ${CLOCK.format(d.lastAt!!)} (${d.lastWhy}); it's $st."
        val top = d.whys.entries.sortedByDescending { it.value }
        if (top.size > 1) out += "By cause: " + top.take(3).joinToString(", ") { "${it.key} ${plural(it.value, "time")}" } + "."
        val main = top.first().key
        out += (if (top.size == 1) "That means " else "The most common means ") + meaning(main) + "."
        if (watchRunning == false && top.any { it.key.startsWith("Android paused") || it.key.startsWith("the connection stopped") || it.key.startsWith("no data") })
            out += "The order watch isn't running right now, so Android can pause IraAlgo whenever it's in the background."
        out += "It reconnects by itself; I don't change a setting on my own."
        return out.joinToString(" ")
    }
}
