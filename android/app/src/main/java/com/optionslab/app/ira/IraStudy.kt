package com.optionslab.app.ira

import com.optionslab.ira.Market as IraMarket
import com.optionslab.ira.Study
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Jarvis's study, round the clock (Jarvis; the owner's wish, 2026-10-02): every night it counts, over the last two
 * years of Nifty, BankNifty and FinNifty candles, what usually followed each known setup ([Study]) and keeps only what
 * held in both years; through the night it reads the trusted news feeds hourly and keeps what matters, quietly. At
 * 9 AM the morning check tells it all after the set-up checks; the Ira screen shows it; "what did you study?" asks it.
 * History and news, never a forecast. Kept encrypted on the phone.
 */
internal object IraStudy {
    private const val KEY = "jarvis.study"
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    val MARKETS = listOf(IraMarket.NIFTY, IraMarket.BANKNIFTY, IraMarket.FINNIFTY)
    private const val OVERNIGHT_HOURS = 18L

    data class Kept(val at: Instant? = null, val findings: List<Study.Finding> = emptyList(),
                    val overnight: List<Pair<Instant, String>> = emptyList(),
                    /** Each candle pattern's two-year record per index and chart (the pattern expert's knowledge). */
                    val edges: List<com.optionslab.ira.PatternExpert.Edge> = emptyList(),
                    /** Each index's at-the-money IV by day, for "are options cheap or dear?". */
                    val iv: Map<String, List<Pair<LocalDate, Double>>> = emptyMap(),
                    /** How the indices behaved on past event days: (kind, line). */
                    val events: List<Pair<String, String>> = emptyList(),
                    /** The monthly re-test of the arms: its month and lines. */
                    val armsMonth: String? = null, val arms: List<String> = emptyList())

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Kept> = _state

    private fun load(): Kept = runCatching {
        val o = JSONObject(com.optionslab.app.security.SecurePrefs.getString(KEY) ?: return Kept())
        val f = o.optJSONArray("f") ?: JSONArray()
        val n = o.optJSONArray("n") ?: JSONArray()
        val e = o.optJSONArray("e") ?: JSONArray()
        val ivo = o.optJSONObject("iv") ?: JSONObject()
        val ev = o.optJSONArray("ev") ?: JSONArray()
        val ar = o.optJSONArray("ar") ?: JSONArray()
        Kept(o.optString("at").takeIf { it.isNotEmpty() }?.let(Instant::parse),
            (0 until f.length()).mapNotNull { i -> runCatching { f.getJSONObject(i).let { x ->
                Study.Finding(IraMarket.valueOf(x.getString("m")), x.getString("k"), x.getString("s"), x.getString("o"),
                    x.getInt("d"), x.getDouble("r"), x.getDouble("a"), x.getDouble("b")) } }.getOrNull() },
            (0 until n.length()).mapNotNull { i -> runCatching { n.getJSONObject(i).let { Instant.parse(it.getString("t")) to it.getString("x") } }.getOrNull() },
            (0 until e.length()).mapNotNull { i -> runCatching { e.getJSONObject(i).let { x ->
                com.optionslab.ira.PatternExpert.Edge(IraMarket.valueOf(x.getString("m")), x.getInt("min"), com.optionslab.ira.PatternKind.valueOf(x.getString("k")),
                    x.getInt("n"), x.getDouble("r"), x.getDouble("a"), x.getDouble("b"), x.getDouble("v"),
                    x.optInt("pn"), x.optDouble("pv", 0.0), x.optDouble("pa", 0.0), x.optDouble("pb", 0.0), x.optInt("pna"), x.optInt("pnb")) } }.getOrNull() },
            ivo.keys().asSequence().associateWith { k -> ivo.getJSONArray(k).let { a -> (0 until a.length()).mapNotNull { i -> runCatching {
                a.getJSONArray(i).let { LocalDate.parse(it.getString(0)) to it.getDouble(1) } }.getOrNull() } } },
            (0 until ev.length()).mapNotNull { i -> runCatching { ev.getJSONArray(i).let { it.getString(0) to it.getString(1) } }.getOrNull() },
            o.optString("am").takeIf { it.isNotEmpty() }, (0 until ar.length()).map { ar.getString(it) })
    }.getOrDefault(Kept())

