package com.optionslab.app.testing

import com.optionslab.app.data.Broker
import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.IST
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.URLDecoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TEST ONLY: a fake Zerodha Kite Connect v3 server on localhost (HTTPS, throwaway certificate),
 * wired into the app's real [Broker] HTTP code through `Broker.testEndpoint`. No real network.
 *
 * It keeps an order book, positions (moved by fills), funds, quotes, the NFO instruments dump and
 * GTTs, answers in Kite's JSON shapes ({"status":"success","data":...} / error_type + message),
 * and records every request ([requests]) so a test can assert exactly what was sent.
 *
 * Scenario controls: [nextPlace] (reject, partial, stays OPEN, fills after N polls, RMS margin
 * rejection, freeze-quantity InputException, NetworkException, lost reply by dropped connection or
 * by a 500 after the order was accepted), [throttle] (429 then success), [sessionExpired]
 * (TokenException), [down] (NetworkException for one path).
 *
 * 18 Oct (live end-to-end tests), closer to Kite: its rate limits ([orderRate], [quoteRate]: 429 past them), slow answers
 * ([slowMs]), connections dropped before any answer ([drop]), a gateway's HTML error page ([gatewayHtml]), Kite's
 * DataException / GeneralException / OrderException after a POST, a market order part-filled with the rest cancelled
 * ([Outcome.PARTIAL_THEN_CANCEL]), exchange-side moves of a working order ([trigger], [fillPart], [reject]), GTT modify, read and
 * trigger ([triggerGtt]), holdings, day positions apart from overnight ones ([overnight]), LTP and OHLC quotes, order margins,
 * and the instrument dumps of every exchange ([Ins.exchange]).
 *
 * Credentials are made up: api key "testkey", token "test-token-not-real".
 */
class FakeKite : Closeable {
    // ---- what was sent -----------------------------------------------------------------

    /** [atNanos]: System.nanoTime() when the whole request had arrived (the latency tests measure up to it). */
    data class Req(val method: String, val path: String, val query: Map<String, String>, val form: Map<String, String>,
                   val body: String, val auth: String?, val atNanos: Long = System.nanoTime())

    val requests = CopyOnWriteArrayList<Req>()

    /** Requests that would change something at the broker (orders, GTTs, session); the margin and contract-note reads are POSTs too. */
    val writes: List<Req> get() = requests.filter { it.method != "GET" && it.path != "/margins/basket" && it.path != "/charges/orders" }
    /** The virtual contract note asks (POST /charges/orders). */
    val contractNotes: List<Req> get() = requests.filter { it.method == "POST" && it.path == "/charges/orders" }
    val placed: List<Req> get() = requests.filter { it.method == "POST" && it.path == "/orders/regular" }

    // ---- state -----------------------------------------------------------------------------

    /** [PARTIAL_THEN_CANCEL]: [Scenario.partialQty] fills, the rest is cancelled by the exchange after [Scenario.polls] reads (a market order's protection). */
    enum class Outcome { FILL, OPEN, PARTIAL, REJECT, FILL_AFTER_POLLS, PARTIAL_THEN_CANCEL }
    /**
     * After the order was accepted (it is in the book): [DROP_AFTER_ACCEPT] no answer at all, [ERROR_500_AFTER_ACCEPT] Kite's 500
     * NetworkException, [DATA_EXCEPTION_AFTER_ACCEPT] its 502 DataException (the OMS's answer unreadable),
     * [GENERAL_EXCEPTION_AFTER_ACCEPT] its 500 GeneralException, [GATEWAY_HTML_AFTER_ACCEPT] a 502 HTML page from a gateway.
     * Nothing placed: [NETWORK_EXCEPTION] (503), [INPUT_EXCEPTION] (400), [ORDER_EXCEPTION] (500 OrderException, Kite refused it),
     * [GENERAL_EXCEPTION] (500 GeneralException).
     */
    enum class Reply { OK, DROP_AFTER_ACCEPT, ERROR_500_AFTER_ACCEPT, NETWORK_EXCEPTION, INPUT_EXCEPTION,
        DATA_EXCEPTION_AFTER_ACCEPT, GENERAL_EXCEPTION_AFTER_ACCEPT, GATEWAY_HTML_AFTER_ACCEPT, ORDER_EXCEPTION, GENERAL_EXCEPTION }

    data class Scenario(val reply: Reply = Reply.OK, val outcome: Outcome = Outcome.FILL, val partialQty: Int = 0,
                        val message: String = "", val polls: Int = 2, val fillPrice: Double? = null)

