package com.optionslab.ira

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImproveTest {
    private val mon = LocalDate.of(2026, 10, 5)            // a Monday
    private val nextMon = mon.plusWeeks(1)

    private val missedWords = listOf("zorbo flim", "glorp the market", "wibble wobble index", "frumple nifty", "snarf it")

    private fun day(vararg kv: Pair<String, Int>) = mapOf(*kv)

    private fun roughWeek(start: LocalDate = mon) = Improve.week(start, listOf(
        day(Improve.HEARD to 20, Improve.MISUNDERSTOOD to 5, Improve.MISTAKES to 2, Improve.TIMED to 10, Improve.SLOW to 7,
            Improve.HUSH to 2, Improve.ASKED_PREFIX + "BANKNIFTY|levels" to 4),
        day(Improve.HEARD to 10, Improve.MISUNDERSTOOD to 2, Improve.MISTAKES to 2, Improve.MUTED to 1, Improve.ALERT_OFF to 1,
            Improve.SIT_OUT to 2, Improve.ASKED_PREFIX + "NIFTY|trend" to 1, Improve.ASKED_PREFIX + "BANKNIFTY|levels" to 2),
    ), missedWords)

    @Test fun weeksAndDays() {
        assertEquals(mon, Improve.weekOf(mon.plusDays(4)))
        assertEquals(nextMon, Improve.weekOf(mon.plusDays(5)), "a Saturday looks to the week ahead")
        assertEquals(nextMon, Improve.weekOf(mon.plusDays(6)))
        assertEquals(nextMon, Improve.nextWeek(mon.plusDays(4)), "the Friday review sets next week's goals")
        assertEquals(nextMon, Improve.nextWeek(mon.plusDays(3)), "a Thursday review (Friday a holiday) too")
        assertEquals(nextMon, Improve.nextWeek(mon.plusDays(6)))
        assertEquals(3, Improve.days(mon, mon.plusDays(2)).size)
        assertEquals(7, Improve.days(mon, mon.plusDays(20)).size, "never past the Sunday")
    }

    @Test fun theWeekIsSummedFromItsDays() {
        val w = roughWeek()
        assertEquals(30, w.heard); assertEquals(7, w.misunderstood); assertEquals(4, w.mistakes)
        assertEquals(30, w.fastShare); assertEquals(76, w.understoodShare)
        assertEquals(4, w.cutShort); assertEquals(2, w.sitOuts)
        assertEquals(mapOf("BANKNIFTY|levels" to 6, "NIFTY|trend" to 1), w.asked)
        assertNull(Improve.week(mon, listOf(day(Improve.TIMED to 4))).fastShare, "too few timed answers to judge")
        assertNull(Improve.week(mon, listOf(day(Improve.HEARD to 9))).understoodShare)
    }

    @Test fun goalsAreMeasurableAndAtMostThree() {
        val goals = Improve.choose(roughWeek(), emptyList())
        assertEquals(Improve.MAX_GOALS, goals.size)
        assertEquals(listOf(Improve.Kind.LEARN_MISSED, Improve.Kind.FEWER_MISTAKES, Improve.Kind.SPEED), goals.map { it.kind })
        val learn = goals[0]
        assertEquals(5, learn.words.size); assertEquals(3, learn.target)
        assertEquals("understand at least 3 of the 5 phrasings I missed", learn.text())
        assertFalse(missedWords.any { learn.text().contains(it) }, "Boss's words are never in a goal's text")
        assertEquals(3, goals[1].target)                       // 4 marked wrong last week: at most 3
        assertTrue(goals[1].text().startsWith("fewer than 4 answers marked wrong"))
        assertEquals(50, goals[2].target, "30% fast: the typical answer under 3 s first")
        assertTrue(goals[2].text().contains("typical spoken answer under 3 s"))
    }

    @Test fun aKindMissedLastWeekIsTriedFirst() {
        val goals = Improve.choose(roughWeek(), emptyList(), missedLast = setOf(Improve.Kind.QUIETER))
        assertEquals(Improve.Kind.QUIETER, goals.first().kind)
        assertEquals(2, goals.first().target)                  // cut short 4 times: 2 or fewer
    }

    @Test fun aGoodWeekNeedsNoGoals() {
        val w = Improve.week(mon, listOf(day(Improve.HEARD to 40, Improve.MISUNDERSTOOD to 1, Improve.MISTAKES to 1, Improve.TIMED to 20, Improve.SLOW to 1)))
        assertTrue(Improve.choose(w, emptyList()).isEmpty())
    }

    @Test fun onlySafePhrasingsAreSetToLearn() {
        val learned = listOf(Corrections.Learned(Corrections.normalize("zorbo flim"), "what is the trend on nifty"))
        val words = Improve.toLearn(listOf("zorbo flim", "my pin is 4321", "what is my password", "buy 2 lots of nifty", "glorp the market", "Glorp the  market"), learned)
        assertEquals(listOf("glorp the market"), words, "learned, secret, order and repeat dropped")
    }

    @Test fun everyGoalOnlySpeaksStudiesAsksOrWorksOnPaper() {
        assertTrue(Improve.Kind.entries.all { it.means in setOf(Improve.Means.SPEAK, Improve.Means.STUDY, Improve.Means.ASK, Improve.Means.PAPER) })
        // No goal pushes for more ideas taken or fewer sat out (more risk): a week of many sit-outs sets none.
        val w = Improve.week(mon, listOf(day(Improve.SIT_OUT to 20, Improve.HEARD to 40, Improve.TIMED to 20)))
        assertTrue(Improve.choose(w, emptyList()).isEmpty())
        val all = Improve.choose(roughWeek(), emptyList(), missedLast = setOf(Improve.Kind.QUIETER, Improve.Kind.UNDERSTAND))
        all.forEach { g -> assertFalse(Regex("(?i)\\b(trade more|more risk|bigger|size up|take more)\\b").containsMatchIn(g.text() + g.kind.how), g.text()) }
    }

    @Test fun progressAndGrade() {
        val plan = Improve.Plan(mon, Improve.choose(roughWeek(mon.minusWeeks(1)), emptyList()))
        val learned = listOf(Corrections.Learned(Corrections.normalize("zorbo flim"), "x"), Corrections.Learned(Corrections.normalize("frumple nifty"), "y"),
            Corrections.Learned(Corrections.normalize("snarf it"), "z"))
        val week = Improve.week(mon, listOf(day(Improve.MISTAKES to 4, Improve.TIMED to 10, Improve.SLOW to 2)))
        val ps = plan.goals.map { Improve.progress(it, week, learned) }
        assertEquals(Improve.Grade.MET, ps[0].grade); assertEquals("3 of 5 learned, 3 needed", ps[0].text)
        assertEquals(Improve.Grade.MISSED, ps[1].grade, "4 marked wrong, 3 at most")
        assertEquals(Improve.Grade.MET, ps[2].grade); assertEquals(80, ps[2].value)
        val (line, score) = assertNotNull(Improve.grade(plan, week, learned))
        assertEquals(Improve.Score(mon, 2, 3), score)
        assertTrue(line.startsWith("Last week's goals: met 2 of 3"), line)
        assertTrue(line.contains("missed"))
        // Too little data is never a met grade.
        val thin = Improve.progress(Improve.Goal(Improve.Kind.SPEED, 50, 30), Improve.week(mon, emptyList()), emptyList())
        assertEquals(Improve.Grade.UNKNOWN, thin.grade)
        assertEquals(setOf(Improve.Kind.FEWER_MISTAKES), Improve.missedKinds(plan, week, learned))
    }

    @Test fun theWeekendGradesAndSetsNewGoals() {
        val old = Improve.Plan(mon, listOf(Improve.Goal(Improve.Kind.FEWER_MISTAKES, 1, 3)), history = listOf(Improve.Score(mon.minusWeeks(1), 0, 2)))
        val c = Improve.weekend(old, roughWeek(), emptyList(), nextMon)
        assertEquals(nextMon, c.plan.week)
        assertEquals(Improve.Kind.FEWER_MISTAKES, c.plan.goals.first().kind, "missed last week: tried first again")
        assertEquals(listOf(Improve.Score(mon.minusWeeks(1), 0, 2), Improve.Score(mon, 0, 1)), c.plan.history)
        assertNotNull(c.plan.lastGrade)
        assertTrue(c.said.contains("My own week, Boss: I heard 30 questions"), c.said)
        assertTrue(c.said.contains("you asked most \"what are the levels on"), c.said)
        assertTrue(c.said.contains("sat out 2 ideas"), c.said)
        assertTrue(c.said.contains("My goals for next week: 1)"), c.said)
        assertTrue(c.said.contains(Improve.ONLY))
        assertTrue(c.spoken.startsWith("Boss, my own review. Last week I met 0 of my 1 goal."), c.spoken)
        assertFalse(missedWords.any { c.spoken.contains(it) || c.said.contains(it) }, "Boss's words are never said in the review")
        // A plan for another week is not graded on this one.
        val stale = Improve.weekend(old.copy(week = mon.minusWeeks(3)), roughWeek(), emptyList(), nextMon)
        assertNull(stale.plan.lastGrade)
        assertEquals(old.history, stale.plan.history)
        // No plan at all: still reviewed and set.
        assertEquals(3, Improve.weekend(null, roughWeek(), emptyList(), nextMon).plan.goals.size)
    }

    @Test fun trendOverWeeks() {
        assertNull(Improve.trend(listOf(Improve.Score(mon, 1, 3))))
        assertEquals("Over the last 2 weeks I met 1 of 3, then 3 of 3 - getting better.",
            Improve.trend(listOf(Improve.Score(mon.minusWeeks(1), 1, 3), Improve.Score(mon, 3, 3))))
        assertTrue(Improve.trend(listOf(Improve.Score(mon.minusWeeks(1), 2, 2), Improve.Score(mon, 0, 2)))!!.endsWith("slipping."))
    }

    @Test fun answeredDuringTheWeek() {
        val plan = Improve.Plan(mon, Improve.choose(roughWeek(mon.minusWeeks(1)), emptyList()), lastGrade = "Last week's goals: met 1 of 2.")
        val sofar = Improve.week(mon, listOf(day(Improve.MISTAKES to 1, Improve.TIMED to 6, Improve.SLOW to 1)))
        val wed = mon.plusDays(2)
        val said = Improve.say(plan, sofar, emptyList(), wed)
        assertTrue(said.startsWith("My goals this week, Boss: 1) understand at least 3"), said)
        assertTrue(said.contains("within it so far") && said.contains("Last week's goals: met 1 of 2."), said)
        assertTrue(Improve.say(null, sofar, emptyList(), wed).startsWith("I haven't set goals for myself this week yet"))
        assertTrue(Improve.say(plan, sofar, emptyList(), wed.plusWeeks(1)).startsWith("I haven't set goals"), "last week's plan is not this week's")
        val check = assertNotNull(Improve.checkLine(plan, sofar, emptyList(), wed))
        assertTrue(check.startsWith("Checking my own goals for the week, Boss:"))
        val wrap = assertNotNull(Improve.wrap(plan, sofar, emptyList(), wed))
        assertTrue(wrap.startsWith("My own goals this week: 2 of 3 on track; behind on understand at least 3"), wrap)
        assertNull(Improve.wrap(plan.copy(goals = emptyList()), sofar, emptyList(), wed))
        assertEquals(5, Improve.toStudy(plan, emptyList(), wed).size)
        assertEquals(4, Improve.toStudy(plan, listOf(Corrections.Learned(Corrections.normalize("snarf it"), "q")), wed).size)
        assertTrue(Improve.toStudy(plan, emptyList(), wed.plusWeeks(1)).isEmpty())
    }

    @Test fun missedPhrasingsKeptOverTheWeek() {
        var l = emptyList<Pair<LocalDate, String>>()
        l = Improve.addMissed(l, mon.minusDays(20), "old one")
        l = Improve.addMissed(l, mon, "zorbo flim")
        l = Improve.addMissed(l, mon, "Zorbo  flim")
        l = Improve.addMissed(l, mon.plusDays(1), "zorbo flim")
        l = Improve.addMissed(l, mon.plusDays(1), "my otp is 123456")
        assertFalse(l.any { it.second == "old one" }, "older than two weeks dropped")
        assertEquals(3, l.size)
        assertFalse(l.any { it.second.contains("123456") }, "secrets hidden")
        assertEquals(3, Improve.missedIn(l, mon).size)
        assertTrue(Improve.missedIn(l, nextMon).isEmpty())
        // Repeats across days are one phrasing in the week.
        assertEquals(2, Improve.week(mon, emptyList(), Improve.missedIn(l, mon)).missed.size)
    }

    @Test fun askedOnlyForHisOwnGoals() {
        listOf("how are you improving?", "Jarvis, what are your goals?", "what are your goals this week", "your goals", "are you getting better",
            "how are your goals going", "what are you working to improve", "tell me your goals").forEach { assertTrue(Improve.asked(it), it) }
        listOf("what are my goals?", "how is my goal progress", "set a goal to lose no more than 5000 this week", "how are you",
            "is nifty improving", "what's your plan today").forEach { assertFalse(Improve.asked(it), it) }
    }

    @Test fun theAgendaChecksHisGoalsOnTradingDays() {
        val items = Agenda.build(Agenda.Facts(mon.plusDays(1), tradingDay = true, selfGoals = 2))
        val self = items.single { it.kind == Agenda.Kind.SELF }
        assertEquals(15 * 60 + 25, self.at)
        assertEquals(Agenda.Means.SPEAK, self.kind.means)
        assertTrue(self.text.contains("2 goals"))
        assertEquals("checked my own goals for the week", Agenda.summary(self))
        assertTrue(Agenda.build(Agenda.Facts(mon.plusDays(1), tradingDay = true)).none { it.kind == Agenda.Kind.SELF })
        assertTrue(Agenda.build(Agenda.Facts(mon.plusDays(5), tradingDay = false, selfGoals = 2)).none { it.kind == Agenda.Kind.SELF })
    }
}
