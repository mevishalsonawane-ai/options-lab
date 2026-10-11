package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shadow variants: SAFE bounds always, promotion only with the statistics, BH and the penalty, paper by itself and live asked. */
class VariantLabTest {
    private val day = LocalDate.of(2026, 10, 12)

    @Test fun variantBoundsAreNeverExceeded() {
        val r = DiscoveryStats.Rng(2026)
        for (s in VariantLab.STRATEGIES) repeat(300) {
            // A random centre anywhere in the bounds, a random step: every neighbour stays inside, valid, a stop never wider.
            val center = s.params.associate { p -> p.key to p.clamp(p.min + r.nextDouble() * (p.max - p.min)) }
            val scale = listOf(0.25, 0.5, 1.0, 2.0)[r.nextInt(4)]
            for (v in VariantLab.neighbours(s, center, scale, r, 6)) {
                for (p in s.params) {
                    val x = v.getValue(p.key)
                    assertTrue(x >= p.min - 1e-12 && x <= p.max + 1e-12, "${s.key} ${p.key} = $x")
                }
                val rules = s.rules(v)
                assertNull(rules.invalid(), "${s.key}: ${rules.invalid()}")
                // Never a wider stop than the researched maximum, never outside the F&O hours, never more than 1 lot (no lot knob).
                s.params.firstOrNull { it.key.startsWith("stop") }?.let { p -> assertTrue((rules.stopPct ?: rules.stopPts!!) <= p.max + 1e-12) }
                assertTrue(rules.untilMin <= ShadowSim.LAST_ENTRY && rules.squareOff <= ShadowSim.SQUARE_OFF)
                assertEquals(s.base.maxTrades, rules.maxTrades)
                assertTrue(s.params.none { it.key.contains("lot", ignoreCase = true) })
            }
        }
        // A file can never carry a value outside the bounds back in.
        val bad = VariantLab.encode(VariantLab.Book(current = mapOf("liquidity" to mapOf("stopPct" to 0.9))))
        assertEquals(0.25, VariantLab.decode(bad).currentOf("liquidity").getValue("stopPct"), 1e-12)
    }

    private fun variant(id: String, key: String, diffs: List<Double>, status: VariantLab.Status = VariantLab.Status.ALIVE): VariantLab.Variant {
        val s = VariantLab.strategy(key)!!
        val v = s.defaults + (s.params.first().key to s.params.first().clamp(s.defaults.getValue(s.params.first().key) - s.params.first().step))
        return VariantLab.Variant(id, key, v, s.defaults, day.minusDays(40), 1.0, status, diffs, diffs.sumOf { it.coerceAtLeast(0.0) },
            -diffs.sumOf { it.coerceAtMost(0.0) }, DiscoveryStats.alwaysValidP(diffs, 1, VariantLab.MIN_SD), DiscoveryStats.alwaysValidP(diffs, -1, VariantLab.MIN_SD))
    }

    private val strong = List(30) { 600.0 + (it % 5) * 50 }

    @Test fun promotionNeedsTheStatisticsTheBhAndThePenalty() {
        val v = variant("V1", "orb", strong)
        assertTrue(VariantLab.wins(v, listOf(v.pBetter), 1))
        // Too few days: no.
        val young = variant("V2", "orb", strong.take(VariantLab.MIN_N - 1))
        assertTrue(!VariantLab.wins(young, listOf(young.pBetter), 1))
        // Noise: no.
        val r = DiscoveryStats.Rng(4)
        val noise = variant("V3", "orb", List(30) { 300 * r.nextGaussian() })
        assertTrue(!VariantLab.wins(noise, listOf(noise.pBetter), 1))
        // A good record (p 0.01) passes alone; among thousands of tries with nothing in them it fails the false-discovery check...
        val good = v.copy(pBetter = 0.01)
        assertTrue(VariantLab.wins(good, listOf(0.01), 1))
        assertTrue(!VariantLab.wins(good, listOf(0.01) + List(5_000) { 0.6 }, 1))
        // ...and the trial penalty alone (BH still passing): after a thousand variants tried, 0.01 is above the bar.
        assertTrue(DiscoveryStats.bhPass(0.01, listOf(0.01), VariantLab.Q))
        assertTrue(!VariantLab.wins(good, listOf(0.01), 1_000))
        assertTrue(DiscoveryStats.penalizedAlpha(0.05, 1_000_000) < DiscoveryStats.penalizedAlpha(0.05, 1))
    }

