package com.optionslab.ira

import java.time.LocalDate

/**
 * Liquidity BANKNIFTY's paper test is run on its own rules, untouched (Boss, 12 Oct; the strategy scorecard: "the strategy
 * lab and regime changes are frozen for this book"; research R12: every exit manager tried made it worse). Until [UNTIL]
 * (the 10th paper day) no automatic change alters its entries or exits:
 *  - the strategy lab's paper variants (its exit parameters) are not applied to its books: they trade their constants;
 *  - Autopilot applies no change aimed at Liquidity (a mode, a pause, a threshold) - recorded, not applied;
 *  - the day's regime lesson and the self-review's paper pause do not skip its entries;
 *  - the trade manager only records it (its exits never read the manager, in ACT or SHADOW).
 * Shadows and records go on. Boss may unfreeze it from the Strategy lab card (automatic changes only ever lower risk), and
 * freeze it again. Pure: today is given; the state is the app's small file.
 */
object LiquidityFreeze {
    /** The last of Boss's 10 paper days (13-26 Oct 2026). A constant: tests give their own today. */
    val UNTIL: LocalDate = LocalDate.of(2026, 10, 26)

    /** What a change recorded but not applied says. */
    const val NOTE = "frozen for Liquidity BANKNIFTY's paper test: recorded, not applied"

    /**
     * The paper test's small state: [unfrozen] by Boss; the live stop rule's restart ([stopResetAtMs]: trades closed before
     * it no longer count, set when Boss re-enables live entries with his PIN) and the stop already told ([stopTold]); Boss's
     * own tick that he watched the first live trade ([watched]).
     */
    data class State(val unfrozen: Boolean = false, val stopResetAtMs: Long = 0L, val stopTold: String = "", val watched: Boolean = false)

    fun frozen(s: State, today: LocalDate): Boolean = !s.unfrozen && !today.isAfter(UNTIL)

    private fun day(d: LocalDate) = "${d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${d.dayOfMonth} ${d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"

    /** The row's note. */
    fun note(s: State, today: LocalDate): String = when {
        frozen(s, today) -> "Frozen for the paper test until ${day(UNTIL)}: the strategy lab's variants, Autopilot and the trade manager " +
            "only record - they do not change its entries or exits."
        s.unfrozen && !today.isAfter(UNTIL) -> "Unfrozen by you: automatic changes that lower risk may apply to it again."
        else -> "The paper test's freeze ended on ${day(UNTIL)}."
    }

    /** Whether an Autopilot change target ("paper:liquidity", "flow:liquidity:BANKNIFTY", "mgr:liquidity:paper") is Liquidity's. */
    fun touches(target: String): Boolean = target.contains("liquidity", ignoreCase = true)

    fun encode(s: State): String = "v1\nunfrozen=${s.unfrozen}\nstopResetAt=${s.stopResetAtMs}\nstopTold=${s.stopTold.replace('\n', ' ')}\nwatched=${s.watched}\n"

    fun decode(text: String?): State {
        if (text.isNullOrBlank()) return State()
        val m = text.lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
        return State(m["unfrozen"] == "true", m["stopResetAt"]?.toLongOrNull() ?: 0L, m["stopTold"].orEmpty(), m["watched"] == "true")
    }
}
