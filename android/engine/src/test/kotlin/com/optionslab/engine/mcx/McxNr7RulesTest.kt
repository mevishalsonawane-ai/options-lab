package com.optionslab.engine.mcx

import com.optionslab.engine.mcx.McxNr7Rules.Day
import com.optionslab.engine.mcx.McxNr7Rules.Held
import com.optionslab.engine.mcx.McxNr7Rules.Minute
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NR7 crude, record only (21 Oct; research R96 R3A): the NR7 day, the break, the 1-ITM strike, the exits in the research's order. */
class McxNr7RulesTest {
    private val d0 = LocalDate.of(2026, 10, 12)
    private fun day(i: Int, range: Double, mid: Double = 6000.0) = Day(d0.plusDays(i.toLong()), mid + range / 2, mid - range / 2, mid + range / 3, mid - range / 3)
    private val strikes = (5800..6200 step 50).map { it.toDouble() }

    @Test fun nr7NeedsSevenSessionsAndTheNarrowestRange() {
        val wide = (0 until 6).map { day(it, 100.0 + it) }
        assertTrue(McxNr7Rules.isNr7(wide + day(6, 50.0)))
        assertFalse(McxNr7Rules.isNr7(wide + day(6, 100.0)), "equal to the narrowest earlier range is not narrower")
        assertFalse(McxNr7Rules.isNr7(wide.drop(1) + day(6, 50.0)), "only six sessions")
        assertFalse(McxNr7Rules.isNr7(wide + day(6, 0.0)), "a flat (bad) day")
        assertFalse(McxNr7Rules.isNr7(emptyList()))
        // Only the last 7 count: an older, narrower day does not stop today's NR7.
        assertTrue(McxNr7Rules.isNr7(listOf(day(-1, 10.0)) + wide + day(6, 50.0)))
    }

    @Test fun aDayFromItsMinutesUsesTheRangeAndTheCloses() {
        val d = McxNr7Rules.day(d0, listOf(Minute(600, 100.0, 105.0, 95.0, 101.0), Minute(601, 101.0, 104.0, 99.0, 103.0),
            Minute(602, 0.0, Double.NaN, 0.0, 0.0)))!!
        assertEquals(105.0, d.high); assertEquals(95.0, d.low); assertEquals(103.0, d.highClose); assertEquals(101.0, d.lowClose)
        assertEquals(10.0, d.range)
        assertNull(McxNr7Rules.day(d0, emptyList()))
    }

    @Test fun theBreakIsTheFirstCloseBeyondInTheWindow() {
        val nr7 = Day(d0, 6010.0, 5990.0, 6005.0, 5995.0)
        fun m(t: Int, c: Double) = Minute(t, c, c, c, c)
        assertNull(McxNr7Rules.signal(nr7, listOf(m(9 * 60 + 5, 6100.0), m(9 * 60 + 20, 6000.0))), "before 09:15 does not count")
        assertEquals(McxNr7Rules.Signal(1, 600, 6006.0), McxNr7Rules.signal(nr7, listOf(m(599, 6000.0), m(600, 6006.0), m(601, 5900.0))))
        assertEquals(-1, McxNr7Rules.signal(nr7, listOf(m(700, 5994.0)))!!.side)
        assertNull(McxNr7Rules.signal(nr7, listOf(m(22 * 60 + 1, 6100.0))), "after 22:00 does not count")
        assertNull(McxNr7Rules.signal(nr7, listOf(m(700, 6005.0))), "at the level is not beyond it")
        assertEquals(5995.0, McxNr7Rules.futureStop(nr7, 1)); assertEquals(6005.0, McxNr7Rules.futureStop(nr7, -1))
    }

    @Test fun oneInTheMoneyAndTheExpiryRule() {
        assertEquals(5950.0, McxNr7Rules.strike(strikes, 6010.0, 1))      // ATM 6000 -> call one below
        assertEquals(6050.0, McxNr7Rules.strike(strikes, 6010.0, -1))     // put one above
        assertNull(McxNr7Rules.strike(strikes, 5790.0, 1))                // off the chain
        assertTrue(McxNr7Rules.expiryOk(false, 3))
        assertFalse(McxNr7Rules.expiryOk(false, 2))
        assertFalse(McxNr7Rules.expiryOk(true, 10))
    }

