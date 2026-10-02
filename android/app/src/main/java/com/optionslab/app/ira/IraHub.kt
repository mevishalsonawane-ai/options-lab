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
import kotlinx.coroutines.Dispatchers
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
    data class Msg(val fromIra: Boolean, val text: String, val facts: List<String> = emptyList(), val order: OrderRequest? = null)

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
    )

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
    val state: StateFlow<State> = _state
    private val lock = Mutex()
    @Volatile private var book = PatternBook()
    private var bookFile: File? = null

    /** TEST ONLY: histories instead of the app's store. */
    @Volatile internal var testHistories: (() -> Map<IraMarket, History>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }; field = v }
    /** TEST ONLY: a market's live candles instead of the network (the test app sets one that returns none). */
    @Volatile internal var testLive: (suspend (IraMarket) -> List<Candle>)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }; field = v }
    /** TEST ONLY: a feed's text instead of the network. */
    @Volatile internal var testFeed: (suspend (String) -> String)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "test seam" }; field = v }

    fun init(context: Context) {
        val f = File(context.applicationContext.noBackupFilesDir, "ira-book.vault")
        bookFile = f
        book = runCatching { Vault.readFileSteady(f)?.let { PatternBook.load(String(it, Charsets.UTF_8)) } }.getOrNull() ?: PatternBook()
        _state.update { it.copy(learned = book.size) }
    }

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
                val days = hs.values.maxOfOrNull { it.days.size } ?: 0
                _state.update { it.copy(loading = false, snaps = snaps, lastDay = snaps.values.maxOfOrNull { s -> s.at.toLocalDate() },
                    days = days, learned = book.size, problem = if (snaps.isEmpty()) "No market data on this phone yet" else null,
                    liveAt = if (live.isNotEmpty()) Instant.now() else it.liveAt, liveMissing = missing,
                    news = news?.first ?: it.news, newsAt = if (news != null) Instant.now() else it.newsAt,
                    newsMissing = news?.second ?: it.newsMissing) }
            }.onFailure {
                _state.update { s -> s.copy(loading = false, problem = "Could not read the market data") }
            }
        }
    }

    /** A question in, Ira's answer appended to the conversation. */
    fun ask(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        val a = runCatching { Ira(book).answer(q, _state.value.snaps, _state.value.news) }.getOrElse { com.optionslab.ira.Answer("I could not work that out.", emptyList()) }
        _state.update { it.copy(messages = (it.messages + Msg(false, q) + Msg(true, a.text, a.facts, a.order)).takeLast(MAX_MESSAGES)) }
    }

    /** Wipes the conversation (the owner's button). */
    fun forgetConversation() { _state.update { it.copy(messages = emptyList()) } }

    /** Wipes what Ira learned too; it relearns from the data on the next refresh. */
    suspend fun forgetAll() = lock.withLock {
        book = PatternBook()
        lastNews = null
        bookFile?.delete()
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
