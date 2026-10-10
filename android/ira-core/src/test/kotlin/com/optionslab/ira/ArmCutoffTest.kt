package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArmCutoffTest {
    private fun arm(name: String, n: Int, each: Double, armed: Boolean = true) = ArmCutoff.Arm(name, armed, List(n) { each })

    @Test fun fifteenClosedPaperTradesWithANegativeNetAreAsked() {
        assertTrue(ArmCutoff.due(arm("ORB", 15, -10.0)))
        assertFalse(ArmCutoff.due(arm("ORB", 14, -100.0)), "14 trades are too few")
        assertFalse(ArmCutoff.due(arm("ORB", 30, 10.0)), "a positive net is kept")
        assertFalse(ArmCutoff.due(ArmCutoff.Arm("ORB", true, List(15) { 0.0 })), "exactly even is not negative")
        assertFalse(ArmCutoff.due(arm("ORB", 30, -10.0, armed = false)), "already off: nothing to ask")
        // Charges count: gross wins that net a loss after charges are a loss.
        assertTrue(ArmCutoff.due(ArmCutoff.Arm("Range Fade", true, List(14) { 15.0 } + (-300.0))))
    }

    @Test fun liquidityIsAskedAboutOnlyFromFortyTrades() {
        assertEquals(40, ArmCutoff.LIQUIDITY_MIN_TRADES)
        val losing = List(39) { -10.0 }
        assertFalse(ArmCutoff.due(ArmCutoff.Arm("Liquidity 15+5", true, losing, ArmCutoff.LIQUIDITY_MIN_TRADES)), "39 trades since 06 Oct are too few")
        assertTrue(ArmCutoff.due(ArmCutoff.Arm("Liquidity 15+5", true, losing + (-10.0), ArmCutoff.LIQUIDITY_MIN_TRADES)))
        assertTrue(ArmCutoff.due(ArmCutoff.Arm("ORB", true, List(15) { -10.0 })), "every other arm keeps the 15")
    }

    @Test fun theDeepestLossIsAskedFirst() {
        val due = ArmCutoff.dueOf(listOf(arm("A", 20, -5.0), arm("B", 20, -50.0), arm("C", 20, 5.0), arm("D", 3, -500.0)))
        assertEquals(listOf("B", "A"), due.map { it.name })
    }

    @Test fun theQuestionAndTheLogLineSayTheRecord() {
        val a = ArmCutoff.Arm("Pine #4 Jarvis: bullish engulfing Nifty 15m", true, List(15) { if (it < 6) 300.0 else -250.0 })
        assertEquals("Boss, Pine #4 Jarvis: bullish engulfing Nifty 15m has 15 closed paper trades, 6 won, -Rs 450 net after charges. " +
            "Shall I switch Pine #4 Jarvis: bullish engulfing Nifty 15m off? Any open position is still managed to its exit; " +
            "I never switch it back on - that stays yours.", ArmCutoff.ask(a))
        assertEquals("Pine #4 Jarvis: bullish engulfing Nifty 15m switched off: paper record negative (15 trades, -Rs 450 after charges).", ArmCutoff.done(a))
        assertTrue(ArmCutoff.ask(arm("X", 15, 10.0)).contains("+Rs 150"))
    }
}
