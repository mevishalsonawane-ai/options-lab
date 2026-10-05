package com.optionslab.ira

import java.time.Duration
import java.time.LocalDateTime

/**
 * Jarvis noticing which of his market reads Boss asks again within minutes (learning round 13, 2026-10-05): when Boss
 * asks the same kind of market read - the same main topic (the levels, the trend, why it moved, patterns, the news,
 * volatility; [READS]) on the same index or indices (or none named) - again within [MAX_GAP_S] of asking it, with no
 * other question of a kind between, the first answer probably missed what he wanted. Each such re-ask is noted by its
 * KIND only (the topic key, the indices named, when, and how many seconds later; never the words) and set against how
 * often Boss asked that kind at all (the daily kind tally [DoubtTally] that [SelfDoubt] keeps already).
 *
 * A RECORD only: it is shown in the Learnings ledger ([Learnings.Area.AGAIN]) and answers "which of your answers do I
 * ask again?" ([asked]), so Boss can tell Jarvis what was missing or mark an answer wrong. It changes nothing Jarvis
 * does or says otherwise, and nothing learned here acts. How the market is doing ([Topic.OVERVIEW]) is left out: asking
 * a price again a few minutes on is wanting it fresh, not a miss. The same words twice within [MIN_GAP_S] (one
 * utterance heard twice) never count; a command, an order or words not understood have no kind and are passed over
 * (a "what?" between keeps the read open); his account, advice, backtests and terms are not market reads and close it.
 * Only the last [WINDOW_DAYS] days count. Pure: the app keeps the log and the last read (in memory).
 */
object AskedAgain {
    /** Only the last this many days count. */
    const val WINDOW_DAYS = 30L
    /** Asked again this soon (seconds) is a re-ask; later is a fresh question. */
    const val MAX_GAP_S = 300L
    /** Sooner than this (seconds) is the same words heard twice, not Boss asking again. */
    const val MIN_GAP_S = 8L
    /** A kind asked again this many times before the ledger shows it (once is chance). */
    const val MIN_SHOW = 2
    /** Events kept at most. */
    const val KEEP = 300
    /** Kinds named at most. */
    const val SHOW = 3

    /** The market reads: the topics whose answer is a read of the market (in [SelfDoubt]'s keys). */
    val READS: Set<Topic> = setOf(Topic.WHY, Topic.LEVELS, Topic.TREND, Topic.PATTERNS, Topic.NEWS, Topic.VOLATILITY)

    /** One market read asked: its kind ("topic:LEVELS"), the indices named ("market:NIFTY", sorted; empty: none) and when. */
    data class Seen(val kind: String, val markets: List<String>, val at: LocalDateTime)

    /** One re-ask: the kind, the indices named, when, and how many seconds after the first. No words are kept. */
    data class Event(val kind: String, val markets: List<String>, val at: LocalDateTime, val gapS: Int)

    /** The re-asks noted. */
    data class Log(val events: List<Event> = emptyList())

    /** What a question is, for this: a market read, another kind (closes an open read), or nothing (passed over). */
    sealed class Read {
        data class Reading(val kind: String, val markets: List<String>) : Read()
        object Other : Read()
        object None : Read()
    }

    /** How [text] reads here ([Read.None] for a command, an order or words not understood). */
    fun read(text: String): Read = read(runCatching { SelfDoubt.tags(text) }.getOrDefault(emptyList()))

    /** How a question with kinds [tags] ([SelfDoubt.tags], read once by the caller) reads here. */
    fun read(tags: List<SelfDoubt.Tag>): Read {
        val topic = tags.firstOrNull { it.dim == SelfDoubt.Dim.TOPIC } ?: return Read.None
        val t = runCatching { Topic.valueOf(topic.key.substringAfter(':')) }.getOrNull() ?: return Read.Other
        if (t !in READS) return Read.Other
        return Read.Reading(topic.key, tags.filter { it.dim == SelfDoubt.Dim.MARKET }.map { it.key }.sorted())
    }

