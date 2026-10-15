package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * The paper arms whose rules PASSED every research gate (Boss, 15 Oct 2026: "Both" - paper arms for the rules that passed, a
 * shadow Watchlist for the near misses). Under CLAUDE.md's PAPER rule a passed idea may act on paper at once: each arm is
 * switched on once by the update, logged in Autopilot's change timeline as "PAPER_ARM_ON", undone in one tap, shown to Boss,
 * and never carries over to live by itself (these arms have no live path at all). The three:
 *
 *  - [DonchianArm] "R51 Donchian 20" (research R51, R44-donchian20-RT, Amendment A);
 *  - [OovArm] "R52 Open outside value" (research R52, H37-10 grid point g17, Amendment 2), with R57's Solo layer as its stop;
 *  - [Solo2] "Solo 2.0" (research R57, the A2 meta-labeller): Jarvis's own second paper Solo, next to Solo (midday).
 *
 * This file holds what they share: the session's minute grid ([Session], [DayBars]), the 5-minute bars the research built
 * ([bars5]), the strike rule ([step], [itmStrike]), the premium exits as the research's engine ran them ([PremiumExits],
 * [exitOnBars]) and the 40-trade forward bar ([ForwardBar]). Pure: no clock, no network, no storage, no orders.
 */
object PassedArms {
    /** What every row, notice and Jarvis answer of these arms says first. */
    const val PASSED = "Passed research (R51/R52/R57) — paper only"
    /** Signal source (CLAUDE.md, 14 Oct: stated for every rule). */
    const val SIGNAL_INDEX = "Signal: the index's own bars (the default); the option is only what is bought"

    /** The index's strike step (R51 / R52 / R57 `STEP`): NIFTY 50, BANKNIFTY 100, FINNIFTY 50, MIDCPNIFTY 25, SENSEX 100. */
    fun step(underlying: String): Int = when (underlying) {
        "NIFTY", "FINNIFTY" -> 50
        "MIDCPNIFTY" -> 25
        else -> 100
    }

    /** ATM of [spot] (the nearest strike, half up) and [money] steps in the money: a call below it, a put above ([side] +1 / -1). */
    fun itmStrike(underlying: String, side: Int, spot: Double, money: Int): Int {
        val st = step(underlying)
        return OrbRules.atmStrike(spot, st) - side * money * st
    }

    // ---- the minute grid ------------------------------------------------------------------------------------------------

    /** The NSE / BSE minute grid of the research: row r = 09:15 + r minutes, 375 rows (09:15 .. 15:29). */
    object Session {
        const val N = 375
        val OPEN: LocalTime = LocalTime.of(9, 15)
        fun row(t: LocalTime): Int = t.hour * 60 + t.minute - (OPEN.hour * 60 + OPEN.minute)
        fun row(h: Int, m: Int): Int = row(LocalTime.of(h, m))
        fun time(row: Int): LocalTime = OPEN.plusMinutes(row.toLong())
    }

    /**
     * One session's 1-minute bars on the research's grid (NaN where a minute has no bar). A row counts only when both its open
     * and its close are known (the lake's flag "ok"); its high and low then fall back to the open and close when missing.
     */
    class DayBars(val date: LocalDate, val o: DoubleArray, val h: DoubleArray, val l: DoubleArray, val c: DoubleArray) {
        /** The last row with a bar (-1: none). */
        val lastRow: Int get() { for (r in Session.N - 1 downTo 0) if (!c[r].isNaN()) return r; return -1 }
        /** The number of rows with a bar. */
        val count: Int get() = c.count { !it.isNaN() }
        /** The last finite close at or before [row] (NaN: none). */
        fun closeAtOrBefore(row: Int): Double { for (r in minOf(row, Session.N - 1) downTo 0) if (!c[r].isNaN()) return c[r]; return Double.NaN }
        /** The first finite open (NaN: none). */
        val firstOpen: Double get() = o.firstOrNull { !it.isNaN() } ?: Double.NaN
        /** The session's high, low and last close (NaN when it has no bar). */
        val high: Double get() = h.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN
        val low: Double get() = l.filter { !it.isNaN() }.minOrNull() ?: Double.NaN
        val lastClose: Double get() = closeAtOrBefore(Session.N - 1)

