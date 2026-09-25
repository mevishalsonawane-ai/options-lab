package com.optionslab.engine.strategy

import java.time.LocalDate

/**
 * One row of the broker's master contract (IraAlgo's `SymToken`): the thing
 * that says a contract exists, its lot size and its tick size.
 *
 * [expiry] is in the database spelling `DD-MMM-YY` ("28-APR-26"); a symbol
 * embeds `DDMMMYY` ("NIFTY28APR2624000CE"). [instrumentType] is the master
 * contract's own code: `CE`/`PE` for options, `FUT`/`FUTIDX`/`FUTSTK`/... for
 * futures, `EQ` or blank for cash.
 */
data class Instrument(
    val symbol: String,
    val exchange: String,
    val name: String? = null,
    val expiry: String? = null,
    val strike: Double? = null,
    val lotsize: Int? = null,
    val instrumentType: String? = null,
    val tickSize: Double? = null,
)

/**
 * The master-contract queries the resolver and the signal path make, answered
 * from an in-memory instrument list instead of a database. Ports of
 * `symbol_resolver.lot_size_for` / `contract_exists` / `resolve_quantity` /
 * `quantity_is_whole_lots`, `expiry_service.get_expiry_dates`, and
 * `option_symbol_service.get_available_strikes` / `find_option_in_database` /
 * `find_near_month_futures` / `resolve_underlying_quote`.
 *
 * Nothing here falls back to a plausible substitute. A wrong lot size or a
 * neighbouring strike is a real position, so "cannot say" is null, and every
 * caller treats it as such.
 *
 * Row order matters where the Python takes `.first()`: the list is read in
 * order, as SQLite returns rows in insertion order.
 */
class MasterContract(val instruments: List<Instrument>) {
    private val bySymbol: Map<Pair<String, String>, Instrument> =
        instruments.asReversed().associateBy { it.symbol to it.exchange }

    /** The exact row for a symbol on an exchange, or null. */
    fun find(symbol: String, exchange: String): Instrument? = bySymbol[symbol to exchange]

    /**
     * The contract lot size for an underlying on an exchange, or null.
     *
     * Matched on `name` first, then on the symbol prefix - anchored so the
     * character after the root is a digit (or the row is the root itself). An
     * unanchored prefix let GOLD borrow GOLDM's lot size, and the user's lot
     * count was then multiplied by a neighbour's number.
     *
     * Null for a non-derivative exchange (cash trades in units), a master
     * contract not yet downloaded, or no match. Null means "cannot say", never
     * "any quantity is fine".
     */
    fun lotSizeFor(symbol: String?, exchange: String?): Int? {
        if (symbol.isNullOrEmpty() || exchange.isNullOrEmpty()) return null
        val venue = exchange.uppercase()
        if (venue !in DERIVATIVE_EXCHANGES) return null
        val root = symbol.uppercase()
        instruments.firstOrNull { it.name == root && it.exchange == venue && (it.lotsize ?: 0) > 0 }?.let { return it.lotsize }
        for (row in instruments.asSequence().filter { likePrefix(it.symbol, root) && it.exchange == venue && (it.lotsize ?: 0) > 0 }.take(200)) {
            val tail = row.symbol.substring(root.length)
            if (tail.isEmpty() || tail[0].isDigit()) return row.lotsize
        }
        return null
    }

    /**
     * Whether the master contract lists this exact symbol. Answers true when
     * the venue has no rows at all: refusing every order because the contract
     * has not been downloaded would be worse than the defect it guards.
     */
    fun contractExists(symbol: String?, exchange: String?): Boolean {
        if (symbol.isNullOrEmpty() || exchange.isNullOrEmpty()) return false
        val name = symbol.uppercase()
        val venue = exchange.uppercase()
        if (find(name, venue) != null) return true
        return instruments.none { it.exchange == venue }
    }

    /** `(quantity, lotSize, error)`. */
    data class Quantity(val quantity: Int?, val lotSize: Int?, val error: String?)

