package com.optionslab.ira

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Slippage per fill ([Slippage]): the sign, ticks, median and p90 per strategy, the file line, the card and Jarvis's words. */
class SlippageTest {
    private val day = "2026-10-12"
    private fun f(strategy: String, side: String, intended: Double, avg: Double, leg: Slippage.Leg = Slippage.Leg.ENTRY, qty: Int = 75,
                  paper: Boolean = false, d: String = day, tick: Double = 0.05) =
        checkNotNull(Slippage.Fill.of(d, 1L, strategy, "NIFTY26OCT25000CE", side, leg, qty, intended, avg, tick, paper, Slippage.Basis.LTP))

    @Test fun aCostIsPositiveForBuysAboveAndSellsBelow() {
        val buy = f("ORB", "BUY", 100.0, 100.5)
        assertEquals(0.5, buy.rupees, 1e-9)
        assertEquals(10.0, buy.ticks, 1e-9)
        val sell = f("ORB", "SELL", 100.0, 99.8, Slippage.Leg.EXIT)
        assertEquals(0.2, sell.rupees, 1e-9)
        assertEquals(4.0, sell.ticks, 1e-9)
        // A better fill is negative.
        assertEquals(-0.3, f("ORB", "SELL", 100.0, 100.3).rupees, 1e-9)
        // A missing, zero or absurd price is no measurement.
        assertNull(Slippage.Fill.of(day, 1, "ORB", "X", "BUY", Slippage.Leg.ENTRY, 75, null, 100.0, 0.05, false, Slippage.Basis.LTP))
        assertNull(Slippage.Fill.of(day, 1, "ORB", "X", "BUY", Slippage.Leg.ENTRY, 75, 100.0, 0.0, 0.05, false, Slippage.Basis.LTP))
        assertNull(Slippage.Fill.of(day, 1, "ORB", "X", "BUY", Slippage.Leg.ENTRY, 0, 100.0, 100.1, 0.05, false, Slippage.Basis.LTP))
        assertNull(Slippage.Fill.of(day, 1, "ORB", "X", "BUY", Slippage.Leg.ENTRY, 75, 100.0, 180.0, 0.05, false, Slippage.Basis.LTP))
        assertNull(Slippage.Fill.of(day, 1, "ORB", "X", "BUY", Slippage.Leg.ENTRY, 75, Double.NaN, 100.0, 0.05, false, Slippage.Basis.LTP))
    }

    @Test fun medianP90AndCountPerStrategy() {
        val xs = (1..10).map { f("ORB", "BUY", 100.0, 100.0 + it * 0.1) } + listOf(f("Pine", "BUY", 50.0, 50.05), f("Pine", "BUY", 50.0, 50.15))
        val orb = checkNotNull(Slippage.byStrategy(xs)["ORB"])
        assertEquals(10, orb.n)
        assertEquals(0.5, orb.median, 1e-9)          // the lower middle of 0.1..1.0
        assertEquals(0.9, orb.p90, 1e-9)             // nearest rank: the 9th of 10
        assertEquals(10.0, orb.medianTicks, 1e-6)
        assertEquals(5.5 * 75, orb.total, 1e-6)
        val pine = checkNotNull(Slippage.byStrategy(xs)["Pine"])
        assertEquals(2, pine.n)
        assertEquals(0.05, pine.median, 1e-9)
        assertNull(Slippage.stats(emptyList()))
        assertEquals(0.0, Slippage.median(emptyList()))
        assertEquals(0.0, Slippage.quantile(emptyList(), 0.9))
        // Groups: per strategy, entry or exit, buy or sell; most fills first.
        val g = Slippage.groups(xs + f("ORB", "SELL", 120.0, 119.9, Slippage.Leg.EXIT))
        assertEquals("ORB · entries · BUY", g.first().first)
        assertTrue(g.any { it.first == "ORB · exits · SELL" && it.second.n == 1 })
    }

