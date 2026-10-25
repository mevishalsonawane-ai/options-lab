package com.optionslab.ira

import com.optionslab.engine.options.OptionMath
import com.optionslab.engine.options.OptionType
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * 25 Oct 2026 (Boss: "Do whatever you think better for application", on the app audit's open decisions,
 * research/APP_AUDIT_BOOKS.md "For Boss to decide"): the new risk rules, as pure functions. The app's host
 * ([com.optionslab.app.data.BossRiskHost]) keeps their switches and the figures they read, and asks them at the paper book's
 * entry ([com.optionslab.app.data.Paper.place], applied: PAPER on) and inside Zerodha's entry door (SHADOW: logged only, behind
 * switches that are off by default, as CLAUDE.md asks for anything new that could skip or resize a live trade).
 *
 *  - [weekly]: the weekly loss stop (R7). Once the account's week (Monday to Sunday, so a Sunday special session counts in its
 *    own week) is down by the limit (Rs 20,000 on paper), no new automatic entry for the rest of that week.
 *  - [riskCap]: the 2% per-trade risk cap (R1, Aziz): rupees at risk to the stop (lots x lot x entry x stop distance, plus the
 *    round trip's charges) at most 2% of the account. More than one lot over the cap is sized DOWN to what fits; one lot over it
 *    is skipped. Never sizes up.
 *  - [opposite]: opposite bets between arms (R5). A later automatic entry on an index that another arm already holds the
 *    other way (a call against a put on the same underlying) is refused. The same arm's own two sides are its own rule.
 *  - [spread]: the bid-ask spread check (E2, not yet researched): what a 0.3% half-spread gate WOULD say. Shadow everywhere.
 *  - [cap]: research h14's capped marketable limit (+0.5% over the signal's price, worked [CAP_WAIT_MIN] minutes, never chasing).
 *  - [entryIv]: the implied volatility of an entry (V1): recorded only, never a filter.
 *
 * Every refusal starts with the rule's own words, so the would-be rows and the diagnostics say which rule it was. Pure: no
 * clock, no storage, no network.
 */
object BossRisk {
    // ---- the weekly loss stop ----------------------------------------------------------------------------------------------

    /** The paper weekly loss stop's default (Boss's decision, 25 Oct): Rs 20,000 a week. */
    const val PAPER_WEEKLY_DEFAULT = 20_000.0
    /** The choices on the Account guard page (0 = off). */
    val WEEKLY_CHOICES = listOf(10_000.0, 20_000.0, 30_000.0, 0.0)

