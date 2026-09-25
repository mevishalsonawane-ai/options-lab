package com.optionslab.engine.strategy

import com.optionslab.engine.risk.RiskValues
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Validates a whole strategy configuration. Port of
 * `validate_strategy_config` in IraAlgo's `blueprints/strategy_module.py`.
 *
 * One definition of a valid strategy for create and for edit: an edit merges
 * its change onto the stored config and passes the result here, so there is
 * no strict path and lenient path to drift apart. Refusals are exact Python
 * messages, because they are what the user reads in the form.
 *
 * Unknown fields are refused rather than dropped: a caller that misspells
 * `overall_sl_mtm` would otherwise be told the strategy saved and find out at
 * the first drawdown that it has no stop.
 */
object StrategyValidator {
    sealed interface Result {
        data class Ok(val def: StrategyDef) : Result
        data class Invalid(val message: String) : Result
    }

    private class Invalid(message: String) : Exception(message)

    val PRODUCTS = listOf("CNC", "NRML", "MIS")
    val PRICETYPES = listOf("MARKET")
    val UNDERLYING_EXCHANGES = listOf("NSE", "BSE", "NFO", "BFO", "CDS", "BCD", "MCX", "NCDEX", "NCO", "NSE_INDEX", "BSE_INDEX")
    val UNIVERSE_TABS = UniverseTab.entries.map { it.wire }
    val LEG_SEGMENTS = listOf("options", "futures", "cash")
    val LEG_EXPIRIES = ExpiryRank.entries.map { it.wire }
    val ATM_OFFSETS = listOf("ATM") + (1..5).map { "ITM$it" } + (1..5).map { "OTM$it" }
    val SIGNAL_LEG_EXCHANGES = listOf("NSE", "BSE", "NFO", "BFO", "MCX", "CDS", "BCD", "NCDEX", "NCO")
    val SCHEDULER_DAYS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    private val LEG_FIELDS = listOf("id", "segment", "position", "lots", "option_type", "strike_mode", "atm_offset", "strike", "expiry", "sl_pts", "target_pts", "trail", "risk_unit")
    private val SIGNAL_LEG_FIELDS = listOf("id", "symbol", "exchange", "side", "qty", "qty_mode", "segment", "expiry", "sl_pts", "target_pts", "trail", "risk_unit")
    private val CONFIG_FIELDS = listOf(
        "name", "direction", "universe_tab", "underlying", "underlying_exchange", "strategy_type", "entry_time",
        "exit_time", "product", "pricetype", "legs", "overall_sl_mtm", "overall_target_mtm", "lock_profit",
        "trail_sl_to_entry", "scheduler", "daily_loss_limit_inr", "webhook_ip_allowlist", "strategy_kind",
    )
    const val MIN_LEGS = 1
    const val MAX_LEGS = StrategyDef.MAX_LEGS
    const val MAX_LOTS = 50L
    const val MAX_CASH_QUANTITY = 1_000_000L
    const val MAX_SIGNAL_QTY = 1_000_000L
    const val MAX_SIGNAL_LOTS = 10_000L
    const val MAX_NAME_LENGTH = 200
    const val MAX_UNDERLYING_LENGTH = 50
    const val MAX_IP_ALLOWLIST = 20
    private val HHMM = Regex("""(\d{1,2}):(\d{2})""")
    private val COMMODITY_EXCHANGES = setOf("MCX", "NCDEX", "NCO")
    private val WEEKLY_RANKS = setOf("weekly", "next_week")

    /**
     * Validate [payload], a JSON object as parsed by [Json]. [lotSizeFor] is
     * the master-contract lookup the signal-leg whole-lot check uses; a null
     * answer means "cannot say" and passes, exactly as upstream, because
     * refusing a form for a contract not yet downloaded blocks the user for a
     * reason they cannot act on. The engine checks again at entry.
     */
    fun validate(payload: Any?, lotSizeFor: (String, String) -> Int? = { _, _ -> null }): Result =
        try {
            Result.Ok(validateOrThrow(payload, lotSizeFor))
        } catch (e: Invalid) {
            Result.Invalid(e.message!!)
        }

