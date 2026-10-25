package com.optionslab.ira

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OPEN POSITIONS AND ORDERS COME FIRST (memory audit, 14 Oct 2026): what each trim level lets go - never the position watch
 * at any level - the model's memory rules, and the start-up order (the positions before anything, the knowledge last).
 */
class MemoryPlanTest {
    private val levels = listOf(0, MemoryPlan.RUNNING_MODERATE, MemoryPlan.RUNNING_LOW, MemoryPlan.RUNNING_CRITICAL, MemoryPlan.UI_HIDDEN,
        MemoryPlan.BACKGROUND, MemoryPlan.MODERATE, MemoryPlan.COMPLETE)

    @Test fun noTrimLevelEverLetsThePositionWatchGo() {
        // Every level Android has, and every number between: only items that are made again when next needed, none of them
        // the watch, its prices, its checks, their cadence, the order flow or candles of what is held, or the paper book.
        val watchWords = Regex("position|order|stop|target|trail|lock|square|guard|paper|watch|cadence|held|armed", RegexOption.IGNORE_CASE)
        for (level in -1..100) {
            val items = MemoryPlan.releases(level)
            assertEquals(items.distinct(), items, "level $level")
            for (i in items) assertFalse(watchWords.containsMatchIn(i.words), "level $level lets go ${i.words}")
            assertTrue(items.none { it.name in MemoryPlan.Never.entries.map { n -> n.name } }, "level $level")
        }
        // The never list names each part of Boss's rule.
        val never = MemoryPlan.Never.entries.joinToString(" ") { it.words }
        for (w in listOf("position watch", "prices", "stop", "target", "trail", "lock", "square-off", "guard", "cadence", "order flow", "candles", "paper book"))
            assertTrue(w in never, w)
    }

    @Test fun eachLevelLetsGoWhatItShould() {
        assertEquals(emptyList(), MemoryPlan.releases(0))
        assertEquals(listOf(MemoryPlan.Item.MODEL, MemoryPlan.Item.CANDLE_AGENT, MemoryPlan.Item.NTFY, MemoryPlan.Item.ANSWER_CACHE, MemoryPlan.Item.MOVE_SUMMARIES), MemoryPlan.releases(MemoryPlan.RUNNING_MODERATE))
        // The screen hidden: only the model (the biggest; the screen that used it is gone).
        assertEquals(listOf(MemoryPlan.Item.MODEL), MemoryPlan.releases(MemoryPlan.UI_HIDDEN))
        for (l in listOf(MemoryPlan.RUNNING_LOW, MemoryPlan.RUNNING_CRITICAL, MemoryPlan.BACKGROUND, MemoryPlan.MODERATE, MemoryPlan.COMPLETE))
            assertEquals(MemoryPlan.Item.entries, MemoryPlan.releases(l), "level $l")
        // Biggest first.
        assertEquals(MemoryPlan.Item.MODEL, MemoryPlan.releases(MemoryPlan.BACKGROUND).first())
        // Levels Android may add between the known ones read as the one below.
        assertEquals(MemoryPlan.releases(MemoryPlan.BACKGROUND), MemoryPlan.releases(50))
        assertEquals(MemoryPlan.releases(MemoryPlan.UI_HIDDEN), MemoryPlan.releases(30))
    }

    @Test fun theScreenHiddenIsNotCountedAndBackgroundTrimsAreNamedSo() {
        assertFalse(MemoryPlan.counted(0)); assertFalse(MemoryPlan.counted(MemoryPlan.UI_HIDDEN))
        for (l in listOf(MemoryPlan.RUNNING_MODERATE, MemoryPlan.RUNNING_LOW, MemoryPlan.RUNNING_CRITICAL, MemoryPlan.BACKGROUND, MemoryPlan.COMPLETE))
            assertTrue(MemoryPlan.counted(l), "$l")
        assertEquals("background trim", MemoryPlan.kind(MemoryPlan.BACKGROUND))
        assertEquals("background trim", MemoryPlan.kind(MemoryPlan.COMPLETE))
        assertEquals("memory trim", MemoryPlan.kind(MemoryPlan.RUNNING_LOW))
    }

