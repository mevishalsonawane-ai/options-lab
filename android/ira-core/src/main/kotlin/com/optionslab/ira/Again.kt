package com.optionslab.ira

/**
 * Saying the last answer again, the way Boss needs it (Boss, by ear in a moving market: "say that again slowly",
 * "what was that number?", "dobara dheere bolo"):
 *
 * - "say that again slowly" / "repeat it slower" / "once more, slowly" / "phir se dheere bolo" says the very words of the
 *   last answer again, slower for that one answer only - his pace setting is left as it is ("speak slower" and
 *   "thoda dheere bolo" stay the lasting change, [Command.Kind.PACE_SLOWER]);
 * - "say the last number again" / "what was that number?" / "woh number phir se bolo" / "kitna bola?" says only the
 *   last figure he said, with the few words that tell what it is ("resistance 24,700"), slowly;
 * - "just the numbers" / "repeat the figures" / "sirf numbers batao" says each figure of the last answer with its words.
 *
 * Only ever what was said aloud a moment ago: nothing is worked out again, nothing is done, nothing is kept. A plain
 * "repeat that" stays "tell me more" ([Command.Kind.MORE], the full answer). A repeat of an answer about the account
 * re-checks the lock: on a locked phone it is not said. Pure.
 */
object Again {
    enum class What { SLOW, NUMBER, NUMBERS }

    /** What Boss asked for, and whether it is said slower than his usual pace (a figure asked again always is). */
    data class Ask(val what: What, val slow: Boolean)

    /** The last answer said aloud (its spoken words), whether it may hold the account, and when it was said. */
    data class Last(val text: String, val account: Boolean, val at: Long)

    sealed class Reply {
        /** Said again ([slow]: for this answer only), as an answer ([account] as the answer it repeats). */
        data class Say(val text: String, val slow: Boolean, val account: Boolean) : Reply()
        /** The answer is about the account and the phone is locked. */
        data class Refused(val why: String) : Reply()
        /** Nothing to say again (no answer lately, or no figure in it). */
        data class None(val why: String) : Reply()
    }

    /** How long after an answer it can still be asked for again. */
    const val KEEP_MS = 180_000L

    /** How much slower a slow repeat is than Boss's pace (for that one answer). */
    const val SLOW = 0.8f

    /** At most this many figures for "just the numbers". */
    const val MAX_FIGURES = 5

    /** The speech rate (times the voice style's own) for a repeat: [pace] for a plain one, slower for a slow one. */
    fun rate(pace: Float, slow: Boolean): Float = if (!slow) pace else (pace * SLOW).coerceIn(0.55f, 1f)

    private fun norm(s: String) = " " + s.lowercase().replace(rx("[^a-z0-9 ]"), " ").replace(rx("\\s+"), " ").trim() + " "

    private const val LEAD = "^ ((hey |ok |okay )?jarvis |please |can you |could you |will you |would you |boss |arre |haan |ok |okay |sorry )*"
    private const val TAIL = "( please| boss| jarvis| now| this time| for me)* $"
    private const val SLOWLY = "(more |a bit |a little |little |bit )?(slowly|slower|slow)"
    private const val AGAIN = "(again|once more|one more time|once again)"
    private const val PHIR = "(dobara|dubara|phir se|fir se|phirse|firse|ek baar aur|ek bar aur|ek baar phir|ek bar phir|ek baar phir se|ek bar phir se)"
    private const val DHEERE = "(thoda |thodi |zara )?(dheere|dhire|dheeray|aaram|araam)( se)?"
    private const val BOLO = "( bolo| boliye| batao| bataiye| bata do| sunao| suna do| kaho)?"
    private const val OBJ = "(that|it|this|the answer|your answer|what you said|the last answer|the last thing you said)"
    private const val FIG = "(number|numbers|figure|figures|level|levels|amount|amounts|value|values|price|prices|strike|strikes)"

    private val SLOW_ASK = listOf(
        // "say that again slowly", "repeat it slower", "tell me that again a bit slower", "say it slowly"
        "(say|tell|read)( me)? ($OBJ( $AGAIN)?|$AGAIN) $SLOWLY", "repeat( $OBJ)?( $AGAIN)? $SLOWLY",
        // "again, slowly", "once more slowly", "slowly again", "slower this time once more"
        "$AGAIN $SLOWLY", "$SLOWLY $AGAIN",
        // "dobara dheere bolo", "phir se thoda dheere", "dheere se phir se bolo", "ek baar aur aaram se batao"
        "$PHIR $DHEERE$BOLO", "$DHEERE $PHIR$BOLO",
    ).map { rx(LEAD + it + TAIL) }

    private val NUMBER_ASK = listOf(
        // "say the last number again", "repeat that figure", "tell me the level again", "give me the numbers once more"
        "(say|tell|give|read)( me)? (just |only )?(the|that|those|your|all the) (last )?$FIG $AGAIN( $SLOWLY)?",
        "repeat (just |only )?(the|that|those|your|all the) (last )?$FIG( $AGAIN)?( $SLOWLY)?",
        // "what was that number?", "what was the figure you said?", "which level did you say?"
        "what was (that|the|the last) $FIG( you (just )?(said|told me|mentioned))?",
        "what was that( last)? $FIG again",
        "(what|which) $FIG (was that|did you (just )?(say|tell me|mention)|you (just )?said)",
        // "the last number again", "numbers again please", "just the numbers", "only the figures"
        "(the )?(last )?$FIG $AGAIN", "(just|only) (the )?$FIG( $AGAIN)?",
        // Hindi: "woh number phir se bolo", "kya number tha", "kitna bola", "sirf numbers batao"
        "(wo |woh |vo |voh |ye |yeh )?(last )?$FIG $PHIR$BOLO", "kya (number|figure|level|amount|price|bhav) (tha|bola|bataya)( aapne| tumne)?",
        "kitna (bola|bataya|kaha)( aapne| tumne| tha)?", "kitne (bola|bataya)", "sirf (number|numbers|figure|figures) (bolo|batao|boliye|bataiye)",
    ).map { rx(LEAD + it + TAIL) }

