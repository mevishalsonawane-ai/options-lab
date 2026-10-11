package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The statistics review's fixes (R3, 11 Oct), each pinned: the forge's continuous twin p, paired experiments tested against
 * no effect, no look-ahead in the busy / quiet filters, the lab's own BH family, Boss's rules judged on rupees, the nightly
 * lessons' always-valid p, the daily review's p, NaN-safe BH, the small bootstrap, the variants' BH family and siblings, and
 * the counterfactual's peak. Every simulation is seeded.
 */
class StatsReviewFixesTest {
    private val day = LocalDate.of(2026, 10, 12)

    // ---- 1: the forge's false-discovery gate can be passed --------------------------------------------------------------------

    private fun forgeBook(rng: DiscoveryStats.Rng, prior: Int): StrategyForge.Book =
        StrategyForge.Book(tried = prior, pvals = List(prior) { rng.nextDouble() })

    private fun judged(b: StrategyForge.Book, twinP: Double): Boolean {
        val c = StrategyForge.Candidate(b.tried + 1, day, "NIFTY",
            ShadowSim.Rules(ShadowSim.Entry.VWAP_RECLAIM, fromMin = 600, untilMin = 840, stopPct = 0.2, targetPct = 0.5), StrategyForge.ExitBlock.FIXED)
        val bt = StrategyForge.Backtest(60, 120, 30_000.0, 55.0, listOf(10_000.0, 10_000.0, 10_000.0), twinP, 0.0, 5_000.0, false,
            listOf("every backtest gate" to true))
        return StrategyForge.judge(b, c, bt, emptyList(), day).second.bt!!.passed
    }

    @Test fun aPlantedEdgePassesTheForgesFdrGateAfter440IdeasWhileNoiseStillFails() {
        val r = DiscoveryStats.Rng(440)
        // The twins' means: 199 random-entry copies, spread 50 rupees a trade around zero.
        fun twins() = List(StrategyForge.TWIN_REPS) { 50.0 * r.nextGaussian() }
        // A real edge five twin-sds above them: the continuous p is tiny and passes BH across all 440 ideas ever tried...
        val edge = LearnStats.twinZP(250.0, twins())
        assertTrue(edge < 1e-5, "edge p $edge")
        assertTrue(judged(forgeBook(r, 439), edge))
        // ...where the old counted p sat at its floor of 1 / 200 and could never pass.
        val floor = LearnStats.twinP(250.0, twins())
        assertEquals(1.0 / 200, floor, 1e-12)
        assertTrue(!judged(forgeBook(r, 439), floor))
        // Pure noise (an idea no better than its own twins) fails the gate at least 95% of the time.
        val reps = 400
        var passed = 0
        repeat(reps) { if (judged(forgeBook(r, 439), LearnStats.twinZP(50.0 * r.nextGaussian(), twins()))) passed++ }
        assertTrue(passed <= reps * 0.05, "noise passed $passed of $reps")
        // The continuous p is a p: under no edge about 5% fall under 0.05.
        var under = 0
        repeat(1_000) { if (LearnStats.twinZP(50.0 * r.nextGaussian(), twins()) < 0.05) under++ }
        assertTrue(under in 20..90, "under 0.05: $under of 1000")
        // No spread in the twins: the counted p.
        assertEquals(LearnStats.twinP(1.0, listOf(0.0, 0.0, 0.0)), LearnStats.twinZP(1.0, listOf(0.0, 0.0, 0.0)), 1e-12)
    }

    // ---- 2 and 7: experiments paired on the same trade, tested against no effect --------------------------------------------

    /** [n] paper trades: each one's rupees the original way (spread 2,000) and the variant's way ([effect] better, spread 300). */
    private fun cases(r: java.util.Random, n: Int, effect: Double): List<Preflight.Case> = List(n) { i ->
        val without = 2_000 * r.nextGaussian()
        Preflight.Case(1_760_000_000_000L + i * 3_600_000L, "t$i", without, without + effect + 300 * r.nextGaussian())
    }

