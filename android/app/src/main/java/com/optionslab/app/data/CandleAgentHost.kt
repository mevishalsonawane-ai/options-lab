package com.optionslab.app.data

import android.content.Context
import com.optionslab.ira.AgentLessons
import com.optionslab.ira.CandleAgent
import com.optionslab.ira.CandleBook
import com.optionslab.ira.CandlePage
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
 *  - The 24x7 study ([studyOffHours], from the hourly study worker): once a day outside NSE's and MCX's sessions, up to
 *    [DEEP_SESSIONS] more sessions of the indices' 5-minute candles the market study already holds in memory (nothing is
 *    fetched for it), each night going further back than the last ([CandleBook.Stats.firsts]), folded to 15 minutes too;
 *    every candle's outcome counted once, never twice ([CandleBook.Stats.countable]).
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
        /** 20 Oct, enhancement round 2: each lesson and note with its instrument, so the page's chips can filter them. */
        val lessons: List<CandlePage.Line> = emptyList(),
        val notes: List<CandlePage.Line> = emptyList(),
        val exchanges: List<CandleAgent.Exchange> = emptyList(),
        val growth: String = "",
        val released: Boolean = false,
        /** NSE or MCX was open when this view was made (the page's "as of" line until it reads the markets itself). */
        val open: Boolean = false,
    )

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    @Volatile private var agent: CandleAgent? = null
    @Volatile private var lastBatchAt = 0L
    /** The index's cash close (its candles end then). */
    private const val INDEX_CLOSE_H = 15
    private const val INDEX_CLOSE_M = 30
    /** How many MCX settle batches ran in this process (tests: one a session). */
    @Volatile internal var mcxSettles = 0
    @Volatile internal var settledDay: LocalDate? = null
    @Volatile private var deepDay: LocalDate? = null
    /** 22 Oct: the kept study day ([K_DEEP]) was read back this process. */
    @Volatile private var deepRead = false
    private const val K_DEEP = "candle.deep.day"

    /** An MCX future's minutes as last fetched: when ([ms] the phone's clock, [at] the market's) and the finished minutes then. */
    private class Fetched(val ms: Long, val at: LocalDateTime, val bars: List<CandleRead.Bar>)
    private val mcxBars = java.util.concurrent.ConcurrentHashMap<String, Fetched>()
    /** One writer of the counts file at a time (the batch worker and the hourly study worker both save). */
    private val saveLock = Any()
    /** The chain's OI per strike at the last batch, per index (for the OI change since). */
    private val lastOi = java.util.concurrent.ConcurrentHashMap<String, Map<Double, Pair<Long, Long>>>()

    fun enabled(): Boolean = !com.optionslab.app.BuildConfig.GOLD

    private fun file(context: Context) = File(context.noBackupFilesDir, "candles/stats.txt")

    /**
     * 26 Oct (Erase everything): the app's context, kept at the start ([init]) or on the agent's first load, so [wipe] can delete
     * its counts without being handed one; and the count [wipe] bumps, so a batch that began before an erase never saves its
     * agent's counts after it ([save]).
     */
    @Volatile private var appContext: Context? = null
    @Volatile private var wipeGen = 0

    fun init(context: Context) { appContext = context.applicationContext }

    /** The agent, loaded on first use (its counts file and the research seed): call on a worker thread only. */
    internal fun agentOrLoad(context: Context): CandleAgent {
        agent?.let { return it }
        if (appContext == null) appContext = context.applicationContext
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
        val mcxState = runCatching { McxMarket.openOrNull() }.getOrNull()
        val mcx = mcxState == true
        val settle = runCatching { Market.isTradingDay() && Market.minuteNow() > Market.foClose() && settledDay != Market.today() }.getOrDefault(false)
        // 20 Oct, review: MCX closed with its minutes still kept: one more batch settles its last candles (they were dropped).
        // Second review: only when MCX is KNOWN closed (a failed read is not a close).
        val mcxSettle = mcxState == false && mcxBars.isNotEmpty()
        // Off hours: a batch once an hour, asked again every minute (never one long wait that would sleep through the open),
        // and only while the agent is loaded - one let go for memory is not brought back just to idle.
        // Battery audit (27 Oct): with nothing held anywhere, the agent rests on a low battery (30% or less, not charging) or
        // under Android's saver, and reads every 10 minutes with the screen off ([com.optionslab.ira.AnalysisRest]). Its reads
        // are knowledge only: no stop, exit or entry waits on them. With anything held it keeps its pace, as before.
        val held = runCatching { HeldNow.any(mcxLive = true) }.getOrDefault(true)
        val pace = com.optionslab.app.work.Battery.analysisPace(context, held)
        // MCX's evening (NSE closed): only while something is held on MCX or an armed MCX arm is in its entry minutes.
        val mcxOnly = mcx && !nse && !settle && !mcxSettle
        val mcxNeeded = !mcxOnly || com.optionslab.ira.AnalysisRest.candlesOnMcxEvening(
            runCatching { McxMarket.heldQuick() }.getOrDefault(true), runCatching { McxPaperArms.inEntryMinutesQuick() }.getOrDefault(true))
        if (!nse && !mcxNeeded) { if (mcxRestNoted != Market.today()) noteMcxRest(); return null }
        if (!nse && !mcx && !settle && !mcxSettle) return if (pace != com.optionslab.ira.AnalysisRest.Pace.REST && agent != null && System.currentTimeMillis() - lastBatchAt >= OFF_HOURS_MS) 0L else null
        val normal = if (runCatching { com.optionslab.app.work.WatchWorkers.saver(context) }.getOrDefault(false)) SAVER_MS else EVERY_MS
        val every = com.optionslab.ira.AnalysisRest.candleMs(pace, normal) ?: return null
        return (every - (System.currentTimeMillis() - lastBatchAt)).coerceIn(0L, every)
    }

    /** The day the MCX evening rest was noted in the diary (once a day). */
    @Volatile private var mcxRestNoted: java.time.LocalDate? = null

    private fun noteMcxRest() {
        mcxRestNoted = runCatching { Market.today() }.getOrNull()
        runCatching { Diag.record("battery", "Candle agent rests on MCX's evening: nothing held on MCX and no MCX arm in its entry minutes (battery audit, 27 Oct)") }
    }

    /** One batch (the worker's body): off the main thread; returns at once while memory is short. */
    suspend fun batch(context: Context) {
        lastBatchAt = System.currentTimeMillis()
        if (!enabled()) return
        runCatching { com.optionslab.app.work.BatteryMeter.meter.count(com.optionslab.ira.LoopMeter.CANDLE_BATCHES) }
        // Open positions first: the start-up restores and watches them before any agent's work.
        PositionsFirst.awaitPositions(); PositionsFirst.awaitStream()
        if (MemoryTrim.underPressure()) return
        val gen = wipeGen
        val a = withContext(Dispatchers.IO) { agentOrLoad(context) }
        val now = Market.now()
        val nowMs = System.currentTimeMillis()
        val nseOpen = runCatching { Market.isOpen() }.getOrDefault(false)
        val nseOver = runCatching { Market.isTradingDay() && Market.minuteNow() > Market.foClose() }.getOrDefault(false)
        val series = ArrayList<CandleAgent.Series>()
        for (m in INDICES) series += indexSeries(m, now.toLocalDateTime(), nseOver, nowMs, nseOpen)
        val mcxState = runCatching { McxMarket.openOrNull() }.getOrNull()
        val mcxOpen = mcxState == true
        // 20 Oct, review: after MCX's close, one batch reads the day's last minutes as a finished session (its last candles
        // settle with what they have, as NSE's do after its close); the kept minutes are then let go.
        val mcxSettle = mcxState == false && mcxBars.isNotEmpty()
        if (mcxOpen || mcxSettle) for (u in MCX) series += mcxSeries(u, now.toLocalDateTime(), nowMs, settle = mcxSettle)
        // 20 Oct, second review: the settle is tried ONCE a session (its minutes let go now, whatever the study does): a study
        // that kept failing re-fetched every MCX series every 5 minutes all night.
        if (mcxSettle) { mcxSettles++; mcxBars.clear() }
        val heard = SmartWorkers.bus.recent(nowMs, kinds = CandleAgent.HEARS)
        val step = withContext(Dispatchers.Default) {
            runCatching {
                if (failNextForTest) { failNextForTest = false; throw IllegalStateException("test") }
                a.study(series, heard, nowMs, now.toLocalDateTime(), nseOpen || mcxOpen)
            }.onFailure { a.failed(it) }.getOrNull()
        } ?: run { publish(a); return }                 // 20 Oct, review: a failed batch shows its error on the page too
        // Its reads on the bus as they are (no direction: never a vote in any strategy's consensus).
        for (p in step.posts) runCatching { SmartWorkers.bus.post(p) }
        if (step.changed) withContext(Dispatchers.IO) { save(context, a, gen) }
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
        // Never in NSE's or MCX's session (MCX positions may be open in its evening: the study's CPU waits for the close).
        val open = runCatching { Market.isOpen() || McxMarket.isOpen() }.getOrDefault(true)
        // 22 Oct (the overnight kills): the day of the last study is kept on disk too, so a restart (Android ended the app) does
        // not study the same day again ([com.optionslab.app.work.NightMarks]; read once a process, off the main thread).
        if (!deepRead) withContext(Dispatchers.IO) {
            com.optionslab.ira.NightStart.dayOf(com.optionslab.app.work.NightMarks.get(context, K_DEEP))?.let { kept ->
                val mine = deepDay
                if (mine == null || kept.isAfter(mine)) deepDay = kept
            }
            deepRead = true
        }
        if (open || deepDay == today || MemoryTrim.underPressure()) return
        val gen = wipeGen
        val a = withContext(Dispatchers.IO) { agentOrLoad(context) }
        val series = ArrayList<CandleAgent.Series>()
        for (m in INDICES) {
            // The market study's 5-minute history, if it holds one (never fetched here). Finished sessions only.
            val five = runCatching { com.optionslab.app.ira.IraHub.twoYearsKept(m, 5) }.getOrNull().orEmpty().filter { it.t.toLocalDate().isBefore(today) }
            if (five.isEmpty()) continue
            for (tf in listOf(5, 15)) {
                val bars = if (tf == 5) five else Candles.fold(five, 15, m)
                // Each night a step further back: the sessions before the first one counted (and any finished one after the last).
                val sk = "${m.name}|$tf"
                val first = a.counted(sk).first; val mark = a.counted(sk).second
                val days = bars.map { it.t.toLocalDate() }.distinct()
                val older = days.filter { first == null || it.isBefore(first.toLocalDate()) }.takeLast(DEEP_SESSIONS)
                val newer = days.filter { mark != null && it.isAfter(mark.toLocalDate()) }
                val pick = (older + newer).toSet()
                if (pick.isEmpty()) continue
                // A few sessions before the picked ones are kept for ATR, the trend and history (read, never counted twice).
                val lead = days.filter { it.isBefore(pick.min()) }.takeLast(3).toSet()
                series += CandleAgent.Series(m.name, tf, bars.filter { it.t.toLocalDate() in pick || it.t.toLocalDate() in lead }
                    .map { CandleRead.Bar(it.t, it.o, it.h, it.l, it.c) }, 9 * 60 + 15, sessionOver = true, deep = true)
            }
        }
        deepDay = today
        // 22 Oct: kept on disk once the budget let the study start - so a study Android ends midway is not begun again by the
        // next start the same day. Nothing to study (the history not in memory yet) or a "no" from the budget: the next start may try.
        if (series.isEmpty()) return
        val now = Market.now().toLocalDateTime()
        // The budget is asked and charged on the thread that does the work (it meters that thread's CPU).
        val step = withContext(Dispatchers.Default) {
            val budget = com.optionslab.ira.BudgetRule.Job.CANDLE_STUDY
            if (!runCatching { com.optionslab.app.work.BackgroundBudget.allow(budget) }.getOrDefault(false)) return@withContext null
            runCatching { com.optionslab.app.work.NightMarks.put(context, K_DEEP, today.toString()) }
            try { runCatching { a.study(series, emptyList(), System.currentTimeMillis(), now, false) }.onFailure { a.failed(it) }.getOrNull() }
            finally { runCatching { com.optionslab.app.work.BackgroundBudget.done(budget) } }
        } ?: return
        if (step.changed) withContext(Dispatchers.IO) { save(context, a, gen) }
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
        // 20 Oct, review: closed by where the data ends, not by the clock. This history is refreshed only while the Ira page is
        // open or Jarvis listens, so the clock can be well past a candle whose later minutes were never fetched (it was read
        // from part of its minutes, then never again); and its newest minute may have been read while still forming.
        // 20 Oct, second review: "over" only when today's minutes reach the index's 15:30 close; a history that stopped at
        // 11:01 is not a finished session (its 11:00 15-minute candle was read from 2 minutes and counted).
        val lastToday = bars.lastOrNull { it.t.toLocalDate() == now.toLocalDate() }?.t
        val done = over && Candles.reachesClose(lastToday, now.toLocalDate().atTime(INDEX_CLOSE_H, INDEX_CLOSE_M))
        val end = Candles.dataEnd(bars.map { it.t }, now, complete = done)
        return TFS.map { tf ->
            val folded = Candles.closedByData(Candles.fold(bars, tf, m), tf, end, sessionOver = done) { it.t }
            // Today's chain, gamma and VWAP are what is known NOW: only a candle that closed within the last batch interval gets
            // them (an older candle never gets a later chain). The expiry day is a calendar fact all day.
            val freshFrom = now.minusMinutes(tf + EVERY_MS / 60_000L)
            val around: (LocalDateTime) -> CandleRead.Around = { t ->
                when {
                    t.toLocalDate() != now.toLocalDate() -> CandleRead.Around()
                    !t.isBefore(freshFrom) -> todays
                    else -> CandleRead.Around(expiry = todays.expiry)
                }
            }
            CandleAgent.Series(name, tf, folded.map { CandleRead.Bar(it.t, it.o, it.h, it.l, it.c) }, 9 * 60 + 15, done, around, open = open)
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
    private suspend fun mcxSeries(u: String, now: LocalDateTime, nowMs: Long, settle: Boolean = false): List<CandleAgent.Series> {
        val kept = mcxBars[u]
        // [settle]: after the close, read once more (the cache can be 10 minutes short of the close); the kept minutes if that fails.
        val got = if (!settle && kept != null && nowMs - kept.ms < MCX_EVERY_MS) kept else withContext(Dispatchers.IO) {
            val key = runCatching { com.optionslab.engine.mcx.McxInstruments.futures(McxMarket.cached(), u, now.toLocalDate(), 1).firstOrNull()?.upstoxKey }.getOrNull()
                ?: return@withContext null
            val nowSec = now.atZone(com.optionslab.engine.IST).toEpochSecond()
            // A failed read is tried again next batch (never kept as "no candles"); the worker's time limit still cuts it.
            val raw = try { Net.intraday(key) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
            val bars = raw.filter { it.epochSecond + 60 <= nowSec }.sortedBy { it.epochSecond }
                .map { b -> CandleRead.Bar(LocalDateTime.ofEpochSecond(b.epochSecond, 0, java.time.ZoneOffset.ofHoursMinutes(5, 30)), b.open, b.high, b.low, b.close,
                    b.volume.takeIf { it > 0 }?.toDouble()) }
            // Settling: a read after midnight (the next day: nothing yet) or one with fewer minutes than kept keeps the kept ones.
            if (bars.isEmpty() || (settle && kept != null && bars.last().t.isBefore(kept.bars.last().t))) null else Fetched(nowMs, now, bars)
        }?.also { if (!settle) mcxBars[u] = it } ?: kept?.takeIf { settle } ?: return emptyList()
        // Closed by where the fetched minutes end (20 Oct, review): a 5-minute candle is never read from part of its minutes
        // because the clock moved on while the minutes came from a cache up to 10 minutes old.
        // 20 Oct, second review: settled as a finished session only when the minutes reach MCX's close (within a minute); kept
        // minutes that stop short of it are read by their own end, as in session.
        val last = got.bars.lastOrNull()?.t
        val done = settle && last != null && runCatching { McxMarket.calendar().window(last.toLocalDate()) }.getOrNull()
            ?.let { w -> Candles.reachesClose(last, last.toLocalDate().atTime(w.close)) } == true
        return TFS.map { tf -> CandleAgent.Series(u, tf, fold(got.bars, tf, got.at, sessionOver = done), 9 * 60, sessionOver = done) }
    }

    /**
     * MCX minutes folded to [tf] from 09:00 (volume summed), closed candles only: closed by the data's own end ([Candles.dataEnd],
     * the minutes as fetched at [asOf], each one already over then), never by the clock alone. [sessionOver]: after MCX's close,
     * its last candle is closed too.
     */
    internal fun fold(bars: List<CandleRead.Bar>, tf: Int, asOf: LocalDateTime, sessionOver: Boolean = false): List<CandleRead.Bar> {
        val end = Candles.dataEnd(bars.map { it.t }, asOf, complete = true)
        val folded = if (tf <= 1) bars else bars.groupBy { b ->
            val m = b.t.hour * 60 + b.t.minute - 9 * 60
            b.t.toLocalDate().atStartOfDay().plusMinutes((Math.floorDiv(m, tf) * tf + 9 * 60).toLong())
        }.toSortedMap().map { (t, g) ->
            CandleRead.Bar(t, g.first().o, g.maxOf { it.h }, g.minOf { it.l }, g.last().c, g.mapNotNull { it.v }.takeIf { it.isNotEmpty() }?.sum())
        }
        return Candles.closedByData(folded, maxOf(tf, 1), end, sessionOver) { it.t }
    }

    /**
     * The counts written (IO thread). 20 Oct, review: one writer at a time (the batch worker and the hourly study worker could
     * both write the same temporary file), and the file replaced in one step: a failed rename used to delete the counts first.
     */
    private fun save(context: Context, a: CandleAgent, gen: Int) {
        synchronized(saveLock) {
            // 26 Oct: an agent loaded before an erase never writes its counts after it (checked under the writers' lock [wipe] takes).
            if (gen != wipeGen) return
            runCatching {
                val f = file(context)
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, "stats.tmp")
                tmp.writeText(a.encodeStats())
                try {
                    java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } catch (e: Exception) {
                    java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun publish(a: CandleAgent) {
        // A batch that finished after the agent was let go never brings it back into view (or into memory).
        if (agent !== a) return
        val day = runCatching { Market.today() }.getOrElse { LocalDate.now() }
        // 20 Oct, enhancement round 2: the lessons and notes keep their instrument (the page's chips), and whether a market was open.
        val open = runCatching { Market.isOpen() }.getOrDefault(false) || runCatching { McxMarket.openOrNull() == true }.getOrDefault(false)
        _view.value = View(a.status(), a.latest(), a.table(), a.lessons(day).map { CandlePage.lessonLine(it) },
            a.noteRows().map { CandlePage.Line(it.inst, CandleBook.noteWords(it)) }, a.exchanges(), a.growthWords(), open = open)
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

    /**
     * 26 Oct (Erase everything): the agent, its kept minutes and OI, its page and its counts file forgotten. Under the load's and
     * the writers' locks (a load or a write is a small file), so a load already reading the old counts cannot keep them and a
     * batch in flight cannot write them back. Never the vault.
     */
    fun wipe() {
        synchronized(this) {
            synchronized(saveLock) {
                wipeGen++
                agent = null
                mcxBars.clear(); lastOi.clear()
                lastBatchAt = 0L; settledDay = null
                _view.value = View()
                filesForErase().forEach { runCatching { it.delete() } }
            }
        }
    }

    /** 26 Oct: the agent or its page in memory (memory only, for the erase's tests). */
    internal fun holdsForErase(): Boolean = agent != null || mcxBars.isNotEmpty() || lastOi.isNotEmpty() || _view.value.status != null ||
        _view.value.lessons.isNotEmpty() || _view.value.notes.isNotEmpty()

    /** 26 Oct: the counts file and its temporary file (none before [init] or the first load). */
    internal fun filesForErase(): List<File> = appContext?.let { c -> val f = file(c); listOf(f, File(f.parentFile, "stats.tmp")) }.orEmpty()

    /** TEST ONLY: [bars] kept for [u] as if fetched at their last minute's end. */
    internal fun keepMcxForTest(u: String, bars: List<CandleRead.Bar>) {
        mcxBars[u] = Fetched(System.currentTimeMillis(), bars.last().t.plusMinutes(1), bars)
    }

    /** TEST ONLY: the next batch's study fails (as a real error would). */
    @Volatile internal var failNextForTest = false

    /** TEST ONLY: as after Android ended the app - the study day in memory gone, the one kept on disk still there. */
    internal fun restartDayForTest() { deepDay = null; deepRead = false }

    /** TEST ONLY. */
    internal fun resetForTest(a: CandleAgent? = null) {
        failNextForTest = false
        agent = a; lastBatchAt = 0L; settledDay = null; deepDay = null; deepRead = false; mcxBars.clear(); lastOi.clear(); mcxSettles = 0
        _view.value = View()
        if (a != null) publish(a)
    }
}
