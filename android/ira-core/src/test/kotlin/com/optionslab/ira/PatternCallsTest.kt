package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatternCallsTest {
    private val day = LocalDate.of(2026, 10, 5)
    private val today = day

    /** 1-minute bars of [d] from 09:15, the close at minute i given by [px]. */
    private fun bars(d: LocalDate, n: Int, px: (Int) -> Double) = List(n) { i ->
        val c = px(i); Candle(d.atTime(9, 15).plusMinutes(i.toLong()), c, c, c, c)
    }

    private fun call(k: PatternKind, at: LocalDateTime, price: Double = 100.0, m: Market = Market.NIFTY, minutes: Int = 15) =
        PatternCalls.call(m, Pattern(k, minutes, at, price))!!

    /** [way] of [n] settled calls of [k] going its way at every horizon, the newest [daysAgo] days ago. */
    private fun record(k: PatternKind, n: Int, way: Int, daysAgo: Long = 1): List<PatternCalls.Call> = List(n) { i ->
        call(k, today.minusDays(daysAgo + i).atTime(10, 0)).copy(went = PatternCalls.HORIZONS.associateWith {
            if (i < way) PatternCalls.Went.WAY else PatternCalls.Went.AGAINST })
    }

    @Test fun onlyDirectionalPatternsAreFollowed() {
        assertNull(PatternCalls.call(Market.NIFTY, Pattern(PatternKind.DOJI, 15, day.atTime(10, 0), 100.0)))
        assertNull(PatternCalls.call(Market.NIFTY, Pattern(PatternKind.HAMMER, 15, day.atTime(10, 0), Double.NaN)))
        assertNotNull(PatternCalls.call(Market.NIFTY, Pattern(PatternKind.HAMMER, 15, day.atTime(10, 0), 100.0)))
    }

    @Test fun mentionedTwiceCountsOnce() {
        val c = call(PatternKind.HAMMER, day.atTime(10, 0))
        val log = PatternCalls.add(PatternCalls.add(emptyList(), listOf(c), today), listOf(c, c), today)
        assertEquals(1, log.size)
        // Older than the keeping window: dropped.
        assertTrue(PatternCalls.add(emptyList(), listOf(call(PatternKind.HAMMER, day.minusDays(200).atTime(10, 0))), today).isEmpty())
    }

    @Test fun settlesEachHorizonFromThePhonesCandles() {
        // Bullish engulfing on the 15-minute candle 10:00-10:15 closing at 100; the price then rises 0.1 a minute.
        val c = call(PatternKind.BULLISH_ENGULFING, day.atTime(10, 0))
        val start = 60  // 10:15 is minute 60 of the session
        val b = bars(day, 375) { i -> if (i < start) 100.0 else 100.0 + 0.1 * (i - start + 1) }
        // Only up to 10:40: 15 minutes is known, 30 and 60 are not yet.
        val part = PatternCalls.settle(listOf(c), Market.NIFTY, b.filter { !it.t.isAfter(day.atTime(10, 40)) }).single()
        assertEquals(PatternCalls.Went.WAY, part.went[15])
        assertNull(part.went[30]); assertFalse(part.settled)
        val all = PatternCalls.settle(listOf(c), Market.NIFTY, b).single()
        assertTrue(all.settled)
        assertTrue(PatternCalls.HORIZONS.all { all.went[it] == PatternCalls.Went.WAY })
        // A bearish one at the same place went against it.
        val bear = PatternCalls.settle(listOf(call(PatternKind.BEARISH_ENGULFING, day.atTime(10, 0))), Market.NIFTY, b).single()
        assertTrue(PatternCalls.HORIZONS.all { bear.went[it] == PatternCalls.Went.AGAINST })
        // Another market's candles say nothing about it.
        assertTrue(PatternCalls.settle(listOf(c), Market.BANKNIFTY, b).single().went.isEmpty())
    }

    @Test fun afterTheCloseIsNeverGuessed() {
        // A pattern on the last 15-minute candle (15:00-15:15): 15 minutes reaches 15:30 (the last bar), 30 and 60 never come.
        val c = call(PatternKind.HAMMER, day.atTime(15, 0))
        val b = bars(day, 375) { 100.0 } + bars(day.plusDays(1), 30) { 101.0 }
        val s = PatternCalls.settle(listOf(c), Market.NIFTY, b).single()
        assertEquals(PatternCalls.Went.FLAT, s.went[15])
        assertEquals(PatternCalls.Went.NONE, s.went[30]); assertEquals(PatternCalls.Went.NONE, s.went[60])
        // Unmeasured calls never count in the record.
        assertEquals(0, PatternCalls.record(listOf(s), Market.NIFTY, 15, PatternKind.HAMMER, today.plusDays(1)).n)
        // The same day, before the hour has passed: still waiting.
        val waiting = PatternCalls.settle(listOf(c), Market.NIFTY, bars(day, 375) { 100.0 }).single()
        assertNull(waiting.went[60])
    }

    @Test fun theRecordIsTheNewestCallsAndFades() {
        val log = record(PatternKind.HAMMER, 30, 10)
        val r = PatternCalls.record(log, Market.NIFTY, 15, PatternKind.HAMMER, today)
        assertEquals(PatternCalls.LAST, r.n)
        assertEquals(10, r.way)                                    // the newest 20: the 10 that worked and 10 that didn't
        assertTrue(r.faded > r.rate)                               // the ones that worked are the newer ones
        // Other markets and charts are their own record.
        assertEquals(0, PatternCalls.record(log, Market.NIFTY, 60, PatternKind.HAMMER, today).n)
        assertEquals(0, PatternCalls.record(log, Market.BANKNIFTY, 15, PatternKind.HAMMER, today).n)
    }

    @Test fun aClearlyPoorRecordIsQuietOnly() {
        assertTrue(PatternCalls.quiet(record(PatternKind.SHOOTING_STAR, 14, 2), Market.NIFTY, 15, PatternKind.SHOOTING_STAR, today))
        // Too few, or not clearly poor: still said.
        assertFalse(PatternCalls.quiet(record(PatternKind.SHOOTING_STAR, 8, 0), Market.NIFTY, 15, PatternKind.SHOOTING_STAR, today))
        assertFalse(PatternCalls.quiet(record(PatternKind.SHOOTING_STAR, 14, 6), Market.NIFTY, 15, PatternKind.SHOOTING_STAR, today))
    }

    @Test fun theLineSaysTheRecord() {
        val p = Pattern(PatternKind.BULLISH_ENGULFING, 15, day.atTime(11, 0), 100.0)
        assertNull(PatternCalls.line(emptyList(), Market.NIFTY, p, today))
        val l = PatternCalls.line(record(PatternKind.BULLISH_ENGULFING, 12, 7), Market.NIFTY, p, today)!!
        assertTrue("7 of the last 12 times" in l, l)
        assertTrue("within 30 minutes" in l && "Nifty 15-minute" in l, l)
        assertTrue("too few" in PatternCalls.line(record(PatternKind.BULLISH_ENGULFING, 3, 1), Market.NIFTY, p, today)!!)
        assertTrue("unless you ask" in PatternCalls.line(record(PatternKind.BULLISH_ENGULFING, 14, 1), Market.NIFTY, p, today)!!)
        assertFalse(Regex("(?i)\\b(buy|sell|should)\\b").containsMatchIn(l))
    }

    private fun snap(ps: List<Pattern>) = Snapshot(Market.NIFTY, day.atTime(12, 0), true, 100.0, 99.0, 99.0, 101.0, 98.0, null, null,
        emptyList(), null, null, emptyList(), emptyList(), ps)

    @Test fun unaskedAPoorKindIsNotBroughtUpAskedItIs() {
        val star = Pattern(PatternKind.SHOOTING_STAR, 15, day.atTime(11, 30), 100.0)
        val hammer = Pattern(PatternKind.HAMMER, 15, day.atTime(11, 0), 100.0)
        val poor = record(PatternKind.SHOOTING_STAR, 14, 1)
        val ira = Ira(PatternBook(), poor)
        val overview = ira.answer("how is nifty doing", mapOf(Market.NIFTY to snap(listOf(star, hammer))), emptyList())
        assertFalse("shooting star" in overview.text, overview.text)
        assertTrue("hammer" in overview.text, overview.text)
        assertEquals(listOf(PatternKind.HAMMER), overview.calls.map { it.kind })
        val asked = ira.answer("any candle patterns on nifty", mapOf(Market.NIFTY to snap(listOf(star, hammer))), emptyList())
        assertTrue("shooting star" in asked.text && "unless you ask" in asked.text, asked.text)
        assertEquals(listOf(PatternKind.SHOOTING_STAR), asked.calls.map { it.kind })
        // Without a record, nothing changes from before.
        assertTrue("shooting star" in Ira().answer("how is nifty doing", mapOf(Market.NIFTY to snap(listOf(star))), emptyList()).text)
    }

    @Test fun askedAboutTheCalls() {
        assertTrue(PatternCalls.asked("which patterns work on Nifty?"))
        assertTrue(PatternCalls.asked("How good are your pattern calls?"))
        assertTrue(PatternCalls.asked("what's your pattern hit rate"))
        assertFalse(PatternCalls.asked("what is a hammer?"))
        assertFalse(PatternCalls.asked("any patterns on nifty"))
        assertFalse(Vetting.asked("which patterns work on Nifty?"))
    }

    @Test fun saysTheRecordAsked() {
        assertTrue("haven't followed" in PatternCalls.say(emptyList(), listOf(Market.NIFTY), today))
        val log = record(PatternKind.HAMMER, 10, 7) + record(PatternKind.SHOOTING_STAR, 14, 1) + record(PatternKind.BREAKOUT_UP, 2, 2)
        val s = PatternCalls.say(log, listOf(Market.NIFTY), today)
        assertTrue(s.startsWith("My pattern calls on Nifty on this phone, Boss: 10 of the last 26"), s)
        assertTrue(s.indexOf("hammer") < s.indexOf("shooting star"), s)
        assertTrue("Too few to go by yet" in s && "I no longer bring up shooting star" in s, s)
        assertTrue("haven't followed" in PatternCalls.say(log, listOf(Market.BANKNIFTY), today))
    }

    @Test fun savedAndReadBack() {
        val log = record(PatternKind.HAMMER, 3, 2) + call(PatternKind.BREAKOUT_DOWN, day.atTime(9, 30), 24_512.35, Market.BANKNIFTY, 60)
        val back = PatternCalls.load(PatternCalls.save(log) + "\nnot a line")
        assertEquals(log, back)
    }
}
