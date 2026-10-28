package com.optionslab.ira

import java.util.Locale

/**
 * Boss's win-rate rule for automatic ideas (28 Oct: "Dont create a pine script or stratergies automatically, until 65% +
 * winrate is found"). One shared gate for every creator that makes, arms or proposes a NEW strategy or Pine script on its
 * own: the forge's proposals ([StrategyForge.forwardDay]), the strategy lab's variant promotions ([VariantLab.judge]), the
 * shadow variants put to Boss for a paper re-arm ([com.optionslab.engine.orb.ShadowRules.promotionDue]) and Jarvis's own
 * Pine scripts (the app's pattern lab, [StrategyLab.backtest]).
 *
 * An idea passes only when ALL of these hold, on top of every gate its creator already has (this rule is added to them and
 * never replaces them):
 *  - its record is out of sample (a forward record or a walk-forward test; an in-sample backtest is held);
 *  - it is counted after real charges and slippage;
 *  - it has at least its creator's own minimum of trades ([Creator.minTrades]; [FALLBACK_MIN_TRADES] where it has none);
 *  - it is still profitable after costs (a high win rate with a losing average stays held);
 *  - its win rate is at least Boss's minimum ([Rule.minPct], [DEFAULT_PCT] unless Boss changes it with the PIN).
 *
 * A held idea is not created, armed or traded, on paper or live: it is kept as a note ([held]'s words) for the Autopilot
 * change log and the Strategy lab. Things Boss creates or switches on by hand never pass through here, and arms already
 * running are never switched off by it. Pure: no clock, no I/O.
 */
object IdeaWinRate {
    /** Boss's minimum (28 Oct). */
    const val DEFAULT_PCT = 65.0
    /** The minimum of trades for a creator that has none of its own. */
    const val FALLBACK_MIN_TRADES = 30
    /** The minimum Boss may choose: never below a coin's 50%, never so high that nothing could ever pass. */
    const val MIN_CHOICE = 50.0
    const val MAX_CHOICE = 90.0
    /** The settings chips. */
    val CHOICES = listOf(55.0, 60.0, 65.0, 70.0, 75.0, 80.0)

    /** The rule as the app holds it: [on] (undone from the change log: off) and Boss's [minPct]. */
    data class Rule(val on: Boolean = true, val minPct: Double = DEFAULT_PCT) {
        companion object {
            val DEFAULT = Rule()
            /** The behaviour before 28 Oct (the change log's undo): no win-rate check at all. */
            val OFF = Rule(on = false)
        }
    }

    /** Who made the idea, with its own minimum of trades. */
    enum class Creator(val words: String, val minTrades: Int) {
        FORGE("Forge idea", StrategyForge.FWD_MIN_TRADES),
        VARIANT("Strategy lab variant", FALLBACK_MIN_TRADES),
        SHADOW("Shadow variant", com.optionslab.engine.orb.ShadowRules.PROMOTE_TRADES),
        PINE("Jarvis's Pine script", StrategyLab.MIN_TRADES),
    }

    /** Where the record comes from. */
    enum class Sample(val words: String) {
        OUT_OF_SAMPLE("out of sample"),
        IN_SAMPLE("in-sample only"),
    }

    /**
     * An idea's own tested record: [trades] closed, [wins] of them made money after costs, [avg] the average a trade after
     * costs (rupees), [sample] where the record comes from, [afterCosts] whether charges and slippage are in it.
     */
    data class Evidence(val trades: Int, val wins: Int, val avg: Double, val sample: Sample, val afterCosts: Boolean) {
        val winPct: Double get() = if (trades <= 0) 0.0 else 100.0 * wins / trades
    }

    /** [nets] (each closed trade's rupees after costs) as evidence. */
    fun fromNets(nets: List<Double>, sample: Sample = Sample.OUT_OF_SAMPLE, afterCosts: Boolean = true): Evidence =
        Evidence(nets.size, nets.count { it > 0 }, if (nets.isEmpty()) 0.0 else nets.average(), sample, afterCosts)

    /** A shadow variant's forward record (its closed trades after the shadow charges and slippage). */
    fun forShadow(nets: List<Double>): Evidence = fromNets(nets)

    /** A forge idea's forward test on the live days (after the paper costs and half a spread more). */
    fun forForge(fwd: List<Double>): Evidence = fromNets(fwd)

    /**
     * Jarvis's pattern-lab backtest ([StrategyLab.Result]): run on the whole history with no holdout and no forward record,
     * its win rate counted in index points before charges, so it is in-sample (and costed only where the options were priced).
     */
    fun forPine(r: StrategyLab.Result): Evidence {
        val wins = Math.round(r.winRate * r.trades / 100.0).toInt()
        val avg = r.rupees?.let { if (r.rupeeTrades > 0) it / r.rupeeTrades else 0.0 } ?: r.avgPoints
        return Evidence(r.trades, wins, avg, Sample.IN_SAMPLE, afterCosts = r.rupees != null)
    }

