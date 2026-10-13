package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.CandleExplain
import com.optionslab.ira.DaysLikeToday
import com.optionslab.ira.EventShield
import com.optionslab.ira.ExpiryCard
import com.optionslab.ira.LevelMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    /** At the app's start: the switch read on an IO thread, then the refresh loop on this object's own thread. */
    fun init(context: Context) {
        if (!enabled() || started) return
        started = true
        app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            runCatching { _mode.value = EventShield.mode(SecurePrefs.getString(K_PAUSE)) }
            runCatching { _shadowDays.value = SecurePrefs.getString(K_SHADOW_DAYS).orEmpty().split(',').filter { it.isNotBlank() }.toSet() }
            while (true) {
                runCatching { refresh() }
                delay(REFRESH_MS)
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
        _mode.value = m
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
     * A NEW entry by arm [key] ([live]: a Zerodha entry). Memory only - no vault, no file, no network: the order path may ask
     * it. A SHADOW verdict is logged by the caller with its other shadow notes.
     */
    fun entry(key: String, live: Boolean): EventShield.Verdict {
        val m = _mode.value
        if (m == EventShield.Mode.OFF || live) return EventShield.GO
        val (d, ev) = today
        if (ev.isEmpty() || d != runCatching { Market.today() }.getOrNull()) return EventShield.GO
        val frozen = runCatching { LiquidityTest.frozenKey(key) }.getOrDefault(true)
        return EventShield.decide(m, ev, live, frozen)
    }

    // ---- events ------------------------------------------------------------------------------------------------------------

    /** US CPI releases the app knows (08:30 New York, after the Indian close: the next Indian session reacts). Boss adds more. */
    private val US_CPI = listOf("2026-10-14").map { LocalDate.parse(it) }

    /** The counting event names on [day]: the event calendar (RBI, Budget, Boss's own), the Fed nights and US CPI. */
    fun eventsOn(day: LocalDate): List<String> {
        val cal = runCatching { EventDays.calendar.value.on(day).map { it.name } }.getOrDefault(emptyList())
        val fed = com.optionslab.ira.Events.builtIn(day, day).map { it.name }
        val cpi = if (US_CPI.any { nextSession(it) == day }) listOf("US CPI overnight") else emptyList()
        return (cal + fed + cpi).filter(EventShield::counts).distinct()
    }

    private fun trading(d: LocalDate) = runCatching { Market.isTradingDay(d) }.getOrDefault(d.dayOfWeek.value <= 5)

    fun nextSession(after: LocalDate): LocalDate {
        var d = after.plusDays(1)
        var n = 0
        while (!trading(d) && n++ < 10) d = d.plusDays(1)
        return d
    }

    // ---- the card ----------------------------------------------------------------------------------------------------------

    data class Card(val event: String? = null, val expiryTitle: String? = null, val expiry: List<String> = emptyList(),
                    val days: Map<String, List<String>> = emptyMap(), val pause: EventShield.Mode = EventShield.DEFAULT_MODE) {
        val empty: Boolean get() = event == null && expiry.isEmpty() && days.isEmpty()
    }

    private val _card = MutableStateFlow(Card())
    val card: StateFlow<Card> = _card

    /** The event warning: today's (morning and through the day) or the next session's (the evening before). */
    fun eventWarning(now: LocalDateTime): String? {
        val d = now.toLocalDate()
        if (trading(d) && now.toLocalTime().isBefore(LocalTime.of(15, 30))) EventShield.warning(eventsOn(d), d, d)?.let { return it }
        val next = nextSession(d)
        return EventShield.warning(eventsOn(next), next, d)
    }

    private val INDICES = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")

    private fun expiringToday(d: LocalDate): List<String> =
        INDICES.filter { u -> runCatching { Market.isExpiryDay(u, d) }.getOrDefault(false) }

    @Volatile private var briefDay: LocalDate? = null
    private val briefs = ConcurrentHashMap<String, List<String>>()

    /** One pass (this object's thread): today's events, the card, and the morning brief once after 09:21. */
    internal suspend fun refresh(now: LocalDateTime = LocalDateTime.now(IST)) {
        val d = now.toLocalDate()
        today = d to eventsOn(d)
        // Shadow first: an event day lived through in SHADOW is kept (this loop runs on an IO thread).
        if (_mode.value == EventShield.Mode.SHADOW && today.second.isNotEmpty() && trading(d) && d.toString() !in _shadowDays.value) {
            val next = (_shadowDays.value + d.toString()).sorted().takeLast(50).toSet()
            _shadowDays.value = next
            runCatching { SecurePrefs.putAllSoon(mapOf(K_SHADOW_DAYS to next.joinToString(","))) }
        }
        val exp = if (trading(d)) expiringToday(d) else emptyList()
        if (briefDay != d) briefs.clear()
        if (trading(d) && !now.toLocalTime().isBefore(DaysLikeToday.READY_AT) && briefDay != d) {
            for (u in listOf("BANKNIFTY", "NIFTY")) runCatching { daysBrief(u, d) }.getOrNull()?.let { briefs[u] = it }
            if (briefs.isNotEmpty()) {
                briefDay = d
                if (com.optionslab.app.BuildConfig.JARVIS) runCatching {
                    com.optionslab.app.ira.IraHub.note("Morning brief - " + briefs.values.joinToString(" ") { it.joinToString(" ") })
                }
            }
        }
        _card.value = Card(eventWarning(now), exp.takeIf { it.isNotEmpty() }?.let(ExpiryCard::title),
            exp.firstOrNull()?.let { ExpiryCard.lines(it, now.toLocalTime()) }.orEmpty(), HashMap(briefs), _mode.value)
    }

    /** The 09:00 morning check's lines. */
    fun morningLines(now: LocalDateTime = LocalDateTime.now(IST)): List<String> {
        if (!enabled()) return emptyList()
        val out = ArrayList<String>()
        eventWarning(now)?.let { out += it }
        val exp = expiringToday(now.toLocalDate())
        if (exp.isNotEmpty()) out += ExpiryCard.title(exp) + ": " + ExpiryCard.lines(exp.first(), now.toLocalTime()).first()
        if (_mode.value != EventShield.Mode.OFF && eventsOn(now.toLocalDate()).isNotEmpty())
            out += "Your switch \"pause paper arms on event days\" is ${_mode.value.words}."
        out += "Days like today: on Home and from Jarvis after 09:21, once the open is known (context, not a forecast)."
        return out
    }

    /** The 15:45 report's line: the next session's event warning, if any. */
    fun eveningLines(now: LocalDateTime = LocalDateTime.now(IST)): List<String> {
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

    /** Past expiry days by NSE's rule from 1 Sep 2025 (NIFTY weekly Tuesday, BANKNIFTY the month's last Tuesday); a holiday's shift is not applied. */
    private fun ruleExpiry(und: String, d: LocalDate): Boolean = d.dayOfWeek == DayOfWeek.TUESDAY &&
        (und != "BANKNIFTY" || d.with(TemporalAdjusters.lastInMonth(DayOfWeek.TUESDAY)) == d)

    private suspend fun daysBrief(und: String, d: LocalDate): List<String>? {
        val m = market(und) ?: return null
        val bars = com.optionslab.app.ira.IraHub.recentBars(m)
        val vix = com.optionslab.app.ira.IraHub.recentBars(IraMarket.VIX)
        val dates = bars.map { it.t.toLocalDate() }.toSet()
        val expiries = dates.filter { it != d && ruleExpiry(und, it) }.toSet() + (if (runCatching { Market.isExpiryDay(und, d) }.getOrDefault(false)) setOf(d) else emptySet())
        val events = dates.filter { eventsOn(it).isNotEmpty() }.toSet()
        val arms = if (und == "BANKNIFTY") paperArms() else emptyMap()
        val phone = DaysLikeToday.fromCandles(bars, vix, expiries, events, d, arms)
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
    const val LEVELS_MS = 60_000L

    /**
     * The chart's level map for index [und] as JSON ({"levels":[{type,label,price,side}],"note"}), from the cache: never
     * computed on the caller's thread. A stale or missing one starts a computation on this object's thread; the chart asks
     * again on its next redraw.
     */
    fun levelJson(und: String): String {
        if (!enabled() || market(und) == null) return "{\"levels\":[]}"
        val c = levels[und]
        val now = System.currentTimeMillis()
        if ((c == null || now - c.atMs > LEVELS_MS) && computing.add(und)) scope.launch {
            try { runCatching { levels[und] = Cached(System.currentTimeMillis(), computeLevels(und)) } } finally { computing.remove(und) }
        }
        return c?.json ?: "{\"levels\":[]}"
    }

    private fun computeLevels(und: String): String {
        val m = market(und) ?: return "{\"levels\":[]}"
        val bars = com.optionslab.app.ira.IraHub.recentBars(m)
        val d = runCatching { Market.today() }.getOrDefault(LocalDate.now(IST))
        val chain = runCatching { Market.chainInMemory(und)?.second }.getOrNull()
        fun maxOi(r: com.optionslab.engine.Right) = chain?.series?.filter { it.right == r && it.oi.isNotEmpty() }?.maxByOrNull { it.oi.last() }?.strike
        val vwap = runCatching { OrderFlowLive.chartLevels.value[und].orEmpty().filter { it.kind == com.optionslab.ira.Auction.ChartLevel.Kind.VWAP }
            .map { it.label to it.price } }.getOrDefault(emptyList())
        val map = LevelMap.map(bars, d, maxOi(com.optionslab.engine.Right.CE), maxOi(com.optionslab.engine.Right.PE), vwap)
        val last = bars.lastOrNull()?.c
        val a = JSONArray()
        for (l in map) a.put(JSONObject().put("type", if (l.type == LevelMap.Type.VWAP_BAND) "VB:" + l.name else l.type.name)
            .put("label", l.label()).put("price", l.price).put("side", if (last != null && l.price < last) "S" else "R"))
        return JSONObject().put("levels", a).put("note", LevelMap.note(LevelMap.counted(bars, d))).toString()
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
    internal fun resetForTest() { _mode.value = EventShield.DEFAULT_MODE; _shadowDays.value = emptySet(); today = LocalDate.MIN to emptyList(); levels.clear(); briefs.clear(); briefDay = null }

    /** TEST ONLY: shadowed event days. */
    internal fun setShadowDaysForTest(days: Set<String>) { _shadowDays.value = days }

    /** TEST ONLY: today's events as the refresh would keep them. */
    internal fun setTodayForTest(d: LocalDate, events: List<String>) { today = d to events }
}
