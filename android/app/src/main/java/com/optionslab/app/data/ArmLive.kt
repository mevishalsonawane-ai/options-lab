package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.orb.LiveMirror
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * The LIVE path of every paper strategy that had none (18 Oct 2026, Boss: "What ever strategies are present for paper, should be
 * present for live too"; "I want all be present for live too, i will switch it off, if i need it"). The rules are the engine's
 * [LiveMirror]; this object carries them out with the app's existing live machinery - [Broker.placeOrder] (the entry door: the
 * paper-only period, static IP, the kill switch, the daily loss limit, the bot's day stop, the day lock, the portfolio guard), the
 * account guard ([Guard.check]), the order gates ([Kite.refusals]: lots, value, freeze quantity), the SL order with its limit and
 * its moves (as the ORB and Pine arms'), the stop taken out before any exit, and the exit sized to what Zerodha holds less what
 * is already on its way out ([com.optionslab.engine.risk.ExitQty]).
 *
 * How: each arm in [LiveMirror.ARMS] keeps trading its own paper book exactly as before. When its paper buy fills it tells this
 * object ([entered]); the live entry goes ONLY with the app in Live, "AI trades go live" on (only going Live with Boss's PIN turns
 * it on), the arm's own "Trade live" switch on (on by default, Boss's choice; off with one tap), outside the paper-only period and
 * with every guard clear - else nothing goes and the reason is noted. Its exits follow its paper trade: the watch ([watch]) sells
 * the live trade when its paper trade is closed ([closed], or gone from its book once it was seen there), sells the same share
 * when the paper trade sold part, moves the Zerodha stop to the paper trade's stop or lock on the live fill ([level]), and keeps
 * its own backstops (the stop reached with nothing resting, the price through the stop's limit, 15:15 for an intraday NSE trade,
 * Boss's stop for today).
 *
 * At Zerodha, so a dead phone cannot miss them: the stop of an intraday (MIS) trade is a real SL order with a limit, moved (a
 * modify of the same order) by the lock and the ladder, never down; a carried (NRML) trade's stop is a single-leg GTT (an SL order
 * dies with the session), moved the same way. NEVER both, and never a second resting exit beside it: a target or a time exit is
 * the app's (as the ORB arms'), because a resting target beside the resting stop could both fill and turn the long into a short.
 *
 * Its book is its own encrypted file (no backup), so every live trade is recorded and labelled live, per arm, apart from the arm's
 * paper record (the plain-rules record stays separable). Open positions first: the book is read at the start before other work
 * ([PositionsFirst]) and its positions are watched in the fast path's minute lane and the 15-second stop check at full cadence.
 * Nothing here runs on the tick path; every order and read happens on a worker; [entered], [level] and [closed] only put a note in
 * memory and return.
 */
