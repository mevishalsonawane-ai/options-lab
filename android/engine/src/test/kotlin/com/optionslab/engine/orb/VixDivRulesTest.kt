package com.optionslab.engine.orb

import com.optionslab.engine.IST
import com.optionslab.engine.Upstox
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** research/HUNT_R1_INTERNET.md N13 (signals.py n13_vixdiv, lib.py run_engine): checks, thresholds, first signal only, strikes, exits. */
class VixDivRulesTest {
    private val day = LocalDate.of(2026, 10, 8)
    private fun m(h: Int, mm: Int) = h * 60 + mm

    private fun bar(minute: Int, close: Double, open: Double = close) =
        Upstox.Bar(day.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(IST).toEpochSecond(), open, close, close, close, 10, 0)

    /** Every minute from 09:15 to [until] (exclusive), each closing at [px] of its label. */
    private fun day(until: Int, open: Double = 25_000.0, px: (Int) -> Double): List<Upstox.Bar> =
        (m(9, 15) until until).map { t -> bar(t, px(t), if (t == m(9, 15)) open else px(t)) }

    /** India VIX's minutes from 09:15 to [until] (exclusive): the 09:15 minute closes at [first] (its open), the rest at [px]. */
    private fun vday(until: Int, first: Double = 12.0, px: (Int) -> Double): List<Upstox.Bar> =
        (m(9, 15) until until).map { t -> bar(t, if (t == m(9, 15)) first else px(t)) }

    @Test fun theRecordAndRulesSayWhatTheResearchFound() {
        assertEquals("VIX divergence (not proven)", VixDivRules.LABEL)
        for (w in listOf("+₹67/day NIFTY", "+₹89/day BANKNIFTY", "+₹70/day (29 trades) NIFTY", "+₹112/day (45 trades)", "q 1.0", "~150 paper trades"))
            assertTrue(w in VixDivRules.RECORD, w)
        for (w in listOf("10:30, 11:30, 12:30 and 13:30", "first signal of the day", "0.2%", "2%", "1-ITM put", "1-ITM call", "15:10", "−15%", "20 min"))
            assertTrue(w in VixDivRules.RULES, w)
        assertEquals(listOf(m(10, 30), m(11, 30), m(12, 30), m(13, 30)), VixDivRules.CHECKS)
        assertEquals(150, VixDivRules.PAPER_TRADES_WANTED)
        assertEquals(listOf("NIFTY", "BANKNIFTY"), VixDivRules.UNDERLYINGS)
        assertEquals(1, VixDivRules.LOTS)
    }

    @Test fun theSideNeedsBothMovesBeyondTheirThresholds() {
        // Index up and VIX up: the put. Both down: the call (signals.py's mirror).
        assertEquals(-1, VixDivRules.side(0.0021, 0.021))
        assertEquals(1, VixDivRules.side(-0.0021, -0.021))
        // At the threshold exactly: nothing (strictly beyond).
        assertEquals(0, VixDivRules.side(0.002, 0.03))
        assertEquals(0, VixDivRules.side(0.003, 0.02))
        assertEquals(0, VixDivRules.side(-0.002, -0.03))
        assertEquals(0, VixDivRules.side(-0.003, -0.02))
        // Index up while VIX falls (or the reverse): no divergence, nothing.
        assertEquals(0, VixDivRules.side(0.005, -0.05))
        assertEquals(0, VixDivRules.side(-0.005, 0.05))
        assertEquals(0, VixDivRules.side(Double.NaN, 0.05))
        assertEquals(0, VixDivRules.side(0.005, Double.POSITIVE_INFINITY))
    }

