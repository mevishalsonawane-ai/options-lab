package com.optionslab.ira

import java.time.LocalDate

/**
 * Liquidity BANKNIFTY's paper test is run on its own rules, untouched (Boss, 12 Oct; the strategy scorecard: "the strategy
 * lab and regime changes are frozen for this book"; research R12: every exit manager tried made it worse). Until [UNTIL]
 * (the 10th paper day) no automatic change alters its entries or exits:
 *  - the strategy lab's paper variants (its exit parameters) are not applied to its books: they trade their constants;
 *  - Autopilot applies no change aimed at Liquidity (a mode, a pause, a threshold) - recorded, not applied;
 *  - the day's regime lesson and the self-review's paper pause do not skip its entries;
 *  - the trade manager only records it (its exits never read the manager, in ACT or SHADOW).
 * Shadows and records go on. Boss may unfreeze it from the Strategy lab card (automatic changes only ever lower risk), and
 * freeze it again. Pure: today is given; the state is the app's small file.
 */
object LiquidityFreeze {
    /** The last of Boss's 10 paper days (13-26 Oct 2026). A constant: tests give their own today. */
    val UNTIL: LocalDate = LocalDate.of(2026, 10, 26)

    /** What a change recorded but not applied says. */
    const val NOTE = "frozen for Liquidity BANKNIFTY's paper test: recorded, not applied"

    /**
     * The paper test's small state: [unfrozen] by Boss; the live stop rule's restart ([stopResetAtMs]: trades closed before
     * it no longer count, set when Boss re-enables live entries with his PIN) and the stop already told ([stopTold]); Boss's
     * own tick that he watched the first live trade ([watched]); Boss's own opt-in Rs 500 give-back trail on the Liquidity PAPER
     * books ([trail], research R19b: failed its gates, so off unless he switches it on; see [trailNote]).
     */
    data class State(val unfrozen: Boolean = false, val stopResetAtMs: Long = 0L, val stopTold: String = "", val watched: Boolean = false,
                     val trail: Boolean = false,
                     /**
                      * 20 Oct, enhancement round 1 ([LiquidityTwin]): the no-twin rule on the paper books - on by this update (logged in the
                      * Autopilot timeline, one tap undoes it). A file from before it has no line: on.
                      */
                     val noTwin: Boolean = true,
                     /**
                      * 22 Oct (Boss: "if the 10% is received, make the stoploss to the start point"): Boss's own breakeven at +10% on the
                      * Liquidity PAPER books ([com.optionslab.engine.orb.LiquidityBreakeven], research R100 A4: did not pass, so off unless
                      * they switch it on; see [breakevenNote]). A file from before it has no line: off.
                      */
                     val breakeven: Boolean = false)

    fun frozen(s: State, today: LocalDate): Boolean = !s.unfrozen && !today.isAfter(UNTIL)

    private fun day(d: LocalDate) = "${d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${d.dayOfMonth} ${d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"

    /** The row's note. */
    fun note(s: State, today: LocalDate): String = when {
        frozen(s, today) -> "Frozen for the paper test until ${day(UNTIL)}: the strategy lab's variants, Autopilot and the trade manager " +
            "only record - they do not change its entries or exits."
        s.unfrozen && !today.isAfter(UNTIL) -> "Unfrozen by you: automatic changes that lower risk may apply to it again."
        else -> "The paper test's freeze ended on ${day(UNTIL)}."
    }

    /** Whether an Autopilot change target ("paper:liquidity", "flow:liquidity:BANKNIFTY", "mgr:liquidity:paper") is Liquidity's. */
    fun touches(target: String): Boolean = target.contains("liquidity", ignoreCase = true)

    fun encode(s: State): String = "v1\nunfrozen=${s.unfrozen}\nstopResetAt=${s.stopResetAtMs}\nstopTold=${s.stopTold.replace('\n', ' ')}\nwatched=${s.watched}\n" +
        "trail=${s.trail}\nnoTwin=${s.noTwin}\nbreakeven=${s.breakeven}\n"

    fun decode(text: String?): State {
        if (text.isNullOrBlank()) return State()
        val m = text.lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
        return State(m["unfrozen"] == "true", m["stopResetAt"]?.toLongOrNull() ?: 0L, m["stopTold"].orEmpty(), m["watched"] == "true",
            m["trail"] == "true", m["noTwin"] != "false", m["breakeven"] == "true")
    }

