package com.optionslab.app.data

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.optionslab.app.security.Vault
import com.optionslab.app.work.NoticeCard
import com.optionslab.app.work.Notifier
import com.optionslab.engine.IST
import com.optionslab.engine.Kite
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.PassedArms
import com.optionslab.engine.trend.TrendTurn
import com.optionslab.engine.trend.TrendWatch
import com.optionslab.engine.trend.TrendWatch.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The Trend watch (16 Oct 2026, Boss: "check the current trend, and if the trend changes, sell the orders ... for all the
 * arms and orders"; research R63, research/HUNT_R63_TREND_TURN_EXIT.md; the rules and the policy are [TrendWatch] and
 * [TrendTurn]). Every open position and pending entry order of every source - every arm, Solo, Jarvis's orders, the
 * Telegram tester, the MCX arms and Boss's own orders, on paper and (with its switch) on Zerodha - is judged on its
 * UNDERLYING's completed bars about once a minute:
 *
 *  - the Hero ₹1,000 ticket's paper legs: T3 PASSED, so a turn asks the arm to sell that leg ([sellRequested]: memory only;
 *    the arm sells it through its own exit in the watch's minute lane) - logged in Autopilot's change timeline, one tap
 *    undoes it (the Hero trend exit off; a sold leg is not bought back);
 *  - everything else: a note "trend turned against <source> <symbol> at hh:mm: would sell at <premium>", and a quiet alert
 *    only with its switch on (off by default; nothing about the account on a locked phone). Never an order;
 *  - Zerodha positions: notes only, and only with "Live positions too" on (OFF by default). Never an order, never a cancel;
 *  - a pending entry order whose side the trend turned against: a "would cancel" note only.
 *
 * Never on the order or tick path: it runs in Jarvis's words worker ([watch]), after the positions are restored at start-up
 * ([PositionsFirst.awaitPositions]); the Hero arm reads only [sellRequested] (a map in memory). Its reads are the passed arms'
 * shared index minutes (one read a minute an index), the market watch's shared Zerodha book ([Broker.passPositionBook]) and,
 * for an MCX or stock underlying, that instrument's own minutes - never a stop, target, trail or square-off's. Its log is its
 * own encrypted file, not in the backup (it names positions and premiums), the last [KEEP] lines.
 */
