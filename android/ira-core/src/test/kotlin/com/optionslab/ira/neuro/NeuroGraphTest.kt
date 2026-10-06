package com.optionslab.ira.neuro

import com.optionslab.ira.NeuroAsk
import com.optionslab.ira.dhan.DhanApi
import com.optionslab.ira.dhan.Plan
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The NeuroGraph on the synthetic store of [NeuroFixture] (a test fixture: none of it is market data). */
class NeuroGraphTest {
    private val today = NeuroFixture.TODAY

    private fun built(): Pair<NeuroFixture, NeuroState> {
        val fx = NeuroFixture(NeuroFixture.tmp()).writeAll()
        return fx to NeuroBuilder.update(fx.files, null, today)
    }

    private val shared: Pair<NeuroFixture, NeuroGraph> by lazy { built().let { (fx, s) -> fx to NeuroLearn.graph(s, 1_000L) } }
    private fun g() = shared.second

    /** Edges with their figures rounded: sums added in another order (an incremental run) differ in the last bits only. */
    private fun List<Edge>.r(): List<String> = map { e ->
        listOf(e.type, e.from, e.to, e.status, e.samples, e.testSamples, e.lag).joinToString("|") + "|" +
            listOf(e.weight, e.base, e.lift, e.lo, e.hi, e.testValue, e.testBase, e.extra).joinToString("|") { "%.9f".format(java.util.Locale.ENGLISH, it) }
    }

    private fun edge(g: NeuroGraph, type: EdgeType, from: String, to: String, lag: Int? = null) =
        g.edges.firstOrNull { it.type == type && it.from == from && it.to == to && (lag == null || it.lag == lag) }

    // ---- statistical guards ---------------------------------------------------------------------------------------------

    @Test fun wilsonAndFisherIntervals() {
        val (lo, hi) = Stats.wilson(8, 10)
        assertTrue(lo in 0.48..0.50 && hi in 0.94..0.95, "$lo $hi")
        assertEquals(0.0 to 1.0, Stats.wilson(0, 0))
        val (a, b) = Stats.fisher(0.5, 103)
        assertTrue(a in 0.33..0.36 && b in 0.62..0.65, "$a $b")
        assertEquals(7, Stats.split(10))
        assertTrue(Stats.pearson(3.0, 0.0, 0.0, 0.0, 0.0, 0.0).isNaN(), "no variation: no correlation")
    }

    @Test fun anEdgeNeedsSamplesLiftAndAnOutOfSampleHoldToBeProven() {
        // Too few samples, too few hits, too little lift: no edge at all.
        assertNull(Stats.judge(9, 10, 0.2, 9, 10, 0.2))
        assertNull(Stats.judge(4, 40, 0.05, 4, 20, 0.05))
        assertNull(Stats.judge(22, 100, 0.2, 10, 40, 0.2))
        // Strong in the fit part and held in the newest 30%: proven.
        val held = Stats.judge(24, 30, 0.2, 10, 13, 0.2)!!
        assertTrue(held.proven && held.lift > 3)
        // The same fit, but the newest 30% did not hold (or had too few samples): only a hypothesis.
        assertFalse(Stats.judge(24, 30, 0.2, 2, 13, 0.2)!!.proven)
        assertFalse(Stats.judge(24, 30, 0.2, 5, 5, 0.2)!!.proven)
        // A weak fit (its 95% lower bound under the base rate) is never proven, whatever the test says.
        assertFalse(Stats.judge(8, 25, 0.22, 13, 13, 0.2)!!.proven)
        // Correlations: proven beyond 0.2 on both parts; weak or flipped is not.
        assertTrue(Stats.judgeCorr(0.8, 100, 0.7, 40)!!.first)
        assertFalse(Stats.judgeCorr(0.8, 100, -0.1, 40)!!.first)
        assertNull(Stats.judgeCorr(0.25, 100, 0.7, 40))
        assertNull(Stats.judgeCorr(0.8, 20, 0.7, 40))
    }

