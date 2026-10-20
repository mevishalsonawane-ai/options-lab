package com.optionslab.ira

import kotlin.math.ceil
import kotlin.math.ln

/**
 * The backup passphrase's strength rule and, since 20 Oct, exactly what a weak one needs. Boss's 19 Oct diagnostics held
 * "That passphrase is too easy to guess: make it longer, or mix words, digits and symbols" five times: the words never said
 * how much longer. The rule itself is unchanged (moved here from the app's Backup, the same figures): at least [MIN]
 * characters, and an estimated [FAIR_BITS] bits or more - the length (repeats counted at most twice per different
 * character) times log2 of the pool of character kinds used (a-z 26, A-Z 26, 0-9 10, symbols and spaces 33, other letters
 * 26). [advice] works the needed length out from that same estimate, so following it always passes. Pure.
 */
object Passphrase {
    const val MIN = 10
    /** At or over this: fair (accepted); under it: weak. */
    const val FAIR_BITS = 50.0
    const val STRONG_BITS = 72.0

    private const val DIGITS = 10
    private const val SYMBOLS = 33

    fun pool(p: CharArray): Int {
        var pool = 0
        if (p.any { it in 'a'..'z' }) pool += 26
        if (p.any { it in 'A'..'Z' }) pool += 26
        if (p.any { it in '0'..'9' }) pool += DIGITS
        if (p.any { !it.isLetterOrDigit() }) pool += SYMBOLS
        if (p.any { it.isLetter() && it !in 'a'..'z' && it !in 'A'..'Z' }) pool += 26
        return pool
    }

    private fun log2(x: Int) = ln(maxOf(x, 2).toDouble()) / ln(2.0)

    /** The estimate: the counted length times log2 of the pool. */
    fun bits(p: CharArray): Double = minOf(p.size, p.toSet().size * 2) * log2(pool(p))

    /** 0 too short, 1 weak, 2 fair, 3 strong (fair or strong is accepted). */
    fun strength(p: CharArray): Int {
        if (p.size < MIN) return 0
        val b = bits(p)
        return when { b < FAIR_BITS -> 1; b < STRONG_BITS -> 2; else -> 3 }
    }

    fun accepted(p: CharArray): Boolean = strength(p) >= 2

    /** The counted characters a pool of [pool] needs to reach [FAIR_BITS] (never under [MIN]). */
    fun lengthFor(pool: Int): Int = maxOf(MIN, ceil(FAIR_BITS / log2(pool) - 1e-9).toInt())

    /** What a passphrase needs: [short] for the line under the field, [full] for the banner. */
    data class Advice(val short: String, val full: String)

    const val EXAMPLE = "Four unrelated words joined by dashes, with a number, pass easily (like river-lamp-cloud-42; choose your own)."

    /** What [p] still needs to be accepted, or null when it already is. Says only counts, never the passphrase. */
    fun advice(p: CharArray): Advice? {
        if (accepted(p)) return null
        if (p.size < MIN) {
            val s = "${p.size} of at least $MIN characters"
            return Advice(s, "That passphrase is too short: it needs at least $MIN characters (${p.size} now). $EXAMPLE")
        }
        val pool = pool(p)
        val need = lengthFor(pool)
        val distinct = p.toSet().size
        val parts = ArrayList<String>()
        if (p.size < need) parts += "make it $need characters long as typed (${p.size} now)"
        // Repeats count at most twice for each different character ("aaaaaaaaaaaa" counts as 2).
        val different = (need + 1) / 2
        if (distinct < different) parts += "use at least $different different characters ($distinct now)"
        // The same estimate with a digit and a symbol (a dash or a space) added: the shorter way, when there is one.
        val more = pool + (if (p.none { it in '0'..'9' }) DIGITS else 0) + (if (p.none { !it.isLetterOrDigit() }) SYMBOLS else 0)
        val needMore = lengthFor(more)
        // Only when repeats are not the problem (the two new characters count too).
        val alt = if (more > pool && needMore < need && (distinct + 2) * 2 >= needMore) {
            val what = listOfNotNull(if (p.none { it in '0'..'9' }) "a digit" else null, if (p.none { !it.isLetterOrDigit() }) "a dash or a space" else null)
                .joinToString(" and ")
            "or $needMore with $what in it"
        } else null
        val needs = (parts + listOfNotNull(alt)).joinToString(", ")
        return Advice("Weak: $needs", "That passphrase is too easy to guess: $needs. $EXAMPLE")
    }
}
