package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.options.OptionMath
import com.optionslab.engine.options.OptionType
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.CisdNet
import com.optionslab.engine.orb.PassedArms
import com.optionslab.engine.orb.S5Trail
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/**
 * "CISD + network" (29 Oct 2026, Boss: "Build CISD (ICT's version) + network + index stop + lock. And add it as a arm, and do forward
 * test too"): research R133's S3-V2-E3 ([CisdNet]) as a PAPER arm. IT FAILED THE RESEARCH GATES - Boss's second exception (CLAUDE.md,
 * 29 Oct), labelled [CisdNet.SHOWN] on its ticket, its row, the timeline, the diagnostics and by Jarvis. 30 Oct (Boss: "Make them live
 * s5 and csid startergies live too"; CLAUDE.md, 30 Oct): its paper trades are mirrored live by [ArmLive] ([LiveMirror.CISD]) through
 * the same gates as every arm: the entry (1 lot), a labelled safety stop at Zerodha while only the index stop guards it, the lock's
 * level as the live stop once it rests, and every paper exit (the index stop, the lock, the square-off) sells the live trade.
 *
 * SIGNAL SOURCE: the index (the default). At each 5-minute bar close from 09:20 to 15:00, for each of the five indices, R131's CISD
 * (ICT's reading) is looked for on the index's 5-minute bars - today's minutes and the earlier sessions the passed arms already read
 * ([PassedArmsHost.indexBars], [PassedArmsHost.history]). Only when a setup passes R131's filters is R123's network run (the same
 * model, inputs and chain reads as S5 Trail: [S5Arm.chainBars]; the option chain is context only), on this pass's worker, outside
 * the lock, never on the tick or order path. Taken when the network's mean EV for that side is above 0.
 *
 * The trade: 1 lot of the ATM option (the index's last close before the entry, half up) of the nearest expiry - on its expiry day the
 * monthly, none when that expires too (R131's series rule) - a MARKET BUY on the PAPER account. Exits (R132 E3): the index stop on
 * the index's ticks ([KiteStream.lowSince] / [KiteStream.highSince] since the last look: every tick counts); once the option's best
 * price of earlier minutes is up ₹1,000 on the lot, a SELL SL-M rests in the paper book at the entry + 60% of that gain (raised as
 * the best rises) and the arm also sells when a look sees the price at or under it; else 15:15 (the paper book's MIS square-off;
 * the research held to 15:20). One trade at a time per index: a setup the network or a guard refused still keeps the index busy
 * until the research would have closed it ([Phantom]; R133 applied the one-at-a-time rule before the filter).
 *
 * Guards (none bypassed): the kill switch, the bot's stop for today, the day lock, the account guard, the paper account's limits and
 * the same-direction cap (inside [Paper.place]), market days and times, R131's expiry rule.
 *
 * Its switch: ON on paper, once, by this update on Boss's ask - logged in Autopilot's timeline as Boss's, undone in one tap; after
 * that only Boss switches it. Its open trades are watched in the minute lane and the 15-second stop check ([manageOnly]: memory, the
 * stream and the paper book only), and its book is read FIRST after a restart ([PositionsFirst]).
 */
object CisdArm {
    private const val FILE = "cisd_arm.vault"
    const val EXITS_KEY = "cisdnet"
    private const val STREAM_OWNER = "cisdnet"
    private val lock = Mutex()
    private val passLock = Mutex()
    @Volatile private var file: File? = null
    private val written = Vault.LastWrite()

    fun init(context: Context) {
        file = File(context.applicationContext.noBackupFilesDir, FILE)
        // 30 Oct: its paper trades for the live mirror (memory only): today's and the open ones, each with what it still holds; null
        // while its book is not read (nothing is decided on an unread book: a live trade is never sold for want of it).
        ArmLive.register(com.optionslab.engine.orb.LiveMirror.CISD) { if (cache == null) null else legs(_view.value, today()) }
    }

    /** [v]'s paper trades for the live mirror: the open ones and those closed on [day] (0 held). */
    internal fun legs(v: View, day: LocalDate): List<ArmLive.PaperLeg> =
        (v.open + v.today.filter { !it.open }).distinctBy { it.key }.map { ArmLive.PaperLeg(it.key, CisdNet.SOURCE, if (it.open) it.qty else 0) }

    /** The first-start note of 29 Oct (built switched OFF, [CisdNet.IV_GAP]); in [Book.migrated] once done, never again. */
    internal const val BUILT_OFF = "built_off_2026-10-29"

    /** The square-off: the paper book's own MIS square-off (15:15; R131 held to 15:20). */
    val SQ: LocalTime = NtfyRules.SQ

    /**
     * One paper trade. [idxStop]: the index stop (real index points); [target]: R131's swing target (the time machine's, never an
     * exit); [peak]: the option's best price of the minutes before [curMinute]; [curHigh]: the best seen in [curMinute] so far;
     * [lockLevel]: the lock's sell level once the best is up ₹1,000 ([lockOrderId]: its SL-M resting in the paper book).
     */
    data class Pos(
        val index: String, val symbol: String, val right: String, val lots: Int, val qty: Int, val entry: Double, val entryTime: LocalDateTime,
        val orderId: String?, val idxStop: Double, val target: Double?, val ref: Double, val r0: Int, val ev: Double,
        val peak: Double? = null, val curMinute: LocalDateTime? = null, val curHigh: Double? = null,
        val lockLevel: Double? = null, val lockOrderId: String? = null,
        val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null, val charges: Double = 0.0,
        /** An exit was decided but the sale did not fill yet: sold again at every look until it does (never re-judged). */
        val exitDue: String? = null,
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        val key: String get() = "cisd:$index:$entryTime"
        val put: Boolean get() = right == "PE"
        /** Rupees after charges, once sold. */
        val net: Double? get() = exit?.let { (it - entry) * qty - charges }
        /** The result in premium points and in % of the premium paid, once sold (the forward record's units). */
        val points: Double? get() = exit?.let { it - entry }
        val pct: Double? get() = exit?.let { if (entry > 0) (it - entry) / entry * 100 else null }
        val entryRow: Int get() = r0
        val exitRow: Int? get() = exitTime?.let { PassedArms.Session.row(it.toLocalTime()) }
    }

    /**
     * A setup that passed R131's filters but was not bought (the network's EV at or under 0, or a guard): the research still counted
     * its trade, so the index stays busy until that trade's exit ([CisdNet.simExit] on the option's minutes, read when the next setup
     * comes).
     */
    data class Phantom(val index: String, val day: LocalDate, val r0: Int, val call: Boolean, val stop: Double, val key: String, val lot: Int)

    /** One setup: [index]'s CISD at entry minute [r0] of [day], taken or not and why, with the network's side EV when it was read. */
    data class Look(val day: LocalDate, val index: String, val r0: Int, val at: LocalDateTime, val call: Boolean, val ev: Double?, val why: String,
                    val taken: Boolean) {
        val key: String get() = "$day|$index|$r0"
        val time: String get() = PassedArms.Session.time(r0).toString()
    }

