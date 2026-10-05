package com.optionslab.ira

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The relay's health, asked (usefulness round 19, 2026-10-05): "why is my relay failing?", "is my static IP working?",
 * "relay kyun nahi chal raha", "can I trade live right now?". Boss, 5 Oct: the relay to his static-IP server failed with
 * "Read timed out" / "socket is not established" for hours, with an odd "connected as opc" between; the Zerodha login
 * through it failed with "Relay: cannot reach ... on port 22"; the morning check said he was not on his registered static
 * IP - and he had no way to ask about it.
 *
 * Answered from the app's own diagnostics diary ("MM-dd HH:mm:ss [area] text", oldest first, already redacted): the
 * relay's connects and failed connects ([relay]), the Zerodha calls that failed through it ([zerodha] ... via relay, or a
 * "Relay: ..." reason), and the static-IP checks (any area). Counts and times only: when it last connected, how long it
 * has been failing and how often, the failure in plain words and the steps Boss takes himself. No raw error, user name,
 * key or host goes into the answer - only the registered IP the app already shows him.
 *
 * Reads only: nothing is switched, tested or connected here (the app's own retries stay as they are); "Connect & test"
 * stays Boss's own tap. "Can I trade live right now?" is said as facts only (mode, login, relay, static IP, kill
 * switch), never advice; a live order still needs his fingerprint. Pure.
 */
object RelayHealth {
    enum class Asked { RELAY, STATIC_IP, LIVE }

    /** What a failure was, as far as the words in the record go. */
    enum class Fail { TIMEOUT, REFUSED, CLOSED, AUTH, HOSTKEY, DNS, NETWORK, NO_KEY, NOT_SET, OTHER }

    enum class Kind { CONNECTED, FAILED, ZERODHA_FAILED, NEW_KEY, ON_STATIC_IP, OFF_STATIC_IP }

    /** One event from the diary. [login]: a Zerodha failure on the login itself. */
    data class Event(val at: LocalDateTime, val kind: Kind, val fail: Fail? = null, val login: Boolean = false)

    /**
     * What the app knows now, without connecting: [relayOn] the relay switched on, [relaySet] a server IP entered,
     * [connected] the relay's session is open right now, [registeredIp] the static IP Boss registered (shown in the app),
     * [live] live mode, [realOrders] real orders allowed, [linked]/[loggedIn] Zerodha, [kill] the kill switch on.
     */
    data class Setup(
        val relayOn: Boolean, val relaySet: Boolean, val connected: Boolean, val registeredIp: String?,
        val live: Boolean = false, val realOrders: Boolean = false, val linked: Boolean = false, val loggedIn: Boolean = false,
        val kill: Boolean = false,
    )

    /** How far back the diary is read. */
    const val DAYS = 7L

    /** Failures closer together than this are one try (each connection the app opens retries the relay). */
    const val BURST_SEC = 60L

    private fun norm(q: String) = q.lowercase(Locale.ENGLISH).replace('’', '\'').replace(rx("[?!.,]"), " ").replace(rx("\\s+"), " ").trim()

