package com.optionslab.ira

import java.time.LocalDateTime
import java.util.Locale

/**
 * Jarvis's confidence words matched to his own numbers (learning round 12, 2026-10-05). When one of his answers - his
 * own words or the on-device model's rewording - says how often something happens ("usually", "often", "rarely",
 * "almost always", "most of the time"...) in the same clause as the record it rests on ("4 of the last 12", "3 out of
 * 10", "33% of the times"), the word is checked against that number ([check]): a word the number does not bear out is
 * said as the word that fits it ("it usually went its way: 4 of the last 12" -> "it often went its way..."; 4 of 12 is
 * 33%, so "sometimes"). Only the word changes: the figures, the sentence count and everything else stay as written, so
 * the chat and the voice say the same thing and "go on" still finds the same sentences.
 *
 * Kept: each word checked beside a number, what it became and the number ([Event]; Jarvis's own words only, never
 * Boss's), the last [WINDOW_DAYS] days - so "how well do your words match your numbers?" and "what do you mean by
 * usually?" answer from his record, and the Learnings ledger shows the words he keeps getting wrong. Boss may have
 * them left as written ("say your confidence words as written", [Request.OFF]) and back ("match your words to the
 * numbers again", [Request.ON]); the record is kept either way.
 *
 * Words only: nothing here acts. Ambiguous clauses (two numbers, two words, a negation, "how often", "less often",
 * "usually 12%" where "usually" means "on usual days") are left alone; a record under [MIN_N] decides nothing. Pure.
 */
object WordFit {
    /** Only the last this many days are kept and counted. */
    const val WINDOW_DAYS = 60L
    /** A record of fewer than this many is too few to check a word against. */
    const val MIN_N = 3
    /** Events kept at most. */
    const val KEEP = 300
    /** The same word and number again within this many minutes is the same answer (the model's rewording of it). */
    const val SAME_MIN = 3L
    /** Words named at most. */
    const val SHOW = 3

    /**
     * Each confidence word: what Boss would take it to mean, as the share of times [lo]..[hi] (both inclusive), and
     * [canon]: the word said for a number in its own range ([forRate] picks these, each inside its own range).
     */
    enum class Word(val forms: List<String>, val lo: Double, val hi: Double, val canon: String?) {
        ALWAYS(listOf("always"), 1.0, 1.0, "always"),
        ALMOST_ALWAYS(listOf("almost always", "nearly always", "almost every time", "nearly every time"), 0.8, 1.0, "almost always"),
        USUALLY(listOf("usually", "most of the time", "more often than not", "typically"), 0.55, 1.0, "usually"),
        OFTEN(listOf("often", "frequently", "a lot of the time"), 0.35, 0.85, "often"),
        SOMETIMES(listOf("sometimes", "occasionally", "at times", "now and then", "now and again", "every so often"), 0.1, 0.6, "sometimes"),
        RARELY(listOf("rarely", "seldom", "hardly ever", "almost never", "scarcely ever", "not often"), 0.0, 0.2, "rarely"),
        NEVER(listOf("never", "not once"), 0.0, 0.0, "never");

        fun fits(rate: Double): Boolean = rate >= lo - 1e-9 && rate <= hi + 1e-9

        /** How Jarvis means it, in words ("55% of the time or more"). */
        fun meaning(): String = when (this) {
            ALWAYS -> "every single time"
            NEVER -> "not once"
            RARELY -> "${pct(hi)} of the time or less"
            ALMOST_ALWAYS, USUALLY -> "${pct(lo)} of the time or more"
            else -> "between ${pct(lo)} and ${pct(hi)} of the time"
        }
    }

    /** The word that fits [rate] (a share of times, 0..1). */
    fun forRate(rate: Double): Word = when {
        rate >= 1.0 - 1e-9 -> Word.ALWAYS
        rate >= 0.85 -> Word.ALMOST_ALWAYS
        rate >= 0.6 -> Word.USUALLY
        rate >= 0.4 -> Word.OFTEN
        rate >= 0.12 -> Word.SOMETIMES
        rate > 1e-9 -> Word.RARELY
        else -> Word.NEVER
    }

    /** Every form, longest first (so "almost always" is read before "always", "not often" before "often"). */
    private val FORMS: List<Pair<String, Word>> = Word.entries.flatMap { w -> w.forms.map { it to w } }.sortedByDescending { it.first.length }

