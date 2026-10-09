package com.optionslab.app.data

import com.optionslab.engine.Upstox
import org.json.JSONObject

/**
 * US prices before MCX opens, for the "US-night silver" paper arm ([com.optionslab.engine.mcx.McxUsSilverRules]): COMEX
 * silver's front month (SI=F) and USD/INR (INR=X) as 5-minute bars of the last five days, from the same public Yahoo chart
 * feed the gold build reads ([GoldChart.parse]; no key, no account, nothing sent but the request). About 10 minutes late,
 * which is fine: both prices it needs are taken before the 09:05 entry. Five days of history cover the previous MCX close
 * even when the app was shut overnight or over a weekend, so nothing depends on a live snapshot taken at the close.
 * Each answer is kept 5 minutes. Read off the main thread only (the market watch's pass).
 */
object UsCues {
    private const val BASE = "https://query1.finance.yahoo.com/v8/finance/chart/"

    /** TEST SEAM: the JSON for a Yahoo URL (no network in tests). */
    @Volatile internal var testFetch: ((String) -> JSONObject)? = null

    private val cache = HashMap<String, Pair<Long, List<Upstox.Bar>>>()
    private const val KEEP_MS = 5 * 60_000L

    internal fun resetForTest() = synchronized(cache) { cache.clear() }

    /** The chart URL for [symbol] ("SI=F" -> ".../chart/SI%3DF?interval=5m&range=5d"). */
    fun url(symbol: String): String = BASE + java.net.URLEncoder.encode(symbol, "UTF-8") + "?interval=5m&range=5d"

    /** [symbol]'s 5-minute bars of the last five days, oldest first (epoch-second starts); empty when the feed did not answer. */
    suspend fun bars(symbol: String): List<Upstox.Bar> {
        val url = url(symbol)
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[url]?.takeIf { now - it.first in 0 until KEEP_MS }?.let { return it.second } }
        val got = runCatching {
            val json = testFetch?.invoke(url) ?: Net.getJson(url, tries = 2, timeoutMs = Net.QUICK_READ_MS, connectMs = Net.QUICK_CONNECT_MS)
            GoldChart.parse(json)
        }.getOrDefault(emptyList())
        if (got.isNotEmpty()) synchronized(cache) { cache[url] = now to got }
        return got
    }
}
