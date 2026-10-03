package com.optionslab.ira

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatTest {
    @Test fun smallTalkIsAnsweredInVariedWords() {
        val a = Chat.smallTalk("How are you?", 0); val b = Chat.smallTalk("Jarvis, how are you", 1)
        assertNotNull(a); assertNotNull(b); assertNotEquals(a, b, "not the same line twice running")
        assertNotNull(Chat.smallTalk("thank you jarvis", 0))
        assertNotNull(Chat.smallTalk("Hey Jarvis, who are you?", 0))
        assertNotNull(Chat.smallTalk("tell me a joke", 2))
        assertNotNull(Chat.smallTalk("good night", 0))
    }

    @Test fun anythingElseIsNotSmallTalk() {
        assertNull(Chat.smallTalk("how is nifty", 0))
        assertNull(Chat.smallTalk("how are you going to trade banknifty", 0))
        assertNull(Chat.smallTalk("thanks, now stop all strategies", 0))
        assertNull(Chat.smallTalk("buy 1 lot nifty 24000 call", 0))
    }

    @Test fun howAreYouIsNotAMarketQuestion() {
        val p = Ask.parse("how are you")
        assertNull(p.command); assertNull(p.order)
        assertTrue(Topic.COMMAND !in p.topics && Topic.ORDER !in p.topics)
    }

    @Test fun onlySafeModelRepliesAreUsed() {
        assertEquals("I'm doing great, Boss. Ready when you are.", Chat.accept("Jarvis: I'm doing great, Boss. Ready when you are.\nBoss: ok"))
        assertNull(Chat.accept("Nifty is at 24,500 today."), "no figures")
        assertNull(Chat.accept("You should buy the call now."), "no advice")
        assertNull(Chat.accept("I placed the order for you."), "no claimed actions")
        assertNull(Chat.accept("I've stopped your strategies."))
        assertNull(Chat.accept(""))
        assertNull(Chat.accept(null))
        assertNull(Chat.accept("x".repeat(300)))
        assertEquals("One. Two.", Chat.accept("One. Two. Three."))
    }

    @Test fun promptAndFallback() {
        val p = Chat.prompt("what's your favourite colour", LocalDateTime.of(2026, 10, 3, 9, 0))
        assertTrue(p.endsWith("Boss: what's your favourite colour\nJarvis:"))
        assertTrue("saturday morning" in p)
        assertNotEquals(Chat.fallback(0), Chat.fallback(1))
        assertEquals(Chat.fallback(0), Chat.fallback(4))
    }
}
