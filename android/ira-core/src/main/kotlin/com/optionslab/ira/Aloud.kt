package com.optionslab.ira

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A reply as Jarvis says it (Boss, 5 Oct: say it shorter and like a person): built on [Wake.spoken] (the first
 * sentences, rupees read as rupees), then made easy on the ear - "Boss" once, not in every sentence; long decimals
 * rounded ("24,612.40" said "24,612", "0.214%" said "0.21 percent"); "pts" said "points"; clock times to the minute
 * ("09:15:42" said "9:16"); a lakh or more in lakh or crore and option symbols as words ([SayAs]); a pause (a comma) where a bracket, a spaced dash or two figures side by side would run together ([Pauses]); and, when the reply goes on longer than is said, "the rest is in the chat". A Hindi reply
 * stays Hindi (its own words for percent, rupees, plus and minus, and the chat line). The chat keeps the full text;
 * this only shapes the words aloud and adds nothing of the account to them. Pure.
 */
object Aloud {
    /** How much is said: one sentence (short answers), the usual three, or eight ("tell me more"). */
    enum class Length(val sentences: Int) { SHORT(1), USUAL(3), FULL(8) }

    private const val BOSS_HI = "बॉस"
    private val DEVANAGARI = Regex("[\\u0900-\\u097F]")
    /** A sentence ends at . ! ? or the Hindi full stop, never inside a number ("24,612.40"). */
    private val SENTENCE = Regex("(?<=[.!?\\u0964])\\s+")

    /** Does [text] read as Hindi (Devanagari script)? */
    fun hindi(text: String): Boolean = DEVANAGARI.containsMatchIn(text)

    fun say(text: String, length: Length = Length.USUAL, hindi: Boolean = hindi(text)): String = say(text, length.sentences, hindi)

    /** [text] as said aloud: at most [sentences] sentences, addressed to Boss once, figures as a person says them. */
    fun say(text: String, sentences: Int, hindi: Boolean = hindi(text)): String {
        val parts = SENTENCE.split(text.trim()).filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        // Shorter never drops a safety verdict or warning: the trade check's verdict first, every warning kept ([keep]).
        val kept = keep(parts, sentences)
        val cut = kept.size < parts.size
        var s = Wake.spoken(kept.joinToString(" "), kept.size)
        if (hindi) s = s.replace(" rupees", " रुपये").replace("plus ", "प्लस ").replace("minus ", "माइनस ")
        s = numbers(s, hindi)
        s = SayAs.figures(s, hindi)
        s = onceBoss(s)
        if (cut) s = s.trimEnd() + (if (hindi) " बाकी चैट में है।" else " The rest is in the chat.")
        // Never without "Boss" (the owner's wish): a Hindi reply already naming him in Hindi keeps that.
        // Commas where a person would pause - brackets, dashes, separators, figures side by side ([Pauses]); words unchanged.
        return Pauses.shape(if (hindi && s.contains(BOSS_HI)) s else Address.boss(s))
    }

    /** The trade check's verdict heads ([TradeCheck.Verdict.say]); the first two warn. */
    private val WARN_HEADS = setOf("Don't trade now.", "Careful today.")
    private const val GO_HEAD = "Conditions are normal: fine to trade."
    /** The trade check's closing line after the market reads: not part of its verdict. */
    private const val READS_END = "That is how the market is moving now"
    /** A sentence that is a safety verdict or warning: never left out when an answer is said shorter. */
    private val WARNING = rx("\\b(don'?t trade|do not trade|never trade|careful|stop|kill switch|loss limit|daily limit|warning|not now)\\b",
        RegexOption.IGNORE_CASE)

    /** Is [sentence] a safety verdict or warning ("Don't trade now.", "The kill switch is on.", "careful", "loss limit")? */
    fun warning(sentence: String): Boolean = WARNING.containsMatchIn(sentence.replace('’', '\''))

    /**
     * The sentences said when [parts] (one answer's sentences, in order) is said in at most [sentences]: never a safety
     * verdict or warning cut. A trade check's verdict comes first - its head with all its reasons when it says "Don't
     * trade now." or "Careful today.", before the market reads - and every warning sentence past the cut is still said,
     * in its order. Nothing is cut when it fits. Pure; words unchanged, only which are said.
     */
    fun keep(parts: List<String>, sentences: Int): List<String> {
        val n = sentences.coerceAtLeast(1)
        if (parts.size <= n) return parts
        val h = parts.indexOfFirst { it.trim() in WARN_HEADS || it.trim() == GO_HEAD }
        var order = parts
        var must = 0
        if (h >= 0) {
            val end = (h + 1 until parts.size).firstOrNull { parts[it].trim().startsWith(READS_END) } ?: parts.size
            val block = parts.subList(h, end)
            order = block + parts.subList(0, h) + parts.subList(end, parts.size)
            must = if (parts[h].trim() == GO_HEAD) 1 else block.size
        }
        val take = maxOf(n, must)
        return order.take(take) + order.drop(take).filter { warning(it) }
    }

