package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.sqrt

/**
 * Self-calibrating judgment (Jarvis learning from his own outcomes): every idea Jarvis had - taken by himself on
 * paper, approved, rejected or lapsed - is kept with the conditions it came in (the index, the time of day, the trend
 * against its side, how dear options were, the kind of idea, call or put) and, each evening, with what it made or would
 * have made on real option prices ([JarvisTrades.OptionSim]). From that record he scores himself per condition and per
 * pair of conditions ("pattern hammer ideas in sideways markets") and becomes MORE CAUTIOUS where his record is poor:
 *
 *  - [Action.SIT_OUT]: the record there is clearly bad - he takes nothing there by himself (he still asks Boss, so the
 *    idea keeps being scored and the condition can earn its way back);
 *  - [Action.SHRINK]: the record there is losing but not yet clearly bad - he takes it at half size, one lot at least.
 *
 * What it learns can ONLY lower Jarvis's own paper risk: a strong record never raises a size, never lowers a bar, never
 * starts anything and never reaches a real order ([lots] is never above what was planned). Recent results count (the
 * last [WINDOW_DAYS] days, at most [RECENT] ideas per condition), so a condition that turns is noticed both ways. Pure.
 */
object SelfCalibration {
    /** Ideas scored in one condition before it is judged; a pair of conditions is a narrower claim and needs more. */
    const val MIN_ONE = 8
    const val MIN_PAIR = 10
    /** Only the last this many days count, and at most the last [RECENT] ideas of a condition. */
    const val WINDOW_DAYS = 90L
    const val RECENT = 30
    /** The prior: as if this many ideas had already come in, half of them working (a few results never decide alone). */
    const val PRIOR = 4.0
    /** How far (in standard errors) the win rate must sit under a half to call a condition clearly bad: stricter for pairs. */
    const val Z_ONE = 1.0
    const val Z_PAIR = 1.28
    /** Conditions named at most, in the review and in an answer. */
    const val SHOW = 3

    /** The conditions one idea came in. [regime]/[ivRank] are null when not known then (that condition is just not judged). */
    data class Conditions(val at: LocalDateTime, val market: Market, val call: Boolean, val kind: String,
                          val regime: Regime.Kind? = null, val ivRank: Double? = null)

    /** One scored idea: its conditions and what it made or would have made (points a unit, after costs). */
    data class Outcome(val c: Conditions, val points: Double)

    enum class Dim { KIND, INDEX, TIME, TREND, OPTIONS, SIDE }

    /** One condition: its kind, a stable [key] (kept between days) and how it is said. */
    data class Tag(val dim: Dim, val key: String, val phrase: String)

    /** The conditions [c] falls in, one per kind of condition known. */
    fun tags(c: Conditions): List<Tag> {
        val out = ArrayList<Tag>()
        out += Tag(Dim.KIND, c.kind, if (c.kind == SoloCalibration.KIND) SoloCalibration.WHO else "${c.kind} ideas")
        out += Tag(Dim.INDEX, c.market.name, "on ${c.market.label}")
        val m = c.at.hour * 60 + c.at.minute
        out += when {
            m < 10 * 60 -> Tag(Dim.TIME, "OPEN", "in the first 45 minutes")
            m < 11 * 60 + 30 -> Tag(Dim.TIME, "MORNING", "in the late morning")
            m < 13 * 60 + 30 -> Tag(Dim.TIME, "MIDDAY", "around midday")
            else -> Tag(Dim.TIME, "AFTERNOON", "in the afternoon")
        }
        c.regime?.let { r ->
            out += when (r) {
                Regime.Kind.UP, Regime.Kind.DOWN -> if ((r == Regime.Kind.UP) == c.call) Tag(Dim.TREND, "WITH", "with the trend")
                    else Tag(Dim.TREND, "AGAINST", "against the trend")
                Regime.Kind.SIDEWAYS -> Tag(Dim.TREND, "SIDEWAYS", "in sideways markets")
                Regime.Kind.VOLATILE -> Tag(Dim.TREND, "VOLATILE", "in volatile markets")
            }
        }
        c.ivRank?.takeIf { it.isFinite() }?.let { v ->
            out += when {
                v >= IvRank.CAREFUL -> Tag(Dim.OPTIONS, "DEAR", "when options are dear")
                v <= 0.25 -> Tag(Dim.OPTIONS, "CHEAP", "when options are cheap")
                else -> Tag(Dim.OPTIONS, "NORMAL", "when options are normally priced")
            }
        }
        out += if (c.call) Tag(Dim.SIDE, "CALL", "buying calls") else Tag(Dim.SIDE, "PUT", "buying puts")
        return out
    }

