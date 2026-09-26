package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.RoundTrips
import com.optionslab.engine.sandbox.SandboxCosts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * Every fill the app knows, as round trips with the strategy that placed them:
 * what the journal, the per-strategy calendar, the strategy comparison and the
 * charges report read.
 *
 *  - Paper: the paper account's own trade book (its charges included).
 *  - Zerodha: Kite only lists today's trades, so each day's are kept here as the
 *    app sees them (the account page, the day report); charges are estimated with
 *    the same F&O schedule the paper account pays.
 *
 * Kept in the encrypted vault; never leaves the phone.
 */
object TradeBook {
    private lateinit var liveFile: File
    fun init(context: Context) { liveFile = File(context.applicationContext.noBackupFilesDir, "live_trades.vault") }

    // ---- Zerodha trades, kept day after day -------------------------------------------------

    private var liveCache: MutableList<Broker.Trade>? = null

    @Synchronized
    private fun liveTrades(): MutableList<Broker.Trade> {
        liveCache?.let { return it }
        val out = ArrayList<Broker.Trade>()
        runCatching {
            val a = JSONArray(String(Vault.readFileSteady(liveFile) ?: return@runCatching, Charsets.UTF_8))
            for (i in 0 until a.length()) {
                val o = a.getJSONArray(i)
                out += Broker.Trade(o.getString(0), o.getString(1), o.getString(2), o.getString(3), o.getString(4), o.getInt(5), o.getDouble(6), o.getString(7), o.getString(8))
            }
        }
        liveCache = out
        return out
    }

    /** Add today's Zerodha trades (deduplicated by trade id). */
    @Synchronized
    fun recordLive(trades: List<Broker.Trade>) {
        if (trades.isEmpty() || !::liveFile.isInitialized) return
        val list = liveTrades()
        val known = list.map { it.id }.toHashSet()
        val fresh = trades.filter { it.id.isNotBlank() && it.id !in known }
        if (fresh.isEmpty()) return
        list += fresh
        val keep = list.takeLast(20_000)
        val a = JSONArray()
        keep.forEach { t -> a.put(JSONArray().put(t.id).put(t.orderId).put(t.symbol).put(t.exchange).put(t.side).put(t.qty).put(t.price).put(t.product).put(t.at)) }
        Vault.writeFile(liveFile, a.toString().toByteArray(Charsets.UTF_8))
        liveCache = keep.toMutableList()
    }

    private fun kiteTime(s: String): LocalDateTime? = runCatching { LocalDateTime.parse(s.trim().replace(' ', 'T').take(19)) }.getOrNull()

    // ---- fills and trips --------------------------------------------------------------------

    fun fills(live: Boolean): List<RoundTrips.Fill> = if (live) {
        liveTrades().mapNotNull { t ->
            val at = kiteTime(t.at) ?: return@mapNotNull null
            RoundTrips.Fill("kite:${t.id}", "kite:${t.orderId}", t.symbol, if (t.side == "BUY") 1 else -1, t.qty, t.price, at,
                SandboxCosts.charge(t.side, BigDecimal(t.price), t.qty).toDouble())
        }
    } else {
        Paper.state.trades.map { t ->
            RoundTrips.Fill("paper:${t.tradeId}", "paper:${t.orderId}", t.symbol, if (t.action == "BUY") 1 else -1, t.quantity, t.price.toDouble(),
                t.timestamp, t.charges.toDouble())
        }
    }

    fun trips(live: Boolean): List<RoundTrips.Trip> = RoundTrips.of(fills(live))

    /**
     * Who placed a trip: the label of the order that opened it ("ORB", "ORB Fresh", a strategy's name,
     * "Protection" …), or "Manual". Labels like "ORB · entry" are cut to the strategy.
     */
    fun ownerOf(trip: RoundTrips.Trip, owners: Map<String, String>): String {
        val label = trip.openOrderIds.firstNotNullOfOrNull { owners[it] } ?: owners[trip.closeOrderId]?.takeIf { !it.startsWith("Protection") }
        return label?.substringBefore(" · ")?.removePrefix("Strategy: ")?.trim()?.ifEmpty { null } ?: "Manual"
    }

