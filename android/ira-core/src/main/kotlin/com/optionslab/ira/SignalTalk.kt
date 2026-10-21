package com.optionslab.ira

/**
 * Jarvis on the Signal tester (15 Oct 2026): "how is channel Sensex Kings doing", "how are the telegram channels doing",
 * "signal tester ka kya haal hai", "is the Bank Nifty Pro channel any good". Answered from the tester's own scorecards (each
 * channel's calls, triggered, win rate, would-be net with a stop, and the p against random entries from 40 triggered calls),
 * always with "shadow — never trades". The channel names and the record are Boss's, so never on a locked phone ([LOCKED]).
 * Read only: a sentence with something to do ("add channel X", "switch off the signal tester") is not read here, and nothing
 * is switched, bought, sold, recorded or promoted. Pure.
 */
object SignalTalk {
    const val LOCKED = "Unlock the phone for that, Boss."
    const val NOTE = "shadow — never trades"

    /** What was asked: one channel by (part of) its [name], or every channel (null). */
    data class Ask(val name: String?)

    private val ACT = Regex(" (switch|switched|turn|band|chalu|enable|disable|set|change|buy|sell|trade|close|cancel|start|stop|pause|resume|add|remove|delete|allow|block|import|paste|follow|copy) ")
    /** A chart's channel (Donchian, Keltner, a price channel) is an indicator, never a Telegram channel. */
    private val INDICATOR = Regex(" (donchian|keltner|bollinger|price channel|channel breakout|channel indicator|regression channel) ")
    private val TESTER = Regex(" (signal tester|signals tester|signal test|telegram channels?|tips channels?|call channels?|telegram calls|telegram tips) ")
    private val CHANNEL = Regex(" channel (.+?) (doing|performing|kaisa|kaise|kesa|score|scorecard|record|results?|ka haal|ka record|any good|worth|accurate|reliable)\\b")
    private val CHANNEL_AFTER = Regex(" (?:is|are|was|how is|how's|hows) (.+?) channel (doing|performing|any good|worth|accurate|reliable|kaisa|score|record)\\b")
    private val ALL_WORDS = Regex(" (channels|tester|all) ")

    /** What [text] asks about the Signal tester, or null. */
    fun asked(text: String): Ask? {
        val t = Spaced.joined(text)
        if (ACT.containsMatchIn(t) || INDICATOR.containsMatchIn(t)) return null
        CHANNEL.find(t)?.let { m -> return Ask(clean(m.groupValues[1])) }
        CHANNEL_AFTER.find(t)?.let { m -> return Ask(clean(m.groupValues[1])) }
        if (TESTER.containsMatchIn(t)) return Ask(null)
        return null
    }

    private fun clean(s: String): String? = s.trim().removePrefix("the ").removePrefix("my ").trim()
        .takeUnless { it.isEmpty() || ALL_WORDS.containsMatchIn(" $it ") || it in setOf("the", "my", "this", "that", "a") }

    /** One channel's facts: its name, its one-line card, and its details. */
    data class Channel(val name: String, val line: String, val details: List<String>)

    /**
     * The answer to [a] from the tester's [channels] ([on]: its switch). One channel named: its line and details; a name that
     * matches none: the channels there are; every channel: one line each.
     */
    fun answer(a: Ask, channels: List<Channel>, on: Boolean): String {
        val head = "Signal tester ($NOTE)" + if (on) "" else ", switched off"
        if (channels.isEmpty()) return "$head: no channels on your allow-list yet, Boss. Add one on Home → Strategies → Signal tester."
        val name = a.name
        if (name != null) {
            val key = norm(name)
            val hit = channels.firstOrNull { norm(it.name) == key } ?: channels.firstOrNull { norm(it.name).contains(key) || key.contains(norm(it.name)) }
            if (hit == null) return "$head: I have no channel called \"$name\". On your allow-list: ${channels.joinToString(", ") { it.name }}."
            return "$head, ${hit.name}: ${hit.line}. " + hit.details.joinToString(" ")
        }
        return "$head: " + channels.joinToString("; ") { "${it.name}: ${it.line}" } + "."
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
}