    /**
     * Turn a configured quantity into what the broker is sent: `lots` means
     * `value * lotSize`, `units` means the value itself (still required to land
     * on a lot boundary on a derivative). Storing lots is what survives a
     * lot-size revision: NIFTY went 75 -> 65. An unknown lot size in `lots`
     * mode is an error, never a guess, because it would fabricate an order size.
     */
    fun resolveQuantity(value: Any?, qtyMode: String, symbol: String, exchange: String): Quantity {
        val count: Long = when (value) {
            is Boolean -> if (value) 1 else 0
            is Long -> value
            is Int -> value.toLong()
            is Double -> if (value.isFinite()) value.toLong() else return Quantity(null, null, "${Py.repr(value)} is not a whole number")
            is String -> Py.parseInt(value) ?: return Quantity(null, null, "${Py.repr(value)} is not a whole number")
            else -> return Quantity(null, null, "${Py.repr(value)} is not a whole number")
        }
        if (count <= 0) return Quantity(null, null, "Quantity must be greater than zero")
        val lotSize = lotSizeFor(symbol, exchange)
        if (qtyMode != "lots") {
            if (lotSize != null && lotSize != 0 && count % lotSize != 0L) {
                return Quantity(null, lotSize, "$count is not a whole number of lots; $symbol trades in lots of $lotSize")
            }
            return Quantity(count.toInt(), lotSize, null)
        }
        if (lotSize == null || lotSize == 0) {
            return Quantity(
                null, null,
                "No lot size is known for $symbol on $exchange. Download the master contract, or set the quantity in units.",
            )
        }
        return Quantity((count * lotSize).toInt(), lotSize, null)
    }

    /** Whether a quantity is whole lots; true when the lot size cannot be determined. */
    fun quantityIsWholeLots(quantity: Long, symbol: String, exchange: String): Pair<Boolean, Int?> {
        val lotSize = lotSizeFor(symbol, exchange)
        if (lotSize == null || lotSize == 0) return true to null
        return (quantity > 0 && quantity % lotSize == 0L) to lotSize
    }

    /**
     * Live expiries for an underlying, sorted, expired ones dropped: port of
     * `expiry_service.get_expiry_dates`. Returns null plus a message where the
     * Python answers a failure (bad instrument type or exchange).
     */
    fun expiryDates(symbol: String, exchange: String, instrumentType: String, today: LocalDate): Pair<List<String>?, String?> {
        if (symbol.isBlank()) return null to "Symbol parameter is required and cannot be empty"
        if (exchange.isBlank()) return null to "Exchange parameter is required and cannot be empty"
        val type = instrumentType.trim().lowercase()
        if (type !in setOf("futures", "options")) return null to "Instrumenttype must be either \"futures\" or \"options\""
        if (exchange.uppercase() !in EXPIRY_EXCHANGES) return null to "Exchange must be one of: ${EXPIRY_EXCHANGES.joinToString(", ")}"
        val sym = symbol.trim().uppercase()
        val exch = exchange.trim().uppercase()
        val types: Set<String>? = when (type) {
            "futures" -> when (exch) {
                "NFO", "BFO" -> setOf("FUTSTK", "FUTIDX", "FUT")
                "MCX", "NCDEX" -> setOf("FUTCOM", "FUTENR", "FUT")
                "CDS", "BCD" -> setOf("FUTCUR", "FUTIRC", "FUT")
                "CRYPTO" -> setOf("FUT", "PERPFUT")
                else -> null
            }
            else -> when (exch) {
                "NFO", "BFO" -> setOf("OPTSTK", "OPTIDX", "CE", "PE")
                "MCX", "NCDEX" -> setOf("OPTFUT", "CE", "PE")
                "CDS", "BCD" -> setOf("OPTCUR", "OPTIRC", "CE", "PE")
                "CRYPTO" -> setOf("CE", "PE")
                else -> null
            }
        }
        val rows = instruments.filter {
            likePrefix(it.symbol, sym) && it.exchange == exch && !it.expiry.isNullOrEmpty() &&
                (types == null || it.instrumentType in types)
        }
        if (rows.isEmpty()) return emptyList<String>() to null

        val primary = if (type == "futures") "^$sym[0-9]{2}[A-Z]{3}[0-9]{2}(FUT)?" else "^$sym[0-9]{2}[A-Z]{3}[0-9]{2}"
        var found = matching(rows, primary)
        if (found.isEmpty()) {
            val alternatives = if (type == "futures") listOf(
                "^$sym[0-9]{2}[A-Z]{3}[0-9]{2}FUT", "^$sym[0-9]{2}[A-Z]{3}[0-9]{2}", "^$sym[0-9]{2}[A-Z]{3}FUT",
                "^$sym[0-9]{4}[A-Z]{3}FUT", "^$sym[A-Z]{3}[0-9]{2}FUT", "^$sym[A-Z]{3}[0-9]{4}FUT",
            ) else listOf(
                "^$sym[0-9]{2}[A-Z]{3}[0-9]{2}", "^$sym[0-9]{2}[A-Z]{3}", "^$sym[0-9]{4}[A-Z]{3}",
                "^$sym[A-Z]{3}[0-9]{2}", "^$sym[A-Z]{3}[0-9]{4}",
            )
            for (alt in alternatives) {
                val m = matching(rows, alt)
                if (m.isNotEmpty()) { found = m; break }
            }
        }
        // Unparseable expiries sort last and are kept, as upstream does.
        val live = found.filter { (Expiries.parseDashed(it) ?: LocalDate.MAX) >= today }
        return live.sortedBy { Expiries.parseDashed(it) ?: LocalDate.MAX } to null
    }

