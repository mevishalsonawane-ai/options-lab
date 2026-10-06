package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs

/**
 * When to ask Boss to switch an arm off (his choice after the 06 Oct paper diagnostics): an arm - an ORB-family arm, a Pine
 * auto-trade script, a Strategy Lab arm - with at least [MIN_TRADES] closed paper trades whose net after charges is below
 * zero. Jarvis then asks (approve or reject, as any request); done by itself only when Boss chose automatic stops in chat.
 * Never arms anything again: switching on stays Boss's own. An open position of the arm switched off is still managed to
 * its exit. Pure.
 */
object ArmCutoff {
    const val MIN_TRADES = 15

    /** One arm: its [name] as the app shows it, [armed] now, and its closed paper trades' net rupees after charges. */
    data class Arm(val name: String, val armed: Boolean, val paper: List<Double>)

    /** Whether [a] is to be put to Boss now: armed, at least [MIN_TRADES] closed paper trades, and a net below zero. */
    fun due(a: Arm): Boolean = a.armed && a.paper.size >= MIN_TRADES && a.paper.sum() < 0

    /** The armed arms due ([due]), the deepest loss first. */
    fun dueOf(arms: List<Arm>): List<Arm> = arms.filter { due(it) }.sortedBy { it.paper.sum() }

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))

    /** The question put to Boss (yes or no). */
    fun ask(a: Arm): String {
        val won = a.paper.count { it > 0 }
        return "Boss, ${a.name} has ${a.paper.size} closed paper trades, $won won, ${rs(a.paper.sum())} net after charges. " +
            "Shall I switch ${a.name} off? Any open position is still managed to its exit; I never switch it back on - that stays yours."
    }

    /** The log line once it is switched off. */
    fun done(a: Arm): String = "${a.name} switched off: paper record negative (${a.paper.size} trades, ${rs(a.paper.sum())} after charges)."
}
