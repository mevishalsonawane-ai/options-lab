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

        suspend fun viaKite(token: Long): List<DailyBar>? = if (!kiteWorks) null else try {
            Broker.dailyBars(token, from, to).map(::toDaily)
        } catch (e: Broker.KiteError) {
            if (e.type == "TokenException") throw e
            kiteWorks = false   // no historical-data add-on on this Kite plan
            null
        }

        for ((sym, ex) in holdings) {
            progress("Reading $sym")
            val k = if (kiteWorks) runCatching { Broker.spec(ex, sym).token }.getOrNull() else null
            val bars = k?.let { viaKite(it) }?.also { sources += "Zerodha daily candles" }
                ?: run {
                    val key = keys["$ex:$sym"] ?: throw IOException("$ex:$sym is not in the instrument list")
                    Net.daily(key, from, to).map(::toDaily).also { sources += "Upstox public daily candles" }
                }
            out[sym] = bars
        }
        if (benchmark != null) {
            progress("Reading $benchmark")
            val bars = Broker.indexToken(benchmark)?.let { viaKite(it) }?.also { sources += "Zerodha daily candles" }
                ?: Net.daily(Upstox.INDEX_KEYS[benchmark] ?: throw IOException("no index $benchmark"), from, to).map(::toDaily)
                    .also { sources += "Upstox public daily candles" }
            out[benchmark] = bars
        }
        return Fetched(out, sources)
    }
}
