package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CutInTest {
    @Test fun onByItselfOnlyWithAHeadsetOrEchoCancelling() {
        assertEquals(CutIn.Why.HEADSET, CutIn.decide(null, false, true, CutIn.Echo.NONE).also { assertTrue(it.on) }.why)
        assertEquals(CutIn.Why.ECHO_APPLIED, CutIn.decide(null, false, false, CutIn.Echo.APPLIED).also { assertTrue(it.on) }.why)
        assertEquals(CutIn.Why.ECHO_AVAILABLE, CutIn.decide(null, false, false, CutIn.Echo.AVAILABLE).also { assertTrue(it.on) }.why)
        // Nothing between his voice and the microphone: off, as before.
        val plain = CutIn.decide(null, false, false, CutIn.Echo.NONE)
        assertFalse(plain.on); assertEquals(CutIn.Why.NO_SHIELD, plain.why)
    }

    @Test fun bossChoiceWins() {
        val off = CutIn.decide(false, false, true, CutIn.Echo.APPLIED)
        assertFalse(off.on); assertEquals(CutIn.Why.BOSS_OFF, off.why)
        val on = CutIn.decide(true, true, false, CutIn.Echo.NONE)
        assertTrue(on.on); assertEquals(CutIn.Why.BOSS_ON, on.why); assertEquals(CutIn.PLAIN_MS, on.startMs)
    }

    @Test fun aPhoneThatGoesSilentKeepsTheAutomaticChoiceOff() {
        val d = CutIn.decide(null, true, true, CutIn.Echo.APPLIED)
        assertFalse(d.on); assertEquals(CutIn.Why.SILENCES, d.why)
    }

    @Test fun listeningStartsSoonerWhenHisVoiceCannotReachTheMicrophone() {
        assertEquals(CutIn.HEADSET_MS, CutIn.decide(null, false, true, CutIn.Echo.APPLIED).startMs)
        assertEquals(CutIn.ECHO_APPLIED_MS, CutIn.decide(true, false, false, CutIn.Echo.APPLIED).startMs)
        assertEquals(CutIn.ECHO_AVAILABLE_MS, CutIn.decide(null, false, false, CutIn.Echo.AVAILABLE).startMs)
        assertEquals(CutIn.PLAIN_MS, CutIn.decide(true, false, false, CutIn.Echo.NONE).startMs)
        assertTrue(CutIn.HEADSET_MS < CutIn.ECHO_APPLIED_MS && CutIn.ECHO_APPLIED_MS < CutIn.ECHO_AVAILABLE_MS && CutIn.ECHO_AVAILABLE_MS < CutIn.PLAIN_MS)
    }

    @Test fun choiceIsKept() {
        for (c in listOf(true, false, null)) assertEquals(c, CutIn.load(CutIn.save(c)))
        assertNull(CutIn.load("garbage"))
        assertNull(CutIn.load(null))
    }

    @Test fun diagnosticsLineSaysWhy() {
        val s = CutIn.say(CutIn.decide(null, false, true, CutIn.Echo.NONE), null)
        assertTrue(s.startsWith("Cut-in: on (automatic: a headset is connected)"), s)
        assertTrue("300 ms" in s, s)
        assertEquals("Cut-in: off (Boss's choice: Boss switched it off)", CutIn.say(CutIn.decide(false, false, true, CutIn.Echo.NONE), false))
        assertTrue(CutIn.say(CutIn.decide(null, false, false, CutIn.Echo.NONE), null).startsWith("Cut-in: off (automatic: no headset"))
    }
}
