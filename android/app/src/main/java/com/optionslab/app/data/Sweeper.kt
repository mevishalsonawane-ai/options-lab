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
 *    when either fires the other comes out; deleted when the position is closed. Placing or moving one needs Live on
 *    ([OrbArms.liveNow]); a failed GTT is said plainly and alerted, and the SL itself always stays.
 *  - 8, the no-price failsafe: Zerodha prices are read through its REST quote (never the stream alone); no price for any
 *    open position for over two minutes is a loud warning, a notice and Jarvis's spoken safety warning
 *    ("Positions not protected: no prices since HH:MM").
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

    @Volatile private var openSince: Long? = null
    @Volatile private var lastPrice: Long? = null
    @Volatile private var alertedFor: Long? = null

    /** One sweep; never throws. */
    suspend fun run(context: Context) {
        if (!mutex.tryLock()) return            // one sweep at a time: a sweep already running does this one's work
        try {
            val now = System.currentTimeMillis()
            var holding = false
            var priced = false
            runCatching { Paper.sweepMissedStops() }.getOrNull()?.let { s ->
                holding = holding || s.holding
                priced = priced || (s.holding && s.priced)
                s.swept.forEach { said(context, MissedLock.say(it.trigger, it.price, it.symbol), "Paper", it.orderId) }
            }
            if (Broker.loggedIn) runCatching { live(context) }.getOrNull()?.let { (h, p) -> holding = holding || h; priced = priced || p }
            failsafe(context, holding, priced, now)
        } finally { mutex.unlock() }
    }

    private fun said(context: Context, text: String, venue: String, orderId: String?) {
        runCatching { Diag.record("risk", "$venue: $text") }
        runCatching { com.optionslab.app.work.Alerts.post("$venue: $text", com.optionslab.app.work.Alerts.Kind.ERROR, "Missed lock") }
        runCatching { Notifier.post(context, 2042, Notifier.RISK, "Lock missed", "$venue: $text", "strategy") }
        orderId?.let { id -> runCatching { Strategies.tagOwner(if (venue == "Paper") "paper:$id" else "kite:$id", "Missed-lock sweep") } }
    }

    /** Zerodha: the sweeper and the GTT backups. (holding, priced). */
    private suspend fun live(context: Context): Pair<Boolean, Boolean> {
        val held = runCatching { Broker.positionBook().net.filter { it.exchange == "NFO" && it.qty > 0 } }.getOrNull() ?: return false to false
        val orders = runCatching { Broker.orders() }.getOrNull() ?: return held.isNotEmpty() to false
        val stops = orders.filter { it.working && it.side == "SELL" && it.exchange == "NFO" && ours(it.tag) && (it.type == "SL" || it.type == "SL-M") }
        val keys = (held.map { "NFO:${it.symbol}" } + stops.map { "NFO:${it.symbol}" }).distinct()
        // REST quotes, whatever the stream says (item 8): a silent stream never leaves the stops unread.
        val ltp = if (keys.isEmpty()) emptyMap() else runCatching { Broker.quotes(keys) }.getOrDefault(emptyMap())
            .mapValues { it.value.last }.filterValues { it > 0 }
        // Item 7: a resting stop the price is already through is turned into a sell at a protected limit (the same order).
        for (so in stops) {
            val px = ltp["NFO:${so.symbol}"] ?: continue
            if (!MissedLock.missed(px, so.trigger)) continue
            val tick = runCatching { Broker.spec("NFO", so.symbol).tickSize }.getOrDefault(0.05)
            val limit = MissedLock.limit(px, tick)
            val r = runCatching { Broker.modify(so, so.qty, "LIMIT", limit, null) }
            if (r.isSuccess) said(context, MissedLock.say(so.trigger, px, so.symbol) + " (a sell at a limit of %.2f)".format(Locale.ENGLISH, limit), "Zerodha", so.id)
            else com.optionslab.app.work.Alerts.post("Zerodha: ${so.symbol} is through its stop %.2f at %.2f, and the stop could not be turned into a sell (%s). Close it in Trade."
                .format(Locale.ENGLISH, so.trigger, px, r.exceptionOrNull()?.message?.take(160) ?: "an error"), com.optionslab.app.work.Alerts.Kind.ERROR, "Missed lock", throttle = true)
        }
        runCatching { backups(orders, stops.filter { backed(it.tag) }, ltp) }
        return held.isNotEmpty() to (held.isNotEmpty() && held.all { ltp.containsKey("NFO:${it.symbol}") })
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
                LiveBackup.Step.KEEP -> if (g.status.lowercase() != "active" && so?.working != true) map.remove(sl)
            }
        }
        // Placed or moved up only with Live on (the live gates); a paper position never has one.
        if (OrbArms.liveNow()) for (so in stops) {
            val spec = runCatching { Broker.spec("NFO", so.symbol) }.getOrNull() ?: continue
            val want = LiveBackup.trigger(so.trigger, so.price.takeIf { so.type == "SL" }, spec.tickSize) ?: continue
            val old = map[so.id]?.let { id -> gtts.firstOrNull { it.id == id } }
            if (!LiveBackup.moves(old?.triggers?.firstOrNull(), want)) continue
            val px = ltp["NFO:${so.symbol}"] ?: continue
            val (gtt, why) = com.optionslab.engine.Kite.protect(spec, so.product.ifBlank { "MIS" }, so.qty - so.filled, px, want, null)
            if (gtt == null) { failed(so.symbol, want, why.joinToString("; ")); continue }
            // Moved: the old one out first, so two GTTs never sell the same position.
            old?.let { o -> runCatching { Broker.deleteGtt(o.id) }; map.remove(so.id) }
            val id = runCatching { Broker.placeGtt(gtt) }
            id.getOrNull()?.let { map[so.id] = it } ?: failed(so.symbol, want, id.exceptionOrNull()?.message?.take(160) ?: "an error")
        }
        keep(map)
    }

    private fun failed(symbol: String, trigger: Double, why: String) {
        val text = "Zerodha: the backup GTT for $symbol at %.2f could not be placed (%s). Its resting stop stays.".format(Locale.ENGLISH, trigger, why)
        runCatching { Diag.record("risk", text) }
        com.optionslab.app.work.Alerts.post(text, com.optionslab.app.work.Alerts.Kind.ERROR, "Backup GTT", throttle = true)
    }

    // ---- item 8: no prices --------------------------------------------------------------------------------------------

    private fun failsafe(context: Context, holding: Boolean, priced: Boolean, now: Long) {
        if (!holding) { openSince = null; alertedFor = null; return }
        val since = openSince ?: now.also { openSince = it }
        if (priced) { lastPrice = now; alertedFor = null; return }
        if (!PriceWatch.alert(lastPrice, since, now)) return
        val from = maxOf(lastPrice ?: since, since)
        if (alertedFor == from) return                 // said once for this silence
        alertedFor = from
        val text = PriceWatch.say(java.time.Instant.ofEpochMilli(from).atZone(com.optionslab.engine.IST).toLocalTime())
        runCatching { Diag.record("risk", text) }
        runCatching { Notifier.post(context, 2043, Notifier.RISK, "Positions not protected", "$text. Check them in Trade.", "strategy") }
        if (com.optionslab.app.BuildConfig.JARVIS) runCatching {
            com.optionslab.app.ira.IraHub.note(text)
            com.optionslab.app.ira.JarvisVoice.announce("$text. Check your positions.", urgent = true)
        }
    }
}