    // ---------------------------------------------------------------- leaves

    private fun fail(message: String): Nothing = throw Invalid(message)

    @Suppress("UNCHECKED_CAST")
    private fun mapping(value: Any?, label: String): Map<String, Any?> =
        value as? Map<String, Any?> ?: fail("$label must be a JSON object")

    private fun rejectUnknown(value: Map<String, Any?>, allowed: List<String>, label: String) {
        val extra = value.keys.filter { it !in allowed }.sorted()
        if (extra.isNotEmpty()) {
            fail("$label does not accept ${extra.joinToString(", ")}. Accepted fields: ${allowed.sorted().joinToString(", ")}")
        }
    }

    private fun required(m: Map<String, Any?>, key: String, label: String = ""): Any {
        val name = if (label.isNotEmpty()) "$label.$key" else key
        return m[key] ?: fail("$name is required")
    }

    /** Python's `dict.get(key, default)`: a present null stays null. */
    private fun Map<String, Any?>.getOr(key: String, default: Any?): Any? = if (containsKey(key)) this[key] else default

    private fun text(value: Any?, label: String, maxLength: Int): String {
        if (value !is String) fail("$label must be text")
        val t = value.trim()
        if (t.isEmpty()) fail("$label is required")
        if (Py.len(t) > maxLength) fail("$label must be at most $maxLength characters, got ${Py.len(t)}")
        return t
    }

    private fun choice(value: Any?, allowed: List<String>, label: String): String {
        if (value !is String) fail("$label must be one of: ${allowed.joinToString(", ")}")
        val t = value.trim()
        for (option in allowed) if (t.lowercase() == option.lowercase()) return option
        fail("$label must be one of: ${allowed.joinToString(", ")}. Got ${Py.repr(value)}")
    }

    /** A finite number with its own type intact (Long stays Long), as Python keeps int and float apart. */
    private fun number(value: Any?, label: String, minimum: Number? = null, maximum: Number? = null, greaterThan: Number? = null): Number {
        if (value is Boolean) fail("$label must be a number")
        var v: Any? = value
        if (v is String) {
            v = RiskValues.pyFloat(v) ?: fail("$label must be a number, got ${Py.repr(value)}")
        }
        if (v !is Long && v !is Int && v !is Double) fail("$label must be a number")
        val n = v as Number
        val d = n.toDouble()
        if (!d.isFinite()) fail("$label must be a number")
        if (minimum != null && d < minimum.toDouble()) fail("$label must be ${Py.str(minimum)} or more, got ${Py.str(n)}")
        if (greaterThan != null && d <= greaterThan.toDouble()) fail("$label must be greater than ${Py.str(greaterThan)}, got ${Py.str(n)}")
        if (maximum != null && d > maximum.toDouble()) fail("$label must be ${Py.str(maximum)} or less, got ${Py.str(n)}")
        return n
    }

    private fun integer(value: Any?, label: String, minimum: Long, maximum: Long): Long {
        if (value is Boolean) fail("$label must be a whole number")
        var v: Any? = value
        if (v is String) v = Py.parseInt(v) ?: fail("$label must be a whole number, got ${Py.repr(value)}")
        if (v is Double) {
            if (!v.isFinite() || v != Math.floor(v)) fail("$label must be a whole number, got ${Py.str(v)}")
            // Python's int() is exact at any size; a Long would clamp 1e308 in the message.
            if (kotlin.math.abs(v) >= 9.0e18) fail("$label must be between $minimum and $maximum, got ${java.math.BigDecimal(v).toBigInteger()}")
            v = v.toLong()
        }
        if (v is Int) v = v.toLong()
        if (v !is Long) fail("$label must be a whole number")
        if (v < minimum || v > maximum) fail("$label must be between $minimum and $maximum, got $v")
        return v
    }

