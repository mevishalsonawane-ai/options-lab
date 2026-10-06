package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The expiry-day "Hero" arm (rule A of the zero-to-hero study: late straddle expansion plus momentum). NOT PROVEN,
 * PAPER ONLY: in-sample (NIFTY 2023-24) +Rs 4.40 lakh on 20 trades, three of which made all of it; out-of-sample
 * (NIFTY 2025 - Apr 2026) 13 trades, 0 wins, -Rs 64,190. It never has a path to Zerodha ([ARM] is paperOnly).
 *
 *   scope     NIFTY options of TODAY's expiry, on a day that is the nearest NIFTY expiry in the instrument master
 *             (never the weekday: the expiry weekday moved and holidays shift it) - [isExpiryDay]
 *   straddle  at each minute u from 12:00: CE + PE last price at the ATM strike (step 50) of that minute; a leg that has
 *             not traded for more than [LEG_STALE_MINUTES] makes the minute invalid ([legPrice]); invalid minutes never
 *             move the day's straddle low
 *   window    bars closing 13:30 .. 14:45 (inclusive)
 *   trigger   straddle >= 15% above its low since 12:00 AND the index moved >= 0.25% in 15 minutes: up buys the CE,
 *             down buys the PE ([scan])
 *   strike    the nearest-the-money OTM option on that side priced Rs 1-5, traded in the last 5 bars, with an ask, and
 *             not stale (no further-OTM strike priced above it by more than max(0.10, 25%)) ([pick])
 *   order     LIMIT BUY at ask + 1 tick (2 ticks under Rs 2), capped at signal price x 1.2 + 0.10, on the 0.05 tick
 *             ([limitPrice]); never a market order; lots = floor(5,000 / (limit x lot size from the master)), 0 skips
 *             ([lots]); one entry a day ([mayEnter])
 *   exit      held to [EXIT_AT] (15:05, the minute the app's expiry square-off closes everything expiring today - the
 *             study held to 15:25, but the square-off would close it first under another name), then a LIMIT SELL at
 *             the bid less 1 tick ([exitLimit]), with the app's usual market-sell fallback
 *   stale     more than [MAX_SKIPPED] skipped minutes in a row inside the window: stand down for the day
 *   disarm    [MAX_LOSING_DAYS] losing firing days in a row, or [MAX_LOSS] lost since it was armed ([killReason])
 *
 * Every time here is the minute a 1-minute bar CLOSES (a bar labelled 13:29 closes at 13:30). Pure: no clock, no
 * network, no orders.
 */
object HeroRules {
    val ARM = Arm("hero", "Hero (expiry)", paperOnly = true, hero = true)
    /** The owner label its orders carry ("Hero · entry", "Hero · exit"), for charges, the scorecard and SmallTrades. */
    const val OWNER = "Hero"
    /** Shown wherever the arm is. */
    const val NOT_PROVEN = "Not proven — paper only"

    const val UNDERLYING = "NIFTY"
    const val STRIKE_STEP = 50
    const val TICK = 0.05
    const val BUDGET = 5_000.0
    const val ST_EXP = 0.15
    const val MOM = 0.0025
    const val MOM_MINUTES = 15L
    const val MIN_PRICE = 1.0
    const val MAX_PRICE = 5.0
    /** Candidates within this share of spot. */
    const val SPOT_BAND = 0.03
    const val LEG_STALE_MINUTES = 2L
    /** Bars t-4 .. t: a candidate must have traded in one of them. */
    const val TRADED_BARS = 5L
    const val MAX_SKIPPED = 5
    const val MAX_LOSING_DAYS = 12
    const val MAX_LOSS = 50_000.0

    val LOW_FROM: LocalTime = LocalTime.of(12, 0)
    val FIRST: LocalTime = LocalTime.of(13, 30)
    val LAST: LocalTime = LocalTime.of(14, 45)
    /** = ExpirySquareOff.AT_MINUTE in the app (15:05); the app's tests hold the two together. */
    val EXIT_AT: LocalTime = LocalTime.of(15, 5)

    private const val EPS = 1e-9

    /** Today is the nearest listed NIFTY expiry on or after today. An empty list (no master) is never an expiry day. */
    fun isExpiryDay(today: LocalDate, expiries: Collection<LocalDate>): Boolean =
        expiries.filter { !it.isBefore(today) }.minOrNull() == today

    fun atm(spot: Double): Int = OrbRules.atmStrike(spot, STRIKE_STEP)

    /** One option minute: its close and the volume traded in it. */
    data class Leg(val close: Double, val volume: Long)

    /**
     * A leg's last price at the close of minute [u] (the newest bar closing at or before it), or null when it has none
     * or has not traded (volume > 0) since [LEG_STALE_MINUTES] before [u].
     */
    fun legPrice(bars: Map<LocalTime, Leg>, u: LocalTime): Double? {
        var last: LocalTime? = null
        var traded: LocalTime? = null
        for ((t, b) in bars) {
            if (t.isAfter(u)) continue
            if (last == null || t.isAfter(last)) last = t
            if (b.volume > 0 && (traded == null || t.isAfter(traded))) traded = t
        }
        if (last == null || traded == null || traded.isBefore(u.minusMinutes(LEG_STALE_MINUTES))) return null
        return bars.getValue(last).close
    }

    /** One minute's verdict. [signal]: +1 buy the CE, -1 buy the PE, 0 none ([why] says why). */
    data class Scan(val at: LocalTime, val signal: Int, val why: String, val stExp: Double? = null, val mom: Double? = null,
                    val spot: Double? = null, val skippedInRow: Int = 0, val standDown: Boolean = false)