    class Order(
        val id: String, val symbol: String, val exchange: String, val side: String, var qty: Int, val product: String,
        var type: String, var price: Double, var trigger: Double, val tag: String, var status: String,
        var filled: Int = 0, var avg: Double = 0.0, var message: String = "", var pollsLeft: Int = 0, var fillPrice: Double = 0.0,
        val at: LocalDateTime = LocalDateTime.now(IST),
        /** The rest is cancelled (not filled) when [pollsLeft] runs out ([Outcome.PARTIAL_THEN_CANCEL]). */
        var cancelRest: Boolean = false,
    ) {
        val working: Boolean get() = status in setOf("OPEN", "TRIGGER PENDING", "MODIFY PENDING")
    }

    /** [exchange]: the dump it is listed in (GET /instruments/{exchange}); [right] "CE", "PE" or "FUT". */
    data class Ins(val token: Long, val symbol: String, val name: String, val expiry: LocalDate, val strike: Double,
                   val right: String, val lot: Int, val tick: Double = 0.05, val exchange: String = "NFO")

    /** A delivery holding (GET /portfolio/holdings). */
    data class Holding(val symbol: String, val qty: Int, val avg: Double, val last: Double, val exchange: String = "NSE", val isin: String = "INE000000000")

    private val lock = Any()
    private val scenarios = ArrayDeque<Scenario>()
    val orders = LinkedHashMap<String, Order>()
    /** Net positions by "EXCHANGE:SYMBOL:PRODUCT" -> signed qty and average. */
    val positions = LinkedHashMap<String, Pair<Int, Double>>()
    val quotes = HashMap<String, Triple<Double, Double?, Double?>>()      // key -> last, bid, ask
    val instruments = ArrayList<Ins>()
    val gtts = LinkedHashMap<Long, JSONObject>()
    val holdings = ArrayList<Holding>()
    /** Overnight quantities by "EXCHANGE:SYMBOL:PRODUCT" ([overnight]): the day book shows only what moved today. */
    val carry = LinkedHashMap<String, Int>()
    var cash = 500_000.0
    var marginPerOrder = 40_000.0
    /** What the contract note (POST /charges/orders) says each order cost, all in; [down] += "/charges/orders" fails it. */
    @Volatile var chargesPerOrder = 31.25
    /** GET /trades lists one trade for each order that filled (default: no trades at all, as before). */
    @Volatile var tradesFromFills = false
    private var seq = 250_926_000_000_000L
    private var gttSeq = 1_000L

    @Volatile var throttle = 0
    /** Keep connections open between requests, as Zerodha does (default: every reply closes its connection). */
    @Volatile var keepAlive = false
    @Volatile var sessionExpired = false
    val down = HashSet<String>()
    /** Paths that answer TokenException while the session itself is still good (one endpoint's glitch). */
    val tokenRefused = HashSet<String>()

    /** Paths never answered (16 Oct: Zerodha's positions read that waited 105 s). */
    val hang: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Paths whose connection is dropped once the request is read, with no answer and nothing done (a write is NOT accepted). */
    val drop: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Paths answered after this many ms (the answer is right, only late). */
    val slowMs: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()
    /** Paths answered by a gateway's HTML error page with this HTTP code (not Kite's JSON), nothing done. */
    val gatewayHtml: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Kite's rate limits, per rolling second (0: off, the default, so the older tests are unchanged): order placement, change and
     * cancel ([orderRate]; Kite allows 10 a second) and the quote reads ([quoteRate]: /quote, /quote/ltp, /quote/ohlc). A request
     * past the limit is answered 429 NetworkException "Too many requests" and does nothing; [limited] counts them.
     */
    @Volatile var orderRate = 0
    @Volatile var quoteRate = 0
    @Volatile var limited = 0
    private val orderTimes = ArrayDeque<Long>()
    private val quoteTimes = ArrayDeque<Long>()

    /** True when one more request of this kind now would pass [perSecond] in the last second (and then it is not counted). */
    private fun over(times: ArrayDeque<Long>, perSecond: Int): Boolean {
        if (perSecond <= 0) return false
        val now = System.nanoTime()
        while (times.isNotEmpty() && now - times.first() >= 1_000_000_000L) times.removeFirst()
        if (times.size >= perSecond) return true
        times.addLast(now)
        return false
    }

    fun nextPlace(s: Scenario) = synchronized(lock) { scenarios.addLast(s) }
    fun nextPlace(reply: Reply = Reply.OK, outcome: Outcome = Outcome.FILL, partialQty: Int = 0, message: String = "") =
        nextPlace(Scenario(reply, outcome, partialQty, message))