    private fun boolean(value: Any?, label: String): Boolean = value as? Boolean ?: fail("$label must be true or false")

    private fun hhmm(value: Any?, label: String): LocalTime {
        if (value !is String) fail("$label must be a HH:MM 24-hour time, for example 09:20")
        val m = HHMM.matchEntire(value.trim())
            ?: fail("$label must be a HH:MM 24-hour time, for example 09:20. Got ${Py.repr(value)}")
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        if (hour > 23 || minute > 59) fail("$label is not a valid time of day: ${Py.repr(value)}")
        return LocalTime.of(hour, minute)
    }

    /**
     * A loss threshold entered as a positive amount. The engine compares MTM
     * against its negative, so a "helpful" -5000 would ask to stop at a PROFIT
     * of 5000; refusing it, and saying why, is the only unambiguous version.
     */
    private fun lossAmount(value: Any?, label: String): Double? {
        if (value == null) return null
        val n = number(value, label)
        if (n.toDouble() < 0) {
            fail("$label is entered as a positive amount and applied as a negative threshold, so it cannot be negative. Enter 5000 to stop at a loss of 5000.")
        }
        return n.toDouble()
    }

    private fun gainAmount(value: Any?, label: String): Double? = value?.let { number(it, label, minimum = 0L).toDouble() }

    /** A percentage above 100 is not a wider stop but one that cannot mean what it says. */
    private fun riskMax(riskUnit: String): Double? = if (riskUnit == "percent") 100.0 else null

    // ------------------------------------------------------------------ legs

    private fun trail(raw: Any?, label: String, riskUnit: String): Trail? {
        if (raw == null) return null
        val data = mapping(raw, label)
        rejectUnknown(data, listOf("x", "y"), label)
        val ceiling = riskMax(riskUnit)
        val x = number(required(data, "x", label), "$label.x", minimum = 0L, maximum = ceiling)
        val y = number(required(data, "y", label), "$label.y", minimum = 0L, maximum = ceiling)
        return Trail(x.toDouble(), y.toDouble())
    }

    private class Risk(val unit: RiskUnit, val sl: Double?, val target: Double?, val trail: Trail?)

    private fun legRisk(leg: Map<String, Any?>, label: String, trailLabel: String): Risk {
        val unit = choice(leg["risk_unit"].orIfFalsy("points"), listOf("points", "percent"), "$label.risk_unit")
        val sl = leg["sl_pts"]?.let { number(it, "$label.sl_pts", minimum = 0L, maximum = riskMax(unit)).toDouble() }
        val target = leg["target_pts"]?.let { number(it, "$label.target_pts", minimum = 0L, maximum = riskMax(unit)).toDouble() }
        val t = trail(leg["trail"], trailLabel, unit)
        return Risk(wireOf<RiskUnit>(unit)!!, sl, target, t)
    }

    /** Python's `x or default`. */
    private fun Any?.orIfFalsy(default: Any?): Any? = if (RiskValues.truthy(this)) this else default

