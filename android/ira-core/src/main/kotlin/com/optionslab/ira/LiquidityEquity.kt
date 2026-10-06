package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Liquidity 15+5's paper record as a chart (the Strategies screen's "Paper record"): its closed paper trades since the
 * forward test began ([ForwardCheck.Expectation.since], 06 Oct 2026), net after charges PER LOT, as a running total, drawn
 * against what the backtest expects after the same number of trades and the running drawdown under it.
 *
 *   total      the running net after trade i (i = 1..n; 0 before the first)
 *   expected   the backtest's mean a trade times i, with its band mean·i ± 2·sd·√i - the same band as the card's mean a
 *              trade ± 2·sd/√i, on the running total ([ForwardCheck.expectedPath])
 *   drawdown   after trade i, the running total less its best so far (counting from 0): 0 or negative
 *   streak     the run of the latest trades on the same side: wins (net > 0) or losses (net 0 or below, as the win rate)
 *
 * Trades are taken by day, then entry time (none first), ties as given; a net that is not a number is left out. The
 * verdict is [ForwardCheck.check]'s on the very same trades. Pure: no clock, no storage; it reads and changes nothing.
 */
object LiquidityEquity {
    /** The chart needs at least this many trades; with fewer, a "waiting for trades" line instead. */
    const val MIN_POINTS = 2

    /** One closed paper trade: the day, its net after charges per lot, the index it traded ("" unknown), its entry time. */
    data class Trade(val day: LocalDate, val net: Double, val index: String = "", val at: LocalTime? = null)

    /** Trade [n] (1-based) of the record: its [day], [index], [net], the running [total] after it and the [drawdown] there. */
    data class Point(val n: Int, val day: LocalDate, val index: String, val net: Double, val total: Double, val drawdown: Double)

    /** After [n] trades the backtest expects a running total of [expected], [low] .. [high] (±2·sd·√n). */
    data class Band(val n: Int, val low: Double, val expected: Double, val high: Double)

    /** The latest trades' run: [wins] (else losses), [length] of them. */
    data class Streak(val wins: Boolean, val length: Int)

    /**
     * The record: [points] (one a trade, in order), [band] for n = 0..trades (its first at 0), [net], [maxDrawdown] (0 or
     * negative), [streak] (null with no trade) and [check] - the forward check on the same trades (its verdict and line).
     */
    data class Equity(
        val expectation: ForwardCheck.Expectation, val points: List<Point>, val band: List<Band>, val net: Double,
        val maxDrawdown: Double, val streak: Streak?, val check: ForwardCheck.Result,
    ) {
        val trades: Int get() = points.size
        /** Enough trades to draw: [MIN_POINTS] or more. */
        val enough: Boolean get() = trades >= MIN_POINTS
        /** The running total from 0: n + 1 values. */
        val totals: List<Double> get() = listOf(0.0) + points.map { it.total }
        /** The running drawdown from 0: n + 1 values, each 0 or negative. */
        val drawdowns: List<Double> get() = listOf(0.0) + points.map { it.drawdown }
    }

    /** The record of [trades] against [e] (Liquidity 15+5's backtest unless said); trades before its start are left out. */
    fun of(trades: List<Trade>, e: ForwardCheck.Expectation = ForwardCheck.LIQUIDITY): Equity {
        val mine = trades.filter { t -> t.net.isFinite() && (e.since?.let { !t.day.isBefore(it) } ?: true) }
            .sortedWith(compareBy<Trade>({ it.day }, { it.at }))
        var run = 0.0
        var peak = 0.0
        val points = mine.mapIndexed { i, t ->
            run += t.net
            peak = max(peak, run)
            Point(i + 1, t.day, t.index, t.net, run, run - peak)
        }
        val band = ForwardCheck.expectedPath(e, points.size).mapIndexed { i, (lo, mid, hi) -> Band(i, lo, mid, hi) }
        val check = ForwardCheck.check(e, mine.map { ForwardCheck.Trade(it.day, it.net) })
        return Equity(e, points, band, run, points.minOfOrNull { it.drawdown }?.let { min(0.0, it) } ?: 0.0, streak(mine.map { it.net }), check)
    }

