package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MorningAsksTest {
    /** A Monday. */
    private val today = LocalDate.of(2026, 10, 5)
    private val levels = Routine.question("NIFTY|levels")!!
    private val trend = Routine.question("BANKNIFTY|trend")!!

    /** The [n] weekdays before today, oldest first. */
    private fun weekdays(n: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = today.minusDays(1)
        while (out.size < n) { if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) out += d; d = d.minusDays(1) }
        return out.reversed()
    }

    /** The last five trading mornings checked, [said] asked at 09:20 on the ones [on] picks (by index, oldest first). */
    private fun mornings(said: String, vararg on: Int, log0: MorningAsks.Log = MorningAsks.Log()): MorningAsks.Log {
        var log = log0
        weekdays(MorningAsks.MORNINGS).forEachIndexed { i, d ->
            if (i in on) log = MorningAsks.heard(log, said, d.atTime(9, 20))
            log = MorningAsks.checked(log, d)
        }
        return log
    }

    @Test fun aQuestionOnThreeOfTheLastFiveMorningsIsOffered() {
        val log = mornings(levels, 0, 2, 4)
        val u = MorningAsks.offer(log, today)
        assertNotNull(u)
        assertEquals("NIFTY|levels", u.key)
        assertEquals(3, u.mornings)
        assertEquals("You usually ask about Nifty's levels in the morning, Boss - say \"yes\" for it.", MorningAsks.line(u))
        // The yes asks that very question: a market question, never an order or a command.
        val q = MorningAsks.question(u.key)
        assertEquals(levels, q)
        assertTrue(Ask.parse(q!!).order == null && Ask.parse(q).command == null)
    }

    @Test fun twoMorningsAreNotAHabit() {
        assertNull(MorningAsks.offer(mornings(levels, 1, 3), today))
        assertTrue(MorningAsks.say(mornings(levels, 1, 3), today).startsWith("I don't offer you a morning question yet, Boss"))
    }

    @Test fun onlyTheLastFiveTradingMorningsCount() {
        // Asked on three mornings long ago, then five checked mornings without it.
        var log = MorningAsks.Log()
        for (d in 20L..22L) { val day = today.minusDays(d); if (day.dayOfWeek.value <= 5) log = MorningAsks.checked(MorningAsks.heard(log, levels, day.atTime(9, 10)), day) }
        log = mornings("what is the time", log0 = log)
        assertNull(MorningAsks.offer(log, today))
    }

    @Test fun notInTheAfternoonNotAtTheWeekendNotTheAccount() {
        var log = MorningAsks.Log()
        val d = weekdays(1).single()
        assertEquals(log, MorningAsks.heard(log, levels, d.atTime(14, 0)))
        assertEquals(log, MorningAsks.heard(log, levels, LocalDate.of(2026, 10, 4).atTime(9, 0)))
        assertEquals(log, MorningAsks.heard(log, "what is my P&L today", d.atTime(9, 30)))
        assertEquals(log, MorningAsks.heard(log, "buy nifty 24500 ce", d.atTime(9, 30)))
        assertEquals(log, MorningAsks.heard(log, "arm the bot", d.atTime(9, 30)))
        log = MorningAsks.heard(log, levels, d.atTime(9, 30))
        assertEquals(setOf("NIFTY|levels"), log.mornings.single().keys)
    }

    @Test fun notOfferedWhenAlreadyAskedTodayAndTheStrongestFirst() {
        var log = mornings(levels, 0, 1, 2)
        log = mornings(trend, 0, 1, 2, 3, log0 = log)
        assertEquals(listOf("BANKNIFTY|trend", "NIFTY|levels"), MorningAsks.usual(log, today).map { it.key })
        assertEquals("BANKNIFTY|trend", MorningAsks.offer(log, today)?.key)
        val asked = MorningAsks.heard(log, trend, today.atTime(8, 50))
        assertEquals("NIFTY|levels", MorningAsks.offer(asked, today)?.key)
        assertNull(MorningAsks.offer(MorningAsks.heard(asked, levels, today.atTime(8, 55)), today))
    }

    @Test fun onlyABareYesAndOnlySoon() {
        for (s in listOf("yes", "Yes please", "yeah", "haan", "haan batao", "Jarvis, yes", "yes, go on")) assertTrue(MorningAsks.yes(s), s)
        for (s in listOf("no", "yes no", "yes buy it", "yes arm it", "yes and nifty levels", "confirm", "ok", "go ahead", "do it", "nahi"))
            assertFalse(MorningAsks.yes(s), s)
        val at = today.atTime(9, 0)
        assertTrue(MorningAsks.fresh(at, at.plusMinutes(MorningAsks.YES_MINUTES)))
        assertFalse(MorningAsks.fresh(at, at.plusMinutes(MorningAsks.YES_MINUTES + 1)))
        assertFalse(MorningAsks.fresh(at, at.minusMinutes(1)))
    }

    @Test fun resetStartsAfresh() {
        val log = mornings(levels, 0, 2, 4)
        assertTrue(MorningAsks.sayReset(log, today).startsWith("Done, Boss"))
        val r = MorningAsks.reset(log, today.minusDays(1).atTime(20, 0))
        assertNull(MorningAsks.offer(r, today))
        assertTrue(MorningAsks.sayReset(r, today).startsWith("I don't offer you a morning question, Boss"))
        assertTrue(MorningAsks.say(log, today).contains(MorningAsks.UNDO))
    }

    @Test fun theLedgerShowsItWithItsUndoAndTheWeekUndoResetsIt() {
        val log = mornings(levels, 0, 2, 4)
        val now: LocalDateTime = today.atTime(10, 0)
        val item = Learnings.items(Learnings.Inputs(asks = log), now).single { it.area == Learnings.Area.MORNING_ASKS }
        assertEquals(MorningAsks.UNDO, item.undo)
        assertTrue(item.text().contains("Nifty's levels"))
        val u = Learnings.undo(Learnings.Inputs(asks = log), now)
        assertEquals(listOf("NIFTY|levels"), u.asks.map { it.key })
        assertFalse(u.empty)
        assertTrue(Learnings.done(u).contains("no longer offered"))
        assertTrue(Learnings.undo(Learnings.Inputs(asks = MorningAsks.reset(log, now)), now).empty)
    }

    @Test fun askedWordings() {
        for (s in listOf("what do I usually ask in the morning", "what question do I ask every morning", "which question do you offer me in the morning",
            "why did you offer that question in the morning check", "what do you offer me at the end of the morning check", "main subah kya poochta hoon"))
            assertEquals(MorningAsks.Request.WHICH, MorningAsks.asked(s), s)
        for (s in listOf("don't offer my usual morning question", "quit offering my morning question", "no need to offer my morning question", "don't offer questions in the morning check", "no more morning offers",
            "subah ka sawal mat poocho"))
            assertEquals(MorningAsks.Request.RESET, MorningAsks.asked(s), s)
        for (s in listOf("what do i usually ask", "say the whole morning check again", "which morning items do you skip", "good morning",
            "what are the levels this morning", "stop the bot", "yes"))
            assertNull(MorningAsks.asked(s), s)
        for (s in listOf(MorningAsks.UNDO, "no more morning offers", "what do i usually ask in the morning"))
            assertTrue(Ask.parse(s).order == null && Ask.parse(s).command == null, s)
    }
}
