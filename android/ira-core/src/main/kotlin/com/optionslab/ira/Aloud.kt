package com.optionslab.ira

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A reply as Jarvis says it (Boss, 5 Oct: say it shorter and like a person): built on [Wake.spoken] (the first
 * sentences, rupees read as rupees), then made easy on the ear - "Boss" once, not in every sentence; long decimals
 * rounded ("24,612.40" said "24,612", "0.214%" said "0.21 percent"); "pts" said "points"; clock times to the minute
 * ("09:15:42" said "9:16"); a lakh or more in lakh or crore and option symbols as words ([SayAs]); and, when the reply goes on longer than is said, "the rest is in the chat". A Hindi reply
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
        val n = sentences.coerceAtLeast(1)
        val cut = parts.size > n
        var s = Wake.spoken(parts.take(n).joinToString(" "), n)
        if (hindi) s = s.replace(" rupees", " रुपये").replace("plus ", "प्लस ").replace("minus ", "माइनस ")
        s = numbers(s, hindi)
        s = SayAs.figures(s, hindi)
        s = onceBoss(s)
        if (cut) s = s.trimEnd() + (if (hindi) " बाकी चैट में है।" else " The rest is in the chat.")
        // Never without "Boss" (the owner's wish): a Hindi reply already naming him in Hindi keeps that.
        return if (hindi && s.contains(BOSS_HI)) s else Address.boss(s)
    }

    /** "Boss" (or "बॉस") kept at its first mention only: "Yes, Boss. It is up, Boss." -> "Yes, Boss. It is up." */
    fun onceBoss(text: String): String {
        val name = rx("\\b${Address.NAME}\\b|$BOSS_HI")
        val first = name.find(text) ?: return text
        val head = text.substring(0, first.range.last + 1)
        var rest = text.substring(first.range.last + 1)
        // ", Boss" before a pause or the end; "Boss, " starting a sentence (the next word then starts it); " Boss" mid-sentence.
        rest = rest.replace(rx(",\\s*(?:${Address.NAME}\\b|$BOSS_HI)(?=[\\s.,!?\\u0964]|$)"), "")
        rest = rest.replace(rx("(^|[.!?\\u0964]\\s+)(?:${Address.NAME}|$BOSS_HI)[,!.]?\\s+(\\S)")) { m ->
            m.groupValues[1] + m.groupValues[2].replaceFirstChar { it.uppercase() }
        }
        rest = rest.replace(rx("\\s+(?:${Address.NAME}\\b|$BOSS_HI)"), "")
        return head + rest
    }

    /**
     * Figures as said: a decimal on 100 or more said whole ("24,612.40" -> "24,612"), a smaller one to two places at
     * most ("1.2345" -> "1.23", "0.50" -> "0.5"); % as percent; "pts" as points; a clock time to the minute without a
     * leading zero. Dates ("05.10.2026"), versions and words with digits in them are left as written.
     */
    fun numbers(text: String, hindi: Boolean = false): String {
        var s = rx("(?<![\\d:])(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?![\\d:])").replace(text) { m ->
            var h = m.groupValues[1].toInt(); var min = m.groupValues[2].toInt()
            val sec = m.groupValues[3].toIntOrNull() ?: 0
            if (h > 23 || min > 59 || sec > 59) return@replace m.value
            if (sec >= 30) { min++; if (min == 60) { min = 0; h = (h + 1) % 24 } }
            "$h:" + min.toString().padStart(2, '0')
        }
        s = rx("(?<![\\w.,])(\\d{1,3}(?:,\\d{2,3})*|\\d+)\\.(\\d+)(?!\\w|[.,]\\d)").replace(s) { m -> round(m.groupValues[1], m.groupValues[2]) }
        s = rx("\\s?%").replace(s, if (hindi) " प्रतिशत" else " percent")
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
