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
 * SL-M exit on paper (an SL with a limit at Zerodha, which refuses SL-M on options),
 * the target as a LIMIT exit. The two are one-cancels-other: when one
 * fills, the other is cancelled here. A trailing stop is moved up (for a long;
 * down for a short) by modifying the stop order - it only ever tightens.
 *
 * Zerodha: a protection is set up only after the owner's PIN or fingerprint
 * (once, when it is created, as the owner chose); its later moves need nothing
 * more. Every order here is an exit: neither the account-guard limits nor the
 * kill switch stop it (a drawdown turns the kill switch on by itself).
 *
 * A protection is finished only once its orders are confirmed gone at the
 * broker; until then it stays on the list and is retried on every pass, so a
 * full-size exit is never left resting unwatched.
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
    private lateinit var app: Context
    private val lock = Mutex()
    private var cache: MutableList<Item>? = null

    /** The note of a protection being removed whose orders are not yet confirmed gone. */
    private const val REMOVING = "removing"
    private val GONE = setOf("CANCELLED", "REJECTED", "COMPLETE")

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.noBackupFilesDir, "protections.vault")
    }

    private fun load(): MutableList<Item> {
        cache?.let { return it }
        val out = ArrayList<Item>()
        val read = runCatching {
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
        if (read.isFailure && file.exists()) {
            // Never overwrite it: kept aside, and the owner told that exits may still rest at the broker.
            Vault.setAside(file)
            Alerts.error("The saved stops and targets could not be read and were set aside. Their orders may still be resting: check the order book.", "Protection")
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
        val c = Paper.contractOf(symbol) ?: return "That paper contract is not known."
        return lock.withLock {
            val list = load()
            // The old exits must be gone first, or the old and the new could both fill.
            val old = list.filter { it.active && !it.live && it.symbol == symbol }
            if (!old.map { cancelOrders(it, "protection_replaced") }.all { it }) return@withLock "Not protected: the previous stop or target on $symbol could not be cancelled; try again."
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
            // An MCX contract keeps its own exchange and tick (9 Oct); NFO exactly as before.
            val tick = if (c.isMcx) McxMarket.find(symbol)?.tick ?: 0.05 else 0.05
            val item = Item(System.currentTimeMillis(), false, symbol, c.exchange, product, qty, c.lotSize, tick, s0, trail, target, price, stopId, targetId)
            list += item; save(list)
            "Protected ${symbol}: ${item.describe()}"
        }
    }

    /**
     * Protect a Zerodha position. Call only after the owner's PIN or fingerprint.
     * The stop goes to Zerodha as an SL order with a limit, the target as a LIMIT order.
     */
    suspend fun protectLive(symbol: String, exchange: String, product: String, qty: Int, price: Double,
                            stop: Double?, trail: Double?, target: Double?): String {
        val s = AppSettings.load()
        if (!s.live || !s.allowRealOrders) return "Switch to Live with the badge at the top first."
        Protection.validate(if (qty > 0) 1 else -1, price, stop, trail, target)?.let { return it }
        val spec = runCatching { Broker.spec(exchange, symbol) }.getOrNull() ?: return "Could not read $symbol's lot and tick from Zerodha."
        return lock.withLock {
            val list = load()
            // The old exits must be confirmed gone first, or the old and the new could both fill.
            val old = list.filter { it.active && it.live && it.symbol == symbol }
            if (!old.map { cancelOrders(it) }.all { it }) return@withLock "Not protected: the previous stop or target on $symbol could not be confirmed cancelled at Zerodha; try again in a moment."
            list.replaceAll { if (it.active && it.live && it.symbol == symbol) it.copy(active = false, note = "replaced") else it }
            if (old.isNotEmpty()) save(list)
            val known = old.flatMap { listOfNotNull(it.stopOrderId, it.targetOrderId) }
            val dir = if (qty > 0) 1 else -1
            val s0 = Protection.initialStop(dir, price, stop, trail, spec.tickSize)
            val side = if (qty > 0) Kite.Side.SELL else Kite.Side.BUY
            var stopId: String? = null; var targetId: String? = null
            var partly = ""
            // SL (with a limit), not SL-M: Zerodha refuses SL-M on options.
            if (s0 != null) stopId = try {
                placeTracked(Kite.Order(symbol, side, abs(qty), spec.lotSize, product, "SL", stopLimit(s0, side, spec.tickSize),
                    spec.tickSize, exchange, "iraprotect", triggerPrice = s0), known)
            } catch (e: Exception) { return@withLock "Not protected: ${e.message}" }
            if (target != null) targetId = try {
                placeTracked(Kite.Order(symbol, side, abs(qty), spec.lotSize, product, "LIMIT",
                    Kite.onTick(target, spec.tickSize, side), spec.tickSize, exchange, "iraprotect"), known + listOfNotNull(stopId))
            } catch (e: Exception) {
                if (stopId == null) return@withLock "Not protected: ${e.message}"
                // The stop is at Zerodha: it is kept and watched rather than cancelled, and the owner told.
                partly = " The target was not placed (${e.message}); only the stop rests at Zerodha."
                null
            }
            stopId?.let { Strategies.tagOwner("kite:$it", "Protection · stop") }
            targetId?.let { Strategies.tagOwner("kite:$it", "Protection · target") }
            val item = Item(System.currentTimeMillis(), true, symbol, exchange, product, qty, spec.lotSize, spec.tickSize, s0, trail,
                target.takeIf { targetId != null }, price, stopId, targetId)
            list += item; save(list)
            "Protected $symbol at Zerodha: ${item.describe()}$partly"
        }
    }

    /** Remove a protection: its resting orders are cancelled. */
    suspend fun remove(id: Long): String = lock.withLock {
        val list = load()
        val p = list.firstOrNull { it.id == id && it.active } ?: return@withLock "Nothing to remove."
        if (!cancelOrders(p)) {
            // Kept, and cancelled again on every pass: marked finished now, an exit still resting at the
            // broker would be forgotten while it can still fill.
            list.replaceAll { if (it.id == id) it.copy(note = REMOVING) else it }
            save(list)
            return@withLock "${p.symbol}: its stop or target could not be confirmed cancelled. The protection stays listed and is cancelled again on every pass until the broker confirms."
        }
        list.replaceAll { if (it.id == id) it.copy(active = false, note = "removed") else it }
        save(list)
        "Protection removed from ${p.symbol}."
    }

    /** Remove every active protection on [symbol] (the position was squared off). The owner is told of any not yet confirmed. */
    suspend fun removeSymbol(live: Boolean, exchange: String, symbol: String): Unit = lock.withLock {
        val list = load()
        var changed = false
        for ((i, p) in list.withIndex()) {
            if (!p.active || p.live != live || p.symbol != symbol || p.exchange != exchange) continue
            list[i] = if (cancelOrders(p, "position_closed")) p.copy(active = false, note = "position closed") else p.copy(note = REMOVING).also {
                Alerts.error("$symbol: its protection's stop or target could not be confirmed cancelled; it is cancelled again on every pass.", "Protection")
            }
            changed = true
        }
        if (changed) save(list)
    }

    /**
     * Cancel the protection's resting exits. True only once every one is confirmed gone (cancelled, rejected
     * or filled); an order from an earlier day ended with that day (Zerodha's DAY validity). [why]: why the paper exits are
     * cancelled, noted beside each for Jarvis ([Paper.cancel]); Zerodha's are cancelled as before.
     */
    private suspend fun cancelOrders(p: Item, why: String = "protection_removed"): Boolean {
        var gone = true
        val today = Market.today().atStartOfDay(com.optionslab.engine.IST).toInstant().toEpochMilli()
        for (id in listOfNotNull(p.stopOrderId, p.targetOrderId)) {
            if (p.live) {
                if (p.id < today) continue
                if (runCatching { Broker.orderState(id)?.status }.getOrNull() in GONE) continue
                runCatching { Broker.cancel(id) }
                if (runCatching { Broker.orderState(id)?.status }.getOrNull() !in GONE) gone = false
            } else {
                runCatching { Paper.cancel(id, why) }
                val st = Paper.state.orders.firstOrNull { it.orderId == id }?.status
                if (st != null && st !in setOf("cancelled", "rejected", "complete")) gone = false
            }
        }
        return gone
    }

    /**
     * Place one exit. A lost reply is looked up in the order book (not adopting any of [known]), so an
     * order that reached Zerodha is never left resting untracked; throws when it was not placed, or when
     * its fate cannot be told (the message says to check the order book).
     */
    private suspend fun placeTracked(o: Kite.Order, known: Collection<String>): String = try {
        Broker.placeOrder(o, exit = true)
    } catch (e: Exception) {
        if (Broker.definite(e)) throw e
        val found = try { Broker.findRecentRetrying(o, known) } catch (_: Exception) {
            throw java.io.IOException("${e.message}; the order book could not be read, so a ${o.orderType} ${o.side.name} of ${o.tradingSymbol} " +
                "may be resting at Zerodha: check the order book")
        }
        found ?: throw java.io.IOException("${e.message}; no matching order found at Zerodha")
    }

    /**
     * The limit of a stop exit: [STOP_SLIP] past the trigger (at least 5 ticks), so a fast
     * market still fills it; a SELL may accept down to it, a BUY may pay up to it.
     */
    fun stopLimit(trigger: Double, side: Kite.Side, tick: Double): Double {
        val room = maxOf(trigger * STOP_SLIP, tick * 5)
        return if (side == Kite.Side.SELL) Kite.onTick(maxOf(tick, trigger - room), tick, side) else Kite.onTick(trigger + room, tick, side)
    }
    private const val STOP_SLIP = 0.10

    // ---- watching ---------------------------------------------------------------------------

    /**
     * One pass: for each active protection - one exit filled? cancel the other and finish;
     * position gone? cancel both; trailing? move the stop up to the new level.
     * Called by the market watch and while the app is open.
     */
    suspend fun tick() = lock.withLock {
        val list = load()
        if (list.none { it.active }) return@withLock
        warnIfBlind(list)
        var changed = false
        for ((i, p) in list.withIndex()) {
            if (!p.active) continue
            val next = runCatching { if (p.live) tickLive(p) else tickPaper(p) }.getOrNull() ?: continue
            if (next != p) { list[i] = next; changed = true }
        }
        if (changed) save(list)
    }

    @Volatile private var blindWarned = 0L

    /**
     * Logged out of Zerodha with live protections on, in market hours: their orders still rest, but nothing
     * cancels the other exit when one fills, and nothing trails or resizes them. Said every 15 minutes.
     */
    private fun warnIfBlind(list: List<Item>) {
        val n = list.count { it.active && it.live }
        if (n == 0 || Broker.loggedIn || !Market.isOpen()) return
        val now = System.currentTimeMillis()
        if (now - blindWarned < 15 * 60_000) return
        blindWarned = now
        val text = "Logged out of Zerodha: $n protection(s) are not being watched. Their orders still rest at Zerodha, but when one " +
            "fills the other is NOT cancelled, and stops do not trail. Log in again."
        Alerts.error(text, "Protection")
        runCatching { com.optionslab.app.work.Notifier.post(app, 2031, com.optionslab.app.work.Notifier.RISK, "Protections not watched", text, "trade") }
    }

    /** A protected position is no longer protected: in the app and as a notification. */
    private fun unprotected(text: String) {
        Alerts.error(text, "Protection")
        runCatching { com.optionslab.app.work.Notifier.post(app, 2031, com.optionslab.app.work.Notifier.RISK, "No longer protected", text, "trade") }
    }

    private suspend fun tickPaper(p: Item): Item {
        if (p.note == REMOVING) return if (cancelOrders(p)) p.copy(active = false, note = "removed") else p
        val orders = Paper.state.orders.associateBy { it.orderId }
        val stopDone = p.stopOrderId?.let { orders[it]?.status == "complete" } == true
        val targetDone = p.targetOrderId?.let { orders[it]?.status == "complete" } == true
        if (stopDone || targetDone) {
            (if (stopDone) p.targetOrderId else p.stopOrderId)?.let { runCatching { Paper.cancel(it, if (stopDone) "oco:stop" else "oco:target") } }
            Alerts.post("${p.symbol}: ${if (stopDone) "stop" else "target"} filled (paper); the other exit was cancelled.",
                if (stopDone) Alerts.Kind.ERROR else Alerts.Kind.SUCCESS, "Protection")
            return p.copy(active = false, note = if (stopDone) "stop filled" else "target filled")
        }
        val net = Paper.state.positions.filter { it.symbol == p.symbol && it.product == p.product }.sumOf { it.quantity }
        if (net == 0 || net.sign() != p.qty.sign()) {
            listOfNotNull(p.stopOrderId, p.targetOrderId).forEach { runCatching { Paper.cancel(it, "position_closed") } }
            return p.copy(active = false, note = "position closed")
        }
        if (p.trail == null) return p
        val c = Paper.contractOf(p.symbol) ?: return p
        // Battery (round 6): the price Paper.tick read for this symbol in this same pass (under 2 s ago), else a fresh read.
        val ltp = Paper.stopPrice(c) ?: return p
        val n = Protection.next(p.spec(), ltp)
        val ns = n.stop
        val orderId = p.stopOrderId
        if (ns != null && ns != p.stop && orderId != null) {
            val r = Paper.modify(orderId, null, null, ns)
            if (!r.ok) return p.copy(best = n.best)
        }
        return p.copy(best = n.best, stop = ns)
    }

    private suspend fun tickLive(p: Item): Item {
        if (!Broker.loggedIn) return p
        if (p.note == REMOVING) return if (cancelOrders(p)) p.copy(active = false, note = "removed") else p
        val rows = Broker.orders().associateBy { it.id }
        val so = p.stopOrderId?.let { rows[it] }; val to = p.targetOrderId?.let { rows[it] }
        val stopDone = so?.status == "COMPLETE"; val targetDone = to?.status == "COMPLETE"
        suspend fun netNow(): Int? = runCatching {
            Broker.positionBook().net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }.sumOf { it.qty }
        }.getOrNull()
        if (stopDone || targetDone) {
            // One-cancels-other: finished only once the other exit is confirmed gone, or it
            // would still rest at Zerodha and could open a naked position when it fills.
            val other = if (stopDone) to else so
            if (other != null && other.working) {
                runCatching { Broker.cancel(other.id, other.variety) }
                val st = runCatching { Broker.orderState(other.id)?.status }.getOrNull()
                if (st == null || st !in GONE) return p
            }
            Alerts.post("${p.symbol}: ${if (stopDone) "stop" else "target"} filled at Zerodha; the other exit was cancelled.",
                if (stopDone) Alerts.Kind.ERROR else Alerts.Kind.SUCCESS, "Protection")
            // The other exit may have part-filled before it was cancelled: what is left is said plainly.
            val left = netNow()
            if (left != null && left != 0) unprotected("${p.symbol}: after the ${if (stopDone) "stop" else "target"} filled, Zerodha shows ${p.product} net $left. " +
                if (left.sign() != p.qty.sign()) "That is a position the other way round: close it in Trade." else "That part is no longer protected.")
            return p.copy(active = false, note = if (stopDone) "stop filled" else "target filled")
        }
        // The position closed by hand (or flipped): the resting exits would now open a new position.
        // (Not in its first minute: the position book can trail a fresh fill.)
        val net = if (System.currentTimeMillis() - p.id < 60_000) null else netNow()
        if (net != null && (net == 0 || net.sign() != p.qty.sign())) {
            listOfNotNull(so, to).filter { it.working }.forEach { runCatching { Broker.cancel(it.id, it.variety) } }
            val still = runCatching { Broker.orders().filter { it.id == p.stopOrderId || it.id == p.targetOrderId }.any { it.working } }.getOrElse { true }
            if (still) return p
            return p.copy(active = false, note = "position closed")
        }
        // A stop refused or cancelled at Zerodha leaves the position unprotected: say so.
        if (so != null && so.status in setOf("REJECTED", "CANCELLED") && p.stopOrderId != null) {
            unprotected("${p.symbol}: the stop at Zerodha was ${so.status.lowercase()}${so.message.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}. The position is not protected by a stop.")
            if (to == null || !to.working) return p.copy(active = false, stopOrderId = null, note = "stop ${so.status.lowercase()}")
            return p.copy(stopOrderId = null, note = "stop ${so.status.lowercase()}")
        }
        // Both exits gone some other way (a DAY order that ended with its day, cancelled at Zerodha): say so.
        if ((so == null || !so.working) && (to == null || !to.working) && (p.stopOrderId != null || p.targetOrderId != null)) {
            if (net != 0) unprotected("${p.symbol}: its stop and target are no longer working at Zerodha (orders last only the day they are placed). " +
                "The position is NOT protected any more: set the protection again.")
            return p.copy(active = false, note = "exit orders no longer working")
        }
        // Less held than the exits cover (part closed by hand, or one exit part-filled): both come down to what
        // is held, or the one that fills in full would leave the account the other way round.
        var stopQty = so?.qty ?: 0
        var cur = p
        if (net != null && net != 0) {
            for (o in listOfNotNull(so, to).filter { it.working }) {
                val n = com.optionslab.engine.risk.ExitQty.shrinkTo(o.qty, o.filled, net, p.lotSize) ?: continue
                val r = runCatching {
                    Broker.modify(o, n, o.type, if (o.type == "SL-M") null else o.price, if (o.type == "SL" || o.type == "SL-M") o.trigger else null)
                }
                if (r.isSuccess) { if (o.id == so?.id) stopQty = n }
                else Alerts.post("${p.symbol}: an exit covers more than is held ($net) and could not be reduced (${r.exceptionOrNull()?.message}); retrying.",
                    Alerts.Kind.ERROR, "Protection", throttle = true)
            }
            if (abs(net) < abs(p.qty)) cur = p.copy(qty = net)
        }
        if (cur.trail == null || so == null || !so.working) return cur
        val key = "${p.exchange}:${p.symbol}"
        val ltp = Broker.quotes(listOf(key))[key]?.last ?: return cur
        val n = Protection.next(cur.spec(), ltp)
        val ns = n.stop   // a local: the engine's property cannot be smart-cast across modules
        if (ns != null && ns != cur.stop && abs(ns - (cur.stop ?: 0.0)) >= p.tick - 1e-9) {
            val side = if (p.qty > 0) Kite.Side.SELL else Kite.Side.BUY
            val r = runCatching {
                if (so.type == "SL-M") Broker.modify(so, stopQty, "SL-M", null, ns)
                else Broker.modify(so, stopQty, "SL", stopLimit(ns, side, p.tick), ns)
            }
            if (r.isFailure) return cur.copy(best = n.best)
        }
        return cur.copy(best = n.best, stop = ns)
    }

    /**
     * Moves an active protection's stop to [newStop] by modifying its resting stop order in place (never cancel and
     * re-place: if the broker refuses, the old stop stays where it was). Returns null when moved, else why not.
     */
    suspend fun moveStop(id: Long, newStop: Double): String? = lock.withLock {
        val list = load()
        val i = list.indexOfFirst { it.id == id && it.active }
        if (i < 0) return@withLock "That protection is no longer active."
        val p = list[i]
        if (p.note == REMOVING) return@withLock "That protection is being removed."
        val orderId = p.stopOrderId ?: return@withLock "It has no resting stop order to move."
        val tick = if (p.tick > 0) p.tick else 0.05
        val ns = Math.round(newStop / tick) * tick
        if (p.live) {
            if (!Broker.loggedIn) return@withLock "Not logged in to Zerodha."
            val so = runCatching { Broker.orders().firstOrNull { it.id == orderId } }.getOrNull()
                ?: return@withLock "The stop order was not found at Zerodha."
            if (!so.working) return@withLock "The stop order is no longer working at Zerodha."
            val side = if (p.qty > 0) Kite.Side.SELL else Kite.Side.BUY
            val r = runCatching {
                if (so.type == "SL-M") Broker.modify(so, so.qty, "SL-M", null, ns)
                else Broker.modify(so, so.qty, "SL", stopLimit(ns, side, tick), ns)
            }
            if (r.isFailure) return@withLock "Zerodha did not move it: ${r.exceptionOrNull()?.message ?: "an error"}."
        } else {
            val r = runCatching { Paper.modify(orderId, null, null, ns) }.getOrNull()
            if (r == null || !r.ok) return@withLock "The paper stop was not moved${r?.message?.let { ": $it" } ?: ""}."
        }
        list[i] = p.copy(stop = ns)
        save(list)
        null
    }

    private fun Int.sign() = if (this > 0) 1 else if (this < 0) -1 else 0

    /** Reset paper: every paper stop / target / trail is dropped; Zerodha's are untouched. */
    suspend fun resetPaper(): Unit = lock.withLock { save(load().filter { it.live }) }

    fun wipe() { cache = null; if (::file.isInitialized) file.delete() }
}
