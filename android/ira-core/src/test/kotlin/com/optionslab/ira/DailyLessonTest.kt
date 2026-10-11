package com.optionslab.ira

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Today's lesson" ([DailyLesson]): today's own records only, in order of preference, and no card when nothing qualifies. */
class DailyLessonTest {
    private val d0 = LocalDate.of(2026, 10, 12)
    private val sec = d0.atTime(10, 32).atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond()

    @Test fun theBiggestMoveWithWhatPrecededItComesFirst() {
        val moves = listOf(DailyLesson.Move("BANKNIFTY", sec, 52.0, 6.1, listOf("futures flow" to -40L, "VIX" to -12L)),
            DailyLesson.Move("NIFTY", sec + 600, -30.0, 5.0, emptyList()))
        val l = DailyLesson.of(d0, moves, listOf(DailyLesson.BotTrade("ORB", -900.0, 400.0, "EARLY", "STOP", true)), "habit")!!
        assertEquals(DailyLesson.Kind.MOVE, l.kind)
        assertTrue(l.text.contains("0.52% up in one minute at 10:32"), l.text)
        assertTrue(l.text.contains("the future's order flow 40 s ahead, then India VIX 12 s ahead"), l.text)
        assertEquals(l, DailyLesson.decode(DailyLesson.encode(l)))
    }

    @Test fun thenTheClearestBotTradeThenHisHabitAndElseNothing() {
        val bot = DailyLesson.of(d0, emptyList(), listOf(DailyLesson.BotTrade("Pine · EMA", 200.0, -650.0, "EARLY: flow turned", "TARGET", true),
            DailyLesson.BotTrade("Solo", 100.0, 50.0, "STOP", "STOP", true)), "habit")!!
        assertEquals(DailyLesson.Kind.BOT, bot.kind)
        assertTrue(bot.text.contains("trailed the original rules by ₹650"), bot.text)
        val loss = DailyLesson.of(d0, emptyList(), listOf(DailyLesson.BotTrade("Liquidity 15+5", -1_200.0, null, "STOP", null, false)), null)!!
        assertTrue(loss.title.contains("lost ₹1,200"), loss.title)
        val habit = DailyLesson.of(d0, emptyList(), listOf(DailyLesson.BotTrade("Solo", 100.0, 50.0, "STOP", "STOP", true)), "Today you made a trade your record says loses.")!!
        assertEquals(DailyLesson.Kind.HABIT, habit.kind)
        // A small move (under 4.7x) and a small bot trade: no card at all.
        assertNull(DailyLesson.of(d0, listOf(DailyLesson.Move("NIFTY", sec, 10.0, 2.0, emptyList())), listOf(DailyLesson.BotTrade("Solo", 50.0, 20.0, "TIME", null, true)), null))
        assertTrue(DailyLesson.say(null, closed = true).startsWith("No lesson today"))
        assertTrue(DailyLesson.say(null, closed = false).contains("after the close"))
    }
}
