package com.optionslab.engine.orb

import com.optionslab.engine.Upstox
import kotlin.math.floor

/**
 * "VIX divergence (not proven)": research/HUNT_R1_INTERNET.md idea N13, as research/hunt/r1/signals.py (n13_vixdiv) and
 * lib.py (run_engine) ran it, for a PAPER-ONLY arm (Boss's yes, 9 Oct 2026). It FAILED the research's luck checks (BH q 1.0,
 * reality-check p 0.85-0.96): the one weak lead of 15 new ideas, positive in both periods on both indices. Paper only,
 * 1 lot, never cleared for Zerodha; the research wants about 150 paper trades before anyone trusts it.
 *
 *  - Indices: NIFTY and BANKNIFTY, each on its own (one decision a day per index), the nearest expiry, 1 lot.
 *  - Checks: 10:30, 11:30, 12:30 and 13:30 ([CHECKS]). The check at T reads the minute that closed at T (the bar labelled
 *    T-1: signals.py's col(h, m) - 1), forward-filled, against the index's 09:15 OPEN and India VIX's first minute's close.
 *  - Signal ([side]): index up more than 0.2% AND VIX up more than 2% buys the 1-ITM PUT; index down more than 0.2% AND
 *    VIX down more than 2% buys the 1-ITM CALL (signals.py: `ri > a and rv > b` -> PE, `ri < -a and rv < -b` -> CE). The
 *    FIRST signal of the day only: a later check is never read once one has fired ([firstSignal]).
 *  - Contract: ATM from the decision minute's close (round half to even, as Python's round), the call one step below, the
 *    put one step above ([strike]); bought within [ENTRY_GRACE] minutes of the check (lib.py: the first printed minute of
 *    s+1..s+3), else the day is let go.
 *  - Exits: BANKNIFTY holds to 15:10 (the research's EOD pick). NIFTY takes the Liquidity exit (its LIQ pick): a stop 15%
 *    under the fill on the option's 1-minute LOW (the wick), and out after 20 minutes unless the 20th minute closed 5% up;
 *    else 15:10 ([exit]).
 *  - The research did not skip expiry days (days_ok is every session), so neither does this; the app's 15:05 expiry
 *    square-off may close an expiring option first.
 *
 * Pure: no clock, no network, no orders.
 */
object VixDivRules {
    const val SOURCE = "vix_div"
    const val LABEL = "VIX divergence (not proven)"
    const val NOT_PROVEN = "Not proven — paper only"
    val UNDERLYINGS: List<String> = listOf("NIFTY", "BANKNIFTY")
    const val VIX = "INDIAVIX"
    const val LOTS = 1

    /** The check times (minutes of the day, IST): each reads the minute that closed then. */
    val CHECKS: List<Int> = listOf(10 * 60 + 30, 11 * 60 + 30, 12 * 60 + 30, 13 * 60 + 30)
    const val INDEX_MOVE = 0.002
    const val VIX_MOVE = 0.02
    /** A check may buy in its own minute and the next two (lib.py: the first printed minute of s+1..s+3). */
    const val ENTRY_GRACE = 3
    const val OPEN = 9 * 60 + 15
    /** Flat by 15:10 (lib.py's SQ column). */
    const val EOD = 15 * 60 + 10

    /** NIFTY's Liquidity exit: -15% on the option's low; out after 20 minutes unless 5% up. */
    const val LIQ_STOP = 0.15
    const val LIQ_MINUTES = 20
    const val LIQ_MIN_GAIN = 0.05
    const val TICK = 0.05

    /** Paper trades the research asks for before the rule is trusted with money. */
    const val PAPER_TRADES_WANTED = 150

