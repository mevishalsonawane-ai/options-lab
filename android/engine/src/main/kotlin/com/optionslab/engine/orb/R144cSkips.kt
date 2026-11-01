package com.optionslab.engine.orb

import java.time.LocalTime

/**
 * Research R144c's skips (1 Nov 2026, Boss: "Update all arms which helps", after R144c part C's before / after backtests,
 * research/HUNT_R144C_LOSS_EXPIRY_OTHER.md). FAILED GATES, BOSS'S EXCEPTION: none of these passed R144c's research gates (they
 * were found by looking at the same years they are judged on), so they are labelled so wherever they are shown, and:
 *  - PAPER: they act on the arm's paper book (Boss's exception, as S5 Trail's), logged in the Autopilot change timeline and
 *    undone in one tap;
 *  - LIVE: SHADOW ONLY. When the arm's trade would really be sent live ([Mode.LIVE_SHADOW]: the app in Live, "AI trades go live",
 *    the arm's own Trade live on and its Shadow off), the skip never acts: the paper trade (and so its live mirror) goes as before,
 *    and what the skip would have done is logged (CLAUDE.md: anything new that could skip a live trade starts logged only).
 *
 * The three rules (signal source: FT3's two read the picked option's own price and OI - FT3 is an option-price arm, labelled so;
 * Night's reads India VIX):
 *  - FT3, monthly-only expiries: no ticket on BANKNIFTY, FINNIFTY or MIDCPNIFTY (monthly expiries only since 20 Nov 2024; R144c:
 *    37 tickets since then, 1 won).
 *  - FT3, "long unwinding": skip the pick when its option's close AND OI both fell over the 15 minutes before the mark (the
 *    close of the mark's bar against the newest close 14-18 minutes before it). Either side unknown: never skipped.
 *  - Night: skip the 15:20 entry when India VIX at 15:19 is more than 3.13% under its previous session's close ([NIGHT_VIX_CUT],
 *    R144c part B's design cut). VIX not known: never skipped.
 * R144c part C (2022-26, hindsight): FT3 both skips 378 -> 306 tickets, net +Rs 6.24 -> 8.40 lakh, win 17% -> 20%; Night 664 ->
 * 536 trades, net +Rs 1.77 -> 2.24 lakh (the unseen years' drawdown got worse: Rs 24.7k -> 36.0k).
 *
 * Pure: no clock, no network, no orders.
 */
object R144cSkips {
    const val LABEL = "failed gates, Boss's exception (1 Nov)"

    /** FT3's indices with monthly expiries only. */
    val FT3_MONTHLY_ONLY: Set<String> = setOf("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")

    /** Night: India VIX down more than this (a fraction) since the previous close skips the entry. */
    const val NIGHT_VIX_CUT = -0.0313

    /** The long-unwinding look-back: the earlier close is the newest one closing [BACK_FROM] .. [BACK_TO] minutes before the mark's. */
    const val BACK_FROM = 14L
    const val BACK_TO = 18L

    enum class Mode { OFF, PAPER, LIVE_SHADOW }

    /** How a skip acts now: [paperOn] its paper switch; [goesLive] the arm's trade would really be sent live. */
    fun mode(paperOn: Boolean, goesLive: Boolean): Mode = when {
        !paperOn -> Mode.OFF
        goesLive -> Mode.LIVE_SHADOW
        else -> Mode.PAPER
    }

    fun ft3MonthlyOnly(underlying: String): Boolean = underlying.uppercase() in FT3_MONTHLY_ONLY

    /** Both the option's close and its OI fell (each compared newer vs older); null when any is unknown (never skipped). */
    fun longUnwinding(closeThen: Double?, closeNow: Double?, oiThen: Double?, oiNow: Double?): Boolean? {
        if (closeThen == null || closeNow == null || oiThen == null || oiNow == null) return null
        if (!(closeThen > 0) || !(closeNow > 0) || !(oiThen > 0) || !(oiNow > 0)) return null
        return closeNow < closeThen && oiNow < oiThen
    }

    /**
     * FT3's long-unwinding test from the option's 1-minute bars keyed by the minute each CLOSES ([closeAt] the mark's bar's close):
     * (close, OI) now from the bar closing at [closeAt]; then from the newest bar closing [BACK_FROM]..[BACK_TO] minutes before it.
     * An OI of 0 (the feed gave none) counts as unknown.
     */
    fun ft3LongUnwinding(bars: Map<LocalTime, Pair<Double, Long>>, closeAt: LocalTime): Boolean? {
        val now = bars[closeAt] ?: return null
        val then = (BACK_FROM..BACK_TO).asSequence().mapNotNull { bars[closeAt.minusMinutes(it)] }.firstOrNull() ?: return null
        return longUnwinding(then.first, now.first, then.second.takeIf { it > 0 }?.toDouble(), now.second.takeIf { it > 0 }?.toDouble())
    }

    /** Night: the VIX minute read as "15:19" must start no more than this many minutes before 15:19 (1 Nov review: a stalled feed). */
    const val NIGHT_VIX_FRESH_MIN = 5

    /** Night: whether a VIX minute starting at [istMinute] (minutes since midnight, IST) is fresh enough to stand for 15:19. */
    fun nightVixFresh(istMinute: Int): Boolean = istMinute in (15 * 60 + 19 - NIGHT_VIX_FRESH_MIN)..(15 * 60 + 19)

    /** Night: VIX at 15:19 [vixNow] against its previous close [vixPrev]; null when either is unknown. */
    fun nightVixDown(vixNow: Double?, vixPrev: Double?): Boolean? {
        if (vixNow == null || vixPrev == null || !(vixNow > 0) || !(vixPrev > 0)) return null
        return vixNow / vixPrev - 1 < NIGHT_VIX_CUT
    }

    /** The words for a skip, by mode: what happened (paper) or what the live shadow logged. */
    fun words(mode: Mode, what: String): String = when (mode) {
        Mode.PAPER -> "skipped by R144c ($LABEL): $what"
        Mode.LIVE_SHADOW -> "R144c would skip ($what), but its live trade goes as before: live stays in shadow, logged only"
        Mode.OFF -> ""
    }
}
