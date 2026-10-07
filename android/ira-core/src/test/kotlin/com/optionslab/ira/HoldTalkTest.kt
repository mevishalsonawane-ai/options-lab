package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HoldTalkTest {
    @Test fun aPauseMidHoldSendsNothingAndListensOn() {
        val b = HoldTalk.Buffer(0)
        b.partial("what is")
        b.partial("what is nifty")
        // The recognizer closed its turn on a pause, the mic still held: kept, listen again.
        assertEquals(HoldTalk.Next.LISTEN, b.turnEnded("what is nifty"))
        assertFalse(b.sent)
        b.partial("doing today")
        // Let go: the next close sends the whole hold, once, as one question.
        b.release()
        assertEquals(HoldTalk.Next.SEND, b.turnEnded("doing today"))
        assertEquals("what is nifty doing today", b.take())
        assertTrue(b.sent)
        assertNull(b.take(), "asked once only")
    }

    @Test fun aTurnEndingWithoutAFinalReadingKeepsItsWordsReadMidTurn() {
        val b = HoldTalk.Buffer(0)
        b.partial("banknifty levels")
        assertEquals(HoldTalk.Next.LISTEN, b.turnEnded(null))
        b.partial("  ")
        assertEquals(HoldTalk.Next.LISTEN, b.turnEnded(""))
        b.partial("please")
        // Let go before the final reading came: the words read so far.
        b.release()
        assertEquals("banknifty levels please", b.take())
    }

    @Test fun nothingHeardAsksNothing() {
        val b = HoldTalk.Buffer(0)
        assertEquals(HoldTalk.Next.LISTEN, b.turnEnded(null))
        b.release()
        assertEquals(HoldTalk.Next.SEND, b.turnEnded("   "))
        assertNull(b.take())
    }

    @Test fun theSameFinalTwiceIsKeptOnce() {
        val b = HoldTalk.Buffer(0)
        b.turnEnded("nifty pcr"); b.turnEnded("nifty pcr")
        assertEquals("nifty pcr", b.text())
    }

    @Test fun aHoldIsSilentTheOtherTalksGreet() {
        assertNull(HoldTalk.greeting(hold = true))
        assertEquals("Yes, Boss?", HoldTalk.greeting(hold = false))
    }

    @Test fun aHoldIsCappedAtSixtySeconds() {
        val b = HoldTalk.Buffer(1_000)
        assertFalse(b.capped(1_000 + HoldTalk.CAP_MS - 1))
        assertTrue(b.capped(1_000 + HoldTalk.CAP_MS))
        assertEquals(60_000L, HoldTalk.CAP_MS)
    }

    @Test fun whileAYesOrNoWaitsOnlyAShortHoldIsAnAnswer() {
        assertTrue(HoldTalk.shortAnswer("yes"))
        assertTrue(HoldTalk.shortAnswer("  yes go ahead please "))
        assertFalse(HoldTalk.shortAnswer("ok so what I was thinking is maybe we should sure"), "a long ramble with ok and sure in it")
        assertFalse(HoldTalk.shortAnswer("yes go ahead please jarvis"), "five words")
        assertFalse(HoldTalk.shortAnswer(""))
        assertFalse(HoldTalk.shortAnswer(null))
        assertEquals(4, HoldTalk.ANSWER_WORDS)
        assertEquals("Say yes or no, Boss.", HoldTalk.SAY_YES_OR_NO)
    }

    @Test fun theVoiceCheckHearsTheSegmentWithTheMostWords() {
        val b = HoldTalk.Buffer(0)
        val long = shortArrayOf(1, 2, 3); val short = shortArrayOf(9)
        b.turnEnded("buy nifty twenty four thousand call", long, 0.9f, listOf("buy nifty twenty four thousand call"))
        assertFalse(b.spliced(), "one segment so far")
        b.turnEnded("now", short, 0.4f, listOf("now", "no"))
        b.release()
        assertTrue(b.spliced(), "two recognizer turns")
        assertTrue(b.audio === long)
        assertEquals(0.4f, b.sure, "the lowest score across the hold")
        assertEquals(listOf("buy nifty twenty four thousand call now", "buy nifty twenty four thousand call no"), b.alternatives())
        assertEquals("buy nifty twenty four thousand call now", b.take())
    }

    @Test fun aSegmentWithoutAudioOrScoreKeepsTheOthers() {
        val b = HoldTalk.Buffer(0)
        val pcm = shortArrayOf(5)
        b.turnEnded("nifty", pcm, null)
        b.partial("what is the level today")
        b.turnEnded(null, null, null)
        assertTrue(b.audio === pcm, "no audio for the longer segment: the one heard is kept")
        assertNull(b.sure)
        assertEquals(listOf("nifty what is the level today"), b.alternatives())
        // The open turn's words read mid-turn count as a segment.
        val c = HoldTalk.Buffer(0)
        c.turnEnded("nifty")
        c.partial("pcr")
        assertTrue(c.spliced())
        assertFalse(HoldTalk.Buffer(0).also { it.partial("pcr") }.spliced(), "one open turn alone is one segment")
    }

    @Test fun timeoutsBackOffAndTheThirdErrorOrAnyOtherEndsTheHold() {
        val b = HoldTalk.Buffer(0)
        b.partial("nifty")
        assertEquals(300L, b.turnFailed(timeout = true))
        assertEquals(600L, b.turnFailed(timeout = true))
        assertNull(b.turnFailed(timeout = true), "three in a row: the hold ends")
        assertEquals("nifty", b.text(), "the words read mid-turn are kept")
        val c = HoldTalk.Buffer(0)
        assertEquals(300L, c.turnFailed(timeout = true))
        c.turnEnded("pcr")
        assertEquals(0, c.errors)
        assertEquals(300L, c.turnFailed(timeout = true), "a turn that closed normally resets the count")
        assertNull(c.turnFailed(timeout = false), "any other error ends it at once")
        assertEquals(listOf(300L, 600L, 1_200L, 1_200L), (1..4).map { HoldTalk.errorWait(it) })
    }
}
