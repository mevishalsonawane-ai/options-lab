package com.optionslab.app.data

import com.optionslab.app.security.KitePin
import com.optionslab.engine.KiteTicks
import com.optionslab.ira.StreamHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Zerodha's live price stream: one WebSocket to ws.kite.trade that pushes every
 * trade of the instruments the app cares about, instead of the app asking for
 * quotes over and over.
 *
 *  - Runs only in Live mode with a Zerodha session (Paper stays on the Upstox feed).
 *  - What it follows is the union of what each part of the app wants: the three
 *    indices, open positions, the option chain on screen, price alarms, and any
 *    instrument a quote was just asked for.
 *  - Reconnects by itself (0.5 s, 1 s, 2 s … 30 s; at once again only after a connection
 *    that stayed up a minute); stops when the session ends.
 *  - A socket that stays open in market hours but goes silent for 10 s (Kite sends a
 *    heartbeat every second) is closed and opened again (the stall watchdog).
 *  - Each drop's reason goes to the diagnostics diary ([StreamHealth]): the close code,
 *    or the error's kind, how long it was up, ticks, tokens, foreground, network, watch.
 *  - The connection carries the access token, so it is pinned like the REST calls
 *    (its own trust-on-first-use CA set); the URL, API key and token are never logged.
 *  - 20 Nov 2026 (audit): the phone's network changing ([NetWatch]) closes the connection and opens a new one at once on the
 *    new network, the held positions subscribed first ([StreamHealth.subscribeOrder]); a subscription Zerodha's socket did
 *    not take is tried again, never counted as done; the kept order and position reads are dropped on every drop and open
 *    (order updates may have been missed meanwhile); a packet that arrived late is stamped by the exchange's own time
 *    ([com.optionslab.ira.TickStamp]); and the stall watchdog and the waits run on the monotonic clock, so a wall clock set
 *    back never stalls them.
 *
 * [Broker.quotes] answers from here when every asked instrument has a fresh tick,
 * which also makes the option chain, alarms, strategies and re-pricing faster.
 */
object KiteStream {
    enum class Status { OFF, CONNECTING, LIVE, RETRYING }

    /**
     * [conn]: 20 Nov 2026, the connection that brought it (0: the test feed), so a connection's first packet of each instrument is known.
     * [arrived]: when it reached the phone ([at] is that, or earlier for a packet that came late: [com.optionslab.ira.TickStamp]).
     */
    data class Seen(val tick: KiteTicks.Tick, val at: Long, val conn: Int = 0, val arrived: Long = at)

    /** 20 Nov 2026: numbers the connections ([Conn.id], [Seen.conn]). */
    private val connIds = java.util.concurrent.atomic.AtomicInteger()

    private val ticks = ConcurrentHashMap<Long, Seen>()
    private val wants = ConcurrentHashMap<String, Set<Long>>()
    private val subscribed = HashSet<Long>()

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status

    /**
     * Bumped when prices arrive, so the screens can redraw from [tick]: at most every [com.optionslab.ira.Coalescer.GAP_MS]
     * (250 ms), always ending on the latest (a burst's last tick is shown at most 250 ms after it, [bump]).
     */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version
    private val redraw = com.optionslab.ira.Coalescer()

    /** Prices arrived at [now]: the screens' version bumped now, or once at the end of the 250 ms gap (never per tick). */
    private fun bump(now: Long) {
        val d = synchronized(redraw) { redraw.offer(now) }
        if (d.now) { _version.value = now; return }
        val at = d.scheduleAt ?: return
        scope.launch {
            // 20 Nov 2026: never longer than a second (a wall clock set back between the offer and this wait).
            delay((at - System.currentTimeMillis()).coerceIn(0L, 1_000L))
            val t = System.currentTimeMillis()
            synchronized(redraw) { redraw.fired(t) }
            _version.value = t
        }
    }

    /** When the latest tick of any instrument reached the phone (0: none yet): the screens' "live" / "delayed" word. */
    @Volatile var lastTickAt: Long = 0L
        private set

    /**
     * Local candles (round 2): every streamed instrument's 1-minute candles by the exchange's stamp, so the minute and
     * bar-close arms decide at the first tick after a boundary ([LocalCandles]). Memory only.
     */
    val candles = com.optionslab.ira.LiveCandles()

    /**
     * 28 Oct (IraThinker's flow, Boss: "Use live data from zerodha instead"): the whole session's 1-minute candles of the flow's
     * indices and India VIX ([SESSION_TOKENS]) built from Zerodha's ticks ALONE - never laid over by another feed (the [candles]
     * above take the candle feed's values) and kept for the full day ([SESSION_MINUTES]). Memory only: a set lookup and a map
     * update per tick of those four instruments, as [candles].
     */
    val sessionCandles = com.optionslab.ira.LiveCandles(SESSION_MINUTES)

    /** NIFTY, BANKNIFTY, FINNIFTY and India VIX (Zerodha's index tokens): the instruments [sessionCandles] keeps. */
    val SESSION_TOKENS: Set<Long> = setOf(256265L, 260105L, 257801L, 264969L)

    /** A whole day's minutes, 09:00 to 15:45 and some margin. */
    const val SESSION_MINUTES = 480

    /** 28 Oct: the stream's reconnects today (memory only; the Flow page's "reconnects today"). */
    @Volatile private var reconnectDay: java.time.LocalDate? = null
    @Volatile private var reconnects = 0

    /** The stream's reconnects counted today in this process (memory only). */
    fun reconnectsToday(): Int = if (reconnectDay == runCatching { Market.today() }.getOrNull()) reconnects else 0

    private fun countReconnect() {
        val d = runCatching { Market.today() }.getOrNull() ?: return
        if (reconnectDay != d) { reconnectDay = d; reconnects = 0 }
        reconnects++
    }

    /** When the current connection dropped (0: not down), for the gap figure; and drops counted today. */
    @Volatile private var downSince = 0L

    /** 20 Nov 2026: the monotonic clock (ms; deep sleep counted), for the watchdog and the waits: a wall clock set back never stalls them. */
    private fun mono(): Long = android.os.SystemClock.elapsedRealtime()

    /** 20 Nov 2026: a late packet's time by the exchange's own stamp ([com.optionslab.ira.TickStamp]). Memory only. */
    private val stamps = com.optionslab.ira.TickStamp()

    /** 20 Nov 2026: ends a wait between tries at once (the phone's network changed). Memory only. */
    private val kick = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Waits [ms] at most; true when [kick] ended it early. */
    private suspend fun pause(ms: Long): Boolean = ms > 0 && withTimeoutOrNull(ms) { kick.receive() } != null

    /** 20 Nov 2026: a subscribe or unsubscribe message the socket did not take: tried again on the watchdog's next look. */
    @Volatile private var subscribeOwed = false

    // 22 Nov 2026 (B6 review): a failure escaping one of its jobs is noted, never the end of the process ([ScopeGuard]).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + ScopeGuard.handler("price stream"))
    private var loop: Job? = null
    @Volatile private var socket: WebSocket? = null

    private val client: OkHttpClient by lazy { streamClient() }

