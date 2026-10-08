package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Kite
import kotlin.math.abs

/**
 * TODO A5: on an expiry day, at 15:05, close every open option position that
 * expires today - paper and live, every product (MIS and NRML) - instead of
 * holding it into the settlement. Runs from the market watch every pass
 * until a pass finds nothing expiring today still open (an exit sent is not
 * a position closed); then it does not repeat that day.
 *
 * The Expiry Put ticket is designed to be held to the 15:30 settlement, so its
 * legs are left alone unless the owner turns that off. The kill switch does
 * not stop it: closing what expires today only lowers risk.
 */
object ExpirySquareOff {
    const val AT_MINUTE = 15 * 60 + 5
    private const val K_DONE = "sq.expiry.done"

    /**
     * TEST ONLY: a fixed clock for this pass's day and minute. Null in the app, always: the day and minute are then
     * [Market.today] and [Market.minuteNow], exactly as before. Its setter throws unless BuildConfig.DEBUG (as
     * Broker.testEndpoint), and no app code sets it; only the unit tests do.
     */
    @Volatile internal var testNow: java.time.ZonedDateTime? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test clock exists only in debug builds" }; field = v }
    private fun todayIst(): java.time.LocalDate = testNow?.toLocalDate() ?: Market.today()
    private fun minuteNow(): Int = testNow?.let { it.hour * 60 + it.minute } ?: Market.minuteNow()

    /** What the owner was already told today ("day|what"), so a retry every pass does not repeat it. */
    private val told = HashSet<String>()

    private fun tellOnce(context: Context, key: String, text: String) {
        val k = "${todayIst()}|$key"
        synchronized(told) { if (!told.add(k)) return }
        // Notifier.post also drops it in as the in-app banner: one call, one banner.
        runCatching { Notifier.post(context, 2032, Notifier.RISK, "Expiry square-off", text, "trade") }
    }

