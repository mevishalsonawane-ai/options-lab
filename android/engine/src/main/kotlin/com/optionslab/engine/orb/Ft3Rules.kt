package com.optionslab.engine.orb

import com.optionslab.engine.options.OptionMath
import com.optionslab.engine.options.OptionType
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.floor

/**
 * "FT3 near-ATM morning" (21 Oct 2026, Boss: "Add FT3 to the app paper bot until live, when live is started then it should move to
 * live"). Forward test FT3 of research R98 (research/hunt/r98/FORWARD_TEST.md, as amended on 21 Oct for the app): the strongest
 * of the four hero-zero near-misses. BOSS'S CHOICE - IT DID NOT PASS RESEARCH: it was the best of R98e's 8,280 variants, and the
 * luck test says that is what the best of so many looks like by chance (Hansen SPA p = 0.96). Only new days can settle it, so
 * its rules are frozen and its paper record is the forward test (read once, at 12 months and >= 100 tickets).
 *
 *   days      NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX, each on ITS OWN expiry day: today is the nearest listed expiry of
 *             that index ([isExpiryDay]: the instrument master, never the weekday) and a trading day
 *   signal    the clock and the option chain: at each 5-minute mark from 11:00 to 12:55 ([MARKS]), the chain at that minute,
 *             the option's own 1-minute close and volume included - so it is labelled an option-price signal (CLAUDE.md: on
 *             by Boss's choice, its record kept apart) ([SIGNAL_SOURCE])
 *   mark      the 1-minute bar that STARTS at the mark (R98's grid: the 11:00 bar closes at 11:01, [barClose]); prices are that
 *             bar's closes (the ATM legs and the index: the last close within 3 / 5 minutes, [ATM_FRESH], [SPOT_FRESH])
 *   strike    the first mark (per index and day) with an OTM option that ([pick]):
 *               - closed at Rs 6-10 on the mark's bar and traded in its last 3 bars ([PX_LO], [PX_HI], [TRADED_BARS]);
 *               - has a Black-76 IV at most 1.0333 x the ATM IV ([IV_REL_MAX]; [ivRatio]: forward = spot, r = 0, calendar
 *                 time from the mark to 15:30; the ATM IV is the mean of the ATM call's and put's);
 *               - is within 1.5466 ATM straddles of spot ([DIST_EM_MAX]; the straddle = the ATM call + put).
 *             Several at that mark: the nearest spot in straddles (then the lower IV ratio, then the call: the app's tie-break;
 *             the research replay never met a tie). The two cut-offs are the 2021-23 10th percentiles R98e used, to 4 decimals. One ticket per index-day; a mark that qualified but whose order could not be worked in time is that
 *             day's ticket gone, never a later mark's ([firstQualifying], [entryOpen])
 *   order     Rs 5,000 of whole lots ([lots]: floor(5,000 / (price x lot))), a LIMIT BUY at min(ask + 1 tick, price x 1.2 +
 *             0.10) ([limitPrice]), worked in the 3 minutes after the mark's bar closes ([ENTRY_WINDOW]); unfilled by then:
 *             no ticket that day
 *   exit      at 15:05 ([EXIT_AT]), the app's expiry square-off; no stop and no target on paper (R98: held to the close)
 *
 * Its live path is the app's standard one ([LiveMirror]): close-judged, so only a labelled -80% disaster stop rests at Zerodha.
 *
 * Every time is a bar's START unless it says "closes". Pure: no clock, no network, no orders.
 */