    private const val STATUS = "(?:work|works|working|worked|fail|fails|failing|failed|failure|down|up|running|connect|connected|connecting|connects|" +
        "disconnect|disconnected|disconnecting|drop|dropping|dropped|time out|timing out|timed out|timeout|timeouts|wrong|problem|problems|issue|issues|" +
        "status|health|healthy|ok|okay|fine|alive|answering|answer|reachable|unreachable|broken|broke|error|errors|stuck|dead|happening|happened)"
    private val RELAY_WORD = "(?:relay|relay server|static ip server|oracle server|oracle instance|cloud server|port 22)"
    private val RELAY = listOf(
        rx("\\b$RELAY_WORD\\b.*\\b$STATUS\\b"),
        rx("\\b$STATUS\\b.*\\b$RELAY_WORD\\b"),
        rx("^(?:what about |how is |how's |hows )?(?:the |my )?relay(?: server)?$"),
        rx("\\b(?:when|what time)\\b.*\\b(?:relay|relay server)\\b.*\\blast\\b"),
        // Hinglish: "relay kyun nahi chal raha", "relay ka kya haal hai", "relay kaam kar raha hai kya", "relay band hai kya".
        rx("\\brelay\\b.*\\b(?:kyun|kyon|kyu|kiu|chal|chalta|chalra|kaam|haal|band|theek|thik|sahi|kya hua|gadbad|dikkat|problem)\\b"),
        rx("\\b(?:kyun|kyon|kyu|kiu)\\b.*\\brelay\\b"),
        // Round 16: "why did the relay stop" (asked why only: "stop the relay" stays a step, never answered here).
        rx("^(?:jarvis )?(?:why|how come)\\b.*\\b$RELAY_WORD\\b.*\\b(?:stop|stops|stopped|stopping)\\b"),
    )
    private val STATIC = listOf(
        rx("\\bstatic ip\\b.*\\b(?:$STATUS|on|right|correct|registered|matching|match|matches|set|why|being used|used)\\b"),
        rx("\\b(?:am i|is the phone|is my phone|are we|are my orders|are orders)\\b.*\\b(?:on|from|using|through)\\b.*\\b(?:my |the )?(?:registered )?static ip\\b"),
        rx("^(?:what about |how is |how's |hows )?(?:the |my )?static ip$"),
        rx("\\bstatic ip\\b.*\\b(?:kyun|kyon|kyu|chal|kaam|haal|theek|thik|sahi|kya hua|gadbad|dikkat)\\b"),
    )
    private val LIVE = listOf(
        rx("^(?:jarvis )?(?:can|could) (?:i|we) (?:trade|place|send|put|take) (?:a |an |my |any )?(?:live )?(?:orders? |trades? )?live\\b(?! mode)"),
        rx("^(?:jarvis )?(?:can|could) (?:i|we) (?:place|send|put|take) (?:a |an |my |any )?live (?:orders?|trades?)\\b"),
        rx("^(?:jarvis )?(?:can|could|will|would|do|does) (?:a |my |the )?live (?:orders?|trades?|trading)\\b.*\\b(?:go through|work|working|be placed|reach|get through|possible|be possible)\\b"),
        rx("^(?:jarvis )?(?:are|is) (?:my )?live (?:orders?|trading|trades?)\\b.*\\b(?:working|going through|possible|blocked|getting through)\\b"),
        rx("\\b(?:abhi |kya )?live (?:trade|order|trading)\\b.*\\b(?:kar sakta|kar sakti|ho sakta|ho sakti|lag sakta|jayega|jaega|chalega|jaa sakta|ja sakta)\\b"),
    )
    /** Words that do something to the relay or the mode (asked as a step, never answered here). */
    private val ACTING = rx("^(?:please |jarvis )*(?:turn|switch|connect|reconnect|test|enable|disable|start|stop|restart|reset|forget|fix|go|make|set|use|try)\\b")
    private val ACTING_HI = rx("\\b(?:on|off|band|chalu|connect|test|reset|restart|start|shuru)\\s+(?:karo|kar do|kardo|kijiye|karna)\\b")
    private val ASKING = rx("^(?:why|when|what|how|is|are|am|can|could|will|would|do|does|did|has|have|kya|relay|static)\\b")

