package com.optionslab.engine.mcx

import java.time.LocalDate

/**
 * NR7 breakout on crude, RECORD ONLY (21 Oct 2026, Boss: "Implement nr7, current exit"; research R96 R3A,
 * research/hunt/r96_mcx_buy/results/NR7_CRUDE_DETAIL.md). It has NOT passed its research gates (BH q 0.135 > 0.10 over 29
 * ideas; the locked holdout unread), so under the PAPER rule it stays a note: the app decides it exactly as tested and books
 * "would-be" trades at the prices it sees, never an order (paper or live). Signal on the underlying (the crude future), the
 * trade an option BUY (a call on an up break, a put on a down break; never a sale).
 *
 *  - NR7 day: a session whose range (high - low of the future's minutes) is narrower than each of the previous 6 sessions'.
 *  - Levels: that day's highest and lowest 1-minute CLOSE of the future (the research's minute closes).
 *  - Signal: the next session only, the first 1-minute close beyond a level between 09:15 and 22:00.
 *  - Entry: the next minute's open of the 1-ITM near-month option (a call one listed strike below the ATM, a put one above),
 *    1 lot; none on expiry day or with fewer than 3 sessions before it ([MIN_SESSIONS_TO_EXPIRY]).
 *  - Exits, the first reached each minute, in this order: the option's minute LOW at the stop level (-40% of the fill, or the
 *    profit lock once higher; out at the level, or at the open when the minute opened below it); the future's close back
 *    beyond the NR7 day's opposite extreme (out at the option's close); the "edge" take-profit, the held strike three listed
 *    strikes in the money (out at the option's close; the research's data stored ATM+/-3 only); then the lock ladder update
 *    on the minute's high (+30% peak -> breakeven, +60% -> +30%, +100% -> +60%, beyond +100% the peak - 40%). Time: out at
 *    the square-off (close - 15 min) of the 3rd session (entry day = 1), or of the session before the option expiry.
 *
 * Pure: no clock, no network, no storage.
 */
object McxNr7Rules {
    const val SOURCE = "mcx_nr7_crude"
    const val LABEL = "NR7 crude (record only)"

    /** Both crude option books, as tested (the same signal days; CRUDEOILM is a tenth of the size). */
    val UNDERLYINGS = listOf("CRUDEOIL", "CRUDEOILM")

    const val LOOKBACK = 7
    const val ENTRY_FROM = 9 * 60 + 15
    const val ENTRY_UNTIL = 22 * 60
    const val FILL_WAIT = 15
    const val HOLD_SESSIONS = 3
    const val MIN_SESSIONS_TO_EXPIRY = 3
    const val SQ_BEFORE_CLOSE = 15
    const val PREM_STOP = 0.40
    /** The held strike this many listed strikes in the money: the edge take-profit (research ATM+/-3). */
    const val EDGE_STEPS = 3

    /** (peak gain, locked gain); NaN = breakeven (the fill plus a round trip of costs). */
    val LADDER = listOf(0.30 to Double.NaN, 0.60 to 0.30, 1.00 to 0.60)
    const val TRAIL_FROM = 1.00
    const val TRAIL_GAP = 0.40

    /** Half-spreads per fill (research costs): before 17:00 and from 17:00. */
    const val HALF_DAY = 0.0060
    const val HALF_EVENING = 0.0030

    const val RECORD = "Research R96 R3A (design 5 Aug 2025 - 14 Jul 2026): 46 trades, +Rs 48,875 after costs, 76% won, " +
        "4 of 4 quarters positive, worst month -Rs 9,718; beat random days (p 0.014) but NOT the multiple-testing gate " +
        "(q 0.135 over 29 ideas); the holdout is unread. One winner (+Rs 16,532) was model-priced."
    const val RULES = "CRUDEOIL and CRUDEOILM: an NR7 day (narrowest range of 7) -> the next session's first future minute " +
        "close beyond its high / low close (09:15-22:00) -> buy the 1-ITM call / put, 1 lot · stop -40% premium or the NR7 " +
        "day's other extreme on the future · lock +30% -> breakeven, +60% -> +30%, +100% -> +60% then trail 40% · take " +
        "profit when the strike is 3 strikes in the money · out after 3 sessions. RECORD ONLY: no order is ever placed."

    /** One session of the future: its range (high - low of its minutes) and its highest / lowest minute close. */
    data class Day(val date: LocalDate, val high: Double, val low: Double, val highClose: Double, val lowClose: Double) {
        val range: Double get() = high - low
    }

    /** One finished minute: [minute] of the day (IST), o/h/l/c. */
    data class Minute(val minute: Int, val open: Double, val high: Double, val low: Double, val close: Double)

    /** A session from its minutes (null with none, or a bad price). */
    fun day(date: LocalDate, bars: List<Minute>): Day? {
        val ok = bars.filter { it.high.isFinite() && it.low.isFinite() && it.close.isFinite() && it.low > 0 && it.high >= it.low }
        if (ok.isEmpty()) return null
        return Day(date, ok.maxOf { it.high }, ok.minOf { it.low }, ok.maxOf { it.close }, ok.minOf { it.close })
    }

