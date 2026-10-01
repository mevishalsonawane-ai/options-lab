package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.gold.GoldLiquidity
import com.optionslab.engine.orb.Bar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * IraGoldAlgo's paper account and its one strategy ([GoldLiquidity], XAUUSD 1-hour liquidity, buys only). Paper only:
 * nothing here can place an order anywhere. On every signal it notifies the owner, who may act in their own broker app.
 *
 * Prices: COMEX gold futures (GC=F) 1-minute and 1-hour candles from Yahoo's public chart feed (no key, no account).
 * Spot XAUUSD has no free live feed that answered reliably; futures move with spot but sit a few dollars above it, so
 * the levels and signals are the same while the prices shown differ a little from a broker's XAUUSD.
 *
 * Saved encrypted in the app's private storage (the vault). All times UTC.
 */
object GoldPaper {
    const val FEED_SYMBOL = "GC=F"
    private const val HOURS_URL = "https://query1.finance.yahoo.com/v8/finance/chart/GC%3DF?interval=60m&range=3mo"
    private const val MINUTES_URL = "https://query1.finance.yahoo.com/v8/finance/chart/GC%3DF?interval=1m&range=1d"
    const val DEFAULT_BALANCE = 1_000.0
    const val DEFAULT_LOTS = 0.01

    data class Position(val entry: Double, val entryTime: LocalDateTime, val signalBar: LocalDateTime, val level: Double, val target: Double?)

    data class Trade(val entry: Double, val exit: Double, val entryTime: LocalDateTime, val exitTime: LocalDateTime, val lots: Double,
                     val why: String, val pnl: Double)

    data class Book(
        val start: Double = DEFAULT_BALANCE,
        val lots: Double = DEFAULT_LOTS,
        val armed: Boolean = false,
        val position: Position? = null,
        val trades: List<Trade> = emptyList(),
        val decided: String? = null,           // the last candle decided on (one decision a candle)
        val status: String = "Not armed",
        val price: Double? = null,
        val priceAt: LocalDateTime? = null,
    ) {
        val realized: Double get() = trades.sumOf { it.pnl }
        val balance: Double get() = start + realized
        fun open(mid: Double?): Double? = position?.let { p -> mid?.let { GoldLiquidity.pnl(p.entry, GoldLiquidity.sellPrice(it), lots) } }
    }

    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()
    private val _book = MutableStateFlow(Book())
    val book: StateFlow<Book> = _book

