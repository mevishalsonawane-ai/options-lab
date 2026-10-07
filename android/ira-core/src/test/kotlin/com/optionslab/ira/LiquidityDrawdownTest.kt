package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Liquidity 15+5's drawdown: the running total, its best, the falls, the losing runs, the words and the question. */
class LiquidityDrawdownTest {
    private val ADVICE = Regex("(?i)\\b(you should|i recommend|i suggest|consider|switch to|increase|raise|reduce|better to|safe to)\\b")
    private val day0: LocalDate = LocalDate.of(2026, 9, 21)   // a Monday

    /** A closed 2-lot trade of [source] on day0 + [i] days that made [perLot] a lot after charges. */
    private fun t(i: Int, perLot: Double, source: String = "liquidity15", live: Boolean = false): BotTrades.Trade {
        val at = LocalDateTime.of(day0.plusDays(i.toLong()), LocalTime.of(10, 0))
        return BotTrades.Trade(source, "BANKNIFTY26OCT56000CE", "CE", 70, 300.0, at, at.minusMinutes(5), 300.0 + perLot / 35.0, at.plusMinutes(20),
            "next_liquidity", 0.0, live, lot = 35)
    }

    private fun rows(vararg nets: Double) = LiquidityRecord.rows(nets.mapIndexed { i, x -> t(i, x) })

    /** Totals 100, 300, 150, 50, 450 (back above 300), 400, 100, -100: two falls, the second still open and the worst. */
    private val nets = doubleArrayOf(100.0, 200.0, -150.0, -100.0, 400.0, -50.0, -300.0, -200.0)

    @Test fun theRead() {
        val r = LiquidityDrawdown.readNets(nets.toList())
        assertEquals(8, r.n)
        assertEquals(-100.0, r.total, 1e-9)
        assertEquals(450.0, r.peak, 1e-9); assertEquals(5, r.peakAt)
        assertEquals(-550.0, r.now, 1e-9)
        assertEquals(listOf(LiquidityDrawdown.Fall(2, 300.0, 4, 50.0, 5), LiquidityDrawdown.Fall(5, 450.0, 8, -100.0, null)), r.falls)
        assertEquals(-550.0, r.worst!!.depth, 1e-9)
        assertEquals(r.falls[1], r.open); assertEquals(listOf(r.falls[0]), r.closed)
        assertEquals(3, r.longestLoss); assertEquals(8, r.longestLossEnd); assertEquals(-550.0, r.longestLossCost, 1e-9)
        assertEquals(3, r.lossNow)
        // Back at the peak exactly counts as recovered; a 0 counts as a loss (as the win rate).
        val flat = LiquidityDrawdown.readNets(listOf(100.0, -50.0, 50.0, 0.0))
        assertEquals(listOf(LiquidityDrawdown.Fall(1, 100.0, 2, 50.0, 3)), flat.falls.take(1))
        assertEquals(1, flat.lossNow)
        // None.
        val none = LiquidityDrawdown.readNets(emptyList())
        assertEquals(0, none.n); assertNull(none.worst); assertNull(none.open); assertEquals(0, none.longestLoss)
        // Never above zero: the start is its best.
        val under = LiquidityDrawdown.readNets(listOf(-100.0, -50.0))
        assertEquals(0.0, under.peak); assertEquals(0, under.peakAt); assertEquals(-150.0, under.now, 1e-9)
        assertEquals(LiquidityDrawdown.Fall(0, 0.0, 2, -150.0, null), under.open)
    }

    @Test fun pctOfThePeak() {
        assertEquals(50, LiquidityDrawdown.pctOf(-225.0, 450.0))
        assertNull(LiquidityDrawdown.pctOf(-100.0, 0.0))
    }

    @Test fun whetherNowIsUnusual() {
        assertTrue(LiquidityDrawdown.unusual(LiquidityDrawdown.readNets(listOf(100.0, 200.0))).startsWith("Nothing unusual now: it is at its best - though 2 trades"))
        assertTrue(LiquidityDrawdown.unusual(LiquidityDrawdown.readNets(nets.toList())).startsWith("The fall now is deeper than any of its 1 past fall"))
        assertTrue(LiquidityDrawdown.unusual(LiquidityDrawdown.readNets(listOf(100.0, -50.0))).startsWith("This is its first fall from a peak"))
        // Shallower than the past ones: within its own record.
        val mild = LiquidityDrawdown.readNets(listOf(500.0, -300.0, 400.0, -400.0, 600.0, -50.0))
        assertTrue(LiquidityDrawdown.unusual(mild).startsWith("The fall now is deeper than 0 of its 2 past falls (the worst of them -Rs 400) - within its own record"), LiquidityDrawdown.unusual(mild))
        // A long enough history says nothing of its length.
        val long = LiquidityDrawdown.readNets(List(20) { if (it % 2 == 0) 100.0 else -50.0 } + listOf(-500.0))
        assertFalse("too short" in LiquidityDrawdown.unusual(long))
    }

