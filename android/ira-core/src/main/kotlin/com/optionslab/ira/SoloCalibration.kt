package com.optionslab.ira

import java.time.LocalDate

/**
 * Self-calibration for Solo (Jarvis learning from his own outcomes, round 2): Solo's own paper trades judged by the
 * conditions they came in, with [SelfCalibration]'s scoring and its one-way rule - what it learns can only make Solo
 * MORE careful on paper:
 *
 *  - where Solo's record is clearly bad ([SelfCalibration.Action.SIT_OUT]) Solo takes nothing by itself; the trade it
 *    would have taken is kept as a [Shadow] and scored on the option's real prices as if taken (nothing is placed), so
 *    the condition keeps being judged and can earn its way back;
 *  - where it is losing but not yet clearly bad ([SelfCalibration.Action.SHRINK]) Solo is already at its smallest (one
 *    lot), so it takes at most [SOFT_A_DAY] trade a day there (the rest are shadows too).
 *
 * Solo is judged by its own record only (never by Boss's trades, never by the news and pattern ideas), and a good
 * record never raises anything: not the lots, not the trades a day, not the stop. Nothing here starts a trade, and
 * nothing here can reach a real order. Pure.
 *
 * Retired as a gate on 06 Oct 2026: Solo (midday) ([com.optionslab.engine.orb.SoloMidday]) takes every trade its rule finds,
 * because the research found a self-gate on Solo's own record cost money out of sample (solo2/SPEC.md, "must NOT be added").
 * Kept for the old Solo's record and the answers and reviews that read it.
 */
object SoloCalibration {
    /** The kind Solo's trades are kept under, and what they are called. */
    const val KIND = "Solo"
    const val WHO = "my Solo trades"
    /** Rupees a round trip charged to every Solo trade (as Solo's own), so a shadow is scored like a real one. */
    const val COSTS = 60.0
    /** Trades a day Solo still takes by itself in a condition where its record is losing but not yet clearly bad. */
    const val SOFT_A_DAY = 1
    /** Shadows kept (the newest; the scoring only reads [SelfCalibration.WINDOW_DAYS] days anyway). */
    const val KEEP = 300

    /** The conditions of a Solo trade entered at session minute [entryMinute] (0 = 09:15) on [day]. */
    fun conditions(day: LocalDate, entryMinute: Int, market: Market, call: Boolean, regime: Regime.Kind? = null, ivRank: Double? = null) =
        SelfCalibration.Conditions(day.atTime(9, 15).plusMinutes(entryMinute.coerceIn(0, 374).toLong()), market, call, KIND, regime,
            ivRank?.takeIf { it.isFinite() })

    /** A closed Solo trade as an outcome: [net] rupees after costs over [qty] units (null: no result to score). */
    fun outcome(c: SelfCalibration.Conditions, net: Double?, qty: Int): SelfCalibration.Outcome? {
        if (net == null || !net.isFinite() || qty <= 0) return null
        return SelfCalibration.Outcome(c.copy(kind = KIND), net / qty)
    }

    /**
     * A trade Solo sat out, followed as if taken: bought at [entry] (the ask then), sold at [exit] (the bid at minute
     * [due]). Only ever a record - no order is placed for it.
     */
    data class Shadow(val c: SelfCalibration.Conditions, val symbol: String, val qty: Int, val entry: Double, val due: Int, val exit: Double? = null,
                      /** The index then (to find the same contract again when it is scored). */
                      val spot: Double? = null) {
        val settled: Boolean get() = exit != null
        /** What it would have made a unit after costs (null until settled, or when a price was not usable). */
        fun points(costs: Double = COSTS): Double? {
            val x = exit ?: return null
            if (!x.isFinite() || x < 0 || !entry.isFinite() || entry <= 0 || qty <= 0) return null
            return x - entry - costs / qty
        }
    }

    /** 15:10 in minutes from 09:15: the old Solo's last minute (everything out), which its shadows were scored by. */
    const val CUT = 355

