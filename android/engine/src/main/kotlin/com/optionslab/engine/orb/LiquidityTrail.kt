package com.optionslab.engine.orb

/**
 * Boss's give-back trail for Liquidity (13 Oct 2026: "if the trade is down Rs 500 from its highest point, sell it"),
 * as research R19b pre-registered it (variant A1): once the open P&L per lot has reached +[GIVE_BACK_RS], the stop rests
 * [GIVE_BACK_RS] per lot under the best price since the entry. Per lot, so the number of lots never changes it: Rs 500 is
 * 500 / lot option points (4.17 at MIDCPNIFTY's 120, 16.67 at BANKNIFTY's 30, 14.29 at 35).
 *
 * R19b FAILED its gates (research/HUNT_R19B_LIQUIDITY_TRAIL.md, 13 Oct): on both BANKNIFTY books it cut 12 of the 13 big
 * winners and lost Rs 10.0k (15m) and Rs 19.2k (5m) against the plain rules in the locked holdout, worse than random early exits. So it is NOT added to
 * the arm: it is only Boss's own opt-in paper switch, off by default, labelled "failed research, your choice" ([NOTE]). It
 * never touches a live trade.
 *
 * Like [ProfitLock.raise] for the ORB arms it MOVES THE RESTING STOP, up only, so it works with the app closed; the best
 * price is every stream tick or the 1-minute candles' highs ([ProfitLock.nextPeak]). It only ever adds an earlier exit:
 * the -15% stop and every other exit of the arm stay as they are. Pure: no clock, no network, no orders.
 *
 * 13 Oct, later (research R19c, HUNT_R19C_CONSISTENCY.md): Boss's rule became "once the open P&L reaches +Rs 1,000 a lot,
 * sell when it falls Rs 500 below its highest point" ([ACTIVATE_RS]), so a trailed trade never keeps less than +Rs 500 a
 * lot. R19c FAILED too (Liquidity 15m -Rs 6.9k against +Rs 23.4k on plain rules, 5m +Rs 6.7k against +Rs 27.3k, in the
 * locked holdout Apr 2025 - Feb 2026, worse than random early exits), so the switch stays their own choice, off by default.
 * A trade carries the activation it entered with ([forEntryFrom]); one entered before this (none stored) keeps +Rs 500.
 */
object LiquidityTrail {
    /** The give-back, rupees per lot (Boss's number). */
    const val GIVE_BACK_RS = 500.0
    /** Where the trail starts, rupees per lot (Boss's rule of 13 Oct, R19c): +Rs 1,000, so the stop is never under +Rs 500. */
    const val ACTIVATE_RS = 1000.0
    /** The exit's reason when the trail sells or its stop fills. */
    const val WHY = "give_back_trail"
    /** The two labels a Liquidity paper trade carries, so the 26 Oct decision can compare the plain-rules record with this one. */
    const val PLAIN = "plain rules"
    const val TRAILED = "with ₹500 trail"
    /** What the switch says next to it. */
    const val NOTE = "failed research, your choice"
    private const val EPS = 1e-9

    /** [rupeesPerLot] as option points for a contract of [lot]; null when either is missing or not positive. */
    fun points(rupeesPerLot: Double?, lot: Int?): Double? {
        if (rupeesPerLot == null || !rupeesPerLot.isFinite() || rupeesPerLot <= 0 || lot == null || lot <= 0) return null
        return rupeesPerLot / lot
    }

    /**
     * The trail's stop for a buy at [entry] whose best price so far is [peak] ([lot]: the contract's lot; [rupeesPerLot]: the
     * give-back, null: no trail), on the [tick] rounded up, or null: no trail, a missing input, or the best not yet
     * [activateRs] per lot above the entry ([activateRs] null or broken: the give-back itself, R19b's first rule). Never above
     * the best price.
     */
    fun level(entry: Double, peak: Double?, lot: Int?, rupeesPerLot: Double?, tick: Double = OrbRules.TICK, activateRs: Double? = null): Double? {
        val pts = points(rupeesPerLot, lot) ?: return null
        val from = points(activateRs, lot) ?: pts
        if (!(entry > 0) || peak == null || !peak.isFinite() || peak - entry < from - EPS) return null
        return minOf(ProfitLock.onTick(peak - pts, tick), peak)
    }

    /** True when [ltp] is at or below the level the best price BEFORE it ([peakBefore]) earned: a new best counts from the next look. */
    fun exits(entry: Double, peakBefore: Double?, ltp: Double, lot: Int?, rupeesPerLot: Double?, activateRs: Double? = null): Boolean =
        level(entry, peakBefore, lot, rupeesPerLot, activateRs = activateRs)?.let { ltp <= it + EPS } == true

    /**
     * The trigger the resting sell stop moves UP to, as [ProfitLock.raise]: null when it must not move - no level yet, not
     * above the stop resting now ([current]; a stop is never moved down), or not below the price now ([ltp]; the app's own
     * check sells that instead).
     */
    fun raise(entry: Double, peak: Double?, lot: Int?, rupeesPerLot: Double?, current: Double?, ltp: Double,
              tick: Double = OrbRules.TICK, activateRs: Double? = null): Double? {
        val l = level(entry, peak, lot, rupeesPerLot, tick, activateRs) ?: return null
        if (current != null && l <= current + EPS) return null
        return l.takeIf { it < ltp - EPS }
    }

    /** Where the stop belongs: the higher of its own ([base]; null: none) and the trail's level; null with neither. */
    fun restingStop(entry: Double, base: Double?, peak: Double?, lot: Int?, rupeesPerLot: Double?, tick: Double = OrbRules.TICK,
                    activateRs: Double? = null): Double? =
        listOfNotNull(base, level(entry, peak, lot, rupeesPerLot, tick, activateRs)).maxOrNull()

    /** A paper Liquidity trade's label: [TRAILED] when it was entered under the trail ([rupeesPerLot] not null), else [PLAIN]. */
    fun label(rupeesPerLot: Double?): String = if (rupeesPerLot != null) TRAILED else PLAIN

    /**
     * The give-back a NEW entry carries: [GIVE_BACK_RS] only on PAPER ([live] false), on a Liquidity book ([arm]), with Boss's
     * switch on ([switchOn]); null otherwise. A live entry never carries it, whatever the switch says.
     */
    fun forEntry(arm: Arm, live: Boolean, switchOn: Boolean): Double? = if (switchOn && !live && arm.liquidity) GIVE_BACK_RS else null

    /** Where a NEW entry's trail starts: [ACTIVATE_RS] when it carries one ([giveBack] from [forEntry]), else null (no trail). */
    fun forEntryFrom(giveBack: Double?): Double? = if (giveBack != null) ACTIVATE_RS else null
}
