package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MorningSenseTest {
    private val today = LocalDate.of(2026, 10, 5) // a Monday
    private val now: LocalDateTime = today.atTime(15, 0)

    private val bots = "Bots: Range Fade is not armed (you usually arm it). Arm it in Algo if you want it today"
    private val holidays = "NSE holiday list up to date"
    private val login = "Zerodha not logged in: log in before 09:15 (Cabinet → Zerodha)"
    private val kill = "Kill switch is ON: every Zerodha order is refused (paper still trades)"

    /** The check on each of the last [days] weekdays before today (oldest first), failing [lines] on each. */
    private fun mornings(days: Int, vararg lines: String, log0: MorningSense.Log = MorningSense.Log()): MorningSense.Log {
        var log = log0
        var d = today.minusDays(1)
        val ds = ArrayList<LocalDate>()
        while (ds.size < days) { if (d.dayOfWeek.value <= 5) ds += d; d = d.minusDays(1) }
        ds.reversed().forEach { log = MorningSense.noted(log, it, lines.map { l -> "✗ $l" }) }
        return log
    }

    @Test fun eachLineHasItsKeyAndOnlyTheMinorOnesAreLearned() {
        assertEquals("BOTS", MorningSense.key("✗ $bots"))
        assertEquals("HOLIDAYS", MorningSense.key("✗ $holidays"))
        assertEquals("LOGIN", MorningSense.key("✗ $login"))
        assertEquals("KILL", MorningSense.key(kill))
        assertEquals("SELF_CHECK", MorningSense.key("Self-check: Night study needs a look."))
        assertEquals("STATIC_IP", MorningSense.key("✗ Not on your registered static IP 1.2.3.4: new live positions will be refused"))
        assertEquals("RELAY", MorningSense.key("✗ Relay server: not answering"))
        assertEquals("PRICES", MorningSense.key("✗ No live prices yet from Nifty"))
        assertNull(MorningSense.key("✗ Something new the check says"))
        listOf("LOGIN", "KILL", "STATIC_IP", "RELAY", "GUARDS", "PRICES", "CONNECTION", "CONTRACTS", "MARGIN", "CARRIED").forEach {
            assertFalse(MorningSense.learned(it), it)
        }
        assertFalse(MorningSense.learned(null))
    }

    @Test fun anItemLeftMorningAfterMorningIsSaidBriefly() {
        // Failing on 4 mornings in a row: 3 outcomes, all left as it was.
        val log = mornings(4, bots)
        val r = MorningSense.records(log, today).single()
        assertEquals(3, r.left); assertEquals(0, r.fixed); assertTrue(r.brief)
        assertEquals(setOf("BOTS"), MorningSense.briefToday(log, today))
        // Twice left is not yet a habit.
        assertTrue(MorningSense.briefToday(mornings(3, bots), today).isEmpty())
    }

    @Test fun anItemBossFixesIsReadInFull() {
        // Fails and is fixed by the next morning, again and again.
        var log = MorningSense.Log()
        var d = today.minusDays(12)
        var fail = true
        while (d.isBefore(today)) {
            if (d.dayOfWeek.value <= 5) { log = MorningSense.noted(log, d, if (fail) listOf("✗ $bots") else emptyList()); fail = !fail }
            d = d.plusDays(1)
        }
        val r = MorningSense.records(log, today).single()
        assertTrue(r.fixed >= 3); assertFalse(r.brief)
        assertTrue(MorningSense.briefToday(log, today).isEmpty())
        assertTrue(MorningSense.say(log, today).contains("What you usually fix by the next morning"))
    }

    @Test fun aFreshFailureIsReadInFullEvenForAnItemUsuallyLeft() {
        // Left 4 times, then fixed on Friday: Monday's failure is fresh.
        var log = mornings(5, bots)
        log = MorningSense.noted(log, today.minusDays(3), emptyList())
        assertTrue(MorningSense.usuallyLeft(log, today).isNotEmpty())
        assertTrue(MorningSense.briefToday(log, today).isEmpty())
    }

    @Test fun safetyItemsAreNeverLearnedHoweverOftenLeft() {
        val log = mornings(8, login, kill, holidays)
        assertEquals(listOf("HOLIDAYS"), MorningSense.records(log, today).map { it.key })
        val brief = MorningSense.briefToday(log, today)
        assertEquals(setOf("HOLIDAYS"), brief)
        val said = MorningSense.aloud(3, listOf(login, kill, holidays), brief)
        assertTrue(said.startsWith("3 things need you: $login. $kill. And one you usually leave as it is - the holiday list - is in the chat."), said)
    }

    @Test fun withNothingLearnedTheWordsAreTheCheckOwn() {
        assertEquals("2 things need you: $login. $bots.", MorningSense.aloud(2, listOf(login, bots), emptySet()))
        assertEquals("1 thing need you: $login.", MorningSense.aloud(1, listOf(login), setOf("BOTS")))
        // Only brief ones: the count stays whole.
        val s = MorningSense.aloud(2, listOf(bots, holidays), setOf("BOTS", "HOLIDAYS"))
        assertEquals("2 things need you: 2 you usually leave as they are - the bots against the ones you usually arm and the holiday list - are in the chat.", s)
    }

    @Test fun theUndoStartsTheCountAfresh() {
        val log = mornings(5, bots)
        assertTrue(MorningSense.usuallyLeft(log, today).isNotEmpty())
        val reset = MorningSense.reset(log, now)
        assertTrue(MorningSense.usuallyLeft(reset, today).isEmpty())
        assertTrue(MorningSense.briefToday(reset, today).isEmpty())
        // Old mornings beyond the window count no more.
        assertTrue(MorningSense.usuallyLeft(log, today.plusDays(MorningSense.WINDOW_DAYS + 7)).isEmpty())
        assertTrue(MorningSense.sayReset(log, today).startsWith("Done, Boss"))
        assertTrue(MorningSense.sayReset(reset, today).startsWith("I already read out"))
    }

    @Test fun aRetriedCheckReplacesItsDay() {
        var log = MorningSense.noted(MorningSense.Log(), today, listOf("✗ $bots"))
        log = MorningSense.noted(log, today, listOf("✗ $holidays", "✗ $login"))
        assertEquals(listOf(MorningSense.Morning(today, setOf("HOLIDAYS"))), log.mornings)
    }

    @Test fun recognisesTheQuestions() {
        listOf("which morning check items do you skip", "what do you leave out of the morning check", "which items do you skip in the morning check",
            "why was the morning check so short today", "which morning check items do i usually fix", "what do i usually ignore in the morning check",
            "morning check mein kya chhodte ho").forEach { assertEquals(MorningSense.Request.WHICH, MorningSense.asked(it), it) }
        listOf("say the whole morning check again", "read the full morning check", "don't skip anything in the morning check",
            "read every item in the morning check", "poora morning check sunao").forEach { assertEquals(MorningSense.Request.RESET, MorningSense.asked(it), it) }
        listOf("am i ready to trade", "go through my morning checklist", "which alerts do you hold back", "say everything again",
            "what did you study last night", "say that again").forEach { assertNull(MorningSense.asked(it), it) }
    }

    @Test fun theAnswerNamesWhatIsBriefAndWhatNever() {
        val s = MorningSense.say(mornings(5, bots), today)
        assertTrue(s.contains("the bots against the ones you usually arm (fixed by the next morning 0 of the 4 times it failed)"), s)
        assertTrue(s.contains(MorningSense.NEVER) && s.contains(MorningSense.UNDO), s)
        assertTrue(MorningSense.say(MorningSense.Log(), today).startsWith("I read out every failing item"))
    }

    @Test fun theLedgerShowsItWithItsUndoAndTheWeekUndoResetsIt() {
        val i = Learnings.Inputs(morning = mornings(5, bots))
        val items = Learnings.items(i, now).filter { it.area == Learnings.Area.MORNING }
        assertEquals(1, items.size)
        assertEquals(MorningSense.UNDO, items[0].undo)
        val u = Learnings.undo(i, now)
        assertEquals(listOf("BOTS"), u.morning.map { it.key })
        assertFalse(u.empty)
        assertTrue(Learnings.offer(u).contains("read out in full again"))
    }
}
