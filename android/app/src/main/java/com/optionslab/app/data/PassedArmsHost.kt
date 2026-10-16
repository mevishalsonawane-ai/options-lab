package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.OovArm
import com.optionslab.engine.orb.OrbRules
import com.optionslab.engine.orb.PassedArms
import com.optionslab.engine.orb.ShadowRules
import com.optionslab.engine.orb.Solo2
import com.optionslab.engine.orb.SoloMidday
import com.optionslab.engine.sandbox.SandboxEvent
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
import java.time.LocalTime
import java.util.Locale

/**
 * The paper arms that PASSED research (15 Oct 2026, Boss: "Both"; the rules are the engine's, [PassedArms]):
 *  - "R51 Donchian 20" ([DonchianArm]) on NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX;
 *  - "R52 Open outside value" ([OovArm]) on the same five, with R57's Solo layer as its stop (labelled);
 *  - "Solo 2.0" ([Solo2], research R57's A2 meta-labeller): Jarvis's own second paper Solo, next to Solo (midday), which it
 *    never changes.
 * Every row, notice and answer says "Passed research (R51/R52/R57) — paper only".
 *
 * PAPER ONLY, always: every order is the paper account's ([Paper.place]); there is no live flag and no Zerodha call anywhere
 * here, so nothing can reach Zerodha whatever Live, "AI trades go live" or a voice command says - and Solo 2.0 is never
 * offered as a trade idea. 1 lot each. Their book is their own encrypted file (not in the backup): their trades are a record
 * of their own, apart from the plain-rules record for the 26 Oct decision. New entries obey the kill switch, the bot's stop
 * for today (the daily loss limit included), the account day lock and the account guard; a stale price refuses a MARKET entry
 * (the paper account's own check). Exits always go, and the bot's stop for today sells what is held (it only lowers risk).
 *
 * Under CLAUDE.md's PAPER rule a passed idea acts on paper at once: each arm is switched ON once by this update (logged in
 * Autopilot's change timeline as PAPER_ARM_ON, Boss's, undone in one tap); after that only Boss switches it - except its
 * forward-test bar, set in advance, which switches it OFF by itself (risk-lowering, logged the same way):
 *  - R51 / R52: at 40 paper trades, a net below 0 or below the cost of twice the spread ([PassedArms.ForwardBar]);
 *  - Solo 2.0: SoloMidday's bar - after 60 trades a net per trade <= 0, or a drawdown beyond -Rs 25,000 at any time.
 *
 * Off the order and tick paths: the entries run in the watch's arms passes (a worker), their reads and orders on a copy of the
 * book OUTSIDE its lock (merged back after), so the exits never wait behind them; the previous sessions' bars and daily candles are read once a day in the background ([prefetch]); Solo 2.0's
 * model is read once, at start-up, from the app's assets on a worker ([refresh]) - never on the main thread, never in the
 * fast lanes. While a position is held its exits run in the fast path's minute lane at full cadence ([manageOnly]), and after
 * a restart the book is read first ([PositionsFirst]).
 */
