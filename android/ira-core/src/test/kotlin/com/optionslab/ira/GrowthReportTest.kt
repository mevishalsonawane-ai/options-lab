package com.optionslab.ira

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The weekly self-report ([GrowthReport]): when it is due, its text from fixed records (numbers exact, nothing invented). */
class GrowthReportTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val mon = LocalDate.of(2026, 10, 12)
    private fun d(k: Long) = mon.plusDays(k)
    private fun at(day: LocalDate, h: Int) = day.atTime(h, 0).atZone(ist).toInstant().toEpochMilli()

    private fun lesson(id: String, name: String, sign: Int, conf: AgentLessons.Confidence, status: AgentLessons.Status, on: LocalDate,
                       topic: AgentLessons.Topic = AgentLessons.Topic.STRATEGY) =
        AgentLessons.Lesson(id, topic, AgentLessons.Kind.RUPEES, name, emptyList(), setOf("the bot trades"),
            listOf(AgentLessons.Bucket(on, 1, 100.0 * sign, 1)), sign, status, conf, on.minusDays(20), on, on)

    private val lessons = AgentLessons.Book(listOf(
        lesson("strat:liquidity", "Liquidity 15+5", 1, AgentLessons.Confidence.STRONG, AgentLessons.Status.CONFIRMED, d(2)),
        lesson("spec:pine:oi", "The OI specialist on Pine", -1, AgentLessons.Confidence.BUILDING, AgentLessons.Status.NEW, d(1), AgentLessons.Topic.SPECIALIST),
        lesson("strat:old", "Old", -1, AgentLessons.Confidence.WEAK, AgentLessons.Status.RETIRED, d(3)),
        lesson("strat:new", "New", 1, AgentLessons.Confidence.WEAK, AgentLessons.Status.NEW, d(3)),
        lesson("strat:hero", "Hero", 1, AgentLessons.Confidence.STRONG, AgentLessons.Status.CONFIRMED, mon.minusDays(11)),
    ))

    private fun change(kind: AutoChange.Kind, target: String, from: String, to: String, what: String) = AutoChange.Change(kind, target, from, to,
        "why", AutoChange.Evidence(30, -3_000.0, AgentLessons.Confidence.STRONG, -1), AutoChange.Risk.LOWER, AutoChange.Scope.PAPER, expect = -1, effect = -1, what = what)

    private val auto = AutoChange.Book(listOf(
        AutoChange.Entry("a1", at(d(1), 16), d(1), change(AutoChange.Kind.SPECIALIST_MUTE, "spec:pine:oi", AutoChange.COUNTS, AutoChange.MUTED,
            "the OI specialist on Pine (paper)"), AutoChange.Status.PROBATION, afterN = 6, effect = 900.0),
        AutoChange.Entry("a2", at(d(0), 16), d(0), change(AutoChange.Kind.TRAP_THRESHOLD, "knob:trap.x", "60", "65", "a trap guard threshold"),
            AutoChange.Status.KEPT, closedOn = d(4), afterN = 15, effect = 300.0),
        AutoChange.Entry("a3", at(mon.minusDays(7), 16), mon.minusDays(7), change(AutoChange.Kind.TRAP_THRESHOLD, "knob:trap.y", "60", "65", "another threshold"),
            AutoChange.Status.REVERTED, closedOn = d(2), afterN = 5, effect = -200.0),
        AutoChange.Entry("a4", at(d(2), 16), d(2), change(AutoChange.Kind.SPECIALIST_MUTE, "spec:solo:vwap", AutoChange.MUTED, AutoChange.COUNTS,
            "the VWAP specialist on Solo (paper)"), AutoChange.Status.PROBATION),
        AutoChange.Entry("a5", at(d(3), 16), d(3), change(AutoChange.Kind.MANAGER_MODE, "mgr:pine:live", "SHADOW", "ACT", "the manager on Pine (live)"),
            AutoChange.Status.OFFERED),
    ))

    private val health: HealthBook.Book by lazy {
        var b = HealthBook.Book()
        repeat(3) { b = HealthBook.stall(b, at(d(1), 10 + it), 800, "BrainSection.invoke") }
        repeat(2) { b = HealthBook.stall(b, at(d(3), 10 + it), 900, "MoveRecorder.write") }
        b = HealthBook.stall(b, at(d(7), 10), 900, "next week")
        b = HealthBook.exits(b, listOf(HealthBook.Exit(at(d(2), 11), HealthBook.ExitKind.ANR, "Input dispatching timed out"),
            HealthBook.Exit(at(mon.minusDays(3), 11), HealthBook.ExitKind.CRASH, "last week")))
        b = HealthBook.orderPath(b, d(1), 180, 3)
        b = HealthBook.orderPath(b, d(3), 420, 2)
        b = HealthBook.orderPath(b, d(4), 250, 1)
        b = HealthBook.orderPath(b, d(7), 90, 4)
        b
    }

    private val goals = LearningGoals.Book(listOf(LearningGoals.Goal("spec:liquidity:oi", AgentLessons.Topic.SPECIALIST,
        "Need 12 more Liquidity 15+5 trades to judge the OI specialist", 18, 30, 12, 15_000.0, 6_000.0, LearningGoals.Record.SHADOW, "liquidity", null,
        12, mon.minusDays(3))), d(4), listOf(LearningGoals.Met("mgr:pine", "x", d(3)), LearningGoals.Met("mgr:old", "y", mon.minusDays(9))))

    @Test fun theWeeksReportFromFixedRecordsNumbersExact() {
        val w = GrowthReport.summarize(mon, lessons, auto, health, goals)
        assertEquals("Week of 12–16 Oct\n" +
            "Lessons: 2 lessons learned or confirmed, 1 retired. Now 2 strong and 1 building. New: Liquidity 15+5 makes money. " +
            "New: The OI specialist on Pine hurts its exits against the original rules.\n" +
            "Autopilot: 3 changes made, 1 kept and 1 reverted this week. Their effect against what would have happened without them: +Rs 1,200 over 21 settled cases.\n" +
            "Specialists: Muted: the OI specialist on Pine (paper). Un-muted: the VWAP specialist on Solo (paper).\n" +
            "App health: 5 stalls of 0.7 s or more, 1 ANR, no crash. Order path (signal to order sent), day medians: fastest 180 ms (Tue 13 Oct), slowest 420 ms (Thu 15 Oct).\n" +
            "Learning goals: Need 12 more Liquidity 15+5 trades to judge the OI specialist: +6 since it was set. 1 goal met this week.\n" +
            "Next week: I'll try to learn: Need 12 more Liquidity 15+5 trades to judge the OI specialist.", GrowthReport.text(w))
        assertEquals("2 lessons learned, 3 Autopilot changes (1 kept, 1 reverted), 5 stalls, 1 ANR or crash. Tap for the report.", GrowthReport.notice(w))
        // Kept as one line and read back the same.
        assertEquals(w, GrowthReport.decode(GrowthReport.encode(w)))
        assertTrue(GrowthReport.say(w).startsWith("How I grew, week of 12–16 Oct, Boss:\n• Lessons: 2 lessons learned"))
    }

    @Test fun nothingRecordedClaimsNothing() {
        val w = GrowthReport.summarize(mon, AgentLessons.Book(), AutoChange.Book(), HealthBook.Book(), LearningGoals.Book())
        assertEquals("Week of 12–16 Oct\n" +
            "Lessons: No lesson learned or retired this week. Now 0 strong and 0 building.\n" +
            "Autopilot: Autopilot made no changes this week.\n" +
            "Specialists: No specialist muted or un-muted.\n" +
            "App health: 0 stalls of 0.7 s or more, no ANR, no crash. No order timed this week.\n" +
            "Learning goals: No learning goal was set.\n" +
            "Next week: No goal yet: I set them each evening after I study the agents.", GrowthReport.text(w))
        // Every figure in a report is one of the records' (no rupee figure without a change behind it).
        assertFalse(Regex("Rs").containsMatchIn(GrowthReport.text(w)))
        assertEquals("No weekly report yet, Boss: the first comes after Friday's close.", GrowthReport.say(null))
    }

    @Test fun dueSaturdayMorningOrTheFirstOpenAfterFridaysClose() {
        val fri = d(4)
        assertEquals(mon.minusWeeks(1), GrowthReport.dueWeek(fri.atTime(15, 29)))
        // Not at Friday's close: after Friday's learning pass and Autopilot's night run (G6-5).
        assertEquals(mon.minusWeeks(1), GrowthReport.dueWeek(fri.atTime(15, 30)))
        assertEquals(mon, GrowthReport.dueWeek(d(5).atTime(0, 0)))
        assertEquals(mon, GrowthReport.dueWeek(d(5).atTime(8, 0)))
        assertEquals(mon, GrowthReport.dueWeek(d(7).atTime(9, 0)))
        assertTrue(GrowthReport.due(d(5).atTime(8, 0), mon.minusWeeks(1)))
        assertFalse(GrowthReport.due(d(5).atTime(9, 0), mon))
        assertTrue(GrowthReport.due(d(5).atTime(9, 0), null))
        assertEquals("Week of 28 Sep–2 Oct", GrowthReport.title(LocalDate.of(2026, 9, 28)))
        assertEquals("Week of 12–16 Oct", GrowthReport.title(mon))
    }

    @Test fun keptWeeksAndAsked() {
        var ws = emptyList<GrowthReport.Week>()
        for (k in 0 until 30L) ws = GrowthReport.keep(ws, GrowthReport.Week(mon.plusWeeks(k)))
        assertEquals(GrowthReport.KEEP_WEEKS, ws.size)
        assertEquals(mon.plusWeeks(29), ws.last().monday)
        for (q in listOf("how did you grow this week?", "Jarvis, how did you grow this week", "show me your weekly self report", "how have you improved this week"))
            assertTrue(GrowthReport.asked(q), q)
        // "Weekly review" and "your weekly report" stay the market's weekly review.
        for (q in listOf("show me your weekly report", "how did this week go", "weekly review", "how are you growing", "how did nifty do this week"))
            assertFalse(GrowthReport.asked(q), q)
        assertNull(GrowthReport.decode("nonsense"))
    }
}
