package com.optionslab.ira

import com.optionslab.engine.orb.ArmPriority

/**
 * One index, one side at a time for the automatic traders (Boss's paper diagnostics, 06 Oct: ORB Sweep bought a BankNifty
 * put at 12:30, ORB a BankNifty call at 12:35 and Liquidity 15m another call at 12:46 - the arms traded against each
 * other and each paid its charges). Every automatic entry path (the ORB arms with Liquidity 15+5 and the Hero arm, the
 * Pine auto-trade scripts, the Strategies, Jarvis's Solo and the trades Jarvis places by himself) asks [check] before a
 * new entry, against every automatically opened position still open (any arm, script, strategy or Solo):
 *
 *  - a position on the same index the OTHER way (a long call against a long put; a short is read by its delta's sign, so
 *    a short put leans up like a call) refuses the entry: "opposite_position_open: <who> holds <symbol>";
 *  - a position on the same index the SAME way also refuses it: at most one automatic position an index a side at a time
 *    ("same_side_already_held: <who> holds <symbol>").
 *
 * Liquidity 15+5 has priority over the ORB arms (Boss's 07 Oct decision, research/HUNT_H21.md; [ArmPriority]): a Liquidity
 * entry is never refused because ORB, ORB Fresh, ORB Sweep or Range Fade holds the index - it runs beside it - while an
 * ORB-family entry is still refused when Liquidity holds it ("...; Liquidity has priority over ORB arms"). When both the
 * Liquidity entry and the old arm's holding are live, the rule holds as before (never two automatic live positions on one
 * index). Every other trader is unchanged either way.
 *
 * A neutral entry (a straddle, an iron condor: no lean) is never refused, and a neutral holding never refuses one. Boss's
 * own orders are never refused: the order review only warns ([warn]). It only ever refuses an entry - never closes,
 * changes or adds anything. Pure: no clock, no network, no orders.
 */
object AutoSide {
    /**
     * One open automatic position: [who] placed it ("ORB Sweep", "Pine #3 · Jarvis: bullish engulfing Nifty 15m",
     * "Jarvis solo"), its [symbol], its index ([underlying], "BANKNIFTY"), its lean ([direction]: +1 up, -1 down, 0 none)
     * and [what] it is in words ("a call", "a short put", "a bullish position"); its [rank] for Liquidity's priority
     * ([ArmPriority.Rank]: Liquidity, the ORB family, or anything else) and whether it is [live] at the broker.
     */
    data class Held(val who: String, val symbol: String, val underlying: String, val direction: Int, val what: String,
                    val rank: ArmPriority.Rank = ArmPriority.Rank.OTHER, val live: Boolean = false) {
        companion object {
            /** A single option leg: [right] "CE" or "PE", [long] bought (true) or sold. */
            fun option(who: String, symbol: String, underlying: String, right: String, long: Boolean,
                       rank: ArmPriority.Rank = ArmPriority.Rank.OTHER, live: Boolean = false): Held =
                Held(who, symbol, underlying, direction(right, long), words(right, long), rank, live)
        }
    }

    const val OPPOSITE = "opposite_position_open"
    const val SAME_SIDE = "same_side_already_held"

    /** The lean of an option leg: a bought call or a sold put leans up (+1); a bought put or a sold call leans down (-1). */
    fun direction(right: String, long: Boolean): Int {
        val r = when (right.uppercase()) { "CE", "CALL" -> 1; "PE", "PUT" -> -1; else -> 0 }
        return if (long) r else -r
    }

    /** The net lean of several legs (a spread, a straddle): the sign of their sum; 0 when they cancel. */
    fun net(directions: List<Int>): Int = Integer.signum(directions.sum())

    private fun words(right: String, long: Boolean): String {
        val kind = when (right.uppercase()) { "CE", "CALL" -> "call"; "PE", "PUT" -> "put"; else -> "position" }
        return if (long) "a $kind" else "a short $kind"
    }

    /** Index names as option symbols start with them, the longest first (BANKNIFTY before NIFTY). */
    private val INDICES = listOf("MIDCPNIFTY", "BANKNIFTY", "FINNIFTY", "NIFTYNXT50", "BANKEX", "SENSEX", "NIFTY")

    /** The index an option symbol is on ("BANKNIFTY26OCT52000CE", "BANKNIFTY-ORB-52000CE" -> BANKNIFTY), or null. */
    fun underlyingOf(symbol: String): String? {
        val s = symbol.uppercase().substringAfter(':')
        return INDICES.firstOrNull { s.startsWith(it) }
    }

