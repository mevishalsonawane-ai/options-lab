package com.optionslab.ira

import com.optionslab.engine.risk.Protection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StopPriceTest {
    private val now = 90_000_000L

    @Test fun theStreamAlwaysWins() {
        assertEquals(StopPrice.Source.STREAM, StopPrice.source(hasStream = true, tickReadAtMs = now - 100, nowMs = now))
        assertEquals(StopPrice.Source.STREAM, StopPrice.source(hasStream = true, tickReadAtMs = null, nowMs = now))
    }

    @Test fun theTicksOwnReadOnlyWithinTwoSeconds() {
        assertEquals(StopPrice.Source.TICK_READ, StopPrice.source(false, now - 1_999, now))
        assertEquals(StopPrice.Source.TICK_READ, StopPrice.source(false, now, now))
        assertEquals(StopPrice.Source.FRESH, StopPrice.source(false, now - StopPrice.TICK_PRICE_MS, now), "2 s old: read afresh")
        assertEquals(StopPrice.Source.FRESH, StopPrice.source(false, now - 15_000, now), "the last pass's read: never")
        assertEquals(StopPrice.Source.FRESH, StopPrice.source(false, null, now), "not read by the tick")
        assertEquals(StopPrice.Source.FRESH, StopPrice.source(false, now + 500, now), "clock went back")
    }

    @Test fun onlyTheTicksVeryCandleReadIsHandedOn() {
        val candle = Any()
        assertTrue(StopPrice.handedOn(candle, candle))
        assertFalse(StopPrice.handedOn(Any(), candle), "the tick priced with a stream tick, or another read came in between")
        assertFalse(StopPrice.handedOn(null, candle))
        assertFalse(StopPrice.handedOn(candle, null))
    }

    @Test fun anOlderPriceNeverLoosensATrail() {
        // A long with a 10-point trail, the best seen so far 120 (stop 110): whatever price the stop is handed - the tick's
        // own read or a fresher one - it only ever stays or tightens.
        val long = Protection.Spec(direction = 1, stop = 110.0, trailPoints = 10.0, target = null, best = 120.0)
        for (px in listOf(100.0, 115.0, 120.0, 125.0)) assertTrue((Protection.next(long, px).stop ?: 0.0) >= 110.0, "price $px")
        assertEquals(115.0, Protection.next(long, 125.0).stop)
        // A short likewise.
        val short = Protection.Spec(direction = -1, stop = 90.0, trailPoints = 10.0, target = null, best = 80.0)
        for (px in listOf(70.0, 80.0, 95.0)) assertTrue((Protection.next(short, px).stop ?: 1e9) <= 90.0, "price $px")
    }
}

class StartChainTest {
    private val now = 90_000_000L

    @Test fun reusesARecentReadOfTheSameMode() {
        assertTrue(StartChain.reuse(now - 60_000, now, sameMode = true))
        assertFalse(StartChain.reuse(now - StartChain.MAX_AGE_MS, now, sameMode = true), "5 minutes old: priced afresh")
        assertFalse(StartChain.reuse(now - 60_000, now, sameMode = false), "read in the other mode")
        assertFalse(StartChain.reuse(null, now, sameMode = true), "never read: priced as before")
        assertFalse(StartChain.reuse(now + 1_000, now, sameMode = true), "clock went back")
    }
}

class QuietNewsWordsTest {
    @Test fun theLineSaysThePaceThatRuns() {
        val s = BatteryUse.Snapshot(listening = false, listenSaver = false, resting = false, watch = true, watchStepSec = 60,
            stream = "OFF", streamTokens = 0, modelLoaded = false, marketOpen = true, batteryPercent = null, charging = false, wordsQuiet = true)
        assertEquals("news every 20 min until you ask about it today", BatteryUse.quietNews(s))
        assertEquals("news every 10 min", BatteryUse.quietNews(s.copy(newsAskedToday = true)))
        assertEquals(20L, WordsPace.UNASKED_NEWS_MS / 60_000)
    }
}
