package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Jarvis's IraThinker's flow questions (28 Oct): asked, never the order flow, never an action. */
class FlowTalkTest {
    @Test fun asksAboutTheFlow() {
        assertTrue(FlowTalk.asked("How is the IraThinker flow doing?"))
        assertTrue(FlowTalk.asked("irathinker's flow today"))
        assertTrue(FlowTalk.asked("what did the flow arm take"))
        assertTrue(FlowTalk.asked("flow trades"))
    }

    @Test fun neverTheOrderFlowOrAnAction() {
        assertFalse(FlowTalk.asked("what is the order flow saying"))
        assertFalse(FlowTalk.asked("order flow page"))
        assertFalse(FlowTalk.asked("switch off the flow arm"))
        assertFalse(FlowTalk.asked("buy nifty"))
        assertFalse(FlowTalk.asked(""))
    }
}
