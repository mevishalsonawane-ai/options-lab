package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.IST
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Upstox
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection

/**
 * Zerodha Kite Connect - the broker, as the PC trading app uses it.
 *
 * CREDENTIALS. The API key, API secret, redirect URL and the day's access
 * token live only in the encrypted vault. The secret and the token are never
 * shown again after entry, never logged, and never put in an error message.
 *
 * THERE IS NO REFRESH TOKEN. Kite issues individual developers an access
 * token that dies at about 06:00 IST the next day; the PC logs in every
 * morning for the same reason. The app asks once per trading day.
 *
 * NOTHING IS SENT WITHOUT YOU. [placeOrder] exists, but the UI calls it only
 * after you have reviewed the legs, held the send button, and re-proved who
 * you are with your PIN or fingerprint. Real orders are also off until you
 * switch them on, and are refused outright on a compromised device.
 */
object Broker {
    private lateinit var app: Context

    private const val K_KEY = "kite.apiKey"
    private const val K_SECRET = "kite.apiSecret"
    private const val K_REDIRECT = "kite.redirect"
    private const val K_TOKEN = "kite.accessToken"
    private const val K_LOGIN_AT = "kite.loginAt"
    private const val K_USER = "kite.userName"
    private const val K_UID = "kite.userId"
    private const val K_SENT_DAY = "kite.sentDay"
    private const val K_SENT = "kite.sentCount"

    fun init(context: Context) { app = context.applicationContext }

    class NotLoggedIn : IOException("Log in to Zerodha for today first")
    class KiteError(val type: String, msg: String) : IOException(msg)

    // ---- credentials ------------------------------------------------------------

    val configured: Boolean get() = SecurePrefs.getString(K_KEY) != null && SecurePrefs.getString(K_SECRET) != null && SecurePrefs.getString(K_REDIRECT) != null
    val apiKey: String? get() = SecurePrefs.getString(K_KEY)
    val redirect: String? get() = SecurePrefs.getString(K_REDIRECT)
    val userName: String? get() = SecurePrefs.getString(K_USER)
    val userId: String? get() = SecurePrefs.getString(K_UID)

    /** Only the last four characters of the key, for recognising it. */
    fun maskedKey(): String = apiKey?.let { "••••" + it.takeLast(4) } ?: "not set"

    fun saveCredentials(apiKey: String, apiSecret: String, redirect: String) {
        require(apiKey.isNotBlank() && apiKey.all { it.isLetterOrDigit() }) { "The API key should be letters and digits only" }
        require(apiSecret.isNotBlank() && apiSecret.all { it.isLetterOrDigit() }) { "The API secret should be letters and digits only" }
        val r = runCatching { java.net.URI(redirect.trim()) }.getOrNull()
        require(r != null && (r.scheme == "https" || r.scheme == "http") && !r.host.isNullOrBlank()) {
            "The redirect URL must be the full address registered in your Kite app, e.g. https://example.com/"
        }
        SecurePrefs.putAll(mapOf(K_KEY to apiKey.trim(), K_SECRET to apiSecret.trim(), K_REDIRECT to redirect.trim(),
            K_TOKEN to null, K_LOGIN_AT to null))
    }

    fun forget() {
        SecurePrefs.putAll(mapOf(K_KEY to null, K_SECRET to null, K_REDIRECT to null, K_TOKEN to null,
            K_LOGIN_AT to null, K_USER to null, K_UID to null))
        File(app.filesDir, "kite_instruments.json").delete()
    }

    fun expiresAt(): ZonedDateTime? = SecurePrefs.getLong(K_LOGIN_AT, 0L).takeIf { it > 0 }
        ?.let { Kite.expiresAt(Instant.ofEpochMilli(it).atZone(IST)) }

    val loggedIn: Boolean get() {
        if (SecurePrefs.getString(K_TOKEN) == null) return false
        val exp = expiresAt() ?: return false
        return ZonedDateTime.now(IST).isBefore(exp)
    }

    private fun token(): String = if (loggedIn) SecurePrefs.getString(K_TOKEN)!! else throw NotLoggedIn()

    private fun dropSession() = SecurePrefs.putAll(mapOf(K_TOKEN to null, K_LOGIN_AT to null))

