package com.optionslab.app.data

import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.KiteTicks
import com.optionslab.ira.CandleAgent
import com.optionslab.ira.CandleBook
import com.optionslab.ira.CandleRead
import com.optionslab.ira.Findings
import com.optionslab.ira.MemoryPlan
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The Candle agent in the app (20 Oct 2026): it is let go first when memory is short and never touches the position watch,
 * it skips its batch under memory pressure, it sleeps when no market is open, it loads the research seed from the app's
 * assets, its reads reach the findings bus with no direction, and Jarvis's answers come from memory.
 */
class CandleAgentHostTest : RobolectricTest() {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 10, 19)

    @Before fun up() { CandleAgentHost.resetForTest(); MemoryTrim.resetForTest(); SmartWorkers.bus.clear() }
    @After fun down() {
        CandleAgentHost.resetForTest(); MemoryTrim.resetForTest(); Market.testClock = null; SmartWorkers.bus.clear(); KiteStream.resetForTest()
        com.optionslab.app.ira.IraHub.testLabBars = null; com.optionslab.app.work.BackgroundBudget.resetForTest()
    }

    /** A trading day of 5-minute candles ending with a clear green big tail at 14:00. */
    private fun bars(): List<CandleRead.Bar> {
        val r = java.util.Random(3)
        val out = ArrayList<CandleRead.Bar>()
        var px = 24_000.0
        var t = day.atTime(9, 15)
        while (t.isBefore(day.atTime(14, 0))) {
            val o = px; val c = o + r.nextGaussian() * 4
            out += CandleRead.Bar(t, o, maxOf(o, c) + 2, minOf(o, c) - 2, c); px = c; t = t.plusMinutes(5)
        }
        return out + CandleRead.Bar(day.atTime(14, 0), px, px + 12, px - 30, px + 5)
    }

    private fun agentWithReads(): CandleAgent {
        val seed = CandleBook.seed(context.assets.open("knowledge/candle_seed.json").bufferedReader().use { it.readText() })
        val a = CandleAgent(seed)
        val now = day.atTime(14, 6)
        a.study(listOf(CandleAgent.Series("NIFTY", 5, bars())), emptyList(), now.atZone(zone).toInstant().toEpochMilli(), now, true)
        return a
    }

    @Test fun theResearchSeedShipsWithTheApp() {
        val seed = CandleBook.seed(context.assets.open("knowledge/candle_seed.json").bufferedReader().use { it.readText() })
        assertFalse(seed.empty)
        assertEquals(949, seed.row("big_lower_wick", "NIFTY", 15)!!.n)
    }

    @Test fun itIsLetGoFirstAndThePositionWatchIsNeverTouched() {
        // Something held is streaming: its candles must stay whatever the trim lets go.
        val held = 2_001L
        KiteStream.want("test-owner", listOf(held))
        val stamp = System.currentTimeMillis() / 1000
        KiteStream.feedForTest(listOf(KiteTicks.Tick(held, 100.0, exchangeTime = stamp)), System.currentTimeMillis())
        CandleAgentHost.resetForTest(agentWithReads())
        assertTrue(CandleAgentHost.view.value.latest.isNotEmpty())
        // The mildest counted level already lets the agent go, right after the language model.
        val mild = MemoryPlan.releases(MemoryPlan.RUNNING_MODERATE)
        assertEquals(MemoryPlan.Item.CANDLE_AGENT, mild[1])
        val done = MemoryTrim.release(mild, MemoryPlan.RUNNING_MODERATE)
        assertTrue(MemoryPlan.Item.CANDLE_AGENT in done)
        assertTrue(CandleAgentHost.view.value.released)
        assertTrue(CandleAgentHost.view.value.latest.isEmpty())
        assertTrue(MemoryTrim.lastLine.orEmpty(), MemoryTrim.lastLine.orEmpty().endsWith("the position watch untouched"))
        assertNotNull("the held instrument's candles stay", KiteStream.candles.latest(held))
        assertEquals("Candle agent: released to free memory", CandleAgentHost.diagLine())
        assertTrue("not loaded" in CandleAgentHost.answer(CandleAgent.Question.MEANING, null, false))
        assertNull(CandleAgentHost.ask("the trade manager", CandleAgent.Ask.Levels("NIFTY")))
        KiteStream.want("test-owner", emptyList())
    }

    @Test fun underMemoryPressureABatchDoesNothing() = runBlocking {
        MemoryTrim.onTrim(MemoryPlan.RUNNING_LOW)
        assertTrue(MemoryTrim.underPressure())
        CandleAgentHost.batch(context)
        assertEquals("Candle agent: not loaded yet", CandleAgentHost.diagLine())
        // Once the pressure has passed, the next batch loads it (its counts file and the research seed).
        MemoryTrim.resetForTest()
        Market.testClock = java.time.Clock.fixed(day.atTime(20, 0).atZone(zone).toInstant(), zone)
        CandleAgentHost.batch(context)
        assertTrue(CandleAgentHost.diagLine(), CandleAgentHost.diagLine().startsWith("Candle agent: "))
        assertNotNull(CandleAgentHost.view.value.status)
    }

    @Test fun itRunsAroundTheClockInBatchesNeverPerTick() {
        // Sunday noon: NSE and MCX shut. Not loaded (or let go for memory): it is not woken just to idle.
        Market.testClock = java.time.Clock.fixed(LocalDate.of(2026, 10, 18).atTime(12, 0).atZone(zone).toInstant(), zone)
        assertNull(CandleAgentHost.delayMs(context))
        // Loaded: a batch once an hour (none yet: now), asked again each minute so it never sleeps through the open.
        CandleAgentHost.resetForTest(agentWithReads())
        assertEquals(0L, CandleAgentHost.delayMs(context))
        // A Monday in NSE's session: a batch now (none yet), then every 5 minutes - never per tick.
        Market.testClock = java.time.Clock.fixed(day.atTime(11, 0).atZone(zone).toInstant(), zone)
        val d = CandleAgentHost.delayMs(context)
        if (Market.isTradingDay(day)) { assertNotNull(d); assertTrue("$d", d!! in 0L..5 * 60_000L) }
    }

    @Test fun itsReadsGoOnTheBusWithNoDirectionAndJarvisAnswersFromMemory() {
        val seed = CandleBook.seed(context.assets.open("knowledge/candle_seed.json").bufferedReader().use { it.readText() })
        val a = CandleAgent(seed)
        val now = day.atTime(14, 6)
        val ms = now.atZone(zone).toInstant().toEpochMilli()
        val posts = a.study(listOf(CandleAgent.Series("NIFTY", 5, bars())), emptyList(), ms, now, true).posts
        assertTrue(posts.isNotEmpty())
        for (p in posts) SmartWorkers.bus.post(p)
        CandleAgentHost.resetForTest(a)
        val mine = SmartWorkers.bus.recent(ms + 1, kinds = setOf(Findings.Kind.CANDLE))
        assertEquals(posts.size, mine.size)
        assertTrue(mine.all { it.direction == 0 && it.who == CandleAgent.WHO })
        assertEquals(0, Findings.consensus(SmartWorkers.bus.recent(ms + 1), "NIFTY", ms + 1).let { it.bullish + it.bearish })
        val why = CandleAgentHost.answer(CandleAgent.Question.WHY_COLOUR, "NIFTY", locked = false)
        assertTrue(why, "Green because" in why || "Red because" in why || "Flat" in why)
        val sup = CandleAgentHost.answer(CandleAgent.Question.SUPPORT, null, locked = true)
        assertTrue(sup, "support" in sup)
        val reply = CandleAgentHost.ask("the trade manager", CandleAgent.Ask.Levels("NIFTY"))!!
        assertNotNull(reply.support)
    }

    @Test fun theDailyStudyReadsOnlyKeptHistoryAndGoesFurtherBackEachNight() = runBlocking {
        // About 70 sessions of 5-minute candles, as the market study's two-year history holds them in memory.
        var calls = 0
        com.optionslab.app.ira.IraHub.testLabBars = { _, minutes ->
            calls++
            assertEquals("the study reads the kept 5-minute history only", 5, minutes)
            val r = java.util.Random(7)
            val out = ArrayList<com.optionslab.ira.Candle>()
            var d = day.minusDays(100); var px = 24_000.0
            while (d.isBefore(day.plusDays(2))) {
                if (d.dayOfWeek.value <= 5) {
                    var t = d.atTime(9, 15)
                    while (t.isBefore(d.atTime(15, 30))) {
                        val o = px; val c = o + r.nextGaussian() * 6
                        out += com.optionslab.ira.Candle(t, o, maxOf(o, c) + 3, minOf(o, c) - 3, c); px = c; t = t.plusMinutes(5)
                    }
                }
                d = d.plusDays(1)
            }
            out
        }
        com.optionslab.app.work.BackgroundBudget.testPhone = com.optionslab.ira.BudgetRule.Phone(80, true, 30.0, false)
        // Tuesday 06:00: NSE and MCX shut.
        Market.testClock = java.time.Clock.fixed(day.plusDays(1).atTime(6, 0).atZone(zone).toInstant(), zone)
        CandleAgentHost.studyOffHours(context)
        val first = CandleAgentHost.view.value.status!!.counted
        assertTrue("$first", first > 10_000)                                   // 60 sessions x 100 candles x 3 indices, 5 and 15 minutes
        assertTrue(CandleAgentHost.view.value.latest.isEmpty())                // studied, never announced
        assertEquals(3, calls)                                                 // one kept history per index, nothing fetched
        // Once a day.
        CandleAgentHost.studyOffHours(context)
        assertEquals(3, calls)
        // 22 Oct (the overnight kills): once a day after Android ended the app too - the day is kept on disk.
        CandleAgentHost.restartDayForTest()
        CandleAgentHost.studyOffHours(context)
        assertEquals(3, calls)
        // The next night: the sessions before the ones already counted - never the same candle twice, never today's.
        Market.testClock = java.time.Clock.fixed(day.plusDays(2).atTime(6, 0).atZone(zone).toInstant(), zone)
        CandleAgentHost.studyOffHours(context)
        val second = CandleAgentHost.view.value.status!!.counted
        assertTrue("$first -> $second", second > first && second - first < first / 2)
        // The battery saver on: it waits.
        Market.testClock = java.time.Clock.fixed(day.plusDays(3).atTime(6, 0).atZone(zone).toInstant(), zone)
        com.optionslab.app.work.BackgroundBudget.testPhone = com.optionslab.ira.BudgetRule.Phone(80, false, 30.0, true)
        CandleAgentHost.studyOffHours(context)
        assertEquals(second, CandleAgentHost.view.value.status!!.counted)
        // Never in NSE's session.
        com.optionslab.app.work.BackgroundBudget.testPhone = com.optionslab.ira.BudgetRule.Phone(80, true, 30.0, false)
        CandleAgentHost.resetForTest()
        Market.testClock = java.time.Clock.fixed(day.atTime(11, 0).atZone(zone).toInstant(), zone)
        assertTrue(Market.isOpen())
        val before = calls
        CandleAgentHost.studyOffHours(context)
        assertEquals(before, calls)
    }

    @Test fun mcxMinutesFoldFromNineWithTheirVolume() {
        val t0 = day.atTime(9, 0)
        val ones = (0 until 31).map { CandleRead.Bar(t0.plusMinutes(it.toLong()), 100.0 + it, 101.0 + it, 99.0 + it, 100.5 + it, 10.0) }
        val now = t0.plusMinutes(31)
        val five = CandleAgentHost.fold(ones, 5, now)
        assertEquals(6, five.size)                                            // 09:00 .. 09:25 closed; 09:30 still open
        assertEquals(LocalDateTime.of(day, java.time.LocalTime.of(9, 5)), five[1].t)
        assertEquals(50.0, five[0].v!!, 1e-9)
        assertEquals(105.0, five[1].o, 1e-9); assertEquals(110.0, five[1].h, 1e-9); assertEquals(104.0, five[1].l, 1e-9); assertEquals(109.5, five[1].c, 1e-9)
        assertEquals(31, CandleAgentHost.fold(ones, 1, now).size)
        assertTrue(CandleAgentHost.fold(emptyList(), 15, now).isEmpty())
    }

    /**
     * 20 Oct, review: the minutes come from a cache up to 10 minutes old while batches run every 5. Closed by the clock, the
     * 10:00 candle was read at 10:07 from the 2 minutes fetched at 10:02 (and never read again). Closed by the data: it waits.
     */
    @Test fun mcxCandlesCloseByTheFetchedMinutesNeverByTheClock() {
        val t0 = day.atTime(9, 50)
        val fetched = (0 until 12).map { CandleRead.Bar(t0.plusMinutes(it.toLong()), 100.0, 101.0, 99.0, 100.5, 10.0) }   // 09:50 .. 10:01
        val five = CandleAgentHost.fold(fetched, 5, day.atTime(10, 2, 30))
        assertEquals(listOf(day.atTime(9, 50), day.atTime(9, 55)), five.map { it.t })
        // After MCX's close the day's last candle, cut short by the close, is closed too.
        val settled = CandleAgentHost.fold(fetched, 15, day.atTime(23, 56), sessionOver = true)
        assertEquals(listOf(day.atTime(9, 45), day.atTime(10, 0)), settled.map { it.t })
        assertEquals(1, CandleAgentHost.fold(fetched, 15, day.atTime(23, 56)).size)
    }

    /** 20 Oct, review: a batch whose study failed returned before publishing, so the page never showed the error. */
    @Test fun aFailedBatchShowsItsErrorOnThePage() = runBlocking {
        Market.testClock = java.time.Clock.fixed(LocalDate.of(2026, 10, 18).atTime(12, 0).atZone(zone).toInstant(), zone)   // a Sunday
        CandleAgentHost.resetForTest(agentWithReads())
        assertNull(CandleAgentHost.view.value.status!!.lastError)
        CandleAgentHost.failNextForTest = true
        CandleAgentHost.batch(context)
        assertEquals("IllegalStateException", CandleAgentHost.view.value.status!!.lastError)
        assertTrue(CandleAgentHost.view.value.status!!.words().contains("last error"))
    }

    /** 20 Oct, review: after MCX's close with minutes still kept, the worker runs one more batch to settle them. */
    @Test fun mcxClosedWithMinutesKeptAsksForOneSettleBatch() {
        Market.testClock = java.time.Clock.fixed(LocalDate.of(2026, 10, 18).atTime(12, 0).atZone(zone).toInstant(), zone)   // a Sunday
        assertNull(CandleAgentHost.delayMs(context))
        CandleAgentHost.keepMcxForTest("CRUDEOIL", listOf(CandleRead.Bar(day.atTime(23, 20), 1.0, 1.0, 1.0, 1.0)))
        assertNotNull(CandleAgentHost.delayMs(context))
    }

    /**
     * 20 Oct, second review: the MCX settle runs once a session (a study that kept failing re-fetched every MCX series every 5
     * minutes all night): after one settle batch nothing more is asked for MCX.
     */
    @Test fun theMcxSettleRunsOnceASession() = runBlocking {
        Market.testClock = java.time.Clock.fixed(LocalDate.of(2026, 10, 18).atTime(12, 0).atZone(zone).toInstant(), zone)   // a Sunday
        CandleAgentHost.keepMcxForTest("CRUDEOIL", listOf(CandleRead.Bar(day.atTime(23, 20), 1.0, 1.0, 1.0, 1.0)))
        CandleAgentHost.batch(context)
        assertEquals(1, CandleAgentHost.mcxSettles)
        CandleAgentHost.batch(context)
        assertEquals("never twice", 1, CandleAgentHost.mcxSettles)
        // Its minutes let go: no more batches asked for MCX on a closed Sunday (the hourly off-hours one aside).
        CandleAgentHost.resetForTest()
        assertNull(CandleAgentHost.delayMs(context))
    }
}
