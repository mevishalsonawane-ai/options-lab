package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.mcx.McxArmRules
import com.optionslab.engine.mcx.McxContract
import com.optionslab.engine.mcx.McxEveRules
import com.optionslab.engine.mcx.McxExpiry
import com.optionslab.engine.mcx.McxInstruments
import com.optionslab.engine.mcx.McxMorningRules
import com.optionslab.engine.mcx.McxSession
import com.optionslab.engine.mcx.McxTrendRules
import com.optionslab.engine.sandbox.SandboxEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * The three MCX research candidates as PAPER-ONLY arms (9 Oct 2026; the rules are the engine's [McxEveRules],
 * [McxMorningRules] and [McxTrendRules]): the NATURALGAS evening breakout (M3), the SILVERM morning OTM call (M4) and the
 * 12-month trend rule on the MCX minis (M2). None passed its research gates: each is "Not proven - paper only", OFF by
 * default, 1 lot fixed, no compounding.
 *
 * PAPER ONLY, always: every order here is the paper account's ([Paper.place] / [Paper.cancel]); nothing in this file can
 * reach Zerodha, whatever Live, "AI trades go live" or any voice command says - there is no live flag to set, and its file
 * is not in the backup. New entries obey MCX's hours and holidays ([McxMarket.isOpen]), the kill switch, the bot's stop
 * for today (the daily loss limit included), the account's day lock, MCX's expiry rules ([McxGuard.entryRefusal]: no option
 * buys on expiry day or after 15:00 the day before, no delivery future from 5 trading days before expiry) and a fresh
 * price (the paper account itself refuses a stale fill). Fills are the honest paper ones: the MCX spread on every fill and
 * MCX's charges ([Paper]). Exits always go (they lower risk): the option's resting stop fills on a minute's LOW (the wick,
 * [Paper]'s resting fills), the bot's stop for today sells what they hold, the MCX expiry exit and the sweeper may close a
 * position first (then it is booked as closed there). Every decision is in the arm's log and the diagnostics.
 */
