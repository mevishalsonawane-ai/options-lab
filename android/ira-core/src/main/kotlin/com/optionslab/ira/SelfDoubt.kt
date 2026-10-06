package com.optionslab.ira

import java.time.LocalDate

/** Question kinds asked per day ([SelfDoubt]): day -> (kind key -> count). No words are kept. */
typealias DoubtTally = Map<LocalDate, Map<String, Int>>

/**
 * Jarvis learning which KINDS of answers he gets wrong (Jarvis learning from his own outcomes, round 3): every answer
 * Boss marks wrong ("Jarvis, that was wrong", [Mistakes]) is read back by what it was about - the topic, the index (or
 * none named) and how it was put (Hinglish, a very short question) - and set against how often Boss asked that kind at
 * all (a small tally of question kinds a day, no words kept). Where his answers are marked wrong often enough he adds
 * caution, and only caution:
 *
 *  - [Level.CHECK]: the answer ends with "Check me on this, Boss" and why;
 *  - [Level.ASK]: clearly weak there - he also says first how he read the question ("Did you mean the levels on
 *    Nifty, Boss? Taking it that way.") so a misreading is caught at once.
 *
 * Nothing learned here ever acts: it only adds words to an answer to a question (never to a command or an order), never
 * changes a figure, a size, a bar or an order, and a kind falls back to normal as soon as its record does (the last
 * [WINDOW_DAYS] days only). Pure.
 */
object SelfDoubt {
    /** Only the last this many days count, both marked mistakes and questions asked. */
    const val WINDOW_DAYS = 30L
    /** Marked wrong this many times in a kind before it is doubted at all (one or two slips decide nothing). */
    const val MIN_WRONG = 3
    /** As if this many more answers of the kind had gone unmarked: a few questions never decide alone. */
    const val PRIOR = 5.0
    /** Share of a kind's answers marked wrong (with the prior) to add "check me", and to also ask "did you mean". */
    const val CHECK_RATE = 0.15
    const val ASK_RATE = 0.3
    const val ASK_WRONG = 4
    /** Kinds named at most, in the review and in an answer. */
    const val SHOW = 2

    const val CHECK_ME = "Check me on this, Boss"

    enum class Dim { TOPIC, MARKET, PHRASING }
    enum class Level { NORMAL, CHECK, ASK }

    /** One kind of question: its dimension, a stable [key] (kept in the daily tally) and how it is said. */
    data class Tag(val dim: Dim, val key: String, val phrase: String)


    /** The topic a question is mainly about: the most specific one first ("today's levels" is about levels). */
    private val MAIN = listOf(Topic.WHY, Topic.LEVELS, Topic.TREND, Topic.PATTERNS, Topic.NEWS, Topic.VOLATILITY, Topic.ADVICE,
        Topic.BACKTEST, Topic.EXPLAIN, Topic.TRADE_CHECK, Topic.ACCOUNT, Topic.OVERVIEW)

    private val TOPIC_PHRASE = mapOf(
        Topic.WHY to "why it moved", Topic.LEVELS to "the levels", Topic.TREND to "the trend", Topic.PATTERNS to "patterns",
        Topic.NEWS to "the news", Topic.VOLATILITY to "volatility", Topic.ADVICE to "what to do", Topic.BACKTEST to "backtests",
        Topic.EXPLAIN to "explaining terms", Topic.TRADE_CHECK to "the trade check", Topic.ACCOUNT to "your account",
        Topic.OVERVIEW to "how the market is doing")

    /** The kinds [text] is. None for a command, an order, or words not understood (those are not answers to judge). */
    fun tags(text: String): List<Tag> {
        val q = runCatching { Ask.parse(text) }.getOrNull() ?: return emptyList()
        if (q.command != null || q.order != null || Commands.parse(text) != null) return emptyList()
        if (Topic.ORDER in q.topics || Topic.COMMAND in q.topics) return emptyList()
        val main = MAIN.firstOrNull { it in q.topics } ?: return emptyList()
        val out = ArrayList<Tag>()
        out += Tag(Dim.TOPIC, "topic:${main.name}", "answers on ${TOPIC_PHRASE[main]}")
        val named = Market.mentioned(text)
        if (named.isEmpty()) out += Tag(Dim.PHRASING, "phrasing:NO_INDEX", "answers when you don't name the index")
        else named.forEach { m -> out += Tag(Dim.MARKET, "market:${m.name}", "answers on ${m.label}") }
        if (Hinglish.hasHindi(text)) out += Tag(Dim.PHRASING, "phrasing:HINGLISH", "answers to Hinglish questions")
        if (Corrections.normalize(text).split(" ").count { it.isNotBlank() } in 1..3) out += Tag(Dim.PHRASING, "phrasing:SHORT", "answers to very short questions")
        return out
    }

