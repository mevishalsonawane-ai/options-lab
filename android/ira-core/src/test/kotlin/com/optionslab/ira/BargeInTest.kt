package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BargeInTest {
    private val saying = "Boss, Nifty is at 24,612. It rose 0.21 percent today. Banks led. The rest is in the chat."

    @Test fun theNameOverHimStopsHimAtOnce() {
        assertEquals(BargeIn.Cut.NAME, BargeIn.cut("Jarvis", saying))
        assertEquals(BargeIn.Cut.NAME, BargeIn.cut("it rose 0.21 jarvis what about bank nifty", saying))
        assertEquals(BargeIn.Cut.NAME, BargeIn.cut("Jarvis stop", saying))
    }

    @Test fun aClearStopWordStopsHimWithoutTheName() {
        for (w in listOf("stop", "bas", "ok ok", "next", "wait", "enough", "chup", "bas karo", "okay okay", "skip", "stop please"))
            assertEquals(BargeIn.Cut.HUSH, BargeIn.cut(w, saying), w)
        // His own words heard first, then Boss's stop.
        assertEquals(BargeIn.Cut.HUSH, BargeIn.cut("banks led stop", saying))
    }

    @Test fun hisOwnVoiceIsNeverBoss() {
        // Only his own words heard back.
        assertNull(BargeIn.cut("nifty is at 24612", saying))
        assertNull(BargeIn.cut("the rest is in the chat", saying))
        // A stop word that he is saying himself is his echo, not Boss.
        val question = "Boss, shall I stop ORB? Yes or no?"
        assertNull(BargeIn.cut("stop", question))
        assertNull(BargeIn.cut("shall i stop", question))
        // "Ok" in his own words: "ok ok" may be his echo.
        assertNull(BargeIn.cut("ok ok", "Ok Boss, done. Nifty is flat."))
        // A soft misreading of the name ("service") is not the name.
        assertNull(BargeIn.cut("service", saying))
    }

    @Test fun otherTalkIsLeftToTheTurnsEnd() {
        assertNull(BargeIn.cut("", saying))
        assertNull(BargeIn.cut(null, saying))
        assertNull(BargeIn.cut("what about bank nifty", saying))
        assertNull(BargeIn.cut("please stop the car near the gate", saying))
        // A stop word inside a longer sentence is not a stop.
        assertNull(BargeIn.cut("stop loss kahan lagaun", saying))
    }

    @Test fun goOnAsksForTheRest() {
        for (w in listOf("go on", "continue", "aage bolo", "Carry on please", "ok continue", "say the rest", "jarvis go on", "haan aage"))
            assertTrue(BargeIn.goOn(w), w)
        for (w in listOf("go", "continue the strategy", "start orb", "aage", "phir", "what next", "go ahead"))
            assertFalse(BargeIn.goOn(w), w)
    }

    @Test fun theSentenceUnderWayIsSaidAgainWhole() {
        val s = "Nifty is up. Banks led. IT lagged."
        assertEquals(0, BargeIn.sentenceAt(s, -1))
        assertEquals(0, BargeIn.sentenceAt(s, 0))
        assertEquals(0, BargeIn.sentenceAt(s, 5))
        assertEquals(1, BargeIn.sentenceAt(s, s.indexOf("Banks")))
        assertEquals(1, BargeIn.sentenceAt(s, s.indexOf("led")))
        assertEquals(2, BargeIn.sentenceAt(s, s.indexOf("IT") + 1))
    }

    @Test fun goOnSaysTheRestOfTheCutOffAnswerOnly() {
        val full = "Nifty is at 24,612.40, Boss. It rose 0.21% today. Banks led. IT lagged. Volume was light."
        val c = BargeIn.cutOff(full, saying, saying.indexOf("rose"), account = false, now = 1_000L)!!
        assertEquals(1, c.from)
        assertEquals(BargeIn.Rest.Say("It rose 0.21% today. Banks led. IT lagged. Volume was light."), BargeIn.rest(c, 2_000L, locked = false))
        // Cut on "The rest is in the chat.": the sentences the chat holds beyond what was said.
        val late = BargeIn.cutOff(full, saying, saying.indexOf("chat"), false, 1_000L)!!
        assertEquals(BargeIn.Rest.Say("IT lagged. Volume was light."), BargeIn.rest(late, 2_000L, false))
        // Too old, or nothing kept: nothing is said.
        assertNull(BargeIn.rest(c, 1_000L + BargeIn.KEEP_MS + 1, false))
        assertNull(BargeIn.rest(null, 2_000L, false))
        // Nothing left unsaid: nothing is kept.
        assertNull(BargeIn.cutOff("Nifty is up.", "Boss, Nifty is up.", 18, false, 0L))
        assertEquals(0, BargeIn.cutOff("Nifty is up.", "Boss, Nifty is up.", 15, false, 0L)!!.from)
        assertNull(BargeIn.cutOff(null, saying, 3, false, 0L))
    }

    @Test fun aLockedPhoneNeverSaysTheAccountAloud() {
        val c = BargeIn.cutOff("Your P&L is 2,400 rupees. Two trades are open.", "Boss, your P&L is 2,400 rupees. Two trades are open.", 3, account = true, now = 0L)
        assertTrue(BargeIn.rest(c, 10L, locked = true) is BargeIn.Rest.Refused)
        assertTrue(BargeIn.rest(c, 10L, locked = false) is BargeIn.Rest.Say)
        // A market answer is still said on a locked phone.
        val m = BargeIn.cutOff("Nifty is up. Banks led.", "Boss, Nifty is up. Banks led.", 3, account = false, now = 0L)
        assertTrue(BargeIn.rest(m, 10L, locked = true) is BargeIn.Rest.Say)
    }

    @Test fun theNameIsReadOnlyWhenSaid() {
        assertTrue(Wake.named("Jarvis, how is Nifty?"))
        assertTrue(Wake.named("ok jarvas"))
        assertFalse(Wake.named("service how are you"))
        assertFalse(Wake.named("jarvisx"))
    }
}
