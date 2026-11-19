package com.optionslab.ira

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 18 Nov 2026 (audit: the 15:05 expiry square-off, the flat-by and the daily loss limit ran only inside the full watch pass, whose
 * p95 was 10 min 42 s on the phone): the clock lane's pure parts ([ClockSteps]) and the supervisor's never-awaited steps
 * ([Supervisor.kick]) it runs them on.
 */
class ClockStepsTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @AfterTest fun end() = scope.cancel()

    private suspend fun until(ms: Long = 5_000, cond: () -> Boolean) = withTimeout(ms) { while (!cond()) delay(5) }

    // Tue 17 Nov 2026 is NIFTY's weekly expiry; Wed 18 Nov is not; Sat 21 / Sun 22 are closed.
    private val tue = LocalDate.of(2026, 11, 17)
    private val wed = tue.plusDays(1)
    private fun at(d: LocalDate, h: Int, m: Int, s: Int = 0): ZonedDateTime = d.atTime(LocalTime.of(h, m, s)).atZone(ist)
    private val weekdays: (LocalDate) -> Boolean = { it.dayOfWeek.value <= 5 }

    @Test fun roundsFallOnTheClocksFifteenSecondMarksNeverAfterTheLastRoundEnded() {
        val base = at(wed, 15, 4, 0).toInstant().toEpochMilli()
        assertEquals(1_000L, ClockSteps.nextRoundIn(base))                 // 15:04:00 -> 15:04:01
        assertEquals(15_000L, ClockSteps.nextRoundIn(base + 1_000))        // at a mark: the next one, a full round on
        assertEquals(1L, ClockSteps.nextRoundIn(base + 15_999))
        // A clock set back (or forward) never makes a wait longer than a round.
        for (t in listOf(base - 3_600_000L, base + 7, 0L, 1L)) assertTrue(ClockSteps.nextRoundIn(t) in 1..ClockSteps.ROUND_MS)
    }

    @Test fun theAlarmRingsAt1504OnAnExpiryDayAndNotOnOtherDays() {
        val expiry: (LocalDate) -> Boolean = { it == tue }
        assertEquals(at(tue, 15, 4), ClockSteps.nextAlarm(at(tue, 9, 30), weekdays, expiry))
        assertEquals(at(tue, 15, 19), ClockSteps.nextAlarm(at(tue, 15, 4), weekdays, expiry), "at the minute itself: the next one")
        assertEquals(at(tue, 15, 24), ClockSteps.nextAlarm(at(tue, 15, 19, 30), weekdays, expiry))
        assertEquals(at(wed, 15, 19), ClockSteps.nextAlarm(at(tue, 15, 24), weekdays, expiry), "not an expiry day: no 15:04")
        assertEquals(at(wed, 15, 19), ClockSteps.nextAlarm(at(wed, 9, 0), weekdays, expiry))
        assertFalse(ClockSteps.alarmMinutes(false).contains(15 * 60 + 4))
        assertEquals(listOf(15 * 60 + 4, 15 * 60 + 19, 15 * 60 + 24), ClockSteps.alarmMinutes(true))
    }

    @Test fun holidaysAndWeekendsGetNoAlarm() {
        val fri = LocalDate.of(2026, 11, 20); val mon = fri.plusDays(3)
        val expiry: (LocalDate) -> Boolean = { true }
        assertEquals(at(mon, 15, 4), ClockSteps.nextAlarm(at(fri, 16, 0), weekdays, expiry), "the weekend skipped")
        // An expiry day that is a holiday is no day at all (the exchange moved its expiry: the day before is told as the expiry).
        val holiday: (LocalDate) -> Boolean = { weekdays(it) && it != tue }
        assertEquals(at(wed, 15, 4), ClockSteps.nextAlarm(at(LocalDate.of(2026, 11, 16), 15, 30), holiday, expiry))
        assertNull(ClockSteps.nextAlarm(at(wed, 9, 0), { false }, expiry), "a broken calendar: no alarm, never a loop")
        // An expiry check that fails counts as an expiry day (the earlier alarm only wakes the watch sooner).
        assertEquals(at(wed, 15, 4), ClockSteps.nextAlarm(at(wed, 9, 0), weekdays) { error("calendar") })
    }

    @Test fun aStepIsLateOnlyWhenNoRunStartedAfterItsTimeCompletedWithin45Seconds() {
        val t1505 = at(tue, 15, 5).toInstant().toEpochMilli()
        val since = at(tue, 9, 15).toInstant().toEpochMilli()
        val lastBefore = t1505 - 2_000
        val due = ClockSteps.dueAt(lastBefore, since, t1505)
        assertEquals(t1505 + 13_000, due, "the rolling round (15:04:58 + 15 s) is later than 15:05")
        assertFalse(ClockSteps.late(due, lastBefore, due + 44_999))
        assertTrue(ClockSteps.late(due, lastBefore, due + 45_000))
        assertFalse(ClockSteps.late(due, due + 1, due + 60_000), "a run started after it completed")
        // Long done before: the 15:05 minute itself decides.
        val longAgo = t1505 - 600_000
        assertEquals(t1505, ClockSteps.dueAt(longAgo - 60_000, longAgo - 60_000, t1505))
        // A lane that only started at 15:10 (a restart) gets its own round's grace, never an alert at once.
        val start = at(tue, 15, 10).toInstant().toEpochMilli()
        assertEquals(start + ClockSteps.ROUND_MS, ClockSteps.dueAt(0L, start, t1505))
        // No scheduled minute (the loss limit): a run completed within each minute.
        assertEquals(since + 15_000, ClockSteps.dueAt(0L, since, null))
    }

    @Test fun lastScheduledIsTheLatestMinuteAlreadyPassed() {
        val mins = listOf(15 * 60 + 20, 15 * 60 + 25)
        assertNull(ClockSteps.lastScheduled(wed, mins, at(wed, 15, 19, 59)))
        assertEquals(at(wed, 15, 20).toInstant().toEpochMilli(), ClockSteps.lastScheduled(wed, mins, at(wed, 15, 20)))
        assertEquals(at(wed, 15, 25).toInstant().toEpochMilli(), ClockSteps.lastScheduled(wed, mins, at(wed, 15, 30)))
    }

    @Test fun theSquareOffsNeverHoldTheTurnTogetherAndAStuckOneOnlyCostsTheOtherItsRound() = runBlocking {
        val turn = ClockSteps.Turn(pollMs = 5)
        val inside = AtomicInteger(); var most = 0
        val runs = (1..20).map { i ->
            async(Dispatchers.IO) {
                turn.take(waitMs = 10_000) {
                    val n = inside.incrementAndGet(); synchronized(this@ClockStepsTest) { most = maxOf(most, n) }
                    delay(2); inside.decrementAndGet(); i
                }
            }
        }.awaitAll()
        assertEquals((1..20).toList(), runs, "every one had its turn")
        assertEquals(1, most, "never two at once")
        // One stuck (a hung read): the other waits its bound and leaves the round, the turn untouched.
        val release = CompletableDeferred<Unit>()
        val stuck = scope.launch { turn.take { release.await() } }
        until { turn.held }
        assertNull(turn.take(waitMs = 50) { "ran" })
        release.complete(Unit); stuck.join()
        assertFalse(turn.held)
        assertEquals("ran", turn.take { "ran" })
        // A failure lets the turn go.
        runCatching { turn.take<Unit> { error("boom") } }
        assertFalse(turn.held)
    }

    @Test fun onceTellsEachKeyOncePerDay() {
        val once = ClockSteps.Once()
        assertTrue(once.first(tue, "expiry|15:05")); assertFalse(once.first(tue, "expiry|15:05"))
        assertTrue(once.first(tue, "flat-by|15:25"))
        assertTrue(once.first(wed, "expiry|15:05"), "a new day")
    }

    /** The supervisor's never-awaited steps: each runs once at a time, a stuck one never holds up a sibling or the caller. */
    @Test fun kickedStepsAreNeverWaitedForAndAStuckOneNeverHoldsASiblingBack() = runBlocking {
        val sup = Supervisor(scope)
        val release = CompletableDeferred<Unit>()
        val stuckRuns = AtomicInteger(); val quickRuns = AtomicInteger()
        val steps = listOf(
            Supervisor.Step("expiry square-off") { stuckRuns.incrementAndGet(); release.await() },
            Supervisor.Step("daily loss limit") { quickRuns.incrementAndGet() },
        )
        val t0 = System.nanoTime()
        repeat(5) { sup.kick("clock lane", steps); until { quickRuns.get() >= it + 1 } }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3_000, "the caller never waited for the stuck step")
        assertEquals(5, quickRuns.get(), "the loss limit ran every round")
        assertEquals(1, stuckRuns.get(), "the stuck one was never started again beside itself")
        assertEquals(Supervisor.State.OVERRUN, sup.status("expiry square-off")!!.state)
        release.complete(Unit)
        until { !sup.busy("expiry square-off") }
        assertEquals(listOf("expiry square-off", "daily loss limit"), sup.kick("clock lane", steps))
        until { stuckRuns.get() == 2 }
    }

    /** The pass ([Supervisor.group]) and the lane ([Supervisor.kick]) on two threads never start one step twice at once. */
    @Test fun thePassAndTheLaneNeverRunOneStepTwiceAtOnce() = runBlocking {
        val sup = Supervisor(scope)
        val inside = AtomicInteger(); val most = AtomicInteger(); val runs = AtomicInteger()
        val step = Supervisor.Step("flat-by") {
            val n = inside.incrementAndGet(); most.accumulateAndGet(n, ::maxOf); runs.incrementAndGet()
            delay(1); inside.decrementAndGet()
        }
        val a = scope.launch { repeat(300) { sup.group("watch pass", listOf(step), 50) } }
        val b = scope.launch { repeat(300) { sup.kick("clock lane", listOf(step)); delay(1) } }
        a.join(); b.join()
        until { !sup.busy("flat-by") }
        assertEquals(1, most.get(), "never two at once")
        assertTrue(runs.get() > 10)
    }

    /** The lane: a round at once, then on the clock's marks; a poke (an alarm) brings one at once; nothing when not active. */
    @Test fun theLaneRunsOnTheClockAndAtOnceWhenPoked() = runBlocking {
        val lane = ClockSteps.Lane(roundMs = 200, offsetMs = 0, pollMs = 10)
        var on = false; var stop = false
        val rounds = CopyOnWriteArrayList<Long>()
        val sinces = CopyOnWriteArrayList<Long>()
        val job = scope.launch { lane.run(stop = { stop }, active = { on }, round = { t, since -> rounds += t; sinces += since }) }
        delay(300)
        assertTrue(rounds.isEmpty(), "not active (before the open): no round")
        on = true
        until { rounds.size >= 3 }
        assertEquals(1, sinces.distinct().size, "active since its first round")
        val n = rounds.size
        lane.poke()
        until(150) { rounds.size > n }   // well inside a 200 ms round: the poke, not the clock
        // A round that throws, or runs long, never ends the lane.
        stop = true; job.join()
        val failures = CopyOnWriteArrayList<Throwable>()
        var count = 0
        val j2 = scope.launch {
            ClockSteps.Lane(roundMs = 50, offsetMs = 0, pollMs = 10).run(stop = { count >= 3 }, active = { true },
                round = { _, _ -> count++; error("a round failed") }, failed = { failures += it })
        }
        j2.join()
        assertEquals(3, failures.size)
    }
}
