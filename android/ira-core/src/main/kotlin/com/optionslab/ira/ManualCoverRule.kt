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
    /** Definite refusals (RMS and the like) at most this many a day per contract; then Boss is told loudly that it is NOT protected. */
    const val MAX_TRIES = 3
    /**
     * 7 Nov, review: every attempt counted (transient failures too: a read that timed out, a lost reply, a trigger the price moved
     * through) at most this many a day per contract, [RETRY_GAP_MS] apart at least, so it never floods Zerodha.
     */
    const val MAX_ATTEMPTS = 10
    const val RETRY_GAP_MS = 60_000L
    /** 7 Nov, review (a 5% stop is tight): the last price must be at least this many ticks over the stop ... */
    const val ROOM_TICKS = 3
    /** ... and at least this fraction of the average over it; less is "already down", nothing is placed (looked at again later). */
    const val ROOM_FRACTION = 0.01
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
    // 7 Nov, review: "exit orders no longer working" too - a carried position's DAY exits that ended with their session and were
    // not placed again (Protections then says "NOT protected any more: set the protection again"); that is no choice of Boss's.
    val NATURAL_ENDS = setOf("position closed", "GTT fired", "replaced", "stop filled", "target filled", "exit orders no longer working")

    /** How far back a carried (NRML, held overnight) position's ended protections are looked at (7 Nov, review). */
    const val CARRIED_DAYS = 7L

    /**
     * One row of Zerodha's net positions. [qty] signed (+ bought). [carried]: NRML and held since an earlier day (its overnight
     * quantity), so a protection Boss removed on an earlier day is still respected (7 Nov, review).
     */
    data class Pos(val symbol: String, val exchange: String, val product: String, val qty: Int, val avg: Double, val last: Double,
                   val carried: Boolean = false) {
        val key: String get() = "$exchange:$symbol"
        /** The two looks' key (7 Nov, review): one contract held in two products is two positions. */
        val lookKey: String get() = "$exchange:$symbol:$product"
    }

    /**
     * A protection that is over, the note it ended with, and whether it was set today (7 Nov, review: a carried position looks at
     * the last [CARRIED_DAYS] days; an intraday one only at today's). [exchange] empty: any.
     */
    data class Ended(val symbol: String, val note: String, val today: Boolean = true, val exchange: String = "")

    /** The underlying an option's Zerodha symbol starts with ("NIFTY26N0925150CE" -> "NIFTY"): the letters before its first digit. */
    fun underlying(symbol: String): String = symbol.takeWhile { !it.isDigit() }

    /** A Zerodha option symbol (CE or PE). */
    fun isOption(symbol: String): Boolean = symbol.endsWith("CE") || symbol.endsWith("PE")

    /**
     * 7 Nov, review: the underlyings the account is SHORT an option on (any expiry, any product). A long option on one of these may
     * be a spread's wing (Boss's Ledger ticket: a short PE and a bought wing PE): covering the wing alone could leave the short naked.
     */
    fun shortUnderlyings(book: List<Pos>): Set<String> =
        book.filter { it.qty < 0 && isOption(it.symbol) }.map { underlying(it.symbol) }.toSet()

    /**
     * Whether a contract is a leg of the open Expiry Put ticket (as the expiry square-off recognises them): the ticket's underlying,
     * a PE, at its strike or its wing's. A contract whose strike is not known (null [strike]) is counted as a leg when its
     * underlying matches and it is (or may be) a PE: the safe side.
     */
    fun isTicketLeg(name: String, strike: Double?, right: String?, ticketUnderlying: String, ticketStrike: Double, wingStrike: Double?): Boolean {
        if (name != ticketUnderlying) return false
        if (strike == null) return right == null || right == "PE"
        return right == "PE" && (strike == ticketStrike || strike == wingStrike)
    }

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
        /** 7 Nov, review: underlyings the account is short an option on ([shortUnderlyings]). */
        val shortUnderlyings: Set<String> = emptySet(),
        /** 7 Nov, review: "EXCHANGE:SYMBOL" keys of the open ticket's legs ([isTicketLeg]). */
        val ticketLegs: Set<String> = emptySet(),
        /** 7 Nov, review: "EXCHANGE:SYMBOL" keys held in more than one product (protections are kept per symbol). */
        val twoProducts: Set<String> = emptySet(),
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
        SPREAD("left alone (part of a spread)"),
        TWO_PRODUCTS("held as both MIS and NRML: set the stops yourself"),
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
        p.key in f.ticketLegs || underlying(p.symbol) in f.shortUnderlyings -> Skip.SPREAD
        p.key in f.twoProducts -> Skip.TWO_PRODUCTS
        f.endedToday.any { e -> e.symbol == p.symbol && (e.exchange.isEmpty() || e.exchange == p.exchange) && (e.today || p.carried) &&
            e.note !in NATURAL_ENDS } -> Skip.ENDED_TODAY
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
        /** The price is already at, under or just over the stop ([room]): nothing placed; Boss is told to set one by hand. */
        data class TooLow(val stop: Double) : Plan()
        /** No usable average price or last price: nothing this look. */
        object NoPrice : Plan()
    }

    /** The room the last price needs over the stop ([ROOM_TICKS] ticks or [ROOM_FRACTION] of the average, the larger). */
    fun room(avg: Double, tick: Double): Double = maxOf(ROOM_TICKS * tick, avg * ROOM_FRACTION)

    fun plan(avg: Double, last: Double, tick: Double): Plan {
        val l = levels(avg, tick) ?: return Plan.NoPrice
        if (!(last > 0) || !last.isFinite()) return Plan.NoPrice
        return when {
            // 7 Nov, review: too close over the stop is "too low" too (it would trigger, or be refused, at once).
            last < l.stop + room(avg, tick) - 1e-9 -> Plan.TooLow(l.stop)
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

    /**
     * 7 Nov, review: whether a "Not protected: ..." answer counts toward [MAX_TRIES]. Transient ones never do: a read or reply that
     * failed or was lost, the lot and tick not read, a stop on the wrong side of the price (it moved), Paper mode meanwhile. A
     * definite refusal (Zerodha's RMS, an input refused) does.
     */
    fun definiteRefusal(result: String): Boolean {
        val r = result.lowercase()
        val transient = listOf("could not read", "could not be confirmed", "lost", "timed out", "timeout", "gateway", "network",
            "couldn't connect", "check the order book", "try again", "the stop must be", "the target must be", "trigger",
            "switch to live", "no matching order", "unknown", "status")
        return transient.none { it in r }
    }

    /**
     * 7 Nov, review: whether an attempt may be made now: under [MAX_ATTEMPTS] today and [RETRY_GAP_MS] since the last one.
     */
    fun mayAttempt(attempts: Int, lastAttemptMs: Long?, nowMs: Long): Boolean =
        attempts < MAX_ATTEMPTS && (lastAttemptMs == null || nowMs - lastAttemptMs >= RETRY_GAP_MS || nowMs < lastAttemptMs)

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

    /** The price is already at, under or just over the 5% stop: nothing placed (looked at again on later passes). */
    fun saidTooLow(symbol: String, last: Double, stop: Double): String =
        "$symbol is already at or close to 5% down (last ${f2(last)}, the stop would be ${f2(stop)}): no stop was placed. Set one yourself."

    /** 7 Nov, review: a contract left alone for a reason Boss should hear once a day (a spread, two products). */
    fun saidLeftAlone(symbol: String, why: Skip): String = "$symbol: no automatic stop and target, ${why.words}."

    /** After [tries] refusals or attempts today ([MAX_TRIES] / [MAX_ATTEMPTS]). */
    fun saidGaveUp(symbol: String, qty: Int, why: String, tries: Int = MAX_TRIES): String =
        "$symbol ($qty bought by you) is NOT protected: the app tried $tries times to place its stop and target and it was not " +
            "placed ($why). Set a stop yourself."
}
