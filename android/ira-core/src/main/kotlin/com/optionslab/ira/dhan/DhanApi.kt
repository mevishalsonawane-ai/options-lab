package com.optionslab.ira.dhan

import com.optionslab.engine.strategy.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Dhan's READ-ONLY market-data API (api.dhan.co/v2), as IraAlgo uses it: candles, expired-option candles, the option chain,
 * its expiry list and the scrip master. Dhan is a data source here, NEVER a broker: no order, position, holding, funds,
 * margin, statement, trader-control or portfolio endpoint is reachable - every request the app sends goes through
 * [requireAllowed], which refuses any path outside [ALLOWED_PATHS] (tested), and the scrip master is the only file read
 * from [MASTER_URL]'s host.
 *
 * Pure: request bodies, response parsing, the retry wait and the token's expiry date are worked out here, so they are
 * tested on the JVM; the app only sends the bytes.
 */
object DhanApi {
    const val HOST = "api.dhan.co"
    const val BASE = "https://$HOST/v2"
    const val MASTER_URL = "https://images.dhan.co/api-data/api-scrip-master-detailed.csv"

    const val HISTORICAL = "/charts/historical"
    const val INTRADAY = "/charts/intraday"
    const val ROLLING_OPTION = "/charts/rollingoption"
    const val OPTION_CHAIN = "/optionchain"
    const val EXPIRY_LIST = "/optionchain/expirylist"
    const val LTP = "/marketfeed/ltp"
    const val OHLC = "/marketfeed/ohlc"
    const val QUOTE = "/marketfeed/quote"

    /** Every path the app may call on [BASE]: market data only. Nothing else is ever sent. */
    val ALLOWED_PATHS: Set<String> = setOf(HISTORICAL, INTRADAY, ROLLING_OPTION, OPTION_CHAIN, EXPIRY_LIST, LTP, OHLC, QUOTE)

    class NotAllowed : SecurityException("Dhan is used for market data only; that request is not allowed")

    /** Is [path] (on [BASE]) one of the data paths? Exact match only: no prefix, no query, no "..". */
    fun isAllowed(path: String): Boolean = path in ALLOWED_PATHS

    /** The full URL for [path], or [NotAllowed] when it is not a data path. */
    fun requireAllowed(path: String): String {
        if (!isAllowed(path)) throw NotAllowed()
        return BASE + path
    }

    /** Is [url] a URL the app may fetch: a data path on [BASE], or the scrip master itself. */
    fun urlAllowed(url: String): Boolean =
        url == MASTER_URL || (url.startsWith("$BASE/") && isAllowed(url.removePrefix(BASE)))

    // ---- request bodies -------------------------------------------------------------------------------------------

    /** Daily candles, [from] inclusive to [toExclusive]. */
    fun historicalBody(securityId: String, segment: String, instrument: String, from: LocalDate, toExclusive: LocalDate,
                       oi: Boolean = false, expiryCode: Int = 0): String = Json.write(linkedMapOf(
        "securityId" to securityId, "exchangeSegment" to segment, "instrument" to instrument,
        "expiryCode" to expiryCode, "oi" to oi, "fromDate" to from.toString(), "toDate" to toExclusive.toString()))

    /** One-minute candles, [from] to [toExclusive] (at most [Plan.MIN_WINDOW_DAYS] days a call). */
    fun intradayBody(securityId: String, segment: String, instrument: String, from: LocalDate, toExclusive: LocalDate,
                     oi: Boolean = false, interval: Int = 1): String = Json.write(linkedMapOf(
        "securityId" to securityId, "exchangeSegment" to segment, "instrument" to instrument, "interval" to interval,
        "oi" to oi, "fromDate" to "$from 09:15:00", "toDate" to "$toExclusive 09:15:00"))

    /** Fields asked of the expired-options endpoint. */
    val ROLLING_FIELDS = listOf("open", "high", "low", "close", "iv", "volume", "strike", "oi", "spot")

    /** "ATM", "ATM+3", "ATM-2". */
    fun strikeWord(offset: Int): String = when { offset == 0 -> "ATM"; offset > 0 -> "ATM+$offset"; else -> "ATM$offset" }

