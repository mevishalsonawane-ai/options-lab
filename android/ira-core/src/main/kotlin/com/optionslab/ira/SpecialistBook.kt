package com.optionslab.ira

import java.util.Locale
import kotlin.math.abs

/**
 * The specialists' learning loop ([ManagerTeam]; Boss, 10 Oct): every decision the manager made or would have made names the
 * specialist that led it and every one that voted for it ([TradeManager.Note.lead], [TradeManager.Note.voters]); when the
 * trade settles against the original rules (the counterfactual on the option's 1-minute wicks, [TradeManager.Record.vsOriginal])
 * each of them is credited or debited with it ([outcomes]). Muted specialists' votes are kept too, so their record goes on.
 *
 *  - The report card per strategy and specialist ([cards]): decisions voted in and led, rupees helped and hurt, hit rate.
 *  - Self-tuning ([tune], once a night, bounded and slow): only once a specialist has [MIN_SETTLED] settled decisions for that
 *    strategy; its weight moves at most [STEP] (10%) a day and stays within [MIN_X]..[MAX_X] of its default; a clearly
 *    negative record (hurt more than helped over [MIN_SETTLED] or more) mutes it for that strategy, with the reason. Boss
 *    un-mutes by hand ([unmute]); it is then not muted again until [MIN_SETTLED] more decisions say so.
 *  - The app applies tuning to SHADOW and paper ACT only; LIVE ACT keeps frozen weights that change only when Boss accepts the
 *    suggestion with his PIN. Tuning never touches the hard vetoes, the lock, the holds or any invariant: only weights and mutes.
 *  - The nightly line per strategy ([nightLine]) and Jarvis's answers ([answerWhich], [answerHow]).
 * Pure.
 */
object SpecialistBook {
    const val MIN_SETTLED = 30
    const val STEP = 0.10
    const val MIN_X = 0.25
    const val MAX_X = 2.0

    /** One specialist's part in one settled trade: it voted for a decision in it ([led]: it led one), worth [rs] against the original rules. */
    data class Outcome(val family: String, val account: TradeManager.Account, val specialist: String, val tradeId: String,
                       val atMs: Long, val kind: String, val led: Boolean, val rs: Double)

    private fun decisionNotes(r: TradeManager.Record) = r.notes.filter { it.kind == TradeManager.EXIT_EARLY || it.kind == TradeManager.EXTEND_NOTE }

    /** Every specialist's part in every settled trade (one per specialist and trade). */
    fun outcomes(records: List<TradeManager.Record>): List<Outcome> {
        val out = ArrayList<Outcome>()
        for (r in records) {
            val vs = r.vsOriginal ?: continue
            val notes = decisionNotes(r)
            if (notes.isEmpty()) continue
            val ids = LinkedHashSet<String>()
            for (n in notes) { ids += n.voters; n.lead?.let { ids += it } }
            for (id in ids) {
                val mine = notes.filter { id in it.voters || it.lead == id }
                out += Outcome(r.trade.family, r.trade.account, id, r.trade.tradeId, mine.last().atMs, mine.last().kind, mine.any { it.lead == id }, vs)
            }
        }
        return out
    }

    /** A specialist's report card for one strategy. */
    data class Card(val family: String, val specialist: String, val votes: Int, val led: Int, val settled: Int,
                    val helped: Double, val hurt: Double, val wins: Int) {
        val net: Double get() = helped - hurt
        val hitRate: Double? get() = if (settled == 0) null else wins.toDouble() / settled
    }

    /** The report cards per strategy (paper and live together: a specialist reads the same market on both), committee order. */
    fun cards(records: List<TradeManager.Record>): List<Card> {
        val votes = HashMap<Pair<String, String>, IntArray>()
        for (r in records) for (n in decisionNotes(r)) {
            for (id in (n.voters + listOfNotNull(n.lead)).distinct()) {
                val a = votes.getOrPut(r.trade.family to id) { IntArray(2) }
                if (id in n.voters) a[0]++
                if (n.lead == id) a[1]++
            }
        }
        val settled = outcomes(records).groupBy { it.family to it.specialist }
        val keys = (votes.keys + settled.keys).toSortedSet(compareBy<Pair<String, String>>({ famOrder(it.first) }, { it.first },
            { ManagerTeam.IDS.indexOf(it.second).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.second }))
        return keys.map { k ->
            val s = settled[k].orEmpty()
            val v = votes[k] ?: IntArray(2)
            Card(k.first, k.second, v[0], v[1], s.size, s.filter { it.rs > 0 }.sumOf { it.rs }, s.filter { it.rs < 0 }.sumOf { -it.rs }, s.count { it.rs > 0 })
        }
    }

    private fun famOrder(f: String) = TradeManager.FAMILIES.indexOfFirst { it.key == f }.let { if (it < 0) Int.MAX_VALUE else it }

