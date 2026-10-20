package com.optionslab.ira

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The detectors added after 12 Oct ("find out why all my agents were not able to find these issues"), each against the day's
 * own numbers: the open-problems board, the diary's errors, the app's health, the session coverage, the policy check, the
 * paper-only period, the money labels and Jarvis's research-contradicting lessons.
 */
class MissedChecksTest {
    private val day = LocalDate.of(2026, 10, 12)

    // ---- the open-problems board ----

    @Test fun problemsAreKeyedUpdatedClearedAndExpire() {
        val P = OpenProblems
        var b = P.report(OpenProblems.Board(), OpenProblems.Problem("a", "Order review", OpenProblems.Severity.WARN, "ORB churn"), 1_000L)
        b = P.report(b, OpenProblems.Problem("b", "App health", OpenProblems.Severity.SERIOUS, "stall 3.9 s", "move it off the main thread"), 2_000L)
        b = P.report(b, OpenProblems.Problem("a", "Order review", OpenProblems.Severity.WARN, "ORB churn again"), 3_000L)
        val open = P.open(b, 3_000L)
        assertEquals(listOf("b", "a"), open.map { it.key })                 // serious first
        assertEquals(1_000L, open[1].firstMs); assertEquals(2, open[1].times); assertEquals("ORB churn again", open[1].text)
        assertEquals("Problems found: 1 serious, 1 warning", P.head(open))
        assertTrue(P.lines(open).first().startsWith("SERIOUS · App health: stall 3.9 s · Fix: move it"))
        assertEquals(listOf("a"), P.open(P.clear(b, "b"), 3_000L).map { it.key })
        assertEquals(listOf("b"), P.open(P.clearOthers(b, "a", emptySet()), 3_000L).map { it.key })
        assertTrue(P.open(b, 3_000L + P.OPEN_MS + 1).isEmpty())
        assertNull(P.head(emptyList()))
        assertEquals(b, P.decode(P.encode(b)))
        assertEquals(OpenProblems.Board(), P.decode("garbage|x\n"))
    }

    // ---- the diary's errors ----

    @Test fun errorKindsAndUnreadableFailures() {
        val D = DiagErrors
        assertEquals("missing required field: quantity", D.kindOf("Missing required field: quantity"))
        assertEquals("no open paper position in <symbol>", D.kindOf("No open paper position in SILVERM27OCT26230000CE"))
        assertEquals("Jarvis words" to "ij1", D.unreadable("a check failed in Jarvis words: ij1 (the watch goes on)"))
        assertNull(D.unreadable("a check failed in Jarvis words (at news, timed out): Supervisor\$TimedOut (the watch goes on)"))
        assertNull(D.unreadable("a check failed in ORB arms: IOException (the watch goes on)"))
        val t = DiagErrors.Tally()
        repeat(3) { t.add(day, "error", "Missing required field: quantity") }
        t.add(day, "watch", "a check failed in Jarvis words: ij1 (the watch goes on)")
        t.add(day, "info", "fine")
        assertEquals(mapOf("missing required field: quantity" to 3), t.of(day).errors)
        assertEquals(mapOf("Jarvis words: ij1" to 1), t.of(day).unreadable)
        // 20 Oct: a notice or a warning is counted apart, never as an error (an old "[error]" line sorted by its words).
        t.add(day, "error", "Good morning Boss · 3 things need you")
        t.add(day, "notice", "That passphrase is too easy to guess")
        t.add(day, "error", "Short put is in the money: NIFTY 25000 is below the 25100 strike")
        t.add(day, "warning", "Live price stream dropped (the phone's network changed); reconnecting")
        assertEquals(mapOf("missing required field: quantity" to 3), t.of(day).errors)
        assertEquals(2, t.of(day).notices.values.sum())
        assertEquals(2, t.of(day).warnings.values.sum())
        assertEquals(DiagErrors.Severity.ERROR, D.classify("Positions not protected: 1 open"))
        assertEquals(DiagErrors.Severity.WARNING, D.classify("  Daily loss limit reached: only exits today"))
        assertEquals(DiagErrors.Severity.NOTICE, D.classify("Day report · Mon 19 Oct"))
        assertNull(D.severityOf("info", "Good morning"))
        // Two counts of one day: kind by kind the larger; another day's is left out.
        val a = DiagErrors.Day(day, mapOf("x" to 2), warnings = mapOf("w" to 1))
        val b = DiagErrors.Day(day, mapOf("x" to 1, "y" to 4), notices = mapOf("n" to 3))
        assertEquals(DiagErrors.Day(day, mapOf("x" to 2, "y" to 4), emptyMap(), mapOf("w" to 1), mapOf("n" to 3)), DiagErrors.merge(a, b))
        assertEquals(a, DiagErrors.merge(a, b.copy(day = day.plusDays(1))))
        assertEquals(b, DiagErrors.merge(null, b))
        assertNull(DiagErrors.merge(null, null))
        // A new day starts afresh.
        t.add(day.plusDays(1), "error", "x")
        assertTrue(t.of(day).errors.isEmpty())
    }

