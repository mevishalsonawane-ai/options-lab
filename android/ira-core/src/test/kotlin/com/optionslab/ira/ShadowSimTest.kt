package com.optionslab.ira

import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The virtual trader: wicks settle, costs are the paper's plus a half-spread more, the index-only approximation is labelled. */
class ShadowSimTest {
    private val day = LocalDate.of(2026, 10, 12)

    /** A flat index with one option leg whose minutes are given. */
    private fun dayWith(leg: List<DoubleArray>, from: Int = 600): ShadowSim.Day {
        val n = 375
        val mins = IntArray(n) { ShadowSim.OPEN + it }
        val flat = DoubleArray(n) { 50_000.0 }
        // A break above the opening range at [from] (index closes 100 up from then on).
        val c = DoubleArray(n) { if (mins[it] >= from) 50_100.0 else 50_000.0 }
        val h = DoubleArray(n) { c[it] + 5 }; val l = DoubleArray(n) { flat[it] - 5 }
        val ix = ShadowSim.Series(mins, c, h, l, c)
        val lm = IntArray(leg[0].size) { from + 1 + it }
        val opt = ShadowSim.Leg(50_100.0, 1, ShadowSim.Series(lm, leg[0], leg[1], leg[2], leg[3]))
        return ShadowSim.Day(day, "BANKNIFTY", 30, false, ix, listOf(opt))
    }

    private val rules = ShadowSim.Rules(ShadowSim.Entry.ORB_BREAK, barMin = 1, orMin = 45, fromMin = 600, untilMin = 840, stopPct = 0.2, targetPct = 0.5)

    @Test fun aStopOnTheWickAndTheCostsOfPaperPlusHalfASpread() {
        // Bought at 200 (the minute's open); the next minute's LOW touches 150 (the stop at 160) -> out at 160.
        val o = doubleArrayOf(200.0, 190.0, 170.0); val hh = doubleArrayOf(205.0, 195.0, 175.0)
        val ll = doubleArrayOf(195.0, 150.0, 160.0); val cc = doubleArrayOf(200.0, 180.0, 170.0)
        val t = ShadowSim.run(rules, dayWith(listOf(o, hh, ll, cc), from = 601))
        assertEquals(1, t.size)
        assertEquals("STOP", t[0].why); assertEquals(160.0, t[0].exit, 1e-9); assertEquals(200.0, t[0].entry, 1e-9)
        val hs = 2 * 0.0016
        val buy = 200 * (1 + hs); val sell = 160 * (1 - hs)
        val ch = SandboxCosts.charge("BUY", BigDecimal(buy), 30).toDouble() + SandboxCosts.charge("SELL", BigDecimal(sell), 30).toDouble()
        assertEquals((sell - buy) * 30 - ch, t[0].net, 1e-6)
        assertTrue(!t[0].approx)
    }

    @Test fun aMinuteTouchingBothCountsAsTheStop() {
        val o = doubleArrayOf(200.0, 200.0); val hh = doubleArrayOf(201.0, 400.0); val ll = doubleArrayOf(199.0, 100.0); val cc = doubleArrayOf(200.0, 300.0)
        val t = ShadowSim.run(rules, dayWith(listOf(o, hh, ll, cc), from = 601))
        assertEquals("STOP", t.single().why)
    }

    @Test fun aGapThroughTheTargetFillsAtTheOpen() {
        val o = doubleArrayOf(200.0, 330.0); val hh = doubleArrayOf(201.0, 340.0); val ll = doubleArrayOf(199.0, 320.0); val cc = doubleArrayOf(200.0, 335.0)
        val t = ShadowSim.run(rules, dayWith(listOf(o, hh, ll, cc), from = 601)).single()
        assertEquals("TARGET", t.why); assertEquals(330.0, t.exit, 1e-9)
    }

    @Test fun withoutTheOptionsItIsTheLabelledApproximation() {
        val d = LabTestData.days(30, 9, legs = false, edge = 0.0)
        val t = d.flatMap { ShadowSim.run(rules.copy(barMin = 5), it) }
        assertTrue(t.isNotEmpty())
        assertTrue(t.all { it.approx })
    }

    @Test fun invalidRulesNeverTrade() {
        assertNotNull(rules.copy(stopPct = null).invalid())
        assertNotNull(rules.copy(untilMin = 15 * 60).invalid())
        assertNotNull(rules.copy(squareOff = 15 * 60 + 20).invalid())
        assertNotNull(rules.copy(itm = 3).invalid())
        assertNull(rules.invalid())
        val d = LabTestData.days(5, 1)
        assertTrue(d.all { ShadowSim.run(rules.copy(stopPct = null), it).isEmpty() })
    }

    @Test fun theRulesRoundTripAndReadPlainly() {
        val r = rules.copy(filters = setOf(ShadowSim.Filter.SKIP_EXPIRY), noProgressMin = 20, indexStopPct = 0.002, itm = 1)
        assertEquals(r, ShadowSim.decode(ShadowSim.encode(r)))
        assertNull(ShadowSim.decode("e=NOPE"))
        val w = r.words("BANKNIFTY")
        for (x in listOf("BANKNIFTY, 1 lot, buys", "one-in-the-money", "never on expiry day", "stop 20% under the price paid", "target +50%",
            "out if not up 5% after 20 minutes", "flat by 15:10"))
            assertTrue(x in w, x)
    }

    @Test fun aStoredSessionReadsAsADayWithItsNearStrikesAndTheBusyRead() {
        val n = 375
        val mins = IntArray(n) { ShadowSim.OPEN + it }
        val ix = DoubleArray(n) { 50_000.0 + it }
        fun opt(strike: Double, right: com.optionslab.engine.Right, expiry: LocalDate) =
            com.optionslab.engine.Series(expiry, strike, right, 30, mins, DoubleArray(n) { 200.0 }, null, null, null, null, LongArray(n) { 1_000L })
        val near = day.plusDays(2)
        val s = com.optionslab.engine.Session(day, 30, listOf(
            com.optionslab.engine.Series(null, 0.0, com.optionslab.engine.Right.IX, 1, mins, ix, null, null, null, null, LongArray(n)),
            opt(50_100.0, com.optionslab.engine.Right.CE, near), opt(50_100.0, com.optionslab.engine.Right.PE, near),
            opt(60_000.0, com.optionslab.engine.Right.CE, near), opt(50_100.0, com.optionslab.engine.Right.CE, near.plusDays(7))))
        val d = ShadowSim.fromSession(s, "BANKNIFTY")!!
        assertEquals(2, d.legs.size, "the nearest series' strikes near the day only")
        assertTrue(!d.expiryDay); assertEquals(30, d.lot)
        val days = LabTestData.days(25, 8)
        val marked = ShadowSim.markBusy(days)
        assertTrue(marked.take(20).all { it.busy == null } && marked.drop(20).all { it.busy != null })
    }

    @Test fun theTwinKeepsTheDaysAndTheCount() {
        val d = LabTestData.days(40, 3)
        val r = rules.copy(barMin = 5)
        val trades = ShadowSim.runAll(r, d)
        val tw = ShadowSim.twin(r, d.associateBy { it.date }, trades, DiscoveryStats.Rng(1))
        assertTrue(tw.size <= trades.size && tw.size >= trades.size * 0.7, "${tw.size} of ${trades.size}")
        assertTrue(tw.map { it.day }.toSet().all { it in trades.map { t -> t.day }.toSet() })
        assertTrue(tw.all { it.entryMin in r.fromMin until r.untilMin })
    }
}
