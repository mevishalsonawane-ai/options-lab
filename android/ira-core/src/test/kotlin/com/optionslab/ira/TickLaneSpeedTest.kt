package com.optionslab.ira

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tick path after the parse (18 Oct, "should work at its minimum time"), benchmarked on synthetic ticks: 200
 * instruments, 50 ticks a frame, 2,500 ticks a second. Per tick on the stream's socket thread: the event-driven checks'
 * inbox ([FastLane.Inbox.offer]), the local candles ([LiveCandles.record]) and the screens' redraw gate ([Coalescer]); per
 * look of the consumer: the inbox drained and the lanes planned ([FastLane.plan]).
 *
 * Each stage prints its median and p99. The gates are generous wall-clock ceilings (~50x a laptop's figure) and
 * deterministic counts: memory stays bounded however long the session runs, and the screens redraw at most 4 times a second
 * whatever the tick rate.
 */
class TickLaneSpeedTest {
    private val instruments = 200
    private val perFrame = 50
    private fun token(i: Int) = (12_000_000L + i) * 256 + 2

    private val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private fun allocated(): Long = mx.getThreadAllocatedBytes(Thread.currentThread().id)

    private fun measure(rounds: Int, perBatch: Int, batch: (Int) -> Unit): Triple<Double, Double, Double> {
        repeat(rounds) { batch(it) }
        val per = DoubleArray(rounds)
        val a0 = allocated()
        for (r in 0 until rounds) {
            val t0 = System.nanoTime()
            batch(rounds + r)
            per[r] = (System.nanoTime() - t0).toDouble() / perBatch
        }
        val bytes = (allocated() - a0).toDouble() / (rounds.toLong() * perBatch)
        per.sort()
        return Triple(per[rounds / 2], per[(rounds * 99) / 100], bytes)
    }

    private fun report(stage: String, r: Triple<Double, Double, Double>) =
        println("LATENCY tick path %-34s median %8.1f ns  p99 %8.1f ns  %7.0f bytes per tick".format(stage, r.first, r.second, r.third))

    @Test fun `the inbox takes a tick in nanoseconds and holds one per instrument at most`() {
        val inbox = FastLane.Inbox()
        var drained = 0
        val r = measure(4_000, perFrame) { f ->
            val now = 1_790_000_000_000L + f * 20L
            for (k in 0 until perFrame) inbox.offer(FastLane.Ev(token((f * perFrame + k) % instruments), now, now / 1000))
            // The consumer drains about every fourth frame (it may lag behind a burst; the inbox keeps only the latest).
            if (f % 4 == 3) drained += inbox.drain().size
            assertTrue(inbox.size() <= instruments)
        }
        report("FastLane.Inbox.offer", r)
        assertTrue(drained > 0)
        assertTrue(r.first < 20_000.0, "an offer took ${r.first} ns (ceiling 20 us)")
    }

    @Test fun `a look plans the lanes in microseconds`() {
        val rolls = FastLane.Rolls()
        val gate = FastLane.Debounce()
        val st = FastLane.State(holding = true, liveHolding = true, armed = true, covered = true, minuteArms = true, liveFresh = true)
        val held = (0 until 40).map { token(it) }.toHashSet()
        var lanes = 0
        val r = measure(4_000, 1) { f ->
            val now = 1_790_000_000_000L + f * 50L
            val evs = (0 until perFrame).map { k -> FastLane.Ev(token((f * perFrame + k) % instruments), now, now / 1000) }
            lanes += FastLane.plan(evs, rolls, gate, now, st) { it in held }.lanes.size
        }
        report("FastLane.plan (one look, 50 ticks)", r)
        assertTrue(lanes > 0)
        assertTrue(r.first < 500_000.0, "a plan took ${r.first} ns (ceiling 500 us)")
    }

    @Test fun `the local candles take a tick in nanoseconds and stay bounded over a whole day`() {
        val c = LiveCandles()
        val day0 = 1_790_000_000L
        // A whole session at 2,500 ticks a second would be 56 million; one tick a second per instrument for 6h15m is the
        // same number of minutes per instrument, which is what the memory holds.
        val r = measure(22_500, instruments) { s ->
            val sec = day0 + s
            for (i in 0 until instruments) c.record(token(i), 200.0 + (s % 50) * 0.05, sec)
        }
        report("LiveCandles.record", r)
        assertTrue(r.first < 20_000.0, "a candle tick took ${r.first} ns (ceiling 20 us)")
        assertEquals(instruments, c.instruments())
        // Two hours per instrument at most, however long the day ran.
        for (i in 0 until instruments) {
            val last = c.latest(token(i))!!
            assertTrue(c.candle(token(i), LiveCandles.minuteOf(last - LiveCandles.KEEP_MINUTES * 60L) * 60) == null,
                "instrument $i kept more than ${LiveCandles.KEEP_MINUTES} minutes")
        }
        // Allocation per tick: a new candle a minute per instrument, nothing per tick in between.
        assertTrue(r.third < 160.0, "a candle tick allocated ${r.third} bytes (ceiling 160)")
    }

    @Test fun `the screens redraw at most four times a second whatever the tick rate`() {
        val gate = Coalescer()
        var emitted = 0
        var scheduled: Long? = null
        val start = 1_790_000_000_000L
        // Ten minutes of ticks every 2 ms (500 a second, the socket's frames), the scheduled redraw fired on time.
        var t = start
        while (t < start + 600_000L) {
            scheduled?.let { if (t >= it) { gate.fired(it); emitted++; scheduled = null } }
            val d = gate.offer(t)
            if (d.now) emitted++
            d.scheduleAt?.let { scheduled = it }
            t += 2
        }
        assertTrue(emitted <= 600 * 4 + 1, "the screens redrew $emitted times in 600 s (at most 4 a second)")
        assertTrue(emitted >= 600 * 4 - 5, "the screens fell behind: $emitted redraws in 600 s")
    }
}
