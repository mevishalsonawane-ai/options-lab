package com.optionslab.app.data

import com.optionslab.engine.Upstox
import org.json.JSONObject

/**
 * IraGoldAlgo's chart candles: gold (COMEX GC=F, the same feed the strategy reads) from Yahoo for any interval the
 * chart asks for ("1m", "5m", "15m", "1h", "4h", "1d", "1w", "1M", ...). Yahoo serves 1/2/5/15/30/60-minute, daily, weekly
 * and monthly candles; any other size is folded from the largest of those that divides it. Each answer is kept 15 s,
 * so the chart's polling and its pages of history do not refetch. No account, no key, nothing sent but the request.
 */
object GoldChart {
    const val SYMBOL = "XAUUSD"
    private const val BASE = "https://query1.finance.yahoo.com/v8/finance/chart/GC%3DF"

    /** Test seam: the JSON for a Yahoo URL (no network in tests). */
    @Volatile internal var testFetch: ((String) -> JSONObject)? = null
    private val cache = HashMap<String, Pair<Long, List<Upstox.Bar>>>()
    internal fun resetForTest() = synchronized(cache) { cache.clear() }

    /** (Yahoo interval, range, minutes to fold into or 0) for a chart interval, or null if it is not one. */
    internal fun plan(interval: String): Triple<String, String, Int>? {
        val m = Regex("(\\d*)\\s*(m|min|h|d|D|w|W|M)").matchEntire(interval.trim()) ?: return null
        val n = m.groupValues[1].ifEmpty { "1" }.toInt().takeIf { it > 0 } ?: return null
        return when (m.groupValues[2]) {
            "m", "min" -> minutes(n)
            "h" -> minutes(60 * n)
            "d", "D" -> if (n == 1) Triple("1d", "5y", 0) else Triple("1d", "5y", 1440 * n)
            "w", "W" -> Triple("1wk", "10y", 0)
            else -> Triple("1mo", "max", 0)
        }
    }

    private fun minutes(n: Int): Triple<String, String, Int> {
        val base = listOf(60, 30, 15, 5, 2, 1).first { n % it == 0 }
        val range = when { base == 1 -> "5d"; base < 60 -> "1mo"; n >= 240 -> "2y"; else -> "6mo" }
        return Triple(if (base == 60) "60m" else "${base}m", range, if (n == base) 0 else n)
    }

    suspend fun bars(interval: String, fromSec: Long?, toSec: Long?): List<Upstox.Bar> {
        val (iv, range, fold) = plan(interval) ?: throw IllegalArgumentException("Unknown interval $interval")
        val url = "$BASE?interval=$iv&range=$range"
        val now = System.currentTimeMillis()
        val all = synchronized(cache) { cache[url]?.takeIf { now - it.first < 15_000 }?.second } ?: run {
            val json = testFetch?.invoke(url) ?: Net.getJson(url, tries = 3, timeoutMs = Net.QUICK_READ_MS, connectMs = Net.QUICK_CONNECT_MS)
            val got = parse(json).let { if (fold > 0) fold(it, fold * 60L) else it }
            synchronized(cache) { cache[url] = now to got }
            got
        }
        return all.filter { (fromSec == null || it.epochSecond >= fromSec) && (toSec == null || it.epochSecond <= toSec) }
    }

    /** Yahoo's chart JSON as candles (epoch seconds); a candle with a missing price is skipped. */
    internal fun parse(o: JSONObject): List<Upstox.Bar> {
        val r = o.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val ts = r.optJSONArray("timestamp") ?: return emptyList()
        val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val (op, hi, lo, cl) = listOf("open", "high", "low", "close").map { q.getJSONArray(it) }
        val vol = q.optJSONArray("volume")
        return (0 until ts.length()).mapNotNull { i ->
            if (op.isNull(i) || hi.isNull(i) || lo.isNull(i) || cl.isNull(i)) null
            else Upstox.Bar(ts.getLong(i), op.getDouble(i), hi.getDouble(i), lo.getDouble(i), cl.getDouble(i),
                vol?.takeIf { !it.isNull(i) }?.optLong(i) ?: 0L, 0L)
        }.sortedBy { it.epochSecond }
    }

    /** Candles folded into [step]-second buckets (aligned to the epoch, so 4-hour candles start 00:00, 04:00 ... UTC). */
    internal fun fold(bars: List<Upstox.Bar>, step: Long): List<Upstox.Bar> =
        bars.groupBy { it.epochSecond - Math.floorMod(it.epochSecond, step) }.map { (t, g) ->
            Upstox.Bar(t, g.first().open, g.maxOf { it.high }, g.minOf { it.low }, g.last().close, g.sumOf { it.volume }, 0L)
        }.sortedBy { it.epochSecond }

    fun search(text: String): List<ChartFeed.Match> = listOf(ChartFeed.Match(SYMBOL, "COMEX", "Gold (COMEX futures, GC=F)"))
}
