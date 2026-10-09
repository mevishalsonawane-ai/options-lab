package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.VixDivRules
import com.optionslab.engine.sandbox.PaperSpread
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
 * "VIX divergence (not proven)" (9 Oct, Boss's yes; research/HUNT_R1_INTERNET.md idea N13, the rules in [VixDivRules]): a
 * PAPER-ONLY arm that, at 10:30, 11:30, 12:30 and 13:30, takes the day's first divergence between NIFTY (or BANKNIFTY) and
 * India VIX - index up over 0.2% while VIX is up over 2% buys the 1-ITM put, both down the 1-ITM call - 1 lot, the nearest
 * expiry. BANKNIFTY holds to 15:10; NIFTY takes the Liquidity exit (-15% on the option's low, out after 20 minutes unless
 * +5%). It failed the research's luck checks: off by default, and it needs about 150 paper trades before anyone trusts it.
 *
 * PAPER ONLY, always: every order is the paper account's ([Paper.place] / [Paper.cancel]); there is no live flag, and its
 * file is not in the backup, so nothing here can reach Zerodha whatever Live, "AI trades go live" or a voice command says.
 * New entries obey the kill switch, the bot's stop for today (the daily loss limit included), the account day lock, a fresh
 * index price and the thin-option gate; fills are the honest paper ones (the spread and the charges, [Paper]). NIFTY's stop
 * rests in the paper book, so it fills on a minute's LOW (the wick). Exits always go; the expiry square-off, the MIS
 * square-off or Boss may close a position first (then it is booked as closed there). Each signal's bid and ask are in its
 * log and the diagnostics ("[vix-div]"). One decision a day per index; its own encrypted file.
 */
object VixDivArm {
    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, "vix_div.vault")
    }

    /** One paper trade. */
    data class Pos(
        val index: String, val symbol: String, val right: String, val qty: Int, val entry: Double, val entryDay: LocalDate,
        val entryTime: LocalDateTime, val entryMinute: Int, val orderId: String?, val stopOrderId: String? = null,
        val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null, val charges: Double = 0.0,
    ) {
        val open: Boolean get() = exit == null
        /** Rupees after charges, once sold. */
        val net: Double? get() = exit?.let { (it - entry) * qty - charges }
    }

    private class Book(
        var armed: Boolean = false,
        val positions: MutableList<Pos> = ArrayList(),
        /** "day|index" decided (one decision a day per index). */
        val decided: MutableSet<String> = HashSet(),
        val status: MutableMap<String, String> = HashMap(),
        val log: ArrayList<String> = ArrayList(),
    )

    /** What the Strategies row shows; [nets] every closed trade's rupees after charges, oldest first (its paper test). */
    data class View(val armed: Boolean, val status: Map<String, String>, val open: List<Pos>, val closed: List<Pos>, val log: List<String>,
                    val nets: List<Double> = emptyList())

    private val _view = MutableStateFlow(View(false, emptyMap(), emptyList(), emptyList(), emptyList()))
    val view: StateFlow<View> = _view

    @Volatile private var cache: Book? = null
    private const val LOG_KEPT = 200
    private const val CLOSED_KEPT = 400

    private fun ready() = ::file.isInitialized

    private fun book(): Book {
        cache?.let { return it }
        val b = Book()
        runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            b.armed = o.optBoolean("armed", false)
            o.optJSONArray("positions")?.let { a -> for (i in 0 until a.length()) b.positions += readPos(a.getJSONObject(i)) }
            o.optJSONArray("decided")?.let { a -> for (i in 0 until a.length()) b.decided += a.getString(i) }
            o.optJSONObject("status")?.let { m -> m.keys().forEach { b.status[it] = m.getString(it) } }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
        }.onFailure { if (file.exists()) Vault.setAside(file) }
        cache = b
        publish(b)
        return b
    }

    private fun readPos(p: JSONObject): Pos = Pos(
        p.getString("index"), p.getString("symbol"), p.getString("right"), p.getInt("qty"), p.getDouble("entry"),
        LocalDate.parse(p.getString("entryDay")), LocalDateTime.parse(p.getString("entryTime")), p.getInt("entryMinute"),
        p.optString("orderId").ifEmpty { null }, p.optString("stopOrderId").ifEmpty { null },
        if (p.has("exit")) p.getDouble("exit") else null, p.optString("exitTime").ifEmpty { null }?.let { LocalDateTime.parse(it) },
        p.optString("why").ifEmpty { null }, p.optDouble("charges", 0.0),
    )

    private fun writePos(p: Pos): JSONObject = JSONObject().put("index", p.index).put("symbol", p.symbol).put("right", p.right).put("qty", p.qty)
        .put("entry", p.entry).put("entryDay", p.entryDay.toString()).put("entryTime", p.entryTime.toString()).put("entryMinute", p.entryMinute)
        .put("orderId", p.orderId ?: "").put("stopOrderId", p.stopOrderId ?: "").put("charges", p.charges)
        .apply { p.exit?.let { put("exit", it) }; p.exitTime?.let { put("exitTime", it.toString()) }; p.why?.let { put("why", it) } }

    /** What this process last wrote to the book's file: an unchanged save is not encrypted and synced again ([Vault.LastWrite]). */
    private val written = Vault.LastWrite()

    private fun save(b: Book) {
        val o = JSONObject().put("armed", b.armed)
        o.put("positions", JSONArray().apply { b.positions.forEach { put(writePos(it)) } })
        o.put("decided", JSONArray().apply { b.decided.sorted().takeLast(40).forEach { put(it) } })
        o.put("status", JSONObject().apply { b.status.forEach { (k, v) -> put(k, v) } })
        o.put("log", JSONArray().apply { b.log.takeLast(LOG_KEPT).forEach { put(it) } })
        runCatching { written.write(file, o.toString().toByteArray(Charsets.UTF_8)) }
        publish(b)
    }

    private fun publish(b: Book) {
        val closed = b.positions.filter { !it.open }
        _view.value = View(b.armed, b.status.toMap(), b.positions.filter { it.open }, closed.takeLast(20), b.log.takeLast(30),
            closed.map { it.net ?: 0.0 })
    }

    private fun note(b: Book, text: String) {
        b.log += "${Market.now().toLocalDateTime().withNano(0)} $text"
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        runCatching { Diag.record("vix-div", "${VixDivRules.LABEL}: $text") }
    }

    /** Read the book (the row calls it when shown, off the main thread). */
    suspend fun refresh(): View { if (ready()) lock.withLock { book() }; return _view.value }

    /** Switch it on or off - on paper, always. Off sells nothing it holds: its own exits still close it. */
    suspend fun setArmed(on: Boolean): String {
        if (!ready()) return "${VixDivRules.LABEL} is not ready yet."
        return lock.withLock {
            val b = book()
            if (b.armed == on) return@withLock "${VixDivRules.LABEL} is already ${if (on) "on" else "off"} (paper only)."
            b.armed = on
            note(b, if (on) "switched on, paper only (${VixDivRules.NOT_PROVEN}; ${VixDivRules.RECORD})" else "switched off" +
                if (b.positions.any { it.open }) "; what it holds still closes by its own exits" else "")
            save(b)
            if (on) "${VixDivRules.LABEL} on, paper only. ${VixDivRules.NOT_PROVEN}. ${VixDivRules.RECORD}." else "${VixDivRules.LABEL} off."
        }
    }

    /** The diagnostics line. */
    fun diagLine(): String {
        val v = _view.value
        return "${VixDivRules.LABEL}: ${if (v.armed) "on (paper only)" else "off"} · open ${v.open.size} · ${v.nets.size} closed " +
            "%,.0f after charges".format(Locale.ENGLISH, v.nets.sum()) + v.status.toSortedMap().entries.joinToString("") { (k, s) -> " · $k $s" }
    }

    // ---- the pass ---------------------------------------------------------------------------------------------------

    /** Called by the market watch's pass (and its bar-close wake): exits first, then each index's decision when armed. */
    suspend fun tick() {
        if (!ready() || !Market.isTradingDay()) return
        lock.withLock {
            val b = book()
            if (!b.armed && b.positions.none { it.open }) return@withLock
            val now = Market.now().toLocalDateTime().withNano(0)
            var changed = runCatching { exits(b, now) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true }
            if (b.armed) {
                for (u in VixDivRules.UNDERLYINGS) {
                    val key = "${now.toLocalDate()}|$u"
                    if (key in b.decided) continue
                    val d = runCatching { decide(b, u, now) }.getOrElse { Decision(false, "error: ${it.javaClass.simpleName}") }
                    if (d.final) {
                        b.decided += key
                        b.status[u] = d.why
                        note(b, "$u ${now.toLocalTime()}: ${d.why}")
                        changed = true
                    } else if (b.status[u] != d.why) { b.status[u] = d.why; changed = true }
                }
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

    /** A pass's word for one index: [final] decides the day (logged once); otherwise it is only the row's status. */
    private data class Decision(val final: Boolean, val why: String)

    private fun minuteOf(t: LocalDateTime): Int = t.hour * 60 + t.minute

    private fun hhmm(m: Int): String = "%02d:%02d".format(m / 60, m % 60)

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

    private fun chargesOf(orderId: String?): Double =
        if (orderId == null) 0.0 else Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    /** Today's finished 1-minute bars of [key] (the paper feed's), oldest first. */
    private suspend fun minutes(key: String, now: LocalDateTime): List<Upstox.Bar> {
        val nowSec = now.atZone(com.optionslab.engine.IST).toEpochSecond()
        return Net.intraday(key).filter { it.istDate == now.toLocalDate() && it.epochSecond + 60 <= nowSec }.sortedBy { it.epochSecond }
    }

    /** Whether the bot is stopped for today (Boss's stop, or the daily loss limit). Unknown counts as stopped. */
    private suspend fun stoppedToday(): Boolean =
        runCatching { Strategies.stoppedToday() }.getOrDefault(true) || runCatching { LossBreaker.trippedToday() }.getOrDefault(false)

    /** The check's decision for [u] at [now]. */
    private suspend fun decide(b: Book, u: String, now: LocalDateTime): Decision {
        val minute = minuteOf(now)
        if (minute < VixDivRules.CHECKS.first()) return Decision(false, "waiting for the 10:30 check")
        val today = now.toLocalDate()
        val indexKey = Upstox.INDEX_KEYS[u] ?: return Decision(true, "vix_no_index_data")
        val vixKey = Upstox.INDEX_KEYS.getValue(VixDivRules.VIX)
        val index = minutes(indexKey, now)
        val vix = minutes(vixKey, now)
        // The check's own minute not printed yet (the feed a moment behind): wait for it rather than read the minute before.
        VixDivRules.awaiting(index, vix, minute)?.let { return Decision(false, "waiting for the ${hhmm(it - 1)} minute") }
        val readings = VixDivRules.readings(index, vix, minute)
        val sig = readings.firstOrNull { it.side != 0 }
        if (sig == null) {
            val last = readings.lastOrNull()?.let { r -> "${hhmm(r.check)}: index ${VixDivRules.pct(r.indexMove)}, VIX ${VixDivRules.pct(r.vixMove)}" }
                ?: if (index.isEmpty() || vix.isEmpty()) "no index or VIX minutes read" else "no reading yet"
            return if (VixDivRules.dayOver(minute)) Decision(true, "vix_no_signal (last $last)") else Decision(false, "no signal yet ($last)")
        }
        val facts = "${hhmm(sig.check)} check: $u ${VixDivRules.pct(sig.indexMove)} from its open, India VIX ${VixDivRules.pct(sig.vixMove)}"
        if (!VixDivRules.inEntryWindow(sig, minute)) return Decision(true, "vix_late ($facts): the watch was not in time; no trade today")
        // The contract: the 1-ITM option of the nearest expiry, from the decision minute's close.
        val listed = Market.contracts().filter { it.underlying == u }
        val expiry = com.optionslab.engine.orb.OrbRules.expiryOnOrAfter(today, listed.map { it.expiry }.distinct())
            ?: return Decision(true, "vix_no_expiry ($facts)")
        val strike = VixDivRules.strike(sig.indexClose, u, sig.side)
        val right = if (sig.side > 0) Right.CE else Right.PE
        val c = Paper.contractFor(u, expiry, strike.toDouble(), right) ?: return Decision(true, "vix_no_contract $u $strike ${right.name} ($facts)")
        // The bid and ask at the signal, logged whatever happens next (Boss: log the spread at each signal).
        val q = runCatching { Paper.quote(c) }.getOrNull()
        val spread = if (q != null && q.bid > 0 && q.ask > 0) "bid %.2f / ask %.2f (spread %.2f%%)".format(Locale.ENGLISH, q.bid, q.ask, (q.ask - q.bid) / ((q.ask + q.bid) / 2) * 100)
            else "bid/ask not known (no live quote; last %s)".format(Locale.ENGLISH, q?.ltp?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "not read")
        val said = "$facts; ${c.symbol} $spread"
        val fresh = index.lastOrNull()?.let { !PaperSpread.isStale(it.epochSecond, now.atZone(com.optionslab.engine.IST).toEpochSecond()) } == true
        VixDivRules.entryRefusal(
            kill = runCatching { AppSettings.load().guardKill }.getOrDefault(true),
            stoppedToday = stoppedToday(),
            dayLock = runCatching { DayLockGuard.refusal(false) }.getOrNull(),
            freshPrice = fresh,
        )?.let { return Decision(true, "vix_$it ($said)") }
        // The thin-option gate: an option that barely trades is not bought.
        val mins = runCatching { Paper.minutes(c) }.getOrNull()?.map { it.epochSecond to it.volume }
        com.optionslab.engine.risk.ThinOption.refusal(mins, now.atZone(com.optionslab.engine.IST).toEpochSecond(), c.lotSize,
            q?.bid?.takeIf { it > 0 }, q?.ask?.takeIf { it > 0 })
            ?.let { return Decision(true, "vix_thin_option: $it ($said)") }
        val r = Paper.place(c, "BUY", VixDivRules.LOTS, "MARKET", "MIS", null, null)
        val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (fill == null) {
            r.orderId?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
            return Decision(true, "vix_not_filled: ${r.message} ($said)")
        }
        r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${VixDivRules.LABEL} · entry") } }
        // NIFTY's -15% stop rests in the paper book, so it fills on a minute's low (the wick) between passes.
        val stopId = if (!VixDivRules.liquidityExit(u)) null else runCatching {
            Paper.place(c, "SELL", VixDivRules.LOTS, "SL-M", "MIS", null, VixDivRules.stop(fill.price))
        }.getOrNull()?.takeIf { it.ok }?.orderId?.also { runCatching { Strategies.tagOwner("paper:$it", "${VixDivRules.LABEL} · stop") } }
        b.positions += Pos(u, c.symbol, right.name, fill.quantity, fill.price, today, now, minute, r.orderId, stopId, charges = chargesOf(r.orderId))
        val exits = if (VixDivRules.liquidityExit(u)) "stop %.2f on the low, out after 20 min unless +5%%, else 15:10".format(Locale.ENGLISH, VixDivRules.stop(fill.price))
            else "held to 15:10"
        runCatching { Notifier.post(app, 6990 + VixDivRules.UNDERLYINGS.indexOf(u), Notifier.BUY, "${VixDivRules.LABEL} bought ${c.symbol} · Paper",
            "Bought ${fill.quantity} @ %.2f (%s). %s. Paper only, not proven.".format(Locale.ENGLISH, fill.price, facts, exits), "almanac") }
        // (Words with a "%" in them are never part of a format string.)
        return Decision(true, "bought ${c.symbol} ${fill.quantity} @ ${"%.2f".format(Locale.ENGLISH, fill.price)} · paper ($said; $exits)")
    }

    /** Each open position's exit, when due. True when [b] changed. */
    private suspend fun exits(b: Book, now: LocalDateTime): Boolean {
        var changed = false
        val stopped = stoppedToday()
        val minute = minuteOf(now)
        for ((i, p) in b.positions.withIndex().toList()) {
            if (!p.open) continue
            val c = Paper.contractOf(p.symbol) ?: continue
            // NIFTY's resting stop filled in the paper book (on a minute's low): that is the exit.
            val so = p.stopOrderId?.let { id -> Paper.state.orders.firstOrNull { it.orderId == id } }
            if (so != null && so.status == "complete") {
                val done = p.copy(exit = so.averagePrice?.toDouble() ?: VixDivRules.stop(p.entry), exitTime = so.updateTimestamp, why = "stop_15",
                    stopOrderId = null, charges = p.charges + chargesOf(so.orderId))
                b.positions[i] = done; closed(b, done); changed = true; continue
            }
            // Gone from the paper book without this arm selling it: the expiry or MIS square-off, the loss limit, or Boss.
            val held = Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity }
            if (held <= 0) {
                p.stopOrderId?.let { runCatching { Paper.cancel(it, "position_closed") } }
                val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
                val done = p.copy(exit = t?.price?.toDouble() ?: p.entry, exitTime = t?.timestamp ?: now, why = "closed_elsewhere",
                    stopOrderId = null, charges = p.charges + (t?.charges?.toDouble() ?: 0.0))
                b.positions[i] = done; closed(b, done); changed = true; continue
            }
            val why = when {
                stopped -> "operator_stop"
                now.toLocalDate().isAfter(p.entryDay) -> "eod_1510"
                else -> {
                    val bars = runCatching { minutes(c.feedKey, now) }.getOrNull().orEmpty()
                        .map { VixDivRules.Minute(it.istMinute, it.low, it.close) }
                    VixDivRules.exit(VixDivRules.Held(p.index, p.entry, p.entryMinute), bars, minute)
                }
            } ?: continue
            val next = sell(b, p, c, why)
            if (next != p) { b.positions[i] = next; changed = true }
        }
        return changed
    }

    /** Sell [p] at market on paper (its resting stop taken out first; if that stop filled meanwhile, that is the exit). */
    private suspend fun sell(b: Book, p: Pos, c: Paper.Contract, why: String): Pos {
        p.stopOrderId?.let { id ->
            runCatching { Paper.cancel(id, "exit:$why") }
            val so = Paper.state.orders.firstOrNull { it.orderId == id }
            if (so?.status == "complete") {
                val done = p.copy(exit = so.averagePrice?.toDouble() ?: VixDivRules.stop(p.entry), exitTime = so.updateTimestamp, why = "stop_15",
                    stopOrderId = null, charges = p.charges + chargesOf(id))
                closed(b, done); return done
            }
        }
        val r = Paper.place(c, "SELL", (p.qty / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1), "MARKET", "MIS", null, null)
        val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (fill == null) {
            r.orderId?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
            if (b.status[p.index] != "exit_retry") { b.status[p.index] = "exit_retry"; note(b, "${p.symbol}: the $why exit did not fill (${r.message}); tried again next pass") }
            return p.copy(stopOrderId = null)
        }
        r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${VixDivRules.LABEL} · exit") } }
        val done = p.copy(exit = fill.price, exitTime = Market.now().toLocalDateTime().withNano(0), why = why, stopOrderId = null,
            charges = p.charges + chargesOf(r.orderId))
        closed(b, done)
        return done
    }

    private fun closed(b: Book, p: Pos) {
        b.status[p.index] = "closed ${p.symbol}: ${p.why}"
        note(b, "${p.symbol}: sold (${p.why}) @ %.2f, %s after charges · paper".format(Locale.ENGLISH, p.exit ?: 0.0, rs(p.net ?: 0.0)))
        runCatching { Notifier.post(app, 6990 + VixDivRules.UNDERLYINGS.indexOf(p.index).coerceAtLeast(0), Notifier.SELL, "${VixDivRules.LABEL} sold ${p.symbol} · Paper",
            "Sold (${p.why}) @ %.2f: %s after charges. Paper only, not proven.".format(Locale.ENGLISH, p.exit ?: 0.0, rs(p.net ?: 0.0)), "almanac") }
    }

    /** For tests: the book forgotten (its file goes with the test's directory). */
    internal fun wipe() { cache = null; _view.value = View(false, emptyMap(), emptyList(), emptyList(), emptyList()) }
}