object PassedArmsHost {
    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, "passed_arms.vault")
    }

    val SOURCES = listOf(DonchianArm.SOURCE, OovArm.SOURCE, Solo2.SOURCE)
    fun label(source: String): String = when (source) { DonchianArm.SOURCE -> DonchianArm.LABEL; OovArm.SOURCE -> OovArm.LABEL; else -> Solo2.LABEL }
    fun research(source: String): String = when (source) { DonchianArm.SOURCE -> DonchianArm.RESEARCH_ID; OovArm.SOURCE -> OovArm.RESEARCH_ID; else -> Solo2.RESEARCH_ID }
    fun underlyings(source: String): List<String> = when (source) { DonchianArm.SOURCE -> DonchianArm.UNDERLYINGS; OovArm.SOURCE -> OovArm.UNDERLYINGS; else -> Solo2.UNDERLYINGS }
    fun apart(source: String): String = when (source) { DonchianArm.SOURCE -> DonchianArm.REPORT_APART; OovArm.SOURCE -> OovArm.REPORT_APART; else -> Solo2.REPORT_APART }
    private val ALL_INDICES = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")

    /** The one-time switch-on of 15 Oct (the PAPER rule, Boss's "Both"); in [Book.migrated] once done, never again. */
    internal const val AUTO_ON = "passed_on_2026-10-15"

    /** TEST SEAM: true in the JVM tests' app (the other tests' books start with these arms off). Its setter throws in a release build. */
    @Volatile internal var testNoAutoOn: Boolean = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /** TEST SEAM: an index's 1-minute bars of the earlier sessions (null in the app: Upstox's history). Debug builds only. */
    @Volatile internal var testHistory: ((String) -> List<Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test feed exists only in debug builds" }; field = v }

    /** TEST SEAM: an index's daily candles (null in the app: Upstox's). Debug builds only. */
    @Volatile internal var testDaily: ((String) -> List<Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test feed exists only in debug builds" }; field = v }

    /**
     * One paper trade. [setup] Solo 2.0's setup key (S1..S4; null for R51 / R52); [entryRef] / [exitRef] the prices seen when
     * each order went in (the spread paid, for the forward bar); [idx] / [atr] S1's 12:00 index price and ATR14 (its exits).
     */
    data class Pos(
        val arm: String, val index: String, val symbol: String, val right: String, val qty: Int, val entry: Double, val entryTime: LocalDateTime,
        val orderId: String?, val setup: String? = null, val entryRef: Double? = null, val idx: Double? = null, val atr: Double? = null,
        val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null, val charges: Double = 0.0, val exitRef: Double? = null,
        val paperFill: Double? = null,
        /** 16 Oct: taken back by the safety net ([adopt]) after it was found held with no record here ([ADOPT_FILL]: from its real fill). */
        val adopted: String? = null,
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        val side: Int get() = if (right == "CE") 1 else -1
        val net: Double? get() = exit?.let { (it - entry) * qty - charges }
        /** The spread and slippage this round trip paid (the forward bar's "twice the spread"). */
        val spread: Double get() = PassedArms.spreadPaid(qty, entry, entryRef, exit ?: entry, exitRef,
            com.optionslab.engine.sandbox.PaperSpread.DEFAULTS[index] ?: com.optionslab.engine.sandbox.PaperSpread.UNKNOWN)
        /** The minute the research's walk starts from: its own entry minute (S1: 12:00, SoloMidday's). */
        val entryMinute: LocalDateTime get() = if (setup == Solo2.Setup.S1.key) SoloMidday.entryTime(day) else entryTime.withSecond(0).withNano(0)
    }

    private class Book(
        val armed: MutableMap<String, Boolean> = HashMap(),
        val positions: MutableList<Pos> = ArrayList(),
        /** Decisions made, so each is made once: "day|arm|..." */
        val decided: MutableSet<String> = HashSet(),
        val status: MutableMap<String, String> = HashMap(),
        val log: ArrayList<String> = ArrayList(),
        val migrated: MutableSet<String> = HashSet(),
        /** R51 / R52: closed trades when Boss switched it back on after its bar switched it off (the bar is not judged again). */
        val base: MutableMap<String, Int> = HashMap(),
        /** Solo 2.0's baseline ("from|peak", [SoloMidday.Baseline]). */
        var soloBase: String? = null,
        /** The forward verdict each arm switched itself off for (acted on once). */
        val acted: MutableMap<String, String> = HashMap(),
        /** Why each arm switched itself off (cleared when Boss switches it on). */
        val paused: MutableMap<String, String> = HashMap(),
        /** Solo 2.0's scored candidates, "day|key|score|what". */
        val scores: ArrayList<String> = ArrayList(),
        val marks: MutableMap<String, Double> = HashMap(),
        /** Each open trade's lock as an earlier look saw it earned ("time|level"): it holds even when the feed loses those bars. */
        val locks: MutableMap<String, String> = HashMap(),
    ) {
        /** A copy for the entries phase (worked on outside the book's lock, then merged back). */
        fun copy(): Book = Book(HashMap(armed), ArrayList(positions), HashSet(decided), HashMap(status), ArrayList(log), HashSet(migrated), HashMap(base),
            soloBase, HashMap(acted), HashMap(paused), ArrayList(scores), HashMap(marks), HashMap(locks))
    }

    /** What the rows and Jarvis read (memory only). */
    data class View(
        val armed: Map<String, Boolean> = emptyMap(), val status: Map<String, String> = emptyMap(), val open: List<Pos> = emptyList(),
        val closed: List<Pos> = emptyList(), val today: List<Pos> = emptyList(), val log: List<String> = emptyList(),
        /** Every closed trade, oldest first (the records and the forward bars). */
        val all: List<Pos> = emptyList(), val marks: Map<String, Double> = emptyMap(), val paused: Map<String, String> = emptyMap(),
        val scores: List<String> = emptyList(), val modelLoaded: Boolean = false,
        val base: Map<String, Int> = emptyMap(), val soloBase: SoloMidday.Baseline = SoloMidday.Baseline(),
    ) {
        fun on(source: String): Boolean = armed[source] == true
        fun closedOf(source: String): List<Pos> = all.filter { it.arm == source }
        fun todayOf(source: String): List<Pos> = today.filter { it.arm == source }
        /** Today's running P&L of [source]: closed after charges, open at the last price before charges. */
        fun todayPnl(source: String): Double = todayOf(source).sumOf { p -> p.net ?: marks[p.symbol]?.let { (it - p.entry) * p.qty } ?: 0.0 }
        /** The forward test's line. */
        fun forwardLine(source: String): String {
            val c = closedOf(source)
            return if (source == Solo2.SOURCE) SoloMidday.forwardLine(SoloMidday.record(c.map { it.net ?: 0.0 })).replace("Forward test", "Forward test (60 trades)").removeSuffix(".")
            else PassedArms.ForwardBar.line(PassedArms.ForwardBar.judge(c.map { it.net ?: 0.0 }, c.map { it.spread }, base[source] ?: 0))
        }
    }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    @Volatile private var cache: Book? = null
    private const val LOG_KEPT = 200
    /** Closed trades kept per arm. */
    private const val CLOSED_KEPT = 1500
    /** Its own notices: 8100.. (buys 8100-8114, switch-offs 8120-8122, sells 8140-8154, taken back 8160-8174); no other part of the app uses 81xx. */
    private const val NOTICE = 8100

    private fun ready() = ::file.isInitialized

    private fun book(): Book {
        cache?.let { return it }
        val b = Book()
        var unreadable = false
        runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            o.optJSONObject("armed")?.let { m -> m.keys().forEach { b.armed[it] = m.getBoolean(it) } }
            o.optJSONArray("positions")?.let { a -> for (i in 0 until a.length()) b.positions += readPos(a.getJSONObject(i)) }
            o.optJSONArray("decided")?.let { a -> for (i in 0 until a.length()) b.decided += a.getString(i) }
            o.optJSONObject("status")?.let { m -> m.keys().forEach { b.status[it] = m.getString(it) } }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
            o.optJSONArray("migrated")?.let { a -> for (i in 0 until a.length()) b.migrated += a.getString(i) }
            o.optJSONObject("base")?.let { m -> m.keys().forEach { b.base[it] = m.getInt(it) } }
            b.soloBase = o.optString("soloBase").ifEmpty { null }
            o.optJSONObject("acted")?.let { m -> m.keys().forEach { b.acted[it] = m.getString(it) } }
            o.optJSONObject("paused")?.let { m -> m.keys().forEach { b.paused[it] = m.getString(it) } }
            o.optJSONArray("scores")?.let { a -> for (i in 0 until a.length()) b.scores += a.getString(i) }
            o.optJSONObject("locks")?.let { m -> m.keys().forEach { b.locks[it] = m.getString(it) } }
        }.onFailure { unreadable = true; if (file.exists()) Vault.setAside(file) }
        cache = b
        // 15 Oct (the PAPER rule): on once, on paper. A book that could not be read is never switched on by itself (Boss may
        // have switched an arm off): it is only marked done. Never in IraGoldAlgo.
        if (AUTO_ON !in b.migrated) {
            b.migrated += AUTO_ON
            val on = !unreadable && !testNoAutoOn && !com.optionslab.app.BuildConfig.GOLD
            if (on) for (s in SOURCES) if (b.armed[s] != true) {
                b.armed[s] = true
                note(b, "${label(s)} switched on once by the 15 Oct update: ${PassedArms.PASSED} (${research(s)})")
                runCatching { com.optionslab.app.ira.Autopilot.logPassedArmOn(s, label(s), research(s)) }
            }
            save(b)
        } else publish(b)
        return b
    }

    private fun readPos(p: JSONObject): Pos {
        fun d(k: String) = if (p.has(k)) p.getDouble(k) else null
        return Pos(p.getString("arm"), p.getString("index"), p.getString("symbol"), p.getString("right"), p.getInt("qty"), p.getDouble("entry"),
            LocalDateTime.parse(p.getString("entryTime")), p.optString("orderId").ifEmpty { null }, p.optString("setup").ifEmpty { null },
            d("entryRef"), d("idx"), d("atr"), d("exit"), p.optString("exitTime").ifEmpty { null }?.let { LocalDateTime.parse(it) },
            p.optString("why").ifEmpty { null }, p.optDouble("charges", 0.0), d("exitRef"), d("paperFill"), p.optString("adopted").ifEmpty { null })
    }

    private fun writePos(p: Pos): JSONObject = JSONObject().put("arm", p.arm).put("index", p.index).put("symbol", p.symbol).put("right", p.right)
        .put("qty", p.qty).put("entry", p.entry).put("entryTime", p.entryTime.toString()).put("orderId", p.orderId ?: "").put("charges", p.charges)
        .apply {
            p.setup?.let { put("setup", it) }; p.entryRef?.let { put("entryRef", it) }; p.idx?.let { put("idx", it) }; p.atr?.let { put("atr", it) }
            p.exit?.let { put("exit", it) }; p.exitTime?.let { put("exitTime", it.toString()) }; p.why?.let { put("why", it) }
            p.exitRef?.let { put("exitRef", it) }; p.paperFill?.let { put("paperFill", it) }; p.adopted?.let { put("adopted", it) }
        }

    private val written = Vault.LastWrite()

    private fun save(b: Book) {
        val o = JSONObject()
        o.put("armed", JSONObject().apply { b.armed.forEach { (k, v) -> put(k, v) } })
        o.put("positions", JSONArray().apply { b.positions.forEach { put(writePos(it)) } })
        o.put("decided", JSONArray().apply { b.decided.sorted().takeLast(600).forEach { put(it) } })
        o.put("status", JSONObject().apply { b.status.forEach { (k, v) -> put(k, v) } })
        o.put("log", JSONArray().apply { b.log.takeLast(LOG_KEPT).forEach { put(it) } })
        o.put("migrated", JSONArray().apply { b.migrated.sorted().forEach { put(it) } })
        o.put("base", JSONObject().apply { b.base.forEach { (k, v) -> put(k, v) } })
        b.soloBase?.let { o.put("soloBase", it) }
        o.put("acted", JSONObject().apply { b.acted.forEach { (k, v) -> put(k, v) } })
        o.put("paused", JSONObject().apply { b.paused.forEach { (k, v) -> put(k, v) } })
        o.put("scores", JSONArray().apply { b.scores.takeLast(120).forEach { put(it) } })
        o.put("locks", JSONObject().apply { b.locks.filterKeys { k -> b.positions.any { it.open && it.symbol == k } }.forEach { (k, v) -> put(k, v) } })
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        runCatching { written.write(file, o.toString().toByteArray(Charsets.UTF_8)) }
        publish(b)
    }

    private fun soloBase(b: Book): SoloMidday.Baseline = b.soloBase?.split('|')?.let { runCatching { SoloMidday.Baseline(it[0].toInt(), it[1].toDouble()) }.getOrNull() }
        ?: SoloMidday.Baseline()

    private fun publish(b: Book) {
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        val closed = b.positions.filter { !it.open }
        _view.value = View(b.armed.toMap(), b.status.toMap(), b.positions.filter { it.open }, closed.takeLast(30), b.positions.filter { it.day == today },
            b.log.takeLast(40), closed, b.marks.toMap(), b.paused.toMap(), b.scores.filter { it.startsWith("$today|") }.map { it.substringAfter('|').substringAfter('|') },
            model != null, b.base.toMap(), soloBase(b))
        // 16 Oct: each open trade's own exits for the position popup's "Exits" line (memory only).
        runCatching { com.optionslab.ira.ArmExits.set("passed", b.positions.filter { it.open }.map { exitsOf(it, b.locks[it.symbol]) }) }
    }

    /**
     * [p]'s exits now, as the position popup words them: the stop on the premium (raised to a lock already earned, [lock] as
     * the book keeps it, "time|level"), the target, and the time it is out by - the time stop or the square-off, the earlier.
     * Solo 2.0's S1 is out on SoloMidday's index stop, its breakeven lock or 14:30.
     */
    internal fun exitsOf(p: Pos, lock: String?): com.optionslab.ira.ArmExits.Entry {
        if (p.setup == Solo2.Setup.S1.key) {
            val idxStop = if (p.idx != null && p.atr != null) SoloMidday.stopLevel(p.side, p.idx, p.atr) else null
            return com.optionslab.ira.ArmExits.Entry("passed", label(p.arm), false, p.symbol, null, null, SoloMidday.EXIT,
                (idxStop?.let { "stop on the index at %.2f".format(Locale.ENGLISH, it) } ?: "stop on the index") + ", breakeven lock")
        }
        val x = exitsOf(p)
        val held = lock?.split('|')?.getOrNull(1)?.toDoubleOrNull()
        val stop = listOfNotNull(PassedArms.stopLevel(p.entry, x), held).maxOrNull()
        val sq = x.squareOff
        val at = PassedArms.timeExitAt(p.entryMinute, x)?.takeIf { it.toLocalDate() == p.day && it.toLocalTime().isBefore(sq) }?.toLocalTime() ?: sq
        val step = if (held == null) x.steps.firstOrNull()?.let { (t, k) -> "+%.0f%% locked once +%.0f%%".format(Locale.ENGLISH, k * 100, t * 100) } else "lock held"
        return com.optionslab.ira.ArmExits.Entry("passed", label(p.arm), false, p.symbol, stop, PassedArms.targetLevel(p.entry, x), at,
            step?.takeIf { x.steps.isNotEmpty() })
    }

    private fun now0(): LocalDateTime = runCatching { Market.now().toLocalDateTime().withNano(0) }.getOrElse { LocalDateTime.now().withNano(0) }

    private fun note(b: Book, text: String) {
        b.log += "${now0()} $text"
        runCatching { Diag.record("passed-arms", text) }
    }

    // ---- Solo 2.0's model (read once, at start-up, off the main thread) --------------------------------------------------

    @Volatile private var model: Solo2.Model? = null
    @Volatile private var modelFailed: String? = null

    /** Reads the model from the assets once (a worker: the start-up's [refresh] or the first pass). Never on the main thread. */
    private fun ensureModel() {
        if (model != null || modelFailed != null || !ready()) return
        runCatching { app.assets.open(Solo2.ASSET).use { String(it.readBytes(), Charsets.UTF_8) } }
            .mapCatching { Solo2.parseModel(it) }
            .onSuccess { model = it }
            .onFailure { modelFailed = it.javaClass.simpleName; runCatching { Diag.record("passed-arms", "Solo 2.0's model could not be read: ${it.javaClass.simpleName}") } }
    }

    /** Read the book (the rows and the start-up's positions-first step call it, off the main thread); the model is read here once. */
    suspend fun refresh(): View {
        if (ready()) { ensureModel(); lock.withLock { publish(book()) } }
        return _view.value
    }

    /** A position is held (memory only). */
    fun holding(): Boolean = _view.value.open.isNotEmpty()

    /**
     * The watch needs this host's pass now (memory only): a position is held, or an arm is on and the time is 09:19 .. 15:16
     * (the entries run 09:20 .. 15:10; the 15:15 square-off). Outside that it costs the watch nothing.
     */
    fun wantsPass(minute: Int = runCatching { Market.minuteNow() }.getOrDefault(0)): Boolean =
        holding() || (_view.value.armed.values.any { it } && minute in WAKE_FROM until WAKE_UNTIL)

    private const val WAKE_FROM = 9 * 60 + 19
    private const val WAKE_UNTIL = 15 * 60 + 17

    /**
     * Switch [source] on or off - on paper, always. Off sells nothing it holds: its own exits still close it. [log]: Boss's flip
     * from the row (Autopilot's change log is told); false for the change log's own Undo. On after the forward bar switched it
     * off: the bar counts from now ([Book.base] / Solo 2.0's baseline).
     */
    suspend fun setArmed(source: String, on: Boolean, log: Boolean = true): String {
        if (!ready() || source !in SOURCES) return "${label(source)} is not ready yet."
        return lock.withLock {
            val b = book()
            if ((b.armed[source] == true) == on) return@withLock "${label(source)} is already ${if (on) "on" else "off"} (paper only)."
            b.armed[source] = on
            if (on && b.paused.remove(source) != null) {
                val nets = b.positions.filter { it.arm == source && !it.open }.map { it.net ?: 0.0 }
                if (source == Solo2.SOURCE) SoloMidday.baseline(nets).let { b.soloBase = "${it.from}|${it.peak}" } else b.base[source] = nets.size
            }
            note(b, "${label(source)} " + if (on) "switched on, paper only" else "switched off" +
                if (b.positions.any { it.arm == source && it.open }) "; what it holds still closes by its own exits" else "")
            save(b)
            if (log) runCatching { com.optionslab.app.ira.Autopilot.passedArmSwitchedByBoss(source, label(source), research(source), on) }
            if (on) "${label(source)} on, paper only. ${PassedArms.PASSED}." else "${label(source)} off."
        }
    }

    /** The diagnostics lines: each arm's switch, open trades, record, apart index and forward test. */
    fun diagLine(): String {
        val v = _view.value
        return SOURCES.joinToString("\n") { s ->
            val c = v.closedOf(s)
            val ap = c.filter { it.index == apart(s) }
            "${label(s)}: ${if (v.on(s)) "on (paper only)" else "off"}${v.paused[s]?.let { " (switched itself off: $it)" } ?: ""} · open ${v.open.count { it.arm == s }} · " +
                "${c.size} closed %,.0f after charges · ${apart(s)} ${ap.size} %,.0f · ".format(Locale.ENGLISH, c.sumOf { it.net ?: 0.0 }, ap.sumOf { it.net ?: 0.0 }) +
                v.forwardLine(s) + (if (s == Solo2.SOURCE) " · model ${if (v.modelLoaded) "loaded" else modelFailed ?: "not loaded yet"}" else "") +
                v.status.filterKeys { it.startsWith("$s|") }.toSortedMap().entries.joinToString("") { (k, x) -> " · ${k.substringAfter('|')} $x" }
        } + " · ${PassedArms.PASSED}"
    }

    // ---- the data the passes read --------------------------------------------------------------------------------------

    /** An index's earlier sessions (oldest first, at most ~11) and its daily candles, read once a day in the background. */
    internal class Hist(val day: LocalDate, val sessions: List<PassedArms.DayBars>, val daily: List<Bar>)

    private val hist = java.util.concurrent.ConcurrentHashMap<String, Hist>()
    @Volatile private var histTried: Pair<LocalDate, Long>? = null
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The history of [index] for [day] (null: not read yet). Memory only. Shared with the Watchlist ([WatchlistHost]). */
    internal fun history(index: String, day: LocalDate): Hist? = hist[index]?.takeIf { it.day == day }

    private fun upKey(index: String): String = runCatching { ChartFeed.instrumentKey(index) }.getOrNull()
        ?: (if (index == "SENSEX") "BSE_INDEX|SENSEX" else Upstox.INDEX_KEYS[index] ?: index)

    private fun toBars(bars: List<Upstox.Bar>): List<Bar> = bars.map { b ->
        Bar(b.istDate.atTime(b.istMinute / 60, b.istMinute % 60), b.open, b.high, b.low, b.close)
    }

    /**
     * Starts the day's history reads in the background (from 08:45; at most every 10 minutes until they are in): each index's
     * 1-minute bars of the last 16 calendar days before today and its daily candles of the last 45, and India VIX's. The passes
     * never wait for them: until they are in, a rule that needs them waits (R51 still decides from today's 21st bar).
     */
    internal fun prefetch(now: LocalDateTime, indices: List<String> = ALL_INDICES) {
        val day = now.toLocalDate()
        if (now.toLocalTime().isBefore(LocalTime.of(8, 45))) return
        if (indices.all { history(it, day) != null } && history(VIX, day) != null) return
        val t = histTried
        val ms = System.currentTimeMillis()
        if (t != null && t.first == day && ms - t.second < 10 * 60_000L) return
        histTried = day to ms
        background.launch { runCatching { prefetchNow(day, indices) } }
    }

    internal const val VIX = "INDIAVIX"

    /** [prefetch]'s reads for [day] (tests call it directly). */
    internal suspend fun prefetchNow(day: LocalDate, indices: List<String> = ALL_INDICES) {
        for (u in indices + VIX) {
            if (history(u, day) != null) continue
            runCatching {
                val key = if (u == VIX) Upstox.INDEX_KEYS.getValue("INDIAVIX") else upKey(u)
                val ones = testHistory?.invoke(u) ?: toBars(Net.history(key, day.minusDays(16), day.minusDays(1)))
                val daily = testDaily?.invoke(u) ?: toBars(Net.daily(key, day.minusDays(45), day.minusDays(1)))
                val sessions = ones.filter { it.start.toLocalDate().isBefore(day) }.groupBy { it.start.toLocalDate() }.toSortedMap()
                    .map { (d, l) -> PassedArms.DayBars.of(d, l) }.filter { it.count > 0 }
                if (sessions.isNotEmpty() || daily.isNotEmpty()) hist[u] = Hist(day, sessions, daily.filter { it.start.toLocalDate().isBefore(day) })
            }
        }
    }

    /** Today's finished 1-minute bars of [key] at [now] (the feed's, with the stream's newer whole minutes), bar start times. */
    private suspend fun minutes(key: String, now: LocalDateTime): List<Bar> {
        val nowSec = now.atZone(com.optionslab.engine.IST).toEpochSecond()
        val feed = Net.intradayQuick(key).filter { it.istDate == now.toLocalDate() && it.epochSecond + 60 <= nowSec }.sortedBy { it.epochSecond }
        val bars = runCatching { LocalCandles.overlay(key, feed, nowSec) }.getOrDefault(feed)
            .filter { it.istDate == now.toLocalDate() && it.epochSecond + 60 <= nowSec }
        return toBars(bars).sortedBy { it.start }.distinctBy { it.start }
    }

    /** [u]'s finished minutes today, read at most once a minute (shared with the Watchlist, [WatchlistHost]). */
    internal suspend fun indexBars(u: String, now: LocalDateTime): List<Bar> {
        val minute = now.withSecond(0).withNano(0)
        lastRead[u]?.takeIf { it.first == minute }?.let { return it.second }
        return minutes(upKey(u), now).also { lastRead[u] = minute to it }
    }

    /** The index's listed options saved on the phone today (SENSEX: BSE's list); never a download in the pass. */
    private fun listed(u: String): List<Upstox.Contract> {
        val bfo = u == Paper.BFO_UNDERLYING
        val saved = runCatching { if (bfo) Market.cachedBfoContracts() else Market.cachedContracts() }.getOrNull()
        if (saved == null || saved.first != Market.today()) {
            runCatching { if (bfo) Market.refreshBfoInBackground() else Market.refreshContractsInBackground() }
            return emptyList()
        }
        return saved.second.filter { it.underlying == u }
    }

    /** The reads of one pass, made outside the lock: each index's minutes today (null: unread), and India VIX's. */
    private class Reads(val bars: Map<String, List<Bar>?>, val vix: List<Bar>?)

    /** The minute each index was last read for entries (each index at most once a minute). */
    private val lastRead = java.util.concurrent.ConcurrentHashMap<String, Pair<LocalDateTime, List<Bar>>>()

    /** Whether an entry decision may be due at [t]: a 5-minute bar has just closed (its first 3 minutes), or Solo's 12:00. */
    internal fun entryMinute(t: LocalTime): Boolean {
        val m = t.hour * 60 + t.minute
        if (m < 9 * 60 + 20 || m > 15 * 60 + 12) return false
        return (m - (9 * 60 + 15)) % 5 <= 2 || m in 12 * 60 until 12 * 60 + 4
    }

    private suspend fun reads(now: LocalDateTime, v: View): Reads {
        val want = SOURCES.filter { v.on(it) }.flatMap { underlyings(it) }.distinct()
        val minute = now.withSecond(0).withNano(0)
        val out = HashMap<String, List<Bar>?>()
        for (u in want) {
            val kept = lastRead[u]?.takeIf { it.first == minute }
            if (kept != null) { out[u] = kept.second; continue }
            out[u] = runCatching { minutes(upKey(u), now) }.getOrNull()?.also { lastRead[u] = minute to it }
        }
        val vix = if (v.on(Solo2.SOURCE)) runCatching { minutes(Upstox.INDEX_KEYS.getValue("INDIAVIX"), now) }.getOrNull() else null
        return Reads(out, vix)
    }

    // ---- the passes ----------------------------------------------------------------------------------------------------

    /**
     * The fast path's minute lane (open positions first): only the open trades' exits - never an entry's reads, so these arms
     * never hold up another arm's exits. Nothing held: returns at once (memory only).
     */
    suspend fun manageOnly() {
        if (!ready() || com.optionslab.app.BuildConfig.GOLD || !holding() || !Market.isTradingDay()) return
        lock.withLock {
            val b = book()
            val changed = runCatching { exits(b, now0()) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true }
            if (changed) save(b) else publish(b)
        }
    }

    /** Only one entries phase at a time (the exits never wait for it: they take only the book's own lock). */
    private val entryLock = Mutex()

    /**
     * The watch's arms passes: the open trades' exits and the forward bars first, under the book's lock; then each arm's entries
     * (their index reads before, their price reads and orders on a copy of the book, OUTSIDE its lock), merged back under it -
     * so the minute lane's exits ([manageOnly]) never wait behind an entry's network reads.
     */
    suspend fun tick() {
        if (!ready() || com.optionslab.app.BuildConfig.GOLD || !Market.isTradingDay()) return
        val now = now0()
        if (cache == null) lock.withLock { book() }
        ensureModel()
        runCatching { prefetch(now) }
        lock.withLock {
            val b = book()
            var changed = runCatching { exits(b, now) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true }
            changed = runCatching { forward(b) }.getOrDefault(false) || changed
            changed = prune(b) || changed
            if (changed) save(b) else publish(b)
        }
        val v = _view.value
        if (!v.armed.values.any { it } || !entryMinute(now.toLocalTime())) return
        if (!entryLock.tryLock()) return
        try {
            val reads = runCatching { reads(now, v) }.getOrNull() ?: return
            val (work, n0, log0, sc0) = lock.withLock { val b = book(); Quad(b.copy(), b.positions.size, b.log.size, b.scores.size) }
            val st0 = HashMap(work.status)
            var changed = false
            if (work.armed[DonchianArm.SOURCE] == true) changed = runCatching { donchian(work, now, reads) }.getOrElse { work.status["${DonchianArm.SOURCE}|error"] = it.javaClass.simpleName; true } || changed
            if (work.armed[OovArm.SOURCE] == true) changed = runCatching { oov(work, now, reads) }.getOrElse { work.status["${OovArm.SOURCE}|error"] = it.javaClass.simpleName; true } || changed
            if (work.armed[Solo2.SOURCE] == true) changed = runCatching { solo2(work, now, reads) }.getOrElse { work.status["${Solo2.SOURCE}|error"] = it.javaClass.simpleName; true } || changed
            if (!changed) return
            lock.withLock {
                val b = book()
                // 16 Oct: each entry is booked in the book the moment it fills ([buy]); only one that is not there yet is added.
                val added = work.positions.drop(n0).filter { a -> b.positions.none { same(it, a) } }
                b.positions += added
                added.forEach { p -> work.marks[p.symbol]?.let { b.marks[p.symbol] = it } }
                b.decided += work.decided
                work.status.forEach { (k, x) -> if (st0[k] != x) b.status[k] = x }
                b.log += work.log.drop(log0)
                b.scores += work.scores.drop(sc0)
                save(b)
            }
        } finally { entryLock.unlock() }
    }

    private data class Quad(val a: Book, val b: Int, val c: Int, val d: Int)

    /** The same trade: one paper order (or, with none, the same option bought at the same time). */
    private fun same(a: Pos, b: Pos): Boolean = a.symbol == b.symbol && if (a.orderId != null || b.orderId != null) a.orderId == b.orderId else a.entryTime == b.entryTime

    /** The options an entry is buying now (between its order and its booking): the safety net never takes them for orphans. */
    private val inFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    internal const val ADOPT_FILL = "fill"

    /** The options these arms hold or are buying now (memory only): the safety net's ([OrphanWatch]) "watched here". */
    fun claims(): Set<String> = _view.value.open.map { it.symbol }.toSet() + inFlight

    /** Closed trades kept: the last [CLOSED_KEPT] of each arm; the forward bars' baselines move with what is dropped. */
    private fun prune(b: Book): Boolean {
        var changed = false
        for (s in SOURCES) {
            val closed = b.positions.filter { it.arm == s && !it.open }
            val drop = closed.size - CLOSED_KEPT
            if (drop <= 0) continue
            val gone = closed.take(drop).toSet()
            b.positions.removeAll { it in gone }
            b.base[s]?.let { b.base[s] = (it - drop).coerceAtLeast(PassedArms.ForwardBar.TRADES) }
            if (s == Solo2.SOURCE) soloBase(b).takeIf { it.from > 0 }?.let { x -> b.soloBase = "${(x.from - drop).coerceAtLeast(0)}|${x.peak - gone.sumOf { it.net ?: 0.0 }}" }
            changed = true
        }
        return changed
    }

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun px(x: Double) = "%.2f".format(Locale.ENGLISH, x)

    private fun say(b: Book, key: String, why: String, final: Boolean = false): Boolean {
        if (final) note(b, "${label(key.substringBefore('|'))} ${key.substringAfter('|', "")}: $why")
        if (b.status[key] == why) return final
        b.status[key] = why
        return true
    }

    // ---- the forward bars ----------------------------------------------------------------------------------------------

    /** Each arm's forward bar on its closed trades: a failed bar switches it off once (risk-lowering, logged). */
    private fun forward(b: Book): Boolean {
        var changed = false
        for (s in SOURCES) {
            if (b.armed[s] != true) continue
            val c = b.positions.filter { it.arm == s && !it.open }
            val nets = c.map { it.net ?: 0.0 }
            val (fail, key, why) = if (s == Solo2.SOURCE) {
                val base = soloBase(b)
                val chk = SoloMidday.judge(nets, base)
                Triple(SoloMidday.switchOff(chk.verdict) && !base.actedOn(b.acted[s], chk.verdict), base.key(chk.verdict),
                    if (chk.verdict == SoloMidday.Verdict.FAILED_DRAWDOWN) "a drawdown of ${SoloMidday.rs(chk.drawdown)}, beyond −₹25,000"
                    else "${chk.record.trades} trades at ${SoloMidday.rs(chk.record.perTrade ?: 0.0)} a trade (it needs more than ₹0)")
            } else {
                val base = b.base[s] ?: 0
                val chk = PassedArms.ForwardBar.judge(nets, c.map { it.spread }, base)
                val k = "${chk.verdict}@$base"
                Triple(PassedArms.ForwardBar.switchOff(chk.verdict) && b.acted[s] != k, k,
                    if (chk.verdict == PassedArms.ForwardBar.Verdict.FAILED_NET) "net ${PassedArms.rs(chk.judgedNet)} after 40 paper trades (below ₹0)"
                    else "net ${PassedArms.rs(chk.judgedNet)} after 40 paper trades is below the cost of twice the spread (${PassedArms.rs(chk.judgedSpread)})")
            }
            if (!fail) continue
            b.armed[s] = false
            b.acted[s] = key
            b.paused[s] = why
            note(b, "${label(s)} switched itself off on its forward-test bar: $why (paper; switching it back on is Boss's choice)")
            runCatching { com.optionslab.app.ira.Autopilot.logPassedArmOff(s, label(s), why) }
            runCatching { Notifier.post(app, NOTICE + 20 + SOURCES.indexOf(s), Notifier.SELL, "${label(s)} switched itself off · Paper",
                "Its forward-test bar, set in advance: $why. What it holds closes by its own exits. Switching it back on is your choice.", "almanac") }
            changed = true
        }
        return changed
    }

    // ---- entries -------------------------------------------------------------------------------------------------------

    /** Whether the bot is stopped for today (Boss's stop, or the daily loss limit). Unknown counts as stopped. */
    private suspend fun stoppedToday(): Boolean =
        runCatching { Strategies.stoppedToday() }.getOrDefault(true) || runCatching { LossBreaker.trippedToday() }.getOrDefault(false)

    /** [u]'s expiry today by the list saved on the phone (null: no list yet - nothing is bought). */
    private fun expiryToday(u: String, today: LocalDate): Boolean? {
        val l = listed(u)
        if (l.isEmpty()) return null
        return HeroRules.isExpiryDay(today, l.map { it.expiry }.distinct())
    }

    /** The day's sessions of [u] up to now: the earlier ones (history) and today's. */
    private fun todayBars(u: String, today: LocalDate, reads: Reads): PassedArms.DayBars? = reads.bars[u]?.let { PassedArms.DayBars.of(today, it) }

    /**
     * Buys [strikes] (the first that is listed and fills) on [u] for [arm]: the guards, then a MARKET buy on the paper account.
     * The position, or why not (null position).
     */
    private suspend fun buy(b: Book, arm: String, u: String, side: Int, strikes: List<Int>, now: LocalDateTime, setup: String? = null,
                            idx: Double? = null, atr: Double? = null, keys: List<String> = emptyList()): Pair<Pos?, String> {
        val today = now.toLocalDate()
        val right = if (side > 0) Right.CE else Right.PE
        val expiry = OrbRules.expiryAfter(today, listed(u).map { it.expiry }.distinct()) ?: return null to "no $u expiry after today is listed"
        if (runCatching { AppSettings.load().guardKill }.getOrDefault(true)) return null to "refused: the kill switch is on"
        if (stoppedToday()) return null to "refused: the bot is stopped for today"
        runCatching { DayLockGuard.refusal(false) }.getOrNull()?.let { return null to "refused: $it" }
        var why = "no listed strike"
        for (k in strikes) {
            val c = Paper.ticketContract(u, expiry, k.toDouble(), right) ?: run { why = "$k ${right.name} not listed"; null } ?: continue
            if (c.lotSize <= 0) { why = "lot size unknown"; continue }
            // One contract, one arm: never two of these arms holding the same option (each one's exits read its own lot).
            b.positions.firstOrNull { it.open && it.symbol == c.symbol }?.let { why = "${c.symbol} is held by ${label(it.arm)}"; return null to why }
            // (and the book itself: one the safety net took back since this pass's copy was made)
            if (c.symbol in _view.value.open.map { it.symbol }) { why = "${c.symbol} is held by these arms"; return null to why }
            val q = runCatching { Paper.quote(c) }.getOrNull()
            val ref = q?.ltp?.takeIf { it > 0 } ?: run { why = "${c.symbol}: no fresh price"; null } ?: continue
            val snap = runCatching { Paper.snapshot() }.getOrNull()
            val refusals = Guard.check(Guard.paperOrder(c, "BUY", 1, ref), snap?.let { Guard.paperAccount(it) }, paper = true)
            if (refusals.isNotEmpty()) return null to "refused by the account guard: " + refusals.joinToString(" ")
            runCatching { LiveRecorder.signal(label(arm), c.symbol, u, right.name, ref, paper = true) }
            inFlight += c.symbol
            val p: Pos = (try {
                val r = Paper.place(c, "BUY", 1, "MARKET", "MIS", null, null, known = q)
                val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
                if (fill == null) {
                    r.orderId?.takeIf { r.ok }?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
                    why = "${c.symbol} not filled (${r.message})"
                    null
                } else {
                    val charges = Paper.state.trades.filter { it.orderId == r.orderId }.sumOf { it.charges.toDouble() }
                    val pos = Pos(arm, u, c.symbol, right.name, fill.quantity, fill.price, now, r.orderId, setup, ref, idx, atr, charges = charges)
                    b.positions += pos
                    b.marks[c.symbol] = fill.price
                    b.decided += keys
                    // 16 Oct (the unwatched re-entry): booked in the book itself at once, never only on the entries' copy until the
                    // end of the pass - its stop, target and time exit run from the next minute-lane look, and a pass cut short or
                    // a restart after the fill can never leave it held with no record here. Not cancellable between the two.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { record(pos, keys) }
                    r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${label(arm)} · entry") } }
                    runCatching { Notifier.orderFilled(app, "BUY", fill.quantity, fill.symbol, fill.price, "Paper", "${label(arm)} · entry", r.orderId) }
                    pos
                }
            } finally { inFlight -= c.symbol }) ?: continue
            runCatching { Notifier.post(app, NOTICE + SOURCES.indexOf(arm) * 5 + ALL_INDICES.indexOf(u).coerceAtLeast(0), Notifier.BUY,
                "${label(arm)} bought ${c.symbol} · Paper",
                "Bought %d @ %s. %s. %s.".format(Locale.ENGLISH, p.qty, px(p.entry), exitWords(p), PassedArms.PASSED), "almanac") }
            note(b, "${label(arm)} bought ${c.symbol} ${p.qty} @ ${px(p.entry)} · paper" + (setup?.let { " ($it)" } ?: ""))
            return p to "bought"
        }
        return null to why
    }

    /**
     * [p] (just filled, on the entries' copy) booked in the book itself, with its decisions [keys]; saved. One the safety net
     * took back meanwhile (the same order) is replaced by it, while still open.
     */
    private suspend fun record(p: Pos, keys: List<String>) = lock.withLock {
        val r = book()
        val i = r.positions.indexOfFirst { same(it, p) }
        when {
            i < 0 -> r.positions += p
            r.positions[i].adopted != null && r.positions[i].open -> r.positions[i] = p
        }
        r.marks[p.symbol] = p.entry
        r.decided += keys
        save(r)
    }

    private fun exitWords(p: Pos): String = when {
        p.setup == Solo2.Setup.S1.key -> "Out on SoloMidday's index stop, at breakeven or at 14:30"
        else -> exitsOf(p).let { x ->
            listOfNotNull(x.stop?.let { "stop −%.0f%%".format(Locale.ENGLISH, it * 100) }, x.target?.let { "target +%.0f%%".format(Locale.ENGLISH, it * 100) },
                x.steps.firstOrNull()?.let { (t, k) -> "+%.0f%% locked once +%.0f%%".format(Locale.ENGLISH, k * 100, t * 100) },
                x.tmaxMinutes?.let { "out after $it minutes" }, "square-off ${x.squareOff}").joinToString(", ")
        }
    }

    /** S1's premium backstop when its index cannot be read: the Solo layer's -30% stop, SoloMidday's 14:30. */
    private val S1_BACKSTOP = PassedArms.PremiumExits(0.30, null, null, emptyList(), SoloMidday.EXIT)

    private fun exitsOf(p: Pos): PassedArms.PremiumExits = when (p.arm) {
        DonchianArm.SOURCE -> DonchianArm.EXITS
        OovArm.SOURCE -> OovArm.EXITS
        else -> Solo2.exits(Solo2.Setup.entries.firstOrNull { it.key == p.setup } ?: Solo2.Setup.S2)
    }

    /** The continuous 5-minute series of [u]: the earlier sessions' (history, when read) and today's. */
    private fun series(u: String, today: PassedArms.DayBars): List<PassedArms.Bar5> {
        val h = history(u, today.date)?.sessions.orEmpty().takeLast(2)
        return h.flatMap { PassedArms.bars5(it) } + PassedArms.bars5(today, today.lastRow)
    }

    private suspend fun donchian(b: Book, now: LocalDateTime, reads: Reads): Boolean {
        val src = DonchianArm.SOURCE
        val today = now.toLocalDate()
        var changed = false
        for (u in DonchianArm.UNDERLYINGS) {
            val key = "$src|$u"
            val d = todayBars(u, today, reads) ?: continue
            when (expiryToday(u, today)) {
                null -> { changed = say(b, key, "waiting for the options list") || changed; continue }
                true -> { changed = say(b, key, "its expiry day: no trades") || changed; continue }
                false -> Unit
            }
            for (s in DonchianArm.signals(u, series(u, d), today)) {
                val dk = "$today|$src|$u|${s.bar.b}|${s.side}"
                if (dk in b.decided) continue
                if (now.isBefore(s.entryTime)) continue
                if (!DonchianArm.mayFill(s, now)) { b.decided += dk; changed = say(b, key, "${s.entryTime.toLocalTime()} ${if (s.side > 0) "call" else "put"} break missed (the fill window passed)", final = true) || changed; continue }
                val open = b.positions.filter { it.arm == src && it.index == u && it.open }.map { it.side }.toSet()
                val taken = b.positions.filter { it.arm == src && it.index == u && it.day == today }.groupingBy { it.side }.eachCount()
                val skip = DonchianArm.mayTake(s, open, taken)
                if (skip != null) { b.decided += dk; changed = say(b, key, "${s.entryTime.toLocalTime()} break skipped: $skip") || changed; continue }
                val (p, why) = buy(b, src, u, s.side, listOf(s.strike), now, keys = listOf(dk))
                changed = true
                if (p != null) { b.decided += dk; say(b, key, "bought ${p.symbol} @ ${px(p.entry)} (${s.entryTime.toLocalTime()} break)") }
                else say(b, key, "${s.entryTime.toLocalTime()} break: $why; tried again until ${s.entryTime.plusMinutes(DonchianArm.FILL_SLACK_MINUTES).toLocalTime()}")
            }
        }
        return changed
    }

    private suspend fun oov(b: Book, now: LocalDateTime, reads: Reads): Boolean {
        val src = OovArm.SOURCE
        val today = now.toLocalDate()
        val t = now.toLocalTime()
        if (t.isBefore(OovArm.DECIDE_AT)) return false
        var changed = false
        for (u in OovArm.UNDERLYINGS) {
            val dk = "$today|$src|$u"
            if (dk in b.decided) continue
            val key = "$src|$u"
            if (!OovArm.mayFill(t)) { b.decided += dk; changed = say(b, key, "the 09:20 decision was missed (09:20-09:22 only)") || changed; continue }
            val d = todayBars(u, today, reads) ?: continue
            when (expiryToday(u, today)) {
                null -> { changed = say(b, key, "waiting for the options list") || changed; continue }
                true -> { b.decided += dk; changed = say(b, key, "its expiry day: no trade") || changed; continue }
                false -> Unit
            }
            val prev = history(u, today)?.sessions?.lastOrNull() ?: run { changed = say(b, key, "waiting for the previous session's bars") || changed; null } ?: continue
            val first = d.through(4)
            if (first.lastRow < 4) continue
            val side = OovArm.signal(first, OovArm.valueArea(prev))
            if (side == 0) { b.decided += dk; changed = say(b, key, "opened inside (or at) yesterday's value area: no trade today") || changed; continue }
            val (p, why) = buy(b, src, u, side, listOf(OovArm.strike(u, side, first)), now, keys = listOf(dk))
            changed = true
            if (p != null) { b.decided += dk; say(b, key, "bought ${p.symbol} @ ${px(p.entry)} (opened outside value)") }
            else say(b, key, "$why; tried again until 09:22")
        }
        return changed
    }

    /** Solo 2.0's context inputs for [u] at the signal row (what the phone has; the rest NaN for the model's medians). */
    private fun context(u: String, today: LocalDate, now: LocalDateTime, reads: Reads): Solo2.Context {
        val h = history(u, today)
        val prevClose = h?.sessions?.lastOrNull()?.lastClose ?: h?.daily?.lastOrNull()?.close ?: Double.NaN
        val ranges = h?.sessions.orEmpty().takeLast(10).map { it.high - it.low }
        val atr = h?.daily?.let { ShadowRules.atr14(it, today) } ?: Double.NaN
        val vixDay = reads.vix?.let { PassedArms.DayBars.of(today, it).c }
        val vixPrev = history(VIX, today)?.let { it.sessions.lastOrNull()?.lastClose ?: it.daily.lastOrNull()?.close } ?: Double.NaN
        val expiries = listed(u).map { it.expiry }.distinct()
        val dte = Solo2.dte(today, expiries, { d -> runCatching { Market.isTradingDay(d) }.getOrDefault(true) })
        val chain = runCatching {
            val snap = MemoryChain.read(u)
            val spot = snap.spot ?: return@runCatching null
            val exp = snap.expiry ?: return@runCatching null
            val years = java.time.Duration.between(now, exp.atTime(15, 30)).toMinutes() / (365.0 * 24 * 60)
            Solo2.chainContext(spot, PassedArms.step(u), snap.legs.map { Solo2.Leg(it.strike, it.right == "CE", it.ltp, it.oi) }, years)
        }.getOrNull()
        return Solo2.Context(u, prevClose, ranges, atr, vixDay, vixPrev, dte, chain?.pcr5 ?: Double.NaN, chain?.ivAtm ?: Double.NaN, chain?.ivSkew ?: Double.NaN)
    }

    private suspend fun solo2(b: Book, now: LocalDateTime, reads: Reads): Boolean {
        val src = Solo2.SOURCE
        val m = model ?: return say(b, "$src|model", "its model is not loaded" + (modelFailed?.let { " ($it)" } ?: " yet"))
        val today = now.toLocalDate()
        val dayKey = "$today|$src"
        if (dayKey in b.decided) return false
        // One position at a time (one trade a day, then nothing more).
        if (b.positions.any { it.arm == src && it.open }) return false
        var changed = false
        val cands = ArrayList<Pair<Solo2.Candidate, PassedArms.DayBars>>()
        for (u in Solo2.UNDERLYINGS) {
            val d = todayBars(u, today, reads) ?: continue
            when (expiryToday(u, today)) {
                null -> { changed = say(b, "$src|$u", "waiting for the options list") || changed; continue }
                true -> { changed = say(b, "$src|$u", "its expiry day: never a candidate") || changed; continue }
                false -> Unit
            }
            val h = history(u, today)
            Solo2.s3(u, d.through(4), h?.sessions?.lastOrNull()?.let { OovArm.valueArea(it) })?.let { cands += it to d }
            Solo2.s4(u, d)?.let { cands += it to d }
            Solo2.s2Taken(Solo2.s2(DonchianArm.signals(u, series(u, d), today))).forEach { cands += it to d }
            val atr = h?.daily?.let { ShadowRules.atr14(it, today) }
            val ones = reads.bars[u].orEmpty()
            Solo2.s1(u, SoloMidday.signal(u, ones, atr, now))?.let { cands += it to d }
        }
        // Only the candidates whose entry minute is now (or up to 2 minutes ago): earlier ones were decided when they came.
        val live = cands.filter { (c, _) -> val e = c.entryTime(today); !now.isBefore(e) && now.isBefore(e.plusMinutes(3)) && "$today|$src|${c.key}" !in b.decided }
        if (live.isEmpty()) return changed
        // Each candidate's 29 inputs are kept with its score: the decision's words (15 Oct: "why", explanation only) come from them.
        val scored = live.map { (c, d) ->
            val x = runCatching { Solo2.features(d, c, context(c.underlying, today, now, reads)) }.getOrNull()
            Triple(Solo2.Scored(c, x?.let { runCatching { m.score(it) }.getOrNull() } ?: Double.NaN), d, x)
        }
        for ((sc, _, x) in scored.sortedWith(compareBy<Triple<Solo2.Scored, PassedArms.DayBars, DoubleArray?>> { it.first.c.entryRow }.thenByDescending { it.first.score })) {
            val c = sc.c
            val ck = "$today|$src|${c.key}"
            val words = "%s %s %s %.4f".format(Locale.ENGLISH, c.setup.key, c.underlying, if (c.side > 0) "call" else "put", sc.score)
            // Why, in words (memory only, from the numbers in hand: no disk, network or settings read here).
            // (Guarded: the words can never stop or change a decision.)
            val because = runCatching { Solo2.reason(x, m) }.getOrDefault("").let { if (it.isEmpty()) "" else " · $it" }
            if (!sc.score.isFinite() || !m.take(sc.score)) {
                b.decided += ck; b.scores += "$today|${c.key.replace('|', ':')}|$words: below ${"%.4f".format(Locale.ENGLISH, m.threshold)}, skipped$because"; changed = true
                continue
            }
            val (p, why) = buy(b, src, c.underlying, c.side, c.strikes, now, c.setup.key, c.index, c.atr, keys = listOf(ck, dayKey))
            changed = true
            if (p != null) {
                b.decided += ck; b.decided += dayKey
                b.scores += "$today|${c.key.replace('|', ':')}|$words: bought ${p.symbol}$because"
                say(b, src, "bought ${p.symbol} @ ${px(p.entry)}: ${Solo2.why(c, sc.score, m)}", final = true)
                return true
            }
            // R57 takes the day's first passing candidate and nothing more that day: one that cannot be bought ends the day too.
            b.decided += ck; b.decided += dayKey
            b.scores += "$today|${c.key.replace('|', ':')}|$words: passed, not bought ($why)$because"
            say(b, src, "${c.setup.key} ${c.underlying} passed but could not be bought: $why; nothing more today (one pick a day)", final = true)
            return true
        }
        return changed
    }

    // ---- exits ---------------------------------------------------------------------------------------------------------

    private fun chargesOf(orderId: String?): Double =
        if (orderId == null) 0.0 else Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    /**
     * Each open trade's exit, when due. True when [b] changed. Each trade in its own try (16 Oct): one that fails is said
     * (its status and Diag, once per reason) and the others' exits still run - never one failure stopping every exit.
     */
    private suspend fun exits(b: Book, now: LocalDateTime): Boolean {
        var changed = false
        val stopped = stoppedToday()
        for ((i, p) in b.positions.withIndex().toList()) {
            if (!p.open) continue
            try {
                if (exitOne(b, i, p, now, stopped)) changed = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val k = "${p.arm}|exit"
                val w = "exit check failed on ${p.symbol}: ${e.javaClass.simpleName}; tried again next look"
                if (b.status[k] != w) { b.status[k] = w; note(b, w); changed = true }
            }
        }
        return changed
    }

    /** The stream's last price counts as the price now for this long (ms). */
    private const val FRESH_MS = 15_000L

    /** [p]'s exit (at [i] in [b]), when due. True when [b] changed. */
    private suspend fun exitOne(b: Book, i: Int, p: Pos, now: LocalDateTime, stopped: Boolean): Boolean {
        val c = Paper.contractOf(p.symbol)
        if (c == null) {
            // The paper book no longer knows the contract (a reset): nothing is held there, so the trade is closed at its entry.
            val done = p.copy(exit = p.entry, exitTime = now, why = "closed_elsewhere")
            b.positions[i] = done; closed(b, done); return true
        }
        // Gone from the paper book without this host selling it (the loss limit, Boss, a square-off): closed there.
        val held = Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity }
        if (held <= 0) {
            val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
            val done = p.copy(exit = t?.price?.toDouble() ?: p.entry, exitTime = t?.timestamp ?: now, why = "closed_elsewhere",
                charges = p.charges + (t?.charges?.toDouble() ?: 0.0))
            b.positions[i] = done; closed(b, done); return true
        }
        val overnight = p.day.isBefore(now.toLocalDate())
        val bars = if (!overnight) runCatching { minutes(c.feedKey, now) }.getOrNull().orEmpty() else emptyList()
        // The stream's price only while fresh (16 Oct: a tick left from a stream that stopped following the option is not
        // the price now); else the last closed minute, else a quote.
        val ltp = runCatching { Paper.bookNow(c)?.takeIf { it.ageMs <= FRESH_MS }?.last }.getOrNull()?.takeIf { it > 0 } ?: bars.lastOrNull()?.close
            ?: if (!overnight) runCatching { Paper.quote(c)?.ltp }.getOrNull()?.takeIf { it > 0 } else null
        ltp?.let { b.marks[p.symbol] = it }
        val known = b.locks[p.symbol]?.split('|')?.let { runCatching { LocalDateTime.parse(it[0]) to it[1].toDouble() }.getOrNull() }
        val why: String? = when {
            overnight -> "overnight"
            p.setup == Solo2.Setup.S1.key -> {
                val ones = runCatching { minutes(upKey(p.index), now) }.getOrNull().orEmpty()
                val w = SoloMidday.walk(SoloMidday.Open(p.index, p.side, p.idx ?: Double.NaN, p.atr ?: Double.NaN, p.entryMinute), ones, now)
                // The index unread (or its levels unknown): the premium backstop, -30% (the Solo layer's stop), so it never runs unwatched.
                w.exit ?: if (ones.isEmpty() || p.idx == null || p.atr == null)
                    PassedArms.exitOnBars(p.entry, p.entryTime.withSecond(0).withNano(0), bars, ltp, now, S1_BACKSTOP).exit?.kind?.let { "backstop_" + it.why } else null
            }
            else -> PassedArms.exitOnBars(p.entry, p.entryMinute, bars, ltp, now, exitsOf(p), known).also { w ->
                w.lock?.let { lv -> if (known == null || lv > known.second) b.locks[p.symbol] = "$now|$lv" }
            }.exit?.kind?.why
        } ?: if (stopped) "operator_stop" else null
        if (why == null) return false
        val next = sell(b, p, c, why, ltp)
        if (next != p) { b.positions[i] = next; return true }
        return false
    }

    /** Sells all of [p] at market on paper (a target is booked at the target at most). */
    private suspend fun sell(b: Book, p: Pos, c: Paper.Contract, why: String, ltp: Double?): Pos {
        val lots = (p.qty / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
        val r = Paper.place(c, "SELL", lots, "MARKET", "MIS", null, null)
        val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (fill == null) {
            r.orderId?.takeIf { r.ok }?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
            val k = "${p.arm}|exit"
            if (b.status[k] != "exit_retry ${p.symbol}") { b.status[k] = "exit_retry ${p.symbol}"; note(b, "${p.symbol}: the $why exit did not fill (${r.message}); tried again next pass") }
            return p
        }
        r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${label(p.arm)} · exit") } }
        runCatching { Notifier.orderFilled(app, "SELL", fill.quantity, fill.symbol, fill.price, "Paper", "${label(p.arm)} · exit", r.orderId) }
        val target = PassedArms.targetLevel(p.entry, exitsOf(p))
        val booked = if (why == PassedArms.ExitKind.TARGET.why) PassedArms.booked(PassedArms.ExitKind.TARGET, fill.price, target) else fill.price
        val done = p.copy(exit = booked, exitTime = now0(), why = why, charges = p.charges + chargesOf(r.orderId), exitRef = ltp,
            paperFill = fill.price.takeIf { kotlin.math.abs(it - booked) > 1e-9 })
        b.marks.remove(p.symbol); b.locks.remove(p.symbol)
        closed(b, done)
        return done
    }

    private fun closed(b: Book, p: Pos) {
        // 16 Oct: on every way out (closed elsewhere too), so a later trade on the same option never reads this one's lock.
        b.marks.remove(p.symbol); b.locks.remove(p.symbol)
        b.status["${p.arm}|${p.index}"] = "closed ${p.symbol}: ${p.why}"
        note(b, "%s: %s sold (%s) @ %s, %s after charges · paper".format(Locale.ENGLISH, label(p.arm), p.symbol, p.why, px(p.exit ?: 0.0), rs(p.net ?: 0.0)))
        runCatching { Notifier.post(app, NOTICE + 40 + SOURCES.indexOf(p.arm) * 5 + ALL_INDICES.indexOf(p.index).coerceAtLeast(0), Notifier.SELL,
            "${label(p.arm)} sold ${p.symbol} · Paper",
            "Sold (%s) @ %s: %s after charges. %s.".format(Locale.ENGLISH, p.why, px(p.exit ?: 0.0), rs(p.net ?: 0.0), PassedArms.PASSED), "almanac") }
    }

    // ---- the safety net (16 Oct) ----------------------------------------------------------------------------------------

    /** The arm whose owner label is [owner] ("R51 Donchian 20"), or null. */
    fun armOf(owner: String): String? = SOURCES.firstOrNull { label(it) == owner }

    /**
     * Takes back [o], a paper long one of these arms opened that has no open record here ([OrphanWatch] found it unwatched):
     * booked as its arm's trade from its real fill and fill time, so its stop, target and time exit run from the next look.
     * Said plainly to Boss (a notice, the log, Diag). The words,
     * or null when it is not taken (not these arms', already held here, being bought now, or the contract unknown).
     */
    suspend fun adopt(o: com.optionslab.engine.orb.OrphanAudit.Orphan): String? {
        if (!ready() || com.optionslab.app.BuildConfig.GOLD) return null
        val arm = armOf(o.owner) ?: return null
        if (o.symbol in inFlight) return null
        val c = Paper.contractOf(o.symbol) ?: return null
        return lock.withLock {
            val b = book()
            if (b.positions.any { it.open && it.symbol == o.symbol }) return@withLock null
            val now = now0()
            val p = Pos(arm, c.underlying, o.symbol, c.right.name, o.qty, o.fill, o.at ?: now, o.orderId, charges = chargesOf(o.orderId),
                adopted = ADOPT_FILL)
            b.positions += p
            b.marks[p.symbol] = p.entry
            val words = "${label(arm)}: ${p.symbol} ${p.qty} was held on paper with no record here, so its exits were not running; " +
                "taken back as its trade (filled @ ${px(p.entry)} at ${p.entryTime.toLocalTime()})" +
                ". ${exitWords(p)}"
            b.status["$arm|${p.index}"] = "taken back ${p.symbol} (safety net)"
            note(b, words)
            save(b)
            runCatching { Notifier.post(app, NOTICE + 60 + SOURCES.indexOf(arm) * 5 + ALL_INDICES.indexOf(p.index).coerceAtLeast(0), Notifier.SELL,
                "${label(arm)}: ${p.symbol} taken back · Paper", "$words. ${PassedArms.PASSED}.", "almanac") }
            words
        }
    }

    // ---- Jarvis ----------------------------------------------------------------------------------------------------------

    /** What Jarvis says ([com.optionslab.ira.PassedArmsTalk]): the arms' book read off the main thread, and the Watchlist's log. */
    suspend fun talk(topic: com.optionslab.ira.PassedArmsTalk.Topic): String = com.optionslab.ira.PassedArmsTalk.answer(topic, facts(refresh()))

    internal fun facts(v: View): com.optionslab.ira.PassedArmsTalk.Facts {
        val arms = SOURCES.map { s ->
            val c = v.closedOf(s)
            val ap = c.filter { it.index == apart(s) }
            com.optionslab.ira.PassedArmsTalk.Arm(s, v.armed[s] ?: false, v.todayOf(s).map { tradeLine(it, v.marks) }, c.size, c.sumOf { it.net ?: 0.0 },
                c.count { (it.net ?: 0.0) > 0 }, ap.size, ap.sumOf { it.net ?: 0.0 }, v.forwardLine(s), v.paused[s],
                if (s == Solo2.SOURCE) v.scores else emptyList())
        }
        val w = runCatching { WatchlistHost.cards() }.getOrDefault(emptyList()).map { com.optionslab.engine.orb.Watchlist.line(it) }
        return com.optionslab.ira.PassedArmsTalk.Facts(arms, w, runCatching { WatchlistHost.on() }.getOrNull(), v.modelLoaded)
    }

    /** One trade in words (its row and Jarvis). */
    fun tradeLine(p: Pos, marks: Map<String, Double>): String =
        if (p.open) "${p.symbol} ${p.qty} @ ${px(p.entry)}" + (marks[p.symbol]?.let { " · now ${px(it)}" } ?: "") + (p.setup?.let { " ($it)" } ?: "") + " · paper"
        else "${p.symbol} ${p.qty} @ ${px(p.entry)} → ${px(p.exit ?: 0.0)} (${p.why}) · ${rs(p.net ?: 0.0)}"

    /** For tests: the book forgotten (its file goes with the test's directory), and the day's memory. */
    internal fun wipe() { cache = null; _view.value = View(); hist.clear(); histTried = null; lastRead.clear() }

    /** TEST ONLY: closed trades added to the book as if traded (the forward bars' tests). Debug builds only. */
    internal suspend fun addClosedForTest(list: List<Pos>) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "a test seam" }
        lock.withLock { val b = book(); b.positions += list; save(b) }
    }

    /** For tests: the model forgotten (read again on the next pass). */
    internal fun forgetModel() { model = null; modelFailed = null }
}
