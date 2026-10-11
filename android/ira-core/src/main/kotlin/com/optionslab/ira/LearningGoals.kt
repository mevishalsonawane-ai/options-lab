package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * What Jarvis tries to learn next (Boss, 11 Oct: "enhance the self improvement" - active learning). Each night after his
 * learning pass ([AgentLessons]) the lessons are ranked by where the evidence is thinnest but worth the most: a strategy, a
 * specialist, the trade manager or an order-flow pattern with few settled cases but a large possible rupee effect, or a
 * lesson stuck at "building" whose time splits still disagree. At most [MAX_GOALS] goals, each in plain words with its
 * numbers ("Need 18 more Liquidity 15+5 trades to judge the OI specialist").
 *
 *  - value: the rupees at stake once it is judged - the mean rupee effect a case so far (absolute) times the cases it takes
 *    to judge it ([Goal.target]);
 *  - thinness: the share of those cases still missing ([Goal.need] / [Goal.target]);
 *  - score = value × thinness; below [MIN_VALUE_RS] of value a lesson is not worth a goal. Ties by id (deterministic).
 *
 * Goals steer RECORDING only ([plan]): the order flow's shadow log is kept longer for a goal's family (within its storage
 * cap, [RecordingPlan.MAX_SHADOW_BYTES]) and the move recorder's files of a goal's index are the last trimmed. A goal never
 * enters, exits, sizes, arms, un-mutes or switches anything ([safe]). Pure.
 */
object LearningGoals {
    const val MAX_GOALS = 3
    /** A goal is worth setting only when this many rupees or more hang on it. */
    const val MIN_VALUE_RS = 500.0
    /** A lesson that has the cases but is still building: half as many again (at least [AgentLessons.BUILDING_N]) to settle it. */
    const val STUCK_EXTRA_SHARE = 0.5
    /** Goals met that are remembered (the weekly report counts them). */
    const val MET_KEEP = 30

    /** The recording a goal steers (never trading). */
    enum class Record(val words: String) { SHADOW("the order flow's shadow log"), MOVES("the move recorder") }

    data class Goal(
        /** The lesson it is about ([AgentLessons.Lesson.id]). */
        val id: String,
        val topic: AgentLessons.Topic,
        val text: String,
        /** Cases so far, cases it takes to judge it, and the cases still missing. */
        val have: Int, val target: Int, val need: Int,
        /** The rupees at stake (see the object's notes) and the score it ranked by. */
        val valueRs: Double, val score: Double,
        val record: Record,
        /** The family it steers recording for (a strategy key, a specialist's family) and the index, when one is known. */
        val family: String?, val instrument: String?,
        /** The cases it had when first set and the day it was first set (its progress is [have] - [startN]). */
        val startN: Int, val since: LocalDate,
    ) {
        val progress: Int get() = (have - startN).coerceAtLeast(0)
    }

    data class Met(val id: String, val text: String, val on: LocalDate)

    data class Book(val goals: List<Goal> = emptyList(), val day: LocalDate? = null, val met: List<Met> = emptyList())

    private fun prefix(id: String) = id.substringBefore(':')

    /** The prefixes a goal can come from: rupee lessons on what the bots, the manager and the order flow did. */
    private val PREFIXES = setOf("strat", "spec", "mgr", "flow", "trap", "tod", "regime", "bots", "pause")

    /** For order-flow lessons: the index each was seen on most, from the shadow log's signals (trap flags and strategy keys). */
    fun instruments(signals: List<FlowShadow.Signal>): Map<String, String> {
        val counts = HashMap<String, HashMap<String, Int>>()
        fun add(id: String, und: String) { if (und.isNotBlank()) counts.getOrPut(id) { HashMap() }.merge(und.uppercase(Locale.ENGLISH), 1, Int::plus) }
        for (s in signals) {
            if (s.live) continue
            for (f in s.flags) add(AgentLessons.norm("trap:$f"), s.underlying)
            add(AgentLessons.norm("flow:${s.key}:with"), s.underlying); add(AgentLessons.norm("flow:${s.key}:against"), s.underlying)
        }
        return counts.mapValues { (_, m) -> m.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first().key }
    }