    /** The run of the last trades of [nets] on the same side (a win: net > 0); null with none. */
    fun streak(nets: List<Double>): Streak? {
        if (nets.isEmpty()) return null
        val wins = nets.last() > 0
        val length = nets.asReversed().takeWhile { (it > 0) == wins }.size
        return Streak(wins, length)
    }

    /** "3 wins", "1 loss", "—" (no trade). */
    fun streakWords(s: Streak?): String = when {
        s == null -> "—"
        s.wins -> "${s.length} win${if (s.length == 1) "" else "s"}"
        else -> "${s.length} loss${if (s.length == 1) "" else "es"}"
    }

    /** The low and high of everything the chart draws (the band, the running total and 0): high > low always. */
    fun range(q: Equity): Pair<Double, Double> {
        val ys = q.band.flatMap { listOf(it.low, it.high) } + q.totals
        val lo = min(0.0, ys.min())
        val hi = max(0.0, ys.max())
        return if (hi > lo) lo to hi else lo to lo + 1.0
    }

    /** The drawdown strip's floor: the deepest drawdown (negative), or −1 with none, so the strip never divides by 0. */
    fun floor(q: Equity): Double = if (q.maxDrawdown < 0) q.maxDrawdown else -1.0

    /**
     * The trade nearest a tap at [fraction] (0 at the left, 1 at the right) of a chart that draws trade i at i / [trades]
     * of its width: 1..trades, or 0 with no trade.
     */
    fun nearest(trades: Int, fraction: Double): Int {
        if (trades <= 0) return 0
        val f = if (fraction.isNaN()) 0.0 else fraction.coerceIn(0.0, 1.0)
        return (f * trades).roundToInt().coerceIn(1, trades)
    }

    /** The figures under the chart, in order: trades, net, max drawdown, streak. */
    fun stats(q: Equity): List<Pair<String, String>> = listOf(
        "Trades" to "${q.trades}",
        "Net" to rs(q.net, sign = true),
        "Max drawdown" to rs(q.maxDrawdown),
        "Streak" to streakWords(q.streak),
    )

    /** The verdict in one line ([ForwardCheck.line]), on the same trades. */
    fun verdict(q: Equity): String = ForwardCheck.line(q.check)

    /** With fewer than [MIN_POINTS] trades: what the card says instead of an empty chart. */
    fun waiting(q: Equity): String {
        val since = q.expectation.since?.let { " since ${date(it)}" } ?: ""
        return when (q.trades) {
            0 -> "Waiting for trades: no closed paper trade$since yet. The chart starts at $MIN_POINTS."
            else -> "Waiting for trades: ${q.trades} closed paper trade$since (${rs(q.net, sign = true)} a lot). The chart starts at $MIN_POINTS."
        }
    }

    /** One trade in words: "6 Oct 2026 · #1 · BANKNIFTY · +₹420 · total +₹420" (no index when unknown). */
    fun pointLine(p: Point): String =
        listOfNotNull(date(p.day), "#${p.n}", p.index.takeIf { it.isNotBlank() }, rs(p.net, sign = true), "total ${rs(p.total, sign = true)}")
            .joinToString(" · ")

    /** The expectation at trade [n] in words: "Expected after 12 trades: ₹2,732 (−₹11,570 to ₹17,034)". */
    fun expectedLine(q: Equity, n: Int = q.trades): String {
        val b = q.band.getOrNull(n) ?: q.band.last()
        return "Expected after ${b.n} trade${if (b.n == 1) "" else "s"}: ${rs(b.expected, sign = true)} (${rs(b.low, sign = true)} to ${rs(b.high, sign = true)})"
    }

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    /** "6 Oct 2026". */
    fun date(d: LocalDate): String = DATE.format(d)

    /** Rupees, whole: "−₹1,234", "₹0", and with [sign] "+₹1,234". */
    internal fun rs(x: Double, sign: Boolean = false): String {
        val r = round(x)
        val head = if (r < 0) "−₹" else if (sign && r > 0) "+₹" else "₹"
        return head + String.format(Locale.ENGLISH, "%,.0f", abs(r))
    }
}
