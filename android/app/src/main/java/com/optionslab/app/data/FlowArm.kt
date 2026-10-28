package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.LiveMirror
import com.optionslab.engine.sandbox.SandboxEvent
import com.optionslab.engine.signals.FlowRules
import com.optionslab.engine.signals.NtfyParser
import com.optionslab.engine.signals.NtfyRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * IraThinker's flow (28 Oct 2026, Boss: "Also check flow page on Irathinker and implement that too"; "Use live data from zerodha
 * instead"; "Dont add alert, just take orders from flow, and keep it for index only"): a PAPER arm that runs IraThinker's flow -
 * Scanner -> IraScore -> Time Machine -> Risk Gate ([FlowRules], ported from IraThinker's Analysis.java and BuyAlerts.java) - on
 * Zerodha's own stream and takes the plan that passes all four as a paper entry. NO ALERT: no ntfy post and no notification; the
 * order appears on its ticket, its row, the Flow page and Autopilot's timeline like the other arms.
 *
 * NOT PROVEN ([FlowRules.NOT_PROVEN]): research R110 - no IraThinker strategy passed; the ORB alert this flow ends in lost on every
 * index. Said on its ticket, its row, the Flow page, the diagnostics and by Jarvis. Logged as a Boss-requested unproven paper arm
 * (research/experiments/entries).
 *
 * SIGNAL SOURCE: the index itself (NIFTY, BANKNIFTY, FINNIFTY - IraThinker's alertable indices; never a stock, MCX, a commodity
 * or crypto), on Zerodha's stream ALONE: the whole day's 1-minute candles of the three indices and India VIX built from Zerodha's
 * ticks ([KiteStream.sessionCandles], never laid over by another feed), folded into 5-minute bars ([FlowRules.fold5]); yesterday's
 * close from the stream's tick. No Yahoo, no Upstox, no web price, no new instrument beyond the indices and VIX an index arm reads.
 * The Time Machine and the 20-day context read the past sessions' Zerodha 5-minute bars this arm recorded itself after each close
 * ([record], its own encrypted file, 60 sessions kept); until enough are kept the Time Machine stage refuses ("not enough Zerodha
 * history yet") - so the arm takes nothing on its first session, by design. A minute missing from the day (the stream started
 * late, or the app restarted mid-session) is a gap: no decision on it that day.
 *
 * The trade: the ATM option of the nearest expiry (the app's strike rule, [NtfyRules.strikeFor]) - a PUT on a breakdown (the only
 * kind IraThinker's gate lets through: India VIX must break up first), 1 lot, a LIMIT BUY at most 1% over the stream's price, MIS,
 * on the PAPER account. Exits: the mandatory premium stop resting in the paper book as a SELL SL-M ([FlowRules.premiumStop], 20%
 * under the fill, the app's "our stop"); IraThinker's alert exits on the index - the stop at the opening range's midpoint and the
 * target at 2R - judged on the stream's fresh index price at full cadence; flat at 15:15. The index's own expiry day is skipped
 * (the app's convention for intraday option arms), so a held option never expires the same day.
 *
 * Guards (none bypassed): the kill switch, the bot's stop for today (the daily loss limit), the day lock, the account guard, the
 * paper account's limits and the same-direction cap (both inside [Paper.place] for an automatic entry), [FlowRules.DAILY_TOP_N]
 * a day and one trade per index a day, market times and holidays ([FlowRules.entryRefusal]).
 *
 * Its switch: ON on paper, once, by this update on Boss's explicit ask - logged in Autopilot's timeline as Boss's, undone in one
 * tap; after that only Boss switches it. Live: never from here. Each paper buy and close is told to [ArmLive] (memory only), whose
 * live trade goes only with Live, "AI trades go live", Boss's PIN and this arm's own "Trade live" switch (off until Boss turns it on).
 *
 * Order and tick path: nothing here runs on either. It decides in the watch's pass (memory reads of the stream; the vault only for
 * its own book and session record, as every arm); its open positions are watched in the minute lane and the 15-second stop check
 * ([manageOnly]: memory and the paper book only) and its book is read FIRST after a restart ([PositionsFirst]).
 */
object FlowArm {
    private const val FILE = "flow_arm.vault"
    private const val HIST_FILE = "flow_sessions.vault"
    /** Its key on the position popup's "Exits" line ([com.optionslab.ira.ArmExits]). */
    const val EXITS_KEY = "flow"
    private val lock = Mutex()
    private val passLock = Mutex()
    @Volatile private var file: File? = null
    @Volatile private var histFile: File? = null
    private val written = Vault.LastWrite()
    private val histWritten = Vault.LastWrite()

    fun init(context: Context) {
        file = File(context.applicationContext.noBackupFilesDir, FILE)
        histFile = File(context.applicationContext.noBackupFilesDir, HIST_FILE)
        // Its paper trades for their live path ([ArmLive]; its live switch starts off - only Boss turns it on, with the PIN).
        ArmLive.register(LiveMirror.FLOW) {
            if (cache == null) null else _view.value.let { v ->
                (v.open + v.today.filter { !it.open }).map { ArmLive.PaperLeg(it.ref, FlowRules.SOURCE, if (it.open) it.qty else 0) }
            }
        }
    }

    /** The one-time switch-on of 28 Oct (Boss's ask); in [Book.migrated] once done, never again. */
    internal const val AUTO_ON = "boss_on_2026-10-28"

    /** TEST SEAM: true in the JVM tests' app, so other tests' books start with this arm off. Throws in a release build. */
    @Volatile internal var testNoAutoOn: Boolean = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /** One paper trade: the option, its premium stop resting in the paper book, and IraThinker's index stop and 2R target. */
    data class Pos(
        val index: String, val symbol: String, val right: String, val lots: Int, val qty: Int, val entry: Double, val entryTime: LocalDateTime,
        val orderId: String?, val stop: Double, val stopOrderId: String?, val idxStop: Double, val idxTarget: Double, val score: Int,
        val targetFirst: Double, val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null, val charges: Double = 0.0,
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        val ref: String get() = "flow:$index:$day"
        val put: Boolean get() = right == "PE"
        /** Rupees after charges, once sold. */
        val net: Double? get() = exit?.let { (it - entry) * qty - charges }
    }

    /**
     * One index's way through the flow today: the [stage] it stopped at ("Scanner", "IraScore", "Time Machine", "Risk Gate") or
     * "taken", its setup, scores and odds, and [why]. [final]: the day's decision for it (taken, or given up with the reason).
     */
    data class Candidate(
        val day: LocalDate, val index: String, val at: LocalDateTime, val stage: String, val setup: String, val score: Int?, val lite: Int?,
        val targetFirst: Double?, val stopFirst: Double?, val analogs: Int?, val why: String, val plan: Boolean, val final: Boolean = false,
    ) {
        val taken: Boolean get() = stage == TAKEN
    }
    const val TAKEN = "taken"

    private class Book(
        var armed: Boolean = false,
        val positions: MutableList<Pos> = ArrayList(),
        val candidates: MutableMap<String, Candidate> = LinkedHashMap(),
        val log: ArrayList<String> = ArrayList(),
        val migrated: MutableSet<String> = HashSet(),
        /** "day|index": an entry being placed (written before the order: a restart in between never orders it again). */
        val ordering: MutableSet<String> = HashSet(),
        /** "day|index" -> an entry order a cancel could not take out (settled next pass, never a second BUY beside it). */
        val pending: MutableMap<String, String> = HashMap(),
        /** Days whose sessions were recorded ([record]). */
        val recorded: MutableSet<String> = HashSet(),
    )

    /** What the row, the page and Jarvis read (memory only). */
    data class View(
        val armed: Boolean = false, val open: List<Pos> = emptyList(), val closed: List<Pos> = emptyList(), val today: List<Pos> = emptyList(),
        val candidates: List<Candidate> = emptyList(), val log: List<String> = emptyList(), val nets: List<Double> = emptyList(),
        val sessions: Int = 0, val lastPassMs: Long = 0L,
    ) {
        /** How many of today's candidates passed each stage (the page's counts): Scanner = a plan found. */
        fun passed(stage: FlowRules.Stage): Int = candidates.count { c ->
            if (!c.plan) false else if (c.taken) true else {
                val failedAt = FlowRules.Stage.entries.firstOrNull { it.words == c.stage } ?: return@count false
                failedAt.ordinal > stage.ordinal
            }
        }
        /** Today's skips with their reasons (a plan or a decided day that was not taken). */
        val skips: List<Candidate> get() = candidates.filter { !it.taken && (it.plan || it.final) }
        val takenToday: Int get() = today.size
    }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view
    @Volatile private var cache: Book? = null
    /** The session record: index -> day -> encoded 5-minute bars ([FlowRules.encode]); null: not read yet. */
    @Volatile private var sessions: Map<String, Map<LocalDate, String>>? = null
    @Volatile private var wipes = 0
    private const val LOG_KEPT = 200
    private const val CLOSED_KEPT = 400

    private fun ready() = file != null

    // ---- the book -----------------------------------------------------------------------------------------------------

    private fun posJson(p: Pos) = JSONObject().put("index", p.index).put("symbol", p.symbol).put("right", p.right).put("lots", p.lots).put("qty", p.qty)
        .put("entry", p.entry).put("at", p.entryTime.toString()).put("stop", p.stop)
        .put("score", p.score).put("charges", p.charges)
        .apply {
            if (p.idxStop.isFinite()) put("idxStop", p.idxStop); if (p.idxTarget.isFinite()) put("idxTarget", p.idxTarget)
            if (p.targetFirst.isFinite()) put("tf", p.targetFirst)
            p.orderId?.let { put("order", it) }; p.stopOrderId?.let { put("stopOrder", it) }
            p.exit?.let { put("exit", it) }; p.exitTime?.let { put("exitAt", it.toString()) }; p.why?.let { put("why", it) }
        }

    private fun dbl(o: JSONObject, k: String): Double? = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null

    private fun posOf(o: JSONObject) = Pos(o.getString("index"), o.getString("symbol"), o.getString("right"), o.getInt("lots"), o.getInt("qty"),
        o.getDouble("entry"), LocalDateTime.parse(o.getString("at")), o.optString("order").ifEmpty { null }, o.getDouble("stop"),
        o.optString("stopOrder").ifEmpty { null }, o.optDouble("idxStop", Double.NaN), o.optDouble("idxTarget", Double.NaN), o.optInt("score"), o.optDouble("tf", 0.0),
        dbl(o, "exit"), o.optString("exitAt").ifEmpty { null }?.let { LocalDateTime.parse(it) }, o.optString("why").ifEmpty { null }, o.optDouble("charges", 0.0))

    private fun candJson(c: Candidate) = JSONObject().put("day", c.day.toString()).put("index", c.index).put("at", c.at.toString()).put("stage", c.stage)
        .put("setup", c.setup).put("why", c.why).put("plan", c.plan).put("final", c.final)
        .apply {
            c.score?.let { put("score", it) }; c.lite?.let { put("lite", it) }; c.analogs?.let { put("an", it) }
            c.targetFirst?.takeIf { it.isFinite() }?.let { put("tf", it) }; c.stopFirst?.takeIf { it.isFinite() }?.let { put("sf", it) }
        }

    private fun candOf(o: JSONObject) = Candidate(LocalDate.parse(o.getString("day")), o.getString("index"), LocalDateTime.parse(o.getString("at")),
        o.getString("stage"), o.optString("setup"), if (o.has("score")) o.getInt("score") else null, if (o.has("lite")) o.getInt("lite") else null,
        dbl(o, "tf"), dbl(o, "sf"), if (o.has("an")) o.getInt("an") else null, o.optString("why"), o.optBoolean("plan"), o.optBoolean("final"))

    private fun book(): Book {
        cache?.let { return it }
        val gen = wipes
        val b = Book()
        val f = file
        var unreadable = false
        if (f != null) runCatching {
            val o = JSONObject(String(Vault.readFileSteady(f) ?: return@runCatching, Charsets.UTF_8))
            b.armed = o.optBoolean("armed")
            o.optJSONArray("positions")?.let { a -> for (i in 0 until a.length()) b.positions += posOf(a.getJSONObject(i)) }
            o.optJSONArray("candidates")?.let { a -> for (i in 0 until a.length()) candOf(a.getJSONObject(i)).let { b.candidates["${it.day}|${it.index}"] = it } }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
            o.optJSONArray("migrated")?.let { a -> for (i in 0 until a.length()) b.migrated += a.getString(i) }
            o.optJSONArray("ordering")?.let { a -> for (i in 0 until a.length()) b.ordering += a.getString(i) }
            o.optJSONObject("pending")?.let { m -> m.keys().forEach { b.pending[it] = m.getString(it) } }
            o.optJSONArray("recorded")?.let { a -> for (i in 0 until a.length()) b.recorded += a.getString(i) }
        }.onFailure { unreadable = true; if (f.exists() && !Vault.isErasing) runCatching { Vault.setAside(f) } }
        synchronized(written) { if (gen == wipes) cache = b }
        // 28 Oct, Boss's explicit ask: on once, on paper. A book that could not be read is never switched on by itself (Boss may have
        // switched it off): only marked done. Never in IraGoldAlgo.
        if (AUTO_ON !in b.migrated) {
            b.migrated += AUTO_ON
            val on = !unreadable && !testNoAutoOn && !com.optionslab.app.BuildConfig.GOLD
            if (on && !b.armed) {
                b.armed = true
                note(b, "switched on once by the 28 Oct update, on your ask - on paper, index options only; ${FlowRules.NOT_PROVEN}")
                runCatching { com.optionslab.app.ira.Autopilot.logFlowOn() }
            }
            save(b)
        } else publish(b)
        return b
    }

    private fun save(b: Book): Boolean {
        val closed = b.positions.count { !it.open }
        if (closed > CLOSED_KEPT) { var drop = closed - CLOSED_KEPT; b.positions.removeAll { !it.open && drop-- > 0 } }
        val keepFrom = today().minusDays(10)
        b.candidates.entries.removeAll { it.value.day.isBefore(keepFrom) }
        val o = JSONObject().put("armed", b.armed)
            .put("positions", JSONArray(b.positions.map { posJson(it) }))
            .put("candidates", JSONArray(b.candidates.values.map { candJson(it) }))
            .put("log", JSONArray(b.log.takeLast(LOG_KEPT)))
            .put("migrated", JSONArray(b.migrated.sorted()))
            .put("ordering", JSONArray(b.ordering.toList()))
            .put("pending", JSONObject().apply { b.pending.forEach { (k, v) -> put(k, v) } })
            .put("recorded", JSONArray(b.recorded.sorted().takeLast(80)))
        return synchronized(written) {
            if (cache !== b) return false
            val ok = file?.let { f -> runCatching { written.write(f, o.toString().toByteArray(Charsets.UTF_8)) }.getOrDefault(false) } ?: false
            publish(b)
            ok
        }
    }

    private fun publish(b: Book) {
        if (b !== cache) return
        val today = today()
        val closed = b.positions.filter { !it.open }
        _view.value = View(b.armed, b.positions.filter { it.open }, closed.takeLast(50), b.positions.filter { it.day == today },
            b.candidates.values.filter { it.day == today }.sortedBy { FlowRules.INDICES.indexOf(it.index) }, b.log.takeLast(30),
            closed.map { it.net ?: 0.0 }, sessions?.values?.flatMap { it.keys }?.toSet()?.size ?: _view.value.sessions, lastPassMs)
        // The position popup's "Exits" line for each open trade (memory only).
        runCatching { com.optionslab.ira.ArmExits.set(EXITS_KEY, b.positions.filter { it.open }.map { exitsOf(it) }) }
    }

    private fun exitsOf(p: Pos) = com.optionslab.ira.ArmExits.Entry(EXITS_KEY, FlowRules.LABEL, false, p.symbol, p.stop, null, NtfyRules.SQ,
        (if (p.idxStop.isFinite() && p.idxTarget.isFinite()) "${p.index} index stop ${NtfyParser.num(p.idxStop)}, target ${NtfyParser.num(p.idxTarget)} (2R) · " else "") +
            FlowRules.NOT_PROVEN)

    private fun note(b: Book, text: String) {
        b.log += "${now()} $text"
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        runCatching { Diag.record("flow", "${FlowRules.LABEL}: $text") }
    }

    private fun now(): LocalDateTime = runCatching { Market.now().toLocalDateTime().withNano(0) }.getOrElse { LocalDateTime.now().withNano(0) }
    private fun today(): LocalDate = runCatching { Market.today() }.getOrElse { LocalDate.now() }
    private fun nowSec(t: LocalDateTime): Long = t.atZone(com.optionslab.engine.IST).toEpochSecond()

    /** Read the book (start-up's positions-first step, the row and the page call it, off the main thread). */
    suspend fun refresh(): View { if (ready()) lock.withLock { book() }; return _view.value }

    /** An open paper trade is held (memory only). */
    fun holding(): Boolean = _view.value.open.isNotEmpty()

    @Volatile private var pendingNow = false
    @Volatile var lastPassMs: Long = 0L
        private set

    /**
     * The watch needs this arm's pass now (memory only): a trade held or an entry order working; or switched on, on a trading day,
     * 09:14 to 15:31 (the opening range, the decisions, and the session's record just after the 15:30 close).
     */
    fun wantsPass(minute: Int = runCatching { Market.minuteNow() }.getOrDefault(0)): Boolean {
        if (com.optionslab.app.BuildConfig.GOLD) return false
        if (holding() || pendingNow) return true
        if (!_view.value.armed || minute !in (9 * 60 + 14)..(15 * 60 + 31)) return false
        return runCatching { Market.isTradingDay() }.getOrDefault(false)
    }

    // ---- Boss's switch --------------------------------------------------------------------------------------------------

    /**
     * Switch it on or off - on paper. Off buys nothing new: what it holds still closes by its own exits. [log]: Boss's flip (logged
     * in Autopilot's timeline); false for the timeline's own Undo.
     */
    suspend fun setArmed(on: Boolean, log: Boolean = true): String {
        if (!ready()) return "${FlowRules.LABEL} is not ready yet."
        return lock.withLock {
            val b = book()
            if (b.armed == on) return@withLock "${FlowRules.LABEL} is already ${if (on) "on" else "off"} (paper)."
            b.armed = on
            note(b, if (on) "switched on, on paper (${FlowRules.NOT_PROVEN})" else "switched off" +
                if (b.positions.any { it.open }) "; what it holds still closes by its own exits" else "")
            save(b)
            if (log) runCatching { com.optionslab.app.ira.Autopilot.flowSwitchedByBoss(on) }
            if (on) "${FlowRules.LABEL} on, on paper, index options only. ${FlowRules.NOT_PROVEN}." else "${FlowRules.LABEL} off. Nothing new is bought."
        }
    }

    fun diagLine(hideNet: Boolean = false): String {
        val v = _view.value
        val net = if (hideNet) PassedArmsHost.NET_HIDDEN else "%,.0f".format(Locale.ENGLISH, v.nets.sum())
        return "${FlowRules.LABEL}: ${if (v.armed) "on (paper)" else "off"} · open ${v.open.size} · today ${v.takenToday} taken, ${v.skips.size} skipped · " +
            "${v.nets.size} closed $net after charges · ${v.sessions} Zerodha sessions kept · ${FlowRules.NOT_PROVEN}"
    }

    /** Jarvis's answer (an unlocked phone only): what the flow did today and why, what is held, the record. No rupees on a locked phone. */
    suspend fun talk(locked: Boolean = false): String {
        val v = refresh()
        val state = if (v.armed) "on, on paper, index options only" else "off"
        val today = if (v.candidates.isEmpty()) "nothing judged yet today" else v.candidates.joinToString("; ") { c ->
            "${c.index} ${if (c.taken) "taken" else "${c.stage}: ${c.why}"}" }
        val held = if (v.open.isEmpty()) "nothing held" else "holding " + v.open.joinToString { it.symbol }
        val record = if (locked) "" else " Its record: ${v.nets.size} closed, %s after charges.".format(Locale.ENGLISH, rs(v.nets.sum()))
        return "${FlowRules.LABEL} is $state - ${FlowRules.NOT_PROVEN}. Today: $today. $held.$record"
    }

    private fun rs(x: Double) = (if (x < 0) "-₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

    // ---- the pass ------------------------------------------------------------------------------------------------------

    /** The minute lane and the 15-second stop check (open positions first): only the open trades' exits. Nothing held: returns at once. */
    suspend fun manageOnly() {
        if (com.optionslab.app.BuildConfig.GOLD || !ready() || !holding()) return
        lock.withLock {
            val b = book()
            val changed = runCatching { exits(b, now()) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true }
            if (changed) save(b) else publish(b)
            Unit
        }
    }

    /** The watch's pass (and its bar-close wake): the open trades' exits first, then each index's flow, then the day's record. */
    suspend fun tick() {
        if (com.optionslab.app.BuildConfig.GOLD || !ready()) return
        passLock.withLock {
            val now = now()
            lock.withLock {
                val b = book()
                var changed = runCatching { exits(b, now) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true }
                val trading = runCatching { Market.isTradingDay(now.toLocalDate()) }.getOrDefault(false)
                if (trading && b.armed) {
                    // The flow's instruments followed on the stream while it decides (a 3-minute touch, as every index arm's read).
                    runCatching { KiteStream.touch(KiteStream.SESSION_TOKENS) }
                    changed = runCatching { decideAll(b, now) }.getOrElse { note(b, "flow pass failed: ${it.javaClass.simpleName}"); true } || changed
                }
                if (trading && now.toLocalTime() >= LocalTime.of(15, 30) && now.toLocalDate().toString() !in b.recorded)
                    changed = runCatching { record(b, now) }.getOrElse { note(b, "session record failed: ${it.javaClass.simpleName}"); false } || changed
                lastPassMs = System.currentTimeMillis()
                pendingNow = b.pending.isNotEmpty()
                if (changed) save(b) else publish(b)
                Unit
            }
        }
    }

    /** TEST SEAM: the session's minutes of an index token (JVM tests only; null in the app: Zerodha's stream candles). */
    @Volatile internal var testMinutes: ((Long) -> List<Upstox.Bar>)? = null

    /** [token]'s 1-minute candles today from Zerodha's stream alone ([KiteStream.sessionCandles]), 09:15 to [nowSec]. Memory only. */
    private fun minutes(token: Long, day: LocalDate, nowSec: Long): List<Upstox.Bar> {
        testMinutes?.let { return it(token) }
        val open = day.atTime(FlowRules.OPEN).atZone(com.optionslab.engine.IST).toEpochSecond()
        val out = ArrayList<Upstox.Bar>()
        var t = open
        while (t + 60 <= nowSec && t < open + 375 * 60) {
            KiteStream.sessionCandles.candle(token, t)?.let { out += it.bar() }
            t += 60
        }
        return out
    }

    private fun tokenOf(u: String): Long? = runCatching { Broker.indexToken(u) }.getOrNull()

    /** The session record, read once (the vault; the pass only - never the stop lanes). */
    private fun history(): Map<String, Map<LocalDate, String>> {
        sessions?.let { return it }
        val f = histFile ?: return emptyMap()
        val m = HashMap<String, Map<LocalDate, String>>()
        runCatching {
            val o = JSONObject(String(Vault.readFileSteady(f) ?: return@runCatching, Charsets.UTF_8))
            for (u in o.keys()) {
                val days = o.getJSONObject(u)
                m[u] = java.util.TreeMap<LocalDate, String>().apply { days.keys().forEach { d -> put(LocalDate.parse(d), days.getString(d)) } }
            }
        }.onFailure { if (f.exists() && !Vault.isErasing) runCatching { Vault.setAside(f) } }
        sessions = m
        return m
    }

    /** Each index's flow at [now] (the watch's pass, under the lock). True when the book changed. */
    private suspend fun decideAll(b: Book, now: LocalDateTime): Boolean {
        val day = now.toLocalDate()
        val sec = nowSec(now)
        var changed = false
        // An entry order a cancel could not take out: settled first, never a second BUY beside it.
        for ((key, id) in b.pending.toMap()) changed = settle(b, key, id, now) || changed
        val vixTok = tokenOf(FlowRules.VIX)
        val vix = vixTok?.let { FlowRules.fold5(minutes(it, day, sec), day, sec).bars }.orEmpty()
        val hist = history()
        val capital = runCatching { Paper.capital.toDouble() }.getOrDefault(Double.NaN)
        for (u in FlowRules.INDICES) {
            val key = "$day|$u"
            val prev = b.candidates[key]
            if (prev?.final == true) continue
            val tok = tokenOf(u)
            val ones = tok?.let { minutes(it, day, sec) }.orEmpty()
            val fold = FlowRules.fold5(ones, day, sec)
            val minute = now.hour * 60 + now.minute
            val dayOver = minute >= 15 * 60
            val c: Candidate = when {
                ones.isEmpty() -> cand(day, u, now, FlowRules.Stage.SCANNER.words, "NONE", "no Zerodha minutes yet (stream off, logged out or the market not open)", false, final = dayOver)
                fold.gapAt != null -> cand(day, u, now, FlowRules.Stage.SCANNER.words, "NONE",
                    "a gap in Zerodha's minutes at ${hhmm(fold.gapAt ?: 0L)} (the stream started late or dropped) - no decision on a gap today", false, final = true)
                else -> {
                    val past = hist[u].orEmpty().filterKeys { it.isBefore(day) }
                    val histBars = past.values.flatMap { FlowRules.decode(it) }
                    val daily = past.map { (d, s) -> d to (FlowRules.decode(s).lastOrNull()?.close ?: Double.NaN) }.filter { !it.second.isNaN() }
                    val prevClose = tok?.let { t -> runCatching { KiteStream.seen(t)?.tick?.close }.getOrNull() }?.takeIf { it > 0 }
                    val v = FlowRules.judge(u, fold.bars, histBars, vix, day, sec, capital, prevClose, daily)
                    if (v.take) take(b, u, v, now, prev) else fromVerdict(day, u, now, v, prev, sec, dayOver)
                }
            }
            // Saved only when something but the time of the look changed (never a vault write every minute for nothing).
            if (prev == null || c.copy(at = prev.at) != prev) {
                b.candidates[key] = c
                changed = true
                if (c.final && !c.taken) note(b, "$u: not taken - ${c.stage}: ${c.why}")
            }
        }
        return changed
    }

    private fun hhmm(sec: Long): String = FlowRules.istTime(sec).let { "%02d:%02d".format(Locale.ENGLISH, it.hour, it.minute) }

    private fun cand(day: LocalDate, u: String, now: LocalDateTime, stage: String, setup: String, why: String, plan: Boolean, final: Boolean = false,
                     v: FlowRules.Verdict? = null) = Candidate(day, u, now, stage, setup, v?.score, v?.lite, v?.odds?.targetFirst, v?.odds?.stopFirst,
        v?.odds?.analogFires, why, plan, final)

    /**
     * A verdict that did not pass, as the day's candidate. Final once nothing can change it: its plan no longer fresh (IraThinker
     * fires a break within 10 minutes of its bar or never), or 15:00 passed. A "found too late" keeps the reason it had before.
     */
    private fun fromVerdict(day: LocalDate, u: String, now: LocalDateTime, v: FlowRules.Verdict, prev: Candidate?, sec: Long, dayOver: Boolean): Candidate {
        val plan = v.entry != null
        val stale = plan && !FlowRules.fresh(v.breakTs, sec)
        if (stale && prev != null && prev.plan && !prev.why.contains("too late")) return prev.copy(at = now, final = true)
        val why = if (dayOver && !plan) "no plan today (${v.why})" else v.why
        return cand(day, u, now, v.reached.words, v.setup.name, why, plan, stale || dayOver, v)
    }

    // ---- the entry -------------------------------------------------------------------------------------------------------

    /** TEST SEAM: the listed contracts and the index's price (JVM tests only; null in the app). */
    @Volatile internal var testListed: ((String) -> List<Upstox.Contract>)? = null
    @Volatile internal var testSpot: ((String) -> Double?)? = null

    /** The index's listed options from the list saved on the phone today: never a download in the pass. */
    private fun listed(u: String): List<Upstox.Contract> {
        testListed?.let { return it(u) }
        val saved = runCatching { Market.cachedContracts() }.getOrNull()
        if (saved == null || saved.first != today()) { runCatching { Market.refreshContractsInBackground() }; return emptyList() }
        return saved.second.filter { it.underlying == u }
    }

    /** The index's price from Zerodha's stream (at most 5 s old), or null. Memory only, never the network. */
    private fun spot(u: String): Double? {
        testSpot?.let { return it(u) }
        return runCatching { tokenOf(u)?.let { KiteStream.tick(it)?.last }?.takeIf { it > 0 } }.getOrNull()
    }

    /** [c]'s price from Zerodha's stream (at most 5 s old; the option followed from now on), or null. Memory only. */
    private fun optionQuote(c: Paper.Contract): com.optionslab.engine.sandbox.Quote? = runCatching {
        val list = Broker.cachedInstruments() ?: return@runCatching null
        val tok = Broker.find(list, c.underlying, c.expiry, c.strike, c.right)?.token?.takeIf { it > 0 } ?: return@runCatching null
        KiteStream.touch(listOf(tok))
        val t = KiteStream.tick(tok) ?: return@runCatching null
        if (t.last <= 0) null else com.optionslab.engine.sandbox.Quote(t.last, bid = t.bid ?: 0.0, ask = t.ask ?: 0.0)
    }.getOrNull()

    private suspend fun stoppedToday(): Boolean =
        runCatching { Strategies.stoppedToday() }.getOrDefault(true) || runCatching { LossBreaker.trippedToday() }.getOrDefault(false)

    private fun chargesOf(orderId: String?): Double =
        if (orderId == null) 0.0 else Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    /** A plan that passed the flow: the guards, then the paper order. Returns the day's candidate for [u]. */
    private suspend fun take(b: Book, u: String, v: FlowRules.Verdict, now: LocalDateTime, prev: Candidate?): Candidate {
        val day = now.toLocalDate()
        val key = "$day|$u"
        fun waiting(why: String) = cand(day, u, now, FlowRules.Stage.RISK_GATE.words, v.setup.name, "passed the flow; not taken: $why", true, v = v)
        fun given(why: String) = waiting(why).copy(final = true)
        // Its BUY was being placed when the app stopped: never placed again (a fill is in the paper book, kept by the orphan watch).
        if (key in b.ordering && key !in placing) { b.ordering.remove(key); return given("interrupted while its order was placed - not ordered again (check Paper for a fill)") }
        if (key in b.pending) return waiting("its entry order is still working")
        val list = listed(u)
        val exp = NtfyRules.expiryFor(list.map { it.expiry }, day, NtfyParser.Expiry.Nearest)
        val refusal = FlowRules.entryRefusal(u, runCatching { Market.isTradingDay(day) }.getOrDefault(false), now.toLocalTime(), exp == day, b.armed,
            runCatching { AppSettings.load().guardKill }.getOrDefault(true), stoppedToday(), runCatching { DayLockGuard.refusal(false) }.getOrNull(),
            KiteStream.status.value == KiteStream.Status.LIVE, b.positions.count { it.day == day } + b.pending.size,
            b.positions.any { it.day == day && it.index == u })
        if (refusal != null) return waiting(refusal)
        if (list.isEmpty() || exp == null) return waiting("waiting for today's $u options list")
        val right = if (v.put) Right.PE else Right.CE
        val strikes = list.filter { it.expiry == exp && it.right == right }.map { it.strike }
        val px = spot(u) ?: return waiting("waiting for $u's price from Zerodha's stream")
        val strike = NtfyRules.strikeFor(strikes, px, right, NtfyParser.StrikeRule.ATM) ?: return waiting("no $u strike for the rule")
        val c = Paper.ticketContract(u, exp, strike, right) ?: return waiting("$u ${NtfyParser.num(strike)} ${right.name} is not in the phone's options list")
        if (c.lotSize <= 0) return waiting("lot size unknown")
        if (b.positions.any { it.open && it.symbol == c.symbol } || Paper.state.positions.any { it.symbol == c.symbol && it.quantity != 0 })
            return given("${c.symbol} is already held - never two positions on one contract")
        val q = optionQuote(c) ?: return waiting("waiting for Zerodha's price of ${c.symbol} (followed on the stream now)")
        val last = q.ltp
        val limit = NtfyRules.round2(NtfyRules.tickDown(last * 1.01) + 0.05)
        val stop0 = FlowRules.premiumStop(last)
        val snap = runCatching { Paper.snapshot() }.getOrNull()
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", 1, limit), snap?.let { Guard.paperAccount(it) }, paper = true)
        if (refusals.isNotEmpty()) return waiting("refused by the account guard: " + refusals.joinToString(" "))
        val verdict = OrderVerdicts.stamp(OrderVerdicts.paperFields(c, "BUY", 1, "LIMIT", "MIS", limit, null, day, FlowRules.OWNER, stop = stop0),
            refusals, "paper guard")
        // Written before the order: a restart between the order and the book's save never orders it again.
        b.ordering += key; placing += key
        if (!save(b)) { b.ordering.remove(key); placing.remove(key); return waiting("the book could not be saved before the order") }
        val res = try {
            Paper.place(c, "BUY", 1, "LIMIT", "MIS", limit, null, known = q, auto = FlowRules.OWNER, verdict = verdict, stop = stop0)
        } finally { b.ordering.remove(key); placing.remove(key) }
        val fill = res.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (fill != null) return bought(b, u, v, c, res.orderId, fill.quantity, fill.price, now)
        val id = res.orderId?.takeIf { res.ok } ?: return waiting("the paper order was refused (${res.message})")
        runCatching { Paper.cancel(id, "unfilled_limit") }
        val o = Paper.state.orders.firstOrNull { it.orderId == id }
        return when {
            o == null || o.status == "cancelled" || o.status == "rejected" -> waiting("limit ${NtfyParser.num(limit)} for ${c.symbol} not filled; tried again")
            o.status == "complete" -> bought(b, u, v, c, id, o.quantity, o.averagePrice?.toDouble() ?: limit, now)
            else -> { b.pending[key] = id; pendingNow = true; waiting("its entry order is still working") }
        }
    }

    /** This run's own orders being placed (memory): one in [Book.ordering] but not here was interrupted by a restart. */
    private val placing: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** An entry order a cancel could not take out ([Book.pending]): filled, gone, or cancelled again. */
    private suspend fun settle(b: Book, key: String, id: String, now: LocalDateTime): Boolean {
        val o = Paper.state.orders.firstOrNull { it.orderId == id }
        return when {
            o == null || o.status == "cancelled" || o.status == "rejected" -> { b.pending.remove(key); true }
            o.status == "complete" -> {
                b.pending.remove(key)
                val c = Paper.contractOf(o.symbol) ?: return true
                val prev = b.candidates[key]
                val u = key.substringAfter('|')
                // The plan's index levels are the candidate's; the levels are read again from the day's flow when unknown.
                val pos = Pos(u, c.symbol, c.right.name, (o.quantity / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1), o.quantity,
                    o.averagePrice?.toDouble() ?: return true, now, id, FlowRules.premiumStop(o.averagePrice?.toDouble() ?: 0.0), null,
                    lastLevels[key]?.first ?: Double.NaN, lastLevels[key]?.second ?: Double.NaN, prev?.score ?: 0, prev?.targetFirst ?: 0.0)
                restStop(b, pos, c)
                if (prev != null) b.candidates[key] = prev.copy(stage = TAKEN, why = "taken: ${c.symbol} (a late fill of its entry order) · paper", final = true)
                true
            }
            else -> { runCatching { Paper.cancel(id, "unfilled_limit") }; false }
        }
    }

    /** The plan's index stop and 2R target by "day|index", for an entry order that fills on a later pass (memory). */
    private val lastLevels = java.util.concurrent.ConcurrentHashMap<String, Pair<Double, Double>>()

    /** [qty] of [c] bought at [price] for [u]'s plan [v]: its stop resting as a paper SELL SL-M, its live path told. */
    private suspend fun bought(b: Book, u: String, v: FlowRules.Verdict, c: Paper.Contract, orderId: String?, qty: Int, price: Double, now: LocalDateTime): Candidate {
        val day = now.toLocalDate()
        lastLevels["$day|$u"] = (v.stop ?: Double.NaN) to (v.target ?: Double.NaN)
        orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${FlowRules.OWNER} · ${FlowRules.NOT_PROVEN} · entry") } }
        val lots = (qty / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
        val pos0 = Pos(u, c.symbol, c.right.name, lots, qty, price, now, orderId, FlowRules.premiumStop(price), null, v.stop ?: Double.NaN,
            v.target ?: Double.NaN, v.score ?: 0, v.odds?.targetFirst ?: 0.0, charges = chargesOf(orderId))
        val pos = restStop(b, pos0, c)
        val entry = v.entry ?: 0.0
        // The time machine's reality check (shadow only; background): IraThinker's index stop and 2R target, out by 15:15.
        runCatching { TimeMachineHost.afterEntry(TimeMachineHost.Entry(FlowRules.SOURCE, FlowRules.OWNER, pos.ref, u, if (pos.put) -1 else 1, now,
            v.stop?.let { kotlin.math.abs(it - entry) }?.takeIf { it > 0 }, v.target?.let { kotlin.math.abs(entry - it) }?.takeIf { it > 0 },
            exitAt = NtfyRules.SQ)) }
        // Its live path ([ArmLive], off until Boss switches it on): the same option and lot, a LIMIT 1% over the fill at most, the stop resting.
        runCatching { ArmLive.entered(ArmLive.Ask(FlowRules.SOURCE, pos.ref, c, 1, qty, price, stop = pos.stop, limit = price * 1.01)) }
        note(b, "$u: bought ${c.symbol} $qty @ ${NtfyParser.num(price)} · paper · premium stop ${NtfyParser.num(pos.stop)} resting · index stop " +
            "${NtfyParser.num(pos.idxStop)}, target ${NtfyParser.num(pos.idxTarget)} (2R) · IraScore ${v.score} · ${FlowRules.NOT_PROVEN}")
        return cand(day, u, now, TAKEN, v.setup.name, "taken: ${c.symbol} $qty @ ${NtfyParser.num(price)} · paper · ${v.why}", true, final = true, v = v)
    }

    /** [p] added to the book with its premium stop resting in the paper book (a SELL SL-M). */
    private suspend fun restStop(b: Book, p: Pos, c: Paper.Contract): Pos {
        val stopId = runCatching { Paper.place(c, "SELL", p.lots, "SL-M", "MIS", null, p.stop, known = com.optionslab.engine.sandbox.Quote(p.entry)) }
            .getOrNull()?.takeIf { it.ok }?.orderId?.also { runCatching { Strategies.tagOwner("paper:$it", "${FlowRules.OWNER} · stop") } }
        val pos = p.copy(stopOrderId = stopId)
        b.positions += pos
        return pos
    }

    // ---- the exits -------------------------------------------------------------------------------------------------------

    /** Each open trade's exit, when due. Memory and the paper book only (the stream's prices). True when [b] changed. */
    private suspend fun exits(b: Book, now: LocalDateTime): Boolean {
        var changed = false
        for (i in b.positions.indices) {
            val p = b.positions[i]
            if (!p.open) continue
            val next = runCatching { exitOne(p, now) }.getOrNull() ?: continue
            if (next != p) {
                b.positions[i] = next; changed = true
                if (!next.open) {
                    runCatching { ArmLive.closed(next.ref, next.why ?: "exit") }
                    note(b, "sold ${p.symbol} (${next.why}): %s after charges · paper".format(Locale.ENGLISH, rs(next.net ?: 0.0)))
                }
            }
        }
        return changed
    }

    private suspend fun exitOne(start: Pos, now: LocalDateTime): Pos {
        var p = start
        val c = Paper.contractOf(p.symbol) ?: return p
        // Its resting stop filled in the paper book: that is the exit.
        p.stopOrderId?.let { id ->
            val o = Paper.state.orders.firstOrNull { it.orderId == id }
            if (o != null && o.status == "complete") return closed(p, o.averagePrice?.toDouble() ?: p.stop, o.updateTimestamp, "premium stop", chargesOf(id))
        }
        // Gone from the paper book without this arm selling it (the paper square-off, the loss limit, or Boss).
        if (Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity } <= 0) {
            p.stopOrderId?.let { runCatching { Paper.cancel(it, "position_closed") } }
            val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
            return closed(p, t?.price?.toDouble() ?: p.entry, t?.timestamp ?: now, "closed_elsewhere", t?.charges?.toDouble() ?: 0.0)
        }
        if (!now.isBefore(p.day.atTime(NtfyRules.SQ)) || p.day.isBefore(now.toLocalDate())) return sellRest(p, c, "square-off 15:15")
        // IraThinker's alert exits on the index: the range midpoint (stop) and 2R (target), on the stream's fresh price only.
        FlowRules.indexExit(p.put, spot(p.index), p.idxStop, p.idxTarget)?.let { return sellRest(p, c, it) }
        // No stop resting in the paper book: the arm checks the premium stop itself and puts the SL-M back while above it.
        if (!stopResting(p.stopOrderId)) {
            val ltp = runCatching { Paper.bookNow(c)?.last }.getOrNull()?.takeIf { it > 0 } ?: optionQuote(c)?.ltp
            if (ltp != null && ltp <= p.stop) return sellRest(p, c, "premium stop")
            val again = if (closingWorks(p.symbol)) null else runCatching { Paper.place(c, "SELL", p.lots, "SL-M", "MIS", null, p.stop) }
                .getOrNull()?.takeIf { it.ok }?.orderId?.also { runCatching { Strategies.tagOwner("paper:$it", "${FlowRules.OWNER} · stop") } }
            if (again != null) p = p.copy(stopOrderId = again)
        }
        return p
    }

    private fun closingWorks(symbol: String): Boolean =
        Paper.state.orders.any { it.symbol == symbol && it.action == "SELL" && (it.status == "open" || it.status == "trigger pending") }

    private fun stopResting(id: String?): Boolean {
        val o = id?.let { x -> Paper.state.orders.firstOrNull { it.orderId == x } } ?: return false
        return o.status != "cancelled" && o.status != "rejected" && o.status != "complete"
    }

    /** Sell [p] at market (its resting stop taken out first; if it filled meanwhile, that is the exit). Reduce-only. */
    private suspend fun sellRest(p: Pos, c: Paper.Contract, why: String): Pos {
        p.stopOrderId?.let { id ->
            runCatching { Paper.cancel(id, "exit:$why") }
            val o = Paper.state.orders.firstOrNull { it.orderId == id }
            if (o != null && o.status == "complete") return closed(p, o.averagePrice?.toDouble() ?: p.stop, o.updateTimestamp, "premium stop", chargesOf(id))
        }
        val r = Paper.place(c, "SELL", p.lots, "MARKET", "MIS", null, null, known = optionQuote(c), exit = true)
        val f = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull() ?: run {
            if (!r.ok && Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity } <= 0) {
                val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
                return closed(p.copy(stopOrderId = null), t?.price?.toDouble() ?: p.entry, t?.timestamp ?: now(), "closed_elsewhere", t?.charges?.toDouble() ?: 0.0)
            }
            // Not filled (no fresh price): the stop goes back into the paper book at once, and the exit is tried next pass.
            r.orderId?.takeIf { r.ok }?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
            val again = if (closingWorks(p.symbol)) null else runCatching { Paper.place(c, "SELL", p.lots, "SL-M", "MIS", null, p.stop) }.getOrNull()?.takeIf { it.ok }?.orderId
            return p.copy(stopOrderId = again)
        }
        r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${FlowRules.OWNER} · exit") } }
        return closed(p.copy(stopOrderId = null), f.price, now(), why, chargesOf(r.orderId))
    }

    private fun closed(p: Pos, price: Double, at: LocalDateTime, why: String, charges: Double): Pos =
        p.copy(exit = price, exitTime = at, why = why, charges = p.charges + charges, stopOrderId = null)

    // ---- the session record ----------------------------------------------------------------------------------------------

    /**
     * After the 15:30 close: today's Zerodha 5-minute bars of each index kept for the Time Machine and the 20-day context (its own
     * encrypted file; the newest [FlowRules.SESSIONS_KEPT] sessions). Once a day. True when the book changed.
     */
    private fun record(b: Book, now: LocalDateTime): Boolean {
        val day = now.toLocalDate()
        val sec = nowSec(now)
        val old = history()
        val next = HashMap<String, Map<LocalDate, String>>(old)
        var kept = 0
        for (u in FlowRules.INDICES) {
            val tok = tokenOf(u) ?: continue
            val bars = FlowRules.fold5(minutes(tok, day, sec), day, sec).bars
            if (bars.size < 10) continue
            next[u] = FlowRules.keep(old[u].orEmpty(), day to FlowRules.encode(bars))
            kept++
        }
        b.recorded += day.toString()
        if (kept == 0) { note(b, "today's session not recorded: too few whole Zerodha 5-minute bars"); return true }
        val o = JSONObject()
        for ((u, days) in next) o.put(u, JSONObject().apply { days.forEach { (d, s) -> put(d.toString(), s) } })
        val f = histFile
        val ok = f != null && runCatching { synchronized(written) { if (cache === b) histWritten.write(f, o.toString().toByteArray(Charsets.UTF_8)) else false } }.getOrDefault(false)
        if (ok) sessions = next
        note(b, if (ok) "today's Zerodha 5-minute bars recorded for the Time Machine ($kept indices; ${next.values.flatMap { it.keys }.toSet().size} sessions kept)"
            else "today's session could not be written")
        return true
    }

    /** TEST ONLY: the session record as if read from its file (debug builds only). */
    internal fun sessionsForTest(m: Map<String, Map<LocalDate, String>>) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }
        sessions = m
    }

    // ---- tests and the erase ---------------------------------------------------------------------------------------------

    /** For tests: the book forgotten (its files stay: a test reads them again, as after a restart). */
    internal fun wipe() {
        synchronized(written) {
            wipes++
            cache = null; sessions = null; _view.value = View(); testListed = null; testSpot = null; testMinutes = null
            pendingNow = false; lastPassMs = 0L; placing.clear(); lastLevels.clear()
            written.forget(); histWritten.forget()
        }
        runCatching { com.optionslab.ira.ArmExits.set(EXITS_KEY, emptyList()) }
    }

    /** "Erase everything" ([PersonalStores]): [wipe], and its files deleted. Memory and files only. */
    internal fun wipeForErase() {
        synchronized(written) {
            for (f in filesForErase()) runCatching { f.delete() }
            wipe()
        }
    }

    /** Anything of the book held in memory (the erase's check; memory only). */
    internal fun holdsForErase(): Boolean = runCatching {
        val v = _view.value
        v.armed || v.open.isNotEmpty() || v.closed.isNotEmpty() || v.candidates.isNotEmpty() || v.log.isNotEmpty() ||
            cache?.let { b -> b.positions.isNotEmpty() || b.log.isNotEmpty() || b.candidates.isNotEmpty() } == true || sessions?.isNotEmpty() == true
    }.getOrDefault(true)

    /** Its book, its session record, and any copy of either set aside as unreadable. */
    internal fun filesForErase(): List<File> = listOfNotNull(file, histFile).flatMap { f ->
        listOf(f) + f.parentFile?.listFiles { x -> x.name.startsWith(f.name + ".unreadable.") }.orEmpty()
    }
}