    @Test fun zeroToHeroAndHeroToZeroRules() {
        // Floor: 0.05 -> 2 is 40x on paper but not from a rupee; 1 -> 25 is.
        assertEquals(-1, Rules.contractDay(intArrayOf(600, 601), doubleArrayOf(0.05, 1.5), doubleArrayOf(0.1, 2.0)).first)
        assertEquals(700, Rules.contractDay(intArrayOf(600, 650, 700), doubleArrayOf(1.0, 3.0, 10.0), doubleArrayOf(1.2, 8.0, 26.0)).first)
        // Hero to zero: 40 then 3 later in the day (not the same minute's low).
        assertTrue(Rules.contractDay(intArrayOf(600, 900), doubleArrayOf(30.0, 3.0), doubleArrayOf(40.0, 5.0)).second)
        assertFalse(Rules.contractDay(intArrayOf(600), doubleArrayOf(3.0), doubleArrayOf(40.0)).second)
        // Events and regimes from fixed rules.
        val m = Rules.daily(100.0, 99.0, 101.0, 98.5, 101.2, 1.0, vix = false)
        assertTrue(m and Ev.GAP_DOWN.bit != 0 && m and Ev.BIG_UP.bit != 0 && m and Ev.BIG_RANGE.bit != 0 && m and Ev.GAP_UP.bit == 0)
        assertEquals(Ev.VIX_SPIKE.bit, Rules.daily(14.0, 14.0, 16.0, 14.0, 15.6, Double.NaN, vix = true))
        assertEquals(Regime.TREND_UP, Rules.trend((0..21).map { 100.0 + it * 0.3 }))
        assertEquals(Regime.RANGE, Rules.trend((0..21).map { 100.0 + (it % 2) }))
        assertNull(Rules.trend(listOf(1.0, 2.0)))
        assertEquals(3, Rules.tod(820)); assertEquals(0, Rules.tod(555)); assertEquals(4, Rules.tod(929))
    }

    // ---- the builder on the fixture -----------------------------------------------------------------------------------------

