package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Right
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.pine.Pine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pine scripts that trade by themselves. On every completed candle of the chosen symbol and
 * interval the script runs; when its signal changes, the app buys the ATM option of the
 * nearest expiry after today (a CALL to go long, a PUT to go short, or just exits), as a
 * MARKET MIS order for the chosen lots, and sells the one it held.
 *
 * It follows the app's Paper / Live switch, decided at each order. Live needs the PIN (or
 * fingerprint) once when the script is switched on; every live order still passes the
 * account guard, the order limits, the kill switch and the static-IP check. Nothing is
 * decided outside market hours; at 15:15 what is held is sold; the day's stop (the
 * kill switch) sells and stands still. A newly switched-on script waits for the next
 * change of signal: it never jumps into a trade already under way.
 */
object PineAuto {
    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()

    data class Held(val symbol: String, val right: String, val qty: Int, val lotSize: Int, val entry: Double, val day: String,
                    val live: Boolean, val kite: String?)
    data class Line(val at: Long, val script: Long, val text: String)

    private class Book(
        val held: HashMap<Long, Held> = HashMap(),
        val lastBar: HashMap<Long, Long> = HashMap(),
        val lastTarget: HashMap<Long, Int> = HashMap(),
        val liveOk: HashMap<Long, Boolean> = HashMap(),
        val log: ArrayList<Line> = ArrayList(),
    )

