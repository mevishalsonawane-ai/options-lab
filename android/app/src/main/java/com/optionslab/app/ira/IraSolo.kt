package com.optionslab.app.ira

import com.optionslab.app.data.AppSettings
import com.optionslab.app.data.Paper
import com.optionslab.engine.Right
import com.optionslab.engine.orb.Bar
import com.optionslab.engine.orb.ShadowRules
import com.optionslab.engine.orb.SoloMidday
import com.optionslab.ira.SelfCalibration
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
import java.time.LocalDate
import java.time.LocalDateTime
import com.optionslab.ira.Market as IraMarket

/**
 * Solo (the owner's wish, 2026-10-03): Jarvis trades by himself, ON PAPER ONLY, when Boss switches it on.
 *
 * Since 06 Oct 2026 (Boss's approval) Solo trades the "risk-reduced Solo", Solo (midday) - [SoloMidday], pure and pinned
 * exactly as the research ran it: at 12:00, of NIFTY, BANKNIFTY and FINNIFTY (SENSEX is not traded: the paper account
 * and the instrument master are NSE F&O only), the index that has moved half its daily ATR or more from the open and
 * closed in the outer quarter of the morning's range - the strongest one - is bought 4 strikes in the money, 1 lot, one
 * trade a day and one position at a time; out on a 1-minute close 0.3 ATR against it, at breakeven once the index has
 * gone 75% of the way to 2R, or at 14:30. It is NOT PROVEN (the research's unseen period on these three: +Rs 4k on 178
 * trades, profit factor 1.02) and runs a forward test set in advance ([SoloMidday.judge]): after 60 closed paper trades
 * Jarvis reports the record, and a net per trade at or below 0 then or a drawdown beyond -Rs 25,000 at any time
 * switches Solo off by itself (that only lowers risk; switching it back on is Boss's choice - the drawdown then counts
 * from that moment, [SoloMidday.Baseline]).
 *
 * Retired as traders on the same day: the online learner (Learner / SoloGate) and the big-candle rules - both lost on the
 * research harness. Their trades stay in the record, readable; the new Solo's record starts fresh with its first trade
 * (tagged [SoloMidday.TAG]), so its 60-trade forward test is clean.
 *
 * Never live: Solo has no path to Zerodha - its orders go to the paper account only, and it never offers its setups as
 * trade ideas for approval.
 */
internal object IraSolo {
    private const val KEY_ON = "jarvis.solo"
    /** Why Solo switched itself off (the forward test; earlier, the old Solo's drawdown pause). Boss switching it on clears it. */
    private const val KEY_PAUSED = "jarvis.solo.paused"
    private const val KEY = "jarvis.solo.trades"
    /**
     * The forward-test verdict Solo last switched itself off for, with the baseline it was judged from ("FAILED_DRAWDOWN@0",
     * [SoloMidday.Baseline.key]): acted once per verdict and baseline - Boss's switching it back on stands until a new one.
     */
    private const val KEY_VERDICT = "jarvis.solo.midday.verdict"
    /** Where the forward test is judged from ("from|peak", [SoloMidday.Baseline]): set when Boss switches Solo back on after it switched itself off. */
    private const val KEY_BASE = "jarvis.solo.midday.base"
    /** How often a thin strike moved Solo's strike, and passed an index over ("moved|skipped"). */
    private const val KEY_THIN = "jarvis.solo.midday.thin"
    /** Set once the 60-trade report has been told. */
    private const val KEY_REPORTED = "jarvis.solo.midday.reported"
    private val MARKETS = SoloMidday.UNDERLYINGS.map { IraMarket.valueOf(it) }

    /** The research line under every Solo record. */
    val RESEARCH: String get() = SoloMidday.RESEARCH

