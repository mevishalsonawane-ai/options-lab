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
 * CREDENTIALS. The API key, API secret and the day's access
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
    private const val K_SECRET = "kite.apiSecret"            // legacy: plain inside the vault; migrated on next login
    private const val K_SEALED = "kite.apiSecretSealed"      // sealed with the owner's PIN (SecretBox)
    private const val K_BIO_SEALED = "kite.apiSecretBio"      // a second copy only the fingerprint opens (BiometricGate)
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

    val configured: Boolean get() = SecurePrefs.getString(K_KEY) != null &&
        (SecurePrefs.getString(K_SEALED) != null || SecurePrefs.getString(K_SECRET) != null || SecurePrefs.getString(K_BIO_SEALED) != null)
    val apiKey: String? get() = SecurePrefs.getString(K_KEY)
    /**
     * The redirect URL to register in the Kite Connect app. The login page
     * inside IraAlgo catches it before it loads, so it never reaches the
     * network and no server is needed. (A URL saved by an older version is kept.)
     */
    const val REDIRECT = "http://127.0.0.1/iraalgo"
    val redirect: String get() = SecurePrefs.getString(K_REDIRECT) ?: REDIRECT
    val userName: String? get() = SecurePrefs.getString(K_USER)
    val userId: String? get() = SecurePrefs.getString(K_UID)

    /** Linked: keys saved and at least one Zerodha login completed. The app opens only once linked. */
    val linked: Boolean get() = configured && !SecurePrefs.getString(K_UID).isNullOrBlank()

    /** Only the last four characters of the key, for recognising it. */
    fun maskedKey(): String = apiKey?.let { "••••" + it.takeLast(4) } ?: "not set"

    /**
     * Save the key with the secret sealed by [pin] (already verified by the caller), and/or by the
     * fingerprint ([bioSealed], from BiometricGate). At least one; the secret is never stored readable.
     */
    fun saveCredentials(apiKey: String, apiSecret: String, pin: CharArray?, bioSealed: String? = null) {
        require(apiKey.isNotBlank() && apiKey.all { it.isLetterOrDigit() }) { "The API key should be letters and digits only" }
        require(apiSecret.isNotBlank() && apiSecret.all { it.isLetterOrDigit() }) { "The API secret should be letters and digits only" }
        require(pin != null || bioSealed != null) { "Enter your PIN, or use your fingerprint, to seal the secret" }
        SecurePrefs.putAll(mapOf(K_KEY to apiKey.trim(), K_SEALED to pin?.let { com.optionslab.app.security.SecretBox.seal(apiSecret.trim(), it) },
            K_BIO_SEALED to bioSealed, K_SECRET to null, K_REDIRECT to null, K_TOKEN to null, K_LOGIN_AT to null))
    }

    /** The fingerprint-sealed copy of the secret, if there is one. */
    val bioSealedSecret: String? get() = SecurePrefs.getString(K_BIO_SEALED)
    val pinSealed: Boolean get() = SecurePrefs.getString(K_SEALED) != null || SecurePrefs.getString(K_SECRET) != null
    fun dropBioSealed() { SecurePrefs.put(K_BIO_SEALED, null) }

    fun forget() {
        KiteStream.stop()
        com.optionslab.app.security.BiometricGate.forgetSecretKey()
        SecurePrefs.putAll(mapOf(K_KEY to null, K_SECRET to null, K_SEALED to null, K_BIO_SEALED to null, K_REDIRECT to null, K_TOKEN to null,
            K_LOGIN_AT to null, K_USER to null, K_UID to null))
        File(app.filesDir, "kite_instruments.json").delete()
        PnlTracker.clear()
    }

    fun expiresAt(): ZonedDateTime? = SecurePrefs.getLong(K_LOGIN_AT, 0L).takeIf { it > 0 }
        ?.let { Kite.expiresAt(Instant.ofEpochMilli(it).atZone(IST)) }

    val loggedIn: Boolean get() {
        if (SecurePrefs.getString(K_TOKEN) == null) return false
        val exp = expiresAt() ?: return false
        return ZonedDateTime.now(IST).isBefore(exp)
    }

    private fun token(): String = if (loggedIn) SecurePrefs.getString(K_TOKEN)!! else throw NotLoggedIn()

    /** The session token for the live price stream (never logged, never shown). */
    internal fun streamToken(): String? = if (loggedIn) SecurePrefs.getString(K_TOKEN) else null

    private fun dropSession() { KiteStream.stop(); SecurePrefs.putAll(mapOf(K_TOKEN to null, K_LOGIN_AT to null)) }

    // ---- HTTP ---------------------------------------------------------------------

    private suspend fun call(method: String, path: String, body: String? = null, auth: Boolean = true, raw: Boolean = false,
                             json: Boolean = false): Any {
        var attempt = 0
        while (true) {
            val c = URL(Kite.API + path).openConnection() as HttpsURLConnection
            c.sslSocketFactory = com.optionslab.app.security.KitePin.socketFactory
            try {
                c.requestMethod = method
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.setRequestProperty("X-Kite-Version", "3")
                c.setRequestProperty("User-Agent", "IraAlgo-Android")
                if (auth) c.setRequestProperty("Authorization", "token ${apiKey}:${token()}")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", if (json) "application/json" else "application/x-www-form-urlencoded")
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
            } catch (e: IOException) {
                if (com.optionslab.app.security.KitePin.mismatch) throw IOException(
                    "Refused: Zerodha's certificate chain no longer matches the one this phone pinned. On a network you trust, " +
                        "check api.kite.trade in a browser, then re-trust it under More → Security.")
                throw IOException("Could not reach Zerodha")
            } finally {
                c.disconnect()
            }
        }
    }

    // ---- login --------------------------------------------------------------------

    fun loginUrl(): String = Kite.loginUrl(apiKey ?: error("API key not set"))

    /**
     * Open the sealed API secret with the owner's (verified) PIN, for one login.
     * A secret saved before sealing existed is sealed now and the readable copy removed.
     */
    fun unsealSecret(pin: CharArray): String? {
        SecurePrefs.getString(K_SEALED)?.let { return com.optionslab.app.security.SecretBox.open(it, pin) }
        val legacy = SecurePrefs.getString(K_SECRET) ?: return null
        SecurePrefs.putAll(mapOf(K_SEALED to com.optionslab.app.security.SecretBox.seal(legacy, pin), K_SECRET to null))
        return legacy
    }

    /** The PIN changed: re-seal the secret under the new one. */
    fun resealSecret(oldPin: CharArray, newPin: CharArray) {
        val secret = unsealSecret(oldPin) ?: return
        SecurePrefs.put(K_SEALED, com.optionslab.app.security.SecretBox.seal(secret, newPin))
    }

    /** Exchange the request token for the day's access token (kite_login.exchange_token). */
    suspend fun completeLogin(requestToken: String, secret: String): String {
        val key = apiKey ?: error("API key not set")
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
        KiteStream.stop()
        val key = apiKey
        val tok = SecurePrefs.getString(K_TOKEN)
        if (key != null && tok != null) runCatching {
            call("DELETE", "/session/token?api_key=${Kite.enc(key)}&access_token=${Kite.enc(tok)}")
        }
        dropSession()
    }

    // ---- account --------------------------------------------------------------------

    data class Funds(val available: Double, val used: Double, val net: Double,
                     val openingBalance: Double = 0.0, val collateral: Double = 0.0, val span: Double = 0.0,
                     val exposure: Double = 0.0, val optionPremium: Double = 0.0, val realised: Double = 0.0, val m2m: Double = 0.0)

    /** One row of GET /portfolio/positions (net or day book). */
    data class Position(
        val symbol: String, val product: String, val qty: Int, val avg: Double, val last: Double, val pnl: Double,
        val exchange: String = "NFO", val token: Long = 0L, val overnight: Int = 0, val multiplier: Double = 1.0,
        val m2m: Double = 0.0, val realised: Double = 0.0, val unrealised: Double = 0.0, val close: Double = 0.0,
        val buyQty: Int = 0, val buyAvg: Double = 0.0, val sellQty: Int = 0, val sellAvg: Double = 0.0,
    ) {
        val open: Boolean get() = qty != 0
    }

    data class Positions(val net: List<Position>, val day: List<Position>) {
        val pnl: Double get() = net.sumOf { it.pnl }
        val m2m: Double get() = net.sumOf { it.m2m }
        val realised: Double get() = net.sumOf { it.realised }
        val unrealised: Double get() = net.sumOf { it.unrealised }
    }

    data class OrderRow(val id: String, val symbol: String, val side: String, val qty: Int, val filled: Int, val price: Double,
                        val avg: Double, val status: String, val message: String, val placedAt: String,
                        val exchange: String = "NFO", val product: String = "", val type: String = "LIMIT", val trigger: Double = 0.0,
                        val variety: String = "regular", val pending: Int = 0, val tag: String = "") {
        /** Still working at the exchange: can be modified or cancelled. */
        val working: Boolean get() = status in WORKING
    }

    data class Trade(val id: String, val orderId: String, val symbol: String, val exchange: String, val side: String,
                     val qty: Int, val price: Double, val product: String, val at: String)

    data class Holding(val symbol: String, val exchange: String, val isin: String, val qty: Int, val t1: Int, val avg: Double,
                       val last: Double, val close: Double, val pnl: Double, val dayChange: Double, val dayChangePct: Double,
                       val product: String = "CNC") {
        val invested: Double get() = avg * (qty + t1)
        val value: Double get() = last * (qty + t1)
    }

    private val WORKING = setOf("OPEN", "TRIGGER PENDING", "AMO REQ RECEIVED", "OPEN PENDING", "VALIDATION PENDING", "PUT ORDER REQ RECEIVED", "MODIFY PENDING")

    suspend fun funds(): Funds {
        val eq = (call("GET", "/user/margins") as JSONObject).getJSONObject("equity")
        val a = eq.optJSONObject("available") ?: JSONObject()
        val u = eq.optJSONObject("utilised") ?: JSONObject()
        return Funds(a.optDouble("live_balance", a.optDouble("cash", 0.0)), u.optDouble("debits", 0.0), eq.optDouble("net", 0.0),
            a.optDouble("opening_balance", 0.0), a.optDouble("collateral", 0.0), u.optDouble("span", 0.0), u.optDouble("exposure", 0.0),
            u.optDouble("option_premium", 0.0), u.optDouble("m2m_realised", 0.0), u.optDouble("m2m_unrealised", 0.0))
    }

    private fun position(it: JSONObject) = Position(
        it.optString("tradingsymbol"), it.optString("product"), it.optInt("quantity"), it.optDouble("average_price", 0.0),
        it.optDouble("last_price", 0.0), it.optDouble("pnl", 0.0), it.optString("exchange", "NFO"), it.optLong("instrument_token"),
        it.optInt("overnight_quantity"), it.optDouble("multiplier", 1.0), it.optDouble("m2m", 0.0), it.optDouble("realised", 0.0),
        it.optDouble("unrealised", 0.0), it.optDouble("close_price", 0.0), it.optInt("buy_quantity"), it.optDouble("buy_price", 0.0),
        it.optInt("sell_quantity"), it.optDouble("sell_price", 0.0))

    private fun rows(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }

    suspend fun positionBook(): Positions {
        val d = call("GET", "/portfolio/positions") as JSONObject
        return Positions(rows(d.optJSONArray("net")).map(::position), rows(d.optJSONArray("day")).map(::position))
    }

    suspend fun positions(): List<Position> = positionBook().net

    suspend fun holdings(): List<Holding> = rows(call("GET", "/portfolio/holdings") as? JSONArray).map {
        Holding(it.optString("tradingsymbol"), it.optString("exchange", "NSE"), it.optString("isin"), it.optInt("quantity"),
            it.optInt("t1_quantity"), it.optDouble("average_price", 0.0), it.optDouble("last_price", 0.0), it.optDouble("close_price", 0.0),
            it.optDouble("pnl", 0.0), it.optDouble("day_change", 0.0), it.optDouble("day_change_percentage", 0.0), it.optString("product", "CNC"))
    }.sortedBy { it.symbol }

    suspend fun trades(): List<Trade> = rows(call("GET", "/trades") as? JSONArray).map {
        Trade(it.optString("trade_id"), it.optString("order_id"), it.optString("tradingsymbol"), it.optString("exchange"),
            it.optString("transaction_type"), it.optInt("quantity"), it.optDouble("average_price", 0.0), it.optString("product"),
            it.optString("fill_timestamp", it.optString("exchange_timestamp", "")))
    }.sortedByDescending { it.at }

    suspend fun orders(): List<OrderRow> {
        val arr = call("GET", "/orders") as JSONArray
        return rows(arr).map(::orderRow).sortedByDescending { it.placedAt }
    }

    private fun orderRow(o: JSONObject) = OrderRow(o.optString("order_id"), o.optString("tradingsymbol"), o.optString("transaction_type"),
        o.optInt("quantity"), o.optInt("filled_quantity"), o.optDouble("price", 0.0), o.optDouble("average_price", 0.0),
        o.optString("status"), o.optString("status_message", "").let { if (it == "null") "" else it }, o.optString("order_timestamp", ""),
        o.optString("exchange", "NFO"), o.optString("product"), o.optString("order_type", "LIMIT"), o.optDouble("trigger_price", 0.0),
        o.optString("variety", "regular"), o.optInt("pending_quantity"), o.optString("tag", "").let { if (it == "null") "" else it })

    // ---- market data ----------------------------------------------------------------

    private val INDEX = mapOf("NIFTY" to ("NSE:NIFTY 50" to 256265L), "BANKNIFTY" to ("NSE:NIFTY BANK" to 260105L),
        "INDIAVIX" to ("NSE:INDIA VIX" to 264969L))

    data class Quote(val last: Double, val bid: Double?, val ask: Double?, val open: Double, val oi: Long = 0, val volume: Long = 0)

    /** "NSE:NIFTY BANK" / "NFO:BANKNIFTY26OCT56000CE" -> Kite instrument token, from the day's list. */
    @Volatile private var tokenMap: Pair<LocalDate, Map<String, Long>>? = null
    fun tokenOf(key: String): Long? {
        INDEX.values.firstOrNull { it.first == key }?.let { return it.second }
        if (!key.startsWith("NFO:")) return null
        val m = tokenMap?.takeIf { it.first == Market.today() }?.second
            ?: cachedInstruments()?.associate { "NFO:" + it.tradingSymbol to it.token }?.also { tokenMap = Market.today() to it }
            ?: return null
        return m[key]
    }

    /**
     * Quotes for "EXCHANGE:SYMBOL" keys. Instruments the live stream has a fresh tick for
     * are answered from it at once; only the rest go to Kite's quote API (and are
     * followed by the stream from then on).
     */
    suspend fun quotes(keys: List<String>): Map<String, Quote> {
        if (keys.isEmpty()) return emptyMap()
        val tokens = keys.associateWith { tokenOf(it) }
        KiteStream.touch(tokens.values.filterNotNull())
        val streamed = keys.mapNotNull { k -> tokens[k]?.let { KiteStream.tick(it) }?.let { t -> k to Quote(t.last, t.bid, t.ask, t.open, t.oi, t.volume) } }.toMap()
        val rest = keys.filter { it !in streamed }
        if (rest.isEmpty()) return streamed
        return streamed + quotesRest(rest)
    }

    private suspend fun quotesRest(keys: List<String>): Map<String, Quote> {
        val data = call("GET", "/quote?" + keys.joinToString("&") { "i=" + Kite.enc(it) }) as JSONObject
        return keys.mapNotNull { k ->
            val q = data.optJSONObject(k) ?: return@mapNotNull null
            val depth = q.optJSONObject("depth")
            fun best(side: String) = depth?.optJSONArray(side)?.optJSONObject(0)?.optDouble("price")?.takeIf { it > 0 }
            k to Quote(q.optDouble("last_price"), best("buy"), best("sell"), q.optJSONObject("ohlc")?.optDouble("open") ?: 0.0,
                q.optLong("oi", 0), q.optLong("volume", 0))
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

    /** Instrument token of an index Kite knows by its IraAlgo name (NIFTY, BANKNIFTY, INDIAVIX). */
    fun indexToken(symbol: String): Long? = INDEX[symbol]?.second

    /** Daily candles (needs Kite's historical-data add-on); Kite serves about 2000 days a call. */
    suspend fun dailyBars(token: Long, from: LocalDate, to: LocalDate): List<Upstox.Bar> {
        val out = ArrayList<Upstox.Bar>()
        var lo = from
        while (!lo.isAfter(to)) {
            val hi = minOf(lo.plusDays(1900), to)
            val q = "from=${Kite.enc("$lo 00:00:00")}&to=${Kite.enc("$hi 23:59:59")}"
            val candles = (call("GET", "/instruments/historical/$token/day?$q") as JSONObject).optJSONArray("candles") ?: JSONArray()
            for (i in 0 until candles.length()) {
                val r = candles.getJSONArray(i)
                val ts = r.getString(0)
                val epoch = runCatching { OffsetDateTime.parse(ts, KITE_TS) }.getOrElse { OffsetDateTime.parse(ts) }.toEpochSecond()
                out += Upstox.Bar(epoch, r.getDouble(1), r.getDouble(2), r.getDouble(3), r.getDouble(4), r.optLong(5), 0L)
            }
            lo = hi.plusDays(1)
            delay(340)
        }
        return out.distinctBy { it.epochSecond }.sortedBy { it.epochSecond }
    }

    /** The index's 1-minute bars (needs Kite's historical data), e.g. for settlement. */
    suspend fun indexMinuteBars(symbol: String, day: LocalDate): List<Upstox.Bar> =
        minuteBars(INDEX[symbol]?.second ?: throw IOException("no Zerodha index for $symbol"), day)

    private val sparks = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, List<Double>>>()

    suspend fun indexQuote(symbol: String): Market.Quote? {
        val (key, token) = INDEX[symbol] ?: return null
        val q = quotes(listOf(key))[key] ?: return null
        val minute = Market.minuteNow()
        val spark = sparks[symbol]?.takeIf { it.first == minute && it.second.isNotEmpty() }?.second
            ?: runCatching { minuteBars(token, Market.today()).map { it.close } }.getOrDefault(emptyList()).also { sparks[symbol] = minute to it }
        val m = Market.now().let { it.hour * 60 + it.minute }
        return Market.Quote(symbol, q.last, q.open.takeIf { it > 0 } ?: q.last, spark.maxOrNull() ?: q.last, spark.minOrNull() ?: q.last, m,
            spark.ifEmpty { listOf(q.last) })
    }

    // ---- instruments ---------------------------------------------------------------------

    /** The last instruments list fetched (any day), without touching the network. */
    fun cachedInstruments(): List<Kite.Instrument>? = runCatching {
        val a = JSONObject(File(app.filesDir, "kite_instruments.json").readText()).getJSONArray("i")
        (0 until a.length()).map { a.getJSONArray(it) }.map {
            Kite.Instrument(it.getLong(0), it.getString(1), it.getString(2), LocalDate.parse(it.getString(3)), it.getDouble(4),
                it.getInt(5), if (it.getString(6) == "CE") Right.CE else Right.PE, it.getDouble(7))
        }
    }.getOrNull()

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

    private val specs = HashMap<String, Kite.Spec>()
    private var specDay: LocalDate? = null

    /**
     * Lot and tick for any instrument, e.g. to square off a position the app
     * did not open. Options the app already knows come from the cached NFO
     * list; anything else is looked up once in that exchange's dump and kept
     * in memory for the day.
     */
    suspend fun spec(exchange: String, symbol: String): Kite.Spec {
        if (specDay != Market.today()) { specs.clear(); specDay = Market.today() }
        specs["$exchange:$symbol"]?.let { return it }
        if (exchange == "NFO") (cachedInstruments() ?: emptyList()).firstOrNull { it.tradingSymbol == symbol }?.let {
            return Kite.Spec("NFO", symbol, it.token, it.lotSize, it.tickSize).also { s -> specs["$exchange:$symbol"] = s }
        }
        require(exchange.all { it.isLetter() }) { "unknown exchange $exchange" }
        val text = call("GET", "/instruments/${Kite.enc(exchange)}", raw = true) as String
        val got = Kite.lookup(text.lineSequence(), setOf(symbol))[symbol] ?: throw IOException("$exchange:$symbol is not listed on Zerodha today")
        specs["$exchange:$symbol"] = got
        return got
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
        val contracts = wanted.map { Upstox.Contract(underlying, it.expiry, it.strike, it.right, it.lotSize, "kite:${it.token}", it.tradingSymbol) }
        // One quote call prices every strike with its open interest: about a second, where fetching
        // each contract's minute candles at Kite's three-a-second limit took 15-20 seconds.
        return quoteSnapshot(underlying, expiry, wanted, contracts, spot)
    }

    private suspend fun quoteSnapshot(underlying: String, expiry: LocalDate, wanted: List<Kite.Instrument>,
                                      contracts: List<Upstox.Contract>, spot: Double): Market.LiveChain {
        val q = quotes(wanted.map { "NFO:${it.tradingSymbol}" })
        val minute = Market.minuteNow()
        val series = wanted.zip(contracts).mapNotNull { (ins, c) ->
            val last = q["NFO:${ins.tradingSymbol}"]?.last?.takeIf { it > 0 } ?: return@mapNotNull null
            val qt = q["NFO:${ins.tradingSymbol}"]!!
            Series(c.expiry, c.strike, c.right, c.lotSize, intArrayOf(minute), doubleArrayOf(last), doubleArrayOf(last),
                doubleArrayOf(last), doubleArrayOf(last), longArrayOf(qt.volume), longArrayOf(qt.oi))
        }
        if (series.isEmpty()) throw IOException("Zerodha returned no quotes for the $expiry $underlying chain")
        return Market.LiveChain(expiry, series, wanted.first().lotSize, contracts, spot, "Zerodha live quotes", minute)
    }

    // ---- orders ------------------------------------------------------------------------

    fun sentToday(): Int = if (SecurePrefs.getString(K_SENT_DAY) == Market.today().toString()) SecurePrefs.getInt(K_SENT, 0) else 0

    @Synchronized private fun countSent() = SecurePrefs.putAll(mapOf(K_SENT_DAY to Market.today().toString(), K_SENT to sentToday() + 1))

    data class Fill(val orderId: String, val status: String, val avgPrice: Double, val filled: Int, val message: String)

    /** Sends ONE order. Callers must have passed Kite.refusals and the owner's confirmation. */
    suspend fun placeOrder(o: Kite.Order, exit: Boolean = false): String {
        // SEBI static IP: a new position is not opened from an IP Zerodha would refuse (exits always go).
        if (!exit) StaticIp.entryBlock()?.let { throw KiteError("static_ip", it) }
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

    /**
     * After a POST whose answer was lost (timeout, dropped connection), find the
     * order Kite may have placed anyway: same symbol, side, quantity and tag,
     * placed in the last three minutes, and not one of [known].
     */
    suspend fun findRecent(o: Kite.Order, known: Collection<String>): String? {
        val since = java.time.LocalDateTime.now(IST).minusMinutes(3)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val arr = call("GET", "/orders") as JSONArray
        return rows(arr).lastOrNull {
            it.optString("tradingsymbol") == o.tradingSymbol && it.optString("transaction_type") == o.side.name &&
                it.optInt("quantity") == o.quantity && it.optString("tag") == o.tag.filter { c -> c.isLetterOrDigit() }.take(20) &&
                it.optString("order_id") !in known &&
                runCatching { !java.time.LocalDateTime.parse(it.optString("order_timestamp"), fmt).isBefore(since) }.getOrDefault(false)
        }?.optString("order_id")?.also { countSent() }
    }

    // ---- GTT ------------------------------------------------------------------------------

    data class GttRow(val id: Long, val type: String, val status: String, val exchange: String, val symbol: String,
                      val triggers: List<Double>, val lastPrice: Double, val orders: String, val created: String)

    suspend fun gtts(): List<GttRow> = rows(call("GET", "/gtt/triggers") as? JSONArray).map { g ->
        val c = g.optJSONObject("condition") ?: JSONObject()
        val tv = c.optJSONArray("trigger_values") ?: JSONArray()
        val os = rows(g.optJSONArray("orders")).joinToString(" / ") { "${it.optString("transaction_type")} ${it.optInt("quantity")} @ ${it.optDouble("price")}" }
        GttRow(g.optLong("id"), g.optString("type"), g.optString("status"), c.optString("exchange"), c.optString("tradingsymbol"),
            (0 until tv.length()).map { tv.getDouble(it) }, c.optDouble("last_price", 0.0), os, g.optString("created_at"))
    }.sortedByDescending { it.created }

    /** Places a GTT. Callers must have had the owner confirm it. */
    suspend fun placeGtt(g: Kite.Gtt): Long = (call("POST", "/gtt/triggers", g.formBody()) as JSONObject).getLong("trigger_id")

    suspend fun deleteGtt(id: Long) { call("DELETE", "/gtt/triggers/$id") }

    /** What Zerodha would block for these orders together ([required]), against the account's free margin. */
    data class Margin(val required: Double, val initial: Double, val available: Double, val charges: Double) {
        val short: Boolean get() = required > available
    }

    /** POST /margins/basket, counting open positions (so a hedge's benefit is included). */
    suspend fun basketMargin(orders: List<Kite.Order>): Margin {
        val d = call("POST", "/margins/basket?consider_positions=true", Kite.basketJson(orders), json = true) as JSONObject
        val fin = d.optJSONObject("final")?.optDouble("total", Double.NaN) ?: Double.NaN
        val init = d.optJSONObject("initial")?.optDouble("total", Double.NaN) ?: Double.NaN
        val charges = rows(d.optJSONArray("orders")).sumOf { it.optJSONObject("charges")?.optDouble("total", 0.0) ?: 0.0 }
        return Margin(if (fin.isNaN()) init else fin, init, funds().net, charges)
    }

    /** One order's latest state (last entry of its history), or null if Kite has none yet. */
    suspend fun orderState(orderId: String): Fill? {
        val hist = call("GET", "/orders/${Kite.enc(orderId)}") as JSONArray
        if (hist.length() == 0) return null
        val o = hist.getJSONObject(hist.length() - 1)
        return Fill(orderId, o.optString("status"), o.optDouble("average_price", 0.0), o.optInt("filled_quantity"), o.optString("status_message", ""))
    }

    suspend fun cancel(orderId: String, variety: String = "regular") {
        call("DELETE", "/orders/${Kite.enc(variety)}/${Kite.enc(orderId)}")
    }

    /** PUT /orders/{variety}/{id}. Callers re-check the order is still working and the owner confirmed. */
    suspend fun modify(order: OrderRow, quantity: Int, orderType: String, price: Double?, trigger: Double?) {
        call("PUT", "/orders/${Kite.enc(order.variety)}/${Kite.enc(order.id)}", Kite.modifyBody(quantity, orderType, price, trigger))
    }
}
