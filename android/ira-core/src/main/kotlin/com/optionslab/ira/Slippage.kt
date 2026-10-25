package com.optionslab.ira

import java.util.Locale

/**
 * Slippage per fill (round 4, measure only): what an app-placed order meant to pay - the signal's price, the stop's
 * trigger, the limit, or the last price when it was sent - against what it filled at on average, in rupees a unit and in
 * ticks, per strategy, buy or sell, entry or exit; Zerodha's fills and the paper book's modelled ones kept apart. The app
 * records each fill after it is known ([Fill.of]); this works out the median, the 90th percentile and the count, and says
 * them. Pure: no clock, no Android, no disk. Never changes an order: it only measures.
 *
 * The sign: a cost is positive - a buy filled above the price it meant, a sell below it; a better fill is negative.
 */
object Slippage {
    enum class Leg(val key: String, val word: String) {
        ENTRY("e", "entries"), EXIT("x", "exits");
        companion object { fun of(k: String): Leg? = entries.firstOrNull { it.key == k } }
    }

    /** Where the intended price came from. */
    enum class Basis(val key: String) {
        SIGNAL("s"), LIMIT("l"), TRIGGER("t"), LTP("p");
        companion object { fun of(k: String): Basis? = entries.firstOrNull { it.key == k } }
    }

    data class Fill(
        val day: String, val at: Long, val strategy: String, val symbol: String,
        /** "BUY" or "SELL". */
        val side: String, val leg: Leg, val qty: Int, val intended: Double, val avg: Double, val tick: Double,
        val paper: Boolean, val basis: Basis,
    ) {
        val buy: Boolean get() = side.equals("BUY", true)
        /** Rupees a unit, a cost positive. */
        val rupees: Double get() = if (buy) avg - intended else intended - avg
        /** In ticks (the contract's tick; 0.05 when unknown). */
        val ticks: Double get() = rupees / (if (tick > 0) tick else 0.05)

        companion object {
            /** A fill worth keeping, or null: a price missing, not a number or not positive, no quantity. */
            fun of(day: String, at: Long, strategy: String, symbol: String, side: String, leg: Leg, qty: Int, intended: Double?,
                   avg: Double?, tick: Double, paper: Boolean, basis: Basis): Fill? {
                if (intended == null || avg == null || !intended.isFinite() || !avg.isFinite() || intended <= 0 || avg <= 0 || qty <= 0) return null
                // A price off by over half its value is a wrong intended price (a stale or different instrument), not slippage.
                if (kotlin.math.abs(avg - intended) > intended * 0.5) return null
                val s = if (side.equals("SELL", true)) "SELL" else "BUY"
                return Fill(day, at, strategy.ifBlank { "Manual" }, symbol, s, leg, qty, intended, avg, tick, paper, basis)
            }
        }
    }

    /** Fills kept in the file at most (the newest); the app trims the file to this. */
    const val KEEP = 1_000

    /**
     * The strategy a Zerodha order belongs to, from its Zerodha [tag], the arm key it was sent under ([arm], the entry door's)
     * and the label the app gave it ([owner], "Liquidity 15+5 · stop"): Liquidity, ORB, Pine, Protection, Expiry square-off,
     * Strategy or Manual.
     */
    fun strategyOf(tag: String?, arm: String?, owner: String?): String {
        val o = owner.orEmpty().lowercase(Locale.ENGLISH)
        val a = arm.orEmpty().lowercase(Locale.ENGLISH)
        return when {
            "liquidity" in o || a.startsWith("liquidity") -> "Liquidity"
            tag == "irapine" || a == "pine" || o.startsWith("pine") -> "Pine"
            tag == "iraprotect" || o.startsWith("protection") -> "Protection"
            tag == "iraorb" -> "ORB"
            tag == "iraalgoexpiry" || o.startsWith("expiry") -> "Expiry square-off"
            tag == "iraalgostrat" -> "Strategy"
            // 18 Oct: a paper strategy's live trade (tag "iraarm", the app's ArmLive), apart from the ORB family's own.
            tag == "iraarm" || a.startsWith("arm:") -> "Arms (live)"
            else -> "Manual"
        }
    }

    // ---- the figures ------------------------------------------------------------------------------------------------------

    data class Stats(val n: Int, val median: Double, val p90: Double, val medianTicks: Double, val p90Ticks: Double,
                     /** Rupees in all (a unit's slippage x the quantity), a cost positive. */
                     val total: Double)

