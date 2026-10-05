package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TalkHoursTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val now: LocalDateTime = today.atTime(20, 0)

    /** Boss asked something at each of [hours] on each of the [days] days before today. */
    private fun talked(days: Int, vararg hours: Int, log0: TalkHours.Log = TalkHours.Log()): TalkHours.Log {
        var log = log0
        for (d in days downTo 1) for (h in hours) log = TalkHours.heard(log, today.minusDays(d.toLong()).atTime(h, 20))
        return log
    }

    private val long = "Boss, here is your week. You made three trades. Two worked. The best was the Nifty put."

    @Test fun nothingIsLearnedBeforeEnoughDays() {
        val log = talked(TalkHours.MIN_ACTIVE - 1, 9, 10)
        assertFalse(TalkHours.record(log, now).learned)
        assertEquals(long, TalkHours.aloud(long, today.atTime(7, 0), TalkHours.record(log, now).hours))
        assertTrue(TalkHours.say(log, now).contains("haven't learned your hours yet, Boss"))
    }

    @Test fun hisHoursAreTheOnesHeUsesOnEnoughDays() {
        var log = talked(6, 9, 10)
        log = TalkHours.heard(log, today.minusDays(2).atTime(22, 5)) // once only: not his hour
        val r = TalkHours.record(log, now)
        assertEquals(listOf(9, 10), r.hours)
        assertEquals(6, r.active)
        assertEquals("09:00 to 11:00", TalkHours.spans(r.hours))
        assertEquals("09:00 to 11:00 and 14:00 to 15:00", TalkHours.spans(listOf(9, 10, 14)))
        assertTrue(TalkHours.say(log, now).startsWith("Boss, you usually talk to me between 09:00 to 11:00"))
    }

    @Test fun aLongBriefingOutsideHisHoursIsOneSentenceAloud() {
        val hours = listOf(9, 10)
        assertEquals("Boss, here is your week. The rest is in the chat.", TalkHours.aloud(long, today.atTime(7, 0), hours))
        // Inside, or within half an hour of, his hours: as it is.
        assertEquals(long, TalkHours.aloud(long, today.atTime(9, 40), hours))
        assertEquals(long, TalkHours.aloud(long, today.atTime(8, 45), hours))
        assertEquals(long, TalkHours.aloud(long, today.atTime(11, 20), hours))
        // Short, Hindi or with a data-age note: as it is.
        assertEquals("Boss, Nifty is up. VIX is calm.", TalkHours.aloud("Boss, Nifty is up. VIX is calm.", today.atTime(7, 0), hours))
        val noted = "Nifty is at 24,500. It is 20 minutes old. Range day. Watch 24,600."
        assertEquals(noted, TalkHours.aloud(noted, today.atTime(7, 0), hours))
        val hi = "बॉस, निफ्टी ऊपर है। वीआईएक्स शांत है। रेंज डे है।"
        assertEquals(hi, TalkHours.aloud(hi, today.atTime(7, 0), hours))
        // Always addressed to Boss.
        assertTrue(TalkHours.aloud("Here is your week. Three trades. Two worked.", today.atTime(7, 0), hours).startsWith("Boss,"))
    }

    @Test fun resetStartsTheCountAfresh() {
        val log = TalkHours.reset(talked(8, 9), now.minusHours(1))
        assertFalse(TalkHours.record(log, now).learned)
        assertTrue(TalkHours.sayReset(talked(8, 9), now).startsWith("Done, Boss"))
        assertTrue(TalkHours.sayReset(log, now).startsWith("I already say every briefing in full, Boss"))
    }

    @Test fun onlyTheWindowCounts() {
        var log = TalkHours.Log()
        for (d in 30L downTo 23L) log = TalkHours.heard(log, today.minusDays(d).atTime(9, 0))
        assertFalse(TalkHours.record(log, now).learned)
    }

    @Test fun theLedgerShowsItWithItsUndoAndTheWeekUndoResetsIt() {
        val log = talked(6, 9, 10)
        val items = Learnings.items(Learnings.Inputs(hours = log), now)
        val it = items.single { it.area == Learnings.Area.HOURS }
        assertEquals(TalkHours.UNDO, it.undo)
        assertTrue(it.text().contains("09:00 to 11:00"))
        val u = Learnings.undo(Learnings.Inputs(hours = log), now)
        assertEquals(listOf(9, 10), u.hours)
        assertFalse(u.empty)
        assertTrue(Learnings.done(u).contains("in full at any hour again"))
        assertTrue(Learnings.undo(Learnings.Inputs(hours = TalkHours.reset(log, now)), now).empty)
    }

    @Test fun askedWordings() {
        for (s in listOf("when do I usually talk to you", "what time of day do I talk to you?", "which hours do you keep briefings short",
            "why was the briefing so short", "Jarvis, why do you keep your updates short", "main tumse kab baat karta hoon"))
            assertEquals(TalkHours.Request.WHICH, TalkHours.asked(s), s)
        for (s in listOf("say your briefings in full at any hour", "don't shorten your briefings", "no need to cut the updates short",
            "briefing poori bolo hamesha"))
            assertEquals(TalkHours.Request.RESET, TalkHours.asked(s), s)
        for (s in listOf("why was the morning briefing so short", "give me the briefing", "what time is it", "when does the market open",
            "say your answers in full again", "arm the bot", "talk to me"))
            assertNull(TalkHours.asked(s), s)
        // Nothing it hears is an order or a command.
        for (s in listOf("say your briefings in full at any hour", "when do i usually talk to you"))
            assertTrue(Ask.parse(s).order == null && Ask.parse(s).command == null, s)
    }
}
