package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VettingTest {
    @Test fun onlyWhatHeldUpIsBrought() {
        val good = List(10) { 600.0 } + List(6) { -300.0 }                 // +4,200, PF 3.3
        assertEquals(Vetting.State.HELD_UP, Vetting.judge("ORB 5", good).state)
        assertEquals(Vetting.State.FAILED, Vetting.judge("Pine A", List(8) { 200.0 } + List(8) { -300.0 }).state)
        assertEquals(Vetting.State.TESTING, Vetting.judge("Pine B", List(14) { 500.0 }).state)          // too few
        assertEquals(Vetting.State.TESTING, Vetting.judge("Pine C", List(8) { 110.0 } + List(8) { -100.0 }).state) // PF 1.1
        // A deep run of losses before the gains: not held up yet.
        assertEquals(Vetting.State.TESTING, Vetting.judge("Pine D", List(8) { -500.0 } + List(8) { 600.0 }).state)
    }

    @Test fun saidPlainly() {
        val s = Vetting.say(listOf(Vetting.judge("ORB 5", List(10) { 600.0 } + List(6) { -300.0 }), Vetting.judge("Pine B", List(3) { 100.0 })))
        assertTrue(s.startsWith("Held up on paper, Boss: ORB 5 held up on paper: 16 trades, +Rs 4,200.00"), s)
        assertTrue(s.endsWith("Going live is your step: arm it in Live with your PIN."), s)
        assertTrue(Vetting.judge("Pine 50% fade", List(16) { 300.0 }).text().startsWith("Pine 50% fade held up"))
        assertTrue(Vetting.asked("Jarvis, what held up?")); assertTrue(Vetting.asked("what should I trade live"))
    }
}
