package com.optionslab.ira

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The order review ([OrderReview]): each flaw from its numbers, the day's summary, patterns, the lab asks, Jarvis's words, the kept book. */
class OrderReviewTest {
    private val day = LocalDate.of(2026, 10, 12)
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int, d: LocalDate = day): Long = d.atTime(h, m).atZone(ist).toInstant().toEpochMilli()
    private val R = OrderReview
    private val P = OrderReview.Venue.PAPER
    private val L = OrderReview.Venue.LIVE

    private fun trade(id: String = "t1", strategy: String = "ORB Fresh", venue: OrderReview.Venue = P, entry: Double = 100.0, exit: Double? = 90.0,
                      inAt: Long = at(10, 30), outAt: Long? = at(10, 50), stop: Double? = 90.0, target: Double? = 130.0, best: Double? = null,
                      why: String? = "stop", net: Double? = null, after: List<OrderReview.Bar> = emptyList(), window: IntRange? = null,
                      squareOff: Int? = null, ruleExit: Double? = null, ruleWhy: String? = null, manual: Boolean = false, qty: Int = 30) =
        OrderReview.Trade(id, venue, strategy, "BANKNIFTY26OCT55000CE", qty, entry, inAt, exit, outAt, stop = stop, target = target, best = best,
            exitWhy = why, ruleExit = ruleExit, ruleExitWhy = ruleWhy, window = window, squareOff = squareOff,
            net = net ?: exit?.let { (it - entry) * qty - 40 }, charges = 40.0, after = after, manual = manual)

    private fun cats(fs: List<OrderReview.Flaw>) = fs.map { it.category }.toSet()

    // ---- execution ----

    @Test fun rejectionsAreSortedIntoFundsAndFixableCauses() {
        val d = R.review(OrderReview.Inputs(day, orders = listOf(
            OrderReview.Order("k1", L, "ORB", "X", "BUY", 30, 0, "REJECTED", "Insufficient funds. Required margin is 5000", at(10, 0)),
            OrderReview.Order("k2", L, "Liquidity", "Y", "BUY", 30, 0, "REJECTED", "Price is outside the circuit limit", at(11, 0)),
        )))
        val f = d.flaws.filter { it.category == OrderReview.Category.REJECTED }
        assertEquals(2, f.size)
        assertEquals(OrderReview.Fix.NOTE, f[0].fix)
        assertTrue("account, not the app" in f[0].evidence)
        assertEquals(OrderReview.Fix.NEEDS_FIX, f[1].fix)
        assertEquals(OrderReview.Severity.HIGH, f[1].severity)
    }

    @Test fun partialsDuplicatesCancelsAndOrphans() {
        val d = R.review(OrderReview.Inputs(day, orders = listOf(
            OrderReview.Order("k1", L, "ORB", "X", "BUY", 60, 30, "CANCELLED", "", at(10, 0)),
            OrderReview.Order("k2", L, "ORB", "Z", "BUY", 30, 30, "COMPLETE", "", at(10, 5)),
            OrderReview.Order("k3", L, "ORB", "Z", "BUY", 30, 30, "COMPLETE", "", at(10, 5) + 2_000),
            OrderReview.Order("k4", P, "Pine", "W", "BUY", 30, 0, "CANCELLED", "expired", at(10, 6)),
            OrderReview.Order("k5", L, "ORB", "SL1", "SELL", 30, 0, "CANCELLED", "", at(10, 7), type = "SL-M"),
            OrderReview.Order("k6", L, "Manual", "M", "BUY", 30, 0, "CANCELLED", "", at(10, 8), manual = true),
        ), trades = listOf(trade(id = "t", venue = L, inAt = at(10, 5)).copy(symbol = "Z")), tracked = setOf("ORB")))
        val by = d.flaws.groupBy { it.category }
        assertEquals(listOf("k1"), by[OrderReview.Category.PARTIAL]?.map { it.ref })
        assertEquals(listOf("k3"), by[OrderReview.Category.DUPLICATE]?.map { it.ref })
        // A resting stop cancelled (the trade left otherwise) and Boss's own cancel are not flaws.
        assertEquals(listOf("k4"), by[OrderReview.Category.CANCELLED]?.map { it.ref })
        // k1 filled 30 of X and no ORB trade follows X; Z is followed.
        assertEquals(listOf("k1"), by[OrderReview.Category.ORPHAN]?.map { it.ref })
    }

    @Test fun slippageIsJudgedOnlyAgainstEnoughOfTheStrategysOwnFills() {
        fun fill(d: String, intended: Double, avg: Double, strategy: String = "ORB", paper: Boolean = false) =
            Slippage.Fill(d, 0L, strategy, "X", "BUY", Slippage.Leg.ENTRY, 30, intended, avg, 0.05, paper, Slippage.Basis.SIGNAL)
        val base = (1..12).map { fill("2026-10-0${1 + it % 9}", 100.0, 100.10) }
        val today = listOf(fill("2026-10-12", 100.0, 101.0), fill("2026-10-12", 100.0, 100.05), fill("2026-10-12", 100.0, 103.0, strategy = "Pine"),
            fill("2026-10-12", 100.0, 103.0, paper = true))
        val d = R.review(OrderReview.Inputs(day, fills = base + today))
        val s = d.flaws.filter { it.category == OrderReview.Category.SLIPPAGE }
        assertEquals(1, s.size, "$s")
        assertEquals(30.0, s[0].rupees, 1e-6)
        assertTrue("p90" in s[0].evidence)
        // Pine has no earlier fills to compare with: said, not judged.
        assertTrue(d.notes.any { "not judged" in it })
    }

    @Test fun syncAndSpeedAreExecutionFlaws() {
        val d = R.review(OrderReview.Inputs(day, sync = listOf(PositionSync.Mismatch(PositionSync.Kind.STOP_MISSING, "X", "ORB", 30, 30),
            PositionSync.Mismatch(PositionSync.Kind.UNTRACKED, "Y", null, null, 30)), speed = listOf("Decision → order sent slow")))
        assertEquals(setOf(OrderReview.Category.STOP_NOT_RESTING, OrderReview.Category.SYNC, OrderReview.Category.SLOW), cats(d.flaws))
        assertTrue(d.flaws.filter { it.category != OrderReview.Category.SLOW }.all { it.fix == OrderReview.Fix.NEEDS_FIX && it.severity == OrderReview.Severity.HIGH })
    }

    // ---- rules ----

    @Test fun windowSquareOffLateExitAndExitReason() {
        val t = trade(stop = 95.0, exit = 88.0, inAt = at(14, 50), outAt = at(15, 20), window = (9 * 60 + 15)..(14 * 60 + 30),
            squareOff = 15 * 60 + 15, why = "stop")
        val fs = R.tradeFlaws(t)
        assertTrue(OrderReview.Category.OUTSIDE_WINDOW in cats(fs))
        assertTrue(OrderReview.Category.PAST_SQUARE_OFF in cats(fs))
        val late = fs.first { it.category == OrderReview.Category.LATE_EXIT }
        assertEquals(7.0 * 30, late.rupees, 1e-6)
        // Late already: the loss over plan is not counted twice.
        assertFalse(OrderReview.Category.LOSS_OVER_PLAN in cats(fs))
        // A stop just past by a tick or two is the market, not a flaw.
        assertTrue(R.tradeFlaws(trade(stop = 90.0, exit = 89.9)).none { it.category == OrderReview.Category.LATE_EXIT })
        // Closed by another reason than its rules would have.
        val ex = R.tradeFlaws(trade(exit = 104.0, why = "time stop", ruleExit = 110.0, ruleWhy = "target", stop = 90.0))
        val e = ex.first { it.category == OrderReview.Category.EXIT_DIFFERS }
        assertEquals(6.0 * 30, e.rupees, 1e-6)
        // The manager's own changes are its own.
        assertTrue(R.tradeFlaws(trade(exit = 104.0, why = "trade_manager", ruleExit = 110.0, ruleWhy = "target")).none { it.category == OrderReview.Category.EXIT_DIFFERS })
        // Still open past its square-off when the review runs.
        assertTrue(R.tradeFlaws(trade(exit = null, outAt = null, squareOff = 15 * 60 + 15), at(15, 45)).any { it.category == OrderReview.Category.PAST_SQUARE_OFF })
    }

    @Test fun aLateExitShowsWhenTheStopWasCrossed() {
        val path = listOf(OrderReview.Bar(at(10, 40), 96.0, 89.0, 92.0), OrderReview.Bar(at(10, 41), 92.0, 86.0, 87.0))
        val fs = R.tradeFlaws(trade(stop = 90.0, exit = 85.0, outAt = at(10, 49)).copy(path = path))
        assertTrue(fs.first { it.category == OrderReview.Category.LATE_EXIT }.evidence.contains("crossed at 10:40"))
    }

    // ---- outcomes ----

    @Test fun lossOverPlanGiveBackThetaAndAfterTheExit() {
        assertTrue(R.tradeFlaws(trade(stop = 95.0, exit = 93.3, why = "gap")).any { it.category == OrderReview.Category.LOSS_OVER_PLAN })
        val gb = R.tradeFlaws(trade(best = 130.0, exit = 105.0, why = "profit_lock")).first { it.category == OrderReview.Category.GAVE_BACK }
        // A lock at half the gain: 115, sold at 105.
        assertEquals(10.0 * 30, gb.rupees, 1e-6)
        val th = R.tradeFlaws(trade(best = 102.0, exit = 96.0, outAt = at(11, 20), why = "time", stop = 80.0))
        assertTrue(th.any { it.category == OrderReview.Category.THETA_BLEED })
        val after = listOf(OrderReview.Bar(at(11, 0), 132.0, 100.0, 131.0))
        val stt = R.tradeFlaws(trade(after = after)).first { it.category == OrderReview.Category.STOP_THEN_TARGET }
        assertTrue("evidence, not proof" in stt.evidence)
        assertEquals(0.0, stt.cost)
        val early = R.tradeFlaws(trade(exit = 110.0, why = "target", after = listOf(OrderReview.Bar(at(11, 0), 140.0, 108.0, 139.0))))
        assertTrue(early.any { it.category == OrderReview.Category.EARLY_EXIT && it.cost == 0.0 })
        // Nothing stored after the exit: no claim at all.
        assertTrue(R.tradeFlaws(trade()).none { it.category.evidenceOnly })
    }

    @Test fun bossesHandTradesAreJudgedOnExecutionNotOnBotRules() {
        val fs = R.tradeFlaws(trade(manual = true, strategy = "Manual", window = 600..610, why = "fear", ruleWhy = "target", ruleExit = 120.0))
        assertFalse(OrderReview.Category.OUTSIDE_WINDOW in cats(fs))
        assertFalse(OrderReview.Category.EXIT_DIFFERS in cats(fs))
        assertTrue(fs.all { it.manual })
        assertTrue(fs.none { "ORB" in it.evidence }, "$fs")
    }

    // ---- the day ----

    @Test fun theDaysSummaryCountsNetCostsAndFlaws() {
        val d = R.review(OrderReview.Inputs(day,
            orders = listOf(OrderReview.Order("p1", P, "ORB Fresh", "X", "BUY", 30, 30, "COMPLETE", "", at(10, 30))),
            trades = listOf(trade(id = "a", exit = 120.0, why = "target", stop = 90.0), trade(id = "b", best = 130.0, exit = 105.0, why = "profit_lock"),
                trade(id = "c", exit = 88.0, stop = 95.0))))
        val s = d.summaries.single()
        assertEquals("ORB Fresh", s.strategy)
        assertEquals(3, s.trades); assertEquals(2, s.wins); assertEquals(1, s.losses)
        assertEquals((20.0 + 5.0 - 12.0) * 30 - 120.0, s.net, 1e-6)
        assertTrue(OrderReview.Category.GAVE_BACK in s.flaws.keys && OrderReview.Category.LATE_EXIT in s.flaws.keys)
        assertTrue(s.cost > 0)
        assertTrue(R.summaryLine(s).startsWith("ORB Fresh (paper): 1 order, 3 trades, 2 won 1 lost, net"))
        val line = R.cardLine(d, 1, day)
        assertTrue(line.startsWith("Today: 1 order · 2 flaws (est. ₹"), line)
        assertTrue(line.endsWith("· 1 improvement testing"), line)
        assertFalse("₹" in R.cardLine(d, 0, day, money = false))
        assertEquals("Nothing reviewed yet (the review runs after the close)", R.cardLine(null, 0, day))
        // The Automation card's line for it, in its place after the lab's.
        val rows = AutomationSummary.Lines(manager = "m", lab = "l", orders = "o", orderFlow = "f").rows().map { it.first }
        assertEquals(listOf(AutomationSummary.Section.MANAGER, AutomationSummary.Section.LAB, AutomationSummary.Section.ORDERS, AutomationSummary.Section.ORDER_FLOW), rows)
    }

    @Test fun twoReviewsOfOneDayMerge() {
        val a = R.review(OrderReview.Inputs(day, trades = listOf(trade(id = "a"))))
        val b = R.review(OrderReview.Inputs(day, trades = listOf(trade(id = "b", exit = 120.0, why = "target"))))
        val m = R.merge(a, b)
        assertEquals(2, m.trades)
        assertEquals(2, m.summaries.single().trades)
        assertEquals(1, m.summaries.single().wins)
        assertEquals(a.flaws.size + b.flaws.size, R.merge(m, b).flaws.size, "a flaw seen twice is kept once")
    }

    // ---- patterns, lessons, the lab ----

    private fun logOf(d: LocalDate, flaws: Int, cat: OrderReview.Category = OrderReview.Category.GAVE_BACK, strategy: String = "ORB Fresh") =
        OrderReview.DayLog(d, if (flaws == 0) emptyMap() else mapOf("$strategy|p|${cat.key}" to OrderReview.Tally(flaws, 100.0 * flaws)),
            mapOf("$strategy|p" to 4), mapOf("$strategy|p" to 50.0))

    @Test fun oneOffsNeverBecomePatterns() {
        val days = (0 until 10).map { day.minusDays((9 - it).toLong()) }
        // Three on one day: one bad day, not a pattern.
        assertTrue(R.patterns(listOf(logOf(days[9], 3))).isEmpty())
        // Two in two days: too few.
        assertTrue(R.patterns(listOf(logOf(days[1], 1), logOf(days[5], 1))).isEmpty())
        val p = R.patterns(listOf(logOf(days[1], 1), logOf(days[3], 0), logOf(days[5], 2))).single()
        assertEquals(3, p.count); assertEquals(2, p.days)
        assertEquals(OrderReview.Category.GAVE_BACK, p.category)
        // Older than the window: forgotten.
        val old = (0 until 12).map { logOf(day.minusDays(30 - it.toLong()), 0) }
        assertTrue(R.patterns(listOf(logOf(day.minusDays(40), 2), logOf(day.minusDays(39), 2)) + old).isEmpty())
    }

    @Test fun patternsBecomeLessonInputsWithTheirReviewedDays() {
        val logs = listOf(logOf(day.minusDays(3), 1), logOf(day.minusDays(2), 0), logOf(day.minusDays(1), 2))
        val ps = R.patterns(logs)
        val ins = R.lessonInputs(logs, ps)
        assertEquals(3, ins.size)
        assertEquals(listOf(1, 0, 2), ins.map { it.hits })
        assertTrue(ins.all { it.n == 4 && it.key == "orb_fresh:paper:gb" })
        // And Jarvis's lessons take them, as a rate.
        val pass = AgentLessons.pass(AgentLessons.Book(), AgentLessons.Inputs(day, orders = ins))
        val l = pass.book.lessons.single { it.id.startsWith("order:") }
        assertEquals(AgentLessons.Topic.ORDERS, l.topic)
        assertEquals(12, AgentLessons.evidence(l).n)
        assertTrue(AgentLessons.statement(l).endsWith("keeps happening"), AgentLessons.statement(l))
        assertTrue("on 3 of 12 orders reviewed" in AgentLessons.evidenceLine(l), AgentLessons.evidenceLine(l))
        // Kept and read back.
        assertEquals(l.topic, AgentLessons.decode(AgentLessons.encode(pass.book)).lessons.single { it.id == l.id }.topic)
        assertEquals(AgentLessons.Topic.ORDERS, AgentLessons.asked("what have you learned about my orders")?.topic)
    }

    @Test fun improvementsAreRoutedHonestly() {
        fun p(cat: OrderReview.Category, strategy: String = "ORB Fresh") = OrderReview.Pattern(strategy, P, cat, 3, 2, 300.0, day.minusDays(3), day)
        val bug = R.improvement(p(OrderReview.Category.STOP_NOT_RESTING))
        assertEquals(OrderReview.Route.NEEDS_FIX, bug.route); assertNull(bug.ask)
        val lab = R.improvement(p(OrderReview.Category.STOP_THEN_TARGET))
        assertEquals(OrderReview.Route.LAB, lab.route)
        assertEquals(OrderReview.LabAsk("orb_fresh", ArmParams.STOP_PTS, 1, lab.ask!!.why), lab.ask)
        assertEquals("liquidity", R.labKeyOf("Liquidity 5m FINNIFTY"))
        assertEquals(ArmParams.LOCK to 1, R.paramFor("liquidity", OrderReview.Category.GAVE_BACK))
        // The lab has no knob for this one there: a proposal only.
        assertEquals(OrderReview.Route.PROPOSAL, R.improvement(p(OrderReview.Category.THETA_BLEED)).route)
        assertEquals(OrderReview.Route.PROPOSAL, R.improvement(p(OrderReview.Category.GAVE_BACK, "Pine")).route)
        assertEquals(OrderReview.Route.WATCH, R.improvement(p(OrderReview.Category.CANCELLED)).route)
    }

    @Test fun theLabSeedsAnAskedVariantThroughItsOwnChecks() {
        val b0 = VariantLab.Book(runs = mapOf("orb_fresh" to VariantLab.Run.PAPER))
        val seed = VariantLab.Seed("r1", "orb_fresh", ArmParams.STOP_PTS, -1, "order review: stopped then target")
        val (b1, r1) = VariantLab.seed(b0, seed, day, emptyList())
        assertNotNull(r1.variant); assertTrue(r1.final)
        val v = b1.variants.single()
        assertEquals(VariantLab.Status.ALIVE, v.status)
        assertEquals(b0.currentOf("orb_fresh").getValue(ArmParams.STOP_PTS) - 5.0, v.values.getValue(ArmParams.STOP_PTS), 1e-9)
        assertEquals(1, b1.tried, "counted in the trial penalty")
        // The same ask again: already tried, nothing new.
        val (b2, r2) = VariantLab.seed(b1, seed, day, emptyList())
        assertEquals(b1, b2); assertTrue(r2.status.startsWith("already tried"))
        // Liquidity's stop is at its researched maximum: never wider - a proposal only.
        val lq = VariantLab.seed(VariantLab.Book(runs = mapOf("liquidity" to VariantLab.Run.PAPER)),
            VariantLab.Seed("r2", "liquidity", ArmParams.STOP_PCT, 1, "x"), day, emptyList())
        assertNull(lq.second.variant); assertTrue("safe bound" in lq.second.status)
        // Not running: it waits.
        val off = VariantLab.seed(VariantLab.Book(), seed, day, emptyList())
        assertFalse(off.second.final)
        // Through the day's pass: born after the comparison, so the day that showed the flaw is not its evidence.
        val pass = VariantLab.day(b0, day, mapOf("orb_fresh" to VariantLab.Run.PAPER), emptyMap(), emptyList(), DiscoveryStats.Rng(1), seeds = listOf(seed))
        val sd = pass.seeded.single()
        val made = pass.book.variants.first { it.id == sd.variant }
        assertEquals(0, made.n)
        assertEquals("order review: stopped then target", made.why)
    }

    @Test fun beforeAndAfterIsMeasuredNotClaimed() {
        val from = day.minusDays(10)
        val logs = (0 until 30).map { k -> val d = day.minusDays(29 - k.toLong()); logOf(d, 0).copy(nets = mapOf("ORB Fresh|p" to if (d.isBefore(from)) -100.0 else 200.0)) }
        val ba = R.beforeAfter(logs, "ORB Fresh", P, from)
        assertTrue(ba.enough)
        assertEquals(-100.0, ba.meanBefore, 1e-9); assertEquals(200.0, ba.meanAfter, 1e-9)
        assertTrue(R.beforeAfter(logs.takeLast(12), "ORB Fresh", P, from).words().startsWith("too few days"))
    }

    // ---- words ----

    @Test fun theQuestions() {
        for (s in listOf("review my orders", "Jarvis, review my orders", "review today's orders", "order review", "audit my orders"))
            assertEquals(OrderReview.AskKind.REVIEW, R.asked(s)?.kind, s)
        for (s in listOf("what went wrong today", "what went wrong", "what went wrong with my orders", "any flaws today", "flaws in my orders"))
            assertEquals(OrderReview.AskKind.WRONG, R.asked(s)?.kind, s)
        assertEquals(OrderReview.Ask(OrderReview.AskKind.STRATEGY, "ORB"), R.asked("flaws in ORB today"))
        assertEquals(OrderReview.Ask(OrderReview.AskKind.STRATEGY, "Liquidity"), R.asked("any flaws in liquidity"))
        assertEquals(OrderReview.AskKind.SLIPPAGE_COST, R.asked("how much did slippage cost me")?.kind)
        for (s in listOf("what's my slippage", "slippage on orb", "what went wrong with orb today", "show my orders", "cancel my last order",
            "buy nifty", "what went wrong with my bots today", "why was my order rejected"))
            assertNull(R.asked(s), s)
    }

    @Test fun jarvisAnswersShortAndFactual() {
        val d = R.review(OrderReview.Inputs(day, trades = listOf(trade(id = "c", exit = 88.0, stop = 95.0))))
        val a = R.answer(d, OrderReview.Ask(OrderReview.AskKind.REVIEW), emptyList(), day)
        assertTrue(a.startsWith("Order review today: 0 orders, 1 trade, "), a)
        assertTrue("never place, change or cancel" in a)
        assertTrue(R.answer(d, OrderReview.Ask(OrderReview.AskKind.WRONG), emptyList(), day).startsWith("Today: "))
        assertTrue(R.answer(d, OrderReview.Ask(OrderReview.AskKind.STRATEGY, "Pine"), emptyList(), day).startsWith("No Pine orders"))
        assertTrue(R.answer(null, OrderReview.Ask(OrderReview.AskKind.REVIEW), emptyList(), day).startsWith("I have not reviewed"))
        assertTrue(R.answer(d, OrderReview.Ask(OrderReview.AskKind.SLIPPAGE_COST), emptyList(), day).startsWith("No live fills"))
    }

    @Test fun theNoticeOnlyWhenItMattersAndNeverWithRupees() {
        val quiet = R.review(OrderReview.Inputs(day, trades = listOf(trade(exit = 120.0, why = "target"))))
        assertNull(R.notice(quiet, 0, 0))
        val bad = R.review(OrderReview.Inputs(day, sync = listOf(PositionSync.Mismatch(PositionSync.Kind.STOP_MISSING, "X", "ORB", 30, 30))))
        val n = R.notice(bad, 1, 2)!!
        assertFalse("₹" in n || "Rs" in n, n)
        assertTrue(n.startsWith("Order review: 1 serious flaw in today's orders"), n)
    }

    @Test fun theBookReadsBackAndADamagedLineIsSkipped() {
        val d = R.review(OrderReview.Inputs(day, orders = listOf(OrderReview.Order("k|1", L, "ORB", "X", "BUY", 60, 30, "COMPLETE", "a|b\nc", at(10, 0))),
            trades = listOf(trade(best = 130.0, exit = 105.0, why = "profit_lock")), fills = emptyList()))
        val b = OrderReview.Book(R.keep(emptyList(), R.log(d)), d, listOf(OrderReview.Request("k", day, OrderReview.LabAsk("orb", ArmParams.STOP_PTS, 1, "w"), "shadowing", "V3")),
            listOf("a proposal"), day, 123L, setOf("2026-10-12"))
        val back = R.decode(R.encode(b) + "\nF|broken\nL|nope\n")
        assertEquals(b.logs, back.logs)
        assertEquals(d.flaws.size, back.last!!.flaws.size)
        assertEquals(d.summaries, back.last!!.summaries)
        assertEquals(b.requests, back.requests)
        assertEquals(b.proposals, back.proposals)
        assertEquals(day, back.lastRun); assertEquals(123L, back.cutoffMs); assertEquals(setOf("2026-10-12"), back.told)
        assertEquals(OrderReview.Book(), R.decode(null))
        // Bounded: never more than the kept days.
        var logs = emptyList<OrderReview.DayLog>()
        for (k in 0 until 80) logs = R.keep(logs, logOf(day.minusDays(k.toLong()), 1))
        assertEquals(OrderReview.KEEP_DAYS, logs.size)
    }
}
