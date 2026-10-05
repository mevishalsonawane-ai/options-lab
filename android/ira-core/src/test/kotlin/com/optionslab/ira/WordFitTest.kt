package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordFitTest {
    private val now = LocalDateTime.of(2026, 10, 5, 11, 0)

    @Test fun `a word the number does not bear out is set right, the figures stay`() {
        val r = WordFit.check("The hammer usually went its way: 4 of the last 12 times on this phone. Watch 24,300.")
        assertEquals("The hammer sometimes went its way: 4 of the last 12 times on this phone. Watch 24,300.", r.text)
        assertEquals(1, r.fits.size)
        assertEquals(WordFit.Word.USUALLY, r.fits[0].word)
        assertEquals(WordFit.Word.SOMETIMES, r.fits[0].to)
    }

    @Test fun `a fitting word is left alone and noted as fitting`() {
        val r = WordFit.check("It usually holds: 9 of the last 12 sessions.")
        assertEquals("It usually holds: 9 of the last 12 sessions.", r.text)
        assertTrue(r.fits.single().fits)
    }

    @Test fun `each word to its range`() {
        fun fix(w: String, k: Int, n: Int) = WordFit.check("It $w went up, $k of the last $n days.").text.removePrefix("It ").substringBefore(" went")
        assertEquals("almost always", fix("rarely", 11, 12))
        assertEquals("never", fix("never", 8, 12))           // absolutes are rules as often as tallies: never re-worded
        assertEquals("often", fix("rarely", 5, 10))
        assertEquals("rarely", fix("often", 1, 12))
        assertEquals("sometimes", fix("sometimes", 0, 10))   // nor written in
        assertEquals("often", fix("often", 10, 10))
        assertEquals("almost always", fix("almost always", 9, 10))
        assertEquals("hardly ever", fix("hardly ever", 1, 10))
    }

    @Test fun `capital kept, phrases read whole, percent read`() {
        assertEquals("Rarely it bounced: 1 of 9.", WordFit.check("Usually it bounced: 1 of 9.").text)
        assertEquals("It went its way sometimes: 3 out of 10.", WordFit.check("It went its way most of the time: 3 out of 10.").text)
        assertEquals("It held often, 45% of the times.", WordFit.check("It held usually, 45% of the times.").text)
        assertEquals("Your red days: rarely, 1 times out of 10.", WordFit.check("Your red days: often, 1 times out of 10.").text)
    }

    @Test fun `ambiguous or not a frequency claim is left alone`() {
        for (s in listOf(
            "Not usually: 2 of the last 10.",                    // negated
            "How often it moved: 2 of the last 10.",              // a question of how often
            "It moved less often than last week, 2 of 10.",       // a comparison
            "By theme, most often first - RBI 4 of the last 9.",  // "most often" orders, not a claim
            "Faint 3 of 10 times (usually 20%).",                // "usually" = on usual days
            "It usually rises: 2 of the last 10 and 3 of 12.",    // two numbers
            "It usually rises and often falls: 2 of 10.",         // two words
            "It usually rises: 1 of 2.",                         // too few
            "It usually rises on Fridays.",                      // no number
            "Usually it bounces. 2 of the last 10 broke.",        // number in another sentence
            "It usually holds - 2 of the last 10 broke.",         // a dash between two facts
            "Nifty moved 0.3% or more within an hour after 4 of the last 9 - timing only.",
        )) assertEquals(s, WordFit.check(s).text, s)
        // A decimal point is not a sentence's end.
        assertEquals("Nifty rarely moved 0.3% within an hour, 1 of the last 9.", WordFit.check("Nifty often moved 0.3% within an hour, 1 of the last 9.").text)
    }

    @Test fun `left as written when asked, still noted`() {
        val s = "It usually held: 2 of the last 10."
        val r = WordFit.check(s, fix = false)
        assertEquals(s, r.text)
        assertEquals(WordFit.Word.SOMETIMES, r.fits.single().to)
    }

    @Test fun `the record counts once an answer and fades`() {
        val f = WordFit.check("It usually held: 2 of the last 10.").fits
        var log = WordFit.noted(WordFit.Log(), f, now)
        log = WordFit.noted(log, f, now.plusMinutes(1))             // the model's rewording of the same answer
        assertEquals(1, log.events.size)
        log = WordFit.noted(log, f, now.plusMinutes(30))
        log = WordFit.noted(log, WordFit.check("It often held: 6 of the last 10.").fits, now.plusMinutes(40))
        val rs = WordFit.records(log, now.plusHours(1))
        assertEquals("usually", rs[0].said)
        assertEquals(2, rs[0].misfit)
        assertEquals(listOf("sometimes"), rs[0].became)
        assertEquals(1, WordFit.misfits(log, now.plusHours(1)).size)
        assertTrue(WordFit.records(log, now.plusDays(WordFit.WINDOW_DAYS + 1)).isEmpty())
        // The ledger shows it, with its undo; off, it says so and offers it back.
        val items = Learnings.items(Learnings.Inputs(wordFit = log), now.plusHours(1)).filter { it.area == Learnings.Area.WORD_FIT }
        assertEquals(1, items.size)
        assertTrue(items[0].text().contains("\"usually\" said as \"sometimes\" twice"), items[0].text())
        assertEquals(WordFit.UNDO, items[0].undo)
        val off = WordFit.switched(log, true, now)
        assertEquals(WordFit.REDO, Learnings.items(Learnings.Inputs(wordFit = off), now.plusHours(1)).single { it.area == Learnings.Area.WORD_FIT }.undo)
        assertFalse(WordFit.noted(off, f, now.plusHours(2)).events.last().fixed)
        assertFalse(WordFit.switched(off, false, now).off)
    }

    @Test fun `asked`() {
        for (s in listOf("how well do your words match your numbers", "do your words match the numbers", "are your confidence words calibrated",
                "how calibrated are you", "which words have you corrected to match your numbers", "what do you mean by usually",
                "when you say often what do you mean", "what does rarely mean", "how often is usually"))
            assertEquals(WordFit.Request.HOW, WordFit.asked(s), s)
        for (s in listOf("say your confidence words as written", "don't correct your words", "stop correcting your confidence words",
                "leave your confidence words as they are"))
            assertEquals(WordFit.Request.OFF, WordFit.asked(s), s)
        for (s in listOf("match your words to the numbers again", "correct your confidence words", "start correcting your words"))
            assertEquals(WordFit.Request.ON, WordFit.asked(s), s)
        for (s in listOf("what?", "what do you mean", "how is nifty", "usually what time do you send the report", "what is max pain"))
            assertNull(WordFit.asked(s), s)
        assertEquals(WordFit.Word.USUALLY, WordFit.wordAsked("how often is usually"))
        assertEquals(WordFit.Word.RARELY, WordFit.wordAsked("what does rarely mean"))
    }

    @Test fun `answers`() {
        val empty = WordFit.say("how well do your words match your numbers", WordFit.Log(), now)
        assertTrue(empty.contains("haven't said"), empty)
        val log = WordFit.noted(WordFit.Log(), WordFit.check("It usually held: 2 of the last 10.").fits, now)
        val s = WordFit.say("how well do your words match your numbers", log, now)
        assertTrue(s.contains("\"usually\" once (the number was 20%, said as \"sometimes\")"), s)
        assertTrue(s.contains(WordFit.UNDO), s)
        val m = WordFit.say("what do you mean by usually", log, now)
        assertTrue(m.startsWith("When I say \"usually\", Boss, I mean 55% of the time or more."), m)
        assertTrue(m.contains("1 set right"), m)
        assertTrue(WordFit.saySwitched(off = true, was = false).startsWith("Done, Boss"))
        assertTrue(WordFit.saySwitched(off = false, was = false).contains("already"))
    }

    @Test fun aRuleOrAnUnrelatedFactIsNeverReworded() {
        for (t in listOf("I never trade live, and 3 of my last 10 calls were right.",
                "Real orders always need your fingerprint, 2 of 9 alerts were said aloud.",
                "Orders usually go through: 2 of 10 were refused.",
                "It always held, but 4 of the last 12 failed."))
            kotlin.test.assertEquals(t, WordFit.check(t).text, t)
        kotlin.test.assertEquals("It sometimes went its way: 4 of the last 12.", WordFit.check("It usually went its way: 4 of the last 12.").text)
    }
}
