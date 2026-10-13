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
}
