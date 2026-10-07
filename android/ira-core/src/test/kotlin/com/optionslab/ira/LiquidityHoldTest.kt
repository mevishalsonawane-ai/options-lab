package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How long Liquidity 15+5 holds its trades: the minutes, the median, the groups, the words, the takeaway and the question. */
class LiquidityHoldTest {
    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|consider|switch to|increase|raise|better to|safe to)\\b")
    private val TODAY: LocalDate = LocalDate.of(2026, 10, 7)   // a Wednesday
    private val mon: LocalDate = LocalDate.of(2026, 10, 5)
    private val lastWed: LocalDate = LocalDate.of(2026, 9, 30)

    /** A closed 2-lot trade of [source] entered on [day] at [h]:[m], held [held] minutes, from 300 to [exit]. */
    private fun t(day: LocalDate, h: Int, m: Int, held: Long, exit: Double?, why: String, source: String = "liquidity15", live: Boolean = false): BotTrades.Trade {
        val at = LocalDateTime.of(day, LocalTime.of(h, m))
        return BotTrades.Trade(source, "BANKNIFTY26OCT56000CE", "CE", 70, 300.0, at, at.minusMinutes(5), exit, exit?.let { at.plusMinutes(held) },
            exit?.let { why }, 0.0, live, lot = 35)
    }

    private fun rows(vararg ts: BotTrades.Trade) = LiquidityRecord.rows(ts.toList())

    @Test fun minutesAndMedian() {
        val r = rows(t(mon, 10, 0, 42, 310.0, "next_liquidity"))[0]
        assertEquals(42L, LiquidityHold.minutes(r))
        assertNull(LiquidityHold.median(emptyList()))
        assertEquals(5.0, LiquidityHold.median(listOf(9, 1, 5)))
        assertEquals(4.0, LiquidityHold.median(listOf(1, 3, 5, 9)))
    }

    /** Winners held 30, 40, 50; losers 5, 8, 12; one more loser 3 minutes last week. */
    private val book = rows(
        t(mon, 10, 0, 30, 320.0, "next_liquidity"), t(mon, 11, 0, 5, 290.0, "index_stop"),
        t(mon.plusDays(1), 10, 0, 40, 330.0, "next_liquidity"), t(mon.plusDays(1), 12, 0, 8, 285.0, "index_stop"),
        t(TODAY, 10, 0, 50, 315.0, "time_stop"), t(TODAY, 11, 0, 12, 295.0, "failed_break"),
        t(lastWed, 10, 0, 3, 280.0, "stop"),
        // Not the arm's: another arm, a live trade, one still open.
        t(mon, 10, 0, 200, 400.0, "target", source = "orb"), t(mon, 10, 0, 300, 400.0, "next_liquidity", live = true),
        t(TODAY, 13, 0, 0, null, ""),
    )

    @Test fun theReadOfTheBook() {
        val r = LiquidityHold.read(book)
        assertEquals(7, r.n)
        assertEquals(12.0, r.median)
        assertEquals(3L, r.shortest!!.second); assertEquals(50L, r.longest!!.second)
        assertEquals(3, r.winners.n); assertEquals(40.0, r.winners.median)
        assertEquals(4, r.losers.n); assertEquals(6.5, r.losers.median)
        assertEquals(listOf(3L, 5L, 8L), r.quick.map { LiquidityHold.minutes(it) })
        assertEquals("index_stop" to 2, r.byReason.first().let { it.first to it.second.n })
    }

    @Test fun theTakeaway() {
        assertTrue(LiquidityHold.takeaway(LiquidityHold.read(book)).startsWith("It cuts its losers quicker"))
        // Losers held longer.
        val slow = rows(t(mon, 10, 0, 5, 320.0, "a"), t(mon, 10, 0, 6, 320.0, "a"), t(mon, 10, 0, 7, 320.0, "a"),
            t(mon, 10, 0, 40, 280.0, "b"), t(mon, 10, 0, 45, 280.0, "b"), t(mon, 10, 0, 50, 280.0, "b"))
        assertTrue(LiquidityHold.takeaway(LiquidityHold.read(slow)).startsWith("Its losers stay open longer"))
        // About the same.
        val same = rows(t(mon, 10, 0, 20, 320.0, "a"), t(mon, 10, 0, 22, 320.0, "a"), t(mon, 10, 0, 24, 320.0, "a"),
            t(mon, 10, 0, 21, 280.0, "b"), t(mon, 10, 0, 23, 280.0, "b"), t(mon, 10, 0, 25, 280.0, "b"))
        assertTrue(LiquidityHold.takeaway(LiquidityHold.read(same)).startsWith("Winners and losers stay open about as long"))
        // Too few of one side.
        assertTrue(LiquidityHold.takeaway(LiquidityHold.read(book.take(3))).startsWith("Too few"))
    }

    @Test fun theAnswerOverASpan() {
        val all = LiquidityHold.answer(LiquidityRecord.Q(), book, TODAY)
        assertTrue(all.startsWith("Liquidity 15+5 held its 7 closed paper trades so far a typical 12 min (the median)"), all)
        assertTrue("shortest 3 min (30 Sep" in all && "longest 50 min (7 Oct" in all, all)
        assertTrue("3 winners a typical 40 min; 4 losers a typical 7 min." in all, all)
        assertTrue("3 trades closed inside 10 minutes (0 won)" in all, all)
        assertTrue("By exit: index stop 2 trades, 7 min;" in all, all)
        assertTrue(all.endsWith(LiquidityHold.END), all)
        assertTrue(!ADVICE.containsMatchIn(all), all)
        // This week: last Wednesday's trade left out.
        val week = LiquidityHold.answer(LiquidityHold.asked("liquidity hold time this week")!!, book, TODAY)
        assertTrue(week.startsWith("Liquidity 15+5 held its 6 closed paper trades this week (from 5 Oct)"), week)
        // Last week: the one trade.
        val last = LiquidityHold.answer(LiquidityHold.asked("liquidity holding time last week")!!, book, TODAY)
        assertTrue(last.startsWith("Liquidity 15+5's one closed paper trade last week (28 Sep - 4 Oct) was open 3 min (30 Sep"), last)
        // Nothing to time.
        val none = LiquidityHold.answer(LiquidityRecord.Q(LiquidityRecord.Span.THIS_MONTH), emptyList(), TODAY)
        assertTrue(none.startsWith("Liquidity 15+5 has no closed paper trade this month"), none)
        // An hour and more said in hours.
        val long = LiquidityHold.answer(LiquidityRecord.Q(), rows(t(mon, 10, 0, 95, 320.0, "session_end")), TODAY)
        assertTrue("was open 1 h 35 min" in long, long)
    }

    @Test fun theQuestion() {
        for (s in listOf("how long does liquidity hold its trades", "how long does liquidity hold", "how long do liquidity trades last", "liquidity hold time",
            "liquidity trade duration", "do liquidity's losers last longer than its winners", "liquidity ke trades kitni der chalte hain",
            "liquidity kitni der trade rakhta hai", "how long are liquidity's trades open"))
            assertTrue(LiquidityHold.asked(s) != null, s)
        for (s in listOf("how is liquidity doing", "liquidity hold time today", "should liquidity hold longer", "how long has liquidity been armed",
            "how long until liquidity trades", "how long is liquidity holding right now", "how long does hero hold its trades", "how long do i hold my trades",
            "read my notes", "hold to talk", "change liquidity hold time"))
            assertNull(LiquidityHold.asked(s), s)
    }
}