    @Test fun theBuilderFindsThePlantedRelationsAndProvesThemOutOfSample() {
        val g = g()
        val after = edge(g, EdgeType.PRECEDES, "EV:NIFTY:GAP_DOWN", "EV:NIFTY:BIG_UP", NeuroLearn.NEXT_SESSIONS)
        assertNotNull(after, "gap down -> big up")
        assertEquals(Status.PROVEN, after.status)
        assertTrue(after.weight > 0.6 && after.lift > 2.5 && after.samples >= Stats.MIN_FIT && after.testSamples >= Stats.MIN_TEST, "$after")

        val corr = edge(g, EdgeType.CORRELATES, "STK:HDFCBANK", "IDX:BANKNIFTY")
        assertNotNull(corr); assertEquals(Status.PROVEN, corr.status); assertTrue(corr.weight > 0.9)
        assertNull(edge(g, EdgeType.CORRELATES, "STK:YESBANK", "IDX:BANKNIFTY"), "independent noise has no edge")
        assertNull(edge(g, EdgeType.CONTRIBUTES, "STK:YESBANK", "IDX:BANKNIFTY"))

        val contrib = edge(g, EdgeType.CONTRIBUTES, "STK:ICICIBANK", "IDX:BANKNIFTY")
        assertNotNull(contrib); assertEquals(Status.PROVEN, contrib.status)
        assertTrue(contrib.weight > 0.9 && contrib.base < 0.75 && contrib.extra in 0.8..1.2, "$contrib")

        val lead = edge(g, EdgeType.LEADS, "STK:HDFCBANK", "IDX:BANKNIFTY")
        assertNotNull(lead, "HDFCBANK leads BANKNIFTY"); assertEquals(1, lead.lag); assertEquals(Status.PROVEN, lead.status)
        assertTrue(lead.weight > 0.4 && lead.base < 0.1, "$lead")
        assertNull(edge(g, EdgeType.LEADS, "IDX:BANKNIFTY", "STK:HDFCBANK"))

        val bucket = edge(g, EdgeType.OCCURS_IN, "EV:NIFTY:ZERO_TO_HERO", "TOD:3")
        assertNotNull(bucket); assertEquals(Status.PROVEN, bucket.status); assertTrue(bucket.weight > 0.95)
        assertEquals(Status.PROVEN, edge(g, EdgeType.OCCURS_IN, "EV:NIFTY:ZERO_TO_HERO", "EXP:NIFTY")?.status)
        assertEquals(Status.PROVEN, edge(g, EdgeType.OCCURS_IN, "EV:NIFTY:ZERO_TO_HERO", "DAY:TUE")?.status)
        assertNull(edge(g, EdgeType.OCCURS_IN, "EV:NIFTY:ZERO_TO_HERO", "DAY:WED"))

        // Structural edges from the dated lists; nothing learned is ever structural.
        assertEquals(Status.STRUCTURAL, edge(g, EdgeType.CONSTITUENT_OF, "STK:HDFCBANK", "IDX:BANKNIFTY")?.status)
        assertEquals(Status.STRUCTURAL, edge(g, EdgeType.IN_SECTOR, "STK:HDFCBANK", "SEC:Banks")?.status)
        assertEquals(NodeType.BANK, g.byId["STK:HDFCBANK"]?.type)
        assertEquals(NodeType.STOCK, g.byId["STK:RELIANCE"]?.type)
        assertTrue(g.edges.filter { it.type !in setOf(EdgeType.CONSTITUENT_OF, EdgeType.IN_SECTOR) }.all { it.learned })
        // Sequences the plant explains (a gap down is a big-range day, and so on) may be proven; the unplanted events (a gap
        // up, a big down day are pure noise here) prove next to nothing.
        val noise = setOf("EV:NIFTY:GAP_UP", "EV:NIFTY:BIG_DOWN")
        val stray = g.edges.filter { it.proven && it.type == EdgeType.PRECEDES && (it.from in noise || it.to in noise) }
        assertTrue(stray.size <= 1, "stray proven sequences: $stray")
        assertTrue(g.days > 400 && g.from == NeuroFixture.FIRST.plusDays(1) && g.to == NeuroFixture.LAST, "${g.from} ${g.to} ${g.days}")
    }

    @Test fun theBuildIsDeterministic() {
        val (fx, _) = shared
        val again = NeuroLearn.graph(NeuroBuilder.update(fx.files, null, today), 1_000L)
        assertEquals(g().edges, again.edges)
        assertEquals(g().nodes, again.nodes)
    }

    @Test fun incrementalUpdatesReadOnlyNewDaysAndEndWhereAFullBuildEnds() {
        val fx = NeuroFixture(NeuroFixture.tmp()).writeAll()
        val mid = java.time.LocalDate.of(2026, 6, 15)
        val first = NeuroBuilder.update(fx.files, null, mid)
        assertEquals(mid.minusDays(1).toEpochDay().toInt(), first.marks["idx|NIFTY"])
        assertTrue(first.rows.getValue("NIFTY").lastKey() < mid.toEpochDay())
        assertNull(NeuroBuilder.needsFull(fx.files, first))
        val inc = NeuroBuilder.update(fx.files, first, today)
        assertEquals(NeuroFixture.LAST.toEpochDay().toInt(), inc.marks["idx|NIFTY"])
        val full = NeuroBuilder.update(fx.files, null, today)
        assertEquals(NeuroLearn.graph(full, 1L).edges.r(), NeuroLearn.graph(inc, 1L).edges.r())
        // Nothing new: the state's sums do not change.
        val before = NeuroLearn.graph(inc, 1L).edges
        val again = NeuroBuilder.update(fx.files, inc, today)
        assertEquals(before, NeuroLearn.graph(again, 1L).edges)
        // Older data arriving later asks for a full rebuild; deleting data too.
        fx.files.writeText("eq/YESBANK/day/2014-12-28.csv.gz", fx.files.candlesCsv(listOf(DhanApi.Candle(NeuroFixture.t0(java.time.LocalDate.of(2015, 1, 5)), 1.0, 1.0, 1.0, 1.0, 0, 0))))
        assertNotNull(NeuroBuilder.needsFull(fx.files, again))
        fx.files.file("eq/YESBANK").deleteRecursively()
        assertTrue(NeuroBuilder.needsFull(fx.files, again)!!.startsWith("data was deleted"))
        assertNotNull(NeuroBuilder.needsFull(fx.files, null))
    }