    @Test fun anExperimentPairsEachTradeWithItselfAndHasThePowerToSeeAnEffect() {
        val c = listOf(Preflight.Case(0L, "a", 100.0, 400.0), Preflight.Case(60_000L, "b", -50.0, -20.0), Preflight.Case(120_000L, "c", 10.0, 0.0))
        assertEquals(listOf(300.0, 30.0, -10.0), Experiments.pairs(c).map { it.second })
        val r = java.util.Random(11)
        val reps = 1_000
        var paired = 0; var alternated = 0
        repeat(reps) {
            val cs = cases(r, 2 * Experiments.MAX_PAIRS, 300.0)
            if (LearnStats.Sprt.runVsZero(Experiments.pairs(cs).take(Experiments.MAX_PAIRS).map { it.second }).call == LearnStats.Sprt.Call.KEEP) paired++
            // The old alternation: one trade's "with" against the NEXT trade's "without".
            val alt = (0 until cs.size / 2).map { cs[2 * it + 1].with - cs[2 * it].without }
            if (LearnStats.Sprt.runVsZero(alt).call == LearnStats.Sprt.Call.KEEP) alternated++
        }
        assertTrue(paired >= reps * 0.8, "paired found the effect $paired of $reps")
        assertTrue(alternated <= reps * 0.3, "alternated found it $alternated of $reps")
    }

    @Test fun aZeroEffectExperimentRarelyGraduates() {
        val r = java.util.Random(7)
        val reps = 4_000
        var kept = 0; var keptOld = 0; var keptZeros = 0
        repeat(reps) {
            val xs = List(Experiments.MAX_PAIRS) { 1_000 * r.nextGaussian() }
            if (LearnStats.Sprt.runVsZero(xs).call == LearnStats.Sprt.Call.KEEP) kept++
            if (LearnStats.Sprt.run(xs).call == LearnStats.Sprt.Call.KEEP) keptOld++
            // Many trades the setting does not touch at all (a zero lead): still no graduation by luck.
            val zs = List(Experiments.MAX_PAIRS) { if (r.nextDouble() < 0.6) 0.0 else 1_000 * r.nextGaussian() }
            if (LearnStats.Sprt.runVsZero(zs).call == LearnStats.Sprt.Call.KEEP) keptZeros++
        }
        assertTrue(kept <= reps * 0.05, "a zero effect graduated $kept of $reps")
        assertTrue(keptZeros <= reps * 0.05, "a zero effect (mostly untouched trades) graduated $keptZeros of $reps")
        assertTrue(keptOld > reps * 0.15, "the old 'helps or hurts' test kept $keptOld of $reps (why it was replaced)")
        // A clear loss still ends it; a clear win still graduates; never before five pairs.
        assertEquals(LearnStats.Sprt.Call.REVERT, LearnStats.Sprt.runVsZero(List(30) { -300.0 + 50 * r.nextGaussian() }).call)
        assertEquals(LearnStats.Sprt.Call.KEEP, LearnStats.Sprt.runVsZero(List(30) { 300.0 + 50 * r.nextGaussian() }).call)
        assertEquals(null, LearnStats.Sprt.runVsZero(List(4) { 300.0 }).call)
    }

    // ---- 4: the busy / quiet filters never look ahead ----------------------------------------------------------------------

    @Test fun anEntryBefore0945IsNeverAllowedUnderABusyOrQuietFilter() {
        val days = LabTestData.days(40, 3, legs = false, sigma = 0.0006)
        // Time-of-day momentum from 09:16: fires on the first minute the day has moved a hair.
        val r = ShadowSim.Rules(ShadowSim.Entry.TIME_OF_DAY, barMin = 1, param = 0.0001, fromMin = ShadowSim.FIRST_ENTRY, untilMin = 14 * 60, stopPct = 0.2,
            targetPct = 0.5)
        val early = days.flatMap { ShadowSim.run(r, it) }
        assertTrue(early.any { it.entryMin < ShadowSim.BUSY_KNOWN }, "without a busy filter it does enter before 09:45")
        for ((f, busy) in listOf(ShadowSim.Filter.BUSY_ONLY to true, ShadowSim.Filter.QUIET_ONLY to false)) {
            val rf = r.copy(filters = setOf(f))
            assertEquals(ShadowSim.BUSY_KNOWN, ShadowSim.firstDecision(rf))
            val marked = days.map { ShadowSim.withBusy(it, busy) }
            val ts = marked.flatMap { ShadowSim.run(rf, it) }
            assertTrue(ts.isNotEmpty(), "$f still trades")
            assertTrue(ts.all { it.entryMin >= ShadowSim.BUSY_KNOWN }, "$f entered before 09:45: ${ts.map { it.entryMin }.filter { it < ShadowSim.BUSY_KNOWN }}")
            // Its random twin keeps to the same window.
            val tw = ShadowSim.twin(rf, marked.associateBy { it.date }, ts, DiscoveryStats.Rng(5))
            assertTrue(tw.all { it.entryMin >= ShadowSim.BUSY_KNOWN })
        }
        // Other filters keep their own window.
        assertEquals(ShadowSim.FIRST_ENTRY, ShadowSim.firstDecision(r.copy(filters = setOf(ShadowSim.Filter.SKIP_EXPIRY))))
    }

