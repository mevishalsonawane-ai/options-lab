package com.optionslab.app.ira

import android.content.Context
import com.optionslab.app.data.ChartFeed
import com.optionslab.app.data.GoldChart
import com.optionslab.app.data.Net
import com.optionslab.app.data.Store
import com.optionslab.app.security.Vault
import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.ira.Brain
import com.optionslab.ira.Candle
import com.optionslab.ira.Candles
import com.optionslab.ira.Headline
import com.optionslab.ira.News
import com.optionslab.ira.History
import com.optionslab.ira.Ira
import com.optionslab.ira.OrderRequest
import com.optionslab.ira.PatternBook
import com.optionslab.ira.Snapshot
import com.optionslab.ira.StrategyLab
import com.optionslab.ira.Topic
import com.optionslab.ira.Ask
import com.optionslab.ira.PatternKind
import com.optionslab.ira.Wake
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import com.optionslab.ira.Market as IraMarket

/**
 * Ira inside IraAlgo: the index candles the app already keeps (the bundled record and every day harvested on this
 * phone) joined with today's live 1-minute candles from the same public feeds IraAlgo's chart uses (Upstox for the
 * indices and India VIX, Yahoo's COMEX feed for gold), and headlines from a fixed list of public news feeds. It teaches
 * the pattern book each new completed candle, builds the snapshots, and answers from them. Reads nothing outside the app
 * but those public feeds (Ira's rules 1 and 2); never places an order - an order request is shown for review. The
 * pattern book is kept encrypted in the app's no-backup folder.
 */
object IraHub {
    data class Msg(val fromIra: Boolean, val text: String, val facts: List<String> = emptyList(), val order: OrderRequest? = null,
                   /** A strategy proposal this message carries (its [Proposal.id]). */ val proposal: Long? = null,
                   /** Ira's own words when [text] was rewritten by the on-device model (numbers checked); [writing] while it works. */
                   val draft: String? = null, val writing: Boolean = false,
                   /** An action waiting for the owner's one tap (its id in [State.pending]). */ val action: Long? = null,
                   /**
                    * An action's, a confirm's or a command's result, or the guard's own report: shown and said whole, never
                    * as a short line ([com.optionslab.ira.ShortAnswer]) - a failure in it must never be cut (review, 5 Oct).
                    */
                   val whole: Boolean = false,
                   /** Stable for the message's life (kept through rewrites), so the screen keeps each message's own state. */
                   val id: Long = msgSeq.incrementAndGet(),
                   /** A request's whole words behind its chat line (a news trade's risk, cautions, confidence): under "Details". */
                   val details: String? = null)

    /** A strategy Jarvis wrote from a pattern and backtested: new until the owner approves (armed on paper) or dismisses it. */
    data class Proposal(val id: Long, val result: StrategyLab.Result, val status: String = NEW, val pineId: Long? = null) {
        companion object { const val NEW = "new"; const val APPROVED = "approved"; const val DISMISSED = "dismissed" }
    }

    data class State(
        val loading: Boolean = false,
        val snaps: Map<IraMarket, Snapshot> = emptyMap(),
        val lastDay: LocalDate? = null,
        val days: Int = 0,
        val learned: Int = 0,
        val messages: List<Msg> = emptyList(),
        val problem: String? = null,
        val liveAt: Instant? = null,                  // the last time live candles came in
        val liveMissing: List<IraMarket> = emptyList(), // markets whose live feed did not answer last time
        val news: List<Headline> = emptyList(),
        val newsAt: Instant? = null,
        val newsMissing: List<String> = emptyList(),  // feeds that did not answer last time
        val proposals: List<Proposal> = emptyList(),
        val busy: Boolean = false,                     // a backtest is running
        val journal: List<DayScore> = emptyList(),     // how Ira's patterns did, session by session (oldest first)
        val nightlyAt: Instant? = null,                // the last evening review
        val best: List<PatternBook.Entry> = emptyList(), // the patterns that went their way most often so far
        val pending: Set<Long> = emptySet(),            // actions waiting for Confirm
        val requestsRev: Long = 0,                      // bumped when a waiting request's view changes (its venue worked out)
    )

    /** One session's review: the patterns Ira saw on the index charts (15 and 60 minutes) and how many went their way. */
    data class DayScore(val day: LocalDate, val seen: Int, val worked: Int) {
        val rate: Double get() = if (seen == 0) 0.0 else worked.toDouble() / seen
    }

    /** The app's harvested underlyings Ira reads from storage. */
    val SOURCES = listOf(IraMarket.NIFTY to "NIFTY", IraMarket.BANKNIFTY to "BANKNIFTY", IraMarket.FINNIFTY to "FINNIFTY",
        IraMarket.SENSEX to "SENSEX", IraMarket.VIX to "INDIAVIX")

    /** Each market's live feed symbol (ChartFeed for the indices; gold comes from GoldChart). */
    val LIVE = linkedMapOf(IraMarket.NIFTY to "NIFTY", IraMarket.BANKNIFTY to "BANKNIFTY", IraMarket.FINNIFTY to "FINNIFTY",
        IraMarket.SENSEX to "SENSEX", IraMarket.VIX to "INDIAVIX", IraMarket.GOLD to "GOLD")

    /**
     * The news feeds Ira reads - the whole list (rule 2: no other host). Chosen 2026-10-02 from the ones that answered
     * a server probe (.github/workflows/news-probe.yml) with market headlines.
     */
    val NEWS_FEEDS = linkedMapOf(
        "Economic Times" to "https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms",
        "Livemint" to "https://www.livemint.com/rss/markets",
        "Business Standard" to "https://www.business-standard.com/rss/markets-106.rss",
        "BusinessLine" to "https://www.thehindubusinessline.com/markets/feeder/default.rss",
        "Moneycontrol" to "https://www.moneycontrol.com/rss/marketreports.xml",
        "FXStreet" to "https://www.fxstreet.com/rss/news",
        // Official sources (probed 2026-10-02, .github/workflows/official-probe.yml).
        "RBI" to "https://www.rbi.org.in/pressreleases_rss.xml",
        "SEBI" to "https://www.sebi.gov.in/sebirss.xml",
        "NSE" to "https://nsearchives.nseindia.com/content/RSS/Circulars.xml",
    )
    /** NSE's daily FII/DII cash-market figures (JSON). */
    const val FLOWS_URL = "https://www.nseindia.com/api/fiidiiTradeReact"
    @Volatile private var flowsCache: Pair<Instant, List<com.optionslab.ira.Flows.Flow>>? = null

    /** The latest FII/DII figures from NSE, read at most once an hour. */
    suspend fun flows(): List<com.optionslab.ira.Flows.Flow> {
        flowsCache?.takeIf { it.first.isAfter(Instant.now().minusSeconds(3600)) }?.let { return it.second }
        val f = runCatching { com.optionslab.ira.Flows.parse(testFeed?.invoke(FLOWS_URL) ?: Net.getText(FLOWS_URL)) }.getOrDefault(emptyList())
        if (f.isNotEmpty()) flowsCache = Instant.now() to f
        return f
    }
    /** News is fetched at most this often; headlines older than [NEWS_DAYS] days are dropped. */
    const val NEWS_EVERY_MINUTES = 5L
    const val NEWS_DAYS = 3L
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    /** Days of candles kept in memory for the snapshots (learning has already seen the older ones). */
    const val KEEP_DAYS = 60

    private val _state = MutableStateFlow(State())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var app: Context? = null
    /** The last candles read (stored + live), for backtests. */
    @Volatile private var histories: Map<IraMarket, History> = emptyMap()
    /** The days in [histories] each index's saved option candles show were its expiry days (market memory). */
    @Volatile private var expiryDays: Map<IraMarket, Set<LocalDate>> = emptyMap()
    /** Pattern, market, chart and day already backtested automatically (each is tried once a day). */
    private val tested = HashSet<String>()
    @Volatile private var lastBackground: Instant? = null
    val state: StateFlow<State> = _state
    private val lock = Mutex()
    @Volatile private var book = PatternBook()
    private var bookFile: File? = null
    /** Proposals, the review journal and the patterns already tried: kept encrypted beside the book. */
    private var stateFile: File? = null

    /** TEST ONLY: histories instead of the app's store. */
    @Volatile internal var testHistories: (() -> Map<IraMarket, History>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }; field = v }
    /** TEST ONLY: a market's live candles instead of the network (the test app sets one that returns none). */
    @Volatile internal var testLive: (suspend (IraMarket) -> List<Candle>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }; field = v }
    /** TEST ONLY: run the automatic strategy hunt outside Jarvis. */
    @Volatile internal var testAutoLab = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }; field = v }
    /** TEST ONLY: the start's read of the saved memory waits for this (to see what happens while it is read). */
    @Volatile internal var testLoadHold: CompletableDeferred<Unit>? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }; field = v }
    /** TEST ONLY: a feed's text instead of the network. */
    @Volatile internal var testFeed: (suspend (String) -> String)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }; field = v }

    fun appContext(): Context? = app

    fun init(context: Context) {
        app = context.applicationContext
        // Jarvis: the home-screen widget follows what Jarvis says and what waits for an answer.
        if (com.optionslab.app.BuildConfig.JARVIS) scope.launch {
            _state.collect { st ->
                val key = (st.messages.lastOrNull { it.fromIra }?.text ?: "") + "|" + st.pending.joinToString()
                if (key != widgetKey) { widgetKey = key; runCatching { com.optionslab.app.widget.JarvisWidget.refresh(context.applicationContext) } }
            }
        }
        IraModel.init(context)
        // The question readers' patterns made ready off the main thread, so Boss's first question does not wait for them.
        scope.launch { runCatching { com.optionslab.ira.Warm.up() } }
        val f = File(context.applicationContext.noBackupFilesDir, "ira-book.vault")
        val sf = File(context.applicationContext.noBackupFilesDir, "ira-state.vault")
        // Started again in the same process: what is in memory is written first (the keeper waits 300 ms before it
        // saves), so the newest message or proposal is never lost to the reload below.
        if (keeper != null && stateFile == sf && loaded.isCompleted) saveState()
        // Speed, round 2: the pattern book and the saved conversation are decrypted off the screen's thread. Until they
        // are in, nothing is saved (an empty conversation is never written over the saved one) and a refresh, a typed
        // question's lane or "forget everything" waits; the screen shows what there is (an empty page) meanwhile.
        val gate = CompletableDeferred<Unit>()
        val before = _state.value.let { st -> st.messages.mapTo(HashSet()) { it.id } to st.proposals.mapTo(HashSet()) { it.id } }
        synchronized(loadLock) { loaded = gate; _ready.value = false; wipedWhileLoading = false; stateReadFailed = false; bookReadFailed = false }
        bookFile = f
        stateFile = sf
        scope.launch(Dispatchers.IO) {
            try {
                testLoadHold?.await()
                val bookRead = runCatching { Vault.readFileSteady(f)?.let { PatternBook.load(String(it, Charsets.UTF_8)) } }
                val savedRead = runCatching { Vault.readFileSteady(sf)?.let { IraSaved.read(String(it, Charsets.UTF_8)) } }
                val readBook = bookRead.getOrNull() ?: PatternBook()
                val saved = savedRead.getOrNull()
                synchronized(loadLock) {
                    // A later start in the same process (tests) reads again: this older read is dropped.
                    if (loaded === gate) {
                        // A file that is there but could not be read is never written over (it may come back readable).
                        if (bookRead.isFailure) { bookReadFailed = true; runCatching { com.optionslab.app.data.Diag.record("jarvis", "the pattern book could not be read; it is not saved over") } }
                        if (savedRead.isFailure) { stateReadFailed = true; runCatching { com.optionslab.app.data.Diag.record("jarvis", "the saved conversation could not be read; it is not saved over") } }
                        try { restore(readBook, saved, before.first, before.second) }
                        catch (_: Exception) {
                            stateReadFailed = true; bookReadFailed = true
                            runCatching { com.optionslab.app.data.Diag.record("jarvis", "the saved conversation could not be put back; it is not saved over") }
                        }
                    }
                }
            } finally {
                synchronized(loadLock) { if (loaded === gate) _ready.value = true }
                gate.complete(Unit)
            }
        }
        if (keeper == null) keeper = scope.launch {
            var last: List<Msg>? = null
            _state.collect { st -> if (st.messages !== last) { last = st.messages; kotlinx.coroutines.delay(300); saveState() } }
        }
    }

    /** What was read at the start put in place (under [loadLock]); messages and proposals added meanwhile are kept after it. */
    private fun restore(readBook: PatternBook, saved: IraSaved.Read?, oldMsgs: Set<Long>, oldProps: Set<Long>) {
        book = readBook
        synchronized(tested) { tested.clear(); saved?.tested?.let { tested += it } }
        // The process started again: what was kept comes back; a strategy still waiting is offered again in the conversation.
        // The conversation comes back as it was (the owner's wish, 2026-10-02); a strategy still waiting is offered again.
        // An option-chain read cut short by the process ending never finishes: its placeholder is told as not read.
        val talk = if (wipedWhileLoading) emptyList<Msg>() else saved?.messages.orEmpty()
            .map { if (it.fromIra && it.text.startsWith(CHAIN_NOTE)) it.copy(text = "I couldn't read the option chain just now, Boss.") else it }
        val waiting = saved?.proposals.orEmpty().filter { it.status == Proposal.NEW && talk.none { m -> m.proposal == it.id } }
            .map { Msg(true, "Still waiting for your decision. " + it.result.summary(), proposal = it.id) }
        _state.update { cur ->
            // Whatever was said while the memory was read (a reply, an alert) stays, after what was kept.
            val newMsgs = cur.messages.filter { it.id !in oldMsgs }
            val newProps = cur.proposals.filter { it.id !in oldProps }
            val actions = newMsgs.mapNotNullTo(HashSet()) { it.action }
            State(learned = book.size, best = book.best(), proposals = saved?.proposals.orEmpty() + newProps,
                journal = saved?.journal.orEmpty(), nightlyAt = saved?.nightlyAt,
                messages = (talk + waiting + newMsgs).takeLast(MAX_MESSAGES), pending = cur.pending.filterTo(HashSet()) { it in actions })
        }
    }

    /** Completed once the latest [init]'s pattern book and saved conversation are read (completed before any init). */
    @Volatile private var loaded: CompletableDeferred<Unit> = CompletableDeferred<Unit>().apply { complete(Unit) }
    private val loadLock = Any()
    /**
     * The saved conversation/proposals/journal file (or the pattern book) is there but could not be read or put back:
     * nothing is written over it until "forget everything" or the next start reads it (an empty memory never replaces it).
     */
    @Volatile private var stateReadFailed = false
    @Volatile private var bookReadFailed = false
    /** The conversation was wiped while the saved one was being read: it does not come back. */
    @Volatile private var wipedWhileLoading = false
    private val _ready = MutableStateFlow(true)
    /** True once Jarvis's memory (pattern book, conversation, proposals) is read at the start. */
    val ready: StateFlow<Boolean> = _ready

    /** Waits until the memory of the latest [init] is read. */
    suspend fun awaitLoaded() {
        while (true) {
            val g = loaded
            g.await()
            if (g === loaded) return
        }
    }

    /** [awaitLoaded] for tests and code that cannot suspend; returns at once when already read. */
    fun awaitLoadedBlocking() {
        if (loaded.isCompleted && _ready.value) return
        kotlinx.coroutines.runBlocking { awaitLoaded() }
    }

    /** Saves the conversation as it changes. */
    private var keeper: kotlinx.coroutines.Job? = null

    /** Writes the proposals, the journal, the tried patterns and the conversation (one writer at a time). */
    @Synchronized private fun saveState() {
        val f = stateFile ?: return
        // Never before the saved conversation is read: an empty one would be written over it (the keeper saves after).
        if (!loaded.isCompleted) return
        if (stateReadFailed) return
        val st = _state.value
        val tried = synchronized(tested) { tested.toList() }
        if (st.proposals.isEmpty() && st.journal.isEmpty() && st.messages.isEmpty() && tried.isEmpty()) { f.delete(); return }
        runCatching { Vault.writeFile(f, IraSaved.write(st.proposals.takeLast(KEEP_PROPOSALS), st.journal, st.nightlyAt, tried,
            LocalDate.now(IST), st.messages.takeLast(MAX_MESSAGES)).toByteArray(Charsets.UTF_8)) }
    }

    /**
     * The evening review: each completed session not reviewed yet (the latest [BACKFILL] at most) is scored - every
     * pattern on the index charts whose outcome is now known, and whether it went its way. A session counts as
     * completed after 15:35 IST, or on any later day. Learning itself happens on every refresh.
     */
    private fun nightlyIfDue(now: Instant = Instant.now()) {
        val idx = histories.filterKeys { it in StrategyLab.MARKETS }
        if (idx.isEmpty()) return
        val t = now.atZone(IST).toLocalDateTime()
        val have = _state.value.journal.map { it.day }
        val newest = have.maxOrNull()
        val days = idx.values.flatMap { it.days }.distinct()
            .filter { d -> (d.isBefore(t.toLocalDate()) || !t.toLocalTime().isBefore(EVENING)) && (newest == null || d.isAfter(newest)) }
            .sorted().takeLast(BACKFILL)
        if (days.isEmpty()) return
        val charts = idx.flatMap { (m, h) -> listOf(15, 60).map { minutes ->
            Candles.closed(Candles.fold(h.bars, minutes, m), minutes, h.bars.last().t.plusMinutes(1)) } }
        val add = days.map { d -> charts.map { book.score(it, d) }.reduce { a, b -> a + b }.let { DayScore(d, it.seen, it.worked) } }
        _state.update { it.copy(journal = (it.journal + add).takeLast(KEEP_JOURNAL), nightlyAt = now) }
        saveState()
    }

    /** After the close (the evening harvest, Jarvis): read the day's candles, learn them and review the session. */
    suspend fun evening() {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        refresh()
        // The scorecard of today's suggestions, on the day's real option prices (kept by the harvest just before).
        val lines = runCatching { IraNewsTrades.scorecard() }.getOrDefault(emptyList())
        if (lines.isNotEmpty() && lines.first() != "No trades suggested today.") {
            app?.let { JarvisPopup.show(it, "Boss, my scorecard", lines.first()) }
            reply(com.optionslab.ira.Address.boss("My scorecard today. " + lines.joinToString(" ")))
            // A record, not a warning: aloud only when Boss chose "Jarvis speaks: everything".
            runCatching { JarvisVoice.offerNote(com.optionslab.ira.Address.boss("My scorecard today. " + lines.first()), com.optionslab.ira.SpeakChoice.Weight.MINOR) }
        }
        // Losses outgrowing wins this week: said with the numbers.
        IraCoach.lossSizeLine()?.let { w -> app?.let { JarvisPopup.show(it, "Boss, a pattern in your trades", w) }; reply(com.optionslab.ira.Address.boss(w)) }
        // The spoken wrap-up of the day.
        runCatching { IraCoach.daySummary(lines.firstOrNull()) }
        // Then Boss's journal for the day, drafted from the facts, with a few questions (unlocked phone only; words only).
        runCatching { IraDayJournal.evening() }
        // How Jarvis did today (questions heard, misunderstood, failed): in the conversation, for fixing what annoys.
        com.optionslab.ira.Usage.line(IraTools.usageToday())?.let { reply("How I did today: $it") }
    }

    private val EVENING: java.time.LocalTime = java.time.LocalTime.of(15, 35)
    /** Sessions reviewed at most on the first run; the journal keeps this many; proposals kept. */
    const val BACKFILL = 20
    const val KEEP_JOURNAL = 120
    const val KEEP_PROPOSALS = 40

    /**
     * Battery (round 10): the keepers' refresh (the listening loop, the Ira page) - only when the last full read is old
     * enough ([com.optionslab.ira.LiveReadPace]); [quiet]: the screen off and nothing held or armed. Timed from when the
     * last full read began (any caller's, a failed one too), so a slow download does not push the next one a turn later
     * and a failing feed is not retried more often than before.
     */
    suspend fun refreshIfDue(quiet: Boolean) {
        val began = refreshBeganAt
        val sinceMs = if (began == 0L) null else System.nanoTime() / 1_000_000L - began
        // Nothing read yet (a first start, or after forgetAll): read now, whoever read a moment ago.
        if (_state.value.snaps.isEmpty() || com.optionslab.ira.LiveReadPace.due(sinceMs, quiet)) refresh()
    }

    /** When the last [refresh] began its reads (monotonic ms, [System.nanoTime]; 0: none yet). Battery, round 10. */
    @Volatile private var refreshBeganAt = 0L

    /** Re-reads the candles, learns from the new ones and rebuilds the snapshots. Never throws. */
    suspend fun refresh() = withContext(Dispatchers.Default) {
        awaitLoaded()           // the pattern book read at the start is the one taught (and saved)
        lock.withLock {
            refreshBeganAt = (System.nanoTime() / 1_000_000L).coerceAtLeast(1L)
            _state.update { it.copy(loading = true) }
            runCatching {
                val stored = testHistories?.invoke() ?: load()
                // No internet: the prices saved on the phone at once (no feed waited on until it times out).
                val net = online()
                // IraGoldAlgo reads no NSE feeds (it had no NSE network before Jarvis; it only talks there).
                val (live, missing) = if (net && !GOLD_ONLY_TALK) live() else emptyMap<IraMarket, List<Candle>>() to LIVE.keys.toList()
                val hs = merge(stored, live)
                val news = if (net && !GOLD_ONLY_TALK) newsIfDue() else null
                var added = 0
                for ((m, h) in hs) for (minutes in listOf(15, 60)) {
                    if (h.bars.isEmpty()) continue
                    val c = Candles.closed(Candles.fold(h.bars, minutes, m), minutes, h.bars.last().t.plusMinutes(1))
                    added += book.learn(m, minutes, c)
                }
                if (added > 0) save()
                val snaps = hs.mapNotNull { (m, h) -> Brain.read(h)?.let { m to it } }.toMap()
                histories = hs
                // The patterns Jarvis told of: how each went, from the phone's own candles ([com.optionslab.ira.PatternCalls]).
                runCatching { IraTools.patternsSettle(hs.mapValues { it.value.bars }) }
                // His trend and range reads: scored after each close by the same measure ([com.optionslab.ira.TrendReads]).
                runCatching { IraTools.trendReadsSettle(hs.mapValues { it.value.bars }) }
                val days = hs.values.maxOfOrNull { it.days.size } ?: 0
                _state.update { it.copy(loading = false, snaps = snaps, lastDay = snaps.values.maxOfOrNull { s -> s.at.toLocalDate() },
                    days = days, learned = book.size, problem = if (snaps.isEmpty()) "No market data on this phone yet" else null,
                    liveAt = if (live.isNotEmpty()) Instant.now() else it.liveAt, liveMissing = missing,
                    news = news?.first ?: it.news, newsAt = if (news != null) Instant.now() else it.newsAt,
                    newsMissing = news?.second ?: it.newsMissing, best = book.best()) }
                // How the index moved after each news theme's headlines, timed on the phone's own candles - timing facts
                // only, never a cause; it only ever adds words ([com.optionslab.ira.NewsMoves]). No news read in IraGoldAlgo.
                if (!GOLD_ONLY_TALK) runCatching { IraTools.newsMovesUpdate(_state.value.news, hs.mapValues { it.value.bars }) }
                // Boss's open legs noted once near the morning mark, for "what changed since this morning?" ([com.optionslab.ira.SinceMorning];
                // where, symbol and quantity only, kept on the phone and never said on a locked phone). Nothing acts.
                if (!GOLD_ONLY_TALK && com.optionslab.app.BuildConfig.JARVIS) runCatching { morningHeldIfDue() }
                if (news != null && com.optionslab.app.BuildConfig.JARVIS) runCatching { judgeNews(news.first) }
                nightlyIfDue()
            }.onFailure {
                _state.update { s -> s.copy(loading = false, problem = "Could not read the market data") }
            }
        }
        if (!GOLD_ONLY_TALK) autoLab()
    }

    /**
     * Jarvis's own look for strategies: today's newest pattern on each index chart, backtested once a day; a result worth
     * a paper trial is offered in the conversation and notified. (Results not worth one are not offered unasked.)
     */
    private suspend fun autoLab() {
        if (!com.optionslab.app.BuildConfig.JARVIS && !testAutoLab) return
        val st = _state.value
        var tried = false
        for ((m, snap) in st.snaps) {
            if (m !in StrategyLab.MARKETS) continue
            val p = snap.patterns.firstOrNull { StrategyLab.supported(it.kind, m) } ?: continue
            val key = "${p.kind}|$m|${p.minutes}|${p.at.toLocalDate()}"
            val fresh = synchronized(tested) { tested.add(key) }
            if (!fresh) continue
            tried = true
            val r = lab(p.kind, m, p.minutes) ?: continue
            if (!r.recommended) continue
            val prop = propose(r, "I found a ${p.kind.label} on ${m.label} ${chartWord(p.minutes)} at ${Brain.when_(p.at, m, p.at.toLocalDate())} and backtested it as a strategy. ")
            // Autopilot: a strategy that passed the two-year test is added on paper without asking.
            if (autopilot) { val said = approve(prop.id); app?.let { JarvisPopup.show(it, "Autopilot added ${r.name}", said) } }
            else notifyProposal(prop)
        }
        if (tried) saveState()
    }

    private fun chartWord(minutes: Int) = if (minutes == 60) "1-hour" else "$minutes-minute"

    /**
     * Backtests a pattern on at least two years of [m]'s candles at the chart's own interval (Upstox's public history,
     * kept from January 2022; the owner's rule: never less than two years); rupees from real option prices where the
     * phone has them. Less than two years is refused by [StrategyLab], never judged.
     */
    private suspend fun lab(kind: PatternKind, m: IraMarket, minutes: Int): StrategyLab.Result? {
        val u = LIVE[m] ?: return null
        val bars = twoYears(m, minutes) ?: histories[m]?.bars ?: return null
        return runCatching {
            StrategyLab.backtest(kind, m, minutes, bars, premium = { trades, bars ->
                if (testHistories != null) null else {
                    val r = com.optionslab.engine.pine.PinePremium.run(trades, bars, { d -> Store.barSession(u, d) },
                        com.optionslab.app.data.PineAuto.strikeStep(u), 1, 100_000.0)
                    if (r.priced == 0) null else (r.report?.netProfit ?: 0.0) to r.priced
                }
            })
        }.getOrNull()
    }

    /**
     * The owner's autopilot (off until switched on): strategies that pass the two-year test are added on paper without
     * asking, and the ones Jarvis added that stop working are retired at the weekly review.
     */
    var autopilot: Boolean
        get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.autopilot", false) }.getOrDefault(false)
        set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.autopilot", v) } }

    /**
     * The weekly review (Jarvis, after the last session of the week): the week and its patterns, then each strategy
     * Jarvis added checked on its last four weeks of trades - one that is losing is retired (autopilot) or offered to stop.
     */
    suspend fun weeklyReview() {
        val c = app ?: return
        val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
        val today = LocalDate.now(IST)
        val paper = IraAccount.trips(false, owners)
        val lines = com.optionslab.ira.Insights.week("Paper", paper, today) + com.optionslab.ira.Insights.patterns("Paper", paper).drop(1).take(3)
        JarvisPopup.show(c, "Boss, your week", lines.first())
        reply(com.optionslab.ira.Address.boss("Your weekly review. " + lines.joinToString(" ")))
        // Boss's own trades (not the bots') this week against last, and the habits that cost money: the numbers in the
        // chat; aloud only plain words, no amount and no symbol (a locked phone may be heard). Words only.
        if (!com.optionslab.app.BuildConfig.GOLD && Automations.on(Automations.Auto.WEEK)) runCatching {
            val live = runCatching { com.optionslab.app.data.AppSettings.load().live }.getOrDefault(false)
            val own = IraAccount.trips(live, owners).filter { it.owner.startsWith("Manual") }
            val mine = com.optionslab.ira.WeekReview.lines(if (live) "Zerodha" else "Paper", own, today)
            reply(com.optionslab.ira.Address.boss("How your own trading went this week. " + mine.joinToString(" ")))
            com.optionslab.ira.WeekReview.spoken(own, today)?.let { s ->
                JarvisVoice.announce(s); IraActivity.add("Gave the week's review of your own trades."); Automations.acted(Automations.Auto.WEEK, s)
            }
        }
        // Jarvis's own week: his goals graded and next week's set (about him only; words, study, questions and paper).
        runCatching { IraImprove.weekend() }
        for (p in _state.value.proposals.filter { it.status == Proposal.APPROVED && it.pineId != null }) {
            val item = com.optionslab.app.data.PineScripts.get(p.pineId!!) ?: continue
            if (!item.auto.on) continue
            val recent = paper.filter { it.owner.contains(item.name) && it.closedAt.toLocalDate().isAfter(today.minusDays(28)) }
            val sick = com.optionslab.ira.Insights.health(item.name, recent) ?: continue
            if (autopilot) {
                val r = com.optionslab.app.data.PineAuto.arm(item.id, false)
                if (r == "ok") { reply("$sick Autopilot retired it."); JarvisPopup.show(c, "Retired: ${item.name}", sick) }
                else reply("$sick Autopilot could not retire it ($r): switch it off in Research → Pine.")
            } else {
                pend("stop ${item.name}", suspend { com.optionslab.app.data.PineAuto.arm(item.id, false).let { r -> if (r == "ok") "Stopped ${item.name}." else r } },
                    "$sick Tap Confirm to stop it.")
            }
        }
    }

    /**
     * The monthly review (Jarvis, after the month's last session): Boss's own trades (not the bots') this month against
     * last month, where they made and lost money, how steady the month was and the one habit that cost most. The
     * numbers in the chat; aloud only plain words, no amount and no symbol (a locked phone may be heard). Words only.
     */
    suspend fun monthlyReview() {
        if (com.optionslab.app.BuildConfig.GOLD || !Automations.on(Automations.Auto.MONTH)) return
        runCatching {
            val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
            val today = LocalDate.now(IST)
            val month = java.time.YearMonth.from(today)
            val live = runCatching { com.optionslab.app.data.AppSettings.load().live }.getOrDefault(false)
            val own = IraAccount.trips(live, owners).filter { it.owner.startsWith("Manual") }
            if (own.isEmpty()) return@runCatching
            val mine = com.optionslab.ira.MonthReview.lines(if (live) "Zerodha" else "Paper", own, month, today, IraJournal.notes())
            reply(com.optionslab.ira.Address.boss("How your own trading went this month. " + mine.joinToString(" ")))
            com.optionslab.ira.MonthReview.spoken(own, month, today)?.let { s ->
                JarvisVoice.announce(s); IraActivity.add("Gave the month's review of your own trades."); Automations.acted(Automations.Auto.MONTH, s)
            }
        }
    }

    /** TEST ONLY: the long history for a backtest instead of Upstox's. */
    @Volatile internal var testLabBars: ((IraMarket, Int) -> List<Candle>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }; field = v }
    /** Two years and a month of [minutes]-minute candles, fetched once a day per market and chart. */
    private val longCache = HashMap<String, Pair<LocalDate, List<Candle>>>()

    internal suspend fun twoYears(m: IraMarket, minutes: Int): List<Candle>? {
        testLabBars?.let { return it(m, minutes) }
        if (m == IraMarket.GOLD || m == IraMarket.VIX) return null
        val key = "$m|$minutes"
        val today = LocalDate.now(IST)
        synchronized(longCache) { longCache[key]?.takeIf { it.first == today }?.let { return it.second } }
        val from = today.minusYears(StrategyLab.MIN_YEARS).minusDays(45).atStartOfDay(IST).toEpochSecond()
        val bars = runCatching { ChartFeed.bars(LIVE.getValue(m), "${minutes}m", from, null) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val out = bars.map { b -> candle(Instant.ofEpochSecond(b.epochSecond).atZone(IST).toLocalDateTime(), b) }
        synchronized(longCache) { longCache[key] = today to out }
        return out
    }

    private fun propose(r: StrategyLab.Result, lead: String): Proposal {
        val tail = if (r.recommended) " Approve it to add it as an arm on paper." else " You can still add it as a paper arm, but I don't recommend it."
        // The id is taken inside the update, so two proposals made at once never share one.
        var prop: Proposal? = null
        _state.update { s ->
            val p = Proposal((s.proposals.maxOfOrNull { it.id } ?: 0L) + 1, r).also { prop = it }
            s.copy(proposals = s.proposals + p, messages = (s.messages + Msg(true, lead + r.summary() + tail, proposal = p.id)).takeLast(MAX_MESSAGES))
        }
        saveState()
        return prop!!
    }

    private fun notifyProposal(p: Proposal) {
        val c = app ?: return
        val r = p.result
        runCatching { JarvisPopup.show(c, "Boss, a new strategy: ${r.kind.label} on ${r.market.label}", "${r.trades} trades, ${Math.round(r.winRate)}% won over ${r.days} days. Open Jarvis to approve or dismiss.", proposal = p.id) }
        val money = r.rupees?.let { " Options: ${"%+,.0f".format(java.util.Locale.ENGLISH, it).replace("+", "+Rs ").replace("-", "-Rs ")} a lot." } ?: ""
        runCatching {
            com.optionslab.app.work.Notifier.post(c, NOTIFY_BASE + p.id.toInt(), com.optionslab.app.work.Notifier.IRA,
                "Jarvis found a strategy: ${r.kind.label} on ${r.market.label} ${chartWord(r.minutes)}",
                "${r.trades} trades, ${Math.round(r.winRate)}% won, ${"%+,.1f".format(java.util.Locale.ENGLISH, r.netPoints)} points over ${r.days} days; " +
                    "both halves positive.$money Open Jarvis to approve or dismiss.", tab = "almanac", proposal = p.id)
        }
    }

    /**
     * The owner approved: the strategy is saved as a Pine script and switched on for auto-trading on paper (a live
     * account still needs the owner's PIN in the Pine screen before it sends anything). Returns what was done.
     */
    private val approving = HashSet<Long>()

    suspend fun approve(id: Long): String {
        if (GOLD_ONLY_TALK) return GOLD_TALK_ONLY
        // One approval at a time per proposal (a double tap, or the autopilot at the same moment, adds it once).
        if (!synchronized(approving) { approving.add(id) }) return "Already being added."
        try { return approveOnce(id) } finally { synchronized(approving) { approving.remove(id) } }
    }

    private suspend fun approveOnce(id: Long): String {
        val p = _state.value.proposals.firstOrNull { it.id == id } ?: return "That strategy is gone."
        if (p.status != Proposal.NEW) return "Already ${p.status}."
        val r = p.result
        val interval = if (r.minutes == 60) "1h" else "${r.minutes}m"
        // The same strategy already saved (Boss's 06 Oct diagnostics: #3 and #4 were one script added twice, so every
        // trade was doubled): the same code - comments and whitespace aside - on the same symbol and chart is refused.
        val saved = com.optionslab.app.data.PineScripts.loadNow().orEmpty().map {
            com.optionslab.ira.PineDupes.Script(it.id, it.code, it.auto.symbol, it.auto.interval, it.auto.on)
        }
        com.optionslab.ira.PineDupes.duplicateOf(r.script, r.market.name, interval, saved)?.let { dup ->
            val text = "Not added: ${r.name} is ${com.optionslab.ira.PineDupes.refusal(dup)} (the same code, symbol and chart) - " +
                "a second copy would double every trade."
            _state.update { s -> s.copy(proposals = s.proposals.map { if (it.id == id) it.copy(status = Proposal.DISMISSED) else it },
                messages = (s.messages + Msg(true, text)).takeLast(MAX_MESSAGES)) }
            saveState()
            IraActivity.add(text)
            strategyDone(p, com.optionslab.ira.Requests.Outcome.FAILED, text)
            return text
        }
        val item = com.optionslab.app.data.PineScripts.put(com.optionslab.app.data.PineScripts.Item(0, r.name, r.script))
        com.optionslab.app.data.PineScripts.setAuto(item.id, com.optionslab.app.data.PineScripts.Auto(
            on = false, symbol = r.market.name, interval = interval, lots = 1, shortWith = "put",
            // Boss's 06 Oct rule: every Pine strategy, Jarvis's included, has a stop-loss, a target and the profit lock
            // (30 / 60 points to start; PineScripts.protect keeps them on whatever is written).
            stopPts = com.optionslab.ira.PineProtection.STOP, targetPts = com.optionslab.ira.PineProtection.TARGET,
            profitLock = true, byJarvis = true))
        val armed = com.optionslab.app.data.PineAuto.arm(item.id, on = true, pinConfirmed = false)
        val text = if (armed == "ok") "Added ${r.name} as an arm and switched it on: on paper it trades 1 lot; in Live it waits for your PIN in Research → Pine. " +
            "You can switch it off there any time." else "Saved ${r.name} in Research → Pine, but could not switch it on ($armed)."
        _state.update { s -> s.copy(proposals = s.proposals.map { if (it.id == id) it.copy(status = Proposal.APPROVED, pineId = item.id) else it },
            messages = (s.messages + Msg(true, text)).takeLast(MAX_MESSAGES)) }
        saveState()
        IraActivity.add(text)
        strategyDone(p, if (armed == "ok") com.optionslab.ira.Requests.Outcome.APPROVED else com.optionslab.ira.Requests.Outcome.FAILED, text)
        return text
    }

    fun dismiss(id: Long) {
        _state.value.proposals.firstOrNull { it.id == id && it.status == Proposal.NEW }?.let { strategyDone(it, com.optionslab.ira.Requests.Outcome.DECLINED, "Dismissed; not offered again today.") }
        _state.update { s -> s.copy(proposals = s.proposals.map { if (it.id == id && it.status == Proposal.NEW) it.copy(status = Proposal.DISMISSED) else it },
            messages = (s.messages + Msg(true, "Dismissed. I won't offer that one again today.")).takeLast(MAX_MESSAGES)) }
        saveState()
    }

    /**
     * From the market watch (Jarvis only): refresh and look for strategies at most every [BACKGROUND_MINUTES] while
     * an index market is open, so a new strategy is notified even when the app is closed.
     */
    suspend fun backgroundCheck(now: Instant = Instant.now()) {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        if (!IraMarket.NIFTY.trading(now.atZone(IST).toLocalDateTime())) return
        if (lastBackground?.let { now.isBefore(it.plusSeconds(BACKGROUND_MINUTES * 60)) } == true) return
        lastBackground = now
        refresh()
        runCatching { watchAlerts() }
        // Battery (round 6): one read of each chain for both (the OI watch first, its reads then kept for the record).
        val chains = IraAccount.ChainPass()
        runCatching { IraCoach.oiWatch(chains) }
        runCatching { keepChains(chains) }
    }

    /**
     * Every 5 minutes (Jarvis, market hours): the news, read and judged; each new headline that matters is told once,
     * with what it means for your arms and positions. Called often by the market watch; it gates itself.
     */
    suspend fun newsWatch(now: Instant = Instant.now()) {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        if (!IraMarket.NIFTY.trading(now.atZone(IST).toLocalDateTime())) return
        val got = newsIfDue() ?: return
        _state.update { it.copy(news = got.first, newsAt = Instant.now(), newsMissing = got.second) }
        judgeNews(got.first)
    }

    /** The index's recent candles as Jarvis last read them (for the study's "today"). */
    internal fun recentBars(m: IraMarket): List<Candle> = histories[m]?.bars.orEmpty()

    /** [m]'s 1-minute candles read now from the feed (Solo needs every minute, not the 15-minute refresh); empty on failure. */
    internal suspend fun freshBars(m: IraMarket): List<Candle> =
        withTimeoutOrNull(20_000) { runCatching { liveOf(m) }.getOrNull() }.orEmpty()

    /**
     * Through the night (Jarvis, outside market hours, called hourly): the trusted feeds read, and each new headline
     * that matters kept quietly with what it means, for the 9 AM brief - no pop-up, no voice at night.
     */
    suspend fun nightNews(now: Instant = Instant.now()) {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        if (IraMarket.NIFTY.trading(now.atZone(IST).toLocalDateTime())) return
        // Battery (round 3): only within the 18 hours before the next open - the brief keeps no older headline - so not
        // hourly through a weekend or a holiday. A calendar that cannot be read reads as before.
        val local = now.atZone(IST).toLocalDateTime()
        val nextOpen = runCatching { com.optionslab.ira.NightNewsPace.nextOpen(local) { com.optionslab.app.data.Market.isTradingDay(it) } }.getOrNull()
        if (!com.optionslab.ira.NightNewsPace.due(local, nextOpen)) return
        val got = newsIfDue() ?: return
        _state.update { it.copy(news = got.first, newsAt = Instant.now(), newsMissing = got.second) }
        val fresh = synchronized(newsSeen) { got.first.filter { newsSeen.firstTime(it) } }
        newsPrimed = true
        val cut = now.minusSeconds(18 * 3600)
        fresh.filter { h -> h.at?.isAfter(cut) == true && com.optionslab.ira.NewsAnalyst.matters(h) }.take(6).forEach { h ->
            IraStudy.overnight(h.at!!, com.optionslab.ira.NewsAnalyst.take(h, emptyMap(), emptySet()).text)
        }
    }

    @Volatile private var widgetKey: String? = null

    /**
     * Stories already judged, the newest [NEWS_SEEN_MAX] only (the app can run for days): the same link (tracking parts
     * removed), or the same or nearly the same title from another site or a reworded update, is told once.
     */
    private const val NEWS_SEEN_MAX = 3_000
    private val newsSeen = com.optionslab.ira.NewsDedup(NEWS_SEEN_MAX)
    @Volatile private var newsPrimed = false

    /** New headlines that matter, told once (a pop-up and a line in the conversation); the first read only primes. */
    private suspend fun judgeNews(heads: List<Headline>) {
        val c = app ?: return
        val fresh = synchronized(newsSeen) { heads.filter { newsSeen.firstTime(it) } }
        if (!newsPrimed) { newsPrimed = true; return }
        val recent = fresh.filter { h -> h.at?.isAfter(Instant.now().minusSeconds(30 * 60)) != false }.filter { com.optionslab.ira.NewsAnalyst.matters(it) }.take(3)
        if (recent.isEmpty()) return
        val arms = HashMap<String, IraMarket>()
        runCatching { com.optionslab.app.data.OrbArms.view().arms }.getOrDefault(emptyList()).filter { it.armed }.forEach { arms[it.arm.label] = if (it.arm.hero) IraMarket.NIFTY else IraMarket.BANKNIFTY }
        com.optionslab.app.data.PineScripts.items.value.filter { it.auto.on }.forEach { x -> IraMarket.entries.firstOrNull { it.name == x.auto.symbol }?.let { arms[x.name] = it } }
        val held = runCatching { com.optionslab.app.data.Paper.state.positions.filter { it.quantity != 0 }.mapNotNull { p ->
            listOf(IraMarket.BANKNIFTY, IraMarket.FINNIFTY, IraMarket.NIFTY, IraMarket.SENSEX).firstOrNull { p.symbol.startsWith(it.name) } }.toSet() }.getOrDefault(emptySet())
        for (h in recent) {
            // A headline on an index the owner holds: good or bad for the side held, at once.
            runCatching { IraJournal.positionNews(h) }
            val t = com.optionslab.ira.NewsAnalyst.take(h, arms, held)
            if (runCatching { newsTrade(c, h, t) }.getOrDefault(false)) continue
            JarvisPopup.show(c, t.title, t.text)
            reply(t.text)
        }
    }

    /** How long a news trade waits for the owner's answer before it lapses (nothing placed). */
    const val NEWS_ANSWER_MS = 10 * 60_000L

    /**
     * Strong news the candles agree with ([com.optionslab.ira.NewsTrade]): Jarvis says the news, whether it is good or
     * bad and why, and asks; the trade (picked as the Liquidity arm picks, with its stop, target and the profit lock) is
     * placed only on the owner's yes - said aloud, or Approve on the pop-up or the Ira screen. No answer in 10 minutes:
     * nothing is placed. True when a trade was proposed (the news was told with it).
     */
    private suspend fun newsTrade(c: Context, h: Headline, t: com.optionslab.ira.NewsAnalyst.Take): Boolean {
        val m = h.markets.firstOrNull { it in listOf(IraMarket.BANKNIFTY, IraMarket.NIFTY, IraMarket.FINNIFTY) } ?: return false
        val snap = _state.value.snaps[m]
        val waiting = synchronized(actions) { newsAsks.size }
        val level = runCatching { tradeCheck().level }.getOrNull()
        val idea = com.optionslab.ira.NewsTrade.idea(h, snap, histories[m]?.bars.orEmpty(), LocalDateTime.now(IST), level,
            IraNewsTrades.today() + waiting, expiryToday(m), IraNewsTrades.lossLimitHit()) ?: return false
        proposeTrade(c, idea, t.title, t.text + " " + idea.why,
            "Hey Boss, news. ${Wake.spoken(t.text, 2)} The candles agree.", "news: " + h.title)
        return true
    }

    /**
     * A trade Jarvis suggests (strong news, or the pattern expert): 1 lot of the index's ATM option, nearest expiry, a
     * fixed 30-point stop, a +60 target and the profit lock on it ([com.optionslab.ira.JarvisTrades]) - never an option at
     * 35 or less - placed only on the owner's yes (aloud, Approve on the pop-up or
     * Confirm on the Ira screen); unanswered in 10 minutes it lapses. [said] opens the spoken question.
     */
    /** [solo]: Solo's setup - its own paper record decides whether the approved trade goes to Zerodha. */
    private suspend fun proposeTrade(c: Context, idea: com.optionslab.ira.NewsTrade.Idea, title: String, text: String, said: String, source: String,
                                     solo: Boolean = false) {
        if (GOLD_ONLY_TALK) return
        val m = idea.market
        // Boss's own rules in what he asked me to remember ("I don't trade BankNifty", "no trades before 9:30"): the
        // idea is neither offered nor taken (the news itself is still told).
        runCatching { com.optionslab.ira.BossRules.blocks(IraTools.memory().map { it.text }, m, expiryToday(m),
            LocalDateTime.now(IST).let { it.hour * 60 + it.minute }) }.getOrNull()?.let { why ->
            if (source.startsWith("news")) reply("$text (No trade offered: $why.)")
            IraActivity.add("Held back a ${m.label} idea: $why.")
            return
        }
        // Two losses in a row: a cooling-off, said once.
        // (The news itself is still told: only the trade is held back.)
        if (Automations.on(Automations.Auto.COOLOFF)) com.optionslab.ira.CoolOff.until(IraNewsTrades.closedTimes(), LocalDateTime.now(IST))?.let { until ->
            if (source.startsWith("news")) reply(text)
            if (synchronized(coolSaid) { coolSaid.add(until.toString()) }) {
                reply(com.optionslab.ira.CoolOff.say(until)); IraActivity.add(com.optionslab.ira.CoolOff.say(until))
                Automations.acted(Automations.Auto.COOLOFF, com.optionslab.ira.CoolOff.say(until))
            }
            return
        }
        // A kind of suggestion the owner always turns down is no longer offered (said once).
        val kind = com.optionslab.ira.Preference.kind(source)
        if (com.optionslab.ira.Preference.skip(kind, IraNewsTrades.answers())) {
            if (source.startsWith("news")) reply(text)
            if (IraNewsTrades.toldSkip(kind)) reply(com.optionslab.ira.Preference.say(kind))
            return
        }
        // Past the weekly loss limit: no suggestions until Monday (the news is still told).
        if (IraTools.weeklyHit()) {
            if (source.startsWith("news")) reply(text)
            if (synchronized(coolSaid) { coolSaid.add("weekly|" + com.optionslab.app.data.Market.today().with(java.time.DayOfWeek.MONDAY)) })
                reply(com.optionslab.ira.WeeklyCap.say(IraTools.weeklyLimit))
            return
        }
        val snap = _state.value.snaps[m]
        val side = if (idea.call) "call" else "put"
        // Money for it, with room to spare (Zerodha's funds when it would go live, else the paper account's).
        snap?.price?.let { px -> runCatching { IraNewsTrades.marginProblem(idea, px) }.getOrNull() }?.let { why -> reply("$text $why"); return }
        // Too cheap for the fixed 30-point stop (35 or less): no trade offered (the news itself is still told).
        snap?.price?.let { px -> runCatching { IraNewsTrades.premiumProblem(idea, px) }.getOrNull() }?.let { why -> reply("$text $why"); return }
        // Are options cheap or dear now? In the dearest tenth of the year, no buy is suggested.
        val iv = snap?.price?.let { runCatching { IraNewsTrades.ivNow(idea, it) }.getOrNull() }
        if (iv != null && iv.first >= com.optionslab.ira.IvRank.BLOCK) {
            reply("$text ${com.optionslab.ira.IvRank.say(iv.first, iv.second)}")
            return
        }
        val ivLine = iv?.let { " " + com.optionslab.ira.IvRank.say(it.first, it.second) } ?: ""
        // How sure: the pattern's record, the regime, how dear options are, the trade check.
        val conf = com.optionslab.ira.Confidence.score(idea.call, idea.hitRate, IraStudy.regimeOf(m), iv?.first, runCatching { tradeCheckFast().level }.getOrNull())
        // What it risks, in rupees and of the capital - worked out before the suggestion exists, and never waited on for
        // long (a slow broker would leave a suggestion registered but unseen while its price went stale).
        val riskAsk = scope.async { runCatching { IraNewsTrades.riskLine(idea, snap?.price ?: 0.0) }.getOrNull() }
        val risk = kotlinx.coroutines.withTimeoutOrNull(3_000) { riskAsk.await() }?.let { " $it" } ?: ""
        val what = "buy 1 lot of the ${m.label} $side at the money, nearest expiry, with a ${IraNewsTrades.STOP_POINTS.toInt()}-point stop, a +${IraNewsTrades.TARGET_POINTS.toInt()} target and the profit lock"
        // Independent (Boss's choice, 4 Oct): a sure enough idea that would go to the PAPER account is taken at once and
        // told - never one that would reach Zerodha (that is always asked).
        val goesLive = runCatching { IraNewsTrades.goesLive(solo) }.getOrDefault(true)
        // The bar is Jarvis's own: raised above any confidence level that has been losing him money.
        val bar = runCatching { IraNewsTrades.actAloneBar() }.getOrDefault(com.optionslab.ira.ActAlone.NONE)
        // And an hour of the day that has been losing him money: he does not act alone then, and tells Boss when asking.
        val nowMin = LocalDateTime.now(IST).let { it.hour * 60 + it.minute }
        // (Read failing: treated as a losing hour or kind - it only stops acting alone, never asking.)
        val badHour = runCatching { com.optionslab.ira.ActAlone.badHour(IraNewsTrades.byMinute(), nowMin) }.getOrElse { "my record could not be read" }
        // And a kind of idea (news, or this pattern) that has been losing: the same.
        val badKind = runCatching { com.optionslab.ira.ActAlone.badKind(IraNewsTrades.byKind(), com.optionslab.ira.Preference.kind(source)) }.getOrElse { "my record could not be read" }
        // And his own scored ideas, by the conditions they came in (self-calibration): where that record is clearly bad he
        // takes nothing by himself (he asks), where it is losing he takes half size. It only ever lowers his own paper risk.
        val regimeNow = runCatching { IraStudy.regimeOf(m) }.getOrNull()
        val calibCond = com.optionslab.ira.SelfCalibration.Conditions(LocalDateTime.now(IST), m, idea.call, com.optionslab.ira.Preference.kind(source), regimeNow, iv?.first)
        val calib = runCatching { com.optionslab.ira.SelfCalibration.judge(IraNewsTrades.calibration(), calibCond) }
            // (Read failing: treated as a bad record - it only stops acting alone, never asking.)
            .getOrElse { com.optionslab.ira.SelfCalibration.Judgment(com.optionslab.ira.SelfCalibration.Action.SIT_OUT, null) }
        val calibLine = calib.text()
        val sitOut = calib.action == com.optionslab.ira.SelfCalibration.Action.SIT_OUT
        val actPaperOn = Automations.on(Automations.Auto.ACT_PAPER)
        // The reason trail ([IraThinking]): every gate as it stood at this moment, written with what was decided.
        fun thought(outcome: com.optionslab.ira.Thinking.Paper, failed: String? = null) = runCatching {
            IraThinking.add(com.optionslab.ira.Thinking.paper(IraThinking.now(), calibCond, outcome, calib, conf.stars, bar, actPaperOn, goesLive,
                badHour, badKind, solo = solo, noPrice = snap == null, failed = failed))
        }
        if (!solo && badHour == null && badKind == null && !sitOut && com.optionslab.ira.ActAlone.ok(actPaperOn, goesLive, conf.stars, bar) && snap != null) {
            val shrink = calib.action == com.optionslab.ira.SelfCalibration.Action.SHRINK
            val done = runCatching { IraNewsTrades.place(idea, _state.value.snaps[m]?.price ?: snap.price, source, paperOnly = true, stars = conf.stars, shrink = shrink) }.getOrElse { "That did not work: ${it.message ?: "an error"}." }
            val took = done.startsWith("Bought")
            thought(if (!took) com.optionslab.ira.Thinking.Paper.NOT_PLACED else if (shrink) com.optionslab.ira.Thinking.Paper.HALF
                else com.optionslab.ira.Thinking.Paper.TOOK, if (took) null else IraActivity.short(done))
            val careful = if (shrink && calibLine != null) " Careful: $calibLine." else ""
            val said2 = "$text$ivLine ${conf.text()}$risk " + (if (took) "I took it myself on paper: $what. $done$careful" else "I meant to take it myself on paper, but: $done")
            reply(said2)
            if (took) {
                // Kept with the suggestions (scorecard, report card, "what if"), marked as Jarvis's own - never as Boss's answer.
                val sid = System.nanoTime()
                runCatching { IraNewsTrades.suggested(sid, idea, snap.price, source, regimeNow, iv?.first, conf.stars); IraNewsTrades.answered(sid, com.optionslab.ira.JarvisTrades.SELF) }
                IraActivity.add("Took on paper by myself: $what (${source.substringBefore(':')}).")
                Automations.acted(Automations.Auto.ACT_PAPER, "Took a ${m.label} $side on paper (${conf.stars}/5).")
            } else IraActivity.add("Did not take my own ${m.label} $side idea: ${IraActivity.short(done)}")
            runCatching { JarvisPopup.show(c, title, said2) }
            return
        }
        // An idea of his own sat out (his record there is poor): counted for his weekly review - it is still asked and scored.
        if (sitOut && !solo) IraTools.count(com.optionslab.ira.Improve.SIT_OUT)
        thought(com.optionslab.ira.Thinking.Paper.ASKED)
        val id = System.nanoTime()
        // The Requests panel's view: the idea in plain words, Paper or Zerodha as it would go now, lapsing with it.
        val reqAt = System.currentTimeMillis()
        val reqView = com.optionslab.ira.Requests.RequestView(id,
            if (solo) com.optionslab.ira.Requests.Kind.SOLO else com.optionslab.ira.Requests.Kind.TRADE,
            "buy 1 lot of the ${m.label} $side", what, com.optionslab.ira.Requests.why(text) ?: idea.why.takeIf { it.isNotBlank() },
            if (goesLive) com.optionslab.ira.Requests.Venue.ZERODHA else com.optionslab.ira.Requests.Venue.PAPER, reqAt, reqAt + NEWS_ANSWER_MS,
            symbol = "${m.label} $side (at the money)", qty = "1 lot",
            price = snap?.price?.let { "index at " + "%.2f".format(java.util.Locale.ENGLISH, it) + "; ${IraNewsTrades.STOP_POINTS.toInt()}-point stop, +${IraNewsTrades.TARGET_POINTS.toInt()} target, profit lock" })
        synchronized(actions) {
            actions[id] = what to suspend { IraNewsTrades.place(idea, _state.value.snaps[m]?.price ?: snap?.price ?: error("no ${m.label} price"), source,
                solo = solo, liveApproved = synchronized(actions) { id in liveApproved }, stars = conf.stars) }
            newsAsks += id
            if (solo) soloAsks += id
            requestMeta[id] = reqView
        }
        IraNewsTrades.suggested(id, idea, snap?.price ?: 0.0, source, regimeNow, iv?.first, conf.stars)
        IraActivity.add("Suggested: $what (${source.substringBefore(':')}).")
        val where = if (goesLive) " on ZERODHA with real money (approve with your fingerprint)"
            else if (com.optionslab.app.data.AppSettings.load().live) " (on paper: ${if (solo) "Solo's" else "my"} trades stay there until proven)" else ""
        val hourLine = listOfNotNull(badHour, badKind, calibLine?.takeIf { sitOut && !solo }).takeIf { it.isNotEmpty() }?.let { " A caution: ${it.joinToString("; ")}." } ?: ""
        // The reason Boss has turned such ideas down for, said up front when it fits this one ([com.optionslab.ira.TurnDowns]):
        // one line of words before the question - the idea, its gates and his Approve or reject are unchanged. Unlocked only.
        val turnLine = if (phoneLocked()) null else runCatching { IraTools.turnDownsLine(expiryToday(m)) }.getOrNull()
        val newsDetails = "$text$ivLine ${conf.text()}$risk$hourLine" + (turnLine?.let { " $it" } ?: "")
        val full = "$newsDetails Shall I $what$where? Approve or reject."
        // The whole idea - its risk, the IV line, any hour or kind caution, the turn-downs, the confidence - kept as the
        // request's details: in the panel's card and under the chat line (never lost to the short line).
        synchronized(actions) { requestMeta[id]?.let { rv -> requestMeta[id] = rv.copy(details = newsDetails.trim()) } }
        // The chat: one line naming it with its full what; the details under it, the buttons in the Requests panel too.
        _state.update { it.copy(pending = it.pending + id, messages = (it.messages + Msg(true, com.optionslab.ira.Requests.chatLine(reqView.title, reqView.what + where),
            action = id, details = newsDetails.trim())).takeLast(MAX_MESSAGES)) }
        JarvisApproval.show(c, id, title, full)
        // A score that has not held up is said aloud with its record beside it ([com.optionslab.ira.HonestStars]); the score,
        // the chat and the approval card are as worked out. On a locked phone, or on any trouble, the plain words.
        val starsAloud = if (phoneLocked()) "Confidence ${conf.stars} out of 5." else IraTools.starsAloud(conf.stars)
        JarvisVoice.askYesNo(id, com.optionslab.ira.Requests.spoken(reqView.title, "$said $starsAloud" + (turnLine?.let { " $it" } ?: "") + " Shall I buy 1 lot of the ${m.label} $side? Yes or no?"))
        scope.launch {
            kotlinx.coroutines.delay(NEWS_ANSWER_MS)
            val lapsedFp = runCatching { needsFingerprint(id) }.getOrNull()
            if (synchronized(actions) { actions.remove(id) } != null) {
                settled(id)
                requestDone(id, com.optionslab.ira.Requests.Outcome.LAPSED, result = "No answer in 10 minutes; nothing was placed.", fingerprint = lapsedFp)
                IraNewsTrades.answered(id, "lapsed")
                _state.update { it.copy(pending = it.pending - id) }
                reply("No answer in 10 minutes, so the ${m.label} trade lapsed; nothing was placed.")
                IraActivity.add("The ${m.label} trade I suggested lapsed unanswered.")
            }
        }
    }

    /**
     * Solo's setup offered to Boss as a trade idea (Solo switched off): the same gates, approval and paper-first rules as
     * every suggestion; one waiting at a time.
     */
    internal suspend fun offerSoloIdea(idea: com.optionslab.ira.NewsTrade.Idea, text: String, solo: Boolean = false): Boolean {
        if (GOLD_ONLY_TALK) return true
        val c = app ?: return false
        if (synchronized(actions) { newsAsks.isNotEmpty() }) return false
        // The gates every suggestion has: two of Jarvis's trades a day at most, none late on an expiry day (the market is
        // then counted as offered for the day).
        if (IraNewsTrades.today() >= com.optionslab.ira.NewsTrade.MAX_A_DAY) return true
        if (com.optionslab.ira.JarvisTrades.expiryBlock(expiryToday(idea.market), LocalDateTime.now(IST)) != null) return true
        proposeTrade(c, idea, "Trade idea: ${idea.market.label}", text, "Hey Boss, a trade idea. ${Wake.spoken(text, 1)}", "pattern: solo|${idea.market.name}", solo = solo)
        return true
    }

    private val coolSaid = HashSet<String>()

    // ---- watchers the market watch calls (Jarvis; each gates itself) ---------------------------------------------

    /** When each open position was first seen without a stop, and those already asked about (today). */
    private val unguardedSince = HashMap<String, Instant>()
    private val rescueAsked = HashSet<String>()

    /**
     * A bought option open two minutes with no stop: Jarvis offers one 15% under the price paid (asked, never set alone).
     */
    suspend fun rescueWatch(now: Instant = Instant.now()) {
        if (!com.optionslab.app.BuildConfig.JARVIS || !com.optionslab.app.data.Market.isOpen()) return
        val c = app ?: return
        val guard = Automations.on(Automations.Auto.GUARD)
        if (!Automations.on(Automations.Auto.RESCUE) && !guard) return
        // Guarded: a protection with a stop (or a trail), or a stop order the owner placed directly (ticket, Kite web).
        val guarded = runCatching { com.optionslab.app.data.Protections.active() }.getOrNull()?.filter { it.stop != null || it.trail != null }
            ?.map { (if (it.live) "L:" else "P:") + it.symbol }?.toMutableSet() ?: return
        val open = ArrayList<Pair<com.optionslab.ira.Rescue.Open, String>>()
        // Battery (round 7): the paper account read once, fresh, for both its positions here and its stop orders below (they
        // read it twice, moments apart: each read downloads every held contract's day of candles without a Zerodha session).
        // The stop priced from it and the "set alone" check further down read as before.
        val paperBook = runCatching { com.optionslab.app.data.Paper.snapshot() }.getOrNull()
        runCatching { paperBook?.positions?.positions?.filter { it.quantity != 0 }
            ?.forEach { open += com.optionslab.ira.Rescue.Open(it.symbol, false, it.quantity, it.averagePrice, it.ltp) to it.product } }
        // Zerodha reads with a deadline (this runs in the risk pass): slow or failed, the live positions wait for the next pass.
        // [liveRead]: Zerodha's positions and stop orders were both read this pass. When not, the live positions' "L:" first-
        // seen times are kept (a flaky read never restarts their two-minute grace, so the guard still gets to them).
        var liveRead = !com.optionslab.app.data.Broker.loggedIn
        if (com.optionslab.app.data.Broker.loggedIn) {
            val liveBook = com.optionslab.app.data.Broker.within(8_000) { com.optionslab.app.data.Broker.positionBook() }
            if (liveBook != null) {
                liveRead = true
                liveBook.net.filter { it.open && it.exchange == "NFO" }
                    .forEach { open += com.optionslab.ira.Rescue.Open(it.symbol, true, it.qty, it.avg, it.last) to it.product }
            }
        }
        // Nothing open (most passes): nothing can be bare, so the stop orders are not read (a Zerodha read each pass).
        if (open.isEmpty()) {
            synchronized(unguardedSince) { if (liveRead) unguardedSince.clear() else unguardedSince.keys.removeAll { !it.startsWith("L:") } }
            return
        }
        runCatching { paperBook?.orders?.orders?.filter { o -> o.priceType.uppercase() in setOf("SL", "SL-M") &&
            o.status.lowercase() !in setOf("complete", "cancelled", "rejected") }?.forEach { guarded += "P:" + it.symbol } }
        if (com.optionslab.app.data.Broker.loggedIn) {
            val liveOrders = com.optionslab.app.data.Broker.within(8_000) { com.optionslab.app.data.Broker.orders() }
            // Zerodha's stop orders not read in time: no live position is called bare this pass (never a stop offered on
            // one that has its own).
            if (liveOrders == null) { open.removeAll { it.first.live }; liveRead = false }
            else liveOrders.filter { o -> o.working && o.type in setOf("SL", "SL-M") }.forEach { guarded += "L:" + it.symbol }
            // An active Kite GTT that is a stop on the position (two-leg, or a closing one past the price - never a target
            // alone) guards it too: never a second stop beside it. Not read in time: no live position is called bare this
            // pass, as with the orders.
            if (open.any { it.first.live }) {
                val liveGtts = com.optionslab.app.data.Broker.within(8_000) { com.optionslab.app.data.Broker.gtts() }
                if (liveGtts == null) { open.removeAll { it.first.live }; liveRead = false }
                else liveGtts.filter { g -> g.status.lowercase() == "active" }.forEach { g ->
                    if (open.any { (o, _) -> o.live && o.symbol == g.symbol &&
                            com.optionslab.ira.Rescue.gttIsStop(g.type, g.triggers, g.lastPrice, g.orders, o.qty > 0) }) guarded += "L:" + g.symbol
                }
            }
            if (open.isEmpty()) return
        }
        // The bots (ORB arms, Pine scripts, strategy runs) manage their own exits: only positions they do not hold.
        val bots = HashSet<String>()
        runCatching { com.optionslab.app.data.OrbArms.view().arms.mapNotNull { it.open?.symbol }.forEach { bots += it } }
        runCatching { com.optionslab.app.data.PineAuto.held.value.values.forEach { bots += it.symbol } }
        runCatching { com.optionslab.app.data.Strategies.all().mapNotNull { it.run }.forEach { r -> r.openLegs().forEach { bots += it.symbol } } }
        // Jarvis's own trades keep their own exits too (none was ever bare long enough to matter, but never doubled).
        runCatching { IraNewsTrades.all().filter { !it.closed }.forEach { bots += it.symbol } }
        // Solo's paper trade keeps its own exits (the index stop, the breakeven lock, 14:30): never a premium stop set on it
        // (the solo2 study found a premium stop instead of the index stop cost money).
        runCatching { IraSolo.all().filter { !it.closed }.forEach { bots += it.symbol } }
        val day = com.optionslab.app.data.Market.today().toString()
        val bare = open.filter { (p, _) -> (if (p.live) "L:" else "P:") + p.symbol !in guarded && p.symbol !in bots }
        val bareKeys = bare.map { (p, _) -> (if (p.live) "L:" else "P:") + p.symbol }.toSet()
        synchronized(unguardedSince) { unguardedSince.keys.retainAll { it in bareKeys || (!liveRead && it.startsWith("L:")) } }
        for ((p, product) in bare) {
            val k = (if (p.live) "L:" else "P:") + p.symbol
            val since = synchronized(unguardedSince) { unguardedSince.getOrPut(k) { now } }
            if (since.isAfter(now.minusSeconds(com.optionslab.ira.Rescue.GRACE_MINUTES * 60))) continue
            if (!synchronized(rescueAsked) { rescueAsked.add("$day|$k") }) continue
            val stop = com.optionslab.ira.Rescue.stopFor(p)
            val text = com.optionslab.ira.Rescue.say(p, stop)
            // (Only told when offering is on: the guard alone never sets a stop on these, so it says nothing.)
            if (stop == null) { if (Automations.on(Automations.Auto.RESCUE)) { JarvisPopup.show(c, "Boss, ${p.symbol} has no stop", text); note(text, whole = true)
                runCatching { JarvisVoice.offerNote(text, com.optionslab.ira.SpeakChoice.Weight.IMPORTANT, whole = true) } }; continue }
            // Set alone only when nothing else could close it too: no working order on it at all and, at Zerodha, no GTT
            // on it (a GTT or a resting exit filling alongside the stop would leave a short). Otherwise only offered.
            val clear = guard && runCatching {
                // Read with a deadline: not read in time is not clear (only offered, never set alone).
                if (p.live) com.optionslab.app.data.Broker.within(8_000) {
                    com.optionslab.app.data.Broker.orders().none { it.working && it.symbol == p.symbol } &&
                        com.optionslab.app.data.Broker.gtts().none { it.symbol == p.symbol && it.status.lowercase() == "active" }
                } ?: false
                else com.optionslab.app.data.Paper.snapshot().orders.orders.none { it.symbol == p.symbol &&
                    it.status.lowercase() !in setOf("complete", "cancelled", "rejected") }
            }.getOrDefault(false)
            if (com.optionslab.ira.Rescue.setAlone(clear, p, stop)) {
                val result = runCatching {
                    if (p.live) com.optionslab.app.data.Protections.protectLive(p.symbol, "NFO", product, p.qty, p.ltp ?: p.avg, stop, null, null)
                    else com.optionslab.app.data.Protections.protectPaper(p.symbol, product, p.qty, p.ltp ?: p.avg, stop, null, null)
                }.getOrElse { "Not protected: ${it.message}" }
                val said = com.optionslab.ira.Rescue.saySet(p, stop, result)
                JarvisPopup.show(c, if (com.optionslab.ira.Rescue.failed(result)) "Boss, ${p.symbol} is NOT guarded" else "Boss, I guarded ${p.symbol}", said)
                // Said and shown whole: a "Not protected: ..." in it is never cut to the line "I set one" (review, 5 Oct).
                note(said, whole = true); IraActivity.add(said)
                runCatching { JarvisVoice.offerNote(said, com.optionslab.ira.SpeakChoice.Weight.IMPORTANT, whole = true) }
                Automations.acted(Automations.Auto.GUARD, said)
                continue
            }
            if (!Automations.on(Automations.Auto.RESCUE)) continue
            val what = "set a stop on ${p.symbol} at " + "%.2f".format(java.util.Locale.ENGLISH, stop)
            // Checked again when confirmed (it can come later): the position as it is then, still bare, still above the stop.
            val id = pend(what, suspend {
                val nowPos = if (p.live) runCatching { com.optionslab.app.data.Broker.positionBook().net.filter { it.symbol == p.symbol && it.product == product }
                        .let { l -> l.sumOf { it.qty } to l.firstOrNull()?.last } }.getOrNull()
                    else runCatching { com.optionslab.app.data.Paper.snapshot().positions.positions.filter { it.symbol == p.symbol && it.product == product }
                        .let { l -> l.sumOf { it.quantity } to l.firstOrNull()?.ltp } }.getOrNull()
                val qty = nowPos?.first ?: 0; val ltp = nowPos?.second
                val has = runCatching { com.optionslab.app.data.Protections.forSymbol(p.live, p.symbol) }.getOrNull()?.let { it.stop != null || it.trail != null } == true
                // A stop order Boss placed directly meanwhile (ticket, Kite web) is a stop too: never a second one (review,
                // 5 Oct). Not read (in time): none placed either. True: one is working; null: the orders could not be read.
                val slWorking: Boolean? = runCatching {
                    if (p.live) com.optionslab.app.data.Broker.within(8_000) {
                        com.optionslab.app.data.Broker.orders().any { o -> o.working && o.symbol == p.symbol && o.type in setOf("SL", "SL-M") } ||
                            com.optionslab.app.data.Broker.gtts().any { g -> g.symbol == p.symbol && g.status.lowercase() == "active" &&
                                com.optionslab.ira.Rescue.gttIsStop(g.type, g.triggers, g.lastPrice, g.orders, qty > 0) }
                    }
                    else com.optionslab.app.data.Paper.snapshot().orders.orders.any { o -> o.symbol == p.symbol && o.priceType.uppercase() in setOf("SL", "SL-M") &&
                        o.status.lowercase() !in setOf("complete", "cancelled", "rejected") }
                }.getOrNull()
                when {
                    qty <= 0 -> "${p.symbol} is no longer held, so no stop was set."
                    has -> "${p.symbol} has a stop now, so I left it."
                    slWorking == true -> "${p.symbol} has a stop order or GTT working now, so I did not place a second one."
                    slWorking == null -> "I could not read ${p.symbol}'s orders just now, so no stop was placed - ask me again in a moment."
                    ltp == null || ltp <= stop -> "${p.symbol} is already at or under " + "%.2f".format(java.util.Locale.ENGLISH, stop) + ": close it or set a stop from the position."
                    p.live -> com.optionslab.app.data.Protections.protectLive(p.symbol, "NFO", product, qty, ltp, stop, null, null)
                    else -> com.optionslab.app.data.Protections.protectPaper(p.symbol, product, qty, ltp, stop, null, null)
                }
            }, "$text Tap Confirm to $what.", kind = com.optionslab.ira.Requests.Kind.GUARD,
                venue = if (p.live) com.optionslab.ira.Requests.Venue.ZERODHA else com.optionslab.ira.Requests.Venue.PAPER,
                symbol = p.symbol, qty = p.qty.toString(), price = "stop at " + "%.2f".format(java.util.Locale.ENGLISH, stop))
            JarvisPopup.show(c, "Boss, ${p.symbol} has no stop", text, action = id)
            JarvisVoice.askYesNo(id, com.optionslab.ira.Requests.spoken(com.optionslab.ira.Requests.title(what), "Boss, $text Yes or no?"))
            IraActivity.add("Offered a stop on ${p.symbol} (it had none).")
            Automations.acted(Automations.Auto.RESCUE, "Offered a stop on ${p.symbol}.")
        }
    }

    /**
     * Offers to close [symbol] (asked yes or no and on the Ira screen; never done alone). Checked again when confirmed:
     * still held, then sold at market (paper) or squared off through the app (Zerodha).
     */
    /**
     * Something Jarvis thinks should be done or stopped, put to Boss first (4 Oct): pending with Confirm on the Ira
     * screen, a pop-up and a spoken yes or no; [act] runs only on his yes, and lapses after 30 minutes.
     */
    fun offer(what: String, title: String, text: String, act: suspend () -> String, addsRisk: Boolean = false,
              /** Always put to Boss, even with automatic stops on (a lesson from Jarvis's study is never kept unasked). */ alwaysAsk: Boolean = false) {
        val c = app ?: return
        // Boss said "do it automatically" in chat: done now and told - only what stops or parks; anything that adds risk
        // (arming again) is always asked (review, 4 Oct).
        if (autoStop && !addsRisk && !alwaysAsk) {
            scope.launch {
                val r = IraActions.run(what, act)
                JarvisPopup.show(c, title, r); reply(com.optionslab.ira.Address.boss("Done by myself, as you asked: $r"), whole = true)
            }
            return
        }
        // Jarvis's own offer (unasked): its question's first line leads the chat's short line, so it reads as his note
        // beside an answer, never as the answer ([replyAfter]); the details and buttons are in the panel.
        val id = pend(what, act, "$text Tap Confirm to go ahead.", kind = com.optionslab.ira.Requests.Kind.OFFER, lead = com.optionslab.ira.Requests.why(text))
        JarvisPopup.show(c, title, text, action = id)
        JarvisVoice.askYesNo(id, com.optionslab.ira.Requests.spoken(com.optionslab.ira.Requests.title(what), Wake.spoken(text, 2) + " Yes or no?"))
        IraActivity.add("Asked: $text")
    }

    /**
     * A wording learned only on Boss's yes ([com.optionslab.ira.Corrections.propose]): asked once, and on the yes checked
     * again - the phone still unlocked and the wording still a question that does not act - before it is kept.
     */
    private fun offerWording(l: com.optionslab.ira.Corrections.Learned) {
        IraTools.offered(l)
        scope.launch {
            kotlinx.coroutines.delay(300)
            // A plain label (Boss's words stay out of the diagnostics log).
            offer("learn a wording you rephrased", "Boss, shall I learn this?", com.optionslab.ira.Corrections.offer(l), suspend {
                when {
                    phoneLocked() -> "Unlock the phone for that, Boss: nothing was learned."
                    !com.optionslab.ira.Corrections.safe(l) -> "Nothing was learned, Boss: that would not be a question."
                    else -> { IraTools.teach(l); com.optionslab.ira.Corrections.kept(l) }
                }
            }, alwaysAsk = true)
        }
    }

    /**
     * A routine of Boss's ([com.optionslab.ira.Routine]): put to him once, kept only on his yes (on an unlocked phone,
     * with "Your usual, unasked" on, never in IraGoldAlgo) - its answer then said at its time, in words only.
     */
    private fun offerRoutine(p: com.optionslab.ira.Routine.Pattern) {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD || !Automations.on(Automations.Auto.USUAL) || phoneLocked()) return
        if (!com.optionslab.ira.Routine.safe(p.key)) return
        IraTools.routineOffered(p)
        scope.launch {
            kotlinx.coroutines.delay(300)
            // A plain label (Boss's words stay out of the diagnostics log).
            offer("keep an answer ready at its usual time", "Boss, your routine?", com.optionslab.ira.Routine.offer(p), suspend {
                when {
                    phoneLocked() -> "Unlock the phone for that, Boss: nothing was kept."
                    !Automations.on(Automations.Auto.USUAL) -> "\"Your usual, unasked\" is off in Automations, Boss: nothing was kept."
                    else -> IraTools.keepRoutine(p) ?: "Nothing was kept, Boss: that would not be a question."
                }
            }, alwaysAsk = true)
        }
    }

    /** Boss's "do it automatically" / "ask me before stopping" (default: asked). Never taken from a backup. */
    var autoStop: Boolean
        get() = runCatching { com.optionslab.app.security.SecurePrefs.getBoolean("jarvis.auto.stop", false) }.getOrDefault(false)
        set(v) { runCatching { com.optionslab.app.security.SecurePrefs.put("jarvis.auto.stop", v) } }

    /** A broken loss goal: the kill switch offered (asked yes or no and on the Ira screen; it only lowers risk). */
    suspend fun offerKillSwitch(text: String) {
        val c = app ?: return
        val (what, act) = runCatching { IraActions.prepare(com.optionslab.ira.Command(com.optionslab.ira.Command.Kind.KILL_ON)) }.getOrNull() ?: return
        if (act == null) { reply(text); return }
        offer(what, "Boss, a goal is broken", text, suspend { IraActions.verified(com.optionslab.ira.Command.Kind.KILL_ON, act()) })
    }

    fun offerClose(symbol: String, live: Boolean, text: String) {
        val c = app ?: return
        val id = pend("close $symbol", suspend {
            if (live) IraActions.closeLiveSymbol(symbol)
            else {
                val p = runCatching { com.optionslab.app.data.Paper.snapshot().positions.positions.firstOrNull { it.symbol == symbol && it.quantity != 0 } }.getOrNull()
                if (p == null) "$symbol is no longer held." else com.optionslab.app.data.Paper.close(p.symbol, p.product).let { r ->
                    r.orderId?.let { com.optionslab.app.data.Strategies.tagOwner("paper:$it", com.optionslab.app.data.Origins.manual("Jarvis")) }   // Boss confirmed it
                    "Paper: ${r.message}" + (com.optionslab.app.data.Origins.shortId(r.orderId)?.let { " (order $it)" } ?: "") }
            }
        }, "$text Tap Confirm to close $symbol.", kind = com.optionslab.ira.Requests.Kind.CLOSE,
            venue = if (live) com.optionslab.ira.Requests.Venue.ZERODHA else com.optionslab.ira.Requests.Venue.PAPER, symbol = symbol, price = "at market")
        JarvisPopup.show(c, "Boss, $symbol is going nowhere", text, action = id)
        JarvisVoice.askYesNo(id, com.optionslab.ira.Requests.spoken("close $symbol", "Boss, $text Yes or no?"))
    }

    /** Expiry day at 14:55: what the 15:05 square-off will close (or, when it is off, what will be settled). */
    suspend fun expiryPreview() {
        if (!com.optionslab.app.BuildConfig.JARVIS || !Automations.on(Automations.Auto.EXPIRY)) return
        val mk = com.optionslab.app.data.Market
        val today = mk.today()
        if (!mk.isTradingDay(today) || mk.minuteNow() !in com.optionslab.ira.ExpiryPreview.AT until 15 * 60 + 5) return
        val key = "jarvis.expiry.preview"
        if (com.optionslab.app.security.SecurePrefs.getString(key) == today.toString()) return
        val names = ArrayList<String>()
        // Words only (names and quantities, never a price): a price read in the last 20 s is shared (Battery, round 7).
        runCatching { com.optionslab.app.data.Paper.snapshot(com.optionslab.app.data.Paper.SHARED_QUOTE_MS).positions.positions.filter { it.quantity != 0 }.forEach { p ->
            if (com.optionslab.app.data.Paper.contractOf(p.symbol)?.expiry == today) names += "paper ${p.symbol} (${p.quantity})" } }
        if (com.optionslab.app.data.Broker.loggedIn) runCatching {
            val ins = com.optionslab.app.data.Broker.cachedInstruments().orEmpty().associateBy { it.tradingSymbol }
            com.optionslab.app.data.Broker.positionBook().net.filter { it.qty != 0 && ins[it.symbol]?.expiry == today }.forEach { names += "Zerodha ${it.symbol} (${it.qty})" }
        }
        val lines = com.optionslab.ira.ExpiryPreview.lines(names, com.optionslab.app.data.AppSettings.load().expirySquareOff)
        com.optionslab.app.security.SecurePrefs.put(key, today.toString())
        if (lines.isEmpty()) return
        Automations.acted(Automations.Auto.EXPIRY, lines.first())
        // A locked phone may be overheard or seen: the positions are named in the chat only.
        val plain = com.optionslab.ira.Overheard.said(lines.first(), phoneLocked(), "Boss, positions of yours expire today: they're named in the chat.")
        app?.let { JarvisPopup.show(it, "Expiry day: 15:05 square-off", plain) }
        reply(lines.first())
        JarvisVoice.announce(com.optionslab.ira.Address.boss(plain), urgent = true)
    }

    @Volatile private var feedWarned = false

    /** Live prices stopped for two minutes in market hours: told once, and again only after they came back. */
    suspend fun feedWatch() {
        if (!com.optionslab.app.BuildConfig.JARVIS || !Automations.on(Automations.Auto.FEED)) return
        val open = com.optionslab.app.data.Market.isOpen()
        // After the close nothing "comes back": a warning still standing is simply cleared.
        if (!open) { feedWarned = false; return }
        fun last() = _state.value.liveAt?.atZone(IST)?.toLocalDateTime()
        val now = LocalDateTime.now(IST)
        if (com.optionslab.ira.FeedHealth.stale(last(), now, open)) runCatching { refresh() }
        val stale = com.optionslab.ira.FeedHealth.stale(last(), LocalDateTime.now(IST), open)
        // The day's freshness record ([com.optionslab.ira.DataAge]): sampled only just after a fetch (or one that failed),
        // so prices merely not re-read yet are not counted as a feed behind. Ages only.
        runCatching {
            val st = _state.value
            if (st.liveAt?.isAfter(Instant.now().minusSeconds(30)) == true || IraMarket.NIFTY in st.liveMissing)
                IraTools.freshSeen(ageChecks(listOf(IraMarket.NIFTY), setOf(Topic.OVERVIEW, Topic.NEWS)))
        }
        if (stale && !feedWarned) {
            feedWarned = true
            val text = com.optionslab.ira.FeedHealth.say(last())
            app?.let { JarvisPopup.show(it, "Live prices stopped", text) }
            reply(text); JarvisVoice.announce(text, urgent = true); IraActivity.add("Warned: live prices stopped.")
            Automations.acted(Automations.Auto.FEED, text)
        } else if (!stale && feedWarned) {
            feedWarned = false
            reply("Live prices are back, Boss.")
        }
    }

    private fun expiryToday(m: IraMarket) = runCatching { com.optionslab.app.data.Market.isExpiryDay(m.name) }.getOrDefault(false)

    /** The suggested trade waiting for the owner's answer now (its action id), or null - for the home-screen widget. */
    fun waitingTrade(): Long? = synchronized(actions) { newsAsks.firstOrNull() }

    /** Is [id] a suggested trade Jarvis asks a yes or no about (so the voice does not also say "tap Confirm")? */
    fun asksYesNo(id: Long): Boolean = synchronized(actions) { id in newsAsks }

    /** Read-only: does action [id] still wait for Boss's yes or Confirm (not yet answered, cancelled or lapsed)? */
    fun waitsFor(id: Long): Boolean = synchronized(actions) { actions.containsKey(id) }

    @Volatile private var lastExpertSlot: LocalDateTime? = null
    private val suggested = HashSet<String>()

    /**
     * The pattern expert on watch (Jarvis, market hours, called by the market watch; it gates itself): at each 5- and
     * 15-minute close, the indices' last candle judged by [com.optionslab.ira.PatternExpert]; a pattern with a record
     * that held in both years, in the right place, becomes a suggested trade with its reasons. One at a time.
     */
    suspend fun expertWatch(now: Instant = Instant.now()) {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        val t = now.atZone(IST).toLocalDateTime()
        if (!IraMarket.NIFTY.trading(t) || t.minute % 5 > 2) return
        val slot = t.withSecond(0).withNano(0).withMinute(t.minute / 5 * 5)
        if (lastExpertSlot == slot) return
        lastExpertSlot = slot
        val edges = IraStudy.state.value.edges
        if (edges.none { it.held }) return
        val c = app ?: return
        if (synchronized(actions) { newsAsks.isNotEmpty() }) return
        refresh()
        val level = runCatching { tradeCheck().level }.getOrNull()
        expertIdea(t, edges, level, IraStudy.MARKETS, every = true)?.let { (idea, key) ->
            if (synchronized(suggested) { suggested.add(key) })
                proposeTrade(c, idea, "Trade idea: ${idea.market.label}", idea.why, "Hey Boss, a trade idea. ${Wake.spoken(idea.why, 1)}", "pattern: " + (idea.kind?.let { "$it|" } ?: "") + key)
        }
    }

    /** The first suggestion on [markets]' 5- and 15-minute charts now, with its key (market, chart, candle); or null. */
    private fun expertIdea(t: LocalDateTime, edges: List<com.optionslab.ira.PatternExpert.Edge>, level: com.optionslab.ira.TradeCheck.Level?,
                           markets: List<IraMarket>, every: Boolean, why: MutableList<String>? = null): Pair<com.optionslab.ira.NewsTrade.Idea, String>? {
        for (m in markets) for (min in listOf(15, 5)) {
            if (every && min == 15 && t.minute % 15 > 2) continue
            val candles = com.optionslab.ira.Candles.closed(com.optionslab.ira.Candles.fold(recentBars(m), min, m), min, t)
            val v = com.optionslab.ira.PatternExpert.judge(min, candles, _state.value.snaps[m], edges, level, t,
                IraNewsTrades.today() + synchronized(actions) { newsAsks.size }, expiryToday(m), IraNewsTrades.lossLimitHit())
            v.idea?.let { return it to "${m.name}|$min|${candles.last().t}" }
            why?.addAll(v.reasons)
        }
        return null
    }

    /**
     * "What should I buy?": the owner does not want trades on demand (2026-10-02) - the expert brings them by itself at
     * each candle close. Asked, Jarvis says what it watches and why nothing qualifies right now; it never proposes here.
     */
    private fun suggestAsked(q: String, markets: List<IraMarket>) {
        _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
        scope.launch {
            val edges = IraStudy.state.value.edges
            if (edges.isEmpty()) { reply("I have not studied the candle patterns yet: I learn each pattern's two-year record every night after the close. Then I watch every 5- and 15-minute candle and bring you a trade for approval when one qualifies."); return@launch }
            if (_state.value.liveAt?.isAfter(Instant.now().minusSeconds(120)) != true) runCatching { refresh() }
            val level = runCatching { tradeCheck().level }.getOrNull()
            val why = ArrayList<String>()
            val got = expertIdea(LocalDateTime.now(IST), edges, level, markets.filter { it in IraStudy.MARKETS }.ifEmpty { IraStudy.MARKETS }, every = false, why = why)
            reply("I watch every 5- and 15-minute candle of Nifty, BankNifty and FinNifty and bring you a trade for approval when one qualifies. " +
                (if (got != null) "One is forming on ${got.first.market.label}: I will bring it at the candle close if it still qualifies. "
                 else "Nothing qualifies right now: " + why.distinct().take(3).joinToString(" ") + " ") +
                "Patterns that held in both years: " + com.optionslab.ira.PatternExpert.best(edges, 3).joinToString(" "))
        }
    }

    /** News trades waiting for an answer (at most [com.optionslab.ira.NewsTrade.MAX_A_DAY] a day with those placed). */
    private val newsAsks = HashSet<Long>()
    /** Of [newsAsks], Solo's setups (their own record decides live), and those Boss approved for real money. */
    private val soloAsks = HashSet<Long>()
    private val liveApproved = HashSet<Long>()

    /** [id] was answered or lapsed: its pop-up hides and Jarvis stops waiting for a yes or no. */
    private fun settled(id: Long) {
        synchronized(actions) { newsAsks.remove(id); soloAsks.remove(id) }
        app?.let { JarvisApproval.hide(it, id) }
        JarvisVoice.answered(id)
    }

    /**
     * Jarvis, every 15 minutes in market hours: the near-the-money option chains (Nifty, BankNifty, FinNifty) as
     * they trade - each contract's 1-minute OHLC, volume and OI - kept into the app's record, like the evening harvest.
     */
    private suspend fun keepChains(pass: IraAccount.ChainPass) {
        // Battery (round 6): Live mode prices the chain from Zerodha's quotes - a snapshot this record never keeps (below) -
        // so it is not read for it at all.
        if (runCatching { com.optionslab.app.data.Market.liveMode() }.getOrDefault(false)) return
        val today = LocalDate.now(IST)
        // With the nightly harvest on (it stores the whole day's chain after the close) a chain not already read in this pass
        // is read hourly and once from 15:15 ([com.optionslab.ira.ChainKeepPace]); harvest off: every pass, as before.
        val at = LocalDateTime.now(IST)
        val harvestOn = runCatching { com.optionslab.app.data.AppSettings.load().nightlyHarvest }.getOrDefault(false)
        val due = com.optionslab.ira.ChainKeepPace.due(chainsKeptAt, at, harvestOn)
        if (due) chainsKeptAt = at
        for (u in listOf("NIFTY", "BANKNIFTY", "FINNIFTY")) runCatching {
            if (!due && !pass.has(u)) return@runCatching
            val lc = withTimeoutOrNull(40_000) { pass.live(u) } ?: return@runCatching
            if (lc.pricedAt != null) return@runCatching          // a quote snapshot, not minute candles: nothing to keep
            Store.upsertDay(u, today, lc.series.filter { it.expiry != null })
        }
    }

    /** When [keepChains] last read the chains it was not handed (the record's own pace). */
    @Volatile private var chainsKeptAt: LocalDateTime? = null

    /** Alerts already given ("type|...|day"), so each is given once. */
    private val alerted = HashSet<String>()

    /**
     * Jarvis's watch (Jarvis, every 15 minutes in market hours): India VIX jumping, an index moving a lot or suddenly,
     * an arm losing half the day's loss limit - each a 3-second pop-up and a line in the conversation, once.
     */
    private suspend fun watchAlerts() {
        val c = app ?: return
        val s = com.optionslab.app.data.AppSettings.load()
        val limit = if (s.live) s.guardDailyLoss else s.guardPaperDailyLoss
        val arms = ArrayList<com.optionslab.ira.Watch.ArmDay>()
        com.optionslab.app.data.PineScripts.items.value.forEach { x -> com.optionslab.app.data.PineAuto.todayOf(x.id)?.let { arms += com.optionslab.ira.Watch.ArmDay(x.name, it) } }
        runCatching { com.optionslab.app.data.OrbArms.view().arms }.getOrDefault(emptyList()).forEach { a ->
            val closed = a.today.filter { !it.open }
            if (closed.isNotEmpty()) arms += com.optionslab.ira.Watch.ArmDay(a.arm.label, closed.sumOf { (it.grossPnl ?: 0.0) - it.charges })
        }
        runCatching { com.optionslab.app.data.Strategies.all() }.getOrDefault(emptyList()).forEach { e -> e.run?.let { r -> arms += com.optionslab.ira.Watch.ArmDay(e.def.name, r.pnlTotal) } }
        val day = LocalDate.now(IST)
        val alerts = com.optionslab.ira.Watch.check(_state.value.snaps, histories.mapValues { it.value.bars.takeLast(KEEP_DAYS * 375) }, arms, limit)
        for (a in alerts) {
            if (!synchronized(alerted) { alerted.add("${a.key}|$day") }) continue
            // A market alert pops up unless one about the same move just did ([IraAirtime]; never spoken); an arm's
            // loss is a safety warning and always pops up.
            val w = com.optionslab.ira.Airtime.ofWatch(a, LocalDateTime.now(IST))
            if (w == null) JarvisPopup.show(c, a.title, a.text)
            else IraAirtime.offer(w.source, w.subject, w.up, a.title, a.text, w.spoken, w.brief, voice = false)
            reply(a.text)
        }
    }
    const val BACKGROUND_MINUTES = 15L
    private const val NOTIFY_BASE = 7300

    /** A question in, Ira's answer appended to the conversation. */
    /** The morning check's line per index: trend, today's usual range, pivot (from the candles the phone holds). */
    fun morningOutlook(): List<String> = runCatching {
        val vix = _state.value.snaps[IraMarket.VIX]?.price ?: 0.0
        // The index Boss asks about by name first ([com.optionslab.ira.LeadIndex]; only the order changes, never a figure).
        val firstOut = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD) runCatching { IraTools.firstIndex() }.getOrNull() else null
        com.optionslab.ira.LeadIndex.order(listOf(IraMarket.NIFTY, IraMarket.BANKNIFTY), firstOut)
            .mapNotNull { m -> com.optionslab.ira.Outlook.brief(m, histories[m]?.bars.orEmpty(), vix) }
    }.getOrDefault(emptyList())

    /** The GIFT Nifty sentences ([com.optionslab.ira.MorningCues.gift]): the recorder's last reading against Nifty's previous close. */
    private fun giftCue(now: LocalDateTime): Triple<com.optionslab.ira.RecorderFeeds.Gift?, LocalDateTime?, com.optionslab.ira.MorningCues.Close?> {
        val g = com.optionslab.app.data.MarketRecorder.lastGift()
        val prev = g?.let { (read, gift) -> com.optionslab.ira.MorningCues.prevClose(histories[IraMarket.NIFTY]?.bars.orEmpty(), gift.at ?: read) }
        return Triple(g?.second, g?.first, prev)
    }

    /** The FIIs' newest and previous index positioning kept by the recorder (either may be null). Reads day files: off the main thread. */
    private fun fiiCue(): Pair<com.optionslab.ira.MorningCues.Fii?, com.optionslab.ira.MorningCues.Fii?> {
        val ps = com.optionslab.app.data.MarketRecorder.participantsRecorded()
        return com.optionslab.ira.MorningCues.fii(ps.getOrNull(0)) to com.optionslab.ira.MorningCues.fii(ps.getOrNull(1))
    }

    /** Jarvis's answer to a morning-cues question ([com.optionslab.ira.MorningCues.Ask]); the FIIs' cash flows added when NSE's are held. */
    private suspend fun morningCuesAnswer(ask: com.optionslab.ira.MorningCues.Ask): String {
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        val gift = if (ask == com.optionslab.ira.MorningCues.Ask.FII) emptyList() else
            giftCue(now).let { (g, read, prev) -> com.optionslab.ira.MorningCues.gift(g, read, prev, now) }
        val fii = if (ask == com.optionslab.ira.MorningCues.Ask.GIFT) emptyList() else {
            val (cur, prev) = fiiCue()
            val flowLine = if (ask == com.optionslab.ira.MorningCues.Ask.FII) runCatching { withTimeoutOrNull(8_000) { flows() } }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { com.optionslab.ira.Flows.lines(it).first() } else null
            com.optionslab.ira.MorningCues.fiiSay(cur, prev) + listOfNotNull(flowLine)
        }
        return com.optionslab.ira.MorningCues.answer(ask, gift, fii)
    }

    /**
     * The 09:00 check's morning cues ([com.optionslab.ira.MorningCues]): GIFT Nifty's gap from Nifty's previous close and
     * the FIIs' index positioning, each only when the recorder holds a recent one. Not in IraGoldAlgo. Reads only.
     */
    suspend fun morningCues(): List<String> {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val now = com.optionslab.app.data.Market.now().toLocalDateTime()
                val (g, read, prev) = giftCue(now)
                val (cur, before) = fiiCue()
                listOfNotNull(com.optionslab.ira.MorningCues.giftBrief(g, read, prev, now),
                    com.optionslab.ira.MorningCues.fiiBrief(cur, before, now.toLocalDate()))
            }.getOrDefault(emptyList())
        }
    }

    /**
     * The 09:00 check made its outlook ([morningOutlook]): each index's numbers (previous close, usual-day range, direction
     * read, pivot - [com.optionslab.ira.OutlookCheck.call]) noted for the 15:35 wrap-up. Trading days only; market data only.
     */
    fun outlookNoted() {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD) return
        runCatching {
            val day = com.optionslab.app.data.Market.today()
            if (!com.optionslab.app.data.Market.isTradingDay(day)) return@runCatching
            val vix = _state.value.snaps[IraMarket.VIX]?.price ?: 0.0
            IraTools.outlookMade(com.optionslab.ira.OutlookCheck.MARKETS.mapNotNull { m -> com.optionslab.ira.OutlookCheck.call(m, histories[m]?.bars.orEmpty(), vix, day) })
        }
    }

    /** For the 15:35 wrap-up: today's 09:00 outlook against the close, with the record so far - or null (none noted, or the day not in). */
    fun outlookCheckLine(): String? {
        if (!com.optionslab.app.BuildConfig.JARVIS || com.optionslab.app.BuildConfig.GOLD) return null
        return runCatching {
            IraTools.outlookCheck(com.optionslab.ira.OutlookCheck.MARKETS.associateWith { m -> histories[m]?.bars.orEmpty() }, com.optionslab.app.data.Market.today())
        }.getOrNull()
    }

    /** IraGoldAlgo: Jarvis only talks there (no orders, commands, NSE feeds, strategies or trade ideas). */
    private val GOLD_ONLY_TALK get() = com.optionslab.app.BuildConfig.GOLD

    /** IraGoldAlgo's answer to an order or a command: Jarvis there only talks. */
    const val GOLD_TALK_ONLY = "In IraGoldAlgo I only talk, Boss: orders and commands are in IraAlgo. The gold arms trade on paper by their own rules."

    fun ask(text: String) = ask(text, understood = false)

    /**
     * Speed (2026-10-05): Boss's question read OFF the screen's thread. [ask] runs every question reader in turn (many
     * patterns) and reads and writes Jarvis's learnings (the vault: Keystore work, disk syncs); on the main thread that
     * froze the screen for the length of it. Questions go through one lane, one at a time, in the order asked, exactly
     * as [ask] / [askConfirmed] do them; the returned job ends when the question has been taken in (as [ask] returning
     * did), so a caller waiting for the reply ([replyAfter]) still waits after it. Not tied to the screen: leaving the
     * page never drops a question.
     */
    fun askSoon(text: String, confirmed: Boolean = false, timed: Long = 0L): kotlinx.coroutines.Job {
        // Speed, round 4: each question's stages timed for the diagnostics ([com.optionslab.ira.AskStages]; durations
        // only). [timed]: the voice's own timing of this question ([askStages] begun at Boss's words); 0: typed, timed here.
        val typed = timed == 0L
        val id = if (typed) askStages.begin(android.os.SystemClock.elapsedRealtime(), voice = false) else timed
        return scope.launch(askLane) {
            askStages.mark(id, com.optionslab.ira.AskStages.Stage.STARTED, android.os.SystemClock.elapsedRealtime())
            // Heard or typed, for the mis-heard fragment check ([com.optionslab.ira.MisHeard]); set in this lane, one question at a time.
            heardByVoice = !typed
            try { awaitLoaded(); if (confirmed) askConfirmed(text) else ask(text) }
            finally { askStages.mark(id, com.optionslab.ira.AskStages.Stage.ROUTED, android.os.SystemClock.elapsedRealtime()) }
            // A typed question: answered when its reply is in the chat (spoken or not - the screen shows it then).
            if (typed) scope.launch {
                val said = com.optionslab.ira.Secrets.redact(text.trim())
                val got = kotlinx.coroutines.withTimeoutOrNull(30_000) { _state.first { st -> replyAfter(st.messages, said) != null } }
                if (got != null) askStages.mark(id, com.optionslab.ira.AskStages.Stage.ANSWERED, android.os.SystemClock.elapsedRealtime())
                askStages.finish(id)
            }
        }
    }

    /** Each question's stages, for the diagnostics' "Speed (asks):" line (durations only, never words). Speed, round 4. */
    val askStages = com.optionslab.ira.AskStages()

    /** The question in the lane now was heard by the voice (else typed): voice, round 26 ([com.optionslab.ira.MisHeard]). */
    @Volatile private var heardByVoice = false

    /** The reset hook between tests (Robolectric shares this object): the ask timings and the account's kept figures. */
    internal fun resetAskSpeed() { askStages.clear(); IraAccount.resetSpeed() }

    /** The diagnostics' "Speed (asks):" line: where a question's wait goes, the account answers, and the Hindi setting. */
    fun askSpeedLine(): String = askStages.line() + " · " + IraAccount.speedNote() +
        " · Hindi replies: " + (if (runCatching { JarvisVoice.hindi }.getOrDefault(false)) "on" else "off") +
        " · AI model loaded: " + runCatching { IraModel.state.value.loaded }.getOrDefault(false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val askLane = Dispatchers.Default.limitedParallelism(1)

    /** A spoken request on a voice that was not recognised: any command in it waits for a yes / Confirm. */
    fun askConfirmed(text: String) = ask(text, understood = true)

    /** [understood]: [text] is the model's reading of the owner's words (its actions always wait for Confirm). */
    /** [cleaned]: the words already read past their fillers or as a follow-up (a question): not read so again. */
    /** [rescued]: [text] is a mis-heard question read as what was meant ([com.optionslab.ira.MisHeard.rescue]) - already counted as heard. */
    private fun ask(text: String, understood: Boolean, cleaned: Boolean = false, rescued: Boolean = false) {
        // Secrets never go further than this line: not into the conversation, the saved history or the model.
        val q = com.optionslab.ira.Secrets.redact(text.trim())
        if (q.isEmpty()) return
        // An answer to Jarvis's journal question, while one is open ([IraDayJournal]): kept in the journal as Boss said it
        // and never acted on - not cleaned, rewritten or read as anything else first. An order or a command is not an
        // answer: it pauses the questions and goes on as usual.
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD) {
            val said = runCatching { IraDayJournal.heard(q) }.getOrNull()
            if (said != null) { _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }; return }
        }
        // Words read as understood (a voice not recognised, the chat's confirm path, or a reading of his words): they are his
        // next words too, so the morning offer and the wait for a turn-down reason end here (never taken by them).
        if (com.optionslab.app.BuildConfig.JARVIS && understood) runCatching { IraTools.endWaits() }
        // Boss's own words: anything but a command naming one arm or position ends the wait for his pick after "Which one?"
        // ([com.optionslab.ira.Nicknames]). Words only; nothing is learned here.
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !understood && !cleaned) runCatching { IraTools.nickHeard(q) }
        // Boss's bare "yes" as his very next words after the morning check offered his usual morning question
        // ([com.optionslab.ira.MorningAsks]): asked as that question - a market question only, checked again here, never an
        // order or a command. Any other words end the offer, and a yes while something waits for his yes or Confirm is never
        // taken as it. Understanding only: nothing learned acts.
        if (com.optionslab.app.BuildConfig.JARVIS && !understood && !cleaned) {
            val waiting = synchronized(actions) { actions.isNotEmpty() }
            val usual = runCatching { IraTools.morningAsksYes(q, waiting) }.getOrNull()
            if (usual != null) {
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, "$TOOK_AS\"$usual\".")).takeLast(MAX_MESSAGES)) }
                ask(usual, understood = true)
                return
            }
        }
        // Boss's bare "yes" as his very next words after an answer ended by offering the question he usually asks next
        // ([com.optionslab.ira.NextAsk]): asked as that question - checked again to be no order and no command, never on a
        // locked phone. Any other words end the offer, and a yes while something waits for his yes or Confirm is never taken
        // as it. Understanding only: nothing learned acts. Not in IraGoldAlgo.
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !understood && !cleaned) {
            val nextAskWaiting = synchronized(actions) { actions.isNotEmpty() } || runCatching { JarvisVoice.askingOpen() }.getOrDefault(true)
            val nextAskQ = runCatching { IraTools.nextAskYes(q, nextAskWaiting, phoneLocked()) }.getOrNull()
            if (nextAskQ != null) {
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, "$TOOK_AS\"$nextAskQ\".")).takeLast(MAX_MESSAGES)) }
                ask(nextAskQ, understood = true)
                return
            }
        }
        // Boss's very next words just after he turned a trade idea down, when they give his reason ("it's too late in the
        // day"): its kind noted ([com.optionslab.ira.TurnDowns]; never his words) and acknowledged - a statement only, never
        // a question, an order or a command. Any other words end the wait and go on as usual. Nothing learned acts.
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !understood && !cleaned) {
            val noted = runCatching { IraTools.turnDownReason(q) }.getOrNull()
            if (noted != null) { _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, noted)).takeLast(MAX_MESSAGES)) }; return }
        }
        // "In short" / "detail mein batao" just after an answer: the last answer said in its first sentence or whole, and
        // that answer's kind noted with the wish ([com.optionslab.ira.TopicLength]; kinds only, never words) - a topic Boss
        // keeps asking one way is then said that way aloud. Words only: never an order or a command, nothing learned acts.
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !understood && !cleaned) {
            val lastSaid = _state.value.messages.lastOrNull { it.fromIra }?.text
            val sized = runCatching { IraTools.lengthWish(q, lastSaid, phoneLocked()) }.getOrNull()
            if (sized != null) { _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, sized)).takeLast(MAX_MESSAGES)) }; return }
        }
        // "No, BankNifty" / "I meant BankNifty" just after a market question that named no index: that question asked again
        // for the index Boss meant, and only that index noted ([com.optionslab.ira.UsualIndex]; never his words) - an index he
        // keeps meaning is then taken when he names none, and said so. A question only: never an order or a command, and
        // never while something waits for his yes or Confirm. Understanding only - nothing learned acts.
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !understood && !cleaned &&
            runCatching { !com.optionslab.ira.Bundle.acts(q) }.getOrDefault(false)) {
            val indexWaiting = synchronized(actions) { actions.isNotEmpty() }
            val meant = runCatching { IraTools.indexCorrection(q, indexWaiting) }.getOrNull()
            if (meant != null) {
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, "$TOOK_AS\"${meant.second}\" - ${meant.first}")).takeLast(MAX_MESSAGES)) }
                ask(meant.second, understood = true)
                return
            }
        }
        // A new question: the model stops polishing the last answer (it stands as shown).
        IraModel.stopWriting()
        // Boss asking anything just after an unasked alert: he followed it up ([com.optionslab.ira.AlertSense]; kinds and minutes only).
        if (com.optionslab.app.BuildConfig.JARVIS) scope.launch { runCatching { IraTools.alertBoss(com.optionslab.ira.AlertSense.Boss.ASKED) } }
        // "What?" / "come again" just after an answer: that answer's kind was unclear to Boss ([com.optionslab.ira.Clarity];
        // kinds only, never words). It only ever makes Jarvis's voice shorter there - never anything that acts.
        if (com.optionslab.app.BuildConfig.JARVIS && !understood && !cleaned) runCatching { IraTools.clarityHeard(q) }
        // Only the very next words after a missed question may teach it (Boss's rephrase).
        if (!understood) runCatching { IraTools.asked(q) }
        // What Boss's corrections taught: misunderstood words read as meant (questions only, never anything that acts).
        val learnedHit = runCatching { com.optionslab.ira.Corrections.match(q, IraTools.learned()) }.getOrNull()
        val learnedAs = learnedHit?.right
        if (learnedHit != null && learnedAs != null && !understood && !lockedAccount(q, learnedAs)) {
            // Used: it stays another 60 days (one unused that long is forgotten).
            scope.launch(Dispatchers.IO) { runCatching { IraTools.usedLearned(learnedHit) } }
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, "$TOOK_AS\"$learnedAs\".")).takeLast(MAX_MESSAGES)) }
            ask(learnedAs, understood = true)
            return
        }
        // A market question naming no index, once Boss has corrected Jarvis to one index often enough
        // ([com.optionslab.ira.UsualIndex]): read for that index and said so ("BankNifty, as you usually mean, Boss") - a
        // question only, a named index always wins, never on a locked phone. Understanding only: nothing learned acts.
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !understood && !cleaned &&
            runCatching { !com.optionslab.ira.Bundle.acts(q) }.getOrDefault(false)) {
            val usualRead = runCatching { IraTools.indexReading(q, phoneLocked()) }.getOrNull()
            if (usualRead != null) {
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, "$TOOK_AS\"${usualRead.second}\" - ${usualRead.first}")).takeLast(MAX_MESSAGES)) }
                ask(usualRead.second, understood = true)
                return
            }
        }
        // "What words have you learned?" / "forget the word X": his learned wordings, listed or dropped - Boss's own words,
        // so only on an unlocked phone, and only as said by him (never from a guess or a learned reading).
        if (!understood) {
            val wordsAsked = runCatching { com.optionslab.ira.Corrections.wordsAsked(q) }.getOrDefault(false)
            val forgetWord = runCatching { com.optionslab.ira.Corrections.forgetWordAsked(q) }.getOrNull()
            if (wordsAsked || forgetWord != null) {
                val said = when {
                    phoneLocked() -> "Unlock the phone for that, Boss."
                    forgetWord != null -> com.optionslab.ira.Corrections.forgot(runCatching { IraTools.forgetWord(forgetWord) }.getOrDefault(emptyList()), forgetWord)
                    else -> com.optionslab.ira.Corrections.words(IraTools.learned(), com.optionslab.app.data.Market.today())
                }
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
                return
            }
            // "What do I usually ask?" / "forget my routine": Boss's routine with Jarvis ([com.optionslab.ira.Routine]) -
            // his own habits, so only on an unlocked phone, and only as said by him.
            val routineAsked = runCatching { com.optionslab.ira.Routine.asked(q) }.getOrDefault(false)
            val forgetRoutine = runCatching { com.optionslab.ira.Routine.forgetAsked(q) }.getOrDefault(false)
            if (routineAsked || forgetRoutine) {
                val said = when {
                    phoneLocked() -> "Unlock the phone for that, Boss."
                    forgetRoutine -> { IraTools.forgetRoutine(); com.optionslab.ira.Routine.FORGOT }
                    else -> runCatching { com.optionslab.ira.Routine.describe(IraTools.routines(), IraTools.routineKept(), com.optionslab.app.data.Market.today()) }
                        .getOrDefault("I couldn't read your routine just now, Boss.")
                }
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
                return
            }
        }
        // A short follow-up ("and BankNifty?", "why?", "what are the levels?") asks again about the last question
        // (questions only), when that was asked in the last five minutes.
        val recent = System.currentTimeMillis() - lastAskAt < FOLLOW_MS
        lastAskAt = System.currentTimeMillis()
        // The same for fillers ("umm"), false starts ("I mean") and two questions in one breath (questions only: words that
        // could act are never split or cleaned, and go on as said). Any part about the account on a locked phone: as said.
        if (!understood && !cleaned && !com.optionslab.ira.Sources.asked(q) &&
            // Asked of his memory as said ("what do you know about me", "what did I tell you"): never read as anything else.
            !runCatching { com.optionslab.ira.AboutBoss.knowAsked(q) || com.optionslab.ira.Memory.recallAsked(q) || com.optionslab.ira.Memory.forgetAsked(q) ||
                com.optionslab.ira.Corrections.wordsAsked(q) || com.optionslab.ira.Corrections.forgetWordAsked(q) != null ||
                com.optionslab.ira.Routine.asked(q) || com.optionslab.ira.Routine.forgetAsked(q) || com.optionslab.ira.PatternCalls.asked(q) || com.optionslab.ira.TrendReads.asked(q) ||
                com.optionslab.ira.Learnings.asked(q) != null || com.optionslab.ira.Learnings.undoAsked(q) ||
                com.optionslab.ira.NewsMoves.asked(q) != null || com.optionslab.ira.PreMarket.asked(q) ||
                com.optionslab.ira.ChainDrift.asked(q) != null || com.optionslab.ira.SinceMorning.asked(q) ||
                com.optionslab.ira.ExpiryPin.asked(q) != null || com.optionslab.ira.ExpiryHour.asked(q) != null || com.optionslab.ira.StraddleDecay.asked(q) != null || com.optionslab.ira.AtmBuy.asked(q) != null || com.optionslab.ira.OtmReach.asked(q) != null || com.optionslab.ira.MarketRecord.asked(q) || com.optionslab.ira.MorningCues.asked(q) != null || com.optionslab.ira.BigMoveRisk.asked(q) || com.optionslab.ira.LiquidityMap.asked(q) != null ||
                com.optionslab.ira.Headroom.asked(q) != null || com.optionslab.ira.ArmFit.asked(q) || com.optionslab.ira.WeakLink.asked(q) || com.optionslab.ira.ArmChange.asked(q) || com.optionslab.ira.PnlGap.asked(q) || com.optionslab.ira.ArmDay.asked(q) != null || com.optionslab.ira.BookDecay.asked(q) || com.optionslab.ira.WhereIWin.asked(q) != null || com.optionslab.ira.TradesADay.asked(q) != null || com.optionslab.ira.AfterLoss.asked(q) != null || com.optionslab.ira.StopNoise.asked(q) || com.optionslab.ira.DayScore.asked(q) || com.optionslab.ira.RequestBook.asked(q) != null || com.optionslab.ira.NetLean.asked(q) || com.optionslab.ira.LiquidityWhyNot.asked(q) != null || com.optionslab.ira.SoloDay.asked(q) != null || com.optionslab.ira.HeroDay.asked(q) != null || com.optionslab.ira.BotTrades.asked(q) != null ||
                com.optionslab.ira.ExpiryEve.asked(q) || com.optionslab.ira.BeforeTomorrow.asked(q) ||
                com.optionslab.ira.SwitchOff.asked(q) != null ||
                com.optionslab.ira.ReminderBook.listAsked(q) || com.optionslab.ira.ReminderBook.cancelOne(q) != null || com.optionslab.ira.Requests.listAsked(q) ||
                com.optionslab.ira.SaidAbout.asked(q) != null || com.optionslab.ira.WeekAhead.asked(q) != null || com.optionslab.ira.WeeklyReview.asked(q) != null || com.optionslab.ira.LiquidityRecord.asked(q) != null || com.optionslab.ira.TomorrowPlan.asked(q) || com.optionslab.ira.OpeningRead.asked(q) ||
                com.optionslab.ira.ZerodhaSession.asked(q) != null || com.optionslab.ira.OrderWhy.asked(q) != null || com.optionslab.ira.Tour.asked(q) || com.optionslab.ira.WhatsNew.asked(q) ||
                com.optionslab.ira.RelayHealth.asked(q) != null || com.optionslab.ira.StreamHealth.asked(q) || com.optionslab.ira.WatchAsk.asked(q) != null ||
                com.optionslab.ira.NeedsTrue.asked(q) ||
                com.optionslab.ira.Clarity.asked(q) != null || com.optionslab.ira.DayClock.asked(q) != null ||
                com.optionslab.ira.GapRecord.asked(q) != null || com.optionslab.ira.RangeBreaks.asked(q) != null ||
                com.optionslab.ira.PriorDay.asked(q) != null || com.optionslab.ira.LastHour.asked(q) != null ||
                com.optionslab.ira.InsideDays.asked(q) != null || com.optionslab.ira.FirstMove.asked(q) != null ||
                com.optionslab.ira.VixNext.asked(q) != null || com.optionslab.ira.SplitDays.asked(q) != null ||
                com.optionslab.ira.RoundCloses.asked(q) != null || com.optionslab.ira.MonthTurns.asked(q) != null ||
                com.optionslab.ira.LunchRange.asked(q) != null || com.optionslab.ira.OpenHighLow.asked(q) != null ||
                com.optionslab.ira.BigCandles.asked(q) != null || com.optionslab.ira.ExtremeCloses.asked(q) != null || com.optionslab.ira.WeekRange.asked(q) != null ||
                com.optionslab.ira.RelativeMove.asked(q) != null || com.optionslab.ira.Comebacks.asked(q) != null || com.optionslab.ira.VixBand.asked(q) != null ||
                com.optionslab.ira.Overnight.asked(q) != null || com.optionslab.ira.DayAfter.asked(q) != null || com.optionslab.ira.OpenReach.asked(q) != null || com.optionslab.ira.MultiDay.asked(q) != null ||
                com.optionslab.ira.MoveTime.asked(q) != null || com.optionslab.ira.GiveBack.asked(q) != null ||
                com.optionslab.ira.Weekdays.asked(q) != null ||
                com.optionslab.ira.WordFit.asked(q) != null ||
                com.optionslab.ira.Causes.asked(q) != null ||
                com.optionslab.ira.AskedAgain.asked(q) || com.optionslab.ira.FigureFirst.asked(q) != null ||
                com.optionslab.ira.WrongThing.asked(q) != null || com.optionslab.ira.WrongThing.objected(q) || com.optionslab.ira.MindChange.asked(q) ||
                com.optionslab.ira.ArmHabits.asked(q) || com.optionslab.ira.MorningSense.asked(q) != null ||
                com.optionslab.ira.HonestStars.asked(q) != null || com.optionslab.ira.TalkHours.asked(q) != null || com.optionslab.ira.MorningAsks.asked(q) != null || com.optionslab.ira.BatteryUse.asked(q) ||
                com.optionslab.ira.TurnDowns.asked(q) != null || com.optionslab.ira.TopicLength.asked(q) != null || com.optionslab.ira.OutlookCheck.asked(q) ||
                com.optionslab.ira.UsualIndex.asked(q) != null || com.optionslab.ira.Nicknames.asked(q) != null || com.optionslab.ira.LeadIndex.asked(q) != null ||
                com.optionslab.ira.LeadPart.asked(q) != null || com.optionslab.ira.NextAsk.asked(q) != null || com.optionslab.ira.MoreAfter.asked(q) != null ||
                com.optionslab.ira.SmallTrades.asked(q) != null || com.optionslab.ira.DayIndex.asked(q) != null || com.optionslab.ira.Conditional.asked(q) || com.optionslab.ira.CheckTimes.asked(q) != null || com.optionslab.ira.CondNeeds.asked(q) != null ||
                com.optionslab.ira.DayCompare.asked(q) != null || com.optionslab.ira.LikeToday.asked(q) }.getOrDefault(false)) {
            val prev = if (recent) _state.value.messages.lastOrNull { !it.fromIra }?.text else null
            val qs = runCatching { com.optionslab.ira.Understand.questions(prev, q) }.getOrNull()
                ?.takeIf { it.isNotEmpty() && it != listOf(q) && it.none { p -> lockedAccount(q, p) } }
            if (qs != null) {
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, "$TOOK_AS\"${qs.joinToString("\" and \"")}\".")).takeLast(MAX_MESSAGES)) }
                // One question (fillers gone, or a follow-up read in full) goes on as Boss's own words, so remembering,
                // "the usual" and his everyday wordings still work; parts of a split stay questions only.
                if (qs.size == 1) ask(qs[0], understood = false, cleaned = true) else qs.forEach { ask(it, understood = true) }
                return
            }
        }
        // A question said with something to do ("..., then exit all"): the question handlers below leave it to the multi-step
        // plan, so the action is never silently dropped ([com.optionslab.ira.Bundle]; review, 5 Oct).
        val bundled = runCatching { com.optionslab.ira.Bundle.acts(q) }.getOrDefault(true)
        val parsed = Ask.parse(q)
        // "Agar Nifty 100 point gire to sab band kar do", "exit all if Nifty falls below 24000" (understanding round 29): an action
        // set to wait for a condition, which Jarvis can't do - never a command, an order or a yes ([com.optionslab.ira.Conditional]).
        // Said so, with the app's own alarm, stop loss and limits in words; nothing is done, nothing about the account is said,
        // and IraGoldAlgo only talks. Before the plan and every question branch, so no part of it is ever acted on alone.
        if (parsed.order == null && parsed.command == null && runCatching { com.optionslab.ira.Conditional.asked(q) }.getOrDefault(false)) {
            // Usefulness round 38: something concrete instead - a price level on one index offers the app's own price alarm
            // there (prepared by the same path as "alert me when Nifty goes below 24000", put as a request and set only on
            // Boss's yes, never alone even with "do it automatically"); a loss amount answers with his daily loss limit and
            // where to change it (his account: unlocked phone only). Never a stop, an exit, an order or the kill switch.
            val condGold = com.optionslab.app.BuildConfig.GOLD || GOLD_ONLY_TALK
            val condInstead = if (condGold) null else runCatching { com.optionslab.ira.Conditional.instead(q) }.getOrNull()
            // Learning (round 32): its kind and minute kept, never the words ([com.optionslab.ira.CondNeeds]) - after enough
            // of them the 15:35 wrap-up names the app's own tool for that need once. Jarvis only; nothing is set or done.
            if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !GOLD_ONLY_TALK)
                scope.launch(Dispatchers.IO) { runCatching { IraTools.condNeedsNote(q) } }
            val condAlarm = condInstead?.alarm
            if (condAlarm != null) {
                _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
                scope.launch {
                    val (alarmWhat, alarmAct) = runCatching { IraActions.prepare(condAlarm) }.getOrElse { "" to null }
                    if (alarmAct == null) { reply(com.optionslab.ira.Conditional.SAY); return@launch }
                    reply(com.optionslab.ira.Conditional.alarmSay(condAlarm), whole = true)
                    offer(alarmWhat, "Boss, set a price alarm?", com.optionslab.ira.Conditional.alarmAsk(condAlarm),
                        suspend { IraActions.verified(condAlarm.kind, alarmAct()) }, alwaysAsk = true)
                }
                return
            }
            val condSaid = if (condGold) GOLD_TALK_ONLY else condInstead?.loss?.let { lossSaid ->
                val condLocked = phoneLocked()
                val condSet = if (condLocked) null else runCatching { com.optionslab.app.data.AppSettings.load() }.getOrNull()
                com.optionslab.ira.Conditional.lossSay(lossSaid, condSet?.let { s -> if (s.live) s.guardDailyLoss else s.guardPaperDailyLoss },
                    if (condSet?.live == true) "Zerodha" else "Paper", condLocked)
            } ?: com.optionslab.ira.Conditional.SAY
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, condSaid)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "Help me journal today": today's journal drafted from the facts, then a few questions by voice. It holds the
        // account, so the phone must be unlocked. Words only; the answers are kept, never acted on. (Like every question
        // below, never when an order or a command is in the words.)
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.DayJournal.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return }
            scope.launch(Dispatchers.IO) { reply(runCatching { IraDayJournal.help() }.getOrElse { "I could not draft your journal just now, Boss." }) }
            return
        }
        // The kinds of question Boss asks (kind keys only, no words): set against those he marks wrong ([SelfDoubt]).
        scope.launch { runCatching { IraTools.countAsked(q) } }
        // "Open requests", "kya pending hai", "what's waiting for my approval" (understanding round 25): what waits for Boss's
        // yes, read from the Requests panel's own list ([com.optionslab.ira.Requests.listSay]) - how many only on a locked
        // phone, none in IraGoldAlgo. It reads only: approving and declining stay [confirm] and [cancelAction].
        if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.Requests.listAsked(q) }.getOrDefault(false)) {
            val reqSaid = runCatching { com.optionslab.ira.Requests.listSay(requestsOf(_state.value), System.currentTimeMillis(), phoneLocked(),
                GOLD_ONLY_TALK || com.optionslab.app.BuildConfig.GOLD) }.getOrDefault("I could not read the requests just now, Boss.")
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, reqSaid)).takeLast(MAX_MESSAGES)) }
            return
        }
        if (askedOfHisWays(q, parsed, bundled, understood)) return
        if (askedOfRecords(q, parsed, bundled, understood)) return
        if (askedOfJarvis(q, parsed, bundled, understood)) return
        // IraGoldAlgo: Jarvis talks only - no order, no command (no broker there; its gold arms trade on paper by their rules).
        if (com.optionslab.app.BuildConfig.GOLD && (parsed.order != null || parsed.command != null || Topic.ORDER in parsed.topics || Topic.COMMAND in parsed.topics)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, GOLD_TALK_ONLY)).takeLast(MAX_MESSAGES)) }
            return
        }
        if (askedOfChain(q, parsed, bundled, understood)) return
        if (askedOfPastDays(q, parsed, bundled, understood)) return
        if (askedToReason(q, parsed, bundled, understood)) return
        // A request in steps ("stop all strategies, then kill switch on and switch to paper"): one plan, one Confirm, done
        // in order through the same gates as each step alone. Never with a time (those go below) and never an order.
        if (com.optionslab.app.BuildConfig.JARVIS && !GOLD_ONLY_TALK && !com.optionslab.app.BuildConfig.GOLD &&
            !runCatching { com.optionslab.ira.Later.mentionsTime(q) }.getOrDefault(true)) {
            // Only steps that lower risk (and questions): anything else is asked alone, where its own gates apply.
            val steps = runCatching { com.optionslab.ira.Plan.steps(q) { s -> Ask.parse(s).let { it.order == null && it.command?.kind in com.optionslab.ira.Plan.ALLOWED } ||
                com.optionslab.ira.Toolbox.isRead(s) } }.getOrNull()
            if (steps != null) { planAsked(q, steps); return }
            // In steps, but with an action a plan may not hold: refused whole (never just its first step done).
            val any = runCatching { com.optionslab.ira.Plan.steps(q) { s -> Ask.parse(s).let { it.order == null && it.command != null } || com.optionslab.ira.Toolbox.isRead(s) } }.getOrNull()
            if (any != null) { _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, com.optionslab.ira.Plan.ONLY_LOWERING)).takeLast(MAX_MESSAGES)) }; return }
        }
        // "How is Nifty, then close it": a close by a pronoun with no position of Boss's named before it - asked which,
        // nothing done (never guessed, never dropped silently; routing round 12). The same when one was named but no plan
        // was formed ("my put is losing, close it"): asked which. IraGoldAlgo only talks.
        if (bundled && parsed.order == null && parsed.command == null && runCatching { com.optionslab.ira.Plan.pronounUnclear(q) || com.optionslab.ira.Plan.pronounAfter(q) }.getOrDefault(false)) {
            val said = if (com.optionslab.app.BuildConfig.GOLD || GOLD_ONLY_TALK) GOLD_TALK_ONLY else com.optionslab.ira.Plan.WHICH_POSITION
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // Boss's reminders one at a time (usefulness round 27): "what reminders do I have" lists each with its time;
        // "cancel the 14:30 reminder" / "delete the reminder about Nifty" finds that one and asks first - only it is
        // dropped, on Confirm (several alike, or none: named, nothing dropped). A reminder only speaks: nothing here trades.
        // Said with something else to do ("cancel the 3 pm reminder and close my nifty put"): none of the reminder answers
        // below takes it (the close would be silently dropped) - said so, nothing done, no reminder cancelled (review, 5 Oct).
        // (The whole sentence's order or command is not the test: "delete the reminder to buy nifty" reads as an order from
        // the reminder's own words. [Bundle.reminderAndMore] reads each part, the reminder's clause left out.)
        val rbMore = com.optionslab.app.BuildConfig.JARVIS && runCatching { com.optionslab.ira.Bundle.reminderAndMore(q) }.getOrDefault(false)
        if (rbMore) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, com.optionslab.ira.Bundle.REMINDER_AND_MORE)).takeLast(MAX_MESSAGES)) }
            return
        }
        if (com.optionslab.app.BuildConfig.JARVIS && runCatching { com.optionslab.ira.ReminderBook.listAsked(q) }.getOrDefault(false)) {
            val rbList = runCatching { com.optionslab.ira.ReminderBook.list(IraLater.kept(), com.optionslab.app.data.Market.now().toLocalDateTime()) }
                .getOrDefault("I could not reach the reminders just now, Boss.")
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, rbList)).takeLast(MAX_MESSAGES)) }
            return
        }
        val rbPick = if (com.optionslab.app.BuildConfig.JARVIS) runCatching { com.optionslab.ira.ReminderBook.cancelOne(q) }.getOrNull() else null
        if (rbPick != null) {
            val rbApp = app
            val rbNow = com.optionslab.app.data.Market.now().toLocalDateTime()
            val rbAll = runCatching { IraLater.kept() }.getOrNull()
            val rbFound = rbAll?.let { all -> com.optionslab.ira.ReminderBook.matches(all, rbPick) }
            if (rbApp == null || rbAll == null || rbFound == null || rbFound.size != 1) {
                val rbSaid = if (rbAll == null || rbFound == null || rbApp == null) "I could not reach the reminders just now, Boss."
                    else com.optionslab.ira.ReminderBook.notOne(rbFound, rbAll, rbNow)
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, rbSaid)).takeLast(MAX_MESSAGES)) }
                return
            }
            val rbOne = rbFound[0]
            val rbWhat = com.optionslab.ira.ReminderBook.confirm(rbOne, rbNow)
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            pend(rbWhat, suspend {
                if (IraLater.dropReminder(rbApp, rbOne)) com.optionslab.ira.ReminderBook.cancelled(rbOne, com.optionslab.app.data.Market.now().toLocalDateTime())
                else "That reminder is gone already, Boss - nothing to cancel."
            }, "Tap Confirm to $rbWhat.")
            return
        }
        // Boss's own reminder ("remind me at 3 pm to check Nifty"): only said at its time, never run (Boss, 4 Oct).
        // "Cancel my reminders": the reminders only (timed commands are cancelled with "cancel everything set for later").
        // Asked first, like one (usefulness round 28): each is named, and only those named are dropped on Confirm.
        if (com.optionslab.app.BuildConfig.JARVIS && runCatching { com.optionslab.ira.Reminder.cancelAsked(q) }.getOrDefault(false)) {
            val raApp = app
            val raAll = runCatching { IraLater.kept() }.getOrNull()
            if (raApp == null || raAll == null || raAll.isEmpty()) {
                val raSaid = if (raApp == null || raAll == null) "I could not reach the reminders just now, Boss." else "You have no reminders set, Boss."
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, raSaid)).takeLast(MAX_MESSAGES)) }
                return
            }
            val raWhat = com.optionslab.ira.ReminderBook.confirmAll(raAll, com.optionslab.app.data.Market.now().toLocalDateTime())
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            pend(raWhat, suspend {
                com.optionslab.ira.ReminderBook.cancelledAll(IraLater.dropReminders(raApp, raAll))
            }, "Tap Confirm to $raWhat.")
            return
        }
        // ("Remind me what's set for later" is the list below, not a new reminder; "remind me I get greedy after a win",
        // with no time, is something Boss tells about himself - kept below, not a reminder.)
        if (com.optionslab.app.BuildConfig.JARVIS && runCatching { com.optionslab.ira.Reminder.asked(q) }.getOrDefault(false) && !FOR_LATER.containsMatchIn(q) &&
            (understood || runCatching { com.optionslab.ira.AboutBoss.fact(q) == null }.getOrDefault(true))) {
            val now = java.time.LocalDateTime.now(IST)
            val r = runCatching { com.optionslab.ira.Reminder.parse(q, now) }.getOrNull()
            val c = app
            val said = if (r == null || c == null) "Boss, tell me when, like \"remind me at 3 pm to check Nifty\" or \"in 20 minutes\"."
                else if (runCatching { com.optionslab.ira.Reminder.daily(q) }.getOrDefault(false)) {
                    // The first one on a trading day too (set on a Saturday, it starts Monday - review, 4 Oct).
                    var first = r.at
                    var guard = 0
                    while (guard++ < 14 && !runCatching { com.optionslab.app.data.Market.isTradingDay(first.toLocalDate()) }.getOrDefault(true)) first = first.plusDays(1)
                    IraLater.remind(c, r.rest, first, daily = true)
                    "Done, Boss: I'll remind you every trading day at ${first.toLocalTime()}, starting ${com.optionslab.ira.Later.say(first, now)}: ${r.rest}."
                }
                else { IraLater.remind(c, r.rest, r.at); "Done, Boss: I'll remind you ${com.optionslab.ira.Later.say(r.at, now)}: ${r.rest}." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "Run a self check": each part Jarvis needs, working or not, from what the app knows now (no account figures).
        if (com.optionslab.app.BuildConfig.JARVIS && runCatching { com.optionslab.ira.SelfCheck.asked(q) }.getOrDefault(false)) {
            val c = app
            val voiceOn = JarvisVoice.listenOn
            val mic = c?.let { androidx.core.content.ContextCompat.checkSelfPermission(it, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED }
            val relay = com.optionslab.app.data.Relay
            val parts = listOf(
                "Listening" to (if (voiceOn) JarvisVoice.state.value.problem == null else null),
                "Microphone permission" to (if (voiceOn) mic else null),
                "Spoken replies" to (if (JarvisVoice.muted) null else true),
                "AI model (${IraModel.choice.name})" to (if (IraModel.state.value.status == IraModel.Status.ABSENT) null else IraModel.state.value.status == IraModel.Status.READY),
                "Live prices" to (if (com.optionslab.app.data.Market.isOpen()) _state.value.liveMissing.size < 3 else null),
                "Relay server" to (if (relay.enabled && relay.host != null) relay.connected.takeIf { it } ?: (if (com.optionslab.app.data.Market.isOpen()) false else null) else null),
                "Zerodha session" to (if (com.optionslab.app.data.Broker.configured) com.optionslab.app.data.Broker.loggedIn else null),
            )
            val said = com.optionslab.ira.SelfCheck.lines(parts).joinToString(" ")
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "What did I miss?": what Jarvis said on his own since Boss last asked (alerts, notes) - may name the account, so
        // the phone must be unlocked.
        if (com.optionslab.app.BuildConfig.JARVIS && runCatching { com.optionslab.ira.Reminder.missedAsked(q) }.getOrDefault(false)) {
            val notes = com.optionslab.ira.Reminder.sinceLastAsked(_state.value.messages.map { m ->
                com.optionslab.ira.Reminder.Said(!m.fromIra, m.id in unaskedIds, m.text) })
            val said = when {
                phoneLocked() -> "Unlock the phone for that, Boss."
                notes.isEmpty() -> "Nothing new since you last asked, Boss."
                else -> "Since you last asked, Boss: " + notes.joinToString(" | ") { com.optionslab.ira.Wake.spoken(it, 2) }
            }
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "What did you hear?": the recognizer's words before this question (redacted), to check the ears.
        if (com.optionslab.app.BuildConfig.JARVIS && runCatching { com.optionslab.ira.Reminder.heardAsked(q) }.getOrDefault(false)) {
            // Asked by voice, the recognizer's last words are this question: the words before it are meant.
            val last = JarvisVoice.heardText
            val prev = if (last != null && runCatching { com.optionslab.ira.Reminder.heardAsked(last) }.getOrDefault(false)) JarvisVoice.heardBefore else last
            val said = when {
                phoneLocked() -> "Unlock the phone for that, Boss."
                prev == null -> "I haven't heard anything from you yet, Boss."
                else -> "I heard: \"$prev\", Boss."
            }
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "How fast are you?": the spoken answers' waits (times only, no words), and the model in use.
        if (com.optionslab.app.BuildConfig.JARVIS && runCatching { com.optionslab.ira.Latency.asked(q) }.getOrDefault(false)) {
            val said = com.optionslab.ira.Latency.spoken(JarvisVoice.latencies, "${IraModel.choice.name} (${IraModel.choice.about})", IraModel.choice == IraModel.FASTEST)
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "How did you do today?": what Jarvis heard, misunderstood and could not do today (no account figures).
        if (com.optionslab.app.BuildConfig.JARVIS && runCatching { com.optionslab.ira.Reminder.usageAsked(q) }.getOrDefault(false)) {
            val said = com.optionslab.ira.Usage.line(IraTools.usageToday())?.let { "Today, Boss: $it" } ?: "Nothing asked of me yet today, Boss."
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "Which AI model are you using?": the choice and its state (Boss, 4 Oct: switching to the fastest model).
        if (com.optionslab.app.BuildConfig.JARVIS && runCatching { com.optionslab.ira.Reminder.modelAsked(q) }.getOrDefault(false)) {
            val st = IraModel.state.value
            val said = "I'm set to ${IraModel.choice.name} (${IraModel.choice.about}), Boss: " + when (st.status) {
                IraModel.Status.READY -> if (st.loaded) "on the phone and loaded now." else "on the phone, loaded when I need it."
                IraModel.Status.DOWNLOADING -> "still downloading."
                IraModel.Status.VERIFYING -> "being checked after the download."
                IraModel.Status.ABSENT -> "not downloaded yet (Settings, Voice and AI model)."
                IraModel.Status.FAILED -> "its download failed; try again in Settings, Voice and AI model."
                IraModel.Status.UNSUPPORTED -> "this phone cannot run it."
            } + " Prices, P&L and actions never come from the model - only from the app's own rules."
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // The time and the date, from the phone's clock: at once, no model.
        runCatching { com.optionslab.ira.Reminder.clock(q, java.time.LocalDateTime.now(IST)) }.getOrNull()?.let { said ->
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "How far is Nifty from 25000?": points and percent from the last price.
        if (parsed.command == null && parsed.order == null) runCatching { com.optionslab.ira.Distance.asked(q) }.getOrNull()?.let { a ->
            val p = _state.value.snaps[a.market]?.price
            val said = if (p == null || p <= 0) "I have no ${a.market.label} price yet, Boss." else
                com.optionslab.ira.Distance.say(a, p).let { t -> offlineNote()?.let { "$it $t" } ?: aged(t, listOf(a.market), setOf(Topic.LEVELS)).text }
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // An option's price, the ATM strike, the lot size, the time left (read from the live chain; never an order).
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && parsed.command == null && parsed.order == null)
            runCatching { com.optionslab.ira.OptionFacts.asked(q) }.getOrNull()?.let { a ->
                _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
                if (a is com.optionslab.ira.OptionFacts.Asked.TimeLeft) {
                    val mk = com.optionslab.app.data.Market
                    val next = runCatching { var d = mk.today().plusDays(1); var g = 0; while (g++ < 14 && !mk.isTradingDay(d)) d = d.plusDays(1)
                        d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH)) }.getOrNull()
                    reply(com.optionslab.ira.OptionFacts.timeLeft(mk.minuteNow(), tradingDay = runCatching { mk.isTradingDay(mk.today()) }.getOrDefault(true), nextDay = next)); return
                }
                val m = when (a) { is com.optionslab.ira.OptionFacts.Asked.Quote -> a.market; is com.optionslab.ira.OptionFacts.Asked.Atm -> a.market
                    is com.optionslab.ira.OptionFacts.Asked.LotSize -> a.market; else -> IraMarket.NIFTY }
                scope.launch {
                    val said = runCatching {
                        val c = kotlinx.coroutines.withTimeoutOrNull(20_000) { IraAccount.chain(m.name) } ?: return@runCatching "I could not read the ${m.label} option chain just now, Boss."
                        when (a) {
                            is com.optionslab.ira.OptionFacts.Asked.Quote -> {
                                val row = c.rows.firstOrNull { kotlin.math.abs(it.strike - a.strike) < 0.5 }
                                val leg = row?.let { if (a.right == "CE") it.ce else it.pe }
                                    ?: return@runCatching "${m.label} ${a.strike} ${a.right} is not in the strikes I read (near the money, ${c.expiry}), Boss."
                                com.optionslab.ira.OptionFacts.quote(m, a.strike, a.right, leg.ltp, leg.bid, leg.ask, leg.oi, c.lotSize) + " Expiry ${c.expiry}."
                            }
                            is com.optionslab.ira.OptionFacts.Asked.Atm -> {
                                val row = c.rows.minByOrNull { kotlin.math.abs(it.strike - c.spot) } ?: return@runCatching "No strikes came back for ${m.label}, Boss."
                                "${m.label} is at %,.2f, so the at-the-money strike is %.0f (CE %s, PE %s), expiry ${c.expiry}.".format(java.util.Locale.ENGLISH,
                                    c.spot, row.strike, row.ce?.ltp?.let { "Rs %,.2f".format(java.util.Locale.ENGLISH, it) } ?: "-",
                                    row.pe?.ltp?.let { "Rs %,.2f".format(java.util.Locale.ENGLISH, it) } ?: "-")
                            }
                            else -> "One ${m.label} lot is ${c.lotSize} units now (the exchange revises it from time to time), Boss."
                        }
                    }.getOrElse { "I could not read that just now, Boss." }
                    reply(said)
                }
                return
            }
        // "How many lots of Nifty can I buy with 20,000?": the at-the-money premium times the lot size - arithmetic only.
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && parsed.command == null && parsed.order == null)
            runCatching { com.optionslab.ira.Sizing.asked(q) }.getOrNull()?.let { a ->
                _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
                val m = a.market ?: IraMarket.NIFTY
                if (m !in listOf(IraMarket.NIFTY, IraMarket.BANKNIFTY, IraMarket.FINNIFTY)) {
                    reply("Boss, I can size Nifty, BankNifty and FinNifty options; ${m.label} options are not in my chain data."); return }
                scope.launch {
                    val said = runCatching {
                        val c = kotlinx.coroutines.withTimeoutOrNull(20_000) { IraAccount.chain(m.name) } ?: return@runCatching "I could not read the ${m.label} option chain just now, Boss."
                        val right = a.right ?: "CE"
                        val row = c.rows.filter { (if (right == "CE") it.ce else it.pe) != null }.minByOrNull { kotlin.math.abs(it.strike - c.spot) }
                            ?: return@runCatching "I could not find the ${m.label} at-the-money option just now, Boss."
                        val leg = if (right == "CE") row.ce!! else row.pe!!
                        com.optionslab.ira.Sizing.say(a, m, right, row.strike, leg.ltp, c.lotSize)
                    }.getOrElse { "I could not work that out just now, Boss." }
                    reply(said)
                }
                return
            }
        // "Wrap up my day" / "aaj ka summary" at any hour: the 15:35 wrap-up's words so far (the day's P&L is Boss's own,
        // so the phone must be unlocked).
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && parsed.command == null && parsed.order == null &&
            runCatching { com.optionslab.ira.DaySummary.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return }
            scope.launch {
                // The scorecard saves its points and lapses waiting answers: only after the close (review, 4 Oct).
                val card = if (com.optionslab.app.data.Market.minuteNow() >= 15 * 60 + 30) runCatching { IraNewsTrades.scorecard().firstOrNull() }.getOrNull() else null
                reply(runCatching { IraCoach.wrapUp(card, review = false) }.getOrElse { "I could not put the day together just now, Boss." })
            }
            return
        }
        // "When is the next expiry?": the next expiry of each index (or the one named), from the loaded contracts.
        // (Not when a command hides behind a time - "at 3 pm stop the ORB arm, it is expiry day" is the timed command below.)
        if (parsed.command == null && parsed.order == null && runCatching { com.optionslab.ira.MarketDays.expiryAsked(q) }.getOrDefault(false) &&
            runCatching { com.optionslab.ira.Later.split(q, java.time.LocalDateTime.now(IST))?.rest?.let { Ask.parse(it) } }.getOrNull()
                .let { r -> r?.command == null && r?.order == null }) {
            val today = com.optionslab.app.data.Market.today()
            val named = parsed.markets.filter { it in listOf(IraMarket.NIFTY, IraMarket.BANKNIFTY, IraMarket.FINNIFTY) }
            val ms = named.ifEmpty { listOf(IraMarket.NIFTY, IraMarket.BANKNIFTY, IraMarket.FINNIFTY) }
            val said = com.optionslab.ira.MarketDays.expirySay(today, ms.map { m -> m to runCatching { com.optionslab.app.data.Market.upcomingExpiries(m.name).firstOrNull() }.getOrNull() })
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "Is tomorrow a holiday?", "is the market open on Friday?", "next holiday": a short answer from the exchange
        // calendar instead of the whole account status.
        if (parsed.command == null && parsed.order == null) runCatching {
            val today = com.optionslab.app.data.Market.today()
            // Today on a trading day is the live status (open now, or closed for the day), answered below.
            com.optionslab.ira.MarketDays.asked(q, today)?.takeIf { a -> !(a is com.optionslab.ira.MarketDays.Asked.Day && a.date == today &&
                com.optionslab.app.data.Market.isTradingDay(today)) }?.let { a ->
                val book = com.optionslab.app.data.Holidays.book()
                val said = com.optionslab.ira.MarketDays.say(a, today,
                    { d -> if (book.holiday(d)) book.upcoming(d).firstOrNull { it.first == d }?.second ?: "a market holiday" else null },
                    book.upcoming(today.plusDays(1)).firstOrNull { it.first.dayOfWeek.value <= 5 },
                    { d -> d.dayOfWeek.value >= 6 && com.optionslab.app.data.Market.isTradingDay(d) })
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
                return
            }
        }
        // "What's the plan for tomorrow?": the indices' outlook and the coming days' events (words only, no command).
        // Not when an index is named (its own outlook answers that) or a command hides behind the time ("get ready to
        // start the arms tomorrow at 9:20" is the timed command below).
        val tomorrowRest = runCatching { com.optionslab.ira.Later.split(q, java.time.LocalDateTime.now(IST))?.rest?.let { Ask.parse(it) } }.getOrNull()
        if (com.optionslab.app.BuildConfig.JARVIS && parsed.command == null && parsed.order == null && parsed.markets.isEmpty() &&
            tomorrowRest?.command == null && tomorrowRest?.order == null && runCatching { com.optionslab.ira.Reminder.tomorrow(q) }.getOrDefault(false)) {
            val today = com.optionslab.app.data.Market.today()
            val ev = runCatching { IraEvents.upcoming(3).take(3).map { com.optionslab.ira.Events.line(it, today).removeSuffix(".") } }.getOrDefault(emptyList())
            val out = morningOutlook()
            val said = buildString {
                append("For the next session, Boss: ")
                append(if (out.isEmpty()) "I have no outlook yet (the price history is still loading)." else out.joinToString(" "))
                if (ev.isNotEmpty()) append(" Coming up: " + ev.joinToString("; ") + ".")
                append(" I'll give you the full plan in the morning check.")
            }
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // A command for a later time ("start all the arms tomorrow at 9am"): set only once confirmed, run by an alarm then.
        // A request that names a time is never done now: set for that time (allowed kinds, confirmed), or refused - an
        // order is never placed for later, and a time already passed or missing is asked again (review, 3 Oct).
        if (com.optionslab.app.BuildConfig.JARVIS) {
            val w = runCatching { com.optionslab.ira.Later.split(q, java.time.LocalDateTime.now(IST)) }.getOrNull()
            val rest = w?.let { Ask.parse(it.rest) }
            val timed = w != null || runCatching { com.optionslab.ira.Later.mentionsTime(q) }.getOrDefault(false)
            if (timed && (parsed.command != null || parsed.order != null || rest?.command != null || rest?.order != null)) {
                val c = rest?.command
                val said = when {
                    com.optionslab.app.BuildConfig.GOLD -> GOLD_TALK_ONLY
                    parsed.order != null || rest?.order != null -> "I don't place orders for later, Boss - nothing was placed. Ask me for the order when you want it."
                    w == null || c == null -> "Boss, give me a time still ahead, like \"tomorrow at 9:20\" or \"in 30 minutes\" - nothing was done."
                    else -> null
                }
                if (said != null) _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
                else laterAsked(q, c!!, w!!)
                return
            }
        }
        // "What have you set for later?" / "cancel everything set for later"
        // (In IraGoldAlgo too: only reminders are kept there.)
        if (com.optionslab.app.BuildConfig.JARVIS && FOR_LATER.containsMatchIn(q)) {
            // Cancelling asks first (review, 5 Oct: it was all dropped at once): each reminder and timed command named,
            // and only those named are dropped on Confirm.
            if (Regex("(?i)\\b(cancel|clear|remove|delete|drop)\\b").containsMatchIn(q)) {
                val flApp = app
                val flNow = com.optionslab.app.data.Market.now().toLocalDateTime()
                val flRems = runCatching { IraLater.kept() }.getOrNull()
                val flCmds = runCatching { IraLater.all() }.getOrNull()
                val flWhat = if (flRems == null || flCmds == null) null
                    else com.optionslab.ira.Later.confirmClear(flRems, flCmds.map { it.text to java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.at), IST) }, flNow)
                if (flApp == null || flRems == null || flCmds == null || flWhat == null) {
                    val flSaid = if (flApp == null || flRems == null || flCmds == null) "I could not reach what is set for later just now, Boss - nothing was cancelled."
                        else "Nothing is set for later, Boss."
                    _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, flSaid)).takeLast(MAX_MESSAGES)) }
                    return
                }
                val flIds = flCmds.map { it.id }.toSet()
                _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
                pend(flWhat, suspend {
                    val flGone = IraLater.dropLater(flApp, flRems, flIds)
                    com.optionslab.ira.Later.clearedSaid(flGone.first, flGone.second)
                }, "Tap Confirm to $flWhat.")
                return
            }
            val said = IraLater.say()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "Monday prediction for BankNifty", "Nifty outlook next week": no price guessed - that index's trend, the range
        // VIX prices in, and its pivots, for the hour, day or week asked about (Boss, 3 Oct).
        if (parsed.order == null && parsed.command == null && com.optionslab.ira.Outlook.asked(q) && !Regex("(?i)\\b(my|mine|our)\\b").containsMatchIn(q)) {
            val st = _state.value
            val mk = parsed.markets.firstOrNull { it != IraMarket.VIX && it != IraMarket.GOLD }
                ?: if (parsed.markets.isNotEmpty()) { _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, "I give that outlook for the indices only, Boss (Nifty, BankNifty, FinNifty, Sensex) - not for ${parsed.markets.first().label}.")).takeLast(MAX_MESSAGES)) }; return }
                else IraMarket.NIFTY
            val said = runCatching {
                com.optionslab.ira.Outlook.say(mk, histories[mk]?.bars.orEmpty(), st.snaps[IraMarket.VIX]?.price ?: 0.0, st.snaps[mk]?.trading == true, q)
            }.getOrNull() ?: "I don't have enough of ${mk.label}'s recent days on the phone yet to say, Boss. I don't predict prices anyway; ask me again once its prices have loaded."
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "What's the main news today?", "any news on banks?", "what news moved the market today?": the news desk
        // ([com.optionslab.ira.NewsDesk]) - the same event from different outlets as one story with its source count, tagged
        // by what it touches; "moved" only as timing against today's sharp moves, never a cause. Headlines and market data
        // only (fine on a locked phone); facts and sources, never advice or a forecast. Not in the GOLD build (no news read there).
        val deskAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.NewsDesk.asked(q) }.getOrNull() else null
        // Battery (round 6): a news question today keeps the background news at its quiet pace (else slower, screen off and
        // nothing held: [com.optionslab.ira.WordsPace.newsDueUnasked]).
        if (Topic.NEWS in parsed.topics || deskAsk != null) noteNewsAsked()
        // A news question with no recent headlines on the phone: the feeds are read first (8 seconds at most), then answered.
        if ((Topic.NEWS in parsed.topics || deskAsk != null) && testHistories == null && System.currentTimeMillis() - newsCheckedAt > 3 * 60_000 && online() &&
            _state.value.newsAt?.isBefore(Instant.now().minusSeconds(NEWS_EVERY_MINUTES * 60)) != false) {
            newsCheckedAt = System.currentTimeMillis()
            // Asked again on the questions' lane ([askLane]), in turn with the others; the feeds read off it (IO), not holding it.
            scope.launch(askLane) { withContext(Dispatchers.IO) { kotlinx.coroutines.withTimeoutOrNull(8_000) { runCatching { freshNews() } } }; ask(text, understood) }
            return
        }
        if (deskAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.NewsDesk.market(parsed.markets)
                val bars = com.optionslab.ira.SharpMove.INDICES.associateWith { histories[it]?.bars.orEmpty() }
                val desk = com.optionslab.ira.NewsDesk.answer(deskAsk, _state.value.news, Instant.now(), IST, mk, bars)
                // Beside a story on RBI, the Fed, inflation...: how Nifty moved after such headlines on this phone (timing only).
                val record = if (_state.value.news.isEmpty()) null else runCatching {
                    val usual = com.optionslab.ira.NewsMoves.usual(IraMarket.NIFTY, histories[IraMarket.NIFTY]?.bars.orEmpty())
                    com.optionslab.ira.NewsMoves.forDesk(deskAsk, _state.value.news, IraTools.newsMoves(), Instant.now(), IST, usual)
                }.getOrNull()
                if (record == null) desk else "$desk $record"
            }.getOrElse { "I could not read the headlines just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return
        }
        // A rescued question was counted when it was first heard (review, 5 Oct: it counted twice).
        if (!rescued) IraTools.count("heard")
        // Just after a missed question or "that was wrong" (a minute at most), a question understood may be what was
        // meant: put to Boss ("Shall I take ... to mean ...?"), kept only on his yes - on an unlocked phone, never in
        // IraGoldAlgo, and never anything that acts (checked when proposed, again on the yes, and again when used).
        if (parsed.command == null && Topic.OFF_TOPIC !in parsed.topics) runCatching { IraTools.proposal(q) }.getOrNull()
            ?.takeIf { !understood && !phoneLocked() && !com.optionslab.app.BuildConfig.GOLD }?.let { l -> offerWording(l) }
        // Small talk ("how are you", "thanks", "who are you"): answered at once, in different words each time. Only words
        // that neither order nor command anything.
        if (parsed.command == null && parsed.order == null && Topic.COMMAND !in parsed.topics && Topic.ORDER !in parsed.topics)
            com.optionslab.ira.Chat.smallTalk(q, chatTurn.getAndIncrement())?.let { said ->
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
                return
            }
        // "Remember that ...", "what did I tell you?", "forget what I told you": Boss's own words, kept - never acted on.
        // And what Boss tells about himself ("I don't trade on Fridays", "remind me I get greedy after a win"), "what do you
        // know about me?" and "forget that (...)": kept with his notes, brought up when they matter - words only.
        if (!understood) {
            val keep = com.optionslab.ira.Memory.toKeep(q)?.let { com.optionslab.ira.Secrets.redact(it) }
            val fact = if (keep != null || parsed.order != null || parsed.command != null) null
                else runCatching { com.optionslab.ira.AboutBoss.fact(q) }.getOrNull()?.let { com.optionslab.ira.Secrets.redact(it) }
            val know = runCatching { com.optionslab.ira.AboutBoss.knowAsked(q) }.getOrDefault(false)
            val forgetOne = if (parsed.order != null || parsed.command != null) null else runCatching { com.optionslab.ira.AboutBoss.forgetAsked(q) }.getOrNull()
            val asks = keep != null || fact != null || know || forgetOne != null || com.optionslab.ira.Memory.recallAsked(q) || com.optionslab.ira.Memory.forgetAsked(q)
            val said = when {
                !asks -> null
                // Notes are Boss's: not kept, read or cleared on a locked phone.
                phoneLocked() -> "Unlock the phone for that, Boss."
                keep != null -> { scope.launch(Dispatchers.IO) { runCatching { IraTools.remember(keep) } }; "Noted, Boss: \"$keep\"." + com.optionslab.ira.BossRules.saidBack(keep) + " Ask \"what did I tell you?\" any time." }
                fact != null -> { scope.launch(Dispatchers.IO) { runCatching { IraTools.remember(fact) } }; com.optionslab.ira.AboutBoss.noted(fact) }
                know -> com.optionslab.ira.AboutBoss.lines(IraTools.memory())
                // A bare "forget that" only right after a "Noted" (else it is not plain which note is meant).
                forgetOne != null && forgetOne.last && _state.value.messages.lastOrNull { it.fromIra }?.text?.startsWith("Noted") != true ->
                    "Forget what, Boss? Name it, like \"forget that I don't trade on Fridays\", or ask \"what do you know about me?\"."
                forgetOne != null -> com.optionslab.ira.AboutBoss.forgot(runCatching { IraTools.forgetOne(forgetOne) }.getOrNull(), forgetOne)
                com.optionslab.ira.Memory.recallAsked(q) -> com.optionslab.ira.Memory.lines(IraTools.memory())
                else -> { scope.launch(Dispatchers.IO) { IraTools.forgetMemory() }; "Done, Boss: I've forgotten what you asked me to remember." }
            }
            if (said != null) {
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
                return
            }
        }
        // "Do it automatically" / "ask me before stopping": what Jarvis thinks should be stopped or parked (4 Oct).
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.AutoStop.read(q) }.getOrNull()?.let { on ->
            // Boss's own choice: typed or said by name (not a reading the model guessed), on an unlocked phone; asking
            // again ("ask me before stopping") is always taken.
            if (on && (understood || phoneLocked())) {
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, "Unlock the phone and say it to me directly, Boss: I don't take that from a guess.")).takeLast(MAX_MESSAGES)) }
                return
            }
            autoStop = on
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, com.optionslab.ira.AutoStop.said(on))).takeLast(MAX_MESSAGES)) }
            IraActivity.add(if (on) "Boss chose: stops and parking done automatically." else "Boss chose: asked before stopping.")
            return
        }
        // Goals over days (part 6): "goal: keep my weekly loss under 5000", "what are my goals", "clear my goals".
        // (A day's target alone stays the journal's "set my day target": a goal here names a goal, a week or a month.)
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD) {
            val g = runCatching { com.optionslab.ira.Goals.read(q) }.getOrNull()?.takeIf { parsed.order == null && parsed.command == null }?.takeIf { Regex("(?i)\\bgoal").containsMatchIn(q) || it.period != com.optionslab.ira.Goals.Period.DAY }
            val ask = g != null || com.optionslab.ira.Goals.asked(q) || com.optionslab.ira.Goals.clearAsked(q)
            if (ask) {
                _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
                if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return }
                scope.launch {
                    val said = runCatching {
                        when {
                            g != null -> { IraGoals.add(g); "Goal kept, Boss: ${g.text()}. I'll track it every day and tell you when it is close. " + IraGoals.say() }
                            com.optionslab.ira.Goals.clearAsked(q) -> { IraGoals.clear(); "Done, Boss: no goals now." }
                            else -> IraGoals.say()
                        }
                    }.getOrElse { "I could not read your goals just now, Boss." }
                    reply(said)
                }
                return
            }
        }
        // "Why did you park ORB 5?", "why did you do that?": his own action, with the reason he wrote down then.
        if (com.optionslab.ira.SelfWhy.asked(q) && parsed.command == null && parsed.order == null) {
            val said = if (phoneLocked()) "Unlock the phone for that, Boss."
                else runCatching { com.optionslab.ira.SelfWhy.answer(q, IraActivity.entries(), LocalDateTime.now(IST)) }.getOrElse { "I could not read my activity just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "What held up?": every paper strategy's forward test (part 7).
        if (com.optionslab.ira.Vetting.asked(q) && Ask.parse(q).command == null && !com.optionslab.app.BuildConfig.GOLD) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return }
            scope.launch { reply(runCatching { IraExpert.say() }.getOrElse { "I could not read the paper trades just now, Boss." }) }
            return
        }
        // "Where are you weakest / strongest?": his own ideas scored by the conditions they came in (self-calibration).
        if (com.optionslab.ira.SelfCalibration.asked(q) && Ask.parse(q).command == null && !com.optionslab.app.BuildConfig.GOLD) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return }
            scope.launch {
                val today = com.optionslab.app.data.Market.today()
                // And Solo's own paper trades, judged the same way (only when Solo has any scored).
                val solo = runCatching { com.optionslab.ira.SoloCalibration.say(IraSolo.calibration(), today) }.getOrNull()
                // And the kinds of answer Boss marks wrong (only when some kind is doubted).
                val answers = runCatching { com.optionslab.ira.SelfDoubt.say(IraTools.mistakes(), IraTools.askedKinds(), today) }.getOrNull()
                reply(runCatching { com.optionslab.ira.SelfCalibration.say(IraNewsTrades.calibration(), today) + (solo?.let { " $it" } ?: "") +
                    (answers?.let { " $it" } ?: "") }
                    .getOrElse { "I could not read my record just now, Boss." })
            }
            return
        }
        // "What have you learned?": the lessons in every arm's, strategy's and Boss's closed trades (part 5).
        if (com.optionslab.ira.Lessons.asked(q) && Ask.parse(q).command == null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return }
            scope.launch {
                val said = runCatching { IraAccount.lessons().let { (l, n) -> com.optionslab.ira.Lessons.say(l, n) } }.getOrElse { "I could not read the trades just now, Boss." }
                reply(said)
            }
            return
        }
        // "How do you know that?": the facts the last answer was built from.
        if (com.optionslab.ira.Sources.asked(q)) {
            val last = _state.value.messages.lastOrNull { it.fromIra && !it.text.startsWith(TOOK_AS) }
            // The facts may be the account's: never read out on a locked phone.
            val said = if (phoneLocked()) "Unlock the phone for that, Boss." else com.optionslab.ira.Sources.say(last?.facts.orEmpty())
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "The usual": the market question Boss asks most around this hour.
        if (!understood && com.optionslab.ira.Habits.asked(q)) {
            val usual = runCatching { com.optionslab.ira.Habits.usual(IraTools.habits(), LocalDateTime.now(IST).hour)?.let { com.optionslab.ira.Habits.question(it) } }.getOrNull()
            if (usual == null) {
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, "I don't know your usual yet, Boss. Ask me a few times and I'll learn it.")).takeLast(MAX_MESSAGES)) }
                return
            }
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, "$TOOK_AS\"$usual\".")).takeLast(MAX_MESSAGES)) }
            ask(usual, understood = true)
            return
        }
        // Everyday words for the commonest requests ("pause all bots", "am I up today"): read at once by fixed rules,
        // then asked as that line - an action waits for Confirm, as when the model picks it.
        // ("Halt the algos" reads as stopping one strategy named "the algos": the everyday reading wins there.)
        if (!understood && parsed.order == null && parsed.command?.kind.let { it == null || it == com.optionslab.ira.Command.Kind.STOP_ONE }) {
            val line = runCatching { com.optionslab.ira.Intents.quick(q) }.getOrNull()
            if (line != null && !lockedAccount(q, line)) {
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, "$TOOK_AS\"$line\".")).takeLast(MAX_MESSAGES)) }
                ask(line, understood = true)
                return
            }
        }
        // A trading word explained ("what is theta", "explain max pain"): at once, before anything is looked up.
        if (parsed.order == null && parsed.command == null && (Topic.ACCOUNT !in parsed.topics || !Regex("(?i)\\b(my|mine|our|me|i)\\b").containsMatchIn(q)) && Topic.EXPLAIN !in parsed.topics) runCatching { com.optionslab.ira.Glossary.explain(q) }.getOrNull()?.let { text ->
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, text)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "What is the premium of 24500 CE": the option chain read now (8 seconds at most) - never an order. (Not in IraGoldAlgo.)
        if (!GOLD_ONLY_TALK && parsed.order == null && parsed.command == null && Topic.ACCOUNT !in parsed.topics && !Regex("(?i)\\b(i|me|my|mine)\\b").containsMatchIn(q))
            runCatching { com.optionslab.ira.OptionQuote.asked(parsed.text.ifBlank { q }) }.getOrNull()?.let { oq ->
                val named = parsed.markets.filter { it != IraMarket.VIX && it != IraMarket.GOLD }
                val m = named.firstOrNull { it in LIVE.keys } ?: IraMarket.NIFTY
                fun answer(text: String) = _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, text)).takeLast(MAX_MESSAGES)) }
                if (parsed.markets.isNotEmpty() && named.isEmpty()) { answer("There are no options on that here, Boss: option prices are for Nifty, BankNifty, FinNifty and Sensex."); return }
                if (!com.optionslab.app.data.Market.isOpen()) { answer("The market is closed, Boss: option prices are read live, from 9:15 to 3:30 on trading days."); return }
                if (!online()) { answer("I'm offline, Boss: option prices need the internet."); return }
                // A placeholder answer at once (so a later question is never answered with this quote), replaced when read.
                val wait = Msg(true, "$CHAIN_NOTE${m.label}, Boss...")
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + wait).takeLast(MAX_MESSAGES)) }
                scope.launch {
                    val read = scope.async { runCatching { IraAccount.chain(LIVE.getValue(m)) }.getOrNull() }
                    val ch = kotlinx.coroutines.withTimeoutOrNull(8_000) { read.await() }
                    if (ch == null) read.cancel()
                    val text = if (ch == null) "I couldn't read the ${m.label} option chain just now, Boss."
                        else runCatching { com.optionslab.ira.OptionQuote.say(ch, oq, m.label) ?: com.optionslab.ira.OptionQuote.near(ch, oq) }
                            .getOrDefault("I couldn't read the ${m.label} option chain just now, Boss.")
                    _state.update { st -> st.copy(messages = st.messages.map { if (it === wait) it.copy(text = text) else it }) }
                }
                return
            }
        // Boss's own market questions are counted by the hour (for "the usual"), off the main thread - and kept in his
        // routine (the question's key and when, and whether a trade of his had just closed at a loss): a habit found is put
        // to him once, kept only on his yes.
        if (!understood) scope.launch {
            val loss = runCatching { IraCoach.recentLoss() }.getOrNull() != null
            runCatching { IraTools.noteHabit(q, loss) }.getOrNull()?.let { p -> offerRoutine(p) }
        }
        if (Topic.BACKTEST in parsed.topics) {
            // IraGoldAlgo: no NSE backtests or strategies (Jarvis only talks there).
            if (GOLD_ONLY_TALK) { _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, GOLD_TALK_ONLY)).takeLast(MAX_MESSAGES)) }; return }
            backtestAsked(q, parsed); return
        }
        if (Topic.ACCOUNT in parsed.topics) { accountAsked(q); return }
        if (Topic.COMMAND in parsed.topics) { commandAsked(q, parsed.command!!, confirmAlways = understood, heard = !understood); return }
        // IraGoldAlgo brings no trade ideas (it only talks; its gold arms trade on paper by their own rules).
        if (Topic.SUGGEST in parsed.topics && com.optionslab.app.BuildConfig.GOLD) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, GOLD_TALK_ONLY)).takeLast(MAX_MESSAGES)) }; return }
        if (Topic.SUGGEST in parsed.topics) { suggestAsked(q, parsed.markets); return }
        val explain = parsed.pattern?.takeIf { Topic.EXPLAIN in parsed.topics }
        if (explain != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(com.optionslab.ira.PatternExpert.explain(explain, IraStudy.state.value.edges))
            return
        }
        if (Topic.TRADE_CHECK in parsed.topics) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            scope.launch {
                // On a weekday Boss follows another index, its read first ([com.optionslab.ira.DayIndex]; unlocked only, never
                // in IraGoldAlgo): the same lines, only their order - the verdict and its reasons unchanged, nothing acts.
                val said = runCatching {
                    val verdict = tradeCheckFast()
                    val dayLead = runCatching { IraTools.dayIndexLead(phoneLocked()) }.getOrNull()
                    (if (dayLead == null) verdict else verdict.copy(reads = com.optionslab.ira.DayIndex.leadReads(verdict.reads, dayLead))).say()
                }.getOrElse { "I could not run the trade check just now." }
                // What Boss told me about himself that bears on now ("I get greedy after a win" when he is up): his words and
                // his day, so only on an unlocked phone. Words only - it changes nothing.
                val about = if (phoneLocked()) null else runCatching { aboutBossNow() }.getOrNull()
                reply(if (about == null) said else "$said $about")
            }
            return
        }
        // "How is Solo doing": its switch and its paper record (switched only on the Jarvis settings page, never by voice).
        if (parsed.order == null && parsed.command == null && Regex("(?i)\\bsolo\\b").containsMatchIn(q)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, IraSolo.status())).takeLast(MAX_MESSAGES)) }
            return
        }
        // Reasoning over the data on the phone: a move over a stretch of time, which market is stronger, the expected range.
        // (Not for an advice question: the usual answer says Jarvis gives no buy or sell advice.)
        if (parsed.order == null && parsed.command == null && Topic.ADVICE !in parsed.topics) runCatching { reasoned(q, parsed) }.getOrNull()?.let { text ->
            // How old the prices are, said first (a reasoned answer over past days is still given, its figures marked).
            val said = offlineNote()?.let { "$it $text" } ?: aged(text, parsed.markets, parsed.topics + Topic.OVERVIEW, withhold = false).text
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said, listOf(text))).takeLast(MAX_MESSAGES)) }
            return
        }
        // "Exit", "close it", "square off", "flatten", "get out" - no command by itself (review, 5 Oct: they were taken as
        // mis-heard fragments): how to ask for it, at once. Words only - nothing is closed from here; "close all" / "exit
        // all" are commands with their own confirm.
        if (com.optionslab.app.BuildConfig.JARVIS && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.MisHeard.exitHint(q) }.getOrNull()?.let { hint ->
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, hint, whole = true)).takeLast(MAX_MESSAGES)) }
                return
            }
        // Voice, round 26 (Boss's diagnostics, 5 Oct: "bus", "office schedule", "what s the piano" went to the slow model).
        // Heard words close to a known question (a word said twice, a known slip, a near-miss index name) are asked as that
        // question - a question only, never a command or an order. Then short words that match nothing are most likely a
        // mis-hear: "say it again" at once, without the model or "One moment", counted as mis-heard (not as a question not
        // understood). Typed words only when a single non-word. Nothing here acts.
        if (com.optionslab.app.BuildConfig.JARVIS && !understood && parsed.order == null && parsed.command == null &&
            (Topic.OFF_TOPIC in parsed.topics || parsed.topics == setOf(Topic.WHY))) {
            val byVoice = heardByVoice
            val meant = if (byVoice && Topic.OFF_TOPIC in parsed.topics) runCatching { com.optionslab.ira.MisHeard.rescue(q) }.getOrNull() else null
            if (meant != null && !lockedAccount(q, meant)) {
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, "$TOOK_AS\"$meant\".")).takeLast(MAX_MESSAGES)) }
                ask(meant, understood = true, rescued = true)
                return
            }
            if (runCatching { com.optionslab.ira.MisHeard.fragment(q, voice = byVoice) }.getOrDefault(false)) {
                IraTools.count(com.optionslab.ira.MisHeard.COUNT)
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, com.optionslab.ira.MisHeard.SAY)).takeLast(MAX_MESSAGES)) }
                return
            }
        }
        // Not understood (counted only now: a briefing or "what changed" was answered by the reasoning above).
        if (Topic.OFF_TOPIC in parsed.topics) IraTools.count("misunderstood")
        // Jarvis, words Jarvis does not know: the model maps them to one line of a fixed list (never an order).
        if (parsed.topics == setOf(Topic.OFF_TOPIC) && com.optionslab.app.BuildConfig.JARVIS && IraModel.usable() && !understood) { freeFormAsked(q); return }
        // Jarvis without the model: a varied "I don't know that" instead of the same line every time.
        if (parsed.topics == setOf(Topic.OFF_TOPIC) && com.optionslab.app.BuildConfig.JARVIS) {
            val said = if (com.optionslab.ira.Chat.personal(q)) com.optionslab.ira.Chat.aboutMe(chatTurn.getAndIncrement()) else com.optionslab.ira.Chat.fallback(chatTurn.getAndIncrement())
            runCatching { IraTools.missed(q) }
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // A complete order on the PAPER account is placed at once (the owner's rule). In Live mode a Jarvis order never
        // reaches Zerodha on its own: it goes to the app's order review (the swipe and the PIN), like any order - real
        // orders are never placed via Jarvis (Boss's rule; found in the review before the merge, 3 Oct).
        parsed.order?.takeIf { com.optionslab.app.BuildConfig.JARVIS && it.missing.isEmpty() && it.refusal == null &&
            runCatching { !com.optionslab.app.data.AppSettings.load().live }.getOrDefault(false) }?.let { o -> tradeAsked(q, o); return }
        // A greeting is answered with the time of day, today's session and where the indices stand.
        val closedReason = noSessionWhy()
        val now = LocalDateTime.now(IST)
        val st0 = _state.value
        val book0 = book
        val calls0 = runCatching { IraTools.patternCalls() }.getOrDefault(emptyList())
        // A greeting names the index Boss asks about by name first ([com.optionslab.ira.LeadIndex]; only the order changes).
        val first0 = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && parsed.topics == setOf(Topic.GREETING))
            runCatching { IraTools.firstIndex() }.getOrNull() else null
        // A plain overview says the part Boss asks for on its own right after the price ([com.optionslab.ira.LeadPart];
        // only the order of sentences changes).
        val part0 = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && com.optionslab.ira.LeadPart.reorders(parsed.topics))
            runCatching { IraTools.leadPart() }.getOrNull() else null
        // Worked out ahead while the recognizer's final reading was awaited ([prepare]): taken only for the same question
        // from the same prices, news and minute (the same words then, exactly); otherwise worked out now, as before.
        val a0 = ahead.take(Inputs(com.optionslab.ira.Turn.key(parsed), st0.snaps, st0.news, book0, now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES), closedReason))
            ?: runCatching { Ira(book0, calls0, first0, part0).answer(q, st0.snaps, st0.news, voice = com.optionslab.app.BuildConfig.JARVIS,
                now = now, closedReason = closedReason) }.getOrElse { com.optionslab.ira.Answer("I could not work that out.", emptyList()) }
        // A holiday or a weekend: said first, so the last session's prices are not taken for today's.
        val closed = closedToday()?.takeIf { parsed.topics.any { it in MARKET_TOPICS } && testHistories == null }
        val a1 = if (closed == null) a0 else a0.copy(text = closed.substringBefore(" Prices") + " " + a0.text, facts = listOf(closed) + a0.facts)
        val market = parsed.topics.any { it in MARKET_TOPICS }
        val off = if (market) offlineNote() else null
        // How old the data it rests on is ([com.optionslab.ira.DataAge]): old, said first with the price figures marked;
        // stale prices, said so instead of quoted. Only ever adds caution.
        val dressed: com.optionslab.ira.DataAge.Dressed? = if (market && off == null && a1.order == null) aged(a1.text, parsed.markets, parsed.topics) else null
        val ageNote: String? = dressed?.note
        val a2 = when {
            off != null -> a1.copy(text = off + " " + a1.text, facts = listOf(off) + a1.facts)
            dressed == null || ageNote == null -> a1
            dressed.withheld -> a1.copy(text = dressed.text, facts = listOf(dressed.text))
            else -> a1.copy(text = dressed.text, facts = listOf(ageNote) + a1.facts)
        }
        // A day with an event (an expiry, RBI, the Fed, the budget): said with the market's picture, it explains the moves.
        val ev = if (parsed.topics.any { it == Topic.OVERVIEW || it == Topic.WHY || it == Topic.VOLATILITY } && testHistories == null)
            runCatching { val today = com.optionslab.app.data.Market.today(); IraEvents.upcoming(1).filter { it.day == today }.take(2).map { com.optionslab.ira.Events.line(it, today) } }.getOrNull().orEmpty()
        else emptyList()
        val a = if (ev.isEmpty()) a2 else a2.copy(text = a2.text + " " + ev.joinToString(" "), facts = a2.facts + ev)
        // Jarvis with the model ready: the answer shows at once, then the model rewrites it in place if it passes the checks.
        // When Boss asked about each market, and its price then (for "what changed since I last asked").
        if (parsed.topics.any { it in MARKET_TOPICS }) (parsed.markets.ifEmpty { listOf(IraMarket.NIFTY) }).forEach { mk ->
            _state.value.snaps[mk]?.let { sn -> synchronized(askedAt) { askedAt.remove(mk); askedAt[mk] = sn.price to LocalDateTime.now(IST) } }
        }
        // An answer carrying its data's age is not rewritten (the model could drop the warning or the marks).
        val write = IraModel.usable() && com.optionslab.ira.Writer.worthRewriting(parsed, a) && ageNote == null
        // A kind of answer Boss often marks wrong: "check me on this, Boss" (and, clearly weak, how the question was read)
        // around it - words only, the figures as they are, never for an order or a command.
        val doubt = if (parsed.order == null && parsed.command == null && a.order == null) IraTools.doubt(q) else com.optionslab.ira.SelfDoubt.NONE
        // Why the caution, as it stood now (for "what made you say check me?"): his record, never Boss's words.
        if (doubt.level != com.optionslab.ira.SelfDoubt.Level.NORMAL) runCatching { IraThinking.add(com.optionslab.ira.Thinking.caution(IraThinking.now(), doubt)) }
        // A confidence word the number beside it doesn't bear out ("usually" beside "4 of the last 12") is said as the word
        // that fits ([com.optionslab.ira.WordFit]); only words, never a figure, and never an order's words.
        val fitted = if (a.order == null && parsed.order == null && parsed.command == null) IraTools.fitWords(a.text) else a.text
        // The question Boss usually asks after this kind, offered in one short question at the end
        // ([com.optionslab.ira.NextAsk]; from his routine log, keys only): his own words only, an unlocked phone, a plain
        // answer (no order, no command, no caution, no old or withheld data). Words only - never answered unasked.
        // Never while anything waits for Boss's yes or Confirm (a stop, an exit, a news trade pending, or Jarvis's own
        // yes-or-no window open): a "yes" meant for the offer must never approve the older request.
        val nextAskBusy = synchronized(actions) { actions.isNotEmpty() } || runCatching { JarvisVoice.askingOpen() }.getOrDefault(true)
        val nextAskLine: String? = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !understood && !bundled &&
            parsed.order == null && parsed.command == null && a.order == null && doubt.level == com.optionslab.ira.SelfDoubt.Level.NORMAL &&
            off == null && ageNote == null && dressed?.withheld != true && !nextAskBusy)
            runCatching { IraTools.nextAskOffer(q, phoneLocked(), nextAskBusy) }.getOrNull() else null
        val nextAskTail = if (nextAskLine == null) "" else " $nextAskLine"
        val msg = Msg(true, doubt.wrap(fitted) + nextAskTail, a.facts, a.order, writing = write)
        _state.update { it.copy(messages = (it.messages + Msg(false, q) + msg).takeLast(MAX_MESSAGES)) }
        // The patterns the answer told of are followed (market data only; withheld words told of nothing).
        if (dressed?.withheld != true && a.calls.isNotEmpty()) scope.launch(Dispatchers.IO) { runCatching { IraTools.patternsTold(a.calls) } }
        if (write) scope.launch {
            // The voice first, the model after (Boss, 5 Oct: first sound 37 s after his words, and 7-9 s of his speech read
            // as nothing, while the model was "writing"). This rewrite is for the screen only - Jarvis always says the
            // answer above at once - yet it used to start 1.5 s after the answer whenever the voice had not made a sound,
            // and then held the fast cores for up to 30 s, starving the speech engine and the recognizer. It now starts
            // only once the voice is free (the answer said, Boss not speaking) and is stopped when either starts again
            // ([JarvisVoice.freeForModel], [IraModel.yieldToVoice]); with Jarvis off it starts at once, as before.
            // One stopped for the voice is tried once more when it is free again ([IraModel.rewriteWhenFree]).
            val better = runCatching { IraModel.rewriteWhenFree(q, a.facts, a.text) }.getOrNull()
            // The model's wording checked the same way (outside the update, which may run more than once).
            val betterFit = if (better != null && better != a.text) IraTools.fitWords(better) else null
            _state.update { s -> s.copy(messages = s.messages.map { m ->
                if (m !== msg) m else if (betterFit != null) m.copy(text = doubt.wrap(betterFit) + nextAskTail, draft = msg.text, writing = false) else m.copy(writing = false)
            }) }
        }
    }

    /**
     * [ask]'s question branches on how Jarvis himself speaks and hears: AlertSense, Airtime, Hearing, PatternCalls,
     * TrendReads, Clarity, WordFit, AskedAgain, FigureFirst, WrongThing, ArmHabits, MorningSense, HonestStars, TalkHours, MorningAsks, TurnDowns, TopicLength, OutlookCheck, UsualIndex, Nicknames, LeadIndex, LeadPart, NextAsk, MoreAfter, SmallTrades, DayIndex, CheckTimes, CondNeeds - in [ask]'s order. True when one
     * took [q], answered exactly as before; each branch keeps its own guard (not [bundled], no order, no command).
     */
    private fun askedOfHisWays(q: String, parsed: com.optionslab.ira.Question, bundled: Boolean, understood: Boolean): Boolean {
        // "Which alerts do you hold back?" / "say everything again": the unasked alerts he says aloud less often (kinds only,
        // nothing about the account; it only ever changes how often his own voice speaks, never anything that acts).
        val alertAsk = if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null) runCatching { com.optionslab.ira.AlertSense.asked(q) }.getOrNull() else null
        if (alertAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            scope.launch { reply(if (alertAsk == com.optionslab.ira.AlertSense.Request.ALL) IraTools.alertsAll()
                else IraTools.alertsHeld() + (if (IraAirtime.heldToday() > 0) " " + IraAirtime.answer() else "")) }
            return true
        }
        // "Why so quiet?", "did you hold back any alerts?": today's market alerts - said aloud, or kept to the chat and why.
        if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null && runCatching { com.optionslab.ira.Airtime.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(IraAirtime.answer())
            return true
        }
        // "How well are you hearing me?" / "are you having trouble hearing?": his ears' own counts today against the days
        // before (numbers only, never words) and what would help - a suggestion only: nothing is switched by voice, the
        // Google speech choice least of all ([com.optionslab.ira.Hearing]).
        if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.Hearing.asked(q) }.getOrDefault(false)) {
            val said = runCatching { com.optionslab.ira.Hearing.spoken(JarvisVoice.hearingDays, JarvisVoice.hearingDay(), JarvisVoice.googleSpeech) }
                .getOrElse { "I couldn't read my hearing counts just now, Boss." }
                .let { h -> JarvisVoice.languageSpoken().takeIf { it.isNotEmpty() }?.let { "$h $it" } ?: h }
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "Which patterns work on Nifty?" / "how good are your pattern calls?": how the patterns he told of played out on
        // this phone ([com.optionslab.ira.PatternCalls]; market data only, facts, never advice).
        if (!bundled && parsed.order == null && parsed.command == null && runCatching { com.optionslab.ira.PatternCalls.asked(q) }.getOrDefault(false)) {
            val said = runCatching { com.optionslab.ira.PatternCalls.say(IraTools.patternCalls(), parsed.markets, com.optionslab.app.data.Market.today()) }
                .getOrDefault("I couldn't read my pattern record just now, Boss.")
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "How often were your trend reads right this month?" / "how accurate are your structure reads?": the trend and range
        // reads he gave in session, scored after each close by the same measure ([com.optionslab.ira.TrendReads]; his calls -
        // the index, the minute, the kind and how the day ended - never Boss's words). Market data only; facts, never advice.
        if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD &&
            runCatching { com.optionslab.ira.TrendReads.asked(q) }.getOrDefault(false)) {
            val said = runCatching { com.optionslab.ira.TrendReads.say(IraTools.trendReads(), parsed.markets,
                com.optionslab.ira.TrendReads.span(q), com.optionslab.app.data.Market.today()) }
                .getOrDefault("I couldn't read my trend-read record just now, Boss.")
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "Open YouTube", "play music", "call mom": something outside IraAlgo. Jarvis works only inside the app, so he says so
        // politely (it landed in "Words I could not place", 5 Oct). Words only: nothing is opened, played, called or sent.
        if (!bundled && parsed.order == null && parsed.command == null && runCatching { com.optionslab.ira.OutsideApp.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, runCatching { com.optionslab.ira.OutsideApp.say(q) }.getOrDefault(com.optionslab.ira.OutsideApp.SAY))).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "Which answers do you keep short?" / "say your answers in full again": the answer kinds said shorter aloud, Boss having
        // asked "what?" after them ([com.optionslab.ira.Clarity]; kinds only). Only Jarvis's voice changes - nothing acts.
        val clarityAsk = if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.Clarity.asked(q) }.getOrNull() else null
        if (clarityAsk != null) {
            val said = if (clarityAsk == com.optionslab.ira.Clarity.Request.RESET) IraTools.clarityReset() else IraTools.clarityHeld()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "How well do your words match your numbers?" / "what do you mean by usually?" / "say your confidence words as
        // written": his confidence words against the numbers beside them ([com.optionslab.ira.WordFit]; his own words only).
        // Only the word Jarvis says changes, never a figure - nothing acts.
        val fitAsk = if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.WordFit.asked(q) }.getOrNull() else null
        if (fitAsk != null) {
            val said = when (fitAsk) {
                com.optionslab.ira.WordFit.Request.OFF -> IraTools.wordFitSwitch(true)
                com.optionslab.ira.WordFit.Request.ON -> IraTools.wordFitSwitch(false)
                com.optionslab.ira.WordFit.Request.HOW -> IraTools.wordFitSay(q)
            }
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "Which of your answers do I ask again?": the market reads Boss asked again within minutes - a sign the first answer
        // missed ([com.optionslab.ira.AskedAgain]; kinds and times only, never words). A record only: nothing learned acts.
        if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.AskedAgain.asked(q) }.getOrDefault(false)) {
            val said = IraTools.againSay()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "Which reads do you start with the number?" / "say your market reads in the usual order": the market reads Boss kept
        // asking again for a figure, said figure first aloud ([com.optionslab.ira.FigureFirst]; kinds only). Only the order
        // Jarvis's voice says them in changes - never a word, a figure or the chat, and nothing acts.
        val figureAsk = if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.FigureFirst.asked(q) }.getOrNull() else null
        if (figureAsk != null) {
            val said = if (figureAsk == com.optionslab.ira.FigureFirst.Request.RESET) IraTools.figureReset() else IraTools.figureHeld()
            // "Don't start your answers with the levels": the levels first in an overview ([com.optionslab.ira.LeadPart]) undone too.
            if (figureAsk == com.optionslab.ira.FigureFirst.Request.RESET && runCatching { com.optionslab.ira.FigureFirst.namesPart(q) }.getOrDefault(false))
                runCatching { IraTools.leadPartReset(phoneLocked()) }
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "What did you get wrong today?" / "galat jawab": the questions Jarvis answered with the wrong thing - asked again within
        // 2 minutes, or Boss saying so ([com.optionslab.ira.WrongThing]; the question's kind and the way taken only, never the
        // words). A record to fix the routing by: nothing learned acts or re-routes by itself.
        val wrongAsk = if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.WrongThing.asked(q) }.getOrNull() else null
        val wrongObjected = wrongAsk == null && com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.WrongThing.objected(q) }.getOrDefault(false)
        if (wrongAsk != null || wrongObjected) {
            val said = if (wrongAsk != null) IraTools.wrongSay(wrongAsk) else {
                IraTools.wrongSaid()
                "Sorry, Boss - noted that my last answer missed what you asked (what it was about and how I took it, never your words). " +
                    "Ask it again in other words, or say \"that was wrong\" and I'll ask whether to learn what you meant."
            }
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "Do I usually disarm my bots after losses?" / "which bots do I keep armed?": what Boss does with his bots after
        // losing days, as his own record ([com.optionslab.ira.ArmHabits]; switches and day signs only, never amounts). Talk
        // only: nothing learned arms, disarms, stops or offers anything. His account, so never on a locked phone; not in GOLD.
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.ArmHabits.asked(q) }.getOrDefault(false)) {
            val said = if (phoneLocked()) com.optionslab.ira.ArmHabits.LOCKED else IraBots.armHabitsSay(q)
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "Which morning items do you skip?" / "say the whole morning check again": the minor 09:00 items Boss usually leaves
        // as they are, named in a few words aloud ([com.optionslab.ira.MorningSense]; item keys only). Only the spoken check
        // is shorter - never a safety item, never the chat - and nothing learned fixes or changes anything.
        val morningAsk = if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.MorningSense.asked(q) }.getOrNull() else null
        if (morningAsk != null) {
            val said = if (morningAsk == com.optionslab.ira.MorningSense.Request.RESET) IraTools.morningReset() else IraTools.morningSay()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "How honest are your confidence scores?" / "say your confidence plainly": the scores whose ideas have not held up,
        // said aloud with their record ([com.optionslab.ira.HonestStars]; counts only). Only the spoken score gains its record -
        // the score, the chat and what Jarvis does are unchanged. His ideas and Boss's answers, so never on a locked phone.
        val starsAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.HonestStars.asked(q) }.getOrNull() else null
        if (starsAsk != null) {
            val said = if (phoneLocked()) com.optionslab.ira.HonestStars.LOCKED
                else if (starsAsk == com.optionslab.ira.HonestStars.Request.RESET) IraTools.starsPlain() else IraTools.starsSay()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "When do I usually talk to you?" / "say your briefings in full at any hour": the hours Boss talks to Jarvis, outside
        // which a long unasked briefing is said in one sentence aloud ([com.optionslab.ira.TalkHours]; days and hours only).
        // Only the voice is shorter - never a safety warning, never the chat - and nothing learned acts.
        val talkAsk = if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.TalkHours.asked(q) }.getOrNull() else null
        if (talkAsk != null) {
            val said = if (talkAsk == com.optionslab.ira.TalkHours.Request.RESET) IraTools.talkReset() else IraTools.talkSay()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "What do you offer me in the morning?" / "don't offer my usual morning question": the question Boss asks every
        // morning, offered in one line at the end of the morning check ([com.optionslab.ira.MorningAsks]; kinds and days only).
        // Words only - never answered unasked, and nothing learned acts.
        val usualAsk = if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.MorningAsks.asked(q) }.getOrNull() else null
        if (usualAsk != null) {
            val said = if (usualAsk == com.optionslab.ira.MorningAsks.Request.RESET) IraTools.morningAsksReset() else IraTools.morningAsksSay()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "Why do I turn down your ideas?" / "don't remind me why I turn your ideas down": the reasons Boss turns Jarvis's
        // trade ideas down for, said up front before the next idea they fit ([com.optionslab.ira.TurnDowns]; reason kinds
        // and times only). His own habits: named on an unlocked phone only. Words only - nothing learned acts.
        val turnAsk = if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.TurnDowns.asked(q) }.getOrNull() else null
        if (turnAsk != null) {
            val said = if (turnAsk == com.optionslab.ira.TurnDowns.Request.RESET) IraTools.turnDownsReset()
                else if (phoneLocked()) com.optionslab.ira.TurnDowns.LOCKED else IraTools.turnDownsSay()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "How long do I like your answers?" / "say every topic at the usual length": the topics said in a sentence or in
        // full aloud, as Boss keeps asking for them ([com.optionslab.ira.TopicLength]; kinds only). Words only - nothing learned acts.
        val lengthAsk = if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.TopicLength.asked(q) }.getOrNull() else null
        if (lengthAsk != null) {
            val said = if (lengthAsk == com.optionslab.ira.TopicLength.Request.RESET) IraTools.lengthReset() else IraTools.lengthSay()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "How good are your morning outlooks?", "did your outlook hold today?", "tumhara outlook kitna sahi hota hai"
        // ([com.optionslab.ira.OutlookCheck]): each index's 09:00 outlook (range, direction read, pivot) against its close,
        // in counts only - never a verdict or advice, nothing acts. Market data only, so on a locked phone too. (Before the
        // index outlook itself, which answers "Nifty outlook for tomorrow". Not in IraGoldAlgo.)
        val outlookAsk = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.OutlookCheck.asked(q) }.getOrDefault(false)
        if (outlookAsk) {
            val outlookSaid = IraTools.outlookSay(com.optionslab.app.data.Market.today())
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, outlookSaid)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "Which index do I usually mean?" / "use Nifty when I don't name an index": the index taken when Boss names none, learned from his
        // corrections ([com.optionslab.ira.UsualIndex]; indices and times only). His habit: named on an unlocked phone only;
        // the undo works locked too. Understanding only - nothing learned acts.
        val indexAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.UsualIndex.asked(q) }.getOrNull() else null
        if (indexAsk != null) {
            val indexSaid = if (indexAsk == com.optionslab.ira.UsualIndex.Request.RESET) IraTools.indexReset()
                else if (phoneLocked()) com.optionslab.ira.UsualIndex.LOCKED else IraTools.indexSay()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, indexSaid)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "What nicknames do I use?" / "forget my nicknames for my arms": the words Boss uses for one arm or position, each kept
        // on his own pick after Jarvis asked which one ([com.optionslab.ira.Nicknames]). They name his arms, so named on an
        // unlocked phone only; the undo works locked too. Understanding only - nothing learned acts. Not in IraGoldAlgo.
        val nickAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.Nicknames.asked(q) }.getOrNull() else null
        if (nickAsk != null) {
            val nickSaid = if (nickAsk == com.optionslab.ira.Nicknames.Request.RESET) IraTools.nickReset()
                else if (phoneLocked()) com.optionslab.ira.Nicknames.LOCKED else IraTools.nickSay()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, nickSaid)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "Which index do you mention first?" / "mention Nifty first again": the index Boss asks about by name, named first
        // where both are given ([com.optionslab.ira.LeadIndex]; from the kinds tally, counts only). His habit: named on an
        // unlocked phone only; the undo works locked too, in neutral words. Only the order of words changes - nothing learned acts. Not in IraGoldAlgo.
        val firstAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.LeadIndex.asked(q) }.getOrNull() else null
        if (firstAsk != null) {
            val firstSaid = if (firstAsk == com.optionslab.ira.LeadIndex.Request.RESET) IraTools.firstIndexReset(phoneLocked())
                else if (phoneLocked()) com.optionslab.ira.LeadIndex.LOCKED else IraTools.firstIndexSay()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, firstSaid)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "What do you say first in an overview?" / "say your overviews in the usual order": the part Boss asks for on its
        // own, said right after the price in a plain overview ([com.optionslab.ira.LeadPart]; from the kinds tally, counts
        // only). His habit: named on an unlocked phone only; the undo works locked too, in neutral words. Only the order of
        // sentences changes - nothing learned acts. Not in IraGoldAlgo.
        val partAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.LeadPart.asked(q) }.getOrNull() else null
        if (partAsk != null) {
            val partSaid = if (partAsk == com.optionslab.ira.LeadPart.Request.RESET) IraTools.leadPartReset(phoneLocked())
                else if (phoneLocked()) com.optionslab.ira.LeadPart.LOCKED else IraTools.leadPartSay()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, partSaid)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "What do I usually ask next?" / "stop offering what I ask next": the question Boss usually asks after an answer,
        // offered in one short question at its end ([com.optionslab.ira.NextAsk]; from his routine log, keys only). His
        // habit: named on an unlocked phone only; the undo works locked too, in neutral words. Words only - nothing learned
        // acts. Not in IraGoldAlgo.
        val nextAskReq = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.NextAsk.asked(q) }.getOrNull() else null
        if (nextAskReq != null) {
            val nextAskSaid = if (nextAskReq == com.optionslab.ira.NextAsk.Request.RESET) IraTools.nextAskReset(phoneLocked())
                else if (phoneLocked()) com.optionslab.ira.NextAsk.LOCKED else IraTools.nextAskSay()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, nextAskSaid)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "Which answers do I usually ask more about?" / "keep my short answers short": the kinds of answer said in full
        // straight away aloud, as Boss usually asks for more after their short line ([com.optionslab.ira.MoreAfter]; kinds
        // and minutes only). His habit: named on an unlocked phone only; the undo works locked too, in neutral words. The
        // voice's length only - nothing learned acts. Not in IraGoldAlgo.
        val moreAfterReq = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.MoreAfter.asked(q) }.getOrNull() else null
        if (moreAfterReq != null) {
            val moreAfterSaid = if (moreAfterReq == com.optionslab.ira.MoreAfter.Request.RESET) IraTools.moreAfterReset(phoneLocked())
                else if (phoneLocked()) com.optionslab.ira.MoreAfter.LOCKED else IraTools.moreAfterSay()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, moreAfterSaid)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "What have you learned about my charges?" / "stop mentioning my small trades": where Boss's trades that moved less
        // than twice their own charges come from, by source and time of day ([com.optionslab.ira.SmallTrades]; his closed
        // trades, read only). His record: named on an unlocked phone only; the undo works locked too, in neutral words. A
        // fact only - nothing learned acts. Not in IraGoldAlgo.
        val smallReq = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.SmallTrades.asked(q) }.getOrNull() else null
        if (smallReq != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val smallLocked = phoneLocked()
            if (smallReq != com.optionslab.ira.SmallTrades.Request.RESET && smallLocked) { reply(com.optionslab.ira.SmallTrades.LOCKED); return true }
            scope.launch(Dispatchers.IO) {
                reply(runCatching {
                    if (smallReq == com.optionslab.ira.SmallTrades.Request.RESET) IraTools.smallTradesReset(smallLocked) else IraTools.smallTradesSay()
                }.getOrElse { "I couldn't read your trades' record just now, Boss." })
            }
            return true
        }
        // "Which index do you lead with on Wednesdays?" / "lead with Nifty every day again": the index Boss follows on a given
        // weekday, its read given first in "how's the market" on that weekday ([com.optionslab.ira.DayIndex]; from the kinds
        // tally, counts only). His habit: named on an unlocked phone only; the undo works locked too, in neutral words. Only
        // the order of the index lines changes - nothing learned acts. Not in IraGoldAlgo.
        val dayIndexReq = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.DayIndex.asked(q) }.getOrNull() else null
        if (dayIndexReq != null) {
            val dayIndexSaid = if (dayIndexReq == com.optionslab.ira.DayIndex.Request.RESET) IraTools.dayIndexReset(phoneLocked())
                else if (phoneLocked()) com.optionslab.ira.DayIndex.LOCKED else IraTools.dayIndexSay()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, dayIndexSaid)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "When do I usually check my P&L?" / "stop getting my P&L ready": the times Boss usually checks his P&L, his account
        // read ahead just before them while the market is open and Jarvis listens ([com.optionslab.ira.CheckTimes]; from his
        // routine log, keys and minutes only). His habit: named on an unlocked phone only; the undo works locked too, in
        // neutral words. A read ahead only - nothing said unasked, nothing learned acts. Not in IraGoldAlgo.
        val checkTimesReq = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.CheckTimes.asked(q) }.getOrNull() else null
        if (checkTimesReq != null) {
            val checkTimesSaid = if (checkTimesReq == com.optionslab.ira.CheckTimes.Request.RESET) IraTools.checkTimesReset(phoneLocked())
                else if (phoneLocked()) com.optionslab.ira.CheckTimes.LOCKED else IraTools.checkTimesSay()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, checkTimesSaid)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "What have you learned about my conditional orders?" / "stop mentioning my conditional orders": the conditional
        // instructions Boss keeps trying to give, the app's own alarm, stop loss or limit named once in the 15:35 wrap-up
        // ([com.optionslab.ira.CondNeeds]; kinds and minutes only). His own words' record: named on an unlocked phone only;
        // the undo works locked too, in neutral words. A pointer only - nothing is set, nothing learned acts. Not in IraGoldAlgo.
        val condNeedsReq = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.CondNeeds.asked(q) }.getOrNull() else null
        if (condNeedsReq != null) {
            val condNeedsSaid = if (condNeedsReq == com.optionslab.ira.CondNeeds.Request.RESET) IraTools.condNeedsReset(phoneLocked())
                else if (phoneLocked()) com.optionslab.ira.CondNeeds.LOCKED else IraTools.condNeedsSay()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, condNeedsSaid)).takeLast(MAX_MESSAGES)) }
            return true
        }
        return false
    }

    /**
     * [ask]'s question branches on the records and Boss's own setup: NewsMoves, TaxRecords, Learnings (and its undo),
     * PreMarket, Headroom, ArmFit, WeakLink, ArmChange, PnlGap, BookDecay, WhereIWin, TradesADay, AfterLoss, StopNoise, DayScore, RequestBook, LiquidityWhyNot, SoloDay, HeroDay, BotTrades, SwitchOff, SaidAbout, WeekAhead, WeeklyReview, LiquidityRecord, TomorrowPlan, OpeningRead, ZerodhaSession, OrderWhy, RelayHealth, StreamHealth, BatteryUse, WatchAsk - in [ask]'s order. True when one
     * took [q], answered exactly as before; each branch keeps its own guard (not [bundled], no order, no command).
     */
    private fun askedOfRecords(q: String, parsed: com.optionslab.ira.Question, bundled: Boolean, understood: Boolean): Boolean {
        // "How does the market react to RBI news?" / "do Fed headlines move Nifty?": how far the index moved in the hour after
        // each theme's headlines on this phone ([com.optionslab.ira.NewsMoves]) - timing only, never a cause, no forecast or
        // advice. Headlines and market data only (fine on a locked phone); not in the GOLD build (no news read there).
        val movesAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.NewsMoves.asked(q) }.getOrNull() else null
        if (movesAsk != null) {
            val said = runCatching {
                val usual = runCatching { com.optionslab.ira.NewsMoves.usual(movesAsk.market, histories[movesAsk.market]?.bars.orEmpty()) }.getOrNull()
                com.optionslab.ira.NewsMoves.say(IraTools.newsMoves(), movesAsk, com.optionslab.app.data.Market.today(), usual)
            }.getOrDefault("I couldn't read my news-and-moves record just now, Boss.")
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "Export my trades for tax": the financial year's trades in a CSV for Boss to share ([com.optionslab.ira.TaxRecords]).
        // His account, so only on an unlocked phone, and only on his yes (checked unlocked again then); never in IraGoldAlgo
        // (Jarvis only talks there). Facts only, not tax advice; nothing placed, changed or closed; no IDs or keys in the file.
        if (!bundled && parsed.order == null && parsed.command == null && runCatching { com.optionslab.ira.TaxRecords.exportAsked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (GOLD_ONLY_TALK) { reply(GOLD_TALK_ONLY); return true }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return true }
            scope.launch(Dispatchers.IO) {
                val today = com.optionslab.app.data.Market.today()
                val fy = com.optionslab.ira.TaxRecords.fy(q, today)
                val picked: Pair<String, List<com.optionslab.ira.TaxRecords.Trade>> = runCatching { IraTax.pick(fy, today) }.getOrElse { "Paper" to emptyList() }
                val ask = com.optionslab.ira.TaxRecords.offer(picked.first, picked.second.size, fy)
                if (ask == null) { reply(com.optionslab.ira.TaxRecords.nothing(fy)); return@launch }
                // (A plain label: the diagnostics log never holds the trades.)
                offer("export the year's trades to a CSV", "Boss, export your trades?", ask, suspend {
                    val c = app
                    when {
                        c == null -> "The app is not ready: nothing was exported."
                        phoneLocked() -> "Unlock the phone for that, Boss: nothing was exported."
                        else -> IraTax.export(c, fy, com.optionslab.app.data.Market.today())
                    }
                }, alwaysAsk = true)
            }
            return true
        }
        // "What have you learned this week?" / "what changed in how you work?" / "show me everything you've learned about me":
        // every learning store in one view, with when, why and each undo by voice ([com.optionslab.ira.Learnings]). Boss's own
        // words, routines and records only on an unlocked phone; nothing in it acts.
        val learnAsk = if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.Learnings.asked(q) }.getOrNull() else null
        if (learnAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val locked = phoneLocked()
            scope.launch(Dispatchers.IO) {
                reply(runCatching {
                    val plan = if (locked) null else runCatching { IraImprove.current() }.getOrNull()
                    // Boss's closed trades for where his small trades come from (his record: none read on a locked phone).
                    val smallBooks: List<com.optionslab.ira.SmallTrades.Book> = if (locked) emptyList() else runCatching { IraTools.smallBooks() }.getOrDefault(emptyList())
                    val now = LocalDateTime.now(IST).withSecond(0).withNano(0)
                    com.optionslab.ira.Learnings.say(com.optionslab.ira.Learnings.items(IraTools.learnings(plan, smallBooks), now), learnAsk, now.toLocalDate(), locked)
                }.getOrElse { "I couldn't read what I've learned just now, Boss." })
            }
            return true
        }
        // "Undo everything you learned this week": learned behaviour only (wordings and routines kept this week, the alert
        // count, his own goals for the week), put to Boss first with Confirm - only as said by him, on an unlocked phone,
        // never in IraGoldAlgo. Settings, the PIN, Live, AI trading, guards and the Google speech choice are never touched.
        if (com.optionslab.app.BuildConfig.JARVIS && !understood && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.Learnings.undoAsked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (GOLD_ONLY_TALK) { reply(GOLD_TALK_ONLY); return true }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return true }
            scope.launch(Dispatchers.IO) {
                val now = LocalDateTime.now(IST).withSecond(0).withNano(0)
                val u = runCatching { com.optionslab.ira.Learnings.undo(IraTools.learnings(IraImprove.current()), now) }.getOrNull()
                when {
                    u == null -> reply("I couldn't read what I've learned just now, Boss.")
                    u.empty -> reply(com.optionslab.ira.Learnings.NOTHING)
                    // A plain label (Boss's words stay out of the diagnostics log).
                    else -> offer("undo this week's learning", "Boss, undo this week's learning?", com.optionslab.ira.Learnings.offer(u), suspend {
                        if (phoneLocked()) "Unlock the phone for that, Boss: nothing was undone."
                        else {
                            val done = IraTools.undoLearnedWeek()
                            val goals = runCatching { IraImprove.dropWeek() }.getOrDefault(0)
                            com.optionslab.ira.Learnings.done(done.copy(goals = goals))
                        }
                    }, alwaysAsk = true)
                }
            }
            return true
        }
        // "Am I ready to trade?", "pre-market checklist" ([com.optionslab.ira.PreMarket]): the app's readiness and Boss's setup,
        // each pass or fail with the fix said as a step he takes himself; his margin and overnight positions only on an
        // unlocked phone. Reads only: nothing is placed, changed, closed or armed. (Not in IraGoldAlgo: no broker there.)
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.PreMarket.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val locked = phoneLocked()
            scope.launch { reply(runCatching { IraPreMarket.answer(app, locked) }.getOrElse { "I could not run the checklist just now, Boss." }) }
            return true
        }
        // "How close am I to my limits?", "how much can I still lose today?", "how many trades do I have left?"
        // ([com.optionslab.ira.Headroom]): each account against the guard's own limits, the nearest first. Boss's account, so
        // never on a locked phone; reads only - nothing is placed, changed or closed, and no limit is moved. (Not in IraGoldAlgo.)
        val roomAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.Headroom.asked(q) }.getOrNull() else null
        if (roomAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return true }
            scope.launch(Dispatchers.IO) {
                val roomSaid = runCatching { IraHeadroom.answer(roomAsk) }.getOrElse { "I could not read your limits just now, Boss." }
                // The Zerodha reads take seconds: locked by now, his account is not said (it may be overheard).
                reply(if (phoneLocked()) "Unlock the phone for that, Boss." else roomSaid)
            }
            return true
        }
        // "Which of my arms suits today?", "how do my arms do on days like today?", "aaj ke din kaun sa bot suit karta hai"
        // ([com.optionslab.ira.ArmFit]): today's BankNifty start banded (the gap's size, the first hour's range among the past
        // sessions' thirds, India VIX) and each armed arm's backtest and paper days split on each band - today's band against
        // the rest, in rupees a day and days up. Boss's account, so never on a locked phone; facts beside facts, never a
        // verdict, a forecast or advice - nothing is armed, stopped, placed or closed. (Before ArmDay; the daily regime, "which
        // arms suit this market", stays the account's. Not in IraGoldAlgo.)
        val armFitAsk = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.ArmFit.asked(q) }.getOrDefault(false)
        if (armFitAsk) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.ArmFit.LOCKED); return true }
            val bankNifty = histories[IraMarket.BANKNIFTY]?.bars.orEmpty()
            val vix = histories[IraMarket.VIX]?.bars.orEmpty()
            scope.launch(Dispatchers.IO) { reply(runCatching { IraBots.armFit(bankNifty, vix) }.getOrElse { "I could not set your arms' records beside today just now, Boss." }) }
            return true
        }
        // "What's the weakest link in my setup?", "what usually goes wrong in my paper trades?", "mere trades mein sabse kamzor
        // kadi kya hai" ([com.optionslab.ira.WeakLink]): the arms' last closed paper trades read as facts with counts - the exit
        // mix (stops, targets, the profit lock, the square-off; stops within 5 minutes of entry), and each arm, the day's opening
        // gap and the time of entry against the rest, the worst ranked with a labelled conditional. Boss's account, so never on
        // a locked phone; facts and arithmetic, never a verdict or advice - nothing is armed, stopped, placed or closed. (Before
        // ArmDay, which keeps one day's loss. Not in IraGoldAlgo.)
        val weakLinkAsk = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.WeakLink.asked(q) }.getOrDefault(false)
        if (weakLinkAsk) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.WeakLink.LOCKED); return true }
            val weakBankNifty = histories[IraMarket.BANKNIFTY]?.bars.orEmpty()
            scope.launch(Dispatchers.IO) { reply(runCatching { IraBots.weakLink(weakBankNifty) }.getOrElse { "I could not read your arms' paper trades just now, Boss." }) }
            return true
        }
        // "What's changed in my arms' results this week vs last?", "how are my bots doing this week compared to last week?", "mere
        // bots ka is hafte vs pichle hafte" ([com.optionslab.ira.ArmChange]): each arm's closed paper trades this week so far
        // against last week - trades, win rate, net after charges, the average loss - all arms together, and the arm whose net
        // changed most named. Boss's account, so never on a locked phone; facts and arithmetic, never a verdict or advice -
        // nothing is armed, stopped, placed or closed. (Before ArmDay, which keeps one day's loss. Not in IraGoldAlgo.)
        val armChangeAsk = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.ArmChange.asked(q) }.getOrDefault(false)
        if (armChangeAsk) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.ArmChange.LOCKED); return true }
            scope.launch(Dispatchers.IO) { reply(runCatching { IraBots.armChange() }.getOrElse { "I could not read your arms' paper weeks just now, Boss." }) }
            return true
        }
        // "Why is my P&L different from what I expected?", "break down my P&L", "realised vs unrealised", "mera p&l expected se alag
        // kyun hai" ([com.optionslab.ira.PnlGap]): today's paper P&L split into booked and open, the charges, and the slippage of
        // orders that had an intended level (never guessed for a market order), the biggest contributor named. Boss's account,
        // so never on a locked phone; facts only - nothing is placed, changed or closed. (Before ArmDay. Not in IraGoldAlgo.)
        val pnlGapAsk = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.PnlGap.asked(q) }.getOrDefault(false)
        if (pnlGapAsk) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.PnlGap.LOCKED); return true }
            scope.launch(Dispatchers.IO) { reply(runCatching { IraBots.pnlGap() }.getOrElse { "I could not read today's paper book just now, Boss." }) }
            return true
        }
        // "Why did my strategy lose today?", "why did ORB lose?", "what went wrong with Range Fade today?", "ORB ka aaj loss kyun
        // hua" ([com.optionslab.ira.ArmDay]): today's arm trades set beside the index each traded - the gap, the opening range and
        // its breaks, the index through each hold and after the exit - and each losing trade's shape from those facts (a break
        // that came back inside, a move that came back past the entry, an index that never went its way). Boss's account, so
        // never on a locked phone; facts only, never a cause proven, a forecast or advice - nothing is armed, stopped, placed
        // or closed. (Not in IraGoldAlgo.)
        val armDayAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.ArmDay.asked(q) }.getOrNull() else null
        if (armDayAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.ArmDay.LOCKED); return true }
            val bars = listOf(IraMarket.BANKNIFTY, IraMarket.FINNIFTY).associate { m -> m.name to histories[m]?.bars.orEmpty() }
            scope.launch(Dispatchers.IO) { reply(runCatching { IraBots.armDay(armDayAsk, bars) }.getOrElse { "I could not set your bots' day beside the index just now, Boss." }) }
            return true
        }
        // "What's my theta?", "how much am I losing to time decay?", "is theta working for me?", "mera theta kitna hai"
        // ([com.optionslab.ira.BookDecay]): the whole book's time decay from each open option's theta now - rupees a day for each
        // account apart, what the bought legs pay and the sold legs collect, the legs that weigh most, what it comes to by the
        // next session over a weekend or a holiday, and the time value left in a leg that expires today. Boss's account, so
        // never on a locked phone; a rough figure, never a forecast or advice - nothing is placed, changed or closed. (Before
        // NetLean, whose "am I long or short" would take "am I long or short theta". Not in IraGoldAlgo.)
        val bookDecayAsk = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.BookDecay.asked(q) }.getOrDefault(false)
        if (bookDecayAsk) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.BookDecay.LOCKED); return true }
            scope.launch(Dispatchers.IO) { reply(runCatching { IraCoach.bookDecay() }.getOrElse { "I could not read your positions' time decay just now, Boss." }) }
            return true
        }
        // "Where do I make my money?", "am I better at calls or puts?", "do I make more buying or selling?", "which index do I
        // make money on?", "call mein zyada kamata hoon ya put mein" ([com.optionslab.ira.WhereIWin]): his own closed trades split
        // by index, by calls / puts / futures, by bought first against sold first, and for options the four together - each
        // account apart, over the span he names, the part asked first. His record, so never on a locked phone; facts only,
        // never a forecast or what to trade - nothing is placed, changed or armed. (Before NetLean. Not in IraGoldAlgo.)
        val whereIWinCut = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.WhereIWin.asked(q) }.getOrNull() else null
        if (whereIWinCut != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.WhereIWin.LOCKED); return true }
            scope.launch(Dispatchers.IO) { reply(runCatching { IraCoach.whereIWin(q, whereIWinCut) }.getOrElse { "I could not split your trades just now, Boss." }) }
            return true
        }
        // "Do I do better when I trade less?", "how many trades a day work best for me?", "how does my first trade of the day
        // do?", "din ka pehla trade kaisa jaata hai" ([com.optionslab.ira.TradesADay]): his own trading days grouped by how
        // many trades he opened on them, and his trades by their place in the day - each account apart, over the span he
        // names. His record, so never on a locked phone; facts only, never a number to take or a limit set - nothing is
        // placed, changed or armed. (After WhereIWin, before NetLean. Not in IraGoldAlgo.)
        val tradesADayPart = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.TradesADay.asked(q) }.getOrNull() else null
        if (tradesADayPart != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.TradesADay.LOCKED); return true }
            scope.launch(Dispatchers.IO) { reply(runCatching { IraCoach.tradesADay(q, tradesADayPart) }.getOrElse { "I could not count your trades by day just now, Boss." }) }
            return true
        }
        // "How do I trade after a loss?", "do I revenge trade?", "do I get careless after a win?", "loss ke baad mera agla
        // trade kaisa jaata hai" ([com.optionslab.ira.AfterLoss]): his own trades placed by the trade that closed just before
        // them the same day - after a loss against after a win, soon after a loss, after two losses, how long he waited -
        // each account apart, over the span he names. His record, so never on a locked phone; facts only, never a rule set
        // or a trade blocked - nothing is placed, changed or armed. (After TradesADay, before NetLean. Not in IraGoldAlgo.)
        val afterLossSide = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.AfterLoss.asked(q) }.getOrNull() else null
        if (afterLossSide != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.AfterLoss.LOCKED); return true }
            scope.launch(Dispatchers.IO) { reply(runCatching { IraCoach.afterLoss(q, afterLossSide) }.getOrElse { "I could not read your trades after a loss just now, Boss." }) }
            return true
        }
        // "Is my stop too tight?", "will normal noise hit my stop?", "mera stop bahut tight hai kya" ([com.optionslab.ira.StopNoise]):
        // each open bought option's stop - its distance in premium and, by its delta, roughly in index points - against how
        // often the index moved that far against it within 15 and 30 minutes of a quarter-hour start in the whole past
        // sessions on the phone. Facts only, never a judgement or advice on the stop. His positions, so never on a locked
        // phone; nothing is placed, changed or cancelled. (After AfterLoss, before RequestBook. Not in IraGoldAlgo.)
        val stopNoiseAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.StopNoise.asked(q) }.getOrDefault(false) else false
        if (stopNoiseAsk) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.StopNoise.LOCKED); return true }
            scope.launch(Dispatchers.IO) { reply(runCatching { stopNoise() }.getOrElse { "I could not set your stops against the index's swings just now, Boss." }) }
            return true
        }
        // "My scorecard today", "did I trade against the trend today?", "how long did I hold my trades today?", "aaj ka scorecard"
        // ([com.optionslab.ira.DayScore]): today's own closed trades, each against the index's move in the 15 minutes before
        // its entry, his usual hold and the option's best price in the 15 minutes after its exit. Facts only, never advice.
        // His trades, so never on a locked phone; nothing is placed, changed or cancelled. (After StopNoise, before
        // RequestBook. Not in IraGoldAlgo.)
        val dayScoreAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.DayScore.asked(q) }.getOrDefault(false) else false
        if (dayScoreAsk) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.DayScore.LOCKED); return true }
            scope.launch(Dispatchers.IO) {
                val scoreSaid = runCatching { if (phoneLocked()) com.optionslab.ira.DayScore.LOCKED else IraJournal.dayScore() }
                    .getOrElse { "I could not score today's trades just now, Boss." }
                reply(scoreSaid)
            }
            return true
        }
        // "What requests are waiting?", "anything waiting for my approval?", "what did I approve today?", "koi request hai",
        // "maine aaj kya approve kiya" ([com.optionslab.ira.RequestBook]): the Requests panel's own read-only lists said -
        // those waiting now (what each would do, where, when it lapses, whether a yes needs his fingerprint) or the last few
        // answered or ended. Words only: nothing is approved, declined or changed here. On a locked phone only how many wait;
        // a list that could not be read is said so, never as none. (After AfterLoss, before NetLean. IraGoldAlgo: only talk.)
        val requestBookAsk = if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.RequestBook.asked(q) }.getOrNull() else null
        if (requestBookAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (GOLD_ONLY_TALK) { reply(com.optionslab.ira.Requests.GOLD); return true }
            val requestBookNow = System.currentTimeMillis()
            val requestBookWaiting = runCatching { com.optionslab.ira.Requests.shown(requestsOf(_state.value), requestBookNow, gold = false) }.getOrNull()
            if (requestBookWaiting == null) { reply(com.optionslab.ira.RequestBook.READ_FAILED); return true }
            if (phoneLocked()) { reply(com.optionslab.ira.RequestBook.locked(requestBookWaiting.size)); return true }
            reply(runCatching {
                com.optionslab.ira.RequestBook.answer(requestBookAsk, requestBookWaiting, _recent.value, requestBookNow, com.optionslab.app.data.Market.now().zone)
            }.getOrElse { com.optionslab.ira.RequestBook.READ_FAILED })
            return true
        }
        // "Am I net long or short?", "which way am I leaning?", "what's my net delta?", "do my bots contradict each other right
        // now?", "main long hoon ya short" ([com.optionslab.ira.NetLean]): the open book by index from each leg's delta now -
        // the net rupees a point and which way it leans, who holds which side (each arm, other automations, his own Paper and
        // Zerodha trades) and how much offsets, and a 1% move each way as a labelled conditional. Boss's account, so never on
        // a locked phone; facts, never a forecast or advice - nothing is placed, changed or closed. (Not in IraGoldAlgo.)
        val netLeanAsk = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.NetLean.asked(q) }.getOrDefault(false)
        if (netLeanAsk) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.NetLean.LOCKED); return true }
            val market = runCatching { com.optionslab.ira.NetLean.market(q) }.getOrNull()
            scope.launch(Dispatchers.IO) { reply(runCatching { IraBots.netLean(market) }.getOrElse { "I could not read which way your book leans just now, Boss." }) }
            return true
        }
        // "What expires tomorrow?", "which of my positions expire tomorrow?", "kal kya expire ho raha hai"
        // ([com.optionslab.ira.ExpiryEve]): the wrap-up's expiry-eve checklist asked for - his open Paper and Zerodha legs that
        // expire on the next trading day, how far in or out of the money, the product and tomorrow's 15:05 square-off - or that
        // nothing does. Boss's account, so never on a locked phone; facts only - nothing is placed, changed or closed. (Not in
        // IraGoldAlgo.)
        val expiryEveAsk = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.ExpiryEve.asked(q) }.getOrDefault(false)
        if (expiryEveAsk) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.ExpiryEve.LOCKED); return true }
            scope.launch(Dispatchers.IO) {
                val said = runCatching {
                    val mk = com.optionslab.app.data.Market
                    val next = com.optionslab.ira.ExpiryEve.nextTradingDay(mk.today()) { mk.isTradingDay(it) }
                    // Whether Zerodha's positions were actually read (round 23): a failed or timed-out read is never "both read".
                    val r = IraCoach.expiryEveRead()
                    com.optionslab.ira.ExpiryEve.answer(r?.line, next, r?.zerodhaRead ?: false, r?.loggedIn ?: com.optionslab.app.data.Broker.loggedIn, r?.undated ?: 0)
                }.getOrElse { "I could not read what of yours expires tomorrow just now, Boss." }
                reply(said)
            }
            return true
        }
        // "What do I need to do before tomorrow?", "checklist for tomorrow", "kal se pehle kya karna hai"
        // ([com.optionslab.ira.BeforeTomorrow]): one checklist for the next trading day - the Zerodha login, his legs that
        // expire then (saying when Zerodha wasn't read), the arms armed now with their paper records, the static IP and the
        // relay, the battery setting and the backup's age. Facts only: nothing is placed, changed, closed, armed or disarmed;
        // each step is his. His legs and the records only on an unlocked phone. (Not in IraGoldAlgo.)
        val beforeTomorrowAsk = com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.BeforeTomorrow.asked(q) }.getOrDefault(false)
        if (beforeTomorrowAsk) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val locked = phoneLocked()
            scope.launch(Dispatchers.IO) {
                reply(runCatching { IraPreMarket.beforeTomorrow(app, locked) }.getOrElse { "I could not put tomorrow's checklist together just now, Boss." })
            }
            return true
        }
        // "Why no liquidity trade today?", "why didn't liquidity trade", "liquidity ne trade kyu nahi liya", "what is liquidity
        // waiting for" ([com.optionslab.ira.LiquidityWhyNot], [IraLiquidity.whyNot]): from Liquidity 15+5's own records of the
        // day, book by book - its switch, the day's stop and its entry hours, the bars it decided on and each break it did not
        // take and why (no room, refused, unapproved, data missing), the trades it did take, and what would trigger its next
        // entry and how far it is. Before BotTrades, which keeps "why did the liquidity bot exit" and today's trades. Boss's
        // paper trades are in it, so never on a locked phone; reads only - nothing is armed, placed, closed or changed.
        // (Not in IraGoldAlgo.)
        val liqWhyNotAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.LiquidityWhyNot.asked(q) }.getOrNull() else null
        if (liqWhyNotAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.LiquidityWhyNot.LOCKED); return true }
            scope.launch(Dispatchers.IO) {
                reply(runCatching { IraLiquidity.whyNot(liqWhyNotAsk) }.getOrElse { "I could not read Liquidity 15+5's day just now, Boss." })
            }
            return true
        }
        // "What did Solo do today?", "why didn't Solo trade", "solo ne aaj kya kiya", "how did Solo decide"
        // ([com.optionslab.ira.SoloDay], [IraSolo.day]): from Solo (midday)'s own records of the day - its switch; before 12:00
        // what it will read and its early reads; at 12:00 each index's read against the others, what set an index aside (its
        // expiry day, another automatic position, a thin strike, no contract or price, LATE after 12:03), the pick with its
        // entry, stop and exits, or why no trade; then its forward test and the research line. Before BotTrades and Thinking
        // ("why didn't solo take that trade" stays Thinking's, "how is solo doing" Solo's status). Its paper trades are in it,
        // so never on a locked phone; reads only - nothing is switched, placed, closed or changed. (Not in IraGoldAlgo.)
        val soloDayAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.SoloDay.asked(q) }.getOrNull() else null
        if (soloDayAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.SoloDay.LOCKED); return true }
            scope.launch(Dispatchers.IO) {
                reply(runCatching { IraSolo.day(soloDayAsk) }.getOrElse { "I could not read Solo's day just now, Boss." })
            }
            return true
        }
        // "What did hero do today?", "why no hero trade", "is today a hero day", "hero ne aaj kya kiya", "when is the next hero
        // day" ([com.optionslab.ira.HeroDay], [IraHero.day]): from the expiry-day Hero arm's own records - whether today is a
        // NIFTY expiry day (else the next), its switch, its window, what it read (the ATM straddle, its legs and low, NIFTY's
        // 15-minute move), each minute's verdict, the trade with its F07 exits and net or why none, then its paper record and
        // that it is paper only and not proven. Before BotTrades ("hero status", its switch, lots, budget, exits and record keep
        // theirs). Its paper trades are in it, so never on a locked phone; reads only - nothing is armed, placed, closed or
        // changed. (Not in IraGoldAlgo.)
        val heroDayAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.HeroDay.asked(q) }.getOrNull() else null
        if (heroDayAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.HeroDay.LOCKED); return true }
            scope.launch(Dispatchers.IO) {
                reply(runCatching { IraHero.day(heroDayAsk) }.getOrElse { "I could not read Hero's day just now, Boss." })
            }
            return true
        }
        // "Explain my bots' trades today", "why did ORB take that trade?", "did my bots follow their rules?", "mere bots ne aaj kya
        // kiya" ([com.optionslab.ira.BotTrades]): each of today's arm trades chained from its signal (the opening range and the
        // break bar, the sweep, the fade, the liquidity level) to its exit and reason and its points and rupees, against the
        // arm's written rule and tested record; then what does not fit (armed though its test lost in both years, opposite
        // sides at once, an exit earlier than its rule). Boss's account, so never on a locked phone; facts only - nothing is
        // armed, stopped, placed or closed. (Not in IraGoldAlgo.)
        val botTradesAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.BotTrades.asked(q) }.getOrNull() else null
        if (botTradesAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.BotTrades.LOCKED); return true }
            val bankNifty = histories[IraMarket.BANKNIFTY]?.bars.orEmpty()
            scope.launch(Dispatchers.IO) { reply(runCatching { IraBots.tradesToday(botTradesAsk, bankNifty) }.getOrElse { "I could not read your bots' trades just now, Boss." }) }
            return true
        }
        // "What should I switch off?", "which arms lost in both the test and on paper?", "should I disarm ORB Fresh?", "kaun sa
        // bot band karun" ([com.optionslab.ira.SwitchOff]): each arm's two-year BankNifty test beside its own paper record - the
        // arms that lost in both (armed first), those that lost in one, and where the switch is (Trade, then Strategies). Facts,
        // never advice. Nothing is switched here: one armed arm that lost in both may be put to Boss as a yes or no (always
        // asked, even with automatic stops), and "stop <arm>" stays the command that asks him to confirm. Boss's account, so
        // never on a locked phone. (Not in IraGoldAlgo.)
        val switchOffAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.SwitchOff.asked(q) }.getOrNull() else null
        if (switchOffAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.SwitchOff.LOCKED); return true }
            scope.launch(Dispatchers.IO) {
                val a = runCatching { IraBots.switchOff(switchOffAsk) }.getOrNull()
                reply(a?.first?.text ?: "I could not read your arms' records just now, Boss.")
                a?.second?.let { first -> runCatching { IraBots.offerSwitchOff(first) } }
            }
            return true
        }
        // "What did I say about expiry?", "did I note anything about the hammer last week?", "maine expiry ke baare mein kya
        // kaha tha" ([com.optionslab.ira.SaidAbout]): Boss's own notes, trade notes and journal answers searched and read back
        // as he said them. His words, so never on a locked phone; read back only - nothing is taken as a rule and nothing
        // acts. (Not in IraGoldAlgo.)
        val saidAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.SaidAbout.asked(q) }.getOrNull() else null
        if (saidAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return true }
            scope.launch(Dispatchers.IO) { reply(runCatching { IraSaidAbout.answer(saidAsk) }.getOrElse { "I could not read your notes just now, Boss." }) }
            return true
        }
        // "What does this week look like?", "is this an expiry week?", "plan for next week", "is hafte kya hai"
        // ([com.optionslab.ira.WeekAhead]): the week's sessions, expiries (weekly, monthly, moved by a holiday), holidays, long
        // breaks and events from the exchange calendar and the loaded contracts. Calendar facts only, nothing acts; the events
        // Boss added are said only on an unlocked phone (else their count). (Before "when is the next expiry" and the holiday
        // answers, which keep their one-line questions.)
        val weekAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.WeekAhead.asked(q) }.getOrNull() else null
        if (weekAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val unlocked = !phoneLocked()
            scope.launch(Dispatchers.IO) {
                val said = runCatching {
                    val today = com.optionslab.app.data.Market.today()
                    val book = com.optionslab.app.data.Holidays.book()
                    val exp = com.optionslab.ira.WeekAhead.INDICES.associateWith { m ->
                        runCatching { com.optionslab.app.data.Market.upcomingExpiries(m.name) }.getOrDefault(emptyList()) }
                    com.optionslab.ira.WeekAhead.answer(weekAsk, today, com.optionslab.app.data.Market.minuteNow() >= 15 * 60 + 30,
                        { d -> com.optionslab.app.data.Market.isTradingDay(d) },
                        { d -> if (book.holiday(d)) book.upcoming(d).firstOrNull { it.first == d }?.second ?: "a market holiday" else null },
                        exp, com.optionslab.ira.Events.builtIn(today, today.plusDays(21)),
                        runCatching { IraEvents.owner() }.getOrDefault(emptyList()), unlocked)
                }.getOrElse { "I could not read the calendar just now, Boss." }
                reply(said)
            }
            return true
        }
        // "Weekly review", "is hafte ka review", "how did this week go", "last week's review" ([com.optionslab.ira.WeeklyReview]):
        // Jarvis's review of the week in three sentences - the paper P&L, each running strategy against its backtest and the
        // one line to watch - the whole of it on the Ira page's card ([IraWeekly]). Before the week's review is made, the week
        // so far. On a locked phone no rupee figure is said. Reads only; nothing acts. ("My weekly review" / "how did my week
        // go" stay the review of Boss's own trades.) Not in IraGoldAlgo.
        val weeklyAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.WeeklyReview.asked(q) }.getOrNull() else null
        if (weeklyAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val weeklyLocked = phoneLocked()
            scope.launch(Dispatchers.IO) {
                reply(runCatching { IraWeekly.answer(weeklyAsk, weeklyLocked) }.getOrElse { "I could not put the week's review together just now, Boss." })
            }
            return true
        }
        // "How did liquidity do this week", "liquidity on 3 Oct", "liquidity last 10 trades", "which index works best for
        // liquidity", "liquidity win streak", "is liquidity on track", "liquidity ne is hafte kaisa kiya"
        // ([com.optionslab.ira.LiquidityRecord]): Liquidity 15+5's paper record over time from the arm's own book - trades, wins
        // and losses, net after charges (in all and per lot), best and worst, by index, by exit reason, the streak - against
        // its backtest (the live-vs-backtest verdict) and the losing-trades study; too few trades said plainly. Boss's paper
        // record, so never on a locked phone; reads only - nothing is armed, stopped, placed or closed. (Not in IraGoldAlgo.)
        val liqRecordAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.LiquidityRecord.asked(q) }.getOrNull() else null
        if (liqRecordAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.LiquidityRecord.LOCKED); return true }
            scope.launch(Dispatchers.IO) {
                reply(runCatching { IraBots.liquidityRecord(liqRecordAsk) }.getOrElse { "I could not read Liquidity 15+5's book just now, Boss." })
            }
            return true
        }
        // "What's the plan for tomorrow", "tomorrow ka plan", "kal ka plan kya hai", "prepare me for tomorrow"
        // ([com.optionslab.ira.TomorrowPlan], [IraTomorrow]): the next session (holidays skipped) and its expiries, Liquidity
        // 15+5's paper day, the levels it carries into tomorrow, its switch and lots, Solo's and Hero's, the events, the FIIs
        // and the big-move read at the close - facts only, each line only when its data is there. Boss's paper trades are in
        // it, so never on a locked phone; reads only - nothing is armed, placed, closed or changed. (Not in IraGoldAlgo.)
        val tomorrowAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.TomorrowPlan.asked(q) }.getOrDefault(false) else false
        if (tomorrowAsk) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply(com.optionslab.ira.TomorrowPlan.LOCKED); return true }
            scope.launch(Dispatchers.IO) {
                reply(runCatching { IraTomorrow.answer() }.getOrElse { "I could not put tomorrow's plan together just now, Boss." })
            }
            return true
        }
        // "How did the market open", "opening read", "market kaisa khula", "where did we open vs the levels"
        // ([com.optionslab.ira.OpeningRead], [IraOpening]): Nifty's, BankNifty's and FinNifty's gap (and what GIFT Nifty
        // pointed to), the open against Liquidity 15+5's levels, the first 5-minute candle against the usual, the arm's switch,
        // lots and first trigger, and the big-move line - the market's facts, at most six lines. Reads only - nothing is
        // armed, placed, closed or changed. (Not in IraGoldAlgo: no Liquidity arm, no NSE feeds.)
        val openingAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.OpeningRead.asked(q) }.getOrDefault(false) else false
        if (openingAsk) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            scope.launch(Dispatchers.IO) {
                reply(runCatching { IraOpening.answer() }.getOrElse { "I could not read the open just now, Boss." })
            }
            return true
        }
        // "Why was I logged out of Zerodha?", "why did Kite log me out?", "when does my Zerodha session end?", "zerodha se
        // logout kyun hua" ([com.optionslab.ira.ZerodhaSession]): from the diagnostics diary (each TokenException with its path
        // and Kite's message, the day's login, Boss's own logout - no key or token is ever in it), the first sentence saying
        // what happened. His broker session, so never on a locked phone; reads only - nothing logs in, out or changes (logging
        // in stays Boss's own step on the Zerodha screen, never by voice). (Not in IraGoldAlgo: no broker there.)
        val kiteAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.ZerodhaSession.asked(q) }.getOrNull() else null
        if (kiteAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return true }
            scope.launch(Dispatchers.IO) {
                reply(runCatching {
                    val b = com.optionslab.app.data.Broker
                    com.optionslab.ira.ZerodhaSession.answer(kiteAsk, com.optionslab.app.data.Diag.lines(), LocalDateTime.now(IST),
                        b.linked, b.loggedIn, b.expiresAt()?.withZoneSameInstant(IST)?.toLocalDateTime())
                }.getOrElse { "I could not read the Zerodha record just now, Boss." })
            }
            return true
        }
        // "Why was my last order cancelled?", "why did my order get rejected?", "why was the BankNifty order cancelled?", "mera
        // order cancel kyun hua", "what happened to my last order?" ([com.optionslab.ira.OrderWhy]): the latest cancelled or
        // rejected order today (or the one named) and why - the reason the app noted when it cancelled it, the broker's or paper
        // book's own message, its leg and what filled beside it. Boss's account, so never on a locked phone; Zerodha's orders
        // only with a session. Reads only: nothing is placed, changed or cancelled ("cancel my last order" stays its command).
        val whyOrder = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.OrderWhy.asked(q) }.getOrNull() else null
        if (whyOrder != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return true }
            scope.launch(Dispatchers.IO) { reply(runCatching { IraOrderWhy.answer(whyOrder) }.getOrElse { "I could not read your orders just now, Boss." }) }
            return true
        }
        // "Why is my relay failing?", "is my static IP working?", "relay kyun nahi chal raha", "can I trade live right now?"
        // ([com.optionslab.ira.RelayHealth]): from the diagnostics diary (the relay's connects and failed connects, the Zerodha
        // calls that failed through it, the static-IP checks) and the app's state as it stands - when it last connected, how
        // long and how often it has failed, the failure in plain words and the steps Boss takes himself; live as facts only.
        // His setup and broker, so never on a locked phone. Reads only: nothing is switched, tested or connected here (the
        // relay's own retries stay as they are; Connect & test stays his tap); no host, user or key is said, only the
        // registered IP the app shows. (Not in IraGoldAlgo: no broker or relay there.)
        val relayAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.RelayHealth.asked(q) }.getOrNull() else null
        if (relayAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return true }
            scope.launch(Dispatchers.IO) {
                reply(runCatching {
                    val r = com.optionslab.app.data.Relay
                    val b = com.optionslab.app.data.Broker
                    val st = com.optionslab.app.data.AppSettings.load()
                    val setup = com.optionslab.ira.RelayHealth.Setup(
                        relayOn = r.enabled, relaySet = r.host != null, connected = runCatching { r.connected }.getOrDefault(false),
                        registeredIp = com.optionslab.app.data.StaticIp.registered, live = st.live, realOrders = st.allowRealOrders,
                        linked = b.linked, loggedIn = b.loggedIn, kill = st.guardKill)
                    com.optionslab.ira.RelayHealth.answer(relayAsk, com.optionslab.app.data.Diag.lines(), LocalDateTime.now(IST), setup)
                }.getOrElse { "I could not read the relay record just now, Boss." })
            }
            return true
        }
        // "Why is live data dropping?", "why does the price stream keep disconnecting?", "stream kyun toot raha hai"
        // ([com.optionslab.ira.StreamHealth]): from the diagnostics diary's [stream] lines - today's drops of Zerodha's live price
        // stream, the last one's time and reason in plain words, the causes counted, and whether the order watch (which keeps
        // the app running in the background) is up. Reads only: the stream reconnects by itself and nothing is switched here;
        // no URL, key or token is ever in those lines. His setup and broker, so never on a locked phone. (Not in IraGoldAlgo.)
        if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD &&
            runCatching { com.optionslab.ira.StreamHealth.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return true }
            scope.launch(Dispatchers.IO) {
                reply(runCatching {
                    val watch = runCatching { System.currentTimeMillis() - com.optionslab.app.work.Heartbeat.last() < 180_000 }.getOrNull()
                    com.optionslab.ira.StreamHealth.answer(com.optionslab.app.data.Diag.lines(), com.optionslab.app.data.Market.now().toLocalDateTime(),
                        com.optionslab.app.data.KiteStream.status.value.name, watch)
                }.getOrElse { "I could not read the stream record just now, Boss." })
            }
            return true
        }
        // "Why is the app using battery?", "battery kyun kha raha hai" ([com.optionslab.ira.BatteryUse]; battery round 1): what of
        // the app runs in the background now, the biggest cost first - listening, the live stream, the order watch (named, never
        // offered slower: it guards stops, targets and exits), the AI model - and the battery saver for listening's switch. The
        // app's own state only (no amount, no position, no symbol); on a locked phone without the watch's pace or the stream's
        // instrument count (both hint at a position). Reads only: nothing is switched.
        if (!bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.BatteryUse.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val lockedNow = phoneLocked()
            scope.launch(Dispatchers.IO) {
                // A locked phone: no watch pace, no instrument count (either hints that something is held).
                reply(runCatching { com.optionslab.ira.BatteryUse.answer(com.optionslab.app.work.BatteryNow.snapshot(app), lockedNow) }
                    .getOrElse { "I could not read what runs in the background just now, Boss." })
            }
            return true
        }
        // "Is the order watch running?", "why did the watch get stuck?", "battery setting kya hai", "kya mera phone app ko rok
        // raha hai" ([com.optionslab.ira.WatchAsk]): from what the diagnostics' "Order watch:" line reads (the last finished
        // check, the service's pulse, the step it waits on, IraAlgo's battery setting) and today's [watch] diary lines. Reads
        // only: nothing is started, restarted or stopped, and the battery setting stays Boss's own tap ("stop the order watch"
        // stays a command). His setup, so never on a locked phone. (Not in IraGoldAlgo.)
        val watchAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.WatchAsk.asked(q) }.getOrNull() else null
        if (watchAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return true }
            scope.launch(Dispatchers.IO) {
                reply(runCatching {
                    val hb = com.optionslab.app.work.Heartbeat
                    val now = System.currentTimeMillis()
                    val restricted = app?.let { hb.batteryRestricted(it) }
                    com.optionslab.ira.WatchAsk.answer(watchAsk, now, hb.last(), hb.alivePulse, hb.busy, hb.busySince, restricted,
                        com.optionslab.app.data.Market.isOpen(), com.optionslab.app.data.Diag.lines(),
                        com.optionslab.app.data.Market.now().toLocalDateTime(), com.optionslab.app.data.Market.now().zone)
                }.getOrElse { "I could not read the order watch just now, Boss." })
            }
            return true
        }
        return false
    }

    /**
     * [ask]'s question branches on what to ask, how fresh the data is, what the phone has no data for and Jarvis's own
     * reasons: Tour, WhatsNew, DataAge, MarketRecord, MorningCues, Honest, Thinking (SelfWhy inside it) - in [ask]'s order. True when one
     * took [q], answered exactly as before; each branch keeps its own guard (not [bundled], no order, no command).
     */
    private fun askedOfJarvis(q: String, parsed: com.optionslab.ira.Question, bundled: Boolean, understood: Boolean): Boolean {
        // "What can I ask you?", "what should I ask now?", "main kya pooch sakta hoon" ([com.optionslab.ira.Tour]): five questions
        // worth asking for the part of the day (before the open, market hours, after the close, a day with no session) - two
        // fixed, three turning with the date. It only names questions (each one Jarvis answers as said), holds nothing of the
        // account and acts on nothing, so it is the same on a locked phone. "What can you do" keeps its full list.
        // (Not in IraGoldAlgo: the questions named are the indices' and the account's.)
        if (!com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.Tour.asked(q) }.getOrDefault(false)) {
            val said = runCatching {
                val today = com.optionslab.app.data.Market.today()
                com.optionslab.ira.Tour.answer(com.optionslab.ira.Tour.part(com.optionslab.app.data.Market.minuteNow(),
                    com.optionslab.app.data.Market.isTradingDay(today)), today) +
                    // Where every question is, by topic (the "What can I ask" sheet; 06 Oct): one line, nothing else changes.
                    " " + com.optionslab.ira.AskGuide.POINTER + "."
            }.getOrDefault("Ask me how the market is doing, what matters right now, or \"what can you do\" for everything, Boss.")
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return true
        }
        // "What's new?", "what changed in the app?", "naya kya hai" ([com.optionslab.ira.WhatsNew]): the newest entries of the app's
        // own changelog, newest first, each with a question to try or where to find it, and where the full list is (Settings ->
        // What's new). It holds nothing of the account and acts on nothing, so it is the same on a locked phone. "What's new in
        // the market", "any news" and "what's changed since I last asked" keep their routes. (Not in IraGoldAlgo.)
        if (!com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.WhatsNew.asked(q) }.getOrDefault(false)) {
            val said = runCatching {
                com.optionslab.ira.WhatsNew.answer(com.optionslab.ira.WhatsNew.forBuild(gold = false, jarvis = com.optionslab.app.BuildConfig.JARVIS))
            }.getOrDefault("What's new in the app, and where to find each change, is in Settings under What's new, Boss.")
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "Is your data fresh?" / "how old are your prices?": how old his prices, candles, news and chain are, and today's
        // record ([com.optionslab.ira.DataAge]; ages only, nothing about the account).
        if (!GOLD_ONLY_TALK && parsed.order == null && parsed.command == null && !bundled && runCatching { com.optionslab.ira.DataAge.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val markets = parsed.markets
            scope.launch { reply(freshAsked(markets)) }
            return true
        }
        // "How much market data have we recorded?", "is the market recorder running?", "kitna market data record hua hai": what the
        // market recorder keeps on this phone - days, storage, the last write, the gaps ([com.optionslab.ira.MarketRecord]). His own
        // data, read from the folder; nothing is recorded, deleted or exported from here. Not in IraGoldAlgo (it records none).
        if (!com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.MarketRecord.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            scope.launch(Dispatchers.IO) {
                reply(runCatching { com.optionslab.app.data.MarketRecorder.answer() }.getOrElse { "I could not read the market recorder just now, Boss." })
            }
            return true
        }
        // "GIFT Nifty kya bol raha hai", "what are FIIs doing", "FII position", "morning cues" ([com.optionslab.ira.MorningCues]):
        // the market recorder's last GIFT Nifty reading against Nifty's previous close (the gap, with the reading's time),
        // and the FIIs' index futures long share and its change and their index calls and puts from NSE's participant OI
        // rows it kept. Read only from what the recorder holds (nothing fetched here); missing data said as missing; facts
        // only, never a trade suggestion; nothing acts. (Before Honest, which says GIFT Nifty has no data.) Not in IraGoldAlgo.
        val cuesAsk = if (!com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.MorningCues.asked(q) }.getOrNull() else null
        if (cuesAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            scope.launch(Dispatchers.IO) {
                reply(runCatching { morningCuesAnswer(cuesAsk) }.getOrElse { "I could not read the morning cues just now, Boss." })
            }
            return true
        }
        // Markets and figures the phone has no data for (crude, US markets, the rupee, results, VWAP), targets, or lots with
        // no budget: said honestly, never answered with Nifty's figures ([com.optionslab.ira.Honest]; nothing of the account).
        if (parsed.order == null && parsed.command == null && !bundled)
            runCatching { com.optionslab.ira.Honest.asked(q) }.getOrNull()?.let { a ->
                // "How many lots is Liquidity trading?": its size from the arms' book (reads only; changing it is its own
                // command, and a raise waits for Boss's yes). Not in IraGoldAlgo (no Liquidity arm).
                if (a == com.optionslab.ira.Honest.Asked.LiquidityLots && !com.optionslab.app.BuildConfig.GOLD) {
                    _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
                    scope.launch(Dispatchers.IO) {
                        reply(runCatching {
                            val row = com.optionslab.app.data.OrbArms.view().arms.first { it.arm.liquidity }
                            com.optionslab.ira.Honest.liquidityLots(row.lots ?: com.optionslab.engine.orb.LiquidityLots.DEFAULT, row.lotSizes,
                                com.optionslab.app.data.AppSettings.load().guardMaxLots) +
                                (row.lotsAsk?.let { w -> " The backup you restored had $w lots; that waits for your choice on the row." } ?: "")
                        }.getOrElse { com.optionslab.ira.Honest.say(a) })
                    }
                    return true
                }
                val follows =if (com.optionslab.app.BuildConfig.GOLD) listOf(com.optionslab.ira.Market.GOLD) else com.optionslab.ira.Market.entries
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, com.optionslab.ira.Honest.say(a, follows))).takeLast(MAX_MESSAGES)) }
                return true
            }
        // "Why didn't you take that trade?", "why were you quiet at 11?", "what made you say check me?", "why did you skip
        // that check?": his own decision walked through from the reason trail written when he made it (Boss's account and
        // words left out on a locked phone). Nothing written for it: he says so - a reason is never found afterwards (a
        // "why did you ...?" about something else he did goes on to his activity log, below). Words only.
        val thinkAsk = if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.Thinking.asked(q) }.getOrNull() else null
        if (thinkAsk != null) {
            val said = IraThinking.answer(thinkAsk, phoneLocked())
            if (said != null || !com.optionslab.ira.SelfWhy.asked(q)) {
                _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
                reply(said ?: com.optionslab.ira.Thinking.nothing(thinkAsk))
                return true
            }
        }
        return false
    }

    /**
     * [ask]'s question branches on contradictions, the co-pilot brief, now against the morning and the option chain:
     * BigMoveRisk, LiquidityMap, Consistency, CoPilot, SinceMorning, ExpiryPin, ExpiryHour, StraddleDecay, AtmBuy, OtmReach, ChainDrift, ChainIntel - in [ask]'s order. True when one
     * took [q], answered exactly as before; each branch keeps its own guard (not [bundled], no order, no command).
     */
    private fun askedOfChain(q: String, parsed: com.optionslab.ira.Question, bundled: Boolean, understood: Boolean): Boolean {
        // "Is a big move likely now?", "abhi kitna risk hai", "volatile hai kya" ([com.optionslab.ira.BigMoveRisk]): how likely a
        // big 5-minute candle is in the next minutes for the index asked (Nifty when none is), from the real-data study's
        // signals - the time of day, a big candle just now, volatility and today's range against recent days, a falling
        // market, India VIX since the open - on the phone's own 1-minute candles ended before now. Its level and rough
        // multiple of the usual chance, the top reasons, and always that the direction can't be told from it. Market data
        // only (fine on a locked phone); information only, never a trade suggestion; nothing acts. Not in IraGoldAlgo.
        if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD &&
            runCatching { com.optionslab.ira.BigMoveRisk.asked(q) }.getOrDefault(false)) {
            val said = runCatching {
                val mk = com.optionslab.ira.BigMoveRisk.market(parsed.markets)
                if (mk == null) com.optionslab.ira.BigMoveRisk.NOT_HERE
                else com.optionslab.ira.BigMoveRisk.answer(mk, histories[mk]?.bars.orEmpty(), histories[IraMarket.VIX]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.now().toLocalDateTime(), com.optionslab.app.data.Market.isTradingDay(com.optionslab.app.data.Market.today()))
            }.getOrElse { "I could not read the big-move risk just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "Where are the liquidity levels?", "liquidity level kahan hai", "what is liquidity waiting for", "how far is the next
        // pool" ([com.optionslab.ira.LiquidityMap]): Liquidity 15+5's map from the very rules the arm decides with, on the
        // candles it reads - per book (BankNifty 15/5-min, FinNifty 30/5-min) the nearest pool on a swing above and below,
        // how far, which side a close through it would buy, the next level beyond and whether that leaves the arm's room;
        // armed or not and in its entry hours or not, said either way. Market data only; information only, nothing acts.
        // Not in IraGoldAlgo (no Liquidity arm).
        val liqMap = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.LiquidityMap.asked(q) }.getOrNull() else null
        if (liqMap != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            scope.launch(Dispatchers.IO) {
                reply(runCatching { IraLiquidity.answer(liqMap) }.getOrElse { "I could not read the liquidity levels just now, Boss." })
            }
            return true
        }
        // "Any contradictions?", "do the facts agree?", "what's pulling different ways?", "am I going against my own rules?"
        // ([com.optionslab.ira.Consistency]): market facts pointing different ways, his own numbers disagreeing (and which he
        // goes by), and - on an unlocked phone only - Boss's words against today's trades. Words only, never advice.
        if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD &&
            runCatching { com.optionslab.ira.Consistency.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            scope.launch { reply(runCatching { consistency() }.getOrElse { "I could not check myself for contradictions just now, Boss." }) }
            return true
        }
        // "What matters right now?", "brief me", "brief me like a co-pilot" ([com.optionslab.ira.CoPilot]): the facts from every
        // reader ranked by a plain score (recency, size against usual, Boss's open positions, conflicts), the top few said
        // with why each ranked there. Boss's account only on an unlocked phone; facts only, never advice. (IraGoldAlgo keeps
        // the plain briefing below.)
        if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD && !GOLD_ONLY_TALK &&
            runCatching { com.optionslab.ira.CoPilot.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            scope.launch { reply(runCatching { coPilot() }.getOrElse { "I could not put the brief together just now, Boss." }) }
            return true
        }
        // "What changed since this morning?", "what's different since the open?", "subah se kya badla" ([com.optionslab.ira.SinceMorning]):
        // the morning's facts at 09:45 - each index's price, gap, structure and range, India VIX, the first chain read of the day,
        // the news themes, and on an unlocked phone Boss's legs noted that morning - set against now, ranked by size. Facts
        // only, never a cause, forecast or advice; nothing acts.
        if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD &&
            runCatching { com.optionslab.ira.SinceMorning.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val markets = parsed.markets
            scope.launch { reply(runCatching { sinceMorning(markets) }.getOrElse { "I could not set now against this morning just now, Boss." }) }
            return true
        }
        // "How often does Nifty close near max pain on expiry?", "does Nifty pin to max pain on expiry day?", "expiry pin record":
        // the past Nifty expiry-day chains on the phone ([com.optionslab.ira.ExpiryPin]) - max pain and the biggest OI strike at
        // 09:30 (and 14:30) against where Nifty settled, beside where it already stood that morning, and today's newest chain
        // read as it stands. A record of past expiries, never a forecast or advice; market data only (fine on a locked phone);
        // nothing acts. (Before the chain's drift and its OI read: "max pain" with past expiries is this record.)
        val pinAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.ExpiryPin.asked(q) }.getOrNull() else null
        if (pinAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val markets = parsed.markets
            scope.launch(Dispatchers.IO) { reply(runCatching { expiryPin(pinAsk, markets) }.getOrElse { "I could not read the past expiry chains just now, Boss." }) }
            return true
        }
        // "How does the ATM option's premium behave in the last hour on expiry day?", "how much does the ATM call lose in the last
        // hour of expiry?", "how often does the ATM put double in the final hour on expiry?", "expiry ke aakhri ghante mein atm
        // premium kitna girta hai": the expiring at-the-money call and put from 14:30 to the end of the day on the past expiry
        // days whose option prices the phone keeps ([com.optionslab.ira.ExpiryHour]), the other days' same hour beside, and
        // today's legs so far when today is an expiry. A record of past expiries, never a forecast or advice; market data only
        // (fine on a locked phone); nothing acts. (Before the straddle's record and the buyer's record from 9:30.)
        val expiryHourAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.ExpiryHour.asked(q) }.getOrNull() else null
        if (expiryHourAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val expiryHourMarkets = parsed.markets
            scope.launch(Dispatchers.IO) { reply(runCatching { expiryHour(expiryHourAsk, expiryHourMarkets) }.getOrElse { "I could not read the expiry-day last-hour record just now, Boss." }) }
            return true
        }
        // "How much does the ATM straddle usually lose between 9:30 and 2:30?", "how much does Nifty's straddle decay on a quiet
        // day vs a trending day?", "straddle decay record for BankNifty", "nifty ka straddle din mein kitna girta hai": what 9:30
        // to 14:30 did to the at-the-money straddle in the past sessions whose option prices the phone keeps
        // ([com.optionslab.ira.StraddleDecay]), quiet against moving sessions, expiry days apart, beside today's straddle so far.
        // A record of past sessions, never a forecast or advice; market data only (fine on a locked phone); nothing acts.
        // (Before the chain's own reads: "straddle" with a record asked is this, the expected move from it ChainIntel's.)
        val straddleAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.StraddleDecay.asked(q) }.getOrNull() else null
        if (straddleAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val markets = parsed.markets
            scope.launch(Dispatchers.IO) { reply(runCatching { straddleDecay(straddleAsk, markets) }.getOrElse { "I could not read the straddle decay record just now, Boss." }) }
            return true
        }
        // "How often does the ATM option double from its 9:30 price before the end of the day?", "how often does a bought ATM
        // call end the day worth more?", "ATM option buyer record for BankNifty", "atm call kitni baar double hota hai": the
        // at-the-money call and put bought at 9:30 and held, in the past sessions whose option prices the phone keeps
        // ([com.optionslab.ira.AtmBuy]) - how often each ended the day worth more and how often each doubled, expiry days apart,
        // beside today's legs so far. A record of past sessions, never a forecast or advice; market data only; nothing acts.
        val atmBuyAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.AtmBuy.asked(q) }.getOrNull() else null
        if (atmBuyAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val atmMarkets = parsed.markets
            scope.launch(Dispatchers.IO) { reply(runCatching { atmBuy(atmBuyAsk, atmMarkets) }.getOrElse { "I could not read the at-the-money buyer's record just now, Boss." }) }
            return true
        }
        // "How often does an OTM option 100 points away end the day in the money?", "how often does a call two strikes out of
        // the money finish in the money?", "OTM option record for BankNifty", "100 point door ka otm call kitni baar itm hota
        // hai": the out-of-the-money call and put held from 9:30 in the past sessions whose option prices the phone keeps
        // ([com.optionslab.ira.OtmReach]) - how often each strike ended the day in the money, how often the index reached it and
        // how often the option doubled, expiry days apart, beside today's legs so far. A record of past sessions, never a
        // forecast or advice; market data only; nothing acts.
        val otmReachAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.OtmReach.asked(q) }.getOrNull() else null
        if (otmReachAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val otmMarkets = parsed.markets
            scope.launch(Dispatchers.IO) { reply(runCatching { otmReach(otmReachAsk, otmMarkets) }.getOrElse { "I could not read the out-of-the-money option record just now, Boss." }) }
            return true
        }
        // "Where is the most call writing?", "how has OI shifted since morning?", "are puts dearer than calls?", "what's the
        // expected move by expiry from the straddle?": the option chain read beyond PCR and max pain ([com.optionslab.ira.ChainIntel]),
        // every number from the chain with its time. Market data only (fine on a locked phone); words only, never advice.
        // (Before the market answers: the VIX's expected range must not take "expected move by expiry".)
        // "How has max pain moved today?", "is the call wall shifting?", "has the biggest put OI moved since morning?": the
        // chain's drift through the day from Jarvis's own reads kept today ([com.optionslab.ira.ChainDrift]) - max pain and the
        // biggest call / put OI with times, spot against max pain then and now. Market data only; facts only, never advice.
        val driftAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.ChainDrift.asked(q) }.getOrNull() else null
        if (driftAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val markets = parsed.markets
            scope.launch { reply(runCatching { chainDrift(driftAsk, markets) }.getOrElse { "I could not read the option chain just now, Boss." }) }
            return true
        }
        val chainAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.ChainIntel.asked(q) }.getOrNull() else null
        if (chainAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val markets = parsed.markets
            scope.launch { reply(runCatching { chainIntel(chainAsk, markets) }.getOrElse { "I could not read the option chain just now, Boss." }) }
            return true
        }
        return false
    }

    /**
     * [ask]'s question branches on the index's record of past sessions and today's structure: DayClock, GapRecord,
     * RangeBreaks, PriorDay, LastHour, InsideDays, FirstMove, VixNext, SplitDays, RoundCloses, MonthTurns, LunchRange, OpenHighLow, BigCandles, ExtremeCloses, WeekRange, RelativeMove, Comebacks, VixBand, Overnight, DayAfter, OpenReach, MultiDay, MoveTime, GiveBack, Weekdays, DayCompare, LikeToday, Structure, MindChange, Breadth - in [ask]'s order. True when one
     * took [q], answered exactly as before; each branch keeps its own guard (not [bundled], no order, no command).
     */
    private fun askedOfPastDays(q: String, parsed: com.optionslab.ira.Question, bundled: Boolean, understood: Boolean): Boolean {
        // "When does Nifty usually make its high?", "is the low of the day usually in by now?", "which half hour moves the
        // most?": the index's day clock from the whole past sessions of 1-minute candles on the phone ([com.optionslab.ira.DayClock])
        // beside today's own times. A record of past days, never a forecast or advice; market data only (fine on a locked phone).
        // (Before the structure: "where was today's high" stays its; this is the usual time of day.)
        val clockAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.DayClock.asked(q) }.getOrNull() else null
        if (clockAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.DayClock.market(parsed.markets)
                if (mk == null) com.optionslab.ira.DayClock.NOT_HERE
                else com.optionslab.ira.DayClock.answer(clockAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), LocalDateTime.now(IST))
            }.getOrElse { "I could not read the day clock just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "When Nifty gaps up over 0.5%, how often does it fill the gap by 11?", "do gap downs usually fill?": how the index's
        // past gaps played out on the phone's own 1-minute sessions ([com.optionslab.ira.GapRecord]) beside today's gap. A record
        // of past days, never a forecast or advice; market data only (fine on a locked phone). (Today's gap alone stays Gap's.)
        val gapAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.GapRecord.asked(q) }.getOrNull() else null
        if (gapAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.GapRecord.market(parsed.markets)
                if (mk == null) com.optionslab.ira.GapRecord.NOT_HERE
                else com.optionslab.ira.GapRecord.answer(gapAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), LocalDateTime.now(IST))
            }.getOrElse { "I could not read the gap record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "When Nifty breaks its first 15-minute range, how often does it hold by the close?", "do opening range breakouts
        // usually hold?": how the index's past opening-range breaks played out on the phone's own 1-minute sessions
        // ([com.optionslab.ira.RangeBreaks]) beside today's range and its break. A record of past days, never a forecast or
        // advice; market data only (fine on a locked phone). (Where the price stands against today's range stays OpeningRange's,
        // Boss's ORB arms his bots'.)
        val orbAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.RangeBreaks.asked(q) }.getOrNull() else null
        if (orbAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.RangeBreaks.market(parsed.markets)
                if (mk == null) com.optionslab.ira.RangeBreaks.NOT_HERE
                else com.optionslab.ira.RangeBreaks.answer(orbAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the opening-range record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "When Nifty takes out yesterday's high in the first hour, how often does it close above it?", "how often does Nifty break
        // the previous day's low?", "PDH PDL record": how the index's past take-outs of the prior day's high and low closed, on
        // the phone's own 1-minute sessions ([com.optionslab.ira.PriorDay]), beside today against yesterday's high and low. A
        // record of past days, never a forecast or advice; market data only (fine on a locked phone); nothing acts. ("Did Nifty break
        // yesterday's high?" and "how far is Nifty from it?" are answered here directly, round 14; where it is stays the level readers'.)
        val priorAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.PriorDay.asked(q) }.getOrNull() else null
        if (priorAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.PriorDay.market(parsed.markets)
                if (mk == null) com.optionslab.ira.PriorDay.NOT_HERE
                else com.optionslab.ira.PriorDay.answer(priorAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the prior-day high and low record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "How often does the last hour continue the day's direction?", "does Nifty usually reverse in the last hour?": the
        // index's past last hours (14:30 to the close) against the day's move to 14:30, on the phone's own 1-minute sessions
        // ([com.optionslab.ira.LastHour]), beside today so far. A record of past days, never a forecast or advice; market data
        // only (fine on a locked phone); nothing acts. (How busy the last hour is stays DayClock's, by weekday Weekdays'.)
        val lastHourAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.LastHour.asked(q) }.getOrNull() else null
        if (lastHourAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.LastHour.market(parsed.markets)
                if (mk == null) com.optionslab.ira.LastHour.NOT_HERE
                else com.optionslab.ira.LastHour.answer(lastHourAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the last-hour record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "After an inside day, how often does Nifty's range expand?", "do NR7 days usually break out the next day?": what
        // followed the index's past inside days and narrowest-of-7 days, set against what followed every session, on the
        // phone's own 1-minute sessions ([com.optionslab.ira.InsideDays]), beside the last session and today. A record of past
        // days, never a forecast or advice; market data only (fine on a locked phone); nothing acts.
        val insideAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.InsideDays.asked(q) }.getOrNull() else null
        if (insideAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.InsideDays.market(parsed.markets)
                if (mk == null) com.optionslab.ira.InsideDays.NOT_HERE
                else com.optionslab.ira.InsideDays.answer(insideAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the inside-day record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "How often does the first 30 minutes' direction match the day's close?", "does the opening move usually decide the
        // day?": the index's past first moves (the price at 09:45 against the open) against each day's close, set against what
        // chance alone would give, on the phone's own 1-minute sessions ([com.optionslab.ira.FirstMove]), beside today's first
        // move. A record of past days, never a forecast or advice; market data only (fine on a locked phone); nothing acts.
        val firstMoveAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.FirstMove.asked(q) }.getOrNull() else null
        if (firstMoveAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.FirstMove.market(parsed.markets)
                if (mk == null) com.optionslab.ira.FirstMove.NOT_HERE
                else com.optionslab.ira.FirstMove.answer(firstMoveAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the first-move record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "When VIX jumps 5% how big is the next day?", "after a VIX spike how much does Nifty move the next day?": each India
        // VIX session's close-to-close change paired with the index's next whole session (its range and its move), the days
        // VIX rose or fell by the size asked set against every pair, on the phone's own 1-minute sessions
        // ([com.optionslab.ira.VixNext]), beside VIX's latest change. A record of past days, never a forecast or advice; market
        // data only (fine on a locked phone); nothing acts. (The last VIX spike one by one stays MarketMemory's.)
        val vixNextAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.VixNext.asked(q) }.getOrNull() else null
        if (vixNextAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.VixNext.market(parsed.markets)
                if (mk == null) com.optionslab.ira.VixNext.NOT_HERE
                else com.optionslab.ira.VixNext.answer(vixNextAsk, mk, histories[mk]?.bars.orEmpty(), histories[IraMarket.VIX]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the VIX next-day record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "How often do Nifty and BankNifty close opposite ways?", "Nifty BankNifty divergence record", "what happens the day
        // after they split?": the days two indices ended on opposite sides of their previous closes, which way round, how far
        // apart, and what the next day did against every day, on the phone's own 1-minute sessions ([com.optionslab.ira.SplitDays]),
        // beside today so far. A record of past days, never a forecast or advice; market data only (fine on a locked phone);
        // nothing acts. (Whether they move together today stays Together's, the leader over a stretch Breadth's.)
        val splitAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.SplitDays.asked(q) }.getOrNull() else null
        if (splitAsk != null) {
            val said = runCatching {
                val two = com.optionslab.ira.SplitDays.market(parsed.markets, splitAsk)
                if (two == null) com.optionslab.ira.SplitDays.NOT_HERE
                else com.optionslab.ira.SplitDays.answer(two.first, two.second, histories[two.first]?.bars.orEmpty(), histories[two.second]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the split-day record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "Does Nifty close near round numbers?", "are round numbers a magnet for Nifty?", "how often does BankNifty end near a
        // round thousand?": each past whole session's close against the nearest round number, how often it ended within 0.1%
        // of one against how often closes landing anywhere would by chance, on the phone's own 1-minute sessions
        // ([com.optionslab.ira.RoundCloses]), beside the price now. A record of past days, never a forecast or advice; market
        // data only (fine on a locked phone); nothing acts. (What sits at one price stays LevelInfo's, the expiry pin ExpiryPin's.)
        val roundAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.RoundCloses.asked(q) }.getOrNull() else null
        if (roundAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.RoundCloses.market(parsed.markets)
                if (mk == null) com.optionslab.ira.RoundCloses.NOT_HERE
                else com.optionslab.ira.RoundCloses.answer(roundAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the round-number record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "How does Nifty do at the start of the month?", "is there a turn of the month effect?", "month end record": a month's
        // first and last 3 trading days against the days well inside it, on the phone's own 1-minute sessions
        // ([com.optionslab.ira.MonthTurns]), beside today's place when it is one of a month's first days. A record of past
        // days, never a forecast or advice; market data only (fine on a locked phone); nothing acts. (A month's own move stays
        // PeriodMove's, Boss's month MonthReview's.)
        val monthEdgeAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.MonthTurns.asked(q) }.getOrNull() else null
        if (monthEdgeAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.MonthTurns.market(parsed.markets)
                if (mk == null) com.optionslab.ira.MonthTurns.NOT_HERE
                else com.optionslab.ira.MonthTurns.answer(monthEdgeAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the month-start and month-end record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "Does Nifty break the lunch range?", "lunch range breakout record", "is the lunch lull real?": the 12:00-13:30 window's
        // range against the morning and the afternoon, how often the day's high or low was made in it, and which side the
        // afternoon first closed beyond it and how the close ended, on the phone's own 1-minute sessions
        // ([com.optionslab.ira.LunchRange]), beside today's lunch range. A record of past days, never a forecast or advice;
        // market data only (fine on a locked phone); nothing acts. (How busy each half hour is stays DayClock's.)
        val lunchBoxAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.LunchRange.asked(q) }.getOrNull() else null
        if (lunchBoxAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.LunchRange.market(parsed.markets)
                if (mk == null) com.optionslab.ira.LunchRange.NOT_HERE
                else com.optionslab.ira.LunchRange.answer(lunchBoxAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the lunch-range record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "How often is the open the high of the day?", "open = low days record", "how do open high days close?": the days
        // whose open was within 0.05% of the day's high or low, how often, how they closed against the open and their range,
        // on the phone's own 1-minute sessions ([com.optionslab.ira.OpenHighLow]), beside today's open against its high and
        // low. A record of past days, never a forecast or advice; market data only (fine on a locked phone); nothing acts.
        val openEndsAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.OpenHighLow.asked(q) }.getOrNull() else null
        if (openEndsAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.OpenHighLow.market(parsed.markets)
                if (mk == null) com.optionslab.ira.OpenHighLow.NOT_HERE
                else com.optionslab.ira.OpenHighLow.answer(openEndsAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the open-high and open-low record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "After a big 5-minute candle in the first hour, does the day continue?", "big candle record", "subah bada candle ke
        // baad nifty kya karta hai": the days whose first hour had a 5-minute candle of 0.4% or more, how often the day closed
        // beyond it in its direction or back past its open, on the phone's own 1-minute sessions ([com.optionslab.ira.BigCandles]),
        // beside today's first hour. A record of past days, never a forecast or advice; market data only (fine on a locked
        // phone); nothing acts.
        val bigCandleAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.BigCandles.asked(q) }.getOrNull() else null
        if (bigCandleAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.BigCandles.market(parsed.markets)
                if (mk == null) com.optionslab.ira.BigCandles.NOT_HERE
                else com.optionslab.ira.BigCandles.answer(bigCandleAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the big-candle record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "How often does Nifty close near its high?", "what happens the day after BankNifty closes at the low?", "high pe close
        // hone ke baad agle din kya hota hai": the days that closed in the top or bottom 10% of their range and what the next
        // session did, on the phone's own 1-minute sessions ([com.optionslab.ira.ExtremeCloses]), beside where today sits in
        // its range. A record of past days, never a forecast or advice; market data only (fine on a locked phone); nothing acts.
        val extremeCloseAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.ExtremeCloses.asked(q) }.getOrNull() else null
        if (extremeCloseAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.ExtremeCloses.market(parsed.markets)
                if (mk == null) com.optionslab.ira.ExtremeCloses.NOT_HERE
                else com.optionslab.ira.ExtremeCloses.answer(extremeCloseAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the close-at-the-ends record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "Which day of the week usually makes the weekly high?", "weekly range record for Nifty", "how big is a normal week for
        // BankNifty?", "hafte ka low kis din banta hai": the past weeks' ranges and the weekdays their highs and lows came on, on
        // the phone's own 1-minute sessions ([com.optionslab.ira.WeekRange]), beside this week so far. A record of past weeks,
        // never a forecast or advice; market data only (fine on a locked phone); nothing acts.
        val weekRangeAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.WeekRange.asked(q) }.getOrNull() else null
        if (weekRangeAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.WeekRange.market(parsed.markets)
                if (mk == null) com.optionslab.ira.WeekRange.NOT_HERE
                else com.optionslab.ira.WeekRange.answer(weekRangeAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the week's range record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "How often does BankNifty move more than Nifty in % on a day?", "is Sensex more volatile than Nifty?", "banknifty nifty se
        // zyada kitni baar chalta hai": the two indices' day ranges and close-to-close changes side by side on the phone's own
        // 1-minute sessions ([com.optionslab.ira.RelativeMove]), beside today so far. A record of past days, never a forecast or
        // advice; market data only (fine on a locked phone); nothing acts.
        val relativeMoveAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.RelativeMove.asked(q) }.getOrNull() else null
        if (relativeMoveAsk != null) {
            val said = runCatching {
                val two = com.optionslab.ira.RelativeMove.pair(relativeMoveAsk, parsed.markets)
                if (two == null) com.optionslab.ira.RelativeMove.NOT_HERE
                else com.optionslab.ira.RelativeMove.answer(two.first, two.second, histories[two.first]?.bars.orEmpty(), histories[two.second]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the relative move record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "When Nifty is down 1% intraday, how often does it recover?", "does a 1% intraday rally usually hold?", "1% girne ke baad
        // nifty kitni baar recover karta hai": the past days the index touched that size from the previous close and how they
        // ended, on the phone's own 1-minute sessions ([com.optionslab.ira.Comebacks]), beside today so far. A record of past
        // days, never a forecast or advice; market data only (fine on a locked phone); nothing acts.
        val comebackAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.Comebacks.asked(q) }.getOrNull() else null
        if (comebackAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.Comebacks.market(parsed.markets)
                if (mk == null) com.optionslab.ira.Comebacks.NOT_HERE
                else com.optionslab.ira.Comebacks.answer(comebackAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime(),
                    isTradingDay = { d -> runCatching { com.optionslab.app.data.Market.isTradingDay(d) }.getOrDefault(d.dayOfWeek.value <= 5) })
            }.getOrElse { "I could not read the comeback record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "How often does Nifty stay within the VIX expected move?", "does India VIX usually overstate the move?", "vix ke expected
        // move se nifty kitni baar bahar jata hai": the past days measured against one day's move priced by India VIX the evening
        // before, on the phone's own 1-minute sessions ([com.optionslab.ira.VixBand]), beside today's band. A record of past
        // days, never a forecast or advice; market data only (fine on a locked phone); nothing acts.
        val vixBandAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.VixBand.asked(q) }.getOrNull() else null
        if (vixBandAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.VixBand.market(parsed.markets)
                if (mk == null) com.optionslab.ira.VixBand.NOT_HERE
                else com.optionslab.ira.VixBand.answer(vixBandAsk, mk, histories[mk]?.bars.orEmpty(), histories[IraMarket.VIX]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime(),
                    isTradingDay = { d -> runCatching { com.optionslab.app.data.Market.isTradingDay(d) }.getOrDefault(d.dayOfWeek.value <= 5) })
            }.getOrElse { "I could not read the VIX band record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "Does Nifty make its moves overnight or during the day?", "overnight vs intraday returns for BankNifty", "nifty ka move
        // raat mein banta hai ya din mein": the past sessions split at the open into the overnight move and the session's, on the
        // phone's own 1-minute sessions ([com.optionslab.ira.Overnight]), beside today so far. A record of past days, never a
        // forecast or advice; market data only (fine on a locked phone); nothing acts.
        val overnightAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.Overnight.asked(q) }.getOrNull() else null
        if (overnightAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.Overnight.market(parsed.markets)
                if (mk == null) com.optionslab.ira.Overnight.NOT_HERE
                else com.optionslab.ira.Overnight.answer(overnightAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime(),
                    isTradingDay = { d -> runCatching { com.optionslab.app.data.Market.isTradingDay(d) }.getOrDefault(d.dayOfWeek.value <= 5) })
            }.getOrElse { "I could not read the overnight record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "After Nifty falls 1% in a day, what happens the next day?", "does BankNifty bounce the day after a big down day?", "1%
        // girne ke baad agle din nifty kya karta hai": what the next session did after the big days, on the phone's own 1-minute
        // sessions ([com.optionslab.ira.DayAfter]), set against every day's next session, beside today so far. A record of past
        // days, never a forecast or advice; market data only (fine on a locked phone); nothing acts.
        val dayAfterAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.DayAfter.asked(q) }.getOrNull() else null
        if (dayAfterAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.DayAfter.market(parsed.markets)
                if (mk == null) com.optionslab.ira.DayAfter.NOT_HERE
                else com.optionslab.ira.DayAfter.answer(dayAfterAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime(),
                    isTradingDay = { d -> runCatching { com.optionslab.app.data.Market.isTradingDay(d) }.getOrDefault(d.dayOfWeek.value <= 5) })
            }.getOrElse { "I could not read the day-after record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "How far does Nifty usually move from its open?", "how often does BankNifty trade 0.5% on both sides of the open?", "how
        // often does Nifty close within 0.3% of its open?", "open se kitna door jata hai": how far the whole past sessions on the
        // phone got above and below their own open ([com.optionslab.ira.OpenReach]), beside today so far. A record of past days,
        // never a forecast or advice; market data only (fine on a locked phone); nothing acts.
        val openReachAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.OpenReach.asked(q) }.getOrNull() else null
        if (openReachAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.OpenReach.market(parsed.markets)
                if (mk == null) com.optionslab.ira.OpenReach.NOT_HERE
                else com.optionslab.ira.OpenReach.answer(openReachAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the reach-from-the-open record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "How far does Nifty usually move in 3 sessions?", "how often does Nifty move 2% in 3 days?", "teen din mein nifty kitna
        // chalta hai": how far stretches of a few whole past sessions on the phone went from the close they started from
        // ([com.optionslab.ira.MultiDay]), beside the newest stretch. A record of past sessions, never a forecast or advice;
        // market data only (fine on a locked phone); nothing acts.
        val multiDayAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.MultiDay.asked(q) }.getOrNull() else null
        if (multiDayAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.MultiDay.market(parsed.markets)
                if (mk == null) com.optionslab.ira.MultiDay.NOT_HERE
                else com.optionslab.ira.MultiDay.answer(multiDayAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime(),
                    isTradingDay = { d -> runCatching { com.optionslab.app.data.Market.isTradingDay(d) }.getOrDefault(d.dayOfWeek.value <= 5) })
            }.getOrElse { "I could not read the few-sessions move record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "How long does Nifty usually take to move 50 points?", "how often does Nifty move 0.3% within 30 minutes?", "nifty ko
        // 50 point chalne mein kitna time lagta hai": how long the index took to travel a distance from each quarter-hour start
        // in the whole past sessions on the phone ([com.optionslab.ira.MoveTime]), beside today's starts so far. A record of
        // past sessions, never a forecast or advice; market data only (fine on a locked phone); nothing acts.
        val moveTimeAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.MoveTime.asked(q) }.getOrNull() else null
        if (moveTimeAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.MoveTime.market(parsed.markets)
                if (mk == null) com.optionslab.ira.MoveTime.NOT_HERE
                else com.optionslab.ira.MoveTime.answer(moveTimeAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the time-to-move record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "After Nifty runs 100 points in the first hour, how much does it give back by the close?", "how deep is the pullback
        // after Nifty runs 1%?", "100 point chalne ke baad nifty kitna wapas deta hai": how much of a run from the open the close
        // gave back in the whole past sessions on the phone, and the deepest pullback inside it ([com.optionslab.ira.GiveBack]),
        // beside today's run so far. A record of past sessions, never a forecast or advice; market data only (fine on a locked
        // phone); nothing acts.
        val giveBackAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.GiveBack.asked(q) }.getOrNull() else null
        if (giveBackAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.GiveBack.market(parsed.markets)
                if (mk == null) com.optionslab.ira.GiveBack.NOT_HERE
                else com.optionslab.ira.GiveBack.answer(giveBackAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not read the give-back record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "Are Mondays more volatile?", "which day of the week moves the most?", "are expiry days wider than other days?": each
        // weekday's record, and the expiry days the phone knows against the rest, from the whole past sessions of 1-minute
        // candles ([com.optionslab.ira.Weekdays]) beside today's range. A record of past days, never a forecast or advice;
        // market data only (fine on a locked phone). (The last expiries one by one stay MarketMemory's, today's ExpiryDay's.)
        val weekdayAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.Weekdays.asked(q) }.getOrNull() else null
        if (weekdayAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.Weekdays.market(parsed.markets)
                if (mk == null) com.optionslab.ira.Weekdays.NOT_HERE
                else {
                    val today = com.optionslab.app.data.Market.today()
                    val ex = expiryDays[mk].orEmpty() + (if (expiryToday(mk)) setOf(today) else emptySet())
                    com.optionslab.ira.Weekdays.answer(weekdayAsk, mk, histories[mk]?.bars.orEmpty(), today, LocalDateTime.now(IST), ex)
                }
            }.getOrElse { "I could not read the weekday record just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "How is today different from yesterday?", "is today more like a trend day than yesterday?", "compare today with last
        // Thursday", "aaj aur kal mein kya fark hai": today and an earlier session side by side on the same measures, the other
        // day cut at the same minute while today trades ([com.optionslab.ira.DayCompare]). Market data only (fine on a locked
        // phone); facts only, never a cause, a forecast or advice; nothing acts. (Before Structure: today alone stays its.)
        val compareAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.DayCompare.asked(q) }.getOrNull() else null
        if (compareAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.DayCompare.market(parsed.markets)
                if (mk == null) com.optionslab.ira.DayCompare.NOT_HERE
                else com.optionslab.ira.DayCompare.answer(compareAsk, mk, histories[mk]?.bars.orEmpty(), com.optionslab.app.data.Market.today())
            }.getOrElse { "I could not set the two days side by side just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "Is today like any past day?", "how did days like today end?", "aaj jaisa din pehle kab tha": today's start (the gap,
        // the range from 09:15 to 10:15 or to now, India VIX at that minute) set against each whole past session read over the
        // same minutes ([com.optionslab.ira.LikeToday]): how many matched, how they went on to the close, the nearest named.
        // Past days, never a forecast or advice; market data only (fine on a locked phone); nothing acts. (After DayCompare:
        // a named day beside today stays its.)
        if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD &&
            runCatching { com.optionslab.ira.LikeToday.asked(q) }.getOrDefault(false)) {
            val said = runCatching {
                val mk = com.optionslab.ira.LikeToday.market(parsed.markets)
                if (mk == null) com.optionslab.ira.LikeToday.NOT_HERE
                else com.optionslab.ira.LikeToday.answer(mk, histories[mk]?.bars.orEmpty(), histories[IraMarket.VIX]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime())
            }.getOrElse { "I could not set today against the past days just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "What's the structure today?", "is Nifty making higher highs?", "where are the swing levels?", "trend or range so
        // far?": today's intraday structure from the 1-minute candles on the phone ([com.optionslab.ira.Structure]), numbers with
        // times. Market data only (fine on a locked phone); facts only, never advice or a forecast. (Before the market answers:
        // the day's story must not take "so far", nor the account "trend or range so far".)
        val structureAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.Structure.asked(q) }.getOrNull() else null
        if (structureAsk != null) {
            val said = runCatching {
                val mk = com.optionslab.ira.Structure.market(parsed.markets)
                if (mk == null) com.optionslab.ira.Structure.NOT_HERE
                else com.optionslab.ira.Structure.answer(structureAsk, mk, histories[mk]?.bars.orEmpty(),
                    com.optionslab.app.data.Market.today(), com.optionslab.app.data.Market.now().toLocalDateTime()).also {
                    // Kept so "what would change your mind?" tests this read (market data only; memory only, never stored).
                    lastStructureRead = runCatching { com.optionslab.ira.MindChange.said(mk, histories[mk]?.bars.orEmpty(), com.optionslab.app.data.Market.today()) }.getOrNull()
                    // A trend or range read said in session is noted, to be scored after the close ([com.optionslab.ira.TrendReads];
                    // the index, the minute, the kind and the price - never Boss's words).
                    if (structureAsk == com.optionslab.ira.Structure.Ask.TREND_RANGE || structureAsk == com.optionslab.ira.Structure.Ask.ALL)
                        runCatching { IraTools.trendReadSaid(com.optionslab.ira.TrendReads.call(mk, histories[mk]?.bars.orEmpty(),
                            com.optionslab.app.data.Market.now().toLocalDateTime())) }
                }
            }.getOrElse { "I could not read today's structure just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "What would change your mind?", "what would make you wrong?", "what would invalidate that read?", "aapka view kab
        // badlega": for the structure read Jarvis last gave ([lastStructureRead]; today's as it stands when there is none, and
        // he says so), the levels on the phone's candles that would make each part no longer true, and which have been
        // crossed since, at what minute ([com.optionslab.ira.MindChange]). Market data only (fine on a locked phone); facts
        // only, never a forecast or advice; nothing acts.
        if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD &&
            runCatching { com.optionslab.ira.MindChange.asked(q) }.getOrDefault(false)) {
            val said = runCatching {
                val named = if (parsed.markets.isEmpty()) null else com.optionslab.ira.Structure.market(parsed.markets)
                com.optionslab.ira.MindChange.answer(named, lastStructureRead, histories.mapValues { it.value.bars },
                    com.optionslab.app.data.Market.today())
            }.getOrElse { "I could not test my read just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        // "Is the rally broad or narrow?", "which index is leading since the open / in the last hour?", "relative strength
        // BankNifty vs Nifty this week": breadth and leadership across the four indices from the candles on the phone
        // ([com.optionslab.ira.Breadth]; no sector or stock data there, and it says so). Market data only (fine on a locked
        // phone); facts only, never advice or a forecast. (Before the market answers: today's comparison must not take a
        // stretch of time or a week.)
        val breadthAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.Breadth.asked(q) }.getOrNull() else null
        if (breadthAsk != null) {
            val said = runCatching {
                com.optionslab.ira.Breadth.answer(breadthAsk, histories.mapValues { it.value.bars }, com.optionslab.app.data.Market.today())
            }.getOrElse { "I could not read the indices just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return true
        }
        return false
    }

    /**
     * [ask]'s question branches on reasoned answers and Jarvis's own plan and goals: TradeCase, Scenarios, Causes,
     * Agenda, Improve - in [ask]'s order. True when one
     * took [q], answered exactly as before; each branch keeps its own guard (not [bundled], no order, no command).
     */
    private fun askedToReason(q: String, parsed: com.optionslab.ira.Question, bundled: Boolean, understood: Boolean): Boolean {
        // "Make the case", "pros and cons of trading now", "talk me through it": the trade check reasoned out, facts both
        // ways (Boss's own day, goals and rules only on an unlocked phone). Words only; the decision is Boss's. (Before the
        // other answers: these whole questions are this, and the glossary or a plan must not read "explain" or "and" in them.)
        if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD &&
            runCatching { com.optionslab.ira.TradeCase.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            scope.launch { reply(runCatching { tradeCase() }.getOrElse { "I could not put the case together just now, Boss." }) }
            return true
        }
        // "What if Nifty opens 1% down tomorrow?", "what if VIX goes to 20?": the scenario worked through from facts he has -
        // past sessions like it, which of his own alerts would go off, and Boss's positions, rules, loss limits and alarms at
        // it (only on an unlocked phone). Never a forecast or advice; the decision is Boss's. ("What happens to my P&L if
        // Nifty moves 100 points" stays the account's answer: Scenarios.asked leaves it to Exposure.)
        val scenario = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.Scenarios.asked(q) }.getOrNull() else null
        if (scenario != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            scope.launch { reply(runCatching { scenarioSaid(scenario) }.getOrElse { "I could not work that through just now, Boss." }) }
            return true
        }
        // "Why did Nifty fall?", "why is the market down today?", "what caused the rally in BankNifty?", "nifty kyun gira": the
        // candidate explanations the phone holds weighed by evidence ([com.optionslab.ira.Causes]) - the gap, headlines timed
        // against the biggest stretch, its theme's record here, the other indices and VIX (describe, don't explain), FII day
        // totals, today's events - and which are only coincidence in time. Market data and headlines only (fine on a locked
        // phone); facts only, never a cause claimed, a forecast or advice; nothing acts. Not in IraGoldAlgo (no news there).
        // ("Why did Nifty suddenly fall" stays SharpMove's; the news behind a move NewsDesk's.)
        val causeAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.Causes.asked(q) }.getOrNull() else null
        if (causeAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val markets = parsed.markets
            scope.launch { reply(runCatching { causes(causeAsk, markets) }.getOrElse { "I could not weigh that just now, Boss." }) }
            return true
        }
        // "What's your plan today?" / "what are you working on?": Jarvis's own plan for the day (it names Boss's goals and
        // words, so the phone must be unlocked). Words only: the plan itself only ever speaks, studies or works on paper.
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.command == null && parsed.order == null &&
            runCatching { com.optionslab.ira.Agenda.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return true }
            scope.launch { reply(runCatching { IraAgenda.say() }.getOrElse { "I could not read my plan just now, Boss." }) }
            return true
        }
        // "How are you improving?" / "what are your goals?": his own goals for the week and how they stand ([IraImprove]). Words
        // only: a goal of his only ever speaks, studies, asks Boss to teach him or works on paper.
        if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.command == null && parsed.order == null &&
            runCatching { com.optionslab.ira.Improve.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return true }
            scope.launch(Dispatchers.IO) { reply(runCatching { IraImprove.say() }.getOrElse { "I could not read my own goals just now, Boss." }) }
            return true
        }
        return false
    }

    /** On a day with no session, why (a holiday's name, "weekend"), for a greeting; null on a trading day. */
    private fun noSessionWhy(today: LocalDate = com.optionslab.app.data.Market.today()): String? =
        if (runCatching { com.optionslab.app.data.Market.isTradingDay(today) }.getOrDefault(true)) null
        else runCatching { com.optionslab.app.data.Holidays.book().upcoming(today).firstOrNull { it.first == today }?.second }.getOrNull()
            ?: if (today.dayOfWeek.value >= 6) "weekend" else "a market holiday"

    /**
     * What a plain answer is worked out from: the question as read, the prices, news and pattern book (the very ones,
     * by identity: any refresh makes new ones, and the book is taught only in a refresh), the minute and why there is no
     * session. The same inputs give the same words ([com.optionslab.ira.Turn.key]).
     */
    private class Inputs(val q: com.optionslab.ira.Question, val snaps: Any, val news: Any, val book: Any, val minute: LocalDateTime, val closedReason: String?) {
        override fun equals(other: Any?): Boolean = other is Inputs && q == other.q && snaps === other.snaps && news === other.news &&
            book === other.book && minute == other.minute && closedReason == other.closedReason
        override fun hashCode(): Int = q.hashCode()
    }

    /** The one plain answer worked out ahead ([prepare]), until [ask] takes or drops it. */
    private val ahead = com.optionslab.ira.Ahead<Inputs, com.optionslab.ira.Answer>()

    /**
     * A plain market question's answer worked out now, from the turn's partial words, while the recognizer's final reading
     * is awaited ([com.optionslab.ira.Turn.ahead]; Boss, 5 Oct: speed). The words only: nothing is posted, counted,
     * learned, said or done, and nothing else the hub keeps changes. [ask] uses it, with all its usual effects, only when
     * the final words read as the same question from the same prices; anything else drops it. Off the main thread.
     */
    fun prepare(question: String) {
        val q = com.optionslab.ira.Secrets.redact(question.trim())
        if (q.isEmpty()) return
        val parsed = Ask.parse(q)
        if (!com.optionslab.ira.Turn.quick(parsed)) return
        val closedReason = noSessionWhy()
        val now = LocalDateTime.now(IST)
        val st = _state.value
        val b = book
        val firstAhead = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && parsed.topics == setOf(Topic.GREETING))
            runCatching { IraTools.firstIndex() }.getOrNull() else null
        val partAhead = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && com.optionslab.ira.LeadPart.reorders(parsed.topics))
            runCatching { IraTools.leadPart() }.getOrNull() else null
        val a = runCatching { Ira(b, runCatching { IraTools.patternCalls() }.getOrDefault(emptyList()), firstAhead, partAhead).answer(q, st.snaps, st.news, voice = com.optionslab.app.BuildConfig.JARVIS, now = now, closedReason = closedReason) }.getOrNull() ?: return
        ahead.put(Inputs(com.optionslab.ira.Turn.key(parsed), st.snaps, st.news, b, now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES), closedReason), a)
    }

    /**
     * "Backtest the hammer on Nifty": the named pattern (or today's newest one) on the named market and chart (or the
     * one it was seen on), backtested in the background; the result is offered with Approve / Dismiss.
     */
    private fun backtestAsked(q: String, parsed: com.optionslab.ira.Question) {
        val snaps = _state.value.snaps
        val markets = parsed.markets.filter { it in StrategyLab.MARKETS }.ifEmpty { StrategyLab.MARKETS }
        val seen = markets.flatMap { m -> snaps[m]?.patterns.orEmpty().filter { StrategyLab.supported(it.kind, m) }.map { m to it } }
            .sortedByDescending { it.second.at }
        val named = parsed.pattern
        val badMarket = parsed.markets.firstOrNull { it !in StrategyLab.MARKETS }
        if (badMarket != null && parsed.markets.none { it in StrategyLab.MARKETS }) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, "I can't write a strategy for ${badMarket.label}: " +
                "strategies trade index options (Nifty, BankNifty, FinNifty, Sensex).")).takeLast(MAX_MESSAGES)) }
            return
        }
        val m: IraMarket
        val kind: PatternKind
        val minutes: Int
        if (named != null) {
            kind = named
            m = parsed.markets.firstOrNull { it in StrategyLab.MARKETS } ?: seen.firstOrNull { it.second.kind == named }?.first ?: IraMarket.NIFTY
            minutes = parsed.minutes ?: 15
        } else if (seen.isNotEmpty()) {
            m = seen.first().first; kind = seen.first().second.kind; minutes = parsed.minutes ?: seen.first().second.minutes
        } else {
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, "I see no pattern I can turn into a strategy today. " +
                "Name one: engulfing, bearish engulfing, hammer, shooting star, three green, three red, breakout or breakdown - and the index.")).takeLast(MAX_MESSAGES)) }
            return
        }
        if (!StrategyLab.supported(kind, m)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, "I can't write a strategy for a ${kind.label} on ${m.label}.")).takeLast(MAX_MESSAGES)) }
            return
        }
        _state.update { it.copy(busy = true, messages = (it.messages + Msg(false, q) + Msg(true, "Backtesting ${StrategyLab.name(kind, m, minutes)} on the candles I have...")).takeLast(MAX_MESSAGES)) }
        scope.launch {
            val r = lab(kind, m, minutes)
            if (r == null) _state.update { it.copy(busy = false, messages = (it.messages + Msg(true, "I have no ${m.label} candles to backtest on yet.")).takeLast(MAX_MESSAGES)) }
            else { propose(r, ""); _state.update { it.copy(busy = false) } }
        }
    }

    /** "Analyze my orders": the app's own books read off the main thread, answered as facts; the model may reword it. */
    private fun accountAsked(q: String) {
        val user = Msg(false, q)
        _state.update { it.copy(messages = (it.messages + user).takeLast(MAX_MESSAGES)) }
        scope.launch {
            // Offline: what the phone keeps is read, and the broker is not waited on past a few seconds.
            val net = online()
            // Speed, round 4: figures kept a few minutes said at once (with their time), read again behind; a slow fresh
            // read is waited on 3 s when kept ones can stand in ([IraAccount.readQuick], [com.optionslab.ira.KeptFigures]).
            val read = scope.async { runCatching { IraAccount.readQuick(com.optionslab.ira.AppAnswers.sections(q), IraMarket.mentioned(q), question = q) }.getOrNull() }
            val got = if (net) read.await() else kotlinx.coroutines.withTimeoutOrNull(5_000) { read.await() }
            val v = got?.view
            val keptNote = got?.note
            if (v == null && !net) {
                _state.update { it.copy(messages = (it.messages + Msg(true, "I'm offline, Boss: your account needs the internet. I can still answer about the markets from the prices saved on the phone.")).takeLast(MAX_MESSAGES)) }
                return@launch
            }
            val a0 = runCatching { Ira(book).answer(q, emptyMap(), emptyList(), app = v) }.getOrElse { com.optionslab.ira.Answer("I could not work that out.", emptyList()) }
            // Figures not as of now: their time said first (in the facts too), and the model never rewrites it away.
            val a = if (keptNote == null) a0 else a0.copy(text = com.optionslab.ira.KeptFigures.dress(a0.text, keptNote), facts = listOf(keptNote) + a0.facts)
            val write = v != null && keptNote == null && IraModel.usable() && a.facts.isNotEmpty()
            val msg = Msg(true, IraTools.fitWords(a.text), a.facts, writing = write)
            _state.update { it.copy(messages = (it.messages + msg).takeLast(MAX_MESSAGES)) }
            if (write) {
                // Asked by voice, the answer above is said at once: the model's rewrite waits for the voice to be free.
                val better = runCatching { IraModel.rewriteWhenFree(q, a.facts, a.text) }.getOrNull()
                val betterFit = if (better != null && better != a.text) IraTools.fitWords(better) else null
                _state.update { s -> s.copy(messages = s.messages.map { m ->
                    if (m !== msg) m else if (betterFit != null) m.copy(text = betterFit, draft = a.text, writing = false) else m.copy(writing = false)
                }) }
            }
        }
    }

    private val actions = HashMap<Long, Pair<String, suspend () -> String>>()

    // ---- the Requests panel (Boss, 5 Oct): what waits for his yes, apart from the chat ---------------------------------
    // Read-only views of [actions] (and of the strategies waiting); approving and declining stay [confirm] and
    // [cancelAction] - the panel holds no path of its own.

    /** Each waiting action's view for the panel, kept beside [actions] (under the same lock). */
    private val requestMeta = HashMap<Long, com.optionslab.ira.Requests.RequestView>()
    /** When each waiting strategy was first listed (strategies carry no time of their own). */
    private val strategySeen = HashMap<Long, Long>()
    private val _recent = MutableStateFlow<List<com.optionslab.ira.Requests.Recent>>(emptyList())

    /** The requests waiting in [s]: the actions still pending, then the strategies still new. None in IraGoldAlgo. */
    fun requestsOf(s: State): List<com.optionslab.ira.Requests.RequestView> {
        if (GOLD_ONLY_TALK) return emptyList()
        val acts = synchronized(actions) { s.pending.mapNotNull { id -> requestMeta[id]?.takeIf { actions.containsKey(id) } } }
            // A yes that asks for the fingerprint: the hub's own gate ([needsFingerprint]), never guessed from the venue.
            .map { rv -> rv.copy(fingerprint = runCatching { needsFingerprint(rv.id) }.getOrDefault(rv.venue.real)) }
        val now = System.currentTimeMillis()
        val strategies = s.proposals.filter { it.status == Proposal.NEW }.map { p ->
            val seen = synchronized(strategySeen) { strategySeen.getOrPut(p.id) { now } }
            com.optionslab.ira.Requests.RequestView(p.id, com.optionslab.ira.Requests.Kind.STRATEGY, p.result.name,
                "add ${p.result.name} as an arm on paper (${p.result.market.label}, ${p.result.minutes} min)",
                com.optionslab.ira.Requests.why(p.result.verdict), com.optionslab.ira.Requests.Venue.PAPER, seen, null, symbol = p.result.market.label)
        }
        return com.optionslab.ira.Requests.sorted(acts) + strategies
    }

    private val _requests: StateFlow<List<com.optionslab.ira.Requests.RequestView>> by lazy {
        _state.map { requestsOf(it) }.stateIn(scope, SharingStarted.Eagerly, requestsOf(_state.value))
    }

    /** The requests waiting now (read-only): the Requests panel, its badge and the voice's many-waiting rule read this. */
    fun pendingRequests(): StateFlow<List<com.optionslab.ira.Requests.RequestView>> = _requests

    /** What became of the last few requests (approved, declined, lapsed, failed), newest first; kept while the app runs. */
    fun recentRequests(): StateFlow<List<com.optionslab.ira.Requests.Recent>> = _recent

    /** The title of the action [id] still waiting, or null. */
    fun requestTitle(id: Long): String? = synchronized(actions) { requestMeta[id]?.takeIf { actions.containsKey(id) }?.title }

    /**
     * Jarvis's spoken ask for the waiting action [id]: its short heading, then the full what - never shortened, so an exit
     * or a plan is heard whole ("Request: <title>. Shall I <what>? Yes or no?") - or null.
     */
    fun requestAsk(id: Long): String? = synchronized(actions) { requestMeta[id]?.takeIf { actions.containsKey(id) } }
        ?.let { v -> com.optionslab.ira.Requests.ask(v.title, v.what) }

    /**
     * [id] is no longer waiting: listed under Recent with [outcome] (once), how it was answered ([by], when known), what
     * came of it ([result]) and whether a yes on it asked for the fingerprint ([fingerprint], read before it was taken out).
     */
    private fun requestDone(id: Long, outcome: com.optionslab.ira.Requests.Outcome, by: com.optionslab.ira.Requests.By? = null,
                            result: String? = null, fingerprint: Boolean? = null) {
        val v = synchronized(actions) { requestMeta.remove(id) } ?: return
        _recent.update { com.optionslab.ira.Requests.keep(it, com.optionslab.ira.Requests.Recent(v, outcome, System.currentTimeMillis(), by, result, fingerprint)) }
    }

    /** Confirmed and taken out of [actions], its result not yet in Recent (under the [actions] lock): a second yes meanwhile is "already answered". */
    private val confirming = HashSet<Long>()

    /**
     * The words for a yes (a tap, the voice, the notification) that found [id] no longer waiting: "That was already
     * answered, Boss." when it was answered - being done now, or Recent shows its outcome - else [lapsed].
     */
    fun alreadyLine(id: Long, lapsed: String): String {
        val inFlight = synchronized(actions) { id in confirming }
        val outcome = _recent.value.firstOrNull { it.view.id == id && it.view.kind != com.optionslab.ira.Requests.Kind.STRATEGY }?.outcome
        return com.optionslab.ira.Requests.alreadyLine(outcome, inFlight, lapsed)
    }

    /**
     * Yes on [id] from a button (the Requests panel's, the chat's): [confirm] itself - every gate exactly as there (the
     * fingerprint for real money and the emergency exit, the PIN, the live and proven-record checks) - launched in the
     * hub's own long-lived [scope], never the button's. A button's scope ends when its card leaves the screen, and confirm
     * takes [id] out of the pending list at once, so a confirm run there was cancelled mid-way (an emergency exit could be
     * reported failed after its orders had gone out). Its result is said through the usual path ([reply], [requestDone]);
     * nothing waiting any more: [alreadyLine].
     */
    fun confirmAsync(id: Long, fingerprint: Boolean = false): kotlinx.coroutines.Job = scope.launch {
        val r = confirm(id, fingerprint = fingerprint,
            by = if (fingerprint) com.optionslab.ira.Requests.By.FINGERPRINT else com.optionslab.ira.Requests.By.TAP)
        if (r == null) reply(alreadyLine(id, "That had already lapsed; nothing was done."))
    }

    /** A strategy approved or dismissed: listed under Recent. */
    private fun strategyDone(p: Proposal, outcome: com.optionslab.ira.Requests.Outcome, result: String? = null) {
        val seen = synchronized(strategySeen) { strategySeen.remove(p.id) } ?: System.currentTimeMillis()
        val v = com.optionslab.ira.Requests.RequestView(p.id, com.optionslab.ira.Requests.Kind.STRATEGY, p.result.name,
            "add ${p.result.name} as an arm on paper", null, com.optionslab.ira.Requests.Venue.PAPER, seen, null)
        _recent.update { com.optionslab.ira.Requests.keep(it, com.optionslab.ira.Requests.Recent(v, outcome, System.currentTimeMillis(),
            result = result, fingerprint = false)) }
    }

    /**
     * Does Jarvis's message [msgId] end with an offer of words still open (the question Boss asks next, the morning one)?
     * Only his newest message, on an unlocked phone - its Yes / No buttons show while this holds.
     */
    fun offerOpen(msgId: Long): Boolean {
        val newest = _state.value.messages.lastOrNull { it.fromIra } ?: return false
        if (newest.id != msgId || _state.value.messages.lastOrNull()?.id != msgId) return false
        return com.optionslab.ira.Requests.offerButtons(newest.text, true, runCatching { IraTools.offerLine() }.getOrNull(), phoneLocked())
    }

    /**
     * Yes or No tapped under the offer in message [msgId]: Yes asks the offered question (a question only - never an
     * order or a command, never on a locked phone); No ends the offer. Nothing acts. False when the offer is not open.
     */
    fun answerOffer(msgId: Long, yes: Boolean): Boolean {
        if (!offerOpen(msgId)) return false
        if (!yes) { runCatching { IraTools.endWaits() }; _state.update { it.copy(messages = (it.messages + Msg(false, "No")).takeLast(MAX_MESSAGES)) }; return true }
        val q = runCatching { IraTools.offerTapped(phoneLocked()) }.getOrNull() ?: return false
        val parsed = Ask.parse(q)
        if (parsed.command != null || parsed.order != null) return false
        _state.update { it.copy(messages = (it.messages + Msg(false, "Yes") + Msg(true, "$TOOK_AS\"$q\".")).takeLast(MAX_MESSAGES)) }
        askSoon(q, confirmed = true)
        return true
    }

    /** Forgetting everything (and the tests' reset): no request views, no recent list. */
    internal fun clearRequests() {
        synchronized(actions) { requestMeta.clear(); confirming.clear() }
        synchronized(strategySeen) { strategySeen.clear() }
        _recent.value = emptyList()
    }

    /**
     * An exit's venue when what is open was not worked out ([IraActions.venueOf] is the usual way): an exit acts on the
     * paper account and on Zerodha alike, so with Zerodha logged in it is "Paper + Zerodha" - never "Paper" on a guess.
     */
    private fun modeVenue(): com.optionslab.ira.Requests.Venue =
        if (runCatching { com.optionslab.app.data.Broker.loggedIn }.getOrDefault(true)) com.optionslab.ira.Requests.Venue.PAPER_ZERODHA
        else com.optionslab.ira.Requests.Venue.PAPER

    /**
     * The waiting request [id]'s venue, worked out in the background from what is open ([work]: [IraActions.venueOf]'s
     * reads) after it was made with its at-once label - the request never waits on the reads. Set only while [id] still
     * waits, and only from a read (null, nothing worked out: the label stays as it is - never "Paper" on a guess).
     */
    private fun venueLater(id: Long, work: suspend () -> com.optionslab.ira.Requests.Venue?) {
        scope.launch {
            val v = runCatching { work() }.getOrNull() ?: return@launch
            val changed = synchronized(actions) {
                val rv = requestMeta[id]
                if (rv != null && actions.containsKey(id) && rv.venue != v) { requestMeta[id] = rv.copy(venue = v); true } else false
            }
            if (changed) _state.update { it.copy(requestsRev = it.requestsRev + 1) }
        }
    }

    /**
     * "Stop strategy 1", "cancel all orders", "switch to live"... In Jarvis what adds risk runs at once; what stops or
     * closes waits for Confirm. In IraAlgo everything waits for Confirm.
     */
    /**
     * The model's work, or null when it fails or takes over 15 seconds (Boss is never left waiting in silence; a slow
     * run finishes in the background and is dropped).
     */
    private suspend fun <T> modelOrNull(ms: Long = 15_000L, work: suspend () -> T?): T? {
        if (ms <= 0L) return null
        val d = scope.async { runCatching { work() }.getOrNull() }
        return kotlinx.coroutines.withTimeoutOrNull(ms) { d.await() }
    }

    /** [rewrite] would read the account where [original] did not, on a locked phone (the lock was checked on [original]). */
    private fun lockedAccount(original: String, rewrite: String): Boolean {
        if (Topic.ACCOUNT !in Ask.parse(rewrite).topics || Topic.ACCOUNT in Ask.parse(original).topics) return false
        return phoneLocked()
    }

    /** The phone is locked now (Boss's account and words are then not said aloud). */
    internal fun locked(): Boolean = phoneLocked()

    private fun phoneLocked(): Boolean {
        val c = app ?: return false
        return runCatching { (c.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked }.getOrDefault(false)
    }

    private const val TOOK_AS = "I took that as: "
    private const val LEARNED_NOTE = "Got it, Boss: next time"
    private const val CHAIN_NOTE = "Reading the option chain for "

    /**
     * Jarvis's reply to [said] in [ms], or null while there is none yet: past an "I took that as" note (a follow-up or a
     * learned wording), the reply is the answer to the question it was taken as.
     */
    fun replyAfter(ms: List<Msg>, said: String): Msg? {
        val i = ms.indexOfLast { !it.fromIra && it.text == said }
        if (i < 0) return null
        val after = ms.drop(i + 1)
        // "Got it, Boss: next time..." and "Shall I take ... to mean ...?" (a wording to learn) are notes beside the
        // answer, not the answer.
        val real = after.filter { it.fromIra && !it.text.startsWith(LEARNED_NOTE) && !it.text.startsWith(CHAIN_NOTE) &&
            !it.text.startsWith(com.optionslab.ira.Corrections.OFFER) && !it.text.startsWith(com.optionslab.ira.Routine.OFFER) &&
            !it.text.startsWith(com.optionslab.ira.Latency.NUDGE) && !it.text.startsWith(com.optionslab.ira.Hearing.NUDGE) }
        val first = real.firstOrNull() ?: return null
        if (!first.text.startsWith(TOOK_AS)) return first
        return real.drop(1).firstOrNull()
    }

    /**
     * "How much did Nifty move in the last hour", "is BankNifty stronger than Nifty", "the expected range today": worked
     * out from the candles and snapshots on the phone, or null when the question is none of these (or the data is missing).
     */
    private fun reasoned(raw: String, parsed: com.optionslab.ira.Question): String? {
        // The words as understood (Hinglish turned into English: "pichle ghante kitna gira" -> "in the last hour how much fell").
        val q = parsed.text.ifBlank { raw }
        // "What happened in the market today?", "what's different about today?": the indices' day against their own
        // recent sessions (whole questions with no index named - before the why-answer, which "what happened" reaches).
        // No session today on the phone (a holiday, a failed refresh): null, and the usual answer says so.
        com.optionslab.ira.MarketStory.asked(q)?.let { kind ->
            val bars = histories.mapValues { it.value.bars }
            val now = LocalDateTime.now(IST)
            val today = com.optionslab.app.data.Market.today()
            return if (kind == com.optionslab.ira.MarketStory.Kind.STORY) com.optionslab.ira.MarketStory.story(bars, _state.value.news, now, today, IST)
                else com.optionslab.ira.MarketStory.different(bars, now, today)
        }
        // "Explain this move", "what coincided with that fall", "what happened at 11:20": the move and what coincided with it
        // (headlines, VIX, the other indices in the same minutes) - before the why-answer, which some of these words reach.
        com.optionslab.ira.SharpMove.asked(q)?.let { a ->
            val mk = parsed.markets.firstOrNull { it in com.optionslab.ira.SharpMove.INDICES } ?: IraMarket.NIFTY
            val bars = (com.optionslab.ira.SharpMove.INDICES + IraMarket.VIX).associateWith { histories[it]?.bars.orEmpty() }
            return com.optionslab.ira.SharpMove.answer(a, mk, bars, _state.value.news, IST, expiry = expiryToday(mk))
        }
        // "When did Nifty last gap down this much?", "last time VIX jumped like this?", "how many trend days this month?",
        // "what happened the last 3 expiries?": the notable sessions remembered from the candles on the phone - before the
        // why-answer ("what happened" reaches it) and the week's and month's moves ("this month" reaches them). Past
        // sessions with their dates and numbers only: never a forecast, never advice.
        com.optionslab.ira.MarketMemory.asked(q)?.let { a ->
            val mk = parsed.markets.firstOrNull { it in com.optionslab.ira.MarketStory.INDICES } ?: IraMarket.NIFTY
            val bars = histories[mk]?.bars ?: return null
            val today = com.optionslab.app.data.Market.today()
            val live = mk.trading(LocalDateTime.now(IST)) && closedToday() == null
            val ex = expiryDays[mk].orEmpty() + (if (expiryToday(mk)) setOf(today) else emptySet())
            val days = com.optionslab.ira.MarketMemory.days(mk, bars, ex, histories[IraMarket.VIX]?.bars.orEmpty(), today, live)
            return com.optionslab.ira.MarketMemory.answer(a, days, today)
        }
        // "How is expiry going?": the expiry-day companion's reads today (none yet: the usual answer).
        if (com.optionslab.ira.ExpiryDay.asked(q)) IraCoach.expirySoFar()?.let { return it }
        // "Why did Nifty fall in the last hour": the why-story and the news answer it, not bare figures ("how much did it
        // fall" is a figure).
        if (Topic.WHY in parsed.topics && !Regex("(?i)\\bhow much\\b").containsMatchIn(q)) return null
        val st = _state.value
        com.optionslab.ira.Payoff.asked(q)?.let { return com.optionslab.ira.Payoff.say(it) }
        if (com.optionslab.ira.VixRank.asked(q)) {
            val v = st.snaps[IraMarket.VIX]?.price ?: return null
            return com.optionslab.ira.VixRank.say(histories[IraMarket.VIX]?.bars ?: return null, v)
        }
        if (com.optionslab.ira.SinceLast.asked(q)) {
            val now = LocalDateTime.now(IST)
            val mk = parsed.markets.firstOrNull() ?: synchronized(askedAt) { askedAt.keys.lastOrNull() } ?: IraMarket.NIFTY
            val (then, at) = synchronized(askedAt) { askedAt[mk] }?.takeIf { it.second.toLocalDate() == now.toLocalDate() }
                ?: return "You haven't asked me about ${mk.label} yet today, Boss - ask me how it is first."
            val nowSnap = st.snaps[mk] ?: return null
            synchronized(askedAt) { askedAt.remove(mk); askedAt[mk] = nowSnap.price to now }
            return com.optionslab.ira.SinceLast.say(mk, then, at, nowSnap, now)
        }
        if (com.optionslab.ira.Briefing.asked(q)) {
            val today = com.optionslab.app.data.Market.today()
            val ev = runCatching { IraEvents.upcoming(1).filter { it.day == today }.map { com.optionslab.ira.Events.line(it, today) } }.getOrNull().orEmpty()
            return com.optionslab.ira.Briefing.say(st.snaps, LocalDateTime.now(IST), ev)
        }
        if (com.optionslab.ira.Realised.asked(q)) {
            val mk = parsed.markets.firstOrNull { it != IraMarket.VIX && it != IraMarket.GOLD } ?: IraMarket.NIFTY
            val v = st.snaps[IraMarket.VIX]?.price ?: return null
            val said = com.optionslab.ira.Realised.say(mk, histories[mk]?.bars ?: return null, v) ?: return null
            return if (mk == IraMarket.NIFTY) said else "$said (India VIX prices Nifty's options, so for ${mk.label} it is a rough guide.)"
        }
        if (com.optionslab.ira.Together.asked(q)) {
            val (m1, m2) = IraMarket.mentioned(q).filter { it != IraMarket.VIX }.let { it[0] to it[1] }
            return com.optionslab.ira.Together.say(m1, histories[m1]?.bars ?: return null, m2, histories[m2]?.bars ?: return null)
        }
        if (com.optionslab.ira.Compare.asked(q)) return com.optionslab.ira.Compare.say(com.optionslab.ira.Compare.markets(q), st.snaps)
        // A question about the owner ("how much did I lose this week") is the account's, never a market's figure.
        if (Regex("(?i)\\b(i|me|my|we|our)\\b").containsMatchIn(q)) return null
        // India VIX named: its own candles for the time questions; the range and the odds are an index's.
        val vixAsked = parsed.markets.firstOrNull() == IraMarket.VIX
        val m = if (vixAsked) IraMarket.VIX else parsed.markets.firstOrNull { it != IraMarket.VIX } ?: IraMarket.NIFTY
        com.optionslab.ira.Odds.asked(q)?.let { odd ->
            if (vixAsked) return null
            val vix = st.snaps[IraMarket.VIX]?.price ?: return null
            return com.optionslab.ira.Odds.say(st.snaps[m] ?: return null, vix, odd, LocalDateTime.now(IST))
        }
        if (com.optionslab.ira.ExpectedRange.asked(q)) {
            if (vixAsked) return null
            val vix = st.snaps[IraMarket.VIX]?.price ?: return null
            return com.optionslab.ira.ExpectedRange.say(st.snaps[m] ?: return null, vix, LocalDateTime.now(IST))
        }
        com.optionslab.ira.Moves.asked(q)?.let { w -> return com.optionslab.ira.Moves.say(m, histories[m]?.bars ?: return null, w) }
        val trading = m.trading(LocalDateTime.now(IST)) && closedToday() == null
        val today = com.optionslab.app.data.Market.today()
        if (com.optionslab.ira.Lookback.prevAsked(q)) return com.optionslab.ira.Lookback.prevDay(m, histories[m]?.bars ?: return null, today)
        com.optionslab.ira.Lookback.time(q)?.let { at ->
            return com.optionslab.ira.Lookback.priceAt(m, histories[m]?.bars ?: return null, at, yesterday = Regex("(?i)\\byesterday\\b").containsMatchIn(q), today = today)
        }
        if (com.optionslab.ira.BigPicture.asked(q)) return com.optionslab.ira.BigPicture.say(m, histories[m]?.bars ?: return null)
        com.optionslab.ira.LevelInfo.asked(q)?.let { x -> return com.optionslab.ira.LevelInfo.say(st.snaps[m] ?: return null, x) }
        if (com.optionslab.ira.OpeningRange.asked(q)) return com.optionslab.ira.OpeningRange.say(st.snaps[m] ?: return null)
        com.optionslab.ira.PeriodMove.asked(q)?.let { span -> return com.optionslab.ira.PeriodMove.say(m, histories[m]?.bars ?: return null, span, today) }
        if (com.optionslab.ira.Momentum.asked(q)) return com.optionslab.ira.Momentum.say(m, histories[m]?.bars ?: return null, LocalDateTime.now(IST))
        if (com.optionslab.ira.Pivots.asked(q)) return com.optionslab.ira.Pivots.say(m, histories[m]?.bars ?: return null, trading, today,
            tomorrow = Regex("(?i)\\b(tomorrow|next session|monday)\\b").containsMatchIn(q))
        if (com.optionslab.ira.DayStory.asked(q)) return com.optionslab.ira.DayStory.say(m, histories[m]?.bars ?: return null)
        // Last, so none of the questions above is taken: "did Nifty gap up / fill its gap", "how many days in a row has it risen".
        if (com.optionslab.ira.Gap.asked(q)) return com.optionslab.ira.Gap.say(st.snaps[m] ?: return null)
        if (com.optionslab.ira.Streak.asked(q)) return com.optionslab.ira.Streak.say(m, histories[m]?.bars ?: return null,
            live = trading && histories[m]?.bars?.lastOrNull()?.t?.toLocalDate() == today)
        return null
    }

    /** When the news was last read for a question (so a question waits for the feeds at most once in 3 minutes). */
    @Volatile private var newsCheckedAt = 0L
    /**
     * The day Boss last asked about the news. Battery (round 7): kept in the vault as the day alone (no words, no time),
     * written at most once a day in the background ([com.optionslab.app.security.SecurePrefs.putAllSoon]) and read back
     * lazily on the first check after a restart - so a restart no longer drops the quiet pace to the slower unasked one.
     */
    @Volatile private var newsAskedOn: LocalDate? = null
    @Volatile private var newsAskedRead = false
    private const val NEWS_ASKED_KEY = "jarvis.news.asked.day"

    /** Has Boss asked about the news today? */
    fun newsAskedToday(): Boolean {
        if (!newsAskedRead) {
            newsAskedRead = true
            val kept = runCatching { com.optionslab.app.security.SecurePrefs.getString(NEWS_ASKED_KEY)?.let { d -> LocalDate.parse(d) } }.getOrNull()
            val seen = newsAskedOn
            if (kept != null && (seen == null || kept.isAfter(seen))) newsAskedOn = kept
        }
        return newsAskedOn == LocalDate.now(IST)
    }

    /** A news question now: the day noted (written only when it changes, so once a day). */
    private fun noteNewsAsked() {
        val today = LocalDate.now(IST)
        if (newsAskedOn == today) return
        newsAskedOn = today
        runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf(NEWS_ASKED_KEY to today.toString())) }
    }

    /** The feeds read now, whatever the usual 5-minute pace (for a news question). */
    private suspend fun freshNews() {
        lastNews = null
        val got = newsIfDue() ?: return
        _state.update { it.copy(news = got.first.ifEmpty { it.news }, newsAt = if (got.first.isNotEmpty()) Instant.now() else it.newsAt, newsMissing = got.second) }
    }

    /** How [m]'s day went, from the candles on the phone (for the 15:35 wrap-up), or null. */
    fun dayStory(m: IraMarket): String? = runCatching {
        val bars = histories[m]?.bars ?: return null
        // Only today's session: an old day (a failed refresh, a holiday) is never told as today's.
        if (bars.lastOrNull()?.t?.toLocalDate() != com.optionslab.app.data.Market.today()) return null
        com.optionslab.ira.DayStory.say(m, bars)
    }.getOrNull()

    /**
     * For the 15:35 wrap-up: what stood out most in the market today against the last sessions on the phone, and how to
     * hear the whole story - or null without today's session (only today's candles are read, never an old day's).
     */
    fun marketWrapLine(): String? = runCatching {
        com.optionslab.ira.MarketStory.wrapLine(histories.mapValues { it.value.bars }, LocalDateTime.now(IST), com.optionslab.app.data.Market.today())
    }.getOrNull()

    /** Each market's price and time when Boss last asked about it. */
    private val askedAt = LinkedHashMap<IraMarket, Pair<Double, LocalDateTime>>()

    /** When Boss last asked something: a follow-up carries the last question over only within [FOLLOW_MS]. */
    @Volatile private var lastAskAt = 0L
    /** The structure read Jarvis last gave (index and newest candle), for "what would change your mind?"; memory only. */
    @Volatile private var lastStructureRead: com.optionslab.ira.MindChange.Said? = null
    private const val FOLLOW_MS = 5 * 60_000L

    /** The last exchanges before [q] (Boss's words and Jarvis's reply, notes left out), oldest first, for the chat. */
    private fun recentTalk(q: String): List<Pair<String, String>> {
        val ms = _state.value.messages
        val upTo = ms.indexOfLast { !it.fromIra && it.text == q }.takeIf { it >= 0 } ?: ms.size
        val out = ArrayList<Pair<String, String>>()
        var pending: String? = null
        for (m in ms.take(upTo)) {
            if (!m.fromIra) pending = m.text
            else if (pending != null && !m.text.startsWith(TOOK_AS) && !m.text.startsWith("One moment") && !m.text.startsWith("I understood:")) {
                // The account is never carried into the chat (it could be spoken on a locked phone).
                if (runCatching { Topic.ACCOUNT !in Ask.parse(pending!!).topics }.getOrDefault(false)) out += pending!! to m.text
                pending = null
            }
        }
        return out.takeLast(2)
    }

    /** Varies the small-talk and "I don't know" lines so the same words are not said twice running. */
    private val chatTurn = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Free-form words: the on-device model picks one line of [com.optionslab.ira.Intents.LINES]; only a valid line is
     * used, said back ("I understood: ..."), and any action from it waits for Confirm. Otherwise the usual answer.
     */
    private fun freeFormAsked(q: String) {
        // Said at once (spoken while the model reads the words), then the real answer when it is ready - never silence.
        // "One moment" only when the answer is not there within 0.6 s (Boss, 4 Oct: slow replies) - said first, it held
        // a quick answer back by the two seconds it takes to say. (Before the voice's own 1 s "working on it".)
        _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
        val held = java.util.concurrent.atomic.AtomicBoolean(false)
        // Speed, round 4: with the model still to load (it leaves memory after a minute while Jarvis listens), "One
        // moment" at once - no answer can come before the load anyway ([com.optionslab.ira.ModelWait.holdAfter]).
        val modelLoaded = runCatching { IraModel.state.value.loaded }.getOrDefault(true)
        // Heard or typed, read here in the lane (the flag changes with the next question).
        val freeHeard = heardByVoice
        val holding = scope.launch {
            kotlinx.coroutines.delay(com.optionslab.ira.ModelWait.holdAfter(modelLoaded))
            if (replyAfter(_state.value.messages, q) == null && _state.value.messages.lastOrNull { !it.fromIra }?.text == q) {
                held.set(true)
                _state.update { it.copy(messages = (it.messages + Msg(true, "One moment, Boss, let me think about that.")).takeLast(MAX_MESSAGES)) }
            }
        }
        // The answer's first words: with "one moment" already said, the voice has taken that as the reply, so the answer
        // is spoken here; without it, the voice (or the typed-reply speaker) says the answer itself - never twice.
        suspend fun answerFirst(text: String) { holding.cancelAndJoin(); reply(text); if (held.get()) speakLater(text, q) }
        scope.launch {
            // Speed, round 4 (Boss's diagnostics, 5 Oct: 33 s from the answer handed to the voice to its first sound, the
            // model not loaded): the model's load and passes take the fast cores, and the speech engine making "One moment"
            // into sound waited behind them. While Jarvis listens, the model starts once that line has begun to sound
            // (2.5 s at most) - and both passes now share one budget ([com.optionslab.ira.ModelWait]; they were 15 s each).
            // Only for a heard question with the model still to load (review, 5 Oct: a typed question, or one with the
            // model loaded, waited up to 2.5 s here for a line that is not said or not needed - [com.optionslab.ira.ModelWait.waitForHold]).
            if (com.optionslab.ira.ModelWait.waitForHold(freeHeard, modelLoaded, JarvisVoice.listenOn && !JarvisVoice.muted)) {
                val since = android.os.SystemClock.elapsedRealtime()
                kotlinx.coroutines.withTimeoutOrNull(com.optionslab.ira.ModelWait.HOLD_FIRST_MS) {
                    while (JarvisVoice.speechStartedAt < since) kotlinx.coroutines.delay(100)
                }
            }
            val began = android.os.SystemClock.elapsedRealtime()
            // Words to Jarvis himself ("how was your day") go straight to the chat: no command could be meant.
            val personal = com.optionslab.ira.Chat.personal(q)
            // Words that cannot mean any command skip the first pass too: one model run, not two (Boss, 4 Oct: too slow).
            val line = if (personal || !com.optionslab.ira.Intents.mayMean(q)) null
                else modelOrNull(com.optionslab.ira.ModelWait.FIRST_PASS_MS) { IraModel.complete(com.optionslab.ira.Intents.prompt(q), 24, 8_000, oneLine = true)?.let { com.optionslab.ira.Intents.pick(it) } }
            if (line == null) {
                // Not something Jarvis can do or look up: the model just talks (a short reply with no figures, no advice
                // and no claimed actions), else a varied "I don't know that".
                // Close to something Jarvis knows: "Did you mean ...?" at once (a suggestion; nothing is done), no model wait.
                val near = if (personal) null else runCatching { com.optionslab.ira.Suggest.line(q) }.getOrNull()
                val left = com.optionslab.ira.ModelWait.left(android.os.SystemClock.elapsedRealtime() - began)
                val chat = if (near != null || !com.optionslab.ira.ModelWait.another(left)) null
                    else modelOrNull(left) { com.optionslab.ira.Chat.accept(IraModel.complete(com.optionslab.ira.Chat.prompt(q, LocalDateTime.now(IST), recentTalk(q)), 40, minOf(8_000L, left), oneLine = true)) }
                val text = near ?: chat ?: if (personal) com.optionslab.ira.Chat.aboutMe(chatTurn.getAndIncrement()) else com.optionslab.ira.Chat.fallback(chatTurn.getAndIncrement())
                // Boss asked something newer meanwhile: a late chat line would land under that answer (seen 2026-10-04).
                if (_state.value.messages.lastOrNull { !it.fromIra }?.text != q) return@launch
                // Not placed: his next wording may teach these words (questions only).
                runCatching { IraTools.missed(if (chat == null && !personal) q else "") }
                answerFirst(text)
                return@launch
            }
            // The voice checked the words as said against the lock, not what the model made of them.
            if (lockedAccount(q, line)) { answerFirst("Your account needs the phone unlocked, Boss."); return@launch }
            holding.cancelAndJoin()
            reply("I understood: \"$line\".")
            val said = com.optionslab.ira.Secrets.redact(line.trim())
            ask(line, understood = true)
            // Its answer, spoken as soon as it is there (the voice already said "one moment", or "I understood").
            val ans = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                _state.first { st -> replyAfter(st.messages, said) != null }.let { st -> replyAfter(st.messages, said)!! }
            }
            ans?.let { speakLater(it.text, said) }
        }
    }

    /** Says a reply that came after the first words (the free-form answer), when Jarvis speaks replies. */
    /** Said only while [question] is still the last thing Boss asked: a late reply never talks over a newer one. */
    private fun speakLater(text: String, question: String) {
        val c = app ?: return
        if (_state.value.messages.lastOrNull { !it.fromIra }?.text != question) return
        if (JarvisVoice.wanted || JarvisSpeaker.speakTyped) runCatching { JarvisSpeaker.speak(c, text) }
    }

    /** A command for a later time: only the allowed kinds, always confirmed first; then kept and run by its alarm. */
    private fun laterAsked(q: String, c: com.optionslab.ira.Command, w: com.optionslab.ira.Later.When) {
        _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
        if (c.kind !in com.optionslab.ira.Later.ALLOWED) {
            reply("Boss, I can set a time only for starting or stopping strategies and arms, the kill switch on, or paper mode - not that. Ask me for it when you want it done.")
            return
        }
        val ctx = app ?: return reply("I could not set that just now.")
        scope.launch {
            val (what, act) = runCatching { IraActions.prepare(c) }.getOrElse { ("I could not do that: ${it.message}") to null }
            if (act == null) { reply(what, whole = true); return@launch }
            val at = com.optionslab.ira.Later.say(w.at, java.time.LocalDateTime.now(IST))
            pend("$what $at", { IraLater.add(ctx, w.rest, w.at); "Set, Boss: I'll $what $at, and tell you when it's done." }, "Tap Confirm to $what $at.")
        }
    }

    private val FOR_LATER = Regex("(?i)\\b(set|scheduled?|planned|pending)\\b.*\\blater\\b|\\bfor later\\b")

    private val AT_ONCE = setOf(com.optionslab.ira.Command.Kind.ALARM_ADD, com.optionslab.ira.Command.Kind.EVENT_ADD)

    /**
     * [heard]: Boss said [c] himself, as heard (never a reading of his words): his nicknames for one arm or position may be
     * read, and his pick after "Which one?" may teach one ([com.optionslab.ira.Nicknames]) - on an unlocked phone only.
     * Understanding only: whatever is prepared waits for its confirm exactly as before.
     */
    private fun commandAsked(q: String, c: com.optionslab.ira.Command, confirmAlways: Boolean = false, heard: Boolean = false) {
        // Boss's "more" right after a short answer: that answer's kind noted ([com.optionslab.ira.MoreAfter]; kinds and
        // minutes only, never his words; never on a locked phone). Learning only - the "more" is answered exactly as before.
        if (c.kind == com.optionslab.ira.Command.Kind.MORE && com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { IraTools.moreAfterAsked(q, lastForMore(), phoneLocked()) }
        _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
        // Practice on a past day: replayed into the conversation, nothing traded.
        if (c.kind == com.optionslab.ira.Command.Kind.PRACTICE) { scope.launch { runCatching { IraTools.practice(c.target ?: q) { reply(it) } } }; return }
        val nickHeard = heard && !phoneLocked()
        scope.launch {
            val (what, act) = runCatching { IraActions.prepare(c, heard = nickHeard) }.getOrElse { ("I could not do that: ${it.message}") to null }
            // A nickname just learned from Boss's pick after "Which one?": said once, before the confirm it changes nothing about.
            if (nickHeard) runCatching { IraTools.nickTakeLearned() }.getOrNull()?.let { reply(it) }
            // A command's answer or result is shown and said whole: a refusal or a failure in it is never cut (review, 5 Oct).
            if (act == null) { reply(what, whole = true); return@launch }
            // Only a price alarm or an event note is done at once; anything that changes trading (starting arms, the
            // kill switch, autopilot, Jarvis's own limits) waits for Confirm - in the real app too (review, 3 Oct).
            if (c.kind.reduces || !com.optionslab.app.BuildConfig.JARVIS || confirmAlways || c.kind !in AT_ONCE) {
                // The emergency exit asks for the fingerprint on the screen (or Boss's own voice, aloud). An exit, a close
                // or a cancel is labelled by what is open now (Paper, Zerodha, or both); anything else sends no order. The
                // request is made at once with the app's standing label (never waiting on a read - least of all the
                // emergency exit); what is open is read in the background and corrects the label while it still waits.
                val cmdId = pend(what, suspend { IraActions.verified(c.kind, act()) }, "Tap Confirm to ${what}.", exit = c.kind == com.optionslab.ira.Command.Kind.EXIT_ALL,
                    venue = IraActions.venueAtOnce(c, what))
                venueLater(cmdId) { IraActions.venueOf(c, what) }
            } else reply(IraActions.run(what, suspend { IraActions.verified(c.kind, act()) }), whole = true)
        }
    }

    /**
     * A plan of steps: each prepared now (one that cannot be done stops the plan before anything runs), shown numbered,
     * confirmed once, then done in order - each prepared again at its turn, as things change - stopping at the first
     * that fails. An emergency exit among them makes the whole plan ask for the fingerprint.
     */
    private fun planAsked(q: String, steps: List<String>) {
        _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
        scope.launch {
            // A question in the plan (no command) is answered at its turn, after the steps before it were done.
            val cmds = steps.map { Ask.parse(it).command }
            // Where the plan's steps would send orders (the Requests panel's label): any exit, close or cancel by what is open
            // - labelled at once as the app stands, then read in the background, the steps side by side.
            val planSteps = ArrayList<Pair<com.optionslab.ira.Command, String>>()
            for ((i, c) in cmds.withIndex()) {
                if (c == null) continue
                val (what, act) = runCatching { IraActions.prepare(c) }.getOrElse { ("I could not do that: ${it.message}") to null }
                if (act == null) { reply("Step ${i + 1} (\"${steps[i]}\") cannot be done: $what Nothing in the plan was done.", whole = true); return@launch }
                planSteps += c to what
            }
            val plan = com.optionslab.ira.Plan.say(steps)
            val planId = pend("this plan: $plan", suspend {
                val done = ArrayList<Pair<String, String>>()
                for ((i, c) in cmds.withIndex()) {
                    if (c == null) {
                        // Answered as if asked now, in order (its answer follows this plan's report).
                        val step = steps[i]
                        scope.launch { kotlinx.coroutines.delay(400L * (i + 1)); ask(step, understood = true) }
                        done += step to "answered below."
                        continue
                    }
                    val (what, act) = runCatching { IraActions.prepare(c) }.getOrElse { ("I could not do that: ${it.message}") to null }
                    val r = if (act == null) "Not done: $what" else IraActions.run(what, suspend { IraActions.verified(c.kind, act()) })
                    done += steps[i] to r
                    if (act == null || com.optionslab.ira.Plan.failed(r)) break
                }
                com.optionslab.ira.Plan.report(done, steps.size)
            }, "My plan, Boss: $plan. Tap Confirm and I'll do them in order, stopping if one fails.",
                exit = cmds.any { it?.kind == com.optionslab.ira.Command.Kind.EXIT_ALL }, kind = com.optionslab.ira.Requests.Kind.PLAN,
                venue = com.optionslab.ira.Requests.venueOf(planSteps.map { (c, what) -> IraActions.venueAtOnce(c, what) }))
            if (planSteps.any { (c, _) -> IraActions.sendsOrders(c) }) venueLater(planId) {
                coroutineScope {
                    val read = planSteps.map { (c, what) -> async { IraActions.venueOf(c, what) } }.awaitAll()
                    com.optionslab.ira.Requests.venueOf(read)
                }
            }
            IraActivity.add("Planned ${steps.size} steps: $plan")
        }
    }

    /** The owner tapped Confirm (or said yes) on [id]: what happened, or null when it was already settled. */
    /** Emergency exits waiting: confirmed with the fingerprint on the screen, or a yes in Boss's own voice. */
    private val exitIds = HashSet<Long>()

    fun isExit(id: Long): Boolean = synchronized(actions) { id in exitIds }

    /** The last answer in full (for "tell me more"). */
    /**
     * Boss's usual market question, answered unasked (Jarvis's own initiative from his habits): the same answer he would
     * get by asking, said as Jarvis's note - never the account, never an action.
     */
    fun usualAnswer(question: String): String? = marketAnswer(question)?.let { "Boss, your usual around now (${question}): $it" }

    /** A market question's answer said unasked (never the account, never an action), or null on old data or offline. */
    fun marketAnswer(question: String): String? {
        val q = Ask.parse(question)
        if (q.command != null || q.order != null || Topic.ACCOUNT in q.topics) return null
        val a = runCatching { Ira(book, runCatching { IraTools.patternCalls() }.getOrDefault(emptyList())).answer(question, _state.value.snaps,
            _state.value.news, voice = com.optionslab.app.BuildConfig.JARVIS, now = LocalDateTime.now(IST)) }.getOrNull() ?: return null
        // Stale data (prices, candles or news it rests on) is not offered unasked.
        val stale = runCatching { ageChecks(q.markets, q.topics).any { it.level == com.optionslab.ira.DataAge.Level.STALE } }.getOrDefault(false)
        if (offlineNote() != null || stale) return null
        if (a.calls.isNotEmpty()) runCatching { IraTools.patternsTold(a.calls) }
        return a.text
    }

    /**
     * A routine's account question answered at its time ([com.optionslab.ira.Routine]: his P&L, the week's events), read
     * as when he asks (8 seconds at most), or null. Reads only; the caller keeps his P&L off a locked phone.
     */
    suspend fun accountAnswer(question: String): String? {
        val q = Ask.parse(question)
        if (q.command != null || q.order != null || !online()) return null
        val read = scope.async { runCatching { IraAccount.readFast(com.optionslab.ira.AppAnswers.sections(question), IraMarket.mentioned(question), question = question) }.getOrNull() }
        val v = withTimeoutOrNull(8_000) { read.await() }
        if (v == null) { read.cancel(); return null }
        return runCatching { Ira(book).answer(question, emptyMap(), emptyList(), app = v).text }.getOrNull()
    }

    fun lastFullAnswer(): String? = _state.value.messages.lastOrNull { it.fromIra }?.text

    /**
     * The last message of Jarvis's for "more" ([com.optionslab.ira.MoreAnswer]): its text, the question before it, and
     * whether it was a note nobody asked for - so a locked phone re-checks it as "go on" and "say that again" do.
     */
    fun lastForMore(): com.optionslab.ira.MoreAnswer.Last? {
        val ms = _state.value.messages
        val i = ms.indexOfLast { it.fromIra }
        if (i < 0) return null
        val m = ms[i]
        val before = ms.subList(0, i).lastOrNull { !it.fromIra }?.text
        return com.optionslab.ira.MoreAnswer.Last(m.text, before, unasked = synchronized(unaskedIds) { m.id in unaskedIds })
    }

    /** [by]: how the yes came (a tap, the voice, the notification), kept for Recent's record only - it changes no gate. */
    suspend fun confirm(id: Long, fingerprint: Boolean = false, ownerVoice: Boolean = false, by: com.optionslab.ira.Requests.By? = null): String? {
        // IraGoldAlgo: nothing Jarvis prepared is ever done there.
        if (GOLD_ONLY_TALK) { synchronized(actions) { exitIds.remove(id); actions.remove(id) }; _state.update { it.copy(pending = it.pending - id) }; return GOLD_TALK_ONLY.also { reply(it) } }
        // Whether a yes on it asked for the fingerprint, read while it still waits (for Recent's record).
        val askedFp = runCatching { needsFingerprint(id) }.getOrNull()
        if (isExit(id) && !fingerprint && !ownerVoice && fingerprintNeeded())
            return synchronized(actions) { actions.containsKey(id) }.let { waiting -> if (!waiting) null else
                "The emergency exit is confirmed with your fingerprint on the Jarvis screen, or by saying yes in your own voice.".also { reply(it, whole = true) } }
        // A trade that goes to Zerodha with real money needs the owner's fingerprint on the Jarvis screen (a spoken yes or
        // the notification's Approve is not enough); it stays waiting until then.
        if (asksYesNo(id) && !fingerprint && IraNewsTrades.goesLive(isSolo(id)) && fingerprintNeeded())
            return synchronized(actions) { actions.containsKey(id) }.let { waiting -> if (!waiting) null else
                "This trade goes to Zerodha with real money: approve it with your fingerprint on the Jarvis screen.".also { reply(it, whole = true) } }
        // Real money only with the fingerprint (or a phone with none, where the app lock covers it): the trade checks this
        // again when placed, so a record that turns proven between now and then never sends one unapproved.
        val yes = asksYesNo(id)
        val a = synchronized(actions) { exitIds.remove(id); actions.remove(id)?.also { confirming += id } } ?: return null
        return try {
            if (yes && (fingerprint || !fingerprintNeeded())) synchronized(actions) { liveApproved += id }
            if (asksYesNo(id)) IraNewsTrades.answered(id, "approved")
            settled(id)
            _state.update { it.copy(pending = it.pending - id) }
            IraActions.run(a.first, a.second).also { synchronized(actions) { liveApproved.remove(id) }; IraAccount.invalidate(); checked = null; reply(it, whole = true)
                requestDone(id, com.optionslab.ira.Requests.outcomeOf(it), by ?: if (fingerprint) com.optionslab.ira.Requests.By.FINGERPRINT else null, it, askedFp) }
        } finally {
            synchronized(actions) { confirming.remove(id) }
        }
    }

    /** Does a live trade's approval ask for the fingerprint? (whenever the phone has one; without one the app lock covers it) */
    fun fingerprintNeeded(): Boolean = app?.let { com.optionslab.app.security.BiometricGate.fingerprintOn(it) } ?: true

    /** Is [id] a trade waiting that will need the fingerprint (the Jarvis screen shows the fingerprint button)? */
    fun needsFingerprint(id: Long): Boolean = (isExit(id) || asksYesNo(id) && IraNewsTrades.goesLive(isSolo(id))) && fingerprintNeeded()

    private fun isSolo(id: Long): Boolean = synchronized(actions) { id in soloAsks }

    /** [said]: Boss's spoken words for the rejection ("no, too late in the day"), when he said it aloud. */
    /** [by]: how the no came (a tap, the voice, the notification), kept for Recent's record only. */
    fun cancelAction(id: Long, said: String? = null, by: com.optionslab.ira.Requests.By? = null) {
        // Whether a yes on it would have asked for the fingerprint, read while it still waits (for Recent's record).
        val askedFp = runCatching { needsFingerprint(id) }.getOrNull()
        // Only what is still waiting can be cancelled: one already confirmed (or lapsed) is not said to be undone.
        val (was, trade) = synchronized(actions) { exitIds.remove(id); (actions.remove(id) != null) to (id in newsAsks) }
        if (!was) { _state.update { it.copy(pending = it.pending - id) }; return }
        requestDone(id, com.optionslab.ira.Requests.Outcome.DECLINED, by,
            if (trade) "Rejected; nothing was placed." else "Cancelled; nothing was done.", askedFp)
        if (trade) IraNewsTrades.answered(id, "rejected")
        // A trade idea turned down: the reason he gave with it, or in his very next words, is noted - its kind only
        // ([com.optionslab.ira.TurnDowns]). It is said up front before the next idea it fits; nothing learned acts.
        if (trade && com.optionslab.app.BuildConfig.JARVIS) runCatching { IraTools.turnedDown(said) }
        settled(id)
        IraActivity.add(if (trade) "You rejected a suggested trade; nothing was placed." else "Cancelled a request; nothing was done.")
        _state.update { it.copy(pending = it.pending - id, messages = (it.messages + Msg(true, "Cancelled; nothing was done.")).takeLast(MAX_MESSAGES)) }
    }

    /** How long a request waiting for Confirm stays open before it lapses (nothing done). */
    const val CONFIRM_LAPSE_MS = 30 * 60_000L

    /** A request that waits for Confirm, with its message; it lapses after [CONFIRM_LAPSE_MS]. */
    /**
     * [lead]: words before the chat's short line (an unasked offer's own question). [kind], [venue], [symbol], [qty] and
     * [price]: what the Requests panel shows of it (an exit acts in the app's mode;
     * anything else not said is no order). The chat shows one short line naming it; the details and the buttons are in
     * the panel (and under that line).
     */
    private fun pend(what: String, act: suspend () -> String, text: String, exit: Boolean = false,
                     kind: com.optionslab.ira.Requests.Kind? = null, venue: com.optionslab.ira.Requests.Venue? = null,
                     symbol: String? = null, qty: String? = null, price: String? = null, lead: String? = null): Long {
        val id = System.nanoTime()
        val askedAt = System.currentTimeMillis()
        val view = com.optionslab.ira.Requests.RequestView(id, kind ?: if (exit) com.optionslab.ira.Requests.Kind.EXIT else com.optionslab.ira.Requests.Kind.COMMAND,
            com.optionslab.ira.Requests.title(what), what, com.optionslab.ira.Requests.why(text),
            venue ?: if (exit) modeVenue() else com.optionslab.ira.Requests.Venue.NONE, askedAt, askedAt + CONFIRM_LAPSE_MS, symbol, qty, price)
        // An emergency exit is known as one before anyone can see it (the voice then asks it in Boss's voice only).
        synchronized(actions) { actions[id] = what to act; if (exit) exitIds += id; requestMeta[id] = view }
        _state.update { it.copy(pending = it.pending + id, messages = (it.messages + Msg(true, listOfNotNull(lead, com.optionslab.ira.Requests.chatLine(view.title, view.what)).joinToString(" "), action = id)).takeLast(MAX_MESSAGES)) }
        scope.launch {
            kotlinx.coroutines.delay(CONFIRM_LAPSE_MS)
            val lapsedFp = runCatching { needsFingerprint(id) }.getOrNull()
            if (synchronized(actions) { exitIds.remove(id); actions.remove(id) } != null) {
                requestDone(id, com.optionslab.ira.Requests.Outcome.LAPSED, result = "No Confirm in 30 minutes; nothing was done.", fingerprint = lapsedFp)
                _state.update { it.copy(pending = it.pending - id) }
                reply("Nothing was done about \"$what\": it waited 30 minutes for your Confirm.", whole = true)
            }
        }
        return id
    }

    /**
     * Every request still waiting is dropped (the conversation was cleared, or everything forgotten): forgotten, not
     * lapsed - none is added to the Requests panel's Recent list, and that list is cleared too (Boss asked to forget).
     */
    private fun dropPending() {
        val ids = synchronized(actions) { actions.keys.toList() }
        ids.forEach { id ->
            val (was, trade) = synchronized(actions) { exitIds.remove(id); (actions.remove(id) != null) to (id in newsAsks) }
            if (was && trade) IraNewsTrades.answered(id, "rejected")
            if (was) settled(id)
        }
        // Every view goes (a confirm still running when forgotten is not listed afterwards either).
        synchronized(actions) { requestMeta.clear() }
        _recent.value = emptyList()
        _state.update { it.copy(pending = emptySet()) }
    }

    /** A complete order in Jarvis: the contract is found and the trade placed at once, in the app's mode. */
    private fun tradeAsked(q: String, o: OrderRequest) {
        _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
        scope.launch {
            val s = com.optionslab.app.data.AppSettings.load()
            val spot = o.market?.let { m -> _state.value.snaps[m] }?.price
            val r = runCatching { IraOrders.prepare(o, s.live, s.guardMaxLots, spot) }.getOrElse { Result.failure(it) }
            reply(r.fold({ t -> runCatching { IraActions.trade(t, s.live) }.getOrElse { e -> "That order failed: ${e.message}" } },
                { e -> e.message ?: "I could not prepare that order." }), whole = true)
        }
    }

    /**
     * "Should I trade now?" - everything the app knows, weighed by [com.optionslab.ira.TradeCheck]: whether trading is
     * possible, today's risks, your day so far, and each arm's tested record; with how Nifty and BankNifty are moving.
     */
    @Volatile private var checked: Pair<Long, com.optionslab.ira.TradeCheck.Verdict>? = null

    /** 0 when the last trade check said go (or none yet), 1 careful, 2 don't trade: the globe's amber. */
    fun caution(): Float = when (checked?.second?.level) {
        com.optionslab.ira.TradeCheck.Level.CAREFUL -> 1f
        com.optionslab.ira.TradeCheck.Level.STOP -> 2f
        else -> 0f
    }

    /** The trade check as worked out in the last minute (kept ready while Jarvis listens), else afresh. */
    suspend fun tradeCheckFast(): com.optionslab.ira.TradeCheck.Verdict {
        val now = android.os.SystemClock.elapsedRealtime()
        checked?.takeIf { now - it.first < 60_000 && testHistories == null }?.let { return it.second }
        return tradeCheck().also { checked = now to it }
    }

    /**
     * Keeps the slow answers ready (called every 30 s while Jarvis listens): your account, and the trade check - every
     * 30 s while something can use it within the minute ([checkKeptFast]), else every 2 minutes
     * ([com.optionslab.ira.CheckWarmPace]; Battery, round 9). [tradeCheckFast] never uses one a minute old or more.
     * [accountQuiet]: the screen off and nothing held or armed - the account is then read ahead about every 2 minutes
     * ([com.optionslab.ira.AccountWarmPace]; Battery, round 11).
     */
    suspend fun warm(accountQuiet: Boolean = false) {
        if (GOLD_ONLY_TALK) return
        runCatching { IraAccount.warm(accountQuiet) }
        val warmAt = android.os.SystemClock.elapsedRealtime()
        val checkAt = checked?.first
        if (com.optionslab.ira.CheckWarmPace.due(checkAt?.let { warmAt - it }, checkKeptFast()))
            runCatching { checked = android.os.SystemClock.elapsedRealtime() to tradeCheck() }
    }

    /**
     * Can anything use a trade check within the minute (Battery, round 9)? Solo on (it then reaches its gate,
     * [IraSolo.tick]), Jarvis taking paper trades alone ([Automations.Auto.ACT_PAPER]), or anything
     * held or waiting to fill: the paper book, Solo's or the news trades' own, and in Live with a Zerodha session always
     * (its positions are not read here). A read failing says yes.
     */
    private fun checkKeptFast(): Boolean = runCatching {
        val soloGate = IraSolo.on
        val alone = Automations.on(Automations.Auto.ACT_PAPER)
        val held = com.optionslab.app.data.Paper.watching() || IraSolo.all().any { !it.closed } || IraNewsTrades.all().any { !it.closed } ||
            (com.optionslab.app.data.AppSettings.load().live && com.optionslab.app.data.Broker.loggedIn)
        com.optionslab.ira.CheckWarmPace.fast(soloGate, alone, held)
    }.getOrDefault(true)

    /**
     * The day's P&L so far (Zerodha's in Live, else the paper account's), or null when it cannot be read. [paperReuseMs]
     * as [com.optionslab.app.data.Paper.snapshot] takes it: 0 (fresh) for the trade check, which gates Solo's entries and
     * the trade ideas; [com.optionslab.app.data.Paper.SHARED_QUOTE_MS] for words only (Battery, round 8).
     */
    // The paper figure is AFTER charges (Paper.Snapshot.dayPnl) on purpose: the trade check's daily loss limit keeps the
    // net, safer figure; only the screens and Jarvis's P&L answers say it before charges (Boss, 5 Oct).
    private suspend fun dayPnlNow(live: Boolean, paperReuseMs: Long = 0L): Double? =
        if (live) runCatching { com.optionslab.app.data.Broker.within(8_000) { com.optionslab.app.data.Broker.positionBook().m2m } }.getOrNull()
        else runCatching { com.optionslab.app.data.Paper.snapshot(paperReuseMs).dayPnl }.getOrNull()

    /** What Boss told about himself that bears on a trade check now ([com.optionslab.ira.AboutBoss.recall]), or null. Words only. */
    private suspend fun aboutBossNow(): String? {
        val notes = IraTools.memory().map { it.text }
        if (notes.isEmpty()) return null
        val m = com.optionslab.app.data.Market
        val live = com.optionslab.app.data.AppSettings.load().live
        return com.optionslab.ira.AboutBoss.recall(notes, com.optionslab.ira.AboutBoss.Moment(m.today(), com.optionslab.ira.AboutBoss.At.CHECK,
            // Words only, said right after the trade check (whose fresh read it then shares): Battery, round 8.
            dayPnl = dayPnlNow(live, com.optionslab.app.data.Paper.SHARED_QUOTE_MS),
            expiryToday = listOf("NIFTY", "BANKNIFTY", "FINNIFTY").any { runCatching { m.isExpiryDay(it) }.getOrDefault(false) }))
    }

    suspend fun tradeCheck(): com.optionslab.ira.TradeCheck.Verdict = com.optionslab.ira.TradeCheck.check(tradeNow())

    /** Everything the trade check reads now ([com.optionslab.ira.TradeCheck.Now]); also what "make the case" starts from. */
    private suspend fun tradeNow(): com.optionslab.ira.TradeCheck.Now {
        val s = com.optionslab.app.data.AppSettings.load()
        val m = com.optionslab.app.data.Market
        val st = _state.value
        val bn = histories[IraMarket.BANKNIFTY]?.bars.orEmpty()
        val bnSnap = st.snaps[IraMarket.BANKNIFTY]
        // The two network reads at once, each bounded (speed round 10): the day's P&L (Zerodha's, at most 8 s) and the
        // static IP check (ipify, whose blocking read could hold the answer up to 12 s; past IP_CHECK_MS it is unknown).
        val dayPnlAsk = scope.async { dayPnlNow(s.live) }
        val ipAsk = com.optionslab.app.data.StaticIp.registered?.let {
            scope.async { runCatching { com.optionslab.app.data.StaticIp.status(force = false).matches }.getOrNull() }
        }
        val arms = ArrayList<String>()
        runCatching { com.optionslab.app.data.OrbArms.view().arms }.getOrDefault(emptyList()).filter { it.armed }.forEach { arms += it.arm.label }
        com.optionslab.app.data.PineScripts.items.value.filter { it.auto.on }.forEach { arms += it.name }
        runCatching { com.optionslab.app.data.Strategies.all() }.getOrDefault(emptyList()).filter { it.def.scheduler?.enabled == true }.forEach { arms += it.def.name }
        val today = m.today()
        val events = runCatching { IraEvents.upcoming(0) }.getOrDefault(emptyList()).filter { it.day == today && !it.name.endsWith("expiry") }.map { it.name }
        val ipOk = ipAsk?.let { withTimeoutOrNull(IP_CHECK_MS) { it.await() } }
        val dayPnl = dayPnlAsk.await()
        val burst =if (bn.isNotEmpty()) com.optionslab.ira.Watch.move30(bn)?.first else null
        return com.optionslab.ira.TradeCheck.Now(
            marketOpen = m.isOpen(), tradingDay = m.isTradingDay(today), minute = m.minuteNow(),
            liveMode = s.live, zerodhaLoggedIn = com.optionslab.app.data.Broker.loggedIn, staticIpOk = ipOk,
            pricesFresh = !m.isOpen() || st.liveAt?.isAfter(Instant.now().minusSeconds(180)) == true,
            killSwitch = s.guardKill, breakerTripped = com.optionslab.app.data.LossBreaker.trippedToday(),
            botsStopped = runCatching { com.optionslab.app.data.Strategies.stoppedToday() }.getOrDefault(false),
            dayPnl = dayPnl, dayLossLimit = if (s.live) s.guardDailyLoss else s.guardPaperDailyLoss,
            vix = st.snaps[IraMarket.VIX]?.price, vixChangePct = st.snaps[IraMarket.VIX]?.changePct,
            indexChangePct = bnSnap?.changePct, gapPct = bnSnap?.prevClose?.let { pc -> (bnSnap.open - pc) / pc * 100 },
            burst30Pct = burst, usual30Pct = com.optionslab.ira.Watch.typical30(bn),
            openingRangeRatio = if (m.minuteNow() >= 10 * 60) com.optionslab.ira.TradeCheck.openingRangeRatio(bn) else null,
            eventsToday = events, expiryToday = listOf("NIFTY", "BANKNIFTY", "FINNIFTY").any { runCatching { m.isExpiryDay(it) }.getOrDefault(false) },
            armsOn = arms,
            reads = listOf(IraMarket.NIFTY, IraMarket.BANKNIFTY).mapNotNull { mk -> st.snaps[mk]?.let { com.optionslab.ira.TradeCheck.read(it) } },
        )
    }

    /**
     * "Make the case", "pros and cons of trading now" ([com.optionslab.ira.TradeCase]): the trade check reasoned out from
     * what Jarvis already knows - the session and guards, today's risks, what stands out against the recent sessions,
     * the days ahead, the arms' tested records, his own record in conditions like now's - and, on an unlocked phone
     * only, Boss's day against his limit, his goals, his rules, what he told about himself and his habits around a
     * trade. Facts both ways, never a buy, sell or verdict: the decision is Boss's. Words only - it changes nothing.
     */
    private suspend fun tradeCase(): String {
        val locked = phoneLocked()
        // The Nifty chain read afresh while the trade check's own reads are made (speed round 10: not one after the other).
        val chainFetch = scope.async { runCatching { withTimeoutOrNull(CASE_CHAIN_MS) { IraAccount.chain("NIFTY") } } }
        val now = tradeNow()
        val mine = if (locked) null else runCatching {
            val live = com.optionslab.app.data.AppSettings.load().live
            val owners = runCatching { com.optionslab.app.data.Strategies.owners() }.getOrDefault(emptyMap())
            com.optionslab.ira.TradeCase.Mine(
                notes = IraTools.memory().map { it.text },
                goals = runCatching { IraGoals.statuses() }.getOrDefault(emptyList()),
                own = IraAccount.trips(live, owners).filter { it.owner.startsWith("Manual") },
                maxTrades = IraGoals.all().filter { it.kind == com.optionslab.ira.Goals.Kind.MAX_TRADES }.minOfOrNull { it.amount.toInt() },
            )
        }.getOrNull()
        val upcoming = runCatching { IraEvents.upcoming(com.optionslab.ira.TradeCase.AHEAD_DAYS) }.getOrDefault(emptyList())
        val calibration = runCatching { IraNewsTrades.calibration() }.getOrDefault(emptyList()) + runCatching { IraSolo.calibration() }.getOrDefault(emptyList())
        // The Nifty option chain's facts (the straddle's implied move, the biggest OI, the skew), with the chain's time:
        // read afresh when it comes quickly, else the last read kept today. Market data, so said on a locked phone too.
        val chainRead = runCatching {
            withTimeoutOrNull(CASE_CHAIN_MS) { chainFetch.await() }
            val today = com.optionslab.app.data.Market.today()
            IraAccount.chainBook.latest("NIFTY")?.takeIf { it.at.toLocalDate() == today }
        }.getOrNull()
        val chainFacts = chainRead?.let { r -> runCatching { com.optionslab.ira.ChainIntel.caseFacts(r, com.optionslab.app.data.Market.today()) }.getOrNull() }.orEmpty()
        // The chain read also goes in whole: its call and put writing set against the day's structure, and its spot
        // against the candle of the same minute ([com.optionslab.ira.Consistency]) - facts pulling apart said plainly.
        return com.optionslab.ira.TradeCase.build(com.optionslab.ira.TradeCase.Input(
            now = now, at = LocalDateTime.now(IST), bars = histories.mapValues { it.value.bars }, upcoming = upcoming,
            calibration = calibration, regime = runCatching { IraStudy.regimeOf(IraMarket.NIFTY) }.getOrNull(),
            mine = mine, locked = locked, chain = chainFacts, chainRead = chainRead,
        )).say()
    }

    /**
     * "Any contradictions?" ([com.optionslab.ira.Consistency]): Nifty's structure, the prior close, the chain's call and
     * put writing and India VIX's move set side by side for facts pointing different ways; the chain's spot against the
     * candle of the same minute; and, on an unlocked phone only, Boss's rules and trade goal against his trades today.
     * Market data is fine on a locked phone; his words and trades are not. Words only.
     */
    private suspend fun consistency(): String {
        val locked = phoneLocked()
        val today = com.optionslab.app.data.Market.today()
        val bars = histories[IraMarket.NIFTY]?.bars.orEmpty()
        val chainRead = runCatching {
            withTimeoutOrNull(CASE_CHAIN_MS) { IraAccount.chain("NIFTY") }
            IraAccount.chainBook.latest("NIFTY")?.takeIf { it.at.toLocalDate() == today }
        }.getOrNull()
        val structure = runCatching { com.optionslab.ira.Structure.read(IraMarket.NIFTY, bars, today) }.getOrNull()
        val vixChange = _state.value.snaps[IraMarket.VIX]?.changePct
        val tensions = runCatching { com.optionslab.ira.Consistency.tensions(com.optionslab.ira.Consistency.leans(structure, chainRead, vixChange)) }
            .getOrDefault(emptyList())
        val mismatch = chainRead?.let { r -> runCatching { com.optionslab.ira.Consistency.priceCheck(IraMarket.NIFTY, bars, com.optionslab.ira.Consistency.chainQuote(r)) }.getOrNull() }
        val clashes = if (locked) null else IraCoach.wordClashes()
        return com.optionslab.ira.Consistency.say(tensions, mismatch, clashes, locked)
    }

    /**
     * "What matters right now?" ([com.optionslab.ira.CoPilot]): candidate facts gathered from every reader - today's sharp
     * moves, Nifty's and BankNifty's structure, the Nifty chain, the most-carried stories, facts pulling different ways,
     * his own numbers disagreeing, data age, the calendar, his own record in conditions like now and, on an unlocked
     * phone only, Boss's open positions, loss limits and words against today - ranked, the top few said with why. Reads
     * only; words only.
     */
    private suspend fun coPilot(): String {
        val locked = phoneLocked()
        val nowAt = LocalDateTime.now(IST)
        val today = com.optionslab.app.data.Market.today()
        val st = _state.value
        // The Nifty chain read afresh from the start, while the candles are read (speed round 10).
        val chainFetch = scope.async { runCatching { withTimeoutOrNull(CASE_CHAIN_MS) { IraAccount.chain("NIFTY") } } }
        val facts = ArrayList<com.optionslab.ira.CoPilot.Fact>()
        for (m in com.optionslab.ira.SharpMove.INDICES) runCatching { facts += com.optionslab.ira.CoPilot.moves(m, histories[m]?.bars.orEmpty()) }
        val niftyBars = histories[IraMarket.NIFTY]?.bars.orEmpty()
        val structure = runCatching { com.optionslab.ira.Structure.read(IraMarket.NIFTY, niftyBars, today) }.getOrNull()
        com.optionslab.ira.CoPilot.structure(structure)?.let { facts += it }
        runCatching { com.optionslab.ira.CoPilot.structure(com.optionslab.ira.Structure.read(IraMarket.BANKNIFTY, histories[IraMarket.BANKNIFTY]?.bars.orEmpty(), today)) }
            .getOrNull()?.let { facts += it }
        val chainRead = runCatching {
            withTimeoutOrNull(CASE_CHAIN_MS) { chainFetch.await() }
            IraAccount.chainBook.latest("NIFTY")?.takeIf { it.at.toLocalDate() == today }
        }.getOrNull()
        runCatching { com.optionslab.ira.CoPilot.chain(chainRead, today) }.getOrNull()?.let { facts += it }
        runCatching { facts += com.optionslab.ira.CoPilot.news(st.news, Instant.now(), IST) }
        runCatching {
            facts += com.optionslab.ira.CoPilot.tensions(com.optionslab.ira.Consistency.tensions(
                com.optionslab.ira.Consistency.leans(structure, chainRead, st.snaps[IraMarket.VIX]?.changePct)), IraMarket.NIFTY, nowAt)
        }
        chainRead?.let { r -> runCatching { com.optionslab.ira.CoPilot.mismatch(com.optionslab.ira.Consistency.priceCheck(IraMarket.NIFTY, niftyBars,
            com.optionslab.ira.Consistency.chainQuote(r))) }.getOrNull() }?.let { facts += it }
        runCatching { facts += com.optionslab.ira.CoPilot.data(ageChecks(listOf(IraMarket.NIFTY), emptySet(), all = true), nowAt) }
        runCatching { facts += com.optionslab.ira.CoPilot.events(IraEvents.upcoming(2), today) }
        runCatching {
            val calibration = runCatching { IraNewsTrades.calibration() }.getOrDefault(emptyList()) + runCatching { IraSolo.calibration() }.getOrDefault(emptyList())
            com.optionslab.ira.CoPilot.record(calibration, nowAt, runCatching { IraStudy.regimeOf(IraMarket.NIFTY) }.getOrNull())?.let { facts += it }
        }
        // Boss's account: never read for this on a locked phone.
        var held = emptySet<IraMarket>()
        if (!locked) {
            val legs = runCatching { IraCoach.openLegs() }.getOrDefault(emptyList())
            held = legs.filter { it.qty != 0 }.mapNotNull { l -> IraMarket.entries.firstOrNull { it.name.equals(l.underlying, true) } }.toSet()
            runCatching { facts += com.optionslab.ira.CoPilot.positions(legs) }
            runCatching {
                val set = com.optionslab.app.data.AppSettings.load()
                val pnl = HashMap<String, Double>()
                // Words only: shares the prices [IraCoach.openLegs] read just above (Battery, round 8). Measured against the
                // daily loss limits, so the paper day AFTER charges (dayPnl), never the before-charges figure the screens show.
                runCatching { com.optionslab.app.data.Paper.snapshot(com.optionslab.app.data.Paper.SHARED_QUOTE_MS).dayPnl }.getOrNull()?.let { pnl["Paper"] = it }
                if (com.optionslab.app.data.Broker.loggedIn) runCatching {
                    com.optionslab.app.data.Broker.within(8_000) { com.optionslab.app.data.Broker.positionBook() }?.m2m
                }.getOrNull()?.let { pnl["Zerodha"] = it }
                facts += com.optionslab.ira.CoPilot.limits(mapOf("Zerodha" to set.guardDailyLoss, "Paper" to set.guardPaperDailyLoss), pnl)
            }
            runCatching { facts += com.optionslab.ira.CoPilot.clashes(IraCoach.wordClashes()) }
        }
        val head = runCatching { com.optionslab.ira.Briefing.say(st.snaps, nowAt, emptyList()) }.getOrNull()
        return com.optionslab.ira.CoPilot.brief(head, facts, held, nowAt, locked)
    }

    /**
     * "What changed since this morning?" ([com.optionslab.ira.SinceMorning]): the first asked index's chain read afresh (so
     * the newest read is kept), then the candles, the day's chain reads, the news and - on an unlocked phone only - Boss's
     * open legs against those noted near 09:45. Reads only.
     */
    private suspend fun sinceMorning(markets: List<IraMarket>): String {
        val locked = phoneLocked()
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        val today = now.toLocalDate()
        val indices = com.optionslab.ira.SinceMorning.indices(markets)
        runCatching { withTimeoutOrNull(CASE_CHAIN_MS) { IraAccount.chain(indices.first().name) } }
        val chains = indices.associate { m -> m.name to runCatching { IraAccount.chainBook.day(m.name, today) }.getOrDefault(emptyList()) }
        val bars = (indices + IraMarket.VIX).associateWith { histories[it]?.bars.orEmpty() }
        // Boss's account: never read for this on a locked phone.
        val positions = if (locked) null else runCatching {
            val (open, zerodha) = IraCoach.openLegsRead()
            val legs = open.filter { it.qty != 0 }.map { com.optionslab.ira.SinceMorning.Held(it.where, it.symbol, it.qty) }
            val morning = IraTools.morningHeld(today)
            com.optionslab.ira.SinceMorning.Positions(morning?.held, legs, morning?.zerodha ?: com.optionslab.ira.SinceMorning.Zerodha.READ, zerodha,
                morningZerodhaAt = morning?.zerodhaAt)
        }.getOrNull()
        return com.optionslab.ira.SinceMorning.answer(indices, bars, chains, _state.value.news, IST, positions, locked, now)
    }

    /** A morning-legs read in flight (the broker may take up to 8 seconds; refreshes don't stack reads). */
    private val morningHeldBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Boss's open legs noted near the morning mark (09:40 to 10:15, in session), in the background: noted on the first
     * read, and - while Zerodha's legs could not be read (the broker timed out, or not logged in) - read again on each
     * refresh in the window, Zerodha's legs filled in once read. Not read by 10:15, the note says so and the answer too.
     */
    private fun morningHeldIfDue() {
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        val mark = com.optionslab.ira.SinceMorning.MARK
        val t = now.toLocalTime()
        if (t.isBefore(mark.minusMinutes(5)) || !t.isBefore(mark.plusMinutes(30)) || !IraMarket.NIFTY.trading(now)) return
        if (IraTools.morningHeldNoted(now.toLocalDate())) return
        if (!morningHeldBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                val (legs, zerodha) = runCatching { IraCoach.openLegsRead() }.getOrNull() ?: return@launch
                // The time the read came back: a read finishing past 10:15 notes nothing, and a late Zerodha read is said with it.
                val readAt = com.optionslab.app.data.Market.now().toLocalTime()
                IraTools.noteMorningHeld(now.toLocalDate(), legs.filter { it.qty != 0 }.map { com.optionslab.ira.SinceMorning.Held(it.where, it.symbol, it.qty) }, zerodha, readAt)
            } finally { morningHeldBusy.set(false) }
        }
    }

    /** "Make the case" waits at most this long for a fresh option chain. */
    private const val CASE_CHAIN_MS = 8_000L

    /** The trade check waits at most this long for the static IP check (unknown past it; Zerodha still decides). */
    private const val IP_CHECK_MS = 8_000L

    /**
     * "Where is the most call writing?", "how has OI shifted since morning?", "what's the IV skew?", "the expected move
     * by expiry from the straddle?" ([com.optionslab.ira.ChainIntel]): the chain read afresh, compared with the day's
     * first read kept in memory. The chain's own numbers with its time (and its age when old); never advice.
     */
    private suspend fun chainIntel(a: com.optionslab.ira.ChainIntel.Ask, markets: List<IraMarket>): String {
        val u = com.optionslab.ira.ChainIntel.underlying(markets) ?: return com.optionslab.ira.ChainIntel.NOT_HERE
        val name = IraMarket.valueOf(u).label
        withTimeoutOrNull(25_000) { IraAccount.chain(u) } ?: return "The $name option chain did not load just now, Boss."
        val now = IraAccount.chainBook.latest(u) ?: return "The $name option chain did not load just now, Boss."
        val today = com.optionslab.app.data.Market.today()
        val note = runCatching { IraTools.chainNote(u) }.getOrNull()
        return listOfNotNull(note, com.optionslab.ira.ChainIntel.answer(a, now, IraAccount.chainBook.first(u, today), today)).joinToString(" ")
    }

    /**
     * The index's past sessions read down for [straddleDecay], [expiryHour] and [atmBuy] in one stream
     * ([com.optionslab.ira.PastReads]), kept until the day or the phone's kept days change.
     */
    private fun pastReads(u: String, today: java.time.LocalDate): com.optionslab.ira.PastReads.Read {
        val kept = Store.deviceBarDays(u).filter { it.isBefore(today) }
        return com.optionslab.ira.PastReads.of(u, "$today|${kept.size}|${kept.lastOrNull()}", today) { Store.barSessions(u) }
    }

    /**
     * "How much does the ATM straddle usually lose between 9:30 and 2:30?" ([com.optionslab.ira.StraddleDecay]): the index's
     * sessions with option prices on the phone (bundled and harvested), each streamed once and read down to a few numbers,
     * then today's kept chain. Reads only.
     */
    private fun straddleDecay(a: com.optionslab.ira.StraddleDecay.Q, markets: List<IraMarket>): String {
        val m = com.optionslab.ira.StraddleDecay.market(markets) ?: return com.optionslab.ira.StraddleDecay.NOT_HERE
        val u = m.name
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        val today = com.optionslab.app.data.Market.today()
        val past = pastReads(u, today).straddleDays
        val todays = runCatching { Store.barSession(u, today) }.getOrNull()
        return com.optionslab.ira.StraddleDecay.answer(a, m, past, todays, today, now)
    }

    /**
     * "How does the ATM option's premium behave in the last hour on expiry day?" ([com.optionslab.ira.ExpiryHour]): the index's
     * sessions with option prices on the phone (bundled and harvested), each streamed once and read down to a few numbers,
     * then today's kept chain. Reads only.
     */
    private fun expiryHour(a: com.optionslab.ira.ExpiryHour.Q, markets: List<IraMarket>): String {
        val m = com.optionslab.ira.ExpiryHour.market(markets) ?: return com.optionslab.ira.ExpiryHour.NOT_HERE
        val u = m.name
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        val today = com.optionslab.app.data.Market.today()
        val past = pastReads(u, today).expiryHourDays
        val todays = runCatching { Store.barSession(u, today) }.getOrNull()
        return com.optionslab.ira.ExpiryHour.answer(a, m, past, todays, today, now)
    }

    /**
     * "Is my stop too tight?" ([com.optionslab.ira.StopNoise]): the open positions as [IraCoach.openLegs] reads them (with
     * each one's stop), set against the 1-minute candles kept for their indices. Reads only; his positions, so never on a
     * locked phone.
     */
    private suspend fun stopNoise(): String {
        if (phoneLocked()) return com.optionslab.ira.StopNoise.LOCKED
        val legs = IraCoach.openLegs()
        val today = com.optionslab.app.data.Market.today()
        val hs = histories
        val noiseRecords = com.optionslab.ira.StopNoise.markets(legs).associateWith { m -> com.optionslab.ira.StopNoise.record(hs[m]?.bars.orEmpty(), today) }
        val noiseSpots = noiseRecords.keys.associateWith { m -> _state.value.snaps[m]?.price }
        return com.optionslab.ira.StopNoise.answer(legs, noiseRecords, noiseSpots).joinToString(" ")
    }

    /** The past sessions read for [otmReach] (the index, its kept days and the day as key), kept until any of them changes. */
    @Volatile private var otmReachDays: Pair<String, List<com.optionslab.ira.OtmReach.Day>>? = null

    /**
     * "How often does an OTM option 100 points away end the day in the money?" ([com.optionslab.ira.OtmReach]): the index's
     * sessions with option prices on the phone (bundled and harvested), each streamed once and read down to a few numbers
     * a strike, then today's kept chain. Reads only.
     */
    private fun otmReach(a: com.optionslab.ira.OtmReach.Q, markets: List<IraMarket>): String {
        val m = com.optionslab.ira.OtmReach.market(markets) ?: return com.optionslab.ira.OtmReach.NOT_HERE
        val u = m.name
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        val today = com.optionslab.app.data.Market.today()
        val kept = Store.deviceBarDays(u).filter { it.isBefore(today) }
        val key = "$u|$today|${kept.size}|${kept.lastOrNull()}"
        val past = otmReachDays?.takeIf { it.first == key }?.second
            ?: com.optionslab.ira.OtmReach.days(Store.barSessions(u), today).also { otmReachDays = key to it }
        val todays = runCatching { Store.barSession(u, today) }.getOrNull()
        return com.optionslab.ira.OtmReach.answer(a, m, past, todays, today, now)
    }

    /**
     * "How often does the ATM option double from its 9:30 price before the end of the day?" ([com.optionslab.ira.AtmBuy]): the
     * index's sessions with option prices on the phone (bundled and harvested), each streamed once and read down to a few
     * numbers, then today's kept chain. Reads only.
     */
    private fun atmBuy(a: com.optionslab.ira.AtmBuy.Q, markets: List<IraMarket>): String {
        val m = com.optionslab.ira.AtmBuy.market(markets) ?: return com.optionslab.ira.AtmBuy.NOT_HERE
        val u = m.name
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        val today = com.optionslab.app.data.Market.today()
        val past = pastReads(u, today).atmBuyDays
        val todays = runCatching { Store.barSession(u, today) }.getOrNull()
        return com.optionslab.ira.AtmBuy.answer(a, m, past, todays, today, now)
    }

    /** The past expiries read for [expiryPin], kept until the phone's own captures or the day change. */
    @Volatile private var pinDays: Pair<String, List<com.optionslab.ira.ExpiryPin.Day>>? = null

    /**
     * "How often does Nifty close near max pain on expiry?" ([com.optionslab.ira.ExpiryPin]): the Nifty expiry-day chains on
     * the phone (bundled and captured), each streamed once and read down to a few numbers, then today's newest chain read
     * kept in memory. Reads only.
     */
    private fun expiryPin(a: com.optionslab.ira.ExpiryPin.Q, markets: List<IraMarket>): String {
        if (com.optionslab.ira.ExpiryPin.market(markets) == null) return com.optionslab.ira.ExpiryPin.NOT_HERE
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        val today = com.optionslab.app.data.Market.today()
        val device = Store.deviceExpiryDays()
        val key = "$today|${!now.toLocalTime().isBefore(java.time.LocalTime.of(15, 30))}|${device.size}|${device.lastOrNull()}"
        val days = pinDays?.takeIf { it.first == key }?.second
            ?: com.optionslab.ira.ExpiryPin.days(Store.expirySessions(true), today, now).also { pinDays = key to it }
        val latest = IraAccount.chainBook.latest("NIFTY")?.takeIf { it.at.toLocalDate() == today }
        return com.optionslab.ira.ExpiryPin.answer(a, days, latest, today)
    }

    /**
     * "How has max pain moved today?", "is the call wall shifting?" ([com.optionslab.ira.ChainDrift]): the chain read afresh
     * (so the newest read is kept), then the day's reads kept in memory compared. The chain's own numbers with their times.
     */
    private suspend fun chainDrift(a: com.optionslab.ira.ChainDrift.Ask, markets: List<IraMarket>): String {
        val u = com.optionslab.ira.ChainIntel.underlying(markets) ?: return com.optionslab.ira.ChainIntel.NOT_HERE
        val name = IraMarket.valueOf(u).label
        withTimeoutOrNull(25_000) { IraAccount.chain(u) } ?: return "The $name option chain did not load just now, Boss."
        val today = com.optionslab.app.data.Market.today()
        val reads = IraAccount.chainBook.day(u, today)
        val note = runCatching { IraTools.chainNote(u) }.getOrNull()
        return listOfNotNull(note, com.optionslab.ira.ChainDrift.answer(a, reads, today)).joinToString(" ")
    }

    /**
     * A what-if worked through ([com.optionslab.ira.Scenarios]): the index's sessions on the phone, the prices now, and on an
     * unlocked phone Boss's open positions, kept rules, daily loss limits and price alarms. Reads only.
     */
    /**
     * "Why did Nifty fall?" ([com.optionslab.ira.Causes]): the index's day and the candidates on the phone weighed - the
     * candles of the four indices and India VIX, the headlines, the news-and-moves record, NSE's latest FII/DII figures
     * (read at most hourly; none is said so) and today's scheduled events. Reads only.
     */
    private suspend fun causes(a: com.optionslab.ira.Causes.Ask, markets: List<IraMarket>): String {
        val mk = com.optionslab.ira.Causes.market(a, markets) ?: return com.optionslab.ira.Causes.NOT_HERE
        val bars = (com.optionslab.ira.Causes.INDICES + IraMarket.VIX).associateWith { histories[it]?.bars.orEmpty() }
        val today = com.optionslab.app.data.Market.today()
        val flows = withTimeoutOrNull(15_000) { runCatching { flows() }.getOrDefault(emptyList()) } ?: emptyList()
        val events = runCatching { IraEvents.upcoming(0) }.getOrDefault(emptyList()).filter { it.day == today && !it.name.endsWith("expiry") }.map { it.name }
        val log = runCatching { IraTools.newsMoves() }.getOrDefault(emptyList())
        return com.optionslab.ira.Causes.answer(a, mk, bars, _state.value.news, IST, LocalDateTime.now(IST), log, flows, events, expiryToday(mk))
    }

    private suspend fun scenarioSaid(s: com.optionslab.ira.Scenarios.Scenario): String {
        val mk = s.market
        val nowAt = LocalDateTime.now(IST)
        val today = com.optionslab.app.data.Market.today()
        val live = mk.trading(nowAt) && closedToday() == null
        val ex = expiryDays[mk].orEmpty() + (if (expiryToday(mk)) setOf(today) else emptySet())
        val days = com.optionslab.ira.MarketMemory.days(mk, histories[mk]?.bars.orEmpty(), ex, histories[IraMarket.VIX]?.bars.orEmpty(), today, live)
        // The session it is about: today's, or the next one (today's when it has not opened yet).
        fun trading(d: LocalDate) = runCatching { com.optionslab.app.data.Market.isTradingDay(d) }.getOrDefault(d.dayOfWeek.value <= 5)
        val beforeOpen = trading(today) && mk.open?.let { nowAt.toLocalTime().isBefore(it) } == true
        var on = today
        if (s.next && !beforeOpen) { on = today.plusDays(1); var guard = 0; while (guard++ < 14 && !trading(on)) on = on.plusDays(1) }
        val expiry = runCatching { com.optionslab.app.data.Market.isExpiryDay(mk.name, on) }.getOrDefault(false)
        val st = _state.value
        val locked = phoneLocked()
        val mine = if (locked) null else runCatching {
            val set = com.optionslab.app.data.AppSettings.load()
            val pnl = HashMap<String, Double>()
            if (!s.next) {
                // Words only (a scenario said aloud): a price read in the last 20 s is shared (Battery, round 8). Set against
                // the loss limits, so the paper day AFTER charges (dayPnl), the safer figure.
                runCatching { com.optionslab.app.data.Paper.snapshot(com.optionslab.app.data.Paper.SHARED_QUOTE_MS).dayPnl }.getOrNull()?.let { pnl["Paper"] = it }
                if (com.optionslab.app.data.Broker.loggedIn) runCatching {
                    com.optionslab.app.data.Broker.within(8_000) { com.optionslab.app.data.Broker.positionBook() }?.m2m
                }.getOrNull()?.let { pnl["Zerodha"] = it }
            }
            val alarms = runCatching { com.optionslab.app.data.Alarms.all() }.getOrDefault(emptyList())
                .filter { it.enabled && it.firedAtMillis == 0L }
                .map { com.optionslab.ira.Scenarios.Alarm(it.symbol.removePrefix(com.optionslab.app.data.PriceAlarm.CHART), it.above, it.level) }
            com.optionslab.ira.Scenarios.Mine(
                legs = IraCoach.openLegs(),
                notes = IraTools.memory().map { it.text },
                lossLimits = mapOf("Zerodha" to set.guardDailyLoss, "Paper" to set.guardPaperDailyLoss),
                dayPnl = pnl, alarms = alarms,
            )
        }.getOrNull()
        return com.optionslab.ira.Scenarios.answer(com.optionslab.ira.Scenarios.Input(
            s = s, days = days, today = today, spot = st.snaps[mk]?.price,
            vix = st.snaps[IraMarket.VIX]?.price, vixPrev = st.snaps[IraMarket.VIX]?.prevClose,
            expiry = expiry, mine = mine, locked = locked,
        ))
    }

    /**
     * Is the phone on a network? False only when it plainly is not (no network, or one without internet), so a phone
     * whose state cannot be read is treated as online.
     */
    fun online(): Boolean {
        if (testLive != null || testHistories != null) return true
        val c = app ?: return true
        return runCatching {
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val n = cm.activeNetwork ?: return@runCatching false
            cm.getNetworkCapabilities(n)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) != false
        }.getOrDefault(true)
    }

    /**
     * How old the data a market answer about [topics] rests on is ([com.optionslab.ira.DataAge]): the first market's
     * prices or candles and the news; with [all], every source whatever the topics (for "is your data fresh?"). Nothing on
     * a holiday (old prices are expected) or in IraGoldAlgo (it reads no NSE feeds).
     */
    private fun ageChecks(markets: List<IraMarket>, topics: Set<Topic>, all: Boolean = false): List<com.optionslab.ira.DataAge.Check> {
        if (testHistories != null || GOLD_ONLY_TALK || (!all && closedToday() != null)) return emptyList()
        // Gold trades round the clock on its own feed: its answers are not checked against the NSE session.
        if (!all && markets.firstOrNull() == IraMarket.GOLD) return emptyList()
        val m = markets.firstOrNull { it != IraMarket.GOLD } ?: IraMarket.NIFTY
        val st = _state.value
        val now = com.optionslab.app.data.Market.now().toLocalDateTime()
        val live = m.trading(now) && closedToday() == null
        val src = if (all) com.optionslab.ira.DataAge.Source.entries.toSet() else com.optionslab.ira.DataAge.sources(topics)
        // A fetch just tried: data still old after it means the feed itself is behind.
        val tried = st.liveAt?.isAfter(Instant.now().minusSeconds(120)) == true || m in st.liveMissing
        val out = ArrayList<com.optionslab.ira.DataAge.Check>()
        st.snaps[m]?.let { s ->
            if (com.optionslab.ira.DataAge.Source.PRICES in src)
                com.optionslab.ira.DataAge.prices(m.label, st.liveAt?.atZone(IST)?.toLocalDateTime(), s.at, now, live, tried)?.let { out += it }
            if (com.optionslab.ira.DataAge.Source.CANDLES in src) com.optionslab.ira.DataAge.candles(m.label, s.at, now, live, tried)?.let { out += it }
        }
        if (com.optionslab.ira.DataAge.Source.NEWS in src)
            com.optionslab.ira.DataAge.news(st.newsAt?.atZone(IST)?.toLocalDateTime(), st.news.mapNotNull { it.at }.maxOrNull()?.atZone(IST)?.toLocalDateTime(), now, live)?.let { out += it }
        if (all) LIVE[m]?.let { u -> IraTools.chainCheck(u) }?.let { out += it }
        return out
    }

    /**
     * A market answer as said given how old its data is: old, the age first and the price figures marked; stale prices
     * (with [withhold]), said so instead of quoted - and fresh ones fetched when no fetch was just tried. Kept in the day's
     * freshness record (ages and counts only). Only ever adds caution; never acts.
     */
    private fun aged(text: String, markets: List<IraMarket>, topics: Set<Topic>, withhold: Boolean = true): com.optionslab.ira.DataAge.Dressed {
        val checks = runCatching { ageChecks(markets, topics) }.getOrDefault(emptyList())
        if (checks.isEmpty()) return com.optionslab.ira.DataAge.Dressed(text, null, false)
        val d = runCatching { com.optionslab.ira.DataAge.dress(text, checks, com.optionslab.app.data.Market.now().toLocalDateTime(), withhold) }.getOrNull()
            ?: return com.optionslab.ira.DataAge.Dressed(text, null, false)
        if (checks.any { it.source == com.optionslab.ira.DataAge.Source.PRICES && it.level == com.optionslab.ira.DataAge.Level.STALE && !it.tried } && online())
            scope.launch { runCatching { refresh() } }
        scope.launch { runCatching { IraTools.freshAnswered(checks, d.note != null, d.withheld) } }
        return d
    }

    /** "Is your data fresh?" / "how old are your prices?": each source's age now and today's record. */
    private fun freshAsked(markets: List<IraMarket>): String {
        val checks = runCatching { ageChecks(markets, emptySet(), all = true) }.getOrDefault(emptyList())
        val m = markets.firstOrNull { it != IraMarket.GOLD } ?: IraMarket.NIFTY
        return IraTools.freshSay(checks, m.trading(LocalDateTime.now(IST)) && closedToday() == null)
    }

    /** Said before a market answer when offline: where the figures come from. */
    private fun offlineNote(): String? {
        if (online()) return null
        val last = _state.value.lastDay?.let { " (up to $it)" } ?: ""
        return "I'm offline, Boss, so this is from the prices saved on the phone$last."
    }

    /**
     * "The market is closed today (Gandhi Jayanti)." on a holiday or a weekend, else null: said first in market answers
     * and the status, so old prices are never mistaken for today's.
     */
    fun closedToday(today: LocalDate = com.optionslab.app.data.Market.today()): String? {
        if (runCatching { com.optionslab.app.data.Market.isTradingDay(today) }.getOrDefault(true)) return null
        val name = runCatching { com.optionslab.app.data.Holidays.book().upcoming(today).firstOrNull { it.first == today }?.second }.getOrNull()
        return when {
            name != null -> "The market is closed today ($name, an NSE holiday)."
            today.dayOfWeek == java.time.DayOfWeek.SATURDAY || today.dayOfWeek == java.time.DayOfWeek.SUNDAY -> "The market is closed today (weekend)."
            else -> "The market is closed today."
        } + " Prices shown are from the last session."
    }

    /** A message from Jarvis itself (the morning check). */
    /** Messages Jarvis posted on his own ([note]): "what did I miss" lists only these. Bounded. */
    private val unaskedIds: MutableSet<Long> = java.util.Collections.synchronizedSet(LinkedHashSet())

    /** [whole]: shown and said whole, never as a short line (the guard's report; [Msg.whole]). */
    fun note(text: String, whole: Boolean = false) {
        val m = Msg(true, text, whole = whole)
        synchronized(unaskedIds) { unaskedIds += m.id; while (unaskedIds.size > 200) unaskedIds.remove(unaskedIds.first()) }
        _state.update { it.copy(messages = (it.messages + m).takeLast(MAX_MESSAGES)) }
    }

    /**
     * A note in the chat that is also said aloud, as its one line, when Boss's "Jarvis speaks" choice takes a note of
     * [weight] ([JarvisVoice.offerNote]; muted, quiet hours and a locked phone as always). Words only.
     */
    fun noteAloud(text: String, weight: com.optionslab.ira.SpeakChoice.Weight) {
        note(text)
        runCatching { JarvisVoice.offerNote(text, weight) }
    }

    /** [whole]: an action's, confirm's or command's result (or the guard's report) - never shortened ([Msg.whole]). */
    private fun reply(text: String, whole: Boolean = false) {
        _state.update { it.copy(messages = (it.messages + Msg(true, text, whole = whole)).takeLast(MAX_MESSAGES)) }
    }

    /** Wipes the conversation (the owner's button). */
    fun forgetConversation() { if (!loaded.isCompleted) wipedWhileLoading = true; dropPending(); synchronized(askedAt) { askedAt.clear() }; _state.update { it.copy(messages = emptyList()) } }

    /** Wipes what Ira learned too; it relearns from the data on the next refresh. */
    suspend fun forgetAll() { awaitLoaded(); forgetAllLoaded() }

    private suspend fun forgetAllLoaded() = lock.withLock {
        dropPending()
        clearRequests()
        // The short lines read from past answers go too (they were read from the conversation just forgotten).
        com.optionslab.ira.ShortAnswer.clearCache()
        IraTools.forgetHabits()
        IraTools.forgetRoutine()
        IraTools.forgetMemory()
        synchronized(askedAt) { askedAt.clear() }
        checked = null
        refreshBeganAt = 0L
        book = PatternBook()
        lastNews = null
        lastBackground = null
        histories = emptyMap()
        synchronized(tested) { tested.clear() }
        bookFile?.delete()
        stateFile?.delete()
        // Boss chose to forget everything: the unreadable files are gone, so saving starts again.
        stateReadFailed = false; bookReadFailed = false
        _state.update { State() }
    }

    const val MAX_MESSAGES = 60
    private val msgSeq = java.util.concurrent.atomic.AtomicLong()
    private val MARKET_TOPICS = setOf(Topic.OVERVIEW, Topic.WHY, Topic.TREND, Topic.LEVELS, Topic.PATTERNS, Topic.VOLATILITY, Topic.ADVICE, Topic.NEWS)

    @Synchronized private fun save() {
        val f = bookFile ?: return
        if (bookReadFailed) return
        runCatching { Vault.writeFile(f, book.save().toByteArray(Charsets.UTF_8)) }
    }

    /** Today's (and the last few days') live candles for every market, fetched together; the markets that failed. */
    private suspend fun live(): Pair<Map<IraMarket, List<Candle>>, List<IraMarket>> = coroutineScope {
        val got = LIVE.keys.map { m ->
            async { m to (withTimeoutOrNull(20_000) { runCatching { liveOf(m) }.getOrNull() } ?: emptyList()) }
        }.awaitAll()
        got.filter { it.second.isNotEmpty() }.toMap() to got.filter { it.second.isEmpty() }.map { it.first }
    }

    private suspend fun liveOf(m: IraMarket): List<Candle> {
        testLive?.let { return it(m) }
        return if (m == IraMarket.GOLD)
            GoldChart.bars("1m", null, null).map { b -> candle(LocalDateTime.ofEpochSecond(b.epochSecond, 0, ZoneOffset.UTC), b) }
        else ChartFeed.bars(LIVE.getValue(m), "1m", null, null, quick = true)
            .also { keepDay(m, it) }
            .map { b -> candle(Instant.ofEpochSecond(b.epochSecond).atZone(IST).toLocalDateTime(), b) }
    }

    private val keptAt = HashMap<IraMarket, Long>()
    /** Live index minutes are written to the app's record at most this often (Jarvis). */
    const val KEEP_EVERY_MS = 5 * 60_000L

    /**
     * Jarvis keeps the live day as it trades: each index's 1-minute candles (with volume and OI where the feed has
     * them) are merged into the app's own record, the same files the evening harvest fills - so a day is kept even if the
     * harvest does not run. The option chains (1-minute OHLC, volume and OI for every contract) are kept by the harvest.
     */
    private fun keepDay(m: IraMarket, bars: List<com.optionslab.engine.Upstox.Bar>) {
        if (!com.optionslab.app.BuildConfig.JARVIS || bars.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(keptAt) { if (now - (keptAt[m] ?: 0L) < KEEP_EVERY_MS) return; keptAt[m] = now }
        val name = LIVE.getValue(m)
        runCatching {
            for ((day, b) in bars.groupBy { it.istDate }) Store.upsertDay(name, day, listOf(com.optionslab.engine.Upstox.toSeries(null, b, day)))
        }
    }

    private fun candle(t: LocalDateTime, b: com.optionslab.engine.Upstox.Bar) = Candle(t, b.open, b.high, b.low, b.close)

    /** Stored days joined with live ones; a day the live feed has replaces the stored copy of it. */
    internal fun merge(stored: Map<IraMarket, History>, live: Map<IraMarket, List<Candle>>): Map<IraMarket, History> =
        (stored.keys + live.keys).associateWith { m ->
            val l = live[m].orEmpty()
            val liveDays = l.map { it.t.toLocalDate() }.toSet()
            History(m, stored[m]?.bars.orEmpty().filter { it.t.toLocalDate() !in liveDays } + l)
        }.filterValues { it.bars.isNotEmpty() }

    private var lastNews: Instant? = null

    /** The headlines of every feed when [NEWS_EVERY_MINUTES] have passed (else null), and the feeds that failed. */
    private suspend fun newsIfDue(): Pair<List<Headline>, List<String>>? = coroutineScope<Pair<List<Headline>, List<String>>?> {
        val now = Instant.now()
        if (lastNews?.let { now.isBefore(it.plusSeconds(NEWS_EVERY_MINUTES * 60)) } == true) return@coroutineScope null
        lastNews = now
        val got = NEWS_FEEDS.map { (name, url) ->
            async {
                name to withTimeoutOrNull(20_000) { runCatching { News.parse(testFeed?.invoke(url) ?: Net.getText(url), name) }.getOrNull() }
            }
        }.awaitAll()
        val cutoff = now.minusSeconds(NEWS_DAYS * 86_400)
        val all = News.merge(got.mapNotNull { it.second }).filter { h -> h.at?.isAfter(cutoff) != false }.take(200)
        all to got.filter { it.second == null }.map { it.first }
    }

    private fun load(): Map<IraMarket, History> {
        val expiries = HashMap<IraMarket, Set<LocalDate>>()
        val out = SOURCES.mapNotNull { (m, u) ->
            val bars = ArrayList<Candle>()
            val ex = HashSet<LocalDate>()
            val all = Store.barDays(u)
            val from = all.takeLast(KEEP_DAYS).firstOrNull()
            // The last KEEP_DAYS days: enough for the snapshots and for learning each new day (the book skips what it learned).
            Store.barSessions(u).forEach { s ->
                if (from != null && s.day.isBefore(from)) return@forEach
                s.index?.let { ix -> bars += candles(s.day, ix) }
                // An expiry day: the day's saved option candles include a contract expiring that day (for Jarvis's market memory).
                if (s.series.any { it.right != Right.IX && it.expiry == s.day }) ex += s.day
            }
            if (ex.isNotEmpty()) expiries[m] = ex
            if (bars.isEmpty()) null else m to History(m, bars)
        }.toMap()
        expiryDays = expiries
        return out
    }

    /** A harvested index series as Ira's candles (minutes are IST minutes of the day). */
    internal fun candles(day: LocalDate, ix: Series): List<Candle> {
        if (ix.right != Right.IX) return emptyList()
        return (0 until ix.size).map { i ->
            val c = ix.close[i]
            Candle(day.atStartOfDay().plusMinutes(ix.minutes[i].toLong()), ix.open?.get(i) ?: c, ix.high?.get(i) ?: c, ix.low?.get(i) ?: c, c)
        }
    }
}