    /** A condition (one tag) or a pair of conditions, and the record in it. */
    data class Slice(val tags: List<Tag>, val n: Int, val wins: Int, val net: Double,
                     /** Which of the scored ideas it holds (to tell conditions that are really the same ideas). */
                     val members: Set<Int> = emptySet()) {
        val key: String get() = tags.joinToString("&") { "${it.dim}=${it.key}" }
        /** The win rate pulled toward a half by [PRIOR] (a few results never decide alone). */
        val rate: Double get() = (wins + PRIOR / 2) / (n + PRIOR)
        private val se: Double get() = sqrt(rate * (1 - rate) / (n + PRIOR + 1))
        private val z: Double get() = if (tags.size > 1) Z_PAIR else Z_ONE
        private val enough: Boolean get() = n >= (if (tags.size > 1) MIN_PAIR else MIN_ONE)
        /** Clearly bad: losing, and its win rate under a half even allowing for luck. Jarvis sits these out. */
        val poor: Boolean get() = enough && net < 0 && rate + z * se < 0.5
        /** Losing, not yet clearly bad. Jarvis takes these smaller. */
        val weak: Boolean get() = enough && net < 0 && !poor
        /** Making money, its win rate over a half even allowing for luck (said only; it never raises any risk). */
        val strong: Boolean get() = enough && net > 0 && rate - z * se > 0.5

        /** "pattern hammer ideas in sideways markets", "my ideas on Nifty" ([who]: what they are called with no kind named). */
        fun what(who: String = "my ideas"): String {
            val k = tags.firstOrNull { it.dim == Dim.KIND }
            val rest = tags.filter { it.dim != Dim.KIND }.joinToString(" ") { it.phrase }
            return listOfNotNull(k?.phrase ?: who, rest.ifEmpty { null }).joinToString(" ")
        }

        /** "9 ideas, 2 worked, -38.0 points a unit". */
        fun record(noun: String = "idea"): String = "$n $noun${if (n == 1) "" else "s"}, $wins worked, " + "%+.1f points a unit".format(Locale.ENGLISH, net)
    }

    /** Only what still counts: the last [WINDOW_DAYS] days before [today] and today. */
    internal fun recent(outcomes: List<Outcome>, today: LocalDate): List<Outcome> =
        outcomes.filter { it.points.isFinite() && it.c.at.toLocalDate().isAfter(today.minusDays(WINDOW_DAYS)) && !it.c.at.toLocalDate().isAfter(today) }
            .sortedBy { it.c.at }

    /** Every condition and pair of conditions seen in [outcomes], with its record (none judged yet are kept too). */
    fun slices(outcomes: List<Outcome>, today: LocalDate): List<Slice> {
        val os = recent(outcomes, today)
        val by = LinkedHashMap<List<Tag>, ArrayList<Int>>()
        for ((k, o) in os.withIndex()) {
            val t = tags(o.c)
            for (i in t.indices) {
                by.getOrPut(listOf(t[i])) { ArrayList() } += k
                for (j in i + 1 until t.size) by.getOrPut(listOf(t[i], t[j])) { ArrayList() } += k
            }
        }
        return by.map { (tags, ix) ->
            val last = ix.takeLast(RECENT)
            Slice(tags, last.size, last.count { os[it].points > 0 }, last.sumOf { os[it].points }, last.toSet())
        }
    }

