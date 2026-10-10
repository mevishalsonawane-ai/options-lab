package com.optionslab.ira

import com.optionslab.engine.orb.Bar
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * Liquidity 15+5's trade notifications in words (Boss, 06 Oct 2026: "so I understand each trade from the notification
 * alone"): the entry (book, side, strike, size, fill, the broken level, the target and the room to it, the 15% stop, the
 * index stop and the time stop), the exit (why in words, the prices, the P&L after charges and per lot, how long it was
 * held, today's tally) and a skipped break (too little room to the next level). With [hide] (the lock-screen "hide
 * figures" switch) no option price and no rupee amount is said; the index levels, which anyone can see, stay.
 *
 * [chart] lays out the expanded notification's mini-chart (candles, the broken level, the target, the entry and exit
 * markers) in pixels; the app only paints it. Information only: nothing here decides, places or changes a trade. Pure.
 */
object LiquidityNotice {
    /** What a notification says: [title], the collapsed [line] and the expanded [lines]. */
    data class Said(val title: String, val line: String, val lines: List<String>) {
        val body: String get() = lines.joinToString("\n")
    }

    /** One book of the arm: BANKNIFTY 5-min, BANKNIFTY 15-min, FINNIFTY 30-min, FINNIFTY 5-min, MIDCPNIFTY 15-min, MIDCPNIFTY 5-min. */
    data class Book(val underlying: String, val minutes: Int) {
        val label: String get() = "$underlying $minutes-min"
    }

    data class Entry(
        val book: Book, val live: Boolean, val right: String, val strike: Double?, val symbol: String,
        val qty: Int, val lots: Double?, val fill: Double, val time: LocalDateTime,
        /** The level the break took, and the next level ahead (the target) or null. */
        val level: Double, val target: Double?,
        /** The deciding bar's close (the room is measured from it, as the arm's room filter does); null: from [level]. */
        val close: Double?,
        /** The option's resting stop (15% under the fill) and the index stop's points (BANKNIFTY 30, FINNIFTY 15, MIDCPNIFTY 8). */
        val stop: Double?, val indexStopPoints: Double,
        val timeStopMinutes: Long = 20, val timeStopGain: Double = 0.05,
    ) {
        val side: Int get() = if (right == "PE") -1 else 1
    }

    data class Exit(
        val book: Book, val live: Boolean, val right: String, val strike: Double?, val symbol: String,
        val qty: Int, val lots: Double?, val entry: Double, val exit: Double, val entryTime: LocalDateTime, val exitTime: LocalDateTime,
        /** The arm's reason code ("stop", "index_stop", "time_stop", "next_liquidity", "new_liquidity", "failed_break", ...). */
        val why: String, val level: Double?, val target: Double?, val indexStopPoints: Double,
        /** The whole trade's P&L before charges, and its charges (both sides). */
        val gross: Double, val charges: Double,
        val tally: Tally,
    ) {
        val side: Int get() = if (right == "PE") -1 else 1
        val net: Double get() = gross - charges
    }

    /** Today's closed Liquidity 15+5 trades (this one included): how many, how many made money after charges, the net. */
    data class Tally(val trades: Int, val won: Int, val net: Double)

    data class Skip(val book: Book, val level: Double, val room: Double, val needs: Double)

    // ---- the words -------------------------------------------------------------------------------------------------

    fun entry(e: Entry, hide: Boolean): Said {
        val what = contract(e.book.underlying, e.strike, e.right, e.symbol)
        val title = "Liquidity 15+5 bought $what · ${venue(e.live)}"
        val size = lotsWords(e.lots, e.qty)
        val first = "${e.book.label} · $size" + (if (hide) "" else " @ ${px(e.fill)}")
        val room = e.target?.let { e.side * (it - (e.close ?: e.level)) }
        val levels = "Broke ${lvl(e.level)} · " + (e.target?.let { "target ${lvl(it)} (${pts(room!!)} pts room)" } ?: "no level ahead (no target)")
        val indexStop = e.level - e.side * e.indexStopPoints
        val stops = "Stop: " + (if (hide || e.stop == null) "option 15% under the fill" else "option ${px(e.stop)} (−15%)") +
            " · index ${lvl(indexStop)} (${pts(e.indexStopPoints)} pts back ${if (e.side > 0) "below" else "above"})"
        val by = e.time.plusMinutes(e.timeStopMinutes)
        val gain = "%.0f".format(Locale.ENGLISH, e.timeStopGain * 100)
        val time = "Time stop: out at ${hm(by)} unless up $gain%" + (if (hide) "" else " (${px(e.fill * (1 + e.timeStopGain))})")
        return Said(title, first, listOf(first, levels, stops, time))
    }

