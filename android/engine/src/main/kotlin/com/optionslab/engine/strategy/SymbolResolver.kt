package com.optionslab.engine.strategy

import com.optionslab.engine.risk.RiskValues
import java.time.LocalDate
import java.time.ZonedDateTime

/** One resolved expiry, in both the database and the symbol spelling. */
data class ExpiryResult(
    val ok: Boolean,
    val rank: String,
    val expiry: String? = null,
    val expirySymbol: String? = null,
    val available: List<String> = emptyList(),
    /** The requested rank did not exist and the nearest was used: recorded, not hidden. */
    val fallback: Boolean = false,
    val error: String? = null,
    val code: String? = null,
)

/**
 * A leg turned into an exact contract, or a refusal explaining why not.
 * Field for field the Python `ResolvedLeg`, plus [legId] and [position] so the
 * runtime can match a resolution back to its leg without a side table.
 */
data class ResolvedLeg(
    val ok: Boolean,
    val symbol: String? = null,
    val exchange: String? = null,
    val segment: String? = null,
    val lotsize: Int? = null,
    val tickSize: Double? = null,
    val strike: Double? = null,
    val expiry: String? = null,
    val expirySymbol: String? = null,
    val quantity: Int? = null,
    val lots: Int? = null,
    val optionType: String? = null,
    val action: String? = null,
    val underlying: String? = null,
    val underlyingLtp: Double? = null,
    val atmStrike: Double? = null,
    val strategyType: String? = null,
    val error: String? = null,
    val code: String? = null,
    /** Everything consulted on the way. Never load bearing, except `expiry_fallback`, which the run reports. */
    val detail: Map<String, Any?> = emptyMap(),
    val legId: Int? = null,
    val position: Position? = null,
) {
    fun asDict(): Map<String, Any?> = linkedMapOf(
        "ok" to ok, "symbol" to symbol, "exchange" to exchange, "segment" to segment, "lotsize" to lotsize,
        "tick_size" to tickSize, "strike" to strike, "expiry" to expiry, "expiry_symbol" to expirySymbol,
        "quantity" to quantity, "lots" to lots, "option_type" to optionType, "action" to action,
        "underlying" to underlying, "underlying_ltp" to underlyingLtp, "atm_strike" to atmStrike,
        "strategy_type" to strategyType, "error" to error, "code" to code, "detail" to detail,
    )
}

/**
 * Resolves strategy legs to exact tradable contracts. Port of IraAlgo's
 * `services/strategy_module/symbol_resolver.py`, with the master contract,
 * the expiry calendar and the underlying price passed in rather than read.
 *
 * A leg is written relatively ("the ATM call of the weekly expiry, two lots")
 * and must become one contract the master contract confirms, with the lot
 * size that contract carries, before the first order goes out - because a
 * basket fails leg by leg, and by the time leg three is refused legs one and
 * two are filled.
 *
 * The cases where approximately right is indistinguishable from wrong:
 *
 * - the monthly is the last expiry of its calendar month, read off the data,
 *   never off a weekday (NFO moved Thursday to Tuesday; MCX never had one);
 * - ITM/OTM point opposite ways for calls and puts: a call's ITM is a lower
 *   strike, a put's a higher one;
 * - strikes are not integers (VEDL25APR24292.5CE is real), so nothing
 *   truncates one;
 * - the ATM is found by walking the strikes actually listed for that expiry,
 *   so an unequal ladder needs no step to be detected or configured. Only a
 *   leg that supplies [LegDef.strikeInt] gets arithmetic, which rounds half to
 *   even as Python's `round()` does (23575/50 = 471.5 -> 472 -> 23600, but
 *   23525/50 = 470.5 -> 470 -> 23500).
 *
 * Failure is a value: a refusal carries a machine code and a message naming
 * exactly what was looked for, and nothing falls back to a neighbour.
 */
