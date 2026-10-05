package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfDoubtTest {
    private val today = LocalDate.of(2026, 10, 5)

    private fun wrong(said: String, daysAgo: Long = 1) = Mistakes.Entry(LocalDateTime.of(today.minusDays(daysAgo), java.time.LocalTime.of(11, 0)), said, "an answer")

    /** [n] questions [text] asked, spread over the last days. */
    private fun asked(text: String, n: Int, tally: DoubtTally = emptyMap()): DoubtTally =
        (1..n).fold(tally) { t, i -> SelfDoubt.count(t, today.minusDays(i.toLong() % 20), text) }

    @Test fun tagsReadTopicIndexAndPhrasing() {
        val t = SelfDoubt.tags("what are the levels on banknifty").map { it.key }
        assertEquals(listOf("topic:LEVELS", "market:BANKNIFTY"), t)
        val h = SelfDoubt.tags("nifty kaisa hai").map { it.key }
        assertTrue("market:NIFTY" in h && "phrasing:HINGLISH" in h && "phrasing:SHORT" in h, h.toString())
        assertTrue("phrasing:NO_INDEX" in SelfDoubt.tags("what are the support levels today").map { it.key })
        // Commands and orders are not answers: never judged, never doubted.
        assertEquals(emptyList(), SelfDoubt.tags("stop strategy 1"))
        assertEquals(emptyList(), SelfDoubt.tags("buy 2 lots nifty 25000 ce"))
    }

    @Test fun countKeepsOnlyTheWindow() {
        val old = mapOf(today.minusDays(SelfDoubt.WINDOW_DAYS + 5) to mapOf("topic:LEVELS" to 9))
        val t = SelfDoubt.count(old, today, "levels on nifty")
        assertEquals(setOf(today), t.keys)
        assertEquals(1, t[today]!!["topic:LEVELS"])
        assertEquals(2, SelfDoubt.count(t, today, "nifty levels")[today]!!["topic:LEVELS"])
        // A command adds nothing.
        assertEquals(t, SelfDoubt.count(t, today, "stop strategy 1"))
    }

    @Test fun twoSlipsDecideNothing() {
        val m = listOf(wrong("levels on nifty"), wrong("nifty levels please"))
        assertEquals(SelfDoubt.Level.NORMAL, SelfDoubt.judge("what are the levels on nifty", m, emptyMap(), today).level)
        assertNull(SelfDoubt.say(m, emptyMap(), today))
        assertEquals("the levels on Nifty", SelfDoubt.reading("what are the levels on nifty"))
    }

    @Test fun oftenWrongAddsCheckMe() {
        val m = listOf(wrong("levels on banknifty"), wrong("banknifty support", 3), wrong("resistance for banknifty", 5))
        val tally = asked("what are the levels on banknifty", 12)
        val c = SelfDoubt.judge("banknifty levels now", m, tally, today)
        assertEquals(SelfDoubt.Level.CHECK, c.level)
        assertNull(c.before())
        val s = c.wrap("Support 51,800, resistance 52,400.")
        assertTrue(s.startsWith("Support 51,800, resistance 52,400. Check me on this, Boss - you marked 3 of my"), s)
        // Said once: wrapping again changes nothing.
        assertEquals(s, c.wrap(s))
        // A question of another kind is untouched.
        assertEquals("Up 0.4%.", SelfDoubt.judge("why did gold move", m, tally, today).wrap("Up 0.4%."))
        // Many answers of the kind asked fine: three marks are no longer enough.
        assertEquals(SelfDoubt.Level.NORMAL, SelfDoubt.judge("banknifty levels now", m, asked("levels on banknifty", 40), today).level)
    }

    @Test fun clearlyWeakAsksHowItWasRead() {
        val m = (1..5L).map { wrong("nifty kaisa hai", it) }
        val c = SelfDoubt.judge("aaj nifty kaisa hai", m, emptyMap(), today)
        assertEquals(SelfDoubt.Level.ASK, c.level)
        val s = c.wrap("Nifty is at 25,100.")
        assertTrue(s.startsWith("Did you mean how the market is doing on Nifty, Boss? Taking it that way. Nifty is at 25,100. Check me on this, Boss"), s)
        // Never for anything that would act.
        assertEquals(SelfDoubt.Level.NORMAL, SelfDoubt.judge("stop strategy 1", m + (1..5L).map { wrong("stop strategy 1", it) }, emptyMap(), today).level)
    }

    @Test fun oldMistakesAgeOut() {
        val m = (1..5L).map { wrong("levels on nifty", SelfDoubt.WINDOW_DAYS + it) }
        assertEquals(SelfDoubt.Level.NORMAL, SelfDoubt.judge("levels on nifty", m, emptyMap(), today).level)
    }

    @Test fun reviewSaysWhatChanged() {
        val m = listOf(wrong("levels on banknifty"), wrong("banknifty support", 3), wrong("resistance for banknifty", 5))
        val tally = asked("what are the levels on banknifty", 12)
        val r = SelfDoubt.review(m, tally, today, emptySet())
        assertEquals(1, r.size, r.toString())
        assertTrue(r[0].endsWith("this month, so I end those with \"check me on this\""), r[0])
        assertEquals(setOf("topic:LEVELS", "market:BANKNIFTY", "phrasing:SHORT"), SelfDoubt.weakKeys(m, tally, today))
        // Yesterday "levels" was doubted; today nothing is: said as recovered.
        val back = SelfDoubt.review(emptyList(), tally, today, setOf("topic:LEVELS"))
        assertEquals(listOf("I no longer ask you to check my answers on the levels - fewer marked wrong lately"), back)
        assertTrue(SelfDoubt.say(m, tally, today)!!.startsWith("In my answers, Boss: you marked 3 of my"))
        // It reaches the evening review.
        val s = SelfReview.say(SelfReview.Facts(3, 3, emptyList(), emptyList(), emptyList(), null, emptyList(), emptyList(), doubts = r))!!
        assertTrue(s.contains("check me on this"), s)
    }
}