    fun exit(x: Exit, hide: Boolean): Said {
        val what = contract(x.book.underlying, x.strike, x.right, x.symbol)
        val title = "Liquidity 15+5 sold $what · ${short(x.why)}"
        val reason = reason(x)
        val held = "held ${duration(Duration.between(x.entryTime, x.exitTime))}"
        val prices = if (hide) "${x.book.label} · ${venue(x.live)} · $held"
            else "${x.book.label} · ${venue(x.live)} · ${px(x.entry)} → ${px(x.exit)} · $held"
        val pnl = if (hide) "Result: ${if (x.net > 0.005) "a gain" else if (x.net < -0.005) "a loss" else "flat"} after charges"
            else "P&L ${signed(x.net)} after ${rupees(x.charges)} charges" + (x.lots?.takeIf { it > 0 }?.let { " (${signed(x.net / it)} a lot)" } ?: "")
        val tally = "Today's Liquidity: ${x.tally.trades} trade${if (x.tally.trades == 1) "" else "s"}, ${x.tally.won} won" +
            (if (hide) "" else ", net ${signed(x.tally.net)}")
        return Said(title, reason, listOf(reason, prices, pnl, tally))
    }

    /** "Skipped: BankNifty 5-min break of 54,180 — only 22 pts to the next level (needs 30)". No rupee figure in it. */
    fun skip(s: Skip): Said {
        val text = "Skipped: ${LiquidityMap.indexName(s.book.underlying)} ${s.book.minutes}-min break of ${lvl(s.level)} — only " +
            "${pts(s.room)} pts to the next level (needs ${pts(s.needs)})"
        return Said("Liquidity 15+5 skipped a break", text, listOf(text, "Information only: the arm stays flat and waits for the next break."))
    }

    /** The exit reason in a word or two: the title's. */
    fun short(why: String): String = when (why) {
        "stop" -> "stop"
        "index_stop" -> "index stop"
        "time_stop" -> "time stop"
        "next_liquidity" -> "next liquidity"
        "new_liquidity" -> "new liquidity"
        "failed_break" -> "failed break"
        "session_end", "backstop_square_off" -> "end of day"
        "operator_stop" -> "stopped for today"
        "closed_by_you" -> "closed by you"
        else -> why.replace('_', ' ')
    }

    /** The exit reason in a sentence. */
    fun reason(x: Exit): String {
        val below = if (x.side > 0) "below" else "above"
        val lv = x.level?.let { lvl(it) }
        return when (x.why) {
            "stop" -> "Stop: the option fell 15% under the price paid"
            "index_stop" -> "Index stop: ${x.book.underlying} traded ${pts(x.indexStopPoints)} pts back $below ${lv ?: "the broken level"}"
            "time_stop" -> "Time stop: not up 5% 20 minutes after the entry"
            "next_liquidity" -> "Target: the index reached the next level" + (x.target?.let { ", ${lvl(it)}" } ?: "")
            "new_liquidity" -> "New liquidity: a fresh level formed on the trade's side"
            "failed_break" -> "Failed break: a bar closed back $below ${lv ?: "the broken level"}"
            "session_end" -> "End of day: sold at 15:10"
            "backstop_square_off" -> "End of day: squared off by the account"
            "operator_stop" -> "You stopped the strategies for today"
            "closed_by_you" -> "Closed by you"
            else -> "Exit: ${x.why.replace('_', ' ')}"
        }
    }

    // ---- the mini-chart ----------------------------------------------------------------------------------------------

    /** One candle in pixels: its centre [x], the wick from [high] to [low], the body from [top] to [bottom]. */
    data class Candle(val x: Float, val high: Float, val low: Float, val top: Float, val bottom: Float, val up: Boolean)

    data class Marker(val x: Float, val y: Float)

    data class Chart(
        val width: Int, val height: Int, val candleWidth: Float, val candles: List<Candle>,
        /** The broken level's y. */
        val level: Float?,
        /** The target's y, clamped to the plot; [targetOff] when the target is beyond it (drawn at the edge). */
        val target: Float?, val targetOff: Boolean,
        val entry: Marker?, val exit: Marker?,
    )

    const val CHART_BARS = 30

