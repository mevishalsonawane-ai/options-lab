package com.optionslab.app.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.optionslab.app.MainActivity
import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Broker
import com.optionslab.app.data.Origins
import com.optionslab.app.data.Paper
import java.util.Locale
import kotlin.math.abs

/**
 * One notification per open position, paper and Zerodha, kept live: quantity,
 * average, last price and P&L, rewritten on every pass of the market watch
 * (every 15 s while anything is open). Each carries a Close button:
 *
 *  - paper: closes the position straight away (no money is involved);
 *  - Zerodha: opens the app on that position's close popup, where the usual
 *    review and PIN / fingerprint confirm the real order (never sent from the
 *    notification shade itself).
 *
 * When a position closes, its card turns into the final result and can be
 * swiped away. A fill notification uses the same card, so a position is one
 * notification that starts with the fill and then stays current.
 */
object PositionCards {
    const val ACTION_CLOSE_PAPER = "com.optionslab.app.CLOSE_PAPER"
    const val EXTRA_SYMBOL = "symbol"

    private val shown = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** Who opened each carded position ("venue|symbol" -> "ORB + Manual"), and the quantity that was read at. */
    private val sources = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val sourceQty = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile var anyOpen = false
        private set

    fun idOf(venue: String, symbol: String) = 30_000 + ("$venue|$symbol".hashCode() and Int.MAX_VALUE) % 5_000

    private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + String.format(Locale.ENGLISH, "%,.0f", abs(x))
    private fun px(x: Double) = String.format(Locale.ENGLISH, "%.2f", x)

    private fun closeAction(context: Context, venue: String, symbol: String): NotificationCompat.Action {
        val pi = if (venue == "Paper") PendingIntent.getBroadcast(
            context, idOf(venue, symbol),
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_CLOSE_PAPER).putExtra(EXTRA_SYMBOL, symbol),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ) else PendingIntent.getActivity(
            context, idOf(venue, symbol),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_CLOSE, symbol).putExtra(MainActivity.EXTRA_NONCE, MainActivity.nonce()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action(0, if (venue == "Paper") "Close position" else "Close…", pi)
    }

    /**
     * The card's P&L line: [pnl] (the symbol's day, trades already closed on it plus the open one) and that P&L as a
     * percentage of what the open position cost (qty x average), so the sign of the two always agrees.
     */
    internal fun pnlLine(qty: Int, avg: Double, pnl: Double): String {
        val cost = avg * abs(qty)
        return "P&L ${rs(pnl)}" + (if (cost > 0) " (%+.1f%%)".format(Locale.ENGLISH, 100 * pnl / cost) else "")
    }

    /**
     * Post or rewrite a position's card. [alert] makes it sound (a fill); updates are silent.
     * [headline] replaces the first line (e.g. "BUY filled · Paper · Strategy: ORB"). [source] is who opened
     * the position ("ORB + Manual"); the last one given is kept for the card's silent updates.
     */
    fun card(context: Context, venue: String, symbol: String, qty: Int, avg: Double, ltp: Double?, pnl: Double,
             alert: Boolean = false, headline: String? = null, source: String? = null) {
        // A position closed (squared off, stopped out, settled): its card is taken down, not left as a result.
        if (qty == 0) { dismiss(context, venue, symbol); return }
        if (source != null) sources["$venue|$symbol"] = source
        if (!Notifier.canPost(context)) return
        val open = qty != 0
        val by = sources["$venue|$symbol"]?.let { "\n" + com.optionslab.app.data.Origins.positionDisplay(it).first } ?: ""
        val title = headline ?: if (open) "$symbol · $venue · ${rs(pnl)}" else "Closed $symbol · $venue · ${rs(pnl)}"
        val text0 = if (open) "${if (qty > 0) "LONG" else "SHORT"} ${abs(qty)} @ ${px(avg)}" + (ltp?.let { " · LTP ${px(it)}" } ?: "") +
            "\n" + pnlLine(qty, avg, pnl)
            else "Realised P&L ${rs(pnl)}"
        val text = text0 + by
        val b = Notifier.builder(context, if (qty >= 0) Notifier.BUY else Notifier.SELL, title, text, "trade")
            .setOnlyAlertOnce(!alert).setSilent(!alert).setOngoing(open).setAutoCancel(!open)
        if (open) b.addAction(closeAction(context, venue, symbol))
        try { NotificationManagerCompat.from(context).notify(idOf(venue, symbol), b.build()) } catch (_: SecurityException) {}
        if (open) shown["$venue|$symbol"] = true
    }

