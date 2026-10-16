package com.optionslab.engine.orb

import java.time.LocalDate
import java.util.Locale

/**
 * "R72 Breadth Donchian" (16 Oct 2026, Boss: "Okay add it"): research R72's one pass, C1 "breadth breakout" at its original
 * setting (research/HUNT_R72_CROSS_INDEX.md, research/hunt/r72/APP_SPEC.md; code research/hunt/r72/r72rules.py
 * `breadth_breakout`, M = 4), as a PAPER arm BESIDE the R51 Donchian 20 paper arm ([DonchianArm]): both run, so the two can be
 * compared on the same unseen days. 1 lot, its own book, never live.
 *
 *   signal   the INDICES' own 5-minute bars, read together: on each bar, [DonchianArm.sideAt] for each of NIFTY, BANKNIFTY,
 *            FINNIFTY, MIDCPNIFTY and SENSEX; when at least [MIN_BREADTH] of them break the same way on the same bar (same
 *            date, same bar of the day), every index that broke that way gets the signal (+1 the call, -1 the put); fewer:
 *            nothing, even where Donchian alone would trade. An index with no bar then (missing data, a feed down) counts as
 *            not breaking; the threshold stays 4 (absolute, not a share).
 *   the rest Donchian's, through its own helpers: the 1-ITM strike from the signal bar's close ([PassedArms.itmStrike]), the
 *            09:20-15:10 window ([DonchianArm.inWindow]), the 2-minute fill slack ([DonchianArm.mayFill]), one position a side
 *            and 2 entries a side a day per index in THIS arm's own book ([DonchianArm.mayTake]), never on the index's own
 *            expiry day, 1 lot; exits [EXITS] (Donchian's, with the 30-60-100 lock and L25 when the book has them on, as the
 *            Donchian paper arm runs today).
 *   forward  [PassedArms.ForwardBar], as Donchian.
 *
 * Not a new edge: 77% of its research trades were Donchian's own (a filter). Pure: no clock, no network, no storage, no orders.
 */
object BreadthDonchianArm {
    const val SOURCE = "r72_breadth_donchian"
    const val LABEL = "R72 Breadth Donchian"
    const val RESEARCH_ID = "R72 (C1, breadth 4 of 5)"
    val ARM = Arm(SOURCE, LABEL, paperOnly = true)
    val UNDERLYINGS = DonchianArm.UNDERLYINGS
    /** Reported apart like Donchian, so the two records compare index for index. */
    const val REPORT_APART = DonchianArm.REPORT_APART
    /** At least this many of the five break the same way on the same bar (research M = 4). */
    const val MIN_BREADTH = 4
    val EXITS = DonchianArm.EXITS

    const val RULES = "On each 5-minute bar: when at least 4 of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX close above the " +
        "highest high of their previous 20 bars, buy the 1-ITM call of each index that did (below the lowest low: the put); fewer " +
        "than 4: no trade. Next bar's open, 09:20-15:10; stop −15%, +5% once +25%, the 30/60/100 ladder, out after 30 minutes, " +
        "square-off 15:15; one position a side, at most 2 entries a side a day per index; never on the index's own expiry day; " +
        "1 lot; paper only"
    const val RECORD = "Research R72 final test: 1,202 trades, +₹1,71,366 (Donchian on the same days +₹55,439 / +₹81,845 with L25), " +
        "p 0.008, q 0.032; +₹1,37,648 at twice the slippage. Same trades as Donchian 77% of the time: a filter, not a new edge"
    /** Shown with it (the spec's risk line). */
    const val RISK = "Backtest worst day −₹58,613, drawdown ₹1.99 lakh at 1 lot on five indices: beyond the −₹40k account stop; " +
        "paper only"
    /** What R72 found for Donchian's trades that breadth did or did not confirm (final test). */
    const val SPLIT = "in research, Donchian's trades breadth confirmed made +₹1,37,635 (752) and the ones it did not lost −₹55,790 (1,029)"