object Ft3Rules {
    /** Its paper book's arm; never on the ORB family's live door ([OrbRules]): its live path is [LiveMirror]'s. */
    val ARM = Arm("ft3", "FT3 near-ATM morning", paperOnly = true)
    const val SOURCE = "ft3"
    const val LABEL = "FT3 near-ATM morning"
    /** The owner label its paper orders carry ("FT3 · entry"), for the charges, the scorecard and the trade list. */
    const val OWNER = "FT3"
    /** Shown wherever the arm is (its row, its notices, Jarvis). */
    const val BOSS_CHOICE = "Boss's choice - a forward test, did not pass research (R98e: SPA p 0.96)"
    /** What R98 found, in a line. */
    const val RECORD = "Boss's choice - did not pass research: the best of R98e's 8,280 variants (Hansen SPA p 0.96, PBO 0.33); " +
        "its paper record from 21 Oct 2026 is the forward test"
    /**
     * Its exact rules replayed the app's way (research/hunt/r98/ft3_app.py, 2021-26 expiry days of the five indices: picked from
     * the raw minutes, Black-76 IV, its LIMIT and 3-minute window, out at 15:05).
     */
    const val MEASURED = "Its exact rules replayed on 2021-26 as the app runs them: 478 tickets, 16.3% won, +Rs 1,805 a ticket, " +
        "+Rs 293 without the 5 best days, 4 of 6 years > 0 (2025: -Rs 1.75 lakh); filled a minute later: +Rs 1,579 (+Rs 62)"
    /** Where its signal comes from (Boss, 14 Oct: say it for every rule). */
    const val SIGNAL_SOURCE = "Signal: the clock (5-minute marks 11:00-12:55 on the index's own expiry day) and the option chain " +
        "at that minute - the option's own 1-minute close and volume (labelled: an option-price signal, on by Boss's choice), " +
        "its IV against the ATM's and its distance in straddles; no chart pattern"
    /** The rules in a few words, for its row, its notices and Jarvis. */
    const val RULES = "NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX, each on its own expiry day: at the first 5-minute mark " +
        "11:00-12:55 with an OTM option at Rs 6-10, IV at most 1.0333x the ATM's and within 1.5466 straddles of spot, buys " +
        "Rs 5,000 of it (whole lots); sells at 15:05; no stop on paper"
    /** How it is judged (FORWARD_TEST.md, fixed in advance). */
    const val JUDGED = "Read once at 12 months and >= 100 tickets: passes only if its mean > 0, the day-bootstrap 95% lower bound " +
        "> 0, > 0 without its 3 best days, and Holm p < 0.05 across the four forward tests"
    /** Its trades are kept apart from every other record. */
    const val SEPARATE = "Its trades are kept in its own record (the forward test), apart from every other arm's"

    val UNDERLYINGS = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")

    /** The strike step R98 rounded spot to for the ATM strike. */
    fun strikeStep(underlying: String): Double = when (underlying) {
        "BANKNIFTY", "SENSEX" -> 100.0
        "MIDCPNIFTY" -> 25.0
        else -> 50.0
    }

    const val PX_LO = 6.0
    const val PX_HI = 10.0
    /** The design-years (2021-23) 10th percentile of the IV ratio (1.0333013), to 4 decimals as frozen. */
    const val IV_REL_MAX = 1.0333
    /** The design-years 10th percentile of the distance in ATM straddles (1.5466147), to 4 decimals as frozen. */
    const val DIST_EM_MAX = 1.5466
    const val BUDGET = 5_000.0
    /** Bars t-2 .. t: a candidate must have traded in one of them (R98's `traded`). */
    const val TRADED_BARS = 3L
    /** An ATM leg's price: its last close within this many minutes before the mark's bar (R98's 3-minute forward fill). */
    const val ATM_FRESH = 3L
    /** The index's price: its last close within this many minutes (R98's 5-minute forward fill of spot). */
    const val SPOT_FRESH = 5L
    /** The order is worked for this many minutes after the mark's bar closes. */
    const val ENTRY_WINDOW = 3L
    const val TICK = 0.05

    val FIRST_MARK: LocalTime = LocalTime.of(11, 0)
    val LAST_MARK: LocalTime = LocalTime.of(12, 55)
    /** 11:00, 11:05, .. 12:55 (24 marks): each the START of its 1-minute bar. */
    val MARKS: List<LocalTime> = (0..23).map { FIRST_MARK.plusMinutes(5L * it) }
    /** = ExpirySquareOff.AT_MINUTE in the app (15:05); the app's tests hold the two together. */
    val EXIT_AT: LocalTime = LocalTime.of(15, 5)
    /** The IV clock runs to the index's close. */
    val CLOSE: LocalTime = LocalTime.of(15, 30)

    private const val EPS = 1e-9
    private const val MINUTES_A_YEAR = 365.0 * 24 * 60

