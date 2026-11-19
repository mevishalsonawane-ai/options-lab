package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.work.Notifier
import com.optionslab.engine.risk.LiveBackup
import com.optionslab.engine.risk.MissedLock
import com.optionslab.engine.risk.PriceWatch
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.util.Locale

/**
 * Boss's 08 Oct safety items, run in the 15-second loop and when the app comes back to the screen:
 *
 *  - 7, the missed-lock sweeper: a resting stop (or the lock it was moved up to) whose price is already at or under it,
 *    the position still open, is sold now - on paper the resting order itself is filled at the price
 *    ([Paper.sweepMissedStops]); at Zerodha the same SL order is changed into a sell at a protected limit just under the
 *    price (one order: never a second sell in flight). Said as "Lock missed at X, sold at Y". The bots' own checks (Pine,
 *    Solo, Liquidity, ORB, protections, Jarvis's trades) run in the same loop for positions with no resting order.
 *  - 6, the live backup: a GTT beside every bot's resting SL at Zerodha ([LiveBackup]), moved up with it, never down;
 *    when either fires the other comes out; deleted when the position is closed. 18 Oct (review of the arms' live path):
 *    NO LONGER PLACED. With the phone dead, the SL triggered and resting at its limit, the GTT firing at that limit puts in a
 *    second sell; a bounce back to the limit then fills both and turns the long into a short - the one state that must never
 *    exist. The SL alone rests at Zerodha now (the app's own check sells a price through its limit; Zerodha's MIS square-off
 *    is the dead-phone backstop). A backup still active beside a working SL (placed by an older build) is deleted here.
 *  - 8, the no-price failsafe: Zerodha prices are read through its REST quote (never the stream alone); no price for any
 *    open position for over two minutes is a loud warning, a notice and Jarvis's spoken safety warning
 *    ("Positions not protected: no prices since HH:MM"). 15 Oct: a position is judged only while its own market trades
 *    (NSE 09:15 to its close on a trading day, MCX in its own hours; [PriceWatch.inSession]): from 09:00 the MCX pass ran
 *    this with an overnight NSE paper position, which has no price before 09:15, and Boss was told "Positions not
 *    protected" before the open. A book not read is judged on the positions it last showed, with no price.
 *
 * Nothing here opens a position, and nothing live is ever sent for a paper position.
 */
object Sweeper {
    private val mutex = Mutex()
    private const val K_GTT = "breaker.gtt"

    /** Tags of the app's own resting stops at Zerodha (the bots', the protections'): only those are swept or backed up. */
    private fun ours(tag: String) = tag.startsWith("ira")
    /** The bots' stops that get a GTT backup (the ORB arms and Liquidity, the Pine scripts). */
    private fun backed(tag: String) = tag == "iraorb" || tag == "irapine"

    /** The no-price failsafe's memory (when the positions were first seen in their session, the last price, what was said). */
    @Volatile private var watch = PriceWatch.Failsafe()
    /**
     * The positions each book showed when last read (15 Oct). A book that cannot be read this pass is judged on them, with
     * no price: a read that fails once is within the two minutes (no false alarm), a silence that goes on is still said.
     * Nothing read yet (just started): nothing to judge.
     */
    @Volatile private var lastPaper: List<PriceWatch.Held> = emptyList()
    @Volatile private var lastLive: List<PriceWatch.Held> = emptyList()

    /** Tests: the failsafe starts afresh. */
    internal fun resetForTest() { watch = PriceWatch.Failsafe(); lastPaper = emptyList(); lastLive = emptyList(); flatSince.clear(); notSwept.clear(); quietSwept.clear(); quietSince.clear() }

    /**
     * 26 Oct (erase everything): the positions each book last showed dropped (the books themselves are erased; the next sweep
     * reads them again). The failsafe's clock is kept (times only, nothing about the account): a silence already running is
     * still said on time. The backup GTTs' list is a settings key, wiped with the settings. Never waits for a sweep: one under
     * way sets the lists again from the books it read.
     */
    fun wipe() { lastPaper = emptyList(); lastLive = emptyList(); flatSince.clear(); notSwept.clear(); quietSwept.clear(); quietSince.clear() }

