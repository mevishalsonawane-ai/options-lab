package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.app.work.Notifier
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.NightRules
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
 * 18 Oct (Boss: "What ever strategies are present for paper, should be present for live too"): this file itself still places
 * only paper orders; its live path is [com.optionslab.app.data.ArmLive], told of each paper buy, stop or lock and close (memory
 * only). That live trade goes only in Live with "AI trades go live" on, its own "Trade live" switch on, outside the paper-only
 * period and past every guard - so the words below about Zerodha describe this book, not that live path.
 *
 * "Night (R3)" (08 Oct, Boss's yes; research/hunt/x2/PREREG.md rule R3, the rules in [NightRules]): a PAPER-ONLY arm that
 * buys one lot of a 1-ITM NIFTY weekly and BANKNIFTY monthly option at 15:20 on a strong close the day-end OI build-up
 * agrees with, and sells it at 09:16 the next session. Not proven (research: about Rs 207 a day before the holdout, Rs 150
 * in it, the holdout not significant). It never reaches Zerodha: every order is the paper account's ([Paper]).
 *
 * Its position is NRML, so the paper account's 15:15 MIS square-off never sells it, and it is not one of the ORB arms', so
 * their 15:10 exits never see it; the expiry square-off leaves it to its own 09:16 sale (it is never held into its
 * contract's expiry). New entries obey the bot's stop for today, the kill switch and the account day lock; its 09:16 sale
 * always goes. Every decision is kept in its log and the diagnostics. Its own encrypted file; one decision a day per index.
 */
object NightArm {
    private lateinit var app: Context
    private lateinit var file: File
    private val lock = Mutex()

    fun init(context: Context) {
        app = context.applicationContext
        file = File(app.filesDir, "night.vault")
        // 18 Oct (Boss: every paper strategy present for live too): its paper trades, for their live path ([ArmLive]).
        ArmLive.register(com.optionslab.engine.orb.LiveMirror.NIGHT) {
            if (cache == null) null else _view.value.let { v ->
                val today = Market.today()
                (v.open + v.closed.filter { it.exitTime?.toLocalDate() == today }).map { ArmLive.PaperLeg(mirrorRef(it), NightRules.SOURCE, if (it.open) it.qty else 0) }
            }
        }
    }

    /** A trade's reference for its live path ([ArmLive]): its index and paper order (or its entry time). */
    private fun mirrorRef(p: Pos): String = "night:${p.index}:${p.orderId ?: p.entryTime}"

    /** One overnight trade on paper. */
    data class Pos(
        val index: String, val symbol: String, val right: String, val qty: Int, val entry: Double, val entryDay: LocalDate,
        val entryTime: LocalDateTime, val orderId: String?, val exit: Double? = null, val exitTime: LocalDateTime? = null,
        val why: String? = null, val charges: Double = 0.0,
    ) {
        val open: Boolean get() = exit == null
        /** Rupees after charges, once sold. */
        val net: Double? get() = exit?.let { (it - entry) * qty - charges }
    }

    private class Book(
        var armed: Boolean = false,
        val positions: MutableList<Pos> = ArrayList(),
        /** Index -> the day-end build-up at 15:19, oldest first (seeded with the research's last 60 sessions). */
        val history: MutableMap<String, MutableList<Double>> = HashMap(),
        /** "day|index" already decided (and recorded). */
        val decided: MutableSet<String> = HashSet(),
        /** 21 Oct: with the day each line was written ([StatusMap]). */
        val status: StatusMap = StatusMap(),
        val log: ArrayList<String> = ArrayList(),
    )

    /** What the Strategies row shows. */
    data class View(val armed: Boolean, val status: Map<String, String>, val open: List<Pos>, val closed: List<Pos>, val log: List<String>)

    private val _view = MutableStateFlow(View(false, emptyMap(), emptyList(), emptyList(), emptyList()))
    val view: StateFlow<View> = _view

    @Volatile private var cache: Book? = null
    private const val LOG_KEPT = 200
    private const val CLOSED_KEPT = 400

    private fun ready() = ::file.isInitialized

    /** Its book can be read now (15 Oct cleanup: a read before that counts as "holding", so nothing is switched off on it). */
    fun readyForCleanup(): Boolean = ready()

    private fun book(): Book {
        cache?.let { return it }
        val gen = wipes
        val b = Book()
        runCatching {
            val o = JSONObject(String(Vault.readFileSteady(file) ?: return@runCatching, Charsets.UTF_8))
            b.armed = o.optBoolean("armed", false)
            o.optJSONArray("positions")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    b.positions += Pos(p.getString("index"), p.getString("symbol"), p.getString("right"), p.getInt("qty"), p.getDouble("entry"),
                        LocalDate.parse(p.getString("entryDay")), LocalDateTime.parse(p.getString("entryTime")), p.optString("orderId").ifEmpty { null },
                        if (p.has("exit")) p.getDouble("exit") else null, p.optString("exitTime").ifEmpty { null }?.let { LocalDateTime.parse(it) },
                        p.optString("why").ifEmpty { null }, p.optDouble("charges", 0.0))
                }
            }
            o.optJSONObject("history")?.let { h -> h.keys().forEach { k -> val a = h.getJSONArray(k); b.history[k] = (0 until a.length()).map { a.getDouble(it) }.toMutableList() } }
            o.optJSONArray("decided")?.let { a -> for (i in 0 until a.length()) b.decided += a.getString(i) }
            b.status.readFrom(o, runCatching { file.lastModified() }.getOrDefault(0L))
            o.optJSONArray("log")?.let { a -> for (i in 0 until a.length()) b.log += a.getString(i) }
        }.onFailure { if (file.exists()) Vault.setAside(file) }
        // 26 Oct (review): a book read before "Erase everything" is used by this pass only, never kept or shown ([save] then skips it).
        synchronized(written) { if (gen == wipes) { cache = b; publish(b) } }
        return b
    }

    /** 26 Oct: bumped by [wipe] (under [written]'s lock): a read that began before it is not kept ([book]). */
    @Volatile private var wipes = 0

    /** What this process last wrote to the book's file: an unchanged save is not encrypted and synced again ([Vault.LastWrite]). */
    private val written = Vault.LastWrite()

    private fun save(b: Book) {
        val o = JSONObject().put("armed", b.armed)
        o.put("positions", JSONArray().apply {
            b.positions.forEach { p ->
                put(JSONObject().put("index", p.index).put("symbol", p.symbol).put("right", p.right).put("qty", p.qty).put("entry", p.entry)
                    .put("entryDay", p.entryDay.toString()).put("entryTime", p.entryTime.toString()).put("orderId", p.orderId ?: "")
                    .apply { p.exit?.let { put("exit", it) }; p.exitTime?.let { put("exitTime", it.toString()) }; p.why?.let { put("why", it) } }
                    .put("charges", p.charges))
            }
        })
        o.put("history", JSONObject().apply { b.history.forEach { (k, v) -> put(k, JSONArray().apply { v.takeLast(NightRules.Z_WINDOW * 2).forEach { put(it) } }) } })
        // Only the last few days' decisions are needed (one a day per index).
        o.put("decided", JSONArray().apply { b.decided.sorted().takeLast(40).forEach { put(it) } })
        o.put("status", JSONObject(b.status as Map<*, *>))
        b.status.writeTo(o)
        o.put("log", JSONArray().apply { b.log.takeLast(LOG_KEPT).forEach { put(it) } })
        // 26 Oct: under [written]'s lock, as [wipe]: a book "Erase everything" dropped meanwhile is never written back or shown.
        synchronized(written) {
            if (cache !== b) return
            runCatching { written.write(file, o.toString().toByteArray(Charsets.UTF_8)) }
            publish(b)
        }
    }

    /**
     * 26 Oct ("Erase everything", [PersonalStores]): its trades, switch, decisions and log dropped from memory and its file deleted.
     * Under [written]'s lock, as every save, so a pass that read the old book writes nothing after it ([save]). Never waits for the
     * book's lock; safe before [init]. Memory and files only: no vault read. (Its live path's memory is [ArmLive]'s.)
     */
    internal fun wipe() {
        synchronized(written) {
            wipes++; cache = null; written.forget()
            _view.value = View(false, emptyMap(), emptyList(), emptyList(), emptyList())
            for (f in filesForErase()) runCatching { f.delete() }
        }
    }

    /** 26 Oct: anything of the book is held in memory (the erase's check; memory only). */
    internal fun holdsForErase(): Boolean = runCatching {
        val v = _view.value
        cache != null || v.armed || v.open.isNotEmpty() || v.closed.isNotEmpty() || v.log.isNotEmpty() || v.status.isNotEmpty()
    }.getOrDefault(true)

    /** 26 Oct: its book, and any copy of it set aside as unreadable (none before [init]). */
    internal fun filesForErase(): List<File> = if (!ready()) emptyList() else
        listOf(file) + file.parentFile?.listFiles { x -> x.name.startsWith(file.name + ".unreadable.") }.orEmpty()

    /** TEST ONLY: [p] kept in the book and its file, as a trade it made is. */
    internal suspend fun seedForTest(p: Pos) { lock.withLock { val b = book(); b.positions += p; save(b) } }

    private fun publish(b: Book) {
        _view.value = View(b.armed, b.status.shown(), b.positions.filter { it.open }, b.positions.filter { !it.open }.takeLast(20),
            b.log.takeLast(30))
        // The trade manager's one call (10 Oct): SHADOW by default - it records only; this arm's own 09:16 sale stays the exit.
        runCatching { val today = Market.today(); TradeManagerHost.sync(b.positions.filter { it.open || it.exitTime?.toLocalDate() == today }.map { managed(it) }, owner = "night") }
    }

    /** When a position bought on [entryDay] is out by: 09:16 of the next trading session (a Friday's is Monday's), as the arm's own sale. */
    internal fun outBy(entryDay: LocalDate): LocalDateTime = nextSession(entryDay).atTime(9, 16)

    /** [p]'s id for the trade manager. */
    private fun managerId(p: Pos): String = "night:${p.symbol}:${p.entryTime}"

    private fun ms(t: LocalDateTime): Long = t.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()

    /** [p] as the trade manager follows it: a bought option, no stop or target on its premium, out by the next session's 09:16. */
    private fun managed(p: Pos) = TradeManagerHost.ArmTrade("night", managerId(p), NightRules.LABEL, p.index, if (p.right == "PE") -1 else 1,
        p.symbol, p.entry, p.qty, null, null, ms(outBy(p.entryDay)), live = false, entryMs = ms(p.entryTime),
        exit = p.exit, exitWhy = p.why, exitMs = p.exitTime?.let { ms(it) })

    private fun note(b: Book, text: String) {
        b.log += "${LocalDateTime.now(com.optionslab.engine.IST).withNano(0)} $text"
        while (b.log.size > LOG_KEPT) b.log.removeAt(0)
        runCatching { Diag.record("night", "${NightRules.LABEL}: $text") }
    }

    /** Read the book (the row calls it when shown). */
    suspend fun refresh(): View { if (ready()) lock.withLock { book() }; return _view.value }

    /** Whether the arm holds [symbol] overnight now (read without the lock; the expiry square-off leaves it to its 09:16 sale). */
    fun holds(symbol: String): Boolean = _view.value.open.any { it.symbol == symbol }

    /** Switch it on or off (paper only, always). Off never sells what it holds: that still goes at 09:16. */
    suspend fun setArmed(on: Boolean): String {
        if (!ready()) return "Night (R3) is not ready yet."
        // 15 Oct (Boss's cleanup): hidden, it is switched on only by its restore from the Hidden arms list.
        if (on && !HiddenArms.mayArm(com.optionslab.engine.orb.ArmsCleanup.NIGHT)) return HiddenArms.refusal(NightRules.LABEL)
        return lock.withLock {
            val b = book()
            if (b.armed == on) return@withLock "${NightRules.LABEL} is already ${if (on) "on" else "off"}."
            b.armed = on
            note(b, if (on) "armed on paper (not proven; ${NightRules.RECORD})" else "switched off" +
                if (b.positions.any { it.open }) "; the open position is still sold at 09:16" else "")
            save(b)
            if (on) "${NightRules.LABEL} armed on paper: ${NightRules.RULES}. ${NightRules.RECORD}." else "${NightRules.LABEL} switched off."
        }
    }

    /** The diagnostics line. */
    fun diagLine(): String {
        val v = _view.value
        val closed = v.closed
        return "Night (R3): ${if (v.armed) "armed (paper only)" else "off"} · open ${v.open.size} · last ${closed.size} closed " +
            "%,.0f after charges".format(Locale.ENGLISH, closed.sumOf { it.net ?: 0.0 }) +
            (v.status.entries.joinToString("") { (k, s) -> " · $k $s" })
    }

    // ---- the pass ---------------------------------------------------------------------------------------------------

    /** Called by the market watch (the full pass and the bar-close wake): the 09:16 sales, then 15:20's decisions. */
    suspend fun tick() {
        if (!ready() || !Market.isTradingDay()) return
        lock.withLock {
            val b = book()
            val now = Market.now().toLocalDateTime()
            // 16 Oct: a failure part-way still saves what was sold before it (a sale booked in the book must reach its file).
            var changed = runCatching { exits(b, now) }.getOrElse { runCatching { Diag.record("night", "exit check failed: ${it.javaClass.simpleName}") }; true }
            // The strategy lab (11 Oct): Night ran today, on paper (one map write: no setting, no I/O).
            if (b.armed) VariantRunner.saw(NightRules.SOURCE, live = false)
            // 25 Oct (Boss's decision on the arms audit): while hidden (switched off by the 15 Oct cleanup) it still reads the day's
            // OI build-up at 15:20 and keeps its history ([decide] returns before any entry), so a restore is judged against a current
            // window, not a stale one. It buys nothing while hidden.
            val hidden = !b.armed && runCatching { HiddenArms.blocks(com.optionslab.engine.orb.ArmsCleanup.NIGHT) }.getOrDefault(false)
            if ((b.armed || hidden) && NightRules.entryWindow(now.toLocalTime())) {
                for (u in NightRules.UNDERLYINGS) {
                    val key = "${now.toLocalDate()}|$u"
                    if (key in b.decided) continue
                    b.decided += key
                    val why = runCatching { decide(b, u, now) }.getOrElse { "error: ${it.javaClass.simpleName}" }
                    // The 15:19 minute not in yet (a look at the close itself, before the feed or the stream has it): looked at
                    // again on the next check, not decided on the minute before.
                    if (why == WAITING) { b.decided -= key; continue }
                    b.status[u] = why
                    note(b, "$u ${now.toLocalTime().withNano(0)}: $why")
                    changed = true
                }
            }
            if (changed) save(b)
        }
    }

    /** The 09:16 sale of every open position bought on an earlier session. True when [b] changed. */
    private suspend fun exits(b: Book, now: LocalDateTime): Boolean {
        var changed = false
        for ((i, p) in b.positions.withIndex().toList()) {
            // The trade manager wants it out sooner: only when Boss switched this arm to ACT (SHADOW by default: never here).
            val manager = if (p.open) TradeManagerHost.exitDue(managerId(p)) else null
            if (!p.open || (!NightRules.exitDue(p.entryDay, now) && manager == null)) continue
            val c = Paper.contractOf(p.symbol) ?: continue
            // Gone from the paper book without this arm selling it (Boss's close, an exit-all): booked as closed elsewhere at
            // that sale, never sold again - a SELL on a flat book opens a naked short nothing manages (18 Oct, review).
            val held = Paper.state.positions.filter { it.symbol == p.symbol }.sumOf { it.quantity }
            val gone = closedElsewhere(p, held, Paper.state.trades, now)
            if (gone != null) {
                b.positions[i] = gone
                runCatching { ArmLive.closed(mirrorRef(p), "closed_elsewhere") }
                b.status[p.index] = "closed elsewhere"
                note(b, "${p.symbol}: no longer held on paper (closed elsewhere), %s after charges".format(Locale.ENGLISH, rs(gone.net ?: 0.0)))
                changed = true
                continue
            }
            val lots = (sellQty(p.qty, held) / c.lotSize.coerceAtLeast(1)).coerceAtLeast(1)
            // 22 Oct (arms audit): an exit, so the paper book too only ever reduces what it holds (the clamp above kept, the book's check beside it).
            val r = Paper.place(c, "SELL", lots, "MARKET", "NRML", null, null, exit = true)
            val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
            if (fill == null) {
                r.orderId?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
                if (!b.status.sameToday(p.index, "night_exit_retry")) { b.status[p.index] = "night_exit_retry"; note(b, "${p.symbol}: the 09:16 sale did not fill (${r.message}); tried again"); changed = true }
                continue
            }
            r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${NightRules.LABEL} · exit") } }
            val charges = p.charges + chargesOf(r.orderId)
            val done = p.copy(exit = fill.price, exitTime = now, why = if (NightRules.exitDue(p.entryDay, now)) "night_exit_0916" else "trade_manager", charges = charges)
            b.positions[i] = done
            // 18 Oct: its live trade, if any, is sold now too ([ArmLive]; memory only).
            runCatching { ArmLive.closed(mirrorRef(p), done.why ?: "night_exit_0916") }
            b.status[p.index] = "sold at 09:16"
            note(b, "${p.symbol}: sold ${fill.quantity} @ %.2f, %s after charges".format(Locale.ENGLISH, fill.price, rs(done.net ?: 0.0)))
            runCatching { Notifier.post(app, 6950 + NightRules.UNDERLYINGS.indexOf(p.index), Notifier.SELL, "${NightRules.LABEL} sold ${p.symbol} · Paper",
                "Sold ${fill.quantity} @ %.2f at 09:16: %s after charges.".format(Locale.ENGLISH, fill.price, rs(done.net ?: 0.0)), "almanac") }
            changed = true
        }
        // Closed trades beyond what is kept are dropped (oldest first); open ones always stay.
        val closed = b.positions.count { !it.open }
        if (closed > CLOSED_KEPT) {
            var drop = closed - CLOSED_KEPT
            b.positions.removeAll { !it.open && drop-- > 0 }
            changed = true
        }
        return changed
    }

    /**
     * [p] booked as closed elsewhere when the paper book holds none of it ([held] <= 0): at the last SELL of its symbol since its
     * entry in [trades] (its entry price and [now] when none is found), that sale's charges added. Null while it is still held.
     */
    internal fun closedElsewhere(p: Pos, held: Int, trades: List<com.optionslab.engine.sandbox.Trade>, now: LocalDateTime): Pos? {
        if (held > 0) return null
        val t = trades.lastOrNull { it.symbol == p.symbol && it.action == "SELL" && !it.timestamp.isBefore(p.entryTime) }
        return p.copy(exit = t?.price?.toDouble() ?: p.entry, exitTime = t?.timestamp ?: now, why = "closed_elsewhere",
            charges = p.charges + (t?.charges?.toDouble() ?: 0.0))
    }

    /** The quantity the 09:16 sale sells: the arm's [qty], never more than the paper book still [held] (18 Oct, review). */
    internal fun sellQty(qty: Int, held: Int): Int = minOf(qty, held.coerceAtLeast(0))

    private fun chargesOf(orderId: String?): Double =
        if (orderId == null) 0.0 else Paper.state.trades.filter { it.orderId == orderId }.sumOf { it.charges.toDouble() }

    private fun rs(x: Double) = (if (x < 0) "-Rs " else "+Rs ") + "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))

    /** The next trading session after [d] (holidays and weekends skipped). */
    private fun nextSession(d: LocalDate): LocalDate {
        var n = d.plusDays(1)
        repeat(14) { if (Market.isTradingDay(n)) return n; n = n.plusDays(1) }
        return n
    }

    /** TEST ONLY: the day's OI build-up for an index instead of the option feed's (null in the app; debug builds only). */
    @Volatile internal var testBuildUp: ((String) -> Double?)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }; field = v }

    /** TEST ONLY: [u]'s OI history length (the seed plus each day recorded). */
    internal suspend fun historySizeForTest(u: String): Int = lock.withLock { book().history[u]?.size ?: NightRules.SEED.getValue(u).size }

    /** 25 Oct: the day's verdict while hidden: no entry, the OI history kept up to date. */
    internal const val HIDDEN_OI = "hidden (your 15 Oct cleanup): no new entry; its OI history was updated"

    /** [decide]'s word for "the 15:19 minute is not in yet": nothing decided, looked at again (until 15:20:30). */
    private const val WAITING = "night_waiting_for_1519"

    /** 15:20's decision for [u]: what it did, in a few words. Records the day's build-up once. */
    private suspend fun decide(b: Book, u: String, now: LocalDateTime): String {
        // 15 Oct (Boss's cleanup): hidden, it buys nothing new; what it holds is still sold at 09:16 (memory only).
        // 25 Oct (the arms audit): but its OI history is still updated (below), so it is never judged against a stale window.
        val hidden = runCatching { HiddenArms.blocks(com.optionslab.engine.orb.ArmsCleanup.NIGHT) }.getOrDefault(true)
        val today = now.toLocalDate()
        // The index's minutes to 15:19, and the previous session's close.
        val key = Upstox.INDEX_KEYS[u] ?: return "night_no_index_data"
        // Round 2: the feed's minutes with the ones it has not published yet built from the stream when whole and gap-free.
        val nowSec = now.atZone(com.optionslab.engine.IST).toEpochSecond()
        val feedMins = Net.intraday(key).filter { it.istDate == today && it.epochSecond + 60 <= nowSec }.sortedBy { it.epochSecond }
        val mins = LocalCandles.overlay(key, feedMins, nowSec).filter { it.istDate == today && it.istMinute <= 15 * 60 + 19 }
        val last = mins.lastOrNull() ?: return "night_no_index_data"
        if (last.istMinute < 15 * 60 + 19 && now.toLocalTime().isBefore(java.time.LocalTime.of(15, 20, 30))) return WAITING
        // 25 Oct (the arms audit): hidden, the day's OI build-up is still recorded (its history kept current), then nothing more.
        if (hidden) {
            val listedH = Market.contracts().filter { it.underlying == u }
            val nearH = com.optionslab.engine.orb.OrbRules.expiryOnOrAfter(today, listedH.map { it.expiry }.distinct())
            val buH = nearH?.let { testBuildUp?.invoke(u) ?: buildUp(listedH.filter { c -> c.expiry == it }, last.close, u, today) }
            if (buH != null) b.history.getOrPut(u) { NightRules.SEED.getValue(u).toMutableList() } += buH
            return HIDDEN_OI + (if (buH == null) " (no build-up read today)" else "")
        }
        val prev = Net.history(key, today.minusDays(10), today.minusDays(1)).filter { it.istDate.isBefore(today) }.maxByOrNull { it.epochSecond }
            ?: return "night_no_previous_close"
        val x = last.close
        val loc = NightRules.loc(x, mins.maxOf { it.high }, mins.minOf { it.low })
        val move = NightRules.dayMove(x, prev.close)
        // The day-end build-up of the nearest series, z-scored against the sessions before (recorded once a day, traded or not).
        val listed = Market.contracts().filter { it.underlying == u }
        val near = com.optionslab.engine.orb.OrbRules.expiryOnOrAfter(today, listed.map { it.expiry }.distinct())
        val bu = near?.let { testBuildUp?.invoke(u) ?: buildUp(listed.filter { c -> c.expiry == it }, x, u, today) }
        val hist = b.history.getOrPut(u) { NightRules.SEED.getValue(u).toMutableList() }
        val z = bu?.let { NightRules.z(it, hist) }
        if (bu != null) hist += bu
        // Breadth from Zerodha's quotes (logged in only).
        val breadth = if (!Broker.loggedIn) null else runCatching {
            val q = Broker.lastAndPrevClose(NightRules.BREADTH_UNIVERSE.map { "NSE:$it" })
            NightRules.breadth(q.values.toList())
        }.getOrNull()
        val d = NightRules.decide(loc, move, breadth, z)
        val facts = "loc %.2f, day %+.2f%%, breadth %s, OI z %s".format(Locale.ENGLISH, loc, move * 100,
            breadth?.let { "%.0f%%".format(Locale.ENGLISH, it * 100) } ?: "not read (needs Zerodha login)",
            z?.let { "%+.2f".format(Locale.ENGLISH, it) } ?: "not known")
        if (d.side == 0) return "${d.why} ($facts)"
        // Guards for a new entry: the bot stopped for today, the kill switch, the account day lock.
        if (runCatching { Strategies.stoppedToday() }.getOrDefault(false)) return "night_stopped_for_today ($facts)"
        if (runCatching { AppSettings.load().guardKill }.getOrDefault(true)) return "night_kill_switch ($facts)"
        DayLockGuard.refusal(false)?.let { return "night_day_lock: $it" }
        val expiry = NightRules.expiry(today, nextSession(today), listed.map { it.expiry }.distinct()) ?: return "night_expiry_rule ($facts)"
        val strike = NightRules.strike(x, u, d.side)
        val right = if (d.side > 0) Right.CE else Right.PE
        val c = Paper.contractFor(u, expiry, strike.toDouble(), right) ?: return "night_no_contract $u $strike ${right.name} ($facts)"
        // The thin-option gate: an option that barely trades is not bought.
        val minutes = runCatching { Paper.minutes(c) }.getOrNull()?.map { it.epochSecond to it.volume }
        com.optionslab.engine.risk.ThinOption.refusal(minutes, System.currentTimeMillis() / 1000, c.lotSize, null, null)
            ?.let { return "night_thin_option: $it" }
        // The order flow at the decision (9 Oct; paper only, as the arm): logged; under CONFIRM skipped unless the flow agrees.
        val flow = FlowGate.gate(NightRules.SOURCE, u, d.side, false, now.withSecond(0).withNano(0).toString(), right.name, contract = c)
        if (flow.skip) return "${flow.pausedWhy ?: FlowGate.SKIPPED} ($facts)"
        runCatching { LiveRecorder.signal(NightRules.LABEL, c.symbol, u, right.name, null, paper = true) }
        val r = Paper.place(c, "BUY", 1, "MARKET", "NRML", null, null, auto = NightRules.LABEL)
        val fill = r.events.filterIsInstance<SandboxEvent.Fill>().firstOrNull()
        if (fill == null) {
            r.orderId?.let { id -> runCatching { Paper.cancel(id, "unfilled_market") } }
            return "night_not_filled: ${r.message}"
        }
        r.orderId?.let { runCatching { Strategies.tagOwner("paper:$it", "${NightRules.LABEL} · entry") } }
        FlowGate.taken(flow, r.orderId)
        val pos = Pos(u, c.symbol, right.name, fill.quantity, fill.price, today, now, r.orderId, charges = chargesOf(r.orderId))
        b.positions += pos
        // 18 Oct: its live path ([ArmLive]): the same option and lot, NRML, sold at 09:16 with its paper trade (the rule has no stop).
        runCatching { ArmLive.entered(ArmLive.Ask(NightRules.SOURCE, mirrorRef(pos), c, 1, fill.quantity, fill.price)) }
        // 22 Oct: the time machine replays intraday entries only; an overnight trade is noted as such (shadow only; background).
        runCatching { TimeMachineHost.afterEntry(TimeMachineHost.Entry(NightRules.SOURCE, NightRules.LABEL, mirrorRef(pos), u, if (right == Right.CE) 1 else -1, now,
            null, null, exitAt = null, skip = "held overnight by design: no intraday replay")) }
        runCatching { Notifier.post(app, 6950 + NightRules.UNDERLYINGS.indexOf(u), Notifier.BUY, "${NightRules.LABEL} bought ${c.symbol} · Paper",
            "Bought ${fill.quantity} @ %.2f on a strong close (%s). Sells at 09:16 next session. Paper only, not proven.".format(Locale.ENGLISH, fill.price, facts), "almanac") }
        // Text goes in as %s arguments (14 Oct: [facts] always holds a "%" - "day +1.20%" - and broke this line on every buy).
        return "%s %s @ %.2f (%s)".format(Locale.ENGLISH, d.why, c.symbol, fill.price, facts)
    }

    /**
     * h26's BUopen at 15:19 for [u]'s nearest series [series]: ATM +-2 strikes around the 15:19 close [x] (np.round), each
     * call and put's close and OI at 09:15 and at 15:19 (the last minute to 15:19) from the paper feed's minutes.
     */
    private suspend fun buildUp(series: List<Upstox.Contract>, x: Double, u: String, today: LocalDate): Double? {
        val step = NightRules.step(u)
        val atm = Math.rint(x / step) * step
        val strikes = (-2..2).map { atm + it * step }
        suspend fun leg(c: Upstox.Contract?): NightRules.Leg {
            if (c == null) return NightRules.Leg(null, null, null, null)
            val bars = runCatching { Net.intraday(c.instrumentKey) }.getOrDefault(emptyList())
                .filter { it.istDate == today && it.istMinute <= 15 * 60 + 19 }.sortedBy { it.epochSecond }
            val first = bars.firstOrNull { it.istMinute == 9 * 60 + 15 }
            val lastBar = bars.lastOrNull()
            return NightRules.Leg(first?.close, lastBar?.close, first?.oi?.toDouble(), lastBar?.oi?.toDouble())
        }
        val rows = ArrayList<NightRules.Strike>()
        for (k in strikes) {
            val ce = series.firstOrNull { it.strike == k && it.right == Right.CE }
            val pe = series.firstOrNull { it.strike == k && it.right == Right.PE }
            rows += NightRules.Strike(leg(ce), leg(pe))
        }
        return NightRules.buildUp(rows)
    }
}
