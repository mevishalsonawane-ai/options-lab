package com.optionslab.ira

import com.optionslab.engine.KiteTicks

/**
 * Time & sales and the liquidity heatmap of one followed instrument (Boss, 9 Oct 2026): DISPLAY ONLY.
 *
 * [Tape]: each volume change of the stream as one print - its time (the exchange's last-trade time when the packet has it),
 * price, quantity and tick-rule side; a print over [BIG_X] × the median size is big. Kite sends snapshots, not every trade:
 * several trades between two packets come as one print.
 *
 * [Heatmap]: the 5-level book once a second (the bids and offers resting at each price) for the last [Heatmap.SECONDS],
 * with dots where size was pulled from the book without trading (the trap guard's pulls) and where a big print traded.
 * Memory is fixed (ring buffers of floats and ints); [Heatmap.frame] downsamples it for drawing. Not thread-safe by itself
 * (the board's lock guards both).
 */
object TapeHeat {
    /** A print this many times the median size (of the last 200) is big. */
    const val BIG_X = 5.0

    data class Print(val ms: Long, val price: Double, val qty: Long, val side: Int, val big: Boolean)

    /** Big: over [BIG_X] × [median] (null median: nothing is big yet). */
    fun big(qty: Long, median: Double?): Boolean = median != null && median > 0 && qty > BIG_X * median

    class Tape(private val cap: Int = 400) {
        private val ring = ArrayDeque<Print>()
        fun add(p: Print) { ring.addLast(p); while (ring.size > cap) ring.removeFirst() }
        /** The latest [n] prints, newest first. */
        fun latest(n: Int = cap): List<Print> = ring.toList().asReversed().take(n)
    }

    enum class DotKind { PULL_BID, PULL_ASK, BIG_BUY, BIG_SELL }

    data class Dot(val sec: Long, val price: Double, val qty: Long, val kind: DotKind)

    /** One drawn column: its first second, and each price's largest resting size (bids and offers) over its seconds. */
    data class Column(val sec: Long, val bids: Map<Double, Long>, val asks: Map<Double, Long>)

    data class Frame(val columns: List<Column>, val dots: List<Dot>, val maxQty: Long, val low: Double, val high: Double)

    class Heatmap(private val seconds: Int = SECONDS) {
        private val secs = LongArray(seconds) { -1 }
        private val px = FloatArray(seconds * LEVELS)
        private val qty = IntArray(seconds * LEVELS)
        private val dots = ArrayDeque<Dot>()

        /** The book at second [sec]: up to 5 bids and 5 offers (a second already taken is overwritten). */
        fun snap(sec: Long, bids: List<KiteTicks.Level>, asks: List<KiteTicks.Level>) {
            val i = Math.floorMod(sec, seconds.toLong()).toInt()
            secs[i] = sec
            for (k in 0 until LEVELS) {
                val l = if (k < 5) bids.getOrNull(k) else asks.getOrNull(k - 5)
                px[i * LEVELS + k] = l?.price?.toFloat() ?: 0f
                qty[i * LEVELS + k] = (l?.qty ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            }
        }

        fun dot(d: Dot) {
            dots.addLast(d)
            while (dots.size > MAX_DOTS || (dots.isNotEmpty() && d.sec - dots.first().sec >= seconds)) dots.removeFirst()
        }

        /** The last [seconds] to [nowSec] in at most [maxCols] columns (each the max size at each price over its seconds). */
        fun frame(nowSec: Long, maxCols: Int = 300): Frame {
            val from = nowSec - seconds + 1
            val per = maxOf(1, (seconds + maxCols - 1) / maxCols)
            val cols = java.util.TreeMap<Long, Pair<HashMap<Double, Long>, HashMap<Double, Long>>>()
            var mx = 0L; var lo = Double.MAX_VALUE; var hi = 0.0
            for (i in 0 until seconds) {
                val s = secs[i]
                if (s < from || s > nowSec) continue
                val c = cols.getOrPut(from + (s - from) / per * per) { HashMap<Double, Long>() to HashMap() }
                for (k in 0 until LEVELS) {
                    val p = px[i * LEVELS + k].toDouble(); val q = qty[i * LEVELS + k].toLong()
                    if (p <= 0 || q <= 0) continue
                    val key = Math.round(p * 100) / 100.0
                    val m = if (k < 5) c.first else c.second
                    m[key] = maxOf(m[key] ?: 0L, q)
                    mx = maxOf(mx, q); lo = minOf(lo, key); hi = maxOf(hi, key)
                }
            }
            val ds = dots.filter { it.sec in from..nowSec }
            return Frame(cols.map { (s, v) -> Column(s, v.first, v.second) }, ds, mx, if (lo == Double.MAX_VALUE) 0.0 else lo, hi)
        }

        companion object {
            /** 30 minutes at 1 s. */
            const val SECONDS = 1800
            const val LEVELS = 10
            const val MAX_DOTS = 600
        }
    }
}