    enum class Action { NORMAL, SHRINK, SIT_OUT }

    /** What Jarvis does with an idea he would take by himself, and the record that decided it ([why]: null for normal). */
    data class Judgment(val action: Action, val why: Slice?) {
        /** In words, for when he asks Boss or tells what he took; null when nothing in his record says to be careful. */
        fun text(): String? {
            val s = why ?: return null
            return when (action) {
                Action.SIT_OUT -> "I'm weak on ${s.what()} (${s.record()}), so I don't take those by myself"
                Action.SHRINK -> "my record on ${s.what()} is soft (${s.record()}), so I take those at half size (one lot at least)"
                Action.NORMAL -> null
            }
        }
    }

    /**
     * The idea now, in conditions [now], judged by [outcomes]: [Action.SIT_OUT] if any condition (or pair) it falls in is
     * clearly bad, else [Action.SHRINK] if any is losing, else [Action.NORMAL]. The worst record decides and is named.
     */
    fun judge(outcomes: List<Outcome>, now: Conditions): Judgment {
        val t = tags(now)
        val here = slices(outcomes, now.at.toLocalDate()).filter { s -> t.containsAll(s.tags) }
        // The worst: the lowest win rate, then the biggest loss.
        val order = compareBy<Slice>({ it.rate }, { it.net })
        here.filter { it.poor }.sortedWith(order).firstOrNull()?.let { return Judgment(Action.SIT_OUT, it) }
        here.filter { it.weak }.sortedWith(order).firstOrNull()?.let { return Judgment(Action.SHRINK, it) }
        return Judgment(Action.NORMAL, null)
    }

    /** Lots after the judgment: never more than [planned] (learning only lowers risk); half, one at least, when shrinking. */
    fun lots(planned: Int, action: Action): Int = when (action) {
        Action.NORMAL -> planned
        Action.SHRINK -> minOf(planned, maxOf(1, planned / 2))
        Action.SIT_OUT -> 0
    }.coerceIn(0, maxOf(0, planned))

    /**
     * The clearly bad conditions, worst first, without a pair that only repeats a single condition already named (a
     * bad "pattern hammer" makes every "pattern hammer + X" look bad too).
     */
    fun weakest(outcomes: List<Outcome>, today: LocalDate, n: Int = SHOW): List<Slice> = distinct(slices(outcomes, today).filter { it.poor }
        .sortedWith(compareBy<Slice>({ it.rate }, { it.net })), n)

    fun soft(outcomes: List<Outcome>, today: LocalDate, n: Int = SHOW): List<Slice> {
        // A losing condition whose ideas all sit inside a clearly bad one adds nothing.
        val bad = slices(outcomes, today).filter { it.poor }
        return distinct(slices(outcomes, today).filter { s -> s.weak && bad.none { b -> b.members.containsAll(s.members) } }.sortedBy { it.net }, n)
    }

    fun strongest(outcomes: List<Outcome>, today: LocalDate, n: Int = SHOW): List<Slice> = distinct(slices(outcomes, today).filter { it.strong }
        .sortedWith(compareByDescending<Slice> { it.rate }.thenByDescending { it.net }), n)

    /**
     * At most [n] slices that say different things: conditions holding exactly the same ideas are said as one ("pattern
     * hammer ideas on BankNifty in sideways markets"), and a condition that only narrows or widens one already named is left out.
     */
    private fun distinct(sorted: List<Slice>, n: Int): List<Slice> {
        val out = ArrayList<Slice>()
        for (s in sorted) {
            val same = out.indexOfFirst { it.members.isNotEmpty() && it.members == s.members }
            if (same >= 0) { out[same] = out[same].let { o -> o.copy(tags = (o.tags + s.tags.filter { it !in o.tags }).sortedBy { it.dim.ordinal }) }; continue }
            if (out.size >= n) continue
            if (out.any { o -> o.tags.all { it in s.tags } || s.tags.all { it in o.tags } }) continue
            out += s
        }
        return out
    }

