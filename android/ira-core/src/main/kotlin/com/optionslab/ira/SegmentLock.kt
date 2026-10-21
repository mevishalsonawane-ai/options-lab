package com.optionslab.ira

import com.optionslab.engine.risk.DayLock
import java.time.LocalDate
import java.util.Locale

/**
 * Commodities' own PAPER day lock and daily loss limit (Boss, 21 Oct: "Yes, give commodities their own day lock").
 *
 * Before, one paper figure decided both: an NSE win at 11:00 (R52 MIDCPNIFTY +Rs 11,514 on 21 Oct) locked paper for the day,
 * and the MCX evening session (17:00-23:30) never traded. With the split on (the default, Boss's choice, logged in the
 * Autopilot change timeline and undone there in one tap):
 *  - the MCX paper arms are judged on the paper MCX positions' day P&L after charges only, against their own amounts;
 *  - the NSE paper lock and loss limit on the rest of the paper day (everything but MCX);
 *  - so MCX results never stop NSE arms, and NSE results never stop MCX arms.
 *
 * What still stops BOTH: Boss's own "stop for today" and the quick-settings tile, the kill switch, the Zerodha (live) loss limit,
 * the account stop rule and every live gate. A loss-limit stop of one segment is never lifted by the other. LIVE is untouched:
 * the Zerodha day lock and live loss limit stay one account; the live entry door refuses on EITHER segment's paper loss stop
 * and on the whole paper day past the paper limit, as before the split ([liveDoorTripped]). Pure: no clock, no storage.
 */
object SegmentLock {
    /** The exchange the MCX arms trade on (the paper book's own name for it). */
    const val MCX = "MCX"

    /** Whether a paper row or trade on [exchange] is a commodity one. */
    fun isMcx(exchange: String?): Boolean = exchange?.trim()?.uppercase(Locale.ENGLISH) == MCX

    /** The paper day after charges, split: [nse] everything but MCX, [mcx] the MCX positions alone. */
    data class Split(val nse: Double, val mcx: Double)

    /**
     * [total]: the paper day after charges (Paper.Snapshot.dayPnl); [rows]: each position row's exchange and day P&L (realised
     * today plus open); [charges]: each of today's trades' exchange and charges. Null when the day is not known.
     */
    fun split(total: Double?, rows: List<Pair<String, Double>>, charges: List<Pair<String, Double>>): Split? {
        if (total == null || !total.isFinite()) return null
        val mcx = rows.filter { isMcx(it.first) }.sumOf { it.second } - charges.filter { isMcx(it.first) }.sumOf { it.second }
        if (!mcx.isFinite()) return null
        return Split(total - mcx, mcx)
    }

    /** Which account's limit stopped the NSE side today: Zerodha's (stops MCX too) or the paper NSE one (NSE only). */
    enum class Trip(val wire: String) {
        /** Zerodha's limit, or [SHARED]: one paper figure for both (the split off, or anything trading live): stops both. */
        LIVE("live"), PAPER("paper"), SHARED("shared");
        companion object { fun of(w: String?): Trip? = entries.firstOrNull { it.wire == w } }
    }

    /** One limit reached: [trip] whose, [who] in words, the day's [pnl] and the [limit]. */
    data class Hit(val trip: Trip, val who: String, val pnl: Double, val limit: Double)

    /** One pass's loss-limit answers: the NSE (or whole) side, the MCX side, and the whole paper day past the paper limit. */
    data class Losses(val nse: Hit?, val mcx: Hit?, val wholePaper: Boolean)

    /**
     * The loss limits on one look ([live] Zerodha's day, [paper] the whole paper day after charges, [split] the paper split; null
     * when not read). [splitOn] false: exactly the shared rule as before (the live limit first, then the whole paper day).
     */
    fun losses(splitOn: Boolean, live: Double?, liveLimit: Double, paper: Double?, paperLimit: Double, split: Split?, mcxLimit: Double): Losses {
        val nsePaper = if (splitOn && split != null) split.nse else paper
        val nse = when {
            live != null && liveLimit > 0 && live <= -liveLimit -> Hit(Trip.LIVE, "Live", live, liveLimit)
            nsePaper != null && paperLimit > 0 && nsePaper <= -paperLimit ->
                if (splitOn) Hit(Trip.PAPER, "Paper NSE", nsePaper, paperLimit) else Hit(Trip.SHARED, "Paper", nsePaper, paperLimit)
            else -> null
        }
        val mcx = if (splitOn && split != null && mcxLimit > 0 && split.mcx <= -mcxLimit) Hit(Trip.PAPER, "Commodities paper", split.mcx, mcxLimit) else null
        val whole = splitOn && paper != null && paperLimit > 0 && paper <= -paperLimit
        return Losses(nse, mcx, whole)
    }

    /**
     * Whether the loss limits split by segment on this pass: the split switched on AND nothing can trade live ([liveActive]: the
     * app in Live, or a live strategy running). While anything can trade live, the one paper figure stops everything, exactly as
     * before the split (it also stops live runs and sells what the bots hold), so LIVE is never weaker.
     */
    fun lossSplit(splitOn: Boolean, liveActive: Boolean): Boolean = splitOn && !liveActive

    /** A stop already made today ([was]) upgraded by a later [hit]: a paper NSE stop becomes one that stops both. */
    fun upgrade(was: Trip?, hit: Trip): Boolean = was == Trip.PAPER && hit != Trip.PAPER