object ArmLive {
    private lateinit var app: Context
    private var file: File? = null
    private val lock = Mutex()
    /** One live exit or watch pass at a time (they read the same books and send the same kinds of orders). */
    private val opLock = Mutex()
    /** One live entry at a time (the entry door is one at a time too); entries never hold the exits' lock. */
    private val entryLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val written = Vault.LastWrite()

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.noBackupFilesDir, FILE)
        // Solo (midday)'s paper book (Jarvis builds only): read by the watch, never at the start (its trades are in the settings vault).
        if (com.optionslab.app.BuildConfig.JARVIS) register(LiveMirror.SOLO) { com.optionslab.app.ira.IraSolo.mirrorLegs() }
    }

    private const val FILE = "arm_live.vault"
    private const val LOG_KEPT = 120
    private const val CLOSED_KEPT = 400
    private const val WHO = "Arms live"

    /**
     * One live trade of a mirrored arm. [key] the arm ([LiveMirror.Spec.key]); [ref] its paper trade ("family:id"); [symbol] and
     * [exchange] Zerodha's; [side] +1 bought, -1 sold; [qty] held now of [entryQty] bought; [lot] Zerodha's order lot (MCX: 1, in
     * lots); [multiplier] units per quantity (MCX's lot; 1 elsewhere); [paperEntry] / [paperQty] its paper trade's fill and quantity
     * (its levels are scaled from them); [stopOrderId] the SL order's id, or "gtt:<id>" for a GTT; [seen] its paper trade was seen
     * open since this fill (only then can its absence close this); [unconfirmed] Zerodha has not said what the entry filled.
     */
    data class Pos(
        val key: String, val ref: String, val symbol: String, val exchange: String, val product: String, val side: Int,
        val qty: Int, val entryQty: Int, val lot: Int, val tick: Double, val multiplier: Int, val entry: Double, val entryTime: LocalDateTime,
        val paperEntry: Double, val paperQty: Int, val expiry: LocalDate?, val entryOrderId: String?,
        val stopOrderId: String? = null, val stopTrigger: Double? = null, val seen: Boolean = false, val unconfirmed: Boolean = false,
        val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null, val charges: Double = 0.0,
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        /** Rupees after charges, once closed. */
        val net: Double? get() = exit?.let { (it - entry) * qty * multiplier.coerceAtLeast(1) * side - charges }
        val label: String get() = LiveMirror.spec(key)?.label ?: key
        val gttId: Long? get() = stopOrderId?.takeIf { it.startsWith(GTT) }?.removePrefix(GTT)?.toLongOrNull()
        val slId: String? get() = stopOrderId?.takeIf { !it.startsWith(GTT) }
    }

    private const val GTT = "gtt:"

    private class Book(
        val positions: MutableList<Pos> = ArrayList(),
        /** Boss's "Trade live" choices; an arm never set is on ([LiveMirror.DEFAULT_ON]). */
        val switches: MutableMap<String, Boolean> = HashMap(),
        val log: ArrayList<String> = ArrayList(),
        /** Each arm's last refused live entry, in words (its row says why nothing went). */
        val refused: MutableMap<String, String> = HashMap(),
    )

    /** What the screen and the tests read: Boss's switches, the open and closed live trades, the log, the last refusals. */
    data class View(
        val switches: Map<String, Boolean> = emptyMap(), val open: List<Pos> = emptyList(), val closed: List<Pos> = emptyList(),
        val log: List<String> = emptyList(), val refused: Map<String, String> = emptyMap(),
    ) {
        fun on(key: String): Boolean = LiveMirror.on(key, switches)
        /** [key]'s live record: closed trades and their net after charges (its paper record is its own book's). */
        fun record(key: String): Pair<Int, Double> = closed.filter { it.key == key }.let { it.size to it.sumOf { p -> p.net ?: 0.0 } }
    }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view
    @Volatile private var cache: Book? = null

    /** The open live trades' quote keys ("NFO:SYMBOL"), for the price stream (memory only: the tick path reads it). */
    @Volatile var liveKeysHint: List<String> = emptyList()
        private set

    /** Something live is held (memory only): the fast path and the watch keep running for it. */
    @Volatile var holdingHint: Boolean = false
        private set

    // ---- the paper arms' notes (memory only, never blocking) -----------------------------------------------------------------

    /** A paper book that hosts mirrored arms: its open paper trades now, from memory (null: not read - nothing is decided on it). */
    fun interface Host { fun open(): List<PaperLeg>? }

    /** One open paper trade: its [ref] ("family:id", as given to [entered]), its arm [key], the quantity it still holds. */
    data class PaperLeg(val ref: String, val key: String, val qty: Int)

    private val hosts = java.util.concurrent.ConcurrentHashMap<String, Host>()

    /** [family]'s paper book ([LiveMirror.Spec.family]), registered once at the app's start (and by the tests). */
    fun register(family: String, host: Host) { hosts[family] = host }

    /** Each paper trade's latest stop or lock level, as its own book last set it (memory only). */
    private val levels = java.util.concurrent.ConcurrentHashMap<String, Double>()
    /** Paper trades closed since the app started, and why (memory only; after a restart their absence from the book says it). */
    private val closedRefs = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * A paper arm's buy just filled ([ask]): the live entry is asked on a worker; this returns at once (memory only). Nothing goes
     * unless every switch and guard allows it ([LiveMirror.refusal]).
     */
    fun entered(ask: Ask) {
        if (!ready() || LiveMirror.spec(ask.key) == null) return
        ask.stop?.let { levels[ask.ref] = it }
        // In Paper (the usual case) nothing more happens: no worker, no read (the settings are in memory).
        if (!runCatching { AppSettings.load().let { it.live && it.allowRealOrders } }.getOrDefault(false)) return
        if (testSync) { kotlinx.coroutines.runBlocking { enterNow(ask) }; return }
        scope.launch { runCatching { enterNow(ask) }.onFailure { e -> note(ask.key, "live entry failed: ${e.javaClass.simpleName}") } }
    }

    /**
     * A paper buy to mirror: arm [key], paper trade [ref], its [contract], [side] (+1 a buy, -1 a sale), its [paperQty] filled at
     * [paperEntry], its stop [stop] (null: none on the premium) and, for a LIMIT entry, the [limit] it paid at most.
     */
    data class Ask(val key: String, val ref: String, val contract: Paper.Contract, val side: Int, val paperQty: Int, val paperEntry: Double,
                   val stop: Double? = null, val limit: Double? = null)

    /** The paper trade [ref]'s stop or lock is at [paperLevel] now: the live stop follows on the next look (memory only). */
    fun level(ref: String, paperLevel: Double?) {
        if (paperLevel == null || !paperLevel.isFinite() || paperLevel <= 0) return
        levels[ref] = paperLevel
    }

    /** The paper trade [ref] is closed ([why]): its live trade is sold on the next look, which is asked now (memory only). */
    fun closed(ref: String, why: String) {
        closedRefs[ref] = why
        if (holdingHint) nudge()
    }

    /** A look soon, off the caller's thread (the minute lane and the stop check run it anyway). */
    fun nudge() {
        if (!ready() || !holdingHint) return
        if (testSync) return
        scope.launch { runCatching { watch(stream = false) } }
    }

    // ---- Boss's switches -----------------------------------------------------------------------------------------------------

    /**
     * Boss's "Trade live" for [key]: OFF at any time with no PIN (it lowers risk); ON only with [pinConfirmed] (it adds live risk).
     * Logged in the diagnostics and the Autopilot change timeline (one tap undoes it there; undoing an "off" asks the PIN). What to
     * say. A worker thread (the book is read and written).
     */
    suspend fun setLive(key: String, on: Boolean, pinConfirmed: Boolean, log: Boolean = true): String {
        val spec = LiveMirror.spec(key) ?: return "No such arm."
        if (!ready()) return "Not ready yet."
        if (on && !pinConfirmed) return "Turning ${spec.label}'s live trading on needs your PIN."
        val was = lock.withLock {
            val b = book()
            val w = LiveMirror.on(key, b.switches)
            b.switches[key] = on
            line(b, "${spec.label}: Trade live ${if (on) "ON" else "OFF"} (you)")
            save(b)
            w
        }
        if (was != on) {
            runCatching { Diag.record("armlive", "${spec.label}: Trade live ${if (on) "on (PIN)" else "off"} by you") }
            if (log) runCatching { com.optionslab.app.ira.Autopilot.logArmLive(key, spec.label, on) }
        }
        return if (on) "${spec.label} trades live again when the app is in Live with \"AI trades go live\" on."
        else "${spec.label} stays on paper only. Its open live trade, if any, is still managed to its exit."
    }

    // ---- the book ------------------------------------------------------------------------------------------------------------

    private fun ready(): Boolean = file != null

    private fun book(): Book {
        cache?.let { return it }
        val b = Book()
        val f = file
        if (f != null) runCatching {
            val o = JSONObject(String(Vault.readFileSteady(f) ?: return@runCatching, Charsets.UTF_8))
            o.optJSONArray("positions")?.let { a -> for (i in 0 until a.length()) runCatching { b.positions += readPos(a.getJSONObject(i)) } }
            o.optJSONObject("switches")?.let { m -> m.keys().forEach { b.switches[it] = m.getBoolean(it) } }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
            o.optJSONObject("refused")?.let { m -> m.keys().forEach { b.refused[it] = m.getString(it) } }
        }.onFailure { if (f.exists()) runCatching { Vault.setAside(f) } }
        cache = b
        publish(b)
        return b
    }

    private fun readPos(p: JSONObject): Pos {
        fun s(k: String) = p.optString(k).ifEmpty { null }
        fun d(k: String) = if (p.has(k) && !p.isNull(k)) p.getDouble(k) else null
        return Pos(p.getString("key"), p.getString("ref"), p.getString("symbol"), p.getString("exchange"), p.getString("product"),
            p.getInt("side"), p.getInt("qty"), p.getInt("entryQty"), p.getInt("lot"), p.getDouble("tick"), p.optInt("mult", 1),
            p.getDouble("entry"), LocalDateTime.parse(p.getString("entryTime")), p.getDouble("paperEntry"), p.getInt("paperQty"),
            s("expiry")?.let { LocalDate.parse(it) }, s("entryOrderId"), s("stopOrderId"), d("stopTrigger"), p.optBoolean("seen"),
            p.optBoolean("unconfirmed"), d("exit"), s("exitTime")?.let { LocalDateTime.parse(it) }, s("why"), p.optDouble("charges", 0.0))
    }

    private fun writePos(p: Pos): JSONObject = JSONObject().put("key", p.key).put("ref", p.ref).put("symbol", p.symbol).put("exchange", p.exchange)
        .put("product", p.product).put("side", p.side).put("qty", p.qty).put("entryQty", p.entryQty).put("lot", p.lot).put("tick", p.tick)
        .put("mult", p.multiplier).put("entry", p.entry).put("entryTime", p.entryTime.toString()).put("paperEntry", p.paperEntry)
        .put("paperQty", p.paperQty).put("expiry", p.expiry?.toString() ?: "").put("entryOrderId", p.entryOrderId ?: "")
        .put("stopOrderId", p.stopOrderId ?: "").apply { p.stopTrigger?.let { put("stopTrigger", it) }; p.exit?.let { put("exit", it) } }
        .put("seen", p.seen).put("unconfirmed", p.unconfirmed).put("exitTime", p.exitTime?.toString() ?: "").put("why", p.why ?: "")
        .put("charges", p.charges)

    private fun save(b: Book) {
        // Closed trades kept: the last [CLOSED_KEPT] (the live record's words read them); every open one always.
        val closed = b.positions.filter { !it.open }
        if (closed.size > CLOSED_KEPT) { val gone = closed.take(closed.size - CLOSED_KEPT).toSet(); b.positions.removeAll { it in gone } }
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        val o = JSONObject()
            .put("positions", JSONArray().apply { b.positions.forEach { put(writePos(it)) } })
            .put("switches", JSONObject().apply { b.switches.forEach { (k, v) -> put(k, v) } })
            .put("log", JSONArray().apply { b.log.forEach { put(it) } })
            .put("refused", JSONObject().apply { b.refused.forEach { (k, v) -> put(k, v) } })
        file?.let { f -> runCatching { written.write(f, o.toString().toByteArray(Charsets.UTF_8)) } }
        publish(b)
    }

    private fun publish(b: Book) {
        val open = b.positions.filter { it.open }
        holdingHint = open.isNotEmpty()
        liveKeysHint = open.map { "${it.exchange}:${it.symbol}" }.distinct()
        _view.value = View(b.switches.toMap(), open, b.positions.filter { !it.open }.takeLast(60), b.log.takeLast(40), b.refused.toMap())
    }

    /** Read the book (the start's positions-first step and the screens). A worker thread. */
    suspend fun refresh(): View { if (ready()) lock.withLock { book() }; return _view.value }

    /** Whether a live trade of these arms is open (the book read when it is not yet). */
    suspend fun holding(): Boolean = refresh().open.isNotEmpty()

    /** Whether one of these arms' stops rests at Zerodha for [symbol] (the Live check's "stop watched"; memory only). */
    fun stopRests(symbol: String): Boolean = _view.value.open.any { it.symbol == symbol && it.stopOrderId != null }

    /** The arm of a live trade of [symbol] held now (memory only), for the order lists' "who placed it". */
    fun ownerOf(symbol: String): String? = _view.value.open.firstOrNull { it.symbol == symbol }?.label

    private fun line(b: Book, text: String) {
        val t = now()
        b.log += "%02d:%02d %s".format(Locale.ENGLISH, t.hour, t.minute, text)
    }

    private fun note(key: String, text: String) {
        runCatching { Diag.record("armlive", "${LiveMirror.spec(key)?.label ?: key}: $text") }
    }

    // ---- clocks (the tests fix them) ----------------------------------------------------------------------------------------

    /** TEST ONLY: a fixed clock. Null in the app, always. Its setter throws unless BuildConfig.DEBUG. */
    @Volatile internal var testNow: java.time.ZonedDateTime? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test clock exists only in debug builds" }; field = v }
    /**
     * TEST SEAM: true in the JVM tests' app - an arm Boss never switched counts as off there, so the paper arms' own tests (Live,
     * AI trades on) stay about their paper books (as [PassedArmsHost.testNoAutoOn]); the live path's tests set it false. Its setter
     * throws in a release build.
     */
    @Volatile internal var testDefaultOff: Boolean = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }
    /** TEST ONLY: [entered] runs the entry before it returns (no worker), so a test reads what it did at once. Debug builds only. */
    @Volatile internal var testSync: Boolean = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }
    private fun now(): LocalDateTime = (testNow ?: Market.now()).toLocalDateTime().withNano(0)

    // ---- entries ---------------------------------------------------------------------------------------------------------------

    /**
     * The gate's answers now, in [LiveMirror.refusal]'s order, read only as far as needed: in Paper, with AI trades off or the arm's
     * own switch off nothing else is read (no Zerodha, no book). A worker: the settings and the books are read here, never on the
     * order path.
     */
    private suspend fun gate(key: String): LiveMirror.Gate {
        val s = AppSettings.load()
        val appLive = s.live && s.allowRealOrders
        val aiLive = appLive && runCatching { !com.optionslab.app.ira.IraNewsTrades.paperFirst }.getOrDefault(false)
        val armOn = lock.withLock { book().switches.toMap() }.let { sw -> if (testDefaultOff && key !in sw) false else LiveMirror.on(key, sw) }
        val first = LiveMirror.Gate(appLive = appLive, aiLive = aiLive, armOn = armOn)
        if (LiveMirror.refusal(first) != null) return first
        val g = first.copy(paperOnly = PaperOnly.entryRefusal(), kill = s.guardKill,
            stopped = runCatching { Strategies.stoppedToday() }.getOrDefault(true),
            lossLimit = runCatching { LossBreaker.trippedToday() }.getOrDefault(false))
        if (LiveMirror.refusal(g) != null) return g
        val g2 = g.copy(dayLock = runCatching { DayLockGuard.refusal(true) }.getOrNull(), accountStop = accountStop(), loggedIn = Broker.loggedIn)
        if (LiveMirror.refusal(g2) != null) return g2
        return g2.copy(compromised = runCatching {
            com.optionslab.app.security.Integrity.compromised(com.optionslab.app.security.Integrity.reportWithin(app, 60_000))
        }.getOrDefault(false))
    }

    /**
     * The account stop rule (Boss's: -Rs 40,000 from the best, or -Rs 13,000 in a calendar month) over the app's live record of the
     * arms - these arms' live trades and the ORB family's - or null while neither is reached. It only ever stops new entries.
     */
    private suspend fun accountStop(): String? = runCatching {
        val mine = lock.withLock { book().positions.filter { !it.open } }.mapNotNull { p ->
            val t = p.exitTime ?: return@mapNotNull null
            com.optionslab.ira.LiquidityStopRule.Trade(t.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli(), t.toLocalDate(), p.net ?: 0.0)
        }
        val orb = runCatching { OrbArms.closedLive() }.getOrDefault(emptyList()).mapNotNull { p ->
            val t = p.exitTime ?: return@mapNotNull null
            val net = p.exit?.let { (it - p.entry) * p.qty - p.charges } ?: return@mapNotNull null
            com.optionslab.ira.LiquidityStopRule.Trade(t.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli(), t.toLocalDate(), net)
        }
        val st = com.optionslab.ira.LiquidityStopRule.check(mine + orb, now().toLocalDate())
        if (st.stopped) st.why else null
    }.getOrNull()

    /** Zerodha's contract for [c]: (symbol, exchange, order lot, tick, multiplier) or why not. */
    private suspend fun zerodha(c: Paper.Contract): Pair<Ins?, String?> {
        if (c.isMcx) {
            val m = McxMarket.find(c.underlying, c.expiry, c.strike, c.right.takeIf { it != Right.IX }) ?: return null to "${c.symbol} is not in the MCX list"
            return Ins(m.tradingSymbol, "MCX", 1, m.tick, m.multiplier.coerceAtLeast(1)) to null
        }
        if (c.underlying == "SENSEX") {
            val l = Broker.cachedBfoOptions() ?: run { Broker.loadBfoOptionsInBackground(); return null to "SENSEX's options list is not read yet" }
            val i = Broker.find(l, c.underlying, c.expiry, c.strike, c.right) ?: return null to "${c.symbol} is not listed on Zerodha"
            return Ins(i.tradingSymbol, "BFO", i.lotSize, i.tickSize, 1) to null
        }
        val list = Broker.cachedInstruments() ?: runCatching { Broker.instruments() }.getOrNull() ?: return null to "Zerodha's instrument list could not be read"
        val i = list.firstOrNull { it.name == c.underlying && it.expiry == c.expiry && it.right == c.right && kotlin.math.abs(it.strike - c.strike) < 1e-6 }
            ?: return null to "${c.symbol} is not listed on Zerodha"
        return Ins(i.tradingSymbol, "NFO", i.lotSize, i.tickSize, 1) to null
    }

    private data class Ins(val symbol: String, val exchange: String, val lot: Int, val tick: Double, val multiplier: Int)

    private suspend fun refuse(key: String, why: String, loud: Boolean) {
        runCatching {
            lock.withLock { val b = book(); b.refused[key] = "%s: %s".format(Locale.ENGLISH, now().toString().take(16).replace('T', ' '), why); save(b) }
        }
        if (loud) note(key, "live entry not sent: $why")
    }

    /** Kite order ids already tracked here and by every other bot: never adopted for a lost reply. */
    private suspend fun known(): List<String> =
        runCatching { Strategies.owners().keys.filter { it.startsWith("kite:") }.map { it.removePrefix("kite:") } }.getOrDefault(emptyList()) +
            lock.withLock { book().positions.flatMap { listOfNotNull(it.entryOrderId, it.slId) } }

    private fun charge(side: String, price: Double, qty: Int, exchange: String, symbol: String, multiplier: Int): Double = runCatching {
        com.optionslab.engine.sandbox.SandboxCosts.charge(side, java.math.BigDecimal(price), qty, java.math.BigDecimal(multiplier.coerceAtLeast(1)),
            exchange, symbol).toDouble()
    }.getOrDefault(0.0)

    private val DONE = setOf("COMPLETE", "REJECTED", "CANCELLED")

    /** The live entry for [a], when the gate allows it. */
    private suspend fun enterNow(a: Ask) = entryLock.withLock {
        val spec = LiveMirror.spec(a.key) ?: return@withLock
        runCatching { OrderTiming.decided() }
        val g = gate(a.key)
        LiveMirror.refusal(g)?.let { why ->
            // In Paper (the usual case) nothing is said: the arm trades its paper book as always.
            if (g.appLive) refuse(a.key, why, loud = true)
            return@withLock
        }
        // One live trade per paper trade, ever.
        if (lock.withLock { book().positions.any { it.ref == a.ref } }) return@withLock
        val (ins, insWhy) = zerodha(a.contract)
        if (ins == null) { refuse(a.key, insWhy ?: "no contract", loud = true); return@withLock }
        val lots = LiveMirror.lots(a.paperQty, a.contract.lotSize)
        val qty = LiveMirror.liveQty(lots, ins.lot)
        if (qty <= 0) { refuse(a.key, "the paper trade is under one lot", loud = true); return@withLock }
        val qkey = "${ins.exchange}:${ins.symbol}"
        val last = runCatching { Broker.stopQuotes(listOf(qkey))[qkey]?.last }.getOrNull()?.takeIf { it > 0 }
            ?: run { refuse(a.key, "no Zerodha price for ${ins.symbol}", loud = true); return@withLock }
        val s = AppSettings.load()
        val side = if (a.side >= 0) Kite.Side.BUY else Kite.Side.SELL
        // The paper arm's own order type: a LIMIT entry pays at most its paper limit (never chases), else MARKET.
        val o = if (a.limit != null) Kite.Order(ins.symbol, side, qty, ins.lot, spec.product, "LIMIT",
                Kite.onTick(a.limit, ins.tick, side), ins.tick, ins.exchange, LiveMirror.TAG, multiplier = ins.multiplier)
            else Kite.Order(ins.symbol, side, qty, ins.lot, spec.product, "MARKET", null, ins.tick, ins.exchange, LiveMirror.TAG, multiplier = ins.multiplier)
        val acct = runCatching { Broker.accountNow(needed = last * qty * ins.multiplier) }.getOrNull()
        Guard.check(Guard.liveOrder(o).copy(price = last), acct).takeIf { it.isNotEmpty() }?.let { refuse(a.key, "account guard: " + it.joinToString(" "), true); return@withLock }
        Kite.refusals(o, s.limits(), Broker.sentToday(), false, refPrice = last).takeIf { it.isNotEmpty() }?.let { refuse(a.key, it.joinToString("; "), true); return@withLock }
        val known = known()
        val id: String? = try {
            Broker.placeOrder(o, arm = "arm:${a.key}", intended = last)
        } catch (e: Broker.NotLoggedIn) {
            refuse(a.key, "not logged in to Zerodha today", true); return@withLock
        } catch (e: Exception) {
            if (Broker.definite(e)) { refuse(a.key, e.message ?: "Zerodha refused the order", true); return@withLock }
            // The answer was lost, not necessarily the order: look before calling it unsent.
            try { Broker.findRecentRetrying(o, known) ?: run { refuse(a.key, "${e.message}; no matching order at Zerodha", true); return@withLock } }
            catch (_: Exception) { null }
        }
        id?.let { runCatching { Strategies.tagOwner("kite:$it", "${spec.label} · live entry") } }
        var f = id?.let { runCatching { Broker.awaitOrder(it, 15_000) }.getOrNull() }
        if (id != null && f?.status !in DONE) {
            // Not finished in 15 s: the rest is cancelled so it can never fill later untracked; only what filled is booked.
            runCatching { Broker.cancel(id) }
            f = runCatching { Broker.orderState(id) }.getOrNull()
        }
        val base = Pos(a.key, a.ref, ins.symbol, ins.exchange, spec.product, if (a.side >= 0) 1 else -1, qty, qty, ins.lot, ins.tick, ins.multiplier,
            last, now(), a.paperEntry, a.paperQty, a.contract.expiry, id,
            seen = paperOpen(spec, a.ref) == true)
        if (f == null || f.status !in DONE) {
            // Zerodha has not said how it ended: held as unconfirmed (no second entry meanwhile) until the order book settles it.
            lock.withLock { val b = book(); b.positions += base.copy(unconfirmed = true); line(b, "${spec.label}: bought ${ins.symbol}, not yet confirmed"); save(b) }
            com.optionslab.app.work.Alerts.error("${spec.label}: Zerodha did not confirm the live ${side.name.lowercase()} of ${ins.symbol}. " +
                "It is treated as held until the order book shows what filled.", WHO)
            return@withLock
        }
        if (f.filled <= 0) { refuse(a.key, "Zerodha ${f.status.lowercase()}" + (f.message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""), true); return@withLock }
        val fill = f.avgPrice.takeIf { it > 0 } ?: last
        runCatching { Notifier.orderFilled(app, side.name, f.filled, ins.symbol, fill, "Live", "${spec.label} · live entry", f.orderId) }
        var p = base.copy(qty = f.filled, entryQty = f.filled, entry = fill, charges = charge(side.name, fill, f.filled, ins.exchange, ins.symbol, ins.multiplier))
        // The stop rests at once, on the paper trade's level on this fill.
        p = restStop(p, wanted(p), ltp = fill)
        lock.withLock {
            val b = book()
            b.positions += p
            b.refused.remove(a.key)
            line(b, "%s: %s %s %d @ %.2f live%s".format(Locale.ENGLISH, spec.label, if (p.side > 0) "bought" else "sold", ins.symbol, p.qty, fill,
                p.stopTrigger?.let { " · stop %.2f %s".format(Locale.ENGLISH, it, if (p.stopOrderId != null) "at Zerodha" else "watched by the app (not rested yet)") }
                    ?: " · no stop on the premium (the app follows its paper exits)"))
            save(b)
        }
        runCatching { Diag.record("armlive", "${spec.label}: live entry ${ins.symbol} ${p.qty} @ %.2f".format(Locale.ENGLISH, fill)) }
        if (closedRefs.containsKey(a.ref)) nudge()
    }

    /** Whether [ref] is open in its paper book now (null: that book not read). */
    private fun paperOpen(spec: LiveMirror.Spec, ref: String): Boolean? = hosts[spec.family]?.open()?.any { it.ref == ref }

    /** The paper trade's stop or lock as the live level on [p]'s fill (null: none). */
    private fun wanted(p: Pos): Double? {
        val spec = LiveMirror.spec(p.key) ?: return null
        val lv = levels[p.ref] ?: return null
        return LiveMirror.level(p.paperEntry, lv, p.entry, spec.scale, p.side, p.tick)
    }

    // ---- the stop at Zerodha -------------------------------------------------------------------------------------------------

    /**
     * [p]'s stop rested at Zerodha at [at] (with the price at [ltp]): an SL order with its limit for an intraday trade, a single-leg
     * GTT for a carried one ([LiveMirror.Rest]). [p] with its order and trigger, or as it was (the app then watches the level).
     */
    private suspend fun restStop(p: Pos, at: Double?, ltp: Double): Pos {
        val spec = LiveMirror.spec(p.key) ?: return p
        val trigger = at ?: return p
        if (!LiveMirror.mayRest(trigger, ltp, p.tick, p.side)) return p.copy(stopTrigger = trigger)
        val exitSide = if (p.side > 0) Kite.Side.SELL else Kite.Side.BUY
        // Never more resting exits than Zerodha holds: the exits already working on this contract (another arm's stop, a fired GTT's
        // order, Boss's own) plus this one must fit in what is held, or both could fill and turn it the other way. Unread: not now.
        val covered = runCatching {
            Broker.orders().filter { it.working && it.symbol == p.symbol && it.side == exitSide.name && it.product == p.product }
                .sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) }
        }.getOrNull() ?: return p.copy(stopTrigger = trigger)
        val heldNow = runCatching {
            Broker.positionBook().net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }.sumOf { it.qty } * p.side
        }.getOrNull() ?: return p.copy(stopTrigger = trigger)
        if (covered + p.qty > heldNow) return p.copy(stopTrigger = trigger)
        if (spec.rest == LiveMirror.Rest.GTT) {
            val sp = Kite.Spec(p.exchange, p.symbol, 0L, p.lot, p.tick)
            val (gtt, why) = Kite.protect(sp, p.product, p.qty * p.side, ltp, trigger, null)
            if (gtt == null) { stopFailed(p, trigger, why.joinToString("; ")); return p.copy(stopTrigger = trigger) }
            val id = runCatching { Broker.placeGtt(gtt) }.getOrElse { e -> stopFailed(p, trigger, e.message?.take(160) ?: "an error"); return p.copy(stopTrigger = trigger) }
            return p.copy(stopOrderId = GTT + id, stopTrigger = trigger)
        }
        val o = Kite.Order(p.symbol, exitSide, p.qty, p.lot, p.product, "SL", LiveMirror.stopLimit(trigger, p.tick, p.side), p.tick, p.exchange,
            LiveMirror.TAG, triggerPrice = trigger, multiplier = p.multiplier)
        val id = try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
            if (Broker.definite(e)) null else runCatching { Broker.findRecentRetrying(o, known()) }.getOrNull()
        }
        if (id == null) { stopFailed(p, trigger, "Zerodha refused it"); return p.copy(stopTrigger = trigger) }
        runCatching { Strategies.tagOwner("kite:$id", "${spec.label} · live stop") }
        return p.copy(stopOrderId = id, stopTrigger = trigger)
    }

    private fun stopFailed(p: Pos, trigger: Double, why: String) {
        com.optionslab.app.work.Alerts.post("${p.label}: the live stop at %.2f could not rest at Zerodha (%s); the app watches it itself."
            .format(Locale.ENGLISH, trigger, why), com.optionslab.app.work.Alerts.Kind.ERROR, WHO, throttle = true)
    }

    /** [p]'s resting stop moved to [to] (a modify of the same SL order or GTT: never a cancel and a new one, never down). */
    private suspend fun moveStop(p: Pos, so: Broker.OrderRow?, to: Double, ltp: Double): Pos {
        val r = runCatching {
            val gid = p.gttId
            if (gid != null) {
                val (gtt, why) = Kite.protect(Kite.Spec(p.exchange, p.symbol, 0L, p.lot, p.tick), p.product, p.qty * p.side, ltp, to, null)
                requireNotNull(gtt) { why.joinToString("; ") }
                Broker.modifyGtt(gid, gtt)
            } else {
                val o = requireNotNull(so) { "the stop order was not read" }
                Broker.modify(o, o.qty, "SL", LiveMirror.stopLimit(to, p.tick, p.side), to)
            }
        }
        if (r.isSuccess) return p.copy(stopTrigger = to)
        com.optionslab.app.work.Alerts.post("${p.label}: Zerodha did not move the live stop on ${p.symbol} to %.2f (%s); it stays at %s and is tried again."
            .format(Locale.ENGLISH, to, r.exceptionOrNull()?.message?.take(160) ?: "an error", p.stopTrigger?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "none"),
            com.optionslab.app.work.Alerts.Kind.ERROR, WHO, throttle = true)
        return p
    }

    /**
     * [p]'s resting stop taken out (cancelled, or the GTT deleted) and confirmed out. (state, filled): COMPLETE with its fill when the
     * stop had filled meanwhile; null when it could not be confirmed out (nothing else may be sold then: both could fill).
     */
    private suspend fun stopOut(p: Pos): Pair<String, Broker.Fill?>? {
        p.gttId?.let { gid ->
            val g = runCatching { Broker.gtts().firstOrNull { it.id == gid } }.getOrElse { return null }
            if (g == null || g.status.lowercase() in setOf("deleted", "cancelled", "expired", "rejected", "disabled")) return "CANCELLED" to null
            // A GTT that has fired cannot fire again: it is out. The order it put in, while it still works, is a working exit the sale
            // below subtracts (never a second sell beside it); refused or cancelled, the sale goes.
            if (g.status.lowercase() == "triggered") return "CANCELLED" to null
            runCatching { Broker.deleteGtt(gid) }
            val after = runCatching { Broker.gtts().firstOrNull { it.id == gid } }.getOrElse { return null }
            return if (after == null || after.status.lowercase() != "active" && after.status.lowercase() != "triggered") "CANCELLED" to null else null
        }
        val id = p.slId ?: return "CANCELLED" to null
        val c0 = System.currentTimeMillis()
        runCatching { Broker.cancel(id) }
        val st = runCatching { Broker.orderState(id) }.getOrNull()
        runCatching { OrderTiming.stopCancel(System.currentTimeMillis() - c0) }
        // An SL order of an earlier session is gone with it (a DAY order): not in today's book, nothing of it can fill.
        if (st == null && p.day.isBefore(now().toLocalDate())) return "CANCELLED" to null
        return when (st?.status) {
            "COMPLETE" -> "COMPLETE" to st
            "CANCELLED", "REJECTED" -> st.status to st
            else -> null
        }
    }

    // ---- exits ------------------------------------------------------------------------------------------------------------------

    /**
     * Sell [qty] of [p] ([why]): its resting stop out first (one that filled meanwhile is the exit), then a MARKET order for what
     * Zerodha holds less what is already on its way out. The closed part, and the rest still open (or null), to book.
     */
    private suspend fun exitLive(p: Pos, qty: Int, why: String): Pair<Pos, Pos?> {
        runCatching { OrderTiming.decided() }
        val out = stopOut(p) ?: return p to null                       // not confirmed out: tried again next look
        if (out.first == "COMPLETE") {
            val f = out.second
            val px = f?.avgPrice?.takeIf { it > 0 } ?: p.stopTrigger ?: p.entry
            val exitSide = if (p.side > 0) "SELL" else "BUY"
            runCatching { Notifier.orderFilled(app, exitSide, p.qty, p.symbol, px, "Live", "${p.label} · live stop", f?.orderId) }
            return p.copy(stopOrderId = null, exit = px, exitTime = now(), why = "stop", charges = p.charges + charge(exitSide, px, p.qty, p.exchange, p.symbol, p.multiplier)) to null
        }
        val cur = p.copy(stopOrderId = null)
        val exitSide = if (p.side > 0) Kite.Side.SELL else Kite.Side.BUY
        val working = runCatching { Broker.orders().filter { it.working && it.symbol == p.symbol && it.side == exitSide.name && it.product == p.product } }.getOrNull()
            ?: return cur to null
        val held = runCatching { Broker.positionBook().net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }.sumOf { it.qty } }.getOrNull()
            ?: return cur to null
        val mine = if (p.side > 0) held else -held
        val sendable = com.optionslab.engine.risk.ExitQty.sendable(mine.coerceAtLeast(0),
            working.sumOf { com.optionslab.engine.risk.ExitQty.remaining(it.qty, it.filled, it.pending) }, minOf(qty, p.qty), p.lot)
        if (sendable <= 0) return cur to null
        val o = Kite.Order(p.symbol, exitSide, sendable, p.lot, p.product, "MARKET", null, p.tick, p.exchange, LiveMirror.TAG, multiplier = p.multiplier)
        val bad = Kite.refusals(o, AppSettings.load().limits(), Broker.sentToday(), false, exit = true)
        if (bad.isNotEmpty()) {
            com.optionslab.app.work.Alerts.error("${p.label}: the live exit was not sent (${bad.joinToString("; ")}). Close ${p.symbol} in Trade.", WHO)
            return cur to null
        }
        val id = try { Broker.placeOrder(o, exit = true) } catch (e: Exception) {
            val found = if (Broker.definite(e)) null else runCatching { Broker.findRecentRetrying(o, known() + working.map { it.id }) }.getOrNull()
            found ?: run {
                com.optionslab.app.work.Alerts.post("${p.label}: the live exit failed (${e.message?.take(160)}); retrying on the next look.",
                    com.optionslab.app.work.Alerts.Kind.ERROR, WHO, throttle = true)
                return cur to null
            }
        }
        runCatching { Strategies.tagOwner("kite:$id", "${p.label} · live $why") }
        var f = runCatching { Broker.awaitOrder(id, 15_000) }.getOrNull()
        if (f?.status !in DONE) { runCatching { Broker.cancel(id) }; f = runCatching { Broker.orderState(id) }.getOrNull() ?: f }
        if (f == null || f.filled <= 0) return cur to null
        val px = f.avgPrice.takeIf { it > 0 } ?: p.entry
        runCatching { Notifier.orderFilled(app, exitSide.name, f.filled, p.symbol, px, "Live", "${p.label} · live $why", f.orderId) }
        val sold = minOf(f.filled, cur.qty)
        val share = sold.toDouble() / cur.qty
        val closedPart = cur.copy(qty = sold, entryQty = sold, exit = px, exitTime = now(), why = why,
            charges = cur.charges * share + charge(exitSide.name, px, sold, p.exchange, p.symbol, p.multiplier))
        val rest = if (sold >= cur.qty) null else cur.copy(qty = cur.qty - sold, charges = cur.charges * (1 - share))
        return closedPart to rest
    }

    // ---- the watch ----------------------------------------------------------------------------------------------------------------

    /**
     * One look at every open live trade (the fast path's minute lane, the 15-second stop check, a paper exit's nudge): settles an
     * unconfirmed entry, books a filled stop or a trade closed at Zerodha, sells what its paper trade sold, keeps the stop resting
     * at the paper trade's level (moved up only), and runs the backstops. [stream]: prices from the stream's fresh ticks where it
     * has them. A worker thread; never the tick path. Nothing open: returns at once (memory only).
     */
    suspend fun watch(stream: Boolean = false) {
        if (!ready()) return
        if (cache == null) lock.withLock { book() }
        if (!holdingHint) return
        if (!opLock.tryLock()) return                 // a look already running does this one's work
        try { look(stream) } finally { opLock.unlock() }
    }

    private suspend fun look(stream: Boolean) {
        val open = lock.withLock { book().positions.filter { it.open }.toList() }
        if (open.isEmpty() || !Broker.loggedIn) return
        val t = now()
        val orders = (if (stream) runCatching { Broker.ordersRecon() } else runCatching { Broker.orders() }).getOrNull()?.associateBy { it.id } ?: return
        val net = (if (stream) runCatching { Broker.positionsRecon().net } else runCatching { Broker.positionBook().net }).getOrNull() ?: return
        val keys = open.map { "${it.exchange}:${it.symbol}" }.distinct()
        val q = runCatching { quotes(keys, stream) }.getOrDefault(emptyMap())
        // The GTT list: read on a regular look; a stream look (the live lane, every tick) uses the last one read within 15 s.
        val gtts = if (open.none { it.gttId != null }) emptyMap() else gttsKept?.takeIf { stream && System.currentTimeMillis() - it.first < 15_000L }?.second
            ?: runCatching { Broker.gtts().associateBy { it.id } }.getOrNull()?.also { gttsKept = System.currentTimeMillis() to it }
        val stopped = runCatching { Strategies.stoppedToday() }.getOrDefault(false)
        val updates = ArrayList<Pair<Pos, List<Pos>>>()
        for (p0 in open) {
            val spec = LiveMirror.spec(p0.key)
            try {
                if (p0.unconfirmed) { updates += p0 to listOfNotNull(settle(p0)); continue }
                var p = p0
                // The paper trade: seen open now (from then on its absence closes this), and what it still holds.
                // The paper books: read on a regular look; a stream look uses the last read (Solo's is in the settings vault).
                val legs = spec?.let { sp -> if (stream) legsKept[sp.family] else hosts[sp.family]?.open()?.also { legsKept[sp.family] = it } }
                val leg = legs?.firstOrNull { it.ref == p.ref }
                if (leg != null && !p.seen) p = p.copy(seen = true)
                val gone = closedRefs.containsKey(p.ref) || LiveMirror.paperGone(p.seen, legs?.let { leg != null }) || leg?.qty == 0
                val paperSell = if (gone) p.qty else leg?.let { LiveMirror.toSell(p.qty, p.entryQty, p.paperQty, it.qty, p.lot) } ?: 0
                // The resting stop at Zerodha.
                val so = p.slId?.let { orders[it] }
                val g = p.gttId?.let { gtts?.get(it) }
                var stopFilled = so?.status == "COMPLETE"
                if (so != null && so.status in setOf("REJECTED", "CANCELLED")) {
                    com.optionslab.app.work.Alerts.post("${p.label}: the live stop at Zerodha was ${so.status.lowercase()} (${so.message.ifBlank { "no reason given" }}); " +
                        "the app watches it and rests it again.", com.optionslab.app.work.Alerts.Kind.ERROR, WHO, throttle = true)
                    p = p.copy(stopOrderId = null)
                }
                if (p.gttId != null && gtts != null && (g == null || g.status.lowercase() in setOf("deleted", "cancelled", "expired", "rejected", "disabled"))) p = p.copy(stopOrderId = null)
                // An SL order of an earlier session (a DAY order) is gone: the stop is rested again today.
                if (p.slId != null && so == null && p.day.isBefore(t.toLocalDate())) p = p.copy(stopOrderId = null)
                val held = net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }.sumOf { it.qty }
                // A fired GTT: its own order is the resting exit while it works; once it is refused or cancelled with the position
                // still held, the stop is rested again (never while that order still works: two exits).
                if (g?.status?.lowercase() == "triggered" && (if (p.side > 0) held else -held) > 0) {
                    val exitSide = if (p.side > 0) "SELL" else "BUY"
                    if (orders.values.none { it.working && it.symbol == p.symbol && it.side == exitSide }) p = p.copy(stopOrderId = null)
                }
                // A fired GTT whose order sold everything: that was the stop.
                if (p.gttId != null && g?.status?.lowercase() == "triggered" && (if (p.side > 0) held else -held) <= 0) stopFilled = true
                val key = "${p.exchange}:${p.symbol}"
                val ltp = q[key]?.last?.takeIf { it > 0 }
                val look = LiveMirror.Look(side = p.side, qty = p.qty, held = held, ltp = ltp, tick = p.tick, stopTrigger = p.stopTrigger,
                    stopResting = p.stopOrderId != null, stopFilled = stopFilled, wanted = wanted(p), paperSell = paperSell,
                    paperReason = closedRefs[p.ref] ?: if (gone) "paper_exit" else if (paperSell > 0) "paper_part" else null,
                    squareOff = LiveMirror.squareOffDue(t, p.exchange, p.product, p.expiry, p.day), operatorStop = stopped)
                val d = LiveMirror.decide(look)
                when (d.step) {
                    LiveMirror.Step.STOP_FILLED -> {
                        val px = so?.avg?.takeIf { it > 0 } ?: p.stopTrigger ?: p.entry
                        val side = if (p.side > 0) "SELL" else "BUY"
                        runCatching { Notifier.orderFilled(app, side, p.qty, p.symbol, px, "Live", "${p.label} · live stop", p.slId) }
                        updates += p0 to listOf(p.copy(stopOrderId = null, exit = px, exitTime = t, why = "stop",
                            charges = p.charges + charge(side, px, p.qty, p.exchange, p.symbol, p.multiplier)))
                    }
                    LiveMirror.Step.CLOSED_OUTSIDE -> {
                        // Its stop is confirmed out first: left resting, it would sell into a short. One that filled was the exit.
                        val out = if (p.stopOrderId != null) stopOut(p) else ("CANCELLED" to null)
                        if (out == null) { updates += p0 to listOf(p); continue }
                        val side = if (p.side > 0) "SELL" else "BUY"
                        val filled = out.first == "COMPLETE"
                        val px = (if (filled) out.second?.avgPrice?.takeIf { it > 0 } ?: p.stopTrigger else null) ?: ltp ?: p.entry
                        if (filled) runCatching { Notifier.orderFilled(app, side, p.qty, p.symbol, px, "Live", "${p.label} · live stop", p.slId) }
                        updates += p0 to listOf(p.copy(stopOrderId = null, exit = px, exitTime = t, why = if (filled) "stop" else "closed_at_zerodha",
                            charges = p.charges + charge(side, px, p.qty, p.exchange, p.symbol, p.multiplier)))
                    }
                    LiveMirror.Step.SELL -> {
                        val (done, rest) = exitLive(p, d.qty, d.why ?: "exit")
                        updates += p0 to listOfNotNull(done, rest)
                    }
                    LiveMirror.Step.PLACE_STOP -> updates += p0 to listOf(restStop(p, d.to, ltp ?: continue))
                    LiveMirror.Step.MOVE_STOP -> updates += p0 to listOf(moveStop(p, so, d.to ?: continue, ltp ?: continue))
                    LiveMirror.Step.HOLD -> if (p != p0) updates += p0 to listOf(p)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) { note(p0.key, "live look failed on ${p0.symbol}: ${e.javaClass.simpleName}; tried again next look") }
        }
        runCatching { WatchClock.stopsChecked() }
        if (updates.isEmpty()) return
        lock.withLock {
            val b = book()
            for ((old, now) in updates) {
                val i = b.positions.indexOfFirst { it == old }
                if (i < 0) continue
                b.positions.removeAt(i)
                b.positions.addAll(i, now)
                now.filter { !it.open }.forEach { c ->
                    line(b, "%s: %s %s %d @ %.2f live (%s), %s after charges".format(Locale.ENGLISH, c.label, if (c.side > 0) "sold" else "bought back",
                        c.symbol, c.qty, c.exit ?: 0.0, c.why, LiveMirror.rs(c.net ?: 0.0)))
                    if (b.positions.none { it.open && it.ref == c.ref }) { closedRefs.remove(c.ref); levels.remove(c.ref) }
                }
            }
            save(b)
        }
    }

    /** The last GTT list read (when, by id), for the stream looks. */
    @Volatile private var gttsKept: Pair<Long, Map<Long, Broker.GttRow>>? = null
    /** Each paper book's legs as last read by a regular look, for the stream looks (memory only). */
    private val legsKept = java.util.concurrent.ConcurrentHashMap<String, List<PaperLeg>>()

    /** The prices of [keys]: the stream's fresh ticks with [stream], the rest Zerodha's (a tick only while 2 s fresh). */
    private suspend fun quotes(keys: List<String>, stream: Boolean): Map<String, Broker.Quote> {
        if (!stream) return Broker.stopQuotes(keys)
        val out = HashMap<String, Broker.Quote>()
        for (k in keys) {
            val tok = runCatching { Broker.tokenOf(k) }.getOrNull() ?: continue
            val tk = KiteStream.freshTick(tok) ?: continue
            if (tk.last > 0) out[k] = Broker.Quote(tk.last, tk.bid, tk.ask, tk.open, tk.oi, tk.volume)
        }
        val rest = keys.filter { it !in out }
        if (rest.isNotEmpty()) out.putAll(Broker.stopQuotes(rest))
        return out
    }

    /** An entry Zerodha had not confirmed: settled from the order book (what filled, with its stop), or dropped (nothing filled). */
    private suspend fun settle(p: Pos): Pos? {
        if (p.day.isBefore(now().toLocalDate())) {
            // An intraday entry of an earlier day is gone with Zerodha's square-off. A carried one may still be held: what Zerodha
            // holds of it (never more than was asked) is adopted with its stop, so it is never left unmanaged.
            val held = if (p.product == "MIS") 0 else runCatching {
                Broker.positionBook().net.filter { it.symbol == p.symbol && it.exchange == p.exchange && it.product == p.product }.sumOf { it.qty } * p.side
            }.getOrElse { return p }
            if (held <= 0) {
                com.optionslab.app.work.Alerts.error("${p.label}: the live entry in ${p.symbol} on ${p.day} was never confirmed by Zerodha; check that day's contract note.", WHO)
                return null
            }
            val q = minOf(held, p.qty)
            val ltp = runCatching { Broker.stopQuotes(listOf("${p.exchange}:${p.symbol}"))["${p.exchange}:${p.symbol}"]?.last }.getOrNull()?.takeIf { it > 0 } ?: p.entry
            com.optionslab.app.work.Alerts.error("${p.label}: the live entry in ${p.symbol} on ${p.day} was never confirmed, but Zerodha holds $q: it is managed as this arm's trade.", WHO)
            val got = p.copy(qty = q, entryQty = q, unconfirmed = false)
            return restStop(got, wanted(got), ltp)
        }
        val side = if (p.side > 0) "BUY" else "SELL"
        val oid = p.entryOrderId ?: run {
            val row = runCatching { Broker.latestTagged(p.symbol, side, LiveMirror.TAG, p.entryTime.minusMinutes(1), known()) }.getOrElse { return p } ?: return null
            runCatching { Strategies.tagOwner("kite:${row.id}", "${p.label} · live entry") }
            row.id
        }
        var st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return p.copy(entryOrderId = oid)
        if (st.status !in DONE) {
            runCatching { Broker.cancel(oid) }
            st = runCatching { Broker.orderState(oid) }.getOrNull() ?: return p.copy(entryOrderId = oid)
            if (st.status !in DONE) return p.copy(entryOrderId = oid)
        }
        if (st.filled <= 0) return null
        val fill = st.avgPrice.takeIf { it > 0 } ?: p.entry
        val got = p.copy(qty = st.filled, entryQty = st.filled, entry = fill, entryOrderId = oid, unconfirmed = false,
            charges = charge(side, fill, st.filled, p.exchange, p.symbol, p.multiplier))
        return restStop(got, wanted(got), ltp = fill)
    }

    /** The diagnostics' line. Memory only. */
    fun diagLine(): String {
        val v = _view.value
        val off = LiveMirror.ARMS.filter { !v.on(it.key) }.map { it.label }
        return "Arms live: ${v.open.size} open" + (if (v.open.isEmpty()) "" else " (" + v.open.joinToString { "${it.label} ${it.symbol} ${it.qty}" + if (it.stopOrderId != null) " stop at Zerodha" else " stop watched by the app" } + ")") +
            ", ${v.closed.size} closed kept; Trade live off: " + (off.joinToString().ifEmpty { "none" })
    }

    /** For tests: the book forgotten (its file goes with the test's directory) and the memory notes cleared. */
    internal fun wipe() { cache = null; gttsKept = null; legsKept.clear(); _view.value = View(); holdingHint = false; liveKeysHint = emptyList(); levels.clear(); closedRefs.clear() }
}
