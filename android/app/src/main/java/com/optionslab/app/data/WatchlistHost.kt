package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.Upstox
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.PassedArms
import com.optionslab.engine.orb.Watchlist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The Watchlist in SHADOW (15 Oct 2026, Boss: "Both"; the rules and the written selection rule are [Watchlist]'s): research's
 * near misses - R47 C4 (an option-chain signal: NON-DEFAULT, labelled), R52 R4-8 and R52 H45-1 - followed on the phone's own
 * data. Shadow, not traded; information only, not a trade signal: nothing here places, changes, skips or resizes an order,
 * paper or live, and nothing raises an alert that looks like a trade signal.
 *
 * In Jarvis's words lane (a worker, about a round a minute; [watch]), from 09:20 on a trading day:
 *  - each rule's signals today from the index minutes (the feed, with the stream's newer whole minutes) and the earlier
 *    sessions the passed arms' background read already holds ([PassedArmsHost.history]); C4 at 10:15 from the BANKNIFTY chain
 *    in memory ([MemoryChain]), else ONE background chain read a day (the existing [Market.liveChain], its own rate limits) and
 *    only while nothing is held ([SmartWorkers.holdingAny]) - a held position's watch is never slowed for it;
 *  - each record names the exact contract (index, expiry, strike, CE / PE) and, once its exit is due, is priced from that
 *    option's own minute bars with the app's paper cost model ([Watchlist.price]); after 15:25 its random-minute twins;
 *  - from 15:25, Jarvis's one evening line: every rule's scorecard ([Watchlist.eveningLine]); and once per rule, at 40 shadow
 *    trades, which passed on unseen data and which to drop ([Watchlist.verdictLine]). Never a promotion.
 * The log is its own encrypted file on this phone, not in the backup (its records carry rupees), the last [KEEP] records. Its switch is on its
 * card (on by default); off, nothing is recorded.
 */
object WatchlistHost {
    const val KEEP = 2000
    private const val FILE = "watchlist.vault"
    @Volatile private var file: File? = null
    private val written = Vault.LastWrite()

    fun init(context: Context) { file = File(context.applicationContext.filesDir, FILE) }

    /** One record: the signal, its contract, and once priced its outcome (and twins). */
    data class Rec(val s: Watchlist.Signal, val expiry: LocalDate?, val symbol: String?, val feedKey: String?, val lot: Int?,
                   val state: String = OPEN, val p: Watchlist.Priced? = null, val twins: List<Double> = emptyList(), val note: String? = null) {
        val final: Boolean get() = state != OPEN
        val net: Double? get() = p?.net
    }

    const val OPEN = "open"
    const val DONE = "done"
    const val NOFILL = "nofill"
    const val UNPRICED = "unpriced"

    data class View(val on: Boolean = true, val recs: List<Rec> = emptyList(), val cards: List<Watchlist.Card> = emptyList())

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    private val lock = Any()
    private class Log(var on: Boolean = true, val recs: MutableList<Rec> = ArrayList(), val pcr: MutableMap<String, Double> = java.util.TreeMap(),
                      val told: MutableSet<String> = HashSet(), var evening: String? = null, var chainTried: String? = null)
    private var cache: Log? = null

    // ---- the log -------------------------------------------------------------------------------------------------------

    private fun sigJson(s: Watchlist.Signal) = JSONObject().put("rule", s.rule.key).put("und", s.underlying).put("book", s.book).put("side", s.side)
        .put("entry", s.entry.toString()).put("strike", s.strike).put("why", s.why)

    private fun sigOf(o: JSONObject): Watchlist.Signal? {
        val r = Watchlist.Rule.of(o.getString("rule")) ?: return null
        return Watchlist.Signal(r, o.getString("und"), o.getString("book"), o.getInt("side"), LocalDateTime.parse(o.getString("entry")), o.getInt("strike"), o.optString("why"))
    }

    private fun pJson(p: Watchlist.Priced) = JSONObject().put("ea", p.entryAt.toString()).put("er", p.entryRaw).put("ef", p.entryFill).put("xa", p.exitAt.toString())
        .put("xr", p.exitRaw).put("xf", p.exitFill).put("why", p.why).put("qty", p.qty).put("ch", p.charges)

