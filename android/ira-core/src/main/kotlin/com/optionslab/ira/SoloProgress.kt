package com.optionslab.ira

import com.optionslab.engine.orb.SoloMidday
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * Solo (midday)'s forward test at a glance, for its card (Jarvis settings → Solo): how far along the 60 trades it is, the net
 * so far after charges (total and per lot) and the win rate, the drawdown from its baseline against the −₹25,000 line it
 * switches itself off beyond (how far from the line), whether it switched itself off, today's state in one line ([today],
 * in [SoloDay]'s words), the research line ([SoloDay.research]) and the points of a sparkline of the running net a trade.
 *
 * Words and numbers from Solo's own records only: nothing here switches, places, closes or changes anything. Pure: no
 * clock, no storage, no network.
 */
object SoloProgress {
    /** The forward test's length (60 closed trades) and the drawdown line it switches itself off beyond (₹25,000). */
    val TARGET: Int get() = SoloMidday.FORWARD_TRADES
    val LINE: Double get() = SoloMidday.FORWARD_MAX_DRAWDOWN

    /** One sparkline point: [x] 0..1 left to right, [y] 0..1 bottom to top. */
    data class Point(val x: Float, val y: Float)

    /** The sparkline: its [points] (the running net, from 0 before the first trade) and where 0 sits ([zero], 0..1 up). */
    data class Spark(val points: List<Point>, val zero: Float)

    /**
     * The glance: [trades] closed (the forward test's), [progress] 0..1 of the 60, [net] after charges (and [perLot],
     * [perTrade]), [wins] and [winRate]; [drawdown] now and [worst] since the baseline (<= 0, [sinceOn]: since Boss switched it
     * back on), [room] left before the line and [toLine] 0..1 of the way to it; the [verdict]; [switchedOff] when Solo switched
     * itself off; the running net a trade ([totals], from 0) and its [spark] (null under 2 trades); and each line's words.
     */
    data class Glance(
        val trades: Int, val progress: Float, val progressLine: String,
        val net: Double, val perLot: Double, val perTrade: Double?, val wins: Int, val winRate: Double?, val netLine: String,
        val drawdown: Double, val worst: Double, val room: Double, val toLine: Float, val sinceOn: Boolean, val drawdownLine: String,
        val verdict: SoloMidday.Verdict, val verdictLine: String?,
        val switchedOff: Boolean, val offLine: String?,
        val totals: List<Double>, val spark: Spark?,
        val research: String,
    )

    /** "+₹1,200", "−₹420", "₹0". */
    fun signed(x: Double): String {
        val r = Math.round(x).toDouble()
        return (if (r > 0) "+" else "") + SoloMidday.rs(if (r == 0.0) 0.0 else r)
    }

    private fun pct(x: Double) = String.format(Locale.ENGLISH, "%.0f%%", x * 100)
    private fun s(n: Int) = if (n == 1) "" else "s"

    /** "12 of 60 forward-test trades" (past 60: "60 of 60 forward-test trades (64 in all)"). */
    fun progressLine(trades: Int): String =
        "${minOf(trades, TARGET)} of $TARGET forward-test trades" + (if (trades > TARGET) " ($trades in all)" else "")

    /** The share of the 60 done, 0..1. */
    fun progress(trades: Int): Float = (minOf(maxOf(trades, 0), TARGET).toFloat() / TARGET)

    /** The drawdown now (<= 0) of [nets] since [b]: the equity from [SoloMidday.Baseline.from] on, the peak from [SoloMidday.Baseline.peak]. */
    fun drawdownNow(nets: List<Double>, b: SoloMidday.Baseline): Double {
        var eq = nets.take(b.from).sum()
        var peak = b.peak
        for (n in nets.drop(b.from)) { eq += n; peak = maxOf(peak, eq) }
        return minOf(0.0, eq - peak) + 0.0   // never −0.0
    }

    /** The running net a trade, from 0 before the first. */
    fun totals(nets: List<Double>): List<Double> = nets.runningFold(0.0) { a, n -> a + n }

    /** The sparkline of [totals] (null under 2 trades, i.e. under 3 points): x evenly spread, y scaled to the range with 0 in it. */
    fun spark(totals: List<Double>): Spark? {
        if (totals.size < 3 || totals.any { !it.isFinite() }) return null
        val lo = minOf(0.0, totals.min())
        val hi = maxOf(0.0, totals.max())
        val h = hi - lo
        fun y(v: Double): Float = if (h <= 0.0) 0.5f else ((v - lo) / h).toFloat()
        val last = totals.size - 1
        return Spark(totals.mapIndexed { i, v -> Point(i.toFloat() / last, y(v)) }, y(0.0))
    }