    @Test fun theEdgeIsThreeStrikesInTheMoney() {
        assertFalse(McxNr7Rules.atEdge(strikes, 6000.0, 5950.0, 1))
        assertFalse(McxNr7Rules.atEdge(strikes, 6050.0, 5950.0, 1))       // ATM 6050: two strikes in the money
        assertTrue(McxNr7Rules.atEdge(strikes, 6100.0, 5950.0, 1))        // ATM 6100: 5950 is three below (the ATM moved two strikes)
        assertTrue(McxNr7Rules.atEdge(strikes, 6110.0, 5950.0, 1))
        assertTrue(McxNr7Rules.atEdge(strikes, 6200.0, 5950.0, 1))
        assertTrue(McxNr7Rules.atEdge(strikes, 5900.0, 6050.0, -1))
        assertFalse(McxNr7Rules.atEdge(strikes, 5950.0, 6050.0, -1))
        assertFalse(McxNr7Rules.atEdge(emptyList(), 6000.0, 5950.0, 1))
    }

    private val h = Held(1, 5950.0, 100.0, 102.0, 5995.0)
    private fun o(open: Double, high: Double, low: Double, close: Double) = Minute(700, open, high, low, close)

    @Test fun theStopFillsAtItsLevelOrTheGapOpen() {
        assertEquals(McxNr7Rules.Exit("stop", 60.0), McxNr7Rules.step(h, o(70.0, 70.0, 55.0, 58.0), 6000.0, strikes).first)
        assertEquals(McxNr7Rules.Exit("stop", 50.0), McxNr7Rules.step(h, o(50.0, 52.0, 48.0, 49.0), 6000.0, strikes).first)
    }

    @Test fun theLadderLocksAndTrails() {
        val (_, a) = McxNr7Rules.step(h, o(100.0, 131.0, 100.0, 130.0), 6000.0, strikes)
        assertEquals(102.0, a.lock)                                          // +31% peak: breakeven
        val (_, b) = McxNr7Rules.step(a, o(130.0, 165.0, 130.0, 160.0), 6000.0, strikes)
        assertEquals(130.0, b.lock, 1e-9)                                    // +65%: +30%
        val (_, c) = McxNr7Rules.step(b, o(160.0, 250.0, 160.0, 240.0), 6000.0, strikes)
        assertEquals(210.0, c.lock, 1e-9)                                    // +150%: trail 40% behind = +110%
        assertEquals(McxNr7Rules.Exit("lock", 210.0), McxNr7Rules.step(c, o(230.0, 230.0, 200.0, 205.0), 6000.0, strikes).first)
        // Never lowered.
        assertEquals(210.0, McxNr7Rules.lockAfter(c, 120.0), 1e-9)
    }

    @Test fun theOrderIsStopThenFutureThenEdge() {
        // Low through the stop and the future through its stop in the same minute: the premium stop goes first.
        assertEquals("stop", McxNr7Rules.step(h, o(70.0, 70.0, 55.0, 58.0), 5990.0, strikes).first!!.why)
        // The future back beyond the NR7 day's other extreme: out at the option's close.
        assertEquals(McxNr7Rules.Exit("future_stop", 90.0), McxNr7Rules.step(h, o(95.0, 96.0, 89.0, 90.0), 5995.0, strikes).first)
        // No print that minute: the exit is known, its price comes from the host (NaN here).
        assertTrue(McxNr7Rules.step(h, null, 5990.0, strikes).first!!.price.isNaN())
        // The edge: out at the option's close.
        assertEquals(McxNr7Rules.Exit("edge", 240.0), McxNr7Rules.step(h, o(230.0, 245.0, 225.0, 240.0), 6110.0, strikes).first)
        // Nothing: the walk goes on, the peak and the lock updated only from a print.
        val (e, same) = McxNr7Rules.step(h, null, 6000.0, strikes)
        assertNull(e); assertEquals(h, same)
    }

    @Test fun theSquareOffAndTheSpreads() {
        assertEquals(23 * 60 + 14, McxNr7Rules.squareOff(23 * 60 + 30))
        assertEquals(0.006, McxNr7Rules.half(16 * 60 + 59)); assertEquals(0.003, McxNr7Rules.half(17 * 60))
        assertEquals(listOf("CRUDEOIL", "CRUDEOILM"), McxNr7Rules.UNDERLYINGS)
        assertTrue(McxNr7Rules.RULES.contains("RECORD ONLY") && McxNr7Rules.RECORD.contains("NOT"))
    }
}