    private fun save(k: Kept) {
        _state.value = k
        runCatching {
            com.optionslab.app.security.SecurePrefs.put(KEY, JSONObject()
                .apply { k.at?.let { put("at", it.toString()) } }
                .put("f", JSONArray().apply { k.findings.forEach { x -> put(JSONObject().put("m", x.market.name).put("k", x.key).put("s", x.setup)
                    .put("o", x.outcome).put("d", x.days).put("r", x.rate).put("a", x.first).put("b", x.second)) } })
                .put("n", JSONArray().apply { k.overnight.forEach { (t, x) -> put(JSONObject().put("t", t.toString()).put("x", x)) } })
                .put("iv", JSONObject().apply { k.iv.forEach { (u, l) -> put(u, JSONArray().apply { l.forEach { (d, v) -> put(JSONArray().put(d.toString()).put(v)) } }) } })
                .put("ev", JSONArray().apply { k.events.forEach { (a, b) -> put(JSONArray().put(a).put(b)) } })
                .apply { k.armsMonth?.let { put("am", it) } }
                .put("ar", JSONArray().apply { k.arms.forEach { put(it) } })
                .put("e", JSONArray().apply { k.edges.forEach { x -> put(JSONObject().put("m", x.market.name).put("min", x.minutes).put("k", x.kind.name)
                    .put("n", x.cases).put("r", x.rate).put("a", x.first).put("b", x.second).put("v", x.avgMovePct)
                    .put("pn", x.priced).put("pv", x.optAvg).put("pa", x.optFirst).put("pb", x.optSecond).put("pna", x.pricedFirst).put("pnb", x.pricedSecond)) } })
                .toString())
        }
    }

    /**
     * The nightly study: once after each close (15:45 to 09:00), the two years of 5-minute candles of each index
     * counted again with the latest session in. Called hourly; it gates itself.
     */
    suspend fun studyIfDue(now: ZonedDateTime = ZonedDateTime.now(IST)) {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        val t = now.toLocalTime()
        if (t >= LocalTime.of(9, 0) && t < LocalTime.of(15, 45)) return
        val last = _state.value.at
        if (last != null && last.isAfter(now.toInstant().minusSeconds(12 * 3600))) return
        val found = ArrayList<Study.Finding>()
        val edges = ArrayList<com.optionslab.ira.PatternExpert.Edge>()
        val ivs = HashMap<String, List<Pair<LocalDate, Double>>>()
        val events = ArrayList<Pair<String, String>>()
        for (m in MARKETS) runCatching {
            // Each day's at-the-money IV over the last year, from the option prices the phone keeps.
            runCatching {
                val step = com.optionslab.ira.JarvisTrades.strikeStep(m.name)
                val from = now.toLocalDate().minusDays(400)
                ivs[m.name] = com.optionslab.app.data.Store.barSessions(m.name).filter { !it.day.isBefore(from) }
                    .mapNotNull { s -> com.optionslab.ira.IvRank.atm(s, step)?.let { s.day to it } }.toList()
            }
            val bars = IraHub.twoYears(m, 5) ?: return@runCatching
            found += Study.run(m, bars)
            com.optionslab.ira.EventStudy.run(m, Study.days(bars)).forEach { events += it.kind.name to it.text() }
            // The pattern expert: every candle pattern's record on the 5- and 15-minute charts.
            // Played as option trades on the real option prices the phone keeps (bundled and harvested days).
            for (min in listOf(5, 15)) edges += com.optionslab.ira.PatternExpert.edges(m, min, com.optionslab.ira.Candles.fold(bars, min, m),
                runCatching { com.optionslab.app.data.Store.barSessions(m.name) }.getOrNull())
        }
        runCatching { regimeStudy(now) }
        if (found.isEmpty() && edges.isEmpty()) return
        save(_state.value.copy(at = Instant.now(), findings = found, edges = edges, iv = ivs.ifEmpty { _state.value.iv }, events = events))
        runCatching { armRetestIfDue(now) }
    }