    const val RECORD = "Research: design +₹67/day NIFTY, +₹89/day BANKNIFTY; holdout +₹70/day (29 trades) NIFTY, +₹112/day (45 trades) " +
        "BANKNIFTY; failed the luck checks (q 1.0) — paper only, needs ~150 paper trades"
    const val RULES = "NIFTY and BANKNIFTY, checked at 10:30, 11:30, 12:30 and 13:30, first signal of the day: index up over 0.2% from its " +
        "open while India VIX is up over 2% from its open buys the 1-ITM put; both down buys the 1-ITM call · nearest expiry, 1 lot · " +
        "BANKNIFTY holds to 15:10; NIFTY: stop −15% on the option's low, out after 20 min unless +5%, else 15:10"

    /** The strike step: NIFTY 50, BANKNIFTY 100. */
    fun step(underlying: String): Int = if (underlying == "BANKNIFTY") 100 else 50

    /** +1 the call, -1 the put, 0 nothing, from the index's and VIX's moves since their opens (fractions). */
    fun side(indexMove: Double, vixMove: Double): Int = when {
        !indexMove.isFinite() || !vixMove.isFinite() -> 0
        indexMove > INDEX_MOVE && vixMove > VIX_MOVE -> -1
        indexMove < -INDEX_MOVE && vixMove < -VIX_MOVE -> 1
        else -> 0
    }

    /** A check's reading: the moves since the opens and the side (0 when it did not fire). */
    data class Reading(val check: Int, val indexOpen: Double, val indexClose: Double, val vixOpen: Double, val vixNow: Double) {
        val indexMove: Double get() = indexClose / indexOpen - 1
        val vixMove: Double get() = vixNow / vixOpen - 1
        val side: Int get() = side(indexMove, vixMove)
        /** The last minute it may still buy in (exclusive). */
        val entryUntil: Int get() = check + ENTRY_GRACE
    }

    /** The index's open: the open of its 09:15 minute in [bars] (today's); null without it. */
    fun indexOpen(bars: List<Upstox.Bar>): Double? = bars.firstOrNull { it.istMinute == OPEN }?.open?.takeIf { it > 0 }

    /** VIX's open: the close of its first minute from 09:15 (signals.py: the first VIX value of the day). */
    fun vixOpen(bars: List<Upstox.Bar>): Double? = bars.filter { it.istMinute >= OPEN }.minByOrNull { it.epochSecond }?.close?.takeIf { it > 0 }

    /** The close of the last minute labelled at or before [minute] (forward-filled); null when none has printed. */
    fun closeAt(bars: List<Upstox.Bar>, minute: Int): Double? =
        bars.filter { it.istMinute in OPEN..minute }.maxByOrNull { it.epochSecond }?.close?.takeIf { it > 0 }

    /** Each check that has closed by [nowMinute], read from today's [index] and [vix] minutes, in order; empty without the opens. */
    fun readings(index: List<Upstox.Bar>, vix: List<Upstox.Bar>, nowMinute: Int): List<Reading> {
        val io = indexOpen(index) ?: return emptyList()
        val vo = vixOpen(vix) ?: return emptyList()
        val out = ArrayList<Reading>()
        for (t in CHECKS) {
            if (t > nowMinute) break
            val ic = closeAt(index, t - 1) ?: continue
            val vc = closeAt(vix, t - 1) ?: continue
            out += Reading(t, io, ic, vo, vc)
        }
        return out
    }

    /** The day's first signal among the checks closed by [nowMinute]; null while none has fired. Later checks are never read once one has. */
    fun firstSignal(index: List<Upstox.Bar>, vix: List<Upstox.Bar>, nowMinute: Int): Reading? =
        readings(index, vix, nowMinute).firstOrNull { it.side != 0 }

    /**
     * The check whose buying minutes run now ([nowMinute]) while its own minute (T-1) has not printed yet in [index] or
     * [vix]: the feed is a moment behind, so it waits rather than read the minute before (null: nothing to wait for).
     */
    fun awaiting(index: List<Upstox.Bar>, vix: List<Upstox.Bar>, nowMinute: Int): Int? {
        val t = CHECKS.lastOrNull { nowMinute >= it && nowMinute < it + ENTRY_GRACE } ?: return null
        return t.takeIf { index.none { b -> b.istMinute == t - 1 } || vix.none { b -> b.istMinute == t - 1 } }
    }

