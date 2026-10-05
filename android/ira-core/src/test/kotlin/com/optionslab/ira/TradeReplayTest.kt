package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TradeReplayTest {
    private val day = LocalDate.of(2026, 10, 5)
    private fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)
    private val sym = "NIFTY25O0725000CE"

    /** Minute candles from 10:00: each (high, low); open and close in the middle. */
    private fun bars(from: LocalDateTime, vararg hl: Pair<Double, Double>) =
        hl.mapIndexed { i, (h, l) -> Candle(from.plusMinutes(i.toLong()), (h + l) / 2, h, l, (h + l) / 2) }

    // Bought at 120 at 10:01, sold at 135 at 10:05. While held: low 105 (10:02), high 142 (10:04). After: up to 160, down to 110.
    private val option = bars(at(10, 0), 121.0 to 118.0, 122.0 to 115.0, 118.0 to 105.0, 130.0 to 117.0, 142.0 to 128.0, 138.0 to 133.0,
        150.0 to 136.0, 160.0 to 140.0, 141.0 to 110.0)
    private val long = TradeReplay.Trip(sym, 1, 75, 120.0, 135.0, at(10, 1).plusSeconds(20), at(10, 5).plusSeconds(40), 1_050.0)
    private val index = listOf(Candle(day.minusDays(1).atTime(15, 29), 24_900.0, 24_900.0, 24_900.0, 24_900.0)) +
        (0..8).map { Candle(at(10, it), 24_950.0 + 10 * it, 24_955.0 + 10 * it, 24_945.0 + 10 * it, 24_955.0 + 10 * it) }

    @Test fun asksOnlyForTheReplay() {
        assertEquals(TradeReplay.Scope.LAST, TradeReplay.asked("Jarvis, how was my last trade?"))
        assertEquals(TradeReplay.Scope.LAST, TradeReplay.asked("how did my last trade go"))
        assertEquals(TradeReplay.Scope.LAST, TradeReplay.asked("replay my last trade"))
        assertEquals(TradeReplay.Scope.TODAY, TradeReplay.asked("go over my trades today"))
        assertEquals(TradeReplay.Scope.TODAY, TradeReplay.asked("How were my exits?"))
        assertEquals(TradeReplay.Scope.TODAY, TradeReplay.asked("did I exit too early"))
        // The loss reasons, an order, the weekly review and the trade search stay theirs.
        assertNull(TradeReplay.asked("why did my last trade lose"))
        assertNull(TradeReplay.asked("close my last trade"))
        assertNull(TradeReplay.asked("review my trades"))
        assertNull(TradeReplay.asked("go over my trades this week"))
        assertNull(TradeReplay.asked("what is nifty doing"))
    }

    @Test fun routedAsAnAccountQuestionNeverAPracticeOrOrder() {
        val q = Ask.parse("Jarvis, how was my last trade?")
        assertEquals(setOf(Topic.ACCOUNT), q.topics)
        assertNull(q.order)
        assertEquals(setOf(Section.REPLAY), AppAnswers.sections("how was my last trade"))
        assertEquals(setOf(Section.REPLAY), AppAnswers.sections("replay my trades today"))
        assertTrue(Commands.parse("replay my last trade")?.kind != Command.Kind.PRACTICE)
        // A past day's practice is still that.
        assertEquals(Command.Kind.PRACTICE, Commands.parse("practice on last Thursday")?.kind)
    }

    @Test fun aLongTradeAgainstItsCandles() {
        val r = TradeReplay.of(long, option, index, Market.NIFTY, at(10, 8))
        assertEquals(142.0, r.best); assertEquals(at(10, 4), r.bestAt)
        assertEquals(105.0, r.worst); assertEquals(at(10, 2), r.worstAt)
        assertEquals(22.0, r.run!!, 1e-9); assertEquals(15.0, r.heat!!, 1e-9); assertEquals(7.0, r.leftInHold!!, 1e-9)
        assertEquals(25.0, r.leftAfter!!, 1e-9); assertEquals(25.0, r.againstAfter!!, 1e-9)
        assertEquals(at(10, 9), r.afterUntil)
        assertEquals(24_960.0, r.indexFrom); assertEquals(25_005.0, r.indexTo)
        val l = TradeReplay.lines("Paper", r)
        assertTrue(l[0].startsWith("Paper trade 10:01 to 10:05: bought 75 $sym at 120.00, sold at 135.00 (+15.00 points in 4 min), net +Rs 1,050.00."), l[0])
        assertTrue(l[1].contains("at best 142.00 at 10:04 (22.00 points your way)") && l[1].contains("at worst 105.00 at 10:02 (15.00 points against you, 13% of the entry price)"), l[1])
        assertTrue(l[2].startsWith("You kept 15.00 of the 22.00 points it gave while held (68%); 7.00 more were there at its best, Rs 525.00 on 75."), l[2])
        assertTrue(l[3].startsWith("After you got out, to 10:09: it went 25.00 points further your way (to 160.00 at 10:07"), l[3])
        assertTrue(l[4].startsWith("Nifty while you held: +0.18% (24,960 to 25,005); +0.54% on the day."), l[4])
        // Facts only.
        assertTrue(l.none { Regex("(?i)\\b(should|must|next time|better to)\\b").containsMatchIn(it) })
    }

    @Test fun aShortTradeAndOneThatTurned() {
        // Sold at 120 at 10:01, bought back at 125 at 10:05: it fell to 105 first (15 your way), then rose to 142.
        val short = TradeReplay.Trip(sym, -1, 75, 120.0, 125.0, at(10, 1), at(10, 5), -400.0, "ORB")
        val r = TradeReplay.of(short, option)
        assertEquals(105.0, r.best); assertEquals(142.0, r.worst)
        assertEquals(15.0, r.run!!, 1e-9); assertEquals(22.0, r.heat!!, 1e-9); assertEquals(-5.0, r.taken, 1e-9)
        val l = TradeReplay.lines("Zerodha", r)
        assertTrue(l[0].contains("sold 75 $sym at 120.00, bought back at 125.00") && l[0].endsWith("(ORB)."), l[0])
        assertTrue(l[2].startsWith("It was 15.00 points your way at 10:02, and you closed 5.00 points against you"), l[2])
        assertTrue(TradeReplay.exits(listOf(r)).any { it.startsWith("1 of 1 went your way first and closed flat or against you.") })
    }

    @Test fun noCandlesOnlyTheFills() {
        val r = TradeReplay.of(long, emptyList())
        assertNull(r.run)
        val l = TradeReplay.lines("Paper", r)
        assertEquals(2, l.size)
        assertTrue(l[1].contains("could not be read"))
        assertTrue(TradeReplay.short(r).contains("no candles for the hold"))
    }

    @Test fun theFillsBoundTheBestAndWorst() {
        // Candles that never reach the exit price (a fill between minute prints): the best is the exit itself.
        val flat = bars(at(10, 0), 125.0 to 118.0, 126.0 to 119.0, 127.0 to 121.0, 128.0 to 122.0, 129.0 to 124.0, 130.0 to 125.0)
        val r = TradeReplay.of(long, flat)
        assertEquals(135.0, r.best); assertEquals(0.0, r.leftInHold!!, 1e-9)
        assertTrue(TradeReplay.lines("Paper", r)[2].endsWith("you got out at its best."))
    }

    @Test fun theDaysTradesALineEach() {
        val second = TradeReplay.Trip(sym, 1, 75, 140.0, 138.0, at(10, 6), at(10, 7), -200.0)
        val rs = listOf(TradeReplay.of(second, option, now = at(10, 8)), TradeReplay.of(long, option, now = at(10, 8)))
        val a = TradeReplay.answer("Paper", TradeReplay.Scope.TODAY, rs)
        assertEquals("Paper: 2 closed trades today, net +Rs 850.00.", a[0])
        assertTrue(a[1].startsWith("10:01-10:05 bought $sym 120.00 to 135.00 (+15.00): while held best +22.00, worst -15.00; 25.00 more your way after you got out."), a[1])
        assertTrue(a.any { it == "In the winning exits you kept 68% of the best move while held, on average." })
        assertTrue(a.any { it.startsWith("The deepest against you while held: 15.00 points") })
        assertEquals("From the app's own minute candles: what happened, not advice.", a.last())
        // The last trade in full.
        assertTrue(TradeReplay.answer("Paper", TradeReplay.Scope.LAST, rs)[0].startsWith("Paper trade 10:06 to 10:07"))
        assertEquals(listOf("Paper: no closed trades today to replay."), TradeReplay.answer("Paper", TradeReplay.Scope.TODAY, emptyList()))
    }
}
