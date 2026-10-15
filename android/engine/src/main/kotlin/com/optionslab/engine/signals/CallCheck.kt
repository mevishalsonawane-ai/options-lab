package com.optionslab.engine.signals

import com.optionslab.engine.signals.CallParser.Draft
import java.time.LocalDate
import kotlin.math.abs

/**
 * The Signal tester's check of a reading against reality before it counts (15 Oct 2026): whoever read it (the rules, the model
 * or both), a call is tested only when
 *  - its index or stock exists (an index the app knows, or a symbol in the phone's contract lists),
 *  - its strike sits on that underlying's strike grid ([STEPS]; a stock's against its listed strikes when the phone has them)
 *    and within a sane distance of the spot at the message's time ([MAX_AWAY]),
 *  - it has CE / PE, an entry (unless "at market"), a premium that looks like a premium,
 *  - its targets are above the entry, in order, and its stop below it (option buys only),
 *  - and its written expiry is not already past.
 * Anything failing goes to "Needs review" with the reasons. What the phone cannot tell (no spot yet, a stock it has no list for)
 * is not a failure: [problems] says only what it could check. Pure.
 */
object CallCheck {
    /** The strike grids of the indices (a strike must be a multiple). */
    val STEPS: Map<String, Int> = mapOf("NIFTY" to 50, "BANKNIFTY" to 100, "FINNIFTY" to 50, "MIDCPNIFTY" to 25, "SENSEX" to 100, "BANKEX" to 100)
    /** The farthest a strike may be from the spot: 6% for an index (weeklies trade near the money), 20% for a stock. */
    const val MAX_AWAY_INDEX = 0.06
    const val MAX_AWAY_STOCK = 0.20

    /**
     * What is wrong with [d] for a call sent on [on]: [known] whether the phone knows its underlying (null: cannot tell), [spot]
     * the underlying's price at the message's time (null: unknown), [listed] whether its strike is listed (null: no list).
     */
    fun problems(d: Draft, on: LocalDate, known: Boolean?, spot: Double?, listed: Boolean?): List<String> {
        val out = ArrayList(CallModel.missing(d))
        val u = d.underlying
        val k = d.strike
        if (u != null && known == false) out += "$u is not an index or a stock the app knows"
        if (u != null && k != null) {
            val step = STEPS[u]
            if (step != null && abs(k / step - Math.rint(k / step)) > 1e-9) out += "strike ${CallParser.num(k)} is not on $u's grid (every $step)"
            if (step == null && listed == false) out += "strike ${CallParser.num(k)} is not a listed $u strike"
            if (spot != null && spot > 0) {
                val away = abs(k / spot - 1)
                val max = if (step != null) MAX_AWAY_INDEX else MAX_AWAY_STOCK
                if (away > max) out += "strike ${CallParser.num(k)} is ${"%.0f".format(java.util.Locale.ENGLISH, away * 100)}% from $u's ${CallParser.num(Math.round(spot).toDouble())}"
            }
        }
        val e = d.trigger
        if (e == null && d.trig != CallParser.Trig.CMP) out += "no entry price"
        if (e != null) {
            if (e <= 0) out += "the entry is not a price"
            else if (spot != null && spot > 0 && e > spot * 0.25) out += "the entry ${CallParser.num(e)} is not an option premium for $u"
            if (d.targets.any { it <= e }) out += "a target is not above the entry"
            d.stopLevel?.let { s -> if (s >= e) out += "the stop is not below the entry" else if (s <= 0) out += "the stop is not a price" }
        }
        if (d.targets.zipWithNext().any { (a, b) -> b <= a }) out += "the targets are not in rising order"
        d.expiry?.let { if (it.isBefore(on)) out += "the expiry $it had already passed" }
        return out
    }

    /** The nearest of [expiries] on or after [day] (the call's expiry when it names none), or null. */
    fun nearestExpiry(day: LocalDate, expiries: Collection<LocalDate>): LocalDate? = expiries.filter { !it.isBefore(day) }.minOrNull()
}