    private fun pOf(o: JSONObject?): Watchlist.Priced? = o?.let {
        Watchlist.Priced(LocalDateTime.parse(it.getString("ea")), it.getDouble("er"), it.getDouble("ef"), LocalDateTime.parse(it.getString("xa")),
            it.getDouble("xr"), it.getDouble("xf"), it.getString("why"), it.getInt("qty"), it.getDouble("ch"))
    }

    private fun recJson(r: Rec) = JSONObject().put("s", sigJson(r.s)).put("state", r.state)
        .apply {
            r.expiry?.let { put("expiry", it.toString()) }; r.symbol?.let { put("symbol", it) }; r.feedKey?.let { put("key", it) }; r.lot?.let { put("lot", it) }
            r.p?.let { put("p", pJson(it)) }; if (r.twins.isNotEmpty()) put("twins", JSONArray(r.twins)); r.note?.let { put("note", it) }
        }

    private fun recOf(o: JSONObject): Rec? {
        val s = sigOf(o.getJSONObject("s")) ?: return null
        val tw = o.optJSONArray("twins")?.let { a -> (0 until a.length()).map { a.getDouble(it) } }.orEmpty()
        return Rec(s, o.optString("expiry").ifEmpty { null }?.let { LocalDate.parse(it) }, o.optString("symbol").ifEmpty { null },
            o.optString("key").ifEmpty { null }, if (o.has("lot")) o.getInt("lot") else null, o.optString("state", OPEN), pOf(o.optJSONObject("p")), tw,
            o.optString("note").ifEmpty { null })
    }

    private fun log(): Log {
        cache?.let { return it }
        val l = Log()
        val f = file
        val ok = f == null || runCatching {
            val o = JSONObject(String(Vault.readFileSteady(f) ?: return@runCatching, Charsets.UTF_8))
            l.on = o.optBoolean("on", true)
            o.optJSONArray("recs")?.let { a -> for (i in 0 until a.length()) recOf(a.getJSONObject(i))?.let { l.recs += it } }
            o.optJSONObject("pcr")?.let { m -> m.keys().forEach { l.pcr[it] = m.getDouble(it) } }
            o.optJSONArray("told")?.let { a -> for (i in 0 until a.length()) l.told += a.getString(i) }
            l.evening = o.optString("evening").ifEmpty { null }
            l.chainTried = o.optString("chainTried").ifEmpty { null }
        }.isSuccess
        if (!ok && f != null && f.exists()) runCatching { Vault.setAside(f) }
        cache = l
        publish(l)
        return l
    }

    private fun save(l: Log) {
        while (l.recs.size > KEEP) l.recs.removeAt(0)
        while (l.pcr.size > 90) (l.pcr as java.util.TreeMap).pollFirstEntry()
        val f = file
        if (f != null) runCatching {
            val o = JSONObject().put("on", l.on).put("recs", JSONArray(l.recs.map { recJson(it) }))
                .put("pcr", JSONObject().apply { l.pcr.forEach { (k, v) -> put(k, v) } }).put("told", JSONArray(l.told.sorted()))
            l.evening?.let { o.put("evening", it) }; l.chainTried?.let { o.put("chainTried", it) }
            written.write(f, o.toString().toByteArray(Charsets.UTF_8))
        }
        publish(l)
    }

    private fun publish(l: Log) { _view.value = View(l.on, l.recs.takeLast(60), cardsOf(l)) }

    private fun cardsOf(l: Log): List<Watchlist.Card> = Watchlist.Rule.entries.map { r ->
        val done = l.recs.filter { it.s.rule == r && it.state == DONE && it.p != null }
        Watchlist.score(r, done.map { it.p!!.net }, done.map { it.twins })
    }

    /** Each rule's scorecard (memory, after one read of the log). */
    fun cards(): List<Watchlist.Card> = synchronized(lock) { cardsOf(log()) }

    /** The Watchlist's switch (on by default). */
    fun on(): Boolean = synchronized(lock) { log().on }

    /** Boss's switch on its card: off, nothing more is recorded (what is recorded stays). Called off the main thread. */
    fun setOn(on: Boolean): String = synchronized(lock) {
        val l = log()
        if (l.on != on) { l.on = on; save(l) }
        if (on) "Watchlist on (${Watchlist.NOTE})." else "Watchlist off: nothing more is recorded."
    }

