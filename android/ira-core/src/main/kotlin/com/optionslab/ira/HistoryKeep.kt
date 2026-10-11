package com.optionslab.ira

import java.time.LocalDate
import kotlin.math.abs

/**
 * What the history keeper ([HistoryVault]) fetches after the close, and when (TODO S5). Pure: the app runs it.
 *
 *  - [due]: after [START_MINUTE] (15:45) on a trading day, and on any later day for the days still missing;
 *  - [plan]: the (day, family) pieces not yet kept for the last [LOOKBACK] trading days, today first, then older ones
 *    (Zerodha's and the public feed's 1-minute history still serve them: a phone that was off catches up). MCX's day ends at
 *    23:30, so today's "mcx" waits for [MCX_MINUTE];
 *  - [strikes]: the strikes the app watched - ATM +/-[STRIKES_EACH_SIDE] around the close;
 *  - [Budget]: one run's wall-clock budget; the run stops between pieces when it is spent and resumes next time.
 */
object HistoryKeep {
    const val START_MINUTE = 15 * 60 + 45
    const val MCX_MINUTE = 23 * 60 + 35
    const val LOOKBACK = 5
    const val STRIKES_EACH_SIDE = 5
    /** One run's budget: 4 minutes of wall clock, then it yields to the next run. */
    const val BUDGET_MS = 4 * 60_000L
    /** The indices kept (the six the app watches; India VIX as Zerodha and the chart name it). */
    val INDICES = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "INDIAVIX")
    /** The indices the arms trade, whose options and chains are kept. */
    val TRADED = listOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")

    data class Piece(val day: LocalDate, val family: String)

    /** Whether a run is due now: a trading day after 15:45, or a day after one (catching up). */
    fun due(minute: Int, tradingToday: Boolean): Boolean = !tradingToday || minute >= START_MINUTE

    /** The families of one day, in the order they are fetched (indices first: the cheapest and the most read). */
    fun families(traded: List<String> = TRADED): List<String> =
        listOf(HistoryVault.Family.INDEX, HistoryVault.Family.FUTURES) +
            traded.flatMap { listOf(HistoryVault.Family.options(it), HistoryVault.Family.chain(it)) } + HistoryVault.Family.MCX

    /**
     * The pieces to fetch: of [tradingDays] (the recent trading days up to today, any order), the last [LOOKBACK], newest
     * first, each family not yet kept ([has]) and not given up on ([tried]: fetched before and found empty). Today's "mcx"
     * only after [MCX_MINUTE]; nothing of today before [START_MINUTE].
     */
    fun plan(today: LocalDate, minute: Int, tradingDays: List<LocalDate>, has: (String, LocalDate) -> Boolean,
             tried: (String, LocalDate) -> Boolean = { _, _ -> false }, traded: List<String> = TRADED): List<Piece> {
        val days = tradingDays.filter { !it.isAfter(today) }.distinct().sortedDescending().take(LOOKBACK)
        val out = ArrayList<Piece>()
        for (d in days) {
            if (d == today && minute < START_MINUTE) continue
            for (f in families(traded)) {
                if (d == today && f == HistoryVault.Family.MCX && minute < MCX_MINUTE) continue
                if (has(f, d) || tried(f, d)) continue
                out += Piece(d, f)
            }
        }
        return out
    }

    /** The strikes ATM +/-[each] of [listed] (any order) around [spot]: the nearest strike and [each] either side. */
    fun strikes(listed: Collection<Double>, spot: Double, each: Int = STRIKES_EACH_SIDE): List<Double> {
        val s = listed.distinct().sorted()
        if (s.isEmpty() || !(spot > 0)) return emptyList()
        val atm = s.indices.minByOrNull { abs(s[it] - spot) } ?: return emptyList()
        return s.subList((atm - each).coerceAtLeast(0), (atm + each + 1).coerceAtMost(s.size))
    }

    /** Runs a piece with some failed reads is tried again before what it has is kept as the day's (G9-7). */
    const val PARTIAL_TRIES = 3

    /**
     * Whether a piece read with [failed] failed reads may be written now, on its [tries]th attempt: whole (no failure) at once;
     * a partial one only after [PARTIAL_TRIES] attempts - once written, [plan] never fetches it again, so a passing timeout must
     * not leave a series out for good.
     */
    fun keepPartial(failed: Int, tries: Int): Boolean = failed <= 0 || tries >= PARTIAL_TRIES

    /**
     * The option's bar paired with the index's last bar ([indexAt], its start in epoch seconds): the last at or before it. F&O
     * trades to 15:40 and the index closes at 15:30, so the option's own last bar is ten minutes after the spot an IV is worked
     * from (G9-5). Null when the option has no bar by then.
     */
    fun pairedBar(option: List<HistoryCodec.Bar>, indexAt: Long): HistoryCodec.Bar? = option.lastOrNull { it.epochSec <= indexAt }

    /**
     * Whether a past [day]'s nearest expiry may be missing from today's contract list ([listed]: the expiries listed now, all
     * on or after today): the list only has live contracts, so an expiry between [day] and [today] is gone. Worked out from the
     * listed cadence (the gap between the two nearest): an expiry before the nearest listed one, on or after [day], was then
     * the nearest (G9-8). True: the two nearest of that day cannot be kept from today's list.
     */
    fun missesNearest(day: LocalDate, today: LocalDate, listed: List<LocalDate>): Boolean {
        if (!day.isBefore(today)) return false
        val e = listed.filter { !it.isBefore(today) }.distinct().sorted()
        if (e.isEmpty()) return false
        // Before the nearest listed expiry: one cadence back (one listed: a month). A holiday moves an expiry earlier, never
        // later, so the real one is on or before this estimate.
        val gap = if (e.size >= 2) java.time.temporal.ChronoUnit.DAYS.between(e[0], e[1]) else 31L
        val previous = e[0].minusDays(gap)
        return !day.isAfter(previous)
    }

    /** One run's budget, from [startMs] for [maxMs]; the clock is given. */
    class Budget(private val startMs: Long, private val maxMs: Long = BUDGET_MS) {
        fun spent(nowMs: Long): Boolean = nowMs - startMs !in 0 until maxMs
    }
}
