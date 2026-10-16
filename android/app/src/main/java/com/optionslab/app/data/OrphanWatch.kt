package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.work.Notifier
import com.optionslab.engine.orb.HeroTicketRules
import com.optionslab.engine.orb.NightRules
import com.optionslab.engine.orb.OrphanAudit
import com.optionslab.engine.orb.VixDivRules
import com.optionslab.engine.signals.SignalPaperRules
import java.util.Locale

/**
 * The safety net that never leaves an automatic arm's position unwatched (16 Oct 2026; Boss's R51 Donchian 20 re-entry on
 * NIFTY19OCT2623050CE was held past its +30% target and its 30-minute exit with no record in its arm's book, so nothing
 * sold it). CLAUDE.md: open positions and orders come first.
 *
 * Every look (the 15-second stop check, the fast path's minute lane, the watch's full pass) each long PAPER position is
 * matched to the order that opened it and that order's owner ([OrphanAudit]). One an arm below opened, that its arm's open
 * record does not hold and no exit order (a resting stop or target, a protection) works on, is an orphan once seen twice
 * 20 s apart:
 *  - the passed-research arms' (R51, R52, Solo 2.0): taken back by their host as its trade, from its real fill and fill
 *    time, with its arm's own exits ([PassedArmsHost.adopt]);
 *  - the Hero ₹1,000 ticket's, Night (R3)'s, VIX divergence's and the Signal tester's: a protection rests on it at once (a
 *    stop at -15% and a target at +30% of its fill, [OrphanAudit.netStop] / [OrphanAudit.netTarget]); the paper account's own
 *    square-off still closes an MIS one; one already beyond a level is sold at market.
 * Each is said plainly to Boss (a notice) and in Diag. The other automatic arms (ORB, Liquidity, the Hero ORB, MCX, Forge,
 * Pine) rest a stop with every entry, which this treats as watched.
 *
 * Paper only: it never places a Zerodha order (live arms' stops rest at the broker as SL / GTT). Memory reads, then at most
 * one quote and the protection's own orders for an orphan: never on the order or tick path, never on the main thread.
 */
object OrphanWatch {
    private val pending = OrphanAudit.Pending()

    /** The arms whose orphans get a protection (their open records are read from memory, [claims]). */
    private val NET_OWNERS = setOf(HeroTicketRules.OWNER, NightRules.LABEL, VixDivRules.LABEL, SignalPaperRules.OWNER)

    /** Its notices: 8200 .. 8219 (no other part of the app uses 82xx). */
    private const val NOTICE = 8200

    /** An owner this net looks after. */
    internal fun automatic(owner: String): Boolean = owner in NET_OWNERS || PassedArmsHost.armOf(owner) != null

    /** The options the arms above hold or are buying now (memory only). */
    private fun claims(): Set<String> = runCatching { PassedArmsHost.claims() }.getOrDefault(emptySet()) +
        runCatching { HeroTicketArm.view.value.open.map { it.symbol } }.getOrDefault(emptyList()) +
        runCatching { NightArm.view.value.open.map { it.symbol } }.getOrDefault(emptyList()) +
        runCatching { VixDivArm.view.value.open.map { it.symbol } }.getOrDefault(emptyList()) +
        runCatching { SignalPaperArm.view.value.open.map { it.symbol } }.getOrDefault(emptyList())

    private fun nowMs(): Long = runCatching { Market.now().toInstant().toEpochMilli() }.getOrElse { System.currentTimeMillis() }

    /** The orphans found now (before the two sightings). Memory reads, and the order owners' labels. */
    internal suspend fun find(): List<OrphanAudit.Orphan> {
        val st = Paper.state
        val longs = st.positions.filter { it.quantity > 0 }
        if (longs.isEmpty()) return emptyList()
        val exitOrders = st.orders.filter { (it.status == "open" || it.status == "trigger pending") && it.action.equals("SELL", true) }.map { it.symbol }.toSet()
        val watched = claims() + exitOrders
        val bySymbol = longs.filter { it.symbol !in watched }.groupBy { it.symbol }
        if (bySymbol.isEmpty()) return emptyList()
        val today = Market.today()
        val held = bySymbol.map { (sym, rows) ->
            val qty = rows.sumOf { it.quantity }
            val avg = rows.sumOf { it.averagePrice.toDouble() * it.quantity } / qty.coerceAtLeast(1)
            OrphanAudit.Held(sym, qty, avg, st.trades.filter { it.symbol == sym && it.timestamp.toLocalDate() == today }.sortedBy { it.timestamp }
                .map { OrphanAudit.Fill(it.orderId, it.action.equals("BUY", true), it.quantity, it.price.toDouble(), it.timestamp) })
        }
        val owners = Strategies.owners()
        return OrphanAudit.find(held, emptySet(), { id -> owners["paper:$id"]?.let { Origins.base(it) } }, ::automatic)
    }

