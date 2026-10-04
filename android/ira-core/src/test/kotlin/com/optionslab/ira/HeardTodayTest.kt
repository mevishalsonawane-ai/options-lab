package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Words Boss said to Jarvis on 4 Oct that went to the chat instead of an answer. */
class HeardTodayTest {
    @Test fun spokenVoiceChecks() {
        for (s in listOf("why can't hear me", "wht cant you here me", "i am trying to speak, am i audible too you", "am i audible to you",
                "can't hear me", "do you hear me at all", "why can t you hear me", "Jarvis, why can't I hear you"))
            assertEquals(Command.Kind.VOICE_CHECK, Ask.parse(s).command?.kind, s)
        // Plain "can you hear me" stays a hello ("Right here, Boss").
        assertTrue(Ask.parse("can you hear me").command?.kind != Command.Kind.VOICE_CHECK)
    }

    @Test fun whatsGoingOnIsTheMarket() {
        for (s in listOf("what s going on", "what's going on?", "what's happening", "any update", "what's going on in the market", "kya chal raha hai"))
            assertTrue(Topic.OVERVIEW in Ask.parse(s).topics, s)
        assertEquals(listOf(Market.BANKNIFTY), Ask.parse("what's going on in banknifty").markets.take(1).ifEmpty { listOf(Market.BANKNIFTY) })
        // Still commands and orders first.
        assertEquals(Command.Kind.MUTE, Ask.parse("mute").command?.kind)
    }
}

class QuickerThinkingTest {
    @Test fun onlyCommandLikeWordsAskTheModelTwice() {
        for (s in listOf("good evening friend", "tell me something interesting", "what is the meaning of life")) assertTrue(!Intents.mayMean(s), s)
        for (s in listOf("halt every strategy", "what did you study", "kill everything", "how much money did I make")) assertTrue(Intents.mayMean(s), s)
    }

    @Test fun aCutReplyEndsOnAWholeSentence() {
        assertEquals("Nice to hear from you, Boss.", Chat.accept("Nice to hear from you, Boss. I was just thinking about"))
    }
}
