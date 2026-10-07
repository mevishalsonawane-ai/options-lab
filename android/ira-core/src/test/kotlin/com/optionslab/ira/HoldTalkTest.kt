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
}
