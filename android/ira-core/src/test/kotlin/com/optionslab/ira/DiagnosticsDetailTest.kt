package com.optionslab.ira

import com.optionslab.ira.LiveCheck.Group
import com.optionslab.ira.LiveCheck.Verdict
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 27 Oct: the detail the 23-27 Oct diagnostics lacked: why each stop-check gap happened, which live-check steps failed, and
 * what the order review's serious flaws were.
 */
class DiagnosticsDetailTest {
    private val day = LocalDate.of(2026, 10, 27)
    private val ist = ZoneId.of("Asia/Kolkata")
    // 10:00:00 IST on the day, as wall-clock ms.
    private val t0 = day.atTime(10, 0).atZone(ist).toInstant().toEpochMilli()

    // ---- stop-check gaps -------------------------------------------------------------------------------------------------

    @Test fun aGapWhileThePhoneSleptIsNamedSo() {
        val d = StopCadence.Drift()
        d.look(t0, true, day, upMs = 1_000_000L)
        d.ended(t0 + 2_000L)
        // 40 s on the wall clock, only 10 s on the awake clock: the phone slept 30 s.
        val g = assertNotNull(d.look(t0 + 40_000L, true, day, upMs = 1_010_000L))
        assertEquals(30_000L, g.sleptMs)
        assertEquals(2_000L, g.prevTookMs)
        assertEquals(StopCadence.Cause.ASLEEP, g.cause)
        assertTrue(StopCadence.words(g).contains("Cause: phone asleep 30 s."), StopCadence.words(g))
        assertTrue(StopCadence.words(g).contains("keep the app open"))
    }

    @Test fun aGapAfterASlowLookIsNamedSo() {
        val d = StopCadence.Drift()
        d.look(t0, true, day, upMs = 0L)
        d.ended(t0 + 21_000L)
        val g = assertNotNull(d.look(t0 + 22_000L, true, day, upMs = 22_000L))
        assertEquals(0L, g.sleptMs)
        assertEquals(StopCadence.Cause.SLOW_LOOK, g.cause)
        assertEquals("the look before took 21 s", g.why())
    }

    @Test fun anAwakePhoneAndAQuickLookMeanTheLaneStartedLate() {
        val d = StopCadence.Drift()
        d.look(t0, true, day, upMs = 0L)
        d.ended(t0 + 1_000L)
        val g = assertNotNull(d.look(t0 + 25_000L, true, day, upMs = 24_000L))
        assertEquals(1_000L, g.sleptMs, "1 s of sleep is under the 5 s mark")
        assertEquals(StopCadence.Cause.LATE, g.cause)
        assertEquals("late start, phone awake, last look 1 s", g.why())
    }

    @Test fun noClocksMeansUnknownAndTheOldWordsStay() {
        val d = StopCadence.Drift()
        d.look(t0, true, day)
        val g = assertNotNull(d.look(t0 + 20_000L, true, day))
        assertEquals(-1L, g.sleptMs); assertEquals(-1L, g.prevTookMs)
        assertEquals(StopCadence.Cause.UNKNOWN, g.cause)
        assertEquals("The stops were looked at 20 s apart while a position was held (the promise is 15 s).", StopCadence.words(g))
    }

    @Test fun anEndBeforeTheLastStartOrAClockThatWentBackIsNotUsed() {
        val d = StopCadence.Drift()
        d.look(t0, true, day, upMs = 50_000L)
        d.ended(t0 - 1_000L)                                       // older than the look: ignored
        val g = assertNotNull(d.look(t0 + 20_000L, true, day, upMs = 10_000L)) // awake clock went back (a reboot)
        assertEquals(-1L, g.sleptMs); assertEquals(-1L, g.prevTookMs)
        assertEquals(StopCadence.Cause.UNKNOWN, g.cause)
    }

    @Test fun theDetailCountsCausesAndNamesTheWorstWithTheirTimes() {
        val d = StopCadence.Drift()
        assertNull(d.detail(day, ist), "no gap: nothing to say")
        var t = t0; var up = 0L
        fun look(wall: Long, awake: Long, took: Long = 1_000L) {
            t += wall; up += awake
            d.look(t, true, day, up); d.ended(t + took)
        }
        look(0L, 0L)
        look(40_000L, 10_000L)            // 10:00:40 asleep 30 s
        look(20_000L, 20_000L, 18_000L)   // 10:01:00 started late (the look before took 1 s); this look takes 18 s
        look(19_000L, 19_000L)            // 10:01:19 slow look (18 s)
        look(15_000L, 15_000L)            // fine
        val line = assertNotNull(d.detail(day, ist))
        assertEquals("Stop-check gap causes: phone asleep 1, slow look 1, late start 1. " +
            "Worst: 10:00:40 40 s (phone asleep 30 s); 10:01:00 20 s (late start, phone awake, last look 1 s); " +
            "10:01:19 19 s (the look before took 18 s).", line)
        assertTrue(line.length <= DiagDay.MAX_TEXT, "${line.length}")
        assertNull(d.detail(day.plusDays(1), ist), "another day reads none")
    }

