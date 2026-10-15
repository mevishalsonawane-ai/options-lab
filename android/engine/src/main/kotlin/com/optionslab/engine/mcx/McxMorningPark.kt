package com.optionslab.engine.mcx

/**
 * The SILVERM morning call (M4, [McxMorningRules]) switched off on paper (14 Oct 2026, Boss's OK; research R42's
 * pre-registered rule, research/HUNT_R42_SILVERM_SPIKES.md: "switch the M4 paper arm off"). R42 checked whether bad option
 * prices made M4 look good: they did not (no trade touched one), but on the cleaned prices M4 has no proven edge - the
 * holdout made +Rs 336 a trade over 53 trades, yet a random morning minute on the same days did about as well (random-twin
 * p 0.079, needs under 0.05); walk-forward lost Rs 44.6k over 129 trades; the design edge shrank to +Rs 871 a trade over 95.
 *
 * Once, on this update ([MIGRATION], kept in the MCX arms' book), the arm is switched off if it is on. It is a PAPER arm
 * only (the MCX arms never reach Zerodha), so this only lowers paper risk and nothing carries over to live. Switching off
 * only stops NEW entries: a position it already holds is still watched and closed by its own 4-hour exit, the expiry exit or
 * the bot's stop. Nothing is deleted and nothing is armed here, ever. A new book (a new install) has nothing to switch off:
 * it starts with the change marked done (the caller's rule). Boss switching it back on (its row, or one tap on the change in Autopilot's change
 * log) is final: the change never runs again. Pure.
 */
object McxMorningPark {
    /** The one-time change's key in the MCX arms' book (its marks). */
    const val MIGRATION = "park_mcx_silverm_am_2026_10_14"

    /** The arm it switches off. */
    const val SOURCE = McxMorningRules.SOURCE

    /** R42's numbers on the cleaned prices (sample sizes with every number). */
    const val HOLDOUT_TRADES = 53
    const val HOLDOUT_PER_TRADE = 336
    const val TWIN_P = 0.079
    const val WALK_FORWARD_TRADES = 129
    const val WALK_FORWARD_RS = -44_600
    const val DESIGN_TRADES = 95
    const val DESIGN_PER_TRADE = 871

    /** The arm's status once switched off (its log line, and its row while it is off). */
    const val PARKED = "switched off on your OK (14 Oct, research R42): no proven edge on cleaned prices"

    /** Why, in plain words with the sample sizes (the change log's reason). */
    const val REASON = "your OK (14 Oct), research R42: on cleaned prices the holdout made +Rs 336/trade over 53 trades, but a random " +
        "morning minute did about as well (p 0.079, needs under 0.05); walk-forward lost Rs 44.6k over 129 trades; the design edge " +
        "shrank to +Rs 871/trade over 95 trades"

    /** What its row says while it is off after the change (its switch stays there to turn it back on, paper only). */
    const val ROW = "Switched off on your OK (14 Oct, research R42): no proven edge - holdout +Rs 336/trade over 53 trades, " +
        "not better than a random minute (p 0.079); walk-forward -Rs 44.6k over 129 trades."

    /** The one-time notice (the arm log, the diagnostics and a notification), only when it was on. */
    const val NOTICE = "SILVERM morning call switched off on your OK (14 Oct, research R42): no proven edge on cleaned prices " +
        "(holdout +Rs 336/trade over 53 trades, random-twin p 0.079; walk-forward -Rs 44.6k over 129 trades). Paper only: an open " +
        "position still closes by its own exit. Switch it back on in Home → Strategies, or undo it in one tap in Home → " +
        "Automation card → Autopilot → Change log."

    /**
     * The change for a book with [armed] switches: true when the arm is on and must be switched off, false when there is
     * nothing to switch off, null when it already ran ([done]). A new install's book is the caller's (nothing to change).
     */
    fun switchOff(armed: Map<String, Boolean>, done: Boolean): Boolean? = if (done) null else armed[SOURCE] == true
}
