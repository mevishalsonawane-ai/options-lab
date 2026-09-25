package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Kite
import com.optionslab.engine.Upstox
import com.optionslab.engine.strategy.Action
import com.optionslab.engine.strategy.Event
import com.optionslab.engine.strategy.Instrument
import com.optionslab.engine.strategy.MasterContract
import com.optionslab.engine.strategy.Quote
import com.optionslab.engine.strategy.RunMode
import com.optionslab.engine.strategy.RunState
import com.optionslab.engine.strategy.RunStateCodec
import com.optionslab.engine.strategy.RunStatus
import com.optionslab.engine.strategy.Scheduler
import com.optionslab.engine.strategy.StrategyCodec
import com.optionslab.engine.strategy.StrategyDef
import com.optionslab.engine.strategy.StrategyHost
import com.optionslab.engine.strategy.StrategyRuntime
import com.optionslab.engine.strategy.StrategyValidator
import com.optionslab.engine.strategy.SymbolResolver
import com.optionslab.engine.strategy.check
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * IraAlgo's Strategy Module on the phone: saved basket strategies, their runs,
 * the weekday scheduler, and per-leg and basket risk (stop, target, trail,
 * lock profit, daily loss limit) evaluated on every price poll.
 *
 * The decisions are the ported engine's (engine/strategy); this object only
 * stores, prices and executes. SANDBOX runs trade the paper account. LIVE runs
 * trade Zerodha, and keep to the app's rules:
 *
 *  - a live START is always yours: from the app with your PIN or fingerprint;
 *    a scheduled live start only notifies you to confirm it;
 *  - the exits a running strategy makes (stops, targets, square-off) go out on
 *    their own - a stop that waits for a tap is not a stop - and only ever
 *    reduce what the run holds;
 *  - every order still passes the Kite gates, and nothing is sent when real
 *    orders are off or the device looks compromised.
 *
 * Everything is kept in one encrypted vault file.
 */
