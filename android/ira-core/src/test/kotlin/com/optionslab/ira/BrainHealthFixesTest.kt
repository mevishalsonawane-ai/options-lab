package com.optionslab.ira

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The line-by-line review's G8 fixes in ira-core: the review's day guard, today's incidents, ANR traces, the budget, FOMC. */
class BrainHealthFixesTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun ms(day: LocalDate, h: Int, m: Int) = day.atTime(h, m).atZone(ist).toInstant().toEpochMilli()

    // ---- G8-1 / G8-5: the review's day guard ------------------------------------------------------------------------------

    @Test fun aPassThatFailsBeforeItsReadsLeavesTheDayToTheNextTry() {
        val g = DriftReview.DayOnce()
        assertTrue(g.claim("2026-10-14"))
        g.release()                                   // cut at its time limit before done(): not reviewed
        assertTrue(g.claim("2026-10-14"), "the next try runs")
        g.done("2026-10-14"); g.release()
        assertFalse(g.claim("2026-10-14"), "done: once a day")
        assertTrue(g.claim("2026-10-15"))
    }

    @Test fun twoCallersAtOnceNeverBothReview() {
        val g = DriftReview.DayOnce()
        val inside = AtomicInteger(0)
        val start = CountDownLatch(1)
        val ts = (1..8).map {
            thread {
                start.await()
                repeat(200) {
                    if (g.claim("2026-10-14")) {
                        inside.incrementAndGet()
                        g.done("2026-10-14"); g.release()
                    }
                }
            }
        }
        start.countDown(); ts.forEach { it.join(10_000) }
        assertEquals(1, inside.get(), "one review a day however many callers race")
    }

    // ---- G8-3: the relay switched off clears its incident -----------------------------------------------------------------

    @Test fun theRelaySwitchedOffNoLongerRefusesLiveEntries() {
        val m = SelfHeal.Monitor { 1_000L }
        m.relay(false); m.relay(false)
        assertTrue(m.fallbacks().blockLiveEntries)
        m.relay(true, enabled = false)
        assertFalse(m.fallbacks().blockLiveEntries)
    }

    // ---- G8-8: today's incidents only ------------------------------------------------------------------------------------

    @Test fun yesterdaysIncidentIsNotListedAsTodays() {
        var now = ms(LocalDate.of(2026, 10, 13), 14, 2)
        val m = SelfHeal.Monitor { now }
        m.relay(false); m.relay(false)
        assertEquals(1, m.evaluate().count { it.started })
        m.relay(true)
        m.evaluate(); now += SelfHeal.RECOVER_MS + 1_000; assertEquals(1, m.evaluate().count { !it.started })
        assertEquals(1, m.past().size)
        assertTrue(m.pastSince(ms(LocalDate.of(2026, 10, 14), 0, 0)).isEmpty(), "yesterday's incident is not today's")
        assertEquals(1, m.pastSince(ms(LocalDate.of(2026, 10, 13), 0, 0)).size)
    }

    // ---- G8-9: an ANR trace keeps the main thread's stack ------------------------------------------------------------------

    @Test fun anAnrTraceStartsAtTheMainThread() {
        val head = listOf("----- pid 1234 at 2026-10-12 10:01:02 -----", "Cmd line: com.optionslab.app", "Build fingerprint: 'google/x'") +
            (1..120).map { "Histogram bucket $it: ${it * 3}" } + listOf("DALVIK THREADS (42):")
        val main = listOf("\"main\" prio=5 tid=1 Blocked", "  at com.optionslab.app.data.SecurePrefs.getString(SecurePrefs.kt:120)",
            "  at com.optionslab.app.ui.Home.invoke(Home.kt:44)")
        val t = HealthExport.trimTrace((head + main).joinToString("\n"))
        assertTrue(t.lines().first().startsWith("\"main\""), t.lines().first())
        assertTrue(t.contains("SecurePrefs.getString"))
        // A crash's stack (no thread dump) is kept from its top, as before.
        val crash = "java.lang.IllegalStateException: token abc\n  at a.b.C.d(C.kt:1)"
        assertEquals("java.lang.IllegalStateException: ‹message left out›\n  at a.b.C.d(C.kt:1)", HealthExport.trimTrace(crash))
    }

    // ---- G8-10: the day's own budget, CPU counted once ---------------------------------------------------------------------

    @Test fun theDaysWorkSpendsTheDaysBudgetNotTheNights() {
        val d = LocalDate.of(2026, 10, 13)
        assertEquals("2026-10-13 day", BudgetRule.budgetOf(d.atTime(15, 50)))
        assertEquals("2026-10-13 night", BudgetRule.budgetOf(d.atTime(16, 0)))
        assertEquals("2026-10-13 night", BudgetRule.budgetOf(LocalDateTime.of(2026, 10, 14, 7, 59)))
        assertEquals("2026-10-14 day", BudgetRule.budgetOf(LocalDateTime.of(2026, 10, 14, 8, 0)))
        assertTrue(BudgetRule.budgetOf(d.atTime(15, 50)) != BudgetRule.budgetOf(d.atTime(21, 0)))
    }

    @Test fun twoJobsRunningTogetherCountTheProcesssCpuOnce() {
        val m = BudgetRule.CpuMeter()
        m.ask(BudgetRule.Job.FORGE, cpuMs = 1_000, wallMs = 0)
        m.ask(BudgetRule.Job.LEARNING, cpuMs = 1_000, wallMs = 0)
        m.ask(BudgetRule.Job.FORGE, cpuMs = 61_000, wallMs = 60_000)
        assertEquals(60_000L, m.ask(BudgetRule.Job.LEARNING, cpuMs = 61_000, wallMs = 60_000), "one minute of CPU is one minute")
        m.done(BudgetRule.Job.FORGE, 71_000)
        m.done(BudgetRule.Job.LEARNING, 71_000)
        assertEquals(70_000L, m.spentMs)
        // A gap between runs is not counted; a new run counts from its own ask.
        m.ask(BudgetRule.Job.HISTORY, cpuMs = 200_000, wallMs = 1_000_000)
        m.ask(BudgetRule.Job.HISTORY, cpuMs = 205_000, wallMs = 1_010_000)
        assertEquals(75_000L, m.spentMs)
        m.reset(); assertEquals(0L, m.spentMs)
    }

    // ---- G8-11: the Fed's window tonight -----------------------------------------------------------------------------------

    @Test fun theFedsDecisionTonightIsAWindowToday() {
        val today = LocalDate.of(2026, 9, 16)                       // the decision 14:00 New York = 23:30 IST (summer time)
        val events = listOf(LocalDate.of(2026, 9, 17) to "US Fed decision overnight (FOMC)")
        val w = MarketBrain.windowsFor(today, events, mcx = false)
        val fed = w.single { it.name == "US Fed decision" }
        assertTrue(fed.active(ms(today, 23, 28)), "active on decision night, MCX still open")
        // Tomorrow's other events are tomorrow's, not tonight's.
        assertTrue(MarketBrain.windowsFor(today, listOf(LocalDate.of(2026, 9, 17) to "RBI policy"), mcx = false).isEmpty())
        // Today's own events as before.
        assertEquals(listOf("RBI policy"), MarketBrain.windowsFor(today, listOf(today to "RBI policy"), mcx = false).map { it.name })
    }
}
