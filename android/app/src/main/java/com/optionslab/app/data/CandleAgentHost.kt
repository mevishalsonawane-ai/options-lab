package com.optionslab.app.data

import android.content.Context
import com.optionslab.ira.AgentLessons
import com.optionslab.ira.CandleAgent
import com.optionslab.ira.CandleBook
import com.optionslab.ira.CandleRead
import com.optionslab.ira.Candles
import com.optionslab.ira.GammaRegime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import com.optionslab.ira.Market as IraMarket

/**
 * The Candle agent in the app (Boss, 20 Oct 2026: "an agent who will only study the candle patterns ... it should have the
 * same characteristics as the other agents: sharing the knowledge, communicating with each other"). The agent itself is
 * [CandleAgent] (ira-core, pure); this host feeds it and keeps it.
 *
 *  - Its own supervised worker ("Candle agent", the watch's analysis group, [delayMs]): one BATCH every 5 minutes in NSE's
 *    session or MCX's (10 with the battery saver), one more after the close to settle the day, then one an hour around the clock
 *    (Boss, 20 Oct: "agent should be running 24x7 to learn new things from candles"). Never per tick.
 *  - The 24x7 study ([studyOffHours], from the hourly study worker): once a day outside market hours, the last
 *    [DEEP_SESSIONS] sessions of 5- and 15-minute candles the app already keeps for the market study, every candle's outcome
 *    counted once (never twice: [CandleBook.Stats.countable]).
 *  - Open positions come first: a batch waits for the start-up's positions and stream steps, skips while memory is short,
 *    and the agent is the second thing let go when Android asks for memory ([releaseForMemory]; its counts are on disk).
 *    It reads nothing the position watch needs and slows none of it.
 *  - Its inputs are what the app already holds, in memory: the indices' 1-minute history (IraHub's), the option chain the
 *    app last read ([MemoryChain]), the gamma regime ([GammaLive]), the future's VWAP ([OrderFlowLive]), the expiry calendar,
 *    and the other agents' posts on the findings bus. MCX's near futures are the one read it makes itself (their 1-minute
 *    candles, at most every 10 minutes while MCX is open, on the worker's thread).
 *  - It posts its reads on the findings bus with NO direction (never a consensus vote), writes its lessons into Jarvis's
 *    nightly pass ([lessons]) and is counted on the shared knowledge's "What I learned" sheet.
 *  - Its counts: noBackupFilesDir/candles/stats.txt, written on IO after a batch that changed them (never on the main thread,
 *    the order path or the tick path).
 *
 * It never trades: nothing here places, sizes, stops or changes an order or an arm.
 */
object CandleAgentHost {
    const val NAME = "Candle agent"
    private val INDICES = listOf(IraMarket.NIFTY, IraMarket.BANKNIFTY, IraMarket.SENSEX)
    /** The MCX futures the app's MCX arms watch. */
    val MCX = listOf("CRUDEOIL", "NATURALGAS", "GOLDM", "SILVERM")
    val TFS = listOf(1, 5, 15)
    private const val SESSIONS = 3
    private const val EVERY_MS = 5 * 60_000L
    private const val SAVER_MS = 10 * 60_000L
    private const val MCX_EVERY_MS = 10 * 60_000L
    private const val SEED_ASSET = "knowledge/candle_seed.json"
    private const val OFF_HOURS_MS = 60 * 60_000L
    private const val GAMMA_FRESH_MS = 15 * 60_000L
    const val DEEP_SESSIONS = 60

    /** What its page shows; refreshed after each batch (memory only). */
    data class View(
        val status: CandleAgent.Status? = null,
        val latest: List<CandleAgent.Explained> = emptyList(),
        val table: List<CandleBook.Compare> = emptyList(),
        val lessons: List<String> = emptyList(),
        val notes: List<String> = emptyList(),
        val exchanges: List<CandleAgent.Exchange> = emptyList(),
        val growth: String = "",
        val released: Boolean = false,
    )

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    @Volatile private var agent: CandleAgent? = null
    @Volatile private var lastBatchAt = 0L
    @Volatile private var settledDay: LocalDate? = null
    @Volatile private var deepDay: LocalDate? = null
    private val mcxBars = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<CandleRead.Bar>>>()
    /** The chain's OI per strike at the last batch, per index (for the OI change since). */
    private val lastOi = java.util.concurrent.ConcurrentHashMap<String, Map<Double, Pair<Long, Long>>>()

    fun enabled(): Boolean = !com.optionslab.app.BuildConfig.GOLD

    private fun file(context: Context) = File(context.noBackupFilesDir, "candles/stats.txt")

    /** The agent, loaded on first use (its counts file and the research seed): call on a worker thread only. */
    internal fun agentOrLoad(context: Context): CandleAgent {
        agent?.let { return it }
        synchronized(this) {
            agent?.let { return it }
            return load(context).also { agent = it; _view.value = _view.value.copy(released = false) }
        }
    }

