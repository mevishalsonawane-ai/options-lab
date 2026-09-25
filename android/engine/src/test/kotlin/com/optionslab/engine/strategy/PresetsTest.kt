package com.optionslab.engine.strategy

import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetsTest {
    private val day = LocalDate.of(2026, 9, 22)
    private val expiry = LocalDate.of(2026, 9, 23)
    private val mins = intArrayOf(555, 556, 557, 558, 559, 560)   // 09:15 .. 09:20

    private fun s(strike: Double, right: Right, vararg px: Double) =
        Series(if (right == Right.IX) null else expiry, strike, right, 75, mins, px, null, null, null, null, LongArray(px.size))

    /** Spot 100, strikes 90..110 step 5; calls gain and puts lose from 09:18. */
    private fun session(): Session {
        val out = arrayListOf(s(0.0, Right.IX, 100.0, 100.0, 100.0, 101.0, 102.0, 103.0))
        for (k in listOf(90.0, 95.0, 100.0, 105.0, 110.0)) {
            val c = maxOf(1.0, 100.0 - k + 5.0); val p = maxOf(1.0, k - 100.0 + 5.0)
            out += s(k, Right.CE, c, c, c, c + 1, c + 2, c + 3)
            out += s(k, Right.PE, p, p, p, p - 0.5, p - 1, p - 1.5)
        }
        return Session(day, 75, out)
    }

    @Test fun presetDefinitionsPassTheValidator() {
        for (p in Presets.ALL) for (u in listOf("NIFTY", "BANKNIFTY")) {
            val def = Presets.def(p, u, 2, LocalTime.of(9, 20), LocalTime.of(15, 15), 3000.0, 2000.0)
            val r = StrategyValidator.check(def)
            assertTrue(r is StrategyValidator.Result.Ok, "${p.id} $u: $r")
        }
    }

    @Test fun straddleReplayMarksTheBasketAndPaysCharges() {
        val d = assertNotNull(Presets.day(Presets.byId("short_straddle")!!, session(), 75, 1, 557, 560, null, null))
        assertEquals(listOf(100.0, 100.0), d.strikes)
        assertEquals(10.0, d.credit, 1e-9)                          // 5 + 5
        assertEquals("Time", d.reason)
        // Call 5 -> 8 (short: -3), put 5 -> 3.5 (short: +1.5): -1.5 x 75
        assertEquals(-112.5, d.gross, 1e-9)
        assertTrue(d.charges > 80.0 && d.net < d.gross)
    }

    @Test fun stopAndTargetEndTheDayEarly() {
        val fly = Presets.byId("short_straddle")!!
        val stopped = Presets.day(fly, session(), 75, 1, 557, 560, 50.0, null)!!
        assertEquals("Stop", stopped.reason)
        assertEquals(559, stopped.exitMinute)                      // -0.5 x 75 = -37.5 at 558, -75 at 559
        val strangle = Presets.day(Presets.byId("short_strangle")!!, session(), 75, 1, 557, 560, null, 1.0)
        assertNotNull(strangle)
        assertEquals(listOf(110.0, 90.0), strangle.strikes)
    }

    @Test fun offTheLadderOrNotTradingSkipsTheDay() {
        // Iron condor wants four strikes out; the ladder has two.
        assertNull(Presets.day(Presets.byId("iron_condor")!!, session(), 75, 1, 557, 560, null, null))
        val r = Presets.backtest(Presets.byId("iron_condor")!!, sequenceOf(session()), { 75 }, 1, 557, 560, null, null)
        assertEquals(0, r.days.size); assertEquals(1, r.skipped)
        // No index bar near the entry minute.
        assertNull(Presets.day(Presets.byId("short_straddle")!!, session(), 75, 1, 600, 620, null, null))
    }
}
