package com.optionslab.ira

import com.optionslab.engine.orb.Bar
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiquidityReplayTest {
    private val day = LocalDate.of(2026, 10, 6)
    private fun at(h: Int, m: Int): LocalDateTime = day.atTime(h, m)

    /** A BANKNIFTY 15-min call, two lots of 35: in at 12:00 (300), out at 12:14 (285) on the index stop. */
    private val trade = BotTrades.Trade("liquidity15", "BANKNIFTY14OCT2654300CE", "CE", 70, 300.0, at(12, 0).plusSeconds(20), at(11, 45),
        exit = 285.0, exitTime = at(12, 14).plusSeconds(5), why = "index_stop", charges = 120.0, level = 54_300.0, target = 54_400.0,
        peak = 306.0, trough = 284.0, lot = 35)

    /** The index's 1-minute candles 11:00-13:00 (rising to 12:05, then falling). */
    private val index: List<Bar> = (0 until 120).map { i ->
        val c = 54_290.0 + (if (i < 65) i * 0.5 else 32.5 - (i - 65) * 1.5)
        Bar(at(11, 0).plusMinutes(i.toLong()), c - 1, c + 3, c - 3, c)
    }

    /** The option's 1-minute candles 11:50-12:30: up to 306 at 12:04, down to 284 at 12:13. */
    private val option: List<Bar> = (0 until 40).map { i ->
        val t = at(11, 50).plusMinutes(i.toLong())
        val c = when {
            i < 10 -> 298.0
            i < 15 -> 300.0 + (i - 10)
            else -> 304.0 - (i - 14) * 2.0
        }
        val high = if (t == at(12, 4)) 306.0 else c + 0.5
        val low = if (t == at(12, 13)) 284.0 else c - 0.5
        Bar(t, c, high, low, c)
    }

    @Test fun theWindowIsFifteenMinutesBeforeTheEntryToTenAfterTheExit() {
        assertEquals(at(11, 45) to at(12, 25), LiquidityReplay.window(at(12, 0).plusSeconds(20), at(12, 14).plusSeconds(5)))
        // Never past 15:30: an exit at 15:10 shows to the close.
        assertEquals(at(14, 55) to at(15, 21), LiquidityReplay.window(at(15, 10), at(15, 10)))
        assertEquals(at(14, 50) to at(15, 30), LiquidityReplay.window(at(15, 5), at(15, 25)))
    }

    @Test fun theReplayOfAClosedTrade() {
        val r = LiquidityReplay.of(trade, 54_300.0, index, option)!!
        assertEquals(at(11, 45), r.from); assertEquals(at(12, 25), r.to)
        assertEquals(40, r.index.size)
        assertEquals(at(11, 45), r.index.first().start); assertEquals(at(12, 24), r.index.last().start)
        assertEquals(54_300.0, r.level); assertEquals(54_270.0, r.indexStop); assertEquals(54_400.0, r.target)
        assertEquals(1, r.side)
        // The index at the entry's and the exit's minute (that minute's open).
        assertEquals(index.first { it.start == at(12, 0) }.open, r.entryIndex!!.price)
        assertEquals(index.first { it.start == at(12, 14) }.open, r.exitIndex!!.price)
        // The premium path over the window (the option's candles from 11:50), the best and worst at the held candles that came nearest.
        assertEquals(35, r.premium.size)
        assertEquals(LiquidityReplay.Mark(306.0, at(12, 4)), r.best)
        assertEquals(LiquidityReplay.Mark(284.0, at(12, 13)), r.worst)
        assertEquals(LiquidityReplay.Point(trade.entryTime, 300.0), r.entryPremium)
        assertEquals(285.0, r.exitPremium.price)
        assertEquals(listOf(
            "BANKNIFTY 15-min · BANKNIFTY 54,300 CE · Paper · 2 lots (70)",
            "Entry 300.00 at 12:00 → exit 285.00 at 12:14 · held 13 min",
            "Exit reason: Index stop: BANKNIFTY traded 30 pts back below 54,300",
            // (285 - 300) x 70 = -1,050 - 120 charges = -1,170; -585 a lot.
            "Net after charges −₹1,170 · −₹585 a lot · charges ₹120",
            "Best premium while held: 306.00 (12:04) · worst: 284.00 (12:13)",
            "Index: broke 54,300 · index stop 54,270 (30 pts back) · target 54,400",
        ), r.lines)
        // The research lesson, as Jarvis says it.
        assertEquals(BotTrades.lesson(trade)!!.text, r.lesson)
        assertTrue(r.lesson!!.startsWith("A normal loss"), r.lesson)
        assertEquals("Replay · BANKNIFTY 15-min CE", r.title)
    }

    @Test fun whatIsNotRecordedIsSaid() {
        val r = LiquidityReplay.of(trade.copy(peak = null, trough = null, lot = null, target = null), null, emptyList(), emptyList())!!
        assertNull(r.best); assertNull(r.worst); assertNull(r.entryIndex); assertTrue(r.premium.isEmpty())
        assertTrue("Best premium while held: not recorded · worst: not recorded" in r.lines, r.lines.toString())
        assertTrue("No index candles on the phone for 11:45–12:25" in r.lines, r.lines.toString())
        assertTrue("Premium path: not on the phone (the fill and the sale only)" in r.lines, r.lines.toString())
        // No lot size: the quantity is one lot; no strike: the symbol.
        assertEquals("BANKNIFTY 15-min · BANKNIFTY14OCT2654300CE · Paper · qty 70", r.lines.first())
        assertTrue("Net after charges −₹1,170 · −₹1,170 a lot · charges ₹120" in r.lines)
        assertTrue(r.lines.any { it.endsWith("· no target") })
        // The best recorded without candles: the price, no time.
        val b = LiquidityReplay.of(trade, null, emptyList(), emptyList())!!
        assertEquals(LiquidityReplay.Mark(306.0, null), b.best)
        assertTrue("Best premium while held: 306.00 · worst: 284.00" in b.lines)
    }

    @Test fun aPutsIndexStopIsAboveTheLevelAndAFinNiftyBookIsItsOwn() {
        val put = trade.copy(source = "liquidity5_fin", symbol = "FINNIFTY14OCT2626000PE", right = "PE", level = 26_000.0, target = 25_950.0,
            why = "next_liquidity", exit = 330.0, lot = 65, qty = 65)
        val r = LiquidityReplay.of(put, 26_000.0, emptyList(), emptyList())!!
        assertEquals(-1, r.side); assertEquals(26_015.0, r.indexStop)
        assertEquals("FINNIFTY 5-min · FINNIFTY 26,000 PE · Paper · 1 lot (65)", r.lines.first())
        assertTrue(r.lines.contains("Exit reason: Target: the index reached the next level, 25,950"))
        // A renamed book (FINNIFTY's old 15-minute one) is still Liquidity's.
        assertNotNull(LiquidityReplay.of(put.copy(source = "liquidity15_fin"), null, emptyList(), emptyList()))
    }

    @Test fun onlyAClosedLiquidityTrade() {
        assertNull(LiquidityReplay.of(trade.copy(exit = null, exitTime = null, why = null), null, index, option))
        assertNull(LiquidityReplay.of(trade.copy(source = "orb"), null, index, option))
    }

    @Test fun theLayout() {
        val r = LiquidityReplay.of(trade.copy(target = 54_340.0), 54_300.0, index, option)!!
        val l = LiquidityReplay.layout(r, 400, 200)!!
        assertEquals(40, l.candles.size)
        assertNotNull(l.premiumTop)
        assertEquals(0.6f * 192 + 4, l.indexBottom, 0.01f)
        // Every candle and level inside the index panel; the target within reach is drawn.
        assertTrue(l.candles.all { it.high >= 4f && it.low <= l.indexBottom && it.x in 4f..396f })
        assertEquals(listOf(LiquidityReplay.LineKind.LEVEL, LiquidityReplay.LineKind.INDEX_STOP, LiquidityReplay.LineKind.TARGET), l.lines.map { it.kind })
        val level = l.lines.first { it.kind == LiquidityReplay.LineKind.LEVEL }.y
        val stop = l.lines.first { it.kind == LiquidityReplay.LineKind.INDEX_STOP }.y
        assertTrue(stop > level, "a call's index stop is under the level")
        assertTrue(l.lines.all { it.y in 4f..l.indexBottom })
        // The held span from the entry to the exit, left to right; the markers on it.
        assertTrue(l.holdFrom < l.holdTo)
        assertEquals(l.holdFrom, l.entry!!.x); assertEquals(l.holdTo, l.exit!!.x)
        // The premium panel under it: the best highest, the worst lowest (y grows downwards).
        val top = l.premiumTop!!
        assertTrue(l.premium.all { it.y in top..196f })
        assertTrue(l.best!!.y < l.premiumEntry!!.y && l.worst!!.y > l.premiumEntry.y)
        // ...of what it did while held (after the exit it fell further: the path goes on, the worst is the held one).
        val held = l.premium.filter { it.x in l.holdFrom..l.holdTo }
        assertTrue(l.best.y <= held.minOf { it.y } && l.worst.y >= held.maxOf { it.y })
        assertTrue(l.premium.maxOf { it.y } > l.worst.y)
        assertTrue(l.best.x > l.holdFrom && l.best.x < l.holdTo)
        // No room: nothing.
        assertNull(LiquidityReplay.layout(r, 8, 200))
    }

    @Test fun aLayoutWithoutPremiumUsesTheWholeHeightAndAFarTargetIsLeftOut() {
        val r = LiquidityReplay.of(trade.copy(peak = null, trough = null, target = 60_000.0), 54_300.0, index, emptyList())!!
        val l = LiquidityReplay.layout(r, 300, 120)!!
        assertNull(l.premiumTop); assertNull(l.best); assertTrue(l.premium.isEmpty())
        assertEquals(116f, l.indexBottom)
        assertFalse(l.lines.any { it.kind == LiquidityReplay.LineKind.TARGET })
        // The best recorded without candles sits at the right edge of the premium panel.
        val b = LiquidityReplay.layout(LiquidityReplay.of(trade, null, index, emptyList())!!, 300, 120)!!
        assertEquals(296f, b.best!!.x)
        // No candles at all: the premium panel alone.
        val bare = LiquidityReplay.layout(LiquidityReplay.of(trade.copy(level = null), null, emptyList(), emptyList())!!, 300, 120)!!
        assertTrue(bare.candles.isEmpty() && bare.lines.isEmpty() && bare.entry == null)
    }
}
