package com.optionslab.ira

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * 7 Nov (Boss: "if any manual order is placed, then stoploss and target (profit lock) order should be placed by the application",
 * from Zerodha or the app): the decisions behind "Stop and target on my manual positions".
 *
 * A live option Boss bought by hand - in the Kite app or on IraAlgo's own order screen - that nothing in the app follows (no arm,
 * no Pine script, no arm's live trade, no protection) gets a stop 5% under its average price and a target sell 10% over it (Boss's
 * choice, 7 Nov: a fixed target, no trail; first 20% / 40%, then "too much, try 5-10" the same day). On 6 Nov NIFTY26N0925150CE (130 qty) was bought by hand and stayed unprotected overnight.
 *
 * It only ever adds exits (lowers risk). It never places an entry, never resizes, and never cancels or changes Boss's own orders:
 * any working order or active GTT on the contract means Boss (or an entry still filling) is handling it, and it is left alone.
 *
 * Pure: no clock, no I/O. The app ([com.optionslab.app.data.ManualCover]) reads Zerodha's books and acts.
 */
object ManualCoverRule {
    /** The stop: 95% of the average price (5% under it), rounded down to the tick. */
    const val STOP_FRACTION = 0.95
    /** The target: 110% of the average price (10% over it), rounded up to the tick. */
    const val TARGET_FRACTION = 1.10
    /** Two looks this far apart, the same quantity both times, before acting (an arm's fill may not be recorded yet). */
    const val SETTLE_MS = 20_000L
    /** Placing tried at most this many times a day per contract; then Boss is told loudly that it is NOT protected. */
    const val MAX_TRIES = 3
    /** 09:15 IST: the session's open. */
    const val FROM_MINUTE = 9 * 60 + 15
    /** 15:24 IST: the last minute it acts (DAY orders only, never an AMO; the flat-by and the square-offs follow). */
    const val UNTIL_MINUTE = 15 * 60 + 24

    /** The exchanges it covers: NSE's and BSE's derivatives. MCX, cash and anything else is never touched. */
    val EXCHANGES = setOf("NFO", "BFO")
    /** The products it covers (CNC and anything else is left alone). */
    val PRODUCTS = setOf("MIS", "NRML")

    /**
     * How a protection may have ended today and still let a new manual buy of the same contract be covered: the position itself
     * ended (closed, a GTT fired, its stop or target filled) or a newer protection took its place. Any other end (Boss removed it,
     * cancelled its stop in Kite, a stop was rejected, its orders stopped working) is Boss's or Zerodha's choice: not re-added today.
     */
    val NATURAL_ENDS = setOf("position closed", "GTT fired", "replaced", "stop filled", "target filled")

    /** One row of Zerodha's net positions. [qty] signed (+ bought). */
    data class Pos(val symbol: String, val exchange: String, val product: String, val qty: Int, val avg: Double, val last: Double) {
        val key: String get() = "$exchange:$symbol"
    }

    /** A protection set today that is over, and the note it ended with. */
    data class Ended(val symbol: String, val note: String)

    /**
     * What the app knows of [Pos]'s contract. Symbols: [followed] (an arm's, Pine's, an arm's live trade, a live strategy run's leg or a protection),
     * [protectedNow] (an active protection), [endedToday]. "EXCHANGE:SYMBOL" keys: [handsOff] (Boss is exiting it by hand),
     * [workingOrders], [activeGtts], [appOrders] (a fill today from an order the app placed for an arm or a strategy), [expiring]
     * (it expires today and the expiry square-off is about to close it: from [EXPIRY_QUIET_MINUTES] before it).
     */
    data class Facts(
        val followed: Set<String> = emptySet(),
        val protectedNow: Set<String> = emptySet(),
        val endedToday: List<Ended> = emptyList(),
        val handsOff: Set<String> = emptySet(),
        val workingOrders: Set<String> = emptySet(),
        val activeGtts: Set<String> = emptySet(),
        val appOrders: Set<String> = emptySet(),
        val expiring: Set<String> = emptySet(),
    )

    enum class Skip(val words: String) {
        NOT_COVERED("not a bought NFO/BFO option in MIS or NRML"),
        FOLLOWED("an arm, a Pine script, a strategy or a protection follows it"),
        PROTECTED("it has a protection"),
        HANDS_OFF("you are exiting it by hand"),
        ENDED_TODAY("its protection was removed or ended today"),
        WORKING_ORDER("an order on it is working at Zerodha"),
        GTT("a GTT on it is active at Zerodha"),
        APP_ORDER("the app's own order bought it"),
        EXPIRING("it expires today and the expiry square-off closes it"),
    }

    /**
     * On its expiry day a contract is left to the expiry square-off from this many minutes before it (15:05): a stop and target
     * placed then would only be cancelled by it a moment later.
     */
    const val EXPIRY_QUIET_MINUTES = 5

    /** Whether a contract expiring today is left to the expiry square-off at [minute] (it is at [squareOffMinute] when [on]). */
    fun leftToExpiry(on: Boolean, minute: Int, squareOffMinute: Int): Boolean = on && minute >= squareOffMinute - EXPIRY_QUIET_MINUTES

    /** NSE's session for this: a trading day, 09:15 to 15:24 IST. */
    fun inHours(tradingDay: Boolean, minute: Int): Boolean = tradingDay && minute in FROM_MINUTE..UNTIL_MINUTE

    /** A bought (qty > 0) option (the symbol ends CE or PE) on NFO or BFO, in MIS or NRML. Shorts, futures, MCX and CNC are not. */
    fun covered(p: Pos): Boolean = p.qty > 0 && p.exchange in EXCHANGES && p.product in PRODUCTS &&
        (p.symbol.endsWith("CE") || p.symbol.endsWith("PE"))

