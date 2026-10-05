package com.optionslab.ira

import java.time.LocalDateTime

/**
 * The last few readings of a whole candle history, kept by the candles they were read from (speed round 9): the
 * background pass folds every index's month of 1-minute bars into 15 and 60-minute candles for the book, then again
 * in [Brain.read] (twice there), and reads the same unchanged history again on every pass while the market is shut;
 * the sharp-move watch reads the earlier sessions' usual move on every pass all day.
 *
 * Exact, not approximate: a reading is looked up by what else it was read with ([of]'s key), the number of candles and
 * the last one's time and close, and is given back only when every candle is equal to those it was read from (a copy
 * is kept, so a list changed in place afterwards is never mistaken for the one read). Comparing the candles costs a
 * small part of reading them again. Only for readers whose answer depends on nothing but the candles and the key (no
 * clock, no stored state). A reader that throws keeps nothing. Nothing here acts.
 */
internal class BarsKept<V>(private val max: Int) {
    private class Entry<V>(val key: Any?, val size: Int, val lastT: LocalDateTime?, val lastC: Double?, val bars: List<Candle>, val value: V)

    private val kept = ArrayList<Entry<V>>()

    /** [read] of [bars] (with [key]), kept; the kept reading when the very same candles were read with the same key. */
    fun of(bars: List<Candle>, key: Any?, read: () -> V): V {
        val size = bars.size
        val last = bars.lastOrNull()
        val lastT = last?.t
        val lastC = last?.c
        synchronized(kept) {
            for (i in kept.indices) {
                val e = kept[i]
                if (e.size == size && e.lastT == lastT && e.lastC == lastC && e.key == key && e.bars == bars) {
                    if (i > 0) { kept.removeAt(i); kept.add(0, e) }
                    return e.value
                }
            }
        }
        val copy = ArrayList(bars)
        val v = read()
        synchronized(kept) {
            kept.add(0, Entry(key, size, lastT, lastC, copy, v))
            while (kept.size > max) kept.removeAt(kept.size - 1)
        }
        return v
    }

    /** How many readings are kept. */
    val size: Int get() = synchronized(kept) { kept.size }

    /** Every kept reading forgotten (tests). */
    fun clear() = synchronized(kept) { kept.clear() }
}
