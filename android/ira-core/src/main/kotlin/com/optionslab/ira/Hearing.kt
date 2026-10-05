package com.optionslab.ira

/**
 * How well Jarvis is hearing Boss, from his own ears' counts (Boss, 5 Oct: "are you having trouble hearing me?"): turns
 * where words were found or not, the speech service failing and being restarted, how soon the first words are read,
 * turns lost, the name caught or saved only from partial words, failures by recognizer code and by hour - per day, for
 * today against the days before. Numbers only: never a word heard. When something is clearly worse than usual (no words
 * found twice as often, say) he says what would help, in things Boss controls - move closer, a headset, the phone's
 * speech language, Google's speech service in Settings - and only ever SUGGESTS: nothing here switches anything (the
 * Google speech choice is never changed by voice). Pure: counts in, words out.
 */
object Hearing {
    /** Days kept (today and the week before). */
    const val KEEP = 8
    /** A day counts towards "usual" only with this many turns where Boss spoke. */
    const val LEAST_DAY = 5
    /** Today needs this many turns where Boss spoke before a problem is named. */
    const val LEAST_TODAY = 10
    /** How the unasked suggestion begins (a note beside answers, never taken for one). */
    const val NUDGE = "Boss, I'm having trouble hearing you today"

    /** One day of the ears, counts only. */
    data class Day(
        val date: String,
        /** Turns with words found. */
        val heard: Int = 0,
        /** Turns where speech began and no words were found ... */
        val noMatch: Int = 0,
        /** ... of which the sound was clear (6 dB and up on the recognizer's scale). */
        val noMatchLoud: Int = 0,
        /** Turns with no speech at all (normal between questions). */
        val silent: Int = 0,
        /** Other failures of the speech service (busy, refused, the microphone, the service restarting). */
        val errors: Int = 0,
        /** The recognizer made anew. */
        val restarts: Int = 0,
        /** Turns that never ended and were reset with nothing kept. */
        val lost: Int = 0,
        /** The name in the recognizer's final words. */
        val nameHeard: Int = 0,
        /** The name saved only from the partial words (the final reading had lost it). */
        val nameSaved: Int = 0,
        /** From "speech began" to the first words read (ms): sum and count. */
        val firstMs: Long = 0,
        val firstN: Int = 0,
        /** Failures by recognizer code. */
        val codes: Map<Int, Int> = emptyMap(),
        /** Failures (no words in speech, errors, lost turns) by hour of the day (India). */
        val hours: Map<Int, Int> = emptyMap(),
    ) {
        /** Turns where Boss spoke. */
        val spoke: Int get() = heard + noMatch
        val noMatchRate: Double get() = if (spoke == 0) 0.0 else noMatch.toDouble() / spoke
        val failures: Int get() = errors + restarts
        val firstAvg: Long? get() = if (firstN == 0) null else firstMs / firstN
        val names: Int get() = nameHeard + nameSaved
        val savedRate: Double get() = if (names == 0) 0.0 else nameSaved.toDouble() / names
    }

    enum class Kind { HEARD, NO_MATCH, SILENT, ERROR, LOST }

    private fun today(days: List<Day>, date: String, f: (Day) -> Day): List<Day> {
        val d = days.firstOrNull { it.date == date } ?: Day(date)
        return (days.filter { it.date != date } + f(d)).sortedBy { it.date }.takeLast(KEEP)
    }

    private fun Map<Int, Int>.plus1(k: Int) = this + (k to ((this[k] ?: 0) + 1))

    /**
     * One recognizer turn ended as [kind] on [date] at [hour]: [code] the recognizer's error (ERROR), [loud] clear sound
     * (NO_MATCH), [name] the name in the words (HEARD), [saved] found only in the partial words.
     */
    fun turn(days: List<Day>, date: String, hour: Int, kind: Kind, code: Int? = null, loud: Boolean = false,
             name: Boolean = false, saved: Boolean = false): List<Day> = today(days, date) { d ->
        val h = hour.coerceIn(0, 23)
        when (kind) {
            Kind.HEARD -> d.copy(heard = d.heard + 1, nameHeard = d.nameHeard + if (name && !saved) 1 else 0, nameSaved = d.nameSaved + if (name && saved) 1 else 0)
            Kind.NO_MATCH -> d.copy(noMatch = d.noMatch + 1, noMatchLoud = d.noMatchLoud + if (loud) 1 else 0, codes = d.codes.plus1(7), hours = d.hours.plus1(h))
            Kind.SILENT -> d.copy(silent = d.silent + 1)
            Kind.ERROR -> d.copy(errors = d.errors + 1, codes = code?.let { d.codes.plus1(it) } ?: d.codes, hours = d.hours.plus1(h))
            Kind.LOST -> d.copy(lost = d.lost + 1, hours = d.hours.plus1(h))
        }
    }

