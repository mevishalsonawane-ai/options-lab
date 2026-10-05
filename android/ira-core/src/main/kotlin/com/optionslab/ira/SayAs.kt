package com.optionslab.ira

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Figures as an Indian trader says them aloud (Voice, round 10): the phone's voice reads "1,23,456" digit group by
 * digit group and "NIFTY25O0724500CE" letter by letter. So, aloud only:
 *  - an amount of a lakh or more is said in lakh or crore: "1,23,456 rupees" (or "Rs 1,23,456") -> "1.23 lakh rupees",
 *    "2,50,00,000" -> "2.5 crore", "1,23,456 crore" -> "1.23 lakh crore"; a figure under a lakh stays as written;
 *  - "N rupees crore" (a "Rs N crore" read as rupees) -> "N crore rupees";
 *  - an option's trading symbol is read as words: "NIFTY25O0724500CE" -> "Nifty 7 October 24,500 call",
 *    "BANKNIFTY26OCT52000PE" -> "Bank Nifty October 52,000 put", "NIFTY26OCTFUT" -> "Nifty October future";
 *  - the paper account's day-month-year symbol too (Voice, round 23; it was spelt out letter by letter):
 *    "NIFTY29SEP2624500PE" -> "Nifty 29 September 24,500 put", "BANKNIFTY27OCT2655100CE" -> "Bank Nifty 27 October
 *    55,100 call" - only with seven or eight digits after the month (two of year, a strike of five or six), so a Zerodha
 *    symbol (year, month, strike of at most six digits) is never read so;
 *  - "24500 CE" / "24,500PE" -> "24500 call" / "24,500 put" (only right after a number, so "PE ratio" stays);
 *  - a plus between two figures is said as one (Voice, round 19): "Liquidity 15+5" -> "Liquidity 15 plus 5" (the voice
 *    read it "fifteen five"); a sign before a lone figure ("+0.4") is left as written;
 *  - a news line's "(word tone +0.0)" is not said (Voice, round 19): the sentence already says how it reads ("It reads
 *    good for Nifty"), and a bare word-list score said aloud tells Boss nothing. Only that aside, only aloud;
 *  - a range written with a dash is said with "to" (Voice, round 24): "a usual day spans about 24,300-24,700",
 *    "the opening range 9:15-10:00", "gapped 0.5-1 percent" were read "24,300 minus 24,700" or run together as one
 *    number; now "24,300 to 24,700", "9:15 to 10:00", "0.5 to 1 percent" (Hindi "से"). Only two figures (or two clock
 *    times) joined by one unspaced hyphen or en dash with nothing else glued on either side - so a date ("2026-10-05",
 *    "05-10-2026"), a phone number ("98765-43210": a side of more than 6 digits, or of more than 4 ungrouped, is no figure
 *    Jarvis writes), a year span ("FY2025-26"), a symbol and a spaced minus ("24,512 - 85") stay as written;
 *  - a band's "±" and a multiple's "x" said as words (Voice, round 25): the VIX band record's "a median ±1.00% of the
 *    price", "±185 points around 24,650.00", the straddle's and the expected move's "about ±210 points" lost the sign
 *    or were read "plus-minus sign"; "its range is 1.3x the usual" was read "one point three ex". Now "plus or minus 1
 *    percent", "about plus or minus 210 points", "1.3 times the usual" (Hindi "प्लस-माइनस", "गुना"). Only a "±" right
 *    before a figure, and only an "x" (or "×") glued to a short figure with no letter or digit after it - so "2x3",
 *    "0x1F", "x2" and words with an x stay as written.
 *
 * The chat keeps the exact figures; this only shapes the words said, adds nothing new and never changes a sentence's end,
 * so "go on" after a cut-in finds the same sentences ([BargeIn]). Applying it twice changes nothing. Pure.
 */
