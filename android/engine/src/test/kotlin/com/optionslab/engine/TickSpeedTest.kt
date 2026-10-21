package com.optionslab.engine

import com.optionslab.engine.risk.Protection
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tick path's engine stages, benchmarked (18 Oct, "should work at its minimum time"): Zerodha's binary frame parsed
 * ([KiteTicks.parse]), each instrument's price path kept ([TickPath.record]), and per open position the profit lock's best
 * price and the resting stop's fill ([TickPath.highSince], [TickPath.sellStopFill]) and its stop/target/trail
 * ([Protection.next], [Protection.hit]). Synthetic ticks: 200 instruments in full mode (the 184-byte packet with depth), 50
 * packets a frame, 40 open positions.
 *
 * Each stage prints its median and p99 per tick. The gates are deterministic or generous so a slow CI runner never trips
 * them: the bytes allocated per tick (the JVM's own count for this thread, not a clock), and a wall-clock ceiling about 50x
 * what the stage takes on a laptop.
 */
class TickSpeedTest {
    private val instruments = 200
    private val perFrame = 50
    private val positions = 40

    private fun full(token: Long, ltpPaise: Int, exch: Int): ByteArray = ByteBuffer.allocate(184).apply {
        putInt(token.toInt()); putInt(ltpPaise); putInt(15); putInt(ltpPaise - 50); putInt(123_456); putInt(900); putInt(800)
        putInt(ltpPaise - 500); putInt(ltpPaise + 700); putInt(ltpPaise - 900); putInt(ltpPaise - 200)
        putInt(exch - 1); putInt(654_321); putInt(700_000); putInt(600_000); putInt(exch)
        repeat(5) { i -> putInt(75 * (i + 1)); putInt(ltpPaise - 5 * (i + 1)); putShort((i + 1).toShort()); putShort(0) }
        repeat(5) { i -> putInt(60 * (i + 1)); putInt(ltpPaise + 5 * (i + 1)); putShort((i + 2).toShort()); putShort(0) }
    }.array()

    private fun frame(packets: List<ByteArray>): ByteArray {
        val b = ByteBuffer.allocate(2 + packets.sumOf { 2 + it.size })
        b.putShort(packets.size.toShort())
        packets.forEach { b.putShort(it.size.toShort()); b.put(it) }
        return b.array()
    }

    /** NFO option tokens (segment 2). */
    private fun token(i: Int) = (12_000_000L + i) * 256 + 2

    private val frames: List<ByteArray> by lazy {
        val rnd = java.util.Random(7)
        (0 until 400).map { f ->
            frame((0 until perFrame).map { k ->
                val i = (f * perFrame + k) % instruments
                full(token(i), 20_000 + rnd.nextInt(2_000), 1_790_000_000 + f)
            })
        }
    }

    private val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private fun allocated(): Long = mx.getThreadAllocatedBytes(Thread.currentThread().id)

    /** Per-tick timings of [rounds] batches of [perBatch] ticks each, after a warm-up: (median ns, p99 ns, bytes per tick). */
    private fun measure(rounds: Int, perBatch: Int, batch: (Int) -> Unit): Triple<Double, Double, Double> {
        repeat(rounds) { batch(it) }                  // warm the JIT
        val per = DoubleArray(rounds)
        val a0 = allocated()
        for (r in 0 until rounds) {
            val t0 = System.nanoTime()
            batch(r)
            per[r] = (System.nanoTime() - t0).toDouble() / perBatch
        }
        val bytes = (allocated() - a0).toDouble() / (rounds.toLong() * perBatch)
        per.sort()
        return Triple(per[rounds / 2], per[(rounds * 99) / 100], bytes)
    }

    private fun report(stage: String, r: Triple<Double, Double, Double>) =
        println("LATENCY tick path %-34s median %8.1f ns  p99 %8.1f ns  %7.0f bytes per tick".format(stage, r.first, r.second, r.third))

    @Test fun `a frame of full packets parses in well under a microsecond a tick`() {
        var sink = 0L
        val r = measure(frames.size * 5, perFrame) { sink += KiteTicks.parse(frames[it % frames.size]).size }
        report("parse (full packet + depth)", r)
        assertTrue(sink > 0)
        assertTrue(r.first < 50_000.0, "parse took ${r.first} ns a tick (ceiling 50 us)")
        // The tick itself (with its boxed optional fields), its depth and its two five-level lists are kept: ~770 bytes. Before
        // 18 Oct each packet was also copied into its own array and buffer and its depth built through two passes (1,219 bytes).
        assertTrue(r.third < 1_050.0, "parse allocated ${r.third} bytes a tick (ceiling 1,050; 1,174-1,320 before the in-place read)")
    }

    @Test fun `the price paths record each tick without allocating per tick`() {
        val paths = Array(instruments) { TickPath() }
        val ticks = frames.map { KiteTicks.parse(it) }
        var at = 1_790_000_000_000L
        val r = measure(ticks.size * 5, perFrame) { f ->
            for (t in ticks[f % ticks.size]) paths[((t.token / 256) - 12_000_000L).toInt()].record(t.last, at)
            at += 20      // 50 ticks a frame, a frame every 20 ms: 2,500 ticks a second
        }
        report("TickPath.record", r)
        assertTrue(r.first < 20_000.0, "record took ${r.first} ns a tick (ceiling 20 us)")
        // One small bucket a second per instrument at most: well under 100 bytes a tick on average.
        assertTrue(r.third < 100.0, "record allocated ${r.third} bytes a tick (ceiling 100)")
        // Bounded: five minutes of one-second buckets at most, however long the session ran.
        assertTrue(paths.all { it.size() <= 301 }, "a path grew past five minutes: ${paths.maxOf { it.size() }}")
    }

    @Test fun `the open positions' lock and stop looks are cheap and allocate nothing`() {
        val path = TickPath()
        val start = 1_790_000_000_000L
        // A full five minutes kept (300 buckets): the worst case for a look.
        for (s in 0 until 300 * 4) path.record(200.0 + (s % 37) * 0.05, start + s * 250L)
        val since = start + 30_000L
        var sink = 0.0
        val r = measure(4_000, positions) {
            for (p in 0 until positions) {
                sink += path.highSince(since + p * 1_000L) ?: 0.0
                sink += path.sellStopFill(since + p * 1_000L, 190.0) ?: 0.0
            }
        }
        report("highSince + sellStopFill (5 min)", r)
        assertTrue(sink > 0)
        assertTrue(r.first < 100_000.0, "a position's looks took ${r.first} ns (ceiling 100 us)")
        // Only the boxed answer (before 18 Oct each look copied the kept buckets into a new list: 7,978 bytes a position).
        assertTrue(r.third < 64.0, "a position's looks allocated ${r.third} bytes (ceiling 64)")
        val m = measure(4_000, 1) { sink += path.minute(start + 60_000L)?.get(3) ?: 0.0 }
        report("TickPath.minute", m)
        assertTrue(m.third < 200.0, "a minute candle allocated ${m.third} bytes (ceiling 200: its four prices)")
    }

    @Test fun `each position's stop, target and trail is judged in nanoseconds`() {
        var specs = Array(positions) { Protection.Spec(1, 160.0, 30.0, 260.0, 200.0) }
        val prices = DoubleArray(1_000) { 195.0 + (it % 101) * 0.1 }
        var hits = 0
        val r = measure(4_000, positions) { f ->
            val px = prices[f % prices.size]
            for (p in 0 until positions) {
                specs[p] = Protection.next(specs[p], px)
                if (Protection.hit(specs[p], px) != null) hits++
            }
        }
        report("Protection.next + hit", r)
        assertTrue(r.first < 20_000.0, "a protection look took ${r.first} ns (ceiling 20 us)")
        assertEquals(0, hits, "the synthetic prices never reach the stop or the target")
        specs = specs.map { it.copy(stop = 205.0) }.toTypedArray()
        assertEquals("stop", Protection.hit(specs[0], 204.0))
    }

    @Test fun `parsing a frame in place gives exactly what parsing each packet gives`() {
        for (f in frames.take(20)) {
            val whole = KiteTicks.parse(f)
            val b = ByteBuffer.wrap(f)
            val n = b.short.toInt()
            val one = (0 until n).map { val len = b.short.toInt(); KiteTicks.packet(ByteArray(len).also { b.get(it) })!! }
            assertEquals(one, whole)
        }
    }
}
