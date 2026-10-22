package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.HeroTicketRules
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
 * "Hero ₹1,000 ticket" (15 Oct 2026, Boss: "We will be getting on 1000 rs worth of lots ... add it directly to arm"; "25% trail
 * it is"), the rules in [HeroTicketRules]: on NIFTY's and SENSEX's own expiry days, at 12:30, 1 lot of a call and 1 lot of a put
 * priced Rs 5-30 whose lot costs at most Rs 1,000; out on Boss's 25% trail (or a 2x / 3x target Boss picks on its row), else
 * 15:05. BOSS'S CHOICE - IT DID NOT PASS RESEARCH (R50): said on its row, in its notices and by Jarvis.
 *
 * 18 Oct (Boss: "What ever strategies are present for paper, should be present for live too"): this file itself still places
 * only paper orders; its live path is [com.optionslab.app.data.ArmLive], told of each paper buy, stop or lock and close (memory
 * only). That live trade goes only in Live with "AI trades go live" on, its own "Trade live" switch on, outside the paper-only
 * period and past every guard - so the words below about Zerodha describe this book, not that live path.
 *
 * PAPER ONLY, always: every order is the paper account's ([Paper.place] / [Paper.close]); there is no live flag and no Zerodha
 * call anywhere here, so nothing can reach Zerodha whatever Live, "AI trades go live" or a voice command says. Its book is
 * its own encrypted file (not in the backup), so its trades are a record of their own - apart from the arms that passed and
 * from the plain-rules record for the 26 Oct decision. New tickets obey the kill switch, the bot's stop for today (the daily
 * loss limit included), the account day lock and the account guard; fills and charges are the honest paper ones. Exits always
 * go; the expiry square-off (15:05) or Boss may close a ticket first (then it is booked as closed there).
 *
 * Switched ON once by the 15 Oct update (Boss's own choice, logged in Autopilot's change log as theirs, undone in one tap);
 * after that only Boss switches it. While a ticket is open its prices and exits run in the watch's minute lane at full
 * cadence, and after a restart the book is read first ([PositionsFirst]).
 */