        /** The same day with only the rows up to [row] (the rest NaN): what was known when that minute closed. */
        fun through(row: Int): DayBars {
            fun cut(a: DoubleArray) = DoubleArray(Session.N) { if (it <= row) a[it] else Double.NaN }
            return DayBars(date, cut(o), cut(h), cut(l), cut(c))
        }

        companion object {
            /** [bars] (bar start times; any day) laid on [date]'s grid; a minute seen twice keeps its first bar. */
            fun of(date: LocalDate, bars: List<Bar>): DayBars {
                val o = DoubleArray(Session.N) { Double.NaN }; val h = o.copyOf(); val l = o.copyOf(); val c = o.copyOf()
                val seen = BooleanArray(Session.N)
                for (b in bars) {
                    if (b.start.toLocalDate() != date) continue
                    val r = Session.row(b.start.toLocalTime())
                    if (r !in 0 until Session.N || seen[r]) continue
                    seen[r] = true
                    if (!b.open.isFinite() || !b.close.isFinite() || b.open <= 0 || b.close <= 0) continue
                    o[r] = b.open; c[r] = b.close
                    h[r] = if (b.high.isFinite()) b.high else maxOf(b.open, b.close)
                    l[r] = if (b.low.isFinite()) b.low else minOf(b.open, b.close)
                }
                return DayBars(date, o, h, l, c)
            }
        }
    }

    /** One 5-minute bar of a session: bar [b] covers rows 5b .. 5b + 4 and closes when row 5b + 4 does (at 09:20 + 5b). */
    data class Bar5(val date: LocalDate, val b: Int, val open: Double, val high: Double, val low: Double, val close: Double) {
        /** The minute the bar closes (its next bar's open: where the research filled). */
        val closesAt: LocalDateTime get() = date.atTime(Session.time(5 * b + 5))
        /** The row the research filled at: the open of the minute after the bar (5(b + 1)). */
        val entryRow: Int get() = 5 * (b + 1)
    }

    /**
     * R51's `bars5` (research/hunt/r51/fam_r44.py): the 5-minute bars of [d] whose rows up to [lastRow] are known, each
     * bar with at least one close: open = the open of its first minute with a close, high / low = the extremes of its
     * minutes, close = its last close. A bar is returned only when its last minute is in ([lastRow] >= 5b + 4).
     */
    fun bars5(d: DayBars, lastRow: Int = Session.N - 1): List<Bar5> {
        val out = ArrayList<Bar5>()
        for (b in 0 until Session.N / 5) {
            if (5 * b + 4 > lastRow) break
            val rows = (5 * b until 5 * b + 5).filter { !d.c[it].isNaN() }
            if (rows.isEmpty()) continue
            out += Bar5(d.date, b, d.o[rows.first()], rows.maxOf { d.h[it] }, rows.minOf { d.l[it] }, d.c[rows.last()])
        }
        return out
    }

    // ---- the premium exits (the research engine's order) ----------------------------------------------------------------

    /**
     * Exits on the option's own premium, as R51's engine (research/hunt/r51/engine.py `simulate`) ran them, checked minute by
     * minute in this order: the resting stop / lock on the minute's LOW (the stop first), the limit target on its HIGH, then
     * the time stop at the close of the [tmaxMinutes]-th minute, then the square-off. The lock ([steps]: once the peak is
     * `trigger` above the fill, at least `keep` above it is locked) is set by a minute's high and applies from the next minute.
     * [stop] / [target] are fractions of the fill (0.15 = -15% / +15%); null: none.
     */
    data class PremiumExits(val stop: Double?, val target: Double?, val tmaxMinutes: Int?, val steps: List<Pair<Double, Double>> = emptyList(),
                            val squareOff: LocalTime)

