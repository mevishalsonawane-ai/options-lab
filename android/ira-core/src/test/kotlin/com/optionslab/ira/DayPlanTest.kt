package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayPlanTest {
    private val d0 = LocalDate.of(2026, 6, 1)
    private val side = Regime.Kind.SIDEWAYS
    private val up = Regime.Kind.UP

    /** "orb5" lost on 6 sideways days and won on 6 up days; "sweep" traded twice. */
    private val record = run {
        val regimes = (0 until 14).associate { d0.plusDays(it.toLong()) to if (it < 6) side else up }
        val trades = (0 until 12).map { i -> ArmHealth.T(d0.plusDays(i.toLong()), "orb5", if (i < 6) -500.0 else 800.0) } +
            listOf(ArmHealth.T(d0, "sweep", -100.0), ArmHealth.T(d0.plusDays(1), "sweep", -100.0))
        DayPlan.record(trades, regimes)
    }

    @Test fun anArmThatLosesInThisRegimeIsParkedOnPaperOnly() {
        assertEquals(-3000.0 to 6, record.getValue("orb5").getValue(side))
        val steps = DayPlan.plan(listOf(DayPlan.ArmNow("orb5", "ORB 5", armed = true, paper = true, parked = false)), record, side)
        assertEquals(listOf(false), steps.map { it.on })
        assertTrue(DayPlan.say(steps, side)!!.contains("I parked ORB 5 (on sideways days it made"))
        // Trading Zerodha: never touched.
        assertTrue(DayPlan.plan(listOf(DayPlan.ArmNow("orb5", "ORB 5", armed = true, paper = false, parked = false)), record, side).isEmpty())
        // Too few trades to judge: left as it is.
        assertTrue(DayPlan.plan(listOf(DayPlan.ArmNow("sweep", "Sweep", armed = true, paper = true, parked = false)), record, side).isEmpty())
    }

    @Test fun onlyAnArmJarvisParkedIsArmedAgainAndBossWins() {
        val parked = DayPlan.ArmNow("orb5", "ORB 5", armed = false, paper = true, parked = true)
        assertEquals(listOf(true), DayPlan.plan(listOf(parked), record, up).map { it.on })
        assertTrue(DayPlan.plan(listOf(parked), record, side).isEmpty())                       // still a losing regime
        // Never arms what Boss left off.
        assertTrue(DayPlan.plan(listOf(parked.copy(parked = false)), record, up).isEmpty())
        // Boss re-armed it in this regime: left alone.
        assertTrue(DayPlan.plan(listOf(DayPlan.ArmNow("orb5", "ORB 5", armed = true, paper = true, parked = false, kept = side)), record, side).isEmpty())
        assertNull(DayPlan.say(emptyList(), up))
    }
}
