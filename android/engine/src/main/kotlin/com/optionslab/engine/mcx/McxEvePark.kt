package com.optionslab.engine.mcx

/**
 * The NG evening breakout ([McxEveRules]) switched off on paper (25 Oct 2026, Boss: "Do whatever you think better for
 * application", on the arms audit's open decisions). Research R53 (research/HUNT_R53_MCX_CRYPTO_RECENT.md: its final test lost
 * -Rs 3,508 over 115 trades, after +Rs 19.4k over 140 in design) and R83 (research/HUNT_R83_MCX_ENERGY.md: -Rs 51,428 over 881
 * final trades after costs on 2016-2026 Henry Hub data) both say switching it off lowers risk.
 *
 * Once, on this update ([MIGRATION], kept in the MCX arms' book), the arm is switched off if it is on - exactly as the SILVERM
 * morning call was ([McxMorningPark]). A PAPER arm only (the MCX arms never reach Zerodha): this only lowers paper risk and
 * nothing carries over to live. Switching off only stops NEW entries: a position it holds is still watched and closed by its
 * own exits, the expiry exit or the bot's stop. Logged in the Autopilot change timeline, where one tap switches it back on;
 * Boss switching it back on (its row, or that tap) is final - the change never runs again. Pure.
 */
object McxEvePark {
    /** The one-time change's key in the MCX arms' book (its marks). */
    const val MIGRATION = "park_mcx_ng_eve_2026_10_25"

    /** The arm it switches off. */
    const val SOURCE = McxEveRules.SOURCE

    /** R53's final test and R83's (sample sizes with every number). */
    const val R53_TRADES = 115
    const val R53_RS = -3_508
    const val R83_TRADES = 881
    const val R83_RS = -51_428

    /** The arm's status once switched off (its log line, and its row while it is off). */
    const val PARKED = "switched off on your decision (25 Oct, research R53 and R83): it loses after costs"

    /** Why, in plain words with the sample sizes (the change log's reason). */
    const val REASON = "your decision on the arms audit (25 Oct), research R53 and R83: its final test lost -Rs 3,508 over 115 trades " +
        "(R53; +Rs 19.4k over 140 in design) and -Rs 51,428 over 881 trades after costs on 2016-2026 data (R83); its timing beats " +
        "random but the wide natgas book's costs take all of it"

    /** What its row says while it is off after the change (its switch stays there to turn it back on, paper only). */
    const val ROW = "Switched off on your decision (25 Oct, research R53 and R83): it loses after costs - R53 final -Rs 3,508 over " +
        "115 trades; R83 final -Rs 51,428 over 881 trades."

    /** The one-time notice (the arm log, the diagnostics and a notification), only when it was on. */
    const val NOTICE = "NG evening breakout switched off on your decision (25 Oct, research R53 and R83): it loses after costs " +
        "(R53 final -Rs 3,508 over 115 trades; R83 final -Rs 51,428 over 881). Paper only: an open position still closes by its own " +
        "exit. Switch it back on in Home → Strategies, or undo it in one tap in Home → Automation card → Autopilot → Change log."

    /**
     * The change for a book with [armed] switches: true when the arm is on and must be switched off, false when there is
     * nothing to switch off, null when it already ran ([done]).
     */
    fun switchOff(armed: Map<String, Boolean>, done: Boolean): Boolean? = if (done) null else armed[SOURCE] == true
}