    private val PLURAL = rx(" (numbers|figures|levels|amounts|values|prices|strikes) |^ (just|only) | sirf ")
    private val SLOW_WORD = rx(" (slowly|slower|slow|dheere|dhire|aaram) ")

    /** Is [text] (as heard, the name may still be on it) asking to hear the last answer again in one of these ways? */
    fun read(text: String): Ask? {
        val s = norm(text)
        if (s.isBlank()) return null
        if (NUMBER_ASK.any { it.matches(s) }) return Ask(if (PLURAL.containsMatchIn(s)) What.NUMBERS else What.NUMBER, slow = true)
        if (SLOW_ASK.any { it.matches(s) } && SLOW_WORD.containsMatchIn(s)) return Ask(What.SLOW, slow = true)
        return null
    }

    /** A figure as said aloud: rupees, a sign, digits (grouped, decimal, a clock time), and its unit. */
    private val FIGURE = rx("(?i)(?:(?:rs\\.?|₹)\\s?)?(?:(?:minus|plus)\\s)?(?<![\\w.,])(?:\\d{1,3}(?:,\\d{2,3})+|\\d+)(?:\\.\\d+)?(?::\\d{2})?(?:\\s?(?:percent|points?|lakh crore|lakh|crore|k|rupees|lots?))*(?![\\w])")
    /** Where a clause ends: a pause or sentence mark (never the comma or point inside a figure), a dash, "and", "but". */
    private val BREAK = rx("(?i)[;!?()|·]|[,.:](?=\\s|$)|\\s[-–—]\\s|\\s(?:and|but|while|whereas|so)\\s")
    private val BOSS = rx("(?i),?\\s*\\b${Address.NAME}\\b,?")
    private val CHAT = rx("(?i)\\s*the rest is in the chat\\.?\\s*$")
    /** A clause longer than this is said as its last words before the figure. */
    private const val CLAUSE_WORDS = 7
    private const val BEFORE_WORDS = 4

    /** Each figure in [spoken] with the words that tell what it is, in the order said (one clause once). */
    fun figures(spoken: String): List<String> {
        val text = CHAT.replace(spoken, "")
        val breaks = BREAK.findAll(text).map { it.range }.toList()
        val out = LinkedHashSet<String>()
        for (m in FIGURE.findAll(text)) {
            if (m.value.isBlank()) continue
            val start = breaks.lastOrNull { it.last < m.range.first }?.let { it.last + 1 } ?: 0
            val end = breaks.firstOrNull { it.first > m.range.last }?.first ?: text.length
            val clause = tidy(text.substring(start, end))
            val words = clause.split(' ').filter { it.isNotEmpty() }
            val said = if (words.size <= CLAUSE_WORDS) clause else {
                val head = tidy(text.substring(start, m.range.first)).split(' ').filter { it.isNotEmpty() }.takeLast(BEFORE_WORDS)
                tidy((head + m.value.trim()).joinToString(" "))
            }
            if (said.isNotEmpty() && rx("\\d").containsMatchIn(said)) out += said
        }
        return out.toList()
    }

    private fun tidy(s: String): String = BOSS.replace(s, " ").replace(rx("\\s+"), " ").trim()
        .replace(rx("(?i)^(and|but|so|then|also|while|it s|its)\\s+"), "").trim().trimEnd(',', '.').trim()

    /**
     * What to say for [ask], from [last] (the last answer said aloud) at [now]. A locked phone never repeats an answer
     * that may hold the account.
     */
    fun reply(ask: Ask, last: Last?, now: Long, locked: Boolean): Reply {
        if (last == null || last.text.isBlank() || now - last.at > KEEP_MS || now < last.at)
            return Reply.None(Address.boss("I haven't said anything just now to say again."))
        if (locked && last.account) return Reply.Refused(Address.boss(LockRule.refuse(true, false, true, false)!!))
        return when (ask.what) {
            What.SLOW -> Reply.Say(last.text.trim(), ask.slow, last.account)
            What.NUMBER -> figures(last.text).lastOrNull()?.let { Reply.Say(Address.boss("the last figure was ${lower(it)}."), ask.slow, last.account) }
                ?: Reply.None(Address.boss("there was no figure in what I just said."))
            What.NUMBERS -> figures(last.text).let { f ->
                if (f.isEmpty()) Reply.None(Address.boss("there was no figure in what I just said."))
                else Reply.Say(Address.boss("the figures: " + f.take(MAX_FIGURES).joinToString("; ") { lower(it) } +
                    (if (f.size > MAX_FIGURES) "; and ${f.size - MAX_FIGURES} more in the chat." else ".")), ask.slow, last.account)
            }
        }
    }

    private val NAMES = setOf("Nifty", "BankNifty", "FinNifty", "Sensex", "India", "Gold", "Zerodha", "Paper", "Live", "Pine", "I")

    /** A clause carried into a line: its first letter lower case unless it is a name ("Nifty", "ORB", "VIX"). */
    private fun lower(s: String): String {
        val w = s.substringBefore(' ')
        val keep = w in NAMES || w.length > 1 && w.drop(1).any { it.isUpperCase() }
        return if (keep) s else s.replaceFirstChar { it.lowercase() }
    }
}