    /** Bumped when a position is closed from its card in the shade: the open app reloads its books at once. */
    val closedFromShade = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Take down every card on [venue] ("Paper" after the paper account is reset). */
    fun dismissAll(context: Context, venue: String) {
        shown.keys.filter { it.startsWith("$venue|") }.forEach { dismiss(context, venue, it.substringAfter('|')) }
    }

    /** Take down [symbol]'s card on [venue] ("Paper" / "Live"): the position is closed. */
    fun dismiss(context: Context, venue: String, symbol: String) {
        val key = "$venue|$symbol"
        shown.remove(key); sources.remove(key); sourceQty.remove(key)
        runCatching { NotificationManagerCompat.from(context).cancel(idOf(venue, symbol)) }
    }

    @Volatile private var lastLive: Broker.Positions? = null

    /**
     * Between position-book readings: rewrite the Zerodha cards from the live price stream
     * (the book's P&L moved on by each tick). Cheap - no network - so it runs every few seconds.
     */
    fun tickLive(context: Context) {
        val book = lastLive ?: return
        if (com.optionslab.app.data.KiteStream.status.value != com.optionslab.app.data.KiteStream.Status.LIVE) return
        val moved = com.optionslab.app.data.KiteStream.live(book)
        moved.net.filter { it.qty != 0 && shown.containsKey("Live|${it.symbol}") }.forEach { p ->
            card(context, "Live", p.symbol, p.qty, p.avg, p.last.takeIf { it > 0 }, p.pnl)
        }
        widgetFromStream(context, moved.pnl)
    }

    @Volatile private var widgetAt = 0L

    /** The home-screen widget from the live stream: index levels and the Zerodha P&L, at most every 5 s. */
    fun widgetFromStream(context: Context, pnl: Double?) {
        val st = com.optionslab.app.data.KiteStream
        if (st.status.value != com.optionslab.app.data.KiteStream.Status.LIVE) return
        val now = System.currentTimeMillis()
        if (now - widgetAt < 5_000) return
        widgetAt = now
        val nifty = st.tick(256265L)?.let { it.last to it.changePct }
        val bank = st.tick(260105L)?.let { it.last to it.changePct }
        if (nifty == null && bank == null) return
        runCatching { com.optionslab.app.widget.IraWidget.publish(context, nifty, bank, pnl) }
    }

    /** Rewrite every card from the accounts' current positions; a position gone since the last pass gets its final card. */
    suspend fun refresh(context: Context) {
        val s = runCatching { AppSettings.load() }.getOrNull() ?: return
        val now = HashMap<String, Unit>()
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        runCatching { Paper.snapshot() }.getOrNull()?.let { snap -> snap.positions.positions.map { it to snap.trades } }?.forEach { (p, trades) ->
            val key = "Paper|${p.symbol}"
            if (p.quantity != 0) {
                now[key] = Unit
                card(context, "Paper", p.symbol, p.quantity, p.averagePrice, p.ltp.takeIf { it > 0 }, p.pnl,
                    source = com.optionslab.app.data.Origins.paperPosition(owners, trades, p.symbol, p.product, p.quantity))
            }
            else if (shown.remove(key) != null) card(context, "Paper", p.symbol, 0, p.averagePrice, null, p.pnl)
        }
        if (s.live && Broker.loggedIn) runCatching { Broker.positionBook() }.getOrNull()?.also { book ->
            lastLive = book
            com.optionslab.app.data.KiteStream.want("positions", book.net.filter { it.qty != 0 }.map { it.token })
        }?.let { com.optionslab.app.data.KiteStream.live(it) }?.net?.also { net ->
            // Who opened a Zerodha position is read from the day's trades only when it is new or its size changed.
            val stale = net.filter { it.qty != 0 && sourceQty["Live|${it.symbol}"] != it.qty }
            if (stale.isNotEmpty()) runCatching {
                val trades = Broker.trades()
                val orders = Broker.orders()
                stale.forEach { p ->
                    Origins.livePosition(owners, trades, orders, p.symbol, p.product, p.qty)?.let { sources["Live|${p.symbol}"] = it }
                    sourceQty["Live|${p.symbol}"] = p.qty
                }
            }
        }?.forEach { p ->
            val key = "Live|${p.symbol}"
            if (p.qty != 0) { now[key] = Unit; card(context, "Live", p.symbol, p.qty, p.avg, p.last.takeIf { it > 0 }, p.pnl) }
            else if (shown.remove(key) != null) card(context, "Live", p.symbol, 0, p.avg, null, p.pnl)
        }
        // A card whose position vanished from the book entirely (a new session): no longer ongoing.
        shown.keys.filter { it !in now }.forEach { k ->
            shown.remove(k)
            runCatching { NotificationManagerCompat.from(context).cancel(idOf(k.substringBefore('|'), k.substringAfter('|'))) }
        }
        anyOpen = now.isNotEmpty()
    }
}
