package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.work.Alerts
import com.optionslab.ira.ManualCoverRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 7 Nov (Boss: "if any manual order is placed, then stoploss and target (profit lock) order should be placed by the application",
 * "From zerodha/application"): Boss's switch "Stop and target on my manual positions", ON by default.
 *
 * Once per full watch pass (NSE, 09:15 to 15:24 on a trading day, Live with real orders allowed, logged in), every live option
 * Boss bought by hand - in the Kite app or on this app's order screen - that nothing in the app follows gets a protection
 * ([Protections.protectLive]): a stop 5% under the position's average price and a target LIMIT sell 10% over it (Boss's choice,
 * 7 Nov; no trail). On 6 Nov NIFTY26N0925150CE (130 qty) was bought by hand and stayed unprotected overnight, and the Zerodha sync
 * kept saying of such positions "no bot, Pine script or protection of the app follows it".
 *
 * The rules are [ManualCoverRule]'s. It leaves a contract alone when an arm, Pine, an arm's live trade or a protection follows it
 * ([ZerodhaSync.beliefs]) or it is a leg of a live Strategy Module run ([Strategies.liveLegSymbols]); it expires today and the
 * expiry square-off is about to close it (from 15:00); Boss is exiting it by hand ([ArmLive.handsOffNow]); any order on it is
 * working at Zerodha (an entry still filling, or Boss's own stop or target); a GTT on it is active; today's fill came from the
 * app's own order for an arm or a strategy; or a protection on it set today was removed or cancelled by Boss (respected for the
 * day; for a carried NRML position, one removed in the last week). It acts only after two looks at least 20 s apart with the same
 * quantity, and on a fresh positions read.
 *
 * 7 Nov, review: it also leaves alone a long option on an underlying the account is SHORT an option on (a spread's wing: covering it
 * alone could leave the short naked) and the open Expiry Put ticket's legs; a contract held in two products (protections are kept
 * per symbol); and a last price too close over the 5% stop (a fresh quote, just before placing). The orders and GTTs are read
 * again in the contract's exit turn ([ExitCoordinator.turn]) right before placing, and it never covers more than is held less
 * what is already on its way out ([com.optionslab.engine.risk.ExitQty.sendable]). Only definite refusals count toward the three
 * tries; every attempt counts toward ten a day, a minute apart at least.
 *
 * 15 Nov (Boss's diagnostics: covered only 60-125 s after it appeared): a look that sees a new manual position asks for one more
 * look about 21 s later ([followUp]; the two looks 20 s apart are kept), and a look whose read failed asks for one 10 s later. A
 * stop the exchange refused or cancelled giving its reason no longer blocks covering the position again that day; one cancelled
 * with no reason given (Boss in Kite) still does, and Boss is told loudly that the position has no stop ([Protections]).
 *
 * 20 Nov 2026 (audit: "it runs only in Live and in NSE hours" - kept, on purpose, and why): in Paper Boss has chosen that the app sends
 * no real order by itself, so nothing new is placed at Zerodha then (CLAUDE.md: real orders only in Live); it covers NSE and BSE
 * options only (MCX, futures and stock are left alone, [ManualCoverRule.covered]), which trade only in NSE hours, when a DAY stop can
 * rest (09:15 to 15:24, before the 15:25 flat-by). What it has already placed is NOT limited to those hours or to Live: the
 * protections' own watch ([Protections.tick]) runs in every pass in Paper and Live - one exit filled cancels the other, a carried
 * position's stop rests overnight as a GTT and is placed again at the next open.
 *
 * It only ever ADDS exits (lowers risk, allowed for live by the safety rules). It never places an entry, never resizes and never
 * cancels or changes Boss's orders. It runs in the watch's pass like [Protections.tick], never on the order or tick path; its
 * switch is read once in the background at start and is memory-only for the pass. Never spoken: the app's banners and the
 * diagnostics only (giving up is also a notification).
 */
object ManualCover {
    private const val K_ON = "g.manualCover.on"
    /** The notifications: a manual position left NOT protected, an untracked app exit, an automatic protection taken off. */
    private const val NOTICE_GAVE_UP = 7301
    private const val NOTICE_UNTRACKED = 7302
    private const val NOTICE_UNHEDGED = 7303
    /** Its name in the contract's exit turn ([ExitCoordinator]). */
    private const val WHO = "manual stop and target"

    @Volatile private var on = true
    @Volatile private var loaded = false
    /** Boss changed the switch in this process (the start's late read never overrides it). */
    @Volatile private var switched = false
    @Volatile private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + ScopeGuard.handler("ManualCover"))

    /** One pass at a time (the watch runs one; a test's pass could overlap). */
    private val passLock = Mutex()
    /**
     * Memory only, under [mem]. Keyed "day|EXCHANGE:SYMBOL:PRODUCT" ([ManualCoverRule.Pos.lookKey]): the two looks, today's
     * definite refusals ([tries]), all attempts and the last one's time, the contracts given up on and those told "too low".
     */
    private val mem = Any()
    private val looks = ManualCoverRule.Looks()
    private val tries = HashMap<String, Int>()
    private val attempts = HashMap<String, Int>()
    private val lastAttempt = HashMap<String, Long>()
    private val gaveUp = HashSet<String>()
    private val toldLow = HashSet<String>()
    /** Contracts counted as "left alone" today (once each, "day|EXCHANGE:SYMBOL"). */
    private val leftAlone = HashSet<String>()
    /** 7 Nov, second review: untracked app exits said ("day|order id") and automatic protections taken off ("day|EXCHANGE:SYMBOL"). */
    private val untracked = HashSet<String>()
    private val unhedged = HashSet<String>()
    /** 7 Nov, third review: the two looks before an automatic protection is taken off, and the ones taken off (by id). */
    private val hedgeLooks = ManualCoverRule.Looks()
    private val unhedgedIds = HashSet<Long>()
    @Volatile private var tally = ManualCoverRule.Tally()

    /** [loaded]: the switch was read (until then the card shows it is reading, never "On"). */
    data class View(val on: Boolean = true, val line: String = "", val loaded: Boolean = false)
    private val _view = MutableStateFlow(View())
    /** Bot settings' card. */
    val view: StateFlow<View> = _view

    fun init(context: Context) {
        app = context.applicationContext
        scope.launch { load() }
    }

    /** The switch from the settings vault (a worker thread). Until read it does nothing (a restart never acts on a stale "on"). */
    internal fun load() {
        runCatching {
            val saved = SecurePrefs.getBoolean(K_ON, true)
            if (!switched) on = saved
            loaded = true
        }
        publish()
    }

    private fun publish() { runCatching { _view.value = View(on, line(), loaded) } }

    fun isOn(): Boolean = on

    /** Read once if the start's read has not landed (a worker thread only: Bot settings' card). */
    fun refresh() { if (!loaded) load() else publish() }

    /**
     * 15 Nov: the symbols of manual positions seen and waiting for their stop and target (within their two looks), for the Zerodha
     * sync's words ("stop and target pending" rather than "nothing follows it"). Never one it gave up on or found already too low.
     * Empty while switched off. Memory only.
     */
    fun pendingSymbols(): Set<String> {
        if (!(loaded && on)) return emptySet()
        // 15 Nov, review: never stale - only in its hours, only sightings of the last [ManualCoverRule.PENDING_MS], never one left
        // alone today (a spread, Boss's working order, a removal) nor one given up on or found already too low.
        val day = runCatching { Market.today() }.getOrNull() ?: return emptySet()
        if (!ManualCoverRule.inHours(Market.isTradingDay(day), Market.minuteNow())) return emptySet()
        val now = Market.now().toInstant().toEpochMilli()
        val d = day.toString()
        return synchronized(mem) {
            val done = (gaveUp + toldLow).map { it.substringAfter('|') }.toSet()
            val alone = leftAlone.filter { it.startsWith("$d|") }.map { it.substringAfter('|') }.toSet()
            looks.keysSince(now - ManualCoverRule.PENDING_MS).filter { it !in done && it.substringBeforeLast(':') !in alone }
                .mapNotNull { it.split(':').getOrNull(1) }.toSet()
        }
    }

    /**
     * 19 Nov 2026 (audit batch 2, second review): the symbols of Boss's manual positions this switch knows of today - seen and waiting,
     * left alone on purpose (a spread, a hedge, a short leg, his own working order, two products, a GTT...) or taken off as a hedge's
     * leg - for the reconciler, which then tells them once instead of every 5 minutes. Never one it failed to protect (given up on, or
     * found already under its stop): those stay unprotected and are told every 5 minutes. Memory only; empty until the switch is read.
     * Never used to place, change or skip anything.
     */
    fun knownSymbols(): Set<String> {
        if (!loaded) return emptySet()
        val d = runCatching { Market.today().toString() }.getOrNull() ?: return emptySet()
        // Each kept as "day|EXCHANGE:SYMBOL[:PRODUCT]"; the looks as "EXCHANGE:SYMBOL:PRODUCT".
        fun syms(s: Collection<String>) = s.filter { it.startsWith("$d|") }.mapNotNull { it.substringAfter('|').split(':').getOrNull(1) }.toSet()
        return synchronized(mem) {
            (syms(leftAlone + unhedged) + looks.keys.mapNotNull { it.split(':').getOrNull(1) }) - syms(gaveUp + toldLow)
        }
    }

    /** TEST ONLY: [key] ("EXCHANGE:SYMBOL") left alone today, as a look that found a spread's leg would. */
    internal fun leftAloneForTest(key: String) { synchronized(mem) { leftAlone.add("${Market.today()}|$key"); loaded = true } }

    /** The diagnostics / health line: the switch and today's counts. Memory only. */
    fun line(): String = ManualCoverRule.line(on, loaded, tally, runCatching { Market.today() }.getOrElse { java.time.LocalDate.now(com.optionslab.engine.IST) })

    /**
     * Boss's switch (Bot settings). Off never takes the PIN (it only stops the app adding exits); on again takes it ([pinConfirmed]:
     * the card asks first), as the app then places real orders at Zerodha by itself.
     */
    fun setOn(on: Boolean, pinConfirmed: Boolean = false): String {
        if (on && !pinConfirmed) return "Enter your PIN to have the app place a stop and target on positions you buy yourself."
        this.on = on
        loaded = true
        switched = true
        runCatching { SecurePrefs.putAllSoon(mapOf(K_ON to on)) }
        runCatching { Diag.record("protection", "Stop and target on my manual positions: ${if (on) "on" else "off"} (by you)") }
        publish()
        return if (on) "On: a live option you buy yourself, in Kite or here, that nothing in the app follows gets a stop 5% under its " +
            "average price and a target sell 10% over it (in market hours)."
        else "Off: positions you buy yourself are left as they are; the app places no stop or target on them."
    }

    /**
     * One look (from the watch's full NSE pass through [kick], in its own job). Returns at once
     * when the switch is off, in Paper mode, logged out, or outside 09:15-15:24 on a trading day.
     */
    suspend fun run(s: AppSettings) = runLook(s, mayFollow = true)

    private suspend fun runLook(s: AppSettings, mayFollow: Boolean) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        // 7 Nov, review: the start's read not landed yet (a slow vault): read here, on the watch's worker thread (never the main one).
        if (!loaded) load()
        if (!s.live || !s.allowRealOrders) return
        if (!Broker.loggedIn) return
        val day = Market.today()
        if (!ManualCoverRule.inHours(Market.isTradingDay(day), Market.minuteNow())) return
        // 7 Nov, third review: switched off (or not read), it still takes off its OWN protections that became part of a spread or a
        // hedge (only ever removing: it lowers risk); it places nothing.
        val placing = loaded && on
        val next = passLock.withLock { look(s, day, placing) }
        if (mayFollow && placing) followUp(next)
    }

    /**
     * 15 Nov (Boss's diagnostics: a new manual position was covered only 60-125 s after it appeared, a full watch pass or two): the
     * look a [kick]ed look asks for ([ManualCoverRule.followUpMs]) - about 21 s after a first sighting (the two looks 20 s apart the
     * rule needs are kept; the second just comes sooner), or 10 s after a read that failed. At most one waits at a time, a follow-up
     * look never asks for another (bounded: one extra look per pass), and it runs in this object's own job like [kick]'s (never on
     * the watch's pass, the order path or the tick path), with the settings read again then.
     */
    private fun followUp(next: ManualCoverRule.FollowUp) {
        if (!followUpsOn) return
        val delayMs = followMsForTest ?: ManualCoverRule.followUpMs(next) ?: return
        if (ManualCoverRule.followUpMs(next) == null) return
        val gate = followGate
        synchronized(mem) {
            if (followJob?.isActive == true) return
            val gen = memGen
            followJob = scope.launch {
                kotlinx.coroutines.delay(delayMs)
                gate?.await()
                // Review: from here the look runs to its end (an erase meanwhile never cuts it between a stop sent and its record).
                if (synchronized(mem) { (gen != memGen).also { if (!it) followLooking = true } }) return@launch
                try { runCatching { runLook(AppSettings.load(), mayFollow = false) } } finally { followLooking = false }
            }
        }
    }

    /** The follow-up look waiting ([followUp]); [memGen] counts erases and test resets (a follow-up from before one does nothing). */
    private var followJob: kotlinx.coroutines.Job? = null
    private var memGen = 0
    /** True while a follow-up's look runs (past its wait): [clearMemory] lets it finish. */
    @Volatile private var followLooking = false
    /** TEST ONLY: a follow-up waits for this after its delay ([followUpsForTest] with a gate). Null in the app. */
    @Volatile private var followGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    /** TEST ONLY: follow-ups off unless a test asks for them ([followUpsForTest]); always on in the app. */
    @Volatile private var followUpsOn = true
    @Volatile private var followMsForTest: Long? = null

    /** TEST ONLY: follow-up looks on, each [ms] after the look that asked for it (the app's own waits are 21 s and 10 s). */
    internal fun followUpsForTest(ms: Long, gated: Boolean = false) {
        followMsForTest = ms; followUpsOn = true
        followGate = if (gated) kotlinx.coroutines.CompletableDeferred() else null
    }

    /** TEST ONLY: lets a gated follow-up look go ([followUpsForTest]). */
    internal fun releaseFollowUpForTest() { followGate?.complete(Unit) }

    /** TEST ONLY: whether a follow-up look is waiting or running. */
    internal fun followUpPendingForTest(): Boolean = synchronized(mem) { followJob?.isActive == true }

    /** The last [kick]'s job (a test waits for it before the next test: [finishLooksForTest]). */
    @Volatile private var kickJob: kotlinx.coroutines.Job? = null

    /** A [kick]'s look still running. */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 7 Nov, second review: [run] in its own job, which the watch's pass never waits on; false when one is still running (the next
     * pass kicks again). Never throws.
     */
    fun kick(s: AppSettings): Boolean {
        // Switched off, it looks only while one of its own protections is active (to take it off if it became a spread's leg).
        if (com.optionslab.app.BuildConfig.GOLD || (loaded && !on && !Protections.autoHint) || !running.compareAndSet(false, true)) return false
        return runCatching {
            kickJob = scope.launch { try { runCatching { run(s) } } finally { running.set(false) } }
            true
        }.getOrElse { running.set(false); false }
    }

    /** TEST ONLY: whether a [kick]'s look is still running. */
    internal fun kickRunningForTest(): Boolean = running.get()

    private suspend fun look(s: AppSettings, day: java.time.LocalDate, placing: Boolean): ManualCoverRule.FollowUp {
        val d = day.toString()
        synchronized(mem) {
            // A new day: yesterday's tries, attempts, refusals and words are over.
            tries.keys.removeAll { !it.startsWith("$d|") }; attempts.keys.removeAll { !it.startsWith("$d|") }
            lastAttempt.keys.removeAll { !it.startsWith("$d|") }; gaveUp.removeAll { !it.startsWith("$d|") }
            toldLow.removeAll { !it.startsWith("$d|") }; leftAlone.removeAll { !it.startsWith("$d|") }
            untracked.removeAll { !it.startsWith("$d|") }; unhedged.removeAll { !it.startsWith("$d|") }
            tally = tally.on(day)
        }
        // The pass's one shared read finds the candidates; a fresh read is made before anything is placed (below).
        val book = Broker.within(8_000) { Broker.passPositionBook() } ?: return ManualCoverRule.FollowUp.READ_FAILED
        val all = posOf(book)
        val candidates = all.filter { ManualCoverRule.covered(it) }
        val today0 = day.atStartOfDay(com.optionslab.engine.IST).toInstant().toEpochMilli()
        // 7 Nov, second review: what ended in the last week, by when it ENDED ([Protections.Item.endedAt]), not when it was set.
        val from = day.minusDays(ManualCoverRule.CARRIED_DAYS - 1).atStartOfDay(com.optionslab.engine.IST).toInstant().toEpochMilli()
        val prot = withTimeoutOrNull(3_000) { runCatching { Protections.active() to Protections.endedSince(from) }.getOrNull() } ?: return ManualCoverRule.FollowUp.READ_FAILED
        // 7 Nov, second review: the protections this switch set (only those are ever taken off by it).
        val autos = prot.first.filter { it.live && it.auto && it.note != "removing" }
        if (candidates.isEmpty() && autos.isEmpty()) { synchronized(mem) { looks.clear() }; return ManualCoverRule.FollowUp.NONE }
        // 7 Nov, fourth review: switched off with none of its own protections, there is nothing to do: nothing more is read.
        if (!placing && autos.isEmpty()) { synchronized(mem) { looks.clear() }; return ManualCoverRule.FollowUp.NONE }
        // Zerodha's orders (working sells open shorts too), and the stock held for a stock option: not read in time, nothing.
        val orders = Broker.within(8_000) { Broker.orders() } ?: return ManualCoverRule.FollowUp.READ_FAILED
        // 7 Nov, fourth review: the spread and hedge decision (taking one off, leaving one alone, saying so) on Zerodha's positions
        // read now, after the orders, never the pass's shared read (it can be some seconds old).
        val now0 = Broker.within(8_000) { Broker.positionBook() } ?: return ManualCoverRule.FollowUp.READ_FAILED
        val allNow = posOf(now0)
        val equity = equityFor(candidates.map { it.symbol } + autos.map { it.symbol }) ?: return ManualCoverRule.FollowUp.READ_FAILED
        val names = namesFor(allNow.map { it.symbol } + all.map { it.symbol } + orders.map { it.symbol })
        val tracked = trackedIds(prot.first)
        val shorts = ManualCoverRule.shortUnderlyings(allNow, sellsOf(orders, tracked), heldTodayOf(now0), names)
        val hedges = ManualCoverRule.hedgeUnderlyings(allNow, equity, names)
        unhedge(d, autos, shorts, hedges, names)
        if (!placing || candidates.isEmpty()) { synchronized(mem) { looks.clear() }; return ManualCoverRule.FollowUp.NONE }
        // What the app follows (arms, Pine, arms' live trades, protections). A book not read in time: nothing this look.
        val beliefs = ZerodhaSync.beliefs() ?: return ManualCoverRule.FollowUp.READ_FAILED
        // The Strategy Module's live runs' legs too (not in the sync's beliefs): never a manual position.
        val legs = withTimeoutOrNull(3_000) { runCatching { Strategies.liveLegSymbols() }.getOrNull() } ?: return ManualCoverRule.FollowUp.READ_FAILED
        val first = ManualCoverRule.Facts(
            followed = beliefs.map { it.symbol }.toSet() + legs,
            protectedNow = prot.first.filter { it.live }.map { it.symbol }.toSet(),
            // 7 Nov, third review: one this switch took off as a spread's or hedge's leg never blocks (even if its cancel was
            // confirmed only later, under the protections' own "removed").
            endedToday = prot.second.filter { it.live && synchronized(mem) { it.id !in unhedgedIds } }
                .map { ManualCoverRule.Ended(it.symbol, it.note, today = (it.endedAt ?: it.id) >= today0, exchange = it.exchange) },
            handsOff = candidates.filter { ArmLive.handsOffNow(it.exchange, it.symbol) }.map { it.key }.toSet(),
            expiring = expiringNow(s, day, candidates),
            shortUnderlyings = shorts,
            hedgeUnderlyings = hedges,
            ticketLegs = ticketLegs(candidates),
            twoProducts = all.groupBy { it.key }.filterValues { rows -> rows.map { it.product }.distinct().size > 1 }.keys,
            names = names,
        )
        val given = synchronized(mem) { gaveUp.toSet() }
        val open = ArrayList<ManualCoverRule.Pos>()
        for (p in candidates) {
            when (val why = ManualCoverRule.skip(p, first)) {
                null -> if ("$d|${p.lookKey}" !in given) open += p
                else -> countLeftAlone(d, p, why)
            }
        }
        if (open.isEmpty()) { synchronized(mem) { looks.keepOnly(emptySet()) }; return ManualCoverRule.FollowUp.NONE }
        untrackedExits(d, open, orders, prot.first)
        val gtts = Broker.within(8_000) { Broker.gtts() } ?: return ManualCoverRule.FollowUp.READ_FAILED
        val owners = withTimeoutOrNull(3_000) { runCatching { Strategies.owners() }.getOrNull() } ?: return ManualCoverRule.FollowUp.READ_FAILED
        val facts = first.copy(
            workingOrders = workingKeys(orders),
            activeGtts = gtts.filter { it.active }.map { "${it.exchange}:${it.symbol}" }.toSet(),
            // A fill today from an order the app placed for an arm or a strategy (its label or its Zerodha tag): not Boss's.
            appOrders = orders.filter { it.side == "BUY" && it.filled > 0 && Origins.of(owners, "kite:${it.id}", it.tag).second }
                .map { "${it.exchange}:${it.symbol}" }.toSet(),
        )
        val uncovered = ArrayList<ManualCoverRule.Pos>()
        for (p in open) {
            when (val why = ManualCoverRule.skip(p, facts)) {
                null -> uncovered += p
                else -> countLeftAlone(d, p, why)
            }
        }
        val now = Market.now().toInstant().toEpochMilli()
        // 15 Nov: one seen uncovered but not yet due (its first sighting, or under 20 s since) is looked at again soon ([run]).
        var waiting = false
        val due = synchronized(mem) {
            looks.keepOnly(uncovered.map { it.lookKey }.toSet())
            val seen = uncovered.filter { looks.see(it.lookKey, it.qty, now) }
            waiting = seen.size < uncovered.size
            seen.filter { ManualCoverRule.mayAttempt(attempts["$d|${it.lookKey}"] ?: 0, lastAttempt["$d|${it.lookKey}"], now) }
        }
        val after = if (waiting) ManualCoverRule.FollowUp.SIGHTED else ManualCoverRule.FollowUp.NONE
        if (due.isEmpty()) return after
        // Placing: Zerodha's positions read now (the pass's shared read is for words only), the same quantity still held.
        val fresh = Broker.within(8_000) { Broker.positionBook() } ?: return ManualCoverRule.FollowUp.READ_FAILED
        var next = after
        for (p in due) {
            val rows = fresh.net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }
            val qty = rows.sumOf { it.qty }
            val row = rows.firstOrNull { it.qty != 0 }
            if (row == null || qty != p.qty) {
                // Changed since the look: it starts its two looks again.
                synchronized(mem) { looks.see(p.lookKey, qty, now) }
                if (next == ManualCoverRule.FollowUp.NONE) next = ManualCoverRule.FollowUp.SIGHTED
                continue
            }
            if (ArmLive.handsOffNow(p.exchange, p.symbol)) continue
            if (cover(d, p.copy(avg = row.avg, last = row.last), now, names)) next = ManualCoverRule.FollowUp.READ_FAILED
        }
        return next
    }

    /** Zerodha's net positions with a quantity, as the rules read them. */
    private fun posOf(book: Broker.Positions): List<ManualCoverRule.Pos> = book.net.filter { it.qty != 0 }.map {
        ManualCoverRule.Pos(it.symbol, it.exchange, it.product, it.qty, it.avg, it.last, carried = it.product == "NRML" && it.overnight > 0)
    }

    /**
     * Every working SELL order, what is left of it - never the app's own protections' exits (tagged "iraprotect", or one an active
     * protection tracks: 7 Nov, third review).
     */
    private fun sellsOf(orders: List<Broker.OrderRow>, tracked: Set<String>): List<ManualCoverRule.Sell> =
        orders.filter { it.working && it.side == "SELL" && it.tag != "iraprotect" && it.id !in tracked }
        .map { ManualCoverRule.Sell(it.symbol, it.exchange, com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending)) }

    /** The order ids the active protections track (their stops and targets). */
    private fun trackedIds(active: List<Protections.Item>): Set<String> = active.flatMap { listOfNotNull(it.stopOrderId, it.targetOrderId) }.toSet()

    /**
     * 7 Nov, third review: the contracts with a long today - held now, held overnight, or bought today (a row whose day's buys
     * Zerodha did not give counts as bought). A working sell on one of them is an exit left over, never a short.
     */
    private fun heldTodayOf(book: Broker.Positions): Set<String> =
        book.net.filter { it.qty > 0 || it.overnight > 0 || (it.dayBuyQty ?: 1) > 0 }.map { "${it.exchange}:${it.symbol}" }.toSet()

    /** 7 Nov, third review: the contract lists' names (memory only) for [symbols], where known ([ManualCoverRule.underlying]). */
    private fun namesFor(symbols: List<String>): Map<String, String> = runCatching {
        val ins = (Broker.cachedInstruments().orEmpty() + Broker.cachedBfoOptions().orEmpty()).associateBy { it.tradingSymbol }
        symbols.distinct().mapNotNull { s -> ins[s]?.name?.let { s to it } }.toMap()
    }.getOrDefault(emptyMap())

    /**
     * 7 Nov, second review: the stock held (holdings with a quantity), read only when one of [symbols] is a stock option (an index
     * has no stock); null when it had to be read and could not be.
     */
    private suspend fun equityFor(symbols: List<String>): Set<String>? {
        if (symbols.none { ManualCoverRule.underlying(it) !in ManualCoverRule.INDEX_UNDERLYINGS }) return emptySet()
        return Broker.within(8_000) { Broker.holdings() }?.filter { it.qty + it.t1 > 0 }?.map { it.symbol }?.toSet()
    }

    /**
     * 7 Nov, second review: a protection this switch set whose position is now part of a spread (Boss sold an option on the same
     * underlying, or a sell of one is working) or a hedge (its future or stock is held) is taken off - its stop and target
     * cancelled ([Protections.remove]) - so Boss's own hedge is whole again (lowers risk). Said loudly once. Never any other one.
     */
    private suspend fun unhedge(d: String, autos: List<Protections.Item>, shorts: Set<String>, hedges: Set<String>, names: Map<String, String>) {
        val flagged = autos.mapNotNull { a -> ManualCoverRule.spreadOrHedge(a.symbol, shorts, hedges, names)?.let { a to it } }
        // 7 Nov, third review: seen in two looks at least 20 s apart first (a read caught between two fills never takes one off).
        val now = Market.now().toInstant().toEpochMilli()
        val due = synchronized(mem) {
            hedgeLooks.keepOnly(flagged.map { "auto:${it.first.id}" }.toSet())
            flagged.filter { hedgeLooks.see("auto:${it.first.id}", 0, now) }
        }
        for ((p, why) in due) {
            synchronized(mem) { unhedgedIds.add(p.id) }
            val result = runCatching { Protections.remove(p.id, ManualCoverRule.UNHEDGED) }.getOrElse { "${p.symbol}: not removed yet (${it.message})." }
            runCatching { Diag.record("protection", "Manual position ${p.symbol}: automatic stop and target taken off (${why.words}): $result") }
            if (synchronized(mem) { unhedged.add("$d|${p.exchange}:${p.symbol}") })
                loud(NOTICE_UNHEDGED, ManualCoverRule.saidUnhedged(p.symbol, why) +
                    if (result.startsWith("Protection removed")) "" else " $result")
        }
    }

    /**
     * 7 Nov, second review: a working order tagged as a protection's ("iraprotect") on a manual position that no protection
     * tracks (its reply was lost) is not Boss's own: said loudly once, and the contract is left alone (it is a working order).
     */
    private fun untrackedExits(d: String, open: List<ManualCoverRule.Pos>, orders: List<Broker.OrderRow>, active: List<Protections.Item>) {
        val tracked = active.flatMap { listOfNotNull(it.stopOrderId, it.targetOrderId) }.toSet()
        val keys = open.map { it.key }.toSet()
        for (o in orders.filter { it.working && it.tag == "iraprotect" && it.id !in tracked && "${it.exchange}:${it.symbol}" in keys }) {
            if (!synchronized(mem) { untracked.add("$d|${o.id}") }) continue
            val said = ManualCoverRule.saidUntracked(o.symbol, o.id)
            runCatching { Diag.record("protection", said) }
            loud(NOTICE_UNTRACKED, said)
        }
    }

    /** "EXCHANGE:SYMBOL" of every order working at Zerodha. */
    private fun workingKeys(orders: List<Broker.OrderRow>): Set<String> = orders.filter { it.working }.map { "${it.exchange}:${it.symbol}" }.toSet()

    /**
     * The candidates that expire today once the expiry square-off is about to close them ([ManualCoverRule.leftToExpiry]): from
     * the contract lists in memory (never read here). One whose expiry is not known is not counted as expiring.
     */
    private fun expiringNow(s: AppSettings, day: java.time.LocalDate, candidates: List<ManualCoverRule.Pos>): Set<String> {
        if (!ManualCoverRule.leftToExpiry(s.expirySquareOff, Market.minuteNow(), ExpirySquareOff.AT_MINUTE)) return emptySet()
        val ins = runCatching { (Broker.cachedInstruments().orEmpty() + Broker.cachedBfoOptions().orEmpty()).associateBy { it.tradingSymbol } }
            .getOrDefault(emptyMap())
        return candidates.filter { ins[it.symbol]?.expiry == day }.map { it.key }.toSet()
    }

    /**
     * 7 Nov, review: the candidates that are legs of the open Expiry Put ticket (its short PE and its wing), recognised as the expiry
     * square-off does; a contract whose strike is not in the lists in memory is counted as one when it may be (the safe side).
     */
    private fun ticketLegs(candidates: List<ManualCoverRule.Pos>): Set<String> {
        val t = runCatching { Ledger.openTicket()?.row?.ticket }.getOrNull() ?: return emptySet()
        val ins = runCatching { (Broker.cachedInstruments().orEmpty() + Broker.cachedBfoOptions().orEmpty()).associateBy { it.tradingSymbol } }
            .getOrDefault(emptyMap())
        return candidates.filter { p ->
            val i = ins[p.symbol]
            ManualCoverRule.isTicketLeg(i?.name ?: ManualCoverRule.underlying(p.symbol), i?.strike, i?.right?.name ?: p.symbol.takeLast(2),
                t.underlying, t.strike, t.wingStrike)
        }.map { it.key }.toSet()
    }

    /**
     * [p] left alone for a reason Boss should know (counted once a day; the other reasons are not Boss's positions). A spread's leg
     * and a contract held in two products are also said once a day in the app (7 Nov, review).
     */
    private fun countLeftAlone(d: String, p: ManualCoverRule.Pos, why: ManualCoverRule.Skip) {
        val told = why == ManualCoverRule.Skip.SPREAD || why == ManualCoverRule.Skip.HEDGE || why == ManualCoverRule.Skip.TWO_PRODUCTS
        if (!told && why != ManualCoverRule.Skip.WORKING_ORDER && why != ManualCoverRule.Skip.GTT && why != ManualCoverRule.Skip.ENDED_TODAY &&
            why != ManualCoverRule.Skip.ENDED_EARLIER) return
        val first = synchronized(mem) { leftAlone.add("$d|${p.key}").also { if (it) tally = tally.copy(leftAlone = tally.leftAlone + 1) } }
        if (first) {
            val said = ManualCoverRule.saidLeftAlone(p.symbol, why)
            runCatching { Diag.record("protection", "Manual position ${p.symbol} (${p.qty}): no automatic stop and target, ${why.words}.") }
            if (told) Alerts.post(said, Alerts.Kind.INFO, "Protection")
            publish()
        }
    }

    /** True when a read it needed failed (the lot and tick, the price): nothing was tried, and the look is made again soon ([run]). */
    private suspend fun cover(d: String, p0: ManualCoverRule.Pos, now: Long, names: Map<String, String>): Boolean {
        val k = "$d|${p0.lookKey}"
        val key = "${p0.exchange}:${p0.symbol}"
        // 7 Nov, review: the lot and tick read with a deadline (protectLive then finds them in the day's memory); not read: next look.
        val spec = Broker.within(8_000) { Broker.spec(p0.exchange, p0.symbol) } ?: return true
        val tick = spec.tickSize.takeIf { it > 0 } ?: 0.05
        // 7 Nov, review (a 5% stop is tight): the price read fresh just before placing; not read: next look (no attempt counted).
        val last = Broker.within(5_000) { Broker.quotesFresh(listOf(key)) }?.get(key)?.last?.takeIf { it > 0 } ?: return true
        val p = p0.copy(last = last)
        val plan = ManualCoverRule.plan(p.avg, p.last, tick)
        val stop: Double; val target: Double?
        when (plan) {
            is ManualCoverRule.Plan.NoPrice -> return false
            is ManualCoverRule.Plan.TooLow -> {
                // Said once a day; looked at again on later passes (if the price comes back over the stop, it is protected then).
                if (synchronized(mem) { toldLow.add(k).also { if (it && leftAlone.add("$d|$key")) tally = tally.copy(leftAlone = tally.leftAlone + 1) } }) {
                    val said = ManualCoverRule.saidTooLow(p.symbol, p.last, plan.stop)
                    Alerts.post(said, Alerts.Kind.ERROR, "Protection")
                    runCatching { Diag.record("protection", "Manual position: $said") }
                    publish()
                }
                return false
            }
            is ManualCoverRule.Plan.StopOnly -> { stop = plan.stop; target = null }
            is ManualCoverRule.Plan.Both -> { stop = plan.stop; target = plan.target }
        }
        // 7 Nov, third review: checked again OUTSIDE the protections' lock (these reads may be slow): the underlying's shorts, working
        // sells, futures and stock, and what is held. Anything found: nothing sent, not an attempt, never a refusal.
        val outside = recheck(p, spec.lotSize, names)
        if (outside != null) {
            outside.first?.let { countLeftAlone(d, p, it) }
            runCatching { Diag.record("protection", "Manual position ${p.symbol} (${p.qty}): stop and target not placed: ${outside.second}") }
            return false
        }
        // Inside the lock only the short last check ([lastCheck]); the contract's turn, when taken, is held until the orders are sent.
        var left: ManualCoverRule.Skip? = null
        var pass: com.optionslab.ira.ExitTurns.Pass? = null
        synchronized(mem) { attempts[k] = (attempts[k] ?: 0) + 1; lastAttempt[k] = now }
        val result = try {
            runCatching {
                Protections.protectLive(p.symbol, p.exchange, p.product, p.qty, p.last, stop, null, target, auto = true, precheck = {
                    lastCheck(p) { pass = it }?.let { (why, words) -> left = why; words }
                })
            }.getOrElse { "Not protected: ${it.message}" }
        } finally { pass?.leave() }
        left?.let { countLeftAlone(d, p, it) }
        // Zerodha's answer to a day GTT lost: the protection is kept and looked for on the next pass, so it counts as placed.
        val placed = result.startsWith("Protected ") || runCatching { Protections.forSymbol(true, p.symbol) }.getOrNull() != null
        if (placed) {
            synchronized(mem) { tally = tally.copy(protectedN = tally.protectedN + 1); tries.remove(k) }
            val extra = if (plan is ManualCoverRule.Plan.StopOnly) " " + ManualCoverRule.saidStopOnly(p.symbol, p.last, plan.target) else ""
            val said = ManualCoverRule.saidProtected(p.symbol, p.qty, p.avg, result) + extra
            Alerts.post(said, Alerts.Kind.SUCCESS, "Protection")
            runCatching { Diag.record("protection", said) }
            publish()
            return false
        }
        // 7 Nov, review: only a definite refusal counts toward the three tries; every attempt toward the day's cap.
        val definite = ManualCoverRule.definiteRefusal(result)
        val (n, all) = synchronized(mem) { (if (definite) (tries[k] ?: 0) + 1 else tries[k] ?: 0).also { tries[k] = it } to (attempts[k] ?: 0) }
        runCatching { Diag.record("protection", "Manual position ${p.symbol} (${p.qty}): stop and target not placed (" +
            (if (definite) "refusal $n of ${ManualCoverRule.MAX_TRIES}" else "not counted as a refusal") + ", attempt $all of " +
            "${ManualCoverRule.MAX_ATTEMPTS}): $result") }
        if (n < ManualCoverRule.MAX_TRIES && all < ManualCoverRule.MAX_ATTEMPTS) return false
        synchronized(mem) { gaveUp.add(k); tally = tally.copy(failed = tally.failed + 1) }
        loud(NOTICE_GAVE_UP, ManualCoverRule.saidGaveUp(p.symbol, p.qty, result.removePrefix("Not protected: ").trim(),
            if (n >= ManualCoverRule.MAX_TRIES) n else all))
        publish()
        return false
    }

    /**
     * 7 Nov, second and third review: the check just before placing, OUTSIDE the protections' lock (bounded reads). Null: go on;
     * else the reason (and the rule it falls under, when it is one Boss is told of).
     */
    private suspend fun recheck(p: ManualCoverRule.Pos, lot: Int, names: Map<String, String>): Pair<ManualCoverRule.Skip?, String>? {
        val again = ManualCoverRule.CHECKED_AGAIN
        val orders = Broker.within(8_000) { Broker.orders() } ?: return null to "$again: Zerodha's orders could not be read"
        val positions = Broker.within(8_000) { Broker.positionBook() } ?: return null to "$again: Zerodha's positions could not be read"
        val equity = equityFor(listOf(p.symbol)) ?: return null to "$again: the holdings could not be read"
        val tracked = withTimeoutOrNull(3_000) { runCatching { trackedIds(Protections.active()) }.getOrNull() }
            ?: return null to "$again: the protections could not be read"
        val heldNow = Broker.within(8_000) { ExitCoordinator.held(p.exchange, p.symbol, p.product, WHO, orders, positions) }
            ?: return null to "$again: what is held could not be read"
        val sendable = com.optionslab.engine.risk.ExitQty.sendable(heldNow, 0, p.qty, lot)
        return ManualCoverRule.precheck(p, posOf(positions), sellsOf(orders, tracked), heldTodayOf(positions), equity, sendable, p.qty, names)
    }

    /**
     * 7 Nov, third review: the last check, INSIDE the protections' lock, kept short so the stops' watch is never held up long: the
     * switch still on; the contract's exit turn taken without waiting ([keep] gets it, held while the orders go; busy: nothing);
     * its orders and GTTs, each read with a 2.5 s deadline (not read: nothing, never a refusal); nothing working or active on it.
     */
    private suspend fun lastCheck(p: ManualCoverRule.Pos, keep: (com.optionslab.ira.ExitTurns.Pass) -> Unit): Pair<ManualCoverRule.Skip?, String>? {
        if (!(loaded && on)) return ManualCoverRule.precheckInLock(false, true, true, false, true, false)
        val turn = ExitCoordinator.tryEnter(p.exchange, p.symbol, WHO) ?: return ManualCoverRule.precheckInLock(true, false, true, false, true, false)
        // 7 Nov, fourth review: the turn is always given back if anything throws before it is handed on (a cancellation too).
        try {
            val orders = Broker.within(2_500) { ExitCoordinator.orders(p.exchange, p.symbol, WHO) }
            val gtts = if (orders == null) null else Broker.within(2_500) { ExitCoordinator.gtts(p.exchange, p.symbol, WHO) }
            val r = ManualCoverRule.precheckInLock(true, true, orders != null,
                orders?.any { it.working && it.exchange == p.exchange && it.symbol == p.symbol } == true, gtts != null,
                gtts?.any { it.active && it.exchange == p.exchange && it.symbol == p.symbol } == true)
            if (r == null) keep(turn) else turn.leave()
            return r
        } catch (e: Throwable) {
            turn.leave()
            throw e
        }
    }

    /**
     * Said loudly, whatever Boss's "other alerts" switch: on the always-shown channel (as the arms' live-entry stop is), whose own
     * in-app banner is the one banner; without the app's context (or if that fails), the banner alone.
     */
    private fun loud(id: Int, said: String) {
        val c = app
        if (c != null && runCatching {
                com.optionslab.app.work.Notifier.post(c, id, com.optionslab.app.work.Notifier.APPROVAL, "Protection", said, "trade",
                    banner = Alerts.Kind.ERROR)
            }.isSuccess) return
        Alerts.post(said, Alerts.Kind.ERROR, "Protection", throttle = true)
    }

    /**
     * 7 Nov: "Erase everything" ([PersonalStores]): the switch back to its default (on: its settings key is gone with
     * [SecurePrefs.wipe]) and today's looks, tries and counts forgotten.
     */
    fun wipe() {
        on = true; loaded = true; switched = true
        clearMemory()
        publish()
    }

    private fun clearMemory() = synchronized(mem) {
        // 15 Nov: a follow-up look still waiting is dropped (cancelled, and the count moved, so it never looks: [followUp]).
        // Review: one already looking is not cut short (between a stop sent and its record); it finishes that one look.
        if (!followLooking) followJob?.cancel()
        followJob = null; memGen++
        looks.clear(); tries.clear(); attempts.clear(); lastAttempt.clear(); gaveUp.clear(); toldLow.clear(); leftAlone.clear()
        untracked.clear(); unhedged.clear(); hedgeLooks.clear(); unhedgedIds.clear()
        tally = ManualCoverRule.Tally()
    }

    /** Whether anything is still in memory beyond the defaults (the erase's tests). Memory only. */
    internal fun holdsForErase(): Boolean = !on || synchronized(mem) {
        looks.size > 0 || tries.isNotEmpty() || attempts.isNotEmpty() || lastAttempt.isNotEmpty() || gaveUp.isNotEmpty() ||
            toldLow.isNotEmpty() || leftAlone.isNotEmpty() || untracked.isNotEmpty() || unhedged.isNotEmpty() || hedgeLooks.size > 0 || unhedgedIds.isNotEmpty() || tally.protectedN + tally.leftAlone + tally.failed > 0
    }

    /** TEST ONLY: the switch as given (tests start it off, so no other test's live positions are covered), memory cleared. */
    internal fun resetForTest(on: Boolean = false, loaded: Boolean = true) {
        finishLooksForTest()
        this.on = on; this.loaded = loaded; switched = false
        followUpsOn = false; followMsForTest = null; followGate = null
        clearMemory()
        publish()
    }

    /**
     * 20 Nov 2026 (minor fixes, item 7: ManualCoverTest's first test failed in CI with nothing placed, in 1.2 s - the test before it,
     * aRetryCoversItOnceTheReadWorksAgain, ended while its follow-up look was still recording the protection it had just placed, and
     * that look then wrote it into the next test's fresh book, so the next test's position read as protected): TEST ONLY, a follow-up
     * look or a [kick]'s look still running finishes before the reset (one still waiting is cancelled, as [clearMemory] does).
     */
    internal fun finishLooksForTest(timeoutMs: Long = 15_000L) {
        val follow = synchronized(mem) { followJob?.also { if (!followLooking) it.cancel() } }
        val kicked = kickJob
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { follow?.join(); kicked?.join() }
        }
    }

    /** TEST ONLY: the context its notification is posted with ([init] sets it in the app; null: the banner alone). */
    internal fun attachForTest(context: Context?) { app = context?.applicationContext }

    /** TEST ONLY: today's counts. */
    internal fun tallyForTest(): ManualCoverRule.Tally = tally.on(Market.today())
}
