package com.optionslab.ira

import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatternExpertTest {
    /**
     * Sessions of 15-minute candles: a slow fall, then at 11:15 a red candle and a bullish engulfing, after which the
     * price rises (when [works]) - so the engulfing went its way every time.
     */
    private fun session(d: LocalDate, start: Double, works: Boolean): List<Candle> {
        val out = ArrayList<Candle>(); var p = start
        for (i in 0 until 25) {
            val t = LocalDateTime.of(d, LocalTime.of(9, 15)).plusMinutes(15L * i)
            val c = when {
                i == 7 -> Candle(t, p, p + 2, p - 30, p - 28)                       // red
                i == 8 -> Candle(t, p - 30, p + 40, p - 32, p + 35)                // engulfs it
                i in 9..14 -> { val n = p + if (works) 20 else -20; Candle(t, p, maxOf(p, n) + 3, minOf(p, n) - 3, n) }
                else -> Candle(t, p, p + 4, p - 6, p - 2)
            }
            out += c; p = c.c
        }
        return out
    }

    /** The day's option prices: every BankNifty call near [spot], rising 1 point a minute after 11:30 ([up]) or falling. */
    private fun options(d: LocalDate, spot: Double, up: Boolean): Session {
        val mins = IntArray(376) { 555 + it }
        val atm = Math.round(spot / 100) * 100.0
        val series = (-8..8).flatMap { k -> listOf(Right.CE, Right.PE).map { r ->
            val close = DoubleArray(mins.size) { j -> val m = mins[j]; 200.0 + (if (m > 690) (m - 690) * (if (up == (r == Right.CE)) 1.0 else -1.0) else 0.0) }
            Series(d.plusDays(2), atm + k * 100, r, 15, mins, close.map { maxOf(it, 1.0) }.toDoubleArray(), null, null, null, null, LongArray(mins.size)) } }
        return Session(d, 15, series)
    }

    private fun weekdays(n: Int): List<LocalDate> {
        var d = LocalDate.of(2024, 10, 1); val out = ArrayList<LocalDate>()
        while (out.size < n) { if (d.dayOfWeek.value <= 5) out += d; d = d.plusDays(1) }
        return out
    }

    private fun snap(price: Double, up: Boolean?, above: Double? = null) = Snapshot(Market.BANKNIFTY, LocalDateTime.now(), true, price, price, price, price, price,
        null, null, up?.let { listOf(TrendRead(15, it, price, null)) } ?: emptyList(), null, null,
        above?.let { listOf(Level("yesterday's high", it)) } ?: emptyList(), listOf(Level("today's low", price - 300)), emptyList())

    @Test fun aPatternThatWorkedInBothYearsIsSuggestedWithWhy() {
        var p = 52_000.0
        val ds = weekdays(80)
        val spots = ArrayList<Double>()
        val c = ds.flatMap { d -> session(d, p, true).also { spots += it[8].c; p = it.last().c } }
        // On the index alone it held, but untested as an option trade it is never suggested.
        val bare = PatternExpert.edges(Market.BANKNIFTY, 15, c).single { it.kind == PatternKind.BULLISH_ENGULFING }
        assertTrue(bare.held && !bare.tradable, bare.text()); assertTrue(bare.text().contains("Not enough real option prices"))
        val e = PatternExpert.edges(Market.BANKNIFTY, 15, c, ds.mapIndexed { i, d -> options(d, spots[i], up = true) }.asSequence())
        val eng = e.single { it.kind == PatternKind.BULLISH_ENGULFING }
        assertTrue(eng.tradable, eng.text()); assertEquals(1.0, eng.rate); assertTrue(eng.text().contains("(ATM, 30-point stop, +60 target, profit lock, 1 point for costs)"), eng.text())
        assertEquals(eng.cases, eng.priced); assertEquals(59.0, eng.optAvg, 1e-9)   // the +60 target less 1 point of costs
        // Today: the engulfing has just closed (11:30 close, now 11:31).
        val today = session(LocalDate.of(2026, 10, 1), p, true).take(9)
        val now = today.last().t.plusMinutes(16)
        val v = PatternExpert.judge(15, c + today, snap(today.last().c, up = true), e, TradeCheck.Level.GO, now, 0)
        val idea = assertNotNull(v.idea, v.reasons.toString())
        assertTrue(idea.call)
        assertTrue(idea.why.contains("a bullish engulfing just closed") && idea.why.contains("What can go wrong") && idea.why.contains("the stop 30 points below the price paid") && idea.why.endsWith("History, not a promise."), idea.why)
        // Against the trend, too close to a level, too late, a stop from the trade check, or the day's limit: none.
        assertNull(PatternExpert.judge(15, c + today, snap(today.last().c, up = false), e, TradeCheck.Level.GO, now, 0).idea)
        assertNull(PatternExpert.judge(15, c + today, snap(today.last().c, up = true, above = today.last().c + 20), e, TradeCheck.Level.GO, now, 0).idea)
        assertNull(PatternExpert.judge(15, c + today, snap(today.last().c, up = true), e, TradeCheck.Level.STOP, now, 0).idea)
        assertNull(PatternExpert.judge(15, c + today, snap(today.last().c, up = true), e, TradeCheck.Level.GO, now, 2).idea)
        assertNull(PatternExpert.judge(15, c + today, snap(today.last().c, up = true), e, TradeCheck.Level.GO, now.plusMinutes(40), 0).idea)
        // Expiry day after 13:00, or the trades' own loss limit hit: none.
        assertNull(PatternExpert.judge(15, c + today, snap(today.last().c, up = true), e, TradeCheck.Level.GO, now, 0, lossLimitHit = true).idea)
        // When the option trade lost in the second year, the index record alone is not enough.
        val half = ds.size / 2
        val mixed = PatternExpert.edges(Market.BANKNIFTY, 15, c, ds.mapIndexed { i, d -> options(d, spots[i], up = i < half) }.asSequence())
            .single { it.kind == PatternKind.BULLISH_ENGULFING }
        assertTrue(mixed.held && !mixed.tradable, mixed.text()); assertTrue(mixed.text().endsWith("so I don't suggest it."))
    }

    @Test fun aCoinTossPatternIsNeverSuggested() {
        var p = 52_000.0
        val c = weekdays(80).mapIndexed { i, d -> session(d, p, i % 2 == 0).also { p = it.last().c } }.flatten()
        val e = PatternExpert.edges(Market.BANKNIFTY, 15, c)
        assertTrue(!e.single { it.kind == PatternKind.BULLISH_ENGULFING }.held)
        val today = session(LocalDate.of(2026, 10, 1), p, true).take(9)
        val v = PatternExpert.judge(15, c + today, snap(today.last().c, up = true), e, TradeCheck.Level.GO, today.last().t.plusMinutes(16), 0)
        assertNull(v.idea); assertTrue(v.reasons.any { it.endsWith("no edge.") }, v.reasons.toString())
        assertTrue(PatternExpert.best(e).none { "bullish engulfing" in it })
        assertTrue(PatternExpert.best(emptyList()).single().startsWith("No candle pattern held up"))
    }

    @Test fun suggestAndExplainAreAskedInPlainWords() {
        assertEquals(setOf(Topic.SUGGEST), Ask.parse("Jarvis, what should I buy now?").topics)
        assertEquals(setOf(Topic.SUGGEST), Ask.parse("suggest me a trade").topics)
        assertEquals(setOf(Topic.SUGGEST), Ask.parse("any trade ideas?").topics)
        val q = Ask.parse("What is a hammer?")
        assertEquals(setOf(Topic.EXPLAIN), q.topics); assertEquals(PatternKind.HAMMER, q.pattern)
        assertEquals(PatternKind.DOJI, Ask.parse("explain doji").pattern)
        assertTrue(PatternExpert.explain(PatternKind.HAMMER, emptyList()).startsWith("A hammer: a small body at the top"))
        // Orders and backtests are unchanged.
        assertEquals(Topic.ORDER, Ask.parse("buy 1 lot nifty atm ce").topics.first())
        assertTrue(Topic.BACKTEST in Ask.parse("backtest the hammer on banknifty").topics)
    }
}