    /** The keys of the clearly bad conditions now: kept each evening so the next review can tell what changed. */
    fun sitOutKeys(outcomes: List<Outcome>, today: LocalDate): Set<String> = slices(outcomes, today).filter { it.poor }.map { it.key }.toSet()

    /**
     * For the evening self-review: the conditions he now sits out ("I'm worse at X in Y; I'll sit those out"), the ones he
     * takes smaller, and those that earned their way back since [before] (yesterday's [sitOutKeys]; null: not known).
     */
    fun review(outcomes: List<Outcome>, today: LocalDate, before: Set<String>? = null): List<String> {
        val out = ArrayList<String>()
        val all = slices(outcomes, today).associateBy { it.key }
        val bad = weakest(outcomes, today)
        if (bad.isNotEmpty()) out += "I'm worse at " + bad.joinToString("; and at ") { "${it.what()} (${it.record()})" } + " - I'll sit those out"
        val soft = soft(outcomes, today, 2)
        if (soft.isNotEmpty()) out += "I take " + soft.joinToString(" and ") { it.what() } + " at half size while they lose"
        if (before != null) {
            val back = before.mapNotNull { k -> all[k]?.takeIf { !it.poor } }.take(2)
            if (back.isNotEmpty()) out += "my record on " + back.joinToString(" and ") { it.what() } + " is no longer clearly bad, so I may take those by myself again"
        }
        return out
    }

    /** "Where are you weakest / strongest?", from his own scored ideas. */
    fun say(outcomes: List<Outcome>, today: LocalDate): String {
        val n = recent(outcomes, today).size
        if (n < MIN_ONE) return "I've scored only $n of my own ideas on real option prices in the last $WINDOW_DAYS days, Boss: " +
            "I need about $MIN_ONE in a condition before I judge myself there."
        val bad = weakest(outcomes, today); val soft = soft(outcomes, today, 2); val good = strongest(outcomes, today)
        val parts = ArrayList<String>()
        parts += if (bad.isEmpty() && soft.isEmpty()) "Nowhere is my record clearly bad yet."
            else "Weakest: " + (bad.map { "${it.what()} (${it.record()}) - I sit those out" } + soft.map { "${it.what()} (${it.record()}) - I take those at half size" }).joinToString("; ") + "."
        parts += if (good.isEmpty()) "Nowhere is my record clearly good yet either."
            else "Strongest: " + good.joinToString("; ") { "${it.what()} (${it.record()})" } + ". A good record never makes me take more: learning only makes me more careful."
        return "From $n of my ideas scored on real option prices (last $WINDOW_DAYS days), Boss. " + parts.joinToString(" ")
    }

    fun asked(text: String): Boolean = rx("(?i)\\bwhere are you (weak|strong|bad|good|worst|best)(est)?\\b|\\bwhat are you (bad|good|worst|best|weak|strong) at\\b|" +
        "\\byour (weak|strong) (spots?|points?|areas?)\\b|\\bwhen do you do (badly|worst|best|well)\\b|\\bwhere do you (struggle|lose|do best|do worst)\\b").containsMatchIn(text)

    /** The scored suggestions as outcomes ([JarvisTrades.Suggestion.points] known); the kind is [Preference.kind]. */
    fun of(suggestions: List<JarvisTrades.Suggestion>): List<Outcome> = suggestions.mapNotNull { s ->
        val p = s.points?.takeIf { it.isFinite() } ?: return@mapNotNull null
        Outcome(Conditions(s.at, s.market, s.call, Preference.kind(s.source), s.regime, s.ivRank), p)
    }
}