    /** Today is the nearest listed expiry of the index on or after today ([HeroRules.isExpiryDay]); no list: never. */
    fun isExpiryDay(today: LocalDate, expiries: Collection<LocalDate>): Boolean = HeroRules.isExpiryDay(today, expiries)

    /** A ticket day for the index: a trading day that is its own expiry day. */
    fun tradesToday(today: LocalDate, tradingDay: Boolean, expiries: Collection<LocalDate>): Boolean = tradingDay && isExpiryDay(today, expiries)

    /** The minute the [mark]'s 1-minute bar closes (the 11:00 bar closes at 11:01). */
    fun barClose(mark: LocalTime): LocalTime = mark.plusMinutes(1)

    /** The marks whose bar has closed by [now] (the ones that can be judged). */
    fun marksClosedBy(now: LocalTime): List<LocalTime> = MARKS.filter { !now.isBefore(barClose(it)) }

    /** The order for a pick at [mark] may still be worked at [now]: from its bar's close for [ENTRY_WINDOW] minutes. */
    fun entryOpen(mark: LocalTime, now: LocalTime): Boolean =
        !now.isBefore(barClose(mark)) && now.isBefore(barClose(mark).plusMinutes(ENTRY_WINDOW))

    enum class Phase { BEFORE, SCAN, LATE }

    /** Before the 11:00 bar closes, the scan (11:01 .. 12:58), or from 12:59 (the last mark's order window has gone). */
    fun phase(now: LocalTime): Phase = when {
        now.isBefore(barClose(FIRST_MARK)) -> Phase.BEFORE
        now.isBefore(barClose(LAST_MARK).plusMinutes(ENTRY_WINDOW)) -> Phase.SCAN
        else -> Phase.LATE
    }

    /** The calendar time from the [mark] (its bar's start) to 15:30, in years; at least a minute. */
    fun yearsToClose(mark: LocalTime): Double = Duration.between(mark, CLOSE).toMinutes().coerceAtLeast(1L) / MINUTES_A_YEAR

    /** R98's ATM strike: spot rounded to the index's [strikeStep], ties to even (as Python's round). */
    fun atmStrike(spot: Double, underlying: String): Double = strikeStep(underlying).let { s -> Math.rint(spot / s) * s }

    /** The ATM straddle: the ATM call + put, both priced; else null. */
    fun straddle(atmCall: Double?, atmPut: Double?): Double? =
        if (atmCall != null && atmPut != null && atmCall > EPS && atmPut > EPS) atmCall + atmPut else null

    /** Black-76 IV (decimal) of an option at [price] (forward = [spot], r = 0, [years] to the close); null where it cannot be solved. */
    fun iv(price: Double, call: Boolean, spot: Double, strike: Double, years: Double): Double? {
        if (!(price > EPS) || !(spot > EPS) || !(strike > EPS) || !(years > 0)) return null
        val intrinsic = if (call) (spot - strike).coerceAtLeast(0.0) else (strike - spot).coerceAtLeast(0.0)
        // R98's solver refused a price not above intrinsic (no time value): so does this.
        if (price <= intrinsic + EPS) return null
        return OptionMath.impliedVol(price, if (call) OptionType.CE else OptionType.PE, spot, strike, years)?.takeIf { it.isFinite() && it > 0 }
    }

    /** The ATM IV: the mean of the ATM call's and put's IVs that can be solved (R98: of those available); none: null. */
    fun atmIv(spot: Double, atmStrike: Double, atmCall: Double?, atmPut: Double?, years: Double): Double? {
        val ivs = listOfNotNull(atmCall?.let { iv(it, true, spot, atmStrike, years) }, atmPut?.let { iv(it, false, spot, atmStrike, years) })
        return if (ivs.isEmpty()) null else ivs.average()
    }

    /** An option's IV against the ATM IV; null when either is unknown. */
    fun ivRatio(price: Double, call: Boolean, spot: Double, strike: Double, atmIv: Double?, years: Double): Double? {
        val a = atmIv?.takeIf { it > EPS } ?: return null
        return iv(price, call, spot, strike, years)?.let { it / a }
    }

    /** One listed option at the mark: its close on the mark's bar (null: no bar at the mark) and whether it traded in the last 3 bars. */
    data class Leg(val strike: Double, val call: Boolean, val close: Double?, val traded: Boolean)