    /** A fresh agent from its counts file and the research seed (a worker thread; not kept). */
    private fun load(context: Context): CandleAgent {
        val seed = CandleBook.seed(runCatching { context.assets.open(SEED_ASSET).bufferedReader().use { it.readText() } }.getOrNull())
        val stats = CandleBook.decode(runCatching { file(context).takeIf { it.isFile }?.readText() }.getOrNull())
        return CandleAgent(seed, stats)
    }

    /**
     * The worker's pace: a batch every 5 minutes in NSE's or MCX's session (10 with the saver), one more after NSE's close until
     * the day is settled, and one an hour otherwise (24x7: it hears the other agents and settles what it read).
     */
    fun delayMs(context: Context?): Long? {
        if (!enabled()) return null
        val nse = runCatching { Market.isTradingDay() && Market.minuteNow() in Market.OPEN..Market.foClose() }.getOrDefault(false)
        val mcx = runCatching { McxMarket.isOpen() }.getOrDefault(false)
        val settle = runCatching { Market.isTradingDay() && Market.minuteNow() > Market.foClose() && settledDay != Market.today() }.getOrDefault(false)
        if (!nse && !mcx && !settle) return (OFF_HOURS_MS - (System.currentTimeMillis() - lastBatchAt)).coerceIn(0L, OFF_HOURS_MS)
        val every = if (runCatching { com.optionslab.app.work.WatchWorkers.saver(context) }.getOrDefault(false)) SAVER_MS else EVERY_MS
        return (every - (System.currentTimeMillis() - lastBatchAt)).coerceIn(0L, every)
    }

    /** One batch (the worker's body): off the main thread; returns at once while memory is short. */
    suspend fun batch(context: Context) {
        lastBatchAt = System.currentTimeMillis()
        if (!enabled()) return
        // Open positions first: the start-up restores and watches them before any agent's work.
        PositionsFirst.awaitPositions(); PositionsFirst.awaitStream()
        if (MemoryTrim.underPressure()) return
        val a = withContext(Dispatchers.IO) { agentOrLoad(context) }
        val now = Market.now()
        val nowMs = System.currentTimeMillis()
        val nseOpen = runCatching { Market.isOpen() }.getOrDefault(false)
        val nseOver = runCatching { Market.isTradingDay() && Market.minuteNow() > Market.foClose() }.getOrDefault(false)
        val series = ArrayList<CandleAgent.Series>()
        for (m in INDICES) series += indexSeries(m, now.toLocalDateTime(), nseOver, nowMs, nseOpen)
        val mcxOpen = runCatching { McxMarket.isOpen() }.getOrDefault(false)
        if (mcxOpen) for (u in MCX) series += mcxSeries(u, now.toLocalDateTime(), nowMs)
        val heard = SmartWorkers.bus.recent(nowMs, kinds = CandleAgent.HEARS)
        val step = withContext(Dispatchers.Default) {
            runCatching { a.study(series, heard, nowMs, now.toLocalDateTime(), nseOpen || mcxOpen) }.onFailure { a.failed(it) }.getOrNull()
        } ?: return
        // Its reads on the bus as they are (no direction: never a vote in any strategy's consensus).
        for (p in step.posts) runCatching { SmartWorkers.bus.post(p) }
        if (step.changed) withContext(Dispatchers.IO) { save(context, a) }
        if (nseOver) settledDay = Market.today()
        if (step.posts.isNotEmpty() || step.counted > 0) SharedKnowledgeHost.added(NAME, "${step.posts.size} candle reads shared, ${step.counted} outcomes counted")
        if (heard.isNotEmpty()) SharedKnowledgeHost.read(NAME, "the other agents' posts on the findings bus")
        publish(a)
    }

    /**
     * The 24x7 study (the hourly study worker, off the main thread): once a day outside NSE's session, the last [DEEP_SESSIONS]
     * sessions of each index's 5- and 15-minute candles the app keeps for the market study (IraHub's two-year cache, read once a
     * day), read and their outcomes counted where never counted before. Skipped while memory is short or the battery budget says no.
     */
    suspend fun studyOffHours(context: Context) {
        if (!enabled()) return
        val today = runCatching { Market.today() }.getOrElse { return }
        val nse = runCatching { Market.isOpen() }.getOrDefault(true)
        if (nse || deepDay == today || MemoryTrim.underPressure()) return
        if (!runCatching { com.optionslab.app.work.BackgroundBudget.allow(com.optionslab.ira.BudgetRule.Job.CANDLE_STUDY) }.getOrDefault(false)) return
        try { deep(context, today) } finally { runCatching { com.optionslab.app.work.BackgroundBudget.done(com.optionslab.ira.BudgetRule.Job.CANDLE_STUDY) } }
    }