    /** The minute a shadow is scored: when the trade would have been out ([horizon] minutes on), 15:10 at the latest. */
    fun dueAt(entryMinute: Int, horizon: Int): Int = minOf(entryMinute + maxOf(1, horizon), CUT)

    /** The price a shadow is bought at: the ask, or the last price when there is no ask (null: no usable price). */
    fun buyPrice(ask: Double, last: Double): Double? = (if (ask > 0 && ask.isFinite()) ask else last).takeIf { it > 0 && it.isFinite() }

    /** The price a shadow is sold at: the bid, or the last price when there is no bid (null: no usable price). */
    fun sellPrice(bid: Double, last: Double): Double? = (if (bid > 0 && bid.isFinite()) bid else last).takeIf { it >= 0 && it.isFinite() }

    /** A new shadow, or null when its price is not usable. */
    fun shadow(c: SelfCalibration.Conditions, symbol: String, qty: Int, ask: Double, last: Double, entryMinute: Int, horizon: Int, spot: Double? = null): Shadow? {
        if (qty <= 0) return null
        val p = buyPrice(ask, last) ?: return null
        return Shadow(c.copy(kind = KIND), symbol, qty, p, dueAt(entryMinute, horizon), spot = spot?.takeIf { it.isFinite() && it > 0 })
    }

    /** One shadow at a time in a market (Solo trades one at a time too): none waiting there to be scored. */
    fun mayShadow(shadows: List<Shadow>, market: Market): Boolean = shadows.none { !it.settled && it.c.market == market }

    /** The shadows to score now: today's, unsettled, due at or before session minute [now]. */
    fun dueNow(shadows: List<Shadow>, today: LocalDate, now: Int): List<Shadow> =
        shadows.filter { !it.settled && it.c.at.toLocalDate() == today && it.due <= now }

    /**
     * What is kept: every settled shadow (the newest [KEEP]) and today's waiting ones; one left unsettled from an earlier
     * day is dropped, never guessed (its price at the exit was not seen).
     */
    fun prune(shadows: List<Shadow>, today: LocalDate): List<Shadow> =
        shadows.filter { it.settled || it.c.at.toLocalDate() == today }.takeLast(KEEP)

    /** Solo's record to judge by: its closed trades and its scored shadows. */
    fun outcomes(taken: List<SelfCalibration.Outcome>, shadows: List<Shadow>): List<SelfCalibration.Outcome> =
        taken.map { it.copy(c = it.c.copy(kind = KIND)) } + shadows.mapNotNull { s -> s.points()?.let { SelfCalibration.Outcome(s.c, it) } }

    /** Whether Solo takes a trade by itself, and why not / why careful. [take] false: shadow it instead. */
    data class Decision(val take: Boolean, val action: SelfCalibration.Action, val why: SelfCalibration.Slice?) {
        /** In words, for the activity log and Boss; null when nothing in Solo's record says to be careful. */
        fun text(): String? {
            val s = why ?: return null
            val w = s.what(WHO); val r = s.record("trade")
            return when {
                action == SelfCalibration.Action.SIT_OUT -> "I'm weak on $w ($r), so Solo doesn't take those by itself; " +
                    "I follow it on paper as if taken (no order), so it can earn its way back"
                action == SelfCalibration.Action.SHRINK && take -> "my record on $w is soft ($r): one lot and no more of those today"
                action == SelfCalibration.Action.SHRINK -> "my record on $w is soft ($r): I already took one of those today, " +
                    "so I follow this one on paper as if taken (no order)"
                else -> null
            }
        }
    }

