package com.optionslab.app.data

import com.optionslab.app.security.KitePin
import com.optionslab.engine.KiteTicks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Zerodha's live price stream: one WebSocket to ws.kite.trade that pushes every
 * trade of the instruments the app cares about, instead of the app asking for
 * quotes over and over.
 *
 *  - Runs only in Live mode with a Zerodha session (Paper stays on the Upstox feed).
 *  - What it follows is the union of what each part of the app wants: the three
 *    indices, open positions, the option chain on screen, price alarms, and any
 *    instrument a quote was just asked for.
 *  - Reconnects by itself (1 s, 2 s, 4 s … 30 s); stops when the session ends.
 *  - The connection carries the access token, so it is pinned like the REST calls
 *    (its own trust-on-first-use CA set) and nothing about it is ever logged.
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
    }

    private val touched = ConcurrentHashMap<Long, Long>()

    /** Instruments a quote was just asked for: followed for the next three minutes. */
    fun touch(tokens: Collection<Long>) {
        val now = System.currentTimeMillis()
        var added = false
        tokens.filter { it > 0 }.forEach { if (touched.put(it, now) == null) added = true }
        if (added) resubscribe()
    }

    private fun wanted(): Set<Long> {
        val now = System.currentTimeMillis()
        touched.entries.removeAll { now - it.value > 180_000 }
        return wants.values.flatten().toSet() + touched.keys
    }

    /** The last tick for [token] if it is at most [maxAgeMs] old (market closed = none fresh). */
    fun tick(token: Long, maxAgeMs: Long = 5_000): KiteTicks.Tick? =
        ticks[token]?.takeIf { System.currentTimeMillis() - it.at <= maxAgeMs }?.tick

    // ---- running --------------------------------------------------------------------------

    /** Start or stop to match the app's state: Live mode, a Zerodha session, market day hours. */
    fun ensure() {
        val should = runCatching { AppSettings.load().live }.getOrDefault(false) && Broker.loggedIn && Broker.apiKey != null &&
            Market.isTradingDay() && Market.minuteNow() in (9 * 60)..(15 * 60 + 45)
        if (should) start() else stop()
    }

    @Synchronized
    private fun start() {
        if (loop?.isActive == true) return
        wants["index"] = INDEX_TOKENS
        loop = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                if (!Broker.loggedIn || !runCatching { AppSettings.load().live }.getOrDefault(false)) break
                _status.value = if (backoff == 1_000L) Status.CONNECTING else Status.RETRYING
                val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                val opened = java.util.concurrent.atomic.AtomicBoolean(false)
                val token = Broker.streamToken() ?: break
                val url = "wss://${KitePin.WS_HOST}/?api_key=${Broker.apiKey}&access_token=$token"
                socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.set(true); _status.value = Status.LIVE
                        com.optionslab.app.work.Alerts.post("Live prices streaming from Zerodha", com.optionslab.app.work.Alerts.Kind.SUCCESS, throttle = true)
                        synchronized(subscribed) { subscribed.clear() }
                        resubscribe()
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onTicks(bytes.toByteArray())
                    override fun onMessage(webSocket: WebSocket, text: String) = onText(text)
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
                })
                closed.await()
                socket = null
                if (opened.get()) {
                    backoff = 1_000L
                    com.optionslab.app.work.Alerts.post("Live price stream dropped; reconnecting", com.optionslab.app.work.Alerts.Kind.ERROR, throttle = true)
                }
                _status.value = Status.RETRYING
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
            _status.value = Status.OFF
        }
    }

    @Synchronized
    fun stop() {
        loop?.cancel(); loop = null
        socket?.close(1000, null); socket = null
        synchronized(subscribed) { subscribed.clear() }
        _status.value = Status.OFF
    }

    /** Bring the socket's subscriptions in line with what is wanted, full mode (depth and OI). */
    private fun resubscribe() {
        val ws = socket ?: return
        if (_status.value != Status.LIVE) return
        val want = wanted()
        synchronized(subscribed) {
            val add = want - subscribed
            val drop = subscribed - want
            if (drop.isNotEmpty()) { ws.send(KiteTicks.unsubscribe(drop)); subscribed.removeAll(drop); drop.forEach { ticks.remove(it) } }
            if (add.isNotEmpty()) { ws.send(KiteTicks.subscribe(add)); ws.send(KiteTicks.mode("full", add)); subscribed.addAll(add) }
        }
    }

    private fun onTicks(message: ByteArray) {
        val now = System.currentTimeMillis()
        val got = runCatching { KiteTicks.parse(message) }.getOrDefault(emptyList())
        if (got.isEmpty()) return
        got.forEach { ticks[it.token] = Seen(it, now) }
        if (now - lastBump >= 500) { lastBump = now; _version.value = now }
    }

    /** Text frames: order updates and errors. An order update makes the account refresh sooner. */
    private fun onText(text: String) {
        val o = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return
        when (o.optString("type")) {
            "order" -> _orderEvents.value = System.currentTimeMillis()
            "error" -> Unit    // e.g. a token Kite no longer accepts; the next REST call surfaces it plainly
        }
    }

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
