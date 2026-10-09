package com.optionslab.engine.orb

/**
 * Two losing paper arms parked on Boss's OK (9 Oct 2026): Liquidity 15+5's FINNIFTY books (-Rs 8,035 over 8 paper trades)
 * and ORB Sweep (-Rs 4,498 over 6). Once, on this update ([MIGRATION], kept in the arms' book), each is switched off on
 * PAPER terms: an open position is still managed to its exit, nothing is sold here, nothing is armed, and Live arming is
 * never touched (a parked book is also not cleared for Zerodha, so switching it back on in Live asks for the PIN again).
 *
 * FINNIFTY stays parked under Liquidity's one switch ([armable]): switching Liquidity off and on again does not bring it
 * back - only Boss's own "switch FINNIFTY back on" does ([unpark]). ORB Sweep has its own switch: on again any time.
 * A new book (a new install) has nothing to park: it starts with the change marked done. Pure.
 */
object ParkedArms {
    /** The one-time change's key in the arms' book. */
    const val MIGRATION = "parked_2026_10_09"

    /** Liquidity 15+5's indices parked by it. */
    val INDICES: Set<String> = setOf("FINNIFTY")

    /** The other arms it switches off (ORB Sweep). */
    val ARMS: List<String> = listOf(SweepRules.ARM.source)

    /** The one-time notice and the line under Liquidity's row. */
    const val NOTICE = "Liquidity 15+5 FINNIFTY (−₹8,035 over 8 paper trades) and ORB Sweep (−₹4,498 over 6) were switched off on your OK; " +
        "switch them back on in Home → Strategies any time."

    /** A parked arm's status (its row, the arm log). */
    const val PARKED = "parked on your OK (9 Oct): paper record negative; switch it back on any time"

    /** Each parked index's paper record, said where it can be switched back on. */
    fun record(index: String): String = when (index) {
        "FINNIFTY" -> "−₹8,035 over 8 paper trades"
        else -> "paper record negative"
    }

    /** The books of Liquidity 15+5 that trade [index]. */
    fun books(index: String): List<Arm> = LiquidityRules.BOOKS.filter { LiquidityRules.underlyingOf(it) == index }

    /** What the change does to a book: the sources to switch off (each armed one), and the indices to park. */
    data class Plan(val off: List<String>, val parked: Set<String>)

    /**
     * The change for a book with [armed] switches: every FINNIFTY book and ORB Sweep that is on goes off, and FINNIFTY is
     * parked whether its books were on or not. Null when it already ran ([done]).
     */
    fun plan(armed: Map<String, Boolean>, done: Boolean): Plan? {
        if (done) return null
        val sources = INDICES.flatMap { i -> books(i).map { it.source } } + ARMS
        return Plan(sources.filter { armed[it] == true }, INDICES)
    }

    /** The Liquidity books its one switch arms: every book but a parked index's. */
    fun armable(parked: Set<String>): List<Arm> = LiquidityRules.BOOKS.filter { LiquidityRules.underlyingOf(it) !in parked }

    /** [parked] without [index] (Boss switched it back on); unchanged when it was not parked. */
    fun unpark(parked: Set<String>, index: String): Set<String> = parked - index
}
