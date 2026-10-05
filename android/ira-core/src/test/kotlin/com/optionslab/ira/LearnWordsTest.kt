package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Learning Boss's words safely: proposed, kept only on yes, never anything that acts, expiring, listed and forgotten. */
class LearnWordsTest {
    private val today = LocalDate.of(2026, 10, 5)

    /** Words that act in every way Jarvis knows: commands, orders, alarms, reminders, notes, goals, settings. */
    private val acting = listOf(
        "stop all strategies", "start the orb arm", "switch to live mode", "turn the kill switch on", "exit everything",
        "buy 1 lot of nifty 24000 call", "sell 2 lots banknifty 52000 pe", "square off my positions",
        "set an alarm for nifty at 25000", "alert me when banknifty crosses 52000", "remind me at 3 pm to book profit",
        "cancel my reminders", "remember that I stop trading after two losses", "forget what I told you",
        "goal: keep my weekly loss under 5000", "clear my goals", "set my daily loss limit to 3000",
        "do it automatically", "forget what you learned", "forget the word street", "mute yourself for 30 minutes", "be quiet", "unmute",
    )

    @Test fun aRephraseAfterAMissIsProposedNotKept() {
        val l = assertNotNull(Corrections.propose("how s the street looking", "how is nifty doing", missed = true))
        assertEquals("how s the street looking", l.wrong)
        assertEquals("how is nifty doing", l.right)
        val ask = Corrections.offer(l)
        assertTrue(ask.startsWith("Shall I take \"how s the street looking\" to mean \"how is nifty doing\" from now on, Boss?"), ask)
        assertTrue(Corrections.safe(l))
        assertTrue(Corrections.kept(l).contains("Boss"))
        // Already learned so: not asked again.
        assertNull(Corrections.propose("how s the street looking", "how is nifty doing", missed = true, learned = listOf(l)))
    }

    @Test fun aRephraseAfterAWrongAnswerIsProposed() {
        val l = assertNotNull(Corrections.propose("how is the nifdee boi doing", "how is nifty doing", missed = false))
        assertEquals("how is nifty doing", l.right)
    }

    @Test fun onlyAQuestionIsProposed() {
        // Small talk, help, or words not understood are never a meaning.
        assertNull(Corrections.propose("how s the street looking", "how are you", missed = true))
        assertNull(Corrections.propose("how s the street looking", "thanks", missed = true))
        assertNull(Corrections.propose("how s the street looking", "zorbo flim", missed = true))
        assertNull(Corrections.propose("how s the street looking", "how s the street looking", missed = true))
    }

    @Test fun nothingThatActsIsEverProposedEitherWay() {
        for (a in acting) {
            assertTrue(Corrections.acts(a), "acts: $a")
            assertNull(Corrections.propose("how s the street looking", a, missed = true), "meaning: $a")
            assertNull(Corrections.propose("how s the street looking", a, missed = false), "meaning: $a")
            assertNull(Corrections.propose(a, "how is nifty doing", missed = true), "original: $a")
            assertNull(Corrections.propose(a, "how is nifty doing", missed = false), "original: $a")
            assertNull(Corrections.learn(a, "how is nifty doing"), "learn original: $a")
            assertNull(Corrections.learn("blah blah", a), "learn meaning: $a")
        }
    }

    @Test fun nothingLearnedCanActEvenIfKeptByHand() {
        // A store tampered with (or kept before the checks grew): an acting meaning is never used, nor kept.
        for (a in acting) {
            val bad = Corrections.Learned("how s the street looking", Corrections.normalize(a), today)
            assertFalse(Corrections.safe(bad), "safe: $a")
            assertNull(Corrections.apply("how s the street looking", listOf(bad)), "applied: $a")
            assertNull(Corrections.match("how s the street looking", listOf(bad)), "matched: $a")
            // And acting words said are never rewritten into anything, whatever was learned.
            val trap = Corrections.Learned(Corrections.normalize(a), "how is nifty doing", today)
            assertNull(Corrections.apply(a, listOf(trap)), "rewritten: $a")
        }
    }

