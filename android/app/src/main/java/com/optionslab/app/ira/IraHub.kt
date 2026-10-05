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
                   /** Stable for the message's life (kept through rewrites), so the screen keeps each message's own state. */
                   val id: Long = msgSeq.incrementAndGet())

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
        bookFile = f
        book = runCatching { Vault.readFileSteady(f)?.let { PatternBook.load(String(it, Charsets.UTF_8)) } }.getOrNull() ?: PatternBook()
        val sf = File(context.applicationContext.noBackupFilesDir, "ira-state.vault")
        // Started again in the same process: what is in memory is written first (the keeper waits 300 ms before it
        // saves), so the newest message or proposal is never lost to the reload below.
        if (keeper != null && stateFile == sf) saveState()
        stateFile = sf
        val saved = runCatching { Vault.readFileSteady(sf)?.let { IraSaved.read(String(it, Charsets.UTF_8)) } }.getOrNull()
        synchronized(tested) { tested.clear(); saved?.tested?.let { tested += it } }
        // The process started again: what was kept comes back; a strategy still waiting is offered again in the conversation.
        // The conversation comes back as it was (the owner's wish, 2026-10-02); a strategy still waiting is offered again.
        // An option-chain read cut short by the process ending never finishes: its placeholder is told as not read.
        val talk = saved?.messages.orEmpty().map { if (it.fromIra && it.text.startsWith(CHAIN_NOTE)) it.copy(text = "I couldn't read the option chain just now, Boss.") else it }
        val waiting = saved?.proposals.orEmpty().filter { it.status == Proposal.NEW && talk.none { m -> m.proposal == it.id } }
            .map { Msg(true, "Still waiting for your decision. " + it.result.summary(), proposal = it.id) }
        _state.value = State(learned = book.size, best = book.best(), proposals = saved?.proposals.orEmpty(),
            journal = saved?.journal.orEmpty(), nightlyAt = saved?.nightlyAt, messages = (talk + waiting).takeLast(MAX_MESSAGES))
        if (keeper == null) keeper = scope.launch {
            var last: List<Msg>? = null
            _state.collect { st -> if (st.messages !== last) { last = st.messages; kotlinx.coroutines.delay(300); saveState() } }
        }
    }

    /** Saves the conversation as it changes. */
    private var keeper: kotlinx.coroutines.Job? = null

    /** Writes the proposals, the journal, the tried patterns and the conversation (one writer at a time). */
    @Synchronized private fun saveState() {
        val f = stateFile ?: return
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

    /** Re-reads the candles, learns from the new ones and rebuilds the snapshots. Never throws. */
    suspend fun refresh() = withContext(Dispatchers.Default) {
        lock.withLock {
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
                val days = hs.values.maxOfOrNull { it.days.size } ?: 0
                _state.update { it.copy(loading = false, snaps = snaps, lastDay = snaps.values.maxOfOrNull { s -> s.at.toLocalDate() },
                    days = days, learned = book.size, problem = if (snaps.isEmpty()) "No market data on this phone yet" else null,
                    liveAt = if (live.isNotEmpty()) Instant.now() else it.liveAt, liveMissing = missing,
                    news = news?.first ?: it.news, newsAt = if (news != null) Instant.now() else it.newsAt,
                    newsMissing = news?.second ?: it.newsMissing, best = book.best()) }
                // How the index moved after each news theme's headlines, timed on the phone's own candles - timing facts
                // only, never a cause; it only ever adds words ([com.optionslab.ira.NewsMoves]). No news read in IraGoldAlgo.
                if (!GOLD_ONLY_TALK) runCatching { IraTools.newsMovesUpdate(_state.value.news, hs.mapValues { it.value.bars }) }
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
        runCatching { JarvisPopup.show(c, "Boss, a new strategy: ${r.kind.label} on ${r.market.label}", "${r.trades} trades, ${Math.round(r.winRate)}% won over ${r.days} days. Open Jarvis to approve or dismiss.") }
        val money = r.rupees?.let { " Options: ${"%+,.0f".format(java.util.Locale.ENGLISH, it).replace("+", "+Rs ").replace("-", "-Rs ")} a lot." } ?: ""
        runCatching {
            com.optionslab.app.work.Notifier.post(c, NOTIFY_BASE + p.id.toInt(), com.optionslab.app.work.Notifier.IRA,
                "Jarvis found a strategy: ${r.kind.label} on ${r.market.label} ${chartWord(r.minutes)}",
                "${r.trades} trades, ${Math.round(r.winRate)}% won, ${"%+,.1f".format(java.util.Locale.ENGLISH, r.netPoints)} points over ${r.days} days; " +
                    "both halves positive.$money Open Jarvis to approve or dismiss.", tab = "almanac")
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
        val item = com.optionslab.app.data.PineScripts.put(com.optionslab.app.data.PineScripts.Item(0, r.name, r.script))
        com.optionslab.app.data.PineScripts.setAuto(item.id, com.optionslab.app.data.PineScripts.Auto(
            on = false, symbol = r.market.name, interval = if (r.minutes == 60) "1h" else "${r.minutes}m", lots = 1, shortWith = "put"))
        val armed = com.optionslab.app.data.PineAuto.arm(item.id, on = true, pinConfirmed = false)
        val text = if (armed == "ok") "Added ${r.name} as an arm and switched it on: on paper it trades 1 lot; in Live it waits for your PIN in Research → Pine. " +
            "You can switch it off there any time." else "Saved ${r.name} in Research → Pine, but could not switch it on ($armed)."
        _state.update { s -> s.copy(proposals = s.proposals.map { if (it.id == id) it.copy(status = Proposal.APPROVED, pineId = item.id) else it },
            messages = (s.messages + Msg(true, text)).takeLast(MAX_MESSAGES)) }
        saveState()
        IraActivity.add(text)
        return text
    }

    fun dismiss(id: Long) {
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
        runCatching { keepChains() }
        runCatching { IraCoach.oiWatch() }
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
        val got = newsIfDue() ?: return
        _state.update { it.copy(news = got.first, newsAt = Instant.now(), newsMissing = got.second) }
        val fresh = synchronized(newsSeen) { got.first.filter { newsSeen.add(it.link.ifBlank { it.title }) }.also { com.optionslab.ira.Upkeep.trimOldest(newsSeen, NEWS_SEEN_MAX) } }
        newsPrimed = true
        val cut = now.minusSeconds(18 * 3600)
        fresh.filter { h -> h.at?.isAfter(cut) == true && com.optionslab.ira.NewsAnalyst.matters(h) }.take(6).forEach { h ->
            IraStudy.overnight(h.at!!, com.optionslab.ira.NewsAnalyst.take(h, emptyMap(), emptySet()).text)
        }
    }

    @Volatile private var widgetKey: String? = null

    /** Headlines already judged (link or title), the newest [NEWS_SEEN_MAX] only: the app can run for days. */
    private val newsSeen = LinkedHashSet<String>()
    private const val NEWS_SEEN_MAX = 3_000
    @Volatile private var newsPrimed = false

    /** New headlines that matter, told once (a pop-up and a line in the conversation); the first read only primes. */
    private suspend fun judgeNews(heads: List<Headline>) {
        val c = app ?: return
        val fresh = synchronized(newsSeen) { heads.filter { newsSeen.add(it.link.ifBlank { it.title }) }.also { com.optionslab.ira.Upkeep.trimOldest(newsSeen, NEWS_SEEN_MAX) } }
        if (!newsPrimed) { newsPrimed = true; return }
        val recent = fresh.filter { h -> h.at?.isAfter(Instant.now().minusSeconds(30 * 60)) != false }.filter { com.optionslab.ira.NewsAnalyst.matters(it) }.take(3)
        if (recent.isEmpty()) return
        val arms = HashMap<String, IraMarket>()
        runCatching { com.optionslab.app.data.OrbArms.view().arms }.getOrDefault(emptyList()).filter { it.armed }.forEach { arms[it.arm.label] = IraMarket.BANKNIFTY }
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
     * A trade Jarvis suggests (strong news, or the pattern expert): 1 lot of the index's ATM option, nearest expiry, the
     * arm's 15% stop, a +40 target and the profit lock - placed only on the owner's yes (aloud, Approve on the pop-up or
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
        val what = "buy 1 lot of the ${m.label} $side at the money, nearest expiry, with a 15% stop, a +${IraNewsTrades.TARGET_POINTS.toInt()} target and the profit lock"
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
                runCatching { IraNewsTrades.suggested(sid, idea, snap.price, source, regimeNow, iv?.first); IraNewsTrades.answered(sid, com.optionslab.ira.JarvisTrades.SELF) }
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
        synchronized(actions) {
            actions[id] = what to suspend { IraNewsTrades.place(idea, _state.value.snaps[m]?.price ?: snap?.price ?: error("no ${m.label} price"), source,
                solo = solo, liveApproved = synchronized(actions) { id in liveApproved }, stars = conf.stars) }
            newsAsks += id
            if (solo) soloAsks += id
        }
        IraNewsTrades.suggested(id, idea, snap?.price ?: 0.0, source, regimeNow, iv?.first)
        IraActivity.add("Suggested: $what (${source.substringBefore(':')}).")
        val where = if (goesLive) " on ZERODHA with real money (approve with your fingerprint)"
            else if (com.optionslab.app.data.AppSettings.load().live) " (on paper: ${if (solo) "Solo's" else "my"} trades stay there until proven)" else ""
        val hourLine = listOfNotNull(badHour, badKind, calibLine?.takeIf { sitOut && !solo }).takeIf { it.isNotEmpty() }?.let { " A caution: ${it.joinToString("; ")}." } ?: ""
        val full = "$text$ivLine ${conf.text()}$risk$hourLine Shall I $what$where? Approve or reject."
        _state.update { it.copy(pending = it.pending + id, messages = (it.messages + Msg(true, full, action = id)).takeLast(MAX_MESSAGES)) }
        JarvisApproval.show(c, id, title, full)
        JarvisVoice.askYesNo(id, "$said Confidence ${conf.stars} out of 5. Shall I buy 1 lot of the ${m.label} $side? Yes or no?")
        scope.launch {
            kotlinx.coroutines.delay(NEWS_ANSWER_MS)
            if (synchronized(actions) { actions.remove(id) } != null) {
                settled(id)
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
        runCatching { com.optionslab.app.data.Paper.snapshot().positions.positions.filter { it.quantity != 0 }
            .forEach { open += com.optionslab.ira.Rescue.Open(it.symbol, false, it.quantity, it.averagePrice, it.ltp) to it.product } }
        if (com.optionslab.app.data.Broker.loggedIn) runCatching { com.optionslab.app.data.Broker.positionBook().net.filter { it.open && it.exchange == "NFO" }
            .forEach { open += com.optionslab.ira.Rescue.Open(it.symbol, true, it.qty, it.avg, it.last) to it.product } }
        // Nothing open (most passes): nothing can be bare, so the stop orders are not read (a Zerodha read each pass).
        if (open.isEmpty()) { synchronized(unguardedSince) { unguardedSince.clear() }; return }
        runCatching { com.optionslab.app.data.Paper.snapshot().orders.orders.filter { o -> o.priceType.uppercase() in setOf("SL", "SL-M") &&
            o.status.lowercase() !in setOf("complete", "cancelled", "rejected") }.forEach { guarded += "P:" + it.symbol } }
        if (com.optionslab.app.data.Broker.loggedIn) runCatching { com.optionslab.app.data.Broker.orders().filter { o -> o.working && o.type in setOf("SL", "SL-M") }
            .forEach { guarded += "L:" + it.symbol } }
        // The bots (ORB arms, Pine scripts, strategy runs) manage their own exits: only positions they do not hold.
        val bots = HashSet<String>()
        runCatching { com.optionslab.app.data.OrbArms.view().arms.mapNotNull { it.open?.symbol }.forEach { bots += it } }
        runCatching { com.optionslab.app.data.PineAuto.held.value.values.forEach { bots += it.symbol } }
        runCatching { com.optionslab.app.data.Strategies.all().mapNotNull { it.run }.forEach { r -> r.openLegs().forEach { bots += it.symbol } } }
        // Jarvis's own trades keep their own exits too (none was ever bare long enough to matter, but never doubled).
        runCatching { IraNewsTrades.all().filter { !it.closed }.forEach { bots += it.symbol } }
        val day = com.optionslab.app.data.Market.today().toString()
        val bare = open.filter { (p, _) -> (if (p.live) "L:" else "P:") + p.symbol !in guarded && p.symbol !in bots }
        synchronized(unguardedSince) { unguardedSince.keys.retainAll(bare.map { (p, _) -> (if (p.live) "L:" else "P:") + p.symbol }.toSet()) }
        for ((p, product) in bare) {
            val k = (if (p.live) "L:" else "P:") + p.symbol
            val since = synchronized(unguardedSince) { unguardedSince.getOrPut(k) { now } }
            if (since.isAfter(now.minusSeconds(com.optionslab.ira.Rescue.GRACE_MINUTES * 60))) continue
            if (!synchronized(rescueAsked) { rescueAsked.add("$day|$k") }) continue
            val stop = com.optionslab.ira.Rescue.stopFor(p)
            val text = com.optionslab.ira.Rescue.say(p, stop)
            // (Only told when offering is on: the guard alone never sets a stop on these, so it says nothing.)
            if (stop == null) { if (Automations.on(Automations.Auto.RESCUE)) { JarvisPopup.show(c, "Boss, ${p.symbol} has no stop", text); reply(text) }; continue }
            // Set alone only when nothing else could close it too: no working order on it at all and, at Zerodha, no GTT
            // on it (a GTT or a resting exit filling alongside the stop would leave a short). Otherwise only offered.
            val clear = guard && runCatching {
                if (p.live) com.optionslab.app.data.Broker.orders().none { it.working && it.symbol == p.symbol } &&
                    com.optionslab.app.data.Broker.gtts().none { it.symbol == p.symbol && it.status.lowercase() == "active" }
                else com.optionslab.app.data.Paper.snapshot().orders.orders.none { it.symbol == p.symbol &&
                    it.status.lowercase() !in setOf("complete", "cancelled", "rejected") }
            }.getOrDefault(false)
            if (com.optionslab.ira.Rescue.setAlone(clear, p, stop)) {
                val result = runCatching {
                    if (p.live) com.optionslab.app.data.Protections.protectLive(p.symbol, "NFO", product, p.qty, p.ltp ?: p.avg, stop, null, null)
                    else com.optionslab.app.data.Protections.protectPaper(p.symbol, product, p.qty, p.ltp ?: p.avg, stop, null, null)
                }.getOrElse { "Not protected: ${it.message}" }
                val said = com.optionslab.ira.Rescue.saySet(p, stop, result)
                JarvisPopup.show(c, "Boss, I guarded ${p.symbol}", said)
                reply(said); IraActivity.add(said)
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
                when {
                    qty <= 0 -> "${p.symbol} is no longer held, so no stop was set."
                    has -> "${p.symbol} has a stop now, so I left it."
                    ltp == null || ltp <= stop -> "${p.symbol} is already at or under " + "%.2f".format(java.util.Locale.ENGLISH, stop) + ": close it or set a stop from the position."
                    p.live -> com.optionslab.app.data.Protections.protectLive(p.symbol, "NFO", product, qty, ltp, stop, null, null)
                    else -> com.optionslab.app.data.Protections.protectPaper(p.symbol, product, qty, ltp, stop, null, null)
                }
            }, "$text Tap Confirm to $what.")
            JarvisPopup.show(c, "Boss, ${p.symbol} has no stop", text)
            JarvisVoice.askYesNo(id, "Boss, $text Yes or no?")
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
                JarvisPopup.show(c, title, r); reply(com.optionslab.ira.Address.boss("Done by myself, as you asked: $r"))
            }
            return
        }
        val id = pend(what, act, "$text Tap Confirm to go ahead.")
        JarvisPopup.show(c, title, text)
        JarvisVoice.askYesNo(id, Wake.spoken(text, 2) + " Yes or no?")
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
        }, "$text Tap Confirm to close $symbol.")
        JarvisPopup.show(c, "Boss, $symbol is going nowhere", text)
        JarvisVoice.askYesNo(id, "Boss, $text Yes or no?")
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
        runCatching { com.optionslab.app.data.Paper.snapshot().positions.positions.filter { it.quantity != 0 }.forEach { p ->
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
        JarvisVoice.announce(com.optionslab.ira.Address.boss(plain))
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
            reply(text); JarvisVoice.announce(text); IraActivity.add("Warned: live prices stopped.")
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
    private suspend fun keepChains() {
        val today = LocalDate.now(IST)
        for (u in listOf("NIFTY", "BANKNIFTY", "FINNIFTY")) runCatching {
            val lc = withTimeoutOrNull(40_000) { com.optionslab.app.data.Market.liveChain(u, near = 12) } ?: return@runCatching
            if (lc.pricedAt != null) return@runCatching          // a quote snapshot, not minute candles: nothing to keep
            Store.upsertDay(u, today, lc.series.filter { it.expiry != null })
        }
    }

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
        listOf(IraMarket.NIFTY, IraMarket.BANKNIFTY).mapNotNull { m -> com.optionslab.ira.Outlook.brief(m, histories[m]?.bars.orEmpty(), vix) }
    }.getOrDefault(emptyList())

    /** IraGoldAlgo: Jarvis only talks there (no orders, commands, NSE feeds, strategies or trade ideas). */
    private val GOLD_ONLY_TALK get() = com.optionslab.app.BuildConfig.GOLD

    /** IraGoldAlgo's answer to an order or a command: Jarvis there only talks. */
    const val GOLD_TALK_ONLY = "In IraGoldAlgo I only talk, Boss: orders and commands are in IraAlgo. The gold arms trade on paper by their own rules."

    fun ask(text: String) = ask(text, understood = false)

    /** A spoken request on a voice that was not recognised: any command in it waits for a yes / Confirm. */
    fun askConfirmed(text: String) = ask(text, understood = true)

    /** [understood]: [text] is the model's reading of the owner's words (its actions always wait for Confirm). */
    /** [cleaned]: the words already read past their fillers or as a follow-up (a question): not read so again. */
    private fun ask(text: String, understood: Boolean, cleaned: Boolean = false) {
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
                com.optionslab.ira.Routine.asked(q) || com.optionslab.ira.Routine.forgetAsked(q) || com.optionslab.ira.PatternCalls.asked(q) ||
                com.optionslab.ira.Learnings.asked(q) != null || com.optionslab.ira.Learnings.undoAsked(q) ||
                com.optionslab.ira.NewsMoves.asked(q) != null || com.optionslab.ira.PreMarket.asked(q) ||
                com.optionslab.ira.ChainDrift.asked(q) != null ||
                com.optionslab.ira.Headroom.asked(q) != null ||
                com.optionslab.ira.SaidAbout.asked(q) != null ||
                com.optionslab.ira.NeedsTrue.asked(q) ||
                com.optionslab.ira.Clarity.asked(q) != null || com.optionslab.ira.DayClock.asked(q) != null ||
                com.optionslab.ira.GapRecord.asked(q) != null }.getOrDefault(false)) {
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
        // "Which alerts do you hold back?" / "say everything again": the unasked alerts he says aloud less often (kinds only,
        // nothing about the account; it only ever changes how often his own voice speaks, never anything that acts).
        val alertAsk = if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null) runCatching { com.optionslab.ira.AlertSense.asked(q) }.getOrNull() else null
        if (alertAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            scope.launch { reply(if (alertAsk == com.optionslab.ira.AlertSense.Request.ALL) IraTools.alertsAll()
                else IraTools.alertsHeld() + (if (IraAirtime.heldToday() > 0) " " + IraAirtime.answer() else "")) }
            return
        }
        // "Why so quiet?", "did you hold back any alerts?": today's market alerts - said aloud, or kept to the chat and why.
        if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null && runCatching { com.optionslab.ira.Airtime.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(IraAirtime.answer())
            return
        }
        // "How well are you hearing me?" / "are you having trouble hearing?": his ears' own counts today against the days
        // before (numbers only, never words) and what would help - a suggestion only: nothing is switched by voice, the
        // Google speech choice least of all ([com.optionslab.ira.Hearing]).
        if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.Hearing.asked(q) }.getOrDefault(false)) {
            val said = runCatching { com.optionslab.ira.Hearing.spoken(JarvisVoice.hearingDays, JarvisVoice.hearingDay(), JarvisVoice.googleSpeech) }
                .getOrElse { "I couldn't read my hearing counts just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "Which patterns work on Nifty?" / "how good are your pattern calls?": how the patterns he told of played out on
        // this phone ([com.optionslab.ira.PatternCalls]; market data only, facts, never advice).
        if (!bundled && parsed.order == null && parsed.command == null && runCatching { com.optionslab.ira.PatternCalls.asked(q) }.getOrDefault(false)) {
            val said = runCatching { com.optionslab.ira.PatternCalls.say(IraTools.patternCalls(), parsed.markets, com.optionslab.app.data.Market.today()) }
                .getOrDefault("I couldn't read my pattern record just now, Boss.")
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "Which answers do you keep short?" / "say your answers in full again": the answer kinds said shorter aloud, Boss having
        // asked "what?" after them ([com.optionslab.ira.Clarity]; kinds only). Only Jarvis's voice changes - nothing acts.
        val clarityAsk = if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.Clarity.asked(q) }.getOrNull() else null
        if (clarityAsk != null) {
            val said = if (clarityAsk == com.optionslab.ira.Clarity.Request.RESET) IraTools.clarityReset() else IraTools.clarityHeld()
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
            return
        }
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
            return
        }
        // "Export my trades for tax": the financial year's trades in a CSV for Boss to share ([com.optionslab.ira.TaxRecords]).
        // His account, so only on an unlocked phone, and only on his yes (checked unlocked again then); never in IraGoldAlgo
        // (Jarvis only talks there). Facts only, not tax advice; nothing placed, changed or closed; no IDs or keys in the file.
        if (!bundled && parsed.order == null && parsed.command == null && runCatching { com.optionslab.ira.TaxRecords.exportAsked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (GOLD_ONLY_TALK) { reply(GOLD_TALK_ONLY); return }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return }
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
            return
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
                    val now = LocalDateTime.now(IST).withSecond(0).withNano(0)
                    com.optionslab.ira.Learnings.say(com.optionslab.ira.Learnings.items(IraTools.learnings(plan), now), learnAsk, now.toLocalDate(), locked)
                }.getOrElse { "I couldn't read what I've learned just now, Boss." })
            }
            return
        }
        // "Undo everything you learned this week": learned behaviour only (wordings and routines kept this week, the alert
        // count, his own goals for the week), put to Boss first with Confirm - only as said by him, on an unlocked phone,
        // never in IraGoldAlgo. Settings, the PIN, Live, AI trading, guards and the Google speech choice are never touched.
        if (com.optionslab.app.BuildConfig.JARVIS && !understood && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.Learnings.undoAsked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (GOLD_ONLY_TALK) { reply(GOLD_TALK_ONLY); return }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return }
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
            return
        }
        // "Am I ready to trade?", "pre-market checklist" ([com.optionslab.ira.PreMarket]): the app's readiness and Boss's setup,
        // each pass or fail with the fix said as a step he takes himself; his margin and overnight positions only on an
        // unlocked phone. Reads only: nothing is placed, changed, closed or armed. (Not in IraGoldAlgo: no broker there.)
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null &&
            runCatching { com.optionslab.ira.PreMarket.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val locked = phoneLocked()
            scope.launch { reply(runCatching { IraPreMarket.answer(app, locked) }.getOrElse { "I could not run the checklist just now, Boss." }) }
            return
        }
        // "How close am I to my limits?", "how much can I still lose today?", "how many trades do I have left?"
        // ([com.optionslab.ira.Headroom]): each account against the guard's own limits, the nearest first. Boss's account, so
        // never on a locked phone; reads only - nothing is placed, changed or closed, and no limit is moved. (Not in IraGoldAlgo.)
        val roomAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.Headroom.asked(q) }.getOrNull() else null
        if (roomAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return }
            scope.launch(Dispatchers.IO) { reply(runCatching { IraHeadroom.answer(roomAsk) }.getOrElse { "I could not read your limits just now, Boss." }) }
            return
        }
        // "What did I say about expiry?", "did I note anything about the hammer last week?", "maine expiry ke baare mein kya
        // kaha tha" ([com.optionslab.ira.SaidAbout]): Boss's own notes, trade notes and journal answers searched and read back
        // as he said them. His words, so never on a locked phone; read back only - nothing is taken as a rule and nothing
        // acts. (Not in IraGoldAlgo.)
        val saidAsk = if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.order == null && parsed.command == null)
            runCatching { com.optionslab.ira.SaidAbout.asked(q) }.getOrNull() else null
        if (saidAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return }
            scope.launch(Dispatchers.IO) { reply(runCatching { IraSaidAbout.answer(saidAsk) }.getOrElse { "I could not read your notes just now, Boss." }) }
            return
        }
        // "Is your data fresh?" / "how old are your prices?": how old his prices, candles, news and chain are, and today's
        // record ([com.optionslab.ira.DataAge]; ages only, nothing about the account).
        if (!GOLD_ONLY_TALK && parsed.order == null && parsed.command == null && !bundled && runCatching { com.optionslab.ira.DataAge.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val markets = parsed.markets
            scope.launch { reply(freshAsked(markets)) }
            return
        }
        // Markets and figures the phone has no data for (crude, US markets, the rupee, results, VWAP), targets, or lots with
        // no budget: said honestly, never answered with Nifty's figures ([com.optionslab.ira.Honest]; nothing of the account).
        if (parsed.order == null && parsed.command == null && !bundled)
            runCatching { com.optionslab.ira.Honest.asked(q) }.getOrNull()?.let { a ->
                val follows = if (com.optionslab.app.BuildConfig.GOLD) listOf(com.optionslab.ira.Market.GOLD) else com.optionslab.ira.Market.entries
                _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, com.optionslab.ira.Honest.say(a, follows))).takeLast(MAX_MESSAGES)) }
                return
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
                return
            }
        }
        // IraGoldAlgo: Jarvis talks only - no order, no command (no broker there; its gold arms trade on paper by their rules).
        if (com.optionslab.app.BuildConfig.GOLD && (parsed.order != null || parsed.command != null || Topic.ORDER in parsed.topics || Topic.COMMAND in parsed.topics)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, GOLD_TALK_ONLY)).takeLast(MAX_MESSAGES)) }
            return
        }
        // "Any contradictions?", "do the facts agree?", "what's pulling different ways?", "am I going against my own rules?"
        // ([com.optionslab.ira.Consistency]): market facts pointing different ways, his own numbers disagreeing (and which he
        // goes by), and - on an unlocked phone only - Boss's words against today's trades. Words only, never advice.
        if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD &&
            runCatching { com.optionslab.ira.Consistency.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            scope.launch { reply(runCatching { consistency() }.getOrElse { "I could not check myself for contradictions just now, Boss." }) }
            return
        }
        // "What matters right now?", "brief me", "brief me like a co-pilot" ([com.optionslab.ira.CoPilot]): the facts from every
        // reader ranked by a plain score (recency, size against usual, Boss's open positions, conflicts), the top few said
        // with why each ranked there. Boss's account only on an unlocked phone; facts only, never advice. (IraGoldAlgo keeps
        // the plain briefing below.)
        if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD && !GOLD_ONLY_TALK &&
            runCatching { com.optionslab.ira.CoPilot.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            scope.launch { reply(runCatching { coPilot() }.getOrElse { "I could not put the brief together just now, Boss." }) }
            return
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
            return
        }
        val chainAsk = if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD)
            runCatching { com.optionslab.ira.ChainIntel.asked(q) }.getOrNull() else null
        if (chainAsk != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            val markets = parsed.markets
            scope.launch { reply(runCatching { chainIntel(chainAsk, markets) }.getOrElse { "I could not read the option chain just now, Boss." }) }
            return
        }
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
            return
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
            return
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
                    com.optionslab.app.data.Market.today(), LocalDateTime.now(IST))
            }.getOrElse { "I could not read today's structure just now, Boss." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(said)
            return
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
            return
        }
        // "Make the case", "pros and cons of trading now", "talk me through it": the trade check reasoned out, facts both
        // ways (Boss's own day, goals and rules only on an unlocked phone). Words only; the decision is Boss's. (Before the
        // other answers: these whole questions are this, and the glossary or a plan must not read "explain" or "and" in them.)
        if (!bundled && parsed.order == null && parsed.command == null && !com.optionslab.app.BuildConfig.GOLD &&
            runCatching { com.optionslab.ira.TradeCase.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            scope.launch { reply(runCatching { tradeCase() }.getOrElse { "I could not put the case together just now, Boss." }) }
            return
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
            return
        }
        // "What's your plan today?" / "what are you working on?": Jarvis's own plan for the day (it names Boss's goals and
        // words, so the phone must be unlocked). Words only: the plan itself only ever speaks, studies or works on paper.
        if (com.optionslab.app.BuildConfig.JARVIS && !com.optionslab.app.BuildConfig.GOLD && !bundled && parsed.command == null && parsed.order == null &&
            runCatching { com.optionslab.ira.Agenda.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return }
            scope.launch { reply(runCatching { IraAgenda.say() }.getOrElse { "I could not read my plan just now, Boss." }) }
            return
        }
        // "How are you improving?" / "what are your goals?": his own goals for the week and how they stand ([IraImprove]). Words
        // only: a goal of his only ever speaks, studies, asks Boss to teach him or works on paper.
        if (com.optionslab.app.BuildConfig.JARVIS && !bundled && parsed.command == null && parsed.order == null &&
            runCatching { com.optionslab.ira.Improve.asked(q) }.getOrDefault(false)) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            if (phoneLocked()) { reply("Unlock the phone for that, Boss."); return }
            scope.launch(Dispatchers.IO) { reply(runCatching { IraImprove.say() }.getOrElse { "I could not read my own goals just now, Boss." }) }
            return
        }
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
        // Boss's own reminder ("remind me at 3 pm to check Nifty"): only said at its time, never run (Boss, 4 Oct).
        // "Cancel my reminders": the reminders only (timed commands are cancelled with "cancel everything set for later").
        if (com.optionslab.app.BuildConfig.JARVIS && runCatching { com.optionslab.ira.Reminder.cancelAsked(q) }.getOrDefault(false)) {
            val n = app?.let { c -> runCatching { IraLater.clearReminders(c) }.getOrNull() }
            val said = when (n) { null -> "I could not reach the reminders just now, Boss."; 0 -> "You have no reminders set, Boss."
                else -> "Done, Boss: $n reminder${if (n > 1) "s" else ""} cancelled." }
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, said)).takeLast(MAX_MESSAGES)) }
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
            val voiceOn = JarvisVoice.wanted
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
            val c = app
            val said = if (c != null && Regex("(?i)\\b(cancel|clear|remove|delete|drop)\\b").containsMatchIn(q)) { IraLater.clear(c); "Done, Boss: nothing is set for later now." } else IraLater.say()
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
        // A news question with no recent headlines on the phone: the feeds are read first (8 seconds at most), then answered.
        if ((Topic.NEWS in parsed.topics || deskAsk != null) && testHistories == null && System.currentTimeMillis() - newsCheckedAt > 3 * 60_000 && online() &&
            _state.value.newsAt?.isBefore(Instant.now().minusSeconds(NEWS_EVERY_MINUTES * 60)) != false) {
            newsCheckedAt = System.currentTimeMillis()
            scope.launch { kotlinx.coroutines.withTimeoutOrNull(8_000) { runCatching { freshNews() } }; ask(text, understood) }
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
        IraTools.count("heard")
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
        if (Topic.COMMAND in parsed.topics) { commandAsked(q, parsed.command!!, confirmAlways = understood); return }
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
                val said = runCatching { tradeCheckFast().say() }.getOrElse { "I could not run the trade check just now." }
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
        // Worked out ahead while the recognizer's final reading was awaited ([prepare]): taken only for the same question
        // from the same prices, news and minute (the same words then, exactly); otherwise worked out now, as before.
        val a0 = ahead.take(Inputs(com.optionslab.ira.Turn.key(parsed), st0.snaps, st0.news, book0, now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES), closedReason))
            ?: runCatching { Ira(book0, calls0).answer(q, st0.snaps, st0.news, voice = com.optionslab.app.BuildConfig.JARVIS,
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
        val msg = Msg(true, doubt.wrap(a.text), a.facts, a.order, writing = write)
        _state.update { it.copy(messages = (it.messages + Msg(false, q) + msg).takeLast(MAX_MESSAGES)) }
        // The patterns the answer told of are followed (market data only; withheld words told of nothing).
        if (dressed?.withheld != true && a.calls.isNotEmpty()) scope.launch(Dispatchers.IO) { runCatching { IraTools.patternsTold(a.calls) } }
        if (write) scope.launch {
            // Talking and writing run side by side: the model starts as soon as the voice has started (the first sound is
            // the only moment it would slow), or after 1.5 seconds when nothing is being said.
            val askedAt = android.os.SystemClock.elapsedRealtime()
            var waited = 0
            while (JarvisVoice.speechStartedAt < askedAt && waited < 1_500) { kotlinx.coroutines.delay(100); waited += 100 }
            val better = runCatching { IraModel.rewrite(q, a.facts, a.text) }.getOrNull()
            _state.update { s -> s.copy(messages = s.messages.map { m ->
                if (m !== msg) m else if (better != null && better != a.text) m.copy(text = doubt.wrap(better), draft = msg.text, writing = false) else m.copy(writing = false)
            }) }
        }
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
        val a = runCatching { Ira(b, runCatching { IraTools.patternCalls() }.getOrDefault(emptyList())).answer(q, st.snaps, st.news, voice = com.optionslab.app.BuildConfig.JARVIS, now = now, closedReason = closedReason) }.getOrNull() ?: return
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
            val read = scope.async { runCatching { IraAccount.readFast(com.optionslab.ira.AppAnswers.sections(q), IraMarket.mentioned(q), question = q) }.getOrNull() }
            val v = if (net) read.await() else kotlinx.coroutines.withTimeoutOrNull(5_000) { read.await() }
            if (v == null && !net) {
                _state.update { it.copy(messages = (it.messages + Msg(true, "I'm offline, Boss: your account needs the internet. I can still answer about the markets from the prices saved on the phone.")).takeLast(MAX_MESSAGES)) }
                return@launch
            }
            val a = runCatching { Ira(book).answer(q, emptyMap(), emptyList(), app = v) }.getOrElse { com.optionslab.ira.Answer("I could not work that out.", emptyList()) }
            val write = v != null && IraModel.usable() && a.facts.isNotEmpty()
            val msg = Msg(true, a.text, a.facts, writing = write)
            _state.update { it.copy(messages = (it.messages + msg).takeLast(MAX_MESSAGES)) }
            if (write) {
                val better = runCatching { IraModel.rewrite(q, a.facts, a.text) }.getOrNull()
                _state.update { s -> s.copy(messages = s.messages.map { m ->
                    if (m !== msg) m else if (better != null && better != a.text) m.copy(text = better, draft = a.text, writing = false) else m.copy(writing = false)
                }) }
            }
        }
    }

    private val actions = HashMap<Long, Pair<String, suspend () -> String>>()

    /**
     * "Stop strategy 1", "cancel all orders", "switch to live"... In Jarvis what adds risk runs at once; what stops or
     * closes waits for Confirm. In IraAlgo everything waits for Confirm.
     */
    /**
     * The model's work, or null when it fails or takes over 15 seconds (Boss is never left waiting in silence; a slow
     * run finishes in the background and is dropped).
     */
    private suspend fun <T> modelOrNull(work: suspend () -> T?): T? {
        val d = scope.async { runCatching { work() }.getOrNull() }
        return kotlinx.coroutines.withTimeoutOrNull(15_000) { d.await() }
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
        val holding = scope.launch {
            kotlinx.coroutines.delay(600)
            if (replyAfter(_state.value.messages, q) == null && _state.value.messages.lastOrNull { !it.fromIra }?.text == q) {
                held.set(true)
                _state.update { it.copy(messages = (it.messages + Msg(true, "One moment, Boss, let me think about that.")).takeLast(MAX_MESSAGES)) }
            }
        }
        // The answer's first words: with "one moment" already said, the voice has taken that as the reply, so the answer
        // is spoken here; without it, the voice (or the typed-reply speaker) says the answer itself - never twice.
        suspend fun answerFirst(text: String) { holding.cancelAndJoin(); reply(text); if (held.get()) speakLater(text, q) }
        scope.launch {
            // Words to Jarvis himself ("how was your day") go straight to the chat: no command could be meant.
            val personal = com.optionslab.ira.Chat.personal(q)
            // Words that cannot mean any command skip the first pass too: one model run, not two (Boss, 4 Oct: too slow).
            val line = if (personal || !com.optionslab.ira.Intents.mayMean(q)) null
                else modelOrNull { IraModel.complete(com.optionslab.ira.Intents.prompt(q), 24, 8_000, oneLine = true)?.let { com.optionslab.ira.Intents.pick(it) } }
            if (line == null) {
                // Not something Jarvis can do or look up: the model just talks (a short reply with no figures, no advice
                // and no claimed actions), else a varied "I don't know that".
                // Close to something Jarvis knows: "Did you mean ...?" at once (a suggestion; nothing is done), no model wait.
                val near = if (personal) null else runCatching { com.optionslab.ira.Suggest.line(q) }.getOrNull()
                val chat = if (near != null) null else modelOrNull { com.optionslab.ira.Chat.accept(IraModel.complete(com.optionslab.ira.Chat.prompt(q, LocalDateTime.now(IST), recentTalk(q)), 40, 8_000, oneLine = true)) }
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
            if (act == null) { reply(what); return@launch }
            val at = com.optionslab.ira.Later.say(w.at, java.time.LocalDateTime.now(IST))
            pend("$what $at", { IraLater.add(ctx, w.rest, w.at); "Set, Boss: I'll $what $at, and tell you when it's done." }, "Tap Confirm to $what $at.")
        }
    }

    private val FOR_LATER = Regex("(?i)\\b(set|scheduled?|planned|pending)\\b.*\\blater\\b|\\bfor later\\b")

    private val AT_ONCE = setOf(com.optionslab.ira.Command.Kind.ALARM_ADD, com.optionslab.ira.Command.Kind.EVENT_ADD)

    private fun commandAsked(q: String, c: com.optionslab.ira.Command, confirmAlways: Boolean = false) {
        _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
        // Practice on a past day: replayed into the conversation, nothing traded.
        if (c.kind == com.optionslab.ira.Command.Kind.PRACTICE) { scope.launch { runCatching { IraTools.practice(c.target ?: q) { reply(it) } } }; return }
        scope.launch {
            val (what, act) = runCatching { IraActions.prepare(c) }.getOrElse { ("I could not do that: ${it.message}") to null }
            if (act == null) { reply(what); return@launch }
            // Only a price alarm or an event note is done at once; anything that changes trading (starting arms, the
            // kill switch, autopilot, Jarvis's own limits) waits for Confirm - in the real app too (review, 3 Oct).
            if (c.kind.reduces || !com.optionslab.app.BuildConfig.JARVIS || confirmAlways || c.kind !in AT_ONCE) {
                // The emergency exit asks for the fingerprint on the screen (or Boss's own voice, aloud).
                pend(what, suspend { IraActions.verified(c.kind, act()) }, "Tap Confirm to ${what}.", exit = c.kind == com.optionslab.ira.Command.Kind.EXIT_ALL)
            } else reply(IraActions.run(what, suspend { IraActions.verified(c.kind, act()) }))
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
            for ((i, c) in cmds.withIndex()) {
                if (c == null) continue
                val (what, act) = runCatching { IraActions.prepare(c) }.getOrElse { ("I could not do that: ${it.message}") to null }
                if (act == null) { reply("Step ${i + 1} (\"${steps[i]}\") cannot be done: $what Nothing in the plan was done."); return@launch }
            }
            val plan = com.optionslab.ira.Plan.say(steps)
            pend("this plan: $plan", suspend {
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
                exit = cmds.any { it?.kind == com.optionslab.ira.Command.Kind.EXIT_ALL })
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

    suspend fun confirm(id: Long, fingerprint: Boolean = false, ownerVoice: Boolean = false): String? {
        // IraGoldAlgo: nothing Jarvis prepared is ever done there.
        if (GOLD_ONLY_TALK) { synchronized(actions) { exitIds.remove(id); actions.remove(id) }; _state.update { it.copy(pending = it.pending - id) }; return GOLD_TALK_ONLY.also { reply(it) } }
        if (isExit(id) && !fingerprint && !ownerVoice && fingerprintNeeded())
            return synchronized(actions) { actions.containsKey(id) }.let { waiting -> if (!waiting) null else
                "The emergency exit is confirmed with your fingerprint on the Jarvis screen, or by saying yes in your own voice.".also { reply(it) } }
        // A trade that goes to Zerodha with real money needs the owner's fingerprint on the Jarvis screen (a spoken yes or
        // the notification's Approve is not enough); it stays waiting until then.
        if (asksYesNo(id) && !fingerprint && IraNewsTrades.goesLive(isSolo(id)) && fingerprintNeeded())
            return synchronized(actions) { actions.containsKey(id) }.let { waiting -> if (!waiting) null else
                "This trade goes to Zerodha with real money: approve it with your fingerprint on the Jarvis screen.".also { reply(it) } }
        // Real money only with the fingerprint (or a phone with none, where the app lock covers it): the trade checks this
        // again when placed, so a record that turns proven between now and then never sends one unapproved.
        val yes = asksYesNo(id)
        val a = synchronized(actions) { exitIds.remove(id); actions.remove(id) } ?: return null
        if (yes && (fingerprint || !fingerprintNeeded())) synchronized(actions) { liveApproved += id }
        if (asksYesNo(id)) IraNewsTrades.answered(id, "approved")
        settled(id)
        _state.update { it.copy(pending = it.pending - id) }
        return IraActions.run(a.first, a.second).also { synchronized(actions) { liveApproved.remove(id) }; IraAccount.invalidate(); checked = null; reply(it) }
    }

    /** Does a live trade's approval ask for the fingerprint? (whenever the phone has one; without one the app lock covers it) */
    fun fingerprintNeeded(): Boolean = app?.let { com.optionslab.app.security.BiometricGate.fingerprintOn(it) } ?: true

    /** Is [id] a trade waiting that will need the fingerprint (the Jarvis screen shows the fingerprint button)? */
    fun needsFingerprint(id: Long): Boolean = (isExit(id) || asksYesNo(id) && IraNewsTrades.goesLive(isSolo(id))) && fingerprintNeeded()

    private fun isSolo(id: Long): Boolean = synchronized(actions) { id in soloAsks }

    fun cancelAction(id: Long) {
        // Only what is still waiting can be cancelled: one already confirmed (or lapsed) is not said to be undone.
        val (was, trade) = synchronized(actions) { exitIds.remove(id); (actions.remove(id) != null) to (id in newsAsks) }
        if (!was) { _state.update { it.copy(pending = it.pending - id) }; return }
        if (trade) IraNewsTrades.answered(id, "rejected")
        settled(id)
        IraActivity.add(if (trade) "You rejected a suggested trade; nothing was placed." else "Cancelled a request; nothing was done.")
        _state.update { it.copy(pending = it.pending - id, messages = (it.messages + Msg(true, "Cancelled; nothing was done.")).takeLast(MAX_MESSAGES)) }
    }

    /** How long a request waiting for Confirm stays open before it lapses (nothing done). */
    const val CONFIRM_LAPSE_MS = 30 * 60_000L

    /** A request that waits for Confirm, with its message; it lapses after [CONFIRM_LAPSE_MS]. */
    private fun pend(what: String, act: suspend () -> String, text: String, exit: Boolean = false): Long {
        val id = System.nanoTime()
        // An emergency exit is known as one before anyone can see it (the voice then asks it in Boss's voice only).
        synchronized(actions) { actions[id] = what to act; if (exit) exitIds += id }
        _state.update { it.copy(pending = it.pending + id, messages = (it.messages + Msg(true, text, action = id)).takeLast(MAX_MESSAGES)) }
        scope.launch {
            kotlinx.coroutines.delay(CONFIRM_LAPSE_MS)
            if (synchronized(actions) { exitIds.remove(id); actions.remove(id) } != null) {
                _state.update { it.copy(pending = it.pending - id) }
                reply("Nothing was done about \"$what\": it waited 30 minutes for your Confirm.")
            }
        }
        return id
    }

    /** Every request still waiting is dropped (the conversation was cleared). */
    private fun dropPending() {
        val ids = synchronized(actions) { actions.keys.toList() }
        ids.forEach { id ->
            val (was, trade) = synchronized(actions) { exitIds.remove(id); (actions.remove(id) != null) to (id in newsAsks) }
            if (was && trade) IraNewsTrades.answered(id, "rejected")
            if (was) settled(id)
        }
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
                { e -> e.message ?: "I could not prepare that order." }))
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

    /** Keeps the slow answers ready (called every 30 s while Jarvis listens): your account and the trade check. */
    suspend fun warm() {
        if (GOLD_ONLY_TALK) return
        runCatching { IraAccount.warm() }
        runCatching { checked = android.os.SystemClock.elapsedRealtime() to tradeCheck() }
    }

    /** The day's P&L so far (Zerodha's in Live, else the paper account's), or null when it cannot be read. */
    private suspend fun dayPnlNow(live: Boolean): Double? =
        if (live) runCatching { kotlinx.coroutines.withTimeoutOrNull(8_000) { com.optionslab.app.data.Broker.positionBook().m2m } }.getOrNull()
        else runCatching { com.optionslab.app.data.Paper.snapshot().dayPnl }.getOrNull()

    /** What Boss told about himself that bears on a trade check now ([com.optionslab.ira.AboutBoss.recall]), or null. Words only. */
    private suspend fun aboutBossNow(): String? {
        val notes = IraTools.memory().map { it.text }
        if (notes.isEmpty()) return null
        val m = com.optionslab.app.data.Market
        val live = com.optionslab.app.data.AppSettings.load().live
        return com.optionslab.ira.AboutBoss.recall(notes, com.optionslab.ira.AboutBoss.Moment(m.today(), com.optionslab.ira.AboutBoss.At.CHECK,
            dayPnl = dayPnlNow(live),
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
        val dayPnl = dayPnlNow(s.live)
        val arms = ArrayList<String>()
        runCatching { com.optionslab.app.data.OrbArms.view().arms }.getOrDefault(emptyList()).filter { it.armed }.forEach { arms += it.arm.label }
        com.optionslab.app.data.PineScripts.items.value.filter { it.auto.on }.forEach { arms += it.name }
        runCatching { com.optionslab.app.data.Strategies.all() }.getOrDefault(emptyList()).filter { it.def.scheduler?.enabled == true }.forEach { arms += it.def.name }
        val today = m.today()
        val events = runCatching { IraEvents.upcoming(0) }.getOrDefault(emptyList()).filter { it.day == today && !it.name.endsWith("expiry") }.map { it.name }
        val ipOk = com.optionslab.app.data.StaticIp.registered?.let { runCatching { com.optionslab.app.data.StaticIp.status(force = false).matches }.getOrNull() }
        val burst = if (bn.isNotEmpty()) com.optionslab.ira.Watch.move30(bn)?.first else null
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
            withTimeoutOrNull(CASE_CHAIN_MS) { IraAccount.chain("NIFTY") }
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
        val facts = ArrayList<com.optionslab.ira.CoPilot.Fact>()
        for (m in com.optionslab.ira.SharpMove.INDICES) runCatching { facts += com.optionslab.ira.CoPilot.moves(m, histories[m]?.bars.orEmpty()) }
        val niftyBars = histories[IraMarket.NIFTY]?.bars.orEmpty()
        val structure = runCatching { com.optionslab.ira.Structure.read(IraMarket.NIFTY, niftyBars, today) }.getOrNull()
        com.optionslab.ira.CoPilot.structure(structure)?.let { facts += it }
        runCatching { com.optionslab.ira.CoPilot.structure(com.optionslab.ira.Structure.read(IraMarket.BANKNIFTY, histories[IraMarket.BANKNIFTY]?.bars.orEmpty(), today)) }
            .getOrNull()?.let { facts += it }
        val chainRead = runCatching {
            withTimeoutOrNull(CASE_CHAIN_MS) { IraAccount.chain("NIFTY") }
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
                runCatching { com.optionslab.app.data.Paper.snapshot().dayPnl }.getOrNull()?.let { pnl["Paper"] = it }
                if (com.optionslab.app.data.Broker.loggedIn) runCatching {
                    withTimeoutOrNull(8_000) { com.optionslab.app.data.Broker.positionBook() }?.m2m
                }.getOrNull()?.let { pnl["Zerodha"] = it }
                facts += com.optionslab.ira.CoPilot.limits(mapOf("Zerodha" to set.guardDailyLoss, "Paper" to set.guardPaperDailyLoss), pnl)
            }
            runCatching { facts += com.optionslab.ira.CoPilot.clashes(IraCoach.wordClashes()) }
        }
        val head = runCatching { com.optionslab.ira.Briefing.say(st.snaps, nowAt, emptyList()) }.getOrNull()
        return com.optionslab.ira.CoPilot.brief(head, facts, held, nowAt, locked)
    }

    /** "Make the case" waits at most this long for a fresh option chain. */
    private const val CASE_CHAIN_MS = 8_000L

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
                runCatching { com.optionslab.app.data.Paper.snapshot().dayPnl }.getOrNull()?.let { pnl["Paper"] = it }
                if (com.optionslab.app.data.Broker.loggedIn) runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(8_000) { com.optionslab.app.data.Broker.positionBook() }?.m2m
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
        val now = LocalDateTime.now(IST)
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
        val d = runCatching { com.optionslab.ira.DataAge.dress(text, checks, LocalDateTime.now(IST), withhold) }.getOrNull()
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

    fun note(text: String) {
        val m = Msg(true, text)
        synchronized(unaskedIds) { unaskedIds += m.id; while (unaskedIds.size > 200) unaskedIds.remove(unaskedIds.first()) }
        _state.update { it.copy(messages = (it.messages + m).takeLast(MAX_MESSAGES)) }
    }

    private fun reply(text: String) {
        _state.update { it.copy(messages = (it.messages + Msg(true, text)).takeLast(MAX_MESSAGES)) }
    }

    /** Wipes the conversation (the owner's button). */
    fun forgetConversation() { dropPending(); synchronized(askedAt) { askedAt.clear() }; _state.update { it.copy(messages = emptyList()) } }

    /** Wipes what Ira learned too; it relearns from the data on the next refresh. */
    suspend fun forgetAll() = lock.withLock {
        dropPending()
        IraTools.forgetHabits()
        IraTools.forgetRoutine()
        IraTools.forgetMemory()
        synchronized(askedAt) { askedAt.clear() }
        checked = null
        book = PatternBook()
        lastNews = null
        lastBackground = null
        histories = emptyMap()
        synchronized(tested) { tested.clear() }
        bookFile?.delete()
        stateFile?.delete()
        _state.update { State() }
    }

    const val MAX_MESSAGES = 60
    private val msgSeq = java.util.concurrent.atomic.AtomicLong()
    private val MARKET_TOPICS = setOf(Topic.OVERVIEW, Topic.WHY, Topic.TREND, Topic.LEVELS, Topic.PATTERNS, Topic.VOLATILITY, Topic.ADVICE, Topic.NEWS)

    @Synchronized private fun save() {
        val f = bookFile ?: return
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
