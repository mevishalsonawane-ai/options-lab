package com.optionslab.app.data

import com.optionslab.engine.orb.HeroRules
import com.optionslab.engine.orb.LiquidityRules
import com.optionslab.engine.orb.ShadowRules
import com.optionslab.ira.ForwardCheck

/**
 * Live vs backtest ([ForwardCheck]): each running strategy's closed forward paper trades, read from its own record, set
 * against its research. Liquidity 15+5 and Hero from the arms' book ([OrbArms.closedPaper]; Liquidity per lot: a trade's
 * net over the lots it bought), Solo from its own record ([com.optionslab.app.ira.IraSolo]; one lot a trade), and for each
 * retired arm (and the midday candidate) the best of its shadows that has a research expectation pinned ([ShadowArms],
 * 1 lot, no orders). Reads only: nothing is armed, stopped or changed.
 */
object ForwardRecords {
    /** One card: the check, and whether it trades (paper) or only shadows (no orders). */
    data class Row(val result: ForwardCheck.Result, val shadow: Boolean = false, val title: String = result.expectation.name)

    /** A closed arm position's net after charges, per lot when [perLot]. */
    private fun net(p: OrbArms.Position, perLot: Boolean): Double {
        val n = (p.grossPnl ?: 0.0) - p.charges
        return if (perLot) n / p.lots else n
    }

    /** Liquidity 15+5's closed paper trades, per lot (its four books). */
    fun liquidity(closed: List<OrbArms.Position>): List<ForwardCheck.Trade> {
        val books = LiquidityRules.BOOKS.map { it.source }.toSet()
        return closed.filter { it.arm in books && !it.open && !it.live }.map { ForwardCheck.Trade(it.day, net(it, perLot = true)) }
    }

    /** The Hero arm's closed paper trades (one ₹5,000 ticket each, as its research). */
    fun hero(closed: List<OrbArms.Position>): List<ForwardCheck.Trade> =
        closed.filter { it.arm == HeroRules.ARM.source && !it.open && !it.live }.map { ForwardCheck.Trade(it.day, net(it, perLot = false)) }

    /**
     * For each retired arm and each new candidate, the shadow with the best net among those with a pinned expectation
     * ([ShadowRules.bestIndex]; a tie keeps the research's own); a variant shared by two arms (V43) shown once.
     */
    fun bestShadows(rows: List<ShadowArms.Row>): List<Row> {
        val pinned = rows.filter { it.variant.id in ForwardCheck.SHADOWS }
        val groups = LinkedHashMap<String, List<ShadowArms.Row>>()
        pinned.forEach { r ->
            val keys = r.variant.arms.map { it.source }.ifEmpty { listOf(r.variant.id) }
            keys.forEach { k -> groups[k] = groups[k].orEmpty() + r }
        }
        val seen = HashSet<String>()
        return groups.values.mapNotNull { mine ->
            val best = mine.getOrNull(ShadowRules.bestIndex(mine.map { it.summary.net })) ?: return@mapNotNull null
            if (!seen.add(best.variant.id)) return@mapNotNull null
            val trades = best.trades.filter { !it.open }.mapNotNull { t -> t.net?.let { ForwardCheck.Trade(t.day, it) } }
            Row(ForwardCheck.check(ForwardCheck.SHADOWS.getValue(best.variant.id), trades), shadow = true,
                title = "${best.variant.label} · shadow ${best.variant.name}")
        }
    }

    /**
     * Every card, in order: Liquidity 15+5 (always: it is the running arm), Solo when on or with a record, Hero when armed
     * or with forward trades, then the shadows. Reads only; a record that cannot be read counts as none.
     */
    suspend fun rows(): List<Row> {
        val closed = runCatching { OrbArms.closedPaper() }.getOrDefault(emptyList())
        val heroArmed = runCatching { OrbArms.view().arms.any { it.arm.source == HeroRules.ARM.source && it.armed } }.getOrDefault(false)
        val solo = runCatching { com.optionslab.app.ira.IraSolo.all().filter { it.midday && it.closed && it.net != null } }   // Solo (midday)'s own forward test only.getOrDefault(emptyList())
            .mapNotNull { t -> runCatching { ForwardCheck.Trade(java.time.LocalDate.parse(t.day), t.net!!) }.getOrNull() }
        val soloOn = runCatching { com.optionslab.app.ira.IraSolo.on }.getOrDefault(false)
        val out = ArrayList<Row>()
        out += Row(ForwardCheck.check(ForwardCheck.LIQUIDITY, liquidity(closed)))
        if (soloOn || solo.isNotEmpty()) out += Row(ForwardCheck.check(ForwardCheck.SOLO, solo))
        val hero = ForwardCheck.check(ForwardCheck.HERO, hero(closed))
        if (heroArmed || hero.trades > 0) out += Row(hero)
        out += bestShadows(runCatching { ShadowArms.rows() }.getOrDefault(emptyList()))
        return out
    }

    /** Jarvis's one line for [name] ("liquidity", "solo", "hero"), or null when it has no card. */
    suspend fun line(name: String): String? = rows().firstOrNull { !it.shadow && it.result.expectation.key == name }?.let { ForwardCheck.line(it.result) }
}