    /** The median (the lower middle for an even count). */
    fun median(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else xs.sorted()[(xs.size - 1) / 2]

    /** The [q] quantile by nearest rank. */
    fun quantile(xs: List<Double>, q: Double): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        val rank = kotlin.math.ceil(q.coerceIn(0.0, 1.0) * s.size).toInt().coerceIn(1, s.size)
        return s[rank - 1]
    }

    /** Null for none. */
    fun stats(fills: List<Fill>): Stats? {
        if (fills.isEmpty()) return null
        val rs = fills.map { it.rupees }
        val ts = fills.map { it.ticks }
        return Stats(fills.size, median(rs), quantile(rs, 0.9), median(ts), quantile(ts, 0.9), fills.sumOf { it.rupees * it.qty })
    }

    /** Live fills needed before a measured slippage is used ([medianShare]). */
    const val MIN_MEASURED = 5

    /**
     * 18 Oct (final review F4, shadow P&L no better than live): the median slippage of Zerodha's own fills (never paper) on [leg]
     * whose intended price came from one of [bases], as a share of that price (a cost positive, never below 0): [strategy]'s own
     * fills when it has [min] of them, else every strategy's; 0 with fewer than [min] (nothing measured yet).
     */
    fun medianShare(fills: List<Fill>, leg: Leg, bases: Set<Basis>, strategy: String? = null, min: Int = MIN_MEASURED): Double {
        val all = fills.filter { !it.paper && it.leg == leg && it.basis in bases && it.intended > 0 }
        val own = if (strategy == null) emptyList() else all.filter { it.strategy == strategy }
        val use = if (own.size >= min) own else all
        if (use.size < min) return 0.0
        return median(use.map { it.rupees / it.intended }).coerceAtLeast(0.0)
    }

    /** Per strategy, then entry or exit, then buy or sell: "ORB · entries · BUY" -> its figures. Most fills first. */
    fun groups(fills: List<Fill>): List<Pair<String, Stats>> =
        fills.groupBy { "${it.strategy} · ${it.leg.word} · ${it.side}" }.mapNotNull { (k, v) -> stats(v)?.let { k to it } }
            .sortedWith(compareByDescending<Pair<String, Stats>> { it.second.n }.thenBy { it.first })

    /** Per strategy, all its fills together. */
    fun byStrategy(fills: List<Fill>): Map<String, Stats> =
        fills.groupBy { it.strategy }.mapNotNull { (k, v) -> stats(v)?.let { k to it } }.toMap()

    private fun r2(x: Double) = "%.2f".format(Locale.ENGLISH, x)
    private fun t1(x: Double) = "%.1f".format(Locale.ENGLISH, x)

    /** "median ₹0.40 (8.0 ticks), p90 ₹1.20 (24.0 ticks), 5 fills". */
    fun said(s: Stats): String = "median ₹${r2(s.median)} (${t1(s.medianTicks)} ticks), p90 ₹${r2(s.p90)} (${t1(s.p90Ticks)} ticks), " +
        "${s.n} ${if (s.n == 1) "fill" else "fills"}"

    /** The card's lines (the order-speed section): today's live fills per group, then paper's (modelled) together. */
    fun lines(fills: List<Fill>, today: String): List<String> {
        val day = fills.filter { it.day == today }
        val out = ArrayList<String>()
        groups(day.filter { !it.paper }).take(6).forEach { (k, s) -> out += "Live slippage · $k: ${said(s)}" }
        stats(day.filter { it.paper })?.let { out += "Paper slippage (modelled) · all: ${said(it)}" }
        if (out.isEmpty()) out += "Slippage: no fills measured today."
        return out
    }

    /**
     * One line for the diagnostics and the health export: live and paper apart, today and all kept. 25 Oct (app audit, Boss's
     * diagnostics 19-24 Oct: "no fills measured yet" every day): the diagnostics are written the next morning and Boss trades
     * paper only, so "paper today" was always empty while the kept paper fills were never said; now the kept paper fills are
     * said too.
     */
    fun diagLine(fills: List<Fill>, today: String): String {
        fun part(label: String, xs: List<Fill>) = stats(xs)?.let { s ->
            "$label ${r2(s.median)}/${r2(s.p90)} ₹ (${t1(s.medianTicks)}/${t1(s.p90Ticks)} ticks) n${s.n}"
        }
        val live = fills.filter { !it.paper }
        val paper = fills.filter { it.paper }
        val parts = listOfNotNull(
            part("live today", live.filter { it.day == today }), part("live kept", live),
            part("paper (modelled) today", paper.filter { it.day == today }), part("paper (modelled) kept", paper),
        ) + byStrategy(live).entries.sortedByDescending { it.value.n }.take(5).map { (k, s) -> "$k ${r2(s.median)}/${r2(s.p90)} n${s.n}" }
        return "Slippage (median/p90 a unit, a cost positive): " + parts.joinToString(" · ").ifEmpty { "no fills measured yet" }
    }

