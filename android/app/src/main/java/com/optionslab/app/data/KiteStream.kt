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
 *  - Reconnects by itself (1 s, 2 s, 4 s … 30 s; at once again only after a connection
 *    that stayed up a minute); stops when the session ends.
 *  - A socket that stays open in market hours but goes silent for 10 s (Kite sends a
 *    heartbeat every second) is closed and opened again (the stall watchdog).
 *  - Each drop's reason goes to the diagnostics diary ([StreamHealth]): the close code,
 *    or the error's kind, how long it was up, ticks, tokens, foreground, network, watch.
 *  - The connection carries the access token, so it is pinned like the REST calls
 *    (its own trust-on-first-use CA set); the URL, API key and token are never logged.
 *
 * [Broker.quotes] answers from here when every asked instrument has a fresh tick,
 * which also makes the option chain, alarms, strategies and re-pricing faster.
 */
object KiteStream {
    enum class Status { OFF, CONNECTING, LIVE, RETRYING }

    data class Seen(val tick: KiteTicks.Tick, val at: Long)

    private val ticks = ConcurrentHashMap<Long, Seen>()
    private val wants = ConcurrentHashMap<String, Set<Long>>()
    private val subscribed = HashSet<Long>()

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status

    /** Bumped (at most every 500 ms) when prices arrive, so the screens can redraw from [tick]. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version
    @Volatile private var lastBump = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    @Volatile private var socket: WebSocket? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .sslSocketFactory(KitePin.streamSocketFactory, KitePin.streamTrust)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)      // a stream; Kite sends a heartbeat every second
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    // ---- what to follow -------------------------------------------------------------------

    /** Instruments one part of the app follows; replaces that part's earlier set. */
    fun want(owner: String, tokens: Collection<Long>) {
        val set = tokens.filter { it > 0 }.toSet()
        if (wants[owner] == set) return
        if (set.isEmpty()) wants.remove(owner) else wants[owner] = set
        resubscribe()
        if (set.isNotEmpty() && owner != "index") wake()
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

    /** Where a resting SELL stop at [trigger] on [token], resting since [sinceMs], filled on the stream's ticks, or null. */
    fun sellStopFill(token: Long, sinceMs: Long, trigger: Double): Double? = paths[token]?.sellStopFill(sinceMs, trigger)

    /** The 1-minute candle of [token] that started at [startMs] built from the stream's ticks (open, high, low, close), or null. */
    fun minuteBar(token: Long, startMs: Long): DoubleArray? = paths[token]?.minute(startMs)

    /** The last tick for [token] if it is at most [maxAgeMs] old (market closed = none fresh). */
    fun tick(token: Long, maxAgeMs: Long = 5_000): KiteTicks.Tick? =
        ticks[token]?.takeIf { System.currentTimeMillis() - it.at <= maxAgeMs }?.tick

    // ---- running --------------------------------------------------------------------------

    @Volatile private var appContext: android.content.Context? = null

    /** The application context, for the network check a drop records (no other use). */
    fun init(context: android.content.Context) { appContext = context.applicationContext }

    /**
     * One connection: what its drop line needs. Every listener callback checks it is still the current connection,
     * so a socket left over from an earlier loop can never set the status, the subscriptions or the ticks.
     */
    private class Conn {
        val opened = AtomicBoolean(false)
        val done = CompletableDeferred<Unit>()
        val end = AtomicReference<StreamHealth.Drop?>(null)
        val ticks = AtomicLong()
        @Volatile var openedAt = 0L
        @Volatile var lastFrame = 0L
        @Volatile var tokens = 0
        @Volatile var pausedSec: Long? = null
        @Volatile var network: String? = null
    }
    @Volatile private var conn: Conn? = null

    /**
     * Start or stop to match the app's state: a Zerodha session and market day hours. It runs in Paper mode too:
     * the paper account is priced from the same live ticks (read-only market data, the owner's own session).
     */
    fun ensure() {
        val loggedIn = Broker.loggedIn
        val hasKey = Broker.apiKey != null
        // NSE's day, and MCX's hours while something is held or working on MCX (9 Oct: to 23:30, 23:55 in US winter).
        val inHours = Market.isTradingDay() && Market.minuteNow() in (9 * 60)..(15 * 60 + 45) ||
            runCatching { McxMarket.watchDue() }.getOrDefault(false)
        if (loggedIn && hasKey && inHours) {
            // Battery, round 1: a stream nobody reads (the app off screen, no position or chart following instruments, no
            // quote asked for 3 minutes) kept the phone's radio busy all session for the indices' ticks alone. Unneeded for
            // [IDLE_STOP_MS], it is closed; the next quote, position or screen that needs live prices starts it again ([wake]).
            // Every price read falls back to Zerodha's quote call meanwhile - as it always does for an instrument not yet
            // streaming - so a stop, target or exit is checked at the same pace either way.
            if (needed()) { idleSince = 0L; idleStopped = false; start(); return }
            if (idleStopped) return
            val now = System.currentTimeMillis()
            if (idleSince == 0L) idleSince = now
            if (now - idleSince < IDLE_STOP_MS) { start(); return }
            idleStopped = true
            stop("nothing needed live prices for ${IDLE_STOP_MS / 60_000} min (the app in the background, no open position, no quote asked); it starts again when something does")
            // A touch that landed between needed() above and idleStopped = true found it not yet stopped, so its wake()
            // did nothing: looked at again now, and started again if so.
            if (needed()) wake()
        } else stop(when { !loggedIn -> "no Zerodha login"; !hasKey -> "no API key"; else -> "outside market hours" })
    }

    /** Battery, round 1: when the stream was first found unneeded (0: needed), and whether it was closed for that. */
    @Volatile private var idleSince = 0L
    @Volatile private var idleStopped = false
    /** Unneeded this long: closed until something needs live prices again. */
    private const val IDLE_STOP_MS = 5 * 60_000L

    /** Something reads live prices: the app on screen (or unknown), a part following instruments, a quote asked in the last 3 min. */
    private fun needed(): Boolean {
        if (foreground() != false) return true
        if (wants.keys.any { it != "index" }) return true
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
     * TEST ONLY: true keeps the stream from ever opening its socket (the fake Kite has no stream, and a connect the
     * test did not wait for would race its "no test may reach the internet" check). False in the app, always: the
     * setter throws unless BuildConfig.DEBUG, and only the test application sets it.
     */
    @Volatile internal var testOff = false
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test switch exists only in debug builds" }; field = v }

    @Synchronized
    private fun start() {
        if (testOff) return
        if (loop?.isActive == true) return
        wants["index"] = INDEX_TOKENS
        loop = scope.launch {
            var wait = 0L
            var fails = 0
            var lastCause: StreamHealth.Cause? = null
            while (isActive) {
                if (!Broker.loggedIn) break
                _status.value = if (wait == 0L) Status.CONNECTING else Status.RETRYING
                val token = Broker.streamToken() ?: break
                val key = Broker.apiKey ?: break
                val c = Conn().also { it.network = networkId() }
                val url = "wss://${KitePin.WS_HOST}/?api_key=$key&access_token=$token"
                // conn is set before the socket is made: onOpen (on OkHttp's thread) checks `conn !== c`, and a fast open
                // landing before the assignment would otherwise close a healthy socket.
                conn = c
                val ws = client.newWebSocket(Request.Builder().url(url).build(), listener(c))
                socket = ws
                try {
                    watch(c, ws)
                } finally {
                    // Cancelled by stop() (or anything else): this loop's socket is closed here, never left open. Before, a stop()
                    // landing while the socket was being made left it running for good - one of Kite's 3 connections per API key
                    // held by nobody, still feeding ticks and setting the status.
                    if (!c.done.isCompleted) ws.close(1000, null)
                    if (conn === c) conn = null
                    if (socket === ws) socket = null
                }
                // ---- the drop, with its reason ----
                val opened = c.opened.get()
                val now = System.currentTimeMillis()
                val seen = c.end.get() ?: StreamHealth.Drop(message = "ended")
                val d = seen.copy(
                    upMs = if (opened) now - c.openedAt else null, ticks = c.ticks.get(), tokens = c.tokens,
                    foreground = foreground(), networkChanged = networkId().let { n -> c.network != null && n != null && n != c.network },
                    watch = watchRunning(), pausedSec = c.pausedSec, tries = if (opened) 1 else fails + 1,
                )
                val cause = StreamHealth.cause(d)
                if (opened) fails = 0 else fails++
                // A connection that never opened is noted once per kind of failure and every tenth try (offline for an hour
                // is not 120 lines); every live connection lost is noted.
                if (opened || fails == 1 || cause != lastCause || fails % 10 == 0)
                    runCatching { Diag.record(StreamHealth.AREA, StreamHealth.diary(d, listOf(token, key))) }
                lastCause = cause
                if (opened) {
                    val why = StreamHealth.plain(cause, if (cause == StreamHealth.Cause.PAUSED) d.pausedSec ?: d.silentSec else d.silentSec)
                    com.optionslab.app.work.Alerts.post("Live price stream dropped ($why); reconnecting", com.optionslab.app.work.Alerts.Kind.ERROR, throttle = true)
                }
                _status.value = Status.RETRYING
                wait = StreamHealth.wait(wait, d.upMs, cause)
                delay(wait)
            }
            _status.value = Status.OFF
        }
    }

    private fun listener(c: Conn) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (conn !== c) { webSocket.close(1000, null); return }
            // An open landing before the loop has stored its socket: this is that socket (conn is c), so it is stored
            // here too, or [resubscribe] would find none and the stream would open with nothing subscribed.
            socket = webSocket
            val now = System.currentTimeMillis()
            c.openedAt = now; c.lastFrame = now; c.opened.set(true)
            _status.value = Status.LIVE
            com.optionslab.app.work.Alerts.post("Live prices streaming from Zerodha", com.optionslab.app.work.Alerts.Kind.SUCCESS, throttle = true)
            synchronized(subscribed) { subscribed.clear() }
            resubscribe()
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (conn !== c) return
            c.lastFrame = System.currentTimeMillis()
            onTicks(c, bytes.toByteArray())
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
        var lastLook = System.currentTimeMillis()
        var graceUntil = 0L
        while (true) {
            if (withTimeoutOrNull(StreamHealth.STEP_MS) { c.done.await() } != null) return
            val now = System.currentTimeMillis()
            StreamHealth.paused(lastLook, now)?.let { sec -> c.pausedSec = sec; graceUntil = now + 5_000 }
            lastLook = now
            if (now < graceUntil) continue
            val marketOpen = runCatching { Market.isOpen() || McxMarket.isOpen() }.getOrDefault(false)
            if (StreamHealth.stalled(c.opened.get(), marketOpen, c.lastFrame, now)) {
                val sec = (now - c.lastFrame) / 1000
                c.end.compareAndSet(null, StreamHealth.Drop(forced = StreamHealth.Cause.SILENT, silentSec = sec))
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
        conn = null
        socket?.close(1000, null); socket = null
        synchronized(subscribed) { subscribed.clear() }
        synchronized(updates) { updates.clear() }
        _status.value = Status.OFF
        if (was) runCatching { Diag.record(StreamHealth.AREA, "stopped: ${why ?: "the session or the day ended"}") }
    }

    /**
     * Bring the socket's subscriptions in line with what is wanted, full mode (depth and OI). At most Kite's 3000
     * instruments (the indices and open positions kept first), sent in messages of 500.
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
        val first = (wants["index"].orEmpty() + wants["positions"].orEmpty())
        val want = StreamHealth.cap(wanted(), first)
        synchronized(subscribed) {
            val add = want - subscribed
            val drop = subscribed - want
            if (drop.isNotEmpty()) {
                StreamHealth.chunks(drop).forEach { ws.send(KiteTicks.unsubscribe(it)) }
                subscribed.removeAll(drop); drop.forEach { ticks.remove(it) }
            }
            if (add.isNotEmpty()) {
                StreamHealth.chunks(add).forEach { ws.send(KiteTicks.subscribe(it)); ws.send(KiteTicks.mode("full", it)) }
                subscribed.addAll(add)
            }
            conn?.tokens = subscribed.size
        }
    }

    private fun onTicks(c: Conn, message: ByteArray) {
        val now = System.currentTimeMillis()
        val got = runCatching { KiteTicks.parse(message) }.getOrDefault(emptyList())
        if (got.isEmpty()) return
        c.ticks.addAndGet(got.size.toLong())
        record(got, now)
    }

    /**
     * [got] reached the phone at [now]: kept as each instrument's last tick and in its path, then handed to the
     * event-driven checks ([FastPath.offer]: a map put and a wake, never the checks themselves on this socket thread),
     * and one exchange stamp in five seconds to the clock-offset figure ([OrderTiming.clock]).
     */
    private fun record(got: List<KiteTicks.Tick>, now: Long) {
        var stamp: Long? = null
        for (t in got) {
            ticks[t.token] = Seen(t, now)
            if (t.last > 0) paths.getOrPut(t.token) { com.optionslab.engine.TickPath() }.record(t.last, now)
            if (stamp == null) stamp = t.exchangeTime
        }
        if (now - lastBump >= 500) { lastBump = now; _version.value = now }
        runCatching { FastPath.offer(got, now) }
        val s = stamp
        if (s != null) runCatching { OrderTiming.clock(now, s) }
    }

    /** The last tick for [token] as it arrived (the exchange's stamp and the phone's arrival time), however old. */
    fun seen(token: Long): Seen? = ticks[token]

    /** The instruments open positions and the armed arms' indices follow (what wakes the event-driven checks). */
    fun followed(): Set<Long> = wants["positions"].orEmpty() + wants["index"].orEmpty()

    /**
     * TEST ONLY: [got] as if the socket had just brought them (kept, pathed and handed on exactly as a live message's).
     * Throws unless BuildConfig.DEBUG (as [testOff]); no app code calls it.
     */
    internal fun feedForTest(got: List<KiteTicks.Tick>) {
        check(com.optionslab.app.BuildConfig.DEBUG) { "the test feed exists only in debug builds" }
        record(got, System.currentTimeMillis())
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
                com.optionslab.ira.OrderUpdate.parse(text)?.let { u -> keepUpdate(u) }
                _orderEvents.value = System.currentTimeMillis()
                runCatching { com.optionslab.app.ira.IraAccount.invalidate() }
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
