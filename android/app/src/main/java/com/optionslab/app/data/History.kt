package com.optionslab.app.data

import android.content.Context
import com.optionslab.engine.IST
import com.optionslab.engine.Upstox
import com.optionslab.engine.portfolio.DailyBar
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/**
 * Daily price history for the portfolio and SIP backtesters - IraAlgo's
 * Historify, on the phone. Backtests are analysis, so in either mode they may
 * use Upstox's public daily candles; when Zerodha is logged in (LIVE mode)
 * Kite's own daily candles are tried first and the source is named.
 */
object History {
    private lateinit var keysFile: File

    fun init(context: Context) {
        File(context.applicationContext.filesDir, "equity_keys.json").delete()   // the old plaintext cache
        keysFile = File(context.applicationContext.filesDir, "equity_keys.vault")
    }

    fun wipe() { keysFile.delete() }

    /** Indices the backtesters accept as benchmarks, by IraAlgo name. */
    val BENCHMARKS = listOf("NIFTY", "BANKNIFTY")

    data class Fetched(val bars: Map<String, List<DailyBar>>, val sources: Set<String>)

    private fun toDaily(b: Upstox.Bar) = DailyBar(Instant.ofEpochSecond(b.epochSecond).atZone(IST).toLocalDate(),
        b.open, b.high, b.low, b.close, b.volume.toDouble())

    // Encrypted: the symbols looked up include the ones you hold.
    private fun cachedKeys(): MutableMap<String, String> = runCatching {
        val o = JSONObject(String(com.optionslab.app.security.Vault.readFile(keysFile)!!, Charsets.UTF_8))
        o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
    }.getOrDefault(HashMap())

    /** Upstox keys for "NSE:RELIANCE"-style names, looked up once and cached. */
    private fun upstoxKeys(names: List<Pair<String, String>>): Map<String, String> {
        val cache = cachedKeys()
        val missing = names.filter { (ex, sym) -> "$ex:$sym" !in cache }
        if (missing.isNotEmpty()) {
            val found = Net.fetchEquityKeys(missing.map { (ex, sym) -> "${ex}_EQ" to sym }.toSet())
            found.forEach { (k, v) -> cache["${k.first.removeSuffix("_EQ")}:${k.second}"] = v }
            runCatching { com.optionslab.app.security.Vault.writeFile(keysFile, JSONObject(cache as Map<*, *>).toString().toByteArray(Charsets.UTF_8)) }
        }
        return cache
    }

    /**
     * Daily bars for [holdings] (symbol, exchange) and an optional benchmark
     * index, keyed by symbol as the engine expects.
     */
    suspend fun load(holdings: List<Pair<String, String>>, benchmark: String?, from: LocalDate, to: LocalDate,
                     progress: (String) -> Unit = {}): Fetched {
        val out = LinkedHashMap<String, List<DailyBar>>()
        val sources = LinkedHashSet<String>()
        val kite = Market.liveMode() && Broker.loggedIn
        var kiteWorks = kite
        val keys by lazy { upstoxKeys(holdings.map { (s, e) -> e to s }) }

        suspend fun viaKite(token: Long, start: LocalDate = from): List<DailyBar>? = if (!kiteWorks) null else try {
            Broker.dailyBars(token, start, to).map(::toDaily)
        } catch (e: Broker.KiteError) {
            if (e.type == "TokenException") throw e
            kiteWorks = false   // no historical-data add-on on this Kite plan
            null
        }

        // Downloaded from Dhan (More, Dhan data): the phone's own daily candles, used when they reach back to the span's start.
        fun dhan(sym: String, index: Boolean): List<DailyBar>? = runCatching {
            val f = DhanSource.filesOrNull() ?: return@runCatching null
            val bars = f.candles(if (index) com.optionslab.ira.dhan.Plan.Group.IDX else com.optionslab.ira.dhan.Plan.Group.EQ, sym, "day")
                .map { c -> DailyBar(Instant.ofEpochSecond(c.t).atZone(IST).toLocalDate(), c.open, c.high, c.low, c.close, c.volume.toDouble()) }
                .filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
            bars.takeIf { b -> b.isNotEmpty() && !b.first().date.isAfter(from.plusDays(7)) }
        }.getOrNull()

        // The last session the span can hold: [to] or the last trading day before it (today's bar only once it is in).
        val lastSession = generateSequence(minOf(to, Market.today())) { it.minusDays(1) }.take(15)
            .firstOrNull { runCatching { Market.isTradingDay(it) }.getOrDefault(it.dayOfWeek.value <= 5) } ?: to
        val prevSession = generateSequence(lastSession.minusDays(1)) { it.minusDays(1) }.take(15)
            .firstOrNull { runCatching { Market.isTradingDay(it) }.getOrDefault(it.dayOfWeek.value <= 5) } ?: lastSession.minusDays(1)

        /**
         * Dhan's candles up to its last day, then the other source's after it: Dhan's alone only when its last day is within
         * one trading day of the span's end (stale downloaded data is never preferred over a fresher source).
         */
        suspend fun withTail(dhanBars: List<DailyBar>, other: suspend (LocalDate) -> List<DailyBar>?): List<DailyBar>? {
            val last = dhanBars.last().date
            if (!last.isBefore(prevSession)) return dhanBars
            val tail = runCatching { other(last.plusDays(1)) }.getOrNull() ?: return null
            return dhanBars + tail.filter { it.date.isAfter(last) && !it.date.isAfter(to) }
        }

        suspend fun otherStock(sym: String, ex: String, start: LocalDate): List<DailyBar> {
            val k = if (kiteWorks) runCatching { Broker.spec(ex, sym).token }.getOrNull() else null
            return k?.let { viaKite(it, start) }?.also { sources += "Zerodha daily candles" }
                ?: run {
                    val key = keys["$ex:$sym"] ?: throw IOException("$ex:$sym is not in the instrument list")
                    Net.daily(key, start, to).map(::toDaily).also { sources += "Upstox public daily candles" }
                }
        }

        suspend fun otherIndex(name: String, start: LocalDate): List<DailyBar> =
            Broker.indexToken(name)?.let { viaKite(it, start) }?.also { sources += "Zerodha daily candles" }
                ?: Net.daily(Upstox.INDEX_KEYS[name] ?: throw IOException("no index $name"), start, to).map(::toDaily)
                    .also { sources += "Upstox public daily candles" }

        for ((sym, ex) in holdings) {
            progress("Reading $sym")
            val fromDhan = if (ex.equals("NSE", ignoreCase = true)) dhan(sym, index = false) else null
            val joined = fromDhan?.let { withTail(it) { s -> otherStock(sym, ex, s) } }
            if (joined != null) { out[sym] = joined; sources += "Dhan daily candles (downloaded)"; continue }
            out[sym] = otherStock(sym, ex, from)
        }
        if (benchmark != null) {
            progress("Reading $benchmark")
            val joined = dhan(benchmark, index = true)?.let { withTail(it) { s -> otherIndex(benchmark, s) } }
            out[benchmark] = joined?.also { sources += "Dhan daily candles (downloaded)" } ?: otherIndex(benchmark, from)
        }
        return Fetched(out, sources)
    }
}
