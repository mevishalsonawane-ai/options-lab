package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickIntentTest {
    @Test fun everydayWordsAreReadAtOnce() {
        assertEquals("stop all strategies", Intents.quick("Jarvis, pause all bots"))
        assertEquals("stop all strategies", Intents.quick("halt the algos please"))
        assertEquals("close all positions", Intents.quick("square off everything"))
        assertEquals("cancel all orders", Intents.quick("pull my pending orders"))
        assertEquals("turn the kill switch on", Intents.quick("hit the panic button"))
        assertEquals("switch to paper mode", Intents.quick("go back to paper trading"))
        assertEquals("what is my p&l today", Intents.quick("am I up today?"))
        assertEquals("show my positions", Intents.quick("what am I holding"))
        assertEquals("should i trade now", Intents.quick("is it a good time to trade"))
        assertEquals("what did you study last night", Intents.quick("what did you learn last night"))
    }

    @Test fun nothingThatStartsOrAddsRiskAndNothingElse() {
        assertNull(Intents.quick("resume all bots"))
        assertNull(Intents.quick("turn on all strategies"))
        assertNull(Intents.quick("go live"))
        assertNull(Intents.quick("buy nifty"))
        assertNull(Intents.quick("how was your day"))
        assertNull(Intents.quick("turn off the kill switch"))
    }

    @Test fun everyQuickLineIsALineOfTheList() {
        for (s in listOf("pause all bots", "square off everything", "pull my orders", "hit the kill switch", "go to paper", "am i up", "what am i holding", "can i trade now", "what did you learn"))
            assertTrue(Intents.quick(s)?.let { Intents.pick(it) != null } == true, s)
    }
}
