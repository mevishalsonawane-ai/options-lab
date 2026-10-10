package com.optionslab.engine

/**
 * The prices one instrument traded on Zerodha's stream, kept for a few minutes ([keepMs]) in buckets of [bucketMs]
 * (each its first price, highest and lowest). It answers what a look every 15 s cannot see on its own: the highest
 * price traded since a moment (the profit lock's best price, research/HUNT_H20.md F2) and where a resting sell stop
 * would have filled since it was placed or moved (F1: at its trigger, or at a bucket's first price that was already
 * under it). Thread-safe; prices that are not positive and finite are ignored.
 */
class TickPath(private val keepMs: Long = 5 * 60_000L, private val bucketMs: Long = 1_000L) {
    private class Bucket(val at: Long, val open: Double, var high: Double, var low: Double, var close: Double = open)

    private val buckets = ArrayDeque<Bucket>()

    /** One traded [price] at [atMs] (epoch ms). A tick stamped before the last bucket counts in that bucket. */
    @Synchronized fun record(price: Double, atMs: Long) {
        if (!(price.isFinite() && price > 0)) return
        val key = atMs - Math.floorMod(atMs, bucketMs)
        val last = buckets.lastOrNull()
        if (last != null && key <= last.at) {
            last.high = maxOf(last.high, price); last.low = minOf(last.low, price); last.close = price
        } else {
            buckets.addLast(Bucket(key, price, price, price))
        }
        val cut = buckets.last().at - keepMs
        while (buckets.first().at < cut) buckets.removeFirst()
    }

    /** The buckets that started after the one holding [sinceMs] (a price at that moment itself is the caller's own look). */
    private fun after(sinceMs: Long): List<Bucket> {
        val from = sinceMs - Math.floorMod(sinceMs, bucketMs)
        return buckets.filter { it.at > from }
    }

    /** The highest price traded after [sinceMs], or null when none is kept. */
    @Synchronized fun highSince(sinceMs: Long): Double? = after(sinceMs).maxOfOrNull { it.high }

    /**
     * Where a resting SELL stop at [trigger], resting since [sinceMs], filled: the first bucket after it that traded at or
     * under the trigger, at the trigger or at that bucket's first price when it was already under it. Null: not reached.
     */
    @Synchronized fun sellStopFill(sinceMs: Long, trigger: Double): Double? =
        after(sinceMs).firstOrNull { it.low <= trigger + 1e-9 }?.let { minOf(trigger, it.open) }

    /**
     * The 1-minute candle that started at [startMs] (epoch ms), from the prices kept: open, high, low, close; null when
     * none of its prices is kept (a bar-close decision then waits for the candle feed, [com.optionslab.engine.orb.BarClose]).
     */
    @Synchronized fun minute(startMs: Long): DoubleArray? {
        // Only a whole minute: the prices kept must reach back to its start (else its open is not known).
        if (buckets.isEmpty() || buckets.first().at > startMs) return null
        val inside = buckets.filter { it.at >= startMs && it.at < startMs + 60_000L }
        if (inside.isEmpty()) return null
        return doubleArrayOf(inside.first().open, inside.maxOf { it.high }, inside.minOf { it.low }, inside.last().close)
    }

    /** How many buckets are kept (tests). */
    @Synchronized fun size(): Int = buckets.size
}