    enum class ExitKind(val why: String) { STOP("stop"), LOCK("lock"), TARGET("target"), TIME("time"), SQUARE_OFF("squareoff") }

    /** An exit decided: why, and the price the research would have booked it at (a stop's level or a gap's open; the target). */
    data class Exit(val kind: ExitKind, val at: LocalDateTime, val price: Double)

    /** The walk's state for an open position: its best price so far and the level its lock holds (null: no lock yet). */
    data class Walk(val exit: Exit?, val peak: Double, val lock: Double?)

    private const val EPS = 1e-9

    /** The stop's level for a fill at [entry] (null: none). */
    fun stopLevel(entry: Double, x: PremiumExits): Double? = x.stop?.let { entry * (1 - it) }
    /** The target's level for a fill at [entry] (null: none). */
    fun targetLevel(entry: Double, x: PremiumExits): Double? = x.target?.let { entry * (1 + it) }

    /** The minute the time stop sells at: [tmaxMinutes] after the entry minute (the close of its last allowed minute). */
    fun timeExitAt(entryMinute: LocalDateTime, x: PremiumExits): LocalDateTime? = x.tmaxMinutes?.let { entryMinute.plusMinutes(it.toLong()) }

    /**
     * The exit of a position filled at [entry] in the minute starting [entryMinute], from the option's closed 1-minute [bars]
     * (bar start times; read from the entry minute on), then [ltp] (the price now, null: none) at [now]. Read from the bars
     * alone (plus the price now), so a restart in the middle of a trade finds the same answer.
     */
    fun exitOnBars(entry: Double, entryMinute: LocalDateTime, bars: List<Bar>, ltp: Double?, now: LocalDateTime, x: PremiumExits): Walk {
        if (!(entry > 0)) return Walk(null, entry, null)
        val stop = stopLevel(entry, x)
        val tgt = targetLevel(entry, x)
        val tend = x.tmaxMinutes?.let { entryMinute.plusMinutes((it - 1).toLong()) }
        val sq = entryMinute.toLocalDate().atTime(x.squareOff)
        var peak = entry
        var lock: Double? = null
        val done = bars.filter { !it.start.isBefore(entryMinute) && !it.start.plusMinutes(1).isAfter(now) && it.start.isBefore(sq) }
            .sortedBy { it.start }.distinctBy { it.start }
        for (b in done) {
            if (!b.low.isFinite() || !b.high.isFinite()) continue
            val lv = maxOf(stop ?: -1.0, lock ?: -1.0)
            if (lv > 0 && b.low <= lv + EPS) {
                val px = if (b.open.isFinite() && b.open < lv) b.open else lv
                val kind = if (lock != null && lock >= (stop ?: -1.0)) ExitKind.LOCK else ExitKind.STOP
                return Walk(Exit(kind, b.start, px), peak, lock)
            }
            if (tgt != null && b.high >= tgt - EPS) return Walk(Exit(ExitKind.TARGET, b.start, tgt), peak, lock)
            if (b.high > peak) peak = b.high
            val g = peak / entry - 1
            for ((trig, keep) in x.steps) if (g >= trig - 1e-12) { val l = entry * (1 + keep); if (lock == null || l > lock) lock = l }
            if (tend != null && b.start == tend) return Walk(Exit(ExitKind.TIME, b.start.plusMinutes(1), b.close), peak, lock)
        }
        // Between bars: the price now against the same levels, then the clock.
        val lv = maxOf(stop ?: -1.0, lock ?: -1.0)
        if (ltp != null && ltp > 0) {
            if (lv > 0 && ltp <= lv + EPS) return Walk(Exit(if (lock != null && lock >= (stop ?: -1.0)) ExitKind.LOCK else ExitKind.STOP, now, ltp), peak, lock)
            if (tgt != null && ltp >= tgt - EPS) return Walk(Exit(ExitKind.TARGET, now, tgt), peak, lock)
        }
        val last = ltp ?: done.lastOrNull()?.close ?: entry
        tend?.let { if (!now.isBefore(it.plusMinutes(1))) return Walk(Exit(ExitKind.TIME, now, last), peak, lock) }
        if (!now.isBefore(sq)) return Walk(Exit(ExitKind.SQUARE_OFF, now, last), peak, lock)
        return Walk(null, peak, lock)
    }

