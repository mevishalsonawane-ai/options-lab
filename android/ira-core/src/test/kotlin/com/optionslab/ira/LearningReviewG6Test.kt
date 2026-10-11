package com.optionslab.ira

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The line-by-line review's G6 fixes (learning and statistics): no bot rupee in a lessons, goals or weekly answer on a
 * locked phone (G6-1); a weak lesson's early noise never retires it, and a retired lesson can be confirmed again (G6-2); a
 * forge idea's result dated by the day it settled (G6-3); a hand exit's net never guessed (G6-4); the weekly report due only
 * after Friday's passes (G6-5); F&O's 15:30-15:40 entries in the afternoon (G6-6); Boss's rules tested always-valid (G6-10).
 * Fixed dates only (never the real day).
 */
class LearningReviewG6Test {
    private val A = AgentLessons
    private val d0: LocalDate = LocalDate.of(2026, 6, 1)
    private val ist = ZoneId.of("Asia/Kolkata")

    private fun trip(owner: String, day: LocalDate, net: Double, h: Int = 11, m: Int = 0) =
        Insights.Trip("X", day.atTime(h, m), day.atTime(h, m).plusMinutes(20), net, owner)

    private fun trips(owner: String, from: LocalDate, days: Int, net: (Int) -> Double) = (0 until days).map { trip(owner, from.plusDays(it.toLong()), net(it)) }

    private fun lesson(b: AgentLessons.Book, id: String) = b.lessons.first { it.id == A.norm(id) }

    private val money = Regex("Rs|₹|\\d{3,}")

    // ---- G6-1 ----

