package com.optionslab.ira

import com.optionslab.engine.strategy.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One shared knowledge for every agent (13 Oct): the shipped pack's shared section, the conflict rule (both kept, both sample
 * sizes), the simple API, each agent's reader, the shadow record, the lessons that execution and the study now write, the
 * forge's skips, and the nightly bundle's money kept apart.
 */
class SharedKnowledgeTest {
    private val day = LocalDate.of(2026, 10, 13)
    private val packFile: File = listOf("../app/src/main/assets/knowledge/pack.bin", "android/app/src/main/assets/knowledge/pack.bin")
        .map(::File).first { it.isFile }

    private companion object {
        @Volatile var pack: Knowledge.Pack? = null
    }
    private val real: Knowledge.Pack
        get() = pack ?: synchronized(SharedKnowledgeTest::class) { pack ?: packFile.inputStream().use { Knowledge.read(it) }.also { pack = it } }

    private fun item(id: String, topics: List<String>, verdict: SharedKnowledge.Verdict, gate: SharedKnowledge.Gate, arm: String? = null,
                     source: SharedKnowledge.Source = SharedKnowledge.Source.RESEARCH, n: Int? = null, text: String = id) =
        SharedKnowledge.Item(id, topics, arm, null, null, null, false, source, "2026-10-12", n, null, gate, verdict, text, "research/X.md")

    /** A strategy lesson of Jarvis's: [n] trades over 30 days, winning or losing by [rs] each day. */
    private fun stratLesson(owner: String, rsPerDay: Double, perDay: Int = 2): AgentLessons.Book {
        val trips = (1..30).flatMap { k ->
            val d = day.minusDays(k.toLong())
            (0 until perDay).map { j -> Insights.Trip("BANKNIFTY26OCT52000CE", d.atTime(10, j), d.atTime(11, j), rsPerDay / perDay + (if (j == 0) 1.0 else -0.5), owner) }
        }
        return AgentLessons.pass(AgentLessons.Book(), AgentLessons.Inputs(day, trips = trips)).book
    }

    // ---- the shipped pack ----------------------------------------------------------------------------------------------------

    @Test fun theShippedPackCarriesTheSharedSectionSmallAndTyped() {
        val r = assertNotNull(real.shared, "the pack has no shared section: run research/knowledge/build_pack.py")
        assertTrue(r.items.size in 100..2_000, "${r.items.size} items")
        val sources = r.items.map { it.source }.toSet()
        assertTrue(SharedKnowledge.Source.RESEARCH in sources && SharedKnowledge.Source.EXPERIMENT in sources && SharedKnowledge.Source.OPEN in sources, sources.toString())
        // The experiment log is in it, R14 with its verdict; the S7 question is open.
        val r14 = r.items.first { it.id == "exp:r14" }
        assertEquals(SharedKnowledge.Gate.PASS, r14.gate)
        assertEquals(SharedKnowledge.Gate.OPEN, r.items.first { it.id == "open:s7-order-flow" }.gate)
        // The execution baselines (h24's real spread, the signal multiplier).
        assertEquals(0.16, r.baselines.spreads.first { it.inst == "BANKNIFTY" }.halfSpreadPct)
        assertEquals(2.0, r.baselines.signalX)
        // Strategy cards carry their app strategy: ORB's card says it does not pay.
        val orb = SharedKnowledge.verdict(SharedKnowledge.build(real, null), "orb")
        assertEquals(SharedKnowledge.Verdict.FAILS, orb.verdict, orb.words())
        assertTrue(orb.research!!.id.startsWith("strategy.orb."), orb.research!!.id)
    }

