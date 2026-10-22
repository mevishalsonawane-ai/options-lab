package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.IST
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.mcx.McxArmRules
import com.optionslab.engine.mcx.McxContract
import com.optionslab.engine.mcx.McxEveRules
import com.optionslab.engine.mcx.McxExpiry
import com.optionslab.engine.mcx.McxInstruments
import com.optionslab.engine.mcx.McxMorningPark
import com.optionslab.engine.mcx.McxMorningRules
import com.optionslab.engine.mcx.McxSession
import com.optionslab.engine.mcx.McxTrendRules
import com.optionslab.engine.mcx.McxUsSilverRules
import com.optionslab.engine.sandbox.SandboxEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * The three MCX research candidates as PAPER-ONLY arms (9 Oct 2026; the rules are the engine's [McxEveRules],
 * [McxMorningRules] and [McxTrendRules]): the NATURALGAS evening breakout (M3), the SILVERM morning OTM call (M4) and the
 * 12-month trend rule on the MCX minis (M2). None passed its research gates: each is "Not proven - paper only", OFF by
 * default, 1 lot fixed, no compounding.
 *
 * 18 Oct (Boss: "What ever strategies are present for paper, should be present for live too"): this file itself still places
 * only paper orders; its live path is [com.optionslab.app.data.ArmLive], told of each paper buy, stop or lock and close (memory
 * only). That live trade goes only in Live with "AI trades go live" on, its own "Trade live" switch on, outside the paper-only
 * period and past every guard - so the words below about Zerodha describe this book, not that live path.
 *
 * PAPER ONLY, always: every order here is the paper account's ([Paper.place] / [Paper.cancel]); nothing in this file can
 * reach Zerodha, whatever Live, "AI trades go live" or any voice command says - there is no live flag to set, and its file
 * is not in the backup. New entries obey MCX's hours and holidays ([McxMarket.isOpen]), the kill switch, the bot's stop
 * for today (the daily loss limit included), the account's day lock, MCX's expiry rules ([McxGuard.entryRefusal]: no option
 * buys on expiry day or after 15:00 the day before, no delivery future from 5 trading days before expiry) and a fresh
 * price (the paper account itself refuses a stale fill). Fills are the honest paper ones: the MCX spread on every fill and
 * MCX's charges ([Paper]). Exits always go (they lower risk): the option's resting stop fills on a minute's LOW (the wick,
 * [Paper]'s resting fills), the bot's stop for today sells what they hold, the MCX expiry exit and the sweeper may close a
 * position first (then it is booked as closed there). Every decision is in the arm's log and the diagnostics.
 */
