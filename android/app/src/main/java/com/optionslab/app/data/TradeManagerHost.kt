package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.Findings
import com.optionslab.ira.TradeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's side of the trade manager ([TradeManager], Boss 10 Oct): ONE place every strategy hands its open trade to with
 * one call - [attach] (Solo, Pine) or [sync] from an arm's book (the ORB arms, Liquidity, Hero, Night, VIX divergence, every
 * MCX leg) - and asks on each of its own looks whether the manager wants it out ([exitDue]) or has moved its target and lock
 * ([levels]). The strategy keeps its own exit path, orders and square-off: the manager never places, enlarges or reverses
 * anything and an exit never waits for it.
 *
 *  - Modes per strategy family and account ([TradeManager.Policy]): Solo and Pine act on PAPER and are SHADOW on LIVE; every
 *    other strategy is SHADOW (records only; its own exits stay authoritative) until Boss switches it on; OFF records nothing.
 *    LIVE to ACT takes the PIN ([setMode]). The policy is read once, off the hot paths ([ensureLoaded]); a trade keeps the mode
 *    it started with.
 *  - [evaluate]: every second from the order flow's pump and on the fast lanes' looks - memory only (the flow's reads and
 *    auction, the brain, the findings bus, the held option's price in memory): no Keystore, no network.
 *  - The record ([records]): every decision with its evidence, and the original rules followed on as a counterfactual on the
 *    option's 1-minute wicks ([settleNow], once a minute, off the hot paths). A plain JSON file under noBackupFilesDir,
 *    written from memory at most every 5 s on its own thread - never per tick, never through the Keystore.
 */
object TradeManagerHost {
    private const val K_POLICY = "trademanager.policy"
    private const val KEEP = 300
    private const val EVAL_MS = 1_000L
    private const val SAVE_MS = 5_000L

    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch { runCatching { ensureLoaded(); load() } }
        subscribe()
    }

    @Volatile private var subscribed = false

    /**
     * The findings bus for the held positions' indices: a directional finding on an index a managed trade is on brings the
     * next look forward (off the poster's thread; the look itself is memory only). The manager's own posts are not heard.
     */
    private fun subscribe() {
        if (subscribed) return
        subscribed = true
        runCatching {
            SmartWorkers.bus.subscribe(Findings.Sub(emptySet(), emptySet()) { f ->
                if (f.who != TradeManager.WHO && live.isNotEmpty() &&
                    live.values.any { f.instrument == Findings.ALL || it.reg.trade.underlying.equals(f.instrument, ignoreCase = true) })
                    if (!testNoWake) scope.launch { runCatching { onLook() } }
            })
        }
    }

    // ---- the modes --------------------------------------------------------------------------------------------------------

    private val _policy = MutableStateFlow(TradeManager.Policy())
    val policy: StateFlow<TradeManager.Policy> = _policy
    @Volatile private var policyLoaded = false

    /** Boss's modes read once (a worker thread: never the main thread, never the order path). */
    fun ensureLoaded() {
        if (policyLoaded) return
        synchronized(this) {
            if (policyLoaded) return
            val p = runCatching { TradeManager.decode(SecurePrefs.getString(K_POLICY)) }.getOrNull() ?: return
            _policy.value = p
            policyLoaded = true
        }
    }

    const val PIN_NEEDED = "The app trades live here: confirm with your PIN or fingerprint to let the trade manager act on live trades. " +
        TradeManager.UNPROVEN

    /** [family]'s mode on [account] set to [m] (an unwired family stays OFF). LIVE to ACT needs [pinConfirmed]. What to say. */
    fun setMode(family: String, account: TradeManager.Account, m: TradeManager.Mode, pinConfirmed: Boolean = false): String {
        val f = TradeManager.familyOf(family) ?: return "No such strategy for the trade manager."
        if (m == TradeManager.Mode.ACT && !f.canAct)
            return "${f.name}: its own exits do not take the trade manager's word yet, so it records only (SHADOW); nothing changed."
        if (m == TradeManager.Mode.ACT && f.paperOnly && account == TradeManager.Account.LIVE) return "${f.name} trades on paper only."
        if (TradeManager.needsPin(m, account) && !pinConfirmed) return PIN_NEEDED
        synchronized(this) {
            val next = _policy.value.with(family, account, m)
            _policy.value = next
            runCatching { SecurePrefs.putAllSoon(mapOf(K_POLICY to TradeManager.encode(next))) }
        }
        val who = "${TradeManager.familyName(family)} ${account.name.lowercase(Locale.ENGLISH)}"
        runCatching { Diag.record("trade manager", "$who: ${m.name}") }
        return when (m) {
            TradeManager.Mode.OFF -> "$who: the trade manager is off (from the next trade)."
            TradeManager.Mode.SHADOW -> "$who: the trade manager records what it would do; nothing changes (from the next trade)."
            TradeManager.Mode.ACT -> "$who: the trade manager acts from the next trade - early exits, and a target moved out only with the lock raised." +
                if (account == TradeManager.Account.LIVE) " ${TradeManager.UNPROVEN}" else ""
        }
    }

    // ---- registrations ------------------------------------------------------------------------------------------------------

    /**
     * One strategy's open trade, handed over by [attach]: the [trade], its option's price in memory ([premium]: the stream's
     * tick or a recent read - never a network read), its 1-minute bars ([bars], read off the hot paths for the counterfactual
     * and the trail), the strategy's own profit lock ([lockAt]: the lock a best price earns; null: none), a judge of its
     * original rules when they are not on the premium ([originalExit]: Solo's index rules; null: the premium stop, target,
     * lock and square-off are followed on the wicks), the underlying's price in memory ([underlyingPrice]: an index stop's
     * distance), and [wake]: the strategy's own look, run at once when the manager acts (its own lock, its own exit path).
     */
    class Registration(
        val trade: TradeManager.ManagedTrade,
        val premium: () -> Double?,
        val bars: (suspend () -> List<TradeManager.Bar>?)? = null,
        val lockAt: ((Double) -> Double?)? = null,
        val originalExit: (suspend (List<TradeManager.Bar>, Long) -> TradeManager.Exit?)? = null,
        val underlyingPrice: (() -> Double?)? = null,
        val wake: (suspend () -> Unit)? = null,
    )

    private class Live(@Volatile var reg: Registration, @Volatile var state: TradeManager.State, @Volatile var pendingExit: String? = null,
                       @Volatile var high: Double? = null, @Volatile var atr: Double? = null, @Volatile var noteLock: Double? = null)

    private val live = ConcurrentHashMap<String, Live>()
    private val recs = LinkedHashMap<String, TradeManager.Record>()
    private val _records = MutableStateFlow<List<TradeManager.Record>>(emptyList())
    /** Every managed trade's record, oldest first (the cards, the "Trade manager record" section and Jarvis read it). */
    val records: StateFlow<List<TradeManager.Record>> = _records

    private fun publish() { _records.value = synchronized(recs) { recs.values.toList() } }

    /**
     * [r]'s trade handed to the manager (the one call a strategy makes; cheap and idempotent - call it on every look). OFF for
     * its strategy and account: nothing. A trade already known keeps its state (and, after a restart, its record's target, lock
     * and extensions). Returns the mode the trade is managed in.
     */
    fun attach(r: Registration): TradeManager.Mode {
        val t = r.trade
        live[t.tradeId]?.let { l ->
            l.reg = r
            return modeOf(t.tradeId)
        }
        val existing = synchronized(recs) { recs[t.tradeId] }
        if (existing?.closed == true) return TradeManager.Mode.OFF
        val mode = existing?.mode ?: _policy.value.mode(t.family, t.account)
        if (mode == TradeManager.Mode.OFF) return mode
        val st = TradeManager.start(t).let { s ->
            if (existing == null) s else s.copy(target = existing.target ?: s.target, lock = existing.lock, extensions = existing.extensions,
                exited = existing.notes.any { it.kind == TradeManager.EXIT_EARLY || it.kind == TradeManager.LOCK_NOTE })
        }
        live[t.tradeId] = Live(r, st, existing?.notes?.lastOrNull { it.acted && (it.kind == TradeManager.EXIT_EARLY || it.kind == TradeManager.LOCK_NOTE) }
            ?.takeIf { mode == TradeManager.Mode.ACT }?.words)
        if (existing == null) {
            synchronized(recs) { recs[t.tradeId] = TradeManager.Record(t, mode) }
            publish(); dirty()
        }
        return mode
    }

    private fun modeOf(id: String): TradeManager.Mode = synchronized(recs) { recs[id]?.mode } ?: TradeManager.Mode.OFF

    /** In ACT: why the manager wants [tradeId] out now (null: it does not, or it only records). The strategy exits its own way. */
    fun exitDue(tradeId: String): String? = live[tradeId]?.takeIf { modeOf(tradeId) == TradeManager.Mode.ACT }?.pendingExit

    /** In ACT: the manager's (target, lock) for [tradeId] (either null: the strategy's own); null when it does not act. */
    fun levels(tradeId: String): Pair<Double?, Double?>? {
        val l = live[tradeId] ?: return null
        if (modeOf(tradeId) != TradeManager.Mode.ACT) return null
        val st = l.state
        val tg = st.target?.takeIf { st.extensions > 0 }
        return tg to st.lock
    }

    /** The trade card's line for [tradeId] (null: not managed). */
    fun cardLine(tradeId: String): String? = synchronized(recs) { recs[tradeId] }?.let { TradeManager.cardLine(it) }

    /**
     * The strategy closed [tradeId] at [price] ([why] in its words): its actual result, and the record settles (the original
     * rules followed on, when the manager acted). No price read: the trade is let go unrecorded.
     */
    fun closed(tradeId: String, price: Double?, why: String, atMs: Long = nowMs()) {
        val l = live[tradeId]
        if (price == null || price.isNaN() || price == 0.0) { live.remove(tradeId); drop(tradeId); return }
        synchronized(recs) {
            val r = recs[tradeId] ?: return
            recs[tradeId] = TradeManager.closed(r, TradeManager.Exit(atMs, price, why.take(80)))
        }
        if (l != null) l.pendingExit = null
        publish(); dirty()
    }

    /** A trade let go with no result (closed outside, no price): its record is dropped (it compares nothing). */
    fun drop(tradeId: String) {
        live.remove(tradeId)
        val gone = synchronized(recs) { recs[tradeId]?.takeIf { it.actual == null }?.let { recs.remove(tradeId) } }
        if (gone != null) { publish(); dirty() }
    }

    // ---- the other arms' one call (10 Oct: every strategy hooked, SHADOW by default) ------------------------------------------

    /**
     * One position of an arm as its book holds it: [family] ([TradeManager.FAMILIES]), a stable [tradeId], what it holds
     * ([symbol], the paper book's; [kite] Zerodha's for a live one), [side] +1 a long bet on the underlying (a call, a future
     * bought) and −1 a short one (a put bought, a future sold: [shortFuture] for the latter), its [entry] and [qty], its own
     * [stop] / [target] on that price where it has them (null: none there), its last exit time [squareOffMs], [live], when it
     * was bought, and - once closed - its [exit] price and [exitWhy].
     */
    data class ArmTrade(
        val family: String, val tradeId: String, val label: String, val underlying: String, val side: Int, val symbol: String,
        val entry: Double, val qty: Int, val stop: Double?, val target: Double?, val squareOffMs: Long, val live: Boolean, val entryMs: Long,
        val exit: Double? = null, val exitWhy: String? = null, val exitMs: Long? = null, val kite: String? = null,
        val shortFuture: Boolean = false, val tick: Double = 0.05,
    )

    /**
     * An arm's positions handed over in ONE call, from its book's publish (memory only; never on its order path): each open
     * one is attached (SHADOW by default: the arm's own exits stay authoritative, the manager only records), each one closed
     * since is told its real exit. A short future is followed with its prices negated (a long on −price: the same
     * arithmetic; the words show the market's prices). Never throws.
     */
    fun sync(trades: List<ArmTrade>) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        for (a in trades) runCatching {
            val k = if (a.shortFuture) -1.0 else 1.0
            if (a.exit != null) {
                if (live.containsKey(a.tradeId) || synchronized(recs) { recs[a.tradeId]?.actual == null && recs.containsKey(a.tradeId) })
                    closed(a.tradeId, a.exit * k, a.exitWhy ?: "closed", a.exitMs ?: nowMs())
                return@runCatching
            }
            if (live.containsKey(a.tradeId)) return@runCatching
            val acct = if (a.live) TradeManager.Account.LIVE else TradeManager.Account.PAPER
            val t = TradeManager.ManagedTrade("${a.family}:${a.label}", a.tradeId, a.label, a.underlying, a.side, a.kite ?: a.symbol,
                a.entry * k, a.qty, a.stop?.let { it * k }, a.target?.let { it * k }, TradeManager.Caps(a.squareOffMs, tick = a.tick), acct,
                a.entryMs, if (a.shortFuture) 0.0 else com.optionslab.engine.orb.ProfitLock.roundTripPerUnit(a.entry, a.qty))
            val sym = a.symbol
            val kite = a.kite
            attach(Registration(t,
                premium = {
                    val px = if (!a.live) Paper.memPrice(sym)
                        else kite?.let { s -> Broker.tokenOf("NFO:$s")?.let { tok -> KiteStream.tick(tok)?.last }?.takeIf { it > 0 } }
                    px?.let { it * k }
                },
                bars = {
                    Paper.contractOf(sym)?.let { c -> Paper.minutes(c) }?.map {
                        if (k > 0) TradeManager.Bar(it.epochSecond * 1000L, it.open, it.high, it.low, it.close)
                        else TradeManager.Bar(it.epochSecond * 1000L, -it.open, -it.low, -it.high, -it.close)
                    }
                }))
        }
    }

    // ---- the looks -----------------------------------------------------------------------------------------------------------

    fun nowMs(): Long = runCatching { Market.now().toInstant().toEpochMilli() }.getOrDefault(System.currentTimeMillis())

    @Volatile private var lastEval = 0L

    /** From the fast lanes' looks: [evaluate] at most once a second. Cheap; never throws. */
    fun onLook() { if (live.isEmpty()) return; val n = nowMs(); if (n - lastEval >= EVAL_MS || n < lastEval) evaluate(n) }

    /** TEST ONLY: the readings at a look (null in the app, always: they come from memory). Throws unless BuildConfig.DEBUG. */
    @Volatile internal var testSnapshot: ((TradeManager.ManagedTrade, Long) -> TradeManager.Snapshot)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test readings exist only in debug builds" }; field = v }

    /** TEST ONLY: the option's 1-minute bars (null in the app). Throws unless BuildConfig.DEBUG. */
    @Volatile internal var testBars: ((String) -> List<TradeManager.Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test bars exist only in debug builds" }; field = v }

    private fun snapshot(l: Live, now: Long): TradeManager.Snapshot {
        val t = l.reg.trade
        testSnapshot?.let { return it(t, now) }
        val u = t.underlying.uppercase(Locale.ENGLISH)
        val read = runCatching { OrderFlowLive.reads.value[u] }.getOrNull()
        val auc = runCatching { OrderFlowLive.auction.value[u] }.getOrNull()
        val vixNow = runCatching { MoveRecorder.vixAt(now / 1000) }.getOrNull()
        val vix5 = runCatching { MoveRecorder.vixAt(now / 1000 - 300) }.getOrNull()
        return TradeManager.Snapshot(
            atMs = now, premium = runCatching { l.reg.premium() }.getOrNull(), premiumHigh = l.high, premiumAtr = l.atr,
            underlyingPrice = runCatching { l.reg.underlyingPrice?.invoke() }.getOrNull(),
            flow = read, futPrice = auc?.last?.takeIf { it > 0 } ?: read?.mid,
            vwap = auc?.vwap, valueHigh = auc?.today?.vah, valueLow = auc?.today?.vaLow, divergence = auc?.divergence,
            vixChangePct = if (vixNow != null && vix5 != null && vix5 > 0) (vixNow / vix5 - 1) * 100 else null,
            brain = runCatching { SmartWorkers.brain.value }.getOrNull(),
            findings = runCatching { SmartWorkers.bus.recent(now, u) }.getOrDefault(emptyList()),
        )
    }

    private val evalLock = Any()
    @Volatile private var minuteSettled = -1L

    /**
     * One look at every managed trade at [now]: the decision, recorded with its evidence; in ACT an exit is handed to the
     * strategy (its own look woken at once) and a moved target / lock is read by it on that look; in SHADOW only recorded.
     * Memory only. Once a minute the counterfactual is settled off this thread.
     */
    fun evaluate(now: Long = nowMs()) {
        if (live.isEmpty()) return
        synchronized(evalLock) {
            lastEval = now
            for ((id, l) in live) runCatching { one(id, l, now) }
        }
        val minute = now / 60_000L
        if (minute != minuteSettled && !testNoWake) { minuteSettled = minute; scope.launch { runCatching { settleNow(now) } } }
    }

    private fun one(id: String, l: Live, now: Long) {
        val r0 = synchronized(recs) { recs[id] } ?: return
        if (r0.closed) {
            // Closed: the manager's way in SHADOW may still be open (an extension the real trade never had) - settled from bars.
            return
        }
        val t = l.reg.trade
        val s = snapshot(l, now)
        val step = TradeManager.decide(t, s, l.state)
        l.state = step.state
        val act = r0.mode == TradeManager.Mode.ACT
        val px = s.premium
        when (val d = step.decision) {
            is TradeManager.Decision.Hold -> {}
            is TradeManager.Decision.Trail -> {
                val was = l.noteLock
                update(id) { it.copy(lock = d.newStop) }
                // Said when it first trails and then on each rise of 1% of the entry or more (not a line a second).
                if (was == null || d.newStop >= was + maxOf(0.01 * t.entry, t.caps.tick)) {
                    l.noteLock = d.newStop
                    note(id, TradeManager.Note(now, TradeManager.TRAIL_NOTE, "lock", "lock trailed to ${TradeManager.lv(d.newStop)} (best ${TradeManager.lv(step.state.peak)})",
                        px, act, mapOf("lock" to d.newStop, "peak" to step.state.peak), lock = d.newStop))
                }
                if (act) wake(l)
            }
            is TradeManager.Decision.ExitEarly -> {
                val lockHit = d.rule == TradeManager.Rule.LOCK
                val words = if (lockHit) "profit lock hit" else TradeManager.short(d.rule, t.side)
                val kind = if (lockHit) TradeManager.LOCK_NOTE else TradeManager.EXIT_EARLY
                note(id, TradeManager.Note(now, kind, d.rule.key, "$words (${d.reason})", px, act, d.evidence, lock = step.state.lock))
                update(id) { r ->
                    var x = r.copy(actedAtMs = r.actedAtMs ?: now)
                    if (!act && px != null) x = x.copy(manager = TradeManager.Exit(now, px, if (lockHit) "LOCK" else "EARLY: ${d.rule.key}"))
                    x
                }
                val who = TradeManager.familyName(t.family)
                if (act) {
                    l.pendingExit = if (lockHit) "the trade manager's profit lock" else "the trade manager: $words"
                    wake(l)
                    post(t, "$who exited early: $words" + if (lockHit) " (lock ${TradeManager.lv(step.state.lock ?: 0.0)})" else "")
                } else post(t, "$who would have exited early (shadow): $words")
            }
            is TradeManager.Decision.Extend -> {
                note(id, TradeManager.Note(now, TradeManager.EXTEND_NOTE, TradeManager.Rule.EXTEND.key,
                    "target ${TradeManager.lv(d.newTarget)}, lock ${TradeManager.lv(d.newStop)} (${d.reason})", px, act, d.evidence,
                    target = d.newTarget, lock = d.newStop))
                l.noteLock = d.newStop
                update(id) { it.copy(target = d.newTarget, lock = d.newStop, extensions = step.state.extensions, actedAtMs = it.actedAtMs ?: now) }
                val who = TradeManager.familyName(t.family)
                if (act) {
                    wake(l)
                    post(t, "$who target extended to ${TradeManager.lv(d.newTarget)}, lock raised to ${TradeManager.lv(d.newStop)}")
                } else post(t, "$who would have extended its target to ${TradeManager.lv(d.newTarget)} with the lock at ${TradeManager.lv(d.newStop)} (shadow)")
            }
        }
    }

    /** TEST ONLY: the strategy's own look is not woken (the test runs it itself). False in the app. Throws unless BuildConfig.DEBUG. */
    @Volatile internal var testNoWake = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test switch exists only in debug builds" }; field = v }

    /** The strategy's own look, run at once on the manager's thread (its own lock and exit path); never on the caller's. */
    private fun wake(l: Live) {
        if (testNoWake) return
        l.reg.wake?.let { w -> scope.launch { runCatching { w() } } }
    }

    private fun post(t: TradeManager.ManagedTrade, words: String) {
        runCatching { SmartWorkers.found(TradeManager.WHO, Findings.Kind.OTHER, t.underlying, 0, null, 50, words, ttlMs = 30 * 60_000L) }
        runCatching { Diag.record("trade manager", "${t.label}: $words") }
    }

    private fun note(id: String, n: TradeManager.Note) {
        update(id) { it.copy(notes = (it.notes + n).takeLast(60)) }
    }

    private fun update(id: String, f: (TradeManager.Record) -> TradeManager.Record) {
        synchronized(recs) { recs[id]?.let { recs[id] = f(it) } }
        publish(); dirty()
    }

    // ---- the counterfactual (off the hot paths) -------------------------------------------------------------------------------

    private val settling = AtomicBoolean(false)

    /**
     * Once a minute (and by tests): each followed trade's option bars read (its best wick and recent range for the trail and
     * the extension's size), and each closed trade's other way settled - the original rules from the manager's first action
     * (ACT), the manager's way from the real close (SHADOW, after a would-be extension). A trade settled is let go.
     */
    internal suspend fun settleNow(now: Long = nowMs()) {
        if (!settling.compareAndSet(false, true)) return
        try {
            for ((id, l) in live.toMap()) runCatching {
                val t = l.reg.trade
                val bars = testBars?.invoke(id) ?: l.reg.bars?.invoke() ?: return@runCatching
                val mine = bars.filter { it.startMs >= t.entryMs - 60_000L }
                if (mine.isNotEmpty()) {
                    l.high = mine.filter { it.startMs + 60_000L <= now }.maxOfOrNull { it.high } ?: l.high
                    val last = mine.takeLast(10)
                    l.atr = last.map { it.high - it.low }.average().takeIf { it.isFinite() && it > 0 }
                }
                val r = synchronized(recs) { recs[id] } ?: return@runCatching
                if (!r.closed) return@runCatching
                var x = r
                val acted = r.actedAtMs
                if (x.original == null && acted != null) {
                    val o = l.reg.originalExit?.invoke(bars, now) ?: TradeManager.walk(t,
                        TradeManager.Path(t.originalStop, t.originalTarget, t.caps.squareOffMs, t.entry), bars, acted, l.reg.lockAt).exit
                    if (o != null) x = x.copy(original = o)
                }
                // A would-have exit (SHADOW) taken with no price in memory then: priced on its minute's close.
                val would = r.notes.lastOrNull { !it.acted && (it.kind == TradeManager.EXIT_EARLY || it.kind == TradeManager.LOCK_NOTE) }
                if (x.manager == null && would != null)
                    TradeManager.priced(bars, would.atMs, "EARLY")?.let { e -> x = x.copy(manager = e.copy(why = if (would.kind == TradeManager.LOCK_NOTE) "LOCK" else "EARLY: ${would.rule}")) }
                if (x.manager == null && acted != null && would == null) {
                    val from = r.actual?.atMs ?: acted
                    val m = TradeManager.walk(t, TradeManager.Path(r.lock ?: t.originalStop, r.target, t.caps.squareOffMs, t.entry, trailing = r.lock != null),
                        bars, from, l.reg.lockAt).exit
                    if (m != null) x = x.copy(manager = m)
                }
                if (x !== r) {
                    synchronized(recs) { recs[id] = x }
                    publish(); dirty()
                    x.vsOriginal?.let { v -> runCatching { Diag.record("trade manager", "${t.label}: settled, ${TradeManager.rs(v)} against the original rules") } }
                }
                if (x.settled || now > t.caps.squareOffMs + 30 * 60_000L) live.remove(id)
            }
        } finally { settling.set(false) }
    }

    // ---- words ---------------------------------------------------------------------------------------------------------------

    fun recordLines(): List<String> = TradeManager.recordLines(_records.value, _policy.value)

    /** Jarvis: "how is the trade manager doing?" / "why did Solo exit early?". */
    fun answer(a: TradeManager.Ask): String {
        val rs = _records.value
        return if (a.why) TradeManager.answerWhy(a.family, rs) { SmartWorkers.hhmm(it) } else TradeManager.answerStatus(rs, _policy.value)
    }

    /** The diagnostics' lines. */
    fun diagLines(): List<String> = listOf("Trade manager modes: " + TradeManager.FAMILIES.map { it.key }.joinToString("; ") { f ->
        "${TradeManager.familyName(f)} paper ${_policy.value.mode(f, TradeManager.Account.PAPER).name}, live ${_policy.value.mode(f, TradeManager.Account.LIVE).name}"
    }) + recordLines().map { "Trade manager: $it" } + live.keys.map { "Trade manager follows $it" }

    // ---- the file --------------------------------------------------------------------------------------------------------------

    private fun file(): File? = appContext?.let { File(File(it.noBackupFilesDir, "trademanager"), "records.json") }

    @Volatile private var loaded = false
    private val pendingSave = AtomicBoolean(false)

    private fun dirty() {
        if (!pendingSave.compareAndSet(false, true)) return
        scope.launch {
            kotlinx.coroutines.delay(SAVE_MS)
            pendingSave.set(false)
            runCatching { save() }
        }
    }

    /** Written now (tests, and the coalesced save). */
    internal fun save() {
        val f = file() ?: return
        val list = synchronized(recs) { recs.values.toList().takeLast(KEEP) }
        val a = JSONArray()
        for (r in list) a.put(enc(r))
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(a.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** The records read once (a worker thread). */
    internal fun load() {
        if (loaded) return
        val f = file() ?: return
        loaded = true
        val text = runCatching { if (f.isFile) f.readText() else null }.getOrNull() ?: return
        val got = runCatching { val a = JSONArray(text); (0 until a.length()).mapNotNull { runCatching { dec(a.getJSONObject(it)) }.getOrNull() } }.getOrDefault(emptyList())
        synchronized(recs) { for (r in got) if (r.trade.tradeId !in recs) recs[r.trade.tradeId] = r }
        publish()
    }

    private fun dn(o: JSONObject, k: String): Double? = if (o.has(k) && !o.isNull(k)) o.getDouble(k).takeIf { it.isFinite() } else null

    private fun encExit(e: TradeManager.Exit?): JSONObject? = e?.let { JSONObject().put("at", it.atMs).put("px", it.price).put("why", it.why) }
    private fun decExit(o: JSONObject?): TradeManager.Exit? = o?.let { TradeManager.Exit(it.getLong("at"), it.getDouble("px"), it.getString("why")) }

    private fun enc(r: TradeManager.Record): JSONObject {
        val t = r.trade
        val o = JSONObject().put("sid", t.strategyId).put("tid", t.tradeId).put("label", t.label).put("u", t.underlying).put("side", t.side)
            .put("inst", t.instrument).put("entry", t.entry).put("qty", t.qty).put("sq", t.caps.squareOffMs).put("maxx", t.caps.maxExtensions)
            .put("tick", t.caps.tick).put("acct", t.account.name).put("ems", t.entryMs).put("chg", t.chargesPerUnit).put("mode", r.mode.name)
            .put("ext", r.extensions)
        t.originalStop?.let { o.put("os", it) }; t.originalTarget?.let { o.put("ot", it) }
        t.stopUnderlying?.let { o.put("su", it) }; t.entryUnderlying?.let { o.put("eu", it) }
        r.target?.let { o.put("tg", it) }; r.lock?.let { o.put("lk", it) }; r.actedAtMs?.let { o.put("acted", it) }
        encExit(r.actual)?.let { o.put("actual", it) }; encExit(r.manager)?.let { o.put("mgr", it) }; encExit(r.original)?.let { o.put("orig", it) }
        o.put("notes", JSONArray().apply {
            for (n in r.notes) put(JSONObject().put("at", n.atMs).put("k", n.kind).put("r", n.rule).put("w", n.words).put("a", n.acted)
                .apply { n.premium?.takeIf { it.isFinite() }?.let { put("px", it) }; n.target?.let { put("tg", it) }; n.lock?.let { put("lk", it) }
                    put("ev", JSONObject().apply { n.evidence.forEach { (k, v) -> if (v.isFinite()) put(k, v) } }) })
        })
        return o
    }

    private fun dec(o: JSONObject): TradeManager.Record {
        val t = TradeManager.ManagedTrade(o.getString("sid"), o.getString("tid"), o.getString("label"), o.getString("u"), o.getInt("side"),
            o.getString("inst"), o.getDouble("entry"), o.getInt("qty"), dn(o, "os"), dn(o, "ot"),
            TradeManager.Caps(o.getLong("sq"), o.optInt("maxx", TradeManager.MAX_EXTENSIONS), o.optDouble("tick", 0.05)),
            TradeManager.Account.valueOf(o.getString("acct")), o.getLong("ems"), o.optDouble("chg", 0.0), dn(o, "su"), dn(o, "eu"))
        val notes = o.optJSONArray("notes")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { n ->
            val ev = n.optJSONObject("ev")?.let { e -> e.keys().asSequence().associateWith { k -> e.getDouble(k) } }.orEmpty()
            TradeManager.Note(n.getLong("at"), n.getString("k"), n.getString("r"), n.getString("w"), dn(n, "px"), n.getBoolean("a"), ev, dn(n, "tg"), dn(n, "lk"))
        } } }.orEmpty()
        return TradeManager.Record(t, TradeManager.Mode.valueOf(o.getString("mode")), notes, dn(o, "tg"), dn(o, "lk"), o.optInt("ext", 0),
            decExit(o.optJSONObject("actual")), decExit(o.optJSONObject("mgr")), decExit(o.optJSONObject("orig")),
            if (o.has("acted")) o.getLong("acted") else null)
    }

    /** TEST ONLY: nothing followed, recorded or set (in memory). */
    internal fun resetForTest() {
        live.clear(); synchronized(recs) { recs.clear() }; publish()
        _policy.value = TradeManager.Policy(); policyLoaded = true; loaded = true
        lastEval = 0L; minuteSettled = -1L
        if (com.optionslab.app.BuildConfig.DEBUG) { testSnapshot = null; testBars = null; testNoWake = false }
    }
}
