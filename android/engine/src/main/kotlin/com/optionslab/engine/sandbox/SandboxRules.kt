package com.optionslab.engine.sandbox

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The sandbox's stateless rules: instrument classification, the stale-quote
 * guard, the session boundary, contract expiry, and the Decimal arithmetic the
 * Python does. Each is a port of one named Python function so a divergence can
 * be traced to its source.
 */
object SandboxRules {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    /** utils.constants.VALID_EXCHANGES, in its order (the refusal message lists them). */
    val VALID_EXCHANGES = listOf(
        "NSE", "NFO", "CDS", "BSE", "BFO", "BCD", "MCX", "NCDEX", "NCO",
        "NSE_INDEX", "BSE_INDEX", "MCX_INDEX", "GLOBAL_INDEX", "CRYPTO",
    )

    /** utils.constants.FNO_EXCHANGES - what is_option/is_future accept. Includes NCO. */
    val FNO_EXCHANGES = setOf("NFO", "BFO", "MCX", "CDS", "BCD", "NCDEX", "NCO", "CRYPTO")
    val CRYPTO_EXCHANGES = setOf("CRYPTO")

    /**
     * The derivative list the order manager and the expiry code hard-code.
     * Unlike FNO_EXCHANGES it has no NCO: an NCO order skips the lot-size
     * check and its expiry is never parsed from the symbol.
     */
    val LOT_EXCHANGES = setOf("NFO", "BFO", "CDS", "BCD", "MCX", "NCDEX", "CRYPTO")

    /** Cash segments: NRML refused. */
    val EQUITY_EXCHANGES = setOf("NSE", "BSE")

    /** Derivative segments: CNC refused. */
    val DERIVATIVE_EXCHANGES = setOf("NFO", "BFO", "MCX", "CDS", "BCD", "NCDEX", "CRYPTO")

    /**
     * Exchange close, for expiry-day settlement (position_manager.EXCHANGE_CLOSE_TIMES).
     * Distinct from the MIS square-off: an expiring contract trades to the bell,
     * and NFO/BFO trade to about 15:40 under the closing-auction session.
     */
    val EXCHANGE_CLOSE_TIMES: Map<String, LocalTime> = mapOf(
        "NFO" to LocalTime.of(15, 40), "BFO" to LocalTime.of(15, 40),
        "CDS" to LocalTime.of(17, 0), "BCD" to LocalTime.of(17, 0),
        "MCX" to LocalTime.of(23, 30), "NCDEX" to LocalTime.of(17, 0),
    )
    val DEFAULT_CLOSE_TIME: LocalTime = LocalTime.of(15, 30)

    /** MIS orders opening exposure are refused before this, as after square-off. */
    val MARKET_OPEN: LocalTime = LocalTime.of(9, 0)

    fun isOption(symbol: String, exchange: String): Boolean =
        exchange in FNO_EXCHANGES && (symbol.endsWith("CE") || symbol.endsWith("PE"))

    fun isFuture(symbol: String, exchange: String): Boolean = when (exchange) {
        in CRYPTO_EXCHANGES -> !(symbol.endsWith("CE") || symbol.endsWith("PE"))
        in FNO_EXCHANGES -> symbol.endsWith("FUT")
        else -> false
    }

    /**
     * quote_looks_stale (issue #1638): an LTP outside the quote's own day
     * range is the broker contradicting itself - typically a weeks-old last
     * trade under a current OHLC - so the fill is deferred, not made. With no
     * high/low there is nothing to cross-check and the quote is trusted: a
     * tick-built quote is live by construction. The range is inclusive.
     */
    fun quoteLooksStale(q: Quote): Boolean {
        if (!q.ltp.isFinite() || !q.high.isFinite() || !q.low.isFinite()) return false
        if (q.ltp <= 0 || q.high <= 0 || q.low <= 0) return false
        val ltp = pyDec(q.ltp); val high = pyDec(q.high); val low = pyDec(q.low)
        return !(low <= ltp && ltp <= high)
    }

