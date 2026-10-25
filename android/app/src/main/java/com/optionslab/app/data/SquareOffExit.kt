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

    /** A stop-type order (waits for a trigger): the kind that is cancelled before the exit. A plain MARKET or LIMIT sell is an exit. */
    private fun isStop(o: Broker.OrderRow): Boolean = o.type.equals("SL", true) || o.type.equals("SL-M", true) || o.status == "TRIGGER PENDING"

    suspend fun send(p: Broker.Position, lotSize: Int, tickSize: Double, read: List<Broker.OrderRow>, working: List<Broker.OrderRow>,
                     positions: Broker.Positions, limits: Kite.Limits, who: String, tag: String, origin: String,
                     aboutIt: String, exitName: String, multiplier: Int = 1, tell: (String, String) -> Unit): Boolean {
        val pass = ExitCoordinator.enter(p.exchange, p.symbol, who)
        try {
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
            // sold again while the positions catch up ([ExitCoordinator.held]).
            val heldNow = runCatching { ExitCoordinator.held(p.exchange, p.symbol, p.product, who, read, positions) }.getOrNull() ?: return false
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
                    placed += Broker.placeOrder(o, exit = true).also { runCatching { Strategies.tagOwner("kite:$it", origin) } }
                } catch (e: Exception) {
                    if (Broker.definite(e)) {
                        tell("rejected ${p.symbol} ${e.message}", "Zerodha refused the $exitName exit of ${p.symbol}: ${e.message}. Retrying every pass until the close; close it yourself if it keeps failing.")
                        break
                    }
                    // The reply was lost, not necessarily the order: look for it before trying again.
                    runCatching { Broker.findRecentRetrying(o, placed + working.map { it.id }) }.getOrNull()?.let { placed += it }
                    break
                }
            }
            return placed.isNotEmpty()
        } finally { pass.leave() }
    }
}
