package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeekReviewTest {
    private val friday = LocalDate.of(2026, 10, 9)          // this week: Mon 5 to Fri 9; last week: 28 Sep to 4 Oct

    /** A trade opened on [date] at [h]:[m], held [held] minutes. */
    private fun t(date: LocalDate, h: Int, m: Int, held: Long, net: Double, sym: String = "NIFTY25O1425000CE") =
        LocalDateTime.of(date, java.time.LocalTime.of(h, m)).let { Insights.Trip(sym, it, it.plusMinutes(held), net, "Manual") }

    private fun day(d: Int) = LocalDate.of(2026, 10, d)

    @Test fun theWeekAgainstLastWeekWithBestAndWorst() {
        val trips = listOf(
            t(day(5), 10, 0, 20, 800.0, "BANKNIFTY25O1455000PE"), t(day(6), 11, 0, 20, -300.0), t(day(7), 12, 0, 20, 200.0),
            t(day(1), 10, 0, 20, -100.0), t(day(2), 10, 0, 20, -100.0), t(day(1), 13, 0, 20, 50.0), t(day(2), 13, 0, 20, -50.0),
            t(LocalDate.of(2026, 9, 25), 10, 0, 20, 9_999.0),   // two weeks ago: left out
        )
        val l = WeekReview.lines("Paper", trips, friday)
        assertEquals("Paper, your own trades this week: 3 trades, 2 won (66%), net +Rs 700.", l[0])
        assertEquals("Last week: 4 trades, 25% won, net -Rs 200; your win rate is up 41 points.", l[1])
        assertTrue(l.contains("Best: BANKNIFTY25O1455000PE +Rs 800 on Monday."), l.toString())
        assertTrue(l.contains("Worst: NIFTY25O1425000CE -Rs 300 on Tuesday."), l.toString())
        assertTrue(l.last().startsWith("No costly habit showed up this week"), l.toString())
        assertEquals("Boss, your week's review is in the chat. You won more often than last week. No costly habit showed up.", WeekReview.spoken(trips, friday))
    }

    @Test fun heldLosersLongerThanWinners() {
        val trips = listOf(t(day(5), 10, 0, 10, 300.0), t(day(6), 10, 0, 14, 200.0), t(day(7), 10, 0, 50, -400.0), t(day(8), 10, 0, 70, -500.0))
        val f = WeekReview.findings(WeekReview.split(trips, friday).first, emptyList())
        assertEquals(listOf(WeekReview.Kind.HELD_LOSERS), f.map { it.kind })
        assertEquals("You held losing trades longer than winning ones: losers about 60 minutes on average, winners about 12.", f[0].text)
        // Losers cut as fast as winners: nothing to say.
        val quick = listOf(t(day(5), 10, 0, 10, 300.0), t(day(6), 10, 0, 14, 200.0), t(day(7), 10, 0, 12, -400.0), t(day(8), 10, 0, 15, -500.0))
        assertTrue(WeekReview.findings(quick, emptyList()).isEmpty())
    }

    @Test fun firstFiveMinutesComparedWithLastWeek() {
        val trips = listOf(t(day(5), 9, 15, 20, -400.0), t(day(6), 9, 17, 20, 100.0), t(day(7), 9, 19, 20, -300.0), t(day(8), 9, 20, 20, -900.0),
            t(day(1), 9, 16, 20, -50.0))
        val (w, prev) = WeekReview.split(trips, friday)
        val f = WeekReview.findings(w, prev).single { it.kind == WeekReview.Kind.FIRST_MINUTES }
        assertEquals(3, f.count); assertEquals(1, f.before)
        assertEquals("3 trades opened in the first five minutes of the session: 1 won, net -Rs 600. Last week: 1 such trade.", f.text)
        // Two of them, but they made money: not a habit worth naming.
        assertTrue(WeekReview.findings(listOf(t(day(5), 9, 15, 20, 400.0), t(day(6), 9, 16, 20, -100.0)), emptyList()).none { it.kind == WeekReview.Kind.FIRST_MINUTES })
    }

    @Test fun tradesRightAfterALoss() {
        val loss = t(day(6), 10, 0, 30, -500.0)                                // closed 10:30
        val trips = listOf(loss, t(day(6), 10, 35, 10, -200.0), t(day(6), 10, 50, 10, -100.0),   // 10:35 after a loss; 10:50 after the 10:45 loss
            t(day(6), 13, 0, 10, 300.0),                                       // long after: not counted
            t(day(7), 9, 50, 10, -50.0))                                       // the next day: not counted
        val f = WeekReview.findings(trips, emptyList()).single { it.kind == WeekReview.Kind.AFTER_LOSS }
        assertEquals("2 trades opened within 15 minutes of a losing trade closing: 0 won, net -Rs 300. Last week: none.", f.text)
    }

    @Test fun aBusyLosingDay() {
        val busy = (0 until 8).map { t(day(7), 10, it * 5, 3, if (it % 2 == 0) -200.0 else 50.0) }
        val calm = listOf(t(day(5), 11, 0, 20, 100.0), t(day(6), 11, 0, 20, 100.0), t(day(8), 11, 0, 20, 100.0))
        val f = WeekReview.findings(busy + calm, emptyList()).single { it.kind == WeekReview.Kind.BUSY_DAY }
        assertEquals("Wednesday had 8 trades, far more than your other days this week, and lost Rs 600.", f.text)
        // The same day winning: not said.
        assertTrue(WeekReview.findings(busy.map { it.copy(net = -it.net + 100) } + calm, emptyList()).none { it.kind == WeekReview.Kind.BUSY_DAY })
    }

    @Test fun costliestFirstAndBetterThanLastWeek() {
        val trips = listOf(t(day(5), 9, 15, 20, -900.0), t(day(6), 9, 16, 20, -100.0),          // first minutes: -1,000
            t(day(7), 10, 0, 30, -100.0), t(day(7), 10, 40, 10, -200.0), t(day(7), 10, 55, 10, -50.0),   // after a loss: -250
            t(day(1), 13, 0, 10, 300.0), t(day(1), 14, 0, 12, 200.0), t(day(2), 13, 0, 60, -400.0), t(day(2), 14, 0, 80, -500.0))  // last week held losers
        val l = WeekReview.lines("Zerodha", trips, friday)
        val i = l.indexOf("Worth a look:")
        assertTrue(i > 0, l.toString())
        assertTrue(l[i + 1].startsWith("2 trades opened in the first five minutes"), l.toString())
        assertTrue(l[i + 2].startsWith("2 trades opened within 15 minutes"), l.toString())
        assertTrue(l.last().startsWith("Better than last week: losers no longer held longer than winners"), l.toString())
    }

    @Test fun spokenNeverSaysAmountsOrSymbols() {
        val trips = listOf(t(day(5), 9, 15, 20, -900.0, "BANKNIFTY25O1455000PE"), t(day(6), 9, 16, 20, -100.0), t(day(1), 10, 0, 20, 500.0))
        val s = WeekReview.spoken(trips, friday)!!
        assertEquals("Boss, your week's review is in the chat. You won less often than last week. One thing worth a look: trades in the first five minutes cost you.", s)
        assertFalse(Regex("Rs|NIFTY|\\d").containsMatchIn(s))
        assertNull(WeekReview.spoken(listOf(t(day(1), 10, 0, 20, 500.0)), friday))
    }

    @Test fun noTradesThisWeek() {
        assertEquals(listOf("Paper: none of your own trades closed this week.", "Last week: no trades to compare with."), WeekReview.lines("Paper", emptyList(), friday))
    }
}
