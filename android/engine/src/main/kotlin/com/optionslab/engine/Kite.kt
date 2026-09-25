package com.optionslab.engine

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Zerodha Kite Connect: the parts that decide correctness, kept pure so they
 * are tested here rather than discovered on a live account.
 *
 * Port of Trading_app/nifty_trading_bot/zerodha (kite_login.py, kite_adapter.py).
 */
object Kite {
    const val API = "https://api.kite.trade"
    const val LOGIN_HOST = "kite.zerodha.com"

    fun loginUrl(apiKey: String): String = "https://$LOGIN_HOST/connect/login?v=3&api_key=${enc(apiKey)}"

    /** SHA-256(api_key + request_token + api_secret), hex - as kite_login.exchange_token. */
    fun checksum(apiKey: String, requestToken: String, apiSecret: String): String =
        MessageDigest.getInstance("SHA-256").digest((apiKey + requestToken + apiSecret).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    sealed interface Redirect {
        data object NotOurs : Redirect
        data class Token(val requestToken: String) : Redirect
        data class Refused(val why: String) : Redirect
    }

    /**
     * Is [url] the redirect Zerodha sends after login, and what does it carry?
     * Matched on scheme, host, port and path prefix of the REGISTERED redirect,
     * never by substring - so a page cannot smuggle "request_token=" into a
     * query and be mistaken for the callback.
     */
    fun readRedirect(url: String, registered: String): Redirect {
        val u = runCatching { URI(url) }.getOrNull() ?: return Redirect.NotOurs
        val r = runCatching { URI(registered.trim()) }.getOrNull() ?: return Redirect.NotOurs
        fun port(x: URI) = if (x.port != -1) x.port else if (x.scheme.equals("https", true)) 443 else 80
        if (!u.scheme.equals(r.scheme, true) || !u.host.equals(r.host, true) || port(u) != port(r)) return Redirect.NotOurs
        val base = (r.path ?: "").ifEmpty { "/" }
        if (!(u.path ?: "/").ifEmpty { "/" }.startsWith(base)) return Redirect.NotOurs
        val q = query(u.rawQuery)
        val status = q["status"]
        val token = q["request_token"]
        return when {
            status != null && status != "success" -> Redirect.Refused("Zerodha returned status '$status'")
            token.isNullOrBlank() -> Redirect.Refused("the redirect carried no request_token")
            !token.all { it.isLetterOrDigit() } -> Redirect.Refused("the request_token is malformed")
            else -> Redirect.Token(token)
        }
    }

    fun query(raw: String?): Map<String, String> =
        (raw ?: "").split("&").filter { "=" in it }.associate {
            val (k, v) = it.split("=", limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun form(fields: List<Pair<String, String?>>): String =
        fields.filter { it.second != null }.joinToString("&") { "${enc(it.first)}=${enc(it.second!!)}" }

    /**
     * Kite access tokens are not refreshable for individual developers: they
     * expire at about 06:00 IST the next day, whatever time the login was.
     */
    fun expiresAt(loginAt: ZonedDateTime): ZonedDateTime {
        val ist = loginAt.withZoneSameInstant(IST)
        val six = ist.toLocalDate().atTime(6, 0).atZone(IST)
        return if (ist.isBefore(six)) six else six.plusDays(1)
    }

    // ---- instruments -----------------------------------------------------------

    data class Instrument(
        val token: Long, val tradingSymbol: String, val name: String, val expiry: LocalDate,
        val strike: Double, val lotSize: Int, val right: Right, val tickSize: Double,
    )

    /**
     * Rows of GET /instruments/NFO for the requested underlyings' options.
     * Columns: instrument_token,exchange_token,tradingsymbol,name,last_price,
     * expiry,strike,tick_size,lot_size,instrument_type,segment,exchange
     */
    fun parseInstruments(lines: Sequence<String>, underlyings: Set<String>): List<Instrument> {
        val out = ArrayList<Instrument>()
        var head: Map<String, Int>? = null
        for (line in lines) {
            if (line.isBlank()) continue
            val c = splitCsv(line)
            if (head == null) { head = c.withIndex().associate { it.value.trim() to it.index }; continue }
            fun col(n: String) = c.getOrNull(head.getValue(n))?.trim() ?: ""
            val type = col("instrument_type")
            val name = col("name")
            if ((type != "CE" && type != "PE") || name !in underlyings || col("segment") != "NFO-OPT") continue
            out += Instrument(col("instrument_token").toLong(), col("tradingsymbol"), name, LocalDate.parse(col("expiry")),
                col("strike").toDouble(), col("lot_size").toDouble().toInt(), if (type == "CE") Right.CE else Right.PE,
                col("tick_size").toDoubleOrNull() ?: 0.05)
        }
        return out
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        for (ch in line) when {
            ch == '"' -> quoted = !quoted
            ch == ',' && !quoted -> { out += sb.toString(); sb.setLength(0) }
            else -> sb.append(ch)
        }
        out += sb.toString()
        return out
    }

    // ---- orders and their safety gates ----------------------------------------

    enum class Side { BUY, SELL }

    data class Order(
        val tradingSymbol: String,
        val side: Side,
        val quantity: Int,
        val lotSize: Int,
        val product: String,         // NRML | MIS
        val orderType: String,       // LIMIT | MARKET
        val price: Double?,          // required for LIMIT
        val tickSize: Double = 0.05,
        val exchange: String = "NFO",
        val tag: String = "iraalgo",
    ) {
        val lots: Int get() = if (lotSize > 0) quantity / lotSize else 0

        /** The form body of POST /orders/regular. */
        fun formBody(): String = form(listOf(
            "tradingsymbol" to tradingSymbol, "exchange" to exchange, "transaction_type" to side.name,
            "order_type" to orderType, "quantity" to quantity.toString(), "product" to product,
            "price" to if (orderType == "LIMIT") "%.2f".format(java.util.Locale.ROOT, price!!) else null,
            // Exchange rules now demand protection on market orders; -1 lets Kite choose it.
            "market_protection" to if (orderType == "MARKET") "-1" else null,
            "validity" to "DAY", "tag" to tag.filter { it.isLetterOrDigit() }.take(20),
        ))
    }

    data class Limits(
        val maxOrdersPerDay: Int = 4,
        val maxLotsPerOrder: Int = Sizing.MAX_LOTS_ON_DEPTH,
        val maxOrderValue: Double = 500_000.0,   // kite_adapter.py default
    )

    /**
     * Every reason this order must not be sent (empty = may be sent). The
     * kite_adapter.py gates - daily count, order value, sanity - plus the ones
     * an options order needs: whole lots, a price on the tick grid, and the
     * product that survives to settlement.
     */
    fun refusals(o: Order, limits: Limits, sentToday: Int, holdToSettlement: Boolean): List<String> {
        val out = ArrayList<String>()
        if (o.tradingSymbol.isBlank()) out += "no trading symbol"
        if (o.quantity <= 0) out += "quantity must be positive, not ${o.quantity}"
        if (o.lotSize <= 0 || o.quantity % o.lotSize != 0) out += "quantity ${o.quantity} is not a whole number of lots of ${o.lotSize}"
        if (o.lots > limits.maxLotsPerOrder) out += "${o.lots} lots exceeds the ${limits.maxLotsPerOrder}-lot cap (median top-of-book depth is 2 lots)"
        if (sentToday >= limits.maxOrdersPerDay) out += "daily order limit reached (${limits.maxOrdersPerDay})"
        if (o.orderType !in setOf("LIMIT", "MARKET")) out += "order type ${o.orderType} is not supported"
        if (o.orderType == "LIMIT") {
            val p = o.price
            if (p == null || p <= 0) out += "a LIMIT order needs a positive price"
            else {
                val ticks = p / o.tickSize
                if (kotlin.math.abs(ticks - Math.round(ticks)) > 1e-6) out += "price %.2f is not on the %.2f tick".format(p, o.tickSize)
                if (p * o.quantity > limits.maxOrderValue) out += "order value Rs %,.0f exceeds the Rs %,.0f cap".format(p * o.quantity, limits.maxOrderValue)
            }
        }
        if (o.product !in setOf("NRML", "MIS")) out += "product ${o.product} is not supported"
        if (holdToSettlement && o.product == "MIS") out += "MIS is squared off by the broker before the close; this strategy holds to settlement, so it must be NRML"
        return out
    }

    /** Round a price to the instrument's tick, toward the side that fills. */
    fun onTick(price: Double, tick: Double, side: Side): Double {
        val t = price / tick
        val n = if (side == Side.SELL) kotlin.math.floor(t + 1e-9) else kotlin.math.ceil(t - 1e-9)
        return Math.round(n * tick * 100) / 100.0
    }

    /**
     * The legs to send for a ticket. A hedged ticket BUYS the wing first: the
     * short leg is then margined as a spread, and if the second order fails
     * the account holds a long put (bounded), never a naked short.
     */
    fun legsFor(ticket: Live.Ticket, short: Instrument, wing: Instrument?, product: String, shortPrice: Double, wingPrice: Double?): List<Order> {
        val legs = ArrayList<Order>()
        if (ticket.wingStrike != null) {
            requireNotNull(wing) { "hedged ticket but no wing instrument" }
            legs += Order(wing.tradingSymbol, Side.BUY, ticket.qty, wing.lotSize, product, "LIMIT",
                onTick(wingPrice ?: ticket.wingDebit ?: 0.0, wing.tickSize, Side.BUY), wing.tickSize)
        }
        legs += Order(short.tradingSymbol, Side.SELL, ticket.qty, short.lotSize, product, "LIMIT",
            onTick(shortPrice, short.tickSize, Side.SELL), short.tickSize)
        return legs
    }
}
