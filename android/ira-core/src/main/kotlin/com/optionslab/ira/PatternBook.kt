package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * What Ira has learned about each pattern, per market and chart: how often the price moved the pattern's way over the
 * next [HORIZON] candles, and by how much on average. It learns from every completed candle it is shown - the first
 * time from the history, then each day from the new candles - and never counts a candle twice. Saved as plain text.
 */
class PatternBook {
    data class Stat(val seen: Int = 0, val worked: Int = 0, val sumMovePct: Double = 0.0) {
        val rate: Double get() = if (seen == 0) 0.0 else worked.toDouble() / seen
        val avgMovePct: Double get() = if (seen == 0) 0.0 else sumMovePct / seen
    }

    private val stats = HashMap<String, Stat>()
    private val learnedTo = HashMap<String, LocalDateTime>()

    fun stat(m: Market, minutes: Int, k: PatternKind): Stat = stats[key(m, minutes, k)] ?: Stat()
    fun learnedUntil(m: Market, minutes: Int): LocalDateTime? = learnedTo["${m.name}/$minutes"]
    val size: Int get() = stats.values.sumOf { it.seen }

    /**
     * Learns from [candles] (completed, one market and chart, oldest first): every pattern whose outcome is now known
     * and that falls after what was learned before. Returns how many new pattern outcomes it counted.
     */
    fun learn(m: Market, minutes: Int, candles: List<Candle>): Int {
        val mk = "${m.name}/$minutes"
        val after = learnedTo[mk]
        var added = 0
        var last: LocalDateTime? = after
        for (i in 1 until candles.size - HORIZON) {
            val t = candles[i].t
            if (after != null && !t.isAfter(after)) continue
            last = t
            // The outcome is measured inside the session only: never across the night's gap.
            if (candles[i + HORIZON].t.toLocalDate() != t.toLocalDate()) continue
            for (k in Patterns.at(candles, i)) {
                val (worked, move) = outcome(candles, i, k)
                val s = stats[key(m, minutes, k)] ?: Stat()
                stats[key(m, minutes, k)] = Stat(s.seen + 1, s.worked + if (worked) 1 else 0, s.sumMovePct + move)
                added++
            }
        }
        if (last != null) learnedTo[mk] = last
        return added
    }

    /** One market, chart and pattern, as the book holds it. */
    data class Entry(val market: Market, val minutes: Int, val kind: PatternKind, val stat: Stat)

    /** Everything learned, one entry per market, chart and pattern. */
    fun entries(): List<Entry> = stats.mapNotNull { (k, s) ->
        val p = k.split('/')
        runCatching { Entry(Market.valueOf(p[0]), p[1].toInt(), PatternKind.valueOf(p[2]), s) }.getOrNull()
    }

    /** The patterns that went their way most often, among those seen at least [min] times. */
    fun best(n: Int = 3, min: Int = ENOUGH): List<Entry> =
        entries().filter { it.stat.seen >= min }.sortedWith(compareByDescending<Entry> { it.stat.rate }.thenByDescending { it.stat.seen }).take(n)

    private fun typicalMovePct(c: List<Candle>, i: Int): Double {
        val from = maxOf(HORIZON, i - 100)
        if (i - from < 5) return 0.0
        val moves = (from until i).filter { c[it].t.toLocalDate() == c[it - HORIZON].t.toLocalDate() }
            .map { kotlin.math.abs((c[it].c - c[it - HORIZON].c) / c[it - HORIZON].c * 100) }.sorted()
        return moves[moves.size / 2]
    }

    /** Plain-text form: one line per stat, then the learned-until marks. */
    fun save(): String = buildString {
        stats.toSortedMap().forEach { (k, s) -> append("S|$k|${s.seen}|${s.worked}|${s.sumMovePct}\n") }
        learnedTo.toSortedMap().forEach { (k, t) -> append("L|$k|$t\n") }
    }

    /** How the patterns of one session did: how many were seen with a known outcome, how many went their way. */
    data class Score(val seen: Int, val worked: Int) {
        operator fun plus(o: Score) = Score(seen + o.seen, worked + o.worked)
        val rate: Double get() = if (seen == 0) 0.0 else worked.toDouble() / seen
    }

    /**
     * Did the move after pattern [k] at candle [i] go its way, and the move in its direction (%). A neutral pattern
     * "works" when the move afterwards is bigger than usual either way.
     */
    private fun outcome(candles: List<Candle>, i: Int, k: PatternKind): Pair<Boolean, Double> {
        val entry = candles[i].c
        val movePct = (candles[i + HORIZON].c - entry) / entry * 100
        val dir = if (k.bias == 0) 1 else k.bias
        val worked = if (k.bias == 0) kotlin.math.abs(movePct) > typicalMovePct(candles, i) else movePct * dir > 0
        return worked to movePct * dir
    }

    /** The patterns found on [day]'s candles of [candles] (completed, one chart) whose outcome is known: the same test as learning. */
    fun score(candles: List<Candle>, day: LocalDate): Score {
        var seen = 0; var worked = 0
        for (i in 1 until candles.size - HORIZON) {
            if (candles[i].t.toLocalDate() != day || candles[i + HORIZON].t.toLocalDate() != day) continue
            for (k in Patterns.at(candles, i)) { seen++; if (outcome(candles, i, k).first) worked++ }
        }
        return Score(seen, worked)
    }

    companion object {
        /** How many candles ahead a pattern's outcome is measured. */
        const val HORIZON = 4
        /** Fewer cases than this and Ira says it cannot tell yet. */
        const val ENOUGH = 30

        private fun key(m: Market, minutes: Int, k: PatternKind) = "${m.name}/$minutes/${k.name}"

        fun load(text: String): PatternBook {
            val b = PatternBook()
            text.lineSequence().forEach { line ->
                val p = line.split('|')
                runCatching {
                    when (p[0]) {
                        "S" -> b.stats[p[1]] = Stat(p[2].toInt(), p[3].toInt(), p[4].toDouble())
                        "L" -> b.learnedTo[p[1]] = LocalDateTime.parse(p[2])
                    }
                }
            }
            return b
        }
    }
}