    /**
     * 22 Nov 2026 (B6 review: the stream could sit in CONNECTING for ever - a TLS handshake or Zerodha's 101 upgrade that stalled never
     * timed out, with no read limit on a stream and nothing else watching a socket that never opened): how long a connection may take
     * to OPEN (the connect, the TLS handshake and the upgrade) before it is given up and tried again. Two guards: the client's call
     * limit ends a stalled handshake by itself - OkHttp lifts that limit the moment the socket opens (its Exchange.newWebSocketStreams
     * calls timeoutEarlyExit), so an open stream is never cut by it - and the watch gives up on one not open by then ([watch]).
     */
    internal const val OPEN_TIMEOUT_MS = 20_000L

    /** The stream's client: pinned like the REST calls, no read limit (a stream: Kite sends a heartbeat every second), [openTimeoutMs] to open. */
    internal fun streamClient(openTimeoutMs: Long = OPEN_TIMEOUT_MS): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(KitePin.streamSocketFactory, KitePin.streamTrust)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)      // a stream; Kite sends a heartbeat every second
        .callTimeout(openTimeoutMs, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** 22 Nov 2026: a connection asked for at [startedAt] (the monotonic clock) and still not open at [now] after [limitMs]: given up. Pure. */
    internal fun openOverdue(opened: Boolean, startedAt: Long, now: Long, limitMs: Long = OPEN_TIMEOUT_MS): Boolean =
        !opened && now - startedAt > limitMs

    /**
     * 22 Nov 2026 (B6 review: the status stayed CONNECTING or RETRYING after [stop]): [s] is set only while [still] holds, under the
     * same lock as [stop], so a stop landing anywhere in the loop or in an open is never followed by a stale status. True when set.
     */
    private inline fun setStatus(s: Status, still: () -> Boolean): Boolean = synchronized(this) {
        if (still()) { _status.value = s; true } else false
    }

    // ---- what to follow -------------------------------------------------------------------

    /** Instruments one part of the app follows; replaces that part's earlier set. */
    fun want(owner: String, tokens: Collection<Long>) {
        val set = tokens.filter { it > 0 }.toSet()
        if (wants[owner] == set) return
        if (set.isEmpty()) wants.remove(owner) else wants[owner] = set
        resubscribe()
        // The order flow's and the tick zone's instruments ride along with the stream; they never wake it or keep it running
        // by themselves (12 Oct: the tick zone records only while something else has the stream on; 14 Oct: the OI-wall shadow
        // log's one option too).
        if (set.isNotEmpty() && owner != "index" && owner != OrderFlowLive.OWNER && owner != TickZoneRecorder.OWNER &&
            owner != OiWallShadowHost.OWNER) wake()
    }

    private val touched = ConcurrentHashMap<Long, Long>()

    /** Instruments a quote was just asked for: followed for the next three minutes. */
    fun touch(tokens: Collection<Long>) {
        val now = System.currentTimeMillis()
        var added = false
        tokens.filter { it > 0 }.forEach { if (touched.put(it, now) == null) added = true }
        if (added) { resubscribe(); wake() }
    }

    private fun wanted(): Set<Long> {
        val now = System.currentTimeMillis()
        touched.entries.removeAll { now - it.value > 180_000 }
        return wants.values.flatten().toSet() + touched.keys
    }

    /** Each instrument's traded prices of the last few minutes ([com.optionslab.engine.TickPath]): the profit lock's best price and resting stops. */
    private val paths = ConcurrentHashMap<Long, com.optionslab.engine.TickPath>()

    /** The highest price [token] traded on the stream after [sinceMs] (epoch ms), or null when the stream has none kept. */
    fun highSince(token: Long, sinceMs: Long): Double? = paths[token]?.highSince(sinceMs)

    /** The lowest price [token] traded on the stream after [sinceMs] (epoch ms), or null when the stream has none kept. */
    fun lowSince(token: Long, sinceMs: Long): Double? = paths[token]?.lowSince(sinceMs)

    /** Where a resting SELL stop at [trigger] on [token], resting since [sinceMs], filled on the stream's ticks, or null. */
    fun sellStopFill(token: Long, sinceMs: Long, trigger: Double): Double? = paths[token]?.sellStopFill(sinceMs, trigger)

    /** Where a resting BUY stop (a short's) at [trigger] on [token], resting since [sinceMs], filled on the stream's ticks, or null. */
    fun buyStopFill(token: Long, sinceMs: Long, trigger: Double): Double? = paths[token]?.buyStopFill(sinceMs, trigger)

    /** The 1-minute candle of [token] that started at [startMs] built from the stream's ticks (open, high, low, close), or null. */
    fun minuteBar(token: Long, startMs: Long): DoubleArray? = paths[token]?.minute(startMs)

    /** 26 Oct: an index's token by its name ("NIFTY", "BANKNIFTY" ...), for a price read from this stream; null when unknown. Memory only. */
    fun indexToken(name: String): Long? = Broker.indexToken(name)

    /**
     * The last tick for [token] if it is at most [maxAgeMs] old (market closed = none fresh). 18 Oct: a tick that reached
     * the phone "in the future" (the phone's clock stepped back since, e.g. a network time correction) is not fresh either -
     * its age was negative, so it passed for fresh for as long as the step, however long the stream had been silent (as
     * [com.optionslab.ira.LiveQuote.pick] already judged it): the caller reads Zerodha's quote instead.
     */
    fun tick(token: Long, maxAgeMs: Long = 5_000): KiteTicks.Tick? =
        ticks[token]?.takeIf { System.currentTimeMillis() - it.at in 0..maxAgeMs }?.tick

    /**
     * B5-D: the last tick for [token] when it REACHED the phone at most [maxAgeMs] ago, however late the exchange stamped it - the
     * stop reads' fallback when no fresh quote answers ([Broker.stopQuotes]), exactly as [tick] was before late packets were stamped
     * back. Never a price to decide on while a fresh one can be had. Memory only.
     */
    fun arrivedTick(token: Long, maxAgeMs: Long = 5_000): KiteTicks.Tick? =
        ticks[token]?.takeIf { System.currentTimeMillis() - it.arrived in 0..maxAgeMs }?.tick

    /**
     * The last tick for [token] when it is fresh enough to DECIDE on ([com.optionslab.ira.LiveQuote.FRESH_MS], 2 s), else
     * null: the caller then reads Zerodha (the REST fallback).
     */
    fun freshTick(token: Long): KiteTicks.Tick? = ticks[token]?.takeIf {
        com.optionslab.ira.LiveQuote.pick(it.at, System.currentTimeMillis()) == com.optionslab.ira.LiveQuote.Source.STREAM
    }?.tick

    /** Every one of [tokens] has a fresh tick (2 s) while the stream is live; false for none. */
    fun allFresh(tokens: Collection<Long>): Boolean = _status.value == Status.LIVE &&
        com.optionslab.ira.LiveQuote.allFresh(tokens.map { ticks[it]?.at }, System.currentTimeMillis())

    // ---- running --------------------------------------------------------------------------

    @Volatile private var appContext: android.content.Context? = null

    /**
     * The application context, for the network check a drop records; 20 Nov 2026: and Android's network callback ([NetWatch]),
     * which opens the stream and the relay again at once when the phone's network changes.
     */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        // B5-D review: registered off the main thread (a binder call), never ahead of the start's positions-first work.
        val app = context.applicationContext
        scope.launch { runCatching { NetWatch.start(app) } }
    }

    /**
     * One connection: what its drop line needs. Every listener callback checks it is still the current connection,
     * so a socket left over from an earlier loop can never set the status, the subscriptions or the ticks.
     */
    private class Conn {
        val id = connIds.incrementAndGet()
        val opened = AtomicBoolean(false)
        val done = CompletableDeferred<Unit>()
        val end = AtomicReference<StreamHealth.Drop?>(null)
        val ticks = AtomicLong()
        @Volatile var openedAt = 0L
        @Volatile var lastFrame = 0L
        /** 25 Oct (app audit): the last frame that carried a price (a heartbeat does not), for [StreamHealth.pricesStalled]. */
        @Volatile var lastTick = 0L
        @Volatile var tokens = 0
        @Volatile var pausedSec: Long? = null
        @Volatile var network: String? = null
        /** Closed on purpose by the Live check to time the reconnect ([dropForCheck]): not a drop of the stream's health. */
        @Volatile var byCheck = false
        /** 20 Nov 2026: closed because the phone's network changed ([networkChanged]): opened again at once. */
        @Volatile var byNetwork = false
    }
    @Volatile private var conn: Conn? = null
    /** 25 Oct: the price-silence drops in a row ([StreamHealth.pricesStalled]); a connection that sees a price starts it again. */
    @Volatile private var priceSilentDrops = 0

    /**
     * Start or stop to match the app's state: a Zerodha session and market day hours. It runs in Paper mode too:
     * the paper account is priced from the same live ticks (read-only market data, the owner's own session).
     */
    fun ensure() {
        val loggedIn = Broker.loggedIn
        val hasKey = Broker.apiKey != null
        // NSE's day, and MCX's hours while something is held or working on MCX (9 Oct: to 23:30, 23:55 in US winter).
        // To five minutes past F&O's close (15:40 from 3 Aug 2026: 15:45; it was 15:45 before too, past the 15:30 close).
        val inHours = inHoursNow()
        if (loggedIn && hasKey && inHours) {
            // Battery, round 1: a stream nobody reads (the app off screen, no position or chart following instruments, no
            // quote asked for 3 minutes) kept the phone's radio busy all session for the indices' ticks alone. Unneeded for
            // [IDLE_STOP_MS], it is closed; the next quote, position or screen that needs live prices starts it again ([wake]).
            // Every price read falls back to Zerodha's quote call meanwhile - as it always does for an instrument not yet
            // streaming - so a stop, target or exit is checked at the same pace either way.
            if (needed()) { idleSince = 0L; idleStopped = false; start(); return }
            if (idleStopped) return
            val now = mono()                              // 20 Nov 2026: monotonic (a wall clock set back never holds or ends it)
            if (idleSince == 0L) idleSince = now
            if (now - idleSince < IDLE_STOP_MS) { start(); return }
            idleStopped = true
            stop("nothing needed live prices for ${IDLE_STOP_MS / 60_000} min (the app in the background, no open position, no quote asked); it starts again when something does")
            // A touch that landed between needed() above and idleStopped = true found it not yet stopped, so its wake()
            // did nothing: looked at again now, and started again if so.
            if (needed()) wake()
        } else stop(when { !loggedIn -> "no Zerodha login"; !hasKey -> "no API key"; else -> "outside market hours" })
    }

    /**
     * The stream's hours: NSE's day to five minutes past F&O's close, and MCX's while something is held or working there
     * ([McxMarket.watchDue]; it may read the MCX books once, so call it off the main thread and never on the tick path).
     */
    internal fun inHoursNow(): Boolean =
        runCatching { Market.isTradingDay() && Market.minuteNow() in (9 * 60)..maxOf(15 * 60 + 45, Market.foClose() + 5) }.getOrDefault(true) ||
            // 20 Nov 2026: NSE's special session (the Muhurat hour), from 15 minutes before it to 5 after it.
            runCatching { specialHours() }.getOrDefault(false) ||
            runCatching { McxMarket.watchDue() }.getOrDefault(false)

    /** 20 Nov 2026: within NSE's special session today ([Market.specialSession]), 15 minutes before it to 5 after. */
    private fun specialHours(): Boolean {
        val s = Market.specialSession() ?: return false
        val m = Market.minuteNow()
        return m in (s.open.hour * 60 + s.open.minute - 15)..(s.close.hour * 60 + s.close.minute + 5)
    }

    /** Battery, round 1: when the stream was first found unneeded (0: needed), and whether it was closed for that. */
    @Volatile private var idleSince = 0L
    @Volatile private var idleStopped = false
    /** Unneeded this long: closed until something needs live prices again. */
    private const val IDLE_STOP_MS = 5 * 60_000L

    /** Something reads live prices: the app on screen (or unknown), a part following instruments, a quote asked in the last 3 min. */
    internal fun needed(): Boolean {
        if (foreground() != false) return true
        if (wants.keys.any { it != "index" && it != OrderFlowLive.OWNER && it != TickZoneRecorder.OWNER && it != OiWallShadowHost.OWNER }) return true
        // The big-move recorder (on by default): kept on 09:15 to F&O's close, never in battery saver ([OrderFlowLive.keepStreamOn]).
        if (runCatching { OrderFlowLive.keepStreamOn() }.getOrDefault(false)) return true
        wanted()                                          // drops touches older than 3 minutes
        return touched.isNotEmpty()
    }

    /** Closed for being unneeded, and now something needs it: started again at once (off the caller's thread). */
    private fun wake() {
        if (!idleStopped) return
        idleStopped = false; idleSince = 0L
        scope.launch { runCatching { ensure() } }
    }

    /** Instruments the stream follows now (the diagnostics' battery line). */
    fun following(): Int = synchronized(subscribed) { subscribed.size }

    /**
     * Ticks the current connection has brought so far (0: none open). The Live check reads it twice to count ticks a second:
     * the connection's own counter, read only - nothing is added to the socket thread for it.
     */
    fun ticksNow(): Long = conn?.ticks?.get() ?: 0L

    /**
     * The Live check (18 Oct) times the reconnect: the open connection is closed as if it had dropped, and the loop opens a
     * new one after its first wait (0.5 s). Not counted as a drop ([Conn.byCheck]). False when no connection is open. The
     * check never calls this while a position or order is open, nor in its automatic 09:20 run (open positions come first).
     */
    @Synchronized   // with start() and stop(): a stop landing mid-way can never leave the status RETRYING with no loop
    fun dropForCheck(): Boolean {
        if (loop?.isActive != true) return false
        val c = conn ?: return false
        val ws = socket ?: return false
        if (!c.opened.get() || _status.value != Status.LIVE) return false
        c.byCheck = true
        c.end.compareAndSet(null, StreamHealth.Drop(forced = StreamHealth.Cause.CLOSED, message = "closed by the live check"))
        _status.value = Status.RETRYING
        ws.cancel()
        c.done.complete(Unit)
        return true
    }

    /**
     * 20 Nov 2026 (audit: the relay and the stream stayed dead for up to 45 s after the phone's network changed): Android says the
     * default network changed ([NetWatch]). The connection on the old network (open, or still being made) is closed now as a
     * network drop, and the loop opens a new one at once instead of after its silence watchdog or its next wait; a wait between
     * tries ends at once too. Nothing running: nothing to do ([ensure] starts it as before). Memory and the socket only (its
     * cancel never blocks); never on the tick path.
     */
    @Synchronized
    fun networkChanged(seenAt: Long = mono()): Boolean {
        if (loop?.isActive != true) return false
        val c = conn
        val ws = socket
        var dropped = false
        // B5-D: one that OPENED after the change was seen ([seenAt], the monotonic clock: it reconnected by itself, on the new
        // network, inside the debounce) is kept; any other is closed and opened again.
        val keep = c != null && keepOnChange(c.opened.get(), c.openedAt, seenAt)
        if (c != null && ws != null && !c.done.isCompleted && !keep) {
            c.byNetwork = true
            c.end.compareAndSet(null, StreamHealth.Drop(forced = StreamHealth.Cause.NETWORK_CHANGED, message = "the phone's network changed"))
            _status.value = Status.RETRYING
            ws.cancel()
            c.done.complete(Unit)
            dropped = true
        }
        kick.trySend(Unit)
        return dropped
    }

    /** B5-D: whether a connection is kept on a network change seen at [seenAt]: only one opened at or after it. Pure. */
    internal fun keepOnChange(opened: Boolean, openedAt: Long, seenAt: Long): Boolean = opened && openedAt >= seenAt

    /** B5-D: the stream's monotonic clock now (the network watch stamps a change with it). */
    internal fun monoNow(): Long = mono()

    /**
     * TEST ONLY: true keeps the stream from ever opening its socket (the fake Kite has no stream, and a connect the
     * test did not wait for would race its "no test may reach the internet" check). False in the app, always: the
     * setter throws unless BuildConfig.DEBUG, and only the test application sets it.
     */
    @Volatile internal var testOff = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test switch exists only in debug builds" }; field = v }

    /**
     * TEST ONLY (22 Nov 2026): the stream's address and client in place of Zerodha's (a fake server on this machine). Null in the app,
     * always: the setters throw unless BuildConfig.DEBUG.
     */
    @Volatile internal var urlForTest: String? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test address exists only in debug builds" }; field = v }
    @Volatile internal var clientForTest: OkHttpClient? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test client exists only in debug builds" }; field = v }
    /** TEST ONLY (22 Nov 2026): run in the loop right after a connection ended, before its drop is handled (a test stops the stream there). */
    @Volatile internal var afterEndForTest: (() -> Unit)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test hook exists only in debug builds" }; field = v }

    /** TEST ONLY (22 Nov 2026): run in the loop just before it takes the connection (a test stops and starts the stream there). */
    @Volatile internal var beforeConnectForTest: (() -> Unit)? = null
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test hook exists only in debug builds" }; field = v }

    /** TEST ONLY: the current connection's number (null: none). */
    internal fun connIdForTest(): Int? = conn?.id

    /** TEST ONLY: the loop running now (null: none). */
    internal fun loopForTest(): Job? = loop

    /** TEST ONLY: [start] as [ensure] calls it (the tests' fake stream runs in any hour). Throws unless BuildConfig.DEBUG. */
    internal fun startForTest() {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }
        start()
    }

    @Synchronized
    private fun start() {
        if (testOff) return
        if (loop?.isActive == true) return
        // 20 Oct, review: the armed arms' other indices (FINNIFTY, MIDCPNIFTY) kept too, not wiped until the next ORB pass.
        wants["index"] = INDEX_TOKENS + wants["index"].orEmpty()
        loop = scope.launch {
            val me = coroutineContext[Job]
            var wait = 0L
            var fails = 0
            var lastCause: StreamHealth.Cause? = null
            try { while (isActive) {
                if (!Broker.loggedIn) break
                // 27 Oct (battery audit): out of hours, no new connection. Before, only ensure(), a logout or the watch's normal
                // end stopped this loop: a watch cancelled or destroyed left it reconnecting all night, the app cached. MCX's
                // hours with anything held there are hours (McxMarket.watchDue): a held position's prices never stop here.
                if (wait > 0L && !inHoursNow()) {
                    runCatching { Diag.record(StreamHealth.AREA, "stream: not reconnecting outside market hours (nothing held on MCX)") }
                    break
                }
                // 22 Nov 2026 (B6 review): never after a stop() (it set OFF): the loop ends instead.
                if (!setStatus(if (wait == 0L) Status.CONNECTING else Status.RETRYING) { isActive }) break
                val token = Broker.streamToken() ?: break
                val key = Broker.apiKey ?: break
                val c = Conn().also { it.network = networkId() }
                // 20 Nov 2026: a kick left from before this try (a network change while it waited) is spent here.
                kick.tryReceive()
                val url = urlForTest?.let { "$it?api_key=$key&access_token=$token" } ?: "wss://${KitePin.WS_HOST}/?api_key=$key&access_token=$token"
                val cl = clientForTest ?: client
                beforeConnectForTest?.invoke()
                // conn is set before the socket is made: onOpen (on OkHttp's thread) checks `conn !== c`, and a fast open
                // landing before the assignment would otherwise close a healthy socket. 22 Nov 2026 (B6 second review): taken under stop()'s
                // lock and only while this loop is still the running one - a loop stop() cancelled (ensure() may stop and start again at
                // once) never takes the connection from the new loop, whose ticks would then all be dropped as another connection's. The
                // socket is only asked for here (OkHttp makes it on its own threads), so the lock is held for no I/O.
                val ws = synchronized(this@KiteStream) {
                    if (!isActive || loop !== me) null
                    else { conn = c; cl.newWebSocket(Request.Builder().url(url).build(), listener(c)).also { socket = it } }
                } ?: break
                try {
                    watch(c, ws)
                } finally {
                    // Cancelled by stop() (or anything else): this loop's socket is closed here, never left open. Before, a stop()
                    // landing while the socket was being made left it running for good - one of Kite's 3 connections per API key
                    // held by nobody, still feeding ticks and setting the status.
                    // 22 Nov 2026 (B6 review): one that never opened is cancelled, not closed - a close only queues a close frame
                    // behind the handshake, which then went on (stalled) until its own limit.
                    if (!c.done.isCompleted) { if (c.opened.get()) ws.close(1000, null) else ws.cancel() }
                    if (conn === c) conn = null
                    if (socket === ws) socket = null
                }
                afterEndForTest?.invoke()
                // ---- the drop, with its reason ----
                val opened = c.opened.get()
                val now = System.currentTimeMillis()
                // 20 Nov 2026: order updates may have been missed while it was down: the kept orders and positions are read afresh.
                runCatching { Broker.reconStale() }
                val seen = c.end.get() ?: StreamHealth.Drop(message = "ended")
                val d = seen.copy(
                    upMs = if (opened) mono() - c.openedAt else null, ticks = c.ticks.get(), tokens = c.tokens,
                    foreground = foreground(), networkChanged = networkId().let { n -> c.network != null && n != null && n != c.network },
                    watch = watchRunning(), pausedSec = c.pausedSec, tries = if (opened) 1 else fails + 1,
                )
                val cause = StreamHealth.cause(d)
                // 18 Oct: the Live check closed it on purpose ([dropForCheck]) - not counted as a drop anywhere (the order speed
                // card, the app's health, today's drops in the diary), no "dropped" banner; tried again at once.
                val check = c.byCheck
                // Round 2: a drop of a live connection is counted for the order speed card, and the local candles' runs are
                // broken (a minute that lost ticks is never decided on: the feed is read for it instead).
                if (opened && !check && !c.byNetwork) {
                    if (downSince == 0L) downSince = now
                    runCatching { OrderTiming.streamDropped() }
                    // The app's own health record: a reconnect of the live stream (a counter; it reconnects by itself).
                    runCatching { AppHealth.reconnect() }
                    runCatching { countReconnect() }
                    runCatching { com.optionslab.app.work.BatteryMeter.meter.count(com.optionslab.ira.LoopMeter.RECONNECTS) }
                }
                runCatching { candles.broke() }
                runCatching { sessionCandles.broke() }
                if (opened) fails = 0 else fails++
                // A connection that never opened is noted once per kind of failure and every tenth try (offline for an hour
                // is not 120 lines); every live connection lost is noted.
                if (check) runCatching { Diag.record(StreamHealth.AREA, "live check: closed the connection on purpose to time the reconnect") }
                // (Merge review: a close for a network change is planned - noted by [NetWatch], never counted or alerted as a drop.)
                else if (!c.byNetwork && (opened || fails == 1 || cause != lastCause || fails % 10 == 0))
                    runCatching { Diag.record(StreamHealth.AREA, StreamHealth.diary(d, listOf(token, key))) }
                lastCause = cause
                if (opened && !check && !c.byNetwork) {
                    val why = StreamHealth.plain(cause, if (cause == StreamHealth.Cause.PAUSED) d.pausedSec ?: d.silentSec else d.silentSec)
                    com.optionslab.app.work.Alerts.post("Live price stream dropped ($why); reconnecting", com.optionslab.app.work.Alerts.Kind.ERROR, throttle = true,
                        // 20 Oct: it reconnects by itself (each drop is also counted in the stream's own line): a warning, not an error.
                        severity = com.optionslab.ira.DiagErrors.Severity.WARNING)
                }
                if (!setStatus(Status.RETRYING) { isActive }) break
                // 27 Oct (battery audit): anything held - exactly as before; nothing held - a flapping network backs off further,
                // spread a little ([StreamHealth.waitFor]). Memory only (HeldNow reads no disk, vault or network).
                val heldNow = runCatching { HeldNow.any(mcxLive = true) }.getOrDefault(true)
                wait = if (check || c.byNetwork) StreamHealth.FIRST_WAIT_MS else StreamHealth.waitFor(wait, d.upMs, cause, heldNow, kotlin.random.Random.nextDouble(-1.0, 1.0))
                // 20 Nov 2026: the phone's network changed: the new connection is opened at once, on the new network (no wait).
                if (c.byNetwork) continue
                // A longer wait with nothing held ends at once when something becomes held (a 1 s look, memory only). 20 Nov 2026: on
                // the monotonic clock (a wall clock set back never stretches it), and ended at once by a network change ([kick]).
                if (heldNow || wait <= StreamHealth.NETWORK_WAIT_MS) pause(wait)
                else {
                    val until = mono() + wait
                    while (mono() < until && !runCatching { HeldNow.any(mcxLive = true) }.getOrDefault(true))
                        if (pause(minOf(1_000L, (until - mono()).coerceAtLeast(1L)))) break
                }
            } } finally {
                // 22 Nov 2026 (B6 review): OFF however this loop ends (a break, a stop()'s cancel, a failure) - unless a newer loop
                // has started meanwhile, whose status it never touches.
                synchronized(this@KiteStream) { if (loop == null || loop === me) _status.value = Status.OFF }
            }
        }
    }

    private fun listener(c: Conn) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (conn !== c) { webSocket.close(1000, null); return }
            val now = mono()
            c.openedAt = now; c.lastFrame = now; c.opened.set(true)
            // 20 Nov 2026: the kept orders and positions are read afresh (updates may have been missed while it was down).
            runCatching { Broker.reconStale() }
            // 22 Nov 2026 (B6 review): LIVE only while this is still the current connection - a stop() since the look above is never
            // followed by LIVE; the socket is closed instead. 22 Nov 2026 (B6 second review): the socket is stored (for [resubscribe]) only with it, so a
            // connection stopped meanwhile never leaves its socket behind.
            if (!setStatus(Status.LIVE) { (conn === c).also { if (it) socket = webSocket } }) {
                webSocket.close(1000, null)
                return
            }
            com.optionslab.app.work.Alerts.post("Live prices streaming from Zerodha", com.optionslab.app.work.Alerts.Kind.SUCCESS, throttle = true)
            synchronized(subscribed) { subscribed.clear() }
            resubscribe()
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (conn !== c) return
            c.lastFrame = mono()
            // 18 Oct (speed): the frame is read through a read-only view of okio's bytes (in place when okio holds them in one
            // piece, else the single copy it always cost). Each packet is then read in place, with no array of its own.
            onTicks(c, bytes.asByteBuffer())
        }
        override fun onMessage(webSocket: WebSocket, text: String) { if (conn === c) onText(text) }
        // Zerodha's close frame: its code and reason are kept, and the close is answered (left unanswered, the socket
        // ended later as a bare EOF and the code was lost).
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            c.end.compareAndSet(null, StreamHealth.Drop(code = code, reason = reason))
            webSocket.close(1000, null)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            c.end.compareAndSet(null, StreamHealth.Drop(code = code, reason = reason))
            c.done.complete(Unit)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            c.end.compareAndSet(null, StreamHealth.Drop(error = t.javaClass.simpleName, message = t.message, http = response?.code))
            runCatching { response?.close() }
            c.done.complete(Unit)
        }
    }

    /**
     * Waits for [c] to end, looking every 2 s. The stall watchdog: an open socket in market hours that has had no frame for
     * 10 s (Kite sends a heartbeat every second; the 20 s ping only finds a dead TCP connection, not a silent one) is closed
     * and opened again. A jump in this loop's own clock means Android froze the app (or the phone slept): the socket gets a
     * few seconds to show it survived before the same rule applies, and its drop is put down to the pause.
     */
    private suspend fun watch(c: Conn, ws: WebSocket) {
        var lastLook = mono()
        val askedAt = lastLook
        var graceUntil = 0L
        while (true) {
            if (withTimeoutOrNull(StreamHealth.STEP_MS) { c.done.await() } != null) return
            val now = mono()
            // 20 Nov 2026: a subscription the socket did not take is tried again (open and current only).
            if (subscribeOwed && c.opened.get() && conn === c) runCatching { resubscribe() }
            StreamHealth.paused(lastLook, now)?.let { sec -> c.pausedSec = sec; graceUntil = now + 5_000 }
            lastLook = now
            if (now < graceUntil) continue
            // 22 Nov 2026 (B6 review): a connection not OPEN [OPEN_TIMEOUT_MS] after it was asked for (a handshake or Zerodha's 101 that
            // stalled) is given up - cancelled, never closed: it never opened - and tried again. The watchdog below judges open ones only.
            if (openOverdue(c.opened.get(), askedAt, now)) {
                c.end.compareAndSet(null, StreamHealth.Drop(forced = StreamHealth.Cause.TIMEOUT, message = "did not open within ${OPEN_TIMEOUT_MS / 1000} s"))
                ws.cancel()
                c.done.complete(Unit)
                return
            }
            val marketOpen = runCatching { Market.isOpen() || Market.specialOpen() || McxMarket.isOpen() }.getOrDefault(false)
            if (StreamHealth.stalled(c.opened.get(), marketOpen, c.lastFrame, now)) {
                val sec = (now - c.lastFrame) / 1000
                c.end.compareAndSet(null, StreamHealth.Drop(forced = StreamHealth.Cause.SILENT, silentSec = sec))
                ws.cancel()
                c.done.complete(Unit)
                return
            }
            // 25 Oct (app audit): heartbeats but no price for 20 s in NSE's cash session: opened again (a fresh subscription). At most
            // [StreamHealth.MAX_PRICE_SILENT_DROPS] such drops in a row (a market halt or a holiday session prints no price at all:
            // no endless reconnects); a price seen starts the count again.
            val nseSession = runCatching { Market.isOpen() && StreamHealth.nseTicking(Market.minuteNow()) }.getOrDefault(false)
            // (25 Oct, merge review: only prices that kept coming past the first 20 s reset the count - one quote sent on subscribe
            // does not, or a stall would reconnect every 20 s all day and the cap of 3 would never hold.)
            if (StreamHealth.pricesResumed(c.lastTick, c.openedAt)) priceSilentDrops = 0
            if (priceSilentDrops < StreamHealth.MAX_PRICE_SILENT_DROPS && StreamHealth.pricesStalled(c.opened.get(), nseSession, c.tokens, c.lastTick, c.openedAt, now)) {
                priceSilentDrops += 1
                val sec = (now - maxOf(c.lastTick, c.openedAt)) / 1000
                c.end.compareAndSet(null, StreamHealth.Drop(forced = StreamHealth.Cause.SILENT, silentSec = sec, message = "heartbeats only, no prices"))
                ws.cancel()
                c.done.complete(Unit)
                return
            }
        }
    }

    /** Is the app on screen (Android does not hold back a visible app's network)? Null when it cannot be told. */
    private fun foreground(): Boolean? = runCatching {
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    }.getOrNull()

    /** The order watch (the foreground service that keeps the app running in the background) beat in the last 3 minutes. */
    private fun watchRunning(): Boolean? = runCatching {
        // Its service's own pulse (alive even while a check waits on the network), or a check finished in the last 3 minutes.
        val now = System.currentTimeMillis()
        now - com.optionslab.app.work.Heartbeat.alivePulse < com.optionslab.ira.WatchHealth.ALIVE_MS ||
            now - com.optionslab.app.work.Heartbeat.last() < 180_000
    }.getOrNull()

    /** Which network carries the phone's traffic now (an id only: Wi-Fi to mobile data changes it). */
    private fun networkId(): String? = runCatching {
        appContext?.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork?.toString()
    }.getOrNull()

    /** [why]: noted in the diary when a running stream is stopped (a stop is not a drop: nothing reconnects). */
    @Synchronized
    fun stop(why: String? = null) {
        val was = loop?.isActive == true
        loop?.cancel(); loop = null
        val c = conn
        conn = null
        // 22 Nov 2026 (B6 review): a socket that never opened is cancelled (a close would wait behind its handshake).
        socket?.let { if (c != null && c.opened.get()) it.close(1000, null) else it.cancel() }; socket = null
        synchronized(subscribed) { subscribed.clear() }
        synchronized(updates) { updates.clear() }
        _status.value = Status.OFF
        downSince = 0L
        runCatching { candles.broke() }
        runCatching { sessionCandles.broke() }
        if (was) runCatching { Diag.record(StreamHealth.AREA, "stopped: ${why ?: "the session or the day ended"}") }
    }

    /**
     * Bring the socket's subscriptions in line with what is wanted, full mode (depth and OI). At most Kite's 3000
     * instruments (the indices and open positions kept first), sent in messages of 500.
     *
     * Order flow (9 Oct): the flow reads the five-level book, the total buy / sell quantity, the last traded quantity and
     * time, the volume and the OI of its ~20 instruments ([OrderFlowLive]) - all in the full packet, so nothing changes here.
     *
     * Battery (round 9), kept full on purpose for every token: "quote" mode drops the depth and the OI. A contract not
     * held yet still needs them - a paper MARKET order fills at the best ask or bid from [Paper]'s stream quote (else the
     * last price), a news trade's liquidity check reads bid, ask and volume ([Broker.quotes]), and the chain's largest OI
     * reads the streamed OI - and an index's full packet is only 4 bytes more than its quote packet (the exchange time
     * the chart's live candle uses), at the same one tick a second, so it would save nothing worth the radio.
     */
    private fun resubscribe() {
        val ws = socket ?: return
        if (_status.value != Status.LIVE) return
        // 21 Oct, enhancement round 3 (idea 3): the paper book's open positions first too (open positions come first, paper or live).
        val first = (wants["index"].orEmpty() + wants["positions"].orEmpty() + wants["live-exits"].orEmpty() + wants[Paper.STREAM_OWNER].orEmpty())
        val want = StreamHealth.cap(wanted(), first)
        // 20 Nov 2026: what is sent first after a (re)connect: the held positions and live exits, then the paper book's, then the indices.
        val order = wants["positions"].orEmpty().toList() + wants["live-exits"].orEmpty() + wants[Paper.STREAM_OWNER].orEmpty() + wants["index"].orEmpty()
        sendSubscriptions(ws, want, order)
    }

    /**
     * Sends what [want] needs over [ws] (in [order] first). 20 Nov 2026 (audit: "a failed subscribe is marked as subscribed"): a
     * message the socket does not take (it is closing, or its queue is full: [WebSocket.send] says false) is not counted as done;
     * [subscribeOwed] has the watchdog try again on its next look. Memory and the socket's queue only.
     */
    private fun sendSubscriptions(ws: WebSocket, want: Set<Long>, order: List<Long>) {
        var owed = false
        synchronized(subscribed) {
            val add = want - subscribed
            val drop = subscribed - want
            if (drop.isNotEmpty()) {
                for (chunk in StreamHealth.chunks(drop)) {
                    if (ws.send(KiteTicks.unsubscribe(chunk))) { subscribed.removeAll(chunk.toSet()); chunk.forEach { ticks.remove(it) } }
                    else owed = true
                }
            }
            if (add.isNotEmpty()) {
                for (chunk in StreamHealth.chunks(StreamHealth.subscribeOrder(add, order))) {
                    if (ws.send(KiteTicks.subscribe(chunk)) && ws.send(KiteTicks.mode("full", chunk))) subscribed.addAll(chunk)
                    else owed = true
                }
            }
            conn?.tokens = subscribed.size
        }
        subscribeOwed = owed
    }

    /** TEST ONLY: [sendSubscriptions] over [ws] as the open socket would do it; returns what is counted as subscribed after it. */
    internal fun sendSubscriptionsForTest(ws: WebSocket, want: Set<Long>, order: List<Long>): Set<Long> {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }
        sendSubscriptions(ws, want, order)
        return synchronized(subscribed) { subscribed.toSet() }
    }

    /** TEST ONLY: a send is owed (a subscription the socket did not take). */
    internal fun subscribeOwedForTest(): Boolean = subscribeOwed

    private fun onTicks(c: Conn, message: java.nio.ByteBuffer) {
        val now = System.currentTimeMillis()
        val got = runCatching { KiteTicks.parse(message) }.getOrDefault(emptyList())
        if (got.isEmpty()) return
        c.lastTick = mono()
        c.ticks.addAndGet(got.size.toLong())
        record(got, now, conn = c.id)
    }

    /**
     * [got] reached the phone at [now]: kept as each instrument's last tick and in its path, then handed to the
     * event-driven checks ([FastPath.offer]: a map put and a wake, never the checks themselves on this socket thread),
     * and one exchange stamp in five seconds to the clock-offset figure ([OrderTiming.clock]).
     */
    private fun record(got: List<KiteTicks.Tick>, now: Long, stampLate: Boolean = true, conn: Int = 0) {
        var stamp: Long? = null
        val mono = if (stampLate) mono() else 0L
        for (t in got) {
            // 15 Nov ([com.optionslab.ira.PriceNow.newTrade]): the price path a resting stop fills from takes NEW trades only - a
            // thin option's packets repeat its last trade (of another day, before it trades) with every change of its book.
            val prevSeen = ticks[t.token]
            val prev = prevSeen?.tick
            val tt = t.lastTradeTime?.takeIf { it > 0 }
            // 20 Nov 2026 (audit: "a burst of late ticks is stamped now"): a packet that came late is kept with the time the exchange
            // stamped it (on the phone's clock), so it is not taken as fresh by a stop or a screen; any other packet: its arrival, as before.
            // Review: never a connection's first packet of an instrument - Zerodha's snapshot of it as it is NOW, which for a quiet option
            // carries the exchange's stamp of its last change, minutes ago.
            val first = prevSeen == null || prevSeen.conn != conn
            val at = if (stampLate && !first) runCatching { stamps.at((t.token and 0xFF).toInt(), now, mono, t.exchangeTime) }.getOrDefault(now) else now
            ticks[t.token] = Seen(t, at, conn, now)
            if (t.last > 0 && com.optionslab.ira.PriceNow.newTrade(t.last, tt, prev != null, prev?.last, prev?.lastTradeTime, now / 1000))
                paths.getOrPut(t.token) { com.optionslab.engine.TickPath() }.record(t.last, now)
            // The local candles by the exchange's own stamp (a tick without one - an index's quote packet - is not used); never from
            // a last price of another session.
            val ex = t.exchangeTime
            if (t.last > 0 && ex != null && ex > 0 && !com.optionslab.ira.PriceNow.anotherSession(tt, ex)) {
                candles.record(t.token, t.last, ex)
                // 28 Oct: IraThinker's flow's own Zerodha-only record of its four instruments (memory only).
                if (t.token in SESSION_TOKENS) sessionCandles.record(t.token, t.last, ex)
            }
            if (stamp == null) stamp = t.exchangeTime
        }
        lastTickAt = now
        // The first tick after a drop: how long prices were missing (the card's gap figure).
        val down = downSince
        if (down > 0L) { downSince = 0L; runCatching { OrderTiming.streamGap(now - down) } }
        bump(now)
        runCatching { FastPath.offer(got, now) }
        // The order flow's instruments: queued for its own thread (a set lookup here, nothing computed on this socket thread).
        runCatching { OrderFlowLive.offer(got, now) }
        val s = stamp
        if (s != null) runCatching { OrderTiming.clock(now, s) }
    }

    /**
     * Memory trim (audit, 14 Oct, item 7): the paths and the candles grew with every instrument streamed today and were never
     * let go. Dropped now: the tick paths of instruments nothing follows any more, and the candles of those quiet for two
     * hours ([com.optionslab.ira.LiveCandles.IDLE_SEC]). What anything follows now - the open positions, the live exits, the
     * armed arms' indices, every subscribed or recently quoted instrument - is never touched, however quiet. Memory only (a
     * background thread; the socket thread's next tick simply starts a new path). Returns (paths, candles) dropped.
     */
    fun pruneIdle(nowMs: Long = System.currentTimeMillis()): Pair<Int, Int> {
        val keep = followedNow()
        var paths = 0
        val it = this.paths.keys.iterator()
        while (it.hasNext()) {
            val t = it.next()
            // Looked at again just before it goes (review, 14 Oct): one followed this very moment keeps its path.
            if (t !in keep && !followsNow(t)) { it.remove(); paths++ }
        }
        val candles = runCatching { candles.prune(keep, nowMs / 1000) }.getOrDefault(0)
        return paths to candles
    }

    /** Everything followed now: every part's instruments, the ones just quoted, and every subscribed one (memory only). */
    fun followedNow(): Set<Long> {
        val keep = HashSet<Long>(MoveRecorder.INDEX_TOKENS.keys)
        for (v in wants.values) keep.addAll(v)
        keep.addAll(touched.keys)
        synchronized(subscribed) { keep.addAll(subscribed) }
        return keep
    }

    private fun followsNow(t: Long): Boolean = t in MoveRecorder.INDEX_TOKENS || touched.containsKey(t) || wants.values.any { t in it } ||
        synchronized(subscribed) { t in subscribed }

    /** The instruments with a tick path and with candles kept (the trim's figures; memory only). */
    fun keptCounts(): Pair<Int, Int> = paths.size to candles.instruments()

    /** The last tick for [token] as it arrived (the exchange's stamp and the phone's arrival time), however old. */
    fun seen(token: Long): Seen? = ticks[token]

    /** The instruments open positions and the armed arms' indices follow (what wakes the event-driven checks). */
    fun followed(): Set<Long> = wants["positions"].orEmpty() + wants["index"].orEmpty()

    /**
     * TEST ONLY: [got] as if the socket had just brought them (kept, pathed and handed on exactly as a live message's).
     * Throws unless BuildConfig.DEBUG (as [testOff]); no app code calls it.
     */
    internal fun feedForTest(got: List<KiteTicks.Tick>, atMs: Long = System.currentTimeMillis(), stampLate: Boolean = false, conn: Int = 0) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the test feed exists only in debug builds" }
        record(got, atMs, stampLate, conn)
    }

    /** TEST ONLY: the stream's status as if the socket were open (the fake Kite has no stream). Throws unless BuildConfig.DEBUG. */
    internal fun liveForTest(on: Boolean) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the test switch exists only in debug builds" }
        _status.value = if (on) Status.LIVE else Status.OFF
    }

    /**
     * TEST ONLY: forget every tick, price path and local candle of an earlier test. The ticks live in this object for the
     * whole run, and [Broker.quotes] answers from one up to 5 s old - so without this a price fed by the test before (a
     * fall to 145 through a stop) is the next test's price, whatever its fake quote says. Throws unless BuildConfig.DEBUG.
     */
    internal fun resetForTest() {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the test reset exists only in debug builds" }
        ticks.clear(); paths.clear(); candles.clear(); sessionCandles.clear(); touched.clear(); stamps.clear()
        reconnects = 0; reconnectDay = null
        urlForTest = null; clientForTest = null; afterEndForTest = null; beforeConnectForTest = null
        synchronized(updates) { updates.clear() }   // an earlier test's order update would answer for the same order id
        lastTickAt = 0L
        _status.value = Status.OFF
    }

    /** TEST ONLY: the instruments [owner] follows now (21 Oct). Throws unless BuildConfig.DEBUG. */
    internal fun wantsForTest(owner: String): Set<Long> {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }
        return wants[owner].orEmpty()
    }

    /** TEST ONLY: the instruments a price read keeps the stream awake for now (Battery, 14 Oct). Throws unless BuildConfig.DEBUG. */
    internal fun touchedForTest(): Set<Long> {
        check(com.optionslab.app.BuildConfig.DEBUG) { "test only" }
        return touched.keys.toSet()
    }

    /** TEST ONLY: a text frame as if the socket had brought it (an order update). Throws unless BuildConfig.DEBUG. */
    internal fun textForTest(text: String) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the test feed exists only in debug builds" }
        onText(text)
    }

    @Volatile private var lastError: Pair<Long, String>? = null

    /** Text frames: order updates and errors. An order update makes the account refresh sooner. */
    private fun onText(text: String) {
        val o = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return
        when (o.optString("type")) {
            // (An order changed at Zerodha - from Kite web too: Jarvis's kept account figures are read afresh.)
            // Its state is kept by order id first, so an order being waited on ([Broker.awaitOrder]) is answered from it at once.
            "order" -> {
                com.optionslab.ira.OrderUpdate.parse(text)?.let { u ->
                    keepUpdate(u)
                    // Its slippage, when it ended filled (a map remove; the measuring is done on its own thread).
                    if (u.terminal) runCatching { FillSlippage.ended(u.orderId, u.status, u.avgPrice, u.filled) }
                }
                // The kept order book and positions (the event-driven exits' looks) are read afresh after any change.
                runCatching { Broker.reconStale() }
                _orderEvents.value = System.currentTimeMillis()
                runCatching { com.optionslab.app.ira.IraAccount.invalidate() }
                // Smart scheduling (10 Oct): the workers waiting on an order update are woken (a set add and a wake; no I/O).
                runCatching { SmartWorkers.event(com.optionslab.ira.WorkerWake.Cause.ORDER_UPDATE) }
            }
            // e.g. a token Kite no longer accepts: kept in the diary (scrubbed), once a minute per message.
            "error" -> {
                val said = StreamHealth.scrub(o.optString("data"), listOf(Broker.streamToken(), Broker.apiKey))
                val now = System.currentTimeMillis()
                val last = lastError
                if (last == null || last.second != said || now - last.first > 60_000) {
                    lastError = now to said
                    runCatching { Diag.record(StreamHealth.AREA, "Zerodha said: $said") }
                }
            }
        }
    }

    /** "Live stream: LIVE · drops today 3 · last drop 09:58:45 (no data from Zerodha for 12 s)", from the diary [lines]. */
    fun statusLine(lines: List<String>): String =
        StreamHealth.statusLine(_status.value.name, lines, Market.now().toLocalDateTime())

    /**
     * The line for [day] (20 Oct): today's as above; an earlier day's (the diagnostics' catch-up the next morning) from that
     * day's own diary lines - before, the 02:53 retry of 19 Oct said "drops today 0" (20 Oct's) beside 19 Oct's 15 drops.
     */
    fun statusLine(lines: List<String>, day: java.time.LocalDate): String {
        val now = Market.now().toLocalDateTime()
        return if (day == now.toLocalDate()) statusLine(lines)
        else StreamHealth.statusLine("the day's record", lines, day.atTime(23, 59, 59), dayWord = "that day")
    }


    /** The latest order update per order id that Zerodha pushed on the stream (the newest [UPDATES_KEPT]). */
    private val updates = java.util.LinkedHashMap<String, com.optionslab.ira.OrderUpdate.Update>()
    private const val UPDATES_KEPT = 200

    private fun keepUpdate(u: com.optionslab.ira.OrderUpdate.Update) {
        synchronized(updates) {
            // A terminal state is never replaced by a late non-terminal one (updates can arrive out of order).
            val was = updates[u.orderId]
            if (was == null || !was.terminal || u.terminal) {
                updates.remove(u.orderId)
                updates[u.orderId] = u
                while (updates.size > UPDATES_KEPT) updates.remove(updates.keys.first())
            }
        }
    }

    /**
     * 26 Oct ("Erase everything", [PersonalStores]): Boss's order updates the stream pushed, and Zerodha's last error, forgotten
     * (memory only; nothing here is saved: the diary lines go through [Diag]). The socket itself is stopped by [Broker.forget].
     * The prices are public and stay. Never reads the vault.
     */
    fun wipe() {
        synchronized(updates) { updates.clear() }
        lastError = null
    }

    /** 26 Oct: whether an order update or Zerodha's error is held in memory. */
    internal fun holdsForErase(): Boolean = synchronized(updates) { updates.isNotEmpty() } || lastError != null

    /** Zerodha's latest pushed state of order [orderId], or null when the stream has said nothing of it. */
    fun orderUpdate(orderId: String): com.optionslab.ira.OrderUpdate.Update? = synchronized(updates) { updates[orderId] }

    private val _orderEvents = MutableStateFlow(0L)
    /** Bumped when Kite pushes an order update (placed, filled, cancelled, rejected). */
    val orderEvents: StateFlow<Long> = _orderEvents

    // ---- helpers --------------------------------------------------------------------------

    /** NIFTY 50, NIFTY BANK and INDIA VIX. */
    val INDEX_TOKENS = setOf(256265L, 260105L, 264969L)

    /**
     * A Zerodha position's P&L moved on from the last position-book reading by the
     * latest tick: the book's figure plus quantity x the price change since.
     */
    fun live(p: Broker.Position): Broker.Position = tick(p.token)?.let { moved(p, it.last) } ?: p

    /** [p] re-marked at [last]: P&L, M2M and unrealised move by quantity x the price change. */
    fun moved(p: Broker.Position, last: Double): Broker.Position {
        if (p.last <= 0 || last <= 0) return p
        val move = p.qty * (last - p.last) * p.multiplier
        return p.copy(last = last, pnl = p.pnl + move, m2m = p.m2m + move, unrealised = p.unrealised + move)
    }

    fun live(book: Broker.Positions): Broker.Positions = book.copy(net = book.net.map { live(it) }, day = book.day.map { live(it) })
}