    /** One look: the orphans seen twice are taken back or protected. Each in its own try. */
    suspend fun run(context: Context) {
        val found = runCatching { find() }.getOrElse { runCatching { Diag.record("orphans", "the safety net's look failed: ${it.javaClass.simpleName}") }; return }
        for (o in pending.confirm(found, nowMs())) {
            try {
                act(context, o)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                runCatching { Diag.record("orphans", "${o.symbol} (${o.owner}): could not be taken back: ${e.javaClass.simpleName}; tried again next look") }
            }
        }
    }

    private suspend fun act(context: Context, o: OrphanAudit.Orphan) {
        if (PassedArmsHost.armOf(o.owner) != null) {
            val words = PassedArmsHost.adopt(o) ?: return
            pending.done(o)
            runCatching { Diag.record("orphans", words) }
            return
        }
        val c = Paper.contractOf(o.symbol) ?: return
        val row = Paper.state.positions.filter { it.symbol == o.symbol && it.quantity > 0 }.maxByOrNull { it.quantity } ?: return
        val ltp = runCatching { Paper.bookNow(c)?.takeIf { it.ageMs <= 15_000L }?.last }.getOrNull()?.takeIf { it > 0 }
            ?: runCatching { Paper.quote(c)?.ltp }.getOrNull()?.takeIf { it > 0 }
        if (ltp == null) { runCatching { Diag.record("orphans", "${o.symbol} (${o.owner}): unwatched, no price yet; tried again next look") }; return }
        val stop = OrphanAudit.netStop(o.fill)
        val target = OrphanAudit.netTarget(o.fill)
        val px = { x: Double -> "%.2f".format(Locale.ENGLISH, x) }
        val words: String
        if (ltp <= stop || ltp >= target) {
            // Already beyond a level: sold now (it only lowers risk).
            val lots = (row.quantity / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
            val r = Paper.place(c, "SELL", lots, "MARKET", row.product, null, null)
            if (r.events.none { it is com.optionslab.engine.sandbox.SandboxEvent.Fill }) {
                r.orderId?.takeIf { r.ok }?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
                runCatching { Diag.record("orphans", "${o.symbol} (${o.owner}): the safety net's sale did not fill (${r.message}); tried again next look") }
                return
            }
            r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "Protection · orphan exit") } }
            words = "${o.owner}: ${o.symbol} ${o.qty} was held on paper with nothing watching it; sold @ market (price ${px(ltp)} was beyond " +
                "the safety net's stop ${px(stop)} / target ${px(target)} on its fill ${px(o.fill)})"
        } else {
            val m = Protections.protectPaper(o.symbol, row.product, row.quantity, ltp, stop, null, target)
            if (!m.startsWith("Protected")) { runCatching { Diag.record("orphans", "${o.symbol} (${o.owner}): unwatched; $m") }; return }
            words = "${o.owner}: ${o.symbol} ${o.qty} was held on paper with nothing watching it; the safety net set a stop ${px(stop)} and a " +
                "target ${px(target)} (-15% / +30% of its fill ${px(o.fill)})"
        }
        pending.done(o)
        runCatching { Diag.record("orphans", words) }
        runCatching { Notifier.post(context, NOTICE + (NET_OWNERS.indexOf(o.owner).coerceAtLeast(0)), Notifier.SELL, "${o.owner}: ${o.symbol} protected · Paper", "$words.", "almanac") }
    }

    /** For tests: the sightings forgotten. */
    internal fun wipe() = pending.clear()
}