    /**
     * The rule at the close of minute [at], from the index closes ([spot]) and the valid straddle values ([straddle];
     * a minute with an invalid straddle is simply absent), both keyed by the minute each bar closes. A minute inside the
     * window with no index close, no straddle or no index close 15 minutes earlier is skipped; more than [MAX_SKIPPED]
     * skipped in a row (up to [at]) stands the arm down for the day.
     */
    fun scan(spot: Map<LocalTime, Double>, straddle: Map<LocalTime, Double>, at: LocalTime): Scan {
        var low: Double? = null
        var row = 0
        var standDown = false
        val minutes = java.time.Duration.between(LOW_FROM, at).toMinutes()
        for (k in 0..minutes) {
            val u = LOW_FROM.plusMinutes(k)
            val s = straddle[u]
            val valid = spot[u] != null && s != null
            if (valid) low = min(low ?: s!!, s!!)
            if (!u.isBefore(FIRST) && !u.isAfter(LAST)) {
                if (valid && spot[u.minusMinutes(MOM_MINUTES)] != null) row = 0
                else { row++; if (row > MAX_SKIPPED) standDown = true }
            }
        }
        if (at.isBefore(FIRST)) return Scan(at, 0, "before_window", skippedInRow = row)
        if (at.isAfter(LAST)) return Scan(at, 0, "after_window", skippedInRow = row, standDown = standDown)
        if (standDown) return Scan(at, 0, "stood_down_stale_data", skippedInRow = row, standDown = true)
        val s = spot[at]; val st = straddle[at]; val back = spot[at.minusMinutes(MOM_MINUTES)]
        if (s == null || st == null || back == null || low == null || low <= EPS || back <= EPS)
            return Scan(at, 0, "skipped_stale_bar", spot = s, skippedInRow = row)
        val exp = st / low - 1
        val mom = s / back - 1
        val signal = when {
            exp < ST_EXP - EPS -> 0
            mom >= MOM - EPS -> 1
            mom <= -MOM + EPS -> -1
            else -> 0
        }
        val why = when {
            signal > 0 -> "fire_up"
            signal < 0 -> "fire_down"
            exp < ST_EXP - EPS -> "straddle_not_expanded"
            else -> "no_momentum"
        }
        return Scan(at, signal, why, exp, mom, s, row)
    }

    /** An option of today's expiry on the signal side at the signal bar. [ask] null: no ask. */
    data class Candidate(val strike: Double, val ltp: Double, val ask: Double?, val tradedRecently: Boolean)

    /**
     * The nearest-the-money OTM candidate on [side] (+1 CE: strike above [spot]; -1 PE: below) priced [MIN_PRICE] ..
     * [MAX_PRICE], traded recently, with an ask, and not stale: no further-OTM strike on that side priced above
     * ltp + max(0.10, 25% of ltp). Null: no trade on this bar.
     */
    fun pick(side: Int, spot: Double, chain: List<Candidate>): Candidate? {
        val otm = chain.filter { if (side > 0) it.strike > spot + EPS else it.strike < spot - EPS }
            .sortedBy { abs(it.strike - spot) }
        for ((i, c) in otm.withIndex()) {
            if (c.ltp < MIN_PRICE - EPS || c.ltp > MAX_PRICE + EPS) continue
            if (!c.tradedRecently || c.ask == null || c.ask <= EPS) continue
            val slack = max(0.10, 0.25 * c.ltp)
            if (otm.drop(i + 1).any { it.ltp > c.ltp + slack + EPS }) continue
            return c
        }
        return null
    }

    /** [x] down to the tick (never above it: the cap is a cap). */
    fun floorTick(x: Double): Double = Math.round(floor(x / TICK + 1e-6) * TICK * 100.0) / 100.0

    /** The entry limit: min(ask + 1 tick (2 under Rs 2), ref x 1.20 + 0.10), on the tick. */
    fun limitPrice(ask: Double, ref: Double): Double {
        val ticks = if (ask < 2.0 - EPS) 2 else 1
        return floorTick(min(ask + ticks * TICK, ref * 1.20 + 0.10))
    }

    /** Lots for the [budget]: floor(budget / (limit x lot)); 0 (skip, never rounded up) when the lot is unknown. */
    fun lots(limit: Double, lotSize: Int, budget: Double = BUDGET): Int =
        if (limit <= EPS || lotSize <= 0) 0 else floor(budget / (limit * lotSize) + 1e-9).toInt()

    /** One entry a day. */
    fun mayEnter(entriesToday: Int): Boolean = entriesToday < 1

    /** The exit is due at [EXIT_AT] (15:05). */
    fun exitDue(t: LocalTime): Boolean = !t.isBefore(EXIT_AT)

    /** The exit limit: the bid less 1 tick, never below one tick. */
    fun exitLimit(bid: Double): Double = max(floorTick(bid - TICK), TICK)

    /**
     * Why the arm disarms itself, or null: [days] are its firing days since it was armed, oldest first, each day's net
     * P&L after charges. [MAX_LOSING_DAYS] losing days in a row at the end, or [MAX_LOSS] or more lost in all.
     */
    fun killReason(days: List<Double>): String? {
        val losingRun = days.asReversed().takeWhile { it < 0 }.size
        if (losingRun >= MAX_LOSING_DAYS) return "$losingRun losing firing days in a row"
        val total = days.sum()
        if (total <= -MAX_LOSS + EPS) return "Rs %,.0f lost since it was armed".format(java.util.Locale.ENGLISH, -total)
        return null
    }
}
