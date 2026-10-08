package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LessonsTest {
    private val mon = LocalDateTime.of(2026, 6, 1, 10, 0)    // a Monday

    private fun t(owner: String, day: Long, h: Int, net: Double, mins: Long = 20) =
        mon.plusDays(day).withHour(h).let { Insights.Trip("X", it, it.plusMinutes(mins), net, owner) }

    @Test fun anArmThatTurnedIsSeen() {
        val trips = (0 until 20).map { t("ORB 5", it * 7L, 10 + it % 5, 500.0) } + (20 until 30).map { t("ORB 5", it * 7L, 10 + it % 5, -200.0) }
        val l = Lessons.of(trips)
        assertTrue(l.first().turned, l.toString())
        assertEquals("ORB 5 has turned: its last 10 trades lost -Rs 2,000.00, after making +Rs 10,000.00 before.", l.first().text)
    }

    @Test fun losingHoursDaysAndHoldingAreSeenAtThirtyTrades() {
        val trips = (0 until 30).map { t("Pine A", it * 7L, 9, -100.0, mins = 60) } +            // Mondays at 9, losers held long
            (0 until 30).map { t("Pine A", it * 7L + 1, 13, 150.0, mins = 10) }                   // Tuesdays at 13, winners quick
        val texts = Lessons.of(trips, max = 10).map { it.text }
        assertTrue(texts.any { it.startsWith("Pine A loses when entered between 09:00 and 10:00: 30 trades") }, texts.toString())
        assertTrue(texts.any { it.startsWith("Pine A loses on Mondays") }, texts.toString())
        assertTrue(texts.any { it.startsWith("Pine A holds losers much longer than winners: 60 minutes against 10") }, texts.toString())
    }

    /** 08 Oct (research X1): under 30 trades in the group nothing is said - 6 trades made a lesson the 5-year record reversed. */
    @Test fun underThirtyInTheGroupNothingIsSaid() {
        assertEquals(30, Lessons.MIN_SLICE); assertEquals(30, Lessons.MIN_OWNER)
        // 29 losing 09:00 entries among 60 trades: the hour is not judged.
        val trips = (0 until 29).map { t("Liquidity 15+5", it * 7L, 9, -100.0) } + (0 until 31).map { t("Liquidity 15+5", it * 7L + 1, 13, 150.0) }
        val texts = Lessons.of(trips, max = 10).map { it.text }
        assertTrue(texts.none { it.contains("loses when entered between 09:00") }, texts.toString())
        assertTrue(texts.none { it.contains("loses on Mondays") }, texts.toString())
    }

    @Test fun tooFewSaysSo() {
        assertTrue(Lessons.of(List(29) { t("A", it.toLong(), 10, -1.0) }).isEmpty())
        assertTrue(Lessons.say(emptyList(), 9).startsWith("Nothing stands out yet"))
        assertTrue(Lessons.say(emptyList(), 9).contains("about 30 trades"))
        assertTrue(Lessons.asked("Jarvis, what have you learned?")); assertTrue(Lessons.asked("what do my trades teach"))
    }
}