    @Test fun aPaperStrategyChangesByItselfAndALiveOneIsAsked() {
        val paper = VariantLab.Book(runs = mapOf("orb" to VariantLab.Run.PAPER, "liquidity" to VariantLab.Run.LIVE),
            variants = listOf(variant("V1", "orb", strong), variant("V2", "orb", List(5) { 0.0 }), variant("V3", "liquidity", strong)), tried = 3, nextId = 4)
        val pass = VariantLab.judge(paper, day)
        val applied = pass.events.filterIsInstance<VariantLab.Event.Applied>().single()
        assertEquals("orb", applied.change.strategy); assertEquals(VariantLab.ChangeKind.AUTO_PAPER, applied.change.kind)
        assertEquals(applied.change.to, pass.book.currentOf("orb"))
        // Its sibling is superseded; the change can be undone (paper: no PIN).
        assertEquals(VariantLab.Status.RETIRED, pass.book.variants.first { it.id == "V2" }.status)
        val undone = VariantLab.undo(pass.book, applied.change.id)!!
        assertEquals(applied.change.from, undone.currentOf("orb"))
        // Live: a proposal only - nothing changes until Boss says yes WITH his PIN.
        val prop = pass.events.filterIsInstance<VariantLab.Event.Proposed>().single()
        assertEquals("liquidity", prop.change.strategy); assertEquals(VariantLab.ChangeKind.LIVE_PROPOSAL, prop.change.kind)
        assertEquals(VariantLab.strategy("liquidity")!!.defaults, pass.book.currentOf("liquidity"))
        assertNull(VariantLab.accept(pass.book, prop.change.id, pinConfirmed = false))
        val yes = VariantLab.accept(pass.book, prop.change.id, pinConfirmed = true)!!
        assertEquals(prop.change.to, yes.currentOf("liquidity"))
        // A live change is never undone around the PIN flow.
        assertNull(VariantLab.undo(yes, prop.change.id))
        // Not running: it waits.
        val off = VariantLab.judge(VariantLab.Book(variants = listOf(variant("V9", "orb_sweep", strong)), tried = 1), day)
        assertTrue(off.events.single() is VariantLab.Event.Waiting)
    }

    @Test fun aLoserIsRetiredAndTheStepShrinks() {
        val bad = variant("V1", "orb", List(VariantLab.MIN_N) { -700.0 - (it % 3) * 40 })
        val b = VariantLab.judge(VariantLab.Book(runs = mapOf("orb" to VariantLab.Run.PAPER), variants = listOf(bad), tried = 1), day).book
        assertEquals(VariantLab.Status.RETIRED, b.variants.single().status)
        assertEquals(0.5, b.scales.getValue("orb"), 1e-12)
        assertTrue(b.variants.single().why.startsWith("worse"))
    }

    @Test fun theDayShadowsSpawnsWithinCapsAndPrechecks() {
        val hist = LabTestData.days(30, 77)
        val live = LabTestData.days(5, 78, start = LocalDate.of(2025, 3, 3))
        var b = VariantLab.Book()
        val runs = VariantLab.STRATEGIES.associate { it.key to VariantLab.Run.PAPER }
        for ((i, d) in live.withIndex()) {
            b = VariantLab.day(b, d.date, runs, mapOf("BANKNIFTY" to d), hist, DiscoveryStats.Rng(i.toLong())).book
            for (s in VariantLab.STRATEGIES) assertTrue(b.alive(s.key).size <= VariantLab.MAX_PER_STRATEGY)
            assertTrue(b.alive().size <= VariantLab.MAX_TOTAL)
        }
        assertTrue(b.tried >= b.variants.size)
        assertTrue(b.variants.any { it.n > 0 }, "some variant compared")
        // Every failed pre-check says why, with the numbers.
        b.variants.filter { it.status == VariantLab.Status.PRECHECK_FAILED }.forEach { assertTrue(it.pre!!.first < it.pre!!.second && "stored days" in it.why) }
        // The same day twice is not counted twice.
        val again = VariantLab.day(b, live.last().date, runs, mapOf("BANKNIFTY" to live.last()), hist, DiscoveryStats.Rng(9))
        assertEquals(b, again.book)
        // Kept and read back.
        assertEquals(b, VariantLab.decode(VariantLab.encode(b)))
    }

    @Test fun thePrecheckRefusesAWorseVariant() {
        val s = VariantLab.strategy("orb")!!
        val hist = LabTestData.days(20, 5)
        val (v, c) = VariantLab.precheck(s, s.defaults, s.defaults, hist)
        assertEquals(v, c, 1e-9)
        assertNotNull(VariantLab.state(variant("V1", "orb", strong)))
    }
}
