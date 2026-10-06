package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CondNeedsTest {
    private val today = LocalDate.of(2026, 10, 7)
    private val now = today.atTime(15, 35)

    @AfterTest fun reset() { CondNeeds.forgetAsked() }

    @Test fun eachConditionalInstructionIsKeptByItsKindOnly() {
        assertEquals(CondNeeds.Need.LEVEL, CondNeeds.need("if Nifty falls below 23850 exit all"))
        assertEquals(CondNeeds.Need.LEVEL, CondNeeds.need("stop ORB if Nifty falls 100 points"))
        assertEquals(CondNeeds.Need.LEVEL, CondNeeds.need("agar Nifty 100 point gire to sab band kar do"))
        assertEquals(CondNeeds.Need.PNL, CondNeeds.need("turn on the kill switch if I lose 5000"))
        assertEquals(CondNeeds.Need.PNL, CondNeeds.need("agar loss 5000 ho jaye to sab band kar do"))
        assertEquals(CondNeeds.Need.POSITION, CondNeeds.need("if my call falls to 80 sell it"))
        // Not a conditional instruction: nothing kept.
        for (q in listOf("exit all", "alert me when BankNifty goes below 51000", "what if I buy Nifty calls", "should I exit if Nifty falls",
                "close all at 3:15", "stop mentioning my conditional orders", "how is the market"))
            assertNull(CondNeeds.need(q), q)
        val log = CondNeeds.noted(CondNeeds.Log(), "if Nifty falls below 23850 exit all", now)
        assertEquals(listOf(CondNeeds.Seen(CondNeeds.Need.LEVEL, now)), log.seen)
        assertEquals(log, CondNeeds.noted(log, "how is the market", now))
        // Older than the window: dropped when the next is kept.
        val later = CondNeeds.noted(log, "if my call falls to 80 sell it", now.plusDays(CondNeeds.WINDOW_DAYS + 1))
        assertEquals(listOf(CondNeeds.Need.POSITION), later.seen.map { it.need })
    }

    private fun asks(n: Int, days: Int, text: String = "if Nifty falls below 23850 exit all"): CondNeeds.Log =
        (0 until n).fold(CondNeeds.Log()) { l, i -> CondNeeds.noted(l, text, today.minusDays((i % days).toLong() + 1).atTime(10, i)) }

    @Test fun learnedOnlyAfterEnoughTimesOnEnoughDays() {
        assertTrue(CondNeeds.learned(asks(5, 2), now).isEmpty())
        assertTrue(CondNeeds.learned(asks(2, 2), now).isEmpty())
        val rs = CondNeeds.learned(asks(4, 3), now)
        assertEquals(1, rs.size)
        assertEquals(CondNeeds.Need.LEVEL, rs[0].need)
        assertEquals(4, rs[0].times); assertEquals(3, rs[0].days)
        assertEquals(today.minusDays(1), rs[0].newest)
        // Out of the window: forgotten.
        assertTrue(CondNeeds.learned(asks(4, 3), now.plusDays(CondNeeds.WINDOW_DAYS)).isEmpty())
        // The undo: nothing before it counts.
        val undone = asks(4, 3).copy(resetAt = today.minusDays(1).atStartOfDay())
        assertTrue(CondNeeds.learned(undone, now).isEmpty())
        assertTrue(CondNeeds.learned(CondNeeds.reset(now), now).isEmpty())
    }

    @Test fun theWrapUpSaysItOnceAFactAndAPointer() {
        val log = asks(4, 3)
        val r = CondNeeds.next(CondNeeds.learned(log, now), log)!!
        val line = CondNeeds.wrapLine(r)
        assertTrue(line.contains("4 times on 3 days") && line.contains("More, then Alerts") && line.contains(CondNeeds.UNDO) &&
            line.contains("I set nothing"), line)
        // Never his own level or words.
        assertFalse(line.contains("23850"), line)
        val told = CondNeeds.told(log, r)
        assertNull(CondNeeds.next(CondNeeds.learned(told, now), told))
        // Each kind names its own screen.
        assertTrue(CondNeeds.Need.PNL.tool.contains("More, then Bot settings"))
        assertTrue(CondNeeds.Need.POSITION.tool.contains("Trade, then Account") && CondNeeds.Need.POSITION.tool.contains("Protect"))
        // Locked: the undo's reply never names what was learned.
        assertFalse(CondNeeds.RESET_LOCKED.contains("Nifty") || CondNeeds.RESET_LOCKED.contains("level") || CondNeeds.RESET_LOCKED.contains("hadn't"))
        assertTrue(CondNeeds.say(emptyList()).startsWith("Nothing yet"))
        assertTrue(CondNeeds.say(CondNeeds.learned(log, now)).contains(CondNeeds.ONLY_POINTER))
    }

    /** Its "stop ..." undos, each a habit's undo ([Commands]' habitUndo): never a STOP of an arm by that name. */
    @Test fun itsStopIsTheHabitsUndoNeverAStrategyStop() {
        for (q in listOf(CondNeeds.UNDO, "stop telling me about my conditional orders", "jarvis stop mentioning my conditional orders please",
                "stop naming my conditional orders")) {
            assertEquals(CondNeeds.Request.RESET, CondNeeds.asked(q), q)
            assertNull(Commands.parse(q), q)
            val p = Ask.parse(q); assertNull(p.command, q); assertNull(p.order, q); assertFalse(Bundle.acts(q), q)
            assertFalse(Conditional.asked(q), q)
            assertEquals("CondNeeds", CoverageTest().feature(q), q)
        }
        assertEquals(Command.Kind.STOP_ONE, Commands.parse("stop orb")?.kind)
        assertEquals(CondNeeds.Request.WHICH, CondNeeds.asked("what have you learned about my conditional orders"))
        // Asking about it or undoing it never counts toward anything.
        assertNull(Routine.key(CondNeeds.UNDO))
        assertEquals(emptyMap(), SelfDoubt.count(emptyMap(), today, CondNeeds.UNDO))
        for (q in listOf("forget my orders", "stop orb", "show my orders", "if nifty falls below 24000 exit all", "what are my open orders"))
            assertNull(CondNeeds.asked(q), q)
    }

    @Test fun inTheLedgerPersonalAndUndoneWithTheWeek() {
        val log = asks(4, 3)
        val inputs = Learnings.Inputs(condNeeds = log)
        val items = Learnings.items(inputs, now).filter { it.area == Learnings.Area.COND_NEEDS }
        assertEquals(1, items.size)
        assertEquals(CondNeeds.UNDO, items[0].undo)
        assertTrue(Learnings.Area.COND_NEEDS.personal)
        assertFalse(Learnings.say(Learnings.items(inputs, now), Learnings.Ask.ALL, today, locked = true).contains("level"))
        val u = Learnings.undo(inputs, now)
        assertEquals(1, u.condNeeds.size)
        assertTrue(Learnings.offer(u).contains("conditional instructions"))
        val after = Learnings.Inputs(condNeeds = CondNeeds.reset(LocalDateTime.of(today, java.time.LocalTime.of(9, 0))))
        assertTrue(Learnings.items(after, now).none { it.area == Learnings.Area.COND_NEEDS })
    }
}
