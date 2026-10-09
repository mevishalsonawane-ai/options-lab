package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.security.Vault
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import com.optionslab.ira.Events
import com.optionslab.ira.Flows
import com.optionslab.ira.Headline
import com.optionslab.ira.MarketRecord
import com.optionslab.ira.RecorderFeeds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The market recorder (Boss's approval, 06 Oct 2026; the formats are [MarketRecord]'s): on trading days from 09:00 to 15:35,
 * once a pass of the market watch, it appends what the app ALREADY reads - the news and official notices as first seen,
 * the event calendar, NSE's FII/DII figures, and with a Zerodha session the index futures with their basis, the order
 * book at the money, the option chain at ATM +/-15 every 5 minutes and the Rs 1-5 options in it - to one encrypted file a
 * day under noBackupFilesDir (never in a backup), so a study in a few months can test what explains direction.
 *
 * Never in the way of trading: [kick] launches the pass in the recorder's own scope and returns at once (the watch never
 * waits on it), one pass at a time, each bounded to 50 s; every read and the write are caught, and a failure is written as
 * a gap ("G") and counted, never thrown. Reads: the news, the calendar and the FII/DII figures from what Jarvis already
 * holds (FII/DII once a day, NSE's page Jarvis already reads); Zerodha's quote call once a minute for ~30 keys and once
 * every 5 minutes for the chain (a key the live stream has a fresh full tick for is answered from it), the futures list
 * once a day (NFO's from the options dump the app downloads anyway), and after the close their 1-minute candles (4 reads).
 * Nothing about the account and no token, key or URL is ever written ([MarketRecord.scrub]).
 *
 * Outside the window, only two public feeds (Boss, 06 Oct 2026; [RecorderFeeds]): GIFT Nifty from NSE IX's own ticker
 * (www.nseix.com) every 15 minutes 06:30-09:15 and at 23:30 (every 5 minutes in hours, inside the pass), and NSE's
 * participant-wise OI and volume files (nsearchives.nseindia.com) once a trade date from 18:30, retried until the next
 * session opens. They run from one alarm re-armed at its next slot, each firing a short WorkManager run ([fired],
 * [RecorderWorker]); no service, no other host.
 */
object MarketRecorder {
    private var dirOrNull: File? = null
    private val dir: File get() = dirOrNull ?: throw IllegalStateException("not initialised")

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        dirOrNull = File(context.applicationContext.noBackupFilesDir, "recorder")
    }

    private const val K_ON = "recorder.on"
    private const val K_GAPS = "recorder.gaps"

    /** Boss's switch (default on: approved 06 Oct 2026). */
    var on: Boolean
        get() = runCatching { SecurePrefs.getBoolean(K_ON, true) }.getOrDefault(true)
        set(v) {
            SecurePrefs.put(K_ON, v); _status.value = null
            // The off-hours alarm (GIFT Nifty, NSE's participant files) follows the switch.
            appContext?.let { c -> runCatching { scheduleOffHours(c) } }
        }

    // ---- what it reads (the app's own feeds; tests swap them) ---------------------------------------------------------

    /** Every read the recorder makes, so a test can stand in for the feeds. */
    interface Source {
        fun loggedIn(): Boolean
        suspend fun futures(): List<Kite.Future>
        suspend fun options(): List<Kite.Instrument>
        fun indexKey(name: String): String?
        fun indexToken(name: String): Long?
        suspend fun quotes(keys: Map<String, Long?>): Map<String, MarketRecord.Quote>
        suspend fun minuteBars(token: Long, day: LocalDate): List<Upstox.Bar>
        /** Jarvis's latest headlines, when they were read, and the feeds that did not answer. */
        fun news(): Triple<List<Headline>, Instant?, List<String>>
        fun events(): List<Events.Event>
        suspend fun flows(): List<Flows.Flow>
        fun matters(h: Headline): Boolean
        /** Values that must never appear in a record (the session token, the API key). */
        fun secrets(): List<String?>
        /** A public NSE / NSE IX page as text ([RecorderFeeds]' sources: no login, cookie or key). */
        suspend fun text(url: String, tries: Int): String
    }

    private object Live : Source {
        override fun loggedIn() = Broker.loggedIn
        override suspend fun futures() = Broker.futures()
        override suspend fun options() = Broker.instruments()
        override fun indexKey(name: String) = Broker.indexKey(name)
        override fun indexToken(name: String) = Broker.indexToken(name)
        override suspend fun quotes(keys: Map<String, Long?>) = Broker.recorderQuotes(keys)
        override suspend fun minuteBars(token: Long, day: LocalDate) = Broker.minuteBars(token, day)
        override fun news(): Triple<List<Headline>, Instant?, List<String>> {
            val s = com.optionslab.app.ira.IraHub.state.value
            return Triple(s.news, s.newsAt, s.newsMissing)
        }
        override fun events() = com.optionslab.app.ira.IraEvents.upcoming(14)
        override suspend fun flows() = com.optionslab.app.ira.IraHub.flows()
        override fun matters(h: Headline) = com.optionslab.ira.NewsAnalyst.matters(h)
        override fun secrets() = listOf(Broker.streamToken(), Broker.apiKey)
        // The same plain HTTPS read as Jarvis's FII/DII and NSE circulars (Net.getText: browser User-Agent, no cookies,
        // no redirects, 5 s / 10 s timeouts, 1.5 MB at most, errors without the URL or body).
        // (Jarvis's test feed, null in the app, stands in for every such read in the JVM tests, as for FII/DII.)
        override suspend fun text(url: String, tries: Int) = com.optionslab.app.ira.IraHub.testFeed?.invoke(url) ?: Net.getText(url, tries)
    }

    /** TEST ONLY: the feeds and the clock. Null in the app, always; the setters throw unless BuildConfig.DEBUG. */
    @Volatile internal var testSource: Source? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test feed exists only in debug builds" }; field = v }
    @Volatile internal var testNow: ZonedDateTime? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test clock exists only in debug builds" }; field = v }

    private val src: Source get() = testSource ?: Live
    private fun now(): ZonedDateTime = testNow ?: Market.now()

    // ---- the pass, off the watch's path -------------------------------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    /** A pass is given this long; past it, it is stopped and written as a gap. */
    const val PASS_MS = 50_000L

    /**
     * From each pass of the market watch: starts one recording pass in the recorder's own scope and returns at once (it
     * never waits, never throws). Off, outside 09:00-15:35 on a trading day, or a pass still running: nothing.
     */
    fun kick() {
        try {
            if (dirOrNull == null || com.optionslab.app.BuildConfig.GOLD || !on) return
            val t = now()
            if (!MarketRecord.inWindow(Market.isTradingDay(t.toLocalDate()), t.toLocalTime())) return
            if (job?.isActive == true) { busy++; return }
            job = scope.launch {
                val done = try { withTimeoutOrNull(PASS_MS) { pass(t); true } } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    runCatching { append(t.toLocalDate(), listOf(MarketRecord.gap(t.toLocalTime(), "pass", MarketRecord.why(e)))) }
                    runCatching { countGaps(t.toLocalDate(), 1) }
                    true
                }
                if (done == null) runCatching {
                    append(t.toLocalDate(), listOf(MarketRecord.gap(now().toLocalTime(), "pass", "over ${PASS_MS / 1000} s")))
                    countGaps(t.toLocalDate(), 1)
                }
            }
        } catch (_: Throwable) {
            // Never the watch's problem.
        }
    }

    /** A recording pass is running now. */
    internal val running: Boolean get() = job?.isActive == true

    /** Passes skipped because the one before was still running (in memory; a diagnostic only). */
    @Volatile var busy = 0
        private set

    /** The day's state (in memory; rebuilt from the day's file after a restart where it matters). */
    private class Day(val day: LocalDate) {
        val seen = HashSet<String>()
        var seenLoaded = false
        var header = false
        var rotated = false
        var events = false
        var flows = false
        var flowsTriedAt = 0L
        var lastNewsAt: Instant? = null
        var lastMinute = -1
        var chainMinute = -1
        var bars = false
        /** Each future's last 1-minute candle written (minute of the day). */
        val barsUpTo = HashMap<String, Int>()
        var noLogin = false
        var futuresGap = false
        val spot = HashMap<String, Double>()
    }
    @Volatile private var today: Day? = null

    private fun dayState(d: LocalDate): Day = today?.takeIf { it.day == d } ?: Day(d).also { today = it }

    /** One recording pass at [at] (internal: the tests call it directly). Throws only what the caller writes as a gap. */
    internal suspend fun pass(at: ZonedDateTime) {
        val day = at.toLocalDate()
        val t = at.toLocalTime().withNano(0)
        val st = dayState(day)
        val lines = ArrayList<String>()
        val gaps = ArrayList<String>()
        val gap: (String, String) -> Unit = { what, why -> gaps += MarketRecord.gap(t, what, why) }
        if (!st.header) { lines += MarketRecord.header(day); st.header = true }
        if (!st.rotated) { st.rotated = true; part("rotation", gap) { rotate(day) } }
        part("news", gap) { news(st, day, t, lines, gap) }
        if (!st.events) part("events", gap) {
            st.events = true
            src.events().forEach { e -> lines += MarketRecord.event(MarketRecord.Event(e.day, null, e.name, e.owner)) }
        }
        if (!st.flows && System.currentTimeMillis() - st.flowsTriedAt > 30 * 60_000L) part("FII/DII", gap) {
            st.flowsTriedAt = System.currentTimeMillis()
            val f = src.flows()
            if (f.isEmpty()) gap("FII/DII", "no figures from NSE")
            else { st.flows = true; f.forEach { lines += MarketRecord.flow(it.date, it.who, it.buy, it.sell, it.net) } }
        }
        part("market", gap) { market(st, at, lines, gap) }
        val secrets = runCatching { src.secrets() }.getOrDefault(emptyList())
        val all = (lines + gaps).map { MarketRecord.scrub(it, secrets) }
        if (all.isNotEmpty()) append(day, all)
        if (gaps.isNotEmpty()) countGaps(day, gaps.size)
        // GIFT Nifty every 5 minutes in hours (one try: the pass has 50 s), and yesterday's participant file's one gap if
        // it was tried and never read. After the pass's own frame, so the day's file starts with its header.
        feeds(at, tries = 1)
    }

    // ---- GIFT Nifty and NSE's participant files: also outside the window ([RecorderFeeds]) ------------------------------

    private const val K_OI_LAST = "recorder.oi.last"
    private const val K_OI_TRY = "recorder.oi.try"
    private const val K_OI_MISSED = "recorder.oi.missed"
    private const val K_GIFT = "recorder.gift.last"

    private fun pref(k: String): String? = runCatching { SecurePrefs.getString(k) }.getOrNull()
    private fun date(k: String): LocalDate? = pref(k)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    private fun time(k: String): LocalDateTime? = pref(k)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    /** One reader of the two feeds at a time (the watch's pass and the off-hours worker may meet at 09:00-09:15). */
    private val feedLock = kotlinx.coroutines.sync.Mutex()
    /** When GIFT Nifty was last tried (in memory: a failure is not tried again before its next slot). */
    @Volatile private var giftTriedAt: LocalDateTime? = null
    /** When each feed last wrote a gap: a broken page is one gap an hour, not one every 5 minutes. */
    private val gapAt = java.util.concurrent.ConcurrentHashMap<String, LocalDateTime>()

    private fun trading(d: LocalDate): Boolean = runCatching { Market.isTradingDay(d) }.getOrDefault(false)
    private val isTrading: (LocalDate) -> Boolean = { d -> trading(d) }

    /** NSE answers 404 (or 403) for a participant file not published yet: not a failure, it is tried again later. */
    private fun notPublished(e: Throwable) = e is Net.HttpFailure && (e.code == 404 || e.code == 403)

    /** A participant file is still to be read for a trade date (the alarm then keeps its evening retries). */
    private fun oiPending(now: LocalDateTime): Boolean {
        val d = RecorderFeeds.oiTradeDate(now, isTrading) ?: return false
        val rec = date(K_OI_LAST)
        return rec == null || rec.isBefore(d)
    }

    /**
     * GIFT Nifty and NSE's participant-wise OI / volume, each only when due ([RecorderFeeds.giftDue], [RecorderFeeds.oiDue]):
     * read, parsed and written to the day file they belong in (GIFT Nifty: today's; a participant file: its trade date's),
     * a failure written as a gap (one an hour at most per feed). Never throws but a cancellation; [tries] per read.
     */
    internal suspend fun feeds(at: ZonedDateTime, tries: Int) {
        if (!feedLock.tryLock()) return
        try {
            val now = at.toLocalDateTime().withNano(0)
            val day = at.toLocalDate()
            val t = now.toLocalTime()
            val out = LinkedHashMap<LocalDate, MutableList<String>>()
            val gapsBy = HashMap<LocalDate, Int>()
            fun add(d: LocalDate, line: String) { out.getOrPut(d) { ArrayList() } += line }
            fun fail(d: LocalDate, what: String, why: String) {
                val key = if (what.startsWith("GIFT")) "gift" else "participant"
                if (!RecorderFeeds.gapAllowed(gapAt[key], now)) return
                gapAt[key] = now
                add(d, MarketRecord.gap(t, what, why)); gapsBy[d] = (gapsBy[d] ?: 0) + 1
            }
            // GIFT Nifty.
            val lastGift = RecorderFeeds.giftDecode(pref(K_GIFT))?.first
            val last = listOfNotNull(lastGift, giftTriedAt).filter { !it.isAfter(now) }.maxOrNull()
            if (RecorderFeeds.giftDue(trading(day), now, last)) {
                giftTriedAt = now
                try {
                    val g = RecorderFeeds.parseGift(src.text(RecorderFeeds.GIFT_URL, tries))
                    if (g == null) fail(day, "GIFT Nifty", "no NIFTY future in NSE IX's answer")
                    else {
                        add(day, RecorderFeeds.gift(t, g))
                        runCatching { SecurePrefs.put(K_GIFT, RecorderFeeds.giftEncode(g, now)) }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                    fail(day, "GIFT Nifty", MarketRecord.why(e))
                }
            }
            // NSE's participant-wise OI (and trading volume), once a trade date, from 18:30.
            RecorderFeeds.oiDue(now, isTrading, date(K_OI_LAST), time(K_OI_TRY))?.let { d ->
                runCatching { SecurePrefs.put(K_OI_TRY, now.toString()) }
                try {
                    val p = RecorderFeeds.parseParticipants(src.text(RecorderFeeds.oiUrl(d), tries))
                    if (p.date != d) fail(d, "participant OI $d", "the file is dated ${p.date}")
                    else {
                        p.rows.forEach { add(d, RecorderFeeds.participant(p, now, "oi", it)) }
                        runCatching { SecurePrefs.put(K_OI_LAST, d.toString()) }
                        // The volume file beside it, once (best effort: a miss is a gap, not a retry).
                        try {
                            val v = RecorderFeeds.parseParticipants(src.text(RecorderFeeds.volUrl(d), tries))
                            if (v.date == d) v.rows.forEach { add(d, RecorderFeeds.participant(v, now, "vol", it)) }
                            else fail(d, "participant volume $d", "the file is dated ${v.date}")
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                            fail(d, "participant volume $d", if (notPublished(e)) "not published" else MarketRecord.why(e))
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                    // Not published yet: tried again at the next slot; the gap only if the next session opens without it.
                    if (!notPublished(e)) fail(d, "participant OI $d", MarketRecord.why(e))
                }
            }
            RecorderFeeds.oiMissed(now, isTrading, date(K_OI_LAST), time(K_OI_TRY), date(K_OI_MISSED))?.let { d ->
                runCatching { SecurePrefs.put(K_OI_MISSED, d.toString()) }
                add(day, MarketRecord.gap(t, "participant OI $d", "not published or not read before the session")); gapsBy[day] = (gapsBy[day] ?: 0) + 1
            }
            if (out.isEmpty()) return
            val secrets = runCatching { src.secrets() }.getOrDefault(emptyList())
            for ((d, lines) in out) {
                try {
                    val head = if (fileOf(d).exists()) emptyList() else listOf(MarketRecord.header(d))
                    append(d, (head + lines).map { MarketRecord.scrub(it, secrets) })
                    gapsBy[d]?.let { countGaps(d, it) }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) {
                    runCatching { countGaps(d, 1) }
                }
            }
        } finally {
            feedLock.unlock()
        }
    }

    /** The off-hours alarm's action (to [com.optionslab.app.work.AlarmReceiver]). */
    const val ACTION = "ol.recorder.offhours"
    /** An off-hours run is given this long. */
    const val OFF_MS = 40_000L

    private fun alarmIntent(context: Context): android.app.PendingIntent = android.app.PendingIntent.getBroadcast(
        context, 196, android.content.Intent(context, com.optionslab.app.work.AlarmReceiver::class.java).setAction(ACTION),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Arms the off-hours alarm at the next slot ([RecorderFeeds.nextSlot]: 06:30-09:00 every 15 minutes, 18:30, the
     * evening's retries while a participant file is missing, 23:30 on trading days; 08:00 on a closed day while one is
     * missing). Off: cancelled. No service: each firing hands one short run to WorkManager ([fired]).
     */
    fun scheduleOffHours(context: Context) {
        if (com.optionslab.app.BuildConfig.GOLD) return
        val am = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        val pi = alarmIntent(context)
        am.cancel(pi)
        if (!on) return
        val now = now().toLocalDateTime()
        val at = RecorderFeeds.nextSlot(now, isTrading, oiPending(now)).atZone(MarketRecord.IST).toInstant().toEpochMilli()
        try {
            if (com.optionslab.app.work.Jobs.canExact(context)) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** The off-hours alarm fired: re-arm, then one short run in WorkManager (with a network; never two at once). */
    fun fired(context: Context) {
        runCatching { scheduleOffHours(context) }
        if (com.optionslab.app.BuildConfig.GOLD || !on) return
        // Not expedited: no foreground notice for a few seconds' read (minSdk 26 would need one); a run Doze holds back
        // waits for its next window, and its records carry the time they were read.
        val req = androidx.work.OneTimeWorkRequestBuilder<RecorderWorker>()
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .build()
        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork("recorder.offhours", androidx.work.ExistingWorkPolicy.KEEP, req)
    }

    /** One off-hours run: GIFT Nifty and the participant files when due, bounded to [OFF_MS]; never throws. */
    suspend fun offHours() {
        try {
            if (dirOrNull == null || com.optionslab.app.BuildConfig.GOLD || !on) return
            val at = now()
            withTimeoutOrNull(OFF_MS) { feeds(at, tries = 2) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Never anyone's problem.
        }
    }

    /** One part of a pass: a failure is that part's gap, never the pass's end (a cancellation goes up). */
    private suspend fun part(what: String, gap: (String, String) -> Unit, block: suspend () -> Unit) {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { gap(what, MarketRecord.why(e)) }
    }

    private fun news(st: Day, day: LocalDate, t: LocalTime, lines: MutableList<String>, gap: (String, String) -> Unit) {
        val (heads, at, missing) = src.news()
        if (!st.seenLoaded) {
            st.seenLoaded = true
            // After a restart mid-day: the stories already written today are not written again.
            runCatching { readDay(day).lines.mapNotNullTo(st.seen) { MarketRecord.newsKey(it) } }
        }
        val fresh = at != null && at != st.lastNewsAt
        // First seen: when Jarvis's read that brought it in happened (to the second), else this pass.
        val seenAt = at?.takeIf { fresh }?.atZone(MarketRecord.IST)?.toLocalTime()?.withNano(0)
            ?.takeIf { it.isAfter(t.minusMinutes(10)) && !it.isAfter(t) } ?: t
        // Since the last session's close (the night's news included); older stories were the last day's.
        val since = lastSessionClose(day)
        for (h in heads) {
            if (h.at?.atZone(MarketRecord.IST)?.toLocalDateTime()?.isBefore(since) == true) continue
            val n = MarketRecord.News(seenAt, h.source, h.at, h.title, h.link, h.tone, h.toneWord, h.markets.map { it.name },
                runCatching { src.matters(h) }.getOrDefault(false), h.also)
            if (n.key.isEmpty() || !st.seen.add(n.key)) continue
            lines += MarketRecord.news(n)
        }
        if (fresh) {
            st.lastNewsAt = at
            missing.forEach { gap("news: $it", "the feed did not answer") }
        }
    }

    private fun lastSessionClose(day: LocalDate): LocalDateTime {
        var d = day.minusDays(1)
        repeat(10) { if (runCatching { Market.isTradingDay(d) }.getOrDefault(true)) return d.atTime(15, 30); d = d.minusDays(1) }
        return d.atTime(15, 30)
    }

    private suspend fun market(st: Day, at: ZonedDateTime, lines: MutableList<String>, gap: (String, String) -> Unit) {
        val day = at.toLocalDate()
        val t = at.toLocalTime().withNano(0)
        val minute = t.hour * 60 + t.minute
        if (!src.loggedIn()) {
            // Once a day: futures, the order book and the chain come from Zerodha only.
            if (!st.noLogin) { st.noLogin = true; gap("futures, order book, chain", "no Zerodha login") }
            return
        }
        // Inside the session only (09:15-15:30) for prices; once a minute.
        if (minute < Market.OPEN || minute > Market.CLOSE) return
        // (Its own four; MIDCPNIFTY's future is in the list for the order flow only.)
        val futs = runCatching { Kite.nearestFutures(src.futures().filter { it.name in MarketRecord.FUTURES }, day) }.getOrElse { e ->
            if (!st.futuresGap) { st.futuresGap = true; gap("futures list", MarketRecord.why(e)) }
            emptyMap()
        }
        // The futures' 1-minute candles: at 15:29 (to 15:28) and again at 15:30 or later for the rest - the watch may not
        // have a pass after 15:30.
        if (minute >= Market.CLOSE - 1 && !st.bars) { if (minute >= Market.CLOSE) st.bars = true; closeBars(st, futs, day, minute, lines, gap) }
        if (minute == st.lastMinute || minute >= Market.CLOSE) return
        st.lastMinute = minute
        val keys = LinkedHashMap<String, Long?>()
        val spotKey = HashMap<String, String>()
        for (ix in MarketRecord.FUTURES + "INDIAVIX") src.indexKey(ix)?.let { k -> keys[k] = src.indexToken(ix); spotKey[ix] = k }
        futs.values.forEach { keys[it.key] = it.token }
        // The book at the money: from the last minute's level (the first minute reads the levels first).
        if (MarketRecord.CHAINS.any { it !in st.spot }) runCatching {
            src.quotes(MarketRecord.CHAINS.mapNotNull { ix -> spotKey[ix]?.let { it to keys[it] } }.toMap())
        }.getOrNull()?.let { q -> MarketRecord.CHAINS.forEach { ix -> spotKey[ix]?.let { q[it] }?.last?.takeIf { it > 0 }?.let { st.spot[ix] = it } } }
        val opts = runCatching { src.options() }.getOrElse { e -> gap("option list", MarketRecord.why(e)); emptyList() }
        // Battery: on a low battery (not charging) the chain is read every 15 minutes instead of 5 (the book stays per minute).
        val saving = testSource == null && runCatching { com.optionslab.app.work.Battery.saving(appContext) }.getOrDefault(false)
        val chainDue = st.chainMinute < 0 || minute - st.chainMinute >= (if (saving) 3 else 1) * MarketRecord.CHAIN_EVERY_MIN
        data class Opt(val ins: Kite.Instrument, val depth: Boolean, val chain: Boolean)
        val wanted = ArrayList<Opt>()
        for (ix in MarketRecord.CHAINS) {
            val s = st.spot[ix] ?: continue
            val listed = opts.filter { it.name == ix && !it.expiry.isBefore(day) }
            val expiry = listed.minOfOrNull { it.expiry } ?: continue
            val chain = listed.filter { it.expiry == expiry }
            val strikes = chain.map { it.strike }
            val near = MarketRecord.around(strikes, s, MarketRecord.DEPTH_STRIKES).toSet()
            val wide = if (chainDue) MarketRecord.around(strikes, s, MarketRecord.CHAIN_STRIKES).toSet() else emptySet()
            for (o in chain) if (o.strike in near || o.strike in wide) wanted += Opt(o, o.strike in near, o.strike in wide)
        }
        wanted.forEach { keys["NFO:${it.ins.tradingSymbol}"] = it.ins.token }
        val q = src.quotes(keys)
        // Levels and futures.
        for ((ix, k) in spotKey) q[k]?.last?.takeIf { it > 0 }?.let { lines += MarketRecord.spot(t, ix, it); st.spot[ix] = it }
        for ((name, f) in futs) {
            val fq = q[f.key] ?: continue
            lines += MarketRecord.future(t, name, f.tradingSymbol, f.expiry, fq, st.spot[name])
            lines += MarketRecord.depth(t, f.tradingSymbol, fq)
        }
        // The book at the money.
        for (o in wanted.filter { it.depth }) q["NFO:${o.ins.tradingSymbol}"]?.let { lines += MarketRecord.depth(t, o.ins.tradingSymbol, it) }
        // The chain (and the Rs 1-5 options in it), every 5 minutes.
        if (chainDue && wanted.any { it.chain }) {
            st.chainMinute = minute
            val now = at.toLocalDateTime()
            for (ix in MarketRecord.CHAINS) {
                val rows = wanted.filter { it.chain && it.ins.name == ix }
                if (rows.isEmpty()) continue
                val s = st.spot[ix] ?: continue
                fun last(o: Opt) = q["NFO:${o.ins.tradingSymbol}"]?.last
                val atm = MarketRecord.atm(rows.map { it.ins.strike }, s)
                val fwd = atm?.let { k -> MarketRecord.forward(k, rows.firstOrNull { it.ins.strike == k && it.ins.right == Right.CE }?.let(::last),
                    rows.firstOrNull { it.ins.strike == k && it.ins.right == Right.PE }?.let(::last)) }
                for (o in rows.sortedWith(compareBy<Opt>({ it.ins.strike }, { it.ins.right.name }))) {
                    val oq = q["NFO:${o.ins.tradingSymbol}"] ?: continue
                    val iv = runCatching { MarketRecord.iv(oq.last, o.ins.right == Right.CE, fwd, o.ins.strike, now, o.ins.expiry) }.getOrNull()
                    lines += MarketRecord.chain(t, ix, o.ins.expiry, o.ins.strike, o.ins.right.name, oq, iv)
                    if (MarketRecord.isCheap(oq.last)) lines += MarketRecord.cheap(t, ix, o.ins.expiry, o.ins.strike, o.ins.right.name, oq)
                }
            }
        }
        if (q.isEmpty() && keys.isNotEmpty()) gap("quotes", "no quotes returned")
    }

    /** After the close: each future's 1-minute candles with OI (Zerodha's historical read, 4 reads, spaced). */
    private suspend fun closeBars(st: Day, futs: Map<String, Kite.Future>, day: LocalDate, minute: Int, lines: MutableList<String>, gap: (String, String) -> Unit) {
        for ((name, f) in futs) {
            try {
                val from = st.barsUpTo[name] ?: -1
                val bars = src.minuteBars(f.token, day).filter { it.istDate == day && it.istMinute > from && it.istMinute < minute }
                if (bars.isEmpty() && from < 0) gap("1-minute candles $name", "none returned")
                bars.lastOrNull()?.let { st.barsUpTo[name] = it.istMinute }
                bars.forEach { b ->
                    lines += MarketRecord.bar(LocalTime.of(b.istMinute / 60, b.istMinute % 60), name, f.tradingSymbol, b.open, b.high, b.low, b.close, b.volume, b.oi)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                gap("1-minute candles $name", MarketRecord.why(e))
            }
            delay(400)
        }
    }

    // ---- the files ----------------------------------------------------------------------------------------------------

    private val fileLock = Any()

    private fun fileOf(day: LocalDate) = File(dir, "$day.rec")

    /** Appends one encrypted frame of [lines] to [day]'s file (each append stands alone: a torn write loses that append only). */
    internal fun append(day: LocalDate, lines: List<String>) {
        if (lines.isEmpty()) return
        val frame = MarketRecord.frame(lines) { Vault.encrypt(it) }
        synchronized(fileLock) {
            dir.mkdirs()
            java.io.FileOutputStream(fileOf(day), true).use { it.write(frame); it.fd.sync() }
        }
        _status.value = null
    }

    /** [day]'s records, decrypted (frames that cannot be opened are counted, not thrown). */
    internal fun readDay(day: LocalDate): MarketRecord.Read {
        val f = fileOf(day)
        if (!f.exists()) return MarketRecord.Read(emptyList(), 0)
        val bytes = synchronized(fileLock) { f.readBytes() }
        return MarketRecord.read(bytes) { Vault.decrypt(it) }
    }

    private val DAY_FILE = Regex("^(\\d{4}-\\d{2}-\\d{2})\\.rec$")

    /** The day files kept, oldest first. */
    internal fun days(): List<Pair<LocalDate, File>> = (dirOrNull?.listFiles() ?: emptyArray()).mapNotNull { f ->
        DAY_FILE.find(f.name)?.let { m -> runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull()?.let { it to f } }
    }.sortedBy { it.first }

    /** Twelve months kept; over the cap the oldest days go (never today's). Once a day, from the day's first pass. */
    internal fun rotate(today: LocalDate) {
        val plan = MarketRecord.rotate(days().map { MarketRecord.DayFile(it.first, it.second.length()) }, today)
        synchronized(fileLock) { plan.delete.forEach { fileOf(it).delete() } }
        if (plan.overBudget) runCatching {
            com.optionslab.app.work.Alerts.post("Market recorder is over its ${MarketRecord.size(MarketRecord.BUDGET_BYTES)} budget " +
                "(${MarketRecord.size(plan.keptBytes)}); the oldest days go first. Export it from More, Data & Harvest.",
                com.optionslab.app.work.Alerts.Kind.ERROR, throttle = true)
        }
        if (plan.delete.isNotEmpty()) _status.value = null
    }

    @Synchronized private fun countGaps(day: LocalDate, n: Int) {
        val m = MarketRecord.gapsDecode(runCatching { SecurePrefs.getString(K_GAPS) }.getOrNull()).toMutableMap()
        m[day] = (m[day] ?: 0) + n
        runCatching { SecurePrefs.putAllSoon(mapOf(K_GAPS to MarketRecord.gapsEncode(m))) }
        _status.value = null
    }

    // ---- the card, Jarvis and the export ------------------------------------------------------------------------------

    private val _status = kotlinx.coroutines.flow.MutableStateFlow<MarketRecord.Status?>(null)

    /** What is kept (reads the folder listing and the gap counts; no decryption). Call off the main thread. */
    fun status(): MarketRecord.Status {
        _status.value?.let { return it }
        val files = days()
        val day = Market.today()
        val gaps = MarketRecord.gapsDecode(runCatching { SecurePrefs.getString(K_GAPS) }.getOrNull())
        val bytes = files.sumOf { it.second.length() }
        val last = files.maxOfOrNull { it.second.lastModified() }?.takeIf { it > 0 }
            ?.let { Instant.ofEpochMilli(it).atZone(MarketRecord.IST).toLocalDateTime().withNano(0) }
        return MarketRecord.Status(on, files.size, bytes, files.firstOrNull()?.first, files.lastOrNull()?.first, last,
            gaps[day] ?: 0, gaps.filterKeys { k -> files.any { it.first == k } || k == day }.values.sum(), bytes > MarketRecord.BUDGET_BYTES,
            participants = date(K_OI_LAST), gift = RecorderFeeds.giftDecode(pref(K_GIFT)))
            .also { _status.value = it }
    }

    /** The last GIFT Nifty reading kept (when it was read, and the reading), or null. Read only. */
    fun lastGift(): Pair<LocalDateTime, RecorderFeeds.Gift>? = RecorderFeeds.giftDecode(pref(K_GIFT))

    /** The recorded days with each file's size in bytes, newest first (the folder listing only; nothing decrypted). */
    fun recordedDays(): List<Pair<LocalDate, Long>> = days().reversed().map { it.first to it.second.length() }

    /**
     * [day]'s records for the "Market data" viewer (decrypted here, on this phone; nothing written or sent). A missing
     * day is empty; frames that cannot be opened are counted. Call off the main thread.
     */
    fun dayRecords(day: LocalDate): MarketRecord.Read = if (dirOrNull == null) MarketRecord.Read(emptyList(), 0) else readDay(day)

    /**
     * NSE's participant-wise OI of the two newest trade dates in the recorded day files ([com.optionslab.ira.MorningCues]:
     * the FIIs' positioning and its change), newest first; at most the newest 12 day files are opened. Read only (the
     * files are decrypted here, on this phone, for Jarvis's answer; nothing is written or sent). Call off the main thread.
     */
    fun participantsRecorded(): List<RecorderFeeds.Participants> {
        if (dirOrNull == null) return emptyList()
        val out = ArrayList<RecorderFeeds.Participants>()
        // Today's file holds no participant rows before NSE publishes them (read from 18:30): not decrypted for nothing.
        val t = now()
        val skip = t.toLocalDate().takeIf { t.toLocalTime().isBefore(LocalTime.of(18, 30)) }
        for ((d, _) in days().reversed().filter { it.first != skip }.take(12)) {
            runCatching { com.optionslab.ira.MorningCues.participants(readDay(d).lines) }.getOrDefault(emptyList())
                .forEach { p -> if (out.none { it.date == p.date }) out += p }
            if (out.size >= 2) break
        }
        return out.sortedByDescending { it.date }.take(2)
    }

    /** Jarvis's answer to "how much market data have we recorded" (his own data; nothing acts). */
    fun answer(): String = MarketRecord.answer(status(), Market.today())

    /**
     * Every recorded day as CSV in one zip ([MarketRecord.README] first), written to [out] (the file Boss picked). The
     * days are decrypted here, on this phone, into the file he chose; nothing is sent anywhere. Returns the days written.
     */
    fun export(out: OutputStream): Int {
        var n = 0
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("README.txt")); zip.write(MarketRecord.README.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            // The order flow's 1-second bars (one gzip CSV a day, 30 days) and its signal log, as kept (9 Oct).
            runCatching { OrderFlowLive.export(zip) }
            for ((day, _) in days()) {
                val r = readDay(day)
                zip.putNextEntry(ZipEntry("market-$day.csv"))
                val w = zip.bufferedWriter(Charsets.UTF_8)
                r.lines.forEach { w.write(it); w.write("\n") }
                if (r.bad > 0) w.write(MarketRecord.gap(LocalTime.MIDNIGHT, "file", "${r.bad} part(s) unreadable") + "\n")
                w.flush()
                zip.closeEntry()
                n++
            }
        }
        return n
    }

    /** Erase: every recorded day and the gap counts (Boss's erase-everything). */
    fun wipe() {
        runCatching { dirOrNull?.deleteRecursively() }
        runCatching { SecurePrefs.put(K_GAPS, null) }
        for (k in listOf(K_OI_LAST, K_OI_TRY, K_OI_MISSED, K_GIFT)) runCatching { SecurePrefs.put(k, null) }
        giftTriedAt = null
        gapAt.clear()
        today = null
        _status.value = null
        RecordedData.clear()
    }
}

/** The market recorder's off-hours run ([MarketRecorder.fired]): GIFT Nifty and NSE's participant files, then the next alarm. */
class RecorderWorker(ctx: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        MarketRecorder.offHours()
        runCatching { MarketRecorder.scheduleOffHours(applicationContext) }
        return Result.success()
    }
}
