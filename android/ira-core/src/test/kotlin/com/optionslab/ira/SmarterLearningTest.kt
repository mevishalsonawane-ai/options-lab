package com.optionslab.ira

import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Smarter learning (11 Oct): Benjamini-Hochberg on known p-value sets and the sign-flip p; the sequential test deciding early
 * on clear effects, staying undecided on noise, and its error rates in a seeded simulation; a pre-flight that is worse
 * skipped (and one over its budget); experiments - the cap, paper only, graduation and ending with a negative lesson; a
 * regime split only when it passes; the trial counter raising the bar; determinism; the files; the answers; and a nightly
 * pass with all of it over a year of synthetic data in under 100 ms.
 */
class SmarterLearningTest {
    private val d0: LocalDate = LocalDate.of(2026, 6, 1)
    private val STRONG = AgentLessons.Confidence.STRONG
    private val BUILDING = AgentLessons.Confidence.BUILDING

    private fun ms(d: LocalDate, h: Int = 11) = d.atTime(h, 0).atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()

    private fun managedTrade(id: String, family: String, at: Long, account: TradeManager.Account = TradeManager.Account.PAPER) =
        TradeManager.ManagedTrade("$family:x", id, family, "NIFTY", 1, "NIFTY25OCT25000CE", 100.0, 75, 70.0, 160.0,
            TradeManager.Caps(at + 3_600_000L), account, at, 1.0)

    /** A settled managed trade at [at]: [lead] led an early exit worth [vs] rupees against the original rules. */
    private fun settled(id: String, family: String, lead: String, vs: Double, at: Long, account: TradeManager.Account = TradeManager.Account.PAPER): TradeManager.Record {
        val note = TradeManager.Note(at, TradeManager.EXIT_EARLY, "team", "out", 110.0, true, lead = lead, voters = listOf(lead))
        return TradeManager.Record(managedTrade(id, family, at, account), TradeManager.Mode.ACT, listOf(note),
            actual = TradeManager.Exit(at, 110.0, "trade manager"), manager = TradeManager.Exit(at, 110.0, "trade manager"),
            original = TradeManager.Exit(at + 1, 110.0 - vs / 75, "STOP"), actedAtMs = at)
    }

    private fun lesson(id: String, sign: Int, conf: AgentLessons.Confidence, q: Double?, cond: String? = null): AgentLessons.Lesson {
        val bs = (0 until 30).map { AgentLessons.Bucket(d0.plusDays(it.toLong()), 2, sign * 200.0 + (it % 3) * 10, if (sign > 0) 2 else 0) }
        return AgentLessons.Lesson(id, AgentLessons.Topic.SPECIALIST, AgentLessons.Kind.RUPEES, "The OI specialist on Pine", listOf("oi", "pine"),
            setOf("the specialists"), bs, sign, AgentLessons.Status.CONFIRMED, conf, d0, d0.plusDays(29), d0, 60, q = q, p = q, cond = cond, condQ = cond?.let { 0.01 })
    }

    private fun change(kind: AutoChange.Kind, target: String, from: String, to: String, scope: AutoChange.Scope, lessonId: String?,
                       conf: AgentLessons.Confidence = STRONG, risk: AutoChange.Risk = AutoChange.Risk.LOWER, expect: Int = -1) =
        AutoChange.Change(kind, target, from, to, "because", AutoChange.Evidence(60, -6_000.0, conf, -1), risk, scope, lessonId,
            expect = expect, effect = expect, what = target)

    private fun mute(fam: String = "pine", id: String = "oi", scope: AutoChange.Scope = AutoChange.Scope.PAPER) =
        change(AutoChange.Kind.SPECIALIST_MUTE, "spec:$fam:$id", AutoChange.COUNTS, AutoChange.MUTED, scope, "spec:$fam:$id")

    private fun plan(vararg cs: AutoChange.Change) = AutoChange.Plan(cs.toList(), emptyList(), emptyList(), emptyList())

    // ---- Benjamini-Hochberg and the p-values ----------------------------------------------------------------------------------

