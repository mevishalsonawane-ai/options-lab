package com.optionslab.ira

/**
 * Which English Jarvis's ears listen in (en-US or en-IN), kept steady (Boss, 5 Oct 08:53: "Listening switched to en-IN
 * (no words found in clear speech)" - a working English was left because, right after listening started, the room was
 * loud three turns running and nothing had yet given words in that run).
 *
 * The rules, all on the phone's own memory of codes and counts (never a word heard):
 *  - Listening starts in the English that last gave words (within [KEEP_DAYS]), not afresh each time.
 *  - "No words found in clear speech" ([CLEAR_IN_ROW] turns in a row) switches by itself at most ONCE a day, never
 *    away from an English that has given words, and never into one the phone refused today.
 *  - The phone refusing a language, or not having it, is a fact, not a guess: the other English is taken at once (or
 *    Jarvis would be deaf) - noted, and that English is not tried again by the clear-speech rule that day.
 * So the most it can do is one switch and one forced way back in a day. The Google speech choice is never touched here,
 * and nothing acts: this only names a language code for the recognizer. Pure: state in, state and words out.
 *
 * Round 20 (Boss, 5 Oct: the activity said "switched to en-IN" while the diagnostics said "language: en-US"): what is
 * said must be what is used.
 *  - Today's switch is kept across a restart of listening ([start], [pick]); a restart used to go back to English (US)
 *    without a word.
 *  - The phone's own list of on-device languages ([clearNoWords], [canTry]): an English it lacks is never switched to
 *    by the clear-speech rule (a Pixel without English (India) for on-device listening may take the code and listen in
 *    another English, or refuse it) - held, and said plainly ([missing]).
 *  - A move made because the phone lacks an English is recorded like any other, never silent.
 */
object ListenLanguage {
    const val US = "en-US"
    const val IN = "en-IN"
    /** An English that gave words this many days ago or less is "the one that works". */
    const val KEEP_DAYS = 14L
    /** Clear-sound turns with no words, in a row, before a switch is thought of (was 3). */
    const val CLEAR_IN_ROW = 4
    /** Switches by the clear-speech rule a day. */
    const val SOFT_A_DAY = 1

    enum class Why { CLEAR_NO_WORDS, REFUSED, MISSING }

    data class Switch(val date: String, val hhmm: String, val from: String, val to: String, val why: Why)

    data class State(
        /** The English that last gave words, and the day it did. */
        val worked: String? = null,
        val workedOn: String? = null,
        /** The day the counts below are for. */
        val day: String? = null,
        /** Switches by the clear-speech rule that day. */
        val soft: Int = 0,
        /** Switches forced by the phone (refused or missing) that day. */
        val forced: Int = 0,
        /** Switches the rules held back that day (a working English, or once a day already used). */
        val held: Int = 0,
        /** The English the phone refused that day. */
        val refused: String? = null,
        val last: Switch? = null,
    )

    fun norm(code: String?): String? {
        val c = code?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
        val p = c.split('-')
        return if (p.size >= 2) p[0].lowercase(java.util.Locale.ROOT) + "-" + p[1].uppercase(java.util.Locale.ROOT) else c.lowercase(java.util.Locale.ROOT)
    }

    fun other(lang: String): String = if (norm(lang) == IN) US else IN