    /** The glance from the forward test's closed trades' [nets] in order, its [base], Solo's switch [on] and [paused] (why it switched itself off). */
    fun of(nets: List<Double>, base: SoloMidday.Baseline = SoloMidday.Baseline(), on: Boolean, paused: String?): Glance {
        val c = SoloMidday.judge(nets, base)
        val r = c.record
        val now = drawdownNow(nets, base)
        val worst = c.drawdown
        val room = maxOf(0.0, LINE + now)
        val toLine = if (now >= 0.0) 0f else (-now / LINE).toFloat().coerceIn(0f, 1f)
        val sinceOn = base.from > 0
        val perLot = r.net / SoloMidday.LOTS
        val winRate = if (r.trades == 0) null else r.wins.toDouble() / r.trades
        val netLine = if (r.trades == 0) "No closed forward-test trade yet: the net, the win rate and the drawdown start with the first." else
            "Net so far after charges: ${signed(r.net)} total, ${signed(perLot)} per lot (${SoloMidday.LOTS} lot a trade) · " +
                "${signed(r.perTrade ?: 0.0)} a trade · won ${r.wins} of ${r.trades} (${pct(winRate ?: 0.0)})"
        val from = if (sinceOn) "since Boss switched it back on" else "since its start"
        val lineRs = SoloMidday.rs(-LINE)
        val drawdownLine = when {
            c.verdict == SoloMidday.Verdict.FAILED_DRAWDOWN ->
                "Drawdown ${SoloMidday.rs(worst)} $from went past the $lineRs line it switches itself off beyond."
            now == 0.0 && worst == 0.0 -> "No drawdown $from: ${SoloMidday.rs(room)} from the $lineRs switch-off line."
            else -> "Drawdown ${SoloMidday.rs(now)} from its best $from · ${SoloMidday.rs(room)} from the $lineRs switch-off line" +
                (if (worst < now) " (worst ${SoloMidday.rs(worst)})" else "") + "."
        }
        val verdictLine = when (c.verdict) {
            SoloMidday.Verdict.PASSED -> "It passed the bar set in advance after $TARGET trades - still paper only, not proven."
            SoloMidday.Verdict.FAILED_NET -> "After $TARGET trades it made ₹0 or less a trade: the net bar failed."
            else -> null
        }
        val switchedOff = !on && paused != null
        val offLine = if (!switchedOff) null else {
            val why = when (c.verdict) {
                SoloMidday.Verdict.FAILED_DRAWDOWN -> "it went more than ${SoloMidday.rs(LINE)} below its best"
                SoloMidday.Verdict.FAILED_NET -> "after $TARGET trades it made ₹0 or less a trade"
                else -> "the bar set in advance failed"
            }
            "Switched itself off: $why. Switching it back on is Boss's switch - the drawdown then counts from that moment."
        }
        val totals = totals(nets)
        return Glance(r.trades, progress(r.trades), progressLine(r.trades), r.net, perLot, r.perTrade, r.wins, winRate, netLine,
            now, worst, room, toLine, sinceOn, drawdownLine, c.verdict, verdictLine, switchedOff, offLine, totals, spark(totals),
            SoloDay.research())
    }

    /**
     * True for a line of [SoloMidday.card] the glance already shows (the "x of 60 trades of the forward test" line and the
     * research line): the card leaves it out under the glance.
     */
    fun shownAtAGlance(line: String): Boolean = line == SoloMidday.RESEARCH || line.endsWith(" trades of the forward test.")

    private fun hhmm(t: LocalDateTime) = String.format(Locale.ENGLISH, "%02d:%02d", t.hour, t.minute)
    private fun list(xs: List<String>): String = when (xs.size) {
        0 -> ""; 1 -> xs[0]; 2 -> "${xs[0]} and ${xs[1]}"
        else -> xs.dropLast(1).joinToString(", ") + " and " + xs.last()
    }

    /**
     * Today's state in one line at [now]: "Decides at 12:00 today", "Deciding now", "Decided: traded X - still open / net",
     * "Decided: no trade - why" (from today's [record], in [SoloDay]'s words), or why there was no decision. [trade] today's
     * Solo (midday) trade (null: none), [on] its switch, [since] when the app's record of the day began.
     */
    fun today(now: LocalDateTime, tradingDay: Boolean, on: Boolean, record: SoloDay.Record?, trade: TodayGlance.SoloTrade?,
              since: LocalDateTime? = null): String {
        val day = now.toLocalDate()
        val t = now.toLocalTime()
        val r = record?.takeIf { it.day == day }
        if (trade != null) return "Decided: traded ${trade.symbol} - " +
            (if (trade.open) "still open." else trade.net?.let { "net ${signed(it)} after charges." } ?: "closed.")
        r?.bought?.let { return "Decided: bought ${it.symbol} on paper at ${hhmm(it.at)}." }
        if (!tradingDay) return "No session today - it decides only on a trading day, once, at 12:00."
        if (t.isBefore(SoloMidday.DECIDE_AT)) return if (on) "Decides at 12:00 today (orders only until 12:03)." else
            "Off: it will not decide at 12:00 today."
        if (SoloMidday.inEntryWindow(t) && r?.decidedAt == null && r?.late == null) return if (on) "Deciding now (12:00-12:03)." else
            "Off: no decision today."
        if (r != null) {
            val decided = r.decidedAt
            if (r.late != null) return "Decided: no trade - the 12:00-12:03 window closed before the order could go in."
            if (decided != null) {
                val signals = r.decisions.mapNotNull { it.signal }
                return "Decided: no trade - " + when {
                    signals.isEmpty() -> "none of ${list(r.decisions.map { it.underlying }.ifEmpty { SoloMidday.UNDERLYINGS })} met both rules " +
                        "(${SoloMidday.MOVE_ATR} ATR from the open and a close in the outer quarter)."
                    r.passes.isNotEmpty() -> "set aside: " + r.passes.joinToString("; ") { SoloDay.passWords(it, r.thin) } + "."
                    else -> "every index that signalled was set aside."
                }
            }
            r.held?.let { return "No trade today - held back at ${hhmm(it.at)}: ${SoloDay.heldWords(it.why)}." }
            r.waited?.let { (at, us) -> return "No trade today - at ${hhmm(at)} it was waiting for the 11:59 minute or the daily ATR of ${list(us)}." }
        }
        if (!on) return "Off: no decision today."
        val started = since?.takeIf { it.toLocalDate() == day }
        return when {
            started != null && !started.isBefore(day.atTime(SoloMidday.LAST_ENTRY.plusMinutes(1))) ->
                "No trade today - the app started at ${hhmm(started)}, after the 12:00-12:03 window."
            else -> "No trade today - no 12:00 decision is on record (it decides while the app's market watch runs 12:00-12:03)."
        }
    }
}
