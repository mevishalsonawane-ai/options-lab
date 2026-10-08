package com.optionslab.engine.mcx

import com.optionslab.engine.Upstox
import com.optionslab.engine.sandbox.PaperSpread
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.sign

/**
 * The three MCX research candidates (9 Oct 2026), as PAPER-ONLY arms. None of them passed its research gates: each is
 * "not proven - paper only", off by default, fixed at 1 lot with no compounding, and never cleared for Zerodha. The rules
 * are the research's own, as tested:
 *
 *  - [McxEveRules]: NATURALGAS evening breakout (research/MCX_INTRADAY.md, M3 rule EVE; research/hunt/m3/run.py + m3lib.py);
 *  - [McxMorningRules]: SILVERM morning OTM call (research/MCX_OPTIONS.md, M4's frozen Q4 rule; research/hunt/m4/analyze.py);
 *  - [McxTrendRules]: the 12-month trend rule on the MCX minis (research/MCX_TREND.md, M2's TSM252; research/hunt/m2/engine.py).
 *
 * This object holds what they share: the "not proven" label, strike picking on a listed chain, trading days to expiry on
 * MCX's calendar, the freshness of a price, and the order of the refusals every new entry passes. Pure: no clock, no
 * network, no orders.
 */
object McxArmRules {
    /** The label every one of them carries on screen, above its research numbers. */
    const val NOT_PROVEN = "Not proven — paper only"

    /** Fixed size: 1 lot, never more, no compounding. */
    const val LOTS = 1

    /** The listed strikes, distinct and ascending. */
    fun strikes(listed: Collection<Double>): List<Double> = listed.filter { it.isFinite() && it > 0 }.distinct().sorted()

    /** The index of the at-the-money strike: the listed strike nearest [price] (the lower one on a tie); null with none. */
    fun atmIndex(strikes: List<Double>, price: Double): Int? {
        if (strikes.isEmpty() || !price.isFinite()) return null
        var best = 0
        for (i in strikes.indices) if (abs(strikes[i] - price) < abs(strikes[best] - price)) best = i
        return best
    }

    /** The strike [steps] listed strikes above the ATM one (negative: below); null off the end of the chain. */
    fun stepFromAtm(listed: Collection<Double>, price: Double, steps: Int): Double? {
        val k = strikes(listed)
        val i = atmIndex(k, price) ?: return null
        return k.getOrNull(i + steps)
    }

    /** True while the last 1-minute bar (started at [barStartSec]) is fresh at [nowSec] (the paper account's own rule). */
    fun fresh(barStartSec: Long, nowSec: Long): Boolean = !PaperSpread.isStale(barStartSec, nowSec)

    /** MCX trading days left after [today] up to and including [expiry] (0 on expiry day): the research's "days to expiry". */
    fun tradingDaysLeft(today: LocalDate, expiry: LocalDate, cal: McxSession.Calendar): Int {
        var n = 0
        var d = today.plusDays(1)
        var guard = 0
        while (!d.isAfter(expiry) && guard < 400) { if (cal.isTradingDay(d)) n++; d = d.plusDays(1); guard++ }
        return n
    }

    /**
     * Why a new entry may not go now, in the order they are checked (null: it may): MCX shut, the kill switch, the bot
     * stopped for today (the daily loss limit or Boss's stop), the account's day lock, then MCX's expiry rule for the
     * contract ([McxExpiry.entryRefusal]'s text) and a stale price.
     */
    fun entryRefusal(sessionOpen: Boolean, kill: Boolean, stoppedToday: Boolean, dayLock: String?, expiry: String?, freshPrice: Boolean): String? = when {
        !sessionOpen -> "mcx_shut"
        kill -> "kill_switch"
        stoppedToday -> "stopped_for_today"
        dayLock != null -> "day_lock: $dayLock"
        expiry != null -> "expiry_rule: $expiry"
        !freshPrice -> "stale_price"
        else -> null
    }

    /** "+Rs 41", "-Rs 1,439". */
    fun rs(x: Int): String = (if (x < 0) "-Rs " else "+Rs ") + "%,d".format(java.util.Locale.ENGLISH, abs(x))
}

