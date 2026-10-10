package com.optionslab.ira

import com.optionslab.engine.Upstox

/**
 * Local candles (Boss, 9 Oct, round 2: "don't wait even for a sec"): 1-minute OHLC candles built in memory from Zerodha's
 * stream ticks, by the EXCHANGE's own stamp, for the instruments the arms read (the indices, India VIX, MCX futures, held
 * options). An arm that judges 1-minute (or 5- / 15-minute) candles can then decide at the first tick after a boundary,
 * instead of waiting a few seconds for the candle feed to publish the minute.
 *
 * The candle feed stays the source of truth: [reconcile] lays the feed's minutes over the local ones (the feed's values
 * win, and a minute the stream missed is backfilled from it), and [overlay] adds local minutes to the feed's only when they
 * follow the feed's last minute WITHOUT A GAP and each of them is whole (the stream was running from before it opened and
 * did not drop meanwhile). Anything else - a missed minute, a minute that began before the stream (re)connected, an
 * instrument that is not streaming - gives the feed's candles alone ([Source.FEED]): never a decision on a local candle
 * with a gap.
 *
 * Boundaries: a tick stamped exactly hh:mm:00 opens minute hh:mm. A minute is closed once a tick of a later minute (by the
 * exchange's stamp) has arrived, or the clock passed its end. Out-of-order ticks (an older stamp after a newer one) update
 * their own minute's high and low, and its close only when they are not older than the close already there. Thread-safe.
 * Pure: no clock (the caller passes the time), no Android.
 */
class LiveCandles(private val keepMinutes: Int = KEEP_MINUTES) {
    /** Where a decision's candles came from. */
    enum class Source { LOCAL, FEED }

    /** One candle; [whole]: every tick of the minute was seen (or the feed gave it). */
    data class Candle(val startSec: Long, val open: Double, val high: Double, val low: Double, val close: Double,
                      val whole: Boolean, val fromFeed: Boolean = false) {
        fun bar(): Upstox.Bar = Upstox.Bar(startSec, open, high, low, close, 0, 0)
    }

    data class Overlay(val bars: List<Upstox.Bar>, val source: Source, val added: Int)

    private class Mut(val startSec: Long, var open: Double, var high: Double, var low: Double, var close: Double,
                      var openSec: Long, var closeSec: Long, var whole: Boolean, var fromFeed: Boolean = false)

    private class Series {
        val minutes = java.util.TreeMap<Long, Mut>()
        /** The first minute the current unbroken run of ticks began in (its own candle is partial). */
        var runStartMinute: Long? = null
        /** The latest exchange stamp seen (epoch seconds). */
        var latestSec: Long = Long.MIN_VALUE
    }

    private val series = HashMap<Long, Series>()

    /**
     * One traded [price] of [token] stamped [exchSec] (epoch seconds, the exchange's). Not positive or not finite: ignored.
     * Returns true when this tick is the first of a new minute for [token] (a minute just closed).
     */
    @Synchronized fun record(token: Long, price: Double, exchSec: Long): Boolean {
        if (!(price.isFinite() && price > 0) || exchSec <= 0) return false
        val s = series.getOrPut(token) { Series() }
        val m = minuteOf(exchSec)
        val start = m * 60
        val rolled = s.latestSec != Long.MIN_VALUE && m > minuteOf(s.latestSec)
        if (s.runStartMinute == null) s.runStartMinute = m
        val c = s.minutes[start]
        if (c == null) {
            // A minute after the run started is whole from its first tick; the run's first minute (or one older than it) is not.
            val whole = m > (s.runStartMinute ?: m)
            s.minutes[start] = Mut(start, price, price, price, price, exchSec, exchSec, whole)
        } else if (!c.fromFeed) {
            c.high = maxOf(c.high, price); c.low = minOf(c.low, price)
            if (exchSec >= c.closeSec) { c.close = price; c.closeSec = exchSec }
            if (exchSec < c.openSec) { c.open = price; c.openSec = exchSec }
        }
        if (exchSec > s.latestSec) s.latestSec = exchSec
        trim(s)
        return rolled
    }

    /**
     * The stream dropped (or reconnected) for [token], or for every instrument when null: the next tick starts a new run,
     * and its first minute is partial. The candles already built are kept (closed minutes stay whole).
     */
    @Synchronized fun broke(token: Long? = null) {
        if (token != null) series[token]?.let { breakRun(it) } else series.values.forEach { breakRun(it) }
    }

    private fun breakRun(s: Series) {
        // The minute in progress may have lost ticks: no longer whole.
        if (s.latestSec != Long.MIN_VALUE) s.minutes[minuteOf(s.latestSec) * 60]?.let { if (!it.fromFeed) it.whole = false }
        s.runStartMinute = null
    }