    /**
     * One strategy's tuning: the weights the self-tuning set ([weights]; absent: the default's), the muted specialists with
     * the reason each ([muted]), the specialists Boss un-muted by hand with their settled count then ([handUnmuted]), and
     * the last day it was tuned ([lastDay], "2026-10-10": once a day).
     */
    data class Tuning(val weights: Map<String, Double> = emptyMap(), val muted: Map<String, String> = emptyMap(),
                      val handUnmuted: Map<String, Int> = emptyMap(), val lastDay: String? = null)

    data class Tuned(val tuning: Tuning, val changes: List<String>, val newlyMuted: List<Pair<String, String>>)

    /**
     * Tonight's step for [family] from its [cards] on [day]: each specialist with [MIN_SETTLED] settled decisions or more moves
     * its weight one [STEP] up (ahead of the original rules, right at least half the time) or down (behind them), kept within
     * [MIN_X]..[MAX_X] of [base]'s default; one that hurt more than it helped is muted with the reason (not again within
     * [MIN_SETTLED] decisions of Boss un-muting it). Nothing the second time on the same day.
     */
    fun tune(family: String, base: ManagerConfig, cur: Tuning, cards: List<Card>, day: String): Tuned {
        if (cur.lastDay == day) return Tuned(cur, emptyList(), emptyList())
        val weights = HashMap(cur.weights)
        val muted = LinkedHashMap(cur.muted)
        val changes = ArrayList<String>()
        val newly = ArrayList<Pair<String, String>>()
        for (c in cards) {
            if (c.family != family || c.settled < MIN_SETTLED || c.specialist !in ManagerTeam.IDS) continue
            val def = base.spec(c.specialist).weight
            if (def > 0) {
                val w0 = weights[c.specialist] ?: def
                val up = c.net > 0 && (c.hitRate ?: 0.0) >= 0.5
                val down = c.net < 0
                val w1 = when { up -> w0 * (1 + STEP); down -> w0 * (1 - STEP); else -> w0 }.coerceIn(MIN_X * def, MAX_X * def)
                if (abs(w1 - w0) > 1e-9) {
                    weights[c.specialist] = w1
                    changes += "${ManagerTeam.nameOf(c.specialist)} weight ${w(w0)} → ${w(w1)}"
                }
            }
            val again = cur.handUnmuted[c.specialist]?.let { c.settled >= it + MIN_SETTLED } ?: true
            if (c.hurt > c.helped && c.specialist !in muted && again) {
                val why = "hurt ${TradeManager.rs(-c.hurt)} against ${TradeManager.rs(c.helped)} helped over ${c.settled} decisions"
                muted[c.specialist] = why
                newly += c.specialist to why
            }
        }
        return Tuned(Tuning(weights, muted, cur.handUnmuted, day), changes, newly)
    }

    private fun w(x: Double) = String.format(Locale.ENGLISH, "%.2f", x)

    /** Boss mutes [id] ([why] kept): no PIN - muting only moves the manager back toward the original rules. */
    fun mute(cur: Tuning, id: String, why: String): Tuning = cur.copy(muted = cur.muted + (id to why))

    /** Boss un-mutes [id] with its settled count now ([settled]): not muted again by itself until [MIN_SETTLED] more. */
    fun unmute(cur: Tuning, id: String, settled: Int): Tuning = cur.copy(muted = cur.muted - id, handUnmuted = cur.handUnmuted + (id to settled))

    /** [base] with [tuning]'s weights and mutes (a muted specialist does not count; its hard veto still stands). */
    fun apply(base: ManagerConfig, tuning: Tuning?): ManagerConfig {
        if (tuning == null || (tuning.weights.isEmpty() && tuning.muted.isEmpty())) return base
        var c = base
        for ((id, x) in tuning.weights) if (id in ManagerTeam.IDS) c = c.with(id) { it.copy(weight = x) }
        for (id in tuning.muted.keys) if (id in ManagerTeam.IDS) c = c.with(id) { it.copy(enabled = false) }
        return c.bounded()
    }

    /** What tuning [tuned] would change against the [frozen] one (LIVE ACT's): "VWAP weight 0.25 → 0.30; OI muted". Empty: nothing. */
    fun suggestion(tuned: Tuning?, frozen: Tuning?, base: ManagerConfig): List<String> {
        val out = ArrayList<String>()
        for (id in ManagerTeam.IDS) {
            val a = frozen?.weights?.get(id) ?: base.spec(id).weight
            val b = tuned?.weights?.get(id) ?: base.spec(id).weight
            if (abs(a - b) > 1e-9) out += "${ManagerTeam.nameOf(id)} weight ${w(a)} → ${w(b)}"
            val ma = frozen?.muted?.containsKey(id) == true
            val mb = tuned?.muted?.containsKey(id) == true
            if (ma != mb) out += "${ManagerTeam.nameOf(id)} ${if (mb) "muted" else "un-muted"}"
        }
        return out
    }

    // ---- words ----------------------------------------------------------------------------------------------------------