object McxPaperArms {
    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, "mcx_arms.vault")
        // 18 Oct (Boss: every paper strategy present for live too): their paper trades, for their live path ([ArmLive]).
        ArmLive.register(com.optionslab.engine.orb.LiveMirror.MCX) {
            if (cache == null) null else _view.value.let { v ->
                val today = Market.today()
                (v.open + v.closed.filter { it.exitTime?.toLocalDate() == today }).map { ArmLive.PaperLeg(mirrorRef(it), it.arm, if (it.open) it.qty else 0) }
            }
        }
    }

    /** A trade's reference for its live path ([ArmLive]): its arm and paper order (or its entry time). */
    private fun mirrorRef(p: Pos): String = "mcx:${p.arm}:${p.orderId ?: p.entryTime}"

    /** The arms, in the order the screen lists them (the US-night silver futures rule, 9 Oct research R2, last). */
    val ARMS: List<String> = listOf(McxEveRules.SOURCE, McxMorningRules.SOURCE, McxTrendRules.SOURCE, McxUsSilverRules.SOURCE)

    fun label(arm: String): String = when (arm) {
        McxEveRules.SOURCE -> McxEveRules.LABEL
        McxMorningRules.SOURCE -> McxMorningRules.LABEL
        McxTrendRules.SOURCE -> McxTrendRules.LABEL
        McxUsSilverRules.SOURCE -> McxUsSilverRules.LABEL
        else -> arm
    }

    fun record(arm: String): String = when (arm) {
        McxEveRules.SOURCE -> McxEveRules.RECORD
        McxMorningRules.SOURCE -> McxMorningRules.RECORD
        McxTrendRules.SOURCE -> McxTrendRules.RECORD
        McxUsSilverRules.SOURCE -> McxUsSilverRules.RECORD
        else -> ""
    }

    fun rules(arm: String): String = when (arm) {
        McxEveRules.SOURCE -> McxEveRules.RULES
        McxMorningRules.SOURCE -> McxMorningRules.RULES
        McxTrendRules.SOURCE -> McxTrendRules.RULES
        McxUsSilverRules.SOURCE -> McxUsSilverRules.RULES
        else -> ""
    }

    /** Where every order of these arms goes. There is no other venue: the arms are paper only. */
    const val VENUE = "paper"

    /** One paper position. [side] +1 long, -1 short; [qty] units (1 lot x multiplier). */
    data class Pos(
        val arm: String, val symbol: String, val side: Int, val qty: Int, val entry: Double, val entryDay: LocalDate,
        val entryTime: LocalDateTime, val entryMinute: Int, val expiry: LocalDate, val option: Boolean, val orderId: String?,
        val stopOrderId: String? = null, val futureKey: String? = null, val stopF: Double? = null, val targetF: Double? = null,
        val flatBy: Int? = null, val leg: String? = null, val exit: Double? = null, val exitTime: LocalDateTime? = null,
        val why: String? = null, val charges: Double = 0.0,
    ) {
        val open: Boolean get() = exit == null
        /** Rupees after charges, once closed. */
        val net: Double? get() = exit?.let { (it - entry) * qty * side - charges }
    }

    private class Book(
        val armed: MutableMap<String, Boolean> = HashMap(),
        val positions: MutableList<Pos> = ArrayList(),
        /** "day|key" already decided today. */
        val decided: MutableSet<String> = HashSet(),
        /** "yyyy-MM|LEG" -> the month's trend signal. */
        val signals: MutableMap<String, Int> = HashMap(),
        /** 21 Oct: with the day each line was written ([StatusMap]). */
        val status: StatusMap = StatusMap(),
        val log: ArrayList<String> = ArrayList(),
        /** Small facts kept between days ("silver_near": the near SILVERMIC expiry the US-night silver arm last saw). */
        val marks: MutableMap<String, String> = HashMap(),
    )

    /**
     * What Home's MCX group shows; [nets] each arm's closed trades' rupees after charges, oldest first (its paper test,
     * over every closed trade the book keeps, not only the last ones shown).
     */
    data class View(val armed: Map<String, Boolean>, val status: Map<String, String>, val open: List<Pos>, val closed: List<Pos>, val log: List<String>,
                    val nets: Map<String, List<Double>> = emptyMap(),
                    /** The same closed trades with the day each closed, per arm, for the paper test ([com.optionslab.ira.Vetting]). */
                    val trades: Map<String, List<com.optionslab.ira.Vetting.Trade>> = emptyMap(),
                    /** The arms switched off once on Boss's OK and not switched back on since (14 Oct: [McxMorningPark]); their rows say why. */
                    val parked: Set<String> = emptySet()) {
        fun isArmed(arm: String): Boolean = armed[arm] == true
    }

    private val _view = MutableStateFlow(View(emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList()))
    val view: StateFlow<View> = _view

    @Volatile private var cache: Book? = null
    /** The one-time switch-off's notification ([parkMorning]); the arms' own trades use 6970 + their index. */
    private const val NOTICE_ID = 6949
    /** A mark "parked:<arm>" (its day): switched off once on Boss's OK, until Boss switches it back on ([setArmed]). */
    private const val PARKED_MARK = "parked:"
    private const val LOG_KEPT = 200
    private const val CLOSED_KEPT = 400

    /** TEST SEAM: a leg's daily closes (the trend arm), instead of Zerodha's historical data. */
    @Volatile internal var testDaily: ((String) -> List<Pair<LocalDate, Double>>)? = null

    private fun ready() = ::file.isInitialized

    /**
     * One book per process (14 Oct review): the watch's scheduling reads it without [lock] ([loaded]) while a pass reads it under
     * the lock; built under this monitor so two first reads never build two books (a position booked in one lost from the other)
     * nor run the one-time switch-off twice.
     */
    private val building = Any()

    private fun book(): Book {
        cache?.let { return it }
        synchronized(building) { cache?.let { return it }; return build() }
    }

    private fun build(): Book {
        val b = Book()
        // Nothing saved yet (a new install): the one-time change below has nothing to change ([McxMorningPark]).
        val existed = file.exists()
        runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            o.optJSONObject("armed")?.let { a -> a.keys().forEach { b.armed[it] = a.getBoolean(it) } }
            o.optJSONArray("positions")?.let { a -> for (i in 0 until a.length()) b.positions += readPos(a.getJSONObject(i)) }
            o.optJSONArray("decided")?.let { a -> for (i in 0 until a.length()) b.decided += a.getString(i) }
            o.optJSONObject("signals")?.let { m -> m.keys().forEach { b.signals[it] = m.getInt(it) } }
            b.status.readFrom(o, runCatching { file.lastModified() }.getOrDefault(0L))
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
            o.optJSONObject("marks")?.let { m -> m.keys().forEach { b.marks[it] = m.getString(it) } }
        }.onFailure { if (file.exists()) Vault.setAside(file) }
        val parked = runCatching { parkMorning(b, newBook = !existed) }.getOrDefault(false)
        cache = b
        if (parked) save(b) else publish(b)
        return b
    }

    /**
     * Once, on this update (14 Oct 2026, Boss's OK; research R42's pre-registered rule, [McxMorningPark]): the SILVERM morning
     * call is switched off if it is on. Paper only, like every arm here: nothing can reach Zerodha, so nothing carries over to
     * live. Switching off only stops NEW entries: a position it holds stays in the book and [tick] still watches it and
     * closes it by its own 4-hour exit (the expiry exit and the bot's stop as before). Told once - the arm log, the
     * diagnostics, a notification, and the Autopilot change log, where one tap switches it back on (paper only). Never runs
     * again: an arm Boss switches back on stays on. A new book has nothing to switch off (the mark is saved with its first
     * save). True when [b] must be saved.
     */
    private fun parkMorning(b: Book, newBook: Boolean): Boolean {
        val off = McxMorningPark.switchOff(b.armed, done = McxMorningPark.MIGRATION in b.marks) ?: return false
        b.marks[McxMorningPark.MIGRATION] = McxMarket.now().toLocalDate().toString()
        if (newBook) return false
        if (!off) return true
        val arm = McxMorningPark.SOURCE
        val holding = b.positions.any { it.arm == arm && it.open }
        b.armed[arm] = false
        b.status[arm] = McxMorningPark.PARKED
        b.marks[PARKED_MARK + arm] = b.marks.getValue(McxMorningPark.MIGRATION)
        note(b, arm, McxMorningPark.PARKED + if (holding) "; what it holds still closes by its own exit" else "")
        if (!com.optionslab.app.BuildConfig.GOLD) {
            runCatching { com.optionslab.app.ira.Autopilot.logMcxPark(arm, label(arm), holding) }
            runCatching { Diag.record("mcx-arms", McxMorningPark.NOTICE) }
            runCatching { Notifier.post(app, NOTICE_ID, Notifier.SCHEDULE, "SILVERM morning call switched off", McxMorningPark.NOTICE, "almanac") }
        }
        return true
    }

    private fun readPos(p: JSONObject): Pos = Pos(
        p.getString("arm"), p.getString("symbol"), p.getInt("side"), p.getInt("qty"), p.getDouble("entry"),
        LocalDate.parse(p.getString("entryDay")), LocalDateTime.parse(p.getString("entryTime")), p.getInt("entryMinute"),
        LocalDate.parse(p.getString("expiry")), p.getBoolean("option"), p.optString("orderId").ifEmpty { null },
        p.optString("stopOrderId").ifEmpty { null }, p.optString("futureKey").ifEmpty { null },
        if (p.has("stopF")) p.getDouble("stopF") else null, if (p.has("targetF")) p.getDouble("targetF") else null,
        if (p.has("flatBy")) p.getInt("flatBy") else null, p.optString("leg").ifEmpty { null },
        if (p.has("exit")) p.getDouble("exit") else null, p.optString("exitTime").ifEmpty { null }?.let { LocalDateTime.parse(it) },
        p.optString("why").ifEmpty { null }, p.optDouble("charges", 0.0),
    )

    private fun writePos(p: Pos): JSONObject = JSONObject().put("arm", p.arm).put("symbol", p.symbol).put("side", p.side).put("qty", p.qty)
        .put("entry", p.entry).put("entryDay", p.entryDay.toString()).put("entryTime", p.entryTime.toString()).put("entryMinute", p.entryMinute)
        .put("expiry", p.expiry.toString()).put("option", p.option).put("orderId", p.orderId ?: "").put("stopOrderId", p.stopOrderId ?: "")
        .put("futureKey", p.futureKey ?: "").put("leg", p.leg ?: "").put("charges", p.charges)
        .apply {
            p.stopF?.let { put("stopF", it) }; p.targetF?.let { put("targetF", it) }; p.flatBy?.let { put("flatBy", it) }
            p.exit?.let { put("exit", it) }; p.exitTime?.let { put("exitTime", it.toString()) }; p.why?.let { put("why", it) }
        }

    /** What this process last wrote to the book's file: an unchanged save is not encrypted and synced again ([Vault.LastWrite]). */
    private val written = Vault.LastWrite()

    private fun save(b: Book) {
        val o = JSONObject()
        o.put("armed", JSONObject().apply { b.armed.forEach { (k, v) -> put(k, v) } })
        o.put("positions", JSONArray().apply { b.positions.forEach { put(writePos(it)) } })
        o.put("decided", JSONArray().apply { b.decided.sorted().takeLast(200).forEach { put(it) } })
        o.put("signals", JSONObject().apply { b.signals.keys.sorted().takeLast(60).forEach { put(it, b.signals.getValue(it)) } })
        o.put("status", JSONObject().apply { b.status.forEach { (k, v) -> put(k, v) } })
        b.status.writeTo(o)
        o.put("log", JSONArray().apply { b.log.takeLast(LOG_KEPT).forEach { put(it) } })
        o.put("marks", JSONObject().apply { b.marks.forEach { (k, v) -> put(k, v) } })
        runCatching { written.write(file, o.toString().toByteArray(Charsets.UTF_8)) }
        publish(b)
    }

    private fun publish(b: Book) {
        // The plain hint the main thread reads instead of this vault ([McxMarket.watchDueQuick]); written only on a change.
        // 21 Oct, review: this book's arms only (the NR7 record keeps its own key, [McxMarket.noteNr7On]).
        runCatching { McxMarket.noteArmsArmed(b.armed.values.any { it }) }
        _view.value = View(b.armed.toMap(), b.status.shown({ _, x -> x == McxMorningPark.PARKED }), b.positions.filter { it.open }, b.positions.filter { !it.open }.takeLast(30),
            b.log.takeLast(40), b.positions.filter { !it.open }.groupBy { it.arm }.mapValues { (_, l) -> l.map { it.net ?: 0.0 } },
            b.positions.filter { !it.open }.groupBy { it.arm }.mapValues { (_, l) ->
                l.map { com.optionslab.ira.Vetting.Trade((it.exitTime ?: it.entryTime).toLocalDate(), it.net ?: 0.0) } },
            b.marks.keys.filter { it.startsWith(PARKED_MARK) }.map { it.removePrefix(PARKED_MARK) }.toSet())
        // The trade manager's one call (10 Oct), every leg: SHADOW by default - it records only; the arms' own exits stay authoritative.
        runCatching {
            val now = McxMarket.now()
            val since = now.minusDays(2)
            val cal = runCatching { McxMarket.calendar() }.getOrDefault(McxSession.DEFAULT)
            TradeManagerHost.sync(b.positions.filter { it.open || it.exitTime?.isAfter(since) == true }.mapNotNull { managed(it, cal, now.toLocalDate()) }, owner = "mcx")
        }
    }

    /** [p]'s id for the trade manager. */
    private fun managerId(p: Pos): String = "mcx:${p.arm}:${p.symbol}:${p.entryTime}"

    /** Each arm's family for the trade manager ([com.optionslab.ira.TradeManager.FAMILIES]). */
    private fun familyOf(arm: String): String? = when (arm) {
        McxEveRules.SOURCE -> "mcx-eve"
        McxMorningRules.SOURCE -> "mcx-morning"
        McxTrendRules.SOURCE -> "mcx-trend"
        McxUsSilverRules.SOURCE -> "silver-night"
        else -> null
    }

    /**
     * [p] as the trade manager follows it: a bought option (its bet: a call up, a put down) or a future (its own stop and target
     * on the future's price; a short one followed negated), out by its arm's own exit time on its entry day, never after that
     * day's MCX close ([McxArmRules.squareOffMinute]); a trend leg (held for days, no time exit) by [today]'s close. Was 20 hours
     * after the entry: a 10:21 SILVERM morning call showed "1195 minutes to the square-off" at 10:26 (06:21 the next day).
     */
    private fun managed(p: Pos, cal: McxSession.Calendar, today: LocalDate): TradeManagerHost.ArmTrade? {
        val fam = familyOf(p.arm) ?: return null
        val dir = betOf(p)
        val at = p.entryTime.atZone(IST).toInstant().toEpochMilli()
        val day = if (p.arm == McxTrendRules.SOURCE && today.isAfter(p.entryDay)) today else p.entryDay
        val offMin = McxArmRules.squareOffMinute(p.arm, p.entryMinute, p.flatBy, runCatching { cal.window(day) }.getOrNull(), day)
        val off = day.atStartOfDay(IST).toInstant().toEpochMilli() + offMin * 60_000L
        return TradeManagerHost.ArmTrade(fam, managerId(p), label(p.arm), SmartWorkers.underlyingOf(p.symbol), dir, p.symbol, p.entry, p.qty,
            if (p.option) null else p.stopF, if (p.option) null else p.targetF, off, live = false, entryMs = at,
            exit = p.exit, exitWhy = p.why, exitMs = p.exitTime?.atZone(IST)?.toInstant()?.toEpochMilli(), shortFuture = p.side < 0, tick = if (p.option) 0.05 else 1.0)
    }

    private fun note(b: Book, arm: String, text: String) {
        val line = "${McxMarket.now().withNano(0)} ${label(arm)}: $text"
        b.log += line
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        runCatching { Diag.record("mcx-arms", line) }
    }

    /** A decision for [arm] today: kept as its status and logged once. */
    private fun decide(b: Book, arm: String, key: String, why: String) {
        b.decided += key
        b.status[arm] = why
        note(b, arm, why)
    }

    /** Read the book (the page calls it when shown). */
    suspend fun refresh(): View { if (ready()) lock.withLock { book() }; return _view.value }

    /** Switch an arm on or off - on paper, always. Off sells nothing it holds: its own exits still close it. */
    suspend fun setArmed(arm: String, on: Boolean): String {
        if (!ready()) return "The MCX paper arms are not ready yet."
        if (arm !in ARMS) return "No such MCX arm."
        // 21 Oct (Boss: remove the SILVERM morning call and the 12-month trend): hidden by the cleanup ([HiddenArms]); only its
        // Hidden arms list (a restore) lets it be switched on again. Off is always allowed (it only lowers risk).
        if (on && !HiddenArms.mayArm(arm)) return HiddenArms.refusal(label(arm))
        return lock.withLock {
            val b = book()
            if ((b.armed[arm] == true) == on) return@withLock "${label(arm)} is already ${if (on) "on" else "off"} (paper only)."
            b.armed[arm] = on
            // Back on after the 14 Oct switch-off ([McxMorningPark]): no longer parked, its row no longer says why, and the
            // change log's entry is closed (its Undo can no longer switch it on later, after Boss switched it off by hand).
            if (on && b.marks.remove(PARKED_MARK + arm) != null) {
                if (b.status[arm] == McxMorningPark.PARKED) b.status.remove(arm)
                if (!com.optionslab.app.BuildConfig.GOLD) runCatching { com.optionslab.app.ira.Autopilot.mcxUnparkedByBoss(arm) }
            }
            note(b, arm, if (on) "switched on, paper only (${McxArmRules.NOT_PROVEN}; ${record(arm)})" else "switched off" +
                if (b.positions.any { it.arm == arm && it.open }) "; what it holds still closes by its own exits" else "")
            save(b)
            if (on) "${label(arm)} on, paper only. ${McxArmRules.NOT_PROVEN}. ${record(arm)}." else "${label(arm)} off."
        }
    }

    /** For the arms cleanup ([HiddenArms]): [arm]'s switch and whether it holds an open position, or null before the book is ready. */
    suspend fun cleanupNow(arm: String): Pair<Boolean, Boolean>? {
        if (!ready()) return null
        return lock.withLock { val b = book(); (b.armed[arm] == true) to b.positions.any { it.arm == arm && it.open } }
    }

    /** True while [arm] is armed (read without the lock). */
    fun armed(arm: String): Boolean = loaded().isArmed(arm)

    /** The view, the book read first when nothing has read it yet (the watch's scheduling asks before any pass). */
    private fun loaded(): View {
        if (cache == null && ready()) runCatching { book() }
        return _view.value
    }

    /** The diagnostics line. */
    fun diagLine(): String {
        val v = _view.value
        return "MCX paper arms: " + ARMS.joinToString(" · ") { a ->
            val closed = v.closed.filter { it.arm == a }
            "${label(a)} ${if (v.isArmed(a)) "on (paper only)" else "off"}, open ${v.open.count { it.arm == a }}, last ${closed.size} closed " +
                "%,.0f after charges".format(Locale.ENGLISH, closed.sumOf { it.net ?: 0.0 }) + (v.status[a]?.let { " ($it)" } ?: "")
        }
    }

    // ---- when the watch must run for them ---------------------------------------------------------------------------------

    /**
     * The minutes of the day the arms need the watch (IST). 12 Oct: with ANY arm armed (or a position still open in an arm's
     * book), the whole MCX session, to its close (23:30 / 23:55, [McxMarket.isOpen]) - not only the arm's entry minutes. On
     * 12 Oct the SILVERM morning call's stop filled at 16:30:36, the paper book then held nothing on MCX and 16:30 was
     * between the morning call's minutes (to 14:00) and the evening break's (from 17:00): the watch and the price stream
     * ended at 16:30 with MCX open and three arms armed, and came back only at 17:01 (the arm booked its close then).
     */
    private fun windows(v: View): List<IntRange> =
        // 21 Oct: the NR7 record ([McxNr7Record], no orders) needs the MCX session's minutes too while it records.
        // (Its book, or its hint before that book is read after a restart: [McxMarket.nr7OnQuick].)
        if (ARMS.any { v.isArmed(it) } || v.open.isNotEmpty() || McxMarket.nr7OnQuick()) listOf(0..24 * 60) else emptyList()

    /** Whether the MCX watch should run now for the arms: MCX open and an arm armed, or a position open in an arm's book. */
    fun wantsWatch(): Boolean = runCatching {
        val now = McxMarket.now()
        val minute = now.hour * 60 + now.minute
        val w = windows(loaded())
        w.isNotEmpty() && McxMarket.isOpen() && w.any { minute in it }
    }.getOrDefault(false)

    /** The arms switched on (the book as last read; read first when nothing has read it yet). */
    fun armedNow(): List<String> = loaded().let { v -> ARMS.filter { v.isArmed(it) } }

    /**
     * A position is open in an arm's book (memory: the book as last read; false before it is read - the arms' positions are
     * the paper account's too, which [McxMarket.heldNow] counts on its own).
     */
    fun holdsNow(): Boolean = _view.value.open.isNotEmpty()

    /** [inEntryMinutes] from memory only (never the vault): false before the arms' book is read in this process. */
    fun inEntryMinutesQuick(): Boolean = runCatching {
        val v = _view.value
        val now = McxMarket.now()
        com.optionslab.ira.McxRest.inEntryMinutes(ARMS.filter { v.isArmed(it) }, now.hour * 60 + now.minute)
    }.getOrDefault(false)

    /**
     * Battery (14 Oct): an armed arm is inside its own entry minutes now ([com.optionslab.ira.McxRest.entryMinutes]), so the
     * MCX pass keeps its 15 s pace there; unknown counts as inside (the 15 s pace, as before).
     */
    fun inEntryMinutes(): Boolean = runCatching {
        val now = McxMarket.now()
        com.optionslab.ira.McxRest.inEntryMinutes(armedNow(), now.hour * 60 + now.minute)
    }.getOrDefault(true)

    /** [wantsWatch] from memory only (never the vault): null when the arms' book has not been read in this process yet. */
    fun wantsWatchIfLoaded(): Boolean? {
        if (!ready() || cache == null) return null
        return wantsWatch()
    }

    /** [nextWakeMillis] from memory only (never the vault): null when the arms' book has not been read in this process yet. */
    fun nextWakeMillisIfLoaded(): Long? {
        if (!ready() || cache == null) return null
        return nextWakeMillis()
    }

    /** The next time (epoch ms) an armed arm needs the watch within a week, on MCX's calendar; null when none is armed. */
    fun nextWakeMillis(): Long? = runCatching {
        val w = windows(loaded())
        if (w.isEmpty()) return@runCatching null
        val now = McxMarket.now()
        val cal = McxMarket.calendar()
        for (i in 0..7) {
            val d = now.toLocalDate().plusDays(i.toLong())
            val s = cal.window(d) ?: continue
            val open = s.open.hour * 60 + s.open.minute
            val close = s.close.hour * 60 + s.close.minute
            val starts = w.mapNotNull { r -> maxOf(r.first, open).takeIf { it < close && it <= r.last } }.sorted()
            for (m in starts) {
                val t = d.atTime(m / 60, m % 60)
                if (t.isAfter(now)) return@runCatching t.atZone(IST).toInstant().toEpochMilli()
            }
        }
        null
    }.getOrNull()

    // ---- the pass ---------------------------------------------------------------------------------------------------------

    /** Called by the market watch's NSE pass and its MCX pass: exits first, then each armed arm's entries. */
    suspend fun tick() {
        if (!ready()) return
        lock.withLock {
            val b = book()
            if (b.armed.values.none { it } && b.positions.none { it.open }) return@withLock
            val now = McxMarket.now().withNano(0)
            val cal = McxMarket.calendar()
            val window = cal.window(now.toLocalDate())
            var changed = runCatching { manage(b, now, cal, window) }.getOrElse { note(b, "mcx", "exit check failed: ${it.javaClass.simpleName}"); true }
            // The strategy lab (11 Oct): the MCX paper arms ran today (one map write: no setting, no I/O; not tuned in the lab).
            if (b.armed.values.any { it }) VariantRunner.saw("mcx", live = false)
            if (McxMarket.isOpen()) {
                if (b.armed[McxEveRules.SOURCE] == true)
                    changed = runCatching { eve(b, now, window) }.getOrElse { b.status[McxEveRules.SOURCE] = "error: ${it.javaClass.simpleName}"; true } || changed
                // 21 Oct: a hidden arm takes no new entry ([HiddenArms.blocks], memory only); what it holds keeps its own exits ([manage]).
                if (b.armed[McxMorningRules.SOURCE] == true && !HiddenArms.blocks(McxMorningRules.SOURCE))
                    changed = runCatching { morning(b, now, cal, window) }.getOrElse { b.status[McxMorningRules.SOURCE] = "error: ${it.javaClass.simpleName}"; true } || changed
                if (b.armed[McxTrendRules.SOURCE] == true)
                    changed = runCatching { trend(b, now, cal) }.getOrElse { b.status[McxTrendRules.SOURCE] = "error: ${it.javaClass.simpleName}"; true } || changed
                if (b.armed[McxUsSilverRules.SOURCE] == true)
                    changed = runCatching { silver(b, now, cal, window) }.getOrElse { b.status[McxUsSilverRules.SOURCE] = "error: ${it.javaClass.simpleName}"; true } || changed
            }
            // Closed trades beyond what is kept are dropped (oldest first); open ones always stay.
            val closed = b.positions.count { !it.open }
            if (closed > CLOSED_KEPT) {
                var drop = closed - CLOSED_KEPT
                b.positions.removeAll { !it.open && drop-- > 0 }
                changed = true
            }
            if (changed) save(b)
        }
    }

    // ---- guards -----------------------------------------------------------------------------------------------------------

    /**
     * Whether the bot is stopped for today for these arms (Boss's stop, the tile, Zerodha's loss limit; 21 Oct: with commodities on
     * their own limits, their own loss limit and not the NSE paper one, [LossBreaker.mcxStopped]). Unknown counts as stopped.
     */
    private suspend fun stoppedToday(): Boolean = stoppedKnown() ?: true

    /**
     * The stop for today as read, or null when it could not be read. An entry treats null as stopped ([stoppedToday]); an EXIT
     * never acts on it (22 Oct audit: a failed read sold every held MCX position as "operator_stop", which is not a stop
     * Boss made). TEST ONLY: [testStoppedWhy] replaces the read.
     */
    private suspend fun stoppedKnown(): Boolean? = runCatching {
        LossBreaker.mcxStopped(testStoppedWhy.let { h -> if (h != null) h() else Strategies.stoppedWhy() })
    }.getOrNull()

    @Volatile internal var testStoppedWhy: (() -> com.optionslab.ira.DayStop.Why?)? = null

    /** The MCX arms' refusal words while their own loss stop holds (else the bot's stop for today). */
    private fun stoppedWords(): String? = if (runCatching { LossBreaker.mcxTrippedToday() }.getOrDefault(false))
        com.optionslab.ira.SegmentLock.MCX_LOSS_REFUSAL else null

    /** Why a new entry in [c] ([buy]: a buy) may not go now, or null ([fresh]: the deciding price is under a minute old). */
    private suspend fun refusal(c: Paper.Contract, buy: Boolean, fresh: Boolean): String? = McxArmRules.entryRefusal(
        sessionOpen = McxMarket.isOpen(),
        kill = runCatching { AppSettings.load().guardKill }.getOrDefault(true),
        stoppedToday = stoppedToday(),
        // 21 Oct: the commodities' own day lock while they keep their own (NSE's never stops them), else the shared paper lock.
        dayLock = runCatching { DayLockGuard.mcxRefusal() }.getOrElse { "the day lock could not be checked" },
        expiry = McxGuard.entryRefusal(c, buy),
        freshPrice = fresh,
    )?.let { r -> if (r == "stopped_for_today") stoppedWords()?.let { "$r: $it" } ?: r else r }

    private fun nowSec(): Long = Market.now().toEpochSecond()

    /**
     * Today's finished 1-minute bars of [key] (the paper feed's), oldest first; round 2: with the minutes the feed has not
     * published yet built from the stream when whole and gap-free ([LocalCandles]), so the arms decide at the close.
     */
    private suspend fun minutes(key: String, now: LocalDateTime): List<Upstox.Bar> {
        val nowSec = now.atZone(IST).toEpochSecond()
        val feed = Net.intraday(key).filter { it.istDate == now.toLocalDate() && it.epochSecond + 60 <= nowSec }.sortedBy { it.epochSecond }
        return LocalCandles.overlay(key, feed, nowSec).filter { it.istDate == now.toLocalDate() && it.epochSecond + 60 <= nowSec }
    }

    private fun minuteOf(t: LocalDateTime): Int = t.hour * 60 + t.minute

    private fun hhmm(m: Int): String = "%02d:%02d".format(m / 60, m % 60)

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

    private fun chargesOf(orderId: String?): Double =
        if (orderId == null) 0.0 else Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    /** The net quantity the paper book holds of [symbol] (all products). */
    private fun heldQty(symbol: String): Int = Paper.state.positions.filter { it.symbol == symbol }.sumOf { it.quantity }

    /**
     * 22 Oct (arms audit): another arm's open position in [symbol] the other way from [side] (the paper book nets positions by
     * symbol, so an entry against it would close that arm's leg instead of opening one); null when none.
     */
    private fun heldOtherWay(b: Book, arm: String, symbol: String, side: Int): Pos? =
        b.positions.firstOrNull { it.open && it.arm != arm && it.symbol == symbol && it.side == -side }

    /**
     * Buy (or sell, [action]) 1 lot of [c] at market on paper; the fill and its order id, or the reason it did not fill. 22 Oct
     * (arms audit): an [exit] only ever reduces what the paper book holds ([Paper.place]'s exit rule) - never a position the
     * other way when the position was already closed by the expiry exit, the sweeper or Boss.
     */
    private suspend fun paperOrder(c: Paper.Contract, action: String, tag: String, exit: Boolean = false): Pair<SandboxEvent.Fill?, Paper.Result> {
        val r = Paper.place(c, action, McxArmRules.LOTS, "MARKET", "NRML", null, null, exit = exit)
        val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (fill == null) r.orderId?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
        else r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", tag) } }
        return fill to r
    }

    // ---- exits ------------------------------------------------------------------------------------------------------------

    private suspend fun manage(b: Book, now: LocalDateTime, cal: McxSession.Calendar, window: McxSession.Window?): Boolean {
        var changed = false
        val stopped = stoppedKnown() == true            // unknown: never a reason to sell what is held
        val cfg = McxGuard.config(AppSettings.load())
        val minute = minuteOf(now)
        for ((i, p) in b.positions.withIndex().toList()) {
            if (!p.open) continue
            val c = Paper.contractOf(p.symbol) ?: continue
            val orders = Paper.state.orders.associateBy { it.orderId }
            // The option's resting stop filled in the paper book (on a minute's low): that is the exit.
            val so = p.stopOrderId?.let { orders[it] }
            if (so != null && so.status == "complete") {
                val done = p.copy(exit = so.averagePrice?.toDouble() ?: stopFallback(p), exitTime = so.updateTimestamp, why = stopWhy(p),
                    stopOrderId = null, charges = p.charges + chargesOf(so.orderId))
                b.positions[i] = done; closed(b, done); changed = true; continue
            }
            // Gone from the paper book without this arm closing it: the MCX expiry exit, the sweeper, or Boss.
            val held = heldQty(p.symbol)
            if (held * p.side <= 0) {
                p.stopOrderId?.let { runCatching { Paper.cancel(it, "position_closed") } }
                val closing = if (p.side > 0) "SELL" else "BUY"
                val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == closing && !it.timestamp.isBefore(p.entryTime) }
                val done = p.copy(exit = t?.price?.toDouble() ?: p.entry, exitTime = t?.timestamp ?: now, why = "closed_elsewhere",
                    stopOrderId = null, charges = p.charges + (t?.charges?.toDouble() ?: 0.0))
                b.positions[i] = done; closed(b, done); changed = true; continue
            }
            val expiryDue = McxExpiry.due(c.underlying, c.expiry, p.option, now, cal, cfg) != null
            val why: String? = when {
                stopped -> "operator_stop"
                expiryDue -> "expiry_exit"
                p.arm == McxEveRules.SOURCE -> eveExit(p, c, now)
                p.arm == McxMorningRules.SOURCE ->
                    if (now.toLocalDate().isAfter(p.entryDay)) "four_hours" else McxMorningRules.exit(p.entryMinute, minute, window)
                p.arm == McxUsSilverRules.SOURCE -> silverExit(p, c, now, window)
                else -> null   // the trend's legs close on their own month's signal and rolls ([trend])
            }
            // The trade manager wants it out: only when Boss switched this arm to ACT (SHADOW by default: never here).
            val due = why ?: TradeManagerHost.exitDue(managerId(p))?.let { "trade_manager" } ?: continue
            if (!McxMarket.isOpen()) continue      // nothing fills while MCX is shut: tried again at its next session
            val next = exit(b, p, c, due)
            if (next != p) { b.positions[i] = next; changed = true }
        }
        return changed
    }

    /**
     * The way [p] bets on its underlying: a bought call +1, a bought put -1 (the position's own [Pos.side] is the OPTION's side,
     * +1 for every bought option); a future its own side. 22 Oct audit: the evening break's exit read [Pos.side] as the break's
     * side, so every down-break PUT (side +1, a break of -1) was judged "future back at the range middle" on its first finished
     * minute and sold within a minute of the buy (20 Oct 290 PE, 21 Oct 275 PE in the diagnostics).
     */
    private fun betOf(p: Pos): Int = if (p.option && p.symbol.endsWith("PE")) -p.side else p.side

    /** The evening break's exit at [now]: every finished minute since the entry, in order (the option's LOW, the future's close). */
    private suspend fun eveExit(p: Pos, c: Paper.Contract, now: LocalDateTime): String? {
        if (now.toLocalDate().isAfter(p.entryDay)) return "flat_2315"
        val held = McxEveRules.Held(betOf(p), p.entry, p.entryMinute, p.stopF ?: return "flat_2315", p.targetF ?: return "flat_2315",
            p.flatBy ?: McxEveRules.FLAT_BY)
        val fut = p.futureKey?.let { k -> runCatching { minutes(k, now) }.getOrNull() }.orEmpty().associateBy { it.istMinute }
        val opt = runCatching { minutes(c.feedKey, now) }.getOrNull().orEmpty().associateBy { it.istMinute }
        val last = minuteOf(now) - 1
        for (m in p.entryMinute..last) {
            McxEveRules.exit(held, opt[m]?.low, fut[m]?.close, m)?.let { return it }
        }
        // The clock alone (no minute printed yet): two hours, or the flat time.
        return McxEveRules.exit(held, null, null, minuteOf(now))
    }

    /**
     * Close [p] at market on paper (its resting stop taken out first; if that stop filled meanwhile, that is the exit). 22 Oct
     * (arms audit): a market exit that does not fill (no fresh price) used to leave the position with NO resting stop until the
     * next pass; now the stop goes back into the paper book at its trigger at once (or stays, when its cancel failed). An exit
     * refused because the paper book no longer holds the position (the expiry exit, the sweeper or Boss closed it) is booked
     * "closed_elsewhere" here, as [manage] would on its next look.
     */
    private suspend fun exit(b: Book, p: Pos, c: Paper.Contract, why: String): Pos {
        var stopId = p.stopOrderId
        var trigger: Double? = null
        p.stopOrderId?.let { id ->
            trigger = Paper.state.orders.firstOrNull { it.orderId == id }?.triggerPrice?.toDouble()
            runCatching { Paper.cancel(id, "exit:$why") }
            val so = Paper.state.orders.firstOrNull { it.orderId == id }
            if (so?.status == "complete") {
                val done = p.copy(exit = so.averagePrice?.toDouble() ?: stopFallback(p), exitTime = so.updateTimestamp, why = stopWhy(p), stopOrderId = null,
                    charges = p.charges + chargesOf(id))
                closed(b, done); return done
            }
            // Cancelled (or gone): no stop rests now. Still trigger-pending (the cancel failed): it rests on, under the same id.
            if (so == null || so.status != "trigger pending") stopId = null
        }
        val (fill, r) = paperOrder(c, if (p.side > 0) "SELL" else "BUY", "${label(p.arm)} · exit", exit = true)
        if (fill == null) {
            if (!r.ok && heldQty(p.symbol) * p.side <= 0) {
                // Already flat in the paper book: closed by the expiry exit, the sweeper or Boss - booked at that sale's price.
                val closing = if (p.side > 0) "SELL" else "BUY"
                val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == closing && !it.timestamp.isBefore(p.entryTime) }
                val done = p.copy(exit = t?.price?.toDouble() ?: p.entry, exitTime = t?.timestamp ?: McxMarket.now().withNano(0), why = "closed_elsewhere",
                    stopOrderId = null, charges = p.charges + (t?.charges?.toDouble() ?: 0.0))
                closed(b, done); return done
            }
            if (!b.status.sameToday(p.arm, "exit_retry")) { b.status[p.arm] = "exit_retry"; note(b, p.arm, "${p.symbol}: the $why exit did not fill (${r.message}); tried again next pass") }
            // The stop back in the book at its trigger (a stop the position had): the wick guard holds until the next pass sells.
            val level = trigger ?: p.stopOrderId?.let { stopFallback(p) }
            if (stopId == null && level != null) {
                stopId = runCatching { Paper.place(c, if (p.side > 0) "SELL" else "BUY", McxArmRules.LOTS, "SL-M", "NRML", null, level) }.getOrNull()
                    ?.takeIf { it.ok }?.orderId?.also { runCatching { Strategies.tagOwner("paper:$it", "${label(p.arm)} · stop") } }
            }
            return p.copy(stopOrderId = stopId)
        }
        val done = p.copy(exit = fill.price, exitTime = McxMarket.now().withNano(0), why = why, stopOrderId = null, charges = p.charges + chargesOf(r.orderId))
        closed(b, done)
        return done
    }

    private fun closed(b: Book, p: Pos) {
        runCatching { ArmLive.closed(mirrorRef(p), p.why ?: "exit") }
        b.status[p.arm] = "closed ${p.symbol}: ${p.why}"
        note(b, p.arm, "%s: closed (%s) @ %.2f, %s after charges · paper".format(Locale.ENGLISH, p.symbol, p.why, p.exit ?: 0.0, rs(p.net ?: 0.0)))
        runCatching { Notifier.post(app, 6970 + ARMS.indexOf(p.arm).coerceAtLeast(0), Notifier.SELL, "${label(p.arm)} closed ${p.symbol} · Paper",
            "Closed (%s) @ %.2f: %s after charges. Paper only, not proven.".format(Locale.ENGLISH, p.why, p.exit ?: 0.0, rs(p.net ?: 0.0)), "almanac") }
    }

    private fun opened(b: Book, p: Pos, facts: String) {
        b.positions += p
        // 18 Oct: its live path ([ArmLive]): the same contract and lot at Zerodha (MCX, NRML), long or short as on paper; its paper
        // stop (when it has one) rests at Zerodha as a GTT - the one resting exit that outlives MCX's session.
        runCatching {
            val c = Paper.contractOf(p.symbol) ?: return@runCatching
            val stop = p.stopOrderId?.let { id -> Paper.state.orders.firstOrNull { it.orderId == id }?.triggerPrice?.toDouble() }
            ArmLive.entered(ArmLive.Ask(p.arm, mirrorRef(p), c, p.side, p.qty, p.entry, stop = stop))
        }
        runCatching { LiveRecorder.signal(label(p.arm), p.symbol, null, if (p.side > 0) "BUY" else "SELL", p.entry, paper = true, exchange = "MCX") }
        val verb = if (p.side > 0) "bought" else "sold"
        decideLine(b, p.arm, "%s %s @ %.2f (%s) · paper".format(Locale.ENGLISH, verb, p.symbol, p.entry, facts))
        runCatching { Notifier.post(app, 6970 + ARMS.indexOf(p.arm).coerceAtLeast(0), Notifier.BUY, "${label(p.arm)} $verb ${p.symbol} · Paper",
            "%s %d @ %.2f (%s). Paper only, not proven.".format(Locale.ENGLISH, verb.replaceFirstChar { it.uppercase() }, p.qty, p.entry, facts), "almanac") }
    }

    private fun decideLine(b: Book, arm: String, text: String) { b.status[arm] = text; note(b, arm, text) }

    // ---- NATURALGAS evening breakout (M3) ---------------------------------------------------------------------------------

    private suspend fun eve(b: Book, now: LocalDateTime, window: McxSession.Window?): Boolean {
        val arm = McxEveRules.SOURCE
        val today = now.toLocalDate()
        val key = "$today|eve"
        if (key in b.decided || b.positions.any { it.arm == arm && it.open }) return false
        val minute = minuteOf(now)
        if (minute < McxEveRules.RANGE_TO) {
            val s = "waiting for the 17:00-19:00 range"
            if (!b.status.sameToday(arm, s)) { b.status[arm] = s; return true }
            return false
        }
        val flatBy = McxEveRules.flatBy(window)
        val all = McxMarket.contracts()
        val chain = McxInstruments.nearChain(all, McxEveRules.UNDERLYING, today)
        if (chain.isEmpty()) { decide(b, arm, key, "eve_no_chain"); return true }
        val fut = McxInstruments.underlyingFuture(all, chain.first()) ?: run { decide(b, arm, key, "eve_no_future"); return true }
        val bars = minutes(fut.upstoxKey, now)
        val range = McxEveRules.range(bars) ?: run { decide(b, arm, key, "eve_no_range (under 60 minutes 17:00-19:00 or no width)"); return true }
        val sig = McxEveRules.signal(range, bars)
        if (sig == null) {
            if (minute > McxEveRules.ENTRY_UNTIL + 1) { decide(b, arm, key, "eve_no_break (range %.2f-%.2f)".format(Locale.ENGLISH, range.low, range.high)); return true }
            val s = "range %.2f-%.2f · watching for a break to 22:00".format(Locale.ENGLISH, range.low, range.high)
            if (!b.status.sameToday(arm, s)) { b.status[arm] = s; return true }
            return false
        }
        val facts = "break %s at %s close %.2f, range %.2f-%.2f".format(Locale.ENGLISH, if (sig.side > 0) "up" else "down", hhmm(sig.minute), sig.close, range.low, range.high)
        if (!McxEveRules.entryWindow(sig, minute, flatBy)) { decide(b, arm, key, "eve_late ($facts): the watch was not in time; no trade today"); return true }
        val right = if (sig.side > 0) Right.CE else Right.PE
        val strike = McxEveRules.strike(chain.map { it.strike }, sig.close, sig.side) ?: run { decide(b, arm, key, "eve_no_strike ($facts)"); return true }
        val oc = chain.firstOrNull { it.strike == strike && it.right == right } ?: run { decide(b, arm, key, "eve_no_contract $strike ${right.name} ($facts)"); return true }
        val pc = McxMarket.paperContract(oc)
        val fresh = bars.lastOrNull()?.let { McxArmRules.fresh(it.epochSecond, nowSec()) } == true
        refusal(pc, buy = true, fresh = fresh)?.let { decide(b, arm, key, "eve_$it ($facts)"); return true }
        // The order flow on the future at the decision (9 Oct; paper only): logged; under CONFIRM skipped unless it agrees.
        val flow = FlowGate.gate(arm, McxEveRules.UNDERLYING, sig.side, false, "$today ${hhmm(sig.minute)}", right.name, contract = pc)
        if (flow.skip) { decide(b, arm, key, "eve_${FlowGate.SKIPPED} ($facts)"); return true }
        b.decided += key
        val (fill, r) = paperOrder(pc, "BUY", "${McxEveRules.LABEL} · entry")
        if (fill == null) { decideLine(b, arm, "eve_not_filled: ${r.message} ($facts)"); return true }
        FlowGate.taken(flow, r.orderId)
        // The -15% stop rests in the paper book, so it fills on a minute's low (the wick) between passes.
        val trigger = toTick(McxEveRules.optionStop(fill.price), oc.tick)
        val stop = runCatching { Paper.place(pc, "SELL", McxArmRules.LOTS, "SL-M", "NRML", null, trigger) }.getOrNull()
        val stopId = stop?.takeIf { it.ok }?.orderId?.also { runCatching { Strategies.tagOwner("paper:$it", "${McxEveRules.LABEL} · stop") } }
        opened(b, Pos(arm, pc.symbol, 1, fill.quantity, fill.price, today, now, sig.entryMinute, oc.expiry, true, r.orderId,
            stopOrderId = stopId, futureKey = fut.upstoxKey, stopF = sig.stop, targetF = sig.target, flatBy = flatBy, charges = chargesOf(r.orderId)), facts)
        return true
    }

    /** [x] on [tick]'s grid (nearest). */
    private fun toTick(x: Double, tick: Double): Double {
        val t = java.math.BigDecimal((if (tick > 0) tick else 0.05).toString())
        return java.math.BigDecimal(x.toString()).divide(t, 0, java.math.RoundingMode.HALF_UP).multiply(t).toDouble()
    }

    // ---- SILVERM morning call (M4) ----------------------------------------------------------------------------------------

    private suspend fun morning(b: Book, now: LocalDateTime, cal: McxSession.Calendar, window: McxSession.Window?): Boolean {
        val arm = McxMorningRules.SOURCE
        val today = now.toLocalDate()
        if (b.positions.any { it.arm == arm && it.open }) return false
        val minute = minuteOf(now)
        val slot = McxMorningRules.gridSlot(minute) ?: return false
        val key = "$today|am|${hhmm(slot)}"
        if (key in b.decided) return false
        val lastEntry = b.positions.filter { it.arm == arm && it.entryDay == today }.maxOfOrNull { it.entryMinute }
        if (!McxMorningRules.mayEnter(slot, lastEntry)) return false
        val all = McxMarket.contracts()
        val chain = McxInstruments.nearChain(all, McxMorningRules.UNDERLYING, today)
        if (chain.isEmpty()) { decide(b, arm, key, "am_no_chain"); return true }
        val expiry = chain.first().expiry
        val dte = McxArmRules.tradingDaysLeft(today, expiry, cal)
        if (!McxMorningRules.dteOk(today, expiry, cal)) { decide(b, arm, key, "am_dte $dte (needs 11-20 to ${expiry})"); return true }
        val fut = McxInstruments.underlyingFuture(all, chain.first()) ?: run { decide(b, arm, key, "am_no_future"); return true }
        val bars = minutes(fut.upstoxKey, now)
        val last = bars.lastOrNull() ?: run { decide(b, arm, key, "am_no_future_price"); return true }
        val fresh = McxArmRules.fresh(last.epochSecond, nowSec())
        var why = "am_no_strike"
        for (steps in McxMorningRules.OTM_STEPS) {
            val strike = McxMorningRules.strike(chain.map { it.strike }, last.close, steps) ?: continue
            val oc = chain.firstOrNull { it.strike == strike && it.right == Right.CE } ?: continue
            val pc = McxMarket.paperContract(oc)
            val px = runCatching { Paper.quote(pc) }.getOrNull()?.ltp
            if (px == null || !(px > 0)) { why = "am_no_price ${oc.label}"; continue }
            if (!McxMorningRules.premiumOk(px, oc.multiplier)) { why = "am_premium ${oc.label}: 1 lot Rs %,.0f (needs Rs 500 to Rs 1 lakh)".format(Locale.ENGLISH, px * oc.multiplier); continue }
            val facts = "OTM$steps call, $dte days to expiry, future %.2f".format(Locale.ENGLISH, last.close)
            refusal(pc, buy = true, fresh = fresh)?.let { decide(b, arm, key, "am_$it ($facts)"); return true }
            val flow = FlowGate.gate(arm, McxMorningRules.UNDERLYING, 1, false, "$today ${hhmm(slot)}", "CE", contract = pc)
            if (flow.skip) { decide(b, arm, key, "am_${FlowGate.SKIPPED} ($facts)"); return true }
            b.decided += key
            val (fill, r) = paperOrder(pc, "BUY", "${McxMorningRules.LABEL} · entry")
            if (fill == null) { decideLine(b, arm, "am_not_filled: ${r.message} ($facts)"); return true }
            FlowGate.taken(flow, r.orderId)
            opened(b, Pos(arm, pc.symbol, 1, fill.quantity, fill.price, today, now, slot, oc.expiry, true, r.orderId,
                futureKey = fut.upstoxKey, charges = chargesOf(r.orderId)), "$facts; sells at ${hhmm(McxMorningRules.exitMinute(slot, window))}")
            return true
        }
        decide(b, arm, key, why)
        return true
    }

    // ---- 12-month trend on the minis (M2) ---------------------------------------------------------------------------------

    /** When the trend arm last tried to read a leg's history and could not (in memory: tried again after half an hour). */
    private val trendTried = HashMap<String, Long>()

    private suspend fun dailyCloses(leg: String, fut: McxContract, today: LocalDate): List<Pair<LocalDate, Double>>? {
        testDaily?.let { return it(leg) }
        if (!Broker.loggedIn || fut.token <= 0) return null
        // Zerodha's continuous near-month daily series (needs its historical-data add-on): about 15 months for 252 sessions.
        return Broker.dailyBars(fut.token, today.minusDays(400), today.minusDays(1), continuous = true).map { it.istDate to it.close }
    }

    private suspend fun trend(b: Book, now: LocalDateTime, cal: McxSession.Calendar): Boolean {
        val arm = McxTrendRules.SOURCE
        val today = now.toLocalDate()
        val month = McxTrendRules.month(today)
        val signalDay = McxTrendRules.signalDay(today, cal)
        val cfg = McxGuard.config(AppSettings.load())
        val all = McxMarket.contracts()
        var changed = false
        val notes = ArrayList<String>()
        for (leg in McxTrendRules.LEGS) {
            val futures = McxInstruments.futures(all, leg, today, 3)
            val hold = McxTrendRules.expiryToHold(futures.map { it.expiry }, today, cal, cfg)
            val sigKey = "$month|$leg"
            var sig = b.signals[sigKey]
            if (sig == null) {
                val tried = trendTried[sigKey]
                if (tried != null && System.currentTimeMillis() - tried < 30 * 60_000L) { notes += "$leg: history not read"; continue }
                val near = futures.firstOrNull()
                val closes = near?.let { runCatching { dailyCloses(leg, it, today) }.getOrNull() }
                if (closes == null) { trendTried[sigKey] = System.currentTimeMillis(); notes += "$leg: history not read (needs Zerodha login with historical data)"; continue }
                sig = McxTrendRules.signal(closes, signalDay)
                b.signals[sigKey] = sig
                note(b, arm, "$leg: $month signal ${if (sig > 0) "long" else if (sig < 0) "short" else "none"} (12-month change to $signalDay)")
                changed = true
            }
            val held = b.positions.firstOrNull { it.arm == arm && it.open && it.leg == leg }
            val step = McxTrendRules.step(held?.side ?: 0, held?.expiry, sig, hold)
            val dayKey = "$today|trend|$leg|${step.why}"
            if (step.why == "trend_hold" || dayKey in b.decided) { notes += "$leg: ${step.why.removePrefix("trend_")}"; continue }
            b.decided += dayKey
            changed = true
            if (step.close && held != null) {
                val c = Paper.contractOf(held.symbol)
                if (c == null) { note(b, arm, "$leg: ${held.symbol} not in the paper book"); continue }
                val i = b.positions.indexOf(held)
                val done = exit(b, held, c, step.why)
                if (i >= 0) b.positions[i] = done
                // Not closed (no fill): nothing new before it is, and the close is tried again on the next pass (the day's
                // step is not marked done until it fills).
                if (done.open) { b.decided -= dayKey; continue }
            }
            if (step.open == 0) { notes += "$leg: ${step.why.removePrefix("trend_")}"; continue }
            // 21 Oct: hidden (Boss's request) - its legs still close on their own signal, rolls and expiry exit; nothing new opens.
            if (HiddenArms.blocks(arm)) { notes += "$leg: no new leg (hidden on your request, 21 Oct)"; continue }
            val fc = futures.firstOrNull { it.expiry == hold } ?: continue
            val pc = McxMarket.paperContract(fc)
            val buy = step.open > 0
            // 22 Oct (arms audit): the US-night silver arm may hold this same future the other way (the paper book nets by symbol).
            heldOtherWay(b, arm, pc.symbol, step.open)?.let { other ->
                val w = "${pc.symbol} is held ${if (other.side > 0) "long" else "short"} by ${label(other.arm)}: no new leg (it would only close that one)"
                note(b, arm, "$leg: $w"); notes += "$leg: $w"; continue
            }
            val bars = runCatching { minutes(fc.upstoxKey, now) }.getOrNull().orEmpty()
            val fresh = bars.lastOrNull()?.let { McxArmRules.fresh(it.epochSecond, nowSec()) } == true
            val refused = refusal(pc, buy = buy, fresh = fresh)
            if (refused != null) { note(b, arm, "$leg: ${step.why} refused: $refused"); notes += "$leg: $refused"; continue }
            // The order flow on this leg's future (9 Oct; paper only): logged; under CONFIRM the opening is skipped unless it agrees.
            val flow = FlowGate.gate(arm, leg, if (buy) 1 else -1, false, "$today ${step.why}", leg, contract = pc)
            if (flow.skip) { note(b, arm, "$leg: ${step.why} ${FlowGate.SKIPPED}"); notes += "$leg: ${FlowGate.SKIPPED}"; continue }
            val (fill, r) = paperOrder(pc, if (buy) "BUY" else "SELL", "${McxTrendRules.LABEL} · ${step.why}")
            if (fill == null) { note(b, arm, "$leg: ${step.why} not filled: ${r.message}"); notes += "$leg: not filled"; continue }
            FlowGate.taken(flow, r.orderId)
            opened(b, Pos(arm, pc.symbol, step.open, fill.quantity, fill.price, today, now, minuteOf(now), fc.expiry, false, r.orderId,
                leg = leg, charges = chargesOf(r.orderId)), "${step.why.removePrefix("trend_")}, $month signal")
        }
        if (notes.isNotEmpty()) {
            val s = notes.joinToString(" · ")
            if (!b.status.sameToday(arm, s)) { b.status[arm] = s; changed = true }
        }
        return changed
    }

    // ---- US-night silver (R2, futures) ------------------------------------------------------------------------------------

    /** What a filled resting stop is called: the silver future's disaster stop (not part of its research), else the option's -15%. */
    private fun stopWhy(p: Pos): String = if (p.arm == McxUsSilverRules.SOURCE) "disaster_stop" else "option_stop"

    /** A filled stop's price when the paper book did not say: the stop's own level. */
    private fun stopFallback(p: Pos): Double =
        if (p.arm == McxUsSilverRules.SOURCE) McxUsSilverRules.disasterLevel(p.entry, p.side) else McxEveRules.optionStop(p.entry)

    /** The silver future's exit at [now]: its disaster stop on any finished minute's high or low since the entry, or the close's 10 minutes. */
    private suspend fun silverExit(p: Pos, c: Paper.Contract, now: LocalDateTime, window: McxSession.Window?): String? {
        val bars = runCatching { minutes(p.futureKey ?: c.feedKey, now) }.getOrNull().orEmpty()
            .map { McxUsSilverRules.Minute(it.istMinute, it.high, it.low) }
        val exitAt = p.flatBy ?: McxUsSilverRules.exitMinute(now.toLocalDate(), window)
        return McxUsSilverRules.exit(p.side, p.entry, p.entryDay, p.entryMinute, bars, now.toLocalDate(), minuteOf(now), exitAt)
    }

    /**
     * 09:05's decision: the US move since the last MCX close (COMEX silver and USD/INR at 08:30 against the close, from
     * five days of [UsCues] bars, so a phone shut overnight still has them), then 1 lot of the near SILVERMIC bought or
     * sold on paper with its disaster stop resting in the paper book. Once a day; every reason it does not trade is logged.
     */
    private suspend fun silver(b: Book, now: LocalDateTime, cal: McxSession.Calendar, window: McxSession.Window?): Boolean {
        val arm = McxUsSilverRules.SOURCE
        val today = now.toLocalDate()
        val key = "$today|silver"
        if (key in b.decided || b.positions.any { it.arm == arm && it.open }) return false
        val minute = minuteOf(now)
        if (minute < McxUsSilverRules.ENTRY) {
            val s = "waiting for 09:05"
            if (!b.status.sameToday(arm, s)) { b.status[arm] = s; return true }
            return false
        }
        val near = McxInstruments.futures(McxMarket.contracts(), McxUsSilverRules.UNDERLYING, today, 2).firstOrNull()
            ?: run { decide(b, arm, key, "silver_no_contract (no SILVERMIC future listed)"); return true }
        // The near contract seen on an earlier day: a different one now means it rolled overnight (the research skips that day).
        val seen = b.marks["silver_near"]?.split('|')?.takeIf { it.size == 2 }
        val previousNear = seen?.takeIf { it[0] != today.toString() }?.let { runCatching { LocalDate.parse(it[1]) }.getOrNull() }
        if (seen == null || seen[0] != today.toString()) b.marks["silver_near"] = "$today|${near.expiry}"
        if (!McxUsSilverRules.entryWindow(minute)) {
            decide(b, arm, key, "silver_late: the watch was not running at 09:05-09:09; no trade today"); return true
        }
        if (McxUsSilverRules.rolled(previousNear, near.expiry)) {
            decide(b, arm, key, "silver_rolled: the near SILVERMIC changed overnight ($previousNear to ${near.expiry}); no trade today"); return true
        }
        val prev = McxUsSilverRules.prevClose(today, cal) ?: run { decide(b, arm, key, "silver_no_previous_session"); return true }
        val ref = McxUsSilverRules.refAt(today)
        val si = UsCues.bars(McxUsSilverRules.US_SYMBOL)
        // 21 Oct: USD/INR from its 5-minute bars, else its 1-hour ones (the research's own; the 5-minute series is empty at night).
        val fx = UsCues.bars(McxUsSilverRules.FX_SYMBOL)
        val fxHourly = UsCues.bars(McxUsSilverRules.FX_SYMBOL, McxUsSilverRules.FX_HOURLY)
        val siNow = McxUsSilverRules.priceAt(si, McxUsSilverRules.epoch(ref))
        val siPrev = McxUsSilverRules.priceAt(si, McxUsSilverRules.epoch(prev))
        val fxNow = McxUsSilverRules.fxAt(fx, fxHourly, McxUsSilverRules.epoch(ref))
        val fxPrev = McxUsSilverRules.fxAt(fx, fxHourly, McxUsSilverRules.epoch(prev))
        val closeAt = hhmm(prev.hour * 60 + prev.minute)
        val sig = McxUsSilverRules.researchSignal(siNow, siPrev, fxNow, fxPrev)
        if (sig == null) {
            val missing = listOfNotNull("COMEX silver at 08:30".takeIf { siNow == null }, "COMEX silver at the $closeAt close".takeIf { siPrev == null })
            val why = if (si.isEmpty()) "the US price feed did not answer" else "not in the feed's history"
            decide(b, arm, key, "silver_no_us_price: ${missing.joinToString(", ").ifEmpty { "a price" }} not read ($why); no trade today"); return true
        }
        val s = sig.first
        // USD/INR not read at all (both feeds): counted as no rupee move, as the research did - said plainly, never silently.
        val fxWords = if (sig.second) "USD/INR not read (${if (fx.isEmpty() && fxHourly.isEmpty()) "the feed did not answer" else "not in the feed's history"}): " +
            "counted as no move, as the research did" else "USD/INR %.4f to %.4f".format(Locale.ENGLISH, fxPrev, fxNow)
        val facts = "s %+.4f: COMEX silver %.3f to %.3f, %s since %s".format(Locale.ENGLISH, s, siPrev, siNow, fxWords,
            prev.toLocalDate().toString() + " " + closeAt)
        val side = McxUsSilverRules.side(s)
        if (side == 0) { decide(b, arm, key, "silver_flat ($facts)"); return true }
        val pc = McxMarket.paperContract(near)
        // 22 Oct (arms audit): the 12-month trend arm may hold this same near SILVERMIC future the other way; the paper book nets
        // by symbol, so this entry would close the trend's leg instead of opening a trade (both were then booked "closed elsewhere",
        // this one at its entry with Rs 0). Held the other way by another arm: no trade today, said plainly.
        heldOtherWay(b, arm, pc.symbol, side)?.let { decide(b, arm, key, "silver_symbol_held_by_${label(it.arm)}: ${pc.symbol} is held ${if (it.side > 0) "long" else "short"} by ${label(it.arm)}; a ${if (side > 0) "buy" else "sell"} would only close its leg ($facts)"); return true }
        val bars = runCatching { minutes(near.upstoxKey, now) }.getOrNull().orEmpty()
        val fresh = bars.lastOrNull()?.let { McxArmRules.fresh(it.epochSecond, nowSec()) } == true
        refusal(pc, buy = side > 0, fresh = fresh)?.let { decide(b, arm, key, "silver_$it ($facts)"); return true }
        val flow = FlowGate.gate(arm, McxUsSilverRules.UNDERLYING, side, false, "$today silver", if (side > 0) "BUY" else "SELL", contract = pc)
        if (flow.skip) { decide(b, arm, key, "silver_${FlowGate.SKIPPED} ($facts)"); return true }
        b.decided += key
        val (fill, r) = paperOrder(pc, if (side > 0) "BUY" else "SELL", "${McxUsSilverRules.LABEL} · entry")
        if (fill == null) { decideLine(b, arm, "silver_not_filled: ${r.message} ($facts)"); return true }
        FlowGate.taken(flow, r.orderId)
        // The disaster stop (not part of the research) rests in the paper book, so it fills on a minute's high or low.
        val level = toTick(McxUsSilverRules.disasterLevel(fill.price, side), near.tick)
        val stop = runCatching { Paper.place(pc, if (side > 0) "SELL" else "BUY", McxArmRules.LOTS, "SL-M", "NRML", null, level) }.getOrNull()
        val stopId = stop?.takeIf { it.ok }?.orderId?.also { runCatching { Strategies.tagOwner("paper:$it", "${McxUsSilverRules.LABEL} · disaster stop") } }
        val exitAt = McxUsSilverRules.exitMinute(today, window)
        opened(b, Pos(arm, pc.symbol, side, fill.quantity, fill.price, today, now, minute, near.expiry, false, r.orderId,
            stopOrderId = stopId, futureKey = near.upstoxKey, flatBy = exitAt, charges = chargesOf(r.orderId)),
            "$facts; out at ${hhmm(exitAt)}; disaster stop %.0f (${McxUsSilverRules.STOP_NOTE})".format(Locale.ENGLISH, level))
        return true
    }

    /** For tests: the book forgotten (its file goes with the test's directory). */
    internal fun wipe() { cache = null; trendTried.clear(); _view.value = View(emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList()) }
}
