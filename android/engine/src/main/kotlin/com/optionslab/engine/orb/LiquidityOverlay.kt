package com.optionslab.engine.orb

import java.time.LocalDateTime
import java.util.Locale

/**
 * What the live chart draws for Liquidity 15+5 on BANKNIFTY / FINNIFTY, from the very code the arm decides with
 * ([LiquidityRules.zones], [LiquidityRules.signal], [LiquidityRules.hasRoom]): the swing zones (bands), the liquidity
 * pools (dashed lines), the arm's trades of the day (entry / exit markers, the broken level and, while open, the target)
 * and the breaks the room filter skipped ("liquidity_no_room").
 *
 * Only bars that have closed by `now` are used (no look-ahead); the levels are computed on all of them (the arm reads
 * about ten days), and only what touches the last [SESSIONS] sessions is kept, at most [MAX_ZONES] zones.
 * Pure: no clock, no network, no drawing.
 */
object LiquidityOverlay {
    /** Sessions shown. */
    const val SESSIONS = 3
    /** Zones drawn at most (the most recently formed). */
    const val MAX_ZONES = 40
    /** Calendar days of history the levels are read from: the arm's own (its read of the ten days before today, and today). */
    const val LOOKBACK_DAYS = 10L

    enum class Kind { SWING, POOL }

    /**
     * One level as drawn: a band ([Kind.SWING]) or a dashed line at its [edge] ([Kind.POOL]), across window bars
     * [from]..[to]; [taken]: a close went through it (drawn faded), at [to].
     */
    data class Shape(val kind: Kind, val side: Int, val top: Double, val bottom: Double, val from: Int, val to: Int, val taken: Boolean) {
        val edge: Double get() = if (side > 0) top else bottom
        val label: String get() = if (kind == Kind.SWING) "swing" else "pool"
    }

    /**
     * One of the arm's trades on the index ([book]: the book's source, [minutes]: its chart; [strike]: e.g. "52000 CE").
     * [pnl]: net of charges once closed (null while open). [near], [volSkip], [strong]: the shadow flags, when recorded.
     */
    data class Trade(
        val book: String, val minutes: Int, val side: Int, val signalBar: LocalDateTime, val entryTime: LocalDateTime,
        val entry: Double, val qty: Int, val strike: String, val exit: Double? = null, val exitTime: LocalDateTime? = null,
        val why: String? = null, val level: Double? = null, val target: Double? = null, val pnl: Double? = null,
        val near: Boolean? = null, val volSkip: Boolean? = null, val strong: Boolean? = null,
    ) {
        val open: Boolean get() = exit == null
        val tag: String get() = "L$minutes"
    }

    /** A break the room filter skipped: the deciding bar, its close, the level it broke and the next one ahead. */
    data class Skip(val bar: LocalDateTime, val side: Int, val close: Double, val level: Double, val target: Double, val need: Double) {
        val room: Double get() = side * (target - close)
    }

    enum class MarkerKind { ENTRY, EXIT, NO_ROOM }

    /** A marker on window bar [bar], [above] or below it; [text] its short tag; [trade] / [skip] what a tap shows. */
    data class Marker(val kind: MarkerKind, val side: Int, val bar: Int, val above: Boolean, val text: String,
                      val trade: Trade? = null, val skip: Skip? = null)

    /** A trade's line: the broken level (solid) or, while the trade is open, its target (dotted). */
    data class Line(val price: Double, val from: Int, val to: Int, val dotted: Boolean, val label: String)

    data class Model(val minutes: Int, val bars: List<Bar>, val shapes: List<Shape>, val markers: List<Marker>, val lines: List<Line>) {
        /** The last closed bar's start, or null: a model is recomputed only when this (or the trades) change. */
        val lastBar: LocalDateTime? get() = bars.lastOrNull()?.start
    }

    /** The chart a book reads: BANKNIFTY 15 and 5, FINNIFTY 30 and 5 (the slower one first). */
    fun timeframes(underlying: String): List<Int> =
        LiquidityRules.BOOKS.filter { LiquidityRules.underlyingOf(it) == underlying }.map { LiquidityRules.minutesOf(it) }