    /** The Monday that starts [day]'s week. */
    fun weekStart(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

    /** One day's net after charges (paper or Zerodha), as [AccountStop.Day]. */
    data class Week(val start: LocalDate, val net: Double, val days: Int, val limit: Double, val reached: Boolean)

    /**
     * [today]'s week over [days] (net after charges; a day not finite is left out): its net, the days counted, and whether it is
     * down by [limit] or more ([limit] 0 or less: off, never reached). [reachedOn]: the day it was first reached, kept by the
     * app - once reached in a week it stays reached until that week ends, so a recovery later in the week never lets the bots
     * back in.
     */
    fun weekly(days: List<AccountStop.Day>, today: LocalDate, limit: Double, reachedOn: LocalDate? = null): Week {
        val start = weekStart(today)
        val mine = days.filter { !it.day.isBefore(start) && !it.day.isAfter(today) && it.net.isFinite() }
        val net = Math.round(mine.sumOf { it.net } * 100) / 100.0
        val on = limit > 0 && limit.isFinite()
        val latched = reachedOn != null && weekStart(reachedOn) == start
        return Week(start, net, mine.size, limit, on && (net <= -limit || latched))
    }

    /** The refusal while [w] is reached, else null. */
    fun weeklyRefusal(w: Week): String? = if (!w.reached) null else
        "weekly loss stop: this week ${rs(w.net)} reached the ${rs(-w.limit)} weekly limit; no new entries until next Monday " +
            "(open positions keep their exits)"

    // ---- the 2% per-trade risk cap ----------------------------------------------------------------------------------------

    /** Aziz's 2%: the most of the account one trade may risk to its stop. */
    const val RISK_PCT = 0.02
    /** Most arms' premium stop (the app audit, R1): used when an arm's own stop is not known. */
    const val DEFAULT_STOP = 0.15
    /** A round trip's charges per lot, estimated (brokerage both ways, STT, exchange charges, GST): rupees. */
    const val CHARGES_PER_LOT = 60.0

    /**
     * How far below the entry an arm's stop sits, as a share of the premium, by the arm's name ([owner]): its own known stop, else
     * [DEFAULT_STOP]. An arm with no stop below the fill risks the whole premium (1.0). [stop]: the stop price itself, when the
     * order carries one (it wins). A share outside (0, 1] is read as the whole premium.
     */
    fun stopShare(owner: String?, entry: Double, stop: Double? = null): Double {
        if (stop != null && stop.isFinite() && entry > 0 && stop < entry && stop >= 0) return ((entry - stop) / entry).coerceIn(0.0, 1.0)
        val o = owner.orEmpty().lowercase(Locale.ENGLISH)
        val share = when {
            // Night (R3): no stop at all (held overnight, sold at 09:16).
            o.startsWith("night") -> 1.0
            // The Hero Rs 1,000 ticket: its 25% trail from the fill (25 Oct, R55's cell) is also its stop.
            o.contains("ticket") -> 0.25
            // FT3: a -80% resting stop.
            o.startsWith("ft3") -> 0.80
            // Hero (expiry): Rs 1-5 options to 15:05.
            o.startsWith("hero") -> 1.0
            else -> DEFAULT_STOP
        }
        return if (share > 0 && share <= 1) share else 1.0
    }

    /** The cap's verdict: [lots] allowed (0: skip), the rupee [riskPerLot], the [cap] in rupees, and [why] when it changed anything. */
    data class Cap(val lots: Int, val asked: Int, val riskPerLot: Double, val cap: Double, val why: String?) {
        val skipped: Boolean get() = lots == 0 && asked > 0
        val sizedDown: Boolean get() = lots in 1 until asked
    }

    /**
     * The 2% cap for [lots] of a contract with [lotSize] units bought at [entry], its stop [stopShare] below, on an account of
     * [account] rupees ([pct] of it at most; [chargesPerLot] for the round trip). Risk per lot = lot x entry x stop share +
     * charges. Unknown inputs (no price, no lot, no account, a share that is not a number): the entry goes as asked (the rule
     * only refuses what it can see). Never more lots than asked.
     */
    fun riskCap(lots: Int, lotSize: Int, entry: Double, stopShare: Double, account: Double, pct: Double = RISK_PCT,
                chargesPerLot: Double = CHARGES_PER_LOT): Cap {
        if (lots <= 0 || lotSize <= 0 || !(entry > 0) || !(account > 0) || !stopShare.isFinite() || !(pct > 0))
            return Cap(lots.coerceAtLeast(0), lots.coerceAtLeast(0), Double.NaN, Double.NaN, null)
        val perLot = lotSize * entry * stopShare.coerceIn(0.0, 1.0) + chargesPerLot.coerceAtLeast(0.0)
        val cap = account * pct
        val fits = if (perLot <= 0) lots else floor((cap + 1e-6) / perLot).toInt().coerceIn(0, lots)
        val why = when {
            fits >= lots -> null
            fits == 0 -> "2% risk cap: 1 lot risks ${rs(perLot)} to its stop, over 2% of the account (${rs(cap)}); skipped"
            else -> "2% risk cap: $lots lots risk ${rs(perLot * lots)} to their stop, over 2% of the account (${rs(cap)}); " +
                "sized down to $fits ${if (fits == 1) "lot" else "lots"} (${rs(perLot * fits)})"
        }
        return Cap(fits, lots, perLot, cap, why)
    }

    // ---- opposite bets between arms ----------------------------------------------------------------------------------------

    /** One automatic entry open now: its arm ([owner]), the index it is on and the way it bets (+1 a call, -1 a put). */
    data class Bet(val owner: String, val underlying: String, val dir: Int, val symbol: String)

    /**
     * Why [owner]'s new entry betting [dir] on [underlying] may not open, or null: another arm's open entry on the same index bets
     * the other way. Arms are told apart by name; the same arm's own entries never block it (its own rule decides its two sides).
     */
    fun opposite(open: List<Bet>, owner: String, underlying: String, dir: Int): String? {
        if (dir == 0) return null
        val u = underlying.uppercase(Locale.ENGLISH)
        val against = open.filter { it.dir == -dir && it.underlying.uppercase(Locale.ENGLISH) == u && it.owner != owner }
        if (against.isEmpty()) return null
        val first = against.first()
        return "opposite bets: ${first.owner} already holds a ${if (dir > 0) "put" else "call"} on $u (${first.symbol})" +
            (if (against.size > 1) " and ${against.size - 1} more" else "") + "; this ${if (dir > 0) "call" else "put"} would bet the other way"
    }

    // ---- the bid-ask spread check (shadow) --------------------------------------------------------------------------------

    /** The audit's suggestion (E2): worry above a 0.3% half-spread. Not researched yet: shadow only. */
    const val MAX_HALF_SPREAD = 0.003

    /** The half-spread as a share of the mid, or null when the book is not two-sided (no bid, no ask, or crossed). */
    fun halfSpread(bid: Double?, ask: Double?): Double? {
        if (bid == null || ask == null || !(bid > 0) || !(ask > 0) || ask < bid) return null
        val mid = (bid + ask) / 2
        return (ask - bid) / 2 / mid
    }

    /** What the spread check WOULD say: null to buy, else why it would skip (no two-sided book, or the half-spread too wide). */
    fun spread(bid: Double?, ask: Double?, max: Double = MAX_HALF_SPREAD): String? {
        val h = halfSpread(bid, ask) ?: return "spread check: no two-sided book (bid ${num(bid)}, ask ${num(ask)})"
        return if (h > max + 1e-12) "spread check: half-spread %.2f%% over %.2f%% (bid %s, ask %s)".format(Locale.ENGLISH, h * 100, max * 100, num(bid), num(ask))
        else null
    }

    // ---- research h14's capped marketable limit ---------------------------------------------------------------------------

    /** h14: the limit sits 0.5% over the signal's price. */
    const val CAP_PCT = 0.005
    /** h14: worked for 3 minutes, never moved (never chasing); unfilled by then, the entry is let go. */
    const val CAP_WAIT_MIN = 3L
    const val TICK = 0.05

    /** The capped limit for a signal priced [ref]: [ref] + 0.5%, down to the tick (never above the cap); null without a price. */
    fun cap(ref: Double?, pct: Double = CAP_PCT): Double? {
        if (ref == null || !(ref > 0) || !ref.isFinite()) return null
        val raw = ref * (1 + pct)
        val ticks = floor(raw / TICK + 1e-9)
        return Math.round(ticks * TICK * 100) / 100.0
    }

    /** Whether [price] (the ask, else the last) may be bought under [cap]. Unknown price: no. */
    fun underCap(price: Double?, cap: Double): Boolean = price != null && price > 0 && price <= cap + 1e-9

    // ---- implied volatility at entry (record only) -------------------------------------------------------------------------

    /**
     * The implied volatility (a decimal, 0.18 = 18%) of a [right] ("CE"/"PE") option at [strike] bought at [premium] with the
     * underlying at [underlying] and [years] to expiry (Black-76 on the underlying as the forward, no rate: the screens' basis),
     * or null when it cannot be solved (a price under intrinsic, no time left, a missing input).
     */
    fun entryIv(premium: Double?, underlying: Double?, strike: Double?, years: Double?, right: String): Double? {
        if (premium == null || underlying == null || strike == null || years == null) return null
        if (!(premium > 0) || !(underlying > 0) || !(strike > 0) || !(years > 0)) return null
        val type = when (right.uppercase(Locale.ENGLISH)) { "CE" -> OptionType.CE; "PE" -> OptionType.PE; else -> return null }
        return OptionMath.impliedVol(premium, type, underlying, strike, years)?.takeIf { it.isFinite() && it > 1e-5 && it < 4.99 }
    }

    /** A Zerodha option symbol's parts (for a live entry's IV): the underlying, the expiry, the strike and "CE"/"PE". */
    data class KiteOption(val underlying: String, val expiry: LocalDate, val strike: Double, val right: String)

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /**
     * Zerodha's option trading symbol read into its parts: the weekly form "NIFTY26O0624500CE" (yy, the month 1-9 / O / N / D,
     * dd) or the monthly form "BANKNIFTY26OCT56000CE", whose expiry day only the day's contract list knows ([monthly]: the
     * underlying, year and month to that month's expiry; null leaves the symbol unread). Null for anything else (a future, a stock).
     */
    fun kiteOption(symbol: String, monthly: (String, Int, Int) -> LocalDate? = { _, _, _ -> null }): KiteOption? = runCatching {
        val s = symbol.trim().uppercase(Locale.ENGLISH)
        Regex("^([A-Z]+)(\\d{2})([1-9OND])(\\d{2})(\\d+(?:\\.\\d+)?)(CE|PE)$").matchEntire(s)?.let { m ->
            val (u, yy, mo, dd, k, r) = m.destructured
            val month = when (mo) { "O" -> 10; "N" -> 11; "D" -> 12; else -> mo.toInt() }
            return@runCatching KiteOption(u, LocalDate.of(2000 + yy.toInt(), month, dd.toInt()), k.toDouble(), r)
        }
        Regex("^([A-Z]+)(\\d{2})([A-Z]{3})(\\d+(?:\\.\\d+)?)(CE|PE)$").matchEntire(s)?.let { m ->
            val (u, yy, mon, k, r) = m.destructured
            val month = MONTHS.indexOf(mon) + 1
            if (month == 0) return@runCatching null
            val e = monthly(u, 2000 + yy.toInt(), month) ?: return@runCatching null
            return@runCatching KiteOption(u, e, k.toDouble(), r)
        }
        null
    }.getOrNull()

    // ---- words -------------------------------------------------------------------------------------------------------------

    fun rs(x: Double): String = (if (x < 0) "-Rs " else "Rs ") + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun num(x: Double?): String = x?.takeIf { it.isFinite() }?.let { "%.2f".format(Locale.ENGLISH, it) } ?: "none"
}
