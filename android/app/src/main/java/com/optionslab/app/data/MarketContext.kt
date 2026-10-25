package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.CandleExplain
import com.optionslab.ira.DaysLikeToday
import com.optionslab.ira.EventShield
import com.optionslab.ira.ExpiryCard
import com.optionslab.ira.LevelMap
import com.optionslab.ira.MarketContextCore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import com.optionslab.ira.Market as IraMarket

/**
 * The market context Boss sees (TODO I6, I10, I11, I12, I13; 13 Oct 2026). Advisory only - the logic is ira-core's
 * ([EventShield], [ExpiryCard], [DaysLikeToday], [LevelMap], [CandleExplain]):
 *
 *  - [card]: Home's "Market context" card - an event warning (the evening before and that morning, with the 2024-26 record
 *    and its sample sizes), the expiry-day card, and from 09:21 "days like today" (context, not a forecast), which Jarvis
 *    also posts once a day as his morning brief;
 *  - [entry]: Boss's optional "pause paper arms on event days" ([EventShield.Mode]) - OFF by default, SHADOW logs only, ON
 *    skips a PAPER arm's new entry on an event day; never live, never an exit, never Liquidity BANKNIFTY while frozen.
 *    Memory only: the switch is read once at the start on an IO thread, today's events are kept by the refresh;
 *  - [levelJson]: the chart's level map, computed on this object's own thread and cached; the chart reads the cache;
 *  - [explain]: "explain this candle", gathered on an IO thread from what the app holds.
 *
 * Nothing here places, sizes, changes or delays an order, and nothing runs on the main thread.
 */
object MarketContext {
    private const val K_PAUSE = "context.eventPause"
    private const val REFRESH_MS = 60_000L
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var app: Context? = null
    @Volatile private var started = false

    fun enabled(): Boolean = !com.optionslab.app.BuildConfig.GOLD

    /** The wait to the next refresh ([com.optionslab.ira.ContextPace]). */
    internal fun nextRefreshMs(now: LocalDateTime): Long = com.optionslab.ira.ContextPace.nextMs(now, trading(now.toLocalDate()))

