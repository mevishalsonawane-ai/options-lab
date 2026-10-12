package com.optionslab.engine.orb

import java.util.Locale

/**
 * ORB / ORB Fresh's re-entry cap (research R14, 12 Oct 2026, research/HUNT_R14_REENTRY_CHURN.md; Boss: "ORB takes the same
 * order again and again, each time only Rs 100-200, but my charges increase a lot").
 *
 * Without a cap the ORB re-enters on the bar after every exit while BANKNIFTY stays beyond its range: about 9 entries a day,
 * up to 23 on one side. On real BANKNIFTY option minutes (Aug 2021 - Oct 2026, the app's exits, 1 lot, real fills and
 * charges) the re-entries made about Rs 0 before charges, so their charges (Rs 75-105 a trade) were the whole cost.
 * At most [PAPER_CAP] entry a side a day:
 *  - ORB: design (to Sep 2025) -Rs 6.50 lakh -> -Rs 0.97 lakh, holdout (Oct 2025 - Oct 2026) -Rs 1.89 lakh -> -Rs 0.18 lakh;
 *  - ORB Fresh: -Rs 2.00 lakh -> -Rs 0.97 lakh, holdout -Rs 38k -> -Rs 18k;
 *  - orders: 15,640 -> 1,924 (ORB, design). Both arms still lose: it is a damage limiter, not an edge.
 * "Let it run" (no +40 target, trailing 75% of the best gain) changed nothing that beat chance (ORB +Rs 14k in 4 years,
 * p 0.16; holdout -Rs 0.4k): it is not used.
 *
 * Fewer entries only: the cap never changes an exit, a stop, the 15:10 square-off or the 15:05 expiry exit. Pure.
 */
object ReentryCap {
    /** The status a capped break leaves: "side_cap_reached:CE:1:1" (side, entries used, the cap). */
    const val REASON = "side_cap_reached"

    /** The cap the research supports (R14: confirmed on train and holdout), the arms' PAPER default from 13 Oct 2026. */
    const val PAPER_CAP = 1

    /** The sources it applies to: the two break arms (ORB Sweep and Range Fade already stop at 2 entries a day). */
    val ARMS: Set<String> = setOf(OrbRules.ORB.source, OrbRules.ORB_FRESH.source)

    /** One line of the evidence, for the lab's change record and Jarvis. */
    const val EVIDENCE = "R14 (12 Oct 2026): on 5 years of real BANKNIFTY option minutes, at most 1 entry a side a day cut ORB's " +
        "loss from Rs 6.50 lakh to Rs 0.97 lakh before Oct 2025 and from Rs 1.89 lakh to Rs 0.18 lakh after (orders 15,640 -> 1,924); " +
        "ORB Fresh Rs 2.00 lakh -> Rs 0.97 lakh and Rs 38k -> Rs 18k. The re-entries made about Rs 0 before charges: the saving is " +
        "their charges. Both arms still lose"

    fun right(direction: Int): String = if (direction > 0) "CE" else "PE"

    /** The refusal of a break on [direction] with [used] entries on that side today under [cap] (0: none), or null to enter. */
    fun refusal(direction: Int, used: Int, cap: Int): String? =
        if (cap > 0 && direction != 0 && used >= cap) "$REASON:${right(direction)}:$used:$cap" else null

    /** Today's entries per side (+1 CE / -1 PE) from the rights of today's positions ("CE" / "PE"). */
    fun taken(rights: List<String>): Map<Int, Int> =
        rights.mapNotNull { when (it) { "CE" -> 1; "PE" -> -1; else -> null } }.groupingBy { it }.eachCount()

    fun isCap(status: String): Boolean = status == REASON || status.startsWith("$REASON:")

    /** [status] ([refusal]'s) in words: "Not re-entering CE: 1 of 1 entries on that side used today; saves charges (R14)." */
    fun words(status: String): String {
        val p = status.split(':')
        val side = p.getOrNull(1) ?: "this side"
        val used = p.getOrNull(2)?.toIntOrNull()
        val cap = p.getOrNull(3)?.toIntOrNull()
        val count = if (used != null && cap != null) "$used of $cap ${if (cap == 1) "entry" else "entries"} on that side used today" else "today's entries on that side used"
        return "Not re-entering $side: $count; saves charges (research R14: re-entries made about Rs 0 before charges)."
    }

    /**
     * The charges line for an arm: "Charges ₹212 today · ₹4,310 this month (58 orders)". Null when nothing was paid this month.
     */
    fun chargesWords(today: Double, month: Double, orders: Int): String? {
        if (!(month > 0.0) && !(today > 0.0)) return null
        return "Charges ${rs(today)} today · ${rs(month)} this month ($orders order${if (orders == 1) "" else "s"})"
    }

    private fun rs(x: Double): String = "₹" + String.format(Locale.ENGLISH, "%,.0f", x)
}
