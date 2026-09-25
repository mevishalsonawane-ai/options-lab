package com.optionslab.app.data

import com.optionslab.engine.Upstox
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.async

/**
 * Candles for the chart terminal, from Upstox's public v3 candles: the three
 * indices and every listed NIFTY/BANKNIFTY option, at any interval the chart
 * asks for ("1m", "5m", "1h", "1d", "1w", "1M", ...). Upstox serves minutes
 * and hours directly, so nothing is re-aggregated here.
 */
object ChartFeed {
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private const val BASE = Upstox.BASE

    data class Match(val symbol: String, val exchange: String, val name: String)

    /** (Upstox unit, count, most days one request may span). */
    private data class Step(val unit: String, val n: Int, val chunkDays: Long)

    private fun unitOf(interval: String): Step {
        val m = Regex("^(\\d*)([a-zA-Z]+)$").find(interval.trim()) ?: throw IOException("unknown interval $interval")
        val n = m.groupValues[1].ifEmpty { "1" }.toInt().coerceAtLeast(1)
        return when (m.groupValues[2]) {
            "m", "min", "minute", "minutes" -> Step("minutes", n, if (n <= 15) 28 else 88)
            "h", "H", "hour", "hours" -> Step("hours", n, 88)
            "d", "D", "day", "days" -> Step("days", n, 3600)
            "w", "W", "week", "weeks" -> Step("weeks", n, 36_500)
            "M", "month", "months" -> Step("months", n, 36_500)
            else -> throw IOException("unknown interval $interval")
        }
    }

    /** The Upstox instrument key for a chart symbol: an index name, or an option's trading symbol. */
    fun instrumentKey(symbol: String): String =
        Upstox.INDEX_KEYS[symbol.uppercase()] ?: contract(symbol)?.instrumentKey
            ?: throw IOException("$symbol is not an index or a listed NIFTY/BANKNIFTY option")

    /** The contract list already on the phone (any day): never waits on the network. */
    private fun known(): List<Upstox.Contract> = Market.cachedContracts()?.second.orEmpty()

    /** Refresh today's contract list in the background when the saved one is from an earlier day. */
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun refreshInBackground() {
        if (Market.cachedContracts()?.first == Market.today() || !refreshing.compareAndSet(false, true)) return
        Thread { try { runCatching { Market.contracts() } } finally { refreshing.set(false) } }.start()
    }

    fun contract(symbol: String): Upstox.Contract? =
        known().firstOrNull { it.tradingSymbol.equals(symbol, ignoreCase = true) }
            ?: Market.contracts().firstOrNull { it.tradingSymbol.equals(symbol, ignoreCase = true) }

    /**
     * Past sessions do not change, so their candles are kept in memory for the day: after the
     * first load a chart only asks for today's session, which is one small request.
     */
    private data class Past(val day: LocalDate, val from: LocalDate, val bars: List<Upstox.Bar>)
    private val past = java.util.concurrent.ConcurrentHashMap<String, Past>()

    suspend fun bars(symbol: String, interval: String, fromSec: Long?, toSec: Long?): List<Upstox.Bar> {
        val key = Upstox.quote(instrumentKey(symbol))
        val u = unitOf(interval)
        val today = Market.today()
        val to = toSec?.let { Instant.ofEpochSecond(it).atZone(IST).toLocalDate() }?.coerceAtMost(today) ?: today
        val defaultBack = when (u.unit) { "minutes" -> if (u.n <= 5) 10L else 60L; "hours" -> 180L; "days" -> 1500L; else -> 5000L }
        var from = fromSec?.let { Instant.ofEpochSecond(it).atZone(IST).toLocalDate() } ?: to.minusDays(defaultBack)
        // Intraday charts always get the last few sessions, so a Monday-morning or holiday-adjacent
        // chart still has well over three hours of candles to show.
        val intraday = u.unit == "minutes" || u.unit == "hours"
        if (intraday) from = minOf(from, to.minusDays(6))
        // Upstox keeps minute and hour candles from January 2022.
        if (u.unit == "minutes" || u.unit == "hours") from = from.coerceAtLeast(LocalDate.of(2022, 1, 1))
        val out = ArrayList<Upstox.Bar>()
        val cacheKey = "$key|${u.unit}|${u.n}"
        val live = to == today && u.unit in setOf("minutes", "hours", "days")
        kotlinx.coroutines.coroutineScope {
            // Today's session lives on a separate endpoint; it is fetched alongside the history.
            val todays = if (live) async(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { Net.parseCandles(Net.getJson("$BASE/intraday/$key/${u.unit}/${u.n}", tries = 2)) }.getOrDefault(emptyList())
            } else null
            val cached = past[cacheKey]?.takeIf { it.day == today && !it.from.isAfter(from) }
            if (cached != null) {
                out += cached.bars
            } else {
                val chunks = ArrayList<Pair<LocalDate, LocalDate>>()
                var hi = to
                while (!hi.isBefore(from)) {
                    val lo = maxOf(from, hi.minusDays(u.chunkDays - 1))
                    chunks += lo to hi
                    hi = lo.minusDays(1)
                }
                val got = chunks.map { (lo, h) -> async(kotlinx.coroutines.Dispatchers.IO) { Net.parseCandles(Net.getJson("$BASE/$key/${u.unit}/${u.n}/$h/$lo", tries = 3)) } }
                    .flatMap { it.await() }
                out += got
                // Keep only finished sessions; today's bars always come fresh.
                if (to == today) past[cacheKey] = Past(today, from, got.filter { it.istDate.isBefore(today) })
            }
            todays?.let { out += it.await() }
        }
        val lo = if (intraday) Long.MIN_VALUE else fromSec ?: Long.MIN_VALUE
        val top = toSec ?: Long.MAX_VALUE
        return out.distinctBy { it.epochSecond }.filter { it.epochSecond in lo..top }.sortedBy { it.epochSecond }
    }

    /** Symbol search for the chart's top bar: the indices, then options whose trading symbol has every word typed. */
    fun search(text: String): List<Match> {
        refreshInBackground()
        val words = text.uppercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val indices = Upstox.INDEX_KEYS.keys.filter { k -> words.all { k.contains(it) } }.map { Match(it, "NSE", "Index") }
        if (words.isEmpty()) return indices
        val today = Market.today()
        val options = known().asSequence()
            .filter { !it.isExpired(today) && words.all { w -> it.tradingSymbol.uppercase().contains(w) } }
            .sortedWith(compareBy({ it.expiry }, { it.strike }))
            .take(40)
            .map { Match(it.tradingSymbol, "NFO", "lot ${it.lotSize}") }
        return indices + options
    }
}
