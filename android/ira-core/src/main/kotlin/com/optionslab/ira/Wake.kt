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
        /** "Jarvis, stop" / "enough" / "quiet": stop talking now and keep listening (never a command to stop anything). */
        object Hush : Heard()
        data class Ask(val question: String) : Heard()
    }

    /** How the recognizer tends to write "Jarvis". */
    // With the ways an Indian-English recognizer often writes the name (Boss, 4 Oct: not heard by name).
    private val WORDS = listOf("jarvis", "jarvas", "jervis", "jarviss", "jar vis", "jarvish", "jarwis", "jaarvis", "jarviz", "jarbis")
    /**
     * Misreadings of a softly said "Jarvis" (Boss, 4 Oct: "service how are you"): counted only as the very first word,
     * so "the service is slow" never wakes him. Never "named": actions still need the name itself.
     */
    private val FIRST = Regex("^ (hey |ok |okay )?(service|jarves|javis|jarvice|jervice|harvis|charvis|jarvi|jarvez|jaris) ")
    private val FILLER = Regex("^(hey|hi|ok|okay|hello|so|and|please)\\b\\s*")
    private val STOP = Regex("^(stop listening|go to sleep|sleep|shut down|turn off|switch off)$")
    /** Boss's "be quiet" (3 Oct: "Jarvis stop" means stop talking, not stop my orders). */
    private val HUSH = Regex("^(just |please |now )?(stop|stop it|stop now|stop that|stop talking|stop speaking|enough|that s enough|thats enough|quiet|be quiet|shut up|silence|hush|chup|chup ho jao|chup raho|chup karo|bas|bas karo|never ?mind|forget it|cancel that)( please| now| jarvis)?$")

    /** Is [text] only "stop talking" (said to Jarvis, with or without its name)? */
    /**
     * A listening turn the recognizer ended with no words (7, no match) or silence (6) while its partial reading held
     * the name (Boss, 4 Oct: "Jarvis" alone was read mid-turn, then dropped as no match - every turn). Counted as the
     * name heard: the partial is what to act on, else null.
     */
    fun lostTurn(error: Int, partial: String?, awake: Boolean = false): String? {
        if (error != 7 && error != 6) return null
        val p = partial?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Awake (after "Yes, Boss?"): the question read mid-turn counts too - at least two words, so a stray sound is not
        // taken for one (Boss, 4 Oct: long turns ended in error 7 with words already read).
        if (awake && p.split(Regex("\\s+")).size >= 2) return p
        return p.takeIf { heard(it, false) !is Heard.Ignore }
    }

    fun hush(text: String): Boolean = HUSH.matches(text.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\b(hey |ok |okay )?(jarvis|jarvas|jervis|jarviss|jarvish|jarwis|jaarvis|jarviz|jarbis)\\b"), " ")
        .replace(Regex("\\s+"), " ").trim())

    /** [awake]: the wake word was said alone a moment ago, so this sentence is the question. */
    fun heard(text: String, awake: Boolean): Heard {
        val t = " " + text.lowercase().replace(Regex("[^a-z0-9% ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        val at = WORDS.mapNotNull { w -> Regex(" $w ").find(t)?.let { it.range.last } }.minOrNull()
            ?: FIRST.find(t)?.let { it.range.last }
        val rest = (if (at != null) t.substring(at) else t).trim().let { r -> var x = r; repeat(3) { x = x.replace(FILLER, "").trim() }; x }
        return when {
            at == null && !awake -> Heard.Ignore
            STOP.matches(rest) -> Heard.Stop
            HUSH.matches(rest) -> Heard.Hush
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
            .replace("+", "plus ").replace(Regex("(^|\\s)-(?=\\d)"), "$1minus ")
    }

    /**
     * The owner's answer when Jarvis asked a yes-or-no question (a news trade to approve): true for yes, false for no,
     * null when it is neither or unclear. Any "no" word wins over a "yes" ("yes... no, leave it" is a no), so a muddled
     * answer never places a trade.
     */
    fun yesNo(text: String): Boolean? {
        val h = Hinglish.yesNo(text); val e = english(text)
        return if (h == false || e == false) false else if (h == true || e == true) true else null
    }

    private fun english(text: String): Boolean? {
        val t = " " + text.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (t.isBlank()) return null
        if (Regex(" (no+|nope|nah|not|not now|don t|dont|do not|reject|rejected|cancel|skip|leave it|stop|wait|never|negative|abort|hold off|hold on|decline|declined|deny|denied|later) ").containsMatchIn(t)) return false
        if (Regex(" (yes|yeah|yep|yup|sure|approve|approved|confirm|confirmed|go ahead|do it|place it|buy it|take it|ok|okay|affirmative|positive) ").containsMatchIn(t)) return true
        return null
    }

    private fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+")).filter { it.length > 1 }

    /**
     * Jarvis's own words heard back (the speaker's tail, the room's echo): most of what was heard is in what it just
     * said. Such words are never a question - answering them is how one answer repeats itself.
     */
    fun echo(heard: String, lastSaid: String?): Boolean {
        if (lastSaid.isNullOrBlank()) return false
        val h = words(heard)
        // A short follow-up ("and nifty?", "is it up?") reuses the answer's words: only three or more count as an echo.
        if (h.size < 3) return false
        val said = words(lastSaid).toSet()
        return h.count { it in said } >= maxOf(1.0, h.size * 0.7)
    }
}
