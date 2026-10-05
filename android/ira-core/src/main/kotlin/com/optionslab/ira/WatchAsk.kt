package com.optionslab.ira

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The order watch and IraAlgo's battery setting, asked (understanding round 16, 2026-10-05). On 5 Oct the notification
 * said "Order watch stopped: No check since 09:31" and "Order watch is stuck", and the morning check said IraAlgo was
 * battery-optimized - and Boss had no way to ask "is the order watch running?", "why did the watch get stuck?",
 * "battery setting kya hai" or "kya mera phone app ko rok raha hai" (they went to the orders list, the settings, or
 * nowhere).
 *
 * Answered from what [WatchHealth.statusLine] reads (the last finished check, the service's pulse, the step it is waiting
 * on, the battery setting) and today's [WatchHealth.AREA] lines of the diagnostics diary - the app's own words (step
 * names, class names), never a URL, key or token. Reads only: nothing is started, restarted, stopped or switched here, and
 * the battery setting stays Boss's own tap ("I don't change it myself"). "Stop the order watch" stays a command. Pure.
 */
object WatchAsk {
    enum class Asked { STATUS, STUCK, BATTERY, PHONE }

    private fun norm(q: String) = q.lowercase(Locale.ENGLISH).replace('’', '\'').replace(rx("[?!.,]"), " ").replace(rx("\\s+"), " ").trim()

    /** The order watch named: "the order watch", "my watch", "order watcher", "the watch service"; Hinglish "watch kyun ...". */
    private const val W = "(?:(?:the|my|order|orders|position|positions|iraalgo's|iraalgo|stop|stops) (?:order )?watch(?:er)?(?: service)?|^watch(?:er)?)"
    private const val STATE = "(?:running|on|working|alive|active|up|ok|okay|fine|checking|stuck|stopped|dead|down|off|hung|frozen|live)"
    private const val TROUBLE = "(?:stuck|stop|stops|stopped|stopping|stall|stalls|stalled|hang|hangs|hung|freez\\w*|frozen|die|dies|died|dead|" +
        "go down|went down|crash\\w*|fail|fails|failed|quit|ruk|ruka|ruki|band|atak|atka|atki|latak)"
    private val STATUS = listOf(
        rx("^(?:jarvis )?(?:is|was|are) $W (?:still |even |actually )?$STATE\\b"),
        rx("^(?:jarvis )?(?:how is|how's|hows|what's|whats|what is) (?:the )?(?:status of |state of )?$W(?: status| state| health| doing)?(?: now| right now| today)?$"),
        rx("^(?:the |my )?$W (?:status|health|state)(?: now| right now)?$"),
        // Hinglish: "order watch chal raha hai kya", "watch chalu hai", "order watch band hai kya"
        rx("$W\\b.*\\b(?:chal raha|chal rahi|chal rha|chalu|on hai|band hai|kaam kar raha|kaam kar rahi|theek hai|thik hai)"),
    )
    private val STUCK = listOf(
        // "why did the watch get stuck", "why did the order watch stop", "why is the order watch stuck"
        rx("\\b(?:why|how come|what made|what caused|kyun|kyon|kyu)\\b.*$W\\b.*\\b$TROUBLE\\b"),
        // "watch kyun ruk gaya", "order watch kyun band hua"
        rx("$W\\b.*\\b(?:kyun|kyon|kyu|kiu)\\b.*\\b(?:ruk|band|atak|atka|latak|stuck|stop|stopped|hang)"),
        rx("^(?:jarvis )?what (?:stopped|stalled|killed|froze) $W\\b"),
    )
    private val BATTERY = listOf(
        // "battery setting kya hai", "what is my battery setting", "is iraalgo's battery unrestricted", "is the app battery optimized"
        rx("\\bbattery\\b.*\\b(?:setting|settings|unrestricted|restricted|optimi[sz]\\w*|optimi[sz]ation)\\b"),
        rx("\\b(?:setting|settings)\\b.*\\bbattery\\b"),
        rx("^(?:jarvis )?(?:is|kya) (?:the app|iraalgo|ira algo|my app|the iraalgo app)\\b.*\\b(?:unrestricted|battery optimi[sz]\\w*|battery restricted)"),
    )
    // "is my phone stopping the app", "is android killing iraalgo", "kya mera phone app ko rok raha hai"
    private val PHONE_WHO = rx("\\b(?:phone|phones|android|mobile|system|battery saver)\\b")
    private val PHONE_DOES = rx("\\b(?:stop|stops|stopping|stopped|kill|kills|killing|killed|close|closes|closing|closed|block|blocks|blocking|blocked|" +
        "restrict\\w*|limit|limits|limiting|limited|paus\\w*|freez\\w*|sleep\\w*|rok|rokta|rokti|rok raha|rok rahi|band kar\\w*|maar\\w*|mar raha)\\b")
    private val PHONE_WHAT = rx("\\b(?:app|apps|iraalgo|ira algo|ira|watch|jarvis|background)\\b")
    private val QUESTION = rx("^(?:jarvis )?(?:is|does|did|has|have|was|are|could|can|will|why|kya|how come)\\b|\\b(?:kya|kyun|kyon)\\b|\\bhai kya$")