    /** At the app's start: the switch read on an IO thread, then the refresh loop on this object's own thread. */
    fun init(context: Context) {
        if (!enabled() || started) return
        started = true
        app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            // 14 Oct: after the open positions are restored and watched ([com.optionslab.app.data.PositionsFirst]).
            com.optionslab.app.data.PositionsFirst.awaitPositions()
            runCatching {
                _mode.value = EventShield.mode(SecurePrefs.getString(K_PAUSE))
                // Shadow first: this process watches from now (a restart mid-session does not count as watched from 09:15).
                shadowSince = if (_mode.value == EventShield.Mode.SHADOW) nowIst() else null
            }
            runCatching { _shadowDays.value = SecurePrefs.getString(K_SHADOW_DAYS).orEmpty().split(',').filter { it.isNotBlank() }.toSet() }
            while (true) {
                runCatching { refresh() }
                // Battery (14 Oct): each minute from 08:45 to 15:45 on a trading day; outside that just after midnight (the new
                // day's events) and at 18:00 (the next session's warning) - it was every minute, 24x7 ([ContextPace]).
                // Waited in steps of at most a minute against the wall clock (a wait's clock stops in deep sleep).
                val due = System.currentTimeMillis() + runCatching { nextRefreshMs(nowIst()) }.getOrDefault(REFRESH_MS)
                while (true) { val left = due - System.currentTimeMillis(); if (left <= 0) break; delay(com.optionslab.ira.RestClock.step(left)) }
            }
        }
    }

    // ---- the switch (OFF by default) -------------------------------------------------------------------------------------

    private val _mode = MutableStateFlow(EventShield.DEFAULT_MODE)
    val mode: StateFlow<EventShield.Mode> = _mode

    private const val K_SHADOW_DAYS = "context.eventShadowDays"
    private val _shadowDays = MutableStateFlow<Set<String>>(emptySet())
    /** The event days the switch has run in SHADOW: ON is offered only after at least one (shadow first). */
    val shadowDays: StateFlow<Set<String>> = _shadowDays

    /** Boss's choice (Bot settings). Written in the background; returns what to say. ON needs a shadowed event day first. */
    fun setMode(m: EventShield.Mode): String {
        if (m == EventShield.Mode.ON && _shadowDays.value.isEmpty())
            return "Shadow first: run it in Shadow through at least one event day, see what it would have paused, then switch it on."
        val was = _mode.value
        _mode.value = m
        // The card shows the switch at once (the refresh may now be hours away out of market hours: Battery, 14 Oct).
        _card.value = _card.value.copy(pause = m)
        if (m != EventShield.Mode.SHADOW) shadowSince = null else if (was != EventShield.Mode.SHADOW) shadowSince = nowIst()
        scope.launch(Dispatchers.IO) { runCatching { SecurePrefs.putAllSoon(mapOf(K_PAUSE to m.name)) } }
        runCatching { Diag.record("context", "pause paper arms on event days: ${m.name}") }
        return when (m) {
            EventShield.Mode.OFF -> "Off: event days change nothing."
            EventShield.Mode.SHADOW -> "Shadow: on event days the app logs which paper entries it would have paused; every entry still goes."
            EventShield.Mode.ON -> "On: paper arms skip new entries on event days. Live orders, exits and Liquidity BANKNIFTY (frozen) are never touched."
        }
    }

    /** Today's counting events, kept by the refresh (memory). */
    @Volatile private var today: Pair<LocalDate, List<String>> = LocalDate.MIN to emptyList()

    /**
     * Shadow first (13 Oct review): a day counts only once the switch actually watched it - since when this process has had
     * SHADOW on ([shadowSince], memory), and when a real shadow verdict was last given to the arms' gate ([shadowVerdictAt]).
     */
    @Volatile private var shadowSince: LocalDateTime? = null
    @Volatile private var shadowVerdictAt: LocalDateTime? = null

    private fun nowIst(): LocalDateTime = runCatching { Market.now().toLocalDateTime() }.getOrElse { LocalDateTime.now(IST) }

    /**
     * A NEW entry by arm [key] ([live]: a Zerodha entry). Memory only - no vault, no file, no network: the order path may ask
     * it. A SHADOW verdict is logged by the caller (FlowGate) with its other shadow notes; its time is kept here in memory.
     */
    fun entry(key: String, live: Boolean): EventShield.Verdict {
        val m = _mode.value
        if (m == EventShield.Mode.OFF || live) return EventShield.GO
        val (d, ev) = today
        if (ev.isEmpty() || d != runCatching { Market.today() }.getOrNull()) return EventShield.GO
        val frozen = runCatching { LiquidityTest.frozenKey(key) }.getOrDefault(true)
        val v = EventShield.decide(m, ev, live, frozen)
        if (v.shadow != null) shadowVerdictAt = nowIst()
        return v
    }

    // ---- events ------------------------------------------------------------------------------------------------------------

    /** The names the calendar holds for [day]: the event calendar (RBI, Budget, Boss's own) and the Fed nights. */
    private fun rawEvents(day: LocalDate): List<String> = rawEventsForTest?.invoke(day) ?:
        runCatching { EventDays.calendar.value.on(day).map { it.name } }.getOrDefault(emptyList()) +
            com.optionslab.ira.Events.builtIn(day, day).map { it.name }

    /**
     * The counting event names of session [day] ([MarketContextCore.eventsOn]): its own, those of the closed days since the
     * session before (a weekend Budget, a Fed night on a holiday), and US CPI ([EventShield.US_CPI]). None on a closed day.
     */
    fun eventsOn(day: LocalDate): List<String> = MarketContextCore.eventsOn(day, ::rawEvents, ::trading)

    private fun trading(d: LocalDate) = runCatching { Market.isTradingDay(d) }.getOrDefault(d.dayOfWeek.value <= 5)

    fun nextSession(after: LocalDate): LocalDate = MarketContextCore.nextSession(after, ::trading)

    // ---- the card ----------------------------------------------------------------------------------------------------------

    data class Card(val event: String? = null, val expiryTitle: String? = null, val expiry: List<String> = emptyList(),
                    val days: Map<String, List<String>> = emptyMap(), val pause: EventShield.Mode = EventShield.DEFAULT_MODE) {
        val empty: Boolean get() = event == null && expiry.isEmpty() && days.isEmpty()
    }

    private val _card = MutableStateFlow(Card())
    val card: StateFlow<Card> = _card

    /** The event warning: today's (morning and through the day) or the next session's (the evening before). */
    fun eventWarning(now: LocalDateTime): String? = MarketContextCore.eventWarning(now, ::eventsOn, ::trading)

    private val INDICES = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")

    private fun expiringToday(d: LocalDate): List<String> =
        INDICES.filter { u -> runCatching { Market.isExpiryDay(u, d) }.getOrDefault(false) }

    /** The day's tries of the "days like today" brief (13 Oct review: it was rebuilt every minute when it could not be built). */
    @Volatile private var briefTries = MarketContextCore.BriefTries()
    private val briefs = ConcurrentHashMap<String, List<String>>()

    /** One pass (this object's thread): today's events, the card, and the morning brief once after 09:21. */
    internal suspend fun refresh(now: LocalDateTime = nowIst()) {
        val d = now.toLocalDate()
        today = d to eventsOn(d)
        // Shadow first: an event day counts only once Shadow actually watched it (a verdict logged in market hours, or on
        // from before 09:15 through 15:30). This loop runs on an IO thread.
        if (d.toString() !in _shadowDays.value &&
            MarketContextCore.shadowDayCounts(d, today.second, trading(d), _mode.value, shadowSince, shadowVerdictAt, now)) {
            val next = (_shadowDays.value + d.toString()).sorted().takeLast(50).toSet()
            _shadowDays.value = next
            runCatching { SecurePrefs.putAllSoon(mapOf(K_SHADOW_DAYS to next.joinToString(","))) }
        }
        val exp = if (trading(d)) expiringToday(d) else emptyList()
        if (briefTries.day != d) briefs.clear()                     // the day's rollover: yesterday's brief is not today's
        if (MarketContextCore.briefDue(briefTries, now, trading(d))) {
            briefs.clear()
            for (u in listOf("BANKNIFTY", "NIFTY")) runCatching { daysBrief(u, d) }.getOrNull()?.let { briefs[u] = it }
            val ok = briefs.isNotEmpty()
            briefTries = MarketContextCore.briefAfter(briefTries, now, ok)
            if (ok && com.optionslab.app.BuildConfig.JARVIS) runCatching {
                com.optionslab.app.ira.IraHub.note("Morning brief - " + briefs.values.joinToString(" ") { it.joinToString(" ") })
            }
        }
        val shown = if (MarketContextCore.briefShown(briefTries, d)) HashMap(briefs) else HashMap()
        _card.value = Card(eventWarning(now), exp.takeIf { it.isNotEmpty() }?.let(ExpiryCard::title),
            exp.firstOrNull()?.let { ExpiryCard.lines(it, now.toLocalTime()) }.orEmpty(), shown, _mode.value)
    }

    /** The 09:00 morning check's lines ([MarketContextCore.morningLines]). */
    fun morningLines(now: LocalDateTime = nowIst()): List<String> {
        if (!enabled()) return emptyList()
        val d = now.toLocalDate()
        return MarketContextCore.morningLines(now, ::eventsOn, ::trading, if (trading(d)) expiringToday(d) else emptyList(), _mode.value)
    }

    /** The 15:45 report's line: the next session's event warning, if any. */
    fun eveningLines(now: LocalDateTime = nowIst()): List<String> {
        if (!enabled()) return emptyList()
        val next = nextSession(now.toLocalDate())
        return listOfNotNull(EventShield.warning(eventsOn(next), next, now.toLocalDate()))
    }

    // ---- days like today ---------------------------------------------------------------------------------------------------

    private val shipped = ConcurrentHashMap<String, List<DaysLikeToday.Row>>()

    private fun shippedRows(und: String): List<DaysLikeToday.Row> = shipped.getOrPut(und) {
        runCatching { app?.assets?.open("context/days_$und.csv")?.use { it.readBytes().toString(Charsets.UTF_8) }?.let(DaysLikeToday::parseCsv) }
            .getOrNull().orEmpty()
    }

    private fun market(und: String): IraMarket? = when (und) {
        "NIFTY" -> IraMarket.NIFTY; "BANKNIFTY" -> IraMarket.BANKNIFTY; "FINNIFTY" -> IraMarket.FINNIFTY; "SENSEX" -> IraMarket.SENSEX; else -> null
    }

    /**
     * Past expiry days by the exchanges' rule from 1 Sep 2025, for the "days like today" context only (no order reads this):
     * NIFTY weekly on Tuesday; SENSEX weekly on Thursday (BSE's day); BANKNIFTY, FINNIFTY and MIDCPNIFTY monthly only, the month's
     * last Tuesday. A holiday's shift is not applied. 22 Oct (arms audit): every index was Tuesday before - SENSEX's Thursdays and
     * FINNIFTY's non-expiry Tuesdays were flagged wrong.
     */
    internal fun ruleExpiry(und: String, d: LocalDate): Boolean = when (und) {
        "SENSEX" -> d.dayOfWeek == DayOfWeek.THURSDAY
        "NIFTY" -> d.dayOfWeek == DayOfWeek.TUESDAY
        else -> d.dayOfWeek == DayOfWeek.TUESDAY && d.with(TemporalAdjusters.lastInMonth(DayOfWeek.TUESDAY)) == d
    }

    private suspend fun daysBrief(und: String, d: LocalDate): List<String>? {
        val m = market(und) ?: return null
        val bars = com.optionslab.app.ira.IraHub.recentBars(m)
        val vix = com.optionslab.app.ira.IraHub.recentBars(IraMarket.VIX)
        val dates = bars.map { it.t.toLocalDate() }.toSet()
        val expiries = dates.filter { it != d && ruleExpiry(und, it) }.toSet() + (if (runCatching { Market.isExpiryDay(und, d) }.getOrDefault(false)) setOf(d) else emptySet())
        val events = dates.filter { eventsOn(it).isNotEmpty() }.toSet()
        val arms = if (und == "BANKNIFTY") paperArms() else emptyMap()
        val phone = DaysLikeToday.fromCandles(bars, vix, expiries, events, d, arms, ::trading)
        val t = phone.firstOrNull { it.day == d } ?: return null
        return DaysLikeToday.brief(und, t, DaysLikeToday.merge(shippedRows(und), phone))
    }

    /** The paper account's net per arm and day (the BANKNIFTY arms), for "how each arm did". */
    private suspend fun paperArms(): Map<LocalDate, Map<String, Double>> = runCatching {
        val owners = Strategies.owners()
        com.optionslab.app.ira.IraAccount.trips(false, owners).groupBy { it.closedAt.toLocalDate() }
            .mapValues { (_, ts) -> ts.groupBy { it.owner }.mapValues { (_, x) -> x.sumOf { it.net - it.unbooked } } }
    }.getOrDefault(emptyMap())

    // ---- the level map -----------------------------------------------------------------------------------------------------

    private class Cached(val atMs: Long, val json: String)
    private val levels = ConcurrentHashMap<String, Cached>()
    private val computing = ConcurrentHashMap.newKeySet<String>()
    private val levelJobs = ConcurrentHashMap<String, Job>()
    const val LEVELS_MS = 60_000L

    /** Where the level map is computed: this object's own thread pool (a test may hand its own; never the main thread). */
    @Volatile internal var levelDispatcher: CoroutineDispatcher = Dispatchers.Default

    /**
     * The chart's level map for index [und] as JSON ({"levels":[{type,label,price,side}],"note"}), from the cache: never
     * computed on the caller's thread. A stale or missing one starts a computation on [levelDispatcher]; the chart asks
     * again on its next redraw.
     */
    fun levelJson(und: String): String {
        if (!enabled() || market(und) == null) return "{\"levels\":[]}"
        val c = levels[und]
        val now = System.currentTimeMillis()
        if ((c == null || now - c.atMs > LEVELS_MS) && computing.add(und)) {
            levelJobs[und] = scope.launch(levelDispatcher) {
                try { runCatching { levels[und] = Cached(System.currentTimeMillis(), computeLevels(und)) } } finally { computing.remove(und) }
            }
        }
        return c?.json ?: "{\"levels\":[]}"
    }

    /** TEST ONLY: waits for the level maps being computed. */
    internal suspend fun awaitLevelsForTest() { levelJobs.values.toList().forEach { it.join() } }

    /** The day's 09:20 largest-OI call and put strikes per index, kept once read (memory): the research's held rate is of 09:20. */
    private val oi0920 = ConcurrentHashMap<String, Triple<LocalDate, Double?, Double?>>()

    private fun maxOi0920(und: String, d: LocalDate): Pair<Double?, Double?> {
        oi0920[und]?.takeIf { it.first == d }?.let { return it.second to it.third }
        val (readMs, chain) = runCatching { Market.chainInMemory(und) }.getOrNull() ?: return null to null
        val read = Instant.ofEpochMilli(readMs).atZone(IST)
        if (read.toLocalDate() != d) return null to null
        val minute = read.hour * 60 + read.minute
        val snap = chain.pricedAt != null
        val ce = LevelMap.maxOiStrikeAt0920(chain.series, com.optionslab.engine.Right.CE, minute, snap)
        val pe = LevelMap.maxOiStrikeAt0920(chain.series, com.optionslab.engine.Right.PE, minute, snap)
        if (ce != null && pe != null) oi0920[und] = Triple(d, ce, pe)
        return ce to pe
    }

    private fun computeLevels(und: String): String {
        val m = market(und) ?: return "{\"levels\":[]}"
        val bars = com.optionslab.app.ira.IraHub.recentBars(m)
        val d = runCatching { Market.today() }.getOrDefault(LocalDate.now(IST))
        val (oiCall, oiPut) = runCatching { maxOi0920(und, d) }.getOrDefault(null to null)
        val vwap = runCatching { OrderFlowLive.chartLevels.value[und].orEmpty().filter { it.kind == com.optionslab.ira.Auction.ChartLevel.Kind.VWAP }
            .map { it.label to it.price } }.getOrDefault(emptyList())
        val map = LevelMap.map(bars, d, oiCall, oiPut, vwap, ::trading)
        val last = bars.lastOrNull()?.c
        val a = JSONArray()
        for (l in map) a.put(JSONObject().put("type", if (l.type == LevelMap.Type.VWAP_BAND) "VB:" + l.name else l.type.name)
            .put("label", l.label()).put("price", l.price).put("side", if (last != null && l.price < last) "S" else "R"))
        val note = if (map.any { it.type == LevelMap.Type.PDH }) LevelMap.note(LevelMap.counted(bars, d, ::trading)) else LevelMap.NO_HISTORY
        return JSONObject().put("levels", a).put("note", note).toString()
    }

    // ---- explain a candle -----------------------------------------------------------------------------------------------------

    /**
     * Jarvis on one candle of [symbol] starting at [startSec] (epoch seconds), [minutes] long, from the chart's own values and
     * the same day's other candles ([dayBars]: [time, o, h, l, c, v] rows). An IO thread: it reads the day's recordings.
     */
    suspend fun explain(symbol: String, minutes: Int, startSec: Long, o: Double, h: Double, l: Double, c: Double, v: Double?,
                        dayBars: List<DoubleArray>, und: String?): String {
        val start = LocalDateTime.ofInstant(Instant.ofEpochSecond(startSec), IST)
        val end = start.plusMinutes(minutes.toLong())
        val vix = runCatching { com.optionslab.app.ira.IraHub.recentBars(IraMarket.VIX) }.getOrDefault(emptyList())
        fun vixAt(t: LocalDateTime): Double? = vix.lastOrNull { !it.t.isAfter(t) && it.t.toLocalDate() == t.toLocalDate() }?.c
        val recordable = und == "NIFTY" || und == "BANKNIFTY"
        val none: Pair<List<CandleExplain.Recorded>, List<CandleExplain.Flow>> = Pair(emptyList(), emptyList())
        val (rec, flow) = if (recordable && minutes < 1440 && und != null) runCatching { recorded(und, start, end) }.getOrDefault(none) else none
        val news = runCatching { com.optionslab.app.ira.IraHub.state.value.news.mapNotNull { hd -> hd.at?.let { LocalDateTime.ofInstant(it, IST) to hd.title } } }
            .getOrDefault(emptyList())
        val others = dayBars.filter { it.size >= 5 && it[0].toLong() != startSec }
        val lines = CandleExplain.explain(CandleExplain.Inputs(symbol, start, minutes, o, h, l, c, v?.takeIf { it > 0 },
            others.map { it[2] - it[3] }, others.mapNotNull { it.getOrNull(5) }, vixAt(start.minusMinutes(1)) ?: vixAt(start), vixAt(end.minusMinutes(1)),
            rec.firstOrNull(), rec.lastOrNull()?.takeIf { rec.size > 1 }, flow, news, recordable))
        return lines.joinToString("\n")
    }

    /** The recorded minutes of [und] from [start] to [end] (LiveRecorder's market file, TickZone's flow file). */
    private fun recorded(und: String, start: LocalDateTime, end: LocalDateTime): Pair<List<CandleExplain.Recorded>, List<CandleExplain.Flow>> {
        val dir = LiveRecorder.days().firstOrNull { it.first == start.toLocalDate() }?.second ?: return Pair(emptyList(), emptyList())
        val from = start.toLocalTime(); val to = end.toLocalTime()
        fun inside(m: String?) = runCatching { LocalTime.parse(m) }.getOrNull()?.let { !it.isBefore(from) && it.isBefore(to) } == true
        val rec = ArrayList<CandleExplain.Recorded>()
        lines(File(dir, com.optionslab.ira.LiveRecord.MARKET_FILE)) { j ->
            if (j.optString("t") != "M" || j.optString("ix") != und || !inside(j.optString("m"))) return@lines
            val ch = j.optJSONArray("chain")
            val legs = (0 until (ch?.length() ?: 0)).mapNotNull { i -> ch!!.optJSONArray(i)?.let { a ->
                CandleExplain.Leg(a.optDouble(0), a.optString(1), a.optDouble(2).takeIf { !it.isNaN() }, if (a.isNull(3)) null else a.optLong(3),
                    if (a.isNull(4)) null else a.optLong(4), if (a.isNull(5)) null else a.optDouble(5).takeIf { !it.isNaN() })
            } }
            fun dd(k: String) = if (j.has(k) && !j.isNull(k)) j.optDouble(k).takeIf { !it.isNaN() } else null
            rec += CandleExplain.Recorded(dd("spot"), dd("fut"), dd("basis"), dd("vix"), legs)
        }
        val flow = ArrayList<CandleExplain.Flow>()
        lines(File(dir, com.optionslab.ira.TickZone.FLOW_FILE)) { j ->
            if (j.optString("t") != "A" || j.optString("und") != und || j.optString("kind") != "FUT" || !inside(j.optString("m"))) return@lines
            flow += CandleExplain.Flow(if (j.has("vol")) j.optLong("vol") else null, if (j.has("delta")) j.optLong("delta") else null,
                if (j.has("vwap")) j.optDouble("vwap") else null)
        }
        return rec to flow
    }

    private inline fun lines(f: File, each: (JSONObject) -> Unit) {
        if (!f.exists()) return
        GZIPInputStream(f.inputStream().buffered()).bufferedReader(Charsets.UTF_8).useLines { seq ->
            for (s in seq) runCatching { JSONObject(s) }.getOrNull()?.let { each(it) }
        }
    }

    /** TEST ONLY. */
    internal fun resetForTest() {
        _mode.value = EventShield.DEFAULT_MODE; _shadowDays.value = emptySet(); today = LocalDate.MIN to emptyList()
        // A level map a test started ends before the next test (it would write its cache after the clear otherwise).
        levelJobs.values.forEach { j -> kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(5_000L) { j.join() } } }
        levelJobs.clear(); computing.clear(); levels.clear(); oi0920.clear()
        levelDispatcher = Dispatchers.Default
        briefs.clear(); briefTries = MarketContextCore.BriefTries(); shadowSince = null; shadowVerdictAt = null; rawEventsForTest = null
    }

    /** TEST ONLY: the calendar's event names per day (null: the real calendar). Cleared by [resetForTest]. */
    @Volatile internal var rawEventsForTest: ((LocalDate) -> List<String>)? = null

    /** TEST ONLY: the day's tries of the "days like today" brief. */
    internal fun briefTriesForTest(): MarketContextCore.BriefTries = briefTries

    /** TEST ONLY: shadowed event days. */
    internal fun setShadowDaysForTest(days: Set<String>) { _shadowDays.value = days }

    /** TEST ONLY: today's events as the refresh would keep them. */
    internal fun setTodayForTest(d: LocalDate, events: List<String>) { today = d to events }
}
