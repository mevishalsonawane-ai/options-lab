package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Jarvis's S5 Trail question ([S5Talk]): asked plainly, never an action and never another feature's. */
class S5TalkTest {
    @Test fun itsQuestionsAreRead() {
        assertTrue(S5Talk.asked("How is S5 Trail doing?"))
        assertTrue(S5Talk.asked("s5 trail today"))
        assertTrue(S5Talk.asked("what did the s5 arm take"))
        assertTrue(S5Talk.asked("s5 trades"))
    }

    @Test fun actionsAndOtherThingsAreNot() {
        assertFalse(S5Talk.asked("switch off s5 trail"))
        assertFalse(S5Talk.asked("turn on the s5 arm"))
        assertFalse(S5Talk.asked("how is the flow arm doing"))
        assertFalse(S5Talk.asked("buy nifty"))
        assertFalse(S5Talk.asked(""))
    }
}