    // ---- the file line ----------------------------------------------------------------------------------------------------

    private fun clean(s: String) = s.replace('|', '/').replace('\n', ' ')

    /** "day|at|strategy|symbol|side|leg|qty|intended|avg|tick|paper|basis". */
    fun encode(f: Fill): String = listOf(f.day, f.at.toString(), clean(f.strategy), clean(f.symbol), f.side, f.leg.key, f.qty.toString(),
        f.intended.toString(), f.avg.toString(), f.tick.toString(), if (f.paper) "1" else "0", f.basis.key).joinToString("|")

    /** [encode] read back; null for anything unreadable (a damaged line is skipped, never fatal). */
    fun decode(line: String): Fill? {
        val p = line.trim().split('|')
        if (p.size < 12) return null
        return runCatching {
            Fill.of(p[0], p[1].toLong(), p[2], p[3], p[4], Leg.of(p[5]) ?: return null, p[6].toInt(), p[7].toDouble(), p[8].toDouble(),
                p[9].toDouble(), p[10] == "1", Basis.of(p[11]) ?: Basis.LTP)
        }.getOrNull()
    }

    // ---- Jarvis -----------------------------------------------------------------------------------------------------------

    /** What Boss asked about: a strategy (null: all) and whether paper. */
    data class Ask(val strategy: String?, val paper: Boolean)

    private val ASKED = Regex("\\b(?:slippage|slipage|slippages|slipped|fill quality|fill price[s]? (?:vs|versus|against)|how (?:good|bad) (?:are|were) my fills)\\b")
    private val STRATS = listOf("liquidity" to "Liquidity", "orb" to "ORB", "pine" to "Pine", "protections?" to "Protection",
        "expiry" to "Expiry square-off", "manual" to "Manual")

    /** "What's my slippage", "slippage on ORB", "paper slippage": the ask, or null when [q] is not about slippage. */
    fun asked(q: String): Ask? {
        val t = q.lowercase(Locale.ENGLISH)
        if (!ASKED.containsMatchIn(t)) return null
        // A request to change something is not a question about the figures (nothing here changes an order).
        if (Regex("\\b(?:set|change|reduce|lower|increase|turn)\\b.*\\bslippage\\b").containsMatchIn(t) && !t.contains("what")) return null
        val s = STRATS.firstOrNull { (w, _) -> Regex("\\b$w\\b").containsMatchIn(t) }?.second
        return Ask(s, Regex("\\bpaper\\b").containsMatchIn(t))
    }

    /** Jarvis's answer from the kept fills (the newest [KEEP]), today first. Words for an unlocked phone only (rupees). */
    fun answer(fills: List<Fill>, ask: Ask, today: String): String {
        val pool = fills.filter { it.paper == ask.paper && (ask.strategy == null || it.strategy == ask.strategy) }
        val what = (if (ask.paper) "paper (the sandbox's modelled) " else "") + "slippage" + (ask.strategy?.let { " on $it" } ?: "")
        val all = stats(pool) ?: return "No ${if (ask.paper) "paper" else "live"} fills measured yet for $what, Boss. " +
            "I measure each fill against the price the order meant to pay once it fills."
        val sb = StringBuilder("Your $what: ${said(all)}, ₹${r2(all.total)} in all")
        stats(pool.filter { it.day == today })?.let { sb.append("; today ${said(it)}") }
        sb.append('.')
        val parts = groups(pool).take(4)
        if (parts.size > 1) sb.append(" By kind: ").append(parts.joinToString("; ") { (k, s) -> "$k ${r2(s.median)} median, ${r2(s.p90)} p90 (${s.n})" }).append('.')
        sb.append(" A cost is positive: a buy above, or a sell below, the price it meant. Measured only; no order is changed by it.")
        return sb.toString()
    }
}
