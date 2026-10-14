package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Jarvis's morning gamma line ([GammaMorning], 14 Oct 2026): R28's GEX core on one minute, the walls, the flip, the words. */
class GammaMorningTest {
    private val day = LocalDate.of(2026, 10, 14)
    private val expiry = LocalDate.of(2026, 10, 20)
    private val ten = day.atTime(10, 0)

    /** A chain priced by Black-Scholes at [iv] around [spot] ([step] apart), OI from [ce] / [pe] by offset. */
    private fun chain(spot: Double, step: Double, center: Double, ce: (Int) -> Long, pe: (Int) -> Long, iv: Double = 0.12,
                      now: LocalDateTime = ten, exp: LocalDate = expiry, age: Double = 1.0): List<GammaMorning.Option> {
        val t = GammaMorning.years(now, exp)!!
        return (-10..10).flatMap { o ->
            val k = center + o * step
            listOf(GammaMorning.Option(k, "CE", GammaMorning.price(spot, k, t, iv, true), age, ce(o)),
                GammaMorning.Option(k, "PE", GammaMorning.price(spot, k, t, iv, false), age, pe(o)))
        }
    }

    // ---- parity with research/brain/gex.py ----

    private class Case(val name: String, val spot: Double, val day: LocalDate, val expiry: LocalDate,
                       val options: MutableList<GammaMorning.Option> = ArrayList(), val expect: MutableMap<String, Double> = HashMap())

    private fun cases(): List<Case> {
        val out = ArrayList<Case>()
        for (line in javaClass.getResource("/gex/r28_parity.txt")!!.readText().lines()) {
            val p = line.trim().split(" ")
            when (p[0]) {
                "case" -> out += Case(p[1], p[2].toDouble(), LocalDate.parse(p[3]), LocalDate.parse(p[4]))
                "opt" -> out.last().options += GammaMorning.Option(p[1].toDouble(), p[2], p[3].toDouble(), p[4].toDouble(), p[5].toLong())
                "expect" -> out.last().expect[p[1]] = p[2].toDouble()
            }
        }
        return out
    }

    private fun near(want: Double, got: Double?, tol: Double, what: String) {
        if (want.isNaN()) { assertNull(got, what); return }
        assertNotNull(got, what)
        assertTrue(abs(want - got) <= tol * maxOf(1.0, abs(want)), "$what: want $want got $got")
    }

    @Test fun matchesTheResearchCoreOnItsOwnFixture() {
        val cs = cases()
        assertEquals(3, cs.size)
        for (c in cs) {
            val r = GammaMorning.read("NIFTY", c.options, c.spot, c.day.atTime(10, 0), c.expiry)
            assertNotNull(r, c.name)
            near(c.expect.getValue("net_cr"), r.netCr, 1e-6, "${c.name} net")
            near(c.expect.getValue("gross_cr"), r.grossCr, 1e-6, "${c.name} gross")
            assertEquals(c.expect.getValue("n_contracts").toInt(), r.contracts, c.name)
            near(c.expect.getValue("flip_dist_pct"), r.flipDistPct, 1e-6, "${c.name} flip")
            assertEquals(c.expect.getValue("wall_pos_strike"), r.callWall, c.name)
            assertEquals(c.expect.getValue("wall_neg_strike"), r.putWall, c.name)
        }
        // The three cases cover both signs, a flip and none, an expiry day, stale and tiny prices, zero OI and a missing strike.
        assertTrue(cs.any { it.expect.getValue("net_cr") < 0 } && cs.any { it.expect.getValue("net_cr") > 0 })
        assertTrue(cs.any { it.expect.getValue("flip_dist_pct").isNaN() } && cs.any { !it.expect.getValue("flip_dist_pct").isNaN() })
    }

    // ---- sign, walls, flip ----

