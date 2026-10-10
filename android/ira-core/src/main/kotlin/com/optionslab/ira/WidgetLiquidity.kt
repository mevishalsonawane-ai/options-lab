package com.optionslab.ira

import com.optionslab.engine.orb.LiquidityRules
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * Liquidity 15+5 on the home-screen widget (IraWidget): one compact line - the arm's state (armed with its lots, off, or
 * stopped for today), then its open position (call or put, the P&L before charges, and how far the index is from the
 * index stop and the target, in points) or, when flat, today's paper result (trades and the net after charges) - and,
 * where the widget is tall enough, a second line: the open contract's premium now against the price paid, or (flat and
 * armed, before the last entry) the nearest trigger with room ahead and how far away it is ([LiquidityWhyNot.nearest]).
 *
 * [row]'s `figures` false (the owner has not turned on "Show my P&L on the widget") leaves out every rupee figure - the
 * P&L and the net - exactly as the widget hides the account P&L, and with them the percent change and the word "LIVE";
 * prices and points stay. Words only: nothing here arms,
 * places, closes or changes anything. Pure: no clock, no storage, no network.
 */
object WidgetLiquidity {
    enum class State { ARMED, OFF, STOPPED }
    enum class Tone { GAIN, LOSS, PLAIN }

    /**
     * The open position: its index [underlying], [right] (CE/PE), [symbol], [live] at Zerodha (else paper), [qty], the
     * [entry] premium and [ltp] the premium now (null: not read yet), the broken [level] and the [target] level (null: none
     * recorded), and [index] the last index price the arm read at [indexAt] (null: none; another day's is ignored).
     */
    data class Open(
        val underlying: String, val right: String, val symbol: String, val live: Boolean, val qty: Int, val entry: Double,
        val ltp: Double?, val level: Double?, val target: Double?, val index: Double?, val indexAt: LocalDateTime?,
    )

    /**
     * What the row is made from at [now]: [state] the arm's switch and the day's stop, [lots] its size (null: not read),
     * [paperTrades] today's paper trades (open and closed), [paperNet] the closed ones' net after charges (null: none closed),
     * [open] its open position (null: flat), [reads] the armed books' levels as the arm last read them (empty: not read).
     */
    data class Facts(
        val now: LocalDateTime, val state: State, val lots: Int? = null, val paperTrades: Int = 0, val paperNet: Double? = null,
        val open: Open? = null, val reads: List<LiquidityMap.Read> = emptyList(),
    )

    /** The [line] always shown, the [detail] shown where there is room (null: none), and the [tone] of the line's figure. */
    data class Row(val line: String, val detail: String?, val tone: Tone)

    /** The placed widget's height (dp) from which the second line fits under the rest of the widget. */
    const val DETAIL_MIN_DP = 190

    /** Does the [detail][Row.detail] line fit a widget [heightDp] tall (0: the launcher did not say - then it does not)? */
    fun detailFits(heightDp: Int): Boolean = heightDp >= DETAIL_MIN_DP

    private fun px(x: Double) = String.format(Locale.ENGLISH, "%,.2f", x)
    private fun pts(x: Double) = String.format(Locale.ENGLISH, "%,.0f", abs(x))
    private fun n(x: Double) = String.format(Locale.ENGLISH, "%,.0f", x)
    private fun pct(x: Double) = (if (x < 0) "−" else "+") + String.format(Locale.ENGLISH, "%.1f%%", abs(x) * 100)
    private fun lotsWord(k: Int) = "$k lot" + if (k == 1) "" else "s"
    private fun tone(x: Double): Tone = when { Math.round(x) > 0 -> Tone.GAIN; Math.round(x) < 0 -> Tone.LOSS; else -> Tone.PLAIN }

    private fun stateWords(f: Facts): String = when (f.state) {
        State.ARMED -> "armed" + (f.lots?.takeIf { it > 0 }?.let { " ${lotsWord(it)}" } ?: "")
        State.OFF -> "off"
        State.STOPPED -> "stopped for today"
    }

    /** Today's paper result: "3 paper trades +₹1,240 net" (no rupees without [figures]), or null for none. */
    private fun result(f: Facts, figures: Boolean): String? {
        if (f.paperTrades <= 0) return null
        val net = f.paperNet?.takeIf { figures && it.isFinite() }?.let { " ${LiquidityOpen.rupees(it)} net" } ?: ""
        return "${f.paperTrades} paper trade${if (f.paperTrades == 1) "" else "s"}$net"
    }

