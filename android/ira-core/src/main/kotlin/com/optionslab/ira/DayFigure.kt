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