    /** The recognizer was made anew on [date]. */
    fun restart(days: List<Day>, date: String): List<Day> = today(days, date) { it.copy(restarts = it.restarts + 1) }

    /** The first words were read [ms] after speech began (a stall over 15 s is not a reading time). */
    fun firstWords(days: List<Day>, date: String, ms: Long): List<Day> =
        if (ms <= 0 || ms > 15_000) days else today(days, date) { it.copy(firstMs = it.firstMs + ms, firstN = it.firstN + 1) }

    // --- Kept on the phone: numbers only, one day per ';'. A damaged day reads as none. ---

    private fun mapOut(m: Map<Int, Int>) = m.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
    private fun mapIn(s: String): Map<Int, Int> = s.split(',').mapNotNull { p ->
        val kv = p.split(':'); if (kv.size != 2) null else kv[0].trim().toIntOrNull()?.let { k -> kv[1].trim().toIntOrNull()?.takeIf { it >= 0 }?.let { k to it } }
    }.toMap()

    fun save(days: List<Day>): String = days.takeLast(KEEP).joinToString(";") { d ->
        listOf(d.date, d.heard, d.noMatch, d.noMatchLoud, d.silent, d.errors, d.restarts, d.lost, d.nameHeard, d.nameSaved,
            d.firstMs, d.firstN, mapOut(d.codes), mapOut(d.hours)).joinToString("|")
    }

    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    fun load(s: String?): List<Day> = s.orEmpty().split(';').mapNotNull { line ->
        val f = line.split('|')
        if (f.size != 14 || !DATE.matches(f[0])) return@mapNotNull null
        val n = (1..9).map { f[it].toIntOrNull()?.takeIf { v -> v >= 0 } ?: return@mapNotNull null }
        val fm = f[10].toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
        val fn = f[11].toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
        Day(f[0], n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7], n[8], fm, fn, mapIn(f[12]), mapIn(f[13]).filterKeys { it in 0..23 })
    }.sortedBy { it.date }.distinctBy { it.date }.takeLast(KEEP)

    // --- Today against the days before. ---

    /** The days before [date] with enough turns to say what is usual. */
    fun before(days: List<Day>, date: String): List<Day> = days.filter { it.date < date && it.spoke >= LEAST_DAY }

    data class Usual(val days: Int, val noMatchRate: Double, val failures: Double, val lost: Double, val firstAvg: Long?, val savedRate: Double?)

    fun usual(days: List<Day>, date: String): Usual? {
        val b = before(days, date).takeIf { it.isNotEmpty() } ?: return null
        val spoke = b.sumOf { it.spoke }
        val firstN = b.sumOf { it.firstN }
        val names = b.sumOf { it.names }
        return Usual(b.size, b.sumOf { it.noMatch }.toDouble() / spoke, b.sumOf { it.failures }.toDouble() / b.size,
            b.sumOf { it.lost }.toDouble() / b.size, if (firstN >= 5) b.sumOf { it.firstMs } / firstN else null,
            if (names >= 6) b.sumOf { it.nameSaved }.toDouble() / names else null)
    }

    enum class Problem { NO_WORDS_FAINT, NO_WORDS_CLEAR, SERVICE, SLOW_START, LOST, NAME }

    /** What is clearly worse today than usual (or, with no usual yet, clearly bad), worst first. */
    fun problems(days: List<Day>, date: String): List<Problem> {
        val t = days.firstOrNull { it.date == date } ?: return emptyList()
        val u = usual(days, date)
        val out = ArrayList<Problem>()
        if (t.spoke >= LEAST_TODAY) {
            val bad = if (u == null) t.noMatchRate >= 0.5 else t.noMatchRate >= 0.3 && t.noMatchRate >= 2 * maxOf(u.noMatchRate, 0.05)
            if (bad) out += if (t.noMatchLoud * 2 >= t.noMatch) Problem.NO_WORDS_CLEAR else Problem.NO_WORDS_FAINT
        }
        if (if (u == null) t.failures >= 8 else t.failures >= 6 && t.failures >= 2 * maxOf(u.failures, 2.0)) out += Problem.SERVICE
        t.firstAvg?.takeIf { t.firstN >= 5 }?.let { a ->
            if (if (u?.firstAvg == null) a >= 2_500 else a >= 1_500 && a >= 2 * u.firstAvg) out += Problem.SLOW_START
        }
        if (if (u == null) t.lost >= 4 else t.lost >= 3 && t.lost >= 2 * maxOf(u.lost, 1.0)) out += Problem.LOST
        if (t.names >= 6) {
            val s = u?.savedRate
            if (if (s == null) t.savedRate >= 0.5 else t.savedRate >= 0.3 && t.savedRate >= 2 * maxOf(s, 0.1)) out += Problem.NAME
        }
        return out
    }

    /** What would help with [p], in things Boss controls - a suggestion only; [google] whether Google's speech is on now. */
    fun fix(p: Problem, google: Boolean): String {
        val googleTip = if (google) "" else " Or switch on \"Use Google's speech service\" in Settings, Voice and AI model (your speech then goes to Google) - only you can switch it, Boss; I never do."
        return when (p) {
            Problem.NO_WORDS_FAINT -> "Your voice reaches me faint: hold the phone closer, or use a headset."
            Problem.NO_WORDS_CLEAR -> "Your voice is clear but the phone's speech pack isn't reading it: add English (India) under the phone's Settings, System, Languages, On-device speech recognition." + googleTip
            Problem.SERVICE -> "The phone's speech service keeps failing: update \"Speech Services by Google\" in the Play Store, and close other apps using the microphone." + googleTip
            Problem.SLOW_START -> "The phone's speech service is slow to start reading: close other apps using the microphone; restarting the phone often clears it."
            Problem.LOST -> "Turns are getting stuck: pause a beat after \"Jarvis\", or tap the mic button for a question."
            Problem.NAME -> "My name is reaching me unclear: say \"Jarvis\" clearly with a short pause after it, closer to the phone or through a headset."
        }
    }

    private fun what(p: Problem, t: Day, u: Usual?): String = when (p) {
        Problem.NO_WORDS_FAINT, Problem.NO_WORDS_CLEAR -> "no words found in ${t.noMatch} of ${t.spoke} turns where you spoke (${pct(t.noMatchRate)}" +
            (u?.let { ", usually ${pct(it.noMatchRate)}" } ?: "") + ")"
        Problem.SERVICE -> "the speech service failed or restarted ${t.failures} times" + (u?.let { " (usually ${one(it.failures)} a day)" } ?: "")
        Problem.SLOW_START -> "your first words took ${sec(t.firstAvg ?: 0)} to be read" + (u?.firstAvg?.let { " (usually ${sec(it)})" } ?: "")
        Problem.LOST -> "${t.lost} turns got stuck and were lost" + (u?.let { " (usually ${one(it.lost)} a day)" } ?: "")
        Problem.NAME -> "my name was caught only from your half-read words ${t.nameSaved} of ${t.names} times" + (u?.savedRate?.let { " (usually ${pct(it)})" } ?: "")
    }

    /** The unasked suggestion for [date], or null when nothing is clearly wrong (the caller says it once a day). */
    fun nudge(days: List<Day>, date: String, google: Boolean): String? {
        val p = problems(days, date).firstOrNull() ?: return null
        val t = days.first { it.date == date }
        return "$NUDGE: ${what(p, t, usual(days, date))}. ${fix(p, google)} Say \"Jarvis, how well are you hearing me?\" for the numbers."
    }

    /** "How well are you hearing me?", "are you having trouble hearing?", "how are your ears today?" */
    fun asked(text: String): Boolean =
        rx("(?i)^\\W*(jarvis,?\\s+)?(how well (are|can) you (hearing|hear) me( today)?|are you having (any )?(trouble|problems?|difficulty) (hearing|listening)( me| to me)?( today)?|" +
            "(do you have|having) (any )?(trouble|problems?) hearing( me)?( today)?|how (is|s|'s) your hearing( today)?|how are your ears( today)?|" +
            "(your )?hearing (report|stats|health)|how (good|clear) (am i|is my voice)( to you)?( today)?|mujhe theek se sun (pa )?rahe ho( kya)?|sunne mein (koi )?(dikkat|problem) (hai|ho rahi hai)( kya)?)\\W*$").containsMatchIn(text)

    private fun pct(r: Double) = "${Math.round(r * 100)}%"
    private fun one(x: Double) = if (x == Math.floor(x)) x.toLong().toString() else "%.1f".format(java.util.Locale.ENGLISH, x)
    private fun sec(ms: Long) = "%.1f seconds".format(java.util.Locale.ENGLISH, ms / 1000.0)

    private fun code(c: Int): String = when (c) {
        7 -> "no words found"; 6 -> "no speech"; 3 -> "the microphone"; 5 -> "the service refusing"; 8 -> "the service busy"
        11 -> "the service restarting"; 1, 2, 4 -> "the network"; 9 -> "the permission"; 10 -> "too many requests"
        12, 13 -> "the language"; else -> "code $c"
    }

    /** The spoken answer: today in plain numbers against the days before, and what would help when something is off. */
    fun spoken(days: List<Day>, date: String, google: Boolean): String {
        val t = days.firstOrNull { it.date == date }
        val u = usual(days, date)
        val usualLine = u?.let { "Usually (the last ${if (it.days == 1) "day" else "${it.days} days"}): no words found in ${pct(it.noMatchRate)} of your turns, " +
            "${one(it.failures)} speech-service failures a day." }
        if (t == null || t.spoke + t.errors + t.lost == 0)
            return listOfNotNull("I haven't heard a turn from you yet today, Boss.", usualLine).joinToString(" ")
        val parts = ArrayList<String>()
        parts += "Today, Boss: words found in ${t.heard} of ${t.spoke} turns where you spoke" +
            (if (t.spoke > 0) ", none in ${t.noMatch} (${pct(t.noMatchRate)}" + (u?.let { "; usually ${pct(it.noMatchRate)}" } ?: "") + ")." else ".")
        if (t.failures > 0 || u != null) parts += "The speech service failed ${t.errors} ${if (t.errors == 1) "time" else "times"} and I restarted it ${t.restarts}" +
            (u?.let { " (usually ${one(it.failures)} a day together)" } ?: "") + "."
        t.firstAvg?.let { a -> parts += "Your first words are read about ${sec(a)} after you start" + (u?.firstAvg?.let { " (usually ${sec(it)})" } ?: "") + "." }
        if (t.lost > 0) parts += "${t.lost} ${if (t.lost == 1) "turn" else "turns"} got stuck and lost."
        if (t.names > 0) parts += "My name: caught ${t.names} ${if (t.names == 1) "time" else "times"}" + (if (t.nameSaved > 0) ", ${t.nameSaved} only from your half-read words" else "") + "."
        t.codes.filterKeys { it != 7 }.maxByOrNull { it.value }?.let { (c, n) -> parts += "Most common failure: ${code(c)} ($n)." }
        t.hours.maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.let { (h, n) -> parts += "Most trouble around %02d:00 ($n).".format(java.util.Locale.ENGLISH, h) }
        val p = problems(days, date)
        parts += if (p.isEmpty()) (if (u == null) "No earlier days to compare yet." else "Nothing worse than usual.")
            else "What would help: " + p.take(2).joinToString(" ") { fix(it, google) }
        return parts.joinToString(" ")
    }

    /** For the diagnostics report: today's counts and the days kept (numbers only). */
    fun say(days: List<Day>, date: String): String {
        val t = days.firstOrNull { it.date == date }
        return "Hearing today: " + (t?.let { "heard ${it.heard}, no words ${it.noMatch} (clear ${it.noMatchLoud}), silent ${it.silent}, errors ${it.errors}, " +
            "restarts ${it.restarts}, lost ${it.lost}, name ${it.nameHeard}+${it.nameSaved} saved, first words ${it.firstAvg ?: "-"} ms, " +
            "codes ${mapOut(it.codes).ifEmpty { "-" }}, hours ${mapOut(it.hours).ifEmpty { "-" }}" } ?: "nothing yet") +
            " · days kept: ${days.size} · problems: ${problems(days, date).joinToString(",").ifEmpty { "none" }}"
    }
}