    /** Is the last of [days] (oldest first, consecutive sessions) an NR7 day? Needs [LOOKBACK] sessions. */
    fun isNr7(days: List<Day>): Boolean {
        if (days.size < LOOKBACK) return false
        val last = days.last()
        val prev = days.subList(days.size - LOOKBACK, days.size - 1)
        return last.range > 0 && prev.all { last.range < it.range }
    }

    data class Signal(val side: Int, val minute: Int, val close: Double)

    /** The first minute of [today] (oldest first) closing beyond [nr7]'s high / low close within the entry window; null if none yet. */
    fun signal(nr7: Day, today: List<Minute>): Signal? {
        for (b in today) {
            if (b.minute < ENTRY_FROM || b.minute > ENTRY_UNTIL || !b.close.isFinite()) continue
            if (b.close > nr7.highClose) return Signal(1, b.minute, b.close)
            if (b.close < nr7.lowClose) return Signal(-1, b.minute, b.close)
        }
        return null
    }

    /** The NR7 day's opposite extreme: the stop on the future. */
    fun futureStop(nr7: Day, side: Int): Double = if (side > 0) nr7.lowClose else nr7.highClose

    /** The 1-ITM strike: a call one listed strike below the ATM, a put one above. */
    fun strike(listed: Collection<Double>, futurePrice: Double, side: Int): Double? = McxArmRules.stepFromAtm(listed, futurePrice, -side)

    /** May a would-be entry go on a session with [sessionsToExpiry] full sessions before the option expiry day (0 on its eve)? */
    fun expiryOk(isExpiryDay: Boolean, sessionsToExpiry: Int): Boolean = !isExpiryDay && sessionsToExpiry >= MIN_SESSIONS_TO_EXPIRY

    /** Is [held] at or beyond the edge: [EDGE_STEPS] listed strikes in the money from the ATM at [futurePrice]? */
    fun atEdge(listed: Collection<Double>, futurePrice: Double, held: Double, side: Int): Boolean {
        val k = McxArmRules.strikes(listed)
        val atm = McxArmRules.atmIndex(k, futurePrice) ?: return false
        val edge = k.getOrNull(atm - side * EDGE_STEPS) ?: return (if (side > 0) held <= k.first() else held >= k.last())
        return if (side > 0) held <= edge + 1e-9 else held >= edge - 1e-9
    }

    /** The half-spread at a fill at [minute] (IST). */
    fun half(minute: Int): Double = if (minute >= 17 * 60) HALF_EVENING else HALF_DAY

    /** A held would-be position's walk state: the fill, breakeven, the lock (NaN: none yet) and the peak. */
    data class Held(val side: Int, val strike: Double, val fill: Double, val breakeven: Double, val futureStop: Double,
                    val lock: Double = Double.NaN, val peak: Double = fill)

    /** The exit: [why] and the price ([price]). */
    data class Exit(val why: String, val price: Double)

    /** The lock after a peak of [peak] (never lower than [lock]). */
    fun lockAfter(h: Held, peak: Double): Double {
        var lock = h.lock
        val g = peak / h.fill - 1.0
        for ((trig, keep) in LADDER) if (g >= trig - 1e-12) {
            val lv = if (keep.isNaN()) h.breakeven else h.fill * (1 + keep)
            if (lock.isNaN() || lv > lock) lock = lv
        }
        if (g >= TRAIL_FROM - 1e-12) {
            val lv = h.fill * (1 + g - TRAIL_GAP)
            if (lock.isNaN() || lv > lock) lock = lv
        }
        return lock
    }

    /**
     * One finished minute of a held position: the option's minute [opt] (null: no print) and the future's close [future]
     * (NaN: none) with the listed strikes; the exit, or null and the updated state. The research's order: stop / lock on the
     * low, the future's stop, the edge, then the lock update on the high.
     */
    fun step(h: Held, opt: Minute?, future: Double, listed: Collection<Double>): Pair<Exit?, Held> {
        if (opt != null) {
            val stop = h.fill * (1 - PREM_STOP)
            val lvl = if (h.lock.isNaN()) stop else maxOf(stop, h.lock)
            if (opt.low <= lvl) {
                val px = if (opt.open < lvl) opt.open else lvl
                return Exit(if (!h.lock.isNaN() && h.lock > stop) "lock" else "stop", px) to h
            }
        }
        if (future.isFinite() && h.side * (future - h.futureStop) <= 0) return Exit("future_stop", opt?.close ?: Double.NaN) to h
        if (opt != null && future.isFinite() && atEdge(listed, future, h.strike, h.side)) return Exit("edge", opt.close) to h
        if (opt == null) return null to h
        val peak = maxOf(h.peak, opt.high)
        return null to h.copy(peak = peak, lock = lockAfter(h, peak))
    }

    /** The square-off minute (IST) of a session closing at [closeMinute]: the last minute before close - 15 (research sq_ix). */
    fun squareOff(closeMinute: Int): Int = closeMinute - SQ_BEFORE_CLOSE - 1
}
