package com.optionslab.ira

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
        val c = ds.flatMap { d -> session(d, p, true).also { p = it.last().c } }
        val e = PatternExpert.edges(Market.BANKNIFTY, 15, c)
        val eng = e.single { it.kind == PatternKind.BULLISH_ENGULFING }
        assertTrue(eng.held, eng.text()); assertEquals(1.0, eng.rate)
        // Today: the engulfing has just closed (11:30 close, now 11:31).
        val today = session(LocalDate.of(2026, 10, 1), p, true).take(9)
        val now = today.last().t.plusMinutes(16)
        val v = PatternExpert.judge(15, c + today, snap(today.last().c, up = true), e, TradeCheck.Level.GO, now, 0)
        val idea = assertNotNull(v.idea, v.reasons.toString())
        assertTrue(idea.call)
        assertTrue(idea.why.contains("a bullish engulfing just closed") && idea.why.contains("What can go wrong") && idea.why.endsWith("History, not a promise."), idea.why)
        // Against the trend, too close to a level, too late, a stop from the trade check, or the day's limit: none.
        assertNull(PatternExpert.judge(15, c + today, snap(today.last().c, up = false), e, TradeCheck.Level.GO, now, 0).idea)
        assertNull(PatternExpert.judge(15, c + today, snap(today.last().c, up = true, above = today.last().c + 20), e, TradeCheck.Level.GO, now, 0).idea)
        assertNull(PatternExpert.judge(15, c + today, snap(today.last().c, up = true), e, TradeCheck.Level.STOP, now, 0).idea)
        assertNull(PatternExpert.judge(15, c + today, snap(today.last().c, up = true), e, TradeCheck.Level.GO, now, 2).idea)
        assertNull(PatternExpert.judge(15, c + today, snap(today.last().c, up = true), e, TradeCheck.Level.GO, now.plusMinutes(40), 0).idea)
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