    @Test fun benjaminiHochbergOnKnownSets() {
        // 4 tests: sorted 0.005, 0.01, 0.03, 0.04 -> 0.02, 0.02, 0.04, 0.04 (each p x m / rank, then the running minimum from the top).
        val q = LearnStats.bh(doubleArrayOf(0.01, 0.04, 0.03, 0.005))
        assertEquals(listOf(0.02, 0.04, 0.04, 0.02), q.map { Math.round(it * 1e9) / 1e9 })
        // The textbook ten (Benjamini & Hochberg's style): at 0.05 the first two pass, at 0.09 the first five, at 0.10 six.
        val p = doubleArrayOf(0.001, 0.008, 0.039, 0.041, 0.042, 0.060, 0.074, 0.205, 0.212, 0.216)
        val bq = LearnStats.bh(p)
        assertEquals(2, bq.count { it <= 0.05 }); assertEquals(5, bq.count { it <= 0.09 }); assertEquals(6, bq.count { it <= 0.10 + 1e-12 })
        assertEquals(0.01, bq[0], 1e-12); assertEquals(0.04, bq[1], 1e-12); assertEquals(0.084, bq[2], 1e-12)
        // Every q against the definition (the smallest p_j x m / rank_j over the p_j at least as large), on a seeded set.
        val r = java.util.Random(3)
        val ps = DoubleArray(200) { if (it % 10 == 0) r.nextDouble() * 1e-3 else r.nextDouble() }
        val qs = LearnStats.bh(ps)
        val sorted = ps.sorted()
        for (i in ps.indices) {
            val want = sorted.indices.filter { sorted[it] >= ps[i] }.minOf { sorted[it] * ps.size / (it + 1) }.coerceAtMost(1.0)
            assertEquals(want, qs[i], 1e-12)
        }
        // Never below its p, never above 1; nothing in, nothing out.
        assertTrue(qs.indices.all { qs[it] >= ps[it] - 1e-15 && qs[it] <= 1.0 })
        assertEquals(0, LearnStats.bh(DoubleArray(0)).size)
    }

