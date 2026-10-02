package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Jarvis's study (the owner's wish, 2026-10-02: "keep studying the markets the whole night"): what usually happened on
 * an index after a known setup - a gap, the first 15 minutes, a narrow opening range, a big day before, where
 * yesterday closed, the weekday - counted over the last two years of its candles. A finding is kept as knowledge only
 * when it held in BOTH halves of the period (each year on the same side, at least 60/40) on enough days; everything
 * else is reported as a coin toss. History, never a promise about tomorrow. Pure.
 */
object Study {
    const val MIN_DAYS = 30
    const val MIN_HALF = 12
    const val EDGE = 0.60

    /** One session, from its intraday candles. */
    data class Day(val date: LocalDate, val open: Double, val high: Double, val low: Double, val close: Double,
                   val prevClose: Double?, val at930: Double?, val orHigh: Double?, val orLow: Double?, val avgOr: Double?,
                   val prevChangePct: Double?, val prevClosePos: Double?) {
        val gapPct: Double? get() = prevClose?.let { (open - it) / it * 100 }
        val changePct: Double? get() = prevClose?.let { (close - it) / it * 100 }
        val first15Pct: Double? get() = at930?.let { (it - open) / open * 100 }
        val orRatio: Double? get() = if (orHigh != null && orLow != null && avgOr != null && avgOr > 0) (orHigh - orLow) / avgOr else null
    }

    data class Finding(val market: Market, val key: String, val setup: String, val outcome: String,
                       val days: Int, val rate: Double, val first: Double, val second: Double) {
        /** Held in both halves: knowledge worth acting on with care. */
        val held: Boolean get() = days >= MIN_DAYS && ((first >= EDGE && second >= EDGE) || (first <= 1 - EDGE && second <= 1 - EDGE))
        fun text(): String = "${market.label}, $setup ($days days in two years): $outcome on ${pct(rate)} " +
            "(first year ${pct(first)}, second ${pct(second)})" + if (held) "." else " - a coin toss, no edge."
    }

    private fun pct(x: Double) = "%.0f%%".format(Locale.ENGLISH, x * 100)

    /** The sessions in [bars] (any intraday timeframe, IST times), oldest first. */
    fun days(bars: List<Candle>): List<Day> {
        val by = bars.groupBy { it.t.toLocalDate() }.toSortedMap()
        val raw = by.mapNotNull { (d, cs) ->
            val day = cs.filter { it.t.toLocalTime() >= java.time.LocalTime.of(9, 15) && it.t.toLocalTime() < java.time.LocalTime.of(15, 30) }
            if (day.size < 10) return@mapNotNull null
            val or = day.filter { it.t.toLocalTime() < java.time.LocalTime.of(10, 0) }
            val at930 = day.lastOrNull { it.t.toLocalTime() < java.time.LocalTime.of(9, 30) }?.c
            Triple(d, day, or) to at930
        }
        val out = ArrayList<Day>()
        for (x in raw) {
            val (d, day, or) = x.first
            val prev = out.lastOrNull()
            val past = out.takeLast(20).mapNotNull { p -> if (p.orHigh != null && p.orLow != null) p.orHigh - p.orLow else null }
            out += Day(d, day.first().o, day.maxOf { it.h }, day.minOf { it.l }, day.last().c,
                prev?.close, x.second,
                or.takeIf { it.isNotEmpty() }?.maxOf { it.h }, or.takeIf { it.isNotEmpty() }?.minOf { it.l },
                past.takeIf { it.size >= 10 }?.average(),
                prev?.changePct, prev?.let { p -> if (p.high > p.low) (p.close - p.low) / (p.high - p.low) else null })
        }
        return out
    }

    /** A setup: when it applies to a day (null: not known), and the outcome counted. */
    private class Setup(val key: String, val setup: String, val outcome: String, val applies: (Day) -> Boolean?, val hit: (Day) -> Boolean?)