    /** Realised P&L per day for one strategy (the calendar's filter). */
    fun days(live: Boolean, owner: String, owners: Map<String, String>): Map<LocalDate, DailyPnl.Day> =
        trips(live).filter { ownerOf(it, owners) == owner }.groupBy { it.day }.mapValues { (d, ts) ->
            DailyPnl.Day(d, Math.round(ts.sumOf { it.net } * 100) / 100.0, ts.size)
        }

    /** Charges paid in [month], line by line. */
    fun charges(live: Boolean, month: YearMonth): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        if (live) liveTrades().filter { kiteTime(it.at)?.let { t -> YearMonth.from(t) == month } == true }.forEach { t ->
            SandboxCosts.breakdown(t.side, t.price, t.qty).forEach { (k, v) -> out[k] = (out[k] ?: 0.0) + v }
        } else Paper.state.trades.filter { YearMonth.from(it.timestamp) == month }.forEach { t ->
            SandboxCosts.breakdown(t.action, t.price.toDouble(), t.quantity).forEach { (k, v) -> out[k] = (out[k] ?: 0.0) + v }
        }
        return out
    }

    fun wipe() { liveCache = null; if (::liveFile.isInitialized) liveFile.delete() }
}

/**
 * The trade journal: a note and tags on any trade, and what each tag has made.
 * Keyed by fill id ("paper:…", "kite:…"); a round trip carries the tags of every
 * fill in it.
 */
object Journal {
    data class Entry(val key: String, val note: String, val tags: Set<String>, val at: Long)

    val SETUPS = listOf("Breakout", "Reversal", "Trend", "Range", "Expiry", "News", "Hedge")
    val MISTAKES = listOf("FOMO", "Late entry", "Early exit", "Oversized", "No stop", "Revenge", "Held a loser")
    val MOODS = listOf("Calm", "Confident", "Anxious", "Greedy", "Bored")

    private lateinit var file: File
    private var cache: MutableMap<String, Entry>? = null
    fun init(context: Context) { file = File(context.applicationContext.noBackupFilesDir, "journal.vault") }

    @Synchronized
    fun all(): Map<String, Entry> {
        cache?.let { return it }
        val m = LinkedHashMap<String, Entry>()
        runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                val tags = e.optJSONArray("t")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
                m[k] = Entry(k, e.optString("n"), tags, e.optLong("at"))
            }
        }
        cache = m
        return m
    }

    @Synchronized
    fun put(key: String, note: String, tags: Set<String>) {
        val m = all().toMutableMap()
        if (note.isBlank() && tags.isEmpty()) m.remove(key) else m[key] = Entry(key, note.trim(), tags, System.currentTimeMillis())
        val o = JSONObject()
        m.forEach { (k, e) -> o.put(k, JSONObject().put("n", e.note).put("t", JSONArray(e.tags.toList())).put("at", e.at)) }
        Vault.writeFile(file, o.toString().toByteArray(Charsets.UTF_8))
        cache = m
    }

    fun of(key: String): Entry? = all()[key]

    /** Tags on a round trip: those of every fill in it. */
    fun tagsOf(trip: RoundTrips.Trip): Set<String> = trip.fillIds.flatMap { all()[it]?.tags.orEmpty() }.toSet()

    /** Net P&L, trips and win rate per tag. */
    data class TagStat(val tag: String, val trips: Int, val net: Double, val wins: Int)

    fun byTag(trips: List<RoundTrips.Trip>): List<TagStat> {
        val m = HashMap<String, MutableList<RoundTrips.Trip>>()
        trips.forEach { t -> tagsOf(t).forEach { tag -> m.getOrPut(tag) { ArrayList() } += t } }
        return m.map { (tag, ts) -> TagStat(tag, ts.size, ts.sumOf { it.net }, ts.count { it.net > 0 }) }.sortedByDescending { it.net }
    }

    fun wipe() { cache = null; if (::file.isInitialized) file.delete() }
}