object SymbolResolver {
    val EXPIRY_RANKS = listOf("weekly", "next_week", "monthly", "next_month", "current", "next")
    private val SEGMENTS = listOf("cash", "futures", "options")
    private val STRIKE_MODES = listOf("atm", "strike")
    private val ATM_OFFSET = Regex("(ATM|ITM[1-5]|OTM[1-5])")
    private val LITERAL_EXPIRY = Regex("(\\d{2})-?([A-Za-z]{3})-?(\\d{2}|\\d{4})")
    private val UNDERLYING_WITH_EXPIRY = Regex("([A-Z]+)(\\d{2}[A-Z]{3}\\d{2})(?:FUT)?")

    /**
     * Resolve every leg of [def] against one underlying price, so all legs of
     * a spread settle around the same ATM: re-quoting per leg lets the
     * underlying move between legs. Results are in leg order; the runtime
     * refuses to start unless every one is `ok`.
     */
    fun resolve(def: StrategyDef, instruments: MasterContract, ltp: Double?, now: ZonedDateTime): List<ResolvedLeg> {
        val today = now.withZoneSameInstant(IST).toLocalDate()
        return def.legs.map { leg ->
            resolveLeg(leg, def.underlying, def.underlyingExchange, def.strategyType.wire, instruments, ltp, today)
                .copy(legId = leg.id, position = leg.position)
        }
    }

    /** Split an optional `DDMMMYY` expiry off an underlying ("NIFTY28OCT25FUT" -> "NIFTY"). */
    fun baseSymbol(underlying: String): String =
        UNDERLYING_WITH_EXPIRY.matchEntire(underlying.uppercase())?.groupValues?.get(1) ?: underlying.uppercase()

    // ----------------------------------------------------------------- expiry

    /**
     * A relative rank as a dated expiry:
     *
     * - weekly, current: the nearest live expiry;
     * - next_week, next: the one after it, else the nearest (fallback);
     * - monthly: the nearest expiry that is the last of its calendar month;
     * - next_month: the monthly after that, else the only monthly (fallback).
     *
     * Options and futures are listed separately because their calendars
     * differ (an MCX GOLDM future expires on the 5th, its options on the 28th).
     */
    fun resolveExpiryRank(
        contract: MasterContract, underlying: String, exchange: String, instrumentType: String, rank: String, today: LocalDate,
    ): ExpiryResult {
        val rankKey = rank.trim().lowercase().replace("-", "_").replace(" ", "_")
        if (rankKey !in EXPIRY_RANKS) {
            return ExpiryResult(
                false, rank,
                error = "Unknown expiry rank ${Py.repr(rank)}. Supported ranks are ${EXPIRY_RANKS.joinToString(", ")}.",
                code = "invalid_rank",
            )
        }
        val canonical = when (instrumentType.trim().lowercase()) {
            "options", "option", "opt", "ce", "pe" -> "options"
            "futures", "future", "fut" -> "futures"
            else -> return ExpiryResult(
                false, rankKey,
                error = "Unknown instrument type ${Py.repr(instrumentType)}. Expiries are listed for options or futures.",
                code = "invalid_instrument_type",
            )
        }
        val base = baseSymbol(underlying)
        val derivatives = MasterContract.derivativesExchange(exchange)
        val (raw, failure) = contract.expiryDates(base, derivatives, canonical, today)
        if (raw == null) {
            return ExpiryResult(
                false, rankKey,
                error = "Could not list $canonical expiries for $base on $derivatives. $failure",
                code = "expiry_lookup_failed",
            )
        }
        val pairs = raw.mapNotNull { text -> Expiries.parseAny(text)?.takeIf { it >= today }?.let { text.trim().uppercase() to it } }
            .sortedBy { it.second }
        if (pairs.isEmpty()) {
            return ExpiryResult(
                false, rankKey,
                error = "No live $canonical expiry found for $base on $derivatives. The master contract may need re-downloading.",
                code = "no_expiry",
            )
        }
        var fallback = false
        val chosen = when (rankKey) {
            "weekly", "current" -> pairs[0]
            "next_week", "next" -> { fallback = pairs.size < 2; if (pairs.size > 1) pairs[1] else pairs[0] }
            else -> {
                // The last expiry of each calendar month, read off the data.
                val monthlies = pairs.groupBy { it.second.year to it.second.monthValue }.toSortedMap(compareBy({ it.first }, { it.second }))
                    .values.map { it.last() }
                if (rankKey == "monthly") monthlies[0]
                else { fallback = monthlies.size < 2; if (monthlies.size > 1) monthlies[1] else monthlies[0] }
            }
        }
        return ExpiryResult(
            true, rankKey, expiry = chosen.first, expirySymbol = Expiries.symbolForm(chosen.second),
            available = pairs.map { it.first }, fallback = fallback,
        )
    }