    /** [tally] with [text]'s kinds counted on [day], days older than the window dropped. */
    fun count(tally: DoubtTally, day: LocalDate, text: String): DoubtTally {
        // "Why do you put the levels first?", "don't put the levels first": about Jarvis's order, never a question about the
        // levels - not tallied, so an undo never counts toward what it undoes ([LeadPart], [FigureFirst]).
        val ours = runCatching { LeadPart.asked(text) != null || FigureFirst.asked(text) != null || NextAsk.asked(text) != null ||
            MoreAfter.asked(text) != null || SmallTrades.asked(text) != null || DayIndex.asked(text) != null ||
            CheckTimes.asked(text) != null || CondNeeds.asked(text) != null }.getOrDefault(false)
        val keys = if (ours) emptyList() else tags(text).map { it.key }
        val kept = tally.filterKeys { !it.isBefore(day.minusDays(WINDOW_DAYS)) }
        if (keys.isEmpty()) return kept
        val today = HashMap(kept[day].orEmpty())
        keys.forEach { today[it] = (today[it] ?: 0) + 1 }
        return kept + (day to today)
    }

    /** One kind's record: answers marked wrong of those asked (asked is never under wrong: the tally may start late). */
    data class Record(val tag: Tag, val wrong: Int, val asked: Int) {
        val rate: Double get() = wrong / (asked + PRIOR)
        val level: Level get() = when {
            wrong >= ASK_WRONG && rate >= ASK_RATE -> Level.ASK
            wrong >= MIN_WRONG && rate >= CHECK_RATE -> Level.CHECK
            else -> Level.NORMAL
        }
        fun say(): String = "you marked $wrong of my ${asked} ${tag.phrase} wrong"
    }

    private fun inWindow(d: LocalDate, today: LocalDate) = !d.isAfter(today) && d.isAfter(today.minusDays(WINDOW_DAYS))

    /** Every kind with a mistake marked in the window, worst first. */
    fun records(mistakes: List<Mistakes.Entry>, tally: DoubtTally, today: LocalDate): List<Record> {
        val wrong = LinkedHashMap<String, Pair<Tag, Int>>()
        mistakes.filter { inWindow(it.at.toLocalDate(), today) }.forEach { e ->
            tags(e.said).forEach { t -> wrong[t.key] = t to ((wrong[t.key]?.second ?: 0) + 1) }
        }
        val asked = HashMap<String, Int>()
        tally.filterKeys { inWindow(it, today) }.values.forEach { m -> m.forEach { (k, n) -> asked[k] = (asked[k] ?: 0) + maxOf(0, n) } }
        return wrong.values.map { (t, w) -> Record(t, w, maxOf(w, asked[t.key] ?: 0)) }
            .sortedWith(compareByDescending<Record> { it.level }.thenByDescending { it.rate }.thenByDescending { it.wrong })
    }

    /** The kinds he now adds caution to, worst first. */
    fun weak(mistakes: List<Mistakes.Entry>, tally: DoubtTally, today: LocalDate): List<Record> =
        records(mistakes, tally, today).filter { it.level != Level.NORMAL }

    /** The stable keys of [weak] (for the review's "since yesterday"). */
    fun weakKeys(mistakes: List<Mistakes.Entry>, tally: DoubtTally, today: LocalDate): Set<String> = weak(mistakes, tally, today).map { it.tag.key }.toSet()