    private suspend fun deep(context: Context, today: LocalDate) {
        val a = withContext(Dispatchers.IO) { agentOrLoad(context) }
        val series = ArrayList<CandleAgent.Series>()
        for (m in INDICES) for (tf in listOf(5, 15)) {
            val bars = runCatching { com.optionslab.app.ira.IraHub.twoYears(m, tf) }.getOrNull().orEmpty()
            if (bars.isEmpty()) continue
            val days = bars.map { it.t.toLocalDate() }.distinct().takeLast(DEEP_SESSIONS).toSet()
            series += CandleAgent.Series(m.name, tf, bars.filter { it.t.toLocalDate() in days }.map { CandleRead.Bar(it.t, it.o, it.h, it.l, it.c) },
                9 * 60 + 15, sessionOver = true, deep = true)
        }
        if (series.isEmpty()) return
        val now = Market.now().toLocalDateTime()
        val step = withContext(Dispatchers.Default) {
            runCatching { a.study(series, emptyList(), System.currentTimeMillis(), now, false) }.onFailure { a.failed(it) }.getOrNull()
        } ?: return
        deepDay = today
        if (step.changed) withContext(Dispatchers.IO) { save(context, a) }
        if (step.counted > 0) SharedKnowledgeHost.added(NAME, "${step.counted} older candles studied")
        publish(a)
    }

    /** The index's last [SESSIONS] sessions of 1-minute bars (IraHub's, memory only), folded to 1, 5 and 15 minutes, closed only. */
    private fun indexSeries(m: IraMarket, now: LocalDateTime, over: Boolean, nowMs: Long, open: Boolean): List<CandleAgent.Series> {
        val all = runCatching { com.optionslab.app.ira.IraHub.recentBars(m) }.getOrDefault(emptyList())
        if (all.isEmpty()) return emptyList()
        val days = all.map { it.t.toLocalDate() }.distinct().takeLast(SESSIONS).toSet()
        val bars = all.filter { it.t.toLocalDate() in days }
        val name = m.name
        val todays = around(name, now.toLocalDate(), nowMs)
        return TFS.map { tf ->
            val folded = Candles.closed(Candles.fold(bars, tf, m), tf, now)
            // Today's chain, gamma and VWAP are what is known NOW: only the candles that closed in the last few candle lengths
            // get them (an older candle never gets a later chain: no look-ahead). The expiry day is a calendar fact all day.
            val freshFrom = now.minusMinutes(3L * tf)
            val around: (LocalDateTime) -> CandleRead.Around = { t ->
                when {
                    t.toLocalDate() != now.toLocalDate() -> CandleRead.Around()
                    !t.isBefore(freshFrom) -> todays
                    else -> CandleRead.Around(expiry = todays.expiry)
                }
            }
            CandleAgent.Series(name, tf, folded.map { CandleRead.Bar(it.t, it.o, it.h, it.l, it.c) }, 9 * 60 + 15, over, around, open = open)
        }
    }

    /** The chain, gamma, VWAP and expiry around [index] now (memory only). */
    private fun around(index: String, today: LocalDate, nowMs: Long): CandleRead.Around {
        val snap = runCatching { MemoryChain.read(index, nowMs) }.getOrNull()
        val oi = snap?.legs.orEmpty().groupBy { it.strike }.mapValues { (_, l) ->
            (l.firstOrNull { it.right == "CE" }?.oi ?: 0L) to (l.firstOrNull { it.right == "PE" }?.oi ?: 0L)
        }
        val was = lastOi[index]
        if (oi.isNotEmpty()) lastOi[index] = oi
        val strikes = oi.map { (k, v) ->
            val p = was?.get(k)
            CandleRead.Strike(k, v.first, v.second, if (p != null) v.first - p.first else 0L, if (p != null) v.second - p.second else 0L)
        }
        // A regime older than 15 minutes is not "now" (the trade manager's own rule): left out.
        val gamma = runCatching {
            GammaLive.state.value[index]?.takeIf { nowMs - it.atMs <= GAMMA_FRESH_MS }
                ?.let { r -> if (r.sign(GammaLive.convention.value) == GammaRegime.Sign.POSITIVE) "long" else "short" }
        }.getOrNull()
        val vwap = runCatching {
            OrderFlowLive.chartLevels.value[index]?.firstOrNull { it.kind == com.optionslab.ira.Auction.ChartLevel.Kind.VWAP }?.price
        }.getOrNull()
        val expiry = runCatching { Market.isExpiryDay(index, today) }.getOrDefault(false)
        return CandleRead.Around(strikes, gamma, vwap, expiry, if (vwap != null) "the future's flow" else null)
    }