    /** The diagnostics line. */
    fun diagLine(): String = synchronized(lock) {
        val l = log()
        "Watchlist (${Watchlist.NOTE}): ${if (l.on) "on" else "off"} · " + cardsOf(l).joinToString(" · ") { Watchlist.line(it) } +
            " · open ${l.recs.count { !it.final }}"
    }

    // ---- the round -----------------------------------------------------------------------------------------------------

    private val START = LocalTime.of(9, 20)
    private val EVENING = LocalTime.of(15, 25)

    /** One round of the words lane. Never throws; before 09:20, or off, it reads nothing more. */
    suspend fun watch() {
        if (com.optionslab.app.BuildConfig.GOLD) return
        val now = Market.now().toLocalDateTime().withNano(0)
        if (now.toLocalTime().isBefore(START) || !runCatching { Market.isTradingDay(now.toLocalDate()) }.getOrDefault(false)) {
            // An earlier day's record not priced (the app was killed): priced from the history feed on the next trading day.
            return
        }
        if (!on()) return
        runCatching { round(now) }.onFailure { runCatching { Diag.record("watchlist", "round failed: ${it.javaClass.simpleName}") } }
    }

    /** One round at [now] (tests call it directly). */
    internal suspend fun round(now: LocalDateTime) {
        val day = now.toLocalDate()
        runCatching { PassedArmsHost.prefetch(now) }
        val signals = ArrayList<Watchlist.Signal>()
        val bars = HashMap<String, List<Bar>>()
        suspend fun today(u: String): List<Bar>? = bars[u] ?: runCatching { indexMinutes(u, now) }.getOrNull()?.also { bars[u] = it }
        if (now.toLocalTime() < LocalTime.of(15, 11)) {
            for (u in Watchlist.Rule.R4_8.underlyings) {
                val h = PassedArmsHost.history(u, day) ?: continue
                val t = today(u) ?: continue
                signals += Watchlist.r48Signals(u, h.sessions.takeLast(5) + PassedArms.DayBars.of(day, t), day)
            }
            for (u in Watchlist.Rule.H45_1.underlyings) {
                if (expiryOn(u, day) != false) continue
                val h = PassedArmsHost.history(u, day) ?: continue
                val t = today(u) ?: continue
                val ones = h.sessions.takeLast(10).flatMap { d -> (0 until PassedArms.Session.N).filter { !d.c[it].isNaN() }.map { r ->
                    Bar(d.date.atTime(PassedArms.Session.time(r)), d.o[r], d.h[r], d.l[r], d.c[r]) } } + t
                signals += Watchlist.h45Signals(u, ones, day, now)
            }
            c4(now)?.let { signals += it }
        }
        val added = synchronized(lock) { accept(log(), signals, now) }
        price(now)
        if (!now.toLocalTime().isBefore(EVENING)) evening(now)
        if (added) synchronized(lock) { save(log()) }
    }

    /** New signals into the log, each once, under each rule's caps: R4-8 one at a time and 3 a day per index; H45-1 one at a time per book. */
    private fun accept(l: Log, signals: List<Watchlist.Signal>, now: LocalDateTime): Boolean {
        var added = false
        for (s in signals.sortedBy { it.entry }) {
            if (l.recs.any { it.s.key == s.key }) continue
            val same = l.recs.filter { it.s.rule == s.rule && it.s.entry.toLocalDate() == s.entry.toLocalDate() &&
                (if (s.rule == Watchlist.Rule.H45_1) it.s.book == s.book else it.s.underlying == s.underlying) && it.state != NOFILL }
            if (s.rule == Watchlist.Rule.R4_8 && same.size >= Watchlist.R48_PER_DAY) continue
            // One at a time: the previous one must be out before this entry (R4-8: 30 minutes; H45-1: priced so far).
            val busy = same.any { r -> when {
                r.p != null -> !r.p.exitAt.isBefore(s.entry)
                s.rule == Watchlist.Rule.R4_8 -> !r.s.entry.plusMinutes(30).isBefore(s.entry)
                else -> !r.final
            } }
            if (busy) continue
            val c = contract(s.underlying, s.entry.toLocalDate(), s.strike, s.right, s.rule != Watchlist.Rule.H45_1)
            l.recs += Rec(s, c?.expiry, c?.tradingSymbol, c?.instrumentKey, c?.lotSize, note = if (c == null) "the contract is not in the list saved on the phone" else null)
            runCatching { Diag.record("watchlist", "${s.rule.label}: shadow ${s.right} ${s.underlying} ${s.strike} at ${s.entry.toLocalTime()}; no order") }
            added = true
        }
        return added
    }

