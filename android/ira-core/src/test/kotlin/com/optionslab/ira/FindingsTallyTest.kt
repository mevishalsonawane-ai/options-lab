package com.optionslab.ira

import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The findings counted, not kept (memory audit, 14 Oct 2026, item 2): the counts give exactly the items and bundle rows the
 * kept list gave, the nightly snapshot plus the new days' logs give exactly what counting every log gives (the golden
 * comparison), windows and the snapshot's words, and the memory it holds against the list's.
 */
class FindingsTallyTest {
    private val day = LocalDate.of(2026, 10, 14)
    private fun at(d: LocalDate, minute: Int) = d.atTime(9, 15).plusMinutes(minute.toLong()).atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()

    private val whos = listOf("Order flow", "Trade manager", "Market brain", "Liquidity", "order flow")

    /** [n] findings over the [days] days before and on [day], in a fixed random order (several agents, kinds and ties). */
    private fun findings(n: Int, days: Int, seed: Int): List<Findings.Finding> {
        val r = Random(seed)
        return List(n) {
            val d = day.minusDays(r.nextInt(days).toLong())
            Findings.Finding(whos[r.nextInt(whos.size)], Findings.Kind.entries[r.nextInt(Findings.Kind.entries.size)], "NIFTY", atMs = at(d, r.nextInt(300)))
        }
    }

    @Test fun theCountsGiveTheSameItemsAndBundleRowsAsTheList() {
        for (seed in 1..20) {
            val fs = findings(500, 30, seed)
            val t = FindingsTally.of(fs)
            assertEquals(fs.size, t.total)
            assertEquals(oldItems(fs, day), SharedKnowledge.findingItems(t, day), "seed $seed")
            assertEquals(oldCounts(fs), LessonsBundle.counts(t), "seed $seed")
            // And the list's own entry points now go through the counts.
            assertEquals(SharedKnowledge.findingItems(t, day), SharedKnowledge.findingItems(fs, day))
        }
    }

    @Test fun nothingCountedGivesNothing() {
        val t = FindingsTally()
        assertTrue(t.isEmpty()); assertEquals(0, t.total); assertNull(t.lastDay())
        assertEquals(emptyList(), SharedKnowledge.findingItems(t, day))
        assertEquals(emptyList(), LessonsBundle.counts(t))
        assertEquals(emptyList(), SharedKnowledge.findingItems(emptyList(), day))
        t.add(Findings.Kind.SWEEP, "x", day.toEpochDay(), 0)
        assertTrue(t.isEmpty(), "a count of nothing adds no row")
    }

    @Test fun logLinesAreCountedAndJunkIsSkipped() {
        val t = FindingsTally()
        val f = Findings.Finding("Order flow", Findings.Kind.ABSORPTION, "BANKNIFTY", atMs = at(day, 5))
        assertTrue(t.line(Findings.line(f)))
        for (junk in listOf("", "garbage", "F|x", "X|1|2|3|4|5|6|7|8|9|10|11")) assertTrue(!t.line(junk), junk)
        assertEquals(1, t.total)
        assertEquals(day, t.lastDay())
    }

    /** The golden comparison: last night's snapshot (the days before it) + the logs since == counting every log, every day. */
    @Test fun theSnapshotAndTheNewLogsGiveTheSameAsCountingEverything() {
        for (seed in 1..10) {
            val byDay = findings(2_000, 40, seed).groupBy { AgentLessons.dayOf(it.atMs) }
            // The logs are read newest first, as the phone reads its folder.
            fun streamed(days: List<LocalDate>): FindingsTally = FindingsTally().also { t -> days.sortedDescending().forEach { d -> byDay[d].orEmpty().forEach { t.line(Findings.line(it)) } } }
            val nights = listOf(day.minusDays(1), day.minusDays(3), day.minusDays(10))
            for (night in nights) {
                // Written that night: everything up to then, the days before it kept.
                val then = streamed(byDay.keys.filter { !it.isAfter(night) })
                val (through, snap) = assertNotNull(FindingsTally.read(then.before(night).text(night.minusDays(1))))
                assertEquals(night.minusDays(1), through)
                // This morning: the logs after the snapshot's day, newest first, then the snapshot's days in the window.
                val from = day.minusDays(30)
                val now = streamed(byDay.keys.filter { it.isAfter(through) && !it.isBefore(from) }).also { it.addAll(snap.since(from)) }
                val all = streamed(byDay.keys.filter { !it.isBefore(from) })
                assertEquals(all.total, now.total, "seed $seed night $night")
                assertEquals(SharedKnowledge.findingItems(all, day), SharedKnowledge.findingItems(now, day), "seed $seed night $night")
                assertEquals(LessonsBundle.counts(all), LessonsBundle.counts(now), "seed $seed night $night")
            }
        }
    }

