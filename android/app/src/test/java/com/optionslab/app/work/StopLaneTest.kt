package com.optionslab.app.work

import com.optionslab.app.data.WatchClock
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.ira.StopCadence
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * 22 Oct: the stop lane ([StopLane]). On 21 Oct the stops were looked at more than 16 s apart while a position was held: the
 * 15-second stop check ran after the full pass (max 20.2 s) and after the bar-close entry check (its arms waited up to 45 s).
 * The lane looks on its own clock, beside them. These run on the test scheduler's clock (virtual time).
 */
class StopLaneTest : RobolectricTest() {
    @Before fun up() { WatchClock.resetForTest() }

    @After fun down() {
        WatchClock.resetForTest()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    /** A wall clock on the test scheduler (never 0: a real clock never is). */
    private fun TestScope.clock(): () -> Long = { 1_700_000_000_000L + testScheduler.currentTime }

    private fun gaps(starts: List<Long>) = starts.zipWithNext { a, b -> b - a }

    /**
     * The watch loop as on 21 Oct, beside the lane: a 20 s full pass, then a bar-close entry check of 30 s, then a 40 s full pass
     * (a hot phone, slow reads). The lane's looks, each 3 s long, stay 15 s apart throughout.
     */
    @Test fun aSlowFullPassOrBarCloseCheckNeverHoldsTheStopsBack() = runTest {
        val now = clock()
        val ctl = StopLane.Control()
        val looks = ArrayList<Pair<StopLane.Mode, Long>>()
        val lane = launch {
            StopLane.run(stop = { ctl.ended }, mode = { ctl.mode }, held = { true }, look = { m, t -> looks += m to t; delay(3_000) },
                now = now, sleep = { delay(it) }, mcxPassAt = { 0L })
        }
        val passStart = now()
        ctl.mode = StopLane.Mode.NSE
        delay(20_000)      // the full pass
        delay(30_000)      // the bar-close entry check
        delay(40_000)      // the next full pass
        ctl.mode = StopLane.Mode.OFF
        ctl.ended = true
        lane.join()
        val starts = looks.map { it.second }
        assertTrue("the first look comes with the pass, not after it: ${starts.first() - passStart} ms", starts.first() - passStart <= StopCadence.LANE_POLL_MS)
        assertTrue("about 90 s held: ${starts.size} looks", starts.size >= 6)
        gaps(starts).forEach { assertEquals("every look 15 s after the last one started", 15_000L, it) }
        assertTrue(looks.all { it.first == StopLane.Mode.NSE })
    }

    @Test fun aLookThatRunsLongIsFollowedASecondAfterItNeverLater() = runTest {
        val now = clock()
        val ctl = StopLane.Control().apply { mode = StopLane.Mode.NSE }
        val starts = ArrayList<Long>()
        val took = ArrayDeque(listOf(18_000L, 2_000L, 2_000L, 2_000L))
        val lane = launch {
            StopLane.run(stop = { ctl.ended || starts.size >= 4 }, mode = { ctl.mode }, held = { true },
                look = { _, t -> starts += t; delay(took.removeFirst()) }, now = now, sleep = { delay(it) }, mcxPassAt = { 0L })
        }
        lane.join()
        // An 18 s look (a broker that hung, the arms' exits left to finish after 8 s): the next 19 s after, then 15 s again.
        assertEquals(listOf(19_000L, 15_000L, 15_000L), gaps(starts))
    }

    @Test fun nothingHeldNothingChanges() = runTest {
        val now = clock()
        val ctl = StopLane.Control().apply { mode = StopLane.Mode.NSE }
        var looks = 0
        val lane = launch {
            StopLane.run(stop = { ctl.ended }, mode = { ctl.mode }, held = { false }, look = { _, _ -> looks++ }, now = now,
                sleep = { delay(it) }, mcxPassAt = { 0L })
        }
        delay(5 * 60_000L)
        ctl.ended = true
        lane.join()
        assertEquals("nothing held: no look at all", 0, looks)
        assertEquals("and nothing noted for the drift guard", 0L, WatchClock.lookAt)
        // Held, but no market's look is due (before the open, the evening look): no look either.
        val off = StopLane.Control()
        val lane2 = launch {
            StopLane.run(stop = { off.ended }, mode = { off.mode }, held = { true }, look = { _, _ -> looks++ }, now = now,
                sleep = { delay(it) }, mcxPassAt = { 0L })
        }
        delay(60_000L)
        off.ended = true
        lane2.join()
        assertEquals(0, looks)
        assertFalse(StopLane.heldFor(StopLane.Mode.OFF, nseSeen = true))
        assertTrue("the watch loop's own answer counts", StopLane.heldFor(StopLane.Mode.NSE, nseSeen = true))
    }

    /** MCX held at night: its own pass looks every 15 s; when one runs 40 s (a slow Zerodha read), the lane covers the gap. */
    @Test fun mcxHeldAtNightTheLaneCoversALatePassAndNeverDoublesAnOnTimeOne() = runTest {
        val now = clock()
        val ctl = StopLane.Control()
        val passStarts = ArrayList<Long>()
        val laneLooks = ArrayList<Long>()
        val lane = launch {
            StopLane.run(stop = { ctl.ended }, mode = { ctl.mode }, held = { it == StopLane.Mode.MCX },
                look = { _, t -> laneLooks += t; delay(2_000) }, now = now, sleep = { delay(it) },
                mcxPassAt = { passStarts.lastOrNull() ?: 0L })
        }
        ctl.mode = StopLane.Mode.MCX
        // Three passes on time (a 2 s pass, then its 13 s wait), then one of 40 s, then on time again.
        for (len in listOf(15_000L, 15_000L, 15_000L, 40_000L, 15_000L, 15_000L)) { passStarts += now(); delay(len) }
        ctl.ended = true
        lane.join()
        assertTrue("the lane looked only while the pass was late: $laneLooks", laneLooks.size in 2..3)
        val all = (passStarts + laneLooks).sorted()
        gaps(all).forEach { assertTrue("a gap of $it ms in $all", it <= 15_000L + StopCadence.LANE_POLL_MS) }
    }

    /** The lane reads no battery, saver, heat or memory state: with the saver on its looks stay 15 s apart. */
    @Test fun theBatterySaverNeverStretchesTheLook() = runTest {
        shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIsPowerSaveMode(true)
        assertTrue(WatchWorkers.saver(context))
        val now = clock()
        val ctl = StopLane.Control().apply { mode = StopLane.Mode.NSE }
        val starts = ArrayList<Long>()
        val lane = launch {
            StopLane.run(stop = { ctl.ended || starts.size >= 5 }, mode = { ctl.mode }, held = { true }, look = { _, t -> starts += t },
                now = now, sleep = { delay(it) }, mcxPassAt = { 0L })
        }
        lane.join()
        assertEquals(List(4) { 15_000L }, gaps(starts))
    }

    /** NSE held with the price stream down (the stream is off in tests): the real look runs the paper book and the stops pass. */
    @Test fun aRealLookRunsThePaperBookAndTheStopsAndNotesTheLook() {
        val t0 = System.currentTimeMillis()
        assertTrue(com.optionslab.app.data.KiteStream.status.value != com.optionslab.app.data.KiteStream.Status.LIVE)
        runBlocking { StopLane.look(context, StopLane.Mode.NSE, t0) }
        assertEquals("the drift guard saw the look start", t0, WatchClock.lookAt)
        assertTrue(WatchClock.paperAt >= t0)
        assertTrue(WatchClock.stopsAt >= t0)
        // MCX's look between its passes: the same safety steps.
        WatchClock.resetForTest()
        val t1 = System.currentTimeMillis()
        runBlocking { StopLane.look(context, StopLane.Mode.MCX, t1) }
        assertEquals(t1, WatchClock.lookAt)
        assertTrue(WatchClock.paperAt >= t1)
        assertTrue(WatchClock.stopsAt >= t1)
        // No look for no market.
        WatchClock.resetForTest()
        runBlocking { StopLane.look(context, StopLane.Mode.OFF, t1) }
        assertEquals(0L, WatchClock.lookAt)
    }

    /** MCX's own pass comes back 15 s after it STARTED (it was 15 s after it ended: a 5 s pass came every 20 s). */
    @Test fun mcxsPassComesBack15sAfterItsStartNotItsEnd() = runTest {
        val clock = { testScheduler.currentTime + 1_000_000L }
        val s1 = clock()
        delay(5_000)       // a 5 s pass
        WatchService.mcxWait(cadence = { 15_000L }, due = { true }, now = clock, startedAt = s1)
        assertEquals(15_000L, clock() - s1)
        // A 20 s pass (a slow Zerodha read): the next one a second after it (the stop lane looked at 16 s meanwhile).
        val s2 = clock()
        delay(20_000)
        WatchService.mcxWait(cadence = { 15_000L }, due = { true }, now = clock, startedAt = s2)
        assertEquals(21_000L, clock() - s2)
        // Nothing held (60 s): still timed from the start.
        val s3 = clock()
        delay(5_000)
        WatchService.mcxWait(cadence = { 60_000L }, due = { true }, now = clock, startedAt = s3)
        assertEquals(60_000L, clock() - s3)
        WatchService.stepSec = 0
    }

    @Test fun theArmsExitsAreWaitedOnInsideTheWindow() {
        assertTrue(WatchWorkers.EXITS_WAIT_MS < StopCadence.WINDOW_MS)
    }

    /**
     * 26 Oct (R105 lead 4: looks 30 s and more apart on 23 and 26 Oct while held, the stream quiet and Zerodha slow): every wait in a
     * look is bounded, so a look ends inside its 15 s window however slow a read is.
     */
    @Test fun aLooksWaitsAddUpToLessThanItsWindow() {
        assertTrue("${StopLane.LOOK_BOUND_MS}", StopLane.LOOK_BOUND_MS < StopCadence.WINDOW_MS)
        assertTrue(StopLane.PAPER_WAIT_MS + StopLane.QUIET_EXITS_WAIT_MS + StopLane.SAFETY_WAIT_MS < StopCadence.WINDOW_MS)
    }

    /**
     * A held paper option whose price read hangs (the stream quiet, the feed slow): the paper book's pass is left to finish by
     * itself and the look - the arms' exits, the sweep, the stops - ends on time, well inside its 15 s.
     */
    @Test fun aSlowPaperPriceReadNeverHoldsTheLookPastItsBound() {
        val upstox = com.optionslab.app.testing.FakeUpstox()
        val released = java.util.concurrent.CountDownLatch(1)
        try {
            val day = com.optionslab.app.testing.AutomationSupport.tradingDayAt(10, 0).toLocalDate()
            com.optionslab.app.data.Market.testClock = java.time.Clock.fixed(day.atTime(10, 0).atZone(com.optionslab.engine.IST).toInstant(), com.optionslab.engine.IST)
            val key = "NSE_FO|SL25050CE"
            com.optionslab.app.testing.AutomationSupport.contracts(context, listOf(
                com.optionslab.engine.Upstox.Contract("NIFTY", day.plusDays(7), 25_050.0, com.optionslab.engine.Right.CE, 65, key, "NIFTY-SL-25050CE")))
            upstox.price(key, 100.0)
            val c = com.optionslab.app.data.Paper.contractFor("NIFTY", day.plusDays(7), 25_050.0, com.optionslab.engine.Right.CE)!!
            assertTrue(runBlocking { com.optionslab.app.data.Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, com.optionslab.engine.sandbox.Quote(100.0)) }.ok)
            upstox.gate = { k -> if (k == key) released.await(10, java.util.concurrent.TimeUnit.SECONDS) }
            runBlocking {
                val run = WatchWorkers.begin(this)
                try {
                    val t0 = System.currentTimeMillis()
                    StopLane.look(context, StopLane.Mode.NSE, t0)
                    val ms = System.currentTimeMillis() - t0
                    assertTrue("the look took $ms ms", ms < StopLane.LOOK_BOUND_MS + 2_000L)
                    assertTrue("inside its window: $ms ms", ms < StopCadence.WINDOW_MS)
                    assertEquals("the drift guard saw the look start", t0, WatchClock.lookAt)
                } finally {
                    released.countDown()
                    WatchWorkers.end(run)
                }
            }
        } finally {
            released.countDown()
            upstox.gate = null
            upstox.close()
            com.optionslab.app.data.Market.testClock = null
        }
    }
}