    @Test fun theStrategyFromTagArmAndLabel() {
        assertEquals("ORB", Slippage.strategyOf("iraorb", "orb", null))
        assertEquals("Liquidity", Slippage.strategyOf("iraorb", "liquidity:NIFTY", null))
        assertEquals("Liquidity", Slippage.strategyOf("iraorb", null, "Liquidity 15+5 · stop"))
        assertEquals("Pine", Slippage.strategyOf("irapine", null, null))
        assertEquals("Protection", Slippage.strategyOf("iraprotect", null, null))
        assertEquals("Expiry square-off", Slippage.strategyOf("iraalgoexpiry", null, null))
        assertEquals("Strategy", Slippage.strategyOf("iraalgostrat", null, null))
        // 18 Oct: a paper strategy's live trade (the arms' live path), by its tag or the arm key it was sent under.
        assertEquals("Arms (live)", Slippage.strategyOf("iraarm", null, null))
        assertEquals("Arms (live)", Slippage.strategyOf("iraalgo", "arm:r51_donchian20", null))
        assertEquals("Manual", Slippage.strategyOf("iraalgo", null, null))
        assertEquals("Manual", Slippage.strategyOf(null, null, null))
    }

    @Test fun theFileLineReadsBackAndADamagedOneIsSkipped() {
        val x = f("Liquidity", "SELL", 80.0, 79.85, Slippage.Leg.EXIT, paper = true)
        assertEquals(x, Slippage.decode(Slippage.encode(x)))
        assertNull(Slippage.decode("garbage"))
        assertNull(Slippage.decode("2026-10-12|x|ORB|S|BUY|e|75|1|1|0.05|0|p"))
        // A '|' in a name never breaks the line.
        val y = f("A|B", "BUY", 10.0, 10.05)
        assertEquals("A/B", Slippage.decode(Slippage.encode(y))?.strategy)
    }

    @Test fun theCardAndTheDiagnosticsLine() {
        val xs = listOf(f("ORB", "BUY", 100.0, 100.4), f("ORB", "BUY", 100.0, 100.2), f("ORB", "BUY", 100.0, 100.2, d = "2026-10-09"),
            f("Paper", "BUY", 100.0, 100.05, paper = true))
        val lines = Slippage.lines(xs, day)
        assertTrue(lines.any { it.startsWith("Live slippage · ORB · entries · BUY: median ₹0.20 (4.0 ticks), p90 ₹0.40 (8.0 ticks), 2 fills") }, "$lines")
        assertTrue(lines.any { it.startsWith("Paper slippage (modelled)") }, "$lines")
        assertEquals(listOf("Slippage: no fills measured today."), Slippage.lines(emptyList(), day))
        val d = Slippage.diagLine(xs, day)
        assertTrue(d.contains("live today 0.20/0.40") && d.contains("live kept") && d.contains("paper (modelled) today") && d.contains("ORB"), d)
        assertTrue(Slippage.diagLine(emptyList(), day).endsWith("no fills measured yet"))
    }

    @Test fun jarvisHearsAndAnswers() {
        assertEquals(Slippage.Ask(null, false), Slippage.asked("what's my slippage"))
        assertEquals(Slippage.Ask("ORB", false), Slippage.asked("slippage on ORB"))
        assertEquals(Slippage.Ask("Liquidity", false), Slippage.asked("How much slippage on liquidity trades?"))
        assertEquals(Slippage.Ask(null, true), Slippage.asked("paper slippage"))
        assertNotNull(Slippage.asked("how good are my fills"))
        assertNull(Slippage.asked("what's my pnl"))
        assertNull(Slippage.asked("reduce slippage on ORB"))
        val xs = listOf(f("ORB", "BUY", 100.0, 100.4), f("Pine", "BUY", 100.0, 100.1))
        val a = Slippage.answer(xs, Slippage.Ask("ORB", false), day)
        assertTrue(a.startsWith("Your slippage on ORB: median ₹0.40") && a.contains("no order is changed"), a)
        assertFalse(a.contains("Pine"), a)
        assertTrue(Slippage.answer(xs, Slippage.Ask(null, true), day).startsWith("No paper fills measured yet"))
    }
}
