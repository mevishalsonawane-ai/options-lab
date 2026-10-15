package com.optionslab.engine.signals

import com.optionslab.engine.signals.CallParser.Trig
import com.optionslab.engine.signals.SignalPaperRules.Exit
import com.optionslab.engine.signals.SignalPaperRules.Phase
import com.optionslab.engine.signals.SignalPaperRules.Settings
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Paper-trading a chat's calls ([SignalPaperRules], Boss's choice - not proven, paper only): the delay and the 15-minute window,
 * the trigger, the stop (the call's, else our -20%), the exit rule's target, half lots, the daily caps, the square-off; and the
 * chat picker's counts ([ChatScan]) with its "looks like a trade-call channel" badge.
 */
class SignalPaperRulesTest {
    private val day = LocalDate.of(2026, 10, 15)
    private fun t(h: Int, m: Int, s: Int = 0): LocalDateTime = day.atTime(h, m, s)
    private val s = Settings()

    @Test fun theDelayTheWindowAndTheSquareOff() {
        val msg = t(9, 28, 4)
        assertEquals(Phase.WAIT, SignalPaperRules.phase(msg, t(9, 28, 20), s, false))
        assertEquals(Phase.OPEN, SignalPaperRules.phase(msg, t(9, 28, 34), s, false))
        assertEquals(Phase.OPEN, SignalPaperRules.phase(msg, t(9, 43, 3), s, false))
        assertEquals(Phase.LATE, SignalPaperRules.phase(msg, t(9, 43, 4), s, false))
        assertEquals(Phase.LATE, SignalPaperRules.phase(msg, t(9, 30).plusDays(1), s, false))
        // Before the open: from 09:15; near the expiry square-off: the window ends at 15:05.
        assertEquals(Phase.WAIT, SignalPaperRules.phase(t(8, 50), t(9, 15, 10), s, false))
        assertEquals(Phase.LATE, SignalPaperRules.phase(t(15, 0), t(15, 5), s, true))
        assertEquals(Phase.OPEN, SignalPaperRules.phase(t(15, 0), t(15, 5), s, false))
        assertEquals(LocalTime.of(15, 5), SignalPaperRules.squareOff(true)); assertEquals(LocalTime.of(15, 15), SignalPaperRules.squareOff(false))
        assertEquals(Phase.OPEN, SignalPaperRules.phase(msg, t(9, 28, 5), s.copy(delaySec = 0), false))
    }

    @Test fun theTrigger() {
        assertTrue(SignalPaperRules.triggered(Trig.ABOVE, 280.0, null, 281.0, null, null))
        assertTrue(SignalPaperRules.triggered(Trig.ABOVE, 280.0, null, 275.0, 280.0, 270.0))
        assertFalse(SignalPaperRules.triggered(Trig.ABOVE, 280.0, null, 275.0, 279.0, 270.0))
        assertFalse(SignalPaperRules.triggered(Trig.ABOVE, null, null, 300.0, null, null))
        assertTrue(SignalPaperRules.triggered(Trig.ZONE, 280.0, null, 284.0, null, null))
        assertTrue(SignalPaperRules.triggered(Trig.ZONE, 300.0, 305.0, 320.0, 330.0, 309.0))
        assertFalse(SignalPaperRules.triggered(Trig.ZONE, 280.0, null, 300.0, 310.0, 290.0))
        assertTrue(SignalPaperRules.triggered(Trig.CMP, null, null, 5.0, null, null))
        assertFalse(SignalPaperRules.triggered(Trig.CMP, null, null, null, null, null))
    }

    @Test fun theStopTargetsHalfLotsAndCaps() {
        assertEquals(250.0 to false, SignalPaperRules.stop(250.0, 282.0, s))
        assertEquals(225.6 to true, SignalPaperRules.stop(null, 282.0, s))
        // A given stop at or above the fill is no stop: ours.
        assertEquals(225.6 to true, SignalPaperRules.stop(290.0, 282.0, s))
        assertEquals(0.05 to true, SignalPaperRules.stop(null, 0.05, s))
        val tg = listOf(310.0, 345.0, 400.0)
        assertEquals(310.0, SignalPaperRules.target(Exit.T1_ALL, tg)); assertEquals(310.0, SignalPaperRules.target(Exit.HALF_COST, tg))
        assertEquals(400.0, SignalPaperRules.target(Exit.HOLD_LAST, tg)); assertNull(SignalPaperRules.target(Exit.T1_ALL, emptyList()))
        assertEquals(0, SignalPaperRules.halfLots(1)); assertEquals(1, SignalPaperRules.halfLots(3))
        assertNull(SignalPaperRules.refusal(2, -2_999.0, s))
        assertEquals("3 paper trades today already", SignalPaperRules.refusal(3, 0.0, s))
        assertTrue(SignalPaperRules.refusal(1, -3_000.0, s)!!.startsWith("paused for today"))
        assertEquals(Exit.HALF_COST, Exit.of("half")); assertEquals(Exit.T1_ALL, Exit.of(null))
        assertEquals(279.95, SignalPaperRules.tickDown(279.97), 1e-9)
    }

    @Test fun theChatPickersCountsAndBadge() {
        val calls = List(6) { "NIFTY 25000 CE above 1$it sl 90 tgt 140" to day }
        val chatter = List(4) { "good morning friends" to day } + listOf("T1 done ✅" to day)
        val st = ChatScan.scan(chatter + calls)
        assertEquals(ChatScan.Stats(11, 6, 1), st)
        assertTrue(st.looksLikeCalls); assertEquals("6 of 11 recent messages looked like calls (1 follow-ups)", st.line)
        val family = ChatScan.scan(List(50) { "dinner at 8?" to day } + calls)
        assertFalse(family.looksLikeCalls)
        assertEquals(0.0, ChatScan.scan(emptyList()).share)
        assertEquals("0 of 0 recent messages looked like calls", ChatScan.scan(emptyList()).line)
        assertEquals(ChatScan.RECENT, ChatScan.scan(List(150) { "hi" to day }).messages)
    }
}