    /** The contract of [u] at [strike] [right]: the nearest listed expiry on or after [day] ([today]: may be today's). */
    private fun contract(u: String, day: LocalDate, strike: Int, right: String, today: Boolean): Upstox.Contract? {
        val list = runCatching { Market.cachedContracts()?.second }.getOrNull().orEmpty().filter { it.underlying == u }
        val exp = list.map { it.expiry }.filter { if (today) !it.isBefore(day) else it.isAfter(day) }.minOrNull() ?: return null
        return list.firstOrNull { it.expiry == exp && it.strike == strike.toDouble() && it.right.name == right }
    }

    /** [u]'s expiry today by the list saved on the phone (null: unknown). */
    private fun expiryOn(u: String, day: LocalDate): Boolean? {
        val l = runCatching { Market.cachedContracts()?.second }.getOrNull().orEmpty().filter { it.underlying == u }.map { it.expiry }.distinct()
        if (l.isEmpty()) return null
        return com.optionslab.engine.orb.HeroRules.isExpiryDay(day, l)
    }

    private suspend fun indexMinutes(u: String, now: LocalDateTime): List<Bar> {
        val key = runCatching { ChartFeed.instrumentKey(u) }.getOrNull() ?: Upstox.INDEX_KEYS.getValue(u)
        return optionBars(key, now.toLocalDate(), now)
    }

    /** [key]'s finished 1-minute bars of [day] by [now]: today's feed (with the stream's whole minutes), an earlier day's history. */
    private suspend fun optionBars(key: String, day: LocalDate, now: LocalDateTime): List<Bar> {
        val nowSec = now.atZone(com.optionslab.engine.IST).toEpochSecond()
        val raw = if (day == now.toLocalDate()) {
            val feed = Net.intradayQuick(key).filter { it.istDate == day && it.epochSecond + 60 <= nowSec }
            runCatching { LocalCandles.overlay(key, feed, nowSec) }.getOrDefault(feed)
        } else Net.history(key, day, day)
        return raw.filter { it.istDate == day && it.epochSecond + 60 <= nowSec }
            .map { Bar(it.istDate.atTime(it.istMinute / 60, it.istMinute % 60), it.open, it.high, it.low, it.close) }.sortedBy { it.start }.distinctBy { it.start }
    }

    /** C4 at 10:15 (once a day): the PCR as at 10:14, ranked; a signal in the top or bottom fifth. Null: none (or not yet). */
    private suspend fun c4(now: LocalDateTime): Watchlist.Signal? {
        val t = now.toLocalTime()
        if (t.isBefore(Watchlist.C4_AT) || t.isAfter(LocalTime.of(10, 25))) return null
        val day = now.toLocalDate()
        val (have, tried) = synchronized(lock) { log().let { (day.toString() in it.pcr) to (it.chainTried == day.toString()) } }
        if (have) return null
        val u = "BANKNIFTY"
        val step = PassedArms.step(u)
        // The chain in memory first (its OI no older than 3 minutes).
        val snap = runCatching { MemoryChain.read(u, each = 12) }.getOrNull()
        var spot = snap?.spot
        var oi: Map<Double, Pair<Long?, Long?>>? = snap?.legs?.filter { it.packetAgeMs <= 180_000 && it.oi != null }?.groupBy { it.strike }
            ?.mapValues { (_, l) -> l.firstOrNull { it.right == "CE" }?.oi to l.firstOrNull { it.right == "PE" }?.oi }
        if ((oi == null || oi.size < 15) && !tried && !runCatching { SmartWorkers.holdingAny() }.getOrDefault(true)) {
            // One background read a day, only while nothing is held (the existing chain read and its rate limits).
            synchronized(lock) { log().let { it.chainTried = day.toString(); save(it) } }
            runCatching { Market.liveChain(u, near = 10) }.getOrNull()?.let { lc ->
                spot = lc.spot
                val at = 10 * 60 + 14
                oi = lc.series.groupBy { it.strike }.mapValues { (_, l) ->
                    fun last(right: com.optionslab.engine.Right) = l.firstOrNull { it.right == right }?.let { s ->
                        val i = (0 until s.size).lastOrNull { s.minutes[it] <= at } ?: return@let null
                        s.oi[i].takeIf { it > 0 }
                    }
                    last(com.optionslab.engine.Right.CE) to last(com.optionslab.engine.Right.PE)
                }
            }
        }
        val sp = spot ?: return null
        val pcr = oi?.let { Watchlist.pcr(sp, step, it) } ?: return null
        val hist = synchronized(lock) { log().let { l -> l.pcr.toSortedMap().filterKeys { it < day.toString() }.values.toList().also { l.pcr[day.toString()] = pcr; save(l) } } }
        val side = Watchlist.c4Side(Watchlist.prevRank(pcr, hist))
        if (side == 0) return null
        return Watchlist.Signal(Watchlist.Rule.C4, u, "c4_$u", side, day.atTime(Watchlist.C4_AT), PassedArms.itmStrike(u, side, sp, Watchlist.C4_MONEY),
            "PCR %.3f in the %s fifth of the last 60 sessions".format(java.util.Locale.ENGLISH, pcr, if (side > 0) "top" else "bottom"))
    }