    /**
     * The breaks on bar [b] of [date] for each index of [seriesByIndex] (the continuous 5-minute series, oldest first): +1, -1,
     * or 0 (no break, or no bar then). Only the five [UNDERLYINGS] count.
     */
    fun sidesAt(seriesByIndex: Map<String, List<PassedArms.Bar5>>, date: LocalDate, b: Int): Map<String, Int> =
        UNDERLYINGS.associateWith { u ->
            val s = seriesByIndex[u].orEmpty()
            val i = s.indexOfLast { it.date == date && it.b == b }
            if (i < 0) 0 else DonchianArm.sideAt(s, i)
        }

    /** The bar's call (+1) or put (-1) breadth, or 0 when fewer than [MIN_BREADTH] break the same way. */
    fun breadth(sides: Map<String, Int>): Int = when {
        sides.values.count { it > 0 } >= MIN_BREADTH -> 1
        sides.values.count { it < 0 } >= MIN_BREADTH -> -1
        else -> 0
    }

    /**
     * Today's signals from the five indices' series ([seriesByIndex]: each index's earlier sessions' 5-minute bars, then
     * [today]'s closed ones): on every bar of [today] whose entry minute is in the window, every index that broke the way at
     * least [MIN_BREADTH] of them did, as a [DonchianArm.Signal] (its strike from that index's bar close). Oldest bar first,
     * then the five in [UNDERLYINGS]' order. The day's caps and the fill window are [DonchianArm.mayTake] / [DonchianArm.mayFill].
     */
    fun signals(seriesByIndex: Map<String, List<PassedArms.Bar5>>, today: LocalDate): List<DonchianArm.Signal> {
        // Each index's side and bar on each of today's bars (one walk per index, the channel being Donchian's own).
        val by = HashMap<Int, MutableMap<String, Pair<Int, PassedArms.Bar5>>>()
        for (u in UNDERLYINGS) {
            val s = seriesByIndex[u] ?: continue
            for (i in s.indices) {
                val bar = s[i]
                if (bar.date != today) continue
                by.getOrPut(bar.b) { HashMap() }[u] = DonchianArm.sideAt(s, i) to bar
            }
        }
        val out = ArrayList<DonchianArm.Signal>()
        for (b in by.keys.sorted()) {
            val row = by.getValue(b)
            val side = breadth(row.mapValues { it.value.first })
            if (side == 0) continue
            for (u in UNDERLYINGS) {
                val (s, bar) = row[u] ?: continue
                if (s != side || !DonchianArm.inWindow(bar.closesAt.toLocalTime())) continue
                out += DonchianArm.Signal(u, side, bar, PassedArms.itmStrike(u, side, bar.close, DonchianArm.MONEY))
            }
        }
        return out
    }

    /**
     * Boss's comparison (the point of running both): since [since] (the switch-on day), this arm's closed paper trades
     * ([breadthNets]) next to Donchian's ([donchianNets]), and Donchian's trades that breadth did not confirm ([unconfirmedNets];
     * null: not known yet). Nets after charges, in words. 16 Oct (R73 T7 on R51 only): [stSplit], Donchian's trades split into
     * those taken under its Supertrend filter and those on its plain rules (null: no filtered trade yet and the filter off), so
     * the comparison says which Donchian record it is set against.
     */
    fun comparison(since: LocalDate?, breadthNets: List<Double>, donchianNets: List<Double>, unconfirmedNets: List<Double>?,
                   stSplit: Pair<List<Double>, List<Double>>? = null): String {
        fun side(n: List<Double>) = "${n.size} trade${if (n.size == 1) "" else "s"}, ${rs(n.sum())}"
        val from = since?.let { "since ${it.dayOfMonth} ${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }.take(3)}" } ?: "so far"
        return "Breadth Donchian vs Donchian on paper $from: $LABEL ${side(breadthNets)}; ${DonchianArm.LABEL} ${side(donchianNets)}" +
            (unconfirmedNets?.let { "; Donchian's trades breadth did not confirm: ${side(it)}" } ?: "") +
            (stSplit?.let { (f, p) -> "; Donchian's with its Supertrend filter: ${side(f)}, on its plain rules: ${side(p)}" } ?: "") +
            " (after charges; $SPLIT)"
    }

    private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
}
