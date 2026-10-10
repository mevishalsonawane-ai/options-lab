package com.optionslab.ira

import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.LiquidityRules
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A closed Liquidity 15+5 paper trade replayed (Boss, 06 Oct 2026): the index's 1-minute candles from [BEFORE_MIN] minutes
 * before the entry to [AFTER_MIN] minutes after the exit, with the level the break took, the index stop and the target; the
 * option's premium over the same minutes when its candles are on the phone, with the best ([BotTrades.Trade.peak]) and the
 * worst ([BotTrades.Trade.trough]) premium seen while it was held; the result after charges (in all and a lot), how long it
 * was held, why it was sold, and its research lesson ([BotTrades.lesson]).
 *
 * The bars are the arm's own (read, never fetched here); [layout] puts the picture in pixels and the app only paints it.
 * Facts about a trade already closed: nothing here places, closes or arms anything. Pure.
 */
object LiquidityReplay {
    /** Minutes of index candles shown before the entry and after the exit. */
    const val BEFORE_MIN = 15L
    const val AFTER_MIN = 10L

    private val SESSION_END: LocalTime = LocalTime.of(15, 30)

    /** A price at a moment (an index level, or a premium). */
    data class Point(val time: LocalDateTime, val price: Double)

    /**
     * The replay of one trade: the window [from] (inclusive) to [to] (exclusive), the [index] candles inside it, the broken
     * [level], the [indexStop] and the [target]; the index at the entry and the exit ([entryIndex], [exitIndex]: null when no
     * candle holds that minute); the option's [premium] path (each 1-minute close in the window, empty when not on the
     * phone), the fill and the sale ([entryPremium], [exitPremium]) and the best and worst premium seen while held
     * ([best], [worst]: null when not recorded; their time is the held candle that came nearest, null without candles).
     * [lines] says it in words; [lesson] the research lesson, or null when there is none.
     */
    data class Replay(
        val title: String, val from: LocalDateTime, val to: LocalDateTime, val index: List<Bar>,
        val level: Double?, val indexStop: Double?, val target: Double?, val side: Int,
        val entryIndex: Point?, val exitIndex: Point?,
        val premium: List<Point>, val entryPremium: Point, val exitPremium: Point,
        val best: Mark?, val worst: Mark?,
        val lines: List<String>, val lesson: String?,
    )

    /** A recorded best or worst premium: its [price], and [time] when a held candle shows where it was (else null). */
    data class Mark(val price: Double, val time: LocalDateTime?)

    /** The minutes shown: [BEFORE_MIN] before the entry's minute to [AFTER_MIN] after the exit's, never past 15:30. */
    fun window(entryTime: LocalDateTime, exitTime: LocalDateTime): Pair<LocalDateTime, LocalDateTime> {
        val from = minute(entryTime).minusMinutes(BEFORE_MIN)
        val end = exitTime.toLocalDate().atTime(SESSION_END)
        val to = minOf(minute(exitTime).plusMinutes(AFTER_MIN + 1), maxOf(end, minute(exitTime).plusMinutes(1)))
        return from to to
    }

    private fun minute(t: LocalDateTime): LocalDateTime = t.withSecond(0).withNano(0)