    /**
     * The mini-chart's geometry, [width] x [height] px with [pad] px inside the edges: the last [bars] (at most
     * [CHART_BARS]) of the book's chart, the price scale fitted to them and the broken level (and the target when it is
     * within one more chart-height of them; else it sits on the edge, [Chart.targetOff]). [entryAt] / [exitAt] put a marker
     * on the bar holding that time (at its open for the entry, else at its close; after a bar's end: that bar's close).
     * Null with no bars or no room to draw.
     */
    fun chart(bars: List<Bar>, minutes: Int, level: Double?, target: Double?, entryAt: LocalDateTime?, exitAt: LocalDateTime?,
              width: Int, height: Int, pad: Int = 4): Chart? {
        val shown = bars.sortedBy { it.start }.takeLast(CHART_BARS)
        if (shown.isEmpty() || width <= 2 * pad || height <= 2 * pad) return null
        var lo = shown.minOf { it.low }
        var hi = shown.maxOf { it.high }
        level?.let { lo = minOf(lo, it); hi = maxOf(hi, it) }
        val span0 = (hi - lo).coerceAtLeast(1e-9)
        val targetIn = target != null && target >= lo - span0 && target <= hi + span0
        if (targetIn) { lo = minOf(lo, target!!); hi = maxOf(hi, target) }
        val margin = (hi - lo).coerceAtLeast(1.0) * 0.05
        lo -= margin; hi += margin
        val plotH = (height - 2 * pad).toFloat()
        fun y(p: Double): Float = (pad + (hi - p) / (hi - lo) * plotH).toFloat()
        val step = (width - 2 * pad).toFloat() / CHART_BARS
        // Right-aligned: the latest bar at the right edge, fewer bars leave space on the left.
        val first = CHART_BARS - shown.size
        fun x(i: Int): Float = pad + step * (first + i + 0.5f)
        val candles = shown.mapIndexed { i, b ->
            Candle(x(i), y(b.high), y(b.low), y(maxOf(b.open, b.close)), y(minOf(b.open, b.close)), b.close >= b.open)
        }
        fun marker(at: LocalDateTime?, open: Boolean): Marker? {
            at ?: return null
            if (at.isBefore(shown.first().start)) return null
            // The bar holding the time (or, past its end, the last bar before it): its open while inside it, else its close.
            val i = shown.indexOfLast { !it.start.isAfter(at) }
            val b = shown[i]
            val inside = at.isBefore(b.start.plusMinutes(minutes.toLong()))
            return Marker(x(i), y(if (open && inside) b.open else b.close))
        }
        val tY = target?.let { if (targetIn) y(it) else if (it > hi) pad.toFloat() else (height - pad).toFloat() }
        return Chart(width, height, (step * 0.6f).coerceAtLeast(1f), candles, level?.let { y(it) }, tY, target != null && !targetIn,
            marker(entryAt, open = true), marker(exitAt, open = false))
    }

    // ---- formats -----------------------------------------------------------------------------------------------------

    fun venue(live: Boolean) = if (live) "Live" else "Paper"

    /** "BANKNIFTY 54,000 CE", or the symbol when the strike is not known. */
    fun contract(underlying: String, strike: Double?, right: String, symbol: String): String =
        strike?.let { "$underlying ${lvl(it)} $right" } ?: symbol

    /** "2 lots (60)", "1 lot (30)"; the quantity alone when the lots are not known. */
    fun lotsWords(lots: Double?, qty: Int): String {
        val l = lots?.takeIf { it > 0 } ?: return "qty $qty"
        val n = if (abs(l - round(l)) < 1e-9) "%.0f".format(Locale.ENGLISH, l) else "%.1f".format(Locale.ENGLISH, l)
        return "$n lot${if (n == "1") "" else "s"} ($qty)"
    }

    fun duration(d: Duration): String {
        val m = d.toMinutes().coerceAtLeast(0)
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }

    internal fun px(x: Double) = "%.2f".format(Locale.ENGLISH, x)
    internal fun lvl(x: Double) = "%,.0f".format(Locale.ENGLISH, x)
    internal fun pts(x: Double) = "%.0f".format(Locale.ENGLISH, abs(x))
    internal fun hm(t: LocalDateTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)
    internal fun rupees(x: Double) = "₹" + "%,.0f".format(Locale.ENGLISH, abs(round(x)))

    /** "+₹1,240", "−₹300", "₹0". */
    internal fun signed(x: Double): String {
        val r = round(x)
        val body = "₹" + "%,.0f".format(Locale.ENGLISH, abs(r))
        return when { r > 0 -> "+$body"; r < 0 -> "−$body"; else -> body }
    }

    /** The day's tally from each closed trade's net P&L after charges. */
    fun tally(nets: List<Double>): Tally = Tally(nets.size, nets.count { it > 0.005 }, nets.sum())
}