    /** The words of a goal for lesson [l] needing [need] more cases (on [instrument] when known). */
    fun words(l: AgentLessons.Lesson, need: Int, instrument: String?): String {
        val on = instrument?.let { " on $it" } ?: ""
        return when (prefix(l.id)) {
            "spec" -> {
                val who = l.name.substringBefore(" on ").removePrefix("The ").trim()
                val fam = l.name.substringAfter(" on ", "").trim()
                "Need $need more ${fam.ifEmpty { "managed" }} trades to judge the $who"
            }
            "mgr" -> "Need $need more managed ${l.name.substringAfter(" on ", "").trim().ifEmpty { "strategy" }} trades to judge the trade manager"
            "strat" -> "Need $need more ${l.name} trades to judge it"
            "tod", "regime" -> "Need $need more trades to judge: ${l.name}"
            "trap" -> "Order flow: record $need more trap events$on (${l.name.substringAfter('"', "").substringBefore('"').ifEmpty { "trap flag" }})"
            "flow" -> "Order flow: record $need more ${l.name.replaceFirstChar { it.lowercase() }}$on"
            "bots" -> "Findings: record $need more ${l.name.replaceFirstChar { it.lowercase() }}"
            "pause" -> "Market brain: record $need more ${l.name.replaceFirstChar { it.lowercase() }}"
            else -> "Need $need more cases to judge: ${l.name}"
        }
    }

    /** The family a lesson's recording is kept for ("spec:pine:oi" -> "pine", "flow:liquidity:BANKNIFTY:with" -> "liquidity:banknifty"). */
    fun familyOf(id: String): String? = when (prefix(id)) {
        "spec", "mgr" -> id.split(':').getOrNull(1)
        "flow" -> id.removePrefix("flow:").substringBeforeLast(':')
        "strat", "tod", "regime" -> id.split(':').getOrNull(1)
        else -> null
    }

    /** One lesson as a candidate goal, or null when it is judged already, is not about rupees, or is worth too little. */
    fun candidate(l: AgentLessons.Lesson, instrument: String?, day: LocalDate, old: Goal? = null): Goal? {
        if (l.status == AgentLessons.Status.RETIRED || l.kind != AgentLessons.Kind.RUPEES || prefix(l.id) !in PREFIXES) return null
        if (l.confidence == AgentLessons.Confidence.STRONG) return null
        val e = AgentLessons.evidence(l)
        if (e.n <= 0) return null
        val target = if (e.n < AgentLessons.STRONG_N) AgentLessons.STRONG_N
            else e.n + maxOf(AgentLessons.BUILDING_N, ceil(e.n * STUCK_EXTRA_SHARE).toInt())
        val need = target - e.n
        val value = abs(e.rs) / e.n * target
        if (value < MIN_VALUE_RS || need <= 0) return null
        val score = value * need / target
        val inst = instrument?.takeIf { prefix(l.id) == "trap" || prefix(l.id) == "flow" }
        val record = if (inst != null && inst in MoveEvents.INDICES && prefix(l.id) == "trap") Record.MOVES else Record.SHADOW
        return Goal(l.id, l.topic, words(l, need, inst), e.n, target, need, value, score, record, familyOf(l.id), inst,
            old?.startN ?: e.n, old?.since ?: day)
    }

    /** All candidates from [lessons] ranked: score, then id. */
    fun ranked(lessons: List<AgentLessons.Lesson>, instruments: Map<String, String>, day: LocalDate, old: List<Goal> = emptyList()): List<Goal> {
        val before = old.associateBy { it.id }
        return lessons.mapNotNull { candidate(it, instruments[it.id], day, before[it.id]) }
            .sortedWith(compareByDescending<Goal> { it.score }.thenBy { it.id })
    }

    /**
     * The night's goals on [day] from [lessons] (after the learning pass): the top [MAX_GOALS]. A goal kept from [old] keeps
     * its start; one that left the list because its lesson is now strong (or has its cases) is remembered as met.
     */
    fun next(old: Book, lessons: List<AgentLessons.Lesson>, instruments: Map<String, String>, day: LocalDate): Book {
        val goals = ranked(lessons, instruments, day, old.goals).take(MAX_GOALS)
        val byId = lessons.associateBy { it.id }
        val met = ArrayList(old.met)
        for (g in old.goals) {
            if (goals.any { it.id == g.id }) continue
            val l = byId[g.id] ?: continue
            val done = l.confidence == AgentLessons.Confidence.STRONG || AgentLessons.evidence(l).n >= g.target
            if (done && met.none { it.id == g.id && !it.on.isBefore(g.since) }) met += Met(g.id, g.text, day)
        }
        return Book(goals, day, met.takeLast(MET_KEEP))
    }

    // ---- what it steers ---------------------------------------------------------------------------------------------------

    /**
     * What the goals steer - recording only: the order flow's shadow log's size before it rotates (raised from
     * [BASE_SHADOW_BYTES] to [MAX_SHADOW_BYTES] while a goal needs it), the families whose shadow lines matter now, and the
     * indices whose move recordings are trimmed last. Nothing here can trade.
     */
    data class RecordingPlan(val shadowLogBytes: Long = BASE_SHADOW_BYTES, val families: Set<String> = emptySet(), val indices: Set<String> = emptySet()) {
        companion object {
            const val BASE_SHADOW_BYTES = 4L * 1024 * 1024
            const val MAX_SHADOW_BYTES = 8L * 1024 * 1024
        }
    }