object TrendWatchHost {
    const val KEEP = 500
    private const val FILE = "trend_watch.vault"
    /** Its quiet alerts: 7300 + a slot. */
    private const val NOTICE = 7300
    @Volatile private var file: File? = null
    @Volatile private var app: Context? = null
    private val written = Vault.LastWrite()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        app = context.applicationContext
        file = File(context.applicationContext.noBackupFilesDir, FILE)
    }

    /** One line of the log: what turned, when, and what was done ([TrendWatch.Act]). */
    data class Rec(val key: String, val day: LocalDate, val source: Source, val symbol: String, val live: Boolean, val rule: String,
                   val at: LocalDateTime, val act: String, val line: String)

    /** A held position as the row's dialog shows it: its source, symbol and its rule's state now. */
    data class Watched(val source: Source, val symbol: String, val live: Boolean, val rule: String, val state: String, val act: String)

    data class View(val sw: TrendWatch.Switches = TrendWatch.Switches(), val status: String = TrendWatch.status(TrendWatch.Switches(), 0, 0, 0),
                    val watched: List<Watched> = emptyList(), val recent: List<Rec> = emptyList(), val loaded: Boolean = false)

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    private val lock = Any()
    private class Log(var sw: TrendWatch.Switches = TrendWatch.Switches(), val recs: MutableList<Rec> = ArrayList(),
                      val told: MutableSet<String> = HashSet(), val migrated: MutableSet<String> = HashSet())
    private var cache: Log? = null

    /** The Hero legs the watch asked the arm to sell: symbol -> when (memory only; read on the minute lane, so no file here). */
    private val sells = ConcurrentHashMap<String, LocalDateTime>()

    /** A pending order's note ([Rec.act]): never counted as a position that turned. */
    const val PENDING_ACT = "PENDING"

    /** The one-time switch-on of the Hero ticket's trend exit under the PAPER rule (logged once in Autopilot's change log). */
    internal const val AUTO_ON = "hero_trend_exit_2026-10-16"

    // ---- the log -------------------------------------------------------------------------------------------------------

    private fun swJson(s: TrendWatch.Switches) = JSONObject().put("on", s.on).put("live", s.live).put("alerts", s.alerts)
        .put("act", JSONObject().apply { s.act.forEach { (k, v) -> put(k.key, v) } })

    private fun swOf(o: JSONObject?): TrendWatch.Switches {
        if (o == null) return TrendWatch.Switches()
        val act = HashMap<Source, Boolean>(TrendWatch.Switches().act)
        o.optJSONObject("act")?.let { a -> a.keys().forEach { k -> Source.of(k)?.let { act[it] = a.getBoolean(k) } } }
        return TrendWatch.Switches(o.optBoolean("on", true), o.optBoolean("live", false), o.optBoolean("alerts", false), act)
    }

    private fun recJson(r: Rec) = JSONObject().put("k", r.key).put("d", r.day.toString()).put("s", r.source.key).put("y", r.symbol)
        .put("l", r.live).put("r", r.rule).put("a", r.at.toString()).put("x", r.act).put("t", r.line)

    private fun recOf(o: JSONObject): Rec? = runCatching {
        Rec(o.getString("k"), LocalDate.parse(o.getString("d")), Source.of(o.getString("s")) ?: return null, o.getString("y"), o.getBoolean("l"),
            o.getString("r"), LocalDateTime.parse(o.getString("a")), o.getString("x"), o.getString("t"))
    }.getOrNull()

    private fun log(): Log {
        cache?.let { return it }
        val l = Log()
        val f = file
        val ok = f == null || runCatching {
            val o = JSONObject(String(Vault.readFileSteady(f) ?: return@runCatching, Charsets.UTF_8))
            l.sw = swOf(o.optJSONObject("sw"))
            o.optJSONArray("recs")?.let { a -> for (i in 0 until a.length()) recOf(a.getJSONObject(i))?.let { l.recs += it } }
            o.optJSONArray("told")?.let { a -> for (i in 0 until a.length()) l.told += a.getString(i) }
            o.optJSONArray("migrated")?.let { a -> for (i in 0 until a.length()) l.migrated += a.getString(i) }
        }.isSuccess
        if (!ok && f != null && f.exists()) {
            runCatching { Vault.setAside(f) }
            // Unreadable (never silently back on): the Hero exit starts OFF and the one-time switch-on is not logged again.
            l.sw = l.sw.copy(act = l.sw.act + (Source.HERO_TICKET to false))
            l.migrated += AUTO_ON
            runCatching { Diag.record("trend watch", "its file could not be read: set aside; the Hero ticket's trend exit starts off") }
        }
        cache = l
        // The Hero ticket's trend exit passed research: on by the update under the PAPER rule, logged once as Boss's ask.
        if (AUTO_ON !in l.migrated) {
            l.migrated += AUTO_ON
            runCatching { com.optionslab.app.ira.Autopilot.logTrendExitOn(Source.HERO_TICKET.key, Source.HERO_TICKET.label,
                "T3 (${TrendTurn.Rule.T3.label})", TrendWatch.RECORD) }
            save(l)
        }
        publish(l, _view.value.watched)
        return l
    }

    private fun save(l: Log) {
        while (l.recs.size > KEEP) l.recs.removeAt(0)
        // "told" keys are per day: only today's and yesterday's are kept.
        val keep = setOf(today().toString(), today().minusDays(1).toString())
        l.told.retainAll { it.substringBefore('#') in keep }
        val f = file
        if (f != null) runCatching {
            val o = JSONObject().put("sw", swJson(l.sw)).put("recs", JSONArray(l.recs.map { recJson(it) }))
                .put("told", JSONArray(l.told.sorted())).put("migrated", JSONArray(l.migrated.sorted()))
            written.write(f, o.toString().toByteArray(Charsets.UTF_8))
        }
        publish(l, _view.value.watched)
    }

    private fun today(): LocalDate = runCatching { Market.today() }.getOrElse { LocalDate.now(IST) }

    private fun publish(l: Log, watched: List<Watched>) {
        val d = today()
        val todays = l.recs.filter { it.day == d && it.act != PENDING_ACT }
        _view.value = View(l.sw, TrendWatch.status(l.sw, watched.size, todays.size, todays.count { it.act == TrendWatch.Act.SELL.name }),
            watched, l.recs.takeLast(40), loaded = true)
    }

    // ---- the switches ----------------------------------------------------------------------------------------------------

    /** The switches, after one read of the log (call off the main thread). */
    fun switches(): TrendWatch.Switches = synchronized(lock) { log().sw }

    /** The row's switch: off, nothing is watched or noted (and no Hero leg is sold on a turn). */
    fun setOn(on: Boolean): String {
        val heroChanged = synchronized(lock) {
            val l = log()
            val changed = l.sw.on != on
            if (changed) { l.sw = l.sw.copy(on = on); save(l) }
            if (!on) sells.clear()
            changed && l.sw.acts(Source.HERO_TICKET)
        }
        // The Hero ticket's paper exit goes with the main switch: logged in the change timeline like its own switch.
        if (heroChanged) runCatching { com.optionslab.app.ira.Autopilot.trendExitSwitchedByBoss(Source.HERO_TICKET.key, Source.HERO_TICKET.label, on) }
        return if (on) "${TrendWatch.LABEL} on: notes for every open position; the Hero ticket's legs sold on paper on a T3 turn." else "${TrendWatch.LABEL} off."
    }

    /** "Live positions too" (notes and alerts only, never an order): off by default. */
    fun setLive(on: Boolean): String = synchronized(lock) {
        val l = log()
        if (l.sw.live != on) { l.sw = l.sw.copy(live = on); save(l) }
        if (on) "Zerodha positions are watched too - notes and alerts only, never an order." else "Zerodha positions are not watched."
    }

    /** Quiet alerts on a turn (the note is written either way): off by default. */
    fun setAlerts(on: Boolean): String = synchronized(lock) {
        val l = log()
        if (l.sw.alerts != on) { l.sw = l.sw.copy(alerts = on); save(l) }
        if (on) "A quiet alert on each turn (nothing about the account on a locked phone)." else "No alerts; the notes are still written."
    }

    /**
     * May [s] sell on paper on a turn? Only a source whose rule PASSED can ([TrendWatch.canAct]): today only the Hero ticket;
     * any other is refused and stays a note. [log]: Boss's flip (logged in the change timeline); false for its own Undo.
     */
    fun setAct(s: Source, on: Boolean, log: Boolean = true): String {
        if (on && !TrendWatch.canAct(s)) return "${s.label}: the trend rule did not pass research for it (R63), so it only writes notes."
        val changed = synchronized(lock) {
            val l = log()
            val was = l.sw.acts(s)
            if (was != on) { l.sw = l.sw.copy(act = l.sw.act + (s to on)); save(l) }
            if (!on) sells.keys.removeAll { true }
            was != on
        }
        if (changed && log) runCatching { com.optionslab.app.ira.Autopilot.trendExitSwitchedByBoss(s.key, s.label, on) }
        return if (on) "${s.label}: legs sold on paper when the trend turns (passed research R63)." else "${s.label}: trend turns are notes only now."
    }

    // ---- the Hero arm's question (the minute lane: memory only) -----------------------------------------------------------

    /** Did the watch ask to sell [symbol] (a Hero ticket leg) on a turn? Memory only: no file, no network, no prefs. */
    fun sellRequested(symbol: String): Boolean = sells.containsKey(symbol)

    /**
     * The Hero arm sold [symbol] on the trend turn ([line]: what it booked): the request forgotten, the sale logged (its own
     * file and Autopilot's change log in the background - never on the minute lane's time).
     */
    fun sold(symbol: String, line: String) {
        sells.remove(symbol)
        background.launch {
            runCatching { com.optionslab.app.ira.Autopilot.logTrendExitSold(Source.HERO_TICKET.key, Source.HERO_TICKET.label, line) }
            runCatching { Diag.record("trend watch", "${Source.HERO_TICKET.label} $symbol sold on paper on the trend turn (T3)") }
        }
    }

    // ---- the round -------------------------------------------------------------------------------------------------------

    /** One round of the words lane. Never throws. */
    suspend fun watch() {
        if (com.optionslab.app.BuildConfig.GOLD) return
        // Never in the unit tests' other passes (their Autopilot books and reads are their own); its tests set [testMinutes].
        if (android.os.Build.FINGERPRINT == "robolectric" && testMinutes == null) return
        val now = Market.now().toLocalDateTime().withNano(0)
        if (!runCatching { Market.isTradingDay(now.toLocalDate()) }.getOrDefault(false) || now.toLocalTime().isBefore(START)) return
        // Restored positions first (start-up order): the watch only reads them once they are.
        runCatching { PositionsFirst.awaitPositions() }
        if (!switches().on) { sells.clear(); synchronized(lock) { publish(log(), emptyList()) }; return }
        runCatching { round(now) }.onFailure { runCatching { Diag.record("trend watch", "round failed: ${it.javaClass.simpleName}") } }
    }

    private val START = LocalTime.of(9, 20)

    /** TEST SEAMS: an underlying's minutes, the Zerodha book and its opening fills (null: the real reads). */
    @Volatile internal var testMinutes: ((String, LocalDateTime) -> List<Bar>)? = null
    @Volatile internal var testLive: (() -> List<LiveHeld>)? = null

    /** A Zerodha position as the watch needs it (from the market watch's shared book read). */
    data class LiveHeld(val symbol: String, val exchange: String, val qty: Int, val last: Double?, val label: String?, val fill: LocalDateTime?)

    /** One round at [now] (tests call it directly). */
    internal suspend fun round(now: LocalDateTime) {
        val sw = switches()
        // A request for a leg the Hero arm no longer holds (sold, its 15:05, closed by Boss) is forgotten.
        sells.keys.removeAll { heroGone(it) }
        val held = paperHeld(now) + if (sw.live) liveHeld(now) else emptyList()
        val minutes = HashMap<String, List<Bar>>()
        suspend fun bars(h: TrendWatch.Held): List<Bar> = minutes.getOrPut(h.underlying + "|" + h.mcx) {
            runCatching { underlyingMinutes(h.underlying, h.mcx, now) }.getOrDefault(emptyList())
        }
        val watched = ArrayList<Watched>()
        val found = ArrayList<Triple<TrendWatch.Held, TrendWatch.Verdict, TrendWatch.Act>>()
        for (h in held) {
            val act = TrendWatch.act(h, sw)
            if (act == TrendWatch.Act.NONE) continue
            val v = TrendWatch.judge(h, bars(h), now)
            watched += Watched(h.source, h.symbol, h.live, v.rule.key, v.state, act.name)
            if (v.at != null) found += Triple(h, v, act)
        }
        val pending = runCatching { pendingNotes(now) { h -> bars(h) } }.getOrDefault(emptyList()) +
            if (sw.live) runCatching { livePendingNotes(now) { h -> bars(h) } }.getOrDefault(emptyList()) else emptyList()
        val new = ArrayList<Rec>()
        synchronized(lock) {
            val l = log()
            val d = now.toLocalDate()
            for ((h, v, act0) in found) {
                // The switches as they are now (Boss may have switched the exit off during the round's reads), and a sale only
                // while the turn is fresh: one first seen late is a note, never a late sale at a later price.
                val wanted = if (act0 == TrendWatch.Act.SELL) TrendWatch.act(h, l.sw) else act0
                val late = wanted == TrendWatch.Act.SELL && !TrendWatch.fresh(v, now)
                val act = if (late) TrendWatch.Act.NOTE else wanted
                val k = "$d#${h.key}|${v.rule.key}"
                if (k in l.told) { if (act == TrendWatch.Act.SELL && !heroGone(h.symbol)) sells.putIfAbsent(h.symbol, now); continue }
                if (act == TrendWatch.Act.NONE) continue
                l.told += k
                val line = TrendWatch.line(h, v, act, late)
                if (act == TrendWatch.Act.SELL) sells[h.symbol] = now
                val r = Rec(k, d, h.source, h.symbol, h.live, v.rule.key, v.at!!, act.name, line)
                l.recs += r; new += r
            }
            for ((k, line, src) in pending) {
                if (k in l.told) continue
                l.told += k
                val r = Rec(k, d, src.first, src.second, false, TrendWatch.SHADOW_RULE.key, now, PENDING_ACT, line)
                l.recs += r; new += r
            }
            if (new.isNotEmpty()) save(l)
            publish(l, watched)
        }
        for (r in new) {
            // The diary gets no premium: what turned, when, by which rule and what was done.
            runCatching { Diag.record("trend watch", "${r.source.label} ${r.symbol}${if (r.live) " (live)" else ""}: ${r.rule} at " +
                "${TrendWatch.hhmm(r.at)} - ${r.act.lowercase(Locale.ENGLISH)}") }
            if (sw.alerts && r.act != TrendWatch.Act.SELL.name) alert(r)
        }
        if (new.isNotEmpty() && com.optionslab.app.BuildConfig.JARVIS)
            // Information only: written in the chat, never spoken (a turn is not a trade signal).
            for (r in new) runCatching { com.optionslab.app.ira.IraHub.note(r.line) }
    }

    /** Whether the Hero arm no longer holds [symbol] (memory only). */
    private fun heroGone(symbol: String): Boolean = HeroTicketArm.view.value.open.none { it.symbol == symbol }

    /** Every open PAPER position, each with the source that opened it (its opening fill's label). */
    private suspend fun paperHeld(now: LocalDateTime): List<TrendWatch.Held> {
        val st = runCatching { Paper.state }.getOrNull() ?: return emptyList()
        val open = st.positions.filter { it.quantity != 0 }
        if (open.isEmpty()) return emptyList()
        val owners = runCatching { Strategies.owners() }.getOrDefault(emptyMap())
        val hero = HeroTicketArm.view.value.open.associateBy { it.symbol }
        return open.mapNotNull { p ->
            val c = Paper.contractOf(p.symbol) ?: return@mapNotNull null
            val fills = st.trades.filter { it.symbol == p.symbol && it.product == p.product }.sortedBy { it.timestamp }
            val opener = openingFill(fills.map { Triple(it.timestamp, it.action.equals("BUY", true), it.quantity) })
            val label = opener?.let { i -> owners["paper:${fills[i].orderId}"] }
            val src = TrendWatch.sourceOf(label)
            val mcx = c.isMcx
            val fill = opener?.let { fills[it].timestamp } ?: sessionOpen(now.toLocalDate(), mcx)
            val right = if (c.right == com.optionslab.engine.Right.IX) null else c.right.name
            val h = hero[p.symbol]
            TrendWatch.Held(src, p.symbol, c.underlying, TrendWatch.bias(right, p.quantity),
                fill.coerceAtLeast(sessionOpen(now.toLocalDate(), mcx)), live = false, premium = p.ltp?.toDouble()?.takeIf { it > 0 },
                mcx = mcx, tested = src != Source.HERO_TICKET || (h != null && TrendWatch.heroTested(h.trailStart, h.target)))
        }
    }

    /** The fill that opened what is held now: the last one that moved the running quantity away from flat (null: carried). */
    internal fun openingFill(fills: List<Triple<LocalDateTime, Boolean, Int>>): Int? {
        var running = 0
        var at: Int? = null
        for ((i, f) in fills.withIndex()) {
            val signed = if (f.second) f.third else -f.third
            if (running == 0 && signed != 0) at = i
            running += signed
            if (running == 0) at = null
        }
        return at
    }

    private fun sessionOpen(d: LocalDate, mcx: Boolean): LocalDateTime = d.atTime(if (mcx) TrendTurn.Grid.MCX.open else TrendTurn.Grid.NSE.open)

    private fun LocalDateTime.coerceAtLeast(x: LocalDateTime): LocalDateTime = if (isBefore(x)) x else this

    /** Every open Zerodha position (the market watch's shared book read; never an extra read for an order). */
    private suspend fun liveHeld(now: LocalDateTime): List<TrendWatch.Held> {
        val rows = testLive?.invoke() ?: run {
            if (!Broker.loggedIn) return emptyList()
            val book = runCatching { Broker.passPositionBook() }.getOrNull() ?: return emptyList()
            val net = book.net.filter { it.qty != 0 }
            if (net.isEmpty()) return emptyList()
            val known = liveFills(now, net.map { it.symbol }.toSet())
            net.map { p -> LiveHeld(p.symbol, p.exchange, p.qty, p.last.takeIf { it > 0 }, known[p.symbol]?.second, known[p.symbol]?.first) }
        }
        return rows.mapNotNull { p ->
            val mcx = p.exchange == "MCX"
            val und = (if (mcx) p.symbol.takeWhile { it.isLetter() } else Kite.underlyingOf(p.symbol) ?: p.symbol.takeWhile { it.isLetter() })
                .uppercase(Locale.ENGLISH).ifEmpty { return@mapNotNull null }
            val right = p.symbol.uppercase(Locale.ENGLISH).let { s -> when { s.endsWith("CE") -> "CE"; s.endsWith("PE") -> "PE"; else -> null } }
            TrendWatch.Held(TrendWatch.sourceOf(p.label), p.symbol, und, TrendWatch.bias(right, p.qty),
                (p.fill ?: sessionOpen(now.toLocalDate(), mcx)).coerceAtLeast(sessionOpen(now.toLocalDate(), mcx)), live = true, premium = p.last, mcx = mcx)
        }
    }

    /** symbol -> (opening fill time, its label), read from Zerodha's day trades once per new position (cached for the day). */
    private val liveFillCache = ConcurrentHashMap<String, Pair<LocalDateTime?, String?>>()
    @Volatile private var liveFillDay: LocalDate? = null

    private suspend fun liveFills(now: LocalDateTime, symbols: Set<String>): Map<String, Pair<LocalDateTime?, String?>> {
        if (liveFillDay != now.toLocalDate()) { liveFillCache.clear(); liveFillDay = now.toLocalDate() }
        if (symbols.any { !liveFillCache.containsKey(it) }) runCatching {
            val trades = Broker.trades()
            val orders = Broker.orders().associateBy { it.id }
            val owners = runCatching { Strategies.owners() }.getOrDefault(emptyMap())
            for (s in symbols) {
                val f = trades.filter { it.symbol == s }.sortedBy { it.at }
                val i = openingFill(f.map { Triple(parseAt(it.at, now.toLocalDate()), it.side.equals("BUY", true), it.qty) })
                val t = i?.let { f[it] }
                liveFillCache[s] = (t?.let { parseAt(it.at, now.toLocalDate()) }) to
                    (t?.let { owners["kite:${it.orderId}"] ?: Origins.fromTag(orders[it.orderId]?.tag) })
            }
        }
        return liveFillCache
    }

    private fun parseAt(s: String, d: LocalDate): LocalDateTime = runCatching { LocalDateTime.parse(s.replace(' ', 'T').take(19)) }
        .getOrElse { runCatching { d.atTime(LocalTime.parse(s.takeLast(8))) }.getOrDefault(d.atTime(9, 15)) }

    /** Pending PAPER entry orders the shadow rule has turned against: (told key, line, (source, symbol)). Notes only. */
    private suspend fun pendingNotes(now: LocalDateTime, bars: suspend (TrendWatch.Held) -> List<Bar>):
        List<Triple<String, String, Pair<Source, String>>> {
        val st = runCatching { Paper.state }.getOrNull() ?: return emptyList()
        val open = st.orders.filter { it.action.equals("BUY", true) && it.status in PENDING }
        if (open.isEmpty()) return emptyList()
        val owners = runCatching { Strategies.owners() }.getOrDefault(emptyMap())
        val out = ArrayList<Triple<String, String, Pair<Source, String>>>()
        for (o in open) {
            val c = Paper.contractOf(o.symbol) ?: continue
            val src = TrendWatch.sourceOf(owners["paper:${o.orderId}"])
            if (src == Source.EXIT_RULE) continue
            val right = if (c.right == com.optionslab.engine.Right.IX) null else c.right.name
            val probe = TrendWatch.Held(src, o.symbol, c.underlying, TrendWatch.bias(right, 1), o.orderTimestamp, false, null, c.isMcx)
            val at = TrendWatch.pendingTurned(probe.bias, o.orderTimestamp, bars(probe), now, c.isMcx) ?: continue
            out += Triple("${now.toLocalDate()}#pending|${o.orderId}|$at", TrendWatch.pendingLine(src, o.symbol, at), src to o.symbol)
        }
        return out
    }

    /** TEST SEAM: Zerodha's working orders (null: the real read). */
    @Volatile internal var testLiveOrders: (() -> List<Broker.OrderRow>)? = null
    @Volatile private var liveOrders: Pair<Long, List<Broker.OrderRow>>? = null

    /**
     * Zerodha's pending entry orders the shadow rule has turned against (only with "Live positions too" on): a "would cancel"
     * note each, never a cancel. The order book is read at most once every [LIVE_ORDERS_MS] for it (the words lane's budget).
     */
    private suspend fun livePendingNotes(now: LocalDateTime, bars: suspend (TrendWatch.Held) -> List<Bar>):
        List<Triple<String, String, Pair<Source, String>>> {
        val rows = testLiveOrders?.invoke() ?: run {
            if (!Broker.loggedIn) return emptyList()
            val ms = System.currentTimeMillis()
            liveOrders?.takeIf { ms - it.first < LIVE_ORDERS_MS }?.second ?: Broker.orders().also { liveOrders = ms to it }
        }
        val open = rows.filter { it.working && it.side.equals("BUY", true) }
        if (open.isEmpty()) return emptyList()
        val owners = runCatching { Strategies.owners() }.getOrDefault(emptyMap())
        val out = ArrayList<Triple<String, String, Pair<Source, String>>>()
        for (o in open) {
            val src = TrendWatch.sourceOf(owners["kite:${o.id}"] ?: Origins.fromTag(o.tag))
            if (src == Source.EXIT_RULE) continue
            val mcx = o.exchange == "MCX"
            val und = (if (mcx) o.symbol.takeWhile { it.isLetter() } else Kite.underlyingOf(o.symbol) ?: o.symbol.takeWhile { it.isLetter() })
                .uppercase(Locale.ENGLISH)
            if (und.isEmpty()) continue
            val right = o.symbol.uppercase(Locale.ENGLISH).let { s -> when { s.endsWith("CE") -> "CE"; s.endsWith("PE") -> "PE"; else -> null } }
            val placed = parseAt(o.placedAt, now.toLocalDate())
            val probe = TrendWatch.Held(src, o.symbol, und, TrendWatch.bias(right, 1), placed, true, null, mcx)
            val at = TrendWatch.pendingTurned(probe.bias, placed, bars(probe), now, mcx) ?: continue
            out += Triple("${now.toLocalDate()}#pending|kite|${o.id}|$at", TrendWatch.pendingLine(src, o.symbol, at) + " (Zerodha)", src to o.symbol)
        }
        return out
    }

    private const val LIVE_ORDERS_MS = 5 * 60_000L

    private val PENDING = setOf(com.optionslab.engine.sandbox.OrderStatus.OPEN, com.optionslab.engine.sandbox.OrderStatus.TRIGGER_PENDING)

    /** [u]'s finished 1-minute bars: earlier sessions (when read) and today's. Indices share the passed arms' reads. */
    private suspend fun underlyingMinutes(u: String, mcx: Boolean, now: LocalDateTime): List<Bar> {
        testMinutes?.let { return it(u, now) }
        val day = now.toLocalDate()
        if (!mcx && u in INDICES) {
            runCatching { PassedArmsHost.prefetch(now) }
            val hist = PassedArmsHost.history(u, day)?.sessions.orEmpty().flatMap { d ->
                (0 until PassedArms.Session.N).filter { !d.c[it].isNaN() }.map { r ->
                    Bar(d.date.atTime(PassedArms.Session.time(r)), d.o[r], d.h[r], d.l[r], d.c[r]) } }
            return hist + PassedArmsHost.indexBars(u, now)
        }
        val key = if (mcx) runCatching { com.optionslab.engine.mcx.McxInstruments.futures(McxMarket.cached(), u, day, 1).firstOrNull()?.upstoxKey }.getOrNull()
            else stockKey(u)
        return key?.let { minutes(it, now) }.orEmpty()
    }

    private val INDICES = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")

    /** Today's finished minutes of [key] (the feed with the stream's newer whole minutes). */
    private suspend fun minutes(key: String, now: LocalDateTime): List<Bar> {
        val nowSec = now.atZone(IST).toEpochSecond()
        val feed = Net.intradayQuick(key).filter { it.istDate == now.toLocalDate() && it.epochSecond + 60 <= nowSec }.sortedBy { it.epochSecond }
        val bars = runCatching { LocalCandles.overlay(key, feed, nowSec) }.getOrDefault(feed).filter { it.istDate == now.toLocalDate() && it.epochSecond + 60 <= nowSec }
        return bars.map { Bar(it.istDate.atTime(it.istMinute / 60, it.istMinute % 60), it.open, it.high, it.low, it.close) }.sortedBy { it.start }.distinctBy { it.start }
    }

    /** A stock's cash-market key (one master read a day per new stock, only for a held stock option or future). */
    private val stockKeys = ConcurrentHashMap<String, String>()
    private val stockTried = ConcurrentHashMap<String, LocalDate>()

    private fun stockKey(sym: String): String? {
        stockKeys[sym]?.let { return it }
        if (stockTried[sym] == today()) return null
        stockTried[sym] = today()
        return runCatching { Net.fetchEquityKeys(setOf("NSE_EQ" to sym))["NSE_EQ" to sym] }.getOrNull()?.also { stockKeys[sym] = it }
    }

    /** A quiet alert for [r]: private on a locked phone (the public version says only "IraAlgo"), no sound. */
    private fun alert(r: Rec) {
        val c = app ?: return
        if (!Notifier.canPost(c)) return
        val id = NOTICE + ((r.key.hashCode() and Int.MAX_VALUE) % 200)
        val title = "${TrendWatch.LABEL}: trend turned"
        val b = Notifier.builder(c, Notifier.HEALTH, title, r.line, "almanac",
            card = NoticeCard(id, Notifier.HEALTH, title, r.line, System.currentTimeMillis(), tab = "almanac"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.line))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        try { NotificationManagerCompat.from(c).notify(id, b.build()) } catch (_: SecurityException) {}
    }

    /** The diagnostics line. */
    fun diagLine(): String {
        val v = _view.value
        return "${TrendWatch.LABEL} (${TrendWatch.RESEARCH}): ${v.status} · alerts ${if (v.sw.alerts) "on" else "off"} · " +
            "acting: ${Source.entries.filter { v.sw.acts(it) && TrendWatch.canAct(it) }.joinToString { it.label }.ifEmpty { "none" }} · sell requests ${sells.size}"
    }

    /** Jarvis's answer to "has the trend turned on my trades?" ([com.optionslab.ira.TrendTalk]); off the main thread. */
    fun talk(): String {
        val sw = switches()
        val (turns, held) = facts()
        return com.optionslab.ira.TrendTalk.answer(com.optionslab.ira.TrendTalk.Facts(sw.on, sw.live,
            turns.map { com.optionslab.ira.TrendTalk.Turn(it.line, it.act, it.live) },
            held.map { com.optionslab.ira.TrendTalk.Held(it.source.label, it.symbol, it.rule, it.state, it.live) }))
    }

    /** For Jarvis ("has the trend turned on my trades?"): today's turns and what is watched now (after one read of the log). */
    fun facts(): Pair<List<Rec>, List<Watched>> = synchronized(lock) {
        val l = log()
        l.recs.filter { it.day == today() } to _view.value.watched
    }

    /**
     * Memory short (the last item [MemoryTrim] lets go): the log's memory (read again by the next round), the stock keys and
     * opening fills looked up. The Hero legs' sell requests stay: they are part of the position watch, never released.
     */
    fun releaseForMemory() {
        synchronized(lock) { cache = null; written.forget() }
        liveFillCache.clear(); stockKeys.clear()
    }

    /** TEST ONLY: a sell request as a SELL round leaves it. */
    internal fun requestForTest(symbol: String) { sells[symbol] = LocalDateTime.now(IST) }

    /** TEST ONLY: forget the log's memory (the file stays) and the sell requests, as a restart would. */
    internal fun resetForTest() = synchronized(lock) { cache = null; written.forget(); sells.clear(); _view.value = View(); testMinutes = null; testLive = null; testLiveOrders = null; liveOrders = null }
}
