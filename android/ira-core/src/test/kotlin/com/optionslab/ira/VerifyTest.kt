package com.optionslab.ira

import com.optionslab.ira.Command.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerifyTest {
    @Test fun jarvisChecksTheEffect() {
        assertNull(Verify.problem(Kind.KILL_ON, Verify.Facts(killOn = true)))
        assertEquals("the kill switch still reads off", Verify.problem(Kind.KILL_ON, Verify.Facts(killOn = false)))
        assertEquals("an arm is still armed", Verify.problem(Kind.STOP_ALL, Verify.Facts(botsStopped = true, anyArmed = true)))
        assertEquals("2 positions are still open (an exit may still be filling)", Verify.problem(Kind.CLOSE_ALL, Verify.Facts(openPositions = 2)))
        assertNull(Verify.problem(Kind.CLOSE_ALL, Verify.Facts()))                       // not read: not judged
        assertTrue(Verify.needs(Kind.ALARM_ADD).isEmpty())
    }

    @Test fun aFailedCheckReadsAsNotDone() {
        val s = Verify.say("Kill switch on.", "the kill switch still reads off", true)
        assertTrue(Plan.failed(s), s)
        assertEquals("Kill switch on. Checked: it took effect.", Verify.say("Kill switch on.", null, true))
    }
}
