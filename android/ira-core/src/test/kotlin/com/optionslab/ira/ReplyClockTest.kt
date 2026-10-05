package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplyClockTest {
    @Test fun timesWordsReadToTheReplysFirstSound() {
        val c = ReplyClock()
        c.heard(10_000)
        assertEquals(10_000L, c.pendingSince())
        c.queued("answer#4", 14_800)
        assertEquals(0L, c.pendingSince())
        assertNull(c.started("say#3", 15_000))                // "Yes, Boss?" or anything else: not the reply
        assertEquals(5_200L, c.started("answer#4.0", 15_200)) // the first piece of the reply
        assertNull(c.started("answer#4.1", 17_000))           // later pieces are not timed again
        assertNull(c.started("answer#4", 19_000))
    }

    @Test fun aSingleSentenceReplyIsItsOwnId() {
        val c = ReplyClock()
        c.heard(1_000); c.queued("question#9", 2_000)
        assertEquals(1_500L, c.started("question#9", 2_500))
    }

    @Test fun anotherUtteranceWithTheSamePrefixIsNotTheReply() {
        val c = ReplyClock()
        c.heard(1_000); c.queued("answer#1", 2_000)
        assertNull(c.started("answer#12.0", 2_100))
        assertEquals(1_200L, c.started("answer#1.0", 2_200))
    }

    /** Boss, 5 Oct: "typical wait 37.0 s" - a turn whose reply was never said must not time a later sound. */
    @Test fun aReplyNeverSaidTimesNothingLater() {
        val c = ReplyClock()
        c.heard(10_000)                                       // words read, but the reply was dropped (muted, a repeat)
        c.drop()
        c.queued("answer#7", 47_000)                          // an announcement later
        assertNull(c.started("answer#7.0", 47_100))
    }

    @Test fun aNewTurnReplacesAnOlderOne() {
        val c = ReplyClock()
        c.heard(10_000); c.queued("answer#1", 12_000)         // handed to the voice, but its sound never came (cut)
        c.heard(40_000); c.queued("answer#2", 44_000)
        assertNull(c.started("answer#1.0", 45_000))
        assertEquals(5_000L, c.started("answer#2.0", 45_000))
    }

    @Test fun onlyTheFirstReplyAfterTheWordsIsBound() {
        val c = ReplyClock()
        c.heard(10_000); c.queued("answer#1", 11_000)
        c.queued("answer#2", 12_000)                          // a second utterance without new words: not bound
        assertNull(c.started("answer#2.0", 12_100))
        assertEquals(1_200L, c.started("answer#1.0", 11_200))
    }

    @Test fun staleWordsAreNotTimed() {
        val c = ReplyClock()
        c.heard(1_000); c.queued("answer#1", 1_000 + ReplyClock.MAX_MS + 1)
        assertNull(c.started("answer#1", 1_000 + ReplyClock.MAX_MS + 2))
        val d = ReplyClock()
        d.queued("answer#1", 5_000)                           // nothing heard at all
        assertNull(d.started("answer#1", 5_100))
        assertNull(d.started(null, 5_100))
    }
}

class ModelYieldTest {
    @Test fun bossSpeakingOnlyInHisOwnTurnAndShortlyAfter() {
        assertFalse(ModelYield.bossSpeaking(listening = true, inSpeech = false, speechAt = 0, endAt = 0, now = 5_000))
        assertTrue(ModelYield.bossSpeaking(true, false, speechAt = 1_000, endAt = 0, now = 9_000))
        assertTrue(ModelYield.bossSpeaking(true, false, speechAt = 1_000, endAt = 8_000, now = 9_000))   // final reading
        assertFalse(ModelYield.bossSpeaking(true, false, speechAt = 1_000, endAt = 8_000, now = 8_000 + ModelYield.AFTER_SPEECH_MS))
        assertTrue(ModelYield.bossSpeaking(true, false, speechAt = 1_000, endAt = 3_000, now = 9_000, wordsAt = 8_500))  // went on
        assertFalse(ModelYield.bossSpeaking(true, false, speechAt = 1_000, endAt = 3_000, now = 9_000, wordsAt = 4_000))
        assertFalse(ModelYield.bossSpeaking(true, inSpeech = true, speechAt = 1_000, endAt = 0, now = 2_000))
        assertFalse(ModelYield.bossSpeaking(listening = false, inSpeech = false, speechAt = 1_000, endAt = 0, now = 2_000))
    }

    @Test fun voiceBusyWhileThinkingSpeakingOrHearing() {
        assertTrue(ModelYield.voiceBusy(speaking = true, thinking = false, bossSpeaking = false))
        assertTrue(ModelYield.voiceBusy(false, true, false))
        assertTrue(ModelYield.voiceBusy(false, false, true))
        assertFalse(ModelYield.voiceBusy(false, false, false))
    }

    @Test fun theWatchdogNeverCutsBossMidSentence() {
        assertFalse(ModelYield.mayResetTurn(0, bossSpeaking = false, now = 20_000))
        assertTrue(ModelYield.mayResetTurn(0, bossSpeaking = false, now = 26_000))
        assertFalse(ModelYield.mayResetTurn(0, bossSpeaking = true, now = 30_000))
        assertTrue(ModelYield.mayResetTurn(0, bossSpeaking = true, now = 46_000))
    }

    @Test fun cutInListeningCountsFromTheFirstSound() {
        assertNull(ModelYield.cutInIn(queuedAt = 1_000, soundAt = 500, startMs = 650, now = 1_700))   // no sound yet
        assertEquals(650L, ModelYield.cutInIn(queuedAt = 1_000, soundAt = 1_800, startMs = 650, now = 1_800))
        assertEquals(0L, ModelYield.cutInIn(queuedAt = 1_000, soundAt = 1_800, startMs = 650, now = 3_000))
    }
}