    /**
     * The replay of [t], a closed Liquidity 15+5 trade ([strike] its option's strike, when known), from the index's
     * 1-minute candles [indexOnes] and the option's [optionOnes] (either may be empty, or hold more than the window).
     * Null for another arm's trade or one still open.
     */
    fun of(t: BotTrades.Trade, strike: Double?, indexOnes: List<Bar>, optionOnes: List<Bar>): Replay? {
        val arm = LiquidityRules.BOOKS.firstOrNull { it.source == (LiquidityRules.RENAMED[t.source] ?: t.source) } ?: return null
        val exit = t.exit ?: return null
        val exitTime = t.exitTime ?: return null
        val und = LiquidityRules.underlyingOf(arm)
        val tf = LiquidityRules.minutesOf(arm)
        val side = if (t.right == "PE") -1 else 1
        val (from, to) = window(t.entryTime, exitTime)
        fun inWindow(b: Bar) = !b.start.isBefore(from) && b.start.isBefore(to)
        val index = indexOnes.filter(::inWindow).distinctBy { it.start }.sortedBy { it.start }
        val option = optionOnes.filter(::inWindow).distinctBy { it.start }.sortedBy { it.start }
        val points = LiquidityRules.indexStopPoints(und)
        val indexStop = t.level?.let { it - side * points }

        fun indexAt(at: LocalDateTime): Point? = index.firstOrNull { it.start == minute(at) }?.let { Point(at, it.open) }
        val held = option.filter { !it.start.isBefore(minute(t.entryTime)) && !it.start.isAfter(minute(exitTime)) }
        val best = t.peak?.let { pk -> Mark(pk, held.minByOrNull { kotlin.math.abs(it.high - pk) }?.start) }
        val worst = t.trough?.let { lo -> Mark(lo, held.minByOrNull { kotlin.math.abs(it.low - lo) }?.start) }

        val book = LiquidityNotice.Book(und, tf)
        val lot = t.lot?.takeIf { it > 0 }
        val lots = lot?.let { t.qty.toDouble() / it }?.takeIf { it > 0 }
        val gross = (exit - t.entry) * t.qty
        val net = gross - t.charges
        val heldMin = Duration.between(t.entryTime, exitTime)
        val what = LiquidityNotice.contract(und, strike, t.right, t.symbol)
        val reason = LiquidityNotice.reason(LiquidityNotice.Exit(book, t.live, t.right, strike, t.symbol, t.qty, lots, t.entry, exit,
            t.entryTime, exitTime, t.why ?: "exit", t.level, t.target, points, gross, t.charges, LiquidityNotice.Tally(1, 0, net)))
        val perLot = (lots ?: 1.0).let { net / it }
        val lines = listOfNotNull(
            "${book.label} · $what · ${LiquidityNotice.venue(t.live)} · ${LiquidityNotice.lotsWords(lots, t.qty)}",
            "Entry ${LiquidityNotice.px(t.entry)} at ${LiquidityNotice.hm(t.entryTime)} → exit ${LiquidityNotice.px(exit)} at " +
                "${LiquidityNotice.hm(exitTime)} · held ${LiquidityNotice.duration(heldMin)}",
            "Exit reason: $reason",
            "Net after charges ${LiquidityNotice.signed(net)} · ${LiquidityNotice.signed(perLot)} a lot · charges ${LiquidityNotice.rupees(t.charges)}",
            "Best premium while held: " + (best?.let { mark(it) } ?: "not recorded") +
                " · worst: " + (worst?.let { mark(it) } ?: "not recorded"),
            t.level?.let { lv ->
                "Index: broke ${LiquidityNotice.lvl(lv)} · index stop ${LiquidityNotice.lvl(indexStop!!)} (${LiquidityNotice.pts(points)} pts back)" +
                    (t.target?.let { " · target ${LiquidityNotice.lvl(it)}" } ?: " · no target")
            },
            if (index.isEmpty()) "No index candles on the phone for ${LiquidityNotice.hm(from)}–${LiquidityNotice.hm(to)}" else null,
            if (option.isEmpty()) "Premium path: not on the phone (the fill and the sale only)" else null,
        )
        val lesson = BotTrades.lesson(t)?.text
        return Replay("Replay · ${book.label} ${t.right}", from, to, index, t.level, indexStop, t.target, side,
            indexAt(t.entryTime), indexAt(exitTime), option.map { Point(it.start, it.close) },
            Point(t.entryTime, t.entry), Point(exitTime, exit), best, worst, lines, lesson)
    }

    private fun mark(m: Mark): String = LiquidityNotice.px(m.price) + (m.time?.let { " (${LiquidityNotice.hm(it)})" } ?: "")

    // ---- the picture ---------------------------------------------------------------------------------------------

    enum class LineKind { LEVEL, INDEX_STOP, TARGET }

    /** A level across the index panel at [y]. */
    data class HLine(val y: Float, val kind: LineKind, val label: String)

    data class Dot(val x: Float, val y: Float)

    /**
     * The replay in pixels: the index panel's [candles] (from 0 to [indexBottom]) with its [lines] and the [entry] / [exit]
     * dots; the held span [holdFrom]..[holdTo] (x); the premium panel below (from [premiumTop] to the bottom; null: no
     * premium panel) with the [premium] polyline, the fill and the sale, and the [best] / [worst] marks (a mark without a
     * time sits at the panel's right edge).
     */
    data class Layout(
        val width: Int, val height: Int, val candleWidth: Float, val candles: List<LiquidityNotice.Candle>, val indexBottom: Float,
        val lines: List<HLine>, val entry: Dot?, val exit: Dot?, val holdFrom: Float, val holdTo: Float,
        val premiumTop: Float?, val premium: List<Dot>, val premiumEntry: Dot?, val premiumExit: Dot?, val best: Dot?, val worst: Dot?,
    )

    /** The share of the height the index panel takes when a premium panel is drawn under it. */
    const val INDEX_SHARE = 0.6f

