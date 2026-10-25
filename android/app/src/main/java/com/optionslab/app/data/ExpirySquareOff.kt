package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Kite

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
    const val AT_MINUTE = com.optionslab.engine.NseHours.EXPIRY_SQUARE_OFF
    /** 20 Oct: from here an arm's live trade still open on its expiry is closed by this square-off too (its own 15:05 missed it). */
    const val BACKSTOP_MINUTE = AT_MINUTE + 3
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

    /** Whether an arm's live trade ([ArmLive]) holds [symbol] (memory only); replaceable in tests. */
    @Volatile internal var armOwns: (String) -> Boolean = { ArmLive.ownerOf(it) != null }

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
        // Until F&O closes (15:40 from 3 Aug 2026): an expiring option still trades, and can be closed, to then.
        if (minuteNow() < AT_MINUTE || minuteNow() >= Market.foClose(today)) return
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
            // 20 Oct, review: SENSEX's options (BFO) too, from the day's list in memory: they were all "unknown", so Boss's own
            // SENSEX options were never closed and an arm's SENSEX trade raised a false "NOT closed automatically" notice.
            val ins = (Broker.cachedInstruments().orEmpty() + Broker.cachedBfoOptions().orEmpty()).associateBy { it.tradingSymbol }
            // 20 Oct, review: the orders are read BEFORE the positions (as [McxGuard] has since 18 Oct). Read the other way, an
            // exit resting from the last pass that filled between the two reads showed the position still open and no exit
            // working, and a second MARKET sell went out and left the account short on expiry day. In this order a fill
            // between the reads shows as still working (nothing more is sent) and the next pass sees the position gone.
            val read = runCatching { Broker.orders() }.getOrElse { unread = true; return@runCatching }
            val working = read.filter { it.working }
            val positions = Broker.positionBook()
            val book = positions.net.filter { it.qty != 0 }
            // A derivative the app's contract list does not know (BFO, a stock option, a list not loaded yet): its
            // expiry cannot be told here, so the owner is told instead of it being held into settlement unseen.
            // SENSEX's list not read yet today: asked for now (in the background) and the notice waits for the next passes, until
            // the backstop minute, when it is said whatever.
            val bfoWait = Broker.cachedBfoOptions() == null && minuteNow() < BACKSTOP_MINUTE
            if (Broker.cachedBfoOptions() == null && book.any { it.exchange == "BFO" }) {
                runCatching { Broker.loadBfoOptionsInBackground() }
                // Its expiry not known yet: the day is not done (a later pass, with the list in, closes or reports it).
                unread = true
            }
            book.filter { it.exchange in setOf("NFO", "BFO") && it.symbol !in ins && Regex("(CE|PE|FUT)$").containsMatchIn(it.symbol) }
                .filter { !(it.exchange == "BFO" && bfoWait) }.forEach {
                tellOnce(context, "unknown ${it.exchange}:${it.symbol}", "${it.exchange} ${it.symbol} (${it.qty}) is open and the app cannot tell when it expires, " +
                    "so it is NOT closed automatically. If it expires today, close it yourself in Trade.")
            }
            // 20 Oct, review: an arm's live trade ([ArmLive]) is sold by its own 15:05 square-off (LiveMirror.squareOffDue). Two
            // senders reading the same books at once could each sell it (a short on expiry day), so it is left to its owner until
            // [BACKSTOP_MINUTE]; after that this closes whatever is still open, as before. ORB's trades exit at 15:10 by their own
            // rules, so they stay this square-off's at 15:05.
            val backstop = minuteNow() >= BACKSTOP_MINUTE
            val expiring = book.filter { it.exchange == "NFO" || it.exchange == "BFO" }
                .filter { p -> ins[p.symbol]?.let { it.expiry == today && !isTicketLeg(it.name, it.strike, it.right.name) } == true }
            // Still counted open (the day is not done) while left to its owner, so the backstop pass still sees it.
            stillOpen += expiring.size
            val open = expiring.filter { p -> backstop || !runCatching { armOwns(p.symbol) }.getOrDefault(false) }
                .sortedBy { if (it.qty < 0) 0 else 1 }
            // Exits already working at Zerodha (an earlier pass's order resting in a thin book, or one whose
            // reply was lost; read above, before the positions) are subtracted first, so a retry never sends a second exit.
            for (p in open) {
                // 20 Oct, enhancement round 2: each contract in its one exit turn ([ExitCoordinator]); an exit sent from here since this
                // pass read the orders (an arm's, a protection's, Boss's own) leaves the contract to the next pass, which reads again.
                val i = ins.getValue(p.symbol)
                val side = if (p.qty < 0) Kite.Side.BUY else Kite.Side.SELL
                val same = working.filter { it.symbol == p.symbol && it.side == side.name && it.product == p.product }
                // Second review: at the backstop, an arm's own exit still working on it (its market sell, or its stop it is about to
                // replace with one) is its closing in progress: never cancelled here (it would only rest it again, and the two
                // could both sell). The arm is left to finish; this pass sends nothing for it and says so once.
                if (backstop && runCatching { armOwns(p.symbol) }.getOrDefault(false) && same.any { it.tag == com.optionslab.engine.orb.LiveMirror.TAG }) {
                    tellOnce(context, "arm closing ${p.symbol}", "${p.symbol} expires today and its arm is still closing it at Zerodha; watch it in Trade.")
                    continue
                }
                // 22 Oct: the exit itself as the flat-by sends it too ([SquareOffExit]: one exit turn, other resting exits cancelled
                // first, the day's GTT out first, fills checked, freeze slices, refusals said once, a lost reply looked for).
                val placed = SquareOffExit.send(p, i.lotSize, i.tickSize, read, working, positions, s.limits(), "expiry square-off", "iraalgoexpiry",
                    Origins.EXPIRY, "expires today", "expiry") { key, text -> tellOnce(context, key, text) }
                if (placed) closed += "live ${p.symbol}"
            }
        }.onFailure { unread = true }
        // 18 Oct, review: a linked account with no session now (the morning's token not renewed yet, or dropped by Zerodha)
        // is a live book not read, not an empty one: the day is not marked done, so a login before F&O's close still closes
        // what expires today (it was marked done at 15:05 with an empty paper book, and a 15:10 login closed nothing).
        if (!Broker.loggedIn && Broker.linked) unread = true

        if (!unread && stillOpen == 0) SecurePrefs.put(K_DONE, today.toString())
        if (closed.isNotEmpty()) Notifier.post(context, 2017, Notifier.APPROVAL, "Expiry square-off at 15:05",
            "Sent the close of ${closed.size} position(s) expiring today: ${closed.joinToString()}. Checked again until none is left.", "trade")
    }
}