    private fun signalLeg(raw: Any?, index: Int, lotSizeFor: (String, String) -> Int?): LegDef {
        val label = "legs[$index]"
        val leg = mapping(raw, label)
        rejectUnknown(leg, SIGNAL_LEG_FIELDS, label)
        val segment = choice(leg["segment"].orIfFalsy("cash"), listOf("cash", "futures"), "$label.segment")
        val id = if (leg["id"] != null) integer(leg["id"], "$label.id", 1, MAX_LEGS.toLong()) else (index + 1).toLong()
        val symbol = text(required(leg, "symbol", label), "$label.symbol", 100).uppercase()
        val exchange = choice(text(required(leg, "exchange", label), "$label.exchange", 20).uppercase(), SIGNAL_LEG_EXCHANGES, "$label.exchange")
        val side = choice(leg["side"].orIfFalsy("both"), LegSide.entries.map { it.wire }, "$label.side")

        // Derivatives count in lots and cash in units, unless the leg says.
        val derivative = exchange in MasterContract.DERIVATIVE_EXCHANGES
        val qtyMode = choice(leg["qty_mode"].orIfFalsy(if (derivative) "lots" else "units"), listOf("lots", "units"), "$label.qty_mode")
        if (qtyMode == "lots" && !derivative) {
            fail("$label.qty_mode is 'lots', but $exchange has no lot size. Cash instruments are counted in units.")
        }
        val qty = integer(required(leg, "qty", label), "$label.qty", 1, if (qtyMode == "lots") MAX_SIGNAL_LOTS else MAX_SIGNAL_QTY)

        // Descriptive only on a signal leg: it names its own contract.
        var expiry: String? = null
        if (segment == "futures") {
            expiry = choice(leg["expiry"].orIfFalsy("current"), LEG_EXPIRIES, "$label.expiry")
        } else if (leg["expiry"] != null) {
            fail("$label.expiry does not apply to a cash leg")
        }
        val risk = legRisk(leg, label, label)

        if (qtyMode == "units") {
            val lotSize = lotSizeFor(symbol, exchange)
            if (lotSize != null && lotSize != 0 && !(qty > 0 && qty % lotSize == 0L)) {
                fail("$label.qty is $qty, which is not a whole number of lots. $symbol on $exchange trades in lots of $lotSize.")
            }
        }
        return LegDef(
            id = id.toInt(), segment = wireOf<Segment>(segment)!!, symbol = symbol, exchange = exchange,
            side = wireOf<LegSide>(side), qty = qty.toInt(), qtyMode = wireOf<QtyMode>(qtyMode), expiry = expiry,
            slPts = risk.sl, targetPts = risk.target, trail = risk.trail, riskUnit = risk.unit,
        )
    }

    private fun batchLeg(raw: Any?, index: Int): LegDef {
        val label = "legs[$index]"
        val leg = mapping(raw, label)
        rejectUnknown(leg, LEG_FIELDS, label)
        val segment = choice(required(leg, "segment", label), LEG_SEGMENTS, "$label.segment")
        val id = if (leg["id"] != null) integer(leg["id"], "$label.id", 1, MAX_LEGS.toLong()) else (index + 1).toLong()
        val position = choice(required(leg, "position", label), listOf("B", "S"), "$label.position")
        // A cash leg's "lots" is a share count, so its ceiling is the cash one.
        val lots = integer(required(leg, "lots", label), "$label.lots", 1, if (segment == "cash") MAX_CASH_QUANTITY else MAX_LOTS)

        var optionType: String? = null
        var strikeMode: String? = null
        var atmOffset: String? = null
        var strike: Double? = null
        if (segment == "options") {
            optionType = choice(required(leg, "option_type", label), listOf("CE", "PE"), "$label.option_type")
            strikeMode = choice(leg["strike_mode"].orIfFalsy("atm"), listOf("atm", "strike"), "$label.strike_mode")
            if (strikeMode == "atm") {
                if (leg["strike"] != null) {
                    fail("$label.strike is only used when strike_mode is 'strike'. Set strike_mode to 'strike', or remove the strike.")
                }
                atmOffset = choice(leg["atm_offset"].orIfFalsy("ATM"), ATM_OFFSETS, "$label.atm_offset")
            } else {
                if (leg["atm_offset"] != null) {
                    fail("$label.atm_offset is only used when strike_mode is 'atm'. Set strike_mode to 'atm', or remove the offset.")
                }
                // Kept as sent: fractional strikes exist (VEDL 292.5).
                strike = number(required(leg, "strike", label), "$label.strike", greaterThan = 0L).toDouble()
            }
        } else {
            for (field in listOf("option_type", "strike_mode", "atm_offset", "strike")) {
                if (leg[field] != null) fail("$label.$field is only valid on an options leg")
            }
        }
        var expiry: String? = null
        if (segment == "cash") {
            if (leg["expiry"] != null) fail("$label.expiry is not valid on a cash leg")
        } else {
            expiry = choice(required(leg, "expiry", label), LEG_EXPIRIES, "$label.expiry")
        }
        val risk = legRisk(leg, label, "$label.trail")
        return LegDef(
            id = id.toInt(), segment = wireOf<Segment>(segment)!!, position = wireOf<Position>(position), lots = lots.toInt(),
            optionType = optionType?.let { wireOf<OptionType>(it) }, strikeMode = strikeMode?.let { wireOf<StrikeMode>(it) },
            atmOffset = atmOffset, strike = strike, expiry = expiry,
            slPts = risk.sl, targetPts = risk.target, trail = risk.trail, riskUnit = risk.unit,
        )
    }