    @Test fun eachCheckReadsTheMinuteThatClosedThenAgainstTheOpens() {
        // NIFTY opens 25,000 (the 09:15 open), +0.25% from 10:00; VIX's first minute closes 12.00, +2.5% from 10:00.
        val idx = day(m(10, 31), open = 25_000.0) { t -> if (t >= m(10, 0)) 25_062.5 else 25_010.0 }
        val vix = vday(m(10, 31)) { t -> if (t >= m(10, 0)) 12.30 else 12.0 }
        assertEquals(25_000.0, VixDivRules.indexOpen(idx))
        assertEquals(12.0, VixDivRules.vixOpen(vix))
        // Before 10:30 nothing is read.
        assertTrue(VixDivRules.readings(idx, vix, m(10, 29)).isEmpty())
        val s = VixDivRules.firstSignal(idx, vix, m(10, 30))
        assertNotNull(s)
        assertEquals(m(10, 30), s.check)
        assertEquals(-1, s.side, "up and VIX up: the put")
        assertEquals(0.0025, s.indexMove, 1e-12)
        assertEquals(0.025, s.vixMove, 1e-12)
        assertEquals(m(10, 33), s.entryUntil)
        assertTrue(VixDivRules.inEntryWindow(s, m(10, 30)))
        assertTrue(VixDivRules.inEntryWindow(s, m(10, 32)))
        assertFalse(VixDivRules.inEntryWindow(s, m(10, 33)), "three minutes, then the day is let go")
        assertFalse(VixDivRules.inEntryWindow(s, m(10, 29)))
    }

    @Test fun theCheckReadsTheBarLabelledTheMinuteBeforeAndForwardFills() {
        // Only the 10:29 bar moves: the 10:30 check sees it. The 10:30 bar itself is not read by the 10:30 check.
        val idx = day(m(10, 31)) { t -> if (t == m(10, 29)) 25_100.0 else 25_000.0 }
        val vix = day(m(10, 31)) { t -> if (t == m(10, 29)) 12.5 else 12.0 }
        assertEquals(-1, VixDivRules.firstSignal(idx, vix, m(10, 30))!!.side)
        val late = day(m(10, 31)) { t -> if (t == m(10, 30)) 25_100.0 else 25_000.0 }
        val lateVix = day(m(10, 31)) { t -> if (t == m(10, 30)) 12.5 else 12.0 }
        assertNull(VixDivRules.firstSignal(late, lateVix, m(10, 31)))
        // A gap before 10:29: the last close printed is used (forward fill).
        val gappy = day(m(10, 0)) { 24_900.0 }
        val gappyVix = vday(m(10, 0)) { 11.7 }
        val r = VixDivRules.readings(gappy, gappyVix, m(10, 30)).single()
        assertEquals(24_900.0, r.indexClose)
        assertEquals(1, r.side, "down 0.4% and VIX down 2.5%: the call")
        assertNull(VixDivRules.closeAt(emptyList(), m(10, 29)))
    }

    @Test fun onlyTheFirstSignalOfTheDayCounts() {
        // 10:30 fires the call; 11:30 would fire the put: the call is the day's signal, whatever the clock.
        val idx = day(m(11, 31)) { t -> if (t < m(11, 0)) 24_900.0 else 25_100.0 }
        val vix = vday(m(11, 31)) { t -> if (t < m(11, 0)) 11.7 else 12.5 }
        val s = VixDivRules.firstSignal(idx, vix, m(11, 31))!!
        assertEquals(m(10, 30), s.check)
        assertEquals(1, s.side)
        // Quiet at 10:30, fires at 12:30.
        val later = day(m(12, 31)) { t -> if (t < m(12, 0)) 25_000.0 else 25_100.0 }
        val laterVix = vday(m(12, 31)) { t -> if (t < m(12, 0)) 12.0 else 12.5 }
        assertNull(VixDivRules.firstSignal(later, laterVix, m(11, 45)))
        assertEquals(m(12, 30), VixDivRules.firstSignal(later, laterVix, m(12, 30))!!.check)
        assertEquals(3, VixDivRules.readings(later, laterVix, m(12, 30)).size)
        // While a check's own minute has not printed yet, it waits (inside its buying minutes only).
        val upTo1028 = later.filter { it.istMinute < m(10, 29) }
        assertEquals(m(10, 30), VixDivRules.awaiting(upTo1028, laterVix, m(10, 30)))
        assertEquals(m(10, 30), VixDivRules.awaiting(later, laterVix.filter { it.istMinute < m(10, 29) }, m(10, 32)))
        assertNull(VixDivRules.awaiting(later, laterVix, m(10, 30)), "both minutes printed")
        assertNull(VixDivRules.awaiting(upTo1028, laterVix, m(10, 33)), "past its buying minutes")
        assertNull(VixDivRules.awaiting(upTo1028, laterVix, m(10, 29)), "before the first check")
        // The day is over after the 13:30 check's minutes.
        assertFalse(VixDivRules.dayOver(m(13, 32)))
        assertTrue(VixDivRules.dayOver(m(13, 33)))
    }

