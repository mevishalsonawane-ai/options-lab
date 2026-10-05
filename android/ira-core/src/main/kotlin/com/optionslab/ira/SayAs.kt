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
 *  - "24500 CE" / "24,500PE" -> "24500 call" / "24,500 put" (only right after a number, so "PE ratio" stays).
 *
 * The chat keeps the exact figures; this only shapes the words said, adds nothing and never changes a sentence's end,
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
    private val FUTURE = Regex("\\b([A-Z]{2,12})(\\d{2})([A-Z]{3})FUT\\b")
    private val STRIKE_SIDE = Regex("(?<![\\w.,])(\\d{1,3}(?:,\\d{3})+|\\d{2,6})(\\.\\d{1,2})? ?(CE|PE)\\b")

    /** A figure that may be a lakh or more: Indian grouping, Western grouping, or plain digits said as rupees or crore. */
    private const val NUM = "(\\d{1,2}(?:,\\d{2})+,\\d{3}|\\d{1,3}(?:,\\d{3})+|\\d{6,}(?=(?:\\.\\d+)?\\s?(?:rupees|crore)\\b))(\\.\\d+)?"
    private val PREFIXED = Regex("(?:\\bRs\\.?|₹|\\bINR)\\s?([+-]?)$NUM(?![\\w,]|\\.\\d)( crore\\b)?")
    private val BARE = Regex("(?<![\\w.,])$NUM(?![\\w,]|\\.\\d)( rupees crore\\b| crore\\b)?")
    private val RUPEES_CRORE = Regex("(\\d) rupees crore\\b")

    /** [text] with its figures as said aloud; [hindi]: the units in Hindi (लाख, करोड़, कॉल, पुट). */
    fun figures(text: String, hindi: Boolean = false): String {
        // Every pattern below needs a digit (and each its own letters), so a line without them is said as written - most
        // spoken lines skip most of the patterns (speed round 9); a pattern is only skipped when it could not match.
        if (text.isEmpty() || !hasDigit(text)) return text
        var s = text
        if (sided(s)) {
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

    /** "24500" -> "24,500"; "24600.5" -> "24,600.5". */
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
