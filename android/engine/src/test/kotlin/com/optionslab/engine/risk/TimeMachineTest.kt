package com.optionslab.engine.risk

import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.OrbRules
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The time machine (22 Oct): the bootstrap replay of an entry on the underlying's past sessions, with the stop checked
 * before the target inside a bar; the edge cases Boss named (no bars, fewer than ten sessions, gaps and holidays, stale
 * bars from a previous day, R <= 0, a slot outside the session) and the ORB rule's re-run.
 */
class TimeMachineTest {
    private val today = LocalDate.of(2026, 10, 22)

    /** A session of 5-minute bars 09:15 .. 15:25 whose closes follow [path] (one value a bar; repeated when short). */
    private fun session(date: LocalDate, path: (Int) -> Double, range: Double = 10.0, bars: Int = 75): TimeMachine.Session {
        val list = (0 until bars).map { i ->
            val start = date.atTime(9, 15).plusMinutes(5L * i)
            val c = path(i)
            Bar(start, c, c + range, c - range, c)
        }
        return TimeMachine.Session(date, list)
    }

    /** Trading days before [today], skipping weekends (and [holidays]), newest last. */
    private fun days(n: Int, holidays: Set<LocalDate> = emptySet()): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var d = today.minusDays(1)
        while (out.size < n) {
            if (d.dayOfWeek.value <= 5 && d !in holidays) out += d
            d = d.minusDays(1)
        }
        return out.reversed()
    }

    private val up = { i: Int -> 50_000.0 + 20.0 * i }      // climbs 20 a bar: a long's target comes first
    private val down = { i: Int -> 50_000.0 - 20.0 * i }
    private val flat = { _: Int -> 50_000.0 }

    private fun ask(direction: Int = 1, stop: Double = 100.0, target: Double = 100.0, slot: LocalTime = LocalTime.of(10, 35),
                    exitAt: LocalTime? = LocalTime.of(15, 10), draws: Int = TimeMachine.DRAWS) =
        TimeMachine.Ask(direction, slot, stop, target, 5, exitAt, draws, seed = 7)

    @Test fun aClimbingUnderlyingIsAnEdgeForACallAndAgainstAPut() {
        val past = days(60).map { session(it, up) }
        val call = TimeMachine.odds(past, ask(1), today)
        assertEquals(TimeMachine.Verdict.EDGE, call.verdict)
        assertEquals(TimeMachine.DRAWS, call.used)
        assertEquals(call.used, call.targetFirst)
        assertEquals(0, call.stopFirst); assertEquals(0, call.neither)
        assertEquals(100.0, call.targetPct); assertEquals(0.0, call.stopPct); assertEquals(0.0, call.neitherPct)
        assertEquals(1.0, call.r); assertEquals(0.5, call.breakEven); assertEquals(1.0, call.decidedWinRate)
        assertEquals(60, call.sessions)
        assertTrue(call.sessionsHit in 50..60, "${call.sessionsHit}")
        assertNull(call.why)
        val put = TimeMachine.odds(past, ask(-1), today)
        assertEquals(TimeMachine.Verdict.AGAINST, put.verdict)
        assertEquals(put.used, put.stopFirst)
        val line = call.line()
        assertTrue(line.startsWith("Time machine (edge): target first 100%, stop first 0%, neither 0% over 2,000 draws from "), line)
        assertTrue("sessions at 10:35 ±2 bars; break-even 50% (R 1.00)." in line, line)
    }

    @Test fun aFallingUnderlyingIsAnEdgeForAPut() {
        val past = days(40).map { session(it, down) }
        val put = TimeMachine.odds(past, ask(-1), today)
        assertEquals(TimeMachine.Verdict.EDGE, put.verdict)
        assertEquals(put.used, put.targetFirst)
        assertEquals(TimeMachine.Verdict.AGAINST, TimeMachine.odds(past, ask(1), today).verdict)
    }

    @Test fun aFlatUnderlyingReachesNeitherAndSaysTheTimeExitDecides() {
        val past = days(30).map { session(it, flat, range = 1.0) }
        val o = TimeMachine.odds(past, ask(), today)
        assertEquals(TimeMachine.Verdict.UNKNOWN, o.verdict)
        assertEquals(o.used, o.neither)
        assertEquals("only 0 draws reached the stop or the target", o.why)
        assertTrue("neither 100%" in o.line(), o.line())
    }

    @Test fun theStopIsCheckedBeforeTheTargetInsideABar() {
        // Every bar touches both the stop and the target: the honest reading is the stop, every time.
        val past = days(20).map { session(it, flat, range = 500.0) }
        val o = TimeMachine.odds(past, ask(stop = 100.0, target = 100.0), today)
        assertEquals(o.used, o.stopFirst)
        assertEquals(TimeMachine.Verdict.AGAINST, o.verdict)
        assertEquals(o.used, TimeMachine.odds(past, ask(direction = -1), today).stopFirst)
        // The entry bar itself counts: a long in at the open of a bar whose low is at the stop.
        val b = listOf(Bar(today.minusDays(1).atTime(10, 35), 100.0, 101.0, 90.0, 100.0))
        assertEquals(-1, TimeMachine.walk(b, 0, 100.0, ask(stop = 10.0, target = 1.0)))
        assertEquals(1, TimeMachine.walk(b, 0, 100.0, ask(stop = 20.0, target = 1.0)))
        assertEquals(0, TimeMachine.walk(b, 0, 100.0, ask(stop = 20.0, target = 2.0)))
        // A short: the stop is above, the target below.
        assertEquals(-1, TimeMachine.walk(b, 0, 100.0, ask(direction = -1, stop = 1.0, target = 10.0)))
        assertEquals(1, TimeMachine.walk(b, 0, 100.0, ask(direction = -1, stop = 2.0, target = 10.0)))
        assertEquals(0, TimeMachine.walk(b, 0, 100.0, ask(direction = -1, stop = 2.0, target = 20.0)))
    }

    @Test fun aCoinFlipSitsInsideTheMarginOfTheBreakEven() {
        // Half the sessions climb, half fall: the win rate sits at the break-even.
        val past = days(60).mapIndexed { i, d -> session(d, if (i % 2 == 0) up else down) }
        val o = TimeMachine.odds(past, ask(), today)
        assertEquals(TimeMachine.Verdict.COIN_FLIP, o.verdict)
        assertTrue(o.targetPct in 35.0..65.0, "${o.targetPct}")
        assertTrue(o.line().startsWith("Time machine (coin flip)"), o.line())
    }

    @Test fun anAsymmetricRMovesTheBreakEven() {
        val past = days(60).map { session(it, up) }
        val o = TimeMachine.odds(past, ask(stop = 50.0, target = 150.0), today)
        assertEquals(3.0, o.r)
        assertEquals(0.25, o.breakEven)
        assertTrue("break-even 25% (R 3.00)" in o.line(), o.line())
    }

    // ---- the edge cases ----

    @Test fun noBarsAtAll() {
        val o = TimeMachine.odds(emptyList(), ask(), today)
        assertEquals(TimeMachine.Verdict.UNKNOWN, o.verdict)
        assertEquals(0, o.used); assertEquals(0, o.sessions)
        assertEquals("Time machine (unknown): no past sessions of the underlying on the phone", o.line())
        assertEquals(0.0, o.targetPct)
        assertTrue(o.decidedWinRate.isNaN())
    }

    @Test fun fewerThanTenSessionsGiveOddsButNoVerdict() {
        val past = days(9).map { session(it, up) }
        val o = TimeMachine.odds(past, ask(), today)
        assertEquals(TimeMachine.Verdict.UNKNOWN, o.verdict)
        assertEquals(9, o.sessions)
        assertEquals(o.used, o.targetFirst)
        assertEquals("only 9 past sessions (10 needed for a verdict)", o.why)
        assertTrue("only 9 past sessions" in o.line(), o.line())
        assertEquals(TimeMachine.Verdict.EDGE, TimeMachine.odds(days(10).map { session(it, up) }, ask(), today).verdict)
    }

    @Test fun gapsAndHolidaysAreSimplyMissingSessions() {
        val holidays = setOf(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 2))
        val past = days(30, holidays).map { session(it, up) }
        val o = TimeMachine.odds(past, ask(), today)
        assertEquals(30, o.sessions)
        assertEquals(TimeMachine.Verdict.EDGE, o.verdict)
        assertFalse(TimeMachine.usable(past, today).any { it.date in holidays })
    }

    @Test fun todaysOwnBarsAndFutureDatesAreNeverUsed() {
        val past = days(20).map { session(it, up) } + session(today, down) + session(today.plusDays(1), down)
        val u = TimeMachine.usable(past, today)
        assertEquals(20, u.size)
        assertTrue(u.all { it.date.isBefore(today) })
        assertEquals(TimeMachine.Verdict.EDGE, TimeMachine.odds(past, ask(), today).verdict)
    }

    @Test fun staleBarsLabelledWithAnotherDayAreLeftOut() {
        // A store whose "yesterday" holds the bars of the day before: a corrupt session, not used.
        val stale = TimeMachine.Session(today.minusDays(1), session(today.minusDays(2), up).bars)
        val past = days(12).map { session(it, up) } + stale
        val u = TimeMachine.usable(past, today)
        assertEquals(12, u.size)
        assertFalse(u.any { it.bars.any { b -> b.start.toLocalDate() != it.date } })
        // An empty session, and two copies of one day: one kept (the last given), sorted by date, the newest 60.
        val dup = session(today.minusDays(1), down)
        val many = days(70).map { session(it, up) } + TimeMachine.Session(today.minusDays(3), emptyList()) + dup
        val kept = TimeMachine.usable(many, today)
        assertEquals(60, kept.size)
        assertEquals(kept.map { it.date }, kept.map { it.date }.sorted())
        assertEquals(dup.bars, kept.last().bars)
    }

    @Test fun rAtOrBelowZeroCannotBeTold() {
        val past = days(30).map { session(it, up) }
        for (a in listOf(ask(stop = 0.0), ask(stop = -40.0), ask(target = 0.0), ask(target = Double.NaN), ask(stop = Double.POSITIVE_INFINITY))) {
            val o = TimeMachine.odds(past, a, today)
            assertEquals(TimeMachine.Verdict.UNKNOWN, o.verdict)
            assertTrue(o.r.isNaN()); assertTrue(o.breakEven.isNaN())
            assertEquals(0, o.used)
            assertTrue("R cannot be told" in o.line(), o.line())
        }
    }

    @Test fun aSlotOutsideTheSessionOrWithNoDrawsIsUnknown() {
        val past = days(30).map { session(it, up) }
        val closed = TimeMachine.odds(past, ask(slot = LocalTime.of(16, 0)), today)
        assertEquals(TimeMachine.Verdict.UNKNOWN, closed.verdict)
        assertEquals(0, closed.used)
        assertEquals("no past session has a bar at 16:00 ±2 bars", closed.why)
        // Past midnight either way: nothing to draw, no exception.
        assertEquals(0, TimeMachine.odds(past, ask(slot = LocalTime.of(0, 5)), today).used)
        assertEquals(0, TimeMachine.odds(past, ask(slot = LocalTime.of(23, 55)), today).used)
        assertEquals("nothing to draw", TimeMachine.odds(past, ask(draws = 0), today).why)
        assertEquals("nothing to draw", TimeMachine.odds(past, ask().copy(barMinutes = 0), today).why)
    }

    @Test fun aSlotNearTheEndUsesOnlyTheBarsThatExistThere() {
        // 15:20 ±2 bars: 15:10 .. 15:30; the sessions end at 15:25, so some draws find no bar and the time exit decides.
        val past = days(30).map { session(it, up) }
        val o = TimeMachine.odds(past, ask(slot = LocalTime.of(15, 20), exitAt = null), today)
        assertTrue(o.used in 1 until TimeMachine.DRAWS, "${o.used}")
        assertTrue(o.targetFirst + o.neither == o.used)
        // With the arm's 15:10 exit, every draw in at or after 15:10 is "neither" at once.
        val late = TimeMachine.odds(past, ask(slot = LocalTime.of(15, 20)), today)
        assertEquals(late.used, late.neither)
    }

    @Test fun theSameSeedReproducesACheck() {
        val past = days(60).mapIndexed { i, d -> session(d, if (i % 3 == 0) down else up) }
        val a = TimeMachine.odds(past, ask(), today)
        val b = TimeMachine.odds(past, ask(), today)
        assertEquals(a, b)
        val c = TimeMachine.odds(past, ask().copy(seed = 8), today)
        assertEquals(a.sessions, c.sessions)
    }

    @Test fun mostDrawsReachingNeitherIsSaidAsACaveat() {
        // Climbs 2 a bar with a 100-point target: only the earliest draws reach it before the 15:10 exit.
        val past = days(40).map { session(it, { i -> 50_000.0 + 2.0 * i }, range = 1.0) }
        val o = TimeMachine.odds(past, ask(slot = LocalTime.of(12, 0), stop = 1_000.0, target = 100.0), today)
        if (o.verdict != TimeMachine.Verdict.UNKNOWN) assertTrue(o.why?.contains("time exit decides") == true, o.line())
        else assertTrue(o.why?.contains("reached the stop or the target") == true, o.line())
    }

    // ---- the rule's re-run ----

    @Test fun aRuleIsRerunOnceASessionInAtTheNextBarsOpen() {
        val past = days(20).map { session(it, up) }
        val rule = TimeMachine.Rule { bars -> if (bars.size > 10) TimeMachine.Signal(10, 1) else null }
        val o = TimeMachine.odds(past, ask(), today, rule)
        val r = o.rerun!!
        assertEquals(20, r.sessions); assertEquals(20, r.signals)
        assertEquals(20, r.targetFirst); assertEquals(0, r.stopFirst); assertEquals(0, r.neither)
        assertEquals("the rule itself fired on 20 of 20 sessions: target first 20, stop first 0, neither 0", r.line())
        assertTrue(o.line().endsWith("; the rule itself fired on 20 of 20 sessions: target first 20, stop first 0, neither 0."), o.line())
        // The direction is the rule's: a down signal on a climbing session stops out.
        val downRule = TimeMachine.Rule { TimeMachine.Signal(10, -1) }
        assertEquals(20, TimeMachine.odds(past, ask(), today, downRule).rerun!!.stopFirst)
        // No signal, a signal on the last bar (no next bar), a bad index, a zero direction, or a rule that throws: not counted.
        for (bad in listOf(TimeMachine.Rule { null }, TimeMachine.Rule { TimeMachine.Signal(74, 1) }, TimeMachine.Rule { TimeMachine.Signal(-1, 1) },
                           TimeMachine.Rule { TimeMachine.Signal(10, 0) }, TimeMachine.Rule { throw IllegalStateException("x") })) {
            val n = TimeMachine.odds(past, ask(), today, bad).rerun!!
            assertEquals(0, n.signals)
            assertEquals("the rule itself fired on none of the 20 sessions", n.line())
        }
        assertNull(TimeMachine.odds(past, ask(), today).rerun)
    }

    @Test fun theOrbRuleFindsTheFirstBreakOfTheOpeningRange() {
        // Flat through the opening range (09:15 .. 10:00), then a break above at 10:05's close.
        fun orbDay(d: LocalDate, dir: Int) = session(d, { i -> if (i <= 9) 50_000.0 else 50_000.0 + dir * 30.0 * (i - 9) }, range = 5.0)
        val upDay = orbDay(today.minusDays(1), 1)
        val sig = TimeMachine.orbRule(OrbRules.ORB).signal(upDay.bars)!!
        assertEquals(1, sig.direction)
        assertEquals(LocalTime.of(10, 5), upDay.bars[sig.bar].start.toLocalTime())
        assertEquals(-1, TimeMachine.orbRule(OrbRules.ORB).signal(orbDay(today.minusDays(2), -1).bars)!!.direction)
        // Never a break: no signal. Too few bars for the range: no signal.
        assertNull(TimeMachine.orbRule(OrbRules.ORB).signal(session(today.minusDays(3), flat, range = 1.0).bars))
        assertNull(TimeMachine.orbRule(OrbRules.ORB).signal(upDay.bars.take(5)))
        val past = days(15).map { orbDay(it, 1) }
        val o = TimeMachine.odds(past, ask(slot = LocalTime.of(10, 10)), today, TimeMachine.orbRule(OrbRules.ORB))
        assertEquals(15, o.rerun!!.signals)
        assertEquals(15, o.rerun!!.targetFirst)
    }
}
