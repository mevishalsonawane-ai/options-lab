package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** "Don't listen" (Boss, 6 Oct): words that switch the microphone OFF only; nothing said or typed switches it back on. */
class NoListenTest {
    private val OFF = listOf(
        "don't listen", "Don't listen.", "dont listen", "do not listen", "Jarvis, don't listen", "please don't listen",
        "don't listen to me", "don't listen to my conversations", "don't listen to all the conversations", "don't listen to everything",
        "don't listen to my calls", "don't listen to what I say", "never listen to my conversations",
        "stop listening to me", "stop listening to my conversations", "Jarvis stop listening to everything",
        "no listening", "no more listening", "listening off", "mic off", "microphone off", "turn off the mic", "switch off your microphone",
        "turn the mic off", "switch listening off", "turn off listening", "shut off the microphone",
        "mat suno", "Jarvis mat suno", "suno mat", "sunna band karo", "sunna band kar do", "Jarvis, sunna band karo please",
        "meri baatein mat suno", "sab baatein mat suno", "baat mat suno", "mic band karo", "microphone band kar do", "mat suno boss",
    )

    /** Questions about listening, other commands, and every way of asking for it back on: never LISTEN_OFF. */
    private val NOT = listOf(
        "are you listening?", "are you listening", "why aren't you listening to me", "you are not listening", "is the mic working",
        "stop listening", "Jarvis stop listening", "listen again", "start listening", "listen to me", "suno", "sunna chalu karo", "mic on", "turn on the mic", "switch listening on",
        "unmute", "mute", "be quiet", "don't speak", "stop talking", "stop all strategies", "stop orb", "don't switch to live",
        "what did you hear", "don't listen to the news, what is nifty doing", "how is nifty", "listening for jarvis?",
    )

    @Test fun offWordsAreTheCommand() {
        for (q in OFF) {
            assertTrue(NoListen.asked(q), q)
            assertEquals(Command.Kind.LISTEN_OFF, Commands.parse(q)?.kind, q)
        }
    }

    @Test fun nothingElseIsAndNothingTurnsItOn() {
        for (q in NOT) {
            assertFalse(NoListen.asked(q), q)
            assertNotEquals(Command.Kind.LISTEN_OFF, Commands.parse(q)?.kind, q)
        }
        // There is no command that turns listening on: only Boss's button (the app) does that.
        assertTrue(Command.Kind.entries.none { it.name.startsWith("LISTEN_") && it != Command.Kind.LISTEN_OFF })
    }

    @Test fun itOnlyLowersAndIsAVoiceTool() {
        assertTrue(Command.Kind.LISTEN_OFF.reduces)
        assertEquals("Voice", Toolbox.of(Command.Kind.LISTEN_OFF).area)
        assertTrue("crossed-out ear" in NoListen.OFF && "Only you" in NoListen.OFF && "crossed-out ear" in NoListen.ALREADY)
        assertTrue(Commands.describe(Command(Command.Kind.LISTEN_OFF)).startsWith("stop listening"))
    }

    @Test fun theSwitchStaysOnThisPhoneThroughABackup() {
        // A restore never brings listening back: the switch is this phone's alone (never written to or taken from a file).
        assertFalse(Upkeep.carried("jarvis.voice.nolisten"))
    }
}