    private val FORM_RX = Regex("(?i)(?<![a-z'])(" + FORMS.joinToString("|") { f -> f.first.split(" ").joinToString("\\s+") { Regex.escape(it) } } + ")(?![a-z'])")

    /** "4 of the last 12", "3 out of 10", "7 of my last 9" - the record a word rests on. */
    private val RATIO_RX = Regex("(?i)(?<![\\d.,])(\\d{1,3}(?:,\\d{3})*) (?:times |days |sessions )?(?:of|out of) (?:the |my |its |his |your |our )?(?:last |past |first |latest )?(\\d{1,3}(?:,\\d{3})*)(?!\\d|[.,]\\d)")
    /** "33% of the times", "40% of the last 12 days". */
    private val PCT_RX = Regex("(?i)(?<![\\d.])(\\d{1,3}(?:\\.\\d+)?) ?(?:%|per ?cent|percent) of (?:the |my |its |his |your )?(?:last |past )?(?:(\\d{1,4}) )?" +
        "(?:times|cases|calls|days|sessions|stretches|trades|headlines|alerts|answers|tries|occasions|weeks|months)\\b")
    /** [s] cut into clauses (a sentence's end, a semicolon or a long dash; never a decimal point), joined back exactly. */
    private fun clauses(s: String): List<String> {
        val out = ArrayList<String>()
        var from = 0
        for (i in s.indices) {
            val ch = s[i]
            val end = ch == '!' || ch == '?' || ch == ';' || ch == '—' || (ch == '.' && (i + 1 == s.length || s[i + 1].isWhitespace()))
            if (end) { out += s.substring(from, i + 1); from = i + 1 }
        }
        if (from < s.length) out += s.substring(from)
        return out
    }

    /** One word checked beside a number: as said ([said], the form used), the record ([k] of [n]) and the fitting word, if it changed. */
    data class Fit(val said: String, val word: Word, val k: Int, val n: Int, val to: Word?) {
        val rate: Double get() = k.toDouble() / n
        val fits: Boolean get() = to == null
    }

    /** [text] with each misfit word set right (when [fix]), and every word checked. */
    data class Result(val text: String, val fits: List<Fit>)

    /** [text]'s confidence words checked against the numbers beside them; the misfits changed when [fix]. Only words change. */
    fun check(text: String, fix: Boolean = true): Result {
        if (text.isEmpty() || !FORM_RX.containsMatchIn(text)) return Result(text, emptyList())
        val fits = ArrayList<Fit>()
        val out = StringBuilder()
        // Split on " - " too (a dash between two facts), keeping the text exactly as it was.
        for (part in splitDash(text)) {
            if (part.second) { out.append(part.first); continue }
            for (c in clauses(part.first)) {
                val r = one(c)
                if (r == null) { out.append(c); continue }
                fits += r.first
                out.append(if (fix && r.first.to != null) r.second else c)
            }
        }
        return Result(if (fix) out.toString() else text, fits)
    }

    private fun splitDash(text: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        var i = 0
        Regex(" - | -- ").findAll(text).forEach { m -> out += text.substring(i, m.range.first) to false; out += m.value to true; i = m.range.last + 1 }
        out += text.substring(i) to false
        return out
    }

    private val SAFETY_RX = Regex("(?i)\\b(?:order|orders|live|real|fingerprint|pin|lock|locked|guard|guards|kill switch|stop loss|stoploss|limit)\\b")

    private fun num(s: String): Int? = s.replace(",", "").toIntOrNull()

