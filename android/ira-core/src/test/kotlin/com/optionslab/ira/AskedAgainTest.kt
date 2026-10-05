package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskedAgainTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val now: LocalDateTime = today.atTime(15, 0)
    private val t0: LocalDateTime = today.atTime(10, 0)
    private val levels = "what are the levels on banknifty"

    /** [said] in order, each [gapS] seconds after the one before, from [start]. */
    private fun run(said: List<String>, gapS: Long, start: LocalDateTime = t0, log: AskedAgain.Log = AskedAgain.Log()): AskedAgain.Step =
        said.foldIndexed(AskedAgain.Step(log, null, null)) { i, s, q -> AskedAgain.heard(s.log, s.open, q, start.plusSeconds(gapS * i)) }

    @Test fun aMarketReadIsItsTopicAndIndices() {
        val r = AskedAgain.read(levels)
        assertTrue(r is AskedAgain.Read.Reading, "$r")
        assertEquals("topic:LEVELS", (r as AskedAgain.Read.Reading).kind)
        assertEquals(listOf("market:BANKNIFTY"), r.markets)
        // Commands and orders have no kind; the account is not a market read.
        assertEquals(AskedAgain.Read.None, AskedAgain.read("stop all strategies"))
        assertEquals(AskedAgain.Read.None, AskedAgain.read("buy 1 lot nifty 25000 ce"))
        assertEquals(AskedAgain.Read.Other, AskedAgain.read("what is my p&l"))
    }

    @Test fun theSameReadAskedAgainWithinMinutesIsNoted() {
        val s = run(listOf(levels, "banknifty levels please"), 120)
        val e = assertNotNull(s.again)
        assertEquals("topic:LEVELS", e.kind)
        assertEquals(120, e.gapS)
        assertEquals(1, s.log.events.size)
        // A third time counts against the second.
        assertEquals(2, run(listOf(levels, levels, levels), 100).log.events.size)
    }

    @Test fun aFreshQuestionLaterOrElsewhereIsNot() {
        // Too late: a fresh question.
        assertNull(run(listOf(levels, levels), AskedAgain.MAX_GAP_S + 1).again)
        // The same words heard twice in a breath: not Boss asking again.
        assertNull(run(listOf(levels, levels), 3).again)
        // Another index, or another topic.
        assertNull(run(listOf(levels, "what are the levels on nifty"), 60).again)
        assertNull(run(listOf(levels, "what is the trend on banknifty"), 60).again)
        // Another kind of question between closes the read; a command between leaves it open.
        assertNull(run(listOf(levels, "what is my p&l", levels), 60).again)
        assertNotNull(run(listOf(levels, "stop all strategies", levels), 60).again)
        // How the market is doing asked again is wanting it fresh, never a miss.
        assertNull(run(listOf("how is nifty", "how is nifty"), 60).again)
    }

    @Test fun theRecordSetsReAsksAgainstAskedAndNamesTheIndex() {
        var log = AskedAgain.Log()
        for (d in 1..3L) log = run(listOf(levels, levels), 90, start = now.minusDays(d), log = log).log
        val tally: DoubtTally = mapOf(today.minusDays(1) to mapOf("topic:LEVELS" to 20))
        val r = AskedAgain.records(log, tally, now).single()
        assertEquals(3, r.again)
        assertEquals(20, r.asked)
        assertEquals(15, r.share)
        assertEquals("market:BANKNIFTY", r.market)
        assertTrue(r.say().contains("3 of my 20 answers on the levels, mostly about BankNifty"), r.say())
        assertEquals("about 2 minutes later", r.later())
        // Asked is never under re-asked, even with no tally.
        assertEquals(3, AskedAgain.records(log, emptyMap(), now).single().asked)
        // Older than the window: gone.
        assertTrue(AskedAgain.records(log, tally, now.plusDays(AskedAgain.WINDOW_DAYS + 1)).isEmpty())
    }

    @Test fun theLedgerShowsItAsARecordWithNoUndo() {
        val once = run(listOf(levels, levels), 120, start = now.minusHours(2)).log
        assertTrue(Learnings.items(Learnings.Inputs(again = once), now).none { it.area == Learnings.Area.AGAIN })
        val twice = run(listOf(levels, levels, levels), 120, start = now.minusHours(2)).log
        val item = Learnings.items(Learnings.Inputs(again = twice), now).single { it.area == Learnings.Area.AGAIN }
        assertNull(item.undo)
        assertFalse(item.personal)
        assertTrue(item.text().contains("asked again within 5 minutes"), item.text())
        assertTrue(item.text().contains("changes nothing I do"), item.text())
        // A record, not a change in how he works; shown in the week's learning.
        val items = Learnings.items(Learnings.Inputs(again = twice), now)
        assertTrue(Learnings.say(items, Learnings.Ask.WEEK, today, locked = true).contains("Market reads you asked again"))
        assertFalse(Learnings.say(items, Learnings.Ask.CHANGED, today, locked = false).contains("Market reads you asked again"))
        // Never reset by undoing the week (nothing to undo: it changes nothing).
        assertTrue(Learnings.undo(Learnings.Inputs(again = twice), now).empty)
    }

    @Test fun askedInBossesWords() {
        for (s in listOf("which of your answers do I ask again?", "Which answers did I have to ask twice", "what do I keep asking again",
            "what do i ask you twice", "Jarvis, which of your market reads missed?", "which answers didn't land", "which of your answers do i repeat",
            "kaun se jawab main dobara puchta hoon", "main kya dobara puchta hoon"))
            assertTrue(AskedAgain.asked(s), s)
        for (s in listOf("say that again", "ask again", "what are the levels again", "what do i usually ask", "which answers do you keep short",
            "what did i say about expiry", "come again"))
            assertFalse(AskedAgain.asked(s), s)
    }

    @Test fun saysItHonestly() {
        assertTrue(AskedAgain.say(AskedAgain.Log(), emptyMap(), now).startsWith("You haven't asked any of my market reads again"))
        val once = run(listOf(levels, levels), 120, start = now.minusHours(2)).log
        assertTrue(AskedAgain.say(once, emptyMap(), now).contains("too few"))
        val twice = run(listOf(levels, levels, levels), 120, start = now.minusHours(2)).log
        val said = AskedAgain.say(twice, emptyMap(), now)
        assertTrue(said.startsWith("Boss, in the last 30 days"), said)
        assertTrue(said.contains("answers on the levels"), said)
        assertTrue(said.contains("nothing I learn acts"), said)
    }
}
