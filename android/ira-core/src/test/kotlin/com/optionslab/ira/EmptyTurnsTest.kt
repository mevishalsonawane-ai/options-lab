package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmptyTurnsTest {
    private val saying = "Boss, Nifty is up 0.4 percent at 24,520, holding above the morning high."

    @Test fun hisOwnWordsHeardBackAreAnEcho() {
        assertTrue(EmptyTurns.echo("nifty is up", saying))
        assertTrue(EmptyTurns.echo("Boss Nifty", saying))
        assertTrue(EmptyTurns.echo("holding above the morning high", saying))
        // Three in four of a longer reading.
        assertTrue(EmptyTurns.echo("nifty is up 0 4 percent at the bank", saying))
    }

    @Test fun bossWordsAreNeverAnEcho() {
        assertFalse(EmptyTurns.echo("Jarvis stop", saying))               // the name is never his own
        assertFalse(EmptyTurns.echo("jarvis nifty is up", saying))
        assertFalse(EmptyTurns.echo("what about bank nifty", saying))
        assertFalse(EmptyTurns.echo("nifty levels", saying))              // a short reading must be all his words
        assertFalse(EmptyTurns.echo("", saying))
        assertFalse(EmptyTurns.echo("nifty is up", null))
        assertFalse(EmptyTurns.echo("nifty is up", "  "))
    }

    @Test fun kindsAndTheRunOfEmptyTurns() {
        assertEquals(EmptyTurns.Kind.WORDS, EmptyTurns.kind(words = true, echoOnly = true, speechBegan = true))
        assertEquals(EmptyTurns.Kind.ECHO, EmptyTurns.kind(false, true, true))
        assertEquals(EmptyTurns.Kind.SPEECH, EmptyTurns.kind(false, false, true))
        assertEquals(EmptyTurns.Kind.QUIET, EmptyTurns.kind(false, false, false))
        assertEquals(3, EmptyTurns.inRow(2, EmptyTurns.Kind.QUIET))
        assertEquals(1, EmptyTurns.inRow(0, EmptyTurns.Kind.SPEECH))
        assertEquals(0, EmptyTurns.inRow(40, EmptyTurns.Kind.WORDS))
        var r = emptyList<EmptyTurns.Kind>()
        repeat(30) { r = EmptyTurns.add(r, EmptyTurns.Kind.QUIET) }
        assertEquals(EmptyTurns.KEEP, r.size)
    }

    @Test fun restartsSlowOnlyAfterManyEmptyTurnsAndNeverWhenBossIsExpected() {
        assertEquals(200L, EmptyTurns.restartIn(0, false))
        assertEquals(200L, EmptyTurns.restartIn(EmptyTurns.CALM_AFTER - 1, false))
        assertEquals(700L, EmptyTurns.restartIn(EmptyTurns.CALM_AFTER, false))
        assertEquals(1_500L, EmptyTurns.restartIn(EmptyTurns.SLOW_AFTER, false))
        assertEquals(1_500L, EmptyTurns.restartIn(500, false))
        assertEquals(200L, EmptyTurns.restartIn(500, expected = true))
        // Never long enough to miss a whole "Jarvis": still listening for the name.
        assertTrue(EmptyTurns.restartIn(10_000, false) <= 2_000L)
    }

    @Test fun theVoiceCheckSaysThePatternPlainly() {
        val S = EmptyTurns.Kind.SPEECH; val Q = EmptyTurns.Kind.QUIET; val E = EmptyTurns.Kind.ECHO; val W = EmptyTurns.Kind.WORDS
        // Boss, 5 Oct 09:56: speech that never became words, turn after turn.
        val tv = EmptyTurns.say(listOf(S, S, S, S, S, S), emptyInRow = 6, cutIn = true)
        assertNotNull(tv)
        assertTrue(tv.startsWith("I keep hearing sound but no words - likely the TV or my own voice"), tv)
        assertTrue(tv.contains("6 of my last 6"), tv)
        assertTrue(tv.contains("cut-in is on"), tv)
        assertFalse(EmptyTurns.say(listOf(S, S, S), 3, cutIn = false)!!.contains("cut-in"))
        // His own voice coming back while he talks.
        val own = EmptyTurns.say(listOf(E, W, E, Q), 1, cutIn = true)
        assertNotNull(own)
        assertTrue(own.startsWith("I keep hearing my own voice"), own)
        // A long quiet run: listening a little less often, still for the name.
        val quiet = EmptyTurns.say(List(15) { Q }, 15, cutIn = false)
        assertNotNull(quiet)
        assertTrue(quiet.contains("15 turns with no words") && quiet.contains("Jarvis"), quiet)
        // Nothing stands out.
        assertNull(EmptyTurns.say(emptyList(), 0, false))
        assertNull(EmptyTurns.say(listOf(W, Q, W, S, W), 0, false))
        assertNull(EmptyTurns.say(listOf(Q, Q, Q), 3, false))
    }

    @Test fun neverAWordHeard() {
        val line = EmptyTurns.say(listOf(EmptyTurns.Kind.SPEECH, EmptyTurns.Kind.SPEECH, EmptyTurns.Kind.SPEECH), 3, true)!!
        assertFalse(line.contains("nifty", ignoreCase = true))
    }
}