    private fun legs(raw: Any?, kind: String, lotSizeFor: (String, String) -> Int?): List<LegDef> {
        if (raw !is List<*>) fail("legs must be a list")
        if (raw.size < MIN_LEGS) fail("A strategy needs at least $MIN_LEGS leg")
        if (raw.size > MAX_LEGS) fail("A strategy takes at most $MAX_LEGS legs, got ${raw.size}")
        val legs = raw.mapIndexed { i, leg -> if (kind == "signal") signalLeg(leg, i, lotSizeFor) else batchLeg(leg, i) }
        if (legs.map { it.id }.toSet().size != legs.size) fail("Every leg needs its own id")
        return legs
    }

    // ----------------------------------------------------------- risk blocks

    private fun lockProfit(raw: Any?): LockProfit? {
        if (raw == null) return null
        val label = "lock_profit"
        val data = mapping(raw, label)
        rejectUnknown(data, listOf("mode", "if_profit_reaches", "lock_profit", "trail_step"), label)
        val mode = choice(required(data, "mode", label), LockProfitMode.entries.map { it.wire }, "$label.mode")
        val reaches = number(required(data, "if_profit_reaches", label), "$label.if_profit_reaches", greaterThan = 0L).toDouble()
        // Zero is allowed: locking at breakeven is a real choice.
        val lockedRaw = number(required(data, "lock_profit", label), "$label.lock_profit", minimum = 0L)
        val locked = lockedRaw.toDouble()
        if (locked > reaches) {
            fail("$label.lock_profit cannot be more than $label.if_profit_reaches: the floor would be above the profit that arms it")
        }
        var step: Double? = null
        if (mode == "lock_and_trail") {
            if (data["trail_step"] == null) fail("$label.trail_step is required when mode is 'lock_and_trail'")
            step = number(data["trail_step"], "$label.trail_step", greaterThan = 0L).toDouble()
        } else if (data["trail_step"] != null) {
            step = number(data["trail_step"], "$label.trail_step", greaterThan = 0L).toDouble()
        }
        return LockProfit(wireOf<LockProfitMode>(mode)!!, reaches, locked, step, lockedRaw is Long)
    }