    private fun matching(rows: List<Instrument>, pattern: String): Set<String> {
        val re = Regex(pattern)
        return rows.filter { re.find(it.symbol)?.range?.first == 0 }.mapNotNull { it.expiry }.toSet()
    }

    /**
     * Every listed strike for one underlying, expiry (`DDMMMYY`) and option
     * type, ascending and distinct: the ladder the resolver walks, which
     * survives an unequal ladder and fractional strikes without describing
     * either.
     */
    fun availableStrikes(base: String, expirySymbol: String, optionType: String, exchange: String): List<Double> {
        val e = expirySymbol.uppercase()
        val dashed = "${e.take(2)}-${e.drop(2).take(3)}-${e.drop(5)}"
        val type = optionType.uppercase()
        val exch = exchange.uppercase()
        val strikes = if (exch in CRYPTO_EXCHANGES) {
            instruments.filter {
                likePrefix(it.symbol, base.uppercase()) && it.expiry == dashed && it.instrumentType == type &&
                    it.exchange in CRYPTO_EXCHANGES && it.strike != null && it.strike > 0
            }
        } else {
            instruments.filter {
                it.symbol.startsWith("$base$e") && it.symbol.endsWith(type) && it.symbol.length >= base.length + e.length + type.length &&
                    it.expiry == dashed && it.instrumentType == type && it.exchange == exch && it.strike != null
            }
        }
        return strikes.mapNotNull { it.strike }.distinct().sorted()
    }

    /**
     * Nearest unexpired future for a base, anchored so GOLD matches only
     * GOLD{DDMMMYY}FUT and never GOLDM or GOLDPETAL, whose prices differ by an
     * order of magnitude and would price a whole chain wrong.
     */
    fun nearMonthFutures(base: String, exchange: String, today: LocalDate): Instrument? {
        val b = base.uppercase()
        val exch = exchange.uppercase()
        if (b.isEmpty() || exch.isEmpty()) return null
        val exact = Regex("^${Regex.escape(b)}\\d{2}[A-Z]{3}\\d{2}FUT$")
        return instruments
            .filter { it.exchange == exch && it.instrumentType == "FUT" && !it.expiry.isNullOrEmpty() && exact.matches(it.symbol) }
            .mapNotNull { row -> Expiries.parseStrict(row.expiry!!)?.takeIf { it >= today }?.let { it to row } }
            .minByOrNull { it.first }?.second
    }

    /**
     * What to quote for an ATM reference: the underlying itself, or for a
     * venue with no spot (MCX, CDS, ...) its nearest future. Null when such a
     * venue has no future to quote. The phone fetches the price; this only
     * says which instrument.
     */
    fun underlyingQuote(base: String, exchange: String, today: LocalDate): Pair<String, String>? {
        val exch = exchange.uppercase()
        if (exch !in NO_SPOT_EXCHANGES) return base to exch
        val fut = nearMonthFutures(base, exch, today) ?: return null
        return fut.symbol to fut.exchange
    }

