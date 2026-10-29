package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Jarvis's CISD + network question ([CisdTalk]): asked plainly, never an action and never another feature's. */
class CisdTalkTest {
    @Test fun itsQuestionsAreRead() {
        assertTrue(CisdTalk.asked("How is CISD doing?"))
        assertTrue(CisdTalk.asked("cisd network today"))
        assertTrue(CisdTalk.asked("what did the cisd arm take"))
        assertTrue(CisdTalk.asked("CISD + network trades"))
    }

    @Test fun actionsAndOtherThingsAreNot() {
        assertFalse(CisdTalk.asked("switch off cisd"))
        assertFalse(CisdTalk.asked("turn on the cisd arm"))
        assertFalse(CisdTalk.asked("how is s5 trail doing"))
        assertFalse(CisdTalk.asked("buy nifty"))
        assertFalse(CisdTalk.asked("discount"))
        assertFalse(CisdTalk.asked(""))
    }
}