    @Test fun buildingIsCheapAndTheSnapshotIsSmall() {
        val book = stratLesson("ORB", 400.0)
        val t0 = System.nanoTime()
        val s = SharedKnowledge.build(real, book, emptyList(), day)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 2_000, "built in $ms ms")
        assertTrue(s.size < 3_000, "${s.size} items")
        assertTrue(s.summary().contains("research items"), s.summary())
    }

    // ---- the conflict rule -----------------------------------------------------------------------------------------------------

    @Test fun anAppLessonAgainstTheResearchKeepsBothAndMarksBothWithTheirSampleSizes() {
        val book = stratLesson("ORB", 400.0)
        val l = AgentLessons.active(book).first { it.id == "strat:orb" }
        assertEquals(1, l.sign)
        val s = SharedKnowledge.build(real, book, emptyList(), day)
        val app = s.items.first { it.id == "jarvis:strat:orb" }
        val c = assertNotNull(app.conflict, "the app lesson is marked")
        assertEquals("orb", c.arm)
        assertEquals(SharedKnowledge.Verdict.FAILS, c.researchVerdict)
        assertEquals(SharedKnowledge.Verdict.WORKS, c.appVerdict)
        assertEquals(AgentLessons.evidence(l).n, c.appN)
        // The research item is kept too, and marked with the same conflict.
        val research = s.items.first { it.id == c.researchId }
        assertEquals(c, research.conflict)
        assertTrue(s.conflicts.isNotEmpty())
        val words = SharedKnowledge.verdict(s, "orb").words()
        assertTrue("conflict" in words.lowercase() && "both kept" in words && "n ${c.appN}" in words, words)
        // A lesson that agrees with the research is no conflict.
        val agree = SharedKnowledge.build(real, stratLesson("ORB", -400.0), emptyList(), day)
        assertNull(agree.items.first { it.id == "jarvis:strat:orb" }.conflict)
    }

    @Test fun theConflictRuleOnPlainItems() {
        val r = item("strategy.x.what", listOf("strategy"), SharedKnowledge.Verdict.WORKS, SharedKnowledge.Gate.PASS, arm = "night", n = 300)
        val a = item("app:strat:night", listOf("profit"), SharedKnowledge.Verdict.FAILS, SharedKnowledge.Gate.FAIL, arm = "night",
            source = SharedKnowledge.Source.APP, n = 40)
        val other = item("app:strat:solo", listOf("profit"), SharedKnowledge.Verdict.FAILS, SharedKnowledge.Gate.FAIL, arm = "solo", source = SharedKnowledge.Source.APP)
        val m = SharedKnowledge.marked(listOf(r, a, other))
        assertEquals(300, m[1].conflict!!.researchN)
        assertEquals(40, m[1].conflict!!.appN)
        assertEquals(m[1].conflict, m[0].conflict)
        assertNull(m[2].conflict, "no research verdict on Solo: nothing to disagree with")
    }

    // ---- the simple API --------------------------------------------------------------------------------------------------------

    @Test fun aboutFiltersByStrategyAndTopicAndRanksDecisiveResearchFirst() {
        val s = SharedKnowledge.build(real, null)
        val orb = SharedKnowledge.about(s, arm = "orb")
        assertTrue(orb.isNotEmpty() && orb.all { it.arm == "orb" || "orb" in it.topics }, orb.map { it.id }.toString())
        val flow = SharedKnowledge.about(s, topic = "order-flow", max = 30)
        assertTrue(flow.all { "order-flow" in it.topics })
        assertTrue(flow.first().gate != SharedKnowledge.Gate.NONE)
    }

    @Test fun theExecutionBaselineIsTheRealSpreadWiderNearTheOpenAndAwayFromTheMoney() {
        val s = SharedKnowledge.build(real, null)
        val mid = assertNotNull(SharedKnowledge.executionBaseline(s, "BANKNIFTY26OCT52000CE", 11 * 60))
        assertEquals("BANKNIFTY", mid.inst)
        assertEquals(0.16, mid.expectedPct, 1e-9)
        assertFalse(mid.assumed)
        assertEquals(133.0, mid.roundTripRs)
        val open = SharedKnowledge.executionBaseline(s, "NFO:BANKNIFTY26OCT52000CE", 9 * 60 + 20)!!
        assertEquals(0.32, open.expectedPct, 1e-9)
        assertTrue(open.assumed && "assumption" in open.words(), open.words())
        assertEquals(0.32, SharedKnowledge.executionBaseline(s, "BANKNIFTY26OCT52000CE", 12 * 60, strikesAway = 4)!!.expectedPct, 1e-9)
        assertEquals(0.42, SharedKnowledge.executionBaseline(s, "FINNIFTY26OCT24000PE", 12 * 60)!!.halfSpreadPct)
        assertNull(SharedKnowledge.executionBaseline(s, "CRUDEOIL26NOVFUT", 12 * 60))
        assertTrue(SharedKnowledge.offBaseline(mid, 0.5))
        assertFalse(SharedKnowledge.offBaseline(mid, 0.2))
    }

    @Test fun fillsAgainstTheBaselineBecomeExecutionLessonsInTheSameFormat() {
        val s = SharedKnowledge.build(real, null)
        val fills = (1..12).flatMap { k ->
            val d = day.minusDays(k.toLong())
            (0 until 4).map { j -> SharedKnowledge.FillCheck(d, "ORB", "BANKNIFTY26OCT52000CE", 11 * 60, if (j == 0) 0.9 else 0.05) }
        }
        val ex = SharedKnowledge.execResults(s, fills, "fills")
        assertEquals(12, ex.size)
        assertTrue(ex.all { it.n == 4 && it.hits == 1 && it.key == "fills:orb:banknifty" && it.agent == "the slippage record" })
        val p = AgentLessons.pass(AgentLessons.Book(), AgentLessons.Inputs(day, exec = ex))
        val l = p.book.lessons.first { it.id == "exec:fills:orb:banknifty" }
        assertEquals(AgentLessons.Topic.EXECUTION, l.topic)
        assertEquals(-1, l.sign, "a quarter off the baseline is over the 20% bar")
        assertEquals("ORB's fills on BANKNIFTY are often off the research's baseline", AgentLessons.statement(l))
        assertTrue(AgentLessons.evidenceLine(l).startsWith("12 of 48 fills off the baseline"), AgentLessons.evidenceLine(l))
        assertTrue("the slippage record" in l.agents)
        // Kept and read back in the lessons' own file format.
        val back = AgentLessons.decode(AgentLessons.encode(p.book)).lessons.first { it.id == l.id }
        assertEquals(listOf(l.topic, l.kind, l.name, l.sign, l.confidence), listOf(back.topic, back.kind, back.name, back.sign, back.confidence))
        assertEquals(l.buckets, back.buckets)
        assertEquals(AgentLessons.Topic.EXECUTION, AgentLessons.asked("what have you learned about slippage")?.topic)
        // The Liquidity paper test's spreads come in as their own agent.
        val sp = SharedKnowledge.execResults(s, fills.take(4), "spread")
        assertEquals("the Liquidity paper test", sp.single().agent)
    }

    @Test fun theStudyWritesOneSnapshotASetupNeverAddedUpNightAfterNight() {
        val f = Study.Finding(Market.NIFTY, "gapup", "a gap up of 0.5% or more", "the gap filled", 80, 0.7, 0.68, 0.72)
        var b = AgentLessons.Book()
        for (k in 5 downTo 0) b = AgentLessons.pass(b, AgentLessons.Inputs(day.minusDays(k.toLong()), study = Study.results(listOf(f), day.minusDays(k.toLong())))).book
        val l = b.lessons.first { it.id == "study:nifty:gapup" }
        assertEquals(AgentLessons.Topic.STUDY, l.topic)
        assertEquals(AgentLessons.Kind.SNAPSHOT, l.kind)
        assertEquals(80, AgentLessons.evidence(l).n, "the latest count, not six nights added up")
        assertEquals(1, l.sign)
        assertTrue(AgentLessons.statement(l).endsWith("held in both years of my study"), AgentLessons.statement(l))
        assertTrue(AgentLessons.evidenceLine(l).startsWith("80 days counted, held on 6 of 6 nightly counts"), AgentLessons.evidenceLine(l))
        assertTrue(AgentLessons.proposals(AgentLessons.active(b), AgentLessons.Inputs(day)).isEmpty(), "a study lesson never proposes")
    }

    // ---- each agent's reader -----------------------------------------------------------------------------------------------------

    @Test fun theTrapsSpecialistReadsTheTrapFindingsAsUnproven() {
        val s = SharedKnowledge.build(real, null)
        val traps = assertNotNull(SharedKnowledge.specialistNote(s, "traps"))
        assertTrue("traps" in traps && "still open" in traps, traps)
        assertNotNull(SharedKnowledge.specialistNote(s, "flow"))
        assertNull(SharedKnowledge.specialistNote(s, "nobody"))
    }

    @Test fun orderReviewFlawsCiteTheFinding() {
        val s = SharedKnowledge.build(real, null)
        val chg = assertNotNull(SharedKnowledge.flawCite(s, "chg", "ORB"))
        assertTrue("research:" in chg && ("R14" in chg || "FINDINGS" in chg || "r14" in chg.lowercase()), chg)
        val slp = assertNotNull(SharedKnowledge.flawCite(s, "slp"))
        assertTrue("BANKNIFTY 0.16%" in slp && "HUNT_H24.md" in slp, slp)
        assertNull(SharedKnowledge.flawCite(s, "rej"))
    }

    @Test fun flowGateSaysTheResearchVerdictIsStillUnproven() {
        val v = assertNotNull(SharedKnowledge.flowVerdict(SharedKnowledge.build(real, null)))
        assertTrue(v.startsWith("Research verdict on order flow: still unproven") && "S7" in v && "Shadow log only" in v, v)
    }

    @Test fun theStudySkipsWhatTheResearchSettledAndAsksOpenQuestionsFirst() {
        val s = SharedKnowledge.build(real, null)
        val plan = SharedKnowledge.studyPlan(s, Study.KEYS)
        assertTrue("f15up" in plan.skipped && "f15down" in plan.skipped, plan.skipped.toString())
        assertTrue(plan.skipped.values.all { "settled by the research" in it })
        assertTrue(plan.order.none { it in plan.skipped })
        assertEquals(Study.KEYS.size, plan.order.size + plan.skipped.size)
        assertTrue(plan.open.isNotEmpty() && plan.open.any { "order flow" in it.lowercase() }, plan.open.toString())
        // Planted: an open question on gaps puts the gap setups first and keeps them.
        val open = SharedKnowledge.Snapshot(s.items + item("open:gaps", listOf("gap"), SharedKnowledge.Verdict.OPEN, SharedKnowledge.Gate.OPEN,
            source = SharedKnowledge.Source.OPEN), s.baselines, "", null)
        assertEquals(setOf("gapup", "gapdown"), SharedKnowledge.studyPlan(open, Study.KEYS).order.take(2).toSet())
        // Study.run counts only the planned setups, in order.
        val bars = (0 until 60).flatMap { d ->
            val date = LocalDate.of(2025, 1, 1).plusDays(d.toLong())
            (0 until 75).map { m -> Candle(date.atTime(9, 15).plusMinutes(5L * m), 100.0 + d + m * 0.01, 100.5 + d + m * 0.01, 99.5 + d, 100.2 + d + m * 0.01) }
        }
        val all = Study.run(Market.NIFTY, bars)
        val some = Study.run(Market.NIFTY, bars, plan.order)
        assertTrue(some.none { it.key in plan.skipped })
        assertEquals(all.filter { it.key !in plan.skipped }.map { it.key }.toSet(), some.map { it.key }.toSet())
    }

    @Test fun theForgeSkipsOnlyThePlainFormOfARejectedIdeaAndSeedsFromTheFindings() {
        val s = SharedKnowledge.build(real, null)
        assertNotNull(SharedKnowledge.forgeAvoid(s, "ORB_BREAK", 0))
        assertNull(SharedKnowledge.forgeAvoid(s, "ORB_BREAK", 1), "a filter makes it a new idea")
        assertNull(SharedKnowledge.forgeAvoid(s, "LEVEL_SWEEP", 0))
        val prior = SharedKnowledge.forgePrior(s)
        assertEquals(0.0 to 1.0, prior["entry:ORB_BREAK"])
        // In the night: skipped ideas are listed, never counted as tried.
        val noise = LabTestData.days(140, 1234)
        val avoid: (StrategyForge.Candidate) -> String? = { c -> if (c.rules.filters.isEmpty()) SharedKnowledge.forgeAvoid(s, c.rules.entry.name, 0) else null }
        val n = StrategyForge.night(StrategyForge.Book(), day, mapOf("BANKNIFTY" to noise), { 0L }, n = 8, avoid = avoid, prior = prior)
        assertTrue(n.made.none { it.c.rules.filters.isEmpty() && avoid(it.c) != null })
        assertEquals(n.made.size, n.book.tried)
        assertTrue(n.skipped.all { "rejected" in it.second })
    }

    @Test fun theLabDoesNotRetestWhatR14RejectedAndNeverGatesLiquidity() {
        val s = SharedKnowledge.build(real, null)
        assertNotNull(SharedKnowledge.variantRejected(s, "orb", ArmParams.SIDE_CAP, +1))
        assertNotNull(SharedKnowledge.variantRejected(s, "orb_fresh", ArmParams.TARGET_PTS, +1))
        assertNull(SharedKnowledge.variantRejected(s, "orb", ArmParams.SIDE_CAP, -1))
        assertNull(SharedKnowledge.variantRejected(s, "liquidity", ArmParams.LOCK, +1), "Liquidity is frozen: never gated")
    }

    @Test fun jarvisMergesResearchAndLessonsWithSourcesAndSaysTheConflict() {
        val s = SharedKnowledge.build(real, stratLesson("ORB", 400.0), emptyList(), day)
        val open = SharedKnowledge.answer(s, "is ORB profitable", locked = false)
        assertTrue(open.first().contains("the research says it did not pay") && "conflict" in open.first().lowercase(), open.toString())
        val locked = SharedKnowledge.answer(s, "is ORB profitable", locked = true)
        assertTrue(locked.none { "Jarvis's lessons" in it || "conflict" in it.lowercase() }, locked.toString())
        assertTrue(SharedKnowledge.answer(s, "what is the weather", locked = false).isEmpty())
    }

    // 13 Oct review: a research item marked with a conflict carries the lesson's verdict and n in its conflict note. A locked phone
    // asked a topic-only question ("what about vix?") must say the research only - no lesson text, no lesson n, no lesson result.
    @Test fun aLockedPhoneAskedATopicOnlyQuestionSaysNoLessonEvenWhenAConflictExists() {
        val r = item("strategy.vixdiv.what", listOf("vix", "expiry"), SharedKnowledge.Verdict.WORKS, SharedKnowledge.Gate.PASS, arm = "vixdiv",
            n = 312, text = "VIX divergence held up in both tested years")
        val jarvis = item("jarvis:strat:vixdiv", listOf("profit", "vix", "expiry"), SharedKnowledge.Verdict.FAILS, SharedKnowledge.Gate.FAIL,
            arm = "vixdiv", source = SharedKnowledge.Source.JARVIS, n = 47, text = "VIX divergence loses on paper (47 trades, strong)")
        val phone = item("app:strat:vixdiv", listOf("profit", "vix", "expiry"), SharedKnowledge.Verdict.FAILS, SharedKnowledge.Gate.FAIL,
            arm = "vixdiv", source = SharedKnowledge.Source.APP, n = 41, text = "The phone: VIX divergence lost on paper")
        val s = SharedKnowledge.Snapshot(SharedKnowledge.marked(listOf(r, jarvis, phone)), SharedKnowledge.Baselines(), "", null)
        assertNotNull(s.items.first { it.id == r.id }.conflict, "the research item is marked")
        for (q in listOf("what about vix?", "expiry", "tell me about VIX on expiry")) {
            val open = SharedKnowledge.answer(s, q, locked = false)
            assertTrue(open.any { "conflict" in it.lowercase() && "n 47" in it }, "$q: $open")
            val locked = SharedKnowledge.answer(s, q, locked = true)
            assertTrue(locked.isNotEmpty() && locked.any { "VIX divergence held up" in it }, "$q: the research is still said: $locked")
            for (line in locked) {
                val l = line.lowercase()
                assertFalse("conflict" in l || "lesson" in l || "phone" in l || "jarvis" in l, "$q: $line")
                assertFalse("n 47" in line || "n 41" in line || "47 trades" in line, "$q: a lesson's n on a locked phone: $line")
                assertFalse("did not pay" in l || "loses" in l || "lost on paper" in l, "$q: a lesson's result on a locked phone: $line")
            }
        }
        // A strategy question on a locked phone: the research's verdict only, too.
        val arm = SharedKnowledge.answer(s, "is vix divergence working", locked = true)
        assertTrue(arm.isNotEmpty() && arm.none { "conflict" in it.lowercase() || "n 47" in it || "did not pay" in it }, arm.toString())
    }

    @Test fun aLockedPhoneSaysNoLessonOnAnyTopicOfTheRealPack() {
        val book = stratLesson("ORB", 400.0)
        val s = SharedKnowledge.build(real, book, emptyList(), day)
        val c = assertNotNull(s.items.first { it.id == "jarvis:strat:orb" }.conflict)
        for (q in listOf("order flow", "traps", "slippage", "spread", "charges", "re-entry", "vix", "expiry", "gap", "vwap", "oi", "trade manager",
                "direction", "is ORB profitable", "orb")) {
            for (line in SharedKnowledge.answer(s, q, locked = true)) {
                assertFalse("conflict" in line.lowercase() || "Jarvis's lessons" in line || "phone's lessons" in line, "$q: $line")
                assertFalse("(n ${c.appN})" in line || "n ${c.appN};" in line, "$q: $line")
            }
        }
    }

    // ---- an empty or old pack through every reader (13 Oct review) --------------------------------------------------------------

    @Test fun anEmptyOrOldPackGivesEveryReaderNothingAndNeverThrows() {
        val old = Knowledge.Pack(1, "old", "test", emptyList(), shared = null)
        val withLessons = SharedKnowledge.build(old, stratLesson("ORB", 400.0), emptyList(), day)
        for (s in listOf(SharedKnowledge.EMPTY, SharedKnowledge.build(old, null), SharedKnowledge.build(null as Knowledge.Pack?, null), withLessons)) {
            assertTrue(s.items.none { !it.source.app && it.source != SharedKnowledge.Source.FINDINGS }, "no research in an old pack")
            assertNull(SharedKnowledge.flawCite(s, "chg", "ORB"))
            assertNull(SharedKnowledge.flawCite(s, "slp"))
            assertNull(SharedKnowledge.flawCite(s, "late"))
            for (sp in SharedKnowledge.SPEC_TOPICS.keys) assertNull(SharedKnowledge.specialistNote(s, sp, "orb"), sp)
            val plan = SharedKnowledge.studyPlan(s, Study.KEYS)
            assertEquals(Study.KEYS, plan.order, "every setup counted, in its own order")
            assertTrue(plan.skipped.isEmpty() && plan.open.isEmpty())
            assertEquals(emptyList(), SharedKnowledge.studyPlan(s, emptyList()).order)
            for (e in listOf("ORB_BREAK", "RANGE_FADE", "OI_BUILDUP", "LEVEL_SWEEP", "NOPE")) for (f in 0..1) assertNull(SharedKnowledge.forgeAvoid(s, e, f), e)
            assertTrue(SharedKnowledge.forgePrior(s).isEmpty())
            assertNull(SharedKnowledge.variantRejected(s, "orb", ArmParams.SIDE_CAP, +1))
            assertNull(SharedKnowledge.variantRejected(s, "orb_fresh", ArmParams.TARGET_PTS, +1))
            assertNull(SharedKnowledge.flowVerdict(s))
            assertNull(SharedKnowledge.executionBaseline(s, "BANKNIFTY26OCT52000CE", 11 * 60))
            assertTrue(SharedKnowledge.execResults(s, listOf(SharedKnowledge.FillCheck(day, "ORB", "BANKNIFTY26OCT52000CE", 600, 1.0)), "fills").isEmpty())
            assertNull(SharedKnowledge.verdict(s, "orb").research)
            assertTrue(SharedKnowledge.shadowSkips(s, listOf(Insights.Trip("X", day.atTime(10, 0), day.atTime(10, 30), -1.0, "ORB")), day).isEmpty())
            for (q in listOf("what about vix?", "expiry", "is ORB profitable", "", "what is the weather"))
                assertTrue(SharedKnowledge.answer(s, q, locked = true).isEmpty(), "$q on a locked phone: ${SharedKnowledge.answer(s, q, locked = true)}")
            assertTrue(SharedKnowledge.answer(s, "what about vix?", locked = false).isEmpty())
        }
        // Unlocked, the lessons alone still answer about ORB (no research beside them), each with its source.
        val orb = SharedKnowledge.answer(withLessons, "is ORB profitable", locked = false)
        assertTrue(orb.isNotEmpty() && orb.first().startsWith("ORB: no research verdict yet"), orb.toString())
        assertTrue(SharedKnowledge.EMPTY.summary().startsWith("0 research items"), SharedKnowledge.EMPTY.summary())
    }

    // ---- the market study: never stronger than building, never counted twice (13 Oct review) ------------------------------------

    @Test fun aStudyLessonNeverBecomesStrongOrAPassedTest() {
        val f = Study.Finding(Market.NIFTY, "gapup", "a gap up of 0.5% or more", "the gap filled", 300, 0.75, 0.74, 0.76)
        var b = AgentLessons.Book()
        for (k in 60 downTo 0) {
            val d = day.minusDays(k.toLong())
            b = AgentLessons.pass(b, AgentLessons.Inputs(d, study = Study.results(listOf(f), d))).book
        }
        val l = b.lessons.first { it.id == "study:nifty:gapup" }
        assertEquals(AgentLessons.Confidence.BUILDING, l.confidence, "61 nightly recounts of the same two years are not independent agreement")
        assertTrue(l.status != AgentLessons.Status.CONFIRMED)
        assertTrue(AgentLessons.proposals(AgentLessons.active(b), AgentLessons.Inputs(day)).isEmpty())
        assertTrue("stays at building" in AgentLessons.sayWhy(listOf(l)), AgentLessons.sayWhy(listOf(l)))
        val item = SharedKnowledge.item(l)
        assertEquals(SharedKnowledge.Gate.NONE, item.gate)
        // An older book that still holds a strong study lesson: never a passed test, and the next pass brings it down.
        val old = l.copy(confidence = AgentLessons.Confidence.STRONG, status = AgentLessons.Status.CONFIRMED)
        assertEquals(SharedKnowledge.Gate.NONE, SharedKnowledge.item(old).gate)
        val next = AgentLessons.pass(b.copy(lessons = b.lessons.map { if (it.id == l.id) old else it }), AgentLessons.Inputs(day.plusDays(1))).book
        assertEquals(AgentLessons.Confidence.BUILDING, next.lessons.first { it.id == l.id }.confidence)
        // The cap is the study's only: other topics still reach strong.
        assertEquals(AgentLessons.Confidence.STRONG, AgentLessons.ceiling(AgentLessons.Topic.STRATEGY, AgentLessons.Confidence.STRONG))
        assertEquals(AgentLessons.Confidence.BUILDING, AgentLessons.ceiling(AgentLessons.Topic.STUDY, AgentLessons.Confidence.STRONG))
        assertEquals(AgentLessons.Confidence.WEAK, AgentLessons.ceiling(AgentLessons.Topic.STUDY, AgentLessons.Confidence.WEAK))
        // Bundled for the research as building, never strong (so never imported as a confirmed "app" positive).
        val bundle = LessonsBundle.build(LessonsBundle.Inputs(day, "test", "v1", b, emptyList()))
        val lessons = Json.parse(LessonsBundle.text(bundle, null)).let { (it as Map<*, *>)["lessons"] as List<*> }
        val sent = lessons.map { it as Map<*, *> }.first { it["id"] == l.id }
        assertEquals("building", sent["confidence"])
    }

    @Test fun aStaleStudyIsNeverSentAgain() {
        val f = Study.Finding(Market.NIFTY, "gapup", "a gap up of 0.5% or more", "the gap filled", 80, 0.7, 0.68, 0.72)
        val ist = java.time.ZoneId.of("Asia/Kolkata")
        fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(ist).toInstant()
        // Today's: made this morning (yesterday's session) or after today's close - dated today.
        assertEquals(listOf(day), Study.freshResults(listOf(f), at(day, 3, 50), day).map { it.day })
        assertEquals(listOf(day), Study.freshResults(listOf(f), at(day, 15, 46), day).map { it.day })
        // 00:30 IST is the previous evening in UTC: still today's study (the IST date counts).
        assertEquals(1, Study.freshResults(listOf(f), at(day, 0, 30), day).size)
        // Stale: last night's, a week old, or none / nothing found.
        assertTrue(Study.freshResults(listOf(f), at(day.minusDays(1), 20), day).isEmpty())
        assertTrue(Study.freshResults(listOf(f), at(day.minusDays(1), 23, 59), day).isEmpty())
        assertTrue(Study.freshResults(listOf(f), at(day.minusDays(7), 16), day).isEmpty())
        assertTrue(Study.freshResults(listOf(f), null, day).isEmpty())
        assertTrue(Study.freshResults(emptyList(), at(day, 16), day).isEmpty())
        // A study made once and left (the study did not run again - no candles, a holiday) is counted on its night only.
        val madeAt = at(day.minusDays(5), 16)
        var b = AgentLessons.Book()
        for (k in 5 downTo 0) {
            val d = day.minusDays(k.toLong())
            b = AgentLessons.pass(b, AgentLessons.Inputs(d, study = Study.freshResults(listOf(f), madeAt, d))).book
        }
        val l = b.lessons.first { it.id == "study:nifty:gapup" }
        assertEquals(1, l.buckets.size, "one night counted, not six")
        assertTrue(AgentLessons.evidenceLine(l).contains("1 of 1 nightly count"), AgentLessons.evidenceLine(l))
    }

    @Test fun theShadowSkipCountsNeverLiquidityAndOnlyWhatTheResearchFailed() {
        val s = SharedKnowledge.build(real, null)
        val t = { owner: String, net: Double -> Insights.Trip("BANKNIFTY26OCT52000CE", day.atTime(10, 0), day.atTime(10, 30), net, owner) }
        val sk = SharedKnowledge.shadowSkips(s, listOf(t("ORB", -500.0), t("ORB", 200.0), t("Liquidity 15m", -100.0), t("Liquidity 15+5", 50.0),
            Insights.Trip("X", LocalDateTime.of(day.minusDays(1), java.time.LocalTime.NOON), day.minusDays(1).atTime(13, 0), 1.0, "ORB")), day)
        assertEquals(listOf("orb"), sk.map { it.arm })
        assertEquals(2, sk.single().wouldSkip)
        assertEquals(-300.0, sk.single().rs)
    }

    // ---- the nightly bundle --------------------------------------------------------------------------------------------------------

    @Test fun theBundleKeepsMoneyApartAndTheExportDecryptsItIn() {
        val book = stratLesson("ORB", 400.0)
        val b = LessonsBundle.build(LessonsBundle.Inputs(day, "test", "v1", book, listOf(LessonsBundle.FindingCount("DATA_HEALTH", "data", 3, 2)),
            shadow = listOf(SharedKnowledge.ShadowSkip("r", "orb", 2, 2, -321.0, "why")), agents = listOf("Order review: read 2")))
        val text = LessonsBundle.text(b, "ENCRYPTED==")
        val plain = Json.parse(text) as Map<*, *>
        assertEquals(LessonsBundle.KIND, plain["kind"])
        assertEquals("ENCRYPTED==", plain["money_enc"])
        assertFalse(Regex("\"(rs|net|money|pnl)\"").containsMatchIn(text.replace("\"money_enc\"", "")), text)
        assertFalse("321" in text, "a shadow skip's rupees stay out of the plain part")
        val lessons = plain["lessons"] as List<*>
        assertTrue(lessons.isNotEmpty())
        val money = Json.parse(LessonsBundle.moneyJson(b)) as Map<*, *>
        assertTrue((money["lessons"] as Map<*, *>).containsKey("strat:orb"))
        assertEquals(-321.0, (money["shadow"] as Map<*, *>)["r|orb"])
        val opened = Json.parse(LessonsBundle.decrypted(text, LessonsBundle.moneyJson(b))) as Map<*, *>
        assertNull(opened["money_enc"])
        assertTrue((opened["money"] as Map<*, *>).containsKey("lessons"))
        val gap = Json.parse(LessonsBundle.decrypted(text, null)) as Map<*, *>
        assertEquals("could not be decrypted", (gap["money"] as Map<*, *>)["gap"])
    }

    // 13 Oct review: when the Vault cannot encrypt (the host passes null), the bundle is written without the money at all - no
    // money_enc and never the money in plain - and the export says nothing of money rather than inventing a figure.
    @Test fun whenEncryptionFailsTheBundleCarriesNoMoneyAtAll() {
        val book = stratLesson("ORB", 400.0)
        val b = LessonsBundle.build(LessonsBundle.Inputs(day, "test", "v1", book, emptyList(),
            shadow = listOf(SharedKnowledge.ShadowSkip("r", "orb", 2, 2, -321.0, "why"))))
        val text = LessonsBundle.text(b, null)
        val plain = Json.parse(text) as Map<*, *>
        assertFalse(plain.containsKey("money_enc"))
        assertFalse(plain.containsKey("money"))
        assertFalse(Regex("\"(rs|net|money|money_enc|pnl|rupees)\"").containsMatchIn(text), text)
        assertFalse("321" in text, "a shadow skip's rupees stay out")
        // The ORB lesson's rupees (the money part's) appear nowhere in the plain text.
        val money = (Json.parse(LessonsBundle.moneyJson(b)) as Map<*, *>)["lessons"] as Map<*, *>
        val rs = (money["strat:orb"] as Number).toDouble()
        assertTrue(kotlin.math.abs(rs) > 1_000.0, "$rs")
        assertFalse(kotlin.math.abs(rs).toLong().toString() in text, text)
        // Rates, counts and effects are still there for the research.
        assertTrue((plain["lessons"] as List<*>).isNotEmpty())
        val opened = Json.parse(LessonsBundle.decrypted(text, null)) as Map<*, *>
        assertFalse(opened.containsKey("money") || opened.containsKey("money_enc"), opened.keys.toString())
    }
}
