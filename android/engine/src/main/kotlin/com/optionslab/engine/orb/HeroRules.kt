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
 *   exits     (Boss's 06 Oct choice: the deep study's D09 / F07 exits, still NOT PROVEN, still paper only) measured
 *             against the entry FILL price e ([exitStep]), checked on every pass of the app's watch like the other
 *             arms' exits - not resting orders: the study's stop is on a minute's close, which no SL-M can express, and
 *             a resting stop beside a resting target would sell the same lots twice:
 *               5x     the last price trades >= floorTick(5e) + 1 tick: LIMIT SELL [halfLots] of the lots at floorTick(5e)
 *                      (round(lots / 2) half-to-even as the study's Python round, at least 1: 1 lot sells it all here)
 *               20x    after the half, the last price >= floorTick(20e) + 1 tick: LIMIT SELL the rest at floorTick(20e)
 *               15:05  [EXIT_AT] (the minute the app's expiry square-off closes everything expiring today): the rest
 *                      sells, a LIMIT SELL at the bid less 1 tick ([exitLimit]) with the app's market-sell fallback
 *               stop   a 1-minute bar of the option closing after the fill's own minute and before 15:05 at or below
 *                      0.4e (-60% of the premium): the rest sells as at 15:05. Still active after the half is sold.
 *             Checked in that order (targets, then 15:05, then the stop), as the study did within a minute.
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

    // ---- exits: the study's D09 / F07 (half at 5x, the rest at 20x or 15:05, a -60% premium stop) ----------------

    const val TARGET1 = 5.0
    const val TARGET2 = 20.0
    /** The share of the lots sold at [TARGET1]. */
    const val HALF = 0.5
    /** The stop: a minute's close at or below the entry x (1 - [STOP]). */
    const val STOP = 0.6
    /** The exits in a few words, for the arm's row, its dialog and Jarvis. */
    const val EXITS = "half out at 5×, the rest at 20× or 15:05, stop −60% of the premium"

    /** The first target's limit: 5 x the entry fill, down to the tick. */
    fun target1(entry: Double): Double = floorTick(entry * TARGET1)
    /** The second target's limit (the rest, once the half is sold): 20 x the entry fill, down to the tick. */
    fun target2(entry: Double): Double = floorTick(entry * TARGET2)
    /** The stop level: entry x 0.4. A minute closing at or below it sells what is held. */
    fun stopLevel(entry: Double): Double = entry * (1 - STOP)

    /**
     * Lots sold at the first target out of [lots]: round(lots x 0.5) rounded half to EVEN (the study's Python round:
     * 3 -> 2, 5 -> 2, 7 -> 4), at least 1 (1 lot: all of it), never more than [lots].
     */
    fun halfLots(lots: Int): Int = if (lots <= 0) 0 else min(lots, max(1, Math.rint(lots * HALF).toInt()))

    /** A resting LIMIT SELL at [target] fills once the price trades a tick through it (the study's fill model). */
    fun targetHit(target: Double, ltp: Double?): Boolean = ltp != null && ltp >= target + TICK - EPS

    /** Any of [closes] (1-minute closes after the fill's minute, before 15:05) at or below [stopLevel]. */
    fun stopHit(entry: Double, closes: Collection<Double>): Boolean = closes.any { it <= stopLevel(entry) + EPS }

    enum class ExitKind(val why: String) {
        HALF("hero_5x"), REST("hero_20x"), TIME("hero_exit"), STOP("hero_stop")
    }

    /** What to sell now: [lots] lots, at the LIMIT [limit], or (null) at the bid less a tick ([exitLimit]). */
    data class ExitStep(val kind: ExitKind, val lots: Int, val limit: Double?)

    /**
     * The next exit, or null to hold. [entry] the entry fill; [lotsHeld] lots still held; [halfSold] the first target
     * has sold its part; [ltp] the last price now; [closes] the option's 1-minute closes after the fill's own minute and
     * before 15:05 ([stopMinutes]); [t] now; [overnight] the position is from an earlier day. Targets first, then 15:05,
     * then the stop.
     */
    fun exitStep(entry: Double, lotsHeld: Int, halfSold: Boolean, ltp: Double?, closes: Collection<Double>, t: LocalTime,
                 overnight: Boolean = false): ExitStep? {
        if (lotsHeld <= 0 || entry <= EPS) return null
        if (!halfSold && targetHit(target1(entry), ltp)) return ExitStep(ExitKind.HALF, halfLots(lotsHeld), target1(entry))
        if (halfSold && targetHit(target2(entry), ltp)) return ExitStep(ExitKind.REST, lotsHeld, target2(entry))
        if (overnight || exitDue(t)) return ExitStep(ExitKind.TIME, lotsHeld, null)
        if (stopHit(entry, closes)) return ExitStep(ExitKind.STOP, lotsHeld, null)
        return null
    }

    /**
     * The minutes (each bar's CLOSE time) whose closes the stop reads: bars that start after the fill's own minute
     * ([entry] falls in it; the study checks from the bar after the fill bar) and close before 15:05.
     */
    fun stopMinutes(entry: LocalTime, closeTimes: Collection<LocalTime>): List<LocalTime> {
        val first = entry.withSecond(0).withNano(0).plusMinutes(2)
        return closeTimes.filter { !it.isBefore(first) && it.isBefore(EXIT_AT) }.sorted()
    }

    /**
     * One look at the option's book for a Hero trade - at the signal minute, or at an exit - so the spread on these
     * Rs 1-5 options can be measured. [bid] / [ask] null: the feed had no depth (the Upstox candles; only Zerodha's
     * stream has it), and the last price alone was logged.
     */
    data class Seen(val what: String, val at: LocalTime, val ltp: Double?, val bid: Double?, val ask: Double?,
                    val bidQty: Long? = null, val askQty: Long? = null) {
        val spread: Double? get() = if (bid != null && ask != null && bid > EPS && ask > EPS) ask - bid else null
    }

    /** "signal 13:46 · bid 3.95 ×1,300 / ask 4.05 ×650 · spread 0.10 (2.5%)", or the last price alone. */
    fun seenLine(s: Seen): String {
        val l = java.util.Locale.ENGLISH
        val head = "${s.what} %02d:%02d".format(l, s.at.hour, s.at.minute)
        val sp = s.spread ?: return "$head · LTP ${s.ltp?.let { "%.2f".format(l, it) } ?: "-"} only (no depth in the feed)"
        fun q(x: Long?) = x?.takeIf { it > 0 }?.let { " ×%,d".format(l, it) } ?: ""
        val mid = (s.bid!! + s.ask!!) / 2
        return "$head · bid %.2f%s / ask %.2f%s · spread %.2f (%.1f%%)".format(l, s.bid, q(s.bidQty), s.ask, q(s.askQty), sp, sp / mid * 100)
    }

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
