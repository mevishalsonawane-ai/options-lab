package com.optionslab.app.data

import com.optionslab.engine.Kite
import kotlin.math.abs

/**
 * One live position's square-off exit, as the expiry square-off has sent it since 20 Oct ([ExpirySquareOff]) and the flat-by
 * sends it from 22 Oct ([FlatBySquareOff]): each contract in its one exit turn ([ExitCoordinator]), other resting exits on it
 * cancelled first (the exit goes on the next pass), the day's GTT deleted first, what is held checked against the orders
 * read's fills, exits already working subtracted, the quantity in freeze-size slices of MARKET orders in the position's own
 * product, every refusal said once, and a lost reply looked for before the order is called unsent.
 *
 * [aboutIt] / [exitName]: the words of the notices ("expires today", "expiry"). MCX: [lotSize] 1 (Zerodha keeps MCX in lots) and
 * the contract's [multiplier].
 * [read]: the orders read BEFORE [positions] by the caller (a fill between the two reads then shows as still working, and
 * nothing more is sent). Returns true when an exit was placed this pass; false when nothing was sent (closed meanwhile, still
 * closing, cancelled first, refused and told).
 */
internal object SquareOffExit {
    /** The two square-offs' tags: each counts the other's working exit as cover and never cancels it (25 Oct review). */
    val TAGS = setOf("iraalgoexpiry", "iraalgoflat")
    private val DONE = setOf("COMPLETE", "REJECTED", "CANCELLED")