    suspend fun maybeRun(context: Context, s: AppSettings) {
        // The kill switch stops new orders, not this: closing what expires today only lowers risk.
        if (!s.expirySquareOff) return
        val today = todayIst()
        if (minuteNow() < AT_MINUTE || minuteNow() >= Market.CLOSE) return
        if (SecurePrefs.getString(K_DONE) == today.toString()) return
        val ticket = Ledger.openTicket()?.row?.ticket?.takeIf { s.keepExpiryPut && it.session == today }
        fun isTicketLeg(underlying: String?, strike: Double?, right: String?): Boolean =
            ticket != null && underlying == ticket.underlying && right == "PE" && (strike == ticket.strike || strike == ticket.wingStrike)

        val closed = ArrayList<String>()
        // Done for the day only once a pass finds nothing expiring still open (and could read every book):
        // an order placed is not a position closed (an RMS-rejected MARKET exit is sent again next pass).
        var unread = false
        var stillOpen = 0

        // Paper: every option position expiring today.
        runCatching {
            val snap = Paper.snapshot()
            for (p in snap.positions.positions.filter { it.quantity != 0 }) {
                val c = Paper.contractOf(p.symbol) ?: continue
                // MCX has its own expiry exit (the day before, by 23:00: [McxGuard]); never this 15:05 one.
                if (c.isMcx) continue
                if (c.expiry != today || isTicketLeg(c.underlying, c.strike, c.right.name)) continue
                // Night (R3)'s own overnight position goes at its own 09:16 sale (it is never bought into its expiry).
                if (NightArm.holds(p.symbol)) continue
                stillOpen++
                val r = Paper.close(p.symbol, p.product)
                r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", Origins.EXPIRY) } }
                if (r.ok) closed += "paper ${p.symbol}" else tellOnce(context, "paper ${p.symbol}", "Paper ${p.symbol} could not be closed: ${r.message}. Retrying.")
            }
        }.onFailure { unread = true }

        // Live: whenever there is a Zerodha session (the positions are real even while the app shows Paper);
        // shorts are bought back before longs are sold.
        if (Broker.loggedIn) runCatching {
            val ins = Broker.cachedInstruments().orEmpty().associateBy { it.tradingSymbol }
            val book = Broker.positionBook().net.filter { it.qty != 0 }
            // A derivative the app's contract list does not know (BFO, a stock option, a list not loaded yet): its
            // expiry cannot be told here, so the owner is told instead of it being held into settlement unseen.
            book.filter { it.exchange in setOf("NFO", "BFO") && it.symbol !in ins && Regex("(CE|PE|FUT)$").containsMatchIn(it.symbol) }.forEach {
                tellOnce(context, "unknown ${it.exchange}:${it.symbol}", "${it.exchange} ${it.symbol} (${it.qty}) is open and the app cannot tell when it expires, " +
                    "so it is NOT closed automatically. If it expires today, close it yourself in Trade.")
            }
            val open = book.filter { it.exchange == "NFO" }
                .filter { p -> ins[p.symbol]?.let { it.expiry == today && !isTicketLeg(it.name, it.strike, it.right.name) } == true }
                .sortedBy { if (it.qty < 0) 0 else 1 }
            stillOpen += open.size
            // Exits already working at Zerodha (an earlier pass's order resting in a thin book, or one whose
            // reply was lost) are subtracted first, so a retry never sends a second exit and reverses the position.
            val working = runCatching { Broker.orders() }.getOrElse { unread = true; return@runCatching }.filter { it.working }
            for (p in open) {
                val i = ins.getValue(p.symbol)
                val side = if (p.qty < 0) Kite.Side.BUY else Kite.Side.SELL
                val same = working.filter { it.symbol == p.symbol && it.side == side.name && it.product == p.product }
                // Any other resting exit on it (a protection's stop or target, a strategy's stop) would keep the
                // market exit from ever going out: cancel them first; the exit goes on the next pass.
                val others = same.filter { it.tag != "iraalgoexpiry" }
                if (others.isNotEmpty()) {
                    others.forEach { runCatching { Broker.cancel(it.id, it.variety) } }
                    continue
                }
                val pending = same.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) }
                val qty = com.optionslab.engine.risk.ExitQty.sendable(p.qty, pending, abs(p.qty), i.lotSize)
                if (qty <= 0) continue                                      // still closing; checked again next pass
                // Above the exchange freeze quantity the exit goes as several orders, each one the exchange accepts.
                val orders = Kite.slices(qty, i.lotSize, Kite.freezeQuantity("NFO", p.symbol))
                    .map { Kite.Order(p.symbol, side, it, i.lotSize, p.product, "MARKET", null, i.tickSize, "NFO", "iraalgoexpiry") }
                val bad = orders.flatMap { Kite.refusals(it, s.limits(), Broker.sentToday(), false, exit = true) }.distinct()
                if (bad.isNotEmpty()) {
                    tellOnce(context, "refused ${p.symbol}", "${p.symbol} expires today and its exit was not sent (${bad.joinToString("; ")}). Close it yourself in Trade.")
                    continue
                }
                val placed = ArrayList<String>()
                for (o in orders) {
                    try {
                        placed += Broker.placeOrder(o, exit = true).also { runCatching { Strategies.tagOwner("kite:$it", Origins.EXPIRY) } }
                    } catch (e: Exception) {
                        if (Broker.definite(e)) {
                            tellOnce(context, "rejected ${p.symbol} ${e.message}", "Zerodha refused the expiry exit of ${p.symbol}: ${e.message}. Retrying every pass until 15:30; close it yourself if it keeps failing.")
                            break
                        }
                        // The reply was lost, not necessarily the order: look for it before trying again.
                        runCatching { Broker.findRecentRetrying(o, placed + working.map { it.id }) }.getOrNull()?.let { placed += it }
                        break
                    }
                }
                if (placed.isNotEmpty()) closed += "live ${p.symbol}"
            }
        }.onFailure { unread = true }

        if (!unread && stillOpen == 0) SecurePrefs.put(K_DONE, today.toString())
        if (closed.isNotEmpty()) Notifier.post(context, 2017, Notifier.APPROVAL, "Expiry square-off at 15:05",
            "Sent the close of ${closed.size} position(s) expiring today: ${closed.joinToString()}. Checked again until none is left.", "trade")
    }
}
