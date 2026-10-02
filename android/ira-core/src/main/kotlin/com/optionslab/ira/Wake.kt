package com.optionslab.ira

/**
 * The wake word, read from what the on-device recognizer heard. "Jarvis, how is Nifty?" asks at once; "Jarvis" alone
 * wakes it for the next sentence; anything else is ignored unless it is awake. Pure: the words in, what to do out.
 */
object Wake {
    sealed class Heard {
        /** Not for Jarvis: nothing is kept. */
        object Ignore : Heard()
        /** The wake word alone: answer "Yes?" and take the next sentence as the question. */
        object Awake : Heard()
        /** "Jarvis, stop listening": the owner switches listening off. */
        object Stop : Heard()
        data class Ask(val question: String) : Heard()
    }

    /** How the recognizer tends to write "Jarvis". */
    private val WORDS = listOf("jarvis", "jarvas", "jervis", "jarviss", "jar vis")
    private val FILLER = Regex("^(hey|hi|ok|okay|hello|so|and|please)\\b\\s*")
    private val STOP = Regex("^(stop listening|go to sleep|stop|sleep|shut down|turn off)$")

    /** [awake]: the wake word was said alone a moment ago, so this sentence is the question. */
    fun heard(text: String, awake: Boolean): Heard {
        val t = " " + text.lowercase().replace(Regex("[^a-z0-9% ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        val at = WORDS.mapNotNull { w -> Regex(" $w ").find(t)?.let { it.range.last } }.minOrNull()
        val rest = (if (at != null) t.substring(at) else t).trim().let { r -> var x = r; repeat(3) { x = x.replace(FILLER, "").trim() }; x }
        return when {
            at == null && !awake -> Heard.Ignore
            STOP.matches(rest) -> Heard.Stop
            rest.isEmpty() -> if (at != null) Heard.Awake else Heard.Ignore
            else -> Heard.Ask(rest)
        }
    }

    /**
     * An answer as it is spoken: the first [sentences] sentences, the rupee sign and "Rs" read as rupees. The full answer
     * stays on screen with the facts it was built from.
     */
    fun spoken(text: String, sentences: Int = 3): String {
        val parts = Regex("(?<=[.!?])\\s+").split(text.trim()).filter { it.isNotBlank() }
        return parts.take(sentences).joinToString(" ")
            .replace(Regex("(?:Rs|₹)\\s?([+-]?[\\d,]+(?:\\.\\d+)?)"), "$1 rupees")
            .replace("+", "plus ").replace(" -", " minus ")
    }
}
