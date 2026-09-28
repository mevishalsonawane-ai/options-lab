package com.optionslab.app.data

import android.util.JsonReader
import android.util.JsonToken
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

/**
 * The only code that touches the network, and it talks to Upstox alone.
 *
 * NOTHING HERE IS LOGGED, and no error message carries a URL, an instrument
 * key or a response body: failures surface as a short, generic sentence. The
 * app holds no credentials, so there is no token to leak - and the request
 * pattern (which contracts you are watching) is private too.
 */
object Net {
    private const val UA = "Mozilla/5.0"

    class HttpFailure(val code: Int) : IOException(
        when (code) {
            429 -> "Market data is rate-limiting requests; try again in a few minutes"
            in 500..599 -> "Market data service is unavailable ($code)"
            else -> "Market data request was refused ($code)"
        })

    class Offline : IOException("No connection to the market data service")

    /**
     * TEST SEAM (JVM tests only): send every request to a local fake Upstox server (its base URL
     * replaces the scheme and host) trusted through [TestEndpoint.ssl]. Its setter throws unless
     * BuildConfig.DEBUG and no app code sets it; when null (always, in the app) requests go to
     * Upstox exactly as before. Timeouts, retries and error mapping run unchanged against the fake.
     */
    internal class TestEndpoint(val base: String, val ssl: javax.net.ssl.SSLSocketFactory)
    @Volatile internal var testEndpoint: TestEndpoint? = null
        set(v) {
            check(com.optionslab.app.BuildConfig.DEBUG) { "the test endpoint exists only in debug builds" }
            field = v
        }

    private fun open(url: String, timeoutMs: Int, connectMs: Int = timeoutMs): HttpsURLConnection {
        val test = testEndpoint
        val target = if (test != null) test.base + url.replaceFirst(Regex("^https://[^/]+"), "") else url
        val c = URL(target).openConnection() as? HttpsURLConnection ?: throw IOException("refusing a non-HTTPS request")
        if (test != null) c.sslSocketFactory = test.ssl
        c.connectTimeout = connectMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = false
        c.useCaches = false
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        return c
    }

    /** The longest any one wait between attempts lasts, a server-sent Retry-After included. */
    private const val MAX_WAIT_MS = 160_000L

    /** Short timeouts for a caller on a deadline (the market watch): a dead feed fails in seconds, not minutes. */
    const val QUICK_CONNECT_MS = 5_000
    const val QUICK_READ_MS = 10_000

    /** Retry-After in delta-seconds, in ms (at least a second); an HTTP-date or anything malformed is ignored. */
    private fun retryAfterMs(header: String?): Long? =
        header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { (it * 1_000L).coerceIn(1_000L, MAX_WAIT_MS) }

    private class Reply(val code: Int, val retryAfter: String?, val body: ByteArray?)

