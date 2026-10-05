package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.gold.GoldLiquidity
import com.optionslab.engine.gold.GoldTas
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
 * IraGoldAlgo's fourth arm, [GoldTas] ("TAS 1h"): buy when the 1-hour trend tracker turns up with a good trend score;
 * the stop on the tracker line, sold when the tracker turns down (no targets: [GoldTas.TARGETS] is empty; the parts
 * logic below stays for a target list). Paper only, like the others: it never sends an order; it notifies the
 * owner on every buy and sell. It runs on [GoldPaper]'s price pass, shares its lot size and paper account, and keeps
 * its own switch, open trade and closed trades here, encrypted. All times UTC.
 */
object GoldTasPaper {
    const val NAME = "TAS 1h"

    data class Position(val entry: Double, val entryTime: LocalDateTime, val lots: Double,   // lots bought
                        val left: Double,                                                     // lots still held
                        val stop: Double, val risk: Double,                                   // the stop (bid) and R
                        val hit: Int,                                                         // targets reached
                        val seen: LocalDateTime)                                              // minutes checked up to here

    data class Book(
        val armed: Boolean = false,
        val position: Position? = null,
        val trades: List<GoldPaper.Trade> = emptyList(),
        val decided: String? = null,           // the last 1-hour candle decided on
        val status: String = "Not armed",
        val lastSignal: String? = null,
        val up: Boolean? = null,               // the tracker after the last completed hour
        val line: Double? = null,              // its line
        val score: Double? = null,             // the trend score, %
        val usedTurn: String? = null,          // the up-turn already bought (one buy per turn)
    ) {
        val realized: Double get() = trades.sumOf { it.pnl }
        fun open(mid: Double?): Double? = position?.let { p -> mid?.let { GoldLiquidity.pnl(p.entry, GoldLiquidity.sellPrice(it), p.left) } }
        /** The targets still ahead of the open buy. */
        val targets: List<Double> get() = position?.let { p -> GoldTas.targets(p.entry, p.entry - p.risk).drop(p.hit) } ?: emptyList()
    }

    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()
    private val _book = MutableStateFlow(Book())
    val book: StateFlow<Book> = _book