    private class Book(
        var armed: Boolean = false,
        val positions: MutableList<Pos> = ArrayList(),
        val looks: MutableMap<String, Look> = LinkedHashMap(),
        val phantoms: MutableMap<String, Phantom> = HashMap(),
        val log: ArrayList<String> = ArrayList(),
        val migrated: MutableSet<String> = HashSet(),
        /** "day|index|r0": an entry being placed (written before the order: a restart in between never orders it again). */
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
        .put("entry", p.entry).put("at", p.entryTime.toString()).put("idxStop", p.idxStop).put("ref", p.ref).put("r0", p.r0).put("ev", p.ev)
        .put("charges", p.charges)
        .apply {
            p.target?.let { put("target", it) }; p.peak?.let { put("peak", it) }; p.curMinute?.let { put("curMinute", it.toString()) }
            p.curHigh?.let { put("curHigh", it) }; p.lockLevel?.let { put("lockLevel", it) }; p.lockOrderId?.let { put("lockOrder", it) }
            p.orderId?.let { put("order", it) }; p.exitDue?.let { put("exitDue", it) }
            p.exit?.let { put("exit", it) }; p.exitTime?.let { put("exitAt", it.toString()) }; p.why?.let { put("why", it) }
        }

    private fun dbl(o: JSONObject, k: String): Double? = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
    private fun str(o: JSONObject, k: String): String? = o.optString(k).ifEmpty { null }

    private fun posOf(o: JSONObject) = Pos(o.getString("index"), o.getString("symbol"), o.getString("right"), o.getInt("lots"), o.getInt("qty"),
        o.getDouble("entry"), LocalDateTime.parse(o.getString("at")), str(o, "order"), o.getDouble("idxStop"), dbl(o, "target"), o.optDouble("ref"),
        o.optInt("r0"), o.optDouble("ev", 0.0), dbl(o, "peak"), str(o, "curMinute")?.let { LocalDateTime.parse(it) }, dbl(o, "curHigh"),
        dbl(o, "lockLevel"), str(o, "lockOrder"), dbl(o, "exit"), str(o, "exitAt")?.let { LocalDateTime.parse(it) }, str(o, "why"),
        o.optDouble("charges", 0.0), str(o, "exitDue"))

    private fun lookJson(l: Look) = JSONObject().put("day", l.day.toString()).put("index", l.index).put("r0", l.r0).put("at", l.at.toString())
        .put("call", l.call).put("why", l.why).put("taken", l.taken).apply { l.ev?.takeIf { it.isFinite() }?.let { put("ev", it) } }

    private fun lookOf(o: JSONObject) = Look(LocalDate.parse(o.getString("day")), o.getString("index"), o.getInt("r0"), LocalDateTime.parse(o.getString("at")),
        o.optBoolean("call"), dbl(o, "ev"), o.optString("why"), o.optBoolean("taken"))

    private fun phJson(p: Phantom) = JSONObject().put("index", p.index).put("day", p.day.toString()).put("r0", p.r0).put("call", p.call)
        .put("stop", p.stop).put("key", p.key).put("lot", p.lot)

    private fun phOf(o: JSONObject) = Phantom(o.getString("index"), LocalDate.parse(o.getString("day")), o.getInt("r0"), o.getBoolean("call"),
        o.getDouble("stop"), o.getString("key"), o.getInt("lot"))

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
            o.optJSONArray("phantoms")?.let { a -> for (i in 0 until a.length()) phOf(a.getJSONObject(i)).let { b.phantoms[it.index] = it } }
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
            o.optJSONArray("migrated")?.let { a -> for (i in 0 until a.length()) b.migrated += a.getString(i) }
            o.optJSONArray("ordering")?.let { a -> for (i in 0 until a.length()) b.ordering += a.getString(i) }
        }.onFailure { unreadable = true; if (f.exists() && !Vault.isErasing) runCatching { Vault.setAside(f) } }
        synchronized(written) { if (gen == wipes) cache = b }
        // 29 Oct: built switched OFF. The phone has no Dhan IV, one of the network's inputs (CLAUDE.md task rule: a missing input is
        // written down, not quietly replaced): only Boss switches it on, from its row, knowing that ([CisdNet.IV_GAP]).
        if (BUILT_OFF !in b.migrated) {
            b.migrated += BUILT_OFF
            if (!unreadable) note(b, "built switched off (29 Oct): ${CisdNet.IV_GAP}; switch it on from its row if you want it on paper")
            save(b)
        } else publish(b)
        return b
    }

    private fun save(b: Book): Boolean {
        val closed = b.positions.count { !it.open }
        if (closed > CLOSED_KEPT) { var drop = closed - CLOSED_KEPT; b.positions.removeAll { !it.open && drop-- > 0 } }
        val keepFrom = today().minusDays(10)
        b.looks.entries.removeAll { it.value.day.isBefore(keepFrom) }
        b.phantoms.entries.removeAll { it.value.day.isBefore(today()) }
        b.ordering.removeAll { k -> runCatching { LocalDate.parse(k.substringBefore('|')).isBefore(today()) }.getOrDefault(true) }
        val o = JSONObject().put("armed", b.armed)
            .put("positions", JSONArray(b.positions.map { posJson(it) }))
            .put("looks", JSONArray(b.looks.values.map { lookJson(it) }))
            .put("phantoms", JSONArray(b.phantoms.values.map { phJson(it) }))
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
            b.looks.values.filter { it.day == today }.sortedWith(compareBy({ it.r0 }, { CisdNet.UNDERLYINGS.indexOf(it.index) })), b.log.takeLast(30),
            closed.map { it.net ?: 0.0 }, modelFailed, lastPassMs)
        runCatching { com.optionslab.ira.ArmExits.set(EXITS_KEY, b.positions.filter { it.open }.map { exitsOf(it) }) }
        follow(b)
    }

    private fun exitsOf(p: Pos) = com.optionslab.ira.ArmExits.Entry(EXITS_KEY, CisdNet.SHOWN, false, p.symbol, p.lockLevel, null, SQ,
        "${p.index} index stop ${NtfyParser.num(p.idxStop)} · lock 60% of the gain once up ₹1,000" +
            (p.lockLevel?.let { " (now out at ${NtfyParser.num(it)})" } ?: "") + " · ${CisdNet.NOT_PROVEN}")

    /** The held options and their indices followed on Zerodha's stream at full speed while held (memory only). */
    private fun follow(b: Book) {
        val open = b.positions.filter { it.open }
        val toks = open.flatMap { p -> listOfNotNull(indexToken(p.index), optionToken(p.symbol)) }.toSet()
        runCatching { KiteStream.want(STREAM_OWNER, toks) }
    }

    private fun note(b: Book, text: String) {
        b.log += "${now()} $text"
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        runCatching { Diag.record("cisdnet", "${CisdNet.SHOWN}: $text") }
    }

    private fun now(): LocalDateTime = runCatching { Market.now().toLocalDateTime().withNano(0) }.getOrElse { LocalDateTime.now().withNano(0) }
    private fun today(): LocalDate = runCatching { Market.today() }.getOrElse { LocalDate.now() }

    /** Read the book (start-up's positions-first step and the row call it, off the main thread). */
    suspend fun refresh(): View { if (ready()) lock.withLock { book() }; return _view.value }

    /** An open paper trade is held (memory only). */
    fun holding(): Boolean = _view.value.open.isNotEmpty()

    @Volatile var lastPassMs: Long = 0L
        private set

    // ---- when it decides ------------------------------------------------------------------------------------------------

    private val FIRST_CLOSE = 9 * 60 + 20
    private val LAST_CLOSE = 15 * 60
    private const val DECIDE_FOR = 2

    /** The entry minute (row) of the 5-minute bar whose close is due at [minute] (09:20 .. 15:00, each for two minutes); null: none. */
    internal fun dueR0(minute: Int): Int? {
        if (minute < FIRST_CLOSE || minute >= LAST_CLOSE + DECIDE_FOR) return null
        val close = minute - (minute - FIRST_CLOSE) % 5
        if (close > LAST_CLOSE || minute - close >= DECIDE_FOR) return null
        return close - (9 * 60 + 15)
    }

    /** The watch needs this arm's pass now (memory only): a trade held; or switched on, on a trading day, 08:45 to 15:02. */
    fun wantsPass(minute: Int = runCatching { Market.minuteNow() }.getOrDefault(0)): Boolean {
        if (com.optionslab.app.BuildConfig.GOLD) return false
        if (holding()) return true
        // 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow"): off, it still decides (would-be trades).
        if (minute !in (8 * 60 + 45) until (LAST_CLOSE + DECIDE_FOR)) return false
        return runCatching { Market.isTradingDay() }.getOrDefault(false)
    }

    /** A bar-close decision is due at [minute] (the watch's bar-close wake; memory only). */
    fun decisionDue(minute: Int = runCatching { Market.minuteNow() }.getOrDefault(0)): Boolean = !com.optionslab.app.BuildConfig.GOLD && dueR0(minute) != null

    // ---- Boss's switch --------------------------------------------------------------------------------------------------

    /**
     * Switch it on or off - on paper. Off buys nothing new: what it holds still closes by its own exits. [log]: Boss's flip (logged
     * in Autopilot's timeline); false for the timeline's own Undo.
     */
    suspend fun setArmed(on: Boolean, log: Boolean = true): String {
        if (!ready()) return "${CisdNet.SHOWN} is not ready yet."
        return lock.withLock {
            val b = book()
            if (b.armed == on) return@withLock "${CisdNet.SHOWN} is already ${if (on) "on" else "off"} (paper)."
            b.armed = on
            note(b, if (on) "switched on, on paper (${CisdNet.NOT_PROVEN})" else "switched off" +
                if (b.positions.any { it.open }) "; what it holds still closes by its own exits" else "")
            save(b)
            if (log) runCatching { com.optionslab.app.ira.Autopilot.cisdSwitchedByBoss(on) }
            if (on) "${CisdNet.SHOWN} on, on paper. ${CisdNet.NOT_PROVEN}." else "${CisdNet.SHOWN} off. Nothing new is bought."
        }
    }

    /**
     * The diagnostics' lines: the switch and counts, then each recent trade as its forward record - the arm's key, index, side, entry
     * and exit times, the network's side EV, why it closed and the result in premium points and % of premium. [hideNet] (the uploaded
     * file) hides the rupee total ([PassedArmsHost.NET_HIDDEN]).
     */
    fun diagLine(hideNet: Boolean = false): String {
        val v = _view.value
        val net = if (hideNet) PassedArmsHost.NET_HIDDEN else "%,.0f".format(Locale.ENGLISH, v.nets.sum())
        val head = "${CisdNet.SHOWN}: ${if (v.armed) "on (paper)" else "off"} · open ${v.open.size} · today ${v.today.size} taken, " +
            "${v.looks.count { !it.taken }} setups not · ${v.nets.size} closed ($net after charges)" + (v.model?.let { " · model unreadable: $it" } ?: "") +
            " · ${CisdNet.NOT_PROVEN}"
        val rec = (v.closed.takeLast(10) + v.open).joinToString("") { "\n  " + recordLine(it) }
        return head + rec
    }

    /** One trade of the forward record (no rupees: the EV is the network's score, the result in points and % of premium). */
    internal fun recordLine(p: Pos): String = "${CisdNet.SOURCE} ${p.day} ${p.index} ${p.right} in ${p.entryTime.toLocalTime()} @ ${NtfyParser.num(p.entry)}" +
        " · network EV ${"%+.0f".format(Locale.ENGLISH, p.ev)} · index stop ${NtfyParser.num(p.idxStop)}" +
        (if (p.open) " · open" else " · out ${p.exitTime?.toLocalTime()} (${p.why}) @ ${NtfyParser.num(p.exit ?: 0.0)} · " +
            "${"%+.2f".format(Locale.ENGLISH, p.points ?: 0.0)} pts (${"%+.1f".format(Locale.ENGLISH, p.pct ?: 0.0)}% of premium)")

    /** Jarvis's answer: what it did today and why, what is held, the record. No rupees on a locked phone. */
    suspend fun talk(locked: Boolean = false): String {
        val v = refresh()
        val state = if (v.armed) "on, on paper" else "off"
        val today = if (v.looks.isEmpty()) "no CISD setup yet today" else v.looks.joinToString("; ") { l ->
            "${l.index} ${l.time} ${if (l.taken) "taken" else if (locked) "not taken" else l.why}" }
        val held = if (v.open.isEmpty()) "nothing held" else "holding " + v.open.joinToString { it.symbol }
        val record = if (locked) "" else " Its record: ${v.nets.size} closed, ${v.wins} won, %s after charges.".format(Locale.ENGLISH, rs(v.nets.sum()))
        return "${CisdNet.SHOWN} is $state - ${CisdNet.NOT_PROVEN}. Today: $today. $held.$record"
    }

    private fun rs(x: Double) = (if (x < 0) "−₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

    // ---- the model (S5 Trail's assets: R123's N1, read once, off the main thread) -------------------------------------------

    @Volatile private var model: S5Trail.Model? = null
    @Volatile private var modelFailed: String? = null
    private fun ensureModel(): S5Trail.Model? {
        model?.let { return it }
        if (modelFailed != null || !S5Arm.assetsReady) return null
        return runCatching { S5Arm.loadModel() }
            .onSuccess { model = it }
            .onFailure { modelFailed = it.javaClass.simpleName; runCatching { Diag.record("cisdnet", "the network could not be read: ${it.javaClass.simpleName}") } }
            .getOrNull()
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

    /** "day|index|r0" decided with no setup or for good this run (memory: a restart looks again, and the book dedupes setups). */
    private val decided = java.util.Collections.synchronizedSet(HashSet<String>())

    /** The watch's pass (and its bar-close wake): the open trades' exits; at a bar close, each index's CISD look. */
    suspend fun tick() {
        if (com.optionslab.app.BuildConfig.GOLD || !ready()) return
        passLock.withLock {
            val now = now()
            val minute = now.hour * 60 + now.minute
            val day = now.toLocalDate()
            val trading = runCatching { Market.isTradingDay(day) }.getOrDefault(false)
            // 0. The held indices' minutes since the open (network reads, outside the lock): the index stop is also judged on them, so a
            //    stream gap or a restart never hides a crossing (the stop lane between passes reads the stream's ticks).
            val minutes = _view.value.open.map { it.index }.toSet().associateWith { u -> bars(u, now) }
            // 1. Exits first, under the lock; and what is due, with the busy state the looks need.
            val due = lock.withLock {
                val b = book()
                val changed = runCatching { exits(b, now, minutes) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true } || peakUnsaved
                peakUnsaved = false
                val r0 = dueR0(minute)
                // 30 Oct: on or off (off: each kept setup a would-be trade in the switched-off shadow, nothing bought).
                val list = if (!trading || r0 == null) emptyList() else CisdNet.UNDERLYINGS
                    .filter { "$day|$it|$r0" !in b.looks && "$day|$it|$r0" !in decided }
                    .map { u -> Due(u, r0, b.positions.filter { it.index == u && it.day == day }, b.phantoms[u]?.takeIf { it.day == day }) }
                lastPassMs = System.currentTimeMillis()
                if (changed) save(b) else publish(b)
                list
            }
            if (trading) runCatching { PassedArmsHost.prefetch(now, CisdNet.UNDERLYINGS) }
            if (due.isEmpty()) return@withLock
            // 2. The reads, the setups and (only for a setup) the network, outside the lock (network reads; never the tick or order path).
            val verdicts = runCatching { decide(due, now) }.getOrElse { e -> due.map { d -> Verdict(d.index, d.r0, null, "the look failed: ${e.javaClass.simpleName}", final = false) } }
            // 3. A pick waits for the account guard's snapshot: read now, outside the lock.
            if (verdicts.any { it.keep }) snapNow = runCatching { Paper.snapshot() }.getOrNull()?.let { System.currentTimeMillis() to it }
            // 4. The book: each setup recorded, each kept one taken (every guard), the refused ones kept as busy, under the lock.
            lock.withLock {
                val b = book()
                for (v in verdicts) {
                    val key = "$day|${v.index}|${v.r0}"
                    if (key in b.looks) continue
                    v.phantomDone?.let { if (b.phantoms[v.index]?.r0 == it) b.phantoms.remove(v.index) }
                    // A read that failed early in the bar's window is tried again at the next pass; its last minute decides.
                    if (!v.final && minute < v.r0 + 9 * 60 + 15 + DECIDE_FOR - 1) continue
                    decided += key
                    val c = v.cand
                    if (c == null) {                                                     // no setup at this bar: nothing to log
                        if (!v.final) note(b, "${v.index} ${PassedArms.Session.time(v.r0)}: could not look - ${v.why}")
                        continue
                    }
                    val look = if (v.keep) runCatching { take(b, v, now) }.getOrElse { Look(day, v.index, v.r0, now, c.call, v.evSide, "not taken: ${it.javaClass.simpleName}", false) }
                        else Look(day, v.index, v.r0, now, c.call, v.evSide, v.why, false)
                    b.looks[key] = look
                    if (v.passed && !look.taken && v.contract != null) b.phantoms[v.index] = Phantom(v.index, day, v.r0, c.call, c.stop, v.contract.instrumentKey, v.contract.lotSize)
                    // A setup its window closed on without a judgement (no options list, a failed read): the research may have traded
                    // it, so the index stays busy for the rest of the day (a skip only lowers risk).
                    else if (!v.final && !look.taken) b.phantoms[v.index] = Phantom(v.index, day, v.r0, c.call, c.stop, "", 0)
                    if (!look.taken) note(b, "${v.index} ${look.time} ${if (c.call) "bullish" else "bearish"} CISD: not taken - ${look.why}")
                }
                snapNow = null
                save(b)
            }
        }
    }

    /** One index's look at one bar close: its trades today and the setup the filter refused earlier today (busy state). */
    internal class Due(val index: String, val r0: Int, val trades: List<Pos>, val phantom: Phantom?)

    /**
     * One index's look: [cand] the setup at [r0] (null: none); [final]: nothing a later read in the window could change; [passed]:
     * it passed R131's filters (the research would have traded it, so the index is busy until its exit); [keep]: the network's
     * verdict; [phantomDone]: an earlier refused setup's trade is over (its r0).
     */
    internal class Verdict(val index: String, val r0: Int, val cand: CisdNet.Cand?, val why: String, val final: Boolean = true,
                           val passed: Boolean = false, val keep: Boolean = false, val evSide: Double? = null, val ref: Double = Double.NaN,
                           val close: Double = Double.NaN, val expiry: LocalDate? = null, val contract: Upstox.Contract? = null,
                           val target: Double? = null, val phantomDone: Int? = null)

    /** TEST SEAMS: the index minutes, the earlier sessions, a contract's minutes, the listed contracts, the index price (JVM tests only). */
    @Volatile internal var testBars: ((String) -> List<Bar>?)? = null
    @Volatile internal var testHist: ((String) -> PassedArmsHost.Hist?)? = null
    @Volatile internal var testChain: ((Upstox.Contract) -> List<Upstox.Bar>)? = null
    @Volatile internal var testListed: ((String) -> List<Upstox.Contract>)? = null
    /** TEST SEAM: the network's EV per cell-side for (index, view row), in place of its own (JVM tests only; the inputs are still built). */
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

    private suspend fun contractMinutes(c: Upstox.Contract): List<Upstox.Bar>? =
        runCatching { testChain?.invoke(c) ?: Net.intradayQuick(c.instrumentKey) }.getOrNull()

    private suspend fun decide(due: List<Due>, now: LocalDateTime): List<Verdict> {
        val day = now.toLocalDate()
        val all = CisdNet.UNDERLYINGS + PassedArmsHost.VIX
        val today = all.associateWith { u -> bars(u, now)?.let { PassedArms.DayBars.of(day, it) } }
        val hists = all.associateWith { hist(it, day) }
        val gate = Semaphore(S5Arm.CHAIN_READS)
        return coroutineScope {
            due.map { d -> async { runCatching { decideOne(d, day, today, hists, gate) }
                .getOrElse { Verdict(d.index, d.r0, null, "the look failed: ${it.javaClass.simpleName}", final = false) } } }.awaitAll()
        }
    }

    private suspend fun decideOne(d: Due, day: LocalDate, today: Map<String, PassedArms.DayBars?>, hists: Map<String, PassedArmsHost.Hist?>,
                                  gate: Semaphore): Verdict {
        val u = d.index
        val r0 = d.r0
        val end = r0 - 1
        val todayBars = today[u]
        if (todayBars == null || todayBars.c[end].isNaN()) return Verdict(u, r0, null, "no $u minute bar for ${PassedArms.Session.time(end)} yet", final = false)
        val h = hists[u]
        val prior = h?.sessions.orEmpty().filter { it.date.isBefore(day) }
        if (prior.isEmpty() || h == null) return Verdict(u, r0, null, "waiting for $u's earlier sessions (read from 08:45)", final = false)
        val sides = CisdNet.Sides(prior.takeLast(CisdNet.SESSIONS_READ) + todayBars.through(end))
        val all = CisdNet.candidates(sides)
        val c = all.firstOrNull { it.d == sides.sessions.size - 1 && it.r0 == r0 } ?: return Verdict(u, r0, null, "no setup")
        // One trade at a time per index (R131's sequence, applied before the filter as R133 did): busy is a skip, never a trade.
        val busy = busyWhy(d, todayBars, gate)
        busy?.first?.let { return Verdict(u, r0, c, it, phantomDone = busy.second) }
        return decideSetup(d, c, sides, all, todayBars, day, today, hists, gate, busy?.second)
    }

    /**
     * Why the index is busy at [Due.r0] (null: free) and an earlier refused setup's trade now known to be over (its r0), or null for
     * neither. A refused setup's option is read only now (its minutes since its entry); unreadable: busy (a skip only lowers risk).
     */
    private suspend fun busyWhy(d: Due, todayBars: PassedArms.DayBars, gate: Semaphore): Pair<String?, Int?>? {
        if (d.trades.any { it.open }) return "a ${d.index} trade is still open" to null
        d.trades.mapNotNull { it.exitRow }.maxOrNull()?.let { if (d.r0 <= it) return "its last ${d.index} trade closed at this minute" to null }
        val ph = d.phantom ?: return null
        if (ph.key.isEmpty()) return "a setup at ${PassedArms.Session.time(ph.r0)} could not be judged, so ${d.index} stays busy today" to null
        val c = Upstox.Contract(d.index, today(), 0.0, if (ph.call) Right.CE else Right.PE, ph.lot, ph.key, ph.key)
        val bars = gate.withPermit { contractMinutes(c) } ?: return "a setup refused at ${PassedArms.Session.time(ph.r0)} may still be running " +
            "(its option could not be read)" to null
        val opt = Array(PassedArms.Session.N) { DoubleArray(4) { Double.NaN } }
        for (b in bars) {
            if (b.istDate != ph.day) continue
            val r = b.istMinute - (9 * 60 + 15)
            if (r in 0 until PassedArms.Session.N && b.close > 0) opt[r] = doubleArrayOf(b.open, b.high, b.low, b.close)
        }
        val last = minOf(d.r0 - 1, CisdNet.EXIT_LAST)
        val ex = CisdNet.simExit(opt, todayBars.h, todayBars.l, ph.call, ph.r0, opt[ph.r0][0], ph.lot, ph.stop, last)
            ?: return null to ph.r0                                                   // no price at its entry: the research had no trade
        if (ex.why == "time" && last < CisdNet.EXIT_LAST) return "the setup refused at ${PassedArms.Session.time(ph.r0)} is still running in the research's count" to null
        return (if (d.r0 <= ex.row) "the setup refused at ${PassedArms.Session.time(ph.r0)} closed at this minute in the research's count" else null) to ph.r0
    }

    private suspend fun decideSetup(d: Due, c: CisdNet.Cand, sides: CisdNet.Sides, all: List<CisdNet.Cand>, todayBars: PassedArms.DayBars,
                                    day: LocalDate, today: Map<String, PassedArms.DayBars?>, hists: Map<String, PassedArmsHost.Hist?>,
                                    gate: Semaphore, done: Int?): Verdict {
        val u = d.index
        val r0 = d.r0
        val end = r0 - 1
        fun v(why: String, final: Boolean = true, passed: Boolean = false) = Verdict(u, r0, c, why, final = final, passed = passed, phantomDone = done)
        val list = listed(u)
        if (list.isEmpty()) return v("waiting for today's $u options list", final = false)
        val exp = CisdNet.expiryFor(day, list.map { it.expiry }) ?: return v("the nearest and the monthly expiry are both today (R131: no trade)")
        val ref = indexPrice(u) ?: todayBars.closeAtOrBefore(end)
        CisdNet.check(sides, c, all, ref)?.let { return v(it) }
        val close = todayBars.closeAtOrBefore(end)
        val strike = CisdNet.strike(u, close).toDouble()
        val right = if (c.call) Right.CE else Right.PE
        val ct = list.firstOrNull { it.expiry == exp && it.strike == strike && it.right == right }
            ?: return v("$u ${NtfyParser.num(strike)} ${right.name} of $exp is not in the phone's options list")
        val target = CisdNet.structTarget(sides, c.side, c.d, c.r0, c.side * ref)?.let { it * c.side }
        // The network: its latest view (<= 5 minutes old) at the index's row t.
        val t = CisdNet.viewRow(r0) ?: return v("no network view for this minute", passed = true).with(ct, exp, ref, close, target)
        if (todayBars.c[t].isNaN()) return v("no $u bar at the network's view (${PassedArms.Session.time(t + 1)})", passed = true).with(ct, exp, ref, close, target)
        val m = ensureModel() ?: return v("the network could not be read (${modelFailed ?: "not ready"})", passed = true).with(ct, exp, ref, close, target)
        val ev = testEv?.invoke(u, t) ?: m.ev(u, network(m, u, t, day, todayBars, hists, today, list, gate))
        val es = CisdNet.evSide(ev, c.call)
        val keep = CisdNet.keep(ev, c.call)
        val why = if (keep) "the network expects this side to pay (EV ${"%+.0f".format(Locale.ENGLISH, es)})"
            else "refused by the network: its EV for this side is ${if (es.isFinite()) "%+.0f".format(Locale.ENGLISH, es) else "unknown"}, not above 0"
        return Verdict(u, r0, c, why, final = true, passed = true, keep = keep, evSide = es, ref = ref, close = close, expiry = exp, contract = ct,
            target = target, phantomDone = done)
    }

    private fun Verdict.with(ct: Upstox.Contract, exp: LocalDate, ref: Double, close: Double, target: Double?) =
        Verdict(index, r0, cand, why, final, passed, keep, evSide, ref, close, exp, ct, target, phantomDone)

    /** R123's network at view row [t] on the app's own inputs (S5 Trail's inputs, chain and IV). Network reads (the chain). */
    private suspend fun network(m: S5Trail.Model, u: String, t: Int, day: LocalDate, todayBars: PassedArms.DayBars, hists: Map<String, PassedArmsHost.Hist?>,
                                today: Map<String, PassedArms.DayBars?>, list: List<Upstox.Contract>, gate: Semaphore): S5Trail.Output {
        val h = hists.getValue(u)!!
        val prior = h.sessions.filter { it.date.isBefore(day) }
        val byDay = CisdNet.UNDERLYINGS.associateWith { x -> (hists[x]?.sessions.orEmpty() + listOfNotNull(today[x])).associateBy { it.date } }
        val vix = (hists[PassedArmsHost.VIX]?.sessions.orEmpty() + listOfNotNull(today[PassedArmsHost.VIX])).associateBy { it.date }
        val market = S5Trail.Market(u, prior.takeLast(3) + todayBars.through(t), byDay, vix, S5Arm.pastOf(prior, h.daily, day))
        val nearest = NtfyRules.expiryFor(list.map { it.expiry }, day, NtfyParser.Expiry.Nearest)
        val trading = (1..45L).map { day.plusDays(it) }.filter { runCatching { Market.isTradingDay(it) }.getOrDefault(it.dayOfWeek.value < 6) }
        val cal = S5Trail.Calendar(S5Trail.sessionsToExpiry(day, nearest, trading), nearest == day, day in com.optionslab.ira.EventStudy.BUDGET)
        val spot = DoubleArray(t + 1).also { a -> var last = Double.NaN; for (r in 0..t) { if (todayBars.c[r].isFinite()) last = todayBars.c[r]; a[r] = last } }
        val seen = spot.filter { it.isFinite() }
        val chain = if (nearest == null || seen.isEmpty()) null else {
            val bars = S5Arm.chainBars(u, list.filter { it.expiry == nearest }, seen.min(), seen.max(), gate) { c -> contractMinutes(c) }
            S5Trail.Chain.of(u, t, spot, bars) { price, s, strike, row, call ->
                val atmK = S5Trail.atm(s, S5Trail.step(u))
                val off = (strike - atmK) / S5Trail.step(u)
                val iv = OptionMath.impliedVol(price, if (call) OptionType.CE else OptionType.PE, s, strike.toDouble(), S5Trail.yearsToExpiry(day, row, nearest))
                if (iv == null || !iv.isFinite()) Double.NaN else iv * 100 + (S5Arm.IV_SHIFT[call to off] ?: 0.0)
            }
        }
        return m.predict(market.window(t), S5Trail.inputs(market, t, cal, chain))
    }

    // ---- prices from the stream (memory only) ------------------------------------------------------------------------------

    private fun indexToken(u: String): Long? = runCatching { Broker.indexToken(u) }.getOrNull()

    /** Each held option's Kite token, found once (memory): the stop lane never re-reads the instruments list. */
    private val tokens = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun optionToken(symbol: String): Long? = tokens[symbol] ?: runCatching {
        val c = Paper.contractOf(symbol) ?: return@runCatching null
        val list = Broker.cachedInstruments() ?: return@runCatching null
        Broker.find(list, c.underlying, c.expiry, c.strike, c.right)?.token?.takeIf { it > 0 }
    }.getOrNull()?.also { tokens[symbol] = it }

    /** [u]'s price on Zerodha's stream (at most 5 s old), or null. */
    private fun indexPrice(u: String): Double? = runCatching { indexToken(u)?.let { KiteStream.tick(it)?.last }?.takeIf { it > 0 } }.getOrNull()

    /** [c]'s price from Zerodha's stream (at most 5 s old; the option followed from now on), or null. Memory only. */
    private fun optionQuote(c: Paper.Contract, anyAge: Boolean = false): com.optionslab.engine.sandbox.Quote? = runCatching {
        val list = Broker.cachedInstruments() ?: return@runCatching null
        val tok = Broker.find(list, c.underlying, c.expiry, c.strike, c.right)?.token?.takeIf { it > 0 } ?: return@runCatching null
        KiteStream.touch(listOf(tok))
        val t = (if (anyAge) KiteStream.seen(tok)?.tick else KiteStream.tick(tok)) ?: return@runCatching null
        if (t.last <= 0) null else com.optionslab.engine.sandbox.Quote(t.last, bid = t.bid ?: 0.0, ask = t.ask ?: 0.0)
    }.getOrNull()

    // ---- the entry -------------------------------------------------------------------------------------------------------

    private suspend fun stoppedToday(): Boolean =
        runCatching { Strategies.stoppedToday() }.getOrDefault(true) || runCatching { LossBreaker.trippedToday() }.getOrDefault(false)

    private fun chargesOf(orderId: String?): Double =
        if (orderId == null) 0.0 else Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    /** This run's own orders being placed (memory): one in [Book.ordering] but not here was interrupted by a restart. */
    private val placing: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** A kept setup: every guard, then the paper order. Returns the setup's record. Under the lock. */
    private suspend fun take(b: Book, v: Verdict, decidedAt: LocalDateTime): Look {
        val c = v.cand!!
        // The clock read again: the reads before this (the network, the chain) take time, and the guards judge the order's own moment.
        val now = now().takeIf { !it.isBefore(decidedAt) } ?: decidedAt
        val day = now.toLocalDate()
        val key = "$day|${v.index}|${v.r0}"
        fun no(why: String) = Look(day, v.index, v.r0, now, c.call, v.evSide, "${v.why}; not taken: $why", false)
        if (key in b.ordering && key !in placing) { b.ordering.remove(key); return no("interrupted while its order was placed - not ordered again (check Paper for a fill)") }
        val time = now.toLocalTime()
        val refusal = when {
            !runCatching { Market.isTradingDay(day) }.getOrDefault(false) -> "not a trading day"
            runCatching { AppSettings.load().guardKill }.getOrDefault(true) -> "the kill switch is on"
            stoppedToday() -> "the bot is stopped for today"
            else -> runCatching { DayLockGuard.refusal(false) }.getOrNull()
                ?: if (b.positions.any { it.open && it.index == v.index }) "a ${v.index} trade is still open"
                // 30 Oct (review): switched off, one open would-be trade per index too (the switched-off shadow's, memory only).
                else if (!b.armed && OffShadow.view.value.openOf(CisdNet.SOURCE).any { it.underlying == v.index }) "a ${v.index} would-be trade is still open"
                else if (PassedArms.Session.row(time) > v.r0 + DECIDE_FOR - 1) "its entry minute (${PassedArms.Session.time(v.r0)}) has passed"
                else if (!time.isBefore(SQ)) "too late in the day"
                else null
        }
        if (refusal != null) return no(refusal)
        val exp = v.expiry ?: return no("no expiry")
        val ct = v.contract ?: return no("no contract")
        val right = if (c.call) Right.CE else Right.PE
        val pc = Paper.ticketContract(v.index, exp, ct.strike, right) ?: return no("${v.index} ${NtfyParser.num(ct.strike)} ${right.name} is not in the phone's options list")
        if (pc.lotSize <= 0) return no("lot size unknown")
        // 30 Oct (Boss: "If i switch any arm off, it should be always run in shadow and record only"): switched off, the setup is a
        // would-be trade in the switched-off shadow (the index stop, the 60% lock after +₹1,000, the 15:15 square-off) - nothing is
        // bought, nothing goes to the paper book or live.
        if (!b.armed) {
            val said = OffShadow.enter(OffShadow.Ask(CisdNet.SOURCE, CisdNet.SHOWN, "${v.index}|${v.r0}", pc, if (c.call) 1 else -1,
                com.optionslab.engine.orb.OffShadow.Exits(indexStop = c.stop, lockAfterRs = CisdNet.LOCK_AFTER, lockKeep = CisdNet.LOCK_KEEP, exitAt = SQ),
                "${if (c.call) "bullish" else "bearish"} CISD at ${PassedArms.Session.time(v.r0)}, network EV ${"%+.0f".format(Locale.ENGLISH, v.evSide ?: Double.NaN)}"))
            return no("switched off - $said")
        }
        if (b.positions.any { it.open && it.symbol == pc.symbol } || Paper.state.positions.any { it.symbol == pc.symbol && it.quantity != 0 })
            return no("${pc.symbol} is already held - never two positions on one contract")
        val q = optionQuote(pc) ?: return no("no fresh price for ${pc.symbol}")
        val snap = snapNow?.takeIf { System.currentTimeMillis() - it.first in 0..SNAP_FRESH_MS }?.second ?: return no("the account guard's check could not be read")
        val refusals = Guard.check(Guard.paperOrder(pc, "BUY", 1, q.ltp), Guard.paperAccount(snap), paper = true)
        if (refusals.isNotEmpty()) return no("refused by the account guard: " + refusals.joinToString(" "))
        val verdict = OrderVerdicts.stamp(OrderVerdicts.paperFields(pc, "BUY", 1, "MARKET", "MIS", null, null, day, CisdNet.OWNER), refusals, "paper guard")
        b.ordering += key; placing += key
        if (!save(b)) { b.ordering.remove(key); placing.remove(key); return no("the book could not be saved before the order") }
        val res = try {
            Paper.place(pc, "BUY", 1, "MARKET", "MIS", null, null, known = q, auto = CisdNet.OWNER, mirror = CisdNet.SOURCE, verdict = verdict)
        } finally { b.ordering.remove(key); placing.remove(key) }
        val fill = res.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
            ?: return no("the paper order did not fill (${res.message})").also { res.orderId?.takeIf { res.ok }?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } } }
        val pos = entered(b, v, pc, res.orderId, fill.quantity, fill.price, now)
        return Look(day, v.index, v.r0, now, c.call, v.evSide, "taken: ${pos.symbol} ${pos.qty} @ ${NtfyParser.num(pos.entry)} · paper", true)
    }

    /** A filled entry booked: its ticket labelled, the time machine's shadow check, the diary's line. */
    private suspend fun entered(b: Book, v: Verdict, pc: Paper.Contract, orderId: String?, qty: Int, price: Double, now: LocalDateTime): Pos {
        val c = v.cand!!
        orderId?.let { id -> runCatching { Strategies.tagOwner("paper:$id", "${CisdNet.OWNER} · ${CisdNet.SHOWN} · entry") } }
        val lots = (qty / pc.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
        val pos = Pos(v.index, pc.symbol, pc.right.name, lots, qty, price, now, orderId, c.stop, v.target, v.ref, v.r0, v.evSide ?: Double.NaN,
            charges = chargesOf(orderId))
        b.positions += pos
        b.phantoms.remove(v.index)
        // 30 Oct: offered to its live path (memory only; nothing goes unless every switch and guard allows it). No premium stop yet:
        // the live trade rests the labelled safety stop until the lock's level comes ([restLock]).
        runCatching { ArmLive.entered(ArmLive.Ask(CisdNet.SOURCE, pos.key, pc, 1, qty, price)) }
        // The time machine's reality check (shadow only; background): the index stop and R131's swing target as points, out by 15:15.
        runCatching { TimeMachineHost.afterEntry(TimeMachineHost.Entry(CisdNet.SOURCE, CisdNet.SHOWN, pos.key, v.index, c.side, now,
            kotlin.math.abs(v.ref - c.stop).takeIf { it.isFinite() && it > 0 }, v.target?.let { kotlin.math.abs(it - v.ref) }?.takeIf { it.isFinite() && it > 0 },
            exitAt = SQ)) }
        note(b, "${v.index} ${PassedArms.Session.time(v.r0)}: ${if (c.call) "bullish" else "bearish"} CISD - bought ${pc.symbol} $qty @ ${NtfyParser.num(price)} · paper · " +
            "network EV ${"%+.0f".format(Locale.ENGLISH, v.evSide ?: Double.NaN)} · index stop ${NtfyParser.num(c.stop)} · lock 60% of the gain once up ₹1,000 · " +
            CisdNet.NOT_PROVEN)
        return pos
    }

    // ---- the exits -------------------------------------------------------------------------------------------------------

    /** Each open trade's last look (real clock ms, the stream's own clock): the next look reads every tick since. Memory. */
    private val lastLook = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** A new best price was marked but not written yet (memory): the watch's pass saves it. */
    @Volatile private var peakUnsaved = false

    /** Each open trade's exits, when due. Memory, the stream and the paper book only. True when [b] must be written. */
    private suspend fun exits(b: Book, now: LocalDateTime, minutes: Map<String, List<Bar>?> = emptyMap()): Boolean {
        var changed = false
        for (i in b.positions.indices) {
            val p = b.positions[i]
            if (!p.open) continue
            val next = runCatching { exitOne(p, now, minutes[p.index]) }.getOrNull() ?: continue
            if (next != p) {
                b.positions[i] = next
                // A new best price alone is kept in memory and written by the watch's pass ([peakUnsaved]), never by the minute or stop lanes.
                if (next.copy(peak = p.peak, curMinute = p.curMinute, curHigh = p.curHigh) != p) changed = true else peakUnsaved = true
                if (!next.open) note(b, "sold ${p.symbol} (${next.why}): ${"%+.2f".format(Locale.ENGLISH, next.points ?: 0.0)} pts, " +
                    "%s after charges · paper".format(Locale.ENGLISH, rs(next.net ?: 0.0)))
            }
        }
        return changed
    }

    /** Positions whose index stop could not be judged at a look (no index price at all): said once each in the diary. Memory. */
    private val unjudged: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    private suspend fun exitOne(start: Pos, now: LocalDateTime, idxMinutes: List<Bar>? = null): Pos {
        var p = start
        val c = Paper.contractOf(p.symbol) ?: return p
        // From one second before the last look: the stream keeps ticks in 1-second buckets and reads those AFTER the bucket holding
        // [since], so the rest of the last look's own second is read again here (judging it twice is harmless; missing it is not).
        val ms = System.currentTimeMillis()
        val since = (lastLook[p.key] ?: (ms - 15_000L)) - 1_000L
        lastLook[p.key] = ms
        // Its resting lock filled in the paper book: that is the exit.
        p.lockOrderId?.let { id ->
            val o = Paper.state.orders.firstOrNull { it.orderId == id }
            if (o != null && o.status == "complete") return closed(p, o.averagePrice?.toDouble() ?: (p.lockLevel ?: p.entry), o.updateTimestamp, "profit lock", chargesOf(id))
        }
        // Gone from the paper book without this arm selling it (the paper square-off, the loss limit, or Boss).
        if (Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity } <= 0) {
            p.lockOrderId?.let { runCatching { Paper.cancel(it, "position_closed") } }
            val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
            return closed(p, t?.price?.toDouble() ?: p.entry, t?.timestamp ?: now, "closed_elsewhere", t?.charges?.toDouble() ?: 0.0)
        }
        if (!now.isBefore(p.day.atTime(SQ)) || p.day.isBefore(now.toLocalDate())) return sellRest(p, c, "square-off $SQ")
        p.exitDue?.let { return sellRest(p, c, it) }
        // The index stop: every index tick since the last look (the stream's path), else its latest price.
        val itok = indexToken(p.index)
        val ilast = itok?.let { KiteStream.tick(it, 60_000)?.last }?.takeIf { it > 0 }
        // The pass's minutes since the entry minute (R132: the index's wicks from the entry on).
        val mins = idxMinutes.orEmpty().filter { it.start.toLocalDate() == p.day && PassedArms.Session.row(it.start.toLocalTime()) >= p.r0 }
        val ilo = (listOfNotNull(itok?.let { KiteStream.lowSince(it, since) }, ilast) + mins.map { it.low }.filter { it.isFinite() && it > 0 }).minOrNull() ?: Double.NaN
        val ihi = (listOfNotNull(itok?.let { KiteStream.highSince(it, since) }, ilast) + mins.map { it.high }.filter { it.isFinite() && it > 0 }).maxOrNull() ?: Double.NaN
        if (ilo.isNaN() && unjudged.add(p.key)) runCatching { Diag.record("cisdnet", "${CisdNet.SHOWN}: ${p.index}'s index stop could not be judged (no index price); the next pass reads its minutes") }
        if (CisdNet.indexStopHit(!p.put, p.idxStop, ilo, ihi)) return sellRest(p.copy(exitDue = "index stop ${NtfyParser.num(p.idxStop)}"), c, "index stop ${NtfyParser.num(p.idxStop)}")
        // The lock (R132 E3): each minute's prices are judged against the level from the best price of EARLIER minutes. The ticks
        // since the last look are judged by the level before this look's fold: right for the minute that just ended, and lower
        // (later, never earlier) for any tick of the new minute; the new minute's own high counts from the next minute on.
        val otok = optionToken(p.symbol)
        // A price at most 5 s old only: a stale last price must never be judged against a level that already holds its minute's high.
        val ltp = runCatching { Paper.bookNow(c)?.takeIf { it.ageMs in 0..5_000 }?.last }.getOrNull()?.takeIf { it > 0 } ?: optionQuote(c)?.ltp
        val ohi = listOfNotNull(otok?.let { KiteStream.highSince(it, since) }, ltp).maxOrNull()
        val olo = listOfNotNull(otok?.let { KiteStream.lowSince(it, since) }, ltp).minOrNull()
        val judge = CisdNet.lockLevel(p.entry, p.qty, p.peak ?: Double.NEGATIVE_INFINITY)
        if (judge != null && olo != null && olo <= judge)
            return sellRest(p.copy(lockLevel = judge, exitDue = "profit lock at ${NtfyParser.num(judge)}"), c, "profit lock at ${NtfyParser.num(judge)}")
        val minute = now.withSecond(0).withNano(0)
        if (p.curMinute != minute) p = p.copy(peak = listOfNotNull(p.peak, p.curHigh).maxOrNull(), curMinute = minute, curHigh = null)
        if (ohi != null) p = p.copy(curHigh = listOfNotNull(p.curHigh, ohi).maxOrNull())
        val level = CisdNet.lockLevel(p.entry, p.qty, p.peak ?: Double.NEGATIVE_INFINITY)
        if (level != null && (p.lockLevel == null || level > p.lockLevel!! || !resting(p.lockOrderId))) p = restLock(p.copy(lockLevel = level), c)
        return p
    }

    /**
     * The lock's SELL SL-M in the paper book at [Pos.lockLevel] on the tick below it (so the book never sells before the research's
     * level; the arm's own look checks the exact level): raised in place, or put (back) when none rests.
     */
    private suspend fun restLock(p: Pos, c: Paper.Contract): Pos {
        val lv = p.lockLevel?.let { lockTrigger(it) } ?: return p
        // 30 Oct: the live stop follows the lock's level on the live fill (memory only; tighter only, at the live watch's next look).
        runCatching { ArmLive.level(p.key, lv) }
        val id = p.lockOrderId
        if (id != null && resting(id)) {
            val r = runCatching { Paper.modify(id, null, null, lv) }.getOrNull()
            if (r?.ok == true) return p
        }
        if (closingWorks(p.symbol)) return p
        val again = runCatching { Paper.place(c, "SELL", p.lots, "SL-M", "MIS", null, lv) }.getOrNull()?.takeIf { it.ok }?.orderId
        again?.let { runCatching { Strategies.tagOwner("paper:$it", "${CisdNet.OWNER} · profit lock") } }
        return p.copy(lockOrderId = again ?: id)
    }

    /** [level] down to the 0.05 tick. */
    internal fun lockTrigger(level: Double): Double = Math.round(kotlin.math.floor(level / 0.05 + 1e-9) * 5.0) / 100.0

    private fun closingWorks(symbol: String): Boolean =
        Paper.state.orders.any { it.symbol == symbol && it.action == "SELL" && (it.status == "open" || it.status == "trigger pending") }

    private fun resting(id: String?): Boolean {
        val o = id?.let { x -> Paper.state.orders.firstOrNull { it.orderId == x } } ?: return false
        return o.status != "cancelled" && o.status != "rejected" && o.status != "complete"
    }

    /** Sell [p] at market (its resting lock taken out first; if it filled meanwhile, that is the exit). Reduce-only. */
    private suspend fun sellRest(p: Pos, c: Paper.Contract, why: String): Pos {
        p.lockOrderId?.let { id ->
            runCatching { Paper.cancel(id, "exit:$why") }
            val o = Paper.state.orders.firstOrNull { it.orderId == id }
            if (o != null && o.status == "complete") return closed(p, o.averagePrice?.toDouble() ?: (p.lockLevel ?: p.entry), o.updateTimestamp, "profit lock", chargesOf(id))
        }
        val r = Paper.place(c, "SELL", p.lots, "MARKET", "MIS", null, null, known = optionQuote(c) ?: optionQuote(c, anyAge = true), exit = true)
        val f = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull() ?: run {
            if (!r.ok && Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity } <= 0) {
                val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
                return closed(p.copy(lockOrderId = null), t?.price?.toDouble() ?: p.entry, t?.timestamp ?: now(), "closed_elsewhere", t?.charges?.toDouble() ?: 0.0)
            }
            // Not filled (no fresh price): the lock goes back into the paper book at once, and the exit is tried again next look.
            r.orderId?.takeIf { r.ok }?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
            return restLock(p.copy(lockOrderId = null, exitDue = p.exitDue ?: why), c)
        }
        r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${CisdNet.OWNER} · exit") } }
        return closed(p.copy(lockOrderId = null), f.price, now(), why, chargesOf(r.orderId))
    }

    private fun closed(p: Pos, price: Double, at: LocalDateTime, why: String, charges: Double): Pos {
        lastLook.remove(p.key)
        // 30 Oct: its live trade (if any) is sold on the live watch's next look (memory only).
        runCatching { ArmLive.closed(p.key, why, ArmLive.pct(p.entry, price)) }
        return p.copy(exit = price, exitTime = at, why = why, charges = p.charges + charges, lockOrderId = null, exitDue = null)
    }

    // ---- tests and the erase ---------------------------------------------------------------------------------------------

    /** For tests: the book forgotten (its file stays: a test reads it again, as after a restart). */
    internal fun wipe() {
        synchronized(written) {
            wipes++
            cache = null; _view.value = View(); testBars = null; testHist = null; testChain = null; testListed = null; testEv = null
            lastPassMs = 0L; placing.clear(); snapNow = null; lastLook.clear(); decided.clear(); peakUnsaved = false; tokens.clear(); unjudged.clear()
            model = null; modelFailed = null
            written.forget()
        }
        runCatching { com.optionslab.ira.ArmExits.set(EXITS_KEY, emptyList()) }
        runCatching { KiteStream.want(STREAM_OWNER, emptySet()) }
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
            cache?.let { b -> b.positions.isNotEmpty() || b.log.isNotEmpty() || b.looks.isNotEmpty() || b.phantoms.isNotEmpty() } == true
    }.getOrDefault(true)

    /** Its book, and any copy set aside as unreadable. */
    internal fun filesForErase(): List<File> = listOfNotNull(file).flatMap { f ->
        listOf(f) + f.parentFile?.listFiles { x -> x.name.startsWith(f.name + ".unreadable.") }.orEmpty()
    }
}
