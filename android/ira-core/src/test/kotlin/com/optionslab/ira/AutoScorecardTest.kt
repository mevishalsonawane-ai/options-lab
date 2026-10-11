package com.optionslab.ira

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The monthly automation scorecard ([AutoScorecard]): per system, kept against reverted, the rupee effect, mutes and promotions. */
class AutoScorecardTest {
    private val sep = YearMonth.of(2026, 9)
    private val ev = AutoChange.Evidence(20, -2000.0, AgentLessons.Confidence.STRONG, -1)
    private fun mute(id: String) = AutoChange.Change(AutoChange.Kind.SPECIALIST_MUTE, "spec:solo:$id", AutoChange.COUNTS, AutoChange.MUTED, "hurt", ev,
        AutoChange.Risk.LOWER, AutoChange.Scope.PAPER, expect = -1, effect = -1, what = "the $id specialist on Solo")
    private fun knob() = AutoChange.Change(AutoChange.Kind.TRAP_THRESHOLD, "knob:trap.lockout_sec", "60", "90", "x", ev, AutoChange.Risk.NEUTRAL_SHADOW,
        AutoChange.Scope.SHADOW, effect = 0)

    private fun book(): AutoChange.Book {
        var b = AutoChange.Book()
        fun add(c: AutoChange.Change, day: LocalDate, st: AutoChange.Status, effect: Double, by: String = "autopilot") {
            val (nb, e) = AutoChange.record(b, c, AutoChange.Status.PROBATION, 0L, day, by = by)
            b = nb.copy(entries = nb.entries.map { if (it.id == e.id) it.copy(status = st, effect = effect) else it })
        }
        add(mute("oi"), LocalDate.of(2026, 9, 3), AutoChange.Status.KEPT, 1_200.0)
        add(mute("vix"), LocalDate.of(2026, 9, 10), AutoChange.Status.REVERTED, -400.0)
        add(mute("flow"), LocalDate.of(2026, 9, 15), AutoChange.Status.UNDONE, 0.0, by = "specialists' tuning")
        add(knob(), LocalDate.of(2026, 9, 20), AutoChange.Status.KEPT, 0.0)
        add(mute("august"), LocalDate.of(2026, 8, 29), AutoChange.Status.KEPT, 9_999.0)
        return b
    }

    @Test fun eachSystemIsScoredAgainstNoChange() {
        val c = AutoScorecard.of(book(), sep, listOf(AutoScorecard.Promotion(LocalDate.of(2026, 9, 22), "ORB Fresh", "stop 15% → 12%", 850.0, "APPLIED", false)), 2)
        assertEquals(4 + 1 + 2, c.changes)
        val ap = c.systems.single { it.name == "Autopilot · trade manager specialists" }
        assertEquals(2, ap.changes); assertEquals(1, ap.kept); assertEquals(1, ap.reverted)
        assertEquals(800.0, ap.effect!!, 1e-9)
        assertEquals(null, c.systems.single { it.name == "Autopilot · trap guard" }.effect, "a shadow knob has no rupees")
        assertEquals(1, c.systems.single { it.name == "Specialists' self-tuning" }.undone)
        assertEquals(800.0, c.total, 1e-9, "August's change is not September's")
        assertEquals(listOf("the oi specialist on Solo", "the vix specialist on Solo", "the flow specialist on Solo"), c.muted)
        assertTrue(AutoScorecard.lines(c).any { it.contains("ORB Fresh") && it.contains("+₹850") })
        assertTrue(AutoScorecard.say(c).contains("+₹800 against making no change"), AutoScorecard.say(c))
        assertTrue(AutoScorecard.notice(c).startsWith("September 2026: 7 automatic changes"))
    }

    @Test fun itIsDueOnTheFirstOrTheFirstDayAfterAMissedOne() {
        assertTrue(AutoScorecard.due(LocalDate.of(2026, 10, 1), YearMonth.of(2026, 8)))
        assertFalse(AutoScorecard.due(LocalDate.of(2026, 10, 1), sep))
        assertTrue(AutoScorecard.due(LocalDate.of(2026, 10, 4), YearMonth.of(2026, 8)), "missed on the 1st: made on the next run")
        assertTrue(AutoScorecard.due(LocalDate.of(2026, 10, 1), null))
        val empty = AutoScorecard.of(AutoChange.Book(), sep, emptyList(), 0)
        assertTrue(empty.empty)
        assertTrue(AutoScorecard.say(empty).startsWith("Nothing changed by itself"))
    }
}
