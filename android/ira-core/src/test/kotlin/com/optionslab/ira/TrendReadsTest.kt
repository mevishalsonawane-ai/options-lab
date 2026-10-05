package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrendReadsTest {
    private val day = LocalDate.of(2026, 10, 5)

    /** A full session of 1-minute bars on [d] (09:15 to 15:29), the close at minute i given by [px]. */
    private fun session(d: LocalDate, px: (Int) -> Double, n: Int = 375) = List(n) { i ->
        val c = px(i); val o = if (i == 0) c else px(i - 1)
        Candle(d.atTime(9, 15).plusMinutes(i.toLong()), o, maxOf(o, c), minOf(o, c), c)
    }

    private val up: (Int) -> Double = { i -> 24000.0 + i }
    /** Up for the first hour and a half, then back to the open: not the trend it looked like. */
    private val turn: (Int) -> Double = { i -> if (i < 90) 24000.0 + i else 24090.0 - (i - 90) * 90.0 / 285 }
    private val flat: (Int) -> Double = { i -> 24000.0 + (if (i % 20 < 10) i % 10 else 10 - i % 10) }

    private fun at(d: LocalDate, h: Int, m: Int) = d.atTime(h, m)

    @Test fun aReadInSessionIsNotedWithItsKind() {
        val bars = session(day, up)
        val c = assertNotNull(TrendReads.call(Market.NIFTY, bars.take(76), at(day, 10, 31)))
        assertEquals(TrendReads.Kind.UP, c.kind)
        assertEquals(at(day, 10, 31), c.at)
        assertEquals(24075.0, c.price)
        // A range day is a call too.
        assertEquals(TrendReads.Kind.RANGE, TrendReads.call(Market.NIFTY, session(day, flat).take(120), at(day, 11, 15))?.kind)
    }

    @Test fun noCallAfterTheSessionLateInItOrFromOldCandles() {
        val bars = session(day, up)
        assertNull(TrendReads.call(Market.NIFTY, bars, at(day, 18, 0)))                      // after the close: a description
        assertNull(TrendReads.call(Market.NIFTY, bars.take(370), at(day, 15, 25)))           // the last 15 minutes
        assertNull(TrendReads.call(Market.NIFTY, bars.take(76), at(day, 11, 30)))            // candles an hour old
        assertNull(TrendReads.call(Market.NIFTY, bars.take(10), at(day, 9, 25)))             // too early to read
        assertNull(TrendReads.call(Market.GOLD, bars.take(76), at(day, 10, 31)))
    }

    @Test fun saidAgainTheSameDayCountsOnceAChangedReadIsANewCall() {
        val a = TrendReads.Call(Market.NIFTY, at(day, 10, 0), TrendReads.Kind.UP, 1.0)
        var log = TrendReads.add(emptyList(), a, day)
        log = TrendReads.add(log, a.copy(at = at(day, 11, 0)), day)
        assertEquals(1, log.size)
        log = TrendReads.add(log, a.copy(at = at(day, 12, 0), kind = TrendReads.Kind.RANGE), day)
        assertEquals(2, log.size)
        log = TrendReads.add(log, a.copy(market = Market.BANKNIFTY), day)
        assertEquals(3, log.size)
        assertTrue(TrendReads.add(emptyList(), a.copy(at = at(day.minusDays(200), 10, 0)), day).isEmpty())
    }

    @Test fun scoredAtTheCloseByTheSameMeasure() {
        val held = assertNotNull(TrendReads.call(Market.NIFTY, session(day, up).take(76), at(day, 10, 31)))
        val turned = assertNotNull(TrendReads.call(Market.NIFTY, session(day, turn).take(76), at(day, 10, 31)))
        assertEquals(TrendReads.Kind.UP, turned.kind)
        // Before the close (and its few minutes after): not scored yet.
        assertFalse(TrendReads.settle(listOf(held), Market.NIFTY, session(day, up), at(day, 15, 35)).single().settled)
        val h = TrendReads.settle(listOf(held), Market.NIFTY, session(day, up), at(day, 16, 0)).single()
        assertEquals(TrendReads.Verdict.HELD, h.verdict)
        assertEquals(TrendReads.Kind.UP, h.ended)
        val t = TrendReads.settle(listOf(turned), Market.NIFTY, session(day, turn), at(day, 16, 0)).single()
        assertEquals(TrendReads.Verdict.TURNED, t.verdict)
        assertEquals(TrendReads.Kind.RANGE, t.ended)
        // Another index's candles say nothing about it.
        assertFalse(TrendReads.settle(listOf(held), Market.BANKNIFTY, session(day, up), at(day, 16, 0)).single().settled)
        // The next day's candles on the phone do not change how this day ended.
        val both = session(day, up) + session(day.plusDays(1), { i -> 24374.0 - i })
        assertEquals(TrendReads.Verdict.HELD, TrendReads.settle(listOf(held), Market.NIFTY, both, at(day.plusDays(1), 16, 0)).single().verdict)
    }

    @Test fun neverGuessedWithoutCandlesToTheClose() {
        val c = assertNotNull(TrendReads.call(Market.NIFTY, session(day, up).take(76), at(day, 10, 31)))
        val part = session(day, up).take(200)                                               // the phone stopped at 12:34
        assertFalse(TrendReads.settle(listOf(c), Market.NIFTY, part, at(day, 16, 0)).single().settled)
        assertEquals(TrendReads.Verdict.NONE, TrendReads.settle(listOf(c), Market.NIFTY, part, at(day.plusDays(1), 16, 0)).single().verdict)
    }

    private fun scored(kind: TrendReads.Kind, v: TrendReads.Verdict, d: LocalDate, h: Int = 10, ended: TrendReads.Kind? = null) =
        TrendReads.Call(Market.NIFTY, at(d, h, 0), kind, 24000.0, v, ended ?: if (v == TrendReads.Verdict.HELD) kind else null, 24050.0)

    @Test fun theRecordIsHeldTurnedAndInBetweenByKindAndTime() {
        val log = listOf(
            scored(TrendReads.Kind.UP, TrendReads.Verdict.HELD, day.minusDays(1)),
            scored(TrendReads.Kind.UP, TrendReads.Verdict.HELD, day.minusDays(2), 12),
            scored(TrendReads.Kind.RANGE, TrendReads.Verdict.HELD, day.minusDays(3), 14),
            scored(TrendReads.Kind.DOWN, TrendReads.Verdict.TURNED, LocalDate.of(2026, 10, 1), 9, TrendReads.Kind.UP),
            scored(TrendReads.Kind.RANGE, TrendReads.Verdict.MIXED, LocalDate.of(2026, 10, 2), 10),
            TrendReads.Call(Market.NIFTY, at(day, 10, 0), TrendReads.Kind.UP, 24000.0),                    // today's, waiting
            scored(TrendReads.Kind.UP, TrendReads.Verdict.HELD, LocalDate.of(2026, 9, 15)),               // last month
        )
        val s = TrendReads.say(log, emptyList(), TrendReads.Span.MONTH, day)
        assertTrue(s.startsWith("My trend and range reads this month, Boss: 3 of 5 matched how the day ended, 1 turned into another kind of day, " +
            "1 ended in between - neither a clear trend nor a clear range."), s)
        assertTrue("trend-like up 2 of 2; trend-like down 0 of 1; range-like 1 of 2" in s, s)
        assertTrue("before 11:00 1 of 3; 11:00 to 13:30 1 of 1; after 13:30 1 of 1" in s, s)
        assertTrue("Turned: Nifty 1 Oct, read trend-like down at 09:00, ended trend-like up (+50 points from my read to the close)" in s, s)
        assertTrue("1 read is waiting for the close to be scored." in s, s)
        assertTrue("not a forecast" in s && "Boss" in s)
        assertFalse(Regex("(?i)\\b(buy|sell|should)\\b").containsMatchIn(s), s)
        // Last month alone, and too few to go by.
        val last = TrendReads.say(log, emptyList(), TrendReads.Span.LAST_MONTH, day)
        assertTrue(last.startsWith("My trend and range reads last month, Boss: 1 of 1 matched how the day ended. Too few to go by yet."), last)
        // Nothing scored yet: said so, with what is noted (never Boss's words).
        val none = TrendReads.say(emptyList(), listOf(Market.BANKNIFTY), TrendReads.Span.ALL, day)
        assertTrue(none.startsWith("I have no trend or range reads on BankNifty on this phone scored yet, Boss."), none)
        assertTrue("never your words" in none)
    }

    @Test fun savedAndLoaded() {
        val log = listOf(scored(TrendReads.Kind.DOWN, TrendReads.Verdict.TURNED, day, 10, TrendReads.Kind.RANGE),
            TrendReads.Call(Market.BANKNIFTY, at(day, 11, 7), TrendReads.Kind.RANGE, 51234.5))
        assertEquals(log, TrendReads.load(TrendReads.save(log)))
        assertTrue(TrendReads.load("junk\nNIFTY|x").isEmpty())
    }

    @Test fun askedInTheWaysBossSaysIt() {
        for (s in listOf("how often were your trend reads right this month", "how accurate are your trend reads", "how accurate are your structure reads",
            "how good are your trend calls", "how good have your trend calls been", "were your trend reads right this week", "did your trend calls hold",
            "your trend read record", "jarvis trend call accuracy", "trend read hit rate", "how often are your trend calls right",
            "how reliable are your trend and range reads", "did your structure reads hold up", "score your trend reads",
            "tumhare trend reads kitne sahi the", "trend calls kaise rahe", "how right were your range calls today", "jarvis's trend read accuracy"))
            assertTrue(TrendReads.asked(s), s)
        for (s in listOf("what's the trend", "trend or range so far", "what's the structure today", "how good are your pattern calls",
            "is your read still valid", "what would change your mind", "how accurate are you", "is nifty trending", "how was my trading this month",
            "how often does nifty trend", "how many trend days this month"))
            assertFalse(TrendReads.asked(s), s)
        assertEquals(TrendReads.Span.MONTH, TrendReads.span("how often were your trend reads right this month"))
        assertEquals(TrendReads.Span.LAST_MONTH, TrendReads.span("your trend read record last month"))
        assertEquals(TrendReads.Span.WEEK, TrendReads.span("were your trend reads right this week"))
        assertEquals(TrendReads.Span.TODAY, TrendReads.span("how right were your range calls today"))
        assertEquals(TrendReads.Span.ALL, TrendReads.span("how accurate are your trend reads"))
    }
}