    /** A leg may pin a literal expiry, the only way to write a calendar or a diagonal. */
    private fun legExpiry(leg: LegDef, base: String, exchange: String, instrumentType: String, contract: MasterContract, today: LocalDate): ExpiryResult {
        val declared = leg.expiry?.takeIf { it.isNotEmpty() }
        if (declared != null && LITERAL_EXPIRY.matches(declared.trim())) {
            val parsed = Expiries.parseAny(declared)
                ?: return ExpiryResult(false, "literal", error = "Expiry ${Py.repr(declared)} is not a date this platform understands.", code = "invalid_expiry")
            return ExpiryResult(true, "literal", expiry = declared.trim().uppercase(), expirySymbol = Expiries.symbolForm(parsed))
        }
        // No date, so a rank; the nearest live expiry is what saying nothing means.
        return resolveExpiryRank(contract, base, exchange, instrumentType, declared ?: "current", today)
    }

    // -------------------------------------------------------------------- leg

    private class Ctx(val strategyType: String?, val underlying: String?, val action: String?) {
        var segment: String? = null
        var lots: Int? = null
        var optionType: String? = null
    }

    private fun fail(
        ctx: Ctx, code: String, message: String, symbol: String? = null, exchange: String? = null, strike: Double? = null,
        expiry: String? = null, expirySymbol: String? = null, underlyingLtp: Double? = null, atmStrike: Double? = null,
    ) = ResolvedLeg(
        ok = false, error = message, code = code, symbol = symbol, exchange = exchange, strike = strike, expiry = expiry,
        expirySymbol = expirySymbol, underlyingLtp = underlyingLtp, atmStrike = atmStrike,
        strategyType = ctx.strategyType, underlying = ctx.underlying, action = ctx.action,
        segment = ctx.segment, lots = ctx.lots, optionType = ctx.optionType,
    )

    /** A strike as a symbol writes it: a whole strike loses its `.0`, a fractional one keeps its decimals. */
    fun strikeText(strike: Double): String =
        if (strike == Math.floor(strike) && strike.isFinite()) strike.toLong().toString() else RiskValues.pyRepr(strike)

    /** `[BASE][DDMMMYY][STRIKE][CE|PE]`, e.g. NIFTY28OCT2523500CE, VEDL25APR24292.5CE. */
    fun optionSymbol(base: String, expirySymbol: String, strike: Double, optionType: String) =
        "$base$expirySymbol${strikeText(strike)}${optionType.uppercase()}"

