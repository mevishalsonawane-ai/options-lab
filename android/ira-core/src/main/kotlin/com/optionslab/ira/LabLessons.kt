package com.optionslab.ira

import java.time.LocalDate
import java.util.Locale

/**
 * What Jarvis learns from the strategy lab (Boss, 11 Oct: "make Jarvis learn from the new strategies"): the forge's and the
 * variants' records turned into lessons that cite their counts -
 *
 *  - per building block: "Ideas using VWAP reclaim failed 23 of 24 forward tests";
 *  - per filter: "The expiry days only filter helped in 6 of 8 survivors";
 *  - per strategy parameter: "Liquidity 15+5's best stop so far is 15%; 4 variants with a wider stop lost";
 *  - the graveyard: how many ideas were tried and how few passed.
 *
 * The forge reads the same counts back as its block weights ([StrategyForge.weights]): blocks that keep failing are drawn
 * less, promising ones more, never below the exploration floor. The lessons only inform; they never weaken a gate. Pure.
 */
object LabLessons {
    enum class Kind { BLOCK, FILTER, PARAM, GRAVEYARD }

    /** One lesson: a stable [id], its words, and the counts behind it ([k] of [n]). */
    data class Lesson(val id: String, val kind: Kind, val text: String, val k: Int, val n: Int)

    /** A block needs this many tries before it earns a lesson. */
    const val MIN_TRIES = 5

    fun lessons(f: StrategyForge.Book, v: VariantLab.Book): List<Lesson> {
        val out = ArrayList<Lesson>()
        // Blocks: the forward record when there is one, else the backtest gates.
        for ((k, s) in f.blocks.toSortedMap()) {
            if (k.startsWith("filter:")) continue
            val name = StrategyForge.words(k)
            val fwd = s.fwdPassed + s.fwdFailed
            if (fwd >= 2) out += Lesson("block:$k", Kind.BLOCK,
                "Ideas using $name ${if (s.fwdFailed >= s.fwdPassed) "failed ${s.fwdFailed} of $fwd forward tests" else "passed ${s.fwdPassed} of $fwd forward tests"}.",
                if (s.fwdFailed >= s.fwdPassed) s.fwdFailed else s.fwdPassed, fwd)
            else if (s.tried >= MIN_TRIES) {
                val failed = s.tried - s.btPassed
                out += Lesson("block:$k", Kind.BLOCK,
                    if (s.btPassed == 0) "Ideas using $name failed the backtest gates in all ${s.tried} tries."
                    else "Ideas using $name passed the backtest gates in ${s.btPassed} of ${s.tried} tries ($failed failed).",
                    if (s.btPassed == 0) failed else s.btPassed, s.tried)
            }
        }
        // Filters: how often each helped the survivors that used it.
        for ((k, hs) in f.filterHelp.toSortedMap()) {
            val (helped, survivors) = hs
            if (survivors < 2) continue
            out += Lesson("filter:$k", Kind.FILTER, "${StrategyForge.words(k).replaceFirstChar { it.uppercase() }} helped in $helped of $survivors survivors.", helped, survivors)
        }
        // Parameters: the variants that lost, wider or tighter than the rules they were born against.
        for (s in VariantLab.STRATEGIES) {
            val lost = v.variants.filter { it.strategy == s.key && (it.status == VariantLab.Status.PRECHECK_FAILED ||
                (it.status == VariantLab.Status.RETIRED && !it.why.startsWith("superseded") && !it.why.startsWith("you declined"))) }
            if (lost.isEmpty()) continue
            val cur = v.currentOf(s.key)
            for (p in s.params) {
                val up = lost.count { x -> val a = x.values[p.key]; val b0 = x.base[p.key]; a != null && b0 != null && a > b0 + 1e-12 }
                val dn = lost.count { x -> val a = x.values[p.key]; val b0 = x.base[p.key]; a != null && b0 != null && a < b0 - 1e-12 }
                if (up + dn < 2) continue
                val stop = p.key.startsWith("stop")
                val more = if (stop) "a wider ${p.words}" else "a higher ${p.words}"
                val less = if (stop) "a tighter ${p.words}" else "a lower ${p.words}"
                val parts = listOfNotNull(if (up > 0) "$up variant${if (up == 1) "" else "s"} with $more lost" else null,
                    if (dn > 0) "$dn with $less lost" else null)
                out += Lesson("param:${s.key}:${p.key}", Kind.PARAM,
                    "${s.label}'s best ${p.words} so far is ${p.say(cur[p.key] ?: s.defaults[p.key] ?: p.min)}; ${parts.joinToString(", ")}.",
                    up + dn, lost.size)
            }
        }
        val variantsTried = v.tried
        if (f.tried + variantsTried > 0) out += Lesson("graveyard", Kind.GRAVEYARD,
            "Of ${f.tried} new idea${if (f.tried == 1) "" else "s"} tried, ${f.passed} passed the backtest gates, ${f.retired} retired after a forward test " +
                "and ${f.proposed} reached you; of $variantsTried strategy variant${if (variantsTried == 1) "" else "s"}, " +
                "${v.changes.count { it.status == VariantLab.ChangeStatus.APPLIED || it.status == VariantLab.ChangeStatus.ACCEPTED }} changed a strategy.",
            f.passed, f.tried)
        return out
    }