    @Test fun callsHeavyIsPositivePutsHeavyIsNegative() {
        val pos = GammaMorning.read("BANKNIFTY", chain(55000.0, 100.0, 55000.0, { 500_000 }, { 100_000 }), 55000.0, ten, expiry)!!
        assertFalse(pos.negative); assertTrue(pos.netCr > 0 && pos.grossCr > pos.netCr)
        val neg = GammaMorning.read("BANKNIFTY", chain(55000.0, 100.0, 55000.0, { 100_000 }, { 500_000 }), 55000.0, ten, expiry)!!
        assertTrue(neg.negative); assertTrue(neg.netCr < 0)
        assertEquals(42, neg.contracts)
        assertTrue(neg.ratio!! < 0)
    }

    @Test fun theWallsAreTheBiggestCallAndPutGammaStrikes() {
        val r = GammaMorning.read("NIFTY", chain(25000.0, 50.0, 25000.0, { o -> if (o == 2) 3_000_000 else 100_000 },
            { o -> if (o == -2) 3_000_000 else 100_000 }), 25000.0, ten, expiry)!!
        assertEquals(25100.0, r.callWall)
        assertEquals(24900.0, r.putWall)
    }

    @Test fun theFlipSitsBetweenAPutHeavyBelowAndACallHeavyAbove() {
        // Puts heavy below the money, calls heavy above: net gamma is negative low and positive high, crossing near spot.
        val r = GammaMorning.read("NIFTY", chain(25000.0, 50.0, 25000.0, { o -> if (o > 0) 800_000 else 50_000 },
            { o -> if (o < 0) 800_000 else 50_000 }), 25000.0, ten, expiry)!!
        val f = r.flipDistPct!!
        assertTrue(abs(f) < 1.0, "$f")
        assertEquals(25000.0 * (1 + f / 100), r.flip!!, 1e-6)
        // All calls: never crosses zero within +/-5%.
        val none = GammaMorning.read("NIFTY", chain(25000.0, 50.0, 25000.0, { 100_000 }, { 0 }), 25000.0, ten, expiry)!!
        assertNull(none.flipDistPct); assertNull(none.flip)
    }

    // ---- missing data ----

    @Test fun missingStrikesTakeTheNearestIvAndZeroOiCountsAsNothing() {
        val full = chain(25000.0, 50.0, 25000.0, { 200_000 }, { 200_000 })
        // Five strikes' prices missing (no price at all): they take the nearest strike's IV and still count with their OI.
        val gaps = full.mapIndexed { i, o -> if (i in 10..19) o.copy(ltp = null, ageMin = null) else o }
        val a = GammaMorning.read("NIFTY", full, 25000.0, ten, expiry)!!
        val b = GammaMorning.read("NIFTY", gaps, 25000.0, ten, expiry)!!
        assertEquals(a.contracts, b.contracts)
        assertTrue(abs(a.netCr - b.netCr) / a.grossCr < 0.05, "${a.netCr} ${b.netCr}")
        // A strike missing from the chain entirely is simply not there.
        val fewer = GammaMorning.read("NIFTY", full.filter { it.strike != 25500.0 }, 25000.0, ten, expiry)!!
        assertEquals(40, fewer.contracts)
        // Zero OI everywhere: every option has a value of 0, so the reading is 0 and positive (never negative by noise).
        val zero = GammaMorning.read("NIFTY", chain(25000.0, 50.0, 25000.0, { 0 }, { 0 }), 25000.0, ten, expiry)!!
        assertEquals(0.0, zero.netCr); assertEquals(0.0, zero.grossCr); assertFalse(zero.negative)
        // OI not known: not counted.
        assertNull(GammaMorning.read("NIFTY", full.map { it.copy(oi = null) }, 25000.0, ten, expiry))
    }

