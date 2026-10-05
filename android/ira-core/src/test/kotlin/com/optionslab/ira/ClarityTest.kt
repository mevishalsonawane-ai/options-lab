package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClarityTest {
    private val today = LocalDate.of(2026, 10, 5)
    private val now: LocalDateTime = today.atTime(15, 0)
    private val levels = "topic:LEVELS"

    private fun log(kind: String, n: Int, from: LocalDateTime = now.minusDays(1)): Clarity.Log =
        (0 until n).fold(Clarity.Log()) { l, i -> Clarity.heard(l, kind, from.minusHours(i.toLong())) }

    private fun tally(kind: String, n: Int): DoubtTally = mapOf(today.minusDays(1) to mapOf(kind to n))

    @Test fun theKindIsTheAnswersTopic() {
        assertEquals(levels, Clarity.kind("what are the levels on nifty"))
        assertNull(Clarity.kind("stop all strategies"))
        assertNull(Clarity.kind("buy 1 lot nifty 25000 ce"))
    }

    @Test fun hearsBossNotFollowing() {
        for (s in listOf("what?", "What", "huh", "come again", "Sorry, what?", "pardon", "say that again", "I didn't get that", "i didnt understand",
            "what do you mean", "samjha nahi", "kya matlab hai", "matlab?", "kya bola", "phir se bolo", "too confusing", "Jarvis, what?"))
            assertTrue(Clarity.unclear(s), s)
        // Wanting more is not finding it unclear; a real question never is.
        for (s in listOf("tell me more", "go on", "more", "why", "what is nifty doing", "what are the levels", "what is theta", "kya hal hai nifty ka"))
            assertFalse(Clarity.unclear(s), s)
    }

    @Test fun aFewRepeatsChangeNothing() {
        val r = Clarity.records(log(levels, 2), tally(levels, 3), now).single()
        assertEquals(Clarity.Level.NORMAL, r.level)
        assertNull(Clarity.sentences("what are the levels on nifty", Clarity.shorter(log(levels, 2), tally(levels, 3), now)))
    }

    @Test fun aKindOftenUnclearIsSaidShorter() {
        // 3 of 8: (3 / 12) = 0.25 - two sentences aloud.
        val s = Clarity.shorter(log(levels, 3), tally(levels, 8), now)
        assertEquals(Clarity.Level.SHORTER, s.single().level)
        assertEquals(2, Clarity.sentences("what are the levels on banknifty", s))
        // Other kinds, commands and orders: as usual.
        assertNull(Clarity.sentences("how is the trend on nifty", s))
        assertNull(Clarity.sentences("stop all strategies", s))
        // 6 of 10: one sentence.
        val worse = Clarity.shorter(log(levels, 6), tally(levels, 10), now)
        assertEquals(Clarity.Level.SHORTEST, worse.single().level)
        assertEquals(1, Clarity.sentences("nifty levels", worse))
    }

    @Test fun manyAskedKeepsItNormal() {
        assertEquals(Clarity.Level.NORMAL, Clarity.records(log(levels, 3), tally(levels, 40), now).single().level)
    }

    @Test fun oldAndResetDoNotCount() {
        assertTrue(Clarity.records(log(levels, 5, now.minusDays(40)), tally(levels, 5), now).isEmpty())
        val reset = Clarity.reset(log(levels, 5), now.minusMinutes(5))
        assertTrue(Clarity.shorter(reset, tally(levels, 5), now).isEmpty())
        // Counted again only after the reset.
        val again = (1..3).fold(reset) { l, i -> Clarity.heard(l, levels, now.minusMinutes(i.toLong())) }
        assertTrue(Clarity.records(again, emptyMap(), now).single().unclear == 3)
        // Never more than kept.
        assertEquals(Clarity.KEEP, log(levels, Clarity.KEEP + 20, now.minusMinutes(1)).events.size)
    }

    @Test fun answersItsQuestions() {
        for (s in listOf("which answers do you keep short", "which of your answers do you keep shorter?", "which answers have you shortened",
            "which answers were unclear", "why are your answers so short now", "kaun se jawab chhote karte ho"))
            assertEquals(Clarity.Request.WHICH, Clarity.asked(s), s)
        for (s in listOf("say your answers in full again", "don't shorten your answers", "no need to shorten my answers anymore",
            "forget which answers i found unclear"))
            assertEquals(Clarity.Request.RESET, Clarity.asked(s), s)
        for (s in listOf("full answers", "tell me more", "what are the levels", "which alerts do you hold back", "say everything again"))
            assertNull(Clarity.asked(s), s)
        // Its words never act, nor are they said with an action.
        for (s in listOf("which answers do you keep short", "say your answers in full again", "don't shorten your answers")) {
            assertNull(Commands.parse(s), s)
            assertNull(Ask.parse(s).order, s)
            assertNull(Ask.parse(s).command, s)
            assertFalse(Bundle.acts(s), s)
        }
    }

    @Test fun saysWhatItKeepsShortAndOnlyTheVoice() {
        val said = Clarity.say(log(levels, 3), tally(levels, 8), now)
        assertTrue(said.contains("the levels"), said)
        assertTrue(said.contains("two sentences aloud"), said)
        assertTrue(said.contains("Boss"), said)
        assertTrue(said.contains(Clarity.UNDO), said)
        assertTrue(Clarity.say(Clarity.Log(), emptyMap(), now).startsWith("I say every answer as usual, Boss"))
        assertTrue(Clarity.sayReset(log(levels, 3), tally(levels, 8), now).startsWith("Done, Boss"))
    }

    @Test fun inTheLedgerWithItsUndo() {
        val i = Learnings.Inputs(tally = tally(levels, 8), clarity = log(levels, 3))
        val item = Learnings.items(i, now).single { it.area == Learnings.Area.CLARITY }
        assertEquals(Clarity.UNDO, item.undo)
        assertEquals(today.minusDays(1), item.on)
        assertTrue(Learnings.say(Learnings.items(i, now), Learnings.Ask.WEEK, today, locked = true).contains("Answers I keep shorter aloud"))
        // Undoing the week says it is undone.
        val u = Learnings.undo(i, now)
        assertEquals(1, u.clarity.size)
        assertFalse(u.empty)
        assertTrue(Learnings.done(u).contains("said as usual again"))
    }
}
