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
            notifyProposal(prop)
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

    /** TEST ONLY: the long history for a backtest instead of Upstox's. */
    @Volatile internal var testLabBars: ((IraMarket, Int) -> List<Candle>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }; field = v }
    /** Two years and a month of [minutes]-minute candles, fetched once a day per market and chart. */
    private val longCache = HashMap<String, Pair<LocalDate, List<Candle>>>()

    private suspend fun twoYears(m: IraMarket, minutes: Int): List<Candle>? {
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
    fun ask(text: String) {
        // Secrets never go further than this line: not into the conversation, the saved history or the model.
        val q = com.optionslab.ira.Secrets.redact(text.trim())
        if (q.isEmpty()) return
        val parsed = Ask.parse(q)
        if (Topic.BACKTEST in parsed.topics) { backtestAsked(q, parsed); return }
        if (Topic.ACCOUNT in parsed.topics) { accountAsked(q); return }
        if (Topic.COMMAND in parsed.topics) { commandAsked(q, parsed.command!!); return }
        if (Topic.TRADE_CHECK in parsed.topics) {
            _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
            scope.launch { reply(runCatching { tradeCheck().say() }.getOrElse { "I could not run the trade check just now." }) }
            return
        }
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
            val v = IraAccount.read(com.optionslab.ira.AppAnswers.sections(q))
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
    private fun commandAsked(q: String, c: com.optionslab.ira.Command) {
        _state.update { it.copy(messages = (it.messages + Msg(false, q)).takeLast(MAX_MESSAGES)) }
        scope.launch {
            val (what, act) = runCatching { IraActions.prepare(c) }.getOrElse { ("I could not do that: ${it.message}") to null }
            if (act == null) { reply(what); return@launch }
            if (c.kind.reduces || !com.optionslab.app.BuildConfig.JARVIS) {
                val id = System.nanoTime()
                synchronized(actions) { actions[id] = what to act }
                _state.update { it.copy(pending = it.pending + id,
                    messages = (it.messages + Msg(true, "Tap Confirm to ${what}.", action = id)).takeLast(MAX_MESSAGES)) }
            } else reply(IraActions.run(what, act))
        }
    }

    /** The owner tapped Confirm on [id]. */
    suspend fun confirm(id: Long) {
        val a = synchronized(actions) { actions.remove(id) } ?: return
        _state.update { it.copy(pending = it.pending - id) }
        reply(IraActions.run(a.first, a.second))
    }

    fun cancelAction(id: Long) {
        synchronized(actions) { actions.remove(id) }
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
