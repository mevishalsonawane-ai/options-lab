package com.optionslab.engine.pine

import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Session
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A premium backtest with the auto-trader's own exits ([PinePremium.Protect]; Boss's 06 Oct rule: a stop-loss, a target
 * and the profit lock on every Pine strategy): a trade the script holds to its own exit is sold at the premium stop, the
 * target or the lock's level first, as it would trade.
 */
class PinePremiumProtectTest {
    private val day = LocalDate.of(2026, 9, 22)
    private val t0 = 1790048700L                                   // 09:15 IST
    private val bars = List(12) { Pine.Bar(t0 + it * 300L, 25000.0, 25001.0, 24999.0, 25000.0, 0.0) }
    /** Bought at bar 1 (minute 5's open), the script's own exit at bar 10 (minute 50's open). */
    private val trade = Pine.Trade("L", "S", true, 1.0, 1, t0 + 300, 25010.0, 10, t0 + 3000, 25030.0, 20.0, 0.08, 0.0, false)

    /** CE 25000 whose minute m opens, peaks, dips and closes as [ohlc] says (60 minutes from 09:15). */
    private fun session(withRange: Boolean = true, ohlc: (Int) -> DoubleArray): Session {
        val mins = IntArray(60) { 555 + it }
        val px = List(60) { ohlc(it) }
        val ce = Series(LocalDate.of(2026, 9, 23), 25000.0, Right.CE, 75, mins, DoubleArray(60) { px[it][3] }, DoubleArray(60) { px[it][0] },
            if (withRange) DoubleArray(60) { px[it][1] } else null, if (withRange) DoubleArray(60) { px[it][2] } else null, null, LongArray(60))
        return Session(day, 75, listOf(ce))
    }

    private fun run(s: Session, p: PinePremium.Protect?) =
        PinePremium.run(listOf(trade), bars, { if (it == day) s else null }, 50, 1, 100_000.0, protect = p).report!!.trades.single()

    private val rule = PinePremium.Protect(30.0, 60.0)

    @Test fun theStopSellsAtEntryLessTheStop() {
        // Flat at 100, then minute 10 dips to 60.
        val s = session { m -> if (m == 10) doubleArrayOf(95.0, 96.0, 60.0, 62.0) else if (m > 10) doubleArrayOf(62.0, 63.0, 61.0, 62.0) else doubleArrayOf(100.0, 100.5, 99.5, 100.0) }
        val t = run(s, rule)
        assertEquals(100.0, t.entryPrice, 1e-9)
        assertEquals("stop-loss", t.exitId)
        assertEquals(70.0, t.exitPrice, 1e-9, "a resting stop at entry - 30")
        assertEquals(2, t.exitBar)
        // Without the auto-trader's exits (a research run) it is held to the script's own exit.
        assertEquals(62.0, run(s, null).exitPrice, 1e-9)
        assertEquals("S", run(s, null).exitId)
    }

    @Test fun aGapPastTheStopSellsAtTheOpen() {
        val s = session { m -> if (m >= 12) doubleArrayOf(50.0, 52.0, 48.0, 50.0) else doubleArrayOf(100.0, 100.0, 100.0, 100.0) }
        assertEquals(50.0, run(s, rule).exitPrice, 1e-9)
    }

    @Test fun theTargetSellsAtEntryPlusTheTarget() {
        // Without highs and lows recorded: the open and close bound the minute.
        val s = session(withRange = false) { m -> if (m >= 12) doubleArrayOf(120.0, 0.0, 0.0, 170.0) else doubleArrayOf(100.0, 0.0, 0.0, 100.0) }
        val t = run(s, rule)
        assertEquals("target", t.exitId)
        assertEquals(160.0, t.exitPrice, 1e-9)
    }

    @Test fun theProfitLockSellsWhatGaveBackItsGain() {
        // Up to 145 by minute 8 (75% of the way to the 60 target), then back down to 105.
        val s = session { m -> when {
            m < 6 -> doubleArrayOf(100.0, 100.0, 100.0, 100.0)
            m <= 8 -> doubleArrayOf(100.0 + (m - 5) * 10, 145.0, 100.0 + (m - 5) * 10, 140.0)
            else -> doubleArrayOf(138.0, 138.0, 105.0, 105.0)
        } }
        val t = run(s, rule)
        assertEquals("profit lock", t.exitId)
        assertTrue(t.exitPrice > 100.0 && t.exitPrice < 145.0, "locked a gain: ${t.exitPrice}")
        // With the lock off (never for a Pine trade, but the engine can test it) only the stop and target count: held.
        val off = run(s, rule.copy(lock = false, trail = null))
        assertEquals("S", off.exitId)
    }

    @Test fun nothingReachedLeavesTheScriptsOwnExit() {
        val s = session { doubleArrayOf(100.0, 101.0, 99.0, 100.0) }
        val t = run(s, rule.copy(lock = false))
        assertEquals("S", t.exitId); assertEquals(10, t.exitBar); assertEquals(100.0, t.exitPrice, 1e-9)
        // A minute's own exit at its first second is not scanned past; an exit on a later day scans the entry day to its end.
        val ce = s.options.single()
        assertNull(PinePremium.protectedExit(ce, 100.0, 75, t0 + 300, t0 + 3000, rule))
        val dip = session { m -> if (m == 59) doubleArrayOf(100.0, 100.0, 60.0, 60.0) else doubleArrayOf(100.0, 100.0, 100.0, 100.0) }.options.single()
        assertEquals("stop-loss", PinePremium.protectedExit(dip, 100.0, 75, t0 + 300, t0 + 86_400 + 300, rule)?.why)
        assertNull(PinePremium.protectedExit(dip, 100.0, 75, t0 + 300, t0 + 3000, rule), "within the same day only up to its exit")
        // A fill inside a minute starts from the next one.
        val first = session { m -> if (m == 5) doubleArrayOf(100.0, 100.0, 60.0, 100.0) else doubleArrayOf(100.0, 100.0, 100.0, 100.0) }.options.single()
        assertNull(PinePremium.protectedExit(first, 100.0, 75, t0 + 300 + 30, t0 + 3000, rule))
        assertEquals("stop-loss", PinePremium.protectedExit(first, 100.0, 75, t0 + 300, t0 + 3000, rule)?.why)
        // No stop, no target, no lock: nothing.
        assertNull(PinePremium.protectedExit(dip, 100.0, 75, t0 + 300, t0 + 86_400, PinePremium.Protect(0.0, 0.0, lock = false)))
    }
}
