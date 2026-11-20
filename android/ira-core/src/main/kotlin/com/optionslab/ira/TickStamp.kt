package com.optionslab.ira

/**
 * 20 Nov 2026 (audit: "a burst of late ticks is stamped now"): when a price really is, for the freshness checks.
 *
 * The stream stamped every tick with the moment it reached the phone. After a stall or a reconnect Zerodha can send a burst of
 * packets that were seconds old when they arrived; each passed for "fresh" (2 s for a stop, 5 s for a screen) and a stop could be
 * judged on it. Each packet also carries the exchange's own stamp (whole seconds). The gap between the arrival and that stamp is
 * the clocks' offset plus the network's delay - steady, but unknown. This keeps the smallest gap seen lately for each exchange
 * segment ([WINDOW_MS], in [BUCKET_MS] steps, on the monotonic clock; the lower median of the steps' smallest gaps, so one bad
 * stamp cannot move it); a packet whose gap is more than [LATE_MS] above it was late
 * by that much, and is stamped back by it (less the stamp's own up-to-one-second rounding, so it is never made older than it is).
 * Every other packet keeps its arrival time, exactly as before: a normal stream changes nothing.
 *
 *  - No exchange stamp (some index packets): the arrival time, as before.
 *  - A wall clock set forward or back (the wall clock moved against the monotonic one by more than [JUMP_MS]): everything learnt
 *    is forgotten, so a step never makes every tick look late (or early).
 *  - Nothing learnt yet: the arrival time.
 *
 * Memory only, a few longs per segment; one call per packet on the stream's thread, no allocation after the first packet of a
 * segment. Thread-safe (one lock, never contended in the app: one socket thread).
 */
class TickStamp {
    companion object {
        /** A packet this much later than the steadiest packets of its segment was late. */
        const val LATE_MS = 3_000L
        /** How long the smallest gap is remembered. */
        const val WINDOW_MS = 300_000L
        const val BUCKET_MS = 10_000L
        private const val BUCKETS = (WINDOW_MS / BUCKET_MS).toInt()
        /** The wall clock moved against the monotonic clock by more than this: a clock step. */
        const val JUMP_MS = 1_000L
    }

    private class Segment {
        val ids = LongArray(BUCKETS) { Long.MIN_VALUE }
        val mins = LongArray(BUCKETS)
        /** Room to sort the buckets' gaps in (no allocation per packet). */
        val scratch = LongArray(BUCKETS)
        /** The last smallest gap, kept when every bucket has gone quiet (a stream down for minutes). */
        var last: Long? = null
    }

    private val segments = HashMap<Int, Segment>()
    private var skew: Long? = null

    /**
     * The time to keep for a packet of [segment] that reached the phone at [nowMs] (wall clock; [monoMs] the monotonic clock at
     * the same moment) with the exchange stamp [exchangeSec] (epoch seconds; null or 0: none). Never later than [nowMs].
     */
    @Synchronized
    fun at(segment: Int, nowMs: Long, monoMs: Long, exchangeSec: Long?): Long {
        val sk = nowMs - monoMs
        val was = skew
        if (was != null && kotlin.math.abs(sk - was) > JUMP_MS) segments.clear()
        skew = sk
        if (exchangeSec == null || exchangeSec <= 0) return nowMs
        val gap = nowMs - exchangeSec * 1000L
        val s = segments.getOrPut(segment) { Segment() }
        val id = Math.floorDiv(monoMs, BUCKET_MS)
        val slot = Math.floorMod(id, BUCKETS.toLong()).toInt()
        if (s.ids[slot] != id) { s.ids[slot] = id; s.mins[slot] = gap } else if (gap < s.mins[slot]) s.mins[slot] = gap
        // Review, 20 Nov 2026: the lower median of the buckets' smallest gaps, not the smallest of all - one packet with a bad stamp
        // (a second or more ahead) would otherwise make every packet of its segment look late for the whole window, and every stop
        // read would go to Zerodha's quote call. Each bucket's smallest gap is the steady one whenever any of its packets was on time.
        var n = 0
        for (i in 0 until BUCKETS) if (s.ids[i] > id - BUCKETS && s.ids[i] <= id) s.scratch[n++] = s.mins[i]
        java.util.Arrays.sort(s.scratch, 0, n)
        val least = s.scratch[(n - 1) / 2]
        // (This packet's own bucket is always in, so n >= 1.) After a quiet longer than the window only this bucket is
        // filled - by the very burst that may be late - so the floor learnt before the quiet is carried across it.
        val floor = if (s.quietBefore(id)) minOf(s.last ?: least, least) else least
        s.last = floor
        val late = gap - floor
        return if (late > LATE_MS) (nowMs - late + 999L).coerceAtMost(nowMs) else nowMs
    }

    /** Whether this segment had no packet in the window before bucket [id] (only this packet's bucket is filled). */
    private fun Segment.quietBefore(id: Long): Boolean {
        for (i in 0 until BUCKETS) if (ids[i] > id - BUCKETS && ids[i] < id) return false
        return true
    }

    /** Everything learnt forgotten (tests). */
    @Synchronized fun clear() { segments.clear(); skew = null }
}