    /** "CE" or "PE" from an option symbol's end, or null (a future, a share). */
    fun rightOf(symbol: String): String? = symbol.uppercase().let { s -> when { s.endsWith("CE") -> "CE"; s.endsWith("PE") -> "PE"; else -> null } }

    /** What a refusal by Liquidity's priority ends with ([check]), and what [describe] says of it. */
    const val PRIORITY = "; " + ArmPriority.WORDS

    /**
     * Whether a new automatic entry on [underlying] leaning [direction] may go, against [held] (every automatic position
     * open now, the asker's own included): null when it may, else the refusal for the row and the log. [rank]: the asker's
     * ([ArmPriority.Rank.LIQUIDITY] for a Liquidity book, [ArmPriority.Rank.OLD_ARM] for the ORB family), [live]: whether
     * the entry goes to the broker - the holdings that do not count against it ([ArmPriority.counts]) are left out.
     */
    fun check(underlying: String, direction: Int, held: List<Held>, rank: ArmPriority.Rank = ArmPriority.Rank.OTHER,
              live: Boolean = false): String? {
        if (direction == 0) return null
        val same = held.filter { it.underlying.equals(underlying, ignoreCase = true) && it.direction != 0 &&
            ArmPriority.counts(it.rank, it.live, rank, live) }
        fun tail(h: Held) = if (ArmPriority.byPriority(h.rank, rank)) PRIORITY else ""
        same.firstOrNull { it.direction == -direction }?.let { return "$OPPOSITE: ${it.who} holds ${it.symbol}${tail(it)}" }
        same.firstOrNull { it.direction == direction }?.let { return "$SAME_SIDE: ${it.who} holds ${it.symbol}${tail(it)}" }
        return null
    }

    /**
     * For the activity log, when a Liquidity entry ([rank]) on [underlying] leaning [direction] goes beside ORB-family
     * holdings that would have refused it before 07 Oct: "Liquidity 15m is not held back by ORB's BANKNIFTY...CE: Liquidity has
     * priority over ORB arms." [who] names the entry. Null when nothing was set aside (or the entry is refused anyway).
     */
    fun priorityNote(who: String, underlying: String, direction: Int, held: List<Held>, rank: ArmPriority.Rank, live: Boolean = false): String? {
        if (direction == 0 || check(underlying, direction, held, rank, live) != null) return null
        val beside = held.filter { it.underlying.equals(underlying, ignoreCase = true) && it.direction != 0 &&
            !ArmPriority.counts(it.rank, it.live, rank, live) }
        if (beside.isEmpty()) return null
        return "$who is not held back by " + beside.joinToString(", ") { "${it.who}'s ${it.symbol}" } + ": ${ArmPriority.WORDS}."
    }

    /** Whether [status] is one of [check]'s refusals. */
    fun refused(status: String?): Boolean = status != null && (status.startsWith("$OPPOSITE: ") || status.startsWith("$SAME_SIDE: "))

    /** A refusal from [check] in plain words for a row or a log line; anything else as it is. */
    fun describe(status: String): String {
        val priority = status.endsWith(PRIORITY)
        val s = status.removeSuffix(PRIORITY)
        val tail = if (priority) " ${ArmPriority.WORDS}." else ""
        return when {
            s.startsWith("$OPPOSITE: ") -> "Not entered: ${s.removePrefix("$OPPOSITE: ")} the other way on this index " +
                "(no automatic trade against another).$tail"
            s.startsWith("$SAME_SIDE: ") -> "Not entered: ${s.removePrefix("$SAME_SIDE: ")} the same way on this index " +
                "(one automatic position an index a side).$tail"
            else -> status
        }
    }

    private fun label(underlying: String): String =
        runCatching { Market.valueOf(underlying.uppercase()).label }.getOrDefault(underlying.uppercase())

    /**
     * Boss's own order (never refused): a word for the order review when an automatic position on the same index leans the
     * other way, e.g. "ORB holds a call on BankNifty; this put works against it". [direction] and [what] describe his order
     * ("this put", "this short call"); null when nothing works against it.
     */
    fun warn(underlying: String, direction: Int, what: String, held: List<Held>): String? {
        if (direction == 0) return null
        val against = held.filter { it.underlying.equals(underlying, ignoreCase = true) && it.direction == -direction }
        if (against.isEmpty()) return null
        return against.joinToString(" ") { "${it.who} holds ${it.what} on ${label(underlying)}; $what works against it." }
    }

    /** "this put", "this short call" for [warn], from an order's [right] and side. */
    fun orderWords(right: String, buy: Boolean): String = "this " + words(right, buy).removePrefix("a ")
}