    /**
     * "HH:MM" as the Python parses it (`map(int, s.split(":"))` then
     * `time(h, m)`): exactly two integer fields, in range; null otherwise.
     */
    fun parseHhMm(text: String): LocalTime? {
        val parts = text.split(":")
        if (parts.size != 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }

    /**
     * session_boundary.last_session_expiry_utc, in IST: the most recent
     * occurrence of the session expiry time at or before [now]. A malformed
     * value falls back to 03:00 rather than raising, because a raise there
     * silently skipped the MIS catch-up.
     */
    fun lastSessionExpiry(sessionExpiry: String, now: LocalDateTime): LocalDateTime {
        val t = parseHhMm(sessionExpiry) ?: LocalTime.of(3, 0)
        val today = now.toLocalDate().atTime(t)
        return if (!now.isBefore(today)) today else today.minusDays(1)
    }

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private val EXPIRY_IN_SYMBOL = Regex("""(\d{2})([A-Z]{3})(\d{2})""")

    /**
     * parse_expiry_from_symbol: the FIRST DDMMMYY in an F&O symbol
     * (NIFTY29SEP2625000CE -> 2026-09-29). A first match that is not a real
     * date gives null; the Python does not search further.
     */
    fun parseExpiryFromSymbol(symbol: String, exchange: String): LocalDate? {
        if (exchange !in LOT_EXCHANGES) return null
        val m = EXPIRY_IN_SYMBOL.find(symbol) ?: return null
        val month = MONTHS.indexOf(m.groupValues[2]) + 1
        if (month == 0) return null
        return runCatching { LocalDate.of(2000 + m.groupValues[3].toInt(), month, m.groupValues[1].toInt()) }.getOrNull()
    }

    /** get_contract_expiry: parsed from the symbol, else the symbol master's expiry. */
    fun contractExpiry(symbol: String, exchange: String, instrument: Instrument?): LocalDate? =
        parseExpiryFromSymbol(symbol, exchange) ?: instrument?.expiry

    /**
     * is_contract_expired_now: any day after expiry is expired; on expiry day
     * only from the exchange close, and only under "expiry_day_close".
     */
    fun isContractExpiredNow(expiry: LocalDate?, exchange: String, now: LocalDateTime, config: SandboxConfig): Boolean {
        if (expiry == null) return false
        val today = now.toLocalDate()
        if (today.isAfter(expiry)) return true
        if (today.isBefore(expiry)) return false
        if (config.expirySettlementTiming != "expiry_day_close") return false
        val close = EXCHANGE_CLOSE_TIMES[exchange] ?: DEFAULT_CLOSE_TIME
        return !now.toLocalTime().isBefore(close)
    }

    /**
     * fund_manager._get_leverage. Cash equity by product (NRML on NSE/BSE uses
     * the CNC leverage), then futures, then options by side; anything else 1x.
     */
    fun leverage(config: SandboxConfig, symbol: String, exchange: String, product: String, action: String?): BigDecimal = when {
        exchange in EQUITY_EXCHANGES -> if (product == "MIS") config.equityMisLeverage else config.equityCncLeverage
        isFuture(symbol, exchange) -> config.futuresLeverage
        isOption(symbol, exchange) -> if (action == "BUY") config.optionBuyLeverage else config.optionSellLeverage
        else -> BigDecimal.ONE
    }

    // ---- Decimal arithmetic, as Python's decimal module does it -------------

    /** Python's default decimal context: 28 significant digits, half-even. */
    internal val PY: MathContext = MathContext(28, RoundingMode.HALF_EVEN)

    /**
     * `Decimal(str(x))` for a float: the shortest repr, with Python's ".0" on
     * whole numbers. The digits matter only where a Decimal is printed into a
     * message ("Required: ₹50000.0"), but that is part of the API.
     */
    internal fun pyDec(x: Double): BigDecimal {
        val bd = BigDecimal(x.toString()).stripTrailingZeros()
        return if (bd.scale() <= 0) bd.setScale(1) else bd
    }

    internal fun div(a: BigDecimal, b: BigDecimal): BigDecimal = a.divide(b, PY)

    /**
     * What a stored Decimal reads back as: through a float, then "%.2f"
     * (half-even on the binary value), which is how SQLite and SQLAlchemy
     * round-trip the Python's DECIMAL columns.
     */
    internal fun store(x: BigDecimal, places: Int = 2): BigDecimal =
        BigDecimal(x.toDouble()).setScale(places, RoundingMode.HALF_EVEN)

    /** Python's `round(float, 2)` - half-even on the exact binary value. */
    internal fun round2(x: Double): Double = BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).toDouble()

    /** str(Decimal), which for these magnitudes is BigDecimal's plain form. */
    internal fun pyStr(x: BigDecimal): String = x.toString()

    internal fun ts(t: LocalDateTime): String =
        "%04d-%02d-%02d %02d:%02d:%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
}
