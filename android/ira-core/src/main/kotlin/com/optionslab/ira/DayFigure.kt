package com.optionslab.ira

/**
 * What the P&L calendar's day figure and the day's P&L curve keep after a reading, and whether anything changed at all.
 *
 * Speed, round 5: every refresh of the paper book (every 2 s on Zerodha's stream) and of the Zerodha account (every
 * 15 s, and after each order) wrote both to the settings vault on the refresh's own thread - a Keystore encryption of
 * the whole settings file and two disk syncs each - before the new book reached the screen, even when the figure was
 * the same as the one already kept. A reading that changes nothing is now not written at all; one that does is written
 * in the background. Pure, so the rule is tested on the JVM.
 */
object DayFigure {
    /** Rupees to the paisa, as the calendar always kept them. */
    fun paise(x: Double): Double = Math.round(x * 100) / 100.0

    /**
     * The day's entry (P&L to the paisa, trade count) after a reading of [pnl] and [trades] ([trades] < 0 keeps the
     * count already [stored], 0 when none), or null when it is exactly what is [stored] (nothing to write).
     */
    fun next(stored: Pair<Double, Int>?, pnl: Double, trades: Int): Pair<Double, Int>? {
        val entry = paise(pnl) to (if (trades >= 0) trades else stored?.second ?: 0)
        return if (entry == stored) null else entry
    }

    /**
     * A day's kept entry with its charges (Boss, 5 Oct: the P&L is shown before charges, the charges on a small line):
     * [pnl] the figure as the account always kept it (paper: after charges; Zerodha: its own m2m, before them), [trades]
     * the count, [charges] that day's charges (0 when not known: an entry kept before charges were, which then shows as it
     * always did - nothing is migrated), [exact] when they are Zerodha's own contract-note figure for every order of the
     * day rather than the estimate from its trades (usefulness, round 35: the calendar then says "Charges ₹X").
     */
    data class Kept(val pnl: Double, val trades: Int, val charges: Double = 0.0, val exact: Boolean = false)

    /**
     * ANR fix (9 Oct): [next] only moved the day's figure - the same day already kept, the same trade count, the same charges
     * as [stored] - so it may wait in memory for a minute before it is written (a figure that moves with every price pass
     * no longer re-encrypts the settings vault each pass). A first reading of the day, a new trade or new charges: false
     * (written at once, as before).
     */
    fun marksOnly(stored: Kept?, next: Kept): Boolean =
        stored != null && next.trades == stored.trades && next.charges == stored.charges && next.exact == stored.exact

    /**
     * [next] with the day's charges: [charges] < 0 keeps the charges already [stored] (0 when none) with whether they were
     * [Kept.exact]; a new figure is [exact] as said (never for no charges). Null when the entry is exactly what is
     * [stored] (nothing to write).
     */
    fun next(stored: Kept?, pnl: Double, trades: Int, charges: Double, exact: Boolean = false): Kept? {
        val given = charges >= 0 && charges.isFinite()
        val c = if (given) paise(charges) else stored?.charges ?: 0.0
        val isExact = if (given) exact && c > 0 else stored?.exact ?: false
        val entry = Kept(paise(pnl), if (trades >= 0) trades else stored?.trades ?: 0, c, isExact)
        return if (entry == stored) null else entry
    }

    /**
     * The day's curve ((minute, P&L) samples, oldest first, at most [max]) after a reading of [pnl] at [minute]: the
     * latest reading in a minute wins. Null when [points] already hold that minute at that figure (nothing to write).
     */
    fun sample(points: List<Pair<Int, Double>>, minute: Int, pnl: Double, max: Int): List<Pair<Int, Double>>? {
        val v = paise(pnl)
        if (points.any { it.first == minute && it.second == v }) return null
        val kept = points.filter { it.first != minute } + (minute to v)
        return kept.sortedBy { it.first }.takeLast(max)
    }
}
