package com.optionslab.ira

/**
 * Words Jarvis could not place: the closest thing he knows, offered as "Did you mean ...?" - a suggestion only. Nothing
 * is done; Boss says it again (with the name for anything that acts) and the usual checks apply. Lines that start
 * something (add risk) are never offered. Pure.
 */
object Suggest {
    private val EXTRA = listOf(
        "what time does the market close", "is the market open tomorrow", "what did you hear", "how fast are you",
        "which AI model are you using", "what did I miss", "how did you do today", "what is the lot size of nifty",
        "what is the atm strike of nifty", "mute for 30 minutes", "what are my reminders",
    )
    private val STOP = setOf("the", "a", "an", "is", "are", "my", "me", "i", "you", "your", "to", "of", "on", "in", "for",
        "what", "how", "do", "does", "did", "can", "please", "jarvis", "boss", "and", "it", "this", "that", "all", "any")
    private val WORD = Regex("[a-z&]+")

    private fun words(s: String): Set<String> = WORD.findAll(s.lowercase()).map { it.value }.filter { it !in STOP && it.length > 1 }.toSet()

    /** The known line closest to [said], or null when none shares enough words (two at least, and half of the line's). */
    fun closest(said: String): String? {
        val m = Market.mentioned(said).firstOrNull()?.label?.lowercase() ?: "nifty"
        val heard = words(said)
        if (heard.isEmpty()) return null
        val lines = Intents.LINES.filter { "<n>" !in it && "<level>" !in it && !it.startsWith("start") }.map { it.replace("<market>", m) } + EXTRA
        return lines.map { l -> val w = words(l); Triple(l, w.count { it in heard }, w.size) }
            .filter { (_, shared, size) -> shared >= 2 && shared * 2 >= size }
            .maxWithOrNull(compareBy<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third })?.first
    }

    fun line(said: String): String? = closest(said)?.let { "I didn't catch that, Boss. Did you mean \"$it\"?" }
}