    /** One clause: its single word and single number checked, and the clause with the word set right; null: nothing to check. */
    private fun one(c: String): Pair<Fit, String>? {
        val words = FORM_RX.findAll(c).toList()
        if (words.size != 1) return null
        val w = words[0]
        val form = w.value.lowercase(Locale.ENGLISH).replace(Regex("\\s+"), " ")
        val word = FORMS.firstOrNull { it.first == form }?.second ?: return null
        val before = c.substring(0, w.range.first).lowercase(Locale.ENGLISH)
        val after = c.substring(w.range.last + 1).lowercase(Locale.ENGLISH)
        // Not a claim about how often: "not usually", "how often", "less often", "more often" (but "more often than not" is one).
        if (Regex("(?:\\bnot|n't|\\bnever|\\bhow|\\bless|\\bmore|\\bmost|\\bso|\\bas|\\btoo|\\bvery|\\bquite|\\bfairly)\\s*$").containsMatchIn(before)) return null
        // "usually 12%", "(usually 2 a day)": what is usual, not a share of times.
        if (Regex("^\\s*(?:about |around |near |nearly |roughly |at |under |over |below |above )?(?:rs\\.? ?)?\\d").containsMatchIn(after)) return null
        if (Regex("^\\s*as\\b").containsMatchIn(after)) return null
        val ratios = RATIO_RX.findAll(c).toList()
        val pcts = PCT_RX.findAll(c).toList()
        if (ratios.size + pcts.size != 1) return null
        // The record must sit beside the word: nothing between them that starts another fact (a comma, "and", "but"), so
        // "I never trade live, and 3 of my last 10 calls were right" is never made "sometimes" (review, 5 Oct).
        val at = (ratios.firstOrNull() ?: pcts.first()).range
        val between = if (at.first > w.range.last) c.substring(w.range.last + 1, at.first) else c.substring(at.last + 1, w.range.first)
        if (Regex(";|\\b(?:and|but|while|whereas)\\b", RegexOption.IGNORE_CASE).containsMatchIn(between)) return null
        // A rule or a safety line is never re-worded: orders, live, the fingerprint, the PIN, the lock, the guards.
        if (SAFETY_RX.containsMatchIn(c)) return null
        val (k, n) = if (ratios.size == 1) {
            val k = num(ratios[0].groupValues[1]) ?: return null
            val n = num(ratios[0].groupValues[2]) ?: return null
            k to n
        } else {
            val p = pcts[0].groupValues[1].toDoubleOrNull() ?: return null
            val n = pcts[0].groupValues[2].toIntOrNull() ?: 100
            if (p > 100) return null
            Math.round(p / 100.0 * n).toInt() to n
        }
        if (n < MIN_N || k < 0 || k > n) return null
        val rate = k.toDouble() / n
        if (word.fits(rate)) return Fit(form, word, k, n, null) to c
        val to = forRate(rate)
        // "Always" and "never" are absolutes - often a rule, not a tally: never written in or out, only noted as fitting.
        if (word == Word.ALWAYS || word == Word.NEVER || to == Word.ALWAYS || to == Word.NEVER) return null
        val canon = to.canon ?: return null
        val said = if (w.value.first().isUpperCase()) canon.replaceFirstChar { it.uppercase() } else canon
        return Fit(form, word, k, n, to) to (c.substring(0, w.range.first) + said + c.substring(w.range.last + 1))
    }

    // ---- the record ------------------------------------------------------------------------------------------------

    /** One word checked: the form said, the word it became (null: it fitted), the number and when. Never Boss's words. */
    data class Event(val said: String, val to: String?, val k: Int, val n: Int, val at: LocalDateTime, val fixed: Boolean = true)

    /** The words checked, and whether Boss asked them left as written ([off]; since [offAt]). */
    data class Log(val events: List<Event> = emptyList(), val off: Boolean = false, val offAt: LocalDateTime? = null)

    /** [log] with [fits] noted at [at] (the same word and number within [SAME_MIN] minutes counted once); old ones dropped. */
    fun noted(log: Log, fits: List<Fit>, at: LocalDateTime): Log {
        if (fits.isEmpty()) return log
        val from = at.minusDays(WINDOW_DAYS)
        val kept = log.events.filter { it.at.isAfter(from) && !it.at.isAfter(at) }.toMutableList()
        for (f in fits) {
            val dup = kept.any { e -> e.said == f.said && e.k == f.k && e.n == f.n && !e.at.isBefore(at.minusMinutes(SAME_MIN)) }
            if (!dup) kept += Event(f.said, f.to?.canon, f.k, f.n, at, fixed = !log.off)
        }
        return log.copy(events = kept.takeLast(KEEP))
    }

    /** Boss's words: [off] = leave them as written; on again otherwise. The record stays. */
    fun switched(log: Log, off: Boolean, now: LocalDateTime): Log = log.copy(off = off, offAt = if (off) now else null)