    // ---- the app's health ----

    @Test fun healthFindingsFromTheDaysNumbers() {
        val H = HealthChecks
        val d = HealthBook.Day(day, stalls = 2, longestMs = 3_900, samples = listOf(
            HealthBook.Stall(1L, 800, "Choreographer.doFrame"), HealthBook.Stall(2L, 3_900, "BinderProxy.transactNative in AppModel.switchArm")))
        val s = assertNotNull(H.stalls(d))
        assertEquals(OpenProblems.Severity.SERIOUS, s.severity)
        assertTrue("3.9 s in BinderProxy.transactNative in AppModel.switchArm" in s.text, s.text)
        assertTrue("Android's system" in s.text)
        assertNull(H.stalls(d.copy(samples = listOf(HealthBook.Stall(1L, 800, "x")), longestMs = 800)))
        // The day's longest kept even when its place rolled off.
        assertNotNull(H.stalls(d.copy(samples = emptyList(), longestMs = 1_200)))

        assertNotNull(H.appStart(2_000, "Jarvis's memory 900 ms")).let { assertTrue("2.0 s" in it.text && "Jarvis's memory" in it.text) }
        assertNull(H.appStart(1_200, null))

        // 16:24, 24 minutes into the night: all 10 minutes gone.
        val b = assertNotNull(H.budget("2026-10-12 night", 10 * 60_000L, BudgetRule.NIGHT_CPU_MS, LocalDateTime.of(2026, 10, 12, 16, 24)))
        assertEquals(OpenProblems.Severity.SERIOUS, b.severity)
        assertTrue("100% spent by 16:24" in b.text, b.text)
        // Half spent by 11:00 of the day's budget: told; at 15:50: the day is nearly over, not told; under half: never.
        assertNotNull(H.budget("2026-10-12 day", 6 * 60_000L, BudgetRule.NIGHT_CPU_MS, LocalDateTime.of(2026, 10, 12, 11, 0)))
        assertNull(H.budget("2026-10-12 day", 6 * 60_000L, BudgetRule.NIGHT_CPU_MS, LocalDateTime.of(2026, 10, 12, 15, 50)))
        assertNull(H.budget("2026-10-12 night", 4 * 60_000L, BudgetRule.NIGHT_CPU_MS, LocalDateTime.of(2026, 10, 12, 16, 24)))
        assertNull(H.budget("2026-10-12 night", 9 * 60_000L, BudgetRule.NIGHT_CPU_MS, LocalDateTime.of(2026, 10, 12, 23, 0)))

        assertNotNull(H.paperOrders(3_211, 38)).let { assertTrue("3.2 s" in it.text && "38 orders" in it.text) }
        assertNull(H.paperOrders(400, 38)); assertNull(H.paperOrders(3_000, 2)); assertNull(H.paperOrders(null, 0))

        assertNotNull(H.unreadable(mapOf("Jarvis words: ij1" to 2))).let { assertTrue("ij1" in it.text && "-keepnames" in it.fix) }
        assertNull(H.unreadable(emptyMap()))
    }

    // ---- session coverage ----