/**
 * NATURALGAS evening breakout (M3's EVE, research/MCX_INTRADAY.md; research/hunt/m3/run.py amendment 1 and
 * m3lib.range_break_signals / sim_option), exactly as tested:
 *
 *  - the range: the NATURALGAS future's 1-minute closes from 17:00 to before 19:00 IST (its high and low close), at least
 *    60 of them ([MIN_RANGE_MINUTES]) and a positive width;
 *  - the signal: the first 1-minute close beyond the range from 19:00 to 22:00 (above: the call, below: the put), once a day;
 *  - the entry: the next minute's open, the 1-ITM near-month option (a call one listed strike below the ATM, a put one
 *    above), 1 lot; the app buys within [ENTRY_GRACE] minutes of that minute, else lets the day go;
 *  - the exits, the first one reached: the option's minute LOW at -15% of the fill (the wick; on a tie it goes first), the
 *    future's close back at the range's middle (the stop), the future's close at the edge plus one width (the target),
 *    120 minutes held, and flat by 23:15 at the latest (15 minutes before an earlier close).
 *
 * Research (1 lot): design +Rs 47 a trade, +Rs 41 a day; holdout +Rs 93 a trade, +Rs 83 a day; not significant (BH q
 * 0.53-0.63); drawdown about Rs 40,000.
 */
object McxEveRules {
    const val SOURCE = "mcx_ng_eve"
    const val LABEL = "NG evening breakout"
    const val UNDERLYING = "NATURALGAS"

    const val RANGE_FROM = 17 * 60
    const val RANGE_TO = 19 * 60
    const val ENTRY_UNTIL = 22 * 60
    const val FLAT_BY = 23 * 60 + 15
    const val MAX_HOLD_MIN = 120
    const val OPTION_STOP = 0.15
    /** m3lib: len(range) >= max(5, (19:00 - 17:00) / 2). */
    const val MIN_RANGE_MINUTES = 60
    /** The entry minute and this many more (the research takes the first printed minute in [e, e + 2]). */
    const val ENTRY_GRACE = 3
    /** An earlier session end: flat this long before it. */
    const val BEFORE_CLOSE_MIN = 15

    const val DESIGN_PER_DAY = 41
    const val HOLDOUT_PER_DAY = 83
    const val DESIGN_PER_TRADE = 47
    const val HOLDOUT_PER_TRADE = 93
    const val DRAWDOWN = 40_000
    const val RECORD = "Research: design +Rs 41/day, holdout +Rs 83/day · not statistically significant · drawdown about Rs 40,000"
    const val RULES = "NATURALGAS: 17:00-19:00 range of the future · first break to 22:00 buys the 1-ITM call (up) or put (down), 1 lot · " +
        "exits: option -15% on its low, back to the range middle, edge + 1 width, 2 hours, flat by 23:15"

    data class Range(val high: Double, val low: Double) {
        val width: Double get() = high - low
        val mid: Double get() = (high + low) / 2
    }

    /** The day's range from the future's minutes of today ([bars], any order); null with too few minutes or no width. */
    fun range(bars: List<Upstox.Bar>): Range? {
        val r = bars.filter { it.istMinute in RANGE_FROM until RANGE_TO }.map { it.close }.filter { it.isFinite() }
        if (r.size < MIN_RANGE_MINUTES) return null
        val hi = r.max(); val lo = r.min()
        return if (hi - lo > 0) Range(hi, lo) else null
    }

    /** The break: [side] +1 up / -1 down, at the close of [minute]; [stop] the range middle, [target] the edge plus a width. */
    data class Signal(val side: Int, val minute: Int, val close: Double, val edge: Double, val stop: Double, val target: Double) {
        /** The minute the research buys at: the next one's open. */
        val entryMinute: Int get() = minute + 1
    }

    /** The first close beyond [range] from 19:00 to 22:00 in [bars] (today's future minutes); null: no break yet. */
    fun signal(range: Range, bars: List<Upstox.Bar>): Signal? {
        for (b in bars.filter { it.istMinute in RANGE_TO..ENTRY_UNTIL }.sortedBy { it.epochSecond }) {
            val side = when { b.close > range.high -> 1; b.close < range.low -> -1; else -> 0 }
            if (side == 0) continue
            val edge = if (side > 0) range.high else range.low
            return Signal(side, b.istMinute, b.close, edge, range.mid, edge + side * range.width)
        }
        return null
    }

