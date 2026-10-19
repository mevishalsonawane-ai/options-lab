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
        /** Venue order id ("paper:…", "kite:…") -> "strategy name · purpose", so books can say who placed it. */
        val owners: LinkedHashMap<String, String> = LinkedHashMap(),
        /** Strategy id -> true when an armed start places its entry by itself, false when it waits for approval. */
        val autoApprove: LinkedHashMap<Long, Boolean> = LinkedHashMap(),
        /** Scheduled starts waiting for the owner's approval: strategy id -> "<mode>|<date>". */
        val pending: LinkedHashMap<Long, String> = LinkedHashMap(),
        /** The day the owner stopped the bot: no armed strategy starts for the rest of it. */
        var stoppedDay: String? = null,
        /** Why the day was stopped ([com.optionslab.ira.DayStop.Why.wire]): by Boss, the daily loss limit or the tile. */
        var stoppedWhy: String? = null,
        /**
         * 18 Oct: each live run's leg stop resting at Zerodha as a real SL order ([restLegStops]): "runId:legId" -> "kiteId|trigger".
         * Taken out (and confirmed out) before any exit of that leg is sent, so the two can never both fill.
         */
        val legStops: LinkedHashMap<String, String> = LinkedHashMap(),
    )

    private var cache: Book? = null

    /**
     * Whether any run is open, as of the last load or save: read without the lock (the main thread must never
     * wait for a pass that is placing orders). Kept up to date wherever runs are loaded or saved.
     */
    @Volatile var runningHint: Boolean = false
        private set

    /**
     * The running strategies' open legs as [com.optionslab.ira.AutoSide] reads them (one lean a strategy: its legs' net), as of
     * the last load or save: read without the lock by the other automatic traders ([AutoExposure]).
     */
    @Volatile var exposureHint: List<com.optionslab.ira.AutoSide.Held> = emptyList()
        private set

    /** An open leg's lean: an option by its right and side, a future by its side; 0 when unknown. */
    private fun legLean(symbol: String, buy: Boolean): Int = com.optionslab.ira.AutoSide.rightOf(symbol)?.let { com.optionslab.ira.AutoSide.direction(it, buy) }
        ?: if (symbol.uppercase().endsWith("FUT")) (if (buy) 1 else -1) else 0

    private fun exposureOf(b: Book): List<com.optionslab.ira.AutoSide.Held> = b.defs.mapNotNull { d ->
        val run = b.runs[d.id]?.takeIf { Entry(d, it).running } ?: return@mapNotNull null
        val legs = run.openLegs().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val lean = com.optionslab.ira.AutoSide.net(legs.map { legLean(it.symbol, it.position == "B") })
        val one = legs.singleOrNull()
        val what = when {
            one != null && com.optionslab.ira.AutoSide.rightOf(one.symbol) != null ->
                if (one.position == "B") "a ${if (one.symbol.uppercase().endsWith("CE")) "call" else "put"}" else "a short ${if (one.symbol.uppercase().endsWith("CE")) "call" else "put"}"
            lean > 0 -> "a bullish position"
            lean < 0 -> "a bearish position"
            else -> "a neutral position"
        }
        com.optionslab.ira.AutoSide.Held(d.name, legs.joinToString("+") { it.symbol }, d.underlying.uppercase(), lean, what)
    }

    /** A strategy's planned lean from its legs (options by right and side, futures by side); 0 for a neutral one. */
    private fun planned(def: StrategyDef): Int = com.optionslab.ira.AutoSide.net(def.legs.map { l ->
        when {
            l.optionType != null && l.position != null -> com.optionslab.ira.AutoSide.direction(l.optionType!!.wire, l.position == com.optionslab.engine.strategy.Position.B)
            l.side == com.optionslab.engine.strategy.LegSide.LONG -> 1
            l.side == com.optionslab.engine.strategy.LegSide.SHORT -> -1
            else -> 0
        }
    })

    private fun hint(b: Book) {
        exposureHint = runCatching { exposureOf(b) }.getOrDefault(emptyList())
        runningHint = b.defs.any { d -> b.runs[d.id]?.let { Entry(d, it).running } == true }
        stopHintDay = b.stoppedDay
        stopHintWhy = com.optionslab.ira.DayStop.Why.of(b.stoppedWhy)
    }

    @Volatile private var stopHintDay: String? = null
    @Volatile private var stopHintWhy: com.optionslab.ira.DayStop.Why = com.optionslab.ira.DayStop.Why.BOSS

    /**
     * Why the bot is stopped for today, as of the last load or save (null: it is not), read without the lock - for words on
     * screen ([OrbArms.describe]) that must never wait on a pass placing orders. [stoppedWhy] reads it under the lock.
     */
    fun stopHint(): com.optionslab.ira.DayStop.Why? =
        if (stopHintDay != null && stopHintDay == runCatching { Market.today().toString() }.getOrNull()) stopHintWhy else null

    private fun book(): Book {
        cache?.let { return it }
        // A read that failed (a Keystore hiccup past readFileSteady's retries) is not a destroyed file (18 Oct, review): it is
        // thrown, nothing cached, so the next call reads again - as TradeBook does. Setting the vault aside for it lost an open
        // live run's legs, broker ids and stops for good. Only bytes that fail authentication or do not parse are set aside.
        val bytes = try { Vault.readFileSteady(file) } catch (e: Vault.Tampered) { null } catch (e: Exception) {
            throw java.io.IOException("strategies could not be read now", e)
        }
        val b = runCatching {
            val o = JSONObject(String(bytes ?: return@runCatching null, Charsets.UTF_8))
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
            val owners = LinkedHashMap<String, String>()
            o.optJSONObject("owners")?.let { m -> m.keys().forEach { k -> owners[k] = m.getString(k) } }
            val auto = LinkedHashMap<Long, Boolean>()
            o.optJSONObject("autoApprove")?.let { m -> m.keys().forEach { k -> auto[k.toLong()] = m.getBoolean(k) } }
            val pending = LinkedHashMap<Long, String>()
            o.optJSONObject("pending")?.let { m -> m.keys().forEach { k -> pending[k.toLong()] = m.getString(k) } }
            val legStops = LinkedHashMap<String, String>()
            o.optJSONObject("legStops")?.let { m -> m.keys().forEach { k -> legStops[k] = m.getString(k) } }
            Book(defs, runs, history, ids, log, o.getLong("nextRunId"), o.getLong("nextStrategyId"), o.optLong("lastCheck", 0).takeIf { it > 0 }, owners, auto, pending,
                o.optString("stoppedDay").takeIf { it.isNotBlank() }, o.optString("stoppedWhy").takeIf { it.isNotBlank() }, legStops)
        }.getOrNull()
        if (b == null && file.exists()) {
            Vault.setAside(file)
            Notifier.post(app, 2015, Notifier.APPROVAL, "Strategies could not be read",
                "The saved strategies and runs were set aside. If a live run was open, check your Zerodha positions now.", "strategy")
        }
        val loaded = (b ?: Book(ArrayList(), HashMap(), ArrayList(), HashMap(), ArrayList(), 1, 1, null))
        // An ORB armed before the block existed is disarmed (TODO A4).
        loaded.defs.forEachIndexed { i, d ->
            val sc = d.scheduler
            if (needsBreakoutRules(d) && sc != null && sc.enabled) loaded.defs[i] = d.copy(scheduler = sc.copy(enabled = false))
        }
        return loaded.also { cache = it; hint(it) }
    }

    /** What this process last wrote to the book's file: an unchanged save is not encrypted and synced again ([Vault.LastWrite]). */
    private val written = Vault.LastWrite()

    private fun save(b: Book) {
        val bytes = encode(b)
        // 19 Oct: one order of writes with [saveSoon]: a write it left pending is dropped (this newer book carries it).
        synchronized(writeOrder) { saveSeq++; ownersPending = null; written.write(file, bytes) }
        cache = b
        hint(b)
    }

    /**
     * [save], with the vault write on this book's own writer thread (19 Oct, order speed: [tagOwner] - every arm's entry and exit
     * labels its order here - wrote the whole book, up to 3,000 labels, under [lock] before the arm went on, and the next entry's
     * [stoppedToday] waited for that lock). In memory at once; written next unless a newer [save] wrote first. Never older over newer.
     */
    private fun saveSoon(b: Book) {
        val bytes = encode(b)
        synchronized(writeOrder) { saveSeq++; ownersPending = PendingWrite(saveSeq, bytes) }
        cache = b
        hint(b)
        ownersWriter.kick()
    }

    private class PendingWrite(val seq: Long, val bytes: ByteArray)
    /** Orders the vault writes of [save] and [saveSoon]; guards [saveSeq] and [ownersPending]. */
    private val writeOrder = Any()
    private var saveSeq = 0L
    private var ownersPending: PendingWrite? = null
    private val ownersWriter = com.optionslab.ira.WriteBehind(com.optionslab.ira.WriteBehind.thread("strategies-writer")) { flushSoon() }

    private fun flushSoon() {
        synchronized(writeOrder) {
            val p = ownersPending ?: return
            ownersPending = null
            if (p.seq != saveSeq) return
            try { written.write(file, p.bytes) } catch (e: Exception) {
                if (ownersPending == null) ownersPending = p
                runCatching { Diag.record("strategies", "The strategies' book could not be written (${e.javaClass.simpleName}); it is written again with the next change") }
            }
        }
    }

    /** TEST ONLY: [saveSoon]'s write on the caller's thread (tests that read the file at once). Throws unless BuildConfig.DEBUG. */
    @Volatile internal var writeInlineForTest = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test switch exists only in debug builds" }; field = v; ownersWriter.inline = v }

    /** Every [saveSoon] on disk now (a test), [timeoutMs] at most. Never on the order path. */
    internal fun awaitWrites(timeoutMs: Long = 10_000L): Boolean = ownersWriter.await(timeoutMs).also { flushSoon() }

    private fun encode(b: Book): ByteArray {
        val o = JSONObject()
        o.put("defs", JSONArray().apply { b.defs.forEach { put(StrategyCodec.encode(it)) } })
        o.put("runs", JSONObject().apply { b.runs.forEach { (k, v) -> put(k.toString(), RunStateCodec.encode(v)) } })
        o.put("history", JSONArray().apply { b.history.takeLast(60).forEach { put(RunStateCodec.encode(it)) } })
        o.put("brokerIds", JSONObject().apply { b.brokerIds.forEach { (k, m) -> put(k.toString(), JSONObject().apply { m.forEach { (i, v) -> put(i.toString(), v) } }) } })
        o.put("log", JSONArray().apply { b.log.takeLast(300).forEach { put(JSONArray().put(it.at).put(it.strategy).put(it.kind).put(it.message).put(it.severity)) } })
        o.put("nextRunId", b.nextRunId).put("nextStrategyId", b.nextStrategyId)
        b.lastCheck?.let { o.put("lastCheck", it) }
        while (b.owners.size > 3000) b.owners.remove(b.owners.keys.first())
        o.put("owners", JSONObject().apply { b.owners.forEach { (k, v) -> put(k, v) } })
        o.put("autoApprove", JSONObject().apply { b.autoApprove.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("pending", JSONObject().apply { b.pending.forEach { (k, v) -> put(k.toString(), v) } })
        b.stoppedDay?.let { o.put("stoppedDay", it) }
        b.stoppedWhy?.let { o.put("stoppedWhy", it) }
        o.put("legStops", JSONObject().apply { b.legStops.forEach { (k, v) -> put(k, v) } })
        return o.toString().toByteArray(Charsets.UTF_8)
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
                save(b)
                GitHubSync.changed()            // G2 (13 Oct): the phone backup goes to GitHub that evening (after the MCX close, G4; charging)
                null
            }
        }
    }

    /**
     * Arm or disarm: an armed strategy starts itself at its entry time on its
     * weekdays (the scheduler), in [mode]. A live arm still only notifies you at
     * start time to confirm with your PIN or fingerprint; nothing live starts unseen.
     */
    suspend fun setArmed(id: Long, on: Boolean, mode: RunMode, automatic: Boolean = mode != RunMode.LIVE): String? = lock.withLock {
        val b = book()
        val i = b.defs.indexOfFirst { it.id == id }
        if (i < 0) return@withLock "That strategy no longer exists."
        val d = b.defs[i]
        if (on && needsBreakoutRules(d)) return@withLock BREAKOUT_BLOCK
        // 15 Oct (Boss's cleanup): scheduled strategies hidden - armed again only after their restore (Hidden arms). Starting
        // one by hand (Research → Strategies) is not arming and is unchanged.
        if (on && !HiddenArms.mayArm(com.optionslab.engine.orb.ArmsCleanup.BASKETS))
            return@withLock HiddenArms.refusal("Scheduled strategies")
        if (on && mode == RunMode.LIVE && !d.liveEnabled) return@withLock "Enable live trading for ${d.name} (Research → Strategies) before arming it live."
        val base = d.scheduler ?: com.optionslab.engine.strategy.SchedulerConfig(
            days = listOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.WEDNESDAY,
                java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY),
            startTime = d.entryTime, autoStopTime = d.exitTime)
        if (on && base.startTime == null) return@withLock "${d.name} has no start time. Set its entry time in Research → Strategies, then arm it."
        b.defs[i] = d.copy(scheduler = base.copy(enabled = on, defaultMode = if (on) mode else base.defaultMode))
        if (on) b.autoApprove[id] = automatic else b.pending.remove(id)
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

    /** After a restore: every strategy disarmed and paper only, no automatic approvals, no carried-over runs. */
    suspend fun disarmAll() = lock.withLock {
        val b = book()
        for (i in b.defs.indices) b.defs[i] = b.defs[i].copy(liveEnabled = false, scheduler = b.defs[i].scheduler?.copy(enabled = false))
        b.autoApprove.clear(); b.pending.clear(); b.runs.clear()
        save(b)
    }

    /** Reset paper: paper runs, their history, order names and waiting paper starts go; Zerodha runs are untouched. */
    suspend fun resetPaper() = lock.withLock {
        val b = book()
        b.runs.entries.removeAll { it.value.mode != RunMode.LIVE }
        b.history.removeAll { it.mode != RunMode.LIVE }
        b.owners.keys.removeAll { it.startsWith("paper:") }
        b.pending.entries.removeAll { !it.value.startsWith("${RunMode.LIVE.wire}|") }
        save(b)
    }

    suspend fun delete(id: Long): String? = lock.withLock {
        val b = book()
        if (b.runs[id]?.let { r -> b.defs.firstOrNull { it.id == id }?.let { Entry(it, r).running } } == true) return@withLock "Stop it first."
        b.defs.removeAll { it.id == id }; b.runs.remove(id)
        save(b)
        GitHubSync.changed()
        null
    }

    suspend fun setLiveEnabled(id: Long, on: Boolean) = lock.withLock {
        val b = book()
        val i = b.defs.indexOfFirst { it.id == id }
        if (i >= 0) { b.defs[i] = b.defs[i].copy(liveEnabled = on); save(b) }
    }

    // ---- the venue: master contract, prices, orders -------------------------------------

    private val DASHED = DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH)

    /** Where an IraAlgo symbol trades: its Kite trading symbol (LIVE) or Upstox contract (SANDBOX). */
    /** [exchange]: Zerodha's segment of [kite] (NFO; BFO for SENSEX and BANKEX contracts - review C7, 18 Oct). */
    private class Ref(val lot: Int, val tick: Double, val kite: String?, val upstox: Upstox.Contract?, val exchange: String = "NFO")

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
            // 18 Oct: the run's stops and exits decide on these: a tick only while 2 s fresh, else Zerodha's quote ([Broker.stopQuotes]).
            val q = runCatching { Broker.stopQuotes(keys.map { it.second }) }.getOrDefault(emptyMap())
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

    /** "ORB · entry", "ORB Fresh · exit": the strategy and why the order was sent. */
    private fun ownerLabel(def: StrategyDef, order: Action.PlaceOrder): String =
        "${def.name} · leg ${order.legId} ${order.kind.replace('_', ' ')}"

    /** Who placed each order this phone knows of: venue id ("paper:…", "kite:…") -> strategy label. */
    suspend fun owners(): Map<String, String> = lock.withLock { HashMap(book().owners) }

    /** Label an order placed outside a strategy definition (the ORB arms), for the Orders and Trades lists. */
    suspend fun tagOwner(key: String, label: String) = lock.withLock {
        // The book unreadable just now: the label is skipped, never a throw on the caller's order path (18 Oct, review).
        val b = runCatching { book() }.getOrNull() ?: return@withLock
        b.owners[key] = label; saveSoon(b)
    }

    /**
     * The order flow beside a one-leg strategy's option BUY entry (9 Oct; a basket's legs are never gated one by one: a
     * skipped leg would leave the rest unhedged). Logged; under CONFIRM an entry the flow does not agree with is refused
     * like a guard's refusal - the flow never places, enlarges or reverses anything. Null: not gated.
     */
    private fun flowGate(def: StrategyDef, order: Action.PlaceOrder, live: Boolean): FlowGate.Ticket? {
        if (order.kind != "entry" || order.side.wire != "BUY" || def.legs.size != 1) return null
        val side = when { order.symbol.endsWith("CE") -> 1; order.symbol.endsWith("PE") -> -1; else -> return null }
        val at = runCatching { Market.now().toLocalDateTime().withSecond(0).withNano(0).toString() }.getOrDefault("")
        return FlowGate.check("saved", def.underlying, side, live, at, "${def.id}:${order.legId}")
    }

    private fun paperExec(b: Book, def: StrategyDef, v: Venue) = object : StrategyHost.Executor {
        override fun place(order: Action.PlaceOrder): StrategyHost.Placed {
            val flow = flowGate(def, order, live = false)
            if (flow?.skip == true) return StrategyHost.Placed.Refused("skipped by the order flow (CONFIRM: the flow did not agree)")
            val ref = v.refs[order.symbol] ?: return StrategyHost.Placed.Refused("${order.symbol} is not listed")
            val c = ref.upstox ?: return StrategyHost.Placed.Refused("no price feed for ${order.symbol}")
            val pc = Paper.Contract(order.symbol, c.underlying, c.expiry, c.strike, c.right, c.lotSize, c.instrumentKey)
            // The account-wide guard: entries must pass it; exits stop only at the kill switch.
            val snap = runCatching { runBlocking { Paper.snapshot() } }.getOrNull()
            val gOrder = Guard.paperOrder(pc, order.side.wire, order.quantity / ref.lot, snap?.positions?.positions?.firstOrNull { it.symbol == pc.symbol }?.ltp ?: 0.0)
            Guard.check(gOrder, snap?.let { Guard.paperAccount(it) }, exit = order.kind != "entry", paper = true).takeIf { it.isNotEmpty() }
                ?.let { return StrategyHost.Placed.Refused("account guard: " + it.joinToString(" ")) }
            val r = runBlocking { Paper.place(pc, order.side.wire, order.quantity / ref.lot, "MARKET", order.product, null, null) }
            if (!r.ok) return StrategyHost.Placed.Refused(r.message)
            val id = r.orderId ?: return StrategyHost.Placed.Refused("paper order not recorded")
            b.owners["paper:$id"] = ownerLabel(def, order)
            FlowGate.taken(flow, id)
            val fill = r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().firstOrNull()
            fill?.let { Notifier.orderFilled(app, it.action, it.quantity, it.symbol, it.price, "Paper", ownerLabel(def, order), it.orderId) }
            return if (fill != null) StrategyHost.Placed.Accepted("paper:$id", "complete", fill.quantity, fill.price)
            else StrategyHost.Placed.Accepted("paper:$id", "open", 0, null)
        }
        override fun cancel(brokerId: String) = runBlocking { Paper.cancel(brokerId.removePrefix("paper:"), "strategy").ok }
        override fun status(brokerId: String): StrategyHost.Status? =
            Paper.state.orders.firstOrNull { it.orderId == brokerId.removePrefix("paper:") }?.let {
                StrategyHost.Status(it.status, it.filledQuantity, it.averagePrice?.toDouble(), it.rejectionReason)
            }
        override fun event(e: Event, alert: Boolean) = record(b, def.name, e, alert)
    }

    private fun kiteExec(b: Book, def: StrategyDef, v: Venue, compromised: Boolean, known: () -> Collection<String>) = object : StrategyHost.Executor {
        override fun place(order: Action.PlaceOrder): StrategyHost.Placed {
            val s = AppSettings.load()
            // Paper mode blocks new live entries only: a live run's stops, targets and square-off must still
            // close what it holds at Zerodha (a stop that waits for the badge to say Live is not a stop).
            if (order.kind == "entry" && (!s.live || !s.allowRealOrders)) return StrategyHost.Placed.Refused("the app is in Paper mode (switch to Live with the badge at the top)")
            if (compromised && order.kind == "entry") return StrategyHost.Placed.Refused("this device shows signs of compromise")
            if (!Broker.loggedIn) return StrategyHost.Placed.Refused("not logged in to Zerodha today")
            // The order flow can only refuse this entry (CONFIRM), never add to it; exits are never asked.
            val flow = flowGate(def, order, live = true)
            if (flow?.skip == true) return StrategyHost.Placed.Refused("skipped by the order flow (CONFIRM: the flow did not agree)")
            val ref = v.refs[order.symbol] ?: return StrategyHost.Placed.Refused("${order.symbol} is not listed on Zerodha")
            val kiteSym = ref.kite ?: return StrategyHost.Placed.Refused("${order.symbol} has no Zerodha symbol")
            val side = if (order.side.wire == "BUY") Kite.Side.BUY else Kite.Side.SELL
            val exit = order.kind != "entry"
            return runBlocking {
                // An automatic exit sends no PIN prompt, so it must only ever close what Zerodha
                // says is held: a leg closed by hand in the Kite app must not be "exited" into a new position.
                // It sends what is held less the exits already working on it (anyone's: the expiry square-off, a
                // protection's stop, the owner's own limit), in whole lots, and at most the run's quantity: a leg
                // part-closed by hand still gets the rest closed, and two exits never add up to a new position.
                var qty = order.quantity
                if (exit) {
                    // 18 Oct: the leg's own stop resting at Zerodha ([restLegStops]) comes out FIRST, confirmed out - else both could
                    // fill. One that already filled IS this exit (its fill is booked as this order's).
                    val sk = b.runs[def.id]?.runId?.let { legKey(it, order.legId) }
                    val rest = sk?.let { b.legStops[it] }
                    if (sk != null && rest != null) {
                        // Taken out and confirmed out: an SL order cancelled (one of an earlier session is gone with it), a GTT
                        // deleted (a fired one's own orders cancelled). Its state, and its fill when it filled meanwhile.
                        val out = legStopOut(rest)
                        val st = out?.second
                        val sid = out?.second?.orderId ?: rest.substringBefore('|')
                        when (out?.first) {
                            "COMPLETE" -> {
                                val st = st ?: return@runBlocking StrategyHost.Placed.Refused("the leg's stop at Zerodha could not be read; tried again next look")
                                b.legStops.remove(sk)
                                b.owners["kite:$sid"] = ownerLabel(def, order)
                                Notifier.orderFilled(app, side.name, st.filled, kiteSym, st.avgPrice, "Live", "${def.name} · leg ${order.legId} stop at Zerodha", sid)
                                return@runBlocking StrategyHost.Placed.Accepted("kite:$sid", "COMPLETE", st.filled, st.avgPrice.takeIf { it > 0 }, st.message)
                            }
                            "CANCELLED", "REJECTED" -> {
                                b.legStops.remove(sk)
                                // Part of it filled before it came out: that part is this exit's (the run asks again for the rest).
                                if (st != null && st.filled > 0) {
                                    b.owners["kite:$sid"] = ownerLabel(def, order)
                                    return@runBlocking StrategyHost.Placed.Accepted("kite:$sid", st.status, st.filled, st.avgPrice.takeIf { it > 0 }, st.message)
                                }
                            }
                            else -> return@runBlocking StrategyHost.Placed.Refused("the leg's stop at Zerodha could not be confirmed out yet; tried again next look")
                        }
                    }
                    val net = runCatching { Broker.positionBook().net }.getOrNull()
                        ?: return@runBlocking StrategyHost.Placed.Refused("could not read Zerodha positions to confirm the exit; will retry")
                    val held = net.filter { it.symbol == kiteSym && it.exchange == ref.exchange && it.product == order.product }.sumOf { it.qty }
                    val closes = (side == Kite.Side.BUY && held < 0) || (side == Kite.Side.SELL && held > 0)
                    if (!closes) return@runBlocking StrategyHost.Placed.Refused(
                        "Zerodha shows $kiteSym ${order.product} net $held, so a ${side.name} would not close it; not sent")
                    val working = runCatching { Broker.orders() }.getOrNull()
                        ?: return@runBlocking StrategyHost.Placed.Refused("could not read Zerodha's orders to confirm the exit; will retry")
                    val busy = working.filter { it.working && it.symbol == kiteSym && it.exchange == ref.exchange && it.product == order.product && it.side == side.name }
                        .sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) }
                    qty = com.optionslab.engine.risk.ExitQty.sendable(held, busy, order.quantity, ref.lot)
                    if (qty <= 0) return@runBlocking StrategyHost.Placed.Refused(
                        "Zerodha shows $kiteSym ${order.product} net $held with $busy already on its way out; nothing more sent")
                }
                val last = runCatching { Broker.quotes(listOf("${ref.exchange}:$kiteSym"))["${ref.exchange}:$kiteSym"]?.last }.getOrNull()
                val o = Kite.Order(kiteSym, side, qty, ref.lot, order.product, "MARKET", null, ref.tick, ref.exchange, "iraalgostrat")
                // The account-wide guard, on a fresh read of the account.
                val acct = Broker.accountNow()      // positions, funds and orders read in parallel
                Guard.check(Guard.liveOrder(o).copy(price = last ?: 0.0), acct, exit).takeIf { it.isNotEmpty() }
                    ?.let { return@runBlocking StrategyHost.Placed.Refused("account guard: " + it.joinToString(" ")) }
                val why = Kite.refusals(o, s.limits(), Broker.sentToday(), false, exit = exit, refPrice = last)
                if (why.isNotEmpty()) return@runBlocking StrategyHost.Placed.Refused(why.joinToString("; "))
                val id = try {
                    Broker.placeOrder(o, exit)
                } catch (e: Exception) {
                    if (Broker.definite(e)) return@runBlocking StrategyHost.Placed.Refused(e.message ?: "Zerodha refused the order")
                    // The answer was lost, not necessarily the order: look (the book can trail the POST) before calling it unsent.
                    runCatching { Broker.findRecentRetrying(o, known().map { it.removePrefix("kite:") }) }.getOrNull()
                        ?: return@runBlocking StrategyHost.Placed.Refused("${e.message}; no matching order found at Zerodha")
                }
                b.owners["kite:$id"] = ownerLabel(def, order)
                FlowGate.outcome(flow, "entered_live")
                // From here the order exists at Zerodha: never report it as refused. An unknown
                // state is polled on the next tick.
                val f = runCatching { Broker.awaitOrder(id, 12_000) }.getOrNull()
                if (f != null && f.filled > 0) Notifier.orderFilled(app, side.name, f.filled, kiteSym, f.avgPrice, "Live", ownerLabel(def, order), id)
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
        if (needsBreakoutRules(def)) return@withLock BREAKOUT_BLOCK
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
        b.pending.remove(id)
        finish(b, run)
        save(b)
        run.startError ?: "${def.name} started (${mode.wire}): ${run.openLegs().size} of ${def.legs.size} legs open."
    }

    /** Whether the owner stopped the bot for today. */
    suspend fun stoppedToday(): Boolean = stoppedWhy() != null

    /**
     * Why the bot is stopped for today (by Boss, the daily loss limit, the tile), or null when it is not. The book unreadable
     * just now: the last one read or saved ([stopHint]), never a throw into an arm's exits (18 Oct, review).
     */
    suspend fun stoppedWhy(): com.optionslab.ira.DayStop.Why? = lock.withLock {
        val b = runCatching { book() }.getOrElse { return@withLock stopHint() }
        if (b.stoppedDay == Market.today().toString()) com.optionslab.ira.DayStop.Why.of(b.stoppedWhy) else null
    }

    /** Whether today's stop was made by the daily loss limit (it is then never lifted today). */
    suspend fun stoppedByLossToday(): Boolean =
        stoppedWhy() == com.optionslab.ira.DayStop.Why.LOSS || runCatching { LossBreaker.trippedToday() }.getOrDefault(false)

    /**
     * Stop the bot for the rest of today: no armed strategy starts, waiting approvals are dropped,
     * and with [stopRunning] every running strategy is stopped too (its positions closed by its own exits).
     * [why]: who stopped it (kept and said wherever the stop is described); a stop by the daily loss limit stays one.
     * ORB arms and Pine scripts read the same stop: they make no new entries and sell what they hold.
     */
    suspend fun stopForToday(stopRunning: Boolean, compromised: Boolean, why: com.optionslab.ira.DayStop.Why = com.optionslab.ira.DayStop.Why.BOSS): String {
        val running = lock.withLock {
            val b = book()
            val today = Market.today().toString()
            val wasLoss = b.stoppedDay == today && com.optionslab.ira.DayStop.Why.of(b.stoppedWhy) == com.optionslab.ira.DayStop.Why.LOSS
            b.stoppedWhy = (if (wasLoss) com.optionslab.ira.DayStop.Why.LOSS else why).wire
            b.stoppedDay = today
            b.pending.clear()
            save(b)
            b.defs.filter { d -> b.runs[d.id]?.let { Entry(d, it).running } == true }.map { it.id to it.name }
        }
        if (!stopRunning || running.isEmpty()) return "Bot stopped for today: armed strategies will not start." +
            if (running.isNotEmpty()) " ${running.size} running strategy(ies) keep managing their exits." else ""
        val results = running.map { (id, _) -> runCatching { stop(id, "bot stopped for the day", compromised) }.getOrElse { e -> "${e.message}" } }
        return "Bot stopped for today. " + results.joinToString(" ")
    }

    /**
     * Undo [stopForToday]: armed strategies start at their times again (and the ORB arms and Pine scripts that are armed
     * trade again). Never on a day the daily loss limit stopped: plans only lower risk, so that stop holds until tomorrow.
     */
    suspend fun startAgain(): String {
        val loss = runCatching { LossBreaker.trippedToday() }.getOrDefault(false)
        return lock.withLock {
            val b = book()
            val today = Market.today().toString()
            if (loss || (b.stoppedDay == today && com.optionslab.ira.DayStop.Why.of(b.stoppedWhy) == com.optionslab.ira.DayStop.Why.LOSS)) {
                return@withLock "Not started: the daily loss limit stopped the bot today, so it stays stopped until tomorrow."
            }
            b.stoppedDay = null; b.stoppedWhy = null; save(b)
            "Bot running: armed strategies start at their times."
        }
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
    private suspend fun startRun(b: Book, def: StrategyDef, v: Venue, mode: RunMode, trigger: String, compromised: Boolean, now: ZonedDateTime): RunState {
        val runId = b.nextRunId++
        val ids = HashMap<Long, String>()
        b.brokerIds[runId] = ids
        val ltp = runCatching { Market.quote(def.underlying)?.last }.getOrNull()
        val run = host.start(def, SymbolResolver.resolve(def, v.master, ltp, now), now, runId, mode, trigger, exec(b, def, v, mode, compromised), ids, checkpoint(b, def.id))
        b.runs[def.id] = run
        finish(b, run)
        b.pending.remove(def.id)
        return run
    }

    /**
     * TODO A4: the desktop app's ORB and ORB Fresh wait for an opening-range breakout, but as imported
     * here they are plain timed baskets that would enter at the start time with no breakout check. Until
     * the real ORB rules are ported (TODO A1) they can be kept and viewed, never armed or started.
     */
    fun needsBreakoutRules(def: StrategyDef): Boolean = Regex("(^|[^a-z])orb([^a-z]|$)").containsMatchIn(def.name.lowercase())
    const val BREAKOUT_BLOCK = "Imported ORB strategies are plain timed baskets with no opening-range breakout check: armed, they would enter at the start time whatever the market did. Use the built-in ORB and ORB Fresh arms on Home, which run the real rules."

    /** Whether an armed strategy places its entry by itself (true) or asks first (false). */
    suspend fun automatic(): Map<Long, Boolean> = lock.withLock { HashMap(book().autoApprove) }

    /** Today's scheduled starts waiting for approval: strategy id -> mode. */
    suspend fun pending(): Map<Long, RunMode> = lock.withLock {
        val today = Market.today().toString()
        book().pending.filterValues { it.substringAfter('|') == today }
            .mapValues { (_, v) -> if (v.substringBefore('|') == RunMode.LIVE.wire) RunMode.LIVE else RunMode.SANDBOX }
    }

    /** The owner approved a waiting start (live approval is PIN- or fingerprint-confirmed by the caller). */
    suspend fun approve(id: Long, compromised: Boolean): String {
        val mode = pending()[id] ?: return "Nothing is waiting for approval."
        return start(id, mode, "approved", confirmedByOwner = true, compromised = compromised)
    }

    suspend fun skip(id: Long) = lock.withLock { val b = book(); b.pending.remove(id); save(b) }

    suspend fun tickAll(compromised: Boolean): List<String> = lock.withLock {
        val b = book()
        // Orders placed in this pass are written down even when the pass is cancelled part-way (the watch
        // stopped, a time limit): a run's broker ids and state kept only in memory would be lost with the process.
        try { tickAllLocked(b, compromised) } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { save(b) } }
        }
    }

    private suspend fun tickAllLocked(b: Book, compromised: Boolean): List<String> {
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
                if (due.job.kind == Scheduler.JobKind.START && needsBreakoutRules(def)) {
                    record(b, def.name, Event("start_refused", "Scheduled start refused: ORB needs its breakout rules (not added yet)", "warn"), false)
                } else if (due.job.kind == Scheduler.JobKind.START && HiddenArms.blocks(com.optionslab.engine.orb.ArmsCleanup.BASKETS)) {
                    // 15 Oct (Boss's cleanup): scheduled strategies are hidden; a restore from the Hidden arms list brings them back.
                    record(b, def.name, Event("start_skipped", "Scheduled start skipped: scheduled strategies are hidden (your 15 Oct cleanup)", "info"), false)
                } else if (due.job.kind == Scheduler.JobKind.START && b.stoppedDay == Market.today().toString()) {
                    record(b, def.name, Event("start_skipped", "Scheduled start skipped: the bot is stopped for today", "info"), false)
                } else if (due.job.kind == Scheduler.JobKind.START) {
                    when (val d = Scheduler.startDecision(def, running)) {
                        is Scheduler.StartDecision.Start -> if (b.autoApprove[def.id] ?: (d.mode != RunMode.LIVE)) {
                            // Automatic: the owner chose this when arming (live arming needed the PIN or fingerprint).
                            // One index, one side, for every automatic trader (Boss's 06 Oct rule): a strategy leaning the other
                            // way from - or the same way as - another automatic position on its index does not start.
                            // The account day lock first (08 Oct): the account this run goes to.
                            val side = AutoExposure.check(AutoExposure.Source.STRATEGIES, def.underlying.uppercase(), planned(def), exposureOf(b),
                                account = d.mode == RunMode.LIVE)
                            if (side != null) {
                                record(b, def.name, Event("start_refused", "Scheduled start refused: $side", "warn"), true)
                                notes += "${def.name}: $side"
                                continue
                            }
                            val v = venueFor(d.mode)
                            if (v == null) { record(b, def.name, Event("start_refused", "Scheduled start skipped: the contract list could not be loaded", "warn"), true); continue }
                            val run = startRun(b, def, v, d.mode, "scheduler", compromised, now)
                            notes += "${def.name}: scheduled ${if (d.mode == RunMode.LIVE) "live" else "paper"} start"
                            if (d.mode == RunMode.LIVE) Notifier.post(app, 6600 + (def.id.toInt() and 0xff), Notifier.SCHEDULE, "${def.name} started (live)",
                                run.startError ?: "${run.openLegs().size} of ${def.legs.size} legs open. Stops, targets and square-off run by themselves.", "almanac")
                        } else {
                            // Manual approval: the entry waits for the owner.
                            b.pending[def.id] = "${d.mode.wire}|${now.toLocalDate()}"
                            Notifier.post(app, 6600 + (def.id.toInt() and 0xff), Notifier.APPROVAL, "${def.name}: approve the start",
                                "It is ${def.name}'s start time (${if (d.mode == RunMode.LIVE) "live" else "paper"}). Open IraAlgo to approve or skip; nothing is sent until you do.", "almanac", approve = "strategy")
                            notes += "${def.name}: waiting for approval"
                        }
                        is Scheduler.StartDecision.Refuse -> record(b, def.name, Event(d.eventKind, d.message, "warn"), false)
                        is Scheduler.StartDecision.Skip -> Unit
                    }
                } else if (!running) {
                    b.pending.remove(def.id)     // the day's window closed unapproved
                } else if (running) {
                    val run = b.runs[def.id]!!
                    val v = venueFor(run.mode)
                    if (v == null) { warnBlind(b, def, run, "the scheduled square-off could not load the contract list"); continue }
                    val next = host.stop(run, def, now, "scheduler", exec(b, def, v, run.mode, compromised), b.brokerIds.getOrPut(run.runId) { HashMap() }, checkpoint(b, def.id))
                    b.runs[def.id] = next; finish(b, next)
                }
            }
            tickOpenRun(b, def, now, compromised) { venueFor(it) }
        }
        runCatching { cleanLegStops(b) }
        return notes
    }

    /** One open run's prices, per-leg and basket risk and exits ([host] tick); nothing when [def] has no open run. */
    private suspend fun tickOpenRun(b: Book, def: StrategyDef, now: ZonedDateTime, compromised: Boolean, venueFor: suspend (RunMode) -> Venue?) {
        val run = b.runs[def.id] ?: return
        if (!Entry(def, run).running) return
        if (run.mode == RunMode.LIVE && !Broker.loggedIn) warnBlind(b, def, run, "the Zerodha session has ended (log in again)")
        val v = venueFor(run.mode)
        if (v == null) { warnBlind(b, def, run, "the contract list could not be loaded"); return }
        val q = quotes(run, v)
        if (q.isEmpty() && run.subscribedSymbols().isNotEmpty() && Market.isOpen()) warnBlind(b, def, run, "no prices could be read")
        val banked = StrategyRuntime.sessionBankedPnl(b.history, def.id, run.runId, now)
        val next = host.tick(run, def, q, now, banked, exec(b, def, v, run.mode, compromised), b.brokerIds.getOrPut(run.runId) { HashMap() }, checkpoint(b, def.id))
        b.runs[def.id] = next; finish(b, next)
        if (next.mode == RunMode.LIVE && Broker.loggedIn) runCatching { restLegStops(b, def, v, q, now, compromised) }
    }

    private fun legKey(runId: Long, legId: Int) = "$runId:$legId"

    /** A leg stop resting as a GTT ("gtt:<id>|trigger|day": an NRML leg's, review C7) - its id, else null (an SL order's). */
    private fun gttOf(v: String): Long? = v.substringBefore('|').takeIf { it.startsWith("gtt:") }?.removePrefix("gtt:")?.toLongOrNull()

    private val GTT_GONE = setOf("deleted", "cancelled", "expired", "rejected", "disabled")

    /**
     * The leg's resting stop [rest] taken out and confirmed out: (state, fill) - "COMPLETE" with its fill when it filled meanwhile,
     * "CANCELLED"/"REJECTED" (with any part filled) when out; null when it could not be confirmed out (nothing else may be sent: both
     * could fill). An SL order is cancelled (one of an earlier session is gone with it); a GTT deleted, and a fired GTT's own working
     * orders cancelled.
     */
    private suspend fun legStopOut(rest: String): Pair<String, Broker.Fill?>? {
        val gid = gttOf(rest)
        if (gid != null) {
            var g = runCatching { Broker.gtts().firstOrNull { it.id == gid } }.getOrElse { return null }
            if (g != null && g.status.lowercase() == "active") {
                runCatching { Broker.deleteGtt(gid) }
                g = runCatching { Broker.gtts().firstOrNull { it.id == gid } }.getOrElse { return null }
            }
            if (g == null || g.status.lowercase() in GTT_GONE) return "CANCELLED" to null
            if (g.status.lowercase() != "triggered") return null
            for (oid in g.orderIds) {
                var st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return null
                if (st.status !in setOf("COMPLETE", "CANCELLED", "REJECTED")) { runCatching { Broker.cancel(oid) }; st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return null }
                if (st.status == "COMPLETE" || st.filled > 0) return (if (st.status == "COMPLETE") "COMPLETE" else st.status) to st
                if (st.status !in setOf("CANCELLED", "REJECTED")) return null
            }
            return "CANCELLED" to null
        }
        val sid = rest.substringBefore('|')
        runCatching { Broker.cancel(sid) }
        val st = runCatching { Broker.orderState(sid) }.getOrNull()
        // A stop of an earlier session (a DAY order, gone with it): nothing of it can fill now.
        if (st == null && stopDay(rest)?.isBefore(Market.today()) == true) return "CANCELLED" to null
        return when (st?.status) { "COMPLETE", "CANCELLED", "REJECTED" -> st.status to st; else -> null }
    }

    /** When each leg's "stop not rested" was last told (memory): told at most once in [NOT_RESTED_EVERY_MS] (review C7). */
    private val notRestedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val NOT_RESTED_EVERY_MS = 10 * 60_000L

    /** The day a leg's resting stop ("id|trigger|day") was placed; null for one saved without it. */
    private fun stopDay(v: String): java.time.LocalDate? = v.split('|').getOrNull(2)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }

    /**
     * The resting stops of runs that are no longer running (finished, stopped, or replaced): cancelled, and forgotten once confirmed
     * out (or gone with an earlier session), so none is ever left resting on a flat position. Each pass, logged in only.
     */
    private suspend fun cleanLegStops(b: Book) {
        if (b.legStops.isEmpty() || !Broker.loggedIn) return
        val running = b.defs.mapNotNull { d -> b.runs[d.id]?.takeIf { Entry(d, it).running }?.runId }.toSet()
        for ((k, v) in b.legStops.toMap()) {
            if (k.substringBefore(':').toLongOrNull() in running) continue
            if (legStopOut(v) != null) b.legStops.remove(k)
        }
    }

    /**
     * 18 Oct (Boss: stops at Zerodha, so a dead phone cannot miss them): each open leg of a LIVE run rests its stop at Zerodha as a
     * real SL order with a limit (as the ORB and Pine arms' stops), at the run's own stop for the leg ([com.optionslab.engine.strategy
     * .LegState.effectiveSl]), moved (a modify of that order) when its trail or lock tightens it, never loosened. The leg's targets,
     * trails and basket exits stay the app's: a second resting exit beside the stop could fill too and turn the leg the other way.
     * A stop that filled at Zerodha closes the leg through the run's own exit (which books that fill); a stop left on a leg that is
     * closed, or no longer held at Zerodha, is cancelled. Never on the order or tick path: the 15-second check and the minute pass.
     */
    private suspend fun restLegStops(b: Book, def: StrategyDef, v: Venue, q: List<Quote>, now: ZonedDateTime, compromised: Boolean) {
        val run = b.runs[def.id] ?: return
        val mine = b.legStops.keys.filter { it.startsWith("${run.runId}:") }
        val open = run.legs.values.filter { it.status == "open" && it.entryStatus == "complete" }
        if (open.isEmpty() && mine.isEmpty()) return
        val orders = runCatching { Broker.orders().associateBy { it.id } }.getOrNull() ?: return
        val net = runCatching { Broker.positionBook().net }.getOrNull() ?: return
        val gtts = if (mine.any { b.legStops[it]?.let(::gttOf) != null } || open.any { legProduct(run, def, it) != "MIS" })
            runCatching { Broker.gtts().associateBy { it.id } }.getOrNull() ?: return else emptyMap()
        // Stops of legs that are no longer open (closed by the run, or gone): out.
        for (k in mine) {
            val legId = k.substringAfter(':').toIntOrNull()
            if (open.any { it.legId == legId }) continue
            val v0 = b.legStops[k] ?: continue
            if (legStopOut(v0) != null) b.legStops.remove(k)
        }
        // The exits working at Zerodha, by order id (kept up to date as stops shrink): what each leg's stop may cover.
        val working = HashMap<String, Int>()
        for (o in orders.values) if (o.working) working[o.id] = com.optionslab.engine.risk.ExitQty.remaining(o.qty, o.filled, o.pending)
        // Newest legs first: when Zerodha holds less than the stops cover, the newest stop shrinks first (review C2).
        for (leg in open.sortedByDescending { it.legId }) {
            val k = legKey(run.runId, leg.legId)
            val ref = v.refs[leg.symbol] ?: continue
            val kiteSym = ref.kite ?: continue
            val ex = ref.exchange
            val product = legProduct(run, def, leg)
            val dir = if (leg.position == "B") 1 else -1
            val exitSide = if (dir > 0) Kite.Side.SELL else Kite.Side.BUY
            val held = net.filter { it.symbol == kiteSym && it.exchange == ex && it.product == product }.sumOf { it.qty } * dir
            val cur = b.legStops[k]
            val gid = cur?.let(::gttOf)
            val sid = cur?.substringBefore('|')
            val so = if (gid == null) sid?.let { orders[it] } else null
            val g = gid?.let { gtts[it] }
            // A stop of an earlier session (a DAY order) is gone: rested again today. A GTT gone at Zerodha likewise.
            if (cur != null && gid == null && so == null && stopDay(cur)?.isBefore(Market.today()) == true) { b.legStops.remove(k); continue }
            if (gid != null && (g == null || g.status.lowercase() in GTT_GONE)) { b.legStops.remove(k); continue }
            val gttFilled = g?.status?.lowercase() == "triggered" && g.orderIds.isNotEmpty() && g.orderIds.all { orders[it]?.status == "COMPLETE" }
            if ((so != null && so.status == "COMPLETE") || gttFilled) {
                // The stop filled at Zerodha: the run closes the leg (its exit books that fill; nothing new is sent).
                val bid = b.brokerIds.getOrPut(run.runId) { HashMap() }
                val next = host.closeLeg(run, def, leg.legId, now, exec(b, def, v, run.mode, compromised), bid, checkpoint(b, def.id))
                b.runs[def.id] = next; finish(b, next)
                continue
            }
            if (so != null && so.status in setOf("CANCELLED", "REJECTED")) { b.legStops.remove(k) }
            // Held no more at Zerodha (closed by hand): its stop must not rest on a flat position.
            if (held <= 0) {
                if (cur != null && b.legStops.containsKey(k) && legStopOut(cur) != null) b.legStops.remove(k)
                continue
            }
            // Zerodha holds less than this stop covers beside the other exits working on it (part sold by hand in Kite, or another
            // run's stop on the same option): shrunk to what is left, in whole lots, or taken out - never a stop that sells into a short.
            val gttOrders = g?.orderIds.orEmpty().toSet()
            val others = orders.values.filter { it.working && it.symbol == kiteSym && it.exchange == ex && it.product == product &&
                it.side == exitSide.name && it.id != sid && it.id !in gttOrders }.sumOf { working[it.id] ?: 0 } +
                gtts.values.filter { it.id != gid && it.symbol == kiteSym && it.exchange == ex }.sumOf { it.covers(exitSide.name, product) }
            val room = (held - others).coerceAtLeast(0) / ref.lot.coerceAtLeast(1) * ref.lot.coerceAtLeast(1)
            val covers = when {
                so != null && so.working -> working[so.id] ?: 0
                g != null && g.status.lowercase() == "active" -> stopQty(cur)
                else -> null
            }
            if (cur != null && covers != null && covers > room) {
                if (room <= 0) { if (legStopOut(cur) != null) b.legStops.remove(k); continue }
                val r = runCatching {
                    if (gid != null) {
                        val px = q.firstOrNull { it.symbol == leg.symbol }?.ltp ?: error("no price")
                        val trig = cur.split('|').getOrNull(1)?.toDoubleOrNull() ?: error("no trigger")
                        val (gtt, why) = Kite.protect(Kite.Spec(ex, kiteSym, 0L, ref.lot, ref.tick), product, room * dir, px, trig, null)
                        Broker.modifyGtt(gid, requireNotNull(gtt) { why.joinToString("; ") })
                    } else if (so != null) Broker.modify(so, so.filled + room, so.type, so.price.takeIf { it > 0 }, so.trigger.takeIf { it > 0 })
                }
                if (r.isSuccess) {
                    if (so != null) working[so.id] = room
                    b.legStops[k] = withQty(cur, room)
                    record(b, def.name, Event("stop_shrunk", "Leg ${leg.legId}'s stop at Zerodha shrunk to $room (Zerodha holds less)", "info"), false)
                }
                continue
            }
            val want = leg.effectiveSl?.let { Kite.onTick(it, ref.tick, if (dir > 0) Kite.Side.SELL else Kite.Side.BUY) }?.takeIf { it > ref.tick } ?: continue
            val ltp = q.firstOrNull { it.symbol == leg.symbol }?.ltp ?: continue
            val qty = minOf(leg.entryFilledQty.takeIf { it > 0 } ?: leg.qty, room)
            if (!b.legStops.containsKey(k)) {
                if (!com.optionslab.engine.orb.LiveMirror.mayRest(want, ltp, ref.tick, dir) || leg.exitInFlight || qty <= 0) continue
                val id: String? = if (product != "MIS") {
                    // A carried (NRML) leg: a single-leg GTT, the only broker-side stop that outlives the session (review C7).
                    val (gtt, _) = Kite.protect(Kite.Spec(ex, kiteSym, 0L, ref.lot, ref.tick), product, qty * dir, ltp, want, null)
                    // A lost reply never leaves a second GTT ([Broker.placeGttOnce] adopts the one that went through).
                    gtt?.let { x -> Broker.placeGttOnce(x, b.legStops.values.mapNotNull(::gttOf).toSet())?.let { "gtt:$it" } }
                } else {
                    val o = Kite.Order(kiteSym, exitSide, qty, ref.lot, product, "SL", com.optionslab.engine.orb.LiveMirror.stopLimit(want, ref.tick, dir), ref.tick,
                        ex, "iraalgostrat", triggerPrice = want)
                    try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
                        if (Broker.definite(e)) null else runCatching { Broker.findRecentRetrying(o, b.owners.keys.filter { it.startsWith("kite:") }.map { it.removePrefix("kite:") }) }.getOrNull()
                    }
                }
                if (id == null) {
                    // Told once in ten minutes per leg (each 15-second look tries again).
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - (notRestedAt[k] ?: 0L) >= NOT_RESTED_EVERY_MS) {
                        notRestedAt[k] = nowMs
                        record(b, def.name, Event("stop_not_rested", "Leg ${leg.legId}'s stop at %.2f could not rest at Zerodha; the run watches it itself".format(Locale.ENGLISH, want), "warn"), false)
                    }
                    continue
                }
                notRestedAt.remove(k)
                b.legStops[k] = "$id|$want|${Market.today()}|$qty"
                if (!id.startsWith("gtt:")) { b.owners["kite:$id"] = "${def.name} · leg ${leg.legId} stop"; working[id] = qty }
                record(b, def.name, Event("stop_rested", "Leg ${leg.legId}'s stop rests at Zerodha at %.2f (%s)".format(Locale.ENGLISH, want,
                    if (id.startsWith("gtt:")) "GTT" else "SL order"), "info"), false)
            } else {
                val at = cur?.split('|')?.getOrNull(1)?.toDoubleOrNull()
                val to = com.optionslab.engine.orb.LiveMirror.move(at, want, ltp, ref.tick, dir) ?: continue
                val r = if (gid != null) {
                    if (g?.status?.lowercase() != "active") continue
                    runCatching {
                        val (gtt, why) = Kite.protect(Kite.Spec(ex, kiteSym, 0L, ref.lot, ref.tick), product, (stopQty(cur) ?: qty) * dir, ltp, to, null)
                        Broker.modifyGtt(gid, requireNotNull(gtt) { why.joinToString("; ") })
                    }
                } else {
                    val row = so ?: continue
                    if (!row.working) continue
                    runCatching { Broker.modify(row, row.qty, "SL", com.optionslab.engine.orb.LiveMirror.stopLimit(to, ref.tick, dir), to) }
                }
                if (r.isSuccess) b.legStops[k] = listOf(sid ?: continue, to.toString(), (stopDay(cur ?: "") ?: Market.today()).toString()).joinToString("|") +
                    (stopQty(cur)?.let { "|$it" } ?: "")
            }
        }
    }

    /** A leg's product as its entry was sent (else its strategy's). */
    private fun legProduct(run: RunState, def: StrategyDef, leg: com.optionslab.engine.strategy.LegState): String =
        leg.entryOrderId?.let { run.orders[it]?.product } ?: def.product.wire.uppercase(Locale.ENGLISH)

    /** The quantity a leg stop ("id|trigger|day|qty") covers, when saved. */
    private fun stopQty(v: String?): Int? = v?.split('|')?.getOrNull(3)?.toIntOrNull()

    /** [v] with its covered quantity set to [qty]. */
    private fun withQty(v: String, qty: Int): String = v.split('|').let { p -> (p.take(3) + List(maxOf(0, 3 - p.size)) { "" } + qty.toString()).joinToString("|") }

    /**
     * The 15-second stop check's part (18 Oct, review): only the open runs' prices, per-leg and basket stops, targets,
     * trails and exits, as [tickAll] runs them - no scheduled start, approval or square-off (those stay in [tickAll]'s
     * minute pass, and its record of the last check is left alone, so no scheduler slot is used up here). Without an open
     * run ([runningHint], memory) it reads nothing at all. Before this a basket's own leg stops were checked only once a
     * minute in the full pass, while the 15-second stop check ran every other arm's exits.
     */
    suspend fun manageOnly(compromised: Boolean) {
        if (!runningHint) return
        lock.withLock {
            val b = book()
            try {
                val now = Market.now()
                val venues = HashMap<RunMode, Venue?>()
                for (def in b.defs.toList()) tickOpenRun(b, def, now, compromised) { m -> venues.getOrPut(m) { runCatching { venue(m) }.getOrNull() } }
                runCatching { cleanLegStops(b) }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { save(b) } }
            }
        }
    }

    /** True when any run is live or entering, so the watch keeps polling. */
    suspend fun anyRunning(): Boolean = all().any { it.running }

    fun wipe() {
        synchronized(writeOrder) { ownersPending = null; saveSeq++ }
        cache = null; runningHint = false; exposureHint = emptyList(); stopHintDay = null; file.delete()
    }

    /** TEST ONLY: the book read from its file again on the next call (as after a restart); the file is kept. */
    internal fun forgetCacheForTest() { cache = null }

    @Suppress("unused") private fun today(): LocalDate = Market.today()
}