    @Test fun aStaleOrThinOrEmptyChainGivesNoReading() {
        // Every price older than 30 minutes: no IV anywhere, no reading.
        assertNull(GammaMorning.read("NIFTY", chain(25000.0, 50.0, 25000.0, { 1 }, { 1 }, age = 31.0), 25000.0, ten, expiry))
        // Exactly 30 minutes is still a price.
        assertNotNull(GammaMorning.read("NIFTY", chain(25000.0, 50.0, 25000.0, { 1 }, { 1 }, age = 30.0), 25000.0, ten, expiry))
        assertNull(GammaMorning.read("NIFTY", emptyList(), 25000.0, ten, expiry))
        // Fewer than 10 options with a value.
        assertNull(GammaMorning.read("NIFTY", chain(25000.0, 50.0, 25000.0, { 1 }, { 1 }).take(9), 25000.0, ten, expiry))
        assertNotNull(GammaMorning.read("NIFTY", chain(25000.0, 50.0, 25000.0, { 1 }, { 1 }).filter { abs(it.strike - 25000.0) <= 100 }, 25000.0, ten, expiry))
        // No spot, no expiry, past the expiry's 15:30.
        val c = chain(25000.0, 50.0, 25000.0, { 1 }, { 1 })
        assertNull(GammaMorning.read("NIFTY", c, null, ten, expiry))
        assertNull(GammaMorning.read("NIFTY", c, Double.NaN, ten, expiry))
        assertNull(GammaMorning.read("NIFTY", c, 25000.0, ten, null))
        assertNull(GammaMorning.read("NIFTY", c, 25000.0, expiry.atTime(15, 30), expiry))
    }

    @Test fun onExpiryDayItStillReads() {
        val now = expiry.atTime(10, 0)
        val r = GammaMorning.read("NIFTY", chain(25000.0, 50.0, 25000.0, { 300_000 }, { 300_000 }, now = now, exp = expiry), 25000.0, now, expiry)
        assertNotNull(r)
    }

    @Test fun impliedVolAndFillNearestBehaveAsTheResearchCore() {
        val t = 6.0 / 365
        val p = GammaMorning.price(25000.0, 25100.0, t, 0.15, true)
        assertEquals(0.15, GammaMorning.impliedVol(p, 25000.0, 25100.0, t, true)!!, 1e-7)
        assertNull(GammaMorning.impliedVol(0.0, 25000.0, 25100.0, t, true))
        assertNull(GammaMorning.impliedVol(p, 25000.0, 25100.0, 0.0, true))
        // Below intrinsic: no IV.
        assertNull(GammaMorning.impliedVol(50.0, 25000.0, 24800.0, t, true))
        // Lower strike first on a tie, then the next one out.
        val f = GammaMorning.fillNearest(doubleArrayOf(Double.NaN, 0.1, Double.NaN, 0.3, Double.NaN, Double.NaN))
        assertEquals(listOf(0.1, 0.1, 0.1, 0.3, 0.3, 0.3), f.toList())
        assertTrue(GammaMorning.fillNearest(doubleArrayOf(Double.NaN, Double.NaN)).all { it.isNaN() })
        assertTrue(GammaMorning.gammaRs(25000.0, 25000.0, 0.0, 0.1).isNaN())
        assertEquals(201, GammaMorning.FLIP_GRID.size)
        assertEquals(-0.05, GammaMorning.FLIP_GRID.first()); assertEquals(0.05, GammaMorning.FLIP_GRID.last()); assertEquals(0.0, GammaMorning.FLIP_GRID[100])
    }

    // ---- when and the words ----

    @Test fun dueAtTenOnTradingDaysOnly() {
        assertTrue(GammaMorning.due(ten, true, null))
        assertTrue(GammaMorning.due(day.atTime(10, 14), true, day.minusDays(1)))
        assertFalse(GammaMorning.due(day.atTime(9, 59), true, null))
        assertFalse(GammaMorning.due(day.atTime(10, 15), true, null))
        assertFalse(GammaMorning.due(ten, false, null), "a holiday or weekend")
        assertFalse(GammaMorning.due(ten, true, day), "once a day")
        assertEquals(LocalTime.of(10, 0), GammaMorning.AT)
    }