    /** TEST ONLY: the feed's 1-minute bars at a moment (UTC), so a test can run the strategy without the network. */
    @Volatile internal var testMinutes: ((LocalDateTime) -> List<Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test feed exists only in debug builds" }; field = v }
    @Volatile internal var testNow: LocalDateTime? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test clock exists only in debug builds" }; field = v }

    /** TEST ONLY: replace the book (screens are tested on prepared accounts). Throws outside debug builds. */
    internal suspend fun replaceForTest(b: Book) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }
        lock.withLock { save(b) }
    }

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.noBackupFilesDir, "gold.vault")
        _book.value = runCatching { read() }.getOrNull() ?: Book()
    }

    fun now(): LocalDateTime = testNow ?: LocalDateTime.now(ZoneOffset.UTC)

    // ---- the owner's switches ---------------------------------------------------

    suspend fun setArmed(on: Boolean) = edit { it.copy(armed = on, status = if (on) "Armed: waiting for the next 1-hour candle" else "Not armed") }

    suspend fun setLots(lots: Double) = edit { it.copy(lots = lots) }

    /** A fresh paper account with [balance]; the switch and the lot size are kept, an open trade and history go. */
    suspend fun reset(balance: Double) = edit { it.copy(start = balance, position = null, trades = emptyList(), decided = null) }

    // ---- the minute pass ---------------------------------------------------------

    /** One pass: read prices, sell an open buy whose exit came, else buy on a fresh signal. Never throws. */
    suspend fun tick() = runCatching {
        lock.withLock {
            val t = now()
            val minutes = minutes(t)
            val hourly = GoldLiquidity.completed(GoldLiquidity.hourly(history() + minutes), t)
            val last = minutes.lastOrNull()
            var b = _book.value
            if (last != null) b = b.copy(price = last.close, priceAt = last.start)
            val pos = b.position
            if (pos != null) {
                val mid = last?.close
                val since = minutes.filter { !it.start.isBefore(pos.entryTime.withSecond(0).withNano(0)) }
                val why = GoldLiquidity.exitReason(pos.level, pos.target, pos.signalBar, pos.entryTime, hourly, since, t)
                if (why != null && mid != null) {
                    val px = GoldLiquidity.exitPrice(why, pos.target, mid)
                    val tr = Trade(pos.entry, px, pos.entryTime, t, b.lots, why, GoldLiquidity.pnl(pos.entry, px, b.lots))
                    b = b.copy(position = null, trades = b.trades + tr, status = "Sold: ${label(why)}")
                    notify("SELL XAUUSD now (paper): ${label(why)}",
                        "Paper sold %.2f lot at %.2f, P&L %s. Futures price: XM's XAUUSD sits a few dollars lower.".format(
                            java.util.Locale.ENGLISH, b.lots, px, usd(tr.pnl)), Notifier.SELL)
                } else b = b.copy(status = "Holding the buy from %.2f".format(java.util.Locale.ENGLISH, pos.entry))
            } else if (b.armed) {
                save(b)
                val status = decide(_book.value, hourly, last, t)
                b = _book.value.copy(status = status)
            }
            save(b)
        }
    }

    /** The buy decision on the last completed candle; returns the status line. */
    private suspend fun decide(b: Book, hourly: List<Bar>, last: Bar?, t: LocalDateTime): String {
        if (!GoldLiquidity.weekday(t)) return "Weekend: gold is closed"
        val bar = hourly.lastOrNull() ?: return "Loading the 1-hour candles"
        val entryAt = bar.start.plusMinutes(GoldLiquidity.MINUTES.toLong())
        if (b.decided == bar.start.toString()) return if (GoldLiquidity.inSession(t)) "Armed: waiting for the next 1-hour candle" else "Outside 07:00-21:00 UTC"
        save(b.copy(decided = bar.start.toString()))
        if (!GoldLiquidity.mayEnterAt(entryAt) || t.isAfter(entryAt.plusMinutes(20))) return "No entry now (entries 08:00-19:00 UTC)"
        val s = GoldLiquidity.signal(hourly) ?: return "No liquidity break on the ${hhmm(bar.start)} candle"
        val mid = last?.close ?: return "No price to buy at"
        val px = GoldLiquidity.buyPrice(mid)
        save(_book.value.copy(position = Position(px, t, bar.start, s.level, s.target)))
        notify("BUY XAUUSD now (paper): liquidity break",
            ("The ${hhmm(bar.start)} UTC 1-hour candle took a liquidity pool above %.2f. Paper bought %.2f lot at %.2f" +
                (s.target?.let { ", target the next level %.2f".format(java.util.Locale.ENGLISH, it) } ?: "") +
                ". Futures price: XM's XAUUSD sits a few dollars lower.").format(java.util.Locale.ENGLISH, s.level, b.lots, px), Notifier.BUY)
        return "Bought at %.2f".format(java.util.Locale.ENGLISH, px)
    }

    // ---- prices ------------------------------------------------------------------

    private var historyCache: Pair<LocalDateTime, List<Bar>>? = null

    /** 1-hour candles of the last three months (refreshed once an hour). */
    private suspend fun history(): List<Bar> {
        if (testMinutes != null) return emptyList()
        val t = now()
        historyCache?.let { (at, bars) -> if (at.plusMinutes(55).isAfter(t)) return bars }
        val bars = runCatching { parse(Net.getJson(HOURS_URL, tries = 3, timeoutMs = Net.QUICK_READ_MS, connectMs = Net.QUICK_CONNECT_MS)) }.getOrDefault(emptyList())
        if (bars.isNotEmpty()) historyCache = t to bars
        return bars
    }

    /** Today's 1-minute candles (the latest price, the hour being formed, and the target check). */
    private suspend fun minutes(t: LocalDateTime): List<Bar> =
        testMinutes?.invoke(t) ?: runCatching {
            parse(Net.getJson(MINUTES_URL, tries = 3, timeoutMs = Net.QUICK_READ_MS, connectMs = Net.QUICK_CONNECT_MS))
        }.getOrDefault(emptyList()).filter { !it.start.plusMinutes(1).isAfter(t) }

    /** Yahoo's chart JSON as bars with UTC starts; candles with a missing price are skipped. */
    fun parse(o: JSONObject): List<Bar> {
        val r = o.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val ts = r.optJSONArray("timestamp") ?: return emptyList()
        val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val (op, hi, lo, cl) = listOf("open", "high", "low", "close").map { q.getJSONArray(it) }
        return (0 until ts.length()).mapNotNull { i ->
            if (op.isNull(i) || hi.isNull(i) || lo.isNull(i) || cl.isNull(i)) null
            else Bar(LocalDateTime.ofInstant(Instant.ofEpochSecond(ts.getLong(i)), ZoneOffset.UTC), op.getDouble(i), hi.getDouble(i), lo.getDouble(i), cl.getDouble(i))
        }
    }

    // ---- helpers -------------------------------------------------------------------

    fun label(why: String): String = when (why) {
        "next_liquidity" -> "reached the next liquidity level"
        "failed_break" -> "the break failed"
        "new_liquidity" -> "new liquidity formed above"
        "cut_off" -> "20:40 UTC cut-off"
        else -> why
    }

    fun usd(x: Double): String = (if (x < 0) "-$" else "+$") + "%,.2f".format(java.util.Locale.ENGLISH, kotlin.math.abs(x))
    private fun hhmm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)

    private fun notify(title: String, text: String, channel: String) {
        if (::app.isInitialized) runCatching { Notifier.post(app, 7100, channel, title, text) }
        runCatching { Diag.record("gold", "$title - $text") }
    }

    private suspend fun edit(f: (Book) -> Book) = lock.withLock { save(f(_book.value)) }

    private fun save(b: Book) {
        _book.value = b
        if (::file.isInitialized) runCatching { Vault.writeFile(file, toJson(b).toString().toByteArray(Charsets.UTF_8)) }
    }

    private fun read(): Book? = Vault.readFileSteady(file)?.let { fromJson(JSONObject(String(it, Charsets.UTF_8))) }

    private fun toJson(b: Book) = JSONObject().put("start", b.start).put("lots", b.lots).put("armed", b.armed).put("status", b.status)
        .apply { b.decided?.let { put("decided", it) }; b.price?.let { put("price", it) }; b.priceAt?.let { put("priceAt", it.toString()) } }
        .apply {
            b.position?.let { p -> put("pos", JSONObject().put("entry", p.entry).put("at", p.entryTime.toString()).put("bar", p.signalBar.toString())
                .put("level", p.level).apply { p.target?.let { put("target", it) } }) }
        }
        .put("trades", JSONArray().apply {
            b.trades.forEach { t -> put(JSONObject().put("entry", t.entry).put("exit", t.exit).put("in", t.entryTime.toString())
                .put("out", t.exitTime.toString()).put("lots", t.lots).put("why", t.why).put("pnl", t.pnl)) }
        })

    private fun fromJson(o: JSONObject): Book {
        val pos = o.optJSONObject("pos")?.let { p ->
            Position(p.getDouble("entry"), LocalDateTime.parse(p.getString("at")), LocalDateTime.parse(p.getString("bar")), p.getDouble("level"),
                if (p.has("target")) p.getDouble("target") else null)
        }
        val tr = o.optJSONArray("trades")?.let { a ->
            (0 until a.length()).map { i -> a.getJSONObject(i).let { t ->
                Trade(t.getDouble("entry"), t.getDouble("exit"), LocalDateTime.parse(t.getString("in")), LocalDateTime.parse(t.getString("out")),
                    t.getDouble("lots"), t.getString("why"), t.getDouble("pnl"))
            } }
        } ?: emptyList()
        return Book(o.optDouble("start", DEFAULT_BALANCE), o.optDouble("lots", DEFAULT_LOTS), o.optBoolean("armed", false), pos, tr,
            o.optString("decided").ifBlank { null }, o.optString("status", "Not armed"),
            if (o.has("price")) o.getDouble("price") else null, o.optString("priceAt").ifBlank { null }?.let { LocalDateTime.parse(it) })
    }
}
