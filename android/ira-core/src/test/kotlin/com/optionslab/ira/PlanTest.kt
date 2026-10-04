package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanTest {
    private fun step(s: String) = Ask.parse(s).let { it.command != null && it.order == null }

    @Test fun aRequestInStepsIsReadAsAPlan() {
        val s = Plan.steps("Stop all strategies, then turn on the kill switch and then switch to paper", ::step)
        assertEquals(3, s?.size, s.toString())
        assertEquals(listOf(Command.Kind.STOP_ALL, Command.Kind.KILL_ON, Command.Kind.MODE_PAPER), s!!.map { Ask.parse(it).command!!.kind })
        assertTrue(Plan.say(s).startsWith("1) Stop all strategies; 2) "))
    }

    @Test fun oneRequestStaysOne() {
        assertNull(Plan.steps("stop all strategies", ::step))
        assertNull(Plan.steps("what is nifty and banknifty doing", ::step))           // a question, not steps
        assertNull(Plan.steps("stop all strategies then what is the nifty price", ::step)) // a part is not a step
    }

    @Test fun itStopsAtTheFirstFailure() {
        assertTrue(Plan.failed("Not sent: the kill switch is on")); assertFalse(Plan.failed("Kill switch on."))
        val r = Plan.report(listOf("stop all" to "Stopped 2 strategies.", "switch to paper" to "Not done: already in Paper."), 3)
        assertTrue(r.contains("2) switch to paper: NOT done - Not done"), r)
        assertTrue(r.endsWith("I stopped there: the step after it was not tried."), r)
    }
}
