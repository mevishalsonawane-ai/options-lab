package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Ft3Rules
import com.optionslab.engine.sandbox.SandboxEvent
import kotlinx.coroutines.async
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
 * "FT3 near-ATM morning" (21 Oct 2026, Boss: "Add FT3 to the app paper bot until live, when live is started then it should move
 * to live"), the rules in [Ft3Rules]: on each of the five indices' own expiry days, at the first 5-minute mark 11:00-12:55 with an
 * OTM option at Rs 6-10, IV at most 1.0333x the ATM's and within 1.5466 straddles of spot, Rs 5,000 of it in whole lots; out at
 * 15:05. BOSS'S CHOICE - IT DID NOT PASS RESEARCH (R98e: SPA p 0.96): said on its row, in its notices and by Jarvis. Its paper
 * record is the R98 forward test FT3 (research/hunt/r98/FORWARD_TEST.md).
 *
 * PAPER here, always: every order in this file is the paper account's ([Paper.place]); there is no Zerodha call anywhere here.
 * Its live path is the app's standard one, [ArmLive], told of each paper buy and close (memory only): that live trade goes only
 * in Live (Boss's PIN) with "AI trades go live" on, its own "Trade live" switch on, its Shadow off (the PIN), outside the
 * paper-only period and past every guard; it rests only a labelled -80% disaster stop at Zerodha (the paper rule has no stop).
 *
 * Its book is its own encrypted file (not in the backup), so its trades are a record of their own. New tickets obey the kill
 * switch, the bot's stop for today (the daily loss limit included), the account day lock and the account guard; fills and
 * charges are the honest paper ones. Exits always go; the expiry square-off (15:05) or Boss may close a ticket first (then it is
 * booked as closed there).
 *
 * Switched ON once by the 21 Oct update (Boss's own choice, logged in Autopilot's change log as theirs, undone in one tap); after
 * that only Boss switches it. The chain reads run in the watch's arms passes, outside the arm's lock ([prefetch]); while a
 * ticket is open its exit runs in the watch's minute lane, and after a restart the book is read first ([PositionsFirst]).
 */
object Ft3Arm {
    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, "ft3.vault")
        // Its paper tickets, for their live path ([ArmLive], the standard one).
        ArmLive.register(com.optionslab.engine.orb.LiveMirror.FT3) {
            if (cache == null) null else _view.value.let { v -> (v.open + v.today.filter { !it.open }).map { ArmLive.PaperLeg(mirrorRef(it), Ft3Rules.SOURCE, if (it.open) it.qty else 0) } }
        }
    }

    /** A ticket's reference for its live path ([ArmLive]): its paper order (or its entry time). */
    private fun mirrorRef(p: Pos): String = "ft3:${p.index}:${p.orderId ?: p.entryTime}"

    /** The one-time switch-on of 21 Oct (Boss's choice); in [Book.migrated] once done, never again. */
    internal const val AUTO_ON = "boss_on_2026-10-21"

    /**
     * TEST SEAM: true in the JVM tests' app, so the other tests' books start with this arm off (its own tests turn it back).
     * False in the app, always: its setter throws in a release build.
     */
    @Volatile internal var testNoAutoOn: Boolean = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /**
     * One paper ticket: the [mark] whose bar gave it, the option's price there ([signalPx]), its distance in straddles ([distEm])
     * and IV ratio ([ivRatio]) as the rule read them.
     */
    data class Pos(
        val index: String, val symbol: String, val right: String, val qty: Int, val entry: Double, val entryTime: LocalDateTime,
        val orderId: String?, val mark: String, val strike: Double, val signalPx: Double, val distEm: Double, val ivRatio: Double,
        val exit: Double? = null, val exitTime: LocalDateTime? = null, val why: String? = null, val charges: Double = 0.0,
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        /** Rupees after charges, once sold. */
        val net: Double? get() = exit?.let { (it - entry) * qty - charges }
        val cost: Double get() = entry * qty
    }

    /**
     * One index-day's outcome for the forward test (review: refused, unfilled and missed signals are counted, not only
     * the bought ones): [outcome] bought / missed / refused / unfilled / skipped / none; the mark and pick when there was one.
     */
    data class Signal(val day: LocalDate, val index: String, val outcome: String, val mark: String? = null, val strike: Double? = null,
                      val right: String? = null, val price: Double? = null, val distEm: Double? = null, val ivRatio: Double? = null,
                      val note: String = "")

    /** The day's pick, kept until it is bought or given up: its mark and the rule's [Ft3Rules.Pick]. */
    private data class Saved(val mark: LocalTime, val pick: Ft3Rules.Pick) {
        fun encode(): String = "$mark|${pick.strike}|${pick.right}|${pick.price}|${pick.distEm}|${pick.ivRatio}"
        companion object {
            fun decode(s: String): Saved? = runCatching {
                val f = s.split("|")
                Saved(LocalTime.parse(f[0]), Ft3Rules.Pick(f[1].toDouble(), f[2] == "CE", f[3].toDouble(), f[4].toDouble(), f[5].toDouble()))
            }.getOrNull()
        }
    }

    private class Book(
        var armed: Boolean = false,
        val positions: MutableList<Pos> = ArrayList(),
        /** "day|index": the index's day is decided (not an expiry day, its ticket bought, missed or given up). */
        val decided: MutableSet<String> = HashSet(),
        /** "day|index" -> the last mark judged with no ticket ("11:35"). */
        val checked: MutableMap<String, String> = HashMap(),
        /** "day|index" -> the day's pick ([Saved.encode]) until it is bought or given up. */
        val picks: MutableMap<String, String> = HashMap(),
        val status: StatusMap = StatusMap(),
        val log: ArrayList<String> = ArrayList(),
        val migrated: MutableSet<String> = HashSet(),
        /** "day|index" -> an entry order a cancel could not take out (settled on the next pass, never a second BUY beside it). */
        val pending: MutableMap<String, String> = HashMap(),
        /** Each open ticket's last price seen (memory only). */
        val marks: MutableMap<String, Double> = HashMap(),
        /** Every index-day's outcome (the forward test's signal record), oldest first. */
        val signals: MutableList<Signal> = ArrayList(),
    )

    /** What its row and Jarvis read: [today] today's tickets (open and closed), [nets] every closed ticket's rupees, oldest first. */
    data class View(val armed: Boolean, val status: Map<String, String>, val open: List<Pos>, val closed: List<Pos>, val today: List<Pos>,
                    val log: List<String>, val nets: List<Double> = emptyList(), val marks: Map<String, Double> = emptyMap(),
                    /** The forward test's signal record (the last [SIGNALS_SHOWN] index-days). */
                    val signals: List<Signal> = emptyList()) {
        /** Today's running P&L: closed tickets after charges, open ones at their last price before charges. */
        val todayPnl: Double get() = today.sumOf { p -> p.net ?: marks[p.symbol]?.let { (it - p.entry) * p.qty } ?: 0.0 }
        /** Closed tickets that made money (after charges). */
        val won: Int get() = nets.count { it > 0 }
    }

    private fun empty() = View(false, emptyMap(), emptyList(), emptyList(), emptyList(), emptyList())
    private val _view = MutableStateFlow(empty())
    val view: StateFlow<View> = _view

    @Volatile private var cache: Book? = null
    private const val LOG_KEPT = 200
    private const val CLOSED_KEPT = 1_000
    private const val SIGNALS_KEPT = 2_000
    private const val SIGNALS_SHOWN = 60
    /** Its own notices (bought, sold): 7600 + the index. */
    private const val NOTICE = 7600

    private fun ready() = ::file.isInitialized

    private fun book(): Book {
        cache?.let { return it }
        val b = Book()
        var unreadable = false
        runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            b.armed = o.optBoolean("armed", false)
            o.optJSONArray("positions")?.let { a -> for (i in 0 until a.length()) b.positions += readPos(a.getJSONObject(i)) }
            o.optJSONArray("decided")?.let { a -> for (i in 0 until a.length()) b.decided += a.getString(i) }
            o.optJSONObject("checked")?.let { m -> m.keys().forEach { b.checked[it] = m.getString(it) } }
            o.optJSONObject("picks")?.let { m -> m.keys().forEach { b.picks[it] = m.getString(it) } }
            b.status.readFrom(o, runCatching { file.lastModified() }.getOrDefault(0L))
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
            o.optJSONArray("migrated")?.let { a -> for (i in 0 until a.length()) b.migrated += a.getString(i) }
            o.optJSONObject("pending")?.let { m -> m.keys().forEach { b.pending[it] = m.getString(it) } }
            o.optJSONArray("signals")?.let { a -> for (i in 0 until a.length()) readSignal(a.getJSONObject(i))?.let { b.signals += it } }
        }.onFailure { unreadable = true; if (file.exists()) Vault.setAside(file) }
        cache = b
        // 21 Oct, Boss's choice: on once, on paper. A book that could not be read is never switched on by itself (Boss may have
        // switched it off): it is only marked done. Never in IraGoldAlgo.
        if (AUTO_ON !in b.migrated) {
            b.migrated += AUTO_ON
            val on = !unreadable && !testNoAutoOn && !com.optionslab.app.BuildConfig.GOLD
            if (on && !b.armed) {
                b.armed = true
                note(b, "switched on once by the 21 Oct update, on your choice - on paper; ${Ft3Rules.BOSS_CHOICE}")
                runCatching { com.optionslab.app.ira.Autopilot.logFt3On() }
            }
            save(b)
        } else publish(b)
        return b
    }

    private fun readPos(p: JSONObject): Pos = Pos(
        p.getString("index"), p.getString("symbol"), p.getString("right"), p.getInt("qty"), p.getDouble("entry"),
        LocalDateTime.parse(p.getString("entryTime")), p.optString("orderId").ifEmpty { null }, p.optString("mark"),
        p.optDouble("strike", 0.0), p.optDouble("signalPx", 0.0), p.optDouble("distEm", 0.0), p.optDouble("ivRatio", 0.0),
        if (p.has("exit")) p.getDouble("exit") else null, p.optString("exitTime").ifEmpty { null }?.let { LocalDateTime.parse(it) },
        p.optString("why").ifEmpty { null }, p.optDouble("charges", 0.0),
    )

    private fun readSignal(o: JSONObject): Signal? = runCatching {
        fun d(k: String) = if (o.has(k)) o.getDouble(k) else null
        Signal(LocalDate.parse(o.getString("day")), o.getString("index"), o.getString("outcome"), o.optString("mark").ifEmpty { null },
            d("strike"), o.optString("right").ifEmpty { null }, d("price"), d("distEm"), d("ivRatio"), o.optString("note"))
    }.getOrNull()

    private fun writeSignal(x: Signal): JSONObject = JSONObject().put("day", x.day.toString()).put("index", x.index).put("outcome", x.outcome)
        .put("note", x.note).apply {
            x.mark?.let { put("mark", it) }; x.strike?.let { put("strike", it) }; x.right?.let { put("right", it) }
            x.price?.let { put("price", it) }; x.distEm?.let { put("distEm", it) }; x.ivRatio?.let { put("ivRatio", it) }
        }

    private fun writePos(p: Pos): JSONObject = JSONObject().put("index", p.index).put("symbol", p.symbol).put("right", p.right).put("qty", p.qty)
        .put("entry", p.entry).put("entryTime", p.entryTime.toString()).put("orderId", p.orderId ?: "").put("mark", p.mark).put("strike", p.strike)
        .put("signalPx", p.signalPx).put("distEm", p.distEm).put("ivRatio", p.ivRatio).put("charges", p.charges)
        .apply { p.exit?.let { put("exit", it) }; p.exitTime?.let { put("exitTime", it.toString()) }; p.why?.let { put("why", it) } }

    /** What this process last wrote to the book's file: an unchanged save is not encrypted and synced again. */
    private val written = Vault.LastWrite()

    private fun save(b: Book) {
        val o = JSONObject().put("armed", b.armed)
        o.put("positions", JSONArray().apply { b.positions.forEach { put(writePos(it)) } })
        o.put("decided", JSONArray().apply { b.decided.sorted().takeLast(60).forEach { put(it) } })
        // Only the last 10 days' marks and picks are kept (a day's are of no use after it).
        val recent = { k: String -> runCatching { !LocalDate.parse(k.substringBefore('|')).isBefore(Market.today().minusDays(10)) }.getOrDefault(true) }
        o.put("checked", JSONObject().apply { b.checked.filterKeys { recent(it) }.forEach { (k, v) -> put(k, v) } })
        o.put("picks", JSONObject().apply { b.picks.filterKeys { recent(it) }.forEach { (k, v) -> put(k, v) } })
        o.put("status", JSONObject().apply { b.status.forEach { (k, v) -> put(k, v) } })
        b.status.writeTo(o)
        o.put("log", JSONArray().apply { b.log.takeLast(LOG_KEPT).forEach { put(it) } })
        o.put("migrated", JSONArray().apply { b.migrated.sorted().forEach { put(it) } })
        o.put("pending", JSONObject().apply { b.pending.forEach { (k, v) -> put(k, v) } })
        o.put("signals", JSONArray().apply { b.signals.takeLast(SIGNALS_KEPT).forEach { put(writeSignal(it)) } })
        runCatching { written.write(file, o.toString().toByteArray(Charsets.UTF_8)) }
        publish(b)
    }

    private fun publish(b: Book) {
        decidedNow = b.decided.toSet()
        checkedNow = b.checked.toMap()
        picksNow = b.picks.keys.toSet()
        pendingNow = b.pending.isNotEmpty()
        val closed = b.positions.filter { !it.open }
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        _view.value = View(b.armed, b.status.shown(), b.positions.filter { it.open }, closed.takeLast(20),
            b.positions.filter { it.day == today }, b.log.takeLast(30), closed.map { it.net ?: 0.0 }, b.marks.toMap(), b.signals.takeLast(SIGNALS_SHOWN))
    }

    private fun note(b: Book, text: String) {
        b.log += "${runCatching { Market.now().toLocalDateTime().withNano(0) }.getOrElse { LocalDateTime.now().withNano(0) }} $text"
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        runCatching { Diag.record("ft3", "${Ft3Rules.LABEL}: $text") }
    }

    /** Read the book (the row and the start-up's positions-first step call it, off the main thread). */
    suspend fun refresh(): View { if (ready()) lock.withLock { book() }; return _view.value }

    /** An open ticket is held (memory only: the book as last read). */
    fun holding(): Boolean = _view.value.open.isNotEmpty()

    /** The watch wakes it from here (the day's lists made ready before the 11:00 mark's bar closes) .. */
    private const val WAKE_FROM = 10 * 60 + 55
    /** .. to here (from 12:59 the last mark's order window has gone and the day is decided; a few minutes' margin for a late pass). */
    private const val WAKE_UNTIL = 13 * 60 + 6

    /**
     * The watch needs this arm's pass now (memory only: no file, no network): a ticket is held, or it is switched on, the time is
     * 10:55 .. 13:05 and some index's day is not decided yet. Outside that it costs the watch nothing.
     */
    fun wantsPass(minute: Int = runCatching { Market.minuteNow() }.getOrDefault(0)): Boolean {
        // 22 Oct (review): an entry order a cancel could not take out keeps the pass coming until it is settled (it may fill late).
        if (holding() || pendingNow) return true
        if (!_view.value.armed || minute !in WAKE_FROM until WAKE_UNTIL) return false
        val today = runCatching { Market.today() }.getOrNull() ?: return true
        val decided = decidedNow
        return Ft3Rules.UNDERLYINGS.any { "$today|$it" !in decided }
    }

    /**
     * Switch it on or off - on paper. Off sells nothing it holds: its 15:05 exit still closes it. [log]: Boss's flip from the row
     * (logged in Autopilot's change log); false for the change log's own Undo.
     */
    suspend fun setArmed(on: Boolean, log: Boolean = true): String {
        if (!ready()) return "${Ft3Rules.LABEL} is not ready yet."
        return lock.withLock {
            val b = book()
            if (b.armed == on) return@withLock "${Ft3Rules.LABEL} is already ${if (on) "on" else "off"} (on paper)."
            b.armed = on
            note(b, if (on) "switched on, on paper (${Ft3Rules.BOSS_CHOICE})" else "switched off" +
                if (b.positions.any { it.open }) "; what it holds still sells at 15:05" else "")
            save(b)
            if (log) runCatching { com.optionslab.app.ira.Autopilot.ft3SwitchedByBoss(on) }
            if (on) "${Ft3Rules.LABEL} on, on paper. ${Ft3Rules.BOSS_CHOICE}." else "${Ft3Rules.LABEL} off."
        }
    }

    /**
     * The diagnostics line. [hideNet] (the uploaded day file): its rupee record written [PassedArmsHost.NET_HIDDEN] - the upload never
     * holds a P&L figure (as the passed arms' line, 20 Oct).
     */
    fun diagLine(hideNet: Boolean = false): String {
        val v = _view.value
        val net = if (hideNet) PassedArmsHost.NET_HIDDEN else "%,.0f".format(Locale.ENGLISH, v.nets.sum())
        return "${Ft3Rules.LABEL}: ${if (v.armed) "on (paper)" else "off"} · open ${v.open.size} · ${v.nets.size} closed $net after charges" +
            v.status.toSortedMap().entries.joinToString("") { (k, s) -> " · $k $s" } + " · ${Ft3Rules.BOSS_CHOICE}"
    }

    // ---- the pass ---------------------------------------------------------------------------------------------------

    /** The fast path's minute lane (open positions first): only the open tickets' exits - never the chain reads. */
    suspend fun manageOnly() {
        if (!ready() || com.optionslab.app.BuildConfig.GOLD || !holding() || !Market.isTradingDay()) return
        lock.withLock {
            val b = book()
            val now = Market.now().toLocalDateTime().withNano(0)
            val changed = runCatching { exits(b, now) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true }
            if (changed) save(b) else publish(b)
        }
    }

    /**
     * Called by the market watch's arms passes: the open tickets' exits first, then each index's marks. The chain reads happen
     * BEFORE the arm's lock is taken ([prefetch]), so the minute lane's exits ([manageOnly]) never wait behind them.
     */
    suspend fun tick() {
        if (!ready() || com.optionslab.app.BuildConfig.GOLD || !Market.isTradingDay()) return
        val now = Market.now().toLocalDateTime().withNano(0)
        if (cache == null) lock.withLock { book() }
        val reads = if (_view.value.armed && Ft3Rules.phase(now.toLocalTime()) != Ft3Rules.Phase.BEFORE)
            runCatching { prefetch(now) }.getOrDefault(emptyMap()) else emptyMap()
        // 22 Oct (review): the row's price for a ticket the stream has no price for, read here, outside the lock, so the minute
        // lane's and the 15-second check's exits ([manageOnly]) never wait behind a network read.
        val barMarks = runCatching { barMarks(now) }.getOrDefault(emptyMap())
        lock.withLock {
            // The clock read again after the reads (review: they can take tens of seconds; the order window and the entry
            // time are judged on the minute the decision is made).
            val now = Market.now().toLocalDateTime().withNano(0)
            val b = book()
            // An earlier day's unsettled entry order is the paper book's (its MIS square-off ended it): forgotten (before the
            // switched-off return, so a forgotten one never keeps the pass coming - 22 Oct).
            var changed = b.pending.keys.removeAll { !it.startsWith("${now.toLocalDate()}|") }
            if (!b.armed && b.positions.none { it.open }) { if (changed) save(b); return@withLock }
            changed = runCatching { exits(b, now, barMarks) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true } || changed
            if (b.armed) for (u in Ft3Rules.UNDERLYINGS) {
                changed = runCatching { decide(b, u, now, reads[u]) }.getOrElse { b.status[u] = "error: ${it.javaClass.simpleName}"; true } || changed
            }
            val closed = b.positions.count { !it.open }
            if (closed > CLOSED_KEPT) {
                var drop = closed - CLOSED_KEPT
                b.positions.removeAll { !it.open && drop-- > 0 }
                changed = true
            }
            if (changed) save(b) else publish(b)
        }
    }

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun px(x: Double) = "%.2f".format(Locale.ENGLISH, x)
    private fun hm(t: LocalTime) = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    private fun chargesOf(orderId: String?): Double =
        if (orderId == null) 0.0 else Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    /** The index's feed key (the four NSE indices, SENSEX on BSE). */
    internal fun indexKey(u: String): String? = when (u) {
        "SENSEX" -> "BSE_INDEX|SENSEX"
        "FINNIFTY" -> "NSE_INDEX|Nifty Fin Service"
        "MIDCPNIFTY" -> "NSE_INDEX|NIFTY MID SELECT"
        else -> Upstox.INDEX_KEYS[u]
    }

    /** Today's finished 1-minute bars of [key], keyed by the minute each CLOSES (the feed's, with the stream's newer whole minutes). */
    private suspend fun closes(key: String, now: LocalDateTime): Map<LocalTime, Upstox.Bar> {
        val nowSec = now.atZone(com.optionslab.engine.IST).toEpochSecond()
        // The quick read (5 s connect, 10 s read, one retry) - never the full read's minutes of retries in the watch's lane.
        val feed = Net.intradayQuick(key).filter { it.istDate == now.toLocalDate() && it.epochSecond + 60 <= nowSec }.sortedBy { it.epochSecond }
        val bars = runCatching { LocalCandles.overlay(key, feed, nowSec) }.getOrDefault(feed)
            .filter { it.istDate == now.toLocalDate() && it.epochSecond + 60 <= nowSec }
        return bars.associateBy { b -> (b.istMinute + 1).let { LocalTime.of(it / 60 % 24, it % 60) } }
    }

    /**
     * The index's listed options (the four NSE indices: the NSE list; SENSEX: BSE's) from the lists saved on the phone today.
     * Never a download in the pass: a list not yet saved today is fetched in the background and the pass waits (empty).
     */
    private fun listed(u: String): List<Upstox.Contract> {
        val bfo = u == Paper.BFO_UNDERLYING
        val saved = runCatching { if (bfo) Market.cachedBfoContracts() else Market.cachedContracts() }.getOrNull()
        if (saved == null || saved.first != Market.today()) {
            runCatching { if (bfo) Market.refreshBfoInBackground() else Market.refreshContractsInBackground() }
            return emptyList()
        }
        return saved.second.filter { it.underlying == u }
    }

    /** Whether the bot is stopped for today (Boss's stop, or the daily loss limit). Unknown counts as stopped. */
    private suspend fun stoppedToday(): Boolean =
        runCatching { Strategies.stoppedToday() }.getOrDefault(true) || runCatching { LossBreaker.trippedToday() }.getOrDefault(false)

    /** Sets [u]'s status; true when it changed (the book is then saved). [final] also logs it once. */
    private fun say(b: Book, u: String, why: String, final: Boolean = false): Boolean {
        if (final) note(b, "$u: $why")
        if (b.status.sameToday(u, why)) return final
        b.status[u] = why
        return true
    }

    /** Strikes read side by side. */
    private const val CHAIN_GROUP = 6

    /** The latest close in [bars] keyed [at] or up to [fresh] minutes before it (R98's forward fill); none: null. */
    internal fun lastClose(bars: Map<LocalTime, Upstox.Bar>, at: LocalTime, fresh: Long): Double? =
        bars.filterKeys { !it.isAfter(at) && !it.isBefore(at.minusMinutes(fresh)) }.maxByOrNull { it.key }?.value?.close?.takeIf { it > 0 }

    /**
     * One index's reads for the marks still to judge: each mark's chain (null: not judged this pass - a read failed, or the
     * feed has not reached the mark's minute yet ([waiting]); judged again on the next pass).
     */
    private class IndexRead(val chains: Map<LocalTime, Ft3Rules.Chain?>, val waiting: Set<LocalTime> = emptySet(),
                            /**
                             * 22 Oct (review): the marks with no index price although the index's feed has gone past them (a gap of
                             * more than [Ft3Rules.SPOT_FRESH] minutes): no ticket at that mark, and the later marks are judged (as the
                             * research replay, which skips a mark with no spot) - never every later mark blocked behind it.
                             */
                            val noSpot: Set<LocalTime> = emptySet())

    /**
     * The candle feed lags its newest minute by up to this many minutes ([com.optionslab.engine.orb.BarClose]; options are not
     * on the stream): an option's feed with no bar at or after the mark's minute is waited for this long before its silence is
     * read as "no trade" (review: a mark judged early, without its own bar, was never judged again).
     */
    private const val FEED_LAG = 2L

    /** Memory copies kept by [publish] for the reads outside the lock. */
    @Volatile private var decidedNow: Set<String> = emptySet()
    @Volatile private var checkedNow: Map<String, String> = emptyMap()
    @Volatile private var picksNow: Set<String> = emptySet()
    @Volatile private var pendingNow: Boolean = false

    /**
     * The reads, outside the lock: for each index whose expiry is today and whose day is still open with no pick yet, the marks
     * closed since the last one judged - the index's bars (spot at each mark), the ATM call and put at each mark's ATM strike,
     * then every OTM strike within 1.5466 straddles of spot at any of those marks. One read per option gives the whole day's
     * bars, so each is read once a pass, in groups of [CHAIN_GROUP] side by side.
     */
    private suspend fun prefetch(now: LocalDateTime): Map<String, IndexRead> {
        val today = now.toLocalDate()
        val t = now.toLocalTime()
        // A feed has reached the mark's minute (a bar at or after it), or the lag allowance has passed.
        fun caughtUp(b: Map<LocalTime, Upstox.Bar>, at: LocalTime, lag: Long) = b.keys.any { !it.isBefore(at) } || !t.isBefore(at.plusMinutes(lag))
        val out = HashMap<String, IndexRead>()
        for (u in Ft3Rules.UNDERLYINGS) {
            val dayKey = "$today|$u"
            if (dayKey in decidedNow || dayKey in picksNow) continue
            val list = listed(u)
            if (!Ft3Rules.tradesToday(today, true, list.map { it.expiry }.distinct())) continue
            val after = checkedNow[dayKey]?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            val marks = Ft3Rules.marksClosedBy(t).filter { after == null || it.isAfter(after) }
            if (marks.isEmpty()) continue
            val ik = indexKey(u) ?: continue
            val idx = runCatching { closes(ik, now) }.getOrNull()
            if (idx == null) { out[u] = IndexRead(marks.associateWith { null }); continue }
            val todays = list.filter { it.expiry == today }.associateBy { it.strike to it.right }
            val bars = HashMap<String, Map<LocalTime, Upstox.Bar>?>()
            suspend fun read(cs: Collection<Upstox.Contract>) {
                for (group in cs.filter { it.instrumentKey !in bars }.distinctBy { it.instrumentKey }.chunked(CHAIN_GROUP)) {
                    val got = kotlinx.coroutines.coroutineScope {
                        group.map { o -> async { o.instrumentKey to runCatching { closes(o.instrumentKey, now) }.getOrNull() } }.map { it.await() }
                    }
                    got.forEach { (k, v) -> bars[k] = v }
                }
            }
            val spots = marks.associateWith { m -> lastClose(idx, Ft3Rules.barClose(m), Ft3Rules.SPOT_FRESH) }
            fun atmOf(m: LocalTime) = spots[m]?.let { Ft3Rules.atmStrike(it, u) }
            read(marks.mapNotNull { atmOf(it) }.distinct().flatMap { k -> listOfNotNull(todays[k to Right.CE], todays[k to Right.PE]) })
            // Each mark's straddle, then the strikes in its band (OTM side only).
            val bands = HashMap<LocalTime, List<Upstox.Contract>>()
            val atms = HashMap<LocalTime, Pair<Double?, Double?>>()
            val incomplete = HashSet<LocalTime>()
            val waiting = HashSet<LocalTime>()
            val noSpot = HashSet<LocalTime>()
            for (m in marks) {
                val at = Ft3Rules.barClose(m)
                // The index's own bar of the mark first (its forward fill only once that minute is long gone).
                if (!idx.containsKey(at) && t.isBefore(at.plusMinutes(Ft3Rules.SPOT_FRESH))) { waiting += m; continue }
                val s = spots[m]
                if (s == null) { if (idx.keys.any { !it.isBefore(at) }) noSpot += m; continue }
                val k = atmOf(m)!!
                val ce = todays[k to Right.CE]; val pe = todays[k to Right.PE]
                if ((ce != null && bars[ce.instrumentKey] == null) || (pe != null && bars[pe.instrumentKey] == null)) { incomplete += m; continue }
                if (listOfNotNull(ce, pe).any { !caughtUp(bars.getValue(it.instrumentKey).orEmpty(), at, FEED_LAG) }) { waiting += m; continue }
                val c = ce?.let { bars[it.instrumentKey]?.let { b -> lastClose(b, at, Ft3Rules.ATM_FRESH) } }
                val p = pe?.let { bars[it.instrumentKey]?.let { b -> lastClose(b, at, Ft3Rules.ATM_FRESH) } }
                atms[m] = c to p
                val em = Ft3Rules.straddle(c, p) ?: continue
                val inBand = Ft3Rules.band(s, em, todays.values.map { it.strike }).toSet()
                bands[m] = todays.values.filter { o -> o.strike in inBand && Ft3Rules.otm(o.strike, o.right == Right.CE, s) }
            }
            read(bands.values.flatten())
            val chains = LinkedHashMap<LocalTime, Ft3Rules.Chain?>()
            for (m in marks) {
                val s = spots[m]
                if (s == null || m in incomplete || m in waiting) { chains[m] = null; continue }
                val (c, p) = atms[m] ?: (null to null)
                val band = bands[m].orEmpty()
                if (band.any { bars[it.instrumentKey] == null }) { chains[m] = null; continue }
                val at = Ft3Rules.barClose(m)
                if (band.any { !caughtUp(bars.getValue(it.instrumentKey).orEmpty(), at, FEED_LAG) }) { waiting += m; chains[m] = null; continue }
                val legs = band.map { o ->
                    val b = bars.getValue(o.instrumentKey).orEmpty()
                    Ft3Rules.Leg(o.strike, o.right == Right.CE, b[at]?.close?.takeIf { it > 0 },
                        b.any { (k, bar) -> bar.volume > 0 && !k.isAfter(at) && !k.isBefore(at.minusMinutes(Ft3Rules.TRADED_BARS - 1)) })
                }
                chains[m] = Ft3Rules.Chain(u, s, c, p, legs)
            }
            out[u] = IndexRead(chains, waiting, noSpot)
        }
        return out
    }

    /** [u]'s marks at [now] from [read] (the reads made outside the lock). True when the book changed. */
    private suspend fun decide(b: Book, u: String, now: LocalDateTime, read: IndexRead?): Boolean {
        val today = now.toLocalDate()
        val dayKey = "$today|$u"
        if (dayKey in b.decided) return false
        val list = listed(u)
        // No list yet (not downloaded, or offline): asked again on the next pass, never the day given up.
        if (list.isEmpty()) return say(b, u, "waiting for the options list")
        if (!Ft3Rules.tradesToday(today, true, list.map { it.expiry }.distinct())) {
            b.decided += dayKey
            return say(b, u, "not its expiry day", final = true)
        }
        // An entry order a cancel could not take out (a hiccup): settled first - adopted when it filled, else retried.
        var changed = false
        b.pending[dayKey]?.let { id -> changed = settlePending(b, u, dayKey, id, now) || changed }
        if (dayKey in b.decided) return true
        if (b.pending.containsKey(dayKey)) return say(b, u, "an entry order is still working; settled before anything new") || changed
        val t = now.toLocalTime()
        val phase = Ft3Rules.phase(t)
        if (phase == Ft3Rules.Phase.BEFORE) return say(b, u, "its expiry day: looks at each 5-minute mark 11:00-12:55") || changed
        // The day's pick already found: bought while its window is open, else that day's ticket is gone (never a later mark's).
        b.picks[dayKey]?.let { Saved.decode(it) }?.let { s ->
            if (Ft3Rules.entryOpen(s.mark, t)) return buy(b, u, dayKey, s, now) || changed
            val last = b.status[u].orEmpty()
            return done(b, u, dayKey, "no ticket: the ${hm(s.mark)} signal (${fmtStrike(s.pick)} @ ${px(s.pick.price)}) was not bought by " +
                "${hm(Ft3Rules.barClose(s.mark).plusMinutes(Ft3Rules.ENTRY_WINDOW))}" + if (last.startsWith("limit")) " ($last)" else "", "unfilled", s)
        }
        val r = read
        if (r != null) for ((m, chain) in r.chains.toSortedMap()) {
            // A mark with no index price though the feed has passed it: no ticket there, the next mark is judged (22 Oct review).
            if (chain == null && m in r.noSpot) {
                b.checked[dayKey] = m.toString(); changed = true
                note(b, "$u: no index price at the ${hm(m)} mark (a gap in the feed): no ticket there, as the research replay")
                continue
            }
            // A mark whose chain could not be read is judged again on the next pass (and the later ones wait behind it).
            if (chain == null) break
            val pick = Ft3Rules.pick(m, chain)
            if (pick == null) { b.checked[dayKey] = m.toString(); changed = true; continue }
            val s = Saved(m, pick)
            b.picks[dayKey] = s.encode()
            note(b, "$u: the ${hm(m)} mark met the rule: ${fmtStrike(pick)} @ ${px(pick.price)}, %.2f straddles, IV ratio %.3f".format(Locale.ENGLISH, pick.distEm, pick.ivRatio))
            if (Ft3Rules.entryOpen(m, t)) return buy(b, u, dayKey, s, now) || true
            // The watch was not in time for it (the phone asleep, the app started late): the rule's ticket that day is gone.
            return done(b, u, dayKey, "no ticket: the ${hm(m)} signal (${fmtStrike(pick)} @ ${px(pick.price)}) came while the watch was not " +
                "in time to buy it", "missed", s)
        }
        val last = b.checked[dayKey]?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        if (phase == Ft3Rules.Phase.LATE) {
            return if (last == Ft3Rules.LAST_MARK) done(b, u, dayKey, "no ticket today: no option met the rule at any mark 11:00-12:55", "none")
            else done(b, u, dayKey, "no ticket today: the chain could not be read for the marks after ${last?.let { hm(it) } ?: "10:55"}", "unread")
        }
        val firstOpen = r?.chains?.entries?.filter { it.value == null }?.minByOrNull { it.key }?.key
        val unread = firstOpen != null || (r == null && Ft3Rules.marksClosedBy(t).any { last == null || it.isAfter(last) })
        return say(b, u, when {
            firstOpen != null && firstOpen in r!!.waiting -> "waiting for the ${hm(firstOpen)} minute's prices from the feed"
            unread -> "the chain could not be read just now; trying again"
            else -> "looking: nothing met the rule up to ${last?.let { hm(it) } ?: "the first mark"}"
        }) || changed
    }

    private fun fmtStrike(p: Ft3Rules.Pick): String = "${"%.0f".format(Locale.ENGLISH, p.strike)} ${p.right}"

    /**
     * [u]'s day decided with [why] (logged once): no ticket, its pick forgotten, and the index-day's [outcome] kept in the signal
     * record (with the pick [s] when there was one). Always true (the book changed).
     */
    private fun done(b: Book, u: String, dayKey: String, why: String, outcome: String = "refused", s: Saved? = null): Boolean {
        b.decided += dayKey
        val sv = s ?: b.picks[dayKey]?.let { Saved.decode(it) }
        b.picks.remove(dayKey)
        record(b, dayKey, u, outcome, sv, why)
        say(b, u, why, final = true)
        return true
    }

    /** One index-day's outcome into the signal record (the forward test counts refused, unfilled and missed signals too). */
    private fun record(b: Book, dayKey: String, u: String, outcome: String, s: Saved?, note: String) {
        val day = runCatching { LocalDate.parse(dayKey.substringBefore('|')) }.getOrElse { return }
        b.signals += Signal(day, u, outcome, s?.mark?.let { hm(it) }, s?.pick?.strike, s?.pick?.right, s?.pick?.price, s?.pick?.distEm,
            s?.pick?.ivRatio, note)
        while (b.signals.size > SIGNALS_KEPT) b.signals.removeAt(0)
    }

    /** The ticket for the saved pick [s]: a LIMIT BUY of Rs 5,000 of whole lots on the PAPER account. True when the book changed. */
    private suspend fun buy(b: Book, u: String, dayKey: String, s: Saved, now: LocalDateTime): Boolean {
        val today = now.toLocalDate()
        val right = if (s.pick.call) Right.CE else Right.PE
        val c = Paper.ticketContract(u, today, s.pick.strike, right) ?: return done(b, u, dayKey, "skipped: ${fmtStrike(s.pick)} not in the options list", "skipped", s)
        if (c.lotSize <= 0) return done(b, u, dayKey, "skipped: lot size unknown", "skipped", s)
        // The kill switch, the stop for today, the day lock and the guard: nothing bypasses them (a refusal is that index's day).
        if (runCatching { AppSettings.load().guardKill }.getOrDefault(true)) return done(b, u, dayKey, "refused: the kill switch is on")
        if (stoppedToday()) return done(b, u, dayKey, "refused: the bot is stopped for today")
        runCatching { DayLockGuard.refusal(false) }.getOrNull()?.let { return done(b, u, dayKey, "refused: $it") }
        val q = runCatching { Paper.quote(c) }.getOrNull()
        val ask = q?.ask?.takeIf { it > 0 } ?: q?.ltp?.takeIf { it > 0 } ?: s.pick.price
        val limit = Ft3Rules.limitPrice(ask, s.pick.price)
        val lots = Ft3Rules.lots(limit, c.lotSize)
        if (lots <= 0) return done(b, u, dayKey, "skipped: one lot of ${c.lotSize} at ${px(limit)} costs more than Rs 5,000", "skipped", s)
        val snap = runCatching { Paper.snapshot() }.getOrNull()
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", lots, limit), snap?.let { Guard.paperAccount(it) }, paper = true)
        if (refusals.isNotEmpty()) return done(b, u, dayKey, "refused by the account guard: " + refusals.joinToString(" "))
        runCatching { LiveRecorder.signal(Ft3Rules.LABEL, c.symbol, u, right.name, limit, paper = true) }
        // A LIMIT BUY on the PAPER account; not filled at once, it is cancelled and tried again on the next pass in its window.
        val until = hm(Ft3Rules.barClose(s.mark).plusMinutes(Ft3Rules.ENTRY_WINDOW))
        val r = Paper.place(c, "BUY", lots, "LIMIT", "MIS", limit, null, known = q, auto = Ft3Rules.OWNER, mirror = Ft3Rules.SOURCE)
        val filled = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (filled != null) return bought(b, u, dayKey, s, c, r.orderId, filled.quantity, filled.price, now)
        val id = r.orderId?.takeIf { r.ok }
            ?: return say(b, u, "limit %s for %s refused (%s); tried again until %s".format(Locale.ENGLISH, px(limit), c.symbol, r.message, until))
        runCatching { Paper.cancel(id, "unfilled_limit") }
        val o = Paper.state.orders.firstOrNull { it.orderId == id }
        return when {
            o == null || o.status == "cancelled" || o.status == "rejected" ->
                say(b, u, "limit %s for %s not filled (%s); tried again until %s".format(Locale.ENGLISH, px(limit), c.symbol, r.message, until))
            o.status == "complete" -> bought(b, u, dayKey, s, c, id, o.quantity, o.averagePrice?.toDouble() ?: limit, now)
            // Still working (the cancel did not go through): never a second BUY beside it - settled on the next pass.
            else -> { b.pending[dayKey] = id; say(b, u, "an entry order is still working; settled before anything new") }
        }
    }

    /**
     * The entry order [id] a cancel could not take out: filled - adopted as the ticket; cancelled or refused - forgotten (the pick
     * may be bought again in its window); still working - cancelled again. True when the book changed.
     */
    private suspend fun settlePending(b: Book, u: String, dayKey: String, id: String, now: LocalDateTime): Boolean {
        val o0 = Paper.state.orders.firstOrNull { it.orderId == id }
        if (o0 != null && o0.status != "complete" && o0.status != "cancelled" && o0.status != "rejected") runCatching { Paper.cancel(id, "unfilled_limit") }
        val o = Paper.state.orders.firstOrNull { it.orderId == id }
        return when {
            o == null || o.status == "cancelled" || o.status == "rejected" -> { b.pending.remove(dayKey); true }
            o.status == "complete" -> {
                b.pending.remove(dayKey)
                val c = Paper.contractOf(o.symbol) ?: return true
                val s = b.picks[dayKey]?.let { Saved.decode(it) } ?: return true
                bought(b, u, dayKey, s, c, id, o.quantity, o.averagePrice?.toDouble() ?: return true, now)
            }
            else -> {
                val gone = b.picks[dayKey]?.let { Saved.decode(it) }?.let { !Ft3Rules.entryOpen(it.mark, now.toLocalTime()) } ?: true
                if (gone && b.status[u]?.contains("could not be cancelled") != true)
                    say(b, u, "its entry order $id could not be cancelled; the paper book's own square-off covers it", final = true)
                else false
            }
        }
    }

    /** [qty] of [c] bought at [price] by the order [orderId]: the ticket, its live path, the notice. */
    private suspend fun bought(b: Book, u: String, dayKey: String, s: Saved, c: Paper.Contract, orderId: String?, qty: Int, price: Double,
                               now: LocalDateTime): Boolean {
        orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${Ft3Rules.OWNER} · entry") } }
        runCatching { Notifier.orderFilled(app, "BUY", qty, c.symbol, price, "Paper", "${Ft3Rules.OWNER} · entry", orderId) }
        val pos = Pos(u, c.symbol, s.pick.right, qty, price, now, orderId, s.mark.toString(), s.pick.strike, s.pick.price, s.pick.distEm, s.pick.ivRatio,
            charges = chargesOf(orderId))
        b.positions += pos
        b.marks[c.symbol] = price
        // 22 Oct: the time machine's reality check (shadow only; background). FT3 has no stop or target on paper: the check says so (R not told).
        runCatching { TimeMachineHost.afterEntry(TimeMachineHost.Entry(Ft3Rules.SOURCE, Ft3Rules.LABEL, mirrorRef(pos), u, if (s.pick.right.toString() == "CE") 1 else -1, now,
            null, null, premium = true, exitAt = Ft3Rules.EXIT_AT)) }
        // Its live path ([ArmLive], the standard one): the same option and quantity, a LIMIT at the paper fill (it never chases).
        // No stop on paper; the mirror rests only its labelled -80% disaster stop at Zerodha (close-judged, [LiveMirror.Spec.closeStop]).
        runCatching { ArmLive.entered(ArmLive.Ask(Ft3Rules.SOURCE, mirrorRef(pos), c, 1, qty, price, stop = null, limit = price)) }
        runCatching { Notifier.post(app, NOTICE + Ft3Rules.UNDERLYINGS.indexOf(u).coerceAtLeast(0), Notifier.BUY,
            "${Ft3Rules.LABEL} bought ${c.symbol} · Paper",
            "Bought %d @ %s (Rs %,.0f) on the %s mark. Sells at 15:05. %s.".format(Locale.ENGLISH, qty, px(price), qty * price, hm(s.mark),
                Ft3Rules.BOSS_CHOICE),
            "almanac") }
        b.decided += dayKey
        b.picks.remove(dayKey)
        record(b, dayKey, u, "bought", s, "${c.symbol} $qty @ ${px(price)}")
        return say(b, u, "bought ${c.symbol} $qty @ ${px(price)} on the ${hm(s.mark)} mark · paper (sells at 15:05)", final = true)
    }

    /**
     * The row's price, from one read of its minute bars, for each of today's open tickets that is not due to sell and that the
     * stream has no price for (the arms pass only, before the lock is taken; memory reads otherwise).
     */
    private suspend fun barMarks(now: LocalDateTime): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (p in _view.value.open) {
            if (p.day != now.toLocalDate() || Ft3Rules.exitDue(now.toLocalTime(), false)) continue
            val c = Paper.contractOf(p.symbol) ?: continue
            if (runCatching { Paper.bookNow(c)?.last }.getOrNull()?.takeIf { it > 0 } != null) continue
            runCatching { closes(c.feedKey, now) }.getOrNull()?.maxByOrNull { it.key }?.value?.close?.takeIf { it > 0 }?.let { out[p.symbol] = it }
        }
        return out
    }

    /**
     * Each open ticket's exit, when due: 15:05 (or an earlier day's at once), Boss's stop for today. True when [b] changed.
     * No network here (22 Oct review): a ticket not due takes the stream's price, else [barMarks] (read by the arms pass outside
     * the lock).
     */
    private suspend fun exits(b: Book, now: LocalDateTime, barMarks: Map<String, Double> = emptyMap()): Boolean {
        var changed = false
        val stopped = stoppedToday()
        for ((i, p) in b.positions.withIndex().toList()) {
            if (!p.open) continue
            val c = Paper.contractOf(p.symbol) ?: continue
            // Gone from the paper book without this arm selling it: the expiry square-off at 15:05, the loss limit, or Boss.
            val held = Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity }
            if (held <= 0) {
                val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
                val done = p.copy(exit = t?.price?.toDouble() ?: p.entry, exitTime = t?.timestamp ?: now, why = "closed_elsewhere",
                    charges = p.charges + (t?.charges?.toDouble() ?: 0.0))
                b.positions[i] = done; closed(b, done); changed = true; continue
            }
            // The stream's last price when it has one (memory only); else the minute bar read outside the lock.
            val streamed = runCatching { Paper.bookNow(c)?.last }.getOrNull()?.takeIf { it > 0 }
            (streamed ?: barMarks[p.symbol])?.let { b.marks[p.symbol] = it }
            val why = when {
                Ft3Rules.exitDue(now.toLocalTime(), p.day.isBefore(now.toLocalDate())) -> Ft3Rules.EXIT_WHY
                // Boss's stop for today (or the daily loss limit) sells what is held: it only lowers risk.
                stopped -> "operator_stop"
                else -> null
            }
            if (why == null) continue
            val next = sell(b, p, c, why)
            if (next != p) { b.positions[i] = next; changed = true }
        }
        return changed
    }

    /** Sell all of [p] on paper at market. */
    private suspend fun sell(b: Book, p: Pos, c: Paper.Contract, why: String): Pos {
        val lots = (p.qty / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
        // 22 Oct (arms audit): an exit only ever reduces the ticket - never a short when the 15:05 expiry square-off sold it first.
        val r = Paper.place(c, "SELL", lots, "MARKET", "MIS", null, null, exit = true)
        val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (fill == null && !r.ok && Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity } <= 0) {
            val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
            val done = p.copy(exit = t?.price?.toDouble() ?: p.entry, exitTime = t?.timestamp ?: Market.now().toLocalDateTime().withNano(0),
                why = "closed_elsewhere", charges = p.charges + (t?.charges?.toDouble() ?: 0.0))
            b.marks.remove(p.symbol)
            closed(b, done)
            return done
        }
        if (fill == null) {
            r.orderId?.takeIf { r.ok }?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
            if (!b.status.sameToday(p.index, "exit_retry")) { b.status[p.index] = "exit_retry"; note(b, "${p.symbol}: the $why exit did not fill (${r.message}); tried again next pass") }
            return p
        }
        r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${Ft3Rules.OWNER} · exit") } }
        runCatching { Notifier.orderFilled(app, "SELL", fill.quantity, fill.symbol, fill.price, "Paper", "${Ft3Rules.OWNER} · exit", r.orderId) }
        val done = p.copy(exit = fill.price, exitTime = Market.now().toLocalDateTime().withNano(0), why = why, charges = p.charges + chargesOf(r.orderId))
        b.marks.remove(p.symbol)
        closed(b, done)
        return done
    }

    private fun closed(b: Book, p: Pos) {
        runCatching { ArmLive.closed(mirrorRef(p), p.why ?: "exit") }
        b.status[p.index] = "closed ${p.symbol}: ${p.why}"
        note(b, "%s: sold (%s) @ %s, %s after charges · paper".format(Locale.ENGLISH, p.symbol, p.why, px(p.exit ?: 0.0), rs(p.net ?: 0.0)))
        runCatching { Notifier.post(app, NOTICE + Ft3Rules.UNDERLYINGS.indexOf(p.index).coerceAtLeast(0), Notifier.SELL,
            "${Ft3Rules.LABEL} sold ${p.symbol} · Paper",
            "Sold (%s) @ %s: %s after charges. %s.".format(Locale.ENGLISH, p.why, px(p.exit ?: 0.0), rs(p.net ?: 0.0), Ft3Rules.BOSS_CHOICE), "almanac") }
    }

    /** For tests: the book forgotten (its file goes with the test's directory). */
    internal fun wipe() { cache = null; _view.value = empty() }
}