    /** "VWAP: 34 votes, led 6, helped +₹1,240, hurt −₹310, hit rate 62% (34 settled)". */
    fun cardLine(c: Card, weight: Double? = null, muted: String? = null): String =
        "${ManagerTeam.nameOf(c.specialist)}: ${c.votes} vote${if (c.votes == 1) "" else "s"}, led ${c.led}, " +
            (if (c.settled == 0) "nothing settled yet" else "helped ${TradeManager.rs(c.helped)}, hurt ${TradeManager.rs(-c.hurt)}, " +
                "hit rate ${((c.hitRate ?: 0.0) * 100).toInt()}% (${c.settled} settled)") +
            (weight?.let { " · weight ${w(it)}" } ?: "") + (muted?.let { " · muted: $it" } ?: "")

    private fun names(ids: List<String>): String = ids.map { ManagerTeam.nameOf(it) }.let {
        when (it.size) { 0 -> ""; 1 -> it[0]; else -> it.dropLast(1).joinToString(", ") + " and " + it.last() }
    }

    /**
     * The nightly line for [family]: "Liquidity: VWAP and Volume specialists helped +₹1,240 over 34 trades; OI hurt −₹310,
     * muted". [trades]: its settled managed trades.
     */
    fun nightLine(family: String, cards: List<Card>, tuning: Tuning?, trades: Int): String {
        val mine = cards.filter { it.family == family && it.settled > 0 }
        val name = TradeManager.familyName(family)
        if (mine.isEmpty()) return "$name: no settled decision for its specialists yet."
        val good = mine.filter { it.net > 0 }.sortedByDescending { it.net }.take(2)
        val bad = mine.filter { it.net < 0 }.sortedBy { it.net }.take(2)
        val parts = ArrayList<String>()
        if (good.isNotEmpty()) parts += "${names(good.map { it.specialist })} specialist${if (good.size == 1) "" else "s"} helped " +
            "${TradeManager.rs(good.sumOf { it.net })} over $trades trade${if (trades == 1) "" else "s"}"
        for (b in bad) parts += "${ManagerTeam.nameOf(b.specialist)} hurt ${TradeManager.rs(b.net)}" + if (tuning?.muted?.containsKey(b.specialist) == true) ", muted" else ""
        if (parts.isEmpty()) parts += "its specialists broke even over $trades trade${if (trades == 1) "" else "s"}"
        return "$name: " + parts.joinToString("; ")
    }

    /** "Which specialist made Solo exit?": the newest early exit (or would-be one) of [family] - who led it and who agreed. */
    fun answerWhich(family: String?, records: List<TradeManager.Record>, hhmm: (Long) -> String): String {
        val mine = records.filter { family == null || it.trade.family == family }
        val r = mine.lastOrNull { it.notes.any { n -> n.kind == TradeManager.EXIT_EARLY || n.kind == TradeManager.LOCK_NOTE } }
        val who = family?.let { TradeManager.familyName(it) }
        if (r == null) return if (who == null) "No managed trade has exited early yet, Boss." else "$who has not exited early under the trade manager yet, Boss."
        val n = r.notes.last { it.kind == TradeManager.EXIT_EARLY || it.kind == TradeManager.LOCK_NOTE }
        val head = "${r.trade.label} ${if (n.acted) "exited" else "would have exited (shadow)"} at ${hhmm(n.atMs)}"
        if (n.kind == TradeManager.LOCK_NOTE) return "$head on the profit lock - the manager's own lock, not a specialist's vote: ${n.words}."
        val lead = n.lead?.let { ManagerTeam.nameOf(it) }
        val others = n.voters.filter { it != n.lead }.map { ManagerTeam.nameOf(it) }
        return "$head: " + (lead?.let { "the $it specialist led it" } ?: "the original rules led it (before the specialists)") +
            " - ${n.words}." + (if (others.isNotEmpty()) " Also voting to exit: ${others.joinToString(", ")}." else "") +
            (r.vsOriginal?.let { " Against the original rules: ${TradeManager.rs(it)}." } ?: " The original rules are still being followed to compare.")
    }

    /** "How are the manager's specialists doing?": the night lines per strategy, then the cards that have settled decisions. */
    fun answerHow(family: String?, records: List<TradeManager.Record>, tunings: Map<String, Tuning>): String {
        val cs = cards(records).filter { family == null || it.family == family }
        if (cs.isEmpty()) return "The trade manager's specialists have not voted on a decision yet, Boss" +
            (family?.let { " for ${TradeManager.familyName(it)}" } ?: "") + "."
        val fams = cs.map { it.family }.distinct()
        val lines = fams.map { f ->
            val trades = records.count { it.trade.family == f && it.vsOriginal != null && decisionNotes(it).isNotEmpty() }
            nightLine(f, cs, tunings[f], trades)
        }
        val detail = cs.filter { it.settled > 0 || it.led > 0 }.sortedByDescending { abs(it.net) }.take(6)
            .map { "${TradeManager.familyName(it.family)} · " + cardLine(it, null, tunings[it.family]?.muted?.get(it.specialist)) }
        return "The trade manager's specialists, Boss:\n" + lines.joinToString("\n") { "• $it" } +
            (if (detail.isNotEmpty()) "\n" + detail.joinToString("\n") { "• $it" } else "") +
            "\nWeights tune themselves slowly on paper and shadow only; live trades keep frozen weights until you accept a change with your PIN."
    }
}
