package com.optionslab.ira

import java.time.LocalDate

/**
 * "What can I ask you?" (voice, round 15): a short spoken tour - five questions worth asking right now, matched to the
 * part of the day - before the open (readiness, the news, the week, gaps), in market hours (what matters, the structure,
 * the chain, his limits, his positions), after the close (his journal, why the market moved, his streak) and on a day with
 * no session (next week, his review, what Jarvis learned). The first two of each part are always named; the other three
 * turn with the date, so asking again another day brings new ones. Every question named is one Jarvis answers as said
 * (the tests route each); none acts - the tour only names questions, never answers them, and holds nothing of the
 * account, so it is the same on a locked phone. "What can you do" keeps its full list of areas. Pure.
 */
object Tour {
    enum class Part(val lead: String) {
        PRE_OPEN("Before the open"),
        MARKET("While the market is open"),
        AFTER_CLOSE("After the close"),
        CLOSED("With no session today"),
    }

    /** How many questions are named. */
    const val NAMED = 5

    private const val LEAD = "(?:(?:hey |ok |okay )?jarvis |boss |so |ok |okay |and |please )*"
    private const val END = "(?: jarvis| boss| please| now| right now| today| here| then)* $"
    private val ASKED = rx(
        // "What can I ask you?", "what should I ask now?", "what else can I ask Jarvis?"
        "^ $LEAD(what|wat) (else )?(can|could|should|shall|do|may) i ask( you| jarvis| u)?( about)?$END|" +
        // "What questions can I ask?", "what kind of questions should I ask you?", "which questions can I ask?"
        "^ $LEAD(what|which|what kind of|what sort of|what type of) (questions|things|stuff) (can|could|should|do|may) i ask( you| jarvis)?$END|" +
        // "Give me some ideas of what to ask", "suggest some questions", "any questions I should ask?"
        "^ $LEAD(give me |suggest |any |got any )?(some |a few |few )?(ideas|suggestions|examples|questions) (of |for |on )?(what|questions|things) (to|i (can|could|should)) ask( you| jarvis)?$END|" +
        "^ $LEAD(suggest|give me|tell me) (some |a few |few |good |useful )*(questions|things to ask)( to ask)?( you| jarvis)?$END|" +
        "^ $LEAD(any )?(good |useful )?questions (i should|to) ask( you| jarvis)?$END|" +
        "^ $LEAD(what s|whats|what is) (worth|good) (asking|to ask)( you| jarvis)?$END|" +
        "^ $LEAD(give me a|take me on a|do a) (quick )?tour( of what (i can ask|you can answer))?$END|" +
        // Hinglish: "main kya pooch sakta hoon?", "tumse kya puchu?", "kya poochna chahiye?", "kuch sawal batao"
        "^ $LEAD(main |mai |me |hum )?(tumse |aapse |tujhse |aap se |tum se )?(kya|kaun se sawal|kya sawal) (pooch|puch|poochh|puchh)(u|un|oon|on|hun|hoon|e|ein|en)?( sakta| sakti| sakte)?( hoon| hu| hun| hain| hai)?( kya)?$END|" +
        "^ $LEAD(mujhe )?(tumse |aapse )?kya (poochna|puchna|poochhna) chahiye$END|" +
        "^ $LEAD(kuch )?(sawal|sawaal|questions) (batao|bata do|suggest karo|bolo)$END"
    )

    /** Does [text] ask which questions to ask Jarvis ("what can I ask you?")? Never "what can you do" (his full list). */
    fun asked(text: String): Boolean = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Boolean>(64)

    private fun askedFresh(text: String): Boolean = ASKED.containsMatchIn(words(text))

    private fun words(s: String) = Spaced.words(s)

    /** The part of the day at [minute] (minutes after midnight, IST) on a day that is or is not a [tradingDay]. */
    fun part(minute: Int, tradingDay: Boolean): Part = when {
        !tradingDay -> Part.CLOSED
        minute < 9 * 60 + 15 -> Part.PRE_OPEN
        minute < 15 * 60 + 30 -> Part.MARKET
        else -> Part.AFTER_CLOSE
    }

    /** Each part's questions: the first two always named, the rest in turn by the date. Each is answered as said. */
    internal val QUESTIONS: Map<Part, List<String>> = mapOf(
        Part.PRE_OPEN to listOf("am I ready to trade", "what's the main news today", "what does this week look like",
            "do gap downs usually fill", "what if Nifty opens 1% down", "is this an expiry week", "what have you learned this week"),
        Part.MARKET to listOf("what matters right now", "what's the structure today", "where is the most call writing",
            "how close am I to my limits", "check my positions", "has max pain shifted since morning", "is the low of the day usually in by now"),
        Part.AFTER_CLOSE to listOf("help me journal today", "why did the market move today", "am I on a winning streak",
            "what does next week look like", "what have you learned this week", "what's the main news today", "how has max pain moved today"),
        Part.CLOSED to listOf("what does next week look like", "what have you learned this week", "am I on a winning streak",
            "what's my best weekday", "are Mondays more volatile", "do gap downs usually fill", "what's the main news today"),
    )

    /** The [NAMED] questions for [part] on [today]: two fixed, three turning with the date (never one twice). */
    fun questions(part: Part, today: LocalDate): List<String> {
        val all = QUESTIONS.getValue(part)
        val rest = all.drop(2)
        val from = Math.floorMod(today.toEpochDay(), rest.size.toLong()).toInt()
        return all.take(2) + (rest.indices).map { rest[(from + it) % rest.size] }.take(NAMED - 2)
    }

    /**
     * The tour as said: "Before the open, Boss, five good ones to ask me: "am I ready to trade", ... and "do gap downs
     * usually fill". Ask any of them as they are, or "what can you do" for everything." Two sentences, so all of it is said
     * aloud; no question mark inside a quote (the voice would stop there).
     */
    fun answer(part: Part, today: LocalDate): String {
        val qs = questions(part, today).map { "\"$it\"" }
        val list = qs.dropLast(1).joinToString(", ") + " and " + qs.last()
        return "${part.lead}, Boss, five good ones to ask me: $list. Ask any of them as they are, or \"what can you do\" for everything."
    }
}
