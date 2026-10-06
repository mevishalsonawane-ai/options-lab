package com.optionslab.ira

import com.optionslab.ira.TodayNotes.Category
import com.optionslab.ira.TodayNotes.Note
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodayNotesTest {
    private val day = LocalDate.of(2026, 10, 6)
    private fun at(h: Int, m: Int, d: LocalDate = day): LocalDateTime = d.atTime(h, m)
    private fun note(h: Int, m: Int, text: String, source: String? = null, kind: Category? = null, d: LocalDate = day): Note {
        val t = TodayNotes.tag(text, source, kind)
        return Note(at(h, m, d), text, t.category, t.source)
    }

    @Test fun eachAutomationHasItsCategory() {
        assertEquals(Category.LIQUIDITY, TodayNotes.categoryOf("LIQUIDITY"))
        assertEquals(Category.MARKET, TodayNotes.categoryOf("OPENING"))
        assertEquals(Category.MARKET, TodayNotes.categoryOf("SHARPMOVE"))
        assertEquals(Category.COACH, TodayNotes.categoryOf("SUMMARY"))
        assertEquals(Category.COACH, TodayNotes.categoryOf("FORWARD"))
        assertEquals(Category.PLANS, TodayNotes.categoryOf("TOMORROW"))
        assertEquals(Category.PLANS, TodayNotes.categoryOf("AGENDA"))
        assertEquals(Category.NEWS, TodayNotes.categoryOf("POSNEWS"))
        assertEquals(Category.SOLO_HERO, TodayNotes.categoryOf(TodayNotes.SOLO))
        assertEquals(Category.OTHER, TodayNotes.categoryOf("RELAY"))
        assertEquals(null, TodayNotes.categoryOf("NOT_ONE"))
        assertEquals(null, TodayNotes.categoryOf(null))
    }

    @Test fun aTagComesFromTheSourceThenTheKindThenTheFirstWords() {
        assertEquals(TodayNotes.Tag(Category.LIQUIDITY, "LIQUIDITY"), TodayNotes.tag("BankNifty 12 pts from 54,180", "LIQUIDITY", Category.NEWS))
        assertEquals(TodayNotes.Tag(Category.LIQUIDITY, null), TodayNotes.tag("Liquidity 15+5 trade closed - a normal loss", null, Category.LIQUIDITY))
        // An unknown source: the kind, and no switch.
        assertEquals(TodayNotes.Tag(Category.COACH, null), TodayNotes.tag("x", "NOT_ONE", Category.COACH))
        assertEquals(TodayNotes.Tag(Category.MARKET, "OPENING"), TodayNotes.tag("Opening read, Boss (06 Oct) - the open against yesterday's close: ..."))
        assertEquals(TodayNotes.Tag(Category.SOLO_HERO, TodayNotes.SOLO), TodayNotes.tag("Solo (paper, not proven): bought NIFTY 25000 CE at 120.50"))
        assertEquals(TodayNotes.Tag(Category.SOLO_HERO, null), TodayNotes.tag("Hero: Nifty expiry tomorrow"))
        assertEquals(TodayNotes.Tag(Category.OTHER, null), TodayNotes.tag("Muted by voice, for today only."))
    }

    @Test fun keptOnlyForTheDayAndBounded() {
        var kept = emptyList<Note>()
        kept = TodayNotes.keep(kept, note(15, 40, "old", d = day.minusDays(1)))
        kept = TodayNotes.keep(kept, note(9, 20, "a"))
        assertEquals(listOf("a"), kept.map { it.text })
        for (i in 0 until 10) kept = TodayNotes.keep(kept, note(10, i, "n$i"), max = 4)
        assertEquals(listOf("n6", "n7", "n8", "n9"), kept.map { it.text })
    }

    @Test fun newestFirstAndFiltered() {
        val notes = listOf(note(9, 20, "Opening read, Boss"), note(10, 5, "near a level", "LIQUIDITY"), note(10, 5, "second same minute", "LIQUIDITY"),
            note(15, 35, "wrap", "SUMMARY"), note(15, 45, "plan", "TOMORROW"), note(12, 0, "other day", "VIX", d = day.minusDays(1)))
        val today = TodayNotes.of(notes, day)
        assertEquals(listOf("plan", "wrap", "second same minute", "near a level", "Opening read, Boss"), today.map { it.text })
        assertEquals(listOf("second same minute", "near a level"), TodayNotes.filter(today, Category.LIQUIDITY).map { it.text })
        assertEquals(today, TodayNotes.filter(today, null))
        assertEquals(listOf(Category.MARKET to 1, Category.LIQUIDITY to 2, Category.COACH to 1, Category.PLANS to 1), TodayNotes.counts(today))
        assertEquals(TodayNotes.FILTERS, TodayNotes.chips(today))
        assertEquals(TodayNotes.FILTERS + Category.OTHER, TodayNotes.chips(today + note(11, 0, "Muted by voice")))
        assertEquals(listOf("LIQUIDITY"), TodayNotes.sources(today, Category.LIQUIDITY))
        assertEquals(listOf("OPENING"), TodayNotes.sources(today, Category.MARKET))
        assertEquals(emptyList(), TodayNotes.sources(today, Category.NEWS))
        assertEquals(listOf("Market", "Liquidity", "Solo/Hero", "Coach", "Plans", "News"), TodayNotes.FILTERS.map { it.label })
    }

    @Test fun headlinesAreTheFirstSentenceCutAtAWord() {
        assertEquals("BankNifty is 12 pts from 54,180", TodayNotes.headline("BankNifty is 12 pts from 54,180. Its entry would be a close through."))
        assertEquals("Opening read, Boss (06 Oct) - the open against yesterday's close: Nifty +0.4%",
            TodayNotes.headline("Opening read, Boss (06 Oct) - the open against yesterday's close: Nifty +0.4%\nBankNifty opened 54,120."))
        // A figure's point is not a sentence's end.
        assertEquals("Nifty at 25,012.5 now", TodayNotes.headline("Nifty at 25,012.5 now"))
        val long = TodayNotes.headline("word ".repeat(40))
        assertTrue(long.endsWith("…") && long.length <= TodayNotes.HEADLINE_MAX + 1, long)
        assertFalse(long.contains("wor…"))
        assertEquals("", TodayNotes.headline("  "))
    }

    @Test fun theDigestCountsAndNamesTheLatestThree() {
        assertEquals(TodayNotes.NONE, TodayNotes.digest(emptyList(), day))
        assertEquals(TodayNotes.NONE, TodayNotes.digest(listOf(note(9, 20, "x", "OPENING", d = day.minusDays(1))), day))
        val one = TodayNotes.digest(listOf(note(9, 20, "Opening read, Boss (06 Oct) - flat open.")), day)
        assertEquals("Today I've posted 1 note by myself, Boss: Market 1. The latest: 09:20 (Market) Opening read, Boss (06 Oct) - flat open. ${TodayNotes.WHERE}", one)
        val notes = listOf(note(9, 20, "Opening read, Boss."), note(10, 5, "BankNifty 12 pts from 54,180. More.", "LIQUIDITY"),
            note(12, 1, "Solo (paper, not proven): bought NIFTY 25000 CE at 120.50"), note(15, 35, "Your day: +1,250.", "SUMMARY"),
            note(15, 45, "Tomorrow, Tue 07 Oct: Nifty expiry.", "TOMORROW"))
        val d = TodayNotes.digest(notes, day)
        assertEquals("Today I've posted 5 notes by myself, Boss: Market 1, Liquidity 1, Solo/Hero 1, Coach 1, Plans 1. The latest 3: " +
            "15:45 (Plans) Tomorrow, Tue 07 Oct: Nifty expiry; 15:35 (Coach) Your day: +1,250; " +
            "12:01 (Solo/Hero) Solo (paper, not proven): bought NIFTY 25000 CE at 120.50. ${TodayNotes.WHERE}", d)
    }

    @Test fun askedAsBossSaysIt() {
        for (s in listOf("what did you tell me today", "What did you tell me today?", "what have you told me today", "what did you say today",
            "what all did you tell me today", "what did you post today", "what notes did you post today", "today's notes", "Today’s notes",
            "show today's notes", "your notes today", "notes for today", "aaj kya bataya", "aaj tumne kya bataya", "tumne aaj kya bataya",
            "aaj kya kya bataya", "aaj ke notes", "jarvis what did you tell me today", "what did you tell me this morning"))
            assertTrue(TodayNotes.asked(s), s)
        for (s in listOf("what did you say", "what did you tell me", "repeat that", "what did i ask", "my notes", "my notes today", "journal",
            "what did you tell me yesterday", "what did you tell me about liquidity", "what did you tell me about nifty today", "kal kya bataya",
            "delete today's notes", "remind me of today's notes", "what did i tell you today", "notes"))
            assertFalse(TodayNotes.asked(s), s)
    }
}
