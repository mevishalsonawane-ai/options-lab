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
 * Jarvis's study, round the clock (JarvisAlgo; the owner's wish, 2026-10-02): every night it counts, over the last two
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
                    val edges: List<com.optionslab.ira.PatternExpert.Edge> = emptyList())

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Kept> = _state

    private fun load(): Kept = runCatching {
        val o = JSONObject(com.optionslab.app.security.SecurePrefs.getString(KEY) ?: return Kept())
        val f = o.optJSONArray("f") ?: JSONArray()
        val n = o.optJSONArray("n") ?: JSONArray()
        val e = o.optJSONArray("e") ?: JSONArray()
        Kept(o.optString("at").takeIf { it.isNotEmpty() }?.let(Instant::parse),
            (0 until f.length()).mapNotNull { i -> runCatching { f.getJSONObject(i).let { x ->
                Study.Finding(IraMarket.valueOf(x.getString("m")), x.getString("k"), x.getString("s"), x.getString("o"),
                    x.getInt("d"), x.getDouble("r"), x.getDouble("a"), x.getDouble("b")) } }.getOrNull() },
            (0 until n.length()).mapNotNull { i -> runCatching { n.getJSONObject(i).let { Instant.parse(it.getString("t")) to it.getString("x") } }.getOrNull() },
            (0 until e.length()).mapNotNull { i -> runCatching { e.getJSONObject(i).let { x ->
                com.optionslab.ira.PatternExpert.Edge(IraMarket.valueOf(x.getString("m")), x.getInt("min"), com.optionslab.ira.PatternKind.valueOf(x.getString("k")),
                    x.getInt("n"), x.getDouble("r"), x.getDouble("a"), x.getDouble("b"), x.getDouble("v")) } }.getOrNull() })
    }.getOrDefault(Kept())

    private fun save(k: Kept) {
        _state.value = k
        runCatching {
            com.optionslab.app.security.SecurePrefs.put(KEY, JSONObject()
                .apply { k.at?.let { put("at", it.toString()) } }
                .put("f", JSONArray().apply { k.findings.forEach { x -> put(JSONObject().put("m", x.market.name).put("k", x.key).put("s", x.setup)
                    .put("o", x.outcome).put("d", x.days).put("r", x.rate).put("a", x.first).put("b", x.second)) } })
                .put("n", JSONArray().apply { k.overnight.forEach { (t, x) -> put(JSONObject().put("t", t.toString()).put("x", x)) } })
                .put("e", JSONArray().apply { k.edges.forEach { x -> put(JSONObject().put("m", x.market.name).put("min", x.minutes).put("k", x.kind.name)
                    .put("n", x.cases).put("r", x.rate).put("a", x.first).put("b", x.second).put("v", x.avgMovePct)) } })
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
        for (m in MARKETS) runCatching {
            val bars = IraHub.twoYears(m, 5) ?: return@runCatching
            found += Study.run(m, bars)
            // The pattern expert: every candle pattern's record on the 5- and 15-minute charts.
            for (min in listOf(5, 15)) edges += com.optionslab.ira.PatternExpert.edges(m, min, com.optionslab.ira.Candles.fold(bars, min, m))
        }
        if (found.isEmpty() && edges.isEmpty()) return
        save(_state.value.copy(at = Instant.now(), findings = found, edges = edges))
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
            out += "Candle patterns that held in both years: " + com.optionslab.ira.PatternExpert.best(k.edges).joinToString(" ")
        }
        val night = overnightNow()
        if (night.isEmpty()) out += "No overnight news that matters." else { out += "Overnight news:"; out += night }
        return out
    }

    /** The 9 AM brief, after the set-up checks: what applies today, and the overnight news (a few lines, to be spoken). */
    fun brief(): List<String> {
        val t = today().take(3)
        val night = _state.value.overnight.filter { it.first.isAfter(Instant.now().minusSeconds(OVERNIGHT_HOURS * 3600)) }.map { it.second }
        return t + (if (night.isEmpty()) emptyList() else listOf("Overnight, ${night.size} headline${if (night.size > 1) "s" else ""} mattered. " + night.last())) +
            (if (t.isEmpty() && _state.value.findings.isNotEmpty()) listOf("No setup that held in two years applies to today yet.") else emptyList())
    }
}

/** Every hour, day and night (JarvisAlgo): the night's news read quietly, and the study once after each close. */
class StudyWorker(ctx: android.content.Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!com.optionslab.app.BuildConfig.JARVIS) return Result.success()
        runCatching { IraHub.nightNews() }
        runCatching { IraStudy.studyIfDue() }
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