    /** The result of one question: the log (with a re-ask noted, or as it was) and the read now open (null: none). */
    data class Step(val log: Log, val open: Seen?, val again: Event?)

    /**
     * Boss asked [text] at [at], with [open] the market read asked last (null: none). A re-ask of the same kind on the same
     * indices within the window is noted; the read stays open from this question (a third ask counts against the second).
     */
    fun heard(log: Log, open: Seen?, text: String, at: LocalDateTime): Step {
        return when (val r = read(text)) {
            Read.None -> Step(log, open, null)
            Read.Other -> Step(log, null, null)
            is Read.Reading -> {
                val now = Seen(r.kind, r.markets, at)
                if (open == null || open.kind != r.kind || open.markets != r.markets) return Step(log, now, null)
                val gap = Duration.between(open.at, at).seconds
                if (gap < 0 || gap > MAX_GAP_S) return Step(log, now, null)
                // The same words heard twice: the read stays as first asked.
                if (gap < MIN_GAP_S) return Step(log, open, null)
                val e = Event(r.kind, r.markets, at, gap.toInt())
                val from = at.minusDays(WINDOW_DAYS)
                Step(Log((log.events.filter { it.at.isAfter(from) } + e).takeLast(KEEP)), now, e)
            }
        }
    }

    /** One kind's record: re-asked of asked (asked is never under re-asked: the tally may start late). */
    data class Record(val kind: String, val again: Int, val asked: Int, val medianGapS: Int, val market: String?, val newest: LocalDateTime) {
        val phrase: String get() = SelfDoubt.phraseOf(kind)
        val share: Int get() = if (asked <= 0) 0 else Math.round(100.0 * again / asked).toInt()
        /** "about 2 minutes later" / "about 40 seconds later". */
        fun later(): String = if (medianGapS < 90) "about ${medianGapS} seconds later" else "about ${Math.round(medianGapS / 60.0)} minutes later"
        fun on(): String = market?.let { k -> runCatching { com.optionslab.ira.Market.valueOf(k.substringAfter(':')).label }.getOrNull() }?.let { ", mostly about $it" } ?: ""
        fun say(): String = "you asked again after $again of my $asked $phrase" + on() + ", " + later()
    }

    /** Every kind re-asked in the window up to [now], most re-asked (by share) first. */
    fun records(log: Log, tally: DoubtTally, now: LocalDateTime): List<Record> {
        val from = now.minusDays(WINDOW_DAYS)
        val events = log.events.filter { it.at.isAfter(from) && !it.at.isAfter(now) }
        if (events.isEmpty()) return emptyList()
        val fromDay = from.toLocalDate(); val today = now.toLocalDate()
        val asked = HashMap<String, Int>()
        tally.filterKeys { !it.isBefore(fromDay) && !it.isAfter(today) }.values
            .forEach { m -> m.forEach { (k, n) -> asked[k] = (asked[k] ?: 0) + maxOf(0, n) } }
        return events.groupBy { it.kind }.map { (k, es) ->
            val gaps = es.map { it.gapS }.sorted()
            val named = es.flatMap { it.markets }.groupingBy { it }.eachCount()
            // An index named in most of them, else none said.
            val market = named.maxByOrNull { it.value }?.takeIf { it.value * 2 > es.size }?.key
            Record(k, es.size, maxOf(es.size, asked[k] ?: 0), gaps[(gaps.size - 1) / 2], market, es.maxOf { it.at })
        }.sortedWith(compareByDescending<Record> { it.again.toDouble() / maxOf(1, it.asked) }.thenByDescending { it.again })
    }

    /** The kinds shown in the ledger (asked again at least [MIN_SHOW] times). */
    fun shown(log: Log, tally: DoubtTally, now: LocalDateTime): List<Record> = records(log, tally, now).filter { it.again >= MIN_SHOW }