    internal suspend fun replaceForTest(b: Book) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }
        GoldBooks.awaitLoaded()
        lock.withLock { readFailed = false; save(b) }
    }

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.noBackupFilesDir, "gold-tas.vault")
    }

    /** Reads the saved book (on [GoldBooks]' background thread; every change waits for it). */
    internal fun load() {
        val r = runCatching { read() }
        // A saved book that is there but could not be read is never written over by the minute passes (it may read
        // back next time); the empty book shows meanwhile. Boss's own change reads it again first; only a reset writes over it ([edit]).
        readFailed = r.isFailure
        if (r.isFailure) runCatching { Diag.record("gold", "${file.name} could not be read; it is not saved over") }
        _book.value = r.getOrNull() ?: Book()
    }

    /** The saved book could not be read at the start: only a reset writes over it ([load], [edit]). */
    @Volatile var readFailed = false
        private set

    suspend fun setArmed(on: Boolean) = edit { it.copy(armed = on, decided = if (on) ARMED else it.decided, status = if (on) WAITING else "Not armed") }

    suspend fun reset() = edit(clear = true) { it.copy(position = null, trades = emptyList(), decided = if (it.armed) ARMED else null, usedTurn = null,
        status = if (it.armed) WAITING else "Not armed") }

    /** How long after a candle's close (on the feed) a buy may still be taken; later, the candle is skipped. */
    const val LATE_MINUTES = 20L
    const val WAITING = "Armed: waiting for the 1-hour tracker to turn up"
    private const val ARMED = "armed"

    /**
     * One pass, from [GoldPaper.tick]: [hourly] the 1-hour candles completed by [fed], [minutes] today's 1-minute
     * candles, [t] the clock, [lots] the paper lot size. Never throws.
     */
    suspend fun step(hourly: List<Bar>, minutes: List<Bar>, t: LocalDateTime, fed: LocalDateTime, lots: Double) = runCatching {
        GoldBooks.awaitLoaded()
        lock.withLock {
            var b = _book.value
            val last = minutes.lastOrNull()
            val r = GoldTas.read(hourly)
            if (r != null) b = b.copy(up = r.up, line = r.line, score = r.score)
            // The stop and the targets, on the minutes not yet seen.
            b.position?.let { p0 ->
                var p = p0
                for (m in minutes) {
                    if (!m.start.isAfter(p.seen)) continue
                    if (GoldLiquidity.sellPrice(m.low) <= p.stop) {
                        b = sell(b, p, p.left, minOf(p.stop, GoldLiquidity.sellPrice(m.open)), t, if (p.hit > 0) "tas_even" else "tas_stop")
                        b = b.copy(position = null); return@let
                    }
                    val tg = GoldTas.targets(p.entry, p.entry - p.risk)
                    var hit = p.hit; var left = p.left
                    while (hit < tg.size && GoldLiquidity.sellPrice(m.high) >= tg[hit]) {
                        val q = if (hit == tg.size - 1) left else minOf(left, p.lots * GoldTas.TARGETS[hit].second)
                        b = sell(b, p.copy(left = left), q, tg[hit], t, "tas_t${hit + 1}")
                        left -= q; hit++
                    }
                    if (left <= 1e-9) { b = b.copy(position = null); return@let }
                    p = p.copy(left = left, hit = hit, stop = if (hit > 0) maxOf(p.stop, p.entry) else p.stop, seen = m.start)
                }
                b = b.copy(position = p)
            }
            if (r == null) {
                if (b.armed && b.position == null) b = b.copy(status = "Loading the 1-hour candles")
                save(b); return@withLock
            }
            val fresh = b.decided != r.bar.toString()
            val priced = last != null && !last.start.isBefore(t.minusMinutes(30))
            if (fresh && GoldLiquidity.weekday(t) && !priced) {
                b = b.copy(status = "Waiting for a fresh gold price")
            } else if (fresh && GoldLiquidity.weekday(t)) {
                val justArmed = b.decided == ARMED
                b = b.copy(decided = r.bar.toString())
                val pos = b.position
                val closeAt = r.bar.plusHours(1)
                val late = fed.isAfter(closeAt.plusMinutes(LATE_MINUTES))
                when {
                    pos != null && !r.up -> { b = sell(b, pos, pos.left, GoldLiquidity.sellPrice(last!!.close), t, "tas_down"); b = b.copy(position = null) }
                    pos != null -> b = b.copy(status = holding(pos))
                    !b.armed -> Unit
                    !r.up -> b = b.copy(status = "Tracker down after the ${GoldPaper.when_(r.bar)} candle: no buy")
                    r.turn != null && r.turn.toString() == b.usedTurn -> b = b.copy(status = "Already bought this up-turn: waiting for the next one")
                    !GoldTas.buys(r) -> b = b.copy(status = if (r.turn != null && r.sinceTurn <= GoldTas.LATE_BARS)
                        "Tracker up, score %s: below +50%%, no buy yet".format(Locale.ENGLISH, r.score?.let { "%+.0f%%".format(Locale.ENGLISH, it) } ?: "not ready")
                        else "Tracker up, but the turn was more than ${GoldTas.LATE_BARS} hours ago: waiting for the next turn")
                    late -> b = b.copy(status = if (justArmed) WAITING else "Missed the ${GoldPaper.when_(r.bar)} candle: the phone checked late")
                    else -> {
                        val px = GoldLiquidity.buyPrice(last!!.close)
                        val risk = px - r.line
                        if (risk <= 0) b = b.copy(status = "The price is under the tracker line: no buy")
                        else {
                            b = b.copy(position = Position(px, t, lots, lots, r.line, risk, 0, last.start), usedTurn = r.turn.toString(),
                                status = "Bought at %.2f".format(Locale.ENGLISH, px),
                                lastSignal = "${GoldPaper.when_(r.bar)}: tracker up, score %+.0f%%, bought at %.2f".format(Locale.ENGLISH, r.score, px))
                            notify("BUY XAUUSD now (paper): 1-hour tracker turned up",
                                ("The ${GoldPaper.when_(r.bar)} 1-hour candle: the trend tracker is up and the trend score is %+.0f%%. Paper bought %.2f lot at %.2f. " +
                                    "Stop %.2f (the tracker line); held until the tracker turns down. " +
                                    "Futures price: XM's XAUUSD sits a few dollars lower.").format(Locale.ENGLISH, r.score, lots, px, r.line), Notifier.BUY)
                        }
                    }
                }
            } else if (b.position != null) {
                b = b.copy(status = holding(b.position!!))
            } else if (!b.armed && b.status != "Not armed" && !b.status.startsWith("Sold")) {
                b = b.copy(status = "Not armed")
            }
            save(b)
        }
    }

    private fun holding(p: Position) = "Holding %.2f lot from %.2f · stop %.2f".format(Locale.ENGLISH, p.left, p.entry, p.stop)

    /** Sells [q] lots of [p] at [px]; the position is left to the caller. */
    private fun sell(b: Book, p: Position, q: Double, px: Double, t: LocalDateTime, why: String): Book {
        val tr = GoldPaper.Trade(p.entry, px, p.entryTime, t, q, why, GoldLiquidity.pnl(p.entry, px, q))
        notify("SELL XAUUSD now (paper): ${GoldPaper.label(why)}",
            "${GoldPaper.when_(t)}: paper sold %.2f lot at %.2f, P&L %s. Futures price: XM's XAUUSD sits a few dollars lower.".format(
                Locale.ENGLISH, q, px, GoldPaper.usd(tr.pnl)), Notifier.SELL)
        return b.copy(trades = b.trades + tr, status = "Sold: ${GoldPaper.label(why)}")
    }

    private fun notify(title: String, text: String, channel: String) {
        if (::app.isInitialized) runCatching { Notifier.post(app, 7103, channel, title, text) }
        runCatching { Diag.record("gold", "$title - $text") }
    }

    /**
     * Boss's own change. A saved book that could not be read is read again first (the change then goes onto it); still
     * unreadable, the change is kept in memory only and said - never an empty book with the change saved over it
     * (review, 5 Oct). Only an explicit reset ([clear]) writes over an unreadable book.
     */
    private suspend fun edit(clear: Boolean = false, f: (Book) -> Book) {
        GoldBooks.awaitLoaded()
        lock.withLock {
            if (clear) { readFailed = false }
            else if (readFailed) {
                val again = runCatching { read() }
                if (again.isSuccess) { readFailed = false; again.getOrNull()?.let { _book.value = it } }
                else runCatching { Diag.record("gold", "${file.name} could not be read; this change is kept on screen only, not saved") }
            }
            save(f(_book.value))
        }
    }

    private fun save(b: Book) {
        _book.value = b
        if (readFailed) return
        if (::file.isInitialized) runCatching { Vault.writeFile(file, toJson(b).toString().toByteArray(Charsets.UTF_8)) }
    }

    private fun read(): Book? = Vault.readFileSteady(file)?.let { fromJson(JSONObject(String(it, Charsets.UTF_8))) }

    private fun toJson(b: Book) = JSONObject().put("armed", b.armed).put("status", b.status)
        .apply { b.decided?.let { put("decided", it) }; b.lastSignal?.let { put("lastSignal", it) }; b.usedTurn?.let { put("usedTurn", it) }
            b.up?.let { put("up", it) }; b.line?.let { put("line", it) }; b.score?.let { put("score", it) } }
        .apply {
            b.position?.let { p -> put("pos", JSONObject().put("entry", p.entry).put("at", p.entryTime.toString()).put("lots", p.lots)
                .put("left", p.left).put("stop", p.stop).put("risk", p.risk).put("hit", p.hit).put("seen", p.seen.toString())) }
        }
        .put("trades", JSONArray().apply {
            b.trades.forEach { t -> put(JSONObject().put("entry", t.entry).put("exit", t.exit).put("in", t.entryTime.toString())
                .put("out", t.exitTime.toString()).put("lots", t.lots).put("why", t.why).put("pnl", t.pnl)) }
        })

    private fun fromJson(o: JSONObject): Book {
        val pos = o.optJSONObject("pos")?.let { p ->
            Position(p.getDouble("entry"), LocalDateTime.parse(p.getString("at")), p.getDouble("lots"), p.getDouble("left"),
                p.getDouble("stop"), p.getDouble("risk"), p.getInt("hit"), LocalDateTime.parse(p.getString("seen")))
        }
        val tr = o.optJSONArray("trades")?.let { a ->
            (0 until a.length()).map { i -> a.getJSONObject(i).let { t ->
                GoldPaper.Trade(t.getDouble("entry"), t.getDouble("exit"), LocalDateTime.parse(t.getString("in")), LocalDateTime.parse(t.getString("out")),
                    t.getDouble("lots"), t.getString("why"), t.getDouble("pnl"))
            } }
        } ?: emptyList()
        return Book(o.optBoolean("armed", false), pos, tr, o.optString("decided").ifBlank { null }, o.optString("status", "Not armed"),
            o.optString("lastSignal").ifBlank { null }, if (o.has("up")) o.getBoolean("up") else null,
            if (o.has("line")) o.getDouble("line") else null, if (o.has("score")) o.getDouble("score") else null,
            o.optString("usedTurn").ifBlank { null })
    }
}