    @Test fun whatIsUsedIsAlwaysAQuestionThatDoesNotAct() {
        val good = Corrections.Learned("how s the street looking", "how is nifty doing", today)
        val out = assertNotNull(Corrections.apply("How's the street looking?", listOf(good)))
        assertTrue(Corrections.understood(out) && !Corrections.acts(out))
        assertEquals(good, Corrections.match("how's the street looking", listOf(good)))
    }

    @Test fun unusedWordingsExpireAfterSixtyDays() {
        val used = Corrections.Learned("a b", "how is nifty doing", today.minusDays(60))
        val old = Corrections.Learned("c d", "how is banknifty doing", today.minusDays(61))
        val legacy = Corrections.Learned("e f", "what is the trend on nifty")
        assertEquals(listOf(used, legacy), Corrections.fresh(listOf(used, old, legacy), today))
        // Using one keeps it another 60 days.
        val touched = Corrections.touch(listOf(used, old), old, today)
        assertEquals(today, touched[1].used)
        assertEquals(listOf(touched[1]), Corrections.fresh(touched, today.plusDays(1)))
        assertEquals(listOf(touched[1]), Corrections.fresh(touched, today.plusDays(60)))
        assertTrue(Corrections.fresh(touched, today.plusDays(61)).isEmpty())
    }

    @Test fun wordsAreListed() {
        for (s in listOf("what words have you learned?", "Jarvis, which words have you learnt", "show me the words you learned",
            "what wordings do you know", "learned words")) assertTrue(Corrections.wordsAsked(s), s)
        assertFalse(Corrections.wordsAsked("what is nifty doing"))
        assertTrue(Corrections.words(emptyList(), today).startsWith("I haven't learned any of your wordings yet, Boss."))
        val ls = listOf(Corrections.Learned("a b", "how is nifty doing", today.minusDays(3)),
            Corrections.Learned("c d", "how is banknifty doing", today), Corrections.Learned("x y", "how is gold", today.minusDays(90)))
        val said = Corrections.words(ls, today)
        assertTrue(said.startsWith("Boss, I've learned 2 of your wordings"), said)
        assertTrue(said.contains("\"c d\" means \"how is banknifty doing\" (used today)"), said)
        assertTrue(said.contains("(used 3 days ago)"), said)
        assertFalse(said.contains("x y"), "an expired wording is not listed")
        assertTrue(said.indexOf("c d") < said.indexOf("a b"), "newest first")
        // The asks themselves are never learned or rewritten.
        assertNull(Corrections.learn("zorbo flim", "what words have you learned"))
        assertNull(Corrections.apply("what words have you learned", listOf(Corrections.Learned("what words have you learned", "how is nifty doing"))))
    }

    @Test fun aWordIsForgotten() {
        assertEquals("street", Corrections.forgetWordAsked("Jarvis, forget the word street"))
        assertEquals("how s the street looking", Corrections.forgetWordAsked("forget the wording \"how's the street looking\""))
        assertNull(Corrections.forgetWordAsked("forget what you learned"))
        assertNull(Corrections.forgetWordAsked("what is the word on the street"))
        val a = Corrections.Learned("how s the street looking", "how is nifty doing", today)
        val b = Corrections.Learned("nifdee boi", "how is nifty doing", today)
        val (kept, gone) = Corrections.forget(listOf(a, b), "street")
        assertEquals(listOf(b), kept); assertEquals(listOf(a), gone)
        assertEquals(listOf(a), Corrections.forget(listOf(a, b), "nifdee boy").first)
        assertTrue(Corrections.forgot(gone, "street").startsWith("Done, Boss: \"how s the street looking\" no longer means"))
        assertTrue(Corrections.forgot(emptyList(), "zebra").contains("Boss"))
        assertEquals(listOf(a, b), Corrections.forget(listOf(a, b), "zebra").first)
        // Forgetting is never learned as a meaning, nor read through a learned wording.
        assertTrue(Corrections.acts("forget the word street"))
    }

    @Test fun theWindowIsAboutAMinute() {
        assertEquals(60_000L, Corrections.REPHRASE_MS)
        assertEquals(60L, Corrections.EXPIRE_DAYS)
    }
}
