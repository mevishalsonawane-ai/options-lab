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
 *
 * Round 28 (Boss's phone listens in English (US) because English (India) is not installed): [arrival] asks him once,
 * aloud and in the chat, to add English (India) (again only a week on if still missing), and when the phone's list
 * shows it, moves listening to it by itself - today's one switch, never in a run whose English has given words.
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

    enum class Why { CLEAR_NO_WORDS, REFUSED, MISSING, INSTALLED }

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

    /**
     * The English Boss added to the phone and listening moved to ([arrival], round 28), kept until something newer:
     * another switch replaces [State.last], or the other English gave words on a later day than the move.
     */
    fun added(s: State): String? {
        val sw = s.last?.takeIf { it.why == Why.INSTALLED } ?: return null
        val to = norm(sw.to) ?: return null
        val on = s.workedOn
        return if (on == null || norm(s.worked) == to || on <= sw.date) to else null
    }

    /** The English to keep: the one Boss added, else the one that works, else today's switch. */
    private fun steady(s: State, date: String): String? = added(s) ?: works(s, date) ?: today(s, date)

    /** The English to start listening in: the one Boss added, else the one that works, else today's switch, else [fallback]. */
    fun start(s: State, date: String, fallback: String = US): String = steady(s, date) ?: norm(fallback) ?: US

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
        steady(s, date)?.let { w -> if (google || w in have) return w }
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
        Why.INSTALLED -> "English (India) was added to this phone"
    }

    /** The activity line for a switch just made. */
    fun line(sw: Switch): String = when (sw.why) {
        Why.CLEAR_NO_WORDS -> "Listening switched to ${name(sw.to)}: ${because(sw.why)} in ${name(sw.from)}. " +
            "I switch by myself at most once a day, and never away from an English that has given words."
        Why.REFUSED -> "Listening switched to ${name(sw.to)}: the phone's speech service refused ${name(sw.from)}."
        Why.MISSING -> "Listening switched to ${name(sw.to)}: ${name(sw.from)} isn't on this phone for on-device listening."
        Why.INSTALLED -> "Listening switched to ${name(sw.to)}: it was added to this phone for on-device listening."
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

    // --- Round 28: English (India) asked for once, and taken by itself when it arrives. ---

    /** Said (and put in the chat) when the phone lacks English (India) for on-device listening. */
    const val ASK_LINE = "Boss, I'd hear you better in Indian English: add English (India) under Settings, System, Languages, On-device speech recognition."
    /** Said (and put in the chat) when listening moves to English (India) because it was added. */
    const val SWITCHED_LINE = "Switched to Indian English, Boss."
    /** The ask is said again only this many days after the last, and only while still missing. */
    const val ASK_AGAIN_DAYS = 7L
    /** The ask is said at most this many times ever (the first, and once more a week on if still missing). */
    const val ASKS = 2
    /** While English (India) is awaited, the phone's list is read again at most this often (and at each listening start). */
    const val CHECK_MS = 3_600_000L

    /**
     * The ask's memory (codes and dates only): when it was last said and how often, whether English (India) has been
     * seen missing and is awaited ([waiting]), and the day listening moved to it by itself.
     */
    data class Ask(val askedOn: String? = null, val asks: Int = 0, val waiting: Boolean = false, val switchedOn: String? = null)

    /** What to do with the phone's list now: the new ask memory and language state, the English to move to, and words to say. */
    data class Arrival(val ask: Ask, val state: State, val to: String?, val say: String?)

    /** Whether the phone's list should be read again now ([lastMs] 0: never read). Only while English (India) is awaited. */
    fun checkDue(a: Ask, lastMs: Long, nowMs: Long, google: Boolean): Boolean =
        !google && a.waiting && (lastMs <= 0L || nowMs < lastMs || nowMs - lastMs >= CHECK_MS)

    /**
     * The phone's on-device languages read ([installed]; null or empty when it cannot say), listening in [current].
     *  - English (India) missing (Google's speech service off): it is awaited, and [ASK_LINE] is due once - again only
     *    [ASK_AGAIN_DAYS] days later if still missing, [ASKS] times in all.
     *  - English (India) there after it was awaited: listening moves to it, [SWITCHED_LINE] - but never in a listening
     *    run whose English has given words ([wordsNow]; a later check or the next listening start does it), never into
     *    an English the phone refused today, and only when today's one switch by itself is unused (this is that switch).
     * An English that has given words on earlier days does not hold it: adding English (India) is Boss's own answer to
     * the ask. Idempotent: the same call on its own result changes nothing and says nothing. Only names a language code.
     */
    fun arrival(a: Ask, s: State, current: String?, installed: List<String>?, google: Boolean, date: String, hhmm: String,
                wordsNow: Boolean): Arrival {
        val none = Arrival(a, s, null, null)
        if (google || !DATE.matches(date)) return none
        val have = known(installed) ?: return none
        val cur = norm(current) ?: US
        if (IN !in have) {
            val days = a.askedOn?.let { daysBetween(it, date) }
            val due = a.askedOn == null || (a.asks < ASKS && days != null && days >= ASK_AGAIN_DAYS)
            if (!due) return if (a.waiting) none else none.copy(ask = a.copy(waiting = true))
            return Arrival(a.copy(askedOn = date, asks = a.asks + 1, waiting = true), s, null, ASK_LINE)
        }
        if (!a.waiting) return none
        if (cur == IN) return none.copy(ask = a.copy(waiting = false))
        val t = on(s, date)
        if (wordsNow || t.refused == IN || t.soft >= SOFT_A_DAY || a.switchedOn == date) return none
        return Arrival(a.copy(waiting = false, switchedOn = date),
            t.copy(soft = t.soft + 1, last = Switch(date, hhmm, cur, IN, Why.INSTALLED)), IN, SWITCHED_LINE)
    }

    fun saveAsk(a: Ask): String = listOf("1", o(a.askedOn), a.asks, if (a.waiting) 1 else 0, o(a.switchedOn)).joinToString("|")

    fun loadAsk(raw: String?): Ask {
        val f = raw.orEmpty().split('|')
        if (f.size != 5 || f[0] != "1") return Ask()
        val n = f[2].toIntOrNull()?.takeIf { it in 0..ASKS } ?: return Ask()
        val w = when (f[3]) { "1" -> true; "0" -> false; else -> return Ask() }
        val asked = i(f[1])?.takeIf { DATE.matches(it) }
        return Ask(asked, if (asked == null) 0 else n, w, i(f[4])?.takeIf { DATE.matches(it) })
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
