package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.SecurePrefs
import com.optionslab.engine.IST
import com.optionslab.engine.Kite
import com.optionslab.engine.Right
import com.optionslab.engine.Series
import com.optionslab.engine.Upstox
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    /** Where a bounded read runs ([within]): apart from its caller, so a caller that stops waiting is not held. */
    private val readScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /**
     * A Zerodha read with a real deadline. [call] does blocking HttpsURLConnection I/O (20 s connect, 60 s read) that does
     * not suspend, so a withTimeoutOrNull wrapped straight around it cannot end the wait. Here [read] runs apart on IO and
     * the caller waits [ms] at most: null on timeout or failure. A read the caller gave up on finishes in the background
     * and is dropped. Reads only: never used for anything that places, changes or cancels.
     */
    suspend fun <T> within(ms: Long, read: suspend () -> T): T? {
        val d = readScope.async { runCatching { read() }.getOrNull() }
        return kotlinx.coroutines.withTimeoutOrNull(ms) { d.await() }
    }

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

    /**
     * The positions read shared within one market-watch pass ([passPositionBook], [com.optionslab.ira.PassShare]): the
     * words-only checks of a pass read Zerodha's positions once, not once each. Nothing that orders uses it.
     */
    private const val PASS_BOOK_MS = 20_000L
    private val passBook = com.optionslab.ira.PassShare<Positions>(PASS_BOOK_MS)

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
        passBook.drop()
        KiteStream.stop()
        com.optionslab.app.security.BiometricGate.forgetSecretKey()
        SecurePrefs.putAll(mapOf(K_KEY to null, K_SECRET to null, K_SEALED to null, K_BIO_SEALED to null, K_REDIRECT to null, K_TOKEN to null,
            K_LOGIN_AT to null, K_USER to null, K_UID to null))
        File(app.filesDir, "kite_instruments.json").delete()
        File(app.filesDir, "kite_futures.json").delete()
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

    private fun dropSession() { passBook.drop(); KiteStream.stop(); SecurePrefs.putAll(mapOf(K_TOKEN to null, K_LOGIN_AT to null)) }

    /** Bumped when Zerodha itself ends the session (expired or logged out elsewhere): the app asks to log in again. */
    val sessionEnded = kotlinx.coroutines.flow.MutableStateFlow(0)

    // ---- HTTP ---------------------------------------------------------------------

    /**
     * TEST SEAM (JVM tests only): send [call]'s requests to a local fake Kite server instead of
     * api.kite.trade, trusting that server's test certificate instead of the pinned Zerodha chain,
     * and without the static-IP relay. Null - always, in the app - means Zerodha itself.
     *
     * It cannot be switched on in a release build: the setter throws unless BuildConfig.DEBUG, and
     * nothing in the app calls it (only src/test does). Every other line of [call] - headers, auth,
     * retries, error mapping - runs unchanged against the fake.
     */
    internal class TestEndpoint(val base: String, val ssl: javax.net.ssl.SSLSocketFactory)
    @Volatile internal var testEndpoint: TestEndpoint? = null
        set(v) {
            check(com.optionslab.app.BuildConfig.DEBUG) { "the test endpoint exists only in debug builds" }
            field = v
        }

    /** Order fields kept in the diagnostics diary (what was asked, never who asked or with what key). */
    private val DIAG_FIELDS = setOf("tradingsymbol", "exchange", "transaction_type", "quantity", "order_type", "product",
        "price", "trigger_price", "validity", "variety", "tag")

    /**
     * Every Zerodha write (orders, changes, cancels, GTTs) and every failed read goes into the owner's diagnostics
     * diary: the path, the order's fields, Zerodha's answer or the failure, how long it took, and whether it went
     * through the static-IP relay. No header, token or key is ever read here.
     */
    private suspend fun call(method: String, path: String, body: String? = null, auth: Boolean = true, raw: Boolean = false,
                             json: Boolean = false, viaRelay: Boolean = false, readOnly: Boolean = false): Any {
        val t0 = System.currentTimeMillis()
        // [readOnly]: a POST that only reads (Zerodha's contract note): sent direct like a GET, and it changes nothing,
        // so no kept read is dropped and no diary line is written for it unless it fails.
        val write = method != "GET" && !readOnly
        val where = "$method ${path.substringBefore('?')}"
        val asked = if (body == null || json) "" else body.split('&').mapNotNull { kv ->
            val k = kv.substringBefore('='); if (k in DIAG_FIELDS) "$k: ${java.net.URLDecoder.decode(kv.substringAfter('='), "UTF-8")}" else null
        }.joinToString(" ", prefix = " {", postfix = "}").takeIf { it != " {}" }.orEmpty()
        val route = if ((write || viaRelay) && testEndpoint == null && Relay.enabled) " via relay" else ""
        // A write (an order, a change, a cancel) changes the positions: the pass's shared read is dropped before it is
        // sent and again once it is answered, so no words-only check is given the book from before it.
        if (write) passBook.drop()
        return try {
            callInner(method, path, body, auth, raw, json, viaRelay, readOnly).also { r ->
                if (write) Diag.record("zerodha", "$where$asked$route -> ok ${(r as? JSONObject)?.optString("order_id")?.takeIf { it.isNotEmpty() }?.let { "order $it " } ?: ""}(${System.currentTimeMillis() - t0} ms)")
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException)
                Diag.record("zerodha", "$where$asked$route -> FAILED ${e.javaClass.simpleName}: ${e.message} (${System.currentTimeMillis() - t0} ms)")
            throw e
        } finally {
            if (write) {
                passBook.drop()
                // Jarvis's kept account figures are dropped too (an order, a change, a cancel or a GTT, from any screen,
                // a strategy or the guard): no answer is said from before it ([com.optionslab.app.ira.IraAccount.invalidate]).
                runCatching { com.optionslab.app.ira.IraAccount.invalidate() }
            }
        }
    }

    private suspend fun callInner(method: String, path: String, body: String?, auth: Boolean, raw: Boolean,
                                  json: Boolean, viaRelay: Boolean, readOnly: Boolean = false): Any {
        var attempt = 0
        while (true) {
            // Orders and every other write go through the static-IP relay when it is on (it throws if it
            // cannot connect, so nothing leaves from another IP); reads go direct ([viaRelay]: a read that
            // warms the order route).
            val test = testEndpoint
            val relay = if (((method != "GET" && !readOnly) || viaRelay) && test == null) Relay.proxy() else null
            val url = URL((test?.base ?: Kite.API) + path)
            val c = (if (relay != null) url.openConnection(relay) else url.openConnection()) as HttpsURLConnection
            c.sslSocketFactory = test?.ssl ?: com.optionslab.app.security.KitePin.socketFactory
            // Speed: a reply read to its end leaves its connection (TCP + TLS, and through the relay its SSH
            // channel) in the HTTP stack's pool, so the next call - the order itself - skips the handshakes.
            // Any failure closes it. A POST is never re-sent on a pooled connection: its body is streamed at
            // a fixed length (below), which the stack cannot replay, and a non-GET gets a health check first.
            var reusable = false
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
                    // Streamed at a fixed length, the body cannot be replayed: neither the JVM's nor Android's
                    // connection code may then silently re-send an order when a reply is lost (a second order).
                    val bytes = body.toByteArray()
                    c.setFixedLengthStreamingMode(bytes.size)
                    c.outputStream.use { it.write(bytes) }
                }
                val code = c.responseCode
                if (code == 429 && attempt < 3) { attempt++; delay(1_000L * attempt); continue }
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                reusable = true
                if (raw && code in 200..299) return text
                val json = runCatching { JSONObject(text) }.getOrNull() ?: throw IOException("Zerodha returned an unreadable reply ($code)")
                if (json.optString("status") != "success") {
                    val type = json.optString("error_type", "Error")
                    // Kite's own message names the problem (margin, price band,
                    // freeze quantity); it carries no credential, so it is shown.
                    val msg = json.optString("message", "request failed").take(300)
                    if (type == "TokenException") {
                        // One request's TokenException is checked against the profile before the day's session is
                        // dropped: a single refused call (a glitch on one endpoint) no longer logs Boss out for the day.
                        // The reason is kept for the diagnostics (the path only, never its query; Kite's message has no key).
                        // Only the profile refused for its token ends the day: a network failure or a timeout on the
                        // check keeps the session (the glitch case), and a cancel is passed on, never read as an end.
                        val kept = path.substringBefore('?') != "/user/profile" && auth && try {
                            callInner("GET", "/user/profile", null, true, false, false, false); true
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (e: KiteError) { e.type != "TokenException"
                        } catch (_: Exception) { true }
                        Diag.record("info", "Zerodha TokenException on $method ${path.substringBefore('?')}: $msg" +
                            if (kept) " (the profile still answers: session kept)" else " (session ended)")
                        if (kept) throw KiteError(type, msg)
                        if (loggedIn) { dropSession(); sessionEnded.value++ }
                        throw KiteError(type, "Zerodha session ended: $msg")
                    }
                    // SEBI's static-IP rule: Zerodha takes orders only from an IP listed in the Kite Connect app.
                    if (msg.contains("No IPs configured", ignoreCase = true) || msg.contains("not allowed to place orders", ignoreCase = true))
                        throw KiteError(type, "Zerodha does not know your order IP yet. Add ${StaticIp.registered ?: "your static IP"} in developers.kite.trade → My apps → your app → IP whitelist, save, then try again. (Zerodha: $msg)")
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
                if (!reusable) c.disconnect()
            }
        }
    }

    @Volatile private var warmedAt = 0L
    private val warmScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /**
     * Get the order route ready while the owner is still reviewing (or, for the bots, during market hours):
     * the relay's SSH session and a pooled TLS connection to Zerodha through it, and the static-IP reading
     * an entry is checked against. Only removes waiting: every check at send still runs. Returns at once
     * (the work runs in the background), never throws, and does something at most every 20 s.
     */
    fun warmOrderRoute(entry: Boolean = true) {
        val now = System.currentTimeMillis()
        if (now - warmedAt < 20_000) return
        warmedAt = now
        if (testEndpoint != null) return            // JVM tests: the fake Kite needs no warming, and nothing may reach the internet
        if (entry && StaticIp.registered != null) warmScope.launch { runCatching { StaticIp.current(maxAgeMs = 30_000) } }
        // Without the relay, the review's own reads have already pooled the connection the order uses.
        if (Relay.enabled && loggedIn) warmScope.launch { runCatching { call("GET", "/user/profile", viaRelay = true) } }
    }

    // ---- login --------------------------------------------------------------------

    fun loginUrl(): String = Kite.loginUrl(apiKey ?: error("API key not set"))

    /**
     * Open the sealed API secret with the owner's (verified) PIN, for one login.
     * A secret saved before sealing existed is sealed now and the readable copy removed.
     */
    fun unsealSecret(pin: CharArray): String? {
        SecurePrefs.getString(K_SEALED)?.let { blob ->
            val secret = com.optionslab.app.security.SecretBox.open(blob, pin) ?: return null
            // An older (v1) seal is re-sealed in the device-bound format now that the PIN has opened it.
            runCatching { com.optionslab.app.security.SecretBox.upgrade(blob, pin, secret)?.let { SecurePrefs.put(K_SEALED, it) } }
            return secret
        }
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
        // Always read fresh (stops, targets, the loss limit and the bots rely on it); within a market-watch pass the
        // answer is also kept for the words-only checks after it ([passPositionBook]).
        val ticket = passBook.ticket()
        val at = passBook.now()
        val d = call("GET", "/portfolio/positions") as JSONObject
        return Positions(rows(d.optJSONArray("net")).map(::position), rows(d.optJSONArray("day")).map(::position))
            .also { passBook.put(ticket, at, it) }
    }

    /** The market watch opens and closes its pass: within it, [passPositionBook] reads Zerodha at most once. */
    fun passBegin() = passBook.open()
    fun passEnd() = passBook.close()

    /**
     * Positions for words only - the Jarvis heads-up, trades going nowhere, the day's target, the plan, the 15:10 MIS
     * word and the watch notice's P&L line; never for anything that places, changes or closes an order. Within a
     * market-watch pass this is the pass's one read (at most [PASS_BOOK_MS] old; any write to Zerodha or a session end
     * drops it); outside a pass, and when there is none yet, Zerodha is read now. A failed read is never kept.
     */
    suspend fun passPositionBook(): Positions = passBook.share { positionBook() }

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
        val list = rows(arr).map(::orderRow).sortedByDescending { it.placedAt }
        // The "Open" widget's pending orders come from the reads already made here, handed to its own thread so this
        // (an order path) never waits on its vault write or the launcher.
        runCatching { com.optionslab.app.widget.OpenWidget.fromOrdersSoon(app, list) }
        return list
    }

    private fun orderRow(o: JSONObject) = OrderRow(o.optString("order_id"), o.optString("tradingsymbol"), o.optString("transaction_type"),
        o.optInt("quantity"), o.optInt("filled_quantity"), o.optDouble("price", 0.0), o.optDouble("average_price", 0.0),
        o.optString("status"), o.optString("status_message", "").let { if (it == "null") "" else it }, o.optString("order_timestamp", ""),
        o.optString("exchange", "NFO"), o.optString("product"), o.optString("order_type", "LIMIT"), o.optDouble("trigger_price", 0.0),
        o.optString("variety", "regular"), o.optInt("pending_quantity"), o.optString("tag", "").let { if (it == "null") "" else it })

    // ---- market data ----------------------------------------------------------------

    private val INDEX = mapOf("NIFTY" to ("NSE:NIFTY 50" to 256265L), "BANKNIFTY" to ("NSE:NIFTY BANK" to 260105L),
        "INDIAVIX" to ("NSE:INDIA VIX" to 264969L), "FINNIFTY" to ("NSE:NIFTY FIN SERVICE" to 257801L),
        "MIDCPNIFTY" to ("NSE:NIFTY MID SELECT" to 288009L), "SENSEX" to ("BSE:SENSEX" to 265L))

    data class Quote(val last: Double, val bid: Double?, val ask: Double?, val open: Double, val oi: Long = 0, val volume: Long = 0)

    /** "NSE:NIFTY BANK" / "NFO:BANKNIFTY26OCT56000CE" -> Kite instrument token, from the day's list. */
    @Volatile private var tokenMap: Pair<LocalDate, Map<String, Long>>? = null
    fun tokenOf(key: String): Long? {
        INDEX.values.firstOrNull { it.first == key }?.let { return it.second }
        // MCX: from the day's MCX list ([McxMarket]), so its contracts stream too.
        if (key.startsWith("MCX:")) return McxMarket.tokenOf(key.removePrefix("MCX:"))
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

    /**
     * Last price and previous close for "EXCHANGE:SYMBOL" keys, from Kite's OHLC quote (500 keys a call): the Night (R3)
     * arm's breadth ([com.optionslab.engine.orb.NightRules.breadth]). Reads only; a key Kite does not know is left out.
     */
    suspend fun lastAndPrevClose(keys: List<String>): Map<String, Pair<Double, Double>> {
        val out = HashMap<String, Pair<Double, Double>>()
        for (chunk in keys.chunked(500)) {
            val data = call("GET", "/quote/ohlc?" + chunk.joinToString("&") { "i=" + Kite.enc(it) }) as JSONObject
            for (k in chunk) {
                val q = data.optJSONObject(k) ?: continue
                val pc = q.optJSONObject("ohlc")?.optDouble("close") ?: continue
                out[k] = q.optDouble("last_price") to pc
            }
        }
        return out
    }

    /** Kite's quote key for an index by its IraAlgo name ("NIFTY" -> "NSE:NIFTY 50"), null for one Kite has no index of. */
    fun indexKey(symbol: String): String? = INDEX[symbol]?.first

    /**
     * The market recorder's read ([MarketRecorder]): every key's last price, volume, OI, best bid and offer with their
     * quantities and the book's totals. A key with a fresh full-mode tick on the live stream is answered from it (no
     * network); the rest in ONE quote call per 500 keys. Unlike [quotes] it never adds anything to the stream's follow
     * list (so the recorder alone never keeps the stream, and the phone's radio, busy). Reads only.
     */
    suspend fun recorderQuotes(keys: Map<String, Long?>): Map<String, com.optionslab.ira.MarketRecord.Quote> {
        if (keys.isEmpty()) return emptyMap()
        val out = HashMap<String, com.optionslab.ira.MarketRecord.Quote>()
        for ((k, token) in keys) {
            val t = (token ?: tokenOf(k))?.let { KiteStream.tick(it) } ?: continue
            if (t.bid == null && t.ask == null && t.tradable) continue          // no depth on this tick: the quote call has it
            out[k] = com.optionslab.ira.MarketRecord.Quote(t.last, t.volume.takeIf { t.tradable }, t.oi.takeIf { t.tradable }, t.bid, t.bidQty,
                t.ask, t.askQty, t.buyQty, t.sellQty, stream = true)
        }
        for (chunk in keys.keys.filter { it !in out }.chunked(500)) {
            val data = call("GET", "/quote?" + chunk.joinToString("&") { "i=" + Kite.enc(it) }) as JSONObject
            for (k in chunk) {
                val q = data.optJSONObject(k) ?: continue
                val depth = q.optJSONObject("depth")
                fun top(side: String) = depth?.optJSONArray(side)?.optJSONObject(0)
                fun px(side: String) = top(side)?.optDouble("price")?.takeIf { it > 0 }
                fun qty(side: String) = top(side)?.optLong("quantity")?.takeIf { px(side) != null }
                fun long(name: String) = if (q.has(name)) q.optLong(name) else null
                out[k] = com.optionslab.ira.MarketRecord.Quote(q.optDouble("last_price"), long("volume"), long("oi"), px("buy"), qty("buy"),
                    px("sell"), qty("sell"), long("buy_quantity"), long("sell_quantity"))
            }
        }
        return out
    }

    /** The index futures the market recorder follows, by name. */
    val FUTURE_NAMES = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")

    private fun futuresFile() = File(app.filesDir, "kite_futures.json")

    /** The futures kept: (the day NFO's were read, the day BFO's were read, the list). */
    private fun futuresKept(): Triple<String, String, List<Kite.Future>> = runCatching {
        val o = JSONObject(futuresFile().readText())
        val a = o.getJSONArray("f")
        Triple(o.optString("nfo"), o.optString("bfo"), (0 until a.length()).map { a.getJSONArray(it) }.map {
            Kite.Future(it.getLong(0), it.getString(1), it.getString(2), LocalDate.parse(it.getString(3)), it.getInt(4), it.getString(5))
        })
    }.getOrDefault(Triple("", "", emptyList()))

    private fun keepFutures(nfoDay: String, bfoDay: String, list: List<Kite.Future>) {
        val a = JSONArray()
        list.forEach { a.put(JSONArray().put(it.token).put(it.tradingSymbol).put(it.name).put(it.expiry.toString()).put(it.lotSize).put(it.exchange)) }
        futuresFile().writeText(JSONObject().put("nfo", nfoDay).put("bfo", bfoDay).put("f", a).toString())
    }

    /** NFO's futures from the day's instruments dump [instruments] has just downloaded (no second download for them). */
    private fun keepNfoFutures(text: String) {
        val (_, bfoDay, kept) = futuresKept()
        val nfo = Kite.parseFutures(text.lineSequence(), FUTURE_NAMES)
        keepFutures(Market.today().toString(), bfoDay, nfo + kept.filter { it.exchange == "BFO" })
    }

    @Volatile private var futuresTriedAt = 0L

    /**
     * Today's index futures (NFO's NIFTY, BANKNIFTY, FINNIFTY; BFO's SENSEX), for the market recorder. NFO's come with the
     * day's instruments dump; BFO's dump is read once a day. A failed read is tried again after 30 minutes at most.
     */
    suspend fun futures(): List<Kite.Future> {
        val today = Market.today().toString()
        val (nfoDay, bfoDay, kept) = futuresKept()
        if (nfoDay == today && bfoDay == today) return kept
        val now = System.currentTimeMillis()
        if (now - futuresTriedAt < 30 * 60_000L) return if (nfoDay == today) kept else emptyList()
        futuresTriedAt = now
        val nfo: List<Kite.Future> = if (nfoDay == today) kept.filter { it.exchange == "NFO" } else {
            // The day's options list first: not downloaded yet today, its dump carries the futures too.
            runCatching { instruments() }
            val again = futuresKept()
            if (again.first == today) again.third.filter { it.exchange == "NFO" }
            else Kite.parseFutures((call("GET", "/instruments/NFO", raw = true) as String).lineSequence(), FUTURE_NAMES)
        }
        val bfo = if (bfoDay == today) kept.filter { it.exchange == "BFO" }
            else runCatching { Kite.parseFutures((call("GET", "/instruments/BFO", raw = true) as String).lineSequence(), setOf("SENSEX")) }.getOrNull()
        val all = nfo + bfo.orEmpty()
        keepFutures(today, if (bfo != null) today else bfoDay, all)
        return all
    }

    private val KITE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")

    /**
     * 1-minute candles with OI (needs a plan that includes historical data). [from] / [to]: the session's window (NSE's
     * 09:15-15:30 unless asked; MCX's 09:00-23:59).
     */
    suspend fun minuteBars(token: Long, day: LocalDate, from: String = "09:15:00", to: String = "15:30:00"): List<Upstox.Bar> {
        val q = "from=${Kite.enc("$day $from")}&to=${Kite.enc("$day $to")}&oi=1"
        val candles = (call("GET", "/instruments/historical/$token/minute?$q") as JSONObject).optJSONArray("candles") ?: JSONArray()
        return (0 until candles.length()).map { candles.getJSONArray(it) }.map { r ->
            val ts = r.getString(0)
            val epoch = runCatching { OffsetDateTime.parse(ts, KITE_TS) }.getOrElse { OffsetDateTime.parse(ts) }.toEpochSecond()
            Upstox.Bar(epoch, r.getDouble(1), r.getDouble(2), r.getDouble(3), r.getDouble(4), r.optLong(5), if (r.length() > 6) r.optLong(6) else 0L)
        }
    }

    /**
     * Zerodha's MCX instrument list (GET /instruments/MCX, CSV): public, so it is read without the login when there is none
     * ([McxMarket.contracts]). Reads only.
     */
    suspend fun mcxInstrumentsText(): String = call("GET", "/instruments/MCX", auth = loggedIn, raw = true) as String

    /** Zerodha's public commodity margin list (JSON: each contract's NRML and MIS margin per lot). Reads only, no login. */
    suspend fun mcxMarginsText(): String = call("GET", "/margins/commodity", auth = false, raw = true) as String

    /** Instrument token of an index Kite knows by its IraAlgo name (NIFTY, BANKNIFTY, INDIAVIX). */
    fun indexToken(symbol: String): Long? = INDEX[symbol]?.second

    /**
     * Daily candles (needs Kite's historical-data add-on); Kite serves about 2000 days a call. [continuous]: a future's
     * continuous near-month series (Kite's continuous=1; the MCX trend arm's 12-month history), not roll-adjusted. Reads only.
     */
    suspend fun dailyBars(token: Long, from: LocalDate, to: LocalDate, continuous: Boolean = false): List<Upstox.Bar> {
        val out = ArrayList<Upstox.Bar>()
        var lo = from
        while (!lo.isAfter(to)) {
            val hi = minOf(lo.plusDays(1900), to)
            val q = "from=${Kite.enc("$lo 00:00:00")}&to=${Kite.enc("$hi 23:59:59")}" + (if (continuous) "&continuous=1" else "")
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

    /**
     * [symbol]'s level from Zerodha. [spark] false (the order watch: it reads only the last price and the change from
     * the open): the quote alone, no read of the day's candles - the high, low and spark are then this minute's kept
     * ones, else the last price ([com.optionslab.ira.IndexSpark]).
     */
    suspend fun indexQuote(symbol: String, spark: Boolean = true): Market.Quote? {
        val (key, token) = INDEX[symbol] ?: return null
        val q = quotes(listOf(key))[key] ?: return null
        val minute = Market.minuteNow()
        val kept = sparks[symbol]
        val read = com.optionslab.ira.IndexSpark.candles(spark, kept?.first, minute, kept?.second.isNullOrEmpty())
        val closes = if (read) runCatching { minuteBars(token, Market.today()).map { it.close } }.getOrDefault(emptyList()).also { sparks[symbol] = minute to it }
            else kept?.takeIf { it.first == minute }?.second.orEmpty()
        val m = Market.now().let { it.hour * 60 + it.minute }
        return Market.Quote(symbol, q.last, q.open.takeIf { it > 0 } ?: q.last, closes.maxOrNull() ?: q.last, closes.minOrNull() ?: q.last, m,
            closes.ifEmpty { listOf(q.last) })
    }

    // ---- instruments ---------------------------------------------------------------------

    /** The parsed list, the day it was fetched and the file stamp (modified time, length) it was parsed from. */
    private class InstrumentsMemo(val stamp: Pair<Long, Long>, val day: String, val list: List<Kite.Instrument>)
    @Volatile private var instrumentsMemo: InstrumentsMemo? = null

    /** The instruments file, parsed once per change of the file (it is large: parsing it on every review cost a few hundred ms). */
    private fun instrumentsFile(): InstrumentsMemo? {
        val f = File(app.filesDir, "kite_instruments.json")
        val stamp = f.lastModified() to f.length()
        instrumentsMemo?.let { if (it.stamp == stamp && stamp.first != 0L) return it }
        return runCatching {
            val o = JSONObject(f.readText())
            val a = o.getJSONArray("i")
            val list = (0 until a.length()).map { a.getJSONArray(it) }.map {
                Kite.Instrument(it.getLong(0), it.getString(1), it.getString(2), LocalDate.parse(it.getString(3)), it.getDouble(4),
                    it.getInt(5), if (it.getString(6) == "CE") Right.CE else Right.PE, it.getDouble(7))
            }
            InstrumentsMemo(stamp, o.optString("day"), list).also { instrumentsMemo = it }
        }.getOrNull()
    }

    /** The last instruments list fetched (any day), without touching the network; parsed again only when the file changes. */
    fun cachedInstruments(): List<Kite.Instrument>? = instrumentsFile()?.list

    /** NIFTY and BANKNIFTY options from GET /instruments/NFO, cached for the day. */
    suspend fun instruments(): List<Kite.Instrument> {
        val f = File(app.filesDir, "kite_instruments.json")
        instrumentsFile()?.takeIf { it.day == Market.today().toString() }?.let { return it.list }
        val text = call("GET", "/instruments/NFO", raw = true) as String
        val list = Kite.parseInstruments(text.lineSequence(), setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"))
        // The market recorder's index futures from the same dump (never a second download for them).
        runCatching { keepNfoFutures(text) }
        val a = JSONArray()
        list.forEach { a.put(JSONArray().put(it.token).put(it.tradingSymbol).put(it.name).put(it.expiry.toString()).put(it.strike).put(it.lotSize).put(it.right.name).put(it.tickSize)) }
        f.writeText(JSONObject().put("day", Market.today().toString()).put("i", a).toString())
        instrumentsMemo = InstrumentsMemo(f.lastModified() to f.length(), Market.today().toString(), list)
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

    /**
     * Counted in memory at once (the daily cap reads it from there), written to the vault in the background:
     * the Keystore encryption and two disk syncs no longer sit between Zerodha's answer and the fill check.
     */
    @Synchronized private fun countSent() = SecurePrefs.putAllSoon(mapOf(K_SENT_DAY to Market.today().toString(), K_SENT to sentToday() + 1))

    data class Fill(val orderId: String, val status: String, val avgPrice: Double, val filled: Int, val message: String)

    /** Sends ONE order. Callers must have passed Kite.refusals and the owner's confirmation. */
    suspend fun placeOrder(o: Kite.Order, exit: Boolean = false): String {
        // SEBI static IP: a new position is not opened from an IP Zerodha would refuse (exits always go).
        if (!exit) StaticIp.entryBlock()?.let { throw KiteError("static_ip", it) }
        val data = call("POST", "/orders/regular", o.formBody()) as JSONObject
        countSent()
        return data.getString("order_id")
    }

    /**
     * Waits between reads of an order's state: most options orders fill within a few hundred ms, so the
     * first second is read closely, then it backs off to Kite's pace (about 5 reads in the first second,
     * well inside its 10-a-second limit).
     */
    internal val POLL_MS = longArrayOf(150, 200, 250, 300, 400, 500, 750, 1_000)

    /**
     * Read an order until it is terminal or [timeoutMs] passes. The order's state always comes from Kite's
     * REST order history; an order update pushed on the live stream only wakes the next read early.
     */
    suspend fun awaitOrder(orderId: String, timeoutMs: Long = 20_000): Fill {
        val end = System.currentTimeMillis() + timeoutMs
        var last: JSONObject? = null
        var i = 0
        while (true) {
            val seen = KiteStream.orderEvents.value
            val hist = call("GET", "/orders/$orderId") as JSONArray
            if (hist.length() > 0) last = hist.getJSONObject(hist.length() - 1)
            val st = last?.optString("status") ?: ""
            if (st in setOf("COMPLETE", "REJECTED", "CANCELLED")) break
            val left = end - System.currentTimeMillis()
            if (left <= 0) break
            val wait = POLL_MS[minOf(i++, POLL_MS.size - 1)].coerceAtMost(left)
            kotlinx.coroutines.withTimeoutOrNull(wait) { KiteStream.orderEvents.first { it != seen } }
        }
        val o = last ?: return Fill(orderId, "UNKNOWN", 0.0, 0, "no order history yet")
        return Fill(orderId, o.optString("status"), o.optDouble("average_price", 0.0), o.optInt("filled_quantity"),
            // Kite sends "status_message": null for an open order, which optString reads as the text "null".
            o.optString("status_message", "").let { if (it == "null") "" else it })
    }

    /**
     * After a POST whose answer was lost (timeout, dropped connection), find the
     * order Kite may have placed anyway: same symbol, side, quantity and tag,
     * placed in the last three minutes, and not one of [known].
     */
    suspend fun findRecent(o: Kite.Order, known: Collection<String>): String? {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val rs = rows(call("GET", "/orders") as JSONArray)
        fun at(r: JSONObject) = runCatching { java.time.LocalDateTime.parse(r.optString("order_timestamp"), fmt) }.getOrNull()
        // "Now" by Kite's own clock when the book has a later order than the phone's clock says (a phone running
        // slow would otherwise widen the window); never earlier than the phone's.
        val now = rs.mapNotNull(::at).fold(java.time.LocalDateTime.now(IST)) { a, t -> if (t.isAfter(a)) t else a }
        val since = now.minusMinutes(3)
        return rs.lastOrNull {
            it.optString("tradingsymbol") == o.tradingSymbol && it.optString("transaction_type") == o.side.name &&
                it.optInt("quantity") == o.quantity && it.optString("tag") == o.tag.filter { c -> c.isLetterOrDigit() }.take(20) &&
                it.optString("order_id") !in known && at(it)?.isBefore(since) == false
        }?.optString("order_id")?.also { countSent() }
    }

    /**
     * True when [e] from [placeOrder] means the order was certainly NOT placed: Zerodha answered with a
     * refusal, or nothing was sent (not logged in). A lost reply (timeout, dropped connection, an
     * unreadable answer, Kite's own NetworkException) is not definite: the order may exist.
     */
    fun definite(e: Throwable): Boolean = e is NotLoggedIn || (e is KiteError && e.type != "NetworkException")

    /**
     * [findRecent] for a POST whose reply was lost, tried [tries] times [waitMs] apart (the order book can
     * trail the POST by a moment). The order id; null when the book was read and holds no such order;
     * throws when the book could not be read at all, so the caller knows the order's fate is unknown.
     */
    suspend fun findRecentRetrying(o: Kite.Order, known: Collection<String>, tries: Int = 3, waitMs: Long = 2_000): String? {
        var read = false
        var err: Exception? = null
        for (i in 0 until tries) {
            if (i > 0) delay(waitMs)
            try {
                findRecent(o, known)?.let { return it }
                read = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                err = e
            }
        }
        if (!read) throw err ?: IOException("Zerodha's order book could not be read")
        return null
    }

    /**
     * The newest of today's orders on [symbol] with [side] and [tag], placed at or after [since] (IST, Kite's
     * timestamp, with two minutes' allowance for the phone's clock) and not in [exclude]: an entry whose order
     * id was lost, found again.
     */
    suspend fun latestTagged(symbol: String, side: String, tag: String, since: java.time.LocalDateTime, exclude: Collection<String>): OrderRow? {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val from = since.minusMinutes(2)
        return orders().firstOrNull {
            it.symbol == symbol && it.side == side && it.tag == tag && it.id !in exclude &&
                runCatching { !java.time.LocalDateTime.parse(it.placedAt, fmt).isBefore(from) }.getOrDefault(false)
        }
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
    suspend fun basketMargin(orders: List<Kite.Order>): Margin = kotlinx.coroutines.coroutineScope {
        // The free margin is read alongside the basket, not after it.
        val free = async { funds() }
        val d = call("POST", "/margins/basket?consider_positions=true", Kite.basketJson(orders), json = true) as JSONObject
        val fin = d.optJSONObject("final")?.optDouble("total", Double.NaN) ?: Double.NaN
        val init = d.optJSONObject("initial")?.optDouble("total", Double.NaN) ?: Double.NaN
        val charges = rows(d.optJSONArray("orders")).sumOf { it.optJSONObject("charges")?.optDouble("total", 0.0) ?: 0.0 }
        Margin(if (fin.isNaN()) init else fin, init, free.await().net, charges)
    }

    /**
     * POST /charges/orders (Kite Connect's virtual contract note): Zerodha's own charges for orders already executed,
     * [body] being [com.optionslab.ira.ExactCharges.requestJson]. A read - it places, changes and cancels nothing - sent
     * as JSON the way [basketMargin] is. Its data array, as text ([com.optionslab.ira.ExactCharges.total] reads it).
     */
    suspend fun contractNote(body: String): String = (call("POST", "/charges/orders", body, json = true, readOnly = true) as JSONArray).toString()

    /**
     * The account as the guard judges it - positions, funds, today's order count - read in parallel (one
     * round trip instead of three). Null when the positions cannot be read; funds and orders are optional,
     * exactly as the sequential reads were.
     */
    suspend fun accountNow(): com.optionslab.engine.risk.AccountGuard.Account? = kotlinx.coroutines.coroutineScope {
        val bookQ = async { runCatching { positionBook() }.getOrNull() }
        val fundsQ = async { runCatching { funds() }.getOrNull() }
        val countQ = async { runCatching { orders().size }.getOrDefault(0) }
        bookQ.await()?.let { runCatching { Guard.liveAccount(it, fundsQ.await(), countQ.await()) }.getOrNull() }
    }

    /** One order's latest state (last entry of its history), or null if Kite has none yet. */
    suspend fun orderState(orderId: String): Fill? {
        val hist = call("GET", "/orders/${Kite.enc(orderId)}") as JSONArray
        if (hist.length() == 0) return null
        val o = hist.getJSONObject(hist.length() - 1)
        return Fill(orderId, o.optString("status"), o.optDouble("average_price", 0.0), o.optInt("filled_quantity"),
            // Kite sends "status_message": null for an open order, which optString reads as the text "null".
            o.optString("status_message", "").let { if (it == "null") "" else it })
    }

    suspend fun cancel(orderId: String, variety: String = "regular") {
        call("DELETE", "/orders/${Kite.enc(variety)}/${Kite.enc(orderId)}")
    }

    /** PUT /orders/{variety}/{id}. Callers re-check the order is still working and the owner confirmed. */
    suspend fun modify(order: OrderRow, quantity: Int, orderType: String, price: Double?, trigger: Double?) {
        call("PUT", "/orders/${Kite.enc(order.variety)}/${Kite.enc(order.id)}", Kite.modifyBody(quantity, orderType, price, trigger))
    }
}