    @Test fun withoutTheOpensNothingIsRead() {
        val idx = day(m(10, 31)) { 25_100.0 }.filter { it.istMinute != m(9, 15) }
        val vix = day(m(10, 31)) { 12.5 }
        assertNull(VixDivRules.indexOpen(idx))
        assertTrue(VixDivRules.readings(idx, vix, m(10, 30)).isEmpty())
        assertTrue(VixDivRules.readings(day(m(10, 31)) { 25_100.0 }, emptyList(), m(10, 30)).isEmpty())
        assertNull(VixDivRules.vixOpen(emptyList()))
        // VIX's first minute only at 10:45: that is its open, and the 10:30 check (no VIX close yet) is passed over.
        val vixLate = (m(10, 45) until m(11, 31)).map { bar(it, 12.5) }
        val idxAll = day(m(11, 31)) { 25_100.0 }
        val rs = VixDivRules.readings(idxAll, vixLate, m(11, 30))
        assertEquals(listOf(m(11, 30)), rs.map { it.check })
        // A price of 0 is no price.
        assertNull(VixDivRules.indexOpen(listOf(bar(m(9, 15), 25_000.0, open = 0.0))))
        assertNull(VixDivRules.vixOpen(listOf(bar(m(9, 15), 0.0))))
        assertNull(VixDivRules.closeAt(listOf(bar(m(9, 20), 0.0)), m(10, 29)))
        // An index with no minute at all after its open still reads the 09:15 close (forward fill).
        val idxOpenOnly = listOf(bar(m(9, 15), 25_100.0, open = 25_000.0))
        val vixNoMinute = listOf(bar(m(11, 0), 12.5))
        assertTrue(VixDivRules.readings(idxOpenOnly, vixNoMinute, m(10, 30)).isEmpty(), "no VIX close to 10:29")
    }

    @Test fun theStrikeIsOneStepInTheMoneyFromTheRoundedClose() {
        assertEquals(25_050, VixDivRules.atm(25_049.0, "NIFTY"))
        // Half to even, as Python's round: 25,025 -> 25,000 (500.5 -> 500), 25,075 -> 25,100 (501.5 -> 502).
        assertEquals(25_000, VixDivRules.atm(25_025.0, "NIFTY"))
        assertEquals(25_100, VixDivRules.atm(25_075.0, "NIFTY"))
        assertEquals(24_950, VixDivRules.strike(25_010.0, "NIFTY", 1), "the call one step below the 25,000 ATM")
        assertEquals(25_050, VixDivRules.strike(25_010.0, "NIFTY", -1), "the put one step above")
        assertEquals(56_000, VixDivRules.strike(56_120.0, "BANKNIFTY", 1))
        assertEquals(56_200, VixDivRules.strike(56_120.0, "BANKNIFTY", -1))
        assertEquals(50, VixDivRules.step("NIFTY")); assertEquals(100, VixDivRules.step("BANKNIFTY"))
    }

    @Test fun theRefusalsComeInOrder() {
        assertEquals("kill_switch", VixDivRules.entryRefusal(kill = true, stoppedToday = true, dayLock = "x", freshPrice = false))
        assertEquals("stopped_for_today", VixDivRules.entryRefusal(false, true, "x", false))
        assertEquals("day_lock: reached", VixDivRules.entryRefusal(false, false, "reached", false))
        assertEquals("stale_price", VixDivRules.entryRefusal(false, false, null, false))
        assertNull(VixDivRules.entryRefusal(false, false, null, true))
    }

