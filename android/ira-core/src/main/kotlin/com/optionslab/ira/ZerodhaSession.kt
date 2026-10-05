package com.optionslab.ira

import com.optionslab.engine.IST
import com.optionslab.engine.Kite
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Why Boss was logged out of Zerodha (voice, round 14, 2026-10-05): "why was I logged out of Zerodha?", "why did Kite log
 * me out?", "what happened to my Zerodha session?", "when does my Zerodha login end?", "zerodha se logout kyun hua" -
 * answered from the app's own diagnostics diary, which since the Broker change of 5 Oct keeps each TokenException with the
 * request's path (never its query) and Kite's own message, and whether the profile still answered (the session kept) or
 * not (the session ended). The diary also has the day's login (POST /session/token), Boss's own logout from the app
 * (DELETE /session/token) and the app noticing an expired login; older records ("FAILED KiteError: Zerodha session
 * ended: ...") are read too.
 *
 * The first sentence says what happened (or that he is logged in now and until when), then why as far as the record goes,
 * and the refused calls that did not end it. Zerodha does not say why it ends a token; the usual causes are said as usual,
 * never as known. Reads only: nothing logs in, out or changes - logging in is Boss's own step on the Zerodha screen,
 * never by voice. The record has no key, token or name in it (the diary redacts them); the app keeps it off a locked
 * phone. Pure.
 */
object ZerodhaSession {
    enum class Asked { WHY, UNTIL }

    enum class Kind { LOGIN, OWN_LOGOUT, ENDED, KEPT, NOTICED }

    /** One session event from the diary: when, what, the request's [path] and Kite's [message] (TokenExceptions only). */
    data class Event(val at: LocalDateTime, val kind: Kind, val method: String = "", val path: String = "", val message: String = "")

    /** How far back the diary is read. */
    const val DAYS = 7L

    private val BROKER = "(?:zerodha|kite|broker|zerodha'?s|kite'?s)"
    private val WHY = listOf(
        rx("\\bwhy\\b.*\\b(?:was|am|did|do|does|got|get|getting|have|has|is|keep)\\b.*\\b(?:i|me|we)\\b.*\\b(?:logged|log|signed|sign|kicked|thrown|booted)\\s*(?:me\\s+)?out\\b"),
        rx("\\bwhy\\b.*\\b$BROKER\\b.*\\b(?:log|logged|logging|sign|signed|kick|kicked)\\s*(?:me|us)?\\s*out\\b"),
        rx("\\bwhy\\b.*\\b(?:my\\s+|the\\s+)?$BROKER\\s+(?:session|login|log ?in|token|connection)\\b.*\\b(?:end|ended|ending|expire|expired|expiring|drop|dropped|stop|stopped|die|died|go|gone|break|broke|cut)\\b"),
        rx("\\bwhy\\b.*\\b(?:my\\s+)?(?:session|login|token)\\b.*\\b(?:end|ended|expire|expired|drop|dropped|die|died|gone|stop|stopped)\\b.*\\b$BROKER\\b"),
        rx("\\bwhat\\s+happened\\s+(?:to|with)\\s+(?:my|the)\\s+$BROKER\\s+(?:session|login|log ?in|connection)\\b"),
        rx("\\b(?:when|what time)\\s+did\\s+(?:my|the)\\s+$BROKER\\s+(?:session|login|log ?in)\\s+(?:end|expire|drop|stop)\\b"),
        rx("\\bwhen\\s+(?:was|did)\\s+i\\s+(?:get\\s+)?(?:logged|log|signed)\\s+out\\s+of\\s+$BROKER\\b"),
        // Hinglish: "zerodha se logout kyun hua", "kite logout kyon ho gaya", "mera zerodha session kyun khatam hua".
        rx("\\b$BROKER\\b.*\\b(?:logout|log out|logged out|session|login)\\b.*\\b(?:kyun|kyon|kyu|kiu)\\b"),
        rx("\\b(?:kyun|kyon|kyu|kiu)\\b.*\\b$BROKER\\b.*\\b(?:logout|log out|logged out|session|login)\\b"),
    )
    private val UNTIL = listOf(
        rx("\\b(?:when|what time|till when|until when)\\b.*\\b(?:does|will|is)\\s+(?:my|the)\\s+$BROKER\\s+(?:session|login|log ?in|token)\\s+(?:end|expire|last|valid|good|run out)\\b"),
        rx("\\bhow\\s+long\\s+(?:does|will|is)\\s+(?:my|the)\\s+$BROKER\\s+(?:session|login|log ?in|token)\\s+(?:last|valid|good)\\b"),
        rx("\\bwhen\\s+will\\s+(?:i|$BROKER)\\s+(?:be\\s+|get\\s+)?(?:logged|log|sign|signed)\\s*(?:me\\s+)?out\\b"),
    )
    /** Words that do something about the login (asked as a step, never answered here). */
    private val ACTING = rx("\\b(?:log me (?:in|out)|log in now|re-?login|sign me (?:in|out))\\b")
    private val ACTING_HI = rx("\\b(?:login|logout|log in|log out) (?:karo|kar do|kardo|kijiye)\\b")
    /** A question about it ("why did Zerodha log me out"), not a step asked for. */
    private val ASKING = rx("^(?:why|when|what time|till when|until when)\\b")

    /** Is [q] a question about the Zerodha session ending (or when it ends)? */
    fun asked(q: String): Asked? {
        val t = q.lowercase(Locale.ENGLISH).replace('’', '\'').replace(rx("[?!.,]"), " ").replace(rx("\\s+"), " ").trim()
        if (ACTING_HI.containsMatchIn(t) || ACTING.containsMatchIn(t) && !ASKING.containsMatchIn(t)) return null
        if (UNTIL.any { it.containsMatchIn(t) }) return Asked.UNTIL
        if (WHY.any { it.containsMatchIn(t) }) return Asked.WHY
        return null
    }

    private val LINE = rx("^(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2}) \\[(\\w+)\\] (.*)$")
    private val TOKEN = rx("^Zerodha TokenException on (\\w+) (\\S+): (.*?)\\s*\\((the profile still answers: session kept|session ended)\\)\\s*$")
    private val OLD_END = rx("^(\\w+) (\\S+).* -> FAILED KiteError: Zerodha session ended: (.*?)(?:\\s*\\(\\d+ ms\\))?\\s*$")

    /** The session events in the diary [lines] ("MM-dd HH:mm:ss [area] text", oldest first), as of [now] (IST). */
    fun events(lines: List<String>, now: LocalDateTime): List<Event> {
        val out = ArrayList<Event>()
        for (raw in lines) {
            val m = LINE.find(raw.trim()) ?: continue
            val (mo, d, h, mi, s, area, text) = m.destructured
            val at = runCatching {
                val x = LocalDateTime.of(now.year, mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
                if (x.isAfter(now.plusMinutes(5))) x.minusYears(1) else x
            }.getOrNull() ?: continue
            if (at.isBefore(now.minusDays(DAYS))) continue
            when {
                area == "zerodha" && text.startsWith("POST /session/token") && text.contains("-> ok") -> out += Event(at, Kind.LOGIN)
                area == "zerodha" && text.startsWith("DELETE /session/token") -> out += Event(at, Kind.OWN_LOGOUT)
                area == "info" && text.startsWith("Zerodha session expired") -> out += Event(at, Kind.NOTICED)
                else -> {
                    val t = TOKEN.find(text)
                    if (t != null) {
                        out += Event(at, if (t.groupValues[4].startsWith("session ended")) Kind.ENDED else Kind.KEPT,
                            t.groupValues[1], t.groupValues[2], t.groupValues[3])
                    } else if (area == "zerodha") {
                        val o = OLD_END.find(text) ?: continue
                        // An older record (before the reason was kept on its own line), or the same end's FAILED line: kept once.
                        if (out.none { it.kind == Kind.ENDED && !it.at.isBefore(at.minusSeconds(10)) })
                            out += Event(at, Kind.ENDED, o.groupValues[1], o.groupValues[2], o.groupValues[3])
                    }
                }
            }
        }
        return out.sortedBy { it.at }
    }

    /** The request in words: "a call for your positions". */
    fun request(method: String, path: String): String {
        val p = path.substringBefore('?').lowercase(Locale.ENGLISH)
        val write = method.uppercase(Locale.ENGLISH) != "GET"
        return when {
            p.startsWith("/user/profile") -> "the profile check"
            p.startsWith("/portfolio/positions") -> if (write) "a position conversion" else "a call for your positions"
            p.startsWith("/portfolio/holdings") -> "a call for your holdings"
            p.startsWith("/user/margins") -> "a call for your margins"
            p.startsWith("/margins") || p.startsWith("/charges") -> "a margin check"
            p.startsWith("/orders") -> when (method.uppercase(Locale.ENGLISH)) {
                "GET" -> "a call for the order book"; "POST" -> "an order"; "PUT" -> "an order change"; "DELETE" -> "an order cancel"; else -> "an order call" }
            p.startsWith("/trades") -> "a call for the trade book"
            p.startsWith("/gtt") -> "a GTT call"
            p.startsWith("/quote") -> "a price quote"
            p.startsWith("/instruments") -> "the instruments list"
            else -> "a request"
        }
    }

    /** Kite's message as said: no backticks, its field names as words, one sentence at most. */
    fun message(m: String): String {
        var s = m.replace("`", "").replace(rx("\\bapi_key\\b"), "API key").replace(rx("\\baccess_token\\b"), "access token")
            .replace('"', '\'').trim().trimEnd('.', ' ')
        if (s.length > 120) s = s.take(117).trimEnd() + "..."
        return s
    }

    private val CLOCK = DateTimeFormatter.ofPattern("H:mm", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** "at 10:42 today", "at 15:40 yesterday", "on Sat 3 Oct at 10:42". */
    fun whenSaid(at: LocalDateTime, now: LocalDateTime): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(at.toLocalDate(), now.toLocalDate())
        return when (days) {
            0L -> "at ${CLOCK.format(at)} today"
            1L -> "at ${CLOCK.format(at)} yesterday"
            else -> "on ${DAY.format(at)} at ${CLOCK.format(at)}"
        }
    }

    /** "6:00 tomorrow morning", "6:00 this morning", "6:00 on Mon 5 Oct". */
    private fun sixSaid(at: LocalDateTime, now: LocalDateTime): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
        return "${CLOCK.format(at)} " + when (days) { 0L -> "this morning"; 1L -> "tomorrow morning"; -1L -> "yesterday morning"; else -> "on ${DAY.format(at)}" }
    }

    private fun expiry(login: LocalDateTime): LocalDateTime = Kite.expiresAt(login.atZone(IST)).withZoneSameInstant(IST).toLocalDateTime()

    private const val STEP = "Log in again yourself on the Zerodha screen when you want it; I never log in by voice."
    private const val CAUSES = "Zerodha doesn't say why; the usual causes are a logout from Kite web or the Kite app, which ends the app's login too, or Zerodha resetting sessions."

    /**
     * The spoken answer. [lines]: the diary as kept (oldest first); [linked]: Zerodha set up on this phone; [loggedIn]:
     * the day's login is good now; [expiresAt]: when the kept login ends (IST), if one is kept.
     */
    fun answer(asked: Asked, lines: List<String>, now: LocalDateTime, linked: Boolean, loggedIn: Boolean, expiresAt: LocalDateTime?): String {
        if (!linked) return "Zerodha isn't linked on this phone, Boss, so there's no session to end. Link it in Settings, Zerodha, if you want it."
        val ev = events(lines, now)
        val login = ev.lastOrNull { it.kind == Kind.LOGIN }
        // The end: Boss's own logout, or Zerodha's refusal that the profile check confirmed (the trade's own path preferred
        // over the profile check made for it, a few seconds apart).
        val endAt = ev.lastOrNull { it.kind == Kind.ENDED || it.kind == Kind.OWN_LOGOUT }
        val end = endAt?.let { e ->
            if (e.kind != Kind.ENDED) e
            else ev.filter { it.kind == Kind.ENDED && !it.at.isBefore(e.at.minusSeconds(10)) && !it.at.isAfter(e.at) }
                .let { c -> c.lastOrNull { !it.path.startsWith("/user/profile") } ?: c.last() }
        }
        val since = login?.at
        // The refused calls that did not end it, since the last login (or over the days read, if none is kept).
        val kept = ev.filter { it.kind == Kind.KEPT && (since == null || it.at.isAfter(since)) }
        val out = ArrayList<String>()

        fun ended(e: Event): String = if (e.kind == Kind.OWN_LOGOUT) "You logged out of Zerodha yourself from the app ${whenSaid(e.at, now)}."
        else {
            val what = request(e.method, e.path)
            val msg = message(e.message).takeIf { it.isNotBlank() }?.let { " (Kite said: \"$it\")" }.orEmpty()
            if (e.path.startsWith("/user/profile")) "Zerodha ended your session ${whenSaid(e.at, now)}: the profile check was refused$msg, so the day's login was gone."
            else "Zerodha ended your session ${whenSaid(e.at, now)}: $what was refused$msg, and the profile check was refused too, so the day's login was gone."
        }

        if (loggedIn) {
            val until = expiresAt ?: login?.let { expiry(it.at) }
            out += "You're logged in to Zerodha now, Boss" + (login?.takeIf { end == null || it.at.isAfter(end.at) }?.let { ", since " + whenSaid(it.at, now).removePrefix("at ").removePrefix("on ") }.orEmpty()) +
                (until?.let { "; Zerodha ends every login at ${sixSaid(it, now)}" } ?: "; Zerodha ends every login at 6 the next morning") + "."
            if (asked == Asked.WHY && end != null && (login == null || end.at.isBefore(login.at)))
                out += "Before that, " + ended(end).replaceFirst("You logged out", "you logged out")
            else if (asked == Asked.WHY) out += "Nothing ended it early in the last $DAYS days."
        } else when {
            end != null && (login == null || !end.at.isBefore(login.at)) -> {
                out += ended(end).replaceFirst("Zerodha ended", "Boss, Zerodha ended").replaceFirst("You logged out", "Boss, you logged out")
                if (end.kind == Kind.ENDED) out += CAUSES
            }
            login != null && !expiry(login.at).isAfter(now) ->
                out += "Boss, your Zerodha login from ${whenSaid(login.at, now).removePrefix("at ").removePrefix("on ")} ran out at ${sixSaid(expiry(login.at), now)}: Zerodha ends every login then, so each trading day needs a fresh one."
            expiresAt != null && !expiresAt.isAfter(now) ->
                out += "Boss, your Zerodha login ran out at ${sixSaid(expiresAt, now)}: Zerodha ends every login then, so each trading day needs a fresh one."
            else -> out += "You're not logged in to Zerodha, Boss, and I have no record of why in the last $DAYS days. Most often it's the 6 a.m. end Zerodha puts on every login."
        }
        if (kept.isNotEmpty()) {
            val last = kept.last()
            val lastSaid = request(last.method, last.path) + " " + whenSaid(last.at, now) +
                message(last.message).takeIf { it.isNotBlank() }?.let { ", Kite said: \"$it\"" }.orEmpty()
            out += (if (kept.size == 1) "One refused call did not end it ($lastSaid)" else "${kept.size} refused calls did not end it (the last, $lastSaid)") +
                ": the profile still answered, so the login was kept."
        }
        if (!loggedIn) out += STEP
        return out.joinToString(" ")
    }
}
