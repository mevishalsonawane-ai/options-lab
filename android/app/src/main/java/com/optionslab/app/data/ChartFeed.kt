package com.optionslab.app.data

import com.optionslab.engine.Upstox
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

    fun contract(symbol: String): Upstox.Contract? =
        Market.contracts().firstOrNull { it.tradingSymbol.equals(symbol, ignoreCase = true) }

    suspend fun bars(symbol: String, interval: String, fromSec: Long?, toSec: Long?): List<Upstox.Bar> {
        val key = Upstox.quote(instrumentKey(symbol))
        val u = unitOf(interval)
        val today = Market.today()
        val to = toSec?.let { Instant.ofEpochSecond(it).atZone(IST).toLocalDate() }?.coerceAtMost(today) ?: today
        val defaultBack = when (u.unit) { "minutes" -> if (u.n <= 5) 10L else 60L; "hours" -> 180L; "days" -> 1500L; else -> 5000L }
        var from = fromSec?.let { Instant.ofEpochSecond(it).atZone(IST).toLocalDate() } ?: to.minusDays(defaultBack)
        // Upstox keeps minute and hour candles from January 2022.
        if (u.unit == "minutes" || u.unit == "hours") from = from.coerceAtLeast(LocalDate.of(2022, 1, 1))
        val out = ArrayList<Upstox.Bar>()
        var hi = to
        while (!hi.isBefore(from)) {
            val lo = maxOf(from, hi.minusDays(u.chunkDays - 1))
            out += Net.parseCandles(Net.getJson("$BASE/$key/${u.unit}/${u.n}/$hi/$lo", tries = 3))
            hi = lo.minusDays(1)
        }
        // Today's session lives on a separate endpoint.
        if (to == today && u.unit in setOf("minutes", "hours", "days")) {
            runCatching { Net.parseCandles(Net.getJson("$BASE/intraday/$key/${u.unit}/${u.n}", tries = 2)) }.onSuccess { out += it }
        }
        val lo = fromSec ?: Long.MIN_VALUE
        val top = toSec ?: Long.MAX_VALUE
        return out.distinctBy { it.epochSecond }.filter { it.epochSecond in lo..top }.sortedBy { it.epochSecond }
    }

    /** Symbol search for the chart's top bar: the indices, then options whose trading symbol has every word typed. */
    fun search(text: String): List<Match> {
        val words = text.uppercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val indices = Upstox.INDEX_KEYS.keys.filter { k -> words.all { k.contains(it) } }.map { Match(it, "NSE", "Index") }
        if (words.isEmpty()) return indices
        val today = Market.today()
        val options = Market.contracts().asSequence()
            .filter { !it.isExpired(today) && words.all { w -> it.tradingSymbol.uppercase().contains(w) } }
            .sortedWith(compareBy({ it.expiry }, { it.strike }))
            .take(40)
            .map { Match(it.tradingSymbol, "NFO", "${it.underlying} ${it.expiry} · lot ${it.lotSize}") }
        return indices + options
    }
}