    /** Whether [nowMinute] may still buy on [s]: from its entry minute for [ENTRY_GRACE] minutes, and never after [flatBy]. */
    fun entryWindow(s: Signal, nowMinute: Int, flatBy: Int = FLAT_BY): Boolean =
        nowMinute >= s.entryMinute && nowMinute < s.entryMinute + ENTRY_GRACE && nowMinute < flatBy

    /** The 1-ITM strike: the call one listed strike below the ATM, the put one above. */
    fun strike(listed: Collection<Double>, future: Double, side: Int): Double? = McxArmRules.stepFromAtm(listed, future, -side)

    /** Flat by 23:15, or [BEFORE_CLOSE_MIN] before an earlier session end. */
    fun flatBy(window: McxSession.Window?): Int {
        val close = window?.close?.let { it.hour * 60 + it.minute } ?: return FLAT_BY
        return minOf(FLAT_BY, close - BEFORE_CLOSE_MIN)
    }

    /** The option's stop: 15% under its fill. */
    fun optionStop(fill: Double): Double = fill * (1 - OPTION_STOP)

    /** What is held: the side, the option's fill, the minute bought, the future's stop and target, the flat time. */
    data class Held(val side: Int, val fill: Double, val entryMinute: Int, val stop: Double, val target: Double, val flatBy: Int = FLAT_BY)

    /**
     * The exit due at [minute], or null to hold, from that minute's option LOW ([optionLow], the wick) and the future's
     * close ([future]); either may be unknown (null). In the research's order: "option_stop", "range_mid_stop", "target",
     * "two_hours", "flat_2315".
     */
    fun exit(h: Held, optionLow: Double?, future: Double?, minute: Int): String? = when {
        optionLow != null && optionLow <= optionStop(h.fill) -> "option_stop"
        future != null && h.side * (future - h.stop) <= 0 -> "range_mid_stop"
        future != null && h.side * (future - h.target) >= 0 -> "target"
        minute - h.entryMinute >= MAX_HOLD_MIN -> "two_hours"
        minute >= h.flatBy -> "flat_2315"
        else -> null
    }
}

/**
 * SILVERM morning OTM call (M4's frozen Q4 rule, research/MCX_OPTIONS.md; research/hunt/m4/analyze.py run_rule /
 * trades_for), exactly as tested: "SILVERM OTM2-3 call, 11-20 days to expiry, morning, 4-hour exit (E1), 1 lot".
 *
 *  - entries on the 15-minute grid of the morning, 09:15 to 13:45 (the research's morning bucket ends 13:59), the first
 *    grid time of the day and again at the first grid time at or after the previous trade's 4-hour exit (one position at a
 *    time: trades_for); the app buys within [ENTRY_GRACE] minutes of a grid time;
 *  - the near-month SILVERM call two listed strikes above the ATM (OTM2; OTM3 when OTM2 has no price), only while 11 to 20
 *    MCX trading days are left to its expiry, and only when 1 lot costs Rs 500 to Rs 1 lakh;
 *  - the exit: 4 hours after the entry (no stop, no target: rule E1), or 10 minutes before an earlier session end.
 *
 * Research (1 lot): holdout +Rs 454 a trade over 51 trades (+Rs 23,178, max drawdown Rs 13,785); it failed 2 of its 4
 * gates (random p 0.050, walk-forward lost Rs 3,500). Design: +Rs 1,439 a trade over 78 trades.
 */
object McxMorningRules {
    const val SOURCE = "mcx_silverm_am"
    const val LABEL = "SILVERM morning call"
    const val UNDERLYING = "SILVERM"

    val OTM_STEPS: List<Int> = listOf(2, 3)
    const val DTE_MIN = 11
    const val DTE_MAX = 20
    const val HOLD_MIN = 240
    const val GRID_FROM = 9 * 60 + 15
    const val GRID_LAST = 13 * 60 + 45
    const val GRID_STEP = 15
    const val ENTRY_GRACE = 3
    const val MIN_LOT_PREMIUM = 500.0
    const val MAX_LOT_PREMIUM = 100_000.0
    const val BEFORE_CLOSE_MIN = 10

