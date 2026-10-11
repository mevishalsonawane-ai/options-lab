package com.optionslab.ira

import com.optionslab.engine.sandbox.SandboxCosts
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.abs

/**
 * Pre-flight replay (11 Oct, "smarter learning"): before Autopilot applies any change on PAPER or LIVE by itself, the proposed
 * setting is replayed on the history the app already keeps, and the change goes ahead only if the replay is not worse.
 *
 * What is replayed ([cases]), case by case, "with" the change against "without" it:
 *  - the trade manager's mode (ACT <-> SHADOW) and a specialist's mute: the trade manager's own records
 *    ([TradeManager.Record]) carry both ends of every managed trade - where the manager's way ended and where the original
 *    rules ended - each already settled on the option's 1-minute wicks ([TradeManager.walk], the counterfactual the
 *    manager's host settles every closed trade with). ACT -> SHADOW takes the original end, SHADOW -> ACT the manager's;
 *    a mute replays every decision the specialist led as never taken (the original end); an un-mute the other way.
 *  - a paper strategy's new entries paused or resumed: its closed paper trades (each [Insights.Trip.net] already after the
 *    paper account's charges): paused, each is 0.
 *  - the order flow's mode CONFIRM -> SHADOW: its shadow log's paper signals that CONFIRM skipped (taken: their paper net).
 *  - anything else (a live park, the self-review's thresholds, the knobs) has nothing stored to replay it on: it says so
 *    ([Verdict.NOT_REPLAYABLE]) and is judged on its lesson and its probation, as before.
 *
 * Costs are the paper account's own ([SandboxCosts.charge], NSE F&O or the MCX schedule by the strategy): an exit at another
 * price pays another sell leg. The app no longer keeps a Dhan store, and the market recorder keeps the chain every 5 minutes,
 * not each option's 1-minute bars - so the wicks a replay reads are those each record was settled on when it closed.
 *
 * The last [N] cases (by time) count. The change is not worse when its rupees with it are at least those without, less a
 * tolerance ([tolerance]: [TOL_MIN_RS] or [TOL_SHARE] of the rupees at stake, whichever is more). Deterministic (sorted by
 * time, then id), pure, off the main thread in the app, with a time budget ([BUDGET_MS]): a replay over it is skipped and
 * says so ([Verdict.TIMEOUT]) - and so is its change.
 */
object Preflight {
    /** The settled cases a replay reads (the newest). */
    const val N = 40
    /** A replay may take at most this long; over it, the change is skipped with a note. */
    const val BUDGET_MS = 200L
    /** The tolerance: at least this many rupees ... */
    const val TOL_MIN_RS = 100.0
    /** ... or this share of the rupees at stake without the change (the sum of their sizes), whichever is more. */
    const val TOL_SHARE = 0.05

    enum class Verdict(val words: String) {
        PASSED("passed"), FAILED("pre-flight failed"), NO_DATA("nothing stored to replay"), NOT_REPLAYABLE("not replayable"), TIMEOUT("over its time budget"),
    }

    /** The history a replay reads: the trade manager's records, the closed paper and live trades, the order flow's shadow log. */
    data class Data(
        val records: List<TradeManager.Record> = emptyList(),
        val paperTrips: List<Insights.Trip> = emptyList(),
        val liveTrips: List<Insights.Trip> = emptyList(),
        val signals: List<FlowShadow.Signal> = emptyList(),
    )

    /** One case: when, its rupees without the change and with it. */
    data class Case(val atMs: Long, val id: String, val without: Double, val with: Double) {
        val diff: Double get() = with - without
    }

    data class Result(val verdict: Verdict, val n: Int, val without: Double, val with: Double, val tolerance: Double, val ms: Long) {
        /** May the change go ahead? (Worse, or over its budget: no.) */
        val ok: Boolean get() = verdict != Verdict.FAILED && verdict != Verdict.TIMEOUT

        /** "pre-flight passed: 40 cases replayed, +Rs 1,200 with the change against -Rs 300 without". */
        fun words(): String = when (verdict) {
            Verdict.PASSED, Verdict.FAILED -> "${if (verdict == Verdict.PASSED) "pre-flight passed" else "pre-flight failed"}: $n case${if (n == 1) "" else "s"} replayed, " +
                "${AgentLessons.rs(with)} with the change against ${AgentLessons.rs(without)} without" +
                (if (verdict == Verdict.FAILED) " (worse by more than the Rs ${String.format(Locale.ENGLISH, "%,.0f", tolerance)} allowed)" else "")
            Verdict.NO_DATA -> "pre-flight: nothing stored to replay it on yet"
            Verdict.NOT_REPLAYABLE -> "pre-flight: no stored history can replay this setting"
            Verdict.TIMEOUT -> "pre-flight skipped: the replay ran over its $BUDGET_MS ms budget"
        }
    }

    private fun isMcx(family: String) = family.startsWith("mcx") || family.startsWith("silver")

    /** The rupees of a managed trade closed at [price]: its move times its size, less the sell leg's charges at that price. */
    private fun net(r: TradeManager.Record, price: Double): Double {
        val t = r.trade
        val q = t.qty
        val sell = if (price > 0 && q > 0) runCatching {
            SandboxCosts.charge("SELL", BigDecimal(price), q, BigDecimal.ONE, if (isMcx(t.family)) "MCX" else "NFO", t.instrument).toDouble()
        }.getOrDefault(0.0) else 0.0
        return (price - t.entry) * q - sell
    }

    private fun recordAt(r: TradeManager.Record): Long = r.actual?.atMs ?: r.trade.entryMs

