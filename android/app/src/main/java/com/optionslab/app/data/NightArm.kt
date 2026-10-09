package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.NightRules
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
 * "Night (R3)" (08 Oct, Boss's yes; research/hunt/x2/PREREG.md rule R3, the rules in [NightRules]): a PAPER-ONLY arm that
 * buys one lot of a 1-ITM NIFTY weekly and BANKNIFTY monthly option at 15:20 on a strong close the day-end OI build-up
 * agrees with, and sells it at 09:16 the next session. Not proven (research: about Rs 207 a day before the holdout, Rs 150
 * in it, the holdout not significant). It never reaches Zerodha: every order is the paper account's ([Paper]).
 *
 * Its position is NRML, so the paper account's 15:15 MIS square-off never sells it, and it is not one of the ORB arms', so
 * their 15:10 exits never see it; the expiry square-off leaves it to its own 09:16 sale (it is never held into its
 * contract's expiry). New entries obey the bot's stop for today, the kill switch and the account day lock; its 09:16 sale
 * always goes. Every decision is kept in its log and the diagnostics. Its own encrypted file; one decision a day per index.
 */
object NightArm {
    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, "night.vault")
    }

    /** One overnight trade on paper. */
    data class Pos(
        val index: String, val symbol: String, val right: String, val qty: Int, val entry: Double, val entryDay: LocalDate,
        val entryTime: LocalDateTime, val orderId: String?, val exit: Double? = null, val exitTime: LocalDateTime? = null,
        val why: String? = null, val charges: Double = 0.0,
    ) {
        val open: Boolean get() = exit == null
        /** Rupees after charges, once sold. */
        val net: Double? get() = exit?.let { (it - entry) * qty - charges }
    }

    private class Book(
        var armed: Boolean = false,
        val positions: MutableList<Pos> = ArrayList(),
        /** Index -> the day-end build-up at 15:19, oldest first (seeded with the research's last 60 sessions). */
        val history: MutableMap<String, MutableList<Double>> = HashMap(),
        /** "day|index" already decided (and recorded). */
        val decided: MutableSet<String> = HashSet(),
        val status: MutableMap<String, String> = HashMap(),
        val log: ArrayList<String> = ArrayList(),
    )

    /** What the Strategies row shows. */
    data class View(val armed: Boolean, val status: Map<String, String>, val open: List<Pos>, val closed: List<Pos>, val log: List<String>)

    private val _view = MutableStateFlow(View(false, emptyMap(), emptyList(), emptyList(), emptyList()))
    val view: StateFlow<View> = _view

    private var cache: Book? = null
    private const val LOG_KEPT = 200
    private const val CLOSED_KEPT = 400

    private fun ready() = ::file.isInitialized

    private fun book(): Book {
        cache?.let { return it }
        val b = Book()
        runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            b.armed = o.optBoolean("armed", false)
            o.optJSONArray("positions")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    b.positions += Pos(p.getString("index"), p.getString("symbol"), p.getString("right"), p.getInt("qty"), p.getDouble("entry"),
                        LocalDate.parse(p.getString("entryDay")), LocalDateTime.parse(p.getString("entryTime")), p.optString("orderId").ifEmpty { null },
                        if (p.has("exit")) p.getDouble("exit") else null, p.optString("exitTime").ifEmpty { null }?.let { LocalDateTime.parse(it) },
                        p.optString("why").ifEmpty { null }, p.optDouble("charges", 0.0))
                }
            }
            o.optJSONObject("history")?.let { h -> h.keys().forEach { k -> val a = h.getJSONArray(k); b.history[k] = (0 until a.length()).map { a.getDouble(it) }.toMutableList() } }
            o.optJSONArray("decided")?.let { a -> for (i in 0 until a.length()) b.decided += a.getString(i) }
            o.optJSONObject("status")?.let { m -> m.keys().forEach { b.status[it] = m.getString(it) } }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
        }.onFailure { if (file.exists()) Vault.setAside(file) }
        cache = b
        publish(b)
        return b
    }

    /** What this process last wrote to the book's file: an unchanged save is not encrypted and synced again ([Vault.LastWrite]). */
    private val written = Vault.LastWrite()

    private fun save(b: Book) {
        val o = JSONObject().put("armed", b.armed)
        o.put("positions", JSONArray().apply {
            b.positions.forEach { p ->
                put(JSONObject().put("index", p.index).put("symbol", p.symbol).put("right", p.right).put("qty", p.qty).put("entry", p.entry)
                    .put("entryDay", p.entryDay.toString()).put("entryTime", p.entryTime.toString()).put("orderId", p.orderId ?: "")
                    .apply { p.exit?.let { put("exit", it) }; p.exitTime?.let { put("exitTime", it.toString()) }; p.why?.let { put("why", it) } }
                    .put("charges", p.charges))
            }
        })
        o.put("history", JSONObject().apply { b.history.forEach { (k, v) -> put(k, JSONArray().apply { v.takeLast(NightRules.Z_WINDOW * 2).forEach { put(it) } }) } })
        // Only the last few days' decisions are needed (one a day per index).
        o.put("decided", JSONArray().apply { b.decided.sorted().takeLast(40).forEach { put(it) } })
        o.put("status", JSONObject(b.status as Map<*, *>))
        o.put("log", JSONArray().apply { b.log.takeLast(LOG_KEPT).forEach { put(it) } })
        runCatching { written.write(file, o.toString().toByteArray(Charsets.UTF_8)) }
        publish(b)
    }

    private fun publish(b: Book) {
        _view.value = View(b.armed, b.status.toMap(), b.positions.filter { it.open }, b.positions.filter { !it.open }.takeLast(20),
            b.log.takeLast(30))
    }

    private fun note(b: Book, text: String) {
        b.log += "${LocalDateTime.now(com.optionslab.engine.IST).withNano(0)} $text"
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        runCatching { Diag.record("night", "${NightRules.LABEL}: $text") }
    }

    /** Read the book (the row calls it when shown). */
    suspend fun refresh(): View { if (ready()) lock.withLock { book() }; return _view.value }

    /** Whether the arm holds [symbol] overnight now (read without the lock; the expiry square-off leaves it to its 09:16 sale). */
    fun holds(symbol: String): Boolean = _view.value.open.any { it.symbol == symbol }

    /** Switch it on or off (paper only, always). Off never sells what it holds: that still goes at 09:16. */
    suspend fun setArmed(on: Boolean): String {
        if (!ready()) return "Night (R3) is not ready yet."
        return lock.withLock {
            val b = book()
            if (b.armed == on) return@withLock "${NightRules.LABEL} is already ${if (on) "on" else "off"}."
            b.armed = on
            note(b, if (on) "armed on paper (not proven; ${NightRules.RECORD})" else "switched off" +
                if (b.positions.any { it.open }) "; the open position is still sold at 09:16" else "")
            save(b)
            if (on) "${NightRules.LABEL} armed on paper: ${NightRules.RULES}. ${NightRules.RECORD}." else "${NightRules.LABEL} switched off."
        }
    }

    /** The diagnostics line. */
    fun diagLine(): String {
        val v = _view.value
        val closed = v.closed
        return "Night (R3): ${if (v.armed) "armed (paper only)" else "off"} · open ${v.open.size} · last ${closed.size} closed " +
            "%,.0f after charges".format(Locale.ENGLISH, closed.sumOf { it.net ?: 0.0 }) +
            (v.status.entries.joinToString("") { (k, s) -> " · $k $s" })
    }

    // ---- the pass ---------------------------------------------------------------------------------------------------

    /** Called by the market watch (the full pass and the bar-close wake): the 09:16 sales, then 15:20's decisions. */
    suspend fun tick() {
        if (!ready() || !Market.isTradingDay()) return
        lock.withLock {
            val b = book()
            val now = Market.now().toLocalDateTime()
            var changed = runCatching { exits(b, now) }.getOrDefault(false)
            if (b.armed && NightRules.entryWindow(now.toLocalTime())) {
                for (u in NightRules.UNDERLYINGS) {
                    val key = "${now.toLocalDate()}|$u"
                    if (key in b.decided) continue
                    b.decided += key
                    val why = runCatching { decide(b, u, now) }.getOrElse { "error: ${it.javaClass.simpleName}" }
                    // The 15:19 minute not in yet (a look at the close itself, before the feed or the stream has it): looked at
                    // again on the next check, not decided on the minute before.
                    if (why == WAITING) { b.decided -= key; continue }
                    b.status[u] = why
                    note(b, "$u ${now.toLocalTime().withNano(0)}: $why")
                    changed = true
                }
            }
            if (changed) save(b)
        }
    }

    /** The 09:16 sale of every open position bought on an earlier session. True when [b] changed. */
    private suspend fun exits(b: Book, now: LocalDateTime): Boolean {
        var changed = false
        for ((i, p) in b.positions.withIndex().toList()) {
            if (!p.open || !NightRules.exitDue(p.entryDay, now)) continue
            val c = Paper.contractOf(p.symbol) ?: continue
            val lots = (p.qty / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
            val r = Paper.place(c, "SELL", lots, "MARKET", "NRML", null, null)
            val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
            if (fill == null) {
                r.orderId?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
                if (b.status[p.index] != "night_exit_retry") { b.status[p.index] = "night_exit_retry"; note(b, "${p.symbol}: the 09:16 sale did not fill (${r.message}); tried again"); changed = true }
                continue
            }
            r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${NightRules.LABEL} · exit") } }
            val charges = p.charges + chargesOf(r.orderId)
            val done = p.copy(exit = fill.price, exitTime = now, why = "night_exit_0916", charges = charges)
            b.positions[i] = done
            b.status[p.index] = "sold at 09:16"
            note(b, "${p.symbol}: sold ${fill.quantity} @ %.2f, %s after charges".format(Locale.ENGLISH, fill.price, rs(done.net ?: 0.0)))
            runCatching { Notifier.post(app, 6950 + NightRules.UNDERLYINGS.indexOf(p.index), Notifier.SELL, "${NightRules.LABEL} sold ${p.symbol} · Paper",
                "Sold ${fill.quantity} @ %.2f at 09:16: %s after charges.".format(Locale.ENGLISH, fill.price, rs(done.net ?: 0.0)), "almanac") }
            changed = true
        }
        // Closed trades beyond what is kept are dropped (oldest first); open ones always stay.
        val closed = b.positions.count { !it.open }
        if (closed > CLOSED_KEPT) {
            var drop = closed - CLOSED_KEPT
            b.positions.removeAll { !it.open && drop-- > 0 }
            changed = true
        }
        return changed
    }

    private fun chargesOf(orderId: String?): Double =
        if (orderId == null) 0.0 else Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

    /** The next trading session after [d] (holidays and weekends skipped). */
    private fun nextSession(d: LocalDate): LocalDate {
        var n = d.plusDays(1)
        repeat(14) { if (Market.isTradingDay(n)) return n; n = n.plusDays(1) }
        return n
    }

    /** [decide]'s word for "the 15:19 minute is not in yet": nothing decided, looked at again (until 15:20:30). */
    private const val WAITING = "night_waiting_for_1519"

    /** 15:20's decision for [u]: what it did, in a few words. Records the day's build-up once. */
    private suspend fun decide(b: Book, u: String, now: LocalDateTime): String {
        val today = now.toLocalDate()
        // The index's minutes to 15:19, and the previous session's close.
        val key = Upstox.INDEX_KEYS[u] ?: return "night_no_index_data"
        // Round 2: the feed's minutes with the ones it has not published yet built from the stream when whole and gap-free.
        val nowSec = now.atZone(com.optionslab.engine.IST).toEpochSecond()
        val feedMins = Net.intraday(key).filter { it.istDate == today && it.epochSecond + 60 <= nowSec }.sortedBy { it.epochSecond }
        val mins = LocalCandles.overlay(key, feedMins, nowSec).filter { it.istDate == today && it.istMinute <= 15 * 60 + 19 }
        val last = mins.lastOrNull() ?: return "night_no_index_data"
        if (last.istMinute < 15 * 60 + 19 && now.toLocalTime().isBefore(java.time.LocalTime.of(15, 20, 30))) return WAITING
        val prev = Net.history(key, today.minusDays(10), today.minusDays(1)).filter { it.istDate.isBefore(today) }.maxByOrNull { it.epochSecond }
            ?: return "night_no_previous_close"
        val x = last.close
        val loc = NightRules.loc(x, mins.maxOf { it.high }, mins.minOf { it.low })
        val move = NightRules.dayMove(x, prev.close)
        // The day-end build-up of the nearest series, z-scored against the sessions before (recorded once a day, traded or not).
        val listed = Market.contracts().filter { it.underlying == u }
        val near = com.optionslab.engine.orb.OrbRules.expiryOnOrAfter(today, listed.map { it.expiry }.distinct())
        val bu = near?.let { buildUp(listed.filter { c -> c.expiry == it }, x, u, today) }
        val hist = b.history.getOrPut(u) { NightRules.SEED.getValue(u).toMutableList() }
        val z = bu?.let { NightRules.z(it, hist) }
        if (bu != null) hist += bu
        // Breadth from Zerodha's quotes (logged in only).
        val breadth = if (!Broker.loggedIn) null else runCatching {
            val q = Broker.lastAndPrevClose(NightRules.BREADTH_UNIVERSE.map { "NSE:$it" })
            NightRules.breadth(q.values.toList())
        }.getOrNull()
        val d = NightRules.decide(loc, move, breadth, z)
        val facts = "loc %.2f, day %+.2f%%, breadth %s, OI z %s".format(Locale.ENGLISH, loc, move * 100,
            breadth?.let { "%.0f%%".format(Locale.ENGLISH, it * 100) } ?: "not read (needs Zerodha login)",
            z?.let { "%+.2f".format(Locale.ENGLISH, it) } ?: "not known")
        if (d.side == 0) return "${d.why} ($facts)"
        // Guards for a new entry: the bot stopped for today, the kill switch, the account day lock.
        if (runCatching { Strategies.stoppedToday() }.getOrDefault(false)) return "night_stopped_for_today ($facts)"
        if (runCatching { AppSettings.load().guardKill }.getOrDefault(true)) return "night_kill_switch ($facts)"
        DayLockGuard.refusal(false)?.let { return "night_day_lock: $it" }
        val expiry = NightRules.expiry(today, nextSession(today), listed.map { it.expiry }.distinct()) ?: return "night_expiry_rule ($facts)"
        val strike = NightRules.strike(x, u, d.side)
        val right = if (d.side > 0) Right.CE else Right.PE
        val c = Paper.contractFor(u, expiry, strike.toDouble(), right) ?: return "night_no_contract $u $strike ${right.name} ($facts)"
        // The thin-option gate: an option that barely trades is not bought.
        val minutes = runCatching { Paper.minutes(c) }.getOrNull()?.map { it.epochSecond to it.volume }
        com.optionslab.engine.risk.ThinOption.refusal(minutes, System.currentTimeMillis() / 1000, c.lotSize, null, null)
            ?.let { return "night_thin_option: $it" }
        val r = Paper.place(c, "BUY", 1, "MARKET", "NRML", null, null)
        val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (fill == null) {
            r.orderId?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
            return "night_not_filled: ${r.message}"
        }
        r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${NightRules.LABEL} · entry") } }
        b.positions += Pos(u, c.symbol, right.name, fill.quantity, fill.price, today, now, r.orderId, charges = chargesOf(r.orderId))
        runCatching { Notifier.post(app, 6950 + NightRules.UNDERLYINGS.indexOf(u), Notifier.BUY, "${NightRules.LABEL} bought ${c.symbol} · Paper",
            "Bought ${fill.quantity} @ %.2f on a strong close (%s). Sells at 09:16 next session. Paper only, not proven.".format(Locale.ENGLISH, fill.price, facts), "almanac") }
        return "${d.why} ${c.symbol} @ %.2f ($facts)".format(Locale.ENGLISH, fill.price)
    }

    /**
     * h26's BUopen at 15:19 for [u]'s nearest series [series]: ATM +-2 strikes around the 15:19 close [x] (np.round), each
     * call and put's close and OI at 09:15 and at 15:19 (the last minute to 15:19) from the paper feed's minutes.
     */
    private suspend fun buildUp(series: List<Upstox.Contract>, x: Double, u: String, today: LocalDate): Double? {
        val step = NightRules.step(u)
        val atm = Math.rint(x / step) * step
        val strikes = (-2..2).map { atm + it * step }
        suspend fun leg(c: Upstox.Contract?): NightRules.Leg {
            if (c == null) return NightRules.Leg(null, null, null, null)
            val bars = runCatching { Net.intraday(c.instrumentKey) }.getOrDefault(emptyList())
                .filter { it.istDate == today && it.istMinute <= 15 * 60 + 19 }.sortedBy { it.epochSecond }
            val first = bars.firstOrNull { it.istMinute == 9 * 60 + 15 }
            val lastBar = bars.lastOrNull()
            return NightRules.Leg(first?.close, lastBar?.close, first?.oi?.toDouble(), lastBar?.oi?.toDouble())
        }
        val rows = ArrayList<NightRules.Strike>()
        for (k in strikes) {
            val ce = series.firstOrNull { it.strike == k && it.right == Right.CE }
            val pe = series.firstOrNull { it.strike == k && it.right == Right.PE }
            rows += NightRules.Strike(leg(ce), leg(pe))
        }
        return NightRules.buildUp(rows)
    }
}
