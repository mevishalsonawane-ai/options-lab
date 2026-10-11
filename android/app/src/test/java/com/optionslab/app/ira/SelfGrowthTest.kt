package com.optionslab.app.ira

import com.optionslab.app.data.AppHealth
import com.optionslab.app.data.Market
import com.optionslab.app.data.SmartWorkers
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.AgentLessons
import com.optionslab.ira.Findings
import com.optionslab.ira.GrowthReport
import com.optionslab.ira.HealthBook
import com.optionslab.ira.LearningGoals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Jarvis's growth on the phone ([SelfGrowth]): his learning goals after a pass (kept in noBackupFilesDir, steering the shadow
 * log's size only), the weekly consolidation (lessons merged by their own rule, old findings logs compacted, a week summary
 * kept), the weekly self-report once a week, and his answers - "is the app healthy?", "why was the app slow?", "what are
 * you trying to learn?", "how did you grow this week?". Nothing acts.
 */
class SelfGrowthTest : RobolectricTest() {

    /** Asks Jarvis [q] and waits for his answer: these are answered off the main thread (review R5 #12). */
    private fun askAndWait(q: String) {
        val start = IraHub.state.value.messages.maxOfOrNull { it.id } ?: 0L
        IraHub.ask(q)
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && IraHub.state.value.messages.none { it.fromIra && it.id > start }) Thread.sleep(10)
    }
    private val mon = LocalDate.of(2026, 10, 12)

    private fun lesson(id: String, name: String, n: Int, rs: Double, end: LocalDate, topic: AgentLessons.Topic = AgentLessons.Topic.SPECIALIST) =
        AgentLessons.Lesson(id, topic, AgentLessons.Kind.RUPEES, name, listOf("x"), setOf("the specialists"),
            (0 until n).map { AgentLessons.Bucket(end.minusDays((n - it).toLong()), 1, rs / n, 0) }, if (rs >= 0) 1 else -1,
            AgentLessons.Status.NEW, if (n >= AgentLessons.BUILDING_N) AgentLessons.Confidence.BUILDING else AgentLessons.Confidence.WEAK, end.minusDays(n.toLong()), end, end)

    @Before fun up() {
        assumeTrue("Jarvis's growth is in IraAlgo with Jarvis only", SelfGrowth.enabled())
        JarvisLearning.resetForTest(); SelfGrowth.resetForTest(); AppHealth.resetForTest(); SmartWorkers.resetForTest()
        JarvisLearning.init(context); SelfGrowth.init(context); AppHealth.init(context)
        SelfGrowth.resetForTest(); AppHealth.resetForTest()
        runBlocking { IraHub.forgetAll() }
    }

    @After fun down() {
        JarvisLearning.resetForTest(); SelfGrowth.resetForTest(); AppHealth.resetForTest(); SmartWorkers.resetForTest()
        runBlocking { IraHub.forgetAll() }
        IraHub.clearRequests()
        Market.testClock = null
    }

    @Test fun goalsAfterThePassAreKeptAndSteerRecordingOnly() = runBlocking<Unit> {
        val oi = lesson("spec:liquidity:oi", "The OI specialist on Liquidity 15+5", 12, -6_000.0, mon)
        JarvisLearning.setForTest(AgentLessons.Book(listOf(oi), lastPass = mon))
        assertEquals(LearningGoals.RecordingPlan.BASE_SHADOW_BYTES, SelfGrowth.shadowLogBytes())
        assertTrue(SelfGrowth.goalsIfDue())
        // Once a pass.
        assertTrue(!SelfGrowth.goalsIfDue())
        val g = SelfGrowth.goals.value
        assertEquals(listOf("Need 18 more Liquidity 15+5 trades to judge the OI specialist"), g.goals.map { it.text })
        // Recording only: the shadow log may grow to its cap while a goal needs it; nothing else moves.
        assertEquals(LearningGoals.RecordingPlan.MAX_SHADOW_BYTES, SelfGrowth.shadowLogBytes())
        assertTrue(File(context.noBackupFilesDir, "growth/goals.txt").isFile)
        assertEquals(1, SmartWorkers.bus.recent(System.currentTimeMillis()).count { it.who == "Jarvis's goals" && it.words.startsWith("What I'm trying to learn next:") })
    }

    @Test fun theWeeklyConsolidationMergesCompactsAndKeepsAWeekSummary() = runBlocking<Unit> {
        val sunday = mon.plusDays(6)
        // The first run on a phone only notes the week; the next Sunday's is the first consolidation.
        SelfGrowth.testNow = { sunday.minusDays(7).atTime(10, 0) }
        assertNull(SelfGrowth.consolidateIfDue())
        SelfGrowth.testNow = { sunday.atTime(10, 0) }
        val a = lesson("spec:pine:oi", "The OI specialist on Pine", 12, -1_200.0, sunday)
        val b = lesson("spec:pine:OI ", "The OI specialist on Pine", 12, -1_200.0, sunday.minusDays(20))
        JarvisLearning.setForTest(AgentLessons.Book(listOf(a, b), lastPass = mon))
        // An old findings log: compacted to its data-health lines and counts.
        val dir = File(context.noBackupFilesDir, "findings").apply { mkdirs() }
        val t = mon.minusDays(10).atTime(10, 0).atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()
        val old = File(dir, "${mon.minusDays(10)}.log")
        old.writeText(listOf(Findings.Finding("trap guard", Findings.Kind.PULL, "BANKNIFTY", atMs = t, seq = 1),
            Findings.Finding("trap guard", Findings.Kind.PULL, "BANKNIFTY", atMs = t + 1, seq = 2),
            Findings.Finding("self-healing", Findings.Kind.DATA_HEALTH, Findings.ALL, atMs = t, words = "stream stale", seq = 3)).joinToString("\n") { Findings.line(it) } + "\n")
        val done = SelfGrowth.consolidateIfDue()
        assertNotNull(done)
        assertEquals(1, done!!.merged)
        assertEquals(1, done.compacted)
        assertEquals(listOf("spec:pine:oi"), JarvisLearning.book.value.lessons.map { it.id })
        val lines = old.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("|DATA_HEALTH|"))
        assertEquals("C|trap guard|PULL|2", lines[1])
        // The week's summary kept; not again this week.
        assertEquals(listOf(mon), SelfGrowth.weeks.value.map { it.monday })
        assertNull(SelfGrowth.consolidateIfDue())
        assertTrue(File(context.noBackupFilesDir, "growth/weeks.txt").readText().startsWith("M|$sunday|"))
    }

    @Test fun theWeeklyReportComesOnceAfterFridaysClose() = runBlocking<Unit> {
        SelfGrowth.testNow = { mon.plusDays(4).atTime(15, 0) }
        // The first run on a phone (Friday before the close): last week only noted - the app did not watch it, nothing to report.
        assertNull(SelfGrowth.reportIfDue(context))
        assertNull(SelfGrowth.reportIfDue(context))
        assertTrue(SelfGrowth.weeks.value.isEmpty())
        // Saturday morning: this week's, once.
        SelfGrowth.testNow = { mon.plusDays(5).atTime(8, 0) }
        val w = SelfGrowth.reportIfDue(context)
        assertEquals(mon, w?.monday)
        assertNull(SelfGrowth.reportIfDue(context))
        assertEquals(mon, SelfGrowth.weeks.value.last().monday)
    }

    @Test fun jarvisAnswersFromTheRecords() {
        IraHub.init(context); IraHub.awaitLoadedBlocking()
        askAndWait("is the app healthy?")
        assertTrue(IraHub.state.value.messages.last().text, IraHub.state.value.messages.last().text.startsWith("Yes, Boss: the app is healthy."))
        val now = System.currentTimeMillis()
        var hb = HealthBook.Book()
        repeat(3) { hb = HealthBook.stall(hb, now - 60_000L * it, 1_300, "Choreographer.doFrame in BrainSection.invoke") }
        AppHealth.setForTest(hb)
        askAndWait("why was the app slow?")
        val slow = IraHub.state.value.messages.last().text
        assertTrue(slow, slow.startsWith("Today the screen stalled 3 times, longest 1.3 s: mostly in BrainSection.invoke (3)."))
        askAndWait("is the app healthy?")
        assertTrue(IraHub.state.value.messages.last().text.startsWith("Not quite, Boss. Today: 3 stalls"))
        val goal = LearningGoals.next(LearningGoals.Book(), listOf(lesson("spec:liquidity:oi", "The OI specialist on Liquidity 15+5", 12, -6_000.0, mon)),
            emptyMap(), mon)
        SelfGrowth.setForTest(goal, listOf(GrowthReport.Week(mon, learned = 2, made = 1, kept = 1)))
        askAndWait("what are you trying to learn?")
        val g = IraHub.state.value.messages.last().text
        assertTrue(g, g.startsWith("What I'm trying to learn next, Boss:\n• Need 18 more Liquidity 15+5 trades to judge the OI specialist"))
        askAndWait("how did you grow this week?")
        val w = IraHub.state.value.messages.last().text
        assertTrue(w, w.startsWith("How I grew, week of 12–16 Oct, Boss:\n• Lessons: 2 lessons learned or confirmed, 0 retired."))
        assertTrue(w, "• Autopilot: 1 change made, 1 kept and 0 reverted this week." in w)
        // Nothing was asked of Boss, nothing changed.
        assertTrue(IraHub.state.value.pending.isEmpty())
    }
}