    /**
     * [input] candles of any width that divides [minutes] (5-minute from the chart, or 1-minute), folded into
     * [minutes]-minute bars from 09:15; only those closed by [now], and as the arm: a bar still missing its last
     * [inputMinutes] is held back for two minutes after it ends.
     */
    fun closedBars(input: List<Bar>, minutes: Int, now: LocalDateTime, inputMinutes: Int = 5): List<Bar> {
        val seen = input.filter { it.start.isBefore(now) }
        val have = seen.mapTo(HashSet()) { it.start }
        return LiquidityRules.completed(LiquidityRules.fold(seen, minutes), minutes, now).filter { b ->
            !now.isBefore(b.start.plusMinutes(minutes + 2L)) || b.start.plusMinutes((minutes - inputMinutes).toLong()) in have
        }
    }

    /** The model of [input] at [now] on the [minutes]-minute chart of [underlying], with that index's [trades]. */
    fun build(input: List<Bar>, minutes: Int, underlying: String, now: LocalDateTime, trades: List<Trade>,
              inputMinutes: Int = 5, sessions: Int = SESSIONS, maxZones: Int = MAX_ZONES): Model {
        // The same days the arm reads, so a level from older history the arm never sees is not drawn.
        val since = now.toLocalDate().minusDays(LOOKBACK_DAYS)
        val all = closedBars(input.filter { !it.start.toLocalDate().isBefore(since) }, minutes, now, inputMinutes)
        if (all.isEmpty()) return Model(minutes, emptyList(), emptyList(), emptyList(), emptyList())
        val days = all.map { it.start.toLocalDate() }.distinct().takeLast(sessions)
        val first = all.indexOfFirst { it.start.toLocalDate() == days.first() }
        val bars = all.subList(first, all.size).toList()
        val last = all.lastIndex
        val zones = LiquidityRules.zones(all)
        val shapes = zones.filter { z -> (if (z.broken < 0) last else z.broken) >= first }
            .sortedByDescending { it.known }.take(maxZones)
            .map { z ->
                Shape(if (z.kind == "swing") Kind.SWING else Kind.POOL, z.side, z.top, z.bottom,
                    maxOf(z.origin, first) - first, (if (z.broken < 0) last else z.broken) - first, z.broken >= 0)
            }.sortedBy { it.from }
        val markers = ArrayList<Marker>()
        val lines = ArrayList<Line>()
        for (s in skips(all, zones, minutes, underlying, first)) {
            val i = all.indexOfFirst { it.start == s.bar } - first
            markers += Marker(MarkerKind.NO_ROOM, s.side, i, above = s.side < 0, text = "skip", skip = s)
        }
        for (t in trades.sortedBy { it.entryTime }) {
            // Decided on the close of the book's own bar: on a faster chart, the bar that closes with it.
            // A moment past the last closed bar draws nothing until its bar has closed.
            val e = barAt(bars, t.signalBar.plusMinutes(t.minutes.toLong()).minusNanos(1), minutes) ?: continue
            markers += Marker(MarkerKind.ENTRY, t.side, e, above = t.side < 0, text = t.tag, trade = t)
            val x = t.exitTime?.let { barAt(bars, it, minutes) }
            if (x != null) markers += Marker(MarkerKind.EXIT, t.side, x, above = t.side > 0, text = reason(t.why), trade = t)
            val end = x ?: bars.lastIndex
            t.level?.let { lines += Line(it, e, end, dotted = false, label = "${t.tag} level") }
            if (t.open) t.target?.let { lines += Line(it, e, end, dotted = true, label = "${t.tag} target") }
        }
        return Model(minutes, bars, shapes, markers, lines)
    }

    /**
     * The breaks the room filter skipped on bars from [from] on: each decided, as the arm does, on the bars up to it
     * only (the zones of all the bars give the same signal there: a zone counts at a bar only once known and not yet
     * taken before it).
     */
    fun skips(bars: List<Bar>, zones: List<LiquidityRules.Zone>, minutes: Int, underlying: String, from: Int = 0): List<Skip> {
        val out = ArrayList<Skip>()
        for (i in maxOf(from, 2 * LiquidityRules.SWING_LOOKBACK + 1)..bars.lastIndex) {
            val b = bars[i]
            if (!LiquidityRules.mayEnterAt(b.start.plusMinutes(minutes.toLong()))) continue
            val s = LiquidityRules.signal(bars.subList(0, i + 1), zones) ?: continue
            if (LiquidityRules.hasRoom(s, b.close, underlying)) continue
            out += Skip(b.start, s.side, b.close, s.level, s.target!!, LiquidityRules.MIN_ROOM_STOPS * LiquidityRules.indexStopPoints(underlying))
        }
        return out
    }

