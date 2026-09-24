package com.optionslab.app.data

import android.util.JsonReader
import android.util.JsonToken
import com.optionslab.engine.Right
import com.optionslab.engine.Upstox
import kotlinx.coroutines.delay
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

    private fun open(url: String, timeoutMs: Int): HttpsURLConnection {
        val c = URL(url).openConnection() as? HttpsURLConnection ?: throw IOException("refusing a non-HTTPS request")
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = false
        c.useCaches = false
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        return c
    }

    /**
     * GET JSON with the PC harvester's retry policy: 401 is FATAL (the
     * unauthenticated route has been withdrawn - stop and re-plan), a 429 backs
     * off geometrically from 10 s, and transport errors retry briefly.
     */
    suspend fun getJson(url: String, tries: Int = 6, timeoutMs: Int = 45_000): JSONObject {
        var last: IOException = Offline()
        for (attempt in 0 until tries) {
            try {
                val c = open(url, timeoutMs)
                try {
                    val code = c.responseCode
                    if (code == 401) throw Upstox.UpstoxError(
                        "Upstox now requires authentication for historical candles. The unauthenticated route has been withdrawn - stop and re-plan.")
                    if (code == 429) {
                        last = HttpFailure(429)
                        delay(10_000L * (1L shl attempt))
                        continue
                    }
                    if (code != HttpURLConnection.HTTP_OK) throw HttpFailure(code)
                    return JSONObject(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
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
            delay(1_500L * (attempt + 1))
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

    suspend fun history(key: String, frm: LocalDate, to: LocalDate, tries: Int = 6): List<Upstox.Bar> =
        Upstox.monthChunks(frm, to).flatMap { (lo, hi) -> parseCandles(getJson(Upstox.candleUrl(key, hi, lo), tries)) }

    /**
     * The instrument master, streamed: the file is tens of megabytes of JSON,
     * so it is parsed token by token and only NIFTY/BANKNIFTY options are kept.
     */
    fun fetchMaster(underlyings: Set<String> = setOf("NIFTY", "BANKNIFTY")): List<Upstox.Contract> {
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