    private fun managerCase(r: TradeManager.Record, managerWay: Boolean): Case? {
        val m = r.manager ?: return null
        val o = r.original ?: return null
        val mn = net(r, m.price); val on = net(r, o.price)
        // [managerWay]: the change takes the manager's end (SHADOW -> ACT, un-mute); else the original's.
        return if (managerWay) Case(recordAt(r), r.trade.tradeId, on, mn) else Case(recordAt(r), r.trade.tradeId, mn, on)
    }

    private fun decisionLedBy(r: TradeManager.Record, id: String): Boolean =
        r.notes.any { (it.kind == TradeManager.EXIT_EARLY || it.kind == TradeManager.EXTEND_NOTE) && it.lead == id }

    /**
     * Every case [c] can be replayed on in [d] (oldest first; null: nothing stored can replay this kind of change). [sinceMs]:
     * only cases after it (an experiment's own); [limit]: only the newest so many managed trades are priced. A live change
     * reads the live records, any other the paper ones.
     */
    fun cases(c: AutoChange.Change, d: Data, sinceMs: Long = Long.MIN_VALUE, limit: Int = Int.MAX_VALUE): List<Case>? {
        val p = c.target.split(':')
        // The newest [limit] records only are priced (each exit's charges are worked out the paper account's way).
        fun newest(rs: List<TradeManager.Record>) = rs.sortedWith(compareBy<TradeManager.Record>({ recordAt(it) }, { it.trade.tradeId })).takeLast(limit)
        val out: List<Case> = when (c.kind) {
            AutoChange.Kind.MANAGER_MODE -> {
                if (p.size != 3) return null
                val acct = if (p[2] == "live") TradeManager.Account.LIVE else TradeManager.Account.PAPER
                val toAct = c.to == TradeManager.Mode.ACT.name
                if (!toAct && c.to != TradeManager.Mode.SHADOW.name) return null
                newest(d.records.filter { it.trade.family == p[1] && it.trade.account == acct && it.settled && recordAt(it) > sinceMs })
                    .mapNotNull { managerCase(it, managerWay = toAct) }
            }
            AutoChange.Kind.SPECIALIST_MUTE -> {
                if (p.size != 3 || p[0] != "spec") return null
                val acct = if (c.scope == AutoChange.Scope.LIVE) TradeManager.Account.LIVE else TradeManager.Account.PAPER
                val muting = c.to == AutoChange.MUTED
                newest(d.records.filter { it.trade.family == p[1] && it.trade.account == acct && it.settled && recordAt(it) > sinceMs && decisionLedBy(it, p[2]) })
                    .mapNotNull { managerCase(it, managerWay = !muting) }
            }
            AutoChange.Kind.PAPER_ENTRIES -> {
                val key = c.target.removePrefix("paper:")
                val pausing = c.to == AutoChange.PAUSED
                d.paperTrips.filter { AutoChange.paperArmOf(it.owner) == key }
                    .map { t -> Case(t.closedAt.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli(), t.owner + "@" + t.closedAt, if (pausing) t.net else 0.0, if (pausing) 0.0 else t.net) }
                    .filter { it.atMs > sinceMs }
            }
            AutoChange.Kind.FLOW_MODE -> {
                if (c.from != OrderFlow.Mode.CONFIRM.name || c.to != OrderFlow.Mode.SHADOW.name) return null
                val key = c.target.removePrefix("flow:")
                d.signals.filter { it.key == key && !it.live && it.agreement == OrderFlow.Agreement.DISAGREES && it.net != null && it.epochSec * 1000 > sinceMs }
                    .map { s -> Case(s.epochSec * 1000, s.id, 0.0, s.net ?: 0.0) }
            }
            else -> return null
        }
        return out.sortedWith(compareBy<Case>({ it.atMs }, { it.id }))
    }

    /** The rupees a change may give back and still pass: [TOL_MIN_RS], or [TOL_SHARE] of the sizes of [cases] without it. */
    fun tolerance(cases: List<Case>): Double = maxOf(TOL_MIN_RS, TOL_SHARE * cases.sumOf { abs(it.without) })

    /**
     * [c] replayed over the last [n] cases of [d], within [budgetMs] ([clockNs]: the time, in ns; tests may move it). Never
     * throws: a replay that fails says nothing is stored.
     */
    fun check(c: AutoChange.Change, d: Data, n: Int = N, budgetMs: Long = BUDGET_MS, clockNs: () -> Long = System::nanoTime): Result {
        val start = clockNs()
        val all = runCatching { cases(c, d, limit = n) }.getOrElse { emptyList() } ?: return Result(Verdict.NOT_REPLAYABLE, 0, 0.0, 0.0, 0.0, 0)
        val budgetNs = budgetMs * 1_000_000L
        if (clockNs() - start > budgetNs) return Result(Verdict.TIMEOUT, 0, 0.0, 0.0, 0.0, (clockNs() - start) / 1_000_000L)
        val last = all.takeLast(n)
        if (last.isEmpty()) return Result(Verdict.NO_DATA, 0, 0.0, 0.0, 0.0, (clockNs() - start) / 1_000_000L)
        var with = 0.0; var without = 0.0
        for ((k, x) in last.withIndex()) {
            with += x.with; without += x.without
            if (k % 8 == 7 && clockNs() - start > budgetNs) return Result(Verdict.TIMEOUT, k + 1, without, with, 0.0, (clockNs() - start) / 1_000_000L)
        }
        val tol = tolerance(last)
        val ms = (clockNs() - start) / 1_000_000L
        if (ms > budgetMs) return Result(Verdict.TIMEOUT, last.size, without, with, tol, ms)
        return Result(if (with >= without - tol) Verdict.PASSED else Verdict.FAILED, last.size, without, with, tol, ms)
    }
}
