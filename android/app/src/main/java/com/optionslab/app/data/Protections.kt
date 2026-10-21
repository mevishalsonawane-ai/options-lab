package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Alerts
import com.optionslab.engine.Kite
import com.optionslab.engine.risk.Protection
import com.optionslab.ira.DayGttTally
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
        /**
         * 20 Oct, enhancement round 1 (idea 2): a carried (not intraday) live position's stop and target resting at Zerodha as a GTT
         * while its DAY orders are gone (after the close, overnight, before the open), so a dead phone misses nothing. Deleted
         * before the DAY orders are placed again at the open: never two resting exits for one position. Null: none.
         */
        val gttId: Long? = null,
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
    /** A carried position's exits ended with the session: placed again at the next open. */
    private const val WAITING = "waiting for the next session"
    /**
     * 21 Oct, enhancement round 4 (idea 1): a carried position's stop and target resting all day as ONE two-leg GTT ([DayOco] on):
     * [Item.gttId] is it, and there are no DAY orders. A null [Item.gttId] with this note: its GTT (or, off, its two orders) is
     * placed on the next look.
     */
    internal const val DAY_GTT = "resting as one GTT at Zerodha (OCO)"
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
                    s("stopId"), s("targetId"), o.optBoolean("active", true), o.optString("note"), o.optLong("gtt", 0L).takeIf { it > 0L })
            }
        }
        if (read.isFailure && file.exists()) {
            // Never overwrite it: kept aside, and the owner told that exits may still rest at the broker.
            Vault.setAside(file)
            Alerts.error("The saved stops and targets could not be read and were set aside. Their orders may still be resting: check the order book.", "Protection")
        }
        cache = out
        hint(out)
        return out
    }

    /**
     * Whether a protection is active, as of the last load or save (null: not loaded yet in this process): read without the
     * lock by the event-driven checks ([FastPath]), which then run [tick] between the watch's passes.
     */
    @Volatile var activeHint: Boolean? = null
        private set

    /** "EXCHANGE:SYMBOL" of the active live protections, as [activeHint] (round 2: followed on the stream, trailed from it). */
    @Volatile var liveKeysHint: List<String> = emptyList()
        private set

    private fun hint(list: List<Item>) {
        activeHint = list.any { it.active }
        liveKeysHint = list.filter { it.active && it.live }.map { "${it.exchange}:${it.symbol}" }.distinct()
        overnightHint = list.any { it.active && it.live && it.product != "MIS" && it.exchange != "MCX" && it.gttId == null && it.note != REMOVING &&
            (it.stop != null || it.target != null) && (gttTries[it.id] ?: 0) < GTT_TRIES }
        mcxOvernightHint = list.any { it.active && it.live && it.product != "MIS" && it.exchange == "MCX" && it.gttId == null && it.note != REMOVING &&
            (it.stop != null || it.target != null) && (gttTries[it.id] ?: 0) < GTT_TRIES }
        // Review: every active live protection's GTT (a day one, an overnight one, one being removed), not only those noted DAY_GTT.
        dayGttHint = list.filter { it.active && it.live && it.gttId != null }
            .groupBy { "${it.exchange}:${it.symbol}" }.mapValues { e -> e.value.mapNotNull { it.gttId } }
        freshDayGtts = emptyMap()
    }

    /** 21 Oct, enhancement round 4 (idea 1): "EXCHANGE:SYMBOL" to the GTT ids of the active live protections. Memory only. */
    @Volatile var dayGttHint: Map<String, List<Long>> = emptyMap()
        private set

    /** Review: a day GTT placed in its exit turn, before the list holding it is saved (the expiry exits see it at once). */
    @Volatile private var freshDayGtts: Map<String, List<Long>> = emptyMap()

    /**
     * 21 Oct, enhancement round 4 (idea 1), for the expiry exits ([ExpirySquareOff], [McxGuard]): the protections' day GTTs on
     * [exchange]:[symbol] deleted and confirmed gone, so none can fire after the exit has sold the position (a short on expiry day).
     * True: none active (the exit goes on); false: one could not be confirmed gone (the exit waits for its next pass). Inside the
     * caller's exit turn; never takes this object's lock (the watch takes the turns inside it). No read at all without one.
     */
    suspend fun dayGttsOut(exchange: String, symbol: String, who: String): Boolean {
        val key = "$exchange:$symbol"
        val ids = (dayGttHint[key].orEmpty() + freshDayGtts[key].orEmpty()).toSet()
        if (ids.isEmpty()) return true
        val rows = (runCatching { ExitCoordinator.gtts(exchange, symbol, who) }.getOrNull() ?: return false).filter { it.id in ids }
        // Review: one that fired since the pass read the orders has a sell working that the pass did not see: cancelled, and the
        // exit waits for the next pass (which reads both books again).
        val fired = rows.filter { !it.active && it.status.lowercase() !in GTT_GONE }.flatMap { it.orderIds }
        if (fired.isNotEmpty()) {
            val working = (runCatching { Broker.orders() }.getOrNull() ?: return false).filter { it.id in fired && it.working }
            if (working.isNotEmpty()) { working.forEach { runCatching { Broker.cancel(it.id, it.variety) } }; return false }
        }
        val live = rows.filter { it.active }
        if (live.isEmpty()) return true
        live.forEach { runCatching { Broker.deleteGtt(it.id) } }
        val after = runCatching { Broker.gtts() }.getOrNull() ?: return false
        return after.none { it.id in ids && it.active }
    }

    /**
     * 20 Oct, enhancement round 1 (idea 2): an active carried (not intraday) live NSE protection without its overnight GTT yet, as of
     * the last load or save - the watch keeps its evening look ([com.optionslab.ira.EveningLook]) while it is true. Memory only.
     */
    @Volatile var overnightHint: Boolean = false
        private set

    /**
     * 20 Oct, enhancement round 2 (idea 3): the same for a carried live MCX protection: MCX's DAY stop and target end at its 23:30
     * (23:55) close, and the watch keeps a look after that close while it is true ([McxMarket.eveningLookDue]). Memory only.
     */
    @Volatile var mcxOvernightHint: Boolean = false
        private set

    /** True while a fast look decides from the stream (round 2, set under [lock] by [tick]): Zerodha's books as last read. */
    @Volatile private var streamLook = false

    private fun save(list: List<Item>) {
        val a = JSONArray()
        // Finished protections are kept a while for the record, then dropped.
        (list.filter { it.active } + list.filter { !it.active }.takeLast(50)).forEach { p ->
            a.put(JSONObject().put("id", p.id).put("live", p.live).put("symbol", p.symbol).put("exchange", p.exchange).put("product", p.product)
                .put("qty", p.qty).put("lot", p.lotSize).put("tick", p.tick).put("stop", p.stop ?: JSONObject.NULL).put("trail", p.trail ?: JSONObject.NULL)
                .put("target", p.target ?: JSONObject.NULL).put("best", p.best).put("stopId", p.stopOrderId ?: "").put("targetId", p.targetOrderId ?: "")
                .put("active", p.active).put("note", p.note).put("gtt", p.gttId ?: 0L))
        }
        Vault.writeFile(file, a.toString().toByteArray(Charsets.UTF_8))
        cache = list.toMutableList()
        hint(list)
    }

    suspend fun active(): List<Item> = lock.withLock { load().filter { it.active } }

    /** Every protection set at or after [fromMs], active or not (the order review's read after the close, 12 Oct). */
    suspend fun since(fromMs: Long): List<Item> = lock.withLock { load().filter { it.id >= fromMs } }
    suspend fun forSymbol(live: Boolean, symbol: String): Item? = lock.withLock { load().lastOrNull { it.active && it.live == live && it.symbol == symbol } }

    // ---- setting one up -------------------------------------------------------------------

    /**
     * Protect a paper position. [qty] is the position's signed quantity; the exits cover all of it.
     * Returns a message for the owner.
     */
    suspend fun protectPaper(symbol: String, product: String, qty: Int, price: Double, stop: Double?, trail: Double?, target: Double?): String {
        // The position's own price (12 Oct): a long's target under what was paid is refused, said why ([Protection.validate]).
        val entry = if (target == null) null else runCatching {
            Paper.state.positions.firstOrNull { it.symbol == symbol && it.quantity != 0 }?.averagePrice?.toDouble()
        }.getOrNull()
        Protection.validate(if (qty > 0) 1 else -1, price, stop, trail, target, entry)?.let { return "Not protected: $it" }
        // A request that would set no stop and no target (12 Oct, 10:44: "Protected SILVERM...: " with nothing after it,
        // the old stop replaced by nothing) is refused before anything is cancelled.
        if (target == null && Protection.initialStop(if (qty > 0) 1 else -1, price, stop, trail) == null)
            return "Not protected: that stop would be at or under zero, so nothing was changed."
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
            val item = Item(nowMs(), false, symbol, c.exchange, product, qty, c.lotSize, tick, s0, trail, target, price, stopId, targetId)
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
        // The position's own price, read only when a target is asked (12 Oct): never a long's target under the price paid.
        val entry = if (target == null) null else runCatching {
            Broker.within(5_000) { Broker.positionBook() }?.net?.firstOrNull { it.symbol == symbol && it.exchange == exchange && it.qty != 0 }?.avg
        }.getOrNull()
        Protection.validate(if (qty > 0) 1 else -1, price, stop, trail, target, entry)?.let { return "Not protected: $it" }
        if (target == null && Protection.initialStop(if (qty > 0) 1 else -1, price, stop, trail) == null)
            return "Not protected: that stop would be at or under zero, so nothing was changed."
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
            var replaced = false
            // 21 Oct, enhancement round 4 (idea 1): a carried position's stop and target as ONE two-leg GTT ([DayOco] on): whichever
            // fires, Zerodha cancels the other, even with the phone off. Not placed (refused, not yet held, the books not read): the
            // two DAY orders as before, said.
            val fresh = Item(nowMs(), true, symbol, exchange, product, qty, spec.lotSize, spec.tickSize, s0, trail, target, price, null, null)
            if (dayOco(fresh)) when (val r = restDayGtt(fresh, qty)) {
                is DayGtt.Placed -> {
                    list += r.item; save(list)
                    return@withLock "Protected $symbol at Zerodha as one GTT (whichever fires, Zerodha cancels the other): ${r.item.describe()}"
                }
                // Review: Zerodha's answer lost - the GTT may exist. Nothing else is placed beside it: the watch's next look finds it
                // (and keeps it) or rests it then.
                DayGtt.Unknown -> {
                    val item = fresh.copy(note = DAY_GTT)
                    list += item; save(list)
                    return@withLock "$symbol: Zerodha's answer to the GTT was lost. The app looks for it and rests the stop and target on " +
                        "its next look (within a minute); check Orders → GTT."
                }
                DayGtt.Refused -> { partly = " (It could not rest as one GTT just now, so the stop and target rest as two orders today.)"; replaced = true }
            }
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
            // 21 Oct, enhancement round 5 (idea 1): the two orders in the day GTT's place, counted for the diagnostics.
            if (replaced && (stopId != null || targetId != null)) DayOco.note(DayGttTally.Event.REPLACED)
            val item = Item(nowMs(), true, symbol, exchange, product, qty, spec.lotSize, spec.tickSize, s0, trail,
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
        // 20 Oct, enhancement round 1: a carried position's overnight GTT ([Item.gttId]) is deleted too (and a fired one's working
        // exit cancelled): left behind, it would sell what is no longer held.
        if (p.live && p.gttId != null && !gttGone(p, cancelFired = true)) gone = false
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

    /** The clock of the ids and the first-minute check (the market's: one clock, tests included). */
    private fun nowMs(): Long = Market.now().toInstant().toEpochMilli()

    /** [exchange]'s market open now (MCX's own hours; NSE's otherwise). */
    private fun marketOpen(exchange: String): Boolean = runCatching {
        if (exchange == "MCX") McxMarket.isOpen() else Market.isOpen()
    }.getOrDefault(false)

    // ---- 20 Oct, enhancement round 1 (idea 2): a carried position's stop rests at Zerodha overnight as a GTT ---------------------

    /** A GTT's statuses that mean it is over at Zerodha. */
    private val GTT_GONE = setOf("deleted", "cancelled", "expired", "rejected", "disabled")

    /** Overnight GTTs tried and refused per protection id (memory: a restart tries again), so a refusal is not sent every pass. */
    private val gttTries = HashMap<Long, Int>()
    private const val GTT_TRIES = 3

    /**
     * Whether [p]'s overnight GTT is gone: none, deleted or over at Zerodha, or fired (its exit order then rests or filled; with
     * [cancelFired] - the position closed or the protection removed - a fired one's exit still working is cancelled and must be
     * confirmed gone). An active one is deleted and confirmed gone. False when that cannot be confirmed (the list not read, the
     * delete refused): the caller keeps the protection and asks again on the next pass, so two exits never rest together.
     */
    private suspend fun gttGone(p: Item, cancelFired: Boolean): Boolean = gttState(p, cancelFired) != GttState.HELD

    /** [gttGone]'s answer in full: gone, fired (Zerodha placed its exit), or not confirmed gone (held: ask again next pass). */
    private enum class GttState { GONE, FIRED, HELD }

    private suspend fun gttState(p: Item, cancelFired: Boolean): GttState {
        val id = p.gttId ?: return GttState.GONE
        suspend fun read(): Broker.GttRow? = Broker.gtts().firstOrNull { it.id == id }
        var g = runCatching { read() }.getOrElse { return GttState.HELD }
        if (g != null && g.active) {
            runCatching { Broker.deleteGtt(id) }
            g = runCatching { read() }.getOrElse { return GttState.HELD }
            if (g != null && g.active) return GttState.HELD
        }
        if (g == null || g.status.lowercase() in GTT_GONE) return GttState.GONE
        // Fired: Zerodha placed its exit (its order id may not be listed yet). At the open it is kept as it is (an exit already
        // working: the next pass re-reads what is held and [restAgain] counts it), and said plainly when it has not filled.
        if (!cancelFired) {
            val resting = runCatching { Broker.orders() }.getOrNull()?.filter { it.id in g.orderIds && it.working }.orEmpty()
            if (resting.isNotEmpty()) unprotected("${p.symbol}: its overnight GTT fired; its sell of ${resting.sumOf { it.qty - it.filled }} at " +
                "%.2f rests at Zerodha unfilled. If the price stays under it, close the position in Trade.".format(resting.first().price))
            return GttState.FIRED
        }
        if (g.orderIds.isEmpty()) return GttState.FIRED
        val rows = runCatching { Broker.orders() }.getOrElse { return GttState.HELD }.filter { it.id in g.orderIds }
        rows.filter { it.working }.forEach { runCatching { Broker.cancel(it.id, it.variety) } }
        for (oid in g.orderIds) {
            // Not in today's book (an earlier day's): over. Read failed or still working: not confirmed yet. 21 Oct, round 4 (found by
            // its test): Zerodha answers an earlier session's order id with an InputException, never an empty history - that is over too.
            val st = try { Broker.orderState(oid)?.status } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Broker.KiteError) { if (e.type == "InputException") null else return GttState.HELD
            } catch (_: Exception) { return GttState.HELD }
            if (st != null && st !in GONE) return GttState.HELD
        }
        return GttState.FIRED
    }

    /**
     * [p] (a carried live position, [net] held, its DAY exits gone with the session) with its stop and target resting at Zerodha as
     * one GTT (two-leg when it has both: whichever fires first closes it) for what it covers - never more than is held less any
     * other exit already resting (an order or another GTT, an arm's own). Only ever adds a stop: it lowers risk. Refused (the price
     * already through the stop, within 0.25% of it): said plainly, and tried again at most [GTT_TRIES] times. Unchanged when it
     * cannot be placed now; with its id once placed.
     */
    private suspend fun restOvernight(p: Item, net0: Int): Item {
        if (!p.live || p.gttId != null || p.product == "MIS" || p.note == REMOVING || (p.stop == null && p.target == null) || streamLook) return p
        if ((gttTries[p.id] ?: 0) >= GTT_TRIES) return p
        // 20 Oct, enhancement round 2: read and rested in the contract's one exit turn, from reads that show every exit sent from here
        // ([ExitCoordinator]), the orders and GTTs first and then the position read again (an exit filling meanwhile is never doubled).
        return ExitCoordinator.turn(p.exchange, p.symbol, "protection") { restOvernightInTurn(p, net0) }
    }

    private suspend fun restOvernightInTurn(p: Item, net0: Int): Item {
        val side = if (p.qty > 0) Kite.Side.SELL else Kite.Side.BUY
        val gtts = runCatching { ExitCoordinator.gtts(p.exchange, p.symbol, "protection") }.getOrNull() ?: return p
        val orders = runCatching { ExitCoordinator.orders(p.exchange, p.symbol, "protection") }.getOrNull() ?: return p
        val net = heldAgain(p, orders) ?: return p
        if (net == 0 || net.sign() != net0.sign()) return p                // closed meanwhile: the next look ends it
        // Review: a GTT of this protection's own that went through but was never recorded (a lost reply, a save that did not land)
        // is adopted, never counted as "another exit": counted busy, the open would place the day's orders beside it.
        val want = listOfNotNull(p.stop, p.target).map { Math.round(Math.round(it / p.tick) * p.tick * 100) / 100.0 }.sorted()   // as [Kite.protect]
        gtts.firstOrNull { g -> g.active && g.symbol == p.symbol && g.exchange == p.exchange && g.legSides.firstOrNull() == side.name &&
            g.legProducts.firstOrNull() == p.product && (g.legQtys.firstOrNull() ?: 0) <= abs(p.qty) && g.triggers.size == want.size &&
            g.triggers.sorted().zip(want).all { (a, b) -> abs(a - b) < p.tick / 2 + 1e-9 } }?.let { mine ->
            gttTries.remove(p.id)
            return p.copy(gttId = mine.id)
        }
        val busy = orders.filter { it.working && it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product && it.side == side.name }
            .sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) } +
            gtts.filter { it.symbol == p.symbol && it.exchange == p.exchange }.sumOf { it.covers(side.name, p.product) }
        // Review: what is held less every other exit, at most what this protection covers (an arm's own lots and GTT beside it
        // never take this protection's place).
        val qty = com.optionslab.engine.risk.ExitQty.sendable(abs(net), busy, abs(p.qty), p.lotSize)
        fun tried() {
            val n = (gttTries[p.id] ?: 0) + 1
            gttTries[p.id] = n
            if (n == GTT_TRIES) unprotected("${p.symbol}: its stop could not be rested at Zerodha overnight as a GTT after $n tries. " +
                "Its stop and target are placed again when the market opens; until then nothing rests at Zerodha.")
        }
        if (qty <= 0) {
            // Not counted as a try: the other exit may go away later in the evening, and the next look checks again.
            Alerts.post("${p.symbol}: an exit already resting at Zerodha covers it overnight, so no GTT was added.", Alerts.Kind.INFO, "Protection", throttle = true)
            return p
        }
        val key = "${p.exchange}:${p.symbol}"
        val last = runCatching { Broker.quotes(listOf(key))[key]?.last }.getOrNull()?.takeIf { it > 0 } ?: return p
        val spec = Kite.Spec(p.exchange, p.symbol, 0L, p.lotSize, p.tick)
        var (gtt, why) = Kite.protect(spec, p.product, p.qty.sign() * qty, last, p.stop, p.target)
        // A target that cannot rest (too near the price) never costs the stop: the stop alone.
        if (gtt == null && p.stop != null && p.target != null) Kite.protect(spec, p.product, p.qty.sign() * qty, last, p.stop, null).let { (g2, w2) -> if (g2 != null) { gtt = g2; why = w2 } }
        val g = gtt
        if (g == null) {
            if ((gttTries[p.id] ?: 0) == 0) unprotected("${p.symbol}: its stop could not rest at Zerodha overnight as a GTT (${why.joinToString("; ")}). " +
                "Its stop and target are placed again when the market opens.")
            gttTries[p.id] = GTT_TRIES          // the price will not move back tonight: not asked again
            return p
        }
        val id = Broker.placeGttOnce(g, gtts.map { it.id }.toSet())
        if (id == null) { tried(); return p }
        gttTries.remove(p.id)
        val stopOnly = g.triggers.size == 1 && p.target != null
        Alerts.post("${p.symbol}: its stop${if (p.target != null && !stopOnly) " and target" else ""} rest at Zerodha overnight as a GTT for $qty " +
            "(${p.describe()}), so they hold even with the phone off. The GTT is replaced by the day's orders at the open.", Alerts.Kind.SUCCESS, "Protection")
        return p.copy(gttId = id)
    }

    // ---- 21 Oct, enhancement round 4 (idea 1): a carried position's stop and target as ONE GTT in the day too ([DayOco]) --------

    /** Whether [p] rests as one two-leg GTT in the day: [DayOco] on, live, carried (not MIS), with both a stop and a target. */
    private fun dayOco(p: Item): Boolean = DayOco.isOn() && p.live && p.product != "MIS" && p.stop != null && p.target != null

    /** Zerodha's last price for [p]'s contract (its quote; a stream look's fresh tick first), or null. */
    private suspend fun lastPrice(p: Item, stream: Boolean): Double? {
        val key = "${p.exchange}:${p.symbol}"
        if (stream) return runCatching { Broker.tokenOf(key)?.let { KiteStream.freshTick(it) }?.last?.takeIf { it > 0 } }.getOrNull()
        return runCatching { Broker.stopQuotes(listOf(key))[key]?.last?.takeIf { it > 0 } }.getOrNull()
    }

    /**
     * At the open, [p]'s overnight GTT ([Item.gttId]) kept for the day when it has both legs, is active, and covers no more than is
     * held ([net]) or than [p] covers: [p] as a day GTT under today's id. Null: not kept (the caller deletes it, as before).
     */
    private suspend fun keepDayGtt(p: Item, net: Int): Item? {
        val id = p.gttId ?: return null
        val g = runCatching { Broker.gtts().firstOrNull { it.id == id } }.getOrNull() ?: return null
        val q = g.legQtys.firstOrNull() ?: return null
        if (!g.active || g.triggers.size != 2 || q <= 0 || q > minOf(abs(net), abs(p.qty))) return null
        Alerts.post("${p.symbol}: its stop and target stay at Zerodha as one GTT today (${p.describe()}): whichever fires, Zerodha " +
            "cancels the other, even with the phone off.", Alerts.Kind.SUCCESS, "Protection")
        DayOco.note(DayGttTally.Event.KEPT)
        return p.copy(id = nowMs(), qty = p.qty.sign() * q, stopOrderId = null, targetOrderId = null, note = DAY_GTT)
    }

    /**
     * [p]'s stop and target as ONE two-leg GTT for what it covers - never more than is held less every other exit resting on the
     * contract (an order or a GTT, an arm's own) - in the contract's one exit turn ([ExitCoordinator]), from reads that show every
     * exit sent from here. [p] as a day GTT under a new id; null when it was not placed (the books or the price not read, nothing
     * left to cover, Zerodha refused it): the caller places the two DAY orders instead.
     */
    private suspend fun restDayGtt(p: Item, net0: Int): DayGtt =
        ExitCoordinator.turn(p.exchange, p.symbol, "protection") { restDayGttInTurn(p, net0) }

    /**
     * [restDayGtt]'s outcome. [Refused]: nothing was sent, or Zerodha said no (the caller may rest the two DAY orders). [Unknown]:
     * the books not read, or the GTT's answer lost and not found in the list (it may exist): the caller places NOTHING else this look.
     */
    private sealed class DayGtt { class Placed(val item: Item) : DayGtt(); object Refused : DayGtt(); object Unknown : DayGtt() }

    private suspend fun restDayGttInTurn(p: Item, net0: Int): DayGtt {
        val side = if (p.qty > 0) Kite.Side.SELL else Kite.Side.BUY
        val gtts = runCatching { ExitCoordinator.gtts(p.exchange, p.symbol, "protection") }.getOrNull() ?: return DayGtt.Unknown
        val orders = runCatching { ExitCoordinator.orders(p.exchange, p.symbol, "protection") }.getOrNull() ?: return DayGtt.Unknown
        val net = heldAgain(p, orders) ?: return DayGtt.Unknown
        if (net == 0 || net.sign() != net0.sign()) return DayGtt.Refused
        // Review: a two-leg GTT of this protection's own that went through but was never recorded (a lost reply) is adopted, as
        // [restOvernightInTurn] does - never one another protection already records.
        val recorded = cache.orEmpty().mapNotNull { it.gttId }.toSet()
        val want = listOfNotNull(p.stop, p.target).map { Math.round(Math.round(it / p.tick) * p.tick * 100) / 100.0 }.sorted()
        gtts.firstOrNull { g -> g.active && g.id !in recorded && g.symbol == p.symbol && g.exchange == p.exchange && g.legSides.firstOrNull() == side.name &&
            g.legProducts.firstOrNull() == p.product && (g.legQtys.firstOrNull() ?: 0) in 1..minOf(abs(net), abs(p.qty)) && g.triggers.size == 2 &&
            want.size == 2 && g.triggers.sorted().zip(want).all { (a, b) -> abs(a - b) < p.tick / 2 + 1e-9 } }?.let { mine ->
            gttTries.remove(p.id)
            noteFresh(p, mine.id)
            DayOco.note(DayGttTally.Event.PLACED)
            return DayGtt.Placed(p.copy(id = nowMs(), qty = p.qty.sign() * (mine.legQtys.firstOrNull() ?: abs(p.qty)), stopOrderId = null,
                targetOrderId = null, gttId = mine.id, note = DAY_GTT))
        }
        val busy = orders.filter { it.working && it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product && it.side == side.name }
            .sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) } +
            gtts.filter { it.symbol == p.symbol && it.exchange == p.exchange }.sumOf { it.covers(side.name, p.product) }
        val qty = com.optionslab.engine.risk.ExitQty.sendable(abs(net), busy, abs(p.qty), p.lotSize)
        if (qty <= 0) return DayGtt.Refused
        val last = lastPrice(p, stream = false) ?: return DayGtt.Refused
        val (g, why) = Kite.protect(Kite.Spec(p.exchange, p.symbol, 0L, p.lotSize, p.tick), p.product, p.qty.sign() * qty, last, p.stop, p.target)
        if (g == null || g.triggers.size != 2) {
            runCatching { Diag.record("protection", "${p.symbol}: its stop and target could not rest as one GTT (${why.joinToString("; ")}); two orders instead") }
            return DayGtt.Refused
        }
        val id = when (val sent = Broker.placeGttSure(g, gtts.map { it.id }.toSet())) {
            is Broker.GttSent.Placed -> sent.id
            Broker.GttSent.Refused -> return DayGtt.Refused
            Broker.GttSent.Unknown -> {
                runCatching { Diag.record("protection", "${p.symbol}: the day GTT's answer was lost and it is not in the list yet; nothing else placed, looked for again") }
                return DayGtt.Unknown
            }
        }
        gttTries.remove(p.id)
        noteFresh(p, id)
        DayOco.note(DayGttTally.Event.PLACED)
        Alerts.post("${p.symbol}: its stop and target rest at Zerodha as one GTT for $qty (${p.describe()}): whichever fires, Zerodha " +
            "cancels the other, even with the phone off.", Alerts.Kind.SUCCESS, "Protection")
        return DayGtt.Placed(p.copy(id = nowMs(), qty = p.qty.sign() * qty, stopOrderId = null, targetOrderId = null, gttId = id, note = DAY_GTT))
    }

    /** A day GTT placed or adopted in its turn: the expiry exits see it at once ([dayGttsOut]), before the list is saved. */
    private fun noteFresh(p: Item, id: Long) {
        val key = "${p.exchange}:${p.symbol}"
        val cur = freshDayGtts
        freshDayGtts = cur + (key to (cur[key].orEmpty() + id))
    }

    /**
     * One look at a protection resting as one GTT ([DAY_GTT]). A stream look ([stream]) only trails it (a modify from the fresh
     * tick; no GTT read). A regular look reads the GTT and what is held:
     *  - fired and the position closed: finished (the caller confirms nothing of the GTT still works); fired, its order still
     *    working: said, kept; fired and its order ended unfilled: said plainly (NOT protected), finished;
     *  - gone at Zerodha (deleted on Kite's site, expired): said plainly while the position is held, finished;
     *  - active: the position closed or turned round - finished (the caller deletes the GTT, confirmed); less held than it covers -
     *    shrunk (a modify); a trailing stop - moved up (a modify, never deleted and placed again); [DayOco] switched off in market
     *    hours - the GTT out first, confirmed, then the day's two orders.
     * A positions read that failed, or a protection's first minute, never ends or changes it.
     */
    private suspend fun tickDayGtt(p: Item, stream: Boolean): Item {
        val gid = p.gttId
        if (stream) {
            if (gid == null) return p
            return trailDayGtt(p, gid, lastPrice(p, stream = true) ?: return p)
        }
        suspend fun netNow(): Int? = runCatching {
            Broker.positionBook().net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }.sumOf { it.qty }
        }.getOrNull()
        val net = if (nowMs() - p.id < 60_000) null else netNow()
        val closed = net != null && (net == 0 || net.sign() != p.qty.sign())
        if (gid == null) return placeDayExits(p, net ?: return p)
        val g = runCatching { Broker.gtts().firstOrNull { it.id == gid } }.getOrElse { return p }
        if (g == null || g.status.lowercase() in GTT_GONE) {
            if (net == null) return p
            if (!closed) DayOco.note(DayGttTally.Event.GONE)
            if (!closed) unprotected("${p.symbol}: its stop and target's GTT is no longer at Zerodha (${g?.status ?: "not listed"}). " +
                "The position is NOT protected: set the protection again.")
            return p.copy(active = false, gttId = null, note = if (closed) "position closed" else "GTT no longer at Zerodha")
        }
        if (!g.active) {
            // Fired: Zerodha placed one leg's order and cancelled the other leg itself.
            if (net == null) return p
            if (closed) {
                // Review: turned round (a fill beside another exit) is said plainly, never as closed.
                if (net != 0) unprotected("${p.symbol}: its GTT fired and Zerodha now shows ${p.product} net $net: a position the other way round. Close it in Trade.")
                else Alerts.post("${p.symbol}: its GTT fired at Zerodha and the position is closed.", Alerts.Kind.INFO, "Protection")
                DayOco.note(DayGttTally.Event.FIRED)
                return p.copy(active = false, note = "GTT fired")
            }
            // Its order's state, each id read on its own (review: an order of an earlier session is not in today's book: ended).
            if (g.orderIds.isEmpty()) {
                Alerts.post("${p.symbol}: its GTT fired; its ${p.exitSide.lowercase()} is on its way at Zerodha.", Alerts.Kind.ERROR, "Protection", throttle = true)
                return p
            }
            // An order of an earlier session is not found at all (Kite's InputException): ended, nothing of it rests. Any other
            // failure (the network): not known, asked again next pass.
            val states = g.orderIds.map { oid ->
                try { Broker.orderState(oid) } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Broker.KiteError) { if (e.type == "InputException") null else return p
                } catch (_: Exception) { return p }
            }
            if (states.any { it != null && it.status !in GONE }) {
                Alerts.post("${p.symbol}: its GTT fired; its ${p.exitSide.lowercase()} rests at Zerodha unfilled. If the price runs away " +
                    "from it, close the position in Trade.", Alerts.Kind.ERROR, "Protection", throttle = true)
                return p
            }
            val filled = states.sumOf { it?.filled ?: 0 }
            unprotected(if (filled > 0) "${p.symbol}: its GTT fired and sold $filled; Zerodha still shows ${p.product} net $net. That part is NOT protected: set the protection again."
                else "${p.symbol}: its GTT fired but its order did not fill (${states.firstOrNull()?.status?.lowercase() ?: "it ended with its session"}); " +
                    "Zerodha shows ${p.product} net $net. The position is NOT protected: set the protection again.")
            DayOco.note(DayGttTally.Event.FIRED)
            return p.copy(active = false, note = if (filled > 0) "GTT fired, part left" else "GTT fired, its order did not fill")
        }
        if (net == null) return p
        if (closed) return p.copy(active = false, note = "position closed")
        // Switched off (as read from the settings, never the unread default after a restart): in market hours, the GTT out first
        // (confirmed), then the day's two orders (never both resting).
        if (DayOco.isKnownOff() && marketOpen(p.exchange)) {
            return when (gttState(p, cancelFired = false)) {
                GttState.HELD, GttState.FIRED -> p                       // asked again next pass (a fired one is read as fired)
                GttState.GONE -> placeDayExits(p.copy(gttId = null), net)
            }
        }
        // Less held than it covers (part closed by hand): shrunk to what is held, in whole lots.
        val covers = g.legQtys.firstOrNull() ?: abs(p.qty)
        var cur = p
        val last = lastPrice(p, stream = false)
        if (abs(net) < covers && last != null) {
            val n = (abs(net) / p.lotSize.coerceAtLeast(1)) * p.lotSize.coerceAtLeast(1)
            if (n > 0) {
                val (gtt, why) = Kite.protect(Kite.Spec(p.exchange, p.symbol, 0L, p.lotSize, p.tick), p.product, p.qty.sign() * n, last, p.stop, p.target)
                val r = runCatching { Broker.modifyGtt(gid, requireNotNull(gtt) { why.joinToString("; ") }) }
                if (r.isSuccess) { cur = p.copy(qty = p.qty.sign() * n); DayOco.note(DayGttTally.Event.SHRUNK) }
                else {
                    // Review: it cannot be reduced (the price within 0.25% of a leg: just when it may fire), so it would sell more
                    // than is held. Taken out first (confirmed), then the two DAY orders for what is held, which resize freely.
                    Alerts.post("${p.symbol}: its GTT covers more than is held ($net) and could not be reduced (${r.exceptionOrNull()?.message}); " +
                        "it is replaced by two orders for what is held.", Alerts.Kind.ERROR, "Protection", throttle = true)
                    return when (gttState(p, cancelFired = false)) {
                        GttState.HELD, GttState.FIRED -> p
                        GttState.GONE -> placeDayExits(p.copy(gttId = null, qty = p.qty.sign() * n), net, ordersNow = true)
                    }
                }
            }
        } else if (abs(cur.qty) != covers && covers in 1..abs(net)) cur = p.copy(qty = p.qty.sign() * covers)
        return trailDayGtt(cur, gid, last ?: return cur)
    }

    /** [p]'s trailing stop moved up (a long; down for a short) at [last]: the GTT modified in place, the target leg kept. */
    private suspend fun trailDayGtt(p: Item, gid: Long, last: Double): Item {
        if (p.trail == null) return p
        val n = Protection.next(p.spec(), last)
        val ns = n.stop ?: return p.copy(best = n.best)
        if (ns == p.stop || abs(ns - (p.stop ?: 0.0)) < p.tick - 1e-9) return p.copy(best = n.best)
        // Too near the price or the target for Zerodha (0.25%): the GTT stays where it is, tried again on a later look.
        val (gtt, _) = Kite.protect(Kite.Spec(p.exchange, p.symbol, 0L, p.lotSize, p.tick), p.product, p.qty, last, ns, p.target)
        if (gtt == null) return p.copy(best = n.best)
        if (runCatching { Broker.modifyGtt(gid, gtt) }.isFailure) return p.copy(best = n.best)
        DayOco.note(DayGttTally.Event.TRAILED)
        return p.copy(best = n.best, stop = ns)
    }

    /**
     * A day-GTT protection with nothing resting yet ([net] held). [DayOco] on (and not [ordersNow]): one GTT; Zerodha refusing it
     * (or nothing to send), the day's two orders at once in market hours; its answer lost (it may exist), nothing else this look -
     * looked for and tried again on the next, at most [GTT_TRIES] looks, then the two orders. Off: the two orders ([restAgain]) in
     * market hours. Out of hours with nothing placed: kept, and tried again on the next look.
     */
    private suspend fun placeDayExits(p: Item, net: Int, ordersNow: Boolean = false): Item {
        if (net == 0 || net.sign() != p.qty.sign()) return p.copy(active = false, note = "position closed")
        val cover = p.copy(qty = net.sign() * minOf(abs(net), abs(p.qty)))
        if (!ordersNow && dayOco(cover) && (gttTries[p.id] ?: 0) < GTT_TRIES) {
            when (val r = restDayGtt(cover, net)) {
                is DayGtt.Placed -> return r.item
                DayGtt.Unknown -> { gttTries[p.id] = (gttTries[p.id] ?: 0) + 1; return p.copy(qty = cover.qty) }
                DayGtt.Refused -> gttTries[p.id] = GTT_TRIES
            }
        }
        if (!marketOpen(p.exchange)) return p.copy(qty = cover.qty)
        val next = restAgain(cover.copy(note = ""), net, said = "its stop and target rest at Zerodha as two orders again")
            ?: run {
                unprotected("${p.symbol}: its stop could not be placed at Zerodha. The position is NOT protected: set the protection again.")
                return p.copy(active = false, note = "stop not placed")
            }
        // Not placed this look (the books not read, or closed meanwhile): kept as it is, asked again on the next look.
        if (next.active && next.stopOrderId == null && next.targetOrderId == null) return p
        if (next.stopOrderId != null || next.targetOrderId != null) DayOco.note(DayGttTally.Event.REPLACED)
        return next
    }

    /**
     * [p]'s stop and target placed again at their levels (an earlier day's DAY orders ended): the protection carried on under
     * a new id (today's: [cancelOrders] knows its orders are today's). Null when the stop could not be placed (the caller says so).
     */
    private suspend fun restAgain(p0: Item, net0: Int = p0.qty, said: String? = null): Item? =
        // 20 Oct, enhancement round 2: read and placed in the contract's one exit turn ([ExitCoordinator]), as [restOvernight].
        ExitCoordinator.turn(p0.exchange, p0.symbol, "protection") { restAgainInTurn(p0, net0, said) }

    /** [said]: what the owner is told once they are placed (21 Oct, round 4: the day GTT switched off); null: the next session's words. */
    private suspend fun restAgainInTurn(p00: Item, net0: Int, said: String? = null): Item? {
        val side = if (p00.qty > 0) Kite.Side.SELL else Kite.Side.BUY
        val known = listOfNotNull(p00.stopOrderId, p00.targetOrderId)
        // Second review: an exit already resting on it (Boss's own after-market order, another owner's) is subtracted first, so
        // the two together never sell more than is held. All of it covered: nothing placed, and said. 20 Oct, enhancement round 1:
        // an active GTT on it (an arm's own, or one of ours not recorded) counts too, against all that is held ([net]).
        var book: List<Broker.OrderRow> = emptyList()
        val busy = runCatching {
            book = ExitCoordinator.orders(p00.exchange, p00.symbol, "protection")
            book.filter { it.working && it.symbol == p00.symbol && it.exchange == p00.exchange && it.product == p00.product &&
                it.side == side.name && it.id !in known }.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) } +
                ExitCoordinator.gtts(p00.exchange, p00.symbol, "protection").filter { it.symbol == p00.symbol && it.exchange == p00.exchange }.sumOf { it.covers(side.name, p00.product) }
        }.getOrNull() ?: return p00                                       // the books not read: asked again next look
        // 20 Oct, enhancement round 2: what is held read again AFTER the books (an exit that filled since the caller's read shows
        // here, not as still held with nothing on its way out); never more than the caller saw.
        val again = heldAgain(p00, book) ?: return p00
        if (again == 0 || again.sign() != net0.sign()) return p00         // closed meanwhile: the next look ends it
        val net = if (abs(again) < abs(net0)) again else net0
        val p0 = if (abs(p00.qty) > abs(net)) p00.copy(qty = net.sign() * abs(net)) else p00
        val qty = com.optionslab.engine.risk.ExitQty.sendable(net, busy, abs(p0.qty), p0.lotSize)
        if (qty <= 0) {
            Alerts.post("${p0.symbol}: an exit already resting at Zerodha covers it, so its stop and target were not placed again.",
                Alerts.Kind.INFO, "Protection")
            return p0.copy(active = false, note = "covered by another exit")
        }
        val p = p0.copy(qty = p0.qty.sign() * qty)
        val stopId = p.stop?.let { s0 ->
            runCatching {
                placeTracked(Kite.Order(p.symbol, side, abs(p.qty), p.lotSize, p.product, "SL", stopLimit(s0, side, p.tick),
                    p.tick, p.exchange, "iraprotect", triggerPrice = s0), known)
            }.getOrElse { e ->
                // Usually the price opened through the stop (Zerodha refuses a stop on the wrong side of the price): said plainly.
                val last = runCatching { Broker.quotes(listOf("${p.exchange}:${p.symbol}"))["${p.exchange}:${p.symbol}"]?.last }.getOrNull()
                val through = last != null && last > 0 && (if (p.qty > 0) last <= s0 else last >= s0)
                unprotected(if (through) "${p.symbol} opened at %.2f, already through its stop of %.2f, so the stop could not be placed again. Close it in Trade.".format(last, s0)
                    else "${p.symbol}: its stop could not be placed again at the open (${e.message}). The position is NOT protected: set the protection again.")
                return p.copy(active = false, note = "stop not placed again")
            }
        }
        val targetId = p.target?.let { t ->
            runCatching {
                placeTracked(Kite.Order(p.symbol, side, abs(p.qty), p.lotSize, p.product, "LIMIT", Kite.onTick(t, p.tick, side),
                    p.tick, p.exchange, "iraprotect"), known + listOfNotNull(stopId))
            }.getOrNull()
        }
        if (stopId == null && targetId == null) return null
        stopId?.let { Strategies.tagOwner("kite:$it", "Protection · stop") }
        targetId?.let { Strategies.tagOwner("kite:$it", "Protection · target") }
        val next = p.copy(id = nowMs(), stopOrderId = stopId, targetOrderId = targetId,
            target = p.target.takeIf { targetId != null }, note = "placed again for today")
        Alerts.post(said?.let { "${p.symbol}: $it (${next.describe()})." }
            ?: "${p.symbol}: its stop and target ended with yesterday's session and were placed again at Zerodha (${next.describe()}).",
            Alerts.Kind.SUCCESS, "Protection")
        return next
    }

    /**
     * 20 Oct, enhancement round 2: [p]'s contract as Zerodha holds it now (net, this product); null when it could not be read.
     * 21 Oct, enhancement round 3 (idea 1): checked against the fills of [orders], the orders read just before it
     * ([ExitCoordinator.held]: an exit that filled a moment ago is never covered again while the positions catch up).
     */
    private suspend fun heldAgain(p: Item, orders: List<Broker.OrderRow>): Int? = runCatching {
        ExitCoordinator.held(p.exchange, p.symbol, p.product, "protection", orders)
    }.getOrNull()

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
    suspend fun tick(fast: Boolean = false, stream: Boolean = false) = lock.withLock {
        streamLook = fast && stream
        try { tickLocked(fast); WatchClock.stopsChecked() } finally { streamLook = false }
    }

    private suspend fun tickLocked(fast: Boolean) {
        val list = load()
        if (list.none { it.active }) return
        warnIfBlind(list)
        var changed = false
        // [fast]: an event-driven look ([FastPath]). A change of the best price seen alone (a trail not yet moved) stays in
        // memory and is written by the next regular look - never a Keystore write per tick; a stop moved, an exit filled or
        // cancelled is written now as before.
        var acted = false
        for ((i, p) in list.withIndex()) {
            if (!p.active) continue
            val next = runCatching { if (p.live) tickLive(p) else tickPaper(p) }.getOrNull() ?: continue
            if (next != p) { list[i] = next; changed = true; if (next.copy(best = p.best) != p) acted = true }
        }
        if (fast && changed && !acted) {
            trackedOnly = true
        } else if (changed || trackedOnly) {
            save(list)
            trackedOnly = false
        }
        // An overnight GTT refused (counted in memory, nothing saved) ends the watch's evening look for it ([overnightHint]).
        hint(list)
    }

    /** A fast look left a best-price change in memory only ([tick]): the next regular look writes it. */
    @Volatile private var trackedOnly = false

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

    /**
     * 20 Oct, enhancement round 1 (idea 2): [tickLiveOnce], and a protection never ends with its overnight GTT still at Zerodha
     * (left there, it would sell what is no longer held): it is deleted first and confirmed, or the protection stays listed as
     * being removed and is cancelled again on every pass until Zerodha confirms.
     */
    private suspend fun tickLive(p: Item): Item {
        // A fast look from the stream ([FastPath]) never reads or deletes a GTT: the overnight GTT exists only while the session's
        // orders are gone, and the watch's regular look (every 15 s while held) does that work.
        // 21 Oct, enhancement round 4 (idea 1): a day GTT ([DAY_GTT]) is trailed from the stream too (a modify, no GTT read).
        if (p.gttId != null && streamLook && p.note != DAY_GTT) return p
        val next = tickLiveOnce(p)
        if (next.active || next.gttId == null) return next
        return if (gttGone(next, cancelFired = true)) next.copy(gttId = null) else next.copy(active = true, note = REMOVING)
    }

    private suspend fun tickLiveOnce(p: Item): Item {
        if (!Broker.loggedIn) return p
        if (p.note == REMOVING) return if (cancelOrders(p)) p.copy(active = false, note = "removed") else p
        // 21 Oct, enhancement round 4 (idea 1): resting all day as one GTT: its own look.
        if (p.note == DAY_GTT) return tickDayGtt(p, streamLook)
        // A stream look (round 2): Zerodha's books as last read (re-read after any change), the price from the fresh tick.
        val stream = streamLook
        val rows = (if (stream) Broker.ordersRecon() else Broker.orders()).associateBy { it.id }
        val so = p.stopOrderId?.let { rows[it] }; val to = p.targetOrderId?.let { rows[it] }
        val stopDone = so?.status == "COMPLETE"; val targetDone = to?.status == "COMPLETE"
        suspend fun netNow(): Int? = runCatching {
            (if (stream) Broker.positionsRecon() else Broker.positionBook()).net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }.sumOf { it.qty }
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
        val net = if (nowMs() - p.id < 60_000) null else netNow()
        if (net != null && (net == 0 || net.sign() != p.qty.sign())) {
            listOfNotNull(so, to).filter { it.working }.forEach { runCatching { Broker.cancel(it.id, it.variety) } }
            val still = runCatching { Broker.orders().filter { it.id == p.stopOrderId || it.id == p.targetOrderId }.any { it.working } }.getOrElse { true }
            if (still) return p
            // 20 Oct, enhancement round 1: its overnight GTT out too (a fired one's exit still working cancelled), confirmed, or kept
            // and asked again next pass: left active, it would sell what is no longer held.
            if (p.gttId != null && !gttGone(p, cancelFired = true)) return p
            return p.copy(active = false, note = "position closed", gttId = null)
        }
        // A carried position's overnight GTT is never judged on a positions read that failed (or a protection's first minute):
        // kept as it is and asked again on the next pass.
        if (net == null && p.gttId != null) return p
        // 20 Oct, review: a carried (not intraday) position whose DAY exits Zerodha cancelled at the close: kept, and placed again
        // at the next session's open ([restAgain]) instead of ending here with nothing watching the position overnight.
        val carriedNow = p.product != "MIS" && net != null && net != 0 && net.sign() == p.qty.sign() && !marketOpen(p.exchange)
        if (carriedNow && listOfNotNull(so, to).all { it.status == "CANCELLED" || !it.working } && listOfNotNull(so, to).any { it.status == "CANCELLED" }) {
            val waiting = if (p.note == WAITING) p else p.copy(note = WAITING).also {
                Alerts.post("${p.symbol}: its stop and target ended with the session at Zerodha; they are placed again when the market opens.",
                    Alerts.Kind.INFO, "Protection", throttle = true)
            }
            // 20 Oct, enhancement round 1 (idea 2): meanwhile they rest at Zerodha as a GTT, so a dead phone misses nothing overnight.
            return restOvernight(waiting, net!!)
        }
        // Review: a carried position's positions read that failed outside its market's hours never ends the protection (it would
        // lose tonight's GTT and the open's orders): asked again next pass.
        if (net == null && p.product != "MIS" && !marketOpen(p.exchange)) return p
        // A stop refused or cancelled at Zerodha leaves the position unprotected: say so.
        if (so != null && so.status in setOf("REJECTED", "CANCELLED") && p.stopOrderId != null) {
            unprotected("${p.symbol}: the stop at Zerodha was ${so.status.lowercase()}${so.message.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}. The position is not protected by a stop.")
            if (to == null || !to.working) return p.copy(active = false, stopOrderId = null, note = "stop ${so.status.lowercase()}")
            return p.copy(stopOrderId = null, note = "stop ${so.status.lowercase()}")
        }
        // Both exits gone some other way (a DAY order that ended with its day, cancelled at Zerodha): say so.
        if ((so == null || !so.working) && (to == null || !to.working) && (p.stopOrderId != null || p.targetOrderId != null)) {
            // 20 Oct, review: a carried (NRML) position's stop and target are DAY orders and end with their day; the next session
            // marked the protection finished and nothing watched the position any more. Now, in market hours, they are placed
            // again at the same levels for what is still held (only ever adding a stop: it lowers risk). Refused: said, as before.
            val today0 = Market.today().atStartOfDay(com.optionslab.engine.IST).toInstant().toEpochMilli()
            if (net != null && net != 0 && net.sign() == p.qty.sign() && (p.id < today0 || p.note == WAITING)) {
                // Before the open: kept until then (Zerodha would refuse it now). 20 Oct, enhancement round 1: its stop rests as a
                // GTT meanwhile, when it was not placed at the close (the app was not running then).
                if (!marketOpen(p.exchange)) return restOvernight(p, net)
                // 21 Oct, enhancement round 4 (idea 1): [DayOco] on, an overnight GTT with both legs, active and covering no more than
                // is held, simply stays for the day (nothing deleted, nothing placed).
                if (p.gttId != null && dayOco(p)) keepDayGtt(p, net)?.let { return it }
                // At the open the GTT goes first, confirmed (or kept, and asked again next pass): the day's orders and a GTT never
                // rest together. One that fired overnight or at the open is an exit already working: [restAgain] counts it.
                if (p.gttId != null) when (gttState(p, cancelFired = false)) {
                    GttState.HELD -> return p
                    // Review: fired (perhaps filled) since this pass read what is held: nothing is placed on that older figure;
                    // the next pass reads the position again and counts the GTT's own sell.
                    GttState.FIRED -> return p.copy(gttId = null)
                    GttState.GONE -> Unit
                }
                // Never more than this protection covered: lots added since, or another owner's lots (an arm's trade with its own
                // GTT), are not this protection's to sell (second review).
                val again = p.copy(qty = net.sign() * minOf(abs(net), abs(p.qty)), gttId = null)
                // 21 Oct, enhancement round 4 (idea 1): [DayOco] on, as one GTT; not placed, the day's two orders as before.
                var refused = false
                if (dayOco(again)) when (val r = restDayGtt(again, net)) {
                    is DayGtt.Placed -> return r.item
                    // Review: its answer lost (it may exist): nothing else placed; the next look finds it or rests it.
                    DayGtt.Unknown -> return again.copy(stopOrderId = null, targetOrderId = null, note = DAY_GTT)
                    DayGtt.Refused -> refused = true
                }
                restAgain(again, net)?.let {
                    // 21 Oct, enhancement round 5 (idea 1): the two orders in the day GTT's place, counted for the diagnostics.
                    if (refused && (it.stopOrderId != null || it.targetOrderId != null)) DayOco.note(DayGttTally.Event.REPLACED)
                    return it
                }
            }
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
        val streamed = if (stream) runCatching { Broker.tokenOf(key)?.let { KiteStream.freshTick(it) }?.last?.takeIf { it > 0 } }.getOrNull() else null
        // 18 Oct: the fallback is Zerodha's quote unless a tick is 2 s fresh ([Broker.stopQuotes]), never a tick up to 5 s old.
        val ltp = streamed ?: Broker.stopQuotes(listOf(key))[key]?.last ?: return cur
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
     *
     * [tightenOnly] (G1-4; the default, for the automatic callers such as Jarvis's AutoTrail): the stop only ever moves
     * toward the price - up for a long, down for a short - judged here, under the lock, against the stop as it stands now
     * (and the resting order's own trigger), never against the caller's older snapshot. A move that would loosen it is refused.
     */
    suspend fun moveStop(id: Long, newStop: Double, tightenOnly: Boolean = true): String? = moveStopAt(id, newStop, tightenOnly).why

    /**
     * A stop move's outcome: [why] null when moved, else why not; [standing] the stop as it stands after the call - the new
     * one when moved; when refused, the one that stands now (the tighter stop that refused an automatic move: the
     * protection's own, Zerodha's resting trigger or the paper order's), never the caller's older snapshot (round 4).
     */
    data class StopMove(val why: String?, val standing: Double?)

    /** [moveStop], saying the stop that stands afterwards ([StopMove.standing]). The same rules and the same moves. */
    suspend fun moveStopAt(id: Long, newStop: Double, tightenOnly: Boolean = true): StopMove = lock.withLock {
        val list = load()
        val i = list.indexOfFirst { it.id == id && it.active }
        if (i < 0) return@withLock StopMove("That protection is no longer active.", null)
        val p = list[i]
        fun no(why: String, standing: Double? = p.stop) = StopMove(why, standing)
        if (p.note == REMOVING) return@withLock no("That protection is being removed.")
        val tick = if (p.tick > 0) p.tick else 0.05
        // On the tick, rounded away from the price (down for a long's stop, up for a short's), never toward it.
        val ns = Protection.onTick(newStop, tick, down = p.qty > 0)
        fun looser(than: Double?) = than != null && than > 0 && (if (p.qty > 0) ns <= than + 1e-9 else ns >= than - 1e-9)
        if (tightenOnly && looser(p.stop))
            return@withLock no("The stop is already at ${"%.2f".format(java.util.Locale.ENGLISH, p.stop)}; an automatic move only tightens it.")
        // 21 Oct, enhancement round 4 (idea 1): resting as one GTT - the GTT's stop leg moved in place (its target leg kept).
        if (p.live && p.note == DAY_GTT) {
            val gid = p.gttId ?: return@withLock no("Its stop and target are being placed at Zerodha; try again in a moment.")
            if (!Broker.loggedIn) return@withLock no("Not logged in to Zerodha.")
            val g = runCatching { Broker.gtts().firstOrNull { it.id == gid } }.getOrNull() ?: return@withLock no("The GTT was not found at Zerodha.")
            if (!g.active) return@withLock no("The GTT is no longer active at Zerodha.")
            val resting = if (p.qty > 0) g.triggers.minOrNull() else g.triggers.maxOrNull()
            if (tightenOnly && looser(resting))
                return@withLock no("The stop at Zerodha is already at ${"%.2f".format(java.util.Locale.ENGLISH, resting)}; an automatic move only tightens it.", resting)
            val last = lastPrice(p, stream = false) ?: return@withLock no("Zerodha's price could not be read.", resting ?: p.stop)
            val (gtt, why) = Kite.protect(Kite.Spec(p.exchange, p.symbol, 0L, p.lotSize, tick), p.product, p.qty.sign() * (g.legQtys.firstOrNull() ?: abs(p.qty)),
                last, ns, p.target)
            if (gtt == null) return@withLock no("Zerodha would refuse that GTT: ${why.joinToString("; ")}.", resting ?: p.stop)
            val r = runCatching { Broker.modifyGtt(gid, gtt) }
            if (r.isFailure) return@withLock no("Zerodha did not move it: ${r.exceptionOrNull()?.message ?: "an error"}.", resting ?: p.stop)
            list[i] = p.copy(stop = ns)
            save(list)
            return@withLock StopMove(null, ns)
        }
        val orderId = p.stopOrderId ?: return@withLock no("It has no resting stop order to move.")
        if (p.live) {
            if (!Broker.loggedIn) return@withLock no("Not logged in to Zerodha.")
            val so = runCatching { Broker.orders().firstOrNull { it.id == orderId } }.getOrNull()
                ?: return@withLock no("The stop order was not found at Zerodha.")
            if (!so.working) return@withLock no("The stop order is no longer working at Zerodha.")
            if (tightenOnly && looser(so.trigger))
                return@withLock no("The stop at Zerodha is already at ${"%.2f".format(java.util.Locale.ENGLISH, so.trigger)}; an automatic move only tightens it.", so.trigger)
            val side = if (p.qty > 0) Kite.Side.SELL else Kite.Side.BUY
            val r = runCatching {
                if (so.type == "SL-M") Broker.modify(so, so.qty, "SL-M", null, ns)
                else Broker.modify(so, so.qty, "SL", stopLimit(ns, side, tick), ns)
            }
            if (r.isFailure) return@withLock no("Zerodha did not move it: ${r.exceptionOrNull()?.message ?: "an error"}.", so.trigger.takeIf { it > 0 } ?: p.stop)
        } else {
            val resting = Paper.stateIfLoaded()?.orders?.firstOrNull { it.orderId == orderId }?.triggerPrice?.toDouble()
            if (tightenOnly && looser(resting))
                return@withLock no("The paper stop is already at ${"%.2f".format(java.util.Locale.ENGLISH, resting)}; an automatic move only tightens it.", resting)
            val r = runCatching { Paper.modify(orderId, null, null, ns) }.getOrNull()
            if (r == null || !r.ok) return@withLock no("The paper stop was not moved${r?.message?.let { ": $it" } ?: ""}.", resting ?: p.stop)
        }
        list[i] = p.copy(stop = ns)
        save(list)
        StopMove(null, ns)
    }

    private fun Int.sign() = if (this > 0) 1 else if (this < 0) -1 else 0

    /** Reset paper: every paper stop / target / trail is dropped; Zerodha's are untouched. */
    suspend fun resetPaper(): Unit = lock.withLock { save(load().filter { it.live }) }

    fun wipe() { cache = null; activeHint = null; liveKeysHint = emptyList(); overnightHint = false; mcxOvernightHint = false; dayGttHint = emptyMap(); gttTries.clear(); if (::file.isInitialized) file.delete() }

    /** TEST ONLY: memory forgotten (the file kept), as after a restart: the next look reads the file. */
    internal fun forgetMemoryForTest() { cache = null; gttTries.clear() }
}