    /**
     * Resolve one leg. [underlyingLtp] is required for an ATM option leg; the
     * Python fetches a quote when it is absent, which is I/O, so here its
     * absence is a `no_ltp` refusal instead (the only deliberate divergence).
     * [today] (IST) drops expired expiries, as the Python's clock does.
     */
    fun resolveLeg(
        leg: LegDef, underlying: String, underlyingExchange: String, strategyType: String?,
        contract: MasterContract, underlyingLtp: Double?, today: LocalDate,
    ): ResolvedLeg {
        val base = baseSymbol(underlying)
        val action = leg.position?.let { if (it == Position.B) "BUY" else "SELL" }
            ?: leg.side?.let { if (it == LegSide.SHORT) "SELL" else "BUY" }
        val ctx = Ctx(strategyType, base, action)
        if (base.isEmpty()) return fail(ctx, "invalid_underlying", "No underlying symbol supplied.")
        val segment = leg.segment.wire
        if (segment !in SEGMENTS) {
            return fail(ctx, "invalid_segment", "Unknown segment ${Py.repr(segment)} on a $base leg. Supported segments are ${SEGMENTS.joinToString(", ")}.")
        }
        ctx.segment = segment
        if (leg.lots <= 0) {
            return fail(ctx, "invalid_lots", "Lots on a $base $segment leg must be a whole number above zero, got ${leg.lots}.")
        }
        ctx.lots = leg.lots
        return when (leg.segment) {
            Segment.CASH -> cashLeg(base, underlyingExchange, contract, ctx)
            Segment.FUTURES -> futuresLeg(leg, base, underlyingExchange, contract, today, ctx)
            Segment.OPTIONS -> optionsLeg(leg, base, underlyingExchange, contract, underlyingLtp, today, ctx)
        }
    }

    /**
     * The lot-size rule and the success result. The lot size is whatever the
     * master contract says for this exact contract; a missing or non-positive
     * one is a refusal, because multiplying by it sends zero or a negative.
     */
    private fun finish(
        row: Instrument, symbol: String, exchange: String, ctx: Ctx, strike: Double? = null, expiry: String? = null,
        expirySymbol: String? = null, underlyingLtp: Double? = null, atmStrike: Double? = null, detail: Map<String, Any?> = emptyMap(),
    ): ResolvedLeg {
        val lotsize = row.lotsize
        if (lotsize == null || lotsize <= 0) {
            return fail(
                ctx, "invalid_lotsize",
                "The master contract gives $symbol on $exchange a lot size of ${Py.repr(lotsize)}, so no quantity can be derived from it. Re-download the master contract.",
                symbol = symbol, exchange = exchange, strike = strike, expiry = expiry, expirySymbol = expirySymbol,
            )
        }
        val lots = ctx.lots ?: 1
        return ResolvedLeg(
            ok = true, symbol = symbol, exchange = exchange, lotsize = lotsize,
            tickSize = row.tickSize?.takeIf { it.isFinite() && it > 0 }, strike = strike, expiry = expiry,
            expirySymbol = expirySymbol, quantity = lots * lotsize, underlyingLtp = underlyingLtp, atmStrike = atmStrike,
            detail = detail, strategyType = ctx.strategyType, underlying = ctx.underlying, action = ctx.action,
            segment = ctx.segment, lots = ctx.lots, optionType = ctx.optionType,
        )
    }

    /** A cash leg is the underlying equity itself; an index maps to its exchange's equity segment and is refused by name. */
    private fun cashLeg(base: String, underlyingExchange: String, contract: MasterContract, ctx: Ctx): ResolvedLeg {
        val exch = underlyingExchange.trim().uppercase()
        val exchange = MasterContract.CASH_EXCHANGE_FOR[exch] ?: exch
        val row = contract.find(base, exchange)
            ?: return fail(
                ctx, "contract_not_found",
                "No cash contract found for $base on $exchange." +
                    if (exch in MasterContract.CASH_EXCHANGE_FOR) " An index has no cash instrument of its own and cannot be traded directly." else "",
                symbol = base, exchange = exchange,
            )
        return finish(row, base, exchange, ctx)
    }