    /** Positions in memory (the erase's test; memory only). */
    internal fun holdsForErase(): Boolean = lastPaper.isNotEmpty() || lastLive.isNotEmpty()

    /** TEST ONLY: the positions the books showed when last read, as a sweep leaves them. Debug builds only. */
    internal fun heldForTest(paper: List<PriceWatch.Held>, live: List<PriceWatch.Held> = emptyList()) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }
        lastPaper = paper; lastLive = live
    }

    /** (holding, priced) over [held] at [at]: only the positions whose own market trades then ([PriceWatch.judge]). */
    internal fun judge(held: List<PriceWatch.Held>, quoted: Set<String>, at: java.time.LocalDateTime, all: Boolean = false): Pair<Boolean, Boolean> =
        PriceWatch.judge(held, quoted, at, { d, minute -> Market.isOpenAt(d, minute) },
            runCatching { McxMarket.calendar() }.getOrDefault(com.optionslab.engine.mcx.McxSession.DEFAULT), all)

    /** One sweep; never throws. */
    suspend fun run(context: Context) {
        if (!mutex.tryLock()) return            // one sweep at a time: a sweep already running does this one's work
        try {
            // The app's clock (the tests' too): the session and the two minutes are judged on the same one.
            val at = Market.now()
            val t = at.toLocalDateTime()
            // Each book's (holding, priced) over its positions in their session.
            val books = ArrayList<Pair<Boolean, Boolean>>(2)
            val paper = runCatching { Paper.sweepMissedStops() }.getOrNull()
            if (paper != null) {
                lastPaper = paper.held
                books += judge(paper.held, paper.quoted, t)
                paper.swept.forEach { said(context, MissedLock.say(it.trigger, it.price, it.symbol), "Paper", it.orderId) }
            } else books += judge(lastPaper, emptySet(), t)
            if (Broker.loggedIn) {
                val l = runCatching { live(context, at.toInstant().toEpochMilli()) }.getOrNull()
                if (l != null) { lastLive = l.first; books += judge(l.first, l.second, t, all = true) }
                else books += judge(lastLive, emptySet(), t, all = true)
            } else lastLive = emptyList()
            // Every book holding in its session must be priced: a priced paper book never hides a live one with no price.
            val (holding, priced) = PriceWatch.combine(books)
            failsafe(context, holding, priced, at.toInstant().toEpochMilli())
        } finally { mutex.unlock() }
    }

    private suspend fun said(context: Context, text: String, venue: String, orderId: String?) {
        runCatching { Diag.record("risk", "$venue: $text") }
        runCatching { com.optionslab.app.work.Alerts.post("$venue: $text", com.optionslab.app.work.Alerts.Kind.ERROR, "Missed lock") }
        runCatching { Notifier.post(context, 2042, Notifier.RISK, "Lock missed", "$venue: $text", "almanac") }
        orderId?.let { id -> runCatching { Strategies.tagOwner(if (venue == "Paper") "paper:$id" else "kite:$id", "Missed-lock sweep") } }
    }

    /** The exchanges swept at Zerodha: NFO, and MCX (9 Oct: its positions are watched in its own hours too). */
    private val SWEPT = setOf("NFO", "MCX")

    /**
     * Zerodha: the sweeper and the GTT backups. The positions held and the keys priced this pass; null when the position book
     * could not be read. 15 Oct: an order book not read no longer counts as "no prices" (a false alarm): the positions are
     * still priced, and only the sweep and the backups wait for the next pass.
     */
    private suspend fun live(context: Context, nowMs: Long): Pair<List<PriceWatch.Held>, Set<String>>? {
        // Long and short alike (18 Oct, review): a sold leg at Zerodha with no prices was never warned of (it was qty > 0, while
        // the paper side counts every quantity != 0). The sweep below still turns only SELL stops (a long's) into sells.
        // 19 Nov 2026 (audit): the rows at 0 are kept too ([book]): a stop's contract shown closed is told from one not listed.
        val book = runCatching { Broker.positionBook().net.filter { it.exchange in SWEPT } }.getOrNull() ?: return null
        val held = book.filter { it.qty != 0 }
        val orders = runCatching { Broker.orders() }.getOrNull()
        val stops = orders.orEmpty().filter { it.working && it.side == "SELL" && it.exchange in SWEPT && ours(it.tag) && (it.type == "SL" || it.type == "SL-M") }
        val keys = (held.map { "${it.exchange}:${it.symbol}" } + stops.map { "${it.exchange}:${it.symbol}" }).distinct()
        // REST quotes, whatever the stream says (item 8): a silent stream never leaves the stops unread. 18 Oct: a tick only
        // while 2 s fresh ([Broker.stopQuotes]); [Broker.quotes] answered from one up to 5 s old.
        val quotes = if (keys.isEmpty()) emptyMap() else runCatching { Broker.stopQuotes(keys) }.getOrDefault(emptyMap())
        val ltp = quotes.mapValues { it.value.last }.filterValues { it > 0 }
        // Item 7: a resting stop the price is already through is turned into a sell at a protected limit (the same order).
        // 19 Nov 2026 (audit: a Pine stop left resting after its position closed could open a naked short): only for what is held. A
        // stop on a contract held long nowhere is never turned into a sell; a Pine script's ("irapine") is cancelled once confirmed
        // ([stale]; every other book takes its own out, and the ORB family puts back a stop it finds cancelled only when it was not
        // Boss's own cancel - the sweep's would look like one). A stop on a contract held is sold for no more than its product holds,
        // less the other sales working on it and what this sweep already turned into sells there.
        val used = HashMap<String, Int>()
        for (so in stops) {
            val pk = "${so.exchange}:${so.symbol}:${so.product}"
            val long = book.filter { it.exchange == so.exchange && it.symbol == so.symbol && it.qty > 0 }.sumOf { it.qty }
            if (long <= 0) { if (so.tag == "irapine") stale(so, book, nowMs); continue }
            flatSince.remove(so.id)
            val px = ltp["${so.exchange}:${so.symbol}"] ?: continue
            if (!MissedLock.missed(px, so.trigger)) continue
            // (Less every other sale already working on it that can fill at this price - a market sale, a limit at or under it: an
            // exit's, Boss's own, a stop swept on an earlier pass - never more than is held in all. A limit above the price could not
            // fill before the stop itself would, so the sweep adds nothing to it.)
            val sales = orders.orEmpty().filter { it.working && it.side == "SELL" && it.id != so.id && it.exchange == so.exchange &&
                it.symbol == so.symbol && it.product == so.product && it.type != "SL" && it.type != "SL-M" &&
                (it.type == "MARKET" || it.price <= px + 1e-9) }
            val selling = sales.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) }
            val heldHere = book.filter { it.exchange == so.exchange && it.symbol == so.symbol && it.product == so.product }.sumOf { it.qty }
            val have = heldHere - selling - (used[pk] ?: 0)
            val left = com.optionslab.engine.risk.ExitQty.remaining(so.qty, so.filled, so.pending)
            val send = minOf(left, have)
            if (send <= 0) {
                if (notSwept.size > 200) notSwept.clear()
                if (quietSwept.size > 200) quietSwept.clear()
                if (quietSince.size > 200) quietSince.clear()
                // (19 Nov 2026, second review: the app's own exit sale already selling all that is held - an arm's exit under way - is
                // the normal case: noted, Boss not alarmed. Third review: only a market sale or a limit at or under the bid, which fills
                // now - with no bid known, or such a sale still not done after 30 s, Boss is told as below.)
                val bid = quotes["${so.exchange}:${so.symbol}"]?.bid?.takeIf { it > 0 }
                val appSelling = sales.filter { ours(it.tag) && it.tag != "iraalgo" && (it.type == "MARKET" || (bid != null && it.price <= bid + 1e-9)) }
                    .sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) } + (used[pk] ?: 0)
                val quiet = heldHere > 0 && appSelling >= heldHere
                val quietFrom = if (quiet) quietSince.putIfAbsent(so.id, nowMs) ?: nowMs else { quietSince.remove(so.id); null }
                if (quiet && nowMs - (quietFrom ?: nowMs) <= QUIET_MS) {
                    if (quietSwept.add(so.id)) runCatching { Diag.record("risk", "Zerodha: ${so.symbol} is through its stop %.2f at %.2f; the app's own exit sale already sells what is held: the stop was left as it is"
                        .format(Locale.ENGLISH, so.trigger, px)) }
                } else if (notSwept.add(so.id)) {
                    val text = "Zerodha: ${so.symbol} is through its stop %.2f at %.2f, but nothing of it in %s is left to sell (other orders already sell what is held there): the stop was not turned into a sell. Check it in Trade."
                        .format(Locale.ENGLISH, so.trigger, px, so.product.ifBlank { "its product" })
                    runCatching { Diag.record("risk", text) }
                    com.optionslab.app.work.Alerts.post(text, com.optionslab.app.work.Alerts.Kind.ERROR, "Missed lock", throttle = true)
                }
                continue
            }
            val tick = runCatching { Broker.spec(so.exchange, so.symbol).tickSize }.getOrDefault(0.05)
            val limit = MissedLock.limit(px, tick)
            val r = runCatching { Broker.modify(so, so.qty - (left - send), "LIMIT", limit, null) }
            if (r.isSuccess) used[pk] = (used[pk] ?: 0) + send
            if (r.isSuccess) said(context, MissedLock.say(so.trigger, px, so.symbol) + " (a sell at a limit of %.2f)".format(Locale.ENGLISH, limit), "Zerodha", so.id)
            else com.optionslab.app.work.Alerts.post("Zerodha: ${so.symbol} is through its stop %.2f at %.2f, and the stop could not be turned into a sell (%s). Close it in Trade."
                .format(Locale.ENGLISH, so.trigger, px, r.exceptionOrNull()?.message?.take(160) ?: "an error"), com.optionslab.app.work.Alerts.Kind.ERROR, "Missed lock", throttle = true)
        }
        if (orders != null) runCatching { backups(orders, stops.filter { backed(it.tag) }, ltp) }
        return held.map { PriceWatch.Held("${it.exchange}:${it.symbol}", it.exchange) } to ltp.keys
    }

    /** 19 Nov 2026 (audit): when each app stop's contract was first read held long nowhere, by order id (market-clock ms). Memory only. */
    private val flatSince = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** 19 Nov 2026 (audit): the stops already said not swept for want of a holding (said once each). Memory only; bounded. */
    private val notSwept: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** 19 Nov 2026 (second review): the same, left quietly beside the app's own exit sale (said once each, apart from [notSwept]). */
    private val quietSwept: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** 19 Nov 2026 (third review): since when each stop has been left quietly beside the app's own exit sale (market-clock ms). Memory only. */
    private val quietSince = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** 19 Nov 2026 (third review): how long a stop is left quietly beside the app's own exit sale before Boss is told. */
    private const val QUIET_MS = 30_000L

    /**
     * 19 Nov 2026 (audit): a Pine script's stop [so], whose contract the positions read [book] shows held long nowhere: never turned into a sell
     * (above); cancelled at Zerodha and confirmed once the reads agree ([com.optionslab.engine.orb.LiveMirror.shortConfirmed]: a row is
     * listed for the contract - a read with no row is no reading - and every read for five minutes has shown it held long nowhere). The
     * sweep cannot tell whose sale closed what, so it never takes the 30-second way the books take with a sale in the order book: the
     * stop's own book takes it out first. Not confirmed gone: Boss is told to cancel it in Kite.
     */
    private suspend fun stale(so: Broker.OrderRow, book: List<Broker.Position>, nowMs: Long) {
        if (flatSince.size > 200) flatSince.clear()
        val rowShown = book.any { it.exchange == so.exchange && it.symbol == so.symbol }
        val first = if (rowShown) flatSince.putIfAbsent(so.id, nowMs) else flatSince[so.id]
        if (!com.optionslab.engine.orb.LiveMirror.shortConfirmed(rowShown, false, first ?: nowMs, nowMs, unbookedSale = false)) return
        runCatching { Broker.cancel(so.id, so.variety) }
        val st = runCatching { Broker.orderState(so.id) }.getOrNull()
        if (st != null && st.status in setOf("CANCELLED", "REJECTED", "COMPLETE")) {
            flatSince.remove(so.id)
            val text = if (st.filled > 0) "Zerodha: the app's stop ${so.id} on ${so.symbol} filled ${st.filled} with nothing held. A short may be open: check it in Kite now."
                else "Zerodha: the app's stop ${so.id} on ${so.symbol} rested with nothing held; it was cancelled"
            runCatching { Diag.record("risk", text) }
            if (st.filled > 0) com.optionslab.app.work.Alerts.post(text, com.optionslab.app.work.Alerts.Kind.ERROR, "Missed lock")
            return
        }
        com.optionslab.app.work.Alerts.post("Zerodha: the app's stop ${so.id} on ${so.symbol} rests with nothing held, and it could not be cancelled. " +
            "Cancel it in Kite: if it fires it sells what is not held.", com.optionslab.app.work.Alerts.Kind.ERROR, "Missed lock", throttle = true)
    }

    // ---- item 6: the GTT beside each bot's SL ------------------------------------------------------------------------

    private fun kept(): MutableMap<String, Long> = runCatching {
        val o = JSONObject(SecurePrefs.getString(K_GTT) ?: "{}")
        o.keys().asSequence().associateWith { o.getLong(it) }.toMutableMap()
    }.getOrDefault(HashMap())

    private fun keep(m: Map<String, Long>) { SecurePrefs.put(K_GTT, JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }.toString()) }

    private suspend fun backups(orders: List<Broker.OrderRow>, stops: List<Broker.OrderRow>, ltp: Map<String, Double>) {
        val map = kept()
        if (map.isEmpty() && stops.isEmpty()) return
        val gtts = runCatching { Broker.gtts() }.getOrNull() ?: return
        // The stop and its GTT: when one fired the other comes out; a closed position's GTT is deleted.
        for ((sl, gid) in map.toMap()) {
            val so = orders.firstOrNull { it.id == sl }
            val g = gtts.firstOrNull { it.id == gid }
            if (g == null) { map.remove(sl); continue }
            when (LiveBackup.step(so?.status, g.status)) {
                LiveBackup.Step.CANCEL_STOP -> {
                    runCatching { Broker.cancel(sl, so?.variety ?: "regular") }
                    runCatching { Diag.record("risk", "Zerodha: the backup GTT on ${g.symbol} fired; its resting stop was taken out") }
                    map.remove(sl)
                }
                LiveBackup.Step.DROP_GTT -> { runCatching { Broker.deleteGtt(gid) }; map.remove(sl) }
                LiveBackup.Step.KEEP -> when {
                    g.status.lowercase() != "active" && so?.working != true -> map.remove(sl)
                    // 18 Oct: an older build's backup beside a working SL - two resting sells: the GTT comes out, the SL stays.
                    g.status.lowercase() == "active" && so?.working == true -> {
                        if (runCatching { Broker.deleteGtt(gid) }.isSuccess) {
                            map.remove(sl)
                            runCatching { Diag.record("risk", "Zerodha: the backup GTT beside the stop on ${g.symbol} was deleted (two resting sells could both fill); the stop stays") }
                        }
                    }
                }
            }
        }
        // 18 Oct: no backup GTT is placed beside a stop any more (see item 6 above): the stop alone rests at Zerodha.
        keep(map)
    }

    private fun failed(symbol: String, trigger: Double, why: String) {
        val text = "Zerodha: the backup GTT for $symbol at %.2f could not be placed (%s). Its resting stop stays.".format(Locale.ENGLISH, trigger, why)
        runCatching { Diag.record("risk", text) }
        com.optionslab.app.work.Alerts.post(text, com.optionslab.app.work.Alerts.Kind.ERROR, "Backup GTT", throttle = true)
    }

    // ---- item 8: no prices --------------------------------------------------------------------------------------------

    /** Said once per silence ([PriceWatch.Failsafe.look]). */
    private fun failsafe(context: Context, holding: Boolean, priced: Boolean, now: Long) {
        val from = watch.look(holding, priced, now) ?: return
        val text = PriceWatch.say(java.time.Instant.ofEpochMilli(from).atZone(com.optionslab.engine.IST).toLocalTime())
        runCatching { Diag.record("risk", text) }
        runCatching { Notifier.post(context, 2043, Notifier.RISK, "Positions not protected", "$text. Check them in Trade.", "trade") }
        if (com.optionslab.app.BuildConfig.JARVIS) runCatching {
            com.optionslab.app.ira.IraHub.note(text)
            com.optionslab.app.ira.JarvisVoice.announce("$text. Check your positions.", urgent = true)
        }
    }
}