    fun quote(key: String, last: Double, bid: Double? = null, ask: Double? = null) = synchronized(lock) { quotes[key] = Triple(last, bid, ask) }
    fun position(symbol: String, qty: Int, avg: Double, product: String = "NRML", exchange: String = "NFO") =
        synchronized(lock) { positions["$exchange:$symbol:$product"] = qty to avg }

    /** Mark an order filled now (e.g. the stop of a protection triggered). */
    fun fill(id: String, price: Double? = null) = synchronized(lock) {
        val o = orders.getValue(id)
        o.status = "COMPLETE"; val add = o.qty - o.filled; o.filled = o.qty; o.avg = price ?: o.price.takeIf { it > 0 } ?: o.trigger
        move(o, add)
    }

    fun order(id: String): Order = synchronized(lock) { orders.getValue(id) }

    /** A position held since yesterday: in the net book, and not in the day book until it moves. */
    fun overnight(symbol: String, qty: Int, avg: Double, product: String = "NRML", exchange: String = "NFO") = synchronized(lock) {
        positions["$exchange:$symbol:$product"] = qty to avg; carry["$exchange:$symbol:$product"] = qty
    }

    /** [qty] more of working order [id] fills at [price] (a partial fill at the exchange); COMPLETE once all of it has. */
    fun fillPart(id: String, qty: Int, price: Double) = synchronized(lock) {
        val o = orders.getValue(id)
        check(o.working) { "order $id is ${o.status}" }
        val add = minOf(qty, o.qty - o.filled)
        o.avg = if (o.filled + add == 0) 0.0 else (o.avg * o.filled + price * add) / (o.filled + add)
        o.filled += add
        if (o.filled == o.qty) o.status = "COMPLETE" else if (o.status == "TRIGGER PENDING") o.status = "OPEN"
        move(o, add)
    }

    /**
     * A resting stop's trigger was hit at the exchange: an SL-M fills at [price] (default its trigger); an SL becomes a working
     * limit at its price and fills at once only when [price] (the market) is at or through that limit.
     */
    fun trigger(id: String, price: Double? = null) = synchronized(lock) {
        val o = orders.getValue(id)
        check(o.status == "TRIGGER PENDING") { "order $id is ${o.status}" }
        val px = price ?: o.trigger
        val through = o.type == "SL-M" || (if (o.side == "SELL") px >= o.price else px <= o.price)
        o.status = "OPEN"
        if (through) {
            val add = o.qty - o.filled
            o.avg = if (o.type == "SL-M") px else o.price
            o.filled = o.qty; o.status = "COMPLETE"; move(o, add)
        }
    }

    /** The exchange rejected (or cancelled) working order [id] after it was accepted: a stop outside the circuit, say. */
    fun reject(id: String, message: String = "RMS:Trigger price is outside the circuit limit", status: String = "REJECTED") = synchronized(lock) {
        val o = orders.getValue(id)
        check(o.working) { "order $id is ${o.status}" }
        o.status = status; o.message = message
    }

    /**
     * GTT [id] triggered (its leg [leg]: 0 the stop, 1 the target of a two-leg): Kite marks it "triggered" and places that leg's
     * LIMIT order, which fills when the quote is at or through it. The new order's id.
     */
    fun triggerGtt(id: Long, leg: Int = 0): String = synchronized(lock) {
        val g = gtts.getValue(id)
        val legs = g.getJSONArray("orders")
        val l = legs.getJSONObject(minOf(leg, legs.length() - 1))
        g.put("status", "triggered")
        val oid = (++seq).toString()
        val o = Order(oid, l.optString("tradingsymbol"), l.optString("exchange", "NFO"), l.optString("transaction_type"), l.optInt("quantity"),
            l.optString("product", "NRML"), "LIMIT", l.optDouble("price", 0.0), 0.0, "gtt", status = "OPEN")
        orders[oid] = o
        val q = quotes["${o.exchange}:${o.symbol}"]
        val marketable = q == null || (if (o.side == "BUY") o.price >= (q.third ?: q.first) else o.price <= (q.second ?: q.first))
        if (marketable) { o.status = "COMPLETE"; o.filled = o.qty; o.avg = o.price; move(o, o.qty) }
        g.put("orders", JSONArray().apply {
            for (i in 0 until legs.length()) put(JSONObject(legs.getJSONObject(i).toString()).apply {
                if (i == minOf(leg, legs.length() - 1)) put("result", JSONObject().put("order_result", JSONObject().put("order_id", oid).put("status", "success")))
            })
        })
        oid
    }

    /** No positions any more (the latency tests start every run flat). */
    fun flat() = synchronized(lock) { positions.clear() }

    // ---- server -------------------------------------------------------------------------------