    @Test fun aStoppedRunLeavesAConsistentStateThatResumes() {
        val fx = NeuroFixture(NeuroFixture.tmp()).writeAll()
        var calls = 0
        val partial = NeuroBuilder.update(fx.files, null, today, active = { calls++ < 3 })
        assertNull(NeuroBuilder.needsFull(fx.files, partial), "a stopped full build resumes incrementally")
        val resumed = NeuroBuilder.update(fx.files, partial, today)
        assertEquals(NeuroLearn.graph(NeuroBuilder.update(fx.files, null, today), 1L).edges.r(), NeuroLearn.graph(resumed, 1L).edges.r())
    }

    // ---- storage and pruning ------------------------------------------------------------------------------------------------

    @Test fun theGraphAndStateRoundTripAndTheFileIsCapped() {
        val g = g()
        val bo = ByteArrayOutputStream(); g.write(DataOutputStream(bo))
        val back = NeuroGraph.read(DataInputStream(ByteArrayInputStream(bo.toByteArray())))
        assertEquals(g.edges, back.edges); assertEquals(g.nodes, back.nodes); assertEquals(g.days, back.days); assertEquals(g.from, back.from)

        val (fx, s) = built()
        val store = NeuroStore(kotlin.io.path.createTempDirectory("neurostore").toFile())
        store.writeState(s)
        assertEquals(NeuroLearn.graph(s, 5L).edges, NeuroLearn.graph(store.state()!!, 5L).edges)
        val written = store.writeGraph(NeuroLearn.graph(s, 5L), maxBytes = 9_000)
        assertTrue(store.file().length() <= 9_000 || written.edges.none { it.learned }, "${store.file().length()}")
        assertTrue(written.edges.count { !it.learned } == NeuroLearn.graph(s, 5L).edges.count { !it.learned }, "structural edges always stay")
        assertEquals(written.edges, store.graph()!!.edges)
        // A whole build through the store, and a newer-version file is refused (rebuilt), never misread.
        val built = store.build(fx.files, today, 7L)!!
        assertEquals(7L, built.builtAt)
        assertTrue(store.graph()!!.provenCount > 0)
        store.file().writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.graph())
    }

    private fun NeuroStore.file() = java.io.File(root, NeuroStore.GRAPH)

    @Test fun pruningKeepsStructuralThenProvenThenTheStrongest() {
        val g = g()
        val learned = g.edges.count { it.learned }
        val structural = g.edges.size - learned
        val p = g.pruned(structural + 3)
        assertEquals(structural + 3, p.edges.size)
        assertTrue(p.edges.filter { it.learned }.all { it.proven }, "proven first")
        assertTrue(g.pruned(structural).edges.none { it.learned })
        assertTrue(p.nodes.all { n -> n.type == NodeType.INDEX || p.edges.any { it.from == n.id || it.to == n.id } })
    }

    // ---- questions --------------------------------------------------------------------------------------------------------

    @Test fun queriesAnswerFromTheGraphWithRatesAndStatus() {
        val g = g()
        val q = NeuroQuery(g)
        assertEquals("EV:NIFTY:BIG_UP", q.after("NIFTY", Ev.GAP_DOWN).first().to)
        assertEquals("STK:ICICIBANK", q.movers("BANKNIFTY").first().from)
        assertTrue(q.movers("BANKNIFTY").any { it.type == EdgeType.LEADS })
        assertEquals("TOD:3", q.zeroToHero("NIFTY").first { it.to.startsWith("TOD:") }.to)
        assertTrue(q.related("STK:HDFCBANK", "IDX:BANKNIFTY").map { it.type }.containsAll(listOf(EdgeType.CORRELATES, EdgeType.LEADS, EdgeType.CONSTITUENT_OF)))
        assertTrue(q.neighbours("STK:HDFCBANK").first().learned)
        assertTrue(q.strongest(EdgeType.CORRELATES, provenOnly = true).all { it.proven })
        assertEquals(listOf("STK:HDFCBANK", "IDX:BANKNIFTY"), NeuroQuery.names("how related are hdfc bank and banknifty"))
        assertEquals(Ev.GAP_DOWN, NeuroQuery.event("gap down ke baad"))

        val after = NeuroAsk.answer(NeuroAsk.asked("what does the graph say happens after a gap down in nifty")!!, g)
        assertTrue("big up day" in after && "proven" in after && "%" in after && "not forecasts" in after, after)
        val movers = NeuroAsk.answer(NeuroAsk.asked("according to the graph what moves banknifty most")!!, g)
        assertTrue("ICICIBANK" in movers && "not how much each one weighs" in movers, movers)
        val zth = NeuroAsk.answer(NeuroAsk.asked("when do zero to hero moves happen per the graph")!!, g)
        assertTrue("13:00-14:30" in zth, zth)
        val stats = NeuroAsk.answer(NeuroAsk.asked("graph stats")!!, g)
        assertTrue("nodes" in stats && "proven" in stats && "hypotheses" in stats, stats)
        val proven = NeuroAsk.answer(NeuroAsk.asked("what is proven in the graph")!!, g)
        assertTrue("proven" in proven, proven)
        assertEquals(NeuroAsk.NONE, NeuroAsk.answer(NeuroAsk.asked("graph stats")!!, null))
        // Hypotheses are never said as facts.
        for (e in g.edges.filter { it.status == Status.HYPOTHESIS }.take(5)) assertTrue("hypothesis" in q.say(e), q.say(e))
    }

    @Test fun researchHintsAreProvenSequencesAndTimingsOnlyAndPaperOnly() {
        val hints = ResearchHints.from(g())
        assertTrue(hints.isNotEmpty())
        assertTrue(hints.all { it.paperOnly && it.edge.proven && it.edge.type in setOf(EdgeType.PRECEDES, EdgeType.OCCURS_IN) })
        assertTrue(hints.all { "arms nothing" in it.text })
    }

    @Test fun theDetectorRoutesTheGraphQuestions() {
        fun k(s: String) = NeuroAsk.asked(s)?.kind
        assertEquals(NeuroAsk.Kind.AFTER, k("what does the graph say happens after a gap up in nifty"))
        assertEquals(NeuroAsk.Kind.AFTER, k("gap down ke baad kya hota hai graph se batao"))
        assertEquals(NeuroAsk.Kind.MOVERS, k("graph se batao banknifty ko kaun hilata hai"))
        assertEquals(NeuroAsk.Kind.ZERO_TO_HERO, k("zero to hero kab hota hai graph ke hisaab se"))
        assertEquals(NeuroAsk.Kind.RELATED, k("how related are hdfc bank and banknifty in the graph"))
        assertEquals(NeuroAsk.Kind.PROVEN, k("graph mein kya pakka hai"))
        assertEquals(NeuroAsk.Kind.STATS, k("how big is the neurograph"))
        assertEquals(NeuroAsk.Kind.ABOUT, k("what does the graph know about reliance"))
        assertEquals(NeuroAsk.Kind.OVERVIEW, k("data se kya seekha"))
        assertEquals(NeuroAsk.Kind.OVERVIEW, k("what have you learned from the market data"))
        for (s in listOf("show me the nifty graph", "nifty ka graph dikhao", "what have you learned about me", "what moves banknifty most",
            "what happens after a gap up", "delete the graph", "what dhan data do you have"))
            assertNull(NeuroAsk.asked(s), s)
    }
}
