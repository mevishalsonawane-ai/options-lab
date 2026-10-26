package com.optionslab.engine.orb

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 26 Oct (R105 lead 6): the exchanges' expiry weekdays with the holiday shift, for the places that cannot read the listed
 * contracts (past days' "was it an expiry day" labels). From 1 Sep 2025: NIFTY weekly on Tuesday, SENSEX weekly on Thursday
 * (BSE), BANKNIFTY / FINNIFTY / MIDCPNIFTY monthly only, on the month's last Tuesday. When that day is not a trading day (a
 * holiday, or a weekend) the expiry moves to the PREVIOUS trading day, never the next: Tue 20 Oct 2026 (Dussehra) moved
 * NIFTY's weekly expiry to Mon 19 Oct, and Wed 21 Oct was not an expiry day.
 *
 * The arms' own "is today its expiry day" never uses this: they read the exchange's listed expiries
 * ([HeroRules.isExpiryDay]), which already carry the shift. Pure (the caller passes the trading-day calendar).
 */
object ExpiryRule {
    /** At most this many days are walked back over holidays and a weekend (never an endless loop on a broken calendar). */
    private const val MAX_BACK = 10

    /** The weekday rule's own day for the expiry on or after [d] for [und] (before any holiday shift); null: not an F&O index. */
    fun nominalOnOrAfter(und: String, d: LocalDate): LocalDate? = when (und) {
        "NIFTY" -> d.with(TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY))
        "SENSEX" -> d.with(TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY))
        "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY" -> {
            val here = d.with(TemporalAdjusters.lastInMonth(DayOfWeek.TUESDAY))
            if (!here.isBefore(d)) here else d.plusMonths(1).with(TemporalAdjusters.lastInMonth(DayOfWeek.TUESDAY))
        }
        else -> null
    }

    /** [nominal] moved back to the nearest trading day on or before it ([trading]); null when none within [MAX_BACK] days. */
    fun shifted(nominal: LocalDate, trading: (LocalDate) -> Boolean): LocalDate? {
        var x = nominal
        repeat(MAX_BACK + 1) {
            if (runCatching { trading(x) }.getOrDefault(true)) return x
            x = x.minusDays(1)
        }
        return null
    }

    /**
     * Whether [d] is [und]'s expiry day by the rule: the expiry the rule sets on or after [d] lands on [d] once moved back over
     * the days that are not trading days. A day that is not a trading day itself is never an expiry day.
     */
    fun isExpiry(und: String, d: LocalDate, trading: (LocalDate) -> Boolean): Boolean {
        if (!runCatching { trading(d) }.getOrDefault(true)) return false
        val first = nominalOnOrAfter(und, d) ?: return false
        return shifted(first, trading) == d
    }
}
