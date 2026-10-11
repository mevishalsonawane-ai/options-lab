package com.optionslab.ira

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The weekly memory consolidation ([Consolidation]): when, the lessons' merge, the findings' compaction, the caps, speed. */
class ConsolidationTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val sunday = LocalDate.of(2026, 10, 11)

    private fun lesson(id: String, n: Int, rs: Double, end: LocalDate = sunday, agent: String = "the bot trades"): AgentLessons.Lesson {
        val bs = (0 until n).map { AgentLessons.Bucket(end.minusDays((n - it).toLong()), 1, rs / n, 1) }
        return AgentLessons.Lesson(id, AgentLessons.Topic.STRATEGY, AgentLessons.Kind.RUPEES, id.substringAfter(':'), listOf("x"), setOf(agent), bs, 1,
            AgentLessons.Status.NEW, AgentLessons.Confidence.BUILDING, end.minusDays(n.toLong()), end, end)
    }

    private fun f(who: String, kind: Findings.Kind, at: Long, seq: Long, words: String = "") =
        Findings.line(Findings.Finding(who, kind, Findings.ALL, atMs = at, words = words, seq = seq))

    @Test fun dueOnSundayOrTheFirstRunAfterIt() {
        assertEquals(sunday, Consolidation.due(sunday, null))
        assertNull(Consolidation.due(sunday, sunday))
        assertNull(Consolidation.due(sunday.plusDays(1), sunday))
        assertEquals(sunday.plusDays(7), Consolidation.due(sunday.plusDays(9), sunday))
        // Missed a Sunday (the phone was off): the first run after it.
        assertEquals(sunday, Consolidation.due(sunday.plusDays(3), sunday.minusDays(7)))
    }

    @Test fun nearDuplicateLessonsMergeByTheLessonsOwnRule() {
        val a = lesson("strat:Hero", 12, 1_200.0, agent = "the bot trades")
        val b = lesson("strat:hero ", 20, 2_000.0, end = sunday.minusDays(30), agent = "the market brain")
        val c = lesson("strat:Solo", 10, -500.0)
        val book = AgentLessons.Book(listOf(a, b, c))
        val (merged, n) = Consolidation.lessons(book)
        assertEquals(1, n)
        assertEquals(listOf("strat:hero", "strat:solo"), merged.lessons.map { it.id })
        // Exactly the lessons' own merge and caps (called, not redone).
        assertEquals(AgentLessons.capped(book.copy(lessons = AgentLessons.merge(book.lessons))), merged)
        val hero = merged.lessons[0]
        assertEquals(32, AgentLessons.evidence(hero).n)
        assertEquals(setOf("the bot trades", "the market brain"), hero.agents)
        // Again: nothing more to merge, the same book.
        assertEquals(merged to 0, Consolidation.lessons(merged))
    }

    @Test fun findingsHistoryCompactsToItsDataHealthLinesAndCounts() {
        val t = sunday.minusDays(10).atTime(10, 0).atZone(ist).toInstant().toEpochMilli()
        val lines = listOf(
            f("self-healing", Findings.Kind.DATA_HEALTH, t, 1, "stream stale"),
            f("trap guard", Findings.Kind.PULL, t + 1, 2),
            f("trap guard", Findings.Kind.PULL, t + 2, 3),
            f("self-healing", Findings.Kind.DATA_HEALTH, t, 9, "stream stale"),
            f("Liquidity 15+5", Findings.Kind.STOP_HIT, t + 3, 4),
            "garbage line", "",
        )
        val c = Consolidation.compact(lines)
        assertEquals(listOf(
            Findings.line(Findings.Finding("self-healing", Findings.Kind.DATA_HEALTH, Findings.ALL, atMs = t, words = "stream stale", seq = 0)),
            "C|Liquidity 15+5|STOP_HIT|1", "C|trap guard|PULL|2"), c)
        // What Jarvis reads back is still there.
        assertEquals(1, c.count { it.contains("|DATA_") && Findings.parse(it) != null })
        // Compacted again: unchanged (and the same input, the same output).
        assertEquals(c, Consolidation.compact(c))
        assertEquals(c, Consolidation.compact(lines))
        assertTrue(Consolidation.compactDue(sunday.minusDays(8), sunday))
        assertTrue(!Consolidation.compactDue(sunday.minusDays(7), sunday))
    }

    @Test fun oldRecordingsBeyondCapsTrimmedTheGoalsLast() {
        val recs = listOf(
            Consolidation.Rec("old.gz", sunday.minusDays(40), 10, "NIFTY"),
            Consolidation.Rec("n1.gz", sunday.minusDays(5), 100, "NIFTY"),
            Consolidation.Rec("b1.gz", sunday.minusDays(6), 100, "BANKNIFTY"),
            Consolidation.Rec("n2.gz", sunday.minusDays(2), 100, "NIFTY"),
            Consolidation.Rec("b2.gz", sunday.minusDays(1), 100, "BANKNIFTY"),
        )
        // 30 days kept; 250 bytes at most: the oldest NIFTY files go first while BANKNIFTY is a goal's index.
        assertEquals(listOf("old.gz", "n1.gz", "n2.gz"), Consolidation.trim(recs, 30, 250, sunday, setOf("BANKNIFTY")))
        // With no goal: simply the oldest.
        assertEquals(listOf("old.gz", "b1.gz", "n1.gz"), Consolidation.trim(recs, 30, 250, sunday))
        // Within caps: only the too-old one.
        assertEquals(listOf("old.gz"), Consolidation.trim(recs, 30, 10_000, sunday))
        assertEquals(Consolidation.trim(recs, 30, 250, sunday, setOf("BANKNIFTY")), Consolidation.trim(recs.reversed(), 30, 250, sunday, setOf("BANKNIFTY")))
    }

    @Test fun aFullConsolidationTakesUnderOneHundredMilliseconds() {
        val ls = (0 until 300).map { lesson("strat:bot${it % 280}", 40, 100.0 * it) }
        val book = AgentLessons.Book(ls)
        val t0 = sunday.minusDays(20).atTime(10, 0).atZone(ist).toInstant().toEpochMilli()
        // A week's logs to compact (2,000 findings a day: more than a busy day posts).
        val logs = (0 until 7).map { d -> (0 until 2_000).map { i -> f("bot${i % 9}", if (i % 50 == 0) Findings.Kind.DATA_HEALTH else Findings.Kind.ENTRY, t0 + d * 86_400_000L + i, i.toLong()) } }
        val recs = (0 until 2_000).map { Consolidation.Rec("e$it.gz", sunday.minusDays((it % 400).toLong()), 500_000, if (it % 2 == 0) "NIFTY" else "BANKNIFTY") }
        fun run() {
            Consolidation.lessons(book)
            logs.forEach { Consolidation.compact(it) }
            Consolidation.trim(recs, MoveEvents.KEEP_DAYS, MoveEvents.BUDGET_BYTES, sunday, setOf("BANKNIFTY"))
        }
        run()   // warm
        val best = (0 until 3).minOf { val a = System.nanoTime(); run(); (System.nanoTime() - a) / 1_000_000 }
        assertTrue(best < 100, "consolidation took $best ms")
    }

    @Test fun theDoneLine() {
        assertEquals("weekly consolidation: 2 lessons merged, 1 findings log compacted, 0 old recordings trimmed, the week of 5–9 Oct summary kept",
            Consolidation.Done(2, 1, 0, LocalDate.of(2026, 10, 5)).words())
    }
}