    /** Expired (rolling) option candles: the [offset]-th strike from the money of the [flag] contract, one side. */
    fun rollingBody(underlyingId: String, segment: String, instrument: String, flag: String, expiryCode: Int, offset: Int,
                    call: Boolean, from: LocalDate, toExclusive: LocalDate, interval: Int = 1): String = Json.write(linkedMapOf(
        "securityId" to (underlyingId.toLongOrNull() ?: underlyingId), "exchangeSegment" to segment, "instrument" to instrument,
        "expiryFlag" to flag, "expiryCode" to expiryCode, "strike" to strikeWord(offset), "drvOptionType" to if (call) "CALL" else "PUT",
        "requiredData" to ROLLING_FIELDS, "fromDate" to from.toString(), "toDate" to toExclusive.toString(), "interval" to interval))

    fun expiryListBody(underlyingId: String, segment: String): String =
        Json.write(linkedMapOf("UnderlyingScrip" to (underlyingId.toLongOrNull() ?: underlyingId), "UnderlyingSeg" to segment))

    fun chainBody(underlyingId: String, segment: String, expiry: LocalDate): String =
        Json.write(linkedMapOf("UnderlyingScrip" to (underlyingId.toLongOrNull() ?: underlyingId), "UnderlyingSeg" to segment, "Expiry" to expiry.toString()))

    // ---- responses --------------------------------------------------------------------------------------------------

    /** One candle: [t] epoch seconds; [oi] 0 where not served. */
    data class Candle(val t: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long, val oi: Long)

    /** One expired-option candle, with the strike it was and the index then. */
    data class OptionCandle(val t: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long,
                            val oi: Long, val iv: Double, val strike: Double, val spot: Double)

    @Suppress("UNCHECKED_CAST")
    private fun obj(text: String): Map<String, Any?> = (Json.parse(text) as? Map<String, Any?>) ?: emptyMap()

    private fun nums(m: Map<String, Any?>, vararg keys: String): List<Number?> {
        for (k in keys) (m[k] as? List<*>)?.let { l -> return l.map { it as? Number } }
        return emptyList()
    }

    /** The arrays of a candle reply ({open:[..], high:[..], ..., timestamp:[..]}, perhaps under "data"). */
    @Suppress("UNCHECKED_CAST")
    fun parseCandles(text: String): List<Candle> {
        if (text.isBlank()) return emptyList()
        val root = obj(text)
        val m = (root["data"] as? Map<String, Any?>)?.takeIf { it.containsKey("timestamp") } ?: root
        val t = nums(m, "timestamp", "start_Time")
        val o = nums(m, "open"); val h = nums(m, "high"); val l = nums(m, "low"); val c = nums(m, "close")
        val v = nums(m, "volume"); val oi = nums(m, "open_interest", "oi")
        val out = ArrayList<Candle>(t.size)
        for (i in t.indices) {
            val ts = t[i]?.toLong() ?: continue
            val close = c.getOrNull(i)?.toDouble() ?: continue
            out += Candle(ts, o.getOrNull(i)?.toDouble() ?: close, h.getOrNull(i)?.toDouble() ?: close, l.getOrNull(i)?.toDouble() ?: close,
                close, v.getOrNull(i)?.toLong() ?: 0L, oi.getOrNull(i)?.toLong() ?: 0L)
        }
        return out
    }

    /** The expired-option reply: {data: {ce: {...arrays}, pe: null}} - whichever side was asked. */
    @Suppress("UNCHECKED_CAST")
    fun parseRolling(text: String): List<OptionCandle> {
        if (text.isBlank()) return emptyList()
        val data = obj(text)["data"] as? Map<String, Any?> ?: return emptyList()
        val side = (data["ce"] as? Map<String, Any?>) ?: (data["pe"] as? Map<String, Any?>) ?: (data["CE"] as? Map<String, Any?>)
            ?: (data["PE"] as? Map<String, Any?>) ?: return emptyList()
        val t = nums(side, "timestamp")
        val o = nums(side, "open"); val h = nums(side, "high"); val l = nums(side, "low"); val c = nums(side, "close")
        val v = nums(side, "volume"); val oi = nums(side, "oi", "open_interest"); val iv = nums(side, "iv")
        val k = nums(side, "strike"); val s = nums(side, "spot")
        val out = ArrayList<OptionCandle>(t.size)
        for (i in t.indices) {
            val ts = t[i]?.toLong() ?: continue
            val close = c.getOrNull(i)?.toDouble() ?: continue
            val strike = k.getOrNull(i)?.toDouble() ?: continue
            out += OptionCandle(ts, o.getOrNull(i)?.toDouble() ?: close, h.getOrNull(i)?.toDouble() ?: close, l.getOrNull(i)?.toDouble() ?: close,
                close, v.getOrNull(i)?.toLong() ?: 0L, oi.getOrNull(i)?.toLong() ?: 0L, iv.getOrNull(i)?.toDouble() ?: 0.0,
                strike, s.getOrNull(i)?.toDouble() ?: 0.0)
        }
        return out
    }