    companion object {
        /** Exchanges that list derivatives under their own code. */
        val DERIVATIVE_EXCHANGES = setOf("NFO", "BFO", "MCX", "CDS", "NCO", "BCD", "NCDEX", "CRYPTO")

        /** An index has no cash instrument, so a cash leg on it trades the same exchange's equity segment. */
        val CASH_EXCHANGE_FOR = mapOf("NSE_INDEX" to "NSE", "BSE_INDEX" to "BSE")

        val NO_SPOT_EXCHANGES = setOf("MCX", "CDS", "BCD", "NCDEX", "NCO")
        val CRYPTO_EXCHANGES = setOf("CRYPTO")
        private val EXPIRY_EXCHANGES = listOf("NFO", "BFO", "MCX", "CDS", "NCO", "BCD", "NCDEX", "CRYPTO")

        /**
         * SQL `LIKE 'root%'`, as the Python queries it. Case-insensitive, as
         * SQLite's LIKE is for ASCII.
         */
        internal fun likePrefix(symbol: String, root: String) = symbol.regionMatches(0, root, 0, root.length, ignoreCase = true)

        /** Upstream's `get_option_exchange`: where an underlying's derivatives list. Unknown -> NFO. */
        fun optionExchange(underlyingExchange: String): String = when (val u = underlyingExchange.uppercase()) {
            "NSE", "NSE_INDEX" -> "NFO"
            "BSE", "BSE_INDEX" -> "BFO"
            "MCX", "CDS", "NCO", "BCD", "NCDEX" -> u
            in CRYPTO_EXCHANGES -> u
            else -> "NFO"
        }

        /** A derivatives code passes straight through; an underlying's exchange is mapped. */
        fun derivativesExchange(exchange: String?): String {
            val e = (exchange ?: "").trim().uppercase()
            return if (e in DERIVATIVE_EXCHANGES) e else optionExchange(e)
        }
    }
}

/**
 * Expiry dates in the spellings the codebase uses, with Python `strptime`'s
 * acceptance rules: `%d` takes one or two digits (and a space-padded day),
 * `%b` is case-insensitive, `%y` maps 69-99 to the 1900s.
 */
object Expiries {
    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private const val DAY = "(3[01]|[12]\\d|0[1-9]|[1-9]| [1-9])"
    private val FORMATS = listOf(
        Regex("$DAY-([A-Za-z]{3})-(\\d\\d)") to false,
        Regex("$DAY-([A-Za-z]{3})-(\\d{4})") to true,
        Regex("$DAY([A-Za-z]{3})(\\d\\d)") to false,
        Regex("$DAY([A-Za-z]{3})(\\d{4})") to true,
    )

    /** `strptime(text, fmt)` for one format; null where Python raises. */
    private fun parse(text: String, format: Int): LocalDate? {
        val (re, fourDigit) = FORMATS[format]
        val m = re.matchEntire(text) ?: return null
        val month = MONTHS.indexOf(m.groupValues[2].uppercase()) + 1
        if (month == 0) return null
        val day = m.groupValues[1].trim().toInt()
        var year = m.groupValues[3].toInt()
        if (!fourDigit) year += if (year <= 68) 2000 else 1900
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    /** The resolver's `_parse_expiry`: any of the four spellings, trimmed and upper-cased. */
    fun parseAny(text: String?): LocalDate? {
        if (text.isNullOrBlank()) return null
        val cleaned = text.trim().uppercase()
        for (f in FORMATS.indices) parse(cleaned, f)?.let { return it }
        return null
    }

    /** `get_expiry_dates`' parse: `%d-%b-%y`, then `%d-%b-%Y`. */
    fun parseDashed(text: String): LocalDate? = parse(text, 0) ?: parse(text, 1)

    /** `find_near_month_futures`' parse: `%d-%b-%y` only. */
    fun parseStrict(text: String): LocalDate? = parse(text, 0)

    /** The `DDMMMYY` form a symbol embeds. */
    fun symbolForm(date: LocalDate): String =
        String.format("%02d%s%02d", date.dayOfMonth, MONTHS[date.monthValue - 1], date.year % 100)
}
