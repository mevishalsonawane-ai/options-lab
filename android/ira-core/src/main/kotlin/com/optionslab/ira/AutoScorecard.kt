package com.optionslab.ira

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * The monthly automation scorecard (S15), made on the 1st for the month before: per system, how many automatic changes it
 * made, how many were kept, reverted by themselves, undone by Boss or still on probation, and their rupee effect against
 * the counterfactual (the same trades with no change: [AutoChange.Entry.effect], measured on the trades that settled after
 * each change); the specialists muted; the strategy lab's promotions (their shadow lead before promotion, as the lab
 * measured it). The health tuner's changes are risk-neutral (how often something non-critical refreshes): counted, no
 * rupees. Facts only; nothing here changes anything. Pure.
 */
object AutoScorecard {
    /** One strategy-lab promotion, as the app hands it over. */
    data class Promotion(val day: LocalDate, val label: String, val moved: String, val lead: Double?, val status: String, val live: Boolean)

    /**
     * One system's month. [effect] null: no rupee measure for it (shadow or speed-only changes). [days]: its figure is the
     * days it changed something, not a count of changes (the health tuner keeps only its days) - said as days, not changes.
     */
    data class System(val name: String, val changes: Int, val kept: Int, val reverted: Int, val undone: Int, val probation: Int, val effect: Double?,
                      val days: Boolean = false)

    /** [healthDays]: the days the health tuner changed a cadence in the month (it keeps no count of its changes). */
    data class Card(val month: YearMonth, val systems: List<System>, val muted: List<String>, val promotions: List<Promotion>, val healthDays: Int) {
        val total: Double get() = systems.mapNotNull { it.effect }.sum()
        val changes: Int get() = systems.filter { !it.days }.sumOf { it.changes }
        val empty: Boolean get() = changes == 0 && promotions.isEmpty() && healthDays == 0
    }

    /** [month]'s card from Autopilot's [book] (its own changes and the specialists' tuning), the lab's [promotions] and the health tuner's [healthDays]. */
    fun of(book: AutoChange.Book, month: YearMonth, promotions: List<Promotion>, healthDays: Int): Card {
        val es = book.entries.filter { YearMonth.from(it.day) == month && it.status != AutoChange.Status.OFFERED &&
            it.status != AutoChange.Status.DECLINED && it.status != AutoChange.Status.SKIPPED }
        val systems = es.groupBy { e -> if (e.by.startsWith("specialists")) "Specialists' self-tuning" else "Autopilot · ${e.change.kind.agent}" }
            .map { (name, xs) ->
                val money = xs.filter { it.change.effect != 0 }
                // (A revert only asked of Boss has not happened - it is still in force, awaiting him: counted as on probation, G5-9.)
                System(name, xs.size, xs.count { it.status == AutoChange.Status.KEPT }, xs.count { it.status == AutoChange.Status.REVERTED },
                    xs.count { it.status == AutoChange.Status.UNDONE }, xs.count { it.status == AutoChange.Status.PROBATION || it.status == AutoChange.Status.REVERT_ASKED },
                    if (money.isEmpty()) null else money.sumOf { it.effect })
            }.sortedWith(compareByDescending<System> { it.changes }.thenBy { it.name })
        val lab = promotions.filter { YearMonth.from(it.day) == month }
        val labSys = if (lab.isEmpty()) emptyList() else listOf(System("Strategy lab", lab.size, lab.count { it.status.equals("APPLIED", true) || it.status.equals("ACCEPTED", true) },
            0, lab.count { it.status.equals("UNDONE", true) }, 0, null))
        val muted = es.filter { it.change.kind == AutoChange.Kind.SPECIALIST_MUTE && it.change.to == AutoChange.MUTED }.map { it.change.what }.distinct()
        val healthSys = if (healthDays > 0) listOf(System("Health tuner (app speed)", healthDays, 0, 0, 0, 0, null, days = true)) else emptyList()
        return Card(month, systems + labSys + healthSys, muted, lab, healthDays)
    }

    /** Is the card for the month before [today] due ([lastFor]: the month the last card was made for)? On the 1st, or the first day after it was missed. */
    fun due(today: LocalDate, lastFor: YearMonth?): Boolean {
        val want = YearMonth.from(today).minusMonths(1)
        return lastFor == null || lastFor.isBefore(want)
    }

    private fun rs(x: Double): String = (if (x < 0) "−₹" else "+₹") + String.format(Locale.ENGLISH, "%,.0f", kotlin.math.abs(x))
    fun monthWords(m: YearMonth): String = "${m.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${m.year}"

    /** The sheet's lines, one per system, then the mutes and the promotions. */
    fun lines(c: Card): List<String> {
        val out = ArrayList<String>()
        for (s in c.systems) out += if (s.days) "${s.name}: changed something on ${s.changes} day${if (s.changes == 1) "" else "s"} · no rupee effect (speed only)" else
            "${s.name}: ${s.changes} change${if (s.changes == 1) "" else "s"} - kept ${s.kept}, reverted ${s.reverted}, undone ${s.undone}" +
            (if (s.probation > 0) ", on probation ${s.probation}" else "") + " · " + (s.effect?.let { "${rs(it)} against no change" } ?: "no rupee effect (shadow or speed only)")
        if (c.muted.isNotEmpty()) out += "Specialists muted: " + c.muted.joinToString("; ")
        for (p in c.promotions) out += "Lab promotion ${AgentLessons.day(p.day)}: ${p.label} (${p.moved}) - ${p.status.lowercase(Locale.ENGLISH)}" +
            (p.lead?.let { ", ${rs(it)} ahead in the shadow before it" } ?: "")
        return out
    }

    /** The notification's one line. */
    fun notice(c: Card): String = if (c.empty) "No automatic changes in ${monthWords(c.month)}."
        else "${monthWords(c.month)}: ${c.changes} automatic change${if (c.changes == 1) "" else "s"}, ${rs(c.total)} against no change. Tap for the scorecard."

    /** "How did the automation do last month?" */
    fun say(c: Card): String {
        if (c.empty) return "Nothing changed by itself in ${monthWords(c.month)}, Boss: no automatic change to score."
        val kept = c.systems.sumOf { it.kept }; val rev = c.systems.sumOf { it.reverted }; val und = c.systems.sumOf { it.undone }
        val best = c.systems.filter { it.effect != null }.maxByOrNull { it.effect!! }
        val worst = c.systems.filter { it.effect != null && it.effect < 0 }.minByOrNull { it.effect!! }
        return "In ${monthWords(c.month)} the agents made ${c.changes} automatic change${if (c.changes == 1) "" else "s"}: kept $kept, reverted $rev, undone by you $und. " +
            "Measured on the trades that settled after each change, all of them together came to ${rs(c.total)} against making no change." +
            (best?.let { " Best: ${it.name} (${rs(it.effect!!)})." } ?: "") + (worst?.takeIf { it != best }?.let { " Worst: ${it.name} (${rs(it.effect!!)})." } ?: "") +
            (if (c.muted.isNotEmpty()) " Muted: ${c.muted.size} specialist${if (c.muted.size == 1) "" else "s"}." else "") +
            (if (c.promotions.isNotEmpty()) " The lab promoted ${c.promotions.size} variant${if (c.promotions.size == 1) "" else "s"}." else "") +
            (if (c.healthDays > 0) " The health tuner changed app speed on ${c.healthDays} day${if (c.healthDays == 1) "" else "s"}." else "")
    }
}