    /**
     * [r] laid out in [width] x [height] px, [pad] px inside the edges. The x axis is time ([Replay.from] to [Replay.to],
     * one slot a minute). The index scale fits the candles, the level and the index stop (and the target when within one
     * more span; else it is left out). The premium panel is drawn when the premium path, the best or the worst is known.
     * Null with no room to draw.
     */
    fun layout(r: Replay, width: Int, height: Int, pad: Int = 4): Layout? {
        if (width <= 2 * pad || height <= 2 * pad) return null
        val minutes = Duration.between(r.from, r.to).toMinutes().coerceAtLeast(1)
        val plotW = (width - 2 * pad).toFloat()
        val step = plotW / minutes
        fun x(t: LocalDateTime): Float =
            (pad + step * (Duration.between(r.from, t).seconds / 60.0).coerceIn(0.0, minutes.toDouble())).toFloat()
        fun xMid(t: LocalDateTime): Float = (x(t) + step / 2).coerceAtMost(pad + plotW)
        val premiumPanel = r.premium.isNotEmpty() || r.best != null || r.worst != null
        val gap = if (premiumPanel) 6 else 0
        val indexBottom = if (premiumPanel) pad + (height - 2 * pad) * INDEX_SHARE else (height - pad).toFloat()

        // The index panel.
        val levels = listOfNotNull(r.level, r.indexStop) + r.index.flatMap { listOf(it.high, it.low) } +
            listOfNotNull(r.entryIndex?.price, r.exitIndex?.price)
        val candles = ArrayList<LiquidityNotice.Candle>()
        val lines = ArrayList<HLine>()
        var entry: Dot? = null
        var exit: Dot? = null
        if (levels.isNotEmpty()) {
            var lo = levels.min(); var hi = levels.max()
            val span0 = (hi - lo).coerceAtLeast(1.0)
            val target = r.target?.takeIf { it >= lo - span0 && it <= hi + span0 }
            target?.let { lo = minOf(lo, it); hi = maxOf(hi, it) }
            val margin = (hi - lo).coerceAtLeast(1.0) * 0.06
            lo -= margin; hi += margin
            val top = pad.toFloat()
            val h = indexBottom - top
            fun y(p: Double): Float = (top + (hi - p) / (hi - lo) * h).toFloat()
            r.index.forEach { b ->
                candles += LiquidityNotice.Candle(xMid(b.start), y(b.high), y(b.low), y(maxOf(b.open, b.close)), y(minOf(b.open, b.close)), b.close >= b.open)
            }
            r.level?.let { lines += HLine(y(it), LineKind.LEVEL, "level ${LiquidityNotice.lvl(it)}") }
            r.indexStop?.let { lines += HLine(y(it), LineKind.INDEX_STOP, "index stop ${LiquidityNotice.lvl(it)}") }
            target?.let { lines += HLine(y(it), LineKind.TARGET, "target ${LiquidityNotice.lvl(it)}") }
            entry = r.entryIndex?.let { Dot(x(it.time), y(it.price)) }
            exit = r.exitIndex?.let { Dot(x(it.time), y(it.price)) }
        }

        // The premium panel.
        var premiumTop: Float? = null
        var path = emptyList<Dot>()
        var pEntry: Dot? = null; var pExit: Dot? = null; var best: Dot? = null; var worst: Dot? = null
        if (premiumPanel) {
            val top = indexBottom + gap
            val bottom = (height - pad).toFloat()
            premiumTop = top
            val values = r.premium.map { it.price } + listOfNotNull(r.entryPremium.price, r.exitPremium.price, r.best?.price, r.worst?.price)
            var lo = values.min(); var hi = values.max()
            val margin = (hi - lo).coerceAtLeast(0.5) * 0.1
            lo -= margin; hi += margin
            fun y(p: Double): Float = (top + (hi - p) / (hi - lo) * (bottom - top)).toFloat()
            path = r.premium.map { Dot(xMid(it.time), y(it.price)) }
            pEntry = Dot(x(r.entryPremium.time), y(r.entryPremium.price))
            pExit = Dot(x(r.exitPremium.time), y(r.exitPremium.price))
            fun at(m: Mark): Dot = Dot(m.time?.let { xMid(it) } ?: (pad + plotW), y(m.price))
            best = r.best?.let(::at)
            worst = r.worst?.let(::at)
        }
        return Layout(width, height, (step * 0.7f).coerceAtLeast(1f), candles, indexBottom, lines, entry, exit,
            x(r.entryPremium.time), x(r.exitPremium.time), premiumTop, path, pEntry, pExit, best, worst)
    }
}
