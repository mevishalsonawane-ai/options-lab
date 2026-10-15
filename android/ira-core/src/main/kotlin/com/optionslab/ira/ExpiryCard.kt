package com.optionslab.ira

import java.time.LocalTime
import java.util.Locale

/**
 * The expiry-day card (TODO I10, 13 Oct 2026; research/EXPIRY_PLAYBOOK.md): on an index's expiry day, how much of the ATM
 * option's 09:20 time value is typically left at this time, that the last hour moves more on expiry days, that max pain is
 * not a forecast (R16), pin risk, and the 15:05 square-off. Every figure carries its sample size. Information only:
 * nothing here places, sizes or exits anything. Pure.
 */
object ExpiryCard {
    const val SOURCE = "research/EXPIRY_PLAYBOOK.md (13 Oct 2026)"

    /** Median share of the 09:20 ATM time value left at each time (%), and how many expiries it is measured on. */
    data class Curve(val measuredOn: String, val expiries: Int, val left: List<Pair<LocalTime, Double>>,
                     val lastHourPct: Double, val otherLastHourPct: Double, val pinPct: Double)

    private fun t(h: Int, m: Int) = LocalTime.of(h, m)

    val NIFTY = Curve("NIFTY", 53, listOf(t(9, 20) to 100.0, t(10, 0) to 61.0, t(11, 0) to 49.0, t(12, 0) to 35.0, t(13, 0) to 24.0,
        t(14, 0) to 11.0, t(14, 30) to 6.0, t(15, 0) to 2.0, t(15, 20) to 0.0), 0.15, 0.10, 11.0)
    val BANKNIFTY = Curve("BANKNIFTY", 11, listOf(t(9, 20) to 100.0, t(10, 0) to 62.0, t(11, 0) to 54.0, t(12, 0) to 50.0, t(13, 0) to 43.0,
        t(14, 0) to 19.0, t(14, 30) to 18.0, t(15, 0) to 4.0, t(15, 20) to 4.0), 0.33, 0.12, 9.0)

    /** The curve for [index]; FINNIFTY and SENSEX use NIFTY's (said so on the card). */
    fun curve(index: String): Curve = if (index.uppercase(Locale.ENGLISH) == "BANKNIFTY") BANKNIFTY else NIFTY

    /** The median share left at [now], read off the curve (straight line between its points); null before 09:20. */
    fun leftAt(c: Curve, now: LocalTime): Double? {
        if (now.isBefore(c.left.first().first)) return null
        for (i in 1 until c.left.size) {
            val (a, va) = c.left[i - 1]
            val (b, vb) = c.left[i]
            if (!now.isAfter(b)) {
                val f = (now.toSecondOfDay() - a.toSecondOfDay()).toDouble() / (b.toSecondOfDay() - a.toSecondOfDay())
                return va + (vb - va) * f
            }
        }
        return c.left.last().second
    }

    private fun pct(x: Double) = String.format(Locale.ENGLISH, "%.0f%%", x)
    private fun p2(x: Double) = String.format(Locale.ENGLISH, "%.2f%%", x)

    /** The card's lines for [index] expiring today, at [now]. */
    fun lines(index: String, now: LocalTime): List<String> {
        val c = curve(index)
        val on = if (c.measuredOn == index.uppercase(Locale.ENGLISH)) "" else " (measured on ${c.measuredOn})"
        val out = ArrayList<String>()
        val left = leftAt(c, now)
        out += if (left == null) "Time value: an ATM option typically keeps 61% of its 09:20 time value at 10:00, 35% at noon, 11% at 14:00 and almost none by 15:00 " +
            "(median of ${c.expiries} ${c.measuredOn} expiries)$on."
        else "Time value now: an ATM option typically has about ${pct(left)} of its 09:20 time value left at this time; " +
            "${pct(leftAt(c, t(14, 0))!!)} by 14:00 and almost none by 15:00 (median of ${c.expiries} ${c.measuredOn} expiries)$on."
        out += "The last hour moves more on expiry days: median ${p2(c.lastHourPct)} from 14:30 to 15:25 against ${p2(c.otherLastHourPct)} on other days (${c.measuredOn})."
        out += "Max pain is not a forecast: the close sat further from it than from the open (R16 and this re-check)."
        out += "Pin: the close ended within half a strike of the morning's largest-OI strike on ${pct(c.pinPct)} of expiries - rare."
        out += "The expiry square-off runs at 15:05 where it is switched on (Bot settings)."
        return out
    }

    /** The card's title. */
    fun title(indices: List<String>): String = "Expiry day: " + indices.joinToString(", ") + " - context, not a forecast"
}