    /** A target is booked at the target at most (a gap through it never counts as better: the research's limit fill). */
    fun booked(kind: ExitKind, fill: Double, target: Double?): Double = if (kind == ExitKind.TARGET && target != null) minOf(fill, target) else fill

    // ---- the 40-trade forward bar (R51 and R52) ---------------------------------------------------------------------------

    /**
     * The bar set in advance for the two rule arms (R51's write-up: "review after 40 paper trades; stop it if net < 0 or below
     * the cost of twice the spread"): judged ONCE, when the record reaches [TRADES] closed paper trades. It fails when the net
     * after charges is below 0, or below the spread and slippage those trades paid (so at twice the spread it would lose).
     * A failed bar switches the arm off by itself (that only lowers risk; Boss switches it back on). After Boss switches it
     * back on ([base] at or past [TRADES]) the bar is not judged again.
     */
    object ForwardBar {
        const val TRADES = 40

        enum class Verdict { RUNNING, PASSED, FAILED_NET, FAILED_SPREAD }

        /** The record judged: closed [trades], [net] after charges, the [spread] (and slippage) they paid, [wins]. */
        data class Check(val verdict: Verdict, val trades: Int, val net: Double, val spread: Double, val wins: Int) {
            /** The net had the spread and slippage been twice what they were. */
            val netAtTwiceSpread: Double get() = net - spread
        }

        /** [nets] (after charges) and [spreads] (each trade's spread and slippage, rupees) of the closed trades, oldest first. */
        fun judge(nets: List<Double>, spreads: List<Double>, base: Int = 0): Check {
            val n = nets.size
            val net = nets.sum()
            val sp = spreads.take(n).sum()
            val v = when {
                n < TRADES || base >= TRADES -> Verdict.RUNNING
                net < 0 -> Verdict.FAILED_NET
                net - sp < 0 -> Verdict.FAILED_SPREAD
                else -> Verdict.PASSED
            }
            return Check(v, n, net, sp, nets.count { it > 0 })
        }

        fun switchOff(v: Verdict): Boolean = v == Verdict.FAILED_NET || v == Verdict.FAILED_SPREAD

        /** The forward-test line: "x of 40", net, the spread paid. */
        fun line(c: Check): String =
            "Forward test: ${minOf(c.trades, TRADES)} of $TRADES paper trades" + (if (c.trades > TRADES) " (${c.trades} in all)" else "") +
                ", net ${rs(c.net)} after charges, at twice the spread ${rs(c.netAtTwiceSpread)}"

        /** The bar in words, for each row. */
        const val BAR = "The bar (set in advance): at 40 paper trades it switches itself off if the net is below ₹0 or below the cost " +
            "of twice the spread; switching it back on is your choice"
    }

    /** The spread and slippage a round trip paid, rupees: the fills against the prices seen when each order went in. */
    fun spreadPaid(qty: Int, entryFill: Double, entryRef: Double?, exitFill: Double, exitRef: Double?, halfSpread: Double): Double {
        val inn = entryRef?.takeIf { it > 0 }?.let { entryFill - it } ?: (entryFill * halfSpread)
        val out = exitRef?.takeIf { it > 0 }?.let { it - exitFill } ?: (exitFill * halfSpread)
        return maxOf(0.0, inn) * qty + maxOf(0.0, out) * qty
    }

    fun rs(x: Double): String = (if (x < 0) "−₹" else "₹") + String.format(Locale.ENGLISH, "%,.0f", abs(x))

    /** Rounded down to the 0.05 tick (a limit that never pays more than meant). */
    fun floorTick(x: Double): Double = Math.round(floor(x / 0.05 + 1e-6) * 0.05 * 100) / 100.0
}