    private val _held = MutableStateFlow<Map<Long, Held>>(emptyMap())
    val held: StateFlow<Map<Long, Held>> = _held
    private val _log = MutableStateFlow<List<Line>>(emptyList())
    val log: StateFlow<List<Line>> = _log
    private var cache: Book? = null

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.noBackupFilesDir, "pine_auto.vault")
    }

    private fun book(): Book {
        cache?.let { return it }
        val b = runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching null, Charsets.UTF_8))
            val bk = Book()
            o.optJSONObject("held")?.let { m -> m.keys().forEach { k -> val h = m.getJSONObject(k)
                bk.held[k.toLong()] = Held(h.getString("symbol"), h.getString("right"), h.getInt("qty"), h.optInt("lot", 1), h.getDouble("entry"),
                    h.getString("day"), h.optBoolean("live"), h.optString("kite").ifBlank { null }) } }
            o.optJSONObject("lastBar")?.let { m -> m.keys().forEach { k -> bk.lastBar[k.toLong()] = m.getLong(k) } }
            o.optJSONObject("lastTarget")?.let { m -> m.keys().forEach { k -> bk.lastTarget[k.toLong()] = m.getInt(k) } }
            o.optJSONObject("liveOk")?.let { m -> m.keys().forEach { k -> bk.liveOk[k.toLong()] = m.getBoolean(k) } }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) { val l = a.getJSONArray(i); bk.log += Line(l.getLong(0), l.getLong(1), l.getString(2)) } }
            bk
        }.getOrNull()
        if (b == null && file.exists()) Vault.setAside(file)
        return (b ?: Book()).also { cache = it; publish(it) }
    }

    private fun save(b: Book) {
        while (b.log.size > 300) b.log.removeAt(0)
        val o = JSONObject()
        o.put("held", JSONObject().apply { b.held.forEach { (k, h) -> put(k.toString(), JSONObject().put("symbol", h.symbol).put("right", h.right)
            .put("qty", h.qty).put("lot", h.lotSize).put("entry", h.entry).put("day", h.day).put("live", h.live).put("kite", h.kite ?: "")) } })
        o.put("lastBar", JSONObject().apply { b.lastBar.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("lastTarget", JSONObject().apply { b.lastTarget.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("liveOk", JSONObject().apply { b.liveOk.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("log", JSONArray().apply { b.log.forEach { put(JSONArray().put(it.at).put(it.script).put(it.text)) } })
        Vault.writeFile(file, o.toString().toByteArray(Charsets.UTF_8))
        cache = b
        publish(b)
    }

    private fun publish(b: Book) { _held.value = HashMap(b.held); _log.value = b.log.toList() }

    private fun note(b: Book, id: Long, text: String) { b.log += Line(System.currentTimeMillis(), id, text) }

    private fun label(item: PineScripts.Item) = "Pine · ${item.name}"

    @Synchronized fun wipe() { cache = null; if (::file.isInitialized) file.delete(); _held.value = emptyMap(); _log.value = emptyList() }

    suspend fun load() = lock.withLock { book(); Unit }

    /**
     * Switch a script's auto-trading on or off. Off sells what it holds. [pinConfirmed]
     * (the PIN or fingerprint was asked just now) lets it place live orders.
     */
    suspend fun arm(id: Long, on: Boolean, pinConfirmed: Boolean = false): String = lock.withLock {
        val item = PineScripts.get(id) ?: return@withLock "not found"
        val b = book()
        if (on) {
            b.liveOk[id] = pinConfirmed
            b.lastTarget.remove(id); b.lastBar.remove(id)
            PineScripts.setAuto(id, item.auto.copy(on = true))
            note(b, id, "Switched on (${if (OrbArms.liveNow()) "Live" else "Paper"}): waiting for the next signal on ${item.auto.symbol} ${item.auto.interval}")
        } else {
            PineScripts.setAuto(id, item.auto.copy(on = false))
            b.liveOk.remove(id)
            b.held[id]?.let { h -> runCatching { exit(b, id, item, h, "switched off") } }
            note(b, id, "Switched off")
        }
        save(b)
        "ok"
    }

    /** Called by the market watch every pass. */
    suspend fun tick() = lock.withLock {
        val all = PineScripts.items.value
        val on = all.filter { it.auto.on }
        val b = book()
        if (on.isEmpty() && b.held.isEmpty()) return@withLock
        // Held by a script that is no longer on (turned off elsewhere, a restore, deleted): sell it.
        for ((id, h) in b.held.toMap()) if (on.none { it.id == id }) {
            val item = all.firstOrNull { it.id == id } ?: PineScripts.Item(id, "deleted script", "")
            runCatching { exit(b, id, item, h, "no longer auto-trading") }
        }
        val s = AppSettings.load()
        val stopped = Strategies.stoppedToday() || s.guardKill
        for (item in on) runCatching { one(b, item, stopped) }.onFailure { e -> note(b, item.id, "Error: ${e.message ?: e.javaClass.simpleName}") }
        save(b)
    }

    private fun stepSeconds(iv: String): Long = when (iv) { "1m" -> 60; "5m" -> 300; "15m" -> 900; "1h" -> 3600; else -> 86400 }
    private fun lookbackDays(iv: String): Long = when (iv) { "1m" -> 4; "5m" -> 12; "15m" -> 30; "1h" -> 90; else -> 700 }

    private suspend fun one(b: Book, item: PineScripts.Item, stopped: Boolean) {
        val id = item.id
        val t = Market.now()
        val mins = t.hour * 60 + t.minute
        var h = b.held[id]
        // Sold outside the app (a notification's Close, the Trade tab, the broker's square-off).
        if (h != null && gone(h)) { note(b, id, "${h.symbol} is no longer held (closed outside the auto-trader)"); b.held.remove(id); h = null }
        if (h != null && (stopped || (item.auto.squareOff && mins >= 15 * 60 + 15) || h.day != Market.today().toString())) {
            exit(b, id, item, h, if (stopped) "the day's stop" else "15:15 square-off"); return
        }
        if (!Market.isOpen() || stopped || mins >= 15 * 60 + 15) return
        val script = PineScripts.script(item) ?: run { note(b, id, "The script has errors: nothing traded"); return }
        val step = stepSeconds(item.auto.interval)
        val now = System.currentTimeMillis() / 1000
        val bars = ChartFeed.bars(item.auto.symbol, item.auto.interval, now - lookbackDays(item.auto.interval) * 86400, null)
            .filter { it.epochSecond + step <= now }                    // completed candles only
        val last = bars.lastOrNull() ?: return
        if (b.lastBar[id] == last.epochSecond) return
        b.lastBar[id] = last.epochSecond
        val r = Pine.run(script, bars.map { PineScripts.toPine(it) }, PineScripts.inputValues(item, script), item.auto.symbol, item.auto.interval)
        r.error?.let { note(b, id, "Script stopped: ${it.message}"); return }
        val prev = b.lastTarget[id]
        val target = targetOf(item, script, r, prev ?: 0)
        b.lastTarget[id] = target
        if (prev == null) { note(b, id, "Watching: the signal now is ${describe(target)}; it trades on the next change"); return }
        if (target == prev) return
        val want: String? = when {
            target > 0 -> "CE"
            target < 0 -> if (item.auto.shortWith == "put") "PE" else null
            else -> null
        }
        note(b, id, "Signal: ${describe(target)} at ${"%.2f".format(java.util.Locale.ENGLISH, last.close)}")
        if (h?.right == want) return
        if (h != null) { exit(b, id, item, h, "signal changed"); if (b.held.containsKey(id)) return }
        if (want == null) return
        val live = OrbArms.liveNow()
        if (live && b.liveOk[id] != true) {
            note(b, id, "Live needs your PIN once: switch auto-trade off and on again for this script. Nothing was sent.")
            return
        }
        enter(b, item, Right.valueOf(want), last.close, live)
    }

    private fun describe(t: Int) = when { t > 0 -> "BUY"; t < 0 -> "SELL"; else -> "FLAT" }

    /** +1 long, -1 short, 0 flat: the strategy's own position, or the last buy/sell signal. */
    private fun targetOf(item: PineScripts.Item, s: Pine.Script, r: Pine.Run, prev: Int): Int {
        val n = r.position.size - 1
        if (n < 0) return prev
        if (item.auto.buy == "strategy" && s.kind == Pine.Kind.STRATEGY) return Math.signum(r.nextPosition).toInt()
        val bi = s.signals.indexOf(item.auto.buy); val si = s.signals.indexOf(item.auto.sell)
        return when {
            bi >= 0 && r.signals[bi][n] -> 1
            si >= 0 && r.signals[si][n] -> -1
            else -> prev
        }
    }

    // ---- orders ---------------------------------------------------------------------------

    private suspend fun enter(b: Book, item: PineScripts.Item, right: Right, spot: Double, live: Boolean) {
        val id = item.id
        val u = item.auto.symbol
        val strike = OrbRules.atmStrike(spot, if (u == "BANKNIFTY") 100 else 50)
        val today = Market.today()
        val listed = Market.contracts().filter { it.underlying == u }.map { it.expiry }.distinct()
        val expiry = OrbRules.expiryAfter(today, listed) ?: run { note(b, id, "No $u expiry after today is listed: nothing bought"); return }
        val c = Paper.contractFor(u, expiry, strike.toDouble(), right) ?: run { note(b, id, "$u $strike $right is not listed: nothing bought"); return }
        val lots = item.auto.lots.coerceIn(1, 50)
        if (!live) {
            val ltp = Paper.lastPrice(c) ?: run { note(b, id, "No price for ${c.symbol}: nothing bought"); return }
            val snap = runCatching { Paper.snapshot() }.getOrNull()
            val refusals = Guard.check(Guard.paperOrder(c, "BUY", lots, ltp * 1.0005), snap?.let { Guard.paperAccount(it) }, paper = true)
            if (refusals.isNotEmpty()) { note(b, id, "Guard refused: ${refusals.joinToString(" ")}"); return }
            val buy = Paper.place(c, "BUY", lots, "MARKET", "MIS", null, null)
            val fill = filledOrCancelled(buy) ?: run { note(b, id, "Paper buy not filled: ${buy.message}"); return }
            buy.orderId?.let { Strategies.tagOwner("paper:$it", "${label(item)} · entry") }
            Notifier.orderFilled(app, "BUY", fill.first, c.symbol, fill.second, "Paper", label(item))
            b.held[id] = Held(c.symbol, right.name, fill.first, c.lotSize, fill.second, today.toString(), false, null)
            note(b, id, "Bought ${fill.first} ${c.symbol} at ${"%.2f".format(java.util.Locale.ENGLISH, fill.second)} (paper)")
            return
        }
        val s = AppSettings.load()
        if (s.guardKill) { note(b, id, "Kill switch is on: nothing sent"); return }
        if (!Broker.loggedIn) { note(b, id, "Not logged in to Zerodha today: nothing sent"); return }
        val ins = (Broker.cachedInstruments() ?: runCatching { Broker.instruments() }.getOrNull())?.firstOrNull {
            it.name == c.underlying && it.expiry == c.expiry && it.right == c.right && kotlin.math.abs(it.strike - c.strike) < 1e-6
        } ?: run { note(b, id, "${c.symbol} is not listed on Zerodha: nothing sent"); return }
        val sym = ins.tradingSymbol
        val key = "NFO:$sym"
        val quote = runCatching { Broker.quotes(listOf(key))[key]?.last }.getOrNull()?.takeIf { it > 0 }
            ?: run { note(b, id, "No Zerodha quote for $sym: nothing sent"); return }
        val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.BUY, lots * ins.lotSize, ins.lotSize, "MIS", "MARKET", null,
            ins.tickSize, "NFO", "irapine")
        val acct = runCatching {
            Guard.liveAccount(Broker.positionBook(), runCatching { Broker.funds() }.getOrNull(), runCatching { Broker.orders().size }.getOrDefault(0))
        }.getOrNull()
        val refusals = Guard.check(Guard.liveOrder(o).copy(price = quote), acct)
        if (refusals.isNotEmpty()) { note(b, id, "Guard refused: ${refusals.joinToString(" ")}"); return }
        val why = com.optionslab.engine.Kite.refusals(o, s.limits(), Broker.sentToday(), false, refPrice = quote)
        if (why.isNotEmpty()) { note(b, id, "Refused: ${why.joinToString("; ")}"); return }
        val orderId = try { Broker.placeOrder(o) } catch (e: Exception) {
            runCatching { Broker.findRecent(o, emptyList()) }.getOrNull() ?: run { note(b, id, "Zerodha refused the buy: ${e.message}"); return }
        }
        Strategies.tagOwner("kite:$orderId", "${label(item)} · entry")
        var f = runCatching { Broker.awaitOrder(orderId, 15_000) }.getOrNull()
        if (f == null || f.filled <= 0) {
            // Not filled in 15 s: take it out so it can never fill later as an untracked entry.
            if (f?.status !in setOf("REJECTED", "CANCELLED")) runCatching { Broker.cancel(orderId) }
            f = runCatching { Broker.orderState(orderId) }.getOrNull()
            if (f == null || f.filled <= 0) { note(b, id, "Zerodha ${f?.status?.lowercase() ?: "did not answer"}: no position"); return }
        }
        val px = f.avgPrice.takeIf { it > 0 } ?: quote
        Notifier.orderFilled(app, "BUY", f.filled, sym, px, "Live", label(item))
        b.held[id] = Held(c.symbol, right.name, f.filled, ins.lotSize, px, today.toString(), true, sym)
        note(b, id, "Bought ${f.filled} $sym at ${"%.2f".format(java.util.Locale.ENGLISH, px)} (LIVE)")
    }

    /** Sell what the script holds. Removes it from [b] once sold (or found gone). */
    private suspend fun exit(b: Book, id: Long, item: PineScripts.Item, h: Held, why: String) {
        if (!h.live) {
            val c = Paper.contractOf(h.symbol) ?: run { b.held.remove(id); return }
            val net = Paper.state.positions.filter { it.symbol == h.symbol && it.product == "MIS" }.sumOf { it.quantity }
            if (net <= 0) { b.held.remove(id); note(b, id, "${h.symbol} already closed"); return }
            val lots = (minOf(net, h.qty) / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
            val sell = Paper.place(c, "SELL", lots, "MARKET", "MIS", null, null)
            val fill = filledOrCancelled(sell) ?: run { note(b, id, "Paper sell of ${h.symbol} not filled (${sell.message}); retrying next pass"); return }
            sell.orderId?.let { Strategies.tagOwner("paper:$it", "${label(item)} · $why") }
            Notifier.orderFilled(app, "SELL", fill.first, h.symbol, fill.second, "Paper", label(item))
            b.held.remove(id)
            note(b, id, "Sold ${fill.first} ${h.symbol} at ${"%.2f".format(java.util.Locale.ENGLISH, fill.second)} ($why) · P&L ${"%+.0f".format(java.util.Locale.ENGLISH, (fill.second - h.entry) * fill.first)}")
            return
        }
        val sym = h.kite ?: run { b.held.remove(id); return }
        if (!Broker.loggedIn) { note(b, id, "Not logged in to Zerodha: cannot sell $sym. Close it in Trade."); return }
        val still = runCatching { Broker.positionBook().net.filter { it.symbol == sym && it.exchange == "NFO" && it.product == "MIS" }.sumOf { it.qty } }
            .getOrNull() ?: return
        val qty = minOf(still, h.qty)
        if (qty <= 0) { b.held.remove(id); note(b, id, "$sym already closed"); return }
        val spec = runCatching { Broker.spec("NFO", sym) }.getOrNull() ?: return
        val o = com.optionslab.engine.Kite.Order(sym, com.optionslab.engine.Kite.Side.SELL, qty, spec.lotSize, "MIS", "MARKET", null, spec.tickSize, "NFO", "irapine")
        val bad = com.optionslab.engine.Kite.refusals(o, AppSettings.load().limits(), Broker.sentToday(), false, exit = true)
        if (bad.isNotEmpty()) {
            com.optionslab.app.work.Alerts.error("${label(item)}: the Zerodha exit was not sent (${bad.joinToString("; ")}). Close $sym in Trade.", "Pine live")
            return
        }
        val orderId = try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
            runCatching { Broker.findRecent(o, emptyList()) }.getOrNull() ?: run {
                com.optionslab.app.work.Alerts.error("${label(item)}: the Zerodha exit failed (${e.message}); retrying on the next pass.", "Pine live")
                return
            }
        }
        Strategies.tagOwner("kite:$orderId", "${label(item)} · $why")
        val f = runCatching { Broker.awaitOrder(orderId, 15_000) }.getOrNull()
        if (f == null || f.filled <= 0) return                          // checked again next pass against the position book
        Notifier.orderFilled(app, "SELL", f.filled, sym, f.avgPrice, "Live", label(item))
        if (f.filled >= qty) b.held.remove(id) else b.held[id] = h.copy(qty = h.qty - f.filled)
        note(b, id, "Sold ${f.filled} $sym at ${"%.2f".format(java.util.Locale.ENGLISH, f.avgPrice)} ($why, LIVE)")
    }

    private suspend fun gone(h: Held): Boolean = if (!h.live) {
        Paper.state.positions.filter { it.symbol == h.symbol && it.product == "MIS" }.sumOf { it.quantity } <= 0
    } else {
        if (!Broker.loggedIn) false
        else runCatching { Broker.positionBook().net.filter { it.symbol == h.kite && it.exchange == "NFO" && it.product == "MIS" }.sumOf { it.qty } <= 0 }
            .getOrDefault(false)
    }

    /** The fill of a MARKET paper order; one left open (no fresh price) is cancelled so it cannot fill later. */
    private suspend fun filledOrCancelled(r: Paper.Result): Pair<Int, Double>? {
        r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().firstOrNull()?.let { return it.quantity to it.price }
        val oid = r.orderId ?: return null
        if (!r.ok) return null
        Paper.cancel(oid)
        val o = Paper.state.orders.firstOrNull { it.orderId == oid } ?: return null
        return if (o.status == "complete") o.quantity to (o.averagePrice?.toDouble() ?: return null) else null
    }
}