    /**
     * The chain at a mark: the index [spot], the ATM strike's call and put prices ([atmCall], [atmPut]: their last closes within
     * [ATM_FRESH] minutes), and the OTM [legs] read.
     */
    data class Chain(val underlying: String, val spot: Double, val atmCall: Double?, val atmPut: Double?, val legs: List<Leg>) {
        val atmStrike: Double get() = atmStrike(spot, underlying)
        val straddle: Double? get() = straddle(atmCall, atmPut)
    }

    /** The ticket at a mark: the option, its price at the mark, its distance in straddles and its IV ratio. */
    data class Pick(val strike: Double, val call: Boolean, val price: Double, val distEm: Double, val ivRatio: Double) {
        val right: String get() = if (call) "CE" else "PE"
    }

    /** OTM: a call above spot, a put below. */
    fun otm(strike: Double, call: Boolean, spot: Double): Boolean = if (call) strike > spot + EPS else strike < spot - EPS

    /**
     * The ticket at the [mark] from its [chain], or null: among the OTM legs that closed at [PX_LO] .. [PX_HI] on the mark's bar
     * and traded in its last [TRADED_BARS] bars, within [DIST_EM_MAX] straddles of spot and with an IV ratio at most
     * [IV_REL_MAX], the nearest spot in straddles (then the lower IV ratio, then the call). No straddle or no spot: null.
     */
    fun pick(mark: LocalTime, chain: Chain): Pick? {
        val spot = chain.spot
        if (!(spot > EPS)) return null
        val em = chain.straddle ?: return null
        val years = yearsToClose(mark)
        val atmIv = atmIv(spot, chain.atmStrike, chain.atmCall, chain.atmPut, years) ?: return null
        val picks = chain.legs.mapNotNull { l ->
            val px = l.close ?: return@mapNotNull null
            if (!l.traded || !otm(l.strike, l.call, spot)) return@mapNotNull null
            if (px < PX_LO - EPS || px > PX_HI + EPS) return@mapNotNull null
            val dist = abs(l.strike - spot) / em
            if (dist > DIST_EM_MAX + EPS) return@mapNotNull null
            val r = ivRatio(px, l.call, spot, l.strike, atmIv, years) ?: return@mapNotNull null
            if (r > IV_REL_MAX + EPS) return@mapNotNull null
            Pick(l.strike, l.call, px, dist, r)
        }
        return picks.minWithOrNull(compareBy<Pick>({ it.distEm }, { it.ivRatio }, { if (it.call) 0 else 1 }))
    }

    /** The strikes whose prices a mark's pick needs: the OTM strikes on each side within [DIST_EM_MAX] straddles of [spot]. */
    fun band(spot: Double, straddle: Double, strikes: Collection<Double>): List<Double> =
        strikes.filter { abs(it - spot) <= DIST_EM_MAX * straddle + EPS && abs(it - spot) > EPS }.distinct().sortedBy { abs(it - spot) }

    /** The first mark that gave a ticket, from each judged mark's result ([picks]: mark -> its pick or null). */
    fun firstQualifying(picks: Map<LocalTime, Pick?>): Pair<LocalTime, Pick>? =
        picks.entries.filter { it.value != null }.minByOrNull { it.key }?.let { it.key to it.value!! }

    /** Whole lots for Rs 5,000 at [price]: floor(5,000 / (price x lot)); 0 (skip, never rounded up) when a lot costs more or is unknown. */
    fun lots(price: Double, lotSize: Int, budget: Double = BUDGET): Int =
        if (lotSize <= 0 || !(price > EPS)) 0 else floor(budget / (price * lotSize) + EPS).toInt()

    /** The entry limit: [HeroRules.limitPrice] (ask + 1 tick, capped at the mark's price x 1.2 + 0.10), on the tick. */
    fun limitPrice(ask: Double, ref: Double): Double = HeroRules.limitPrice(ask, ref)

    /** The exit is due: from 15:05 on its day, or a ticket from an earlier day ([overnight]). */
    fun exitDue(now: LocalTime, overnight: Boolean = false): Boolean = overnight || !now.isBefore(EXIT_AT)

    /** Why a ticket was sold: [TIME] at 15:05. */
    const val EXIT_WHY = "ft3_1505"
}
