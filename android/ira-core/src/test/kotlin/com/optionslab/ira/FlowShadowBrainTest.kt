package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The brain's pauses and the findings' consensus in the order flow's shadow log, and their effect per strategy. */
class FlowShadowBrainTest {
    private fun sig(id: String, key: String = "orb") = FlowShadow.signal(id, 1_760_000_000L, key, "BANKNIFTY", 1, false, OrderFlow.Mode.SHADOW, 55,
        OrderFlow.Agreement.UNKNOWN, false, null)

    @Test fun shadowPausesAndConsensusAreLoggedBesideEachSignalAndRecordedPerCause() {
        val c = Findings.Consensus("BANKNIFTY", 3, 1, 200, 70, emptyList())
        val lines = listOf(
            sig("a"), FlowShadow.brain("a", null, listOf("traps"), listOf("brain: trap alert: pull bid")), FlowShadow.consensus("a", c),
            FlowShadow.order("a", "P1"), FlowShadow.result("a", -900.0),
            sig("b"), FlowShadow.brain("b", null, emptyList(), emptyList()), FlowShadow.consensus("b", null),
            FlowShadow.order("b", "P2"), FlowShadow.result("b", 1_240.0),
            sig("c"), FlowShadow.brain("c", "paused: RBI policy window", emptyList(), emptyList()), FlowShadow.verdict("c", FlowShadow.PAUSED),
        )
        val s = FlowShadow.parse(lines.asSequence()).associateBy { it.id }
        assertEquals(listOf("traps"), s.getValue("a").wouldPause)
        assertEquals(3 to 1, s.getValue("a").consensus)
        assertNull(s.getValue("b").consensus)
        assertEquals("paused: RBI policy window", s.getValue("c").paused)
        assertEquals(FlowShadow.PAUSED, s.getValue("c").verdict)
        val e = FlowShadow.pauseEffects(s.values.toList()).single()
        assertEquals("traps", e.cause)
        assertEquals(1, e.would.trades); assertEquals(-900.0, e.would.net)
        assertEquals(1, e.rest.trades); assertEquals(1_240.0, e.rest.net)
        val line = FlowShadow.pauseLine(e)
        assertTrue(line.startsWith("ORB, trap alerts: would have paused signals with 1 trade, 0% won"), line)
        assertTrue(line.endsWith("(too few to judge)"))
        assertTrue(FlowShadow.brain("x", null, listOf("a,b"), listOf("p|q")).count { it == '|' } == 4)
    }
}