    private const val REGIME_KEY = "jarvis.regime"

    /**
     * The market's regime now for each index, and how each arm did on days of each regime (BankNifty, the days the phone
     * keeps): saved for "what is the market regime" and "which arms suit this market".
     */
    private suspend fun regimeStudy(now: ZonedDateTime) {
        val out = ArrayList<String>()
        var bankRegimes: Map<LocalDate, com.optionslab.ira.Regime.Kind> = emptyMap()
        var bankNow: com.optionslab.ira.Regime.Kind? = null
        val kinds = HashMap<String, String>()
        var bankGaps: Map<LocalDate, Double> = emptyMap()
        for (m in MARKETS) {
            val days = Study.days(IraHub.twoYears(m, 5) ?: continue)
            val k = com.optionslab.ira.Regime.of(days) ?: continue
            out += com.optionslab.ira.Regime.say(m, k, days)
            kinds[m.name] = k.name
            if (m == com.optionslab.ira.Market.BANKNIFTY) { bankRegimes = com.optionslab.ira.Regime.history(days); bankNow = k
                bankGaps = days.mapNotNull { d -> d.gapPct?.let { d.date to it } }.toMap() }
        }
        if (bankRegimes.isNotEmpty()) {
            val r = com.optionslab.engine.orb.ArmsBacktest.run(com.optionslab.app.data.Store.barSessions("BANKNIFTY"))
            val trades = r.trades.map { com.optionslab.ira.ArmHealth.T(it.day, it.arm, it.net) }
            runCatching { IraCoach.saveGapRecord(com.optionslab.ira.GapPlan.arms(trades, bankGaps)) }
            val by = com.optionslab.ira.Regime.armsBy(trades, bankRegimes)
            if (by.isNotEmpty()) out += listOf("BankNifty arms by regime:") + by
            bankNow?.let { k -> com.optionslab.ira.Regime.suits(trades, bankRegimes, k).takeIf { it.isNotEmpty() }
                ?.let { out += "On ${k.label} days like now, these arms made money: ${it.joinToString(", ")}." } }
        }
        if (kinds.isNotEmpty()) com.optionslab.app.security.SecurePrefs.put("jarvis.regime.kinds", JSONObject(kinds as Map<*, *>).toString())
        if (out.isNotEmpty()) com.optionslab.app.security.SecurePrefs.put(REGIME_KEY, org.json.JSONArray(out + "Read ${now.toLocalDate()}.").toString())
    }

    /** The regime the nightly study read for [m], or null. */
    fun regimeOf(m: IraMarket): com.optionslab.ira.Regime.Kind? = runCatching {
        com.optionslab.ira.Regime.Kind.valueOf(JSONObject(com.optionslab.app.security.SecurePrefs.getString("jarvis.regime.kinds") ?: "{}").getString(m.name))
    }.getOrNull()

