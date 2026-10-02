package com.optionslab.ira

import com.optionslab.engine.pine.Pine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrategyLabTest {
    private val bars = Fixtures.indexDays(60, step = 6.0, seed = 21)

    @Test fun everySupportedPatternCompilesAndRuns() {
        for (k in StrategyLab.SUPPORTED) for (minutes in listOf(15, 60)) {
            val src = StrategyLab.pine(k, Market.BANKNIFTY, minutes)
            val c = Pine.compile(src)
            assertTrue(c is Pine.Compiled.Ok, "${k.label} ${minutes}m: $c\n$src")
            val r = StrategyLab.backtest(k, Market.BANKNIFTY, minutes, bars)
            assertNull(r.error, "${k.label} ${minutes}m: ${r.error}")
            assertEquals(60, r.days)
            assertEquals(r.netPoints, r.firstHalfPoints + r.secondHalfPoints, 1e-6)
            assertTrue(r.summary().startsWith("Backtest of Jarvis:"), r.summary())
        }
    }

    @Test fun tradesFollowThePatternsDirectionAndRules() {
        val r = StrategyLab.backtest(PatternKind.BREAKOUT_UP, Market.NIFTY, 15, bars)
        assertTrue(r.trades > 0)
        val script = Pine.compile(r.script) as Pine.Compiled.Ok
        val candles = Candles.fold(bars, 15, Market.NIFTY)
        val pb = candles.map { Pine.Bar(it.t.atZone(java.time.ZoneId.of("Asia/Kolkata")).toEpochSecond(), it.o, it.h, it.l, it.c, 0.0) }
        val trades = Pine.run(script.script, pb, qty = 1.0).report!!.trades.filter { !it.open }
        assertTrue(trades.all { it.long }, "a bullish pattern only buys")
        assertTrue(trades.all { it.exitBar - it.entryBar <= StrategyLab.HOLD }, "out within ${StrategyLab.HOLD} candles")
        val ist = java.time.ZoneId.of("Asia/Kolkata")
        assertTrue(trades.all { java.time.Instant.ofEpochSecond(it.exitTime).atZone(ist).toLocalTime() <= java.time.LocalTime.of(15, 0) }, "out by 15:00")
        assertTrue(trades.all { java.time.Instant.ofEpochSecond(it.entryTime).atZone(ist).toLocalTime() >= java.time.LocalTime.of(9, 15) })
        val short = StrategyLab.backtest(PatternKind.BREAKOUT_DOWN, Market.NIFTY, 15, bars)
        val st = (Pine.compile(short.script) as Pine.Compiled.Ok).script
        assertTrue(Pine.run(st, pb, qty = 1.0).report!!.trades.all { !it.long })
    }

    @Test fun theVerdictNeedsTradesBothHalvesAndOptionProfit() {
        val r = StrategyLab.backtest(PatternKind.BULLISH_ENGULFING, Market.BANKNIFTY, 15, bars)
        if (r.trades < StrategyLab.MIN_TRADES) assertTrue(r.verdict.startsWith("Too few trades"))
        val big = StrategyLab.backtest(PatternKind.BREAKOUT_UP, Market.BANKNIFTY, 15, bars) { _, _ -> -500.0 to 10 }
        if (big.trades >= StrategyLab.MIN_TRADES && big.firstHalfPoints > 0 && big.secondHalfPoints > 0) {
            assertFalse(big.recommended); assertTrue(big.verdict.contains("options lost"))
        }
        assertEquals(-500.0, big.rupees); assertEquals(10, big.rupeeTrades)
        assertTrue(big.summary().contains("-Rs 500"), big.summary())
    }

    @Test fun refusals() {
        assertFalse(StrategyLab.supported(PatternKind.DOJI, Market.NIFTY))
        assertFalse(StrategyLab.supported(PatternKind.HAMMER, Market.GOLD))
        assertFailsWith<IllegalArgumentException> { StrategyLab.pine(PatternKind.DOJI, Market.NIFTY, 15) }
        assertNotNull(StrategyLab.backtest(PatternKind.DOJI, Market.NIFTY, 15, bars).error)
        assertEquals("too few candles (25)", StrategyLab.backtest(PatternKind.HAMMER, Market.NIFTY, 15, bars.take(375)).error)
        assertTrue(StrategyLab.backtest(PatternKind.DOJI, Market.NIFTY, 15, bars).summary().startsWith("I could not backtest"))
    }
}
