package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.gold.GoldLiquidity
import com.optionslab.engine.gold.GoldTrend
import com.optionslab.engine.orb.Bar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.util.Locale

/**
 * IraGoldAlgo's second arm, [GoldTrend]: buy while the 4-hour Supertrend points up, with a 4-ATR profit lock. Paper
 * only, like [GoldPaper]: it never sends an order; it notifies the owner on every buy and sell. It shares GoldPaper's
 * price pass (GoldPaper.tick calls [step] with the candles it read), its lot size and its paper account; its own switch,
 * open trade and closed trades are kept here, encrypted, apart from the liquidity arm's (so neither can disturb the
 * other). All times UTC.
 */
object GoldTrendPaper {
    const val NAME = "Trend 4h"

    data class Position(val entry: Double, val entryTime: LocalDateTime, val lots: Double, val atr: Double,
                        val peak: Double,                   // the highest bid since the entry
                        val seen: LocalDateTime)            // the minutes up to here are in [peak]

    data class Book(
        val armed: Boolean = false,
        val position: Position? = null,
        val trades: List<GoldPaper.Trade> = emptyList(),
        val decided: String? = null,           // the last 4-hour candle decided on
        val status: String = "Not armed",
        val lastSignal: String? = null,
        val up: Boolean? = null,               // the trend after the last completed 4-hour candle
        val line: Double? = null,              // its Supertrend line
        val waitFlip: Boolean = false,         // sold by the profit lock: no buy until the trend turns down and up
    ) {
        val realized: Double get() = trades.sumOf { it.pnl }
        fun open(mid: Double?): Double? = position?.let { p -> mid?.let { GoldLiquidity.pnl(p.entry, GoldLiquidity.sellPrice(it), p.lots) } }
        /** The profit lock's stop now (a bid price), or null before the buy is 1 ATR up. */
        val stop: Double? get() = position?.let { GoldTrend.lockStop(it.entry, it.peak, it.atr) }
    }

    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()
    private val _book = MutableStateFlow(Book())
    val book: StateFlow<Book> = _book