    @Test fun anOpenMarketWithArmsAndNoWatchIsAGap() {
        val C = SessionCoverage
        // 12 Oct 16:30: NSE closed, MCX open with three arms armed, the watch ended.
        val markets = listOf(SessionCoverage.Market("NSE", open = false), SessionCoverage.Market("MCX", open = true, armed = listOf("Silver morning call", "Gold trend", "Silver evening")))
        val g = C.gaps(markets, watchAlive = false, streamUp = null).single()
        assertEquals("coverage:MCX:watch", g.key)
        assertTrue("MCX is open with 3 armed" in g.text && "not running" in g.text, g.text)
        // The watch up but the stream down (Zerodha logged in): a stream gap.
        assertEquals("coverage:MCX:stream", C.gaps(markets, watchAlive = true, streamUp = false).single().key)
        // All well, or nothing to watch, or the market shut: no gap.
        assertTrue(C.gaps(markets, watchAlive = true, streamUp = true).isEmpty())
        assertTrue(C.gaps(listOf(SessionCoverage.Market("MCX", open = true)), watchAlive = false, streamUp = null).isEmpty())
        assertTrue(C.gaps(listOf(SessionCoverage.Market("MCX", open = false, held = 1)), watchAlive = false, streamUp = null).isEmpty())
        assertTrue("1 position or order open" in C.gaps(listOf(SessionCoverage.Market("NSE", open = true, held = 1)), false, null).single().text)
    }

    /**
     * 16 Oct (Boss's diagnostics: "MCX is open with 2 armed, but the market watch is not running" at 09:01 and 17:56): the
     * dead-man check that starts the watch judged the gap from before that start. A gap is now told only once it has lasted
     * the grace (the watch's start takes seconds; Boss's restarts about 16 s).
     */
    @Test fun aCoverageGapIsToldOnlyWhenItOutlastsTheGrace() {
        val t = SessionCoverage.GapTimer()
        val at = 1_000_000L
        // 09:01: MCX opened, the arms armed, the watch not yet up - this very check starts it. Not told.
        assertFalse(t.report(true, at))
        assertTrue(t.pending())
        // The watch came up within seconds: the next look sees no gap, and the timer is cleared.
        assertFalse(t.report(false, at + 20_000))
        assertFalse(t.pending())
        // A real gap: still there when the grace is over - told (and every look after, while it lasts).
        val r = SessionCoverage.GapTimer()
        assertFalse(r.report(true, at))
        assertFalse(r.report(true, at + SessionCoverage.GRACE_MS - 1))
        assertTrue(r.report(true, at + SessionCoverage.GRACE_MS))
        assertTrue(r.report(true, at + SessionCoverage.GRACE_MS + 300_000))
        // A clock set back never makes an old gap look new and told at once: its grace starts again.
        val c = SessionCoverage.GapTimer(graceMs = 1_000)
        assertFalse(c.report(true, 5_000))
        assertFalse(c.report(true, 1_000))
        assertTrue(c.report(true, 2_000))
        assertTrue(SessionCoverage.GRACE_MS >= 60_000, "longer than a restart, which took about 16 s")
    }

    /** Review, 16 Oct: a watch that keeps dying and is brought back within the grace each time is still told. */
    @Test fun aGapThatKeepsComingBackIsToldAtOnce() {
        val t = SessionCoverage.GapTimer()
        val at = 1_000_000L
        assertFalse(t.report(true, at))
        assertFalse(t.report(false, at + 20_000))
        // The watch ended again 5 minutes later: within 30 minutes of the last gap - told at once.
        assertTrue(t.report(true, at + 5 * 60_000L))
        assertFalse(t.report(false, at + 5 * 60_000L + 20_000))
        // Long after the last one: a fresh gap waits for its grace again.
        assertFalse(t.report(true, at + 5 * 60_000L + SessionCoverage.REPEAT_MS + 1))
    }

    // ---- the policy check ----