    @Test fun theSignFlipTestIsExactForFewDaysAndHonestForMany() {
        assertEquals(0.25, LearnStats.signFlipP(doubleArrayOf(1.0, 1.0, 1.0)), 1e-12)
        assertEquals(2.0 / 64, LearnStats.signFlipP(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)), 1e-12)
        assertEquals(1.0, LearnStats.signFlipP(doubleArrayOf(5.0, -5.0)))
        // 30 equal days: z = sqrt(30).
        assertEquals(LearnStats.pOfZ(Math.sqrt(30.0)), LearnStats.signFlipP(DoubleArray(30) { -100.0 }), 1e-15)
        assertTrue(LearnStats.signFlipP(DoubleArray(40) { if (it % 2 == 0) 100.0 else -100.0 }) > 0.5)
        // Rates: an exact binomial against the threshold.
        assertEquals(1.0, LearnStats.binomTwoSided(5, 10, 0.5), 1e-9)
        assertEquals(2.0 / 1024, LearnStats.binomTwoSided(10, 10, 0.5), 1e-12)
        assertEquals(1.0 / 32, LearnStats.binomUpper(5, 5, 0.5), 1e-12)
        assertEquals(0.5, LearnStats.phi(0.0), 1e-7); assertEquals(0.975, LearnStats.phi(1.959964), 1e-6)
    }

    @Test fun strongNeedsBenjaminiHochbergAndTheEvidenceShowsQ() {
        val e = AgentLessons.evidence(AgentLessons.Kind.RUPEES, "strat:a", (0 until 30).map { AgentLessons.Bucket(d0.plusDays(it.toLong()), 1, 100.0, 1) })
        assertEquals(STRONG, AgentLessons.confidence(e))
        assertEquals(STRONG, AgentLessons.confidence(e, 0.01))
        assertEquals(BUILDING, AgentLessons.confidence(e, 0.2))
        assertEquals(BUILDING, AgentLessons.confidence(e, null))
        // One real lesson among many noisy ones: BH keeps it strong; the evidence line carries its q.
        val trips = (0 until 40).map { k -> Insights.Trip("X", d0.plusDays(k.toLong()).atTime(11, 0), d0.plusDays(k.toLong()).atTime(11, 20), 400.0 + k, "Pine trend") } +
            (0 until 12).flatMap { o -> (0 until 40).map { k -> Insights.Trip("X", d0.plusDays(k.toLong()).atTime(11, 0), d0.plusDays(k.toLong()).atTime(11, 20),
                if ((k * 7 + o * 3) % 5 < 2) 300.0 else -210.0, "Bot $o") } }
        val pass = AgentLessons.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(40), trips = trips))
        val good = pass.book.lessons.first { it.id == AgentLessons.norm("strat:Pine trend") }
        assertEquals(STRONG, good.confidence); assertTrue(good.q!! <= AgentLessons.BH_Q)
        assertTrue("q < 0.001" in AgentLessons.evidenceLine(good), AgentLessons.evidenceLine(good))
        // Every active lesson has its p and q; no strong lesson above the bar.
        val act = AgentLessons.active(pass.book)
        assertTrue(act.all { it.p != null && it.q != null && it.q!! >= it.p!! - 1e-12 })
        assertTrue(act.filter { it.confidence == STRONG }.all { it.q!! <= AgentLessons.BH_Q })
        assertTrue(AgentLessons.sayWhy(listOf(good)).contains("q < 0.001 across all my lessons"))
    }

    // ---- the sequential test --------------------------------------------------------------------------------------------------

    @Test fun theSequentialTestDecidesEarlyOnClearEffectsAndWaitsOnNoise() {
        val r = java.util.Random(11)
        val good = LearnStats.Sprt.run(List(15) { 1.0 + 0.5 * r.nextGaussian() })
        assertEquals(LearnStats.Sprt.Call.KEEP, good.call); assertTrue(good.n < 15, "${good.n}")
        val bad = LearnStats.Sprt.run(List(15) { -1.0 + 0.5 * r.nextGaussian() })
        assertEquals(LearnStats.Sprt.Call.REVERT, bad.call); assertTrue(bad.n <= 8, "${bad.n}")
        // Never before five cases, however clear.
        assertNull(LearnStats.Sprt.run(List(4) { -100.0 }).call)
        // Noise: a mean near zero with a wide spread stays undecided over the probation's 15 cases.
        val noise = LearnStats.Sprt.run(List(15) { i -> if (i % 2 == 0) 1_000.0 else -980.0 })
        assertNull(noise.call)
        assertTrue(noise.words().startsWith("deciding: ") && "% of the way" in noise.words(), noise.words())
        assertTrue(noise.progress in 0.0..1.0)
        assertTrue(LearnStats.Sprt.run(List(8) { 50.0 }).words().startsWith("decided after"))
        // In a probation: kept early with the reason in words.
        val c = mute()
        val (b, _) = AutoChange.record(AutoChange.Book(), c, AutoChange.Status.PROBATION, 0, d0)
        val (k, kj) = AutoChange.review(b, d0.plusDays(1)) { List(9) { i -> -300.0 - i * 7 } }
        assertEquals(AutoChange.Verdict.KEEP, kj.single().verdict)
        assertTrue(k.entries.single().note.startsWith("kept: the sequential test decided early"), k.entries.single().note)
        assertTrue(k.entries.single().test.startsWith("decided after"))
    }

    @Test fun theSequentialTestsErrorRatesAreRoughlyAlphaAndBeta() {
        // Seeded: a change that hurts by the effect size (-0.2 sd) is kept at most about alpha (0.05); one that helps by it
        // (+0.2 sd) is reverted at most about beta (0.20). The sd is estimated as it goes, so "about": within a few points.
        val r = java.util.Random(2026)
        val sims = 1500
        var keptBad = 0; var revertedGood = 0
        repeat(sims) {
            if (LearnStats.Sprt.run(List(3000) { -0.2 + r.nextGaussian() }).call == LearnStats.Sprt.Call.KEEP) keptBad++
            if (LearnStats.Sprt.run(List(3000) { 0.2 + r.nextGaussian() }).call == LearnStats.Sprt.Call.REVERT) revertedGood++
        }
        val alpha = keptBad.toDouble() / sims; val beta = revertedGood.toDouble() / sims
        assertTrue(alpha <= LearnStats.Sprt.ALPHA + 0.025, "alpha $alpha")
        assertTrue(beta <= LearnStats.Sprt.BETA + 0.05, "beta $beta")
        // And it does decide: almost every run ends one way or the other well before 3,000 cases.
        assertTrue(LearnStats.Sprt.run(List(3000) { 0.2 + r.nextGaussian() }).n < 3000)
    }

    // ---- pre-flight -----------------------------------------------------------------------------------------------------------

    @Test fun aPreflightWorseChangeIsSkippedAndABetterOneMade() {
        val lessons = mapOf("mgr:pine" to lesson("mgr:pine", 1, STRONG, 0.001))
        // The manager would have done worse than the original rules on 30 paper trades: acting on them fails its replay.
        val worse = (0 until 30).map { settled("w$it", "pine", "oi", -300.0, ms(d0.plusDays(it.toLong()))) }
        val act = change(AutoChange.Kind.MANAGER_MODE, "mgr:pine:paper", "SHADOW", "ACT", AutoChange.Scope.PAPER, "mgr:pine", risk = AutoChange.Risk.RAISE, expect = 1)
        val r = AutoGate.route(AutoChange.Book(), plan(act), lessons, Preflight.Data(records = worse))
        assertTrue(r.apply.isEmpty())
        val s = r.skipped.single()
        assertTrue(s.note.startsWith("pre-flight failed: 30 cases replayed"), s.note)
        // Logged as skipped (never applied, never a trial), and not tried again within the flip-flop days.
        val (b, e) = AutoChange.record(AutoChange.Book(), s.change, AutoChange.Status.SKIPPED, 0, d0, note = s.note)
        assertEquals(0, b.trials); assertTrue(!e.applied)
        assertNotNull(AutoChange.blocked(b, act.key, d0.plusDays(1)))
        // The same records with the manager ahead: passes, with the replay's words.
        val better = (0 until 30).map { settled("b$it", "pine", "oi", 300.0, ms(d0.plusDays(it.toLong()))) }
        val ok = AutoGate.route(AutoChange.Book(), plan(act), lessons, Preflight.Data(records = better)).apply.single()
        assertTrue(ok.preflight!!.ok); assertTrue(ok.preflight!!.words().startsWith("pre-flight passed: 30 cases replayed"), ok.preflight!!.words())
        assertTrue(ok.preflight!!.with > ok.preflight!!.without)
        // Only the newest 40 cases count; nothing stored: it goes ahead on its lesson, and says so.
        val many = (0 until 90).map { settled("m$it", "pine", "oi", if (it < 50) -900.0 else 300.0, ms(d0.plusDays(it.toLong()))) }
        assertEquals(40, Preflight.check(act, Preflight.Data(records = many)).n)
        assertEquals(Preflight.Verdict.PASSED, Preflight.check(act, Preflight.Data(records = many)).verdict)
        val none = Preflight.check(act, Preflight.Data())
        assertEquals(Preflight.Verdict.NO_DATA, none.verdict); assertTrue(none.ok)
        // The paper account's costs: an exit at another price pays another sell leg (the replay is not the bare price move).
        val one = Preflight.cases(act, Preflight.Data(records = better.take(1)))!!.single()
        assertTrue(abs(one.diff - 300.0) in 0.01..5.0, "${one.diff}")
        // A pause replays the strategy's closed paper trades (each already after charges) as not taken.
        val pause = change(AutoChange.Kind.PAPER_ENTRIES, "paper:night_r3", AutoChange.ON, AutoChange.PAUSED, AutoChange.Scope.PAPER, null)
        val winners = (0 until 10).map { Insights.Trip("X", d0.plusDays(it.toLong()).atTime(21, 0), d0.plusDays(it.toLong()).atTime(21, 30), 800.0, "Night (R3)") }
        assertEquals(Preflight.Verdict.FAILED, Preflight.check(pause, Preflight.Data(paperTrips = winners)).verdict)
        assertEquals(Preflight.Verdict.PASSED, Preflight.check(pause, Preflight.Data(paperTrips = winners.map { it.copy(net = -800.0) })).verdict)
        // Nothing stored can replay a stricter self-review threshold: not replayable, judged on its own evidence.
        val knob = change(AutoChange.Kind.DRIFT_THRESHOLD, "knob:drift.z_crit", "3.3", "3.2", AutoChange.Scope.LIVE, null)
        assertEquals(Preflight.Verdict.NOT_REPLAYABLE, Preflight.check(knob, Preflight.Data()).verdict)
    }

    @Test fun aReplayOverItsTimeBudgetIsSkippedWithANote() {
        var t = 0L
        val slow = { t += 60_000_000L; t }     // every look at the clock: 60 ms later
        val act = change(AutoChange.Kind.MANAGER_MODE, "mgr:pine:paper", "SHADOW", "ACT", AutoChange.Scope.PAPER, "mgr:pine", risk = AutoChange.Risk.RAISE, expect = 1)
        val recs = (0 until 40).map { settled("s$it", "pine", "oi", 300.0, ms(d0.plusDays(it.toLong()))) }
        val res = Preflight.check(act, Preflight.Data(records = recs), clockNs = slow)
        assertEquals(Preflight.Verdict.TIMEOUT, res.verdict); assertTrue(!res.ok)
        assertTrue(res.words().contains("over its 200 ms budget"), res.words())
        val r = AutoGate.route(AutoChange.Book(), plan(act), mapOf("mgr:pine" to lesson("mgr:pine", 1, STRONG, 0.001)), Preflight.Data(records = recs), clockNs = slow)
        assertTrue(r.apply.isEmpty()); assertTrue(r.skipped.single().note.startsWith("pre-flight skipped"))
        // On the real clock a replay of 40 cases is far inside it.
        assertTrue(Preflight.check(act, Preflight.Data(records = recs)).ms < Preflight.BUDGET_MS)
    }

    // ---- experiments ----------------------------------------------------------------------------------------------------------

    @Test fun aBuildingLessonStartsAPaperExperimentAtMostTwoAtATime() {
        val ids = listOf("spec:pine:oi", "spec:pine:vwap", "spec:solo:oi")
        val lessons = ids.associateWith { lesson(it, -1, BUILDING, 0.3) }
        val cs = ids.map { id -> mute(id.split(':')[1], id.split(':')[2]) }
        val r = AutoGate.route(AutoChange.Book(), plan(*cs.toTypedArray()), lessons, Preflight.Data())
        assertEquals(2, r.experiments.size); assertTrue(r.apply.isEmpty())
        assertTrue(r.held.single().second.contains("2 experiments are already running"), r.held.single().second)
        var b = AutoChange.Book()
        for (c in r.experiments) b = Experiments.start(b, c, d0, ms(d0))
        assertEquals(2, Experiments.running(b).size); assertEquals(2, b.trials)
        // The cap holds in the book too, and the same target is never tried twice at once.
        assertEquals(b, Experiments.start(b, cs[2], d0, ms(d0)))
        assertEquals("2 experiments are already running", Experiments.cannotStart(b, cs[2]))
        // Paper only: a live or a shadow change is never an experiment.
        assertEquals("experiments are paper only", Experiments.cannotStart(AutoChange.Book(), mute(scope = AutoChange.Scope.LIVE)))
        assertEquals("experiments are paper only", Experiments.cannotStart(AutoChange.Book(), mute(scope = AutoChange.Scope.SHADOW)))
        val live = AutoGate.route(AutoChange.Book(), plan(mute(scope = AutoChange.Scope.LIVE)), mapOf("spec:pine:oi" to lesson("spec:pine:oi", -1, BUILDING, 0.3)), Preflight.Data())
        assertTrue(live.experiments.isEmpty() && live.apply.isEmpty()); assertTrue(live.held.single().second.contains("not made outright"))
        // A threshold in shadow carries no money: shadow changes are made as before.
        val sh = change(AutoChange.Kind.TRAP_THRESHOLD, "knob:trap.lockout_sec", "120", "150", AutoChange.Scope.SHADOW, "trap:stop_hunt", BUILDING, AutoChange.Risk.NEUTRAL_SHADOW)
        assertEquals(1, AutoGate.route(AutoChange.Book(), plan(sh), mapOf("trap:stop_hunt" to lesson("trap:stop_hunt", -1, BUILDING, 0.3)), Preflight.Data()).apply.size)
        // Shown in the log as an experiment, and said.
        assertTrue(" · experiment · running · " in Experiments.line(b.experiments.first()))
        assertTrue(Experiments.sayRunning(b).startsWith("2 paper experiments are running, Boss"))
        assertTrue(Experiments.sayRunning(AutoChange.Book()).startsWith("No paper experiment is running"))
    }

    @Test fun anExperimentThatWinsGraduatesAndOneThatLosesEndsWithANegativeLesson() {
        val start = ms(d0)
        // The trade manager acting on Pine's paper trades: alternate trades take its way (the variant) against the original rules.
        val act = change(AutoChange.Kind.MANAGER_MODE, "mgr:pine:paper", "SHADOW", "ACT", AutoChange.Scope.PAPER, "mgr:pine", BUILDING, AutoChange.Risk.RAISE, expect = 1)
        var b = Experiments.start(AutoChange.Book(), act, d0, start)
        val ahead = (1..20).map { settled("a$it", "pine", "oi", 300.0 + it, start + it * 3_600_000L) }
        val (won, outs) = Experiments.review(b, d0.plusDays(3), Preflight.Data(records = ahead))
        val o = outs.single()
        assertEquals(Experiments.Status.WON, o.experiment.status)
        assertTrue(o.experiment.note.startsWith("won: after"), o.experiment.note)
        // It graduates to a normal paper change; the live twin is only a question (it adds live risk).
        assertEquals("mgr:pine:paper", o.graduate!!.target); assertEquals(AutoChange.Scope.PAPER, o.graduate!!.scope)
        val twin = o.ask!!
        assertEquals("mgr:pine:live", twin.target); assertEquals(AutoChange.Scope.LIVE, twin.scope)
        assertEquals(AutoChange.Decision.ASK, AutoChange.classify(twin))
        assertTrue(Experiments.running(won).isEmpty())
        // Before any trade: still running, no decision.
        assertEquals(Experiments.Status.RUNNING, Experiments.review(b, d0.plusDays(1), Preflight.Data()).first.experiments.single().status)
        // A paper mute whose variant trails (the specialist's exits helped): lost, the target rests, and Jarvis keeps a negative lesson.
        val m = mute()
        b = Experiments.start(AutoChange.Book(), m, d0, start)
        val helped = (1..12).map { settled("h$it", "pine", "oi", 250.0 + it, start + it * 3_600_000L) }
        val (lost, lo) = Experiments.review(b, d0.plusDays(2), Preflight.Data(records = helped))
        assertEquals(Experiments.Status.LOST, lo.single().experiment.status)
        assertNull(lo.single().graduate)
        assertNotNull(lost.rest[m.key]); assertNotNull(AutoChange.blocked(lost, m.key, d0.plusDays(10)))
        val src = lesson("spec:pine:oi", -1, BUILDING, 0.3)
        val lb = AgentLessons.experimentResult(AgentLessons.Book(listOf(src)), m.lessonId, m.target, m.what, false, lo.single().pairs, d0.plusDays(2))
        val neg = lb.lessons.first { it.id == "exp:spec:pine:oi" }
        assertEquals(-1, neg.sign)
        assertEquals("The paper experiment on spec:pine:oi did worse than the current setting", AgentLessons.statement(neg))
        val after = lb.lessons.first { it.id == "spec:pine:oi" }
        assertTrue(after.weight < 1.0); assertNotNull(after.cooldownUntil); assertTrue(after.auto!!.startsWith("experiment lost"))
        // Undecided at its cap: ends, nothing changed.
        // (Each control trade: the manager and the original rules alike; each variant: the specialist's exit behind, then ahead.)
        val mixed = (1..90).map { settled("x$it", "pine", "oi", if (it % 2 == 1) 0.0 else if ((it / 2) % 2 == 0) 400.0 else -390.0, start + it * 3_600_000L) }
        val ended = Experiments.review(Experiments.start(AutoChange.Book(), m, d0, start), d0.plusDays(2), Preflight.Data(records = mixed)).second.single()
        assertEquals(Experiments.Status.ENDED, ended.experiment.status); assertNull(ended.graduate)
        assertEquals(Experiments.MAX_PAIRS, ended.experiment.pairs)
    }

    // ---- regime-aware lessons -------------------------------------------------------------------------------------------------

    @Test fun aRegimeSplitOnlyWhenItPasses() {
        val regimes = (0 until 60).associate { d0.plusDays(it.toLong()) to if (it % 3 == 0) "busy" else "normal" }
        fun lessonOf(rs: (Int) -> Double, n: Int = 2, days: Int = 60): AgentLessons.Lesson {
            val bs = (0 until days).map { AgentLessons.Bucket(d0.plusDays(it.toLong()), n, rs(it), if (rs(it) > 0) n else 0) }
            val e = AgentLessons.evidence(AgentLessons.Kind.RUPEES, "strat:a", bs)
            return AgentLessons.Lesson("strat:a", AgentLessons.Topic.STRATEGY, AgentLessons.Kind.RUPEES, "A", listOf("a"), setOf("t"), bs, e.sign,
                AgentLessons.Status.CONFIRMED, AgentLessons.confidence(e), d0, d0.plusDays(days - 1L), d0)
        }
        // Wins on busy days, loses on the rest: "on busy days only".
        val busy = lessonOf({ k -> if (k % 3 == 0) 900.0 + (k % 7) * 20 else -200.0 - (k % 5) * 15 })
        assertEquals("regime:busy", AgentLessons.conditions(listOf(busy), regimes)[busy.id]?.first)
        assertEquals("A makes money on busy days only", AgentLessons.statement(busy.copy(cond = "regime:busy")))
        // The same everywhere: no condition.
        val flat = lessonOf({ k -> 100.0 + (k % 4) * 10 })
        assertNull(AgentLessons.conditions(listOf(flat), regimes)[flat.id])
        // Too few cases on a side (fewer than 20): no condition, however different.
        val thin = lessonOf({ k -> if (k % 3 == 0) 900.0 else -200.0 }, n = 1, days = 45)
        assertNull(AgentLessons.conditions(listOf(thin), regimes)[thin.id])
        // Through the nightly pass: the strategy's lesson says it, with the split's q in its evidence.
        val trips = (0 until 60).flatMap { k -> (0 until 2).map { j ->
            val d = d0.plusDays(k.toLong())
            Insights.Trip("X", d.atTime(11, j), d.atTime(11, 20 + j), if (k % 3 == 0) 900.0 + (k % 7) * 20 else -200.0 - (k % 5) * 15, "Hero")
        } }
        val pass = AgentLessons.pass(AgentLessons.Book(regimes = regimes), AgentLessons.Inputs(d0.plusDays(60), trips = trips))
        val hero = pass.book.lessons.first { it.id == AgentLessons.norm("strat:Hero") }
        assertEquals("regime:busy", hero.cond)
        assertTrue("on busy days only" in AgentLessons.said(hero), AgentLessons.said(hero))
        assertTrue("(the split: q" in AgentLessons.evidenceLine(hero), AgentLessons.evidenceLine(hero))
        // A change from a regime-only lesson on a target with no per-regime setting is recorded, not applied.
        val c = mute()
        val r = AutoGate.route(AutoChange.Book(), plan(c), mapOf("spec:pine:oi" to lesson("spec:pine:oi", -1, STRONG, 0.001, cond = "regime:busy")), Preflight.Data())
        assertTrue(r.apply.isEmpty())
        assertTrue(r.skipped.single().note.startsWith("recorded, not applied: its lesson holds on busy days only"), r.skipped.single().note)
        assertTrue(AutoChange.Kind.entries.none { AutoChange.condCapable(it) })
    }

    // ---- no overfitting -------------------------------------------------------------------------------------------------------

    @Test fun theTrialCounterRaisesTheBar() {
        assertEquals(0.10, LearnStats.bar(0, 0.10), 1e-12)
        assertEquals(0.05, LearnStats.bar(20, 0.10), 1e-12)
        assertEquals(0.025, LearnStats.bar(60, 0.10), 1e-12)
        assertTrue((0..200 step 10).map { LearnStats.bar(it, 0.10) }.zipWithNext().all { (a, b) -> b < a })
        // Every configuration tried counts: applied by itself, on Boss's yes, started as an experiment; not a skip or an ask.
        var b = AutoChange.Book()
        b = AutoChange.record(b, mute(), AutoChange.Status.PROBATION, 0, d0).first
        b = AutoChange.record(b, mute("solo"), AutoChange.Status.SKIPPED, 0, d0).first
        val (ob, oe) = AutoChange.record(b, mute("night"), AutoChange.Status.OFFERED, 0, d0)
        assertEquals(1, ob.trials)
        b = AutoChange.accepted(ob, oe.id, 1, d0)
        assertEquals(2, b.trials)
        b = Experiments.start(b, mute("vixdiv"), d0, 0)
        assertEquals(3, b.trials)
        assertTrue(AutoGate.header(b).startsWith("Trials so far: 3 · tonight's bar q ≤ "), AutoGate.header(b))
        // A lesson at q 0.04: made at 0 trials; after 60 trials (bar 0.025) it is tried as an experiment instead.
        val l = mapOf("spec:pine:oi" to lesson("spec:pine:oi", -1, STRONG, 0.04))
        assertEquals(1, AutoGate.route(AutoChange.Book(), plan(mute()), l, Preflight.Data()).apply.size)
        val late = AutoGate.route(AutoChange.Book(trials = 60), plan(mute()), l, Preflight.Data())
        assertTrue(late.apply.isEmpty()); assertEquals(1, late.experiments.size)
        // ... and an experiment needs more to win when more has been tried (alpha shared by the trials).
        assertTrue(LearnStats.Sprt.upper(LearnStats.bar(60, LearnStats.Sprt.ALPHA)) > LearnStats.Sprt.upper())
    }

    // ---- determinism, the files, the answers ------------------------------------------------------------------------------------

    @Test fun theSameInputsGiveTheSameNightAndTheFilesRoundTrip() {
        val (lessons, records, trips) = year(120)
        val first = night(lessons, records, trips)
        val second = night(lessons, records, trips)
        assertEquals(AgentLessons.encode(first.first), AgentLessons.encode(second.first))
        assertEquals(AutoChange.encode(first.second), AutoChange.encode(second.second))
        // The new fields survive the files: q and the test on entries, the trials, the experiments, a lesson's p, q and condition.
        var b = AutoChange.record(AutoChange.Book(), mute(), AutoChange.Status.PROBATION, 5, d0, q = 0.0123, preflight = "pre-flight passed: 3 cases replayed").first
        b = AutoChange.review(b, d0.plusDays(1)) { List(6) { i -> if (i % 2 == 0) 50.0 else -40.0 } }.first
        b = Experiments.start(b, mute("solo"), d0, 7)
        val back = AutoChange.decode(AutoChange.encode(b))
        assertEquals(b, back)
        val l = lesson("spec:pine:oi", -1, STRONG, 0.0123, cond = "regime:busy")
        val lback = AgentLessons.decode(AgentLessons.encode(AgentLessons.Book(listOf(l)))).lessons.single()
        assertEquals(0.0123, lback.q!!, 1e-9); assertEquals("regime:busy", lback.cond); assertEquals(0.01, lback.condQ!!, 1e-9)
        // An older file (no p, q, test or trials) still reads.
        val old = AutoChange.encode(b).lines().filter { !it.startsWith("T|") && !it.startsWith("X|") }
            .joinToString("\n") { if (it.startsWith("E|")) it.split('|').take(27).joinToString("|") else it }
        assertEquals(1, AutoChange.decode(old).entries.size); assertEquals(0, AutoChange.decode(old).trials)
    }

    @Test fun jarvisSaysHowSureHeIsAndWhatExperimentsRun() {
        assertEquals(AutoChange.AskKind.SURE, AutoChange.asked("how sure are you about the oi specialist")?.kind)
        assertEquals("the oi specialist", AutoChange.asked("how sure are you about the oi specialist")?.subject)
        assertEquals(AutoChange.AskKind.SURE, AutoChange.asked("Jarvis, how confident are you about Night?")?.kind)
        assertEquals(AutoChange.AskKind.EXPERIMENTS, AutoChange.asked("what experiments are running")?.kind)
        assertEquals(AutoChange.AskKind.EXPERIMENTS, AutoChange.asked("any experiments running?")?.kind)
        // The answers' own confidence (asked before Autopilot in the hub) keeps its words; these are Autopilot's.
        for (s in listOf("how sure are you about the oi specialist", "how confident are you about night", "what experiments are running"))
            assertNull(HonestStars.asked(s), s)
        // Never the market's or an answer's own confidence.
        for (s in listOf("how sure are you", "how confident are you", "how sure are you about nifty going up", "how sure are you about that trade",
            "is the market testing support", "how confident are you on this trade"))
            assertNull(AutoChange.asked(s), s)
        val l = lesson("spec:pine:oi", -1, STRONG, 0.004).copy(name = "The OI specialist on Pine", tags = listOf("oi", "pine", "specialist"))
        var b = AutoChange.record(AutoChange.Book(trials = 4), mute(), AutoChange.Status.PROBATION, 0, d0, q = 0.004).first
        b = AutoChange.review(b, d0.plusDays(1)) { List(6) { i -> if (i % 2 == 0) -300.0 else 250.0 } }.first
        val said = AutoGate.sayHowSure(AgentLessons.Book(listOf(l)), b, "the oi specialist")
        assertTrue(said.startsWith("How sure I am about the oi specialist, Boss: "), said)
        assertTrue("60 settled votes" in said && "q 0.004" in said && "strong" in said, said)
        assertTrue("its test: deciding: " in said && "% of the way" in said, said)
        assertTrue("Trials so far: 5" in said, said)
        assertTrue(AutoGate.sayHowSure(AgentLessons.Book(), AutoChange.Book(), "mornings").startsWith("I have no lesson, change or experiment about mornings"))
    }

    // ---- speed --------------------------------------------------------------------------------------------------------------

    /** A year of synthetic agents' data: lessons from the pass, the trade manager's records, the bots' trades. */
    private fun year(days: Int = 250): Triple<AgentLessons.Inputs, List<TradeManager.Record>, List<Insights.Trip>> {
        val owners = listOf("Liquidity 15+5", "ORB", "ORB Fresh", "Solo", "Hero", "Night (R3)", "VIX divergence", "MCX evening", "Pine", "Range Fade")
        val trips = owners.flatMapIndexed { o, owner ->
            (0 until days).flatMap { k -> (0 until 4).map { j ->
                val d = d0.plusDays(k.toLong())
                Insights.Trip("X", d.atTime(9 + o % 6, j * 5), d.atTime(9 + o % 6, j * 5 + 3), if ((k + o + j) % 3 == 0) -150.0 else 120.0 + (k % 7) * 5, owner)
            } }
        }
        val families = listOf("solo", "pine", "orb_fresh", "vixdiv")
        val specs = listOf("oi", "vwap", "flow", "volume", "traps")
        val records = (0 until 2_000).map { settled("m$it", families[it % 4], specs[it % 5], if (it % 3 == 0) -200.0 else 150.0 + it % 11, ms(d0.plusDays((it % days).toLong()))) }
        val keys = listOf("orb", "orb_fresh", "liquidity:BANKNIFTY", "solo", "pine")
        val flow = (0 until 5_000).map { k ->
            FlowShadow.Signal("s$k", ms(d0.plusDays((k % days).toLong())) / 1000, keys[k % 5], "BANKNIFTY", if (k % 2 == 0) 1 else -1, false, OrderFlow.Mode.SHADOW,
                if (k % 3 == 0) OrderFlow.Agreement.DISAGREES else OrderFlow.Agreement.AGREES, false, 60, net = if (k % 4 == 0) -100.0 else 80.0,
                flags = if (k % 7 == 0) listOf("PULL_BID") else emptyList(), wouldPause = if (k % 11 == 0) listOf("traps") else emptyList(), consensus = (k % 3) to (k % 2))
        }
        return Triple(AgentLessons.Inputs(d0.plusDays(days.toLong()), trips, "normal", emptyList(), records, flow), records, trips)
    }

    /** One whole night: Jarvis's pass, Autopilot's candidates, plan and gate, the probations and the experiments. */
    private fun night(inputs: AgentLessons.Inputs, records: List<TradeManager.Record>, trips: List<Insights.Trip>,
                      lessons0: AgentLessons.Book = AgentLessons.Book(regimes = (0 until 400).associate { d0.plusDays(it.toLong()) to listOf("busy", "quiet", "normal")[it % 3] }),
                      auto0: AutoChange.Book = AutoChange.Book()): Pair<AgentLessons.Book, AutoChange.Book> {
        val day = inputs.day
        val lb = AgentLessons.pass(lessons0, inputs).book
        val ai = AutoChange.Inputs(day, lb.lessons, records, TradeManager.Policy())
        val data = Preflight.Data(records, trips, emptyList(), inputs.flow)
        var b = auto0
        val p = AutoChange.plan(b, AutoChange.candidates(ai), day, AutoChange.Mode.SAFE)
        val r = AutoGate.route(b, p, lb.lessons.associateBy { it.id }, data)
        for (s in r.skipped) b = AutoChange.record(b, s.change, AutoChange.Status.SKIPPED, 0, day, note = s.note, q = s.q).first
        for (c in r.experiments) b = Experiments.start(b, c, day, 0)
        for (g in r.apply) b = AutoChange.record(b, g.change, AutoChange.Status.PROBATION, 0, day, q = g.q, preflight = g.preflight?.words().orEmpty()).first
        b = AutoChange.review(b, day) { e -> Preflight.cases(e.change, data, sinceMs = e.atMs)?.map { it.diff } }.first
        b = Experiments.review(b, day, data).first
        return lb to b
    }

    @Test fun aNightWithAllOfItOverAYearTakesUnder100Ms() {
        val (inputs, records, trips) = year()
        val auto0 = AutoChange.Book(trials = 30, experiments = listOf(Experiments.Experiment("x1", mute(), d0, 0, Experiments.Status.RUNNING)))
        // Warm up (the JIT), then the best of a few.
        repeat(3) { night(inputs, records, trips, auto0 = auto0) }
        var best = Long.MAX_VALUE
        var out: Pair<AgentLessons.Book, AutoChange.Book>? = null
        repeat(5) {
            val t = System.nanoTime()
            out = night(inputs, records, trips, auto0 = auto0)
            best = minOf(best, System.nanoTime() - t)
        }
        assertTrue(best < 250_000_000L, "a night took ${best / 1_000_000} ms")
        val (lb, ab) = out!!
        assertTrue(lb.lessons.size > 50)
        assertTrue(AgentLessons.active(lb).all { it.q != null })
        assertTrue(ab.experiments.isNotEmpty())
    }
}