    private val ACTING = rx("^(?:please |jarvis )*(?:turn|switch|start|restart|stop|reset|fix|open|show|set|change|kill|close|run|resume|enable|disable)\\b")

    /** What [q] asks about the order watch or the battery setting, or null. */
    fun asked(q: String): Asked? {
        val t = norm(q)
        if (t.isEmpty() || ACTING.containsMatchIn(t)) return null
        if ("watchlist" in t || "watch list" in t || "watch out" in t || "kill switch" in t || "relay" in t || "static ip" in t) return null
        if (rx("\\b(?:stream|streaming|websocket|live data|price feed|ticks?)\\b").containsMatchIn(t)) return null
        if (BATTERY.any { it.containsMatchIn(t) } && !rx("\\b(?:phone's battery|battery (?:level|percent|percentage|low|charge|charging|life|drain\\w*))\\b").containsMatchIn(t)) return Asked.BATTERY
        if (STUCK.any { it.containsMatchIn(t) }) return Asked.STUCK
        if (STATUS.any { it.containsMatchIn(t) }) return Asked.STATUS
        if (QUESTION.containsMatchIn(t) && PHONE_WHO.containsMatchIn(t) && PHONE_DOES.containsMatchIn(t) && PHONE_WHAT.containsMatchIn(t)) return Asked.PHONE
        return null
    }

    // ---- the answer --------------------------------------------------------------------------

    private val LINE = rx("^(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2}) \\[([\\w-]+)\\] (.*)$")
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val WAITING = rx("waiting on (.+?)(?: for (\\d+)s)?(?: · |$)")

    /** Today's watch diary lines (time, text), oldest first. */
    fun today(lines: List<String>, now: LocalDateTime): List<Pair<LocalDateTime, String>> = lines.mapNotNull { raw ->
        val m = LINE.find(raw.trim()) ?: return@mapNotNull null
        val (mo, d, h, mi, s, area, text) = m.destructured
        if (area != WatchHealth.AREA || mo.toInt() != now.monthValue || d.toInt() != now.dayOfMonth) return@mapNotNull null
        val at = runCatching { LocalDateTime.of(now.year, mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()) }.getOrNull() ?: return@mapNotNull null
        if (at.isAfter(now.plusMinutes(5))) null else at to text
    }

    private fun mins(sec: Long): String = if (sec < 90) "$sec seconds" else "${sec / 60} minutes"

    /** A diary line in plain words, or null when it is not a stall, an end or a restart. */
    private fun plain(text: String): String? = when {
        text.startsWith("STUCK") -> WAITING.find(text)?.let { m ->
            "it was stuck waiting on ${m.groupValues[1]}" + (m.groupValues[2].toLongOrNull()?.let { " for ${mins(it)}" } ?: "")
        } ?: "it was stuck on a check that did not finish"
        text.startsWith("STOPPED") -> "it had stopped: the watch service was not running (Android or the app's process ended it)"
        text.startsWith("ended: ") && "market closed" !in text && "stopped from the notification" !in text -> "it " + text
        text.startsWith("restarted: ") -> "it " + text
        text.startsWith("Android refused") || text.startsWith("Android's time limit") -> text
        else -> null
    }

