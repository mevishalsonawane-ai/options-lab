package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Follow-ups, two questions in one breath and the recognizer's fillers (2026-10-05) - and no action widened. */
class UnderstandTest {
    private fun acts(s: String) = FollowUp.acts(s)

    // ---- Follow-ups that refer back ----

    @Test fun hinglishFollowUpsAskTheSameOfAnotherMarket() {
        assertEquals("how is Sensex", FollowUp.resolve("how is nifty", "aur sensex?"))
        assertEquals("how is Sensex", FollowUp.resolve("how is nifty", "or sensex"), "the recognizer's \"or\" for \"aur\"")
        assertEquals("how is BankNifty", FollowUp.resolve("how is nifty", "aur banknifty ka?"))
        assertEquals("how is BankNifty", FollowUp.resolve("how is nifty", "aur bank nifty ka kya"))
        assertEquals("how is BankNifty", FollowUp.resolve("how is nifty", "banknifty ka kya?"))
        assertEquals("how is FinNifty", FollowUp.resolve("how is nifty", "fin nifty mein?"))
        assertEquals("how is BankNifty", FollowUp.resolve("how is nifty", "and banknifty too"))
        assertEquals("BankNifty kaisa hai", FollowUp.resolve("nifty kaisa hai", "aur banknifty?"))
        assertEquals("What are the levels on Sensex", FollowUp.resolve("What are the levels on Nifty", "same with sensex"))
        assertEquals("why is Nifty moving today", FollowUp.resolve("how is nifty", "kyun?"))
        assertEquals("why is BankNifty moving today", FollowUp.resolve("banknifty kya chal raha hai", "aisa kyun hua"))
    }

    @Test fun aHindiTailAloneNeedsAMarket() {
        assertNull(FollowUp.resolve("how is nifty", "boss ka kya"))
        assertNull(FollowUp.resolve("how is nifty", "mera ka kya"))
        assertNull(FollowUp.resolve("how is nifty", "aur kya?"))
    }

    @Test fun whatComesNextIsTheTrendOfTheMarketJustAsked() {
        assertEquals("where is Nifty heading next", FollowUp.resolve("how is nifty", "uske baad?"))
        assertEquals("where is BankNifty heading next", FollowUp.resolve("banknifty kaisa hai", "phir kya"))
        assertEquals("where is Nifty heading next", FollowUp.resolve("how is nifty", "then what?"))
        assertEquals(setOf(Topic.TREND), Ask.parse("where is Nifty heading next").topics)
        assertNull(FollowUp.resolve("show my positions", "uske baad?"), "nothing named before")
        assertNull(FollowUp.resolve("stop all strategies", "uske baad?"), "a command is never followed")
    }

    @Test fun anotherDayOfTheSameQuestion() {
        val y = FollowUp.resolve("how is nifty doing", "what about yesterday?")
        assertEquals("what was yesterday's high, low and close on Nifty", y)
        assertTrue(Lookback.prevAsked(y!!))
        assertEquals(listOf(Market.NIFTY), Ask.parse(y).markets)
        assertEquals("how did BankNifty do last week", FollowUp.resolve("how is banknifty", "and last week?"))
        assertEquals(PeriodMove.Span.LAST_WEEK, PeriodMove.asked("how did BankNifty do last week"))
        assertEquals("how did BankNifty do last week", FollowUp.resolve("how is banknifty", "aur pichle hafte?"))
        assertEquals(PeriodMove.Span.WEEK, PeriodMove.asked(FollowUp.resolve("how is nifty", "what about this week")!!))
        assertEquals(PeriodMove.Span.MONTH, PeriodMove.asked(FollowUp.resolve("how is nifty", "and this month")!!))
        // Boss's own P&L asked of another day stays Boss's own.
        val p = FollowUp.resolve("what is my p&l today", "and yesterday?")
        assertEquals("what was my p&l yesterday", p)
        assertEquals(setOf(Topic.ACCOUNT), Ask.parse(p!!).topics)
        assertEquals(setOf(Topic.ACCOUNT), Ask.parse(FollowUp.resolve("aaj kitna kamaya", "what about last week")!!).topics)
        // Nothing is known of tomorrow; nothing named before, nothing carried.
        assertNull(FollowUp.resolve("how is nifty doing", "and tomorrow?"))
        assertNull(FollowUp.resolve("how is the market", "what about yesterday"))
        assertNull(FollowUp.resolve("stop all strategies", "and yesterday?"))
    }

    @Test fun fillersAroundAFollowUpAreLeftOut() {
        assertEquals("how is BankNifty", FollowUp.resolve("how is nifty", "umm, and banknifty?"))
        assertEquals("why is Nifty moving today", FollowUp.resolve("how is nifty", "uh why?"))
    }