    /** A futures leg is `{BASE}{DDMMMYY}FUT` on the derivatives exchange. */
    private fun futuresLeg(leg: LegDef, base: String, underlyingExchange: String, contract: MasterContract, today: LocalDate, ctx: Ctx): ResolvedLeg {
        val exchange = MasterContract.derivativesExchange(underlyingExchange)
        val expiry = legExpiry(leg, base, underlyingExchange, "futures", contract, today)
        if (!expiry.ok) return fail(ctx, expiry.code ?: "no_expiry", expiry.error ?: "", exchange = exchange)
        val symbol = "$base${expiry.expirySymbol}FUT"
        val row = contract.find(symbol, exchange)
            ?: return fail(
                ctx, "contract_not_found",
                "No futures contract found for $base ${expiry.expiry} on $exchange (looked for $symbol).",
                symbol = symbol, exchange = exchange, expiry = expiry.expiry, expirySymbol = expiry.expirySymbol,
            )
        return finish(
            row, symbol, exchange, ctx, expiry = expiry.expiry, expirySymbol = expiry.expirySymbol,
            detail = linkedMapOf("expiry_rank" to expiry.rank, "expiry_fallback" to expiry.fallback),
        )
    }

    private fun optionsLeg(
        leg: LegDef, base: String, underlyingExchange: String, contract: MasterContract, underlyingLtp: Double?, today: LocalDate, ctx: Ctx,
    ): ResolvedLeg {
        val optionType = leg.optionType?.wire
            ?: return fail(ctx, "invalid_leg", "Invalid option_type: None. Supported option types are CE and PE.")
        ctx.optionType = optionType
        val exchange = MasterContract.derivativesExchange(underlyingExchange)
        val expiry = legExpiry(leg, base, underlyingExchange, "options", contract, today)
        if (!expiry.ok) return fail(ctx, expiry.code ?: "no_expiry", expiry.error ?: "", exchange = exchange)
        val expirySymbol = expiry.expirySymbol!!

        val strikeMode = leg.strikeMode?.wire ?: "atm"
        if (strikeMode !in STRIKE_MODES) {
            return fail(
                ctx, "invalid_strike_mode",
                "Unknown strike mode ${Py.repr(strikeMode)} on a $base option leg. Supported modes are ${STRIKE_MODES.joinToString(", ")}.",
                exchange = exchange, expiry = expiry.expiry, expirySymbol = expirySymbol,
            )
        }
        var ltp: Double? = null
        var atm: Double? = null
        val detail = linkedMapOf<String, Any?>("expiry_rank" to expiry.rank, "expiry_fallback" to expiry.fallback, "strike_mode" to strikeMode)
        val target: Double

        if (strikeMode == "strike") {
            val declared = leg.strike
            if (declared == null || !declared.isFinite() || declared <= 0) {
                return fail(
                    ctx, "invalid_strike",
                    "Strike ${Py.repr(declared)} on a $base option leg is not a usable price. A strike must be a positive number, and may be fractional.",
                    exchange = exchange, expiry = expiry.expiry, expirySymbol = expirySymbol,
                )
            }
            target = declared
        } else {
            val offsetRaw = leg.atmOffset?.takeIf { it.isNotEmpty() } ?: "ATM"
            val offset = offsetRaw.trim().uppercase()
            if (!ATM_OFFSET.matches(offset)) {
                return fail(
                    ctx, "invalid_offset",
                    "Unknown offset ${Py.repr(offsetRaw)} on a $base option leg. Supported offsets are ATM, ITM1 to ITM5 and OTM1 to OTM5.",
                    exchange = exchange, expiry = expiry.expiry, expirySymbol = expirySymbol,
                )
            }
            detail["atm_offset"] = offset
            if (underlyingLtp == null) {
                return fail(
                    ctx, "no_ltp", "No underlying price was supplied for $base; the resolver takes the price as an input.",
                    exchange = exchange, expiry = expiry.expiry, expirySymbol = expirySymbol,
                )
            }
            if (!underlyingLtp.isFinite() || underlyingLtp <= 0) {
                return fail(
                    ctx, "no_ltp", "Unusable last price ${Py.repr(underlyingLtp)} supplied for $base.",
                    exchange = exchange, expiry = expiry.expiry, expirySymbol = expirySymbol,
                )
            }
            ltp = underlyingLtp
            val step = leg.strikeInt
            var found: Double? = null
            var error: String? = null
            var code: String? = null
            if (step != null) {
                if (!step.isFinite() || step <= 0) {
                    return fail(ctx, "invalid_leg", "Invalid strike_int: ${Py.repr(step)}. Strike interval must be a positive number.")
                }
                // Python's round() is half-to-even; Math.rint is the same rule.
                atm = Math.rint(ltp / step) * step
                found = offsetStrike(atm, offset, step, optionType)
            } else {
                val strikes = contract.availableStrikes(base, expirySymbol, optionType, exchange)
                if (strikes.isEmpty()) {
                    error = "No $optionType strikes listed for $base expiring $expirySymbol on $exchange. Check the expiry, or re-download the master contract."
                    code = "no_strikes"
                } else {
                    atm = strikes.minByOrNull { kotlin.math.abs(it - ltp) }
                    found = offsetStrikeFromActual(atm!!, offset, optionType, strikes)
                    if (found == null) {
                        error = "Offset $offset runs off the end of the $base $expirySymbol $optionType chain, which lists ${strikes.size} strikes around an ATM of ${strikeText(atm)}."
                        code = "offset_out_of_range"
                    }
                }
            }
            if (found == null) {
                return fail(
                    ctx, code ?: "no_strikes", error ?: "", exchange = exchange, expiry = expiry.expiry, expirySymbol = expirySymbol,
                    underlyingLtp = ltp, atmStrike = atm,
                )
            }
            target = found
        }

        val symbol = optionSymbol(base, expirySymbol, target, optionType)
        val row = contract.find(symbol, exchange)
            ?: return fail(
                ctx, "contract_not_found",
                "No option contract found for $base ${expiry.expiry} ${strikeText(target)} $optionType on $exchange (looked for $symbol).",
                symbol = symbol, exchange = exchange, strike = target, expiry = expiry.expiry, expirySymbol = expirySymbol,
                underlyingLtp = ltp, atmStrike = atm,
            )
        return finish(
            row, symbol, exchange, ctx, strike = target, expiry = expiry.expiry, expirySymbol = expirySymbol,
            underlyingLtp = ltp, atmStrike = atm, detail = detail,
        )
    }