    /** True once every check's buying minutes are over: no signal can come today. */
    fun dayOver(nowMinute: Int): Boolean = nowMinute >= CHECKS.last() + ENTRY_GRACE

    /** Whether [nowMinute] may still buy on [r]: from its check for [ENTRY_GRACE] minutes. */
    fun inEntryWindow(r: Reading, nowMinute: Int): Boolean = nowMinute >= r.check && nowMinute < r.entryUntil

    /** The ATM strike from the index close [x]: round half to even (Python's round), times the step. */
    fun atm(x: Double, underlying: String): Int = (Math.rint(x / step(underlying)) * step(underlying)).toInt()

    /** The 1-ITM strike: the call ([side] +1) one step below the ATM, the put one step above. */
    fun strike(x: Double, underlying: String, side: Int): Int = atm(x, underlying) - side * step(underlying)

    /** Why a new entry may not go now (null: it may), in the order checked: the kill switch, the day's stop, the day lock, a stale price. */
    fun entryRefusal(kill: Boolean, stoppedToday: Boolean, dayLock: String?, freshPrice: Boolean): String? = when {
        kill -> "kill_switch"
        stoppedToday -> "stopped_for_today"
        dayLock != null -> "day_lock: $dayLock"
        !freshPrice -> "stale_price"
        else -> null
    }

    /** NIFTY's stop: 15% under the fill, down to the tick (lib.py: floor(en x 0.85 / tick) x tick). */
    fun stop(fill: Double): Double = floor(fill * (1 - LIQ_STOP) / TICK + 1e-9) * TICK

    /** Whether [underlying] takes the Liquidity exit (NIFTY) or holds to 15:10 (BANKNIFTY). */
    fun liquidityExit(underlying: String): Boolean = underlying == "NIFTY"

    /** What is held: the index, the option's fill and the minute it was bought in. */
    data class Held(val underlying: String, val fill: Double, val entryMinute: Int)

    /** One finished minute of the option: its label, LOW (the wick) and close. */
    data class Minute(val minute: Int, val low: Double, val close: Double)

    /**
     * The exit due at [nowMinute] from the option's finished minutes [bars] (any order; those before the entry minute are
     * ignored), or null to hold: "stop_15" (NIFTY: a minute's low at the stop; a stop and the 20-minute check in the same
     * minute: the stop first), "not_up_5_in_20" (NIFTY: the 20th minute from the entry closed under +5%, forward-filled; no
     * print at all: no time exit, as lib.py), "eod_1510".
     */
    fun exit(h: Held, bars: List<Minute>, nowMinute: Int): String? {
        if (liquidityExit(h.underlying)) {
            val stop = stop(h.fill)
            val check = h.entryMinute + LIQ_MINUTES - 1
            val seen = bars.filter { it.minute >= h.entryMinute && it.minute < nowMinute }.sortedBy { it.minute }
            var timeDone = false
            for (b in seen) {
                if (!timeDone && b.minute > check) {
                    timeDone = true
                    if (notUp(h, seen, check)) return "not_up_5_in_20"
                }
                if (b.low <= stop) return "stop_15"
                if (!timeDone && b.minute == check) {
                    timeDone = true
                    if (b.close < h.fill * (1 + LIQ_MIN_GAIN)) return "not_up_5_in_20"
                }
            }
            if (!timeDone && nowMinute > check && notUp(h, seen, check)) return "not_up_5_in_20"
        }
        return if (nowMinute >= EOD) "eod_1510" else null
    }

    /** The close at the 20-minute check (the last printed at or before it, from the entry): under +5% of the fill. */
    private fun notUp(h: Held, seen: List<Minute>, check: Int): Boolean {
        val c = seen.lastOrNull { it.minute <= check }?.close ?: return false
        return c < h.fill * (1 + LIQ_MIN_GAIN)
    }

    /** "+0.23%". */
    fun pct(x: Double): String = "%+.2f%%".format(java.util.Locale.ENGLISH, x * 100)
}
