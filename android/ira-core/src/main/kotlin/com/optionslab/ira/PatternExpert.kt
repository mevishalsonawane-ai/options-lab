package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Jarvis's candle-pattern expert (the owner's wish, 2026-10-02): it knows each candle pattern - what it is and what it
 * says - and each one's own record on each index and chart over the last two years: how often the price went the
 * pattern's way over the next [HORIZON] candles, year by year. When a pattern with a record that held in BOTH years
 * closes on a live chart, in the right place (the trend agreeing, room to the next level, entry hours, the trade check
 * not saying stop), it suggests an order and says why - and what can go wrong. A pattern whose record is a coin toss is
 * never suggested, however textbook it looks. Pure.
 */
object PatternExpert {
    /** The outcome is measured this many candles after the pattern, inside the same session. */
    const val HORIZON = PatternBook.HORIZON
    const val MIN_CASES = 30
    const val MIN_HALF = 12
    /** Each year at least this often the pattern's way. */
    const val EDGE = 0.55
    const val MAX_A_DAY = 2
    /** At least this much room (%) to the next level in the trade's direction. */
    const val ROOM_PCT = 0.2

    /** What each pattern is, and what it is read to say (textbook; its record says whether it works here). */
    val MEANING: Map<PatternKind, String> = mapOf(
        PatternKind.BULLISH_ENGULFING to "a green candle whose body covers the whole body of the red one before it: buyers took over from sellers",
        PatternKind.BEARISH_ENGULFING to "a red candle whose body covers the whole body of the green one before it: sellers took over from buyers",
        PatternKind.HAMMER to "a small body at the top with a lower shadow at least twice the body: sellers pushed down and buyers pushed it all back - read as a turn up, best after a fall",
        PatternKind.SHOOTING_STAR to "a small body at the bottom with an upper shadow at least twice the body: buyers pushed up and sellers pushed it all back - read as a turn down, best after a rise",
        PatternKind.DOJI to "open and close almost equal: indecision; it says a move may come, not which way",
        PatternKind.INSIDE_BAR to "a candle inside the previous one's high and low: the market pausing; the break of its range is what traders watch",
        PatternKind.THREE_WHITE_SOLDIERS to "three strong green candles, each closing higher: steady buying",
        PatternKind.THREE_BLACK_CROWS to "three strong red candles, each closing lower: steady selling",
        PatternKind.BREAKOUT_UP to "a close above the highest high of the last 20 candles: price leaving its range upwards",
        PatternKind.BREAKOUT_DOWN to "a close below the lowest low of the last 20 candles: price leaving its range downwards",
        PatternKind.DOUBLE_TOP to "two highs at the same price and then a close below the low between them: buyers failed twice at a level",
        PatternKind.DOUBLE_BOTTOM to "two lows at the same price and then a close above the high between them: sellers failed twice at a level",
    )

    data class Edge(val market: Market, val minutes: Int, val kind: PatternKind, val cases: Int, val rate: Double,
                    val first: Double, val second: Double, val avgMovePct: Double) {
        val held: Boolean get() = cases >= MIN_CASES && first >= EDGE && second >= EDGE && avgMovePct > 0
        fun text(): String = "${market.label} ${minutes}-minute, ${kind.label}: went its way over the next ${HORIZON * minutes} minutes on " +
            "${pct(rate)} of $cases cases (${pct(first)} and ${pct(second)} by year), ${"%+.2f".format(Locale.ENGLISH, avgMovePct)}% on average" +
            if (held) "." else " - no edge."
    }

    private fun pct(x: Double) = "%.0f%%".format(Locale.ENGLISH, x * 100)
    private fun px(x: Double) = "%,.2f".format(Locale.ENGLISH, x)

    /** Every directional pattern's record on [candles] (completed [minutes]-minute candles of [m], two years). */
    fun edges(m: Market, minutes: Int, candles: List<Candle>): List<Edge> {
        if (candles.size < Patterns.LOOKBACK + HORIZON + 1) return emptyList()
        val mid = candles[candles.size / 2].t.toLocalDate()
        data class Case(val day: LocalDate, val move: Double)
        val by = HashMap<PatternKind, ArrayList<Case>>()
        for (i in Patterns.LOOKBACK until candles.size - HORIZON) {
            val later = candles[i + HORIZON]
            if (later.t.toLocalDate() != candles[i].t.toLocalDate()) continue          // never across the night
            for (k in Patterns.at(candles, i)) {
                if (k.bias == 0) continue
                val move = (later.c - candles[i].c) / candles[i].c * 100 * k.bias
                by.getOrPut(k) { ArrayList() } += Case(candles[i].t.toLocalDate(), move)
            }
        }
        return by.mapNotNull { (k, cs) ->
            val a = cs.filter { it.day < mid }; val b = cs.filter { it.day >= mid }
            if (a.size < MIN_HALF || b.size < MIN_HALF) return@mapNotNull null
            fun rate(l: List<Case>) = l.count { it.move > 0 }.toDouble() / l.size
            Edge(m, minutes, k, cs.size, rate(cs), rate(a), rate(b), cs.map { it.move }.average())
        }.sortedByDescending { it.rate }
    }

