package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The strategy lab's results are Jarvis's evidence: his nightly digest forms "lab:" lessons with the same confidence and BH rules. */
class LabEvidenceTest {
    private val day = LocalDate.of(2026, 10, 12)
    private fun d(k: Int) = day.minusDays(k.toLong())

    private val vwap = StrategyForge.key(ShadowSim.Entry.VWAP_RECLAIM)

    /** [days] days of [perDay] tests of [key]; [passEvery]: one in so many passes (0: none). */
    private fun results(key: String, name: String, days: Int, perDay: Int, passEvery: Int): List<AgentLessons.LabResult> =
        (0 until days).flatMap { k -> (0 until perDay).map { j ->
            val i = k * perDay + j
            AgentLessons.LabResult(d(days - k), key, name, passEvery > 0 && i % passEvery == 0, -800.0)
        } }

    @Test fun aBlockThatKeepsFailingBecomesAStrongLabLessonWithItsNumbers() {
        val lab = results("block:$vwap", "Ideas using VWAP reclaim", 12, 4, 16)
        val p = AgentLessons.pass(AgentLessons.Book(), AgentLessons.Inputs(day, lab = lab))
        val l = p.book.lessons.firstOrNull { it.id == AgentLessons.norm("lab:block:$vwap") }
        assertNotNull(l, p.book.lessons.map { it.id }.toString())
        assertEquals(AgentLessons.Topic.LAB, l.topic)
        assertEquals(AgentLessons.Kind.RATE, l.kind)
        assertEquals(-1, l.sign)
        assertEquals(AgentLessons.Confidence.STRONG, l.confidence, AgentLessons.said(l))
        assertTrue(l.q != null && l.q!! <= AgentLessons.BH_Q)
        assertEquals("Ideas using VWAP reclaim mostly failed in the strategy lab", AgentLessons.statement(l))
        val ev = AgentLessons.evidenceLine(l)
        assertTrue(ev.startsWith("passed 3 of 48 lab tests, -Rs 38,400"), ev)
        assertTrue("from the strategy lab" in ev, ev)
        // Found by its topic and by "the lab" in a question.
        assertEquals(AgentLessons.Topic.LAB, AgentLessons.asked("what do you know about the forge")?.topic)
        assertTrue(AgentLessons.about(p.book, AgentLessons.Ask(AgentLessons.AskKind.ABOUT, AgentLessons.Topic.LAB)).isNotEmpty())
        // No proposal ever comes from a lab lesson (lessons only inform).
        assertTrue(p.proposals.none { it.lessonId.startsWith("lab:") })
    }

    @Test fun aCoinFlipIsNeverStrongAndThinEvidenceIsWeak() {
        val coin = results("filter:EXPIRY_ONLY", "The expiry days only filter", 12, 4, 2)
        val thin = results("param:liquidity:stopPct:wider", "Liquidity 15+5 with a wider stop", 3, 1, 0)
        val p = AgentLessons.pass(AgentLessons.Book(), AgentLessons.Inputs(day, lab = coin + thin))
        val c = p.book.lessons.first { it.id == AgentLessons.norm("lab:filter:EXPIRY_ONLY") }
        assertTrue(c.confidence != AgentLessons.Confidence.STRONG, AgentLessons.said(c))
        assertTrue((c.p ?: 0.0) > 0.5)
        val t = p.book.lessons.first { it.id == AgentLessons.norm("lab:param:liquidity:stopPct:wider") }
        assertEquals(AgentLessons.Confidence.WEAK, t.confidence)
    }

    @Test fun aSecondNightReadingTheSameDaysChangesNothing() {
        val lab = results("block:$vwap", "Ideas using VWAP reclaim", 12, 4, 16)
        val one = AgentLessons.pass(AgentLessons.Book(), AgentLessons.Inputs(day, lab = lab))
        val two = AgentLessons.pass(one.book, AgentLessons.Inputs(day.plusDays(1), lab = lab))
        val a = one.book.lessons.first { it.id.startsWith("lab:") }
        val b = two.book.lessons.first { it.id.startsWith("lab:") }
        assertEquals(AgentLessons.evidence(a).n, AgentLessons.evidence(b).n)
        // And the book keeps it through the file.
        val back = AgentLessons.decode(AgentLessons.encode(two.book)).lessons.first { it.id.startsWith("lab:") }
        assertEquals(AgentLessons.Topic.LAB, back.topic); assertEquals(48, AgentLessons.evidence(back).n)
    }

    @Test fun theLabsRecordsBecomeResults() {
        val bt = StrategyForge.Backtest(55, 300, 12_000.0, 48.0, listOf(5_000.0, 4_000.0, 3_000.0), 0.01, -40.0, 6_000.0, false,
            listOf("x" to true), helped = setOf(ShadowSim.Filter.EXPIRY_ONLY))
        val failed = bt.copy(net = -3_000.0, gates = listOf("x" to false))
        fun cand(n: Int, filters: Set<ShadowSim.Filter> = emptySet()) = StrategyForge.Candidate(n, d(1), "NIFTY",
            ShadowSim.Rules(ShadowSim.Entry.VWAP_RECLAIM, stopPct = 0.2, targetPct = 0.5, filters = filters), StrategyForge.ExitBlock.FIXED)
        val f = StrategyForge.Book(records = listOf(
            StrategyForge.Record(cand(1, setOf(ShadowSim.Filter.EXPIRY_ONLY)), StrategyForge.Stage.FORWARD, bt),
            StrategyForge.Record(cand(2), StrategyForge.Stage.REJECTED, failed),
            StrategyForge.Record(cand(3), StrategyForge.Stage.RETIRED, bt, fwd = listOf(-500.0, -700.0)),
            StrategyForge.Record(cand(4).copy(born = d(20)), StrategyForge.Stage.REJECTED, failed)))
        val s = VariantLab.strategy("liquidity")!!
        val v = VariantLab.Book(variants = listOf(
            VariantLab.Variant("V1", "liquidity", s.defaults + ("stopPct" to 0.2), s.defaults, d(2), 1.0, VariantLab.Status.RETIRED, why = "worse than the current rules",
                vSum = -900.0, cSum = 100.0),
            VariantLab.Variant("V2", "liquidity", s.defaults + ("stopPct" to 0.2), s.defaults, d(2), 1.0, VariantLab.Status.ALIVE)))
        val rs = LabLessons.results(f, v, since = d(5))
        val entry = rs.filter { it.key == "block:$vwap" }
        assertEquals(3, entry.size, "the idea born before 'since' is left out")
        assertEquals(listOf(true, false, false), entry.map { it.passed })
        assertEquals(-1_200.0, entry[2].rs, 1e-9)
        assertEquals(listOf(true), rs.filter { it.key == "filter:EXPIRY_ONLY" }.map { it.passed })
        val par = rs.filter { it.key.startsWith("param:liquidity:stopPct:") }
        assertEquals(1, par.size, "the alive variant is not decided")
        assertTrue(!par[0].passed); assertEquals(-1_000.0, par[0].rs, 1e-9)
        assertTrue(par[0].key.endsWith(":wider") || par[0].key.endsWith(":tighter"))
        assertNull(rs.firstOrNull { it.key.startsWith("block:filter:") })
        // All of it digests.
        val p = AgentLessons.pass(AgentLessons.Book(), AgentLessons.Inputs(day, lab = LabLessons.results(f, v)))
        assertTrue(p.book.lessons.count { it.id.startsWith("lab:") } >= 4)
    }
}
