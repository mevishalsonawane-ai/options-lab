package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Speed round 9: a history's split into sessions ([MarketStory.sessions]) is kept by its candles - every market-history
 * answer (MoveTime, GiveBack, MultiDay, OpenReach, DayAfter and the rest) split the same months of 1-minute candles twice
 * per ask. Work is counted by the fresh splits made ([MarketStory.splitsDone]) and the kept split handed back (the very
 * same object), never by the clock.
 */
class SessionsKeptTest {
    private val today = LocalDate.of(2026, 10, 7)   // a Wednesday
    private val now = today.atTime(13, 0)

    /** [n] whole weekday sessions before today and today's morning, 1-minute candles that wander up and down. */
    private fun history(n: Int = 60, base: Double = 25000.0): List<Candle> {
        val days = ArrayList<LocalDate>()
        var d = today.minusDays(1)
        while (days.size < n) { if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) days += d; d = d.minusDays(1) }
        val out = ArrayList<Candle>()
        for ((k, day) in (days.reversed() + today).withIndex()) {
            var px = base + k * 10
            val mins = if (day == today) 225 else 375
            for (i in 0 until mins) {
                val c = px + Math.sin((i + k) / 7.0) * 6
                out += Candle(day.atTime(9, 15).plusMinutes(i.toLong()), px, maxOf(px, c) + 1, minOf(px, c) - 1, c)
                px = c
            }
        }
        return out
    }

    /** The split as it was always made: afresh, nothing kept. */
    private fun fresh(bars: List<Candle>): List<MarketStory.Session> =
        bars.groupBy { it.t.toLocalDate() }.toSortedMap().map { (d, b) -> MarketStory.Session(d, b.sortedBy { it.t }) }

    @BeforeTest @AfterTest fun forget() = MarketStory.forgetSplits()

    @Test fun aHistorySplitAgainIsNotSplitAgain() {
        val bars = history()
        val first = MarketStory.sessions(bars)
        assertEquals(1, MarketStory.splitsDone)
        assertEquals(fresh(bars), first)
        // Again, and from a copy of the same candles (an equal history): handed back as kept, nothing split again.
        assertSame(first, MarketStory.sessions(bars))
        assertSame(first, MarketStory.sessions(ArrayList(bars)))
        assertEquals(1, MarketStory.splitsDone)
    }

    @Test fun oneAskSplitsOnceAndTheNextAskNotAtAll() {
        val bars = history()
        val q = MoveTime.asked("how long does nifty usually take to move 50 points")!!
        val a = MoveTime.answer(q, Market.NIFTY, bars, today, now)
        // The record and today's line read one split (they were two).
        assertEquals(1, MarketStory.splitsDone)
        // The other market-history answers on the same candles split nothing more.
        val gb = GiveBack.asked("give back record for sensex")!!
        val md = MultiDay.asked("how far does nifty usually move in 3 sessions")!!
        val da = DayAfter.asked("after nifty falls 1% in a day what happens the next day")!!
        val answers = listOf(
            MoveTime.answer(q, Market.NIFTY, bars, today, now),
            GiveBack.answer(gb, Market.NIFTY, bars, today, now),
            MultiDay.answer(md, Market.NIFTY, bars, today, now),
            DayAfter.answer(da, Market.NIFTY, bars, today, now),
        )
        assertEquals(1, MarketStory.splitsDone)
        assertEquals(a, answers[0])
        // Word for word what each says with nothing kept.
        MarketStory.forgetSplits()
        assertEquals(answers[0], MoveTime.answer(q, Market.NIFTY, bars, today, now))
        MarketStory.forgetSplits()
        assertEquals(answers[1], GiveBack.answer(gb, Market.NIFTY, bars, today, now))
        MarketStory.forgetSplits()
        assertEquals(answers[2], MultiDay.answer(md, Market.NIFTY, bars, today, now))
        MarketStory.forgetSplits()
        assertEquals(answers[3], DayAfter.answer(da, Market.NIFTY, bars, today, now))
    }

    @Test fun newOrChangedCandlesAreSplitAfresh() {
        val bars = ArrayList(history())
        val first = MarketStory.sessions(bars)
        // A new minute (the live feed): split again, and the new minute is in today's session.
        val next = bars.last().let { Candle(it.t.plusMinutes(1), it.c, it.c + 5, it.c - 5, it.c + 2) }
        bars += next
        val grown = MarketStory.sessions(bars)
        assertEquals(2, MarketStory.splitsDone)
        assertNotSame(first, grown)
        assertEquals(next, grown.last().bars.last())
        assertEquals(fresh(bars), grown)
        // A candle changed in place (same count, same last candle): never mistaken for the one kept.
        val i = bars.size / 2
        bars[i] = bars[i].copy(h = bars[i].h + 100)
        val changed = MarketStory.sessions(bars)
        assertEquals(3, MarketStory.splitsDone)
        assertEquals(fresh(bars), changed)
    }

    @Test fun keptSplitsStayFewAndSmallOrHugeHistoriesAreNotKept() {
        for (k in 0 until 10) MarketStory.sessions(history(n = 3, base = 20000.0 + k))
        assertTrue(MarketStory.splitsKept <= 6, "kept ${MarketStory.splitsKept}")
        MarketStory.forgetSplits()
        // A handful of candles (under a day): split each time, nothing kept.
        val few = history().take(100)
        MarketStory.sessions(few); MarketStory.sessions(few)
        assertEquals(2, MarketStory.splitsDone)
        assertEquals(0, MarketStory.splitsKept)
        assertEquals(fresh(few), MarketStory.sessions(few))
        // Empty: nothing kept, nothing said.
        assertEquals(emptyList(), MarketStory.sessions(emptyList()))
        assertEquals(0, MarketStory.splitsKept)
    }

    @Test fun theResetHookForgets() {
        val bars = history()
        val first = MarketStory.sessions(bars)
        assertEquals(1, MarketStory.splitsKept)
        MarketStory.forgetSplits()
        assertEquals(0, MarketStory.splitsKept)
        assertEquals(0, MarketStory.splitsDone)
        val again = MarketStory.sessions(bars)
        assertNotSame(first, again)
        assertEquals(first, again)
        assertEquals(1, MarketStory.splitsDone)
    }
}