    private val server = MockWebServer()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(lock) { handle(request) }
        }
        server.start()
        // A throwaway certificate for this run only; the app's real pinned-chain factory is bypassed by the seam.
        val cert = HeldCertificate.Builder().addSubjectAlternativeName(server.hostName).build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        Broker.testEndpoint = Broker.TestEndpoint(server.url("/").toString().trimEnd('/'), trust.sslSocketFactory())
    }

    override fun close() {
        Broker.testEndpoint = null
        server.shutdown()
    }

    /** A logged-in Kite session for today (made-up key and token), and Live mode with real orders allowed when [live]. */
    fun login(live: Boolean = true) {
        SecurePrefs.putAll(mapOf("kite.apiKey" to "testkey", "kite.accessToken" to "test-token-not-real",
            "kite.loginAt" to System.currentTimeMillis(), "kite.userId" to "AB1234", "kite.userName" to "Test User"))
        if (live) SecurePrefs.putAll(mapOf("k.mode" to "live", "k.allow" to true))
    }

    // ---- replies --------------------------------------------------------------------------------

    private fun closing(r: MockResponse): MockResponse = if (keepAlive) r else r.addHeader("Connection", "close")

    private fun ok(data: Any?): MockResponse = closing(MockResponse().setResponseCode(200))
        .addHeader("Content-Type", "application/json").setBody(JSONObject().put("status", "success").put("data", data ?: JSONObject.NULL).toString())

    private fun error(code: Int, type: String, message: String): MockResponse = closing(MockResponse().setResponseCode(code))
        .addHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("status", "error").put("error_type", type).put("message", message).put("data", JSONObject.NULL).toString())

    private fun parseForm(body: String): Map<String, String> =
        if (body.isBlank() || body.trimStart().startsWith("[") || body.trimStart().startsWith("{")) emptyMap()
        else body.split("&").filter { it.contains("=") }.associate {
            val (k, v) = it.split("=", limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private fun handle(r: RecordedRequest): MockResponse {
        val url = r.requestUrl!!
        val path = url.encodedPath
        val body = r.body.clone().readUtf8()
        val q = url.queryParameterNames.associateWith { url.queryParameter(it) ?: "" }
        requests += Req(r.method!!, path, q, parseForm(body), body, r.getHeader("Authorization"))
        if (throttle > 0) { throttle--; return error(429, "NetworkException", "Too many requests").addHeader("Retry-After", "1") }
        val orderCall = path.startsWith("/orders/") && r.method != "GET"
        if ((orderCall && over(orderTimes, orderRate)) || (path.startsWith("/quote") && over(quoteTimes, quoteRate))) {
            limited++
            return error(429, "NetworkException", "Too many requests").addHeader("Retry-After", "1")
        }
        if (path in drop) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        gatewayHtml[path]?.let { code ->
            return closing(MockResponse().setResponseCode(code)).addHeader("Content-Type", "text/html")
                .setBody("<html><head><title>$code Bad Gateway</title></head><body><center><h1>$code Bad Gateway</h1></center></body></html>")
        }
        val late = slowMs[path]
        if (late != null && late > 0) return handleKnown(r, url, path, body).setHeadersDelay(late, java.util.concurrent.TimeUnit.MILLISECONDS)
        return handleKnown(r, url, path, body)
    }

    private fun handleKnown(r: RecordedRequest, url: okhttp3.HttpUrl, path: String, body: String): MockResponse {
        if (path in down) return error(503, "NetworkException", "Gateway timed out")
        // No answer at all until the client gives up (released when the client closes the connection).
        if (path in hang) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        if (path in tokenRefused) return error(403, "TokenException", "Incorrect `api_key` or `access_token`.")
        if (path != "/session/token" && sessionExpired) return error(403, "TokenException", "Incorrect `api_key` or `access_token`.")
        val m = r.method!!
        return when {
            m == "POST" && path == "/session/token" ->
                ok(JSONObject().put("access_token", "test-token-not-real").put("user_name", "Test User").put("user_id", "AB1234"))
            m == "DELETE" && path == "/session/token" -> ok(true)
            m == "GET" && path == "/user/profile" -> ok(JSONObject().put("user_id", "AB1234").put("user_name", "Test User"))
            m == "GET" && path == "/user/margins" -> ok(funds())
            m == "GET" && path == "/portfolio/positions" -> ok(positionsJson())
            m == "GET" && path == "/portfolio/holdings" -> ok(holdingsJson())
            m == "GET" && path == "/trades" -> ok(tradesJson())
            m == "GET" && path == "/orders" -> ok(JSONArray().apply { orders.values.forEach { put(orderJson(it)) } })
            m == "POST" && path == "/orders/regular" -> place(parseForm(body))
            m == "GET" && path.startsWith("/orders/") && path.count { it == '/' } == 2 -> history(path.substringAfterLast('/'))
            m == "DELETE" && path.startsWith("/orders/") -> cancel(path.substringAfterLast('/'))
            m == "PUT" && path.startsWith("/orders/") -> modify(path.substringAfterLast('/'), parseForm(body))
            m == "GET" && path == "/quote" -> quoteJson(url.queryParameterValues("i").filterNotNull())
            m == "GET" && path == "/quote/ltp" -> quoteJson(url.queryParameterValues("i").filterNotNull(), mode = "ltp")
            m == "GET" && path == "/quote/ohlc" -> quoteJson(url.queryParameterValues("i").filterNotNull(), mode = "ohlc")
            // NFO's dump always; another exchange's (and the whole list) once a test lists something there, so the older tests,
            // which never had one, still see it missing.
            m == "GET" && path == "/instruments" && instruments.any { it.exchange != "NFO" } ->
                MockResponse().setResponseCode(200).addHeader("Connection", "close").setBody(instrumentsCsv(null))
            m == "GET" && path.startsWith("/instruments/") && path.count { it == '/' } == 2 &&
                path.substringAfterLast('/').let { ex -> ex == "NFO" || instruments.any { it.exchange == ex } } ->
                MockResponse().setResponseCode(200).addHeader("Connection", "close").setBody(instrumentsCsv(path.substringAfterLast('/')))
            m == "POST" && path == "/margins/orders" -> orderMargins(body)
            m == "GET" && path.startsWith("/instruments/historical/") -> ok(JSONObject().put("candles", JSONArray()))
            m == "POST" && path == "/margins/basket" -> basket(body)
            m == "POST" && path == "/charges/orders" -> contractNote(body)
            m == "GET" && path == "/gtt/triggers" -> ok(JSONArray().apply { gtts.values.forEach { put(it) } })
            m == "POST" && path == "/gtt/triggers" -> placeGtt(parseForm(body))
            m == "GET" && path.startsWith("/gtt/triggers/") -> {
                val g = path.substringAfterLast('/').toLongOrNull()?.let { gtts[it] }
                if (g != null) ok(g) else error(400, "InputException", "Invalid trigger ID.")
            }
            m == "PUT" && path.startsWith("/gtt/triggers/") -> modifyGtt(path.substringAfterLast('/').toLongOrNull(), parseForm(body))
            m == "DELETE" && path.startsWith("/gtt/triggers/") -> {
                val id = path.substringAfterLast('/').toLong()
                if (gtts.remove(id) != null) ok(JSONObject().put("trigger_id", id)) else error(400, "InputException", "Invalid trigger ID.")
            }
            else -> error(404, "GeneralException", "Route not found")
        }
    }

    private fun funds() = JSONObject().put("equity", JSONObject().put("enabled", true).put("net", cash)
        .put("available", JSONObject().put("cash", cash).put("live_balance", cash).put("opening_balance", cash).put("collateral", 0))
        .put("utilised", JSONObject().put("debits", 0).put("span", 0).put("exposure", 0).put("option_premium", 0)
            .put("m2m_realised", 0).put("m2m_unrealised", 0)))

    private fun positionsJson(): JSONObject {
        val net = JSONArray()
        for ((k, v) in positions) {
            val (ex, sym, prod) = k.split(":")
            val last = quotes["$ex:$sym"]?.first ?: v.second
            net.put(JSONObject().put("tradingsymbol", sym).put("exchange", ex).put("product", prod).put("quantity", v.first)
                .put("average_price", v.second).put("last_price", last).put("pnl", (last - v.second) * v.first)
                .put("m2m", (last - v.second) * v.first).put("unrealised", (last - v.second) * v.first).put("realised", 0)
                .put("multiplier", 1).put("instrument_token", instruments.firstOrNull { it.symbol == sym }?.token ?: 0)
                .put("overnight_quantity", carry[k] ?: 0))
        }
        // The day book: what moved today (an overnight position is in it only once it moved, by that much).
        val day = JSONArray()
        for (i in 0 until net.length()) {
            val row = net.getJSONObject(i)
            val k = "${row.getString("exchange")}:${row.getString("tradingsymbol")}:${row.getString("product")}"
            val moved = row.getInt("quantity") - (carry[k] ?: 0)
            if (carry[k] == null) day.put(row) else if (moved != 0) day.put(JSONObject(row.toString()).put("quantity", moved))
        }
        return JSONObject().put("net", net).put("day", day)
    }

    private fun holdingsJson() = JSONArray().apply {
        for (h in holdings) put(JSONObject().put("tradingsymbol", h.symbol).put("exchange", h.exchange).put("isin", h.isin).put("quantity", h.qty)
            .put("t1_quantity", 0).put("average_price", h.avg).put("last_price", h.last).put("close_price", h.last).put("pnl", (h.last - h.avg) * h.qty)
            .put("day_change", 0).put("day_change_percentage", 0).put("product", "CNC"))
    }

    private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun orderJson(o: Order) = JSONObject().put("order_id", o.id).put("exchange_order_id", "1100000${o.id.takeLast(8)}")
        .put("status", o.status).put("status_message", if (o.message.isEmpty()) JSONObject.NULL else o.message)
        .put("order_timestamp", o.at.format(TS)).put("variety", "regular").put("exchange", o.exchange)
        .put("tradingsymbol", o.symbol).put("order_type", o.type).put("transaction_type", o.side).put("validity", "DAY")
        .put("product", o.product).put("quantity", o.qty).put("price", o.price).put("trigger_price", o.trigger)
        .put("average_price", o.avg).put("filled_quantity", o.filled).put("pending_quantity", if (o.working) o.qty - o.filled else 0)
        .put("cancelled_quantity", if (o.status == "CANCELLED") o.qty - o.filled else 0).put("tag", o.tag)

    private fun move(o: Order, qty: Int) {
        if (qty == 0) return
        val k = "${o.exchange}:${o.symbol}:${o.product}"
        val (cur, avg) = positions[k] ?: (0 to 0.0)
        val signed = if (o.side == "BUY") qty else -qty
        val next = cur + signed
        positions[k] = next to (if (next == 0) 0.0 else if (cur == 0 || (cur > 0) == (signed > 0)) (avg * cur + o.avg * signed) / next else avg)
    }

    private fun place(f: Map<String, String>): MockResponse {
        val s = scenarios.removeFirstOrNull() ?: Scenario()
        when (s.reply) {
            Reply.NETWORK_EXCEPTION -> return error(503, "NetworkException", "Couldn't connect to the OMS. Please retry.")
            Reply.INPUT_EXCEPTION -> return error(400, "InputException", s.message.ifEmpty { "Quantity exceeds the freeze limit." })
            Reply.ORDER_EXCEPTION -> return error(500, "OrderException", s.message.ifEmpty { "Order could not be placed." })
            Reply.GENERAL_EXCEPTION -> return error(500, "GeneralException", s.message.ifEmpty { "Something went wrong. Please try again." })
            else -> Unit
        }
        val id = (++seq).toString()
        val type = f["order_type"] ?: "LIMIT"
        val o = Order(id, f["tradingsymbol"] ?: "", f["exchange"] ?: "NFO", f["transaction_type"] ?: "BUY", f["quantity"]?.toInt() ?: 0,
            f["product"] ?: "NRML", type, f["price"]?.toDouble() ?: 0.0, f["trigger_price"]?.toDouble() ?: 0.0, f["tag"] ?: "",
            status = if (type == "SL" || type == "SL-M") "TRIGGER PENDING" else "OPEN")
        val px = s.fillPrice ?: o.price.takeIf { it > 0 } ?: quotes["${o.exchange}:${o.symbol}"]?.first ?: 0.0
        o.fillPrice = px
        orders[id] = o
        // Stop orders rest until triggered (fill(id)), whatever the scenario says about fills.
        // A limit away from the quoted market rests (a target), as at the exchange; with no quote it fills.
        val q = quotes["${o.exchange}:${o.symbol}"]
        val marketable = type != "LIMIT" || q == null ||
            (if (o.side == "BUY") o.price >= (q.third ?: q.first) else o.price <= (q.second ?: q.first))
        if (o.status == "OPEN") when (s.outcome) {
            Outcome.FILL -> if (marketable) { o.status = "COMPLETE"; o.filled = o.qty; o.avg = px; move(o, o.qty) }
            Outcome.PARTIAL -> { o.filled = s.partialQty; o.avg = px; move(o, s.partialQty) }
            Outcome.REJECT -> { o.status = "REJECTED"; o.message = s.message.ifEmpty { "RMS:Margin Exceeds, Required:1,20,000.00, Available:40,000.00" } }
            Outcome.FILL_AFTER_POLLS -> o.pollsLeft = s.polls
            Outcome.PARTIAL_THEN_CANCEL -> { o.filled = s.partialQty; o.avg = px; move(o, s.partialQty); o.pollsLeft = maxOf(1, s.polls); o.cancelRest = true }
            Outcome.OPEN -> Unit
        } else if (s.outcome == Outcome.REJECT) { o.status = "REJECTED"; o.message = s.message.ifEmpty { "RMS:Margin Exceeds" } }
        return when (s.reply) {
            Reply.DROP_AFTER_ACCEPT -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            Reply.ERROR_500_AFTER_ACCEPT -> error(500, "NetworkException", "Order status unknown. Please check the order book.")
            Reply.DATA_EXCEPTION_AFTER_ACCEPT -> error(502, "DataException", "Unknown response from the OMS.")
            Reply.GENERAL_EXCEPTION_AFTER_ACCEPT -> error(500, "GeneralException", "Something went wrong. Please try again.")
            Reply.GATEWAY_HTML_AFTER_ACCEPT -> closing(MockResponse().setResponseCode(502)).addHeader("Content-Type", "text/html")
                .setBody("<html><body><h1>502 Bad Gateway</h1></body></html>")
            else -> ok(JSONObject().put("order_id", id))
        }
    }

    private fun history(id: String): MockResponse {
        val o = orders[id] ?: return error(400, "InputException", "Couldn't find that `order_id`.")
        if (o.pollsLeft > 0 && --o.pollsLeft == 0) {
            if (o.cancelRest) { o.status = "CANCELLED"; o.message = "Your order has been cancelled because of market protection." }
            else { o.status = "COMPLETE"; val add = o.qty - o.filled; o.filled = o.qty; o.avg = o.fillPrice; move(o, add) }
        }
        val first = orderJson(o).put("status", "OPEN PENDING").put("filled_quantity", 0).put("average_price", 0)
        return ok(JSONArray().put(first).put(orderJson(o)))
    }

    private fun cancel(id: String): MockResponse {
        val o = orders[id] ?: return error(400, "InputException", "Couldn't find that `order_id`.")
        if (!o.working) return error(400, "InputException", "Order cannot be cancelled as it is being processed. Try later.")
        o.status = "CANCELLED"
        return ok(JSONObject().put("order_id", id))
    }

    private fun modify(id: String, f: Map<String, String>): MockResponse {
        val o = orders[id] ?: return error(400, "InputException", "Couldn't find that `order_id`.")
        if (!o.working) return error(400, "InputException", "Order cannot be modified as it is being processed.")
        f["quantity"]?.let { o.qty = it.toInt() }
        f["order_type"]?.let { o.type = it }
        f["price"]?.let { o.price = it.toDouble() }
        f["trigger_price"]?.let { o.trigger = it.toDouble() }
        return ok(JSONObject().put("order_id", id))
    }

    /** [mode]: "full" (GET /quote: depth, OI, volume), "ohlc" (last price and OHLC) or "ltp" (last price only), as Kite answers each. */
    private fun quoteJson(keys: List<String>, mode: String = "full"): MockResponse {
        val d = JSONObject()
        for (k in keys) {
            val (last, bid, ask) = quotes[k] ?: continue
            val token = instruments.firstOrNull { "${it.exchange}:${it.symbol}" == k }?.token ?: 0
            if (mode == "ltp") { d.put(k, JSONObject().put("instrument_token", token).put("last_price", last)); continue }
            if (mode == "ohlc") {
                d.put(k, JSONObject().put("instrument_token", token).put("last_price", last)
                    .put("ohlc", JSONObject().put("open", last).put("high", last).put("low", last).put("close", prevClose[k] ?: last)))
                continue
            }
            d.put(k, JSONObject().put("instrument_token", 0).put("last_price", last).put("volume", 1000).put("oi", 5000)
                .put("ohlc", JSONObject().put("open", last).put("high", last).put("low", last).put("close", last))
                .put("depth", JSONObject()
                    .put("buy", JSONArray().put(JSONObject().put("price", bid ?: 0).put("quantity", 75).put("orders", 1)))
                    .put("sell", JSONArray().put(JSONObject().put("price", ask ?: 0).put("quantity", 75).put("orders", 1)))))
        }
        return ok(d)
    }

    /** The dump of [exchange] (GET /instruments/{exchange}), or of every exchange (null: GET /instruments). */
    private fun instrumentsCsv(exchange: String? = "NFO"): String = buildString {
        append("instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange\n")
        for (i in instruments) {
            if (exchange != null && i.exchange != exchange) continue
            val segment = "${i.exchange}-${if (i.right == "FUT") "FUT" else "OPT"}"
            append("${i.token},${i.token / 256},${i.symbol},\"${i.name}\",0,${i.expiry},${i.strike},${i.tick},${i.lot},${i.right},$segment,${i.exchange}\n")
        }
    }

    /** Previous closes for the OHLC quote ("EXCHANGE:SYMBOL" -> close), else the last price. */
    val prevClose = HashMap<String, Double>()

    /** POST /margins/orders: each order's margin ([marginPerOrder]) with Kite's fields. */
    private fun orderMargins(body: String): MockResponse {
        val asked = runCatching { JSONArray(body) }.getOrNull() ?: return error(400, "InputException", "Invalid orders.")
        return ok(JSONArray().apply {
            for (i in 0 until asked.length()) {
                val o = asked.getJSONObject(i)
                put(JSONObject().put("type", "equity").put("tradingsymbol", o.optString("tradingsymbol")).put("exchange", o.optString("exchange"))
                    .put("span", marginPerOrder * 0.7).put("exposure", marginPerOrder * 0.3).put("option_premium", 0).put("additional", 0)
                    .put("bo", 0).put("cash", 0).put("var", 0).put("pnl", JSONObject().put("realised", 0).put("unrealised", 0))
                    .put("leverage", 1).put("charges", JSONObject().put("total", 20.0)).put("total", marginPerOrder))
            }
        })
    }

    /** PUT /gtt/triggers/{id}: an active GTT's condition and orders replaced (a triggered or deleted one is refused). */
    private fun modifyGtt(id: Long?, f: Map<String, String>): MockResponse {
        val g = id?.let { gtts[it] } ?: return error(400, "InputException", "Invalid trigger ID.")
        if (g.optString("status") != "active") return error(400, "InputException", "Trigger is not active.")
        f["type"]?.let { g.put("type", it) }
        f["condition"]?.let { g.put("condition", JSONObject(it)) }
        f["orders"]?.let { g.put("orders", JSONArray(it)) }
        return ok(JSONObject().put("trigger_id", id))
    }

    private fun basket(body: String): MockResponse {
        val n = runCatching { JSONArray(body).length() }.getOrDefault(1)
        val total = marginPerOrder * n
        val orders = JSONArray().apply { repeat(n) { put(JSONObject().put("total", marginPerOrder).put("charges", JSONObject().put("total", 20.0))) } }
        return ok(JSONObject().put("initial", JSONObject().put("total", total)).put("final", JSONObject().put("total", total)).put("orders", orders))
    }

    private fun tradesJson(): JSONArray = JSONArray().apply {
        if (tradesFromFills) orders.values.filter { it.filled > 0 }.forEach { o ->
            put(JSONObject().put("trade_id", "T${o.id}").put("order_id", o.id).put("tradingsymbol", o.symbol).put("exchange", o.exchange)
                .put("transaction_type", o.side).put("quantity", o.filled).put("average_price", o.avg).put("product", o.product)
                .put("fill_timestamp", o.at.format(TS)))
        }
    }

    /** Kite's virtual contract note: each order asked about echoed back with its charges ([chargesPerOrder] in all). */
    private fun contractNote(body: String): MockResponse {
        val asked = runCatching { JSONArray(body) }.getOrNull() ?: return error(400, "InputException", "Invalid orders.")
        val out = JSONArray()
        for (i in 0 until asked.length()) {
            val o = asked.getJSONObject(i)
            val gst = chargesPerOrder * 0.1
            out.put(JSONObject().put("order_id", o.optString("order_id")).put("exchange", o.optString("exchange"))
                .put("tradingsymbol", o.optString("tradingsymbol")).put("transaction_type", o.optString("transaction_type"))
                .put("variety", o.optString("variety")).put("product", o.optString("product")).put("order_type", o.optString("order_type"))
                .put("quantity", o.optInt("quantity")).put("price", o.optDouble("average_price", 0.0))
                .put("charges", JSONObject().put("transaction_tax", 0).put("transaction_tax_type", "stt")
                    .put("exchange_turnover_charge", 0).put("sebi_turnover_charge", 0).put("brokerage", chargesPerOrder - gst)
                    .put("stamp_duty", 0).put("gst", JSONObject().put("igst", gst).put("cgst", 0).put("sgst", 0).put("total", gst))
                    .put("total", chargesPerOrder)))
        }
        return ok(out)
    }

    private fun placeGtt(f: Map<String, String>): MockResponse {
        val id = ++gttSeq
        val cond = JSONObject(f["condition"] ?: "{}")
        gtts[id] = JSONObject().put("id", id).put("type", f["type"]).put("status", "active").put("created_at", LocalDateTime.now(IST).format(TS))
            .put("condition", cond).put("orders", JSONArray(f["orders"] ?: "[]"))
        return ok(JSONObject().put("trigger_id", id))
    }
}