    private fun daysBetween(a: String, b: String): Long? = runCatching {
        java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(a), java.time.LocalDate.parse(b))
    }.getOrNull()

    /** The English that has given words lately, or null. */
    fun works(s: State, date: String): String? {
        val w = norm(s.worked) ?: return null
        val d = daysBetween(s.workedOn ?: return null, date) ?: return null
        return if (d in 0..KEEP_DAYS) w else null
    }

    /** The counts for [date] (a new day starts them afresh; what worked is kept). */
    fun on(s: State, date: String): State = if (s.day == date) s else s.copy(day = date, soft = 0, forced = 0, held = 0, refused = null)

    /** Today's switch (by the rules or forced), kept so a restart of listening goes on in it. */
    fun today(s: State, date: String): String? = s.last?.takeIf { it.date == date }?.let { norm(it.to) }

    /** The English to start listening in: the one that works, else today's switch, else [fallback]. */
    fun start(s: State, date: String, fallback: String = US): String = works(s, date) ?: today(s, date) ?: norm(fallback) ?: US

    /** The phone's on-device languages, normalised; null when it cannot say (an empty list says nothing). */
    fun known(installed: List<String>?): List<String>? = installed?.mapNotNull { norm(it) }?.takeIf { it.isNotEmpty() }

    /** Whether [lang] may be asked of the recognizer: always with Google's speech service, or when the phone cannot say. */
    fun canTry(lang: String, installed: List<String>?, google: Boolean): Boolean {
        if (google) return true
        val have = known(installed) ?: return true
        return norm(lang) in have
    }

    /**
     * From the languages the phone's on-device recognizer has ([installed]; empty when it cannot say): the one that
     * works when it is there (or with Google's speech service, which has both), else English (US), English (India), any
     * English; null to keep the current one.
     */
    fun pick(installed: List<String>, s: State, date: String, google: Boolean): String? {
        val have = installed.mapNotNull { norm(it) }
        (works(s, date) ?: today(s, date))?.let { w -> if (google || w in have) return w }
        if (google) return null
        return listOf(US, IN).firstOrNull { it in have } ?: have.firstOrNull { it.startsWith("en-") || it == "en" }
    }

    /** Words were found while listening in [lang] (unchanged state when already known today, to spare a write). */
    fun gaveWords(s: State, lang: String, date: String): State {
        val l = norm(lang) ?: return s
        return if (norm(s.worked) == l && s.workedOn == date) s else s.copy(worked = l, workedOn = date)
    }

    /** [missing]: the switch was held because the phone has no on-device model for the other English. */
    data class Decision(val to: String?, val state: State, val held: Boolean, val missing: Boolean = false)

    /**
     * [inRow] turns of clear sound with no words, listening in [current]: the English to switch to, or null to stay
     * (with the reason counted as held when a switch was due). [installed]: the phone's on-device languages (null or
     * empty when it cannot say); with [google] off, an English not among them is never switched to.
     */
    fun clearNoWords(s0: State, current: String, date: String, hhmm: String, inRow: Int,
                     installed: List<String>? = null, google: Boolean = false): Decision {
        val s = on(s0, date)
        if (inRow < CLEAR_IN_ROW) return Decision(null, s0, false)
        val cur = norm(current) ?: US
        val to = other(cur)
        val keep = works(s, date) == cur || s.soft >= SOFT_A_DAY || s.refused == to
        if (keep) return Decision(null, s.copy(held = s.held + 1), true)
        if (!canTry(to, installed, google)) return Decision(null, s.copy(held = s.held + 1), true, missing = true)
        return Decision(to, s.copy(soft = s.soft + 1, last = Switch(date, hhmm, cur, to, Why.CLEAR_NO_WORDS)), false)
    }

    /** The phone refused [current] (or does not have it) and listening went to [to]: noted, and not retried today. */
    fun forced(s0: State, current: String, to: String, date: String, hhmm: String, why: Why): State {
        val s = on(s0, date)
        val cur = norm(current) ?: return s
        val t = norm(to) ?: return s
        if (cur == t) return s
        return s.copy(forced = s.forced + 1, refused = cur, last = Switch(date, hhmm, cur, t, why))
    }

    fun name(lang: String?): String = when (norm(lang)) { US -> "English (US)"; IN -> "English (India)"; null -> "English"; else -> norm(lang)!! }

    private fun because(w: Why): String = when (w) {
        Why.CLEAR_NO_WORDS -> "no words were found in clear speech $CLEAR_IN_ROW turns in a row"
        Why.REFUSED -> "the phone's speech service refused it"
        Why.MISSING -> "it isn't on this phone"
    }

    /** The activity line for a switch just made. */
    fun line(sw: Switch): String = when (sw.why) {
        Why.CLEAR_NO_WORDS -> "Listening switched to ${name(sw.to)}: ${because(sw.why)} in ${name(sw.from)}. " +
            "I switch by myself at most once a day, and never away from an English that has given words."
        Why.REFUSED -> "Listening switched to ${name(sw.to)}: the phone's speech service refused ${name(sw.from)}."
        Why.MISSING -> "Listening switched to ${name(sw.to)}: ${name(sw.from)} isn't on this phone for on-device listening."
    }

    /** The activity line when the clear-speech rule would have switched but the phone lacks the other English. */
    fun heldLine(current: String): String = "Kept listening in ${name(current)}: ${name(other(current))} isn't on this phone " +
        "for on-device listening, so switching to it would not change what I hear."

    /**
     * Said plainly when the phone's on-device recognizer lacks the other English (Google's speech service off), else
     * null: switching to it would change nothing, so the working one is kept. Never a suggestion to switch Google on.
     */
    fun missing(current: String?, installed: List<String>?, google: Boolean): String? {
        val cur = norm(current) ?: return null
        if (cur != US && cur != IN) return null
        val o = other(cur)
        if (canTry(o, installed, google)) return null
        return "${name(o)} isn't on this phone for on-device listening, so I stay in ${name(cur)}; to have it, add " +
            "${name(o)} under Settings, System, Languages, On-device speech recognition."
    }

    /** Said after the hearing answer: which English, why, and today's switches (codes and counts only). */
    fun spoken(s0: State, current: String?, date: String, installed: List<String>? = null, google: Boolean = false): String {
        val s = on(s0, date)
        val cur = norm(current)
        val w = works(s, date)
        val parts = ArrayList<String>()
        parts += "I'm listening in ${name(cur)}" + when {
            cur != null && w == cur -> ", the English that last gave words."
            w != null -> "; ${name(w)} is the one that last gave words."
            else -> "; no English has given words lately, so I'm still finding the right one."
        }
        s.last?.takeIf { it.date == date }?.let { sw ->
            parts += "At ${sw.hhmm} I switched from ${name(sw.from)} to ${name(sw.to)}, because ${because(sw.why)}."
        }
        if (s.soft >= SOFT_A_DAY) parts += "I won't switch by myself again today."
        if (s.held > 0) parts += "I held off switching ${s.held} ${if (s.held == 1) "time" else "times"} today to keep a steady English."
        missing(cur, installed, google)?.let { parts += it }
        return parts.joinToString(" ")
    }

    /** For the diagnostics report: codes and counts only. */
    fun say(s0: State, current: String?, date: String, installed: List<String>? = null): String {
        val s = on(s0, date)
        return "Language: now ${norm(current) ?: "-"} · on-device has ${known(installed)?.joinToString(",") ?: "-"} · works ${works(s, date) ?: "-"} (${s.workedOn ?: "-"}) · today switches ${s.soft} by clear speech, " +
            "${s.forced} forced, ${s.held} held · refused ${s.refused ?: "-"}" +
            (s.last?.let { " · last ${it.date} ${it.hhmm} ${it.from}>${it.to} ${it.why}" } ?: "")
    }

    // --- Kept on the phone: one line of codes and counts. A damaged line reads as nothing known. ---

    private fun o(v: Any?) = v?.toString() ?: "-"
    private fun i(v: String) = v.takeIf { it != "-" }

    fun save(s: State): String = listOf("1", o(s.worked), o(s.workedOn), o(s.day), s.soft, s.forced, s.held, o(s.refused),
        o(s.last?.date), o(s.last?.hhmm), o(s.last?.from), o(s.last?.to), o(s.last?.why)).joinToString("|")

    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val HHMM = Regex("^\\d{2}:\\d{2}$")
    private val LANG = Regex("^[a-z]{2}(-[A-Z]{2})?$")

    fun load(raw: String?): State {
        val f = raw.orEmpty().split('|')
        if (f.size != 13 || f[0] != "1") return State()
        fun lang(x: String) = i(x)?.takeIf { LANG.matches(it) }
        fun date(x: String) = i(x)?.takeIf { DATE.matches(it) }
        val n = (4..6).map { f[it].toIntOrNull()?.takeIf { v -> v >= 0 } ?: return State() }
        val last = runCatching {
            Switch(date(f[8])!!, i(f[9])!!.takeIf { HHMM.matches(it) }!!, lang(f[10])!!, lang(f[11])!!, Why.valueOf(f[12]))
        }.getOrNull()
        val worked = lang(f[1]); val on = date(f[2])
        return State(if (on == null) null else worked, if (worked == null) null else on, date(f[3]), n[0], n[1], n[2], lang(f[7]), last)
    }
}