    /** One confidence word's record: how often it was checked, how often the number bore it out, and the numbers beside it. */
    data class Record(val said: String, val checked: Int, val misfit: Int, val lowRate: Double, val highRate: Double, val became: List<String>, val newest: LocalDateTime) {
        val fitted: Int get() = checked - misfit
    }

    /** Every word checked in the window, the ones most often wrong first. */
    fun records(log: Log, now: LocalDateTime): List<Record> {
        val from = now.minusDays(WINDOW_DAYS)
        return log.events.filter { it.at.isAfter(from) && !it.at.isAfter(now) }.groupBy { it.said }.map { (s, es) ->
            val rates = es.map { it.k.toDouble() / it.n }
            Record(s, es.size, es.count { it.to != null }, rates.min(), rates.max(),
                es.mapNotNull { it.to }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }, es.maxOf { it.at })
        }.sortedWith(compareByDescending<Record> { it.misfit }.thenByDescending { it.checked })
    }

    /** The words the numbers did not bear out, at least once in the window (for the Learnings ledger). */
    fun misfits(log: Log, now: LocalDateTime): List<Record> = records(log, now).filter { it.misfit > 0 }

    private fun pct(r: Double): String = "${Math.round(r * 100)}%"
    private fun span(r: Record): String = if (Math.round(r.lowRate * 100) == Math.round(r.highRate * 100)) pct(r.lowRate) else "${pct(r.lowRate)} to ${pct(r.highRate)}"
    private fun times(n: Int) = if (n == 1) "once" else if (n == 2) "twice" else "$n times"

    /** The ledger's line for [r]: what the word became and why. */
    fun ledgerWhat(r: Record, off: Boolean): String =
        "\"${r.said}\" ${if (off) "did not fit my number" else "said as \"${r.became.firstOrNull() ?: r.said}\""} ${times(r.misfit)}"

    fun ledgerWhy(r: Record, off: Boolean): String =
        "the number beside it was ${span(r)} in the last $WINDOW_DAYS days, which it doesn't mean" +
            (if (off) "; left as written on your word" else "; only the word changes, the figures stay")

    // ---- asked -----------------------------------------------------------------------------------------------------

    enum class Request { HOW, OFF, ON }

    private fun norm(text: String) = " " + text.lowercase(Locale.ENGLISH).replace("'", "").replace("’", "")
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| again)* $"
    private const val WORDS = "(confidence words|frequency words|words like usually|words like often|usuallys|hedges|hedging words|probability words)"
    private val FORM_ALT = FORMS.joinToString("|") { it.first }

    private val HOW = Regex(LEAD + "(how well|how closely|do|does) (do |does )?(your|jarviss) (words|$WORDS) (match|fit|agree with) (your|the|my) (numbers|records?|figures|stats)" + TAIL + "|" +
        LEAD + "(are|is) (your )?$WORDS (calibrated|honest|accurate|right)" + TAIL + "|" +
        LEAD + "how (well )?calibrated (are you|are your words|is your language|are your $WORDS)" + TAIL + "|" +
        LEAD + "(which|what) (of your )?$WORDS (have you|did you) (corrected|changed|fixed)" + TAIL + "|" +
        LEAD + "(which|what) words (have you|did you) (correct|corrected|change|changed|fix|fixed) to (match|fit) (your |the )?(numbers|records?)" + TAIL + "|" +
        LEAD + "(when you say|what do you mean (by|when you say)|how often is|how often do you mean by|what does) ($FORM_ALT)( mean)?( what do you mean| how often is that| exactly| to you)?" + TAIL + "|" +
        LEAD + "(tum|aap) ($FORM_ALT|aksar|kabhi kabhi) (ka|se) (kya )?matlab (lete|samajhte) (ho|hain)" + TAIL)
    private val OFF = Regex(LEAD + "(say|leave|keep|use) (your )?$WORDS (as written|as they are|as you wrote them|unchanged)" + TAIL + "|" +
        LEAD + "(dont|do not|stop|no need to) (correct|change|fix|adjust|calibrate|match)(ing)? (your )?($WORDS|words)( to (the |your )?(numbers|records?))?( any ?more)?" + TAIL)
    private val ON = Regex(LEAD + "(match|fit|correct|calibrate) (your )?($WORDS|words) (to|with) (the |your )?(numbers|records?)" + TAIL + "|" +
        LEAD + "(start )?(correcting|matching|calibrating) (your )?($WORDS|words)( to (the |your )?(numbers|records?))?" + TAIL + "|" +
        LEAD + "(correct|calibrate) (your )?$WORDS" + TAIL)

    /** "How well do your words match your numbers?", "what do you mean by usually?", "say your confidence words as written", "match your words to the numbers again"; else null. */
    fun asked(text: String): Request? {
        val t = norm(text)
        return when {
            OFF.containsMatchIn(t) -> Request.OFF
            ON.containsMatchIn(t) -> Request.ON
            HOW.containsMatchIn(t) -> Request.HOW
            else -> null
        }
    }

    /** The word Boss asked the meaning of ("what do you mean by usually?" -> [Word.USUALLY]), else null. */
    fun wordAsked(text: String): Word? {
        val t = norm(text)
        if (Regex("\\baksar\\b").containsMatchIn(t)) return Word.OFTEN
        if (Regex("\\bkabhi kabhi\\b").containsMatchIn(t)) return Word.SOMETIMES
        // The last one named: "how often is usually?" asks of "usually".
        val m = FORM_RX.findAll(t.trim()).lastOrNull() ?: return null
        return FORMS.firstOrNull { it.first == m.value }?.second
    }

    const val UNDO = "say your confidence words as written"
    const val REDO = "match your words to the numbers again"
    const val ONLY_WORDS = "Only the word changes, never a figure - and nothing I learn acts."

    /** "How well do your words match your numbers?" / "what do you mean by usually?". */
    fun say(text: String, log: Log, now: LocalDateTime): String {
        val rs = records(log, now)
        val asked = wordAsked(text)
        val state = if (log.off) " You asked me to leave them as written, so I do; say \"$REDO\" to have them set right." else ""
        if (asked != null) {
            val mine = rs.filter { r -> FORMS.firstOrNull { it.first == r.said }?.second == asked }
            val rec = if (mine.isEmpty()) " I haven't said it beside a number lately."
            else " Beside a number in the last $WINDOW_DAYS days: ${mine.sumOf { it.checked }} time${if (mine.sumOf { it.checked } == 1) "" else "s"}, " +
                "${mine.sumOf { it.fitted }} fitting" + (mine.sumOf { it.misfit }.takeIf { it > 0 }?.let { " and $it set right" } ?: "") + "."
            return "When I say \"${asked.forms.first()}\", Boss, I mean ${asked.meaning()}." + rec +
                " A record under $MIN_N decides nothing.$state"
        }
        if (rs.isEmpty()) return "I haven't said a word like \"usually\" or \"rarely\" beside a number in the last $WINDOW_DAYS days, Boss. When I do, " +
            "I check the word against the number and say the one that fits - \"usually\" is ${Word.USUALLY.meaning()}, \"rarely\" ${Word.RARELY.meaning()}.$state $ONLY_WORDS"
        val checked = rs.sumOf { it.checked }
        val misfit = rs.sumOf { it.misfit }
        val wrong = rs.filter { it.misfit > 0 }
        return "In the last $WINDOW_DAYS days, Boss, I said a word like \"usually\" beside a number $checked time${if (checked == 1) "" else "s"}; " +
            (if (misfit == 0) "every one fitted its number." else "$misfit didn't fit - " + wrong.take(SHOW).joinToString("; ") { r ->
                "\"${r.said}\" ${times(r.misfit)} (the number was ${span(r)}" + (r.became.firstOrNull()?.let { if (log.off) "" else ", said as \"$it\"" } ?: "") + ")"
            } + (if (wrong.size > SHOW) "; and ${wrong.size - SHOW} more" else "") + ".") +
            state + " " + ONLY_WORDS + (if (log.off || misfit == 0) "" else " Say \"$UNDO\" to have them left alone.")
    }

    /** "Say your confidence words as written" / "match your words to the numbers again". */
    fun saySwitched(off: Boolean, was: Boolean): String = when {
        off && was -> "I'm already leaving my confidence words as written, Boss. Say \"$REDO\" to have them set right again."
        off -> "Done, Boss: I'll leave words like \"usually\" as written, even where the number beside them says otherwise. I keep noting it, so you can ask how well they match."
        !was -> "I'm already matching my confidence words to the numbers beside them, Boss."
        else -> "Done, Boss: a word like \"usually\" is set to fit the number beside it again. $ONLY_WORDS"
    }
}
