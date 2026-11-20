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
    /** The Strategy Module's orders' tag ([Strategies]). */
    private const val STRATEGY_TAG = "iraalgostrat"
    /** 19 Nov 2026 (final review): a strategy's exit sale resting longer than this is told to Boss. */
    private const val STRATEGY_COVER_MS = 60_000L
    /** ... and longer than this, taken over by the square-off (cancelled, then sold). */
    const val TAKEOVER_MS = 120_000L
    /**
     * 20 Nov 2026 (minor fixes, item 2: the stuck-exit alert came every minute from 15:15, its key carrying the minutes left): a
     * strategy's stuck exit sale is told once per leg per stuck episode (its order), then reminded at most this often while it lasts.
     */
    const val STRATEGY_REMIND_MS = 15 * 60_000L

    /**
     * The once-a-day key ([ExpirySquareOff] / [FlatBySquareOff] tell each key once a day) of a strategy's stuck exit alert: [what]
     * ("resting", "taken over") on [symbol], its order [orderId] (one stuck episode), and the 15-minute window of [sinceMs] (how long it
     * has been past the alert's own threshold; under 0 counts as 0) - so one alert per episode, one reminder per [STRATEGY_REMIND_MS].
     */
    internal fun strategyAlertKey(what: String, symbol: String, orderId: String, sinceMs: Long): String =
        "strategy exit $what $symbol $orderId ${sinceMs.coerceAtLeast(0L) / STRATEGY_REMIND_MS}"
    private val STAMP = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** How long it has rested by the market's clock (Zerodha's stamps are IST; a phone behind it reads young; unread: 0). */
    private fun restedMs(o: Broker.OrderRow): Long = runCatching {
        val at = java.time.LocalDateTime.parse(o.placedAt, STAMP).atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()
        Market.now().toInstant().toEpochMilli() - at
    }.getOrDefault(0L)

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

    /** 19 Nov 2026 (final review): nothing of it is out after all (a LIMIT confirmed cancelled unfilled): the gap is not kept. */
    private fun clearPlaced(exchange: String, symbol: String, product: String) { placedAt.remove("$exchange:$symbol:$product") }

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

    /**
     * 20 Nov 2026 (audit, the general version of what Liquidity's own exits do): the resting orders [ids] on [exchange]:[symbol] (and
     * a protection's day GTT on it) that this square-off is about to take out, marked as the app's own cancels for every book that may
     * own one, as Boss's exit from the app marks them: the protections ([Protections.noteTakenOver]: a stop or target, or a day GTT,
     * found gone while the position is still held is placed again), the arms' live book ([ArmLive.markOwnCancels]) and the ORB /
     * Liquidity book ([OrbArms.markOwnCancels]). Memory at once; none of them waits on a book's lock or the disk.
     */
    private suspend fun markTaken(exchange: String, symbol: String, ids: List<String>, gtts: List<String>) {
        if (ids.isEmpty() && gtts.isEmpty()) return
        runCatching { Protections.noteTakenOver(ids + gtts) }
        if (ids.isEmpty()) return
        val armIds = runCatching { ArmLive.view.value.let { v -> ids.filter { id -> !v.loaded || v.open.any { it.stopOrderId == id } } } }.getOrDefault(ids)
        runCatching { ArmLive.markOwnCancels(armIds) }
        runCatching { OrbArms.markOwnCancels(ids.filter { OrbArms.mayOwnStop(it) }) }
    }

    /** 20 Nov 2026: [markTaken]'s marks taken back for [ids] whose cancel did not take (still working): a later cancel of them is Boss's. */
    private suspend fun untake(ids: List<String>) {
        if (ids.isEmpty()) return
        runCatching { Protections.forgetTakenOver(ids) }
        runCatching { ArmLive.unmarkOwnCancels(ids) }
        runCatching { OrbArms.unmarkOwnCancels(ids) }
    }

    suspend fun send(p: Broker.Position, lotSize: Int, tickSize: Double, read: List<Broker.OrderRow>, working: List<Broker.OrderRow>,
                     positions: Broker.Positions, limits: Kite.Limits, who: String, tag: String, origin: String,
                     aboutIt: String, exitName: String, multiplier: Int = 1, backstop: Boolean = false, minutesLeft: Int? = null,
                     tell: (String, String) -> Unit): Boolean {
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
            // 19 Nov 2026 (final review): a strategy's own exit sale working ([STRATEGY_TAG], not its resting stop) is its closing in
            // progress, so cover - a cancel racing its fill, then a sale here, could sell twice. (The Strategy Module's own exit reads the
            // orders and what is held in the contract's exit turn, the lag guard always on, so it never sends again over a sale here.)
            // Not for ever: from [backstop] (the expiry square-off's own backstop minute), or once it has rested [TAKEOVER_MS] (a
            // protected MARKET left behind as the price moved), it is cancelled, confirmed out with nothing more filled, and the
            // square-off sells what is held - so a stuck one never carries the position into the settlement.
            fun takeOver(o: Broker.OrderRow) = backstop || restedMs(o) > TAKEOVER_MS
            val cover = same.filter { it.tag == tag || it.tag in TAGS || (it.tag == com.optionslab.engine.orb.LiveMirror.TAG && !isStop(it)) ||
                (it.tag == STRATEGY_TAG && !isStop(it) && !takeOver(it)) }
            same.firstOrNull { it.tag == STRATEGY_TAG && !isStop(it) && (restedMs(it) > STRATEGY_COVER_MS || takeOver(it)) }?.let {
                if (takeOver(it)) {
                    tell(strategyAlertKey("taken over", p.symbol, it.id, restedMs(it) - TAKEOVER_MS), "${p.symbol} $aboutIt and a strategy's exit sale for it was still resting at Zerodha: " +
                        "the $exitName cancels it and sells what is held.")
                } else {
                    // Told once per stuck order, naming the minutes left when near the close (20 Nov 2026: before, one each minute from
                    // 15:15). From two minutes it is taken over (above), so in practice this is said once; the 15-minute reminder
                    // ([STRATEGY_REMIND_MS]) is for a takeover whose cancel keeps failing.
                    val left = minutesLeft
                    tell(strategyAlertKey("resting", p.symbol, it.id, restedMs(it) - STRATEGY_COVER_MS), "${p.symbol} $aboutIt and a strategy's exit sale for it is still " +
                        "resting at Zerodha" + (left?.let { " with $it minute(s) to the close" } ?: "") + ". The $exitName takes over if it does not fill " +
                        "(cancels it, then sells); check it in Trade.")
                }
            }
            val others = same.filter { it !in cover }
            // 20 Nov 2026 (audit: the square-off cancelled the stops before it knew its sale would go - a sale then refused left the
            // position with no stop): what is held (against the orders read's fills), what is still to sell and the app's own order
            // checks are worked out FIRST, from the reads already in hand. A sale the checks refuse, or a positions figure that could not
            // be worked out, leaves every resting stop where it is. (Taking the stops out changes none of these figures: the cancels
            // confirm nothing of them filled meanwhile, else the exit waits for the next pass, as before.)
            // Every resting order and GTT taken out ([takeOut]) is marked first as the app's own cancel for each book that may own it
            // ([markTaken]), so a stop of theirs found cancelled while the position is still held - the sale refused or failed below -
            // is put back at Zerodha by its owner, never read as Boss's own cancel in Kite (CLAUDE.md, "WHO CANCELLED A STOP"). The
            // Strategy Module rests a leg's cancelled stop again by itself; Pine watches its level itself.
            // (Review: the protections' GTT marks, fresh ones included, as [Protections.dayGttsOut] takes them out.)
            val gttMarks = runCatching { Protections.dayGttMarks(p.exchange, p.symbol) }.getOrDefault(emptyList())
            suspend fun takeOut(): Boolean {
                markTaken(p.exchange, p.symbol, others.map { it.id }, gttMarks)
                // A protection's stop and target resting as one GTT ([DayOco]) would still fire after this exit sold the position:
                // deleted first, confirmed (or the exit waits for the next pass). Not confirmed: the orders' marks taken back (none was
                // cancelled yet), and the marks of the GTTs still active (review: one still resting that Boss deletes later is his own);
                // a GTT this pass did delete keeps its mark, so it is put back while the position is held. The list not read: kept.
                if (!Protections.dayGttsOut(p.exchange, p.symbol, who)) {
                    val active = runCatching { Broker.gtts().filter { it.active }.map { "gtt:${it.id}" }.toSet() }.getOrNull()
                    untake(others.map { it.id } + gttMarks.filter { active != null && it in active })
                    return false
                }
                if (others.isEmpty()) return true
                var filled = false; var allOut = true
                val cancelled = HashSet<String>()
                for (o in others) {
                    runCatching { Broker.cancel(o.id, o.variety) }
                    val st = runCatching { Broker.orderState(o.id) }.getOrNull()
                    if (st?.status == "CANCELLED") cancelled += o.id
                    // 19 Nov 2026: more of it filled since the read (a strategy's exit taken over, part filled as it came out) is a
                    // fill too - the next pass reads the books again, never a sale on what this read showed.
                    if (st?.status == "COMPLETE" || (st != null && st.filled > o.filled)) filled = true else if (st?.status !in DONE) allOut = false
                }
                if (filled || !allOut) {
                    // (Those whose cancel did not take are still their owners': the marks taken back, so a later cancel by Boss is his.)
                    // (Review: the book not read - every mark taken back but those this pass saw cancelled, which stay the app's.)
                    val still = runCatching { Broker.orders().filter { it.working }.map { it.id }.toSet() }.getOrNull()
                    untake(others.map { it.id }.filter { it !in cancelled && (still == null || it in still) })
                    return false
                }
                return true
            }
            val pending = cover.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) }
            // What is held, checked against the orders read's fills: an exit sent from here that filled a moment ago is never
            // sold again while the positions catch up ([ExitCoordinator.held]). 19 Nov 2026 (second review of the clock lane: a
            // square-off can now run twice back to back, the clock lane's run and the pass's): the lag guard always acts here
            // ([forceLag], as [Protections] has since 15 Nov), whatever its switch says - it only ever lowers what is sent. Boss's
            // choice (19 Nov 2026, "Go"): the square-offs ALWAYS use the positions-lag guard ([noteBossChoice] says so once in the diary).
            noteBossChoice()
            val heldNow = runCatching { ExitCoordinator.held(p.exchange, p.symbol, p.product, who, read, positions, forceLag = true) }.getOrNull() ?: return false
            // Closed meanwhile, or still closing (an exit already working covers it): what rests on it is taken out as before (two
            // exits never both fill), and nothing more is sent; the next pass reads the books again.
            if (heldNow == 0 || (heldNow > 0) != (p.qty > 0)) { takeOut(); return false }
            val qty = com.optionslab.engine.risk.ExitQty.sendable(heldNow, pending, abs(heldNow), lotSize)
            if (qty <= 0) { takeOut(); return false }
            // Above the exchange freeze quantity the exit goes as several orders, each one the exchange accepts.
            val orders = Kite.slices(qty, lotSize, Kite.freezeQuantity(p.exchange, p.symbol))
                .map { Kite.Order(p.symbol, side, it, lotSize, p.product, "MARKET", null, tickSize, p.exchange, tag, multiplier = multiplier) }
            val bad = orders.flatMap { Kite.refusals(it, limits, Broker.sentToday(), false, exit = true) }.distinct()
            if (bad.isNotEmpty()) {
                tell("refused ${p.symbol}", "${p.symbol} $aboutIt and its exit was not sent (${bad.joinToString("; ")}). Close it yourself in Trade.")
                return false
            }
            if (!takeOut()) return false
            val placed = ArrayList<String>()
            for (o in orders) {
                try {
                    // 19 Nov 2026 (third review): the contract's gap starts with each order placed, not after the whole loop.
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
                            // 19 Nov 2026 (third review): a lost reply is not a refusal - the LIMIT may be out, so the gap starts and it
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
                                // Confirmed cancelled, nothing filled, nothing else out this time: no gap (the retry near 15:05 is not held).
                                if (placed.isEmpty()) clearPlaced(p.exchange, p.symbol, p.product)
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
            // 20 Nov 2026 (review): the sale sent, the stops it took out are put back by the protections only once the order book shows
            // it COMPLETE and the positions catch up - as for Boss's own exit from the app ([Protections.exitFilledFor]) - never on a
            // positions read that still shows what the sale sold. A sale refused or not filled: put back after that wait.
            if (placed.isNotEmpty() && (others.isNotEmpty() || gttMarks.isNotEmpty()))
                runCatching { Protections.exitFilledFor(others.map { it.id } + gttMarks, placed.associateWith { 1 }) }
            return placed.isNotEmpty()
        } finally { pass.leave() }
    }
}