    @Test fun windowsKeepOnlyTheirDays() {
        val t = FindingsTally.of(findings(300, 30, 7))
        val since = t.since(day.minusDays(4))
        val before = t.before(day.minusDays(4))
        assertEquals(t.total, since.total + before.total)
        assertTrue(since.lastDay()!! <= day)
        assertTrue(before.lastDay()!! < day.minusDays(4))
        assertTrue(t.since(day.plusDays(1)).isEmpty())
    }

    @Test fun aSnapshotThatDoesNotReadIsNull() {
        for (bad in listOf("", "not json", "[]", "{\"v\":2,\"through\":\"2026-10-13\",\"rows\":[]}", "{\"v\":1,\"through\":\"soon\",\"rows\":[]}", "{\"v\":1}"))
            assertNull(FindingsTally.read(bad), bad)
        // Rows it cannot read (an unknown kind, a short row) are skipped; the rest are kept.
        val (_, t) = assertNotNull(FindingsTally.read("{\"v\":1,\"through\":\"2026-10-13\",\"rows\":[[\"SWEEP\",\"a\",20740,3],[\"NOPE\",\"a\",20740,1],[\"SWEEP\"]]}"))
        assertEquals(3, t.total)
        // Round trip.
        val x = FindingsTally.of(findings(100, 10, 3))
        assertEquals(x, FindingsTally.read(x.text(day))!!.second)
    }

    /** Before / after (the release note): the list kept every finding as an object; the counts keep a few rows. */
    @Test fun theCountsHoldAFewRowsWhereTheListHeldEveryFinding() {
        val rt = Runtime.getRuntime()
        fun used(): Long { repeat(3) { System.gc(); Thread.sleep(20) }; return rt.totalMemory() - rt.freeMemory() }
        val base = used()
        var kept: List<Findings.Finding>? = findings(200_000, 30, 11)
        val listBytes = used() - base
        val n = kept!!.size
        kept = null
        val base2 = used()
        // The phone's way: each finding counted as it is read and let go (made here one at a time from the same list's seed).
        val t = FindingsTally().also { t -> findings(200_000, 30, 11).forEach { t.add(it) } }
        val tallyBytes = used() - base2
        // At most kinds x agents rows, each with at most 30 days: a few hundred numbers for 200,000 findings.
        assertTrue(t.size <= Findings.Kind.entries.size * whos.size, "${t.size}")
        assertEquals(n, t.total)
        assertNull(kept)
        println("memory: $n findings kept as objects ~${listBytes / 1024} KB; as counts ${t.size} rows ~${maxOf(tallyBytes, 0L) / 1024} KB")
    }

    // ---- the old list code, as it was before 14 Oct (the comparison's reference) ----

    private fun oldItems(findings: List<Findings.Finding>, day: LocalDate?): List<SharedKnowledge.Item> {
        val ref = SharedKnowledge.findingItems(FindingsTally.of(findings), day).associateBy { it.id }
        if (findings.isEmpty()) return emptyList()
        return findings.groupBy { it.kind }.map { (k, fs) ->
            val days = fs.map { AgentLessons.dayOf(it.atMs) }.distinct().size
            val who = fs.groupingBy { it.who }.eachCount().entries.sortedByDescending { it.value }.take(3).joinToString(", ") { it.key }
            val id = "findings:${k.name.lowercase(java.util.Locale.ENGLISH)}"
            // Everything but the counts and words is fixed by the kind; the counts and the words are checked here.
            ref.getValue(id).copy(n = fs.size,
                text = "The findings bus: ${fs.size} ${k.words} post${if (fs.size == 1) "" else "s"} on $days day${if (days == 1) "" else "s"} (most from $who)")
        }.sortedBy { it.id }
    }

    private fun oldCounts(findings: List<Findings.Finding>): List<LessonsBundle.FindingCount> = findings.groupBy { it.kind.name to it.who }.map { (k, fs) ->
        LessonsBundle.FindingCount(k.first, k.second, fs.size, fs.map { AgentLessons.dayOf(it.atMs) }.distinct().size)
    }.sortedWith(compareByDescending<LessonsBundle.FindingCount> { it.count }.thenBy { it.kind }.thenBy { it.who.lowercase(java.util.Locale.ENGLISH) })
}
