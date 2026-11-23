package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Alerts
import com.optionslab.engine.Kite
import com.optionslab.engine.risk.Protection
import com.optionslab.ira.DayGttTally
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        /**
         * 7 Nov: set by "Stop and target on my manual positions" ([ManualCover]) on a position Boss bought by hand. Only such a
         * protection is ever taken off by it (when the position becomes part of a spread or a hedge). False for every other one.
         */
        val auto: Boolean = false,
        /**
         * 7 Nov: when it ended (epoch ms, the market's clock), stamped by [save] the first time it is saved finished. An item saved
         * before this field: its [id] (when it was set).
         */
        val endedAt: Long? = null,
        /**
         * 22 Nov 2026 (B6 review, M5: Zerodha takes no second full sale beside a resting stop - it reads it as a new short and refuses it for
         * funds - so an intraday target was never placed, saved as none, and nothing watched it): the [target] is the app's own, with no order
         * at Zerodha: only the stop rests there, and when the price reaches the target the app takes the stop out and sells ([sellAtTarget]).
         * Always so for an intraday (MIS) stop and target; a carried one only when Zerodha refused its target that way. Saved.
         */
        val appTarget: Boolean = false,
        /**
         * 23 Nov 2026 (B6 review, eighth round, m4): the protection's whole size (signed as [qty]) while its stop covers only [qty] - put back
         * beside Boss's own exit whose reply was lost, for what that exit leaves. Grown to it once that exit is known ([growStop]); a later
         * put-back puts it back for the whole. Null: [qty] is the whole. Saved.
         */
        val whole: Int? = null,
    ) {
        val direction: Int get() = if (qty > 0) 1 else -1
        val exitSide: String get() = if (qty > 0) "SELL" else "BUY"
        fun spec() = Protection.Spec(direction, stop, trail, target, best, tick)
        fun describe(): String = listOfNotNull(
            stop?.let { (if (trail != null) "trailing stop %.2f (%.2f behind)".format(it, trail) else "stop %.2f".format(it)) },
            target?.let { "target %.2f".format(it) + if (appTarget) " (watched by the app)" else "" },
        ).joinToString(" · ")
    }

    private lateinit var file: File
    private lateinit var app: Context
    private val lock = Mutex()
    private var cache: MutableList<Item>? = null
    /**
     * 26 Oct (Erase everything): bumped by [wipe], which cannot take [lock] (a coroutine lock). [loadGen] is the count when the
     * list in hand was read; a look that read it before an erase neither saves it nor publishes its hints afterwards, so the
     * old stops and targets never come back in memory or on disk.
     */
    @Volatile private var wipeGen = 0
    @Volatile private var loadGen = 0

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

    /**
     * 23 Nov 2026 (B6 review, sixth round: Kite's unsure answer to a stop - [Broker.KiteUnsure] - or a lost reply not found in the book, was
     * taken as "not placed": the protection ended, and setting it again could rest a second stop beside one that did reach Zerodha - two
     * stops on one holding, which could sell twice): its stop MAY be resting. The order is looked for in the background ([Broker.noteLost]
     * under [lostStopKey]); found, it is the stop; no other stop is placed on the holding meanwhile; placed again only once a read of the
     * book that succeeded [Broker.LOST_SEARCH_MS] after it went still does not show it ([tickLostStop]). Saved with the item, so a restart
     * looks for it again from when it went.
     */
    internal const val STOP_LOST = "its stop may be resting at Zerodha (its answer was unsure): looked for"
    /**
     * 23 Nov 2026 (B6 review, eighth round, m3: a lost stop given up on whose put-back did not go this look was noted lost again, and every two
     * minutes Boss had the same red alert): given up on - not at Zerodha - and waiting to be placed again ([placeGivenUp]); never looked for again.
     */
    internal const val STOP_GAVE_UP = "its unsure stop was not at Zerodha: placed again on the next look"
    /** 23 Nov 2026 (B6 review, eighth round, m4): a stop put back for only part beside Boss's own exit whose reply was lost ([growStop]). */
    internal const val BOSS_CUT = "its stop covers only what your exit whose reply was lost leaves: grown once that is known"
    private const val LOST_STOP = "protstop:"
    private fun lostStopKey(p: Item) = LOST_STOP + p.id

    /** 23 Nov 2026 (B6 review, sixth round): an order whose fate Zerodha left unknown ([placeTracked]) - it may be resting there. */
    private class MaybeResting(val order: Kite.Order, msg: String) : java.io.IOException(msg)

    /** 23 Nov 2026 (B6 review, sixth round): Boss's words for a stop that may be resting ([STOP_LOST]). */
    private fun maybeRestingWords(symbol: String) = "$symbol: Zerodha's answer to its stop was unsure, so the stop may be resting at Zerodha. " +
        "The app looks for it (about 2 minutes at most) and places no other stop beside it meanwhile: found, it is the stop; not there 2 " +
        "minutes on, it is placed again. Check Kite's order book."

    /** 23 Nov 2026 (B6 review, sixth round): an active live protection on [symbol] whose stop may be resting ([STOP_LOST]), [exceptId] left out. */
    private fun stopMaybeResting(items: List<Item>?, exchange: String, symbol: String, exceptId: Long? = null): Item? =
        items?.firstOrNull { it.active && it.live && maybeResting(it) && it.id != exceptId && it.symbol == symbol && it.exchange == exchange }

    /**
     * 23 Nov 2026 (B6 review, eighth round): [p]'s stop may be resting at Zerodha - its answer was unsure ([STOP_LOST]), or it is being removed
     * with that still so (being removed with no order at all: only such a one is kept removing, [lostStopGone]) - so no sibling's stop goes in
     * beside it. Memory only.
     */
    private fun maybeResting(p: Item): Boolean = p.note == STOP_LOST ||
        (p.note == REMOVING && p.stopOrderId == null && p.targetOrderId == null && p.gttId == null && p.stop != null)
    private val GONE = setOf("CANCELLED", "REJECTED", "COMPLETE")

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.noBackupFilesDir, "protections.vault")
        marksFile = File(app.noBackupFilesDir, MARKS_FILE)
        // 22 Nov 2026 (B6 review): the square-offs count the app's own target sales as going ([targetSalesGoing]) and take no stop out beside one.
        SquareOffExit.lostPendingProviders["protections"] = { sym, product -> targetSalesGoing(sym, product) }
        // (23 Nov 2026, B6 review, fourth round: Boss's own exit counts a target sale Zerodha took through its lag guard - not twice.)
        SquareOffExit.bossPendingProviders["protections"] = { sym, product -> targetSalesGoing(sym, product, booked = false) }
        startUnknownUntil = System.currentTimeMillis() + Broker.LOST_SEARCH_MS
    }

    private fun load(): MutableList<Item> {
        cache?.let { return it }
        val gen = wipeGen
        val out = ArrayList<Item>()
        val read = runCatching {
            val a = JSONArray(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                fun d(k: String) = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
                fun s(k: String) = o.optString(k).ifEmpty { null }
                val active = o.optBoolean("active", true)
                out += Item(o.getLong("id"), o.getBoolean("live"), o.getString("symbol"), o.getString("exchange"), o.getString("product"),
                    o.getInt("qty"), o.getInt("lot"), o.getDouble("tick"), d("stop"), d("trail"), d("target"), o.getDouble("best"),
                    s("stopId"), s("targetId"), active, o.optString("note"), o.optLong("gtt", 0L).takeIf { it > 0L },
                    // 7 Nov: both new (an older file has neither): not automatic; a finished one ended no earlier than it was set.
                    auto = o.optBoolean("auto", false),
                    endedAt = o.optLong("endedAt", 0L).takeIf { it > 0L } ?: if (active) null else o.getLong("id"),
                    // 22 Nov 2026 (B6 review): new (an older file has none): the target an order at Zerodha, as before.
                    appTarget = o.optBoolean("appTarget", false),
                    // (23 Nov 2026, B6 review, eighth round: new - an older file has none.)
                    whole = o.optInt("whole", 0).takeIf { it != 0 })
            }
        }
        // 26 Oct: a read refused because everything is being erased is not a damaged file: never set aside (the copy would
        // outlive the erase). The list in hand is then empty and never saved ([wipeGen]).
        if (read.isFailure && file.exists() && read.exceptionOrNull() !is Vault.Erasing) {
            // Never overwrite it: kept aside, and the owner told that exits may still rest at the broker.
            Vault.setAside(file)
            Alerts.error("The saved stops and targets could not be read and were set aside. Their orders may still be resting: check the order book.", "Protection")
        }
        loadGen = gen
        // 22 Nov 2026 (B6 review, M6): the taken-over marks kept on disk, read with the list (this worker, never the tick path).
        if (gen == wipeGen) synchronized(marksMon) { loadMarksLocked() }
        // 15 Nov, review: a new id is never one already on the list (a clock moved back after a restart included).
        // Review (R2): never an id from a clock that was once ahead (more than [ID_SLACK_MS] past now): it would carry on to every new one.
        val seedMax = nowMs() + ID_SLACK_MS
        out.filter { it.id <= seedMax }.maxOfOrNull { it.id }?.let { top -> synchronized(this) { if (top > lastId) lastId = top } }
        if (gen == wipeGen) { cache = out; hint(out) }
        return out
    }

    /**
     * Whether a protection is active, as of the last load or save (null: not loaded yet in this process): read without the
     * lock by the event-driven checks ([FastPath]), which then run [tick] between the watch's passes.
     */
    @Volatile var activeHint: Boolean? = null
        private set

    /** 7 Nov: an active live protection set by [ManualCover] (memory, as [activeHint]): its look runs while switched off only then. */
    @Volatile var autoHint: Boolean = false
        private set

    /** "EXCHANGE:SYMBOL" of the active live protections, as [activeHint] (round 2: followed on the stream, trailed from it). */
    @Volatile var liveKeysHint: List<String> = emptyList()
        private set

    private fun hint(list: List<Item>) {
        // 26 Oct: never the hints of a list read before an erase.
        if (loadGen != wipeGen) return
        published = list.toList()
        activeHint = list.any { it.active }
        autoHint = list.any { it.active && it.live && it.auto }
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
        // 7 Nov: every protection saved finished for the first time is stamped with when it ended (all the ways one ends go through
        // here), in the list in hand too, so the next save keeps the same time.
        val ended = nowMs()
        if (list is MutableList<Item>) runCatching { list.replaceAll { if (!it.active && it.endedAt == null) it.copy(endedAt = ended) else it } }
        val stamped = list.map { if (!it.active && it.endedAt == null) it.copy(endedAt = ended) else it }
        val a = JSONArray()
        // Finished protections are kept a while for the record, then dropped. 7 Nov: one that ended in the last week other than with its
        // position (Boss removed it, its stop was cancelled) is kept beyond the 50 ([ManualCover] respects it on a carried position).
        val weekAgo = ended - com.optionslab.ira.ManualCoverRule.CARRIED_DAYS * 86_400_000L
        val finished = stamped.filter { !it.active }
        val last50 = finished.takeLast(50).toSet()
        (stamped.filter { it.active } + finished.filter { it in last50 ||
            ((it.endedAt ?: it.id) >= weekAgo && it.note !in com.optionslab.ira.ManualCoverRule.NATURAL_ENDS) }).forEach { p ->
            a.put(JSONObject().put("id", p.id).put("live", p.live).put("symbol", p.symbol).put("exchange", p.exchange).put("product", p.product)
                .put("qty", p.qty).put("lot", p.lotSize).put("tick", p.tick).put("stop", p.stop ?: JSONObject.NULL).put("trail", p.trail ?: JSONObject.NULL)
                .put("target", p.target ?: JSONObject.NULL).put("best", p.best).put("stopId", p.stopOrderId ?: "").put("targetId", p.targetOrderId ?: "")
                .put("active", p.active).put("note", p.note).put("gtt", p.gttId ?: 0L).put("auto", p.auto).put("endedAt", p.endedAt ?: 0L)
                .apply { if (p.appTarget) put("appTarget", true); p.whole?.let { put("whole", it) } })
        }
        // 26 Oct: a list read before an erase is never written back over it.
        if (loadGen != wipeGen) return
        Vault.writeFile(file, a.toString().toByteArray(Charsets.UTF_8))
        cache = stamped.toMutableList()
        hint(stamped)
    }

    /**
     * 20 Nov 2026 (audit: one lock for everything - a look reading Zerodha held up every reader): the list as of the last load, save or
     * look, published whole (never the list a look is changing). The readers below read it without [lock]; only before the first load
     * do they take it (to read the file).
     */
    @Volatile private var published: List<Item>? = null

    private suspend fun snapshot(): List<Item> = published ?: lock.withLock { published ?: load().toList() }

    suspend fun active(): List<Item> = snapshot().filter { it.active }

    /** Every protection set at or after [fromMs], active or not (the order review's read after the close, 12 Oct). */
    suspend fun since(fromMs: Long): List<Item> = snapshot().filter { it.id >= fromMs }
    /** 7 Nov ([ManualCover]): every finished protection that ended at or after [fromMs] ([Item.endedAt]; an older one: when set). */
    suspend fun endedSince(fromMs: Long): List<Item> = snapshot().filter { !it.active && (it.endedAt ?: it.id) >= fromMs }
    suspend fun forSymbol(live: Boolean, symbol: String): Item? = snapshot().lastOrNull { it.active && it.live == live && it.symbol == symbol }

    /**
     * 20 Nov 2026 (audit: a manual Protect as a GTT did not read the position again before placing): null when Zerodha, read now,
     * still holds what [g] would close - its legs' side and product, at least its quantity (a CNC stock from the holdings); else why
     * it is not placed. A read that fails: not placed (Boss tries again). Off the main thread (the caller's IO lane).
     */
    suspend fun gttStillFits(g: Kite.Gtt): String? {
        val leg = g.orders.firstOrNull() ?: return "the GTT has no order in it."
        val unread = "Zerodha's positions could not be read just now, so the position could not be checked. Try again."
        val book = runCatching { Broker.within(5_000) { Broker.positionBook() } }.getOrNull()?.takeIf { !it.unknown(g.tradingSymbol) } ?: return unread
        var held = book.net.filter { it.symbol == g.tradingSymbol && it.exchange == g.exchange && it.product == leg.product }.sumOf { it.qty }
        if (leg.product == "CNC") held += (runCatching { Broker.within(5_000) { Broker.holdings() } }.getOrNull() ?: return unread)
            .filter { it.symbol == g.tradingSymbol && it.exchange == g.exchange }.sumOf { it.qty + it.t1 }
        val closes = if (leg.side == Kite.Side.SELL) held > 0 else held < 0
        if (!closes) return "Zerodha shows ${g.tradingSymbol} ${leg.product} net $held now, so this GTT would not close a position. Nothing was placed."
        if (leg.quantity > abs(held)) return "Zerodha holds ${abs(held)} of ${g.tradingSymbol} now, less than the GTT's ${leg.quantity}. " +
            "Nothing was placed: prepare it again."
        return null
    }

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
            val item = Item(newId(), false, symbol, c.exchange, product, qty, c.lotSize, tick, s0, trail, target, price, stopId, targetId)
            list += item; save(list)
            "Protected ${symbol}: ${item.describe()}"
        }
    }

    /**
     * Protect a Zerodha position. Call only after the owner's PIN or fingerprint.
     * The stop goes to Zerodha as an SL order with a limit, the target as a LIMIT order.
     *
     * 7 Nov ([ManualCover]): [auto] marks a protection the app set by itself on a position bought by hand. [precheck] runs inside
     * this object's lock before anything is cancelled or placed: null goes on, a reason returns "Not protected: <reason>" with
     * nothing placed (it takes the contract's exit turn itself, in the same order as the watch: this lock, then the turn).
     */
    suspend fun protectLive(symbol: String, exchange: String, product: String, qty: Int, price: Double,
                            stop: Double?, trail: Double?, target: Double?, auto: Boolean = false,
                            precheck: (suspend () -> String?)? = null, recheck: Boolean = false): String {
        val s = AppSettings.load()
        if (!s.live || !s.allowRealOrders) return "Switch to Live with the badge at the top first."
        // 20 Nov 2026 (audit: a manual Protect did not read the position again before placing): Boss's own Protect from a position
        // card, or Jarvis's stop on a held position Boss confirmed ([recheck] true), reads Zerodha's positions now. Not read: nothing placed (try again). Not held, or held the other way round: nothing placed.
        // Less held than the screen showed (part sold since): only what is held is protected, and said - a stop for more than is
        // held would sell into a position the other way round. ([ManualCover]'s own protections read it themselves, just before.)
        // Review: every other caller ([recheck] false: the stop of an entry whose fill was confirmed a moment ago - a bracket, a news
        // trade - or an automatic re-set of a stop already resting) is NOT checked so: Zerodha's positions can trail a fresh fill, and a
        // refusal then would leave the new position with no stop at all. Its quantity is the caller's, as before.
        val book = if (target == null && (auto || !recheck)) null else runCatching { Broker.within(5_000) { Broker.positionBook() } }.getOrNull()
        var qty = qty
        var resized = ""
        if (!auto && recheck) {
            val b = book?.takeIf { !it.unknown(symbol) } ?: return "Not protected: Zerodha's positions could not be read just now, so the position could not be checked. Try again."
            val held = b.net.filter { it.symbol == symbol && it.exchange == exchange && it.product == product }.sumOf { it.qty }
            if (held == 0 || held.sign() != qty.sign()) return "Not protected: Zerodha shows no ${if (qty > 0) "long" else "short"} $symbol $product " +
                "position now (net $held). Nothing was placed."
            if (abs(held) < abs(qty)) {
                resized = " (Zerodha holds ${abs(held)} now, not ${abs(qty)}, so only ${abs(held)} is protected.)"
                qty = held
            }
        }
        // The position's own price, read only when a target is asked (12 Oct): never a long's target under the price paid.
        val entry = if (target == null) null else book?.net?.firstOrNull { it.symbol == symbol && it.exchange == exchange && it.qty != 0 }?.avg
        Protection.validate(if (qty > 0) 1 else -1, price, stop, trail, target, entry)?.let { return "Not protected: $it" }
        if (target == null && Protection.initialStop(if (qty > 0) 1 else -1, price, stop, trail) == null)
            return "Not protected: that stop would be at or under zero, so nothing was changed."
        val spec = runCatching { Broker.spec(exchange, symbol) }.getOrNull() ?: return "Could not read $symbol's lot and tick from Zerodha."
        return lock.withLock {
            precheck?.let { check -> runCatching { check() }.getOrElse { "${com.optionslab.ira.ManualCoverRule.CHECKED_AGAIN}: it failed (${it.message})" }?.let { why ->
                return@withLock "Not protected: $why" } }
            val list = load()
            // (23 Nov 2026, B6 review, sixth round: a stop placed a moment ago whose answer was unsure may be resting - no other beside it.)
            stopMaybeResting(list, exchange, symbol)?.let {
                return@withLock "Not protected: the stop placed on $symbol a moment ago may be resting at Zerodha (its answer was unsure). The app " +
                    "looks for it - about 2 minutes at most - and places no other stop beside it meanwhile; check Kite's order book, or set it again then."
            }
            // (23 Nov 2026, B6 review, eighth round: Boss's own exit on it whose reply was lost may be selling it - no new protection beside it.)
            if (SquareOffExit.bossLostQty(symbol, product, if (qty > 0) Kite.Side.SELL else Kite.Side.BUY, exchange) > 0)
                return@withLock "Not protected: your exit on $symbol may still be selling it (Zerodha's reply to it was lost). The app looks for it - " +
                    "about 2 minutes at most - and places no new stop beside it meanwhile; set it again then."
            // The old exits must be confirmed gone first, or the old and the new could both fill.
            val old = list.filter { it.active && it.live && it.symbol == symbol }
            if (!old.map { cancelOrders(it) }.all { it }) return@withLock "Not protected: the previous stop or target on $symbol could not be confirmed cancelled at Zerodha; try again in a moment."
            list.replaceAll { if (it.active && it.live && it.symbol == symbol) it.copy(active = false, note = "replaced") else it }
            if (old.isNotEmpty()) save(list)
            val known = old.flatMap { listOfNotNull(it.stopOrderId, it.targetOrderId) }
            val dir = if (qty > 0) 1 else -1
            val s0 = Protection.initialStop(dir, price, stop, trail, spec.tickSize)
            val side = if (qty > 0) Kite.Side.SELL else Kite.Side.BUY
            // 15 Nov (Boss's diagnostics: a large manual position sat with no stop - its one stop order was above the exchange's
            // freeze quantity, e.g. more than 1,800 NIFTY, and the exchange refuses such an order): above the freeze quantity the
            // stop and target rest in slices, one protection per slice ([protectSliced]). At or under it: exactly as before.
            val freeze = Kite.freezeQuantity(exchange, symbol)
            val sizes = Kite.slices(abs(qty), spec.lotSize, freeze)
            if (sizes.size > 1) return@withLock protectSliced(list, symbol, exchange, product, qty, price, s0, trail, target,
                spec.lotSize, spec.tickSize, known, auto, sizes, freeze ?: abs(qty)).let { if (it.startsWith("Protected")) it + resized else it }
            var stopId: String? = null; var targetId: String? = null
            var partly = ""
            var replaced = false
            // 21 Oct, enhancement round 4 (idea 1): a carried position's stop and target as ONE two-leg GTT ([DayOco] on): whichever
            // fires, Zerodha cancels the other, even with the phone off. Not placed (refused, not yet held, the books not read): the
            // two DAY orders as before, said.
            val fresh = Item(newId(), true, symbol, exchange, product, qty, spec.lotSize, spec.tickSize, s0, trail, target, price, null, null, auto = auto)
            if (dayOco(fresh)) when (val r = restDayGtt(fresh, qty)) {
                is DayGtt.Placed -> {
                    list += r.item; save(list)
                    return@withLock "Protected $symbol at Zerodha as one GTT (whichever fires, Zerodha cancels the other): ${r.item.describe()}$resized"
                }
                // Review: Zerodha's answer lost - the GTT may exist. Nothing else is placed beside it: the watch's next look finds it
                // (and keeps it) or rests it then.
                DayGtt.Unknown, DayGtt.Wait -> {
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
            } catch (e: MaybeResting) {
                // (Sixth round: it may be resting - kept, looked for, never one placed beside it; its target the app's once it is found.)
                val item = Item(newId(), true, symbol, exchange, product, qty, spec.lotSize, spec.tickSize, s0, trail, target, price, null, null,
                    note = STOP_LOST, auto = auto, appTarget = target != null)
                list += item; save(list)
                Broker.noteLost(lostStopKey(item), e.order, known, "$symbol: its protection's stop", liveOnly = true)
                return@withLock maybeRestingWords(symbol)
            } catch (e: Exception) { return@withLock "Not protected: ${e.message}" }
            // 22 Nov 2026 (B6 review, M5): intraday, a stop and a target are never both at Zerodha (it refuses the second full sale for funds):
            // only the stop rests there; the target is the app's ([Item.appTarget], [sellAtTarget]). A target alone rests as before.
            var appTarget = product == "MIS" && stopId != null && target != null
            if (target != null && !appTarget) targetId = try {
                placeTracked(Kite.Order(symbol, side, abs(qty), spec.lotSize, product, "LIMIT",
                    Kite.onTick(target, spec.tickSize, side), spec.tickSize, exchange, "iraprotect"), known + listOfNotNull(stopId))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (stopId == null) return@withLock "Not protected: ${e.message}"
                // The stop is at Zerodha: it is kept and watched rather than cancelled, and the owner told. (22 Nov 2026: refused for funds -
                // the same refusal as intraday - the target is the app's instead.)
                if (CheaperStrike.fundsRefused(e.message)) appTarget = true
                else partly = " The target was not placed (${e.message}); only the stop rests at Zerodha."
                null
            }
            stopId?.let { Strategies.tagOwner("kite:$it", "Protection · stop") }
            targetId?.let { Strategies.tagOwner("kite:$it", "Protection · target") }
            // 21 Oct, enhancement round 5 (idea 1): the two orders in the day GTT's place, counted for the diagnostics.
            if (replaced && (stopId != null || targetId != null)) DayOco.note(DayGttTally.Event.REPLACED)
            val item = Item(newId(), true, symbol, exchange, product, qty, spec.lotSize, spec.tickSize, s0, trail,
                target.takeIf { targetId != null || appTarget }, price, stopId, targetId, auto = auto, appTarget = appTarget)
            list += item; save(list)
            // 20 Nov 2026 (audit): Boss is told how the target is kept. 22 Nov 2026 (B6 review): the app watches it (a dead phone cannot sell there).
            val watched = if (appTarget) " $TARGET_BY_APP" else ""
            "Protected $symbol at Zerodha: ${item.describe()}$partly$resized$watched"
        }
    }

    /**
     * 15 Nov: [protectLive] for a quantity above the exchange's freeze quantity ([freeze]): the stop (and target) rest as one order
     * per slice of [sizes] (whole lots, each at most [freeze]), and each slice is its own protection (its own stop and target, one
     * cancelling the other), so everything that watches, trails, shrinks, re-rests or removes one works on each slice as it always
     * did. The slices of one position are told apart from any other protection by [siblings]: their quantities add up to at most
     * what is held, and the orders of one never count as "another exit" against another ([othersBusy]).
     *
     * Each slice is saved as soon as its orders rest, so a slice already resting is never forgotten (a cancel or an error in a later
     * slice included). A slice whose stop is refused ends the slicing there: the slices already resting stay (some protection is
     * better than none), and the part left uncovered is said loudly, as an unprotected position is. Nothing rests beyond [qty].
     * Carried (NRML) slices rest as DAY orders today, even with "one GTT" (DayOco) on: each slice takes its own GTT at the next open.
     */
    private suspend fun protectSliced(list: MutableList<Item>, symbol: String, exchange: String, product: String, qty: Int, price: Double,
                                      s0: Double?, trail: Double?, target: Double?, lot: Int, tick: Double, known: List<String>,
                                      auto: Boolean, sizes: List<Int>, freeze: Int): String {
        val side = if (qty > 0) Kite.Side.SELL else Kite.Side.BUY
        // Every order this sends is added here, so a lost reply on a later slice never adopts an earlier slice's order (same size).
        val sent = ArrayList<String>(known)
        val total = abs(qty)
        var covered = 0
        var rested = 0
        var why: String? = null
        // (23 Nov 2026, B6 review, seventh round: the slice whose stop may be resting - [STOP_LOST] - never said as "not protected".)
        var lostQ = 0
        var targetMissed: String? = null
        var one: Item? = null
        // 22 Nov 2026 (B6 review, M5): intraday, each slice rests only its stop; its target is the app's ([Item.appTarget], as one order's).
        var anyAppTarget = false
        for (q in sizes) {
            var stopId: String? = null
            var targetId: String? = null
            if (s0 != null) {
                try {
                    stopId = placeTracked(Kite.Order(symbol, side, q, lot, product, "SL", stopLimit(s0, side, tick), tick, exchange,
                        "iraprotect", triggerPrice = s0), sent)
                } catch (e: MaybeResting) {
                    // (Sixth round: this slice's stop may be resting - kept and looked for, nothing more placed beside it.)
                    val item = Item(newId(), true, symbol, exchange, product, qty.sign() * q, lot, tick, s0, trail, target, price, null, null,
                        note = STOP_LOST, auto = auto, appTarget = target != null)
                    list += item; save(list)
                    Broker.noteLost(lostStopKey(item), e.order, sent, "$symbol: its protection's stop", liveOnly = true)
                    lostQ = q
                    why = "Zerodha's answer to one slice's stop was unsure: it may be resting, and the app looks for it"
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    why = e.message ?: "an error"
                }
                if (stopId == null) break
                sent += stopId
            }
            var appTarget = product == "MIS" && stopId != null && target != null
            if (target != null && !appTarget) {
                try {
                    targetId = placeTracked(Kite.Order(symbol, side, q, lot, product, "LIMIT", Kite.onTick(target, tick, side), tick, exchange,
                        "iraprotect"), sent)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // The stop rests: kept and watched, the target's refusal said (as for one order). No stop either: the end. (22 Nov 2026:
                    // refused for funds beside the stop - the target is the app's instead.)
                    if (stopId == null) why = e.message ?: "an error"
                    else if (CheaperStrike.fundsRefused(e.message)) appTarget = true
                    else targetMissed = e.message ?: "an error"
                }
                targetId?.let { sent += it }
            }
            if (stopId == null && targetId == null) break
            stopId?.let { Strategies.tagOwner("kite:$it", "Protection · stop") }
            targetId?.let { Strategies.tagOwner("kite:$it", "Protection · target") }
            if (appTarget) anyAppTarget = true
            val item = Item(newId(), true, symbol, exchange, product, qty.sign() * q, lot, tick, s0, trail, target.takeIf { targetId != null || appTarget },
                price, stopId, targetId, auto = auto, appTarget = appTarget)
            list += item; save(list)
            one = item
            covered += q
            rested++
        }
        // (Seventh round: the part with no stop is what is neither covered nor maybe covered by the slice looked for.)
        val bare = total - covered - lostQ
        val shown = one ?: run {
            if (lostQ == 0) return "Not protected: ${why ?: "nothing could be placed"}"
            val rest = if (bare > 0) " The other $bare has NO stop: set it again once that is known, or close it in Trade." else ""
            if (bare > 0) unprotected("$symbol: $bare of $total has no stop at Zerodha (the stop for $lostQ may be resting: its answer was unsure).$rest")
            return "$symbol: Zerodha's answer to its stop for $lostQ of $total was unsure, so it may be resting at Zerodha. The app looks for it - " +
                "about 2 minutes at most - and places no other stop beside it meanwhile; check Kite's order book.$rest"
        }
        val kind = if (s0 != null) "stop" else "target"
        val missed = (targetMissed?.let { " The target was not placed on every slice ($it); their stops rest at Zerodha." } ?: "") +
            (if (anyAppTarget) " $TARGET_BY_APP" else "")
        if (covered < total) {
            val maybe = if (lostQ > 0) " (the stop for $lostQ more may be resting: its answer was unsure, and the app looks for it)" else ""
            if (bare <= 0) return "Protected $symbol at Zerodha in $rested slice${if (rested == 1) "" else "s"} of at most $freeze (the exchange's freeze " +
                "quantity): ${shown.describe()} for $covered of $total$maybe.$missed"
            unprotected("$symbol: only $covered of $total is protected at Zerodha$maybe. The $kind for the other $bare could not be " +
                "placed ($why). That part has NO stop: set one for it yourself, or close it in Trade.")
            return "Partly protected $symbol at Zerodha: ${shown.describe()} for $covered of $total only (in $rested order${if (rested == 1) "" else "s"} " +
                "of at most $freeze, the exchange's freeze quantity)$maybe; the other $bare is NOT protected ($why).$missed"
        }
        return "Protected $symbol at Zerodha in $rested slices of at most $freeze (the exchange's freeze quantity): ${shown.describe()}.$missed"
    }

    /**
     * 15 Nov: the other active live protections on [p]'s contract (exchange, symbol, product, the same direction), [p] left out: the
     * other slices of one position protected in slices ([protectSliced]). Only one protection per contract existed before (a new
     * one replaces the old), so none of them is ever anything else. Read from the list in hand ([cache]: the watch updates it in
     * place, slice by slice). One being removed is not counted: its orders still at Zerodha count as another exit, as before.
     */
    private fun siblings(p: Item): List<Item> = cache.orEmpty().filter {
        it.active && it.live && it.id != p.id && it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product &&
            it.note != REMOVING && it.qty.sign() == p.qty.sign()
    }

    /**
     * 15 Nov, review: what sibling slice [s] really has resting to close its units, from books already read ([orders] by id;
     * [gtts]: null when not read): its GTT active (a stop with it), or its stop order working with nothing of it or of its target
     * filled - what is left of that stop, at most its units. Anything else counts nothing: a stop or target that filled or part
     * filled, a target alone, nothing working, a book not read. So a slice never shrinks or ends its own stop on the strength of
     * a sibling that is not really resting (it may then cover more for a pass, never less).
     */
    private fun restingCover(s: Item, orders: Map<String, Broker.OrderRow>, gtts: List<Broker.GttRow>?): Int =
        restingCoverKnown(s, orders, gtts) ?: abs(s.qty)

    /**
     * [restingCover], or null when it cannot be told: a sibling placed in the last [FRESH_SLICE_MS] whose stop (or GTT) a trailing
     * read of the books does not show yet. Review (N3, R3): the placing paths count such a one whole ([restingCover]: two slices
     * never cover the same units in one pass); the watch's shrink and end decisions are skipped for that pass instead (a sibling
     * refused within a second must never make this slice give up its stop). Never an older one (yesterday's ended ids count 0).
     */
    private fun restingCoverKnown(s: Item, orders: Map<String, Broker.OrderRow>, gtts: List<Broker.GttRow>?): Int? {
        if (s.stop == null) return 0
        val fresh = nowMs() - s.id in 0 until FRESH_SLICE_MS
        val gid = s.gttId
        if (gid != null) {
            val g = gtts?.firstOrNull { it.id == gid }
            if (g != null && g.active) return minOf(g.legQtys.firstOrNull() ?: abs(s.qty), abs(s.qty))
            if (g == null && gtts != null && fresh && s.stopOrderId == null) return null
        }
        val sid = s.stopOrderId ?: return 0
        val so = orders[sid] ?: return if (fresh) null else 0
        val to = s.targetOrderId?.let { orders[it] }
        if (!so.working || so.filled > 0 || (to != null && (to.filled > 0 || to.status == "COMPLETE"))) return 0
        return minOf(com.optionslab.engine.risk.ExitQty.remaining(so.qty, so.filled, so.pending), abs(s.qty))
    }

    /** 15 Nov, review (R2): how far ahead of now a saved id may be and still seed [newId]. */
    private const val ID_SLACK_MS = 5_000L

    /** 15 Nov, review (N3): a slice set this recently is counted whole while a trailing read does not show its stop yet. */
    private const val FRESH_SLICE_MS = 60_000L

    /** 15 Nov, review (R3): [siblingCover] for the watch's shrink and end decisions; null when one sibling cannot be told yet. */
    private fun siblingCoverKnown(p: Item, orders: Collection<Broker.OrderRow>, gtts: List<Broker.GttRow>?): Int? {
        val sib = siblings(p)
        if (sib.isEmpty()) return 0
        val byId = orders.associateBy { it.id }
        var sum = 0
        for (x in sib) sum += restingCoverKnown(x, byId, gtts) ?: return null
        return sum
    }

    /** 15 Nov: the units [p]'s sibling slices really have resting ([restingCover]); 0 for a protection that is not sliced. */
    private fun siblingCover(p: Item, orders: Collection<Broker.OrderRow>, gtts: List<Broker.GttRow>?): Int {
        val sib = siblings(p)
        if (sib.isEmpty()) return 0
        val byId = orders.associateBy { it.id }
        return sib.sumOf { restingCover(it, byId, gtts) }
    }

    /**
     * 15 Nov: what already rests to close [p]'s contract on [side] that is NOT [p]'s own ([known]) nor a sibling slice's: working
     * orders ([orders]) and GTTs ([gtts]). A sibling's stop and target are one-cancels-other (they cover its units once, and its
     * units are taken off what is held instead: [siblingCover]). With no siblings: exactly the sum used before.
     */
    private fun othersBusy(p: Item, side: Kite.Side, orders: List<Broker.OrderRow>, gtts: List<Broker.GttRow>, known: Collection<String>): Int {
        // 15 Nov, review: only a sibling counted as resting ([restingCover]) is left out; any other sibling's working orders count
        // here as any exit does (both its stop and target: more counted as busy only ever places less).
        val byId = orders.associateBy { it.id }
        val all = siblings(p)
        val counted = all.filter { restingCover(it, byId, gtts) > 0 }
        val sibOrders = all.flatMap { listOfNotNull(it.stopOrderId, it.targetOrderId) }.toSet()
        val sibGtts = counted.mapNotNull { it.gttId }.toSet()
        fun left(id: String?): Int = id?.let { byId[it] }?.takeIf { it.working }?.let { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) } ?: 0
        // Review (N4): a sibling not counted as resting adds the larger of its working stop and target (one-cancels-other: they
        // never both sell), never their sum - a sum would leave this slice nothing to send while units go bare.
        val uncounted = (all - counted.toSet()).sumOf { maxOf(left(it.stopOrderId), left(it.targetOrderId)) }
        return orders.filter { it.working && it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product &&
            it.side == side.name && it.id !in known && it.id !in sibOrders }.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) } +
            uncounted + gtts.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.id !in sibGtts }.sumOf { it.covers(side.name, p.product) }
    }

    /**
     * 15 Nov: the stop and target orders the app cancelled itself to send Boss's exit (the exit takeover in Trade), id to when, for
     * an hour at most. Read by the watch ([tickLive]): one of them found cancelled while the position is still held is placed again,
     * never ended as Boss's choice.
     * 22 Nov 2026 (B6 review, M6: memory only - after a restart that followed a square-off's or Boss's in-app exit, the cancelled stop read as
     * Boss's own and was never put back): kept on disk too ([MARKS_FILE], encrypted like the list), handed to this object's own writer before
     * the cancel goes ([noteTakenOver]); read back with the list after a restart; each for [TAKEN_OVER_MS] at most, and one whose stop is still
     * working [TAKEN_SETTLE_MS] after it was made ends there (its cancel never took: a later cancel of it by Boss in Kite is his).
     */
    private val takenOver = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** 22 Nov 2026 (B6 review): how long a taken-over mark is kept (an hour, as before). */
    private const val TAKEN_OVER_MS = 60 * 60_000L
    /** 22 Nov 2026 (B6 review): a stop seen working this long after its mark was made: that cancel never took. */
    internal const val TAKEN_SETTLE_MS = 2 * 60_000L

    /**
     * 15 Nov: [ids] are being cancelled by the app for Boss's exit ([takenOver]). Never blocks. 22 Nov 2026 (B6 review, M6): in memory at once,
     * and handed to the marks' own writer now - before the caller's cancel goes - so a restart keeps it (never the disk on the caller's thread).
     */
    fun noteTakenOver(ids: Collection<String>) {
        val now = System.currentTimeMillis()
        takenOver.entries.removeIf { now - it.value > TAKEN_OVER_MS }
        ids.forEach { takenOver[it] = now }
        if (ids.isNotEmpty()) writeMarksSoon()
    }

    /** 22 Nov 2026 (B6 review, M6): the taken-over marks' own file, beside the list's. */
    private const val MARKS_FILE = "protections_marks.vault"
    @Volatile private var marksFile: File? = null
    /** 22 Nov 2026 (B6 review): the marks' file read and its writes, one at a time (the writer thread and the list's load). */
    private val marksMon = Any()
    /** Whether this process read the marks' file (guarded by [marksMon]). */
    @Volatile private var marksLoaded = false
    /** Marks forgotten before the file was read: never taken back from it ([loadMarksLocked] reads it after its merge too). */
    private val marksForgotten: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val marksWriter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "protections-marks").apply { isDaemon = true } }

    /**
     * 22 Nov 2026 (B6 review): the marks' file read into memory once (under [marksMon]; a worker, never the tick path).
     * (Second round of that review: a read that failed for a moment - the Keystore - left the marks "read", and the next write replaced the
     * file with the marks in memory only, so a restart could take the app's own cancel for Boss's.) Loaded only when the file was read, or
     * there is none; one that fails authentication or does not parse is set aside (kept, never overwritten) and the marks start afresh; any
     * other failure leaves them not loaded - no write replaces the file meanwhile - and it is read again later ([retryMarksSoon]).
     */
    private fun loadMarksLocked() {
        if (marksLoaded) return
        val now = System.currentTimeMillis()
        val f = marksFile
        val bytes: ByteArray? = if (f == null) null else try {
            marksReadForTest.let { if (it != null) it() else Vault.readFileSteady(f) }
        } catch (e: Vault.Tampered) {
            runCatching { Vault.setAside(f) }
            runCatching { Diag.record("protection", "the saved marks of the app's own cancels failed authentication: set aside") }
            null
        } catch (e: Exception) {
            // (Review: one that never reads again - a key gone - would leave every later mark unsaved for good: after [MARKS_FAILS_BEFORE_ASIDE]
            // tries over [MARKS_FAIL_ASIDE_MS] at least, as the ORB arms' book does, it is set aside, the marks start afresh and Boss is told.)
            val wall = System.currentTimeMillis()
            if (marksFails == 0) marksFailSince = wall
            marksFails++
            if (marksFails < MARKS_FAILS_BEFORE_ASIDE || wall - marksFailSince < MARKS_FAIL_ASIDE_MS) {
                runCatching { Diag.record("protection", "the saved marks of the app's own cancels could not be read now (${e.javaClass.simpleName}); " +
                    "read again before anything is written over them") }
                retryMarksSoon()
                return
            }
            runCatching { Vault.setAside(f) }
            runCatching { Alerts.error("The saved record of the stops the app cancelled itself could not be read and was set aside. A stop cancelled " +
                "for an exit before this may now read as cancelled by you and not be put back: check your positions' stops in Kite.", "Protection") }
            null
        }
        marksFails = 0
        if (bytes != null) {
            val o = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
            if (o == null) {
                runCatching { f?.let { Vault.setAside(it) } }
                runCatching { Diag.record("protection", "the saved marks of the app's own cancels could not be parsed: set aside") }
            } else {
                o.keys().forEach { k -> val at = o.optLong(k, 0L); if (k !in marksForgotten && now - at in 0..TAKEN_OVER_MS) takenOver.putIfAbsent(k, at) }
                // (23 Nov 2026, B6 review, eighth round, m5: Boss's own exits whose reply was lost, kept beside the marks - looked for again from
                // when they went, so a restart inside their search keeps the hold and the counts.)
                o.optJSONArray(BOSS_LOST_KEY)?.let { a -> for (i in 0 until a.length()) runCatching {
                    bossLostOf(a.getJSONObject(i))?.takeIf { now - it.sinceMs in 0..TAKEN_OVER_MS }?.let { SquareOffExit.restoreBossLost(it) }
                } }
            }
        }
        // (Review: one forgotten while the file was read - its own removal may have come before the merge - is taken out again; loaded only then.)
        takenOver.keys.removeAll(marksForgotten)
        marksLoaded = true
        marksForgotten.clear()
    }

    /**
     * 23 Nov 2026 (B6 review, ninth round): the marks' file read now - at the start's positions step, before the arms, the ORB books and the
     * square-offs look ([PositionsFirst]), so Boss's lost exits kept there hold and count from the first look. That step's own worker only.
     */
    internal fun loadMarksNow() {
        if (marksFile == null) return
        val gen = wipeGen
        synchronized(marksMon) { if (gen == wipeGen) loadMarksLocked() }
    }

    /** 23 Nov 2026 (B6 review, eighth round, m5): Boss's lost exits changed ([SquareOffExit.noteBossLost]): the marks written again. Never blocks. */
    internal fun bossLostChanged() = writeMarksSoon()

    /** 23 Nov 2026 (B6 review, eighth round): the marks file's entry for Boss's lost exits (no taken-over mark is ever named so). */
    private const val BOSS_LOST_KEY = "bossLostExits"

    private fun bossLostJson(r: SquareOffExit.BossLost): JSONObject = JSONObject().apply {
        val o = r.order
        put("key", r.key); put("symbol", o.tradingSymbol); put("exchange", o.exchange); put("product", o.product); put("side", o.side.name)
        put("qty", o.quantity); put("lot", o.lotSize); put("type", o.orderType); put("tick", o.tickSize); put("tag", o.tag); put("mult", o.multiplier)
        o.price?.let { put("price", it) }; o.triggerPrice?.let { put("trigger", it) }
        put("known", JSONArray(r.known.toList())); put("since", r.sinceMs); r.sentAt?.let { put("sentAt", it.toString()) }
        r.session?.let { put("session", it.toString()) }; put("source", r.source)
    }

    private fun bossLostOf(j: JSONObject): SquareOffExit.BossLost? = runCatching {
        fun d(k: String) = if (j.has(k) && !j.isNull(k)) j.getDouble(k) else null
        val order = Kite.Order(j.getString("symbol"), Kite.Side.valueOf(j.getString("side")), j.getInt("qty"), j.getInt("lot"), j.getString("product"),
            j.getString("type"), d("price"), j.optDouble("tick", 0.05), j.getString("exchange"), j.optString("tag", "iraalgo"), d("trigger"),
            multiplier = j.optInt("mult", 1))
        val known = j.optJSONArray("known")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        SquareOffExit.BossLost(j.getString("key"), order, known, j.getLong("since"),
            j.optString("sentAt").ifEmpty { null }?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() },
            j.optString("session").ifEmpty { null }?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }, j.optString("source", Origins.MANUAL))
    }.getOrNull()

    /** TEST ONLY: what the marks' file read gives (or throws) in place of the vault. */
    @Volatile internal var marksReadForTest: (() -> ByteArray?)? = null
    /** 22 Nov 2026 (B6 review, second round): how long after a failed read of the marks' file it is read (and written) again. */
    private const val MARKS_RETRY_MS = 30_000L
    /** 22 Nov 2026 (B6 review, second round): failed reads of the marks' file in a row before it is set aside, and over how long at least. */
    internal const val MARKS_FAILS_BEFORE_ASIDE = 5
    internal const val MARKS_FAIL_ASIDE_MS = 2 * 60_000L
    @Volatile private var marksFails = 0
    @Volatile private var marksFailSince = 0L
    private val marksRetryDue = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 22 Nov 2026 (B6 review, second round): a marks write tried again on the marks' writer after [MARKS_RETRY_MS] - one waiting at a time. */
    private fun retryMarksSoon() {
        if (!marksRetryDue.compareAndSet(false, true)) return
        runCatching { marksWriter.schedule({ marksRetryDue.set(false); writeMarksSoon() }, MARKS_RETRY_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .onFailure { marksRetryDue.set(false) }
    }

    /**
     * 22 Nov 2026 (B6 review): the marks in memory written on their own writer, in order (an erase since drops the write). Never blocks.
     * (Second round: never while the file could not be read - it may hold marks not in memory; written once it is.)
     */
    private fun writeMarksSoon() {
        if (marksFile == null) return
        runCatching { marksWriter.execute(marksWrite(wipeGen)) }
    }

    /**
     * 23 Nov 2026 (B6 review, eighth round, review): the marks written now on their own writer, waited for (at most 5 s) - a found lost exit
     * of Boss's is no longer kept before he is told or it is booked ([SquareOffExit]), so a restart never tells it twice. Only off the
     * order and tick paths (its caller's own background work).
     */
    internal fun bossLostWritten(): Boolean {
        if (marksFile == null) return false
        // (Ninth round: true only once the file was written - its read failing, or the write, it is not.)
        val gen = wipeGen
        return runCatching { marksWriter.submit(java.util.concurrent.Callable { marksWriteNow(gen) }).get(5, java.util.concurrent.TimeUnit.SECONDS) }
            .getOrDefault(false)
    }

    private fun marksWrite(gen: Int) = Runnable { marksWriteNow(gen) }

    /** The marks written now (the marks' writer only). True once written. */
    private fun marksWriteNow(gen: Int): Boolean {
        synchronized(marksMon) {
            if (gen != wipeGen) return false
            loadMarksLocked()
            if (!marksLoaded) return false
            val now = System.currentTimeMillis()
            val o = JSONObject().apply { takenOver.forEach { (k, at) -> if (now - at in 0..TAKEN_OVER_MS) put(k, at) } }
            // (23 Nov 2026, B6 review, eighth round, m5: Boss's lost exits beside them.)
            val lost = SquareOffExit.bossLostRecords()
            if (lost.isNotEmpty()) o.put(BOSS_LOST_KEY, JSONArray().apply { lost.forEach { put(bossLostJson(it)) } })
            val f = marksFile ?: return false
            return runCatching { Vault.writeFile(f, o.toString().toByteArray(Charsets.UTF_8)) }.isSuccess
        }
    }

    /** TEST ONLY: every marks write handed to the writer so far is done. */
    internal fun drainMarksForTest() { marksWriter.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS) }

    /** TEST ONLY: the marks' file's failed reads in a row began [ms] earlier than they did. */
    internal fun ageMarksFailsForTest(ms: Long) { marksFailSince -= ms }

    /** TEST ONLY: whether this process has read the marks' file. */
    internal fun marksLoadedForTest(): Boolean = marksLoaded

    /** TEST ONLY: as after a restart - the list and the marks forgotten in memory, both read again from disk. */
    internal fun restartForTest() {
        drainMarksForTest(); forgetMemoryForTest(); takenOver.clear(); targetHit.clear(); targetSales.clear(); targetWait.clear()
        synchronized(marksMon) { marksLoaded = false; marksForgotten.clear(); marksFails = 0 }
    }

    /** TEST ONLY: [id]'s taken-over mark made [ms] earlier than it was. */
    internal fun ageTakenOverForTest(id: String, ms: Long) { takenOver.computeIfPresent(id) { _, at -> at - ms } }

    /**
     * 22 Nov 2026 (B6 review): the taken-over mark on [id], whose stop is seen working, belongs to a cancel that never took when made
     * [TAKEN_SETTLE_MS] or more ago: it ends ([forgetTakenOver]), so a later cancel of that stop by Boss in Kite is his. Memory at once.
     */
    private fun endIfSettled(id: String) {
        val at = takenOver[id] ?: return
        if (System.currentTimeMillis() - at in 0 until TAKEN_SETTLE_MS) return
        runCatching { Diag.record("protection", "the app's own cancel of $id never took (still working): its mark ended") }
        // (Second round: with the target sale's own mark on it, if any - [TARGET_CANCEL].)
        forgetTakenOver(listOf(id, TARGET_CANCEL + id))
    }

    /**
     * 20 Nov 2026 (review, the square-offs): the marks ("gtt:<id>") of every active live protection GTT on [exchange]:[symbol] - those
     * saved and those placed in an exit turn not yet saved - as [dayGttsOut] takes them out. Memory only.
     */
    fun dayGttMarks(exchange: String, symbol: String): List<String> {
        val key = "$exchange:$symbol"
        return (dayGttHint[key].orEmpty() + freshDayGtts[key].orEmpty()).distinct().map { "gtt:$it" }
    }

    /**
     * 23 Nov 2026 (B6 review, eighth round, m2): by GTT id, the orders a protection's GTT gave as it fired while Boss's exit from the app
     * deleted it - its put-back reads them ([tickDayGtt]) even while Zerodha's book or GTT list does not show them. Memory only (bounded).
     */
    private val gttFired = java.util.concurrent.ConcurrentHashMap<Long, List<String>>()
    internal fun noteGttFired(gid: Long, ids: List<String>) {
        if (ids.isEmpty()) return
        if (gttFired.size > 200) gttFired.clear()
        gttFired[gid] = ids
    }

    /** 20 Nov 2026 (review): whether [id] is already noted taken over by another of the app's paths ([noteTakenOver]). Memory only. */
    fun isTakenOver(id: String): Boolean = takenOver.containsKey(id)

    /**
     * 23 Nov 2026 (B6 review, ninth round): whether [id] may be one of the protections' own orders - an active protection's stop or target as
     * the list was last published, or any id while it is not read yet. Memory only (never the lock or the disk). (A stop placed again by a
     * look and not published yet is not matched until that look ends.)
     */
    fun mayOwn(id: String): Boolean = published?.any { it.active && (it.stopOrderId == id || it.targetOrderId == id) } ?: true

    /**
     * 20 Nov 2026 (audit, the square-offs): [ids] were noted ([noteTakenOver]) but their cancel did not take (still working at Zerodha):
     * forgotten, so a later cancel of them by Boss in Kite is his (never put back). Memory only; never blocks.
     */
    fun forgetTakenOver(ids: Collection<String>) {
        if (ids.isEmpty()) return
        // (22 Nov 2026, B6 review, M6: on disk too - one forgotten before the file was read is never taken back from it: noted first, then
        // removed, so a read in progress either skips it or takes it out again after its merge.)
        if (!marksLoaded) marksForgotten += ids
        ids.forEach { takenOver.remove(it) }
        writeMarksSoon()
    }

    /**
     * 15 Nov, review: Boss's exit for which [ids] were taken over ([noteTakenOver]) filled in full (AppModel, once every leg of it
     * did). That does not mean the position is closed (lots added between the review and the send stay held), so they stay to be
     * put back, on what is held less every exit fill the positions do not show yet. Only when Kite's positions do not give the
     * day's quantity (that guard cannot work) does the put-back wait, for [EXIT_DONE_MS] (the market's clock).
     */
    fun exitFilledFor(ids: Collection<String>, exits: Map<String, Int> = emptyMap()) {
        val now = nowMs()
        exitFilled.entries.removeIf { now - it.value > EXIT_DONE_MS }
        exitOrders.keys.retainAll(exitFilled.keys)
        ids.forEach { exitFilled[it] = now; exitOrders[it] = exits }
    }

    /**
     * 20 Nov 2026 (deep review: a square-off sale refused after it was placed left the stop it took out away for 120 s): an exit
     * registered by [exitFilledFor] whose order the book [rows] shows REJECTED or CANCELLED has ended: its wait is dropped at once, so
     * the stop it took out is put back on the next look (on what is held less every fill of today, the lag guard forced).
     */
    private fun settleExits(rows: Collection<Broker.OrderRow>?) {
        if (rows == null || exitOrders.isEmpty()) return
        // Review: only an order that ended with NOTHING filled is dropped from what the book must show; one that filled part keeps
        // the wait (its fill may not show in the positions yet). The wait itself ends only once no order is left to show.
        val ended = rows.filter { (it.status == "REJECTED" || it.status == "CANCELLED") && it.filled == 0 }.map { it.id }.toSet()
        if (ended.isEmpty()) return
        for ((id, exits) in exitOrders.entries.toList()) {
            if (exits.keys.none { it in ended }) continue
            val left = exits.filterKeys { it !in ended }
            if (left.isEmpty()) { exitOrders.remove(id); exitFilled.remove(id) } else exitOrders[id] = left
        }
    }

    /** 15 Nov, review (F1): Boss's exit orders (id to quantity filled) for each taken-over id said filled ([exitFilledFor]). */
    private val exitOrders = java.util.concurrent.ConcurrentHashMap<String, Map<String, Int>>()

    /**
     * 15 Nov, review (F1): Boss's exit orders the order book must show COMPLETE, with their quantity filled, before a stop taken
     * over by [ids] is put back - only within [EXIT_DONE_MS] of the fill (after that, as before: the lag guard alone). Null: none.
     */
    private fun mustShowFor(ids: Collection<String>): Map<String, Int>? =
        ids.filter { exitDone(it) }.flatMap { exitOrders[it].orEmpty().entries }.associate { it.key to it.value }.takeIf { it.isNotEmpty() }

    /**
     * 15 Nov, review (F1): whether the order book [book] shows every one of [must] COMPLETE with its quantity filled. 23 Nov 2026 (B6 review,
     * seventh round: an exit that filled part and then ended - cancelled or refused for the rest - never shows COMPLETE, and the stop waited the
     * whole [EXIT_DONE_MS]): one that ended with its fill shown counts too.
     */
    private fun bookShows(book: Collection<Broker.OrderRow>, must: Map<String, Int>?): Boolean =
        must.isNullOrEmpty() || must.all { (id, q) -> book.any { it.id == id && it.status in GONE && it.filled >= q } }

    private val exitFilled = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val EXIT_DONE_MS = 120_000L

    /** 15 Nov, review: [id] was cancelled for Boss's exit that filled in full, within [EXIT_DONE_MS] ([exitFilledFor]). */
    private fun exitDone(id: String): Boolean = exitFilled[id]?.let { at -> nowMs() - at in 0..EXIT_DONE_MS } == true

    /**
     * 15 Nov, review (R1): whether Zerodha's positions give [p]'s day quantity on its exit side (the lag guard compares the order
     * book's fills with it). Not read, or not given: false.
     */
    private suspend fun dayQtyKnown(p: Item): Boolean = runCatching {
        val rs = Broker.positionBook().net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }
        rs.isNotEmpty() && rs.all { (if (p.qty > 0) it.daySellQty else it.dayBuyQty) != null }
    }.getOrDefault(false)

    /** TEST ONLY: whether [id]'s exit was said filled in full within [EXIT_DONE_MS] ([exitFilledFor]). */
    internal fun exitDoneForTest(id: String): Boolean = exitDone(id)

    /** TEST ONLY: whether [id] is still to be put back if its exit does not close the position ([noteTakenOver]). */
    internal fun takenOverForTest(id: String): Boolean = takenOver.containsKey(id)

    /** 15 Nov: a fresh protection id, never the same as one just given (slices set, or placed again, in the same millisecond). */
    @Volatile private var lastId = 0L
    @Synchronized private fun newId(): Long {
        // 15 Nov, CI: the last id given is followed only while it is not ahead of the clock (a clock moved back, or once ahead):
        // carried on, every new protection's id would be in the future, and the watch reads a carried one's id against today
        // (an earlier day's orders ended with their day; "its first minute") - its stops would not be placed again at the open.
        val now = nowMs()
        val next = if (lastId > now + ID_SLACK_MS) now else maxOf(now, lastId + 1)
        lastId = next
        return next
    }

    /** Remove a protection: its resting orders are cancelled. */
    /**
     * 7 Nov: [note] is what it ends with ("removed" by Boss; [com.optionslab.ira.ManualCoverRule.UNHEDGED] when "Stop and target on
     * my manual positions" takes its own off because the position became part of a spread or a hedge).
     */
    suspend fun remove(id: Long, note: String = "removed"): String = lock.withLock {
        val list = load()
        val p = list.firstOrNull { it.id == id && it.active } ?: return@withLock "Nothing to remove."
        if (!cancelOrders(p)) {
            // Kept, and cancelled again on every pass: marked finished now, an exit still resting at the
            // broker would be forgotten while it can still fill.
            list.replaceAll { if (it.id == id) it.copy(note = REMOVING) else it }
            save(list)
            // (23 Nov 2026, B6 review, seventh round: one whose stop may be resting - looked for, and cancelled once found.)
            if (p.note == STOP_LOST) return@withLock "${p.symbol}: its stop may be resting at Zerodha (its answer was unsure). The protection stays " +
                "listed while the app looks for it - about 2 minutes at most - and cancels it once found; check Kite's order book."
            return@withLock "${p.symbol}: its stop or target could not be confirmed cancelled. The protection stays listed and is cancelled again on every pass until the broker confirms."
        }
        list.replaceAll { if (it.id == id) it.copy(active = false, note = note) else it }
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
                // (23 Nov 2026, B6 review, seventh round: one whose stop may be resting - looked for, and cancelled once found.)
                if (p.note == STOP_LOST) Alerts.error("$symbol: its protection's stop may be resting at Zerodha (its answer was unsure); the app " +
                    "looks for it and cancels it once found - check Kite's order book.", "Protection")
                else Alerts.error("$symbol: its protection's stop or target could not be confirmed cancelled; it is cancelled again on every pass.", "Protection")
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
        // (23 Nov 2026, B6 review, seventh round: one whose stop may be resting - [STOP_LOST], no order id - removed by Boss, the square-off or
        // an unhedged cover was ended with nothing to cancel, and a stop found later was left to sell what is no longer held: [lostStopGone].)
        if (p.live && p.stopOrderId == null && p.gttId == null && p.stop != null && p.id >= today && !lostStopGone(p)) gone = false
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
     * 23 Nov 2026 (B6 review, seventh round): whether [p]'s stop that may be resting ([STOP_LOST]) is known gone, for its removal ([cancelOrders]).
     * Its lost reply asked after ([Broker.lookLost]). Found: cancelled now and confirmed (not confirmed: noted again, so the next pass finds it
     * and cancels it again). Still looked for: not gone - the protection is kept being removed and asked again each pass. Given up on (a good
     * read 2 minutes on did not show it), or none was ever lost: gone. None kept for one that was (a restart: memory only): looked for again
     * from when it went.
     */
    private suspend fun lostStopGone(p: Item): Boolean {
        val key = lostStopKey(p)
        if (!Broker.lostPending(key)) {
            // (Only a lost stop's protection is removed with no order at all: one with none placed is ended at once, never kept removing.)
            if (p.note != STOP_LOST && !(p.note == REMOVING && p.targetOrderId == null)) return true
            noteLostStop(p)
            return false
        }
        return when (val l = Broker.lookLost(key)) {
            is Broker.LostLook.Found -> {
                if (runCatching { Broker.orderState(l.id)?.status }.getOrNull() !in GONE) runCatching { Broker.cancel(l.id) }
                (runCatching { Broker.orderState(l.id)?.status }.getOrNull() in GONE).also { done ->
                    runCatching { Diag.record("protection", "${p.symbol}: its unsure stop found (order ${l.id}) as its protection was removed: " +
                        if (done) "cancelled" else "not confirmed cancelled yet - asked again next pass") }
                    if (!done) noteLostStop(p)
                }
            }
            Broker.LostLook.Searching -> false
            else -> true
        }
    }

    /** 23 Nov 2026 (B6 review, sixth round): [p]'s stop that may be resting noted lost again, from when it went (its id is that time). */
    private fun noteLostStop(p: Item) {
        val side = if (p.qty > 0) Kite.Side.SELL else Kite.Side.BUY
        val s0 = p.stop ?: return
        val o = Kite.Order(p.symbol, side, abs(p.qty), p.lotSize, p.product, "SL", stopLimit(s0, side, p.tick), p.tick, p.exchange,
            "iraprotect", triggerPrice = s0)
        val sent = runCatching { java.time.Instant.ofEpochMilli(p.id).atZone(com.optionslab.engine.IST).toLocalDateTime() }.getOrNull()
        Broker.noteLost(lostStopKey(p), o, siblings(p).flatMap { listOfNotNull(it.stopOrderId, it.targetOrderId) }, "${p.symbol}: its protection's stop",
            liveOnly = true, sinceMs = p.id, sentAt = sent)
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
        // (23 Nov 2026, B6 review, eighth round: nor while Boss's own exit on it whose reply was lost may be selling - the next look.)
        if (SquareOffExit.bossExitLostOn(p.exchange, p.symbol)) return p
        // 20 Oct, enhancement round 2: read and rested in the contract's one exit turn, from reads that show every exit sent from here
        // ([ExitCoordinator]), the orders and GTTs first and then the position read again (an exit filling meanwhile is never doubled).
        return ExitCoordinator.turn(p.exchange, p.symbol, "protection") { restOvernightInTurn(p, net0) }
    }

    private suspend fun restOvernightInTurn(p: Item, net0: Int): Item {
        val side = if (p.qty > 0) Kite.Side.SELL else Kite.Side.BUY
        val gtts = runCatching { ExitCoordinator.gtts(p.exchange, p.symbol, "protection") }.getOrNull() ?: return p
        val orders = runCatching { ExitCoordinator.orders(p.exchange, p.symbol, "protection") }.getOrNull() ?: return p
        // (23 Nov 2026, B6 review, eighth round: never a GTT for what Boss's own exit whose reply was lost may be selling.)
        val net = heldAgain(p, orders)?.let { SquareOffExit.lessBossLost(it, p.symbol, p.product, p.exchange) } ?: return p
        if (net == 0 || net.sign() != net0.sign()) return p                // closed meanwhile: the next look ends it
        // Review: a GTT of this protection's own that went through but was never recorded (a lost reply, a save that did not land)
        // is adopted, never counted as "another exit": counted busy, the open would place the day's orders beside it.
        val want = listOfNotNull(p.stop, p.target).map { Math.round(Math.round(it / p.tick) * p.tick * 100) / 100.0 }.sorted()   // as [Kite.protect]
        // 15 Nov: never a GTT another protection records (a sibling slice of the same size and levels: [protectSliced]).
        val recorded = cache.orEmpty().filter { it.id != p.id }.mapNotNull { it.gttId }.toSet()
        gtts.firstOrNull { g -> g.active && g.id !in recorded && g.symbol == p.symbol && g.exchange == p.exchange && g.legSides.firstOrNull() == side.name &&
            g.legProducts.firstOrNull() == p.product && (g.legQtys.firstOrNull() ?: 0) <= abs(p.qty) && g.triggers.size == want.size &&
            g.triggers.sorted().zip(want).all { (a, b) -> abs(a - b) < p.tick / 2 + 1e-9 } }?.let { mine ->
            gttTries.remove(p.id)
            return p.copy(gttId = mine.id)
        }
        // 15 Nov: a sibling slice's orders and GTT are not "another exit": its units come off what is held instead ([othersBusy]).
        val busy = othersBusy(p, side, orders, gtts, emptyList())
        // Review: what is held less every other exit, at most what this protection covers (an arm's own lots and GTT beside it
        // never take this protection's place).
        // Review (R3): a sibling set seconds ago that the books do not show yet: nothing this look (never two GTTs for the same units).
        if (siblingCoverKnown(p, orders, gtts) == null) return p
        val qty = com.optionslab.engine.risk.ExitQty.sendable((abs(net) - siblingCover(p, orders, gtts)).coerceAtLeast(0), busy, abs(p.qty), p.lotSize)
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
        val last = runCatching { Broker.quotes(listOf(key), lane = com.optionslab.ira.RateGate.Priority.SAFETY)[key]?.last }.getOrNull()?.takeIf { it > 0 } ?: return p   // B5-D: a stop's read
        val spec = Kite.Spec(p.exchange, p.symbol, 0L, p.lotSize, p.tick)
        var (gtt, why) = Kite.protect(spec, p.product, p.qty.sign() * qty, last, p.stop, p.target, stopSlippage = Kite.STOP_GTT_SLIPPAGE)
        // A target that cannot rest (too near the price) never costs the stop: the stop alone.
        if (gtt == null && p.stop != null && p.target != null) Kite.protect(spec, p.product, p.qty.sign() * qty, last, p.stop, null, stopSlippage = Kite.STOP_GTT_SLIPPAGE).let { (g2, w2) -> if (g2 != null) { gtt = g2; why = w2 } }
        // 10 Nov (Boss's diagnostics, 6 Nov: a NIFTY call carried overnight with NO stop, its own already at or through the price):
        // the nearest stop Zerodha takes rests in its place ([Kite.overnightFallbackStop]). Only ever adds a stop: it lowers risk.
        var fallback: Double? = null
        if (gtt == null) Kite.overnightFallbackStop(p.qty > 0, last, p.stop, p.tick)?.let { fb ->
            Kite.protect(spec, p.product, p.qty.sign() * qty, last, fb, null, stopSlippage = Kite.STOP_GTT_SLIPPAGE).first?.let { gtt = it; fallback = fb }
        }
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
        fallback?.let { fb ->
            Alerts.post("%s: its own stop (%.2f) is through or too close to the price (%.2f) for Zerodha to take overnight, so a stop at %.2f "
                .format(java.util.Locale.ENGLISH, p.symbol, p.stop ?: 0.0, last, fb) + "rests at Zerodha overnight as a GTT for $qty instead. " +
                "Its own stop and target are placed again at the open.", Alerts.Kind.INFO, "Protection")
            return p.copy(gttId = id)
        }
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
        val all = runCatching { Broker.gtts() }.getOrNull() ?: return null
        val g = all.firstOrNull { it.id == id } ?: return null
        val q = g.legQtys.firstOrNull() ?: return null
        // 15 Nov: a slice ([protectSliced]) keeps it only within what the other slices really have resting (their books read).
        val sibOrders = if (siblings(p).any { it.stopOrderId != null }) runCatching { Broker.orders() }.getOrNull() ?: return null else emptyList()
        if (!g.active || g.triggers.size != 2 || q <= 0 || q > minOf(abs(net) - siblingCover(p, sibOrders, all), abs(p.qty))) return null
        Alerts.post("${p.symbol}: its stop and target stay at Zerodha as one GTT today (${p.describe()}): whichever fires, Zerodha " +
            "cancels the other, even with the phone off.", Alerts.Kind.SUCCESS, "Protection")
        DayOco.note(DayGttTally.Event.KEPT)
        return p.copy(id = newId(), qty = p.qty.sign() * q, stopOrderId = null, targetOrderId = null, note = DAY_GTT)
    }

    /**
     * [p]'s stop and target as ONE two-leg GTT for what it covers - never more than is held less every other exit resting on the
     * contract (an order or a GTT, an arm's own) - in the contract's one exit turn ([ExitCoordinator]), from reads that show every
     * exit sent from here. [p] as a day GTT under a new id; null when it was not placed (the books or the price not read, nothing
     * left to cover, Zerodha refused it): the caller places the two DAY orders instead.
     */
    private suspend fun restDayGtt(p: Item, net0: Int, lagSafe: Boolean = false, mustShow: Map<String, Int>? = null): DayGtt =
        ExitCoordinator.turn(p.exchange, p.symbol, "protection") { restDayGttInTurn(p, net0, lagSafe, mustShow) }

    /**
     * [restDayGtt]'s outcome. [Refused]: nothing was sent, or Zerodha said no (the caller may rest the two DAY orders). [Unknown]:
     * the books not read, or the GTT's answer lost and not found in the list (it may exist): the caller places NOTHING else this look.
     */
    private sealed class DayGtt { class Placed(val item: Item) : DayGtt(); object Refused : DayGtt(); object Unknown : DayGtt()
        /** 15 Nov, review (F2): nothing sent, nothing known to be wrong - asked again next look, no try used up. */
        object Wait : DayGtt() }

    private suspend fun restDayGttInTurn(p: Item, net0: Int, lagSafe: Boolean = false, mustShow: Map<String, Int>? = null): DayGtt {
        val side = if (p.qty > 0) Kite.Side.SELL else Kite.Side.BUY
        val gtts = runCatching { ExitCoordinator.gtts(p.exchange, p.symbol, "protection") }.getOrNull() ?: return DayGtt.Unknown
        val orders = runCatching { ExitCoordinator.orders(p.exchange, p.symbol, "protection") }.getOrNull() ?: return DayGtt.Unknown
        // Review (F1): Boss's exit said filled in full but the order book just read does not show it yet: asked again next look.
        if (!bookShows(orders, mustShow)) return DayGtt.Wait
        val net = heldAgain(p, orders, lagSafe) ?: return DayGtt.Unknown
        // (23 Nov 2026, B6 review, eighth round: never a GTT beside Boss's own exit whose reply was lost - it waits; the next look's two orders
        // cover what that exit leaves and grow once it is known ([restAgainClaimed]).)
        if (SquareOffExit.lessBossLost(net, p.symbol, p.product, p.exchange) != net) return DayGtt.Wait
        // Review (F2): put back after Boss's exit, nothing left held once the fills the positions trail are counted: asked again
        // next look (never a refusal, which would give up the one-GTT route for good).
        if (net == 0 || net.sign() != net0.sign()) return if (lagSafe) DayGtt.Wait else DayGtt.Refused
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
            return DayGtt.Placed(p.copy(id = newId(), qty = p.qty.sign() * (mine.legQtys.firstOrNull() ?: abs(p.qty)), stopOrderId = null,
                targetOrderId = null, gttId = mine.id, note = DAY_GTT))
        }
        // 15 Nov: a sibling slice's orders and GTT are not "another exit": its units come off what is held instead ([othersBusy]).
        val busy = othersBusy(p, side, orders, gtts, emptyList())
        // Review (R3): a sibling set seconds ago that the books do not show yet: nothing this look (its answer is asked again).
        // (F3: it waits even when there is room - so, if that sibling was refused, this one can then cover all of it.)
        if (siblingCoverKnown(p, orders, gtts) == null) return DayGtt.Wait
        val qty = com.optionslab.engine.risk.ExitQty.sendable((abs(net) - siblingCover(p, orders, gtts)).coerceAtLeast(0), busy, abs(p.qty), p.lotSize)
        if (qty <= 0) return DayGtt.Refused
        val last = lastPrice(p, stream = false) ?: return DayGtt.Refused
        val (g, why) = Kite.protect(Kite.Spec(p.exchange, p.symbol, 0L, p.lotSize, p.tick), p.product, p.qty.sign() * qty, last, p.stop, p.target, stopSlippage = Kite.STOP_GTT_SLIPPAGE)
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
        return DayGtt.Placed(p.copy(id = newId(), qty = p.qty.sign() * qty, stopOrderId = null, targetOrderId = null, gttId = id, note = DAY_GTT))
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
        // 20 Nov 2026 (audit): the look's one positions read ([PassReads]); a read with no row for it is not known, and closed or
        // turned round counts only once two reads agree ([confirmedHeld]).
        suspend fun netNow(): Int? = heldConfirmed(p, runCatching { passPositions(stream = false) }.getOrNull())
        // Review (R2): an id ahead of now (a clock once ahead) is never "its first minute" for ever.
        val net = if (nowMs() - p.id in 0L until 60_000L) null else netNow()
        val closed = net != null && (net == 0 || net.sign() != p.qty.sign())
        if (gid == null) return placeDayExits(p, net ?: return p)
        val allGtts = runCatching { passGtts() }.getOrElse { return p }
        val g = allGtts.firstOrNull { it.id == gid }
        if (g == null || g.status.lowercase() in GTT_GONE) {
            if (net == null) return p
            // 15 Nov: deleted by the app itself for Boss's exit ([noteTakenOver]: "gtt:<id>") and the position still held - the exit
            // refused, failed or part filled: its stop and target are placed again for what is held, never ended as gone. Not
            // while Boss's exit is going, nor for [EXIT_DONE_MS] after it filled in full (the positions may trail it); always on
            // what is held less any exit fill the positions do not show yet (review: never a naked stop on a flat account). Kept
            // marked until it is placed, so every try is made the same careful way.
            if (!closed && takenOver.containsKey("gtt:$gid")) {
                if (exitFilled.containsKey("gtt:$gid")) settleExits(runCatching { passOrders(stream = false) }.getOrNull())
                if (ArmLive.handsOffNow(p.exchange, p.symbol) || (exitDone("gtt:$gid") && !dayQtyKnown(p))) return p
                // (23 Nov 2026, B6 review, eighth round, m2: one that fired as it was deleted - its orders read first, each on its own (Zerodha's
                // book may not show them yet): one still working covers the position - nothing beside it, asked again next look; one that filled
                // is that much sold - placed again for the rest once the book shows it; one refused or cancelled covers nothing.)
                val firedFills = HashMap<String, Int>()
                for (oid in (g?.orderIds.orEmpty() + gttFired[gid].orEmpty()).distinct()) {
                    // (Ninth round: one Zerodha does not know yet - it fired a moment ago - is not known: never "covers nothing", nothing goes
                    // beside it, and Boss is told - for [Broker.LOST_SEARCH_MS] from the mark, by when its book shows any order it took; after
                    // that, an order it does not know covers nothing.)
                    val young = takenOver["gtt:$gid"]?.let { System.currentTimeMillis() - it in 0 until Broker.LOST_SEARCH_MS } == true
                    val st = try { Broker.orderState(oid) } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Broker.KiteError) { if (e.type == "InputException") null else return p
                    } catch (_: Exception) { return p }
                    if (st == null) {
                        if (!young) continue
                        Alerts.post("${p.symbol}: its GTT fired as your exit took it out and Zerodha does not show its order $oid yet, so no stop " +
                            "goes beside it; check Kite.", Alerts.Kind.ERROR, "Protection", throttle = true)
                        return p
                    }
                    if (st.status !in GONE) {
                        // (Review: never silent - said as the fired branch says it, each minute while it works.)
                        Alerts.post("${p.symbol}: its GTT fired as your exit took it out; its ${p.exitSide.lowercase()} rests at Zerodha unfilled, so no " +
                            "stop goes beside it. If the price runs away from it, close the position in Trade.", Alerts.Kind.ERROR, "Protection", throttle = true)
                        return p
                    }
                    if (st.filled > 0) firedFills[oid] = st.filled
                }
                val must = (mustShowFor(listOf("gtt:$gid")).orEmpty() + firedFills).takeIf { it.isNotEmpty() }
                val next = placeDayExits(p.copy(gttId = null), net, lagSafe = true, mustShow = must)
                if (next.active && next.gttId == null && next.stopOrderId == null && next.targetOrderId == null) return p
                takenOver.remove("gtt:$gid"); gttFired.remove(gid)
                runCatching { Diag.record("protection", "${p.symbol}: its GTT was deleted for your exit and the position is still held ($net): placed again") }
                return next
            }
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
        // 15 Nov: one slice ([protectSliced]) left with nothing to cover (the other slices cover all that is held): its GTT out,
        // confirmed, and the slice ended; not confirmed: asked again next pass.
        // 15 Nov, review: only what the other slices really have resting ([restingCover]); their orders read only when one has any.
        // Review (N5): their orders not read: no shrink or end decision this pass (the next look reads them again).
        val sibOrders = if (siblings(p).any { it.stopOrderId != null }) runCatching { passOrders(stream = false) }.getOrNull() else emptyList()
        val sibKnownCover = if (sibOrders == null) null else siblingCoverKnown(p, sibOrders, allGtts)
        val sibCover = sibKnownCover ?: 0
        if (sibKnownCover != null && sibCover > 0 && abs(net) <= sibCover) return when (gttState(p, cancelFired = false)) {
            GttState.GONE -> p.copy(active = false, gttId = null, note = com.optionslab.ira.ManualCoverRule.SLICE_DONE)
            GttState.HELD, GttState.FIRED -> p
        }
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
        // 15 Nov: a slice covers what is held less what the other slices cover (not sliced: what is held, as before).
        val mine = abs(net) - sibCover
        if (sibKnownCover != null && mine < covers && last != null) {
            val n = (mine / p.lotSize.coerceAtLeast(1)) * p.lotSize.coerceAtLeast(1)
            if (n > 0) {
                val (gtt, why) = Kite.protect(Kite.Spec(p.exchange, p.symbol, 0L, p.lotSize, p.tick), p.product, p.qty.sign() * n, last, p.stop, p.target, stopSlippage = Kite.STOP_GTT_SLIPPAGE)
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
        } else if (abs(cur.qty) != covers && covers in 1..mine) cur = p.copy(qty = p.qty.sign() * covers)
        return trailDayGtt(cur, gid, last ?: return cur)
    }

    /** [p]'s trailing stop moved up (a long; down for a short) at [last]: the GTT modified in place, the target leg kept. */
    private suspend fun trailDayGtt(p: Item, gid: Long, last: Double): Item {
        if (p.trail == null) return p
        val n = Protection.next(p.spec(), last)
        val ns = n.stop ?: return p.copy(best = n.best)
        if (ns == p.stop || abs(ns - (p.stop ?: 0.0)) < p.tick - 1e-9) return p.copy(best = n.best)
        // Too near the price or the target for Zerodha (0.25%): the GTT stays where it is, tried again on a later look.
        val (gtt, _) = Kite.protect(Kite.Spec(p.exchange, p.symbol, 0L, p.lotSize, p.tick), p.product, p.qty, last, ns, p.target, stopSlippage = Kite.STOP_GTT_SLIPPAGE)
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
    private suspend fun placeDayExits(p: Item, net: Int, ordersNow: Boolean = false, lagSafe: Boolean = false,
                                      mustShow: Map<String, Int>? = null): Item {
        if (net == 0 || net.sign() != p.qty.sign()) return p.copy(active = false, note = "position closed")
        val cover = p.copy(qty = net.sign() * minOf(abs(net), abs(p.qty)))
        // 6 Nov: Boss is exiting this contract by hand ([ArmLive.handsOff]): nothing placed beside his sale (asked again next look,
        // without using up a GTT try).
        if (ArmLive.handsOffNow(p.exchange, p.symbol)) return p.copy(qty = cover.qty)
        // (23 Nov 2026, B6 review, eighth round, m4: while Boss's own exit on it whose reply was lost may be selling, as two orders - the stop
        // only for what his exit leaves, grown once it is known ([restAgainClaimed], [growStop]) - never a GTT for the whole.)
        val bossCut = SquareOffExit.bossLostQty(p.symbol, p.product, if (p.qty > 0) Kite.Side.SELL else Kite.Side.BUY, p.exchange) > 0
        if (!ordersNow && !bossCut && dayOco(cover) && (gttTries[p.id] ?: 0) < GTT_TRIES) {
            when (val r = restDayGtt(cover, net, lagSafe, mustShow)) {
                is DayGtt.Placed -> return r.item
                DayGtt.Unknown -> { gttTries[p.id] = (gttTries[p.id] ?: 0) + 1; return p.copy(qty = cover.qty) }
                DayGtt.Wait -> return p.copy(qty = cover.qty)
                DayGtt.Refused -> gttTries[p.id] = GTT_TRIES
            }
        }
        if (!marketOpen(p.exchange)) return p.copy(qty = cover.qty)
        val next = restAgain(cover.copy(note = ""), net, said = "its stop and target rest at Zerodha as two orders again", lagSafe = lagSafe,
            mustShow = mustShow)
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
    private suspend fun restAgain(p0: Item, net0: Int = p0.qty, said: String? = null, lagSafe: Boolean = false,
                                  mustShow: Map<String, Int>? = null): Item? =
        // 20 Oct, enhancement round 2: read and placed in the contract's one exit turn ([ExitCoordinator]), as [restOvernight].
        ExitCoordinator.turn(p0.exchange, p0.symbol, "protection") { restAgainInTurn(p0, net0, said, lagSafe, mustShow) }

    /**
     * [said]: what the owner is told once they are placed (21 Oct, round 4: the day GTT switched off); null: the next session's words.
     * 22 Nov 2026 (B6 review, second round, with the square-offs' protocol): the row's claim held while they go in ([CLAIM_OWNER]; the target's
     * own sale putting its stop back holds it already) - another seller on the row (a square-off whose stops are coming out, an arm's exit):
     * nothing put back this pass ([p00] as it was: asked again on the next look).
     */
    private suspend fun restAgainInTurn(p00: Item, net0: Int, said: String? = null, lagSafe: Boolean = false,
                                        mustShow: Map<String, Int>? = null): Item? {
        // (23 Nov 2026, B6 review, fourth round: under its own name, [STOP_CLAIM_OWNER], so Boss is told a stop is going in, not a sale.)
        val owner = if (SquareOffExit.exitClaimedBy(p00.exchange, p00.symbol, p00.product) == CLAIM_OWNER) CLAIM_OWNER else STOP_CLAIM_OWNER
        val claim = SquareOffExit.claimExit(p00.exchange, p00.symbol, p00.product, owner) ?: return p00
        try { return restAgainClaimed(p00, net0, said, lagSafe, mustShow) } finally { claim.release() }
    }

    /** 23 Nov 2026 (B6 review, fourth round): the protections' name on a row's claim while one of their stops goes back ([restAgainInTurn]). */
    internal const val STOP_CLAIM_OWNER = "protections-stop"

    /** [restAgainInTurn] with the row's exit claim held. */
    private suspend fun restAgainClaimed(given: Item, net0: Int, said: String?, lagSafe: Boolean, mustShow: Map<String, Int>?): Item? {
        // (23 Nov 2026, B6 review, eighth round: one put back earlier for only part ([Item.whole]) is put back for its whole - cut again below
        // while Boss's lost exit is still looked for.)
        val p00 = given.whole?.takeIf { abs(it) > abs(given.qty) }?.let { given.copy(qty = it, whole = null) } ?: given
        // 6 Nov: Boss is exiting this contract by hand ([ArmLive.handsOff]): nothing placed beside his sale (asked again next look).
        // (23 Nov 2026, B6 review, eighth round, m4: while his own exit on it whose reply was lost may be selling, the stop goes back for what
        // that exit leaves - below - never nothing: a protection has no level watched by the app.)
        if (ArmLive.handsOffNow(p00.exchange, p00.symbol)) return p00
        // (23 Nov 2026, B6 review, sixth round: another protection's stop on it may be resting - nothing placed beside it until that is known.)
        if (stopMaybeResting(cache, p00.exchange, p00.symbol, exceptId = p00.id) != null) return p00
        val side = if (p00.qty > 0) Kite.Side.SELL else Kite.Side.BUY
        val known = listOfNotNull(p00.stopOrderId, p00.targetOrderId)
        // Second review: an exit already resting on it (Boss's own after-market order, another owner's) is subtracted first, so
        // the two together never sell more than is held. All of it covered: nothing placed, and said. 20 Oct, enhancement round 1:
        // an active GTT on it (an arm's own, or one of ours not recorded) counts too, against all that is held ([net]).
        var book: List<Broker.OrderRow> = emptyList()
        var gttBook: List<Broker.GttRow> = emptyList()
        val busy = runCatching {
            book = ExitCoordinator.orders(p00.exchange, p00.symbol, "protection")
            gttBook = ExitCoordinator.gtts(p00.exchange, p00.symbol, "protection")
            // 15 Nov: a sibling slice's own orders and GTT are left out here; its units come off what is held instead (below).
            othersBusy(p00, side, book, gttBook, known)
        }.getOrNull() ?: return p00                                       // the books not read: asked again next look
        // 20 Oct, enhancement round 2: what is held read again AFTER the books (an exit that filled since the caller's read shows
        // here, not as still held with nothing on its way out); never more than the caller saw.
        // Review (F1): Boss's exit said filled in full but the order book just read does not show it yet (the app learns a fill
        // from Zerodha's stream before its order book shows it): asked again next look, so the lag guard has the fill to count.
        if (!bookShows(book, mustShow)) return p00
        val again = heldAgain(p00, book, lagSafe) ?: return p00
        if (again == 0 || again.sign() != net0.sign()) return p00         // closed meanwhile: the next look ends it
        // (23 Nov 2026, B6 review, eighth round, m4: what Boss's own exit whose reply was lost may be selling is not covered beside it - the
        // stop goes back for the rest only, the protection keeping its size, and grows once that exit is known ([growStop]); all of it: nothing
        // this look, asked again on the next.)
        val less = SquareOffExit.lessBossLost(again, p00.symbol, p00.product, p00.exchange)
        if (less == 0) return p00
        val net = if (abs(again) < abs(net0)) again else net0
        val p0 = if (abs(p00.qty) > abs(net)) p00.copy(qty = net.sign() * abs(net)) else p00
        // 15 Nov: a position protected in slices ([protectSliced]): what the other slices cover is not this one's to cover again.
        // Review (R3): a sibling set seconds ago that the books do not show yet: nothing placed or ended this look; the next look,
        // with books that show it (or show it refused), decides. Never two slices for the same units, never one ended for nothing.
        // (F3: it waits even when there is room - so, if that sibling was refused, this one can then cover all of it.)
        if (siblingCoverKnown(p00, book, gttBook) == null) return p00
        val sib = siblingCover(p00, book, gttBook)
        val room = (abs(net) - sib).coerceAtLeast(0)
        if (sib > 0 && room <= 0) return p0.copy(active = false, note = com.optionslab.ira.ManualCoverRule.SLICE_DONE)
        val whole = com.optionslab.engine.risk.ExitQty.sendable(room, busy, abs(p0.qty), p0.lotSize)
        if (whole <= 0) {
            Alerts.post("${p0.symbol}: an exit already resting at Zerodha covers it, so its stop and target were not placed again.",
                Alerts.Kind.INFO, "Protection")
            return p0.copy(active = false, note = "covered by another exit")
        }
        // (Eighth round: only what Boss's lost exit leaves - [less] - beside it.)
        val qty = if (abs(less) >= abs(again)) whole
            else com.optionslab.engine.risk.ExitQty.sendable((minOf(abs(less), abs(net)) - sib).coerceAtLeast(0), busy, abs(p0.qty), p0.lotSize)
        if (qty <= 0) return p00
        val cut = qty < whole
        val p = p0.copy(qty = p0.qty.sign() * qty)
        val stopId = p.stop?.let { s0 ->
            try {
                placeTracked(Kite.Order(p.symbol, side, abs(p.qty), p.lotSize, p.product, "SL", stopLimit(s0, side, p.tick),
                    p.tick, p.exchange, "iraprotect", triggerPrice = s0), known)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: MaybeResting) {
                // (Sixth round: it may be resting - kept and looked for under a new id, never one placed beside it; its target the app's.)
                val lost = p.copy(id = newId(), stopOrderId = null, targetOrderId = null, note = STOP_LOST, appTarget = p.appTarget || p.target != null,
                    whole = if (cut) p0.qty.sign() * whole else null)
                Broker.noteLost(lostStopKey(lost), e.order, known, "${p.symbol}: its protection's stop", liveOnly = true)
                Alerts.post(maybeRestingWords(p.symbol), Alerts.Kind.ERROR, "Protection", throttle = true)
                return lost
            } catch (e: Exception) {
                // Usually the price opened through the stop (Zerodha refuses a stop on the wrong side of the price): said plainly.
                val last = runCatching { Broker.quotes(listOf("${p.exchange}:${p.symbol}"), lane = com.optionslab.ira.RateGate.Priority.SAFETY)["${p.exchange}:${p.symbol}"]?.last }.getOrNull()   // B5-D
                val through = last != null && last > 0 && (if (p.qty > 0) last <= s0 else last >= s0)
                unprotected(if (through) "${p.symbol} ${if (said == null) "opened" else "is"} at %.2f, already through its stop of %.2f, so the stop could not be placed again. Close it in Trade.".format(last, s0)
                    else "${p.symbol}: its stop could not be placed again${if (said == null) " at the open" else ""} (${e.message}). The position is NOT protected: set the protection again.")
                return p.copy(active = false, note = "stop not placed again")
            }
        }
        // 22 Nov 2026 (B6 review, M5): intraday, the target is never placed beside the stop (Zerodha refuses it for funds): the app's
        // ([Item.appTarget]). A target refused for funds beside the stop is the app's too; one refused otherwise is said (it was dropped
        // unsaid before - its failure swallowed) and only the stop rests.
        // (Eighth round: a stop put back for only part ([cut]): its target the app's too, so it grows with the protection.)
        var appTarget = (p.product == "MIS" || cut) && stopId != null && p.target != null
        var targetWhy: String? = null
        val targetId = if (appTarget) null else p.target?.let { t ->
            try {
                placeTracked(Kite.Order(p.symbol, side, abs(p.qty), p.lotSize, p.product, "LIMIT", Kite.onTick(t, p.tick, side),
                    p.tick, p.exchange, "iraprotect"), known + listOfNotNull(stopId))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                if (stopId != null && CheaperStrike.fundsRefused(e.message)) appTarget = true else targetWhy = e.message ?: "an error"
                null
            }
        }
        if (stopId == null && targetId == null) return null
        stopId?.let { Strategies.tagOwner("kite:$it", "Protection · stop") }
        targetId?.let { Strategies.tagOwner("kite:$it", "Protection · target") }
        // (Eighth round: put back for only part - the protection keeps its size ([whole]), marked [BOSS_CUT] until its stop grows ([growStop]).)
        val next = p.copy(id = newId(), stopOrderId = stopId, targetOrderId = targetId, whole = if (cut && stopId != null) p0.qty.sign() * whole else null,
            target = p.target.takeIf { targetId != null || appTarget }, appTarget = appTarget,
            note = if (cut && stopId != null) BOSS_CUT else "placed again for today")
        targetWhy?.let { why -> p.target?.let { t ->
            Alerts.post("${p.symbol}: its target ${"%.2f".format(java.util.Locale.ENGLISH, t)} could not be placed again at Zerodha ($why); only its stop " +
                "rests there. Set the target again if you want it.", Alerts.Kind.ERROR, "Protection") } }
        Alerts.post((said?.let { "${p.symbol}: $it (${next.describe()})." }
            ?: "${p.symbol}: its stop and target ended with yesterday's session and were placed again at Zerodha (${next.describe()}).") +
            (if (next.note == BOSS_CUT) " For $qty of $whole only: your exit on it whose reply was lost may be selling the rest. The stop grows " +
                "to $whole once that exit is known not to have gone." else ""),
            Alerts.Kind.SUCCESS, "Protection")
        return next
    }

    /**
     * 23 Nov 2026 (B6 review, eighth round, m4): [p]'s stop, put back for only part while Boss's own exit on it whose reply was lost might be
     * selling the rest ([BOSS_CUT]), once that exit is known (found, or given up on): grown (a modify, never a cancel) to the protection's
     * size - at most what is held less every other exit on it, in whole lots - or, his exit having sold the rest, the protection brought down
     * to its stop. In the contract's exit turn, on fresh reads; one not read is asked again next look.
     */
    private suspend fun growStop(p: Item): Item = ExitCoordinator.turn(p.exchange, p.symbol, "protection") { growStopInTurn(p) }

    private suspend fun growStopInTurn(p: Item): Item {
        val want = abs(p.whole ?: return p)
        val side = if (p.qty > 0) Kite.Side.SELL else Kite.Side.BUY
        val book = runCatching { ExitCoordinator.orders(p.exchange, p.symbol, "protection") }.getOrNull() ?: return p
        val gtts = runCatching { ExitCoordinator.gtts(p.exchange, p.symbol, "protection") }.getOrNull() ?: return p
        val so = book.firstOrNull { it.id == p.stopOrderId } ?: return p
        // (Its stop sold, or is selling: the regular look books it - and says what is left without a stop.)
        if (!so.working || so.filled > 0) return p.copy(whole = null, note = "")
        val held = heldAgain(p, book, lagSafe = true)?.let { SquareOffExit.lessBossLost(it, p.symbol, p.product, p.exchange) } ?: return p
        if (held == 0 || held.sign() != p.qty.sign()) return p
        if (siblingCoverKnown(p, book, gtts) == null) return p
        val busy = othersBusy(p, side, book, gtts, listOfNotNull(p.stopOrderId, p.targetOrderId))
        val lot = p.lotSize.coerceAtLeast(1)
        val cap = (abs(held) - siblingCover(p, book, gtts)).coerceAtLeast(0)
        // (Review: done only when what is held shows his exit sold the rest; while other working exits merely cover it - his found order
        // still working, say - it keeps its whole size and is asked again next look, so it grows if they end unfilled.)
        if (cap <= so.qty) return p.copy(whole = null, note = "")
        val to = minOf(want, cap - busy) / lot * lot
        if (to <= so.qty) return p
        // (Ninth round: never grown on positions that do not give the day's quantities - the lag guard cannot count a fill they trail; said.)
        if (!dayQtyKnown(p)) {
            Alerts.post("${p.symbol}: its stop covers ${so.qty} of $want; it grows once Zerodha's positions show today's quantities.",
                Alerts.Kind.ERROR, "Protection", throttle = true)
            return p
        }
        val r = runCatching { Broker.modify(so, to, so.type, if (so.type == "SL-M") null else so.price, so.trigger) }
        if (r.isFailure) {
            Alerts.post("${p.symbol}: its stop covers ${so.qty} of $want and could not be grown (${r.exceptionOrNull()?.message?.take(120)}); " +
                "retrying.", Alerts.Kind.ERROR, "Protection", throttle = true)
            return p
        }
        Alerts.post("${p.symbol}: your exit on it whose reply was lost did not sell it all, so its stop now covers $to.", Alerts.Kind.SUCCESS, "Protection")
        return if (to < want) p.copy(qty = p.qty.sign() * to) else p.copy(qty = p.qty.sign() * to, whole = null, note = "")
    }

    /**
     * 20 Oct, enhancement round 2: [p]'s contract as Zerodha holds it now (net, this product); null when it could not be read.
     * 21 Oct, enhancement round 3 (idea 1): checked against the fills of [orders], the orders read just before it
     * ([ExitCoordinator.held]: an exit that filled a moment ago is never covered again while the positions catch up).
     */
    private suspend fun heldAgain(p: Item, orders: List<Broker.OrderRow>, lagSafe: Boolean = false): Int? = runCatching {
        // 15 Nov, review: [lagSafe] (a stop put back after Boss's exit): an exit fill the positions do not show yet is counted as
        // sold whatever the lag guard's switch says - it only ever lowers what is placed.
        ExitCoordinator.held(p.exchange, p.symbol, p.product, "protection", orders, forceLag = lagSafe)
    }.getOrNull()

    /**
     * Place one exit. A lost reply is looked up in the order book (not adopting any of [known]), so an
     * order that reached Zerodha is never left resting untracked; throws when it was not placed, or when
     * its fate cannot be told (the message says to check the order book).
     */
    private suspend fun placeTracked(o: Kite.Order, known: Collection<String>): String = try {
        Broker.placeOrder(o, exit = true)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e
    } catch (e: Exception) {
        if (Broker.definite(e)) throw e
        // (23 Nov 2026, B6 follow-ups, item 1: Kite's unsure answer - [Broker.KiteUnsure] - was looked for in the door already: straight to the
        // lost-reply route below, never a second ~4 s search. Sixth round: its fate unknown - [MaybeResting], so a stop is looked for and
        // none placed beside it.)
        val found = if (e is Broker.KiteUnsure) null else try { Broker.findRecentRetrying(o, known) } catch (x: kotlinx.coroutines.CancellationException) { throw x
        } catch (_: Exception) {
            throw MaybeResting(o, "${e.message}; the order book could not be read, so a ${o.orderType} ${o.side.name} of ${o.tradingSymbol} " +
                "may be resting at Zerodha: check the order book")
        }
        found ?: throw MaybeResting(o, "${e.message}; no matching order found at Zerodha yet, so it may still be resting there")
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
    suspend fun tick(fast: Boolean = false, stream: Boolean = false) {
        var wait = look(fast, stream)
        // 20 Nov 2026 (deep review: the second read and the other exit's retries waited while holding [lock]): a look that wants another
        // read soon ([askFollowUp]) gets it as a follow-up look after the pause, made with the lock let go meanwhile - never a wait
        // under it. At most [FOLLOW_UPS]; a stream look never asks.
        // (Final review: after a fast look the follow-up is made apart, on this object's own worker - FastPath's live lane is never
        // held up by it, and the confirming read still comes [FLAT_RECHECK_MS] later, not at the next regular look.)
        if (fast) {
            // (Final review: a failure of the follow-up - a disk or Keystore fault saving the list - is logged, never thrown out of the
            // worker, which would end the app and the position watch with it.)
            if (wait > 0L && followApart.compareAndSet(false, true)) followJob = followScope.launch {
                try {
                    kotlinx.coroutines.delay(wait)
                    runCatching { tick() }.onFailure { e ->
                        // (Only this worker's own cancel ends it quietly; any other failure, a timeout's included, is told.)
                        if (e is kotlinx.coroutines.CancellationException && !kotlinx.coroutines.currentCoroutineContext().isActive) throw e
                        runCatching { Diag.record("protection", "a fast look's follow-up failed: ${e.javaClass.simpleName}: ${e.message}") }
                        // The watch's health counts it, as every other stop check's failure (throttled by itself).
                        runCatching { com.optionslab.app.work.Tasks.stepFailed("stops and targets (follow-up)", e) }
                    }
                } finally { followApart.set(false) }
            }
            return
        }
        var rounds = 0
        while (wait > 0L && rounds < FOLLOW_UPS) {
            kotlinx.coroutines.delay(wait)
            rounds++
            wait = look(fast = false, stream = false)
        }
    }

    /** One look, under [lock]; returns the pause a follow-up look was asked after (0: none). */
    private suspend fun look(fast: Boolean, stream: Boolean): Long = lock.withLock {
        streamLook = fast && stream
        pass = PassReads(streamLook)
        followUpMs = 0L
        try { tickLocked(fast); WatchClock.stopsChecked() } finally { streamLook = false; pass = null }
        followUpMs
    }

    /** 20 Nov 2026: the pause a follow-up look was asked after in the look in progress (the shortest asked). Under [lock] only. */
    @Volatile private var followUpMs = 0L
    private fun askFollowUp(ms: Long) { if (!streamLook) followUpMs = if (followUpMs == 0L) ms else minOf(followUpMs, ms) }

    /** 20 Nov 2026 (final review): a fast look's follow-up, made apart; at most one waiting at a time. */
    private val followScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO +
        kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            runCatching { Diag.record("protection", "follow-up worker: ${e.javaClass.simpleName}: ${e.message}") }
        })
    private val followApart = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var followJob: kotlinx.coroutines.Job? = null

    /** TEST ONLY: whether a fast look's follow-up is waiting or running. */
    internal fun followUpPendingForTest(): Boolean = followApart.get()

    /** TEST ONLY: a fast look's follow-up cancelled and forgotten (no worker left behind for the next test). */
    internal fun resetFollowUpForTest() {
        followJob?.let { j -> kotlinx.coroutines.runBlocking { j.cancelAndJoin() } }
        followJob = null; followApart.set(false)
    }

    /** 20 Nov 2026: follow-up looks after one [tick] at most. */
    private const val FOLLOW_UPS = 4

    /**
     * 20 Nov 2026 (audit: Protections read Zerodha's orders and positions again for every protection, one after another, in one look):
     * the look's reads of the order book, the positions and the GTT list, read once and shared by every protection in the look while
     * nothing was sent or changed at Zerodha since ([Broker.writeGen]: every order, change or cancel from this phone, and every order
     * update on the stream, bumps it - the next protection then reads again, so one never acts on a book that misses another's order).
     * The orders are always read before the positions (a fill between the two reads then shows as still working, never as a position
     * with nothing on its way out). A failed read is kept for the look too (one dead read never costs every protection its timeout).
     * Set and cleared under [lock] by [tick]; null outside a look (each call reads, as before).
     */
    private class PassReads(val stream: Boolean) {
        private var orders: Pair<Long, Result<List<Broker.OrderRow>>>? = null
        private var positions: Pair<Long, Result<Broker.Positions>>? = null
        private var gtts: Pair<Long, Result<List<Broker.GttRow>>>? = null
        /** The order of the reads in this look: a positions read is reused only when it was made after the orders read in use. */
        private var seq = 0L
        private var ordersSeq = -1L
        private var positionsSeq = -1L

        suspend fun orders(): List<Broker.OrderRow> {
            val g = Broker.writeGen()
            orders?.takeIf { it.first == g }?.let { return it.second.getOrThrow() }
            val r = readCatching { if (stream) Broker.ordersRecon() else Broker.orders() }
            orders = g to r
            ordersSeq = ++seq
            return r.getOrThrow()
        }

        suspend fun positions(): Broker.Positions {
            val g = Broker.writeGen()
            positions?.takeIf { it.first == g && positionsSeq > ordersSeq }?.let { return it.second.getOrThrow() }
            val r = readCatching { if (stream) Broker.positionsRecon() else Broker.positionBook() }
            positions = g to r
            positionsSeq = ++seq
            return r.getOrThrow()
        }

        suspend fun gtts(): List<Broker.GttRow> {
            val g = Broker.writeGen()
            gtts?.takeIf { it.first == g }?.let { return it.second.getOrThrow() }
            val r = readCatching { Broker.gtts() }
            gtts = g to r
            return r.getOrThrow()
        }

        private inline fun <T> readCatching(read: () -> T): Result<T> = try { Result.success(read()) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }

    /** The look in progress's shared reads ([PassReads]); null outside [tick]. Read and written under [lock] only. */
    @Volatile private var pass: PassReads? = null

    private suspend fun passOrders(stream: Boolean): List<Broker.OrderRow> = pass?.orders() ?: if (stream) Broker.ordersRecon() else Broker.orders()
    private suspend fun passPositions(stream: Boolean): Broker.Positions = pass?.positions() ?: if (stream) Broker.positionsRecon() else Broker.positionBook()
    private suspend fun passGtts(): List<Broker.GttRow> = pass?.gtts() ?: Broker.gtts()

    /**
     * 20 Nov 2026 (audit: a malformed or partial positions reply was read as "no positions"): what [book] shows held of [p]'s contract
     * (net, its product), or null when it cannot be told: a read with no row at all for a contract protected today is a bad read
     * (Zerodha lists every contract held or traded today, at 0 once closed), never "flat". A protection from an earlier day with no row
     * is flat (its position closed on that day). A read that left [p]'s row out as malformed ([Broker.Positions.unknown]): null too.
     */
    private fun heldIn(book: Broker.Positions, p: Item): Int? {
        if (book.unknown(p.symbol)) return null
        val rows = book.net.filter { it.symbol == p.symbol && it.exchange == p.exchange }
        if (rows.isEmpty()) return if (p.id < todayStartMs()) 0 else null
        return rows.filter { it.product == p.product }.sumOf { it.qty }
    }

    private fun todayStartMs(): Long = Market.today().atStartOfDay(com.optionslab.engine.IST).toInstant().toEpochMilli()

    /**
     * 20 Nov 2026 (audit, as the arms' since 18 Nov): [net] (what a read shows held of [p]'s contract) as the look may act on it. A
     * read showing it closed or turned round ends nothing alone: only a second read at least [FLAT_AGREE_MS] later that agrees (no read
     * between showing it held) confirms it. Until then: null (not known), so the protection, its stop and target stay as they are. A
     * stream look (Zerodha's books as last read) neither starts nor confirms it. Memory only (a restart starts the count again).
     */
    private fun confirmedHeld(p: Item, net: Int?): Int? {
        if (net == null) return null
        if (net != 0 && net.sign() == p.qty.sign()) { flatSince.remove(p.id); return net }
        if (streamLook) return null
        if (flatSince.size > 500) flatSince.clear()
        val now = nowMs()
        val first = flatSince.putIfAbsent(p.id, now) ?: return null
        // (Kept once confirmed: an exit whose cancel is not confirmed this look is tried again on the next without a new count.)
        return if (now - first >= FLAT_AGREE_MS) net else null
    }

    /**
     * 20 Nov 2026 (review: waiting for the next look left a stop resting on a position Boss had closed for up to half a minute): what
     * [p]'s look may act on of what is held - [confirmedHeld] on the look's read. When that read is the first to show it closed or
     * turned round, a follow-up look is asked ([askFollowUp]) [FLAT_RECHECK_MS] later: [tick] makes it after letting go of [lock] (deep
     * review: never waiting while holding it), with a separate read that confirms it when it agrees. Never in a stream look.
     */
    private fun heldConfirmed(p: Item, book: Broker.Positions?): Int? {
        val first = confirmedHeld(p, book?.let { heldIn(it, p) })
        // (Review: asked for the moment it can be confirmed, so a shorter follow-up asked by another protection is not taken for it.)
        if (first == null && !streamLook) flatSince[p.id]?.let { at -> askFollowUp((FLAT_RECHECK_MS - (nowMs() - at)).coerceIn(OCO_GAP_MS, FLAT_RECHECK_MS)) }
        return first
    }

    /** 20 Nov 2026: the pause before the follow-up look's read (past [Broker.SHARE_MS], so it is a read of its own). */
    private const val FLAT_RECHECK_MS = 2_100L

    /** 20 Nov 2026 (review): whether [p]'s first closed read is still fresh (its confirming look is due, not reads that keep failing). */
    private fun freshFlat(p: Item): Boolean = flatSince[p.id]?.let { nowMs() - it in 0 until FLAT_AGREE_MS + FLAT_RECHECK_MS + 1_000L } == true

    /** When each protection (by id) was first read closed or turned round ([confirmedHeld]). Memory only. */
    private val flatSince = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** 20 Nov 2026: how far apart the two agreeing reads must be (two separate reads of Zerodha, never one shared read). */
    internal const val FLAT_AGREE_MS = 2_000L

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

    /**
     * 20 Nov 2026 (audit: an intraday protection rests its stop and target as two orders - Zerodha's GTT, whose two legs cancel each
     * other at Zerodha, takes only carried (NRML/CNC) positions - so both could fill and the second open a position the other way
     * round): [p]'s other exit [id] ([row]: as the look's order book shows it; null: not in it), once [stopFilled] (or its target)
     * filled, taken out and confirmed out - cancelled, and its state read back, up to [OCO_TRIES] times in this look. True once it is
     * confirmed gone (cancelled, rejected or filled); false while it may still rest: the look keeps the protection and tries again on
     * the next one (every 15 s, and at once on Zerodha's order update), and Boss is told loudly on the first failure and every
     * [OCO_SAY_MS] after, so he can cancel it in Kite himself. A read that fails is never taken for gone.
     */
    private suspend fun ocoOut(p: Item, id: String, row: Broker.OrderRow?, stopFilled: Boolean): Boolean {
        // Its state as Zerodha's history of it says: [NOT_FOUND] when Kite does not know the order at all (an earlier session's:
        // over, nothing of it rests); null when the history is empty (not known: never taken for gone); a failed read throws.
        suspend fun state(): String? = try { Broker.orderState(id)?.status } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Broker.KiteError) { if (e.type == "InputException") NOT_FOUND else throw e }
        fun over(st: String?) = st == NOT_FOUND || st in GONE
        val variety = row?.variety ?: "regular"
        var gone = false
        var why: String? = null
        try {
            // Not in the order book just read (a book trailing a fresh order): its own state is read first. Review: a row neither
            // working nor ended (CANCEL PENDING, a modify being validated) is read again too, never taken for gone.
            // Deep review: one cancel and one read back per look - never a wait under [lock]; not gone, a follow-up look is asked.
            if (row != null && row.status in GONE) gone = true
            else if ((row == null || !row.working) && over(state())) gone = true
            else {
                runCatching { Broker.cancel(id, variety) }.onFailure { why = it.message }
                if (over(state())) gone = true
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { why = e.message }
        if (gone) { ocoPending.remove(p.id); return true }
        val now = nowMs()
        // Review: every cancel taken (no error), the order perhaps still on its way out at the exchange: said only once it has still
        // been found resting for [OCO_PENDING_MS], never at once (a false alarm while it is being cancelled). A clock moved back: said.
        val since = ocoPending.putIfAbsent(p.id, now) ?: now
        // Follow-up looks for its first tries only (three in all), so it never crowds out another protection's confirming read.
        if (now - since in 0 until 2 * OCO_GAP_MS) askFollowUp(OCO_GAP_MS)
        if (why == null && now - since in 0 until OCO_PENDING_MS) return false
        val last = ocoSaid[p.id]
        if (last == null || now - last !in 0 until OCO_SAY_MS) {
            ocoSaid[p.id] = now
            val (done, left) = if (stopFilled) "stop" to "target" else "target" to "stop"
            unprotected("${p.symbol}: its $done filled at Zerodha, but its $left (${p.exitSide.lowercase()} of ${abs(p.qty)}) could not be " +
                "cancelled${why?.let { " ($it)" } ?: ""}. If it fills it opens a position the other way round: cancel it in Kite now. " +
                "The app keeps trying.")
        }
        return false
    }

    /**
     * 22 Nov 2026 (B6 review, M5): said when a protection's target is the app's own ([Item.appTarget]) - every intraday stop and target since
     * Zerodha refuses a second full sale beside the stop (20 Nov 2026: before, said as two orders, and the target was never placed).
     */
    internal const val TARGET_BY_APP = "Zerodha does not hold a target beside the stop (it would take a second sale for a new position), so " +
        "only the stop rests there: the app watches the target and, when it is reached, takes the stop out and sells. If the phone is off " +
        "then, the target is not taken (the stop still is)."

    /**
     * 22 Nov 2026 (B6 review, M5): by protection id, when a stream look saw its app-watched target reached ([Item.appTarget]): the look apart it
     * asked for ([TARGET_LOOK_MS]) sells within [TARGET_HIT_KEEP_MS] of it even if the price has just slipped back. Memory only.
     */
    private val targetHit = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private const val TARGET_LOOK_MS = 1L
    private const val TARGET_HIT_KEEP_MS = 5_000L
    /** 22 Nov 2026 (B6 review): the key a target sale whose reply was lost is looked for under ([Broker.noteLost]), by protection id. */
    private const val TARGET_LOST = "prottarget:"
    /**
     * 22 Nov 2026 (B6 review, second round: a stop whose cancel showed pending at first was left out, the next look put it back as cancelled
     * "for your exit", and the look after cancelled it again - the target never sold, its stop in and out): the target sale's own mark on the
     * stop it cancels, by stop id, beside its taken-over mark (kept on disk the same way). The next look that finds that stop cancelled with
     * the target still reached, or this mark younger than [TARGET_HIT_KEEP_MS], sells at once ([sellAtTarget], out) instead of putting it back.
     */
    internal const val TARGET_CANCEL = "prottcancel:"
    /** 22 Nov 2026 (B6 review, second round): a look about a second after a target sale whose stop's cancel had not shown yet. */
    private const val TARGET_PENDING_LOOK_MS = 1_000L
    /**
     * 22 Nov 2026 (B6 review, second round, m-1: a target sale held back - another exit covering it, the gates, a square-off's claim - had every
     * stream tick ask a regular look apart, back to back, each several Zerodha reads outside the safety lane): by protection id, until when
     * (the market's clock) its stream looks neither mark its target nor ask a look apart, after a try that took nothing out. The regular looks
     * still try it. Memory only.
     */
    private val targetWait = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private const val TARGET_WAIT_MS = 12_000L

    /** TEST ONLY: whether a stream look marked protection [id]'s target reached ([targetHit]). */
    internal fun targetMarkedForTest(id: Long): Boolean = targetHit.containsKey(id)

    /** TEST ONLY: run just before a target sale's cancel of its stop goes. */
    @Volatile internal var testBeforeTargetCancel: (() -> Unit)? = null

    /**
     * 22 Nov 2026 (B6 review): the app's own target sales ([sellAtTarget]) by protection id - symbol, product, quantity, when (the square-offs'
     * clock), and the key its lost reply is looked for under, if lost. Counted as going for the square-offs ([targetSalesGoing]). Memory only.
     */
    private data class TargetSale(val symbol: String, val product: String, val qty: Int, val atMs: Long, val lostKey: String? = null,
                                  /** 23 Nov 2026: Zerodha took it (placed, or a lost one found) - its fill is in an exit's lag guard. */
                                  val placed: Boolean = false)
    private val targetSales = java.util.concurrent.ConcurrentHashMap<Long, TargetSale>()
    private fun gapClock(): Long = runCatching { SquareOffExit.clock() }.getOrDefault(System.currentTimeMillis())

    /**
     * 22 Nov 2026 (B6 review): the square-offs' look ([SquareOffExit.lostPendingProviders], "protections") at the app's own target sales on
     * Zerodha's [symbol] [product] row, by quantity: each from just before it goes until the square-offs' gap after ([SquareOffExit.gapMs]:
     * [SquareOffExit.GAP_MS] in the app - the books may not show it yet), and one whose reply was lost while it is looked for
     * ([Broker.lostPending]). Memory only: never the lock, the disk or the network.
     * (Second round, m-4: those sales are memory only, so after a restart a lost one counted nowhere.) Negative (not known) for a row with a
     * lost target sale's saved mark ([TARGET_LOST], read back with the list) younger than [Broker.LOST_SEARCH_MS] that this process does not
     * know the sale of - by then Zerodha's order book shows any sale it took.
     */
    internal fun targetSalesGoing(symbol: String, product: String, booked: Boolean = true): Int {
        val now = gapClock()
        targetSales.entries.removeIf { (_, t) -> now - t.atMs !in 0 until SquareOffExit.gapMs && (t.lostKey == null || !Broker.lostPending(t.lostKey)) }
        val wall = System.currentTimeMillis()
        // (Review: before the list is read - the start's positions step reads it first thing - any row; with the marks' file not read yet,
        // a row with an app-watched target: a lost sale saved before the restart may not be known. Only within [Broker.LOST_SEARCH_MS] of
        // this process's start - by then Zerodha's order book shows any sale it took.)
        val listNow = published
        if (wall < startUnknownUntil && (listNow == null || (!marksLoaded &&
                listNow.any { it.active && it.appTarget && it.symbol == symbol && it.product == product }))) return -1
        val list = listNow.orEmpty()
        val unknown = takenOver.entries.any { (k, at) ->
            k.startsWith(TARGET_LOST) && wall - at in 0 until Broker.LOST_SEARCH_MS && targetSales.values.none { it.lostKey == k } &&
                k.removePrefix(TARGET_LOST).toLongOrNull()?.let { id -> list.any { it.id == id && it.symbol == symbol && it.product == product } } == true
        }
        if (unknown) return -1
        return targetSales.values.filter { it.symbol == symbol && it.product == product && (booked || !it.placed) }.sumOf { it.qty }
    }

    /** 22 Nov 2026 (B6 review, second round): until when (wall clock) a lost target sale saved before this process started may not be known. */
    @Volatile private var startUnknownUntil = 0L

    /** TEST ONLY: as a process that started [ms] ago ([targetSalesGoing]). */
    internal fun startedForTest(ms: Long) { startUnknownUntil = System.currentTimeMillis() - ms + Broker.LOST_SEARCH_MS }

    /** TEST ONLY: the target sales counted for the square-offs made [ms] older. */
    internal fun ageTargetSalesForTest(ms: Long) { targetSales.replaceAll { _, t -> t.copy(atMs = t.atMs - ms) } }

    /**
     * 22 Nov 2026 (B6 review, M5: Zerodha takes no second full sale beside the resting stop, so an intraday target was never placed - and
     * nothing watched it): [p]'s own target ([Item.appTarget]) reached at [ltp] - sold by the app, in the contract's one exit turn. What may be
     * sold - held (the lag guard on), less every other exit working on it and what the other slices cover - and the order's gates are worked
     * out FIRST, so its stop [so] comes out only when a sale will go. The stop's cancel is marked the app's own first ([noteTakenOver]: kept
     * across a restart) and confirmed: filled meanwhile, it was the exit; filled in part, only the rest is sold; still working or not known,
     * nothing is sold (the next look tries again). The sale refused: the stop is put back at once ([restAgainInTurn]). Its reply lost and not
     * found: nothing is put back beside a sale that may be selling until the book shows it or lets it go ([TARGET_LOST]). Otherwise the stop
     * stays out while the sale fills, and if the position is still held once it is done (refused later, part filled), the look puts the stop
     * back ([exitFilledFor], the taken-over branch). [p] as it then stands. Never two exits; never a stop out unless its sale goes.
     * [out] (second round): the stop came out for this sale already (its cancel showed a moment late, [TARGET_CANCEL]) - the sale only.
     */
    private suspend fun sellAtTarget(p: Item, so: Broker.OrderRow, ltp: Double?, out: Boolean = false): Item {
        // (22 Nov 2026, with the square-offs' protocol: one seller at a time on the row - taken before the stop comes out, let go in a finally;
        // held by a square-off: nothing taken out, it sells the row.)
        var claim = SquareOffExit.claimExit(p.exchange, p.symbol, p.product, CLAIM_OWNER)
        // (23 Nov 2026, B6 review, fifth round, #7: one of the protections' own stops going back holds it only a moment - never waited for
        // under the look's lock: skipped this look, its reached mark kept, and the stream looks leave it be only about a second. Let go
        // between the two reads: taken now.)
        var holder = if (claim == null) SquareOffExit.exitClaimedBy(p.exchange, p.symbol, p.product) else null
        if (claim == null && holder == null) {
            claim = SquareOffExit.claimExit(p.exchange, p.symbol, p.product, CLAIM_OWNER)
            if (claim == null) holder = SquareOffExit.exitClaimedBy(p.exchange, p.symbol, p.product)
        }
        // (Second review: any of the app's stops going back - its name ends "-stop" - holds the row only a moment. Sixth round: a look about a
        // second on is asked for, so the target is tried again even when the price slips back meanwhile - its reached mark kept.)
        if (claim == null && SquareOffExit.isStopOwner(holder)) {
            targetWait[p.id] = nowMs() + TARGET_PENDING_LOOK_MS
            askFollowUp(TARGET_PENDING_LOOK_MS)
            return p
        }
        // (Sixth round: the mark of the stop this sale took out ends only now that the sale has the row - a look skipped for a stop going
        // back never lets the next look put that stop back.)
        if (out && claim != null) forgetTakenOver(listOf(TARGET_CANCEL + so.id))
        targetHit.remove(p.id)
        // (Second round, m-1: until this try takes something out, the stream looks leave it be for a while - [targetWait].)
        targetWait[p.id] = nowMs() + TARGET_WAIT_MS
        if (claim == null) return p
        try { return sellAtTargetClaimed(p, so, ltp, out) } finally { claim.release() }
    }

    /** 22 Nov 2026 (B6 review): the protections' name on a position row's exit claim ([SquareOffExit.claimExit]). */
    internal const val CLAIM_OWNER = "protections"

    /** 22 Nov 2026 (B6 review, second round, m-2): another seller holds [p]'s position row ([SquareOffExit.claimExit]) - no stop goes back. */
    private fun rowClaimedByOther(p: Item): Boolean =
        SquareOffExit.exitClaimedBy(p.exchange, p.symbol, p.product)?.let { it != CLAIM_OWNER && it != STOP_CLAIM_OWNER } == true

    /** [sellAtTarget] with the row's exit claim held. */
    private suspend fun sellAtTargetClaimed(p: Item, so: Broker.OrderRow, ltp: Double?, out: Boolean): Item = ExitCoordinator.turn(p.exchange, p.symbol, "protection target") {
        // Boss is exiting it by hand, or a square-off's sale is on its way: theirs.
        if (ArmLive.handsOffNow(p.exchange, p.symbol) || SquareOffExit.inGap(p.exchange, p.symbol, p.product)) return p
        val side = if (p.qty > 0) Kite.Side.SELL else Kite.Side.BUY
        val book = runCatching { ExitCoordinator.orders(p.exchange, p.symbol, "protection target") }.getOrNull() ?: return p
        val stop = book.firstOrNull { it.id == so.id }?.takeIf { if (out) it.status == "CANCELLED" else it.working } ?: return p
        val gttBook = runCatching { ExitCoordinator.gtts(p.exchange, p.symbol, "protection target") }.getOrNull() ?: return p
        // (23 Nov 2026, B6 review, eighth round, M1: less what Boss's own exit whose reply was lost may be selling - never sold beside it.)
        val held = heldAgain(p, book, lagSafe = true)?.let { SquareOffExit.lessBossLost(it, p.symbol, p.product, p.exchange) } ?: return p
        if (held == 0 || held.sign() != p.qty.sign()) return p
        if (siblingCoverKnown(p, book, gttBook) == null) return p
        val room = (abs(held) - siblingCover(p, book, gttBook)).coerceAtLeast(0)
        val busy = othersBusy(p, side, book, gttBook, listOfNotNull(p.stopOrderId, p.targetOrderId))
        val planned = com.optionslab.engine.risk.ExitQty.sendable(room, busy, abs(p.qty), p.lotSize)
        if (planned <= 0) return p                                         // covered by another exit: nothing taken out
        val o = Kite.Order(p.symbol, side, planned, p.lotSize, p.product, "MARKET", null, p.tick, p.exchange, "iraprotect")
        val bad = Kite.refusals(o, AppSettings.load().limits(), Broker.sentToday(), false, exit = true)
        if (bad.isNotEmpty()) {
            Alerts.post("${p.symbol}: its target was reached but the sale could not be sent (${bad.joinToString("; ")}); " +
                (if (out) "its stop, taken out for that sale, is put back on the next look. " else "its stop stays at Zerodha. ") +
                "Close it in Trade if you want it out.", Alerts.Kind.ERROR, "Protection", throttle = true)
            return p
        }
        // (Out already: its fills before the cancel are in what is held, read after it.)
        val filled = if (out) 0 else {
            // (Second round: this sale's own cancel of it went less than [TAKEN_SETTLE_MS] ago and has not shown yet - never a second cancel:
            // Zerodha refuses one while the first is processed, and the stop's end would then read as Boss's. Its state only; a stop still
            // working [TAKEN_SETTLE_MS] on ends both marks ([endIfSettled]) and the next try cancels afresh.)
            val sent = takenOver[TARGET_CANCEL + stop.id]?.let { System.currentTimeMillis() - it in 0 until TAKEN_SETTLE_MS } == true
            // (Second round, m-5: a mark another of the app's paths made on it already - a square-off's, Boss's exit - is never taken back here.)
            val premarked = isTakenOver(stop.id) && !isTakenOver(TARGET_CANCEL + stop.id)
            // (Review: only Zerodha's own refusal of the cancel - the order not open (an input error) - or a cancel that never left the phone
            // says the stop was cancelled by someone else; any other refusal may have come after Zerodha took the app's cancel.)
            var notOurs = false
            val refused = if (sent) false else {
                noteTakenOver(listOf(stop.id, TARGET_CANCEL + stop.id))
                targetWait.remove(p.id)                                    // (something taken out: the stream looks watch it again)
                testBeforeTargetCancel?.invoke()
                try { Broker.cancel(stop.id, stop.variety); false } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Exception) {
                        notOurs = e is Broker.NotSent || e is Broker.NotLoggedIn || (e is Broker.KiteError && e.type == "InputException")
                        Broker.definite(e)
                    }
            }
            suspend fun state() = try { Broker.orderState(stop.id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            // (Second round: Zerodha's book shows a cancel it took a moment after its reply - read again for about a second, as the arms do.)
            var st = state()
            var tries = 0
            while (!sent && !(refused && notOurs) && (st == null || st.status !in GONE) && tries++ < 5) {
                kotlinx.coroutines.delay(200)
                st = state() ?: st
            }
            // (Not known: nothing sold; a look in about a second reads it - cancelled: sold then, [TARGET_CANCEL]; working: the target tried again.
            // Asked once, for the cancel just sent - while it stays pending the regular looks read it, never one a second.)
            if (st == null) { if (!sent) askFollowUp(TARGET_PENDING_LOOK_MS); return p }
            // (The stop filled meanwhile: it was the exit - the next look books it.)
            if (st.status == "COMPLETE") return p
            if (refused && notOurs && st.status == "CANCELLED") {
                // Second round, m-5 (CLAUDE.md, "WHO CANCELLED A STOP"): Zerodha refused the app's cancel because the stop was already
                // cancelled - not the app's doing: Boss cancelled it in Kite as the target was reached. Its marks end (one another path made
                // stays), nothing is sold, and it is never put back: the next look reads it as his - the protection ends and he is told that
                // the position has no stop and its target is not watched any more.
                forgetTakenOver(listOfNotNull(stop.id.takeIf { !premarked }, TARGET_CANCEL + stop.id))
                runCatching { Diag.record("protection", "${p.symbol}: its target was reached, and its stop ${stop.id} was already cancelled (not by the app): left as it is") }
                return p
            }
            if (st.status != "CANCELLED" && st.status != "REJECTED") {
                // (Still working: nothing sold. A cancel Zerodha refused as such (the order not open to it) did not take - its marks end at
                // once. Pending, or another refusal that may have come after it took: a look in about a second reads it - it sells once the
                // stop shows cancelled; a stop still working [TAKEN_SETTLE_MS] on ends the marks.)
                if (refused && notOurs) forgetTakenOver(listOfNotNull(stop.id.takeIf { !premarked }, TARGET_CANCEL + stop.id))
                else if (!sent) askFollowUp(TARGET_PENDING_LOOK_MS)
                return p
            }
            // (What of it filled since the read that sized the sale - in part as it came out: only the rest is sold.)
            (st.filled - stop.filled).coerceAtLeast(0)
        }
        val lot = p.lotSize.coerceAtLeast(1)
        val sell = (planned - filled).coerceAtLeast(0) / lot * lot
        if (sell <= 0) return p
        val order = o.copy(quantity = sell)
        val known = book.map { it.id }
        // (22 Nov 2026, with the square-offs' protocol: counted as going FIRST, then a square-off's sale looked at - one selling it now: nothing
        // from here, and its stop put back by the next looks only beside what is left.)
        targetSales[p.id] = TargetSale(p.symbol, p.product, sell, gapClock())
        SquareOffExit.saleStarting(p.symbol, p.product)
        if (SquareOffExit.inGap(p.exchange, p.symbol, p.product)) { targetSales.remove(p.id); return p }
        // (Second round, m-6: Boss's exit from the app that began meanwhile is his - nothing sold beside it; the stop, out, is put back by the
        // looks once his exit is done, if the position is still held.)
        if (ArmLive.handsOffNow(p.exchange, p.symbol)) { targetSales.remove(p.id); return p }
        targetWait.remove(p.id)
        val id = try { Broker.placeOrder(order, exit = true) } catch (e: kotlinx.coroutines.CancellationException) { targetSales.remove(p.id); throw e } catch (e: Exception) {
            if (Broker.definite(e)) {
                targetSales.remove(p.id)
                // (Second round: refused, its stop goes back - never a sale tried again on the next look for this cancel.)
                forgetTakenOver(listOf(TARGET_CANCEL + stop.id))
                // Refused: the stop goes back now, for what is held (the lag guard on), as it was.
                val left = held.sign() * (abs(held) - filled).coerceAtLeast(0)
                val back = if (left == 0) null else restAgainInTurn(p.copy(qty = p.qty.sign() * minOf(abs(p.qty), abs(left))), left,
                    said = "its target was reached but Zerodha refused the sale (${e.message}), so its stop rests at Zerodha again", lagSafe = true)
                // (Zerodha's words never go through a format: a "%" in them would throw.)
                Alerts.error("${p.symbol}: its target ${"%.2f".format(java.util.Locale.ENGLISH, p.target ?: 0.0)} was reached but Zerodha refused the sale (${e.message})." +
                    when {
                        back != null && back.id != p.id && back.stopOrderId != null -> " Its stop rests at Zerodha again."
                        back != null && !back.active -> " Its stop could not be placed again: close it in Trade."
                        else -> " Its stop is put back on the next look; close it in Trade if you want it out."
                    },
                    "Protection")
                // (Placed again under a new id, or refused again - ended, said by [restAgainInTurn]: that is what it is now.)
                if (back != null && (back.id != p.id || !back.active)) { forgetTakenOver(listOf(stop.id)); return back }
                return p
            }
            // (23 Nov 2026, B6 follow-ups, item 1: Kite's unsure answer was looked for in the door already - straight to the lost sale's route.)
            val found = if (e is Broker.KiteUnsure) null
                else try { Broker.findRecentRetrying(order, known) } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (_: Exception) { null }
            found ?: run {
                // (Its reply lost and not found yet: it may be selling - no stop goes back beside it until the book shows it or lets it go.)
                Broker.noteLost(TARGET_LOST + p.id, order, known, "${p.symbol}: its target's sale")
                noteTakenOver(listOf(TARGET_LOST + p.id))                     // (kept on disk: a restart still waits for it)
                forgetTakenOver(listOf(TARGET_CANCEL + stop.id))              // (that sale, once known, decides - never a second one)
                targetSales[p.id] = TargetSale(p.symbol, p.product, sell, gapClock(), TARGET_LOST + p.id)
                Alerts.error("${p.symbol}: its target was reached and its stop taken out, but Zerodha's answer to the sale was lost. The app looks for " +
                    "it and puts the stop back only once it knows the sale did not go; check ${p.symbol} in Kite.", "Protection")
                return p
            }
        }
        // (Second round, m-4: counted from now, when Zerodha took it, for the square-offs' gap.)
        targetSales[p.id] = TargetSale(p.symbol, p.product, sell, gapClock(), placed = true)
        forgetTakenOver(listOf(TARGET_CANCEL + stop.id))
        runCatching { Strategies.tagOwner("kite:$id", "Protection · target") }
        // The stop stays out while the sale fills; still held once it is done (refused later, part filled): put back by the next looks.
        exitFilledFor(listOf(stop.id), mapOf(id to sell))
        Alerts.post("${p.symbol}: its target %.2f was reached (%s): its stop was taken out and $sell sold at market."
            .format(java.util.Locale.ENGLISH, p.target ?: 0.0, ltp?.let { "%.2f".format(java.util.Locale.ENGLISH, it) } ?: "seen a moment ago"),
            Alerts.Kind.SUCCESS, "Protection")
        return p
    }

    /** 20 Nov 2026: [ocoOut]'s mark for an order Kite does not know at all. */
    private const val NOT_FOUND = "NOT FOUND"

    /** 20 Nov 2026: the pause before [ocoOut]'s follow-up look, how long a taken cancel may rest unsaid, how often Boss is told. */
    private const val OCO_GAP_MS = 300L
    private const val OCO_PENDING_MS = 5_000L
    private const val OCO_SAY_MS = 2 * 60_000L

    /** 20 Nov 2026: when Boss was last told that a protection's other exit could not be taken out ([ocoOut]). Memory only. */
    private val ocoSaid = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** 20 Nov 2026 (review): when a protection's other exit was first found still resting after a cancel Zerodha took ([ocoOut]). */
    private val ocoPending = java.util.concurrent.ConcurrentHashMap<Long, Long>()

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

    /**
     * 23 Nov 2026 (B6 review, sixth round): a protection whose stop may be resting ([STOP_LOST]), on a regular look: its lost reply asked
     * after ([Broker.lookLost]: searched in the background). Found: it is the stop (the target, if any, the app's). Still looked for: nothing
     * placed beside it. Given up on - a read of the book that succeeded [Broker.LOST_SEARCH_MS] after it went did not show it (Boss told) -
     * placed again now, the careful way ([restAgain]), unless the position is no longer held. Not kept (a restart: memory only): looked for
     * again from when it went (its id is that time).
     */
    private suspend fun tickLostStop(p: Item): Item {
        if (streamLook) return p
        val key = lostStopKey(p)
        return when (val l = Broker.lookLost(key)) {
            is Broker.LostLook.Found -> {
                runCatching { Strategies.tagOwner("kite:${l.id}", "Protection · stop") }
                // (Seventh round, review: found may mean filled too - said as found, its state the next look's.)
                Alerts.post("${p.symbol}: its stop, whose answer from Zerodha was unsure, was found at Zerodha (order ${l.id}): watched as its stop" +
                    (if (p.target != null) "; its target is watched by the app." else "."), Alerts.Kind.SUCCESS, "Protection")
                p.copy(stopOrderId = l.id, note = "")
            }
            Broker.LostLook.Searching -> p
            null -> {
                if (p.stop == null) return p.copy(note = "")
                noteLostStop(p)
                p
            }
            Broker.LostLook.GaveUp -> placeGivenUp(p)
        }
    }

    /**
     * 23 Nov 2026 (B6 review, sixth round; eighth round, m3): [p]'s stop given up on (a good read 2 minutes after its unsure answer did not show
     * it): placed again the careful way ([restAgain]) unless the position is no longer held. Not placed this look - the positions or the books
     * not read, another seller on the row - kept as [STOP_GAVE_UP] and tried on the next look, never looked for (nor Boss told) again.
     */
    private suspend fun placeGivenUp(p: Item): Item {
        if (streamLook) return p
        val net = heldConfirmed(p, runCatching { passPositions(false) }.getOrNull())
            ?: return p.copy(note = STOP_GAVE_UP).also { noteGaveUpAgain(p) }
        if (net == 0 || net.sign() != p.qty.sign()) return p.copy(active = false, note = "position closed")
        val back = restAgain(p.copy(note = ""), net, said = "its stop was not at Zerodha 2 minutes after its unsure answer, so it was placed again",
            lagSafe = true)
        return if (back == null || (back.active && back.stopOrderId == null && back.note != STOP_LOST)) p.copy(note = STOP_GAVE_UP) else back
    }

    /** 23 Nov 2026 (B6 review, sixth round): a lost stop given up on whose position could not be read: noted again, so the next look decides. */
    private fun noteGaveUpAgain(p: Item) { runCatching { Diag.record("protection", "${p.symbol}: its unsure stop was not found; the positions could not be read, so it is placed on the next look") } }

    private suspend fun tickLiveOnce(p: Item): Item {
        if (!Broker.loggedIn) return p
        if (p.note == REMOVING) return if (cancelOrders(p)) p.copy(active = false, note = "removed") else p
        // 21 Oct, enhancement round 4 (idea 1): resting all day as one GTT: its own look.
        if (p.note == DAY_GTT) return tickDayGtt(p, streamLook)
        // (23 Nov 2026, B6 review, sixth round: its stop may be resting - looked for, never one placed beside it.)
        if (p.note == STOP_LOST) return tickLostStop(p)
        // (Eighth round, m3: given up on and waiting to be placed again - placed, never looked for again.)
        if (p.note == STOP_GAVE_UP) return placeGivenUp(p)
        // A stream look (round 2): Zerodha's books as last read (re-read after any change), the price from the fresh tick.
        val stream = streamLook
        // 20 Nov 2026 (audit): the look's one read of each book, shared by every protection while nothing changed ([PassReads]).
        val rows = passOrders(stream).associateBy { it.id }
        settleExits(rows.values)
        val so = p.stopOrderId?.let { rows[it] }; val to = p.targetOrderId?.let { rows[it] }
        val stopDone = so?.status == "COMPLETE"; val targetDone = to?.status == "COMPLETE"
        // 22 Nov 2026 (B6 review, M6): a taken-over mark whose order still works [TAKEN_SETTLE_MS] after it was made ends (its cancel never took).
        if (!stream) listOfNotNull(so, to).filter { it.working }.forEach { endIfSettled(it.id) }
        // 20 Nov 2026 (audit): a read with no row for the contract is not known ([heldIn]), never "flat".
        suspend fun heldRead(): Int? = runCatching { heldIn(passPositions(stream), p) }.getOrNull()
        // ... and a read showing it closed or turned round ends nothing until a second read agrees ([heldConfirmed]).
        suspend fun netNow(): Int? = heldConfirmed(p, runCatching { passPositions(stream) }.getOrNull())
        // 15 Nov, review: the other slices' cover ([protectSliced]) is only what they really have resting in the books just read
        // ([restingCover]); a sibling's GTT list is read only when one has a GTT (never in a stream look: none counted then).
        val gttSibling = siblings(p).any { it.gttId != null }
        val sibGtts = if (!stream && gttSibling) runCatching { passGtts() }.getOrNull() else null
        val sibKnownCover = siblingCoverKnown(p, rows.values, sibGtts)
        val sibCover = sibKnownCover ?: siblingCover(p, rows.values, sibGtts)
        // Review (N5): a sibling resting as a GTT whose list was not read (a stream look, a failed read) is not known: this pass makes
        // no shrink or end decision on the slices (no change is safer than a wrong one; any over-cover lasts until the next regular
        // look, which reads the GTTs).
        val sibKnown = !(gttSibling && sibGtts == null) && sibKnownCover != null
        if (stopDone || targetDone) {
            // One-cancels-other: finished only once the other exit is confirmed gone, or it
            // would still rest at Zerodha and could open a naked position when it fills.
            val other = if (stopDone) to else so
            // 20 Nov 2026 (audit: intraday the stop and target are two orders, so both could fill): taken out at once, a few tries in
            // this look, and Boss told loudly while it cannot be ([ocoOut]).
            val otherId = if (stopDone) p.targetOrderId else p.stopOrderId
            if (otherId != null && !ocoOut(p, otherId, other, stopDone)) return p
            ocoSaid.remove(p.id)
            // (22 Nov 2026, B6 review, second round: with the app's own target - no order at Zerodha - there was no other exit to cancel.)
            Alerts.post("${p.symbol}: ${if (stopDone) "stop" else "target"} filled at Zerodha; " +
                (if (otherId != null) "the other exit was cancelled." else if (stopDone && p.appTarget && p.target != null) "its target, which the app watched, ends with it." else "nothing else rested on it."),
                if (stopDone) Alerts.Kind.ERROR else Alerts.Kind.SUCCESS, "Protection")
            // The other exit may have part-filled before it was cancelled: what is left is said plainly (words only: one read).
            val left = heldRead()
            // 15 Nov: a position protected in slices - what the other slices still cover is protected; only the rest is said.
            val bare = if (left == null || left == 0 || left.sign() != p.qty.sign()) left else left.sign() * (abs(left) - sibCover).coerceAtLeast(0)
            if (left != null && bare != null && bare != 0) unprotected("${p.symbol}: after the ${if (stopDone) "stop" else "target"} filled, Zerodha shows ${p.product} net $left. " +
                if (left.sign() != p.qty.sign()) "That is a position the other way round: close it in Trade." else "That part is no longer protected.")
            return p.copy(active = false, note = if (stopDone) "stop filled" else "target filled")
        }
        // The position closed by hand (or flipped): the resting exits would now open a new position.
        // (Not in its first minute: the position book can trail a fresh fill.)
        // Review (R2): an id ahead of now (a clock once ahead) is never "its first minute" for ever.
        val net = if (nowMs() - p.id in 0L until 60_000L) null else netNow()
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
        // 15 Nov: one slice of a position protected in slices ([protectSliced]) that part-sold, where the other slices already cover
        // all that is held: its exits are cancelled (confirmed; else asked again next pass), so the slices together never sell more
        // than is held. It only takes away exits that cover nothing: the rest of the position keeps its stops (review: counted only
        // from stops really resting, so a sibling whose exit filled never makes this one give up its own).
        if (sibKnown && net != null && net != 0 && net.sign() == p.qty.sign() && sibCover > 0 && abs(net) <= sibCover) {
            if (!cancelOrders(p, "slice_not_needed")) return p
            runCatching { Diag.record("protection", "${p.symbol}: one slice of its protection (${abs(p.qty)}) ended: the other slices cover all $net held") }
            return p.copy(active = false, note = com.optionslab.ira.ManualCoverRule.SLICE_DONE)
        }
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
        // 15 Nov (Boss's diagnostics, 6 Nov: a stop the app cancelled for Boss's square-off stayed gone when the sale did not go): its
        // stop or target cancelled by the app itself to send Boss's exit ([noteTakenOver]) while the position is still held - the
        // exit refused, failed or only part filled - is placed again at its levels for what is held, once Boss's exit is done. Only
        // ever puts back the stop it had: it never covers more than is held less every other exit ([restAgain]).
        if (p.gttId == null && listOfNotNull(so, to).any { it.status == "CANCELLED" && takenOver.containsKey(it.id) }) {
            // 22 Nov 2026 (B6 review, M5): the app's own target sale whose reply was lost ([sellAtTarget]): nothing goes back beside it while it
            // may be selling; found, it is the exit (the stop put back only if the position is still held once it is done).
            when (val l = Broker.lookLost(TARGET_LOST + p.id)) {
                Broker.LostLook.Searching -> return p
                is Broker.LostLook.Found -> {
                    // (Second round, m-4: counted for the square-offs' gap from now, when it is known - after a restart too.)
                    targetSales[p.id] = TargetSale(p.symbol, p.product, l.qty, gapClock(), placed = true)
                    forgetTakenOver(listOfNotNull(TARGET_LOST + p.id, p.stopOrderId?.let { TARGET_CANCEL + it }))
                    exitFilledFor(listOfNotNull(p.stopOrderId), mapOf(l.id to l.qty)); return p
                }
                Broker.LostLook.GaveUp -> forgetTakenOver(listOf(TARGET_LOST + p.id))
                null -> {
                    // (After a restart its search, memory only, is gone; its mark is kept on disk: nothing goes back for [Broker.LOST_SEARCH_MS]
                    // from the sale - the order book shows any sale Zerodha took by then - and the lag-guarded reads below decide after.)
                    val at = takenOver[TARGET_LOST + p.id]
                    if (at != null && System.currentTimeMillis() - at in 0 until Broker.LOST_SEARCH_MS) return p
                }
            }
            if (net == null || net == 0 || net.sign() != p.qty.sign()) return p     // not read (or its first minute): next pass
            if (stream) return p                                                     // the regular look places (never a stream look)
            if (ArmLive.handsOffNow(p.exchange, p.symbol)) return p                  // Boss's exit is still going
            // (22 Nov 2026, B6 review: nor beside a square-off's sale on its way - its gap; the next look reads the books again. Second round,
            // m-2: nor while another seller holds the row's claim - a square-off whose stops take longer to come out than its gap.)
            if (SquareOffExit.inGap(p.exchange, p.symbol, p.product) || rowClaimedByOther(p)) return p
            // 22 Nov 2026 (B6 review, second round): its stop came out for its own target's sale and its cancel showed a moment late
            // ([TARGET_CANCEL]): with the target still reached, or reached within [TARGET_HIT_KEEP_MS], that sale goes now (sized on fresh,
            // lag-guarded reads, [sellAtTarget]) - never the stop put back to be taken out again by the next look. Not reached any more: put
            // back as below.
            val cancelAt = if (p.appTarget && p.target != null && so != null && so.status == "CANCELLED") takenOver[TARGET_CANCEL + so.id] else null
            if (cancelAt != null && so != null) {
                val key = "${p.exchange}:${p.symbol}"
                val ltp = runCatching { Broker.stopQuotes(listOf(key))[key]?.last }.getOrNull()
                val t = p.target!!
                val reached = ltp != null && ltp > 0 && (if (p.qty > 0) ltp >= t - 1e-9 else ltp <= t + 1e-9)
                // (One try for that cancel: its mark ends once the sale has the row's claim, so a sale that does not go - the gates, another
                // exit covering it, a read that failed - leaves the next look to put the stop back below; one that goes keeps it out while it
                // fills. Another seller on the row: that seller's - this look waits above; the mark kept, so a skip never puts the stop back.)
                if (reached || System.currentTimeMillis() - cancelAt in 0 until TARGET_HIT_KEEP_MS) {
                    // (Sixth round: its mark ends in [sellAtTarget], once the row's claim is taken.)
                    return sellAtTarget(p, so, ltp, out = true)
                }
            }
            // Review (R1): always on what is held less every exit fill of today the positions do not show yet (the lag guard forced,
            // any age: [restAgain]) - never a naked stop on a flat account, and a part still held gets its stop at once. Only when
            // Kite's positions do not give the day's quantity (the guard cannot work) does it wait, for [EXIT_DONE_MS] after Boss's
            // exit filled in full ([exitFilledFor]).
            if (listOfNotNull(so, to).any { exitDone(it.id) } && !dayQtyKnown(p)) return p
            // Its other exit still working is taken out first (confirmed), so the two put back never rest beside an old one.
            val working = listOfNotNull(so, to).filter { it.working }
            if (working.isNotEmpty()) {
                working.forEach { runCatching { Broker.cancel(it.id, it.variety) } }
                val still = runCatching { Broker.orders().filter { r -> working.any { it.id == r.id } }.any { it.working } }.getOrElse { true }
                if (still) return p
            }
            val back = restAgain(p.copy(qty = net.sign() * minOf(abs(net), abs(p.qty))), net,
                said = if (cancelAt != null) "its stop rests at Zerodha again (the app took it out to sell at the target, which was no longer reached " +
                    "when the cancel showed, and the position is still held)"
                else "its stop${if (p.target != null) " and target" else ""} rest at Zerodha again (the app had cancelled them for your exit, " +
                    "and the position is still held)", lagSafe = true, mustShow = mustShowFor(listOfNotNull(p.stopOrderId, p.targetOrderId)))
            if (back == null) {
                unprotected("${p.symbol}: its stop was cancelled for your exit, which did not close the position, and it could not be placed " +
                    "again. The position is NOT protected: set a stop yourself.")
                return p.copy(active = false, stopOrderId = null, targetOrderId = null, note = "stop not placed again")
            }
            if (back.active && back.id == p.id) return p                             // not placed this look: asked again next pass
            forgetTakenOver(listOfNotNull(p.stopOrderId, p.targetOrderId, TARGET_LOST + p.id, p.stopOrderId?.let { TARGET_CANCEL + it }))
            return back
        }
        // A stop refused or cancelled at Zerodha leaves the position unprotected: say so.
        // 20 Nov 2026 (deep review): a stream look that cannot tell what is held (closed reads count only in a regular look) says
        // nothing and changes nothing: the regular look decides (a closed position ends quietly there).
        // (Nor a regular look whose read showed it closed for the first time: its follow-up read decides.)
        // (Review: only while that first read is fresh - a count left by reads that keep failing never hides a refused stop.)
        if ((stream || freshFlat(p)) && net == null && so != null && so.status in setOf("REJECTED", "CANCELLED") && p.stopOrderId != null) return p
        if (so != null && so.status in setOf("REJECTED", "CANCELLED") && p.stopOrderId != null) {
            // 15 Nov (Boss's diagnostics: a manual position's stop cancelled at the exchange was not covered again that day): told
            // apart by Zerodha's own words. REJECTED, or CANCELLED with the exchange's reason, is the exchange's doing: ended as such
            // ([com.optionslab.ira.ManualCoverRule.STOP_BY_EXCHANGE]), so "Stop and target on my manual positions" may cover the
            // position again. CANCELLED with no reason given, or with words naming a user, is taken as Boss's own cancel (in Kite):
            // respected (never put back automatically), and said loudly that the position now has no stop. How Kite words a user's
            // cancel is an assumption not verified against live Kite ([com.optionslab.ira.ManualCoverRule.endedByExchange]).
            val byExchange = com.optionslab.ira.ManualCoverRule.endedByExchange(so.status, so.message)
            val alone = siblings(p).isEmpty()
            val note = if (byExchange) com.optionslab.ira.ManualCoverRule.STOP_BY_EXCHANGE else "stop ${so.status.lowercase()}"
            unprotected("${p.symbol}: the stop at Zerodha was ${so.status.lowercase()}${so.message.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}. " +
                (if (!alone) "${abs(p.qty)} of the position (one slice of its stops) is not protected by a stop." else "The position is not protected by a stop.") +
                // (22 Nov 2026, B6 review: the app watched its target only beside its stop - said, so Boss knows it is not watched any more.)
                (if (p.appTarget && p.target != null) " Its target %.2f, which the app watched, is not watched any more either.".format(java.util.Locale.ENGLISH, p.target) else "") +
                when {
                    !byExchange -> " It was cancelled by you (in Kite?), so the app leaves it as it is: set a stop yourself if you want one."
                    // Review: one slice of several - the other slices keep the position "protected", so nothing covers these units
                    // again by itself.
                    !alone -> " The other slices' stops still rest; set a stop for these ${abs(p.qty)} yourself."
                    p.auto -> " The app places its stop and target again on its next look at your manual positions."
                    else -> ""
                })
            if (to == null || !to.working) return p.copy(active = false, stopOrderId = null, note = note)
            // An automatic protection ([ManualCover]) whose stop the exchange refused: its target is taken out too (confirmed), so the
            // position is covered afresh (stop and target) rather than left with a target only. Not confirmed: kept as before.
            if (p.auto && byExchange && alone) {
                runCatching { Broker.cancel(to.id, to.variety) }
                if (runCatching { Broker.orderState(to.id)?.status }.getOrNull() in GONE)
                    return p.copy(active = false, stopOrderId = null, targetOrderId = null, note = note)
            }
            return p.copy(stopOrderId = null, note = note)
        }
        // 15 Nov, review: an order placed seconds ago that a trailing read of the order book does not show yet is not "gone": asked
        // again next pass (ended now, the order would rest at Zerodha untracked and another could be placed beside it).
        if (((p.stopOrderId != null && so == null) || (p.targetOrderId != null && to == null)) && nowMs() - p.id in 0 until FRESH_SLICE_MS) return p
        // Both exits gone some other way (a DAY order that ended with its day, cancelled at Zerodha): say so.
        if ((so == null || !so.working) && (to == null || !to.working) && (p.stopOrderId != null || p.targetOrderId != null)) {
            // 20 Oct, review: a carried (NRML) position's stop and target are DAY orders and end with their day; the next session
            // marked the protection finished and nothing watched the position any more. Now, in market hours, they are placed
            // again at the same levels for what is still held (only ever adding a stop: it lowers risk). Refused: said, as before.
            val today0 = Market.today().atStartOfDay(com.optionslab.engine.IST).toInstant().toEpochMilli()
            // 20 Nov 2026 (audit): what is held not known (a bad read, or one read showing it closed that a second has not confirmed
            // yet): a carried one is never ended on it (it would lose the open's orders), nor one waiting for its second read.
            // (Review: nor in a stream look, which never counts a closed read: the regular look decides.)
            if (net == null && (stream || p.id < today0 || p.note == WAITING || freshFlat(p))) return p
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
                // 6 Nov: Boss is exiting this contract by hand ([ArmLive.handsOff]): nothing placed beside his sale (next look).
                if (ArmLive.handsOffNow(p.exchange, p.symbol)) return p
                val again = p.copy(qty = net.sign() * minOf(abs(net), abs(p.qty)), gttId = null)
                // 21 Oct, enhancement round 4 (idea 1): [DayOco] on, as one GTT; not placed, the day's two orders as before.
                var refused = false
                if (dayOco(again)) when (val r = restDayGtt(again, net)) {
                    is DayGtt.Placed -> return r.item
                    // Review: its answer lost (it may exist): nothing else placed; the next look finds it or rests it.
                    DayGtt.Unknown, DayGtt.Wait -> return again.copy(stopOrderId = null, targetOrderId = null, note = DAY_GTT)
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
        // 15 Nov: a slice ([protectSliced]) comes down to what is held less what the other slices cover (never below nothing: the
        // block above ends a slice with nothing left to cover). Not sliced: what is held, as before.
        val mine = if (net == null || net == 0 || sibCover == 0 || net.sign() != p.qty.sign()) net else net.sign() * (abs(net) - sibCover)
        if (sibKnown && net != null && net != 0 && mine != null) {
            // 15 Nov, review: a reduce Zerodha refused leaves the order as it is: the protection keeps the quantity it really has.
            var reduced = true
            for (o in listOfNotNull(so, to).filter { it.working }) {
                val n = com.optionslab.engine.risk.ExitQty.shrinkTo(o.qty, o.filled, mine, p.lotSize) ?: continue
                val r = runCatching {
                    Broker.modify(o, n, o.type, if (o.type == "SL-M") null else o.price, if (o.type == "SL" || o.type == "SL-M") o.trigger else null)
                }
                if (r.isSuccess) { if (o.id == so?.id) stopQty = n }
                else reduced = false
                if (r.isFailure) Alerts.post("${p.symbol}: an exit covers more than is held ($net) and could not be reduced (${r.exceptionOrNull()?.message}); retrying.",
                    Alerts.Kind.ERROR, "Protection", throttle = true)
            }
            if (reduced && abs(mine) < abs(p.qty)) cur = p.copy(qty = mine)
        }
        // (23 Nov 2026, B6 review, eighth round, m4: a stop put back for only part beside Boss's own exit whose reply was lost ([BOSS_CUT]) grows
        // once that exit is known - a regular look, never the stream's.)
        // (Ninth round, MAJOR 1: the look ends with the change - this look's [so] and [stopQty] were read before it, and a trail modify on them
        // would shrink the grown stop back to its old size; the next look trails it on a fresh read.)
        if (!stream && cur.whole != null && so != null && !SquareOffExit.bossExitLostOn(cur.exchange, cur.symbol)) {
            val g = growStop(cur)
            if (g.qty != cur.qty) return g
            cur = g
        }
        // 22 Nov 2026 (B6 review, M5): the app's own target ([Item.appTarget]) watched while its stop rests and the position is held: reached
        // (the price now, or a stream look's tick a moment ago), it is sold - on a regular look; a stream look (the live lane, every tick) only
        // marks it and asks a look apart at once ([targetHit]), never a Zerodha call from the tick.
        val watchTarget = cur.appTarget && cur.target != null && so != null && so.working && net != null && net != 0 && net.sign() == cur.qty.sign()
        if ((cur.trail == null && !watchTarget) || so == null || !so.working) return cur
        val key = "${p.exchange}:${p.symbol}"
        val streamed = if (stream) runCatching { Broker.tokenOf(key)?.let { KiteStream.freshTick(it) }?.last?.takeIf { it > 0 } }.getOrNull() else null
        // 18 Oct: the fallback is Zerodha's quote unless a tick is 2 s fresh ([Broker.stopQuotes]), never a tick up to 5 s old.
        // (22 Nov 2026: a stream look with no fresh tick reads no quote for the target alone - the regular look does.)
        val ltp = streamed ?: (if (stream && cur.trail == null) null else Broker.stopQuotes(listOf(key))[key]?.last)
        if (watchTarget) {
            val t = cur.target!!
            val reached = ltp != null && ltp > 0 && (if (cur.qty > 0) ltp >= t - 1e-9 else ltp <= t + 1e-9)
            if (reached && stream) {
                // (Second round, m-1: not while a try a moment ago took nothing out - the regular looks try it meanwhile.)
                if (targetWait[cur.id]?.let { nowMs() < it } == true) return cur
                targetHit[cur.id] = nowMs()
                followUpMs = if (followUpMs == 0L) TARGET_LOOK_MS else minOf(followUpMs, TARGET_LOOK_MS)
                return cur
            }
            val marked = targetHit[cur.id]?.let { nowMs() - it in 0 until TARGET_HIT_KEEP_MS } == true
            if (!stream && (reached || marked)) return sellAtTarget(cur, so, ltp)
        }
        if (cur.trail == null || ltp == null) return cur
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
                last, ns, p.target, stopSlippage = Kite.STOP_GTT_SLIPPAGE)
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

    fun wipe() {
        // 26 Oct (Erase everything): first the count (a look in flight then drops its list), then every copy in memory and the
        // files, a set-aside copy included. Never takes [lock] and never reads the vault.
        wipeGen++
        synchronized(this) { lastId = 0L }
        // (22 Nov 2026, B6 review: erased - no lost target sale saved before is kept, so none is "not known": [targetSalesGoing].)
        startUnknownUntil = 0L
        cache = null; published = null; activeHint = null; autoHint = false; liveKeysHint = emptyList(); overnightHint = false; mcxOvernightHint = false; dayGttHint = emptyMap()
        freshDayGtts = emptyMap(); trackedOnly = false; blindWarned = 0L; takenOver.clear(); exitFilled.clear(); exitOrders.clear(); targetHit.clear(); targetSales.clear(); targetWait.clear()
        // (22 Nov 2026, B6 review: the marks' file deleted beside no write in flight - a write handed over before this erase is dropped.)
        synchronized(marksMon) { marksLoaded = false; marksForgotten.clear(); marksFails = 0; marksFile?.let { runCatching { it.delete() } } }
        runCatching { gttTries.clear() }
        flatSince.clear(); ocoSaid.clear(); ocoPending.clear(); gttFired.clear()
        // (23 Nov 2026, B6 review, eighth round: Boss's lost exits kept beside the marks go with them.)
        runCatching { SquareOffExit.forgetBossLostForErase() }
        filesForErase().forEach { runCatching { it.delete() } }
    }

    /** 26 Oct: stops and targets in memory (memory only, for the erase's tests). */
    internal fun holdsForErase(): Boolean = !cache.isNullOrEmpty() || activeHint == true || liveKeysHint.isNotEmpty() || dayGttHint.isNotEmpty() ||
        freshDayGtts.isNotEmpty()

    /** 26 Oct: the file this writes and any copy of it set aside as unreadable (none before [init]). */
    internal fun filesForErase(): List<File> {
        if (!::file.isInitialized) return emptyList()
        val aside = file.parentFile?.listFiles { f -> f.name.startsWith("${file.name}.unreadable.") }.orEmpty().toList()
        // (22 Nov 2026, B6 review: the taken-over marks' own file too.)
        return listOf(file) + aside + listOfNotNull(marksFile)
    }

    /** TEST ONLY: memory forgotten (the file kept), as after a restart: the next look reads the file. */
    /** TEST ONLY: the one-GTT tries used by protection [id] ([GTT_TRIES]). */
    internal fun gttTriesForTest(id: Long): Int = gttTries[id] ?: 0

    /** TEST ONLY: this object's lock held until [release] completes (a look holding it across Zerodha calls). */
    internal suspend fun holdLockForTest(release: kotlinx.coroutines.CompletableDeferred<Unit>) { lock.withLock { release.await() } }

    internal fun forgetMemoryForTest() { resetFollowUpForTest(); cache = null; published = null; gttTries.clear(); flatSince.clear(); synchronized(this) { lastId = 0L } }
}