    // ---- asked -----------------------------------------------------------------------------------------------------

    private fun norm(text: String) = " " + spacedWords(text.lowercase().replace("'", "").replace("’", "")) + " "

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(can you |could you |would you )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| lately| these days| this month)* $"
    private const val AGAIN = "(again and again|over and over( again)?|again|twice|a second time|over again|repeatedly)"
    private val ASKED = rx(LEAD + "(which|what) (of )?(your |my )?(answers|replies|market reads|reads) (do|did|have) i (ask|asked|keep asking|have to ask|had to ask|need to ask|needed to ask) (you )?(for )?$AGAIN" + TAIL + "|" +
        LEAD + "what do i (keep asking|ask) (you )?$AGAIN" + TAIL + "|" +
        LEAD + "what (did|have) i (asked|ask|had to ask) (you )?$AGAIN( within minutes)?" + TAIL + "|" +
        LEAD + "(which|what) (of )?(your )?(answers|replies|market reads|reads) (miss|missed|missed the mark|didnt land|did not land)" + TAIL + "|" +
        LEAD + "(which|what) (of )?(your )?(answers|replies|market reads|reads) (do|did) i (re ?ask|repeat)" + TAIL + "|" +
        // "What questions do I repeat?" (routing round 11).
        LEAD + "(which|what) (questions|market questions) (do|did) i (repeat|re ?ask|keep repeating|keep asking)( you)?( $AGAIN)?" + TAIL + "|" +
        LEAD + "(kaun se|kon se|kaunse) (jawab|answers) (main |mai |mein )?(dobara|phir se) (puchta|poochta|puchhta) (hoon|hu|hun)" + TAIL + "|" +
        LEAD + "(main |mai )?kya (dobara|phir se) (puchta|poochta|puchhta) (hoon|hu|hun)" + TAIL)

    /** "Which of your answers do I ask again?" / "what do I keep asking twice?". */
    fun asked(text: String): Boolean = askedKept.of(text) { askedFresh(text) }

    /** The last words read (speed round 10: the hub reads them as said, then again in its order; [Kept], pure). */
    private val askedKept = Kept<Boolean>(64)

    private fun askedFresh(text: String): Boolean = ASKED.containsMatchIn(norm(text))

    const val ONLY_RECORD = "A record only: it changes nothing I do, and nothing I learn acts - though a read you keep asking again for its figure, I say figure first aloud."

    /** "Which of your answers do I ask again?". */
    fun say(log: Log, tally: DoubtTally, now: LocalDateTime): String {
        val all = records(log, tally, now)
        val mins = MAX_GAP_S / 60
        if (all.isEmpty()) return "You haven't asked any of my market reads again within $mins minutes in the last $WINDOW_DAYS days, Boss. " +
            "If you do, I'll note which kind - a sign my first answer missed what you wanted. $ONLY_RECORD"
        val s = all.filter { it.again >= MIN_SHOW }
        if (s.isEmpty()) return "You asked again within $mins minutes after ${all.sumOf { it.again }} of my market reads in the last $WINDOW_DAYS days, Boss - " +
            "never twice of one kind, too few to say one kind misses. $ONLY_RECORD"
        return "Boss, in the last $WINDOW_DAYS days, within $mins minutes: " + s.take(SHOW).joinToString("; ") { "${it.say()} (${it.share}%)" } +
            (if (s.size > SHOW) "; and ${s.size - SHOW} more" else "") +
            ". That may mean my first answer missed what you wanted - tell me what was missing, or say \"that was wrong\". $ONLY_RECORD"
    }

    /** The ledger's line for [r]. */
    fun ledgerWhat(r: Record): String = "${r.phrase}: asked again within ${MAX_GAP_S / 60} minutes"
    fun ledgerWhy(r: Record): String = "${r.say()}, in the last $WINDOW_DAYS days - a sign my first answer missed; a record only, it changes nothing I do"
}