    private fun battery(restricted: Boolean?): String = when (restricted) {
        true -> "IraAlgo is battery-optimized, so Android can stop the order watch and the live stream in the background: set IraAlgo's battery to Unrestricted (App settings, then Battery, then Unrestricted)."
        false -> "IraAlgo's battery is set to Unrestricted, so Android's battery saving does not stop the watch."
        null -> "I can't read IraAlgo's battery setting just now; if it is not Unrestricted, set it (App settings, then Battery, then Unrestricted)."
    }

    /**
     * The spoken answer. [now]/[lastBeat]/[alivePulse]/[busyStep]/[busySince]: the watch's clocks as [WatchHealth.statusLine]
     * takes them; [batteryRestricted] null when unknown; [marketOpen] whether the watch should be checking now; [lines] the
     * diagnostics diary (oldest first) and [local] the time now in IST.
     */
    fun answer(asked: Asked, now: Long, lastBeat: Long, alivePulse: Long, busyStep: String?, busySince: Long, batteryRestricted: Boolean?,
               marketOpen: Boolean, lines: List<String>, local: LocalDateTime, zone: ZoneId): String {
        val state = WatchHealth.state(now, lastBeat, alivePulse)
        val last = if (lastBeat > 0 && Instant.ofEpochMilli(lastBeat).atZone(zone).toLocalDate() == local.toLocalDate())
            HM.format(Instant.ofEpochMilli(lastBeat).atZone(zone)) else null
        val waited = if (busyStep != null && busySince > 0 && now >= busySince) (now - busySince) / 1000 else null
        val watch = when (state) {
            WatchHealth.State.OK -> "the order watch is running: its last check was at ${last ?: "just now"}."
            WatchHealth.State.BUSY -> "the order watch is running but stuck: it has waited ${waited?.let { mins(it) } ?: "minutes"} on ${busyStep ?: "a network call"}" +
                (last?.let { ", and its last finished check was at $it" } ?: "") + ". Stops, targets and strategy exits are not checked until it answers."
            WatchHealth.State.DEAD -> if (!marketOpen) "the order watch isn't running; it runs only in market hours." +
                    (last?.let { " Its last check today was at $it." } ?: "")
                else "the order watch isn't running" + (last?.let { ": no check since $it" } ?: ": it has not run today") +
                    ". Stops, targets and strategy exits are not being watched; opening IraAlgo restarts it."
        }
        val events = today(lines, local).mapNotNull { (at, text) -> plain(text)?.let { at to it } }
        val out = ArrayList<String>()
        when (asked) {
            Asked.STATUS -> {
                out += "Boss, $watch"
                if (batteryRestricted == true) out += battery(true)
            }
            Asked.STUCK -> {
                if (state == WatchHealth.State.BUSY) out += "Boss, $watch"
                else {
                    val e = events.lastOrNull()
                    out += if (e == null) "Boss, I have no stall or stop of the order watch in today's record; $watch"
                        else "Boss, at ${HM.format(e.first)} ${e.second}. Now $watch"
                    if (events.size > 1) out += "That's ${events.size} stalls, stops or restarts today."
                }
                out += "A check that waits on the network (the relay or Zerodha) holds the watch until it answers; the app gives up on it and goes on."
                out += battery(batteryRestricted)
            }
            Asked.BATTERY -> {
                out += "Boss, " + battery(batteryRestricted)
                if (state != WatchHealth.State.OK && marketOpen) out += "Right now $watch"
            }
            Asked.PHONE -> {
                val androidEnds = events.count { "Android" in it.second || "stopped" in it.second }
                out += when (batteryRestricted) {
                    true -> "Boss, it can: IraAlgo is battery-optimized, so Android may pause or stop it in the background. Set IraAlgo's battery to Unrestricted (App settings, then Battery, then Unrestricted)."
                    false -> "Boss, IraAlgo's battery is already Unrestricted, so Android's battery saving shouldn't stop it; a phone's own cleaner or the app closing still can."
                    null -> "Boss, " + battery(null)
                }
                if (androidEnds > 0) out += "Today's record has ${if (androidEnds == 1) "1 time" else "$androidEnds times"} the watch was stopped or ended by Android or the app closing" +
                    " - the last at ${HM.format(events.last { "Android" in it.second || "stopped" in it.second }.first)}."
                out += "Right now " + watch
            }
        }
        out += "I don't change a setting or restart anything myself."
        return out.joinToString(" ")
    }
}
