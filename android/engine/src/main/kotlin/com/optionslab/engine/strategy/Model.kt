package com.optionslab.engine.strategy

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

/**
 * The Strategy Module's configuration, ported from IraAlgo's
 * `database/strategy_module_db.py` and the validator in
 * `blueprints/strategy_module.py`.
 *
 * Two kinds share one engine:
 *
 * - **batch**: a basket of up to ten legs entered and exited as a unit;
 * - **signal**: one leg per alert (`long_entry`, `long_exit`, `short_entry`,
 *   `short_exit`), where a leg names its own instrument and can be flipped.
 *
 * Every enum carries the wire spelling the Python stores, so a definition
 * round-trips through [StrategyCodec] into exactly the JSON IraAlgo would
 * have written, and a Python-written config reads back here unchanged.
 */

/** Every trading time in this product is IST. */
val IST: ZoneId = ZoneId.of("Asia/Kolkata")

/** Wire-spelled enums share one lookup. */
interface Wire { val wire: String }

inline fun <reified E> wireOf(text: String?): E? where E : Enum<E>, E : Wire =
    enumValues<E>().firstOrNull { it.wire == text }

enum class StrategyKind(override val wire: String) : Wire { BATCH("batch"), SIGNAL("signal") }

/** Signal-mode direction filter; inert on a batch strategy. */
enum class Direction(override val wire: String) : Wire { BOTH("both"), LONG_ONLY("long_only"), SHORT_ONLY("short_only") }

/** Intraday strategies must name entry and exit times; positional ones may carry across days. */
enum class StrategyType(override val wire: String) : Wire { INTRADAY("intraday"), POSITIONAL("positional") }

/** A run's mode decides its pipe. Live is opt-in per strategy; a strategy is born sandbox-only. */
enum class RunMode(override val wire: String) : Wire { LIVE("live"), SANDBOX("sandbox") }

/**
 * The product a strategy asks for, read as intent: MIS is intraday
 * everywhere, anything else means "carry", which is NRML on a derivatives
 * venue and CNC on cash (see [OrderRules.productForExchange]).
 */
enum class Product(override val wire: String) : Wire { CNC("CNC"), NRML("NRML"), MIS("MIS") }

enum class Segment(override val wire: String) : Wire { CASH("cash"), FUTURES("futures"), OPTIONS("options") }

/** A batch leg's side. B/S rather than BUY/SELL because that is what run state records. */
enum class Position(override val wire: String) : Wire { B("B"), S("S") }

enum class OptionType(override val wire: String) : Wire { CE("CE"), PE("PE") }

enum class StrikeMode(override val wire: String) : Wire { ATM("atm"), STRIKE("strike") }

/**
 * Relative expiry. `current`/`next` are the segment-neutral spelling of
 * `weekly`/`next_week` (MCX has no weekly in the NFO sense) and resolve
 * identically.
 */
enum class ExpiryRank(override val wire: String) : Wire {
    WEEKLY("weekly"), NEXT_WEEK("next_week"), MONTHLY("monthly"), NEXT_MONTH("next_month"), CURRENT("current"), NEXT("next")
}

/** How a leg's stop, target and trail are expressed; one toggle covers all three. */
enum class RiskUnit(override val wire: String) : Wire { POINTS("points"), PERCENT("percent") }

/** Which signals a signal leg accepts - not the side it is held, which the signal decides. */
enum class LegSide(override val wire: String) : Wire { LONG("long"), SHORT("short"), BOTH("both") }

/** A signal leg's quantity: a lot count, or the quantity itself. Storing lots survives a lot-size revision. */
enum class QtyMode(override val wire: String) : Wire { LOTS("lots"), UNITS("units") }

/** The wizard's instrument grouping, which decides which segments a leg may use. */
enum class UniverseTab(override val wire: String, val segments: List<Segment>) : Wire {
    WEEKLY_MONTHLY("weekly_monthly", listOf(Segment.FUTURES, Segment.OPTIONS)),
    MONTHLY_ONLY("monthly_only", listOf(Segment.FUTURES, Segment.OPTIONS)),
    STOCKS_FNO("stocks_fno", listOf(Segment.CASH, Segment.FUTURES, Segment.OPTIONS)),
    MCX("mcx", listOf(Segment.FUTURES, Segment.OPTIONS)),
}