    const val HOLDOUT_PER_TRADE = 454
    const val HOLDOUT_TRADES = 51
    const val GATES_FAILED = 2
    const val GATES = 4
    const val RECORD = "Research: holdout +Rs 454/trade over 51 trades · failed 2 of 4 gates"
    const val RULES = "SILVERM near-month call 2 strikes out of the money, 11-20 days to expiry · buys at the morning's first 15-minute " +
        "time (09:15, again after an exit to 13:45), 1 lot · sells 4 hours later"

    /** The grid time [nowMinute] may buy at (its own minute and [ENTRY_GRACE] - 1 more), or null. */
    fun gridSlot(nowMinute: Int): Int? {
        if (nowMinute < GRID_FROM) return null
        val slot = GRID_FROM + (nowMinute - GRID_FROM) / GRID_STEP * GRID_STEP
        return slot.takeIf { it <= GRID_LAST && nowMinute - slot < ENTRY_GRACE }
    }

    /** One position at a time: a grid time at or after the previous trade's exit ([lastEntryMinute] + 4 h) today. */
    fun mayEnter(slot: Int, lastEntryMinute: Int?): Boolean = lastEntryMinute == null || slot >= lastEntryMinute + HOLD_MIN

    /** 11 to 20 MCX trading days left to [expiry] after [today]. */
    fun dteOk(today: LocalDate, expiry: LocalDate, cal: McxSession.Calendar): Boolean =
        McxArmRules.tradingDaysLeft(today, expiry, cal) in DTE_MIN..DTE_MAX

    /** The OTM call strike [steps] listed strikes above the ATM. */
    fun strike(listed: Collection<Double>, future: Double, steps: Int): Double? = McxArmRules.stepFromAtm(listed, future, steps)

    /** 1 lot's premium in Rs 500 .. Rs 1 lakh (the research's lot rule and its amendment 4). */
    fun premiumOk(price: Double, multiplier: Int): Boolean = (price * multiplier).let { it.isFinite() && it >= MIN_LOT_PREMIUM && it <= MAX_LOT_PREMIUM }

    /** The minute it is sold: 4 hours after [entryMinute], or [BEFORE_CLOSE_MIN] before an earlier session end. */
    fun exitMinute(entryMinute: Int, window: McxSession.Window?): Int {
        val close = window?.close?.let { it.hour * 60 + it.minute } ?: return entryMinute + HOLD_MIN
        return minOf(entryMinute + HOLD_MIN, close - BEFORE_CLOSE_MIN)
    }

    /** The exit due at [nowMinute]: "four_hours", or null to hold. */
    fun exit(entryMinute: Int, nowMinute: Int, window: McxSession.Window?): String? =
        if (nowMinute >= exitMinute(entryMinute, window)) "four_hours" else null
}

/**
 * The 12-month trend rule on the MCX minis (M2's TSM252, research/MCX_TREND.md; research/hunt/m2/engine.py signal()), as
 * tested: on the last trading day of each month, each leg's sign of its 252-trading-day change in price (+1 long, -1 short,
 * 0 with too little history), traded at the next session's open and held for the month; long AND short (a short future is
 * risk without a floor, which is why it is off by default). 1 lot of each mini future, the near month rolled to the next
 * [McxExpiry.Config.futureExitTradingDays] trading days before expiry (the research: "about 5 days").
 *
 * The research's warning comes first: it needs Rs 8-10 lakh; at Rs 1 lakh it was often ruined (all 7 legs need about
 * Rs 1.7-1.9 lakh of margin; 25% of 2-year starts lost money). Development 2013-2024: about Rs 3,850 a month (Sharpe 0.54)
 * with drawdowns of Rs 1.8 lakh; Reality Check p 0.13, SPA p 0.79: not proven. The app reads the price history from
 * Zerodha's continuous near-month daily series, which is not back-adjusted for rolls (the research's was).
 */
object McxTrendRules {
    const val SOURCE = "mcx_trend12"
    const val LABEL = "MCX 12-month trend"

