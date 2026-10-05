package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EarsTest {
    @Test fun scoresThatMeanNothingAreIgnored() {
        assertNull(Sure.best(null, 3))
        assertNull(Sure.best(floatArrayOf(), 3))
        assertNull(Sure.best(floatArrayOf(0.9f, 0.2f), 3))            // does not match the readings
        assertNull(Sure.best(floatArrayOf(-1f, -1f, -1f), 3))         // "not available"
        assertNull(Sure.best(floatArrayOf(0f, 0f, 0f), 3))            // not filled in
        assertNull(Sure.best(floatArrayOf(Float.NaN), 1))
        assertNull(Sure.best(floatArrayOf(1.5f), 1))
        assertEquals(0.82f, Sure.best(floatArrayOf(0.82f, 0f, 0f), 3))
        assertEquals(0.1f, Sure.best(floatArrayOf(0.1f), 1))
    }

    @Test fun aFaintFollowUpIsTheRoom() {
        assertTrue(Sure.faint(0.1f, named = false, called = false))
        assertFalse(Sure.faint(0.6f, named = false, called = false))  // clear: still heard
        assertFalse(Sure.faint(Sure.FAINT, named = false, called = false))
    }

    @Test fun aFaintReadingNeverLosesTheNameOrAQuestionBossCalledFor() {
        assertFalse(Sure.faint(0.05f, named = true, called = false))  // with the name: as before
        assertFalse(Sure.faint(0.05f, named = false, called = true))  // after "Yes, Boss?" or the mic button
        assertFalse(Sure.faint(null, named = false, called = false))  // no scores: as before
    }

    @Test fun aFaintYesIsNotAYes() {
        assertTrue(Sure.faintYes(0.1f))
        assertFalse(Sure.faintYes(0.7f))
        assertFalse(Sure.faintYes(null))
    }

    @Test fun theTraceHoldsANumberOnly() {
        assertEquals("sure 0.42", Sure.say(0.42f))
        assertEquals("no score", Sure.say(null))
    }

    @Test fun theWaitIsTheDefaultUntilBossPausesAreKnown() {
        assertEquals(BossPace.DEFAULT_MS, BossPace.endAfter(emptyList()))
        assertEquals(BossPace.DEFAULT_MS, BossPace.endAfter(List(BossPace.LEAST - 1) { 1_200L }))
    }

    @Test fun aSlowSpeakerIsGivenLongerAndAQuickOneLess() {
        val slow = List(20) { if (it % 3 == 0) 1_000L else 400L }
        assertEquals(1_300L, BossPace.endAfter(slow))
        val quick = List(20) { 250L }
        assertEquals(BossPace.MIN_MS, BossPace.endAfter(quick))
        val long = List(20) { 1_400L }
        assertEquals(BossPace.MAX_MS, BossPace.endAfter(long))
    }

    @Test fun onlyPausesInsideSpeechAreKept() {
        assertEquals(emptyList(), BossPace.add(emptyList(), 50))       // the recognizer's own rhythm
        assertEquals(emptyList(), BossPace.add(emptyList(), 4_000))    // a stalled recognizer
        assertEquals(listOf(500L), BossPace.add(emptyList(), 500))
        var g = emptyList<Long>()
        repeat(100) { g = BossPace.add(g, 300L + it) }
        assertEquals(BossPace.KEEP, g.size)
        assertEquals(399L, g.last())
    }

    @Test fun keptAsNumbersAndADamagedValueReadsAsNone() {
        val g = listOf(300L, 640L, 900L)
        assertEquals(g, BossPace.load(BossPace.save(g)))
        assertEquals(emptyList(), BossPace.load(null))
        assertEquals(listOf(400L), BossPace.load("abc,400,,99999,-5"))
        assertTrue(BossPace.say(g).contains("default"))
        assertTrue(BossPace.say(List(10) { 500L }).contains("learned from 10"))
    }
}