    @Test fun theAnswer() {
        val a = LiquidityDrawdown.answer(rows(*nets))
        assertTrue(a.startsWith("Boss, over its 8 closed paper trades Liquidity 15+5 stands at -Rs 100 a lot. Its best was +Rs 450, at trade 5 (25 Sep)."), a)
        assertTrue("Drawdown now: -Rs 550 from that best (all of that best and more), 3 trades since; its low in this fall -Rs 550 at trade 8 (28 Sep)." in a, a)
        assertTrue("Worst drawdown ever: -Rs 550 (all of that best and more), from +Rs 450 at trade 5 (25 Sep) down to -Rs 100 at trade 8 (28 Sep), 3 trades down; " +
            "it has not climbed back to that best yet." in a, a)
        assertTrue("Longest losing streak: 3 losses in a row from 26 Sep to 28 Sep, -Rs 550 a lot between them; it is on 3 losses in a row now." in a, a)
        assertTrue("At the backtest's 41% win rate, a run of 3 or more losses within 8 trades comes in" in a, a)
        assertTrue(a.endsWith(LiquidityDrawdown.END), a)
        assertTrue(!ADVICE.containsMatchIn(a), a)
        // A fall climbed back from: the trades and days it took.
        val back = LiquidityDrawdown.answer(rows(300.0, -100.0, -100.0, 50.0, 300.0))
        assertTrue("Drawdown now: none - it is at its best (trade 5 (25 Sep))." in back, back)
        assertTrue("Worst drawdown ever: -Rs 200 (67% of that best), from +Rs 300 at trade 1 (21 Sep) down to +Rs 100 at trade 3 (23 Sep), 2 trades down; " +
            "it climbed back to that best at trade 5 (25 Sep), 2 trades and 2 days after the low." in back, back)
        assertTrue("Longest losing streak: 2 losses in a row from 22 Sep to 23 Sep, -Rs 200 a lot between them; its last trade won." in back, back)
        assertTrue("Nothing unusual now: it is at its best" in back, back)
        // Only gains: no fall and no loss.
        val up = LiquidityDrawdown.answer(rows(100.0, 50.0))
        assertTrue("Worst drawdown ever: none - it has never fallen from a best." in up && "Longest losing streak: none - no losing trade yet." in up, up)
        // Never above zero.
        val under = LiquidityDrawdown.answer(rows(-100.0))
        assertTrue("It has not yet been above zero: its best is the start, -Rs 100 below it." in under, under)
        assertTrue("Its low so far -Rs 100 at trade 1 (21 Sep)." in under, under)
        assertTrue("Longest losing streak: 1 loss in a row on 21 Sep" in under, under)
        // Not the arm's: another arm, a live trade.
        assertEquals(LiquidityDrawdown.answer(rows(100.0)),
            LiquidityDrawdown.answer(LiquidityRecord.rows(listOf(t(0, 100.0), t(1, -900.0, source = "orb"), t(2, -900.0, live = true)))))
        // Nothing to read.
        assertTrue(LiquidityDrawdown.answer(emptyList()).startsWith("Liquidity 15+5 has no closed paper trade in its book yet"))
    }

    @Test fun theQuestion() {
        for (s in listOf("how deep has liquidity fallen from its best", "liquidity drawdown", "liquidity's current drawdown", "what is liquidity's drawdown",
            "liquidity's worst losing streak", "liquidity longest losing streak", "how long did liquidity take to recover", "how far is liquidity below its peak",
            "liquidity peak se kitna neeche hai", "liquidity kitna gira hai", "is liquidity underwater", "liquidity max drawdown"))
            assertTrue(LiquidityDrawdown.asked(s), s)
        for (s in listOf("how is liquidity doing", "liquidity win streak", "liquidity ki streak", "what is my drawdown", "liquidity drawdown today",
            "set liquidity drawdown limit", "should liquidity stop in a drawdown", "solo drawdown", "hero's worst losing streak", "what is a drawdown",
            "liquidity backtest drawdown", "why is liquidity in a drawdown", "liquidity drawdown and what is my pnl", "is nifty down from its high",
            "liquidity levels", "read my notes", "catch me up"))
            assertFalse(LiquidityDrawdown.asked(s), s)
    }
}