    /** The dates of {data: ["2026-10-07", ...]}. */
    fun parseExpiries(text: String): List<LocalDate> {
        if (text.isBlank()) return emptyList()
        val l = obj(text)["data"] as? List<*> ?: return emptyList()
        return l.mapNotNull { runCatching { LocalDate.parse(it.toString().take(10)) }.getOrNull() }.distinct().sorted()
    }

    // ---- failures and waits -----------------------------------------------------------------------------------------

    enum class Failure {
        /** Try again after a wait ([backoffMs]): rate-limited, or the service is down. */
        RETRY,
        /** The token is wrong or has expired: stop the whole run, Boss renews it. */
        AUTH,
        /** Nothing for that range or instrument: the chunk is done, and empty. */
        NO_DATA,
        /** Refused for another reason (DH-905, an input error, among them): no file is written; counted failed, tried next run. */
        REFUSED,
    }

    /**
     * What an HTTP [code] with error [body] means. Dhan's error codes: DH-901 invalid or expired token, DH-902 no data
     * API subscription, DH-904 / 805 too many requests, DH-905 an input exception (a bad or unserved field - REFUSED: it
     * must not be stored as an empty "done" chunk), DH-907 no data for the input (NO_DATA).
     */
    fun classify(code: Int, body: String?): Failure {
        val b = body.orEmpty()
        return when {
            code == 429 || b.contains("DH-904") || b.contains("\"805\"") -> Failure.RETRY
            code in 500..599 -> Failure.RETRY
            code == 401 || code == 403 || b.contains("DH-901") || b.contains("DH-902") -> Failure.AUTH
            b.contains("DH-905") -> Failure.REFUSED
            b.contains("DH-907") || code == 404 -> Failure.NO_DATA
            else -> Failure.REFUSED
        }
    }

    /** The longest wait between attempts. */
    const val MAX_WAIT_MS = 120_000L
    /** Attempts at one request before it counts as failed for this run. */
    const val MAX_ATTEMPTS = 6

    /** Exponential backoff for attempt [attempt] (0-based), honouring a Retry-After in seconds; [jitter] in 0..1. */
    fun backoffMs(attempt: Int, retryAfterSec: Long? = null, jitter: Double = 0.0): Long {
        val base = (1_000L shl attempt.coerceIn(0, 7)).coerceAtMost(MAX_WAIT_MS)
        val server = retryAfterSec?.takeIf { it >= 0 }?.let { (it * 1_000L).coerceAtMost(MAX_WAIT_MS) } ?: 0L
        return (maxOf(base, server) + (base * jitter.coerceIn(0.0, 1.0) / 4).toLong()).coerceAtMost(MAX_WAIT_MS)
    }

    /** Dhan's data API allows about 5 requests a second; the app keeps under it. */
    const val DATA_GAP_MS = 250L
    /** The option chain allows one request every 3 seconds. */
    const val CHAIN_GAP_MS = 3_100L

    // ---- the access token ------------------------------------------------------------------------------------------

    /**
     * The date (UTC) the access token stops working, read from its JWT "exp" claim; null when it is not a JWT. Only the
     * date is ever shown; the token itself is never decoded anywhere else, logged or shown.
     */
    fun tokenExpiry(token: String): LocalDate? = runCatching {
        val parts = token.trim().split('.')
        if (parts.size < 2) return null
        val payload = String(java.util.Base64.getUrlDecoder().decode(parts[1].trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) }), Charsets.UTF_8)
        val exp = (obj(payload)["exp"] as? Number)?.toLong() ?: return null
        Instant.ofEpochSecond(exp).atZone(ZoneOffset.UTC).toLocalDate()
    }.getOrNull()

    /** Does [token] look like something to send (non-blank, one line, no spaces)? It is never otherwise inspected. */
    fun tokenShapeOk(token: String): Boolean = token.isNotBlank() && token.length in 20..4096 && token.none { it.isWhitespace() }

    /** A client ID: digits only. */
    fun clientIdOk(id: String): Boolean = id.length in 4..20 && id.all { it.isDigit() }
}
