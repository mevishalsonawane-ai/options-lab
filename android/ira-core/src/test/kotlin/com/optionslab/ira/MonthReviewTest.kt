package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonthReviewTest {
    private val today = LocalDate.of(2026, 10, 30)          // Friday, October's last trading day
    private val october = YearMonth.of(2026, 10)

    /** A trade opened on [date] at [h]:[m], held [held] minutes. */
    private fun t(date: LocalDate, h: Int, m: Int, held: Long, net: Double, sym: String = "NIFTY25O1425000CE") =
        LocalDateTime.of(date, LocalTime.of(h, m)).let { Insights.Trip(sym, it, it.plusMinutes(held), net, "Manual") }

    private fun oct(d: Int) = LocalDate.of(2026, 10, d)
    private fun sep(d: Int) = LocalDate.of(2026, 9, d)

    @Test fun asked() {
        for (q in listOf("how was my month", "Jarvis, how was my month?", "review my month", "monthly review", "how did I do this month",
                "review last month", "how did my trading go last month", "month end review", "mera mahina kaisa raha"))
            assertTrue(MonthReview.asked(q), q)
        for (q in listOf("how did my Thursday trades do this month", "how did Nifty do this month", "what is my p&l this month",
                "how was my week", "how was my last trade", "how much did I make this month"))
            assertFalse(MonthReview.asked(q), q)
        assertEquals(YearMonth.of(2026, 9), MonthReview.month("review last month", today))
        assertEquals(YearMonth.of(2026, 9), MonthReview.month("pichla mahina kaisa raha", today))
        assertEquals(october, MonthReview.month("how was my month", today))
    }

    @Test fun routedAsAnAccountQuestion() {
        assertEquals(setOf(Topic.ACCOUNT), Ask.parse("Jarvis, how was my month?").topics)
        assertNull(Ask.parse("review my month").order)
        assertEquals(setOf(Section.MONTH), AppAnswers.sections("how was my month"))
        assertEquals(setOf(Section.MONTH), AppAnswers.sections("monthly review"))
        // A filtered search keeps its own way.
        assertEquals(setOf(Section.SEARCH), AppAnswers.sections("how did my Thursday trades do this month"))
    }

    @Test fun lastTradingDayOfTheMonth() {
        val trading = { d: LocalDate -> d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY }
        assertTrue(MonthReview.lastTradingDay(oct(30), trading))       // Friday; Monday is November
        assertFalse(MonthReview.lastTradingDay(oct(29), trading))
        assertFalse(MonthReview.lastTradingDay(oct(31), trading))      // a Saturday is no trading day
        // A holiday on the last weekday moves it a day earlier.
        assertTrue(MonthReview.lastTradingDay(oct(29)) { trading(it) && it != oct(30) })
    }

    @Test fun theMonthAgainstTheMonthBefore() {
        val trips = listOf(
            t(oct(5), 10, 30, 20, 1_000.0, "BANKNIFTY25O1455000PE"), t(oct(5), 13, 0, 20, 400.0),
            t(oct(6), 10, 30, 20, -300.0), t(oct(7), 13, 0, 20, 500.0),
            t(sep(10), 11, 0, 20, -200.0), t(sep(11), 11, 0, 20, 100.0),
            t(LocalDate.of(2026, 8, 20), 11, 0, 20, 9_999.0),     // two months ago: left out
        )
        val l = MonthReview.lines("Paper", trips, october, today)
        assertEquals("Paper, your own trades in October: 4 trades, 3 won (75%), net +Rs 1,600.", l[0])
        assertEquals("September: 2 trades, 50% won, net -Rs 100; your win rate is up 25 points.", l[1])
        assertTrue(l.contains("By index: BANKNIFTY 1 trade, 1 won, +Rs 1,000; NIFTY 3 trades, 2 won, +Rs 600. (Most made: NIFTY.)"), l.toString())
        assertTrue(l.any { it.startsWith("By time of day (opened): 10:00-12:00 2 trades, 1 won, +Rs 700; 12:00-13:30 2 trades, 2 won, +Rs 900.") }, l.toString())
        assertTrue(l.any { it.startsWith("By weekday: Monday 2 trades, 2 won, +Rs 1,400; Tuesday 1 trade, 0 won, -Rs 300; Wednesday") }, l.toString())
        assertTrue(l.contains("Green days: 2 of 3 (66%). The month before: 1 of 2 (50%)."), l.toString())
        assertTrue(l.contains("Best day: Monday 5 October, +Rs 1,400, 87% of the month's net; the other 2 days together +Rs 200."), l.toString())
        assertTrue(l.contains("Worst day: Tuesday 6 October, -Rs 300."), l.toString())
        assertTrue(l.any { it.startsWith("Not enough noted reasons this month") }, l.toString())
        assertEquals("Too few trades to see a habit.", l.last())
        assertEquals("Boss, your month's review is in the chat. You won more often than last month. 2 days of 3 were green. No costly habit showed up.",
            MonthReview.spoken(trips, october, today))
    }

    @Test fun reviewingLastMonth() {
        val trips = listOf(t(sep(10), 11, 0, 20, -200.0), t(sep(11), 11, 0, 20, 100.0), t(oct(5), 10, 30, 20, 1_000.0))
        val l = MonthReview.lines("Paper", trips, YearMonth.of(2026, 9), today)
        assertEquals("Paper, your own trades in September: 2 trades, 1 won (50%), net -Rs 100.", l[0])
        assertEquals("August: no trades of yours to compare with.", l[1])
        assertTrue(l.contains("Best day: Friday 11 September, +Rs 100; without it the month would be -Rs 200."), l.toString())
    }

    @Test fun theHabitThatCostMost() {
        val trips = listOf(
            t(oct(5), 9, 15, 10, -800.0), t(oct(6), 9, 17, 10, -700.0), t(oct(7), 9, 16, 10, 200.0),     // first minutes: -1,300
            t(oct(8), 15, 5, 10, -100.0), t(oct(9), 15, 10, 10, -50.0),                                   // late: -150
            t(oct(12), 11, 0, 20, 300.0), t(oct(13), 11, 0, 20, 400.0),
            t(sep(14), 9, 15, 10, -200.0),
        )
        val c = MonthReview.costliest(MonthReview.split(trips, october, today).first)!!
        assertEquals(MonthReview.Habit.FIRST_MINUTES, c.habit)
        val l = MonthReview.lines("Zerodha", trips, october, today)
        assertEquals("The habit that cost most: 3 trades opened in the first five minutes of the session: 1 won, net -Rs 1,300. The month before: 1 such trade, net -Rs 200.", l.last())
        val s = MonthReview.spoken(trips, october, today)!!
        assertTrue(s.endsWith("The habit that cost most: trades in the first five minutes of the session."), s)
        // Spoken unasked, it holds nothing of Boss's account.
        assertFalse(Overheard.holdsAccount(s), s)
        assertTrue(l.any { Overheard.holdsAccount(it) })
    }

    @Test fun heldLosersAndBusyDays() {
        val winners = listOf(t(oct(5), 11, 0, 10, 300.0), t(oct(6), 11, 0, 10, 200.0), t(oct(7), 11, 0, 12, 100.0))
        val held = listOf(t(oct(8), 11, 0, 60, -400.0), t(oct(9), 11, 0, 90, -500.0), t(oct(12), 11, 0, 12, -50.0))
        assertEquals(MonthReview.Habit.HELD_LOSERS, MonthReview.costliest(winners + held)!!.habit)
        assertEquals(2, MonthReview.costs(winners + held).first { it.habit == MonthReview.Habit.HELD_LOSERS }.count)
        val busy = (0 until 8).map { t(oct(14), 10, it * 5, 3, if (it % 2 == 0) -400.0 else 100.0) }
        val c = MonthReview.costs(winners + busy).first { it.habit == MonthReview.Habit.BUSY_DAYS }
        assertEquals(8, c.count); assertEquals(-1_200.0, c.net)
    }

    @Test fun setupsFromNotedReasons() {
        val a = t(oct(5), 10, 0, 20, 500.0); val b = t(oct(6), 10, 0, 20, 300.0); val g1 = t(oct(7), 10, 0, 20, -400.0); val g2 = t(oct(8), 10, 0, 20, -200.0)
        val notes = listOf(oct(5).atTime(10, 1) to "breakout above the high", oct(6).atTime(9, 58) to "range break",
            oct(7).atTime(10, 0) to "just a feeling", oct(8).atTime(10, 5) to "gut", oct(9).atTime(10, 0) to "no trade near this one")
        val s = MonthReview.setups(listOf(a, b, g1, g2), notes)
        assertEquals(listOf("breakout", "gut feel"), s.map { it.name })
        val l = MonthReview.lines("Paper", listOf(a, b, g1, g2), october, today, notes)
        assertTrue(l.contains("By the reasons you noted: \"breakout\" 2 trades, 2 won, +Rs 800; \"gut feel\" 2 trades, 0 won, -Rs 600."), l.toString())
    }

    @Test fun nothingThisMonth() {
        assertEquals(listOf("Paper: none of your own trades closed in October.", "September: no trades of yours to compare with."),
            MonthReview.lines("Paper", emptyList(), october, today))
        assertNull(MonthReview.spoken(emptyList(), october, today))
    }
}
