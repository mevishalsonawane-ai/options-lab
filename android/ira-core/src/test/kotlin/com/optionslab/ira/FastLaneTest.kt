package com.optionslab.ira

import com.optionslab.ira.FastLane.Ev
import com.optionslab.ira.FastLane.Lane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The event-driven checks' scheduling ([FastLane]): bursts collapse, candle closes, the per-lane gaps, the plan. */
class FastLaneTest {
    /** 11:00:00 IST on a weekday, as epoch ms (a 5-minute boundary). */
    private val t0 = java.time.ZonedDateTime.of(2026, 10, 9, 11, 0, 0, 0, java.time.ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
    private val all: (Long) -> Boolean = { true }
    private val held = FastLane.State(holding = true, liveHolding = false, armed = false, covered = true, minuteArms = false)

    @Test fun aBurstCollapsesToTheLatestPerInstrument() {
        val box = FastLane.Inbox()
        assertTrue(box.offer(Ev(1, t0, null)), "the first wakes the consumer")
        assertFalse(box.offer(Ev(1, t0 + 5, null)))
        assertFalse(box.offer(Ev(2, t0 + 6, null)))
        assertFalse(box.offer(Ev(1, t0 + 9, null)))
        assertEquals(2, box.size())
        assertEquals(listOf(Ev(1, t0 + 9, null), Ev(2, t0 + 6, null)), box.drain())
        assertEquals(0, box.size())
        assertEquals(emptyList(), box.drain())
        assertTrue(box.offer(Ev(3, t0, null)))
    }

    @Test fun aMinuteAndABarCloseAreToldByTheExchangeStamp() {
        val r = FastLane.Rolls()
        val s = t0 / 1000
        // First sight of an instrument closes nothing.
        assertEquals(FastLane.Roll(false, false, null), r.see(listOf(Ev(1, t0, s - 5))))
        assertEquals(FastLane.Roll(false, false, null), r.see(listOf(Ev(1, t0, s - 1))))
        // The first tick stamped 11:00:00 closes 10:59's minute and the 10:55-11:00 bar (the phone's clock is not asked).
        assertEquals(FastLane.Roll(true, true, t0), r.see(listOf(Ev(1, t0 - 400, s))))
        // Same minute again: nothing; an older stamp never goes back.
        assertEquals(FastLane.Roll(false, false, null), r.see(listOf(Ev(1, t0 + 900, s + 10), Ev(1, t0, s - 30))))
        // 11:01: a minute, not a bar.
        assertEquals(FastLane.Roll(true, false, t0 + 60_000), r.see(listOf(Ev(1, t0 + 60_100, s + 60))))
        // Without a stamp, the phone's arrival time is used.
        r.see(listOf(Ev(2, t0 + 61_000, null)))
        assertEquals(FastLane.Roll(true, false, t0 + 120_000), r.see(listOf(Ev(2, t0 + 120_050, null))))
        // A skipped stretch across a boundary still closes the bar.
        assertTrue(r.see(listOf(Ev(1, t0 + 400_000, s + 400))).bar)
    }

    @Test fun eachLaneRunsAtMostOncePerItsGapUnlessACandleClosed() {
        val g = FastLane.Debounce()
        assertTrue(g.due(Lane.STREAM, t0, 200))
        assertFalse(g.due(Lane.STREAM, t0 + 150, 200))
        assertEquals(50, g.left(Lane.STREAM, t0 + 150, 200))
        assertTrue(g.due(Lane.STREAM, t0 + 200, 200))
        assertEquals(0, g.left(Lane.LIVE, t0, 1_000))
        assertTrue(g.due(Lane.LIVE, t0, 1_000))
        // A candle close lets the 1-second lane run early, never within the floor.
        assertFalse(g.due(Lane.LIVE, t0 + 100, 1_000, force = true))
        assertTrue(g.due(Lane.LIVE, t0 + 250, 1_000, force = true))
        // A clock that went back is not a reason to wait forever.
        assertTrue(g.due(Lane.LIVE, t0 - 10_000, 1_000))
        assertEquals(0, g.left(Lane.LIVE, t0 + 5_000, 1_000))
    }

    @Test fun ticksOfHeldInstrumentsRunTheStreamLaneAndABurstWaitsItsGap() {
        val r = FastLane.Rolls(); val g = FastLane.Debounce()
        val p = FastLane.plan(listOf(Ev(9, t0 + 10, t0 / 1000)), r, g, t0 + 12, held, all)
        assertEquals(setOf(Lane.STREAM), p.lanes)
        assertEquals(t0 + 10, p.triggerMs)
        assertEquals(t0, p.triggerExchMs)
        assertNull(p.minuteAtMs); assertNull(p.retryMs)
        // 100 ms later: held back, looked at again when the gap ends.
        val q = FastLane.plan(listOf(Ev(9, t0 + 110, null)), r, g, t0 + 112, held, all)
        assertTrue(q.lanes.isEmpty())
        assertEquals(100, q.retryMs)
        assertNull(q.triggerExchMs)
        assertFalse(q.idle)
        val later = FastLane.plan(listOf(Ev(9, t0 + 110, null)), r, g, t0 + 212, held, all)
        assertEquals(setOf(Lane.STREAM), later.lanes)
    }

    @Test fun nothingRunsForOtherInstrumentsOrWhenNothingIsHeldOrPricesAreNotAllStreamed() {
        val r = FastLane.Rolls(); val g = FastLane.Debounce()
        val none = FastLane.plan(listOf(Ev(5, t0, null)), r, g, t0, held) { it == 9L }
        assertTrue(none.idle); assertNull(none.triggerMs)
        assertTrue(FastLane.plan(emptyList(), r, g, t0, held, all).idle)
        assertTrue(FastLane.plan(listOf(Ev(9, t0, null)), r, g, t0, held.copy(holding = false), all).lanes.isEmpty())
        // A paper holding without a stream price: the stream lane would read the candle feed, so it waits for the 15-second pass.
        assertTrue(FastLane.plan(listOf(Ev(9, t0, null)), r, g, t0, held.copy(covered = false, liveHolding = true), all).lanes.isEmpty())
    }

    @Test fun liveHoldingsRunTheLiveLaneEveryTwoSeconds() {
        val r = FastLane.Rolls(); val g = FastLane.Debounce()
        val st = held.copy(holding = false, liveHolding = true)
        assertEquals(setOf(Lane.LIVE), FastLane.plan(listOf(Ev(9, t0, null)), r, g, t0, st, all).lanes)
        val soon = FastLane.plan(listOf(Ev(9, t0 + 300, null)), r, g, t0 + 300, st, all)
        assertTrue(soon.lanes.isEmpty()); assertEquals(FastLane.LIVE_GAP_MS - 300, soon.retryMs)
        assertEquals(setOf(Lane.LIVE), FastLane.plan(listOf(Ev(9, t0 + 2_000, null)), r, g, t0 + FastLane.LIVE_GAP_MS, st, all).lanes)
    }

    @Test fun aBarCloseRunsTheEntriesOnceAndAMinuteCloseSchedulesTheMinuteLane() {
        val r = FastLane.Rolls(); val g = FastLane.Debounce()
        val s = t0 / 1000
        val st = FastLane.State(holding = true, liveHolding = true, armed = true, covered = true, minuteArms = true)
        FastLane.plan(listOf(Ev(1, t0 - 2_000, s - 2)), r, g, t0 - 2_000, st, all)
        // The first tick of 11:00 (stamped by the exchange): every lane, the stream and live lanes early (a candle closed).
        val p = FastLane.plan(listOf(Ev(1, t0 - 1_800, s)), r, g, t0 - 1_800, st, all)
        assertEquals(setOf(Lane.STREAM, Lane.LIVE, Lane.BAR_CLOSE), p.lanes)
        // The minute lane: 3 s after the close (by the phone's clock, at least now).
        assertEquals(t0 + FastLane.MINUTE_AFTER_MS, p.minuteAtMs)
        // 11:01: no bar; the minute lane once a minute only.
        val m = FastLane.plan(listOf(Ev(1, t0 + 60_200, s + 60)), r, g, t0 + 60_200, st, all)
        assertFalse(Lane.BAR_CLOSE in m.lanes)
        assertEquals(t0 + 63_000, m.minuteAtMs)
        val again = FastLane.plan(listOf(Ev(2, t0 + 60_300, s + 59), Ev(2, t0 + 60_400, s + 60)), r, g, t0 + 60_400, st, all)
        assertNull(again.minuteAtMs)
        // Not armed: a bar close runs no entries.
        val r2 = FastLane.Rolls(); val g2 = FastLane.Debounce()
        FastLane.plan(listOf(Ev(1, t0 - 2_000, s - 2)), r2, g2, t0, st.copy(armed = false, minuteArms = false), all)
        val un = FastLane.plan(listOf(Ev(1, t0, s)), r2, g2, t0 + 10_000, st.copy(armed = false, minuteArms = false), all)
        assertFalse(Lane.BAR_CLOSE in un.lanes); assertNull(un.minuteAtMs)
        // A late look (the phone 10 s after the close) runs the minute lane at once, not in the past.
        val r3 = FastLane.Rolls(); val g3 = FastLane.Debounce()
        FastLane.plan(listOf(Ev(1, t0 - 2_000, s - 2)), r3, g3, t0, st, all)
        assertEquals(t0 + 10_000, FastLane.plan(listOf(Ev(1, t0, s)), r3, g3, t0 + 10_000, st, all).minuteAtMs)
    }

    @Test fun liveExitsDecideAtTheStreamPaceWhileEveryLiveInstrumentIsFresh() {
        val r = FastLane.Rolls(); val g = FastLane.Debounce()
        val st = held.copy(holding = false, liveHolding = true, liveFresh = true)
        assertEquals(FastLane.STREAM_GAP_MS, FastLane.liveGap(st))
        assertEquals(FastLane.LIVE_GAP_MS, FastLane.liveGap(st.copy(liveFresh = false)))
        assertEquals(setOf(Lane.LIVE), FastLane.plan(listOf(Ev(9, t0, null)), r, g, t0, st, all).lanes)
        val soon = FastLane.plan(listOf(Ev(9, t0 + 120, null)), r, g, t0 + 120, st, all)
        assertTrue(soon.lanes.isEmpty()); assertEquals(80, soon.retryMs)
        assertEquals(setOf(Lane.LIVE), FastLane.plan(listOf(Ev(9, t0 + 200, null)), r, g, t0 + 200, st, all).lanes)
        // A stale live instrument: back to Zerodha's pace.
        val stale = FastLane.plan(listOf(Ev(9, t0 + 400, null)), r, g, t0 + 400, st.copy(liveFresh = false), all)
        assertTrue(stale.lanes.isEmpty()); assertEquals(FastLane.LIVE_GAP_MS - 200, stale.retryMs)
    }

    @Test fun lanesAreIndependentABusyLaneHoldsBackOnlyItself() {
        val r = FastLane.Rolls(); val g = FastLane.Debounce()
        val st = held.copy(liveHolding = true)
        val busy = FastLane.Busy()
        assertTrue(busy.start(Lane.LIVE))
        assertFalse(busy.start(Lane.LIVE), "never beside itself")
        // The live lane is stuck on a slow Zerodha read: the stream lane still runs on every tick's gap.
        val p = FastLane.plan(listOf(Ev(9, t0, null)), r, g, t0, st, busy.now(), all)
        assertEquals(setOf(Lane.STREAM), p.lanes)
        assertEquals(FastLane.LIVE_GAP_MS, p.retryMs, "the busy lane is looked at again after its gap")
        val q = FastLane.plan(listOf(Ev(9, t0 + 200, null)), r, g, t0 + 200, st, busy.now(), all)
        assertEquals(setOf(Lane.STREAM), q.lanes)
        // A busy bar-close lane is not run again; the minute lane is unaffected.
        busy.end(Lane.LIVE)
        assertEquals(emptySet(), busy.now())
        assertEquals(setOf(Lane.LIVE), FastLane.plan(listOf(Ev(9, t0 + 2_100, null)), r, g, t0 + 2_100, st, busy.now(), all).lanes - Lane.STREAM)
        val s = t0 / 1000
        val r2 = FastLane.Rolls(); val g2 = FastLane.Debounce()
        val armed = st.copy(armed = true, minuteArms = true)
        FastLane.plan(listOf(Ev(1, t0 - 2_000, s - 2)), r2, g2, t0 - 2_000, armed, all)
        val b = FastLane.plan(listOf(Ev(1, t0 + 10, s)), r2, g2, t0 + 10, armed, setOf(Lane.BAR_CLOSE), all)
        assertFalse(Lane.BAR_CLOSE in b.lanes)
        assertEquals(t0 + FastLane.MINUTE_AFTER_MS, b.minuteAtMs)
    }

    @Test fun withLocalCandlesTheMinuteLaneRunsAtTheCloseAndAgainFromTheFeed() {
        val r = FastLane.Rolls(); val g = FastLane.Debounce()
        val s = t0 / 1000
        val st = FastLane.State(holding = false, liveHolding = false, armed = false, covered = true, minuteArms = true, localCandles = true)
        FastLane.plan(listOf(Ev(1, t0 - 2_000, s - 2)), r, g, t0 - 2_000, st, all)
        val p = FastLane.plan(listOf(Ev(1, t0 + 40, s)), r, g, t0 + 40, st, all)
        assertEquals(t0 + 40, p.minuteAtMs, "at the first tick after the boundary")
        assertEquals(t0 + FastLane.MINUTE_AFTER_MS, p.minuteAgainMs, "the feed's run, the source of truth")
        assertFalse(p.idle)
        // A late first tick (after the feed's moment): one run, from the feed.
        val r2 = FastLane.Rolls(); val g2 = FastLane.Debounce()
        FastLane.plan(listOf(Ev(1, t0 - 2_000, s - 2)), r2, g2, t0 - 2_000, st, all)
        val late = FastLane.plan(listOf(Ev(1, t0 + 5_000, s + 4)), r2, g2, t0 + 5_000, st, all)
        assertEquals(t0 + 5_000, late.minuteAtMs); assertNull(late.minuteAgainMs)
        // Without local candles: as before, 3 s after the close, once.
        val r3 = FastLane.Rolls(); val g3 = FastLane.Debounce()
        FastLane.plan(listOf(Ev(1, t0 - 2_000, s - 2)), r3, g3, t0 - 2_000, st.copy(localCandles = false), all)
        val feed = FastLane.plan(listOf(Ev(1, t0 + 40, s)), r3, g3, t0 + 40, st.copy(localCandles = false), all)
        assertEquals(t0 + FastLane.MINUTE_AFTER_MS, feed.minuteAtMs); assertNull(feed.minuteAgainMs)
    }

    @Test fun theRouteIsWarmedFiveSecondsBeforeEachBarClose() {
        assertEquals(t0 + 295_000, FastLane.warmAt(t0))
        assertEquals(t0 + 295_000, FastLane.warmAt(t0 + 100_000))
        // Past this bar's warm moment: the next bar's.
        assertEquals(t0 + 595_000, FastLane.warmAt(t0 + 296_000))
        assertEquals(t0 + 590_000, FastLane.warmAt(t0 + 295_000, leadMs = 10_000))
    }
}
