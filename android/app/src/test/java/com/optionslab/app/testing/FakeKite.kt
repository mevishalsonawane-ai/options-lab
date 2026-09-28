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
 * Credentials are made up: api key "testkey", token "test-token-not-real".
 */
class FakeKite : Closeable {
    // ---- what was sent -----------------------------------------------------------------

    /** [atNanos]: System.nanoTime() when the whole request had arrived (the latency tests measure up to it). */
    data class Req(val method: String, val path: String, val query: Map<String, String>, val form: Map<String, String>,
                   val body: String, val auth: String?, val atNanos: Long = System.nanoTime())

    val requests = CopyOnWriteArrayList<Req>()

    /** Requests that would change something at the broker (orders, GTTs, session). */
    val writes: List<Req> get() = requests.filter { it.method != "GET" && it.path != "/margins/basket" }
    val placed: List<Req> get() = requests.filter { it.method == "POST" && it.path == "/orders/regular" }

    // ---- state -----------------------------------------------------------------------------

    enum class Outcome { FILL, OPEN, PARTIAL, REJECT, FILL_AFTER_POLLS }
    enum class Reply { OK, DROP_AFTER_ACCEPT, ERROR_500_AFTER_ACCEPT, NETWORK_EXCEPTION, INPUT_EXCEPTION }

    data class Scenario(val reply: Reply = Reply.OK, val outcome: Outcome = Outcome.FILL, val partialQty: Int = 0,
                        val message: String = "", val polls: Int = 2, val fillPrice: Double? = null)

    class Order(
        val id: String, val symbol: String, val exchange: String, val side: String, var qty: Int, val product: String,
        var type: String, var price: Double, var trigger: Double, val tag: String, var status: String,
        var filled: Int = 0, var avg: Double = 0.0, var message: String = "", var pollsLeft: Int = 0, var fillPrice: Double = 0.0,
        val at: LocalDateTime = LocalDateTime.now(IST),
    ) {
        val working: Boolean get() = status in setOf("OPEN", "TRIGGER PENDING", "MODIFY PENDING")
    }

    data class Ins(val token: Long, val symbol: String, val name: String, val expiry: LocalDate, val strike: Double,
                   val right: String, val lot: Int, val tick: Double = 0.05)

    private val lock = Any()
    private val scenarios = ArrayDeque<Scenario>()
    val orders = LinkedHashMap<String, Order>()
    /** Net positions by "EXCHANGE:SYMBOL:PRODUCT" -> signed qty and average. */
    val positions = LinkedHashMap<String, Pair<Int, Double>>()
    val quotes = HashMap<String, Triple<Double, Double?, Double?>>()      // key -> last, bid, ask
    val instruments = ArrayList<Ins>()
    val gtts = LinkedHashMap<Long, JSONObject>()
    var cash = 500_000.0
    var marginPerOrder = 40_000.0
    private var seq = 250_926_000_000_000L
    private var gttSeq = 1_000L

