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
                assertNull(s.invalid(v), "${s.key}: ${s.invalid(v)}")
                // Never a wider stop than the researched maximum, never outside the F&O hours, never more than 1 lot (no lot knob).
                s.params.firstOrNull { it.key.startsWith("stop") }?.let { p -> assertTrue(v.getValue(p.key) <= p.max + 1e-12) }
                v["until"]?.let { assertTrue(it <= ShadowSim.LAST_ENTRY) }
                assertTrue(s.params.none { it.key.contains("lot", ignoreCase = true) })
                assertEquals(s.params.map { it.key }.toSet(), v.keys)
                // The maxima themselves: ORB family -40 points, Liquidity its own 15% (never wider), VIX divergence 15%.
                assertTrue((v["stopPts"] ?: 0.0) <= 40.0 && (v["stopPct"] ?: 0.0) <= 0.15 + 1e-12)
                // Liquidity's next-level target is never turned off by a variant (an automatic change never skips an exit).
                if (s.key == "liquidity") assertEquals(1.0, v.getValue("tgt"), 1e-12)
            }
        }
        // A file can never carry a value outside the bounds back in.
        val bad = VariantLab.encode(VariantLab.Book(current = mapOf("liquidity" to mapOf("stopPct" to 0.9))))
        assertEquals(0.15, VariantLab.decode(bad).currentOf("liquidity").getValue("stopPct"), 1e-12)
        val noTarget = VariantLab.encode(VariantLab.Book(current = mapOf("liquidity" to mapOf("tgt" to 0.0))))
        assertEquals(1.0, VariantLab.decode(noTarget).currentOf("liquidity").getValue("tgt"), 1e-12)
        // Review R1: Liquidity's bounds cap the stop at its default and keep the target on; a wider stop or no target is invalid.
        val liq = VariantLab.strategy("liquidity")!!
        assertEquals(com.optionslab.engine.orb.LiquidityRules.PREMIUM_STOP, liq.param("stopPct")!!.max, 1e-12)
        assertEquals(VariantLab.LIQUIDITY_STOP_MAX, liq.defaults.getValue("stopPct"), 1e-12)
        assertNotNull(liq.invalid(mapOf("stopPct" to 0.175)))
        assertNotNull(liq.invalid(mapOf("tgt" to 0.0)))
        assertNull(liq.invalid(mapOf("stopPct" to 0.125, "tgt" to 1.0)))
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

    @Test fun aWithheldPromotionLeavesNoTuneBehind() {
        // Review 2 #3: learning frozen - the night's paper promotion is put straight back; the arm must never trade it.
        val b0 = VariantLab.Book(runs = mapOf("orb" to VariantLab.Run.PAPER), variants = listOf(variant("V1", "orb", strong)), tried = 1, nextId = 2)
        val next = day.plusDays(1)
        val pass = VariantLab.judge(b0, day, next)
        val c = pass.events.filterIsInstance<VariantLab.Event.Applied>().single().change
        assertEquals(c.to, VariantLab.valuesFor(pass.book, "orb", false, next))
        val held = VariantLab.withhold(pass.book, c.id)!!
        assertTrue(held.tunes.none { it.change == c.id }, "no tune of the withheld change is left")
        assertNull(VariantLab.valuesFor(held, "orb", false, next))
        assertNull(VariantLab.valuesFor(held, "orb", false, next.plusDays(30)))
        assertEquals(VariantLab.ChangeStatus.UNDONE, held.change(c.id)!!.status)
        assertEquals(c.from, held.currentOf("orb"))
        // Only an applied paper promotion can be withheld.
        assertNull(VariantLab.withhold(held, c.id))
    }

    @Test fun backToTheDefaultsNeverWidensALiveStopWithoutThePin() {
        // Review 2 #5: Liquidity's accepted live variant has a tighter stop than its defaults.
        val s = VariantLab.strategy("liquidity")!!
        val b0 = VariantLab.Book(runs = mapOf("liquidity" to VariantLab.Run.LIVE), variants = listOf(variant("V1", "liquidity", strong)), tried = 1, nextId = 2)
        val p = VariantLab.judge(b0, day)
        val c = p.events.filterIsInstance<VariantLab.Event.Proposed>().single().change
        val tight = c.to.getValue(ArmParams.STOP_PCT)
        assertTrue(tight < s.defaults.getValue(ArmParams.STOP_PCT))
        val from = day.plusDays(1)
        val yes = VariantLab.accept(p.book, c.id, pinConfirmed = true, from = from)!!
        // Boss's undo would widen the live stop: the PIN.
        assertTrue(!VariantLab.restoresDefault(yes, c.id))
        assertNull(VariantLab.undoLive(yes, c.id, pinConfirmed = false, from = from.plusDays(1)))
        // The safety net puts it back by itself, but the live arm keeps its tighter stop; paper goes to the full defaults.
        val live = mapOf("liquidity" to VariantLab.Run.LIVE)
        val net = VariantLab.safety(yes, from, live, from.plusDays(1)) { _, _ -> -1e6 }
        val r = net.events.filterIsInstance<VariantLab.Event.Reverted>().single()
        assertTrue(r.liveStopKept)
        assertTrue("needs your PIN" in r.change.why, r.change.why)
        val after = from.plusDays(1)
        val liveValues = VariantLab.valuesFor(net.book, "liquidity", true, after)!!
        assertEquals(tight, liveValues.getValue(ArmParams.STOP_PCT), 1e-12)
        assertEquals(s.defaults - ArmParams.STOP_PCT, liveValues - ArmParams.STOP_PCT)
        assertNull(VariantLab.valuesFor(net.book, "liquidity", false, after))
        // Already back as far as it goes by itself: another bad day does nothing more.
        assertTrue(VariantLab.safety(net.book, after, live, after.plusDays(1)) { _, _ -> -1e6 }.events.isEmpty())
        // The wider default stop only with Boss's PIN.
        assertTrue(!VariantLab.restoresDefault(net.book, c.id))
        assertNull(VariantLab.undoLive(net.book, c.id, pinConfirmed = false, from = after.plusDays(1)))
        val pinned = VariantLab.undoLive(net.book, c.id, pinConfirmed = true, from = after.plusDays(1))!!
        assertNull(VariantLab.valuesFor(pinned, "liquidity", true, after.plusDays(1)))
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

    // ---- S1: what the arms trade -----------------------------------------------------------------------------------------

    @Test fun aPaperPromotionIsTradedFromTheNextSessionNeverMidDay() {
        val b0 = VariantLab.Book(runs = mapOf("orb_sweep" to VariantLab.Run.PAPER), variants = listOf(variant("V1", "orb_sweep", strong)), tried = 1, nextId = 2)
        val next = day.plusDays(3)                     // Friday's settle: Monday is the next session
        val pass = VariantLab.judge(b0, day, next)
        val c = pass.events.filterIsInstance<VariantLab.Event.Applied>().single().change
        assertEquals(next, c.effective)
        // The day it was decided and the days up to the next session: the arm's defaults; from then on, the variant (paper only).
        for (d in listOf(day, day.plusDays(1), day.plusDays(2))) assertNull(VariantLab.valuesFor(pass.book, "orb_sweep", false, d), "$d")
        assertEquals(c.to, VariantLab.valuesFor(pass.book, "orb_sweep", false, next))
        assertEquals(c.to, VariantLab.valuesFor(pass.book, "orb_sweep", false, next.plusDays(30)))
        assertNull(VariantLab.valuesFor(pass.book, "orb_sweep", true, next), "a paper promotion never reaches the live account")
        assertEquals("running: default · from ${DiscoveryStats.day(next)}: variant V1", VariantLab.runningWords(pass.book, "orb_sweep", false, day))
        assertEquals("running: variant V1 since ${DiscoveryStats.day(next)}", VariantLab.runningWords(pass.book, "orb_sweep", false, next))
        assertEquals("V1", VariantLab.running(pass.book, "orb_sweep", false, next).variant)
        // Undo (paper, no PIN) also from a next session; a live change cannot be undone through it.
        val later = next.plusDays(7)
        val undone = VariantLab.undo(pass.book, c.id, later)!!
        assertEquals(c.to, VariantLab.valuesFor(undone, "orb_sweep", false, later.minusDays(1)))
        assertNull(VariantLab.valuesFor(undone, "orb_sweep", false, later))
        assertEquals(VariantLab.ChangeStatus.UNDONE, undone.change(c.id)!!.status)
        assertNull(VariantLab.undo(undone, c.id, later))
        // The whole day's pass: the change carries the safety net's yardsticks and is traded from the session given.
        val hist = LabTestData.days(25, 8)
        val dp = VariantLab.day(b0.copy(lastDay = null), day, b0.runs, emptyMap(), hist, DiscoveryStats.Rng(1), from = next)
        val ap = dp.events.filterIsInstance<VariantLab.Event.Applied>().single().change
        assertEquals(next, ap.effective)
        assertTrue(ap.avgLoss >= VariantLab.MIN_AVG_LOSS && ap.expect > 0)
        assertEquals(ap, dp.book.change(ap.id))
        assertNull(VariantLab.valuesFor(dp.book, "orb_sweep", false, day))
    }

    private fun proposed(): Pair<VariantLab.Book, VariantLab.Change> {
        val b = VariantLab.Book(runs = mapOf("orb" to VariantLab.Run.LIVE), variants = listOf(variant("V1", "orb", strong)), tried = 1, nextId = 2)
        val p = VariantLab.judge(b, day)
        return p.book to p.events.filterIsInstance<VariantLab.Event.Proposed>().single().change
    }

    @Test fun liveOnlyWithThePinFromTheNextSessionAndItsUndo() {
        val (b, c) = proposed()
        assertNull(VariantLab.valuesFor(b, "orb", true, day.plusDays(1)), "a proposal changes nothing")
        assertNull(VariantLab.accept(b, c.id, pinConfirmed = false, from = day.plusDays(1)))
        val from = day.plusDays(1)
        val yes = VariantLab.accept(b, c.id, pinConfirmed = true, from = from)!!
        assertNull(VariantLab.valuesFor(yes, "orb", true, day))
        assertEquals(c.to, VariantLab.valuesFor(yes, "orb", true, from))
        assertEquals(c.to, VariantLab.valuesFor(yes, "orb", false, from), "paper follows the accepted live rules")
        assertEquals(from, yes.change(c.id)!!.effective)
        // Its undo puts the defaults back (lower risk): no PIN needed.
        assertTrue(VariantLab.restoresDefault(yes, c.id))
        val back = VariantLab.undoLive(yes, c.id, pinConfirmed = false, from = from.plusDays(1))!!
        assertNull(VariantLab.valuesFor(back, "orb", true, from.plusDays(1)))
        assertNull(VariantLab.valuesFor(back, "orb", false, from.plusDays(1)))
        assertEquals(c.to, VariantLab.valuesFor(back, "orb", true, from))
        // A second live change on top of the first: undoing it goes back to the FIRST variant, not the defaults - the PIN.
        val v2 = variant("V2", "orb", strong).copy(values = c.to + ("stopPts" to 30.0), base = c.to)
        val b2 = VariantLab.judge(yes.copy(variants = yes.variants + v2, tried = 2), day.plusDays(2)).let { p ->
            val c2 = p.events.filterIsInstance<VariantLab.Event.Proposed>().single().change
            VariantLab.accept(p.book, c2.id, true, day.plusDays(3))!! to c2
        }
        assertTrue(!VariantLab.restoresDefault(b2.first, b2.second.id))
        assertNull(VariantLab.undoLive(b2.first, b2.second.id, pinConfirmed = false, from = day.plusDays(4)))
        val pinned = VariantLab.undoLive(b2.first, b2.second.id, pinConfirmed = true, from = day.plusDays(4))!!
        assertEquals(c.to, VariantLab.valuesFor(pinned, "orb", true, day.plusDays(4)))
        // Not accepted, or already undone: nothing.
        assertNull(VariantLab.undoLive(b, c.id, true, from))
        assertNull(VariantLab.undoLive(back, c.id, true, from))
    }

    @Test fun theSafetyNetRevertsToTheDefaults() {
        val (b, c) = proposed()
        val from = day.plusDays(1)
        val yes = VariantLab.accept(b, c.id, true, from)!!.let { x -> x.copy(changes = x.changes.map { if (it.id == c.id) it.copy(avgLoss = 1_000.0, expect = 200.0) else it }) }
        val live = mapOf("orb" to VariantLab.Run.LIVE)
        // Before the variant is traded (the day of the decision): never judged.
        assertTrue(VariantLab.safety(yes, day, live, from) { _, _ -> -1e9 }.events.isEmpty())
        // A day's loss within 3 x the backtest's average losing day: kept (a streak of one).
        val d1 = from
        val s1 = VariantLab.safety(yes, d1, live, d1.plusDays(1)) { _, _ -> -2_900.0 }
        assertTrue(s1.events.isEmpty()); assertEquals(1, s1.book.streaks["orb"])
        // More than 3 x: back to the defaults on both accounts from the next session (lower risk: no PIN), said with the numbers.
        val big = VariantLab.safety(yes, d1, live, d1.plusDays(1)) { _, _ -> -3_100.0 }
        val r = big.events.filterIsInstance<VariantLab.Event.Reverted>().single().change
        assertEquals(VariantLab.ChangeKind.SAFETY_REVERT, r.kind); assertEquals(VariantLab.strategy("orb")!!.defaults, r.to)
        assertTrue("3 x its backtest's average losing day" in r.why, r.why)
        assertEquals(c.to, VariantLab.valuesFor(big.book, "orb", true, d1), "the day itself is never changed")
        assertNull(VariantLab.valuesFor(big.book, "orb", true, d1.plusDays(1)))
        assertNull(VariantLab.valuesFor(big.book, "orb", false, d1.plusDays(1)))
        assertEquals(VariantLab.strategy("orb")!!.defaults, big.book.currentOf("orb"))
        // Three losing days in a row below the forward expectation; a good day in between resets the count.
        var x = yes
        val results = listOf(-100.0, -100.0, 500.0, -100.0, -100.0)
        for ((i, net) in results.withIndex()) {
            val p = VariantLab.safety(x, d1.plusDays(i.toLong()), live, d1.plusDays(i + 1L)) { _, _ -> net }
            assertTrue(p.events.isEmpty(), "day $i")
            x = p.book
        }
        assertEquals(2, x.streaks["orb"])
        val third = VariantLab.safety(x, d1.plusDays(5), live, d1.plusDays(6)) { _, _ -> -50.0 }
        assertTrue("3 losing days in a row" in third.events.filterIsInstance<VariantLab.Event.Reverted>().single().change.why)
        assertNull(VariantLab.valuesFor(third.book, "orb", true, d1.plusDays(6)))
        // Not running, or no result known: nothing; on the defaults: nothing.
        assertTrue(VariantLab.safety(yes, d1, emptyMap(), d1.plusDays(1)) { _, _ -> -1e9 }.events.isEmpty())
        assertTrue(VariantLab.safety(yes, d1, live, d1.plusDays(1)) { _, _ -> null }.events.isEmpty())
        assertTrue(VariantLab.safety(big.book, d1.plusDays(1), live, d1.plusDays(2)) { _, _ -> -1e9 }.events.isEmpty())
        // Kept on the phone: the tunes, the streaks, the changes' yardsticks.
        assertEquals(x, VariantLab.decode(VariantLab.encode(x)))
        assertEquals(third.book, VariantLab.decode(VariantLab.encode(third.book)))
    }

    @Test fun liquidityHasASmallerBudgetAndAStricterBar() {
        val hist = LabTestData.days(12, 9)
        val b = VariantLab.spawn(VariantLab.Book(runs = mapOf("liquidity" to VariantLab.Run.PAPER)), "liquidity", day, hist, DiscoveryStats.Rng(5))
        assertTrue(b.tried in 1..VariantLab.LIQUIDITY_MAX)
        assertTrue(b.variants.all { it.values.keys == ArmParams.LIQUIDITY_EXIT_KEYS })
        // A record that passes the ORB's bar (p 0.04 with one variant tried) does not pass Liquidity's (alpha halved).
        val orb = variant("V1", "orb", strong).copy(pBetter = 0.04)
        val liq = variant("V2", "liquidity", strong).copy(pBetter = 0.04)
        assertTrue(VariantLab.wins(orb, listOf(0.04), 1))
        assertTrue(!VariantLab.wins(liq, listOf(0.04), 1))
        assertTrue(VariantLab.wins(liq.copy(pBetter = 0.02), listOf(0.02), 1))
    }

    @Test fun anOldModelsVariantIsRetiredOnTheWayIn() {
        val old = VariantLab.Variant("V7", "liquidity", mapOf("stopPct" to 0.15, "np" to 20.0, "is" to 0.00055), emptyMap(), day, 1.0, VariantLab.Status.ALIVE)
        val back = VariantLab.decode(VariantLab.encode(VariantLab.Book(variants = listOf(old), current = mapOf("liquidity" to mapOf("np" to 25.0)))))
        assertEquals(VariantLab.Status.RETIRED, back.variants.single().status)
        assertEquals(VariantLab.OLD_RULES, back.variants.single().why)
        assertEquals(VariantLab.strategy("liquidity")!!.defaults, back.currentOf("liquidity"))
    }
}