    /**
     * The blocking exchange, made cancellable: cancelling the caller (a withTimeoutOrNull deadline)
     * interrupts the thread and closes the connection, so a hung read ends at once instead of
     * running out its read timeout.
     */
    private suspend fun exchange(c: HttpsURLConnection): Reply = kotlinx.coroutines.coroutineScope {
        val closer = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            try { kotlinx.coroutines.awaitCancellation() } finally { c.disconnect() }
        }
        try {
            kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                val code = c.responseCode
                Reply(code, c.getHeaderField("Retry-After"), if (code == HttpURLConnection.HTTP_OK) c.inputStream.use { it.readBytes() } else null)
            }
        } finally {
            closer.cancel()
        }
    }

    /**
     * GET JSON with the PC harvester's retry policy: 401 is FATAL (the
     * unauthenticated route has been withdrawn - stop and re-plan), a 429 waits
     * the longer of Retry-After and a geometric backoff from 10 s (capped), and
     * transport errors retry briefly. Nothing waits after the last attempt.
     * [connectMs] defaults to [timeoutMs]; a caller on a deadline passes the QUICK_ pair.
     */
    suspend fun getJson(url: String, tries: Int = 6, timeoutMs: Int = 45_000, connectMs: Int = timeoutMs): JSONObject {
        var last: IOException = Offline()
        for (attempt in 0 until tries) {
            var wait = 1_500L * (attempt + 1)
            try {
                val c = open(url, timeoutMs, connectMs)
                try {
                    val r = exchange(c)
                    if (r.code == 401) throw Upstox.UpstoxError(
                        "Upstox now requires authentication for historical candles. The unauthenticated route has been withdrawn - stop and re-plan.")
                    if (r.code == 429) {
                        last = HttpFailure(429)
                        wait = maxOf(retryAfterMs(r.retryAfter) ?: 0L, 10_000L * (1L shl attempt.coerceAtMost(8))).coerceAtMost(MAX_WAIT_MS)
                    } else {
                        if (r.code != HttpURLConnection.HTTP_OK) throw HttpFailure(r.code)
                        return JSONObject(r.body!!.toString(Charsets.UTF_8))
                    }
                } finally {
                    c.disconnect()
                }
            } catch (e: Upstox.UpstoxError) {
                throw e
            } catch (e: HttpFailure) {
                if (e.code in 400..499) throw e
                last = e
            } catch (_: IOException) {
                last = Offline()
            } catch (_: org.json.JSONException) {
                last = IOException("Market data returned something that is not JSON")
            }
            // A read ended by cancellation (the socket closed under it) is not a failure to retry.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (attempt < tries - 1) delay(wait)
        }
        throw last
    }

    /** Upstox returns candles newest-first; they are stored ascending. Errors are never "empty". */
    fun parseCandles(payload: JSONObject): List<Upstox.Bar> {
        if (payload.optString("status") != "success") {
            val errs = payload.optJSONArray("errors")
            val detail = if (errs != null && errs.length() > 0)
                (0 until errs.length()).joinToString("; ") { i ->
                    val e = errs.optJSONObject(i); "${e?.optString("errorCode", "?")}: ${e?.optString("message", "")}"
                } else "unknown Upstox error"
            throw Upstox.UpstoxError(detail)
        }
        val rows = payload.optJSONObject("data")?.optJSONArray("candles") ?: return emptyList()
        val out = ArrayList<Upstox.Bar>(rows.length())
        for (i in 0 until rows.length()) {
            val r = rows.getJSONArray(i)
            out += Upstox.Bar(
                epochSecond = OffsetDateTime.parse(r.getString(0)).toEpochSecond(),
                open = r.getDouble(1), high = r.getDouble(2), low = r.getDouble(3), close = r.getDouble(4),
                volume = r.getLong(5), oi = if (r.length() > 6) r.optLong(6, 0L) else 0L,
            )
        }
        out.sortBy { it.epochSecond }
        return out
    }

    suspend fun intraday(key: String, tries: Int = 3): List<Upstox.Bar> = parseCandles(getJson(Upstox.intradayUrl(key), tries))

    /** Today's candles for a caller on a deadline: one retry at most, short timeouts. */
    suspend fun intradayQuick(key: String): List<Upstox.Bar> =
        parseCandles(getJson(Upstox.intradayUrl(key), tries = 2, timeoutMs = QUICK_READ_MS, connectMs = QUICK_CONNECT_MS))

    suspend fun history(key: String, frm: LocalDate, to: LocalDate, tries: Int = 6): List<Upstox.Bar> =
        Upstox.monthChunks(frm, to).flatMap { (lo, hi) -> parseCandles(getJson(Upstox.candleUrl(key, hi, lo), tries)) }

    /**
     * The instrument master, streamed: the file is tens of megabytes of JSON,
     * so it is parsed token by token and only NIFTY/BANKNIFTY options are kept.
     */
    fun fetchMaster(underlyings: Set<String> = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")): List<Upstox.Contract> {
        val c = open(Upstox.MASTER_URL, 300_000)
        c.setRequestProperty("Accept", "*/*")
        try {
            if (c.responseCode != 200) throw HttpFailure(c.responseCode)
            return c.inputStream.use { parseMaster(GZIPInputStream(it, 1 shl 16), underlyings) }
        } catch (e: HttpFailure) {
            throw e
        } catch (_: IOException) {
            throw Offline()
        } finally {
            c.disconnect()
        }
    }

    /**
     * Instrument keys for cash-market symbols ("NSE_EQ" to "RELIANCE"), from
     * one streamed pass over the master. Missing symbols are simply absent.
     */
    fun fetchEquityKeys(wanted: Set<Pair<String, String>>): Map<Pair<String, String>, String> {
        if (wanted.isEmpty()) return emptyMap()
        val c = open(Upstox.MASTER_URL, 300_000)
        c.setRequestProperty("Accept", "*/*")
        try {
            if (c.responseCode != 200) throw HttpFailure(c.responseCode)
            val out = HashMap<Pair<String, String>, String>()
            c.inputStream.use { raw ->
                JsonReader(InputStreamReader(GZIPInputStream(raw, 1 shl 16), Charsets.UTF_8)).use { r ->
                    r.beginArray()
                    while (r.hasNext() && out.size < wanted.size) {
                        var segment: String? = null; var sym: String? = null; var key: String? = null
                        r.beginObject()
                        while (r.hasNext()) {
                            val name = r.nextName()
                            if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
                            when (name) {
                                "segment" -> segment = r.nextString()
                                "trading_symbol" -> sym = r.nextString()
                                "instrument_key" -> key = r.nextString()
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        if (segment != null && sym != null && key != null && (segment to sym) in wanted) out[segment to sym] = key
                    }
                }
            }
            return out
        } catch (e: HttpFailure) {
            throw e
        } catch (_: IOException) {
            throw Offline()
        } finally {
            c.disconnect()
        }
    }

    /** Daily candles, in windows Upstox accepts (a decade each). */
    suspend fun daily(key: String, frm: LocalDate, to: LocalDate, tries: Int = 4): List<Upstox.Bar> {
        val out = ArrayList<Upstox.Bar>()
        var lo = frm
        while (!lo.isAfter(to)) {
            val hi = minOf(lo.plusDays(3600), to)
            out += parseCandles(getJson("${Upstox.BASE}/${Upstox.quote(key)}/days/1/$hi/$lo", tries))
            lo = hi.plusDays(1)
        }
        return out.distinctBy { it.epochSecond }.sortedBy { it.epochSecond }
    }

    fun parseMaster(input: InputStream, underlyings: Set<String>): List<Upstox.Contract> {
        val out = ArrayList<Upstox.Contract>()
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.beginArray()
            while (r.hasNext()) {
                var segment: String? = null; var type: String? = null; var und: String? = null; var asset: String? = null
                var expiry = 0L; var strike = 0.0; var lot = 0; var key: String? = null; var sym: String? = null
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
                    when (name) {
                        "segment" -> segment = r.nextString()
                        "instrument_type" -> type = r.nextString()
                        "underlying_symbol" -> und = r.nextString()
                        "asset_symbol" -> asset = r.nextString()
                        "expiry" -> expiry = r.nextLong()
                        "strike_price" -> strike = r.nextDouble()
                        "lot_size" -> lot = r.nextInt()
                        "instrument_key" -> key = r.nextString()
                        "trading_symbol" -> sym = r.nextString()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                val u = und ?: asset
                if (segment == "NSE_FO" && (type == "CE" || type == "PE") && u in underlyings && key != null) {
                    out += Upstox.Contract(
                        underlying = u!!,
                        expiry = Instant.ofEpochMilli(expiry).atZone(com.optionslab.engine.IST).toLocalDate(),
                        strike = strike, right = if (type == "CE") Right.CE else Right.PE, lotSize = lot,
                        instrumentKey = key, tradingSymbol = sym ?: "",
                    )
                }
            }
            r.endArray()
        }
        return out
    }
}
