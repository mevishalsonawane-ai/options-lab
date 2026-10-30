package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.options.OptionMath
import com.optionslab.engine.options.OptionType
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.PassedArms
import com.optionslab.engine.orb.S5Trail
import com.optionslab.engine.sandbox.SandboxCosts
import com.optionslab.engine.sandbox.SandboxEvent
import com.optionslab.engine.signals.NtfyParser
import com.optionslab.engine.signals.NtfyRules
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * S5 Trail (28 Oct 2026, Boss: "Add s5 trail to arms directly, if it does not perform I will turn it off"): research R123's morning
 * pick with R128's trailing exit ([S5Trail]) as a PAPER arm. IT FAILED THE RESEARCH GATES - Boss's exception (CLAUDE.md, 28 Oct),
 * said on its ticket, its row, the diagnostics and by Jarvis ([S5Trail.NOT_PROVEN]). 30 Oct (Boss: "Make them live s5 and csid startergies
 * live too"; CLAUDE.md, 30 Oct): its paper trades are mirrored live by [ArmLive] ([LiveMirror.S5]) through the same gates as every arm
 * (Live with the PIN, "AI trades go live", its own Trade live switch, every guard): the entry ([ArmLive.entered], 1 lot as on paper),
 * its resting stop as the live stop's level, and every paper exit (the lock, the stop, the square-off) sells the live trade.
 *
 * SIGNAL SOURCE: the index (the default). At the 09:20, 09:25 and 09:30 bar closes, for each of NIFTY, BANKNIFTY, FINNIFTY,
 * MIDCPNIFTY and SENSEX, R123's network reads the last 120 index minutes of the index and the other four, India VIX, the calendar
 * and the option chain's state (context only: ATM premiums, IV, skew, PCR, OI and volume), and buys the ATM option of its best
 * cell-side when the expected net is at least ₹300. The reads: today's index minutes and the earlier sessions the passed arms
 * already fetch ([PassedArmsHost.indexBars], [PassedArmsHost.history]); the chain from Upstox's intraday candles of the strikes
 * near the money, read once per index at each decision (outside the lock, never on the tick or order path).
 *
 * IV: the network learned Dhan's IV; the app computes Black-76 IV on the index price (rate 0) and adds Dhan's measured offset by
 * side and strike ([IV_SHIFT]; research/hunt/r128_missing/s5_app_check.py, design-period samples only).
 *
 * The trade: 1 lot of the ATM option (the index close at the decision, half up to the strike step) of the nearest expiry, a MARKET
 * BUY on the PAPER account. Exits: the chosen cell's stop (−₹1,000 or −₹2,000 net after charges) resting in the paper book as a
 * SELL SL-M; the profit lock - once up ₹1,000 net, out when the net at a minute's close falls to 60% of its best; else 15:15 (the
 * paper book's MIS square-off; R128 held to 15:20, so this is 5 minutes earlier) or 15:05 on expiry day. One trade per index at a
 * time, at most 5 a day.
 *
 * Guards (none bypassed): the kill switch, the bot's stop for today, the day lock, the account guard, the paper account's limits and
 * the same-direction cap (inside [Paper.place]), market days and times.
 *
 * Its switch: ON on paper, once, by this update on Boss's ask - logged in Autopilot's timeline as Boss's, undone in one tap; after
 * that only Boss switches it. Its open trades are watched in the minute lane and the 15-second stop check ([manageOnly]: memory and
 * the paper book only), and its book is read FIRST after a restart ([PositionsFirst]).
 */
object S5Arm {
    private const val FILE = "s5_arm.vault"
    const val EXITS_KEY = "s5trail"
    private val lock = Mutex()
    private val passLock = Mutex()
    @Volatile private var file: File? = null
    @Volatile private var app: Context? = null
    private val written = Vault.LastWrite()

    fun init(context: Context) {
        app = context.applicationContext
        file = File(context.applicationContext.noBackupFilesDir, FILE)
        // 30 Oct: its paper trades for the live mirror (memory only): today's and the open ones, each with what it still holds; null
        // while its book is not read (nothing is decided on an unread book: a live trade is never sold for want of it).
        ArmLive.register(com.optionslab.engine.orb.LiveMirror.S5) { if (cache == null) null else legs(_view.value, today()) }
    }

    /** [v]'s paper trades for the live mirror: the open ones and those closed on [day] (0 held). */
    internal fun legs(v: View, day: LocalDate): List<ArmLive.PaperLeg> =
        (v.open + v.today.filter { !it.open }).distinctBy { it.ref }.map { ArmLive.PaperLeg(it.ref, S5Trail.SOURCE, if (it.open) it.qty else 0) }

    /** The one-time switch-on of 28 Oct (Boss's ask); in [Book.migrated] once done, never again. */
    internal const val AUTO_ON = "boss_on_2026-10-28"

    /** TEST SEAM: true in the JVM tests' app, so other tests' books start with this arm off. Throws in a release build. */
    @Volatile internal var testNoAutoOn: Boolean = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /** Dhan's IV minus the app's (percentage points), by side and strike offset (s5_app_check.IV_SHIFT). */
    val IV_SHIFT = mapOf((true to -3) to -2.02, (true to 0) to -1.53, (true to 3) to -1.21, (false to -3) to 0.97, (false to 0) to 1.31, (false to 3) to 1.79)

    /** The square-off: the paper book's own MIS square-off (15:15), or 15:05 on the index's expiry day. */
    val SQ: LocalTime = NtfyRules.SQ
    val SQ_EXPIRY: LocalTime = LocalTime.of(15, 5)

    /** One paper trade. [peak]: the best net seen at a minute's close (null: not marked yet). [stopRs]: the cell's stop in rupees. */
    data class Pos(
        val index: String, val symbol: String, val right: String, val lots: Int, val qty: Int, val entry: Double, val entryTime: LocalDateTime,
        val orderId: String?, val stop: Double?, val stopOrderId: String?, val stopRs: Double, val ev: Double, val cell: Int, val expiryDay: Boolean,
        val peak: Double? = null, val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null, val charges: Double = 0.0,
        /** The profit lock said "out" but the sale did not fill yet: sold again at every look until it does (never re-judged). */
        val lockHit: Boolean = false,
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        val ref: String get() = "s5:$index:${entryTime}"
        val put: Boolean get() = right == "PE"
        /** Rupees after charges, once sold. */
        val net: Double? get() = exit?.let { (it - entry) * qty - charges }
        /** The net the profit lock would exit below now (null until the trade has been up ₹1,000). */
        val lockFloor: Double? get() = S5Trail.Trail(peak ?: Double.NEGATIVE_INFINITY).floor
    }

    /** One decision: [index] at decision [row] (4, 9, 14) of [day]: taken or not and why, with the best EV seen. */
    data class Look(val day: LocalDate, val index: String, val row: Int, val at: LocalDateTime, val ev: Double?, val cell: Int?, val why: String,
                    val taken: Boolean) {
        val key: String get() = "$day|$index|$row"
        val time: String get() = PassedArms.Session.time(row + 1).toString()
    }

    private class Book(
        var armed: Boolean = false,
        val positions: MutableList<Pos> = ArrayList(),
        val looks: MutableMap<String, Look> = LinkedHashMap(),
        val log: ArrayList<String> = ArrayList(),
        val migrated: MutableSet<String> = HashSet(),
        /** "day|index|row": an entry being placed (written before the order: a restart in between never orders it again). */
        val ordering: MutableSet<String> = HashSet(),
    )

    /** What the row and Jarvis read (memory only). */
    data class View(
        val armed: Boolean = false, val open: List<Pos> = emptyList(), val closed: List<Pos> = emptyList(), val today: List<Pos> = emptyList(),
        val looks: List<Look> = emptyList(), val log: List<String> = emptyList(), val nets: List<Double> = emptyList(), val model: String? = null,
        val lastPassMs: Long = 0L,
    ) {
        val wins: Int get() = nets.count { it > 0 }
    }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view
    @Volatile private var cache: Book? = null
    @Volatile private var wipes = 0
    private const val LOG_KEPT = 200
    private const val CLOSED_KEPT = 400

    private fun ready() = file != null

    // ---- the book -----------------------------------------------------------------------------------------------------

    private fun posJson(p: Pos) = JSONObject().put("index", p.index).put("symbol", p.symbol).put("right", p.right).put("lots", p.lots).put("qty", p.qty)
        .put("entry", p.entry).put("at", p.entryTime.toString()).put("stopRs", p.stopRs).put("ev", p.ev).put("cell", p.cell)
        .put("expiryDay", p.expiryDay).put("charges", p.charges)
        .apply {
            p.stop?.let { put("stop", it) }; p.peak?.takeIf { it.isFinite() }?.let { put("peak", it) }; if (p.lockHit) put("lockHit", true)
            p.orderId?.let { put("order", it) }; p.stopOrderId?.let { put("stopOrder", it) }
            p.exit?.let { put("exit", it) }; p.exitTime?.let { put("exitAt", it.toString()) }; p.why?.let { put("why", it) }
        }

    private fun dbl(o: JSONObject, k: String): Double? = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null

    private fun posOf(o: JSONObject) = Pos(o.getString("index"), o.getString("symbol"), o.getString("right"), o.getInt("lots"), o.getInt("qty"),
        o.getDouble("entry"), LocalDateTime.parse(o.getString("at")), o.optString("order").ifEmpty { null }, dbl(o, "stop"),
        o.optString("stopOrder").ifEmpty { null }, o.optDouble("stopRs", 2000.0), o.optDouble("ev", 0.0), o.optInt("cell"), o.optBoolean("expiryDay"),
        dbl(o, "peak"), dbl(o, "exit"), o.optString("exitAt").ifEmpty { null }?.let { LocalDateTime.parse(it) }, o.optString("why").ifEmpty { null },
        o.optDouble("charges", 0.0), o.optBoolean("lockHit"))

    private fun lookJson(l: Look) = JSONObject().put("day", l.day.toString()).put("index", l.index).put("row", l.row).put("at", l.at.toString())
        .put("why", l.why).put("taken", l.taken).apply { l.ev?.takeIf { it.isFinite() }?.let { put("ev", it) }; l.cell?.let { put("cell", it) } }

    private fun lookOf(o: JSONObject) = Look(LocalDate.parse(o.getString("day")), o.getString("index"), o.getInt("row"), LocalDateTime.parse(o.getString("at")),
        dbl(o, "ev"), if (o.has("cell")) o.getInt("cell") else null, o.optString("why"), o.optBoolean("taken"))

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
            o.optJSONArray("looks")?.let { a -> for (i in 0 until a.length()) lookOf(a.getJSONObject(i)).let { b.looks[it.key] = it } }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
            o.optJSONArray("migrated")?.let { a -> for (i in 0 until a.length()) b.migrated += a.getString(i) }
            o.optJSONArray("ordering")?.let { a -> for (i in 0 until a.length()) b.ordering += a.getString(i) }
        }.onFailure { unreadable = true; if (f.exists() && !Vault.isErasing) runCatching { Vault.setAside(f) } }
        synchronized(written) { if (gen == wipes) cache = b }
        // 28 Oct, Boss's explicit ask: on once, on paper. A book that could not be read is never switched on by itself (Boss may have
        // switched it off): only marked done. Never in IraGoldAlgo.
        if (AUTO_ON !in b.migrated) {
            b.migrated += AUTO_ON
            val on = !unreadable && !testNoAutoOn && !com.optionslab.app.BuildConfig.GOLD
            if (on && !b.armed) {
                b.armed = true
                note(b, "switched on once by the 28 Oct update, on your ask - on paper; ${S5Trail.NOT_PROVEN}")
                runCatching { com.optionslab.app.ira.Autopilot.logS5On() }
            }
            save(b)
        } else publish(b)
        return b
    }

    private fun save(b: Book): Boolean {
        val closed = b.positions.count { !it.open }
        if (closed > CLOSED_KEPT) { var drop = closed - CLOSED_KEPT; b.positions.removeAll { !it.open && drop-- > 0 } }
        val keepFrom = today().minusDays(10)
        b.looks.entries.removeAll { it.value.day.isBefore(keepFrom) }
        val o = JSONObject().put("armed", b.armed)
            .put("positions", JSONArray(b.positions.map { posJson(it) }))
            .put("looks", JSONArray(b.looks.values.map { lookJson(it) }))
            .put("log", JSONArray(b.log.takeLast(LOG_KEPT)))
            .put("migrated", JSONArray(b.migrated.sorted()))
            .put("ordering", JSONArray(b.ordering.toList()))
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
            b.looks.values.filter { it.day == today }.sortedWith(compareBy({ it.row }, { S5Trail.UNDERLYINGS.indexOf(it.index) })), b.log.takeLast(30),
            closed.map { it.net ?: 0.0 }, modelFailed, lastPassMs)
        runCatching { com.optionslab.ira.ArmExits.set(EXITS_KEY, b.positions.filter { it.open }.map { exitsOf(it) }) }
    }

    private fun exitsOf(p: Pos) = com.optionslab.ira.ArmExits.Entry(EXITS_KEY, S5Trail.LABEL, false, p.symbol, p.stop, null,
        if (p.expiryDay) SQ_EXPIRY else SQ, "stop −₹${"%,.0f".format(Locale.ENGLISH, p.stopRs)} net · lock 60% of the best once +₹1,000" +
            (p.lockFloor?.let { " (now out below ${rs(it)})" } ?: "") + " · ${S5Trail.NOT_PROVEN}")

    private fun note(b: Book, text: String) {
        b.log += "${now()} $text"
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        runCatching { Diag.record("s5trail", "${S5Trail.LABEL}: $text") }
    }

    private fun now(): LocalDateTime = runCatching { Market.now().toLocalDateTime().withNano(0) }.getOrElse { LocalDateTime.now().withNano(0) }
    private fun today(): LocalDate = runCatching { Market.today() }.getOrElse { LocalDate.now() }

    /** Read the book (start-up's positions-first step and the row call it, off the main thread). */
    suspend fun refresh(): View { if (ready()) lock.withLock { book() }; return _view.value }

    /** An open paper trade is held (memory only). */
    fun holding(): Boolean = _view.value.open.isNotEmpty()

    @Volatile var lastPassMs: Long = 0L
        private set

    /** The decision minutes: the 09:20, 09:25 and 09:30 bar closes, each for two minutes. */
    private val DECIDE = S5Trail.DECISION_ROWS.map { r -> PassedArms.Session.time(r + 1).let { it.hour * 60 + it.minute } }
    private const val DECIDE_FOR = 2

    /** The decision row due at [minute] (null: none). */
    internal fun dueRow(minute: Int): Int? = S5Trail.DECISION_ROWS.zip(DECIDE).firstOrNull { (_, m) -> minute in m until m + DECIDE_FOR }?.first

    /**
     * 30 Oct (CI review; CLAUDE.md "open positions come first"): the watch's pass runs this arm in front of the stops only when it
     * has real work - switched on, or holding (or a book not read yet; [ensureRead] reads it first). Switched off and flat it only decides for the switched-off
     * shadow ([OffShadow]), which the pass leaves to its own job after the stops ([OffShadow.runLate]). Memory only.
     */
    fun realDue(): Boolean = ready() && (cache == null || _view.value.armed || holding())

    /** 30 Oct: the book read once (a restart), on the watch's worker, so [realDue] knows the switch and what is held. */
    suspend fun ensureRead() { if (ready() && cache == null) refresh() }

    /**
     * The watch needs this arm's pass now (memory only): a trade held; or switched on, on a trading day, 08:45 to 09:32 (the history
     * read, then the three decisions).
     */
    fun wantsPass(minute: Int = runCatching { Market.minuteNow() }.getOrDefault(0)): Boolean {
        if (com.optionslab.app.BuildConfig.GOLD) return false
        if (holding()) return true
        // 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow"): off, it still decides (would-be trades).
        if (minute !in (8 * 60 + 45) until (DECIDE.last() + DECIDE_FOR)) return false
        return runCatching { Market.isTradingDay() }.getOrDefault(false)
    }

    /** A decision is due at [minute] (the watch's bar-close wake; memory only). */
    fun decisionDue(minute: Int = runCatching { Market.minuteNow() }.getOrDefault(0)): Boolean = !com.optionslab.app.BuildConfig.GOLD && dueRow(minute) != null

    // ---- Boss's switch --------------------------------------------------------------------------------------------------

    /**
     * Switch it on or off - on paper. Off buys nothing new: what it holds still closes by its own exits. [log]: Boss's flip (logged
     * in Autopilot's timeline); false for the timeline's own Undo.
     */
    suspend fun setArmed(on: Boolean, log: Boolean = true): String {
        if (!ready()) return "${S5Trail.LABEL} is not ready yet."
        return lock.withLock {
            val b = book()
            if (b.armed == on) return@withLock "${S5Trail.LABEL} is already ${if (on) "on" else "off"} (paper)."
            b.armed = on
            note(b, if (on) "switched on, on paper (${S5Trail.NOT_PROVEN})" else "switched off" +
                if (b.positions.any { it.open }) "; what it holds still closes by its own exits" else "")
            save(b)
            if (log) runCatching { com.optionslab.app.ira.Autopilot.s5SwitchedByBoss(on) }
            if (on) "${S5Trail.LABEL} on, on paper. ${S5Trail.NOT_PROVEN}." else "${S5Trail.LABEL} off. Nothing new is bought."
        }
    }

    fun diagLine(hideNet: Boolean = false): String {
        val v = _view.value
        val net = if (hideNet) PassedArmsHost.NET_HIDDEN else "%,.0f".format(Locale.ENGLISH, v.nets.sum())
        return "${S5Trail.LABEL}: ${if (v.armed) "on (paper)" else "off"} · open ${v.open.size} · today ${v.today.size} taken, " +
            "${v.looks.count { !it.taken }} not · ${v.nets.size} closed ($net after charges)" + (v.model?.let { " · model unreadable: $it" } ?: "") +
            " · ${S5Trail.NOT_PROVEN}"
    }

    /** Jarvis's answer: what it did today and why, what is held, the record. No rupees on a locked phone. */
    suspend fun talk(locked: Boolean = false): String {
        val v = refresh()
        val state = if (v.armed) "on, on paper" else "off"
        val today = if (v.looks.isEmpty()) "no decision yet today" else v.looks.joinToString("; ") { l ->
            "${l.index} ${l.time} ${if (l.taken) "taken" else if (locked) "not taken" else l.why}" }
        val held = if (v.open.isEmpty()) "nothing held" else "holding " + v.open.joinToString { it.symbol }
        val record = if (locked) "" else " Its record: ${v.nets.size} closed, ${v.wins} won, %s after charges.".format(Locale.ENGLISH, rs(v.nets.sum()))
        return "${S5Trail.LABEL} is $state - ${S5Trail.NOT_PROVEN}. Today: $today. $held.$record"
    }

    private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

    // ---- the model (read once, off the main thread) ------------------------------------------------------------------------

    @Volatile private var model: S5Trail.Model? = null
    @Volatile private var modelFailed: String? = null
    private fun ensureModel(): S5Trail.Model? {
        model?.let { return it }
        if (modelFailed != null) return null
        if (!assetsReady) return null
        return runCatching { loadModel() }
            .onSuccess { model = it }
            .onFailure { modelFailed = it.javaClass.simpleName; runCatching { Diag.record("s5trail", "the model could not be read: ${it.javaClass.simpleName}") } }
            .getOrNull()
    }

    /** The assets can be read (the app has started this arm). */
    internal val assetsReady: Boolean get() = app != null

    /**
     * R123's N1 from the app's assets (s5/), read and checked ([S5Trail.parseModel]); shared with CISD + network ([CisdArm]). Disk:
     * off the main thread, never on the tick or order path. Throws when the assets are missing or do not match their checksum.
     */
    internal fun loadModel(): S5Trail.Model {
        val ctx = app ?: throw IllegalStateException("not ready")
        fun read(name: String): ByteArray = ctx.assets.open(name).use { it.readBytes() }
        return S5Trail.parseModel(String(read(S5Trail.ASSET_META), Charsets.UTF_8), read(S5Trail.ASSET_BIN))
    }

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

    /** The account guard's snapshot, read before the lock for a pick that needs it: (when, snapshot). */
    @Volatile private var snapNow: Pair<Long, Paper.Snapshot>? = null
    private const val SNAP_FRESH_MS = 30_000L

    /** The watch's pass (and its bar-close wake): the open trades' exits; at a decision minute, each index's decision. */
    suspend fun tick() {
        if (com.optionslab.app.BuildConfig.GOLD || !ready()) return
        passLock.withLock {
            val now = now()
            val minute = now.hour * 60 + now.minute
            val day = now.toLocalDate()
            val trading = runCatching { Market.isTradingDay(day) }.getOrDefault(false)
            // 1. Exits first, under the lock; and what is due.
            val due = lock.withLock {
                val b = book()
                val changed = runCatching { exits(b, now) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true } || peakUnsaved
                peakUnsaved = false
                val row = dueRow(minute)
                // 30 Oct: on or off (off: each pick a would-be trade in the switched-off shadow, nothing bought).
                val list = if (!trading || row == null) emptyList()
                    else S5Trail.UNDERLYINGS.filter { "$day|$it|$row" !in b.looks }.map { it to row }
                lastPassMs = System.currentTimeMillis()
                if (changed) save(b) else publish(b)
                list
            }
            if (trading) runCatching { PassedArmsHost.prefetch(now, S5Trail.UNDERLYINGS) }
            if (due.isEmpty()) return@withLock
            // 2. The reads and the network's verdicts, outside the lock (network reads; never the tick or order path).
            val verdicts = runCatching { decide(due, now) }.getOrElse { e -> due.map { (u, r) -> Verdict(u, r, null, null, "the decision failed: ${e.javaClass.simpleName}", final = false) } }
            // 3. A pick waits for the account guard's snapshot: read now, outside the lock.
            if (verdicts.any { it.pick != null }) snapNow = runCatching { Paper.snapshot() }.getOrNull()?.let { System.currentTimeMillis() to it }
            // 4. The book: each verdict recorded, each pick taken (every guard), under the lock.
            lock.withLock {
                val b = book()
                for (v in verdicts.sortedWith(compareBy({ it.row }, { -(it.pick?.ev ?: Double.NEGATIVE_INFINITY) }))) {
                    val key = "$day|${v.index}|${v.row}"
                    if (key in b.looks) continue
                    val look = if (v.pick != null) runCatching { take(b, v, now) }.getOrElse { Look(day, v.index, v.row, now, v.pick.ev, v.pick.j, "not taken: ${it.javaClass.simpleName}", false) }
                        else Look(day, v.index, v.row, now, v.bestEv, null, v.why, false)
                    // A read that failed early in the window is tried again at the next pass; the last minute of the window decides.
                    if (!v.final && !look.taken && minute < (DECIDE[S5Trail.DECISION_ROWS.indexOf(v.row)] + DECIDE_FOR - 1)) continue
                    b.looks[key] = look
                    if (!look.taken) note(b, "${v.index} ${look.time}: not taken - ${look.why}")
                }
                snapNow = null
                save(b)
            }
        }
    }

    /** One index's decision at one row: the pick, or why not. [final]: nothing a later read in the window could change. */
    internal class Verdict(val index: String, val row: Int, val pick: S5Trail.Pick?, val bestEv: Double?, val why: String, val final: Boolean = true,
                           val close: Double = Double.NaN, val expiry: LocalDate? = null, val chain: Map<Pair<Int, Boolean>, Double> = emptyMap())

    /** TEST SEAMS: the index minutes, the earlier sessions, the chain's per-strike bars and the listed contracts (JVM tests only). */
    @Volatile internal var testBars: ((String) -> List<Bar>?)? = null
    @Volatile internal var testHist: ((String) -> PassedArmsHost.Hist?)? = null
    @Volatile internal var testChain: ((Upstox.Contract) -> List<Upstox.Bar>)? = null
    @Volatile internal var testListed: ((String) -> List<Upstox.Contract>)? = null
    /** TEST SEAM: the network's EV per cell-side for (index, row), in place of its own (JVM tests only; the inputs are still built). */
    @Volatile internal var testEv: ((String, Int) -> DoubleArray?)? = null

    private suspend fun bars(u: String, now: LocalDateTime): List<Bar>? = testBars?.let { return it(u) } ?: runCatching { PassedArmsHost.indexBars(u, now) }.getOrNull()
    private fun hist(u: String, day: LocalDate): PassedArmsHost.Hist? = testHist?.let { return it(u) } ?: PassedArmsHost.history(u, day)

    /** The index's listed options saved on the phone today (SENSEX: BSE's list); never a download in the pass. */
    private fun listed(u: String): List<Upstox.Contract> {
        testListed?.let { return it(u) }
        val bfo = u == Paper.BFO_UNDERLYING
        val saved = runCatching { if (bfo) Market.cachedBfoContracts() else Market.cachedContracts() }.getOrNull()
        if (saved == null || saved.first != today()) {
            runCatching { if (bfo) Market.refreshBfoInBackground() else Market.refreshContractsInBackground() }
            return emptyList()
        }
        return saved.second.filter { it.underlying == u }
    }

    private suspend fun decide(due: List<Pair<String, Int>>, now: LocalDateTime): List<Verdict> {
        val day = now.toLocalDate()
        val m = ensureModel() ?: return due.map { (u, r) -> Verdict(u, r, null, null, "the network could not be read (${modelFailed ?: "not ready"})") }
        val all = S5Trail.UNDERLYINGS + PassedArmsHost.VIX
        val today = all.associateWith { u -> bars(u, now)?.let { PassedArms.DayBars.of(day, it) } }
        val hists = all.associateWith { hist(it, day) }
        val byDay = S5Trail.UNDERLYINGS.associateWith { u ->
            (hists[u]?.sessions.orEmpty() + listOfNotNull(today[u])).associateBy { it.date }
        }
        val vix = (hists[PassedArmsHost.VIX]?.sessions.orEmpty() + listOfNotNull(today[PassedArmsHost.VIX])).associateBy { it.date }
        // The five indices at once (one gate over all their option reads), so a pick is placed soon after the bar's close.
        val gate = Semaphore(CHAIN_READS)
        return coroutineScope {
            due.map { (u, t) -> async { runCatching { decideOne(m, u, t, day, today[u], hists[u], byDay, vix, gate) }
                .getOrElse { Verdict(u, t, null, null, "the decision failed: ${it.javaClass.simpleName}", final = false) } } }.awaitAll()
        }
    }

    private suspend fun decideOne(m: S5Trail.Model, u: String, t: Int, day: LocalDate, todayBars: PassedArms.DayBars?, h: PassedArmsHost.Hist?,
                                  byDay: Map<String, Map<LocalDate, PassedArms.DayBars>>, vix: Map<LocalDate, PassedArms.DayBars>, gate: Semaphore): Verdict {
        if (todayBars == null || todayBars.c[t].isNaN()) return Verdict(u, t, null, null, "no $u minute bar for ${PassedArms.Session.time(t)} yet", final = false)
        val prior = h?.sessions.orEmpty().filter { it.date.isBefore(day) }
        if (prior.isEmpty() || h == null) return Verdict(u, t, null, null, "waiting for $u's earlier sessions (read from 08:45)", final = false)
        val past = pastOf(prior, h.daily, day)
        val market = S5Trail.Market(u, prior.takeLast(3) + todayBars.through(t), byDay, vix, past)
        val list = listed(u)
        val exp = NtfyRules.expiryFor(list.map { it.expiry }, day, NtfyParser.Expiry.Nearest)
            ?: return Verdict(u, t, null, null, "waiting for today's $u options list", final = false)
        val isExpiry = exp == day
        val trading = (1..45L).map { day.plusDays(it) }.filter { runCatching { Market.isTradingDay(it) }.getOrDefault(it.dayOfWeek.value < 6) }
        val cal = S5Trail.Calendar(S5Trail.sessionsToExpiry(day, exp, trading), isExpiry, day in com.optionslab.ira.EventStudy.BUDGET)
        val close = todayBars.closeAtOrBefore(t)
        val spot = DoubleArray(t + 1).also { a -> var last = Double.NaN; for (r in 0..t) { if (todayBars.c[r].isFinite()) last = todayBars.c[r]; a[r] = last } }
        val seen = spot.filter { it.isFinite() }
        val chainBars = chainBars(u, list.filter { it.expiry == exp }, seen.min(), seen.max(), gate)
        val chain = S5Trail.Chain.of(u, t, spot, chainBars) { price, s, strike, row, call ->
            val atmK = S5Trail.atm(s, S5Trail.step(u))
            val off = (strike - atmK) / S5Trail.step(u)
            val iv = OptionMath.impliedVol(price, if (call) OptionType.CE else OptionType.PE, s, strike.toDouble(), S5Trail.yearsToExpiry(day, row, exp))
            if (iv == null || !iv.isFinite()) Double.NaN else iv * 100 + (IV_SHIFT[call to off] ?: 0.0)
        }
        val x = S5Trail.inputs(market, t, cal, chain)
        val out = m.predict(market.window(t), x)
        val ev = testEv?.invoke(u, t) ?: m.ev(u, out)
        val atmStrike = S5Trail.atm(close, S5Trail.step(u))
        val last = S5Trail.lastRow(isExpiry)
        val sides = setOf(true, false).filter { call -> list.any { it.expiry == exp && it.strike == atmStrike.toDouble() && it.right == (if (call) Right.CE else Right.PE) } }
        val pick = S5Trail.pick(u, t, ev) { j -> S5Trail.fitsWindow(j, t + 1, last) && (j % 2 == 0) in sides }
        val best = ev.filter { it.isFinite() }.maxOrNull()
        val lastPx = chainBars.mapValues { (_, a) -> a.getOrNull(t)?.get(0) ?: Double.NaN }.filterKeys { it.first == atmStrike }
            .mapNotNull { (k, v) -> v.takeIf { it.isFinite() && it > 0 }?.let { k to it } }.toMap()
        val why = when {
            pick != null -> "picked"
            best == null -> "no expected net"
            else -> "best expected net ${rs(best)} is under ₹300"
        }
        return Verdict(u, t, pick, best, why, final = true, close = close, expiry = exp, chain = lastPx)
    }

    /** The completed sessions before [day] as R123's daily stats: the minute sessions where read, else the daily candles; 25 kept. */
    internal fun pastOf(minute: List<PassedArms.DayBars>, daily: List<Bar>, day: LocalDate): List<S5Trail.Past> {
        val byMinute = minute.filter { it.date.isBefore(day) && it.count > 0 }.associateBy { it.date }
        val dates = (byMinute.keys + daily.map { it.start.toLocalDate() }.filter { it.isBefore(day) }).toSortedSet()
        return dates.mapNotNull { d ->
            byMinute[d]?.let { S5Trail.Past.of(it) } ?: daily.lastOrNull { it.start.toLocalDate() == d }?.let { b ->
                if (b.close > 0 && b.high.isFinite() && b.low.isFinite()) S5Trail.Past.of(d, b.high, b.low, b.close) else null }
        }.takeLast(25)
    }

    /** Strikes beyond each minute's ATM whose minutes are read: the inputs read offsets -5..+5 (the OI and volume), one spare. */
    private const val CHAIN_HALF = 6
    /** Option reads at once over all five indices. */
    internal const val CHAIN_READS = 12

    /**
     * Today's minutes of the listed strikes from [lo]'s ATM − [CHAIN_HALF] steps to [hi]'s ATM + [CHAIN_HALF] steps ([lo], [hi]: the
     * index's lowest and highest close since the open), both sides: (strike, call) -> row -> (close, OI, volume). Network reads
     * ([read]: one contract's minutes; CISD + network passes its own, with its test seam).
     */
    internal suspend fun chainBars(u: String, list: List<Upstox.Contract>, lo: Double, hi: Double, gate: Semaphore,
                                   read: (suspend (Upstox.Contract) -> List<Upstox.Bar>?)? = null): Map<Pair<Int, Boolean>, Array<DoubleArray>> {
        val st = S5Trail.step(u)
        val from = S5Trail.atm(lo, st) - CHAIN_HALF * st
        val to = S5Trail.atm(hi, st) + CHAIN_HALF * st
        val want = list.filter { c -> c.right != Right.IX && c.strike >= from - 1e-6 && c.strike <= to + 1e-6 && c.strike % st == 0.0 }
        val day = today()
        val got = coroutineScope {
            want.map { c -> async { gate.withPermit { c to runCatching { if (read != null) read(c) else testChain?.invoke(c) ?: Net.intradayQuick(c.instrumentKey) }.getOrNull() } } }.awaitAll()
        }
        val out = HashMap<Pair<Int, Boolean>, Array<DoubleArray>>()
        for ((c, bars) in got) {
            if (bars == null) continue
            val a = Array(PassedArms.Session.N) { doubleArrayOf(Double.NaN, Double.NaN, Double.NaN) }
            for (b in bars) {
                if (b.istDate != day) continue
                val r = b.istMinute - (9 * 60 + 15)
                if (r !in 0 until PassedArms.Session.N || !(b.close > 0)) continue
                a[r] = doubleArrayOf(b.close, b.oi.toDouble(), b.volume.toDouble())
            }
            out[c.strike.toInt() to (c.right == Right.CE)] = a
        }
        return out
    }

    // ---- the entry -------------------------------------------------------------------------------------------------------

    private suspend fun stoppedToday(): Boolean =
        runCatching { Strategies.stoppedToday() }.getOrDefault(true) || runCatching { LossBreaker.trippedToday() }.getOrDefault(false)

    private fun chargesOf(orderId: String?): Double =
        if (orderId == null) 0.0 else Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    /** One leg's charges for [qty] at a premium (the paper book's own [SandboxCosts]). */
    internal fun chargeFor(qty: Int): (Boolean, Double) -> Double = { buy, px ->
        if (!(px > 0)) 0.0 else SandboxCosts.charge(if (buy) "BUY" else "SELL", BigDecimal.valueOf(px), qty).toDouble()
    }

    /** [c]'s price from Zerodha's stream (at most 5 s old; the option followed from now on), or null. Memory only. */
    private fun optionQuote(c: Paper.Contract, anyAge: Boolean = false): com.optionslab.engine.sandbox.Quote? = runCatching {
        val list = Broker.cachedInstruments() ?: return@runCatching null
        val tok = Broker.find(list, c.underlying, c.expiry, c.strike, c.right)?.token?.takeIf { it > 0 } ?: return@runCatching null
        KiteStream.touch(listOf(tok))
        val t = (if (anyAge) KiteStream.seen(tok)?.tick else KiteStream.tick(tok)) ?: return@runCatching null
        if (t.last <= 0) null else com.optionslab.engine.sandbox.Quote(t.last, bid = t.bid ?: 0.0, ask = t.ask ?: 0.0)
    }.getOrNull()

    /** This run's own orders being placed (memory): one in [Book.ordering] but not here was interrupted by a restart. */
    private val placing: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** A pick: every guard, then the paper order. Returns the decision's record. Under the lock. */
    private suspend fun take(b: Book, v: Verdict, now: LocalDateTime): Look {
        val p = v.pick!!
        val day = now.toLocalDate()
        val key = "$day|${v.index}|${v.row}"
        fun no(why: String) = Look(day, v.index, v.row, now, p.ev, p.j, "picked (expected net ${rs(p.ev)}); not taken: $why", false)
        if (key in b.ordering && key !in placing) { b.ordering.remove(key); return no("interrupted while its order was placed - not ordered again (check Paper for a fill)") }
        val time = now.toLocalTime()
        val refusal = when {
            !runCatching { Market.isTradingDay(day) }.getOrDefault(false) -> "not a trading day"
            runCatching { AppSettings.load().guardKill }.getOrDefault(true) -> "the kill switch is on"
            stoppedToday() -> "the bot is stopped for today"
            else -> runCatching { DayLockGuard.refusal(false) }.getOrNull()
                ?: if (!b.armed) shadowBusy(v.index, day)
                else if (b.positions.count { it.day == day } >= S5Trail.DAY_MAX) "${S5Trail.DAY_MAX} trades today already"
                else if (b.positions.any { it.open && it.index == v.index }) "a ${v.index} trade is still open"
                else if (!time.isBefore(SQ_EXPIRY)) "too late in the day"
                else null
        }
        if (refusal != null) return no(refusal)
        val exp = v.expiry ?: return no("no expiry")
        val right = if (p.call) Right.CE else Right.PE
        val strike = S5Trail.atm(v.close, S5Trail.step(v.index)).toDouble()
        val c = Paper.ticketContract(v.index, exp, strike, right) ?: return no("${v.index} ${NtfyParser.num(strike)} ${right.name} is not in the phone's options list")
        if (c.lotSize <= 0) return no("lot size unknown")
        if (b.positions.any { it.open && it.symbol == c.symbol } || Paper.state.positions.any { it.symbol == c.symbol && it.quantity != 0 })
            return no("${c.symbol} is already held - never two positions on one contract")
        // The fill reads Zerodha's price (the stream's, or the paper book's own fresh read): never the chain's minute-old close.
        val q = optionQuote(c)
        val est = q?.ltp ?: v.chain[strike.toInt() to p.call] ?: return no("no price for ${c.symbol}")
        val stop0 = S5Trail.stopPremium(est, c.lotSize, p.stopRs, chargeFor(c.lotSize))
        // 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record only"): switched off, the pick is a
        // would-be trade in the switched-off shadow (its stop, its 60% lock after +₹1,000, its square-off) - nothing is bought, nothing
        // goes to the paper book or live.
        if (!b.armed) {
            val said = OffShadow.enter(OffShadow.Ask(S5Trail.SOURCE, S5Trail.LABEL, "${v.index}|${v.row}", c, if (p.call) 1 else -1,
                com.optionslab.engine.orb.OffShadow.Exits(stop = stop0, lockAfterRs = S5Trail.LOCK_FROM, lockKeep = S5Trail.LOCK_KEEP,
                    exitAt = if (exp == day) SQ_EXPIRY else SQ),
                "picked (expected net ${rs(p.ev)}) at ${PassedArms.Session.time(v.row + 1)}"))
            return no("switched off - $said")
        }
        val snap = snapNow?.takeIf { System.currentTimeMillis() - it.first in 0..SNAP_FRESH_MS }?.second ?: return no("the account guard's check could not be read")
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", 1, est), Guard.paperAccount(snap), paper = true)
        if (refusals.isNotEmpty()) return no("refused by the account guard: " + refusals.joinToString(" "))
        val verdict = OrderVerdicts.stamp(OrderVerdicts.paperFields(c, "BUY", 1, "MARKET", "MIS", null, null, day, S5Trail.OWNER, stop = stop0),
            refusals, "paper guard")
        b.ordering += key; placing += key
        if (!save(b)) { b.ordering.remove(key); placing.remove(key); return no("the book could not be saved before the order") }
        val res = try {
            Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, known = q, auto = S5Trail.OWNER, mirror = S5Trail.SOURCE, verdict = verdict, stop = stop0)
        } finally { b.ordering.remove(key); placing.remove(key) }
        val fill = res.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
            ?: return no("the paper order did not fill (${res.message})").also { res.orderId?.takeIf { res.ok }?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } } }
        val pos = entered(b, v, c, res.orderId, fill.quantity, fill.price, now, exp == day)
        return Look(day, v.index, v.row, now, p.ev, p.j, "taken: ${pos.symbol} ${pos.qty} @ ${NtfyParser.num(pos.entry)} · paper", true)
    }

    /**
     * 30 Oct (review): switched off, its own rules hold the would-be trades as they would the real ones - one open trade per index,
     * at most [S5Trail.DAY_MAX] a day - counted on the switched-off shadow's would-be trades ([OffShadow], memory only).
     */
    private fun shadowBusy(index: String, day: LocalDate): String? {
        val v = OffShadow.view.value
        val mine = (v.openOf(S5Trail.SOURCE) + v.closedOf(S5Trail.SOURCE)).filter { it.day == day }
        return if (mine.size >= S5Trail.DAY_MAX) "${S5Trail.DAY_MAX} would-be trades today already"
            else if (v.openOf(S5Trail.SOURCE).any { it.underlying == index }) "a $index would-be trade is still open" else null
    }

    /** A filled entry booked: its ticket labelled, its stop resting as a paper SELL SL-M, the diary's line. */
    private suspend fun entered(b: Book, v: Verdict, c: Paper.Contract, orderId: String?, qty: Int, price: Double, now: LocalDateTime, expiryDay: Boolean): Pos {
        val p = v.pick!!
        orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${S5Trail.OWNER} · ${S5Trail.NOT_PROVEN} · entry") } }
        val lots = (qty / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
        val stop = S5Trail.stopPremium(price, qty, p.stopRs, chargeFor(qty))
        val stopId = stop?.let { s ->
            runCatching { Paper.place(c, "SELL", lots, "SL-M", "MIS", null, s, known = com.optionslab.engine.sandbox.Quote(price)) }
                .getOrNull()?.takeIf { it.ok }?.orderId?.also { runCatching { Strategies.tagOwner("paper:$it", "${S5Trail.OWNER} · stop") } }
        }
        val pos = Pos(v.index, c.symbol, c.right.name, lots, qty, price, now, orderId, stop, stopId, p.stopRs, p.ev, p.j, expiryDay,
            charges = chargesOf(orderId))
        b.positions += pos
        // 30 Oct: offered to its live path (memory only; nothing goes unless every switch and guard allows it).
        runCatching { ArmLive.entered(ArmLive.Ask(S5Trail.SOURCE, pos.ref, c, 1, qty, price, stop = stop)) }
        note(b, "${v.index} ${PassedArms.Session.time(v.row + 1)}: bought ${c.symbol} $qty @ ${NtfyParser.num(price)} · paper · expected net ${rs(p.ev)} · " +
            "stop ${stop?.let { NtfyParser.num(it) } ?: "none (the premium is under the stop)"} (−₹${"%,.0f".format(Locale.ENGLISH, p.stopRs)} net) resting · " +
            "lock 60% of the best once +₹1,000 · ${S5Trail.NOT_PROVEN}")
        return pos
    }

    // ---- the exits -------------------------------------------------------------------------------------------------------

    /** The minute each open trade was last marked for the lock (memory): the lock reads one price a minute, as R128 marked closes. */
    private val marked = java.util.concurrent.ConcurrentHashMap<String, LocalDateTime>()

    /** A new best net was marked but not written yet (memory): the watch's pass saves it, never the minute or stop lanes. */
    @Volatile private var peakUnsaved = false

    /**
     * Each open trade's exit, when due. Memory and the paper book only (the stream's prices). True when [b] must be written (an
     * exit or a stop put back); a new best net alone is kept in memory and written by the next pass ([peakUnsaved]).
     */
    private suspend fun exits(b: Book, now: LocalDateTime): Boolean {
        var changed = false
        for (i in b.positions.indices) {
            val p = b.positions[i]
            if (!p.open) continue
            val next = runCatching { exitOne(p, now) }.getOrNull() ?: continue
            if (next != p) {
                b.positions[i] = next
                if (next.copy(peak = p.peak) != p) changed = true else peakUnsaved = true
                if (!next.open) note(b, "sold ${p.symbol} (${next.why}): %s after charges · paper".format(Locale.ENGLISH, rs(next.net ?: 0.0)))
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
            if (o != null && o.status == "complete") return closed(p, o.averagePrice?.toDouble() ?: (p.stop ?: p.entry), o.updateTimestamp, "stop", chargesOf(id))
        }
        // Gone from the paper book without this arm selling it (the paper square-off, the loss limit, or Boss).
        if (Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity } <= 0) {
            p.stopOrderId?.let { runCatching { Paper.cancel(it, "position_closed") } }
            val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
            return closed(p, t?.price?.toDouble() ?: p.entry, t?.timestamp ?: now, "closed_elsewhere", t?.charges?.toDouble() ?: 0.0)
        }
        val sq = if (p.expiryDay) SQ_EXPIRY else SQ
        if (!now.isBefore(p.day.atTime(sq)) || p.day.isBefore(now.toLocalDate())) return sellRest(p, c, "square-off $sq")
        // The profit lock already said "out" and the sale did not fill: sold again now, never re-judged.
        if (p.lockHit) return sellRest(p, c, "profit lock (best ${rs(p.peak ?: 0.0)})")
        val ltp = runCatching { Paper.bookNow(c)?.last }.getOrNull()?.takeIf { it > 0 } ?: optionQuote(c)?.ltp
        // The profit lock, once a minute (R128 marked the net at minute closes).
        val minute = now.withSecond(0).withNano(0)
        if (ltp != null && marked[p.ref] != minute) {
            marked[p.ref] = minute
            val net = S5Trail.netAt(p.entry, ltp, p.qty, chargeFor(p.qty))
            val (trail, out) = S5Trail.Trail(p.peak ?: Double.NEGATIVE_INFINITY).onClose(net)
            if (out) return sellRest(p.copy(peak = trail.peak, lockHit = true), c, "profit lock (best ${rs(trail.peak)}, out at ${rs(net)})")
            if (trail.peak != (p.peak ?: Double.NEGATIVE_INFINITY)) p = p.copy(peak = trail.peak)
        }
        // No stop resting in the paper book: the arm checks the stop itself and puts the SL-M back while above it.
        val stop = p.stop
        if (stop != null && !stopResting(p.stopOrderId)) {
            if (ltp != null && ltp <= stop) return sellRest(p, c, "stop")
            val again = if (closingWorks(p.symbol)) null else runCatching { Paper.place(c, "SELL", p.lots, "SL-M", "MIS", null, stop) }
                .getOrNull()?.takeIf { it.ok }?.orderId?.also { runCatching { Strategies.tagOwner("paper:$it", "${S5Trail.OWNER} · stop") } }
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
            if (o != null && o.status == "complete") return closed(p, o.averagePrice?.toDouble() ?: (p.stop ?: p.entry), o.updateTimestamp, "stop", chargesOf(id))
        }
        val r = Paper.place(c, "SELL", p.lots, "MARKET", "MIS", null, null, known = optionQuote(c) ?: optionQuote(c, anyAge = true), exit = true)
        val f = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull() ?: run {
            if (!r.ok && Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity } <= 0) {
                val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
                return closed(p.copy(stopOrderId = null), t?.price?.toDouble() ?: p.entry, t?.timestamp ?: now(), "closed_elsewhere", t?.charges?.toDouble() ?: 0.0)
            }
            // Not filled (no fresh price): the stop goes back into the paper book at once, and the exit is tried next pass.
            r.orderId?.takeIf { r.ok }?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
            val again = p.stop?.let { s -> if (closingWorks(p.symbol)) null else runCatching { Paper.place(c, "SELL", p.lots, "SL-M", "MIS", null, s) }.getOrNull()?.takeIf { it.ok }?.orderId }
            return p.copy(stopOrderId = again)
        }
        r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${S5Trail.OWNER} · exit") } }
        return closed(p.copy(stopOrderId = null), f.price, now(), why, chargesOf(r.orderId))
    }

    private fun closed(p: Pos, price: Double, at: LocalDateTime, why: String, charges: Double): Pos {
        marked.remove(p.ref)
        // 30 Oct: its live trade (if any) is sold on the live watch's next look (memory only).
        runCatching { ArmLive.closed(p.ref, why, ArmLive.pct(p.entry, price)) }
        return p.copy(exit = price, exitTime = at, why = why, charges = p.charges + charges, stopOrderId = null)
    }

    // ---- tests and the erase ---------------------------------------------------------------------------------------------

    /** For tests: the book forgotten (its file stays: a test reads it again, as after a restart). */
    internal fun wipe() {
        synchronized(written) {
            wipes++
            cache = null; _view.value = View(); testBars = null; testHist = null; testChain = null; testListed = null; testEv = null
            lastPassMs = 0L; placing.clear(); snapNow = null; marked.clear(); peakUnsaved = false
            written.forget()
        }
        runCatching { com.optionslab.ira.ArmExits.set(EXITS_KEY, emptyList()) }
    }

    /** "Erase everything" ([PersonalStores]): [wipe], and its file deleted. Memory and files only. */
    internal fun wipeForErase() {
        synchronized(written) {
            for (f in filesForErase()) runCatching { f.delete() }
            wipe()
        }
    }

    /** Anything of the book held in memory (the erase's check; memory only). */
    internal fun holdsForErase(): Boolean = runCatching {
        val v = _view.value
        v.armed || v.open.isNotEmpty() || v.closed.isNotEmpty() || v.looks.isNotEmpty() || v.log.isNotEmpty() ||
            cache?.let { b -> b.positions.isNotEmpty() || b.log.isNotEmpty() || b.looks.isNotEmpty() } == true
    }.getOrDefault(true)

    /** Its book, and any copy set aside as unreadable. */
    internal fun filesForErase(): List<File> = listOfNotNull(file).flatMap { f ->
        listOf(f) + f.parentFile?.listFiles { x -> x.name.startsWith(f.name + ".unreadable.") }.orEmpty()
    }
}