    @Test fun armedAgainstAStandingDecisionIsAViolation() {
        val v = PolicyCheck.check(listOf(
            PolicyCheck.Armed("breakdown below the last 20 candles' low", "SENSEX", kind = "Pine script"),
            PolicyCheck.Armed("three red candles in a row", "FINNIFTY", kind = "Pine script"),
            PolicyCheck.Armed("ORB"),
            PolicyCheck.Armed("Liquidity 15+5"),
            PolicyCheck.Armed("alerts only on SENSEX", "SENSEX", trades = false),
        ))
        assertEquals(3, v.size)
        assertTrue(v[0].text.contains("SENSEX on paper") && "BSE" in v[0].text, v[0].text)
        assertEquals(OpenProblems.Severity.SERIOUS, v[0].severity)
        assertTrue("research X1" in v[1].text)
        assertTrue(v[2].line().startsWith("Policy: \"ORB\" is armed on paper, but it lost in both tested years"), v[2].line())
        assertEquals(OpenProblems.Severity.WARN, v[2].severity)
        assertEquals(OpenProblems.Severity.SERIOUS, PolicyCheck.check(listOf(PolicyCheck.Armed("ORB", live = true))).single().severity)
        assertTrue(PolicyCheck.check(listOf(PolicyCheck.Armed("ORB", trades = false))).isEmpty())
    }

    // ---- the paper-only period ----

    @Test fun thePaperOnlyPeriodOnlyLowersRiskWithoutThePin() {
        val Q = PaperOnlyPeriod
        val p = PaperOnlyPeriod.Period(day.plusDays(9), "10 days on paper after the 12 Oct review", day)
        assertTrue(Q.active(p, day)); assertTrue(Q.active(p, day.plusDays(9))); assertFalse(Q.active(p, day.plusDays(10))); assertFalse(Q.active(null, day))
        assertFalse(Q.needsPin(null, p, day))                                       // setting one
        assertFalse(Q.needsPin(p, p.copy(until = p.until.plusDays(3)), day))         // a later end
        assertTrue(Q.needsPin(p, p.copy(until = p.until.minusDays(3)), day))         // an earlier end
        assertTrue(Q.needsPin(p, null, day))                                         // ending it
        assertFalse(Q.needsPin(p, null, day.plusDays(10)))                           // over already
        assertNull(Q.invalid(day.plusDays(9), day)); assertNotNull(Q.invalid(day.minusDays(1), day)); assertNotNull(Q.invalid(day.plusDays(91), day))
        assertTrue(Q.liveAsk(p).startsWith("You set a paper-only period until Wed 21 Oct: \"10 days on paper"), Q.liveAsk(p))
        assertTrue("Exits and square-offs are never blocked" in Q.liveAsk(p))
        assertEquals("Paper only until Wed 21 Oct (10 days left): 10 days on paper after the 12 Oct review", Q.status(p, day))
        assertEquals(p, Q.decode(Q.encode(p))); assertNull(Q.decode(null)); assertNull(Q.decode("x"))
    }

    // ---- money labels ----

    @Test fun aMoneyFigureWithoutItsAccountIsFound() {
        val src = """
            val a = String.format(Locale.ENGLISH, "P&L  Rs %+,.0f", pnl)
            val b = String.format(Locale.ENGLISH, "Zerodha P&L  Rs %+,.0f", pnl)
            val title = "Day report · ${'$'}{d}" + " · ${'$'}{rs(total)}"
            Text("Capital", style = s)
            Text("Capital", style = s)   // money-label: whose follows on the next line
            lines += "Paper (practice): ${'$'}{rs(x)}"
            // "P&L ${'$'}{rs(x)}" in a comment
            val n = rs(x)
        """.trimIndent()
        assertEquals(listOf(1, 3, 4), MoneyLabels.unlabelled(src).map { it.first })
    }

    /** The app's money screens (Home's card, the day report, the widget, Jarvis's funds): every figure says whose money it is. */
    @Test fun theAppsMoneyScreensNameTheirAccount() {
        val root = listOf(File("../app/src/main/java/com/optionslab/app"), File("android/app/src/main/java/com/optionslab/app")).firstOrNull { it.isDirectory }
            ?: return     // the app's sources are not beside this module (a lone ira-core checkout)
        val files = listOf("ui/screens/AlmanacScreen.kt", "work/DailyReports.kt", "widget/IraWidget.kt", "ira/IraAccount.kt")
        val bad = files.flatMap { f -> MoneyLabels.unlabelled(File(root, f).readText()).map { "$f:${it.first}: ${it.second}" } }
        assertTrue(bad.isEmpty(), "money figures with no account named:\n" + bad.joinToString("\n"))
    }