    var on: Boolean
        // A pause the old Solo set ("paused until you switch it on again") still holds: it reads as off.
        get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean(KEY_ON, false) && paused == null }.getOrDefault(false)
        set(v) {
            val m = HashMap<String, Any?>()
            m[KEY_ON] = v
            if (v) {
                // Back on after Solo switched itself off: the forward test's drawdown counts from here ([SoloMidday.Baseline]).
                if (paused != null) m[KEY_BASE] = SoloMidday.baseline(nets(all())).let { "${it.from}|${it.peak}" }
                m[KEY_PAUSED] = null
            }
            runCatching { com.optionslab.app.security.SecurePrefs.putAll(m) }
            IraActivity.add(if (v) "Solo switched on (paper only, not proven)." else "Solo switched off.")
            refreshOn()
        }

    private val _onState = MutableStateFlow(false)
    /** [on] for the Ira page's card: set on every switch, Solo's own switch-off and each pass ([refreshOn]). */
    val onState: StateFlow<Boolean> = _onState

    /** Reads [on] into [onState]. */
    fun refreshOn() { _onState.value = on }

    /** Where the forward test is judged from: the last switch-on after a switch-off, else the start. */
    fun baseline(): SoloMidday.Baseline = runCatching {
        com.optionslab.app.security.SecurePrefs.getString(KEY_BASE)?.split('|')?.let { SoloMidday.Baseline(it[0].toInt(), it[1].toDouble()) }
    }.getOrNull() ?: SoloMidday.Baseline()

    /** How often a thin strike made Solo buy the next strike ([first]) and pass an index over ([second]). */
    fun thinCounts(): Pair<Int, Int> = runCatching {
        com.optionslab.app.security.SecurePrefs.getString(KEY_THIN)?.split('|')?.let { it[0].toInt() to it[1].toInt() }
    }.getOrNull() ?: (0 to 0)

    private fun countThin(moved: Boolean): Pair<Int, Int> {
        val (a, b) = thinCounts()
        val n = if (moved) a + 1 to b else a to b + 1
        runCatching { com.optionslab.app.security.SecurePrefs.put(KEY_THIN, "${n.first}|${n.second}") }
        return n
    }

    /** Why Solo switched itself off (null: it did not). Switching it on again clears it. */
    val paused: String? get() = runCatching { com.optionslab.app.security.SecurePrefs.getString(KEY_PAUSED) }.getOrNull()

    /**
     * One Solo trade; [closed] once out, with [exitPrice] and [net] (rupees after the paper account's charges). [tag]
     * [SoloMidday.TAG] for Solo (midday)'s trades (null: the retired Solo's); [atr] the ATR14 its stop and lock came from.
     */
    data class T(val day: String, val market: String, val symbol: String, val call: Boolean, val qty: Int, val entry: Double,
                 val entryMinute: Int, val index: Double, val level: Double, val target: Double, val why: String,
                 val closed: Boolean = false, val exitPrice: Double? = null, val net: Double? = null, val exit: String? = null,
                 /** The paper order ids of the entry and the exit (for the order book: "who placed it, which order"). */
                 val orderId: String? = null, val exitOrderId: String? = null,
                 /** The old Solo's conditions (kept readable; Solo (midday) does not judge itself by them). */
                 val regime: String? = null, val ivRank: Double? = null,
                 val tag: String? = null, val atr: Double? = null) {
        val midday: Boolean get() = tag == SoloMidday.TAG
    }

    fun all(): List<T> = runCatching {
        val a = JSONArray(com.optionslab.app.security.SecurePrefs.getString(KEY) ?: "[]")
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { o ->
            T(o.getString("d"), o.getString("m"), o.getString("s"), o.getBoolean("c"), o.getInt("q"), o.getDouble("e"), o.getInt("em"),
                o.getDouble("i"), o.getDouble("lv"), o.getDouble("tg"), o.getString("w"), o.optBoolean("x"),
                if (o.has("xp")) o.getDouble("xp") else null, if (o.has("n")) o.getDouble("n") else null, o.optString("xr").ifEmpty { null },
                o.optString("oid").ifEmpty { null }, o.optString("xoid").ifEmpty { null },
                o.optString("rg").ifEmpty { null }, if (o.has("iv")) o.getDouble("iv") else null,
                o.optString("tag").ifEmpty { null }, if (o.has("atr")) o.getDouble("atr") else null)
        } }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun save(list: List<T>) = runCatching {
        com.optionslab.app.security.SecurePrefs.put(KEY, JSONArray().apply { list.takeLast(500).forEach { t ->
            put(JSONObject().put("d", t.day).put("m", t.market).put("s", t.symbol).put("c", t.call).put("q", t.qty).put("e", t.entry)
                .put("em", t.entryMinute).put("i", t.index).put("lv", t.level).put("tg", t.target).put("w", t.why).put("x", t.closed)
                .apply { t.exitPrice?.let { put("xp", it) }; t.net?.let { put("n", it) }; t.exit?.let { put("xr", it) }
                    t.orderId?.let { put("oid", it) }; t.exitOrderId?.let { put("xoid", it) }
                    t.regime?.let { put("rg", it) }; t.ivRank?.takeIf { it.isFinite() }?.let { put("iv", it) }
                    t.tag?.let { put("tag", it) }; t.atr?.takeIf { it.isFinite() }?.let { put("atr", it) } })
        } }.toString())
    }

    /** Solo (midday)'s own trades (the forward test's), oldest first. */
    fun midday(list: List<T> = all()): List<T> = list.filter { it.midday }

    /** Solo (midday)'s closed trades' nets in order (the forward test's). */
    private fun nets(list: List<T>): List<Double> = midday(list).filter { it.closed && it.net != null }.map { it.net!! }

    /** Solo (midday)'s forward-test record: its closed trades' nets in order. */
    fun forward(list: List<T> = all()): SoloMidday.Record = SoloMidday.record(nets(list))

    /** The day Solo (midday)'s record starts: its first trade's (null: none yet). */
    fun since(list: List<T> = all()): LocalDate? = midday(list).firstOrNull()?.let { runCatching { LocalDate.parse(it.day) }.getOrNull() }

    /** What Solo's card shows: the forward test ("x of 60"), the not-proven label and the research line. */
    fun card(): List<String> = all().let { SoloMidday.card(forward(it), since(it)) }

    /**
     * Solo no longer judges itself by the conditions a trade came in: a self-gate on its own record was tested and cost
     * money out of sample (solo2/SPEC.md). Nothing to calibrate - the old Solo's sit-outs are not carried over.
     */
    @Suppress("UNUSED_PARAMETER")
    fun calibration(list: List<T> = all()): List<SelfCalibration.Outcome> = emptyList()

    // ---- the test seams (tests set them; the app never does) -----------------------------------------------------------

    /** TEST ONLY: an index's daily candles (null in the app: Upstox's are read). Setter throws unless BuildConfig.DEBUG. */
    @Volatile internal var testDaily: ((String) -> List<com.optionslab.engine.Upstox.Bar>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test feed exists only in debug builds" }; field = v }

    /** TEST ONLY: the trade check's level (null in the app: [IraHub.tradeCheckFast]). Setter throws unless BuildConfig.DEBUG. */
    @Volatile internal var testCheck: com.optionslab.ira.TradeCheck.Level? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test check exists only in debug builds" }; field = v }

    private val lock = Mutex()

    /** What Solo saw at its last decision (for "how is Solo doing"), and when. */
    @Volatile private var watch: Pair<LocalDateTime, String>? = null

    /** The day Solo made its 12:00 decision (one a day), with the vault's generation (a wiped vault starts afresh). */
    @Volatile private var decided: Pair<Int, LocalDate>? = null
    private fun generation(): Int = runCatching { com.optionslab.app.security.SecurePrefs.generationNow() }.getOrDefault(0)

    /** Each index's ATR14 for the day (read once a day; a failed read is tried again on the next pass). */
    private val atrs = HashMap<String, Pair<LocalDate, Double>>()

    /** Each Solo index's listed expiries for the day (the instrument master, read once a day). */
    @Volatile private var listed: Pair<LocalDate, Map<String, List<LocalDate>>>? = null

    private fun expiries(today: LocalDate): Map<String, List<LocalDate>>? {
        listed?.takeIf { it.first == today }?.let { return it.second }
        val all = runCatching { com.optionslab.app.data.Market.contracts() }.getOrNull() ?: return null
        val m = SoloMidday.UNDERLYINGS.associateWith { u -> all.filter { it.underlying == u }.map { it.expiry }.distinct().sorted() }
        if (m.values.any { it.isNotEmpty() }) listed = today to m
        return m
    }

    /** The day the slow reads were started early ([prefetch]), with the vault's generation. */
    @Volatile private var prefetched: Pair<Int, LocalDate>? = null
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The 12:00 decision's slow reads - each index's ATR14 (the daily candles) and the instrument master - started on the
     * first pass from 09:15 (once a day, in the background: the market watch's pass does not wait), so the 12:00-12:03
     * window reads them from memory. One that fails is read again at 12:00.
     */
    private fun prefetch(t: LocalDateTime) {
        val tt = t.toLocalTime()
        if (tt.isBefore(java.time.LocalTime.of(9, 15)) || !tt.isBefore(SoloMidday.DECIDE_AT)) return
        val key = generation() to t.toLocalDate()
        if (prefetched == key) return
        prefetched = key
        background.launch { prefetchNow(t.toLocalDate()) }
    }

    /** [prefetch]'s reads for [today] (tests call it directly). */
    internal suspend fun prefetchNow(today: LocalDate) {
        for (m in MARKETS) runCatching { atr(m.name, today) }
        runCatching { expiries(today) }
    }

    /** Tests: forget the day's memory (ATR14s, listed expiries, the early reads, the decision and what Solo saw). */
    internal fun resetForTest() {
        synchronized(atrs) { atrs.clear() }
        listed = null; prefetched = null; decided = null; watch = null
        synchronized(dayLock) { dayKept = null }
    }

    // ---- Solo's day in words (Jarvis's "what did Solo do today", [com.optionslab.ira.SoloDay]) ------------------------

    /**
     * Today's decision as Solo made it (each index's 12:00 read, what set one aside, a thin strike, LATE, the buy, a guard
     * holding it back). In memory only, guarded by itself - never Solo's [lock] - and never read by a decision, an exit or an
     * order: words for Jarvis. A new day's first note drops the earlier day's.
     */
    private val dayLock = Any()
    private var dayKept: com.optionslab.ira.SoloDay.Record? = null

    /** When this run's record began (the app's start): a decision made before it is not in memory. */
    val dayRecordSince: LocalDateTime = runCatching { com.optionslab.app.data.Market.now().toLocalDateTime() }.getOrElse { LocalDateTime.now() }

    /** Keeps one step of today's record ([com.optionslab.ira.SoloDay]'s pure steps). Never throws into a pass; nothing in GOLD. */
    private fun noteDay(step: (com.optionslab.ira.SoloDay.Record?) -> com.optionslab.ira.SoloDay.Record) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        runCatching { synchronized(dayLock) { dayKept = step(dayKept) } }
    }

    /** Today's record for [day] (null: none kept): a copy, never waiting on Solo's lock. Reads only. */
    fun dayRecord(day: LocalDate): com.optionslab.ira.SoloDay.Record? =
        runCatching { synchronized(dayLock) { dayKept }?.takeIf { it.day == day } }.getOrNull()

    /** The early reads for [day] as they stand in memory: started, each index's ATR14 read, the contracts read. Reads only. */
    fun prefetchState(day: LocalDate): com.optionslab.ira.SoloDay.Prefetch {
        val a = synchronized(atrs) { atrs.filterValues { it.first == day }.mapValues { it.value.second } }
        return com.optionslab.ira.SoloDay.Prefetch(prefetched?.second == day, a, listed?.first == day)
    }

    /** One of Solo's trades as [com.optionslab.ira.SoloDay] reads it (null: unreadable). */
    private fun dayTrade(t: T): com.optionslab.ira.SoloDay.Trade? = runCatching {
        com.optionslab.ira.SoloDay.Trade(LocalDate.parse(t.day), t.market, t.symbol, t.call, t.qty, t.entry, t.entryMinute, t.index, t.level,
            t.target, t.atr, t.why, t.closed, t.exitPrice, t.net, t.exit)
    }.getOrNull()

    /**
     * "What did Solo do today", "why didn't Solo trade", "how did Solo decide" ([com.optionslab.ira.SoloDay]): from Solo's own
     * records - its switch, today's record ([dayRecord], no lock), its early reads, today's trades, a trade from an earlier day
     * still open, the forward test from its baseline and the thin-strike counts. Read only: nothing is switched, placed or closed.
     */
    fun day(q: com.optionslab.ira.SoloDay.Q): String {
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        val today = now.toLocalDate()
        val tradingDay = runCatching { com.optionslab.app.data.Market.isTradingDay(today) }.getOrDefault(true)
        val list = all()
        val facts = com.optionslab.ira.SoloDay.Facts(
            now = now, tradingDay = tradingDay, on = runCatching { on }.getOrNull(), paused = runCatching { paused }.getOrNull(),
            record = dayRecord(today), since = dayRecordSince, prefetch = runCatching { prefetchState(today) }.getOrNull(),
            trades = midday(list).filter { it.day == today.toString() }.mapNotNull { dayTrade(it) },
            earlier = list.lastOrNull { !it.closed && it.day != today.toString() }?.let { dayTrade(it) },
            check = runCatching { SoloMidday.judge(nets(list), baseline()) }.getOrNull(), thinCounts = runCatching { thinCounts() }.getOrNull())
        return com.optionslab.ira.SoloDay.answer(q, facts)
    }

    private suspend fun atr(u: String, day: LocalDate): Double? {
        synchronized(atrs) { atrs[u]?.takeIf { it.first == day }?.let { return it.second } }
        val raw = testDaily?.invoke(u)
            ?: com.optionslab.app.data.Net.daily(com.optionslab.app.data.ChartFeed.instrumentKey(u), day.minusDays(45), day.minusDays(1))
        val a = ShadowRules.atr14(raw.map { Bar(it.istDate.atStartOfDay(), it.open, it.high, it.low, it.close) }, day) ?: return null
        synchronized(atrs) { atrs[u] = day to a }
        return a
    }

    /** [m]'s 1-minute bars today that have started by [t] (the forming minute included: its open is the 12:00 price). */
    private suspend fun bars(m: IraMarket, t: LocalDateTime): List<Bar> =
        runCatching { IraHub.freshBars(m) }.getOrDefault(emptyList())
            .filter { it.t.toLocalDate() == t.toLocalDate() && !it.t.isAfter(t) && it.t.toLocalTime() >= java.time.LocalTime.of(9, 15) }
            .map { Bar(it.t, it.o, it.h, it.l, it.c) }

    /** Every market-watch pass: Solo's open trade managed (even after it is switched off), or the 12:00 decision. */
    suspend fun tick() = lock.withLock {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD) return@withLock
        if (!com.optionslab.app.data.Market.isOpen()) return@withLock
        pass(com.optionslab.app.data.Market.now().toLocalDateTime())
    }

    /**
     * The 15-second check between full passes (08 Oct, research/PROFIT_LOCK_8OCT.md fix 3): only Solo's open trade's exits
     * (its index stop on a 1-minute close, the breakeven lock, 14:30), so a minute's close is acted on within 15 s. Never a
     * decision or an entry. Under Solo's own lock as [tick]: one exit in flight.
     */
    suspend fun manageOnly() = lock.withLock {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD) return@withLock
        if (!com.optionslab.app.data.Market.isOpen()) return@withLock
        val list = all()
        val open = list.lastOrNull { !it.closed } ?: return@withLock
        manage(open, list, com.optionslab.app.data.Market.now().toLocalDateTime())
    }

    /** Whether Solo holds an open trade (its saved list; no network): the 15-second check runs while it does. */
    fun holding(): Boolean = runCatching { all().any { !it.closed } }.getOrDefault(false)

    /** One pass at [t], after the market watch's gates ([tick]); tests call it directly. */
    internal suspend fun passAt(t: LocalDateTime) = lock.withLock { pass(t) }

    @Volatile private var brainsDropped = false

    private suspend fun pass(t: LocalDateTime) {
        // The retired learner's saved minds ("solo.brain.*") are dropped once: nothing reads them now, and every vault
        // write re-encrypts them.
        if (!brainsDropped) {
            brainsDropped = true
            runCatching {
                val old = com.optionslab.app.security.SecurePrefs.keys("solo.brain.")
                if (old.isNotEmpty()) com.optionslab.app.security.SecurePrefs.putAll(old.associateWith { null })
            }
        }
        val list = all()
        refreshOn()
        // At most one Solo position: an open trade is always seen through to its exit, even after Solo is switched off.
        list.lastOrNull { !it.closed }?.let { manage(it, list, t); return }
        if (!on) return
        prefetch(t)
        val today = t.toLocalDate()
        val tt = t.toLocalTime()
        // One decision a day, from 12:00 to 12:03 (the research's 3 minutes' slack); one trade a day, no re-entry.
        if (tt.isBefore(SoloMidday.DECIDE_AT) || !tt.isBefore(SoloMidday.LAST_ENTRY.plusMinutes(1))) return
        if (decided == generation() to today || midday(list).any { it.day == today.toString() }) return
        // The app's guards (they let paper through): the kill switch, the day's loss breaker, the trade check (a read
        // failing is a no - fail closed).
        // (Each guard that holds the decision back is kept for Jarvis's words only; the gates themselves are exactly as before.)
        val s = AppSettings.load()
        if (s.guardKill || com.optionslab.app.data.LossBreaker.trippedToday()) {
            noteDay { com.optionslab.ira.SoloDay.held(it, t, if (s.guardKill) com.optionslab.ira.SoloDay.KILL_SWITCH else com.optionslab.ira.SoloDay.LOSS_BREAKER) }
            return
        }
        val level = testCheck ?: runCatching { IraHub.tradeCheckFast().level }.getOrNull()
        if (level == null) { noteDay { com.optionslab.ira.SoloDay.held(it, t, com.optionslab.ira.SoloDay.CHECK_UNREAD) }; return }
        if (level == com.optionslab.ira.TradeCheck.Level.STOP) { noteDay { com.optionslab.ira.SoloDay.held(it, t, com.optionslab.ira.SoloDay.CHECK_STOP) }; return }
        decide(t, list)
    }

    /**
     * Whether today is [m]'s expiry day by the instrument master (as the Hero arm reads it: the nearest listed expiry is
     * today); null when the master could not be read (then Solo skips the index).
     */
    private fun expiryToday(m: IraMarket, today: LocalDate): Boolean? = runCatching {
        val listed = expiries(today)?.get(m.name).orEmpty()
        if (listed.isEmpty()) null else com.optionslab.engine.orb.HeroRules.isExpiryDay(today, listed)
    }.getOrNull()

    /** Solo's open paper trade as [com.optionslab.ira.AutoSide] reads it, for the other automatic traders ([com.optionslab.app.data.AutoExposure]). */
    fun exposure(): List<com.optionslab.ira.AutoSide.Held> = all().filter { !it.closed }.map { t ->
        com.optionslab.ira.AutoSide.Held.option("Jarvis solo", t.symbol, t.market, if (t.call) "CE" else "PE", long = true)
    }

    /** Another automatic position on [s]'s index (Liquidity 15+5 among them): one index, one side ([com.optionslab.app.data.AutoExposure]). */
    private fun conflict(s: SoloMidday.Signal): String? = runCatching {
        com.optionslab.app.data.AutoExposure.check(com.optionslab.app.data.AutoExposure.Source.SOLO, s.underlying,
            com.optionslab.ira.AutoSide.direction(if (s.call) "CE" else "PE", true), exposure())?.let { com.optionslab.ira.AutoSide.describe(it) }
    }.getOrElse { "the other automatic positions could not be read" }

    /** The 12:00 decision: each index read, the strongest signal that may be taken bought on paper. */
    private suspend fun decide(t: LocalDateTime, list: List<T>) {
        val today = t.toLocalDate()
        val decisions = MARKETS.map { m -> SoloMidday.signal(m.name, bars(m, t), runCatching { atr(m.name, today) }.getOrNull(), t) }
        // An index whose 11:59 minute (or ATR) is not in yet is waited for until 12:01; then Solo decides with what it has.
        val late = decisions.filter { it.why == "waiting_for_1159" || it.why == "no_atr" }
        if (late.isNotEmpty() && t.toLocalTime().isBefore(SoloMidday.DECIDE_AT.plusMinutes(1))) {
            noteDay { com.optionslab.ira.SoloDay.waited(it, t, late.map { d -> d.underlying }) }
            return
        }
        decided = generation() to today
        // Each index's read, kept for Jarvis's words ([com.optionslab.ira.SoloDay]); nothing reads it back to decide.
        noteDay { com.optionslab.ira.SoloDay.decided(it, t, decisions) }
        val signals = decisions.mapNotNull { it.signal }
        val none = decisions.filter { it.signal == null }.map { SoloMidday.skip(it) }
        if (signals.isEmpty()) {
            val line = "No Solo trade today: " + none.joinToString("; ") + "."
            watch = t to line
            IraActivity.add(line)
            return
        }
        // The strongest that may be taken; one that cannot be bought (no strike, no price, a thin strike, no fill) passes
        // the turn to the next strongest, as the research's portfolio did.
        val excluded = HashMap<String, String>()
        while (true) {
            val c = SoloMidday.pick(signals) { s ->
                excluded[s.underlying] ?: SoloMidday.gate(expiryToday(IraMarket.valueOf(s.underlying), today), conflict(s))
            }
            c.skipped.forEach { (x, why) -> noteDay { com.optionslab.ira.SoloDay.passed(it, t, x.underlying, why) } }
            val s = c.taken
            if (s == null) {
                val line = "No Solo trade today: " + (c.skipped.map { (x, why) -> SoloMidday.passedOver(x, why, null) } + none).joinToString("; ") + "."
                watch = t to line
                IraActivity.add(line)
                c.skipped.forEach { (x, why) -> think(x, false, listOf(SoloMidday.passedOver(x, why, null))) }
                return
            }
            val why = enter(s, signals, c.skipped, list, t) ?: return
            if (why == LATE) {
                val line = "No Solo trade today: the 12:00-12:03 entry window closed before the order could go in."
                watch = t to line
                IraActivity.add(line)
                return
            }
            excluded[s.underlying] = why
            noteDay { com.optionslab.ira.SoloDay.passed(it, t, s.underlying, why) }
        }
    }

    private fun think(s: SoloMidday.Signal, took: Boolean, facts: List<String>) = runCatching {
        IraThinking.add(com.optionslab.ira.Thinking.soloMidday(IraThinking.now(), IraMarket.valueOf(s.underlying), s.call, took, facts))
    }

    /** [enter]'s answer when the clock passed 12:03 before the order: nothing more is tried today. */
    private const val LATE = "late"

    /**
     * Buys [s] on paper: null when bought, [LATE] when the entry window closed first, else why not (the next strongest is
     * then tried).
     */
    private suspend fun enter(s: SoloMidday.Signal, signals: List<SoloMidday.Signal>, skipped: List<Pair<SoloMidday.Signal, String>>,
                              list: List<T>, t: LocalDateTime): String? {
        val today = t.toLocalDate()
        val listed = expiries(today)?.get(s.underlying).orEmpty()
        // The nearest expiry (never today's: Solo does not trade an index on its expiry day).
        val expiry = com.optionslab.engine.orb.OrbRules.expiryAfter(today, listed) ?: return "no ${s.underlying} expiry is listed"
        // A thin strike: the research's fallback order (4 in the money, then one step either way, the less in the money
        // first) is tried before the index is passed over - each time counted and said.
        var thinFirst: String? = null
        val right = if (s.call) Right.CE else Right.PE
        var why = "no ${s.underlying} strike near ${s.strike} is listed"
        val held = runCatching { Paper.snapshot().positions.positions.filter { it.quantity != 0 }.map { it.symbol }.toSet() }.getOrNull()
            ?: return "the paper account could not be read"
        for (k in SoloMidday.strikesToTry(s.underlying, s.side, s.index)) {
            val c = Paper.contractFor(s.underlying, expiry, k.toDouble(), right) ?: continue
            // Boss's own paper position in this contract is never mixed with Solo's (its exit would touch it).
            if (c.symbol in held) { why = "you hold ${c.symbol} yourself"; continue }
            val q = runCatching { Paper.quote(c) }.getOrNull()
            if (q == null) { why = "no price for ${c.symbol}"; continue }
            val thin = com.optionslab.ira.StrikeLiquidity.problem(q.bid, q.ask, q.volume, c.lotSize)
            if (thin != null) {
                why = thin
                if (thinFirst == null) thinFirst = "${c.symbol}: $thin"
                IraActivity.add("Solo skipped ${c.symbol}: $thin")
                noteDay { com.optionslab.ira.SoloDay.thin(it, t, s.underlying, c.symbol, thin) }
                runCatching { IraThinking.add(com.optionslab.ira.Thinking.soloThin(IraThinking.now(), IraMarket.valueOf(s.underlying), s.call, thin)) }
                continue
            }
            // The decision was made at [t]; the reads since take time: never an order after 12:03.
            val clock = runCatching { com.optionslab.app.data.Market.now().toLocalDateTime() }.getOrDefault(t)
            if (!SoloMidday.mayPlace(today, if (clock.isAfter(t)) clock else t)) {
                noteDay { com.optionslab.ira.SoloDay.late(it, if (clock.isAfter(t)) clock else t) }
                return LATE
            }
            // PAPER ONLY: the paper account's MARKET buy, 1 lot - there is no Zerodha path here.
            val r = Paper.place(c, "BUY", SoloMidday.LOTS, "MARKET", "MIS", null, null, q)
            val fill = r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().firstOrNull()
            if (fill == null) {
                // Not filled now (a stale quote): cancelled, never left to fill later with no record.
                r.orderId?.let { runCatching { Paper.cancel(it) } }
                return "the paper order for ${c.symbol} did not fill"
            }
            r.orderId?.let { com.optionslab.app.data.Strategies.tagOwner("paper:$it", "Jarvis solo · entry") }
            val itm = kotlin.math.abs(SoloMidday.atm(s.underlying, s.index) - k) / SoloMidday.step(s.underlying)
            val n = signals.size
            val seen = SoloMidday.why(s, n)
            val trade = T(today.toString(), s.underlying, c.symbol, s.call, fill.quantity, fill.price,
                t.hour * 60 + t.minute - (9 * 60 + 15), s.index, s.stop, s.target, seen, orderId = r.orderId, tag = SoloMidday.TAG, atr = s.atr)
            save(list + trade)
            val thinMoved = thinFirst != null
            noteDay { com.optionslab.ira.SoloDay.bought(it, com.optionslab.ira.SoloDay.Bought(t, s.underlying, c.symbol, k, itm, fill.price, thinMoved)) }
            // The ones passed over: blocked before it, and the weaker ones (one Solo position at a time).
            val over = skipped.map { (x, w) -> x to SoloMidday.passedOver(x, w, s) } +
                SoloMidday.rank(signals).dropWhile { it != s }.drop(1).map { it to SoloMidday.passedOver(it, "stronger", s) }
            val passed = over.map { it.second }
            // A thin strike moved the buy to the next one: counted and said (how often the research's strike is not the one bought).
            val moved = thinFirst?.let { first ->
                val n = countThin(moved = true).first
                "The strike ${SoloMidday.ITM_STEPS} in the money was thin ($first): bought the next one, as the research's fallback " +
                    "(the strike moved $n time${if (n == 1) "" else "s"} so far)."
            }
            moved?.let { IraActivity.add("Solo: $it") }
            tell("Solo (paper, not proven): bought ${c.symbol} ($itm ITM) at ${"%.2f".format(java.util.Locale.ENGLISH, fill.price)}" +
                (com.optionslab.app.data.Origins.shortId(r.orderId)?.let { " (order $it)" } ?: "") + ". Why: $seen " + SoloMidday.plan(s) +
                (if (passed.isNotEmpty()) " Passed over: ${passed.joinToString("; ")}." else ""))
            watch = t to "bought ${c.symbol}: ${s.underlying} was the strongest of $n"
            think(s, true, listOfNotNull(seen, moved, SoloMidday.plan(s)))
            over.forEach { (x, words) -> think(x, false, listOf(words)) }
            IraHub.appContext()?.let { com.optionslab.app.work.Notifier.orderFilled(it, "BUY", fill.quantity, c.symbol, fill.price, "Paper", "Jarvis solo · entry", r.orderId) }
            return null
        }
        // Every strike near it thin: the index is passed over (counted and said; the next strongest is tried).
        if (thinFirst != null) {
            val n = countThin(moved = false).second
            IraActivity.add("Solo passed ${s.underlying} over: its strikes near ${s.strike} were thin (an index passed over $n time${if (n == 1) "" else "s"} so far).")
        }
        return why
    }

    private suspend fun manage(t: T, list: List<T>, now: LocalDateTime) {
        val today = now.toLocalDate()
        val soloBook = runCatching { Paper.snapshot() }.getOrNull()
        val held = soloBook?.positions?.positions?.any { it.symbol == t.symbol && it.quantity != 0 } ?: true
        if (!held) {
            // Closed outside Solo (the 15:15 square-off, or by hand), maybe on an earlier day while the app was away: the
            // sells of its symbol after its entry in the paper account's whole trade history are the exit, whatever the day.
            // With none found, no result is counted.
            val sold = runCatching { exitOf(Paper.state.trades, t) }.getOrNull()
            finish(t, list, sold?.first, if (sold != null) "closed outside Solo" else "closed while the app was away (no result read)",
                exitOrderId = sold?.second)
            return
        }
        val a = t.atr
        // The retired Solo's trade, still open when Solo (midday) replaced it: its rules are gone, so it is closed now.
        val walked: Pair<SoloMidday.Open, SoloMidday.Walk>? = if (!t.midday || a == null) null else {
            // From the 12:00 decision minute (the research's entry bar), not the minute the paper order filled.
            val o = SoloMidday.Open(t.market, if (t.call) 1 else -1, t.index, a, SoloMidday.entryTime(LocalDate.parse(t.day)))
            o to SoloMidday.walk(o, bars(IraMarket.valueOf(t.market), now), now)
        }
        // Solo's own exits only (the index stop, the breakeven lock, 14:30). The 30/60 rule (Boss's fixed 30-point stop and
        // +60 target with the ladder) is deliberately NOT applied to Solo: the solo2 study (06 Oct 2026, solo2/out/ablation.csv)
        // found it cost -Rs 2.18 L against these exits on the same trades. A day that ended with it still open: out now.
        val exit: String = when {
            walked == null -> "the retired Solo's trade, closed when Solo (midday) replaced it"
            walked.second.exit != null -> walked.second.exit!!
            t.day != today.toString() -> SoloMidday.TIME
            else -> return
        }
        val r = Paper.close(t.symbol, "MIS")
        val px = r.events.filterIsInstance<com.optionslab.engine.sandbox.SandboxEvent.Fill>().firstOrNull()?.price
        if (px == null) {
            // Not filled (no fresh price): the exit order is cancelled and tried again next pass.
            r.orderId?.let { runCatching { Paper.cancel(it) } }
            return
        }
        // Solo's exit is labelled as its own (who placed it, which order) and notified like any fill.
        r.orderId?.let { oid ->
            com.optionslab.app.data.Strategies.tagOwner("paper:$oid", "Jarvis solo · exit")
            IraHub.appContext()?.let { com.optionslab.app.work.Notifier.orderFilled(it, "SELL", t.qty, t.symbol, px, "Paper", "Jarvis solo · exit", oid) }
        }
        val words = if (walked != null) {
            val last = runCatching { bars(IraMarket.valueOf(t.market), now).lastOrNull()?.close }.getOrNull()
            SoloMidday.exitSay(walked.first, walked.second.copy(exit = exit), last, (px - t.entry) * t.qty)
        } else exit
        finish(t, list, px, words, exitOrderId = r.orderId)
    }

    /**
     * [t]'s exit in the paper account's trade history [trades] (every day's, not the session's): the sells of its symbol at
     * or after its entry fill (found by its order id; else its entry minute), up to its quantity - their quantity-weighted
     * price and the first sell's order id; null when none.
     */
    internal fun exitOf(trades: List<com.optionslab.engine.sandbox.Trade>, t: T): Pair<Double, String>? {
        val entered = trades.firstOrNull { t.orderId != null && it.orderId == t.orderId }?.timestamp
            ?: runCatching { LocalDate.parse(t.day).atTime(9, 15).plusMinutes(t.entryMinute.toLong()).withSecond(0) }.getOrNull() ?: return null
        val sells = trades.filter { it.symbol == t.symbol && it.action == "SELL" && !it.timestamp.isBefore(entered) }.sortedBy { it.timestamp }
        var q = 0
        var value = 0.0
        for (x in sells) {
            if (q >= t.qty) break
            val take = minOf(kotlin.math.abs(x.quantity), t.qty - q)
            q += take; value += take * x.price.toDouble()
        }
        return if (q == 0) null else value / q to sells.first().orderId
    }

    private fun finish(t: T, list: List<T>, px: Double?, why: String, exitOrderId: String? = null) {
        val net = px?.let { (it - t.entry) * t.qty - ShadowRules.charges(t.entry, it, t.qty) }
        val done = t.copy(closed = true, exitPrice = px, net = net, exit = why, exitOrderId = exitOrderId)
        val all = list.map { if (it === t) done else it }
        save(all)
        tell("Solo (paper, not proven): closed ${t.symbol}" + (px?.let { " at ${"%.2f".format(java.util.Locale.ENGLISH, it)}" } ?: "") +
            (com.optionslab.app.data.Origins.shortId(exitOrderId)?.let { " (order $it)" } ?: "") + ". $why " +
            (net?.let { "Result ${SoloMidday.rs(it)} after charges. " } ?: "") + record(all))
        if (done.midday) forwardTest(all)
    }

    /**
     * The forward test set in advance ([SoloMidday.verdict]), after each closed trade: the record told once at 60 trades;
     * a failed bar switches Solo off by itself and says so (once a verdict and baseline: Boss switching it back on stands, and
     * the drawdown is then judged from that moment).
     */
    private fun forwardTest(all: List<T>) {
        val c = SoloMidday.judge(nets(all), baseline())
        val r = c.record
        val v = c.verdict
        val words = SoloMidday.verdictSay(c)
        var told = false
        val reported = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean(KEY_REPORTED, false) }.getOrDefault(true)
        if (r.trades >= SoloMidday.FORWARD_TRADES && !reported) {
            runCatching { com.optionslab.app.security.SecurePrefs.put(KEY_REPORTED, true) }
            tell(words); told = true
        }
        val acted = runCatching { com.optionslab.app.security.SecurePrefs.getString(KEY_VERDICT) }.getOrNull()
        // Once per verdict and baseline: after Boss switches it back on (a new baseline), a new failure switches it off again.
        val key = c.base.key(v)
        // (An older build kept the bare verdict: acted on too while no switch-on has been recorded since.)
        if (SoloMidday.switchOff(v) && !c.base.actedOn(acted, v)) {
            // Switching off only lowers risk: allowed by itself. Turning it back on is Boss's choice.
            runCatching { com.optionslab.app.security.SecurePrefs.putAll(mapOf(KEY_VERDICT to key, KEY_ON to false, KEY_PAUSED to words)) }
            IraActivity.add("Solo switched itself off: the forward test's bar failed.")
            refreshOn()
            if (!told) tell(words)
        }
    }

    private fun tell(line: String) {
        IraActivity.add(line)
        IraHub.note(line)
        // A locked phone may be overheard: Solo's symbol and result stay in the chat.
        runCatching { JarvisVoice.announce(com.optionslab.ira.Overheard.said(line, IraHub.locked(), "Boss, Solo has news on its paper trade: it's in the chat.")) }
    }

    /** Solo's day for the 15:35 wrap-up (null when it was off and did nothing). */
    fun daySummary(): String? = runCatching {
        val all = all()
        val today = com.optionslab.app.data.Market.today().toString()
        val mine = all.filter { it.day == today && it.closed }
        if (mine.isEmpty()) {
            if (!on) return@runCatching null
            val w = watch?.takeIf { it.first.toLocalDate().toString() == today }?.second
            return@runCatching "Solo (midday) took no trade today" + (w?.let { ": ${it.removePrefix("No Solo trade today: ").trimEnd('.')}" } ?: "") +
                ". " + SoloMidday.forwardLine(forward(all))
        }
        "Solo today on paper: " + mine.joinToString("; ") { t ->
            "${t.symbol} " + (t.net?.let { SoloMidday.rs(it) } ?: "result not read") + (t.exit?.let { " (${it.take(80)})" } ?: "")
        } + ". " + SoloMidday.forwardLine(forward(all))
    }.getOrNull()

    /** "How is Solo doing": on or off, why it switched itself off, the forward test, what it saw today, the old record. */
    fun status(): String {
        val all = all()
        val head = if (on) "Solo is on, Boss - Solo (midday), paper only and not proven. At 12:00 it buys the index (NIFTY, BANKNIFTY " +
            "or FINNIFTY) that has moved half its daily ATR or more from the open and closed in the outer quarter of the morning's " +
            "range - the strongest one, 4 strikes in the money, one trade a day, never on that index's expiry day; out on a 1-minute close " +
            "0.3 ATR against it, at breakeven once 75% of the way to 2R, or at 14:30."
        else "Solo is off, Boss: switch it on in Jarvis settings (Solo (midday), paper only, not proven)."
        val seen = watch?.takeIf { on && it.first.toLocalDate() == com.optionslab.app.data.Market.today() }?.let { (at, w) ->
            " At %02d:%02d: %s".format(java.util.Locale.ENGLISH, at.hour, at.minute, w.trimEnd('.')) + "."
        } ?: ""
        val (moved, passed) = thinCounts()
        val thin = if (moved + passed == 0) "" else " Thin strikes so far: the strike moved $moved time${if (moved == 1) "" else "s"}, " +
            "an index passed over $passed time${if (passed == 1) "" else "s"}."
        return head + (paused?.let { " $it" } ?: "") + " " + SoloMidday.forwardLine(forward(all)) + " " + RESEARCH + seen + thin + " " + record(all)
    }

    /** Solo's record as Jarvis's graduation reads it: none - Solo (midday) never graduates to real money from here. */
    fun closedRecord(): List<com.optionslab.ira.JarvisTrades.Closed> = emptyList()

    /** Why Solo's trades do not go live: always - Solo is paper only, with no path to Zerodha. */
    fun provenWhy(): String? = "Solo's trades stay on paper always: Solo (midday) is a paper-only test, not proven, and never reaches Zerodha."

    /** Solo's paper record in one line: Solo (midday)'s, then the retired rules' (kept readable). */
    fun record(list: List<T> = all()): String {
        val r = forward(list)
        val now = if (r.trades == 0) "Solo (midday) has no closed trades yet." else
            "Solo (midday) so far: ${r.trades} trade${if (r.trades == 1) "" else "s"}, ${r.wins} won, net ${SoloMidday.rs(r.net)} on paper."
        val old = list.filter { !it.midday && it.closed && it.net != null }
        return now + if (old.isEmpty()) "" else
            " Before it, the retired rules: ${old.size} trade${if (old.size == 1) "" else "s"}, net ${SoloMidday.rs(old.sumOf { it.net!! })}."
    }
}