    @Test fun followUpWordsNeverAct() {
        for (now in listOf("aur banknifty band karo", "and stop banknifty", "aur sensex ka order lagao", "banknifty bhi band karo",
            "uske baad stop all", "and then close everything", "and yesterday close my position", "umm and close banknifty",
            "aur banknifty khareedo", "or kill switch on"))
            assertNull(FollowUp.resolve("how is nifty", now), now)
        // What a follow-up gives is always a question.
        for (now in listOf("aur sensex?", "banknifty ka kya", "uske baad?", "what about yesterday", "and last week", "kyun?"))
            FollowUp.resolve("how is nifty", now)?.let { assertFalse(acts(it), it) }
    }

    // ---- Fillers, repeats and false starts ----

    @Test fun fillersAreLeftOut() {
        assertEquals("how is nifty", Filler.clean("umm how is nifty"))
        assertEquals("how is nifty?", Filler.clean("uh, how is, umm, nifty?"))
        assertEquals("what's my p&l", Filler.clean("so, you know, what's my p&l"))
        assertEquals("how is banknifty", Filler.clean("hmm well how is banknifty"))
        assertEquals("nifty kaisa hai", Filler.clean("arre nifty kaisa hai"))
        assertEquals("how is nifty", Filler.clean("how how is nifty"))
        assertEquals("how is nifty", Filler.clean("how is how is nifty"))
        assertEquals("what is the trend", Filler.clean("what is the the the trend"))
        assertEquals("how is bank nifty", Filler.clean("how is bank nifty bank nifty"))
    }

    @Test fun wordsAlreadyCleanAreReturnedExactly() {
        for (s in listOf("How is Nifty?", "what's my P&L today", "Nifty at 10 10", "what is the vix", "hammer on nifty", "umbrella", "her positions",
            "ahead of the open", "doji matlab kya"))
            assertEquals(s, Filler.clean(s), s)
    }

    @Test fun aFalseStartIsReadAsItWasPutRight() {
        assertEquals("how is BankNifty", Filler.clean("how is nifty, I mean bank nifty"))
        assertEquals("BankNifty kaisa hai", Filler.clean("nifty kaisa hai matlab banknifty"))
        assertEquals("how is Sensex today", Filler.clean("how is nifty today, sorry, sensex"))
        assertEquals("what are the levels on banknifty", Filler.clean("what's the trend, I mean, what are the levels on banknifty"))
        assertEquals("what's my p&l", Filler.clean("how is nifty no wait what's my p&l"))
    }

    // ---- Two questions in one breath ----

    @Test fun twoQuestionsAreBothAnswered() {
        val two = Understand.questions(null, "how is nifty and what's my p&l")!!
        assertEquals(listOf("how is nifty", "what's my p&l"), two)
        assertEquals(listOf(Market.NIFTY), Ask.parse(two[0]).markets)
        assertEquals(setOf(Topic.ACCOUNT), Ask.parse(two[1]).topics)
        // (News asked after a market is that market's news.)
        assertEquals(listOf("how is nifty", "any news on Nifty"), Understand.questions(null, "how is nifty? any news?"))
        assertEquals(listOf("how is nifty", "what's my p&l"), Understand.questions(null, "how is nifty; what's my p&l"))
        assertEquals(listOf("how is nifty", "how is banknifty", "what is the vix"), Understand.questions(null, "how is nifty, how is banknifty and what is the vix"))
        assertEquals(listOf("nifty kaisa hai", "mera p&l kitna hai"), Understand.questions(null, "nifty kaisa hai aur mera p&l kitna hai"))
        assertEquals(setOf(Topic.ACCOUNT), Ask.parse("mera p&l kitna hai").topics)
        assertEquals(listOf("how is nifty", "positions dikhao"), Understand.questions(null, "how is nifty and positions dikhao"))
    }

    @Test fun aLaterPartIsReadWithThePartBeforeIt() {
        assertEquals(listOf("how is nifty", "how is BankNifty"), Understand.questions(null, "how is nifty and what about banknifty"))
        assertEquals(listOf("how is banknifty", "is it going up on BankNifty"), Understand.questions(null, "how is banknifty and is it going up"))
        assertEquals(listOf("what are the levels on BankNifty", "what's my p&l"),
            Understand.questions("how is banknifty doing", "what are the levels and what's my p&l"))
    }

    @Test fun oneQuestionIsNotSplit() {
        for (s in listOf("how are nifty and banknifty", "what is support and resistance on nifty", "what's the high and low of nifty",
            "nifty aur banknifty kaise hain", "levels on nifty, banknifty and finnifty"))
            assertNull(Compound.split(s), s)
        assertNull(Understand.questions(null, "how is nifty"), "nothing to change")
        assertNull(Understand.questions(null, "How is Nifty?"))
        assertNull(Understand.questions(null, "hello and how is nifty"), "a greeting is not a question to split off")
        assertNull(Understand.questions(null, "how is nifty and who won the cricket match"), "an off-topic part is not split off")
    }