    private fun scheduler(raw: Any?): SchedulerConfig? {
        if (raw == null) return null
        val label = "scheduler"
        val data = mapping(raw, label)
        rejectUnknown(data, listOf("enabled", "days", "start_time", "auto_stop_time", "default_mode"), label)
        val enabled = boolean(data.getOr("enabled", false), "$label.enabled")
        val rawDays = data["days"].orIfFalsy(emptyList<Any?>())
        if (rawDays !is List<*>) fail("$label.days must be a list of days, MON to SUN")
        val days = ArrayList<String>()
        rawDays.forEachIndexed { i, day ->
            val canonical = choice(day, SCHEDULER_DAYS, "$label.days[$i]")
            if (canonical in days) fail("$label.days lists $canonical more than once")
            days.add(canonical)
        }
        if (enabled && days.isEmpty()) fail("$label.days needs at least one day when the scheduler is enabled")
        var startRaw = data["start_time"]
        var stopRaw = data["auto_stop_time"]
        if (enabled) {
            startRaw = required(data, "start_time", label)
            stopRaw = required(data, "auto_stop_time", label)
        }
        val start = startRaw?.let { hhmm(it, "$label.start_time") }
        val stop = stopRaw?.let { hhmm(it, "$label.auto_stop_time") }
        if (start != null && stop != null && start >= stop) fail("$label.start_time must be earlier than $label.auto_stop_time")
        val mode = choice(data["default_mode"].orIfFalsy("sandbox"), listOf("live", "sandbox"), "$label.default_mode")
        return SchedulerConfig(
            enabled = enabled,
            days = days.sortedBy { SCHEDULER_DAYS.indexOf(it) }.map { DayOfWeek.of(SCHEDULER_DAYS.indexOf(it) + 1) },
            startTime = start,
            autoStopTime = stop,
            defaultMode = wireOf<RunMode>(mode)!!,
        )
    }

    private fun ipAllowlist(raw: Any?): List<String>? {
        if (raw == null) return null
        val label = "webhook_ip_allowlist"
        if (raw !is List<*>) fail("$label must be a list of IP addresses or CIDR ranges")
        if (raw.size > MAX_IP_ALLOWLIST) fail("$label takes at most $MAX_IP_ALLOWLIST entries, got ${raw.size}")
        return raw.mapIndexed { i, entry ->
            if (entry !is String || entry.isBlank()) fail("$label[$i] must be an IP address or CIDR range")
            val t = entry.trim()
            if (!IpNetwork.isValid(t)) fail("$label[$i] is not a valid IP address or CIDR range: ${Py.repr(t)}")
            t
        }
    }

    // ------------------------------------------------------------ whole config

    private fun validateOrThrow(payload: Any?, lotSizeFor: (String, String) -> Int?): StrategyDef {
        val raw = mapping(payload, "The request body")
        rejectUnknown(raw, CONFIG_FIELDS, "The request")

        // Legs first: the universe tab is derived from them when unnamed.
        val kind = choice(raw["strategy_kind"].orIfFalsy("batch"), listOf("batch", "signal"), "strategy_kind")
        val legs = legs(required(raw, "legs"), kind, lotSizeFor)

        val name = text(required(raw, "name"), "name", MAX_NAME_LENGTH)
        val direction = choice(raw["direction"].orIfFalsy("both"), Direction.entries.map { it.wire }, "direction")
        val tab = choice(raw["universe_tab"].orIfFalsy(tabForLegs(raw, legs)), UNIVERSE_TABS, "universe_tab")
        val underlying = text(required(raw, "underlying"), "underlying", MAX_UNDERLYING_LENGTH).uppercase()
        val underlyingExchange = choice(required(raw, "underlying_exchange"), UNDERLYING_EXCHANGES, "underlying_exchange")
        val strategyType = choice(raw["strategy_type"].orIfFalsy("intraday"), listOf("intraday", "positional"), "strategy_type")
        val product = choice(raw["product"].orIfFalsy("NRML"), PRODUCTS, "product")
        val pricetype = choice(raw["pricetype"].orIfFalsy("MARKET"), PRICETYPES, "pricetype")
        val overallSl = lossAmount(raw["overall_sl_mtm"], "overall_sl_mtm")
        val overallTarget = gainAmount(raw["overall_target_mtm"], "overall_target_mtm")
        val lock = lockProfit(raw["lock_profit"])
        val trailToEntry = boolean(raw.getOr("trail_sl_to_entry", false), "trail_sl_to_entry")
        val sched = scheduler(raw["scheduler"])
        val dailyLoss = lossAmount(raw["daily_loss_limit_inr"], "daily_loss_limit_inr")
        val allowlist = ipAllowlist(raw["webhook_ip_allowlist"])

        // An intraday strategy with no exit time has nothing to square off against.
        val entryRaw = raw["entry_time"]
        val exitRaw = raw["exit_time"]
        if (strategyType == "intraday") {
            if (entryRaw == null) fail("entry_time is required for an intraday strategy")
            if (exitRaw == null) fail("exit_time is required for an intraday strategy")
        }
        val entryTime = entryRaw?.let { hhmm(it, "entry_time") }
        val exitTime = exitRaw?.let { hhmm(it, "exit_time") }
        if (entryTime != null && exitTime != null && entryTime >= exitTime) fail("entry_time must be earlier than exit_time")

        val def = StrategyDef(
            name = name, kind = wireOf<StrategyKind>(kind)!!, direction = wireOf<Direction>(direction)!!,
            universeTab = wireOf<UniverseTab>(tab)!!, underlying = underlying, underlyingExchange = underlyingExchange,
            strategyType = wireOf<StrategyType>(strategyType)!!, entryTime = entryTime, exitTime = exitTime,
            product = wireOf<Product>(product)!!, pricetype = pricetype, legs = legs,
            overallSlMtm = overallSl, overallTargetMtm = overallTarget, lockProfit = lock,
            trailSlToEntry = trailToEntry, scheduler = sched, dailyLossLimitInr = dailyLoss,
            webhookIpAllowlist = allowlist,
        )
        rejectContradictorySides(def)
        rejectSegmentsOutsideTab(def)
        rejectCashOnDerivativeVenue(def)
        rejectUncoverableShortCash(def)
        return def
    }