    /** The research's PORT legs (GOLDPETAL at 1 lot: the app's fixed 1-lot rule; the research held 5). */
    val LEGS: List<String> = listOf("CRUDEOILM", "NATGASMINI", "SILVERMIC", "ZINCMINI", "LEADMINI", "ALUMINI", "GOLDPETAL")
    const val LOOKBACK = 252

    const val CAPITAL_WARNING = "needs ₹8–10 lakh; at ₹1 lakh often ruined"
    const val RECORD = "Research: 2013-24 about +Rs 3,850/month with Rs 1.8 lakh drawdowns · not proven (SPA p 0.79) · needs ₹8–10 lakh; at ₹1 lakh often ruined"
    const val RULES = "1 lot each of CRUDEOILM, NATGASMINI, SILVERMIC, ZINCMINI, LEADMINI, ALUMINI, GOLDPETAL · long if up over 12 months, " +
        "short if down · decided at each month end, traded at the next open · rolled before expiry"

    /**
     * The signal on [asOf]: the sign of the last close on or before [asOf] against the close [LOOKBACK] trading days before
     * it ([closes]: (day, close), any order). 0 with fewer than [LOOKBACK] + 1 closes or an unpriced one.
     */
    fun signal(closes: List<Pair<LocalDate, Double>>, asOf: LocalDate): Int {
        val c = closes.filter { !it.first.isAfter(asOf) && it.second.isFinite() }.sortedBy { it.first }.distinctBy { it.first }
        if (c.size < LOOKBACK + 1) return 0
        return sign(c.last().second - c[c.size - 1 - LOOKBACK].second).toInt()
    }

    /** The month-end the signal for [today]'s month is read on: the last MCX trading day of the previous month. */
    fun signalDay(today: LocalDate, cal: McxSession.Calendar): LocalDate =
        cal.tradingDaysBefore(YearMonth.from(today).atDay(1), 1)

    /** The month's key ("2026-10"). */
    fun month(today: LocalDate): String = YearMonth.from(today).toString()

    /** The day a leg expiring on [expiry] is rolled (closed, and the next month bought): its exit day. */
    fun rollDay(expiry: LocalDate, cal: McxSession.Calendar, cfg: McxExpiry.Config = McxExpiry.Config()): LocalDate =
        cal.tradingDaysBefore(expiry, cfg.futureExitTradingDays.coerceAtLeast(1))

    /** Whether a leg expiring on [expiry] is due to roll on [today]. */
    fun rollDue(expiry: LocalDate, today: LocalDate, cal: McxSession.Calendar, cfg: McxExpiry.Config = McxExpiry.Config()): Boolean =
        !today.isBefore(rollDay(expiry, cal, cfg))

    /** The future to hold on [today]: the nearest listed expiry not yet due to roll; null when none is. */
    fun expiryToHold(expiries: Collection<LocalDate>, today: LocalDate, cal: McxSession.Calendar, cfg: McxExpiry.Config = McxExpiry.Config()): LocalDate? =
        expiries.filter { !rollDue(it, today, cal, cfg) }.minOrNull()

    /** One leg's step now: close what is held, then open [open] (+1 buy, -1 sell, 0 nothing new). */
    data class Step(val close: Boolean, val open: Int, val why: String)

    /**
     * What one leg does: [held] its side now (+1, -1, 0) on a future expiring [heldExpiry]; [signal] this month's; [hold]
     * the expiry it should be on now ([expiryToHold]). A flip or a 0 closes; a due roll closes and reopens on [hold].
     */
    fun step(held: Int, heldExpiry: LocalDate?, signal: Int, hold: LocalDate?): Step = when {
        held != 0 && signal != held -> Step(true, if (hold != null) signal else 0, if (signal == 0) "trend_flat" else "trend_flip")
        held != 0 && heldExpiry != hold -> Step(true, if (hold != null) held else 0, "trend_roll")
        held != 0 -> Step(false, 0, "trend_hold")
        signal != 0 && hold != null -> Step(false, signal, if (signal > 0) "trend_long" else "trend_short")
        signal != 0 -> Step(false, 0, "trend_no_contract")
        else -> Step(false, 0, "trend_no_signal")
    }
}