    @Test fun lockedLessonsGoalsAndWeekSayNoRupee() {
        val b = A.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(29), trips = trips("Hero", d0, 30) { 1_000.0 })).book
        assertTrue(money.containsMatchIn(A.sayAll(b)), "unlocked: the numbers are said")
        val locked = A.sayLocked(b)
        assertFalse(money.containsMatchIn(locked), locked)
        assertTrue(locked.endsWith(A.LOCKED), locked)
        assertTrue(locked.startsWith("I hold ${A.active(b).size} lessons from the agents, Boss: "), locked)
        val hero = lesson(b, "strat:Hero")
        assertTrue(money.containsMatchIn(A.sayWhy(listOf(hero))))
        assertEquals(A.LOCKED, A.sayWhy(listOf(hero), locked = true))
        // Learning goals: their rupees at stake only when unlocked.
        val end = d0.plusDays(29)
        val thin = AgentLessons.Lesson("spec:liquidity:oi", AgentLessons.Topic.SPECIALIST, AgentLessons.Kind.RUPEES, "The OI specialist on Liquidity 15+5",
            listOf("x"), setOf("the specialists"), (0 until 12).map { AgentLessons.Bucket(end.minusDays((12 - it).toLong()), 1, -500.0, 0) }, -1,
            AgentLessons.Status.NEW, AgentLessons.Confidence.BUILDING, end.minusDays(12), end, end)
        val goals = LearningGoals.next(LearningGoals.Book(), listOf(thin), emptyMap(), end)
        assertTrue(goals.goals.isNotEmpty())
        assertTrue(LearningGoals.say(goals).contains("at stake"))
        val g = LearningGoals.say(goals, locked = true)
        assertFalse(money.containsMatchIn(g) || g.contains("at stake"), g)
        assertEquals("I have 1 learning goal just now. ${LearningGoals.LOCKED}", g)
        // The weekly report: Autopilot's rupee effect only when unlocked.
        val w = GrowthReport.Week(LocalDate.of(2026, 10, 12), learned = 2, made = 1, kept = 1, effectRs = 4_200.0, effectN = 6)
        assertEquals(GrowthReport.LOCKED, GrowthReport.say(w, locked = true))
        assertNotEquals(GrowthReport.LOCKED, GrowthReport.say(w))
        assertEquals(GrowthReport.say(null), GrowthReport.say(null, locked = true), "no report: the generic line may be said")
    }

    // ---- G6-2 ----

    @Test fun aWeakLessonsEarlyNoiseNeverRetiresIt() {
        // Day 1 +500; day 2 -700 (the sum below zero); day 4 +300 (above again): two flips while the lesson is weak.
        val days = listOf(d0 to 500.0, d0.plusDays(1) to -700.0, d0.plusDays(3) to 300.0)
        var b = AgentLessons.Book()
        for (k in days.indices) {
            val ts = days.take(k + 1).map { (d, x) -> trip("Noisy", d, x) }
            b = A.pass(b, AgentLessons.Inputs(days[k].first, trips = ts)).book
        }
        val l = lesson(b, "strat:Noisy")
        assertEquals(AgentLessons.Confidence.WEAK, l.confidence)
        assertNotEquals(AgentLessons.Status.RETIRED, l.status, "a weak lesson's noise never retires it")
        assertNotEquals(AgentLessons.Status.WEAKENED, l.status)
        assertTrue(A.active(b).any { it.id == l.id })
    }

    @Test fun aRetiredLessonCanBeConfirmedAgainByStrongEvidence() {
        val retired = AgentLessons.Lesson("strat:hero", AgentLessons.Topic.STRATEGY, AgentLessons.Kind.RUPEES, "Hero", listOf("hero"), setOf("the bot trades"),
            listOf(AgentLessons.Bucket(d0, 1, 100.0, 1)), 1, AgentLessons.Status.RETIRED, AgentLessons.Confidence.WEAK, d0, d0, d0, checkedN = 1)
        // Forty more days of winners on the same side: strong, and with a q of its own it is confirmed again.
        val ts = listOf(trip("Hero", d0, 100.0)) + trips("Hero", d0.plusDays(1), 40) { 100.0 + it }
        val p = A.pass(AgentLessons.Book(listOf(retired)), AgentLessons.Inputs(d0.plusDays(40), trips = ts))
        val l = lesson(p.book, "strat:Hero")
        assertTrue(l.q != null && l.q!! <= A.BH_Q, "q ${l.q}")
        assertEquals(AgentLessons.Confidence.STRONG, l.confidence)
        assertEquals(AgentLessons.Status.CONFIRMED, l.status)
        assertTrue(A.active(p.book).any { it.id == l.id })
    }

    // ---- G6-3 ----

    @Test fun aForgeIdeaRetiredAfterItsForwardTestCountsAsFailedOnItsRetirementDay() {
        val day = LocalDate.of(2026, 10, 12)
        val bt = StrategyForge.Backtest(55, 300, 12_000.0, 48.0, listOf(5_000.0, 4_000.0, 3_000.0), 0.01, -40.0, 6_000.0, false, listOf("x" to true))
        val born = day.minusDays(40)
        val cand = StrategyForge.Candidate(7, born, "NIFTY", ShadowSim.Rules(ShadowSim.Entry.VWAP_RECLAIM, stopPct = 0.2, targetPct = 0.5),
            StrategyForge.ExitBlock.FIXED)
        val key = "block:" + StrategyForge.key(ShadowSim.Entry.VWAP_RECLAIM)
        // Still in its forward test: not a result yet (not a pass that can never be taken back).
        val fwd = StrategyForge.Book(records = listOf(StrategyForge.Record(cand.copy(born = day), StrategyForge.Stage.FORWARD, bt, fwd = listOf(200.0), since = day)))
        assertTrue(LabLessons.results(fwd, VariantLab.Book(), since = day.minusDays(5)).none { it.key == key })
        // Retired yesterday, 40 days after its birth: read in tonight's window, as a fail with its forward net.
        val ret = StrategyForge.Book(records = listOf(StrategyForge.Record(cand, StrategyForge.Stage.RETIRED, bt, fwd = listOf(-500.0, -700.0),
            since = day.minusDays(1))))
        val rs = LabLessons.results(ret, VariantLab.Book(), since = day.minusDays(5)).filter { it.key == key }
        assertEquals(1, rs.size)
        assertEquals(day.minusDays(1), rs[0].day)
        assertFalse(rs[0].passed)
        assertEquals(-1_200.0, rs[0].rs, 1e-9)
    }

    // ---- G6-4 ----

    @Test fun aHandExitsNetWaitsForItsOwnRoundTripNeverGuessed() {
        val at = d0.atTime(10, 0).atZone(ist).toInstant().toEpochMilli()
        val t = BossTrades.Trade("kite:1", "NIFTY26OCT25000CE", true, at, closedMs = at + 600_000L, closeId = "kite:2")
        assertTrue(t.pending); assertFalse(t.closed); assertFalse(t.open)
        // The trade book does not hold this close yet (only an earlier trip on the symbol): still pending.
        val still = BossTrades.settle(listOf(t), mapOf("kite:0" to -1_200.0)).single()
        assertNull(still.net); assertTrue(still.pending)
        assertTrue(BossTrades.rules(List(10) { still }).isEmpty(), "a pending close is never in a rule")
        // Its own round trip (two pieces closed by the one order): its net.
        val done = BossTrades.settle(listOf(t), mapOf("kite:2" to 3_000.0, "kite:0" to -1_200.0)).single()
        assertEquals(3_000.0, done.net); assertTrue(done.closed)
        // The file keeps a pending close and its order.
        assertEquals(listOf(t), BossTrades.decode(BossTrades.encode(listOf(t))))
        val open = BossTrades.Trade("paper:9", "NIFTY", false, at)
        assertEquals(listOf(open), BossTrades.decode(BossTrades.encode(listOf(open))))
        assertEquals(13, BossTrades.encode(listOf(open)).split('|').size, "a trade with no pending close keeps the old 13 fields")
    }

    // ---- G6-5 ----

    @Test fun theWeeklyReportIsDueOnlyAfterFridaysPasses() {
        val mon = LocalDate.of(2026, 10, 12)
        val fri = mon.plusDays(4)
        assertEquals(mon.minusWeeks(1), GrowthReport.dueWeek(fri.atTime(15, 35)), "before F&O's close and the 15:45 passes")
        assertEquals(mon.minusWeeks(1), GrowthReport.dueWeek(fri.atTime(23, 59)))
        assertEquals(mon, GrowthReport.dueWeek(fri.plusDays(1).atTime(0, 0)))
        assertFalse(GrowthReport.due(fri.atTime(16, 0), mon.minusWeeks(1)))
        assertTrue(GrowthReport.due(fri.plusDays(1).atTime(8, 0), mon.minusWeeks(1)))
    }

    // ---- G6-6 ----

    @Test fun anFnoEntryBefore1540IsInTheAfternoon() {
        assertEquals("afternoon", A.slot(15 * 60 + 35))
        assertEquals("evening", A.slot(15 * 60 + 40))
        val b = A.pass(AgentLessons.Book(), AgentLessons.Inputs(d0, trips = listOf(trip("Late", d0, 100.0, 15, 35)))).book
        assertTrue(b.lessons.any { it.id == "tod:late:afternoon" }, b.lessons.map { it.id }.toString())
        assertTrue(b.lessons.none { it.id == "tod:late:evening" })
        assertTrue(lesson(b, "tod:late:afternoon").name.endsWith("(13:30-15:40)"), lesson(b, "tod:late:afternoon").name)
    }

    // ---- G6-10 ----

    @Test fun bossRulesAreTestedAlwaysValidOldestDayFirst() {
        // Stored newest first on purpose: the test must still see the days oldest first.
        val ts = (0 until 12).map { i ->
            val at = d0.plusDays(i.toLong()).atTime(14, 30).atZone(ist).toInstant().toEpochMilli()
            BossTrades.Trade("paper:$i", "NIFTY", false, at, BossTrades.Why.GUT, closedMs = at + 60_000L, net = if (i % 4 == 0) 200.0 else -300.0 - 10 * i)
        }.reversed()
        val r = BossTrades.rules(ts).single { it.key == "why:GUT" }
        val perDay = ts.sortedBy { it.openedMs }.map { it.net!! }.toDoubleArray()
        assertEquals(LearnStats.signFlipAlwaysValidP(perDay, side = -1), r.p, 1e-12)
        assertNotEquals(LearnStats.signFlipP(perDay), r.p, "not the fixed-sample p looked at on every review")
        // Early winners never back "it loses": a group that won its first ten days and lost big on the last two has no
        // evidence of losing, so no warning (a two-sided p would be ~0 from the early run of wins).
        val turned = (0 until 12).map { i ->
            val at = d0.plusDays(i.toLong()).atTime(14, 30).atZone(ist).toInstant().toEpochMilli()
            BossTrades.Trade("paper:t$i", "NIFTY", false, at, BossTrades.Why.GUT, closedMs = at + 60_000L, net = if (i < 10) 100.0 else -2_500.0)
        }
        val g = BossTrades.rules(turned).single { it.key == "why:GUT" }
        assertEquals(-1, g.sign)
        assertTrue(g.p > BossTrades.Q_WARN, "p ${g.p}")
        assertNull(BossTrades.warn(BossTrades.rules(turned), 14 * 60 + 30))
        // A record that clearly loses still warns: nine losing days, then two small wins.
        val losing = (0 until 11).map { i ->
            val at = d0.plusDays(i.toLong()).atTime(14, 20).atZone(ist).toInstant().toEpochMilli()
            BossTrades.Trade("paper:l$i", "NIFTY", false, at, BossTrades.Why.GUT, closedMs = at + 60_000L, net = if (i >= 9) 300.0 else -500.0)
        }
        assertTrue(BossTrades.warn(BossTrades.rules(losing), 14 * 60 + 30)?.startsWith("Heads up, Boss: ") == true)
    }
}