    /** The paper-only period is set only from its Settings card: never by voice, a backup, Autopilot or another agent. */
    @Test fun thePaperOnlyPeriodHasOneDoor() {
        val root = listOf(File("../app/src/main/java/com/optionslab/app"), File("android/app/src/main/java/com/optionslab/app")).firstOrNull { it.isDirectory } ?: return
        val callers = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> Regex("PaperOnly\\.(set|end)\\(").containsMatchIn(f.readText()) }.map { it.name }.toSet()
        assertEquals(setOf("PaperOnlyCard.kt"), callers)
        // Its key is a private setting: a backup never carries it.
        assertFalse(Upkeep.carried("k.paperOnly"))
    }

    // ---- Jarvis's lessons against the research ----

    @Test fun aLessonThatOrbMakesMoneyContradictsTheResearchAndIsNotTrusted() {
        val d0 = LocalDate.of(2026, 9, 1)
        val trips = (0 until 36).map { k ->
            val d = d0.plusDays(k.toLong())
            Insights.Trip("X", d.atTime(10, 0), d.atTime(10, 20), 95.0, "ORB")
        } + (0 until 36).map { k ->
            val d = d0.plusDays(k.toLong())
            Insights.Trip("Y", d.atTime(11, 0), d.atTime(11, 20), 95.0, "Liquidity 15+5")
        }
        val pass = AgentLessons.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(36), trips = trips))
        val orb = pass.book.lessons.first { it.id == AgentLessons.norm("strat:ORB") }
        val liq = pass.book.lessons.first { it.id == AgentLessons.norm("strat:Liquidity 15+5") }
        assertEquals(AgentLessons.Confidence.WEAK, orb.confidence)
        assertTrue(AgentLessons.said(orb).endsWith("; contradicts research - not trusted)"), AgentLessons.said(orb))
        assertTrue("lost in both tested years" in assertNotNull(AgentLessons.contradiction(orb)))
        assertNull(AgentLessons.contradiction(liq))
        assertTrue(liq.confidence != AgentLessons.Confidence.WEAK)
        assertTrue(pass.proposals.none { it.lessonId == orb.id })
    }

    @Test fun lessonsWeighCostsTheBookDidNotBook() {
        val d0 = LocalDate.of(2026, 9, 1)
        // +Rs 20 a trade as booked, Rs 30 of unbooked paper spread each: after all costs it loses.
        val trips = (0 until 12).map { k ->
            val d = d0.plusDays(k.toLong())
            Insights.Trip("X", d.atTime(10, 0), d.atTime(10, 20), 20.0, "Pine one", unbooked = 30.0)
        }
        val pass = AgentLessons.pass(AgentLessons.Book(), AgentLessons.Inputs(d0.plusDays(12), trips = trips))
        val l = pass.book.lessons.first { it.id == AgentLessons.norm("strat:Pine one") }
        assertEquals(-1, l.sign)
        assertEquals(-120.0, AgentLessons.evidence(l).rs, 1e-6)
        // The paper spread a trip from before 08 Oct did not pay: a half-spread on each fill (5 bps, at least a tick); none after.
        assertEquals((0.15 + 0.15) * 30, Insights.unbookedSpread(LocalDate.of(2026, 10, 1), 300.0, 300.0, 30), 1e-9)
        assertEquals((0.05 + 0.05) * 30, Insights.unbookedSpread(LocalDate.of(2026, 10, 1), 20.0, 20.0, 30), 1e-9)
        assertEquals(0.0, Insights.unbookedSpread(LocalDate.of(2026, 10, 8), 300.0, 300.0, 30), 1e-9)
    }

    @Test fun aBinderStallKeepsItsSystemFrameSoTheFixIsNamed() {
        assertEquals("BinderProxy.transactNative in AppModel.switchArm", HealthBook.place("BinderProxy.transactNative in AppModel.switchArm"))
        assertEquals("BrainSection.invoke", HealthBook.place("Choreographer.doFrame in BrainSection.invoke"))
        val b = HealthBook.stall(HealthBook.Book(), 1_791_800_000_000L, 3_900, "BinderProxy.transactNative in AppModel.switchArm")
        val f = assertNotNull(HealthChecks.stalls(b.day(HealthBook.dayOf(1_791_800_000_000L))))
        assertTrue(f.text.contains("BinderProxy.transactNative") && f.text.contains("system"), f.text)
    }
}
