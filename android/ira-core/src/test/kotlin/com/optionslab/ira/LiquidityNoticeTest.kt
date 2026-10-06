package com.optionslab.ira

import com.optionslab.engine.orb.Bar
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiquidityNoticeTest {
    private val day: LocalDate = LocalDate.of(2026, 10, 6)
    private fun at(h: Int, m: Int = 0): LocalDateTime = day.atTime(h, m)
    private val bank5 = LiquidityNotice.Book("BANKNIFTY", 5)

    private fun entry(target: Double? = 54_250.0, right: String = "CE", lots: Double? = 2.0, stop: Double? = 255.10, live: Boolean = false) =
        LiquidityNotice.Entry(bank5, live, right, 54_000.0, "BANKNIFTY-LIQ-54000CE", 60, lots, 300.15, at(13, 5),
            level = 54_100.0, target = target, close = 54_140.0, stop = stop, indexStopPoints = 30.0)

    private fun exit(why: String, gross: Double = -900.0, charges: Double = 82.0, right: String = "CE", lots: Double? = 2.0,
                     tally: LiquidityNotice.Tally = LiquidityNotice.Tally(2, 1, 1_240.0)) =
        LiquidityNotice.Exit(bank5, false, right, 54_000.0, "BANKNIFTY-LIQ-54000CE", 60, lots, 300.15, 285.15, at(13, 5), at(13, 47),
            why, 54_100.0, 54_250.0, 30.0, gross, charges, tally)

    // ---- the entry ----------------------------------------------------------------------------------------------------

    @Test fun entrySaysEverythingBossNeeds() {
        val s = LiquidityNotice.entry(entry(), hide = false)
        assertEquals("Liquidity 15+5 bought BANKNIFTY 54,000 CE · Paper", s.title)
        assertEquals("BANKNIFTY 5-min · 2 lots (60) @ 300.15", s.line)
        assertEquals(listOf(
            "BANKNIFTY 5-min · 2 lots (60) @ 300.15",
            "Broke 54,100 · target 54,250 (110 pts room)",
            "Stop: option 255.10 (−15%) · index 54,070 (30 pts back below)",
            "Time stop: out at 13:25 unless up 5% (315.16)",
        ), s.lines)
        assertEquals(s.lines.joinToString("\n"), s.body)
    }

    @Test fun entryOnThePutSideAndWithoutATarget() {
        val s = LiquidityNotice.entry(entry(target = null, right = "PE", lots = null, stop = null, live = true), hide = false)
        assertTrue(s.title.endsWith("54,000 PE · Live"), s.title)
        assertEquals("BANKNIFTY 5-min · qty 60 @ 300.15", s.line)
        assertEquals("Broke 54,100 · no level ahead (no target)", s.lines[1])
        assertEquals("Stop: option 15% under the fill · index 54,130 (30 pts back above)", s.lines[2])
    }

    @Test fun entryHidesPricesWhenFiguresAreHidden() {
        val s = LiquidityNotice.entry(entry(), hide = true)
        assertEquals("BANKNIFTY 5-min · 2 lots (60)", s.line)
        assertFalse(s.body.contains("300.15") || s.body.contains("255.10") || s.body.contains("315.16") || s.body.contains("₹"), s.body)
        assertTrue(s.body.contains("Broke 54,100"), "index levels stay")
        assertEquals("Time stop: out at 13:25 unless up 5%", s.lines[3])
    }

    @Test fun entryRoomFallsBackToTheLevelAndLotsWords() {
        val e = entry().copy(close = null)
        assertTrue(LiquidityNotice.entry(e, false).lines[1].contains("(150 pts room)"))
        assertEquals("1 lot (30)", LiquidityNotice.lotsWords(1.0, 30))
        assertEquals("1.5 lots (45)", LiquidityNotice.lotsWords(1.5, 45))
        assertEquals("qty 30", LiquidityNotice.lotsWords(0.0, 30))
        assertEquals("BANKNIFTY-X", LiquidityNotice.contract("BANKNIFTY", null, "CE", "BANKNIFTY-X"))
    }

    // ---- the exit -----------------------------------------------------------------------------------------------------

    @Test fun exitSaysWhyPricesPnlPerLotHoldAndTally() {
        val s = LiquidityNotice.exit(exit("failed_break"), hide = false)
        assertEquals("Liquidity 15+5 sold BANKNIFTY 54,000 CE · failed break", s.title)
        assertEquals(listOf(
            "Failed break: a bar closed back below 54,100",
            "BANKNIFTY 5-min · Paper · 300.15 → 285.15 · held 42 min",
            "P&L −₹982 after ₹82 charges (−₹491 a lot)",
            "Today's Liquidity: 2 trades, 1 won, net +₹1,240",
        ), s.lines)
        assertEquals(s.lines[0], s.line)
    }

    @Test fun exitHidesRupeesAndPricesWhenFiguresAreHidden() {
        val s = LiquidityNotice.exit(exit("stop"), hide = true)
        assertFalse(s.body.contains("₹") || s.body.contains("300.15") || s.body.contains("285.15"), s.body)
        assertEquals("Result: a loss after charges", s.lines[2])
        assertEquals("Today's Liquidity: 2 trades, 1 won", s.lines[3])
        assertEquals("Result: a gain after charges", LiquidityNotice.exit(exit("stop", gross = 500.0, charges = 40.0), true).lines[2])
        assertEquals("Result: flat after charges", LiquidityNotice.exit(exit("stop", gross = 40.0, charges = 40.0), true).lines[2])
    }

    @Test fun everyReasonInWords() {
        val words = mapOf(
            "stop" to ("stop" to "Stop: the option fell 15% under the price paid"),
            "index_stop" to ("index stop" to "Index stop: BANKNIFTY traded 30 pts back below 54,100"),
            "time_stop" to ("time stop" to "Time stop: not up 5% 20 minutes after the entry"),
            "next_liquidity" to ("next liquidity" to "Target: the index reached the next level, 54,250"),
            "new_liquidity" to ("new liquidity" to "New liquidity: a fresh level formed on the trade's side"),
            "failed_break" to ("failed break" to "Failed break: a bar closed back below 54,100"),
            "session_end" to ("end of day" to "End of day: sold at 15:10"),
            "backstop_square_off" to ("end of day" to "End of day: squared off by the account"),
            "operator_stop" to ("stopped for today" to "You stopped the strategies for today"),
            "closed_by_you" to ("closed by you" to "Closed by you"),
            "profit_lock" to ("profit lock" to "Exit: profit lock"),
        )
        for ((why, w) in words) {
            assertEquals(w.first, LiquidityNotice.short(why), why)
            assertEquals(w.second, LiquidityNotice.reason(exit(why)), why)
        }
        // The put side reads "above"; no level known reads "the broken level"; no target, no level named.
        val pe = exit("failed_break", right = "PE")
        assertEquals("Failed break: a bar closed back above 54,100", LiquidityNotice.reason(pe))
        assertEquals("Index stop: BANKNIFTY traded 30 pts back below the broken level", LiquidityNotice.reason(exit("index_stop").copy(level = null)))
        assertEquals("Failed break: a bar closed back below the broken level", LiquidityNotice.reason(exit("failed_break").copy(level = null)))
        assertEquals("Target: the index reached the next level", LiquidityNotice.reason(exit("next_liquidity").copy(target = null)))
        assertEquals(-1, pe.side)
    }

    @Test fun exitWithoutLotsAndSingleTradeTally() {
        val s = LiquidityNotice.exit(exit("time_stop", gross = 300.0, charges = 50.0, lots = null, tally = LiquidityNotice.Tally(1, 1, 250.0)), false)
        assertEquals("P&L +₹250 after ₹50 charges", s.lines[2])
        assertEquals("Today's Liquidity: 1 trade, 1 won, net +₹250", s.lines[3])
        assertEquals(250.0, LiquidityNotice.tally(listOf(250.0)).net, 1e-9)
        assertEquals(LiquidityNotice.Tally(3, 1, 0.0), LiquidityNotice.tally(listOf(100.0, -100.0, 0.0)))
        assertEquals("₹0", LiquidityNotice.signed(0.2))
        assertEquals("2 h 5 min", LiquidityNotice.duration(Duration.ofMinutes(125)))
        assertEquals("0 min", LiquidityNotice.duration(Duration.ofMinutes(-3)))
    }

    // ---- the skip ----------------------------------------------------------------------------------------------------

    @Test fun skipSaysTheRoomItLacked() {
        val s = LiquidityNotice.skip(LiquidityNotice.Skip(bank5, 54_180.0, 22.0, 30.0))
        assertEquals("Skipped: BankNifty 5-min break of 54,180 — only 22 pts to the next level (needs 30)", s.line)
        assertEquals("Liquidity 15+5 skipped a break", s.title)
        assertFalse(s.body.contains("₹"))
        val fin = LiquidityNotice.skip(LiquidityNotice.Skip(LiquidityNotice.Book("FINNIFTY", 30), 26_010.0, -9.0, 15.0))
        assertTrue(fin.line.startsWith("Skipped: FinNifty 30-min break of 26,010 — only 9 pts"), fin.line)
    }

    // ---- the mini-chart -------------------------------------------------------------------------------------------------

    private fun bars(n: Int, from: LocalDateTime = at(10, 0), mins: Long = 5, base: Double = 54_000.0) = (0 until n).map { i ->
        val o = base + i; val c = if (i % 2 == 0) o + 4 else o - 4
        Bar(from.plusMinutes(mins * i), o, maxOf(o, c) + 5, minOf(o, c) - 5, c)
    }

    @Test fun chartKeepsTheLastThirtyBarsRightAligned() {
        val ch = assertNotNull(LiquidityNotice.chart(bars(40), 5, 54_030.0, null, null, null, 304, 104, pad = 2))
        assertEquals(30, ch.candles.size)
        val step = 300f / 30
        assertEquals(2 + step * 29.5f, ch.candles.last().x, 1e-3f)
        assertEquals(2 + step * 0.5f, ch.candles.first().x, 1e-3f)
        assertTrue(ch.candles.zipWithNext().all { (a, b) -> b.x > a.x })
        // Every candle sits inside the plot and its body inside its wick.
        assertTrue(ch.candles.all { it.high >= 2f && it.low <= 102f && it.high <= it.top && it.top <= it.bottom && it.bottom <= it.low })
        assertTrue(ch.candles.first().up && !ch.candles[1].up)
        assertEquals(step * 0.6f, ch.candleWidth, 1e-3f)
        assertNull(ch.target); assertFalse(ch.targetOff); assertNull(ch.entry); assertNull(ch.exit)
        // Fewer bars leave the left empty: the latest is still at the right edge.
        val few = assertNotNull(LiquidityNotice.chart(bars(3), 5, null, null, null, null, 304, 104, pad = 2))
        assertEquals(2 + step * 27.5f, few.candles.first().x, 1e-3f)
        assertNull(few.level)
    }

    @Test fun chartLevelsAndTargetOnTheScale() {
        val b = bars(10)
        val ch = assertNotNull(LiquidityNotice.chart(b, 5, 53_900.0, 54_100.0, null, null, 200, 100))
        // Higher prices are higher up: the target (above) has a smaller y than the level (below).
        assertTrue(ch.target!! < ch.level!!)
        assertTrue(ch.level!! <= 96f && ch.target!! >= 4f)
        assertFalse(ch.targetOff)
        // A target far beyond the chart sits on the edge, flagged.
        val far = assertNotNull(LiquidityNotice.chart(b, 5, 54_000.0, 60_000.0, null, null, 200, 100))
        assertTrue(far.targetOff); assertEquals(4f, far.target!!, 0f)
        val farBelow = assertNotNull(LiquidityNotice.chart(b, 5, 54_000.0, 40_000.0, null, null, 200, 100))
        assertTrue(farBelow.targetOff); assertEquals(96f, farBelow.target!!, 0f)
    }

    @Test fun chartMarkersOnTheirBars() {
        val b = bars(10)                                  // 10:00 ... 10:45, 5-minute bars
        val ch = assertNotNull(LiquidityNotice.chart(b, 5, 54_000.0, null, at(10, 21), at(10, 46), 200, 100))
        assertEquals(ch.candles[4].x, ch.entry!!.x, 0f)   // 10:21 is in the 10:20 bar
        val e = b[4]; val upAt = if (e.close >= e.open) ch.candles[4].bottom else ch.candles[4].top
        assertEquals(upAt, ch.entry!!.y, 1e-3f)           // at its open
        assertEquals(ch.candles[9].x, ch.exit!!.x, 0f)    // 10:46 is in the last bar
        // Past the last bar's end: the last bar's close.
        val late = assertNotNull(LiquidityNotice.chart(b, 5, null, null, at(11, 30), null, 200, 100))
        assertEquals(ch.candles[9].x, late.entry!!.x, 0f)
        val l = b[9]; assertEquals(if (l.close >= l.open) ch.candles[9].top else ch.candles[9].bottom, late.entry!!.y, 1e-3f)
        // Before the chart: no marker.
        assertNull(LiquidityNotice.chart(b, 5, null, null, at(9, 0), null, 200, 100)!!.entry)
    }

    @Test fun chartNeedsBarsAndRoom() {
        assertNull(LiquidityNotice.chart(emptyList(), 5, 1.0, null, null, null, 200, 100))
        assertNull(LiquidityNotice.chart(bars(5), 5, 1.0, null, null, null, 8, 100))
        assertNull(LiquidityNotice.chart(bars(5), 5, 1.0, null, null, null, 200, 8))
        // A flat chart still has a scale (no division by zero).
        val flat = (0 until 5).map { Bar(at(10, it * 5), 100.0, 100.0, 100.0, 100.0) }
        val ch = assertNotNull(LiquidityNotice.chart(flat, 5, 100.0, null, null, null, 200, 100))
        assertTrue(ch.candles.all { it.high.isFinite() && it.low.isFinite() })
        assertEquals(50f, ch.level!!, 1e-3f)
    }
}
