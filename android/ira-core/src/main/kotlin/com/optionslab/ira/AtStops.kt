package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * "How much can I lose today?" at his stops (usefulness, round 26, 2026-10-05): the daily loss limit's room
 * ([Headroom]) says what the guard still allows, but not what Boss's open legs can still cost him. This adds, after the
 * limits, the worst case from here across every open paper and Zerodha leg if each one's current stop is hit: a long
 * to its stop, a short to its stop, a bought leg with no stop down to zero (its whole value now), and a short with no
 * stop said plainly as having no ceiling. Each account's total is set against the daily loss limit left on it: within
 * it, or past it by how much.
 *
 * Facts only: a stop is a trigger, not a price - a gap or a fast market fills past it, and a stop the app keeps itself
 * fires only while the order watch runs (said so; a stop resting at Zerodha is triggered by the exchange). Nothing here
 * places, moves or closes anything, and no stop is set or suggested - the call is his. His account, so the app asks
 * for it only on an unlocked phone. Pure.
 */
object AtStops {
    /**
     * One open leg. [where] "Paper" or "Zerodha"; [qty] units (+long / -short); [ltp] its price now; [stop] its stop's
     * trigger (null: none); [stopAtBroker] true when that stop is an order resting at Zerodha.
     */
    data class Leg(
        val where: String, val symbol: String, val qty: Int, val ltp: Double,
        val stop: Double? = null, val stopAtBroker: Boolean = false,
    )

    /** One leg's worst case from here: [loss] in rupees (0 when the stop is at or past the price), null = no ceiling. */
    data class Worst(val leg: Leg, val loss: Double?, val how: How)

    enum class How { TO_STOP, PAST_STOP, TO_ZERO, NO_CEILING }

    private fun rs(x: Double) = "Rs " + "%,.0f".format(Locale.ENGLISH, abs(x))
    private fun px(x: Double) = "%.2f".format(Locale.ENGLISH, x).removeSuffix(".00")
    private fun qty(q: Int) = if (q < 0) "short ${-q}" else "$q"

    /** The worst case from here of one leg. */
    fun worst(l: Leg): Worst {
        val stop = l.stop?.takeIf { it.isFinite() && it > 0 }
        val n = abs(l.qty).toDouble()
        return when {
            l.qty > 0 && stop != null -> if (stop >= l.ltp) Worst(l, 0.0, How.PAST_STOP) else Worst(l, (l.ltp - stop) * n, How.TO_STOP)
            l.qty < 0 && stop != null -> if (stop <= l.ltp) Worst(l, 0.0, How.PAST_STOP) else Worst(l, (stop - l.ltp) * n, How.TO_STOP)
            l.qty > 0 -> Worst(l, max(0.0, l.ltp) * n, How.TO_ZERO)
            else -> Worst(l, null, How.NO_CEILING)
        }
    }

    private fun line(w: Worst): String {
        val l = w.leg
        val head = "${l.symbol} ${qty(l.qty)}"
        return when (w.how) {
            How.TO_STOP -> "$head ${rs(w.loss ?: 0.0)} to its ${px(l.stop ?: 0.0)} stop"
            How.PAST_STOP -> "$head is at or past its ${px(l.stop ?: 0.0)} stop (now ${px(l.ltp)})"
            How.TO_ZERO -> "$head has no stop: ${rs(w.loss ?: 0.0)} if it went to zero"
            How.NO_CEILING -> "$head is a short with no stop: its loss has no ceiling"
        }
    }

    /**
     * One account in words: the total if every stop is hit, each leg, and the total against [left] - the daily loss
     * limit still left on it (null: no limit set, or today's P&L not read). Null with no open leg.
     */
    fun account(where: String, legs: List<Leg>, left: Double?): String? {
        val open = legs.filter { it.qty != 0 && it.ltp.isFinite() }
        if (open.isEmpty()) return null
        val ws = open.map { worst(it) }
        val bounded = ws.mapNotNull { it.loss }.sum()
        val unbounded = ws.count { it.loss == null }
        val head = when {
            unbounded > 0 && bounded > 0 -> "On $where, if every stop is hit from here: at least ${rs(bounded)} more, and no ceiling"
            unbounded > 0 -> "On $where the worst case from here has no ceiling"
            else -> "On $where, if every stop is hit from here: ${rs(bounded)} more"
        }
        val against = when {
            left == null -> ""
            left <= 0 -> " - the daily loss limit is already used up"
            unbounded > 0 -> " - more than any limit; ${rs(left)} of the daily loss limit is left"
            bounded <= left -> " - within the ${rs(left)} left of the daily loss limit"
            else -> " - ${rs(bounded - left)} past the ${rs(left)} left of the daily loss limit"
        }
        return "$head$against (${ws.joinToString("; ") { line(it) }})."
    }

    /**
     * The answer added after the limits: each account with open legs ([left] by account name), then what a stop is and
     * is not. Null when nothing is open anywhere.
     */
    fun say(legs: List<Leg>, left: Map<String, Double?>): String? {
        val byWhere = legs.groupBy { it.where }
        val order = (listOf("Zerodha", "Paper") + byWhere.keys).distinct().filter { it in byWhere }
        val parts = order.mapNotNull { w -> account(w, byWhere.getValue(w), left[w]) }
        if (parts.isEmpty()) return null
        val open = legs.filter { it.qty != 0 && it.stop != null }
        val appKept = open.any { !it.stopAtBroker }
        val note = "A stop is a trigger, not a price: a gap or a fast market fills past it" +
            (if (appKept) ", and a stop the app keeps itself works only while the order watch runs." else ".")
        return (parts + note).joinToString(" ")
    }

    /** The daily loss limit still left on [b] (null: no limit, or today's P&L not read). */
    fun left(b: Headroom.Book): Double? {
        val l = b.limits.maxDailyLoss; val pnl = b.account.dayPnl
        return if (l > 0 && pnl.isFinite()) l + pnl else null
    }

    // ---- asked -------------------------------------------------------------------------------------------------

    /**
     * The worst case asked in words of its own ("what's my worst case today", "what if all my stops get hit", "aaj
     * kitna loss ho sakta hai"): read by [Headroom.asked] as its LOSS question, where this answer is added.
     */
    internal val ASKED = rx(
        " (whats|what is|what s|tell me|show me) (my |the )?(worst case|worst possible loss|max possible loss|maximum possible loss)( today| for today| for the day| right now| now)? $" +
        "| (my )?worst case (today|for today|for the day|right now) $| how much (do|would|will) i lose if (all|every|each) (of )?(my )?stops? (is |are |get |gets |were )?(hit|triggered) " +
        "| what if (all|every|each) (of )?(my )?stops? (is |are |get |gets )?(hit|triggered) | (if )?(all|every) (of )?my stops? (get |gets |are |is )?(hit|triggered) (how much|kitna) " +
        "| aaj (max |maximum |zyada se zyada |jyada se jyada )?kitna (loss|nuksan|nuksaan) ho sakta (hai|he) | (sab|saare|sare) (stop|stops) (hit|lag) (ho|ho gaye|ho jaye|hue) (to|toh) kitna ")
}
