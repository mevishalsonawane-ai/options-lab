package com.optionslab.ira

import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.ProfitLock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The pre-trade checks (TODO I3, I5, I4; research/HUNT_R17_PRETRADE.md, 13 Oct 2026). All three FAILED their locked
 * holdout test, so in the app they are words and shadow lines only: they never skip, resize or move anything on their
 * own. The one switch that may act ([DangerMeter.lots], off by default) only ever halves a paper arm's lots.
 *
 *  - [RangeModel]   R16's expected high-low of the next 30 / 60 minutes (the one thing R16 found predictable)
 *  - [OptionDelta]  how much the option moves per index point (Black-Scholes at the option's own implied vol)
 *  - [ChargesCheck] "costs take X% of the expected move" (I3), and whether the 10% line would have skipped the trade
 *  - [SmartStop]    "smart stop would be N points; fixed stop is M" (I5, k = 1)
 *  - [DangerMeter]  "Today: normal / careful / paper-only day" from gap, VIX, VIX change, event, expiry, weekday (I4)
 *
 * Pure: no clock, no I/O, so it may run beside an arm's entry.
 *
 * Not exactly the research's model (review, 13 Oct): R17 refit the range model every month on earlier sessions, with a
 * trailing 20-session time-of-day term, and refit the danger meter monthly. The app uses R16's fixed coefficients, R17's
 * design-period time-of-day means and one fixed danger model (research/HUNT_R17_PRETRADE.md, "In the app").
 */
object RangeModel {
    /** R16's portable formula (fitted on R16's DESIGN rows): log range = c + a tod + b log r30 + d log r60 + e log VIX. */
    data class Coef(val c: Double, val tod: Double, val r30: Double, val r60: Double, val vix: Double)

    private val COEF = mapOf(
        ("BANKNIFTY" to 30) to Coef(-2.491, 0.159, 0.261, 0.292, 0.234),
        ("BANKNIFTY" to 60) to Coef(-2.656, 0.113, 0.253, 0.291, 0.282),
        ("NIFTY" to 30) to Coef(-2.702, 0.101, 0.272, 0.320, 0.239),
        ("NIFTY" to 60) to Coef(-2.906, 0.042, 0.287, 0.292, 0.276),
    )

    /**
     * The time-of-day term: the mean log range (over the price) of the next 30 / 60 minutes for decisions at 09:30, 09:45 ..
     * 15:00, from the R17 design sessions (BANKNIFTY 376, NIFTY 379; research/hunt/r17/results/app_constants.json). The app
     * keeps no 20 sessions of minutes at an entry, so this average stands in for R16's trailing 20-session one.
     */
    private val TOD = mapOf(
        ("BANKNIFTY" to 30) to listOf(-5.4958, -5.6353, -5.7037, -5.7838, -5.8674, -5.9154, -5.9511, -5.9915, -6.0624, -6.1191, -6.0927, -6.0981,
            -6.1109, -6.1178, -6.086, -6.0564, -6.0576, -6.0343, -6.0182, -5.9999, -5.9896, -5.9234, -5.9177),
        ("BANKNIFTY" to 60) to listOf(-5.2246, -5.3265, -5.4096, -5.4918, -5.5381, -5.5834, -5.6455, -5.6961, -5.7372, -5.7773, -5.7587, -5.7578,
            -5.7534, -5.7336, -5.7203, -5.7016, -5.6982, -5.6797, -5.6734, -5.6314, -5.6098, -5.6098, -5.6098),
        ("NIFTY" to 30) to listOf(-5.7736, -5.9022, -6.0096, -6.0611, -6.1579, -6.2, -6.2537, -6.2892, -6.3307, -6.3681, -6.3638, -6.3436,
            -6.3521, -6.3598, -6.3195, -6.3102, -6.3084, -6.2771, -6.2689, -6.2421, -6.2358, -6.1459, -6.2159),
        ("NIFTY" to 60) to listOf(-5.5074, -5.6049, -5.7005, -5.7596, -5.8359, -5.8818, -5.9325, -5.9695, -5.9761, -5.998, -5.9976, -5.9816,
            -5.9727, -5.9647, -5.9631, -5.9441, -5.9363, -5.9217, -5.9154, -5.839, -5.8618, -5.8618, -5.8618),
    )
    private const val FIRST_SLOT = 9 * 60 + 30
    /** A frozen feed's range of 0 counts as 0.001% of the price (as the research). */
    private const val FLOOR = 1e-5

    fun supports(underlying: String): Boolean = COEF.containsKey(underlying to 30)

    /**
     * The time-of-day term at [minuteOfDay] (the minute a decision is made, IST), the nearest 15-minute slot. Before 09:30
     * it is 09:30's slot and after 15:00 it is 15:00's (the research made no decision outside 09:30-15:00).
     */
    fun tod(underlying: String, minuteOfDay: Int, horizon: Int = 30): Double? {
        val t = TOD[underlying to horizon] ?: return null
        return t[((minuteOfDay - FIRST_SLOT) / 15.0).roundToInt().coerceIn(0, t.size - 1)]
    }

    /** The index's recent window: high / low of the last 30 and 60 minutes (or since 09:15 if fewer) and its price now. */
    data class Window(val high30: Double, val low30: Double, val high60: Double, val low60: Double, val price: Double, val minuteOfDay: Int)

    /**
     * Expected high-low of the next [horizon] minutes in index points (a median-type forecast), or null without the inputs.
     * A NaN or infinite input (a broken feed) gives null, never a number.
     */
    fun points(underlying: String, w: Window, vixPrev: Double?, horizon: Int = 30): Double? {
        val c = COEF[underlying to horizon] ?: return null
        val tod = tod(underlying, w.minuteOfDay, horizon) ?: return null
        if (vixPrev == null || !(vixPrev > 0) || !vixPrev.isFinite() || !(w.price > 0) || !w.price.isFinite()) return null
        if (!(w.high30.isFinite() && w.low30.isFinite() && w.high60.isFinite() && w.low60.isFinite())) return null
        if (!(w.high30 >= w.low30) || !(w.high60 >= w.low60)) return null
        fun lr(h: Double, l: Double) = ln(max(h - l, FLOOR * w.price) / w.price)
        val z = c.c + c.tod * tod + c.r30 * lr(w.high30, w.low30) + c.r60 * lr(w.high60, w.low60) + c.vix * ln(vixPrev)
        return (exp(z) * w.price).takeIf { it.isFinite() && it > 0 }
    }

    /**
     * The [Window] from 1-minute (or 5-minute) bars as (high, low), oldest first: the last 30 / 60 minutes of them. A bar
     * with a NaN or infinite side, or a high below its low, is left out; none left, or no usable [price]: null.
     */
    fun window(bars: List<Pair<Double, Double>>, barMinutes: Int, price: Double, minuteOfDay: Int): Window? {
        if (bars.isEmpty() || barMinutes <= 0 || !(price > 0) || !price.isFinite()) return null
        val ok = bars.filter { it.first.isFinite() && it.second.isFinite() && it.first >= it.second }
        if (ok.isEmpty()) return null
        val n30 = max(1, 30 / barMinutes); val n60 = max(1, 60 / barMinutes)
        val a = ok.takeLast(n30); val b = ok.takeLast(n60)
        return Window(a.maxOf { it.first }, a.minOf { it.second }, b.maxOf { it.first }, b.minOf { it.second }, price, minuteOfDay)
    }

    /**
     * One 1-minute bar as (high, low) for [window], as the research reads it (R17 uses 1-minute highs and lows). A bar with
     * no usable high / low (0, NaN, infinite, high below low) falls back to its close alone.
     */
    fun highLow(high: Double, low: Double, close: Double): Pair<Double, Double> =
        if (high.isFinite() && low.isFinite() && low > 0 && high >= low) max(high, close) to minOf(low, close) else close to close
}

/** Black-Scholes (r 6.5%, no dividend) delta at the option's own implied vol, VIX / 100 when none solves (R17 amendment 1). */
object OptionDelta {
    const val RATE = 0.065
    private const val MIN_VOL = 0.01
    private const val MAX_VOL = 3.0

    private fun ncdf(x: Double): Double {
        // Abramowitz-Stegun 26.2.17 (error < 7.5e-8): plenty for a delta.
        val t = 1.0 / (1.0 + 0.2316419 * abs(x))
        val d = 0.3989422804014327 * exp(-x * x / 2)
        val p = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        return if (x >= 0) 1 - p else p
    }

    private fun years(minutes: Double) = (if (minutes.isFinite()) max(minutes, 1.0) else 1.0) / (365.0 * 1440.0)

    /**
     * Minutes from [at] to [expiry]'s 15:30 (the index's close, when the option settles). On expiry day near or after
     * 15:30 it is 1 or less; the pricing then counts one minute (never a division by zero).
     */
    fun minutesTo(expiry: LocalDate, at: LocalDateTime): Double = Duration.between(at, expiry.atTime(15, 30)).toMinutes().toDouble()

    fun price(spot: Double, strike: Double, minutes: Double, vol: Double, call: Boolean): Double {
        val t = years(minutes)
        val d1 = (ln(spot / strike) + (RATE + vol * vol / 2) * t) / (vol * sqrt(t))
        val d2 = d1 - vol * sqrt(t)
        val df = exp(-RATE * t)
        return if (call) spot * ncdf(d1) - strike * df * ncdf(d2) else strike * df * ncdf(-d2) - spot * ncdf(-d1)
    }

    /** The vol at which [premium] is the Black-Scholes price (1% .. 300% a year), or null when none gives it. */
    fun impliedVol(premium: Double, spot: Double, strike: Double, minutes: Double, call: Boolean): Double? {
        if (!(premium > 0 && spot > 0 && strike > 0) || !premium.isFinite() || !spot.isFinite() || !strike.isFinite()) return null
        if (premium < price(spot, strike, minutes, MIN_VOL, call) || premium > price(spot, strike, minutes, MAX_VOL, call)) return null
        var lo = MIN_VOL; var hi = MAX_VOL
        repeat(60) { val mid = (lo + hi) / 2; if (price(spot, strike, minutes, mid, call) < premium) lo = mid else hi = mid }
        return (lo + hi) / 2
    }

    fun delta(spot: Double, strike: Double, minutes: Double, vol: Double, call: Boolean): Double {
        if (!(spot > 0 && strike > 0 && vol > 0) || !spot.isFinite() || !strike.isFinite() || !vol.isFinite()) return if (call) 0.5 else -0.5
        val t = years(minutes)
        val d1 = (ln(spot / strike) + (RATE + vol * vol / 2) * t) / (vol * sqrt(t))
        return if (call) ncdf(d1) else ncdf(d1) - 1
    }

    /** The delta to use: at the option's implied vol, else at [vixPrev] / 100, else null (nothing to price it with). */
    fun of(spot: Double, strike: Double, minutesToExpiry: Double, premium: Double, vixPrev: Double?, call: Boolean): Double? {
        val iv = impliedVol(premium, spot, strike, minutesToExpiry, call)
        val vol = iv ?: vixPrev?.takeIf { it > 0 && it.isFinite() }?.let { it / 100 } ?: return null
        return delta(spot, strike, minutesToExpiry, vol, call)
    }
}

/**
 * I3 (shadow): the round trip's costs per unit against the option's expected 30-minute move. R17: costs were a median
 * 3-6% of it on every arm; a 10% line (the design pick) skipped 3 of 499 holdout trades and failed (p 0.31). Logged only.
 */
object ChargesCheck {
    /** The would-skip line (R17's design pick; it failed the holdout, so nothing is skipped). */
    const val SKIP_AT = 0.10
    /** R17's holdout for the charges check: 499 trades across the arms (3 would-skips, p 0.31). */
    const val HOLDOUT_TRADES = 499
    /** Two fills' slippage: 5 bps and one tick each (the research's formula). */
    const val SLIP_BPS = 0.0005
    const val TICK = 0.05

    /** Charges for the round trip at [premium] ([ProfitLock.roundTripPerUnit]) plus two fills' slippage, per unit (0: no usable premium). */
    fun costPerUnit(premium: Double, qty: Int): Double =
        if (!(premium > 0) || !premium.isFinite()) 0.0 else ProfitLock.roundTripPerUnit(premium, qty) + 2 * (SLIP_BPS * premium + TICK)

    data class Reading(val costPerUnit: Double, val expectedPoints: Double?, val delta: Double?) {
        /** The option's expected move in premium points: index points x |delta|. */
        val premiumMove: Double? get() = if (expectedPoints != null && delta != null) (expectedPoints * abs(delta)).takeIf { it.isFinite() } else null
        val ratio: Double? get() = premiumMove?.takeIf { it > 0 }?.let { costPerUnit / it }?.takeIf { it.isFinite() }
        val wouldSkip: Boolean get() = (ratio ?: 0.0) > SKIP_AT

        fun line(): String {
            val r = ratio ?: return "Charges check: expected move not known (no VIX or index minutes yet); costs %s a unit".format(Locale.ENGLISH, f2(costPerUnit))
            return "Costs take %d%% of the expected move (%s a unit vs %s points in 30 min)".format(Locale.ENGLISH, (r * 100).roundToInt(),
                f2(costPerUnit), f1(premiumMove!!)) + if (wouldSkip) " · would skip at ${(SKIP_AT * 100).roundToInt()}% (shadow)" else ""
        }
    }

    fun read(premium: Double, qty: Int, expectedPoints: Double?, delta: Double?) = Reading(costPerUnit(premium, qty), expectedPoints, delta)

    internal fun f1(x: Double) = "%.1f".format(Locale.ENGLISH, x)
    internal fun f2(x: Double) = "₹%.2f".format(Locale.ENGLISH, x)
}

/** I5 (shadow): the stop the expected range would set, beside the arm's fixed stop. R17: no k beat the fixed stop. */
object SmartStop {
    /** k: the pre-registered default shown when no pick survives the holdout (none did). */
    const val K = 1.0
    /** R17's one holdout test of a smart stop (VIX divergence NIFTY, tight k 1): 15 trades, p 0.17. */
    const val HOLDOUT_TRADES = 15
    private const val FLOOR = 2 * ChargesCheck.TICK

    /** k x expected 30-minute index range x |delta|, in premium points; null without the inputs (or with a NaN / infinite one). */
    fun points(expectedPoints: Double?, delta: Double?, k: Double = K): Double? =
        if (expectedPoints == null || delta == null || !(expectedPoints > 0) || !expectedPoints.isFinite() || !delta.isFinite()) null
        else max(k * expectedPoints * abs(delta), FLOOR)

    /** "Smart stop would be N points; fixed stop is M" ([fixed] null: the arm has no stop in points). */
    fun line(smart: Double?, fixed: Double?): String = when {
        smart == null -> "Smart stop: not known (no expected range)"
        fixed == null -> "Smart stop would be %s points; this arm has no fixed premium stop".format(Locale.ENGLISH, ChargesCheck.f1(smart))
        else -> "Smart stop would be %s points; fixed stop is %s".format(Locale.ENGLISH, ChargesCheck.f1(smart), ChargesCheck.f1(fixed))
    }
}

/** One paper entry's shadow checks, kept in memory for Order Review and the arm's day detail. */
data class PreTradeNote(val arm: String, val label: String, val minuteOfDay: Int, val charges: ChargesCheck.Reading,
                        val smartStop: Double?, val fixedStop: Double?) {
    fun line(): String = "%02d:%02d %s: %s; %s".format(Locale.ENGLISH, minuteOfDay / 60, minuteOfDay % 60, label, charges.line(),
        SmartStop.line(smartStop, fixedStop).replaceFirstChar { it.lowercase() }) + if (arm == HeroRules.ARM.source) HERO_SAMPLE else ""

    companion object {
        /** Hero's R17 sample: 8 design and 2 holdout trades, far too few to judge either check on Hero. */
        const val HERO_DESIGN_TRADES = 8
        const val HERO_HOLDOUT_TRADES = 2
        internal const val HERO_SAMPLE = " (Hero's R17 sample is tiny: $HERO_DESIGN_TRADES design, $HERO_HOLDOUT_TRADES holdout trades)"
        /** The notes kept in memory: at most [MAX], from the [KEEP_DAYS] days before today and today. */
        const val MAX = 300
        const val KEEP_DAYS = 7L

        /** [notes] (oldest first) without those older than [keepDays] before [today], then the newest [max] of them. */
        fun <T> prune(notes: List<Pair<LocalDate, T>>, today: LocalDate, max: Int = MAX, keepDays: Long = KEEP_DAYS): List<Pair<LocalDate, T>> =
            notes.filter { !it.first.isBefore(today.minusDays(keepDays)) }.takeLast(max.coerceAtLeast(0))

        /** Order Review's day summary: how many entries, the range of the cost share, the would-skips. Empty with none. */
        fun summary(notes: List<PreTradeNote>): List<String> {
            if (notes.isEmpty()) return emptyList()
            val r = notes.mapNotNull { it.charges.ratio }.sorted()
            val skip = notes.count { it.charges.wouldSkip }
            val share = if (r.isEmpty()) "the expected move was not known" else
                "costs took %d-%d%% of the expected move (median %d%%)".format(Locale.ENGLISH, (r.first() * 100).roundToInt(),
                    (r.last() * 100).roundToInt(), (r[r.size / 2] * 100).roundToInt())
            val wider = notes.count { it.smartStop != null && it.fixedStop != null && it.smartStop > it.fixedStop }
            val stops = notes.count { it.smartStop != null && it.fixedStop != null }
            val hero = notes.any { it.arm == HeroRules.ARM.source }
            return listOf("Charges check (shadow, R17 — failed its holdout test on ${ChargesCheck.HOLDOUT_TRADES} trades, nothing skipped): " +
                "${notes.size} paper ${if (notes.size == 1) "entry" else "entries"}; " +
                "$share; $skip would have been skipped at ${(ChargesCheck.SKIP_AT * 100).roundToInt()}%." +
                (if (stops > 0) " Smart stop (k ${ChargesCheck.f1(SmartStop.K)}; failed its holdout test on ${SmartStop.HOLDOUT_TRADES} trades): " +
                    "wider than the fixed stop on $wider of $stops." else "") +
                (if (hero) " Hero's R17 sample is tiny ($HERO_DESIGN_TRADES design, $HERO_HOLDOUT_TRADES holdout trades): too few to judge." else ""))
        }
    }
}

/**
 * I4 (advice): today's day type against the days the simulated arms (R17's backtest of the paper rules) lost most.
 * Walk-forward in R17 it FAILED: on 122 holdout days the "careful" days lost less than the normal ones. So it is said
 * with its sample sizes and its result, and acts only through [lots] when Boss switches that on (paper arms, halving only).
 */
object DangerMeter {
    enum class Level(val word: String) { NORMAL("normal"), CAREFUL("careful"), PAPER_ONLY("paper-only") }

    /** One feature bucket: the sessions in it, their mean summed arm net (Rs a day), and its shrunk deviation. */
    data class Bucket(val feature: String, val bucket: String, val n: Int, val mean: Double, val dev: Double)

    /** Fitted on 347 sessions with both indices, 26 Sep 2024 - 23 Feb 2026 (results/app_constants.json). */
    const val SESSIONS = 347
    const val HOLDOUT_DAYS = 122
    const val OVERALL = -895.2
    const val CAREFUL_AT = -372.5
    const val PAPER_ONLY_AT = -753.9
    /** |gap| % terciles and VIX terciles of the last 120 sessions of the data. */
    val GAP_CUTS = 0.11 to 0.301
    val VIX_CUTS = 10.68 to 11.98
    /** VIX change beyond +-5% is "up" / "down". */
    const val VIX_MOVE = 0.05

    val MODEL: List<Bucket> = listOf(
        Bucket("gap", "large", 110, -709.5, 157.1), Bucket("gap", "mid", 111, -610.8, 241.0), Bucket("gap", "small", 126, -1307.8, -356.1),
        Bucket("vix", "high", 119, -691.4, 174.4), Bucket("vix", "low", 156, -982.2, -77.1), Bucket("vix", "mid", 72, -1043.4, -116.0),
        Bucket("vix_chg", "down", 25, -1157.1, -145.5), Bucket("vix_chg", "flat", 279, -847.1, 44.9), Bucket("vix_chg", "up", 43, -1055.0, -109.1),
        Bucket("event", "no", 338, -836.6, 55.3), Bucket("event", "yes", 9, -3095.7, -682.9),
        Bucket("expiry", "no", 265, -760.9, 124.8), Bucket("expiry", "yes", 82, -1329.0, -348.8),
        Bucket("dow", "Fri", 69, -1243.6, -270.1), Bucket("dow", "Mon", 71, -131.9, 595.5), Bucket("dow", "Sat", 1, -4084.7, -151.9),
        Bucket("dow", "Thu", 69, -988.2, -72.1), Bucket("dow", "Tue", 71, -1251.5, -278.0), Bucket("dow", "Wed", 66, -823.2, 55.3),
    )

    /** What is known in the morning. [gapPct]: |open - previous close| in % (null before the open); [vixChange]: a fraction. */
    data class Inputs(val gapPct: Double?, val vixPrev: Double?, val vixChange: Double?, val event: Boolean, val expiry: Boolean, val day: DayOfWeek)

    data class Reading(val level: Level, val score: Double, val used: List<Bucket>, val unknown: List<String>) {
        /** The bucket that pulls the score down most (its sample size is said with it), or null. */
        val worst: Bucket? get() = used.filter { it.dev < 0 }.minByOrNull { it.dev }
        /** Read before BANKNIFTY's open is in: the gap, the meter's strongest sign, is not known yet. */
        val provisional: Boolean get() = GAP in unknown

        fun line(): String {
            val why = worst?.let { " Worst sign: ${describe(it)} (the simulated arms made %s a day on those %d sessions).".format(Locale.ENGLISH, rs(it.mean), it.n) } ?: ""
            val rest = unknown.filter { it != GAP }
            val miss = if (rest.isEmpty()) "" else " Not known yet: ${rest.joinToString()} (counted as neutral)."
            val first = if (provisional) "provisional, gap not known yet, read again after the open; " else ""
            return "Today: ${level.word} day (${first}danger meter, $SESSIONS sessions).$why$miss Advice only: this meter failed its " +
                "holdout test ($HOLDOUT_DAYS days), so it changes nothing unless you switch on half size for paper arms."
        }
    }

    /** The name the gap goes by in [Reading.unknown]. */
    const val GAP = "the gap"

    fun buckets(i: Inputs): Pair<Map<String, String>, List<String>> {
        val b = LinkedHashMap<String, String>(); val unknown = ArrayList<String>()
        i.gapPct?.takeIf { it.isFinite() }?.let { g -> val a = abs(g); b["gap"] = if (a <= GAP_CUTS.first) "small" else if (a <= GAP_CUTS.second) "mid" else "large" } ?: unknown.add(GAP)
        i.vixPrev?.takeIf { it > 0 && it.isFinite() }?.let { v -> b["vix"] = if (v <= VIX_CUTS.first) "low" else if (v <= VIX_CUTS.second) "mid" else "high" } ?: unknown.add("VIX")
        i.vixChange?.takeIf { it.isFinite() }?.let { c -> b["vix_chg"] = if (c > VIX_MOVE) "up" else if (c < -VIX_MOVE) "down" else "flat" } ?: unknown.add("VIX change")
        b["event"] = if (i.event) "yes" else "no"
        b["expiry"] = if (i.expiry) "yes" else "no"
        b["dow"] = i.day.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        return b to unknown
    }

    fun read(i: Inputs): Reading {
        val (b, unknown) = buckets(i)
        val used = b.mapNotNull { (f, v) -> MODEL.firstOrNull { it.feature == f && it.bucket == v } }
        val score = used.sumOf { it.dev }
        val level = when { score <= PAPER_ONLY_AT -> Level.PAPER_ONLY; score <= CAREFUL_AT -> Level.CAREFUL; else -> Level.NORMAL }
        return Reading(level, score, used, unknown)
    }

    /**
     * A PAPER arm's lots under the switch: half (rounded down, at least 1) on a careful or paper-only day when [halfOn];
     * otherwise [planned] unchanged. Never more than [planned]; a live order never comes here.
     */
    fun lots(planned: Int, level: Level?, halfOn: Boolean): Int =
        if (!halfOn || level == null || level == Level.NORMAL || planned <= 1) planned else max(1, planned / 2)

    /** One index's last 1-minute snapshot as the meter reads it: that bar's minute, its price, the previous close, today's open. */
    data class Quote(val at: LocalDateTime, val price: Double, val prevClose: Double?, val open: Double)

    /** The trading session before [today] by [isTradingDay] (null: none in the 30 days before). */
    fun previousSession(today: LocalDate, isTradingDay: (LocalDate) -> Boolean): LocalDate? =
        generateSequence(today.minusDays(1)) { it.minusDays(1) }.take(30).firstOrNull(isTradingDay)

    /** India VIX's previous close and its last session's change (a fraction); null where not known. */
    data class Vix(val prev: Double?, val change: Double?)

    /**
     * VIX from its snapshot [q], only when the snapshot is fresh (review, 13 Oct): its last bar today (then its previous
     * close is yesterday's close; today's change is not the meter's) or on [prevSession] (then its price is that session's
     * close and its change that session's). A snapshot of any older day - a phone that did not read for days, a holiday
     * gap - is not used: VIX is unknown.
     */
    fun vix(q: Quote?, today: LocalDate, prevSession: LocalDate?): Vix {
        if (q == null) return Vix(null, null)
        val pc = q.prevClose?.takeIf { it > 0 && it.isFinite() }
        return when (q.at.toLocalDate()) {
            today -> Vix(pc, null)
            prevSession -> {
                val p = q.price.takeIf { it > 0 && it.isFinite() }
                Vix(p, if (p != null && pc != null) (p - pc) / pc else null)
            }
            else -> Vix(null, null)
        }
    }

    /** BANKNIFTY's |gap| input in %: today's open against the previous close, once today's first bar is in; else null. */
    fun gap(q: Quote?, today: LocalDate): Double? {
        if (q == null || q.at.toLocalDate() != today || !(q.open > 0) || !q.open.isFinite()) return null
        val pc = q.prevClose?.takeIf { it > 0 && it.isFinite() } ?: return null
        return (q.open - pc) / pc * 100
    }

    /**
     * The meter's [Inputs] for [today] from the snapshots in hand. [earlier]: today's inputs from an earlier read (the
     * 09:00 check); what this read cannot see any more (VIX's change once VIX trades today) is kept from it.
     */
    fun inputs(today: LocalDate, prevSession: LocalDate?, bankNifty: Quote?, vixQuote: Quote?, event: Boolean, expiry: Boolean,
               earlier: Inputs? = null): Inputs {
        val v = vix(vixQuote, today, prevSession)
        return Inputs(gap(bankNifty, today) ?: earlier?.gapPct, v.prev ?: earlier?.vixPrev, v.change ?: earlier?.vixChange, event, expiry, today.dayOfWeek)
    }

    private val DAYS = mapOf("Mon" to "Mondays", "Tue" to "Tuesdays", "Wed" to "Wednesdays", "Thu" to "Thursdays", "Fri" to "Fridays", "Sat" to "Saturdays")

    internal fun describe(b: Bucket): String = when (b.feature) {
        "gap" -> "a ${b.bucket} gap"
        "vix" -> "${b.bucket} VIX"
        "vix_chg" -> "VIX ${b.bucket} over 5%"
        "event" -> "an event day"
        "expiry" -> "an expiry day"
        else -> DAYS[b.bucket] ?: b.bucket
    }

    private fun rs(x: Double) = (if (x < 0) "-₹" else "+₹") + "%,.0f".format(Locale.ENGLISH, abs(x))
}
