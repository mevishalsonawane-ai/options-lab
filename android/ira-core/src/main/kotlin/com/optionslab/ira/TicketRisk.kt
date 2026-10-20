package com.optionslab.ira

import com.optionslab.engine.fmtG
import java.time.LocalDate
import java.util.Locale

/**
 * The paper ledger's open ticket (the expiry short put, SELL PE, paper only) and its two risk notices, decided here (20 Oct).
 *
 * Boss's 19 Oct diagnostics held "index nearing your breakeven" twice ("is 0.40% above" and then "is -0.12% above") and
 * "short put is in the money" twice. They were a real paper short put (the ledger only ever records SELL PE), not a long put
 * misread, but the notices were wrong in two ways:
 *  - each was said once per watch run (a set in memory), so the 13:33 restart said both again;
 *  - below the breakeven the words said "-0.12% above" instead of "below".
 * Now each notice (nearing, below, in the money) is said once per ticket ([key]: the notice and the ticket's session), the keys kept across a restart by
 * the app; below the breakeven it says so; and a ticket past its expiry (left unsettled) is not watched as if still live.
 * Pure: the app gives the ticket, the index price and the keys already said; nothing here places or closes anything.
 */
object TicketRisk {
    /** One notice: [key] (said once per ticket), the notification's [id], its [title] and [text]. */
    data class Notice(val key: String, val id: Int, val title: String, val text: String)

    const val NEAR = "risk.near"
    const val ITM = "risk.itm"
    const val BELOW = "risk.below"

    /** The key a notice is said once under: one per ticket (its session), so a new ticket is warned again. */
    fun key(kind: String, session: LocalDate): String = "$kind|$session"

    /**
     * The notices due now for a ticket ([side], [right], [strike], [breakeven], [expiry], [session]) on [today] with the index at
     * [spot], warning within [riskPct] (a fraction) of the breakeven; [said] holds the keys already said. Only a short put
     * (SELL PE) is watched this way, and only to its expiry.
     */
    fun due(underlying: String, side: String, right: String, strike: Double, breakeven: Double, expiry: LocalDate, session: LocalDate,
            today: LocalDate, spot: Double?, riskPct: Double, said: Set<String>): List<Notice> {
        if (spot == null || !spot.isFinite() || spot <= 0 || !strike.isFinite() || !breakeven.isFinite()) return emptyList()
        if (!side.equals("SELL", true) || !right.equals("PE", true)) return emptyList()
        if (today.isAfter(expiry)) return emptyList()
        val out = ArrayList<Notice>()
        val cushion = (spot - breakeven) / spot
        val near = key(NEAR, session)
        val below = key(BELOW, session)
        if (cushion <= riskPct) {
            val pct = "%.2f%%".format(Locale.ENGLISH, 100 * kotlin.math.abs(cushion))
            val at = "$underlying ${n1(spot)} is $pct"
            // Nearing it and then below it are two steps, each said once (review: one key hid the "losing" step).
            if (cushion >= 0 && near !in said) out += Notice(near, 3001, "Index nearing your breakeven",
                "$at above the ${n1(breakeven)} breakeven of the ${fmtG(strike)} put.")
            if (cushion < 0 && below !in said) out += Notice(below, 3001, "Index below your breakeven",
                "$at below the ${n1(breakeven)} breakeven of the ${fmtG(strike)} put: the paper ticket is losing.")
        }
        val itm = key(ITM, session)
        if (spot < strike && itm !in said) out += Notice(itm, 3002, "Short put is in the money",
            "$underlying ${n1(spot)} is below the ${fmtG(strike)} strike. The loss is unbounded below here.")
        return out
    }

    private fun n1(x: Double) = "%,.1f".format(Locale.ENGLISH, x)

    /** The keys as kept ("risk.near|2026-10-19,risk.itm|2026-10-19"), and back; only the newest [keep] tickets' keys. */
    fun encode(keys: Set<String>, keep: Int = 20): String {
        val sessions = keys.map { it.substringAfter('|') }.distinct().sorted().takeLast(keep).toSet()
        return keys.filter { it.substringAfter('|') in sessions }.sorted().joinToString(",")
    }

    fun decode(s: String?): Set<String> = s.orEmpty().split(",").map { it.trim() }.filter { '|' in it }.toSet()
}