    // ---- HTTP ---------------------------------------------------------------------

    private suspend fun call(method: String, path: String, body: String? = null, auth: Boolean = true, raw: Boolean = false): Any {
        var attempt = 0
        while (true) {
            val c = URL(Kite.API + path).openConnection() as HttpsURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.setRequestProperty("X-Kite-Version", "3")
                c.setRequestProperty("User-Agent", "OptionsLab-Android")
                if (auth) c.setRequestProperty("Authorization", "token ${apiKey}:${token()}")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = c.responseCode
                if (code == 429 && attempt < 3) { attempt++; delay(1_000L * attempt); continue }
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                if (raw && code in 200..299) return text
                val json = runCatching { JSONObject(text) }.getOrNull() ?: throw IOException("Zerodha returned an unreadable reply ($code)")
                if (json.optString("status") != "success") {
                    val type = json.optString("error_type", "Error")
                    // Kite's own message names the problem (margin, price band,
                    // freeze quantity); it carries no credential, so it is shown.
                    val msg = json.optString("message", "request failed").take(300)
                    if (type == "TokenException") { dropSession(); throw KiteError(type, "Zerodha session ended: $msg") }
                    throw KiteError(type, msg)
                }
                return json.opt("data") ?: JSONObject.NULL
            } catch (e: KiteError) {
                throw e
            } catch (e: NotLoggedIn) {
                throw e
            } catch (_: java.net.UnknownHostException) {
                throw IOException("No connection to Zerodha")
            } catch (_: java.net.SocketTimeoutException) {
                throw IOException("Zerodha did not answer in time")
            } catch (_: IOException) {
                throw IOException("Could not reach Zerodha")
            } finally {
                c.disconnect()
            }
        }
    }

    // ---- login --------------------------------------------------------------------

    fun loginUrl(): String = Kite.loginUrl(apiKey ?: error("API key not set"))

    /** Exchange the request token for the day's access token (kite_login.exchange_token). */
    suspend fun completeLogin(requestToken: String): String {
        val key = apiKey ?: error("API key not set")
        val secret = SecurePrefs.getString(K_SECRET) ?: error("API secret not set")
        val data = call("POST", "/session/token",
            Kite.form(listOf("api_key" to key, "request_token" to requestToken, "checksum" to Kite.checksum(key, requestToken, secret))),
            auth = false) as JSONObject
        val token = data.optString("access_token")
        if (token.isBlank()) throw IOException("Zerodha returned no access token")
        SecurePrefs.putAll(mapOf(K_TOKEN to token, K_LOGIN_AT to System.currentTimeMillis(),
            K_USER to data.optString("user_name"), K_UID to data.optString("user_id")))
        return data.optString("user_name", "you")
    }

    suspend fun logout() {
        val key = apiKey
        val tok = SecurePrefs.getString(K_TOKEN)
        if (key != null && tok != null) runCatching {
            call("DELETE", "/session/token?api_key=${Kite.enc(key)}&access_token=${Kite.enc(tok)}")
        }
        dropSession()
    }

    // ---- account --------------------------------------------------------------------

    data class Funds(val available: Double, val used: Double, val net: Double)
    data class Position(val symbol: String, val product: String, val qty: Int, val avg: Double, val last: Double, val pnl: Double)
    data class OrderRow(val id: String, val symbol: String, val side: String, val qty: Int, val filled: Int, val price: Double,
                        val avg: Double, val status: String, val message: String, val placedAt: String)

    suspend fun funds(): Funds {
        val eq = (call("GET", "/user/margins") as JSONObject).getJSONObject("equity")
        val avail = eq.optJSONObject("available")
        val used = eq.optJSONObject("utilised")
        return Funds(avail?.optDouble("live_balance", avail.optDouble("cash", 0.0)) ?: 0.0, used?.optDouble("debits", 0.0) ?: 0.0, eq.optDouble("net", 0.0))
    }

    suspend fun positions(): List<Position> {
        val net = (call("GET", "/portfolio/positions") as JSONObject).optJSONArray("net") ?: JSONArray()
        return (0 until net.length()).map { net.getJSONObject(it) }.map {
            Position(it.optString("tradingsymbol"), it.optString("product"), it.optInt("quantity"), it.optDouble("average_price"),
                it.optDouble("last_price"), it.optDouble("pnl"))
        }
    }

    suspend fun orders(): List<OrderRow> {
        val arr = call("GET", "/orders") as JSONArray
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map(::orderRow).sortedByDescending { it.placedAt }
    }

    private fun orderRow(o: JSONObject) = OrderRow(o.optString("order_id"), o.optString("tradingsymbol"), o.optString("transaction_type"),
        o.optInt("quantity"), o.optInt("filled_quantity"), o.optDouble("price"), o.optDouble("average_price"),
        o.optString("status"), o.optString("status_message", ""), o.optString("order_timestamp", ""))

    // ---- market data ----------------------------------------------------------------

    private val INDEX = mapOf("NIFTY" to ("NSE:NIFTY 50" to 256265L), "BANKNIFTY" to ("NSE:NIFTY BANK" to 260105L),
        "INDIAVIX" to ("NSE:INDIA VIX" to 264969L))

    data class Quote(val last: Double, val bid: Double?, val ask: Double?, val open: Double)

    suspend fun quotes(keys: List<String>): Map<String, Quote> {
        if (keys.isEmpty()) return emptyMap()
        val data = call("GET", "/quote?" + keys.joinToString("&") { "i=" + Kite.enc(it) }) as JSONObject
        return keys.mapNotNull { k ->
            val q = data.optJSONObject(k) ?: return@mapNotNull null
            val depth = q.optJSONObject("depth")
            fun best(side: String) = depth?.optJSONArray(side)?.optJSONObject(0)?.optDouble("price")?.takeIf { it > 0 }
            k to Quote(q.optDouble("last_price"), best("buy"), best("sell"), q.optJSONObject("ohlc")?.optDouble("open") ?: 0.0)
        }.toMap()
    }

    private val KITE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")

    /** 1-minute candles with OI (needs a plan that includes historical data). */
    suspend fun minuteBars(token: Long, day: LocalDate): List<Upstox.Bar> {
        val q = "from=${Kite.enc("$day 09:15:00")}&to=${Kite.enc("$day 15:30:00")}&oi=1"
        val candles = (call("GET", "/instruments/historical/$token/minute?$q") as JSONObject).optJSONArray("candles") ?: JSONArray()
        return (0 until candles.length()).map { candles.getJSONArray(it) }.map { r ->
            val ts = r.getString(0)
            val epoch = runCatching { OffsetDateTime.parse(ts, KITE_TS) }.getOrElse { OffsetDateTime.parse(ts) }.toEpochSecond()
            Upstox.Bar(epoch, r.getDouble(1), r.getDouble(2), r.getDouble(3), r.getDouble(4), r.optLong(5), if (r.length() > 6) r.optLong(6) else 0L)
        }
    }

    suspend fun indexQuote(symbol: String): Market.Quote? {
        val (key, token) = INDEX[symbol] ?: return null
        val q = quotes(listOf(key))[key] ?: return null
        val spark = runCatching { minuteBars(token, Market.today()).map { it.close } }.getOrDefault(emptyList())
        val m = Market.now().let { it.hour * 60 + it.minute }
        return Market.Quote(symbol, q.last, q.open.takeIf { it > 0 } ?: q.last, spark.maxOrNull() ?: q.last, spark.minOrNull() ?: q.last, m,
            spark.ifEmpty { listOf(q.last) })
    }

    // ---- instruments ---------------------------------------------------------------------

    /** NIFTY and BANKNIFTY options from GET /instruments/NFO, cached for the day. */
    suspend fun instruments(): List<Kite.Instrument> {
        val f = File(app.filesDir, "kite_instruments.json")
        runCatching {
            val o = JSONObject(f.readText())
            if (o.getString("day") == Market.today().toString()) {
                val a = o.getJSONArray("i")
                return (0 until a.length()).map { a.getJSONArray(it) }.map {
                    Kite.Instrument(it.getLong(0), it.getString(1), it.getString(2), LocalDate.parse(it.getString(3)), it.getDouble(4),
                        it.getInt(5), if (it.getString(6) == "CE") Right.CE else Right.PE, it.getDouble(7))
                }
            }
        }
        val text = call("GET", "/instruments/NFO", raw = true) as String
        val list = Kite.parseInstruments(text.lineSequence(), setOf("NIFTY", "BANKNIFTY"))
        val a = JSONArray()
        list.forEach { a.put(JSONArray().put(it.token).put(it.tradingSymbol).put(it.name).put(it.expiry.toString()).put(it.strike).put(it.lotSize).put(it.right.name).put(it.tickSize)) }
        f.writeText(JSONObject().put("day", Market.today().toString()).put("i", a).toString())
        return list
    }

    fun find(list: List<Kite.Instrument>, underlying: String, expiry: LocalDate, strike: Double, right: Right) =
        list.firstOrNull { it.name == underlying && it.expiry == expiry && it.strike == strike && it.right == right }

    /**
     * Today's chain from Kite's own 1-minute candles, in the same shape the
     * Upstox path returns, so the ticket is priced by the same engine code.
     */
    suspend fun liveChain(underlying: String, near: Int = 14): Market.LiveChain {
        val today = Market.today()
        val all = instruments().filter { it.name == underlying && !it.expiry.isBefore(today) }
        if (all.isEmpty()) throw IOException("no listed $underlying options on Kite")
        val expiry = all.minOf { it.expiry }
        val chain = all.filter { it.expiry == expiry }
        val spot = indexQuote(underlying)?.last ?: throw IOException("no $underlying quote from Kite")
        val strikes = chain.map { it.strike }.distinct().sortedBy { kotlin.math.abs(it - spot) }.take(near * 2 + 1).toSet()
        val wanted = chain.filter { it.strike in strikes }
        val series = ArrayList<Series>()
        for (ins in wanted) {
            val c = Upstox.Contract(underlying, ins.expiry, ins.strike, ins.right, ins.lotSize, "kite:${ins.token}", ins.tradingSymbol)
            val bars = minuteBars(ins.token, today)
            if (bars.isNotEmpty()) series += Upstox.toSeries(c, bars, today, contracts = false)
            delay(340)   // Kite allows three historical calls a second
        }
        if (series.isEmpty()) throw IOException("Kite returned no bars for the $expiry chain")
        val contracts = wanted.map { Upstox.Contract(underlying, it.expiry, it.strike, it.right, it.lotSize, "kite:${it.token}", it.tradingSymbol) }
        return Market.LiveChain(expiry, series, wanted.first().lotSize, contracts, spot)
    }

    // ---- orders ------------------------------------------------------------------------

    fun sentToday(): Int = if (SecurePrefs.getString(K_SENT_DAY) == Market.today().toString()) SecurePrefs.getInt(K_SENT, 0) else 0

    private fun countSent() = SecurePrefs.putAll(mapOf(K_SENT_DAY to Market.today().toString(), K_SENT to sentToday() + 1))

    data class Fill(val orderId: String, val status: String, val avgPrice: Double, val filled: Int, val message: String)

    /** Sends ONE order. Callers must have passed Kite.refusals and the owner's confirmation. */
    suspend fun placeOrder(o: Kite.Order): String {
        val data = call("POST", "/orders/regular", o.formBody()) as JSONObject
        countSent()
        return data.getString("order_id")
    }

    /** Poll an order until it is terminal or [timeoutMs] passes. */
    suspend fun awaitOrder(orderId: String, timeoutMs: Long = 20_000): Fill {
        val end = System.currentTimeMillis() + timeoutMs
        var last: JSONObject? = null
        while (System.currentTimeMillis() < end) {
            val hist = call("GET", "/orders/$orderId") as JSONArray
            if (hist.length() > 0) last = hist.getJSONObject(hist.length() - 1)
            val st = last?.optString("status") ?: ""
            if (st in setOf("COMPLETE", "REJECTED", "CANCELLED")) break
            delay(1_000)
        }
        val o = last ?: return Fill(orderId, "UNKNOWN", 0.0, 0, "no order history yet")
        return Fill(orderId, o.optString("status"), o.optDouble("average_price", 0.0), o.optInt("filled_quantity"), o.optString("status_message", ""))
    }

    suspend fun cancel(orderId: String) { call("DELETE", "/orders/regular/${Kite.enc(orderId)}") }
}