    /** The tab a config belongs to, read off its validated legs when the caller named none. */
    private fun tabForLegs(raw: Map<String, Any?>, legs: List<LegDef>): String {
        if (legs.any { it.segment == Segment.CASH }) return "stocks_fno"
        val exch = raw["underlying_exchange"].orIfFalsy("")
        if ((if (exch is String) exch else Py.str(exch)).uppercase() in COMMODITY_EXCHANGES) return "mcx"
        val ranks = legs.map { (it.expiry ?: "").lowercase() }.toSet()
        return if (ranks.any { it in WEEKLY_RANKS }) "weekly_monthly" else "monthly_only"
    }

    /** A cash leg on an index tab names the index itself, which has no cash instrument. */
    private fun rejectSegmentsOutsideTab(def: StrategyDef) {
        val allowed = def.universeTab.segments
        def.legs.forEachIndexed { i, leg ->
            if (leg.segment !in allowed) {
                fail(
                    "legs[$i].segment is ${Py.repr(leg.segment.wire)}, which the ${Py.repr(def.universeTab.wire)} universe does not offer. " +
                        "That tab trades ${allowed.joinToString(" and ") { it.wire }}.",
                )
            }
        }
    }

    /** A signal leg names its own venue, so its segment and exchange must agree. */
    private fun rejectCashOnDerivativeVenue(def: StrategyDef) {
        if (def.kind != StrategyKind.SIGNAL) return
        def.legs.forEachIndexed { i, leg ->
            val exchange = leg.exchange ?: return@forEachIndexed
            val derivative = exchange in MasterContract.DERIVATIVE_EXCHANGES
            if (leg.segment == Segment.CASH && derivative) {
                fail("legs[$i] is a cash leg on $exchange, which lists derivatives. Use NSE or BSE for cash, or set the segment to 'futures'.")
            }
            if (leg.segment == Segment.FUTURES && !derivative) {
                fail("legs[$i] is a futures leg on $exchange, which lists cash. Use a derivative exchange, or set the segment to 'cash'.")
            }
        }
    }

    /** Cash cannot be carried short: under a carry product a short cash leg is a naked short delivery. */
    private fun rejectUncoverableShortCash(def: StrategyDef) {
        if (def.kind == StrategyKind.SIGNAL || def.product == Product.MIS) return
        def.legs.forEachIndexed { i, leg ->
            if (leg.segment == Segment.CASH && leg.position == Position.S) {
                fail(
                    "legs[$i] sells cash short, but product ${Py.repr(def.product.wire)} carries the position. " +
                        "Cash cannot be held short overnight. Use MIS for an intraday short, or make the leg long.",
                )
            }
        }
    }

