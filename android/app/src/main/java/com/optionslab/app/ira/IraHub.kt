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
                   /** A strategy proposal this message carries (its [Proposal.id]). */ val proposal: Long? = null)

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
    )

    /** One session's review: the patterns Ira saw on the index charts (15 and 60 minutes) and how many went their way. */
    data class DayScore(val day: LocalDate, val seen: Int, val worked: Int) {
        val rate: Double get() = if (seen == 0) 0.0 else worked.toDouble() / seen
    }

    /** The app's harvested underlyings Ira reads from storage. */
    val SOURCES = listOf(IraMarket.NIFTY to "NIFTY", IraMarket.BANKNIFTY to "BANKNIFTY", IraMarket.VIX to "INDIAVIX")

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
    )
    /** News is fetched at most this often; headlines older than [NEWS_DAYS] days are dropped. */
    const val NEWS_EVERY_MINUTES = 10L
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

    fun init(context: Context) {
        app = context.applicationContext
        val f = File(context.applicationContext.noBackupFilesDir, "ira-book.vault")
        bookFile = f
        book = runCatching { Vault.readFileSteady(f)?.let { PatternBook.load(String(it, Charsets.UTF_8)) } }.getOrNull() ?: PatternBook()
        val sf = File(context.applicationContext.noBackupFilesDir, "ira-state.vault")
        stateFile = sf
        val saved = runCatching { Vault.readFileSteady(sf)?.let { IraSaved.read(String(it, Charsets.UTF_8)) } }.getOrNull()
        synchronized(tested) { tested.clear(); saved?.tested?.let { tested += it } }
        // The process started again: what was kept comes back; a strategy still waiting is offered again in the conversation.
        val waiting = saved?.proposals.orEmpty().filter { it.status == Proposal.NEW }
            .map { Msg(true, "Still waiting for your decision. " + it.result.summary(), proposal = it.id) }
        _state.value = State(learned = book.size, best = book.best(), proposals = saved?.proposals.orEmpty(),
            journal = saved?.journal.orEmpty(), nightlyAt = saved?.nightlyAt, messages = waiting.takeLast(MAX_MESSAGES))
    }

    /** Writes the proposals, the journal and the tried patterns (never the conversation). */
    private fun saveState() {
        val f = stateFile ?: return
        val st = _state.value
        val tried = synchronized(tested) { tested.toList() }
        runCatching { Vault.writeFile(f, IraSaved.write(st.proposals.takeLast(KEEP_PROPOSALS), st.journal, st.nightlyAt, tried,
            LocalDate.now(IST)).toByteArray(Charsets.UTF_8)) }
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
    suspend fun evening() { if (com.optionslab.app.BuildConfig.JARVIS) refresh() }

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
    private fun autoLab() {
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
            notifyProposal(prop)
        }
        if (tried) saveState()
    }

    private fun chartWord(minutes: Int) = if (minutes == 60) "1-hour" else "$minutes-minute"

    /** Backtests a pattern on the candles read last; rupees from real option prices where the phone has them. */
    private fun lab(kind: PatternKind, m: IraMarket, minutes: Int): StrategyLab.Result? {
        val h = histories[m] ?: return null
        val u = LIVE[m] ?: return null
        return runCatching {
            StrategyLab.backtest(kind, m, minutes, h.bars) { trades, bars ->
                if (testHistories != null) null else {
                    val r = com.optionslab.engine.pine.PinePremium.run(trades, bars, { d -> Store.barSession(u, d) },
                        com.optionslab.app.data.PineAuto.strikeStep(u), 1, 100_000.0)
                    if (r.priced == 0) null else (r.report?.netProfit ?: 0.0) to r.priced
                }
            }
        }.getOrNull()
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
    }
    const val BACKGROUND_MINUTES = 15L
    private const val NOTIFY_BASE = 7300

    /** A question in, Ira's answer appended to the conversation. */
    fun ask(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        val parsed = Ask.parse(q)
        if (Topic.BACKTEST in parsed.topics) { backtestAsked(q, parsed); return }
        val a = runCatching { Ira(book).answer(q, _state.value.snaps, _state.value.news) }.getOrElse { com.optionslab.ira.Answer("I could not work that out.", emptyList()) }
        _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, a.text, a.facts, a.order)).takeLast(MAX_MESSAGES)) }
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
            .map { b -> candle(Instant.ofEpochSecond(b.epochSecond).atZone(IST).toLocalDateTime(), b) }
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