    private val SETUPS = listOf(
        Setup("gapup", "a gap up of 0.5% or more", "the gap filled (it came back to yesterday's close)",
            { d -> d.gapPct?.let { it >= 0.5 } }, { d -> d.prevClose?.let { d.low <= it } }),
        Setup("gapdown", "a gap down of 0.5% or more", "the gap filled (it came back to yesterday's close)",
            { d -> d.gapPct?.let { it <= -0.5 } }, { d -> d.prevClose?.let { d.high >= it } }),
        Setup("f15up", "the first 15 minutes up 0.2% or more", "it closed above its 09:30 price",
            { d -> d.first15Pct?.let { it >= 0.2 } }, { d -> d.at930?.let { d.close > it } }),
        Setup("f15down", "the first 15 minutes down 0.2% or more", "it closed below its 09:30 price",
            { d -> d.first15Pct?.let { it <= -0.2 } }, { d -> d.at930?.let { d.close < it } }),
        Setup("narrowor", "a narrow opening range (under 0.7 of its 20-day average by 10:00)", "it closed outside that range",
            { d -> d.orRatio?.let { it < 0.7 } }, { d -> if (d.orHigh != null && d.orLow != null) d.close > d.orHigh || d.close < d.orLow else null }),
        Setup("wideor", "a wide opening range (over 1.3 of its 20-day average by 10:00)", "it closed outside that range",
            { d -> d.orRatio?.let { it > 1.3 } }, { d -> if (d.orHigh != null && d.orLow != null) d.close > d.orHigh || d.close < d.orLow else null }),
        Setup("bigup", "the day after a rise of 1% or more", "it closed up again",
            { d -> d.prevChangePct?.let { it >= 1.0 } }, { d -> d.changePct?.let { it > 0 } }),
        Setup("bigdown", "the day after a fall of 1% or more", "it closed down again",
            { d -> d.prevChangePct?.let { it <= -1.0 } }, { d -> d.changePct?.let { it < 0 } }),
        Setup("closehigh", "the day after closing in the top fifth of its range", "it opened higher",
            { d -> d.prevClosePos?.let { it >= 0.8 } }, { d -> d.gapPct?.let { it > 0 } }),
        Setup("closelow", "the day after closing in the bottom fifth of its range", "it opened lower",
            { d -> d.prevClosePos?.let { it <= 0.2 } }, { d -> d.gapPct?.let { it < 0 } }),
    ) + DayOfWeek.entries.take(5).map { w ->
        val name = w.name.lowercase().replaceFirstChar { it.uppercase() }
        Setup("dow-${w.name.lowercase()}", "on ${name}s", "it closed up", { d -> d.date.dayOfWeek == w }, { d -> d.changePct?.let { it > 0 } })
    }

    /** Every setup counted over [bars] (two years of intraday candles of [market]). */
    fun run(market: Market, bars: List<Candle>): List<Finding> {
        val ds = days(bars)
        if (ds.isEmpty()) return emptyList()
        val mid = ds[ds.size / 2].date
        return SETUPS.mapNotNull { s ->
            val got = ds.mapNotNull { d -> if (s.applies(d) == true) s.hit(d)?.let { d.date to it } else null }
            val a = got.filter { it.first < mid }; val b = got.filter { it.first >= mid }
            if (got.size < MIN_DAYS || a.size < MIN_HALF || b.size < MIN_HALF) return@mapNotNull null
            fun rate(l: List<Pair<LocalDate, Boolean>>) = l.count { it.second }.toDouble() / l.size
            Finding(market, s.key, s.setup, s.outcome, got.size, rate(got), rate(a), rate(b))
        }
    }

    /**
     * Which held findings apply to today so far ([bars] end with today's candles; setups not known yet are skipped):
     * "today matches ...", in words.
     */
    fun today(findings: List<Finding>, bars: List<Candle>): List<String> {
        val d = days(bars).lastOrNull() ?: return emptyList()
        val keys = SETUPS.filter { it.applies(d) == true }.map { it.key }.toSet()
        return findings.filter { it.held && it.key in keys }.map { "Today: ${it.setup}. ${it.text()}" }
    }

    /**
     * Before [date]'s open: the held findings already known to apply from the last session in [bars] (a big day, where it
     * closed) and [date]'s weekday; the gap and the first 15 minutes are told after the open.
     */
    fun next(findings: List<Finding>, bars: List<Candle>, date: LocalDate): List<String> {
        val last = days(bars).lastOrNull()?.takeIf { it.date < date } ?: return emptyList()
        val d = Day(date, last.close, last.close, last.close, last.close, null, null, null, null, null,
            last.changePct, if (last.high > last.low) (last.close - last.low) / (last.high - last.low) else null)
        val keys = SETUPS.filter { it.applies(d) == true }.map { it.key }.toSet()
        return findings.filter { it.held && it.key in keys }.map { "Today is ${it.setup}. ${it.text()}" }
    }

    /** The findings in short: the ones that held first, then how many were coin tosses. */
    fun summary(findings: List<Finding>): List<String> {
        val held = findings.filter { it.held }.sortedByDescending { kotlin.math.abs(it.rate - 0.5) }
        val toss = findings.size - held.size
        return held.map { it.text() } + (if (toss > 0) listOf("$toss other setups were coin tosses over the two years: no edge in them.") else emptyList()) +
            listOf("This is what history shows, not a promise: markets change.")
    }
}
