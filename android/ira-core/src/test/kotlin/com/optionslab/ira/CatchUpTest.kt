package com.optionslab.ira

import com.optionslab.ira.TodayNotes.Category
import com.optionslab.ira.TodayNotes.Note
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatchUpTest {
    private val day = LocalDate.of(2026, 10, 7)
    private fun at(h: Int, m: Int, d: LocalDate = day): LocalDateTime = d.atTime(h, m)
    private fun note(h: Int, m: Int, text: String, c: Category, d: LocalDate = day) = Note(at(h, m, d), text, c)

    private val notes = listOf(
        note(9, 20, "Opening read: Nifty opened 40 points up, above yesterday's high. First candle green.", Category.MARKET),
        note(9, 40, "VIX is up 6% in ten minutes.", Category.MARKET),
        note(10, 5, "Liquidity 15+5 bought 1 lot NIFTY 24800 CE at 112. Stop at 98.", Category.LIQUIDITY),
        note(10, 30, "Three trades before 10:30 - slow down, Boss.", Category.COACH),
        note(10, 45, "Banknifty broke its morning range.", Category.MARKET),
        note(11, 10, "Liquidity 15+5 trade closed at 131 - a win of 19 points.", Category.LIQUIDITY),
        note(11, 20, "Nifty at the day's high.", Category.MARKET),
        note(11, 40, "Solo is watching BankNifty for the midday window.", Category.SOLO_HERO),
        note(12, 0, "Gap fill done on Sensex.", Category.MARKET),
    )

    @Test fun theAnchorIsTheLaterOfSeenAndCaughtUp() {
        assertEquals(null, CatchUp.anchor(null, null))
        assertEquals(at(10, 0), CatchUp.anchor(at(10, 0), null))
        assertEquals(at(11, 0), CatchUp.anchor(null, at(11, 0)))
        assertEquals(at(11, 0), CatchUp.anchor(at(10, 0), at(11, 0)))
        assertEquals(at(12, 0), CatchUp.anchor(at(12, 0), at(11, 0)))
    }

    @Test fun onlyTodaysNotesAfterTheAnchorNewestFirst() {
        val old = note(15, 0, "Yesterday's wrap-up.", Category.COACH, day.minusDays(1))
        val s = CatchUp.since(notes + old, day, at(11, 10))
        assertEquals(listOf(at(12, 0), at(11, 40), at(11, 20)), s.map { it.at })
        assertEquals(notes.size, CatchUp.since(notes + old, day, null).size)
        assertTrue(CatchUp.since(notes, day, at(12, 0)).isEmpty())
    }

    @Test fun thePickHearsEveryCategoryFirstImportantFirstAndCountsTheRest() {
        val p = CatchUp.pick(CatchUp.since(notes, day, null))
        assertEquals(listOf(Category.LIQUIDITY, Category.SOLO_HERO, Category.MARKET, Category.COACH), p.said.map { it.first })
        assertEquals(5, p.said.sumOf { it.second.size })
        // Liquidity's two (newest first), then Solo's, the newest market note, the coach's.
        assertEquals(listOf(at(11, 10), at(10, 5)), p.said[0].second.map { it.at })
        assertEquals(listOf(at(12, 0)), p.said[2].second.map { it.at })
        assertEquals(listOf(Category.MARKET to 4), p.rest)
        // Fewer than five: all of them, nothing left.
        val few = CatchUp.pick(CatchUp.since(notes, day, at(11, 0)))
        assertEquals(4, few.said.sumOf { it.second.size }); assertTrue(few.rest.isEmpty())
        assertTrue(CatchUp.pick(emptyList()).said.isEmpty())
    }

    @Test fun theDigestSaysHeadlinesGroupedAndTheRestCounted() {
        val s = CatchUp.digest(notes, day, at(9, 0), locked = false)
        assertEquals("Since you last looked, Boss, I posted 9 notes. " +
            "Liquidity: Liquidity 15+5 trade closed at 131 - a win of 19 points; Liquidity 15+5 bought 1 lot NIFTY 24800 CE at 112. " +
            "Solo and Hero: Solo is watching BankNifty for the midday window. Market: Gap fill done on Sensex. " +
            "Coach: Three trades before 10:30 - slow down, Boss. And 4 more market notes - they're in Today's notes.", s)
        // Never asked before today: "today so far".
        assertTrue(CatchUp.digest(notes, day, null, locked = false).startsWith("Today so far, Boss, I posted 9 notes."))
        // One note, nothing left over.
        assertEquals("Since you last looked, Boss, I posted 1 note. Market: Gap fill done on Sensex.",
            CatchUp.digest(notes, day, at(11, 45), locked = false))
        assertEquals(CatchUp.NOTHING, CatchUp.digest(notes, day, at(12, 0), locked = false))
        assertEquals(CatchUp.NOTHING, CatchUp.digest(emptyList(), day, null, locked = false))
        // A long note is said only to its headline, without the cut's ellipsis.
        val long = note(13, 0, "Plan for the afternoon: " + "watch the range and the levels and the news and ".repeat(4) + "more. Second sentence.", Category.PLANS)
        val d = CatchUp.digest(listOf(long), day, null, locked = false)
        assertFalse("…" in d, d); assertFalse("Second sentence" in d, d); assertTrue(d.contains("Plans: Plan for the afternoon"), d)
    }

    @Test fun theRestOfSeveralCategoriesIsCountedEach() {
        val many = (0 until 4).map { note(10, it, "Market $it.", Category.MARKET) } + (0 until 3).map { note(11, it, "Coach $it.", Category.COACH) } +
            note(12, 0, "Plan one.", Category.PLANS) + note(12, 1, "Plan two.", Category.PLANS)
        val s = CatchUp.digest(many, day, null, locked = false)
        assertTrue(s.endsWith("And 2 more market notes, 1 more coach note and 1 more plan note - they're in Today's notes."), s)
    }

    @Test fun aLockedPhoneHearsOnlyTheCounts() {
        val s = CatchUp.digest(notes, day, at(9, 0), locked = true)
        assertEquals("Since you last looked, Boss: 2 Liquidity notes, 1 Solo and Hero note, 5 market notes and 1 coach note. Unlock the phone to hear them.", s)
        for (n in notes) assertFalse(TodayNotes.headline(n.text) in s, s)
        assertEquals(CatchUp.NOTHING, CatchUp.digest(notes, day, at(12, 0), locked = true))
    }

    @Test fun askedAsBossSaysIt() {
        for (q in listOf("catch me up", "Jarvis, catch me up", "catch me up please", "catch me up boss", "hey jarvis catch me up on your notes",
            "catch me up on what I missed", "read my notes", "read me my notes", "read your notes", "read out my notes", "read my notes aloud",
            "read me your notes please", "notes padh do", "mere notes padh do", "notes padho", "notes padh ke sunao", "notes suna do", "notes sunao",
            "kya hua jab main nahi tha", "kya hua jab mai nahi tha?", "Jarvis kya hua jab main nahin tha", "jab main nahi tha tab kya hua",
            "main nahi tha to kya hua"))
            assertTrue(CatchUp.asked(q), q)
        for (q in listOf("what did i miss", "catch me up on nifty", "catch me up on the market", "my notes", "show my notes", "today's notes",
            "read today's notes", "read the news", "what did you tell me today", "brief me", "bring me up to speed", "read my notes and close all",
            "delete my notes", "kya hua", "kal kya hua", "notes", "write a note", "read the levels", "catch up"))
            assertFalse(CatchUp.asked(q), q)
    }
}