object SayAs {
    private val INDEX = mapOf("NIFTY" to "Nifty", "BANKNIFTY" to "Bank Nifty", "FINNIFTY" to "Fin Nifty",
        "MIDCPNIFTY" to "Midcap Nifty", "SENSEX" to "Sensex", "BANKEX" to "Bankex")
    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private val MONTH_NAMES = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September",
        "October", "November", "December")
    /** Zerodha's weekly-expiry month letter: 1-9 for January to September, O, N, D for the rest. */
    private const val WEEK_MONTH = "123456789OND"

    /** NAME + YY + (MMM | M DD) + strike + CE/PE. */
    private val OPTION = Regex("\\b([A-Z]{2,12})(\\d{2})(?:([A-Z]{3})|([1-9OND])(\\d{2}))(\\d{3,6}(?:\\.\\d{1,2})?)(CE|PE)\\b")
    /** An index option with no expiry in its name (the paper account's): "NIFTY25000CE". */
    private val PLAIN_OPTION = Regex("\\b(NIFTY|BANKNIFTY|FINNIFTY|MIDCPNIFTY|SENSEX|BANKEX)(\\d{3,6})(CE|PE)\\b")
    /**
     * The paper account's NAME + DD + MMM + YY + strike + CE/PE ("NIFTY29SEP2624500PE"): a strike of five or six digits
     * after the two of the year, so seven or eight digits in all after the month - never a Zerodha symbol's (a year
     * before the month, a strike of at most six digits after it).
     */
    private val DAY_OPTION = Regex("\\b([A-Z]{2,12})(\\d{1,2})([A-Z]{3})(\\d{2})(\\d{5,6}(?:\\.\\d{1,2})?)(CE|PE)\\b")
    private val FUTURE = Regex("\\b([A-Z]{2,12})(\\d{2})([A-Z]{3})FUT\\b")
    private val STRIKE_SIDE = Regex("(?<![\\w.,])(\\d{1,3}(?:,\\d{3})+|\\d{2,6})(\\.\\d{1,2})? ?(CE|PE)\\b")

    /** A figure that may be a lakh or more: Indian grouping, Western grouping, or plain digits said as rupees or crore. */
    private const val NUM = "(\\d{1,2}(?:,\\d{2})+,\\d{3}|\\d{1,3}(?:,\\d{3})+|\\d{6,}(?=(?:\\.\\d+)?\\s?(?:rupees|crore)\\b))(\\.\\d+)?"
    private val PREFIXED = Regex("(?:\\bRs\\.?|₹|\\bINR)\\s?([+-]?)$NUM(?![\\w,]|\\.\\d)( crore\\b)?")
    private val BARE = Regex("(?<![\\w.,])$NUM(?![\\w,]|\\.\\d)( rupees crore\\b| crore\\b)?")
    private val RUPEES_CRORE = Regex("(\\d) rupees crore\\b")
    /**
     * A plus joining a short whole number to a figure ("15+5", "2+3"): nothing between the number and the plus, and that
     * number at most three digits with no comma or decimal - so a level and its change ("24,512 +85", "24512 +85") is
     * never read as a sum.
     */
    private val PLUS = Regex("(?<![\\w,.])(\\d{1,3})\\+ ?(?=\\d)")
    /** The news line's word-list score ([NewsAnalyst.take]): " (word tone +0.0)". */
    private val WORD_TONE = Regex(" ?\\(word tone [+-]?\\d+(?:\\.\\d+)?\\)")
    /** One side of a range: a clock time ("9:15"), a grouped figure ("24,300", "1,23,456.5") or plain digits ("0.5", "15"). */
    private const val SIDE = "(?:\\d{1,2}:\\d{2}|\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)"
    /**
     * Two figures joined by one unspaced hyphen or en dash: nothing of a word, a figure, a colon or another dash before the
     * first or after the second - so dates ("2026-10-05", "05-10-2026"), phone numbers and "FY2025-26" are never one.
     */
    /** "am" / "pm" right after: "3-30 pm" is half past three, not a range. */
    private val CLOCK_AFTER = Regex("\\s?[aApP]\\.?[mM]\\b")
    private val RANGE = Regex("(?<![\\w.,:\\-\\u2013+])($SIDE)[\\-\\u2013]($SIDE)(?![\\w:\\-\\u2013]|[.,]\\d)")

    /** "±" before a figure (a space between allowed): "±1.00%", "± 185 points". */
    private val PLUS_MINUS = Regex("±\\s?(?=\\d)")
    /** A multiple: a short figure with "x" or "×" glued on and no letter or digit after ("1.3x the usual", "2x."). */
    private val TIMES = Regex("(?<![\\w.,])(\\d{1,3}(?:\\.\\d{1,2})?)[x×](?![\\w×])")

    /** At most this many digits before the point on either side of a range (commas not counted: "1,00,000" is six). */
    private const val RANGE_DIGITS = 6
    /** An ungrouped side longer than this is no figure Jarvis writes (he groups 10,000 and up): a phone number's half, an id. */
    private const val RANGE_PLAIN_DIGITS = 4

    /** [side] may be one side of a range: short enough, and grouped when 10,000 or more - so "98765-43210" stays as written. */
    private fun rangeSide(side: String): Boolean {
        if (side.contains(':')) return true
        val whole = side.substringBefore('.')
        val digits = whole.count { it.isDigit() }
        return digits <= RANGE_DIGITS && (whole.contains(',') || digits <= RANGE_PLAIN_DIGITS)
    }

    /** [text] with its figures as said aloud; [hindi]: the units in Hindi (लाख, करोड़, कॉल, पुट). */
    fun figures(text: String, hindi: Boolean = false): String {
        // Every pattern below needs a digit (and each its own letters), so a line without them is said as written - most
        // spoken lines skip most of the patterns (speed round 9); a pattern is only skipped when it could not match.
        if (text.isEmpty() || !hasDigit(text)) return text
        var s = text
        if (s.contains("(word tone ")) s = WORD_TONE.replace(s, "")
        if (s.indexOf('±') >= 0) s = PLUS_MINUS.replace(s, if (hindi) "प्लस-माइनस " else "plus or minus ")
        if (s.indexOf('x') >= 0 || s.indexOf('×') >= 0) s = TIMES.replace(s, if (hindi) "\$1 गुना" else "\$1 times")
        if (s.indexOf('-') >= 0 || s.indexOf('\u2013') >= 0) {
            val src = s
            s = RANGE.replace(src) { m ->
                // "3-30 pm" is a clock time heard with a dash, not a range: as written.
                if (m.groupValues[2].length == 2 && CLOCK_AFTER.matchesAt(src, m.range.last + 1)) m.value
                // A phone number ("call 98765-43210") or any side too long for a figure: as written.
                else if (!rangeSide(m.groupValues[1]) || !rangeSide(m.groupValues[2])) m.value
                else m.groupValues[1] + (if (hindi) " से " else " to ") + m.groupValues[2]
            }
        }
        if (s.indexOf('+') >= 0) s = PLUS.replace(s, if (hindi) "\$1 प्लस " else "\$1 plus ")
        if (sided(s)) {
            s = DAY_OPTION.replace(s) { m -> dayOption(m, hindi) ?: m.value }
            s = OPTION.replace(s) { m -> option(m, hindi) ?: m.value }
            s = PLAIN_OPTION.replace(s) { m -> "${INDEX[m.groupValues[1]]} ${group(m.groupValues[2])} ${side(m.groupValues[3], hindi)}" }
        }
        if (s.contains("FUT")) s = FUTURE.replace(s) { m ->
            val mo = MONTHS.indexOf(m.groupValues[3])
            if (mo < 0) m.value else "${name(m.groupValues[1])} ${MONTH_NAMES[mo]} " + (if (hindi) "फ्यूचर" else "future")
        }
        if (sided(s)) s = STRIKE_SIDE.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + " " + side(m.groupValues[3], hindi) }
        // A lakh or more is written with a comma between digits or as six digits in a row ([NUM]).
        if (mayBeBig(s)) {
            if (s.contains("Rs") || s.contains('₹') || s.contains("INR")) s = PREFIXED.replace(s) { m ->
                val crore = m.groupValues[4].isNotEmpty()
                val said = big(m.groupValues[2] + m.groupValues[3], crore, hindi) ?: return@replace m.value
                m.groupValues[1].let { if (it == "+") "plus " else if (it == "-") "minus " else "" } + said + " " + (if (hindi) "रुपये" else "rupees")
            }
            s = BARE.replace(s) { m ->
                val tail = m.groupValues[3]
                val crore = tail.isNotEmpty()
                val said = big(m.groupValues[1] + m.groupValues[2], crore, hindi) ?: return@replace m.value
                said + if (tail == " rupees crore") " " + (if (hindi) "रुपये" else "rupees") else ""
            }
        }
        return if (s.contains(" rupees crore")) RUPEES_CRORE.replace(s) { m -> m.groupValues[1] + " crore rupees" } else s
    }

    /** Could [s] hold "CE" or "PE" (every option and strike pattern ends in one)? */
    private fun sided(s: String) = s.contains("CE") || s.contains("PE")

    /** Could [s] hold a [NUM]: a digit, a comma and a digit, or six digits in a row? */
    private fun mayBeBig(s: String): Boolean {
        var run = 0
        for (i in s.indices) {
            val c = s[i]
            if (c in '0'..'9') { if (++run >= 6) return true }
            else { if (c == ',' && run > 0 && i + 1 < s.length && s[i + 1] in '0'..'9') return true; run = 0 }
        }
        return false
    }

    private fun side(cepe: String, hindi: Boolean) = if (cepe == "CE") (if (hindi) "कॉल" else "call") else (if (hindi) "पुट" else "put")

    private fun name(u: String) = INDEX[u] ?: (u.take(1) + u.drop(1).lowercase(Locale.ENGLISH))

    private fun option(m: MatchResult, hindi: Boolean): String? {
        val (u, _, mmm, wm, dd, strike, cepe) = m.destructured
        val expiry = if (mmm.isNotEmpty()) {
            val mo = MONTHS.indexOf(mmm); if (mo < 0) return null
            MONTH_NAMES[mo]
        } else {
            val mo = WEEK_MONTH.indexOf(wm[0]); val day = dd.toInt()
            if (mo < 0 || day !in 1..31) return null
            "$day ${MONTH_NAMES[mo]}"
        }
        return "${name(u)} $expiry ${group(strike)} ${side(cepe, hindi)}"
    }

    /** "NIFTY29SEP2624500PE" -> "Nifty 29 September 24,500 put"; null when the day or the month is not one. */
    private fun dayOption(m: MatchResult, hindi: Boolean): String? {
        val (u, dd, mmm, _, strike, cepe) = m.destructured
        val mo = MONTHS.indexOf(mmm)
        val day = dd.toInt()
        if (mo < 0 || day !in 1..31) return null
        return "${name(u)} $day ${MONTH_NAMES[mo]} ${group(strike)} ${side(cepe, hindi)}"
    }

    /** "24500" -> "24,500";"24600.5" -> "24,600.5". */
    private fun group(n: String): String {
        val int = n.substringBefore('.'); val dec = n.substringAfter('.', "")
        val g = int.reversed().chunked(3).joinToString(",").reversed()
        return if (dec.isEmpty()) g else "$g.$dec"
    }

    /**
     * [figure] (in rupees, or in crore when [inCrore]) said in lakh or crore, or null when it is under a lakh (said as
     * written). Two decimals at most below 100 of the unit, whole at 100 or more; a lakh figure that rounds to 100 is
     * said as 1 crore.
     */
    private fun big(figure: String, inCrore: Boolean, hindi: Boolean): String? {
        val v = figure.replace(",", "").toBigDecimalOrNull() ?: return null
        val lakh = if (hindi) "लाख" else "lakh"; val crore = if (hindi) "करोड़" else "crore"
        if (inCrore) {
            if (v < LAKH) return null
            return "${unit(v.divide(LAKH))} $lakh $crore"
        }
        if (v < LAKH) return null
        if (v < CRORE) {
            val l = v.divide(LAKH)
            if (l.setScale(2, RoundingMode.HALF_UP) < HUNDRED) return "${unit(l)} $lakh"
        }
        return "${unit(v.divide(CRORE))} $crore"
    }

    private fun unit(x: BigDecimal): String {
        val r = x.setScale(if (x.setScale(2, RoundingMode.HALF_UP) >= HUNDRED) 0 else 2, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = if (r.scale() <= 0) r.setScale(0).toPlainString() else r.toPlainString()
        return group(plain)
    }

    private val LAKH = BigDecimal(100_000)
    private val CRORE = BigDecimal(10_000_000)
    private val HUNDRED = BigDecimal(100)
}