    fun plan(goals: List<Goal>): RecordingPlan {
        if (goals.isEmpty()) return RecordingPlan()
        val shadow = goals.any { it.record == Record.SHADOW || it.record == Record.MOVES }
        return RecordingPlan(if (shadow) RecordingPlan.MAX_SHADOW_BYTES else RecordingPlan.BASE_SHADOW_BYTES,
            goals.mapNotNull { it.family }.toSortedSet(), goals.mapNotNull { it.instrument?.takeIf { i -> i in MoveEvents.INDICES } }.toSortedSet())
    }

    private val TRADING = Regex("(?i)\\b(buy|sell|enter|entry|exit|arm|disarm|live|lots?|size|unmute|un-mute|resume|place|order|pin)\\b")

    /** A goal says and does recording only: no trading word, a plan within its caps. */
    fun safe(g: Goal): Boolean = !TRADING.containsMatchIn(g.text.replace(Regex("(?i)order flow|trade manager|trades?\\b"), "")) && g.record in Record.entries
    fun safe(p: RecordingPlan): Boolean = p.shadowLogBytes in RecordingPlan.BASE_SHADOW_BYTES..RecordingPlan.MAX_SHADOW_BYTES

    // ---- words --------------------------------------------------------------------------------------------------------------

    fun line(g: Goal): String = "${g.text} (${g.have} of ${g.target} so far; about ${AgentLessons.rs(g.valueRs).removePrefix("+")} at stake)"

    /** Said for the goals on a locked phone (G6-1): each carries the bots' rupees at stake. */
    const val LOCKED = "Unlock the phone for my learning goals, Boss: they carry the bots' rupees."

    /** "What are you trying to learn?" ([locked]: the phone is locked - how many only). */
    fun say(b: Book, locked: Boolean = false): String {
        if (b.goals.isEmpty()) return "Nothing in particular just now, Boss: no lesson is both thin on evidence and worth at least " +
            "${AgentLessons.rs(MIN_VALUE_RS).removePrefix("+")}. I set my goals each evening after I study the agents."
        if (locked) return "I have ${b.goals.size} learning goal${if (b.goals.size == 1) "" else "s"} just now. $LOCKED"
        return "What I'm trying to learn next, Boss:\n" + b.goals.joinToString("\n") { "• " + line(it) } +
            "\nI only record more for these (the order flow's shadow log and the move recorder, within their storage caps); a goal never places, changes or sizes a trade."
    }

    private const val LEAD = "^ (hey |ok |okay )?(jarvis )?(so )?(please )?(tell me )?"
    private const val TAIL = "( please| boss| jarvis| now| next| these days| lately)* $"
    private val ASK = Regex(LEAD + "(what are you (trying|hoping|aiming|working) to learn|what (do|would) you (want|like|need) to learn|" +
        "what (will|are) you (going to )?learn(ing)? next|what are your learning goals|your learning goals|what are you studying|" +
        "what do you need more (data|evidence|cases) (on|for|about)|where (is|s) your evidence thin(nest)?)" + TAIL)

    fun asked(text: String): Boolean = ASK.containsMatchIn(Spaced.joined(text))

    // ---- the file -------------------------------------------------------------------------------------------------------------

    private fun clean(s: String) = s.replace('|', '/').replace('\n', ' ')

    fun encode(b: Book): String = buildString {
        append("G1|").append(b.day ?: "").append('\n')
        for (g in b.goals) append("g|${g.id}|${g.topic.name}|${clean(g.text)}|${g.have}|${g.target}|${g.need}|${g.valueRs}|${g.score}|${g.record.name}|" +
            "${g.family ?: ""}|${g.instrument ?: ""}|${g.startN}|${g.since}\n")
        for (m in b.met) append("m|${m.id}|${clean(m.text)}|${m.on}\n")
    }

    fun decode(text: String?): Book {
        if (text.isNullOrBlank()) return Book()
        var day: LocalDate? = null
        val goals = ArrayList<Goal>()
        val met = ArrayList<Met>()
        for (line in text.lineSequence()) runCatching {
            val p = line.split('|')
            when (p[0]) {
                "G1" -> day = p[1].takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
                "g" -> goals += Goal(p[1], AgentLessons.Topic.valueOf(p[2]), p[3], p[4].toInt(), p[5].toInt(), p[6].toInt(), p[7].toDouble(), p[8].toDouble(),
                    Record.valueOf(p[9]), p[10].ifEmpty { null }, p[11].ifEmpty { null }, p[12].toInt(), LocalDate.parse(p[13]))
                "m" -> met += Met(p[1], p[2], LocalDate.parse(p[3]))
            }
        }
        return Book(goals.take(MAX_GOALS), day, met.takeLast(MET_KEEP))
    }
}
