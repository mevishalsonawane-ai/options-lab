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
 * day). It acts only after two looks at least 20 s apart with the same quantity, and on a fresh positions read.
 *
 * It only ever ADDS exits (lowers risk, allowed for live by the safety rules). It never places an entry, never resizes and never
 * cancels or changes Boss's orders. It runs in the watch's pass like [Protections.tick], never on the order or tick path; its
 * switch is read once in the background at start and is memory-only for the pass. Never spoken: the app's banners and the
 * diagnostics only (a failure after three tries is also a notification).
 */
object ManualCover {
    private const val K_ON = "g.manualCover.on"
    /** The notification of a manual position left NOT protected after three tries. */
    private const val NOTICE_ID = 2038

    @Volatile private var on = true
    @Volatile private var loaded = false
    /** Boss changed the switch in this process (the start's late read never overrides it). */
    @Volatile private var switched = false
    @Volatile private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One pass at a time (the watch runs one; a test's pass could overlap). */
    private val passLock = Mutex()
    /** Memory only, under [mem]: the two looks, today's tries, the contracts given up on and those told "already down 5%". */
    private val mem = Any()
    private val looks = ManualCoverRule.Looks()
    private val tries = HashMap<String, Int>()
    private val gaveUp = HashSet<String>()
    private val toldLow = HashSet<String>()
    /** Contracts counted as "left alone" today (once each). */
    private val leftAlone = HashSet<String>()
    @Volatile private var tally = ManualCoverRule.Tally()

    data class View(val on: Boolean = true, val line: String = "")
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

    private fun publish() { runCatching { _view.value = View(on, line()) } }

    fun isOn(): Boolean = on

    /** Read once if the start's read has not landed (a worker thread only: Bot settings' card). */
    fun refresh() { if (!loaded) load() else publish() }

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
     * One look (the watch's full NSE pass, before the stops and targets). Returns at once when the switch is off or not read, in
     * Paper mode, logged out, or outside 09:15-15:24 on a trading day.
     */
    suspend fun run(s: AppSettings) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        if (!loaded || !on) return
        if (!s.live || !s.allowRealOrders) return
        if (!Broker.loggedIn) return
        val day = Market.today()
        if (!ManualCoverRule.inHours(Market.isTradingDay(day), Market.minuteNow())) return
        passLock.withLock { look(s, day) }
    }

    private suspend fun look(s: AppSettings, day: java.time.LocalDate) {
        val d = day.toString()
        synchronized(mem) {
            // A new day: yesterday's tries, refusals and words are over.
            tries.keys.removeAll { !it.startsWith("$d|") }; gaveUp.removeAll { !it.startsWith("$d|") }
            toldLow.removeAll { !it.startsWith("$d|") }; leftAlone.removeAll { !it.startsWith("$d|") }
            tally = tally.on(day)
        }
        // The pass's one shared read finds the candidates; a fresh read is made before anything is placed (below).
        val book = Broker.within(8_000) { Broker.passPositionBook() } ?: return
        val candidates = book.net.map { ManualCoverRule.Pos(it.symbol, it.exchange, it.product, it.qty, it.avg, it.last) }
            .filter { ManualCoverRule.covered(it) }
        if (candidates.isEmpty()) { synchronized(mem) { looks.clear() }; return }
        // What the app follows (arms, Pine, arms' live trades, protections). A book not read in time: nothing this look.
        val beliefs = ZerodhaSync.beliefs() ?: return
        // The Strategy Module's live runs' legs too (not in the sync's beliefs): never a manual position.
        val legs = withTimeoutOrNull(3_000) { runCatching { Strategies.liveLegSymbols() }.getOrNull() } ?: return
        val today0 = day.atStartOfDay(com.optionslab.engine.IST).toInstant().toEpochMilli()
        val prot = withTimeoutOrNull(3_000) { runCatching { Protections.active() to Protections.since(today0) }.getOrNull() } ?: return
        val first = ManualCoverRule.Facts(
            followed = beliefs.map { it.symbol }.toSet() + legs,
            protectedNow = prot.first.filter { it.live }.map { it.symbol }.toSet(),
            endedToday = prot.second.filter { it.live && !it.active }.map { ManualCoverRule.Ended(it.symbol, it.note) },
            handsOff = candidates.filter { ArmLive.handsOffNow(it.exchange, it.symbol) }.map { it.key }.toSet(),
            expiring = expiringNow(s, day, candidates),
        )
        val given = synchronized(mem) { gaveUp.toSet() }
        val open = ArrayList<ManualCoverRule.Pos>()
        for (p in candidates) {
            when (val why = ManualCoverRule.skip(p, first)) {
                null -> if ("$d|${p.key}" !in given) open += p
                else -> countLeftAlone(d, p, why)
            }
        }
        if (open.isEmpty()) { synchronized(mem) { looks.keepOnly(emptySet()) }; return }
        // Zerodha's orders and GTTs, read only now (a manual position nothing follows): not read in time, nothing this look.
        val orders = Broker.within(8_000) { Broker.orders() } ?: return
        val gtts = Broker.within(8_000) { Broker.gtts() } ?: return
        val owners = withTimeoutOrNull(3_000) { runCatching { Strategies.owners() }.getOrNull() } ?: return
        val facts = first.copy(
            workingOrders = orders.filter { it.working }.map { "${it.exchange}:${it.symbol}" }.toSet(),
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
        val due = synchronized(mem) {
            val keys = uncovered.map { it.key }.toSet()
            looks.keepOnly(keys)
            uncovered.filter { looks.see(it.key, it.qty, now) }
        }
        if (due.isEmpty()) return
        // Placing: Zerodha's positions read now (the pass's shared read is for words only), the same quantity still held.
        val fresh = Broker.within(8_000) { Broker.positionBook() } ?: return
        for (p in due) {
            val rows = fresh.net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }
            val qty = rows.sumOf { it.qty }
            val row = rows.firstOrNull { it.qty != 0 }
            if (row == null || qty != p.qty) {
                // Changed since the look: it starts its two looks again.
                synchronized(mem) { looks.see(p.key, qty, now) }
                continue
            }
            if (ArmLive.handsOffNow(p.exchange, p.symbol)) continue
            cover(d, p.copy(avg = row.avg, last = row.last))
        }
    }

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

    /** [p] left alone because of Boss's own orders, GTT or removal (counted once a day; the others are not Boss's positions). */
    private fun countLeftAlone(d: String, p: ManualCoverRule.Pos, why: ManualCoverRule.Skip) {
        if (why != ManualCoverRule.Skip.WORKING_ORDER && why != ManualCoverRule.Skip.GTT && why != ManualCoverRule.Skip.ENDED_TODAY) return
        val first = synchronized(mem) { leftAlone.add("$d|${p.key}").also { if (it) tally = tally.copy(leftAlone = tally.leftAlone + 1) } }
        if (first) {
            runCatching { Diag.record("protection", "Manual position ${p.symbol} (${p.qty}): no automatic stop and target, ${why.words}.") }
            publish()
        }
    }

    private suspend fun cover(d: String, p: ManualCoverRule.Pos) {
        val k = "$d|${p.key}"
        val tick = runCatching { Broker.spec(p.exchange, p.symbol).tickSize }.getOrNull()?.takeIf { it > 0 } ?: 0.05
        val plan = ManualCoverRule.plan(p.avg, p.last, tick)
        val stop: Double; val target: Double?
        when (plan) {
            is ManualCoverRule.Plan.NoPrice -> return
            is ManualCoverRule.Plan.TooLow -> {
                // Said once a day; looked at again on later passes (if the price comes back over the stop, it is protected then).
                if (synchronized(mem) { toldLow.add(k).also { if (it && leftAlone.add(k)) tally = tally.copy(leftAlone = tally.leftAlone + 1) } }) {
                    val said = ManualCoverRule.saidTooLow(p.symbol, p.last, plan.stop)
                    Alerts.post(said, Alerts.Kind.ERROR, "Protection")
                    runCatching { Diag.record("protection", "Manual position: $said") }
                    publish()
                }
                return
            }
            is ManualCoverRule.Plan.StopOnly -> { stop = plan.stop; target = null }
            is ManualCoverRule.Plan.Both -> { stop = plan.stop; target = plan.target }
        }
        val result = runCatching { Protections.protectLive(p.symbol, p.exchange, p.product, p.qty, p.last, stop, null, target) }
            .getOrElse { "Not protected: ${it.message}" }
        // Zerodha's answer to a day GTT lost: the protection is kept and looked for on the next pass, so it counts as placed.
        val placed = result.startsWith("Protected ") || runCatching { Protections.forSymbol(true, p.symbol) }.getOrNull() != null
        if (placed) {
            synchronized(mem) { tally = tally.copy(protectedN = tally.protectedN + 1); tries.remove(k) }
            val extra = if (plan is ManualCoverRule.Plan.StopOnly) " " + ManualCoverRule.saidStopOnly(p.symbol, p.last, plan.target) else ""
            val said = ManualCoverRule.saidProtected(p.symbol, p.qty, p.avg, result) + extra
            Alerts.post(said, Alerts.Kind.SUCCESS, "Protection")
            runCatching { Diag.record("protection", said) }
            publish()
            return
        }
        val n = synchronized(mem) { (tries[k] ?: 0).plus(1).also { tries[k] = it } }
        runCatching { Diag.record("protection", "Manual position ${p.symbol} (${p.qty}): stop and target not placed (try $n of " +
            "${ManualCoverRule.MAX_TRIES}): $result") }
        if (n < ManualCoverRule.MAX_TRIES) return
        synchronized(mem) { gaveUp.add(k); tally = tally.copy(failed = tally.failed + 1) }
        val said = ManualCoverRule.saidGaveUp(p.symbol, p.qty, result.removePrefix("Not protected: ").trim())
        val c = app
        if (c != null) runCatching { com.optionslab.app.work.Notifier.post(c, NOTICE_ID, com.optionslab.app.work.Notifier.RISK, "Not protected", said, "trade") }
            .onFailure { Alerts.error(said, "Protection") }
        else Alerts.error(said, "Protection")
        publish()
    }

    /**
     * 7 Nov: "Erase everything" ([PersonalStores]): the switch back to its default (on: its settings key is gone with
     * [SecurePrefs.wipe]) and today's looks, tries and counts forgotten.
     */
    fun wipe() {
        on = true; loaded = true; switched = true
        synchronized(mem) { looks.clear(); tries.clear(); gaveUp.clear(); toldLow.clear(); leftAlone.clear(); tally = ManualCoverRule.Tally() }
        publish()
    }

    /** Whether anything is still in memory beyond the defaults (the erase's tests). Memory only. */
    internal fun holdsForErase(): Boolean = !on || synchronized(mem) {
        looks.size > 0 || tries.isNotEmpty() || gaveUp.isNotEmpty() || toldLow.isNotEmpty() || leftAlone.isNotEmpty() ||
            tally.protectedN + tally.leftAlone + tally.failed > 0
    }

    /** TEST ONLY: the switch as given (tests start it off, so no other test's live positions are covered), memory cleared. */
    internal fun resetForTest(on: Boolean = false, loaded: Boolean = true) {
        this.on = on; this.loaded = loaded; switched = false
        synchronized(mem) { looks.clear(); tries.clear(); gaveUp.clear(); toldLow.clear(); leftAlone.clear(); tally = ManualCoverRule.Tally() }
        publish()
    }

    /** TEST ONLY: today's counts. */
    internal fun tallyForTest(): ManualCoverRule.Tally = tally.on(Market.today())
}
