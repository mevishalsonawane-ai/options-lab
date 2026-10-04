package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoachTest {
    private val day = LocalDate.of(2026, 10, 5)

    @Test fun autoTrail() {
        assertNull(AutoTrail.next(100.0, 115.0, 85.0))                 // not up 20% yet
        assertEquals(102.0, AutoTrail.next(100.0, 120.0, 85.0))        // 15% under 120, above the price paid
        assertNull(AutoTrail.next(100.0, 121.0, 102.0))                // too small a step
        assertEquals(110.5, AutoTrail.next(100.0, 130.0, 102.0))
        assertNull(AutoTrail.next(100.0, 130.0, 115.0))                // never down
        assertEquals(102.0, AutoTrail.next(100.0, 120.0, null))
        assertTrue(AutoTrail.say("X", 100.0, 100.0).contains("can no longer lose"))
        assertTrue(AutoTrail.say("X", 110.5, 100.0).startsWith("Trailed the stop on X up to 110.50 ("))
    }

    @Test fun overtrading() {
        val now = day.atTime(11, 0)
        assertEquals(4, Overtrade.count(listOf(10, 40, 45, 55).map { now.minusMinutes(it.toLong() - 10) }.map { it.minusMinutes(0) }.let { l -> listOf(now.minusMinutes(1), now.minusMinutes(10), now.minusMinutes(20), now.minusMinutes(29)) }, now))
        assertNull(Overtrade.count(listOf(now.minusMinutes(1), now.minusMinutes(10), now.minusMinutes(40), now.minusMinutes(50)), now))
    }

    @Test fun lossSize() {
        assertNull(LossSize.check(listOf(100.0, 200.0, -100.0, -150.0, -120.0)))
        val w = LossSize.check(listOf(100.0, 200.0, -300.0, -400.0, -350.0))!!
        assertTrue(w.contains("average loss is Rs 350.00, the average win Rs 150.00 (2.3 times)"), w)
        assertNull(LossSize.check(listOf(-300.0, -400.0, -350.0, 100.0)))
    }

    @Test fun margin() {
        assertNull(MarginCheck.problem(20000.0, 10000.0, "Zerodha"))
        assertTrue(MarginCheck.problem(11000.0, 10000.0, "Zerodha")!!.contains("needs about Rs 12,000.00"))
        assertNull(MarginCheck.problem(null, 10000.0, "Zerodha"))
    }

    @Test fun preferences() {
        assertEquals("pattern hammer", Preference.kind("pattern: HAMMER|NIFTY|5|2026"))
        assertEquals("news", Preference.kind("news: RBI holds rates"))
        val many = (1..15).map { "news" to true } + (1..5).map { "pattern hammer" to false }
        assertTrue(Preference.skip("pattern hammer", many))
        assertFalse(Preference.skip("news", many))
        assertFalse(Preference.skip("pattern hammer", many.drop(5)))
        assertEquals(Command.Kind.PREF_RESET, Commands.parse("Jarvis, reset my preferences")?.kind)
    }

    @Test fun gapPlan() {
        assertEquals(GapPlan.Gap.UP, GapPlan.of(0.5)); assertEquals(GapPlan.Gap.FLAT, GapPlan.of(0.1)); assertEquals(GapPlan.Gap.DOWN, GapPlan.of(-0.4))
        val gaps = (0 until 6).associate { day.minusDays(it.toLong()) to 0.6 }
        val trades = (0 until 6).map { ArmHealth.T(day.minusDays(it.toLong()), "ORB 5", 100.0) }
        val m = GapPlan.arms(trades, gaps)
        assertEquals(listOf("ORB 5 +Rs 600.00 over 6 (6 won)"), m[GapPlan.Gap.UP])
        assertTrue(GapPlan.say(Market.NIFTY, 0.6, m.getValue(GapPlan.Gap.UP)).startsWith("Nifty opened +0.60%: a gap-up day. On past gap-up days: ORB 5"))
    }

    @Test fun oiShift() {
        val l = OiShift.say("NIFTY", OiShift.Walls(25000.0, 24500.0), OiShift.Walls(25200.0, 24500.0))
        assertEquals(listOf("NIFTY: the biggest call open interest moved from 25,000 to 25,200 - resistance moved up."), l)
        assertTrue(OiShift.say("NIFTY", OiShift.Walls(null, null), OiShift.Walls(1.0, 2.0)).isEmpty())
    }

    @Test fun positionTalk() {
        val s = PositionTalk.lines(PositionTalk.Pos("NIFTY24000CE", "Paper", 75, 100.0, 110.0, 85.0, 140.0, 125, 4.0))
        assertEquals("Paper NIFTY24000CE, 75 at 100.00, now 110.00: +Rs 750.00; 25.00 points to the stop at 85.00; 30.00 points to the target at 140.00; 2h 5m left; time decay about Rs 300.00 a day.", s)
        assertEquals(setOf(Section.EXPLAIN_POS), AppAnswers.sections("Jarvis, explain my BankNifty position"))
    }

    @Test fun summaryVoiceAndLock() {
        assertEquals("Day done, Boss. You made +Rs 100.00. Nothing on the calendar for tomorrow.", DaySummary.say("You made +Rs 100.00.", "No trades suggested today.", emptyList()))
        assertTrue(VoiceHealth.stuck(400_000, 100_000, false, true))
        assertFalse(VoiceHealth.stuck(400_000, 100_000, true, true))
        assertFalse(VoiceHealth.stuck(150_000, 100_000, false, true))
        assertTrue(LockRule.refuse(true, true, false, true)!!.contains("unlock"))
        assertTrue(LockRule.refuse(true, false, true, false)!!.contains("Unlock"))
        assertNull(LockRule.refuse(true, false, true, true))
        assertNull(LockRule.refuse(false, true, true, false))
    }
}