object HeroTicketArm {
    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, "hero_ticket.vault")
        // 18 Oct (Boss: every paper strategy present for live too): its paper tickets, for their live path ([ArmLive]).
        ArmLive.register(com.optionslab.engine.orb.LiveMirror.HERO_TICKET) {
            if (cache == null) null else _view.value.let { v -> (v.open + v.today.filter { !it.open }).map { ArmLive.PaperLeg(mirrorRef(it), HeroTicketRules.SOURCE, if (it.open) it.qty else 0) } }
        }
    }

    /** A ticket's reference for its live path ([ArmLive]): its paper order (or its entry time). */
    private fun mirrorRef(p: Pos): String = "hero_ticket:${p.index}:${p.orderId ?: p.entryTime}"

    /** The one-time switch-on of 15 Oct (Boss's choice); in [Book.migrated] once done, never again. */
    internal const val AUTO_ON = "boss_on_2026-10-15"

    /**
     * TEST SEAM: true in the JVM tests' app, so the other tests' books start with this arm off (its own tests turn it back).
     * False in the app, always: its setter throws in a release build.
     */
    @Volatile internal var testNoAutoOn: Boolean = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

    /**
     * One paper ticket. [target] Boss's exit pick when it was bought; [paperFill] the paper sale's own price when booked apart;
     * [targetOrderId] its 2x / 3x target resting in the paper book as a LIMIT SELL (filled there on any tick, at the target).
     */
    data class Pos(
        val index: String, val symbol: String, val right: String, val qty: Int, val entry: Double, val entryTime: LocalDateTime,
        val orderId: String?, val target: String = HeroTicketRules.Target.DEFAULT.key, val exit: Double? = null,
        val exitTime: LocalDateTime? = null, val why: String? = null, val charges: Double = 0.0, val paperFill: Double? = null,
        val targetOrderId: String? = null,
        /** Where its 25% trail starts ([HeroTicketRules.TrailStart] key), as Boss had it set when it was bought. */
        val trailStart: String = HeroTicketRules.TrailStart.DEFAULT.key,
    ) {
        val open: Boolean get() = exit == null
        val day: LocalDate get() = entryTime.toLocalDate()
        /** Rupees after charges, once sold. */
        val net: Double? get() = exit?.let { (it - entry) * qty - charges }
        val cost: Double get() = entry * qty
    }

    private class Book(
        var armed: Boolean = false,
        var target: HeroTicketRules.Target = HeroTicketRules.Target.DEFAULT,
        var trailStart: HeroTicketRules.TrailStart = HeroTicketRules.TrailStart.DEFAULT,
        val positions: MutableList<Pos> = ArrayList(),
        /** "day|index" (not an expiry day, the window missed) and "day|index|CE" / "|PE" (each side's ticket decided). */
        val decided: MutableSet<String> = HashSet(),
        /** 21 Oct: with the day each line was written ([StatusMap]). */
        val status: StatusMap = StatusMap(),
        val log: ArrayList<String> = ArrayList(),
        val migrated: MutableSet<String> = HashSet(),
        /** "day|index|side" -> an entry order a cancel could not take out (settled on the next pass, never a second BUY beside it). */
        val pending: MutableMap<String, String> = HashMap(),
        /** Each open ticket's last price seen (memory only). */
        val marks: MutableMap<String, Double> = HashMap(),
    )

    /** What its row and Jarvis read: [today] today's tickets (open and closed), [nets] every closed ticket's rupees, oldest first. */
    data class View(val armed: Boolean, val target: HeroTicketRules.Target, val status: Map<String, String>, val open: List<Pos>,
                    val closed: List<Pos>, val today: List<Pos>, val log: List<String>, val nets: List<Double> = emptyList(),
                    val marks: Map<String, Double> = emptyMap(),
                    /** Where the 25% trail starts for new tickets (after 2x by default: a placeholder until Boss chooses, R55). */
                    val trailStart: HeroTicketRules.TrailStart = HeroTicketRules.TrailStart.DEFAULT) {
        /** Today's running P&L: closed tickets after charges, open ones at their last price before charges. */
        val todayPnl: Double get() = today.sumOf { p -> p.net ?: marks[p.symbol]?.let { (it - p.entry) * p.qty } ?: 0.0 }
    }

    private fun empty() = View(false, HeroTicketRules.Target.DEFAULT, emptyMap(), emptyList(), emptyList(), emptyList(), emptyList())
    private val _view = MutableStateFlow(empty())
    val view: StateFlow<View> = _view

    @Volatile private var cache: Book? = null
    private const val LOG_KEPT = 200
    private const val CLOSED_KEPT = 400
    /** Its own notices (bought, sold); 6940 + the index. */
    private const val NOTICE = 6940

    private fun ready() = ::file.isInitialized

    private fun book(): Book {
        cache?.let { return it }
        val b = Book()
        var unreadable = false
        runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            b.armed = o.optBoolean("armed", false)
            b.target = HeroTicketRules.Target.of(o.optString("target").ifEmpty { null })
            b.trailStart = HeroTicketRules.TrailStart.of(o.optString("trailStart").ifEmpty { null })
            o.optJSONArray("positions")?.let { a -> for (i in 0 until a.length()) b.positions += readPos(a.getJSONObject(i)) }
            o.optJSONArray("decided")?.let { a -> for (i in 0 until a.length()) b.decided += a.getString(i) }
            b.status.readFrom(o, runCatching { file.lastModified() }.getOrDefault(0L))
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
            o.optJSONArray("migrated")?.let { a -> for (i in 0 until a.length()) b.migrated += a.getString(i) }
            o.optJSONObject("pending")?.let { m -> m.keys().forEach { b.pending[it] = m.getString(it) } }
        }.onFailure { unreadable = true; if (file.exists()) Vault.setAside(file) }
        cache = b
        // 15 Oct, Boss's choice: on once, on paper. A book that could not be read is never switched on by itself (Boss may have
        // switched it off): it is only marked done. Never in IraGoldAlgo.
        if (AUTO_ON !in b.migrated) {
            b.migrated += AUTO_ON
            val on = !unreadable && !testNoAutoOn && !com.optionslab.app.BuildConfig.GOLD
            if (on && !b.armed) {
                b.armed = true
                note(b, "switched on once by the 15 Oct update, on your choice - paper only; ${HeroTicketRules.BOSS_CHOICE}")
                runCatching { com.optionslab.app.ira.Autopilot.logBossTicketOn() }
            }
            save(b)
        } else publish(b)
        return b
    }

    private fun readPos(p: JSONObject): Pos = Pos(
        p.getString("index"), p.getString("symbol"), p.getString("right"), p.getInt("qty"), p.getDouble("entry"),
        LocalDateTime.parse(p.getString("entryTime")), p.optString("orderId").ifEmpty { null },
        p.optString("target").ifEmpty { HeroTicketRules.Target.DEFAULT.key },
        if (p.has("exit")) p.getDouble("exit") else null, p.optString("exitTime").ifEmpty { null }?.let { LocalDateTime.parse(it) },
        p.optString("why").ifEmpty { null }, p.optDouble("charges", 0.0), if (p.has("paperFill")) p.getDouble("paperFill") else null,
        p.optString("targetOrderId").ifEmpty { null },
        p.optString("trailStart").ifEmpty { HeroTicketRules.TrailStart.DEFAULT.key },
    )

    private fun writePos(p: Pos): JSONObject = JSONObject().put("index", p.index).put("symbol", p.symbol).put("right", p.right).put("qty", p.qty)
        .put("entry", p.entry).put("entryTime", p.entryTime.toString()).put("orderId", p.orderId ?: "").put("target", p.target).put("charges", p.charges)
        .apply { p.exit?.let { put("exit", it) }; p.exitTime?.let { put("exitTime", it.toString()) }; p.why?.let { put("why", it) }; p.paperFill?.let { put("paperFill", it) }; p.targetOrderId?.let { put("targetOrderId", it) }; put("trailStart", p.trailStart) }

    /** What this process last wrote to the book's file: an unchanged save is not encrypted and synced again. */
    private val written = Vault.LastWrite()

    private fun save(b: Book) {
        val o = JSONObject().put("armed", b.armed).put("target", b.target.key).put("trailStart", b.trailStart.key)
        o.put("positions", JSONArray().apply { b.positions.forEach { put(writePos(it)) } })
        o.put("decided", JSONArray().apply { b.decided.sorted().takeLast(60).forEach { put(it) } })
        o.put("status", JSONObject().apply { b.status.forEach { (k, v) -> put(k, v) } })
        b.status.writeTo(o)
        o.put("log", JSONArray().apply { b.log.takeLast(LOG_KEPT).forEach { put(it) } })
        o.put("migrated", JSONArray().apply { b.migrated.sorted().forEach { put(it) } })
        o.put("pending", JSONObject().apply { b.pending.forEach { (k, v) -> put(k, v) } })
        runCatching { written.write(file, o.toString().toByteArray(Charsets.UTF_8)) }
        publish(b)
    }

    private fun publish(b: Book) {
        decidedNow = b.decided.toSet()
        pendingNow = b.pending.isNotEmpty()
        val closed = b.positions.filter { !it.open }
        val today = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        _view.value = View(b.armed, b.target, b.status.shown(), b.positions.filter { it.open }, closed.takeLast(20),
            b.positions.filter { it.day == today }, b.log.takeLast(30), closed.map { it.net ?: 0.0 }, b.marks.toMap(), b.trailStart)
    }

    private fun note(b: Book, text: String) {
        b.log += "${runCatching { Market.now().toLocalDateTime().withNano(0) }.getOrElse { LocalDateTime.now().withNano(0) }} $text"
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        runCatching { Diag.record("hero-ticket", "${HeroTicketRules.LABEL}: $text") }
    }

    /** Read the book (the row and the start-up's positions-first step call it, off the main thread). */
    suspend fun refresh(): View { if (ready()) lock.withLock { book() }; return _view.value }

    /** An open ticket is held (memory only: the book as last read). */
    fun holding(): Boolean = _view.value.open.isNotEmpty()

    /**
     * The watch needs this arm's pass now (memory only: no file, no network): a ticket is held, or it is switched on and the
     * time is 12:25 .. 12:33 (its lists are made ready from 12:25, its tickets bought 12:30 .. 12:32). Outside that it costs
     * the watch nothing - no wake, no minute lane, no safety-net round.
     */
    fun wantsPass(minute: Int = runCatching { Market.minuteNow() }.getOrDefault(0)): Boolean =
        // 22 Oct (arms audit, as FT3): an entry order a cancel could not take out keeps the pass coming until it is settled - it
        // may fill late, and a ticket the arm never booked would have no trail and no place in the day's budget.
        holding() || pendingNow || (_view.value.armed && minute in WAKE_FROM until WAKE_UNTIL)

    /** An entry order is still working (memory only: the book as last read). */
    @Volatile private var pendingNow: Boolean = false

    /** A stream tick older than this is not the ticket's price now (the minute bars are read instead). */
    private const val FRESH_TICK_MS = 5_000L

    private const val WAKE_FROM = 12 * 60 + 25
    private const val WAKE_UNTIL = 12 * 60 + 34

    /**
     * Switch it on or off - on paper, always. Off sells nothing it holds: its own exits still close it. [log]: Boss's flip from
     * the row (an off closes the 15 Oct change in Autopilot's log, so its Undo can never switch it again); false for the
     * change log's own Undo.
     */
    suspend fun setArmed(on: Boolean, log: Boolean = true): String {
        if (!ready()) return "${HeroTicketRules.LABEL} is not ready yet."
        return lock.withLock {
            val b = book()
            if (b.armed == on) return@withLock "${HeroTicketRules.LABEL} is already ${if (on) "on" else "off"} (paper only)."
            b.armed = on
            // 22 Oct ("switched off" at 13:02 right after the Trend watch sold both tickets: who did it?): the line says which door
            // switched it - its own row (Boss's switch) or the Undo in Autopilot's change log. Nothing else in the app switches it.
            note(b, (if (on) "switched on, paper only (${HeroTicketRules.BOSS_CHOICE})" else "switched off" +
                if (b.positions.any { it.open }) "; what it holds still closes by its own exits" else "") + " - ${byWords(log)}")
            save(b)
            if (log) runCatching { com.optionslab.app.ira.Autopilot.heroTicketSwitchedByBoss(on) }
            if (on) "${HeroTicketRules.LABEL} on, paper only. ${HeroTicketRules.BOSS_CHOICE}." else "${HeroTicketRules.LABEL} off."
        }
    }

    /** Who switched it ([setArmed]'s [log]: true from its row, false from the change log's Undo). */
    internal fun byWords(log: Boolean): String = if (log) "by you, from its row on Home" else "by the Undo in Autopilot's change log"

    /** Boss's exit pick for NEW tickets (2x, 3x or the trail alone); an open ticket keeps the one it was bought with. Kept. */
    suspend fun setTarget(t: HeroTicketRules.Target): String {
        if (!ready()) return "${HeroTicketRules.LABEL} is not ready yet."
        return lock.withLock {
            val b = book()
            if (b.target != t) { b.target = t; note(b, "exit for new tickets: ${t.words} (with the 25% trail and 15:05)"); save(b) }
            "${HeroTicketRules.LABEL}: new tickets sell on ${t.words}" + (if (t.mult != null) " or the 25% trail" else "") + ", else 15:05. Paper only."
        }
    }

    /**
     * Where the 25% trail starts for NEW tickets (at the fill as R50 measured it, or after 2x / 3x); an open ticket keeps the one
     * it was bought with. Kept. 15 Oct: after 2x is a placeholder default until Boss chooses (research R55).
     */
    suspend fun setTrailStart(s: HeroTicketRules.TrailStart): String {
        if (!ready()) return "${HeroTicketRules.LABEL} is not ready yet."
        return lock.withLock {
            val b = book()
            if (b.trailStart != s) { b.trailStart = s; note(b, "trail for new tickets: ${s.words}"); save(b) }
            "${HeroTicketRules.LABEL}: new tickets' 25% trail - ${s.words}. Paper only."
        }
    }

    /** The diagnostics line. */
    fun diagLine(): String {
        val v = _view.value
        return "${HeroTicketRules.LABEL}: ${if (v.armed) "on (paper only)" else "off"} · ${v.target.words} · ${v.trailStart.words} · open ${v.open.size} · ${v.nets.size} closed " +
            "%,.0f after charges".format(Locale.ENGLISH, v.nets.sum()) + v.status.toSortedMap().entries.joinToString("") { (k, s) -> " · $k $s" } +
            " · ${HeroTicketRules.BOSS_CHOICE}"
    }

    // ---- the pass ---------------------------------------------------------------------------------------------------

    /**
     * The fast path's minute lane (open positions first): only the open tickets' exits (trail, target, 15:05) - never the 12:30
     * chain reads, so this arm never holds up another arm's exits. Nothing held: returns at once (memory only).
     */
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
     * Called by the market watch's arms passes: the open tickets' exits first, then each index's 12:30 tickets. The 12:30 chain
     * reads happen BEFORE the arm's lock is taken ([prefetch]), so the minute lane's exits ([manageOnly]) never wait behind them.
     */
    suspend fun tick() {
        if (!ready() || com.optionslab.app.BuildConfig.GOLD || !Market.isTradingDay()) return
        val now = Market.now().toLocalDateTime().withNano(0)
        // The book read first (a fresh start), so its switch is known before the reads.
        if (cache == null) lock.withLock { book() }
        val reads = if (_view.value.armed && HeroTicketRules.entryPhase(now.toLocalTime()) == HeroTicketRules.Phase.OPEN)
            runCatching { prefetch(now) }.getOrDefault(emptyMap()) else emptyMap()
        lock.withLock {
            val b = book()
            if (!b.armed && b.positions.none { it.open }) return@withLock
            // An earlier day's unsettled entry order is the paper book's (its MIS square-off ended it): forgotten.
            var changed = b.pending.keys.removeAll { !it.startsWith("${now.toLocalDate()}|") }
            changed = runCatching { exits(b, now) }.getOrElse { note(b, "exit check failed: ${it.javaClass.simpleName}"); true } || changed
            if (b.armed) for (u in HeroTicketRules.UNDERLYINGS) {
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

    private fun chargesOf(orderId: String?): Double =
        if (orderId == null) 0.0 else Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    /** The index's feed key (NIFTY 50 on NSE, SENSEX on BSE). */
    internal fun indexKey(u: String): String? = if (u == "SENSEX") "BSE_INDEX|SENSEX" else Upstox.INDEX_KEYS[u]

    /** Today's finished 1-minute bars of [key], keyed by the minute each CLOSES (the feed's, with the stream's newer whole minutes). */
    private suspend fun closes(key: String, now: LocalDateTime): Map<LocalTime, Upstox.Bar> {
        val nowSec = now.atZone(com.optionslab.engine.IST).toEpochSecond()
        // The quick read (12 Oct: 5 s connect, 10 s read, one retry) - never the full read's minutes of retries in the watch's lane.
        val feed = Net.intradayQuick(key).filter { it.istDate == now.toLocalDate() && it.epochSecond + 60 <= nowSec }.sortedBy { it.epochSecond }
        val bars = runCatching { LocalCandles.overlay(key, feed, nowSec) }.getOrDefault(feed)
            .filter { it.istDate == now.toLocalDate() && it.epochSecond + 60 <= nowSec }
        return bars.associateBy { b -> (b.istMinute + 1).let { LocalTime.of(it / 60 % 24, it % 60) } }
    }

    /**
     * The index's listed options (NIFTY: the NSE list; SENSEX: BSE's) from the lists saved on the phone today. Never a download
     * in the pass: a list not yet saved today is fetched in the background (at most every 10 minutes) and the pass waits (empty).
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

    private const val FRESH_WAIT = "waiting for a fresh"
    /** Strikes read side by side at 12:30. */
    private const val CHAIN_GROUP = 6
    /** The resting 2x / 3x target is taken out of the paper book from this minute (before the 15:05 square-off). */
    private val TARGET_PULL: LocalTime = LocalTime.of(15, 3)

    /** One side's chain as read at 12:30 (outside the lock): its lot, the candidates, and how many strikes could not be read. */
    private class SideRead(val lot: Int, val cands: List<HeroRules.Candidate>, val unread: Int)

    /** One index's reads: the 12:30 index close (null: not fresh yet) and each side's chain. */
    private class IndexRead(val spot: Double?, val sides: Map<Right, SideRead>)

    /** The sides already decided today (memory only; kept by [publish]) - the reads skip them. */
    @Volatile private var decidedNow: Set<String> = emptySet()

    /**
     * The 12:30 reads, outside the lock: for each index whose expiry is today and with a side still open, the 12:30 index close,
     * then each side's chain on that side within 5% of spot, nearest the money first, in groups of [CHAIN_GROUP] read side by
     * side, outward until three strikes in a row are under Rs 5 (the stale check needs the further strikes).
     */
    private suspend fun prefetch(now: LocalDateTime): Map<String, IndexRead> {
        val today = now.toLocalDate()
        val out = HashMap<String, IndexRead>()
        for (u in HeroTicketRules.UNDERLYINGS) {
            val dayKey = "$today|$u"
            val decided = decidedNow
            if (dayKey in decided) continue
            val sides = listOf(Right.CE, Right.PE).filter { "$dayKey|${it.name}" !in decided }
            if (sides.isEmpty()) continue
            val list = listed(u)
            if (!HeroTicketRules.tradesToday(today, true, list.map { it.expiry }.distinct())) continue
            val ik = indexKey(u) ?: continue
            val spot = runCatching { closes(ik, now) }.getOrNull()?.get(HeroTicketRules.ENTRY_BAR)?.close
            if (spot == null) { out[u] = IndexRead(null, emptyMap()); continue }
            val reads = HashMap<Right, SideRead>()
            for (right in sides) {
                val s = if (right == Right.CE) 1 else -1
                val otm = list.filter { it.expiry == today && it.right == right && (if (s > 0) it.strike > spot else it.strike < spot) }
                    .filter { kotlin.math.abs(it.strike - spot) <= spot * HeroTicketRules.SPOT_BAND }.sortedBy { kotlin.math.abs(it.strike - spot) }
                val lot = otm.firstOrNull()?.lotSize ?: continue
                val cands = ArrayList<HeroRules.Candidate>()
                var under = 0
                var unread = 0
                for (group in otm.chunked(CHAIN_GROUP)) {
                    val got = kotlinx.coroutines.coroutineScope {
                        group.map { o -> async { o to runCatching { closes(o.instrumentKey, now) }.getOrNull() } }.map { it.await() }
                    }
                    for ((o, bars0) in got) {
                        if (bars0 == null) unread++
                        val bars = bars0.orEmpty().filterKeys { !it.isAfter(HeroTicketRules.ENTRY_BAR) }
                        val ltp = bars.maxByOrNull { it.key }?.value?.close ?: 0.0
                        val traded = bars.any { (t, bar) -> bar.volume > 0 && !t.isBefore(HeroTicketRules.ENTRY_BAR.minusMinutes(HeroTicketRules.TRADED_BARS - 1)) }
                        // The paper feed has no depth: the last price stands in for the ask (as the paper fill does).
                        cands += HeroRules.Candidate(o.strike, ltp, ltp.takeIf { it > 0 }, traded)
                        under = if (bars0 != null && ltp < HeroTicketRules.MIN_PRICE - 1e-9) under + 1 else 0
                        if (under >= 3) break
                    }
                    if (under >= 3) break
                }
                reads[right] = SideRead(lot, cands, unread)
            }
            out[u] = IndexRead(spot, reads)
        }
        return out
    }

    /** [u]'s 12:30 tickets at [now] from [read] (the reads made outside the lock). True when the book changed. */
    private suspend fun decide(b: Book, u: String, now: LocalDateTime, read: IndexRead?): Boolean {
        val today = now.toLocalDate()
        val dayKey = "$today|$u"
        if (dayKey in b.decided) return false
        val sides = listOf(Right.CE, Right.PE).filter { "$dayKey|${it.name}" !in b.decided }
        if (sides.isEmpty()) return false
        val list = listed(u)
        // No list yet (not downloaded, or offline): asked again on the next pass, never the day given up.
        if (list.isEmpty()) return say(b, u, "waiting for the options list")
        if (!HeroTicketRules.tradesToday(today, true, list.map { it.expiry }.distinct())) {
            b.decided += dayKey
            return say(b, u, "not its expiry day", final = true)
        }
        // An entry order a cancel could not take out (a hiccup): settled first - adopted when it filled, else retried.
        var changed = false
        for (right in sides) {
            val key = "$dayKey|${right.name}"
            val id = b.pending[key] ?: continue
            changed = settlePending(b, u, right, key, id, now) || changed
        }
        if (sides.any { b.pending.containsKey("$dayKey|${it.name}") }) return say(b, u, "an entry order is still working; settled before anything new") || changed
        val open = sides.filter { "$dayKey|${it.name}" !in b.decided }
        if (open.isEmpty()) return changed
        val phase = HeroTicketRules.entryPhase(now.toLocalTime())
        if (phase == HeroTicketRules.Phase.BEFORE) return say(b, u, "its expiry day: buys at 12:30") || changed
        if (phase == HeroTicketRules.Phase.LATE) {
            open.forEach { b.decided += "$dayKey|${it.name}" }
            val last = b.status[u].orEmpty()
            val why = when {
                last.startsWith("$FRESH_WAIT price") -> "the strikes' prices could not be read by 12:33"
                last.startsWith(FRESH_WAIT) -> "no fresh 12:30 index price by 12:33"
                last.startsWith("limit") -> "not filled by 12:33 ($last)"
                else -> "the watch was not in time for 12:30"
            }
            return say(b, u, "no ticket ${open.joinToString("/") { it.name }}: $why", final = true)
        }
        // 12:30 .. 12:32: the index's 12:30 close first (a fresh price, or no ticket).
        val r = read ?: return say(b, u, "$FRESH_WAIT 12:30 index price") || changed
        val spot = r.spot ?: return say(b, u, "$FRESH_WAIT 12:30 index price") || changed
        for (right in open) changed = side(b, u, right, spot, r.sides[right], now) || changed
        return changed
    }

    /** One side's ticket ([right]) at [spot] from its chain [read]. True when the book changed. */
    private suspend fun side(b: Book, u: String, right: Right, spot: Double, read: SideRead?, now: LocalDateTime): Boolean {
        val today = now.toLocalDate()
        val key = "$today|$u|${right.name}"
        val s = if (right == Right.CE) 1 else -1
        fun done(why: String): Boolean { b.decided += key; return say(b, u, "${right.name} $why", final = true) }
        if (read == null) return done("no strike listed within 5% of spot")
        // A strike whose price could not be read (a timeout) leaves the chain incomplete: never a pick from it (the nearest one
        // might be the one missing); the next pass reads again until 12:33.
        if (read.unread > 0) return say(b, u, "$FRESH_WAIT price: ${read.unread} strike(s) could not be read; trying again until 12:33")
        val lot = read.lot
        val pick = HeroTicketRules.pick(s, spot, lot, read.cands)
            ?: return done("skipped: no strike priced Rs 5-30 whose lot of $lot costs at most Rs 1,000 (or its price was not fresh)")
        val c = Paper.ticketContract(u, today, pick.strike, right) ?: return done("skipped: ${pick.strike} not in the options list")
        if (c.lotSize <= 0) return done("skipped: lot size unknown")
        // The kill switch, the stop for today, the day lock and the guard: nothing bypasses them (a refusal is that side's day).
        if (runCatching { AppSettings.load().guardKill }.getOrDefault(true)) return done("refused: the kill switch is on")
        if (stoppedToday()) return done("refused: the bot is stopped for today")
        runCatching { DayLockGuard.refusal(false) }.getOrNull()?.let { return done("refused: $it") }
        val q = runCatching { Paper.quote(c) }.getOrNull()
        val ask = q?.ask?.takeIf { it > 0 } ?: q?.ltp?.takeIf { it > 0 } ?: pick.ltp
        val limit = HeroTicketRules.limitPrice(ask, pick.ltp, c.lotSize)
        val spent = b.positions.filter { it.day == today }.sumOf { it.cost }
        if (!HeroTicketRules.dayBudgetOk(spent, limit * c.lotSize * HeroTicketRules.LOTS))
            return done("skipped: today's tickets would pass Rs 2,000 (Rs %,.0f spent)".format(Locale.ENGLISH, spent))
        val snap = runCatching { Paper.snapshot() }.getOrNull()
        val refusals = Guard.check(Guard.paperOrder(c, "BUY", HeroTicketRules.LOTS, limit), snap?.let { Guard.paperAccount(it) }, paper = true)
        if (refusals.isNotEmpty()) return done("refused by the account guard: " + refusals.joinToString(" "))
        runCatching { LiveRecorder.signal(HeroTicketRules.LABEL, c.symbol, u, right.name, limit, paper = true) }
        // A LIMIT BUY on the PAPER account; not filled at once, it is cancelled and tried again on the next pass until 12:33.
        val r = Paper.place(c, "BUY", HeroTicketRules.LOTS, "LIMIT", "MIS", limit, null, known = q, auto = HeroTicketRules.OWNER)
        // 22 Oct (arms audit; the 22 Oct diagnostics showed the SENSEX 73,800 CE's two cancelled 12:30 orders as "IraAlgo-Android"):
        // the order carries this arm's name from the moment it is placed, filled or not, so a cancelled chase order is never
        // mistaken for one of Boss's own in Orders, the order review or the uploaded diagnostics.
        r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${HeroTicketRules.OWNER} · entry") } }
        val filled = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (filled != null) return bought(b, u, right, key, c, r.orderId, filled.quantity, filled.price, now)
        val id = r.orderId?.takeIf { r.ok }
            ?: return say(b, u, "limit %s for %s refused (%s); tried again until 12:33".format(Locale.ENGLISH, px(limit), c.symbol, r.message))
        runCatching { Paper.cancel(id, "unfilled_limit") }
        val o = Paper.state.orders.firstOrNull { it.orderId == id }
        return when {
            o == null || o.status == "cancelled" || o.status == "rejected" -> say(b, u, "limit %s for %s not filled (%s); tried again until 12:33".format(Locale.ENGLISH, px(limit), c.symbol, r.message))
            o.status == "complete" -> bought(b, u, right, key, c, id, o.quantity, o.averagePrice?.toDouble() ?: limit, now)
            // Still working (the cancel did not go through): never a second BUY beside it - settled on the next pass.
            else -> { b.pending[key] = id; say(b, u, "an entry order is still working; settled before anything new") }
        }
    }

    /**
     * The entry order [id] a cancel could not take out: filled - adopted as the ticket; cancelled or refused - forgotten (the
     * side may buy again until 12:33); still working - cancelled again (from 12:33, left to the paper book's own square-off and
     * told). True when the book changed.
     */
    private suspend fun settlePending(b: Book, u: String, right: Right, key: String, id: String, now: LocalDateTime): Boolean {
        val o0 = Paper.state.orders.firstOrNull { it.orderId == id }
        if (o0 != null && o0.status != "complete" && o0.status != "cancelled" && o0.status != "rejected") runCatching { Paper.cancel(id, "unfilled_limit") }
        val o = Paper.state.orders.firstOrNull { it.orderId == id }
        return when {
            o == null || o.status == "cancelled" || o.status == "rejected" -> { b.pending.remove(key); true }
            o.status == "complete" -> {
                b.pending.remove(key)
                val c = Paper.contractOf(o.symbol) ?: return true
                bought(b, u, right, key, c, id, o.quantity, o.averagePrice?.toDouble() ?: return true, now)
            }
            else -> {
                if (HeroTicketRules.entryPhase(now.toLocalTime()) == HeroTicketRules.Phase.LATE && b.status[u]?.contains("could not be cancelled") != true)
                    say(b, u, "${right.name}: its 12:30 entry order $id could not be cancelled; the paper book's own square-off covers it", final = true)
                else false
            }
        }
    }

    /** [qty] of [c] bought at [price] by the order [orderId]: the ticket, its resting target (2x / 3x), the notice. */
    private suspend fun bought(b: Book, u: String, right: Right, key: String, c: Paper.Contract, orderId: String?, qty: Int, price: Double,
                               now: LocalDateTime): Boolean {
        orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${HeroTicketRules.OWNER} · entry") } }
        runCatching { Notifier.orderFilled(app, "BUY", qty, c.symbol, price, "Paper", "${HeroTicketRules.OWNER} · entry", orderId) }
        // Boss's 2x / 3x: the target rests in the paper book as a LIMIT SELL, so any tick through it fills it there - at the target.
        val targetId = HeroTicketRules.targetLevel(price, b.target)?.let { lv ->
            runCatching { Paper.place(c, "SELL", HeroTicketRules.LOTS, "LIMIT", "MIS", lv, null, exit = true) }.getOrNull()?.takeIf { it.ok }?.orderId
                ?.also { runCatching { Strategies.tagOwner("paper:$it", "${HeroTicketRules.OWNER} · target") } }
        }
        val pos = Pos(u, c.symbol, right.name, qty, price, now, orderId, b.target.key, charges = chargesOf(orderId), targetOrderId = targetId,
            trailStart = b.trailStart.key)
        b.positions += pos
        b.marks[c.symbol] = price
        // 18 Oct: its live path ([ArmLive]): the same option and lot, a LIMIT at the paper fill (it never chases). Its 25% trail is judged
        // on minute closes on paper, so the paper exit sells the live trade; only a labelled -80% disaster stop rests at Zerodha (review
        // D2: a tick stop at the trail would sell on a wick the paper ticket holds through). The 2x / 3x target stays the app's.
        runCatching { ArmLive.entered(ArmLive.Ask(HeroTicketRules.SOURCE, mirrorRef(pos), c, 1, qty, price,
            stop = if (b.trailStart.mult == null) price * (1 - HeroTicketRules.TRAIL) else null, limit = price)) }
        val exits = HeroTicketRules.targetLevel(price, b.target)?.let { "target %s (%s) or the 25%% trail, else 15:05".format(Locale.ENGLISH, px(it), b.target.words) }
            ?: "the 25% trail, else 15:05"
        val exitsSaid = "$exits (${b.trailStart.words})"
        runCatching { Notifier.post(app, NOTICE + HeroTicketRules.UNDERLYINGS.indexOf(u) * 2 + (if (right == Right.CE) 0 else 1), Notifier.BUY,
            "${HeroTicketRules.LABEL} bought ${c.symbol} · Paper",
            // (Words with a "%" in them - "25% trail" - are never part of a format pattern.)
            "Bought %d @ %s (Rs %,.0f) at 12:30. Sells on %s. %s.".format(Locale.ENGLISH, qty, px(price), qty * price,
                exitsSaid, HeroTicketRules.BOSS_CHOICE),
            "almanac") }
        b.decided += key
        return say(b, u, "${right.name} bought ${c.symbol} $qty @ ${px(price)} · paper (sells on $exitsSaid)", final = true)
    }

    /** Each open ticket's exit, when due. True when [b] changed. */
    private suspend fun exits(b: Book, now: LocalDateTime): Boolean {
        var changed = false
        val stopped = stoppedToday()
        for ((i, p0) in b.positions.withIndex().toList()) {
            if (!p0.open) continue
            val c = Paper.contractOf(p0.symbol) ?: continue
            // Its resting target filled in the paper book (at the target): that is the exit.
            val filled = targetFilled(p0)
            if (filled != null) { b.positions[i] = filled; closed(b, filled); changed = true; continue }
            // From 15:03 the resting target is taken out of the book before the 15:05 square-off (which closes the position but
            // not its orders): a target left resting after it could never sell what is no longer held. The check below still
            // sells at the target if it is reached.
            var p = p0
            if (p.targetOrderId != null && !now.toLocalTime().isBefore(TARGET_PULL)) {
                p.targetOrderId?.let { id -> runCatching { Paper.cancel(id, "before_1505") } }
                val pulled = targetFilled(p)
                if (pulled != null) { b.positions[i] = pulled; closed(b, pulled); changed = true; continue }
                p = p.copy(targetOrderId = null); b.positions[i] = p; changed = true
            }
            // Gone from the paper book without this arm selling it: the expiry square-off at 15:05, the loss limit, or Boss.
            val held = Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity }
            if (held <= 0) {
                p.targetOrderId?.let { runCatching { Paper.cancel(it, "position_closed") } }
                val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
                val done = p.copy(exit = t?.price?.toDouble() ?: p.entry, exitTime = t?.timestamp ?: now, why = "closed_elsewhere",
                    charges = p.charges + (t?.charges?.toDouble() ?: 0.0), targetOrderId = null)
                b.positions[i] = done; closed(b, done); changed = true; continue
            }
            val bars = if (p.day == now.toLocalDate()) runCatching { closes(c.feedKey, now) }.getOrNull().orEmpty() else emptyMap()
            // The stream's last price when it has one (memory only), else the minute bars just read: one read a ticket a pass.
            // 22 Oct (arms audit): the stream's tick only while fresh (a tick left from a stream that stopped is not the price now).
            val ltp = runCatching { Paper.bookNow(c)?.takeIf { it.ageMs <= FRESH_TICK_MS }?.last }.getOrNull()?.takeIf { it > 0 } ?: bars.maxByOrNull { it.key }?.value?.close
            ltp?.let { b.marks[p.symbol] = it }
            val trail = HeroTicketRules.trail(p.entry, p.entryTime.toLocalTime(), bars.mapValues { it.value.close }, HeroTicketRules.TrailStart.of(p.trailStart))
            // 18 Oct: the trail's level once armed, told to its live path ([ArmLive]; memory only) - which, the ticket being close-judged,
            // follows the paper exit rather than resting this level at Zerodha.
            HeroTicketRules.TrailStart.of(p.trailStart).mult.let { m -> if (m == null || trail.peak >= m * p.entry - 1e-9) ArmLive.level(mirrorRef(p), trail.level) }
            // A resting target is the paper book's to fill; the pure check below covers a target that could not be placed.
            val target = if (p.targetOrderId != null) HeroTicketRules.Target.TRAIL_ONLY else HeroTicketRules.Target.of(p.target)
            val step = HeroTicketRules.exitStep(p.entry, ltp, trail, now.toLocalTime(), target, p.day.isBefore(now.toLocalDate()))
            // 16 Oct (research R63, Boss's "if the trend changes, sell"): the Trend watch asked to sell this leg - the index turned
            // against it (T3, which passed every gate for this ticket). Memory only: a map read, never a file or the network here.
            val trend = runCatching { TrendWatchHost.sellRequested(p.symbol) }.getOrDefault(false)
            // Boss's stop for today (or the daily loss limit) sells what is held: it only lowers risk.
            val (why, limit) = when {
                step != null -> step.kind.why to step.limit
                trend -> TREND_WHY to null
                stopped -> "operator_stop" to null
                else -> continue
            }
            val next = sell(b, p, c, why, limit)
            if (next != p) { b.positions[i] = next; changed = true }
            // (The loop's variable p0 is the book's as it was; p is the ticket after the target pull above.)
        }
        return changed
    }

    /** Sell all of [p] on paper: at the target's LIMIT [limit] (booked there at most), else at market. */
    private suspend fun sell(b: Book, p0: Pos, c: Paper.Contract, why: String, limit: Double?): Pos {
        // The resting target out of the book first; if it filled meanwhile, that is the exit.
        val p = p0.targetOrderId?.let { id ->
            runCatching { Paper.cancel(id, "exit:$why") }
            targetFilled(p0)?.let { done -> closed(b, done); return done }
            p0.copy(targetOrderId = null)
        } ?: p0
        val lots = (p.qty / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
        // 22 Oct (arms audit): an exit, so it only ever reduces the ticket - when something else closed it between the held read and
        // this sale (the sweeper, Boss, or the 15:15 square-off), the arm's SELL used to open a paper SHORT. Now that sale is refused
        // as already closed and the ticket is booked closed elsewhere, at that sale.
        val r = if (limit != null) Paper.place(c, "SELL", lots, "LIMIT", "MIS", limit, null, exit = true) else Paper.place(c, "SELL", lots, "MARKET", "MIS", null, null, exit = true)
        val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (fill == null && !r.ok && Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity } <= 0) {
            val t = Paper.state.trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
            val done = p.copy(exit = t?.price?.toDouble() ?: p.entry, exitTime = t?.timestamp ?: Market.now().toLocalDateTime().withNano(0),
                why = "closed_elsewhere", charges = p.charges + (t?.charges?.toDouble() ?: 0.0), targetOrderId = null)
            b.marks.remove(p.symbol)
            closed(b, done)
            return done
        }
        if (fill == null) {
            r.orderId?.takeIf { r.ok }?.let { id -> runCatching { Paper.cancel(id, if (limit != null) "unfilled_limit" else "unfilled_market") } }
            if (!b.status.sameToday(p.index, "exit_retry")) { b.status[p.index] = "exit_retry"; note(b, "${p.symbol}: the $why exit did not fill (${r.message}); tried again next pass") }
            return p
        }
        r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${HeroTicketRules.OWNER} · exit") } }
        runCatching { Notifier.orderFilled(app, "SELL", fill.quantity, fill.symbol, fill.price, "Paper", "${HeroTicketRules.OWNER} · exit", r.orderId) }
        val booked = if (limit != null) HeroTicketRules.bookedTarget(fill.price, limit) else fill.price
        val done = p.copy(exit = booked, exitTime = Market.now().toLocalDateTime().withNano(0), why = why, charges = p.charges + chargesOf(r.orderId),
            paperFill = fill.price.takeIf { kotlin.math.abs(it - booked) > 1e-9 })
        b.marks.remove(p.symbol)
        closed(b, done)
        return done
    }

    /** [p] closed at its resting target when that order is complete in the paper book; else null. */
    private fun targetFilled(p: Pos): Pos? {
        val id = p.targetOrderId ?: return null
        val o = Paper.state.orders.firstOrNull { it.orderId == id }?.takeIf { it.status == "complete" } ?: return null
        val level = HeroTicketRules.targetLevel(p.entry, HeroTicketRules.Target.of(p.target))
        val fill = o.averagePrice?.toDouble() ?: level ?: return null
        // Booked at the target at most (a gap through it never counts as better); the paper fill kept beside it when it differs.
        val px = level?.let { HeroTicketRules.bookedTarget(fill, it) } ?: fill
        return p.copy(exit = px, paperFill = fill.takeIf { kotlin.math.abs(it - px) > 1e-9 }, exitTime = o.updateTimestamp, why = HeroTicketRules.ExitKind.TARGET.why,
            charges = p.charges + chargesOf(id), targetOrderId = null)
    }

    /** A leg sold because the Trend watch saw the index turn against it ([TrendWatchHost], research R63 T3). */
    const val TREND_WHY = "trend_turn"

    private fun closed(b: Book, p: Pos) {
        runCatching { ArmLive.closed(mirrorRef(p), p.why ?: "exit") }
        b.status[p.index] = "closed ${p.symbol}: ${p.why}"
        if (p.why == TREND_WHY) runCatching {
            TrendWatchHost.sold(p.symbol, "%s %s sold on paper on the trend turn (T3) @ %s".format(Locale.ENGLISH,
                HeroTicketRules.LABEL, p.symbol, px(p.exit ?: 0.0)))
        }
        note(b, "%s: sold (%s) @ %s, %s after charges · paper".format(Locale.ENGLISH, p.symbol, p.why, px(p.exit ?: 0.0), rs(p.net ?: 0.0)))
        runCatching { Notifier.post(app, NOTICE + HeroTicketRules.UNDERLYINGS.indexOf(p.index).coerceAtLeast(0) * 2 + (if (p.right == "CE") 0 else 1),
            Notifier.SELL, "${HeroTicketRules.LABEL} sold ${p.symbol} · Paper",
            "Sold (%s) @ %s: %s after charges. %s.".format(Locale.ENGLISH, p.why, px(p.exit ?: 0.0), rs(p.net ?: 0.0), HeroTicketRules.BOSS_CHOICE), "almanac") }
    }

    /** For tests: the book forgotten (its file goes with the test's directory). */
    internal fun wipe() { cache = null; _view.value = empty() }
}
