package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "R51 Donchian 20" ([DonchianArm]): R44's `donchian` on the continuous 5-minute series (the previous session's bars count),
 * the 09:20-15:10 entry window, the 1-ITM strike, one position a side and 2 entries a side a day, the 2-minute fill slack,
 * and the exits as R51 tuned them.
 */
class DonchianArmTest {
    private val prev = LocalDate.of(2026, 10, 16)
    private val day = LocalDate.of(2026, 10, 19)
    private fun b5(d: LocalDate, b: Int, o: Double, h: Double, l: Double, c: Double) = PassedArms.Bar5(d, b, o, h, l, c)

    /** The previous session's last 20 bars flat at 100 (high 101, low 99), then [today]. */
    private fun series(today: List<PassedArms.Bar5>) = (55 until 75).map { b5(prev, it, 100.0, 101.0, 99.0, 100.0) } + today

    @Test fun aCloseAboveThePrevious20HighsBuysTheCallBelowTheLowsThePut() {
        val s = series(listOf(b5(day, 0, 100.0, 102.0, 99.5, 101.5), b5(day, 1, 101.0, 101.2, 98.0, 98.5), b5(day, 2, 99.0, 100.5, 99.0, 100.0)))
        assertEquals(1, DonchianArm.sideAt(s, 20))
        assertEquals(-1, DonchianArm.sideAt(s, 21))
        assertEquals(0, DonchianArm.sideAt(s, 22))
        // Fewer than 20 bars before, past the end, a missing price in the window or on the bar: nothing.
        assertEquals(0, DonchianArm.sideAt(s, 19)); assertEquals(0, DonchianArm.sideAt(s, 99))
        val nan = s.toMutableList().also { it[5] = b5(prev, 60, 100.0, Double.NaN, 99.0, 100.0) }
        assertEquals(0, DonchianArm.sideAt(nan, 20))
        val nanC = s.toMutableList().also { it[20] = b5(day, 0, 100.0, 102.0, 99.5, Double.NaN) }
        assertEquals(0, DonchianArm.sideAt(nanC, 20))
        // A close exactly at the high is not above it.
        assertEquals(0, DonchianArm.sideAt(series(listOf(b5(day, 0, 100.0, 101.0, 99.5, 101.0))), 20))
    }

    @Test fun todaysSignalsWithTheirEntryMinuteAndStrike() {
        val s = series(listOf(b5(day, 0, 25_000.0, 25_200.0, 24_990.0, 25_180.0)).map { it } + (1 until 75).map { b5(day, it, 25_100.0, 25_150.0, 25_050.0, 25_100.0) })
            .let { list -> list.map { if (it.date == prev) b5(prev, it.b, 25_000.0, 25_010.0, 24_990.0, 25_000.0) else it } }
        val sig = DonchianArm.signals("NIFTY", s, day)
        // 09:15-09:19 breaks up: the call, bought at 09:20, 1-ITM of its close (25,180 -> ATM 25,200 -> 25,150).
        assertEquals(1, sig.first().side)
        assertEquals(day.atTime(9, 20), sig.first().entryTime)
        assertEquals(25_150, sig.first().strike)
        assertTrue(sig.all { DonchianArm.inWindow(it.entryTime.toLocalTime()) })
        // Yesterday's bars are never today's signals.
        assertTrue(sig.all { it.bar.date == day })
    }

    @Test fun theWindowIsNineTwentyToThreeTen() {
        assertTrue(DonchianArm.inWindow(LocalTime.of(9, 20))); assertTrue(DonchianArm.inWindow(LocalTime.of(15, 10)))
        assertTrue(!DonchianArm.inWindow(LocalTime.of(9, 19)) && !DonchianArm.inWindow(LocalTime.of(15, 11)))
        // The bar closing 15:15 (b = 71) is never an entry; b = 70 (closing 15:10) is the last.
        val late = series(emptyList()) + (0 until 72).map { b -> when (b) { 70 -> b5(day, b, 100.0, 120.0, 99.0, 119.0); 71 -> b5(day, b, 100.0, 126.0, 99.0, 125.0); else -> b5(day, b, 100.0, 100.5, 99.5, 100.0) } }
        val sig = DonchianArm.signals("NIFTY", late, day)
        assertEquals(listOf(70), sig.map { it.bar.b })
        assertEquals(1, DonchianArm.sideAt(late, late.size - 1))
    }

    @Test fun onePositionASideAndTwoEntriesASideADay() {
        val s = DonchianArm.Signal("BANKNIFTY", 1, b5(day, 3, 1.0, 1.0, 1.0, 1.0), 53_400)
        assertNull(DonchianArm.mayTake(s, emptySet(), emptyMap()))
        assertTrue(DonchianArm.mayTake(s, setOf(1), emptyMap())!!.contains("one position a side"))
        // The put held does not stop the call.
        assertNull(DonchianArm.mayTake(s, setOf(-1), mapOf(-1 to 2, 1 to 1)))
        assertTrue(DonchianArm.mayTake(s, emptySet(), mapOf(1 to 2))!!.contains("2 entries a side"))
    }

    @Test fun theFillWaitsAtMostTwoMinutes() {
        val s = DonchianArm.Signal("NIFTY", -1, b5(day, 5, 1.0, 1.0, 1.0, 1.0), 25_000)
        assertEquals(day.atTime(9, 45), s.entryTime)
        assertTrue(DonchianArm.mayFill(s, day.atTime(9, 45)))
        assertTrue(DonchianArm.mayFill(s, day.atTime(9, 47, 59)))
        assertTrue(!DonchianArm.mayFill(s, day.atTime(9, 48)) && !DonchianArm.mayFill(s, day.atTime(9, 44, 59)))
    }

    @Test fun itsSettingsAreR51s() {
        assertEquals(PassedArms.PremiumExits(0.15, 0.30, 30, emptyList(), LocalTime.of(15, 15)), DonchianArm.EXITS)
        assertEquals(listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"), DonchianArm.UNDERLYINGS)
        assertEquals("FINNIFTY", DonchianArm.REPORT_APART)
        assertTrue(DonchianArm.ARM.paperOnly)
        assertEquals(20, DonchianArm.LOOKBACK); assertEquals(1, DonchianArm.MONEY); assertEquals(1, DonchianArm.LOTS); assertEquals(2, DonchianArm.MAX_PER_SIDE)
        for (w in listOf("previous 20 bars", "1-ITM", "09:20-15:10", "−15%", "+30%", "30 minutes", "15:15", "2 entries a side", "expiry day", "paper only"))
            assertTrue(w in DonchianArm.RULES, w)
        assertTrue("1,787 trades" in DonchianArm.RECORD && "+₹55,439" in DonchianArm.RECORD && "FINNIFTY" in DonchianArm.RECORD)
    }
}
