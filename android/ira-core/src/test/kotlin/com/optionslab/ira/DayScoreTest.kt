package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayScoreTest {
    private val today = LocalDate.of(2026, 10, 7)
    private fun at(h: Int, m: Int, d: LocalDate = today): LocalDateTime = d.atTime(h, m)

    /** Minute candles from [from] for [n] minutes, from [start] moving [step] a minute. */
    private fun bars(from: LocalDateTime, n: Int, start: Double, step: Double): List<Candle> = (0 until n).map { i ->
        val o = start + step * i; val c = o + step
        Candle(from.plusMinutes(i.toLong()), o, maxOf(o, c), minOf(o, c), c)
    }

    private fun trip(sym: String, dir: Int, entry: Double, exit: Double, open: LocalDateTime, close: LocalDateTime, owner: String = "Manual") =
        DayScore.Trip(sym, dir, 75, entry, exit, open, close, (exit - entry) * dir * 75, owner)

    /** Six of his own earlier trades held 10 minutes each: his usual hold is 10 min. */
    private val earlier = (1..6).map { i ->
        val d = today.minusDays(i.toLong()); trip("NIFTY26O1325000CE", 1, 100.0, 105.0, at(10, 0, d), at(10, 10, d))
    }

    @Test fun asksAndNot() {
        listOf("my scorecard today", "how's today's scorecard", "aaj ka scorecard", "my trades so far today", "how are my trades today so far",
            "did i trade against the trend today", "did I trade against the index today", "how many of my trades were against the trend today",
            "how long did i hold my trades today", "did i hold my trades longer than usual today", "aaj maine trend ke against trade kiya kya",
            "were my exits before the best price today", "day score card").forEach { assertTrue(DayScore.asked(it), it) }
        listOf("should i trade against the trend today", "close my trades today", "why did i trade against the trend today",
            "my scorecard this week", "your scorecard today", "the bots scorecard today", "how is the market today", "go over my trades today",
            "how was my last trade", "what's my pnl today", "gold scorecard today", "is the trend up today", "buy nifty call").forEach {
            assertFalse(DayScore.asked(it), it)
        }
    }

    @Test fun leans() {
        assertEquals(DayScore.Lean.UP, DayScore.lean("NIFTY26O1325000CE", 1))
        assertEquals(DayScore.Lean.DOWN, DayScore.lean("NIFTY26O1325000CE", -1))
        assertEquals(DayScore.Lean.DOWN, DayScore.lean("NIFTY26O1325000PE", 1))
        assertEquals(DayScore.Lean.UP, DayScore.lean("NIFTY26O1325000PE", -1))
        assertEquals(DayScore.Lean.UP, DayScore.lean("NIFTY26OCTFUT", 1))
        assertNull(DayScore.lean("RELIANCE", 1))
    }

    @Test fun usualHold() {
        assertEquals(600L to 6, DayScore.usual(earlier, today))
        // Today's trades and a bot's never count; too few is null.
        assertNull(DayScore.usual(earlier.take(4) + trip("NIFTY26O1325000CE", 1, 1.0, 2.0, at(9, 30), at(9, 40)) +
            trip("NIFTY26O1325000CE", 1, 1.0, 2.0, at(9, 30, today.minusDays(9)), at(9, 40, today.minusDays(9)), "ORB"), today))
    }

    @Test fun boughtCallAgainstAFallingIndexExitBeforeTheBest() {
        // Nifty falls 2 points a minute from 9:15: at a 10:00 entry it had fallen 30 in the 15 minutes before
        // (the 9:44 candle closes at 9:45, the 9:59 candle at 10:00 - review, 6 Oct: the full 15 minutes, never 14).
        val nifty = bars(at(9, 15), 120, 25000.0, -2.0)
        val t = trip("NIFTY26O1325000CE", 1, 100.0, 110.0, at(10, 0), at(10, 3))
        // After the 10:03 exit the call rises 1 a minute: within 15 minutes the best high is 110 + 15 = 125 at 10:18.
        val opt = bars(at(10, 0), 30, 107.0, 1.0)
        val c = DayScore.card(t, opt, nifty, 600, at(12, 0))
        assertEquals(DayScore.Side.AGAINST, c.side)
        assertEquals(-30.0, c.move!!, 1e-9)
        assertEquals(true, c.beforeBest)
        assertEquals(15L, c.window)
        val line = DayScore.line(c)
        assertTrue(line.contains("Nifty had fallen 30 points in the 15 minutes before (against the trade)"), line)
        assertTrue(line.contains("held 3 min (your usual 10 min)"), line)
        assertTrue(line.contains("within 15 min after the exit it traded"), line)
    }

    @Test fun boughtPutWithAFallingIndexAndAnExitAtTheBest() {
        val nifty = bars(at(9, 15), 120, 25000.0, -2.0)
        val t = trip("NIFTY26O1325000PE", 1, 100.0, 120.0, at(10, 0), at(10, 30))
        // After the exit the put only falls: nothing beat it.
        val opt = bars(at(10, 31), 20, 119.0, -1.0)
        val c = DayScore.card(t, opt, nifty, 600, at(12, 0))
        assertEquals(DayScore.Side.WITH, c.side)
        assertEquals(false, c.beforeBest)
        assertTrue(DayScore.line(c).contains("nothing in the 15 min after the exit beat it"), DayScore.line(c))
    }

    @Test fun flatEarlyEntryAndShortWindow() {
        // An entry at 9:20: the move is read from the open (5 minutes), and a 1-point drift on 25,000 is flat.
        val nifty = bars(at(9, 15), 10, 25000.0, 0.2)
        val t = trip("NIFTY26O1325000CE", 1, 100.0, 101.0, at(9, 20), at(9, 22))
        // Only 4 minutes of candles after the exit by "now".
        val opt = bars(at(9, 23), 10, 101.0, -0.5)
        val c = DayScore.card(t, opt, nifty, null, at(9, 26))
        assertEquals(DayScore.Side.FLAT, c.side)
        assertEquals(5L, c.moveMin)
        assertEquals(4L, c.window)
        val line = DayScore.line(c)
        assertTrue(line.contains("from the open to the entry (5 min)") && line.contains("(flat)"), line)
        assertTrue(line.contains("held 2 min;"), line)
    }

    @Test fun shortCallLeansDown() {
        val nifty = bars(at(9, 15), 120, 25000.0, -2.0)
        val t = trip("NIFTY26O1325000CE", -1, 100.0, 90.0, at(10, 0), at(10, 5))
        // After the exit the call falls further: a better price for the short.
        val opt = bars(at(10, 6), 20, 90.0, -0.5)
        val c = DayScore.card(t, opt, nifty, null, at(12, 0))
        assertEquals(DayScore.Side.WITH, c.side)
        assertEquals(true, c.beforeBest)
        assertEquals(82.5, c.best!!, 1e-9)   // the 10:20 low: 90 - 0.5 * 15
    }

    /** Review, 6 Oct: index candles that stop short of the entry, or of the minute 15 before it, are no direction. */
    @Test fun staleIndexCandlesAreUnreadable() {
        val t = trip("NIFTY26O1325000CE", 1, 100.0, 110.0, at(10, 0), at(10, 3))
        val opt = bars(at(10, 0), 30, 107.0, 1.0)
        // The index's candles stop at 9:50: ten minutes short of the entry.
        val early = DayScore.card(t, opt, bars(at(9, 15), 36, 25000.0, -2.0), 600, at(12, 0))
        assertEquals(null, early.side); assertEquals(null, early.move)
        assertTrue(DayScore.line(early).contains("Nifty's candles before the entry could not be read"), DayScore.line(early))
        // Fresh at the entry, but a gap from 9:30 to 9:57: the price 15 minutes before is stale.
        val gap = bars(at(9, 15), 16, 25000.0, -2.0) + bars(at(9, 57), 3, 24900.0, -2.0)
        val g = DayScore.card(t, opt, gap, 600, at(12, 0))
        assertEquals(null, g.side); assertEquals(null, g.move)
        // A minute's gap at either end is still read (within 2 minutes).
        val near = bars(at(9, 15), 29, 25000.0, -2.0) + bars(at(9, 45), 14, 24940.0, -2.0)
        assertEquals(DayScore.Side.AGAINST, DayScore.card(t, opt, near, 600, at(12, 0)).side)
    }

    /** Review, 6 Oct: the summary never says "within 15 minutes" of a window the clock or the close cut short. */
    @Test fun aShortWindowIsSaidInTheSummary() {
        val nifty = bars(at(9, 15), 200, 25000.0, -2.0)
        val a = trip("NIFTY26O1325000CE", 1, 100.0, 110.0, at(10, 0), at(10, 3))
        // Only 4 minutes of candles after the exit by "now".
        val opt = mapOf("NIFTY26O1325000CE" to bars(at(10, 0), 30, 107.0, 1.0))
        val all = DayScore.lines("Paper", earlier + a, today, opt, mapOf(Market.NIFTY to nifty), at(10, 7)).joinToString("\n")
        assertTrue(all.contains("Exits: 1 of 1 came before a better price within up to 15 minutes, 0 at or past anything in the up to 15 minutes after " +
            "(each on a shorter window, cut short by the clock or the close)."), all)
        assertTrue(all.contains("within 4 min after the exit"), all)
    }

    @Test fun theDaysLines() {
        val nifty = bars(at(9, 15), 200, 25000.0, -2.0)
        val a = trip("NIFTY26O1325000CE", 1, 100.0, 110.0, at(10, 0), at(10, 3))          // against, 3 min, before a better price
        val b = trip("NIFTY26O1325000PE", 1, 100.0, 120.0, at(10, 30), at(11, 0))         // with, 30 min, at the best
        val bot = trip("NIFTY26O1325000PE", 1, 100.0, 90.0, at(11, 0), at(11, 5), "ORB")
        val opt = mapOf("NIFTY26O1325000CE" to bars(at(10, 0), 30, 107.0, 1.0), "NIFTY26O1325000PE" to bars(at(11, 1), 20, 119.0, -1.0))
        val out = DayScore.lines("Paper", earlier + a + b + bot, today, opt, mapOf(Market.NIFTY to nifty), at(12, 0))
        val all = out.joinToString("\n")
        assertTrue(out[0].startsWith("Paper, your own trades today so far: 2 closed trades, net +Rs 2,250.00 (1 trade by the bots today, not scored here)"), out[0])
        assertTrue(all.contains("Index direction: 1 of 2 taken against the index's move just before the entry, 1 with it."), all)
        assertTrue(all.contains("Hold: your usual is 10 min (the median of 6 of your trades before today); today 1 held under half of it, 1 over twice it, 0 near it."), all)
        assertTrue(all.contains("Exits: 1 of 2 came before a better price within 15 minutes, 1 at or past anything in the 15 minutes after."), all)
        assertEquals(6, out.size)
        // Never advice.
        listOf("should", "better to", "avoid", "next time", "recommend").forEach { assertFalse(all.contains(it), it) }
    }

    @Test fun noneOfHisOwnAndTooFewForUsual() {
        val bot = trip("NIFTY26O1325000PE", 1, 100.0, 90.0, at(11, 0), at(11, 5), "ORB")
        assertEquals(listOf("Zerodha: no closed trades of your own today yet (1 trade by the bots today, not scored here)."),
            DayScore.lines("Zerodha", listOf(bot), today, emptyMap(), emptyMap(), at(12, 0)))
        val one = trip("NIFTY26O1325000CE", 1, 100.0, 110.0, at(10, 0), at(10, 3))
        val out = DayScore.lines("Paper", listOf(one), today, emptyMap(), emptyMap(), at(12, 0))
        assertTrue(out.contains("The index before each entry could not be read."), out.toString())
        assertTrue(out.any { it.startsWith("Hold: too few") }, out.toString())
        assertTrue(out.contains("Exits: no candles after the exits to read."), out.toString())
        assertTrue(out.last().contains("Nifty's candles before the entry could not be read") && out.last().contains("no candles of"), out.last())
    }
}