    /** The figures the paper day locks read: (the NSE lock's, the MCX lock's; null: the MCX lock is not checked). */
    fun lockFigures(splitOn: Boolean, paper: Double?, split: Split?): Pair<Double?, Double?> =
        if (!splitOn) paper to null else (split?.nse ?: paper) to split?.mcx

    /**
     * Whether the MCX paper arms make no new entry today (and sell what they hold, as the loss stop does). [stoppedWhy]: the bot's
     * day stop, if any; [bossStop]: Boss (or the tile) stopped today, kept apart so a loss stop made first does not hide it;
     * [nseTripped] / [nseTrip]: the NSE side's loss limit today and whose; [mcxTripped]: the MCX limit today. Unknown counts as
     * the shared rule: a loss stop whose account is not known stops both.
     */
    fun mcxStopped(splitOn: Boolean, stoppedWhy: DayStop.Why?, bossStop: Boolean, nseTripped: Boolean, nseTrip: Trip?, mcxTripped: Boolean): Boolean {
        // Shared (the split switched off): the one paper figure, as before - and an MCX loss stop already made today still holds.
        if (!splitOn) return stoppedWhy != null || nseTripped || bossStop || mcxTripped
        if (mcxTripped || bossStop) return true
        if (stoppedWhy != null && stoppedWhy != DayStop.Why.LOSS) return true
        val nsePaperOnly = nseTrip == Trip.PAPER
        if (stoppedWhy == DayStop.Why.LOSS && !nsePaperOnly) return true
        return nseTripped && !nsePaperOnly
    }

    /**
     * The live entry door's loss stop, never weaker than before the split: the NSE side's (or Zerodha's) stop, the MCX paper
     * stop, or the whole paper day past the paper limit ([wholePaper]) - any of them refuses a bot's live entry.
     */
    fun liveDoorTripped(nseTripped: Boolean, mcxTripped: Boolean, wholePaper: Boolean): Boolean = nseTripped || mcxTripped || wholePaper

    // ---- words ------------------------------------------------------------------------------------------------------------

    private fun rs(x: Double): String = "%,.0f".format(Locale.ENGLISH, kotlin.math.abs(x))
    private fun hm(r: DayLock.Reached): String = "%02d:%02d".format(Locale.ENGLISH, r.at.hour, r.at.minute)

    /** Which paper or live side a day lock line is for. */
    enum class Side { PAPER, PAPER_NSE, ZERODHA, MCX }

    /**
     * Home's, Bot settings' and the diagnostics' line for a reached day lock: "Day lock reached (paper NSE) +Rs 8,000 at 11:00:
     * no new NSE entries today", "Commodities day lock reached +Rs 8,000 at 18:05: no new MCX entries today".
     */
    fun lockLine(r: DayLock.Reached, side: Side): String = when (side) {
        Side.MCX -> "Commodities day lock reached +Rs ${rs(r.limit)} at ${hm(r)}: no new MCX entries today"
        Side.PAPER_NSE -> "Day lock reached (paper NSE) +Rs ${rs(r.limit)} at ${hm(r)}: no new NSE entries today"
        Side.PAPER -> "Day lock reached (paper) +Rs ${rs(r.limit)} at ${hm(r)}: no new entries today"
        Side.ZERODHA -> "Day lock reached (Zerodha) +Rs ${rs(r.limit)} at ${hm(r)}: no new entries today"
    }

    /** An MCX arm's refusal while its day lock holds. */
    fun mcxLockRefusal(r: DayLock.Reached): String = lockLine(r, Side.MCX) + " (open positions keep their own exits)"

    /** The MCX loss limit reached ([r]: its day, time, P&L and limit). */
    fun mcxLossLine(r: DayLock.Reached): String =
        "Commodities daily loss limit reached -Rs ${rs(r.limit)} at ${hm(r)}: no new MCX entries today; the MCX arms sold what they held"

    /** Said beside the day stop's bar while only the NSE paper loss limit stopped the bot (the split on). */
    const val NSE_LOSS_ONLY = "The NSE paper daily loss limit stopped the NSE arms today; the MCX paper arms keep their own limit and trade on."

    /** An MCX arm's refusal while its own loss stop holds. */
    const val MCX_LOSS_REFUSAL = "the commodities daily loss limit was reached today"

    /** Bot settings' and the diagnostics' words while nothing is reached. */
    fun idleLine(splitOn: Boolean, mcxLock: Double, mcxLoss: Double): String = if (!splitOn)
        "Commodities share the paper day lock and loss limit with NSE (one paper figure)."
    else "Commodities keep their own paper day lock (${DayLock.describe(mcxLock)}) and daily loss limit (" +
        (if (mcxLoss > 0) "-Rs ${rs(mcxLoss)}" else "off") + "), on the MCX paper day only."

    /** A loss limit kept as "day|HH:mm|pnl|limit" ([DayLock.encode]'s shape): reached at [at] on [day]. */
    fun lossReached(day: LocalDate, at: java.time.LocalTime, pnl: Double, limit: Double): DayLock.Reached =
        DayLock.Reached(day, at.withSecond(0).withNano(0), pnl, limit)

    /** "2026-10-21|paper": whose limit stopped the NSE side, and on which day. */
    fun encodeTrip(day: LocalDate, t: Trip): String = "$day|${t.wire}"

    /** [encodeTrip] read back for [today]; null for another day or anything broken. */
    fun decodeTrip(s: String?, today: LocalDate): Trip? {
        val p = s?.split('|') ?: return null
        if (p.size != 2 || p[0] != today.toString()) return null
        return Trip.of(p[1])
    }
}