    /**
     * The trade Solo would take now, in conditions [now], judged by Solo's own [outcomes]. [today]: the conditions of the
     * trades Solo already took today (a soft condition allows [SOFT_A_DAY] of them). Only ever says no or careful.
     */
    fun decide(outcomes: List<SelfCalibration.Outcome>, now: SelfCalibration.Conditions, today: List<SelfCalibration.Conditions> = emptyList()): Decision {
        val c = now.copy(kind = KIND)
        val j = SelfCalibration.judge(outcomes, c)
        return when (j.action) {
            SelfCalibration.Action.NORMAL -> Decision(true, j.action, null)
            SelfCalibration.Action.SIT_OUT -> Decision(false, j.action, j.why)
            SelfCalibration.Action.SHRINK -> {
                val tags = j.why?.tags.orEmpty()
                val there = today.count { t -> t.at.toLocalDate() == c.at.toLocalDate() && SelfCalibration.tags(t.copy(kind = KIND)).containsAll(tags) }
                Decision(there < SOFT_A_DAY, j.action, j.why)
            }
        }
    }

    /** Lots for a Solo trade: never more than [planned]; nothing when the decision says no (learning only lowers risk). */
    fun lots(planned: Int, d: Decision): Int = if (!d.take) 0 else SelfCalibration.lots(planned, d.action).coerceIn(0, maxOf(0, planned))

    /**
     * For the evening self-review: where Solo now sits out, where it takes one a day, and what earned its way back since
     * [before] (yesterday's [SelfCalibration.sitOutKeys] of Solo's record; null: not known).
     */
    fun review(outcomes: List<SelfCalibration.Outcome>, today: LocalDate, before: Set<String>? = null): List<String> {
        val out = ArrayList<String>()
        val bad = SelfCalibration.weakest(outcomes, today)
        if (bad.isNotEmpty()) out += "I'm worse at " + bad.joinToString("; and at ") { "${it.what(WHO)} (${it.record("trade")})" } +
            " - Solo sits those out and only follows them on paper"
        val soft = SelfCalibration.soft(outcomes, today, 2)
        if (soft.isNotEmpty()) out += "Solo takes at most one a day of " + soft.joinToString(" and ") { it.what(WHO) } + " while they lose"
        if (before != null) {
            val all = SelfCalibration.slices(outcomes, today).associateBy { it.key }
            val back = before.mapNotNull { k -> all[k]?.takeIf { !it.poor } }.take(2)
            if (back.isNotEmpty()) out += "my record on " + back.joinToString(" and ") { it.what(WHO) } +
                " is no longer clearly bad, so Solo may take those by itself again"
        }
        return out
    }

    /** "Where are you weakest", Solo's part; null when Solo has nothing scored in the window. */
    fun say(outcomes: List<SelfCalibration.Outcome>, today: LocalDate): String? {
        val n = outcomes.count { o -> o.points.isFinite() && o.c.at.toLocalDate().let { it.isAfter(today.minusDays(SelfCalibration.WINDOW_DAYS)) && !it.isAfter(today) } }
        if (n == 0) return null
        if (n < SelfCalibration.MIN_ONE) return "Solo has only $n paper trade${if (n == 1) "" else "s"} scored in the last ${SelfCalibration.WINDOW_DAYS} days: " +
            "too few to judge it anywhere yet."
        val bad = SelfCalibration.weakest(outcomes, today); val soft = SelfCalibration.soft(outcomes, today, 2); val good = SelfCalibration.strongest(outcomes, today)
        val parts = ArrayList<String>()
        parts += if (bad.isEmpty() && soft.isEmpty()) "nowhere is Solo's record clearly bad yet."
            else (bad.map { "${it.what(WHO)} (${it.record("trade")}) - sat out" } + soft.map { "${it.what(WHO)} (${it.record("trade")}) - one a day at most" })
                .joinToString("; ", prefix = "weakest: ", postfix = ".")
        if (good.isNotEmpty()) parts += "Strongest: " + good.joinToString("; ") { "${it.what(WHO)} (${it.record("trade")})" } + " - a good record never makes Solo take more."
        return "From $n of my Solo trades on paper (last ${SelfCalibration.WINDOW_DAYS} days): " + parts.joinToString(" ")
    }
}