    // ---- 5: the lab's lessons in their own BH family ------------------------------------------------------------------------

    @Test fun labLessonsAreTheirOwnBhFamily() {
        val d0 = LocalDate.of(2026, 6, 1)
        val trips = (0 until 6).flatMap { o -> (0 until 40).map { k ->
            Insights.Trip("X", d0.plusDays(k.toLong()).atTime(11, 0), d0.plusDays(k.toLong()).atTime(11, 20),
                if ((k * 7 + o * 3) % 5 < 3) 260.0 else -300.0, "Bot $o") } }
        // Fifty lab blocks that nearly always fail: fifty sure "mostly failed" discoveries.
        val lab = (0 until 50).flatMap { b -> (0 until 40).map { k ->
            AgentLessons.LabResult(d0.plusDays(k.toLong()), "block:entry:B$b", "Ideas using B$b", k % 20 == 0, -500.0) } }
        val day = d0.plusDays(40)
        val alone = AgentLessons.pass(AgentLessons.Book(), AgentLessons.Inputs(day, trips = trips)).book.lessons
        val both = AgentLessons.pass(AgentLessons.Book(), AgentLessons.Inputs(day, trips = trips, lab = lab)).book.lessons
        assertTrue(both.count { it.id.startsWith("lab:") } == 50)
        assertTrue(alone.isNotEmpty() && alone.none { it.id.startsWith("lab:") })
        // The agents' lessons get exactly the q-values they would have without the lab's.
        val qAlone = alone.associate { it.id to it.q }
        for (l in both.filter { !it.id.startsWith("lab:") }) assertEquals(qAlone[l.id], l.q, l.id)
        // And the lab's are adjusted among themselves.
        val labPs = both.filter { it.id.startsWith("lab:") }.sortedBy { it.id }
        val q = LearnStats.bh(labPs.map { it.p!! }.toDoubleArray())
        labPs.forEachIndexed { i, l -> assertEquals(q[i], l.q!!, 1e-12) }
    }

    // ---- 6: Boss's rules on rupees, exit reasons left out ------------------------------------------------------------------

    @Test fun bossRulesAreJudgedOnRupeesAndExitReasonsAreNotGroups() {
        val ist = java.time.ZoneId.of("Asia/Kolkata")
        fun at(k: Int, h: Int) = day.minusDays(60 - k.toLong()).atTime(h, 20).atZone(ist).toInstant().toEpochMilli()
        // Gut trades that win 10 of 12 small and lose 2 big: a "good" win rate that loses money.
        val gut = (0 until 12).map { i -> BossTrades.Trade("paper:g$i", "NIFTY", false, at(i, 14), BossTrades.Why.GUT,
            exitWhy = if (i < 10) BossTrades.ExitWhy.TARGET else BossTrades.ExitWhy.STOP, closedMs = at(i, 15), net = if (i < 10) 100.0 else -2_500.0) }
        val rules = BossTrades.rules(gut)
        assertTrue(rules.none { it.key.startsWith("exit:") }, rules.map { it.key }.toString())
        val g = rules.single { it.key == "why:GUT" }
        assertEquals(-1, g.sign, "the sign follows the rupees (-Rs 4,000), not the 83% win rate")
        val perDay = gut.groupBy { it.day }.values.map { d -> d.sumOf { it.net!! } }.toDoubleArray()
        assertEquals(LearnStats.signFlipP(perDay), g.p, 1e-12)
        // Two trades a day count as one day's sum.
        val twice = gut.map { it.copy(openedMs = at(it.id.removePrefix("paper:g").toInt() / 2, 14)) }
        assertEquals(6, twice.map { it.day }.distinct().size)
        val p6 = BossTrades.rules(twice).single { it.key == "why:GUT" }.p
        assertEquals(LearnStats.signFlipP(twice.groupBy { it.day }.values.map { d -> d.sumOf { it.net!! } }.toDoubleArray()), p6, 1e-12)
    }