    @Volatile var throttle = 0
    /** Keep connections open between requests, as Zerodha does (default: every reply closes its connection). */
    @Volatile var keepAlive = false
    @Volatile var sessionExpired = false
    val down = HashSet<String>()

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
        if (path in down) return error(503, "NetworkException", "Gateway timed out")
        if (path != "/session/token" && sessionExpired) return error(403, "TokenException", "Incorrect `api_key` or `access_token`.")
        val m = r.method!!
        return when {
            m == "POST" && path == "/session/token" ->
                ok(JSONObject().put("access_token", "test-token-not-real").put("user_name", "Test User").put("user_id", "AB1234"))
            m == "DELETE" && path == "/session/token" -> ok(true)
            m == "GET" && path == "/user/profile" -> ok(JSONObject().put("user_id", "AB1234").put("user_name", "Test User"))
            m == "GET" && path == "/user/margins" -> ok(funds())
            m == "GET" && path == "/portfolio/positions" -> ok(positionsJson())
            m == "GET" && path == "/portfolio/holdings" -> ok(JSONArray())
            m == "GET" && path == "/trades" -> ok(JSONArray())
            m == "GET" && path == "/orders" -> ok(JSONArray().apply { orders.values.forEach { put(orderJson(it)) } })
            m == "POST" && path == "/orders/regular" -> place(parseForm(body))
            m == "GET" && path.startsWith("/orders/") && path.count { it == '/' } == 2 -> history(path.substringAfterLast('/'))
            m == "DELETE" && path.startsWith("/orders/") -> cancel(path.substringAfterLast('/'))
            m == "PUT" && path.startsWith("/orders/") -> modify(path.substringAfterLast('/'), parseForm(body))
            m == "GET" && path == "/quote" -> quoteJson(url.queryParameterValues("i").filterNotNull())
            m == "GET" && path == "/instruments/NFO" -> MockResponse().setResponseCode(200).addHeader("Connection", "close").setBody(instrumentsCsv())
            m == "GET" && path.startsWith("/instruments/historical/") -> ok(JSONObject().put("candles", JSONArray()))
            m == "POST" && path == "/margins/basket" -> basket(body)
            m == "GET" && path == "/gtt/triggers" -> ok(JSONArray().apply { gtts.values.forEach { put(it) } })
            m == "POST" && path == "/gtt/triggers" -> placeGtt(parseForm(body))
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
                .put("multiplier", 1).put("instrument_token", instruments.firstOrNull { it.symbol == sym }?.token ?: 0))
        }
        return JSONObject().put("net", net).put("day", net)
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
            Outcome.OPEN -> Unit
        } else if (s.outcome == Outcome.REJECT) { o.status = "REJECTED"; o.message = s.message.ifEmpty { "RMS:Margin Exceeds" } }
        return when (s.reply) {
            Reply.DROP_AFTER_ACCEPT -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            Reply.ERROR_500_AFTER_ACCEPT -> error(500, "NetworkException", "Order status unknown. Please check the order book.")
            else -> ok(JSONObject().put("order_id", id))
        }
    }

    private fun history(id: String): MockResponse {
        val o = orders[id] ?: return error(400, "InputException", "Couldn't find that `order_id`.")
        if (o.pollsLeft > 0 && --o.pollsLeft == 0) { o.status = "COMPLETE"; o.filled = o.qty; o.avg = o.fillPrice; move(o, o.qty) }
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

    private fun quoteJson(keys: List<String>): MockResponse {
        val d = JSONObject()
        for (k in keys) {
            val (last, bid, ask) = quotes[k] ?: continue
            d.put(k, JSONObject().put("instrument_token", 0).put("last_price", last).put("volume", 1000).put("oi", 5000)
                .put("ohlc", JSONObject().put("open", last).put("high", last).put("low", last).put("close", last))
                .put("depth", JSONObject()
                    .put("buy", JSONArray().put(JSONObject().put("price", bid ?: 0).put("quantity", 75).put("orders", 1)))
                    .put("sell", JSONArray().put(JSONObject().put("price", ask ?: 0).put("quantity", 75).put("orders", 1)))))
        }
        return ok(d)
    }

    private fun instrumentsCsv(): String = buildString {
        append("instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange\n")
        for (i in instruments) append("${i.token},${i.token / 256},${i.symbol},\"${i.name}\",0,${i.expiry},${i.strike},${i.tick},${i.lot},${i.right},NFO-OPT,NFO\n")
    }

    private fun basket(body: String): MockResponse {
        val n = runCatching { JSONArray(body).length() }.getOrDefault(1)
        val total = marginPerOrder * n
        val orders = JSONArray().apply { repeat(n) { put(JSONObject().put("total", marginPerOrder).put("charges", JSONObject().put("total", 20.0))) } }
        return ok(JSONObject().put("initial", JSONObject().put("total", total)).put("final", JSONObject().put("total", total)).put("orders", orders))
    }

    private fun placeGtt(f: Map<String, String>): MockResponse {
        val id = ++gttSeq
        val cond = JSONObject(f["condition"] ?: "{}")
        gtts[id] = JSONObject().put("id", id).put("type", f["type"]).put("status", "active").put("created_at", LocalDateTime.now(IST).format(TS))
            .put("condition", cond).put("orders", JSONArray(f["orders"] ?: "[]"))
        return ok(JSONObject().put("trigger_id", id))
    }
}
