package com.optionslab.ira

/**
 * Natural pauses for the phone's voice (Voice, round 12): the phone's speech engine breathes at a comma but runs
 * straight through a bracket, a spaced dash, a "·" or "|" separator, a line break and two figures side by side - so
 * "Rs 1,400 (70 percent) left - your call" came out in one breath and "24,500 24,600" as one long number. Aloud only,
 * punctuation only:
 *  - " (70 percent) " -> ", 70 percent, " (a short aside said as one; never one holding a sentence's end, never "position(s)");
 *  - a spaced dash before a word -> a comma ("reached - what next" -> "reached, what next"; " - 5" may be minus, left);
 *  - " · ", " | " and "•" separators, and a line break (with any bullet starting the line), -> a comma (or a space
 *    after a line already ending in a stop);
 *  - two figures side by side get a comma between them ("24,500 24,600" -> "24,500, 24,600");
 *  - a figure with its unit or decimals followed by the next item's name gets a breath before the name
 *    ("Nifty up 0.4 percent Bank Nifty down 0.2 percent" -> "Nifty up 0.4 percent, Bank Nifty down 0.2 percent"); a
 *    date ("7 October") or a quantity ("2 Bank Nifty lots") is left as written.
 *
 * Never a word added, dropped or changed, and never a full stop, question or exclamation mark added or removed, so
 * the sentences stay the same and "go on" after a cut-in finds the same ones ([BargeIn]). Run after [SayAs] (a comma
 * right after a figure would keep it from being read in lakh). Applying it twice changes nothing. Pure.
 */
object Pauses {
    private val MONTHS_DAYS = "January|February|March|April|May|June|July|August|September|October|November|December|" +
        "Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec|Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday"

    /** An aside in brackets after a space, holding no sentence's end and no other bracket; up to 80 characters. */
    private val ASIDE = Regex("\\s+\\(([^()\\n]{1,80}?)\\)(?=([\\s,.;:!?\\u0964]|$))")
    private val SENTENCE_END = Regex("[.!?\\u0964](?=\\s|$)")
    private val DASH = Regex("\\s+[-\\u2013\\u2014]\\s+(?=[^\\d\\s+-])")
    private val SEPARATOR = Regex("\\s+(?:[\\u00B7|\\u2022])\\s+")
    private val LINE = Regex("[ \\t]*(?:\\r?\\n)+[ \\t]*(?:[-\\u2022*]\\s+(?=\\D))?")
    private val LEAD_BULLET = Regex("^\\s*[-\\u2022*]\\s+(?=\\D)")
    /** A figure as written ("24,500", "1,23,456", "0.4", "9:15" is not one), not part of a word or another figure. */
    private const val FIG = "(?<![\\w.,:])(?:\\d{1,3}(?:,\\d{2,3})+|\\d+)(?:\\.\\d+)?(?![\\w:]|[.,]\\d)"
    private val SIDE_BY_SIDE = Regex("($FIG) (?=\\d)")
    private val FIG_NAME = Regex("((?<![\\w.,:])(?:(?:\\d{1,3}(?:,\\d{2,3})+|\\d+)\\.\\d+|(?:\\d{1,3}(?:,\\d{2,3})+|\\d+)(?:\\.\\d+)?" +
        "(?: (?:percent|points?|rupees|lakh|crore|प्रतिशत|रुपये|लाख|करोड़)){1,3})(?![\\w.]|,\\d)) (?=(?!(?:$MONTHS_DAYS)\\b)[A-Z][a-z])")

    /** [text] with commas where a person would pause; the words exactly as they were. */
    fun shape(text: String): String {
        if (text.isBlank()) return text
        // Each step runs only when its pattern could match (its bracket, dash, separator, line break, digit or comma is
        // there), so a plain sentence costs a scan, not a dozen patterns (speed round 9); what it gives is the same.
        val lines = if (has(text, BULLETS)) LEAD_BULLET.replace(text, "") else text
        var s = if (lines.indexOf('\n') < 0) lines else LINE.replace(lines) { m ->
            val before = lines.substring(0, m.range.first).trimEnd()
            if (before.isEmpty() || before.last() in ".!?:;,।") " " else ", "
        }
        if (s.indexOf('(') >= 0) s = ASIDE.replace(s) { m ->
            val inner = m.groupValues[1].trim()
            if (inner.isEmpty() || SENTENCE_END.containsMatchIn(inner)) return@replace m.value
            val after = m.groupValues[2]
            ", " + inner + (if (after.isEmpty() || after[0] in ",.;:!?।") "" else ",")
        }
        if (has(s, DASHES)) s = DASH.replace(s, ", ")
        if (has(s, SEPARATORS)) s = SEPARATOR.replace(s, ", ")
        if (hasDigit(s)) {
            s = SIDE_BY_SIDE.replace(s) { m -> m.groupValues[1] + ", " }
            s = FIG_NAME.replace(s) { m -> m.groupValues[1] + ", " }
        }
        return tidy(s)
    }

    private const val BULLETS = "-\u2022*"
    private const val DASHES = "-\u2013\u2014"
    private const val SEPARATORS = "\u00B7|\u2022"

    private fun has(s: String, any: String): Boolean {
        for (c in any) if (s.indexOf(c) >= 0) return true
        return false
    }

    private val DOUBLED = Regex(",(?:\\s*,)+")
    private val AFTER_STOP = Regex("([.!?:;\\u0964]),(?=\\s|$)")
    private val BEFORE_STOP = Regex(",\\s*(?=[.!?:;\\u0964](?:\\s|$))")
    private val SPACED = Regex("(?<=\\S)\\s+,(?=\\s|$)")
    private val AT_START = Regex("^\\s*,\\s*")
    private val AT_END = Regex("\\s*,\\s*$")

    /** No pause doubled, none before a stop, a colon or another pause, none at either end (each needs a comma). */
    private fun tidy(text: String): String {
        if (text.indexOf(',') < 0) return text
        var s = text.replace(DOUBLED, ",")
        s = s.replace(AFTER_STOP, "$1")
        s = s.replace(BEFORE_STOP, "")
        s = s.replace(SPACED, ",")
        s = s.replace(AT_START, "").replace(AT_END, "")
        return s
    }
}
