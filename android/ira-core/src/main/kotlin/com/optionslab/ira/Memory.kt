package com.optionslab.ira

import java.time.LocalDate

/**
 * Boss's own words kept (Jarvis self-improvement, 2026-10-03): "Jarvis, remember that I want to book at 25,000",
 * "what did I tell you?", "forget what I told you". Only kept and read back - never acted on. Pure: the app keeps them,
 * with secrets hidden.
 */
object Memory {
    data class Item(val day: LocalDate, val text: String)

    const val KEEP = 50

    private fun norm(text: String) = " " + spacedWords(text.lowercase())
        .replace(rx("^(jarvis|hey jarvis|ok jarvis|boss|please) "), "") + " "

    /** What to remember from "remember (that) ...", in Boss's own words, or null. */
    fun toKeep(text: String): String? {
        val m = rx("(?i)^\\s*(?:jarvis[,!.]?\\s+|hey jarvis[,!.]?\\s+|please\\s+)?(?:remember|keep in mind|note down|make a note)[:,-]?\\s+(?:that\\s+|this[:,]?\\s+)?(.{3,300})$").find(text.trim()) ?: return null
        val what = m.groupValues[1].trim().trimEnd('.', '!')
        // "Remember my PIN is..." - nothing secret is kept, and "remember me" is not a note.
        if (Secrets.hasSecret(what) || rx("(?i)\\b(m?pin|tpin|pass(word|code|wd|phrase|key)?|pass phrase|pwd|otp|totp|2fa|api[ _-]?key|secret|token|login|cvv|cvc|security (answer|question)|card number|account number)\\b").containsMatchIn(what)) return null
        // "Remember the event RBI policy on Friday" is the events command, not a note.
        if (rx("(?i)^(an |the )?event\\b").containsMatchIn(what)) return null
        // "Remember when Nifty gapped down?", "remember the last time VIX jumped": asked of his market memory, not a note
        // (routing audit, 5 Oct).
        if (rx("(?i)^(when|the last time|last time|how|what|whether)\\b").containsMatchIn(what)) return null
        return what.takeIf { it.split(" ").size >= 2 }
    }

    fun recallAsked(text: String): Boolean = rx("^ (what did i tell you|what did i ask you to remember|what do you remember|what have i told you|my notes to you|what are my reminders|remind me what i said|what should i remember) $").containsMatchIn(norm(text))

    fun forgetAsked(text: String): Boolean = rx("^ (forget what i told you|forget my notes|forget everything i told you|clear my notes|forget the notes) $").containsMatchIn(norm(text))

    fun lines(items: List<Item>): String =
        if (items.isEmpty()) "You haven't asked me to remember anything yet, Boss. Say \"remember that ...\"."
        else "You asked me to remember: " + items.takeLast(10).reversed().joinToString("; ") { "${it.text} (${it.day})" } + "."
}