    @Test fun theLongestDetailStaysUnderTheFilesCut() {
        val d = StopCadence.Drift()
        var t = t0; var up = 0L
        d.look(t, true, day, up)
        // Every cause at its longest words, many times over, with gaps of 100 s or more.
        repeat(StopCadence.KEEP + 10) { i ->
            val wall = 100_000L + i * 1_000L
            t += wall
            up += when (i % 4) { 0 -> 1_000L; 1 -> wall; else -> wall - 1_000L }
            d.ended(t - wall + if (i % 4 == 1) 99_000L else 14_000L)
            d.look(t, true, day, if (i % 4 == 3) -1L else up)
        }
        val line = assertNotNull(d.detail(day, ist))
        assertTrue(line.length <= DiagDay.MAX_TEXT, "${line.length}: $line")
    }

    @Test fun onlyTheLastGapsAreKeptAndANewDayStartsClean() {
        val d = StopCadence.Drift()
        var t = t0
        d.look(t, true, day)
        repeat(StopCadence.KEEP + 5) { t += 17_000L; d.look(t, true, day) }
        assertEquals(StopCadence.KEEP, d.gaps(day).size)
        assertEquals(StopCadence.KEEP + 5, d.today(day).first)
        val line = assertNotNull(d.detail(day, ist))
        assertTrue(line.startsWith("Stop-check gap causes (the last ${StopCadence.KEEP}): unknown ${StopCadence.KEEP}."), line)
        assertTrue(line.length <= DiagDay.MAX_TEXT, "${line.length}")
        d.look(t + 1_000L, true, day.plusDays(1))
        assertTrue(d.gaps(day.plusDays(1)).isEmpty())
    }

    // ---- the live check's failed steps -------------------------------------------------------------------------------------

    private fun step(g: Group, n: String, v: Verdict, why: String = "x") = LiveCheck.Step(g, n, v, why, 10L)

    @Test fun theFailedStepsAreNamed() {
        assertNull(LiveCheck.failedNames(emptyList()))
        assertNull(LiveCheck.failedNames(listOf(step(Group.ACCOUNT, "Funds", Verdict.PASS))))
        val out = listOf(step(Group.ACCOUNT, "Funds", Verdict.PASS), step(Group.STREAM, "First tick", Verdict.FAIL),
            step(Group.ACCOUNT, "Order book", Verdict.FAIL), step(Group.STREAM, "Ticks", Verdict.WARN))
        assertEquals("Price stream · First tick; Zerodha account · Order book", LiveCheck.failedNames(out))
        val many = (1..5).map { step(Group.ACCOUNT, "S$it", Verdict.FAIL) }
        assertEquals("Zerodha account · S1; Zerodha account · S2; Zerodha account · S3; and 2 more", LiveCheck.failedNames(many))
    }

    @Test fun theDiarysLastRunGivesItsFailures() {
        val a = LiveCheck.AREA
        assertEquals(emptyList(), LiveCheck.lastFailures(emptyList()))
        assertEquals(emptyList(), LiveCheck.lastFailures(listOf("10-23 09:20:01 [$a] FAILED Price stream · A: x (1 ms)")),
            "a run with no summary yet is not read")
        val lines = listOf(
            "10-22 09:20:01 [$a] FAILED Price stream · Old: gone (1 ms)",
            "10-22 09:21:00 [$a] ${LiveCheck.SUMMARY}20 of 21 passed, 1 failed",
            "10-23 09:20:01 [$a] ok Zerodha account · Funds: read (12 ms)",
            "10-23 09:20:02 [watch] something else",
            "10-23 09:20:03 [$a] FAILED Paper · Round trip: no fill in 10 s (10004 ms)",
            "10-23 09:21:00 [$a] ${LiveCheck.SUMMARY}20 of 21 passed, 1 failed",
        )
        assertEquals(listOf("Paper · Round trip: no fill in 10 s (10004 ms)"), LiveCheck.lastFailures(lines))
        // A clean run after it: nothing failed last time.
        assertEquals(emptyList(), LiveCheck.lastFailures(lines + "10-24 09:21:00 [$a] ${LiveCheck.SUMMARY}21 of 21 passed"))
    }

    // ---- the order review's serious flaws ---------------------------------------------------------------------------------

    @Test fun theSeriousFlawsAreSaidWithoutTheirIdsOrRupees() {
        fun flaw(sev: OrderReview.Severity, strategy: String, cat: OrderReview.Category, ev: String) =
            OrderReview.Flaw("ref-251027000123", strategy, OrderReview.Venue.PAPER, cat, sev, ev, 1_234.0)
        assertNull(OrderReview.seriousLine(emptyList()))
        assertNull(OrderReview.seriousLine(listOf(flaw(OrderReview.Severity.WARN, "ORB", OrderReview.Category.SLIPPAGE, "a"))))
        val l = assertNotNull(OrderReview.seriousLine(listOf(
            flaw(OrderReview.Severity.HIGH, "Liquidity", OrderReview.Category.REJECTED, "margin short"),
            flaw(OrderReview.Severity.INFO, "ORB", OrderReview.Category.SLIPPAGE, "b"),
            flaw(OrderReview.Severity.HIGH, "Hero", OrderReview.Category.PARTIAL, "1 of 2 lots"),
        )))
        assertEquals("Serious flaws: Liquidity (paper) · order rejected: margin short; Hero (paper) · partly filled: 1 of 2 lots", l)
        assertTrue("ref-" !in l && "1234" !in l && "1,234" !in l)
        val many = (1..5).map { flaw(OrderReview.Severity.HIGH, "A$it", OrderReview.Category.REJECTED, "e") }
        assertTrue(OrderReview.seriousLine(many)!!.endsWith("; and 2 more"))
    }
}