    fun regimeLines(): List<String> = runCatching {
        val a = org.json.JSONArray(com.optionslab.app.security.SecurePrefs.getString(REGIME_KEY) ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList()).ifEmpty { listOf("I read the market regime in the nightly study; it has not run yet.") }

    /**
     * The Saturday report card (from 09:00, once a week): the week's P&L in the app's mode, Jarvis's suggestions taken
     * and skipped and how they did, the best and worst arm, and one habit to fix - shown, noted and said.
     */
    suspend fun reportCardIfDue(now: ZonedDateTime = ZonedDateTime.now(IST)) {
        if (now.dayOfWeek != java.time.DayOfWeek.SATURDAY || now.toLocalTime() < LocalTime.of(9, 0)) return
        val key = "jarvis.reportcard"
        val week = now.toLocalDate().toString()
        if (com.optionslab.app.security.SecurePrefs.getString(key) == week) return
        val live = com.optionslab.app.data.AppSettings.load().live
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        val today = now.toLocalDate()
        val all = IraAccount.trips(live, owners)
        val trips = all.filter { !it.closedAt.toLocalDate().isBefore(today.minusDays(6)) }
        val arms = trips.groupBy { it.owner }.map { (o, l) -> com.optionslab.ira.ReportCard.ArmWeek(o, l.sumOf { it.net }, l.size) }
        val habit = com.optionslab.ira.Insights.patterns(if (live) "Zerodha" else "Paper", all).drop(1).firstOrNull()
        val lines = com.optionslab.ira.ReportCard.lines(if (trips.isEmpty()) null else trips.sumOf { it.net }, trips.size,
            IraNewsTrades.weekSuggestions(today), arms, habit) +
            listOfNotNull(com.optionslab.ira.TimeOfDay.line(IraJournal.trips())) + com.optionslab.ira.TradeReasons.lines(IraJournal.noted())
        IraHub.appContext()?.let { JarvisPopup.show(it, "Boss, your week's report card", lines.joinToString(" ")) }
        IraHub.note(com.optionslab.ira.Address.boss("Your week's report card. " + lines.joinToString(" ")))
        JarvisVoice.announce("Good morning, Boss. Your week's report card. " + lines.joinToString(" ") { com.optionslab.ira.Wake.spoken(it, 1) })
        IraActivity.add("Gave the weekly report card.")
        com.optionslab.app.security.SecurePrefs.put(key, week)
    }

    fun ivHistory(u: String): List<Pair<LocalDate, Double>> = _state.value.iv[u].orEmpty()

    /** How the indices behaved on past days like an event coming today (RBI, the Fed, the Budget). */
    fun eventLines(names: List<String>): List<String> {
        val kinds = names.mapNotNull { com.optionslab.ira.EventStudy.kindOf(it)?.name }.toSet()
        return _state.value.events.filter { it.first in kinds }.map { it.second }
    }

    /**
     * Once a month, after the close: every arm backtested again on the BankNifty days the phone keeps, its last two
     * months against its whole record; an arm slipping is told at once.
     */
    suspend fun armRetestIfDue(now: ZonedDateTime = ZonedDateTime.now(IST)) {
        val month = now.toLocalDate().withDayOfMonth(1).toString()
        if (_state.value.armsMonth == month) return
        val r = com.optionslab.engine.orb.ArmsBacktest.run(com.optionslab.app.data.Store.barSessions("BANKNIFTY"))
        val checks = com.optionslab.ira.ArmHealth.check(r.trades.map { com.optionslab.ira.ArmHealth.T(it.day, it.arm, it.net) }, now.toLocalDate())
        if (checks.isEmpty()) return
        val lines = checks.map { it.text() }
        save(_state.value.copy(armsMonth = month, arms = lines))
        val slipping = checks.filter { it.slipping }
        if (slipping.isNotEmpty()) {
            IraHub.appContext()?.let { JarvisPopup.show(it, "Boss, an arm is slipping", slipping.joinToString(" ") { s -> s.text() }) }
            IraHub.note(com.optionslab.ira.Address.boss("My monthly re-test of the arms. " + lines.joinToString(" ")))
        }
    }

    /** A headline read overnight that matters, with what it means (kept 18 hours). */
    fun overnight(at: Instant, text: String) {
        val cut = Instant.now().minusSeconds(OVERNIGHT_HOURS * 3600)
        val k = _state.value
        if (k.overnight.any { it.second == text }) return
        save(k.copy(overnight = (k.overnight.filter { it.first.isAfter(cut) } + (at to text)).sortedBy { it.first }.takeLast(12)))
    }

    fun overnightNow(): List<String> {
        val cut = Instant.now().minusSeconds(OVERNIGHT_HOURS * 3600)
        return _state.value.overnight.filter { it.first.isAfter(cut) }.map { (t, x) -> t.atZone(IST).let { "%02d:%02d".format(it.hour, it.minute) } + " · " + x }
    }

    /**
     * What the study says about today: during or after today's session, the setups today showed; before the open,
     * those already known (yesterday's move and close, the weekday).
     */
    fun today(): List<String> {
        val k = _state.value
        if (k.findings.isEmpty()) return emptyList()
        val now = ZonedDateTime.now(IST)
        val day = com.optionslab.app.data.Market.today()
        return MARKETS.flatMap { m ->
            val bars = IraHub.recentBars(m)
            if (bars.isEmpty()) return@flatMap emptyList()
            val f = k.findings.filter { it.market == m }
            if (bars.last().t.toLocalDate() == now.toLocalDate()) Study.today(f, bars)
            else Study.next(f, bars, nextSession(day))
        }
    }

    private fun nextSession(from: LocalDate): LocalDate {
        var d = from
        val now = ZonedDateTime.now(IST)
        if (d == now.toLocalDate() && now.toLocalTime() >= LocalTime.of(15, 30)) d = d.plusDays(1)
        repeat(10) { if (!com.optionslab.app.data.Market.isTradingDay(d)) d = d.plusDays(1) }
        return d
    }

    /** Everything, for "what did you study?" and the Ira screen. */
    fun lines(): List<String> {
        val k = _state.value
        val out = ArrayList<String>()
        if (k.at == null) out += "No study yet: Jarvis studies two years of Nifty, BankNifty and FinNifty candles each night after the close."
        else {
            out += "Studied " + k.at.atZone(IST).let { "${it.dayOfMonth} ${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }.take(3)}, %02d:%02d".format(it.hour, it.minute) } +
                " IST: ${k.findings.size} setups counted over two years, ${k.findings.count { it.held }} held in both years."
            out += today()
            out += Study.summary(k.findings)
            out += k.events.map { it.second }
            if (k.arms.isNotEmpty()) { out += "Arms re-tested this month:"; out += k.arms }
            out += "Candle patterns that held in both years: " + com.optionslab.ira.PatternExpert.best(k.edges).joinToString(" ")
        }
        val night = overnightNow()
        if (night.isEmpty()) out += "No overnight news that matters." else { out += "Overnight news:"; out += night }
        return out
    }

    /** The 9 AM brief, after the set-up checks: what applies today, and the overnight news (a few lines, to be spoken). */
    fun brief(): List<String> {
        val events = runCatching { IraEvents.upcoming(0).filter { it.day == com.optionslab.app.data.Market.today() }.map { it.name } }.getOrDefault(emptyList())
        val t = eventLines(events).take(2) + today().take(3)
        val night = _state.value.overnight.filter { it.first.isAfter(Instant.now().minusSeconds(OVERNIGHT_HOURS * 3600)) }.map { it.second }
        return t + (if (night.isEmpty()) emptyList() else listOf("Overnight, ${night.size} headline${if (night.size > 1) "s" else ""} mattered. " + night.last())) +
            (if (t.isEmpty() && _state.value.findings.isNotEmpty()) listOf("No setup that held in two years applies to today yet.") else emptyList())
    }
}

/** Every hour, day and night (Jarvis): the night's news read quietly, and the study once after each close. */
class StudyWorker(ctx: android.content.Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!com.optionslab.app.BuildConfig.JARVIS) return Result.success()
        runCatching { IraHub.nightNews() }
        runCatching { IraStudy.studyIfDue() }
        runCatching { IraStudy.reportCardIfDue() }
        return Result.success()
    }

    companion object {
        fun schedule(context: android.content.Context) {
            if (!com.optionslab.app.BuildConfig.JARVIS) return
            val req = androidx.work.PeriodicWorkRequestBuilder<StudyWorker>(1, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build()
            runCatching { androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork("jarvis.study", androidx.work.ExistingPeriodicWorkPolicy.KEEP, req) }
        }
    }
}
