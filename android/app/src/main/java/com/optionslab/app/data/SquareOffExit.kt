package com.optionslab.app.data

import com.optionslab.engine.Kite
import kotlin.math.abs

/**
 * One live position's square-off exit, as the expiry square-off has sent it since 20 Oct ([ExpirySquareOff]) and the flat-by
 * sends it from 22 Oct ([FlatBySquareOff]): each contract in its one exit turn ([ExitCoordinator]), other resting exits on it
 * cancelled first (the exit goes on the next pass), the day's GTT deleted first, what is held checked against the orders
 * read's fills, exits already working subtracted, the quantity in freeze-size slices of MARKET orders in the position's own
 * product, every refusal said once, and a lost reply looked for before the order is called unsent. 22 Nov 2026: an arm's exit sale
 * whose reply was lost and is not resolved yet ([lostPendingProviders]) is subtracted too, and nothing resting is taken out meanwhile.
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
    internal fun resetForTest(gap: Long = GAP_MS) { placedAt.clear(); clock = System::currentTimeMillis; gapMs = gap; claims.clear(); skips.clear(); soldAt.clear() }

    /**
     * 22 Nov 2026 (B6 review, fourth round: an arm's look that began before a square-off sold its row, and reached the row after the gap,
     * put a stop back on a flat position from its old reads): by position row ("EXCHANGE:SYMBOL:PRODUCT"), when (by [clock]) a square-off
     * last sent a sale on it - placed, or its reply lost ([send]). A book putting a stop back puts none when this is not older than its
     * own read of the positions ([OrbArms]); the row waits for a look that reads again. Memory only.
     */
    private val soldAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** 22 Nov 2026 (B6 review, fourth round): when a square-off last sent a sale on the row ([soldAt]); null: none in this process. */
    fun lastSaleAt(exchange: String, symbol: String, product: String): Long? = soldAt["$exchange:$symbol:$product"]
    /** A square-off sent a sale on the row now (placed, or its reply lost): [soldAt]. */
    internal fun noteSold(exchange: String, symbol: String, product: String) { soldAt["$exchange:$symbol:$product"] = clock() }

    /**
     * 22 Nov 2026 (B6 review: a Liquidity exit sale whose reply was lost at 15:04:50, then the 15:05 square-off sold the whole position and
     * took its stop out - had the lost sale filled too, the account was short): the books' unresolved lost exit sales, one provider per book
     * (by its name; putting it again replaces it). Asked with a Zerodha trading symbol and a product ("MIS", "NRML" - one position row),
     * each answers the quantity of that book's own exit SALES of that row not settled yet: whose reply was lost and which are not resolved
     * yet - neither found in Zerodha's order book and booked, nor let go after a read that does not show them - and (a book that keeps them)
     * any on its way, sent or about to be, and not yet booked, which this pass's read may not show: 0 when none. It must answer from memory
     * only: it is asked inside the contract's exit turn, so never a lock wait, the disk or the network. A book whose sale is about to go
     * counts it here and calls [saleStarting] BEFORE it checks [inGap], as [send] starts the gap before its last look here; it keeps a sale
     * it has booked counted for [GAP_MS] more (a pass's read of the books can still trail it). A negative answer, or one that throws, is a
     * book that cannot tell now: counted as an unresolved sale of unknown size. While any is unresolved on a row, [send] takes nothing
     * resting on it out (a stop, a target, a GTT) and sells only what is held less those sales - nothing while anything rests there or the
     * size is unknown; the next pass reads again. [OrbArms] puts its own in at start ("orb"); another book puts its own the same way, under
     * its own name. One seller at a time on a row: [claimExit].
     */
    internal val lostPendingProviders = java.util.concurrent.ConcurrentHashMap<String, (symbol: String, product: String) -> Int>()

    /**
     * 22 Nov 2026 (B6 review, third round): the order tags of the books that count their own lost and on-their-way sales in
     * [lostPendingProviders] (by product row) - "iraorb" for [OrbArms], [com.optionslab.engine.orb.LiveMirror.TAG] for [ArmLive]. [send]
     * leaves those sales to their book's count; another sender's exit
     * sale sent a moment ago and not in the fresh read ([ExitCoordinator.unshownPending]) holds the row back. A book adds its tag with
     * its provider.
     */
    internal val lostPendingTags: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 22 Nov 2026 (B6 review, second pass: one arm's sale ending and another's starting between [send]'s two looks left the total the
     * same): by Zerodha trading symbol and product ("SYMBOL:PRODUCT", one position row - 22 Nov 2026, second round), bumped by a book each
     * time it counts a new sale of its own there as on its way ([lostPendingProviders]), before it checks [inGap]. [send] looks at it with
     * its first look and again just before anything is taken out. (22 Nov 2026, fourth round: a sale started since the pass's own reads no
     * longer puts the row off - the fresh read under the row's claim shows it.) Memory only.
     */
    private val salesStarted = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * A book's own exit sale on [symbol] ([product]: its position row) is about to go (counted in its provider first): see [salesStarted].
     * Memory only, never blocks.
     */
    internal fun saleStarting(symbol: String, product: String = "MIS") { salesStarted.merge("$symbol:$product", 1L, Long::plus) }

    /**
     * 22 Nov 2026 (B6 review, second round: two senders on one row each checked the other and both could act - or both back off with a stop
     * already out): one seller at a time on a position row ([exchange], [symbol], [product]). A sender takes the claim BEFORE it takes any
     * stop, target or GTT out, and holds it until its own sale went (or it gave up), releasing it in a finally ([ExitClaim.release]):
     *  - [send] (the expiry square-off, the flat-by) takes it, as its [tag], before it looks at the row at all;
     *  - an arm's exit takes it, as its book's own name ("orb": [OrbArms]), before its stop comes out.
     * Null: another [owner] holds it - send nothing, take nothing out, leave every stop resting, and look again next pass. The same [owner]
     * taking it again (a nested call of its own, or two of its runs at once) gets it too, and the row stays claimed until every one of them
     * released. Never waits, never blocks; memory only (a restart starts with none).
     */
    fun claimExit(exchange: String, symbol: String, product: String, owner: String): ExitClaim? {
        val key = "$exchange:$symbol:$product"
        // (Counted per holder: two calls of the same owner at once - nested, or two runs overlapping - each hold it until each releases.)
        val h = claims.compute(key) { _, cur -> when { cur == null -> Holder(owner, 1); cur.owner == owner -> Holder(owner, cur.count + 1); else -> cur } }
        return if (h?.owner == owner) ExitClaim(key, owner) else null
    }

    /** 22 Nov 2026 (B6 review): who holds the row's claim now ([claimExit]); null: nobody. Memory only. */
    fun exitClaimedBy(exchange: String, symbol: String, product: String): String? = claims["$exchange:$symbol:$product"]?.owner

    /**
     * 22 Nov 2026 (B6 review): a claim's owner in plain words, for the diary and Boss's notices ([claimExit]'s owners: the square-offs' tags,
     * a book's name - "orb" and "orb-stop" ([OrbArms]), "armlive" ([ArmLive]) and "protections" ([Protections]) from B6-B, "boss" for
     * Boss's own exit from the app).
     */
    fun claimWords(owner: String?): String = when (owner) {
        null -> "another seller"
        "iraalgoexpiry" -> "the expiry square-off"
        "iraalgoflat" -> "the flat-by"
        "orb" -> "an ORB / Liquidity 15+5 exit"
        "orb-stop" -> "an ORB / Liquidity 15+5 stop going back at Zerodha"
        // (22 Nov 2026, B6 review: the arms' live copies' exits and the protections' app-watched targets claim their rows too.)
        "armlive" -> "an arm's live exit"
        "protections" -> "a protection's target sale"
        // (23 Nov 2026, B6-B fourth round: the arms' and the protections' stops going back hold the row under their own names.)
        "armlive-stop" -> "an arm's stop going back at Zerodha"
        "protections-stop" -> "a protection's stop going back at Zerodha"
        BOSS_OWNER -> "your exit from the app"
        // (22 Nov 2026, B6-B third round: each of Boss's sends claims under its own name, "boss#...".)
        else -> if (owner.startsWith("$BOSS_OWNER#")) "your exit from the app" else "another seller ($owner)"
    }

    /** 22 Nov 2026 (B6 review, second round): the name Boss's own exit from the app claims a row by ([claimExit]; AppModel's takeover). */
    const val BOSS_OWNER = "boss"

    /** A row's claim ([claimExit]): [release] it in a finally. Each claim releases once (a second call changes nothing). */
    class ExitClaim internal constructor(private val key: String, private val owner: String) {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)
        fun release() {
            if (!released.compareAndSet(false, true)) return
            claims.computeIfPresent(key) { _, cur -> if (cur.owner != owner) cur else if (cur.count > 1) Holder(owner, cur.count - 1) else null }
        }
    }
    private class Holder(val owner: String, val count: Int)
    private val claims = java.util.concurrent.ConcurrentHashMap<String, Holder>()

    /**
     * 22 Nov 2026 (B6 review, third round: a row skipped every pass - an arm's sale on it that keeps failing, holding the row or starting
     * again each time - was put off with only a diary line): by square-off and position row ("TAG|EXCHANGE:SYMBOL:PRODUCT"), the passes in
     * a row (none more than [SKIP_STREAK_MS] after the last) that left it unsold - an arm selling it, a sale on it not in the books yet, or
     * its books, what is held or its resting orders' cancels not readable or confirmed ([send]) - with when that streak began and when its
     * last pass was. Boss is told once a streak has lasted [SKIP_TELL_MS] (fourth round: by time - the clock lane runs the square-offs
     * every 15 s, and an arm's sale can take that long to fill), and at once from the expiry square-off's backstop minute; each streak is
     * told on its own (its start is in the notice's key). A pass that sells it, or finds nothing left to sell, ends the streak. Memory only.
     */
    private val skips = java.util.concurrent.ConcurrentHashMap<String, Skips>()
    private class Skips(val count: Int, val since: Long, val last: Long)
    /** A streak told once it is this old. */
    const val SKIP_TELL_MS = 2 * 60_000L
    /** Passes further apart than this are not "in a row" (the square-offs look every minute or more often). */
    const val SKIP_STREAK_MS = 3 * 60_000L

    /** TEST ONLY (22 Nov 2026): the passes in a row that left [exchange]:[symbol] [product] unsold ([skips]), by either square-off. */
    internal fun skipsForTest(exchange: String, symbol: String, product: String): Int =
        skips.filterKeys { it.endsWith("|$exchange:$symbol:$product") }.values.sumOf { it.count }

    /**
     * 23 Nov 2026 (B6-B, fourth round): for Boss's own exit from the app ([com.optionslab.app.ui.AppModel]) - each book's exit sales of [symbol]
     * [product] not settled yet, by book name ([claimWords] words it): lost or on their way, never one booked in the last [gapMs], whose fill
     * his exit's lag guard counts already ([bossPendingProviders]; a book without one: its [lostPendingProviders] count, booked ones too).
     * Negative: that book cannot tell now. Memory only.
     */
    internal val bossPendingProviders = java.util.concurrent.ConcurrentHashMap<String, (symbol: String, product: String) -> Int>()
    internal fun pendingForBoss(symbol: String, product: String): Map<String, Int> =
        lostPendingProviders.mapValues { (name, f) -> runCatching { (bossPendingProviders[name] ?: f)(symbol, product) }.getOrDefault(-1) }

    /**
     * The unresolved lost exit sales on [symbol]'s [product] row across [lostPendingProviders]: their quantity, or null when a book cannot
     * tell. Memory only.
     */
    internal fun lostPending(symbol: String, product: String): Int? {
        var n = 0
        for (f in lostPendingProviders.values) {
            val q = runCatching { f(symbol, product) }.getOrNull() ?: return null
            if (q < 0) return null
            n += q
        }
        return n
    }

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

    /**
     * [read], [positions]: the pass's own reads - since the third round of that review used only to leave a row alone early (a stale or
     * unreadable row); [working] is no longer read. What is taken out and sold is worked out from a fresh read of the orders made under the
     * row's claim.
     */
    suspend fun send(p: Broker.Position, lotSize: Int, tickSize: Double, read: List<Broker.OrderRow>, working: List<Broker.OrderRow>,
                     positions: Broker.Positions, limits: Kite.Limits, who: String, tag: String, origin: String,
                     aboutIt: String, exitName: String, multiplier: Int = 1, backstop: Boolean = false, minutesLeft: Int? = null,
                     tell: (String, String) -> Unit): Boolean {
        val pass = ExitCoordinator.enter(p.exchange, p.symbol, who)
        var claim: ExitClaim? = null
        val rowKey = "${p.exchange}:${p.symbol}:${p.product}"
        val skipKey = "$tag|$rowKey"
        val soldBefore = soldAt[rowKey]
        /**
         * 22 Nov 2026 (B6 review, third round): this pass leaves the row unsold ([why]); counted, and Boss told as [skips] says. [armSelling]:
         * an arm's own sale on it is going out - Boss is told to let it finish, never to sell beside it.
         */
        fun skipped(why: String, armSelling: Boolean = false) {
            val now = clock()
            val k = skips.compute(skipKey) { _, cur -> if (cur == null || now - cur.last !in 0..SKIP_STREAK_MS) Skips(1, now, now) else Skips(cur.count + 1, cur.since, now) }!!
            runCatching { Diag.record("exits", "$who $rowKey: $why; nothing taken out or sent this pass (${k.count} in a row)") }
            fun words(at: String) = "${p.symbol} $aboutIt, and the $exitName has left it unsold $at ($why). It tries again on every pass. " +
                if (armSelling) "Another exit on it is going out: let it finish - do not sell ${p.symbol} yourself while an exit sale for it " +
                    "is working in Kite's orders; if it is still open with none working, close it there."
                else "Check ${p.symbol} in Kite and close it yourself if it is still open."
            val minutes = (now - k.since) / 60_000
            if (now - k.since >= SKIP_TELL_MS) tell("skipped $rowKey ${k.since}", words("for $minutes minutes, ${k.count} passes in a row"))
            if (backstop) tell("skipped at the backstop $rowKey ${k.since}", words("at its last minutes"))
        }
        /** 22 Nov 2026 (B6 review, third round): the row worked out this pass (sold, or nothing left to sell): its count starts again. */
        fun workedOut() { skips.remove(skipKey) }
        try {
            // 19 Nov 2026: an exit placed on it a moment ago is still on its way ([GAP_MS]): nothing more now, the next run reads again.
            if (inGap(p.exchange, p.symbol, p.product)) return false
            // 20 Nov 2026 (deep review): a read that left this contract's own row (its symbol and product) out as malformed: nothing taken
            // out or sent on it now. (Final review: a row with no symbol at all never blocks an exit.)
            if (positions.unknownForExit(p.symbol, p.product)) { skipped("its row in Zerodha's positions could not be read"); return false }
            if (ExitCoordinator.stale(p.exchange, p.symbol, who, read)) { skipped("this pass's read of the orders missed an exit sent a moment ago", armSelling = true); return false }
            // 22 Nov 2026 (B6 review): one seller at a time on the row ([claimExit]): an arm is selling it now - nothing taken out or sent;
            // the next pass looks again.
            claim = claimExit(p.exchange, p.symbol, p.product, tag) ?: run {
                val holder = exitClaimedBy(p.exchange, p.symbol, p.product)
                // (Review: a stop of an arm going back at Zerodha holds the row a moment - no sale of its is going out.)
                skipped("${claimWords(holder)} held it", armSelling = holder?.endsWith("-stop") != true)
                return false
            }
            // 22 Nov 2026 (B6 review): an arm's exit sale on this row not settled yet - its reply lost, or on its way ([lostPendingProviders])
            // - may still fill: nothing resting on it is taken out meanwhile, and only what is held less that sale is sold (below). Null: a
            // book could not tell its size. (Looked at again just before anything is taken out.) (Fourth round: one started since this pass
            // read the books no longer puts the row off - pass after pass, while an arm kept restarting a refused sale - as the fresh read
            // below, under the claim, shows it: booked, lost (counted here) or refused.)
            val row = "${p.symbol}:${p.product}"
            val started = salesStarted[row] ?: 0L
            // 22 Nov 2026 (B6 review, third round: an arm's sale started just before this pass's reads, missed by them, and booked more than
            // the gap before this row was reached - shorts first, a LIMIT's 10 s wait, cancels confirmed - was sold again): with the claim
            // held no arm's sale can start on the row, so what is sold is worked out from a FRESH read of the orders (never one shared with
            // another reader, nor a kept copy) and a positions read after it, through the lag guard: an arm's sale booked before the claim
            // shows as a fill, whenever it was booked and however old this pass's reads are. That read failing, or missing an exit sent from
            // here in the last minute whose id Zerodha gave (its book trailing it, [ExitCoordinator.missesAnswered]): nothing taken out or
            // sent this pass, every stop resting. (Fourth round: the positions read, [Broker.positionBook], may join one already on its way,
            // begun up to 2 s earlier - never one begun before an order, change or cancel of this phone. That is safe here: the lag guard,
            // forced and comparing fills of any age, counts every fill in the fresh orders read those positions do not show yet as sold, so
            // it only ever sells less.)
            val booksNow = runCatching { Broker.orders(fresh = true) }.getOrNull()
                ?: run { skipped("its orders could not be read again"); return false }
            if (ExitCoordinator.missesAnswered(p.exchange, p.symbol, who, booksNow)) { skipped("Zerodha's order book did not show an exit sent a moment ago yet"); return false }
            val workingNow = booksNow.filter { it.working }
            val lost = lostPending(p.symbol, p.product)
            val lostOpen = lost != 0
            val side = if (p.qty < 0) Kite.Side.BUY else Kite.Side.SELL
            // (Also an exit on it - a protection's, the Strategy Module's, Boss's from the app, a square-off's own (fourth round), or any stop
            // whose reply was lost - sent in the last minute, still on its way or its reply lost and not found since ([Broker.findRecent]
            // gives it its id), that the fresh read does not show and no book counts ([lostPendingTags]): nothing this pass. A counting
            // book's sales are its count, [lost].)
            // (22 Nov 2026, B6-B: a book's count is subtracted from a SALE only ([lostQty] below) - a buy back of a short under its tag is
            // never left to it.)
            val unshown = ExitCoordinator.unshownPending(p.exchange, p.symbol, side.name, booksNow,
                exceptBy = if (side == Kite.Side.SELL) lostPendingTags else emptySet())
            if (unshown > 0) { skipped("another exit sale on it, sent a moment ago, is not in Zerodha's order book yet", armSelling = true); return false }
            val same = workingNow.filter { it.symbol == p.symbol && it.side == side.name && it.product == p.product }
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
                // (Review: the marks another of the app's paths made already - Boss's exit from the app - are never taken back here.)
                val premarked = others.map { it.id }.filter { runCatching { Protections.isTakenOver(it) }.getOrDefault(true) }.toSet()
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
                val notOurs = ArrayList<String>()
                for (o in others) {
                    // (Refused by Zerodha itself - not a lost reply, which may have gone through.)
                    val refused = runCatching { Broker.cancel(o.id, o.variety) }.exceptionOrNull()?.let { Broker.definite(it) } == true
                    val st = runCatching { Broker.orderState(o.id) }.getOrNull()
                    if (st?.status == "CANCELLED") { if (refused) notOurs += o.id else cancelled += o.id }
                    // 19 Nov 2026: more of it filled since the read (a strategy's exit taken over, part filled as it came out) is a
                    // fill too - the next pass reads the books again, never a sale on what this read showed.
                    if (st?.status == "COMPLETE" || (st != null && st.filled > o.filled)) filled = true else if (st?.status !in DONE) allOut = false
                }
                // 20 Nov 2026 (deep review, "WHO CANCELLED A STOP"): one already CANCELLED before this cancel went (Zerodha refused the
                // cancel: nothing working to cancel) was not the app's doing: its mark taken back, so its owner reads it as before.
                untake(notOurs.filter { it !in premarked })
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
            // (22 Nov 2026, B6 review, third round: on the fresh read under the claim, and a positions read after it - [booksNow]; a shared one
            // begun up to 2 s before it is safe, as said above.)
            val heldNow = runCatching { ExitCoordinator.held(p.exchange, p.symbol, p.product, who, booksNow, forceLag = true) }.getOrNull()
                ?: run { skipped("what is held of it could not be read"); return false }
            // 22 Nov 2026 (B6 review): while an arm's lost exit sale on it is unresolved, nothing resting on it is taken out (it may fill
            // beside them, into a short) - said once; the next pass reads again (that sale is found and booked, or let go, in about two
            // minutes).
            fun lostWaits(what: String) {
                runCatching { Diag.record("exits", "$who ${p.exchange}:${p.symbol}: an arm's exit sale on it is not settled " +
                    "(${lost ?: "size unknown"}); $what") }
                // (Said once for each new sale an arm started on it ([salesStarted]) - a later lost sale there is told too.)
                tell("unsettled $row ${salesStarted[row] ?: 0L}", "${p.symbol} $aboutIt, and an arm's own exit sale on it has not " +
                    "settled yet: it went a moment ago, or Zerodha's reply to it was lost and the app is still looking for it. The $exitName " +
                    "takes no stop or target on it out meanwhile and sells only what that sale leaves, once it has settled (about two minutes " +
                    "at most). Check ${p.symbol} in Kite.")
            }
            // Closed meanwhile, or still closing (an exit already working covers it): what rests on it is taken out as before (two
            // exits never both fill), and nothing more is sent; the next pass reads the books again. (22 Nov 2026: not while a lost
            // exit sale on it is unresolved - left resting.)
            if (heldNow == 0 || (heldNow > 0) != (p.qty > 0)) { if (lostOpen) lostWaits("nothing taken out (nothing to sell)") else { workedOut(); takeOut() }; return false }
            // (22 Nov 2026: such a lost sale counts as already going - it sells the long side, so it lowers a SALE only; of unknown size,
            // nothing is sold.)
            val lostQty = if (side == Kite.Side.SELL) lost ?: abs(heldNow) else 0
            val qty = com.optionslab.engine.risk.ExitQty.sendable(heldNow, pending + lostQty, abs(heldNow), lotSize)
            if (qty <= 0) { if (lostOpen) lostWaits("nothing sold or taken out") else { workedOut(); takeOut() }; return false }
            // Above the exchange freeze quantity the exit goes as several orders, each one the exchange accepts.
            fun slices(q: Int) = Kite.slices(q, lotSize, Kite.freezeQuantity(p.exchange, p.symbol))
                .map { Kite.Order(p.symbol, side, it, lotSize, p.product, "MARKET", null, tickSize, p.exchange, tag, multiplier = multiplier) }
            var orders = slices(qty)
            val bad = orders.flatMap { Kite.refusals(it, limits, Broker.sentToday(), false, exit = true) }.distinct()
            if (bad.isNotEmpty()) {
                tell("refused ${p.symbol}", "${p.symbol} $aboutIt and its exit was not sent (${bad.joinToString("; ")}). Close it yourself in Trade.")
                return false
            }
            // 22 Nov 2026 (B6 review): a stop, target or GTT resting on it would have to come out before this sale: not while a lost exit
            // sale on it is unresolved - nothing sent this pass (a resting stop and a sale could both fill).
            if (lostOpen && (others.isNotEmpty() || gttMarks.isNotEmpty())) { lostWaits("its resting orders left in place, nothing sold"); return false }
            // 22 Nov 2026 (B6 review: this square-off and an arm's own exit each looked at the other once, before the slow part - with the
            // exit coordinator in shadow both could still sell): the contract's gap starts NOW, and the arms' unsettled sales are looked at
            // once more, BEFORE anything is taken out - so nothing comes out for a sale that will not go. (An arm's exit counts its own
            // sale as on its way before it looks at the gap, and neither sells while the other holds the row's claim: [claimExit].) A sale
            // that came up meanwhile (more unsettled, or any new one started - one ending as another starts leaves the total the same):
            // nothing taken out or sent this pass, and the gap ended again.
            notePlaced(p.exchange, p.symbol, p.product)
            val lostNow = lostPending(p.symbol, p.product)
            if (side == Kite.Side.SELL && (lostNow == null || lostNow > (lost ?: 0) || (salesStarted[row] ?: 0L) != started)) {
                clearPlaced(p.exchange, p.symbol, p.product)
                lostWaits("one came up as this sale was about to go: nothing taken out or sold")
                return false
            }
            if (!takeOut()) { clearPlaced(p.exchange, p.symbol, p.product); skipped("its resting orders could not be confirmed out"); return false }
            // (22 Nov 2026, B6 review, second round: a backstop - a book selling only under the row's claim cannot start a sale meanwhile -
            // for anything of the arms' that came up while the stops were coming out: what goes is cut to what is held less every unsettled
            // sale now; one of unknown size, or nothing left: nothing sent this pass, said - the next pass reads again.)
            if (side == Kite.Side.SELL) {
                val lostAfter = lostPending(p.symbol, p.product)
                if (lostAfter == null || lostAfter > (lostNow ?: 0)) {
                    val cut = if (lostAfter == null) 0 else com.optionslab.engine.risk.ExitQty.sendable(heldNow, pending + lostAfter, abs(heldNow), lotSize)
                    if (cut <= 0) {
                        clearPlaced(p.exchange, p.symbol, p.product)
                        runCatching { Diag.record("exits", "$who ${p.exchange}:${p.symbol}: an arm's exit sale on it came up (${lostAfter ?: "size unknown"}) " +
                            "while its resting orders came out: nothing sold") }
                        tell("unsettled out $row ${salesStarted[row] ?: 0L}", "${p.symbol} $aboutIt. Its stop at Zerodha is OUT: the $exitName " +
                            "took its resting orders out to sell it, and an arm's own exit sale on it (${lostAfter ?: "size unknown"}) came up meanwhile and is still pending, so " +
                            "nothing was sold beside that sale. The $exitName looks again on its next pass; check ${p.symbol} in Kite now.")
                        return false
                    }
                    orders = slices(cut)
                }
            }
            // (A reply lost on the way: the sale may be out, so the gap stays; nothing went at all: it ends below.)
            var unsure = false
            val placed = ArrayList<String>()
            for (o in orders) {
                try {
                    // 19 Nov 2026 (third review): the contract's gap starts with each order placed, not after the whole loop.
                    placed += Broker.placeOrder(o, exit = true).also { notePlaced(p.exchange, p.symbol, p.product); noteSold(p.exchange, p.symbol, p.product)
                        runCatching { Strategies.tagOwnerSoon("kite:$it", origin) } }
                } catch (e: Exception) {
                    if (Broker.definite(e)) {
                        // 10 Nov (the 3 Nov refusal, for an exit): MARKET blocked on a low-open-interest contract - the same slice once as a
                        // LIMIT at the bid (never more than 1% under the last; [com.optionslab.engine.orb.LiveMirror.blockedMarketExit]). Not
                        // filled in 10 s: cancelled, so it never sits as "cover" while the price moves away, and said as a refusal (the next
                        // pass prices it again). A lost reply is counted by the next pass's read of the working orders, as every exit here.
                        if (com.optionslab.engine.orb.LiveMirror.marketBlocked(e.message)) {
                            val key = "${p.exchange}:${p.symbol}"
                            val q = runCatching { Broker.quotes(listOf(key), lane = com.optionslab.ira.RateGate.Priority.SAFETY)[key] }.getOrNull()   // B5-D: an exit's read
                            val limit = com.optionslab.engine.orb.LiveMirror.blockedMarketExit(o, q?.last, q?.ask, q?.bid)
                            // 19 Nov 2026 (third review): a lost reply is not a refusal - the LIMIT may be out, so the gap starts and it
                            // is looked for, as the MARKET path does; never said as refused.
                            var limitLost = false
                            val sent = limit?.let { l ->
                                try {
                                    Broker.placeOrder(l, exit = true).also { notePlaced(p.exchange, p.symbol, p.product); noteSold(p.exchange, p.symbol, p.product) }
                                } catch (e2: kotlinx.coroutines.CancellationException) {
                                    throw e2
                                } catch (e2: Exception) {
                                    if (Broker.definite(e2)) null else {
                                        limitLost = true; unsure = true
                                        notePlaced(p.exchange, p.symbol, p.product); noteSold(p.exchange, p.symbol, p.product)
                                        runCatching { Broker.findRecentRetrying(l, placed + workingNow.map { it.id }) }.getOrNull()
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
                        // (20 Nov 2026, audit B5: one that never left the phone is no refusal - Zerodha never saw it.)
                        tell("rejected ${p.symbol} ${e.message}", (if (e is Broker.NotSent) Broker.notSentWords("$exitName exit of ${p.symbol}") + " "
                            else "Zerodha refused the $exitName exit of ${p.symbol}: ${e.message}. ") + "Retrying every pass until the close; close it yourself if it keeps failing.")
                        break
                    }
                    // The reply was lost, not necessarily the order: look for it before trying again. 19 Nov 2026: it may be out
                    // either way, so the contract's gap starts ([GAP_MS]).
                    notePlaced(p.exchange, p.symbol, p.product); noteSold(p.exchange, p.symbol, p.product); unsure = true
                    runCatching { Broker.findRecentRetrying(o, placed + workingNow.map { it.id }) }.getOrNull()?.let { placed += it }
                    break
                }
            }
            // 20 Nov 2026 (review): the sale sent, the stops it took out are put back by the protections only once the order book shows
            // it COMPLETE and the positions catch up - as for Boss's own exit from the app ([Protections.exitFilledFor]) - never on a
            // positions read that still shows what the sale sold. A sale refused or not filled: put back after that wait.
            if (placed.isNotEmpty() && (others.isNotEmpty() || gttMarks.isNotEmpty()))
                runCatching { Protections.exitFilledFor(others.map { it.id } + gttMarks, placed.associateWith { 1 }) }
            // (22 Nov 2026: nothing went out - refused, or a LIMIT confirmed cancelled unfilled: the gap started before it ends again.)
            if (placed.isEmpty() && !unsure) {
                clearPlaced(p.exchange, p.symbol, p.product)
                // (Review: nothing of it went - a LIMIT confirmed cancelled unfilled: the row's sale stamp as it was before this pass.)
                soldBefore?.let { soldAt[rowKey] = it } ?: soldAt.remove(rowKey)
            }
            if (placed.isNotEmpty()) workedOut()
            return placed.isNotEmpty()
        } finally { claim?.release(); pass.leave() }
    }
}