    internal suspend fun replaceForTest(b: Book) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }
        GoldBooks.awaitLoaded()
        lock.withLock { save(b) }
    }

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.noBackupFilesDir, "gold-trend.vault")
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

    suspend fun setArmed(on: Boolean) = edit { it.copy(armed = on, decided = if (on) ARMED else it.decided, status = if (on) WAITING else "Not armed") }

    /** With the paper account's reset: the open trade and the history go; the switch stays. */
    suspend fun reset() = edit { it.copy(position = null, trades = emptyList(), decided = if (it.armed) ARMED else null, waitFlip = false,
        status = if (it.armed) WAITING else "Not armed") }

    /**
     * One pass, from [GoldPaper.tick]: [hourly] the 1-hour candles completed by [fed] (the feed's time), [minutes] today's
     * 1-minute candles, [t] the clock, [lots] the paper lot size. Never throws.
     */
    suspend fun step(hourly: List<Bar>, minutes: List<Bar>, t: LocalDateTime, fed: LocalDateTime, lots: Double) = runCatching {
        GoldBooks.awaitLoaded()
        lock.withLock {
            val bars = GoldTrend.completed(GoldTrend.chart(hourly), fed)
            val st = GoldTrend.state(bars)
            val last = minutes.lastOrNull()
            var b = _book.value
            if (st != null) b = b.copy(up = st.up, line = st.line)
            // The profit lock, on the minutes not yet seen.
            b.position?.let { p ->
                var peak = p.peak; var seen = p.seen; var hit: Pair<LocalDateTime, Double>? = null
                for (m in minutes) {
                    if (!m.start.isAfter(seen)) continue
                    val stop = GoldTrend.lockStop(p.entry, peak, p.atr)
                    if (stop != null && GoldLiquidity.sellPrice(m.low) <= stop) { hit = m.start to minOf(stop, GoldLiquidity.sellPrice(m.open)); break }
                    peak = maxOf(peak, GoldLiquidity.sellPrice(m.high)); seen = m.start
                }
                if (hit != null) {
                    b = sell(b, p, hit.second, t, "giveback").copy(waitFlip = true)
                } else b = b.copy(position = p.copy(peak = peak, seen = seen))
            }
            val bar = bars.lastOrNull()
            if (st == null || bar == null) {
                if (b.armed && b.position == null) b = b.copy(status = "Loading the 4-hour candles")
                save(b); return@withLock
            }
            if (!st.up) b = b.copy(waitFlip = false)
            val fresh = b.decided != bar.start.toString()
            // A decision needs a fresh price: without one the candle stays undecided (a sell is not skipped for 4 hours).
            val priced = last != null && !last.start.isBefore(t.minusMinutes(30))
            if (fresh && GoldLiquidity.weekday(t) && !priced) {
                b = b.copy(status = "Waiting for a fresh gold price")
            } else if (fresh && GoldLiquidity.weekday(t)) {
                val justArmed = b.decided == ARMED
                b = b.copy(decided = bar.start.toString())
                val pos = b.position
                when {
                    pos != null && !st.up -> b = sell(b, pos, GoldLiquidity.sellPrice(last!!.close), t, "trend_down")
                    pos != null -> b = b.copy(status = "Holding the buy from %.2f: trend up".format(Locale.ENGLISH, pos.entry))
                    !b.armed -> Unit
                    justArmed -> b = b.copy(status = if (st.up) "Armed: the trend is up; buys at the next 4-hour close if it stays up"
                        else "Armed: the trend is down; waiting for it to turn up")
                    !st.up -> b = b.copy(status = "Trend down after the ${GoldPaper.when_(bar.start)} candle: no buy")
                    b.waitFlip -> b = b.copy(status = "Sold by the profit lock: waiting for the trend to turn down and up again")
                    else -> {
                        val px = GoldLiquidity.buyPrice(last!!.close)
                        b = b.copy(position = Position(px, t, lots, st.atr, GoldLiquidity.sellPrice(last.close), last.start),
                            status = "Bought at %.2f".format(Locale.ENGLISH, px),
                            lastSignal = "${GoldPaper.when_(bar.start)}: trend up, bought at %.2f".format(Locale.ENGLISH, px))
                        notify("BUY XAUUSD now (paper): 4-hour trend up",
                            ("The ${GoldPaper.when_(bar.start)} 4-hour candle closed with the trend up (Supertrend line %.2f). Paper bought %.2f lot at %.2f. " +
                                "Held while the trend stays up; profit lock 4 ATR (%.2f) from the top once 1 ATR up. Futures price: XM's XAUUSD sits a few dollars lower.")
                                .format(Locale.ENGLISH, st.line, lots, px, GoldTrend.LOCK_GIVEBACK * st.atr), Notifier.BUY)
                    }
                }
            } else if (b.position != null && !b.status.startsWith("Holding")) {
                b = b.copy(status = "Holding the buy from %.2f: trend up".format(Locale.ENGLISH, b.position!!.entry))
            }
            save(b)
        }
    }

    private fun sell(b: Book, p: Position, px: Double, t: LocalDateTime, why: String): Book {
        val tr = GoldPaper.Trade(p.entry, px, p.entryTime, t, p.lots, why, GoldLiquidity.pnl(p.entry, px, p.lots))
        notify("SELL XAUUSD now (paper): ${GoldPaper.label(why)}",
            "${GoldPaper.when_(t)}: paper sold %.2f lot at %.2f, P&L %s. Futures price: XM's XAUUSD sits a few dollars lower.".format(
                Locale.ENGLISH, p.lots, px, GoldPaper.usd(tr.pnl)), Notifier.SELL)
        return b.copy(position = null, trades = b.trades + tr, status = "Sold: ${GoldPaper.label(why)}")
    }

    private const val ARMED = "armed"
    const val WAITING = "Armed: waiting for the next 4-hour candle"

    /** When the next 4-hour candle is decided (its close plus the feed's delay), UTC and IST: "12:10 UTC (17:40 IST)". */
    fun nextDecision(t: LocalDateTime): String {
        val back = t.minusMinutes(GoldPaper.FEED_DELAY_MINUTES)
        var h = GoldTrend.slot(back).plusHours(GoldTrend.CHART_HOURS)
        while (!GoldLiquidity.weekday(h)) h = h.plusHours(GoldTrend.CHART_HOURS)
        val at = h.plusMinutes(GoldPaper.FEED_DELAY_MINUTES)
        val day = if (at.toLocalDate() == t.toLocalDate()) "" else "${at.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)} "
        return "$day%02d:%02d UTC (%02d:%02d IST)".format(at.hour, at.minute, at.plusMinutes(330).hour, at.plusMinutes(330).minute)
    }

    private fun notify(title: String, text: String, channel: String) {
        if (::app.isInitialized) runCatching { Notifier.post(app, 7101, channel, title, text) }
        runCatching { Diag.record("gold", "$title - $text") }
    }

    private suspend fun edit(f: (Book) -> Book) { GoldBooks.awaitLoaded(); lock.withLock { readFailed = false; save(f(_book.value)) } }

    private fun save(b: Book) {
        _book.value = b
        if (readFailed) return
        if (::file.isInitialized) runCatching { Vault.writeFile(file, toJson(b).toString().toByteArray(Charsets.UTF_8)) }
    }

    private fun read(): Book? = Vault.readFileSteady(file)?.let { fromJson(JSONObject(String(it, Charsets.UTF_8))) }

    private fun toJson(b: Book) = JSONObject().put("armed", b.armed).put("status", b.status).put("waitFlip", b.waitFlip)
        .apply { b.decided?.let { put("decided", it) }; b.lastSignal?.let { put("lastSignal", it) }
            b.up?.let { put("up", it) }; b.line?.let { put("line", it) } }
        .apply {
            b.position?.let { p -> put("pos", JSONObject().put("entry", p.entry).put("at", p.entryTime.toString()).put("lots", p.lots)
                .put("atr", p.atr).put("peak", p.peak).put("seen", p.seen.toString())) }
        }
        .put("trades", JSONArray().apply {
            b.trades.forEach { t -> put(JSONObject().put("entry", t.entry).put("exit", t.exit).put("in", t.entryTime.toString())
                .put("out", t.exitTime.toString()).put("lots", t.lots).put("why", t.why).put("pnl", t.pnl)) }
        })

    private fun fromJson(o: JSONObject): Book {
        val pos = o.optJSONObject("pos")?.let { p ->
            Position(p.getDouble("entry"), LocalDateTime.parse(p.getString("at")), p.getDouble("lots"), p.getDouble("atr"),
                p.getDouble("peak"), LocalDateTime.parse(p.getString("seen")))
        }
        val tr = o.optJSONArray("trades")?.let { a ->
            (0 until a.length()).map { i -> a.getJSONObject(i).let { t ->
                GoldPaper.Trade(t.getDouble("entry"), t.getDouble("exit"), LocalDateTime.parse(t.getString("in")), LocalDateTime.parse(t.getString("out")),
                    t.getDouble("lots"), t.getString("why"), t.getDouble("pnl"))
            } }
        } ?: emptyList()
        return Book(o.optBoolean("armed", false), pos, tr, o.optString("decided").ifBlank { null }, o.optString("status", "Not armed"),
            o.optString("lastSignal").ifBlank { null }, if (o.has("up")) o.getBoolean("up") else null,
            if (o.has("line")) o.getDouble("line") else null, o.optBoolean("waitFlip", false))
    }
}
