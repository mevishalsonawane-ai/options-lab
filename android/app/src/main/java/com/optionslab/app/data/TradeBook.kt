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

    /** Moves on every change to the kept Zerodha trades (a fill recorded, a wipe): what [liveChargesOn]'s kept figure is keyed on. */
    @Volatile private var liveGen = 0L

    /**
     * The kept Zerodha trades. No file yet: empty (and kept). A vault that cannot be read (a Keystore failure, a damaged
     * file, or no bytes from a file that is there): throws, and nothing is kept - so [recordLive] never rewrites the vault
     * from an empty list, and Jarvis says the trade book could not be read rather than "no trades" (round 22 review; every
     * caller reads inside runCatching, as [Journal.all]'s do).
     */
    @Synchronized
    private fun liveTrades(): MutableList<Broker.Trade> {
        liveCache?.let { return it }
        if (!::liveFile.isInitialized) return ArrayList()
        val out = ArrayList<Broker.Trade>()
        val exists = liveFile.exists()
        val bytes = Vault.readFileSteady(liveFile)
        if (bytes == null && exists) throw java.io.IOException("live trade book could not be read")
        if (bytes != null) {
            val a = JSONArray(String(bytes, Charsets.UTF_8))
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
        liveGen++
    }

    private fun kiteTime(s: String): LocalDateTime? = runCatching { LocalDateTime.parse(s.trim().replace(' ', 'T').take(19)) }.getOrNull()

    // ---- fills and trips --------------------------------------------------------------------

    fun fills(live: Boolean): List<RoundTrips.Fill> = if (live) liveFills(liveSnapshot()) else paperFills(Paper.state.trades)

    /** A copy of the Zerodha trades taken under the lock ([recordLive] adds to the kept list in place). */
    @Synchronized
    private fun liveSnapshot(): List<Broker.Trade> = ArrayList(liveTrades())

    private fun liveFills(trades: List<Broker.Trade>): List<RoundTrips.Fill> =
        trades.mapNotNull { t ->
            val at = kiteTime(t.at) ?: return@mapNotNull null
            RoundTrips.Fill("kite:${t.id}", "kite:${t.orderId}", t.symbol, if (t.side == "BUY") 1 else -1, t.qty, t.price, at,
                SandboxCosts.charge(t.side, BigDecimal(t.price), t.qty).toDouble())
        }

    private fun paperFills(trades: List<com.optionslab.engine.sandbox.Trade>): List<RoundTrips.Fill> =
        trades.map { t ->
            RoundTrips.Fill("paper:${t.tradeId}", "paper:${t.orderId}", t.symbol, if (t.action == "BUY") 1 else -1, t.quantity, t.price.toDouble(),
                t.timestamp, t.charges.toDouble())
        }

    /**
     * The round trips, rebuilt only when the trades they are made from changed ([com.optionslab.ira.SameInput]): the
     * goals, the paper tests, the day's target and the journal each read them in every market-watch pass, and each read
     * re-priced every fill's charges and re-paired them all. The trades themselves are the key (compared in full), so a
     * new fill, a reset paper account or a wiped book is a different input and rebuilt at once; nothing to remember to clear.
     */
    private val liveTrips = com.optionslab.ira.SameInput<List<Broker.Trade>, List<RoundTrips.Trip>>()
    private val paperTrips = com.optionslab.ira.SameInput<List<com.optionslab.engine.sandbox.Trade>, List<RoundTrips.Trip>>()

    fun trips(live: Boolean): List<RoundTrips.Trip> =
        if (live) liveTrips.of(liveSnapshot()) { RoundTrips.of(liveFills(it)) }
        // The paper state is immutable and replaced on each change; copied anyway so the key can never change under it.
        else paperTrips.of(ArrayList(Paper.state.trades)) { RoundTrips.of(paperFills(it)) }

    /**
     * Who placed a trip: the label of the order that opened it ("ORB", "ORB Fresh", a strategy's name,
     * "Protection" …), or "Manual". Labels like "ORB · entry" are cut to the strategy.
     */
    fun ownerOf(trip: RoundTrips.Trip, owners: Map<String, String>): String {
        val label = trip.openOrderIds.firstNotNullOfOrNull { owners[it] } ?: owners[trip.closeOrderId]?.takeIf { !it.startsWith("Protection") }
        // A Liquidity 15+5 book's own name ("Liquidity 5m FINNIFTY") is that one arm's (ArmOwners): one row, all its trades.
        return label?.substringBefore(" · ")?.removePrefix("Strategy: ")?.trim()?.ifEmpty { null }?.let { com.optionslab.ira.ArmOwners.arm(it) } ?: "Manual"
    }

    /** Realised P&L per day for one strategy (the calendar's filter): before charges, with the day's charges beside it. */
    fun days(live: Boolean, owner: String, owners: Map<String, String>): Map<LocalDate, DailyPnl.Day> =
        trips(live).filter { ownerOf(it, owners) == owner }.groupBy { it.day }.mapValues { (d, ts) ->
            DailyPnl.Day(d, Math.round(ts.sumOf { it.gross } * 100) / 100.0, ts.size, Math.round(ts.sumOf { it.charges } * 100) / 100.0)
        }

    /**
     * Zerodha's charges on [trades] (fills as Kite lists them), estimated with the same F&O schedule as [charges] (the
     * charges report): the "Charges ≈ ₹X (estimate)" line under a Zerodha P&L. Pure: no vault read (safe on the main thread).
     */
    fun liveCharges(trades: List<Broker.Trade>): Double =
        com.optionslab.ira.PnlCharges.estimate(trades.map { com.optionslab.ira.PnlCharges.Fill(it.side, it.price, it.qty) })

    /**
     * Zerodha's estimated charges on [day] from the trades kept here, or null when none are kept for it (or the book could
     * not be read). Reads the vault: never on the main thread.
     */
    fun liveChargesOn(day: LocalDate): Double? = runCatching {
        // Battery, round 14: the order watch asks every pass (once a minute in market hours); the book is copied and
        // every kept fill's time parsed only when a fill was recorded since, or the day turned ([com.optionslab.ira.DayKept]).
        // The generation is read before the snapshot, so a fill recorded meanwhile is made again on the next ask.
        val gen = liveGen
        dayCharges.of(day.toString(), gen) {
            liveSnapshot().filter { kiteTime(it.at)?.toLocalDate() == day }.takeIf { it.isNotEmpty() }?.let { liveCharges(it) }
        }
    }.getOrNull()

    private val dayCharges = com.optionslab.ira.DayKept<Double?>()

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

    /**
     * Forgets the kept Zerodha trades. Lock order: [liveChargesOn] holds [dayCharges]'s lock while it reads the book (this
     * object's lock), so [dayCharges] is never touched while this object's lock is held - that was a deadlock (wipe:
     * TradeBook then DayKept; liveChargesOn: DayKept then TradeBook). Bumping [liveGen] under the lock is what makes the
     * kept figure stale (it is keyed on the generation); forgetting it after the lock is let go only frees it sooner.
     */
    fun wipe() {
        synchronized(this) { liveCache = null; liveGen++; if (::liveFile.isInitialized) liveFile.delete() }
        dayCharges.forget()
    }
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
    // Volatile: [cached] peeks at it from the main thread without the lock. Never changed in place once kept (each
    // change keeps a new map), so a peeked map stays whole.
    @Volatile private var cache: MutableMap<String, Entry>? = null

    /** The journal if it has been read already, else null: never reads the vault (safe on the main thread). */
    fun cached(): Map<String, Entry>? = cache

    fun init(context: Context) { file = File(context.applicationContext.noBackupFilesDir, "journal.vault") }

    /**
     * The whole journal. No file yet: empty (and kept). A vault that cannot be read (a Keystore failure, a damaged file):
     * throws, and nothing is kept - so [put] and [resetPaper] never rewrite the vault from an empty map and lose every
     * note; the caller shows "Journal not saved", and a read shows nothing (every caller reads inside runCatching).
     */
    @Synchronized
    fun all(): Map<String, Entry> {
        cache?.let { return it }
        val m = LinkedHashMap<String, Entry>()
        val bytes = Vault.readFileSteady(file)
        if (bytes != null) {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
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

    /** Reset paper: notes on paper trades go (the fresh account numbers its trades from 1 again). */
    @Synchronized
    fun resetPaper() {
        val m = all().filterKeys { !it.startsWith("paper:") }
        val o = JSONObject()
        m.forEach { (k, e) -> o.put(k, JSONObject().put("n", e.note).put("t", JSONArray(e.tags.toList())).put("at", e.at)) }
        Vault.writeFile(file, o.toString().toByteArray(Charsets.UTF_8))
        cache = m.toMutableMap()
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