    private fun reading(index: String, net: Double) = GammaMorning.Reading(index, ten, 55000.0, net, abs(net) * 3, 42, -0.4, 55500.0, 54500.0)

    @Test fun theLineSaysTheSignTheMediansAndNothingAboutDirection() {
        val n = GammaMorning.line(reading("BANKNIFTY", -12.0))
        assertTrue(n.startsWith("Dealer gamma is negative this morning on BankNifty."), n)
        assertTrue("somewhat wider rest-of-day range (median 0.87% vs 0.75%, 199 vs 397 days, 2023-26)" in n, n)
        assertTrue("It says nothing about direction." in n)
        assertTrue("biggest call gamma at 55,500" in n && "biggest put gamma at 54,500" in n && "zero-gamma flip near 54,780" in n, n)
        val p = GammaMorning.line(reading("MIDCPNIFTY", 4.0))
        assertTrue("positive this morning on Midcap Nifty" in p && "narrower" in p && "median 0.76% vs 1.21% on negative days" in p, p)
        // NIFTY's difference at 10:00 was not significant (q 0.17): said so.
        assertTrue("weak difference for Nifty at 10:00, not significant" in GammaMorning.line(reading("NIFTY", -1.0)))
        assertFalse("weak" in n)
        val note = GammaMorning.note(listOf(reading("NIFTY", 2.0), reading("BANKNIFTY", -3.0)))!!
        assertTrue(note.endsWith(GammaMorning.INFO) && "Nifty" in note && "BankNifty" in note, note)
        assertNull(GammaMorning.note(emptyList()))
        // Every index R28 measured has its medians; nothing in the line is an amount of money.
        assertEquals(GammaMorning.INDICES.toSet(), GammaMorning.R28.keys)
        assertFalse(Regex("Rs|₹|crore").containsMatchIn(note), note)
    }

    @Test fun answersWhatGammaIsSaying() {
        assertEquals("", GammaMorning.asked("what's gamma saying"))
        assertEquals("BANKNIFTY", GammaMorning.asked("what is gamma saying on bank nifty today?"))
        assertEquals("NIFTY", GammaMorning.asked("nifty gamma walls"))
        assertEquals("", GammaMorning.asked("morning gamma"))
        assertNull(GammaMorning.asked("what's the gamma regime on banknifty"), "the live regime stays the order flow's answer")
        assertNull(GammaMorning.asked("zero gamma level"))
        assertNull(GammaMorning.asked("what is gamma?"), "the word itself is the glossary's")
        assertEquals("", GammaMorning.asked("what's gamma today"))
        assertNull(GammaMorning.asked("how is nifty"))
        val m = listOf(reading("BANKNIFTY", -3.0))
        assertTrue(GammaMorning.answer("BANKNIFTY", m, emptyList(), true, day.atTime(11, 0)).startsWith("At 10:00: Dealer gamma is negative"))
        val now = GammaMorning.answer(null, emptyList(), m, true, day.atTime(9, 40))
        assertTrue("From the chain on the phone at 09:40" in now && "before 10:00" in now && now.endsWith(GammaMorning.INFO), now)
        assertTrue("a later reading was not tested" in GammaMorning.answer(null, emptyList(), m, true, day.atTime(12, 0)))
        val atTen = GammaMorning.answer(null, emptyList(), m, true, day.atTime(10, 5))
        assertTrue(atTen.startsWith("From the chain on the phone at 10:05:") && "before 10:00" !in atTen && "later reading" !in atTen, atTen)
        assertTrue("no gamma reading for Nifty" in GammaMorning.answer("NIFTY", emptyList(), m, true, day.atTime(12, 0)))
        assertTrue("market is closed" in GammaMorning.answer(null, m, m, false, day.atTime(12, 0)))
        assertEquals("gamma 10:00: no reading", GammaMorning.diagLine(emptyList()))
        assertEquals("gamma 10:00: BANKNIFTY negative (call wall 55500, put wall 54500, 42 options)", GammaMorning.diagLine(m))
    }
}
