package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Jarvis's questions on the app's own automation, Boss's habits and the study pages ([SelfTalk]). */
class SelfTalkTest {
    private fun kind(s: String) = SelfTalk.asked(s)?.kind

    @Test fun theTimelineAndUndoEverythingAreAskedInPlainWords() {
        for (s in listOf("What changed today across the app?", "Jarvis, what changed today in the app", "show today's changes", "all automatic changes today",
            "what did the agents change today")) assertEquals(SelfTalk.Kind.TIMELINE, kind(s), s)
        for (s in listOf("Undo everything today", "undo all changes today", "undo all the automatic changes", "undo today's changes", "roll back everything today"))
            assertEquals(SelfTalk.Kind.UNDO_ALL, kind(s), s)
    }

    @Test fun theFreezeTheScorecardTheLessonAndHisHabitsAreTheirOwn() {
        assertEquals(SelfTalk.Kind.FREEZE, kind("Is learning frozen?"))
        assertEquals(SelfTalk.Kind.FREEZE, kind("why is learning frozen"))
        assertEquals(SelfTalk.Kind.SCORECARD, kind("How did the automation do last month?"))
        assertEquals(SelfTalk.Kind.SCORECARD, kind("show the autopilot scorecard"))
        assertEquals(SelfTalk.Kind.LESSON, kind("What's today's lesson?"))
        assertEquals(SelfTalk.Kind.HABITS, kind("What are my trading habits?"))
        assertEquals(SelfTalk.Kind.RULES, kind("Which of my rules work?"))
    }

    @Test fun aStudyPageNeedsATopicAndAReplayNamesItsStrategy() {
        assertEquals(SelfTalk.Ask(SelfTalk.Kind.STUDY, "vwap"), SelfTalk.asked("Teach me about VWAP"))
        assertEquals(SelfTalk.Ask(SelfTalk.Kind.STUDY, "the value area"), SelfTalk.asked("explain the value area"))
        // "Explain" alone is not a study request unless it names a study topic.
        assertNull(SelfTalk.asked("explain today's move"))
        assertEquals(SelfTalk.Ask(SelfTalk.Kind.REPLAY, "liquidity"), SelfTalk.asked("Replay my last Liquidity trade"))
        assertEquals(SelfTalk.Ask(SelfTalk.Kind.REPLAY, null), SelfTalk.asked("replay my last bot trade"))
        // Boss's own last trade is his replay ([TradeReplay]), never the newest bot trade (review R5 #5).
        for (s in listOf("replay my last trade", "go over my last trade", "walk me through my last trade", "go over my latest losing trade"))
            assertNull(SelfTalk.asked(s), s)
        assertEquals(SelfTalk.Ask(SelfTalk.Kind.REPLAY, "orb"), SelfTalk.asked("go over the last orb trade"))
    }

    @Test fun ordinaryQuestionsAndOrdersAreNotTaken() {
        for (s in listOf("what is vix", "what did you change today", "undo your last change", "buy 1 lot nifty 25000 ce", "exit all", "how is nifty",
            "what is my pnl today", "turn off autopilot learning", "explain my liquidity trade", "explain theta", "explain my position",
            "teach me about my trades today", "what are my rules", "todays lessons")) assertNull(SelfTalk.asked(s), s)
    }

    @Test fun undoEverythingIsNeverReadAsAnOrderOrACommand() {
        for (s in listOf("undo everything today", "what changed today across the app", "teach me about vwap", "replay my last liquidity trade")) {
            val p = Ask.parse(s)
            assertNull(p.order, s)
            // "Replay ..." reads as the read-only practice command too (so "said with something to do"): the hub lets a replay
            // of a bot trade through on exactly that command, never on any other.
            if (SelfTalk.asked(s)?.kind == SelfTalk.Kind.REPLAY) assertTrue(p.command == null || p.command?.kind == Command.Kind.PRACTICE, s)
            else { assertNull(p.command, s); assertTrue(!Bundle.acts(s), s) }
        }
    }
}
