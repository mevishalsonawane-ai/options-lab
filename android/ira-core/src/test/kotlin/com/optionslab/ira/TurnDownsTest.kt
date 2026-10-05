package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnDownsTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val now = today.atTime(14, 40)

    private fun log(vararg at: Pair<TurnDowns.Reason, LocalDateTime>): TurnDowns.Log =
        at.fold(TurnDowns.Log()) { l, (r, t) -> TurnDowns.heard(l, r, t) }

    @Test fun readsTheReasonBossGives() {
        assertEquals(TurnDowns.Reason.LATE, TurnDowns.reason("no, too late in the day"))
        assertEquals(TurnDowns.Reason.LATE, TurnDowns.reason("it's too late in the day"))
        assertEquals(TurnDowns.Reason.LATE, TurnDowns.reason("nah, the market's about to close"))
        assertEquals(TurnDowns.Reason.EXPIRY, TurnDowns.reason("no, it's expiry day"))
        assertEquals(TurnDowns.Reason.RISK, TurnDowns.reason("too risky"))
        assertEquals(TurnDowns.Reason.TREND, TurnDowns.reason("no, that's against the trend"))
        assertEquals(TurnDowns.Reason.CHOPPY, TurnDowns.reason("market is choppy"))
        assertEquals(TurnDowns.Reason.ALREADY, TurnDowns.reason("I already have a position"))
        assertEquals(TurnDowns.Reason.NEWS_OLD, TurnDowns.reason("that's old news"))
        assertEquals(TurnDowns.Reason.LATE, TurnDowns.reason("nahi, bahut late ho gaya"))
    }

    @Test fun aQuestionAnOrderOrPlainWordsAreNoReason() {
        assertNull(TurnDowns.reason("no"))
        assertNull(TurnDowns.reason("how is nifty"))
        assertNull(TurnDowns.reason("is it too late in the day to trade?"))
        assertNull(TurnDowns.reason("is it too late in the day to trade"))
        assertNull(TurnDowns.reason("buy 1 lot of nifty call, it's too late in the day"))
        assertNull(TurnDowns.reason("what is a choppy market"))
    }

    @Test fun oneReasonOnceIsNotLearned() {
        val l = log(TurnDowns.Reason.LATE to now.minusDays(1))
        assertTrue(TurnDowns.learned(l, now).isEmpty())
        assertNull(TurnDowns.upFront(l, now, false))
        assertTrue(TurnDowns.say(l, now).startsWith("I haven't learned why you turn my ideas down yet, Boss"))
    }

    @Test fun theSameReasonTwiceIsSaidUpFrontWhenItFits() {
        val l = log(TurnDowns.Reason.LATE to today.minusDays(3).atTime(14, 50), TurnDowns.Reason.LATE to today.minusDays(1).atTime(15, 5))
        val r = TurnDowns.learned(l, now).single()
        assertEquals(2, r.times)
        assertEquals("Before you answer, Boss: you turned down 2 of my ideas lately as too late in the day - it's 14:40 now.", TurnDowns.upFront(l, now, false))
        // Too late fits only from the hour he first said it.
        assertNull(TurnDowns.upFront(l, today.atTime(11, 0), false))
        assertNotNull(TurnDowns.upFront(l, today.atTime(14, 0), false))
    }

    @Test fun expiryFitsOnlyOnAnExpiryDayAndTheRestAlways() {
        val e = log(TurnDowns.Reason.EXPIRY to now.minusDays(7), TurnDowns.Reason.EXPIRY to now.minusDays(14))
        assertNull(TurnDowns.upFront(e, now, false))
        assertEquals("Before you answer, Boss: you turned down 2 of my ideas lately as it being an expiry day - today is one.", TurnDowns.upFront(e, now, true))
        val r = log(TurnDowns.Reason.RISK to now.minusDays(2), TurnDowns.Reason.RISK to now.minusDays(1), TurnDowns.Reason.LATE to now.minusDays(1))
        assertEquals("Before you answer, Boss: you turned down 2 of my ideas lately as too risky.", TurnDowns.upFront(r, today.atTime(9, 30), false))
    }

    @Test fun oldReasonsAndThoseBeforeTheUndoDoNotCount() {
        val old = log(TurnDowns.Reason.RISK to now.minusDays(40), TurnDowns.Reason.RISK to now.minusDays(35))
        assertTrue(TurnDowns.learned(old, now).isEmpty())
        val l = log(TurnDowns.Reason.RISK to now.minusDays(2), TurnDowns.Reason.RISK to now.minusDays(1))
        assertTrue(TurnDowns.sayReset(l, now).startsWith("Done, Boss"))
        val reset = TurnDowns.reset(l, now)
        assertTrue(TurnDowns.learned(reset, now).isEmpty())
        assertNull(TurnDowns.upFront(reset, now, false))
        // Counted afresh after it.
        val again = TurnDowns.heard(TurnDowns.heard(reset, TurnDowns.Reason.RISK, now.plusMinutes(5)), TurnDowns.Reason.RISK, now.plusMinutes(30))
        assertEquals(1, TurnDowns.learned(again, now.plusHours(1)).size)
    }

    @Test fun theReasonCountsOnlyJustAfterTheRejection() {
        assertTrue(TurnDowns.fresh(now, now.plusMinutes(2)))
        assertFalse(TurnDowns.fresh(now, now.plusMinutes(TurnDowns.AFTER_MINUTES + 1)))
        assertFalse(TurnDowns.fresh(now, now.minusMinutes(1)))
    }

    @Test fun askedAndItsUndo() {
        for (s in listOf("why do i turn down your ideas", "Why do I usually reject your trade ideas?", "what reasons do i give for turning down your ideas",
            "why did you remind me why i turned it down", "main tumhare idea kyun reject karta hoon"))
            assertEquals(TurnDowns.Request.WHICH, TurnDowns.asked(s), s)
        for (s in listOf(TurnDowns.UNDO, "Jarvis, don't remind me why I turn your ideas down", "don't tell me my reasons up front", "mere reasons mat yaad dilao"))
            assertEquals(TurnDowns.Request.RESET, TurnDowns.asked(s), s)
        for (s in listOf("how is nifty", "why did nifty fall", "too late in the day", "reject it", "what are my reminders"))
            assertNull(TurnDowns.asked(s), s)
        // The undo is a question to answer, never an order or a command (and it does not start with "stop").
        val p = Ask.parse(TurnDowns.UNDO)
        assertNull(p.order); assertNull(p.command)
        assertFalse(TurnDowns.UNDO.startsWith("stop"))
        assertFalse(Bundle.acts(TurnDowns.UNDO))
    }

    @Test fun inTheLedgerWithItsUndoAndResetByTheWeeksUndo() {
        val l = log(TurnDowns.Reason.LATE to now.minusDays(3), TurnDowns.Reason.LATE to now.minusDays(1))
        val items = Learnings.items(Learnings.Inputs(turnDowns = l), now)
        val item = items.single { it.area == Learnings.Area.TURN_DOWNS }
        assertEquals(TurnDowns.UNDO, item.undo)
        assertTrue(item.personal)
        assertEquals(today.minusDays(1), item.on)
        // Personal: left out on a locked phone.
        val locked = Learnings.say(items, Learnings.Ask.WEEK, today, locked = true)
        assertFalse(locked.contains("too late in the day"), locked)
        assertTrue(Learnings.say(items, Learnings.Ask.WEEK, today, locked = false).contains("too late in the day"))
        val u = Learnings.undo(Learnings.Inputs(turnDowns = l), now)
        assertFalse(u.empty)
        assertEquals(1, u.turnDowns.size)
        assertTrue(Learnings.offer(u).contains("your reasons for turning down my ideas, said up front (too late in the day) - no longer said"))
        // Every line says "Boss".
        assertTrue(TurnDowns.say(l, now).contains("Boss"))
        assertTrue(TurnDowns.noted(TurnDowns.Reason.LATE).contains("Boss"))
    }
}
