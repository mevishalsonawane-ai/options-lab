package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Alerts
import com.optionslab.engine.Kite
import com.optionslab.engine.risk.Protection
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Stops, trailing stops and targets on open positions - paper and Zerodha -
 * and the bracket an order can carry (entry + stop + target).
 *
 * A protection rests as real orders in the account it protects: the stop as an
 * SL-M exit, the target as a LIMIT exit. The two are one-cancels-other: when one
 * fills, the other is cancelled here. A trailing stop is moved up (for a long;
 * down for a short) by modifying the SL-M order - it only ever tightens.
 *
 * Zerodha: a protection is set up only after the owner's PIN or fingerprint
 * (once, when it is created, as the owner chose); its later moves need nothing
 * more. The kill switch stops every order here too. Exits never go through the
 * account-guard limits.
 */
object Protections {
    data class Item(
        val id: Long, val live: Boolean, val symbol: String, val exchange: String, val product: String,
        /** Units protected; +long / -short. */
        val qty: Int, val lotSize: Int, val tick: Double,
        val stop: Double?, val trail: Double?, val target: Double?, val best: Double,
        val stopOrderId: String?, val targetOrderId: String?,
        val active: Boolean = true, val note: String = "",
    ) {
        val direction: Int get() = if (qty > 0) 1 else -1
        val exitSide: String get() = if (qty > 0) "SELL" else "BUY"
        fun spec() = Protection.Spec(direction, stop, trail, target, best, tick)
        fun describe(): String = listOfNotNull(
            stop?.let { (if (trail != null) "trailing stop %.2f (%.2f behind)".format(it, trail) else "stop %.2f".format(it)) },
            target?.let { "target %.2f".format(it) },
        ).joinToString(" · ")
    }

    private lateinit var file: File
    private val lock = Mutex()
    private var cache: MutableList<Item>? = null

    fun init(context: Context) { file = File(context.applicationContext.noBackupFilesDir, "protections.vault") }

    private fun load(): MutableList<Item> {
        cache?.let { return it }
        val out = ArrayList<Item>()
        runCatching {
            val a = JSONArray(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                fun d(k: String) = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
                fun s(k: String) = o.optString(k).ifEmpty { null }
                out += Item(o.getLong("id"), o.getBoolean("live"), o.getString("symbol"), o.getString("exchange"), o.getString("product"),
                    o.getInt("qty"), o.getInt("lot"), o.getDouble("tick"), d("stop"), d("trail"), d("target"), o.getDouble("best"),
                    s("stopId"), s("targetId"), o.optBoolean("active", true), o.optString("note"))
            }
        }
        cache = out
        return out
    }

    private fun save(list: List<Item>) {
        val a = JSONArray()
        // Finished protections are kept a while for the record, then dropped.
        (list.filter { it.active } + list.filter { !it.active }.takeLast(50)).forEach { p ->
            a.put(JSONObject().put("id", p.id).put("live", p.live).put("symbol", p.symbol).put("exchange", p.exchange).put("product", p.product)
                .put("qty", p.qty).put("lot", p.lotSize).put("tick", p.tick).put("stop", p.stop ?: JSONObject.NULL).put("trail", p.trail ?: JSONObject.NULL)
                .put("target", p.target ?: JSONObject.NULL).put("best", p.best).put("stopId", p.stopOrderId ?: "").put("targetId", p.targetOrderId ?: "")
                .put("active", p.active).put("note", p.note))
        }
        Vault.writeFile(file, a.toString().toByteArray(Charsets.UTF_8))
        cache = list.toMutableList()
    }

    suspend fun active(): List<Item> = lock.withLock { load().filter { it.active } }
    suspend fun forSymbol(live: Boolean, symbol: String): Item? = lock.withLock { load().lastOrNull { it.active && it.live == live && it.symbol == symbol } }

    // ---- setting one up -------------------------------------------------------------------