    // ---- Boss's Rs 500 give-back trail (R19b, 13 Oct): his own choice, on paper only -----------------------------------------
    // 13 Oct, later (R19c): Boss's rule is now "from +Rs 1,000 a lot, sell Rs 500 below the best" (never under +Rs 500).

    /** The switch's name on the Strategy lab card. */
    const val TRAIL_ON = "Try your ₹1,000 / ₹500 trail on paper"
    const val TRAIL_OFF = "Back to plain rules"
    /** The card's line under the switch (13 Oct review): what switching it on costs the plain-rules record. */
    const val TRAIL_RECORD = "While it is on, new Liquidity BANKNIFTY paper trades use your trail, so the plain-rules record " +
        "(used for the 26 Oct decision) does not grow."

    /**
     * The switch's line: what the trail is, that it FAILED the research (with its numbers) and is Boss's choice, that it is
     * paper only and labels each trade, and whether it is on.
     */
    fun trailNote(s: State): String = "Your trail: from +₹1,000 a lot, sell ₹500 below the best, so never under +₹500 " +
        "(failed research, your choice): " +
        (if (s.trail) "ON for new Liquidity PAPER trades - each one is labelled \"with ₹500 trail\"; live trades never use it."
        else "off. Liquidity trades on plain rules.") +
        " R19c (locked holdout Apr 2025 - Feb 2026): more green days (15m 37% to 47%, 5m 40% to 50%) but the net fell from +₹23.4k " +
        "to -₹6.9k (15m, 51 trades) and from +₹27.3k to +₹6.7k (5m, 170 trades), worse than random early exits. R19b's ₹500 from " +
        "+₹500 cut 12 of 13 big winners. MIDCPNIFTY was not tested (no option data)."

    /** What Boss's switch does, said back to Boss (and to the diagnostics) when they flip it. */
    fun trailSaid(on: Boolean): String = if (on)
        "Your trail (from +₹1,000 a lot, ₹500 below the best) ON for new Liquidity PAPER trades (failed research, your choice): each trade " +
            "is labelled \"with ₹500 trail\". " +
            "Live trades never use it. Switch it off on the Strategy lab card at any time."
    else "Your trail off: new Liquidity paper trades run on plain rules again (open trades keep the rule they entered with)."

    // ---- Boss's breakeven at +10% (22 Oct, R100 A4): their own choice, on paper only ------------------------------------------

    /** The switch's name on the Strategy lab card. */
    const val BREAKEVEN_ON = "Move the stop to the price paid at +10% (paper)"
    const val BREAKEVEN_OFF = "Back to plain rules (breakeven off)"

    /**
     * The switch's line: what it does, that it did NOT pass the research (with its numbers) and is Boss's choice, that it is paper
     * only and labels each trade, and whether it is on.
     */
    fun breakevenNote(s: State): String = "Your breakeven: once a trade is +10%, its stop moves to the price paid plus charges " +
        "(${com.optionslab.engine.orb.LiquidityBreakeven.NOTE}): " +
        (if (s.breakeven) "ON for new Liquidity PAPER trades - each one is labelled \"${com.optionslab.engine.orb.LiquidityBreakeven.LABEL}\" " +
            "and kept apart from the plain-rules record; live trades never use it."
        else "off. Liquidity trades on plain rules.") +
        " Research R100 (1,238 past trades, 20 Nov 2024 - 13 Oct 2026, 1 lot, after charges): Rs 2,45,083 at a 38% win rate, " +
        "against Rs 2,68,404 at 42% on the plain rules. A sale at the breakeven ends about Rs 100 down after slippage (100 such exits); on 22 Oct's MIDCPNIFTY trade that is about -Rs 100 " +
        "instead of -Rs 1,168."

    /** What Boss's switch does, said back to Boss (and to the diagnostics) when they flip it. */
    fun breakevenSaid(on: Boolean): String = if (on)
        "Your breakeven at +10% ON for new Liquidity PAPER trades (${com.optionslab.engine.orb.LiquidityBreakeven.NOTE}): once a trade is " +
            "+10%, its stop moves to the price paid plus charges. Each trade is labelled \"${com.optionslab.engine.orb.LiquidityBreakeven.LABEL}\". " +
            "Live trades never use it. Switch it off on the Strategy lab card at any time."
    else "Your breakeven off: new Liquidity paper trades run on plain rules again (open trades keep the rule they entered with)."
}