    /** A variant's own forward trades (counted by the lab since it was born, after the lab's costs). */
    fun forVariant(trades: Int, wins: Int, net: Double): Evidence = trades.coerceAtLeast(0).let { n ->
        Evidence(n, wins.coerceIn(0, n), if (n == 0) 0.0 else net / n, Sample.OUT_OF_SAMPLE, afterCosts = true)
    }

    /**
     * Why [creator]'s idea with [e] is held under [rule] (the note's words), or null when it may go ahead. Called only once
     * the creator's own gates have passed: this only ever adds a reason to stop.
     */
    fun held(creator: Creator, e: Evidence, rule: Rule = Rule.DEFAULT): String? {
        if (!rule.on) return null
        val min = minOf(MAX_CHOICE, maxOf(MIN_CHOICE, rule.minPct))
        val yours = "your ${pct(min)} rule"
        return when {
            e.sample != Sample.OUT_OF_SAMPLE -> "held: tested ${e.sample.words} (${countWords(e)}); $yours needs an out-of-sample record"
            !e.afterCosts -> "held: not counted after charges and slippage (${countWords(e)}); $yours needs that"
            e.trades < creator.minTrades -> "held: only ${e.trades} trade${if (e.trades == 1) "" else "s"}, under the ${creator.minTrades} $yours needs"
            e.avg <= 0.0 -> "held: loses after costs (${DiscoveryStats.rs(e.avg)} a trade, win rate ${pct(e.winPct)} (${e.trades})); $yours needs a profit too"
            e.winPct < min - 1e-9 -> "held: win rate ${pct(e.winPct)} (${e.trades}) below $yours"
            else -> null
        }
    }

    /** Whether it may go ahead ([held] is null). */
    fun passes(creator: Creator, e: Evidence, rule: Rule = Rule.DEFAULT): Boolean = held(creator, e, rule) == null

    private fun countWords(e: Evidence) = "win rate ${pct(e.winPct)} (${e.trades})"

    /** "65%", "64.9%". */
    fun pct(x: Double): String {
        val r = Math.round(x * 10) / 10.0
        return if (r == Math.floor(r)) "${r.toLong()}%" else String.format(Locale.ENGLISH, "%.1f%%", r)
    }

    // ---- the notes (the Autopilot change log and the Strategy lab) ----------------------------------------------------------

    /** The held ideas in [book] (each a note: [AutoChange.Kind.IDEA_HELD]), newest first. */
    fun heldOf(book: AutoChange.Book): List<AutoChange.Entry> =
        book.entries.filter { it.change.kind == AutoChange.Kind.IDEA_HELD }.sortedByDescending { it.atMs }

    /** One held idea in a line: "Forge idea F12 …: held: win rate 64.9% (40) below your 65% rule". */
    fun heldLine(e: AutoChange.Entry): String = "${e.change.what}: ${e.change.reason}"

    /** The Strategy lab card's line (null: nothing held). */
    fun heldSummary(held: List<AutoChange.Entry>): String? =
        if (held.isEmpty()) null else "Held by your win-rate rule: ${held.size} automatic idea${if (held.size == 1) "" else "s"} (notes only, nothing made)"

    // ---- Boss's setting ("Automatic ideas: minimum win rate") ----------------------------------------------------------------

    /** What Settings shows. */
    fun settingLine(rule: Rule): String =
        "Automatic ideas: minimum win rate " + (if (rule.on) pct(rule.minPct) else "off (undone in the change log)")

    const val PIN_LINE = "Changing the minimum win rate for automatic ideas needs your app PIN. Nothing changed."

    /**
     * Boss changing the minimum to [next]: every change needs the PIN ([pinConfirmed]), up or down; a value outside
     * [MIN_CHOICE]..[MAX_CHOICE] is refused. Returns the new rule, or the words refusing it.
     */
    fun change(cur: Rule, next: Double, pinConfirmed: Boolean): Pair<Rule?, String> = when {
        !next.isFinite() || next < MIN_CHOICE - 1e-9 || next > MAX_CHOICE + 1e-9 ->
            null to "The minimum win rate must be between ${pct(MIN_CHOICE)} and ${pct(MAX_CHOICE)}. Nothing changed."
        !pinConfirmed -> null to PIN_LINE
        else -> cur.copy(minPct = next) to "Automatic ideas now need a win rate of at least ${pct(next)} (out of sample, after costs)."
    }
}