    /**
     * Protect a paper position. [qty] is the position's signed quantity; the exits cover all of it.
     * Returns a message for the owner.
     */
    suspend fun protectPaper(symbol: String, product: String, qty: Int, price: Double, stop: Double?, trail: Double?, target: Double?): String {
        Protection.validate(if (qty > 0) 1 else -1, price, stop, trail, target)?.let { return it }
        if (AppSettings.load().guardKill) return "The kill switch is on: no orders at all until it is cleared."
        val c = Paper.contractOf(symbol) ?: return "That paper contract is not known."
        return lock.withLock {
            val list = load()
            list.filter { it.active && !it.live && it.symbol == symbol }.forEach { cancelOrders(it) }
            list.replaceAll { if (it.active && !it.live && it.symbol == symbol) it.copy(active = false, note = "replaced") else it }
            val dir = if (qty > 0) 1 else -1
            val s0 = Protection.initialStop(dir, price, stop, trail)
            val lots = abs(qty) / c.lotSize.coerceAtLeast(1)
            val exit = if (qty > 0) "SELL" else "BUY"
            var stopId: String? = null; var targetId: String? = null
            if (s0 != null) {
                val r = Paper.place(c, exit, lots, "SL-M", product, null, s0)
                if (!r.ok) return@withLock "The stop was not placed: ${r.message}"
                stopId = r.orderId; stopId?.let { Strategies.tagOwner("paper:$it", "Protection · stop") }
            }
            if (target != null) {
                val r = Paper.place(c, exit, lots, "LIMIT", product, target, null)
                if (r.ok) { targetId = r.orderId; targetId?.let { Strategies.tagOwner("paper:$it", "Protection · target") } }
            }
            val item = Item(System.currentTimeMillis(), false, symbol, "NFO", product, qty, c.lotSize, 0.05, s0, trail, target, price, stopId, targetId)
            list += item; save(list)
            "Protected ${symbol}: ${item.describe()}"
        }
    }

    /**
     * Protect a Zerodha position. Call only after the owner's PIN or fingerprint.
     * The stop goes to Zerodha as an SL-M order, the target as a LIMIT order.
     */
    suspend fun protectLive(symbol: String, exchange: String, product: String, qty: Int, price: Double,
                            stop: Double?, trail: Double?, target: Double?): String {
        val s = AppSettings.load()
        if (!s.live || !s.allowRealOrders) return "Switch to Live with the badge at the top first."
        if (s.guardKill) return "The kill switch is on: no orders at all until it is cleared."
        Protection.validate(if (qty > 0) 1 else -1, price, stop, trail, target)?.let { return it }
        val spec = runCatching { Broker.spec(exchange, symbol) }.getOrNull() ?: return "Could not read $symbol's lot and tick from Zerodha."
        return lock.withLock {
            val list = load()
            list.filter { it.active && it.live && it.symbol == symbol }.forEach { cancelOrders(it) }
            list.replaceAll { if (it.active && it.live && it.symbol == symbol) it.copy(active = false, note = "replaced") else it }
            val dir = if (qty > 0) 1 else -1
            val s0 = Protection.initialStop(dir, price, stop, trail, spec.tickSize)
            val side = if (qty > 0) Kite.Side.SELL else Kite.Side.BUY
            var stopId: String? = null; var targetId: String? = null
            try {
                if (s0 != null) stopId = Broker.placeOrder(Kite.Order(symbol, side, abs(qty), spec.lotSize, product, "SL-M", null, spec.tickSize, exchange,
                    "iraprotect", triggerPrice = s0))
                if (target != null) targetId = Broker.placeOrder(Kite.Order(symbol, side, abs(qty), spec.lotSize, product, "LIMIT",
                    Kite.onTick(target, spec.tickSize, side), spec.tickSize, exchange, "iraprotect"))
            } catch (e: Exception) {
                stopId?.let { runCatching { Broker.cancel(it) } }
                return@withLock "Not protected: ${e.message}"
            }
            stopId?.let { Strategies.tagOwner("kite:$it", "Protection · stop") }
            targetId?.let { Strategies.tagOwner("kite:$it", "Protection · target") }
            val item = Item(System.currentTimeMillis(), true, symbol, exchange, product, qty, spec.lotSize, spec.tickSize, s0, trail, target, price, stopId, targetId)
            list += item; save(list)
            "Protected $symbol at Zerodha: ${item.describe()}"
        }
    }

    /** Remove a protection: its resting orders are cancelled. */
    suspend fun remove(id: Long): String = lock.withLock {
        val list = load()
        val p = list.firstOrNull { it.id == id && it.active } ?: return@withLock "Nothing to remove."
        cancelOrders(p)
        list.replaceAll { if (it.id == id) it.copy(active = false, note = "removed") else it }
        save(list)
        "Protection removed from ${p.symbol}."
    }

    private suspend fun cancelOrders(p: Item) {
        listOfNotNull(p.stopOrderId, p.targetOrderId).forEach { id ->
            runCatching { if (p.live) Broker.cancel(id) else Paper.cancel(id) }
        }
    }

