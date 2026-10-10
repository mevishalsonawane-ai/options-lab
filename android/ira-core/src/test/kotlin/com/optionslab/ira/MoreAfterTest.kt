package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoreAfterTest {
    private val now = LocalDateTime.of(2026, 10, 5, 11, 0)
    private val today: LocalDate = now.toLocalDate()

    private val levels = "what are the levels on nifty"
    private val levelsAnswer = "Nifty is at 24,612.40 (+0.42% on the day), in a range. Support is 24,500 and resistance 24,700. " +
        "The trend is up on the 15-minute chart. Volume is about average for this hour."
    private val kind: String get() = Clarity.kind(levels)!!

    /** [n] questions of [kind] asked on [day] in the kinds tally. */
    private fun tally(vararg days: Pair<LocalDate, Int>): DoubtTally = days.associate { (d, n) -> d to mapOf(kind to n) }

    private fun log(vararg at: LocalDateTime): MoreAfter.Log = at.fold(MoreAfter.Log()) { l, t -> MoreAfter.heard(l, kind, t) }

    @Test fun onlyWantingMoreCounts() {
        for (s in listOf("more", "tell me more", "go on", "details", "aur batao", "more details", "pura batao", "carry on"))
            assertTrue(MoreAfter.wantsMore(s), s)
        // Hearing it again, "what?", a wish for a topic's length or a question is never wanting more.
        for (s in listOf("repeat", "repeat that", "say that again", "come again", "pardon", "what", "once more", "in detail",
                "detail mein batao", "what are the levels", "stop orb", "short answers", "shorter"))
            assertFalse(MoreAfter.wantsMore(s), s)
    }

    @Test fun theKindOfAShortenedAnswerOnly() {
        assertTrue(MoreAfter.shortened(levels, levelsAnswer))
        assertEquals(kind, MoreAfter.kindAfter(levels, levelsAnswer))
        // A one-line answer had nothing more behind it; no question, a command or an order has no kind.
        assertNull(MoreAfter.kindAfter(levels, "Support is 24,500."))
        assertNull(MoreAfter.kindAfter(null, levelsAnswer))
        assertNull(MoreAfter.kindAfter("stop all strategies", levelsAnswer))
        assertNull(MoreAfter.kindAfter("buy 1 lot of nifty 25000 call", levelsAnswer))
    }

    @Test fun learnedOnlyWhenUsualOnSeveralDays() {
        val t = tally(today.minusDays(2) to 2, today.minusDays(1) to 2)
        // Three "more"s on two days, of four asked: learned.
        val three = log(now.minusDays(2), now.minusDays(1).minusHours(2), now.minusDays(1))
        val r = MoreAfter.learned(three, t, now).single()
        assertEquals(kind, r.kind); assertEquals(3, r.more); assertEquals(4, r.asked); assertEquals(2, r.days)
        assertTrue(r.say().contains("you asked for more after 3 of my 4"), r.say())
        // Too few, all on one day, under half of what he asked, older than the window, or before the undo: nothing.
        assertTrue(MoreAfter.learned(log(now.minusDays(2), now.minusDays(1)), t, now).isEmpty())
        assertTrue(MoreAfter.learned(log(now.minusHours(3), now.minusHours(2), now.minusHours(1)), t, now).isEmpty())
        assertTrue(MoreAfter.learned(three, tally(today.minusDays(2) to 5, today.minusDays(1) to 5), now).isEmpty())
        assertTrue(MoreAfter.learned(log(now.minusDays(40), now.minusDays(39), now.minusDays(38)), t, now).isEmpty())
        assertTrue(MoreAfter.learned(three.copy(resetAt = now.minusHours(1)), t, now).isEmpty())
        assertTrue(MoreAfter.learned(MoreAfter.reset(now.minusHours(1)), t, now).isEmpty())
        // The tally may start late: never fewer asked than "more"s.
        assertEquals(3, MoreAfter.learned(three, emptyMap(), now).single().asked)
    }

    @Test fun staysLearnedWhileItsAnswersAreSaidInFull() {
        // Learned on three "more"s of four asks (review, round 26)...
        val t = tally(today.minusDays(2) to 2, today.minusDays(1) to 2)
        val l = listOf(now.minusDays(2), now.minusDays(1).minusHours(2), now.minusDays(1))
            .fold(MoreAfter.Log()) { acc, at -> MoreAfter.heard(acc, kind, at, t) }
        assertEquals(setOf(kind), l.learnedAt.keys)
        // ...then twenty more asks said in full, with no "more" after them (there is no short line to say it after): still learned,
        // on the counts it was learned on - the share of the later asks never un-learns it.
        val later = tally(today.minusDays(2) to 2, today.minusDays(1) to 2, today to 20)
        assertTrue(MoreAfter.learned(l.copy(learnedAt = emptyMap()), later, now).isEmpty())
        val r = MoreAfter.learned(l, later, now).single()
        assertEquals(kind, r.kind); assertEquals(3, r.more); assertEquals(4, r.asked)
        assertEquals(r, MoreAfter.learned(l, tally(today.plusDays(40) to 50), now.plusDays(45)).single())
        assertTrue(MoreAfter.detailed(levels, MoreAfter.learned(l, later, now)))
        // A kind not yet learned is not frozen; settling twice changes nothing.
        assertEquals(l, MoreAfter.settle(l, later, now))
        assertTrue(MoreAfter.heard(MoreAfter.Log(), kind, now, t).learnedAt.isEmpty())
        // The undo clears it, and the count starts afresh.
        val undone = MoreAfter.reset(now.plusMinutes(1))
        assertTrue(undone.learnedAt.isEmpty())
        assertTrue(MoreAfter.learned(undone, later, now.plusMinutes(2)).isEmpty())
        // A frozen kind from before an undo never counts.
        assertTrue(MoreAfter.learned(l.copy(resetAt = now), later, now.plusMinutes(2)).isEmpty())
    }

    @Test fun saidInFullOnlyForThatKind() {
        val rs = listOf(MoreAfter.Record(kind, 3, 4, 2, now))
        assertTrue(MoreAfter.detailed(levels, rs))
        assertTrue(MoreAfter.detailed("what are the levels on nifty today", rs))
        assertFalse(MoreAfter.detailed(levels, emptyList()))
        assertFalse(MoreAfter.detailed("what is the news", rs))
        // Never a command or an order.
        assertFalse(MoreAfter.detailed("stop all strategies", rs))
        assertFalse(MoreAfter.detailed("buy 1 lot of nifty 25000 call", rs))
        assertFalse(MoreAfter.detailedOf(null, rs))
    }

    @Test fun theVoiceSaysItInFullInsteadOfTheShortLine() {
        val rs = listOf(MoreAfter.Record(kind, 3, 4, 2, now))
        fun said(brief: Boolean, short: Boolean, fuller: List<MoreAfter.Record>, more: Boolean = false) =
            SpokenReply.said(levels, levelsAnswer, more = more, wishedLong = false, brief = brief, { emptyList() }, { emptyList() }, { emptyList() },
                short = short, fuller = { fuller })
        val line = said(brief = false, short = true, fuller = emptyList())
        val full = said(brief = false, short = true, fuller = rs)
        assertNotEquals(line.spoken, full.spoken)
        assertTrue(full.spoken.contains("15-minute") && full.spoken.contains("Volume"), full.spoken)
        assertEquals(levelsAnswer, full.full)
        // As "tell me more" says it.
        assertEquals(said(brief = false, short = true, fuller = emptyList(), more = true).spoken, full.spoken)
        // Boss's own "shorter", the detailed choice, and another kind: as before.
        assertEquals(said(brief = true, short = true, fuller = emptyList()), said(brief = true, short = true, fuller = rs))
        assertEquals(said(brief = false, short = false, fuller = emptyList()), said(brief = false, short = false, fuller = rs))
        val other = listOf(MoreAfter.Record("topic:NEWS", 3, 4, 2, now))
        assertEquals(line, said(brief = false, short = true, fuller = other))
        // A command's words are never lengthened.
        val stop = SpokenReply.said("stop all strategies", levelsAnswer, false, false, false, { emptyList() }, { emptyList() }, { emptyList() }, short = true)
        assertEquals(stop, SpokenReply.said("stop all strategies", levelsAnswer, false, false, false, { emptyList() }, { emptyList() }, { emptyList() },
            short = true, fuller = { rs }))
    }

    @Test fun askedAndUndone() {
        for (q in listOf("which answers do i usually ask more about", "where do i ask for more", "why did you give me the whole answer",
                "which answers do you give in full straight away", "why didn't you keep it short", "kaun se jawab ke baad main aur puchta hoon"))
            assertEquals(MoreAfter.Request.WHICH, MoreAfter.asked(q), q)
        for (q in listOf(MoreAfter.UNDO, "keep your short answers short", "stop skipping the short line", "don't skip the short line",
                "stop giving me the whole answer straight away", "don't give me the full answer first", "give me the short line first",
                "forget which answers i ask more about", "poora jawab seedha mat do"))
            assertEquals(MoreAfter.Request.RESET, MoreAfter.asked(q), q)
        // Ordinary questions and Boss's own commands are never it.
        for (q in listOf("tell me more", "more", "go on", "short answers", "shorter", "keep it short", "full answers", "in short", "in detail",
                "give me the full answer", "give me the short answer", "what are the levels", "which answers do you keep short",
                "how long do i like your answers", "what do i usually ask next", "say your answers in full again", "stop orb",
                "stop all strategies", "why is nifty down", "what is my p&l", "explain more", "keep the levels short"))
            assertNull(MoreAfter.asked(q), q)
    }

    @Test fun theUndoIsNeverAStopOrAnyAction() {
        for (s in listOf(MoreAfter.UNDO, "stop skipping the short line", "stop leaving out the short answer", "stop giving me the whole answer straight away",
                "don't skip the short line", "don't give me the full answer first", "keep your short answers short")) {
            val p = Ask.parse(s)
            assertNull(p.command, s); assertNull(p.order, s)
            assertTrue(Topic.ORDER !in p.topics && Topic.COMMAND !in p.topics, s)
            assertTrue(!Bundle.acts(s) && !FollowUp.acts(s) && !Reminder.asked(s) && !Reminder.cancelAsked(s), s)
            assertNull(Intents.quick(s), s)
            assertEquals(MoreAfter.Request.RESET, MoreAfter.asked(s), s)
        }
        // The commands beside it are as they were.
        assertEquals(Command.Kind.STOP_ONE, Ask.parse("stop orb").command?.kind)
        assertEquals(Command.Kind.STOP_ALL, Ask.parse("stop all strategies").command?.kind)
        assertEquals(Command.Kind.MORE, Ask.parse("tell me more").command?.kind)
        assertEquals(Command.Kind.BRIEF_ON, Ask.parse("short answers").command?.kind)
    }

    @Test fun saidAndShownWithItsUndo() {
        assertTrue(MoreAfter.say(emptyList()).startsWith("I start every answer with its short line"))
        val r = MoreAfter.Record(kind, 3, 4, 2, now)
        val said = MoreAfter.say(listOf(r))
        assertTrue(said.contains("in full straight away") && said.contains(MoreAfter.UNDO) && said.contains("nothing I learn acts"), said)
        assertTrue(MoreAfter.sayReset(listOf(r)).startsWith("Done, Boss"))
        assertTrue(MoreAfter.sayReset(emptyList()).startsWith("I already"))
        assertFalse(MoreAfter.RESET_LOCKED.contains(r.phrase))
        assertTrue(MoreAfter.learnedNote(r).contains(MoreAfter.UNDO))
        // In the ledger, personal, with its undo, and reset by "undo everything you learned this week".
        val t = tally(today.minusDays(2) to 2, today.minusDays(1) to 2)
        val i = Learnings.Inputs(tally = t, moreAfter = log(now.minusDays(2), now.minusDays(1).minusHours(2), now.minusDays(1)))
        val item = Learnings.items(i, now).single { it.area == Learnings.Area.MORE_AFTER }
        assertEquals(MoreAfter.UNDO, item.undo); assertTrue(item.personal)
        assertTrue(item.text().contains("in full straight away aloud"), item.text())
        val u = Learnings.undo(i, now)
        assertEquals(1, u.moreAfter.size)
        assertTrue(Learnings.done(u).contains("the short line first again"), Learnings.done(u))
        assertFalse(Learnings.say(Learnings.items(i, now), Learnings.Ask.ALL, today, locked = true).contains(item.what))
    }
}