    @Test fun theKnowledgeWaitsForTheCloseUnderPressureOrSaverInMarketHours() {
        val now = 1_000_000_000L
        assertFalse(MemoryPlan.underPressure(0L, now), "no trim yet")
        assertTrue(MemoryPlan.underPressure(now - 60_000L, now))
        assertTrue(MemoryPlan.underPressure(now - MemoryPlan.PRESSURE_MS + 1, now))
        assertFalse(MemoryPlan.underPressure(now - MemoryPlan.PRESSURE_MS, now))
        assertFalse(MemoryPlan.underPressure(now + 5_000L, now), "a clock that went back")
        // Outside market hours (the market closed, a holiday, the weekend): always now.
        for (p in listOf(false, true)) for (s in listOf(false, true)) assertTrue(MemoryPlan.knowledgeNow(false, p, s))
        assertTrue(MemoryPlan.knowledgeNow(true, pressure = false, saver = false))
        assertFalse(MemoryPlan.knowledgeNow(true, pressure = true, saver = false))
        assertFalse(MemoryPlan.knowledgeNow(true, pressure = false, saver = true))
    }

    @Test fun theModelLeavesSoonerAndIsNotLoadedAheadInMarketHours() {
        assertEquals(3 * 60_000L, ModelMemory.idleMs(marketHours = false, listening = false))
        assertEquals(60_000L, ModelMemory.idleMs(marketHours = true, listening = false))
        assertEquals(60_000L, ModelMemory.idleMs(marketHours = false, listening = true))
        assertTrue(ModelMemory.IDLE_MS < 10 * 60_000L, "was 10 minutes")
        assertFalse(ModelMemory.preload(marketHours = true)); assertTrue(ModelMemory.preload(marketHours = false))
        assertFalse(ModelMemory.coldLoadForRewrite(marketHours = true)); assertTrue(ModelMemory.coldLoadForRewrite(marketHours = false))
    }

    // ---- the start-up order -------------------------------------------------------------------------------------------------

    @Test fun thePositionsAreAlwaysFirstAndTheKnowledgeLast() {
        val o = StartOrder()
        // The knowledge and the stream finish quickly, before the positions: they are held until the positions are done.
        o.done(StartOrder.Step.KNOWLEDGE, 5)
        o.done(StartOrder.Step.STREAM, 7)
        assertEquals(emptyList(), o.log())
        assertFalse(o.isDone(StartOrder.Step.STREAM))
        assertNull(o.positionsWatchedAfter(0))
        o.done(StartOrder.Step.POSITIONS, 40)
        assertEquals(listOf(StartOrder.Step.POSITIONS, StartOrder.Step.STREAM, StartOrder.Step.KNOWLEDGE), o.log().map { it.first })
        // A step held never reads as earlier than the one before it.
        assertEquals(listOf(40L, 40L, 40L), o.log().map { it.second })
        assertEquals(40L, o.positionsWatchedAfter(0))
        // Marked twice: the first counts.
        o.done(StartOrder.Step.POSITIONS, 99)
        assertEquals(3, o.log().size); assertEquals(40L, o.log().first().second)
    }

    @Test fun theStepsInOrderAreLoggedAsTheyEnd() {
        val o = StartOrder()
        o.done(StartOrder.Step.POSITIONS, 10)
        assertTrue(o.isDone(StartOrder.Step.POSITIONS))
        o.done(StartOrder.Step.STREAM, 30)
        o.done(StartOrder.Step.KNOWLEDGE, 900)
        assertEquals(listOf(10L, 30L, 900L), o.log().map { it.second })
        assertEquals(5L, o.positionsWatchedAfter(5))
    }

    @Test fun theKnowledgeLoadWaitsUntilThePositionsAndTheStreamAreDone() = runBlocking {
        val o = StartOrder()
        val events = java.util.Collections.synchronizedList(ArrayList<String>())
        val knowledge = async { o.await(StartOrder.Step.STREAM); events += "knowledge loads"; o.done(StartOrder.Step.KNOWLEDGE, 3) }
        repeat(5) { yield() }
        assertTrue(events.isEmpty(), "nothing before the positions")
        o.done(StartOrder.Step.STREAM, 1)
        repeat(5) { yield() }
        assertTrue(events.isEmpty(), "the stream marked first is held for the positions")
        events += "positions watched"
        o.done(StartOrder.Step.POSITIONS, 2)
        knowledge.await()
        assertEquals(listOf("positions watched", "knowledge loads"), events)
        assertEquals(StartOrder.Step.entries, o.log().map { it.first })
        assertEquals(2L, o.await(StartOrder.Step.POSITIONS))
    }
}
