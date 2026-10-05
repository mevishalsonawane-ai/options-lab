package com.optionslab.ira

/**
 * Words Jarvis could not place: the closest thing he knows, offered as "Did you mean ...?" - a suggestion only. Nothing
 * is done; Boss says it again (with the name for anything that acts) and the usual checks apply. Lines that start
 * something (add risk) are never offered. Pure.
 */
object Suggest {
    private val EXTRA = listOf(
        "what time does the market close", "is the market open tomorrow", "what did you hear", "how fast are you",
        "which AI model are you using", "what did I miss", "how did you do today", "what is the lot size of <market>",
        "what is the atm strike of <market>", "mute for 30 minutes", "what are my reminders",
    )
    private val STOP = setOf("the", "a", "an", "is", "are", "my", "me", "i", "you", "your", "to", "of", "on", "in", "for",
        "what", "how", "do", "does", "did", "can", "please", "jarvis", "boss", "and", "it", "this", "that", "all", "any")
    private val WORD = Regex("[a-z&]+")

    private fun words(s: String): Set<String> = WORD.findAll(s.lowercase()).map { it.value }.filter { it !in STOP && it.length > 1 }.toSet()

    /** Words that flip a line's meaning: a line is never offered for words that say its opposite. */
    private val OPPOSITE = mapOf("on" to "off", "off" to "on", "paper" to "live", "live" to "paper", "stop" to "start",
        "start" to "stop", "above" to "below", "below" to "above", "cancel" to "place", "close" to "open")
    private val MARKET_WORDS = Market.entries.flatMap { m -> m.aliases.flatMap { words(it) } }.toSet()

    /**
     * The known line closest to [said], or null when none is close enough: its words (the market aside) all heard, or
     * all but one for a line of three or more, and two at least; a line for a market only when a known market was named.
     */
    fun closest(said: String): String? {
        val m = Market.mentioned(said).firstOrNull()?.label?.lowercase()
        val raw = WORD.findAll(said.lowercase()).map { it.value }.toSet()
        val heard = words(said) - MARKET_WORDS
        if (heard.isEmpty()) return null
        val lines = (Intents.LINES.filter { "<n>" !in it && "<level>" !in it && !it.startsWith("start") } + EXTRA)
            .mapNotNull { l -> if ("<market>" in l) m?.let { l.replace("<market>", it) } else l }
        return lines.mapNotNull { l ->
            val lw = WORD.findAll(l.lowercase()).map { it.value }.toSet()
            if (lw.any { w -> OPPOSITE[w]?.let { it in raw && w !in raw } == true }) return@mapNotNull null
            val w = words(l) - MARKET_WORDS
            val shared = w.count { it in heard }
            if (shared >= 2 && shared >= w.size - (if (w.size >= 3) 1 else 0)) Triple(l, shared, w.size) else null
        }.maxWithOrNull(compareBy<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third })?.first
    }

    fun line(said: String): String? = closest(said)?.let { "I didn't catch that, Boss. Did you mean \"$it\"?" }
}