object McxPaperArms {
    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, "mcx_arms.vault")
    }

    /** The arms, in the order the screen lists them. */
    val ARMS: List<String> = listOf(McxEveRules.SOURCE, McxMorningRules.SOURCE, McxTrendRules.SOURCE)

    fun label(arm: String): String = when (arm) {
        McxEveRules.SOURCE -> McxEveRules.LABEL
        McxMorningRules.SOURCE -> McxMorningRules.LABEL
        McxTrendRules.SOURCE -> McxTrendRules.LABEL
        else -> arm
    }

    fun record(arm: String): String = when (arm) {
        McxEveRules.SOURCE -> McxEveRules.RECORD
        McxMorningRules.SOURCE -> McxMorningRules.RECORD
        McxTrendRules.SOURCE -> McxTrendRules.RECORD
        else -> ""
    }

    fun rules(arm: String): String = when (arm) {
        McxEveRules.SOURCE -> McxEveRules.RULES
        McxMorningRules.SOURCE -> McxMorningRules.RULES
        McxTrendRules.SOURCE -> McxTrendRules.RULES
        else -> ""
    }

    /** Where every order of these arms goes. There is no other venue: the arms are paper only. */
    const val VENUE = "paper"

    /** One paper position. [side] +1 long, -1 short; [qty] units (1 lot x multiplier). */
    data class Pos(
        val arm: String, val symbol: String, val side: Int, val qty: Int, val entry: Double, val entryDay: LocalDate,
        val entryTime: LocalDateTime, val entryMinute: Int, val expiry: LocalDate, val option: Boolean, val orderId: String?,
        val stopOrderId: String? = null, val futureKey: String? = null, val stopF: Double? = null, val targetF: Double? = null,
        val flatBy: Int? = null, val leg: String? = null, val exit: Double? = null, val exitTime: LocalDateTime? = null,
        val why: String? = null, val charges: Double = 0.0,
    ) {
        val open: Boolean get() = exit == null
        /** Rupees after charges, once closed. */
        val net: Double? get() = exit?.let { (it - entry) * qty * side - charges }
    }

    private class Book(
        val armed: MutableMap<String, Boolean> = HashMap(),
        val positions: MutableList<Pos> = ArrayList(),
        /** "day|key" already decided today. */
        val decided: MutableSet<String> = HashSet(),
        /** "yyyy-MM|LEG" -> the month's trend signal. */
        val signals: MutableMap<String, Int> = HashMap(),
        val status: MutableMap<String, String> = HashMap(),
        val log: ArrayList<String> = ArrayList(),
    )

    /** What the Commodities page shows. */
    data class View(val armed: Map<String, Boolean>, val status: Map<String, String>, val open: List<Pos>, val closed: List<Pos>, val log: List<String>) {
        fun isArmed(arm: String): Boolean = armed[arm] == true
    }

    private val _view = MutableStateFlow(View(emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList()))
    val view: StateFlow<View> = _view

    @Volatile private var cache: Book? = null
    private const val LOG_KEPT = 200
    private const val CLOSED_KEPT = 400

    /** TEST SEAM: a leg's daily closes (the trend arm), instead of Zerodha's historical data. */
    @Volatile internal var testDaily: ((String) -> List<Pair<LocalDate, Double>>)? = null

    private fun ready() = ::file.isInitialized

    private fun book(): Book {
        cache?.let { return it }
        val b = Book()
        runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            o.optJSONObject("armed")?.let { a -> a.keys().forEach { b.armed[it] = a.getBoolean(it) } }
            o.optJSONArray("positions")?.let { a -> for (i in 0 until a.length()) b.positions += readPos(a.getJSONObject(i)) }
            o.optJSONArray("decided")?.let { a -> for (i in 0 until a.length()) b.decided += a.getString(i) }
            o.optJSONObject("signals")?.let { m -> m.keys().forEach { b.signals[it] = m.getInt(it) } }
            o.optJSONObject("status")?.let { m -> m.keys().forEach { b.status[it] = m.getString(it) } }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
        }.onFailure { if (file.exists()) Vault.setAside(file) }
        cache = b
        publish(b)
        return b
    }

    private fun readPos(p: JSONObject): Pos = Pos(
        p.getString("arm"), p.getString("symbol"), p.getInt("side"), p.getInt("qty"), p.getDouble("entry"),
        LocalDate.parse(p.getString("entryDay")), LocalDateTime.parse(p.getString("entryTime")), p.getInt("entryMinute"),
        LocalDate.parse(p.getString("expiry")), p.getBoolean("option"), p.optString("orderId").ifEmpty { null },
        p.optString("stopOrderId").ifEmpty { null }, p.optString("futureKey").ifEmpty { null },
        if (p.has("stopF")) p.getDouble("stopF") else null, if (p.has("targetF")) p.getDouble("targetF") else null,
        if (p.has("flatBy")) p.getInt("flatBy") else null, p.optString("leg").ifEmpty { null },
        if (p.has("exit")) p.getDouble("exit") else null, p.optString("exitTime").ifEmpty { null }?.let { LocalDateTime.parse(it) },
        p.optString("why").ifEmpty { null }, p.optDouble("charges", 0.0),
    )

    private fun writePos(p: Pos): JSONObject = JSONObject().put("arm", p.arm).put("symbol", p.symbol).put("side", p.side).put("qty", p.qty)
        .put("entry", p.entry).put("entryDay", p.entryDay.toString()).put("entryTime", p.entryTime.toString()).put("entryMinute", p.entryMinute)
        .put("expiry", p.expiry.toString()).put("option", p.option).put("orderId", p.orderId ?: "").put("stopOrderId", p.stopOrderId ?: "")
        .put("futureKey", p.futureKey ?: "").put("leg", p.leg ?: "").put("charges", p.charges)
        .apply {
            p.stopF?.let { put("stopF", it) }; p.targetF?.let { put("targetF", it) }; p.flatBy?.let { put("flatBy", it) }
            p.exit?.let { put("exit", it) }; p.exitTime?.let { put("exitTime", it.toString()) }; p.why?.let { put("why", it) }
        }

    private fun save(b: Book) {
        val o = JSONObject()
        o.put("armed", JSONObject().apply { b.armed.forEach { (k, v) -> put(k, v) } })
        o.put("positions", JSONArray().apply { b.positions.forEach { put(writePos(it)) } })
        o.put("decided", JSONArray().apply { b.decided.sorted().takeLast(200).forEach { put(it) } })
        o.put("signals", JSONObject().apply { b.signals.keys.sorted().takeLast(60).forEach { put(it, b.signals.getValue(it)) } })
        o.put("status", JSONObject().apply { b.status.forEach { (k, v) -> put(k, v) } })
        o.put("log", JSONArray().apply { b.log.takeLast(LOG_KEPT).forEach { put(it) } })
        runCatching { Vault.writeFile(file, o.toString().toByteArray(Charsets.UTF_8)) }
        publish(b)
    }

    private fun publish(b: Book) {
        // The plain hint the main thread reads instead of this vault ([McxMarket.watchDueQuick]); written only on a change.
        runCatching { McxMarket.noteArmsArmed(b.armed.values.any { it }) }
        _view.value = View(b.armed.toMap(), b.status.toMap(), b.positions.filter { it.open }, b.positions.filter { !it.open }.takeLast(30),
            b.log.takeLast(40))
    }

    private fun note(b: Book, arm: String, text: String) {
        val line = "${McxMarket.now().withNano(0)} ${label(arm)}: $text"
        b.log += line
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        runCatching { Diag.record("mcx-arms", line) }
    }

    /** A decision for [arm] today: kept as its status and logged once. */
    private fun decide(b: Book, arm: String, key: String, why: String) {
        b.decided += key
        b.status[arm] = why
        note(b, arm, why)
    }

    /** Read the book (the page calls it when shown). */
    suspend fun refresh(): View { if (ready()) lock.withLock { book() }; return _view.value }

    /** Switch an arm on or off - on paper, always. Off sells nothing it holds: its own exits still close it. */
    suspend fun setArmed(arm: String, on: Boolean): String {
        if (!ready()) return "The MCX paper arms are not ready yet."
        if (arm !in ARMS) return "No such MCX arm."
        return lock.withLock {
            val b = book()
            if ((b.armed[arm] == true) == on) return@withLock "${label(arm)} is already ${if (on) "on" else "off"} (paper only)."
            b.armed[arm] = on
            note(b, arm, if (on) "switched on, paper only (${McxArmRules.NOT_PROVEN}; ${record(arm)})" else "switched off" +
                if (b.positions.any { it.arm == arm && it.open }) "; what it holds still closes by its own exits" else "")
            save(b)
            if (on) "${label(arm)} on, paper only. ${McxArmRules.NOT_PROVEN}. ${record(arm)}." else "${label(arm)} off."
        }
    }

    /** True while [arm] is armed (read without the lock). */
    fun armed(arm: String): Boolean = loaded().isArmed(arm)

    /** The view, the book read first when nothing has read it yet (the watch's scheduling asks before any pass). */
    private fun loaded(): View {
        if (cache == null && ready()) runCatching { book() }
        return _view.value
    }

    /** The diagnostics line. */
    fun diagLine(): String {
        val v = _view.value
        return "MCX paper arms: " + ARMS.joinToString(" · ") { a ->
            val closed = v.closed.filter { it.arm == a }
            "${label(a)} ${if (v.isArmed(a)) "on (paper only)" else "off"}, open ${v.open.count { it.arm == a }}, last ${closed.size} closed " +
                "%,.0f after charges".format(Locale.ENGLISH, closed.sumOf { it.net ?: 0.0 }) + (v.status[a]?.let { " ($it)" } ?: "")
        }
    }

    // ---- when the watch must run for them ---------------------------------------------------------------------------------

    /** The minutes of the day each armed arm needs the watch (IST): the evening break 17:00-23:16, the morning call 09:14-14:00, the trend 09:00-10:00. */
    private fun windows(v: View): List<IntRange> = buildList {
        if (v.isArmed(McxEveRules.SOURCE)) add(McxEveRules.RANGE_FROM..McxEveRules.FLAT_BY + 1)
        if (v.isArmed(McxMorningRules.SOURCE)) add(McxMorningRules.GRID_FROM - 1..14 * 60)
        if (v.isArmed(McxTrendRules.SOURCE)) add(9 * 60..10 * 60)
    }

    /** Whether the MCX watch should run now for an armed arm (MCX open, inside that arm's minutes); held positions are the paper exposure's. */
    fun wantsWatch(): Boolean = runCatching {
        val now = McxMarket.now()
        val minute = now.hour * 60 + now.minute
        val w = windows(loaded())
        w.isNotEmpty() && McxMarket.isOpen() && w.any { minute in it }
    }.getOrDefault(false)

    /** [wantsWatch] from memory only (never the vault): null when the arms' book has not been read in this process yet. */
    fun wantsWatchIfLoaded(): Boolean? {
        if (!ready() || cache == null) return null
        return wantsWatch()
    }

    /** [nextWakeMillis] from memory only (never the vault): null when the arms' book has not been read in this process yet. */
    fun nextWakeMillisIfLoaded(): Long? {
        if (!ready() || cache == null) return null
        return nextWakeMillis()
    }

    /** The next time (epoch ms) an armed arm needs the watch within a week, on MCX's calendar; null when none is armed. */
    fun nextWakeMillis(): Long? = runCatching {
        val w = windows(loaded())
        if (w.isEmpty()) return@runCatching null
        val now = McxMarket.now()
        val cal = McxMarket.calendar()
        for (i in 0..7) {
            val d = now.toLocalDate().plusDays(i.toLong())
            val s = cal.window(d) ?: continue
            val open = s.open.hour * 60 + s.open.minute
            val close = s.close.hour * 60 + s.close.minute
            val starts = w.mapNotNull { r -> maxOf(r.first, open).takeIf { it < close && it <= r.last } }.sorted()
            for (m in starts) {
                val t = d.atTime(m / 60, m % 60)
                if (t.isAfter(now)) return@runCatching t.atZone(IST).toInstant().toEpochMilli()
            }
        }
        null
    }.getOrNull()

    // ---- the pass ---------------------------------------------------------------------------------------------------------

    /** Called by the market watch's NSE pass and its MCX pass: exits first, then each armed arm's entries. */
    suspend fun tick() {
        if (!ready()) return
        lock.withLock {
            val b = book()
            if (b.armed.values.none { it } && b.positions.none { it.open }) return@withLock
            val now = McxMarket.now().withNano(0)
            val cal = McxMarket.calendar()
            val window = cal.window(now.toLocalDate())
            var changed = runCatching { manage(b, now, cal, window) }.getOrElse { note(b, "mcx", "exit check failed: ${it.javaClass.simpleName}"); true }
            if (McxMarket.isOpen()) {
                if (b.armed[McxEveRules.SOURCE] == true)
                    changed = runCatching { eve(b, now, window) }.getOrElse { b.status[McxEveRules.SOURCE] = "error: ${it.javaClass.simpleName}"; true } || changed
                if (b.armed[McxMorningRules.SOURCE] == true)
                    changed = runCatching { morning(b, now, cal, window) }.getOrElse { b.status[McxMorningRules.SOURCE] = "error: ${it.javaClass.simpleName}"; true } || changed
                if (b.armed[McxTrendRules.SOURCE] == true)
                    changed = runCatching { trend(b, now, cal) }.getOrElse { b.status[McxTrendRules.SOURCE] = "error: ${it.javaClass.simpleName}"; true } || changed
            }
            // Closed trades beyond what is kept are dropped (oldest first); open ones always stay.
            val closed = b.positions.count { !it.open }
            if (closed > CLOSED_KEPT) {
                var drop = closed - CLOSED_KEPT
                b.positions.removeAll { !it.open && drop-- > 0 }
                changed = true
            }
            if (changed) save(b)
        }
    }

    // ---- guards -----------------------------------------------------------------------------------------------------------

    /** Whether the bot is stopped for today (Boss's stop, or the daily loss limit). Unknown counts as stopped. */
    private suspend fun stoppedToday(): Boolean =
        runCatching { Strategies.stoppedToday() }.getOrDefault(true) || runCatching { LossBreaker.trippedToday() }.getOrDefault(false)

    /** Why a new entry in [c] ([buy]: a buy) may not go now, or null ([fresh]: the deciding price is under a minute old). */
    private suspend fun refusal(c: Paper.Contract, buy: Boolean, fresh: Boolean): String? = McxArmRules.entryRefusal(
        sessionOpen = McxMarket.isOpen(),
        kill = runCatching { AppSettings.load().guardKill }.getOrDefault(true),
        stoppedToday = stoppedToday(),
        dayLock = runCatching { DayLockGuard.refusal(false) }.getOrNull(),
        expiry = McxGuard.entryRefusal(c, buy),
        freshPrice = fresh,
    )

    private fun nowSec(): Long = Market.now().toEpochSecond()

    /** Today's finished 1-minute bars of [key] (the paper feed's), oldest first. */
    private suspend fun minutes(key: String, now: LocalDateTime): List<Upstox.Bar> {
        val nowSec = now.atZone(IST).toEpochSecond()
        return Net.intraday(key).filter { it.istDate == now.toLocalDate() && it.epochSecond + 60 <= nowSec }.sortedBy { it.epochSecond }
    }

    private fun minuteOf(t: LocalDateTime): Int = t.hour * 60 + t.minute

    private fun hhmm(m: Int): String = "%02d:%02d".format(m / 60, m % 60)

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

    private fun chargesOf(orderId: String?): Double =
        if (orderId == null) 0.0 else Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    /** The net quantity the paper book holds of [symbol] (all products). */
    private fun heldQty(symbol: String): Int = Paper.state.positions.filter { it.symbol == symbol }.sumOf { it.quantity }

    /** Buy (or sell, [action]) 1 lot of [c] at market on paper; the fill and its order id, or the reason it did not fill. */
    private suspend fun paperOrder(c: Paper.Contract, action: String, tag: String): Pair<SandboxEvent.Fill?, Paper.Result> {
        val r = Paper.place(c, action, McxArmRules.LOTS, "MARKET", "NRML", null, null)
        val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (fill == null) r.orderId?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
        else r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", tag) } }
        return fill to r
    }

    // ---- exits ------------------------------------------------------------------------------------------------------------

    private suspend fun manage(b: Book, now: LocalDateTime, cal: McxSession.Calendar, window: McxSession.Window?): Boolean {
        var changed = false
        val stopped = stoppedToday()
        val cfg = McxGuard.config(AppSettings.load())
        val minute = minuteOf(now)
        for ((i, p) in b.positions.withIndex().toList()) {
            if (!p.open) continue
            val c = Paper.contractOf(p.symbol) ?: continue
            val orders = Paper.state.orders.associateBy { it.orderId }
            // The option's resting stop filled in the paper book (on a minute's low): that is the exit.
            val so = p.stopOrderId?.let { orders[it] }
            if (so != null && so.status == "complete") {
                val done = p.copy(exit = so.averagePrice?.toDouble() ?: McxEveRules.optionStop(p.entry), exitTime = so.updateTimestamp, why = "option_stop",
                    stopOrderId = null, charges = p.charges + chargesOf(so.orderId))
                b.positions[i] = done; closed(b, done); changed = true; continue
            }
            // Gone from the paper book without this arm closing it: the MCX expiry exit, the sweeper, or Boss.
            val held = heldQty(p.symbol)
            if (held * p.side <= 0) {
                p.stopOrderId?.let { runCatching { Paper.cancel(it, "position_closed") } }
                val closing = if (p.side > 0) "SELL" else "BUY"
                val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == closing && !it.timestamp.isBefore(p.entryTime) }
                val done = p.copy(exit = t?.price?.toDouble() ?: p.entry, exitTime = t?.timestamp ?: now, why = "closed_elsewhere",
                    stopOrderId = null, charges = p.charges + (t?.charges?.toDouble() ?: 0.0))
                b.positions[i] = done; closed(b, done); changed = true; continue
            }
            val expiryDue = McxExpiry.due(c.underlying, c.expiry, p.option, now, cal, cfg) != null
            val why: String? = when {
                stopped -> "operator_stop"
                expiryDue -> "expiry_exit"
                p.arm == McxEveRules.SOURCE -> eveExit(p, c, now)
                p.arm == McxMorningRules.SOURCE ->
                    if (now.toLocalDate().isAfter(p.entryDay)) "four_hours" else McxMorningRules.exit(p.entryMinute, minute, window)
                else -> null   // the trend's legs close on their own month's signal and rolls ([trend])
            }
            if (why == null) continue
            if (!McxMarket.isOpen()) continue      // nothing fills while MCX is shut: tried again at its next session
            val next = exit(b, p, c, why)
            if (next != p) { b.positions[i] = next; changed = true }
        }
        return changed
    }

    /** The evening break's exit at [now]: every finished minute since the entry, in order (the option's LOW, the future's close). */
    private suspend fun eveExit(p: Pos, c: Paper.Contract, now: LocalDateTime): String? {
        if (now.toLocalDate().isAfter(p.entryDay)) return "flat_2315"
        val held = McxEveRules.Held(p.side, p.entry, p.entryMinute, p.stopF ?: return "flat_2315", p.targetF ?: return "flat_2315",
            p.flatBy ?: McxEveRules.FLAT_BY)
        val fut = p.futureKey?.let { k -> runCatching { minutes(k, now) }.getOrNull() }.orEmpty().associateBy { it.istMinute }
        val opt = runCatching { minutes(c.feedKey, now) }.getOrNull().orEmpty().associateBy { it.istMinute }
        val last = minuteOf(now) - 1
        for (m in p.entryMinute..last) {
            McxEveRules.exit(held, opt[m]?.low, fut[m]?.close, m)?.let { return it }
        }
        // The clock alone (no minute printed yet): two hours, or the flat time.
        return McxEveRules.exit(held, null, null, minuteOf(now))
    }

    /** Close [p] at market on paper (its resting stop taken out first; if that stop filled meanwhile, that is the exit). */
    private suspend fun exit(b: Book, p: Pos, c: Paper.Contract, why: String): Pos {
        p.stopOrderId?.let { id ->
            runCatching { Paper.cancel(id, "exit:$why") }
            val so = Paper.state.orders.firstOrNull { it.orderId == id }
            if (so?.status == "complete") {
                val done = p.copy(exit = so.averagePrice?.toDouble(), exitTime = so.updateTimestamp, why = "option_stop", stopOrderId = null,
                    charges = p.charges + chargesOf(id))
                closed(b, done); return done
            }
        }
        val (fill, r) = paperOrder(c, if (p.side > 0) "SELL" else "BUY", "${label(p.arm)} · exit")
        if (fill == null) {
            if (b.status[p.arm] != "exit_retry") { b.status[p.arm] = "exit_retry"; note(b, p.arm, "${p.symbol}: the $why exit did not fill (${r.message}); tried again next pass") }
            return p.copy(stopOrderId = null)
        }
        val done = p.copy(exit = fill.price, exitTime = McxMarket.now().withNano(0), why = why, stopOrderId = null, charges = p.charges + chargesOf(r.orderId))
        closed(b, done)
        return done
    }

    private fun closed(b: Book, p: Pos) {
        b.status[p.arm] = "closed ${p.symbol}: ${p.why}"
        note(b, p.arm, "${p.symbol}: closed (${p.why}) @ %.2f, %s after charges · paper".format(Locale.ENGLISH, p.exit ?: 0.0, rs(p.net ?: 0.0)))
        runCatching { Notifier.post(app, 6970 + ARMS.indexOf(p.arm).coerceAtLeast(0), Notifier.SELL, "${label(p.arm)} closed ${p.symbol} · Paper",
            "Closed (${p.why}) @ %.2f: %s after charges. Paper only, not proven.".format(Locale.ENGLISH, p.exit ?: 0.0, rs(p.net ?: 0.0)), "strategy") }
    }

    private fun opened(b: Book, p: Pos, facts: String) {
        b.positions += p
        val verb = if (p.side > 0) "bought" else "sold"
        decideLine(b, p.arm, "$verb ${p.symbol} @ %.2f ($facts) · paper".format(Locale.ENGLISH, p.entry))
        runCatching { Notifier.post(app, 6970 + ARMS.indexOf(p.arm).coerceAtLeast(0), Notifier.BUY, "${label(p.arm)} $verb ${p.symbol} · Paper",
            "${verb.replaceFirstChar { it.uppercase() }} ${p.qty} @ %.2f ($facts). Paper only, not proven.".format(Locale.ENGLISH, p.entry), "strategy") }
    }

    private fun decideLine(b: Book, arm: String, text: String) { b.status[arm] = text; note(b, arm, text) }

    // ---- NATURALGAS evening breakout (M3) ---------------------------------------------------------------------------------

    private suspend fun eve(b: Book, now: LocalDateTime, window: McxSession.Window?): Boolean {
        val arm = McxEveRules.SOURCE
        val today = now.toLocalDate()
        val key = "$today|eve"
        if (key in b.decided || b.positions.any { it.arm == arm && it.open }) return false
        val minute = minuteOf(now)
        if (minute < McxEveRules.RANGE_TO) {
            val s = "waiting for the 17:00-19:00 range"
            if (b.status[arm] != s) { b.status[arm] = s; return true }
            return false
        }
        val flatBy = McxEveRules.flatBy(window)
        val all = McxMarket.contracts()
        val chain = McxInstruments.nearChain(all, McxEveRules.UNDERLYING, today)
        if (chain.isEmpty()) { decide(b, arm, key, "eve_no_chain"); return true }
        val fut = McxInstruments.underlyingFuture(all, chain.first()) ?: run { decide(b, arm, key, "eve_no_future"); return true }
        val bars = minutes(fut.upstoxKey, now)
        val range = McxEveRules.range(bars) ?: run { decide(b, arm, key, "eve_no_range (under 60 minutes 17:00-19:00 or no width)"); return true }
        val sig = McxEveRules.signal(range, bars)
        if (sig == null) {
            if (minute > McxEveRules.ENTRY_UNTIL + 1) { decide(b, arm, key, "eve_no_break (range %.2f-%.2f)".format(Locale.ENGLISH, range.low, range.high)); return true }
            val s = "range %.2f-%.2f · watching for a break to 22:00".format(Locale.ENGLISH, range.low, range.high)
            if (b.status[arm] != s) { b.status[arm] = s; return true }
            return false
        }
        val facts = "break %s at %s close %.2f, range %.2f-%.2f".format(Locale.ENGLISH, if (sig.side > 0) "up" else "down", hhmm(sig.minute), sig.close, range.low, range.high)
        if (!McxEveRules.entryWindow(sig, minute, flatBy)) { decide(b, arm, key, "eve_late ($facts): the watch was not in time; no trade today"); return true }
        val right = if (sig.side > 0) Right.CE else Right.PE
        val strike = McxEveRules.strike(chain.map { it.strike }, sig.close, sig.side) ?: run { decide(b, arm, key, "eve_no_strike ($facts)"); return true }
        val oc = chain.firstOrNull { it.strike == strike && it.right == right } ?: run { decide(b, arm, key, "eve_no_contract $strike ${right.name} ($facts)"); return true }
        val pc = McxMarket.paperContract(oc)
        val fresh = bars.lastOrNull()?.let { McxArmRules.fresh(it.epochSecond, nowSec()) } == true
        refusal(pc, buy = true, fresh = fresh)?.let { decide(b, arm, key, "eve_$it ($facts)"); return true }
        b.decided += key
        val (fill, r) = paperOrder(pc, "BUY", "${McxEveRules.LABEL} · entry")
        if (fill == null) { decideLine(b, arm, "eve_not_filled: ${r.message} ($facts)"); return true }
        // The -15% stop rests in the paper book, so it fills on a minute's low (the wick) between passes.
        val trigger = toTick(McxEveRules.optionStop(fill.price), oc.tick)
        val stop = runCatching { Paper.place(pc, "SELL", McxArmRules.LOTS, "SL-M", "NRML", null, trigger) }.getOrNull()
        val stopId = stop?.takeIf { it.ok }?.orderId?.also { runCatching { Strategies.tagOwner("paper:$it", "${McxEveRules.LABEL} · stop") } }
        opened(b, Pos(arm, pc.symbol, 1, fill.quantity, fill.price, today, now, sig.entryMinute, oc.expiry, true, r.orderId,
            stopOrderId = stopId, futureKey = fut.upstoxKey, stopF = sig.stop, targetF = sig.target, flatBy = flatBy, charges = chargesOf(r.orderId)), facts)
        return true
    }

    /** [x] on [tick]'s grid (nearest). */
    private fun toTick(x: Double, tick: Double): Double {
        val t = java.math.BigDecimal((if (tick > 0) tick else 0.05).toString())
        return java.math.BigDecimal(x.toString()).divide(t, 0, java.math.RoundingMode.HALF_UP).multiply(t).toDouble()
    }

    // ---- SILVERM morning call (M4) ----------------------------------------------------------------------------------------

    private suspend fun morning(b: Book, now: LocalDateTime, cal: McxSession.Calendar, window: McxSession.Window?): Boolean {
        val arm = McxMorningRules.SOURCE
        val today = now.toLocalDate()
        if (b.positions.any { it.arm == arm && it.open }) return false
        val minute = minuteOf(now)
        val slot = McxMorningRules.gridSlot(minute) ?: return false
        val key = "$today|am|${hhmm(slot)}"
        if (key in b.decided) return false
        val lastEntry = b.positions.filter { it.arm == arm && it.entryDay == today }.maxOfOrNull { it.entryMinute }
        if (!McxMorningRules.mayEnter(slot, lastEntry)) return false
        val all = McxMarket.contracts()
        val chain = McxInstruments.nearChain(all, McxMorningRules.UNDERLYING, today)
        if (chain.isEmpty()) { decide(b, arm, key, "am_no_chain"); return true }
        val expiry = chain.first().expiry
        val dte = McxArmRules.tradingDaysLeft(today, expiry, cal)
        if (!McxMorningRules.dteOk(today, expiry, cal)) { decide(b, arm, key, "am_dte $dte (needs 11-20 to ${expiry})"); return true }
        val fut = McxInstruments.underlyingFuture(all, chain.first()) ?: run { decide(b, arm, key, "am_no_future"); return true }
        val bars = minutes(fut.upstoxKey, now)
        val last = bars.lastOrNull() ?: run { decide(b, arm, key, "am_no_future_price"); return true }
        val fresh = McxArmRules.fresh(last.epochSecond, nowSec())
        var why = "am_no_strike"
        for (steps in McxMorningRules.OTM_STEPS) {
            val strike = McxMorningRules.strike(chain.map { it.strike }, last.close, steps) ?: continue
            val oc = chain.firstOrNull { it.strike == strike && it.right == Right.CE } ?: continue
            val pc = McxMarket.paperContract(oc)
            val px = runCatching { Paper.quote(pc) }.getOrNull()?.ltp
            if (px == null || !(px > 0)) { why = "am_no_price ${oc.label}"; continue }
            if (!McxMorningRules.premiumOk(px, oc.multiplier)) { why = "am_premium ${oc.label}: 1 lot Rs %,.0f (needs Rs 500 to Rs 1 lakh)".format(Locale.ENGLISH, px * oc.multiplier); continue }
            val facts = "OTM$steps call, $dte days to expiry, future %.2f".format(Locale.ENGLISH, last.close)
            refusal(pc, buy = true, fresh = fresh)?.let { decide(b, arm, key, "am_$it ($facts)"); return true }
            b.decided += key
            val (fill, r) = paperOrder(pc, "BUY", "${McxMorningRules.LABEL} · entry")
            if (fill == null) { decideLine(b, arm, "am_not_filled: ${r.message} ($facts)"); return true }
            opened(b, Pos(arm, pc.symbol, 1, fill.quantity, fill.price, today, now, slot, oc.expiry, true, r.orderId,
                futureKey = fut.upstoxKey, charges = chargesOf(r.orderId)), "$facts; sells at ${hhmm(McxMorningRules.exitMinute(slot, window))}")
            return true
        }
        decide(b, arm, key, why)
        return true
    }

    // ---- 12-month trend on the minis (M2) ---------------------------------------------------------------------------------

    /** When the trend arm last tried to read a leg's history and could not (in memory: tried again after half an hour). */
    private val trendTried = HashMap<String, Long>()

    private suspend fun dailyCloses(leg: String, fut: McxContract, today: LocalDate): List<Pair<LocalDate, Double>>? {
        testDaily?.let { return it(leg) }
        if (!Broker.loggedIn || fut.token <= 0) return null
        // Zerodha's continuous near-month daily series (needs its historical-data add-on): about 15 months for 252 sessions.
        return Broker.dailyBars(fut.token, today.minusDays(400), today.minusDays(1), continuous = true).map { it.istDate to it.close }
    }

    private suspend fun trend(b: Book, now: LocalDateTime, cal: McxSession.Calendar): Boolean {
        val arm = McxTrendRules.SOURCE
        val today = now.toLocalDate()
        val month = McxTrendRules.month(today)
        val signalDay = McxTrendRules.signalDay(today, cal)
        val cfg = McxGuard.config(AppSettings.load())
        val all = McxMarket.contracts()
        var changed = false
        val notes = ArrayList<String>()
        for (leg in McxTrendRules.LEGS) {
            val futures = McxInstruments.futures(all, leg, today, 3)
            val hold = McxTrendRules.expiryToHold(futures.map { it.expiry }, today, cal, cfg)
            val sigKey = "$month|$leg"
            var sig = b.signals[sigKey]
            if (sig == null) {
                val tried = trendTried[sigKey]
                if (tried != null && System.currentTimeMillis() - tried < 30 * 60_000L) { notes += "$leg: history not read"; continue }
                val near = futures.firstOrNull()
                val closes = near?.let { runCatching { dailyCloses(leg, it, today) }.getOrNull() }
                if (closes == null) { trendTried[sigKey] = System.currentTimeMillis(); notes += "$leg: history not read (needs Zerodha login with historical data)"; continue }
                sig = McxTrendRules.signal(closes, signalDay)
                b.signals[sigKey] = sig
                note(b, arm, "$leg: $month signal ${if (sig > 0) "long" else if (sig < 0) "short" else "none"} (12-month change to $signalDay)")
                changed = true
            }
            val held = b.positions.firstOrNull { it.arm == arm && it.open && it.leg == leg }
            val step = McxTrendRules.step(held?.side ?: 0, held?.expiry, sig, hold)
            val dayKey = "$today|trend|$leg|${step.why}"
            if (step.why == "trend_hold" || dayKey in b.decided) { notes += "$leg: ${step.why.removePrefix("trend_")}"; continue }
            b.decided += dayKey
            changed = true
            if (step.close && held != null) {
                val c = Paper.contractOf(held.symbol)
                if (c == null) { note(b, arm, "$leg: ${held.symbol} not in the paper book"); continue }
                val i = b.positions.indexOf(held)
                val done = exit(b, held, c, step.why)
                if (i >= 0) b.positions[i] = done
                if (done.open) continue          // not closed (no fill): nothing new before it is
            }
            if (step.open == 0) { notes += "$leg: ${step.why.removePrefix("trend_")}"; continue }
            val fc = futures.firstOrNull { it.expiry == hold } ?: continue
            val pc = McxMarket.paperContract(fc)
            val bars = runCatching { minutes(fc.upstoxKey, now) }.getOrNull().orEmpty()
            val fresh = bars.lastOrNull()?.let { McxArmRules.fresh(it.epochSecond, nowSec()) } == true
            val buy = step.open > 0
            val refused = refusal(pc, buy = buy, fresh = fresh)
            if (refused != null) { note(b, arm, "$leg: ${step.why} refused: $refused"); notes += "$leg: $refused"; continue }
            val (fill, r) = paperOrder(pc, if (buy) "BUY" else "SELL", "${McxTrendRules.LABEL} · ${step.why}")
            if (fill == null) { note(b, arm, "$leg: ${step.why} not filled: ${r.message}"); notes += "$leg: not filled"; continue }
            opened(b, Pos(arm, pc.symbol, step.open, fill.quantity, fill.price, today, now, minuteOf(now), fc.expiry, false, r.orderId,
                leg = leg, charges = chargesOf(r.orderId)), "${step.why.removePrefix("trend_")}, $month signal")
        }
        if (notes.isNotEmpty()) {
            val s = notes.joinToString(" · ")
            if (b.status[arm] != s) { b.status[arm] = s; changed = true }
        }
        return changed
    }

    /** For tests: the book forgotten (its file goes with the test's directory). */
    internal fun wipe() { cache = null; trendTried.clear(); _view.value = View(emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList()) }
}