    /** Is [q] a question about the relay, the static IP or whether live orders can go now? */
    fun asked(q: String): Asked? = askedKept.of(q) { askedFresh(q) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Asked?>(64)

    private fun askedFresh(q: String): Asked? {
        val t = norm(q)
        if (ACTING_HI.containsMatchIn(t) || ACTING.containsMatchIn(t) && !ASKING.containsMatchIn(t)) return null
        if (rx("\\b(?:relay|static ip)\\b.*\\b(?:on|off)$").containsMatchIn(t) && !ASKING.containsMatchIn(t)) return null
        if (LIVE.any { it.containsMatchIn(t) }) return Asked.LIVE
        val relay = RELAY.any { it.containsMatchIn(t) }
        val static = STATIC.any { it.containsMatchIn(t) }
        return when {
            relay && !t.contains("static ip") -> Asked.RELAY
            static -> Asked.STATIC_IP
            relay -> Asked.RELAY
            else -> null
        }
    }

    private val LINE = rx("^(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2}) \\[([\\w-]+)\\] (.*)$")

    /** The failure in [m]'s words. */
    fun fail(m: String): Fail {
        val s = m.lowercase(Locale.ENGLISH)
        return when {
            "no server ip set" in s -> Fail.NOT_SET
            "no key yet" in s -> Fail.NO_KEY
            "hostkey" in s || "host key" in s || "identity changed" in s -> Fail.HOSTKEY
            "auth fail" in s || "auth cancel" in s || "refused the app's key" in s || "permission denied" in s -> Fail.AUTH
            "unknownhost" in s || "unknown host" in s || "unable to resolve" in s -> Fail.DNS
            "connection refused" in s || "econnrefused" in s -> Fail.REFUSED
            "network is unreachable" in s || "enetunreach" in s || "no route to host" in s || "ehostunreach" in s -> Fail.NETWORK
            "timed out" in s || "timeout" in s || "socket is not established" in s || "cannot reach" in s -> Fail.TIMEOUT
            "end of io stream" in s || "connection reset" in s || "connection closed" in s || "broken pipe" in s -> Fail.CLOSED
            else -> Fail.OTHER
        }
    }

    private val RELAY_REASON = rx("\\bRelay: (?:cannot reach|the server refused|the server's identity|no key yet|no server ip set)", RegexOption.IGNORE_CASE)

    /** The relay's events in the diary [lines] ("MM-dd HH:mm:ss [area] text", oldest first), as of [now] (IST). */
    fun events(lines: List<String>, now: LocalDateTime): List<Event> {
        val out = ArrayList<Event>()
        for (raw in lines) {
            val m = LINE.find(raw.trim()) ?: continue
            val (mo, d, h, mi, s, area, text) = m.destructured
            val at = runCatching {
                val x = LocalDateTime.of(now.year, mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
                if (x.isAfter(now.plusMinutes(5))) x.minusYears(1) else x
            }.getOrNull() ?: continue
            if (at.isBefore(now.minusDays(DAYS)) || at.isAfter(now.plusMinutes(5))) continue
            val low = text.lowercase(Locale.ENGLISH)
            when {
                area == "relay" && low.startsWith("connected") -> out += Event(at, Kind.CONNECTED)
                area == "relay" && low.startsWith("connect failed") -> out += Event(at, Kind.FAILED, fail(text.substringAfter(':', "")))
                area == "relay" && low.startsWith("new relay key") -> out += Event(at, Kind.NEW_KEY)
                area == "zerodha" && "-> failed" in low && (" via relay -> " in low || RELAY_REASON.containsMatchIn(text)) ->
                    out += Event(at, Kind.ZERODHA_FAILED, fail(text.substringAfter("-> FAILED", text)),
                        login = "/session/token" in low || "login" in low)
                "not on your registered static ip" in low || "not your registered static ip" in low -> out += Event(at, Kind.OFF_STATIC_IP)
                "on your registered static ip" in low -> out += Event(at, Kind.ON_STATIC_IP)
            }
        }
        return out.sortedBy { it.at }
    }

    private val CLOCK = DateTimeFormatter.ofPattern("H:mm", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** "11:55" today, "11:55 yesterday", "11:55 on Sat 3 Oct". */
    fun whenSaid(at: LocalDateTime, now: LocalDateTime): String = when (java.time.temporal.ChronoUnit.DAYS.between(at.toLocalDate(), now.toLocalDate())) {
        0L -> CLOCK.format(at)
        1L -> "${CLOCK.format(at)} yesterday"
        else -> "${CLOCK.format(at)} on ${DAY.format(at)}"
    }

    /** "3 hours 20 minutes", "45 minutes", "2 days". */
    fun span(d: Duration): String {
        val m = d.toMinutes().coerceAtLeast(0)
        return when {
            m < 1 -> "under a minute"
            m < 60 -> "$m minute${if (m == 1L) "" else "s"}"
            m < 48 * 60 -> (m / 60).let { h -> "$h hour${if (h == 1L) "" else "s"}" } + (m % 60).let { r -> if (r == 0L) "" else " $r minute${if (r == 1L) "" else "s"}" }
            else -> "${m / (24 * 60)} days"
        }
    }

    /** The tries among [fails] (failures within [BURST_SEC] of the one before are one try): their start times. */
    fun tries(fails: List<Event>): List<LocalDateTime> {
        val out = ArrayList<LocalDateTime>()
        var last: LocalDateTime? = null
        for (e in fails) {
            if (last == null || Duration.between(last, e.at).seconds > BURST_SEC) out += e.at
            last = e.at
        }
        return out
    }

    /** The usual gap between tries, in whole minutes (the median), when there are at least three tries. */
    fun cadence(fails: List<Event>): Long? {
        val t = tries(fails)
        if (t.size < 3) return null
        val gaps = t.zipWithNext { a, b -> Duration.between(a, b).toMinutes() }.sorted()
        return gaps[gaps.size / 2].coerceAtLeast(1)
    }

    /** What the failure means, in plain words. */
    fun meaning(f: Fail): String = when (f) {
        Fail.TIMEOUT -> "a timeout reaching the server on port 22: nothing answered at all. That is usually the cloud instance stopped, the reserved IP " +
            "detached from it, port 22 closed in its security list, or the network in between"
        Fail.REFUSED -> "the server's address answered but refused port 22: the server is up but its SSH service isn't running, or its own firewall turns the app away"
        Fail.CLOSED -> "the server took the connection and then dropped it: its SSH service restarting or overloaded, or the network cutting out"
        Fail.AUTH -> "the server answered but refused the app's key: the key isn't in the login user's authorized keys on the server"
        Fail.HOSTKEY -> "the server's identity changed since the app first met it: a rebuilt server, or something else now answering on that IP"
        Fail.DNS -> "the server's name could not be looked up: the address entered isn't a plain IP, or the phone's network had no name service"
        Fail.NETWORK -> "the phone had no route to the server: the phone's own connection was down, or the IP isn't routed to anything"
        Fail.NO_KEY -> "the app has no relay key yet: one has to be made and added to the server"
        Fail.NOT_SET -> "no server IP is entered for the relay"
        Fail.OTHER -> "an error I can't put in plain words"
    }

    /** The steps Boss takes himself for [f]. */
    fun steps(f: Fail?): String = when (f) {
        Fail.AUTH -> "What you can check yourself: log in to the server once and add the app's key (Zerodha, Static IP shows it) to the login user's authorized keys, then tap Connect & test there."
        Fail.HOSTKEY -> "What you can check yourself: if you rebuilt the server, tap Forget server under Zerodha, Static IP, then Connect & test; if you didn't, find out what is answering on that IP before you trust it."
        Fail.NO_KEY, Fail.NOT_SET -> "What you can do yourself: under Zerodha, Static IP, make the key, add it to the server, enter its IP and tap Connect & test."
        Fail.NETWORK, Fail.DNS -> "What you can check yourself: the phone's own internet first, then that the cloud instance is running with the reserved IP attached, then tap Connect & test under Zerodha, Static IP."
        Fail.REFUSED, Fail.CLOSED -> "What you can check yourself: the cloud instance is running and its SSH service is up (a reboot from the cloud console does it), then tap Connect & test under Zerodha, Static IP."
        else -> "What you can check yourself: in the cloud console, that the instance is running, that the reserved IP is attached to it, and that port 22 is open in its security list; then tap Connect & test under Zerodha, Static IP."
    }

    private const val NO_ACT = "I don't change a setting or connect on my own."

    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    /** The relay's state as said: what happened, how long, how often, why in plain words. Empty when nothing is to be said. */
    private fun relaySaid(ev: List<Event>, s: Setup, now: LocalDateTime): Pair<List<String>, Fail?> {
        val out = ArrayList<String>()
        if (!s.relaySet) return listOf("The relay isn't set up on this phone, Boss, so orders go to Zerodha straight from the phone's own connection.") to null
        val conn = ev.filter { it.kind == Kind.CONNECTED || it.kind == Kind.FAILED }
        val off = if (s.relayOn) "" else " It is switched off now, so orders go to Zerodha straight from the phone's connection."
        if (conn.isEmpty()) {
            out += (if (s.connected) "The relay is connected right now, Boss, and" else "Boss,") +
                " I have no relay connects or failures in the record for the last $DAYS days." + off
            return out to null
        }
        val lastOk = conn.lastOrNull { it.kind == Kind.CONNECTED }
        val lastFail = conn.lastOrNull { it.kind == Kind.FAILED }
        val today = conn.filter { it.at.toLocalDate() == now.toLocalDate() }
        val failsToday = today.filter { it.kind == Kind.FAILED }
        val failing = lastFail != null && (lastOk == null || lastFail.at.isAfter(lastOk.at)) && !s.connected
        var kindOf: Fail? = null
        if (failing) {
            val streak = conn.filter { it.kind == Kind.FAILED && (lastOk == null || it.at.isAfter(lastOk.at)) }
            val since = streak.first().at
            // The day's trouble, when it began before the last good connect (on and off since then).
            val troubleStart = failsToday.firstOrNull()?.at?.takeIf { it.isBefore(since) }
            val okBetween = troubleStart?.let { t -> today.count { it.kind == Kind.CONNECTED && it.at.isAfter(t) } } ?: 0
            val every = cadence(streak)?.let { ", about every ${plural(it.toInt(), "minute")}" }.orEmpty()
            out += "Boss, the relay has been failing for ${span(Duration.between(since, now))}: " +
                "${plural(streak.size, "failed connect")} since ${whenSaid(since, now)}$every, the last at ${whenSaid(lastFail!!.at, now)}" +
                (lastOk?.let { "; the last good connect was at ${whenSaid(it.at, now)}" } ?: "; it hasn't connected once in the last $DAYS days of the record") + "."
            if (troubleStart != null && okBetween > 0)
                out += "It's been on and off since ${whenSaid(troubleStart, now)}: ${plural(failsToday.size, "failure")} today with ${plural(okBetween, "good connect")} between them."
            kindOf = dominant(streak)
        } else if (lastOk != null && failsToday.isNotEmpty()) {
            out += "The relay is connecting again, Boss: last good connect at ${whenSaid(lastOk.at, now)}. Earlier today it failed ${plural(failsToday.size, "time")} between " +
                "${whenSaid(failsToday.first().at, now)} and ${whenSaid(failsToday.last().at, now)}" +
                (cadence(failsToday)?.let { ", about every ${plural(it.toInt(), "minute")}" }.orEmpty()) + "."
            kindOf = dominant(failsToday)
        } else if (lastOk != null) {
            out += "The relay is working, Boss: last good connect at ${whenSaid(lastOk.at, now)}" +
                (if (lastFail != null) ", the last failure at ${whenSaid(lastFail.at, now)}" else ", with no failures in the last $DAYS days") + "."
        } else {
            out += "The relay is connected right now, Boss; the last failure in the record was at ${whenSaid(lastFail!!.at, now)}."
            kindOf = lastFail.fail
        }
        if (kindOf != null) {
            val all = (if (failing) conn.filter { it.kind == Kind.FAILED && (lastOk == null || it.at.isAfter(lastOk.at)) } else failsToday).mapNotNull { it.fail }
            val each = all.isNotEmpty() && all.all { it == kindOf }
            out += (if (each) (if (all.size == 1) "It was " else "Every one was ") else "Mostly it was ") + meaning(kindOf) + "."
            ev.lastOrNull { it.kind == Kind.NEW_KEY }?.takeIf { lastOk == null || it.at.isAfter(lastOk.at) }?.let {
                out += "A new relay key was made at ${whenSaid(it.at, now)}: the server needs that new key before it lets the app in."
            }
        }
        if (off.isNotEmpty()) out += off.trim()
        return out to (if (failing) kindOf else null)
    }

    private fun dominant(fails: List<Event>): Fail? = fails.mapNotNull { it.fail }.groupingBy { it }.eachCount().let { c ->
        val last = fails.lastOrNull()?.fail
        c.maxByOrNull { (k, n) -> n * 2 + (if (k == last) 1 else 0) }?.key
    }

    /** The Zerodha calls that failed through the relay today. */
    private fun zerodhaSaid(ev: List<Event>, now: LocalDateTime): String? {
        val z = ev.filter { it.kind == Kind.ZERODHA_FAILED && it.at.toLocalDate() == now.toLocalDate() }
        if (z.isEmpty()) return null
        val logins = z.filter { it.login }
        return if (logins.isNotEmpty()) "The Zerodha login through the relay failed ${plural(logins.size, "time")} today, the last at ${whenSaid(logins.last().at, now)}" +
            (if (z.size > logins.size) ", and ${plural(z.size - logins.size, "other Zerodha call")} failed through it too" else "") + "."
        else "${plural(z.size, "Zerodha call")} failed through the relay today, the last at ${whenSaid(z.last().at, now)}."
    }

    /** The static IP as the record and the app have it. */
    private fun staticSaid(ev: List<Event>, s: Setup, now: LocalDateTime): String {
        val ip = s.registeredIp ?: return "No static IP is registered in the app, so orders aren't checked against one; Zerodha refuses live orders from an IP it doesn't have for your app."
        val last = ev.lastOrNull { it.kind == Kind.ON_STATIC_IP || it.kind == Kind.OFF_STATIC_IP }
        return when {
            last == null -> "Your registered static IP is $ip; I have no check of it in the record for the last $DAYS days."
            last.kind == Kind.ON_STATIC_IP -> "The last static IP check, at ${whenSaid(last.at, now)}, found you on your registered static IP $ip."
            else -> "The last static IP check, at ${whenSaid(last.at, now)}, found you not on your registered static IP $ip: new live positions are refused until you are" +
                (if (s.relayOn) " (with the relay on, that means the relay isn't carrying the orders)." else ".")
        }
    }

    /** The spoken answer from the diary [lines] (oldest first) and the app's [setup] now. */
    fun answer(asked: Asked, lines: List<String>, now: LocalDateTime, setup: Setup): String {
        val ev = events(lines, now)
        val out = ArrayList<String>()
        val (relay, failing) = relaySaid(ev, setup, now)
        val stillFailing = failing != null
        when (asked) {
            Asked.RELAY -> {
                out += relay
                zerodhaSaid(ev, now)?.let { out += it }
                if (setup.registeredIp != null) out += staticSaid(ev, setup, now)
            }
            Asked.STATIC_IP -> {
                out += staticSaid(ev, setup, now).replaceFirst("The last", "Boss, the last").replaceFirst("Your registered", "Boss, your registered")
                    .replaceFirst("No static IP", "Boss, no static IP")
                if (setup.registeredIp != null) out += relay.map { it.replaceFirst(", Boss", "").replaceFirst("Boss, the relay", "The relay").replaceFirst("Boss, I", "I") }
                zerodhaSaid(ev, now)?.let { out += it }
            }
            Asked.LIVE -> return live(ev, setup, now, relay, stillFailing)
        }
        if (stillFailing) out += steps(failing)
        if (stillFailing || asked == Asked.RELAY) out += NO_ACT
        return out.joinToString(" ")
    }

    /** "Can I trade live right now?": the facts, each as it stands, and what they add up to - never advice. */
    private fun live(ev: List<Event>, s: Setup, now: LocalDateTime, relay: List<String>, failing: Boolean): String {
        val facts = ArrayList<String>()
        val blocks = ArrayList<String>()
        if (!s.live) { facts += "the app is in Paper mode"; blocks += "Paper mode" } else facts += "the app is in Live mode"
        if (s.live && !s.realOrders) { facts += "real orders are not allowed in Settings"; blocks += "real orders not allowed" }
        if (!s.linked) { facts += "Zerodha isn't linked"; blocks += "no Zerodha" }
        else if (!s.loggedIn) { facts += "Zerodha isn't logged in today"; blocks += "no Zerodha login" }
        else facts += "Zerodha is logged in"
        if (s.kill) { facts += "the kill switch is on"; blocks += "the kill switch" }
        if (s.relaySet && s.relayOn) {
            if (failing) { facts += "the relay is failing"; blocks += "the relay" }
            else if (s.connected) facts += "the relay is connected"
            else facts += "the relay is on"
        }
        val lastIp = ev.lastOrNull { it.kind == Kind.ON_STATIC_IP || it.kind == Kind.OFF_STATIC_IP }
        if (s.registeredIp != null && lastIp != null) {
            val reconnected = ev.lastOrNull { it.kind == Kind.CONNECTED }?.at?.takeIf { s.relayOn && s.connected && it.isAfter(lastIp.at) }
            when {
                lastIp.kind == Kind.ON_STATIC_IP -> facts += "the last static IP check (${whenSaid(lastIp.at, now)}) found you on your registered IP"
                reconnected != null -> facts += "the last static IP check (${whenSaid(lastIp.at, now)}) found you off your registered IP, " +
                    "before the relay connected again at ${whenSaid(reconnected, now)}"
                else -> { facts += "the last static IP check (${whenSaid(lastIp.at, now)}) found you off your registered IP"; blocks += "the static IP" }
            }
        }
        val out = ArrayList<String>()
        out += "Facts only, Boss: " + facts.joinToString("; ") + "."
        out += if (blocks.isEmpty()) "Nothing in that stops a live order now; each one still needs your fingerprint, and Zerodha has the last word."
        else "So a live order wouldn't go through right now (${blocks.joinToString(", ")})."
        if (failing) {
            relay.firstOrNull()?.let { out += it.replaceFirst("Boss, the relay", "The relay") }
            out += "Ask me why the relay is failing for what it means and what to check."
        }
        return out.joinToString(" ")
    }
}