    @Test fun niftyTakesTheLiquidityExitOnTheMinutesLow() {
        val h = VixDivRules.Held("NIFTY", 200.0, m(10, 31))
        assertEquals(170.0, VixDivRules.stop(200.0), 1e-9)
        assertEquals(169.95, VixDivRules.stop(199.99), 1e-9)
        assertTrue(VixDivRules.liquidityExit("NIFTY")); assertFalse(VixDivRules.liquidityExit("BANKNIFTY"))
        fun mins(vararg x: Triple<Int, Double, Double>) = x.map { VixDivRules.Minute(it.first, it.second, it.third) }
        // A wick to the stop (its close well above) is the exit.
        assertEquals("stop_15", VixDivRules.exit(h, mins(Triple(m(10, 35), 169.9, 205.0)), m(10, 36)))
        // A minute before the entry is not read; nor one not yet finished.
        assertNull(VixDivRules.exit(h, mins(Triple(m(10, 30), 100.0, 100.0), Triple(m(10, 40), 100.0, 100.0)), m(10, 40)))
        // The 20th minute (10:50) closes under +5% (209.9 < 210): out at 10:51.
        val flat = (m(10, 31)..m(10, 50)).map { Triple(it, 199.0, if (it == m(10, 50)) 209.9 else 200.0) }.let { mins(*it.toTypedArray()) }
        assertNull(VixDivRules.exit(h, flat, m(10, 50)))
        assertEquals("not_up_5_in_20", VixDivRules.exit(h, flat, m(10, 51)))
        // 5% up at the 20th minute: held to 15:10.
        val up = (m(10, 31)..m(11, 30)).map { Triple(it, 205.0, 211.0) }.let { mins(*it.toTypedArray()) }
        assertNull(VixDivRules.exit(h, up, m(11, 31)))
        assertEquals("eod_1510", VixDivRules.exit(h, up, m(15, 10)))
        // The stop and the 20-minute check in the same minute: the stop goes first.
        val both = (m(10, 31)..m(10, 50)).map { Triple(it, if (it == m(10, 50)) 160.0 else 199.0, 200.0) }.let { mins(*it.toTypedArray()) }
        assertEquals("stop_15", VixDivRules.exit(h, both, m(10, 51)))
        // Still held after the check, the stop keeps working.
        val later = up + mins(Triple(m(11, 31), 150.0, 151.0))
        assertEquals("stop_15", VixDivRules.exit(h, later, m(11, 32)))
        // No print at the 20th minute: the last close before it decides (forward fill), read at the next minute printed.
        val gap = mins(Triple(m(10, 32), 199.0, 201.0), Triple(m(10, 55), 199.0, 230.0))
        assertEquals("not_up_5_in_20", VixDivRules.exit(h, gap, m(10, 56)))
        // ... or, with nothing printed after it, from the clock.
        assertEquals("not_up_5_in_20", VixDivRules.exit(h, mins(Triple(m(10, 32), 199.0, 201.0)), m(10, 51)))
        // Forward-filled close up 5%: held.
        assertNull(VixDivRules.exit(h, mins(Triple(m(10, 32), 199.0, 215.0)), m(10, 51)))
        assertNull(VixDivRules.exit(h, mins(Triple(m(10, 32), 199.0, 215.0), Triple(m(10, 55), 199.0, 100.0)), m(10, 56)))
        // Nothing printed at all since the entry: no time exit (lib.py), only the clock's 15:10.
        assertNull(VixDivRules.exit(h, emptyList(), m(11, 0)))
        assertEquals("eod_1510", VixDivRules.exit(h, emptyList(), m(15, 10)))
    }

    @Test fun bankNiftyHoldsTo1510WithNoStop() {
        val h = VixDivRules.Held("BANKNIFTY", 300.0, m(11, 31))
        val crash = listOf(VixDivRules.Minute(m(11, 40), 10.0, 12.0))
        assertNull(VixDivRules.exit(h, crash, m(12, 0)), "the research's EOD pick has no stop")
        assertNull(VixDivRules.exit(h, crash, m(15, 9)))
        assertEquals("eod_1510", VixDivRules.exit(h, crash, m(15, 10)))
        assertEquals("+0.25%", VixDivRules.pct(0.0025))
        assertEquals("-2.50%", VixDivRules.pct(-0.025))
    }
}