    // ---- 8 and 9: the nightly lessons' p ------------------------------------------------------------------------------------

    @Test fun nightlyPeekingIsControlled() {
        val r = java.util.Random(5)
        val reps = 1_500
        var ever = 0; var everFixed = 0
        repeat(reps) {
            val xs = DoubleArray(60) { 500 * r.nextGaussian() }
            var hit = false; var hitFixed = false
            for (n in 10..60) {
                val bs = (0 until n).map { AgentLessons.Bucket(day.plusDays(it.toLong()), 1, xs[it], if (xs[it] > 0) 1 else 0) }
                if (AgentLessons.pValue(AgentLessons.Kind.RUPEES, "strat:x", bs) <= 0.05) hit = true
                if (LearnStats.signFlipP(xs.copyOf(n)) <= 0.05) hitFixed = true
            }
            if (hit) ever++
            if (hitFixed) everFixed++
        }
        assertTrue(ever <= reps * 0.06, "a null lesson looked 'significant' on some night $ever of $reps")
        assertTrue(everFixed > reps * 0.12, "the fixed-sample p peeked nightly: $everFixed of $reps (why it was replaced)")
        // A real effect still shows.
        val real = (0 until 40).map { AgentLessons.Bucket(day.plusDays(it.toLong()), 1, 400.0 + 500 * r.nextGaussian(), 1) }
        assertTrue(AgentLessons.pValue(AgentLessons.Kind.RUPEES, "strat:x", real) < 0.001)
    }

    @Test fun aDailyReviewsPDoesNotCountTheLatestReviewAsEvidenceForItself() {
        fun reviews(vararg drifting: Boolean) = drifting.mapIndexed { i, h -> AgentLessons.Bucket(day.plusDays(i.toLong()), 30, 0.0, if (h) 1 else 0) }
        // Five drifting days in a row: four earlier ones agree with the latest - P(4 of 4) = 1/16.
        assertEquals(0.0625, AgentLessons.pValue(AgentLessons.Kind.SNAPSHOT, "drift:x:paper", reviews(true, true, true, true, true)), 1e-12)
        assertEquals(LearnStats.binomUpper(4, 4, 0.5), AgentLessons.pValue(AgentLessons.Kind.SNAPSHOT, "drift:x:paper", reviews(true, true, true, true, true)), 1e-12)
        // One review alone says nothing.
        assertEquals(1.0, AgentLessons.pValue(AgentLessons.Kind.SNAPSHOT, "drift:x:paper", reviews(true)), 1e-12)
        assertEquals(1.0, AgentLessons.pValue(AgentLessons.Kind.SNAPSHOT, "drift:x:paper", reviews(false, true)), 1e-12)
    }

    // ---- 12 and 13: NaN-safe BH, a broken day, the small bootstrap ----------------------------------------------------------

    @Test fun benjaminiHochbergIsNanSafeAndABrokenDayIsNotEvidence() {
        val q = LearnStats.bh(doubleArrayOf(0.001, 0.002, Double.NaN, 0.5))
        assertTrue(q.all { it.isFinite() }, q.toList().toString())
        assertEquals(LearnStats.bh(doubleArrayOf(0.001, 0.002, 1.0, 0.5)).toList(), q.toList())
        assertTrue(q[0] <= 0.10 && q[2] == 1.0)
        // An infinite day is dropped, never "p = 0".
        val sums = doubleArrayOf(120.0, -80.0, 60.0, -40.0, 30.0, -90.0, 70.0, -20.0, 50.0, -60.0, 10.0, Double.POSITIVE_INFINITY)
        assertEquals(LearnStats.signFlipP(sums.copyOf(11)), LearnStats.signFlipP(sums), 1e-12)
        assertTrue(LearnStats.signFlipP(doubleArrayOf(Double.NaN, 1.0, Double.NEGATIVE_INFINITY, 2.0)) == 1.0)
        assertTrue(LearnStats.signFlipAlwaysValidP(sums) > 0.05)
    }

    @Test fun aBootstrapOfAHandfulOfValuesSaysNothing() {
        assertEquals(1.0, LearnStats.bootstrapP(listOf(10.0, 1.0), 7L))
        assertEquals(1.0, LearnStats.bootstrapP(List(LearnStats.BOOT_MIN_N - 1) { 100.0 + it }, 7L))
        assertTrue(LearnStats.bootstrapP(List(30) { 100.0 + it }, 7L) < 0.01)
    }