object Strategies {
    private lateinit var file: File
    private lateinit var app: Context
    private val lock = Mutex()
    private val host = StrategyHost()

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, "strategies.vault")
    }

    data class LogLine(val at: Long, val strategy: String, val kind: String, val message: String, val severity: String)

    data class Entry(val def: StrategyDef, val run: RunState?) {
        val running: Boolean get() = run != null && run.stoppedAt == null && run.startedAt != null &&
            run.status in setOf(RunStatus.ENTERING, RunStatus.ACTIVE, RunStatus.EXITING)
    }

    private class Book(
        val defs: MutableList<StrategyDef>,
        val runs: MutableMap<Long, RunState>,          // latest run per strategy id
        val history: MutableList<RunState>,             // finished runs, for the session's banked P&L
        val brokerIds: MutableMap<Long, MutableMap<Long, String>>,   // run id -> runtime order id -> venue id
        val log: MutableList<LogLine>,
        var nextRunId: Long,
        var nextStrategyId: Long,
        var lastCheck: Long?,
    )

    private var cache: Book? = null

    private fun book(): Book {
        cache?.let { return it }
        val b = runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching null, Charsets.UTF_8))
            val defs = o.getJSONArray("defs").let { a -> (0 until a.length()).map { StrategyCodec.decode(a.getString(it)) } }.toMutableList()
            val runs = HashMap<Long, RunState>()
            o.getJSONObject("runs").let { r -> r.keys().forEach { k -> runs[k.toLong()] = RunStateCodec.decode(r.getString(k)) } }
            val history = o.getJSONArray("history").let { a -> (0 until a.length()).map { RunStateCodec.decode(a.getString(it)) } }.toMutableList()
            val ids = HashMap<Long, MutableMap<Long, String>>()
            o.getJSONObject("brokerIds").let { r ->
                r.keys().forEach { k -> val m = r.getJSONObject(k); ids[k.toLong()] = m.keys().asSequence().associate { it.toLong() to m.getString(it) }.toMutableMap() }
            }
            val log = o.getJSONArray("log").let { a ->
                (0 until a.length()).map { a.getJSONArray(it) }.map { LogLine(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
            }.toMutableList()
            Book(defs, runs, history, ids, log, o.getLong("nextRunId"), o.getLong("nextStrategyId"), o.optLong("lastCheck", 0).takeIf { it > 0 })
        }.getOrNull()
        if (b == null && file.exists()) {
            Vault.setAside(file)
            Notifier.post(app, 2014, Notifier.RISK, "Strategies could not be read",
                "The saved strategies and runs were set aside. If a live run was open, check your Zerodha positions now.", "strategy")
        }
        return (b ?: Book(ArrayList(), HashMap(), ArrayList(), HashMap(), ArrayList(), 1, 1, null)).also { cache = it }
    }

    private fun save(b: Book) {
        val o = JSONObject()
        o.put("defs", JSONArray().apply { b.defs.forEach { put(StrategyCodec.encode(it)) } })
        o.put("runs", JSONObject().apply { b.runs.forEach { (k, v) -> put(k.toString(), RunStateCodec.encode(v)) } })
        o.put("history", JSONArray().apply { b.history.takeLast(60).forEach { put(RunStateCodec.encode(it)) } })
        o.put("brokerIds", JSONObject().apply { b.brokerIds.forEach { (k, m) -> put(k.toString(), JSONObject().apply { m.forEach { (i, v) -> put(i.toString(), v) } }) } })
        o.put("log", JSONArray().apply { b.log.takeLast(300).forEach { put(JSONArray().put(it.at).put(it.strategy).put(it.kind).put(it.message).put(it.severity)) } })
        o.put("nextRunId", b.nextRunId).put("nextStrategyId", b.nextStrategyId)
        b.lastCheck?.let { o.put("lastCheck", it) }
        Vault.writeFile(file, o.toString().toByteArray(Charsets.UTF_8))
        cache = b
    }

    // ---- reading ---------------------------------------------------------------------

    suspend fun all(): List<Entry> = lock.withLock { book().let { b -> b.defs.map { Entry(it, b.runs[it.id]) } } }

    suspend fun log(): List<LogLine> = lock.withLock { book().log.reversed() }

    // ---- editing ---------------------------------------------------------------------

    /** Validate as IraAlgo does and save; returns the error, or null. A running strategy cannot be edited. */
    suspend fun save(def: StrategyDef): String? = lock.withLock {
        val b = book()
        if (def.id != 0L && b.runs[def.id]?.let { Entry(def, it).running } == true) return@withLock "Stop the strategy before editing it."
        when (val r = StrategyValidator.check(def)) {
            is StrategyValidator.Result.Invalid -> r.message
            is StrategyValidator.Result.Ok -> {
                val id = if (def.id == 0L) b.nextStrategyId++ else def.id
                val saved = r.def.copy(id = id)
                val i = b.defs.indexOfFirst { it.id == id }
                if (i >= 0) b.defs[i] = saved else b.defs += saved
                save(b); null
            }
        }
    }

    /**
     * Arm or disarm: an armed strategy starts itself at its entry time on its
     * weekdays (the scheduler), in [mode]. A live arm still only notifies you at
     * start time to confirm with your PIN or fingerprint; nothing live starts unseen.
     */
    suspend fun setArmed(id: Long, on: Boolean, mode: RunMode): String? = lock.withLock {
        val b = book()
        val i = b.defs.indexOfFirst { it.id == id }
        if (i < 0) return@withLock "That strategy no longer exists."
        val d = b.defs[i]
        if (on && mode == RunMode.LIVE && !d.liveEnabled) return@withLock "Enable live trading for ${d.name} (Trade → Strategies) before arming it live."
        val base = d.scheduler ?: com.optionslab.engine.strategy.SchedulerConfig(
            days = listOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.WEDNESDAY,
                java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY),
            startTime = d.entryTime, autoStopTime = d.exitTime)
        if (on && base.startTime == null) return@withLock "${d.name} has no start time. Set its entry time in Trade → Strategies, then arm it."
        b.defs[i] = d.copy(scheduler = base.copy(enabled = on, defaultMode = if (on) mode else base.defaultMode))
        save(b); null
    }

    /**
     * Import strategies exported from the desktop app: one definition, a list,
     * or the desktop API's {"data": ...} reply. They arrive new, disarmed and
     * paper-only; a name already here is skipped rather than duplicated.
     */
    suspend fun importJson(text: String): String {
        val root = runCatching { org.json.JSONTokener(text.trim()).nextValue() }.getOrNull() ?: return "That is not JSON exported from the desktop app."
        val items = ArrayList<JSONObject>()
        fun collect(v: Any?) {
            when (v) {
                is JSONArray -> for (k in 0 until v.length()) collect(v.opt(k))
                is JSONObject -> if (v.has("legs")) items += v else if (v.has("data")) collect(v.opt("data"))
            }
        }
        collect(root)
        if (items.isEmpty()) return "No strategy definitions found in that text."
        val have = all().map { it.def.name.lowercase() }.toMutableSet()
        val done = ArrayList<String>(); val skipped = ArrayList<String>(); val failed = ArrayList<String>()
        for (o in items) {
            val name = o.optString("name", "strategy")
            if (name.lowercase() in have) { skipped += name; continue }
            val def = runCatching { StrategyCodec.decode(o.toString()) }.getOrElse { failed += "$name (${it.message})"; null } ?: continue
            val err = save(def.copy(id = 0, liveEnabled = false, scheduler = def.scheduler?.copy(enabled = false)))
            if (err == null) { done += name; have += name.lowercase() } else failed += "$name ($err)"
        }
        return buildString {
            append(if (done.isEmpty()) "Nothing imported." else "Imported ${done.joinToString()} (disarmed, paper only).")
            if (skipped.isNotEmpty()) append(" Already here: ${skipped.joinToString()}.")
            if (failed.isNotEmpty()) append(" Could not import: ${failed.joinToString()}.")
        }
    }

    suspend fun delete(id: Long): String? = lock.withLock {
        val b = book()
        if (b.runs[id]?.let { r -> b.defs.firstOrNull { it.id == id }?.let { Entry(it, r).running } } == true) return@withLock "Stop it first."
        b.defs.removeAll { it.id == id }; b.runs.remove(id)
        save(b); null
    }

    suspend fun setLiveEnabled(id: Long, on: Boolean) = lock.withLock {
        val b = book()
        val i = b.defs.indexOfFirst { it.id == id }
        if (i >= 0) { b.defs[i] = b.defs[i].copy(liveEnabled = on); save(b) }
    }

    // ---- the venue: master contract, prices, orders -------------------------------------

    private val DASHED = DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH)

    /** Where an IraAlgo symbol trades: its Kite trading symbol (LIVE) or Upstox contract (SANDBOX). */
    private class Ref(val lot: Int, val tick: Double, val kite: String?, val upstox: Upstox.Contract?)

    private class Venue(val master: MasterContract, val refs: Map<String, Ref>)

    private suspend fun venue(mode: RunMode): Venue {
        val refs = HashMap<String, Ref>()
        val list = ArrayList<Instrument>()
        if (mode == RunMode.LIVE) {
            for (i in Broker.instruments()) {
                val sym = i.name + i.expiry.format(DASHED).replace("-", "").uppercase(Locale.ENGLISH) + com.optionslab.engine.fmtG(i.strike) + i.right.name
                list += Instrument(sym, "NFO", i.name, i.expiry.format(DASHED).uppercase(Locale.ENGLISH), i.strike, i.lotSize, i.right.name, i.tickSize)
                refs[sym] = Ref(i.lotSize, i.tickSize, i.tradingSymbol, null)
            }
        } else {
            for (c in Market.contracts()) {
                val sym = Paper.symbolOf(c)
                list += Instrument(sym, "NFO", c.underlying, c.expiry.format(DASHED).uppercase(Locale.ENGLISH), c.strike, c.lotSize, c.right.name, 0.05)
                refs[sym] = Ref(c.lotSize, 0.05, null, c)
            }
        }
        return Venue(MasterContract(list), refs)
    }

    private suspend fun quotes(run: RunState, v: Venue): List<Quote> {
        val want = run.subscribedSymbols().toList()
        if (want.isEmpty()) return emptyList()
        return if (run.mode == RunMode.LIVE) {
            val keys = want.mapNotNull { (s, _) -> v.refs[s]?.kite?.let { s to "NFO:$it" } }
            val q = runCatching { Broker.quotes(keys.map { it.second }) }.getOrDefault(emptyMap())
            keys.mapNotNull { (s, k) -> q[k]?.last?.takeIf { it > 0 }?.let { Quote(s, "NFO", it) } }
        } else {
            want.mapNotNull { (s, ex) ->
                val c = v.refs[s]?.upstox ?: return@mapNotNull null
                runCatching { Net.intraday(c.instrumentKey).filter { it.istDate == Market.today() }.lastOrNull()?.close }.getOrNull()
                    ?.let { Quote(s, ex, it) }
            }
        }
    }

    private fun record(b: Book, name: String, e: Event, alert: Boolean) {
        b.log += LogLine(System.currentTimeMillis(), name, e.kind, e.message, e.severity)
        if (alert || e.severity == "warn") Notifier.post(app, 6000 + (name.hashCode() and 0x1ff), if (alert) Notifier.RISK else Notifier.LIVE,
            "$name: ${e.kind.replace('_', ' ')}", e.message, "strategy")
    }

    private fun paperExec(b: Book, def: StrategyDef, v: Venue) = object : StrategyHost.Executor {
        override fun place(order: Action.PlaceOrder): StrategyHost.Placed {
            val ref = v.refs[order.symbol] ?: return StrategyHost.Placed.Refused("${order.symbol} is not listed")
            val c = ref.upstox ?: return StrategyHost.Placed.Refused("no price feed for ${order.symbol}")
            val pc = Paper.Contract(order.symbol, c.underlying, c.expiry, c.strike, c.right, c.lotSize, c.instrumentKey)
            val r = runBlocking { Paper.place(pc, order.side.wire, order.quantity / ref.lot, "MARKET", order.product, null, null) }
            if (!r.ok) return StrategyHost.Placed.Refused(r.message)
            val id = r.orderId ?: return StrategyHost.Placed.Refused("paper order not recorded")
            val fill = r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().firstOrNull()
            return if (fill != null) StrategyHost.Placed.Accepted("paper:$id", "complete", fill.quantity, fill.price)
            else StrategyHost.Placed.Accepted("paper:$id", "open", 0, null)
        }
        override fun cancel(brokerId: String) = runBlocking { Paper.cancel(brokerId.removePrefix("paper:")).ok }
        override fun status(brokerId: String): StrategyHost.Status? =
            Paper.state.orders.firstOrNull { it.orderId == brokerId.removePrefix("paper:") }?.let {
                StrategyHost.Status(it.status, it.filledQuantity, it.averagePrice?.toDouble(), it.rejectionReason)
            }
        override fun event(e: Event, alert: Boolean) = record(b, def.name, e, alert)
    }

    private fun kiteExec(b: Book, def: StrategyDef, v: Venue, compromised: Boolean, known: () -> Collection<String>) = object : StrategyHost.Executor {
        override fun place(order: Action.PlaceOrder): StrategyHost.Placed {
            val s = AppSettings.load()
            if (!s.live || !s.allowRealOrders) return StrategyHost.Placed.Refused("real orders are off (More → Zerodha)")
            if (compromised) return StrategyHost.Placed.Refused("this device shows signs of compromise")
            if (!Broker.loggedIn) return StrategyHost.Placed.Refused("not logged in to Zerodha today")
            val ref = v.refs[order.symbol] ?: return StrategyHost.Placed.Refused("${order.symbol} is not listed on Zerodha")
            val kiteSym = ref.kite ?: return StrategyHost.Placed.Refused("${order.symbol} has no Zerodha symbol")
            val side = if (order.side.wire == "BUY") Kite.Side.BUY else Kite.Side.SELL
            val exit = order.kind != "entry"
            return runBlocking {
                // An automatic exit sends no PIN prompt, so it must only ever close what Zerodha
                // says is held: a leg closed by hand in the Kite app must not be "exited" into a new position.
                if (exit) {
                    val net = runCatching { Broker.positionBook().net }.getOrNull()
                        ?: return@runBlocking StrategyHost.Placed.Refused("could not read Zerodha positions to confirm the exit; will retry")
                    val held = net.filter { it.symbol == kiteSym && it.exchange == "NFO" && it.product == order.product }.sumOf { it.qty }
                    val closes = (side == Kite.Side.BUY && held < 0) || (side == Kite.Side.SELL && held > 0)
                    if (!closes || kotlin.math.abs(held) < order.quantity) return@runBlocking StrategyHost.Placed.Refused(
                        "Zerodha shows $kiteSym ${order.product} net $held, so a ${side.name} of ${order.quantity} would not simply close it; not sent")
                }
                val last = runCatching { Broker.quotes(listOf("NFO:$kiteSym"))["NFO:$kiteSym"]?.last }.getOrNull()
                val o = Kite.Order(kiteSym, side, order.quantity, ref.lot, order.product, "MARKET", null, ref.tick, "NFO", "iraalgostrat")
                val why = Kite.refusals(o, s.limits(), Broker.sentToday(), false, exit = exit, refPrice = last)
                if (why.isNotEmpty()) return@runBlocking StrategyHost.Placed.Refused(why.joinToString("; "))
                val id = try {
                    Broker.placeOrder(o)
                } catch (e: Broker.KiteError) {
                    return@runBlocking StrategyHost.Placed.Refused(e.message ?: "Zerodha refused the order")
                } catch (e: Broker.NotLoggedIn) {
                    return@runBlocking StrategyHost.Placed.Refused(e.message ?: "not logged in")
                } catch (e: Exception) {
                    // The answer was lost, not necessarily the order: look before calling it unsent.
                    runCatching { Broker.findRecent(o, known().map { it.removePrefix("kite:") }) }.getOrNull()
                        ?: return@runBlocking StrategyHost.Placed.Refused("${e.message}; no matching order found at Zerodha")
                }
                // From here the order exists at Zerodha: never report it as refused. An unknown
                // state is polled on the next tick.
                val f = runCatching { Broker.awaitOrder(id, 12_000) }.getOrNull()
                StrategyHost.Placed.Accepted("kite:$id", f?.status ?: "UNKNOWN", f?.filled ?: 0, f?.avgPrice?.takeIf { it > 0 }, f?.message)
            }
        }
        override fun cancel(brokerId: String) = runBlocking { runCatching { Broker.cancel(brokerId.removePrefix("kite:")) }.isSuccess }
        override fun status(brokerId: String): StrategyHost.Status? = runBlocking {
            runCatching { Broker.orderState(brokerId.removePrefix("kite:")) }.getOrNull()?.let {
                StrategyHost.Status(it.status, it.filled, it.avgPrice.takeIf { p -> p > 0 }, it.message)
            }
        }
        override fun event(e: Event, alert: Boolean) = record(b, def.name, e, alert)
    }

    private fun exec(b: Book, def: StrategyDef, v: Venue, mode: RunMode, compromised: Boolean) =
        if (mode == RunMode.LIVE) kiteExec(b, def, v, compromised) { b.brokerIds.values.flatMap { it.values } } else paperExec(b, def, v)

    /** Persist the run after every accepted order, before the next one goes out. */
    private fun checkpoint(b: Book, strategyId: Long): (RunState) -> Unit = { r -> b.runs[strategyId] = r; runCatching { save(b) } }

    /** Last time each strategy warned that it could not be evaluated (in memory; one warning per 10 minutes). */
    private val blindWarned = HashMap<Long, Long>()

    private fun warnBlind(b: Book, def: StrategyDef, run: RunState, why: String) {
        val now = System.currentTimeMillis()
        if (now - (blindWarned[def.id] ?: 0L) < 600_000) return
        blindWarned[def.id] = now
        record(b, def.name, Event("run_unmanaged", "${if (run.mode == RunMode.LIVE) "LIVE" else "Paper"} run not evaluated: $why. Stops and targets are NOT being checked.", "critical"), true)
    }

    // ---- running -------------------------------------------------------------------------

    private fun finish(b: Book, run: RunState) {
        if (run.stoppedAt != null && b.history.none { it.runId == run.runId }) b.history += run
    }

    /**
     * Start a run. LIVE only from the app after you have confirmed it (the UI
     * asks for your PIN or fingerprint first) - [confirmedByOwner] records that.
     */
    suspend fun start(id: Long, mode: RunMode, trigger: String, confirmedByOwner: Boolean, compromised: Boolean): String = lock.withLock {
        val b = book()
        val def = b.defs.firstOrNull { it.id == id } ?: return@withLock "No such strategy."
        if (b.runs[id]?.let { Entry(def, it).running } == true) return@withLock "${def.name} is already running."
        if (mode == RunMode.LIVE) {
            if (!confirmedByOwner) return@withLock "A live start needs your confirmation in the app."
            if (!def.liveEnabled) return@withLock "Enable live trading for ${def.name} first."
        }
        val now = Market.now()
        val v = try { venue(mode) } catch (e: Exception) { return@withLock "Could not load the contracts: ${e.message}" }
        val ltp = runCatching { Market.quote(def.underlying)?.last }.getOrNull()
        val resolved = SymbolResolver.resolve(def, v.master, ltp, now)
        val runId = b.nextRunId++
        val ids = HashMap<Long, String>()
        b.brokerIds[runId] = ids
        val run = host.start(def, resolved, now, runId, mode, trigger, exec(b, def, v, mode, compromised), ids, checkpoint(b, id))
        b.runs[id] = run
        finish(b, run)
        save(b)
        run.startError ?: "${def.name} started (${mode.wire}): ${run.openLegs().size} of ${def.legs.size} legs open."
    }

    suspend fun stop(id: Long, reason: String, compromised: Boolean): String = lock.withLock {
        val b = book()
        val def = b.defs.firstOrNull { it.id == id } ?: return@withLock "No such strategy."
        val run = b.runs[id] ?: return@withLock "${def.name} is not running."
        val v = try { venue(run.mode) } catch (e: Exception) { return@withLock "Could not load the contracts: ${e.message}" }
        val ids = b.brokerIds.getOrPut(run.runId) { HashMap() }
        val next = host.stop(run, def, Market.now(), reason, exec(b, def, v, run.mode, compromised), ids, checkpoint(b, id))
        b.runs[id] = next; finish(b, next); save(b)
        if (next.stoppedAt != null) "${def.name} stopped." else "Exit orders sent for ${def.name}; it closes when they fill."
    }

    suspend fun closeLeg(id: Long, legId: Int, compromised: Boolean): String = lock.withLock {
        val b = book()
        val def = b.defs.firstOrNull { it.id == id } ?: return@withLock "No such strategy."
        val run = b.runs[id] ?: return@withLock "Not running."
        val v = try { venue(run.mode) } catch (e: Exception) { return@withLock "Could not load the contracts: ${e.message}" }
        val next = host.closeLeg(run, def, legId, Market.now(), exec(b, def, v, run.mode, compromised), b.brokerIds.getOrPut(run.runId) { HashMap() }, checkpoint(b, id))
        b.runs[id] = next; finish(b, next); save(b)
        "Leg $legId exit sent."
    }

    /**
     * One pass for every strategy: scheduled starts and stops that are due,
     * then prices, risk and exits for every running run. Called by the live
     * watch each minute and by the Strategy page while it is open.
     */
    suspend fun tickAll(compromised: Boolean): List<String> = lock.withLock {
        val b = book()
        val now = Market.now()
        val notes = ArrayList<String>()
        val last = b.lastCheck?.let { ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), now.zone) }
        b.lastCheck = now.toInstant().toEpochMilli()
        val venues = HashMap<RunMode, Venue?>()
        suspend fun venueFor(m: RunMode) = venues.getOrPut(m) { runCatching { venue(m) }.getOrNull() }

        for (def in b.defs.toList()) {
            val cur = b.runs[def.id]
            val running = cur?.let { Entry(def, it).running } == true
            // The watch polls about once a minute and a tick can itself take a while, so slots are
            // caught up to five minutes late rather than IraAlgo's 60 s.
            for (due in Scheduler.due(def, last, now, { !Market.isTradingDay(it) }, java.time.Duration.ofMinutes(5))) {
                if (due.job.kind == Scheduler.JobKind.START) {
                    when (val d = Scheduler.startDecision(def, running)) {
                        is Scheduler.StartDecision.Start -> if (d.mode == RunMode.LIVE) {
                            Notifier.post(app, 6600 + (def.id.toInt() and 0xff), Notifier.SCHEDULE, "${def.name}: scheduled live start",
                                "Open IraAlgo to review and confirm it; live entries are never sent without you.", "strategy")
                            notes += "${def.name}: live start waiting for you"
                        } else {
                            val v = venueFor(RunMode.SANDBOX)
                            if (v == null) { record(b, def.name, Event("start_refused", "Scheduled paper start skipped: the contract list could not be loaded", "warn"), false); continue }
                            val runId = b.nextRunId++
                            val ids = HashMap<Long, String>()
                            b.brokerIds[runId] = ids
                            val ltp = runCatching { Market.quote(def.underlying)?.last }.getOrNull()
                            val run = host.start(def, SymbolResolver.resolve(def, v.master, ltp, now), now, runId, RunMode.SANDBOX, "scheduler",
                                exec(b, def, v, RunMode.SANDBOX, compromised), ids, checkpoint(b, def.id))
                            b.runs[def.id] = run; finish(b, run)
                            notes += "${def.name}: scheduled paper start"
                        }
                        is Scheduler.StartDecision.Refuse -> record(b, def.name, Event(d.eventKind, d.message, "warn"), false)
                        is Scheduler.StartDecision.Skip -> Unit
                    }
                } else if (running) {
                    val run = b.runs[def.id]!!
                    val v = venueFor(run.mode)
                    if (v == null) { warnBlind(b, def, run, "the scheduled square-off could not load the contract list"); continue }
                    val next = host.stop(run, def, now, "scheduler", exec(b, def, v, run.mode, compromised), b.brokerIds.getOrPut(run.runId) { HashMap() }, checkpoint(b, def.id))
                    b.runs[def.id] = next; finish(b, next)
                }
            }
            val run = b.runs[def.id] ?: continue
            if (!Entry(def, run).running) continue
            if (run.mode == RunMode.LIVE && !Broker.loggedIn) warnBlind(b, def, run, "the Zerodha session has ended (log in again)")
            val v = venueFor(run.mode)
            if (v == null) { warnBlind(b, def, run, "the contract list could not be loaded"); continue }
            val q = quotes(run, v)
            if (q.isEmpty() && run.subscribedSymbols().isNotEmpty() && Market.isOpen()) warnBlind(b, def, run, "no prices could be read")
            val banked = StrategyRuntime.sessionBankedPnl(b.history, def.id, run.runId, now)
            val next = host.tick(run, def, q, now, banked, exec(b, def, v, run.mode, compromised), b.brokerIds.getOrPut(run.runId) { HashMap() }, checkpoint(b, def.id))
            b.runs[def.id] = next; finish(b, next)
        }
        save(b)
        notes
    }

    /** True when any run is live or entering, so the watch keeps polling. */
    suspend fun anyRunning(): Boolean = all().any { it.running }

    fun wipe() { cache = null; file.delete() }

    @Suppress("unused") private fun today(): LocalDate = Market.today()
}
