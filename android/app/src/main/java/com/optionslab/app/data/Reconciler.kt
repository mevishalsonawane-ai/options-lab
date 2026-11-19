package com.optionslab.app.data

import android.content.Context
import com.optionslab.ira.Reconcile
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 19 Nov 2026 (audit batch 2: an independent safety net, [Reconcile]). Nothing compared Zerodha's real positions with every book:
 * the Zerodha sync ([ZerodhaSync]) is report-only inside Jarvis's lowest-priority words round, and "Stop and target on my manual
 * positions" ([ManualCover]) leaves the app's own orders alone. Now, about every minute while the watch runs and Zerodha is logged
 * in, in NSE's or MCX's hours, in any mode (the watch's reconcile step beside its stop lane, never on the order or tick path):
 *  - Zerodha's net positions (one read; the kept read when it may stand for one) against what every book owns - the arms' live
 *    positions (ORB family and Liquidity, with their live quantities), the arms' live copies ([ArmLive]), Pine's live holdings, the
 *    live protections (with Boss's covered manual positions), the Strategy Module's live legs, the Expiry Put ticket's legs and the
 *    manual positions about to be covered;
 *  - for any position no book owns, whether an exit stop rests at Zerodha (its order book and its GTTs, read only then);
 *  - such a position seen in two reads in a row and lasting a minute is a RISK notification, repeated every 5 minutes while it lasts;
 *  - "Zerodha holds something" feeds the watch's "is anything held?" ([HeldNow]), so the watch and the dead-man never sleep while
 *    Zerodha holds anything.
 * A read that fails, or a book that cannot be read in time, changes nothing (never "flat", never an alert).
 *
 * It only reports: it NEVER places, changes or cancels an order and never touches a book.
 */
object Reconciler {
    @Volatile private var tracker = Reconcile.Tracker()
    @Volatile private var lastAt = 0L
    @Volatile private var told = false
    @Volatile private var line: String = "Reconciler: not run yet"

    /** The RISK notification's id. */
    internal const val NOTE_ID = 2063
    private const val WAIT_MS = 8_000L
    private const val BOOK_WAIT_MS = 3_000L

    /** Zerodha held a position at the last good read, at most [Reconcile.HINT_MS] ago (memory only). */
    fun heldHint(now: Long = System.currentTimeMillis()): Boolean = runCatching { tracker.heldHint(now) }.getOrDefault(false)

    /** The diagnostics' line. */
    fun line(): String = line

    /** NSE's session now (the calendar and the clock). */
    private fun nseHours(): Boolean = runCatching {
        Market.isTradingDay() && Market.minuteNow() in Market.OPEN..Market.foClose()
    }.getOrDefault(false)

    /** Due now: logged in, NSE's or MCX's hours, a minute since the last one. Memory and the calendars only. */
    fun due(now: Long = System.currentTimeMillis()): Boolean = !com.optionslab.app.BuildConfig.GOLD &&
        Reconcile.due(Broker.loggedIn, nseHours(), runCatching { McxMarket.isOpen() }.getOrDefault(false), lastAt, now)

    /**
     * One reconcile when [due] (a worker thread; the watch's reconcile step). The start-up's books not read yet: nothing this time
     * (each book's own memory would be empty, never "nobody owns it").
     */
    suspend fun step(context: Context, now: Long = System.currentTimeMillis()) {
        if (!due(now)) return
        if (PositionsFirst.startedAt != 0L && !PositionsFirst.order.isDone(com.optionslab.ira.StartOrder.Step.POSITIONS)) return
        lastAt = now
        val read = runCatching { read() }.getOrNull()
        if (read == null) {
            tracker.failed()
            line = "Reconciler (${hhmm()}): Zerodha or a book could not be read; nothing judged"
            return
        }
        val (found, held) = read
        val alerts = tracker.step(now, found, held)
        line = "Reconciler (${hhmm()}): " + if (found.isEmpty()) "every Zerodha position is followed by a book" + (if (held) "" else " (none held)")
            else found.joinToString("; ") { Reconcile.say(it) }
        if (alerts.isNotEmpty()) {
            told = true
            val text = Reconcile.notice(alerts)
            runCatching { Diag.record("sync", "RISK: $text") }
            // On the always-shown channel (as the dead-man's "Order watch stopped"): the risk channel reaches the shade only with
            // "other alerts" on, and this must never be missed. Red in the app.
            runCatching { com.optionslab.app.work.Notifier.post(context, NOTE_ID, com.optionslab.app.work.Notifier.APPROVAL,
                Reconcile.title(alerts), text, "broker", banner = com.optionslab.app.work.Alerts.Kind.ERROR) }
        } else if (told && found.isEmpty()) {
            told = false
            runCatching { androidx.core.app.NotificationManagerCompat.from(context).cancel(NOTE_ID) }
            runCatching { Diag.record("sync", "Reconciler: every Zerodha position is followed by a book again") }
        }
    }

    private fun hhmm(): String = runCatching { Market.now().toLocalTime().withSecond(0).withNano(0).toString() }.getOrDefault("--:--")

    /**
     * Zerodha's positions against the books: the findings and whether Zerodha held anything; null when Zerodha or a book could not
     * be read in time. Reads only.
     */
    internal suspend fun read(): Pair<List<Reconcile.Finding>, Boolean>? {
        val book = Broker.within(WAIT_MS) { Broker.positionsRecon() } ?: return null
        val positions = book.net.filter { it.exchange in com.optionslab.engine.Kite.DERIVATIVE_EXCHANGES && it.qty != 0 }
            .groupBy { it.symbol }.mapValues { (_, v) -> v.sumOf { it.qty } }.filterValues { it != 0 }
        if (positions.isEmpty()) return emptyList<Reconcile.Finding>() to false
        val claims = claims(positions.keys) ?: return null
        val owned = claims.map { it.symbol }.toSet()
        val ownedQty = Reconcile.ownedQty(claims)
        if (!Reconcile.unownedOrOversized(positions, owned, ownedQty)) return emptyList<Reconcile.Finding>() to true
        // Only now the order book and the GTTs (whether an exit stop rests); either unread is "unknown".
        val orders = Broker.within(WAIT_MS) { Broker.ordersRecon() }?.map { Reconcile.Order(it.symbol, it.side, it.type, it.working) }
        val gtts = Broker.within(WAIT_MS) { Broker.gtts() }?.map { Reconcile.Gtt(it.symbol, it.active, it.legSides) }
        return Reconcile.find(positions, owned, orders, gtts, ownedQty, known(positions.keys - owned)) to true
    }

    /**
     * 19 Nov 2026 (second review): of the positions no book owns, those that are Boss's own as the app knows them - seen or left alone
     * on purpose by "Stop and target on my manual positions", or any at all while the app is in Paper mode (no automatic live trade
     * can be open then). Told once while they last, not every 5 minutes.
     */
    // Review: Paper mode counts only while no live book holds anything (an arm's live trade, a live protection, Pine's live holding, a
    // live MCX position, or one being recovered): live positions outlive a switch to Paper, and an orphan of theirs must repeat.
    private fun known(unowned: Set<String>): Set<String> {
        if (unowned.isEmpty()) return emptySet()
        val paper = runCatching { !AppSettings.load().live }.getOrDefault(false)
        if (paper && !liveBookHolds()) return unowned
        return unowned intersect runCatching { ManualCover.knownSymbols() }.getOrDefault(emptySet())
    }

    /** Any live book holds (or is recovering) something (memory hints; a failure counts as yes). */
    private fun liveBookHolds(): Boolean = runCatching {
        ArmLive.holdingHint || ArmLive.recoverHint || OrbArms.liveHint || Protections.liveKeysHint.isNotEmpty() ||
            PineAuto.held.value.values.any { it.live } || McxMarket.liveExposureOrThrow()
    }.getOrDefault(true)

    /**
     * Every book's claim on a Zerodha symbol now (memory and each book's own bounded read), with its quantity where the book gives one
     * ([Reconcile.Claim]); null when one could not be read in time, or the arms' live copies' book is not loaded yet. [held]: the
     * symbols Zerodha holds (for the ticket's legs).
     */
    internal suspend fun claims(held: Set<String>): List<Reconcile.Claim>? {
        val out = ArrayList<Reconcile.Claim>()
        // The arms (ORB family, Liquidity), Pine's live holdings, the arms' live copies and the live protections, with their quantities
        // (a fill not confirmed yet: no quantity).
        if (runCatching { ArmLive.view.value.loaded }.getOrDefault(false) != true) return null
        val beliefs = ZerodhaSync.beliefs() ?: return null
        beliefs.forEach { out += Reconcile.Claim(it.symbol, it.qty.takeIf { _ -> !it.unconfirmed }, it.holds) }
        val seen = beliefs.map { it.symbol }.toSet()
        // The ORB family's live quantities (the same positions as its beliefs: added only where the beliefs had none).
        runCatching { OrbArms.liveQtyHint }.getOrNull()?.forEach { (s, q) -> if (s !in seen) out += Reconcile.Claim(s, q) }
        // Pine's holdings not among the beliefs, the Strategy Module's live legs, Boss's manual positions about to be covered and the
        // ticket's legs: owned, with no quantity judged.
        runCatching { PineAuto.held.value.values.mapNotNull { it.kite } }.getOrNull()?.forEach { if (it !in seen) out += Reconcile.Claim(it, null) }
        val legs = withTimeoutOrNull(BOOK_WAIT_MS) { runCatching { Strategies.liveLegSymbols() }.getOrNull() } ?: return null
        legs.forEach { out += Reconcile.Claim(it, null) }
        runCatching { ManualCover.pendingSymbols() }.getOrNull()?.forEach { out += Reconcile.Claim(it, null) }
        ticketLegs(held - out.map { it.symbol }.toSet()).forEach { out += Reconcile.Claim(it, null) }
        return out
    }

    /**
     * The Expiry Put ticket's legs among [symbols] (its short put and its wing), recognised as "Stop and target on my manual
     * positions" does; one whose strike is not in the lists in memory counts as a leg when it may be one (never a false alarm).
     */
    private fun ticketLegs(symbols: Set<String>): Set<String> {
        if (symbols.isEmpty()) return emptySet()
        val t = runCatching { Ledger.openTicket()?.row?.ticket }.getOrNull() ?: return emptySet()
        val ins = runCatching { (Broker.cachedInstruments().orEmpty() + Broker.cachedBfoOptions().orEmpty()).associateBy { it.tradingSymbol } }
            .getOrDefault(emptyMap())
        return symbols.filter { s ->
            val i = ins[s]
            runCatching {
                com.optionslab.ira.ManualCoverRule.isTicketLeg(i?.name ?: com.optionslab.ira.ManualCoverRule.underlying(s), i?.strike,
                    i?.right?.name ?: s.takeLast(2), t.underlying, t.strike, t.wingStrike)
            }.getOrDefault(true)
        }.toSet()
    }

    /**
     * 19 Nov 2026 (audit batch 2, the quiet start): the plain mark of "anything held" ([com.optionslab.ira.QuietStart]) written when it
     * changes - any book's hint or Zerodha's own positions. A worker thread only ([com.optionslab.app.work.NightMarks]: a plain file).
     */
    fun markHeld(context: Context, held: Boolean) {
        val v = if (held) com.optionslab.ira.QuietStart.HELD else com.optionslab.ira.QuietStart.FLAT
        if (markKept == v) return
        runCatching { com.optionslab.app.work.NightMarks.put(context, HELD_MARK, v) }
        // Review: kept only once it reads back (a write skipped on the main thread, or failed, is tried again next time).
        if (runCatching { com.optionslab.app.work.NightMarks.get(context, HELD_MARK) }.getOrNull() == v) markKept = v
    }

    @Volatile private var markKept: String? = null

    /**
     * 19 Nov 2026 (second review): what the plain mark says - [heldAny] (every book's hint), or a live MCX position Zerodha showed and
     * no read has seen closed since, whether MCX is open or not (the books' "MCX live" hint counts only while MCX trades, so a position
     * carried overnight read as nothing held after the close). A failure counts as held.
     */
    fun markFor(heldAny: Boolean, mcxLive: () -> Boolean = { McxMarket.liveExposureOrThrow() }): Boolean =
        heldAny || runCatching(mcxLive).getOrDefault(true)

    /** The plain mark's key ([com.optionslab.app.work.NightMarks]). */
    internal const val HELD_MARK = "held.mark"

    /** TEST ONLY: as after a good read at [now] in which Zerodha held something. */
    internal fun heldForTest(now: Long = System.currentTimeMillis()) { tracker.step(now, emptyList(), true) }

    /** TEST ONLY: as a fresh process. */
    internal fun resetForTest() {
        lastAt = 0L; told = false; line = "Reconciler: not run yet"; markKept = null; tracker = Reconcile.Tracker()
    }
}