    // ---- watching ---------------------------------------------------------------------------

    /**
     * One pass: for each active protection - one exit filled? cancel the other and finish;
     * position gone? cancel both; trailing? move the stop up to the new level.
     * Called by the market watch and while the app is open.
     */
    suspend fun tick() = lock.withLock {
        val list = load()
        if (list.none { it.active }) return@withLock
        val kill = AppSettings.load().guardKill
        var changed = false
        for ((i, p) in list.withIndex()) {
            if (!p.active) continue
            val next = runCatching { if (p.live) tickLive(p, kill) else tickPaper(p, kill) }.getOrNull() ?: continue
            if (next != p) { list[i] = next; changed = true }
        }
        if (changed) save(list)
    }

    private suspend fun tickPaper(p: Item, kill: Boolean): Item {
        val orders = Paper.state.orders.associateBy { it.orderId }
        val stopDone = p.stopOrderId?.let { orders[it]?.status == "complete" } == true
        val targetDone = p.targetOrderId?.let { orders[it]?.status == "complete" } == true
        if (stopDone || targetDone) {
            (if (stopDone) p.targetOrderId else p.stopOrderId)?.let { runCatching { Paper.cancel(it) } }
            Alerts.post("${p.symbol}: ${if (stopDone) "stop" else "target"} filled (paper); the other exit was cancelled.",
                if (stopDone) Alerts.Kind.ERROR else Alerts.Kind.SUCCESS, "Protection")
            return p.copy(active = false, note = if (stopDone) "stop filled" else "target filled")
        }
        val net = Paper.state.positions.filter { it.symbol == p.symbol && it.product == p.product }.sumOf { it.quantity }
        if (net == 0 || net.sign() != p.qty.sign()) {
            listOfNotNull(p.stopOrderId, p.targetOrderId).forEach { runCatching { Paper.cancel(it) } }
            return p.copy(active = false, note = "position closed")
        }
        if (p.trail == null || kill) return p
        val c = Paper.contractOf(p.symbol) ?: return p
        val ltp = Paper.lastPrice(c) ?: return p
        val n = Protection.next(p.spec(), ltp)
        if (n.stop != null && n.stop != p.stop && p.stopOrderId != null) {
            val r = Paper.modify(p.stopOrderId, null, null, n.stop)
            if (!r.ok) return p.copy(best = n.best)
        }
        return p.copy(best = n.best, stop = n.stop)
    }

    private suspend fun tickLive(p: Item, kill: Boolean): Item {
        if (!Broker.loggedIn) return p
        val rows = Broker.orders().associateBy { it.id }
        val so = p.stopOrderId?.let { rows[it] }; val to = p.targetOrderId?.let { rows[it] }
        val stopDone = so?.status == "COMPLETE"; val targetDone = to?.status == "COMPLETE"
        if (stopDone || targetDone) {
            (if (stopDone) p.targetOrderId else p.stopOrderId)?.let { runCatching { Broker.cancel(it) } }
            Alerts.post("${p.symbol}: ${if (stopDone) "stop" else "target"} filled at Zerodha; the other exit was cancelled.",
                if (stopDone) Alerts.Kind.ERROR else Alerts.Kind.SUCCESS, "Protection")
            return p.copy(active = false, note = if (stopDone) "stop filled" else "target filled")
        }
        // Both exits gone some other way (cancelled at Zerodha, rejected): nothing left to watch.
        if ((so == null || !so.working) && (to == null || !to.working) && (p.stopOrderId != null || p.targetOrderId != null)) {
            return p.copy(active = false, note = "exit orders no longer working")
        }
        if (p.trail == null || kill || so == null || !so.working) return p
        val key = "${p.exchange}:${p.symbol}"
        val ltp = Broker.quotes(listOf(key))[key]?.last ?: return p
        val n = Protection.next(p.spec(), ltp)
        if (n.stop != null && n.stop != p.stop && abs(n.stop - (p.stop ?: 0.0)) >= p.tick - 1e-9) {
            runCatching { Broker.modify(so, so.qty, "SL-M", null, n.stop) }.onFailure { return p.copy(best = n.best) }
        }
        return p.copy(best = n.best, stop = n.stop)
    }

    private fun Int.sign() = if (this > 0) 1 else if (this < 0) -1 else 0

    fun wipe() { cache = null; if (::file.isInitialized) file.delete() }
}