    /**
     * The window bar ([minutes] wide) holding moment [t] (the last one starting by then), or null when [t] is before the
     * window or at / after the end of its last bar (that bar has not closed yet, or is not in the window).
     */
    fun barAt(bars: List<Bar>, t: LocalDateTime, minutes: Int): Int? {
        if (bars.isEmpty() || t.isBefore(bars.first().start)) return null
        if (!t.isBefore(bars.last().start.plusMinutes(minutes.toLong()))) return null
        return bars.indexOfLast { !it.start.isAfter(t) }
    }

    /** The marker a tap on window bar [bar] means: one on that bar, else on the bar either side (entries first). */
    fun markerNear(markers: List<Marker>, bar: Int, slack: Int = 1): Marker? =
        (0..slack).asSequence().flatMap { d -> sequenceOf(bar - d, bar + d) }
            .firstNotNullOfOrNull { b -> markers.filter { it.bar == b }.minByOrNull { it.kind.ordinal } }

    /** An exit's reason as the chart says it. */
    fun reason(why: String?): String = when (why) {
        null -> "exit"
        "stop" -> "stop"
        "index_stop" -> "index stop"
        "time_stop" -> "time stop"
        "next_liquidity" -> "next liquidity"
        "new_liquidity" -> "new liquidity"
        "failed_break" -> "failed break"
        "session_end" -> "15:10"
        "backstop_square_off" -> "15:15"
        "operator_stop" -> "stopped"
        "closed_by_you" -> "closed by you"
        else -> why.replace('_', ' ')
    }

    private fun money(x: Double) = String.format(Locale.ENGLISH, "%,.2f", x)
    private fun hhmm(t: LocalDateTime) = String.format(Locale.ENGLISH, "%02d:%02d", t.hour, t.minute)
    private fun book(t: Trade) = LiquidityRules.BOOKS.firstOrNull { it.source == t.book }?.label ?: t.book

    /** A trade's card: book, strike and quantity, entry, exit and reason, P&L, the shadow flags recorded. */
    fun card(t: Trade): List<String> = listOfNotNull(
        "${book(t)} · ${t.tag}",
        "${t.strike} · qty ${t.qty}",
        "Entry ₹${money(t.entry)} at ${hhmm(t.entryTime)}",
        if (t.exit == null) "Open" + (t.target?.let { " · target ${money(it)}" } ?: "")
        else "Exit ₹${money(t.exit)}" + (t.exitTime?.let { " at ${hhmm(it)}" } ?: "") + " · ${reason(t.why)}",
        t.pnl?.let { "P&L ${if (it >= 0) "+" else "-"}₹${money(kotlin.math.abs(it))}" },
        t.level?.let { "Broke ${money(it)}" + (t.target?.let { g -> " · next level ${money(g)}" } ?: "") },
        flags(t),
    )

    /** The shadow flags recorded with a trade (they never changed it), or null when none was. */
    fun flags(t: Trade): String? {
        val f = listOfNotNull(
            t.near?.let { if (it) "room: too little" else "room: ok" },
            t.volSkip?.let { if (it) "vol skip (c): would skip" else "vol skip (c): would take" },
            t.strong?.let { if (it) "strong close (f): yes" else "strong close (f): no" },
        )
        return if (f.isEmpty()) null else "Shadow: " + f.joinToString(" · ")
    }

    /** A skipped break's card. */
    fun card(s: Skip): List<String> = listOf(
        "Skipped: too little room",
        "${hhmm(s.bar)} bar closed ${money(s.close)} through ${money(s.level)} (${if (s.side > 0) "above" else "below"})",
        "Next level ${money(s.target)} only ${money(s.room)} pts away (needs ${money(s.need)})",
    )
}
