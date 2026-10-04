package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals

class WakeTest {
    @Test fun theWakeWordAsksWakesOrStops() {
        assertEquals(Wake.Heard.Ask("how is nifty"), Wake.heard("Jarvis, how is Nifty?", awake = false))
        assertEquals(Wake.Heard.Ask("what is banknifty doing"), Wake.heard("hey Jarvis what is BankNifty doing", false))
        assertEquals(Wake.Heard.Ask("any news on banks"), Wake.heard("ok jervis, please any news on banks", false))
        assertEquals(Wake.Heard.Awake, Wake.heard("Jarvis", false))
        assertEquals(Wake.Heard.Awake, Wake.heard("Hey Jarvis!", true))
        assertEquals(Wake.Heard.Stop, Wake.heard("Jarvis, stop listening", false))
        assertEquals(Wake.Heard.Stop, Wake.heard("go to sleep", true))
    }

    @Test fun otherTalkIsIgnoredUnlessAwake() {
        assertEquals(Wake.Heard.Ignore, Wake.heard("what's for dinner", false))
        assertEquals(Wake.Heard.Ignore, Wake.heard("jarvisland is a place", false), "a longer word is not the wake word")
        assertEquals(Wake.Heard.Ask("how is gold"), Wake.heard("How is gold?", true))
        assertEquals(Wake.Heard.Ignore, Wake.heard("   ", true))
    }

    @Test fun answersAreShortenedForSpeech() {
        assertEquals("One. Two! Three?", Wake.spoken("One. Two! Three? Four."))
        assertEquals("Options: plus 1,200 rupees a lot.", Wake.spoken("Options: Rs +1,200 a lot."))
        assertEquals("Nifty is at 24,612.40 (plus 0.21%).", Wake.spoken("Nifty is at 24,612.40 (+0.21%)."))
    }

    @Test fun aYesOrNoAnswerAndNoWinsWhenMuddled() {
        assertEquals(true, Wake.yesNo("Yes"))
        assertEquals(true, Wake.yesNo("yeah go ahead"))
        assertEquals(true, Wake.yesNo("Jarvis, approve it"))
        assertEquals(false, Wake.yesNo("No"))
        assertEquals(false, Wake.yesNo("reject"))
        assertEquals(false, Wake.yesNo("yes... no, leave it"))
        assertEquals(false, Wake.yesNo("don't"))
        assertEquals(false, Wake.yesNo("not okay"))
        assertEquals(true, Wake.yesNo("Positive"))
        assertEquals(true, Wake.yesNo("approved"))
        assertEquals(false, Wake.yesNo("Negative"))
        assertEquals(false, Wake.yesNo("rejected"))
        assertEquals(null, Wake.yesNo("what is nifty doing"))
        assertEquals(null, Wake.yesNo(""))
    }
}
