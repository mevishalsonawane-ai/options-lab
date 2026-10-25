package com.optionslab.engine.orb

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * "Hero ₹1,000 ticket" (15 Oct 2026, Boss: "We will be getting on 1000 rs worth of lots ... add it directly to arm"; then "25%
 * trail it is, and 1000 rs worth of lots"). A PAPER-ONLY arm, separate from the expiry-day Hero arm ([HeroRules]) and with its
 * own switch and record. BOSS'S CHOICE - IT DID NOT PASS RESEARCH: R50 (research/HUNT_R50_HERO_RECENT.md) found 0 of 28 hero
 * rules pass on 20 Nov 2024 - 13 Oct 2026; cheap expiry tickets lose about Rs 525 of every Rs 1,000 staked, and the profits
 * come from rare jackpots (the SENSEX 72,500 CE on 2 Apr 2026). It never has a path to Zerodha.
 *
 * The rules are R50's tested cells (the 12:30 basket's strike rule, PREREG "cheap"; Boss's 25% trail, PREREG "tr25"), with
 * Boss's Rs 1,000 a ticket:
 *
 *   days      NIFTY and SENSEX, each on ITS OWN expiry day: today is the nearest listed expiry of that index ([isExpiryDay]:
 *             the instrument master, never the weekday - holidays move it) and a trading day ([tradesToday])
 *   signal    the clock only (12:30); no chart signal, no option-chart signal ([SIGNAL_SOURCE])
 *   entry     on the 1-minute bar closing 12:30 ([ENTRY_BAR]); the order is worked over the next 3 minutes (bars starting
 *             12:30, 12:31, 12:32 - R50's next-minute fills), never after 12:33 ([entryPhase]); no fresh index price for the
 *             12:30 bar by then: no ticket that day
 *   strike    each side (CE, PE): the nearest-the-money OTM option within 5% of spot priced Rs 5-30, traded in the last 5
 *             bars, with an ask, not stale (no further-OTM strike priced above it by more than max(0.10, 25%)) - R25's
 *             `eligible`, R50's "cheap" - AND whose one lot costs at most Rs 1,000 at that price ([pick], [fits]). None: that
 *             side is skipped. NIFTY's lot of 65 allows up to Rs 15.35; SENSEX's 20 the whole band
 *   order     1 lot ([LOTS]), a LIMIT BUY at min(ask + 1 tick (2 under Rs 2), price x 1.2 + 0.10, Rs 1,000 / lot) on the tick
 *             ([limitPrice]): the ticket never costs more than Rs 1,000 (plus charges), its most it can lose
 *   budget    at most Rs 2,000 of tickets a day across both indices ([dayBudgetOk])
 *   exits     against the entry FILL e, checked on every pass of the app's watch:
 *               target  only when Boss picks 2x or 3x on its row ([Target]; the default is trail only): once the price trades
 *                       a tick through floorTick(m x e), a LIMIT SELL there; a gap through it is booked at the target, never
 *                       the better gap price ([bookedTarget], R50's fill model)
 *               15:05   [EXIT_AT], the expiry square-off: whatever is held sells
 *               trail   Boss's 25% trail exactly as R50 measured it ([trail]): a 1-minute bar closing after the fill's own bar
 *                       and before 15:05 at or below 0.75 x the best close so far (the fill counts as the first best) sells at
 *                       the next look. It is also the stop: a ticket that never rises sells 25% under its fill.
 *             Checked in that order (target, then 15:05, then the trail) ([exitStep]).
 *
 * Every time here is the minute a 1-minute bar CLOSES (a bar labelled 12:29 closes at 12:30). Pure: no clock, no network,
 * no orders.
 */
object HeroTicketRules {
    /** Paper only, always; never the Hero arm (it has none of its flags). */
    val ARM = Arm("hero_ticket", "Hero ₹1,000 ticket", paperOnly = true)
    const val SOURCE = "hero_ticket"
    const val LABEL = "Hero ₹1,000 ticket"
    /** The owner label its paper orders carry ("Hero ₹1,000 · entry"), for the charges, the scorecard and the trade list. */
    const val OWNER = "Hero ₹1,000"
    /** Shown wherever the arm is (its row, its notices, Jarvis). */
    const val BOSS_CHOICE = "Boss's choice - did not pass research (R50)"
    /** The label Boss asked for (15 Oct), on its row. */
    const val RECORD = "Boss's choice - did not pass research (R50): about -Rs 525 per Rs 1,000 on average; jackpots are rare"
    /**
     * Its own exact rules re-run on R50's data (15 Oct, R50's engine, charges and fills; NIFTY and SENSEX expiry days only): the
     * design years and the final test, with sample sizes. The Rs 525 above is R50's cheap-ticket baskets held to 15:05.
     */
    const val MEASURED = "Its exact rules on R50's data: 20 Nov 2024 - 31 Mar 2026, 252 tickets on 142 expiry days, -Rs 14,351 " +
        "(-Rs 88 per Rs 1,000); 1 Apr - 13 Oct 2026, 110 tickets on 55 days, +Rs 3,047 (-Rs 10,144 without its best 5)"
    /** Where its signal comes from (Boss, 14 Oct: say it for every rule). */
    const val SIGNAL_SOURCE = "Signal: the clock only (12:30 on the index's own expiry day), no chart signal"
    /** The rules in a few words, for its row, its notices and Jarvis. */
    const val RULES = "NIFTY and SENSEX, each on its own expiry day: at 12:30 buys 1 lot of a call and 1 lot of a put priced Rs 5-30 " +
        "whose lot costs at most Rs 1,000 (nearest the money); sells on your 25% trail (a minute closing 25% below its best close, " +
        "from where the trail starts) or at 15:05; at most Rs 2,000 a day; paper only"
    /**
     * 15 Oct (research R54): R50's "tr25" trail starts at the fill, so it is also a 25% stop, and these options swing 18-25% inside
     * a minute - it sold the 2 Apr 2026 SENSEX 72,500 CE at 2.7x (+Rs 280) where holding to 15:05 made +Rs 13,958. Boss was told
     * the trail starts once the option has run up: so where it starts is a setting ([TrailStart]); "after 2x" was a placeholder
     * default until Boss chose after the follow-up comparison (research R55).
     * 25 Oct (Boss: "Do whatever you think better for application"; the arms audit): R55 found "after 2x" the worst of its five
     * cells and recommends the trail from the fill, so the default is now [TrailStart.AT_FILL] (paper only; a one-time change,
     * [TRAIL_FILL_MIGRATION], logged in the Autopilot change timeline, one tap back). Until a ticket doubles, the old default
     * left no stop below it but the 15:05 sale.
     */
    const val TRAIL_START_NOTE = "Where the trail starts: from the fill since 25 Oct (research R55's recommended cell; \"after 2×\" " +
        "was its worst); R50 measured it from the fill"

    /** 25 Oct: the one-time change of a book still on the old "after 2x" placeholder to the trail from the fill (kept in its marks). */
    const val TRAIL_FILL_MIGRATION = "ticket_trail_from_fill_2026_10_25"
    /** Why, for the change timeline and the book's log (R55, sample sizes in research/HUNT_R55*). */
    const val TRAIL_FILL_REASON = "your decision on the arms audit (25 Oct): research R55 found the trail \"after 2×\" the worst of its five " +
        "cells (design 248 tickets: from the fill -Rs 14,507, after 2× -Rs 59,286; final 107 tickets: +Rs 1,559 vs -Rs 9,680) and " +
        "recommends it from the fill (R50's own rule: also a 25% stop); new tickets only, an open ticket keeps its own; the trend-turn exit, " +
        "tested with the trail after 2×, only takes notes on these tickets; paper only"
    /** Its trades are kept apart from every other record (the arms that passed, the plain-rules record for 26 Oct). */
    const val SEPARATE = "Its trades are kept in its own record, apart from the arms that passed and from the plain-rules record for 26 Oct"

    val UNDERLYINGS = listOf("NIFTY", "SENSEX")
    /** The index's strike step (NIFTY 50, SENSEX 100). */
    fun strikeStep(underlying: String): Int = if (underlying == "SENSEX") 100 else 50

    const val TICKET_BUDGET = 1_000.0
    const val DAY_BUDGET = 2_000.0
    const val LOTS = 1
    const val MIN_PRICE = 5.0
    const val MAX_PRICE = 30.0
    /** Candidates within this share of spot (R25's `otm_chain`). */
    const val SPOT_BAND = 0.05
    /** Bars t-4 .. t: a candidate must have traded in one of them. */
    const val TRADED_BARS = 5L
    /** Boss's trail: a close this far below the best close sells (R50 "tr25"). */
    const val TRAIL = 0.25
    const val TICK = 0.05

    /** The decision bar: the 1-minute bar closing at 12:30. */
    val ENTRY_BAR: LocalTime = LocalTime.of(12, 30)
    /** The order is worked until this minute (R50: the 3 bars after the signal bar); later, no ticket that day. */
    val ENTRY_UNTIL: LocalTime = LocalTime.of(12, 33)
    /** = ExpirySquareOff.AT_MINUTE in the app (15:05); the app's tests hold the two together. */
    val EXIT_AT: LocalTime = LocalTime.of(15, 5)

    private const val EPS = 1e-9

    /** The exit Boss picks on its row: a target at 2x or 3x of the fill beside the trail, or the trail alone (the default). */
    enum class Target(val key: String, val mult: Double?, val words: String) {
        TRAIL_ONLY("trail", null, "trail only"), X2("2x", 2.0, "2× target"), X3("3x", 3.0, "3× target");

        companion object {
            /** The saved [key]'s target; anything unknown (or nothing saved) is the default, trail only. */
            fun of(key: String?): Target = entries.firstOrNull { it.key == key } ?: DEFAULT
            val DEFAULT = TRAIL_ONLY
        }
    }

    /**
     * Where Boss's 25% trail starts ([trail]): at the fill (R50's "tr25": also a 25% stop; the default from 25 Oct), or only once
     * the option has closed at or above 2x / 3x its fill (no stop below that: until then it holds to 15:05, and the most it can
     * lose is the ticket).
     */
    enum class TrailStart(val key: String, val mult: Double?, val words: String) {
        AT_FILL("fill", null, "trail from the fill (R50)"), AFTER_2X("2x", 2.0, "trail after 2×"), AFTER_3X("3x", 3.0, "trail after 3×");

        companion object {
            /** The saved [key]'s start; anything unknown (or nothing saved) is the default. */
            fun of(key: String?): TrailStart = entries.firstOrNull { it.key == key } ?: DEFAULT
            /** 25 Oct: from the fill (R55's recommended cell); it was "after 2x", a placeholder, from 15 Oct. */
            val DEFAULT = AT_FILL
            /** The start a ticket record saved before 15 Oct's field reads as (the placeholder then in force). */
            val LEGACY = AFTER_2X
        }
    }

    /** Today is the nearest listed expiry of the index on or after today ([HeroRules.isExpiryDay]); no list: never. */
    fun isExpiryDay(today: LocalDate, expiries: Collection<LocalDate>): Boolean = HeroRules.isExpiryDay(today, expiries)

    /** A ticket day for the index: a trading day (not a holiday or weekend) that is its own expiry day. */
    fun tradesToday(today: LocalDate, tradingDay: Boolean, expiries: Collection<LocalDate>): Boolean = tradingDay && isExpiryDay(today, expiries)

    enum class Phase { BEFORE, OPEN, LATE }

    /** Before 12:30, 12:30 .. 12:32 (the order may be worked), or 12:33 and later (the day's chance has gone). */
    fun entryPhase(t: LocalTime): Phase = when {
        t.isBefore(ENTRY_BAR) -> Phase.BEFORE
        t.isBefore(ENTRY_UNTIL) -> Phase.OPEN
        else -> Phase.LATE
    }

    /** One lot at [ltp] costs at most Rs 1,000 ([lotSize] known). */
    fun fits(ltp: Double, lotSize: Int): Boolean = lotSize > 0 && ltp > EPS && ltp * lotSize <= TICKET_BUDGET + EPS

    /**
     * The ticket on [side] (+1 CE: strike above [spot]; -1 PE: below), or null to skip that side: the nearest-the-money OTM
     * candidate within [SPOT_BAND] of spot priced [MIN_PRICE] .. [MAX_PRICE], traded recently, with an ask, not stale (no
     * further-OTM strike on that side priced above ltp + max(0.10, 25% of ltp)), and one lot of [lotSize] within Rs 1,000.
     */
    fun pick(side: Int, spot: Double, lotSize: Int, chain: List<HeroRules.Candidate>): HeroRules.Candidate? {
        if (spot <= EPS || lotSize <= 0) return null
        val otm = chain.filter { (if (side > 0) it.strike > spot + EPS else it.strike < spot - EPS) && abs(it.strike - spot) <= spot * SPOT_BAND + EPS }
            .sortedBy { abs(it.strike - spot) }
        for ((i, c) in otm.withIndex()) {
            if (c.ltp < MIN_PRICE - EPS || c.ltp > MAX_PRICE + EPS) continue
            if (!c.tradedRecently || c.ask == null || c.ask <= EPS) continue
            val slack = max(0.10, 0.25 * c.ltp)
            if (otm.drop(i + 1).any { it.ltp > c.ltp + slack + EPS }) continue
            if (!fits(c.ltp, lotSize)) continue
            return c
        }
        return null
    }

    /** The entry limit: [HeroRules.limitPrice] (ask + 1 tick, capped at ref x 1.2 + 0.10), never above Rs 1,000 / lot. */
    fun limitPrice(ask: Double, ref: Double, lotSize: Int): Double =
        min(HeroRules.limitPrice(ask, ref), HeroRules.floorTick(TICKET_BUDGET / lotSize.coerceAtLeast(1)))

    /** A new ticket costing [cost] fits today's Rs 2,000 with [spentToday] already spent on tickets. */
    fun dayBudgetOk(spentToday: Double, cost: Double): Boolean = spentToday + cost <= DAY_BUDGET + EPS

    /** The target's limit for a fill at [entry] under [target]: m x entry down to the tick; null with the trail alone. */
    fun targetLevel(entry: Double, target: Target): Double? = target.mult?.let { HeroRules.floorTick(entry * it) }

    /** A target LIMIT SELL that filled at [fill] is booked at the target at most: a gap through it never counts as better. */
    fun bookedTarget(fill: Double, target: Double): Double = min(fill, target)

    /** The trail's state: the best close since the buy ([peak], the fill to start with) and the minute it was hit (null: not). */
    data class Trail(val peak: Double, val hitAt: LocalTime?) {
        val hit: Boolean get() = hitAt != null
        /** Where the trail sells now: 25% below the best close. */
        val level: Double get() = peak * (1 - TRAIL)
    }

    /**
     * Boss's 25% trail from the option's 1-minute [closes] (keyed by each bar's CLOSE time) after a fill at [entry] at
     * [entryTime]: the fill's own bar (closing the minute after [entryTime]'s) only raises the best; every later bar closing
     * before 15:05 sells when its close is at or below 0.75 x the best close before it (the fill counting as the first). Read
     * from the bars alone, so a restart in the middle of a trade finds the same answer.
     */
    fun trail(entry: Double, entryTime: LocalTime, closes: Map<LocalTime, Double>, start: TrailStart = TrailStart.AT_FILL): Trail {
        val fillBar = entryTime.withSecond(0).withNano(0).plusMinutes(1)
        // Armed from the fill (R50), or once a close has reached m x the fill (then no stop below: held to 15:05 until then).
        val armAt = start.mult?.let { it * entry }
        var best = entry
        for ((t, c) in closes.toSortedMap()) {
            if (t.isBefore(fillBar) || c <= EPS) continue
            val armed = armAt == null || best >= armAt - EPS
            if (armed && t.isAfter(fillBar) && t.isBefore(EXIT_AT) && c <= (1 - TRAIL) * best + EPS) return Trail(best, t)
            best = max(best, c)
        }
        return Trail(best, null)
    }

    enum class ExitKind(val why: String) { TARGET("ticket_target"), TIME("ticket_1505"), TRAIL("ticket_trail") }

    /** What to sell now (the whole ticket): at the LIMIT [limit], or (null) at the bid less a tick. */
    data class ExitStep(val kind: ExitKind, val limit: Double?)

    /**
     * The next exit of a ticket bought at [entry], or null to hold: [ltp] the last price now; [trail] from [trail]; [t] now;
     * [target] Boss's pick when it was bought; [overnight] it is from an earlier day. Target first, then 15:05, then the trail.
     */
    fun exitStep(entry: Double, ltp: Double?, trail: Trail, t: LocalTime, target: Target, overnight: Boolean = false): ExitStep? {
        if (entry <= EPS) return null
        targetLevel(entry, target)?.let { lv -> if (HeroRules.targetHit(lv, ltp)) return ExitStep(ExitKind.TARGET, lv) }
        if (overnight || !t.isBefore(EXIT_AT)) return ExitStep(ExitKind.TIME, null)
        if (trail.hit) return ExitStep(ExitKind.TRAIL, null)
        return null
    }
}