    /** The caution for one question: the worst kind it falls in, or [Level.NORMAL] with no record. */
    data class Caution(val level: Level, val record: Record?, val reading: String?) {
        /** Said before the answer: how he read the question ([Level.ASK] only). */
        fun before(): String? = if (level == Level.ASK && reading != null) "Did you mean $reading, Boss? Taking it that way." else null
        /** Said after the answer. */
        fun after(): String? = record?.takeIf { level != Level.NORMAL }?.let { "$CHECK_ME - ${it.say()} lately." }
        /** [answer] with the caution around it (unchanged when there is none, or it is already there). */
        fun wrap(answer: String): String {
            if (level == Level.NORMAL || answer.contains(CHECK_ME)) return answer
            return listOfNotNull(before(), answer.trim(), after()).joinToString(" ")
        }
    }

    val NONE = Caution(Level.NORMAL, null, null)

    /** How careful to be answering [text] now. Never for anything that would act. */
    fun judge(text: String, mistakes: List<Mistakes.Entry>, tally: DoubtTally, today: LocalDate): Caution =
        judge(text, weak(mistakes, tally, today))

    /** The same, from the [weak] kinds worked out already (the app keeps them a while: each question is not a re-read). */
    fun judge(text: String, weak: List<Record>): Caution {
        if (weak.isEmpty()) return NONE
        val keys = tags(text).map { it.key }.toSet()
        if (keys.isEmpty()) return NONE
        val worst = weak.filter { it.level != Level.NORMAL }.firstOrNull { it.tag.key in keys } ?: return NONE
        return Caution(worst.level, worst, reading(text))
    }

    /** How the question was read, in words ("the levels on Nifty"), or null when it has no main topic. */
    fun reading(text: String): String? {
        val q = runCatching { Ask.parse(text) }.getOrNull() ?: return null
        val main = MAIN.firstOrNull { it in q.topics } ?: return null
        val named = Market.mentioned(text)
        return TOPIC_PHRASE[main] + if (named.isEmpty()) "" else " on " + named.joinToString(" and ") { it.label }
    }

    /**
     * For the evening self-review: the kinds he now flags, and those whose mistakes stopped since [before] (yesterday's
     * [weakKeys]; null: not known).
     */
    fun review(mistakes: List<Mistakes.Entry>, tally: DoubtTally, today: LocalDate, before: Set<String>? = null): List<String> {
        val out = ArrayList<String>()
        val weak = weak(mistakes, tally, today)
        val ask = weak.filter { it.level == Level.ASK }.take(SHOW)
        val check = weak.filter { it.level == Level.CHECK }.take(SHOW)
        if (ask.isNotEmpty()) out += ask.joinToString("; and ") { it.say() } + " this month, so on those I first say how I read the question, and ask you to check me"
        if (check.isNotEmpty()) out += check.joinToString("; and ") { it.say() } + " this month, so I end those with \"check me on this\""
        if (before != null) {
            val now = weak.map { it.tag.key }.toSet()
            val all = records(mistakes, tally, today).associateBy { it.tag.key }
            val back = before.filter { it !in now }.take(SHOW).map { k -> all[k]?.tag?.phrase ?: phraseOf(k) }
            if (back.isNotEmpty()) out += "I no longer ask you to check my " + back.joinToString(" and ") + " - fewer marked wrong lately"
        }
        return out
    }

    /** "Where are you weakest?": his answers' side, or null when no kind is doubted. */
    fun say(mistakes: List<Mistakes.Entry>, tally: DoubtTally, today: LocalDate): String? {
        val weak = weak(mistakes, tally, today).take(SHOW + 1)
        if (weak.isEmpty()) return null
        return "In my answers, Boss: " + weak.joinToString("; ") { it.say() } + " in the last $WINDOW_DAYS days, " +
            "so I ask you to check me there. That only makes me more careful - it never changes a figure or does anything."
    }

    /** A key's words when its kind no longer has a mistake in the window. */
    internal fun phraseOf(key: String): String {
        val (dim, name) = key.substringBefore(':') to key.substringAfter(':')
        return when (dim) {
            "topic" -> runCatching { "answers on " + TOPIC_PHRASE[Topic.valueOf(name)] }.getOrNull()
            "market" -> runCatching { "answers on " + Market.valueOf(name).label }.getOrNull()
            else -> when (name) { "NO_INDEX" -> "answers when you don't name the index"; "HINGLISH" -> "answers to Hinglish questions"
                "SHORT" -> "answers to very short questions"; else -> null }
        } ?: "answers of that kind"
    }
}