    @Test fun fillersAloneOrAroundOneQuestion() {
        assertEquals(listOf("how is nifty"), Understand.questions(null, "umm how how is nifty"))
        assertEquals(listOf("how is BankNifty"), Understand.questions(null, "how is nifty, I mean banknifty"))
        assertEquals(listOf("how is Sensex"), Understand.questions("how is nifty", "umm aur sensex?"))
        assertNull(Understand.questions(null, "umm"))
        assertNull(Understand.questions(null, "   "))
    }

    // ---- Nothing that acts is widened ----

    /** Words that act, as heard: given back unchanged (null), never cleaned or split. */
    private val ACTING = listOf(
        "stop all strategies and what's my p&l", "how is nifty and stop all strategies", "what's my p&l and close all positions",
        "how is nifty and cancel all orders", "how is nifty and switch to live", "what's my p&l and kill switch on",
        "how is nifty and set an alarm at 25000", "how is nifty and mute", "how is nifty and buy 2 lots nifty 25000 ce",
        "how is nifty? sell 1 lot banknifty 52000 pe", "how is nifty, start orb", "nifty kaisa hai aur strategy 1 band karo",
        "how is nifty and square off everything", "how is nifty and turn on autopilot", "how is nifty and remind me at 3",
        "umm stop all strategies", "uh, switch to live", "start start all strategies", "how is nifty I mean stop all strategies",
        "stop, I mean, how is nifty", "umm kill switch off", "how is nifty and what are my open orders", "how is nifty and is live mode on",
        "how is nifty and pause the orb arm", "how is nifty, exit everything", "how is nifty aur banknifty khareedo",
    )

    @Test fun nothingThatActsIsCleanedOrSplit() {
        for (s in ACTING) {
            assertNull(Compound.split(s), s)
            val u = Understand.questions(null, s)
            assertNull(u, "$s -> $u")
            // With a question before it too (only the old follow-up reading, which never acts).
            Understand.questions("how is nifty", s)?.forEach { assertFalse(acts(it), "$s -> $it") }
        }
    }

    @Test fun cleaningNeverMakesACommand() {
        // Fillers cleaned out of a command never make it act more than as heard: such words go on unchanged.
        for (s in listOf("umm stop all strategies", "uh close all positions", "stop stop all strategies", "hmm cancel all orders",
            "switch to umm live", "kill kill switch off", "so, start all strategies", "start, I mean, stop all"))
            assertNull(Understand.questions(null, s), s)
        // Every question given back, for every phrasing here, is a question only.
        for (s in listOf("how is nifty and what's my p&l", "umm how is nifty", "how is nifty, I mean banknifty", "how is nifty? any news?",
            "nifty kaisa hai aur mera p&l kitna hai", "how is nifty and what about banknifty"))
            Understand.questions(null, s)!!.forEach { q ->
                assertFalse(acts(q), q)
                assertNull(Commands.parse(q), q)
                assertNull(Ask.parse(q).order, q)
            }
    }

    @Test fun actionWordsAreSeenInHinglishToo() {
        for (s in listOf("nifty kaisa hai aur sab band karo", "how is nifty aur strategy chalu karo", "p&l kitna hai aur order lagao",
            "how is nifty aur alarm laga do"))
            assertNull(Understand.questions(null, s), s)
    }
}

class YouKnowTest {
    @kotlin.test.Test fun youKnowIsFillerOnlyWhenSetApart() {
        kotlin.test.assertEquals("what do you know about me", Filler.clean("what do you know about me"))
        kotlin.test.assertEquals("how well do you know me", Filler.clean("how well do you know me"))
        kotlin.test.assertEquals("do you know the nifty levels", Filler.clean("do you know the nifty levels"))
        kotlin.test.assertTrue(!Filler.clean("nifty, you know, is it up").contains("you know"))
    }
}

class BundleTest {
    @kotlin.test.Test fun aQuestionWithAnActionIsBundled() {
        for (s in listOf("where is the most call writing, then exit all", "how has OI shifted since morning then square off everything",
                "what is the vwap of nifty then exit all", "why were you quiet at 11 and then kill switch on",
                "help me journal today then exit all", "help me journal today and then stop all arms"))
            kotlin.test.assertTrue(Bundle.acts(s), s)
        for (s in listOf("where is the most call writing", "what is the vwap of nifty", "why were you quiet at 11",
                "help me journal today", "how are nifty and banknifty", "what's the structure today"))
            kotlin.test.assertTrue(!Bundle.acts(s), s)
    }
}