enum class LockProfitMode(override val wire: String) : Wire { LOCK("lock"), LOCK_AND_TRAIL("lock_and_trail") }

/**
 * `{x, y}`: arm at x of favourable movement; with y > 0 advance the stop by y
 * per x (stepped), with y = 0 hold a fixed x-point gap behind the extreme
 * (continuous). Read in the leg's own [RiskUnit].
 */
data class Trail(val x: Double, val y: Double)

/**
 * One leg. Batch and signal legs are different shapes in the Python, and the
 * fields that do not apply to a kind are null here:
 *
 * - batch: [position], [lots], and for options [optionType], [strikeMode],
 *   [atmOffset] or [strike]; [expiry] for derivatives (a rank, or a literal
 *   date such as `28-MAY-26` for calendars and diagonals);
 * - signal: [symbol], [exchange], [side], [qty], [qtyMode], and [expiry]
 *   (descriptive only) on a futures leg.
 *
 * [strikeInt] is the resolver's optional strike interval: set, the ATM is
 * plain arithmetic; unset (the normal case), the listed strikes are walked.
 */
data class LegDef(
    val id: Int,
    val segment: Segment,
    val position: Position? = null,
    val lots: Int = 1,
    val optionType: OptionType? = null,
    val strikeMode: StrikeMode? = null,
    val atmOffset: String? = null,
    val strike: Double? = null,
    val expiry: String? = null,
    val symbol: String? = null,
    val exchange: String? = null,
    val side: LegSide? = null,
    val qty: Int? = null,
    val qtyMode: QtyMode? = null,
    val slPts: Double? = null,
    val targetPts: Double? = null,
    val trail: Trail? = null,
    val riskUnit: RiskUnit = RiskUnit.POINTS,
    val strikeInt: Double? = null,
)

/**
 * Basket lock profit: arm once MTM reaches [ifProfitReaches], then hold a
 * floor at [lockProfit]; in `lock_and_trail` the floor also rises to
 * `peak - trailStep` and never falls back.
 */
data class LockProfit(
    val mode: LockProfitMode,
    val ifProfitReaches: Double,
    val lockProfit: Double,
    val trailStep: Double? = null,
    /**
     * Whether [lockProfit] was written as a whole number (`1000`, not
     * `1000.0`). Python keeps the literal's type through the floor ratchet, so
     * its audit line reads "a floor of 1000" for one and "1000.0" for the
     * other; this is only how that line prints, never a rule.
     */
    val lockProfitWhole: Boolean = false,
)

/** Timed start and square-off on chosen weekdays, in IST. */
data class SchedulerConfig(
    val enabled: Boolean = false,
    val days: List<DayOfWeek> = emptyList(),
    val startTime: LocalTime? = null,
    val autoStopTime: LocalTime? = null,
    val defaultMode: RunMode = RunMode.SANDBOX,
)

/**
 * A whole strategy. [overallSlMtm] and [dailyLossLimitInr] are entered as
 * positive amounts and applied as negative thresholds. The daily loss limit is
 * measured across the platform session (see [Session]), not one run, so it
 * survives the strategy being started and stopped several times a day.
 */
data class StrategyDef(
    val id: Long = 0,
    val name: String,
    val kind: StrategyKind = StrategyKind.BATCH,
    val direction: Direction = Direction.BOTH,
    val universeTab: UniverseTab = UniverseTab.WEEKLY_MONTHLY,
    val underlying: String,
    val underlyingExchange: String,
    val strategyType: StrategyType = StrategyType.INTRADAY,
    val entryTime: LocalTime? = null,
    val exitTime: LocalTime? = null,
    val product: Product = Product.NRML,
    val pricetype: String = "MARKET",
    val legs: List<LegDef>,
    val overallSlMtm: Double? = null,
    val overallTargetMtm: Double? = null,
    val lockProfit: LockProfit? = null,
    val trailSlToEntry: Boolean = false,
    val scheduler: SchedulerConfig? = null,
    val dailyLossLimitInr: Double? = null,
    val liveEnabled: Boolean = false,
    val webhookIpAllowlist: List<String>? = null,
) {
    companion object {
        /** The leg cap. Ten is what the wizard offers and what the validator enforces. */
        const val MAX_LEGS = 10
    }
}
