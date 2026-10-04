package com.optionslab.ira

import java.util.Locale

/**
 * What the results teach (part 5 of "an AGI for this app"): from every closed trade - each arm, strategy, Pine script,
 * Jarvis's own and Boss's by hand - the lessons that stand out: something that has turned (it made money, its last
 * trades lose), an hour or a weekday that keeps losing, and losers held much longer than winners. Each lesson names
 * its numbers; the strongest first. Pure.
 */
object Lessons {
    data class Lesson(val owner: String, val text: String, val rupees: Double, val turned: Boolean = false)

    /** Trades of one owner before anything is said about it; trades in one hour or weekday before it is judged. */
    const val MIN_OWNER = 10
    const val MIN_SLICE = 6
    const val RECENT = 10

    fun of(trips: List<Insights.Trip>, max: Int = 5): List<Lesson> {
        val out = ArrayList<Lesson>()
        for ((owner, all) in trips.groupBy { it.owner }) {
            if (all.size < MIN_OWNER) continue
            val ts = all.sortedBy { it.closedAt }
            val who = if (owner.isBlank()) "Your own trades" else owner
            // Turned: it made money before, its last trades lose.
            if (ts.size >= RECENT * 2) {
                val recent = ts.takeLast(RECENT).sumOf { it.net }; val before = ts.dropLast(RECENT).sumOf { it.net }
                if (recent < 0 && before > 0) out += Lesson(owner, "$who has turned: its last $RECENT trades lost ${AppFacts.rs(recent)}, after making ${AppFacts.rs(before)} before.", recent, turned = true)
            }
            // An hour that keeps losing (by entry).
            ts.groupBy { it.openedAt.hour }.filter { it.value.size >= MIN_SLICE }.forEach { (h, l) ->
                val net = l.sumOf { it.net }
                if (net < 0 && l.count { it.net > 0 } * 2 < l.size)
                    out += Lesson(owner, "$who loses when entered between %02d:00 and %02d:00: ${l.size} trades, ${AppFacts.rs(net)}.".format(Locale.ENGLISH, h, h + 1), net)
            }
            // A weekday that keeps losing.
            ts.groupBy { it.openedAt.dayOfWeek }.filter { it.value.size >= MIN_SLICE }.forEach { (d, l) ->
                val net = l.sumOf { it.net }
                if (net < 0 && l.count { it.net > 0 } * 2 < l.size)
                    out += Lesson(owner, "$who loses on ${d.name.lowercase().replaceFirstChar { it.uppercase() }}s: ${l.size} trades, ${AppFacts.rs(net)}.", net)
            }
            // Losers held much longer than winners.
            fun mins(t: Insights.Trip) = java.time.Duration.between(t.openedAt, t.closedAt).toMinutes().toDouble()
            val win = ts.filter { it.net > 0 }; val lose = ts.filter { it.net < 0 }
            if (win.size >= 3 && lose.size >= 3) {
                val w = win.map(::mins).average(); val l = lose.map(::mins).average()
                if (w > 0 && l >= w * 2 && l - w >= 10)
                    out += Lesson(owner, "$who holds losers much longer than winners: %.0f minutes against %.0f - cut them sooner.".format(Locale.ENGLISH, l, w), lose.sumOf { it.net })
            }
        }
        return out.sortedBy { it.rupees }.take(max)
    }

    fun say(lessons: List<Lesson>, trips: Int): String =
        if (lessons.isEmpty()) "Nothing stands out yet from $trips closed trades, Boss: I need about $MIN_OWNER trades of an arm or strategy before I judge it."
        else "What the results teach, Boss (from $trips closed trades): " + lessons.joinToString(" ") { it.text }

    fun asked(text: String): Boolean = Regex("(?i)\\bwhat (have you|did you) learn(ed|t)?\\b|\\blessons?\\b.*\\b(trades?|results?|learn)|\\bwhat (do|does) (my|the) (results|trades) (say|teach|show)").containsMatchIn(text)
}