    /** The feed's [bars] of [token] laid over the local candles: the feed's values win, missing minutes are backfilled. */
    @Synchronized fun reconcile(token: Long, bars: List<Upstox.Bar>) {
        val s = series.getOrPut(token) { Series() }
        for (b in bars) {
            val start = minuteOf(b.epochSecond) * 60
            if (!(b.open > 0 && b.high > 0 && b.low > 0 && b.close > 0)) continue
            s.minutes[start] = Mut(start, b.open, b.high, b.low, b.close, start, start + 59, whole = true, fromFeed = true)
        }
        trim(s)
    }

    /** The minute candle of [token] starting at [startSec], or null. */
    @Synchronized fun candle(token: Long, startSec: Long): Candle? = series[token]?.minutes?.get(startSec)?.let(::freeze)

    /** The latest exchange stamp seen for [token] (null: none). */
    @Synchronized fun latest(token: Long): Long? = series[token]?.latestSec?.takeIf { it != Long.MIN_VALUE }

    /**
     * The closed 1-minute candles of [token] from [fromSec] (a minute start) to the last minute closed by [nowSec] (or by a
     * later tick), all whole and none missing; null when any is missing or partial (the caller then reads the feed).
     */
    @Synchronized fun closedRun(token: Long, fromSec: Long, nowSec: Long): List<Candle>? {
        val s = series[token] ?: return null
        val last = lastClosedMinute(s, nowSec) ?: return null
        val out = ArrayList<Candle>()
        var m = minuteOf(fromSec)
        while (m <= last) {
            val c = s.minutes[m * 60] ?: return null
            if (!c.whole) return null
            out += freeze(c)
            m++
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * [feed] (one instrument's 1-minute candles, epoch seconds, oldest first or not) with the closed local minutes after its
     * last candle, up to the last minute closed at [nowSec]: [Source.LOCAL] when at least one was added, every one of them
     * whole and none missing; else [feed] as it is ([Source.FEED]). An empty feed is returned as it is (the arms read the
     * session from its start, which the stream may not have). At most [maxAdd] minutes are added.
     */
    @Synchronized fun overlay(token: Long?, feed: List<Upstox.Bar>, nowSec: Long, maxAdd: Int = MAX_ADD): Overlay {
        val asIs = Overlay(feed, Source.FEED, 0)
        if (token == null) return asIs
        val s = series[token] ?: return asIs
        val lastFeed = feed.maxOfOrNull { it.epochSecond } ?: return asIs
        val lastClosed = lastClosedMinute(s, nowSec) ?: return asIs
        val from = minuteOf(lastFeed) + 1
        if (from > lastClosed) return asIs
        if (lastClosed - from + 1 > maxAdd) return asIs
        val add = ArrayList<Upstox.Bar>()
        for (m in from..lastClosed) {
            val c = s.minutes[m * 60] ?: return asIs
            if (!c.whole) return asIs
            add += freeze(c).bar()
        }
        return Overlay(feed + add, Source.LOCAL, add.size)
    }

    /** The last minute (index) of [s] closed at [nowSec]: one before the latest tick's minute, or the latest when time passed it. */
    private fun lastClosedMinute(s: Series, nowSec: Long): Long? {
        if (s.latestSec == Long.MIN_VALUE) return null
        val latestMinute = minuteOf(s.latestSec)
        return if (minuteOf(nowSec) > latestMinute) latestMinute else latestMinute - 1
    }

    private fun trim(s: Series) {
        while (s.minutes.size > keepMinutes) s.minutes.pollFirstEntry()
    }

    private fun freeze(c: Mut) = Candle(c.startSec, c.open, c.high, c.low, c.close, c.whole, c.fromFeed)

    /** TEST ONLY / a reset: forget everything. */
    @Synchronized fun clear() = series.clear()

    companion object {
        /** Minutes kept per instrument (two hours: enough for a 15-minute bar and the feed's lag). */
        const val KEEP_MINUTES = 120
        /** At most this many minutes are added to the feed's (more means the feed is far behind: read it instead). */
        const val MAX_ADD = 15

        fun minuteOf(epochSec: Long): Long = Math.floorDiv(epochSec, 60L)

        /**
         * [ones] (1-minute bars) folded into [n]-minute bars aligned to epoch multiples of [n] minutes (IST's 09:15 and
         * every 5- and 15-minute boundary are such), each labelled by its start. Only a bar with all [n] minutes present is
         * kept (a bar with a missing minute is a gap: never decided on).
         */
        fun fold(ones: List<Upstox.Bar>, n: Int): List<Upstox.Bar> {
            require(n >= 1)
            val step = n * 60L
            return ones.distinctBy { minuteOf(it.epochSecond) }.groupBy { Math.floorDiv(it.epochSecond, step) * step }.toSortedMap()
                .mapNotNull { (start, g) ->
                    val s = g.sortedBy { it.epochSecond }
                    if (s.size != n) return@mapNotNull null
                    Upstox.Bar(start, s.first().open, s.maxOf { it.high }, s.minOf { it.low }, s.last().close,
                        s.sumOf { it.volume }, s.last().oi)
                }
        }
    }
}
