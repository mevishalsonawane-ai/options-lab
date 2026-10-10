package com.optionslab.ira

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckTimesTest {
    /** A Wednesday. */
    private val today = LocalDate.of(2026, 10, 7)

    @AfterTest fun reset() { CheckTimes.forgetAsked() }

    /** The last [n] weekdays before today. */
    private fun weekdays(n: Int): List<LocalDate> =
        generateSequence(today.minusDays(1)) { it.minusDays(1) }.filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }.take(n).toList()

    private fun pnl(d: LocalDate, h: Int, m: Int, afterLoss: Boolean = false) = Routine.Seen(CheckTimes.KEY, d.atTime(h, m), afterLoss)

    /** His P&L asked around 11:00 and 14:30 on [n] weekdays (a few minutes' drift each day). */
    private fun log(n: Int): List<Routine.Seen> = weekdays(n).flatMapIndexed { i, d -> listOf(pnl(d, 11, i % 4), pnl(d, 14, 30 + i % 5)) }

    @Test fun learnedFromHisRoutineLogOnlyAfterEnoughDays() {
        val rs = CheckTimes.learned(log(6), CheckTimes.Log(), today)
        assertEquals(listOf("11:00", "14:30"), rs.map { it.clock })
        assertTrue(rs.all { it.days == 6 && it.ofDays == 6 }, rs.toString())
        assertEquals(today.minusDays(1), rs.first().newest)
        // Four days are not enough.
        assertTrue(CheckTimes.learned(log(4), CheckTimes.Log(), today).isEmpty())
        // Other questions, asks just after a loss, and asks outside market hours never count.
        val noise = weekdays(8).flatMap { d -> listOf(Routine.Seen("NIFTY|levels", d.atTime(11, 0)), pnl(d, 12, 0, afterLoss = true), pnl(d, 8, 30), pnl(d, 18, 0)) }
        assertTrue(CheckTimes.learned(noise, CheckTimes.Log(), today).isEmpty())
        // Older than the window: forgotten.
        val old = log(6).map { it.copy(at = it.at.minusDays(CheckTimes.WINDOW_DAYS + 5)) }
        assertTrue(CheckTimes.learned(old, CheckTimes.Log(), today).isEmpty())
    }

    @Test fun aScatteredHabitIsNotATime() {
        // P&L asked every day, but at a different hour each day: no half hour holds half his days.
        val scattered = weekdays(10).mapIndexed { i, d -> pnl(d, 10 + i % 5, 15 * (i % 3)) }
        assertTrue(CheckTimes.learned(scattered, CheckTimes.Log(), today).isEmpty())
        // 13:00 on 7 of 12 P&L days is learned; 11:00 on 5 of them, under half, is not.
        val few = weekdays(12).mapIndexed { i, d -> if (i < 5) pnl(d, 11, 0) else pnl(d, 13, 0) }
        assertEquals(listOf("13:00"), CheckTimes.learned(few, CheckTimes.Log(), today).map { it.clock })
    }

    @Test fun theReadAheadIsOnlyNearHisTimesInMarketHours() {
        val rs = CheckTimes.learned(log(6), CheckTimes.Log(), today)
        fun at(h: Int, m: Int) = today.atTime(h, m)
        // A minute or two before 11:00 and a few after: the quiet pace is lifted.
        assertTrue(CheckTimes.near(rs, at(10, 58)))
        assertTrue(CheckTimes.near(rs, at(11, 5)))
        assertTrue(CheckTimes.near(rs, at(14, 29)))
        assertFalse(CheckTimes.near(rs, at(10, 50)))
        assertFalse(CheckTimes.near(rs, at(12, 0)))
        assertFalse(CheckTimes.quiet(true, true, rs, at(10, 59)))
        assertTrue(CheckTimes.quiet(true, true, rs, at(12, 0)))
        // Market shut: never (battery) - neither by the flag nor by the clock (a Saturday at 11:00).
        assertTrue(CheckTimes.quiet(true, false, rs, at(10, 59)))
        assertFalse(CheckTimes.near(rs, LocalDate.of(2026, 10, 10).atTime(10, 59)))
        // Never quieter than asked: a pass with the screen on stays on every pass.
        assertFalse(CheckTimes.quiet(false, true, rs, at(12, 0)))
        assertFalse(CheckTimes.quiet(false, false, emptyList(), at(12, 0)))
        // Nothing learned: the usual pace.
        assertTrue(CheckTimes.quiet(true, true, emptyList(), at(11, 0)))
    }

    @Test fun theUndoStartsTheCountAfresh() {
        val l = log(6)
        val undone = CheckTimes.reset(today.minusDays(1).atTime(16, 0))
        assertTrue(CheckTimes.learned(l, undone, today).isEmpty())
        // Asks after the undo count again; it takes MIN_DAYS fresh days.
        val later = LocalDate.of(2026, 10, 20)
        val fresh = l + generateSequence(today) { it.plusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(5).map { pnl(it, 11, 0) }.toList()
        assertEquals(listOf("11:00"), CheckTimes.learned(fresh, undone, later).map { it.clock })
    }

    @Test fun heardAsAsked() {
        for (q in listOf("when do i usually check my p&l", "what time do i usually check my P&L?", "what time do i check my pnl",
                "when do i check my positions", "what times do i ask for my p&l", "do you read my account ahead",
                "why do you read my p&l in advance", "why was my p&l already ready", "do you keep my p&l ready",
                "main p&l kab check karta hoon", "Jarvis, when do I usually check my P&L"))
            assertEquals(CheckTimes.Request.WHEN, CheckTimes.asked(q), q)
        for (q in listOf(CheckTimes.UNDO, "stop getting my p&l ready", "stop reading my account ahead", "stop preparing my p&l in advance",
                "don't read my account ahead", "forget when i check my p&l", "forget the times i check my p&l", "forget the p&l times", "read my p&l only when i ask",
                "p&l pehle se mat padho", "stop getting my P&L ready please"))
            assertEquals(CheckTimes.Request.RESET, CheckTimes.asked(q), q)
        // Never a P&L question, a hush, the routine kept on his yes, or an arm's stop.
        for (q in listOf("what is my p&l", "show my positions", "how am i doing", "stop", "stop reading", "stop stop", "stop orb",
                "forget my routine", "what time does the market open", "when did i take my last trade", "p&l batao", "am i up today"))
            assertNull(CheckTimes.asked(q), q)
    }

    /** Its "stop ..." undos, each a habit's undo ([Commands]' habitUndo): never a STOP of an arm by that name. */
    private val STOPS = listOf("stop getting my p&l ready", "stop reading my account ahead", "stop preparing my p&l in advance",
        "stop loading my positions ahead", "stop warming up my account in advance", "jarvis stop getting my p&l ready please")

    @Test fun itsStopIsTheHabitsUndoNeverAStrategyStop() {
        for (q in STOPS) {
            assertEquals(CheckTimes.Request.RESET, CheckTimes.asked(q), q)
            assertNull(Commands.parse(q), q)
            val p = Ask.parse(q); assertNull(p.command, q); assertNull(p.order, q); assertFalse(Bundle.acts(q), q)
            assertEquals("CheckTimes", CoverageTest().feature(q), q)
        }
        // A real stop keeps its route.
        assertEquals(Command.Kind.STOP_ONE, Commands.parse("stop orb")?.kind)
        for (q in listOf("when do i usually check my p&l", "forget when i check my p&l", "p&l pehle se mat padho")) {
            val p = Ask.parse(q); assertNull(p.order, q); assertNull(p.command, q); assertFalse(Bundle.acts(q), q)
        }
        // Asking about it or undoing it never counts toward it: not logged as a P&L ask, not tallied.
        assertNull(Routine.key("when do i usually check my p&l"))
        assertNull(Routine.key("stop getting my p&l ready"))
        assertEquals(CheckTimes.KEY, Routine.key("what is my p&l"))
        assertEquals(emptyMap(), SelfDoubt.count(emptyMap(), today, "stop getting my p&l ready"))
    }

    @Test fun whatIsSaid() {
        val l = log(6)
        val rs = CheckTimes.learned(l, CheckTimes.Log(), today)
        val said = CheckTimes.say(rs)
        assertTrue(said.contains("you usually check your P&L around 11:00 on 6 of the 6 days") && said.contains("11:00 and 14:30") &&
            said.contains(CheckTimes.UNDO) && said.contains(CheckTimes.ONLY_READ), said)
        assertTrue(CheckTimes.say(emptyList()).startsWith("I don't see a usual time yet"))
        assertTrue(CheckTimes.sayReset(rs).startsWith("Done, Boss: I'll read your account ahead at my usual pace"))
        assertTrue(CheckTimes.sayReset(emptyList()).startsWith("I wasn't reading"))
        // Locked: one neutral reply, the times learned (or whether any were) never named.
        assertFalse(CheckTimes.RESET_LOCKED.contains("11") || CheckTimes.RESET_LOCKED.contains("14") || CheckTimes.RESET_LOCKED.contains("wasn't"))
        // In the ledger, with its undo; personal (never on a locked phone); reset by "undo everything you learned this week".
        val now = today.atTime(12, 0)
        val inputs = Learnings.Inputs(routineLog = l)
        val items = Learnings.items(inputs, now).filter { it.area == Learnings.Area.CHECK_TIMES }
        assertEquals(2, items.size)
        assertTrue(items.all { it.undo == CheckTimes.UNDO })
        assertTrue(Learnings.Area.CHECK_TIMES.personal)
        assertFalse(Learnings.say(Learnings.items(inputs, now), Learnings.Ask.ALL, today, locked = true).contains("P&L"))
        assertTrue(Learnings.say(Learnings.items(inputs, now), Learnings.Ask.ALL, today, locked = false).contains("read ahead just before 11:00"))
        val u = Learnings.undo(inputs, now)
        assertEquals(listOf("11:00", "14:30"), u.checkTimes.map { it.clock })
        assertFalse(u.empty)
        assertTrue(Learnings.offer(u).contains("at the usual pace again"))
        // Undone: gone from the ledger.
        val after = Learnings.Inputs(routineLog = l, checkTimes = CheckTimes.reset(LocalDateTime.of(today, java.time.LocalTime.of(11, 30))))
        assertTrue(Learnings.items(after, now).none { it.area == Learnings.Area.CHECK_TIMES })
    }

    @kotlin.test.Test fun stopCheckingAheadIsTheUndoNeverAStrategyStop() {
        for (q in listOf("stop checking my P&L ahead", "stop checking my account in advance")) {
            if (CheckTimes.asked(q) == null) continue
            kotlin.test.assertNull(Commands.parse(q), q)
        }
        kotlin.test.assertNotNull(Commands.parse("stop orb"))
    }
}
