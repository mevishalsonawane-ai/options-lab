package com.optionslab.ira

/**
 * When Jarvis is not sure he heard a QUESTION right, he says what he took it as before the answer (voice, round 11):
 * "Boss, I took that as "what is Bank Nifty doing". Bank Nifty is up 0.4 percent..." - so a misheard "Nifty" for "Bank
 * Nifty" is caught by Boss at once instead of being answered as if right. Not sure means: the recognizer scored its best
 * reading low ([UNSURE], above the faint level a follow-up is let go at - [Sure]), or its two best readings differ in a
 * word that changes the answer (an index, a number, call or put, buy or sell, up or down, a day or week) and it did not
 * score the best one high ([SURE]). Recognizers without scores are judged by their readings alone.
 *
 * Questions only: an order or a command is never echoed here (each has its own confirm), and this never asks back or
 * waits - the answer comes right after, Boss corrects by asking again. On a locked phone a question about the account is
 * not echoed. Long questions (over [MAX_WORDS] words) are not read back (they would bury the answer). The words read back
 * are Boss's own, secrets hidden ([Secrets]) and one sentence, so "go on" after a cut-in still finds the answer's
 * sentences ([full]). Words only: nothing acts. Pure.
 */
object HeardBack {
    /** Below this score (and the recognizer gave one) a question is read back first. */
    const val UNSURE = 0.5f
    /** At or above this score the readings disagreeing is not enough: the recognizer was sure of the best one. */
    const val SURE = 0.8f
    const val MAX_WORDS = 14

    enum class Why { LOW_SCORE, READINGS_DIFFER }

    private val SPACES = Regex("\\s+")
    private val JOINED = listOf(
        Regex("\\bbank\\s+nifty\\b") to "banknifty", Regex("\\bfin\\s+nifty\\b") to "finnifty",
        Regex("\\bmid\\s*cap\\s+nifty\\b|\\bnifty\\s+mid\\s*cap\\b") to "midcpnifty", Regex("\\bindia\\s+vix\\b") to "vix",
        Regex("\\bnifty\\s+50\\b|\\bnifty\\s+fifty\\b") to "nifty")
    private val SAME = mapOf("calls" to "call", "ce" to "call", "puts" to "put", "pe" to "put", "buying" to "buy", "selling" to "sell",
        "bought" to "buy", "sold" to "sell", "weekly" to "week", "monthly" to "month", "profits" to "profit", "losses" to "loss",
        "highs" to "high", "lows" to "low")
    private val KEY = setOf("nifty", "banknifty", "finnifty", "midcpnifty", "sensex", "vix", "gold", "call", "put", "buy", "sell",
        "up", "down", "high", "low", "today", "yesterday", "tomorrow", "week", "month", "profit", "loss", "open", "close",
        "monday", "tuesday", "wednesday", "thursday", "friday")

    /** The words in [text] that change an answer: indices, numbers, sides, directions and days, said one way. */
    fun keys(text: String): Set<String> {
        var s = text.lowercase().replace(Regex("(?<=\\d),(?=\\d)"), "")
        for ((r, w) in JOINED) s = r.replace(s, w)
        return s.replace(Regex("[^a-z0-9.\\s]"), " ").split(SPACES).map { it.trim('.') }.filter { it.isNotEmpty() }
            .map { SAME[it] ?: it }.filter { it in KEY || it.any(Char::isDigit) }.toSet()
    }

    /**
     * Why [question] should be read back before its answer, or null. [readings]: the recognizer's readings, best first;
     * [sure]: its score for the best one (null: none). [isQuestion]: no order and no command in the words; [lockedAccount]:
     * a locked phone and the account asked about.
     */
    fun why(question: String, readings: List<String>, sure: Float?, isQuestion: Boolean, lockedAccount: Boolean = false): Why? {
        if (!isQuestion || lockedAccount) return null
        val words = question.trim().split(SPACES).filter { it.isNotBlank() }.size
        if (words == 0 || words > MAX_WORDS) return null
        if (sure != null && sure < UNSURE) return Why.LOW_SCORE
        if (sure != null && sure >= SURE) return null
        val best = readings.filter { it.isNotBlank() }
        if (best.size >= 2 && keys(best[0]) != keys(best[1])) return Why.READINGS_DIFFER
        return null
    }

    /** The one sentence said before the answer: Boss's words as heard, secrets hidden, no sentence end inside. */
    fun line(question: String, hindi: Boolean = false): String {
        val q = Secrets.redact(question).replace(Regex("[.!?\\u0964\"“”]+"), " ").replace(SPACES, " ").trim().trimEnd(',', ';', ':')
        return if (hindi) "बॉस, मैंने सुना: \"$q\"।" else "${Address.NAME}, I took that as \"$q\"."
    }

    /** [line] before [spoken] (the answer as said), Boss named once. Null [line]: [spoken] as it is. */
    fun lead(line: String?, spoken: String): String = if (line == null) spoken else Aloud.onceBoss("$line $spoken")

    /** The answer's full text with [line] before it, so "go on" counts the read-back as the sentence it is. */
    fun full(line: String?, full: String): String = if (line == null) full else "$line $full"
}
