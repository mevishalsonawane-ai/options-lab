package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HonestStarsTest {
    private val now: LocalDateTime = LocalDate.of(2026, 10, 5).atTime(15, 0)

    /** [n] scored ideas at [stars], [worked] of them working, one a day back from yesterday. */
    private fun ideas(stars: Int, worked: Int, n: Int, from: Int = 1): List<HonestStars.Scored> =
        (0 until n).map { HonestStars.Scored(now.minusDays((from + it).toLong()), stars, it < worked) }

    @Test fun onlyScoredIdeasThatKnewTheirScoreCount() {
        val at = now.minusDays(1)
        fun s(points: Double?, stars: Int?) = JarvisTrades.Suggestion(at, Market.NIFTY, true, 24500.0, "news: x", "rejected", points, 75, stars = stars)
        val got = HonestStars.of(listOf(s(12.0, 4), s(-3.0, 4), s(null, 4), s(5.0, null), s(Double.NaN, 5), s(1.0, 9)))
        assertEquals(listOf(true, false), got.map { it.worked })
        assertTrue(got.all { it.stars == 4 })
    }

    @Test fun aSureScoreThatHasNotHeldUpIsSaidWithItsRecord() {
        val scored = ideas(4, 4, 10) + ideas(5, 7, 10) + ideas(3, 5, 10)
        val honest = HonestStars.honest(scored, now)
        assertEquals(listOf(4), honest.map { it.stars })
        assertEquals("Confidence 4 out of 5 - but my 4 out of 5 ideas have been about a coin toss: 4 of the last 10 worked.", HonestStars.aloud(4, honest))
        // A score that holds up, and one not in the list, are said plainly - the score itself never changes.
        assertEquals("Confidence 5 out of 5.", HonestStars.aloud(5, honest))
        assertEquals("Confidence 2 out of 5.", HonestStars.aloud(2, honest))
    }

    @Test fun aMiddlingScoreOnlyWhenItMostlyFailedAndLowScoresNever() {
        assertTrue(HonestStars.honest(ideas(3, 4, 10), now).isEmpty())
        val three = HonestStars.honest(ideas(3, 3, 10), now).single()
        assertEquals("Confidence 3 out of 5 - but my 3 out of 5 ideas have not held up: 3 of the last 10 worked.", HonestStars.aloud(3, listOf(three)))
        assertTrue(HonestStars.honest(ideas(2, 0, 12) + ideas(1, 0, 12), now).isEmpty())
    }

    @Test fun tooFewOldOrBeforeTheResetNeverCount() {
        assertTrue(HonestStars.honest(ideas(5, 1, HonestStars.MIN - 1), now).isEmpty())
        assertTrue(HonestStars.honest(ideas(5, 1, 10, from = 61), now).isEmpty())
        assertTrue(HonestStars.honest(ideas(5, 1, 10), now, resetAt = now.minusHours(1)).isEmpty())
        // Only the newest RECENT of a score: an old poor run is outgrown.
        val r = HonestStars.records(ideas(4, 20, 20) + ideas(4, 0, 10, from = 21), now).single()
        assertEquals(HonestStars.RECENT, r.n); assertEquals(20, r.worked); assertFalse(r.honest)
    }

    @Test fun askedAndUndone() {
        listOf("how honest are your confidence scores", "are your confidence stars reliable", "can i trust your confidence",
            "does your 4 out of 5 mean anything", "how often does your five out of five work", "Jarvis, is your confidence any good?",
            "tumhara confidence kitna sahi hai").forEach { assertEquals(HonestStars.Request.WHICH, HonestStars.asked(it), it) }
        listOf("say your confidence plainly", "just say the confidence", "don't add your record to the confidence",
            "don't qualify your confidence scores", "confidence seedha bolo").forEach { assertEquals(HonestStars.Request.RESET, HonestStars.asked(it), it) }
        listOf("how confident are you", "what is your confidence on this trade", "are your confidence words calibrated", "buy 1 lot of nifty",
            "how are your trades doing", "where are you weakest", "say your confidence words as written").forEach { assertNull(HonestStars.asked(it), it) }
        assertTrue(HonestStars.asked("say your confidence plainly")!!.let { Ask.parse("say your confidence plainly").order == null })
    }

    @Test fun theAnswerTellsTheRecordAndNeverActs() {
        val scored = ideas(4, 3, 10) + ideas(5, 2, 4)
        val said = HonestStars.say(scored, now)
        assertTrue("4 out of 5: 3 of the last 10 worked" in said, said)
        assertTrue("Too few yet at 5 out of 5" in said, said)
        assertTrue(HonestStars.UNDO in said && "never changes whether I ask, act" in said, said)
        assertTrue("Rs" !in said && "₹" !in said)
        assertTrue(HonestStars.say(emptyList(), now).startsWith("None of my scored trade ideas"))
        assertTrue(HonestStars.sayReset(scored, now).startsWith("Done, Boss"))
        assertTrue(HonestStars.sayReset(emptyList(), now).startsWith("I already say my confidence plainly"))
    }

    @Test fun inTheLedgerWithItsUndoAndResetByTheWeek() {
        val i = Learnings.Inputs(stars = ideas(4, 3, 10))
        val item = Learnings.items(i, now).single { it.area == Learnings.Area.STARS }
        assertEquals(HonestStars.UNDO, item.undo)
        assertTrue(item.personal)
        val u = Learnings.undo(i, now)
        assertEquals(listOf(4), u.stars.map { it.stars })
        assertTrue("said plainly again" in Learnings.offer(u))
        // After the reset nothing is left to undo, and the ledger has no such line.
        val after = i.copy(starsReset = now)
        assertTrue(Learnings.undo(after, now).empty)
        assertTrue(Learnings.items(after, now).none { it.area == Learnings.Area.STARS })
        // Locked: not said.
        assertTrue("4 out of 5" !in Learnings.say(Learnings.items(i, now), Learnings.Ask.ALL, now.toLocalDate(), locked = true))
    }
}