    /** Why [p] is left alone, or null: it is a manual position nothing covers. The first reason that applies, in this order. */
    fun skip(p: Pos, f: Facts): Skip? = when {
        !covered(p) -> Skip.NOT_COVERED
        p.symbol in f.followed -> Skip.FOLLOWED
        p.symbol in f.protectedNow -> Skip.PROTECTED
        p.key in f.handsOff -> Skip.HANDS_OFF
        p.key in f.expiring -> Skip.EXPIRING
        f.endedToday.any { it.symbol == p.symbol && it.note !in NATURAL_ENDS } -> Skip.ENDED_TODAY
        p.key in f.workingOrders -> Skip.WORKING_ORDER
        p.key in f.activeGtts -> Skip.GTT
        p.key in f.appOrders -> Skip.APP_ORDER
        else -> null
    }

    /** The stop and target for [avg] on [tick]: the stop rounded DOWN, the target UP (never looser than 5% / tighter than 10%). */
    data class Levels(val stop: Double, val target: Double)

    fun levels(avg: Double, tick: Double): Levels? {
        if (!(avg > 0) || !(tick > 0) || !avg.isFinite()) return null
        val t = BigDecimal.valueOf(tick)
        fun on(x: Double, mode: RoundingMode): Double =
            BigDecimal.valueOf(x).divide(t, 6, RoundingMode.HALF_EVEN).setScale(0, mode).multiply(t).toDouble()
        val stop = on(avg * STOP_FRACTION, RoundingMode.FLOOR)
        val target = on(avg * TARGET_FRACTION, RoundingMode.CEILING)
        return if (stop > 0) Levels(stop, target) else null
    }

    /** What to place for one position. */
    sealed class Plan {
        /** The stop and the target sell. */
        data class Both(val stop: Double, val target: Double) : Plan()
        /** The price is already at or over the target: the stop only (a target sell under the price would fill at once). */
        data class StopOnly(val stop: Double, val target: Double) : Plan()
        /** The price is already at or under the stop: nothing placed; Boss is told to set one by hand. */
        data class TooLow(val stop: Double) : Plan()
        /** No usable average price or last price: nothing this look. */
        object NoPrice : Plan()
    }

    fun plan(avg: Double, last: Double, tick: Double): Plan {
        val l = levels(avg, tick) ?: return Plan.NoPrice
        if (!(last > 0) || !last.isFinite()) return Plan.NoPrice
        return when {
            last <= l.stop -> Plan.TooLow(l.stop)
            last >= l.target -> Plan.StopOnly(l.stop, l.target)
            else -> Plan.Both(l.stop, l.target)
        }
    }

    /**
     * The two looks before acting. A contract is due once it was seen uncovered with the same quantity at two looks at least
     * [SETTLE_MS] apart; a new quantity starts it again, and a contract not seen uncovered in a look is forgotten. Memory only.
     * Not thread-safe by itself: the app calls it from the watch's pass only (one at a time).
     */
    class Looks {
        private data class Seen(val qty: Int, val since: Long)
        private val seen = HashMap<String, Seen>()

        /** [key] seen uncovered with [qty] at [nowMs]: true when it is due. */
        fun see(key: String, qty: Int, nowMs: Long): Boolean {
            val s = seen[key]
            if (s == null || s.qty != qty || nowMs < s.since) { seen[key] = Seen(qty, nowMs); return false }
            return nowMs - s.since >= SETTLE_MS
        }

        /** Only [keys] are kept (the others were not seen uncovered in this look). */
        fun keepOnly(keys: Set<String>) { seen.keys.retainAll(keys) }

        fun clear() = seen.clear()

        val size: Int get() = seen.size
    }

    /** Today's counts for the diagnostics line (memory; a new day starts at nothing). */
    data class Tally(val day: LocalDate? = null, val protectedN: Int = 0, val leftAlone: Int = 0, val failed: Int = 0) {
        fun on(today: LocalDate): Tally = if (day == today) this else Tally(today)
    }

    /** The diagnostics / health line. */
    fun line(on: Boolean, loaded: Boolean, t: Tally, today: LocalDate): String {
        val d = t.on(today)
        val state = if (!loaded) "not read yet" else if (on) "on" else "off"
        return "Stop and target on my manual positions: $state; today protected ${d.protectedN}, left alone ${d.leftAlone}, failed ${d.failed}"
    }

    private fun f2(x: Double): String = String.format(java.util.Locale.ENGLISH, "%.2f", x)

    /** Boss's words when it protected a position Boss bought by hand. */
    fun saidProtected(symbol: String, qty: Int, avg: Double, result: String): String =
        "$symbol: you bought $qty yourself (average ${f2(avg)}) and nothing in the app followed it, so the app protected it " +
            "automatically: a stop 5% under your average and a target sell 10% over it (Stop and target on my manual positions). $result"

    /** The price is already at or over the target: the stop alone. */
    fun saidStopOnly(symbol: String, last: Double, target: Double): String =
        "$symbol is already at ${f2(last)}, at or over the 10% target (${f2(target)}), so only the stop was placed."

    /** The price is already 5% or more under the average: nothing placed. */
    fun saidTooLow(symbol: String, last: Double, stop: Double): String =
        "$symbol is already down 5% or more (last ${f2(last)}, the stop would be ${f2(stop)}): no stop was placed. Set one yourself."

    /** After [MAX_TRIES] refusals today. */
    fun saidGaveUp(symbol: String, qty: Int, why: String): String =
        "$symbol ($qty bought by you) is NOT protected: the app tried $MAX_TRIES times to place its stop and target and Zerodha " +
            "refused ($why). Set a stop yourself."
}
