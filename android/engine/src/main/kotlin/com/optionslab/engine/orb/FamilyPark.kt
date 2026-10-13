package com.optionslab.engine.orb

import java.util.Locale

/**
 * ORB, ORB Fresh, ORB Sweep and Range Fade parked on Boss's choice (13 Oct 2026: "Switch off ORB, ORB Fresh, Sweep and
 * Range Fade"): each lost money after costs in every tested year (two BankNifty years, 1 lot, real charges). Once, on this
 * update ([MIGRATION], kept in the arms' book), each one that is armed is switched off - on paper, and in Live too - and all
 * four are marked parked. Disarming only stops NEW entries: an open position is still managed to its own exit, nothing is
 * sold here. Nothing is armed here, ever.
 *
 * Paused for research (Boss, 13 Oct: "research this more deeply when all the Dhan data is downloaded"): TODO R3.
 * Parked means: its row says so with a tap to switch it back on (the usual arming flow - the PIN in Live), the morning check
 * and Problems found do not warn about it, and Jarvis does not ask to switch it off. Boss switching one back on
 * ends its park ([unpark]); the change never runs again, so it is never switched off a second time. A new book (a new
 * install) has nothing to park: it starts with the change marked done. Pure.
 */
object FamilyPark {
    /** The one-time change's key in the arms' book. */
    const val MIGRATION = "park_orb_family_2026_10_13"

    /** The four arms, in the rows' order. */
    val ARMS: List<Arm> = listOf(OrbRules.ORB, OrbRules.ORB_FRESH, SweepRules.ARM, RangeFadeRules.ARM)
    private val SOURCES: Set<String> = ARMS.map { it.source }.toSet()

    /** Each arm's two tested BankNifty years, rupees a lot after charges (the research's record, as TradeCheck's). */
    val TESTED: Map<String, Pair<Double, Double>> = mapOf(
        OrbRules.ORB.source to (-77_013.0 to -69_754.0),
        OrbRules.ORB_FRESH.source to (-16_838.0 to -6_361.0),
        SweepRules.ARM.source to (-24_046.0 to -76_025.0),
        RangeFadeRules.ARM.source to (-47_158.0 to -72_340.0),
    )

    /** Is [source] one of the four? */
    fun covers(source: String): Boolean = source in SOURCES

    /** A parked arm's status (the arm log). */
    const val PARKED = "parked on your choice (13 Oct): lost in every tested year"

    /** What a parked arm's row says (the tap to switch it back on is under it). */
    const val ROW = "parked on your choice (13 Oct)"

    private fun rs(x: Double) = (if (x < 0) "−₹" else "₹") + String.format(Locale.ENGLISH, "%,.0f", kotlin.math.abs(x))

    /** "−₹77,013 and −₹69,754 a lot" for [source]; empty when it is not one of the four. */
    fun record(source: String): String = TESTED[source]?.let { (a, b) -> "${rs(a)} and ${rs(b)} a lot" } ?: ""

    /** The row's line: "parked on your choice (13 Oct): lost in every tested year (−₹77,013 and −₹69,754 a lot)". */
    fun rowLine(source: String): String = "$ROW: lost in every tested year (${record(source)})"

    /** The one-time notice (the arm log, and a notification when something was switched off). */
    const val NOTICE = "ORB, ORB Fresh, ORB Sweep and Range Fade are parked on your choice (13 Oct): each lost money after costs in " +
        "every tested year. Paused for research: they will be studied again on the full Dhan data (Boss, 13 Oct). Open positions " +
        "are still managed to their exits; switch any back on in Home → Strategies."

    /** What Jarvis says first when asked about a parked arm. */
    fun history(label: String): String = "$label is parked on your choice (13 Oct), Boss: it lost money after costs in every tested year. " +
        "It is paused for research: it will be studied again on the full Dhan data. Switch it back on in Home → Strategies whenever you choose."

    /** What the change does: the sources to switch off (each armed one), and the four marked parked. */
    data class Plan(val off: List<String>, val parked: Set<String>)

    /** The change for a book with [armed] switches; null when it already ran ([done]). */
    fun plan(armed: Map<String, Boolean>, done: Boolean): Plan? {
        if (done) return null
        return Plan(ARMS.map { it.source }.filter { armed[it] == true }, SOURCES)
    }

    /** [parked] after Boss switched [source] on: no longer parked. */
    fun unpark(parked: Set<String>, source: String): Set<String> = parked - source
}