    /** An order suggested, or why not ([reasons]). */
    data class Verdict(val idea: NewsTrade.Idea?, val reasons: List<String>)

    /**
     * The last completed candle of [candles] ([minutes]-minute, [snap]'s market), judged now: a suggestion when a
     * pattern with a held record closed on it, fresh, in entry hours, the trend agreeing and room to the next level.
     */
    fun judge(minutes: Int, candles: List<Candle>, snap: Snapshot?, edges: List<Edge>, check: TradeCheck.Level?,
              now: LocalDateTime, today: Int): Verdict {
        val s = snap ?: return Verdict(null, listOf("No live prices yet."))
        val m = s.market
        val minute = now.hour * 60 + now.minute
        if (minute < 9 * 60 + 20 || minute > 14 * 60 + 30) return Verdict(null, listOf("Suggestions are made between 09:20 and 14:30 only."))
        if (check == TradeCheck.Level.STOP) return Verdict(null, listOf("The trade check says don't trade now."))
        if (today >= MAX_A_DAY) return Verdict(null, listOf("Already $MAX_A_DAY suggested trades today: that is the day's limit."))
        val i = candles.lastIndex
        if (i < Patterns.LOOKBACK) return Verdict(null, listOf("Not enough ${m.label} candles yet."))
        val last = candles[i]
        val closedAt = last.t.plusMinutes(minutes.toLong())
        if (last.t.toLocalDate() != now.toLocalDate() || closedAt.isAfter(now) || closedAt.plusMinutes(minutes.toLong()).isBefore(now))
            return Verdict(null, listOf("No fresh ${minutes}-minute ${m.label} candle."))
        val found = Patterns.at(candles, i).filter { it.bias != 0 }
        if (found.isEmpty()) return Verdict(null, listOf("${m.label} ${minutes}-minute: no pattern on the last candle."))
        val reasons = ArrayList<String>()
        for (k in found) {
            val e = edges.firstOrNull { it.market == m && it.minutes == minutes && it.kind == k }
            if (e == null || !e.held) { reasons += e?.text() ?: "${m.label} ${minutes}-minute, ${k.label}: not enough cases in two years to trust it."; continue }
            val up = k.bias > 0
            val trend = s.trend(minutes)?.up
            if (trend != null && trend != up) { reasons += "A ${k.label} on ${m.label} ${minutes}-minute, but against the ${if (trend) "up" else "down"}trend: not suggested."; continue }
            val next = if (up) s.above.firstOrNull() else s.below.firstOrNull()
            val room = next?.let { kotlin.math.abs(it.price - last.c) / last.c * 100 }
            if (room != null && room < ROOM_PCT) { reasons += "A ${k.label} on ${m.label}, but ${next.name} at ${px(next.price)} is right ${if (up) "above" else "below"}: no room."; continue }
            val behind = if (up) s.below.firstOrNull() else s.above.firstOrNull()
            val why = "${m.label} ${minutes}-minute chart: a ${k.label} just closed at ${px(last.c)} - ${MEANING[k]}. " +
                "Its record here: ${e.text()} " +
                (if (trend != null) "The ${minutes}-minute trend is ${if (up) "up" else "down"}, with it. " else "") +
                (next?.let { "Room to ${it.name} at ${px(it.price)}. " } ?: "") +
                (behind?.let { "${it.name} at ${px(it.price)} is ${if (up) "below" else "above"}. " } ?: "") +
                "Trade check: ${when (check) { TradeCheck.Level.GO -> "normal"; TradeCheck.Level.CAREFUL -> "careful today"; else -> "not known" }}. " +
                "What can go wrong: it failed ${pct(1 - e.rate)} of the time; the stop 15% below the price paid and the profit lock limit that. History, not a promise."
            return Verdict(NewsTrade.Idea(m, up, why), reasons)
        }
        return Verdict(null, reasons)
    }

    /** "What is a hammer?": what it is, and its record on each index and chart Jarvis studied. */
    fun explain(k: PatternKind, edges: List<Edge>): String {
        val mine = edges.filter { it.kind == k }
        return "A ${k.label}: ${MEANING[k]}." + if (mine.isEmpty()) " I have no two-year record of it yet." else
            " Its record: " + mine.joinToString(" ") { it.text() }
    }

    /** The patterns that held in both years, best first: the expert's short list. */
    fun best(edges: List<Edge>, n: Int = 5): List<String> {
        val h = edges.filter { it.held }.sortedByDescending { it.rate }.take(n)
        return if (h.isEmpty()) listOf("No candle pattern held up in both years on these charts: I will not suggest trades from patterns until one does.")
        else h.map { it.text() }
    }
}