    /**
     * The lab's records as Jarvis's evidence ([AgentLessons.LabResult]; his nightly digest forms "lab:" lessons from them with
     * the same confidence and Benjamini-Hochberg rules as every other agent's), one result per test, dated by the day it was
     * settled (an idea's stage day, a filter's birth) or last judged (a variant):
     *
     *  - each settled forge idea (not one still in its forward test), once per building block it used (entry and exit):
     *    passed = it passed the backtest and was not retired by its forward test; rupees = its forward net when it has one,
     *    else its backtest's;
     *  - each forge survivor, once per filter it used: passed = the filter made it better than without;
     *  - each decided variant, once per parameter it moved: passed = promoted, failed = worse on the stored days or retired.
     *
     * Only days on or after [since] (null: all): the forge keeps only its newest rejected ideas, so an older day read again
     * would be thinner than it was; Jarvis's book keeps the days it already read.
     */
    fun results(f: StrategyForge.Book, v: VariantLab.Book, since: LocalDate? = null): List<AgentLessons.LabResult> {
        val out = ArrayList<AgentLessons.LabResult>()
        for (r in f.records) {
            val born = r.c.born
            val bt = r.bt ?: continue
            if (bt.gates.isEmpty()) continue
            val passed = bt.passed && r.stage != StrategyForge.Stage.RETIRED
            val rs = if (r.fwd.isNotEmpty()) r.fwdNet else bt.net
            // An idea's block result is dated by the day it was settled (G6-3): its stage's day ([StrategyForge.Record.since]).
            // One still in its forward test is not settled - not a result yet: dated by its birth it was read once as a pass,
            // and its retirement 20 to 60 days later fell on a day never read again, so a failed idea counted as passed.
            val day = r.since ?: born
            val settled = r.stage != StrategyForge.Stage.FORWARD
            if (settled && (since == null || !day.isBefore(since))) for (k in r.c.blocks.distinct()) {
                if (k.startsWith("filter:")) continue
                val w = StrategyForge.words(k)
                out += AgentLessons.LabResult(day, "block:$k", "Ideas using $w", passed, rs, 1, w.lowercase(Locale.ENGLISH).split(' ').filter { it.length > 2 })
            }
            // A filter's result is its backtest's (it never changes): dated by the idea's birth.
            if (bt.passed && (since == null || !born.isBefore(since))) for (fl in r.c.rules.filters) {
                val k = StrategyForge.key(fl)
                val w = StrategyForge.words(k)
                out += AgentLessons.LabResult(born, k, w.replaceFirstChar { it.uppercase() }, fl in bt.helped, 0.0, 1, listOf("filter", fl.name.lowercase(Locale.ENGLISH)))
            }
        }
        for (s in VariantLab.STRATEGIES) for (x in v.variants) {
            if (x.strategy != s.key) continue
            val lost = x.status == VariantLab.Status.PRECHECK_FAILED ||
                (x.status == VariantLab.Status.RETIRED && !x.why.startsWith("superseded") && !x.why.startsWith("you declined"))
            val won = x.status == VariantLab.Status.PROMOTED
            if (!lost && !won) continue
            val day = x.lastDay ?: x.born
            if (since != null && day.isBefore(since)) continue
            for (p in s.params) {
                val a = x.values[p.key] ?: continue
                val b0 = x.base[p.key] ?: continue
                if (kotlin.math.abs(a - b0) <= 1e-12) continue
                val stop = p.key.startsWith("stop")
                val dir = if (a > b0) (if (stop) "wider" else "higher") else (if (stop) "tighter" else "lower")
                out += AgentLessons.LabResult(day, "param:${s.key}:${p.key}:$dir", "${s.label} with a $dir ${p.words}", won, x.vSum - x.cSum, 1,
                    listOf(s.key, "variant", p.key.lowercase(Locale.ENGLISH)))
            }
        }
        return out
    }

    /** Lessons as short notes for the model or the findings bus (at most [n], the strongest-counted first). */
    fun notes(ls: List<Lesson>, n: Int = 5): List<String> = ls.sortedByDescending { it.n }.take(n).map { it.text }

    /**
     * The blocks the forge leans toward next (best posterior mean first), with their counts. Only the entries it draws by
     * default (14 Oct: read from the index, [StrategyForge.entriesFor]) - never one it would not try.
     */
    fun exploring(f: StrategyForge.Book, n: Int = 3): List<Pair<String, String>> {
        val w = StrategyForge.weights(f)
        return StrategyForge.entriesFor().map { StrategyForge.key(it) }.sortedByDescending { StrategyForge.mean(w.getValue(it)) }.take(n).map { k ->
            val s = f.blocks[k]
            k to (StrategyForge.words(k) + if (s == null || s.tried == 0) " (not tried yet)" else " (${s.btPassed} of ${s.tried} passed the backtest)")
        }
    }

    /** The blocks that keep failing (most failures first; at least [MIN_TRIES] tries), with their counts. */
    fun failing(f: StrategyForge.Book, n: Int = 4): List<String> =
        f.blocks.filter { (k, s) -> !k.startsWith("filter:") && s.tried >= MIN_TRIES }.entries
            .sortedByDescending { (it.value.failures) / (it.value.tried + it.value.fwdPassed + it.value.fwdFailed).coerceAtLeast(1) }.take(n)
            .map { (k, s) -> "${StrategyForge.words(k)}: failed ${s.tried - s.btPassed} of ${s.tried} backtests" +
                (if (s.fwdFailed > 0) ", ${s.fwdFailed} forward test${if (s.fwdFailed == 1) "" else "s"} lost" else "") }

    internal fun pct(x: Double) = "%.0f%%".format(Locale.ENGLISH, x * 100)
}