    /** Each open record priced once its exit is due; after 15:25 the day's twins. */
    private suspend fun price(now: LocalDateTime) {
        val open = synchronized(lock) { log().recs.filter { !it.final || (it.state == DONE && it.twins.isEmpty() && !now.toLocalTime().isBefore(EVENING) && it.s.entry.toLocalDate() == now.toLocalDate()) } }
        if (open.isEmpty()) return
        val byKey = HashMap<String, List<Bar>>()
        for (r in open) {
            val key = r.feedKey
            val lot = r.lot
            val day = r.s.entry.toLocalDate()
            val updated = if (key == null || lot == null) r.copy(state = UNPRICED) else {
                val bars = byKey["$key|$day"] ?: runCatching { optionBars(key, day, if (day == now.toLocalDate()) now else day.atTime(15, 31)) }.getOrNull()?.also { byKey["$key|$day"] = it }
                if (bars == null) r else {
                    val at = if (day == now.toLocalDate()) now else day.atTime(15, 31)
                    val exp = r.expiry == day
                    when (val o = if (r.state == DONE) Watchlist.Outcome.Done(r.p!!) else Watchlist.price(r.s, bars, at, lot, exp)) {
                        is Watchlist.Outcome.NoFill -> r.copy(state = NOFILL)
                        is Watchlist.Outcome.Open -> if (day.isBefore(now.toLocalDate())) r.copy(state = UNPRICED, note = "its bars could not be read in full") else r
                        is Watchlist.Outcome.Done -> {
                            val twins = if (!at.toLocalTime().isBefore(EVENING)) Watchlist.twins(r.s, bars, at, lot, exp) else r.twins
                            r.copy(state = DONE, p = o.p, twins = twins)
                        }
                    }
                }
            }
            if (updated != r) synchronized(lock) {
                val l = log()
                val i = l.recs.indexOfFirst { it.s.key == r.s.key }
                if (i >= 0) { l.recs[i] = updated; save(l) }
            }
        }
    }

    /** From 15:25 once a day: the evening line; and once per rule, at 40 shadow trades, Jarvis's word on it. */
    private fun evening(now: LocalDateTime) {
        val day = now.toLocalDate().toString()
        val (cards, line, verdicts) = synchronized(lock) {
            val l = log()
            val cards = cardsOf(l)
            val line = if (l.evening != day) { l.evening = day; Watchlist.eveningLine(cards) } else null
            val v = cards.filter { it.trades >= Watchlist.REVIEW_TRADES && it.rule.key !in l.told }.onEach { l.told += it.rule.key }
            if (line != null || v.isNotEmpty()) save(l)
            Triple(cards, line, v)
        }
        line?.let { t -> runCatching { Diag.record("watchlist", t) } }
        if (cards.isEmpty() || !com.optionslab.app.BuildConfig.JARVIS) return
        // Information only: written in the chat, never spoken, never an alert.
        line?.let { t -> runCatching { com.optionslab.app.ira.IraHub.note(t) } }
        for (c in verdicts) runCatching { com.optionslab.app.ira.IraHub.note(Watchlist.verdictLine(c)) }
    }

    /** TEST ONLY: forget the log's memory (the file stays), as a restart would. */
    internal fun resetForTest() = synchronized(lock) { cache = null; written.forget(); _view.value = View() }
}
