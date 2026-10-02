package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.gold.GoldDip
import com.optionslab.engine.gold.GoldLiquidity
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
 * IraGoldAlgo's third arm, [GoldDip] ("Dip 1h+30m"): buy a 30-minute dip that turns up after two green hours, with a
 * 2-ATR profit lock, out after 8 hours or before the daily break. Paper only, like the other two: it never sends an
 * order; it notifies the owner on every buy and sell. It runs on [GoldPaper]'s price pass, shares its lot size and paper
 * account, and keeps its own switch, open trade and closed trades here, encrypted. All times UTC.
 */
object GoldDipPaper {
    const val NAME = "Dip 1h+30m"

    data class Position(val entry: Double, val entryTime: LocalDateTime, val lots: Double, val atr: Double,
                        val peak: Double, val seen: LocalDateTime)

    data class Book(
        val armed: Boolean = false,
        val position: Position? = null,
        val trades: List<GoldPaper.Trade> = emptyList(),
        val decided: String? = null,           // the last 30-minute candle decided on
        val status: String = "Not armed",
        val lastSignal: String? = null,
    ) {
        val realized: Double get() = trades.sumOf { it.pnl }
        fun open(mid: Double?): Double? = position?.let { p -> mid?.let { GoldLiquidity.pnl(p.entry, GoldLiquidity.sellPrice(it), p.lots) } }
        val stop: Double? get() = position?.let { GoldDip.lockStop(it.entry, it.peak, it.atr) }
    }

    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()
    private val _book = MutableStateFlow(Book())
    val book: StateFlow<Book> = _book

    internal suspend fun replaceForTest(b: Book) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }
        lock.withLock { save(b) }
    }

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.noBackupFilesDir, "gold-dip.vault")
        _book.value = runCatching { read() }.getOrNull() ?: Book()
    }

    suspend fun setArmed(on: Boolean) = edit { it.copy(armed = on, decided = if (on) ARMED else it.decided, status = if (on) WAITING else "Not armed") }

    suspend fun reset() = edit { it.copy(position = null, trades = emptyList(), decided = if (it.armed) ARMED else null,
        status = if (it.armed) WAITING else "Not armed") }

    /** How long after a candle's close (on the feed) a buy may still be taken; later, the candle is skipped. */
    const val LATE_MINUTES = 20L
    const val WAITING = "Armed: waiting for a dip after two green hours"
    private const val ARMED = "armed"

    /**
     * One pass, from [GoldPaper.tick]: [thirty] the 30-minute candles completed by [fed], [hours] the completed 1-hour
     * candles, [minutes] today's 1-minute candles, [t] the clock, [lots] the paper lot size. Never throws.
     */
    suspend fun step(thirty: List<Bar>, hours: List<Bar>, minutes: List<Bar>, t: LocalDateTime, fed: LocalDateTime, lots: Double) = runCatching {
        lock.withLock {
            var b = _book.value
            val last = minutes.lastOrNull()
            // Exits first: the lock on the minutes not yet seen, then the time limits.
            b.position?.let { p ->
                var peak = p.peak; var seen = p.seen; var hit: Double? = null
                for (m in minutes) {
                    if (!m.start.isAfter(seen)) continue
                    val stop = GoldDip.lockStop(p.entry, peak, p.atr)
                    if (stop != null && GoldLiquidity.sellPrice(m.low) <= stop) { hit = minOf(stop, GoldLiquidity.sellPrice(m.open)); break }
                    peak = maxOf(peak, GoldLiquidity.sellPrice(m.high)); seen = m.start
                }
                val timeWhy = GoldDip.timeExit(p.entryTime, t)
                b = when {
                    hit != null -> sell(b, p, hit, t, "dip_lock")
                    timeWhy != null && last != null -> sell(b, p, GoldLiquidity.sellPrice(last.close), t, timeWhy)
                    else -> b.copy(position = p.copy(peak = peak, seen = seen),
                        status = "Holding the buy from %.2f".format(Locale.ENGLISH, p.entry))
                }
            }
            val bar = thirty.lastOrNull()
            if (b.position == null && b.armed && bar != null && b.decided != bar.start.toString()) {
                val justArmed = b.decided == ARMED
                val closeAt = bar.start.plusMinutes(GoldDip.MINUTES)
                val priced = last != null && !last.start.isBefore(t.minusMinutes(30))
                when {
                    !priced -> b = b.copy(status = "Waiting for a fresh gold price")
                    else -> {
                        b = b.copy(decided = bar.start.toString())
                        val s = GoldDip.signal(thirty, hours)
                        b = when {
                            justArmed -> b.copy(status = WAITING)
                            s == null -> b.copy(status = "No dip set-up on the ${GoldPaper.when_(bar.start)} candle")
                            fed.isAfter(closeAt.plusMinutes(LATE_MINUTES)) -> b.copy(status = "Missed the ${GoldPaper.when_(bar.start)} set-up: the phone checked late")
                            else -> {
                                val px = GoldLiquidity.buyPrice(last!!.close)
                                notify("BUY XAUUSD now (paper): dip after two green hours",
                                    ("Two green 1-hour candles, then a 30-minute dip that turned up (${GoldPaper.when_(bar.start)}). Paper bought %.2f lot at %.2f. " +
                                        "Profit lock 2 ATR (%.2f) from the top once 1 ATR up; out after 8 hours or before the daily break. " +
                                        "Futures price: XM's XAUUSD sits a few dollars lower.").format(Locale.ENGLISH, lots, px, GoldDip.LOCK_GIVEBACK * s.atr), Notifier.BUY)
                                b.copy(position = Position(px, t, lots, s.atr, GoldLiquidity.sellPrice(last.close), last.start),
                                    status = "Bought at %.2f".format(Locale.ENGLISH, px),
                                    lastSignal = "${GoldPaper.when_(bar.start)}: dip turned up, bought at %.2f".format(Locale.ENGLISH, px))
                            }
                        }
                    }
                }
            } else if (b.position == null && !b.armed && b.status != "Not armed" && !b.status.startsWith("Sold")) {
                b = b.copy(status = "Not armed")
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

    private fun notify(title: String, text: String, channel: String) {
        if (::app.isInitialized) runCatching { Notifier.post(app, 7102, channel, title, text) }
        runCatching { Diag.record("gold", "$title - $text") }
    }

    private suspend fun edit(f: (Book) -> Book) = lock.withLock { save(f(_book.value)) }

    private fun save(b: Book) {
        _book.value = b
        if (::file.isInitialized) runCatching { Vault.writeFile(file, toJson(b).toString().toByteArray(Charsets.UTF_8)) }
    }

    private fun read(): Book? = Vault.readFileSteady(file)?.let { fromJson(JSONObject(String(it, Charsets.UTF_8))) }

    private fun toJson(b: Book) = JSONObject().put("armed", b.armed).put("status", b.status)
        .apply { b.decided?.let { put("decided", it) }; b.lastSignal?.let { put("lastSignal", it) } }
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
            o.optString("lastSignal").ifBlank { null })
    }
}
