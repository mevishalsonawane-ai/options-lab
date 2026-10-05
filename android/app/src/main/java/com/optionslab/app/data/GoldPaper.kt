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
 * IraGoldAlgo's paper account and its one strategy ([GoldLiquidity], XAUUSD 1-hour liquidity, buys only, 24x5). Paper only:
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
        val lastSignal: String? = null,        // the last buy, said for Home ("Mon 10:00 UTC (15:30 IST): broke 2388.00")
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
    /** The last 30 one-hour closes of the session (Home's small chart); not saved. */
    private val _chart = MutableStateFlow<List<Double>>(emptyList())
    val chart: StateFlow<List<Double>> = _chart

    /** TEST ONLY: the feed's 1-minute bars at a moment (UTC), so a test can run the strategy without the network. */
    @Volatile internal var testMinutes: ((LocalDateTime) -> List<Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test feed exists only in debug builds" }; field = v }
    /** TEST ONLY: the feed's three months of 1-hour candles at a moment (UTC); without it a test has no history. */
    @Volatile internal var testHistory: ((LocalDateTime) -> List<Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test feed exists only in debug builds" }; field = v }
    @Volatile internal var testNow: LocalDateTime? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test clock exists only in debug builds" }; field = v }

    /** TEST ONLY: replace the book (screens are tested on prepared accounts). Throws outside debug builds. */
    internal suspend fun replaceForTest(b: Book) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }
        GoldBooks.awaitLoaded()
        lock.withLock { readFailed = false; save(b) }
    }

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.noBackupFilesDir, "gold.vault")
    }

    /** Reads the saved book (on [GoldBooks]' background thread; every change waits for it). */
    internal fun load() {
        val r = runCatching { read() }
        // A saved book that is there but could not be read is never written over by the minute passes (it may read
        // back next time); the empty book shows meanwhile. Boss's own change (a switch, the lots, a reset) writes again.
        readFailed = r.isFailure
        if (r.isFailure) runCatching { Diag.record("gold", "${file.name} could not be read; it is not saved over") }
        _book.value = r.getOrNull() ?: Book()
    }

    /** The saved book could not be read at the start: only Boss's own change writes over it ([load]). */
    @Volatile private var readFailed = false

    fun now(): LocalDateTime = testNow ?: LocalDateTime.now(ZoneOffset.UTC)

    // ---- the owner's switches ---------------------------------------------------

    suspend fun setArmed(on: Boolean) = edit { it.copy(armed = on, decided = if (on) ARMED else it.decided, status = if (on) WAITING else "Not armed") }

    suspend fun setLots(lots: Double) = edit { it.copy(lots = lots) }

    /** A fresh paper account with [balance]; the switch and the lot size are kept, an open trade and history go. */
    suspend fun reset(balance: Double) {
        edit { it.copy(start = balance, position = null, trades = emptyList(), decided = null) }
        GoldTrendPaper.reset()
        GoldDipPaper.reset()
        GoldTasPaper.reset()
    }

    // ---- the minute pass ---------------------------------------------------------

    /** One pass: read prices, sell an open buy whose exit came, else buy on a fresh signal. Never throws. */
    suspend fun tick() = runCatching {
        GoldBooks.awaitLoaded()
        lock.withLock {
            val t = now()
            val minutes = minutes(t)
            // A candle is complete when the feed has reached its end, not when the clock has: Yahoo's COMEX prices run
            // about 10 minutes late, and deciding at the hour read a candle without its last 10 minutes.
            val fed = minutes.lastOrNull()?.start?.plusMinutes(1)?.let { if (it.isBefore(t)) it else t } ?: t
            val hourly = GoldLiquidity.completed(GoldLiquidity.hourly(history() + minutes), fed)
            if (hourly.isNotEmpty()) _chart.value = hourly.takeLast(30).map { it.close }
            val last = minutes.lastOrNull()
            var b = _book.value
            if (last != null) b = b.copy(price = last.close, priceAt = last.start)
            val pos = b.position
            if (pos != null) {
                val mid = last?.close
                // The target is checked on today's 1-minute bars since the buy, and on the completed 1-hour candles that
                // began after the buy's hour: a target touched while the phone slept (overnight) is no longer missed.
                val afterHour = pos.entryTime.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                val since = hourly.filter { it.start.isAfter(afterHour) } +
                    minutes.filter { !it.start.isBefore(pos.entryTime.withSecond(0).withNano(0)) }
                val why = GoldLiquidity.exitReason(pos.level, pos.target, pos.signalBar, pos.entryTime, hourly, since, t)
                if (why != null && mid != null) {
                    val px = GoldLiquidity.exitPrice(why, pos.target, mid)
                    val tr = Trade(pos.entry, px, pos.entryTime, t, b.lots, why, GoldLiquidity.pnl(pos.entry, px, b.lots))
                    b = b.copy(position = null, trades = b.trades + tr, status = "Sold: ${label(why)}")
                    notify("SELL XAUUSD now (paper): ${label(why)}",
                        "${when_(t)}: paper sold %.2f lot at %.2f, P&L %s. Futures price: XM's XAUUSD sits a few dollars lower.".format(
                            java.util.Locale.ENGLISH, b.lots, px, usd(tr.pnl)), Notifier.SELL)
                } else b = b.copy(status = "Holding the buy from %.2f".format(java.util.Locale.ENGLISH, pos.entry))
            } else if (b.armed) {
                save(b)
                val status = decide(_book.value, hourly, last, t, fed)
                b = _book.value.copy(status = status)
            }
            save(b)
            // The trend arm on the same prices (its own book: nothing it does can touch this one's).
            GoldTrendPaper.step(hourly, minutes, t, fed, b.lots)
            // The Dip arm on its 30-minute candles (five days of Yahoo's 30-minute feed and today's minutes).
            if (GoldDipPaper.book.value.let { it.armed || it.position != null }) {
                val thirty = com.optionslab.engine.gold.GoldDip.completed(com.optionslab.engine.gold.GoldDip.thirty(history30() + minutes), fed)
                GoldDipPaper.step(thirty, hourly, minutes, t, fed, b.lots)
            }
            // The TAS arm on the same 1-hour candles.
            if (GoldTasPaper.book.value.let { it.armed || it.position != null }) GoldTasPaper.step(hourly, minutes, t, fed, b.lots)
        }
    }

    /** The buy decision on the last completed candle; returns the status line. */
    private suspend fun decide(b: Book, hourly: List<Bar>, last: Bar?, t: LocalDateTime, fed: LocalDateTime = t): String {
        if (!GoldLiquidity.weekday(t)) return "Weekend: gold is closed"
        val bar = hourly.lastOrNull() ?: return "Loading the 1-hour candles"
        // Too few candles to find levels (the three-month history did not arrive): say so, and leave the candle undecided
        // so it is decided once the history comes - it used to read "No liquidity break", as if it had looked.
        if (hourly.size < MIN_CANDLES) return "Waiting for the 1-hour price history (${hourly.size} of $MIN_CANDLES candles)"
        val entryAt = bar.start.plusMinutes(GoldLiquidity.MINUTES.toLong())
        // Already decided: keep what that decision said (it used to be replaced 5 minutes later by a stale "next decision").
        if (b.decided == bar.start.toString()) return b.status
        val justArmed = b.decided == ARMED
        save(b.copy(decided = bar.start.toString()))
        // Armed after a candle's decision time, or a candle whose buy would start outside 08:00-19:00 UTC: say when the
        // next decision is (UTC and IST), not "no entry", which read as if the hours were wrong.
        if (!GoldLiquidity.mayEnterAt(entryAt)) return WAITING
        // The phone ran the check too late to buy at this candle's close (Android delays alarms that are not precise):
        // say so, and record it, instead of passing over the candle silently.
        if (fed.isAfter(entryAt.plusMinutes(LATE_MINUTES))) {
            if (justArmed) return WAITING
            if (t.isBefore(entryAt.plusMinutes(60))) runCatching { Diag.record("gold", "missed the ${when_(bar.start)} candle: checked ${java.time.Duration.between(entryAt, t).toMinutes()} min late") }
            return if (t.isBefore(entryAt.plusMinutes(60))) "Missed the ${when_(bar.start)} candle: the phone checked " +
                "${java.time.Duration.between(entryAt, t).toMinutes()} min late."
            else WAITING
        }
        val s = GoldLiquidity.signal(hourly) ?: return "No liquidity break on the ${when_(bar.start)} candle"
        val mid = last?.close ?: return "No price to buy at"
        val px = GoldLiquidity.buyPrice(mid)
        save(_book.value.copy(position = Position(px, t, bar.start, s.level, s.target),
            lastSignal = "${when_(bar.start)}: broke %.2f, bought at %.2f".format(java.util.Locale.ENGLISH, s.level, px)))
        notify("BUY XAUUSD now (paper): liquidity break",
            ("The ${when_(bar.start)} 1-hour candle took a liquidity pool above %.2f. Paper bought %.2f lot at %.2f" +
                (s.target?.let { ", target %.2f (the second level up)".format(java.util.Locale.ENGLISH, it) } ?: "") +
                ". Futures price: XM's XAUUSD sits a few dollars lower.").format(java.util.Locale.ENGLISH, s.level, b.lots, px), Notifier.BUY)
        return "Bought at %.2f".format(java.util.Locale.ENGLISH, px)
    }

    // ---- prices ------------------------------------------------------------------

    /** The fewest completed 1-hour candles the levels need (the swing lookback each side, and two). */
    val MIN_CANDLES = 2 * com.optionslab.engine.orb.LiquidityRules.SWING_LOOKBACK + 2

    /** [Book.decided] right after arming: the first candle seen then was not missed, it came before the arming. */
    private const val ARMED = "armed"

    /**
     * How far past a candle's close the prices may have moved for a buy still to be taken. Counted on the feed (which
     * runs ~10 minutes behind the clock), so its delay does not use the margin up; a check later than that skips the candle.
     */
    const val LATE_MINUTES = 20L

    private var historyCache: Pair<LocalDateTime, List<Bar>>? = null

    /** 1-hour candles of the last three months (refreshed once an hour). */
    private suspend fun history(): List<Bar> {
        testHistory?.let { return it(now()) }
        if (testMinutes != null) return emptyList()
        val t = now()
        historyCache?.let { (at, bars) -> if (at.plusMinutes(55).isAfter(t)) return bars }
        val bars = runCatching { parse(Net.getJson(HOURS_URL, tries = 3, timeoutMs = Net.QUICK_READ_MS, connectMs = Net.QUICK_CONNECT_MS)) }.getOrDefault(emptyList())
        if (bars.isNotEmpty()) historyCache = t to bars
        return bars
    }

    private const val THIRTY_URL = "https://query1.finance.yahoo.com/v8/finance/chart/GC%3DF?interval=30m&range=5d"
    private var history30Cache: Pair<LocalDateTime, List<Bar>>? = null

    /** TEST ONLY: the feed's 30-minute candles at a moment (UTC). */
    @Volatile internal var testHistory30: ((LocalDateTime) -> List<Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test feed exists only in debug builds" }; field = v }

    /** 30-minute candles of the last five days (refreshed every 25 minutes): the Dip arm's ATR and colours. */
    private suspend fun history30(): List<Bar> {
        testHistory30?.let { return it(now()) }
        if (testMinutes != null) return emptyList()
        val t = now()
        history30Cache?.let { (at, bars) -> if (at.plusMinutes(25).isAfter(t)) return bars }
        val bars = runCatching { parse(Net.getJson(THIRTY_URL, tries = 3, timeoutMs = Net.QUICK_READ_MS, connectMs = Net.QUICK_CONNECT_MS)) }.getOrDefault(emptyList())
        if (bars.isNotEmpty()) history30Cache = t to bars
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
        "next_liquidity" -> "reached its target liquidity level"
        "failed_break" -> "the break failed"
        "new_liquidity" -> "new liquidity formed above"
        "cut_off" -> "Friday 20:40 UTC (Sat 02:10 IST) cut-off before the weekend"
        "trend_down" -> "the 4-hour trend turned down"
        "giveback" -> "profit lock: fell 4 ATR from its top"
        "dip_lock" -> "profit lock: fell 2 ATR from its top"
        "dip_time" -> "8 hours after the buy"
        "dip_break" -> "before the daily break (02:25 IST)"
        "tas_t1" -> "first target (1.5 R)"
        "tas_t2" -> "second target (2.5 R)"
        "tas_t3" -> "third target (3.5 R)"
        "tas_stop" -> "stop: the tracker line"
        "tas_even" -> "stop at the buy price after the first target"
        "tas_down" -> "the 1-hour tracker turned down"
        else -> why
    }

    /** A UTC time with its IST beside it: "10:00 UTC (15:30 IST)". */
    /** The arm a closed trade came from (the trend arm's exits are its own). */
    fun arm(t: Trade): String = when {
        t.why == "trend_down" || t.why == "giveback" -> GoldTrendPaper.NAME
        t.why.startsWith("dip_") -> GoldDipPaper.NAME
        t.why.startsWith("tas_") -> GoldTasPaper.NAME
        else -> "Liquidity 1h"
    }

    fun when_(t: LocalDateTime): String = "${hhmm(t)} UTC (${hhmm(t.plusMinutes(330))} IST)"

    /** True when the last price is more than 20 minutes old while gold is trading (the feed is down; it always runs ~10 late). */
    fun stale(b: Book, t: LocalDateTime = now()): Boolean =
        GoldLiquidity.inSession(t) && (b.priceAt == null || b.priceAt.isBefore(t.minusMinutes(20)))

    /** The status while armed with nothing decided yet (the time is on its own line, worked out afresh each time). */
    const val WAITING = "Armed: waiting for the next candle"

    /** Yahoo's COMEX prices arrive about this many minutes late, so a candle is decided that long after it closes. */
    const val FEED_DELAY_MINUTES = 10L

    /**
     * When the next candle that may take a buy ([GoldLiquidity.mayEnterAt]) will be decided, UTC and IST: its close plus
     * the feed's delay ("11:10 UTC (16:40 IST)"). Between a close and its decision, that pending decision is the next one.
     */
    fun nextDecision(t: LocalDateTime): String {
        var h = t.minusMinutes(FEED_DELAY_MINUTES).withMinute(0).withSecond(0).withNano(0).plusHours(1)
        while (!GoldLiquidity.mayEnterAt(h)) h = h.plusHours(1)
        val at = h.plusMinutes(FEED_DELAY_MINUTES)
        val day = if (at.toLocalDate() == t.toLocalDate()) "" else "${at.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)} "
        return "$day${hhmm(at)} UTC (${hhmm(at.plusMinutes(330))} IST)"
    }

    fun usd(x: Double): String = (if (x < 0) "-$" else "+$") + "%,.2f".format(java.util.Locale.ENGLISH, kotlin.math.abs(x))
    private fun hhmm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)

    private fun notify(title: String, text: String, channel: String) {
        if (::app.isInitialized) runCatching { Notifier.post(app, 7100, channel, title, text) }
        runCatching { Diag.record("gold", "$title - $text") }
    }

    private suspend fun edit(f: (Book) -> Book) { GoldBooks.awaitLoaded(); lock.withLock { readFailed = false; save(f(_book.value)) } }

    private fun save(b: Book) {
        _book.value = b
        if (readFailed) return
        if (::file.isInitialized) runCatching { Vault.writeFile(file, toJson(b).toString().toByteArray(Charsets.UTF_8)) }
    }

    private fun read(): Book? = Vault.readFileSteady(file)?.let { fromJson(JSONObject(String(it, Charsets.UTF_8))) }

    private fun toJson(b: Book) = JSONObject().put("start", b.start).put("lots", b.lots).put("armed", b.armed).put("status", b.status)
        .apply { b.decided?.let { put("decided", it) }; b.price?.let { put("price", it) }; b.priceAt?.let { put("priceAt", it.toString()) }
            b.lastSignal?.let { put("lastSignal", it) } }
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
            if (o.has("price")) o.getDouble("price") else null, o.optString("priceAt").ifBlank { null }?.let { LocalDateTime.parse(it) },
            o.optString("lastSignal").ifBlank { null })
    }
}