    // ---- 10 and 11: the variants' BH family and their siblings ---------------------------------------------------------------

    private fun variant(id: String, key: String, diffs: List<Double>, status: VariantLab.Status = VariantLab.Status.ALIVE): VariantLab.Variant {
        val s = VariantLab.strategy(key)!!
        val v = s.defaults + (s.params.first().key to s.params.first().clamp(s.defaults.getValue(s.params.first().key) - s.params.first().step))
        return VariantLab.Variant(id, key, v, s.defaults, day.minusDays(40), 1.0, status, diffs, diffs.sumOf { it.coerceAtLeast(0.0) },
            -diffs.sumOf { it.coerceAtMost(0.0) }, DiscoveryStats.alwaysValidP(diffs, 1, VariantLab.MIN_SD), DiscoveryStats.alwaysValidP(diffs, -1, VariantLab.MIN_SD))
    }

    @Test fun precheckFailuresStayInTheVariantsFamilyAndAcceptRetiresTheSiblings() {
        val strong = List(30) { 600.0 + (it % 5) * 50 }
        val failed = (1..20).map { variant("P$it", "orb", emptyList(), VariantLab.Status.PRECHECK_FAILED) }
        val b = VariantLab.Book(variants = listOf(variant("V1", "orb", strong)) + failed, tried = 21)
        val all = VariantLab.allP(b)
        assertEquals(21, all.size)
        assertEquals(20, all.count { it == 1.0 })
        // Live: a proposal, then Boss's yes with the PIN. A sibling still alive was measured against the old rules: retired.
        val sib = variant("V2", "orb", List(10) { 100.0 })
        val start = VariantLab.Book(runs = mapOf("orb" to VariantLab.Run.LIVE), variants = listOf(variant("V1", "orb", strong), sib,
            variant("V3", "liquidity", List(5) { 0.0 })), tried = 3, nextId = 4)
        val p = VariantLab.judge(start, day)
        val c = p.events.filterIsInstance<VariantLab.Event.Proposed>().single().change
        assertEquals(VariantLab.Status.ALIVE, p.book.variants.first { it.id == "V2" }.status, "a proposal alone changes nothing")
        val yes = assertNotNull(VariantLab.accept(p.book, c.id, pinConfirmed = true, from = day.plusDays(1)))
        val v2 = yes.variants.first { it.id == "V2" }
        assertEquals(VariantLab.Status.RETIRED, v2.status); assertEquals("superseded by V1", v2.why)
        assertEquals(VariantLab.Status.ALIVE, yes.variants.first { it.id == "V3" }.status, "another strategy's variant is untouched")
        assertEquals(p.book.retiredCount + 1, yes.retiredCount)
    }

    // ---- 14: the counterfactual's peak starts at the entry -------------------------------------------------------------------

    @Test fun theMinuteBeforeTheEntryIsNotPartOfThePeak() {
        val t0 = 1_760_000_040_000L - 1_760_000_040_000L % 60_000L
        val t = TradeManager.ManagedTrade("pine:4", "pine:4:x", "Pine", "NIFTY", 1, "NIFTY25OCT25000CE", 100.0, 75, 70.0, 160.0,
            TradeManager.Caps(t0 + 3 * 3_600_000L), TradeManager.Account.PAPER, t0, 1.0)
        val bars = listOf(TradeManager.Bar(t0 - 60_000L, 100.0, 150.0, 99.0, 100.0), TradeManager.Bar(t0, 100.0, 110.0, 98.0, 105.0),
            TradeManager.Bar(t0 + 60_000L, 105.0, 112.0, 101.0, 108.0))
        val p = TradeManager.walk(t, TradeManager.Path(null, null, t0 + 3 * 3_600_000L, t.entry), bars, t0)
        assertEquals(112.0, p.peak, 1e-9)
        // Entered mid-minute (09:59:30): the minute from 09:59 holds the entry, so its high counts.
        val mid = t.copy(entryMs = t0 - 30_000L)
        assertEquals(150.0, TradeManager.walk(mid, TradeManager.Path(null, null, t0 + 3 * 3_600_000L, mid.entry), bars, mid.entryMs).peak, 1e-9)
    }
}