    /**
     * The arithmetic offset: a call's ITM is a LOWER strike and its OTM a
     * higher one; a put's the reverse. A sign error here turns a debit spread
     * into a credit one.
     */
    fun offsetStrike(atm: Double, offset: String, step: Double, optionType: String): Double {
        val o = offset.uppercase()
        if (o == "ATM") return atm
        val n = o.substring(3).toInt()
        val call = optionType == "CE"
        return when {
            o.startsWith("ITM") -> if (call) atm - n * step else atm + n * step
            o.startsWith("OTM") -> if (call) atm + n * step else atm - n * step
            else -> throw IllegalArgumentException("Invalid offset: $o")
        }
    }

    /** The same rule by position in the listed ladder; null when it walks off either end. */
    fun offsetStrikeFromActual(atm: Double, offset: String, optionType: String, strikes: List<Double>): Double? {
        val atmIndex = strikes.indexOf(atm)
        if (atmIndex < 0) return null
        val o = offset.uppercase()
        if (o == "ATM") return atm
        val call = optionType == "CE"
        val target = when {
            o.startsWith("ITM") -> { val n = o.substring(3).toInt(); if (call) atmIndex - n else atmIndex + n }
            o.startsWith("OTM") -> { val n = o.substring(3).toInt(); if (call) atmIndex + n else atmIndex - n }
            else -> return null
        }
        return strikes.getOrNull(target)
    }
}
