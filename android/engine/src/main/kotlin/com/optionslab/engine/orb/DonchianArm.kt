package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalTime

/**
 * "R51 Donchian 20" (15 Oct 2026, Boss: "Both"): research R51's one pass, R44-donchian20-RT (research/HUNT_R51_CANCELLED_RECENT.md,
 * the decision table and Amendment A; code research/hunt/r51/fam_r44.py + r44/ind.py `donchian`), as a PAPER arm, 1 lot, its
 * own book, never live. It passed every gate, and it is thin: cleaned final test 1 Apr - 13 Oct 2026, 1,787 trades, +Rs 55,439,
 * twin p 0.002, BH q 0.025; at twice the spread +Rs 5,757; NIFTY lost; FINNIFTY carried it (reported apart: [REPORT_APART]).
 *
 *   signal   the INDEX's 5-minute bars (09:15-anchored, continuous across sessions, [PassedArms.bars5]): a close above the
 *            highest high of the previous 20 bars buys the call; a close below the lowest low of the previous 20 bars buys
 *            the put ([signal]). The previous bars run into the previous session(s), as the research's continuous series did.
 *   entry    the 1-ITM option of the nearest expiry ([PassedArms.itmStrike] on the index close at the signal bar's last
 *            minute) at the next bar's open, i.e. as soon as the bar has closed; the research filled within 2 minutes of it,
 *            else no fill ([FILL_SLACK_MINUTES]); entries 09:20 - 15:10 ([FIRST_ENTRY], [LAST_ENTRY])
 *   size     1 lot; one position a side per index, at most 2 entries a side a day per index ([MAX_PER_SIDE])
 *   days     never on that index's own expiry day
 *   exits    -15% stop, +30% target on the premium, out 30 minutes after the entry, square-off 15:15 ([EXITS])
 *   indices  NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX, as tested (the paper account takes SENSEX's BSE options since
 *            15 Oct: the Hero ticket's door). The FINNIFTY-only profit was found after the read: trading FINNIFTY alone would
 *            be a new rule, so all five run and FINNIFTY is reported apart.
 *   forward  [PassedArms.ForwardBar]: at 40 paper trades it switches itself off if the net is below 0 or below the cost of
 *            twice the spread.
 *
 * Pure: no clock, no network, no orders.
 */
object DonchianArm {
    const val SOURCE = "r51_donchian20"
    const val LABEL = "R51 Donchian 20"
    const val RESEARCH_ID = "R51 (R44-donchian20-RT)"
    val ARM = Arm(SOURCE, LABEL, paperOnly = true)
    val UNDERLYINGS = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
    /** Reported apart (the pass depends on it, found after the read). */
    const val REPORT_APART = "FINNIFTY"

    const val LOOKBACK = 20
    const val MONEY = 1
    const val LOTS = 1
    const val MAX_PER_SIDE = 2
    /** The research filled at the minute after the bar, or up to 2 minutes later; never after. */
    const val FILL_SLACK_MINUTES = 2L
    val FIRST_ENTRY: LocalTime = LocalTime.of(9, 20)
    val LAST_ENTRY: LocalTime = LocalTime.of(15, 10)
    val SQUARE_OFF: LocalTime = LocalTime.of(15, 15)
    val EXITS = PassedArms.PremiumExits(stop = 0.15, target = 0.30, tmaxMinutes = 30, squareOff = SQUARE_OFF)

    const val RULES = "On the index's 5-minute bars: a close above the highest high of the previous 20 bars buys the 1-ITM call, a close " +
        "below the lowest low buys the 1-ITM put (nearest expiry), at the next bar's open, 09:20-15:10; stop −15%, target +30% on the " +
        "premium, out after 30 minutes, square-off 15:15; one position a side, at most 2 entries a side a day; never on the index's " +
        "own expiry day; NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX; 1 lot; paper only"
    const val RECORD = "Research R51 final test (cleaned): 1,787 trades, +₹55,439, p 0.002, q 0.025 - thin: +₹5,757 at twice the spread, " +
        "NIFTY lost, FINNIFTY carried it (reported apart)"

    /** A Donchian break on bar [bar] (the signal bar): [side] +1 buys the call, -1 the put. */
    data class Signal(val underlying: String, val side: Int, val bar: PassedArms.Bar5, val strike: Int) {
        val entryTime get() = bar.closesAt
    }

    /**
     * R44's `donchian` on a continuous series of 5-minute bars ([series], oldest first): the side at bar [i] (+1, -1, or 0),
     * comparing its close with the highest high / lowest low of bars i-20 .. i-1. Fewer than 20 bars before it, or a missing
     * price among them: no signal.
     */
    fun sideAt(series: List<PassedArms.Bar5>, i: Int, n: Int = LOOKBACK): Int {
        if (i < n || i >= series.size) return 0
        val w = series.subList(i - n, i)
        if (w.any { it.high.isNaN() || it.low.isNaN() }) return 0
        val c = series[i].close
        if (c.isNaN()) return 0
        return when { c > w.maxOf { it.high } -> 1; c < w.minOf { it.low } -> -1; else -> 0 }
    }

    /** [t] is an entry minute (09:20 .. 15:10, both included). */
    fun inWindow(t: LocalTime): Boolean = !t.isBefore(FIRST_ENTRY) && !t.isAfter(LAST_ENTRY)

    /**
     * Today's signals on [underlying] from the series [series] (the earlier sessions' 5-minute bars, then [today]'s closed
     * ones): every bar of [today] that breaks, with its entry minute in the window, oldest first, its strike from the index
     * close of the bar's last minute ([closeAtSignal]). The day's caps (one position a side, 2 a side) are [take]'s.
     */
    fun signals(underlying: String, series: List<PassedArms.Bar5>, today: LocalDate): List<Signal> {
        val out = ArrayList<Signal>()
        for (i in series.indices) {
            val b = series[i]
            if (b.date != today || !inWindow(b.closesAt.toLocalTime())) continue
            val s = sideAt(series, i)
            if (s != 0) out += Signal(underlying, s, b, PassedArms.itmStrike(underlying, s, b.close, MONEY))
        }
        return out
    }

    /**
     * May [s] be bought now? One position a side ([openSides]: the sides held on this index now) and at most 2 entries a side
     * a day ([takenToday]: entries so far today by side). The entry window and the 2-minute fill slack are checked by
     * [mayFill].
     */
    fun mayTake(s: Signal, openSides: Set<Int>, takenToday: Map<Int, Int>): String? = when {
        s.side in openSides -> "one position a side: the ${if (s.side > 0) "call" else "put"} is still held"
        (takenToday[s.side] ?: 0) >= MAX_PER_SIDE -> "2 entries a side today already"
        else -> null
    }

    /** The research filled at the bar's close or up to 2 minutes after it (else no fill): [now] is still in time. */
    fun mayFill(s: Signal, now: java.time.LocalDateTime): Boolean =
        !now.isBefore(s.entryTime) && now.isBefore(s.entryTime.plusMinutes(FILL_SLACK_MINUTES + 1))
}