    /** The index today (an earlier day's last read is no price for now). */
    private fun indexNow(o: Open, now: LocalDateTime): Double? = o.index?.takeIf { o.indexAt == null || o.indexAt.toLocalDate() == now.toLocalDate() }

    /** "stop 42 · target 65 pts" from the index now, each said as passed or reached when it is. */
    private fun distances(o: Open, now: LocalDateTime): String {
        val index = indexNow(o, now) ?: return "index not read"
        // (words, a figure in points): every part a figure - "stop 42 · target 65 pts"; else each figure says its unit.
        val parts = ArrayList<Pair<String, Boolean>>()
        o.level?.let { lv ->
            val d = LiquidityOpen.pointsToIndexStop(o.right, lv, o.underlying, index)
            parts += if (d < 0) "stop passed" to false else "stop ${pts(d)}" to true
        }
        val t = o.target
        parts += if (t == null) "no target" to false
        else LiquidityOpen.pointsToTarget(o.right, t, index).let { d -> if (d <= 0) "target reached" to false else "target ${pts(d)}" to true }
        return if (parts.all { it.second }) parts.joinToString(" · ") { it.first } + " pts"
        else parts.joinToString(" · ") { (w, fig) -> if (fig) "$w pts" else w }
    }

    /** The nearest trigger with room ahead, compact: "Next: BankNifty 5-min close above 54,180 · 60 pts away", or null. */
    fun nextLine(reads: List<LiquidityMap.Read>): String? = LiquidityWhyNot.nearest(reads)?.let { (r, s) ->
        val edge = s.trigger?.edge ?: return null
        val price = r.price ?: return null
        val d = s.side * (edge - price)
        if (d < 0) return null
        "Next: ${LiquidityMap.indexName(r.underlying)} ${r.minutes}-min close ${if (s.side > 0) "above" else "below"} ${n(edge)} · ${pts(d)} pts away"
    }

    /** How old a read may be while the market is open before its row is hidden ([fresh]). */
    const val STALE_MINUTES = 10L

    /**
     * Is the read [f] still good to show at [now]? Only one from today; while the market is open ([marketOpen]), only one
     * at most [STALE_MINUTES] old. An older read hides the row (a stale position or result is never shown as current).
     */
    fun fresh(f: Facts, now: LocalDateTime, marketOpen: Boolean): Boolean {
        if (f.now.toLocalDate() != now.toLocalDate()) return false
        return !marketOpen || !f.now.isBefore(now.minusMinutes(STALE_MINUTES))
    }

    /** The widget's Liquidity row for [f]; rupee figures only with [figures]. */
    fun row(f: Facts, figures: Boolean): Row {
        val o = f.open
        if (o != null) {
            val gross = o.ltp?.let { (it - o.entry) * o.qty }
            val money = gross?.takeIf { figures }?.let { " ${LiquidityOpen.rupees(it)}" } ?: if (figures) "" else " open"
            // Without figures the word "LIVE" and the percent change are left out too (they say how the account is doing).
            val line = "Liquidity: ${if (o.live && figures) "LIVE " else ""}${o.right.uppercase()}$money · ${distances(o, f.now)}"
            val ltp = o.ltp
            val detail = if (ltp == null) "${o.symbol} bought at ${px(o.entry)} · no price yet"
            else "${o.symbol} ${px(ltp)} vs ${px(o.entry)}" + if (figures) " (${pct((ltp - o.entry) / o.entry)})" else ""
            return Row(line, detail, gross?.takeIf { figures }?.let(::tone) ?: Tone.PLAIN)
        }
        val res = result(f, figures)
        val line = "Liquidity: ${stateWords(f)}" + when {
            res != null -> " · $res"
            f.state == State.ARMED -> " · no trades yet"
            else -> ""
        }
        val detail = if (f.state == State.ARMED && !f.now.toLocalTime().isAfter(LiquidityRules.LAST_ENTRY)) nextLine(f.reads) else null
        val net = f.paperNet?.takeIf { figures && f.paperTrades > 0 && it.isFinite() }
        return Row(line, detail, net?.let(::tone) ?: Tone.PLAIN)
    }
}
