package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureEchoTest {
    @Test fun echoCancellingOnlyWhileJarvisTalks() {
        assertTrue(CaptureEcho.on(speaking = true))
        assertFalse(CaptureEcho.on(speaking = false))
    }

    @Test fun aQuietCutInTurnOnTheCallMicrophoneGivesWay() {
        assertTrue(CaptureEcho.plainAfter(listening = true, inSpeech = true, callSource = true, speechBegan = false, partial = false))
    }

    @Test fun bossSpeakingIntoTheTurnIsNeverCut() {
        assertFalse(CaptureEcho.plainAfter(listening = true, inSpeech = true, callSource = true, speechBegan = true, partial = false))
        assertFalse(CaptureEcho.plainAfter(listening = true, inSpeech = true, callSource = true, speechBegan = false, partial = true))
        assertFalse(CaptureEcho.plainAfter(listening = true, inSpeech = true, callSource = true, speechBegan = true, partial = true))
    }

    @Test fun otherTurnsAreLeftAlone() {
        // A taught voice's capture (the recognition source): its canceller is switched off instead, and the turn goes on.
        assertFalse(CaptureEcho.plainAfter(listening = true, inSpeech = true, callSource = false, speechBegan = false, partial = false))
        // A turn opened in silence, or none open.
        assertFalse(CaptureEcho.plainAfter(listening = true, inSpeech = false, callSource = true, speechBegan = false, partial = false))
        assertFalse(CaptureEcho.plainAfter(listening = false, inSpeech = true, callSource = true, speechBegan = false, partial = false))
    }
}
