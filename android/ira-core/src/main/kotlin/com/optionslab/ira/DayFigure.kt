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
     * always did - nothing is migrated).
     */
    data class Kept(val pnl: Double, val trades: Int, val charges: Double = 0.0)

    /**
     * [next] with the day's charges: [charges] < 0 keeps the charges already [stored] (0 when none). Null when the entry
     * is exactly what is [stored] (nothing to write).
     */
    fun next(stored: Kept?, pnl: Double, trades: Int, charges: Double): Kept? {
        val c = if (charges >= 0 && charges.isFinite()) paise(charges) else stored?.charges ?: 0.0
        val entry = Kept(paise(pnl), if (trades >= 0) trades else stored?.trades ?: 0, c)
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