    /** A leg whose side the strategy's direction can never act on is configuration that never trades. */
    private fun rejectContradictorySides(def: StrategyDef) {
        if (def.kind != StrategyKind.SIGNAL) return
        val accepted = when (def.direction) {
            Direction.BOTH -> setOf("long", "short", "both")
            Direction.LONG_ONLY -> setOf("long", "both")
            Direction.SHORT_ONLY -> setOf("short", "both")
        }
        def.legs.forEachIndexed { i, leg ->
            val side = leg.side?.wire ?: "both"
            if (side !in accepted) {
                fail(
                    "legs[$i].side is ${Py.repr(side)}, which a ${Py.repr(def.direction.wire)} strategy never acts on. " +
                        "Use ${accepted.sorted().joinToString(" or ")}, or change the strategy direction.",
                )
            }
        }
    }
}

/**
 * Python's `ipaddress.ip_network(text, strict=False)` acceptance, without
 * touching the network: `InetAddress.getByName` would resolve a hostname,
 * which is I/O. Accepts IPv4 with a prefix, netmask or hostmask, and IPv6
 * with a prefix.
 */
internal object IpNetwork {
    fun isValid(text: String): Boolean {
        val parts = text.split("/")
        if (parts.size > 2) return false
        val addr = parts[0]
        val v4 = ipv4(addr)
        if (v4 != null) {
            if (parts.size == 1) return true
            val p = parts[1]
            if (Regex("\\d+").matches(p)) return prefix(p, 32)
            val mask = ipv4(p) ?: return false
            return contiguous(mask) || contiguous(mask.inv())
        }
        if (!ipv6(addr)) return false
        if (parts.size == 1) return true
        val p = parts[1]
        return Regex("\\d+").matches(p) && prefix(p, 128)
    }

    private fun prefix(p: String, max: Int): Boolean {
        val digits = p.trimStart('0').ifEmpty { "0" }
        return digits.length <= 3 && digits.toInt() <= max
    }

    private fun contiguous(mask: Long): Boolean {
        val m = mask and 0xFFFFFFFFL
        val inverted = m.inv() and 0xFFFFFFFFL
        return inverted and (inverted + 1) == 0L
    }

    private fun ipv4(s: String): Long? {
        val octets = s.split(".")
        if (octets.size != 4) return null
        var v = 0L
        for (o in octets) {
            if (o.isEmpty() || o.length > 3 || !o.all { it in '0'..'9' }) return null
            if (o.length > 1 && o[0] == '0') return null
            val n = o.toInt()
            if (n > 255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    private fun ipv6(s0: String): Boolean {
        val s = s0.substringBefore('%').also { if (s0.contains('%') && s0.substringAfter('%').isEmpty()) return false }
        if (s.isEmpty() || s.count { it == ':' } < 2) return false
        var groups = s.split(":").toMutableList()
        if (groups.last().contains('.')) {
            if (ipv4(groups.last()) == null) return false
            groups[groups.size - 1] = "0"
            groups.add("0")
        }
        val doubleColon = s.indexOf("::")
        if (doubleColon != s.lastIndexOf("::")) return false
        if (doubleColon >= 0) {
            if (groups.first().isEmpty()) groups = groups.drop(1).toMutableList()
            if (groups.isNotEmpty() && groups.last().isEmpty()) groups = groups.dropLast(1).toMutableList()
            val empties = groups.count { it.isEmpty() }
            if (empties > 1) return false
            val explicit = groups.filter { it.isNotEmpty() }
            if (explicit.size > 7) return false
            return explicit.all { hex(it) }
        }
        if (groups.size != 8) return false
        return groups.all { hex(it) }
    }

    private fun hex(g: String) = g.isNotEmpty() && g.length <= 4 && g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
