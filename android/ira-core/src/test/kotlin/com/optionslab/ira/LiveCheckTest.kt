package com.optionslab.ira

import com.optionslab.ira.LiveCheck.Group
import com.optionslab.ira.LiveCheck.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveCheckTest {
    /** A clock each step moves on by what the step says it took (virtual time: no wall-clock budget can flake). */
    private class Clock { var now = 1_000L; fun read() = now }

    private fun runner(clock: Clock, seen: MutableList<LiveCheck.Step> = ArrayList()) =
        LiveCheck.Runner(clock = clock::read, reason = { it.message ?: it.javaClass.simpleName }, onStep = { seen += it })

    @Test fun passSlowFailTimeoutAndClosed() = runBlocking {
        val c = Clock()
        val seen = ArrayList<LiveCheck.Step>()
        val r = runner(c, seen)
        val fast = r.step(Group.ACCOUNT, "Funds", 1_500) { c.now += 120; LiveCheck.pass("read") }
        val slow = r.step(Group.ACCOUNT, "Positions", 1_500) { c.now += 1_501; LiveCheck.pass("2 open") }
        val bad = r.step(Group.ACCOUNT, "Order book", 1_500) { c.now += 40; error("Gateway timed out") }
        val hung = r.step(Group.STREAM, "First tick: NIFTY 50", 2_000, timeoutMs = 50) { awaitCancellation() }
        val shut = r.step(Group.STREAM, "Ticks per second", 2_000) { LiveCheck.closed() }
        val skip = r.step(Group.POSITIONS, "Reconnect", 3_000) { LiveCheck.skipped("a position is open") }
        val own = r.step(Group.CHAIN, "ATM", 1_000) { c.now += 5; LiveCheck.fail("no NIFTY options listed") }

        assertEquals(Verdict.PASS, fast.verdict); assertEquals(120, fast.ms); assertEquals("read", fast.detail)
        assertEquals(Verdict.WARN, slow.verdict); assertEquals(1_501, slow.ms)
        assertEquals("2 open · slow (budget 1500 ms)", slow.detail)
        assertEquals(Verdict.FAIL, bad.verdict); assertEquals("Gateway timed out", bad.detail); assertFalse(bad.ok)
        assertEquals(Verdict.FAIL, hung.verdict); assertEquals("no answer in 50 ms", hung.detail)
        assertEquals(Verdict.CLOSED, shut.verdict); assertEquals("market closed", shut.detail); assertTrue(shut.ok)
        assertEquals(Verdict.SKIPPED, skip.verdict); assertTrue(skip.ok)
        assertEquals(Verdict.FAIL, own.verdict)
        assertEquals(r.steps, seen, "every step is handed on as it ends, in order")
        assertEquals(listOf("✓", "!", "✗", "✗", "–", "–", "✗"), seen.map { it.mark })
    }

    @Test fun aWholeSecondLimitIsSaidInSeconds() = runBlocking {
        val s = runner(Clock()).step(Group.STREAM, "Connect", 3_000, timeoutMs = 1_000) { awaitCancellation() }
        assertEquals("no answer in 1 s", s.detail)
    }

    @Test fun aTimeLimitInsideAStepIsThatStepsFailureNotTheRunsEnd() = runBlocking {
        val r = runner(Clock())
        val s = r.step(Group.FEEDS, "Feed", 1_000) { withTimeout(10) { awaitCancellation() } }
        val next = r.step(Group.FEEDS, "Next", 1_000) { LiveCheck.pass("ran") }
        assertEquals(Verdict.FAIL, s.verdict); assertEquals("timed out", s.detail)
        assertEquals(Verdict.PASS, next.verdict)
    }

    @Test fun cancellingTheRunIsPassedOn() {
        assertFailsWith<CancellationException> {
            runBlocking {
                val r = runner(Clock())
                r.step(Group.FEEDS, "Feed", 1_000) { throw CancellationException("the screen closed") }
            }
        }
        // And a run cancelled from outside stops at once, without a step being recorded for it.
        val seen = ArrayList<LiveCheck.Step>()
        runBlocking {
            val job = launch { runner(Clock(), seen).step(Group.FEEDS, "Feed", 1_000) { awaitCancellation() } }
            kotlinx.coroutines.yield()
            job.cancel(); job.join()
        }
        assertTrue(seen.isEmpty())
    }

    @Test fun aReasonThatThrowsStillGivesAFailure() = runBlocking {
        val r = LiveCheck.Runner(clock = Clock()::read, reason = { error("boom") })
        val s = r.step(Group.FEEDS, "Feed", 1_000) { throw java.io.IOException("x") }
        assertEquals("IOException", s.detail)
    }

    @Test fun cleanKeepsNoAmountTokenOrOrderId() {
        val kite = "Insufficient funds. Required margin is 95417.50 but available margin is 1234.25. Order 250926000000123 rejected; token abcdefghijklmnopqrstuvwxyz123456 Rs 4,000 and ₹12,500.75"
        val c = LiveCheck.clean(kite)
        for (bad in listOf("95417", "1234", "250926000000123", "abcdefghijklmnopqrstuvwxyz", "4,000", "12,500")) assertFalse(c.contains(bad), "$bad in $c")
        assertTrue(c.startsWith("Insufficient funds. Required margin is # but available margin is #"), c)
        // Whole-number amounts after a money word too (Zerodha's RMS words them without paise).
        val rms = LiveCheck.clean("Insufficient funds. Required margin is 52345 but available margin is 1,200; cash: 900")
        for (bad in listOf("52345", "1,200", "900")) assertFalse(rms.contains(bad), "$bad in $rms")
        assertEquals("Insufficient funds. Required margin is # but available margin is #; cash: #", rms)
        // A count that is not an amount stays.
        assertEquals("3 orders still resting", LiveCheck.clean("3 orders still resting"))
        assertEquals("failed", LiveCheck.clean("   "))
        assertEquals(160, LiveCheck.clean("x ".repeat(200)).length)
        assertEquals("Gateway timed out", LiveCheck.clean("Gateway  timed\nout"))
    }

    @Test fun rates() {
        assertEquals(4.2, LiveCheck.perSecond(42, 10_000), 1e-9)
        assertEquals(0.0, LiveCheck.perSecond(5, 0))
        assertEquals(Verdict.PASS, LiveCheck.rate(42, 10_000).verdict)
        assertEquals("4.2 ticks a second over 10 s (42 ticks)", LiveCheck.rate(42, 10_000).detail)
        assertEquals(Verdict.PASS, LiveCheck.rate(10, 10_000).verdict)
        assertEquals(Verdict.WARN, LiveCheck.rate(5, 10_000).verdict)
        assertEquals(Verdict.FAIL, LiveCheck.rate(2, 10_000).verdict)
        assertEquals("no ticks in 10 s", LiveCheck.rate(0, 10_000).detail)
    }

    @Test fun gaps() {
        assertEquals(10_000, LiveCheck.largestGap(emptyList(), 0, 10_000), "nothing at all: the whole window")
        assertEquals(400, LiveCheck.largestGap(listOf(400L, 400L, 600L, 900L, 1_200L), 0, 1_500), "repeats ignored, edges counted")
        assertEquals(7_000, LiveCheck.largestGap(listOf(1_000L, 2_000L, 3_000L), 0, 10_000), "a stop half way shows the silence to the end")
        assertEquals(500, LiveCheck.largestGap(listOf(-5_000L, 500L, 900L, 20_000L), 0, 1_000), "outside the window ignored")
        assertEquals(Verdict.PASS, LiveCheck.gap(1_100, 50).verdict)
        assertEquals("largest gap 1100 ms (looked at every 50 ms)", LiveCheck.gap(1_100, 50).detail)
        assertEquals(Verdict.WARN, LiveCheck.gap(2_001, 50).verdict)
        assertEquals(Verdict.WARN, LiveCheck.gap(10_000, 50).verdict)
        assertEquals(Verdict.FAIL, LiveCheck.gap(10_001, 50).verdict)
    }

    @Test fun agreement() {
        assertEquals(Verdict.PASS, LiveCheck.agree(24_480.0, 24_480.0).verdict)
        assertEquals("within 0.000% of the REST quote", LiveCheck.agree(24_480.0, 24_480.0).detail)
        assertEquals(Verdict.PASS, LiveCheck.agree(24_490.0, 24_480.0).verdict)
        assertEquals(Verdict.WARN, LiveCheck.agree(24_520.0, 24_480.0).verdict)
        assertEquals(Verdict.FAIL, LiveCheck.agree(24_600.0, 24_480.0).verdict)
        assertEquals(Verdict.FAIL, LiveCheck.agree(null, 24_480.0).verdict)
        assertEquals(Verdict.FAIL, LiveCheck.agree(24_480.0, 0.0).verdict)
        assertEquals(Verdict.FAIL, LiveCheck.agree(Double.NaN, 1.0).verdict)
        assertFalse(LiveCheck.agree(24_490.0, 24_480.0).detail.contains("24"), "no price is shown")
    }

    @Test fun clockDrift() {
        assertEquals(Verdict.PASS, LiveCheck.drift(-120, "Zerodha's clock").verdict)
        assertEquals("phone behind by 120 ms (Zerodha's clock)", LiveCheck.drift(-120, "Zerodha's clock").detail)
        assertEquals("phone ahead by 500 ms (x)", LiveCheck.drift(500, "x").detail)
        assertEquals(Verdict.WARN, LiveCheck.drift(1_500, "x").verdict)
        assertEquals(Verdict.FAIL, LiveCheck.drift(-2_001, "x").verdict)
        assertEquals(Verdict.WARN, LiveCheck.drift(null, "x").verdict)
    }

    @Test fun feedAges() {
        assertEquals(Verdict.PASS, LiveCheck.age(45, "Zerodha candles").verdict)
        assertEquals("Zerodha candles: last bar 45 s old", LiveCheck.age(45, "Zerodha candles").detail)
        assertEquals(Verdict.WARN, LiveCheck.age(121, "x").verdict)
        assertEquals(Verdict.FAIL, LiveCheck.age(301, "x").verdict)
        assertEquals(Verdict.FAIL, LiveCheck.age(null, "x").verdict)
    }

    @Test fun everyOpenPositionWatched() {
        assertEquals(LiveCheck.pass("nothing open"), LiveCheck.watched(emptyList()))
        val ok = LiveCheck.watched(listOf(LiveCheck.Held(true, true, true, 4_000), LiveCheck.Held(false, true, false, 900)))
        assertEquals(Verdict.PASS, ok.verdict)
        assertEquals("2 open (1 paper, 1 live): 2 with a stop, 1 with a target, last checked at most 4 s ago", ok.detail)
        val late = LiveCheck.watched(listOf(LiveCheck.Held(true, true, true, 15_000)))
        assertEquals(Verdict.WARN, late.verdict, "15 s is not under 15 s")
        assertTrue(late.detail.endsWith("1 not checked in the last 15 s"))
        assertEquals(Verdict.WARN, LiveCheck.watched(listOf(LiveCheck.Held(true, true, true, 29_999))).verdict)
        val stale = LiveCheck.watched(listOf(LiveCheck.Held(true, true, true, 30_000), LiveCheck.Held(true, true, true, 16_000)))
        assertEquals(Verdict.FAIL, stale.verdict, "a stale one fails the step, whatever the others")
        assertTrue(stale.detail.endsWith("1 not checked in the last 30 s"))
        assertEquals(Verdict.FAIL, LiveCheck.watched(listOf(LiveCheck.Held(false, true, true, null))).verdict)
        assertTrue(LiveCheck.watched(listOf(LiveCheck.Held(false, true, true, null))).detail.contains("never checked"))
        val noStop = LiveCheck.watched(listOf(LiveCheck.Held(true, false, false, 1_000)))
        assertEquals(Verdict.WARN, noStop.verdict)
    }

    private fun st(g: Group, name: String, v: Verdict, ms: Long) = LiveCheck.Step(g, name, v, "d", ms)

    @Test fun summaryGroupsAndDiary() {
        assertEquals("No checks run yet", LiveCheck.summary(emptyList()))
        val steps = listOf(
            st(Group.PAPER, "Paper fill", Verdict.PASS, 840),
            st(Group.ACCOUNT, "Funds", Verdict.PASS, 120),
            st(Group.STREAM, "Reconnect", Verdict.FAIL, 30_000),
            st(Group.STREAM, "First tick", Verdict.WARN, 2_500),
            st(Group.STREAM, "Ticks", Verdict.CLOSED, 0),
            st(Group.FEEDS, "Dhan", Verdict.SKIPPED, 0),
        )
        assertEquals("2 of 4 passed, 1 slow or warned, 1 failed, 1 waiting for market hours, 1 skipped, slowest: Reconnect 30000 ms", LiveCheck.summary(steps))
        assertEquals("2 of 2 passed, slowest: Paper fill 840 ms", LiveCheck.summary(steps.take(2)))
        assertEquals("0 of 0 passed, 1 waiting for market hours", LiveCheck.summary(listOf(steps[4])))
        val g = LiveCheck.grouped(steps)
        assertEquals(listOf(Group.ACCOUNT, Group.STREAM, Group.PAPER, Group.FEEDS), g.map { it.first })
        assertEquals("Price stream · 0 of 2 passed", LiveCheck.groupLine(Group.STREAM, g[1].second))
        assertEquals("Price stream · market closed", LiveCheck.groupLine(Group.STREAM, listOf(steps[4])))
        assertEquals("Data feeds · skipped", LiveCheck.groupLine(Group.FEEDS, listOf(steps[5])))
        assertEquals("FAILED Price stream · Reconnect: d (30000 ms)", LiveCheck.diaryLine(steps[2]))
        assertEquals(listOf("ok", "WARN", "closed", "skipped"), listOf(steps[0], steps[3], steps[4], steps[5]).map { LiveCheck.diaryLine(it).substringBefore(' ') })
    }

    @Test fun lastSummaryFromTheDiary() {
        val lines = listOf(
            "10-19 09:20:41 [live-check] summary: 20 of 24 passed",
            "10-19 09:20:41 [stream] dropped: x",
            "10-19 11:02:03 [live-check] summary: 23 of 24 passed, 1 failed, slowest: X 840 ms",
            "10-19 11:02:03 [live-check] ok Clock · Phone clock: phone ahead by 20 ms (5 ms)",
        )
        assertEquals("Live check (last run 10-19 11:02:03): 23 of 24 passed, 1 failed, slowest: X 840 ms", LiveCheck.lastSummary(lines))
        assertEquals(null, LiveCheck.lastSummary(lines.drop(3)))
    }

    @Test fun autoRunWindow() {
        val d = LocalDate.of(2026, 10, 19)
        assertTrue(LiveCheck.autoDue(true, true, 9 * 60 + 20, null, d))
        assertTrue(LiveCheck.autoDue(true, true, 9 * 60 + 34, d.minusDays(1), d))
        assertFalse(LiveCheck.autoDue(false, true, 9 * 60 + 20, null, d), "off by default and off means never")
        assertFalse(LiveCheck.autoDue(true, false, 9 * 60 + 20, null, d), "a holiday")
        assertFalse(LiveCheck.autoDue(true, true, 9 * 60 + 19, null, d))
        assertFalse(LiveCheck.autoDue(true, true, 9 * 60 + 35, null, d))
        assertFalse(LiveCheck.autoDue(true, true, 9 * 60 + 25, d, d), "once a day")
    }

    @Test fun jarvisAsks() {
        for (q in listOf("run the live check", "Jarvis, live check karo", "do the self-test", "self test chalao", "LIVE-CHECK", "check everything live"))
            assertTrue(LiveCheck.asked(q), q)
        for (q in listOf("check my positions", "is the market live", "test the strategy", "selfish check"))
            assertFalse(LiveCheck.asked(q), q)
        // A question about the last run never starts a new one (a run drops the stream and trades on paper).
        for (q in listOf("what did the live check find", "why did the self-test fail", "show the last live check", "live check ka result kya hua"))
            assertTrue(LiveCheck.asked(q) && LiveCheck.resultAsked(q), q)
        for (q in listOf("run the live check", "Jarvis, live check karo", "do the self-test", "check everything live"))
            assertFalse(LiveCheck.resultAsked(q), q)
        assertEquals("The live check did not run, Boss.", LiveCheck.answer(emptyList()))
        val good = listOf(st(Group.ACCOUNT, "Funds", Verdict.PASS, 120))
        assertEquals("Live check done, Boss: 1 of 1 passed, slowest: Funds 120 ms.", LiveCheck.answer(good))
        val bad = (1..5).map { LiveCheck.Step(Group.STREAM, "S$it", Verdict.FAIL, "why $it", 10) }
        val a = LiveCheck.answer(bad)
        assertTrue(a.endsWith("Failed: S1 (why 1); S2 (why 2); S3 (why 3); and 2 more on the card."), a)
    }
}
