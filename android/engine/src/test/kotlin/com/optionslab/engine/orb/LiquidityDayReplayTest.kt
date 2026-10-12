package com.optionslab.engine.orb

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiquidityDayReplayTest {
    private val day = LocalDate.of(2026, 10, 14)

    /** As LiquidityRulesTest's setup: flat 5-minute bars at 54,000 with a swing high / pool at 54,100 taken by the 13:00 bar. */
    private fun bars(n: Int = 47): List<Bar> {
        val j = 20
        return (0 until n).map { i ->
            val t = day.atTime(9, 15).plusMinutes(5L * i)
            when (i) {
                j -> Bar(t, 54_050.0, 54_100.0, 54_040.0, 54_060.0)
                j + 6 -> Bar(t, 54_040.0, 54_070.0, 54_020.0, 54_030.0)
                j + 25 -> Bar(t, 54_020.0, 54_150.0, 54_015.0, 54_140.0)
                else -> Bar(t, 54_000.0, 54_010.0, 53_990.0, 54_000.0)
            }
        }
    }

    @Test fun theDaysSignalsAsTheAppDecidesThemBarByBar() {
        val s = LiquidityDayReplay.signals(LiquidityRules.ARM5, bars(), day)
        val first = s.first()
        assertEquals(day.atTime(13, 0), first.bar)
        assertEquals(1, first.side)
        assertEquals(54_000, first.strike, "one strike in the money")
        assertEquals(54_100.0, first.level)
        // Another day: nothing; too few bars for the levels: nothing.
        assertTrue(LiquidityDayReplay.signals(LiquidityRules.ARM5, bars(), day.plusDays(1)).isEmpty())
        assertTrue(LiquidityDayReplay.signals(LiquidityRules.ARM5, bars().take(30), day).isEmpty())
    }

    @Test fun outsideTheEntryHoursNothingIsDecided() {
        // The same shape shifted so the break comes at 14:30: past the last entry.
        val late = bars().map { it.copy(start = it.start.plusMinutes(90)) }
        assertTrue(LiquidityDayReplay.signals(LiquidityRules.ARM5, late, day).none { it.bar.toLocalTime() > java.time.LocalTime.of(14, 0) })
    }
}