    /** "Boss" (or "बॉस") kept at its first mention only: "Yes, Boss. It is up, Boss." -> "Yes, Boss. It is up." */
    fun onceBoss(text: String): String {
        if (!named(text)) return text
        val name = rx("\\b${Address.NAME}\\b|$BOSS_HI")
        val first = name.find(text) ?: return text
        val head = text.substring(0, first.range.last + 1)
        var rest = text.substring(first.range.last + 1)
        if (!named(rest)) return text
        // ", Boss" before a pause or the end; "Boss, " starting a sentence (the next word then starts it); " Boss" mid-sentence.
        rest = rest.replace(rx(",\\s*(?:${Address.NAME}\\b|$BOSS_HI)(?=[\\s.,!?\\u0964]|$)"), "")
        rest = rest.replace(rx("(^|[.!?\\u0964]\\s+)(?:${Address.NAME}|$BOSS_HI)[,!.]?\\s+(\\S)")) { m ->
            m.groupValues[1] + m.groupValues[2].replaceFirstChar { it.uppercase() }
        }
        rest = rest.replace(rx("\\s+(?:${Address.NAME}\\b|$BOSS_HI)"), "")
        return head + rest
    }

    /** Does [text] hold "Boss" or "बॉस" at all (every pattern in [onceBoss] needs one)? */
    private fun named(text: String) = text.contains(Address.NAME) || text.contains(BOSS_HI)

    /**
     * Figures as said: a decimal on 100 or more said whole ("24,612.40" -> "24,612"), a smaller one to two places at
     * most ("1.2345" -> "1.23", "0.50" -> "0.5"); % as percent; "pts" as points; a clock time to the minute without a
     * leading zero. Dates ("05.10.2026"), versions and words with digits in them are left as written.
     */
    fun numbers(text: String, hindi: Boolean = false): String {
        // Times, decimals and points need a digit, percent its sign: a line with neither is said as written (speed round 9).
        val digits = hasDigit(text)
        if (!digits && text.indexOf('%') < 0) return text
        var s = text
        if (digits && s.indexOf(':') >= 0) s = rx("(?<![\\d:])(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?![\\d:])").replace(s) { m ->
            var h = m.groupValues[1].toInt(); var min = m.groupValues[2].toInt()
            val sec = m.groupValues[3].toIntOrNull() ?: 0
            if (h > 23 || min > 59 || sec > 59) return@replace m.value
            if (sec >= 30) { min++; if (min == 60) { min = 0; h = (h + 1) % 24 } }
            "$h:" + min.toString().padStart(2, '0')
        }
        if (digits && s.indexOf('.') >= 0)
            s = rx("(?<![\\w.,])(\\d{1,3}(?:,\\d{2,3})*|\\d+)\\.(\\d+)(?!\\w|[.,]\\d)").replace(s) { m -> round(m.groupValues[1], m.groupValues[2]) }
        if (s.indexOf('%') >= 0) s = rx("\\s?%").replace(s, if (hindi) " प्रतिशत" else " percent")
        if (digits && (s.indexOf('p') >= 0 || s.indexOf('P') >= 0))
            s = rx("(?<=\\d)\\s?(pts?)\\b", RegexOption.IGNORE_CASE).replace(s) { m -> if (m.groupValues[1].length == 3) " points" else " point" }
        return s
    }

    private fun round(whole: String, frac: String): String {
        val v = BigDecimal(whole.replace(",", "") + "." + frac)
        val places = if (v.abs() >= BigDecimal(100)) 0 else 2
        val r = v.setScale(places, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = if (r.scale() <= 0) r.setScale(0).toPlainString() else r.toPlainString()
        if (!whole.contains(',')) return plain
        // Grouped as written: the same commas (Indian "1,23,456" or "123,456") on the whole part.
        val int = plain.substringBefore('.'); val dec = plain.substringAfter('.', "")
        val indian = rx("^\\d{1,2},\\d{2},").containsMatchIn(whole)
        val grouped = if (indian && int.length > 3) int.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + int.takeLast(3)
            else int.reversed().chunked(3).joinToString(",").reversed()
        return if (dec.isEmpty()) grouped else "$grouped.$dec"
    }
}
