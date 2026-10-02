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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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
                   /** An action waiting for the owner's one tap (its id in [State.pending]). */ val action: Long? = null)

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
    /** TEST ONLY: run the automatic strategy hunt outside JarvisAlgo. */
    @Volatile internal var testAutoLab = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }; field = v }
    /** TEST ONLY: a feed's text instead of the network. */
    @Volatile internal var testFeed: (suspend (String) -> String)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }; field = v }

    fun appContext(): Context? = app

    fun init(context: Context) {
        app = context.applicationContext
        IraModel.init(context)
        val f = File(context.applicationContext.noBackupFilesDir, "ira-book.vault")
        bookFile = f
        book = runCatching { Vault.readFileSteady(f)?.let { PatternBook.load(String(it, Charsets.UTF_8)) } }.getOrNull() ?: PatternBook()
        val sf = File(context.applicationContext.noBackupFilesDir, "ira-state.vault")
        stateFile = sf
        val saved = runCatching { Vault.readFileSteady(sf)?.let { IraSaved.read(String(it, Charsets.UTF_8)) } }.getOrNull()
        synchronized(tested) { tested.clear(); saved?.tested?.let { tested += it } }
        // The process started again: what was kept comes back; a strategy still waiting is offered again in the conversation.
        // The conversation comes back as it was (the owner's wish, 2026-10-02); a strategy still waiting is offered again.
        val talk = saved?.messages.orEmpty()
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

    /** Writes the proposals, the journal and the tried patterns (never the conversation). */
    private fun saveState() {
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

    /** After the close (the evening harvest, JarvisAlgo): read the day's candles, learn them and review the session. */
    suspend fun evening() {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        refresh()
        // The scorecard of today's suggestions, on the day's real option prices (kept by the harvest just before).
        val lines = runCatching { IraNewsTrades.scorecard() }.getOrDefault(emptyList())
        if (lines.isNotEmpty() && lines.first() != "No trades suggested today.") {
            app?.let { JarvisPopup.show(it, "Boss, my scorecard", lines.first()) }
            reply(com.optionslab.ira.Address.boss("My scorecard today. " + lines.joinToString(" ")))
        }
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
                val (live, missing) = live()
                val hs = merge(stored, live)
                val news = newsIfDue()
                var added = 0
                for ((m, h) in hs) for (minutes in listOf(15, 60)) {
                    if (h.bars.isEmpty()) continue
                    val c = Candles.closed(Candles.fold(h.bars, minutes, m), minutes, h.bars.last().t.plusMinutes(1))
                    added += book.learn(m, minutes, c)
                }
                if (added > 0) save()
                val snaps = hs.mapNotNull { (m, h) -> Brain.read(h)?.let { m to it } }.toMap()
                histories = hs
                val days = hs.values.maxOfOrNull { it.days.size } ?: 0
                _state.update { it.copy(loading = false, snaps = snaps, lastDay = snaps.values.maxOfOrNull { s -> s.at.toLocalDate() },
                    days = days, learned = book.size, problem = if (snaps.isEmpty()) "No market data on this phone yet" else null,
                    liveAt = if (live.isNotEmpty()) Instant.now() else it.liveAt, liveMissing = missing,
                    news = news?.first ?: it.news, newsAt = if (news != null) Instant.now() else it.newsAt,
                    newsMissing = news?.second ?: it.newsMissing, best = book.best()) }
                if (news != null && com.optionslab.app.BuildConfig.JARVIS) runCatching { judgeNews(news.first) }
                nightlyIfDue()
            }.onFailure {
                _state.update { s -> s.copy(loading = false, problem = "Could not read the market data") }
            }
        }
        autoLab()
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
     * The weekly review (JarvisAlgo, after the last session of the week): the week and its patterns, then each strategy
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
        for (p in _state.value.proposals.filter { it.status == Proposal.APPROVED && it.pineId != null }) {
            val item = com.optionslab.app.data.PineScripts.get(p.pineId!!) ?: continue
            if (!item.auto.on) continue
            val recent = paper.filter { it.owner.contains(item.name) && it.closedAt.toLocalDate().isAfter(today.minusDays(28)) }
            val sick = com.optionslab.ira.Insights.health(item.name, recent) ?: continue
            if (autopilot) {
                com.optionslab.app.data.PineAuto.arm(item.id, false)
                reply("$sick Autopilot retired it.")
                JarvisPopup.show(c, "Retired: ${item.name}", sick)
            } else {
                val id = System.nanoTime()
                synchronized(actions) { actions[id] = "stop ${item.name}" to suspend { com.optionslab.app.data.PineAuto.arm(item.id, false).let { r -> if (r == "ok") "Stopped ${item.name}." else r } } }
                _state.update { it.copy(pending = it.pending + id, messages = (it.messages + Msg(true, "$sick Tap Confirm to stop it.", action = id)).takeLast(MAX_MESSAGES)) }
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
        val id = (_state.value.proposals.maxOfOrNull { it.id } ?: 0L) + 1
        val prop = Proposal(id, r)
        val tail = if (r.recommended) " Approve it to add it as an arm on paper." else " You can still add it as a paper arm, but I don't recommend it."
        _state.update { it.copy(proposals = it.proposals + prop,
            messages = (it.messages + Msg(true, lead + r.summary() + tail, proposal = id)).takeLast(MAX_MESSAGES)) }
        saveState()
        return prop
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
    suspend fun approve(id: Long): String {
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
        return text
    }

    fun dismiss(id: Long) {
        _state.update { s -> s.copy(proposals = s.proposals.map { if (it.id == id && it.status == Proposal.NEW) it.copy(status = Proposal.DISMISSED) else it },
            messages = (s.messages + Msg(true, "Dismissed. I won't offer that one again today.")).takeLast(MAX_MESSAGES)) }
        saveState()
    }

    /**
     * From the market watch (JarvisAlgo only): refresh and look for strategies at most every [BACKGROUND_MINUTES] while
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
    }

    /**
     * Every 5 minutes (JarvisAlgo, market hours): the news, read and judged; each new headline that matters is told once,
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

    /**
     * Through the night (JarvisAlgo, outside market hours, called hourly): the trusted feeds read, and each new headline
     * that matters kept quietly with what it means, for the 9 AM brief - no pop-up, no voice at night.
     */
    suspend fun nightNews(now: Instant = Instant.now()) {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        if (IraMarket.NIFTY.trading(now.atZone(IST).toLocalDateTime())) return
        val got = newsIfDue() ?: return
        _state.update { it.copy(news = got.first, newsAt = Instant.now(), newsMissing = got.second) }
        val fresh = synchronized(newsSeen) { got.first.filter { newsSeen.add(it.link.ifBlank { it.title }) } }
        newsPrimed = true
        val cut = now.minusSeconds(18 * 3600)
        fresh.filter { h -> h.at?.isAfter(cut) == true && com.optionslab.ira.NewsAnalyst.matters(h) }.take(6).forEach { h ->
            IraStudy.overnight(h.at!!, com.optionslab.ira.NewsAnalyst.take(h, emptyMap(), emptySet()).text)
        }
    }

    private val newsSeen = HashSet<String>()
    @Volatile private var newsPrimed = false

    /** New headlines that matter, told once (a pop-up and a line in the conversation); the first read only primes. */
    private suspend fun judgeNews(heads: List<Headline>) {
        val c = app ?: return
        val fresh = synchronized(newsSeen) { heads.filter { newsSeen.add(it.link.ifBlank { it.title }) } }
        if (!newsPrimed) { newsPrimed = true; return }
        val recent = fresh.filter { h -> h.at?.isAfter(Instant.now().minusSeconds(30 * 60)) != false }.filter { com.optionslab.ira.NewsAnalyst.matters(it) }.take(3)
        if (recent.isEmpty()) return
        val arms = HashMap<String, IraMarket>()
        runCatching { com.optionslab.app.data.OrbArms.view().arms }.getOrDefault(emptyList()).filter { it.armed }.forEach { arms[it.arm.label] = IraMarket.BANKNIFTY }
        com.optionslab.app.data.PineScripts.items.value.filter { it.auto.on }.forEach { x -> IraMarket.entries.firstOrNull { it.name == x.auto.symbol }?.let { arms[x.name] = it } }
        val held = runCatching { com.optionslab.app.data.Paper.state.positions.filter { it.quantity != 0 }.mapNotNull { p ->
            listOf(IraMarket.BANKNIFTY, IraMarket.FINNIFTY, IraMarket.NIFTY, IraMarket.SENSEX).firstOrNull { p.symbol.startsWith(it.name) } }.toSet() }.getOrDefault(emptySet())
        for (h in recent) {
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
    private fun proposeTrade(c: Context, idea: com.optionslab.ira.NewsTrade.Idea, title: String, text: String, said: String, source: String) {
        val m = idea.market
        val snap = _state.value.snaps[m]
        val side = if (idea.call) "call" else "put"
        val what = "buy 1 lot of the ${m.label} $side at the money, nearest expiry, with a 15% stop, a +${IraNewsTrades.TARGET_POINTS.toInt()} target and the profit lock"
        val id = System.nanoTime()
        synchronized(actions) {
            actions[id] = what to suspend { IraNewsTrades.place(idea, _state.value.snaps[m]?.price ?: snap?.price ?: error("no ${m.label} price"), source) }
            newsAsks += id
        }
        IraNewsTrades.suggested(id, idea, snap?.price ?: 0.0, source)
        val where = if (IraNewsTrades.paperFirst && com.optionslab.app.data.AppSettings.load().live) " (on paper: my trades stay there until proven)" else ""
        val full = "$text Shall I $what$where? Approve or reject."
        _state.update { it.copy(pending = it.pending + id, messages = (it.messages + Msg(true, full, action = id)).takeLast(MAX_MESSAGES)) }
        JarvisApproval.show(c, id, title, full)
        JarvisVoice.askYesNo(id, "$said Shall I buy 1 lot of the ${m.label} $side? Yes or no?")
        scope.launch {
            kotlinx.coroutines.delay(NEWS_ANSWER_MS)
            if (synchronized(actions) { actions.remove(id) } != null) {
                settled(id)
                IraNewsTrades.answered(id, "lapsed")
                _state.update { it.copy(pending = it.pending - id) }
                reply("No answer in 10 minutes, so the ${m.label} trade lapsed; nothing was placed.")
            }
        }
    }

    private fun expiryToday(m: IraMarket) = runCatching { com.optionslab.app.data.Market.isExpiryDay(m.name) }.getOrDefault(false)

    /** Is [id] a suggested trade Jarvis asks a yes or no about (so the voice does not also say "tap Confirm")? */
    fun asksYesNo(id: Long): Boolean = synchronized(actions) { id in newsAsks }

    @Volatile private var lastExpertSlot: LocalDateTime? = null
    private val suggested = HashSet<String>()

    /**
     * The pattern expert on watch (JarvisAlgo, market hours, called by the market watch; it gates itself): at each 5- and
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
                proposeTrade(c, idea, "Trade idea: ${idea.market.label}", idea.why, "Hey Boss, a trade idea. ${Wake.spoken(idea.why, 1)}", "pattern: " + key)
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

    /** [id] was answered or lapsed: its pop-up hides and Jarvis stops waiting for a yes or no. */
    private fun settled(id: Long) {
        synchronized(actions) { newsAsks.remove(id) }
        app?.let { JarvisApproval.hide(it, id) }
        JarvisVoice.answered(id)
    }

    /**
     * JarvisAlgo, every 15 minutes in market hours: the near-the-money option chains (Nifty, BankNifty, FinNifty) as
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
     * Jarvis's watch (JarvisAlgo, every 15 minutes in market hours): India VIX jumping, an index moving a lot or suddenly,
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
            JarvisPopup.show(c, a.title, a.text)
            reply(a.text)
        }
    }
    const val BACKGROUND_MINUTES = 15L
    private const val NOTIFY_BASE = 7300

    /** A question in, Ira's answer appended to the conversation. */
    fun ask(text: String) = ask(text, understood = false)

    /** [understood]: [text] is the model's reading of the owner's words (its actions always wait for Confirm). */
    private fun ask(text: String, understood: Boolean) {
        // Secrets never go further than this line: not into the conversation, the saved history or the model.
        val q = com.optionslab.ira.Secrets.redact(text.trim())
        if (q.isEmpty()) return
        val parsed = Ask.parse(q)
        if (Topic.BACKTEST in parsed.topics) { backtestAsked(q, parsed); return }
        if (Topic.ACCOUNT in parsed.topics) { accountAsked(q); return }
        if (Topic.COMMAND in parsed.topics) { commandAsked(q, parsed.command!!, confirmAlways = understood); return }
        if (Topic.SUGGEST in parsed.topics) { suggestAsked(q, parsed.markets); return }
        val explain = parsed.pattern?.takeIf { Topic.EXPLAIN in parsed.topics }
        if (explain != null) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            reply(com.optionslab.ira.PatternExpert.explain(explain, IraStudy.state.value.edges))
            return
        }
        if (Topic.TRADE_CHECK in parsed.topics) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            scope.launch { reply(runCatching { tradeCheck().say() }.getOrElse { "I could not run the trade check just now." }) }
            return
        }
        // JarvisAlgo, words Jarvis does not know: the model maps them to one line of a fixed list (never an order).
        if (parsed.topics == setOf(Topic.OFF_TOPIC) && com.optionslab.app.BuildConfig.JARVIS && IraModel.usable() && !understood) { freeFormAsked(q); return }
        // JarvisAlgo: a complete order is placed at once (the owner's rule); IraAlgo keeps the review.
        parsed.order?.takeIf { com.optionslab.app.BuildConfig.JARVIS && it.missing.isEmpty() && it.refusal == null }?.let { o -> tradeAsked(q, o); return }
        val a = runCatching { Ira(book).answer(q, _state.value.snaps, _state.value.news, voice = com.optionslab.app.BuildConfig.JARVIS) }.getOrElse { com.optionslab.ira.Answer("I could not work that out.", emptyList()) }
        // JarvisAlgo with the model ready: the answer shows at once, then the model rewrites it in place if it passes the checks.
        val write = IraModel.usable() && com.optionslab.ira.Writer.worthRewriting(parsed, a)
        val msg = Msg(true, a.text, a.facts, a.order, writing = write)
        _state.update { it.copy(messages = (it.messages + Msg(false, q) + msg).takeLast(MAX_MESSAGES)) }
        if (write) scope.launch {
            val better = runCatching { IraModel.rewrite(q, a.facts, a.text) }.getOrNull()
            _state.update { s -> s.copy(messages = s.messages.map { m ->
                if (m !== msg) m else if (better != null && better != a.text) m.copy(text = better, draft = a.text, writing = false) else m.copy(writing = false)
            }) }
        }
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
            val v = IraAccount.read(com.optionslab.ira.AppAnswers.sections(q), IraMarket.mentioned(q))
            val a = Ira(book).answer(q, emptyMap(), emptyList(), app = v)
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
     * "Stop strategy 1", "cancel all orders", "switch to live"... In JarvisAlgo what adds risk runs at once; what stops or
     * closes waits for Confirm. In IraAlgo everything waits for Confirm.
     */
    /**
     * Free-form words: the on-device model picks one line of [com.optionslab.ira.Intents.LINES]; only a valid line is
     * used, said back ("I understood: ..."), and any action from it waits for Confirm. Otherwise the usual answer.
     */
    private fun freeFormAsked(q: String) {
        scope.launch {
            val line = runCatching { IraModel.complete(com.optionslab.ira.Intents.prompt(q))?.let { com.optionslab.ira.Intents.pick(it) } }.getOrNull()
            if (line == null) { ask(q, understood = true); return@launch }
            _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, "I understood: \"$line\".")).takeLast(MAX_MESSAGES)) }
            ask(line, understood = true)
        }
    }

    private fun commandAsked(q: String, c: com.optionslab.ira.Command, confirmAlways: Boolean = false) {
        _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
        scope.launch {
            val (what, act) = runCatching { IraActions.prepare(c) }.getOrElse { ("I could not do that: ${it.message}") to null }
            if (act == null) { reply(what); return@launch }
            if (c.kind.reduces || !com.optionslab.app.BuildConfig.JARVIS || confirmAlways) {
                val id = System.nanoTime()
                synchronized(actions) { actions[id] = what to act }
                _state.update { it.copy(pending = it.pending + id,
                    messages = (it.messages + Msg(true, "Tap Confirm to ${what}.", action = id)).takeLast(MAX_MESSAGES)) }
            } else reply(IraActions.run(what, act))
        }
    }

    /** The owner tapped Confirm (or said yes) on [id]: what happened, or null when it was already settled. */
    suspend fun confirm(id: Long): String? {
        val a = synchronized(actions) { actions.remove(id) } ?: return null
        if (asksYesNo(id)) IraNewsTrades.answered(id, "approved")
        settled(id)
        _state.update { it.copy(pending = it.pending - id) }
        return IraActions.run(a.first, a.second).also { reply(it) }
    }

    fun cancelAction(id: Long) {
        if (asksYesNo(id)) IraNewsTrades.answered(id, "rejected")
        synchronized(actions) { actions.remove(id) }
        settled(id)
        _state.update { it.copy(pending = it.pending - id, messages = (it.messages + Msg(true, "Cancelled; nothing was done.")).takeLast(MAX_MESSAGES)) }
    }

    /** A complete order in JarvisAlgo: the contract is found and the trade placed at once, in the app's mode. */
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
    suspend fun tradeCheck(): com.optionslab.ira.TradeCheck.Verdict {
        val s = com.optionslab.app.data.AppSettings.load()
        val m = com.optionslab.app.data.Market
        val st = _state.value
        val bn = histories[IraMarket.BANKNIFTY]?.bars.orEmpty()
        val bnSnap = st.snaps[IraMarket.BANKNIFTY]
        val dayPnl = if (s.live) runCatching { kotlinx.coroutines.withTimeoutOrNull(8_000) { com.optionslab.app.data.Broker.positionBook().m2m } }.getOrNull()
            else runCatching { com.optionslab.app.data.Paper.snapshot().dayPnl }.getOrNull()
        val arms = ArrayList<String>()
        runCatching { com.optionslab.app.data.OrbArms.view().arms }.getOrDefault(emptyList()).filter { it.armed }.forEach { arms += it.arm.label }
        com.optionslab.app.data.PineScripts.items.value.filter { it.auto.on }.forEach { arms += it.name }
        runCatching { com.optionslab.app.data.Strategies.all() }.getOrDefault(emptyList()).filter { it.def.scheduler?.enabled == true }.forEach { arms += it.def.name }
        val today = m.today()
        val events = runCatching { IraEvents.upcoming(0) }.getOrDefault(emptyList()).filter { it.day == today && !it.name.endsWith("expiry") }.map { it.name }
        val ipOk = com.optionslab.app.data.StaticIp.registered?.let { runCatching { com.optionslab.app.data.StaticIp.status(force = false).matches }.getOrNull() }
        val burst = if (bn.isNotEmpty()) com.optionslab.ira.Watch.move30(bn)?.first else null
        return com.optionslab.ira.TradeCheck.check(com.optionslab.ira.TradeCheck.Now(
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
        ))
    }

    /** A message from Jarvis itself (the morning check). */
    fun note(text: String) = reply(text)

    private fun reply(text: String) {
        _state.update { it.copy(messages = (it.messages + Msg(true, text)).takeLast(MAX_MESSAGES)) }
    }

    /** Wipes the conversation (the owner's button). */
    fun forgetConversation() { _state.update { it.copy(messages = emptyList()) } }

    /** Wipes what Ira learned too; it relearns from the data on the next refresh. */
    suspend fun forgetAll() = lock.withLock {
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

    private fun save() {
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
    /** Live index minutes are written to the app's record at most this often (JarvisAlgo). */
    const val KEEP_EVERY_MS = 5 * 60_000L

    /**
     * JarvisAlgo keeps the live day as it trades: each index's 1-minute candles (with volume and OI where the feed has
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

    private fun load(): Map<IraMarket, History> = SOURCES.mapNotNull { (m, u) ->
        val bars = ArrayList<Candle>()
        val all = Store.barDays(u)
        val from = all.takeLast(KEEP_DAYS).firstOrNull()
        // The last KEEP_DAYS days: enough for the snapshots and for learning each new day (the book skips what it learned).
        Store.barSessions(u).forEach { s -> s.index?.let { ix -> if (from == null || !s.day.isBefore(from)) bars += candles(s.day, ix) } }
        if (bars.isEmpty()) null else m to History(m, bars)
    }.toMap()

    /** A harvested index series as Ira's candles (minutes are IST minutes of the day). */
    internal fun candles(day: LocalDate, ix: Series): List<Candle> {
        if (ix.right != Right.IX) return emptyList()
        return (0 until ix.size).map { i ->
            val c = ix.close[i]
            Candle(day.atStartOfDay().plusMinutes(ix.minutes[i].toLong()), ix.open?.get(i) ?: c, ix.high?.get(i) ?: c, ix.low?.get(i) ?: c, c)
        }
    }
}
