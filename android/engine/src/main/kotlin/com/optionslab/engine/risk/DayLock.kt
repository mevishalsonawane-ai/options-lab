package com.optionslab.engine.risk

import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * The account day lock (08 Oct 2026, research/PROFIT_LOCK_8OCT.md fix 5): Boss's comfort rule, set high and loose. Once an
 * account's day P&L (realised plus open, the paper account's after charges, Zerodha's its own) reaches +[limit] rupees, no
 * NEW automatic entry is made in that account for the rest of the day - every ORB arm, Liquidity, Pine, Solo and Jarvis's
 * trades. Positions already open keep their own exits; nothing is sold by it. The paper and the Zerodha accounts are locked
 * separately. It stays locked for the day once reached, even if the P&L falls back (that is its point).
 *
 * It can only ever reduce trading: 0 switches it off, any other amount only stops entries. The amount is a Bot settings
 * value on this phone alone - never changed by voice or by a restored backup (the "g." settings are private,
 * [com.optionslab.ira.Upkeep]), as the PIN, the locks, Live and the guard.
 *
 * Research (5 years of the arms' replays): such a lock barely changes the long run - it trades the best days for comfort
 * on others - so it is set high (+Rs 8,000 by default) and only stops new entries. Pure: no clock, no storage.
 */
object DayLock {
    /** The default amount: +Rs 8,000. */
    const val DEFAULT_RUPEES = 8_000.0

    /** The amounts Bot settings offers (0 = off). */
    val CHOICES: List<Double> = listOf(5_000.0, 8_000.0, 10_000.0, 15_000.0, 0.0)

    /** The day an account reached its lock: when ([at], IST), at what P&L ([pnl]) and against which amount ([limit]). */
    data class Reached(val day: LocalDate, val at: LocalTime, val pnl: Double, val limit: Double)

    /** A saved amount made safe: 0 (off) or a positive amount; anything broken (NaN, infinite, negative) is the default. */
    fun clean(limit: Double): Double = if (limit.isFinite() && limit >= 0) limit else DEFAULT_RUPEES

    /**
     * The lock after one more look at the account: [prev] when it was already reached on [day] (it holds for the day),
     * else a new [Reached] when the day's [pnl] is at or above the amount [limit] (on), else null. An unknown P&L (null,
     * NaN) never reaches it. An earlier day's record is forgotten.
     */
    fun next(prev: Reached?, day: LocalDate, at: LocalTime, limit: Double, pnl: Double?): Reached? {
        if (prev != null && prev.day == day) return prev
        val l = clean(limit)
        if (l <= 0 || pnl == null || !pnl.isFinite() || pnl < l) return null
        return Reached(day, at.withSecond(0).withNano(0), pnl, l)
    }

    /** True when [r] locks new entries on [day] with the amount set now ([limit]; 0 = off lifts it). */
    fun active(r: Reached?, day: LocalDate, limit: Double): Boolean = r != null && r.day == day && clean(limit) > 0

    /** "Day lock: reached +Rs 8,000 at 14:11, no new entries" ([account]: "paper", "Zerodha"; null: not said). */
    fun line(r: Reached, account: String? = null): String =
        "Day lock" + (account?.let { " ($it)" } ?: "") + ": reached +Rs ${rupees(r.limit)} at %02d:%02d, no new entries".format(Locale.ENGLISH, r.at.hour, r.at.minute)

    /** An automatic entry's refusal while [r] locks the [account]: the line, and that open positions keep their exits. */
    fun refusal(r: Reached, account: String): String =
        line(r, account) + " today (open positions keep their own exits)"

    /** The day lock in Bot settings' words: "+Rs 8,000" or "off". */
    fun describe(limit: Double): String = clean(limit).let { if (it <= 0) "off" else "+Rs ${rupees(it)}" }

    /** Kept as one line: "2026-10-08|14:11|8001.5|8000.0". */
    fun encode(r: Reached): String = "${r.day}|%02d:%02d|${r.pnl}|${r.limit}".format(Locale.ENGLISH, r.at.hour, r.at.minute)

    /** [encode]'s line read back; null for none or anything broken. */
    fun decode(s: String?): Reached? = runCatching {
        val p = s!!.split('|')
        require(p.size == 4)
        val pnl = p[2].toDouble(); val limit = p[3].toDouble()
        require(pnl.isFinite() && limit.isFinite() && limit > 0)
        Reached(LocalDate.parse(p[0]), LocalTime.parse(p[1]), pnl, limit)
    }.getOrNull()

    private fun rupees(x: Double): String = "%,.0f".format(Locale.ENGLISH, x)
}