    /** An MCX near future's 1-minute candles today (read at most every [MCX_EVERY_MS]), folded to 1, 5 and 15 minutes. */
    private suspend fun mcxSeries(u: String, now: LocalDateTime, nowMs: Long): List<CandleAgent.Series> {
        val kept = mcxBars[u]
        val bars = if (kept != null && nowMs - kept.first < MCX_EVERY_MS) kept.second else withContext(Dispatchers.IO) {
            val key = runCatching { com.optionslab.engine.mcx.McxInstruments.futures(McxMarket.cached(), u, now.toLocalDate(), 1).firstOrNull()?.upstoxKey }.getOrNull()
                ?: return@withContext emptyList()
            val nowSec = now.atZone(com.optionslab.engine.IST).toEpochSecond()
            // A failed read is tried again next batch (never kept as "no candles"); the worker's time limit still cuts it.
            val raw = try { Net.intraday(key) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
            raw.filter { it.epochSecond + 60 <= nowSec }.sortedBy { it.epochSecond }
                .map { b -> CandleRead.Bar(LocalDateTime.ofEpochSecond(b.epochSecond, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30)), b.open, b.high, b.low, b.close,
                    b.volume.takeIf { it > 0 }?.toDouble()) }
        }.also { if (it.isNotEmpty()) mcxBars[u] = nowMs to it }
        if (bars.isEmpty()) return emptyList()
        return TFS.map { tf -> CandleAgent.Series(u, tf, fold(bars, tf, now), 9 * 60) }
    }

    /** MCX minutes folded to [tf] from 09:00 (volume summed), closed candles only. */
    internal fun fold(bars: List<CandleRead.Bar>, tf: Int, now: LocalDateTime): List<CandleRead.Bar> {
        if (tf <= 1) return bars.filter { !it.t.plusMinutes(1).isAfter(now) }
        return bars.groupBy { b ->
            val m = b.t.hour * 60 + b.t.minute - 9 * 60
            b.t.toLocalDate().atStartOfDay().plusMinutes((Math.floorDiv(m, tf) * tf + 9 * 60).toLong())
        }.toSortedMap().map { (t, g) ->
            CandleRead.Bar(t, g.first().o, g.maxOf { it.h }, g.minOf { it.l }, g.last().c, g.mapNotNull { it.v }.takeIf { it.isNotEmpty() }?.sum())
        }.filter { !it.t.plusMinutes(tf.toLong()).isAfter(now) }
    }

    private fun save(context: Context, a: CandleAgent) {
        runCatching {
            val f = file(context)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "stats.tmp")
            tmp.writeText(a.encodeStats())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    private fun publish(a: CandleAgent) {
        // A batch that finished after the agent was let go never brings it back into view (or into memory).
        if (agent !== a) return
        val day = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        _view.value = View(a.status(), a.latest(), a.table(), a.lessons(day).map { "${it.name}: ${if (it.agrees) "agrees with" else "differs from"} the research (${it.n} counted)" },
            a.notes(), a.exchanges(), a.growthWords())
    }

    /** Memory is short: the agent is let go (its counts are on disk) and read again from its next batch. */
    fun releaseForMemory() {
        agent = null
        mcxBars.clear()
        lastOi.clear()
        _view.value = View(released = true)
    }

    /** Tonight's lessons for Jarvis's nightly pass (a worker thread: it may load the agent's counts). */
    fun lessons(context: Context, day: LocalDate): List<AgentLessons.CandleResult> {
        if (!enabled()) return emptyList()
        // Under memory pressure: read from a throwaway copy, never kept loaded overnight.
        val r = runCatching { (agent ?: if (MemoryTrim.underPressure()) load(context) else agentOrLoad(context)).lessons(day) }.getOrDefault(emptyList())
        if (r.isNotEmpty()) SharedKnowledgeHost.added(NAME, "${r.size} candle lessons for Jarvis")
        return r
    }

    /** Jarvis's answer from the agent in memory (null: the agent is not loaded - it says so). Never touches the disk. */
    fun answer(q: CandleAgent.Question, inst: String?, locked: Boolean): String =
        agent?.answer(q, inst, locked, System.currentTimeMillis())
            ?: "The Candle agent is not loaded just now, Boss (it starts with its next batch in market hours)."

    /** Another agent's question (memory only; null when the agent is not loaded). */
    fun ask(from: String, q: CandleAgent.Ask): CandleAgent.Reply? = agent?.ask(from, q, System.currentTimeMillis())

    /** The diagnostics' line. */
    fun diagLine(): String = agent?.status()?.words() ?: if (_view.value.released) "Candle agent: released to free memory" else "Candle agent: not loaded yet"

    /** TEST ONLY. */
    internal fun resetForTest(a: CandleAgent? = null) {
        agent = a; lastBatchAt = 0L; settledDay = null; deepDay = null; mcxBars.clear(); lastOi.clear()
        _view.value = View()
        if (a != null) publish(a)
    }
}