    /**
     * 19 Nov 2026 (second review of the clock lane: a square-off can now run twice back to back, the lane's run and the pass's):
     * after a square-off exit was placed on a contract (by any square-off: this object's two and the MCX expiry exit), none is sent
     * on it again for [GAP_MS], whatever the next read of the books shows (an orders read can miss an order placed a moment ago,
     * and a positions read can trail its fill). Per contract and product (one position row), so no other position's exit is ever
     * held back. Memory only.
     */
    const val GAP_MS = 15_000L
    private val placedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The wall clock the gap reads (a test's fake one). */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis
    /** The gap in force: [GAP_MS] in the app, always; the older tests' back-to-back passes set it to 0 (its own tests set it). */
    @Volatile internal var gapMs: Long = GAP_MS

    /** Whether a square-off exit went on [exchange]:[symbol] [product] within [GAP_MS]. */
    fun inGap(exchange: String, symbol: String, product: String): Boolean {
        val at = placedAt["$exchange:$symbol:$product"] ?: return false
        val now = clock()
        return now >= at && now - at < gapMs
    }

    /** A square-off exit was placed on [exchange]:[symbol] [product] now. */
    fun notePlaced(exchange: String, symbol: String, product: String) { placedAt["$exchange:$symbol:$product"] = clock() }

    /** Whether the diary was told this process that the square-offs always use the lag guard (Boss's choice). Memory only. */
    private val choiceSaid = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 19 Nov 2026 (Boss's choice, "Go"): the square-offs always use the positions-lag guard - said once in the diary. */
    fun noteBossChoice() {
        if (choiceSaid.compareAndSet(false, true))
            runCatching { Diag.record("exits", "square-offs always use the positions-lag guard, whatever its switch says (your choice, 19 Nov 2026)") }
    }

    /** TEST ONLY. */
    internal fun resetForTest(gap: Long = GAP_MS) { placedAt.clear(); clock = System::currentTimeMillis; gapMs = gap }

    /** A stop-type order (waits for a trigger): the kind that is cancelled before the exit. A plain MARKET or LIMIT sell is an exit. */
    private fun isStop(o: Broker.OrderRow): Boolean = o.type.equals("SL", true) || o.type.equals("SL-M", true) || o.status == "TRIGGER PENDING"

    suspend fun send(p: Broker.Position, lotSize: Int, tickSize: Double, read: List<Broker.OrderRow>, working: List<Broker.OrderRow>,
                     positions: Broker.Positions, limits: Kite.Limits, who: String, tag: String, origin: String,
                     aboutIt: String, exitName: String, multiplier: Int = 1, tell: (String, String) -> Unit): Boolean {
        val pass = ExitCoordinator.enter(p.exchange, p.symbol, who)
        try {
            // 19 Nov 2026: an exit placed on it a moment ago is still on its way ([GAP_MS]): nothing more now, the next run reads again.
            if (inGap(p.exchange, p.symbol, p.product)) return false
            if (ExitCoordinator.stale(p.exchange, p.symbol, who, read)) return false
            val side = if (p.qty < 0) Kite.Side.BUY else Kite.Side.SELL
            val same = working.filter { it.symbol == p.symbol && it.side == side.name && it.product == p.product }
            // 25 Oct review: a plain exit already working on it - this square-off's own, the other square-off's, or the arm's own
            // market or limit sell ([LiveMirror.TAG]: its closing in progress) - is COVER: subtracted below, never cancelled (a
            // cancel racing its fill, then a sell on stale holdings, could sell the position twice). Anything else resting the same
            // way (the arm's stop at Zerodha, a protection's stop or target, a strategy's) would keep the market exit from ever going
            // out: cancelled first, and - new - confirmed out, so the exit goes in THIS pass. (Before, the exit waited for the next
            // pass; the arm put its stop back in between and this cancelled it again, every minute until the close, with the
            // position never flat and its stop gone most of the time.) A cancelled order found filled sold the position meanwhile:
            // nothing more this pass, the next reads the books again; one not yet out: the exit waits for the next pass too.
            val cover = same.filter { it.tag == tag || it.tag in TAGS || (it.tag == com.optionslab.engine.orb.LiveMirror.TAG && !isStop(it)) }
            val others = same.filter { it !in cover }
            if (others.isNotEmpty()) {
                var filled = false; var allOut = true
                for (o in others) {
                    runCatching { Broker.cancel(o.id, o.variety) }
                    val st = runCatching { Broker.orderState(o.id)?.status }.getOrNull()
                    if (st == "COMPLETE") filled = true else if (st !in DONE) allOut = false
                }
                if (filled || !allOut) return false
            }
            // A protection's stop and target resting as one GTT ([DayOco]) would still fire after this exit sold the position:
            // deleted first, confirmed (or the exit waits for the next pass).
            if (!Protections.dayGttsOut(p.exchange, p.symbol, who)) return false
            val pending = cover.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) }
            // What is held, checked against the orders read's fills: an exit sent from here that filled a moment ago is never
            // sold again while the positions catch up ([ExitCoordinator.held]). 19 Nov 2026 (second review of the clock lane: a
            // square-off can now run twice back to back, the clock lane's run and the pass's): the lag guard always acts here
            // ([forceLag], as [Protections] has since 15 Nov), whatever its switch says - it only ever lowers what is sent. Boss's
            // choice (19 Nov 2026, "Go"): the square-offs ALWAYS use the positions-lag guard ([noteBossChoice] says so once in the diary).
            noteBossChoice()
            val heldNow = runCatching { ExitCoordinator.held(p.exchange, p.symbol, p.product, who, read, positions, forceLag = true) }.getOrNull() ?: return false
            if (heldNow == 0 || (heldNow > 0) != (p.qty > 0)) return false          // closed meanwhile: the next pass sees it
            val qty = com.optionslab.engine.risk.ExitQty.sendable(heldNow, pending, abs(heldNow), lotSize)
            if (qty <= 0) return false                                      // still closing; checked again next pass
            // Above the exchange freeze quantity the exit goes as several orders, each one the exchange accepts.
            val orders = Kite.slices(qty, lotSize, Kite.freezeQuantity(p.exchange, p.symbol))
                .map { Kite.Order(p.symbol, side, it, lotSize, p.product, "MARKET", null, tickSize, p.exchange, tag, multiplier = multiplier) }
            val bad = orders.flatMap { Kite.refusals(it, limits, Broker.sentToday(), false, exit = true) }.distinct()
            if (bad.isNotEmpty()) {
                tell("refused ${p.symbol}", "${p.symbol} $aboutIt and its exit was not sent (${bad.joinToString("; ")}). Close it yourself in Trade.")
                return false
            }
            val placed = ArrayList<String>()
            for (o in orders) {
                try {
                    // 20 Nov 2026 (third review): the contract's gap starts with each order placed, not after the whole loop.
                    placed += Broker.placeOrder(o, exit = true).also { notePlaced(p.exchange, p.symbol, p.product); runCatching { Strategies.tagOwnerSoon("kite:$it", origin) } }
                } catch (e: Exception) {
                    if (Broker.definite(e)) {
                        // 10 Nov (the 3 Nov refusal, for an exit): MARKET blocked on a low-open-interest contract - the same slice once as a
                        // LIMIT at the bid (never more than 1% under the last; [com.optionslab.engine.orb.LiveMirror.blockedMarketExit]). Not
                        // filled in 10 s: cancelled, so it never sits as "cover" while the price moves away, and said as a refusal (the next
                        // pass prices it again). A lost reply is counted by the next pass's read of the working orders, as every exit here.
                        if (com.optionslab.engine.orb.LiveMirror.marketBlocked(e.message)) {
                            val key = "${p.exchange}:${p.symbol}"
                            val q = runCatching { Broker.quotes(listOf(key))[key] }.getOrNull()
                            val limit = com.optionslab.engine.orb.LiveMirror.blockedMarketExit(o, q?.last, q?.ask, q?.bid)
                            // 20 Nov 2026 (third review): a lost reply is not a refusal - the LIMIT may be out, so the gap starts and it
                            // is looked for, as the MARKET path does; never said as refused.
                            var limitLost = false
                            val sent = limit?.let { l ->
                                try {
                                    Broker.placeOrder(l, exit = true).also { notePlaced(p.exchange, p.symbol, p.product) }
                                } catch (e2: kotlinx.coroutines.CancellationException) {
                                    throw e2
                                } catch (e2: Exception) {
                                    if (Broker.definite(e2)) null else {
                                        limitLost = true
                                        notePlaced(p.exchange, p.symbol, p.product)
                                        runCatching { Broker.findRecentRetrying(l, placed + working.map { it.id }) }.getOrNull()
                                    }
                                }
                            }
                            if (sent == null && limitLost) break
                            if (sent != null) {
                                runCatching { Strategies.tagOwnerSoon("kite:$sent", origin) }
                                var f = runCatching { Broker.awaitOrder(sent, 10_000) }.getOrNull()
                                if (f?.status !in DONE) { runCatching { Broker.cancel(sent) }; f = runCatching { Broker.orderState(sent) }.getOrNull() ?: f }
                                // Counted as placed when any of it filled or it may still be working; cancelled unfilled: said, next pass.
                                if (f == null || f.filled > 0 || f.status !in DONE) { placed += sent; continue }
                                tell("unfilled ${p.symbol}", "Zerodha blocks MARKET orders on ${p.symbol} (low open interest), and its $exitName exit as a LIMIT at " +
                                    "%.2f did not fill in 10 s, so it was cancelled. Retrying every pass until the close; close it yourself if it keeps failing."
                                        .format(java.util.Locale.ENGLISH, limit?.price ?: 0.0))
                                break
                            }
                        }
                        tell("rejected ${p.symbol} ${e.message}", "Zerodha refused the $exitName exit of ${p.symbol}: ${e.message}. Retrying every pass until the close; close it yourself if it keeps failing.")
                        break
                    }
                    // The reply was lost, not necessarily the order: look for it before trying again. 19 Nov 2026: it may be out
                    // either way, so the contract's gap starts ([GAP_MS]).
                    notePlaced(p.exchange, p.symbol, p.product)
                    runCatching { Broker.findRecentRetrying(o, placed + working.map { it.id }) }.getOrNull()?.let { placed += it }
                    break
                }
            }
            return placed.isNotEmpty()
        } finally { pass.leave() }
    }
}
