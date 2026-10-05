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
    private val HUSH = Regex("^(just |please |now )?(stop|stop stop|stop it|stop now|stop that|stop talking|stop speaking|enough|that s enough|thats enough|quiet|be quiet|shut up|silence|hush|chup|chup ho jao|chup raho|chup karo|bas|bas bas|bas karo|ruko|rukko|ruko ruko|rukiye|ruk jao|wait|wait wait|never ?mind|forget it|cancel that|" +
        // Understanding round 24: a moment asked for, as Boss says it ("hold on", "ek minute ruko") - a hush, never a question.
        "hold on|one second|one sec|one moment|wait a (second|sec|minute|min|moment)|just a (second|sec|minute|moment)|" +
        "ek (minute|min|second|sec|sec ruko|second ruko|minute ruko|min ruko)|ruko ek (minute|min|second|sec)|ruko na|ruk ja|thehro|theher jao|thairo)( please| now| jarvis| boss)?$")

    /**
     * Does [text] hold the name itself (never a soft misreading)? Jarvis never says his own name aloud, so the name is
     * never his own voice heard back ([BargeIn]).
     */
    fun named(text: String): Boolean {
        val t = " " + spacedWords(text.lowercase(), "%") + " "
        return WORDS.any { t.contains(" $it ") }
    }

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
        if (awake && p.split(rx("\\s+")).size >= 2) return p
        return p.takeIf { heard(it, false) !is Heard.Ignore }
    }

    fun hush(text: String): Boolean = HUSH.matches(text.lowercase().replace(rx("[^a-z ]"), " ").replace(rx("\\b(hey |ok |okay )?(jarvis|jarvas|jervis|jarviss|jarvish|jarwis|jaarvis|jarviz|jarbis)\\b"), " ")
        .replace(rx("\\s+"), " ").trim())

    /** [awake]: the wake word was said alone a moment ago, so this sentence is the question. */
    fun heard(text: String, awake: Boolean): Heard {
        // "jarvis's" is the name ([com.optionslab.ira.Heard.nameForms]); "darius good evening" a slip of it ([com.optionslab.ira.Heard.nameSlip]; voice, round 26).
        val t = com.optionslab.ira.Heard.nameForms(" " + spacedWords(text.lowercase(), "%") + " ")
        val at = WORDS.mapNotNull { w -> rx(" $w ").find(t)?.let { it.range.last } }.minOrNull()
            ?: FIRST.find(t)?.let { it.range.last } ?: com.optionslab.ira.Heard.nameSlip(t)
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
     * An answer as it is spoken: the first [sentences] sentences (never a safety verdict or warning cut - [Aloud.keep]), the rupee sign and "Rs" read as rupees. The full answer
     * stays on screen with the facts it was built from.
     */
    /** "Rs" or "₹" before a figure: a sign, then digits with their grouping commas, never a comma after the last digit. */
    private val RUPEE_FIGURE = rx("(?:Rs|₹)\\s?([+-]?\\d(?:[\\d,]*\\d)?(?:\\.\\d+)?)")

    fun spoken(text: String, sentences: Int = 3): String {
        val parts = rx("(?<=[.!?])\\s+").split(text.trim()).filter { it.isNotBlank() }
        // Never a safety verdict or warning cut ([Aloud.keep]).
        return Aloud.keep(parts, sentences).joinToString(" ")
            // The figure ends on a digit (Voice, round 26): "Rs 1,234, mostly brokerage" was said "1,234, rupees mostly" and
            // "STT Rs 1,02,345, exchange" kept its comma glued on, so it was never said in lakh.
            .replace(RUPEE_FIGURE, "$1 rupees")
            .replace("+", "plus ").replace(rx("(^|\\s)-(?=\\d)"), "$1minus ")
    }

    /**
     * What is said, in the pieces it is handed to the voice (Boss, 5 Oct: speed): the first sentence alone, the rest
     * queued behind it in one piece, so the first word is heard as soon as the first sentence is made into sound - not
     * after the whole answer is. A short answer stays whole (two pieces only add a pause), and a very short first
     * sentence ("Boss, yes.") is kept with the next. Every word is kept, in order.
     */
    fun pieces(text: String): List<String> {
        val t = text.trim()
        if (t.length < WHOLE_UNDER) return listOf(t)
        val parts = rx("(?<=[.!?])\\s+").split(t).filter { it.isNotBlank() }
        var first = 0
        var head = ""
        while (first < parts.size && head.length < FIRST_AT_LEAST) { head = if (head.isEmpty()) parts[first] else "$head ${parts[first]}"; first++ }
        val rest = parts.drop(first).joinToString(" ")
        return if (rest.isEmpty()) listOf(t) else listOf(head, rest)
    }

    /** Shorter than this, an answer is said in one piece. */
    const val WHOLE_UNDER = 60
    /** The first piece holds at least this many characters. */
    const val FIRST_AT_LEAST = 12

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
        // "No, problem" / "No. Problem.": a "no" said on its own and then something else - not the idiom. The marks are
        // gone once the words are read, so it is caught here and read as the no it starts with (the safe side).
        val split = SPLIT_IDIOM.containsMatchIn(text.lowercase())
        val t = " " + text.lowercase().replace(rx("[^a-z ]"), " ").replace(rx("\\s+"), " ").trim() + " "
        if (t.isBlank()) return null
        // "Why not", "no problem": a yes said with a no word in it (voice round 25) - it used to cancel the request. Read
        // as a yes only when nothing else is said, or what else is said is itself a clear yes; with any other words
        // ("why not the other one") it is unclear, and with a no ("no problem, leave it") a no. Unclear is never a yes.
        if (!split && YES_IDIOM.containsMatchIn(t)) {
            val rest = t.replace(YES_IDIOM, " ").replace(ANSWER_EXTRA, " ").replace(rx("\\s+"), " ").trim()
            if (rest.isEmpty()) return true
            val e = english(rest); val h = Hinglish.yesNo(rest)
            if (e == false || h == false) return false
            // Boss doing it himself ("no problem, I will do it myself", "no worries, I'll take it from here") is not a yes
            // to Jarvis doing it: only bare yes words may follow the idiom; anything else is unclear, never a yes.
            if (SELF.containsMatchIn(" $rest ")) return null
            return if (" $rest ".replace(BARE_YES, " ").isBlank()) true else null
        }
        if (rx(" (no+|nope|nah|not|not now|don t|dont|do not|reject|rejected|cancel|skip|leave it|stop|wait|never|negative|abort|hold off|hold on|decline|declined|deny|denied|later) ").containsMatchIn(t)) return false
        if (rx(" (yes|yeah|yep|yup|sure|approve|approved|confirm|confirmed|go ahead|go for it|do it|please do|place it|buy it|take it|ok|okay|alright|all right|affirmative|positive|absolutely|definitely|certainly|of course|correct) ").containsMatchIn(t)) return true
        return null
    }

    /** A yes said with a no word in it ([english]). */
    private val YES_IDIOM = rx("(?<= )(why not|no problem|not a problem|no worries|no issues?)(?= )")
    /** The idiom broken by a mark ("no, problem", "No. Problem."): a no and then other words ([english]). */
    private val SPLIT_IDIOM = rx("\\b(why|no|not a)\\s*[^a-z0-9\\s']+\\s*(not|problem|worries|issues?)\\b")
    /** Boss as the one who acts ("I will", "myself", "main khud"): after a yes idiom, never read as a yes ([english]). */
    private val SELF = rx(" (i|i ll|ill|i will|i m|im|i am|myself|me|mine|my|manually|main|mai|mein|khud|apne aap) ")
    /** The only words that may follow a yes idiom and keep it a yes ([english]). */
    private val BARE_YES = rx("(?<= )(yes|yeah|yep|yup|sure|ok|okay|alright|all right|go ahead|go for it|please do|do it|haan|haa|han|ji haan|bilkul|zaroor|zarur|theek hai|thik hai|kar do|kardo|kar dijiye|kar dijie|kar dena|chalo|chalega|ho jaye)(?= )")
    /** Words that only address or soften ("Jarvis", "Boss", "please"): nothing to read in a yes or a no. */
    private val ANSWER_EXTRA = rx("(?<= )(jarvis|boss|sir|please|yaar|bhai|ji|then|so|well|oh|ah|um|uh|hmm)(?= )")

    private fun words(s: String) = s.lowercase().replace(rx("[^a-z0-9 ]"), " ").split(rx("\\s+")).filter { it.length > 1 }

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
